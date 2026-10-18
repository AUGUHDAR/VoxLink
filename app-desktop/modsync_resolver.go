package main

// modsync_resolver.go — 手动安装条目的 MR+CF 双源兜底解析，逐句对应
// `modsync/ModSyncResolver.java`（876 行）。
//
// 职责（Java 类注释 :22-29）：6 线程并行逐条搜索/反查 → 本端兼容版本 →
// 真名+真图标+可下载回流；前置跟随（required 依赖，深度 ≤2、单次会话最多补 24 个）；
// **网络失败 ≠ 未找到**（不写负缓存，失败批在 pass0 结束后整体重试一次）；
// 逐条结果落日志供真机对账。
//
// 常量：
//   BUCKET_NOT_FOUND/INCOMPATIBLE/DOWNLOADABLE = 0/1/2  （:32-36）
//   线程池 6                                      （:40-44）
//   MAX_PER_MINUTE=240 / WINDOW_MS=60000          （:45-46，超限自旋 200ms，:808-825）
//   MAX_NET_FAILURES=16                           （:50）
//   MAX_DEP_ADD=24 / MAX_DEP_DEPTH=2              （:52-54）

import (
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

const (
	modSyncBucketNotFound      = 0 // ModSyncResolver.java:32
	modSyncBucketIncompatible  = 1 // :34
	modSyncBucketDownloadable  = 2 // :36
	modSyncResolvePoolSize     = 6 // :40
	modSyncMaxPerMinute        = 240
	modSyncResolveWindowMillis = int64(60000)
	modSyncMaxNetFailures      = 16
	modSyncMaxDepAdd           = 24
	modSyncMaxResolveDepDepth  = 2
)

// modSyncResolvedCache = RESOLVED（:38）：key → 解析出的条目。
var (
	modSyncResolvedMu sync.Mutex
	modSyncResolved   = map[string]*modSyncEntry{}
	modSyncNegativeMu sync.Mutex
	modSyncNegative   = map[string]bool{} // NEGATIVE（:39）
)

// modSyncResolveCallback：Java 的 Callback 接口（:57-61）。
// original==nil 表示"新增前置条目"（Java 注释 :56）。
type modSyncResolveCallback struct {
	onResolved func(original, resolved *modSyncEntry, bucket int)
	onFinished func(resolvedCount int)
}

// ---- 6 线程解析池 ----

type modSyncPool struct {
	mu     sync.Mutex
	queue  []func()
	wake   chan struct{}
	once   sync.Once
	closed bool
}

var modSyncResolvePool = &modSyncPool{wake: make(chan struct{}, 1)}

func (p *modSyncPool) start() {
	p.once.Do(func() {
		for i := 0; i < modSyncResolvePoolSize; i++ {
			go p.worker()
		}
	})
}

func (p *modSyncPool) execute(task func()) {
	p.start()
	p.mu.Lock()
	if p.closed {
		p.mu.Unlock()
		return
	}
	p.queue = append(p.queue, task)
	p.mu.Unlock()
	select {
	case p.wake <- struct{}{}:
	default:
	}
}

func (p *modSyncPool) worker() {
	for {
		p.mu.Lock()
		var task func()
		if len(p.queue) > 0 {
			task = p.queue[0]
			p.queue = p.queue[1:]
		}
		p.mu.Unlock()
		if task == nil {
			select {
			case <-p.wake:
			case <-time.After(100 * time.Millisecond):
			}
			continue
		}
		func() {
			defer func() {
				if rec := recover(); rec != nil {
					modSyncLogWarn("resolve task error: {}", rec) // :152-153
				}
			}()
			task()
		}()
	}
}

// ---- 滚动窗限速（guard，:808-825） ----

var (
	modSyncWindowStart atomic.Int64
	modSyncWindowCount atomic.Int64
)

func modSyncGuard() {
	now := time.Now().UnixMilli()
	win := modSyncWindowStart.Load()
	if now-win >= modSyncResolveWindowMillis {
		modSyncWindowStart.Store(now)
		modSyncWindowCount.Store(0)
	}
	for modSyncWindowCount.Add(1) > modSyncMaxPerMinute {
		modSyncWindowCount.Add(-1)
		time.Sleep(200 * time.Millisecond)
		n2 := time.Now().UnixMilli()
		if n2-modSyncWindowStart.Load() >= modSyncResolveWindowMillis {
			modSyncWindowStart.Store(n2)
			modSyncWindowCount.Store(0)
		}
	}
}

// ---- 会话上下文（Ctx，:64-104） ----

type modSyncResolveCtx struct {
	mu            sync.Mutex
	seen          map[string]bool
	norms         map[string]bool
	added         atomic.Int64
	totalResolved atomic.Int64
	netFailures   atomic.Int64

	env modSyncEnv // 本端 (loader, gameVersion, modsDir)：Java 直接读 ModSyncEnv 静态量

	localNorms  map[string]bool
	localSha1s  map[string]bool
	localScanMu sync.Mutex
}

func newModSyncResolveCtx() *modSyncResolveCtx {
	return &modSyncResolveCtx{seen: map[string]bool{}, norms: map[string]bool{}}
}

// scanLocal 对应 Ctx.scanLocal()（:73-103）：本地 jar 的归一名集合 + sha1 集合，只扫一次。
func (c *modSyncResolveCtx) scanLocal(env modSyncEnv) {
	c.localScanMu.Lock()
	defer c.localScanMu.Unlock()
	if c.localNorms != nil {
		return
	}
	n := map[string]bool{}
	s := map[string]bool{}
	for _, jar := range modSyncListJars(env.ModsDir) {
		if nb := modSyncNormalizeFileName(filepath.Base(jar)); nb != "" {
			n[nb] = true
		}
		if h, err := modSyncSha1File(jar); err == nil {
			s[h] = true
		}
	}
	c.localNorms = n
	c.localSha1s = s
}

// resolveAsync 公开入口（:109-138）：manualEntries + incompatibleEntries 一起解析，
// knownDownloadable 作为"清单里已有"的防重复基线。
func (s *modSyncState) resolveAsync(env modSyncEnv, manual, incompatible, knownDownloadable []*modSyncEntry,
	cb modSyncResolveCallback) {
	all := []*modSyncEntry{}
	all = append(all, manual...)
	all = append(all, incompatible...)
	if len(all) == 0 {
		if cb.onFinished != nil {
			cb.onFinished(0)
		}
		return
	}

	ctx := newModSyncResolveCtx()
	ctx.env = env
	for _, k := range knownDownloadable {
		modSyncMarkSeen(ctx, k)
	}
	for _, k := range all {
		modSyncMarkSeen(ctx, k)
	}
	modSyncResolveBatch(all, 0, ctx, cb)
}

// modSyncResolveBatch（:140-159）
func modSyncResolveBatch(entries []*modSyncEntry, pass int, ctx *modSyncResolveCtx, cb modSyncResolveCallback) {
	remaining := atomic.Int64{}
	remaining.Store(int64(len(entries)))
	failed := &modSyncFailedList{}
	for _, e := range entries {
		if e == nil {
			modSyncDecRemaining(&remaining, failed, pass, ctx, cb)
			continue
		}
		entry := e
		modSyncResolvePool.execute(func() {
			modSyncRunOne(entry, failed, ctx, cb)
			modSyncDecRemaining(&remaining, failed, pass, ctx, cb)
		})
	}
}

// modSyncFailedList = CopyOnWriteArrayList<ModSyncEntry>（:142）
type modSyncFailedList struct {
	mu    sync.Mutex
	items []*modSyncEntry
}

func (l *modSyncFailedList) add(e *modSyncEntry) {
	l.mu.Lock()
	l.items = append(l.items, e)
	l.mu.Unlock()
}

func (l *modSyncFailedList) snapshot() []*modSyncEntry {
	l.mu.Lock()
	defer l.mu.Unlock()
	return append([]*modSyncEntry(nil), l.items...)
}

func (l *modSyncFailedList) empty() bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	return len(l.items) == 0
}

// modSyncRunOne（:161-206）
func modSyncRunOne(e *modSyncEntry, failed *modSyncFailedList, ctx *modSyncResolveCtx, cb modSyncResolveCallback) {
	key := modSyncKeyOf(e)

	modSyncResolvedMu.Lock()
	hit := modSyncResolved[key]
	modSyncResolvedMu.Unlock()

	modSyncNegativeMu.Lock()
	negative := modSyncNegative[key]
	modSyncNegativeMu.Unlock()

	if hit == nil && !negative {
		resolved, err := modSyncQuery(e, key, ctx.env)
		if err != nil {
			// 网络失败 ≠ 未找到：进 failed，不写 NEGATIVE（:170-179）
			failed.add(e)
			n := ctx.netFailures.Add(1)
			modSyncLogWarn("resolve {} failed: {}", key, err.Error()) // ModSyncResolver.java:173
			if int(n) >= modSyncMaxNetFailures {
				modSyncLogWarn("resolve giving up after {} network failures", n) // ModSyncResolver.java:175
			}
			return
		}
		hit = resolved
	}

	if hit == nil {
		modSyncLogInfo("modsync resolve: NOT-FOUND {} ({})", // ModSyncResolver.java:183
			modSyncResolveClip(e.Title), modSyncResolveClip(e.FileName))
		return
	}

	modSyncApplyMeta(e, hit)
	bucket := modSyncBucketOf(hit)
	if bucket == modSyncBucketDownloadable {
		ctx.totalResolved.Add(1)
	}

	cf := hit.foundOn == "cf"
	outcome := "meta-only-cf"
	switch {
	case bucket == modSyncBucketDownloadable:
		if cf {
			outcome = "downloadable-cf"
		} else {
			outcome = "downloadable-mr"
		}
	case bucket == modSyncBucketIncompatible:
		if cf {
			outcome = "cf-file"
		} else {
			outcome = "no-compatible-file-mr"
		}
	}
	title := hit.Title
	if title == "" {
		title = e.Title
	}
	modSyncLogInfo("modsync resolve: {} {} ({})", outcome, modSyncResolveClip(title), modSyncResolveClip(e.FileName)) // ModSyncResolver.java:197
	if cb.onResolved != nil {
		cb.onResolved(e, hit, bucket)
	}
	if bucket == modSyncBucketDownloadable {
		modSyncProcessDeps(hit, ctx, 1, cb)
	}
}

// modSyncDecRemaining（:208-223）
func modSyncDecRemaining(remaining *atomic.Int64, failed *modSyncFailedList, pass int,
	ctx *modSyncResolveCtx, cb modSyncResolveCallback) {
	if remaining.Add(-1) != 0 {
		return
	}
	if !failed.empty() && pass == 0 && ctx.netFailures.Load() < int64(modSyncMaxNetFailures) {
		modSyncLogInfo("resolve retry pass for {} failed entries", len(failed.snapshot())) // ModSyncResolver.java:214
		modSyncResolveBatch(failed.snapshot(), 1, ctx, cb)
		return
	}
	if !failed.empty() {
		modSyncLogWarn("resolve: {} entries unresolved (network), retry next join", len(failed.snapshot())) // ModSyncResolver.java:218
	}
	if cb.onFinished != nil {
		cb.onFinished(int(ctx.totalResolved.Load()))
	}
}

// modSyncProcessDeps 前置跟随（:226-301）：先查清单/下载清单/本地，没有才补下。
func modSyncProcessDeps(hit *modSyncEntry, ctx *modSyncResolveCtx, depth int, cb modSyncResolveCallback) {
	if depth > modSyncMaxResolveDepDepth || int(ctx.added.Load()) >= modSyncMaxDepAdd {
		return
	}

	for _, pid := range hit.depProjectID {
		if int(ctx.added.Load()) >= modSyncMaxDepAdd {
			return
		}
		ctx.mu.Lock()
		already := ctx.seen["mr:"+pid]
		ctx.seen["mr:"+pid] = true
		ctx.mu.Unlock()
		if already {
			continue
		}
		dep, err := modSyncResolveMrDep(pid, ctx.env)
		if err != nil {
			modSyncLogWarn("dep {} failed: {}", pid, err.Error()) // ModSyncResolver.java:258
			continue
		}
		if dep == nil || dep.DownloadURL == "" {
			continue
		}
		if modSyncIsCovered(dep, ctx) {
			continue
		}
		modSyncMarkSeen(ctx, dep)
		ctx.added.Add(1)
		ctx.totalResolved.Add(1)
		modSyncLogInfo("modsync resolve: dep-added-mr {} ({})", modSyncResolveClip(dep.Title), modSyncResolveClip(dep.FileName)) // ModSyncResolver.java:254
		if cb.onResolved != nil {
			cb.onResolved(nil, dep, modSyncBucketDownloadable)
		}
		modSyncProcessDeps(dep, ctx, depth+1, cb)
	}

	for _, id := range hit.cfDepID {
		if int(ctx.added.Load()) >= modSyncMaxDepAdd {
			return
		}
		i := strings.Index(id, ":")
		if i <= 0 {
			continue
		}
		modID, err1 := strconv.ParseInt(id[:i], 10, 64)
		fileID, err2 := strconv.ParseInt(id[i+1:], 10, 64)
		if err1 != nil || err2 != nil {
			continue
		}
		dep, err := modSyncResolveCfDep(modID, fileID, ctx.env)
		if err != nil {
			modSyncLogWarn("cf dep {} failed: {}", id, err.Error()) // ModSyncResolver.java:298
			continue
		}
		if dep == nil || dep.DownloadURL == "" {
			continue
		}
		if modSyncIsCovered(dep, ctx) {
			continue
		}
		modSyncMarkSeen(ctx, dep)
		ctx.added.Add(1)
		ctx.totalResolved.Add(1)
		modSyncLogInfo("modsync resolve: dep-added-cf {} ({})", modSyncResolveClip(dep.Title), modSyncResolveClip(dep.FileName)) // ModSyncResolver.java:294
		if cb.onResolved != nil {
			cb.onResolved(nil, dep, modSyncBucketDownloadable)
		}
		modSyncProcessDeps(dep, ctx, depth+1, cb)
	}
}

// modSyncResolveMrDep（:304-320）：project 元数据 + 本端兼容版本；无文件时 CF 兜底。
func modSyncResolveMrDep(projectID string, env modSyncEnv) (*modSyncEntry, error) {
	modSyncGuard()
	meta, err := modrinthProject(projectID)
	if err != nil {
		return nil, err
	}
	if meta == nil {
		return nil, nil
	}
	modSyncGuard()
	version, err := modrinthLatestVersionFor(projectID, env.GameVersion, env.Loader)
	if err != nil {
		return nil, err
	}
	out := modSyncEntryFromMetaAndVersion(meta, version)
	out.depProjectID = append(out.depProjectID, modSyncRequiredProjectIDs(version)...)
	if out.DownloadURL == "" {
		if _, err := modSyncTryCfFile(out, out.Title, out.FileName, env); err != nil {
			return nil, err
		}
	}
	return out, nil
}

// modSyncResolveCfDep（:323-359）：fileDetail 直取文件 + modDetail 补真名真图标；
// 无地址走 CDN 推导。
func modSyncResolveCfDep(modID, fileID int64, env modSyncEnv) (*modSyncEntry, error) {
	modSyncGuard()
	f, err := cfFileDetail(int(modID), fileID)
	if err != nil {
		return nil, err
	}
	if f == nil || jstr(f, "fileName") == "" {
		return nil, nil
	}
	out := newModSyncEntry()
	out.foundOn = "cf"
	out.FileName = jstr(f, "fileName")
	out.DownloadURL = jstr(f, "downloadUrl")
	if out.DownloadURL == "" {
		out.DownloadURL = cfCDNURL(fileID, out.FileName)
	}
	out.Size = jnum64(f, "fileLength")
	modSyncFillCfHash(out, f)

	modSyncGuard()
	m, err := cfModDetail(int(modID))
	if err != nil {
		return nil, err
	}
	if m != nil {
		out.Title = jstr(m, "name")
		out.Slug = jstr(m, "slug")
		logo := jobj(m, "logo")
		if logo != nil {
			thumb := jstr(logo, "thumbnailUrl")
			if thumb == "" {
				out.IconURL = jstr(logo, "url")
			} else {
				out.IconURL = thumb
			}
		}
	}
	if out.Title == "" {
		out.Title = jstr(f, "displayName")
	}
	if out.Title == "" {
		out.Title = modSyncGuessTitleFromFileName(out.FileName)
	}
	out.cfDepID = append(out.cfDepID, modSyncCfDepIDsOf(f)...)
	return out, nil
}

// modSyncIsCovered（:362-374）：清单/已发前置按归一名，本地 mods 按归一名+sha1。
func modSyncIsCovered(dep *modSyncEntry, ctx *modSyncResolveCtx) bool {
	nb := modSyncNormalizeFileName(dep.FileName)
	ctx.mu.Lock()
	inNorms := nb != "" && ctx.norms[nb]
	ctx.mu.Unlock()
	if inNorms {
		return true
	}
	ctx.scanLocal(ctx.env)
	if nb != "" && ctx.localNorms[nb] {
		return true
	}
	if dep.Sha1 != "" && ctx.localSha1s[dep.Sha1] {
		return true
	}
	return false
}

// modSyncMarkSeen（:376-389）
func modSyncMarkSeen(ctx *modSyncResolveCtx, e *modSyncEntry) {
	if e == nil {
		return
	}
	ctx.mu.Lock()
	defer ctx.mu.Unlock()
	if e.ProjectID != "" {
		ctx.seen["mr:"+e.ProjectID] = true
	}
	if nb := modSyncNormalizeFileName(e.FileName); nb != "" {
		ctx.norms[nb] = true
	}
}

// modSyncBucketOf（:391-397）
func modSyncBucketOf(hit *modSyncEntry) int {
	if hit.DownloadURL != "" {
		return modSyncBucketDownloadable
	}
	if hit.foundOn == "cf" {
		return modSyncBucketNotFound
	}
	return modSyncBucketIncompatible
}

// modSyncQuery 查询阶梯（:399-490）。
// 返回 nil,nil 表示"确实没找到"（写负缓存）；error 表示网络失败（不写负缓存）。
func modSyncQuery(e *modSyncEntry, key string, env modSyncEnv) (*modSyncEntry, error) {
	loader := env.Loader
	gv := env.GameVersion
	var meta map[string]any
	projectID := ""

	if e.ProjectID != "" {
		projectID = e.ProjectID
		if strings.TrimSpace(e.Title) != "" && strings.TrimSpace(e.IconURL) != "" {
			// 新版清单已带真名真图标：跳过元数据请求，只查兼容文件（:406-409）
			meta = modSyncMetaFrom(e)
		} else {
			modSyncGuard()
			got, err := modrinthProject(projectID)
			if err != nil {
				return nil, err
			}
			meta = got
		}
	} else {
		q := modSyncGuessTitleFromFileName(e.FileName)
		if q == "" {
			q = modSyncStripJar(e.FileName)
		}
		modSyncGuard()
		hits, err := modrinthSearch(q, loader, gv)
		if err != nil {
			return nil, err
		}
		hit := modSyncPickHit(hits, q)
		if hit == nil {
			// 放宽：去掉版本过滤再试（:423-427）
			modSyncGuard()
			hits, err = modrinthSearch(q, loader, "")
			if err != nil {
				return nil, err
			}
			hit = modSyncPickHit(hits, q)
		}
		if hit == nil {
			// 再用原始文件名试（:429-437）
			raw := modSyncStripJar(e.FileName)
			if strings.TrimSpace(raw) != "" && !strings.EqualFold(raw, q) {
				modSyncGuard()
				hits, err = modrinthSearch(raw, loader, "")
				if err != nil {
					return nil, err
				}
				hit = modSyncPickHit(hits, raw)
			}
		}
		if hit != nil {
			meta = hit
			projectID = jstr(hit, "project_id")
		}
	}

	if meta == nil || projectID == "" {
		// MR 无此项目 → CF 镜像免 key 补真名真图标（:445-455）
		cf, err := modSyncCfLookup(e, env)
		if err != nil {
			return nil, err
		}
		if cf != nil {
			modSyncResolvedMu.Lock()
			modSyncResolved[key] = cf
			modSyncResolvedMu.Unlock()
			return cf, nil
		}
		modSyncNegativeMu.Lock()
		modSyncNegative[key] = true
		modSyncNegativeMu.Unlock()
		return nil, nil
	}

	modSyncGuard()
	version, err := modrinthLatestVersionFor(projectID, gv, loader)
	if err != nil {
		return nil, err
	}
	out := newModSyncEntry()
	out.ProjectID = projectID
	out.Slug = jstr(meta, "slug")
	out.Title = jstr(meta, "title")
	out.IconURL = jstr(meta, "icon_url")
	out.VersionNumber = e.VersionNumber
	if version != nil {
		out.VersionNumber = jstr(version, "version_number")
		out.Loaders = jstrList(version, "loaders")
		out.GameVersions = jstrList(version, "game_versions")
		if file := modrinthPrimaryFile(version); file != nil {
			out.FileName = jstr(file, "filename")
			out.DownloadURL = jstr(file, "url")
			out.Size = jnum64(file, "size")
			out.Sha1, out.Sha512 = jhashes(file)
		}
		out.depProjectID = append(out.depProjectID, modSyncRequiredProjectIDs(version)...)
	}

	if out.FileName == "" {
		// MR 无兼容文件 → CF 兜底找本端可用文件（:480-486）
		found, cerr := modSyncTryCfFile(out, jstr(meta, "title"), e.FileName, env)
		if cerr != nil {
			return nil, cerr
		}
		if !found {
			out.FileName = e.FileName
			out.Size = e.Size
		}
	}

	modSyncResolvedMu.Lock()
	modSyncResolved[key] = out
	modSyncResolvedMu.Unlock()
	return out, nil
}

// modSyncMetaFrom（:493-499）
func modSyncMetaFrom(e *modSyncEntry) map[string]any {
	return map[string]any{"slug": e.Slug, "title": e.Title, "icon_url": e.IconURL}
}

// modSyncCfLookup（:502-537）：CF 免 key 查询真名+真图标。
func modSyncCfLookup(e *modSyncEntry, env modSyncEnv) (*modSyncEntry, error) {
	q := modSyncGuessTitleFromFileName(e.FileName)
	if q == "" {
		q = modSyncStripJar(e.FileName)
	}
	modSyncGuard()
	hits, err := cfSearchByLoader(q, env.GameVersion, env.Loader)
	if err != nil {
		return nil, err
	}
	m := modSyncPickCf(hits, q)
	if m == nil {
		raw := modSyncStripJar(e.FileName)
		modSyncGuard()
		hits2, err2 := cfSearch(raw, "", 0)
		if err2 != nil {
			return nil, err2
		}
		m = modSyncPickCf(hits2, raw)
	}
	if m == nil {
		return nil, nil
	}

	out := newModSyncEntry()
	out.foundOn = "cf"
	out.Title = jstr(m, "name")
	out.Slug = jstr(m, "slug")
	logo := jobj(m, "logo")
	icon := ""
	if logo != nil {
		thumb := jstr(logo, "thumbnailUrl")
		if thumb == "" {
			icon = jstr(logo, "url")
		} else {
			icon = thumb
		}
	}
	out.IconURL = icon
	out.FileName = e.FileName
	if out.Title == "" && out.IconURL == "" {
		return nil, nil
	}
	if out.Title == "" {
		out.Title = modSyncGuessTitleFromFileName(e.FileName)
	}
	if _, err := modSyncTryCfFile(out, out.Title, e.FileName, env); err != nil {
		return nil, err
	}
	return out, nil
}

// modSyncTryCfFile（:540-615）：按名称在 CF 找本端 版本+加载器 的文件。
// 返回是否补齐；网络失败上抛（与 Java 一致：失败不写成"没找到"）。
func modSyncTryCfFile(out *modSyncEntry, queryName, fileNameHint string, env modSyncEnv) (bool, error) {
	variants := []string{}
	seen := map[string]bool{}
	addVariant := func(v string) {
		if strings.TrimSpace(v) == "" || seen[v] {
			return
		}
		seen[v] = true
		variants = append(variants, v)
	}
	addVariant(queryName)
	if strings.TrimSpace(fileNameHint) != "" {
		addVariant(modSyncGuessTitleFromFileName(fileNameHint))
		addVariant(modSyncStripJar(fileNameHint))
	}
	if len(variants) == 0 {
		return false, nil
	}

	var m map[string]any
	for _, v := range variants {
		modSyncGuard()
		hits, err := cfSearchByLoader(v, env.GameVersion, env.Loader)
		if err != nil {
			return false, err
		}
		if got := modSyncPickCf(hits, v); got != nil {
			m = got
			break
		}
	}
	if m == nil {
		return false, nil
	}

	idx := cfPickFileIndex(m, env.GameVersion, env.Loader)
	if idx == nil {
		return false, nil
	}
	fileID, err := strconv.ParseInt(jstr(idx, "fileId"), 10, 64)
	if err != nil {
		return false, nil
	}
	modID, err := strconv.Atoi(jstr(m, "id"))
	if err != nil {
		return false, nil
	}

	modSyncGuard()
	f, err := cfFileDetail(modID, fileID)
	if err != nil {
		return false, err
	}
	if f == nil || jstr(f, "downloadUrl") == "" {
		return false, nil
	}
	out.foundOn = "cf"
	out.DownloadURL = jstr(f, "downloadUrl")
	out.FileName = jstr(f, "fileName")
	out.Size = jnum64(f, "fileLength")
	modSyncFillCfHash(out, f)
	out.cfDepID = append(out.cfDepID, modSyncCfDepIDsOf(f)...)
	return true, nil
}

// modSyncCfDepIDsOf（:618-645）：CF file 的 required 前置（relationType==3 → modId:fileId）。
func modSyncCfDepIDsOf(f map[string]any) []string {
	out := []string{}
	for _, el := range jarr(f, "dependencies") {
		d, ok := el.(map[string]any)
		if !ok {
			continue
		}
		if jstr(d, "relationType") != "3" {
			continue
		}
		mid, err1 := strconv.ParseInt(jstr(d, "modId"), 10, 64)
		fid, err2 := strconv.ParseInt(jstr(d, "fileId"), 10, 64)
		if err1 != nil || err2 != nil {
			continue
		}
		if mid > 0 && fid > 0 {
			out = append(out, strconv.FormatInt(mid, 10)+":"+strconv.FormatInt(fid, 10))
		}
	}
	return out
}

// modSyncRequiredProjectIDs（:648-669）
func modSyncRequiredProjectIDs(version map[string]any) []string {
	out := []string{}
	for _, el := range jarr(version, "dependencies") {
		d, ok := el.(map[string]any)
		if !ok {
			continue
		}
		if strings.EqualFold(jstr(d, "dependency_type"), "required") {
			if pid := jstr(d, "project_id"); pid != "" {
				out = append(out, pid)
			}
		}
	}
	return out
}

// modSyncFillCfHash（:671-688）：CF 只有 sha1（hashes[].algo=="1"），sha512 清空
// —— 下载侧因此走 sha1 回退校验（ModrinthClient.java:400-417）。
func modSyncFillCfHash(out *modSyncEntry, f map[string]any) {
	for _, el := range jarr(f, "hashes") {
		h, ok := el.(map[string]any)
		if !ok {
			continue
		}
		if jstr(h, "algo") == "1" {
			out.Sha1 = jstr(h, "value")
			out.Sha512 = ""
			return
		}
	}
}

// modSyncApplyMeta（:691-707）：原条目原地升级真名/真图标。
func modSyncApplyMeta(orig, resolved *modSyncEntry) {
	if resolved.Title != "" {
		orig.Title = resolved.Title
	}
	if resolved.IconURL != "" {
		orig.IconURL = resolved.IconURL
	}
	if resolved.Slug != "" {
		orig.Slug = resolved.Slug
	}
	if orig.ProjectID == "" {
		orig.ProjectID = resolved.ProjectID
	}
}

// modSyncPickHit / modSyncPickCf（:710-717）
func modSyncPickHit(hits []any, query string) map[string]any {
	return modSyncPickCandidate(hits, query, "title")
}

func modSyncPickCf(hits []any, query string) map[string]any {
	return modSyncPickCandidate(hits, query, "name")
}

// modSyncPickCandidate（:719-742）：slug 精确匹配优先，否则首个通过质量闸门的候选。
func modSyncPickCandidate(hits []any, query, titleField string) map[string]any {
	if len(hits) == 0 {
		return nil
	}
	// Java: query.toLowerCase(ROOT).replace(' ', '-')
	want := strings.ReplaceAll(strings.ToLower(query), " ", "-")
	var first map[string]any
	for _, el := range hits {
		h, ok := el.(map[string]any)
		if !ok {
			continue
		}
		if want == jstr(h, "slug") {
			return h
		}
		if first == nil && modSyncAccept(query, jstr(h, titleField), jstr(h, "slug")) {
			first = h
		}
	}
	return first
}

// modSyncAccept 匹配质量闸门（:749-751）：
// 查询与标题/slug 分别归一化（仅留字母数字）后互为包含才认。
func modSyncAccept(query, title, slug string) bool {
	return modSyncMatchOne(query, title, 3) || modSyncMatchOne(query, slug, 4)
}

func modSyncMatchOne(query, candidate string, minCand int) bool {
	a := modSyncNorm(query)
	b := modSyncNorm(candidate)
	if a == "" || b == "" {
		return false
	}
	if len(a) >= 3 && strings.Contains(b, a) {
		return true
	}
	return len(b) >= minCand && strings.Contains(a, b)
}

// modSyncNorm（:767-780）：小写后只留 a-z0-9。
func modSyncNorm(s string) string {
	if s == "" {
		return ""
	}
	var sb strings.Builder
	for _, r := range strings.ToLower(s) {
		if (r >= 'a' && r <= 'z') || (r >= '0' && r <= '9') {
			sb.WriteRune(r)
		}
	}
	return sb.String()
}

// modSyncStopWords 与房客 diff 同款噪音词表（ModSyncGuestService.java:577-578）
var modSyncStopWords = map[string]bool{
	"fabric": true, "forge": true, "neoforge": true, "quilt": true, "fml": true,
	"mc": true, "all": true, "for": true, "the": true, "with": true,
}

var modSyncNonLetterRe = regexp.MustCompile(`[^a-z]+`)

// modSyncNormalizeFileName 归一化基名（ModSyncGuestService.java:566-589 /
// ModSyncResolver.java:783-805）：小写、去 .jar、按非字母分词、剔噪音词后拼接。
func modSyncNormalizeFileName(fileName string) string {
	if fileName == "" {
		return ""
	}
	s := strings.ToLower(fileName)
	if strings.HasSuffix(s, ".jar") {
		s = s[:len(s)-4]
	}
	var sb strings.Builder
	for _, tok := range modSyncNonLetterRe.Split(s, -1) {
		if tok == "" || modSyncStopWords[tok] {
			continue
		}
		sb.WriteString(tok)
	}
	return sb.String()
}

func modSyncKeyOf(e *modSyncEntry) string {
	if e.ProjectID != "" {
		return "id:" + e.ProjectID
	}
	return "file:" + e.FileName
}

// modSyncStripJar（:859-863）：去掉最后一个 ".jar" 及其后内容（i>0 才切）。
func modSyncStripJar(fileName string) string {
	s := fileName
	i := strings.LastIndex(strings.ToLower(s), ".jar")
	if i > 0 {
		return s[:i]
	}
	return s
}
