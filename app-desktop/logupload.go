package main

// logupload.go — 打洞样本日志上传，逐段移植模组 network/LogUploadManager.java（1.2.0，624 行）。
//
// 覆盖：arm / 终局失败 5s 快传 / 2026-10-05 规则（断链与链路判死无条件上传，稳定窗口不再拦截上传，
// 但"已连上且已过稳定窗口"的成功会话仍不上传）/ 关程序兜底补传 / 房主侧"对手已上传"轮询 / 上传单飞锁。
//
// 只做 Java→Go 的形态转换：
//   - Executors.newSingleThreadScheduledExecutor(:44-48) → upnp.go 的 seqScheduler
//     （同一套"单线程串行 + cancel(false) 不打断在跑任务"语义）
//   - HttpClient.sendAsync + orTimeout(90s)(:375-376) → goroutine 内的请求 + context 超时
//   - Runtime.addShutdownHook(:73) → 由退出路径显式调 logUploadOnExit()（hunk 见交付报告）
//   - Minecraft.getInstance().gameDirectory/logs/latest.log(:505) → 见 logUploadLogPath
//   - mc.player.sendSystemMessage(:294-299) → 前端事件 "log:uploaded"（本端没有游戏聊天框）
//
// 与模组的三处有意差异（报告列明，不隐藏）：
//  1. 日志：模组打 code（6 位房码，可推断房间）与服务器回显消息；本端网络日志出口有全局消敏
//     规则（netlog_sanitize_test.go），一律换成 i18n-neutral 键 + role=/bytes=/attempts= 形式。
//  2. UA：模组是 "Mozilla/5.0 (Java) VoxLink/<ver>"（:455/:539/:557）；本端用 app 侧统一 UA
//     （api.go:19 httpUserAgent），版本仍由 X-VoxLink-Version 承载。
//  3. 玩家名：模组取 Minecraft 用户档案（:444-447/:552）；本 App 无账号体系，取登录用户名，
//     取不到则空串（服务端只做 32 字符截断展示，server-go/loguploader.go:256）。

import (
	"bytes"
	"compress/gzip"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"os/user"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// ---- 常量（LogUploadManager.java:29-42）----

const (
	logUploadDelay         = 90 * time.Second        // :29 UPLOAD_DELAY_MS
	logStableWindow        = 120 * time.Second       // :30 STABLE_WINDOW_MS
	logActivityTimeout     = 15 * time.Second        // :31 ACTIVITY_TIMEOUT_MS
	logHostWaitTimeout     = 5 * time.Minute         // :32 HOST_WAIT_TIMEOUT_MS
	logFailureDelay        = 5 * time.Second         // :33-34 FAILURE_UPLOAD_DELAY_MS（注释原文"不再硬等 90s"）
	logMinAgeForQuit       = 30 * time.Second        // :35-36 MIN_AGE_FOR_QUIT_UPLOAD_MS（"秒进秒出不算"）
	logShutdownTimeout     = 6 * time.Second         // :37 SHUTDOWN_UPLOAD_TIMEOUT_MS
	logShutdownConnect     = 4 * time.Second         // :467 Math.min(SHUTDOWN_UPLOAD_TIMEOUT_MS, 4000)
	logMaxBytes            = 4 * 1024 * 1024         // :38 MAX_LOG_BYTES
	logMaxAttempts         = 3                       // :39 MAX_ATTEMPTS
	logRetryDelay          = 15 * time.Second        // :40 RETRY_DELAY_MS
	logRouteStatus         = "/log/status"           // :41
	logRouteUpload         = "/log/upload"           // :42
	logPollInterval        = 5 * time.Second         // :114/:136/:238 轮询间隔
	logUploadOrTimeout     = 90 * time.Second        // :376 orTimeout(90s)
	logRequestTimeout      = 20 * time.Second        // :541/:565 取 client 的 connectTimeout=VoxLinkConfig:62 默认 20000ms
	logActivityThrottle    = 1000 * time.Millisecond // :151 onTransportActivity 内部节流 1s
	logPostponeInterval    = 15 * time.Second        // :344 稳定窗口内的顺延复查
	logFlushOnDisarmDelay  = 200 * time.Millisecond  // :268 disarm 补传延时
	logImmediateDelay      = 1 * time.Millisecond    // :164/:179/:249 "立刻上传"的实际延时
	logRateLimitedRetrySec = 15                      // :578 429 时模组写死的 retryAfter
)

// ---- 状态（模组为 static 字段 :50-66）----

var (
	logUploadMu      sync.Mutex // 护住下面这批 volatile 字段（模组逐字段 volatile，这里合并一把锁）
	logActiveCodePtr *string    // :50 ACTIVE_CODE（AtomicReference；nil 等价 null）
	logUploaded      atomic.Bool
	logInFlight      atomic.Bool // :53 上传单飞锁（CAS）
	logFailureArmed  bool        // :55
	logUploadFuture  int64       // :56 seqScheduler 任务 id，0=null
	logPollFuture    int64       // :57
	logOnUploaded    func()      // :58
	logActiveCode    string      // :59 activeCode（"" 等价 null）
	logActiveIsHost  bool        // :60
	logPunchStartMs  int64       // :61
	logConnectedMs   int64       // :62
	logLastActiveMs  int64       // :63
	logLastReportMs  int64       // :64
	logRole          string      // :65
	logHTTPClient    *http.Client
	logUploadSched   *seqScheduler
	logSchedOnce     sync.Once
)

// logScheduler 上传调度器懒建（模组 :44-48 是静态单线程 daemon 调度器）。
func logScheduler() *seqScheduler {
	logSchedOnce.Do(func() { logUploadSched = newSeqScheduler() })
	return logUploadSched
}

// logUploadEnabled 内存开关，默认开启（LogUploadState.java:8；
// VoxLinkMod.java:130-132 在 onInitialize 用持久化配置初始化它）。
var logUploadEnabled atomic.Bool

func init() { logUploadEnabled.Store(true) }

// ---- 生命周期入口 ----

// logUploadArm 会话开始登记（arm(code, isHost)，:80-116）。
// 房主调用点 RoomManager.java:377（房间信息落地之后）；房客调用点 AttemptingJoinScreen.java:258
// （startJoin，即发出 join 之前）。
func (a *App) logUploadArm(code string, isHost bool) {
	normalized := strings.TrimSpace(code) // :82 code==null ? "" : code.trim()
	if normalized == "" {                 // :83-85
		return
	}

	logUploadMu.Lock()
	// :86-89 已有登记且有排期 → 先取消旧定时器，否则"上一场的 90s 定时器"会误传这一场之前的日志
	if logActiveCodePtr != nil && logUploadFuture != 0 {
		logScheduler().cancel(logUploadFuture)
		logUploadFuture = 0
	}
	if logPollFuture != 0 { // :90-93
		logScheduler().cancel(logPollFuture)
		logPollFuture = 0
	}
	logActiveCodePtr = &normalized // :94-95
	logActiveCode = normalized
	logActiveIsHost = isHost                 // :96
	logPunchStartMs = time.Now().UnixMilli() // :97
	if isHost {                              // :98
		logRole = "host"
	} else {
		logRole = "joiner"
	}
	logUploaded.Store(false) // :99
	logFailureArmed = false  // :100
	logConnectedMs = 0       // :101
	logLastActiveMs = 0      // :102
	role := logRole

	// :105-107 覆盖排期前先取消（模组注释：避免双 runUpload 并发）
	if logUploadFuture != 0 {
		logScheduler().cancel(logUploadFuture)
		logUploadFuture = 0
	}
	// :108-115 注释原文：两端各自计时 2 分钟；host 从建房改到真正开打（punch_info）才起算，
	// 否则 host 建房间比 joiner 进房早、上传永远早于 joiner；host 额外保留"对手已上传立即传"快路径。
	if !logActiveIsHost {
		logUploadFuture = logScheduler().scheduleRepeat(logUploadDelay, 0, func() { a.logUploadRunUpload() })
	}
	if logActiveIsHost {
		logPollFuture = logScheduler().scheduleRepeat(logPollInterval, 0, func() { a.logUploadPollOpponentUploaded() })
	}
	logUploadMu.Unlock()

	logUploadLog("info", "log_upload_armed role="+role) // :103
	a.logUploadReportStatus()                           // :104
}

// logUploadSchedulePunchUpload 房主侧把 90s 计时基准挪到"真正开打"这一刻（:118-138）。
// 调用点 ConnectionManager.java:3863（handleHostPunchInfo，:3622 起的方法）。
func (a *App) logUploadSchedulePunchUpload() {
	logUploadMu.Lock()
	if !logActiveIsHost || logUploaded.Load() { // :120-122
		logUploadMu.Unlock()
		return
	}
	// :123-126 注释原文：自身已连接且稳定时，不应因"新加入玩家(可能失败)触发的 punch"
	// 去重铸铁上传/去上传样本
	if logConnectedMs > 0 && time.Now().UnixMilli()-logConnectedMs >= logStableWindow.Milliseconds() {
		logUploadMu.Unlock()
		return
	}
	if logUploadFuture != 0 { // :127-129
		logScheduler().cancel(logUploadFuture)
	}
	logPunchStartMs = time.Now().UnixMilli() // :130
	logUploadFuture = logScheduler().scheduleRepeat(logUploadDelay, 0, func() { a.logUploadRunUpload() })
	// :132-136 成功连接时 disarm 会取消轮询；新 joiner 进房重打时恢复"对手已传→1 秒跟上"快路径
	if logPollFuture != 0 {
		logScheduler().cancel(logPollFuture)
	}
	logPollFuture = logScheduler().scheduleRepeat(logPollInterval, 0, func() { a.logUploadPollOpponentUploaded() })
	logUploadMu.Unlock()
	logUploadLog("info", "log_upload_host_punch_timer_rearmed") // :137
}

// logUploadOnConnected 连接成功事件（:140-145）。注释原文：不再直接取消上传——
// 连上后短时间内掉线仍属失败，需要日志诊断。
// 调用点 ConnectionManager.java:15723（establishUdpTransport，:15655 起，所有数据面成功路径的汇聚点）。
func (a *App) logUploadOnConnected() {
	now := time.Now().UnixMilli()
	logUploadMu.Lock()
	logConnectedMs = now
	logLastActiveMs = now
	logUploadMu.Unlock()
}

// logUploadOnTransportActivity 数据面收到对端包即视为活跃（:147-155，内部节流 1s）。
// 调用点 ReliableUdpTransport.java:599（recvPathLoop 每个收到的包）。
func (a *App) logUploadOnTransportActivity() {
	now := time.Now().UnixMilli()
	logUploadMu.Lock()
	if now-logLastReportMs >= logActivityThrottle.Milliseconds() { // :151
		logLastReportMs = now
		logLastActiveMs = now
	}
	logUploadMu.Unlock()
}

// logUploadOnDisconnected 断链事件（:157-168）。
// 注释原文（2026-10-05 用户规则）：桥建进入原生连接屏后，任何掉线无条件上传
// ——半开案：判死时长不可控，稳定窗口曾吞证据。
// 调用点 ConnectionManager.java:15077（host 桥断开，为该房客重新打洞前）。
func (a *App) logUploadOnDisconnected() {
	logUploadMu.Lock()
	dropped := logConnectedMs > 0 && !logUploaded.Load() // :160
	if dropped {
		if logUploadFuture != 0 { // :161-163
			logScheduler().cancel(logUploadFuture)
		}
		logUploadFuture = logScheduler().scheduleRepeat(logImmediateDelay, 0, func() { a.logUploadRunUpload() }) // :164
	}
	logConnectedMs = 0 // :167 无条件清零（模组在 if 之外）
	logUploadMu.Unlock()
	if dropped {
		logUploadLog("info", "log_upload_link_dropped_inside_stable_window_upload_now") // :165
	}
}

// logUploadOnLinkDead 链路判死（单向死/静默超时）：与 onDisconnected 同权，无条件快传（:170-181）。
// 调用点 ReliableUdpTransport.java:1148（心跳连续 5 拍零入站）/:1174（60s 硬超）/
// :1180（24s 静默且有待确认包）/:1206（重传超限）。
func (a *App) logUploadOnLinkDead() {
	logUploadMu.Lock()
	if logActiveCode == "" || logUploaded.Load() { // :173-175
		logUploadMu.Unlock()
		return
	}
	if logUploadFuture != 0 { // :176-178
		logScheduler().cancel(logUploadFuture)
	}
	logUploadFuture = logScheduler().scheduleRepeat(logImmediateDelay, 0, func() { a.logUploadRunUpload() }) // :179
	logUploadMu.Unlock()
	logUploadLog("info", "log_upload_link_dead_upload_now") // :180
}

// logUploadOnTerminalFailure 终局失败事件（:183-196）。
// 注释原文：失败样本 5s 后快传，不再硬等 90s；2026-10-05 移除稳定窗口拦截，
// 连接屏阶段失败无条件传（半开案修复）。
// 调用点 ConnectionManager.java:13576（showConnectFailedFinal，:13514 起）
// 与 :15438（declareZeroRecvFinalFailure，:15376 起：持续重试耗尽且双向零收包）。
func (a *App) logUploadOnTerminalFailure() {
	logUploadMu.Lock()
	if logActiveCode == "" || logUploaded.Load() || logFailureArmed { // :187-189
		logUploadMu.Unlock()
		return
	}
	logFailureArmed = true    // :190
	if logUploadFuture != 0 { // :191-193
		logScheduler().cancel(logUploadFuture)
	}
	logUploadFuture = logScheduler().scheduleRepeat(logFailureDelay, 0, func() { a.logUploadRunUpload() }) // :194
	logUploadMu.Unlock()
	logUploadLog("info", "log_upload_terminal_failure_upload_in_5s") // :195
}

// logUploadDisarm 取消登记（:252-271）。
// 注释原文：终局失败后玩家退出加入界面 → 失败样本已经完整，立即补传而不是随 disarm 丢弃；
// 主动取消且未失败时 failureUploadArmed=false，行为不变（纯取消不上传）。
// 调用点 AttemptingJoinScreen.java:249（cancelJoin；注释写明"否则 90s 后 runUpload 照跑"）。
func (a *App) logUploadDisarm() {
	logUploadMu.Lock()
	flushFailure := logFailureArmed && !logUploaded.Load() && logActiveCodePtr != nil // :256
	if logUploadFuture != 0 {                                                         // :257-260
		logScheduler().cancel(logUploadFuture)
		logUploadFuture = 0
	}
	if logPollFuture != 0 { // :261-264
		logScheduler().cancel(logPollFuture)
		logPollFuture = 0
	}
	snapshot := logActiveCode                    // :267 模组用 static activeCode（不随 ACTIVE_CODE 清空）
	logActiveCodePtr = nil                       // :265 ACTIVE_CODE.set(null)
	if flushFailure && logUploadEnabled.Load() { // :266-270
		logActiveCodePtr = &snapshot
		logUploadFuture = logScheduler().scheduleRepeat(logFlushOnDisarmDelay, 0, func() { a.logUploadRunUpload() })
		logUploadMu.Unlock()
		logUploadLog("info", "log_upload_left_room_after_terminal_failure_flush") // :269
		return
	}
	logUploadMu.Unlock()
}

// logUploadSetOnUploaded 上传完成回调（:273-275）。模组无调用点（grep 全仓仅定义处），
// 本端保留同名入口，接线时可用来刷新 UI。
func logUploadSetOnUploaded(cb func()) {
	logUploadMu.Lock()
	logOnUploaded = cb
	logUploadMu.Unlock()
}

// logUploadIsUploadFinished 供 UI 显示"样本已上传"（:277-280；
// 调用点 AttemptingJoinScreen.java:791/805，用来给提示文字腾位置并画右下角成功串）。
func logUploadIsUploadFinished() bool { return logUploaded.Load() }

// ---- 房主轮询 ----

// logUploadPollOpponentUploaded 房主侧"对手已上传则 1 秒跟上"（:198-241）。
// 模组的 postOnce 是 sendAsync：HTTP 与后续判定都不占 :44 的单线程调度器，
// 只有"下一次轮询/上传"的排期回到调度器上。这里同构：取状态→异步发→回调里续排。
func (a *App) logUploadPollOpponentUploaded() {
	logUploadMu.Lock()
	if logPollFuture == 0 { // :200-202 已取消就不再续轮
		logUploadMu.Unlock()
		return
	}
	code := logActiveCode // :203-206
	if code == "" || logUploaded.Load() {
		logUploadMu.Unlock()
		return
	}
	// :207-214 本端已连接且稳定时，不因对端（可能是无关玩家的失败样本）上传而跟着上传
	if logConnectedMs > 0 && time.Now().UnixMilli()-logConnectedMs >= logStableWindow.Milliseconds() {
		logScheduler().cancel(logPollFuture)
		logPollFuture = 0
		logUploadMu.Unlock()
		return
	}
	if time.Now().UnixMilli()-logPunchStartMs > logHostWaitTimeout.Milliseconds() { // :215-219
		logUploadMu.Unlock()
		logUploadLog("info", "log_upload_host_wait_timeout_upload_anyway")
		a.logUploadRescheduleUploadNow()
		return
	}
	role := logRole // :220-224 请求体
	enabled := logUploadEnabled.Load()
	logUploadMu.Unlock()

	go func() {
		resp := a.logUploadPostOnceJSON(logRouteStatus, map[string]any{
			"code": code, "role": role, "enabled": enabled, "query": true,
		})
		a.logUploadPollDecide(resp)
	}()
}

// logUploadPollDecide 轮询回调体（:225-240 的 thenAccept lambda）。
func (a *App) logUploadPollDecide(resp *logApiResponse) {
	logUploadMu.Lock()
	if logPollFuture == 0 { // :226-228 响应回来时已取消
		logUploadMu.Unlock()
		return
	}
	opponentUploaded := false // :230-233 data.opponent.uploaded
	if resp != nil && resp.success && resp.data != nil {
		if opp, ok := resp.data["opponent"].(map[string]any); ok {
			if b, ok := opp["uploaded"].(bool); ok {
				opponentUploaded = b
			}
		}
	}
	if opponentUploaded { // :234-236
		logUploadMu.Unlock()
		logUploadLog("info", "log_upload_opponent_uploaded_host_upload_now")
		a.logUploadRescheduleUploadNow()
		return
	}
	logPollFuture = logScheduler().scheduleRepeat(logPollInterval, 0, func() { a.logUploadPollOpponentUploaded() }) // :238
	logUploadMu.Unlock()
}

// logUploadRescheduleUploadNow 覆盖调度前先取消旧任务，避免双 runUpload 并发（:243-250）。
func (a *App) logUploadRescheduleUploadNow() {
	logUploadMu.Lock()
	if logUploadFuture != 0 {
		logScheduler().cancel(logUploadFuture)
	}
	logUploadFuture = logScheduler().scheduleRepeat(logImmediateDelay, 0, func() { a.logUploadRunUpload() })
	logUploadMu.Unlock()
}

// ---- 上传主逻辑 ----

// logUploadRunUpload 到期执行体（:320-352）。
func (a *App) logUploadRunUpload() {
	logUploadMu.Lock()
	code := logActiveCode // :322-325
	active := ""
	if logActiveCodePtr != nil {
		active = *logActiveCodePtr
	}
	connected := logConnectedMs   // :333
	lastActive := logLastActiveMs // :335
	logUploadMu.Unlock()
	if code == "" || active != code {
		return
	}
	if !logUploadEnabled.Load() { // :326-329
		logUploadLog("info", "log_upload_skipped_disabled_by_player")
		return
	}
	if logUploaded.Load() { // :330-332
		return
	}
	if connected > 0 {
		now := time.Now().UnixMilli()
		if now-lastActive > logActivityTimeout.Milliseconds() { // :335-337
			// 数据面已静默：连上但链路死了，属失败 → 继续往下走立即上传
			logUploadLog("info", "log_upload_transport_silent_upload_now")
		} else if now-connected < logStableWindow.Milliseconds() { // :338-345
			// 仍在稳定窗口内：顺延复查（先取消旧 future 再排新的，模组注释同义）
			logUploadMu.Lock()
			if logUploadFuture != 0 {
				logScheduler().cancel(logUploadFuture)
			}
			logUploadFuture = logScheduler().scheduleRepeat(logPostponeInterval, 0, func() { a.logUploadRunUpload() })
			logUploadMu.Unlock()
			return
		} else { // :346-349
			logUploadLog("info", "log_upload_connection_stable_skip")
			return
		}
	}
	a.logUploadWithRetry(code, logMaxAttempts) // :351
}

// logUploadWithRetry 单飞 + 重试（:354-405）。
func (a *App) logUploadWithRetry(code string, attemptsLeft int) {
	logUploadMu.Lock()
	cur := ""
	if logActiveCodePtr != nil { // :356-360
		cur = *logActiveCodePtr
	}
	logUploadMu.Unlock()
	if cur != code {
		// 注释原文：已 disarm（取消加入/离开房间）时，在途重试不得再上传，
		// 否则玩家取消后日志仍会冒出来
		return
	}
	if !logInFlight.CompareAndSwap(false, true) { // :361-369
		logUploadLog("info", "log_upload_already_in_flight_retry_in_15s")
		logUploadMu.Lock()
		if logUploadFuture != 0 {
			logScheduler().cancel(logUploadFuture)
		}
		logUploadFuture = logScheduler().scheduleRepeat(logRetryDelay, 0, func() { a.logUploadWithRetry(code, attemptsLeft) })
		logUploadMu.Unlock()
		return
	}
	payload := a.logUploadBuildPayload()
	if payload == nil { // :371-374
		logInFlight.Store(false)
		return
	}

	ctx, cancel := context.WithTimeout(context.Background(), logUploadOrTimeout) // :376 orTimeout(90s)
	go func() {
		defer cancel()
		resp, err := a.logUploadPostBytes(ctx, logRouteUpload, payload, code) // :375
		logInFlight.Store(false)                                              // :378 whenComplete 第一件事就是释放单飞锁
		if err != nil {                                                       // :379-389
			why := "timeout"
			if !errors.Is(err, context.DeadlineExceeded) { // :381 root instanceof TimeoutException
				why = err.Error()
			}
			if attemptsLeft > 1 { // :382-384
				logUploadLog("warn", "log_upload_error retry_left="+strconv.Itoa(attemptsLeft-1))
				// 模组的这两条重试**不**登记进 uploadFuture（:384/:400 直接 SCHEDULER.schedule），
				// 所以之后的 disarm 取消不掉在途重试链。如实复刻；要改属行为变更，需拍板（报告列明）。
				logScheduler().scheduleRepeat(logRetryDelay, 0, func() { a.logUploadWithRetry(code, attemptsLeft-1) })
			} else { // :385-387
				logUploadLog("warn", "log_upload_gave_up err="+why)
			}
			return
		}
		if resp != nil && resp.success { // :390-397
			logUploadLog("info", fmt.Sprintf("log_upload_uploaded role=%s bytes=%d", logRoleOf(), len(payload))) // :391
			logUploaded.Store(true)
			logUploadMu.Lock()
			cb := logOnUploaded
			logUploadMu.Unlock()
			if cb != nil {
				cb()
			}
			a.logUploadNotifyUploaded()
			return
		}
		if attemptsLeft > 1 { // :398-400
			logUploadLog("warn", "log_upload_failed retry_left="+strconv.Itoa(attemptsLeft-1))
			logScheduler().scheduleRepeat(logRetryDelay, 0, func() { a.logUploadWithRetry(code, attemptsLeft-1) })
			return
		}
		logUploadLog("warn", "log_upload_gave_up code="+respCode(resp)) // :401-403
	}()
}

// ---- 退出兜底 ----

// logUploadOnExit JVM 退出兜底上传（:407-436，模组在静态块 :68-76 注册 shutdown hook）。
// 注释原文：失败后玩家直接关游戏（90s 定时器随 JVM 蒸发）是最大丢样本源；钩子里只对
// "终局失败"或"满 30s 未连上即退游戏"的会话做一次限时同步上传；成功/已连接/主动取消一律不传。
func (a *App) logUploadOnExit() {
	defer func() { _ = recover() }() // :434 catch(Throwable)：任何异常都必须吞掉，不能阻断退出
	logUploadMu.Lock()
	code := ""
	if logActiveCodePtr != nil { // :411-414
		code = *logActiveCodePtr
	}
	connected := logConnectedMs // :418-421
	startMs := logPunchStartMs  // :424
	failed := logFailureArmed
	enabled := logUploadEnabled.Load()
	logUploadMu.Unlock()

	if code == "" || logUploaded.Load() || logInFlight.Load() {
		return
	}
	if !enabled {
		return
	}
	if connected > 0 {
		// 注释原文：已连接的会话(无论是否过稳定窗口)视为正常使用，关游戏不传
		return
	}
	abandoned := time.Now().UnixMilli()-startMs >= logMinAgeForQuit.Milliseconds() // :424
	if !failed && !abandoned {                                                     // :425-427
		return
	}
	payload := a.logUploadBuildPayload() // :428-431
	if payload == nil {
		return
	}
	logInFlight.Store(true) // :432
	cause := "unfinished"
	if failed {
		cause = "failed"
	}
	a.logUploadSyncBeforeExit(code, payload, cause) // :433
}

// logUploadSyncBeforeExit 退出前的同步限时上传（:438-481）。
func (a *App) logUploadSyncBeforeExit(code string, payload []byte, cause string) {
	defer func() { _ = recover() }()                                 // :478-480 catch(Exception)
	urlStr, err := buildRequestURL(a.baseURL(), logRouteUpload, nil) // :441 buildUrl(ROUTE_UPLOAD)
	if err != nil {
		logUploadLog("warn", "log_upload_shutdown_failed err=bad_server_url")
		return
	}
	playerName := a.logUploadPlayerName() // :442-449
	logUploadMu.Lock()
	startMs := logPunchStartMs // :450
	role := logRole            // :458
	logUploadMu.Unlock()
	durationMs := time.Now().UnixMilli() - startMs
	if durationMs < 0 { // :450 Math.max(0, …)
		durationMs = 0
	}

	req, err := http.NewRequest(http.MethodPost, urlStr, bytes.NewReader(payload))
	if err != nil {
		logUploadLog("warn", "log_upload_shutdown_failed err=build_request")
		return
	}
	req.ContentLength = int64(len(payload))
	// 头集合逐条对齐 :453-462
	req.Header.Set("Content-Type", "application/octet-stream")
	req.Header.Set("Accept", "application/json")
	req.Header.Set("User-Agent", httpUserAgent)
	req.Header.Set("X-VoxLink-Version", appVersion)
	req.Header.Set("X-Log-Sha256", logUploadSHA256Hex(payload))
	req.Header.Set("X-Log-Role", role)
	req.Header.Set("X-Log-Code", code)
	req.Header.Set("X-Log-Name", playerName)
	req.Header.Set("X-Log-Version", appVersion)
	req.Header.Set("X-Log-Duration-Ms", strconv.FormatInt(durationMs, 10))

	ctx, cancel := context.WithTimeout(context.Background(), logShutdownTimeout) // :463 timeout(6s)
	defer cancel()
	req = req.WithContext(ctx)
	client := &http.Client{Timeout: logShutdownConnect} // :466-470 connectTimeout=min(6s,4s)、HTTP/1.1、跟随重定向
	resp, err := client.Do(req)                         // :471 同步 send
	if err != nil {
		logUploadLog("warn", "log_upload_shutdown_failed err=network") // :478-480
		return
	}
	defer resp.Body.Close()
	if resp.StatusCode == 200 { // :472-474
		logUploaded.Store(true)
		logUploadLog("info", fmt.Sprintf("log_upload_shutdown_ok cause=%s bytes=%d", cause, len(payload)))
		return
	}
	logUploadLog("warn", "log_upload_shutdown_rejected http="+strconv.Itoa(resp.StatusCode)) // :476
}

// ---- 载荷 ----

// logUploadBuildPayload 读日志尾并 gzip（:483-500）。
func (a *App) logUploadBuildPayload() []byte {
	raw := a.logUploadReadLogTail()
	if len(raw) == 0 { // :487-490（模组：raw==null || raw.length==0 → "no log content, skip"）
		logUploadLog("info", "log_upload_no_log_content_skip")
		return nil
	}
	var buf bytes.Buffer
	gw := gzip.NewWriter(&buf) // :492
	if _, err := gw.Write(raw); err != nil {
		_ = gw.Close()
		logUploadLog("warn", "log_upload_gzip_failed err=write") // :497
		return nil
	}
	if err := gw.Close(); err != nil { // try-with-resources 的关闭也可能是失败点
		logUploadLog("warn", "log_upload_gzip_failed err=close")
		return nil
	}
	return buf.Bytes()
}

// logUploadReadLogTail 读日志文件最后 4MB（:502-525）。
func (a *App) logUploadReadLogTail() []byte {
	path := a.logUploadLogPath()
	st, err := os.Stat(path)
	if err != nil || !st.Mode().IsRegular() { // :506-509 !logFile.isFile()
		logUploadLog("info", "log_upload_latest_log_missing_skip")
		return nil
	}
	length := st.Size()
	if length <= 0 { // :510-513
		return []byte{}
	}
	offset := int64(0)
	if length > logMaxBytes { // :514 offset = max(0, length - MAX_LOG_BYTES)
		offset = length - logMaxBytes
	}
	f, err := os.Open(path) // :515 RandomAccessFile(…, "r")
	if err != nil {
		logUploadLog("warn", "log_upload_read_latest_log_failed err=open") // :521-523
		return nil
	}
	defer f.Close()
	if _, err := f.Seek(offset, io.SeekStart); err != nil { // :516 seek
		logUploadLog("warn", "log_upload_read_latest_log_failed err=seek")
		return nil
	}
	data := make([]byte, length-offset)             // :517
	if _, err := io.ReadFull(f, data); err != nil { // :518 readFully
		logUploadLog("warn", "log_upload_read_latest_log_failed err=read")
		return nil
	}
	return data
}

// logUploadLogPath 本端的 logs/latest.log 等价物：<配置目录>/VoxLinkApp/logs/latest.log。
// 模组读 Minecraft.getInstance().gameDirectory/logs/latest.log（:505）。本 App 目前不落盘日志
// （log.Printf 只到 stderr），所以这里先把"读哪里的日志"这一件事按模组同名结构定下来：
// 交付报告给了把 netLog 同时写入该文件的 hunk，接上后本函数一字不用改就能取到内容；
// 没接上时行为与模组"文件缺失"分支完全一致——记 missing/skip 且不上传（:506-509）。
func (a *App) logUploadLogPath() string {
	return filepath.Join(filepath.Dir(a.settingsPath), "logs", "latest.log")
}

// logUploadPlayerName 玩家名（模组 :444-447 取 Minecraft 用户档案）。本 App 无账号体系，
// 取登录用户名；取不到给空串（服务端 truncateRunes(...,32)，server-go/loguploader.go:256）。
func (a *App) logUploadPlayerName() string {
	// os/user 在无 cgo 时有纯 Go 实现（Windows 走 syscall、unix 读 /etc/passwd），
	// 三平台交叉编译都不需要 CGO。Windows 的 Username 形如 DESKTOP-XXX\alice，
	// 模组给的是纯玩家名，这里同样只取反斜杠后那段。
	u, err := user.Current()
	if err != nil || u == nil {
		return ""
	}
	name := u.Username
	if i := strings.LastIndexByte(name, '\\'); i >= 0 {
		name = name[i+1:]
	}
	return name
}

// ---- HTTP 与响应 ----

// logApiResponse 复刻 SignalingClient.ApiResponse 中本文件用到的字段（SignalingClient.java:857-911）。
type logApiResponse struct {
	success    bool
	code       string
	message    string
	data       map[string]any
	retryAfter int
}

func respCode(r *logApiResponse) string {
	if r == nil {
		return "NULL_RESPONSE"
	}
	return r.code
}

// logUploadPostOnceJSON postOnce(path, body)（:527-530）= postJson + parseResponse。
// 模组用 sendAsync().thenAccept()：异常时不执行回调。这里以 nil 表达"没有响应"。
func (a *App) logUploadPostOnceJSON(route string, body map[string]any) *logApiResponse {
	status, raw, err := a.logUploadPostJSON(route, body)
	if err != nil {
		return nil
	}
	return logUploadParseResponse(status, raw)
}

// logUploadPostJSON postJson(path, body)（:532-545）。
func (a *App) logUploadPostJSON(route string, body map[string]any) (int, string, error) {
	urlStr, err := buildRequestURL(a.baseURL(), route, nil) // :534 buildUrl
	if err != nil {
		return 0, "", err
	}
	b, err := json.Marshal(body)
	if err != nil {
		return 0, "", err
	}
	req, err := http.NewRequest(http.MethodPost, urlStr, bytes.NewReader(b))
	if err != nil {
		return 0, "", err
	}
	req.Header.Set("Content-Type", "application/json")                          // :537
	req.Header.Set("Accept", "application/json")                                // :538
	req.Header.Set("User-Agent", httpUserAgent)                                 // :539
	req.Header.Set("X-VoxLink-Version", appVersion)                             // :540
	ctx, cancel := context.WithTimeout(context.Background(), logRequestTimeout) // :541 timeout
	defer cancel()
	req = req.WithContext(ctx)
	resp, err := a.logUploadClient().Do(req) // :544 sendAsync
	if err != nil {
		return 0, "", err
	}
	defer resp.Body.Close()
	data, _ := io.ReadAll(io.LimitReader(resp.Body, maxResponseLen))
	return resp.StatusCode, string(data), nil
}

// logUploadPostBytes postBytes(path, payload, code)（:547-569）。
// 这里的"异步"由调用方（logUploadWithRetry 的 goroutine）承载，与模组 sendAsync 的分工一致。
func (a *App) logUploadPostBytes(ctx context.Context, route string, payload []byte, code string) (*logApiResponse, error) {
	urlStr, err := buildRequestURL(a.baseURL(), route, nil) // :549
	if err != nil {
		return nil, err
	}
	sha := logUploadSHA256Hex(payload) // :550
	logUploadMu.Lock()
	startMs := logPunchStartMs // :551
	role := logRole            // :560
	logUploadMu.Unlock()
	durationMs := time.Now().UnixMilli() - startMs
	if durationMs < 0 {
		durationMs = 0
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, urlStr, bytes.NewReader(payload))
	if err != nil {
		return nil, err
	}
	req.ContentLength = int64(len(payload))
	req.Header.Set("Content-Type", "application/octet-stream")             // :553
	req.Header.Set("Accept", "application/json")                           // :554
	req.Header.Set("User-Agent", httpUserAgent)                            // :555
	req.Header.Set("X-VoxLink-Version", appVersion)                        // :556
	req.Header.Set("X-Log-Sha256", sha)                                    // :557
	req.Header.Set("X-Log-Role", role)                                     // :558
	req.Header.Set("X-Log-Code", code)                                     // :559
	req.Header.Set("X-Log-Name", a.logUploadPlayerName())                  // :560-562
	req.Header.Set("X-Log-Version", appVersion)                            // :561
	req.Header.Set("X-Log-Duration-Ms", strconv.FormatInt(durationMs, 10)) // :562-564
	resp, err := a.logUploadClient().Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	data, _ := io.ReadAll(io.LimitReader(resp.Body, maxResponseLen)) // :565
	return logUploadParseResponse(resp.StatusCode, string(data)), nil
}

// logUploadReportStatus reportStatus()（:306-318）：arm 时把本端开关登记给服务端。
// 模组用 sendAsync（不占调用线程，也不占 :44 的单线程调度器），这里同样异步发出，
// 否则 arm 会被一次 HTTP 往返卡住 CreateRoom/JoinRoom 的返回。
func (a *App) logUploadReportStatus() {
	logUploadMu.Lock()
	code := logActiveCode
	role := logRole
	logUploadMu.Unlock()
	enabled := logUploadEnabled.Load() // :308
	go func() {
		resp := a.logUploadPostOnceJSON(logRouteStatus, map[string]any{
			"code": code, "role": role, "enabled": enabled, // :309-313
		})
		if resp != nil && !resp.success { // :314-316
			logUploadLog("warn", "log_upload_status_report_failed code="+resp.code)
		}
	}()
}

// logUploadClient client()（:595-608）：connectTimeout=config.getConnectionTimeout()（默认 20s）、
// HTTP/1.1（:602）、跟随重定向（:603）；惰性建一次并缓存（模组同样缓存）。
func (a *App) logUploadClient() *http.Client {
	logUploadMu.Lock()
	defer logUploadMu.Unlock()
	if logHTTPClient == nil {
		logHTTPClient = &http.Client{
			Timeout: logRequestTimeout, // :541/:565 请求超时取同一个值
			Transport: &http.Transport{
				DialContext: (&net.Dialer{
					Timeout:   logRequestTimeout, // :601 connectTimeout
					KeepAlive: 30 * time.Second,
				}).DialContext,
				ForceAttemptHTTP2:     false, // :602 Version.HTTP_1_1
				MaxIdleConns:          8,
				IdleConnTimeout:       30 * time.Second,
				TLSHandshakeTimeout:   logRequestTimeout,
				ExpectContinueTimeout: time.Second,
			},
		}
	}
	return logHTTPClient
}

// logUploadNotifyUploaded notifyUploadedInChat()（:283-304）。
// 模组往玩家聊天框推 voxlink.log_upload.uploaded；本端没有聊天框，改推前端事件，
// 前端按同一 lang 键渲染（接线说明见交付报告）。
func (a *App) logUploadNotifyUploaded() {
	a.emit("log:uploaded", map[string]any{"role": logRoleOf()})
}

// ---- 解析与工具 ----

// logUploadParseResponse parseResponse()（:571-582）。
// 200 → ApiResponse.fromHttpResponse；429 → 写死 RATE_LIMITED + retryAfter=15；
// 其余 → 先按统一信封试解析错误，解不出来记 SERVER_<status>。
func logUploadParseResponse(status int, body string) *logApiResponse {
	if status == 200 { // :573-576
		return logUploadFromHTTPBody(body)
	}
	if status == http.StatusTooManyRequests { // :577-579
		return &logApiResponse{success: false, code: "RATE_LIMITED", message: "RATE_LIMITED", retryAfter: logRateLimitedRetrySec}
	}
	if parsed := logUploadTryParseError(body); parsed != nil { // :580-581
		return parsed
	}
	code := "SERVER_" + strconv.Itoa(status)
	return &logApiResponse{success: false, code: code, message: code}
}

// logUploadFromHTTPBody 等价 ApiResponse.fromHttpResponse（SignalingClient.java:882-896）。
func logUploadFromHTTPBody(body string) *logApiResponse {
	var m map[string]any
	if json.Unmarshal([]byte(body), &m) != nil || m == nil { // :885-887 / :894
		return &logApiResponse{success: false, code: "PARSE_ERROR", message: "响应解析失败"}
	}
	return logUploadFromMap(m) // :889
}

// logUploadFromMap 等价 ApiResponse.fromJson（SignalingClient.java:899-911）。
func logUploadFromMap(m map[string]any) *logApiResponse {
	r := &logApiResponse{}
	if v, ok := m["success"].(bool); ok { // :904
		r.success = v
	}
	if v, ok := m["error"].(string); ok { // :905
		r.code = v
	}
	if v, ok := m["message"].(string); ok { // :906
		r.message = v
	}
	if d, ok := m["data"].(map[string]any); ok { // :907
		r.data = d
	}
	if v, ok := m["retryAfter"].(float64); ok { // :909
		r.retryAfter = int(v)
	}
	return r
}

// logUploadTryParseError 等价 ApiResponse.tryParseError（SignalingClient.java:913-）：
// 空体/非 JSON 对象/没有 success 字段都算解析失败（返回 nil）。
func logUploadTryParseError(body string) *logApiResponse {
	if body == "" { // :914
		return nil
	}
	var m map[string]any
	if json.Unmarshal([]byte(body), &m) != nil || m == nil { // :916-920
		return nil
	}
	if _, ok := m["success"]; !ok {
		return nil
	}
	return logUploadFromMap(m)
}

// logUploadSHA256Hex sha256(data) 的小写十六进制（:610-623）。
// 模组手写 Character.forDigit 拼串，等价 hex.EncodeToString（服务端用 Sha256Hex 复核，
// server-go/loguploader.go:304）。
func logUploadSHA256Hex(data []byte) string {
	sum := sha256.Sum256(data)
	return hex.EncodeToString(sum[:])
}

func logRoleOf() string {
	logUploadMu.Lock()
	defer logUploadMu.Unlock()
	return logRole
}

// logUploadLogSink 由 App 启动时注入（hunk 见交付报告）。
var logUploadLogSink func(level, msg string)

// logUploadLog 上传模块日志出口：消敏口径同 netLog。
func logUploadLog(level, msg string) {
	if logUploadLogSink != nil {
		logUploadLogSink(level, msg)
		return
	}
	log.Printf("[%s] %s", level, sanitizeNetLogMessage(msg))
}

// ---- 给 UI/CLI 的两个绑定方法 ----

// LogUploadStatus 当前上传开关与"本场样本是否已传"（对应模组 GUI 开关状态 +
// AttemptingJoinScreen.java:791/805 用 isUploadFinished() 决定要不要画那行提示）。
func (a *App) LogUploadStatus() string {
	return wrapOK(map[string]any{
		"enabled":  logUploadEnabled.Load(),
		"uploaded": logUploadIsUploadFinished(),
		"inRoom":   a.logUploadInRoom(),
	})
}

// LogUploadSetEnabled 玩家开关（对应 VoxLinkConfig.java:274-278 setLogUploadEnabled
// + LogUploadState.java:12-16 内存态；模组在 GUI 关闭后保持关闭）。
// 只翻内存态：本端 settings.json 尚无这一项，落盘的 hunk 见交付报告（settings.go）。
func (a *App) LogUploadSetEnabled(enabled bool) string {
	logUploadEnabled.Store(enabled) // LogUploadState.setLogUploadEnabled
	// 模组关开关时不会取消已在排的定时器（下次 runUpload 里判 enabled 直接 skip，:326-329），
	// 这里同样只做"下一次上传时生效"，不回滚已排期。
	return wrapOK(map[string]any{"enabled": enabled})
}

// logUploadInRoom 是否处于已登记会话（activeCode 非空）。
func (a *App) logUploadInRoom() bool {
	logUploadMu.Lock()
	defer logUploadMu.Unlock()
	return logActiveCodePtr != nil
}
