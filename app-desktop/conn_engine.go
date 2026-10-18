package main

// conn_engine.go — 连接引擎编排：信令分发、UDP 打洞 → rudp 隧道 → 本地端口映射桥、
// 直连探测（TryDirect）与 MC 端口探测（DetectMcPorts）。玩家中继见 relay.go。
//
// 角色分工（对齐模组 RoomManager/ConnectionManager）：
//   - guest：JoinRoom 成功自动开始 p2p（等 host 的 holepunch_offer → 发 punch_info →
//     等 holepunch_mapped/齐射时刻 → 打洞 → rudp → 本地桥）；TryDirect / UsePlayerRelay
//     由前端按需触发；结果全部经 conn:state 事件推送。
//   - host：收到 join_request 起 host 侧引擎（打洞 socket 绑 hostPort → STUN →
//     签发 holepunch_offer → 收 punch_info 回 holepunch_mapped 并开始打洞 → lazy 桥）。
//     host 端不发 conn:state 事件（host 会话只做应答与桥接）。
//
// 幂等：guest 用 cycle CAS 防重复 holepunch_offer 重入（对齐模组 connectionCycleActive）。

import (
	"context"
	"fmt"
	"net"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// conn:state 事件字段取值
const (
	phaseP2P      = "p2p"
	phaseDirect   = "direct"
	phasePrelay   = "prelay"
	phaseTurn     = "turn"
	statusTrying  = "trying"
	statusFailed  = "failed"
	statusSuccess = "success"
)

const (
	offerWaitMappedMax  = 5 * time.Second  // 等 holepunch_mapped 最长（同时受 punchSyncTimeMs 约束）
	punchSyncMaxWait    = 5 * time.Second  // punchSyncTimeMs 时钟差容忍上限
	punchCycleInterval  = 1 * time.Second  // 两次打洞周期之间的退避
	hostPunchLifetime   = 10 * time.Minute // host 打洞等待窗口（实际受会话寿命约束）
	directAttempts      = 3                // 直连探测尝试次数
	directDialTimeout   = 3 * time.Second
	directRetryInterval = 2 * time.Second
	punchInfoTimeout    = 15 * time.Second // host 等 punch_info 的清理窗口
	relayRequestTimeout = 20 * time.Second // relay_request 无响应判失败
	relayPunchTimeout   = 15 * time.Second // 对中继者打洞超时
	relaySetupTimeout   = 15 * time.Second // 中继节点对 target 打洞超时

	// 打洞终局常量（对齐 mod ConnectionManager：正交，前者管整会话零收包，后者管预测关闭频次）
	zeroRecvFinalRoundLimit = 20 // 持续重试轮次上限：整会话零收包才允许判定终局
	predictionOffCap        = 50 // PREDICTION_OFF 累计频次封顶
)

// emitConnState 推送 conn:state 事件（host 端不调用）。
func (a *App) emitConnState(phase, status, address, detail string, nat map[string]any) {
	payload := map[string]any{
		"phase":   phase,
		"status":  status,
		"address": address,
		"detail":  detail,
	}
	if nat != nil {
		// 加入过程中的三行 NAT 文案（AttemptingJoinScreen.java:900-933 的等价出口）
		payload["nat"] = nat
	}
	a.emit("conn:state", payload)
}

// notifyState 引擎侧状态推送：会话已解绑时静默（避免离开房间后还推失败态给前端）。
func (e *connEngine) notifyState(phase, status, address, detail string) {
	e.mu.Lock()
	alive := e.sess != nil
	e.mu.Unlock()
	if !alive {
		return
	}
	// 日志上传的连接事件登记：模组在 ConnectionManager 的同一批位点调用
	// （:15723 onConnected / :15077 onDisconnected）。notifyState 是本端这些位点的共同出口；
	// onDisconnected 内部要求"曾经连上过且未上传过"，没连上时自然 no-op，所以这里无条件调。
	switch status {
	case statusSuccess:
		e.app.logUploadOnConnected()
	case statusFailed:
		e.app.logUploadOnDisconnected()
	}
	e.mu.Lock()
	localNat, remoteNat := e.gLocalNat, e.gRemoteNat
	e.mu.Unlock()
	e.app.emitConnState(phase, status, address, detail, e.natDisplaySnapshot(localNat, remoteNat))
}

// ---- 引擎 ----

// hostPeer host 侧单个房客的连接上下文。
type hostPeer struct {
	id                 string
	caps               []string // join_request 携带的加入者能力表
	protoVersion       int      // join_request 携带的 clientProtocolVersion，0=旧版（RoomInfo.java:243-245 的 legacy 判据）
	natClass           natClass // punch_info 上报的加入者 NAT 分级
	joinerSym          bool     // punch_info 的 joinerSymmetric 原值（CM:3871，阵列规模与扫窗带宽都读它）
	requestHostLocalIp bool     // punch_info 是否索要局域网地址（CM:3993）
	joinerPortRange    int      // punch_info 上报的加入者预测范围（反向打洞用）
	puncher            *puncher
	punching           bool // CM:2747 HostPunchContext.punching：阵列通告的漂移重发只在打洞进行中继续发
	rudp               *rudpConn
	mapped             *stunMappedAddr // joiner 映射地址（punch_info 上报）
	hostMapped         *stunMappedAddr // 我方打洞 socket 映射（offer 时缓存）
	hostDelta          int
	sameCgnat          bool // 两端映射同一公网 IP（CM:3659-3665）
}

type connEngine struct {
	app *App

	mu       sync.Mutex
	sess     *session
	code     string
	token    string
	isHost   bool
	clientID string
	hostPort int
	hostIp   string

	ctx    context.Context
	cancel context.CancelFunc

	// guest 侧状态
	gCycle         int  // 连接周期代数（幂等）
	gActive        bool // 打洞周期进行中（cycle CAS）
	gOffer         map[string]any
	gLastOffer     map[string]any  // ICE 重启用：最近一次 holepunch_offer
	gMapped        *stunMappedAddr // 最新 hostMapped（holepunch_mapped / offer）
	gMappedCh      chan *stunMappedAddr
	gMappedDelta   int
	gHostPortRange int      // holepunch 系列信令携带的 host 预测范围
	gRemoteNat     natClass // 信令携带的 host NAT 分级（中途可纠偏）
	gPunchSock     *net.UDPConn
	gPuncher       *puncher
	gRudp          *rudpConn
	gBridge        *tcpBridge
	gP2PDone       bool
	gTcpLn         net.Listener // TCP 打洞（tcp_punch.go）成功后的本地代理监听
	gTcpConn       net.Conn     // TCP 打洞得到的远端连接，被所有 MC 接入共享

	// 房主侧阵列状态：roomHostSym 对 RoomInfo.hostSymmetric（CM:4290 写、CM:4421/4599 读），
	// scenarioTier 对 ConnectionManager.scenarioTier（CM:4306 写、CM:3965 读）。
	roomHostSym  bool
	scenarioTier scenarioTier

	// 打洞会话记账（终局判定）
	punchRecvEver      bool          // 整个会话是否收到过对端任何 UDP 包
	predictionOffCount int           // PREDICTION_OFF 累计频次（封顶 50）
	connStartMs        int64         // CM:6590 connectionStartTimeMs
	connTimeoutSec     int           // CM:6625 connectionTimeoutSec
	connTimeoutTimer   *sessionTimer // CM:6701 connectionTimeoutFuture
	retryRound         int           // 持续重试轮次（零收包终局判定）
	retryCancelled     bool          // CM:285 continuousRetryCancelled：对方取消后不再进持续重试轮
	gLocalNat          natClass

	// 智能自动中继编排器（模组 CM:12733-12747，字段含义逐项对应）
	autoRelayDirectorCancel  context.CancelFunc // autoRelayDirectorTask
	autoRelayArmWaiterCancel context.CancelFunc // autoRelayArmWaiterTask（F1 挂载复查）
	relayVerifyCancel        context.CancelFunc // relayVerifyWatchdogTask（25s 真连接验证窗）
	relayBgProbeCancel       context.CancelFunc // relayBgProbeTask（中继后 10 分钟后台直连探测）
	autoRelayTurnAttempted   bool
	autoRelayTurnUnhealthy   bool
	autoRelayPlayerAttempted bool
	autoRelayMode            bool   // 自动尝试进行中：中继失败走换道/回打洞而非失败屏
	autoRelayArmedRound      int    // 触发时的持续重试轮（初值 -1，见 stopAutoRelayAll）
	carrierSwapFrom          string // 旧道形态 "turn"/"player"，换道成功后据此拆旧
	punchUiStartMs           int64  // 打洞计时基准：首周期只设一次，轮次/ICE 重置都不清（CM:322,6592）
	turnSessionEpoch         int64  // TURN 会话纪元（F7 异步写回屏障）

	// 玩家中继候选池（relay_pool.go）
	poolCancel  context.CancelFunc
	poolMapped  *stunMappedAddr
	poolNatType string

	// TCP SimOpen（tcp_punch.go）
	tcpPunchInFlight bool
	tcpPunchToken    int64 // 邀约轮次令牌：35s 看门狗只清自己那一轮的占位
	tcpPunchers      []*tcpPuncher
	relayPrefetched  bool   // CM:6576 relayPrefetchFuture==null 的等价门（一次进程只预热一次）
	gMyMappedIP      string // guest 本端 STUN 映射 IP（tcp_punch_info 用）
	gMyMappedPort    int    // guest 本端 STUN 映射端口（tcp_simopen_request 用，CM:7137）

	// 直连波次（conn_wave.go）：模组 RoomInfo 的两个字段在引擎里的落位
	gHostLocalIp         string                        // 房主内网 IP（CM:2650 setHostLocalIp）
	gSameCgnat           bool                          // 两端映射同一公网 IP（CM:2874/2996 setSameCgnat）
	gHostMappedPorts     []int                         // 房主公布的映射端口列表（CM:3160-3180，生日打洞的输入）
	gLastHostMappedPorts []int                         // 上一次收到的那串，用于漂移判定（CM:3186 lastHostMappedPorts）
	gExtraPunchers       map[string]*guestExtraPuncher // CM:3418-3432 的 joiner_extra_*
	gPunchTemplate       *guestPunchTemplate           // 当前轮档位/参数/密钥，extras 用同一条模板

	// host 侧 NAT 分级（TCP punch 门控等）
	hostNatClass natClass

	// guest 玩家中继（relay.go）
	rInFlight         bool
	rDone             bool
	rAttempt          int64 // 中继尝试轮次号：重试/退房时递增，用于作废上一轮在途收尾
	rPunchLiveAttempt int64 // 本轮 relayPunchFlow 已在建隧道（对应模组 currentRelayPeer 去重）
	rResult           chan string
	rSock             *net.UDPConn
	rPuncher          *puncher
	rRudp             *rudpConn
	rBridge           *tcpBridge
	rConnectedHint    bool
	rAsNodeStop       func()

	// TURN 中继（turn_flow.go）
	turnInFlight      bool
	turnSession       *turnSession
	stdTurnSession    *stdTurnSession
	turnTransport     *rudpConn
	turnBridge        *tcpBridge
	turnKeepaliveStop func()
	turnReadyApplied  bool
	turnPeerID        string

	// TURN 建立后的后台升直连 + 玩家中继热备（CM:235-270 的字段组，逐条对位）
	turnBgMonitorStop   func()       // CM:240 turnBgMonitorTask
	turnBgDeadlineMs    int64        // CM:241 turnBgDeadlineMs（10 分钟窗口）
	turnSwitchWatchStop func()       // CM:243 turnSwitchWatchTask
	turnSwitchStreak    int          // CM:244 turnSwitchStreak
	turnBgPunchWon      bool         // CM:245 turnBgPunchWon（CAS 去重）
	turnP2pGivenUp      bool         // CM:247 turnP2pGivenUp
	turnSwitchedToP2p   bool         // CM:249 turnSwitchedToP2p
	turnBgTicks         int          // CM:253 turnBgTicks
	turnHotstandby      *rudpConn    // CM:258 turnHotstandbyTransport
	hotstandbyKbStop    func()       // CM:270 hotstandbyKeepaliveTask
	relayFailForTest    func(string) // 测试钩子：非 nil 时替代 relayFailOrAuto 观察失败上报
	turnBgAttempts      int          // 观测量：后台直连 puncher 被驱动次数（用例判守卫用）
	turnStandbyAttempts int          // 观测量：热备建立被驱动次数
	// ICE 重启限次与冷却（CM:293 MAX_ATTEMPTS=3、CM:295 COOLDOWN=5000ms、CM:297/299 两个计数）
	iceRestartAttempts int   // CM:297 iceRestartAttempts
	lastIceRestartMs   int64 // CM:299 lastIceRestartTimeMs

	// host 侧状态
	hPeers map[string]*hostPeer
	// 本会话已向某候选派发过几次 relay_setup（挑最闲的候选用）
	hRelayDispatched map[string]int
	// host 侧 TCP 打洞赢下的成对连接（远端 + 本地 MC），对应模组 trackFallback 那本账
	hTcpPairs []net.Conn
}

func newConnEngine(a *App) *connEngine {
	return &connEngine{app: a}
}

// bindSession 会话启动时绑定引擎（guest 自动开始 p2p）。
func (e *connEngine) bindSession(s *session, clientID string) {
	ctx, cancel := context.WithCancel(context.Background())
	room := s.app.snapshotRoom()

	e.mu.Lock()
	e.sess = s
	e.code = s.code
	e.token = s.token
	e.isHost = s.isHost
	e.clientID = clientID
	e.ctx = ctx
	e.cancel = cancel
	if room != nil {
		e.hostPort = roomInt(room, "hostPort")
		e.hostIp = roomStr(room, "hostIp")
	}
	if s.isHost {
		e.hPeers = map[string]*hostPeer{}
	} else {
		e.gMappedCh = make(chan *stunMappedAddr, 4)
	}
	isHost := s.isHost
	e.punchUiStartMs = 0 // 新会话起点清零（CM:14502：0 表示本会话尚未开始打洞，防旧值误判成已超时）
	e.autoRelayArmedRound = autoRelayInitialRound
	e.mu.Unlock()

	// 会话起点先做一次全量复位，再挂导演（模组 startDualP2P 就是这个顺序：stopAutoRelayAll → arm）。
	// 用 arm 而不是直接挂：join 往返 >1s 时此刻的会话/offer 还没齐，直接挂会在这一场整落空（F1）。
	e.stopAutoRelayAll()
	if !isHost && !roomIsHappyLink(room) {
		e.scheduleAutoRelayDirectorArm()
	}
	// 候选池：房主与房客都要登记，否则本端永远挑不到、别人也借不到我们中继。
	e.startRelayPool()
	// 桥层日志出口：不接就整层静默（换道/排干/种子重放这些关键留痕全在桥层）。
	setJoinerBridgeLogf(e.app.netLog)

	e.app.netLog("info", fmt.Sprintf("连接引擎就绪（role=%s）", roleStr(isHost)))
	if !isHost {
		// 互通房（source=happylink）不打洞、不走中继：由 connectFedIfHappyLink 播 fed/trying，
		// 这里再播 p2p/trying 会让前端先闪一个"正在打洞"的假阶段（ConnectionManager.connectFed 语义）。
		if !roomIsHappyLink(e.app.snapshotRoom()) {
			e.notifyState(phaseP2P, statusTrying, "", "")
		}
	}
}

func roleStr(isHost bool) string {
	if isHost {
		return "host"
	}
	return "guest"
}

func (e *connEngine) currentHostPort() int {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.hostPort
}

// unbindSession 会话结束（LeaveRoom / fatal / 应用退出）时清理。幂等，只对本会话生效。
func (e *connEngine) unbindSession(s *session) {
	e.mu.Lock()
	if e.sess == nil || e.sess != s {
		e.mu.Unlock()
		return
	}
	e.app.netLog("info", "连接引擎停止")
	e.mu.Unlock()
	e.teardown()
}

// teardown 全量清理：停打洞、关 socket、拆桥、尽力通知对端 disconnect。
func (e *connEngine) teardown() {
	e.stopAutoRelayAll() // 模组把 stopAutoRelayAll 放在 stopAllConnectionWork 的第一步（CM:1588-1590）
	// 候选池注销要趁 code/token 还在的时候做：清完之后就没法向服务端证明"这个 peer 是我的"了，
	// 只能等 TTL 120s 自然回收——那段时间里别人挑到一个已经不在的候选（0926 踩过）。
	e.stopRelayPool()
	// 会话是"主动结束"而不是"链路死了"：不声明会话终止的话，承载死亡关闭会按模组的
	// 换道窗口等满 15s 才回调拆桥（PB:1185/1269 是故意留给换道的）。
	stopJoinerSession()
	e.teardownTurn(false)
	e.mu.Lock()
	if e.cancel != nil {
		e.cancel()
	}
	// 快照并置空全部资源引用
	gPuncher, gSock, gRudp, gBridge := e.gPuncher, e.gPunchSock, e.gRudp, e.gBridge
	gTcpLn, gTcpConn := e.gTcpLn, e.gTcpConn
	rPuncher, rSock, rRudp, rBridge := e.rPuncher, e.rSock, e.rRudp, e.rBridge
	asNodeStop := e.rAsNodeStop
	peers := e.hPeers
	// peer 的 puncher/rudp 由 e.mu 保护写入，收尾也必须在锁内取值、锁外关闭
	var peerPunchers []*puncher
	var peerRudps []*rudpConn
	for _, p := range peers {
		if p.puncher != nil {
			peerPunchers = append(peerPunchers, p.puncher)
		}
		if p.rudp != nil {
			peerRudps = append(peerRudps, p.rudp)
		}
	}
	hTcpPairs := e.hTcpPairs
	tcpPunchers := e.tcpPunchers
	var extraPunchers []*guestExtraPuncher
	for _, ep := range e.gExtraPunchers {
		extraPunchers = append(extraPunchers, ep)
	}
	// 断开通知所需快照（复位前抓取；复位后 postSignal 读不到会话字段）
	sendBye := e.sess != nil && e.code != "" && e.token != ""
	byeCode, byeToken, byeBase := e.code, e.token, e.app.baseURL()
	byeTo := "host"
	byeIsHost := e.isHost
	if byeIsHost {
		byeTo = ""
	}
	// 引擎复位
	e.sess = nil
	e.code = ""
	e.token = ""
	e.clientID = ""
	e.isHost = false
	e.hostPort = 0
	e.hostIp = ""
	e.gOffer = nil
	e.gLastOffer = nil
	e.gMapped = nil
	e.gPunchSock = nil
	e.gPuncher = nil
	e.gRudp = nil
	e.gBridge = nil
	e.gTcpLn = nil
	e.gTcpConn = nil
	e.gP2PDone = false
	e.gActive = false
	e.gMappedCh = nil
	e.punchRecvEver = false
	e.predictionOffCount = 0
	e.retryRound = 0
	e.retryCancelled = false // CM:13921-13927 resetContinuousRetryState：下一间房从零开始
	e.tcpPunchInFlight = false
	e.gMyMappedIP = ""
	e.gMyMappedPort = 0
	e.gHostLocalIp = ""
	e.gSameCgnat = false
	e.gHostMappedPorts = nil
	e.gLastHostMappedPorts = nil
	e.gExtraPunchers = nil
	e.gPunchTemplate = nil
	e.hostNatClass = natUnknown
	e.turnInFlight = false
	e.turnSession = nil
	e.stdTurnSession = nil
	e.turnTransport = nil
	e.turnBridge = nil
	e.turnKeepaliveStop = nil
	e.turnReadyApplied = false
	e.turnPeerID = ""
	// 后台升直连/热备的字段复位（CM:12718-12721、13046）：漏复位会让下一会话的
	// 后台升级 CAS 恒失败，直连升级永久失效（模组注释点名为 P1-4）。
	bgStop := e.turnBgMonitorStop
	watchStop := e.turnSwitchWatchStop
	hbStop := e.hotstandbyKbStop
	hotstandby := e.turnHotstandby
	connTimer := e.connTimeoutTimer // CM:6804：会话结束撤掉总超时；锁内只摘表，取消动作放锁外
	e.connTimeoutTimer = nil
	e.turnBgMonitorStop = nil
	e.turnSwitchWatchStop = nil
	e.hotstandbyKbStop = nil
	e.turnHotstandby = nil
	e.turnBgDeadlineMs = 0
	e.turnBgTicks = 0
	e.turnSwitchStreak = 0
	e.turnBgPunchWon = false
	e.turnP2pGivenUp = false
	e.turnSwitchedToP2p = false
	e.iceRestartAttempts = 0 // CM:14181 resetIceRestartState（RoomManager.java:886 退房路径调用）
	e.lastIceRestartMs = 0   // CM:14183
	e.rInFlight = false
	e.rDone = false
	e.rAttempt++ // 作废上一轮中继的在途打洞/看门狗，防止退房后晚到的隧道写回新会话
	e.rResult = nil
	e.rSock = nil
	e.rPuncher = nil
	e.rRudp = nil
	e.rBridge = nil
	e.rConnectedHint = false
	e.rPunchLiveAttempt = 0
	e.rAsNodeStop = nil
	e.hPeers = nil
	e.hTcpPairs = nil
	e.tcpPunchers = nil
	e.hRelayDispatched = nil
	resetRelayCandidates(e) // 退房清账：批次计时器/current/90s 总闸一起归零（CM:13486 clearRelayTracking）
	e.mu.Unlock()

	// 定时器/热备在锁外收尾（goroutine 只靠这些闭包停，漏关就是常驻泄漏）
	if bgStop != nil {
		bgStop()
	}
	if watchStop != nil {
		watchStop()
	}
	if hbStop != nil {
		hbStop()
	}
	if hotstandby != nil {
		_ = hotstandby.Close()
	}
	if connTimer != nil {
		connTimer.cancel()
	}

	// 尽力通知对端（模组离开时也发 disconnect）
	if sendBye {
		_ = e.app.api.post(byeBase, "/signal/send", map[string]any{
			"code":   byeCode,
			"token":  byeToken,
			"isHost": byeIsHost,
			"type":   "disconnect",
			"data":   map[string]any{},
			"to":     byeTo,
		}, nil)
	}

	// 关闭资源（rudp Close 会连带停桥的接收侧；bridge.stop 关 TCP 侧）
	// 语音转发桥先收：模组的挂点在 killAllConnectionAttempts 里（CM:13803→15789-15805），
	// 本端的同位落点就是这个"所有连接工作停下"的收口。
	closeAllUdpForwardBridges()
	if gPuncher != nil {
		gPuncher.stop()
	}
	if gSock != nil {
		_ = gSock.Close()
	}
	if gBridge != nil {
		gBridge.stop()
	}
	if gRudp != nil {
		_ = gRudp.Close()
	}
	if gTcpLn != nil {
		_ = gTcpLn.Close()
	}
	if gTcpConn != nil {
		_ = gTcpConn.Close()
	}
	// host 侧 TCP 桥成对连接：不登记就没人关，退房后仍有一条通往本地 MC 口的活桥
	for _, c := range hTcpPairs {
		if c != nil {
			_ = c.Close()
		}
	}
	for _, tp := range tcpPunchers {
		tp.stop()
	}
	// 生日打洞的每一路都有自己的 socket，退房时必须一起收（模组的 activeHolePunchers
	// 在 stopAllConnectionWork 里全数 cancel+close）
	for _, ep := range extraPunchers {
		e.closeExtraPuncher(ep)
	}
	if rPuncher != nil {
		rPuncher.stop()
	}
	if rSock != nil {
		_ = rSock.Close()
	}
	if rBridge != nil {
		rBridge.stop()
	}
	if rRudp != nil {
		_ = rRudp.Close()
	}
	if asNodeStop != nil {
		asNodeStop()
	}
	for _, pn := range peerPunchers {
		pn.stop()
	}
	for _, rc := range peerRudps {
		_ = rc.Close()
	}
}

// ---- 信令发送 ----

// sendSignal 发送一条信令（POST /signal/send，读取当前会话快照后外发）。
func (e *connEngine) sendSignal(typ string, data map[string]any, to string) error {
	e.mu.Lock()
	code, token, isHost := e.code, e.token, e.isHost
	base := e.app.baseURL()
	e.mu.Unlock()
	if code == "" || token == "" {
		return fmt.Errorf("会话未绑定")
	}
	body := map[string]any{
		"code":   code,
		"token":  token,
		"isHost": isHost,
		"type":   typ,
		"data":   data,
		"to":     to,
	}
	return e.app.api.post(base, "/signal/send", body, nil)
}

// ---- 信令分发（session.handleSignals 调用）----

// engineSignals 引擎关心的信令类型。
var engineSignals = map[string]bool{
	"join_request":        true,
	"holepunch_offer":     true,
	"holepunch_mapped":    true,
	"punch_info":          true,
	"peer_port":           true,
	"disconnect":          true,
	"relay_request":       true,
	"relay_notify":        true,
	"relay_accept":        true,
	"relay_declined":      true,
	"relay_setup":         true,
	"relay_ready":         true,
	"turn_alloc":          true,
	"turn_ready":          true,
	"turn_nack":           true,
	"ice_restart":         true,
	"tcp_punch_info":      true,
	"tcp_punch_go":        true,
	"tcp_simopen_request": true, // Wave2 房客请房主也发起 SimOpen（CM:7142）
	"cancel_connection":   true, // RM:1956 → CM:14026 对端要求停止持续重试
	"turn_bg_punch":       true, // RM:1950 → CM:12371 后台升直连
	"turn_stby":           true, // RM:1953 → CM:12497 热备建立提示
}

// onSignal 把一条信令交给引擎（只处理引擎关心的类型）。
func (e *connEngine) onSignal(typ, from string, data map[string]any) {
	if !engineSignals[typ] {
		return
	}
	e.mu.Lock()
	sess := e.sess
	e.mu.Unlock()
	if sess == nil || sess.isDone() {
		return
	}
	// 信令来源门控（与 server-go/signal_auth.go 的服务端矩阵配套的第二道防线）。
	// 服务端把 from 绑成 token 推导出的真实身份：房主侧发出的任何信令 from 恒为 "host"，
	// 房客侧恒为其 clientId，因此本端可按角色判定归属。修复前任一房客发
	// host_closing / turn_alloc / relay_setup / peer_port / ice_restart，
	// 就能让本端拆线、把本地 MC 端口桥到它指定地址、或改写打洞目标。
	if !e.signalFromAllowed(typ, from) {
		e.app.netLog("warn", "忽略来源不符的信令: "+typ)
		return
	}

	switch typ {
	case "join_request":
		if e.roleIsHost() {
			e.hostOnJoinRequest(from, data)
		}
	case "holepunch_offer":
		if !e.roleIsHost() {
			e.guestOnOffer(from, data)
		}
	case "holepunch_mapped":
		if !e.roleIsHost() {
			e.guestOnMapped(data)
		}
	case "punch_info":
		if e.roleIsHost() {
			e.hostOnPunchInfo(from, data)
		}
	case "peer_port":
		e.onPeerPort(data)
	case "disconnect":
		e.onPeerDisconnect(from)
	case "relay_request":
		if e.roleIsHost() {
			// 派发内有 1 次 GET + 2 次 POST（各 10s），同步跑会把 WS 读循环占住，
			// 心跳只能等或超时降级；与 turn_alloc 路径同样离开分发线程。
			go e.hostOnRelayRequest(from, data)
		}
	case "relay_notify":
		if !e.roleIsHost() {
			e.guestOnRelayNotify(from, data)
		}
	case "relay_declined":
		if !e.roleIsHost() {
			e.guestRelayFail(e.relayAttemptCur(), "房主或中继者拒绝了中继请求")
		}
	case "relay_setup":
		if !e.roleIsHost() {
			e.guestOnRelaySetup(from, data)
		}
	case "relay_accept":
		if e.roleIsHost() {
			e.hostOnRelayAccept(from, data)
		} else {
			e.app.netLog("info", "中继节点已接受")
		}
	case "relay_ready":
		e.app.netLog("info", "房客中继就绪")
	case "turn_alloc":
		if e.roleIsHost() {
			e.hostOnTurnAlloc(from, data)
		}
	case "turn_ready":
		if !e.roleIsHost() {
			e.guestOnTurnReady(data)
		}
	case "turn_nack":
		if !e.roleIsHost() {
			e.guestOnTurnNack(data)
		}
	case "ice_restart":
		e.onRequestIceRestart(from)
	case "tcp_punch_info":
		if e.roleIsHost() {
			e.hostOnTcpPunchInfo(from, data)
		}
	case "tcp_punch_go":
		if !e.roleIsHost() {
			e.guestOnTcpPunchGo(data)
		}
	case "tcp_simopen_request":
		if e.roleIsHost() {
			e.hostOnTcpSimopenRequest(from, data)
		}
	case "cancel_connection":
		// CM:14026-14070：对端按了"取消"。本端只停持续重试并清这一条对端状态，
		// 绝不走终态关房（房主收到房客取消必须回等待态，见 2026-10-05 越权关房修复）。
		e.handleCancelConnection(from)
	case "turn_bg_punch":
		// CM:12371：房主侧纯信号驱动（from 不参与判定，打的就是信令里带来的地址）
		if e.roleIsHost() {
			e.handleTurnBgPunch(data)
		}
	case "turn_stby":
		// CM:12497：房主侧零状态变更，只提示
		if e.roleIsHost() {
			e.handleTurnStby()
		}
	}
}

// signalFromAllowed 判定一条信令的来源是否与本端角色相称。
//   - 房客：只接受房主/服务端权威信令（from=="host"）
//   - 房主：只接受自家房客；join_request 例外（服务端在新房客加入时代为注入，
//     此时该房客尚未登记进 hPeers）；"host" 来源覆盖服务端注入的 room_evicted 等
func (e *connEngine) signalFromAllowed(typ, from string) bool {
	if e.roleIsHost() {
		if typ == "join_request" || from == "host" {
			return true
		}
		e.mu.Lock()
		_, known := e.hPeers[from]
		e.mu.Unlock()
		return known
	}
	return from == "host"
}

func (e *connEngine) roleIsHost() bool {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.isHost
}

// ---- 通用工具 ----

func roomStr(m map[string]any, key string) string {
	if v, ok := m[key].(string); ok {
		return v
	}
	return ""
}

func roomInt(m map[string]any, key string) int {
	switch v := m[key].(type) {
	case float64:
		return int(v)
	case int:
		return v
	case int64:
		return int(v)
	}
	return 0
}

func sigStr(data map[string]any, key string) string {
	if v, ok := data[key].(string); ok {
		return v
	}
	return ""
}

func sigInt(data map[string]any, key string) int { return roomInt(data, key) }

func sigInt64(data map[string]any, key string) int64 {
	switch v := data[key].(type) {
	case float64:
		return int64(v)
	case int64:
		return v
	case int:
		return int64(v)
	}
	return 0
}

func sigInts(data map[string]any, key string) []int {
	arr, ok := data[key].([]any)
	if !ok {
		return nil
	}
	out := make([]int, 0, len(arr))
	for _, item := range arr {
		out = append(out, roomInt(map[string]any{"v": item}, "v"))
	}
	return out
}

// intSlicesEqual 逐位比较（模组用 List.equals 判"房主端口漂移是否同一串"，CM:3186）。
func intSlicesEqual(a, b []int) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

func sigBool(data map[string]any, key string) bool {
	if v, ok := data[key].(bool); ok {
		return v
	}
	return false
}

// udpAddrOf 由 STUN 映射地址构造 UDP 目标。
func udpAddrOf(m *stunMappedAddr) *net.UDPAddr {
	if m == nil {
		return nil
	}
	ip := net.ParseIP(m.IP)
	if ip == nil || m.Port <= 0 {
		return nil
	}
	return &net.UDPAddr{IP: ip, Port: m.Port}
}

// filterAddressFamilyCandidate 地址族过滤（逐句对齐 CM:15549-15585）：
// 对端通告的候选是本端够不着的地址族/环回地址时丢弃，返回 ""。
//
// 为什么必须有：模组的实测写在注释里——"本端无全局IPv6时丢弃host发布的IPv6候选，
// 避免朝不可达地址空转47轮(QWA6D3/S822QF)"。app 的打洞 socket 只绑 IPv4
// （punch.go:848），而对端可能是 28 模板里跑在双栈上的模组房，它会把 IPv6 映射地址通告过来；
// 没有这层过滤就是一边朝根本发不出去的目标刷轮次，还把中继兜底一路往后推。
func (e *connEngine) filterAddressFamilyCandidate(ip string, port int, tag string) string {
	if ip == "" {
		return ip
	}
	if strings.Contains(ip, ":") {
		if !fbVerifyIPv6Connectivity() {
			e.app.netLog("warn", fmt.Sprintf("[AddrFilter] 忽略不可达 IPv6 候选 %s:%d (%s)：本端无全局IPv6", ip, port, tag))
			return ""
		}
		return ip
	}
	if strings.HasPrefix(ip, "127.") || strings.HasPrefix(ip, "0.") || ip == "localhost" {
		e.app.netLog("warn", fmt.Sprintf("[AddrFilter] 忽略不可达候选 %s:%d (%s)：环回地址", ip, port, tag))
		return ""
	}
	return ip
}

// getLocalIP 取本机局域网 IP（UDP connect 探测默认路由出口，不实际发包）。
func getLocalIP() string {
	conn, err := net.Dial("udp", "8.8.8.8:80")
	if err != nil {
		return ""
	}
	defer conn.Close()
	if addr, ok := conn.LocalAddr().(*net.UDPAddr); ok {
		return addr.IP.String()
	}
	return ""
}

func localPort(uc *net.UDPConn) int {
	if a, ok := uc.LocalAddr().(*net.UDPAddr); ok {
		return a.Port
	}
	return 0
}

// beginGuestCycle cycle CAS：防重复 holepunch_offer / 迟到信号重入。
func (e *connEngine) beginGuestCycle() (int, bool) {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.gActive || e.gP2PDone {
		return e.gCycle, false
	}
	// CM:13986-13990 shouldContinuousRetry：对方已取消持久重试 → 不再开新一轮。
	// 首轮不受此门约束；中继两条道耗尽后的"复活打洞"会先清位再重驱（见 auto_relay F2）。
	if e.retryCancelled && e.retryRound > 0 {
		e.app.netLog("info", "voxlink.connection.persistent_retry_cancelled_skip_round")
		return e.gCycle, false
	}
	e.gActive = true
	e.gCycle++
	return e.gCycle, true
}

func (e *connEngine) endGuestCycle() {
	e.mu.Lock()
	e.gActive = false
	e.mu.Unlock()
}

func (e *connEngine) genValid(gen int) bool {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.sess != nil && gen == e.gCycle
}

// closeGuestPunchSock 关闭 p2p 打洞 socket，并在它正是登记中的那一个时同步清掉引用。
// 只关不清会让玩家中继路径（relay.go 的 reused 判据）把一个已关闭的 socket 当作
// 可复用句柄拿去打洞——而"可尝试中继"正是终局判定时提示给玩家的动作。
func (e *connEngine) closeGuestPunchSock(sock *net.UDPConn) {
	if sock == nil {
		return
	}
	_ = sock.Close()
	e.mu.Lock()
	if e.gPunchSock == sock {
		e.gPunchSock = nil
	}
	e.mu.Unlock()
}

func (e *connEngine) ctxDone() <-chan struct{} {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.ctx == nil {
		return nil
	}
	return e.ctx.Done()
}

func (e *connEngine) mappedChan() chan *stunMappedAddr {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.gMappedCh == nil {
		e.gMappedCh = make(chan *stunMappedAddr, 4)
	}
	return e.gMappedCh
}

// ---- guest：p2p 打洞流程 ----

// guestOnOffer 收到 host 的 holepunch_offer（模组 handleHolePunchOffer 对应）。
func (e *connEngine) guestOnOffer(from string, data map[string]any) {
	gen, ok := e.beginGuestCycle()
	if !ok {
		e.app.netLog("warn", "忽略重复/迟到的 holepunch_offer（周期进行中或已连通）")
		return
	}
	e.mu.Lock()
	e.gOffer = copyMap(data)
	e.gLastOffer = copyMap(data)
	if ip := sigStr(data, "hostIp"); ip != "" {
		e.hostIp = ip
	}
	if port := sigInt(data, "hostPort"); port > 0 {
		e.hostPort = port
	}
	// CM:2644-2652 offer 里的房主内网 IP 先存下来，Wave1/Wave2 的 CGNAT 分支全靠它。
	if lip := sigStr(data, "hostLocalIp"); lip != "" {
		e.gHostLocalIp = lip
	}
	if ip := sigStr(data, "hostMappedIp"); ip != "" && sigInt(data, "hostMappedPort") > 0 {
		// CM:2604 同款地址族过滤：本端没有全局 IPv6 时不采信对端通告的 IPv6 映射，
		// 否则就是朝发不出去的目标空转几十轮（模组工单 QWA6D3/S822QF 的成因）。
		if e.filterAddressFamilyCandidate(ip, sigInt(data, "hostMappedPort"), "holepunch_offer:hostMappedIp") != "" {
			e.gMapped = &stunMappedAddr{IP: ip, Port: sigInt(data, "hostMappedPort")}
		}
	}
	if d := sigInt(data, "hostMappedPortDelta"); d != 0 {
		e.gMappedDelta = d
	}
	if r := sigInt(data, "hostMappedPortRange"); r > 0 {
		e.gHostPortRange = r
	}
	if sigBool(data, "hostSymmetric") {
		e.gRemoteNat = natHardSym
		if sigBool(data, "hostEasySym") {
			e.gRemoteNat = natEasySym
		}
	}
	offer := copyMap(data)
	e.mu.Unlock()

	go e.guestPunchFlow(gen, offer, from)
}

// shouldPrefetchRelay CM:6391-6394：本端或对端对称、或两端映射到同一个公网 IP（同层 CGNAT）
// 才值得提前预热中继；锥对锥本来就该直连赢，不必多打一次拓扑。
// 门里的 relayPrefetched 对应模组 relayPrefetchFuture==null（CM:6576），一次进程只热一次。
func (e *connEngine) shouldPrefetchRelay(local, remote natClass) bool {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.relayPrefetched {
		return false
	}
	return local.symmetric() || remote.symmetric() || e.gSameCgnat
}

// guestPunchTemplate 当前打洞轮的模板快照（模组的 this.punchProfile() + activePunchParams
// + 认证密钥），CM:3422-3424 给 extra puncher 调 applyPunchTemplate 用的就是这一份。
type guestPunchTemplate struct {
	gen     int
	profile *punchProfile
	params  *punchParams
	authKey []byte
}

// guestExtraPuncher 一路生日端口 puncher（CM:3412-3432 的 joiner_extra_i），自带 socket。
type guestExtraPuncher struct {
	key    string
	p      *puncher
	sock   *net.UDPConn
	port   int
	closed atomic.Bool
}

// guestCloseExtraPunchers CM:3356-3400：房主端口漂移后，旧的 extra 全数撤掉，
// 由最新那串端口重建（留着它们只会继续朝已作废的端口喷洒）。
func (e *connEngine) guestCloseExtraPunchers(reason string) {
	e.mu.Lock()
	var stale []*guestExtraPuncher
	for k, ep := range e.gExtraPunchers {
		if strings.HasPrefix(k, "joiner_extra_") {
			stale = append(stale, ep)
			delete(e.gExtraPunchers, k)
		}
	}
	e.mu.Unlock()
	if len(stale) == 0 {
		return
	}
	e.app.netLog("info", fmt.Sprintf("端口漂移(%s)：撤掉 %d 路旧的生日 puncher，按最新端口重建", reason, len(stale)))
	for _, ep := range stale {
		e.closeExtraPuncher(ep)
	}
}

// closeExtraPuncher 幂等收尾：cancel 只关未赢 socket，本路 socket 由这里统一关。
func (e *connEngine) closeExtraPuncher(ep *guestExtraPuncher) {
	// ep.p == nil 是"已占位、puncher 还没建起来"的那一路，没什么可关
	if ep == nil || ep.p == nil || !ep.closed.CompareAndSwap(false, true) {
		return
	}
	ep.p.cancel()
	if ep.sock != nil {
		_ = ep.sock.Close()
	}
}

// guestSpawnExtraPunchers CM:3406-3425：同 CGNAT 回环命中率低，生日端口覆盖加密到 12 路；
// 其余场景 6 路。第 0 个端口由主 puncher 打，这里从第 1 个开始，同 key 已存在就不重开。
// socket 与 puncher 在锁内建好再登记（CM:3428-3432 的 createSocket+put 也是同步做的），
// 这样表里永远只有"真家伙"，端口漂移时关的就是这一批。
func (e *connEngine) guestSpawnExtraPunchers(ip string, ports []int) {
	e.mu.Lock()
	tpl := e.gPunchTemplate
	done := e.gP2PDone
	sess := e.sess
	sameCgnat := e.gSameCgnat
	gen := e.gCycle
	if e.gExtraPunchers == nil {
		e.gExtraPunchers = map[string]*guestExtraPuncher{}
	}
	e.mu.Unlock()
	if tpl == nil || sess == nil || done || len(ports) < 2 || ip == "" {
		return
	}
	maxExtraCap := 6
	if sameCgnat {
		maxExtraCap = 12
	}
	maxExtra := min(len(ports), maxExtraCap)
	for i := 1; i < maxExtra; i++ {
		key := fmt.Sprintf("joiner_extra_%d", i)
		target := &net.UDPAddr{IP: net.ParseIP(ip), Port: ports[i]}
		if target.IP == nil {
			continue
		}
		e.mu.Lock()
		if e.gP2PDone || e.sess == nil || gen != e.gCycle {
			e.mu.Unlock()
			return
		}
		if _, exists := e.gExtraPunchers[key]; exists {
			e.mu.Unlock()
			continue
		}
		sock, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4zero, Port: 0})
		if err != nil {
			e.mu.Unlock()
			continue // CM:3428-3431：建 socket 失败这一路直接跳过
		}
		p := newPuncher(sock, tpl.profile, tpl.params, tpl.authKey)
		p.setLogf(e.app.netLog)
		p.setTarget(target)
		ep := &guestExtraPuncher{key: key, p: p, sock: sock, port: ports[i]}
		e.gExtraPunchers[key] = ep
		e.mu.Unlock()
		e.app.netLog("info", fmt.Sprintf("Multi-port puncher %s: %s:%d", key, ip, ports[i]))
		go e.runGuestExtraPunch(gen, ep, ip, ports[i], tpl)
	}
}

// runGuestExtraPunch 一路生日 puncher 的一生：朝第 i 个端口喷洒 →
// 赢就走与主 puncher 完全同一段收尾（guestWinP2P），败就摘登记并记账（CM:3446-3541）。
func (e *connEngine) runGuestExtraPunch(gen int, ep *guestExtraPuncher, ip string, port int, tpl *guestPunchTemplate) {
	res := ep.p.punchPrediction(port, tpl.profile.joinerMultiPortRange, false)
	if res != nil && (res.recvPunch > 0 || res.recvAck > 0) {
		// CM:3452-3464：失败样本回灌给终局判定（整会话"收到过对端包"这一条门就是它撑的）
		e.mu.Lock()
		e.punchRecvEver = true
		e.mu.Unlock()
	}
	if res != nil && res.success {
		e.guestWinP2P(gen, ep.p, ep.sock, tpl.authKey) // 内部负责 cancel 与 socket 归属
		e.dropExtraPuncherByKey(ep.key, ep)
		return
	}
	e.closeExtraPuncher(ep)
	e.dropExtraPuncherByKey(ep.key, ep)
}

// dropExtraPuncherByKey 仅当表里还是这一路时才摘（漂移重建后不能误删新那一路）。
func (e *connEngine) dropExtraPuncherByKey(key string, ep *guestExtraPuncher) {
	e.mu.Lock()
	if cur, ok := e.gExtraPunchers[key]; ok && (ep == nil || cur == ep) {
		delete(e.gExtraPunchers, key)
	}
	e.mu.Unlock()
}

// armConnectionTimeout 对位 CM:6590-6627 + CM:6701-6727。
// 到点只报"全局超时"这一件事实（模组的 showConnectFailed(global_timeout)）：
// 不掐打洞循环，connectionCycleActive / 持续重试 / 自动中继导演照旧跑——
// 这与用户裁决"任何 NAT 都不许提前结束，只有必定失败才提前结束"一致。
func (e *connEngine) armConnectionTimeout(profile *punchProfile, joinerSym, hostSym bool) {
	e.mu.Lock()
	e.connStartMs = time.Now().UnixMilli() // CM:6590
	timeoutSec := profile.connectionTimeoutSec
	if joinerSym || hostSym { // CM:6605-6607
		timeoutSec = profile.symmetricConnectionTimeoutSec
	}
	e.connTimeoutSec = timeoutSec // CM:6625
	round := e.retryRound         // CM:6626 continuousRetryRound
	sess := e.sess
	e.mu.Unlock()

	if joinerSym || hostSym {
		e.app.netLog("info", "global_timeout_extended") // CM:6609-6615（值走日志字段，不本地化）
	}
	if round != 0 {
		return
	}
	t := newSessionTimer(
		func() bool { e.mu.Lock(); defer e.mu.Unlock(); return e.gP2PDone },                    // CM:6715 connectionWon
		func() bool { e.mu.Lock(); defer e.mu.Unlock(); return e.gActive },                     // CM:6717 connectionCycleActive
		func() bool { e.mu.Lock(); defer e.mu.Unlock(); return sess != nil && e.sess == sess }, // CM:6717 currentRoom == state
	)
	e.mu.Lock()
	e.connTimeoutTimer = t
	e.mu.Unlock()
	t.arm(timeoutSec, func(final int) {
		e.app.netLog("warn", "voxlink.connection.global_timeout")
		e.notifyState(phaseP2P, statusFailed, "", "voxlink.connection.global_timeout") // CM:6719-6721
	})
}

// cancelConnectionTimeout 对位 CM:5163（胜利）与 CM:6804（会话清理）的 cancel。
func (e *connEngine) cancelConnectionTimeout() {
	e.mu.Lock()
	t := e.connTimeoutTimer
	e.connTimeoutTimer = nil
	e.mu.Unlock()
	if t != nil {
		t.cancel()
	}
}

// handleCancelConnection 对位 CM:14026-14070：置"取消持久重试"位 → 留痕 →
// 取消方正是 TURN 对端时静默拆 TURN → 按对端断开那套清场（removePeer + abortPunch + 关传输）。
func (e *connEngine) handleCancelConnection(from string) {
	e.mu.Lock()
	e.retryCancelled = true // CM:14028
	isTurnPeer := e.turnPeerID != "" && e.turnPeerID == from
	e.mu.Unlock()
	e.app.netLog("info", "voxlink.connection.persistent_retry_cancelled")
	if isTurnPeer {
		e.teardownTurnQuietly() // CM:14050-14053
	}
	e.onPeerDisconnect(from) // CM:14054-14068
}

// guestWinP2P 打洞成功后的收尾：CAS 占位 → rudp 承载 → 本地桥 → 成功事件。
// 主 puncher 与 CM:3406-3425 的 joiner_extra_* 生日 puncher 共用这一段（模组两边都调
// establishUdpTransport），所以必须是同一个函数体而不是两份复制。
// 语句与原内联块逐条相同（重构为函数，非改写）。
func (e *connEngine) guestWinP2P(gen int, p *puncher, sock *net.UDPConn, authKey []byte) {
	winConn, actual := p.win()
	if winConn == nil {
		winConn = sock
	}
	if !e.genValid(gen) {
		p.cancel()
		_ = winConn.Close()
		e.closeGuestPunchSock(sock)
		return
	}
	e.mu.Lock()
	alreadyDone := e.gP2PDone
	if !alreadyDone {
		e.gPuncher = nil
		e.gPunchSock = nil
	}
	e.mu.Unlock()
	if alreadyDone {
		p.cancel()
		_ = winConn.Close()
		e.closeGuestPunchSock(sock)
		return
	}

	rc := newRudp(winConn, actual)
	if authKey != nil {
		// 直连路径绝不开认证降级（ReliableUdpTransport.java:366-369 的注释就是规则本身：
		// "仅 TURN 路径开启…直连 P2P 路径保持严格认证不受影响"）。
		// 降级后源地址重绑仍生效，等于让"能往这个已打洞 UDP 口塞 3 个伪造包的人"
		// 永久解除本端认证。
		rc.setAuthKey(authKey, false)
	}
	rc.setOnRestart(func() { e.guestIceRestart() })
	// 玩家中继 v2：双方都声明 relayMuxV1 才 mux 化，且必须在 Start 之前（模组 RUDP:160）。
	setupRelayMux(rc, hostSupportsCap(e.app.snapshotRoom(), relayMuxCapability), e.app.netLog, "guest", "joiner")
	// 载具热切优先（模组 startUdpPunchBridge 的第一个分支，CM:14949-14957）：
	// 旧道被判"建立但死"、新道到了 → 不新建本地桥，只换承载并重放上行日志，
	// MC 那条 TCP 全程不动。swapJoinerCarrier 内部会 start() 新承载，所以这里必须排在 rc.Start() 之前。
	if e.tryCarrierSwap(rc) {
		p.cancel()
		if winConn != sock {
			_ = sock.Close()
		}
		e.app.netLog("info", "P2P 新承载已热切，本地桥与 MC 连接不动")
		return
	}
	rc.Start()
	bridgeAddr, bridge, berr := startGuestBridge(rc, func() { e.guestBridgeDown() })
	if berr != nil {
		p.cancel()
		_ = rc.Close()
		e.closeGuestPunchSock(sock)
		e.notifyState(phaseP2P, statusFailed, "", "本地桥建立失败："+berr.Error())
		return
	}
	e.mu.Lock()
	// 写回屏障与写回同锁：建桥期间可能已退房（sess=nil）、已改道（gP2PDone）
	// 或已重开一轮（gCycle 变了）。旧写法只判 gP2PDone，而复位会把它清回 false，
	// 于是往已拆掉的引擎上挂 rudp/bridge → 三个 goroutine + 一个 TCP 监听口留到进程退出。
	if e.gP2PDone || e.sess == nil || gen != e.gCycle {
		e.mu.Unlock()
		p.cancel()
		_ = rc.Close()
		if bridge != nil {
			bridge.stop()
		}
		e.closeGuestPunchSock(sock)
		e.app.netLog("warn", "P2P 隧道建立过晚（本端已改道/已退房/已重开一轮），丢弃")
		return
	}
	e.gRudp = rc
	e.gBridge = bridge
	e.gP2PDone = true
	wonTimer := e.connTimeoutTimer // CM:5163：本端已胜出，撤总超时；锁内只摘表
	e.connTimeoutTimer = nil
	e.mu.Unlock()
	if wonTimer != nil {
		wonTimer.cancel()
	}
	p.cancel() // 关掉组内未赢 socket（winConn 除外）
	if winConn != sock {
		_ = sock.Close()
	}
	e.app.netLog("info", "P2P 打洞成功，rudp 隧道已建立")
	// 语音转发桥：模组的挂点在 establishUdpTransport 末尾（CM:15755→15761-15785），
	// 只有打洞出来的直连传输挂，TURN 与玩家中继的传输不挂。
	startVoiceForwardBridge(rc, false, e.app.netLog) // CM:15767 joiner 侧：绑 loopback:24454/25565
	// 成功卡上的地址必须是**本机桥接口**，不能是房主的 hostIp:hostPort：
	// 模组里 MC 在进程内，它的连接会被模组钩住再转隧道，所以能给房主地址；
	// 应用端 MC 是独立进程，它只会照着地址真连公网 —— 打不通，表现就是
	// "连接已打通"而 MC 报 Connection timed out: getsockopt。
	e.notifyState(phaseP2P, statusSuccess, bridgeAddr, "")
}

// guestPunchFlow guest 打洞主流程（对齐模组 ConnectionManager 打洞编排）：
// socket → STUN 分级 → punch_info → 等 mapped/齐射 → 档位推荐 + PunchTuner 自适应
// 无限轮次循环（仅"连续 20 轮零收包"或"PREDICTION_OFF 封顶 50 且零收包"允许终局）。
//
// 关键约束：
//   - 轮次无上限。PREDICTION_OFF_CAP=50 / ZERO_RECV_FINAL_ROUND_LIMIT=20 均只在
//     整会话从未收到对端任何 UDP 包时才判定终局（到达后仅停止直连打洞，
//     TURN/玩家中继按钮仍可用，是否使用由玩家决定）。
//   - 每轮用 PunchTuner.nextParams 调参；轮次耗尽升级档位（DEFAULT→AGGRESSIVE→HARDSYM）。
//   - 心跳闭环：链路死亡（rudp 判死/MC 断开）→ requestIceRestart + 重新打洞。
func (e *connEngine) guestPunchFlow(gen int, offer map[string]any, from string) {
	defer e.endGuestCycle()

	// 全程共用同一个打洞 socket：失败保留复用，跨周期不重绑。
	sock, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4zero, Port: 0})
	if err != nil {
		e.app.netLog("error", "创建打洞 socket 失败: "+err.Error())
		e.notifyState(phaseP2P, statusFailed, "", "创建打洞 socket 失败")
		return
	}

	// STUN 采样（本端 NAT 分级 + punch_info 映射地址）
	samples := stunSampleSeries(sock, stunServers, 3, 2, 1500*time.Millisecond)
	var mine *stunMappedAddr
	if len(samples) > 0 {
		mine = &samples[0]
	} else {
		e.app.netLog("warn", "STUN 探测全部失败，punch_info 不带映射地址")
	}
	localNat := natClassFromDelta(samples)
	e.mu.Lock()
	e.gLocalNat = localNat
	if mine != nil {
		// 顺手把候选池要的映射地址与 natType 记下来，省掉注册路径上再探一次 STUN
		//（同一 socket 的采样结果就是打洞用的那个映射）。
		m := *mine
		e.poolMapped = &m
		e.poolNatType = natTypeFromSamples(samples, "")
	}
	if e.punchUiStartMs == 0 {
		// 打洞计时基准：首周期只设一次，持续重试轮次与 ICE 重启都不重置（CM:6592-6596）——
		// 重置会让编排器的 15/30/45s 证据档永远数不满。
		e.punchUiStartMs = time.Now().UnixMilli()
	}
	e.mu.Unlock()
	if mine != nil {
		e.mu.Lock()
		e.gMyMappedIP = mine.IP
		e.gMyMappedPort = mine.Port
		e.mu.Unlock()
	}
	// 直连波次（CM:6819-6900 step0）：与 UDP 打洞并行提交，模组在同一个 step0 里先挂
	// 直连 future 再起 punch。这里必须在采样之后挂，否则本端映射地址未知，
	// sameCgnat 判据（CM:2872）算不出来，Wave1 的 CGNAT 分支就永远不进。
	e.refreshGuestSameCgnat()
	go e.guestWave1Direct(gen)

	// 对端 NAT 分级：offer 标志（host 自报）> 房间 natType
	remoteNat := natClassFromRoomType(roomStr(e.app.snapshotRoom(), "natType"))
	if sigBool(offer, "hostSymmetric") {
		remoteNat = natHardSym
		if sigBool(offer, "hostEasySym") {
			remoteNat = natEasySym
		}
	}

	// punch_info（带本端对称标志与 delta/range，供 host 反向打洞预测）
	punchData := map[string]any{}
	if mine != nil {
		punchData["joinerMappedIp"] = mine.IP
		punchData["joinerMappedPort"] = mine.Port
	}
	if lip := getLocalIP(); lip != "" {
		punchData["joinerLocalIp"] = lip
	}
	if localNat.symmetric() {
		punchData["joinerSymmetric"] = true
		if localNat == natEasySym {
			punchData["joinerEasySym"] = true
		}
		if d := stunDeltaFromSamples(samples); d != 0 {
			punchData["joinerMappedPortDelta"] = d
		}
	}
	if err := e.sendSignal("punch_info", punchData, "host"); err != nil {
		e.app.netLog("warn", "发送 punch_info 失败: "+err.Error())
	}

	// 齐射时刻：host 墙钟+3000（容忍时钟差，最多等 5s）
	startAt := time.Now()
	if syncMs := sigInt64(offer, "punchSyncTimeMs"); syncMs > 0 {
		st := time.UnixMilli(syncMs)
		if d := time.Until(st); d > 0 && d <= punchSyncMaxWait {
			startAt = st
		} else if d > punchSyncMaxWait {
			e.app.netLog("warn", fmt.Sprintf("punchSyncTimeMs 时钟偏差过大（%v），忽略齐射同步", d))
		}
	}

	// 等 holepunch_mapped（齐射时刻前；offer 已带 mapped 则到点即走）
	waitDur := time.Until(startAt)
	if waitDur < 0 {
		waitDur = 0
	}
	if waitDur > offerWaitMappedMax {
		waitDur = offerWaitMappedMax
	}
	var hostMapped *stunMappedAddr
	timer := time.NewTimer(waitDur)
	defer timer.Stop()
	select {
	case m := <-e.mappedChan():
		hostMapped = m
	case <-timer.C:
	case <-e.ctxDone():
		_ = sock.Close()
		return
	}
	if hostMapped == nil {
		e.mu.Lock()
		hostMapped = e.gMapped
		e.mu.Unlock()
	}

	// 目标选择：holepunch_mapped > offer.hostMapped > hostIp:hostPort
	var target *net.UDPAddr
	e.mu.Lock()
	hostIp, hostPort := e.hostIp, e.hostPort
	e.mu.Unlock()
	switch {
	case hostMapped != nil && udpAddrOf(hostMapped) != nil:
		target = udpAddrOf(hostMapped)
	case hostIp != "" && hostPort > 0:
		target = &net.UDPAddr{IP: net.ParseIP(hostIp), Port: hostPort}
	default:
		_ = sock.Close()
		e.app.netLog("error", "holepunch_offer 缺少可用目标地址")
		e.notifyState(phaseP2P, statusFailed, "", "缺少打洞目标地址")
		return
	}

	// 档位推荐 + punchAuthV1 密钥
	profile := recommendProfile(localNat, remoteNat)
	punchProfileSwitch(profile, "nat_matrix_"+localNat.String()+"x"+remoteNat.String())

	// CM:6445 runConnectionCycle 内 6590-6627：记会话起点，"任一侧对称"取 75~110s、
	// 否则取档位 45~65s，作为会话级总超时挂一次（只在第 0 轮挂；模组用的就是
	// 刚定下来的 punchProfile()，所以挂点必须排在档位选定之后）。
	e.armConnectionTimeout(profile, localNat.symmetric(), sigBool(offer, "hostSymmetric"))
	e.app.netLog("info", "NAT 分级 local="+localNat.String()+" remote="+remoteNat.String()+" → 档位 "+profile.name)

	// CM:6576-6586 Layer2：分完档就预热中继拓扑——对称 NAT 或两端同在一层 CGNAT 时，
	// 直连很可能是打不通的，等失败才开始找备胎就晚了。模组用 relayPrefetchFuture==null
	// 保证一次进程只热一次，本端同一口径（见 topologyPrefetchRelay）。
	if e.shouldPrefetchRelay(localNat, remoteNat) {
		e.mu.Lock()
		e.relayPrefetched = true
		e.mu.Unlock()
		go topologyPrefetchRelay()
	}

	roomCode := ""
	authKey := func() []byte {
		room := e.app.snapshotRoom()
		roomCode = roomStr(room, "code")
		if roomCode == "" {
			e.mu.Lock()
			roomCode = e.code
			e.mu.Unlock()
		}
		if hostSupportsCap(room, "punchAuthV1") {
			e.mu.Lock()
			cid := e.clientID
			e.mu.Unlock()
			if key := punchAuthDeriveDirectKey(roomCode, cid); key != nil {
				e.app.netLog("info", "打洞认证模式已启用（punchAuthV1）")
				return key
			}
		}
		return nil
	}()

	hostPortRange := sigInt(offer, "hostMappedPortRange")

	// 一次性 sendSignal hook：每周期切换目标/预测时复用。
	punchOnPeer := func(addr *net.UDPAddr) {
		_ = e.sendSignal("peer_port", map[string]any{"peer_ip": addr.IP.String(), "peer_port": addr.Port}, "host")
	}

	e.mu.Lock()
	e.gPunchSock = sock
	e.mu.Unlock()

	params := punchParamsFromProfile(profile)
	var lastFailure *punchFailureReason
	var lastResult *punchResult
	cycle := 0
	for {
		// 会话已结束 → 静默退出
		select {
		case <-e.ctxDone():
			e.closeGuestPunchSock(sock)
			return
		default:
		}
		if !e.genValid(gen) {
			e.closeGuestPunchSock(sock)
			return
		}
		// 已走 direct/prelay/TURN 通道成功 → p2p 不再尝试（先到者优先）
		e.mu.Lock()
		alreadyDone := e.gP2PDone
		e.mu.Unlock()
		if alreadyDone {
			e.closeGuestPunchSock(sock)
			return
		}

		// 终局判定（两个常量均要求整会话零收包）
		e.mu.Lock()
		recvEver := e.punchRecvEver
		offCount := e.predictionOffCount
		round := e.retryRound
		e.mu.Unlock()
		if offCount >= predictionOffCap && !recvEver {
			e.app.netLog("warn", "PREDICTION_OFF 封顶（50）且零收包，终止直连打洞")
			e.notifyState(phaseP2P, statusFailed, "", "端口预测已封顶，直连暂停（中继由自动编排接管）")
			e.closeGuestPunchSock(sock)
			return
		}
		if round >= zeroRecvFinalRoundLimit && !recvEver {
			e.app.netLog("warn", "持续重试 20 轮零收包，判定终局")
			e.notifyState(phaseP2P, statusFailed, "", "对端无响应，直连暂停（中继由自动编排器尝试）")
			e.closeGuestPunchSock(sock)
			return
		}

		// 当前最新目标（host 侧可能发来新的 mapped / peer_port）
		e.mu.Lock()
		curTarget := target
		curMapped := e.gMapped
		e.mu.Unlock()
		if curMapped != nil && udpAddrOf(curMapped) != nil {
			next := *udpAddrOf(curMapped) // 拷一份再改，别原地动到 e.gMapped 派生的地址对象
			curTarget = &next
			// 房主映射端口步长纠偏：模组 CM:3572-3578 在起连接周期时
			// cyclePort = hostMappedPort + hostMappedPortDelta（并钳到 1..65535）。
			// 但 CM:7751-7761 明确"房主是对称 NAT 时保持 mapped 端口、delta 跳过"，
			// 理由写在它的日志里：同 socket 双 STUN 的差值不是跨 socket 的步长，拿来预测反而打偏。
			// 两处合起来的口径就是：delta 只在非对称房主身上用。
			e.mu.Lock()
			delta, sym := e.gMappedDelta, e.gRemoteNat == natHardSym
			e.mu.Unlock()
			if delta != 0 && !sym {
				if predicted := next.Port + delta; predicted > 0 && predicted <= 65535 {
					next.Port = predicted
				}
			}
		}

		// 对端 NAT 分级/范围随信令纠偏（模组：新证据即重推荐档位）
		e.mu.Lock()
		peerNat := e.gRemoteNat
		peerRange := e.gHostPortRange
		e.mu.Unlock()
		if peerNat != natUnknown && peerNat != remoteNat {
			remoteNat = peerNat
			if next := recommendProfile(localNat, remoteNat); next != profile {
				profile = next
				punchProfileSwitch(profile, "peer_nat_update_"+remoteNat.String())
				e.app.netLog("info", "对端 NAT 更新 → 档位 "+profile.name)
			}
		}
		if peerRange > 0 {
			hostPortRange = peerRange
		}

		// PunchTuner 自适应调参
		params = tunerNextParams(profile, localNat, remoteNat, cycle, effectiveMaxCycles(profile, stunGetCachedResult()), lastFailure, lastResult)
		strategy := selectStrategy(localNat, remoteNat, cycle, false)

		p := newPuncher(sock, profile, params, authKey)
		p.setLogf(e.app.netLog)
		p.setTarget(curTarget)
		// 快照本轮模板：CM:3422-3424 的 extra puncher 走的是同一份档位/参数/密钥
		e.mu.Lock()
		e.gPunchTemplate = &guestPunchTemplate{gen: gen, profile: profile, params: params, authKey: authKey}
		e.mu.Unlock()
		p.setOnPeer(punchOnPeer)
		e.mu.Lock()
		e.gPuncher = p
		e.mu.Unlock()

		// 首轮等到齐射时刻再开打；后续轮直接发
		if cycle == 0 {
			if d := time.Until(startAt); d > 0 {
				select {
				case <-e.ctxDone():
					p.cancel()
					e.closeGuestPunchSock(sock)
					return
				case <-time.After(d):
				}
			}
		}
		select {
		case <-e.ctxDone():
			p.cancel()
			e.closeGuestPunchSock(sock)
			return
		default:
		}

		basePort := curTarget.Port
		predRange := hostPortRange
		if predRange <= 0 {
			predRange = params.portRange
		}

		var res *punchResult
		switch {
		case profile == punchEasySymDual:
			// 双易对称：多 socket 对轰（RECIPE bomb 窗口）
			res = p.punchEasySymDual(curTarget)
		case profile == punchHardSym:
			// 硬对称：socket 组全端口洗牌喷洒（PpsLimiter 3000）
			group := makePunchSocketGroup(sock, profile.hardSymSocketCount)
			res = p.punchGroup(group, 0)
		case strategy == strategyReverseOnly && cycle == 0:
			// 锥×硬对称首轮：本端反向优先，只听不发（对端扫我）
			res = p.punchPrediction(basePort, 0, false)
		case predRange <= 0:
			res = p.punchPrediction(basePort, 0, false)
		default:
			res = p.punchPrediction(basePort, predRange, false)
		}

		if res != nil && res.success {
			e.mu.Lock()
			hostIp, hostPort = e.hostIp, e.hostPort
			e.mu.Unlock()
			e.guestWinP2P(gen, p, sock, authKey)
			return
		}

		// 本轮失败：记账 + 调参输入
		if res != nil {
			e.mu.Lock()
			if res.recvPunch > 0 || res.recvAck > 0 {
				e.punchRecvEver = true
			}
			e.mu.Unlock()
			reason := classifyPunchFailure(res)
			if reason == failurePredictionOff {
				e.mu.Lock()
				e.predictionOffCount++
				n := e.predictionOffCount
				e.mu.Unlock()
				if n == predictionOffCap {
					e.app.netLog("warn", "PREDICTION_OFF 累计达 50，本轮会话直连预测打洞将停用")
				}
			}
			lastFailure = &reason
			lastResult = res
		}
		p.cancel() // 关闭组模式附加 socket（单 socket 不受影响）

		// TCP SimOpen 邀约（本端锥形、cycle≥1）
		e.tryTcpHolePunch(cycle, localNat, e.guestMyMappedIP())

		// 轮次推进：cycle 耗尽 → 持续重试轮 +1 + 档位升级 + cycle 归零
		cycle++
		if cycle >= effectiveMaxCycles(profile, stunGetCachedResult()) { // CM:6214-6228 getEffectiveMaxCycles()
			e.mu.Lock()
			e.retryRound++
			rr := e.retryRound
			e.mu.Unlock()
			profile = escalateProfileForRound(profile, rr)
			punchProfileSwitch(profile, "continuous_retry_round")
			e.app.netLog("info", "打洞周期耗尽，进入持续重试第 "+strconv.Itoa(rr)+" 轮（档位 "+profile.name+"）")
			cycle = 0
		}

		select {
		case <-e.ctxDone():
			e.closeGuestPunchSock(sock)
			return
		case <-time.After(punchCycleInterval):
		}
	}
}

// makePunchSocketGroup 构造打洞 socket 组：首 socket 复用共享 socket，其余新建。
func makePunchSocketGroup(first *net.UDPConn, count int) []*net.UDPConn {
	if count < 1 {
		count = 1
	}
	group := make([]*net.UDPConn, 0, count)
	if first != nil {
		group = append(group, first)
	}
	for len(group) < count {
		uc, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4zero, Port: 0})
		if err != nil {
			break
		}
		group = append(group, uc)
	}
	return group
}

// escalateProfileForRound 持续重试轮档位升级（DEFAULT→AGGRESSIVE→HARDSYM）。
func escalateProfileForRound(current *punchProfile, round int) *punchProfile {
	if current == punchHardSym {
		return current
	}
	if current == punchAggressive {
		return punchHardSym
	}
	return punchAggressive
}

func (e *connEngine) guestMyMappedIP() string {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.gMyMappedIP
}

// guestOnMapped 收到 host 的 holepunch_mapped。
func (e *connEngine) guestOnMapped(data map[string]any) {
	ip := sigStr(data, "hostMappedIp")
	port := sigInt(data, "hostMappedPort")
	if ip == "" || port <= 0 {
		return
	}
	if e.filterAddressFamilyCandidate(ip, port, "holepunch_mapped:hostMappedIp") == "" {
		return
	}
	m := &stunMappedAddr{IP: ip, Port: port}
	e.mu.Lock()
	e.gMapped = m
	if d := sigInt(data, "hostMappedPortDelta"); d != 0 {
		e.gMappedDelta = d
	}
	if r := sigInt(data, "hostMappedPortRange"); r > 0 {
		e.gHostPortRange = r
	}
	if sigBool(data, "hostSymmetric") {
		e.gRemoteNat = natHardSym
		if sigBool(data, "hostEasySym") {
			e.gRemoteNat = natEasySym
		}
	}
	puncher := e.gPuncher
	// CM:3160-3208：房主可以公布一串映射端口（它多 socket STUN 阵列的结果），
	// 模组据此开 joiner_extra_* 生日打洞（同 CGNAT 上限 12、否则 6，CM:3406-3425）。
	// 以前应用端整段丢弃，连"对端给了几个端口"都看不见；这里先收下来并记一条。
	ports := sigInts(data, "hostMappedPorts")
	if len(ports) == 0 {
		ports = []int{port} // CM:3178-3180：没给列表就退回单端口
	}
	portsChanged := !intSlicesEqual(e.gLastHostMappedPorts, ports)
	e.gLastHostMappedPorts = append([]int(nil), ports...)
	e.gHostMappedPorts = ports
	e.mu.Unlock()
	if len(ports) > 1 && puncher != nil {
		// CM:3356-3360：端口串变了先撤旧的 extra，再按最新那串重建
		if portsChanged {
			e.guestCloseExtraPunchers("holepunch_mapped")
		}
		e.guestSpawnExtraPunchers(ip, ports)
	}
	// CM:3278-3330：mapped 里带内网 IP 时补存（已设过不覆盖），同 CGNAT 就在打洞进行中
	// 立刻再朝内网 IP 拨一次；新 mapped 也可能第一次让"两端同公网 IP"成立。
	e.storeGuestHostLocalIp(sigStr(data, "hostLocalIp"), true, "holepunch_mapped")
	e.refreshGuestSameCgnat()
	e.guestCgnatDirectOnMapped()

	select {
	case e.mappedChan() <- m:
	default:
	}
	if puncher != nil {
		// 打洞进行中收到：刷新目标（对端纠偏/漂移）；预测范围由 PunchTuner 周期参数接管
		puncher.setTarget(udpAddrOf(m))
	}
}

// onPeerPort 收到 peer_port：对端以其实际源地址纠偏我方打洞目标。
func (e *connEngine) onPeerPort(data map[string]any) {
	ip := sigStr(data, "peer_ip")
	port := sigInt(data, "peer_port")
	if ip == "" || port <= 0 {
		return
	}
	addr := &net.UDPAddr{IP: net.ParseIP(ip), Port: port}
	e.mu.Lock()
	puncher := e.gPuncher
	e.mu.Unlock()
	if puncher != nil {
		puncher.setTarget(addr)
	}
}

// guestBridgeDown MC 断开/桥拆除/链路判死时复位 p2p 状态并重新打洞（心跳闭环）。
func (e *connEngine) guestBridgeDown() {
	e.guestTeardownP2P()
	e.app.netLog("warn", "MC 隧道已断开，发起 ICE 重启重新打洞")
	// 必须给界面发一条：本地桥已经随链路拆掉，成功卡上那个地址此刻是死的。
	// 界面按 detail=link_lost 把卡片退回"连接中"；自动换道不走这里，仍保持成功卡。
	e.notifyState(phaseP2P, statusTrying, "", "link_lost")
	e.guestIceRestart()
}

// guestTeardownP2P 拆除 guest p2p 隧道与桥（不动 TURN/中继）。
// UDP rudp 桥与 TCP 打洞桥互斥（TCP 只在 p2p 未成时建立），因此这里一并拆是安全的。
func (e *connEngine) guestTeardownP2P() {
	e.mu.Lock()
	rc := e.gRudp
	bridge := e.gBridge
	tcpLn, tcpConn := e.gTcpLn, e.gTcpConn
	e.gRudp = nil
	e.gBridge = nil
	e.gTcpLn = nil
	e.gTcpConn = nil
	e.gP2PDone = false
	e.gActive = false
	e.mu.Unlock()
	if bridge != nil {
		bridge.stop()
	}
	if rc != nil {
		_ = rc.Close()
	}
	if tcpLn != nil {
		_ = tcpLn.Close()
	}
	if tcpConn != nil {
		_ = tcpConn.Close()
	}
}

// guestIceRestart 心跳闭环：链路死亡 → 通知对端 + 以最近一次 offer 重新驱动打洞。
// iceRestartCooldown / iceRestartMaxAttempts / iceRestartNowMs 对位 CM:295(5000ms)、
// CM:293(3 次)；抽成 var 是用例拨表用，值即模组常量。
var (
	iceRestartCooldown    = 5 * time.Second
	iceRestartMaxAttempts = 3
	iceRestartNowMs       = func() int64 { return time.Now().UnixMilli() }
)

// guestIceRestart 对位 CM:14254-14295 performIceRestart：
// 先静默拆 TURN，再判 5s 冷却，再判 3 次上限，三道守卫都在"发信令之前"——
// 被冷却/上限挡掉时一条 ice_restart 都不该发出去（模组那两条分支只记日志并 return）。
func (e *connEngine) guestIceRestart() {
	e.mu.Lock()
	turnLive := e.turnSession != nil || e.stdTurnSession != nil
	e.mu.Unlock()
	if turnLive {
		// CM:14256-14259：ICE 重启将停所有传输，TURN 会话先静默拆净，
		// 否则 stdTurnSession 残留会锁死自动中继导演（G2 恒判"中继在途"）。
		e.teardownTurnQuietly()
	}

	e.mu.Lock()
	now := iceRestartNowMs()
	if now-e.lastIceRestartMs < iceRestartCooldown.Milliseconds() { // CM:14263-14267
		e.mu.Unlock()
		e.app.netLog("info", "ice_restart_cooldown_ignored")
		return
	}
	e.iceRestartAttempts++ // CM:14271
	if e.iceRestartAttempts > iceRestartMaxAttempts {
		e.mu.Unlock()
		e.app.netLog("warn", "ice_restart_max_attempts_reached")
		return
	}
	e.lastIceRestartMs = now
	attempt := e.iceRestartAttempts
	e.punchRecvEver = false
	e.predictionOffCount = 0
	e.retryRound = 0
	offer := e.gLastOffer
	e.mu.Unlock()
	e.app.netLog("info", fmt.Sprintf("ice_restart_trigger %d/%d", attempt, iceRestartMaxAttempts)) // CM:14277-14279

	// 上线类型是 ice_restart（requestIceRestart 为模组方法名，发错会被服务端拒收）
	_ = e.sendSignal("ice_restart", map[string]any{}, "host")
	if offer == nil {
		return
	}
	if gen, ok := e.beginGuestCycle(); ok {
		go e.guestPunchFlow(gen, copyMap(offer), "host")
	}
	// F3：ICE 重挂导演。轮次与计时基准都不重置（punchUiStartMs 语义），但导演可能已在上一道
	// 中继里被 G2 挡住后自取消；重挂一次，新一轮打洞才有新的中继机会（CM:14381-14385 在方法末尾）。
	e.startAutoRelayDirector()
}

// onRequestIceRestart 收到对端 ice_restart：拆当前链路重新协商。
func (e *connEngine) onRequestIceRestart(from string) {
	e.app.netLog("warn", "收到对端 ICE 重启请求，拆除当前链路重新协商")
	if e.roleIsHost() {
		e.mu.Lock()
		p := e.hPeers[from]
		delete(e.hPeers, from)
		var pn *puncher
		var rc *rudpConn
		if p != nil {
			pn, rc = p.puncher, p.rudp
		}
		e.mu.Unlock()
		if pn != nil {
			pn.cancel()
		}
		if rc != nil {
			_ = rc.Close()
		}
		return
	}
	e.guestTeardownP2P()
	// 重新驱动（本端不再回发 ice_restart，避免回声风暴）
	e.mu.Lock()
	e.punchRecvEver = false
	e.predictionOffCount = 0
	e.retryRound = 0
	offer := e.gLastOffer
	e.mu.Unlock()
	if offer != nil {
		if gen, ok := e.beginGuestCycle(); ok {
			go e.guestPunchFlow(gen, copyMap(offer), "host")
		}
	}
}

// onPeerDisconnect 收到对端 disconnect。
func (e *connEngine) onPeerDisconnect(from string) {
	if e.roleIsHost() {
		e.mu.Lock()
		p := e.hPeers[from]
		delete(e.hPeers, from)
		var pn *puncher
		var rc *rudpConn
		if p != nil {
			pn, rc = p.puncher, p.rudp
		}
		e.mu.Unlock()
		if pn != nil {
			pn.stop()
		}
		if rc != nil {
			_ = rc.Close()
		}
		if p != nil {
			e.app.netLog("info", "对端已断开")
		}
		return
	}
	// guest：房主断开 → 清理连接状态（保留房间会话），由无限打洞周期循环重新尝试
	e.mu.Lock()
	rc := e.gRudp
	bridge := e.gBridge
	puncher := e.gPuncher
	e.gRudp = nil
	e.gBridge = nil
	e.gP2PDone = false
	e.gActive = false
	e.mu.Unlock()
	if puncher != nil {
		puncher.stop()
	}
	if bridge != nil {
		bridge.stop()
	}
	if rc != nil {
		_ = rc.Close()
	}
	e.app.netLog("warn", "房主已断开，将重新尝试打洞")
}

// ---- host：打洞应答与桥接 ----

// roomNatType = RoomInfo.getNatType()（本房间记录里那份 NAT 结论，建房时由 STUN 精探写回）。
func (e *connEngine) roomNatType() string {
	e.mu.Lock()
	sess := e.sess
	e.mu.Unlock()
	if sess == nil || sess.app == nil {
		return ""
	}
	sess.app.mu.Lock()
	defer sess.app.mu.Unlock()
	if sess.app.room == nil {
		return ""
	}
	if s, ok := sess.app.room["natType"].(string); ok {
		return s
	}
	return ""
}

// roomNatTypeSymmetric = ConnectionManager.java:3867 的
// StunDetector.isNatTypeSymmetric(state.roomInfo.getNatType())。
func (e *connEngine) roomNatTypeSymmetric() bool {
	return stunNatTypeFromKey(e.roomNatType()).symmetric()
}

// setRoomNatType 对位 CM:4332 的 roomInfo.setNatType("symmetric_easy_inc")：
// 只改当前会话那间房。
func (e *connEngine) setRoomNatType(natType string) {
	e.mu.Lock()
	sess := e.sess
	e.mu.Unlock()
	if sess == nil || sess.app == nil {
		return
	}
	sess.app.mu.Lock()
	if sess.app.room != nil {
		sess.app.room["natType"] = natType
	}
	sess.app.mu.Unlock()
}

// roomHostSymmetric / setRoomHostSymmetric = RoomInfo.isHostSymmetric()/setHostSymmetric(true)
// （CM:4288 的升级判据与 CM:4421/4599 的读取点）。房主侧自己给自己盖章。
func (e *connEngine) roomHostSymmetric() bool {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.roomHostSym
}

func (e *connEngine) setRoomHostSymmetric(v bool) {
	e.mu.Lock()
	e.roomHostSym = v
	e.mu.Unlock()
}

// connectionWonFor = CM:4268 的 connectionWon.get()：这个房客已经有链路了。
func (e *connEngine) connectionWonFor(peer *hostPeer) bool {
	e.mu.Lock()
	defer e.mu.Unlock()
	return peer != nil && peer.rudp != nil
}

// hostOnJoinRequest 收到（服务端代发的）join_request → 起 host 侧引擎。
func (e *connEngine) hostOnJoinRequest(from string, data map[string]any) {
	e.mu.Lock()
	if e.hPeers == nil {
		e.hPeers = map[string]*hostPeer{}
	}
	if old := e.hPeers[from]; old != nil && (old.rudp != nil || old.puncher != nil) {
		e.mu.Unlock()
		e.app.netLog("warn", "忽略重复 join_request")
		return
	}
	// 先同步登记占位（防并发 join_request 双开 socket），重活在 goroutine 里做
	var caps []string
	if arr, ok := data["clientCapabilities"].([]any); ok {
		for _, c := range arr {
			if s, ok := c.(string); ok {
				caps = append(caps, s)
			}
		}
	}
	proto := sigInt(data, "clientProtocolVersion")
	if peerDataIsLegacy(data) {
		// 老客户端（无 protocolVersion 或 caps 空）一律按 legacy 处理，不做任何 v2/stdTurn 强求
		e.app.netLog("info", "join_request 来自旧版客户端（legacy）")
	}
	peer := &hostPeer{id: from, caps: caps, protoVersion: proto}
	e.hPeers[from] = peer
	hostPort := e.hostPort
	e.mu.Unlock()
	if hostPort <= 0 {
		e.app.netLog("error", "收到 join_request 但缺少房间 hostPort")
		e.dropHostPeer(peer)
		return
	}
	go e.hostServeJoin(peer, hostPort)
}

// dropHostPeer 移除尚未建链的 peer（仍是指向同一实例时）。
func (e *connEngine) dropHostPeer(peer *hostPeer) {
	e.mu.Lock()
	cur := e.hPeers[peer.id]
	if cur == peer && cur.rudp == nil {
		delete(e.hPeers, peer.id)
	}
	e.mu.Unlock()
}

// hostServeJoin host 侧连接主流程：绑 hostPort → STUN 分级+预测 → 签发 holepunch_offer →
// 等 punch_info → 轮次循环打洞（PunchTuner 自适应）→ rudp → lazy 桥。
func (e *connEngine) hostServeJoin(peer *hostPeer, hostPort int) {
	// 打洞 socket 优先绑 hostPort（绑定失败退随机；MC 走 TCP，UDP 端口不冲突）
	sock, err := punchListen(hostPort)
	if err != nil {
		e.app.netLog("error", "创建 host 打洞 socket 失败: "+err.Error())
		e.dropHostPeer(peer)
		return
	}

	// STUN 双采样（NAT 分级 + 映射地址）
	samples := stunSampleSeries(sock, stunServers, 2, 2, 1500*time.Millisecond)
	var hostMapped *stunMappedAddr
	if len(samples) > 0 {
		hostMapped = &samples[0]
	}
	hostNat := natClassFromDelta(samples)
	e.mu.Lock()
	e.hostNatClass = hostNat
	e.mu.Unlock()

	offer := map[string]any{"hostPort": hostPort}
	e.mu.Lock()
	if e.hostIp != "" {
		offer["hostIp"] = e.hostIp
	}
	e.mu.Unlock()
	if lip := getLocalIP(); lip != "" {
		offer["hostLocalIp"] = lip
	}
	hostDelta := 0
	hostRange := 0
	if hostMapped != nil {
		offer["hostMappedIp"] = hostMapped.IP
		offer["hostMappedPort"] = hostMapped.Port
		hostDelta = stunDeltaFromSamples(samples)
		if hostNat.symmetric() {
			offer["hostSymmetric"] = true
			if hostNat == natEasySym {
				offer["hostEasySym"] = true
			}
			// 对称 NAT：顺序采样 10 次做端口预测，把 delta/range 告知 guest（模组 P-PRE 同款）
			if pd, pr := stunPredictForOffer(sock); pd != 0 || pr > 0 {
				hostDelta = pd
				hostRange = pr
			}
			if hostDelta != 0 {
				offer["hostMappedPortDelta"] = hostDelta
			}
			if hostRange > 0 {
				offer["hostMappedPortRange"] = hostRange
			}
		}
	}
	// peer 的这两个字段会被信令分发协程（hostOnPunchInfo）读，必须与它同一把锁。
	e.mu.Lock()
	peer.hostMapped = hostMapped
	peer.hostDelta = hostDelta
	e.mu.Unlock()

	// RTT 齐射同步：host 墙钟 + 3000（模组 RoomManager 同款）
	offer["punchSyncTimeMs"] = time.Now().Add(3 * time.Second).UnixMilli()

	if err := e.sendSignal("holepunch_offer", offer, peer.id); err != nil {
		e.app.netLog("warn", "发送 holepunch_offer 失败: "+err.Error())
		_ = sock.Close()
		e.dropHostPeer(peer)
		return
	}
	e.app.netLog("info", "已签发 holepunch_offer，等待房客穿透")

	// 等 punch_info（guest 映射地址 + 对称标志），窗口 punchInfoTimeout
	deadline := time.After(punchInfoTimeout)
	for {
		e.mu.Lock()
		cur := e.hPeers[peer.id]
		mapped := peer.mapped
		e.mu.Unlock()
		if mapped != nil {
			break
		}
		if cur != peer {
			_ = sock.Close()
			return
		}
		select {
		case <-e.ctxDone():
			_ = sock.Close()
			e.dropHostPeer(peer)
			return
		case <-deadline:
			e.app.netLog("warn", "等待 punch_info 超时，清理 host 打洞")
			_ = sock.Close()
			e.dropHostPeer(peer)
			return
		case <-time.After(200 * time.Millisecond):
		}
	}

	// punchAuthV1：joiner 声明能力 → 双方派生同一密钥
	var authKey []byte
	if len(peer.caps) > 0 {
		for _, c := range peer.caps {
			if c == "punchAuthV1" {
				e.mu.Lock()
				code := e.code
				e.mu.Unlock()
				authKey = punchAuthDeriveDirectKey(code, peer.id)
				break
			}
		}
	}
	if authKey != nil {
		e.app.netLog("info", "打洞认证模式已启用（punchAuthV1，host 侧）")
	}

	// 轮次循环打洞（对齐 guest 侧：PunchTuner 调参 + 档位升级，会话寿命内无上限）
	e.mu.Lock()
	joinerNat := peer.natClass
	joinerRange0 := peer.joinerPortRange
	e.mu.Unlock()
	if joinerNat == natUnknown {
		joinerNat = natCone
	}
	_ = joinerRange0
	profile := recommendProfile(hostNat, joinerNat)
	punchProfileSwitch(profile, "host_nat_matrix_"+hostNat.String()+"x"+joinerNat.String())
	e.app.netLog("info", "host NAT 分级 local="+hostNat.String()+" joiner="+joinerNat.String()+" → 档位 "+profile.name)

	// ---- 房主侧多 socket STUN 阵列（CM:3941-4457）----
	// 产出 hostMappedPorts 给房客的生日多端口扇出用；阵列自己检出对称时还会升级 NAT 判定和档位。
	group, arr, profile := e.hostMappedArray(profile, sock, peer, hostNat, joinerNat, stunDiscoverMappedAddressDual)
	e.mu.Lock()
	hostNat = e.hostNatClass // CM:4298 升级写的是 this.localNatClass，后续分支都读升级后的值
	e.mu.Unlock()
	// CM:4547-4549：只有 joiner 自报对称、或双方都对称时才给扫窗带宽，否则只打已知端口
	// （模组注释：对称 CGNAT 上扫射会炸出 socket数×端口数 个未知映射触发洪泛保护，双向全丢）。
	e.mu.Lock()
	joinerSymFlag := peer.joinerSym
	e.mu.Unlock()
	hostSweep := hostRoundPeerRange(profile, joinerSymFlag, hostNat.symmetric(), joinerNat.symmetric())
	e.mu.Lock()
	peer.punching = true // CM:4467 hostPunchContext.punching = true
	e.mu.Unlock()
	// 出口统一收口：阵列里的源端口整组处置，赢家用 keep（CM:4274-4284/4519-4531）。
	closeGroup := func(keep *net.UDPConn) {
		for _, c := range group {
			if c != nil && c != keep {
				_ = c.Close()
			}
		}
	}
	defer func() {
		e.mu.Lock()
		peer.punching = false // CM:4511 timeoutCtx.punching = false
		e.mu.Unlock()
	}()
	driftStop := make(chan struct{})
	defer close(driftStop)
	if hostDriftSyncNeeded(arr, e.roomHostSymmetric()) { // CM:4599
		go e.hostDriftResync(peer.id, arr, func() bool {
			e.mu.Lock()
			defer e.mu.Unlock()
			return e.hPeers[peer.id] == peer && peer.punching && peer.rudp == nil
		}, driftStop)
	}

	params := punchParamsFromProfile(profile)
	var lastFailure *punchFailureReason
	var lastResult *punchResult
	cycle := 0
	deadlineMs := time.Now().Add(hostPunchLifetime)
	for time.Now().Before(deadlineMs) {
		select {
		case <-e.ctxDone():
			closeGroup(nil)
			return
		default:
		}
		e.mu.Lock()
		cur := e.hPeers[peer.id]
		won := peer.rudp != nil
		mapped := peer.mapped
		e.mu.Unlock()
		if cur != peer || won {
			closeGroup(nil)
			return
		}
		if mapped == nil {
			closeGroup(nil)
			return
		}

		params = tunerNextParams(profile, hostNat, joinerNat, cycle, effectiveMaxCycles(profile, stunGetCachedResult()), lastFailure, lastResult)
		strategy := selectStrategy(hostNat, joinerNat, cycle, false)
		sweep := hostSweep
		if strategy == strategyReverseOnly && cycle == 0 {
			sweep = 0 // 反向优先那一轮只打已知端口（本端既有口径）
		}
		// CM:4701-4849：组里每个 socket 各打一枪，赢的那个留下、其余收流
		p, res := e.hostPunchRound(group, profile, params, authKey, mapped, sweep, peer.id)
		if p == nil {
			closeGroup(nil)
			return
		}
		e.mu.Lock()
		peer.puncher = p
		e.mu.Unlock()

		if res != nil && res.success {
			winConn, actual := p.win()
			if winConn == nil {
				winConn = sock
			}
			rc := newRudp(winConn, actual)
			if authKey != nil {
				rc.setAuthKey(authKey, false) // 同 guest 侧：降级只属于 TURN 路径
			}
			rc.setOnRestart(func() {
				e.app.netLog("warn", "host 侧收到 ICE 重启请求，重新协商该房客")
				e.hostRestartPeer(peer.id)
			})
			// 玩家中继 v2：房主侧也要 mux 化，否则这个房客无法把别人中继出去（caps 在建 peer 时定，不再变）。
			setupRelayMux(rc, peerCapsListContains(peer.caps, relayMuxCapability), e.app.netLog, peer.id, "host")
			rc.Start()
			e.mu.Lock()
			cur := e.hPeers[peer.id]
			if cur != peer || peer.rudp != nil {
				e.mu.Unlock()
				_ = rc.Close()
				p.cancel()
				closeGroup(nil)
				return
			}
			peer.rudp = rc
			peer.puncher = nil
			mcPort := e.hostPort
			e.mu.Unlock()
			p.cancel()          // 收掉本 puncher 的接收协程（组内 socket 由下一行统一处置）
			closeGroup(winConn) // CM:4274-4284：赢家留着，其余源端口全部关掉
			e.app.netLog("info", "host 端 rudp 隧道已建立，等待 MC 握手")
			// 语音转发桥（CM:15755→15761-15785）：房主侧不绑端口，走出站中继（CM:15765 注释）
			startVoiceForwardBridge(rc, true, e.app.netLog)
			startHostLazyBridge(rc, mcPort, e.app.netLog)
			return
		}

		if res != nil {
			reason := classifyPunchFailure(res)
			lastFailure = &reason
			lastResult = res
		}
		p.cancel()

		cycle++
		if cycle >= effectiveMaxCycles(profile, stunGetCachedResult()) { // CM:6214-6228 getEffectiveMaxCycles()
			profile = escalateProfileForRound(profile, 0)
			punchProfileSwitch(profile, "host_continuous_retry")
			cycle = 0
		}

		select {
		case <-e.ctxDone():
			closeGroup(nil)
			return
		case <-time.After(punchCycleInterval):
		}
	}
	closeGroup(nil)
	e.mu.Lock()
	cur := e.hPeers[peer.id]
	if cur == peer && cur.rudp == nil {
		delete(e.hPeers, peer.id)
	}
	e.mu.Unlock()
	e.app.netLog("warn", "host 打洞等待窗口耗尽")
}

// hostRestartPeer 拆除某房客链路并重新走 join 协商（ICE 重启 host 侧）。
func (e *connEngine) hostRestartPeer(peerID string) {
	e.mu.Lock()
	p := e.hPeers[peerID]
	delete(e.hPeers, peerID)
	var caps []string
	proto := 0
	var pn *puncher
	var rc *rudpConn
	if p != nil {
		caps = p.caps
		proto = p.protoVersion
		pn, rc = p.puncher, p.rudp
	}
	e.mu.Unlock()
	if pn != nil {
		pn.cancel()
	}
	if rc != nil {
		_ = rc.Close()
	}
	if caps != nil || p != nil {
		// 重放必须带上 clientProtocolVersion，否则一次 ICE 重启就把新版房客降级成 legacy，
		// 后续 mux/stdTurn 判定全按老客户端走（RoomInfo.java:243-245）。
		e.hostOnJoinRequest(peerID, map[string]any{
			"clientId":              peerID,
			"clientCapabilities":    toAnySlice(caps),
			"clientProtocolVersion": proto,
		})
	}
}

func toAnySlice(ss []string) []any {
	out := make([]any, len(ss))
	for i, s := range ss {
		out[i] = s
	}
	return out
}

// hostOnPunchInfo 收到 guest punch_info → 回 holepunch_mapped 并开始向 guest 打洞。
func (e *connEngine) hostOnPunchInfo(from string, data map[string]any) {
	ip := sigStr(data, "joinerMappedIp")
	port := sigInt(data, "joinerMappedPort")
	// CM:3635：候选地址族本端够不着就整条 punch_info 丢掉，不进入打洞。
	if ip != "" && e.filterAddressFamilyCandidate(ip, port, "punch_info:joinerMapped") == "" {
		e.app.netLog("warn", "[HostPunchInfo] Drop punch_info from "+from+": candidate address family unreachable locally")
		return
	}
	requestHostLocalIp := sigBool(data, "requestHostLocalIp")
	var cgnatHit bool
	e.mu.Lock()
	peer := e.hPeers[from]
	if peer != nil {
		if ip != "" && port > 0 {
			peer.mapped = &stunMappedAddr{IP: ip, Port: port}
		}
		peer.requestHostLocalIp = requestHostLocalIp // CM:3993 fRequestHostLocalIp，阵列通告要不要带局域网地址
		// CM:3659-3665：两端映射到同一个公网 IP = 同一层 CGNAT 后面。
		// 朝这个公网映射打洞是白打（包出去就被自家 NAT 弹回来），模组在这里留 warn，
		// 并让后续"要不要发局域网地址/提前备中继"都按这个事实走。
		if ip != "" && peer.hostMapped != nil && peer.hostMapped.IP == ip {
			peer.sameCgnat = true
			cgnatHit = true
		}
		// joiner 对称标志 + delta/range（反向打洞预测用；easySym 为对端权威判定）
		if sigBool(data, "joinerSymmetric") {
			peer.joinerSym = true // CM:3871 原值，阵列规模与扫窗带宽按它走
			peer.natClass = natHardSym
			if sigBool(data, "joinerEasySym") || absInt(sigInt(data, "joinerMappedPortDelta")) < 100 {
				peer.natClass = natEasySym
			}
		} else {
			peer.natClass = natCone
		}
		if r := sigInt(data, "joinerMappedPortRange"); r > 0 {
			peer.joinerPortRange = r
		}
	}
	e.mu.Unlock()
	if peer == nil {
		return
	}
	if cgnatHit {
		e.app.netLog("warn", fmt.Sprintf("[HostPunchInfo] Same public IP (%s): both behind same CGNAT", ip))
	}

	// 模组不在这里回 holepunch_mapped：它是在这条信令里就地起多 socket 阵列，
	// 阵列做完双 STUN 才把 hostMappedPorts 一起发出去（CM:4389-4457）。
	// 应用端那段落在 hostServeJoin 的阵列步骤里，这里只做上面的入参登记。

	// 打洞进行中：刷新目标（对端纠偏/漂移）
	e.mu.Lock()
	pn := peer.puncher
	mp := peer.mapped
	e.mu.Unlock()
	if pn != nil && mp != nil {
		pn.setTarget(udpAddrOf(mp))
	}
}

func absInt(v int) int {
	if v < 0 {
		return -v
	}
	return v
}

// ---- TryDirect：直连探测 ----

// tryDirectContext 直连探测防并发。TryLock 只覆盖到起协程前，两次连点会出两套
// conn:state 交替；标志必须在探测本体结束时才清。
var directBusy atomic.Bool

// tryDirect TCP 直连探测 hostIp:hostPort，成功即回报（探测连接随即关闭）。
func (e *connEngine) tryDirect() error {
	e.mu.Lock()
	sess := e.sess
	hostIp, hostPort := e.hostIp, e.hostPort
	e.mu.Unlock()
	if sess == nil {
		return fmt.Errorf("当前没有房间会话")
	}
	if !directBusy.CompareAndSwap(false, true) {
		return fmt.Errorf("直连探测已在进行中")
	}

	go func() {
		defer directBusy.Store(false)
		e.notifyState(phaseDirect, statusTrying, "", "")
		if hostIp == "" || hostPort <= 0 {
			e.app.netLog("warn", "直连探测缺少目标")
			e.notifyState(phaseDirect, statusFailed, "", "缺少房主地址（尚未收到 holepunch_offer）")
			return
		}
		target := net.JoinHostPort(hostIp, strconv.Itoa(hostPort))
		for i := 0; i < directAttempts; i++ {
			conn, err := net.DialTimeout("tcp", target, directDialTimeout)
			if err == nil {
				_ = conn.Close() // 探测成功即关闭，玩家自己用 MC 连
				e.app.netLog("info", "直连探测成功")
				e.notifyState(phaseDirect, statusSuccess, target, "")
				return
			}
			e.app.netLog("warn", "直连探测未通")
			if i < directAttempts-1 {
				select {
				case <-e.ctxDone():
					return
				case <-time.After(directRetryInterval):
				}
			}
		}
		e.notifyState(phaseDirect, statusFailed, "", "直连探测未通")
	}()
	return nil
}

// ---- DetectMcPorts：netstat + tasklist ----

// DetectMcPorts 实现见 mc_ports.go（独立文件）。
