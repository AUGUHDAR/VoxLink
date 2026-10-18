package main

// relay_candidates.go — 模组侧房主"玩家中继候选派发机器"（ConnectionManager.tryRelay
// 房主分支 CM:10508-10796）的 Go 移植。只覆盖"选谁、按什么顺序、一批几个、失败拉黑多久、
// 总体多久弃疗"；信号发送（relay_setup/relay_notify）仍由集成方在回调里做。
//
// 与现状的关键差异（集成时替换 relay.go 的跨房服务端池路径）：
//   - 候选只从**本房间在册房客**里选（模组的 activeUdpTransports/roomInfo.getPeers()，
//     在 app 侧对应 e.hPeers，conn_engine.go:204），且必须已有建立好的 mux 化传输
//     （CM:10589、CM:10601-10605）。
//   - 服务端跨房全局池 /relay/candidates（relay.go:389、relay.go:421-435）对应模组的
//     fetchGlobalRelayCandidates（CM:10902）：已被实证为跨房间数据注入（候选者复用其连往
//     自己房间 host 的传输转发），**永久停用**（CM:10880-10883 注释，CM:10884 改走本机器）。
//   - "挑最闲"的度量从本会话自记的 hRelayDispatched（relay.go:441）改为模组同款
//     RelayBridge 在转发数（CM:10639-10647 ↔ app bridge.go:919 relayCountForPeer），
//     同载荷再以传输 RTO 升序（CM:10653-10657）。
//
// 状态全部收在本文件的 relayCandidateMachine 里，字段逐项对应模组实例字段；
// 并发纪律：机器字段一律挂在 e.mu 下读写（沿用 conn_engine 既有约定）。

import (
	"fmt"
	"math"
	"sort"
	"sync"
	"time"
)

// ---- 常量与计时量（逐项对照 Java 源）----

const (
	// maxRelayCandidates 一批并行尝试的候选上限。ConnectionManager.java:190
	// （MAX_RELAY_CANDIDATES = 3）与 ConnectionManager.java:10661（Math.min(3, size)）。
	maxRelayCandidates = 3
)

// 下面四个是计时量：值即模组常量，声明成 var 只为用例能拨表验证
// "批次超时→拉黑整批→递归下一批"与"90 秒总闸停递归"这两条链路，语义不变。
var (
	// relayBatchTimeout 单批 8s 无人成桥→整批拉黑并重开下一批。
	// ConnectionManager.java:192（SHORT_TIMEOUT_SEC = 8）与 :10793（schedule(..., 8L, SECONDS)）。
	relayBatchTimeout = 8 * time.Second

	// relayFailureWindow 失败黑名单条目有效期 60s，过期即视同不在册并顺手剔除。
	// ConnectionManager.java:210（RELAY_FAILURE_WINDOW_MS = 60000L）、:425-427（过期 remove）。
	relayFailureWindow = 60 * time.Second

	// manualRelayTotalDeadline 手动中继 90s 总时限，到期后停止递归批处理、走 failed_timeout。
	// ConnectionManager.java:1402（now + 90_000L，注释 :1401）、:10511-10517（判据与分桶）。
	manualRelayTotalDeadline = 90 * time.Second

	// relayDisconnectFailoverDelay 中继断开后备用候选切换的延时。
	// ConnectionManager.java:13472-13498（current 拉黑 :13480，500L ms 后重开 tryRelay :13488-13498）。
	relayDisconnectFailoverDelay = 500 * time.Millisecond
)

// 机器可读进度稳定键（应用侧裁决：无 MC 聊天框，批次进度走 netLog 稳定键而非本地化文案；
// 对应模组 relayBatchIndex/relayBatchSize 与 trying_batch 阶段，CM:10719-10726）。
const (
	keyRelayBatchIndex    = "relay_batch_index"                  // CM:1438/:10719
	keyRelayBatchSize     = "relay_batch_size"                   // CM:1449/:10721
	keyRelayNoCandidates  = "voxlink.relay.failed_no_candidates" // CM:10627
	keyRelayFailedTimeout = "voxlink.relay.failed_timeout"       // CM:10516
)

// ---- 机器本体（模组 ConnectionManager 上那组 relay 派发字段的集合体）----

// relayCandidateMachine 房主侧候选派发状态。字段与模组一一对应：
type relayCandidateMachine struct {
	failed     map[string]int64 // CM:212 failedRelayPeers（clientId→最近失败毫秒；60s 窗口 CM:210/:425）
	batchIndex int              // CM:1438 relayBatchIndex（每开始新批次 +1，CM:10719）
	batchSize  int              // CM:1449 relayBatchSize（本批并行数，CM:10721）
	inProgress bool             // CM:216 manualRelayInProgress（总时限判据的与项，CM:10511）
	deadlineMs int64            // CM:223 manualRelayDeadline（0=未设，CM:1402 置位/CM:10514、:1514 清零）
	current    string           // CM:214 currentRelayPeer（""=无；置为批首候选 CM:10717）
	batch      []*hostPeer      // 当前批次候选快照（整批超时按 CM:10777-10779 逐条拉黑）
	batchTimer *time.Timer      // CM:10771 relayFailoverTask（8s 批超时这一支）
}

// relayCandidateMachines 按引擎实例存机器；集成方既可以直接 relayCandidatesOf(e) 取，
// 也可以在 connEngine 上放一个 *relayCandidateMachine 字段指过来——二选一，状态只有这一份。
// 读写规则同其余 app 引擎态：一律在 e.mu 临界区内访问机器字段。
var relayCandidateMachines sync.Map // *connEngine → *relayCandidateMachine

// relayCandidatesOf 取/懒建本引擎的派发机器（黑名单初始为空，模组 CM:212 构造即空表）。
func relayCandidatesOf(e *connEngine) *relayCandidateMachine {
	if v, ok := relayCandidateMachines.Load(e); ok {
		return v.(*relayCandidateMachine)
	}
	m := &relayCandidateMachine{failed: map[string]int64{}}
	actual, _ := relayCandidateMachines.LoadOrStore(e, m)
	return actual.(*relayCandidateMachine)
}

// resetRelayCandidates 会话销毁时调用（teardown 挂点）：停批计时器并整台丢弃。
// 语义对齐模组退房清账（CM:15861 failedRelayPeers.clear 的"下一场从零开始"）。
// 调用方必须已持有 e.mu（本函数不再自锁：引擎复位整段都在临界区内，
// 这里再 Lock 一次就是同线程重入死锁——Go 的 sync.Mutex 不可重入）。
func resetRelayCandidates(e *connEngine) {
	if v, ok := relayCandidateMachines.Load(e); ok {
		m := v.(*relayCandidateMachine)
		if m.batchTimer != nil {
			m.batchTimer.Stop()
			m.batchTimer = nil
		}
		m.batch = nil
		m.current = ""
		m.inProgress = false
		m.deadlineMs = 0
	}
	relayCandidateMachines.Delete(e)
}

// ---- 黑名单（CM:210/:417-435/:10779/:13480）----

// relayMarkFailed 把某候选拉黑到 60s 窗口（CM:10779 批超时、CM:13480 断开切换同形态）。
func (e *connEngine) relayMarkFailed(peerID string) {
	if peerID == "" {
		return
	}
	m := relayCandidatesOf(e)
	e.mu.Lock()
	if m.failed == nil {
		m.failed = map[string]int64{}
	}
	m.failed[peerID] = time.Now().UnixMilli()
	e.mu.Unlock()
}

// relayPeerFailed 60s 窗口判（CM:417-435：过期剔除并放行）。
func (e *connEngine) relayPeerFailed(peerID string) bool {
	m := relayCandidatesOf(e)
	e.mu.Lock()
	t, ok := m.failed[peerID]
	if ok && time.Now().UnixMilli()-t > relayFailureWindow.Milliseconds() {
		delete(m.failed, peerID) // CM:427 remove(clientId, t) 的条件删除，Go 下先判后删等价
		ok = false
	}
	e.mu.Unlock()
	return ok
}

// relayClearFailures 清空黑名单（CM:10623 无候选即清、CM:13518 终局失败即清）。
func (e *connEngine) relayClearFailures() {
	m := relayCandidatesOf(e)
	e.mu.Lock()
	m.failed = map[string]int64{}
	e.mu.Unlock()
}

// ---- 候选筛选与排序（CM:10577-10615、CM:10639-10663）----

// pickRelayCandidates 从本房在册房客里选候选并返回本批（≤3，CM:10661-10663）。
// 全部判据照抄 CM:10581-10611：
//   - mappedIp/mappedPort 有效（:10583-10585 → hostPeer.mapped 非空且 valid，stun.go:43）；
//   - 不在 60s 黑名单（:10587 → relayPeerFailed）；
//   - 已有建立好的传输（:10589 → hostPeer.rudp != nil，app 侧 hPeers 即 activeUdpTransports）；
//   - NAT 非 sym/strict/unknown（:10591-10595；app 侧按 natClass 判定，punch_strategy.go:9-14：
//     easy/hard sym=natEasySym/natHardSym（CM 串里含 "sym"）、unknown=natUnknown；
//     "strict" 在 app 的四级分类里没有对应档位，见交付报告未清单）；
//   - 声明 "relay" 能力（:10597 → ProtocolNegotiator.java:47-48，能力名 :12；
//     app 侧 hostPeer.caps + peerCapsListContains，rudp.go:477-483）；
//   - 声明 relayMuxV1 且其传输真的已 mux 化（:10601-10605 → ProtocolNegotiator.java:85-86
//     能力名 :37，relayMuxCapability rudp.go:464；isMuxEnabled rudp.go:505）。
//
// 排序照 CM:10639-10659：RelayBridge 在转发数升序，同载荷再按传输 RTO 升序
// （传输缺失按 Long.MAX_VALUE——本函数里不可能缺失，保留判式以示同源）。
// 候选为空时先清黑名单再返回 nil（CM:10619-10631 的顺序：先 log 排除数、再 clear、
// 再由调用方走 failed_no_candidates）。
//
// targetID 是被中继者（模组里必为 sym/strict/unknown，CM:10679-10703，天然选不进候选；
// 参数化是为了集成方直接传 relay_request 的 from 时同样维持"目标不在自己候选池"这条不变量）。
func (e *connEngine) pickRelayCandidates(targetID string) []*hostPeer {
	e.mu.Lock()
	var list []*hostPeer
	excluded := 0
	for id, p := range e.hPeers {
		// CM:10581-10595 硬门槛
		if p == nil || p.mapped == nil || !p.mapped.valid() {
			continue
		}
		if p.natClass.symmetric() || p.natClass == natUnknown {
			continue
		}
		if p.rudp == nil {
			continue
		}
		// CM:10597-10605 能力门槛（legacy 与缺 relayMuxV1 分两支留痕，模组各有一条 info）
		if !peerCapsListContains(p.caps, capNameRelay) {
			excluded++
			continue
		}
		if !peerCapsListContains(p.caps, relayMuxCapability) || !p.rudp.isMuxEnabled() {
			excluded++
			continue
		}
		if id == targetID {
			continue
		}
		if e.relayPeerFailedLocked(id) {
			excluded++
			continue
		}
		list = append(list, p)
	}
	e.mu.Unlock()

	if len(list) == 0 {
		// CM:10621-10623：留痕（只报数量不报地址，遵守日志消敏纪律）→ 清黑名单 → 调用方
		// relayFailOrAuto(keyRelayNoCandidates)（CM:10627）。
		e.app.netLog("info", fmt.Sprintf("pickRelayCandidates: no cone relay node (excluded_failed=%d)", excluded))
		e.relayClearFailures()
		return nil
	}

	rb := getRelayBridge(e.app.netLog) // CM:10637 getInstance(scheduler) ↔ app 单例 bridge.go:874
	e.mu.Lock()
	rto := make(map[string]int64, len(list))
	for _, p := range list {
		rto[p.id] = p.rudp.relayCandidateRtoMs()
	}
	e.mu.Unlock()
	sort.SliceStable(list, func(i, j int) bool {
		// CM:10639-10659：负载优先，负载相同看 RTO。Java List.sort 稳定，Go 用 SliceStable 对齐。
		la := rb.relayCountForPeer(list[i].id)
		lb := rb.relayCountForPeer(list[j].id)
		if la != lb {
			return la < lb // CM:10645-10647 Integer.compare(loadA, loadB)
		}
		return rto[list[i].id] < rto[list[j].id] // CM:10653-10657 Long.compare(getRtoMs...)
	})
	if len(list) > maxRelayCandidates {
		list = list[:maxRelayCandidates] // CM:10661-10663 Math.min(3, size) + subList
	}
	return list
}

// relayPeerFailedLocked 60s 窗口判（CM:417-435）。调用方必须已持 e.mu（_Locked 约定，
// auto_relay.go:63 一节同款）。
func (e *connEngine) relayPeerFailedLocked(peerID string) bool {
	m := relayCandidatesOf(e)
	t, ok := m.failed[peerID]
	if ok && time.Now().UnixMilli()-t > relayFailureWindow.Milliseconds() {
		delete(m.failed, peerID)
		return false
	}
	return ok
}

// relayCandidateRtoMs 对应模组 ReliableUdpTransport.getRtoMs（CM:10657 排序键）：
// 读 rudpConn 发送侧在 sendMu 下记账的 rtoMs（rudp.go:268-273、:1077-1096）。
// 传输已关按 math.MaxInt64（CM:10657 Long.MAX_VALUE 的"缺失沉底"语义）。
func (c *rudpConn) relayCandidateRtoMs() int64 {
	if c == nil {
		return math.MaxInt64
	}
	c.sendMu.Lock()
	defer c.sendMu.Unlock()
	if c.closed.Load() {
		return math.MaxInt64
	}
	return int64(c.rtoMs)
}

// ---- 批次生命周期（CM:10717-10793）----

// relayBatchBegin 开批：current 置为批首（CM:10717）、批次号 +1（CM:10719）、
// 批大小登记（CM:10721），并用稳定键输出机器可读进度（替代 CM:10723-10726 的
// trying_batch 本地化 Component——应用侧无聊天框，硬规则走键）。
func (e *connEngine) relayBatchBegin(cands []*hostPeer) (index, size int) {
	m := relayCandidatesOf(e)
	e.mu.Lock()
	if len(cands) > 0 {
		m.current = cands[0].id // CM:10717 currentRelayPeer.set(relayCandidates.get(0).clientId)
	}
	m.batch = cands
	m.batchIndex++
	m.batchSize = len(cands)
	index, size = m.batchIndex, m.batchSize
	e.mu.Unlock()
	e.app.netLog("info", fmt.Sprintf("%s=%d %s=%d", keyRelayBatchIndex, index, keyRelayBatchSize, size))
	return index, size
}

// relayArmBatchTimeout 挂 8s 批超时（CM:10771-10793）：到点时若会话未换防、房间还是
// 开局那个（CM:10771 connectionWon + currentRoom==state 的 app 对应物），整批拉黑
// （CM:10777-10779）、清 current（CM:10785）后调 retry（模组递归 tryRelay(state)，CM:10789）。
// retry 里应当先过 relayDeadlineExpired 总时限门（模组靠递归回 tryRelay 头部
// CM:10511-10517 完成，本文件把这道门做成显式函数，见 relayDispatchOnce）。
func (e *connEngine) relayArmBatchTimeout(sess *session, retry func()) {
	m := relayCandidatesOf(e)
	e.mu.Lock()
	if m.batchTimer != nil {
		m.batchTimer.Stop()
	}
	batch := append([]*hostPeer(nil), m.batch...)
	m.batchTimer = time.AfterFunc(relayBatchTimeout, func() {
		e.mu.Lock()
		// CM:10771 守卫：房间已换/已散 → 整段作废。app 房主没有"自家 p2p 先赢"的常态，
		// connectionWon 对应物就是 gP2PDone（guest 侧字段，host 恒 false，判上以对齐原式）。
		alive := e.sess == sess && sess != nil && !e.gP2PDone
		e.mu.Unlock()
		if !alive {
			return
		}
		e.app.netLog("warn", fmt.Sprintf("relay batch timeout: mark %d failed", len(batch))) // CM:10773
		for _, p := range batch {
			e.relayMarkFailed(p.id) // CM:10777-10779
		}
		e.mu.Lock()
		m.current = "" // CM:10785
		m.batch = nil
		m.batchTimer = nil
		e.mu.Unlock()
		retry() // CM:10789（递归；总时限在其入口判 CM:10511-10517）
	})
	e.mu.Unlock()
}

// relayBatchCancel 撤掉在途批次（成桥成功/手动终止时）。CM:11136-11142 中继连通后
// manualRelayDeadline 清 0；成功路径由集成方另行 relayEndManualRelay 收 current。
func (e *connEngine) relayBatchCancel() {
	m := relayCandidatesOf(e)
	e.mu.Lock()
	if m.batchTimer != nil {
		m.batchTimer.Stop()
		m.batchTimer = nil
	}
	m.batch = nil
	m.current = ""
	e.mu.Unlock()
}

// relayCurrentPeer 现在的在池中继者（CM:214 currentRelayPeer 的读侧；
// CM:10528-10530 "已在尝试就跳过重复"用得上）。
func (e *connEngine) relayCurrentPeer() string {
	e.mu.Lock()
	defer e.mu.Unlock()
	return relayCandidatesOf(e).current
}

// ---- 90s 总时限（CM:1398/:1402/:10511-10517/:1514）----

// relayBeginManualRelay 手动中继开闸：inProgress=true（CM:1398）、deadline=now+90s（CM:1402）。
// 注释 CM:1401 说明了为什么必须有它：递归批次（8s/批）不设上限会无限循环。
func (e *connEngine) relayBeginManualRelay() {
	m := relayCandidatesOf(e)
	e.mu.Lock()
	m.inProgress = true
	m.deadlineMs = time.Now().UnixMilli() + manualRelayTotalDeadline.Milliseconds()
	e.mu.Unlock()
}

// relayDeadlineExpired 每批入口的总时限门（CM:10511-10512）：
// inProgress 且 deadline>0 且已到期 → 清 deadline（CM:10514）并返回 true；
// 调用方据此留痕（CM:10513）、notifyRelayFailed（CM:10515）并
// relayFailOrAuto(keyRelayFailedTimeout)（CM:10516）后终止递归。
func (e *connEngine) relayDeadlineExpired() bool {
	m := relayCandidatesOf(e)
	e.mu.Lock()
	expired := m.inProgress && m.deadlineMs > 0 && time.Now().UnixMilli() >= m.deadlineMs
	if expired {
		m.deadlineMs = 0
	}
	e.mu.Unlock()
	if expired {
		e.app.netLog("warn", keyRelayFailedTimeout) // CM:10513（90s abort）
	}
	return expired
}

// relayEndManualRelay 中继有结果时收口：inProgress=false、deadline=0（CM:1514/:11138）、
// 撤批计时器并清 current（模组 clearRelayTracking 的对应件，CM:13486）。
func (e *connEngine) relayEndManualRelay() {
	m := relayCandidatesOf(e)
	e.mu.Lock()
	m.inProgress = false
	m.deadlineMs = 0
	if m.batchTimer != nil {
		m.batchTimer.Stop()
		m.batchTimer = nil
	}
	m.batch = nil
	m.current = ""
	e.mu.Unlock()
}

// ---- 断开切备（CM:13472-13498）----

// relayFailoverOnDisconnect 在途中继被对端宣告断开：把 current 拉黑（CM:13478-13480）、
// 收口追踪（CM:13486 clearRelayTracking），500ms 后回调 retry 重开派发
// （CM:13488-13498；retry 入口照旧要过 90s 总时限门，同 relayArmBatchTimeout）。
func (e *connEngine) relayFailoverOnDisconnect(sess *session, retry func()) {
	e.mu.Lock()
	m := relayCandidatesOf(e)
	cur := m.current
	e.mu.Unlock()
	if cur != "" {
		e.relayMarkFailed(cur) // CM:13480
	}
	e.relayBatchCancel() // CM:13486（clearRelayTracking：current/计时器一并收）
	time.AfterFunc(relayDisconnectFailoverDelay, func() {
		e.mu.Lock()
		alive := e.sess == sess && sess != nil && !e.gP2PDone // CM:13490（currentRoom==state && !connectionWon）
		e.mu.Unlock()
		if alive {
			retry() // CM:13494
		}
	})
}

// ---- 一站式派发（集成主循环钩子；递归形态与模组 tryRelay 逐行对齐）----

// relayDispatchOnce 房主侧一批派发，等价 CM:10571-10793 的房主分支主体：
//  1. 90s 总时限门（模组靠递归回 tryRelay 头部判 CM:10511-10517，这里显式同款）；
//  2. pickRelayCandidates（空→黑名单已在内部清空 CM:10623，此处只补 failed_no_candidates
//     分桶 CM:10627，与模组 notifyRelayFailed+relayFailOrAuto 的次序一致）；
//  3. relayBatchBegin（批次号/大小/稳定键进度，CM:10717-10726）；
//  4. sendBatch 回调里由集成方逐候选发 relay_setup{targetClientId, targetIp, targetPort,
//     replyHostId} 与 relay_notify{relayIp, relayPort}（CM:10736-10753；replyHostId
//     取 relayPeerID(state)，CM:10743，app 侧 relay_pool.go:31 relayPeerID()）；
//  5. relayArmBatchTimeout 挂 8s 后递归回本函数（CM:10789 的 tryRelay 递归）。
//
// 调用前置门（保持模组原样、不在本函数里代判）：
//   - relayEnabled（CM:10520 入口门、CM:11144 relay_notify 侧门、CM:13185 中继节点侧门）；
//   - 无在池中继者（CM:10528-10530 去重，读 relayCurrentPeer()）。
func (e *connEngine) relayDispatchOnce(sess *session, targetID, targetIP string, targetPort int, sendBatch func(cands []*hostPeer, index, size int)) {
	if e.relayDeadlineExpired() {
		// CM:10515-10516：notifyRelayFailed 由集成方在此补（host 侧即 relay_declined 兜底），
		// 终判走 failed_timeout 分桶。
		e.relayFailOrAuto(keyRelayFailedTimeout, "中继总超时（90s）")
		return
	}
	cands := e.pickRelayCandidates(targetID)
	if len(cands) == 0 {
		e.relayFailOrAuto(keyRelayNoCandidates, "房内没有可用的锥型中继节点") // CM:10627
		return
	}
	index, size := e.relayBatchBegin(cands)
	sendBatch(cands, index, size)
	e.relayArmBatchTimeout(sess, func() {
		e.relayDispatchOnce(sess, targetID, targetIP, targetPort, sendBatch) // CM:10789 递归
	})
}
