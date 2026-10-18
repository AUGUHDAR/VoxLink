package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/coder/websocket"
)

const (
	defaultHeartbeatInterval = 5 * time.Second
	maxHeartbeatFailures     = 5
	maxPollFailures          = 10
	wsRetryWait              = 30 * time.Second
	httpPollInterval         = time.Second
	wsResponseTimeout        = 15 * time.Second
	// WS 健康时信令由服务端推送实时到达，帧轮询只是安全网（对齐 mod 端 5s 策略）
	wsPollInterval = 5 * time.Second

	// ---- WS 传输硬化参数（逐项对齐 network/SignalingWsTransport.java） ----
	// wsWatchdogDead 静默判死阈值 35s（Java:46 HEARTBEAT_WATCHDOG_MS=35000L：
	// 半开连接只有靠"多久没收到任何帧"才能判死；服务端 30s 一次 ping，35s 留余量，
	// 最坏 35~45s 发现并切回 HTTP 兜底）。
	wsWatchdogDead = 35 * time.Second
	// wsWatchdogCheckEvery 看门狗检查粒度 10s（Java:109 scheduleAtFixedRate(…, 10, 10, SECONDS)）。
	wsWatchdogCheckEvery = 10 * time.Second
	// wsClientPingEvery 本端主动空 ping 帧间隔 15s（Java:49 CLIENT_PING_INTERVAL_SEC=15L，
	// :118-136 clientPing()——原生 WebSocket ping 控制帧，不是文本帧；失败即判死触发重连）。
	wsClientPingEvery = 15 * time.Second
	// wsConnectTimeout 单次连接尝试超时 3s（Java:51 CONNECT_TIMEOUT_MS=3000L）。
	wsConnectTimeout = 3 * time.Second
)

// wsBackoffSteps 连接不可用退避三档 10s/30s/60s（Java:38 BACKOFF_STEPS={10000,30000,60000}，
// markUnavailable :333-338：退避窗口内 canUse()=false，调用方逐请求回落 HTTP）。
var wsBackoffSteps = []time.Duration{10 * time.Second, 30 * time.Second, 60 * time.Second}

// session 单个房间会话：心跳循环 + 信令循环，直到 stop 或致命错误。
type session struct {
	app    *App
	code   string
	token  string
	isHost bool

	done     chan struct{} // 会话结束（stop 或 fatal）
	doneOnce sync.Once
	stopCh   chan struct{} // 主动停止（LeaveRoom）
	stopOnce sync.Once
	fatalMu  sync.Mutex
	fatalled bool

	since atomic.Int64 // 信令游标：收到的最大 timestamp（ms）

	// WS 复用为通用帧通道：wsLoop 持有连接，心跳/轮询经 wsSend 帧化发送，
	// 读 goroutine 按 ID 回填 pending（推送帧 id=0 走 handleSignals）。
	wsMu      sync.Mutex
	wsConn    *websocket.Conn
	wsPending map[int64]chan *wsFrame

	// ---- 传输硬化状态（SignalingWsTransport.java 对应字段） ----
	// wsLastFrameAt 最近一次"收到任何帧"的时刻（Java:86 lastFrameAt；
	// onText :522 与 pong 到达即 Ping() 成功都刷新它——对应 Java onPing/onPong H10 注释 :533-544）。
	wsLastFrameAt atomic.Int64
	// wsNextRetryAt 退避窗口：早于此刻禁止再次拨号/用 WS（Java:84 nextRetryAt，canUse() :185-187）。
	wsNextRetryAt atomic.Int64
	// wsBackoffStep 退避档位游标，拨号成功清零（Java:85 backoffStep，onConnected :346）。
	wsBackoffStep atomic.Int64
	pollFails     int       // HTTP 轮询连续失败次数（退避与"已恢复"日志口径）
	wsSafetyOnce  sync.Once // WS 安全网补拉只起一条 goroutine（会话级）
	// wsDead 当前连接的判死信号（看门狗/主动 ping 失败/写失败关闭它 → wsLoop 立即返回触发重连）。
	wsDead chan struct{}
	// wsKill 关闭 wsDead（幂等，每连接一个 sync.Once）。
	wsKill func()
}

func newSession(app *App, code, token string, isHost bool) *session {
	return &session{
		app:    app,
		code:   code,
		token:  token,
		isHost: isHost,
		done:   make(chan struct{}),
		stopCh: make(chan struct{}),
	}
}

func (s *session) isDone() bool {
	select {
	case <-s.done:
		return true
	default:
		return false
	}
}

// stop 主动停止会话（LeaveRoom），不触发 closed 事件。
// done 必须一并关闭：心跳循环只认 done，若不关它 LeaveRoom 后旧循环会带着旧 token
// 每 5s 继续心跳，服务端就一直算本端在房内，且 run() 的 clearSession/unbindSession 永不执行。
func (s *session) stop() {
	s.stopOnce.Do(func() { close(s.stopCh) })
	s.closeDone()
}

func (s *session) closeDone() {
	s.doneOnce.Do(func() { close(s.done) })
}

// fatal 标记会话致命结束，广播 closed 事件（只执行一次）。
func (s *session) fatal(msg string) {
	s.fatalMu.Lock()
	defer s.fatalMu.Unlock()
	if s.fatalled {
		return
	}
	s.fatalled = true
	s.app.netLog("error", "会话结束: "+msg)
	s.app.emit("session:state", map[string]any{
		"state":   "closed",
		"message": msg,
		"room":    s.app.snapshotRoom(),
	})
	s.closeDone()
}

// run 并发运行心跳与信令循环，返回时清理 App 状态与连接引擎。
func (s *session) run() {
	defer s.app.clearSession(s)
	defer s.app.engine.unbindSession(s)
	var wg sync.WaitGroup
	wg.Add(2)
	go func() {
		defer wg.Done()
		s.heartbeatLoop()
	}()
	go func() {
		defer wg.Done()
		s.signalLoop()
	}()
	wg.Wait()
}

// isFatalAuthError 判断是否为会话必须终止的致命错误码。
func isFatalAuthError(code string) bool {
	switch code {
	// ROOM_NOT_FOUND 必须在列：模组的心跳/轮询都把"服务端说房不存在"当作房已消失
	// （RoomManager.java:1508 与 :1779-1786 → handleRoomLost）。缺这一条时房间被回收后
	// 本端只会拆 WS 重拨、每 ~5s 一次 TLS 握手空转，界面还显示"运行中"，玩家拿不到失败提示。
	case "ROOM_EXPIRED", "ROOM_CLOSED", "ROOM_EVICTED", "INVALID_TOKEN", "ROOM_NOT_FOUND":
		return true
	}
	return false
}

// ---- 心跳循环 ----

// 心跳的容错口径（重要，勿改回"失败即致命"）：
// 只有服务端明确判定房间失效（410 / ROOM_CLOSED / ROOM_EXPIRED / ROOM_EVICTED /
// INVALID_TOKEN）才结束会话。网络抖动、超时、5xx 一律退避重试——
// 房主侧一次"会话自杀"就等于整间房：心跳停 → 服务端 room_ttl(300s) 判房主失联 →
// 房间从列表消失且拒绝新加入，对房客表现为"房主关闭了房间"。
// 打洞/中继期间的瞬时网络失败尤其常见（自建 UDP 洪水会挤占 HTTP），
// 因此这里必须扛住，而不是 25s 就放弃整间房。
const heartbeatMaxBackoff = 30 * time.Second

func heartbeatBackoff(interval time.Duration, fails int) time.Duration {
	wait := interval
	for i := 0; i < fails && wait < heartbeatMaxBackoff; i++ {
		wait *= 2
	}
	if wait > heartbeatMaxBackoff {
		wait = heartbeatMaxBackoff
	}
	return wait
}

func (s *session) heartbeatLoop() {
	interval := defaultHeartbeatInterval
	seq := int64(0)
	fails := 0

	for {
		if s.isDone() {
			return
		}

		seq++
		body := s.heartbeatBody(seq)
		var hb struct {
			HeartbeatInterval int    `json:"heartbeatInterval"`
			CurrentPlayers    int    `json:"currentPlayers"`
			Name              string `json:"name"`
			// 心跳响应也会携带审核结果（RoomManager.java:1594-1607）：信令丢包时这是唯一兜底。
			NameModerationStatus *string `json:"nameModerationStatus"`
			NameModerationReason string  `json:"nameModerationReason"`
			NameApproved         *bool   `json:"nameApproved"`
		}
		// 心跳优先走 WS 帧（省去每 5s 一次的 HTTP 请求）；WS 未连接/失败回退 HTTP
		var err error
		if f, ok := s.wsSend("/room/heartbeat", body); ok {
			if !f.Success {
				err = &APIError{Code: f.Error, Message: f.Message, Status: 400}
			} else if len(f.Data) > 0 {
				_ = json.Unmarshal(f.Data, &hb)
			}
		} else {
			err = s.app.api.post(s.app.baseURL(), "/room/heartbeat", body, &hb)
		}
		wait := interval
		if err != nil {
			var ae *APIError
			if errors.As(err, &ae) {
				if ae.Status == http.StatusGone || isFatalAuthError(ae.Code) {
					msg := ae.Message
					if msg == "" {
						msg = "房间已失效"
					}
					s.fatal(msg)
					return
				}
			}
			fails++
			if fails == 1 || fails%maxHeartbeatFailures == 0 {
				s.app.netLog("warn", fmt.Sprintf("心跳失败(%d 次)，继续重试: %v", fails, err))
			}
			wait = heartbeatBackoff(interval, fails)
		} else {
			if fails >= maxHeartbeatFailures {
				s.app.netLog("info", fmt.Sprintf("心跳已恢复（中断 %d 次）", fails))
			}
			fails = 0
			s.app.updateRoomInfo(s, hb.CurrentPlayers, heartbeatDisplayName(hb.Name))
			if hb.NameModerationStatus != nil {
				// :1603 approved 直接取心跳里的 nameApproved，不靠状态字符串推断；
				// :1597 服务端恒带 name，所以这里传指针而不是 nil。
				approved := hb.NameApproved != nil && *hb.NameApproved
				s.app.handleNameModerationUpdate(*hb.NameModerationStatus, hb.NameModerationReason, &hb.Name, approved)
			}
			if hb.HeartbeatInterval > 0 {
				interval = time.Duration(hb.HeartbeatInterval) * time.Second
			}
		}

		select {
		case <-s.done:
			return
		case <-time.After(wait):
		}
	}
}

// ---- 信令循环 ----

// signalLoop WS 优先；失败回退 HTTP 轮询。重连时刻按退避窗口决定：
// 对齐 SignalingWsTransport.java runReconnect :402-409（失败 → markUnavailable →
// 延迟 max(1000ms, nextRetryAt-now) 再试；断开事件则立即重连 delay=0），
// 等待窗口内以 HTTP 逐请求兜底（等价 Java canUse()=false 时 SignalingClient 直落 HTTP）。
func (s *session) signalLoop() {
	s.since.Store(time.Now().UnixMilli() - 30_000)
	s.startWSSafetyNet()

	for {
		if s.isDone() {
			return
		}
		if err := s.wsLoop(); err != nil && !s.isDone() {
			s.app.netLog("warn", "WebSocket 断开，回退 HTTP 轮询: "+err.Error())
		}
		if s.isDone() {
			return
		}
		if wait := s.wsNextRetryDelay(); wait > 0 {
			if s.httpPollLoop(wait) {
				return // 会话已结束
			}
		}
		if s.isDone() {
			return
		}
		s.app.netLog("info", "ws_reconnect_attempt")
	}
}

// wsSafetyNetEvery 模组 WS 健康时的补拉周期：RoomManager.java:1829-1841 把轮询间隔在 WS
// 健康时放宽到 5s，:1700-1710 再"睡 5 拍、第 6 拍补一次"，合起来 30s 一次 HTTP 兜底。
var wsSafetyNetEvery = 30 * time.Second

// startWSSafetyNet WS 健康时的安全网补拉。推送是实时通道，但"连接看着活着、帧不再到达"
// 的半开黑洞只能靠这一条兜住——应用端此前 WS 一在线就完全不跑 HTTP 轮询，
// 推送一漏就永久卡住（隔离装置里复现的正是这个形态）。
func (s *session) startWSSafetyNet() {
	s.wsSafetyOnce.Do(func() {
		go func() {
			t := time.NewTicker(wsSafetyNetEvery)
			defer t.Stop()
			for {
				select {
				case <-s.done:
					return
				case <-s.stopCh:
					return
				case <-t.C:
					if !s.wsCanUse() {
						continue // WS 不在时由 httpPollLoop 兜底，这里不重复打服务端
					}
					if done, _ := s.httpPollOnce(); done {
						return
					}
				}
			}
		}()
	})
}

// wsNextRetryDelay 距退避窗口打开还需等待的时间；0 表示可立即重连
// （Java:379 onDisconnected → scheduleReconnect(0)；:407 失败续试 max(1000ms, 剩余)）。
func (s *session) wsNextRetryDelay() time.Duration {
	next := s.wsNextRetryAt.Load()
	if next == 0 {
		return 0
	}
	remaining := next - time.Now().UnixMilli()
	if remaining <= 0 {
		return 0
	}
	delay := time.Duration(remaining) * time.Millisecond
	if delay < time.Second {
		delay = time.Second
	}
	return delay
}

// wsCanUse 是否可尝试使用 WS：不在退避冷却窗口内（Java:184-187 canUse()）。
func (s *session) wsCanUse() bool {
	return time.Now().UnixMilli() >= s.wsNextRetryAt.Load()
}

// wsMarkUnavailable 标记连接不可用并进入下一档退避（Java:333-338 markUnavailable()：
// step 递增、delay=BACKOFF_STEPS[min(step,2)]、nextRetryAt=now+delay；连接成功后清零
// onConnected :340-347）。reason 为日志标识（i18n-neutral 键，不承载用户文案）。
func (s *session) wsMarkUnavailable(reason string) {
	step := s.wsBackoffStep.Add(1) - 1
	idx := int(step)
	if idx >= len(wsBackoffSteps) {
		idx = len(wsBackoffSteps) - 1
	}
	delay := wsBackoffSteps[idx]
	s.wsNextRetryAt.Store(time.Now().UnixMilli() + delay.Milliseconds())
	s.app.netLog("warn", fmt.Sprintf("ws_backoff step=%d delay_ms=%d reason=%s", idx+1, delay.Milliseconds(), reason))
}

// wsKillNow 判定当前连接死亡：关闭 wsDead，让 wsLoop 与全部在途 wsSend 立即返回
// （Java:364-380 onDisconnected——在途请求以网络错误完成、由上层降级 HTTP，
// 随后主动重连）。
func (s *session) wsKillNow() {
	s.wsMu.Lock()
	kill := s.wsKill
	s.wsMu.Unlock()
	if kill != nil {
		kill()
	}
}

// httpPollLoop 每 1s 轮询 /signal/poll，最多运行 maxWait；返回 true 表示会话结束。
// 与心跳同口径：网络类失败只退避重试（signalLoop 会在 maxWait 后改试 WS），
// 唯有服务端判定房间失效才致命——否则房主的信令循环一死，整间房就跟着没了。
func (s *session) httpPollLoop(maxWait time.Duration) bool {
	timer := time.NewTimer(maxWait)
	defer timer.Stop()
	interval := httpPollInterval

	for {
		select {
		case <-s.done:
			return true
		case <-s.stopCh:
			return true
		case <-timer.C:
			return false
		case <-time.After(interval):
		}

		done, failed := s.httpPollOnce()
		if done {
			return true
		}
		if failed {
			// 连续失败逐步退避（1s→2s→4s…上限 10s），避免网络全断时空转打服务端
			interval = httpPollInterval << min(s.pollFails-1, 3)
			if interval > 10*time.Second {
				interval = 10 * time.Second
			}
			continue
		}
		interval = httpPollInterval
	}
}

// httpPollOnce 一次 HTTP /signal/poll。done=会话应结束（410/致命鉴权），failed=这次网络失败
// （由调用方决定退避）。从 httpPollLoop 里抽出来，是为了让"WS 健康时的安全网补拉"
// 复用同一条口径（见 startWSSafetyNet）。
func (s *session) httpPollOnce() (done bool, failed bool) {
	body := map[string]any{
		"code":   s.code,
		"token":  s.token,
		"isHost": s.isHost,
		"since":  s.since.Load(),
	}
	var sd signalsData
	err := s.app.api.post(s.app.baseURL(), "/signal/poll", body, &sd)
	if err != nil {
		var ae *APIError
		if errors.As(err, &ae) {
			if ae.Status == http.StatusGone || isFatalAuthError(ae.Code) {
				msg := ae.Message
				if msg == "" {
					msg = "房间已失效"
				}
				s.fatal(msg)
				return true, false
			}
		}
		s.pollFails++
		if s.pollFails == 1 || s.pollFails%maxPollFailures == 0 {
			s.app.netLog("warn", fmt.Sprintf("信令轮询失败(%d 次)，继续重试: %v", s.pollFails, err))
		}
		return false, true
	}
	if s.pollFails >= maxPollFailures {
		s.app.netLog("info", fmt.Sprintf("信令轮询已恢复（中断 %d 次）", s.pollFails))
	}
	s.pollFails = 0
	s.handleSignals(sd)
	return false, false
}

// heartbeatBody 与模组 heartbeatTask 同一组字段（RoomManager.java:1480-1497 发
// natType / load / peerLatency / overlayPort / mcPlayerCount）；服务端按这四个键写拓扑节点
// （server-go/roommanager.go:1181-1186：nat_type/load/overlay_port/peers）。
// 本端此前只发 code/token/isHost/seq/currentInterval/mcPlayerCount —— 少这四个键，
// 应用端房间在服务端拓扑视图里永远是不可用节点（nat_type 空、overlay_port 0）。
func (s *session) heartbeatBody(seq int64) map[string]any {
	room := s.app.snapshotRoom()
	return map[string]any{
		"code":            s.code,
		"token":           s.token,
		"isHost":          s.isHost,
		"seq":             seq,
		"currentInterval": 5,
		"mcPlayerCount":   0,
		"natType":         roomStr(room, "natType"),
		"load":            0.1,
		"peerLatency":     topologyPeerLatency(),
		"overlayPort":     topologyOverlayPort(),
	}
}

// handleSignals 分发信令并推进 since 游标（同时交给连接引擎处理）。
func (s *session) handleSignals(sd signalsData) {
	maxTs := s.since.Load()
	for _, sig := range sd.S {
		if sig.Timestamp > maxTs {
			maxTs = sig.Timestamp
		}
		data := sig.Data
		if data == nil {
			data = map[string]any{}
		}
		s.app.emit("signal", map[string]any{
			"type":      sig.Type,
			"from":      sig.From,
			"data":      data,
			"timestamp": sig.Timestamp,
		})
		// 连接引擎按 type 分发（只处理引擎关心的类型）
		s.app.engine.onSignal(sig.Type, sig.From, data)
		// 拓扑信令：模组 RoomManager 在同一个分发点把 topology_* 两型交给 TopologyClient
		topologyHandleSignal(sig.Type, data)
		// 房主名审核三型（RoomManager.java:1967-1985 的同一分发点）：房间级信令，不进连接引擎
		s.app.handleRoomNameSignal(sig.Type, data)
		// 模组同步：mods_request 要现算/回传清单（可能几十秒），必须离开信令分发线程
		// ——与模组把 bind/allocate 丢给 TURN_BG_EXECUTOR 是同一条教训（审计 P1-2）。
		s.app.modSyncHandleSignalAsync(sig.Type, sig.From, data)
	}
	if sd.TS > maxTs {
		maxTs = sd.TS
	}
	s.since.Store(maxTs)
}

// ---- WebSocket 通道 ----

// wsFrameSeq WS 请求帧号发号器（全进程单调递增，见 wsSend）。
var wsFrameSeq atomic.Int64

type wsRequest struct {
	ID     int64  `json:"id"`
	Route  string `json:"route"`
	Method string `json:"method"`
	Body   any    `json:"body"`
}

// wsFrame 服务器帧：响应帧带 id/success/data；主动推送帧 id=0 且带 push 字段
// （Java handleFrame :418-439——无 id 字段的帧按 -1 处理、不入推送也不匹配 pending，
// 故 ID 用指针区分"缺省"与"0"）。
type wsFrame struct {
	ID      *int64          `json:"id"`
	Push    string          `json:"push"`
	Success bool            `json:"success"`
	Error   string          `json:"error"`
	Message string          `json:"message"`
	Data    json.RawMessage `json:"data"`
}

type signalsData struct {
	S  []signalMsg `json:"s"`
	TS int64       `json:"ts"`
}

type signalMsg struct {
	ID        json.RawMessage `json:"id"`
	From      string          `json:"from"`
	Type      string          `json:"type"`
	Data      map[string]any  `json:"data"`
	Timestamp int64           `json:"timestamp"`
	To        string          `json:"to"`
}

// wsURLFrom 把 http(s) 基址转为 ws(s) 的 /ws 地址。
func wsURLFrom(baseURL string) string {
	u := strings.TrimRight(baseURL, "/")
	if strings.HasPrefix(u, "https://") {
		u = "wss://" + strings.TrimPrefix(u, "https://")
	} else if strings.HasPrefix(u, "http://") {
		u = "ws://" + strings.TrimPrefix(u, "http://")
	}
	return u + "/ws"
}

// wsSend 在 WS 已连接且不在退避窗口时发送一个帧请求并等待响应；
// 不可用/失败返回 ok=false（调用方回退 HTTP 逐请求兜底，对齐 Java canUse 语义）。
func (s *session) wsSend(route string, body any) (*wsFrame, bool) {
	if !s.wsCanUse() {
		return nil, false
	}
	s.wsMu.Lock()
	conn := s.wsConn
	dead := s.wsDead
	if conn == nil || s.wsPending == nil || dead == nil {
		s.wsMu.Unlock()
		return nil, false
	}
	// 帧号取全进程自增：UnixNano 在 Windows 上只有 ~1ms 粒度，心跳与 poll 并发时
	// 两个请求会撞进同一个 pending chan（先注册者拿不到回填，只能等超时回落 HTTP）。
	// 0 保留给服务端推送帧，负数保留给"无 id"，所以从 1 起自增且永不复用。
	id := wsFrameSeq.Add(1)
	ch := make(chan *wsFrame, 1)
	s.wsPending[id] = ch
	s.wsMu.Unlock()

	dropPending := func() {
		s.wsMu.Lock()
		if s.wsPending != nil {
			delete(s.wsPending, id)
		}
		s.wsMu.Unlock()
	}

	payload, err := json.Marshal(wsRequest{ID: id, Route: route, Method: "POST", Body: body})
	if err != nil {
		dropPending()
		return nil, false
	}
	ctx, cancel := context.WithTimeout(context.Background(), wsResponseTimeout)
	defer cancel()
	if err := conn.Write(ctx, websocket.MessageText, payload); err != nil {
		// 发送失败 → 判死并立即触发重连（Java sendFrame :273-280 写失败完成异常 +
		// pendingFut.whenComplete :245-255 markUnavailable；onDisconnected :379 重连 delay=0）。
		dropPending()
		s.wsMarkUnavailable("send")
		s.wsKillNow()
		return nil, false
	}
	select {
	case f := <-ch:
		return f, true
	case <-time.After(wsResponseTimeout):
		// 请求超时 → markUnavailable：避免后续请求继续对着半开连接逐个等超时
		// （Java:245-255 注释语义）。
		dropPending()
		s.wsMarkUnavailable("timeout")
		return nil, false
	case <-dead:
		// 连接判死：在途请求即刻失败（Java:364-380），本端以 reason 归入退避。
		dropPending()
		s.wsMarkUnavailable("disconnected")
		return nil, false
	case <-s.done:
		dropPending()
		return nil, false
	case <-s.stopCh:
		dropPending()
		return nil, false
	}
}

// wsLoop 维持一次 WebSocket 连接：推送帧实时分发，帧轮询降为 5s 安全网，
// 同一连接同时承载心跳等带 ID 的帧请求（wsSend）。
// 传输硬化整体移植 network/SignalingWsTransport.java：
//   - 半开判死 35s + 检查粒度 10s（:46 HEARTBEAT_WATCHDOG_MS、:109 scheduleAtFixedRate 10s、
//     :450-465 heartbeatWatchdog）
//   - 每 15s 主动空 ping 控制帧，失败即判死触发重连（:49、:118-136 clientPing）
//   - 拨号成功清零退避（:340-347 onConnected）、拨号/请求失败进 10s/30s/60s 退避
//     （:38、:333-338 markUnavailable，窗口内 canUse=false 调用方逐请求回落 HTTP）
//   - 握手必带 X-VoxLink-Version 头（:302-307，1.2.0 互通实测修复）
//
// 返回错误表示连接失效，由上层回退 HTTP 轮询并按退避窗口重连。
func (s *session) wsLoop() error {
	wsURL := wsURLFrom(s.app.baseURL())

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { // 会话结束时中断读写
		select {
		case <-s.done:
			cancel()
		case <-s.stopCh:
			cancel()
		case <-ctx.Done():
		}
	}()

	dialCtx, dialCancel := context.WithTimeout(ctx, wsConnectTimeout)
	hdr := http.Header{}
	hdr.Set("User-Agent", httpUserAgent)
	// 版本头必须随 WS 握手带上（Java:303-306 注释原文要义）：服务端把 WS 帧按握手
	// 请求头克隆后内部分发，/room/list 的 HL 展示闸与 /room/join 的 allowHostPeers
	// 都读这个头；缺了即按旧端处理，HL 房在 WS 模式下永远不可见（fed 互通功能依赖）。
	hdr.Set("X-VoxLink-Version", appVersion)
	conn, _, err := websocket.Dial(dialCtx, wsURL, &websocket.DialOptions{HTTPHeader: hdr})
	dialCancel()
	if err != nil {
		// 连接失败 → markUnavailable 进入退避，上层降级 HTTP
		// （Java request():211-215 与 runReconnect():402-409 同口径）。
		s.wsMarkUnavailable("dial")
		return fmt.Errorf("连接失败: %w", err)
	}
	conn.SetReadLimit(1 << 20)
	defer conn.Close(websocket.StatusNormalClosure, "")

	// 每连接独立判死信号（wsLoop 串行运行，绑定/摘除无需再判并发连接）。
	dead := make(chan struct{})
	var killOnce sync.Once
	kill := func() { killOnce.Do(func() { close(dead) }) }

	s.wsMu.Lock()
	s.wsConn = conn
	s.wsPending = make(map[int64]chan *wsFrame)
	s.wsDead = dead
	s.wsKill = kill
	s.wsMu.Unlock()
	// onConnected（Java:340-347）：刷新收帧锚点、重置退避两态。
	s.wsLastFrameAt.Store(time.Now().UnixMilli())
	s.wsBackoffStep.Store(0)
	s.wsNextRetryAt.Store(0)
	s.app.netLog("info", "WebSocket 已连接")

	defer func() { // 断开后先广播判死（在途 wsSend 立即失败回落 HTTP，Java:364-380），再摘除连接
		kill()
		s.wsMu.Lock()
		if s.wsConn == conn {
			s.wsConn = nil
			s.wsPending = nil
			s.wsDead = nil
			s.wsKill = nil
		}
		s.wsMu.Unlock()
	}()

	// 读 goroutine：推送帧直接分发，响应帧按 ID 回填 pending。
	// 任何收到的数据帧都刷新看门狗锚点（Java onText :521-522 lastFrameAt）。
	go func() {
		for {
			_, data, err := conn.Read(ctx)
			if err != nil {
				kill() // 读失败=连接死亡（Java onError/onClose :546-556 → onDisconnected）
				return
			}
			s.wsLastFrameAt.Store(time.Now().UnixMilli())
			var f wsFrame
			if json.Unmarshal(data, &f) != nil {
				continue
			}
			id := int64(-1)
			if f.ID != nil {
				id = *f.ID
			}
			// 服务端主动推送帧：id==0 且带 push 字段（Java:418-439）。
			// 必须先确认这帧的 data 里真的带 s 数组再交给信令管线——模组的门是
			// RoomManager.java:1791-1796 `if (!data.get(sigKey).isJsonArray()) return;`：
			// lobby 推送的 data 只有 {"ts":服务端此刻}，缺了这道门就会把 since 顶到未来，
			// 于是"比 lobby 推送更早写入"的信令被永久跳过（现网表现：join_request/punch_info 收不到）。
			if id == 0 && f.Push != "" {
				var probe struct {
					S *[]signalMsg `json:"s"`
				}
				if json.Unmarshal(f.Data, &probe) == nil && probe.S != nil {
					var sd signalsData
					if json.Unmarshal(f.Data, &sd) == nil {
						s.handleSignals(sd)
					}
				}
				continue // lobby 推送：本 App 大厅列表走轮询，无对应消费者，等价忽略
			}
			if id < 0 {
				continue // 无 id 帧：Java 按 -1 查 pending 不命中后丢弃（:418、:440-444）
			}
			s.wsMu.Lock()
			ch, ok := s.wsPending[id]
			if ok {
				delete(s.wsPending, id)
			}
			s.wsMu.Unlock()
			if ok {
				select {
				case ch <- &f:
				default:
				}
			}
		}
	}()

	// 主动心跳 ping（Java:118-136 clientPing）：发送原生 WebSocket ping 控制帧——
	// coder/websocket 的 Conn.Ping 走 writeControl(opPing)（conn.go:223→c.ping→
	// writeControl），是协议层控制帧而非文本帧；半开连接下数据写仍会"成功"，
	// 只有主动 ping 收不到 pong 才能暴露。失败即判死、触发重连。
	// pong 到达计入看门狗锚点（H10，Java:533-544：协议层 ping/pong 也要计入心跳，
	// 否则仅有 keepalive 的健康连接会被误杀；库内部自动应答服务端 ping，
	// 调用侧以 Ping() 成功为"收到 pong"的等价信号）。
	go func() {
		t := time.NewTicker(wsClientPingEvery)
		defer t.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-dead:
				return
			case <-t.C:
				pctx, pcancel := context.WithTimeout(ctx, wsResponseTimeout)
				pingErr := conn.Ping(pctx)
				pcancel()
				if pingErr != nil {
					s.app.netLog("warn", "ws_ping_failed err="+pingErr.Error())
					kill()
					return
				}
				s.wsLastFrameAt.Store(time.Now().UnixMilli())
			}
		}
	}()

	// 心跳看门狗（Java:450-465）：每 10s 检查一次，静默超 35s 判死。半开连接
	// （对端进程消失/NAT 映射失效但没发 FIN）下 socket 仍"可写"，只有"多久没收到
	// 任何帧"可靠；最坏 35~45s 内发现并切回 HTTP 兜底。
	go func() {
		t := time.NewTicker(wsWatchdogCheckEvery)
		defer t.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-dead:
				return
			case <-t.C:
				if time.Now().UnixMilli()-s.wsLastFrameAt.Load() > wsWatchdogDead.Milliseconds() {
					s.app.netLog("warn", "ws_watchdog_dead")
					kill()
					return
				}
			}
		}
	}()

	ticker := time.NewTicker(wsPollInterval)
	defer ticker.Stop()

	for {
		select {
		case <-s.done:
			return errors.New("会话已结束")
		case <-s.stopCh:
			return errors.New("会话已停止")
		case <-dead:
			// 看门狗/主动 ping/读错误任一判死 → 立即返回，上层按退避窗口重连
			// （Java onDisconnected → scheduleReconnect(0)：主动立即重连）。
			return errors.New("ws_dead")
		case <-ticker.C:
		}

		reqBody := map[string]any{
			"code":   s.code,
			"token":  s.token,
			"isHost": s.isHost,
			"since":  s.since.Load(),
		}
		f, ok := s.wsSend("/signal/poll", reqBody)
		if !ok {
			return errors.New("发送失败")
		}
		if !f.Success {
			code := f.Error
			if isFatalAuthError(code) {
				msg := f.Message
				if msg == "" {
					msg = "房间已失效"
				}
				s.fatal(msg)
				return errors.New("会话已结束")
			}
			return fmt.Errorf("服务端错误: %s %s", code, f.Message)
		}
		// 轮询响应与推送同构：复用 handleSignals 推进 since 游标（幂等）
		var sd signalsData
		if json.Unmarshal(f.Data, &sd) == nil {
			s.handleSignals(sd)
		}
	}
}
