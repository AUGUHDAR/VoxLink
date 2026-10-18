package main

// modsync_manifest.go — 房主侧清单服务，逐句对应
// `modsync/ModSyncManifestService.java`（419 行）。
//
// 职责（Java 类注释 :26-31）：解析本地 mods → Modrinth 批量反查 → 构建两档清单
// （必装闭包 / 全部可识别），按 mods 状态哈希缓存到磁盘（跨房间复用）；
// 房客按需经信号 mods_request 请求时从缓存应答（未命中现场构建）。
// 全程异步、失败静默降级（房间照常可用）。
//
// 常量：
//   MAX_DEP_DEPTH     8    （:40 依赖闭包最大深度，visited 已防环，此为双保险）
//   UNKNOWN_MODS_CAP  192  （:42 1.2.0 由 64 提到 192；信令单条 48KB 上限的防御）
//   SCOPE_REQUIRED/"required"、SCOPE_ALL/"all"（:44-45）
//   应答超时 20s（:94 `.get(20L, TimeUnit.SECONDS)`）
//
// 两条必须有生产事故背书的纪律（都原样保留）：
//   1) MR 反查零命中守卫（:125-135）：有 jar 但两档 mods 全空 ⇒ 大概率
//      api.modrinth.com 整体不可达，绝不落盘/进内存，否则坏清单被 stateHash 永久复用；
//   2) 项目元数据批量查询必须同时包含"自有 mod ids + 被引用的前置 ids"（:268-273）：
//      1.1.2 二轮只查 depRefs，自有 mod 元数据缺失 → 根集 fail-open 全排除 →
//      必装清单恒为 0（1.1.2–1.1.6 的 "required list always empty" 故障）。

import (
	"encoding/json"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"
)

const (
	modSyncMaxDepDepth    = 8                // ModSyncManifestService.java:40
	modSyncUnknownModsCap = 192              // ModSyncManifestService.java:42
	modSyncAnswerTimeout  = 20 * time.Second // ModSyncManifestService.java:94
	modSyncPushTimeout    = 20 * time.Second // 主动推送沿用同一预算
)

// ---- 数据结构（Java 用 JsonObject，这里用等价 struct/map） ----

// modSyncScopeManifest 单档清单骨架（emptyManifest，:411-418）。
// unknownMods 在 Java 里是"构建末尾才 add"的键：早期返回路径不带该键
// （:223-227 无 jar 时直接返回），故用 omitempty 复刻"缺键"与"空数组"的一致下游语义。
type modSyncScopeManifest struct {
	ProtocolVersion string          `json:"protocolVersion"`
	Loader          string          `json:"loader"`
	MCVersion       string          `json:"mcVersion"`
	Mods            []*modSyncEntry `json:"mods"`
	UnknownMods     []string        `json:"unknownMods,omitempty"`
}

// modSyncCacheRoot 磁盘/内存缓存根对象：{required, all, jarCount, stateHash, builtAt}。
// JarCount 用指针：Java 在"无 jar"与"全部哈希失败"两条早退路径上不写该键（:223-243），
// 零命中守卫读的正是它（:128-129），缺键与 0 在守卫里等价（都 >0 才触发），
// 但为了不把"缺键"这件事抹掉，这里保留可空。
type modSyncCacheRoot struct {
	Required  *modSyncScopeManifest `json:"required"`
	All       *modSyncScopeManifest `json:"all"`
	JarCount  *int                  `json:"jarCount,omitempty"`
	StateHash string                `json:"stateHash,omitempty"`
	BuiltAt   int64                 `json:"builtAt,omitempty"`
}

func modSyncEmptyScopeManifest(env modSyncEnv) *modSyncScopeManifest {
	return &modSyncScopeManifest{
		ProtocolVersion: modSyncProtocolVersion, // :413
		Loader:          env.Loader,             // :414
		MCVersion:       env.GameVersion,        // :415
		Mods:            []*modSyncEntry{},
	}
}

// scope 归一：非 "all" 一律按 "required"（onModsRequest，:77-79）
func modSyncNormalizeScope(scope string) string {
	if scope == modSyncScopeAll {
		return modSyncScopeAll
	}
	return modSyncScopeRequired
}

func (r *modSyncCacheRoot) scopeManifest(scope string) *modSyncScopeManifest {
	if modSyncNormalizeScope(scope) == modSyncScopeAll {
		return r.All
	}
	return r.Required
}

// ---- 入口 ----

// modSyncOnRoomCreated 房主创建房间成功后的入口（:51-59）：
// 异步确保本地缓存已构建。Java 1.2.0 **不再建房即上报**（类注释 :27-28），
// 只有收到 mods_request 时才应答；主动推送是本端按 docs/launcher-integration.md §3.1
// "建房后把两档清单都推上去最稳"追加的一步，受同一 publish 开关约束。
func (a *App) modSyncOnRoomCreated() {
	s := a.modSync()
	s.hostExec(func() {
		if _, err := s.ensureCache(); err != nil {
			modSyncLogWarn("local manifest cache build failed: {}", err.Error()) // ModSyncManifestService.java:56
			return
		}
		s.proactivePushBothScopes()
	})
}

// modSyncOnModsRequest 房主收到房客按需请求（信号 mods_request，:65-100）：
// 从本地缓存取对应档清单（未命中现场构建）→ POST /room/mods/answer 回服务器。
func (a *App) modSyncOnModsRequest(data map[string]any) {
	s := a.modSync()
	if !s.isHostModSyncPublish() {
		modSyncLogInfo("host mod-sync disabled, ignore mods_request")
		return
	}
	requestID := jstr(data, "requestId")
	scope := jstr(data, "scope")
	if requestID == "" {
		return
	}
	scope = modSyncNormalizeScope(scope)

	code, token, ok := a.modSyncRoom()
	if !ok {
		// :81-85：不在当房主状态 → 留痕丢弃
		modSyncLogWarn("mods_request {} ignored (not hosting)", requestID) // ModSyncManifestService.java:83
		return
	}

	s.hostExec(func() {
		root, err := s.ensureCache()
		if err != nil {
			modSyncLogWarn("answer mods_request {} failed: {}", requestID, err.Error()) // ModSyncManifestService.java:97
			return
		}
		manifest := root.scopeManifest(scope)
		if manifest == nil {
			modSyncLogWarn("answer mods_request {} failed: manifest missing for scope {}", requestID, scope)
			return
		}
		err = a.modSyncAnswer(code, token, requestID, scope, manifest)
		if err != nil {
			modSyncLogWarn("answer mods_request {} failed: {}", requestID, err.Error()) // ModSyncManifestService.java:97
			return
		}
		modSyncLogInfo("mods_request {} ({}) answered, success={}", requestID, scope, true) // ModSyncManifestService.java:95
	})
}

// modSyncHandleSignal 由会话信令分发处调用（见交付报告 hunk：session.go handleSignals）。
// 只认服务端注入给房主的 mods_request（server-go/modsmanifest.go:194 SignalAppend
// "server"→"host" type=mods_request）。
func (a *App) modSyncHandleSignal(sigType, from string, data map[string]any) {
	if sigType != "mods_request" {
		return
	}
	// 房主侧只接受 server 来源（模组侧同规则：room/RoomManager.java 的信令来源门控）
	if from != "server" && from != "host" {
		return
	}
	a.modSyncOnModsRequest(data)
}

// modSyncHandleSignalAsync 把清单构建/回传挪出信令分发线程：信令线程里做几十秒的网络事务，
// 本房所有信令都会停摆（模组把同类活丢给 executor，是同一条 P1-2 教训）。
func (a *App) modSyncHandleSignalAsync(sigType, from string, data map[string]any) {
	if sigType != "mods_request" {
		return
	}
	go a.modSyncHandleSignal(sigType, from, data)
}

// ---- 本地缓存（:102-205） ----

// ensureCache：状态哈希命中内存直接复用 → 磁盘命中 → 现场构建并落盘。
func (s *modSyncState) ensureCache() (*modSyncCacheRoot, error) {
	env := s.env()
	stateHash, err := modSyncComputeStateHash(env)
	if err != nil {
		return nil, err
	}

	s.cacheMu.Lock()
	defer s.cacheMu.Unlock()

	if s.cachedRoot != nil && s.cachedStateHash == stateHash {
		return s.cachedRoot, nil
	}

	if disk := modSyncReadDiskCache(env.CacheFile, stateHash); disk != nil {
		s.cachedStateHash = stateHash
		s.cachedRoot = disk
		modSyncLogInfo("manifest cache hit (state {})", stateHash) // ModSyncManifestService.java:120
		return disk, nil
	}

	built, err := s.buildBothManifests(env, stateHash)
	if err != nil {
		return nil, err
	}

	// MR 反查零命中守卫（:125-135）
	jarCount := 0
	if built.JarCount != nil {
		jarCount = *built.JarCount
	}
	if jarCount > 0 && modSyncModsLen(built.Required) == 0 && modSyncModsLen(built.All) == 0 {
		modSyncLogWarn("MR zero-hit for {} jars, skip caching (modrinth unreachable?), rebuild on next request", jarCount) // ModSyncManifestService.java:133
		return built, nil
	}

	s.cachedStateHash = stateHash
	s.cachedRoot = built
	modSyncWriteDiskCache(env.CacheFile, built, stateHash)
	return built, nil
}

func modSyncModsLen(m *modSyncScopeManifest) int {
	if m == nil {
		return 0
	}
	return len(m.Mods)
}

// modSyncComputeStateHash mods 状态哈希：全部 .jar 的 sha1 排序后 join("\n") 再 sha1
// （computeStateHash，:144-155）。注意用的是 listModJars()（活跃 jar，不含 .jar.disabled）。
func modSyncComputeStateHash(env modSyncEnv) (string, error) {
	jars := modSyncListJars(env.ModsDir)
	sha1s := []string{}
	for _, jar := range jars {
		h, err := modSyncSha1File(jar)
		if err != nil {
			continue // Java：catch (Exception ignored)（:150）
		}
		sha1s = append(sha1s, h)
	}
	sort.Strings(sha1s)
	return modSyncSha1OfBytes([]byte(strings.Join(sha1s, "\n"))), nil
}

// modSyncReadDiskCache 对应 readDiskCache(stateHash)（:161-189）。
func modSyncReadDiskCache(cacheFile, stateHash string) *modSyncCacheRoot {
	if cacheFile == "" {
		return nil
	}
	data, err := os.ReadFile(cacheFile)
	if err != nil {
		return nil
	}
	var probe map[string]any
	if json.Unmarshal(data, &probe) != nil {
		return nil
	}
	if jstr(probe, "stateHash") != stateHash {
		return nil
	}
	if jobj(probe, "required") == nil || jobj(probe, "all") == nil {
		return nil
	}
	var root modSyncCacheRoot
	if json.Unmarshal(data, &root) != nil || root.Required == nil || root.All == nil {
		return nil
	}
	// 历史坏缓存防御（:175-181）：两档 mods 全空 = 当年 MR 不可达时落盘的残废清单，
	// 丢弃重建。
	if modSyncModsLen(root.Required) == 0 && modSyncModsLen(root.All) == 0 {
		modSyncLogWarn("disk cache has empty mods (legacy zero-hit?), discard and rebuild")
		return nil
	}
	return &root
}

// modSyncWriteDiskCache 对应 writeDiskCache(...)（:191-205）：
// stateHash + builtAt（秒）后 tmp + 原子改名。
func modSyncWriteDiskCache(cacheFile string, root *modSyncCacheRoot, stateHash string) {
	if cacheFile == "" {
		return
	}
	root.StateHash = stateHash
	root.BuiltAt = time.Now().Unix() // System.currentTimeMillis()/1000（:194）
	if dir := filepath.Dir(cacheFile); dir != "" {
		if err := os.MkdirAll(dir, 0o755); err != nil {
			modSyncLogWarn("manifest cache write failed: {}", err.Error()) // ModSyncManifestService.java:203
			return
		}
	}
	data, err := json.Marshal(root)
	if err != nil {
		modSyncLogWarn("manifest cache write failed: {}", err.Error()) // ModSyncManifestService.java:203
		return
	}
	tmp := cacheFile + ".tmp"
	if err := os.WriteFile(tmp, data, 0o644); err != nil {
		modSyncLogWarn("manifest cache write failed: {}", err.Error()) // ModSyncManifestService.java:203
		return
	}
	if err := os.Rename(tmp, cacheFile); err != nil {
		_ = os.Remove(tmp)
		modSyncLogWarn("manifest cache write failed: {}", err.Error()) // ModSyncManifestService.java:203
	}
}

// ---- 构建（:209-362） ----

// buildBothManifests 一次扫描构建两档清单。
// required = 以"客户端必装"为根、沿 required 依赖的 BFS 闭包；
// all      = 房主全部 MR 可识别模组（server-only 除外），不区分 client_side。
func (s *modSyncState) buildBothManifests(env modSyncEnv, stateHash string) (*modSyncCacheRoot, error) {
	start := time.Now().UnixMilli()
	required := modSyncEmptyScopeManifest(env)
	all := modSyncEmptyScopeManifest(env)
	out := &modSyncCacheRoot{Required: required, All: all}

	jars := modSyncListJars(env.ModsDir)
	if len(jars) == 0 {
		modSyncLogInfo("no local mods, manifests built in {}ms", time.Now().UnixMilli()-start) // ModSyncManifestService.java:225
		return out, nil
	}

	// 1) 本地 jar 的 sha1 → MR 版本对象
	// sha1ToJarName 是 Java 的 LinkedHashMap（键首次插入定序、值可被后来者覆盖，:230-240）
	type sha1Jar struct {
		sha1 string
		name string
	}
	order := []string{}
	sha1ToJarName := map[string]string{}
	sha1List := []string{}
	for _, jar := range jars {
		h, err := modSyncSha1File(jar)
		if err != nil {
			modSyncLogWarn("hash failed {}: {}", filepath.Base(jar), err.Error()) // ModSyncManifestService.java:238
			continue
		}
		if _, seen := sha1ToJarName[h]; !seen {
			order = append(order, h)
		}
		sha1ToJarName[h] = filepath.Base(jar)
		sha1List = append(sha1List, h)
	}
	if len(sha1List) == 0 {
		return out, nil
	}

	versionsBySha1 := modrinthVersionsFromSha1(sha1List)

	// 2) 未知模组：MR 上查不到的一律进仅提示名单
	unknownMods := []string{}
	for _, h := range order {
		if _, ok := versionsBySha1[h]; !ok {
			unknownMods = append(unknownMods, sha1ToJarName[h])
		}
	}

	// project_id → 版本对象（同项目多文件取首个；LinkedHashMap putIfAbsent，:256-266）
	versionByProject := map[string]map[string]any{}
	versionOrder := []string{}
	depRefsOrder := []string{}
	depRefs := map[string]bool{}
	for _, v := range versionsBySha1 {
		pid := jstr(v, "project_id")
		if pid == "" {
			continue
		}
		if _, ok := versionByProject[pid]; !ok {
			versionByProject[pid] = v
			versionOrder = append(versionOrder, pid)
		}
		for _, dep := range modSyncRequiredDepIds(v) {
			if !depRefs[dep] {
				depRefs[dep] = true
				depRefsOrder = append(depRefsOrder, dep)
			}
		}
	}

	// 3) 项目元数据一次批量查全：自有 mod + 被引用的前置（见文件头的事故 2）
	projectIDs := append([]string(nil), versionOrder...)
	seenID := map[string]bool{}
	for _, id := range projectIDs {
		seenID[id] = true
	}
	for _, id := range depRefsOrder {
		if !seenID[id] {
			seenID[id] = true
			projectIDs = append(projectIDs, id)
		}
	}
	projects := modrinthProjectsByIds(projectIDs)

	// 4) required 根集（依赖感知）：client_required 但"同时是其他房主 mod 的 required
	//    依赖"的库类不作根——它们是否必装由 BFS 按需决定（:275-287）
	depIDsOfHostMods := map[string]bool{}
	for _, v := range versionsBySha1 {
		for _, dep := range modSyncRequiredDepIds(v) {
			depIDsOfHostMods[dep] = true
		}
	}
	queue := []string{}
	for _, pid := range versionOrder {
		if !depIDsOfHostMods[pid] && modSyncIsClientRequiredRoot(projects[pid], pid) {
			queue = append(queue, pid)
		}
	}

	// 5) BFS 必需前置闭包（visited 按 project_id 去重；:289-314）
	visited := map[string]bool{}
	selectedVersions := []map[string]any{}
	depth := 0
	limit := modSyncMaxDepDepth * len(versionByProject)
	for len(queue) > 0 && depth <= limit {
		pid := queue[0]
		queue = queue[1:]
		depth++
		if pid == "" || visited[pid] {
			continue
		}
		visited[pid] = true

		if modrinthIsServerOnly(projects[pid]) {
			continue
		}
		if version, ok := versionByProject[pid]; ok {
			selectedVersions = append(selectedVersions, version)
			for _, dep := range modSyncRequiredDepIds(version) {
				if !visited[dep] {
					queue = append(queue, dep)
				}
			}
		}
	}

	// 6) 组装：required=闭包结果；all=全部可识别（server-only 除外）
	requiredCount := 0
	for _, version := range selectedVersions {
		pid := jstr(version, "project_id")
		entry := modSyncEntryFromVersion(version, projects[pid])
		if entry.DownloadURL == "" {
			continue
		}
		required.Mods = append(required.Mods, entry)
		requiredCount++
	}

	allCount := 0
	for _, pid := range versionOrder {
		meta := projects[pid]
		if modrinthIsServerOnly(meta) {
			continue
		}
		entry := modSyncEntryFromVersion(versionByProject[pid], meta)
		if entry.DownloadURL == "" {
			continue
		}
		all.Mods = append(all.Mods, entry)
		allCount++
	}

	// 保护清单大小：unknownMods 只用于提示，截断到 UNKNOWN_MODS_CAP（:345-354）
	trimmed := unknownMods
	if len(unknownMods) > modSyncUnknownModsCap {
		trimmed = append([]string(nil), unknownMods[:modSyncUnknownModsCap]...)
	}
	required.UnknownMods = append([]string(nil), trimmed...)
	all.UnknownMods = append([]string(nil), trimmed...)
	jarCount := len(jars)
	out.JarCount = &jarCount

	short := stateHash
	if len(short) > 8 {
		short = short[:8]
	}
	modSyncLogInfo("manifests ready: {} jars, required={} all={} unknown={} state={} {}ms", // ModSyncManifestService.java:357
		len(jars), requiredCount, allCount, len(unknownMods), short, time.Now().UnixMilli()-start)
	return out, nil
}

// modSyncRequiredDepIds：version.dependencies 中 dependency_type=="required" 的
// project_id 列表（requiredDepIds，:371-391）。
func modSyncRequiredDepIds(version map[string]any) []string {
	out := []string{}
	deps := jarr(version, "dependencies")
	if deps == nil {
		return out
	}
	for _, el := range deps {
		d, ok := el.(map[string]any)
		if !ok {
			continue
		}
		typ := jstr(d, "dependency_type")
		pid := jstr(d, "project_id")
		if strings.EqualFold(typ, "required") && pid != "" {
			out = append(out, pid)
		}
	}
	return out
}

// modSyncIsClientRequiredRoot 根集判定（:397-405）：optional/unsupported → 否；
// 元数据缺失按 "fail-open" 判否。
func modSyncIsClientRequiredRoot(project map[string]any, projectID string) bool {
	if project == nil {
		modSyncLogWarn("isClientRequiredRoot: project meta missing for {} (fail-open, not a root)", projectID) // ModSyncManifestService.java:399
		return false
	}
	cs := jstr(project, "client_side")
	return !strings.EqualFold(cs, "optional") && !strings.EqualFold(cs, "unsupported")
}

// ---- 信令 POST ----

// modSyncAnswer 房主应答：POST /room/mods/answer（SignalingClient.java:488-496）。
func (a *App) modSyncAnswer(code, token, requestID, scope string, manifest *modSyncScopeManifest) error {
	_, err := a.modSyncCall("/room/mods/answer", map[string]any{
		"code":      strings.ToUpper(code),
		"token":     token,
		"requestId": requestID,
		"scope":     scope,
		"manifest":  manifest,
		"action":    "answer_room_mods",
	}, modSyncAnswerTimeout)
	return err
}

// modSyncPublish 旧兼容推送：POST /room/mods/publish（SignalingClient.java:461-468）。
func (a *App) modSyncPublish(code, token string, manifest *modSyncScopeManifest) error {
	_, err := a.modSyncCall("/room/mods/publish", map[string]any{
		"code":     strings.ToUpper(code),
		"token":    token,
		"manifest": manifest,
		"action":   "publish_mod_manifest",
	}, modSyncAnswerTimeout)
	return err
}

// proactivePushBothScopes 建房后把两档清单都推上去（docs §3.1"最稳"路径）。
// /room/mods/answer 允许无在途 requestId 调用：服务端只写缓存、不唤醒任何人
// （server-go/modsmanifest.go:217-270 的 pending 查不到即跳过唤醒）。
func (s *modSyncState) proactivePushBothScopes() {
	app := s.currentApp()
	if app == nil || !s.isHostModSyncPublish() {
		return
	}
	code, token, ok := app.modSyncRoom()
	if !ok {
		return
	}
	root, err := s.ensureCache()
	if err != nil {
		modSyncLogWarn("proactive push skipped: {}", err.Error())
		return
	}
	for _, scope := range []string{modSyncScopeRequired, modSyncScopeAll} {
		manifest := root.scopeManifest(scope)
		if manifest == nil {
			continue
		}
		if err := app.modSyncAnswer(code, token, "push-"+strconv.FormatInt(time.Now().UnixNano(), 36), scope, manifest); err != nil {
			modSyncLogWarn("proactive push {} failed: {}", scope, err.Error())
			continue
		}
		modSyncLogInfo("proactive push {} ok ({} mods)", scope, modSyncModsLen(manifest))
	}
}

// ---- 绑定方法（房主侧） ----

// ModSyncHostStatus 返回本端实例与清单概况：{configured, instance, jarCount, required, all}。
func (a *App) ModSyncHostStatus() string {
	s := a.modSync()
	env := s.env()
	if !env.configured() {
		return wrapOK(map[string]any{"configured": false, "loader": env.Loader, "mcVersion": env.GameVersion})
	}
	resp := map[string]any{
		"configured":  true,
		"loader":      env.Loader,
		"mcVersion":   env.GameVersion,
		"modsDir":     env.ModsDir,
		"cacheFile":   env.CacheFile,
		"publishOn":   s.isHostModSyncPublish(),
		"joinCheckOn": s.isJoinRequiredModsCheck(),
	}
	if root, err := s.ensureCache(); err == nil {
		resp["required"] = modSyncModsLen(root.Required)
		resp["all"] = modSyncModsLen(root.All)
		if root.JarCount != nil {
			resp["jarCount"] = *root.JarCount
		}
	} else {
		resp["error"] = err.Error()
	}
	return wrapOK(resp)
}

// ModSyncRebuild 强制重建并（当房主时）推送两档清单，供"刷新清单"按钮使用。
func (a *App) ModSyncRebuild() string {
	s := a.modSync()
	if !s.env().configured() {
		return wrapErr("NOT_CONFIGURED", "尚未配置 MC 实例的 mods 目录")
	}
	s.hostExec(func() {
		s.cacheMu.Lock()
		s.cachedStateHash = ""
		s.cachedRoot = nil
		s.cacheMu.Unlock()
		root, err := s.ensureCache()
		if err != nil {
			modSyncLogWarn("manual rebuild failed: {}", err.Error())
			return
		}
		modSyncLogInfo("manual rebuild ok: required={} all={}", modSyncModsLen(root.Required), modSyncModsLen(root.All))
		s.proactivePushBothScopes()
	})
	return wrapOK(map[string]any{"queued": true})
}
