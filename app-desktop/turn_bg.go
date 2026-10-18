package main

// turn_bg.go — 模组 ConnectionManager 的"TURN 建立后后台打直连 + 玩家中继热备"整块。
//
// 逐语句对照（CM = fabric/1.20_1.20.1/src/main/java/icu/wuhui/voxlink/room/ConnectionManager.java，
// RM = 同目录 room/RoomManager.java，RUDP = network/ReliableUdpTransport.java，
// UHP = network/UdpHolePuncher.java）：
//
//	startTurnBgMonitor      CM:12253-12294（10min 窗 / 30s 拍；四个 TURN 建立位点各调一次）
//	attemptBgDirectPunch    CM:12315-12341（房客发 turn_bg_punch 并本端起后台 puncher；房主纯信号驱动）
//	startBgDirectPuncher    CM:12344-12368（沿用当前档位模板+密钥，单 socket，**无端口预测**，5s 预算）
//	onBgDirectPath          CM:12389-12442（CAS 去重 → 挂副路径 → 1s 拍连续 20s 稳定 → promote → 11s 后收尾）
//	attemptBgPlayerRelay     CM:12297-12304（奇数拍发 relay_request）
//	establishHotstandby     CM:12445-12494（打中继 → 起传输 → 15s keepalive → 发 turn_stby）
//	handleTurnStby          CM:12497-12511（房主侧仅提示，零状态变更）
//	finishTurnRelease       CM:12522-12557（纪元++ → 标记已切 P2P → 停三路定时器 → 拆会话 → 关热备 → 释放 TURN）
//	热备转正                CM:12592-12633（TURN 死掉且热备活着 → 提升为 relay_cone 并重连）
//	标志复位                CM:12718-12721、13046（cleanupTurnQuietly / 会话终结）
//	分发点                  RM:1950-1955（turn_bg_punch / turn_stby 两型）
//
// 本端与模组的两处结构性差异（都属"MC 进程内 → 进程外"的等价替换，不是省略）：
//   - 模组的 setUsingRelay/mode 是 HUD 与聊天提示；本端只落机器可读日志键（voxlink.turn.*）。
//   - 模组的 mc.player.sendSystemMessage 提示改为 netLog 键。

import (
	"net"
	"time"
)

// 观测量（不是状态机的一部分，只计数，供用例判守卫是否真的生效）
var _ = 0

var (
	// 以下四项是可拨的测试旋钮（与 tcpPunchWatchdogDelay 同一手法）：值即模组常量，用例只改速度不改语义。
	turnBgMonitorPeriod   = 30 * time.Second  // CM:12293 30s/30s
	turnBgWindow          = 600 * time.Second // CM:12258 now+600_000（10 分钟后台升直连窗口）
	turnSwitchWatchPeriod = 1 * time.Second   // CM:12410 1s/1s
	turnSwitchDropDelay   = 11 * time.Second  // CM:12426 promote 后 +11s 收尾
)

const (
	turnSwitchStreakNeeded   = 20                      // CM:12422 streak>=20（连续 20 拍稳定）
	turnSecondaryAliveWindow = 2000 * time.Millisecond // CM:12419 now-lastRx<2000L 记一次活
	turnBgPunchTimeout       = 5 * time.Second         // CM:12358 orTimeout(5, SECONDS)
	turnStandbyPunchTimeout  = 10 * time.Second        // CM:12457 orTimeout(10, SECONDS)
	hotstandbyKeepaliveEvery = 15 * time.Second        // CM:12487 15s/15s
)

// turnBgNowMs 抽成变量便于用例拨表（模组的 System.currentTimeMillis 对位）。
var turnBgNowMs = func() int64 { return time.Now().UnixMilli() }

// turnCarryingActive 等价 CM:12284 的 turnCarrying（TURN 传输在且连着）。
// 模组把它写进局部变量，本端直接复用 isTurnActive()（turn_flow.go:346，同一判据）。
func (e *connEngine) turnCarryingActive() bool { return e.turnActive() }

// startTurnBgMonitor 对应 CM:12253-12294：四个 TURN 建立位点各调一次，重复调用被首道守卫挡掉。
func (e *connEngine) startTurnBgMonitor() {
	e.mu.Lock()
	if e.turnBgMonitorStop != nil || e.turnP2pGivenUp { // CM:12254
		e.mu.Unlock()
		return
	}
	e.turnBgDeadlineMs = turnBgNowMs() + turnBgWindow.Milliseconds() // CM:12258
	stop := make(chan struct{})
	e.turnBgMonitorStop = func() {
		select {
		case <-stop:
		default:
			close(stop)
		}
	}
	e.mu.Unlock()

	go func() {
		t := time.NewTicker(turnBgMonitorPeriod)
		defer t.Stop()
		for {
			select {
			case <-stop:
				return
			case <-t.C:
				e.turnBgTick()
			}
		}
	}()
	e.app.netLog("info", "voxlink.turn.bg_monitor_armed")
}

// stopTurnBgMonitorInternal 只撤定时器、不动 turnBgMonitorStop 之外的语义（模组 cancel(false) 对位）。
func (e *connEngine) stopTurnBgMonitorInternal() {
	e.mu.Lock()
	stop := e.turnBgMonitorStop
	e.turnBgMonitorStop = nil
	e.mu.Unlock()
	if stop != nil {
		stop()
	}
}

// turnBgTick 是 CM:12260-12290 的 tick 体，六道判断的顺序照抄，不做重排。
func (e *connEngine) turnBgTick() {
	e.mu.Lock()
	if e.turnSwitchedToP2p { // CM:12261
		e.mu.Unlock()
		e.stopTurnBgMonitorInternal()
		return
	}
	if turnBgNowMs() > e.turnBgDeadlineMs { // CM:12266-12271
		e.turnP2pGivenUp = true
		e.mu.Unlock()
		e.stopTurnBgMonitorInternal()
		e.app.netLog("info", "voxlink.turn.bg_window_exhausted")
		return
	}
	carrying := e.turnTransport != nil && e.turnTransport.IsConnected() // CM:12284
	if !carrying {                                                      // CM:12273-12279：TURN 没了就撤监视器并拆 TURN
		e.mu.Unlock()
		e.stopTurnBgMonitorInternal()
		e.teardownTurn(false)
		e.app.netLog("warn", "voxlink.turn.transport_lost_in_bg_monitor")
		return
	}
	e.turnBgTicks++ // CM:12281
	tick := e.turnBgTicks
	standby := e.turnHotstandby // CM:12285 只判"有没有"，不判连接状态
	e.mu.Unlock()

	// CM:12284-12287：奇数拍 + TURN 正在承载 + 还没有热备 → 60s 一次玩家中继热备尝试
	if tick%2 == 1 && standby == nil {
		e.attemptBgPlayerRelay()
	}
	e.attemptBgDirectPunch() // CM:12289 每拍一次后台直连打洞
}

// attemptBgDirectPunch 对应 CM:12315-12341。
// 房客：把自己映射地址报给房主（无去重、无限流，一拍一条）后本端起 puncher；
// 房主：不发这条信令，只被 handleTurnBgPunch 驱动（CM:12334-12338 提前 return）。
func (e *connEngine) attemptBgDirectPunch() {
	e.mu.Lock()
	isHost := e.isHost
	myIP, myPort := e.gMyMappedIP, e.gMyMappedPort
	var peerIP string
	var peerPort int
	if !isHost && e.gMapped != nil { // CM:12316-12320 读 hostMappedIp/Port
		peerIP, peerPort = e.gMapped.IP, e.gMapped.Port
	}
	e.mu.Unlock()

	if isHost { // CM:12334-12338
		return
	}
	if peerIP == "" || peerPort <= 0 { // CM:12321 对端地址未知就跳过本拍
		return
	}
	if myIP != "" && myPort > 0 { // CM:12325-12332
		_ = e.sendSignal("turn_bg_punch", map[string]any{"ip": myIP, "port": myPort}, "host")
	}
	e.startBgDirectPuncher(peerIP, peerPort) // CM:12340
}

// handleTurnBgPunch 对应 CM:12371-12386（RM:1950-1952 分发进来）。
// 三道守卫照抄：有会话且非 PENDING、是房主、后台监视器活着且没放弃/没切换过。
// 模组的 from 参数不参与判定（房主打的就是信令里带的那个地址），本端同样不用。
func (e *connEngine) handleTurnBgPunch(data map[string]any) {
	e.mu.Lock()
	sess := e.sess
	isHost := e.isHost
	armed := e.turnBgMonitorStop != nil
	givenUp := e.turnP2pGivenUp
	switched := e.turnSwitchedToP2p
	e.mu.Unlock()
	if sess == nil || !isHost || !armed || givenUp || switched {
		return
	}
	ip := sigStr(data, "ip")
	port := sigInt(data, "port")
	if ip == "" || port <= 0 {
		return
	}
	e.startBgDirectPuncher(ip, port)
}

// startBgDirectPuncher 对应 CM:12344-12368：单 socket、沿用当前档位模板与 punchAuthV1 密钥、
// **关端口预测**（只打精确映射口），5s 预算，失败/超时即关。
func (e *connEngine) startBgDirectPuncher(ip string, port int) {
	addr := &net.UDPAddr{IP: net.ParseIP(ip), Port: port}
	if addr.IP == nil {
		return
	}
	e.mu.Lock()
	e.turnBgAttempts++ // 观测量：一次后台直连 puncher 起没起来（用例据此判守卫，不靠日志文本）
	e.mu.Unlock()
	profile, params, authKey := e.bgPunchTemplate()
	sock, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4zero, Port: 0})
	if err != nil {
		e.app.netLog("warn", "voxlink.turn.bg_punch_socket_failed")
		return
	}
	p := newPuncher(sock, profile, params, authKey)
	p.setTarget(addr)
	done := make(chan *punchResult, 1)
	go func() {
		done <- p.punchPrediction(port, 0, false) // UHP:358-360 → :1134 portPrediction=false
	}()
	go func() {
		select {
		case res := <-done:
			if res != nil && res.success {
				win, actual := p.win()
				if win == nil {
					win = sock
				}
				if actual == nil {
					actual = addr
				}
				p.stop() // UHP:304 markSocketTransferred 的本端对位：socket 交出去，puncher 不再关
				e.onBgDirectPath(win, actual)
				return
			}
			p.stop()
			_ = sock.Close()
		case <-time.After(turnBgPunchTimeout):
			p.stop()
			_ = sock.Close()
		}
	}()
}

// onBgDirectPath 对应 CM:12389-12442。
// CAS 只允许一次；TURN 已塌就关 socket；否则挂副路径（codec=nil 即裸直连）并起 1s 稳定观察。
func (e *connEngine) onBgDirectPath(sock *net.UDPConn, addr *net.UDPAddr) {
	e.mu.Lock()
	if e.turnBgPunchWon { // CM:12390
		e.mu.Unlock()
		_ = sock.Close()
		return
	}
	e.turnBgPunchWon = true
	tr := e.turnTransport
	e.mu.Unlock()

	if tr == nil || !tr.IsConnected() { // CM:12398-12405
		_ = sock.Close()
		e.mu.Lock()
		e.turnBgPunchWon = false
		e.mu.Unlock()
		return
	}
	e.app.netLog("info", "voxlink.turn.bg_direct_established_observing")
	tr.addSecondaryPath(sock, addr, nil) // CM:12408 codec=null=裸直连路径

	e.mu.Lock()
	e.turnSwitchStreak = 0
	watchStop := make(chan struct{})
	e.turnSwitchWatchStop = func() {
		select {
		case <-watchStop:
		default:
			close(watchStop)
		}
	}
	e.mu.Unlock()

	go func() { // CM:12410-12441
		t := time.NewTicker(turnSwitchWatchPeriod)
		defer t.Stop()
		for {
			select {
			case <-watchStop:
				return
			case <-t.C:
				last := tr.getSecondaryLastRxMs()
				alive := last > 0 && turnBgNowMs()-last < turnSecondaryAliveWindow.Milliseconds()
				e.mu.Lock()
				if alive {
					e.turnSwitchStreak++
				} else {
					e.turnSwitchStreak = 0 // CM:12436 严格连续：一次不通就清零
				}
				streak := e.turnSwitchStreak
				e.mu.Unlock()
				if streak >= turnSwitchStreakNeeded {
					e.stopTurnSwitchWatch()
					tr.promoteSecondaryPath() // CM:12424
					time.AfterFunc(turnSwitchDropDelay, func() {
						tr.dropInactivePath() // CM:12427
						e.finishTurnRelease()
					})
					return
				}
			}
		}
	}()
}

func (e *connEngine) stopTurnSwitchWatch() {
	e.mu.Lock()
	stop := e.turnSwitchWatchStop
	e.turnSwitchWatchStop = nil
	e.mu.Unlock()
	if stop != nil {
		stop()
	}
}

// finishTurnRelease 对应 CM:12522-12557：切回直连后的静默释放（不弹失败、不发终态）。
func (e *connEngine) finishTurnRelease() {
	e.mu.Lock()
	e.turnSessionEpoch++ // CM:12523 纪元先自增，晚到的异步写回自然被屏障挡掉
	e.turnSwitchedToP2p = true
	s := e.turnSession
	std := e.stdTurnSession
	sb := e.turnHotstandby
	code, token, clientID := e.code, e.token, e.clientID
	e.turnSession = nil
	e.stdTurnSession = nil
	e.turnHotstandby = nil
	e.turnReadyApplied = false
	e.turnPeerID = ""
	e.turnInFlight = false
	e.mu.Unlock()

	e.stopTurnBgMonitorInternal()
	e.stopTurnSwitchWatch()
	e.stopHotstandbyKeepalive()
	e.stopTurnKeepalive()

	if s != nil {
		s.unbind()
		turnRelease(e.app, code, clientID, token, s.sessionIDHex) // CM:12548 release(sessionIdHex)
	}
	if std != nil {
		stdTurnClose(std)
	}
	if sb != nil {
		_ = sb.Close()
	}
	e.app.netLog("info", "voxlink.turn.switched_p2p") // CM:12556 mode 的本端等价（只落键，不渲染文案）
}

// attemptBgPlayerRelay 对应 CM:12297-12304：向房主要一条玩家中继，回包走 relay_notify，
// 由 guestOnRelayNotify 的 TURN 门把它领进热备路径（不建桥）。
func (e *connEngine) attemptBgPlayerRelay() {
	e.mu.Lock()
	clientID := e.clientID
	isHost := e.isHost
	e.mu.Unlock()
	if isHost || clientID == "" {
		return
	}
	_ = e.sendSignal("relay_request", map[string]any{"clientId": clientID}, "host") // CM:12300-12303
	e.app.netLog("info", "voxlink.turn.hotstandby_requested")
}

// establishHotstandby 对应 CM:12445-12494。
// 打中继（10s 预算）→ 二次确认 TURN 还活着且还没有热备 → 起传输 → 15s keepalive → 发 turn_stby。
// 与正式中继的分别：热备**不建桥、不接管连接**（模组 handleRelayNotify 里 return，不走 bridge）。
func (e *connEngine) establishHotstandby(ip string, port int) {
	addr := &net.UDPAddr{IP: net.ParseIP(ip), Port: port}
	if addr.IP == nil {
		return
	}
	e.mu.Lock()
	e.turnStandbyAttempts++ // 观测量：热备建立被驱动过几次
	e.mu.Unlock()
	profile, params, authKey := e.bgPunchTemplate()
	sock, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4zero, Port: 0})
	if err != nil {
		e.app.netLog("warn", "voxlink.turn.hotstandby_socket_failed")
		return
	}
	p := newPuncher(sock, profile, params, authKey)
	p.setTarget(addr)
	done := make(chan *punchResult, 1)
	go func() { done <- p.punchPrediction(port, 0, false) }()
	go func() {
		var res *punchResult
		select {
		case res = <-done:
		case <-time.After(turnStandbyPunchTimeout):
		}
		if res == nil || !res.success {
			p.stop()
			_ = sock.Close()
			return
		}
		win, actual := p.win()
		if win == nil {
			win = sock
		}
		if actual == nil {
			actual = addr
		}
		p.stop()

		tr := newRudp(win, actual)
		e.mu.Lock()
		if !e.turnActiveLocked() || e.turnHotstandby != nil { // CM:12463 打洞期间状态可能已变
			e.mu.Unlock()
			_ = win.Close() // CM:12471 puncher.close() 的等价：socket 没人接手就关掉
			return
		}
		e.turnHotstandby = tr
		e.mu.Unlock()

		tr.Start()                     // CM:12476 transport.start()
		e.startHotstandbyKeepalive(tr) // CM:12487 15s/15s sendKeepaliveFrame
		e.app.netLog("info", "voxlink.turn.hotstandby_established")

		e.mu.Lock()
		clientID := e.clientID
		e.mu.Unlock()
		if clientID != "" { // CM:12481-12485
			_ = e.sendSignal("turn_stby", map[string]any{"clientId": clientID}, "host")
		}
	}()
}

// handleTurnStby 对应 CM:12497-12511：房主侧零状态变更，只提示。
// 模组的 data 与 from 都不参与判定，本端同样不判（只落一条机器可读键）。
func (e *connEngine) handleTurnStby() {
	e.mu.Lock()
	isHost := e.isHost
	sess := e.sess
	e.mu.Unlock()
	if !isHost || sess == nil {
		return
	}
	e.app.netLog("info", "voxlink.turn.standby")
}

// promoteHotstandbyOnTurnDeath 对应 CM:12592-12633，由 teardownTurn 在"TURN 塌了但热备活着"时调用。
// 返回 true 表示热备已接管，调用方**不能**再走失败上报（模组那条路径就是继续承载）。
func (e *connEngine) promoteHotstandbyOnTurnDeath() bool {
	e.mu.Lock()
	if e.isHost || e.turnHotstandby == nil || !e.turnHotstandby.IsConnected() {
		e.mu.Unlock()
		return false
	}
	sb := e.turnHotstandby
	e.turnHotstandby = nil
	e.turnBgPunchWon = false // CM:12624 转正后允许下一轮后台升级
	e.turnSwitchedToP2p = false
	e.turnP2pGivenUp = false
	e.turnPeerID = ""
	// CM:12619-12622 的 connectionWon/relayConnectedSignaled 两路状态，在本端由"中继道"自己的
	// 完成位在承担：热备转正就是把它升格为现役中继，故清掉完成/在途位，让成功上报重走一遍。
	e.rDone = false
	e.rInFlight = false
	e.rRudp = sb // CM:12627 activeUdpTransports.remove("turn_host") + put("relay_cone", standby)
	e.mu.Unlock()

	local, bridge, berr := startGuestBridge(sb, func() { e.relayBridgeDown() }) // CM:12628 startUdpPunchBridge
	if berr != nil {
		e.app.netLog("warn", "voxlink.turn.hotstandby_bridge_failed")
		e.mu.Lock()
		e.rRudp = nil
		e.mu.Unlock()
		_ = sb.Close()
		return false
	}
	e.mu.Lock()
	e.rBridge = bridge
	e.mu.Unlock()
	e.app.netLog("info", "voxlink.turn.hotstandby_promoted")
	e.relaySuccess(e.relayAttemptCur(), local)
	return true
}

// startHotstandbyKeepalive / stopHotstandbyKeepalive 对应 CM:12487 与 12531 的定时器对。
func (e *connEngine) startHotstandbyKeepalive(tr *rudpConn) {
	e.stopHotstandbyKeepalive()
	stop := make(chan struct{})
	e.mu.Lock()
	e.hotstandbyKbStop = func() {
		select {
		case <-stop:
		default:
			close(stop)
		}
	}
	e.mu.Unlock()
	go func() {
		t := time.NewTicker(hotstandbyKeepaliveEvery)
		defer t.Stop()
		for {
			select {
			case <-stop:
				return
			case <-t.C:
				tr.sendKeepaliveFrame() // CM:12487 → RUDP:443-446：走带 running&&connected 守卫的入口
			}
		}
	}()
}

func (e *connEngine) stopHotstandbyKeepalive() {
	e.mu.Lock()
	stop := e.hotstandbyKbStop
	e.hotstandbyKbStop = nil
	e.mu.Unlock()
	if stop != nil {
		stop()
	}
}

// resetTurnBgFlags 对应 CM:12718-12721（cleanupTurnQuietly）与会话终结处的复位：
// 漏复位会让下一会话的后台升级 CAS 恒失败，直连升级永久失效（模组注释点名 P1-4）。
func (e *connEngine) resetTurnBgFlags() {
	e.mu.Lock()
	sb := e.turnHotstandby
	e.turnHotstandby = nil
	e.turnBgPunchWon = false
	e.turnP2pGivenUp = false
	e.turnSwitchedToP2p = false
	e.turnBgTicks = 0
	e.turnSwitchStreak = 0
	e.turnBgDeadlineMs = 0
	e.mu.Unlock()
	e.stopTurnBgMonitorInternal()
	e.stopTurnSwitchWatch()
	e.stopHotstandbyKeepalive()
	if sb != nil {
		_ = sb.Close()
	}
}

// bgPunchTemplate 取当前档位的模板三元组（CM:845-857 applyPunchTemplate 用同一份：
// setProfile(punchProfile()) + setPunchParams(activePunchParams) + setAuthKey(activePunchAuthKey)）。
func (e *connEngine) bgPunchTemplate() (*punchProfile, *punchParams, []byte) {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.gPunchTemplate != nil {
		t := e.gPunchTemplate
		return t.profile, t.params, t.authKey
	}
	profile := punchProfileNow()
	return profile, punchParamsFromProfile(profile), nil
}
