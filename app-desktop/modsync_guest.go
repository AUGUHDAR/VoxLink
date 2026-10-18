package main

// modsync_guest.go — 房客侧门控：清单拉取重试状态机 + 离线 diff + 下载执行环。
// 逐句对应 `modsync/ModSyncGuestService.java`（590 行）；下载执行环对应
// `modsync/ModSyncSelectScreen.java` 的 downloadLoop（:313-390，纯逻辑部分）。
//
// 与 Java 的分工差异：Java 把回调投递回 MC 主线程并打开 Screen；
// 本端把同一批状态作为 JSON 交给前端（HTML 渲染），流程节点一一对应：
//   gate()          → ModSyncGate（返回 proceed / choose）
//   bypass()        → ModSyncBypass
//   markSkipped()   → ModSyncMarkSkipped
//   clearGateFor()  → ModSyncClearGate
//   SelectScreen 的下载环 → ModSyncDownload / ModSyncCancelDownload（事件 modsync:progress / modsync:download）
//
// 重试预算（全部有 Java file:line 背书，勿改）：
//   READY_RETRIES = 5                       ModSyncGuestService.java:43
//   maxAttempts   = READY_RETRIES*2 = 10    :286（"瞬态失败也要重试"）
//   单次请求超时   8s                        :299 `.get(8L, TimeUnit.SECONDS)`
//   总时限        12000ms                    :289（与服务端长轮询 modsRequestWaitSec=12
//                                             server-go/modsmanifest.go:23 同值）
//   瞬态失败 sleep 1500ms                    :313
//   supported 但未 ready sleep 2000ms        :346
//   睡眠按 100ms 粒度可中断                   cancellableSleep :241-265
//   权威错误表（不再重试）                    :300-309
//   NOT_READY 终局撤销在途标记，允许下次重试    :361-365
//
// 门控绝不拦住玩家：supported=false / 超时 / 空清单 / 未配置 一律直通，
// 直通原因一律落日志（Java 类注释 :21-24）。

import (
	"errors"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// ---- FetchOutcome（ModSyncGuestService.java:268）----

const (
	modSyncFetchManifest    = "MANIFEST"    // 拿到清单
	modSyncFetchUnsupported = "UNSUPPORTED" // 房主未声明 modSyncV1
	modSyncFetchEmpty       = "EMPTY"       // 两档皆空的真·空清单
	modSyncFetchNotReady    = "NOT_READY"   // 未就绪/权威失败/预算耗尽
	modSyncFetchBypassed    = "BYPASSED"    // 玩家点"直接进入"
	// 本端口补充（Java 无此态）：MC 实例未配置时无从 diff，按"开关关闭"同一等直通。
	// 记在报告 (e) 里：这是新增态，不是对 Java 任何一态的简化。
	modSyncFetchNotConfigured = "NOT_CONFIGURED"
	// gate() 入口的两种直通（Java :103-106：开关关闭 / 非 VoxLink 房号）
	modSyncGateDisabled = "DISABLED"
)

const (
	modSyncReadyRetries   = 5                // ModSyncGuestService.java:43
	modSyncFetchPerTry    = 8 * time.Second  // :299
	modSyncFetchDeadline  = 12 * time.Second // :289
	modSyncTransientSleep = 1500 * time.Millisecond
	modSyncNotReadySleep  = 2000 * time.Millisecond
	modSyncSleepStep      = 100 * time.Millisecond
)

// modSyncGateState 门控会话状态（对应四个 static Set，:25-36）
type modSyncGateState struct {
	mu sync.Mutex
	// GATED_THIS_LAUNCH：在途标记（:25）
	gated map[string]bool
	// BYPASSED_THIS_LAUNCH：本次启动内玩家已选择跳过检查的房间（:27）
	bypassed map[string]bool
	// USER_CHOSE_THIS_LAUNCH：Java 声明了但除注释外未使用（:28-34）—— 保留同一形状
	userChose map[string]bool
	// SKIPPED_THIS_SESSION：已选择"跳过"的版本冲突键 projectId@versionNumber（:36）
	skipped map[string]bool
}

var modSyncGate = &modSyncGateState{
	gated:     map[string]bool{},
	bypassed:  map[string]bool{},
	userChose: map[string]bool{},
	skipped:   map[string]bool{},
}

// modSyncResetGateStateForTest 清四个"本进程一次性"集合（模组侧是 static Set，进程活多久存多久）。
// 仅供测试用：Go 的测试二进制里同一用例可以跑多轮（-count=N），不清就会第二轮直接撞在旧集合上。
func modSyncResetGateStateForTest() {
	modSyncGate.mu.Lock()
	defer modSyncGate.mu.Unlock()
	modSyncGate.gated = map[string]bool{}
	modSyncGate.bypassed = map[string]bool{}
	modSyncGate.userChose = map[string]bool{}
	modSyncGate.skipped = map[string]bool{}
}

// pendingNote：门控直通时的待播报提示（:44-46）。
// Java 由加入屏在连接启动后 pushPendingGateNote() 取出，避开面板重置；
// 本端由前端在 enterRoomPage 之后 flush。
var (
	modSyncNoteMu  sync.Mutex
	modSyncNoteKey string
	modSyncNoteLvl = -1
)

func modSyncSetPendingNote(level int, key string) {
	modSyncNoteMu.Lock()
	modSyncNoteKey = key
	modSyncNoteLvl = level
	modSyncNoteMu.Unlock()
}

func modSyncTakePendingNote() (int, string) {
	modSyncNoteMu.Lock()
	defer modSyncNoteMu.Unlock()
	lvl, key := modSyncNoteLvl, modSyncNoteKey
	modSyncNoteKey = ""
	modSyncNoteLvl = -1
	return lvl, key
}

func (g *modSyncGateState) isBypassed(code string) bool {
	g.mu.Lock()
	defer g.mu.Unlock()
	return g.bypassed[code]
}

func (g *modSyncGateState) markGated(code string) {
	g.mu.Lock()
	g.gated[code] = true
	g.mu.Unlock()
}

func (g *modSyncGateState) clearGated(code string) {
	g.mu.Lock()
	delete(g.gated, code)
	g.mu.Unlock()
}

// clearGateFor 清该房门控会话状态（在途标记 + bypass）（:52-57）：
// 新一次加入必须重新弹"获取模组"页。
func (g *modSyncGateState) clearGateFor(code string) {
	if code == "" {
		return
	}
	g.mu.Lock()
	delete(g.gated, code)
	delete(g.bypassed, code)
	g.mu.Unlock()
}

// ---- 下载执行环状态（SelectScreen 的 downloadAbort / 进度字段）----

type modSyncDownloadState struct {
	mu       sync.Mutex
	running  bool
	abort    atomic.Bool
	startMs  int64
	cancelWG sync.WaitGroup
	// last 下载终局快照（与 "modsync:download" 事件同载荷）。
	// 存在理由：CLI/启动器是一次性进程，未必挂着事件流，必须还能事后问出结果。
	last map[string]any
}

// modSyncEmitDownloadFinal 下载终局：发给界面（模组 SelectScreen 回调的等价物），
// 同时留快照。载荷字段一律可选，调用方按自己关心的读。
func (a *App) modSyncEmitDownloadFinal(payload map[string]any) {
	a.emit("modsync:download", payload)
	modSyncDL.mu.Lock()
	modSyncDL.last = payload
	modSyncDL.mu.Unlock()
}

var modSyncDL = &modSyncDownloadState{}

// ---- 拉取 ----

type modSyncFetchResult struct {
	outcome  string
	manifest map[string]any
}

// modSyncRoomSupportsManifest 门控的前置校验：这间房到底有没有"房主发布的清单"。
//
// 位点来源说明（移植纪律）：**模组无此位点**。ModSyncGuestService.gate（:102-136）跑在 MC
// 进程内，本地 mods 目录与房主清单都在同进程可及之处，"房主开没开上传"只能在拉清单那一步
// 由服务端回包里的 supported 判出（fetchManifestWithRetry :320-323 → UNSUPPORTED），
// 模组拉这一次就是必需成本。应用端 MC 在进程外、本地既没有清单也没有房主身份可查，
// 所以必须自己先问服务端一次，否则 HL/互通镜像房也要先把一轮长轮询请求跑完才被判不支持
// （玩家实机报的缺陷：互通房弹"必装模组检查"）。
//
// 判据复用已有接口与已有字段，不新增服务端接口、不新造判据：
// 走 App.GetRoomMods 同一个 /room/mods（app.go:1056），读它的 "supported"——
// 服务端该端点只读房间记录里的 hostCapabilities（modsmanifest.go 的 hostSupportsModSync），
// 不向房主发信号、不长轮询，与 /room/mods/request 里那句 supported 判定同源同值。
// HL/镜像房的记录里没有 hostCapabilities（source=happylink 一类字段见 fed_state.go），
// 因此 supported 恒 false → 前置跳过。
//
// 只在服务端**明确**回 supported=false 时才判"不支持"：网络失败/权威错误/空响应
// 一律返回 true 交给原有取清单路径继续判 —— 门控绝不拦住玩家（本文件类注释 :27-28）。
func (a *App) modSyncRoomSupportsManifest(roomCode string) bool {
	data, err := a.modSyncCall("/room/mods", map[string]any{
		"code": strings.ToUpper(roomCode),
	}, modSyncFetchPerTry)
	if err != nil {
		modSyncLogWarn("gate pre-check inconclusive, keep original flow: {}", err.Error()) // 探测失败不当不支持用
		return true
	}
	if len(data) == 0 {
		modSyncLogWarn("gate pre-check empty response, keep original flow")
		return true
	}
	return jbool(data, "supported")
}

// modSyncFetchWithRetry 对应 fetchManifestWithRetry（:281-368）。
func (a *App) modSyncFetchWithRetry(roomCode, scope string) modSyncFetchResult {
	sawNotReady := false
	sawTransient := false
	maxAttempts := modSyncReadyRetries * 2
	deadline := time.Now().Add(modSyncFetchDeadline)

	for attempt := 0; attempt < maxAttempts && time.Now().Before(deadline); attempt++ {
		if modSyncGate.isBypassed(roomCode) {
			return modSyncFetchResult{outcome: modSyncFetchBypassed}
		}

		data, err := a.modSyncCall("/room/mods/request", map[string]any{
			"code":   strings.ToUpper(roomCode),
			"scope":  scope,
			"action": "request_room_mods",
		}, modSyncFetchPerTry)

		if err != nil {
			var apiErr *APIError
			code := ""
			msg := ""
			if errors.As(err, &apiErr) {
				code = strings.ToUpper(apiErr.Code)
				msg = apiErr.Message
			}
			// 网络/超时/HTTP 解析层失败：Java 走 catch(TimeoutException)/catch(Exception)
			// （:349-358）—— 记一笔瞬态、不休眠、直接进下一次尝试。
			networkLike := code == "" || code == "NETWORK" || strings.HasPrefix(code, "HTTP_")
			if !networkLike {
				// 权威错误表（:300-309）：房不存在/过期/无效 token/已关闭/被挤出，
				// 以及老部署没有 /room/mods 路由（UNKNOWN_ENDPOINT/INVALID_ENDPOINT）
				// —— 房主必不支持清单，立即直通不重试。
				authoritative := strings.Contains(code, "ROOM_NOT_FOUND") ||
					strings.Contains(code, "ROOM_EXPIRED") ||
					strings.Contains(code, "INVALID_TOKEN") ||
					strings.Contains(code, "ROOM_CLOSED") ||
					strings.Contains(code, "ROOM_EVICTED") ||
					strings.Contains(code, "UNKNOWN_ENDPOINT") ||
					strings.Contains(code, "INVALID_ENDPOINT")
				if authoritative {
					modSyncLogWarn("getRoomMods failed (authoritative): {} {}", code, msg) // ModSyncGuestService.java:307
					return modSyncFetchResult{outcome: modSyncFetchNotReady}
				}
				// 服务器明确回了一个业务错误码：瞬态，sleep 1500ms 后重试（:311-317）
				sawTransient = true
				modSyncLogWarn("getRoomMods transient ({}/{}): {}", attempt+1, maxAttempts, code) // ModSyncGuestService.java:312
				if !modSyncCancellableSleep(roomCode, modSyncTransientSleep) {
					return modSyncFetchResult{outcome: modSyncFetchBypassed}
				}
				continue
			}
			sawTransient = true
			if code == "" || code == "NETWORK" {
				modSyncLogWarn("manifest fetch error: {}", err.Error()) // ModSyncGuestService.java:357
			} else {
				modSyncLogWarn("getRoomMods timeout ({}/{})", attempt+1, maxAttempts) // ModSyncGuestService.java:351
			}
			continue
		}

		// 信封 success 但无数据 —— Java 的 `resp.data == null` 路径：按瞬态处理。
		if len(data) == 0 {
			sawTransient = true
			modSyncLogWarn("getRoomMods transient ({}/{}): {}", attempt+1, maxAttempts, "EMPTY_RESPONSE") // ModSyncGuestService.java:312
			if !modSyncCancellableSleep(roomCode, modSyncTransientSleep) {
				return modSyncFetchResult{outcome: modSyncFetchBypassed}
			}
			continue
		}

		if !jbool(data, "supported") {
			return modSyncFetchResult{outcome: modSyncFetchUnsupported}
		}
		if !jbool(data, "ready") {
			sawNotReady = true
			if !modSyncCancellableSleep(roomCode, modSyncNotReadySleep) {
				return modSyncFetchResult{outcome: modSyncFetchBypassed}
			}
			continue
		}

		m := map[string]any{
			"loader":          modSyncLoaderOrDefault(data, "unknown"), // :328（缺键按 "unknown"）
			"mcVersion":       jstr(data, "mcVersion"),
			"mods":            modSyncArrayOrEmpty(data, "mods"),
			"unknownMods":     modSyncArrayOrEmpty(data, "unknownMods"),
			"protocolVersion": jstr(data, "protocolVersion"),
		}
		// mods 为空时不再一律静默（:336-341）：unknownMods 非空仍要给玩家看；
		// 两者都空才是真正的空清单 → EMPTY 直通。
		if len(m["mods"].([]any)) == 0 && len(m["unknownMods"].([]any)) == 0 {
			return modSyncFetchResult{outcome: modSyncFetchEmpty}
		}
		return modSyncFetchResult{outcome: modSyncFetchManifest, manifest: m}
	}

	if sawNotReady || sawTransient {
		// 房主清单一直没发布（MR 解析慢/失败）：不算完成门控，撤销在途标记允许下次重试
		modSyncLogWarn("manifest never became ready, giving up gate")
		modSyncGate.clearGated(roomCode)
	}
	return modSyncFetchResult{outcome: modSyncFetchNotReady}
}

// modSyncCancellableSleep 拆粒度可中断睡眠（:241-265）：100ms 一查 bypass/中断，
// 最坏 100ms 就能感知"直接进入"。返回 true 表示"睡满"。
func modSyncCancellableSleep(roomCode string, d time.Duration) bool {
	millis := int64(d / time.Millisecond)
	if millis <= 0 {
		return !modSyncGate.isBypassed(roomCode)
	}
	for millis > 0 {
		if modSyncGate.isBypassed(roomCode) {
			return false
		}
		step := millis
		if step > int64(modSyncSleepStep/time.Millisecond) {
			step = int64(modSyncSleepStep / time.Millisecond)
		}
		time.Sleep(time.Duration(step) * time.Millisecond)
		millis -= step
	}
	return !modSyncGate.isBypassed(roomCode)
}

func modSyncLoaderOrDefault(o map[string]any, def string) string {
	if _, ok := o["loader"]; !ok || o["loader"] == nil {
		return def
	}
	return jstr(o, "loader")
}

func modSyncArrayOrEmpty(o map[string]any, key string) []any {
	if arr := jarr(o, key); arr != nil {
		return arr
	}
	return []any{}
}

// ---- diff ----

// modSyncDiffResult 对应 Java 的 DiffResult（:408-417）。
type modSyncDiffResult struct {
	Downloadable []*modSyncEntry `json:"downloadable"`
	// ManualEntries：需手动安装的条目（MR 未收录/兼容性不符）（:410-411）
	ManualEntries []*modSyncEntry   `json:"manual"`
	Unresolvable  []modSyncLangLine `json:"unresolvable"`
	// VersionDiff：同类模组但版本与房主不同 —— 仅强提示，绝不移动/删除用户已装文件
	// （运行中的 jar 被占用且属用户资产）（:413-414）
	VersionDiff []modSyncLangLine `json:"versionDiff"`
	SkipKeys    []string          `json:"skipKeys"`
}

func newModSyncDiffResult() *modSyncDiffResult {
	return &modSyncDiffResult{
		Downloadable:  []*modSyncEntry{},
		ManualEntries: []*modSyncEntry{},
		Unresolvable:  []modSyncLangLine{},
		VersionDiff:   []modSyncLangLine{},
		SkipKeys:      []string{},
	}
}

// modSyncComputeDiff 离线 diff（:427-559）：清单条目自带房主文件的 sha1，
// 与本地 mods 目录的 sha1 集合直接对比 —— 客户端全程零 Modrinth API 调用，
// 仅按清单里的 CDN 直链下载（sha512 校验）。
func modSyncComputeDiff(manifest map[string]any, env modSyncEnv) *modSyncDiffResult {
	out := newModSyncDiffResult()

	scan := modSyncListJarsWithDisabled(env.ModsDir)
	localSha1s := map[string]bool{}
	localFileNames := map[string]bool{}
	for _, jar := range scan.Jars {
		if h, err := modSyncSha1File(jar); err == nil {
			localSha1s[h] = true
		}
		localFileNames[filepath.Base(jar)] = true
	}

	// .jar.disabled 也参与 sha1 匹配（文件内容就是 jar，只是被禁用）（:444-458）
	disabledSha1s := map[string]bool{}
	if len(scan.Disabled) > 0 {
		for disabledName := range scan.Disabled {
			p := filepath.Join(env.ModsDir, disabledName+".disabled")
			if st, err := modSyncStatRegular(p); err == nil && st {
				if h, herr := modSyncSha1File(p); herr == nil {
					disabledSha1s[h] = true
				}
			}
		}
	}

	// 本地 jar 的归一化基名 → 原始文件名 / 字节数（识别"装了但版本不同"）（:460-474）
	normBaseToLocal := map[string]string{}
	normSizeByBase := map[string]int64{}
	for _, jar := range scan.Jars {
		nb := modSyncNormalizeFileName(filepath.Base(jar))
		if nb == "" {
			continue
		}
		if _, ok := normBaseToLocal[nb]; !ok {
			normBaseToLocal[nb] = filepath.Base(jar)
		}
		if _, ok := normSizeByBase[nb]; !ok {
			if st, err := modSyncFileSize(jar); err == nil {
				normSizeByBase[nb] = st
			}
		}
	}

	// 未知模组：房主侧在 MR 查不到的 jar → 结构化条目（:476-483）
	for _, el := range modSyncArrayOrEmpty(manifest, "unknownMods") {
		if s, ok := el.(string); ok {
			out.ManualEntries = append(out.ManualEntries, modSyncEntryFromFileName(s))
		}
	}

	for _, el := range modSyncArrayOrEmpty(manifest, "mods") {
		o, ok := el.(map[string]any)
		if !ok {
			continue
		}
		entry := modSyncEntryFromWire(o)
		if entry.ProjectID == "" {
			continue
		}

		// 精确 sha1 匹配：先看活跃 jar，再看 disabled jar（:495-510）
		installed := false
		installedButDisabled := false
		if entry.Sha1 != "" {
			if localSha1s[entry.Sha1] {
				installed = true
			} else if disabledSha1s[entry.Sha1] {
				installed = true
				installedButDisabled = true
			}
		} else if entry.FileName != "" && localFileNames[entry.FileName] {
			installed = true
		} else if scan.Disabled[entry.FileName] {
			installed = true
			installedButDisabled = true
		}
		if !installed {
			// 旧清单兼容兜底：归一化名相同且文件大小一致 ⇒ 视为已安装（:511-518）
			nb2 := modSyncNormalizeFileName(entry.FileName)
			if nb2 != "" {
				if localSize, ok2 := normSizeByBase[nb2]; ok2 && localSize == entry.Size {
					installed = true
				}
			}
		}
		if installed {
			if installedButDisabled {
				// 玩家主动禁用：不算 missing，提示让其手动启用（:520-528）
				out.Unresolvable = append(out.Unresolvable,
					newModSyncLangLine(langModSyncDisabledHint, entry.Title, entry.FileName))
			}
			continue
		}

		normBase := modSyncNormalizeFileName(entry.FileName)
		if _, has := normBaseToLocal[normBase]; normBase != "" && has {
			// 同类模组但与房主版本不同：绝不擅自动用户的文件（:533-545）
			skipKey := entry.ProjectID + "@" + entry.VersionNumber
			modSyncGate.mu.Lock()
			_, already := modSyncGate.skipped[skipKey]
			if !already {
				modSyncGate.skipped[skipKey] = true
			}
			modSyncGate.mu.Unlock()
			if !already {
				out.VersionDiff = append(out.VersionDiff, newModSyncLangLine(langModSyncDiffLine, entry.Title))
				out.SkipKeys = append(out.SkipKeys, skipKey)
			}
			continue
		}

		hostFileCompatible := modSyncContains(entry.Loaders, env.Loader) &&
			modSyncContains(entry.GameVersions, env.GameVersion) &&
			entry.DownloadURL != ""
		if hostFileCompatible {
			out.Downloadable = append(out.Downloadable, entry)
		} else {
			// 房主的构建版本与本端 MC/加载器不一致 → 转手动安装条目（:547-555）
			out.ManualEntries = append(out.ManualEntries, entry)
		}
	}

	return out
}

func modSyncContains(list []string, v string) bool {
	for _, s := range list {
		if s == v {
			return true
		}
	}
	return false
}

func modSyncStatRegular(path string) (bool, error) {
	st, err := modSyncOsStat(path)
	if err != nil {
		return false, nil
	}
	return st.Mode().IsRegular(), nil
}

func modSyncFileSize(path string) (int64, error) {
	st, err := modSyncOsStat(path)
	if err != nil {
		return 0, err
	}
	return st.Size(), nil
}

// RE2 不认 Java 的 \u 转义，码点要写成 \x{...}
var modSyncSectionRe = regexp.MustCompile(`\x{00a7}.`)

// Java 的 \\p{Cntrl} = [\x00-\x1F\x7F]
var modSyncCtrlRe = regexp.MustCompile(`[\x00-\x1f\x7f]`)

// modSyncCleanText 对应 ModSyncSelectScreen.cleanText（:175-177）：
// 剥 MC 的 § 颜色码、剥控制字符、trim。前端是 HTML，但远端字符串仍可能带 §/控制字符。
func modSyncCleanText(s string) string {
	s = modSyncSectionRe.ReplaceAllString(s, "")
	s = modSyncCtrlRe.ReplaceAllString(s, "")
	return strings.TrimSpace(s)
}

// ---- 门控入口 ----

// modSyncGateRequest ModSyncGate 的入参。
type modSyncGateRequest struct {
	Code  string `json:"code"`
	Scope string `json:"scope"`
}

// modSyncGateResponse 门控结果（proceed=直通；choose=要玩家选）。
type modSyncGateResponse struct {
	Action    string `json:"action"`
	Outcome   string `json:"outcome"`
	Code      string `json:"code"`
	Scope     string `json:"scope"`
	Loader    string `json:"loader"`
	MCVersion string `json:"mcVersion"`
	// PendingNote：直通时要在连接启动后播报的一行（logui 键；level 0=提示 2=警告）
	PendingNote  *modSyncLangLine  `json:"pendingNote,omitempty"`
	NoteLevel    int               `json:"noteLevel"`
	Downloadable []*modSyncEntry   `json:"downloadable,omitempty"`
	Incompatible []*modSyncEntry   `json:"incompatible,omitempty"`
	NotFound     []*modSyncEntry   `json:"notfound,omitempty"`
	Unresolvable []modSyncLangLine `json:"unresolvable,omitempty"`
	VersionDiff  []modSyncLangLine `json:"versionDiff,omitempty"`
	SkipKeys     []string          `json:"skipKeys,omitempty"`
	TotalBytes   int64             `json:"totalBytes"`
}

// ModSyncGate 加入流程入口门控（gate()，:102-233）。
// 前端在 JoinRoom 之前调用；返回 choose 时同时后台启动 MR+CF 兜底解析
// （对应 SelectScreen 展示即 startResolve，:544-561）。
func (a *App) ModSyncGate(reqJSON string) string {
	var req modSyncGateRequest
	if err := parseParams(reqJSON, &req); err != nil {
		return wrapErr("INVALID_PARAMS", err.Error())
	}
	req.Code = strings.ToUpper(strings.TrimSpace(req.Code))
	s := a.modSync()

	if !s.isJoinRequiredModsCheck() || !modSyncIsVoxLinkCode(req.Code) {
		// :103-106：开关关闭 / 非 VoxLink 房号 → 直接放行
		return wrapOK(modSyncGateResponse{Action: "proceed", Outcome: modSyncGateDisabled, Code: req.Code})
	}
	if modSyncGate.isBypassed(req.Code) {
		// :109-112：玩家已选择跳过检查
		return wrapOK(modSyncGateResponse{Action: "proceed", Outcome: modSyncFetchBypassed, Code: req.Code})
	}

	env := s.env()
	if !env.configured() {
		modSyncLogWarn("gate skip: {} (no MC instance / mods dir configured)", modSyncFetchNotConfigured)
		return wrapOK(modSyncGateResponse{Action: "proceed", Outcome: modSyncFetchNotConfigured, Code: req.Code})
	}

	scope := modSyncNormalizeScope(req.Scope)

	// 前置校验（模组无此位点，理由见 modSyncRoomSupportsManifest 注释）：
	// 这间房根本没有房主上传的清单（HL/互通镜像、旧客户端、上传开关关闭）→ 直通，
	// 不发 /room/mods/request、不走重试预算，提示行与取清单后才判 UNSUPPORTED 那一路同键。
	// 注意这条**不替代**下面 res.outcome == modSyncFetchUnsupported 分支：
	// 探测到取清单之间服务端仍可能改口（房主关房/关开关），那条路要留着。
	if !a.modSyncRoomSupportsManifest(req.Code) {
		modSyncLogInfo("gate skip: {} (pre-check: host publishes no manifest)", modSyncFetchUnsupported)
		modSyncSetPendingNote(0, langLogUIModSyncUnsupported)
		return wrapOK(modSyncGateResponse{
			Action:      "proceed",
			Outcome:     modSyncFetchUnsupported,
			Code:        req.Code,
			Scope:       scope,
			PendingNote: &modSyncLangLine{Key: langLogUIModSyncUnsupported},
			NoteLevel:   0,
		})
	}

	modSyncGate.markGated(req.Code)

	// 重置在途提示；gate 入口留痕供真机定谳（:133-135）
	modSyncSetPendingNote(-1, "")
	modSyncLogInfo("gate: room={} scope={}", req.Code, scope) // ModSyncGuestService.java:135

	res := a.modSyncFetchWithRetry(req.Code, scope)
	if modSyncGate.isBypassed(req.Code) {
		// :138-141：玩家已在获取页点"直接进入"：丢弃结果，绝不弹窗打断已开始的加入
		modSyncGate.clearGated(req.Code)
		return wrapOK(modSyncGateResponse{Action: "proceed", Outcome: modSyncFetchBypassed, Code: req.Code, Scope: scope})
	}

	if res.outcome != modSyncFetchManifest {
		modSyncLogInfo("gate skip: {}", res.outcome) // ModSyncGuestService.java:145
		resp := modSyncGateResponse{Action: "proceed", Outcome: res.outcome, Code: req.Code, Scope: scope}
		switch res.outcome {
		case modSyncFetchUnsupported:
			modSyncSetPendingNote(0, langLogUIModSyncUnsupported)
			resp.PendingNote = &modSyncLangLine{Key: langLogUIModSyncUnsupported}
			resp.NoteLevel = 0
		case modSyncFetchNotReady:
			modSyncSetPendingNote(2, langLogUIModSyncFetchFailed)
			resp.PendingNote = &modSyncLangLine{Key: langLogUIModSyncFetchFailed}
			resp.NoteLevel = 2
		}
		modSyncGate.clearGated(req.Code)
		return wrapOK(resp)
	}

	modSyncGate.clearGated(req.Code)

	diff, err := func() (*modSyncDiffResult, error) {
		defer func() {
			if rec := recover(); rec != nil {
				modSyncLogWarn("guest diff panic recovered: {}", rec)
			}
		}()
		return modSyncComputeDiff(res.manifest, env), nil
	}()
	if err != nil || diff == nil {
		// :214-231：diff 失败 → 强提示后放行（静默放行有风险，玩家要能看见）
		modSyncLogWarn("guest diff failed, proceed without gate: {}", err) // ModSyncGuestService.java:215
		modSyncSetPendingNote(2, langModSyncGateFailedWarn)
		return wrapOK(modSyncGateResponse{
			Action:      "proceed",
			Outcome:     res.outcome,
			Code:        req.Code,
			Scope:       scope,
			PendingNote: &modSyncLangLine{Key: langModSyncGateFailedWarn},
			NoteLevel:   2,
		})
	}

	// MC 版本 / 加载器检测（:162-179）：不一致时清单仅供参考，置顶进 unresolvable
	hostMc := jstr(res.manifest, "mcVersion")
	if hostMc != "" && hostMc != env.GameVersion {
		modSyncLogWarn("MC version mismatch: host={} guest={}", hostMc, env.GameVersion) // ModSyncGuestService.java:166
		diff.Unresolvable = append([]modSyncLangLine{
			newModSyncLangLine(langModSyncMcMismatch, hostMc, env.GameVersion),
		}, diff.Unresolvable...)
	}
	hostLoader := jstr(res.manifest, "loader")
	if hostLoader != "" && !strings.EqualFold(hostLoader, env.Loader) {
		modSyncLogWarn("Loader mismatch: host={} guest={}", hostLoader, env.Loader) // ModSyncGuestService.java:175
		diff.Unresolvable = append([]modSyncLangLine{
			newModSyncLangLine(langModSyncLoaderMismatch, hostLoader, env.Loader),
		}, diff.Unresolvable...)
	}

	modSyncLogInfo("guest diff: downloadable={} versionDiff={} manual={} unresolvable={} titles={}", // ModSyncGuestService.java:181
		len(diff.Downloadable), len(diff.VersionDiff), len(diff.ManualEntries), len(diff.Unresolvable),
		modSyncClipRune(modSyncJoinTitles(diff), 200))

	if len(diff.Downloadable) == 0 && len(diff.ManualEntries) == 0 &&
		len(diff.Unresolvable) == 0 && len(diff.VersionDiff) == 0 {
		// :186-189：无差异 → 直通（无感知）
		return wrapOK(modSyncGateResponse{Action: "proceed", Outcome: modSyncFetchManifest,
			Code: req.Code, Scope: scope, Loader: hostLoader, MCVersion: hostMc})
	}

	// SelectScreen 构造期的分组（:105-112）：手动条目里"已知项目/有下载地址"的归"版本不匹配"组，
	// 其余归"找不到"组。
	incompatible := []*modSyncEntry{}
	notfound := []*modSyncEntry{}
	for i := len(diff.ManualEntries) - 1; i >= 0; i-- {
		me := diff.ManualEntries[i]
		if me.ProjectID != "" || me.DownloadURL != "" {
			incompatible = append([]*modSyncEntry{me}, incompatible...)
		}
	}
	for _, me := range diff.ManualEntries {
		if me.ProjectID == "" && me.DownloadURL == "" {
			notfound = append(notfound, me)
		}
	}

	var total int64
	for _, e := range diff.Downloadable {
		total += e.Size
	}

	resp := modSyncGateResponse{
		Action:       "choose",
		Outcome:      modSyncFetchManifest,
		Code:         req.Code,
		Scope:        scope,
		Loader:       hostLoader,
		MCVersion:    hostMc,
		Downloadable: diff.Downloadable,
		Incompatible: incompatible,
		NotFound:     notfound,
		Unresolvable: diff.Unresolvable,
		VersionDiff:  diff.VersionDiff,
		SkipKeys:     diff.SkipKeys,
		TotalBytes:   total,
	}

	// 展示即解析（SelectScreen:544-561）：结果经 modsync:resolved 事件回流
	if len(notfound) > 0 || len(incompatible) > 0 {
		a.modSyncStartResolve(env, notfound, incompatible, diff.Downloadable)
	}

	for _, list := range [][]*modSyncEntry{resp.Downloadable, resp.Incompatible, resp.NotFound} {
		for _, e := range list {
			e.Title = modSyncCleanText(e.Title)
			e.FileName = modSyncCleanText(e.FileName)
		}
	}
	return wrapOK(resp)
}

// modSyncJoinTitles 对应 joinTitles（:370-406）：日志用的紧凑摘要。
// 顺序与 Java 完全一致：可下载 `title@version`、手动 `?title`、版本差 `~line`、不可解 `?line`。
func modSyncJoinTitles(d *modSyncDiffResult) string {
	var sb strings.Builder
	sb.WriteString("[")
	n := 0
	sep := func() {
		if n > 0 {
			sb.WriteString(", ")
		}
		n++
	}
	for _, e := range d.Downloadable {
		sep()
		sb.WriteString(e.Title)
		sb.WriteString("@")
		sb.WriteString(e.VersionNumber)
	}
	for _, e := range d.ManualEntries {
		sep()
		sb.WriteString("?")
		sb.WriteString(e.Title)
	}
	for _, s := range d.VersionDiff {
		sep()
		sb.WriteString("~")
		sb.WriteString(modSyncRenderLangForLog(s))
	}
	for _, s := range d.Unresolvable {
		sep()
		sb.WriteString("?")
		sb.WriteString(modSyncRenderLangForLog(s))
	}
	sb.WriteString("]")
	return sb.String()
}

// modSyncRenderLangForLog 日志专用：把语言键 + 参数拼成一行可读文本。
// 渲染后的中文由前端负责（这里只是日志摘要，不参与任何判定）。
func modSyncRenderLangForLog(l modSyncLangLine) string {
	if len(l.Args) == 0 {
		return l.Key
	}
	return l.Key + "{" + strings.Join(l.Args, ",") + "}"
}

// ---- 解析回流（SelectScreen.startResolve / applyResolved，:544-605） ----

// modSyncStartResolve：手动条目 + 不兼容条目交给 MR/CF 双源兜底解析，
// 结果经 "modsync:resolved" 事件回流；前端按 applyOne 的规则换组（:578-591）。
func (a *App) modSyncStartResolve(env modSyncEnv, notfound, incompatible, knownDownloadable []*modSyncEntry) {
	s := a.modSync()
	cb := modSyncResolveCallback{
		onResolved: func(original, resolved *modSyncEntry, bucket int) {
			payload := map[string]any{"bucket": bucket}
			if original != nil {
				payload["original"] = modSyncResolveRef(original)
			}
			if resolved != nil {
				payload["resolved"] = modSyncResolveRef(resolved)
			}
			a.emit("modsync:resolved", payload)
		},
		onFinished: func(resolvedCount int) {
			modSyncLogInfo("resolve finished, {} became downloadable", resolvedCount) // ModSyncSelectScreen.java:557
			a.emit("modsync:resolve", map[string]any{"resolving": false, "resolved": resolvedCount})
		},
	}
	a.emit("modsync:resolve", map[string]any{"resolving": true})
	s.resolveAsync(env, notfound, incompatible, knownDownloadable, cb)
}

// modSyncResolveRef 条目的"前端可寻址"投影：以 fileName 为键（Java 用对象引用换组，
// 前端只能按值匹配；fileName 在同一批列表里唯一，unknownMods 也按文件名入表）。
func modSyncResolveRef(e *modSyncEntry) map[string]any {
	return map[string]any{
		"fileName":      e.FileName,
		"projectId":     e.ProjectID,
		"slug":          e.Slug,
		"title":         modSyncCleanText(e.Title),
		"iconUrl":       e.IconURL,
		"versionNumber": e.VersionNumber,
		"url":           e.DownloadURL,
		"sha1":          e.Sha1,
		"sha512":        e.Sha512,
		"size":          e.Size,
		"loaders":       e.Loaders,
		"gameVersions":  e.GameVersions,
		"foundOn":       e.foundOn,
	}
}

// ---- 门控辅助（bypass / markSkipped / clearGate / pendingNote） ----

// ModSyncBypass：获取页"直接进入"/取消 —— 仅作废本次在途清单回调（:78-83）。
func (a *App) ModSyncBypass(roomCode string) string {
	code := strings.ToUpper(strings.TrimSpace(roomCode))
	if code != "" {
		modSyncGate.mu.Lock()
		modSyncGate.bypassed[code] = true
		modSyncGate.mu.Unlock()
		modSyncLogInfo("gate: player bypassed, room={}", code) // ModSyncGuestService.java:81
	}
	return wrapOK(map[string]any{"bypassed": true})
}

// ModSyncClearGate：新一次加入前清该房门控会话状态（clearGateFor，:52-57）。
func (a *App) ModSyncClearGate(roomCode string) string {
	modSyncGate.clearGateFor(strings.ToUpper(strings.TrimSpace(roomCode)))
	return wrapOK(map[string]any{"cleared": true})
}

// ModSyncMarkSkipped：玩家在弹窗点了"直接加入"—— 本会话记住这些版本冲突键（:71-75）。
func (a *App) ModSyncMarkSkipped(keysJSON string) string {
	var keys []string
	if err := parseParams(keysJSON, &keys); err != nil {
		return wrapErr("INVALID_PARAMS", err.Error())
	}
	if len(keys) > 0 {
		modSyncGate.mu.Lock()
		for _, k := range keys {
			modSyncGate.skipped[k] = true
		}
		modSyncGate.mu.Unlock()
	}
	return wrapOK(map[string]any{"skipped": len(keys)})
}

// ModSyncTakePendingNote：连接启动后取出直通提示（pushPendingGateNote，:60-68）。
// 前端在 enterRoomPage 之后调用，避开房间页日志面板重置把提示吞掉。
func (a *App) ModSyncTakePendingNote() string {
	level, key := modSyncTakePendingNote()
	if key == "" {
		return wrapOK(map[string]any{"note": nil})
	}
	return wrapOK(map[string]any{"note": map[string]any{"level": level, "key": key}})
}

// ---- 下载执行环（ModSyncSelectScreen.downloadLoop，:313-390） ----

// modSyncDownloadRequest ModSyncDownload 的入参。
type modSyncDownloadRequest struct {
	Entries []*modSyncEntry `json:"entries"`
}

// ModSyncDownload 串行下载所选条目到本实例 mods 目录。
// 全程经 "modsync:progress" 事件回报，终局经 "modsync:download" 事件回报
// （成功 → 需要"重启游戏"屏；失败 → 回选择态 + 重试提示；取消 → 静默回选择态）。
func (a *App) ModSyncDownload(reqJSON string) string {
	var req modSyncDownloadRequest
	if err := parseParams(reqJSON, &req); err != nil {
		return wrapErr("INVALID_PARAMS", err.Error())
	}
	s := a.modSync()
	env := s.env()
	if !env.configured() {
		return wrapErr("NOT_CONFIGURED", "尚未配置 MC 实例的 mods 目录")
	}
	if len(req.Entries) == 0 {
		return wrapErr("INVALID_PARAMS", "没有要下载的模组")
	}
	modSyncDL.mu.Lock()
	if modSyncDL.running {
		modSyncDL.mu.Unlock()
		return wrapErr("BUSY", "下载正在进行中")
	}
	modSyncDL.running = true
	modSyncDL.last = nil // 新一轮开始，旧终局不得被事后读到（CLI 轮询靠这个判终局）
	modSyncDL.abort.Store(false)
	modSyncDL.startMs = time.Now().UnixMilli()
	modSyncDL.mu.Unlock()

	entries := req.Entries
	go a.modSyncDownloadLoop(env, entries)
	return wrapOK(map[string]any{"started": len(entries)})
}

// ModSyncCancelDownload：取消下载（abort 标志；底层 ModrinthClient 在 150/200ms 内掐断）。
func (a *App) ModSyncCancelDownload() string {
	modSyncDL.abort.Store(true)
	return wrapOK(map[string]any{"cancelling": true})
}

// ModSyncDownloadState：前端重连/切页后取回当前进度；CLI/启动器轮询同一个口拿终局快照。
func (a *App) ModSyncDownloadState() string {
	modSyncDL.mu.Lock()
	running := modSyncDL.running
	abort := modSyncDL.abort.Load()
	last := modSyncDL.last
	elapsed := modSyncDL.startMs
	modSyncDL.mu.Unlock()
	out := map[string]any{"running": running, "abort": abort}
	if elapsed > 0 {
		out["elapsedMs"] = time.Now().UnixMilli() - elapsed
	}
	if last != nil {
		out["last"] = last
	}
	return wrapOK(out)
}

func (a *App) modSyncDownloadLoop(env modSyncEnv, entries []*modSyncEntry) {
	defer func() {
		modSyncDL.mu.Lock()
		modSyncDL.running = false
		modSyncDL.mu.Unlock()
	}()

	modsDir := env.ModsDir
	if err := modSyncMkdirAll(modsDir); err != nil {
		a.modSyncEmitDownloadFinal(map[string]any{
			"outcome": "failed",
			"note":    newModSyncLangLine(langModSyncDownloadFailed, "mods dir"),
		})
		return
	}

	var totalBytes int64
	for _, e := range entries {
		totalBytes += e.Size
	}

	shouldAbort := func() bool { return modSyncDL.abort.Load() }
	var bytesDone int64
	doneFiles := 0
	succeeded := 0
	var lastError string

	for i, e := range entries {
		if shouldAbort() {
			a.modSyncEmitProgress(entries, i, doneFiles, bytesDone, totalBytes, "", 0)
			a.modSyncEmitDownloadFinal(map[string]any{
				"outcome":    "cancelled",
				"note":       newModSyncLangLine(langModSyncDownloadCancelled),
				"downloaded": succeeded,
			})
			return
		}
		current := e.Title
		a.modSyncEmitProgress(entries, i, doneFiles, bytesDone, totalBytes, current, -1)

		// H6：fileName 消毒 —— 防恶意/错乱清单把文件写到 mods 目录之外（:333-341）
		if !modSyncSafeFileName(e.FileName, modsDir) {
			modSyncLogWarn("skip unsafe fileName entry: {} (title={})", e.FileName, e.Title) // ModSyncSelectScreen.java:336
			lastError = e.Title
			continue
		}

		var sha1Arg string
		if e.Sha1 != "" {
			sha1Arg = e.Sha1
		}
		bytes, err := modrinthDownloadVerified(e.DownloadURL, e.Sha512, sha1Arg, e.FileName, modsDir, shouldAbort)
		if err != nil {
			if _, aborted := err.(*modrinthAbortedError); aborted {
				// 玩家取消：静默收场，不算失败（:364-368）
				modSyncLogInfo("download {} cancelled by user", e.FileName) // ModSyncSelectScreen.java:366
				a.modSyncEmitProgress(entries, i, doneFiles, bytesDone, totalBytes, current, 0)
				a.modSyncEmitDownloadFinal(map[string]any{
					"outcome":    "cancelled",
					"note":       newModSyncLangLine(langModSyncDownloadCancelled),
					"downloaded": succeeded,
				})
				return
			}
			modSyncLogWarn("download {} failed: {}", e.FileName, err.Error()) // ModSyncSelectScreen.java:370
			lastError = e.Title
			continue
		}
		succeeded++
		doneFiles++
		bytesDone += bytes

		elapsed := time.Now().UnixMilli() - modSyncDL.startMs
		if elapsed < 1 {
			elapsed = 1
		}
		speed := float64(bytesDone) / (float64(elapsed) / 1000.0)
		etaSec := int64(-1)
		if speed > 0 {
			remain := totalBytes - bytesDone
			if remain < 0 {
				remain = 0
			}
			etaSec = int64(float64(remain) / speed)
		}
		a.modSyncEmitProgress(entries, i+1, doneFiles, bytesDone, totalBytes, current, etaSec)
	}

	if shouldAbort() {
		a.modSyncEmitDownloadFinal(map[string]any{
			"outcome":    "cancelled",
			"note":       newModSyncLangLine(langModSyncDownloadCancelled),
			"downloaded": succeeded,
		})
		return
	}

	// :383-389：全部成功（且有成功项）→ 强制重启屏；否则回选择态 + 重试提示
	if succeeded > 0 && lastError == "" {
		// RestartScreen（:27-38）：restart_title / restart_line1(数量) / restart_line2，
		// 唯一出口按钮 = 退出游戏。本端等价：提示"重启 MC 实例后生效"。
		a.modSyncEmitDownloadFinal(map[string]any{
			"outcome":    "done",
			"downloaded": succeeded,
			"restart":    true,
			"restartKeys": []modSyncLangLine{
				{Key: "voxlink.modsync.restart_line1", Args: []string{strconv.Itoa(succeeded)}},
				{Key: "voxlink.modsync.restart_line2"},
			},
		})
		return
	}
	errName := "?"
	if lastError != "" {
		errName = modSyncClipRune(lastError, 40)
	}
	a.modSyncEmitDownloadFinal(map[string]any{
		"outcome":    "failed",
		"downloaded": succeeded,
		"note":       newModSyncLangLine(langModSyncDownloadFailed, errName),
		"hint":       newModSyncLangLine(langModSyncRetryHint),
	})
}

// modSyncEmitProgress 对应 renderProgress 的数据源（:636-664）：
// 总进度 %、已完成 ok/total、当前文件、剩余数、ETA。
func (a *App) modSyncEmitProgress(entries []*modSyncEntry, index, doneFiles int,
	bytesDone, totalBytes int64, current string, etaSec int64) {
	pct := 0
	if totalBytes > 0 {
		p := bytesDone * 100 / totalBytes
		if p > 100 {
			p = 100
		}
		pct = int(p)
	}
	remaining := len(entries) - doneFiles
	if remaining < 0 {
		remaining = 0
	}
	a.emit("modsync:progress", map[string]any{
		"overallPct": pct,
		"done":       doneFiles,
		"total":      len(entries),
		"current":    current,
		"remaining":  remaining,
		"etaSec":     etaSec,
		"bytesDone":  bytesDone,
		"bytesTotal": totalBytes,
	})
}

func modSyncMkdirAll(dir string) error {
	if dir == "" {
		return errEmptyModsDir
	}
	return os.MkdirAll(dir, 0o755)
}

var errEmptyModsDir = errors.New("mods dir empty")
