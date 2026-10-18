package main

// relay.go — 玩家中继（尽力互通模组 RelayBridge / ConnectionManager 的 relay_* 信令）：
//   - guest 发 relay_request {clientId} to="host"（20s 无响应 failed）。
//   - 收 relay_notify 分派：{relayIp,relayPort} → 用打洞 socket punch 中继者（15s）→
//     建 rudp → 本地桥 → emit success → 发 relay_ready {clientId} to="host"；
//     {connected:true} → 中继链路已就绪（模组间互桥），等我们到中继者的 rudp 起来同样处理。
//   - 失败/被拒：relay_declined 或超时 → failed。
//   - host 收 relay_request：查 GET /relay/candidates?excludeRoom=<本房>（候选池=除本房外所有
//     房间，服务端已滤 sym/unknown 并洗牌）→ 本地再排 requester/self/自家房客，按已派发次数
//     取最闲的一个 → 向其发 relay_setup {targetClientId, targetIp, targetPort, replyHostId}
//     （服务端跨房间转发）；同时给 requester 回 relay_notify {relayIp, relayPort}。
//     候选为空 → relay_declined。收候选的 relay_accept {forClientId} → 转告该房客
//     relay_notify {connected:true}。
//   - 我们作为中继节点（guest 收 relay_setup）：与 target punch（15s）→ 本端到 host 的传输
//     必须已 mux 化（relayMuxV1），被中继会话走它上面的子通道 chan 1..7，
//     与我们自己的会话（chan 0）字节级隔离 → 回 relay_accept {forClientId, chan}；
//     没有到 host 的隧道、或传输未 mux 化 → declined（v1 裸流转发已被实证为串流污染，绝不接回）。

import (
	"fmt"
	"net"
	"strings"
	"sync/atomic"
	"time"
)

// punchRelayOnce 对给定目标做单 socket 直打（玩家中继路径，无端口预测）。
// 成功返回赢下的 socket 与对端实际地址；调用方负责失败时关闭 sock。
func punchRelayOnce(sock *net.UDPConn, target *net.UDPAddr, timeout time.Duration) (*net.UDPConn, *net.UDPAddr, error) {
	profile := punchProfileNow()
	params := punchParamsFromProfile(profile)
	params.timeoutMs = int(timeout / time.Millisecond)
	p := newPuncher(sock, profile, params, nil)
	p.setTarget(target)
	res := p.punchPrediction(target.Port, 0, false)
	if res == nil || !res.success {
		return nil, nil, fmt.Errorf("打洞未成功")
	}
	win, actual := p.win()
	if win == nil {
		win = sock
	}
	if actual == nil {
		return win, target, nil
	}
	return win, actual, nil
}

// usePlayerRelay guest 请求玩家中继（App.UsePlayerRelay 调用）。
func (e *connEngine) usePlayerRelay() error {
	e.mu.Lock()
	sess := e.sess
	isHost := e.isHost
	inFlight := e.rInFlight
	live := e.rRudp != nil || e.rBridge != nil
	clientID := e.clientID
	e.mu.Unlock()

	if sess == nil {
		return fmt.Errorf("当前没有房间会话")
	}
	// 与模组同一道门：relayEnabled 关掉时"用别人的中继"整条路直接退出
	// （CM:10520-10526 用 connection.relay_unavailable），自动编排器也就拿不到玩家中继这一道。
	if !e.app.relayEnabled() {
		return fmt.Errorf("中继已在设置中关闭")
	}
	if isHost {
		return fmt.Errorf("房主无需玩家中继")
	}
	if inFlight {
		return fmt.Errorf("玩家中继已在进行中")
	}
	if live {
		return fmt.Errorf("玩家中继已建立")
	}

	// p2p 打洞仍在进行时先停掉（用户显式选择了中继兜底）：
	// 保留其 socket 供中继打洞复用；若 rudp 已建立则 socket 归 rudp 所有，不复用
	e.mu.Lock()
	if e.gPuncher != nil && !e.gP2PDone {
		e.gPuncher.stop()
		e.gPuncher = nil
		if e.gRudp != nil {
			e.gPunchSock = nil
		}
	}
	e.rAttempt++
	attempt := e.rAttempt
	e.rInFlight = true
	e.rResult = make(chan string, 4)
	e.rDone = false
	e.mu.Unlock()
	// CM:1396-1402：进入玩家中继就起 90 秒总闸，防房主侧 8 秒批次递归无限重试。
	e.relayBeginManualRelay()

	e.notifyState(phasePrelay, statusTrying, "", "")
	if err := e.sendSignal("relay_request", map[string]any{"clientId": clientID}, "host"); err != nil {
		e.guestRelayFail(attempt, "发送中继请求失败: "+err.Error())
		return nil
	}
	e.app.netLog("info", "已发送 relay_request，等待房主派发中继…")

	go func() {
		select {
		case addr := <-e.relayResultChan():
			if addr == "" {
				return // 已被清理
			}
		case <-e.ctxDone():
			return
		case <-time.After(relayRequestTimeout):
			e.guestRelayFail(attempt, "中继请求超时（20s 无响应）")
		}
	}()
	return nil
}

func (e *connEngine) relayResultChan() chan string {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.rResult == nil {
		e.rResult = make(chan string, 4)
	}
	return e.rResult
}

// relayResultChanSend 通知中继结果（addr 非空=成功地址；空串=忽略）。
func (e *connEngine) relayResultChanSend(addr string) {
	select {
	case e.relayResultChan() <- addr:
	default:
	}
}

// relayAttemptCur 当前中继尝试轮次号（信令侧无本地轮次上下文时取它）。
func (e *connEngine) relayAttemptCur() int64 {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.rAttempt
}

// guestRelayFail 中继失败收尾（幂等；轮次不符说明本端已开始新一轮，旧轮直接作废）。
func (e *connEngine) guestRelayFail(attempt int64, reason string) {
	e.mu.Lock()
	if attempt != e.rAttempt || e.rDone {
		e.mu.Unlock()
		return
	}
	e.rDone = true
	e.rInFlight = false
	sock, puncher, rudp, bridge := e.rSock, e.rPuncher, e.rRudp, e.rBridge
	e.rSock = nil
	e.rPuncher = nil
	e.rRudp = nil
	e.rBridge = nil
	e.mu.Unlock()
	// 收口批次追踪与 90 秒总闸（CM:11138）；60 秒失败名单保留，
	// 下一批要靠它跳过刚失败的那个（CM:10779-10786）。
	e.relayEndManualRelay()

	if puncher != nil {
		puncher.stop()
	}
	if bridge != nil {
		bridge.stop()
	}
	if rudp != nil {
		_ = rudp.Close()
	}
	if sock != nil {
		_ = sock.Close()
	}
	e.app.netLog("warn", "玩家中继失败: "+reason)
	// 1.2.0 起这里不再是失败终点：自动模式交给编排器换道/复活打洞，只有非自动（旧版对端触发等
	// 手动语义）才落失败（模组 relayFailOrAuto 替换了全部直接 showConnectFailed 的中继失败位点）。
	e.relayFailOrAuto(keyAutoRelayFailed, reason)
}

// guestOnRelayNotify 收到房主的 relay_notify（两种形态分派）。
func (e *connEngine) guestOnRelayNotify(from string, data map[string]any) {
	if from != "host" && from != "" {
		return
	}
	attempt := e.relayAttemptCur()
	relayIP := sigStr(data, "relayIp")
	relayPort := sigInt(data, "relayPort")
	if relayIP != "" && relayPort > 0 {
		// CM:11150-11161 的 TURN 门：TURN 正在承载且还没有热备时，这条 notify 只用来建**热备**，
		// 绝不建第二条本地桥（双桥冲突正是模组注释点名要防的）。
		e.mu.Lock()
		turnLive := e.turnTransport != nil && e.turnTransport.IsConnected()
		haveStandby := e.turnHotstandby != nil
		e.mu.Unlock()
		if turnLive && !haveStandby {
			e.app.netLog("info", "relay_notify 到达且 TURN 承载中：建玩家中继热备，不建第二条桥")
			e.establishHotstandby(relayIP, relayPort)
			return
		}
		e.app.netLog("info", "收到 relay_notify，开始打洞")
		go e.relayPunchFlow(attempt, relayIP, relayPort)
		return
	}
	if sigBool(data, "connected") {
		// 模组间已互桥（中继链路就绪）：等我们到中继者的 rudp 起来同样处理
		e.mu.Lock()
		e.rConnectedHint = true
		already := e.rRudp != nil
		e.mu.Unlock()
		e.app.netLog("info", "收到 relay_notify(connected)：中继链路就绪")
		if already {
			// rudp 已建立但尚未回报（罕见）：直接以现有桥地址回报成功
			if b := e.relayBridgeAddr(); b != "" {
				e.relaySuccess(attempt, b)
			}
		}
	}
}

// relaySuccess 中继链路就绪收尾：回报地址、通知 host（幂等；旧轮次到达直接丢弃）。
// 中继链路 success 的 address 永远是本地桥 127.0.0.1:<port>，绝不暴露公网 IP。
func (e *connEngine) relaySuccess(attempt int64, addr string) {
	e.mu.Lock()
	if attempt != e.rAttempt || e.rDone {
		e.mu.Unlock()
		return
	}
	e.rDone = true
	e.rInFlight = false
	clientID := e.clientID
	e.mu.Unlock()

	e.app.netLog("info", "中继链路就绪")
	e.notifyState(phasePrelay, statusSuccess, addr, "")
	if err := e.sendSignal("relay_ready", map[string]any{"clientId": clientID}, "host"); err != nil {
		e.app.netLog("warn", "发送 relay_ready 失败: "+err.Error())
	}
	e.relayResultChanSend(addr)
}

func (e *connEngine) relayBridgeAddr() string {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.rBridge != nil && e.rBridge.ln != nil {
		return e.rBridge.ln.Addr().String()
	}
	return ""
}

// relayPunchFlow 对中继者打洞（15s）→ rudp → 本地桥 → success → relay_ready。
// attempt 是本轮中继尝试的轮次号：用户在失败后重试会开出新一轮，旧轮晚到的隧道必须丢弃，
// 否则同一 socket 上会跑两条 rudp 互抢帧。
func (e *connEngine) relayPunchFlow(attempt int64, relayIP string, relayPort int) {
	// 复用 p2p 打洞 socket（仅当它不归任何活跃 puncher/rudp 所有时），否则新建
	e.mu.Lock()
	if e.rDone || attempt != e.rAttempt || e.sess == nil {
		e.mu.Unlock()
		return
	}
	// CM:11239-11241 两道门：connectionWon 已置位、或 relay_cone 传输已在册 ⇒ 后来的 notify 作废。
	// rPunchLiveAttempt 补上模组 tryRelay 的 "Relay already trying, skip duplicate"（CM:10528-10530）：
	// 建桥要 15s，期间 rRudp 还是空，两条同 attempt 的 notify 会各自建一条隧道并互相覆盖句柄。
	if e.gP2PDone || e.rRudp != nil || e.rBridge != nil || e.rPunchLiveAttempt == attempt {
		e.mu.Unlock()
		e.app.netLog("warn", "中继隧道已在建立/已建立，忽略重复 relay_notify")
		return
	}
	e.rPunchLiveAttempt = attempt
	sock := e.gPunchSock
	reused := sock != nil && e.gRudp == nil && e.gPuncher == nil
	e.mu.Unlock()
	if !reused {
		s, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4zero, Port: 0})
		if err != nil {
			e.guestRelayFail(attempt, "创建中继 socket 失败: "+err.Error())
			return
		}
		sock = s
	}

	relayAddr := net.ParseIP(relayIP)
	if relayAddr == nil {
		if !reused {
			_ = sock.Close()
		}
		e.guestRelayFail(attempt, "中继地址无法解析: "+relayIP)
		return
	}
	target := &net.UDPAddr{IP: relayAddr, Port: relayPort}
	win, actual, perr := punchRelayOnce(sock, target, relayPunchTimeout)
	if perr != nil {
		if !reused {
			_ = sock.Close()
		}
		e.guestRelayFail(attempt, "中继打洞失败（"+perr.Error()+"）")
		return
	}
	if win != sock {
		e.closeGuestPunchSock(sock)
		sock = win
	}

	rc := newRudp(sock, actual)
	// 载具热切（模组 startUdpPunchBridge 第一分支）：旧道被 25s 验证窗判死时，玩家中继这条新
	// 承载不新建本地桥，只换承载。swapJoinerCarrier 内部会 start()，所以必须抢在 rc.Start() 之前。
	if e.tryCarrierSwap(rc) {
		e.mu.Lock()
		if e.rPunchLiveAttempt == attempt {
			e.rPunchLiveAttempt = 0
		}
		e.mu.Unlock()
		if !reused {
			_ = sock.Close()
		}
		return
	}
	rc.Start()
	local, bridge, berr := startGuestBridge(rc, func() { e.relayBridgeDown() })
	if berr != nil {
		_ = rc.Close()
		e.guestRelayFail(attempt, "中继本地桥建立失败: "+berr.Error())
		return
	}
	e.mu.Lock()
	if e.rDone || attempt != e.rAttempt || e.sess == nil {
		// 写回屏障：打洞/建桥期间 20s 预算已判失败、本端已重开一轮或已退房，晚到的隧道直接丢弃，
		// 否则既漏 socket 又会在"已失败"之后翻一个成功出来
		if e.rPunchLiveAttempt == attempt {
			e.rPunchLiveAttempt = 0
		}
		e.mu.Unlock()
		_ = rc.Close()
		bridge.stop()
		if !reused {
			_ = sock.Close()
		}
		e.app.netLog("warn", "中继隧道建立过晚（本端已判失败或已重开一轮），丢弃")
		return
	}
	e.rSock = sock
	e.rPuncher = nil
	e.rRudp = rc
	e.rBridge = bridge
	e.mu.Unlock()

	e.app.netLog("info", "中继隧道建立")
	// 中继模式：起连接期上行记账（换道重放用）+ 25s 真连接验证窗（CM:14986-14989）。
	// turnPath 传 false——本道是玩家中继，验证通过后还要起 10 分钟后台直连探测。
	startJoinerJournal()
	e.armRelayVerifyWatchdog(false)
	e.relaySuccess(attempt, local)
}

// relayBridgeDown 中继桥拆除断开。
func (e *connEngine) relayBridgeDown() {
	e.mu.Lock()
	rc, bridge := e.rRudp, e.rBridge
	e.rRudp = nil
	e.rBridge = nil
	e.mu.Unlock()
	if bridge != nil {
		bridge.stop()
	}
	if rc != nil {
		_ = rc.Close()
	}
	e.notifyState(phasePrelay, statusFailed, "", "中继隧道已断开")
}

// ---- host：relay_request 派发 ----

// 跨房全局候选池的三件旧物（relayCandidate / fetchRelayCandidates / pickRelayCandidate）已随模组退役：
// 模组在 CM:10879-10883 明确注释这条池会把别的房间的人派进本房当中继点（跨房串流污染），
// 现改为本房成员内挑选（relay_candidates.go 的 pickRelayCandidates），旧路径整体删除。

// hostOnRelayRequest 房主收到 relay_request：挑选候选中继者并派发。
func (e *connEngine) hostOnRelayRequest(from string, data map[string]any) {
	e.app.netLog("info", "收到 relay_request")

	e.mu.Lock()
	requester := e.hPeers[from]
	sess := e.sess
	roomCode := e.code
	// mapped 由 hostOnPunchInfo 在锁内写，这里也必须锁内取（L5 同类）
	var reqIP string
	var reqPort int
	if requester != nil && requester.mapped != nil {
		reqIP, reqPort = requester.mapped.IP, requester.mapped.Port
	}
	e.mu.Unlock()

	if reqIP == "" || reqPort <= 0 {
		e.app.netLog("warn", "relay_request 但缺少 requester 映射地址，拒绝")
		_ = e.sendSignal("relay_declined", map[string]any{}, from)
		return
	}

	// 模房的跨房全局候选池（CM:10879-10883 的注释判定它会造成跨房串流污染，已永久停用）。
	// 这里按模组口径改为**只在本房间内**挑：已建立且开启 mux 的锥型中继者、
	// 60 秒失败黑名单、一批最多 3 个并行、8 秒无回音整批拉黑后递归下一批、90 秒总闸兜底。
	e.relayDispatchOnce(sess, from, reqIP, reqPort, func(batch []*hostPeer, index, size int) {
		// CM:10719-10726：每批留痕"第 N 轮 / M 个候选"。本端是机器可读口径，
		// 只给键与计数（房主界面没有中继按钮，1.2.0 起手动按钮已退役）。
		e.app.netLog("info", fmt.Sprintf("relay_batch_index=%d relay_batch_size=%d", index, size))
		for _, c := range batch {
			setup := map[string]any{
				"targetClientId": from,
				"targetIp":       reqIP,
				"targetPort":     reqPort,
				// CM:11437-11443 relayPeerId：房主有 clientId 用 clientId，否则 "host-<CODE>"。
				// 房主侧没有 clientId（/room/create 不分配），所以这里恒为后者。
				"replyHostId": "host-" + strings.ToUpper(roomCode),
			}
			if err := e.sendSignal("relay_setup", setup, c.id); err != nil {
				e.app.netLog("warn", "发送 relay_setup 失败: "+err.Error())
			}
			notify := map[string]any{"relayIp": c.mapped.IP, "relayPort": c.mapped.Port}
			if err := e.sendSignal("relay_notify", notify, from); err != nil {
				e.app.netLog("warn", "发送 relay_notify 失败: "+err.Error())
			}
		}
		e.app.netLog("info", "中继派发完成")
	})
}

// hostOnRelayAccept 候选者回执：把被中继会话挂到候选者 joiner 传输的 mux 子通道上，
// 再转告自家房客"桥已就绪"（relay_notify{connected}）。
// from 是候选者的 clientId（模组 handleRelayAccept 的 from）——它的传输就是从这儿查的。
func (e *connEngine) hostOnRelayAccept(from string, data map[string]any) {
	forClientID := sigStr(data, "forClientId")
	if forClientID == "" {
		return
	}
	// 玩家中继 v2：relay_accept 带候选者在其 joiner 传输上分配的子通道号，
	// host 侧把该子通道桥到一个独立的本地 MC 会话（被中继房客的专属桥）。
	muxChan := sigInt(data, "chan")

	e.mu.Lock()
	candidate := e.hPeers[from]
	var candidateRudp *rudpConn
	if candidate != nil {
		candidateRudp = candidate.rudp
	}
	mcPort := e.hostPort
	e.mu.Unlock()

	if muxChan <= 0 {
		// 旧版候选者（无 chan 字段）：v1 数据面已被实证为跨流污染，绝不接旧线
		e.app.netLog("warn", "relay_accept 未带 chan（候选者 "+from+" 为旧版），忽略：v1 中继数据面已退役")
		return
	}
	if candidateRudp == nil || !candidateRudp.isMuxEnabled() {
		// 候选者传输缺失/未 mux 化（如候选者刚好退出）：不通知 connected，
		// 被中继侧的超时兜底自然 failover 到下一候选
		e.app.netLog("warn", "relay_accept 来自 "+from+"，但其传输缺失或未 mux 化，忽略")
		return
	}

	e.app.netLog("info", fmt.Sprintf("中继节点已接受：为 %s 挂接 chan%d 子通道桥", forClientID, muxChan))
	startHostMuxRelayBridge(candidateRudp, muxChan, mcPort, e.app.netLog, func() {
		e.app.netLog("info", fmt.Sprintf("被中继会话 %s（经 %s chan%d）已结束", forClientID, from, muxChan))
	})

	if err := e.sendSignal("relay_notify", map[string]any{"connected": true}, forClientID); err != nil {
		e.app.netLog("warn", "转发 relay_notify 失败: "+err.Error())
	}
	// CM:11138：这批有了结果就收口——撤 8 秒批次计时、清 current，
	// 否则 current 常驻会让后续派发被"Relay already trying"那道门永久挡掉。
	e.relayEndManualRelay()
}

// ---- 我们作为中继节点（guest 收 relay_setup）----

// relayMuxChanCounter 子通道轮转计数器（模组 ConnectionManager.relayMuxChanCounter，
// 那是挂在 ConnectionManager 实例上的 AtomicInteger）。
var relayMuxChanCounter atomic.Int64

// allocateRelayMuxChan 1..7 轮转分配被中继会话的子通道（模组 CM:1443-1445）。
// chan 0 是本连接自有流，永不参与分配。
func allocateRelayMuxChan() int {
	n := relayMuxChanCounter.Add(1) - 1 // 等价 getAndIncrement
	if n < 0 {
		n = -n
	}
	return 1 + int(n%7)
}

// guestOnRelaySetup 收到 relay_setup：与 target 打洞，把被中继会话接到本端 joiner 传输的
// mux 子通道上（v2 数据面），再回 relay_accept{forClientId, chan}。
func (e *connEngine) guestOnRelaySetup(from string, data map[string]any) {
	// 同一个开关的另一半语义：关掉后不为别人中继（模组 relayOk 判据，CM:2026/:7596/:11457 一系）。
	if !e.app.relayEnabled() {
		e.app.netLog("info", "中继开关已关闭，婉拒 relay_setup")
		_ = e.sendSignal("relay_declined", map[string]any{}, "host")
		return
	}
	targetClientID := sigStr(data, "targetClientId")
	targetIP := sigStr(data, "targetIp")
	targetPort := sigInt(data, "targetPort")

	e.mu.Lock()
	hostRudp := e.gRudp
	e.mu.Unlock()

	// mux 三查（模组 CM:13215-13235）：被中继会话只能走本端连 host 传输的 mux 子通道。
	// v1 曾在这里复用任意已连接传输转发裸流——两条 MC 会话字节交错进同一条有序流 +
	// InputStream 双读者互抢，1.0.x 起从未真正可用。旧版 host 或本端未 mux 化一律婉拒，
	// 绝不降级回坏接线。
	if hostRudp == nil || !hostRudp.IsConnected() || !hostRudp.isMuxEnabled() {
		if !hostSupportsCap(e.app.snapshotRoom(), relayMuxCapability) {
			e.app.netLog("info", "host 未声明 relayMuxV1，婉拒 relay_setup（v1 中继数据面已被实证为串流污染，绝不接回）")
		} else {
			e.app.netLog("warn", "本端到 host 的传输未 mux 化，婉拒 relay_setup")
		}
		_ = e.sendSignal("relay_declined", map[string]any{}, "host")
		return
	}

	target := net.ParseIP(targetIP)
	if target == nil || targetPort <= 0 {
		// 畸形信令不能拿去打洞：nil IP 的 UDPAddr 会让后续 rebind 把任意来源地址当对端绑定
		e.app.netLog("warn", "relay_setup 目标地址非法，declined")
		_ = e.sendSignal("relay_declined", map[string]any{}, "host")
		return
	}
	e.app.netLog("info", "受托为中继节点")

	go func() {
		sock, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4zero, Port: 0})
		if err != nil {
			_ = e.sendSignal("relay_declined", map[string]any{}, "host")
			return
		}
		win, actual, perr := punchRelayOnce(sock, &net.UDPAddr{IP: target, Port: targetPort}, relaySetupTimeout)
		if perr != nil {
			_ = sock.Close()
			e.app.netLog("warn", "中继节点对 target 打洞失败: "+perr.Error())
			_ = e.sendSignal("relay_declined", map[string]any{}, "host")
			return
		}
		if win != sock {
			_ = sock.Close()
			sock = win
		}
		targetRc := newRudp(sock, actual)
		targetRc.Start()

		// F5：relayKey 必须带 clientId —— chan 1..7 是轮转复用的，纯 chan 当 key
		// 会撞上上一段还没收尾的残留会话，新会话的 leg 就被 putIfAbsent 静默丢掉。
		muxChan := allocateRelayMuxChan()
		peerID := targetClientID
		if peerID == "" {
			peerID = "sym"
		}
		relayKey := fmt.Sprintf("muxrelay-%d-%s", muxChan, peerID)
		rb := getRelayBridge(e.app.netLog)
		// A 腿=本端 joiner 传输上的子通道（与自己的 chan 0 会话字节级隔离），
		// B 腿=对被中继房客的专打传输（整条会话，收尾即关传输）
		rb.startRelay(relayKey, fmt.Sprintf("host-chan%d", muxChan), peerID,
			relayLegOf(rudpMuxRelayStream(hostRudp, muxChan)),
			relayLegOf(rudpBridgeStream(targetRc)))
		e.mu.Lock()
		e.rAsNodeStop = rb.stopAllRelays
		e.mu.Unlock()

		// chan 随 accept 上报：host 侧要靠它把子通道桥到被中继房客的本地 MC 会话，
		// 没有它 host 只会当我们是旧版候选者并忽略（v1 已退役）
		if err := e.sendSignal("relay_accept", map[string]any{"forClientId": peerID, "chan": muxChan}, "host"); err != nil {
			e.app.netLog("warn", "发送 relay_accept 失败: "+err.Error())
		}
		e.app.netLog("info", "中继桥已建立")
	}()
}

// relayRelease 中继资源释放钩子。
// 契约口径："POST /relay/release（若有 TURN/relay 资源）"——该端点要求 /relay/allocate
// 返回的 32 位 TURN sessionId，我们全程未分配 TURN 资源（玩家中继走 P2P rudp 流搬运），
// 因此退出时无需调用；保留钩子以便未来接入 TURN 时补充。
func (e *connEngine) relayRelease() {
	_ = e
}
