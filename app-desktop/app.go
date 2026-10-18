package main

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net/url"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

const (
	appVersion       = "1.0.0-beta"
	defaultServerURL = "https://link.voxdev.top"
	// clientProtocolVersion 是信令体里的客户端协议号，模组在 SignalingClient.java:199/284
	// 两处硬编码发 7（语义即 ProtocolNegotiator.PROTOCOL_VERSION_7），服务端用 ">0" 当
	// "新客户端"标记（roommanager.go:882）。
	// 别把它和 VoxLinkConstants.PROTOCOL_VERSION 混为一谈——那个是 Minecraft 协议号
	// （1.20.1→763），只用于 CM:1731 的版本观测日志，不在这条线上。
	clientProtocolVersion = 7
)

// ---- 协议能力表（与模组 ProtocolNegotiator.java:12-38 逐字节对齐） ----
//
// 能力名常量化，每项配一个"本端已实现"门控常量；声明口径沿用模组规则
// "只声明本端真正实现的项"（对齐 SignalingClient.java:202-211 的逐项过滤循环：
// 遍历能力、命中门控才加入上报数组）。新增实现落地 = 把对应门控翻成 true。
const (
	capNameRelay           = "relay"            // ProtocolNegotiator.java:12
	capNameIceRestart      = "ice_restart"      // ProtocolNegotiator.java:13
	capNameContinuousRetry = "continuous_retry" // ProtocolNegotiator.java:14
	capNamePunchAuthV1     = "punchAuthV1"      // ProtocolNegotiator.java:20
	capNameOverlayAuthV1   = "overlayAuthV1"    // ProtocolNegotiator.java:22
	capNameModSyncV1       = "modSyncV1"        // ProtocolNegotiator.java:24
	capNameStdTurnV1       = "stdTurnV1"        // ProtocolNegotiator.java:30
	capNameRelayMuxV1      = "relayMuxV1"       // ProtocolNegotiator.java:37
)

// 每项能力一个"已实现"门控。注释写明"什么东西让它为真"——该项对应的数据面/流程
// 代码在本端存在并接线后才可置 true；未接线必须保持 false（虚报能力会让对端
// 按新协议发帧，本端无法解析）。
const (
	// relay：relay.go/bridge.go 玩家中继转发数据面已实现。
	capImplementedRelay = true
	// ice_restart：链路死亡后重新协商已实现（conn_engine.go hostRestartPeer / ICE 重启流程）。
	capImplementedIceRestart = true
	// continuous_retry：打洞周期无上限持续重试已实现（conn_engine.go 会话寿命内轮次循环）。
	capImplementedContinuousRetry = true
	// punchAuthV1：punch_auth.go 打洞控制报文与数据面帧的截断 HMAC-SHA256 已实现。
	capImplementedPunchAuthV1 = true
	// overlayAuthV1：overlay 链路握手/中继报文的来源验签已实现并在收包路径生效
	// （overlay.go linkKeyFor→overlayVerifyAndStripPacket→authDropCount，对位
	// P2POverlayManager.java:350-395/:237-285；用例 overlay_auth_test.go）。
	// 模组把它无条件写进 CURRENT_CAPABILITIES（ProtocolNegotiator.java:45），本端同声明；
	// 密钥注入点 setLinkKeyForPeer 在模组 28 个模板里没有任何调用点，本端也保持不注入，
	// 于是两端线上行为一致：未注入密钥的来源按旧行为放行（:61-62）。
	capImplementedOverlayAuthV1 = true
	// modSyncV1：modsync_*.go（并行 agent）"必装 Mod 清单"发布/拉取——接入前不声明；
	// 对齐模组语义（SignalingClient.java:204-208）：未声明时房客按旧版房主秒判"无清单"直通。
	capImplementedModSyncV1 = false
	// stdTurnV1：std_turn.go RFC 5766 Allocate/ChannelBind/Refresh 已实现，
	// 任一端旧版自动回落自定义协议 v1（ProtocolNegotiator.java:26-29 语义）。
	capImplementedStdTurnV1 = true
	// relayMuxV1：mux 子通道数据面（候选者↔host 被中继会话分路承载，relay.go/conn_engine.go，
	// 并行 agent 本轮接线）——mux 合入即真；若 mux 未合入必须回置 false，
	// 否则旧版对端经本端做玩家中继会拿到 mux 帧格式（ProtocolNegotiator.java:31-36 语义）。
	capImplementedRelayMuxV1 = true
)

// currentCapabilities 按门控装配本端上报的能力表（只含已实现项）。
// 顺序无协议含义（服务端/模组均按集合处理），保持与模组声明集一致的可读顺序。
func currentCapabilities() []string {
	specs := []struct {
		name        string
		implemented bool
	}{
		{capNameRelay, capImplementedRelay},
		{capNameIceRestart, capImplementedIceRestart},
		{capNameContinuousRetry, capImplementedContinuousRetry},
		{capNamePunchAuthV1, capImplementedPunchAuthV1},
		{capNameOverlayAuthV1, capImplementedOverlayAuthV1},
		{capNameModSyncV1, capImplementedModSyncV1},
		{capNameStdTurnV1, capImplementedStdTurnV1},
		{capNameRelayMuxV1, capImplementedRelayMuxV1},
	}
	out := make([]string, 0, len(specs))
	for _, s := range specs {
		if s.implemented {
			out = append(out, s.name)
		}
	}
	return out
}

// appCapabilities 本端协议能力（与模组 ProtocolNegotiator.CURRENT_CAPABILITIES 对齐，
// 只声明本端真正实现的项，见上方逐项门控）。
var appCapabilities = currentCapabilities()

// App 是会话状态与流程层的持有者：UI 版把它 Bind 给前端，CLI 版直接调同一批方法。
// 界面相关的一切（事件推送、文件对话框）都走 sink / picker 两个接口注入，见 events.go。
type App struct {
	sink         eventSink
	picker       filePicker
	api          *apiClient
	settingsPath string
	settings     appSettings
	engine       *connEngine

	// logFiles 日志落盘出口（latest.log / debug.log），由入口层 openLogFiles 打开；
	// 未打开时为 nil，netLog 只走 stderr + UI 事件（测试进程即如此）。
	logFiles *logFiles

	// 房主名审核结果的播报去重（RoomManager.java:1144-1150 的 lastModerationStatus/lastModeratedName）
	lastModerationStatus string
	lastModeratedName    string

	mu      sync.Mutex
	state   string // "idle" | "hosting" | "in_room"
	code    string
	token   string
	isHost  bool
	room    map[string]any
	session *session

	// slotTaken 会话占位（对齐模组 currentRoom CAS null→PENDING，RoomManager.java:642-644）：
	// state 要等真正 startSession 才离开 idle，而加入重试链最长约 35s（3×10s HTTP + 退避），
	// 这期间玩家再点"建房"或双击"加入"会穿过互斥检查，两次 startSession 互相覆盖 a.session，
	// 前一个会话的心跳/WS/轮询 goroutine 就再没人 stop（leaveInternal 只 stop 当前那个）。
	// 刻意不往 state 里塞 "pending"：那是 UI 不认识的值，会污染 GetSessionState 的契约。
	slotTaken bool

	// joinAttemptGeneration 加入尝试代数（对齐模组 ConnectionManager.java:158 joinAttemptGeneration）：
	// JoinRoom 开新一轮 +1；任何 kill of connection attempts（leaveInternal）也 +1，
	// 旧重试链发现代数变了立即放弃（ConnectionManager.java:14911）。
	joinAttemptGeneration atomic.Int64

	// joinNonceMu 保护 joinNonce（对齐模组 SignalingClient.java:69,279-283：
	// 同一 SignalingClient 生命周期内复用同一幂等键，重试不换 key，服务端按 key 去重复用 clientId）。
	joinNonceMu sync.Mutex
	joinNonce   string
}

// NewApp 创建 App 实例（测试可覆盖 settingsPath）。
func NewApp() *App {
	a := &App{
		api:      newAPIClient(),
		sink:     nullSink{},
		state:    "idle",
		settings: defaultSettings(),
	}
	a.settingsPath = defaultSettingsPath()
	a.engine = newConnEngine(a)
	// 模组在 VoxLinkMod.java:141（SignalingClient 建好之后、RoomManager 之前）装拓扑客户端；
	// 本端此前只有测试在装，导致 getTopologyClient() 恒 nil —— 预热/轮询/overlay/心跳两字段
	// 全部走 nil-safe 早退，功能整块不生效。这里补上唯一的安装点。
	installTopologyClient(a)
	return a
}

// Init 加载持久化设置，由 UI 版 startup 或 CLI 版入口在拿到 sink 之后调用。
func (a *App) Init() {
	a.loadSettings()
}

// Shutdown 停掉当前会话并通知服务端。
//
// 必须走 leaveInternal，不能只 s.stop()：只停本地而不发 /room/leave，服务端要等心跳超时
// （room_ttl=300s）才回收房间——玩家关掉窗口（UI 的 OnShutdown）或启动器 Ctrl+C 掉 CLI
// 之后，那间房还能凭房号被加进来好几分钟，进去就是"连接失败"。
// leaveInternal 会 bump 加入代数、拆引擎、后台补发 /room/leave，并跑 sessionStopped
// （撤 UPnP 映射 + 日志上传 disarm）。
func (a *App) Shutdown() {
	a.leaveInternal()
	upnpStop() // UPnPManager.stop()：停续租调度器并清空登记表（模组在客户端卸载时同调用）
}

// ---- 事件辅助 ----

// SelectModsDir 给界面层的"浏览…"用：弹出系统目录选择框，返回 {path}。
// 取消、或当前构建没有对话框能力（CLI）时返回空串，前端据此保持原值不动。
func (a *App) SelectModsDir() string {
	a.mu.Lock()
	p := a.picker
	a.mu.Unlock()
	dp, ok := p.(dirPicker)
	if !ok || p == nil {
		return wrapOK(map[string]any{"path": ""})
	}
	dir, err := dp.PickDir("选择 mods 目录")
	if err != nil || strings.TrimSpace(dir) == "" {
		return wrapOK(map[string]any{"path": ""})
	}
	return wrapOK(map[string]any{"path": filepath.ToSlash(strings.TrimSpace(dir))})
}

// emit 把事件交给当前 sink（未注入时静默丢弃，便于测试与无头调用）。
func (a *App) emit(event string, data any) {
	a.mu.Lock()
	sink := a.sink
	a.mu.Unlock()
	if sink == nil {
		return
	}
	sink.Emit(event, data)
}

// AttachSink 由入口层注入事件出口（UI=wails 事件，CLI=NDJSON stdout）。
func (a *App) AttachSink(sink eventSink, picker filePicker) {
	a.mu.Lock()
	a.sink = sink
	a.picker = picker
	a.mu.Unlock()
}

// netLogSanitize 兜底消敏：去掉 IPv4、loopback 字面量、:port、IP:port 等模式。
// 本轮改造清单要求所有 emitNetLog 消息不得包含任何 IP/端口/地址，正则自检也在测试中。
var (
	netLogIPv4Re   = regexp.MustCompile(`\b(?:\d{1,3}\.){3}\d{1,3}\b`)
	netLogHostPort = regexp.MustCompile(`(?:\[?[0-9a-fA-F:]+\]?:\d{1,5})`)
	netLogAnyPort  = regexp.MustCompile(`:\d{2,5}\b`)
)

// sanitizeNetLogMessage 防御性兜底：去掉消息中可能的 IP/端口模式（出现即替换为 *）。
func sanitizeNetLogMessage(msg string) string {
	msg = netLogIPv4Re.ReplaceAllString(msg, "*")
	msg = netLogHostPort.ReplaceAllString(msg, "*:*")
	msg = netLogAnyPort.ReplaceAllString(msg, ":*")
	return msg
}

// netLog 记录网络层日志并推送 "net:log" 事件。
// 出口消敏：消息经 sanitizeNetLogMessage 处理，确保不再含 IP/端口/地址字面量。
// 三个出口与模组的 LOGGER 一一对应：stderr（控制台）、UI 事件（界面日志面板）、
// 落盘 latest.log/debug.log（模组的对应物是 log4j 的 AppenderRefs，见 logfile.go）。
func (a *App) netLog(level, msg string) {
	clean := sanitizeNetLogMessage(msg)
	log.Printf("[%s] %s", level, clean)
	a.mu.Lock()
	files := a.logFiles
	a.mu.Unlock()
	if files != nil {
		files.Write(level, clean) // 消敏后才落盘：上传/附件里的文本与界面看到的一致
	}
	a.emit("net:log", map[string]any{
		"level": level,
		"msg":   clean,
		"ts":    time.Now().UnixMilli(),
	})
}

// openLogFiles 打开日志落盘（latest.log / debug.log），只由两个入口调用：
// UI 版传 true（vanilla 语义：每次启动截断 latest.log），CLI 版传 false（一次性命令
// 追加而不截断，否则启动器调一次 status 就把 UI 那一场的上传样本抹了）。
// 刻意不在 NewApp/Init 里自动开：测试构造的 App 会指向真实的用户配置目录。
func (a *App) openLogFiles(truncateLatest bool) {
	a.mu.Lock()
	if a.logFiles == nil {
		a.logFiles = newLogFiles(filepath.Join(filepath.Dir(a.settingsPath), "logs"), truncateLatest)
	}
	a.mu.Unlock()
}

// ---- 内部状态辅助 ----

func (a *App) baseURL() string {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.settings.ServerURL == "" {
		return defaultServerURL
	}
	return a.settings.ServerURL
}

func (a *App) snapshotRoom() map[string]any {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.room == nil {
		return nil
	}
	return copyMap(a.room)
}

func copyMap(m map[string]any) map[string]any {
	out := make(map[string]any, len(m))
	for k, v := range m {
		out[k] = v
	}
	return out
}

// setState 更新会话状态字段。
func (a *App) setState(state, code, token string, isHost bool, room map[string]any, s *session) {
	a.mu.Lock()
	a.state = state
	a.code = code
	a.token = token
	a.isHost = isHost
	a.room = room
	a.session = s
	a.mu.Unlock()
}

// startSession 启动后台会话 goroutine 并广播状态。
// 房间元数据必须含 clientType / gameVersion / loader（缺则填空字符串）：
// - clientType：join 由 /room/join 返回、create 由本端写 "app"。
// - gameVersion/loader：同上，join 响应可能不带，前端会按 "未指定" 兜底显示。
func (a *App) startSession(code, token string, isHost bool, room map[string]any, clientID string) {
	state := "in_room"
	if isHost {
		state = "hosting"
	}
	if room == nil {
		room = map[string]any{}
	}
	if _, ok := room["clientType"]; !ok {
		room["clientType"] = ""
	}
	if _, ok := room["gameVersion"]; !ok {
		room["gameVersion"] = ""
	}
	if _, ok := room["loader"]; !ok {
		room["loader"] = ""
	}
	s := newSession(a, code, token, isHost)
	a.setState(state, code, token, isHost, room, s)
	a.engine.bindSession(s, clientID)
	// 模组在房间确立时把 (code, token, isHost, clientId) 交给 TopologyClient，并按 nodeID
	// 规则区分 host_/client_（topology.go:245-266 照抄该规则），同时起 overlay 与轮询。
	topologyOnRoomJoined(code, token, isHost, clientID, 0)
	a.emit("session:state", map[string]any{
		"state": state,
		"room":  copyMap(room),
	})
	go s.run()
}

// leaveInternal 停止当前会话并尽力通知服务器离开。幂等。
// 开头先 bump 加入代数（对齐模组 killAllConnectionAttempts：ConnectionManager.java:13687/13812
// 第一步就是 joinAttemptGeneration.incrementAndGet()），让在途的 join 重试链自行放弃。
func (a *App) leaveInternal() {
	a.joinAttemptGeneration.Add(1)

	a.mu.Lock()
	s := a.session
	code, token, isHost := a.code, a.token, a.isHost
	hostPort := 0
	if a.room != nil {
		hostPort = roomInt(a.room, "hostPort")
	}
	a.mu.Unlock()

	// 模组在房间离开回调里做同样的事（RoomManager.java:170-180 → topologyClient.onRoomLeft）：
	// 停 overlay、清 code/token/nodeID、把 generation 归零。放在会话判空之前，
	// 保证"没起过会话也调了一次离开"也把状态清干净（幂等）。
	topologyOnRoomLeft()

	if s != nil {
		a.engine.unbindSession(s)
		s.stop()
		select {
		case <-s.done:
		case <-time.After(3 * time.Second):
			a.netLog("warn", "等待会话循环退出超时，强制继续")
		}
		// 尽力通知服务器（失败忽略：房间会因心跳超时自动回收）。
		// 放到后台：这是个 10s 超时的同步 POST，压在"离开"按钮上会让玩家觉得界面死了，
		// 期间任何建房/加入还会被 SESSION_ACTIVE 拒掉。本地拆除动作必须留在前面做完，
		// 这条 POST 不影响本地状态，谁先谁后对服务端等价。
		go func() {
			_ = a.api.post(a.baseURL(), "/room/leave", map[string]any{
				"code":   code,
				"token":  token,
				"isHost": isHost,
			}, nil)
		}()
	}
	a.setState("idle", "", "", false, nil, nil)
	a.releaseSessionSlot()
	a.sessionStopped(isHost, hostPort) // RoomManager.java:556 + AttemptingJoinScreen.java:249
}

// clearSession 由会话 goroutine 在结束时调用，重置 App 状态（仅当仍是本会话）。
func (a *App) clearSession(s *session) {
	a.mu.Lock()
	had := false
	isHost := false
	hostPort := 0
	if a.session == s {
		isHost = a.isHost
		if a.room != nil {
			hostPort = roomInt(a.room, "hostPort")
		}
		a.session = nil
		a.state = "idle"
		a.code = ""
		a.token = ""
		a.isHost = false
		a.room = nil
		a.slotTaken = false
		had = true
		resetPortStatusOnLeave() // 端口可达性结论属于这一间房，下一间要重新探（模组随 roomInfo 销毁）
	}
	a.mu.Unlock()
	// 模组的拆除点是 stopScheduledTasks（RoomManager.java:1105）→ app 侧等价落点就是这里。
	// 必须放在锁外：stopFedTunnel / sessionStopped 都会经 netLog→emit 回来抢同一把 a.mu。
	if had {
		a.stopFedTunnel()
		// 本机 /info 服务随会话收尾（模组在游戏退出时停，PeerServer.java:109-121；
		// 本端进程模型是会话制，不收就会在 CLI 单次命令结束后留一个回环监听口）。
		stopPeerServer()
		// 房被回收/被踢/令牌失效这类"会话自己死了"的路径同样要收尾：
		// 模组在 handleRoomLost 里就明确 closePort(hostPort)（RoomManager.java:1289-1298），
		// 否则房主的 UPnP 映射会一直挂在路由器上、续租任务每 50 分钟续一次直到进程退出。
		a.sessionStopped(isHost, hostPort)
	}
}

// updateRoomInfo 心跳响应携带的房间信息回写（有变化才广播）。
func (a *App) updateRoomInfo(s *session, currentPlayers int, name string) {
	a.mu.Lock()
	if a.session != s {
		a.mu.Unlock()
		return
	}
	changed := false
	if currentPlayers > 0 && a.room != nil && a.room["currentPlayers"] != currentPlayers {
		a.room["currentPlayers"] = currentPlayers
		changed = true
	}
	if name != "" && a.room != nil && a.room["name"] != name {
		a.room["name"] = name
		changed = true
	}
	state := a.state
	var room map[string]any
	if a.room != nil {
		room = copyMap(a.room)
	}
	a.mu.Unlock()

	if changed {
		a.emit("session:state", map[string]any{"state": state, "room": room})
	}
}

// ---- JSON 结果包装 ----

func wrapOK(data any) string {
	b, err := json.Marshal(map[string]any{"ok": true, "data": data})
	if err != nil {
		return `{"ok":false,"error":"INTERNAL","message":"结果序列化失败"}`
	}
	return string(b)
}

func wrapErr(code, msg string) string {
	// code 与 error 同值：error 是老前端在读的字段，code 是给启动器/脚本匹配的机器码。
	b, err := json.Marshal(map[string]any{"ok": false, "error": code, "code": code, "message": msg})
	if err != nil {
		return `{"ok":false,"error":"INTERNAL","message":"错误序列化失败"}`
	}
	return string(b)
}

// wrapCallErr 把底层错误转为统一格式：服务器错误透传，其余归为 NETWORK。
func wrapCallErr(err error) string {
	if ae, ok := err.(*APIError); ok {
		return wrapErr(ae.Code, ae.Message)
	}
	return wrapErr("NETWORK", err.Error())
}

// parseParams 解析前端传来的 JSON 字符串参数。
func parseParams(jsonStr string, out any) error {
	if jsonStr == "" {
		return nil
	}
	if err := json.Unmarshal([]byte(jsonStr), out); err != nil {
		return fmt.Errorf("参数解析失败: %w", err)
	}
	return nil
}

// ==================== 绑定方法（前端契约，勿改签名） ====================

// GetSettings 返回 {serverUrl, theme, version, clientTag}。serverUrl 恒为默认（应用内不可改）。
// clientTag 为启动器/用户来源标记（上报服务端供后台展示），默认空 = 不上报。
func (a *App) GetSettings() string {
	a.mu.Lock()
	theme := a.settings.Theme
	clientTag := a.settings.ClientTag
	language := a.settings.Language
	a.mu.Unlock()
	if language == "" {
		language = "auto"
	}
	return wrapOK(map[string]any{
		"serverUrl":    defaultServerURL,
		"theme":        theme,
		"language":     language,
		"languages":    availableLangs(),
		"version":      appVersion,
		"clientTag":    clientTag,
		"relayEnabled": a.relayEnabled(),
		"autoUPnP":     a.autoUPnPEnabled(),
	})
}

// SaveSettings 保存 {theme?, clientTag?}（参数里的 serverUrl 一律忽略）并返回同 GetSettings。
// clientTag 用指针区分"未提供"与"显式清空"；入库前按服务端同款规则归一
// （小写+trim，不匹配 ^[a-z0-9_-]{1,32}$ 一律丢空，对齐 server-go/roommanager.go:410-413 的"丢弃不拒绝"）。
func (a *App) SaveSettings(settingsJSON string) string {
	var req struct {
		ServerURL    string  `json:"serverUrl"` // 忽略（收到也不应用）
		Theme        string  `json:"theme"`
		ClientTag    *string `json:"clientTag"`
		RelayEnabled *bool   `json:"relayEnabled"`
		AutoUPnP     *bool   `json:"autoUPnP"`
		Language     string  `json:"language"`
	}
	if err := parseParams(settingsJSON, &req); err != nil {
		return wrapErr("INVALID_PARAMS", err.Error())
	}

	// 先校验再改状态：半途报错不能留下"内存已改、磁盘没写"的半套设置
	var cleanTag *string
	if req.ClientTag != nil {
		clean, err := sanitizeClientTag(*req.ClientTag)
		if err != nil {
			return wrapErr("INVALID_PARAMS", err.Error()) // CLI 传超长标签要报错，不能静默丢掉
		}
		cleanTag = &clean
	}
	if req.Theme != "" && req.Theme != "light" && req.Theme != "dark" {
		// 与 clientTag/language 同一口径：开发者入口当场报错，不写进磁盘。
		// 界面只会发这两个值，落盘的野值会让主题解析走兜底分支。
		return wrapErr("INVALID_PARAMS", "theme 只接受 light|dark，收到 "+req.Theme)
	}

	a.mu.Lock()
	if req.Theme != "" {
		a.settings.Theme = req.Theme
	}
	if req.Language != "" {
		// 只认 "auto" 与 lang/ 里真有的代码：写错的语言会让界面整片空白，宁可忽略
		if req.Language == "auto" || langPackOf(req.Language) != nil {
			a.settings.Language = req.Language
		}
	}
	if cleanTag != nil {
		a.settings.ClientTag = *cleanTag
	}
	if req.RelayEnabled != nil {
		a.settings.RelayEnabled = req.RelayEnabled
	}
	if req.AutoUPnP != nil {
		a.settings.AutoUPnP = req.AutoUPnP
	}
	a.mu.Unlock()

	if err := a.saveSettings(); err != nil {
		return wrapErr("IO", err.Error())
	}
	return a.GetSettings()
}

// clientTagOf 读当前 clientTag（已在入口归一，可再归一次兜底磁盘手工编辑的值）。
func (a *App) clientTagOf() string {
	a.mu.Lock()
	tag := a.settings.ClientTag
	a.mu.Unlock()
	return normalizeClientTag(tag)
}

// GetCategories 返回分类 map（键值如 survival->生存）。
func (a *App) GetCategories() string {
	var data map[string]any
	if err := a.api.get(a.baseURL(), "/categories", nil, &data); err != nil {
		return wrapCallErr(err)
	}
	return wrapOK(data)
}

// ListRooms 参数 {page,size,category,loader,search}；search 服务端不支持时本地过滤。
func (a *App) ListRooms(queryJSON string) string {
	var req struct {
		Page     int    `json:"page"`
		Size     int    `json:"size"`
		Category string `json:"category"`
		Loader   string `json:"loader"`
		Search   string `json:"search"`
	}
	if err := parseParams(queryJSON, &req); err != nil {
		return wrapErr("INVALID_PARAMS", err.Error())
	}
	if req.Page <= 0 {
		req.Page = 1
	}
	if req.Size <= 0 {
		req.Size = 20
	}

	query := url.Values{
		"page":       {strconv.Itoa(req.Page)},
		"size":       {strconv.Itoa(req.Size)},
		"clientType": {"mod"},
	}
	if req.Category != "" {
		query.Set("category", req.Category)
	}
	if req.Loader != "" {
		query.Set("loader", req.Loader)
	}

	var data struct {
		Rooms []map[string]any `json:"rooms"`
		Total int              `json:"total"`
		Page  int              `json:"page"`
		Size  int              `json:"size"`
	}
	if err := a.api.get(a.baseURL(), "/room/list", query, &data); err != nil {
		return wrapCallErr(err)
	}

	if req.Search != "" {
		data.Rooms = filterRooms(data.Rooms, req.Search)
		data.Total = len(data.Rooms)
	}
	return wrapOK(map[string]any{
		"rooms": data.Rooms,
		"total": data.Total,
		"page":  data.Page,
		"size":  data.Size,
	})
}

// filterRooms 按房间名本地模糊过滤（不区分大小写）。
func filterRooms(rooms []map[string]any, search string) []map[string]any {
	needle := strings.ToLower(search)
	out := make([]map[string]any, 0, len(rooms))
	for _, r := range rooms {
		name, _ := r["name"].(string)
		if strings.Contains(strings.ToLower(name), needle) {
			out = append(out, r)
		}
	}
	return out
}

// RoomInfo 查询房间公开信息。
func (a *App) RoomInfo(code string) string {
	if code == "" {
		return wrapErr("INVALID_PARAMS", "房间码不能为空")
	}
	var data map[string]any
	if err := a.api.get(a.baseURL(), "/room/info", url.Values{"code": {code}}, &data); err != nil {
		return wrapCallErr(err)
	}
	return wrapOK(data)
}

// CreateRoom 创建房间并自动开启会话。
// 契约：参数 {name,password,category,visible,hostPort,loader,gameVersion}（无 maxPlayers/authType）。
// hostPort 必填（1024-65535）；create body 的 maxPlayers 固定 20、不发 authType；
// loader/gameVersion 用传入值（空则 "unknown"；loader 如实上报启动器/用户所选加载器名，
// 不硬编码，对齐 docs/launcher-integration.md §2B#14），
// natType 用探测结果（拿不到 "unknown"），
// clientCapabilities 见 appCapabilities（逐项门控装配），modVersion=appVersion。
//
// 流程：参数校验 → 会话互斥检查 → 端口探测 → STUN → 调用 /room/create → 启会话。
// 端口探测失败不再调用 create API，直接返回 PORT_NOT_LISTENING。
func (a *App) CreateRoom(reqJSON string) string {
	var req struct {
		Name        string `json:"name"`
		Password    string `json:"password"`
		Category    string `json:"category"`
		Visible     *bool  `json:"visible"`
		HostPort    int    `json:"hostPort"`
		Loader      string `json:"loader"`
		GameVersion string `json:"gameVersion"`
		// ClientTag 本次建房要显示的客户端标签（CLI --client-tag）。空则用设置里的持久值。
		ClientTag string `json:"clientTag"`
	}
	if err := parseParams(reqJSON, &req); err != nil {
		return wrapErr("INVALID_PARAMS", err.Error())
	}
	if strings.TrimSpace(req.Name) == "" {
		return wrapErr("INVALID_PARAMS", "房间名不能为空")
	}
	if req.HostPort < 1024 || req.HostPort > 65535 {
		return wrapErr("INVALID_PARAMS", "hostPort 必须在 1024-65535 之间")
	}
	loader := strings.TrimSpace(req.Loader)
	if loader == "" {
		loader = "unknown"
	}
	gameVersion := strings.TrimSpace(req.GameVersion)
	if gameVersion == "" {
		gameVersion = "unknown"
	}
	visible := true
	if req.Visible != nil {
		visible = *req.Visible
	}

	// clientTag：本次建房的来源标签（CLI `room create --client-tag`）。给了就用它，
	// 不给则回落到设置里的持久值；清洗后为空则不发。
	// 报错而不是静默丢弃：这是面向开发者的入参，必须告诉他为什么没生效。
	clientTag := a.clientTagOf()
	if strings.TrimSpace(req.ClientTag) != "" {
		clean, err := sanitizeClientTag(req.ClientTag)
		if err != nil {
			return wrapErr("INVALID_PARAMS", err.Error())
		}
		if clean == "" {
			return wrapErr("INVALID_PARAMS",
				"clientTag 没有可用字符（允许：字母/数字/下划线/连字符/中文/单个空格）")
		}
		clientTag = clean
	}

	// 会话互斥 + 占位：hosting/in_room 或上一次建房/加入还在途时拒绝（不再自动 LeaveRoom）。
	if msg := requireIdleSession(a); msg != "" {
		return msg
	}
	sessionStarted := false
	defer func() {
		if !sessionStarted {
			a.releaseSessionSlot() // 没真正开会话就把占位交还，别让 App 永远"忙"
		}
	}()

	// 端口探测：本地必须有人在监听 hostPort（MC / 服务端）。
	if err := probeHostPort(req.HostPort); err != nil {
		return wrapErr("PORT_NOT_LISTENING",
			fmt.Sprintf("端口 %d 没有服务在监听，请先在 MC 开好局域网或启动服务端", req.HostPort))
	}

	// natType 建房请求里先按 "unknown" 发（模组同样是 instant create + NAT probe deferred，
	// RoomManager.java:161-162：探测放后台，探到再 updateRoom）。
	// 本端此前在这里同步做一次端口-only 的轻探测，最坏会阻塞建房两秒多——现在交给
	// hostSessionStarted 的后台探测（session_wiring.go），与模组同一时序。
	natType := "unknown"

	// 本机 /info 服务（PeerServer.java:54-107）：模组在游戏启动时就拉起，建房时把端口随
	// create_room 上报（RoomManager.java:162 取端口 → SignalingClient.java:185-186）。
	// 本端的进程模型是"建房/加入才起"，同样只绑回环、同样随机端口。
	setPeerCacheProvider(a.peerCacheFromApp)
	peerPort := startPeerServer(a)

	body := map[string]any{
		"name":                  req.Name,
		"maxPlayers":            20,
		"hostPort":              req.HostPort,
		"natType":               natType,
		"visible":               visible,
		"category":              req.Category,
		"gameVersion":           gameVersion,
		"loader":                loader,
		"clientType":            "app",
		"clientProtocolVersion": clientProtocolVersion,
		"clientCapabilities":    a.modSyncCapabilities(), // modVersion：本端版本号，服务端存进房间（server-go/roommanager.go:386-390）
		// 并渲染到大厅/后台版本列。模组在 create 即无条件上报
		// （SignalingClient.java:192），本端对齐携带 appVersion。
		"modVersion": appVersion,
	}
	// clientTag：来源标记，为空则整个字段不发（服务端 create 侧再校验一次）。
	if clientTag != "" {
		body["clientTag"] = clientTag
	}
	if peerPort > 0 {
		body["peerPort"] = peerPort // SignalingClient.java:185-186：未起服务时不发这个键
	}
	if req.Password != "" {
		body["password"] = req.Password
	}

	var data struct {
		Code      string `json:"code"`
		HostToken string `json:"hostToken"`
		Name      string `json:"name"`
		HostIP    string `json:"hostIp"`
		HostIPv6  string `json:"hostIpv6"` // RoomManager.java:474 的 ipv6 可达性探测要用
		HostPort  int    `json:"hostPort"`
		ExpiresIn int    `json:"expiresIn"`
		// Fed：服务端为本房开的 Happy Link 镜像凭据（RoomManager.java:359 用 isJsonObject 判定，
		// 这里用 any 复刻"缺字段/非对象都不当镜像房"的宽容解析）。
		Fed any `json:"fed"`
	}
	if err := a.api.post(a.baseURL(), "/room/create", body, &data); err != nil {
		return wrapCallErr(err)
	}

	room := map[string]any{
		"code":           data.Code,
		"name":           data.Name,
		"hostIp":         data.HostIP,
		"hostPort":       data.HostPort,
		"maxPlayers":     20,
		"currentPlayers": 1,
		"hasPassword":    req.Password != "",
		"category":       req.Category,
		"gameVersion":    gameVersion,
		"loader":         loader,
		"clientType":     "app",
		"expiresIn":      data.ExpiresIn,
		"isHost":         true,
	}
	// 互通：服务端给了 fed 凭据就是镜像房，房主侧隧道必须在会话起来之前武装好
	// （RoomManager.java:367 早于 :371 的时序，晚了会有一窗 HL 玩家打不进本地 MC）。
	if applyFedCreatePayload(room, data.Fed) {
		a.startFedTunnelForRoom(room, req.HostPort)
	}
	sessionStarted = true
	a.startSession(data.Code, data.HostToken, true, room, "")
	a.modSyncOnRoomCreated()                                                            // 建房成功后立即后台构建两档清单（ModSyncManifestService 的 onRoomCreated）
	a.hostSessionStarted(data.Code, req.Name, req.HostPort, data.HostIP, data.HostIPv6) // 模组同位置：UPnP + STUN 精探 + 端口可达性 + 日志样本登记

	return wrapOK(map[string]any{
		"code":      data.Code,
		"hostToken": data.HostToken,
		"name":      data.Name,
		"hostIp":    data.HostIP,
		"hostPort":  data.HostPort,
		"expiresIn": data.ExpiresIn,
	})
}

// requireIdleSession 会话互斥检查 + 占位：忙（已开会话，或上一次建房/加入还在途）时
// 返回 SESSION_ACTIVE 错误串，成功时把会话槽位原子占下。
// CreateRoom 与 JoinRoom 共用（建房中再建房/再加入是事故源，删除原来的自动 LeaveRoom 幂等）。
// **占到槽位的调用必须在每一条提前 return 的路径上释放**——统一用
// defer 配 "没走到 startSession 就 release" 的写法，别手写在各分支里。
func requireIdleSession(a *App) string {
	a.mu.Lock()
	busy := a.state != "idle" || a.slotTaken
	if !busy {
		a.slotTaken = true
	}
	a.mu.Unlock()
	if busy {
		return wrapErr("SESSION_ACTIVE", "请先退出当前房间再操作")
	}
	return ""
}

// releaseSessionSlot 交还占位（建房/加入失败、或会话结束时）。
func (a *App) releaseSessionSlot() {
	a.mu.Lock()
	a.slotTaken = false
	a.mu.Unlock()
}

// ---- 加入房间瞬态重试（对齐模组 ConnectionManager.java:156-157/14891-14930 + RoomManager.java:88） ----

const (
	// joinTransientMaxAttempts 最多 3 次尝试（ConnectionManager.java:156 JOIN_TRANSIENT_MAX_ATTEMPTS = 3）。
	joinTransientMaxAttempts = 3
	// joinRetryBackoffFirstMs / joinRetryBackoffSecondMs 重试退避 {1500,3000}ms
	// （ConnectionManager.java:157 JOIN_RETRY_BACKOFF_MS = {1500L, 3000L}）。
	joinRetryBackoffFirstMs  = 1500
	joinRetryBackoffSecondMs = 3000
)

// isTransientJoinError 判定加入失败是否瞬态可重试。
// 对齐模组 RoomManager.java:88 TRANSIENT_ERRORS = {NETWORK_ERROR, CDN_ERROR, RATE_LIMITED}；
// 其中 "NETWORK" 是本端 api.go 对传输层失败的等价编码（模组侧由 SignalingClient.java:640-650
// 把连接超时/拒连/DNS 统一映射成 NETWORK_ERROR，语义同一类）。
func isTransientJoinError(err error) bool {
	var ae *APIError
	if !errors.As(err, &ae) {
		// 本端所有传输层失败都在 api.go do() 里包成 *APIError{Code:"NETWORK"}
		// （等价模组 SignalingClient.java:640-650 把连接超时/拒连/DNS 映射成 NETWORK_ERROR）；
		// 非 APIError 说明是响应解析等本地问题，模组侧对应 RuntimeException（不可重试）。
		return false
	}
	switch ae.Code {
	case "NETWORK_ERROR", "CDN_ERROR", "RATE_LIMITED", "NETWORK":
		return true
	}
	return false
}

// joinIdempotencyKey 返回本 App 生命周期的加入幂等键（懒生成、跨重试链复用，
// 对齐模组 SignalingClient.java:69,279-283：nonce 只生成一次，重试不换 key；
// 服务端按 key 去重并复用原 clientId，roommanager.go:777-800）。
func (a *App) joinIdempotencyKey() string {
	a.joinNonceMu.Lock()
	defer a.joinNonceMu.Unlock()
	if a.joinNonce == "" {
		buf := make([]byte, 16)
		if _, err := rand.Read(buf); err != nil {
			// 随机源异常兜底：时间戳派生 32 位 hex，保证 key 唯一即可（服务端只按字符串去重）。
			buf = make([]byte, 16)
			n := time.Now().UnixNano()
			for i := range buf {
				buf[i] = byte(n >> (i % 8 * 8))
			}
		}
		a.joinNonce = hex.EncodeToString(buf)
	}
	return a.joinNonce
}

// JoinRoom 加入房间并自动开启会话（body 带与 create 相同的 caps 组）。
// 流程：参数校验 → 会话互斥 → /room/join（瞬态错误有界重试：≤3 次尝试、退避 {1500,3000}ms、
// 代数保护）→ 启会话。
// 不再做自动 LeaveRoom 幂等：建房中再加入是事故源，统一走 SESSION_ACTIVE。
func (a *App) JoinRoom(reqJSON string) string {
	var req struct {
		Code     string `json:"code"`
		Password string `json:"password"`
	}
	if err := parseParams(reqJSON, &req); err != nil {
		return wrapErr("INVALID_PARAMS", err.Error())
	}
	req.Code = strings.ToUpper(strings.TrimSpace(req.Code))
	if !ValidateJoinRoomCode(req.Code) {
		return wrapErr("INVALID_PARAMS", "房间码必须为 6 位（字符集 A-Z 去掉 I/O、2-9 去掉 0/1）")
	}

	// 会话互斥 + 占位：加入重试链要跑几十秒，这期间必须挡住第二次加入/建房（见 requireIdleSession）。
	if msg := requireIdleSession(a); msg != "" {
		return msg
	}
	sessionStarted := false
	defer func() {
		if !sessionStarted {
			a.releaseSessionSlot()
			// 加入没成：撤销日志上传登记（模组 AttemptingJoinScreen.java:249 的 cancelJoin 同款，
			// 注释原文"否则 90s 后 runUpload 照跑，玩家取消了还上传日志"）
			a.logUploadDisarm()
		}
	}()

	// 新一轮加入：代数 +1（模组 startVoxLinkP2P 的 joinAttemptGeneration.incrementAndGet()，
	// ConnectionManager.java:14892）。leaveInternal 同样 bump，令旧链放弃。
	gen := a.joinAttemptGeneration.Add(1)
	a.guestSessionStarted(req.Code) // AttemptingJoinScreen.java:258（界面一开就 arm，失败样本才完整）

	body := map[string]any{
		"code":                  req.Code,
		"clientType":            "app",
		"clientProtocolVersion": clientProtocolVersion,
		"clientCapabilities":    a.modSyncCapabilities(),
		// modVersion：服务端从 join body 读取并按 clientId 落库（roommanager.go:739-743,829-831），
		// 后台"房间管理"卡片据此显示房客版本；模组 join 侧同样携带（SignalingClient.java:286）。
		"modVersion":     appVersion,
		"idempotencyKey": a.joinIdempotencyKey(),
	}
	if req.Password != "" {
		body["password"] = req.Password
	}

	// 瞬态重试链（对齐 startVoxLinkP2PAttempt，ConnectionManager.java:14896-14930）：
	// 失败 → 可重试 && 未到 3 次上限 && 代数未变 → 退避后重试；否则终止并留痕。
	// 重试不换请求体：同一 idempotencyKey，服务端复用 clientId（幂等分支）。
	var data struct {
		ClientToken string         `json:"clientToken"`
		ClientID    string         `json:"clientId"`
		Room        map[string]any `json:"room"`
	}
	lastErr := error(nil)
	for attempt := 1; attempt <= joinTransientMaxAttempts; attempt++ {
		start := time.Now()
		err := a.api.post(a.baseURL(), "/room/join", body, &data)
		if err == nil {
			if attempt > 1 {
				a.netLog("info", fmt.Sprintf("join_ok attempt=%d/%d elapsed_ms=%d", attempt, joinTransientMaxAttempts, time.Since(start).Milliseconds()))
			}
			lastErr = nil
			break
		}
		lastErr = err
		a.netLog("warn", fmt.Sprintf("join_attempt_failed attempt=%d/%d elapsed_ms=%d err=%s", attempt, joinTransientMaxAttempts, time.Since(start).Milliseconds(), err.Error()))
		retryable := isTransientJoinError(err)
		if !retryable || attempt >= joinTransientMaxAttempts || gen != a.joinAttemptGeneration.Load() {
			break
		}
		delayMs := joinRetryBackoffFirstMs
		if attempt >= 2 {
			delayMs = joinRetryBackoffSecondMs
		}
		// 日志用键（i18n-neutral）：join_retry，携带 attempt 进度（对齐 UiLogBus voxlink.logui.join_retry）。
		a.netLog("warn", fmt.Sprintf("join_retry attempt=%d/%d delay_ms=%d", attempt+1, joinTransientMaxAttempts, delayMs))
		time.Sleep(time.Duration(delayMs) * time.Millisecond)
		// 代数被 bump（取消/离开）→ 旧链放弃，不再发起下一次尝试。
		if gen != a.joinAttemptGeneration.Load() {
			lastErr = &APIError{Code: "CANCELLED", Message: "join chain superseded"}
			break
		}
	}
	if lastErr != nil {
		a.netLog("error", "join_failed err="+lastErr.Error())
		return wrapCallErr(lastErr)
	}

	room := data.Room
	if room == nil {
		room = map[string]any{}
	}
	room["code"] = req.Code
	room["isHost"] = false
	// join 响应可能不带 clientType/gameVersion/loader，标准化透传字段（"app" 一律由本端填）。
	if _, ok := room["clientType"]; !ok || room["clientType"] == "" {
		room["clientType"] = "app"
	}
	applyFedJoinPayload(room) // join 回包里的 fedAddress/source（RoomManager.java:766-771）
	sessionStarted = true
	a.startSession(req.Code, data.ClientToken, false, room, data.ClientID)
	a.connectFedIfHappyLink() // HL 房：不打洞不走中继，直接 TCP 转中继（RoomManager.java:827-830）

	return wrapOK(map[string]any{
		"clientToken": data.ClientToken,
		"clientId":    data.ClientID,
		"room":        room,
	})
}

// LeaveRoom 停止会话并尽力通知服务器离开。
func (a *App) LeaveRoom() string {
	a.mu.Lock()
	had := a.session != nil
	a.mu.Unlock()
	if !had {
		return wrapOK(map[string]any{"left": false})
	}
	a.leaveInternal()
	a.emit("session:state", map[string]any{"state": "idle"})
	return wrapOK(map[string]any{"left": true})
}

// GetSessionState 返回当前会话状态。
func (a *App) GetSessionState() string {
	a.mu.Lock()
	state := a.state
	var room any
	if state != "idle" && a.room != nil {
		room = copyMap(a.room)
	}
	a.mu.Unlock()
	return wrapOK(map[string]any{"state": state, "room": room})
}

// SendRoomUpdate host 修改房间信息（name/category/visible/password）。
// 契约：收到 maxPlayers/authType 字段直接忽略（不再透传到服务端）。
func (a *App) SendRoomUpdate(reqJSON string) string {
	var req map[string]any
	if err := parseParams(reqJSON, &req); err != nil {
		return wrapErr("INVALID_PARAMS", err.Error())
	}

	a.mu.Lock()
	code, token := a.code, a.token
	hosting := a.state == "hosting" && a.isHost
	a.mu.Unlock()

	if req["code"] != nil {
		code, _ = req["code"].(string)
	}
	if req["token"] != nil {
		token, _ = req["token"].(string)
	}
	if code == "" || token == "" {
		return wrapErr("NO_SESSION", "当前没有房间会话")
	}

	body := map[string]any{}
	// 仅接受 name/category/visible/password；maxPlayers/authType 一律忽略
	for _, k := range []string{"name", "category", "visible", "password"} {
		if v, ok := req[k]; ok {
			body[k] = v
		}
	}
	body["code"] = code
	body["token"] = token
	body["isHost"] = hosting

	if err := a.api.post(a.baseURL(), "/room/update", body, nil); err != nil {
		return wrapCallErr(err)
	}
	return wrapOK(map[string]any{})
}

// GetRoomMods 查询房间模组支持情况。
func (a *App) GetRoomMods(code string) string {
	if code == "" {
		return wrapErr("INVALID_PARAMS", "房间码不能为空")
	}
	var data map[string]any
	if err := a.api.post(a.baseURL(), "/room/mods", map[string]any{"code": code}, &data); err != nil {
		return wrapCallErr(err)
	}
	return wrapOK(data)
}

// RelayStatus 查询中继是否启用。
func (a *App) RelayStatus() string {
	var data map[string]any
	if err := a.api.get(a.baseURL(), "/relay/status", nil, &data); err != nil {
		return wrapCallErr(err)
	}
	return wrapOK(data)
}

// RelayList 查询中继节点列表。
func (a *App) RelayList() string {
	var data map[string]any
	if err := a.api.get(a.baseURL(), "/relay/list", nil, &data); err != nil {
		return wrapCallErr(err)
	}
	return wrapOK(data)
}

// GetStun 获取本机公网地址。
func (a *App) GetStun() string {
	var data map[string]any
	if err := a.api.get(a.baseURL(), "/stun", nil, &data); err != nil {
		return wrapCallErr(err)
	}
	return wrapOK(data)
}

// ==================== 连接引擎绑定方法 ====================

// DetectMcPorts 探测本机 TCP LISTENING 端口及进程名，java* 进程优先。
// 返回 {ok,data:{ports:[{port,pid,name}]}}。
func (a *App) DetectMcPorts() string {
	ports, err := detectMcPorts()
	if err != nil {
		return wrapErr("DETECT_FAILED", err.Error())
	}
	return wrapOK(map[string]any{"ports": ports})
}

// 2026-10-05 对齐模组 1.2.0：手动中继按钮退役——直连/玩家中继/TURN 一律由引擎侧
// 智能自动编排器触发。原先的 UI 绑定方法 TryDirect / UsePlayerRelay / UseTurnRelay
// 已随前端连接卡一起去掉调用方后删除；引擎本体 tryDirect() / usePlayerRelay() /
// useTurnRelay() 保留（编排器需要），见 conn_engine.go / relay.go / turn_flow.go。
