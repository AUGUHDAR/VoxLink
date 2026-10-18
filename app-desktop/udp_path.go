package main

// udp_path.go — 传输层"路径"抽象与双路径平滑切换（make-before-break）。
//
// 对齐两处模组源码：
//   - network/UdpPath.java（52 行）：一对 (socket, 对端地址) + 可选包封编解码器，
//     加收包健康观测计数（rxCount/lastRxMs，:20-22 注释点明它是 BackgroundPunchMonitor
//     判"能否升级为主路径"的依据）。
//   - network/ReliableUdpTransport.java 的切换段（:1352-1455）：
//     sendOnPrimary :1358-1377 / addSecondaryPath :1384-1400 / getSecondaryPath :1402-1404 /
//     getPrimaryPath :1406-1408 / getSecondaryRxCount :1411-1414 / getSecondaryLastRxMs :1416-1420 /
//     promoteSecondaryPath :1426-1437 / dropInactivePath :1439-1455 / recvPathLoop :592-635 /
//     processDatagram 里的 rx 记账 :679-680、:685-686、:706-707、:732-733。
//
// 语义（:12-13 类注释原文要义）：同一会话下 primary/secondary 两条路径并存，
// 双路径收包喂同一个序号空间（重复包由 nextExpectedSeq 天然去重，对端无感知，
// 两端各自独立 promote，永不脑裂）；发送永远走 primary，promote 后 10s 宽限期内新旧双发，
// 切换瞬间在途包零丢失。
//
// 为什么状态放在包级表而不是 rudpConn 字段：本轮文件归属规定我不能改 rudp.go，
// 而 primary/secondary/graceUntil 是 rudpConn 的状态。故用 sync.Map 以 *rudpConn 为键
// 承载同一份状态，rudp.go 只需三处接线（交付报告 R3/R4/R5）：
//   sendFrame 的出口改走 sendOnPrimary、recvLoop 对主路径记 rx、Close 里关掉次路径 socket。
// 在接线之前本套代码不参与任何现有流程（新路径不会被挂载），线上字节不变。

import (
	"errors"
	"fmt"
	"net"
	"sync"
	"sync/atomic"
	"time"
)

const (
	// rudpSwitchGrace 切主后的双发宽限 10s（ReliableUdpTransport.java:1435
	// `switchGraceUntilMs = now + 10000L`；调用侧 CM:12432 在 11s 后才 drop，留 1s 余量）。
	rudpSwitchGrace = 10000 * time.Millisecond
	// rudpSecondaryReadTimeout 次路径 socket 的读超时（:1390 sock.setSoTimeout(100)）。
	// 主路径用 rudp.go 既有的 rudpTick(50ms) 粒度，本端沿用模组次路径的 100ms。
	rudpSecondaryReadTimeout = 100 * time.Millisecond
	// rudpMaxPacketSize recvPathLoop 的读缓冲（:593 new byte[1454]）。
	rudpPathReadBufSize = 1454
)

// udpPath 对应 UdpPath.java:15-42。
type udpPath struct {
	conn   *net.UDPConn
	remote atomic.Pointer[net.UDPAddr]
	codec  rudpPathCodec // nil=直连裸跑（rudp 帧不包信封）

	rxCount  atomic.Int64 // :21
	lastRxMs atomic.Int64 // :22（0=从未）
}

func newUDPPath(conn *net.UDPConn, remote *net.UDPAddr, codec rudpPathCodec) *udpPath {
	p := &udpPath{conn: conn, codec: codec}
	if remote != nil {
		p.remote.Store(remote)
	}
	return p
}

// encode 对应 :30-32。
func (p *udpPath) encode(frame []byte) []byte {
	if p.codec == nil {
		return frame
	}
	return p.codec.encode(frame)
}

// decode 对应 :35-37：底层 UDP 包 → rudp 帧；不属于本路径协议的包返回 nil。
// Java 的无 codec 分支是 `len > 0 ? packet : null`（空包视为无效）。
func (p *udpPath) decode(packet []byte) []byte {
	if p.codec == nil {
		if len(packet) > 0 {
			return packet
		}
		return nil
	}
	return p.codec.decode(packet)
}

// send 对应 :39-42：encode 后单次发送到本路径当前对端地址。
// 注意用 WriteToUDP 一次，不套 punch.go 的 udpSendTo（那是打洞期的 3 次重试语义）。
func (p *udpPath) send(rudpFrame []byte) error {
	out := p.encode(rudpFrame)
	if p.conn == nil {
		return errors.New("rudp path: socket 为空")
	}
	addr := p.remote.Load()
	if addr == nil {
		return errors.New("rudp path: 对端地址为空")
	}
	_, err := p.conn.WriteToUDP(out, addr)
	return err
}

// maxSendChunk 对应 ReliableUdpTransport.java:457-460：按当前主路径取发送分块上限
// （TURN 包封要给信封让出字节，否则节点按 1400 静默丢）。
func (p *udpPath) maxSendChunk() int {
	if p != nil && p.codec != nil {
		return rudpTurnMaxChunk
	}
	return rudpMaxPayload
}

// noteRx 记一次"有效收包"。模组只在四类帧上记账（processDatagram）：
// DATA(:679-680)、ACK(:685-686)、KEEPALIVE(:706-707)、PUNCH/PUNCH_ACK(:732-733)。
// FEC_XOR / RESTART / VOICE 不记 —— 它们不能证明"这条路径对端活着"的强弱不同，
// 模组就是这么判的，照抄。
func (p *udpPath) noteRx(frame []byte) {
	if p == nil || len(frame) < 3 {
		return
	}
	if frame[0] != 0x56 || frame[1] != 0x4C {
		return
	}
	switch frame[2] {
	case rudpTypeData, rudpTypeAck, rudpTypeKeepalive, rudpTypePunch, rudpTypePunchAck:
		p.rxCount.Add(1)
		p.lastRxMs.Store(time.Now().UnixMilli())
	}
}

// ---- 会话的路径集（ReliableUdpTransport.java:90-92 的三个字段） ----

type rudpPathSet struct {
	mu           sync.Mutex
	primary      *udpPath
	secondary    *udpPath
	graceUntilMs atomic.Int64 // :92 switchGraceUntilMs
	logf         func(level, msg string)
	recvLoopDone chan struct{} // 次路径接收线程的停止信号
}

var rudpPathSets sync.Map // *rudpConn → *rudpPathSet

// paths 取（必要时建）本会话的路径集。首建时 primary 直接采用 rudpConn 现有的
// socket/remote/codec —— 与模组 :344 `primaryPath = new UdpPath(socket, remoteAddress, primaryCodec)`
// 同构（mod 端 initial primary 与 this.socket 同引用，见 :88-89 注释）。
func (c *rudpConn) paths() *rudpPathSet {
	if v, ok := rudpPathSets.Load(c); ok {
		return v.(*rudpPathSet)
	}
	ps := &rudpPathSet{
		primary: newUDPPath(c.conn, c.remote.Load(), c.codec),
		logf:    nil,
	}
	// 主路径地址会随漂移重绑更新（rudp.go maybeRebindRemote 改的是 c.remote），
	// 因此主路径不缓存地址快照：每次取地址时以 c.remote 为准（见 primaryRemote）。
	v, _ := rudpPathSets.LoadOrStore(c, ps)
	return v.(*rudpPathSet)
}

// primaryRemote 主路径的当前发送目标：优先取 rudpConn.remote（漂移后的真值）。
func (c *rudpConn) primaryRemote() *net.UDPAddr { return c.remote.Load() }

func (ps *rudpPathSet) rlog(level, format string, args ...any) {
	if ps.logf == nil {
		return
	}
	ps.logf(level, "[ReliableUdp] "+fmt.Sprintf(format, args...))
}

// bindPathLog 把路径日志接到 app 的网络日志（不绑则静默）。
func (c *rudpConn) bindPathLog(logf func(level, msg string)) {
	ps := c.paths()
	ps.mu.Lock()
	ps.logf = logf
	ps.mu.Unlock()
}

// getPrimaryPath 对应 :1406-1408。
func (c *rudpConn) getPrimaryPath() *udpPath { return c.paths().primary }

// getSecondaryPath 对应 :1402-1404。
func (c *rudpConn) getSecondaryPath() *udpPath {
	ps := c.paths()
	ps.mu.Lock()
	defer ps.mu.Unlock()
	return ps.secondary
}

// secondaryRxCount 对应 :1411-1414（次路径累计有效收包数，0=没有次路径）。
func (c *rudpConn) getSecondaryRxCount() int64 {
	s := c.getSecondaryPath()
	if s == nil {
		return 0
	}
	return s.rxCount.Load()
}

// secondaryLastRxMs 对应 :1416-1420（次路径最近一次有效收包时刻，0=从未）。
func (c *rudpConn) getSecondaryLastRxMs() int64 {
	s := c.getSecondaryPath()
	if s == nil {
		return 0
	}
	return s.lastRxMs.Load()
}

// addSecondaryPath 对应 :1384-1400：挂载第二条路径作为切换候选，即刻启动其接收线程（双收）。
// sock/addr 为空、会话已关、或已有次路径 → 直接返回（同模组的守卫）。
func (c *rudpConn) addSecondaryPath(sock *net.UDPConn, addr *net.UDPAddr, codec rudpPathCodec) {
	ps := c.paths()
	ps.mu.Lock()
	if sock == nil || addr == nil || c.closed.Load() || ps.secondary != nil {
		ps.mu.Unlock()
		return
	}
	_ = sock.SetReadDeadline(time.Now().Add(rudpSecondaryReadTimeout))
	path := newUDPPath(sock, addr, codec)
	ps.secondary = path
	ps.recvLoopDone = make(chan struct{})
	done := ps.recvLoopDone
	ps.rlog("info", "secondary path added: %s (codec=%s)", addr.String(), pathCodecName(codec))
	ps.mu.Unlock()

	go c.recvPathLoop(path, done)
}

func pathCodecName(codec rudpPathCodec) string {
	if codec == nil {
		return "raw"
	}
	return "turn"
}

// recvPathLoop 对应 :592-635 的单路径接收循环（次路径专用的一份）。
// 主路径那份仍是 rudp.go 的 recvLoop（它已经把帧交给 processFrame），
// 因此这里只补次路径：解码 → 剥认证尾 → 喂同一个 rudpConn 的帧处理 → 记 rx。
//
// 与模组的对应关系：
//   - 致命异常只允许主路径线程关整条连接，次路径线程退出即可（:612-616）——
//     本函数出错只返回，绝不碰 c.closed；
//   - 地址漂移重绑只对无包封路径生效（maybeRebindRemote :543-546），且只有主路径会同步到
//     remoteConfirmed/lastCurrentPortRecv（:553-559）——次路径的漂移只在它自己内部。
func (c *rudpConn) recvPathLoop(path *udpPath, done chan struct{}) {
	buf := make([]byte, rudpPathReadBufSize)
	for {
		select {
		case <-done:
			return
		case <-c.doneCh:
			return
		default:
		}
		if path.conn == nil {
			return
		}
		_ = path.conn.SetReadDeadline(time.Now().Add(rudpSecondaryReadTimeout))
		n, from, err := path.conn.ReadFromUDP(buf)
		if err != nil {
			select {
			case <-done:
				return
			case <-c.doneCh:
				return
			default:
			}
			if errors.Is(err, net.ErrClosed) {
				return
			}
			var ne net.Error
			if errors.As(err, &ne) && ne.Timeout() {
				continue
			}
			c.rlog("warn", "Receive error: %s", err.Error())
			continue
		}
		pkt := buf[:n]
		frame := path.decode(pkt)
		if frame == nil {
			continue
		}
		// 认证模式：次路径同样必须校验截断 MAC（模组 processDatagram :654-662 与路径无关）
		if key := c.armedAuthKey(); key != nil {
			if len(frame) < 9 || !punchAuthVerifyTrailer4(key, frame) {
				c.authDrop()
				continue
			}
			c.authOk()
		}
		if len(frame) >= 3 && frame[0] == 0x56 && frame[1] == 0x4C {
			c.maybeRebindPathRemote(path, from)
		}
		f, ok := rudpDecode(frame)
		if !ok {
			continue
		}
		path.noteRx(frame)
		c.processFrame(f)
	}
}

// maybeRebindPathRemote 次路径的地址漂移重绑（maybeRebindRemote :543-585 的路径版）：
// 只对该路径自身的 remoteAddress 生效，不碰 rudpConn.remote（那是主路径的发送目标）。
// 有包封（TURN）的路径对端固定是中继节点，不漂移。
func (c *rudpConn) maybeRebindPathRemote(path *udpPath, from *net.UDPAddr) {
	if path == nil || path.codec != nil || from == nil {
		return
	}
	cur := path.remote.Load()
	if cur == nil {
		path.remote.Store(from)
		return
	}
	nowMs := time.Now().UnixMilli()
	if cur.Port == from.Port && cur.IP.Equal(from.IP) {
		return
	}
	if !cur.IP.Equal(from.IP) {
		return
	}
	// 次路径不是发送目标，"当前端口仍存活则绝不 rebind"（:564-567 的 1.0.0 兼容判据）
	// 只对主路径有意义；这里保留同一形态：以本路径自己的最近收包时刻判活。
	if last := path.lastRxMs.Load(); last > 0 && nowMs-last < rudpCurrentPortAlive.Milliseconds() {
		return
	}
	old := cur.Port
	path.remote.Store(from)
	c.rlog("info", "Remote port drifted %d -> %d, rebind to actual peer socket (symmetric NAT)", old, from.Port)
}

// sendOnPrimary 对应 :1358-1377：走主路径；切换后 10s 宽限内新旧路径双发。
// 入参是"已完成 rudp 编码 + 认证尾"的帧，包封由路径自己的 codec 负责（同模组 finalizeFrame
// 之后才进 path.send 的顺序）。
func (c *rudpConn) sendOnPrimary(framed []byte) {
	ps := c.paths()
	ps.mu.Lock()
	pri := ps.primary
	sec := ps.secondary
	ps.mu.Unlock()

	if pri != nil {
		if pri.conn == c.conn {
			// 主路径就是 rudpConn 自带的 socket/remote：走既有出口，保持 rudp.go 的
			// udpSendTo 三次重试语义不变（打洞期靠它兜住 WSAECONNRESET）。
			addr := c.remote.Load()
			if addr != nil {
				pkt := framed
				if pri.codec != nil {
					pkt = pri.codec.encode(pkt)
				}
				udpSendTo(c.conn, pkt, addr)
			}
		} else if err := pri.send(framed); err != nil {
			c.rlog("debug", "send failed: %s", err.Error())
		}
	}

	if sec != nil && sec != pri && time.Now().UnixMilli() < ps.graceUntilMs.Load() {
		_ = sec.send(framed) // :1372-1375 静默
	}
}

// promoteSecondaryPath 对应 :1426-1437：次路径升为主路径（只影响本端发送方向），
// 旧主路径降级为宽限期备胎（10s 双发后可 drop）。
func (c *rudpConn) promoteSecondaryPath() {
	ps := c.paths()
	ps.mu.Lock()
	sec := ps.secondary
	pri := ps.primary
	if sec == nil || sec == pri {
		ps.mu.Unlock()
		return
	}
	ps.primary = sec
	ps.secondary = pri
	ps.graceUntilMs.Store(time.Now().UnixMilli() + rudpSwitchGrace.Milliseconds())
	fromAddr, toAddr := remoteOrUnknown(pri), remoteOrUnknown(sec)
	ps.mu.Unlock()
	ps.rlog("info", "path promoted: %s -> %s (grace dual-send 10s)", fromAddr, toAddr)
}

// dropInactivePath 对应 :1439-1455：宽限期结束收尾，关闭并移除非主路径的 socket
// （其接收线程随 socket 关闭退出）。
func (c *rudpConn) dropInactivePath() {
	ps := c.paths()
	ps.mu.Lock()
	sec := ps.secondary
	if sec == nil {
		ps.mu.Unlock()
		return
	}
	ps.secondary = nil
	ps.graceUntilMs.Store(0)
	done := ps.recvLoopDone
	ps.recvLoopDone = nil
	ps.mu.Unlock()

	if done != nil {
		close(done) // 唤醒可能阻塞在 SetReadDeadline 间隙里的接收线程
	}
	if sec.conn != nil {
		_ = sec.conn.Close()
	}
	ps.rlog("info", "inactive path dropped")
}

// closeSecondaryPath 对应 close() 的 :1502-1509（会话关闭时连带关掉次路径 socket）。
func (c *rudpConn) closeSecondaryPath() {
	ps := c.paths()
	ps.mu.Lock()
	sec := ps.secondary
	done := ps.recvLoopDone
	ps.secondary = nil
	ps.recvLoopDone = nil
	ps.mu.Unlock()
	if done != nil {
		close(done)
	}
	if sec != nil && sec.conn != nil {
		_ = sec.conn.Close()
	}
}

func remoteOrUnknown(p *udpPath) string {
	if p == nil {
		return "<nil>"
	}
	addr := p.remote.Load()
	if addr == nil {
		return "<nil>"
	}
	return addr.String()
}

// primaryMaxSendChunk 对应 :457-460 的 currentMaxSendChunk（主路径视角的分块上限）。
// rudp.go 的 maxSendChunk() 目前读的是会话创建时设置的 c.codec；主路径被 promote 成
// 直连后，分块上限必须跟着变，否则直连路径仍按 1374 切（模组 TURN_MAX_SEND_CHUNK :45 的
// 语义就是"给信封让字节"，信封没了还让就是白让）。接线见交付报告 R6。
func (c *rudpConn) primaryMaxSendChunk() int {
	ps := c.paths()
	ps.mu.Lock()
	pri := ps.primary
	ps.mu.Unlock()
	return pri.maxSendChunk()
}
