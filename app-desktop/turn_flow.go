package main

// turn_flow.go — TURN 中继编排（对齐模组 ConnectionManager 的 TURN 段）：
//   guest（玩家主动触发）: 拉节点 → PING 测延迟 → 选最低 → stdTurn(双方声明+节点双栈)
//     走 RFC 5766，否则自定义协议 v1 → allocate → BIND(3轮×5发+TCP兜底+ROLE_CONFLICT容忍)
//     → 发 turn_alloc → 等 host turn_ready（20s 兜底 / 35s 总预算）
//   host: 收 turn_alloc → bind/allocate+channelBind → 建 transport → 起 host 桥 →
//     回 turn_ready（4s 投递保险重发）
//   保活: v1 15s KEEPALIVE；stdTurn 240s Refresh+ChannelBind（发了就算）
//   失败/断线: teardownTurn（UNBIND/Refresh(lifetime=0) + 拆桥）

import (
	"fmt"
	"net"
	"time"
)

const (
	turnReadyTimeout      = 20 * time.Second // guest 等 host turn_ready 兜底
	turnTotalBudget       = 35 * time.Second // guest TURN 流程总预算
	turnReadyResendDelay  = 4 * time.Second  // host turn_ready 投递保险重发
	stdTurnKeepaliveEvery = 240 * time.Second
	stdTurnOpTimeoutMs    = 8000
	stdTurnChannel        = 0x4000
)

// hostCapabilitiesOf 取房主能力表原始数组（join 回包 room.hostCapabilities，
// server-go/roommanager.go:897 透出；JSON 反序列化后为 []any）。
func hostCapabilitiesOf(room map[string]any) []any {
	caps, _ := room["hostCapabilities"].([]any)
	return caps
}

// hostIsLegacy 房主是否旧版（房客视角），对齐 RoomInfo.isHostLegacy
// （fabric .../room/RoomInfo.java:243-245）：hostProtocolVersion==0 或能力表为空。
// 视角为 nil 时按"无房主信息"处理（模组侧 room==null 由各 hostSupportsXxx 直接判 false，
// ProtocolNegotiator.java:65-92）。HL 互通导入房即此形态：protocolVersion=0、caps=[]
// （server-go/hlmaster.go:1451-1452）→ 判旧版，能力一律不采信。
func hostIsLegacy(room map[string]any) bool {
	if room == nil {
		return true
	}
	if roomInt(room, "hostProtocolVersion") == 0 {
		return true
	}
	return len(hostCapabilitiesOf(room)) == 0
}

// hostSupportsCap 房主能力表（room.hostCapabilities）是否含 cap（房客视角）。
// 对齐模组 ProtocolNegotiator hostSupportsPunchAuth/hostSupportsStdTurn/hostSupportsRelayMux
// （.../network/ProtocolNegotiator.java:65-92）三件套的统一口径：
// room != nil && !isHostLegacy() && capabilities.contains(cap)——
// 旧版房主（protocolVersion==0 或空能力表）即便数据里混入了 cap 也不采信。
func hostSupportsCap(room map[string]any, cap string) bool {
	if hostIsLegacy(room) {
		return false
	}
	for _, c := range hostCapabilitiesOf(room) {
		if s, ok := c.(string); ok && s == cap {
			return true
		}
	}
	return false
}

// hostSupportsStdTurn 房主是否支持标准 TURN（RFC 5766），即 ProtocolNegotiator.java:70-72
// 的 hostSupportsStdTurn：!isHostLegacy && caps 含 stdTurnV1。turn_flow 决策点等价改写，
// 保留命名入口对齐模组。
func hostSupportsStdTurn(room map[string]any) bool {
	return hostSupportsCap(room, capNameStdTurnV1)
}

// peerCapsContain 信令 data 里的能力数组是否含 cap（join_request.clientCapabilities）。
// 对端（加入者）侧判定，对齐 ProtocolNegotiator.supportsXxx(PeerInfo)（:47-87）：
// 纯能力表查询、不叠加 legacy 门（legacy 判定另见 peerDataIsLegacy / ProtocolNegotiator.java:43-45）。
func peerCapsContain(data map[string]any, cap string) bool {
	caps, ok := data["clientCapabilities"].([]any)
	if !ok {
		return false
	}
	for _, c := range caps {
		if s, ok := c.(string); ok && s == cap {
			return true
		}
	}
	return false
}

// peerDataIsLegacy join_request data 视角的旧版加入者判定，对齐
// ProtocolNegotiator.isLegacyPeer（:43-45）：peer==null → 旧版；
// protocolVersion==0 || capabilities==nil || capabilities.isEmpty() → 旧版。
// 数据侧依据：RoomInfo.PeerInfo.protocolVersion 默认 0（RoomInfo.java:669），
// 服务端仅当加入者 clientProtocolVersion>0 才在 join_request 里带上
// clientProtocolVersion/clientCapabilities（server-go/roommanager.go:865-869）——
// 字段缺失即 0/空，正是老客户端形态。host 侧 hostPeer 的登记改造见交接 hunk。
func peerDataIsLegacy(data map[string]any) bool {
	if data == nil {
		return true
	}
	if sigInt(data, "clientProtocolVersion") == 0 {
		return true
	}
	caps, ok := data["clientCapabilities"].([]any)
	return !ok || len(caps) == 0
}

// peerCapsLegacy 已登记 hostPeer 形态的旧版判定（供 conn_engine 侧接线）：
// 与 peerDataIsLegacy 同规则，输入改为解析后的 caps 切片 + 协议号。
func peerCapsLegacy(caps []string, protocolVersion int) bool {
	return protocolVersion == 0 || len(caps) == 0
}

// ---- 引擎辅助 ----

func (e *connEngine) turnActive() bool {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.turnSession != nil || e.stdTurnSession != nil
}

func (e *connEngine) turnStillInFlight() bool {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.turnInFlight
}

func (e *connEngine) p2pGuestDone() bool {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.gP2PDone
}

// p2pPeerConnected host 侧：任一房客 rudp/TURN 通路已建立。
func (e *connEngine) p2pPeerConnected() bool {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.turnTransport != nil {
		return true
	}
	for _, p := range e.hPeers {
		if p.rudp != nil {
			return true
		}
	}
	return false
}

// stopTurnKeepalive 停保活循环。
func (e *connEngine) stopTurnKeepalive() {
	e.mu.Lock()
	stop := e.turnKeepaliveStop
	e.turnKeepaliveStop = nil
	e.mu.Unlock()
	if stop != nil {
		stop()
	}
}

func (e *connEngine) startTurnKeepalive(s *turnSession) {
	e.stopTurnKeepalive()
	stop := make(chan struct{})
	e.mu.Lock()
	e.turnKeepaliveStop = func() { close(stop) }
	e.mu.Unlock()
	go func() {
		t := time.NewTicker(turnKeepaliveInterval)
		defer t.Stop()
		for {
			select {
			case <-stop:
				return
			case <-t.C:
				s.sendKeepalive()
			}
		}
	}()
}

func (e *connEngine) startStdTurnKeepalive(s *stdTurnSession) {
	e.stopTurnKeepalive()
	stop := make(chan struct{})
	e.mu.Lock()
	e.turnKeepaliveStop = func() { close(stop) }
	e.mu.Unlock()
	go func() {
		t := time.NewTicker(stdTurnKeepaliveEvery)
		defer t.Stop()
		for {
			select {
			case <-stop:
				return
			case <-t.C:
				stdTurnRefreshQuiet(s, stdTurnDefaultLifetime)
				stdTurnChannelBindQuiet(s)
			}
		}
	}()
}

// teardownTurn 全量拆除 TURN（失败/断线/会话结束共用）。
func (e *connEngine) teardownTurn(notifyFail bool) {
	// CM:12592-12633：TURN 塌掉但热备活着 → 热备转正接管（activeUdpTransports 换键、
	// setUsingRelay(true)、重起桥），并且这一轮**不能**再走失败上报。
	promoted := e.promoteHotstandbyOnTurnDeath()
	if promoted {
		notifyFail = false
	}
	e.mu.Lock()
	// F7 会话纪元：每次拆 +1。异步链（cred/allocate/bind）出发时抓取纪元、写回时比对，
	// 单靠 turnInFlight 判不出来——35s 总预算复位过一轮再重新置 true，晚到的旧会话照样写得进。
	e.turnSessionEpoch++
	s := e.turnSession
	std := e.stdTurnSession
	tr := e.turnTransport
	br := e.turnBridge
	isHost := e.isHost
	e.turnSession = nil
	e.stdTurnSession = nil
	e.turnTransport = nil
	e.turnBridge = nil
	e.turnInFlight = false
	e.turnReadyApplied = false
	e.turnPeerID = ""
	e.mu.Unlock()

	e.stopTurnKeepalive()
	// CM:12531-12536：拆 TURN 时后台监视器、稳定观察窗、热备 keepalive 三路定时器一起撤；
	// 没转正的热备在这一并关掉（转正的那条已交给 rRudp，不能再关）。
	e.stopTurnBgMonitorInternal()
	e.stopTurnSwitchWatch()
	e.stopHotstandbyKeepalive()
	if !promoted {
		e.mu.Lock()
		sb := e.turnHotstandby
		e.turnHotstandby = nil
		e.mu.Unlock()
		if sb != nil {
			_ = sb.Close()
		}
	}
	if s != nil {
		s.unbind()
	}
	if std != nil {
		stdTurnClose(std)
	}
	if br != nil {
		br.stop()
	}
	if tr != nil {
		_ = tr.Close()
	}
	if notifyFail && !isHost {
		// 统一失败口：自动模式下不弹失败屏，交编排器换道/复活打洞（模组 teardownTurn 的 failKey 语义）。
		if e.relayFailForTest != nil {
			e.relayFailForTest(keyAutoTurnFailed)
		} else {
			e.relayFailOrAuto(keyAutoTurnFailed, "TURN 中继建立失败")
		}
	}
}

// turnEpochNow 抓取当前 TURN 会话纪元，供异步链在写回前比对（F7）。
func (e *connEngine) turnEpochNow() int64 {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.turnSessionEpoch
}

// turnWriteBackAllowed 异步 TURN 链的写回屏障：
//   - 纪元变了 → 期间已被拆过一次（含 35s 总预算的 teardown），这条晚到的隧道是孤儿，丢弃；
//   - 房客侧还要求 turnInFlight 仍为真（模组同一条判据）；
//   - 会话已没了 → 直接丢弃。
//
// 光靠 turnInFlight 判不住：它是布尔位，拆掉后新一轮会重新置 true，旧链晚到时看不出"已经不是那一轮"。
func (e *connEngine) turnWriteBackAllowed(epoch int64, needInFlight bool) bool {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.turnSessionEpoch != epoch || e.sess == nil {
		return false
	}
	if needInFlight && !e.turnInFlight {
		return false
	}
	return true
}

// sendTurnNack host → guest 拒绝/失败回执。
func (e *connEngine) sendTurnNack(to, reason string) {
	_ = e.sendSignal("turn_nack", map[string]any{"reason": reason}, to)
}

// ---- guest：触发与主流程 ----

// useTurnRelay 玩家点击"TURN 中继"（仅房客；直连已赢/进行中则忽略）。
func (e *connEngine) useTurnRelay() error {
	e.mu.Lock()
	if e.sess == nil || e.isHost {
		e.mu.Unlock()
		return fmt.Errorf("仅房客可使用 TURN 中继")
	}
	if e.turnInFlight || e.turnSession != nil || e.stdTurnSession != nil {
		e.mu.Unlock()
		return nil
	}
	if e.gP2PDone {
		e.mu.Unlock()
		e.app.netLog("info", "直连已建立，跳过 TURN 请求")
		return nil
	}
	e.turnInFlight = true
	e.turnReadyApplied = false
	code, token, clientID := e.code, e.token, e.clientID
	room := e.app.snapshotRoom()
	e.mu.Unlock()

	go e.turnGuestFlow(code, token, clientID, room)
	return nil
}

func (e *connEngine) turnGuestFlow(code, token, clientID string, room map[string]any) {
	e.notifyState(phaseTurn, statusTrying, "", "")
	e.app.netLog("info", "TURN 中继：正在选择节点…")

	fail := func() {
		e.teardownTurn(true)
	}

	// 35s 总预算：期间未就绪即拆（异步链的写回屏障按 turnInFlight 判定）
	budget := time.AfterFunc(turnTotalBudget, func() {
		if !e.p2pGuestDone() && !e.turnReady() {
			e.app.netLog("warn", "TURN 流程总超时（35s），拆除")
			e.teardownTurn(true)
		}
	})
	defer budget.Stop()

	nodes, err := turnFetchNodeList(e.app)
	if err != nil || len(nodes) == 0 {
		e.app.netLog("warn", "TURN 节点列表获取失败: "+errString(err))
		fail()
		return
	}
	probed := turnProbeNodes(nodes)
	if len(probed) == 0 {
		fail()
		return
	}
	// 探测全超时不判死（弱网丢包常见）：照常 allocate+bind，bind 自带重试
	node := probed[0].node
	if probed[0].rttMs < 0 {
		e.app.netLog("warn", "全部节点探测超时，仍尝试 bind（弱网）")
	} else {
		e.app.netLog("info", fmt.Sprintf("已选节点 %s（rtt=%dms）", node.ID, probed[0].rttMs))
	}

	// 标准 TURN（双方 stdTurnV1 + 节点双栈 stdTurnPort>0）→ RFC 5766；
	// 任一端旧版/节点未启用 → 自定义协议 v1
	if node.StdTurnPort > 0 && hostSupportsCap(room, "stdTurnV1") {
		e.app.netLog("info", "节点双栈+房主声明 stdTurnV1：走标准 TURN（RFC 5766）")
		e.stdTurnGuestFlow(node, code, token, clientID)
		return
	}
	e.turnV1GuestFlow(node, code, token, clientID, room)
}

func (e *connEngine) turnReady() bool {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.turnTransport != nil && e.turnTransport.IsConnected()
}

// turnV1GuestFlow 自定义协议 v1（:37000 BIND/DATA）。
func (e *connEngine) turnV1GuestFlow(node turnNodeInfo, code, token, clientID string, room map[string]any) {
	epoch := e.turnEpochNow() // F7：本轮异步链的凭证，写回时比对
	e.app.netLog("info", "TURN 中继：正在分配会话…")
	alloc, err := turnAllocate(e.app, code, clientID, token, node.ID)
	if err != nil {
		e.app.netLog("warn", "TURN allocate 失败: "+err.Error())
		e.teardownTurn(true)
		return
	}
	// 竞态二次检查：列表+测延迟+allocate 耗费数秒，期间直连可能已打通
	if e.p2pGuestDone() && !e.turnActive() {
		turnRelease(e.app, code, clientID, token, alloc.SessionIDHex)
		e.teardownTurn(false)
		return
	}

	session := newTurnSession(alloc, turnRoleGuest, alloc.GuestTicket)
	// 3 轮重试（每轮 5 发）：弱网单轮全丢很常见
	bindCode := turnBindWithRetry(session, 3, e.app.netLog)
	if bindCode == turnBindRoleConflict {
		bindCode = turnBindOK
		e.app.netLog("info", "TURN bind ROLE_CONFLICT 容忍（会话已绑定）")
	}
	if bindCode != turnBindOK {
		e.app.netLog("warn", fmt.Sprintf("TURN bind 失败 code=%d", bindCode))
		session.unbind()
		e.teardownTurn(true)
		return
	}
	// 写回屏障（F7）：总预算超时的 teardown 已把 turnInFlight 置 false，或期间已换新纪元时中止
	if !e.turnWriteBackAllowed(epoch, true) {
		session.unbind()
		e.app.netLog("warn", "TURN 流程在 bind 期间被取消，丢弃会话")
		return
	}

	e.mu.Lock()
	e.turnSession = session
	e.turnPeerID = "host"
	sock := session.socket()
	ep := session.endpoint()
	e.mu.Unlock()
	e.startTurnKeepalive(session)
	e.app.netLog("info", "TURN guest 会话已绑定，发送 turn_alloc")

	transport := newRudp(sock, ep)
	var codec turnPathCodec
	copy(codec.sessionID[:], session.sessionID[:])
	codec.fromRole = turnRoleGuest
	codec.toRole = turnRoleHost
	transport.setCodec(codec)
	e.applyTurnAuth(transport, code, clientID, room, nil)

	e.mu.Lock()
	e.turnTransport = transport
	e.mu.Unlock()

	_ = e.sendSignal("turn_alloc", map[string]any{
		"sessionId": alloc.SessionIDHex,
		"host":      alloc.Host,
		"port":      alloc.Port,
		"ticket":    alloc.HostTicket,
		"expire":    alloc.ExpireSec,
		"clientId":  clientID,
		"punchAuth": true,
	}, "host")

	// turn_ready 兜底 20s（handleTurnReady 到达即 start+桥接）
	time.AfterFunc(turnReadyTimeout, func() {
		e.mu.Lock()
		noReady := e.turnTransport == nil || !e.turnTransport.started.Load()
		e.mu.Unlock()
		if noReady && !e.p2pGuestDone() {
			e.app.netLog("warn", "20s 未收到 turn_ready（房主失联/信令丢失），拆除 TURN")
			e.teardownTurn(true)
		}
	})
}

// stdTurnGuestFlow 标准 TURN guest（RFC 5766）：签 cred → Allocate → 发 turn_alloc
// （带本端 relay 地址）→ 等 host turn_ready（handleStdTurnReady 里 ChannelBind+桥接）。
func (e *connEngine) stdTurnGuestFlow(node turnNodeInfo, code, token, clientID string) {
	epoch := e.turnEpochNow() // F7：本轮异步链的凭证
	e.app.netLog("info", "标准 TURN：正在签发凭证…")
	cred := stdTurnFetchCred(e.app, code, clientID, token, node.ID)
	if cred == nil {
		e.app.netLog("warn", "标准 TURN 凭证签发失败")
		e.teardownTurn(true)
		return
	}
	if e.p2pGuestDone() && !e.turnActive() {
		e.teardownTurn(false)
		return
	}
	session := stdTurnAllocate(cred.Host, cred.Port, cred.Username, cred.Password, stdTurnOpTimeoutMs)
	if session == nil {
		e.app.netLog("warn", "标准 TURN allocate 失败")
		e.teardownTurn(true)
		return
	}
	if !e.turnWriteBackAllowed(epoch, true) {
		stdTurnClose(session)
		e.app.netLog("warn", "标准 TURN 流程在 allocate 期间被取消，丢弃会话")
		return
	}
	e.mu.Lock()
	e.stdTurnSession = session
	e.turnPeerID = "host"
	e.mu.Unlock()
	e.startStdTurnKeepalive(session)
	relay := session.relayAddr.Load()
	e.app.netLog("info", "标准 TURN guest 已分配 relay，发送 turn_alloc")

	_ = e.sendSignal("turn_alloc", map[string]any{
		"stdTurn":     true,
		"nodeId":      node.ID,
		"nodeHost":    node.Host,
		"stdTurnPort": node.StdTurnPort,
		"relayHost":   relay.IP.String(),
		"relayPort":   relay.Port,
		"clientId":    clientID,
		"punchAuth":   true,
	}, "host")

	time.AfterFunc(turnReadyTimeout, func() {
		e.mu.Lock()
		noReady := e.turnTransport == nil || !e.turnTransport.started.Load()
		e.mu.Unlock()
		if noReady && !e.p2pGuestDone() {
			e.app.netLog("warn", "20s 未收到 stdTurn turn_ready，拆除 TURN")
			e.teardownTurn(true)
		}
	})
}

// applyTurnAuth TURN 路径密钥现场重派生（不沿用打洞阶段密钥）：
// guest 按 host 能力表；host 按 turn_alloc 携带的 clientId+punchAuth 声明。
func (e *connEngine) applyTurnAuth(rc *rudpConn, roomCode, joinerClientID string, room map[string]any, allocData map[string]any) {
	var key []byte
	if room != nil {
		// guest 侧：host 声明 punchAuthV1 → key = derive(code, 本端 clientId)
		if hostSupportsCap(room, "punchAuthV1") && joinerClientID != "" {
			key = punchAuthDeriveDirectKey(roomCode, joinerClientID)
		}
	} else if allocData != nil {
		// host 侧：guest 显式声明 punchAuth → key = derive(code, guest clientId)
		if sigBool(allocData, "punchAuth") {
			peerID := sigStr(allocData, "clientId")
			if peerID != "" {
				key = punchAuthDeriveDirectKey(roomCode, peerID)
			}
		}
	}
	if key != nil {
		rc.setAuthKey(key, true)
		e.app.netLog("info", "TURN 传输层认证已启用")
	} else {
		e.app.netLog("info", "TURN 传输层明文（对端未声明 punchAuthV1）")
	}
}

// ---- host：turn_alloc 处理 ----

func (e *connEngine) hostOnTurnAlloc(from string, data map[string]any) {
	if !e.roleIsHost() {
		return
	}
	if sigBool(data, "stdTurn") {
		e.hostOnStdTurnAlloc(from, data)
		return
	}
	// guest 点中继期间直连恰好打通：静默忽略 + nack（guest 兜底超时自动释放）
	if e.p2pPeerConnected() && !e.turnActive() {
		e.app.netLog("info", "直连已建立，忽略 turn_alloc")
		e.sendTurnNack(from, "direct_won")
		return
	}
	e.mu.Lock()
	if e.turnSession != nil || e.stdTurnSession != nil {
		e.mu.Unlock()
		return
	}
	e.mu.Unlock()

	sessionIDHex := sigStr(data, "sessionId")
	epoch := e.turnEpochNow() // F7：host 侧异步链（allocate+BIND 可达十几秒）的写回凭证
	host := sigStr(data, "host")
	port := sigInt(data, "port")
	ticket := sigStr(data, "ticket")
	expire := sigInt64(data, "expire")
	if len(sessionIDHex) != 32 || host == "" || port <= 0 || ticket == "" {
		e.sendTurnNack(from, "bad_alloc")
		return
	}

	go func() {
		alloc := &turnAllocation{SessionIDHex: sessionIDHex, Host: host, Port: port, HostTicket: ticket, GuestTicket: ticket, ExpireSec: expire}
		session := newTurnSession(alloc, turnRoleHost, ticket)
		// 3 轮重试（总预算 ~16s < guest 的 20s turn_ready 等待）
		code := turnBindWithRetry(session, 3, e.app.netLog)
		if code == turnBindRoleConflict {
			code = turnBindOK
			e.app.netLog("info", "TURN host bind ROLE_CONFLICT 容忍（首发已被接受，响应丢失）")
		}
		if code != turnBindOK {
			e.app.netLog("warn", fmt.Sprintf("TURN host bind 失败 code=%d", code))
			e.sendTurnNack(from, "bind_failed")
			session.unbind()
			return
		}

		e.mu.Lock()
		if e.turnSession != nil || e.stdTurnSession != nil || e.sess == nil || epoch != e.turnSessionEpoch {
			e.mu.Unlock()
			session.unbind()
			return
		}
		e.turnSession = session
		e.turnPeerID = from
		sock := session.socket()
		ep := session.endpoint()
		mcPort := e.hostPort
		roomCode := e.code
		e.mu.Unlock()
		e.startTurnKeepalive(session)

		transport := newRudp(sock, ep)
		var codec turnPathCodec
		copy(codec.sessionID[:], session.sessionID[:])
		codec.fromRole = turnRoleHost
		codec.toRole = turnRoleGuest
		transport.setCodec(codec)
		e.applyTurnAuth(transport, roomCode, "", nil, data)

		e.mu.Lock()
		e.turnTransport = transport
		e.mu.Unlock()
		// TURN 承载**不开 mux**：模组全仓只有一处 enableMux()，在直连工厂 establishUdpTransport 里
		// （CM:15711），四条 TURN 传输（CM:11678/11875/12007/12140）都是 new ReliableUdpTransport(...)
		// 直接造的，从不进 mux。原先这里按 turn_alloc 的 clientCapabilities 判，而该信令根本不带这个
		// 字段（恒假）——于是房主没开、房客按房间能力表开了，房客每条 DATA 多 1 字节 chan 被房主当
		// 原始字节灌进 MC 流，走 TURN 必进不去服。两边都必须不开。
		transport.Start()
		startHostLazyBridge(transport, mcPort, e.app.netLog)
		// CM:11918 / CM:12052：房主侧 TURN 承载起来后开后台升直连监视器
		e.startTurnBgMonitor()

		_ = e.sendSignal("turn_ready", map[string]any{"clientId": e.hostClientID()}, from)
		// 投递保险：4s 后补发一次（会话仍在且对端未换）
		time.AfterFunc(turnReadyResendDelay, func() {
			e.mu.Lock()
			same := e.turnSession == session && e.turnPeerID == from
			e.mu.Unlock()
			if same {
				_ = e.sendSignal("turn_ready", map[string]any{"clientId": e.hostClientID()}, from)
				e.app.netLog("info", "turn_ready 已补发（投递保险）")
			}
		})
		e.app.netLog("info", "TURN host 通路已建立")
	}()
}

func (e *connEngine) hostOnStdTurnAlloc(from string, data map[string]any) {
	if e.p2pPeerConnected() && !e.turnActive() {
		e.app.netLog("info", "直连已建立，忽略 stdTurn turn_alloc")
		e.sendTurnNack(from, "direct_won")
		return
	}
	e.mu.Lock()
	if e.turnSession != nil || e.stdTurnSession != nil {
		e.mu.Unlock()
		return
	}
	e.mu.Unlock()

	relayHost := sigStr(data, "relayHost")
	relayPort := sigInt(data, "relayPort")
	nodeID := sigStr(data, "nodeId")
	if relayHost == "" || relayPort <= 0 || nodeID == "" {
		e.sendTurnNack(from, "bad_alloc")
		return
	}
	epoch := e.turnEpochNow() // F7：标准 TURN host 链（cred+allocate+bind）的写回凭证

	go func() {
		e.mu.Lock()
		code, token, roomCode, mcPort := e.code, e.token, e.code, e.hostPort
		e.mu.Unlock()
		cred := stdTurnFetchCred(e.app, code, e.hostClientID(), token, nodeID)
		if cred == nil {
			e.app.netLog("warn", "标准 TURN host 凭证签发失败")
			e.sendTurnNack(from, "std_cred_failed")
			return
		}
		session := stdTurnAllocate(cred.Host, cred.Port, cred.Username, cred.Password, stdTurnOpTimeoutMs)
		if session == nil {
			e.app.netLog("warn", "标准 TURN host allocate 失败")
			e.sendTurnNack(from, "std_alloc_failed")
			return
		}
		peer := netUDPAddrHostPort(relayHost, relayPort)
		if peer == nil || peer.IP == nil {
			// 对端 relay 地址不是合法 IP 字面量时，putXorAddress 会解引用 nil IP 直接崩进程
			// （模组 1.2.0 F12 同一处：房客一条 turn_alloc{relayHost:"evil.com"} 就能打死房主）。
			e.app.netLog("warn", "标准 TURN host 对端 relay 地址不可解析")
			stdTurnClose(session)
			e.sendTurnNack(from, "std_bind_failed")
			return
		}
		if !stdTurnChannelBind(session, peer, stdTurnChannel, stdTurnOpTimeoutMs) {
			e.app.netLog("warn", "标准 TURN host ChannelBind 失败")
			stdTurnClose(session)
			e.sendTurnNack(from, "std_bind_failed")
			return
		}
		e.mu.Lock()
		if e.turnSession != nil || e.stdTurnSession != nil || e.sess == nil || epoch != e.turnSessionEpoch {
			e.mu.Unlock()
			stdTurnClose(session)
			return
		}
		e.stdTurnSession = session
		e.turnPeerID = from
		e.mu.Unlock()
		e.startStdTurnKeepalive(session)

		transport := newRudp(session.sock, session.endpoint())
		transport.setCodec(stdTurnPathCodec{channel: stdTurnChannel})
		e.applyTurnAuth(transport, roomCode, "", nil, data)

		e.mu.Lock()
		e.turnTransport = transport
		e.mu.Unlock()
		// TURN 承载不开 mux（同上 CM:15711 只在直连工厂里 enableMux）
		transport.Start()
		startHostLazyBridge(transport, mcPort, e.app.netLog)
		// CM:11918 / CM:12052：房主侧 TURN 承载起来后开后台升直连监视器
		e.startTurnBgMonitor()

		relay := session.relayAddr.Load()
		ready := map[string]any{
			"stdTurn":   true,
			"relayHost": relay.IP.String(),
			"relayPort": relay.Port,
			"clientId":  e.hostClientID(),
		}
		_ = e.sendSignal("turn_ready", ready, from)
		time.AfterFunc(turnReadyResendDelay, func() {
			e.mu.Lock()
			same := e.stdTurnSession == session && e.turnPeerID == from
			e.mu.Unlock()
			if same {
				_ = e.sendSignal("turn_ready", ready, from)
				e.app.netLog("info", "stdTurn turn_ready 已补发（投递保险）")
			}
		})
		e.app.netLog("info", "标准 TURN host 通路已建立")
	}()
}

// hostClientID host 侧本端 clientID（app 作房主时为空串，仅回执展示用）。
func (e *connEngine) hostClientID() string {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.clientID
}

// ---- guest：turn_ready 处理 ----

func (e *connEngine) guestOnTurnReady(data map[string]any) {
	if e.roleIsHost() {
		return
	}
	if sigBool(data, "stdTurn") {
		e.guestOnStdTurnReady(data)
		return
	}
	e.mu.Lock()
	transport := e.turnTransport
	if transport == nil || transport.started.Load() || e.turnSession == nil {
		e.mu.Unlock()
		return
	}
	if e.gP2PDone && !e.turnActive() {
		e.mu.Unlock()
		e.teardownTurn(false)
		return
	}
	if e.turnReadyApplied {
		e.mu.Unlock()
		return
	}
	e.turnReadyApplied = true
	e.turnInFlight = false
	transport.started.Store(true)
	e.mu.Unlock()

	// TURN 承载不开 mux：模组四条 TURN 传输都不 enableMux（见房主侧注释），房客单边开
	// 会让线上载荷多 1 字节 chan，房主按原始字节写进 MC 流 → 登录即崩/被踢。
	transport.Start()
	e.turnGuestBridgeUp(transport)
}

func (e *connEngine) guestOnStdTurnReady(data map[string]any) {
	e.mu.Lock()
	session := e.stdTurnSession
	if session == nil {
		e.mu.Unlock()
		return
	}
	if e.gP2PDone && !e.turnActive() {
		e.mu.Unlock()
		e.teardownTurn(false)
		return
	}
	if e.turnReadyApplied {
		e.mu.Unlock()
		return
	}
	e.turnReadyApplied = true
	e.mu.Unlock()

	relayHost := sigStr(data, "relayHost")
	relayPort := sigInt(data, "relayPort")
	if relayHost == "" || relayPort <= 0 {
		e.teardownTurn(true)
		return
	}
	// ChannelBind 同步事务离开信令分发线程
	go func() {
		peer := netUDPAddrHostPort(relayHost, relayPort)
		if peer == nil || peer.IP == nil {
			e.app.netLog("warn", "标准 TURN guest 对端 relay 地址不可解析")
			e.teardownTurn(true)
			return
		}
		if !stdTurnChannelBind(session, peer, stdTurnChannel, stdTurnOpTimeoutMs) {
			e.app.netLog("warn", "标准 TURN guest ChannelBind 失败")
			e.teardownTurn(true)
			return
		}
		// 竞态屏障：ChannelBind 期间 teardown 可能已拆会话
		e.mu.Lock()
		if e.stdTurnSession != session {
			e.mu.Unlock()
			return
		}
		transport := newRudp(session.sock, session.endpoint())
		transport.setCodec(stdTurnPathCodec{channel: stdTurnChannel})
		transport.started.Store(true)
		e.turnInFlight = false
		e.turnTransport = transport
		room := e.app.snapshotRoom()
		e.mu.Unlock()
		e.applyTurnAuth(transport, e.roomCode(), e.turnJoinerID(), room, nil)

		// TURN 承载不开 mux（同上）
		transport.Start()
		e.turnGuestBridgeUp(transport)
	}()
}

// turnGuestBridgeUp guest 侧桥接：本地监听桥 + 成功上报。
func (e *connEngine) turnGuestBridgeUp(transport *rudpConn) {
	// CM:12093 / CM:12166：房客侧 TURN 承载起来后开后台升直连监视器
	e.startTurnBgMonitor()
	// 载具热切优先（模组 startUdpPunchBridge 第一分支）：旧道被 25s 验证窗判死时 gP2PDone 仍是 true，
	// 若先看下面那道"已完成就关新传输"的守卫，新道会被自己关掉——换道必须抢在它前面。
	if e.tryCarrierSwap(transport) {
		return
	}
	e.mu.Lock()
	if e.gP2PDone {
		e.mu.Unlock()
		_ = transport.Close()
		return
	}
	e.gP2PDone = true
	e.mu.Unlock()

	addr, bridge, err := startGuestBridge(transport, func() {
		e.app.netLog("warn", "TURN 桥已断开")
		e.teardownTurn(false)
		e.mu.Lock()
		e.gP2PDone = false
		e.mu.Unlock()
	})
	if err != nil {
		e.mu.Lock()
		e.gP2PDone = false
		e.mu.Unlock()
		_ = transport.Close()
		e.notifyState(phaseTurn, statusFailed, "", "本地桥建立失败："+err.Error())
		return
	}
	transport.setOnRestart(func() {
		e.app.netLog("warn", "对端请求 ICE 重启，拆除 TURN 重新协商")
		e.teardownTurn(false)
	})
	e.mu.Lock()
	e.turnBridge = bridge
	e.mu.Unlock()
	e.app.netLog("info", "TURN 中继已建立")
	// 中继模式：起上行记账 + 25s 真连接验证窗，turnPath=true（CM:14986-14989）。
	startJoinerJournal()
	e.armRelayVerifyWatchdog(true)
	e.notifyState(phaseTurn, statusSuccess, addr, "")
}

// ---- guest：turn_nack ----

func (e *connEngine) guestOnTurnNack(data map[string]any) {
	reason := sigStr(data, "reason")
	e.app.netLog("warn", "TURN 被房主拒绝（"+reason+"），提前拆除")
	e.teardownTurn(true)
}

func errString(err error) string {
	if err == nil {
		return "无可用节点"
	}
	return err.Error()
}

// netUDPAddrHostPort 由 host:port 构造 *net.UDPAddr（解析失败返回 nil）。
func netUDPAddrHostPort(host string, port int) *net.UDPAddr {
	ip := net.ParseIP(host)
	if ip == nil || port <= 0 {
		return nil
	}
	return &net.UDPAddr{IP: ip, Port: port}
}

// roomCode 当前房间码（快照）。
func (e *connEngine) roomCode() string {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.code
}

// turnJoinerID 本端作为加入者的 clientId（快照）。
func (e *connEngine) turnJoinerID() string {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.clientID
}
