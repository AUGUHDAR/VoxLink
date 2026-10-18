package main

// auto_relay.go — 智能自动中继编排器（模组 1.2.0：ConnectionManager.java:12728-13168，
// 2026-10-05 手动中继按钮退役；模组保留 triggerManualRelay/triggerTurnRelay 的方法本体，
// 只失去 UI 调用方，本文件就是那个新的调用方）。
//
// 三条准则，逐条有事故背书（模组类头原话）：
//  1. 证据分级触发，不是盲轮数：死局 NAT 组合 15s / 证据累计 30s / 兜底 45s，全部要求"整会话零收包"；
//  2. 中继建立 ≠ 成功：25s 真连接验证窗盯 MC 会话下行首包，"建立但死"就无缝换道
//     （本地桥与 MC TCP 全程不动，只换承载并重放上行日志）；
//  3. 两条道都用尽也不弹失败屏，而是复位并复活打洞（F2）；复活不了才给终态失败。
//
// 线程模型：模组把全部编排决策串行在信令单线程 scheduler 上，故 fireAuto* 自身不做 CAS。
// Go 侧用"每个任务一条 goroutine + ticker"复刻这份串行性：单个任务内不会重入，
// 跨任务的状态位一律在 e.mu 下读写；持锁期间绝不回头调用 useTurnRelay/usePlayerRelay
// 这类自带加锁的方法（Go 的 mutex 不可重入）。

import (
	"context"
	"fmt"
	"net"
	"strconv"
	"time"
)

const (
	autoRelayTick         = time.Second       // 导演：1s 初延迟/1s 周期（CM:12796）
	autoRelayMinPunchMs   = int64(10_000)     // G3 触发下限（CM:12764）
	autoRelayTierAMS      = int64(15_000)     // tierA 死局 NAT 组合（CM:12773）
	autoRelayTierBPredOff = 10                // tierB 的 PREDICTION_OFF 阈，与封顶 50 并存勿合并（CM:12774 vs CM:390）
	autoRelayTierBMS      = int64(30_000)     // tierB 时长阈（CM:12774）
	autoRelayTierCMS      = int64(45_000)     // tierC 兜底（CM:12775）
	relayVerifyWindowMs   = int64(25_000)     // 真连接验证窗（CM:12937）
	autoRelayArmWait      = time.Second       // F1 挂载复查周期（CM:13077-13094）
	relayBgProbeWindow    = 600 * time.Second // 后台直连探测窗口 10 分钟（CM:13115）
	relayBgProbeTick      = 30 * time.Second  // 探测周期（CM:13159）
	relayBgProbeBudget    = 5 * time.Second   // 单次探测超时（CM:13145）
	carrierFromTurn       = "turn"
	carrierFromPlayer     = "player"
	autoRelayInitialRound = -1 // autoRelayArmedRound 初值（CM:12743）
)

// 文案对应 1.2.0 新增 lang 键（lang/*.json 已入库）；i18n 接线后改走键查表，先按本仓既有风格落中文。
const (
	txtLogAutoTurn      = "直连暂未打通，自动尝试中继" // voxlink.logui.auto_turn
	txtLogAutoPlayer    = "自动尝试玩家中继"      // voxlink.logui.auto_player_relay
	txtLogAutoDead      = "中继通道无响应，自动换道"  // voxlink.logui.auto_relay_dead
	txtLogAutoSwapped   = "已自动切换中继通道"     // voxlink.logui.auto_relay_swapped
	txtLogAutoExhausted = "中继均不可用，继续打洞"   // voxlink.logui.auto_relay_exhausted
	txtModeAutoTurn     = "直连未通，自动使用中继…"  // voxlink.auto_relay.turn
	txtModeAutoPlayer   = "自动使用玩家中继…"     // voxlink.auto_relay.player
	txtModeAutoExhaust  = "中继均不可用，继续打洞…"  // voxlink.auto_relay.exhausted
	keyAutoRelayFailed  = "voxlink.relay.failed"
	keyAutoTurnFailed   = "voxlink.turn.failed"
	keyMaxCyclesHit     = "voxlink.connection.max_cycles_exceeded"
)

func r2s(v any) string {
	if err, ok := v.(error); ok {
		return err.Error()
	}
	return fmt.Sprint(v)
}

// ---- 持锁谓词（*Locked 变体：调用方必须已持有 e.mu）----

func (e *connEngine) turnActiveLocked() bool {
	return e.turnSession != nil || e.stdTurnSession != nil
}

func (e *connEngine) relayInUseLocked() bool {
	return e.rRudp != nil || e.rBridge != nil
}

// joinerBridgeRunningLocked 本端 joiner 本地桥是否在跑（模组 P2PBridge.isRunning 的对应物：
// 打洞桥 / 玩家中继桥 / TURN 桥任一存在即算，因为三者共用同一个"给 MC 填的本地地址"语义）。
func (e *connEngine) joinerBridgeRunningLocked() bool {
	return e.gBridge != nil || e.rBridge != nil || e.turnBridge != nil
}

// ---- 导演（CM:12749-12820）----

func (e *connEngine) startAutoRelayDirector() {
	e.stopAutoRelayDirector()

	e.mu.Lock()
	sess := e.sess
	e.mu.Unlock()
	if sess == nil {
		return
	}

	ctx, cancel := context.WithCancel(context.Background())
	e.mu.Lock()
	e.autoRelayDirectorCancel = cancel
	e.mu.Unlock()

	go func() {
		t := time.NewTicker(autoRelayTick)
		defer t.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-t.C:
				e.autoRelayTickOnce(sess)
			}
		}
	}()
}

// autoRelayTickOnce 门序即语义，顺序不可调换（CM:12752-12796）。
func (e *connEngine) autoRelayTickOnce(sess *session) {
	defer func() {
		if r := recover(); r != nil {
			e.app.netLog("warn", "[AutoRelay] director tick error: "+r2s(r)) // 导演不退出（CM:12793）
		}
	}()

	e.mu.Lock()
	// G1 已赢 / 换房 → 自取消
	if (e.gP2PDone || e.rDone) || e.sess != sess {
		cancel := e.autoRelayDirectorCancel
		e.autoRelayDirectorCancel = nil
		e.mu.Unlock()
		if cancel != nil {
			cancel()
		}
		return
	}
	// G2 任何中继在途/在跑：不重复触发
	if e.turnActiveLocked() || e.turnInFlight || e.rInFlight {
		e.mu.Unlock()
		return
	}
	// G3 打洞时长下限。punchUiStartMs 首周期只设一次，轮次/ICE 重置都不清；0=本会话还没开始打洞
	punchMs := int64(0)
	if e.punchUiStartMs > 0 {
		punchMs = time.Now().UnixMilli() - e.punchUiStartMs
	}
	if punchMs < autoRelayMinPunchMs {
		e.mu.Unlock()
		return
	}
	// G4 零收包硬前提：收过对端任何一个包说明直连还有戏
	if e.punchRecvEver {
		e.mu.Unlock()
		return
	}
	// G5 三档证据，任一命中即触发
	tierA := e.deadNatComboLocked() && punchMs >= autoRelayTierAMS
	tierB := (e.predictionOffCount >= autoRelayTierBPredOff || e.samePublicIpAsHostLocked()) && punchMs >= autoRelayTierBMS
	tierC := punchMs >= autoRelayTierCMS
	if !(tierA || tierB || tierC) {
		e.mu.Unlock()
		return
	}
	// G6 轮次推进 → 重置两条道的尝试位（新一轮打洞 = 新一次中继机会）
	if e.retryRound != e.autoRelayArmedRound {
		e.autoRelayArmedRound = e.retryRound
		e.autoRelayTurnAttempted = false
		e.autoRelayPlayerAttempted = false
	}
	detail := "[AutoRelay] evidence tier hit (punchMs=" + strconv.FormatInt(punchMs, 10) +
		", predOff=" + strconv.Itoa(e.predictionOffCount) +
		", deadCombo=" + strconv.FormatBool(e.deadNatComboLocked()) +
		", sameIp=" + strconv.FormatBool(e.samePublicIpAsHostLocked()) + "), auto relay"
	e.mu.Unlock()

	e.app.netLog("info", detail)
	e.fireAutoRelay()
}

// stopAutoRelayDirector 只取消、不清任何状态位（黑名单 #19）。
func (e *connEngine) stopAutoRelayDirector() {
	e.mu.Lock()
	cancel := e.autoRelayDirectorCancel
	e.autoRelayDirectorCancel = nil
	e.mu.Unlock()
	if cancel != nil {
		cancel()
	}
}

// deadNatComboLocked 本端 STUN 判对称 且 房主自报对称（信令 hostSymmetric → e.gRemoteNat）。
func (e *connEngine) deadNatComboLocked() bool {
	return e.gLocalNat.symmetric() && e.gRemoteNat.symmetric()
}

// samePublicIpAsHostLocked 两端映射到同一公网 IP：同一 NAT 下正向打洞几乎必然白搭。
func (e *connEngine) samePublicIpAsHostLocked() bool {
	mine := e.gMyMappedIP
	if mine == "" || e.gMapped == nil {
		return false
	}
	return mine == e.gMapped.IP
}

// ---- F1 挂载竞态：房间态未就绪时每秒复查挂载（CM:13071-13103）----

// scheduleAutoRelayDirectorArm 修的是"join 往返 >1s 时整场自动中继落空"：
// 会话登记与打洞起点之间有空窗，此时直接挂导演会因 sess 仍为 nil 而永不触发。
func (e *connEngine) scheduleAutoRelayDirectorArm() {
	e.cancelAutoRelayDirectorArm()

	ctx, cancel := context.WithCancel(context.Background())
	e.mu.Lock()
	e.autoRelayArmWaiterCancel = cancel
	e.mu.Unlock()

	go func() {
		t := time.NewTicker(autoRelayArmWait)
		defer t.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-t.C:
				e.mu.Lock()
				sess := e.sess
				won := e.gP2PDone || e.rDone
				host := e.isHost
				e.mu.Unlock()
				if sess == nil || won {
					e.cancelAutoRelayDirectorArm()
					continue
				}
				e.cancelAutoRelayDirectorArm()
				if !host {
					e.startAutoRelayDirector()
				}
				return
			}
		}
	}()
}

func (e *connEngine) cancelAutoRelayDirectorArm() {
	e.mu.Lock()
	cancel := e.autoRelayArmWaiterCancel
	e.autoRelayArmWaiterCancel = nil
	e.mu.Unlock()
	if cancel != nil {
		cancel()
	}
}

// ---- 触发（CM:12822-12846）----

func (e *connEngine) fireAutoRelay() {
	e.mu.Lock()
	turnNext := !e.autoRelayTurnUnhealthy && !e.autoRelayTurnAttempted
	playerNext := !e.autoRelayPlayerAttempted
	e.mu.Unlock()
	switch {
	case turnNext:
		e.fireAutoTurn()
	case playerNext:
		e.fireAutoPlayer()
	}
}

func (e *connEngine) fireAutoTurn() {
	e.mu.Lock()
	e.autoRelayTurnAttempted = true
	e.autoRelayMode = true
	e.mu.Unlock()

	e.app.netLog("info", txtLogAutoTurn)
	e.notifyState(phaseTurn, statusTrying, "", txtModeAutoTurn)
	e.app.netLog("info", "[AutoRelay] auto TURN fired")
	_ = e.useTurnRelay()
}

func (e *connEngine) fireAutoPlayer() {
	e.mu.Lock()
	e.autoRelayPlayerAttempted = true
	e.autoRelayMode = true
	e.mu.Unlock()

	e.app.netLog("info", txtLogAutoPlayer)
	e.notifyState(phasePrelay, statusTrying, "", txtModeAutoPlayer)
	e.app.netLog("info", "[AutoRelay] auto player relay fired")
	_ = e.usePlayerRelay()
}

// ---- 失败统一口（CM:12849-12905）----

// relayFailOrAuto 中继失败的唯一出口：自动模式走换道/回打洞；非自动模式（旧版对端触发等手动语义）保持失败。
// 模组用它替换了全部直接 showConnectFailed 的中继失败位点。
func (e *connEngine) relayFailOrAuto(reasonKey, reasonText string) {
	e.mu.Lock()
	auto := e.autoRelayMode
	e.mu.Unlock()
	if !auto {
		e.notifyState(phasePrelay, statusFailed, "", reasonText)
		return
	}
	e.autoRelayFailoverOrEnd(reasonKey, reasonText)
}

func (e *connEngine) autoRelayFailoverOrEnd(reasonKey, reasonText string) {
	e.mu.Lock()
	if e.sess == nil {
		e.autoRelayMode = false
		e.mu.Unlock()
		return
	}
	turnNext := !e.autoRelayTurnUnhealthy && !e.autoRelayTurnAttempted
	playerNext := !e.autoRelayPlayerAttempted
	e.mu.Unlock()

	if turnNext {
		e.fireAutoTurn()
		return
	}
	if playerNext {
		e.fireAutoPlayer()
		return
	}

	e.mu.Lock()
	e.autoRelayMode = false
	e.mu.Unlock()
	e.app.netLog("warn", "[AutoRelay] both relay paths exhausted ("+reasonKey+"), back to punching")
	e.notifyState(phaseP2P, statusTrying, "", txtModeAutoExhaust)
	e.app.netLog("info", txtLogAutoExhausted)

	if e.roleIsHost() {
		return
	}
	// 模组原话：中继桥建立时 stopAllPunchingForRelay 已杀打洞、轮次随之冻结，
	// 两道耗尽后会话静默挂死（无打洞/无中继/无失败屏）。所以先如实复位"已赢"位，再复活打洞链。
	e.mu.Lock()
	carrierDead := e.gRudp == nil || !e.gRudp.IsConnected()
	if (e.gP2PDone || e.rDone) && !e.turnActiveLocked() && !e.rInFlight && carrierDead {
		e.gP2PDone = false
		e.rDone = false
		// CM:12894：复活打洞前先清"对方已取消持久重试"位，
		// 否则两条中继道耗尽后永远复活不了（取消只针对那一次持续重试，不等于整场放弃）。
		e.retryCancelled = false
	}
	canRevive := !e.gP2PDone && !e.rDone && !e.turnActiveLocked() && !e.turnInFlight &&
		!e.rInFlight && e.gPuncher == nil && !e.gActive
	offer := e.gLastOffer
	e.mu.Unlock()

	failText := reasonText
	if failText == "" {
		failText = "多次尝试都没连上 :("
	}
	if !canRevive || offer == nil {
		// 终局失败：模组的 onTerminalFailure 位点（CM:13576 showConnectFailedFinal /
		// :15438 declareZeroRecvFinalFailure）——5s 后把这一场的日志样本传走，不再硬等 90s
		e.app.logUploadOnTerminalFailure()
		e.notifyState(phaseP2P, statusFailed, "", failText)
		return
	}
	gen, ok := e.beginGuestCycle()
	if !ok {
		e.app.logUploadOnTerminalFailure()
		e.notifyState(phaseP2P, statusFailed, "", failText)
		return
	}
	go e.guestPunchFlow(gen, copyMap(offer), "host")
}

// ---- 真连接验证窗（CM:12907-12976）----

// armRelayVerifyWatchdog 中继桥就绪后 25s 内必须见到 MC 会话下行首包，否则判该道"建立但死"。
// 模组还或了一个 isMcTrulyConnected()（进程内 MC 客户端才有的判据）——应用端 MC 在进程外，
// 唯一可观测的就是下行首包；这处语义差异如实记录，不造假判据。
func (e *connEngine) armRelayVerifyWatchdog(turnPath bool) {
	e.cancelRelayVerifyWatchdog()
	resetJoinerDownlink()

	e.mu.Lock()
	sess := e.sess
	e.mu.Unlock()
	if sess == nil {
		return
	}

	ctx, cancel := context.WithCancel(context.Background())
	e.mu.Lock()
	e.relayVerifyCancel = cancel
	e.mu.Unlock()
	armMs := time.Now().UnixMilli()

	go func() {
		t := time.NewTicker(autoRelayTick)
		defer t.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-t.C:
				if !e.relayVerifyTickOnce(sess, turnPath, armMs) {
					return
				}
			}
		}
	}()
}

func (e *connEngine) relayVerifyTickOnce(sess *session, turnPath bool, armMs int64) (again bool) {
	defer func() {
		if r := recover(); r != nil {
			e.app.netLog("warn", "[AutoRelay] verify watchdog error: "+r2s(r))
		}
	}()

	if hasJoinerDownlink() {
		e.cancelRelayVerifyWatchdog()
		e.mu.Lock()
		e.autoRelayMode = false
		e.mu.Unlock()
		stopJoinerJournal() // 活会话不允许"重放"语义，验证通过即停止记账（CM:12922）
		if !turnPath {
			e.startRelayBgProbe()
		}
		path := carrierFromPlayer
		if turnPath {
			path = carrierFromTurn
		}
		e.app.netLog("info", "[AutoRelay] relay verified ("+path+" path, "+
			strconv.FormatInt(time.Now().UnixMilli()-armMs, 10)+"ms)")
		return false
	}

	e.mu.Lock()
	running := e.joinerBridgeRunningLocked()
	current := e.sess
	e.mu.Unlock()
	if !running || current != sess {
		e.cancelRelayVerifyWatchdog() // 桥已拆 / 已换会话：不自判死（CM:12932-12935）
		return false
	}

	if time.Now().UnixMilli()-armMs >= relayVerifyWindowMs {
		e.cancelRelayVerifyWatchdog()
		e.onRelayVerifyDead(turnPath)
		return false
	}
	return true
}

func (e *connEngine) cancelRelayVerifyWatchdog() {
	e.mu.Lock()
	cancel := e.relayVerifyCancel
	e.relayVerifyCancel = nil
	e.mu.Unlock()
	if cancel != nil {
		cancel()
	}
}

// onRelayVerifyDead 该道"建立但死"。必须先把 carrierSwapFrom 挂上再 fire 新道——
// 反了的话新传输到达时 tryCarrierSwap 认不出这是换道，会去新建第二个本地桥（CM:12959）。
func (e *connEngine) onRelayVerifyDead(turnPath bool) {
	e.app.netLog("warn", "[AutoRelay] relay path verified DEAD, seamless failover starting")
	e.notifyState(phasePrelay, statusTrying, "", txtLogAutoDead)

	from := carrierFromPlayer
	if turnPath {
		from = carrierFromTurn
	}
	e.mu.Lock()
	e.carrierSwapFrom = from
	if turnPath {
		e.autoRelayTurnUnhealthy = true
	}
	playerTried := e.autoRelayPlayerAttempted
	turnTried := e.autoRelayTurnAttempted
	turnUnhealthy := e.autoRelayTurnUnhealthy
	e.mu.Unlock()

	if turnPath {
		if !playerTried {
			e.fireAutoPlayer()
		} else {
			e.teardownTurn(true) // 玩家中继也试过：诚实失败
		}
		return
	}
	if !turnUnhealthy && !turnTried {
		e.fireAutoTurn()
		return
	}
	e.stopPlayerRelay()
	e.relayFailOrAuto(keyAutoRelayFailed, "中继失败")
}

// ---- 无缝换道（CM:12978-13012）----

// tryCarrierSwap 新到传输不新建桥：热切承载 + 重放上行日志 + 拆旧道。
// host 侧无需换道信令——旧传输一关旧桥自拆，新桥早被 relay_accept/turn_alloc 武装好，
// 早到的字节在子通道接收缓冲里排队。必须接在"新建桥"之前（模组放在 startUdpPunchBridge 第一分支）。
func (e *connEngine) tryCarrierSwap(transport *rudpConn) bool {
	e.mu.Lock()
	from := e.carrierSwapFrom
	running := e.joinerBridgeRunningLocked()
	carrier := getJoinerCarrier()
	if from == "" || !running || carrier == nil {
		e.mu.Unlock()
		return false
	}
	e.carrierSwapFrom = ""
	e.mu.Unlock()

	journal := takeJoinerUplinkJournal()
	if !swapJoinerCarrier(transport, journal) {
		e.app.netLog("warn", "[AutoRelay] carrier swap failed (no local bridge?), falling back to fresh bridge")
		return false
	}
	if len(journal) > 0 {
		e.app.netLog("info", "[AutoRelay] uplink journal seeded "+strconv.Itoa(len(journal))+" bytes onto new carrier")
	}

	switch from {
	case carrierFromTurn:
		e.teardownTurnQuietly()
	case carrierFromPlayer:
		e.stopPlayerRelay()
	}

	// 车道记账（模组 activeUdpTransports.add/remove 的对应物，PB:1047/1067）：
	// 换道后"新承载归哪条道"决定了后续谁负责关它、谁的写回屏障还认这条会话。
	e.mu.Lock()
	if from == carrierFromPlayer {
		e.turnTransport = transport // 新道是 TURN
	} else {
		e.rRudp = transport // 新道是玩家中继
	}
	e.mu.Unlock()

	// 旧道是 player → 新道是 turn（传 true），反之 false（CM:13009）
	e.armRelayVerifyWatchdog(from != carrierFromPlayer)
	e.notifyState(phasePrelay, statusTrying, "", txtLogAutoSwapped)
	return true
}

// teardownTurnQuietly 换道成功后拆旧 TURN：不 release 节点、不 reset turnReadyApplied、不弹失败文案
// ——这三条是它与 teardownTurn 的全部差别（黑名单 #12，CM:13014-13054）。
func (e *connEngine) teardownTurnQuietly() {
	defer func() {
		if r := recover(); r != nil {
			e.app.netLog("warn", "[AutoRelay] teardown old turn error: "+r2s(r))
		}
	}()

	e.mu.Lock()
	e.turnSessionEpoch++ // F7：纪元 +1，异步写回屏障据此作废旧会话
	session := e.turnSession
	e.turnSession = nil
	stdSession := e.stdTurnSession
	e.stdTurnSession = nil
	transport := e.turnTransport
	e.turnTransport = nil
	bridge := e.turnBridge
	e.turnBridge = nil
	keepaliveStop := e.turnKeepaliveStop
	e.turnKeepaliveStop = nil
	e.turnInFlight = false
	e.turnPeerID = ""
	e.mu.Unlock()

	if keepaliveStop != nil {
		keepaliveStop()
	}
	if bridge != nil {
		bridge.stop()
	}
	if transport != nil {
		_ = transport.Close()
	}
	if stdSession != nil {
		stdTurnClose(stdSession)
	} else if session != nil {
		session.unbind()
	}

	e.app.netLog("info", txtModeAutoPlayer)
}

// stopPlayerRelay 停掉本端玩家中继链路（模组 stopRelay 的对应物）：拆 rudp 与本地桥并清引用，
// 与 relayBridgeDown 的区别是不再触发失败播报（换道场景下新道马上接上）。
func (e *connEngine) stopPlayerRelay() {
	e.mu.Lock()
	sock, puncher, rudp, bridge := e.rSock, e.rPuncher, e.rRudp, e.rBridge
	e.rSock, e.rPuncher, e.rRudp, e.rBridge = nil, nil, nil, nil
	e.rInFlight = false
	e.mu.Unlock()

	if puncher != nil {
		puncher.stop()
	}
	if rudp != nil {
		_ = rudp.Close()
	}
	if bridge != nil {
		bridge.stop()
	}
	if sock != nil {
		_ = sock.Close()
	}
}

// ---- 玩家中继成功后的后台直连探测（CM:13105-13168）----

// startRelayBgProbe 10 分钟窗口、30s 一拍。本轮形态是"只探测+留痕"：无缝升级需要房主侧
// 为被中继会话热切载具的配套（跨房虚拟加入），模组明确留给下迭代，这里不抢跑。
func (e *connEngine) startRelayBgProbe() {
	e.cancelRelayBgProbe()

	e.mu.Lock()
	sess := e.sess
	leg := e.rRudp
	e.mu.Unlock()
	if sess == nil || leg == nil {
		return
	}

	ctx, cancel := context.WithCancel(context.Background())
	e.mu.Lock()
	e.relayBgProbeCancel = cancel
	e.mu.Unlock()
	deadline := time.Now().Add(relayBgProbeWindow)

	go func() {
		t := time.NewTicker(relayBgProbeTick)
		defer t.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-t.C:
				if !e.relayBgProbeTickOnce(ctx, sess, deadline) {
					return
				}
			}
		}
	}()
}

func (e *connEngine) relayBgProbeTickOnce(ctx context.Context, sess *session, deadline time.Time) bool {
	defer func() {
		if r := recover(); r != nil {
			e.app.netLog("warn", "[Relay] bg probe tick error: "+r2s(r))
		}
	}()

	e.mu.Lock()
	gone := e.sess != sess || time.Now().After(deadline)
	leg := e.rRudp
	var ip string
	var port int
	if e.gMapped != nil {
		ip, port = e.gMapped.IP, e.gMapped.Port
	}
	e.mu.Unlock()
	if gone {
		e.cancelRelayBgProbe()
		return false
	}
	if leg == nil || !leg.IsConnected() {
		e.cancelRelayBgProbe()
		return false
	}
	if ip == "" || port <= 0 {
		return true
	}

	sock, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4zero, Port: 0})
	if err != nil {
		return true
	}
	defer sock.Close()
	target := net.ParseIP(ip)
	if target == nil {
		return true
	}
	_, _, perr := punchRelayOnce(sock, &net.UDPAddr{IP: target, Port: port}, relayBgProbeBudget)
	if perr == nil {
		e.app.netLog("info", "中继后台直连探测：房主应答，直连可行（无缝升级随跨房配套落地）")
	}
	return true
}

func (e *connEngine) cancelRelayBgProbe() {
	e.mu.Lock()
	cancel := e.relayBgProbeCancel
	e.relayBgProbeCancel = nil
	e.mu.Unlock()
	if cancel != nil {
		cancel()
	}
}

// ---- 统一复位（CM:13056-13068）----

// stopAutoRelayAll 会话起点与"一切连接工作停止"两处都要调（CM:14502-14519 / CM:1588-1590）。
// 模组的第 5 步清的是死字段 pendingCarrierSwapTransport（全文件只在这里写、无人读，
// 见 20261010-02 §1.2 与黑名单 #16），Go 侧不为其造空壳，其余八步一步不少。
func (e *connEngine) stopAutoRelayAll() {
	e.cancelAutoRelayDirectorArm()
	e.stopAutoRelayDirector()
	e.cancelRelayVerifyWatchdog()
	e.cancelRelayBgProbe()
	e.mu.Lock()
	e.carrierSwapFrom = ""
	e.autoRelayMode = false
	e.autoRelayTurnAttempted = false
	e.autoRelayTurnUnhealthy = false
	e.autoRelayPlayerAttempted = false
	e.autoRelayArmedRound = autoRelayInitialRound
	e.mu.Unlock()
}
