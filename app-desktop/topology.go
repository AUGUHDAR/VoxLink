package main

// topology.go — 拓扑指令客户端（逐句对齐模组 network/TopologyClient.java，1.2.0，289 行）。
//
// 职责：会话期内持有 P2POverlayManager（overlay.go），消费服务端下发的重排指令
// （become_head / connect_to / direct_server，:106-118），并把链路就绪回报给服务端
// （/topology/report_ready）；收到 topology_change 才补一次 /topology/poll（:153-168）。
//
// 节奏与线上字段（file:line 指 TopologyClient.java，除注明外）：
//   - become_head  → 1000ms 后 reportLinkReady（:213，常量 SCHEDULE_DELAY_MS :20）
//   - connect_to   →  500ms 后 reportLinkReady（:231，常量 SCHEDULE_PERIOD_MS :21）
//   - direct_server→ 立即 reportLinkReady（:239）
//   - nodeId = "host_"+roomCode / "client_"+clientId（:64，与服务端 deriveTopologyNodeID
//     的 host_<CODE> / client_<clientId> 一致）
//   - 指令只在 generation 变大时生效，生效后 reportedReady 复位（:102-105）
//   - 心跳要带 peerLatency + overlayPort（:79-96 + SignalingClient.java:323-339）
//
// 现网事实（务必知悉，不改本端代码来绕过）：服务端 /topology/poll 与 /topology/report_ready
// 恒 403 —— handlers_misc.go:741-768 的 deriveTopologyNodeID 拿 RoomInfo() 的脱敏视图
// （不含 hostToken/clients）去校验 token，必然失败（记录见 .workbuddy/audit/20261010-06*
// 与"已定位未修的隐患清单"第 8 条）。本端按模组原样实现，不做任何客户端侧兜底。

import (
	"encoding/hex"
	"fmt"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

const (
	topoScheduleDelayMs  = 1000 * time.Millisecond // :20 SCHEDULE_DELAY_MS（:213 用 1000L）
	topoSchedulePeriodMs = 500 * time.Millisecond  // :21 SCHEDULE_PERIOD_MS（:231 用 500L）
	topoUUIDPrefixLen    = 8                       // :22 UUID_PREFIX_LEN（:287 substring(0,8)）
	topoAwaitTermination = 2 * time.Second         // :46 awaitTermination(2, SECONDS)
	topoAwaitPollEvery   = 10 * time.Millisecond   // awaitTermination 的检查粒度（Java 由 JDK 内部等待）
)

// 路由（SignalingClient.java:51-52 ACTION_TO_ROUTE + :499-516）
const (
	topoRoutePoll        = "/topology/poll"
	topoRouteReportReady = "/topology/report_ready"
)

// ---- 延时执行器（:23-27 TOPOLOGY_DELAY_EXECUTOR：单线程 ScheduledExecutor，进程级共享） ----

type topoDelayEntry struct {
	due time.Time
	seq uint64
	fn  func()
}

var topoDelay = struct {
	mu      sync.Mutex
	tasks   []*topoDelayEntry
	wake    chan struct{}
	stopped bool
	seq     uint64
	once    sync.Once
}{wake: make(chan struct{}, 1)}

// topoDelaySchedule 延时执行 fn（等价 schedule(runnable, delay, MILLIS)）。
// 已 shutdown 时丢弃并留痕：Java 会抛 RejectedExecutionException 冒泡到指令处理，
// 而 shutdown 只在 mod 退出时发生（VoxLinkMod.java:115），此后不会再有指令进来，
// 两者可观测差别仅在"退出竞态里少一次上报"。
func topoDelaySchedule(delay time.Duration, fn func()) {
	td := &topoDelay
	td.once.Do(func() { go topoDelayWorker() })
	td.mu.Lock()
	if td.stopped {
		td.mu.Unlock()
		return
	}
	td.seq++
	due := time.Now().Add(delay)
	td.tasks = append(td.tasks, &topoDelayEntry{due: due, seq: td.seq, fn: fn})
	sort.SliceStable(td.tasks, func(i, j int) bool {
		if td.tasks[i].due.Equal(td.tasks[j].due) {
			return td.tasks[i].seq < td.tasks[j].seq
		}
		return td.tasks[i].due.Before(td.tasks[j].due)
	})
	td.mu.Unlock()
	select {
	case td.wake <- struct{}{}:
	default:
	}
}

func topoDelayWorker() {
	td := &topoDelay
	for {
		td.mu.Lock()
		now := time.Now()
		var due []func()
		for len(td.tasks) > 0 && !td.tasks[0].due.After(now) {
			due = append(due, td.tasks[0].fn)
			td.tasks = td.tasks[1:]
		}
		idle := len(td.tasks) == 0
		var wait time.Duration
		if !idle {
			wait = td.tasks[0].due.Sub(now)
			if wait < 0 {
				wait = 0
			}
		}
		stopped := td.stopped
		td.mu.Unlock()

		for _, fn := range due {
			topoRunQuietly(fn)
		}
		if stopped && idle {
			return
		}
		if idle {
			<-td.wake
			continue
		}
		select {
		case <-td.wake:
		case <-time.After(wait):
		}
	}
}

// topoRunQuietly 任务异常不能掀翻调度线程（Java 的 scheduleAtFixedRate 会把异常吞进
// ScheduledFuture，主调度继续跑）。
func topoRunQuietly(fn func()) {
	defer func() { _ = recover() }()
	fn()
}

// shutdownTopologyDelay 对应 :42-53 shutdown()：不再接受新任务，等 2s，超时则强停。
func shutdownTopologyDelay() {
	td := &topoDelay
	td.mu.Lock()
	if td.stopped {
		td.mu.Unlock()
		return
	}
	td.stopped = true
	td.mu.Unlock()

	deadline := time.Now().Add(topoAwaitTermination)
	for {
		td.mu.Lock()
		n := len(td.tasks)
		td.mu.Unlock()
		if n == 0 {
			break
		}
		if time.Now().After(deadline) {
			td.mu.Lock()
			td.tasks = nil
			td.mu.Unlock()
			break
		}
		time.Sleep(topoAwaitPollEvery)
	}
	select {
	case td.wake <- struct{}{}:
	default:
	}
}

// ---- 主线程跳转（:145-151 executeOnClientThread） ----

// topoMainThread 非 nil 时，指令处理被投递到它指定的执行器（对应 Minecraft.execute）。
// 桌面端没有"客户端主线程"这一约束，默认直接内联执行 —— 与模组 NoClassDefFoundError
// 分支（:148-150 action.run()）同形态。若日后要串行化到 UI 线程，挂上这个钩子即可。
var topoMainThread atomic.Pointer[func(func())]

func topoRunOnMainThread(action func()) {
	if hook := topoMainThread.Load(); hook != nil {
		(*hook)(action)
		return
	}
	action()
}

// setTopologyMainThread 挂上串行执行器（对应 Minecraft.getInstance().execute）。
func setTopologyMainThread(hook func(func())) {
	if hook == nil {
		topoMainThread.Store(nil)
		return
	}
	h := hook
	topoMainThread.Store(&h)
}

// ---- TopologyClient ----

type topologyClient struct {
	app     *App
	overlay *overlayManager // :28

	knownGeneration atomic.Int32 // :30
	optimizing      atomic.Bool  // :31
	reportedReady   atomic.Bool  // :32

	mu                 sync.Mutex // :33-40 的 volatile 字段
	roomCode           string
	token              string
	isHost             bool
	nodeID             string
	onOptimizing       func(bool)
	onData             func(map[string]any)
	cachedUpstreamID   string
	cachedDownstreamID string
}

// newTopologyClient 对应 :55-58：覆盖网先以一个随机 8 位 id、端口 0 建好，
// 真正身份在 onRoomJoined 里 setNodeID 覆盖。
func newTopologyClient(app *App) *topologyClient {
	t := &topologyClient{app: app}
	t.overlay = newOverlayManager(topoCreateNodeID(), 0, app.netLog)
	// :66 的静态 LOGGER 在本端换成包级日志出口（解压超限的 WARN 走它）
	defaultOverlayLog = app.netLog
	return t
}

// topoCreateNodeID 对应 :286-288 getOrCreateNodeId（UUID.randomUUID().substring(0,8)）。
// UUID 的前 8 位同样是十六进制（8-4-4-4-12 分段），故用 16 随机字节 → hex 取前 8 位，
// 随机性与取值域都一致；它只是 setNodeID 之前的占位身份（:57 建实例、:70 覆盖）。
func topoCreateNodeID() string {
	buf := make([]byte, 16)
	if _, err := cryptRandRead(buf); err != nil {
		n := time.Now().UnixNano()
		for i := range buf {
			buf[i] = byte(n >> (i % 8 * 8))
		}
	}
	raw := hex.EncodeToString(buf)
	if len(raw) > topoUUIDPrefixLen {
		return raw[:topoUUIDPrefixLen]
	}
	return raw
}

// onRoomJoined 对应 :60-77。
func (t *topologyClient) onRoomJoined(roomCode, token string, isHost bool, clientID string, generation int) {
	t.mu.Lock()
	t.roomCode = roomCode
	t.token = token
	t.isHost = isHost
	nodeID := "client_" + clientID
	if isHost {
		nodeID = "host_" + roomCode
	}
	t.nodeID = nodeID
	t.mu.Unlock()

	t.knownGeneration.Store(int32(generation))
	t.reportedReady.Store(false)

	if err := t.overlay.start(t); err != nil {
		t.app.netLog("warn", "P2P overlay start failed, keep direct: "+err.Error())
	} else {
		t.overlay.setNodeID(nodeID)
		t.app.netLog("info", "P2P overlay started, node "+nodeID)
	}
}

// pollAndGetPeerLatency 对应 :79-96：把上下游实测时延折算成 {邻居 nodeId → ms}，
// 只有 >0 才上报（-1 表示"尚未测得"，0 表示链路不通）。
func (t *topologyClient) pollAndGetPeerLatency() map[string]int {
	out := map[string]int{}
	if t.overlay.getRole() == overlayRoleNone {
		return out
	}
	t.mu.Lock()
	upID, downID := t.cachedUpstreamID, t.cachedDownstreamID
	t.mu.Unlock()
	if ul := t.overlay.getUpstreamLatency(); ul > 0 && upID != "" {
		out[upID] = ul
	}
	if dl := t.overlay.getDownstreamLatency(); dl > 0 && downID != "" {
		out[downID] = dl
	}
	return out
}

// handleTopologyInstruction 对应 :98-121。
func (t *topologyClient) handleTopologyInstruction(instruction map[string]any) {
	if instruction == nil {
		return
	}
	action, _ := topoStr(instruction, "action")
	gen := topoInt(instruction, "generation") // Java：缺键 → 0（永远不大于已知代数）
	if int32(gen) > t.knownGeneration.Load() {
		t.knownGeneration.Store(int32(gen))
		t.reportedReady.Store(false)
		t.app.netLog("info", fmt.Sprintf("Process topology instruction: action=%s, gen=%d", action, gen))
		switch action {
		case "become_head":
			t.handleBecomeHead(instruction)
		case "connect_to":
			t.handleConnectTo(instruction)
		case "direct_server":
			t.handleDirectServer(instruction)
		default:
			t.app.netLog("warn", "Unknown topology instruction: "+action)
		}
	}
}

// handlePollInstructions 对应 :123-143。
func (t *topologyClient) handlePollInstructions(response map[string]any) {
	if response == nil {
		return
	}
	if v, ok := response["optimizing"]; ok && topoBool(v) {
		t.setOptimizing(true)
	}
	if mode, ok := topoStr(response, "mode"); ok && mode == "direct_fallback" &&
		t.overlay.getRole() != overlayRoleNone {
		t.overlay.switchToDirectMode()
	}
	if arr, ok := response["instructions"].([]any); ok {
		for _, elem := range arr {
			if obj, ok := elem.(map[string]any); ok {
				t.handleTopologyInstruction(obj)
			}
		}
	}
}

// handleTopologySignal 对应 :153-168（信令侧的两个拓扑事件）。
func (t *topologyClient) handleTopologySignal(signalType string, data map[string]any) {
	switch signalType {
	case "topology_optimization_done":
		t.setOptimizing(false)
		t.app.netLog("info", "Topology optimization completed")
	case "topology_change":
		t.pollTopology()
	}
	_ = data // 模组的 data 未被消费（:153-168）
}

// topologyPrefetched CM:6399-6420 prefetchRelayCandidates 的 relayPrefetchFuture==null 门：
// 模组的 ConnectionManager 与 RoomManager 同生命周期，所以这个"预热"一次进程只做一次。
var topologyPrefetched atomic.Bool

// topologyPrefetchRelay Layer2 中继预热（CM:6576-6586 的调用点在本端 NAT 分完档之后）。
func topologyPrefetchRelay() {
	t := getTopologyClient()
	if t == nil || !topologyPrefetched.CompareAndSwap(false, true) {
		return
	}
	t.pollTopologyNow()
}

// pollTopologyNow 与 pollTopology 同一条 /topology/poll，差别只是这里要出声：
// 预热的意义就是"早点知道有没有备胎"，静默失败等于让排查猜。
func (t *topologyClient) pollTopologyNow() {
	t.mu.Lock()
	code, token, isHost := t.roomCode, t.token, t.isHost
	t.mu.Unlock()
	if code == "" || token == "" {
		return
	}
	var resp map[string]any
	err := t.app.api.post(t.app.baseURL(), topoRoutePoll, map[string]any{
		"code":       code,
		"token":      token,
		"isHost":     isHost,
		"generation": 0,
	}, &resp)
	if err != nil {
		t.app.netLog("warn", "Layer2 中继预热失败: "+err.Error())
		return
	}
	t.app.netLog("info", "Layer2 中继预热完成")
	topoRunOnMainThread(func() { t.handlePollInstructions(resp) })
}

// pollTopology 对应 :160-166：roomCode/token 齐备才轮询，成功才处理指令；
// 失败模组完全静默（连日志都没有），本端保持一致。
func (t *topologyClient) pollTopology() {
	t.mu.Lock()
	code, token, isHost := t.roomCode, t.token, t.isHost
	t.mu.Unlock()
	if code == "" || token == "" {
		return
	}
	var resp map[string]any
	err := t.app.api.post(t.app.baseURL(), topoRoutePoll, map[string]any{
		"code":       code,
		"token":      token,
		"isHost":     isHost,
		"generation": int(t.knownGeneration.Load()),
	}, &resp)
	if err != nil || resp == nil {
		return
	}
	topoRunOnMainThread(func() { t.handlePollInstructions(resp) })
}

// onRoomLeft 对应 :170-180。
func (t *topologyClient) onRoomLeft() {
	t.overlay.stop()
	t.mu.Lock()
	t.roomCode = ""
	t.token = ""
	t.nodeID = ""
	t.cachedUpstreamID = ""
	t.cachedDownstreamID = ""
	t.mu.Unlock()
	t.knownGeneration.Store(0)
	t.setOptimizing(false)
	t.reportedReady.Store(false)
}

func (t *topologyClient) isOptimizing() bool { return t.optimizing.Load() }

// setOnOptimizingChanged / setOnDataReceived 对应 :186-192。
func (t *topologyClient) setOnOptimizingChanged(cb func(bool)) {
	t.mu.Lock()
	t.onOptimizing = cb
	t.mu.Unlock()
}

func (t *topologyClient) setOnDataReceived(cb func(map[string]any)) {
	t.mu.Lock()
	t.onData = cb
	t.mu.Unlock()
}

func (t *topologyClient) getKnownGeneration() int { return int(t.knownGeneration.Load()) }

func (t *topologyClient) getNodeId() string {
	t.mu.Lock()
	defer t.mu.Unlock()
	return t.nodeID
}

func (t *topologyClient) getOverlayManager() *overlayManager { return t.overlay }

// ---- 指令落地 ----

func (t *topologyClient) handleBecomeHead(instruction map[string]any) {
	downstream, _ := topoStr(instruction, "downstream")
	downstreamIP, _ := topoStr(instruction, "downstream_ip")
	downstreamPort := topoInt(instruction, "downstream_port")
	t.mu.Lock()
	t.cachedDownstreamID = downstream
	t.mu.Unlock()
	t.overlay.becomeHead(downstream, downstreamIP, downstreamPort)
	t.setOptimizing(true)
	topoDelaySchedule(topoScheduleDelayMs, t.reportLinkReady)
}

func (t *topologyClient) handleConnectTo(instruction map[string]any) {
	upstream, _ := topoStr(instruction, "upstream")
	upstreamIP, ok := topoStr(instruction, "upstream_ip")
	if !ok {
		upstreamIP = "0.0.0.0" // :218 的默认值
	}
	upstreamPort := topoInt(instruction, "upstream_port")
	downstream, hasDown := topoStr(instruction, "downstream")
	t.mu.Lock()
	t.cachedUpstreamID = upstream
	if hasDown {
		t.cachedDownstreamID = downstream
	} else {
		t.cachedDownstreamID = ""
	}
	t.mu.Unlock()
	t.overlay.connectUpstream(upstream, upstreamIP, upstreamPort)
	if hasDown && downstream != "" {
		downstreamPort := topoInt(instruction, "downstream_port")
		downstreamIP, _ := topoStr(instruction, "downstream_ip")
		t.overlay.setDownstream(downstream, downstreamIP, downstreamPort)
	}
	t.setOptimizing(true)
	topoDelaySchedule(topoSchedulePeriodMs, t.reportLinkReady)
}

func (t *topologyClient) handleDirectServer(instruction map[string]any) {
	reason, ok := topoStr(instruction, "reason")
	if !ok {
		reason = "unknown" // :235
	}
	t.app.netLog("info", "Switched to direct mode, reason: "+reason)
	t.overlay.switchToDirectMode()
	t.setOptimizing(true)
	t.reportLinkReady()
}

// reportLinkReady 对应 :242-257：每代指令只报一次（reportedReady 抢在前）。
func (t *topologyClient) reportLinkReady() {
	if t.reportedReady.Swap(true) {
		return
	}
	t.mu.Lock()
	code, token, isHost := t.roomCode, t.token, t.isHost
	t.mu.Unlock()
	if code == "" || token == "" {
		return
	}
	err := t.app.api.post(t.app.baseURL(), topoRouteReportReady, map[string]any{
		"code":   code,
		"token":  token,
		"isHost": isHost,
	}, nil)
	if err == nil {
		t.app.netLog("info", "Link ready report success")
		return
	}
	code2 := topoErrCode(err)
	// 房间已终态后的迟到上报属正常边缘，降为 debug 防噪音（:248-250 同款判据）
	if strings.Contains(code2, "INVALID_TOKEN") || strings.Contains(code2, "ROOM_NOT_FOUND") {
		t.app.netLog("debug", "Link ready report skipped (room gone): "+code2)
		return
	}
	t.app.netLog("warn", "Link ready report failed: "+code2)
}

func (t *topologyClient) setOptimizing(value bool) {
	prev := t.optimizing.Swap(value)
	if prev == value {
		return
	}
	t.mu.Lock()
	cb := t.onOptimizing
	t.mu.Unlock()
	if cb != nil {
		cb(value)
	}
}

// ---- overlayPacketHandler 实现（:266-284） ----

func (t *topologyClient) onDataReceived(from, priority string, payload *ovObject) {
	t.app.netLog("debug", fmt.Sprintf("Received P2P data, from=%s, priority=%s", from, priority))
	t.mu.Lock()
	cb := t.onData
	t.mu.Unlock()
	if cb != nil {
		cb(payload.toMap())
	}
}

func (t *topologyClient) onLinkReady() {
	t.app.netLog("info", "P2P link ready")
	t.reportLinkReady()
}

func (t *topologyClient) onLinkLost(reason string) {
	t.app.netLog("warn", "P2P link broken: "+reason)
	t.overlay.switchToDirectMode()
}

// ---- 顶层接线辅助（对齐 VoxLinkMod.java:44/141/198 的 static topologyClient） ----

var appTopologyClient atomic.Pointer[topologyClient]

// installTopologyClient 建实例并登记（模组 VoxLinkMod.java:141 的位置：SignalingClient
// 建好之后、RoomManager 之前）。
func installTopologyClient(app *App) *topologyClient {
	t := newTopologyClient(app)
	appTopologyClient.Store(t)
	return t
}

func getTopologyClient() *topologyClient { return appTopologyClient.Load() }

// topologyOnRoomJoined / topologyOnRoomLeft / topologyHandleInstruction /
// topologyHandleSignal / topologyPeerLatency / topologyOverlayPort 是给 session 与
// conn_engine 调的 nil-safe 入口（未接线时全部零作用，不影响既有联机流程）。

func topologyOnRoomJoined(roomCode, token string, isHost bool, clientID string, generation int) {
	if t := getTopologyClient(); t != nil {
		t.onRoomJoined(roomCode, token, isHost, clientID, generation)
	}
}

func topologyOnRoomLeft() {
	if t := getTopologyClient(); t != nil {
		t.onRoomLeft()
	}
}

func topologyHandleInstruction(instruction map[string]any) {
	if t := getTopologyClient(); t != nil {
		t.handleTopologyInstruction(instruction)
	}
}

// topologyHandleSignal 处理拓扑信令；返回 true 表示该信令已被消费（供信令分发处判重）。
func topologyHandleSignal(signalType string, data map[string]any) bool {
	switch signalType {
	case "topology_optimization_done", "topology_change":
	default:
		return false
	}
	if t := getTopologyClient(); t != nil {
		t.handleTopologySignal(signalType, data)
	}
	return true
}

// topologyPeerLatency 心跳体 peerLatency（模组 RoomManager.java:1474）。
func topologyPeerLatency() map[string]int {
	t := getTopologyClient()
	if t == nil {
		return map[string]int{}
	}
	return t.pollAndGetPeerLatency()
}

// topologyOverlayPort 心跳体 overlayPort（模组 RoomManager.java:1494）。
// 未接线/未起覆盖网时返回 0 —— 服务端 topology.go:698-700 会把 0 以外的非法端口也归零，
// 而 overlay_port==0 的节点不参与建链（topology.go:372/419），与模组行为一致。
func topologyOverlayPort() int {
	t := getTopologyClient()
	if t == nil {
		return 0
	}
	return t.overlay.getLocalPort()
}

// ---- 小工具 ----

func topoStr(m map[string]any, key string) (string, bool) {
	v, ok := m[key]
	if !ok || v == nil {
		return "", false
	}
	s, ok := v.(string)
	return s, ok
}

func topoInt(m map[string]any, key string) int {
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

func topoBool(v any) bool {
	if b, ok := v.(bool); ok {
		return b
	}
	return false
}

// topoErrCode 取模组的 response.error 语义：网络类失败在 Java 里就是 "NETWORK_ERROR"
// （SignalingClient.java:75 TRANSIENT_ERRORS 含该项），本端同样折算成 code 而非整条 err。
func topoErrCode(err error) string {
	if ae, ok := err.(*APIError); ok {
		return ae.Code
	}
	return "NETWORK_ERROR"
}
