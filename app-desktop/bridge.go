package main

// bridge.go — 本地端口映射桥 + 玩家中继 v2 转发器：
//   - guest：rudp 就绪后 net.Listen("tcp","127.0.0.1:0")，MC 一连上本地桥就立刻把
//     握手字节送进隧道（host 桥 lazy 依赖这个）；单客户端语义：新 MC 连入关闭旧的。
//   - host：lazy 桥——等隧道第一个 DATA（MC 握手字节）到达才 Dial 127.0.0.1:<hostPort>
//     （失败每 1s 重试，10s 窗口）；30s 零流量 watchdog 拆桥，之后下一段 DATA 再重拨。
//     多个 guest = 多条独立 rudp 会话各自桥一条 TCP。
//   - 中继转发（模组 RelayBridge.java 2026-10-05 v2 重写）：只做"流↔流"搬运，
//     不关心流由哪条通道承载；任一方向 EOF/出错即整个会话收尾、双腿互关。
//     v1 复用了候选者连 host 的传输裸流，两条 MC 会话字节交错进同一条有序流 +
//     InputStream 双读者互抢（1.0.x 起从未真正可用），已被本地复现实验实证并废除，
//     所以这里没有"对端没 mux 就退回裸流"的降级路径。

import (
	"errors"
	"fmt"
	"io"
	"net"
	"sync"
	"sync/atomic"
	"time"
)

const (
	bridgeBufSize     = 32 * 1024
	bridgeIdleTimeout = 30 * time.Second
	bridgeDialWindow  = 10 * time.Second
	bridgeDialRetry   = 1 * time.Second
	bridgeDialTimeout = 2 * time.Second

	// relayBufferSize 模组 RelayBridge.RELAY_BUFFER_SIZE
	relayBufferSize = 65536
)

// ===== joiner 侧载具热切（模组 P2PBridge.java:913-1076 移植） =====
// MC 客户端的 TCP 连接与本地桥端口全程不动，只换背后承载的 RUDP 传输。
// 字节流零丢失的锁纪律（PB:914-916）：上行泵写出口必须在 joinerSwapMu 内且暂停时 wait；
// 热切=暂停泵→排干旧出口积压→锁内二次排干+原子换引用→种子重放→重启 journal→唤醒泵→关旧。
//
// 锁形态映射：Java 的 JOINER_SWAP_LOCK 监视器 → joinerSwapMu + joinerSwapWake 唤醒通道；
// Object.wait(ms) → joinerSwapWaitLocked（等待期释放锁、到期或被 notifyAll 唤醒后重新持锁）。
var (
	joinerSwapMu sync.Mutex
	// joinerSwapWake 是 Java JOINER_SWAP_LOCK.notifyAll() 的通道形态：
	// 持锁时 close 旧通道并换新（joinerSwapNotifyAllLocked），等待方 select 它 + 超时。
	joinerSwapWake     = make(chan struct{})
	joinerCarrier      atomic.Pointer[rudpConn] // PB:917 volatile joinerCarrier
	joinerPumpPaused   atomic.Bool              // PB:918 volatile joinerPumpPaused
	joinerSessionAlive atomic.Bool              // PB:921 volatile joinerSessionAlive
	joinerServer       net.Listener             // PB joinerServer（Go 代理：guest 本地桥监听；joinerSwapMu 守护）
	joinerPort         = -1                     // PB:887 joinerPort（Java 初值 -1）
	joinerDownlinkSeen atomic.Bool              // PB:924 joinerDownlinkSeen
	// joinerBridgeRef 当前登记为"模组那个静态 joiner 桥"的 tcpBridge（joinerSwapMu 守护）。
	// Java 全进程只有一条静态桥；app 允许新旧 guest 桥短暂并存（打洞/TURN/中继竞速），
	// 泵据此判断自己是否还受热切纪律管辖（见 currentCarrierLocked）。
	joinerBridgeRef *tcpBridge
)

// 排干/暂停预算（毫秒，逐条照抄 PB:1022-1040、PB:1185/1269、PB:1247/1272）
const (
	joinerSwapDrainPhase2Ms = 3000  // PB:1022 ackDeadline
	joinerSwapDrainPollMs   = 20    // PB:1025 Thread.sleep(20L) / PB:1040 wait(20L)
	joinerSwapDrainPhase3Ms = 1000  // PB:1037 ackDeadline2
	joinerPumpWaitMs        = 200   // PB:1247/1272 JOINER_SWAP_LOCK.wait(200L)
	joinerSwapDeadWaitMs    = 15000 // PB:1185/1269 载具死亡等新载具上限
)

// joinerBridgeLogger 桥级日志出口（Java 静态 LOGGER 的 Go 对应；同 rudpConn.setLogf 形态，不挂则静默）。
var (
	joinerLogMu  sync.Mutex
	joinerLogfFn func(level, msg string)
)

// setJoinerBridgeLogf 由引擎接线（如 app.netLog）；传 nil 复位为静默。
func setJoinerBridgeLogf(fn func(level, msg string)) {
	joinerLogMu.Lock()
	joinerLogfFn = fn
	joinerLogMu.Unlock()
}

func joinerBridgeLog(level, msg string) {
	joinerLogMu.Lock()
	fn := joinerLogfFn
	joinerLogMu.Unlock()
	if fn != nil {
		fn(level, msg)
	}
}

// markJoinerDownlink / hasJoinerDownlink / resetJoinerDownlink（PB:926-936）：
// 下行首包标志——中继"真连接验证"用，服务器经中继回包到达即算该道活。
func markJoinerDownlink()     { joinerDownlinkSeen.Store(true) }
func hasJoinerDownlink() bool { return joinerDownlinkSeen.Load() }
func resetJoinerDownlink()    { joinerDownlinkSeen.Store(false) }

// ===== 连接期上行日志（PB:938-990，中继换道重放用） =====
// 从 MC 客户端读出的每一个字节都记账，直到"真连接验证"通过（下行首包/进世界）。
// 换道时整段重放到新载具：旧道未送达的握手字节不丢，MC 服务器看到完整全新握手。
// 验证通过后停止记账（活会话不允许重放语义）；上限 256KB 防失控。

const joinerJournalMaxBytes = 262144 // PB:942 JOURNAL_MAX_BYTES

var (
	journalMu             sync.Mutex  // PB:943 JOURNAL_LOCK
	joinerUplinkJournal   []byte      // PB:944（nil 对应 Java joinerUplinkJournal == null，即从未开账）
	joinerJournalActive   atomic.Bool // PB:945 volatile joinerJournalActive
	joinerJournalOverflow atomic.Bool // PB:946 volatile joinerJournalOverflow
)

// startJoinerJournal PB:948-954：锁内新建缓冲 + active=true + overflow=false。
func startJoinerJournal() {
	journalMu.Lock()
	defer journalMu.Unlock()
	joinerUplinkJournal = []byte{}
	joinerJournalActive.Store(true)
	joinerJournalOverflow.Store(false)
}

// stopJoinerJournal PB:956-958：只置 active=false（Java 就不加锁；黑名单第 4 条：只在验证通过时调）。
func stopJoinerJournal() {
	joinerJournalActive.Store(false)
}

// takeJoinerUplinkJournal PB:960-968：取当前日志快照（不停止记账；换道后由 startJoinerJournal 重置）。
// 语义分叉（PB:963-964，黑名单第 5 条）：溢出 → nil（本会话换道失去重放依据）；
// 从未开账/空账 → 非 nil 空切片。
func takeJoinerUplinkJournal() []byte {
	journalMu.Lock()
	defer journalMu.Unlock()
	if joinerJournalOverflow.Load() {
		return nil
	}
	if joinerUplinkJournal == nil {
		return []byte{}
	}
	out := make([]byte, len(joinerUplinkJournal))
	copy(out, joinerUplinkJournal)
	return out
}

// journalUplink PB:970-986：先无锁查 active，锁内复查 active||overflowed，超上限置溢出并停账。
// PB:988-990 的 joinerUpstreamOverflowed() 只是 overflow 位的读取，Go 内联为 joinerJournalOverflow.Load()。
func journalUplink(buf []byte, off, n int) {
	if !joinerJournalActive.Load() {
		return
	}
	journalMu.Lock()
	defer journalMu.Unlock()
	if !joinerJournalActive.Load() || joinerJournalOverflow.Load() {
		return
	}
	if len(joinerUplinkJournal)+n > joinerJournalMaxBytes {
		joinerJournalOverflow.Store(true)
		joinerJournalActive.Store(false)
		joinerBridgeLog("warn", fmt.Sprintf("[Bridge] joiner uplink journal overflow (%dB), relay swap disabled this session", joinerJournalMaxBytes))
		return
	}
	joinerUplinkJournal = append(joinerUplinkJournal, buf[off:off+n]...)
}

// getJoinerCarrier PB:993-995：joiner 桥当前承载（无桥=nil）。
func getJoinerCarrier() *rudpConn {
	return joinerCarrier.Load()
}

// joinerSwapWaitLocked Java JOINER_SWAP_LOCK.wait(ms) 的通道形态：须持 joinerSwapMu 调用；
// 等待期间释放锁（监视器语义），到期 ms 或被 joinerSwapNotifyAllLocked 唤醒后重新持锁再检查条件。
func joinerSwapWaitLocked(ms time.Duration) {
	wake := joinerSwapWake // 持锁捕获：notifyAll 也须在持锁下做，不会丢唤醒
	joinerSwapMu.Unlock()
	select {
	case <-wake:
	case <-time.After(ms):
	}
	joinerSwapMu.Lock()
}

// joinerSwapNotifyAllLocked Java JOINER_SWAP_LOCK.notifyAll()（PB:1065）：须持 joinerSwapMu 调用。
func joinerSwapNotifyAllLocked() {
	close(joinerSwapWake)
	joinerSwapWake = make(chan struct{})
}

// swapJoinerCarrier PB:1003-1076：热切 joiner 桥承载——排干旧传输出口积压 → 原子换载具 →
// 种子重放 → 关旧开新。seedJournal（连接期上行日志）必须在泵解除暂停前、在临界区内写入新载具——
// 否则泵恢复后先写未读字节，种子后到，字节序倒置即流损坏（PB:999-1001，本地实验实证）。
// 前置：本地桥已在跑（joinerServer/joinerPort 由 startGuestBridge 登记，停桥即摘，
// 对应 Java 的 joinerServer==null||isClosed()||joinerPort<=0 门）。返回是否成功。
func swapJoinerCarrier(newTransport *rudpConn, seedJournal []byte) bool {
	if newTransport == nil { // PB:1004-1006
		return false
	}
	joinerSwapMu.Lock()
	// PB:1008-1010 锁内前置。isClosed 的 Go 对应：桥停止时（监听关闭）注册即摘除，nil 即已关闭。
	if joinerServer == nil || joinerPort <= 0 {
		joinerSwapMu.Unlock()
		return false
	}
	oldT := joinerCarrier.Load()
	if oldT == newTransport { // PB:1012-1014 同载具幂等 no-op
		joinerSwapMu.Unlock()
		return true
	}
	// 阶段1：暂停泵（锁内置位，泵在锁内写出口，此刻不可能有在途写）（PB:1015-1017）
	joinerPumpPaused.Store(true)
	joinerSwapMu.Unlock()

	// 阶段2：锁外排干旧出口积压（泵已暂停；ack 由网络线程推进无需锁）（PB:1019-1031）。
	// 3000ms 预算、20ms 轮询、Thread.sleep → time.Sleep（不持锁，无监视器语义要复刻）。
	oldT = joinerCarrier.Load()
	if oldT != nil && oldT.isConnected() && oldT.hasPendingOutbound() {
		ackDeadline := time.Now().Add(time.Duration(joinerSwapDrainPhase2Ms) * time.Millisecond)
		for oldT.isConnected() && oldT.hasPendingOutbound() && time.Now().Before(ackDeadline) {
			time.Sleep(time.Duration(joinerSwapDrainPollMs) * time.Millisecond)
		}
	}

	// 阶段3（PB:1033-1075）：重新进锁。
	joinerSwapMu.Lock()
	defer joinerSwapMu.Unlock()
	oldT = joinerCarrier.Load()
	// 锁内二次排干（防阶段2结束后最后一批写入）：1000ms 预算、20ms 监视器等待（PB:1034-1046）。
	if oldT != nil && oldT != newTransport && oldT.isConnected() && oldT.hasPendingOutbound() {
		ackDeadline2 := time.Now().Add(time.Duration(joinerSwapDrainPhase3Ms) * time.Millisecond)
		for oldT.isConnected() && oldT.hasPendingOutbound() && time.Now().Before(ackDeadline2) {
			joinerSwapWaitLocked(time.Duration(joinerSwapDrainPollMs) * time.Millisecond)
		}
	}
	// PB:1047 activeUdpTransports.add(newTransport)：Go 形态的活跃传输表在引擎侧（conn_engine），
	// 桥层无此集合——由换道调用方登记/摘除（见移植报告 hunk）。
	joinerCarrier.Store(newTransport) // PB:1048
	// startGuestBridge 的载具生命周期钩子对齐到新载具：引擎主动关或会话空闲时关桥照旧；
	// 判死且在跑交还泵纪律（PB:914 全程不动 MC 的 TCP，15s 等换道由泵自己收口）。
	if jb := joinerBridgeRef; jb != nil {
		newTransport.setOnClosed(func(reason string) {
			if !jb.joinerCarrierCurrent(newTransport) {
				return
			}
			if jb.pumpLive.Load() && reason != "closed" {
				return
			}
			jb.stop()
		})
	}
	// PB:1049 newTransport.start()。模组 start() 有 connected CAS 幂等闸（RUDP:410-413），
	// Go 的 rudpConn.Start() 无闸（部分流程先手动置 started 再 Start），这里只补同款前置判。
	if !newTransport.started.Load() && !newTransport.closed.Load() {
		newTransport.Start()
	}
	// 种子重放：泵仍处暂停态，新载具上字节序 = 旧载具已交付流 + 种子 + 泵后续写入（PB:1050-1059）。
	// Java getOutputStream().write+flush → Go rudpConn.Write 即分片出口，无独立 flush 语义。
	if seedJournal != nil && len(seedJournal) > 0 {
		if _, err := newTransport.Write(seedJournal); err != nil {
			joinerBridgeLog("warn", "[Bridge] journal seed write failed: "+err.Error()) // PB:1057
		} else {
			joinerBridgeLog("info", fmt.Sprintf("[Bridge] joiner carrier seeded with %d journal bytes", len(seedJournal))) // PB:1055
		}
	}
	// 换道即重启 journal（此刻泵仍被 joinerSwapMu 暂停，无竞态）（PB:1060-1062，F4）：
	// 不重启的话二次换道 take 会把旧快照+已交付字节整段重放=字节重复流损坏。
	startJoinerJournal()
	var closedT *rudpConn // PB:1063：只关"确实换了出去"的旧载具
	if oldT != nil && oldT != newTransport {
		closedT = oldT
	}
	joinerPumpPaused.Store(false) // PB:1064
	joinerSwapNotifyAllLocked()   // PB:1065 notifyAll：唤醒暂停等待中的上行泵
	if closedT != nil {
		// PB:1066-1072：解除暂停之后才关旧载具（activeUdpTransports.remove 归引擎侧集合，见 hunk）。
		_ = closedT.Close()
	}
	joinerBridgeLog("info", "[Bridge] joiner carrier swapped (old pending drained)") // PB:1073
	return true
}

// stopJoinerSession PB:774：disconnect() 清 joinerSessionAlive。
// Go 形态的"会话终结"在引擎侧（guest 桥拆/退房），由引擎调用；泵的两个 15s 等待窗据此收口。
func stopJoinerSession() {
	joinerSessionAlive.Store(false)
}

// registerJoinerBridge 模组 startUdpJoinerBridge（PB:872-896）在 Go 的落位：startGuestBridge
// 成功建监听后登记 1.2.0 新增的三件（resetJoinerDownlink PB:875、joinerSessionAlive=true PB:889、
// joinerCarrier=transport PB:891）与热切门要的 joinerServer/joinerPort（PB:886-887）。
// transport.start()（PB:892）不在这里做：Go 的引擎在调用 startGuestBridge 前已 Start。
func registerJoinerBridge(b *tcpBridge, ln net.Listener, rc *rudpConn) {
	port := -1
	if ta, ok := ln.Addr().(*net.TCPAddr); ok {
		port = ta.Port
	}
	resetJoinerDownlink() // PB:875
	joinerSwapMu.Lock()
	joinerSessionAlive.Store(true) // PB:889
	joinerCarrier.Store(rc)        // PB:891
	joinerServer = ln              // PB:886-887
	joinerPort = port
	joinerBridgeRef = b
	joinerSwapMu.Unlock()
}

// unregisterJoinerBridge 模组 acceptUdpJoinerConnections 收尾（PB:1142-1146）：
// "joinerServer 仍是这一条监听"才摘（同 Java 的 joinerServer == js 判据），
// 晚到的清理不得误摘新桥的登记；一并落 PB:774 disconnect 的 joinerSessionAlive=false。
func unregisterJoinerBridge(b *tcpBridge, ln net.Listener) {
	joinerSwapMu.Lock()
	if joinerServer == ln {
		joinerServer = nil
		joinerPort = -1
		joinerSessionAlive.Store(false)
		if joinerBridgeRef == b {
			joinerBridgeRef = nil
		}
	}
	joinerSwapMu.Unlock()
}

// currentCarrierLocked 该 joiner 泵此刻应使用的承载（须持 joinerSwapMu）：
// 仍是全局登记的桥 → 跟热切走（joinerCarrier，可被 swapJoinerCarrier 原子替换，PB:1253/1159）；
// 已被更新的桥取代 → 回落自己绑定的承载，绝不把字节写进别人刚换道的新道。
func (b *tcpBridge) currentCarrierLocked() *rudpConn {
	if joinerBridgeRef == b {
		return joinerCarrier.Load()
	}
	return b.ownCar
}

// currentCarrier 加锁版取值。
func (b *tcpBridge) currentCarrier() *rudpConn {
	joinerSwapMu.Lock()
	defer joinerSwapMu.Unlock()
	return b.currentCarrierLocked()
}

// joinerCarrierCurrent rc 此刻是否仍是"这条登记在跑的 joiner 桥"的承载：
// 被热切换出（PB:1048 已换引用）或被新桥取代（joinerBridgeRef 易主）都返回 false——
// 两者的关闭都不许连坐桥。
func (b *tcpBridge) joinerCarrierCurrent(rc *rudpConn) bool {
	joinerSwapMu.Lock()
	defer joinerSwapMu.Unlock()
	return joinerBridgeRef == b && joinerCarrier.Load() == rc
}

// isCurrentJoinerBridge 这条桥是否仍占据"模组那条静态 joiner 桥"的登记位。
func (b *tcpBridge) isCurrentJoinerBridge() bool {
	joinerSwapMu.Lock()
	defer joinerSwapMu.Unlock()
	return joinerBridgeRef == b
}

// journalUplinkIfCurrent 需求 6：joiner 上行泵读到的字节入账。
// 被新桥取代的旧桥不得替会话记账——否则它的字节会被当成本会话上行日志整段重放到新桥载具上；
// 登记在位时与 Java 的无条件 journalUplink（PB:1242，同步块外、写入出口前）等价。
func (b *tcpBridge) journalUplinkIfCurrent(buf []byte, off, n int) {
	if b.isCurrentJoinerBridge() {
		journalUplink(buf, off, n)
	}
}

// joinerStopped Java 泵循环里的 running.get()/载体判据在 Go 的对应：桥已收尾即停。
func joinerStopped(stop <-chan struct{}) bool {
	select {
	case <-stop:
		return true
	default:
		return false
	}
}

// joinerSleepOrStop Java Thread.sleep 的可打断形态：stop 关闭即刻返回 false。
func joinerSleepOrStop(stop <-chan struct{}, d time.Duration) bool {
	select {
	case <-stop:
		return false
	case <-time.After(d):
		return true
	}
}

// bridgeStream 桥一端的字节流：读可被 abort 打断、写按隧道分片。
// rudp 整传输与 mux 子通道都满足它，因此同一段桥逻辑既能承载自有会话也能承载被中继会话。
type bridgeStream struct {
	in     func(p []byte, abort <-chan struct{}) (int, error)
	out    func(p []byte) (int, error)
	closer func()          // 会话级收尾：整传输腿关整条隧道，子通道腿只 closeSubstream
	done   <-chan struct{} // 承载它的传输终止信号
}

// rudpBridgeStream 整传输腿（模组 RelayBridge.legOf）：被关闭的就是这条会话本身。
func rudpBridgeStream(rc *rudpConn) *bridgeStream {
	return &bridgeStream{
		in:     rc.readAbort,
		out:    rc.Write,
		closer: func() { _ = rc.Close() },
		done:   rc.doneCh,
	}
}

// rudpMuxRelayStream mux 子通道腿（模组 CM:13360-13378 的匿名 RelayLeg）：
// 收尾只关子通道，绝不关父传输——候选者自己的会话（chan 0）不能被被中继的人连坐。
func rudpMuxRelayStream(rc *rudpConn, ch int) *bridgeStream {
	sc := rc.substream(ch)
	return &bridgeStream{
		in:     sc.read,
		out:    sc.Write,
		closer: func() { rc.closeSubstream(ch) },
		done:   rc.doneCh,
	}
}

// tcpBridge 一条 MC TCP 连接 ↔ 隧道流（rudp 会话或 mux 子通道）的搬运桥。
type tcpBridge struct {
	stream   *bridgeStream
	ln       net.Listener // guest 模式才有
	tcp      atomic.Pointer[net.Conn]
	lastAct  atomic.Int64
	stopOnce sync.Once
	stopCh   chan struct{}
	downCb   func()      // MC 侧连接断开（pump 退出）时回调
	joiner   bool        // joiner 侧桥（模组 acceptUdpJoinerConnections 形态）：载具热切纪律生效
	ownCar   *rudpConn   // joiner 泵自己绑定的承载（被更新桥取代后回落到它，见 currentCarrierLocked）
	pumpLive atomic.Bool // joiner 泵在跑（MC 会话进行中）：载具死亡交还给泵的 15s 换道等待窗（PB:1185/1269）
}

func newTcpBridge(stream *bridgeStream, downCb func()) *tcpBridge {
	return &tcpBridge{stream: stream, stopCh: make(chan struct{}), downCb: downCb}
}

func (b *tcpBridge) touch() { b.lastAct.Store(time.Now().UnixMilli()) }

// idleMs 距最近一次流量的毫秒数。
func (b *tcpBridge) idleMs() int64 { return time.Now().UnixMilli() - b.lastAct.Load() }

// stop 关闭监听与当前 TCP 连接（不动承载它的隧道本身）。幂等。
func (b *tcpBridge) stop() {
	b.stopOnce.Do(func() {
		close(b.stopCh)
		if ln := b.ln; ln != nil {
			_ = ln.Close()
		}
		if c := b.tcp.Load(); c != nil {
			_ = (*c).Close()
		}
	})
}

func (b *tcpBridge) setConn(conn net.Conn, old *net.Conn) {
	if old != nil {
		_ = (*old).Close()
	}
	b.tcp.Store(&conn)
	b.touch()
}

// startGuestBridge guest 侧：监听 127.0.0.1 随机端口，返回本地代理地址。
// downCb：MC 侧断开（或空闲拆桥）时回调（引擎据此复位 p2p 状态并关 rudp）。
func startGuestBridge(rc *rudpConn, downCb func()) (string, *tcpBridge, error) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return "", nil, fmt.Errorf("本地端口监听失败: %w", err)
	}
	b := newTcpBridge(rudpBridgeStream(rc), downCb)
	b.ln = ln
	b.touch()
	b.joiner = true // 模组形态：这条桥走 joiner 泵纪律（热切锁 + 上行日志），见 pumpJoiner
	b.ownCar = rc

	// 载具关闭 → 是否拆桥，按模组泵纪律收口（PB:914 MC 的 TCP 与本地端口全程不动）：
	// 	· 被热切换出去而关（swapJoinerCarrier PB:1066-1072 关旧）：carrier 已指向新道 → 不关桥；
	// 	· 判死类关闭（closeWith 带 reason：对端断开/静默判死/重传超限）且会话在跑：这条道可能
	// 	  马上被换道接住，泵在锁内等换道最多 15s（PB:1181-1194/1266-1280），这里不能抢跑掐 MC 连接；
	// 	· 引擎主动 Close（reason 缺省 "closed"，无换道意图）或无 MC 会话在跑：维持 app 既有
	// 	  "隧道关即拆桥"形态，downCb 即时回引擎。
	rc.setOnClosed(func(reason string) {
		if !b.joinerCarrierCurrent(rc) {
			return
		}
		if b.pumpLive.Load() && reason != "closed" {
			return // 判死：15s 换道等待窗由泵收口（窗内无换道 → 泵退出 → stopBoth 拆桥）
		}
		b.stop()
	})

	registerJoinerBridge(b, ln, rc)

	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				unregisterJoinerBridge(b, ln) // PB:1142-1146：监听收尾摘除登记
				return                        // 监听关闭（桥停止）
			}
			if tc, ok := conn.(*net.TCPConn); ok {
				_ = tc.SetNoDelay(true) // MC 握手字节必须立刻进隧道
			}
			b.setConn(conn, b.tcp.Load())
			// PB:1126-1127：上行泵跑在 accept 线程上，一条桥只服务一个 MC 连接；泵一结束
			// PB:1087 的循环条件就不再成立 → 关监听退出。并发 accept 会让旧连接的收尾
			// 通过 b.tcp.Load() 误关新连接，这里从形态上杜绝。
			b.pump(conn)
			b.stop()
			unregisterJoinerBridge(b, ln)
			return
		}
	}()
	return ln.Addr().String(), b, nil
}

// startHostLazyBridge host 侧：等隧道第一个 DATA（MC 握手字节）到达才拨号本地 MC 端口，
// 失败每 1s 重试共 10s 窗口；30s 零流量 watchdog 拆桥，之后可由下一段 DATA 触发重拨。
func startHostLazyBridge(rc *rudpConn, hostPort int, logf func(level, msg string)) {
	startHostLazyBridgeOnStream(rudpBridgeStream(rc), hostPort, logf, nil)
}

// startHostMuxRelayBridge host 侧被中继房客桥（模组 P2PBridge.startUdpHostBridgeForRelayedClient）：
// 候选者在其 joiner 传输上分配的 mux 子通道 ↔ 本地 MC 端口。桥死只收尾子通道，
// 承载它的候选者传输（连同候选者自己的会话）必须活着。
func startHostMuxRelayBridge(rc *rudpConn, muxChan, hostPort int, logf func(level, msg string), onClose func()) {
	startHostLazyBridgeOnStream(rudpMuxRelayStream(rc, muxChan), hostPort, logf, onClose)
}

// startHostLazyBridgeOnStream lazy 桥主体，流可以是整传输也可以是子通道。
// onClose：会话级收尾（子通道桥用它通知上层这条被中继会话结束了；整传输桥传 nil）。
func startHostLazyBridgeOnStream(stream *bridgeStream, hostPort int, logf func(level, msg string), onClose func()) {
	go func() {
		buf := make([]byte, bridgeBufSize)
		for {
			n, err := stream.in(buf, nil) // 阻塞等 MC 握手字节（KEEPALIVE 维持隧道活性）
			if err != nil || n == 0 {
				if onClose != nil {
					onClose()
				}
				return // 隧道关闭
			}
			conn := bDialLocalMC(hostPort, stream, logf)
			if conn == nil {
				if logf != nil {
					logf("error", "host 桥：无法连接本地 MC 端口，关闭该条隧道")
				}
				stream.closer()
				if onClose != nil {
					onClose()
				}
				return
			}
			b := newTcpBridge(stream, nil)
			b.setConn(conn, nil)
			// 首包（MC 握手字节）先写入，再进入双向搬运
			if _, err := conn.Write(buf[:n]); err != nil {
				_ = conn.Close()
				stream.closer()
				if onClose != nil {
					onClose()
				}
				return
			}
			go b.pump(conn)
			// watchdog 判定 30s 零流量：拆桥回到等待下一段 DATA 的状态
			idle := b.watchdogOnce()
			b.stop()
			if !idle {
				if onClose != nil {
					onClose()
				}
				return // 隧道已关闭
			}
			if logf != nil {
				logf("info", "host 桥：长时间无流量，拆桥待重拨")
			}
		}
	}()
}

// bDialLocalMC 拨号本地 MC 端口，失败每 1s 重试，总窗口 10s。
func bDialLocalMC(hostPort int, stream *bridgeStream, logf func(level, msg string)) net.Conn {
	deadline := time.Now().Add(bridgeDialWindow)
	for {
		d := bridgeDialTimeout
		if remain := time.Until(deadline); remain < d {
			d = remain
		}
		if d <= 0 {
			return nil
		}
		conn, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", hostPort), d)
		if err == nil {
			if tc, ok := conn.(*net.TCPConn); ok {
				_ = tc.SetNoDelay(true)
			}
			return conn
		}
		if logf != nil {
			logf("warn", "host 桥：连接本地 MC 端口失败，稍后重试")
		}
		if time.Now().Add(bridgeDialRetry).After(deadline) {
			return nil
		}
		select {
		case <-stream.done:
			return nil
		case <-time.After(bridgeDialRetry):
		}
	}
}

// pump 双向搬运：TCP → 隧道、隧道 → TCP。任一方向出错即双向退出，
// 并回调 downCb（guest 引擎据此复位；host 侧借隧道关闭传导）。
// joiner 桥（b.joiner）走热切纪律版泵（模组 acceptUdpJoinerConnections PB:1126-1127
// 提交的 bridgeCarrierToMcJoiner / bridgeMcToCarrierJoiner 两条泵），其余保持通用形态。
func (b *tcpBridge) pump(conn net.Conn) {
	if b.joiner {
		b.pumpJoiner(conn)
		return
	}
	done := make(chan struct{})
	var once sync.Once
	stopBoth := func() {
		once.Do(func() {
			close(done)
			_ = conn.Close()
			b.stop()
		})
	}

	go func() { // TCP → 隧道
		defer stopBoth()
		buf := make([]byte, bridgeBufSize)
		for {
			n, err := conn.Read(buf)
			if n > 0 {
				b.touch()
				if _, werr := b.stream.out(buf[:n]); werr != nil {
					return
				}
			}
			if err != nil {
				return
			}
			select {
			case <-b.stopCh:
				return
			default:
			}
		}
	}()
	go func() { // 隧道 → TCP
		defer stopBoth()
		buf := make([]byte, bridgeBufSize)
		for {
			n, err := b.stream.in(buf, b.stopCh)
			if n > 0 {
				b.touch()
				if _, werr := conn.Write(buf[:n]); werr != nil {
					return
				}
			}
			if err != nil {
				return
			}
		}
	}()
	<-done
	if cb := b.downCb; cb != nil {
		cb()
	}
}

// pumpJoiner joiner 桥双向搬运（模组 acceptUdpJoinerConnections 循环体 PB:1123-1127 的骨架）：
// 下行泵 bridgeCarrierToMcJoiner、上行泵 bridgeMcToCarrierJoiner 各一条，任一退出即整体收尾
// （沿用 app 既有 stopBoth+downCb 生命周期；Java 泵回 accept 后关监听的效果由 stop() 关 ln、
// accept 循环退出时的 unregisterJoinerBridge 承接）。
func (b *tcpBridge) pumpJoiner(conn net.Conn) {
	done := make(chan struct{})
	var once sync.Once
	stopBoth := func() {
		once.Do(func() {
			close(done)
			_ = conn.Close()
			b.stop()
		})
	}
	b.pumpLive.Store(true)
	defer b.pumpLive.Store(false)

	go func() { // 载体 → MC（PB:1126 exec.submit(bridgeCarrierToMcJoiner)）
		defer stopBoth()
		b.joinerDownlink(conn, done)
	}()
	go func() { // MC → 载体（PB:1127 bridgeMcToCarrierJoiner：Java 在 accept 线程原地跑，
		// Go 补一层 goroutine 保持与既有 pump 的双向收尾形态一致）
		defer stopBoth()
		b.joinerUplink(conn, done)
	}()
	<-done
	if cb := b.downCb; cb != nil {
		cb()
	}
}

// joinerUplink joiner 上行泵（PB:1218-1291）：MC 客户端 → 中继/host。
// 写出口在热切锁内进行（暂停时 wait），热切换载续搬；每段实际读到字节先入上行日志。
func (b *tcpBridge) joinerUplink(mcConn net.Conn, stop <-chan struct{}) {
	buf := make([]byte, bridgeBufSize)
	for !joinerStopped(stop) { // PB:1225 while (running.get() && !mcSocket.isClosed())
		n, err := mcConn.Read(buf)
		if n > 0 {
			b.touch()
			b.journalUplinkIfCurrent(buf, 0, n) // PB:1242：写入出口前先记账（换道重放的依据）
			if !b.joinerWrite(buf[:n], stop) {
				return
			}
		}
		if err != nil {
			if joinerStopped(stop) {
				return
			}
			if errors.Is(err, io.EOF) { // PB:1227-1236：MC 关闭 → 关当前载具、退出
				joinerBridgeLog("info", "MC->UDP joiner pump end: MC closed")
				if t2 := b.currentCarrier(); t2 != nil {
					_ = t2.Close()
				}
				return
			}
			// PB:1238-1240 的 bytesRead<=0 continue 在 Go 读语义下无独立形态；
			// 其余错误落 PB:1286-1289 外层 catch（running 为真才记日志）
			joinerBridgeLog("info", "MC->UDP joiner pump closed: "+err.Error())
			return
		}
	}
}

// joinerWrite 上行泵写出口（PB:1244-1284），全程持 joinerSwapMu（= Java 监视器同步块）：
// 暂停时 wait(200L)；写出走当前载具；写失败按"热切竞速/载具死亡"两路处置。
// 返回 false 表示泵退出（Java return），true 表示 continue readLoop。
// 泵纪律只管辖登记中的那条 joiner 桥（Java 全进程仅一条静态桥）；被新桥取代的旧桥
// 回落到自己的承载，热切换道不再对它生效（currentCarrierLocked）。
func (b *tcpBridge) joinerWrite(p []byte, stop <-chan struct{}) bool {
	joinerSwapMu.Lock()
	defer joinerSwapMu.Unlock()
	registered := joinerBridgeRef == b
	if registered {
		// PB:1245-1252：while (joinerPumpPaused && running.get()) JOINER_SWAP_LOCK.wait(200L)
		for joinerPumpPaused.Load() && !joinerStopped(stop) {
			joinerSwapWaitLocked(time.Duration(joinerPumpWaitMs) * time.Millisecond)
		}
	}
	t := b.currentCarrierLocked() // PB:1253：锁内取当前载具（换道后写新道）
	if t == nil {
		return true // PB:1254-1256 continue readLoop
	}
	_, werr := t.Write(p) // PB:1258-1259 write+flush：Go 的 rudpConn.Write 即分块出口，无独立 flush 语义
	if werr == nil {
		return true
	}
	if t != b.currentCarrierLocked() { // PB:1261-1264：热切竞速——绝不向新载具重发本段；
		// journal 在写入前已记账，换道重放会完整补上，此处重发会与重放产生字节重复
		return true
	}
	if registered {
		// PB:1266-1280：当前载具死亡——字节已在 journal 里（写入前记账），桥还活着=可能换道
		// 挂起中，锁内监视器等待换载最多 15s；换载成功则本段由 journal 重放覆盖。
		waitStart := time.Now()
		for time.Since(waitStart) < time.Duration(joinerSwapDeadWaitMs)*time.Millisecond &&
			!joinerStopped(stop) && joinerCarrier.Load() == t && joinerSessionAlive.Load() {
			joinerSwapWaitLocked(time.Duration(joinerPumpWaitMs) * time.Millisecond)
		}
		if joinerCarrier.Load() != t {
			return true // PB:1278-1279 换载成功，continue readLoop
		}
	}
	joinerBridgeLog("info", "MC->UDP joiner pump end: "+werr.Error()) // PB:1281-1282
	return false
}

// joinerDownlink joiner 下行泵（PB:1150-1215）：中继/host → MC 客户端。
// 旧载具 EOF/异常时若桥仍存活则等待换道（15s 上限），换载续搬。
func (b *tcpBridge) joinerDownlink(mcConn net.Conn, stop <-chan struct{}) {
	buf := make([]byte, bridgeBufSize)
	carrierDeadSince := int64(0) // PB:1156
	for !joinerStopped(stop) {   // PB:1158 while (running.get() && !mcSocket.isClosed())
		t := b.currentCarrier() // PB:1159：每轮取当前载具（热切后续搬新道；被取代的旧桥回落自身承载）
		if t == nil {
			if !joinerSleepOrStop(stop, 50*time.Millisecond) { // PB:1160-1167 sleep(50L)
				return
			}
			continue
		}
		n, err := t.readAbort(buf, stop) // PB:1172 t.getInputStream().read(buffer)
		if err != nil {
			// Java 把 read 的 IOException 一律视作 -1（PB:1173-1175）；Go 读侧的中断
			// （stop 关闭 / errRudpAborted）先收口，其余错误进 -1 分支。
			if joinerStopped(stop) || errors.Is(err, errRudpAborted) {
				return
			}
			if t != b.currentCarrier() { // PB:1177-1180：载具已换，清零死锚、重取续读
				carrierDeadSince = 0
				continue
			}
			// PB:1181-1194：当前载具死亡——桥还活着可能是换道挂起中，15s 内等新载具
			if carrierDeadSince == 0 {
				carrierDeadSince = time.Now().UnixMilli()
			}
			if time.Now().UnixMilli()-carrierDeadSince < joinerSwapDeadWaitMs &&
				!joinerStopped(stop) && joinerSessionAlive.Load() {
				if !joinerSleepOrStop(stop, 100*time.Millisecond) { // PB:1187-1193 sleep(100L)
					return
				}
				continue
			}
			joinerBridgeLog("info", "UDP->MC joiner pump end: carrier dead, no swap in 15s") // PB:1195
			return
		}
		carrierDeadSince = 0 // PB:1198
		if n > 0 {
			// PB:1200 下行首包 = 中继"真连接验证"的该道活证据。Java 全进程只有那一条静态
			// joiner 泵；Go 侧被新桥取代的旧桥不得替会话点亮这个标志。
			if b.isCurrentJoinerBridge() {
				markJoinerDownlink()
			}
			b.touch()
			if _, werr := mcConn.Write(buf[:n]); werr != nil { // PB:1201-1202 mcOut.write+flush
				if !joinerStopped(stop) { // PB:1205-1208 外层 catch
					joinerBridgeLog("info", "UDP->MC joiner pump closed: "+werr.Error())
				}
				return
			}
		}
	}
	// PB:1209-1213 finally mcSocket.shutdownOutput()：Go net.Conn 没有对应的输出半关闭
	// 原语（TCPConn.CloseWrite 需类型断言且既有 pump 生命周期从不半关），由 pumpJoiner
	// 收尾的 conn.Close() 承担——见移植报告"未能逐语句复刻"条。
}

// watchdogOnce 每 5s 检查一次，30s 零流量返回 true（调用方拆桥）。
// 隧道关闭（stopCh 关闭）返回 false。
func (b *tcpBridge) watchdogOnce() bool {
	t := time.NewTicker(5 * time.Second)
	defer t.Stop()
	for {
		select {
		case <-b.stopCh:
			return false
		case <-t.C:
			if b.idleMs() > bridgeIdleTimeout.Milliseconds() {
				return true
			}
		}
	}
}

// ---- 玩家中继 v2 转发器（模组 network/RelayBridge.java）----

// relayLeg 一条被转发的腿：给出来往流与关闭动作。
// 子通道腿的 close 只 closeSubstream，不动父传输；整传输腿（对被中继房客的专打传输）关整条。
type relayLeg struct {
	stream *bridgeStream
}

// relayBridge 中继转发会话表（模组 RelayBridge 单例）。
// v2 只做流↔流转发：被中继会话走候选者↔host 传输的 mux 子通道（chan 1..7），
// 与候选者自有会话（chan 0）字节级隔离。
type relayBridge struct {
	logf   func(level, msg string)
	mu     sync.Mutex
	active map[string]*relaySession
}

var (
	relayBridgeMu    sync.Mutex
	relayBridgeValue *relayBridge
)

// getRelayBridge 进程内单例（模组 RelayBridge.getInstance(scheduler)：首次调用者定 logger）。
func getRelayBridge(logf func(level, msg string)) *relayBridge {
	relayBridgeMu.Lock()
	defer relayBridgeMu.Unlock()
	if relayBridgeValue == nil {
		relayBridgeValue = &relayBridge{logf: logf, active: map[string]*relaySession{}}
	}
	return relayBridgeValue
}

// relayBridgeIfStarted 实例尚未建立时返回 nil（模组 stop() 里 getInstance(null) != null 那道守卫：
// 本端从没当中继节点时不该去动任何会话表）。
func relayBridgeIfStarted() *relayBridge {
	relayBridgeMu.Lock()
	defer relayBridgeMu.Unlock()
	return relayBridgeValue
}

func (b *relayBridge) log(level, msg string) {
	if b != nil && b.logf != nil {
		b.logf(level, msg)
	}
}

// startRelay 建立一条中继转发会话：A 腿与 B 腿双向泵流。
// 任一方向读到 EOF/出错即停止整个会话并关闭双腿；重复 key 静默忽略（幂等屏障：
// 同一 key 起两次会有两套泵抢同一条流，字节直接被切碎）。
func (b *relayBridge) startRelay(relayKey, peerAID, peerBID string, legA, legB *relayLeg) {
	if relayKey == "" || legA == nil || legB == nil || legA == legB {
		b.log("warn", "[Relay] Reject invalid relay session: "+relayKey)
		return
	}
	session := &relaySession{owner: b, key: relayKey, peerAID: peerAID, peerBID: peerBID, legA: legA, legB: legB}
	b.mu.Lock()
	if _, ok := b.active[relayKey]; ok {
		b.mu.Unlock()
		b.log("info", "[Relay] Relay session already exists: "+relayKey)
		return
	}
	b.active[relayKey] = session
	b.mu.Unlock()
	session.startForwarding()
	b.log("info", fmt.Sprintf("[Relay] Relay started: %s (A=%s, B=%s)", relayKey, peerAID, peerBID))
}

// relayCountForPeer 该对端当前在转发几条会话（模组派发候选时按它排序取最闲的）。
func (b *relayBridge) relayCountForPeer(peerID string) int {
	count := 0
	b.mu.Lock()
	defer b.mu.Unlock()
	for _, s := range b.active {
		if s.peerAID == peerID || s.peerBID == peerID {
			count++
		}
	}
	return count
}

// stopAllRelays 拆掉全部中继会话（本端退房/停止中继时）。
func (b *relayBridge) stopAllRelays() {
	b.mu.Lock()
	sessions := make([]*relaySession, 0, len(b.active))
	for _, s := range b.active {
		sessions = append(sessions, s)
	}
	b.mu.Unlock()
	if len(sessions) == 0 {
		return
	}
	b.log("info", fmt.Sprintf("[Relay] Stopping %d relay sessions", len(sessions)))
	for _, s := range sessions {
		s.stop()
	}
	b.mu.Lock()
	clear(b.active)
	b.mu.Unlock()
}

// relaySession A↔B 一条转发会话：两条泵，任一退出即整体收尾。
type relaySession struct {
	owner      *relayBridge
	key        string
	peerAID    string
	peerBID    string
	legA       *relayLeg
	legB       *relayLeg
	forwarding atomic.Bool
	done       chan struct{} // 泵线程的打断闸（Java thread.interrupt 的 Go 形态）
	interrupt  sync.Once
}

// relayLegOf 包一层，便于调用处表达"这条腿是整传输/子通道"。
func relayLegOf(stream *bridgeStream) *relayLeg { return &relayLeg{stream: stream} }

func (s *relaySession) startForwarding() {
	s.forwarding.Store(true)
	s.done = make(chan struct{})
	go s.pump("A->B", s.legA.stream, s.legB.stream)
	go s.pump("B->A", s.legB.stream, s.legA.stream)
}

// pump 单向搬运：读到的字节原样写进对腿。EOF/出错/会话已停即退出并收尾整个会话。
func (s *relaySession) pump(dir string, from, to *bridgeStream) {
	buf := make([]byte, relayBufferSize)
	for s.forwarding.Load() {
		n, err := from.in(buf, s.done)
		if err == nil && n > 0 {
			_, err = to.out(buf[:n])
		}
		if err != nil {
			if s.forwarding.Load() {
				s.owner.log("warn", "[Relay] "+dir+" end: "+err.Error())
			}
			break
		}
	}
	s.stop()
}

// stop 会话收尾：CAS 幂等（两条腿都会调，只生效一次），关双腿、摘表、打断另一条泵。
func (s *relaySession) stop() {
	if s.forwarding.CompareAndSwap(true, false) {
		s.owner.log("info", "[Relay] Relay session stopped: "+s.key)
		s.legA.stream.closer()
		s.legB.stream.closer()
		// 模组此处用 getInstance(null) != null 做"实例已存在"守卫，摘的是 owner 的表：
		// 本端从没当中继节点（单例没建过）时不该动任何会话表
		if relayBridgeIfStarted() != nil {
			s.owner.removeSession(s.key)
		}
	}
	// CAS 之外无条件打断：晚到的第二条泵也必须从阻塞读里出来
	s.interrupt.Do(func() {
		if s.done != nil {
			close(s.done)
		}
	})
}

// removeSession 从会话表摘除（key 相同才摘，避免误摘同 key 的新会话）。
func (b *relayBridge) removeSession(key string) {
	b.mu.Lock()
	delete(b.active, key)
	b.mu.Unlock()
}
