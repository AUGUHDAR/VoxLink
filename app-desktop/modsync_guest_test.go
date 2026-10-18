package main

// modsync_guest_test.go — 离线 diff 的六类分流与文件名归一化，
// 对照 ModSyncGuestService.computeDiff（:427-559）与 normalizeFileName（:566-589）；
// 另覆盖 ModSyncGate 入口门控的前置校验（HL/互通房零清单请求）与取清单后的 UNSUPPORTED 路径。

import (
	"crypto/sha1"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"sync"
	"testing"
)

func sha1Of(s string) string {
	h := sha1.Sum([]byte(s))
	return hex.EncodeToString(h[:])
}

func modSyncTestEnv(t *testing.T) modSyncEnv {
	t.Helper()
	return modSyncEnv{
		Loader:      "fabric",
		GameVersion: "1.20.1",
		ModsDir:     t.TempDir(),
	}
}

func modSyncTestManifest(mods []any, unknown []any) map[string]any {
	m := map[string]any{
		"loader":          "fabric",
		"mcVersion":       "1.20.1",
		"mods":            mods,
		"unknownMods":     unknown,
		"protocolVersion": modSyncProtocolVersion,
	}
	return m
}

func modSyncTestWireEntry(projectID, fileName, sha1Hex string, size int64, url string, loaders, gvs []any) map[string]any {
	return map[string]any{
		"projectId":     projectID,
		"slug":          projectID,
		"title":         projectID,
		"versionNumber": "1.0",
		"fileName":      fileName,
		"url":           url,
		"sha1":          sha1Hex,
		"sha512":        "",
		"size":          float64(size),
		"loaders":       loaders,
		"gameVersions":  gvs,
	}
}

func TestModSyncDiffInstalledBySha1IsSilent(t *testing.T) {
	env := modSyncTestEnv(t)
	writeJar(t, env.ModsDir, "sodium.jar", "same-bytes")
	manifest := modSyncTestManifest([]any{
		modSyncTestWireEntry("pSodium", "sodium.jar", sha1Of("same-bytes"), 10,
			"https://cdn.modrinth.com/x/sodium.jar", []any{"fabric"}, []any{"1.20.1"}),
	}, []any{})

	d := modSyncComputeDiff(manifest, env)
	if len(d.Downloadable)+len(d.ManualEntries)+len(d.Unresolvable)+len(d.VersionDiff) != 0 {
		t.Fatalf("已装（sha1 命中）不该产生任何提示：%+v", d)
	}
}

func TestModSyncDiffInstalledButDisabled(t *testing.T) {
	env := modSyncTestEnv(t)
	// 玩家把 sodium.jar 重命名为 sodium.jar.disabled
	writeJar(t, env.ModsDir, "sodium.jar.disabled", "disabled-bytes")
	manifest := modSyncTestManifest([]any{
		modSyncTestWireEntry("pDis", "sodium.jar", sha1Of("disabled-bytes"), 9,
			"https://cdn.modrinth.com/x/sodium.jar", []any{"fabric"}, []any{"1.20.1"}),
	}, []any{})

	d := modSyncComputeDiff(manifest, env)
	if len(d.Downloadable) != 0 {
		t.Fatalf("被禁用=已装，不应再下载：%v", d.Downloadable)
	}
	if len(d.Unresolvable) != 1 || d.Unresolvable[0].Key != langModSyncDisabledHint {
		t.Fatalf("应给 disabled_hint：%+v", d.Unresolvable)
	}
	if len(d.Unresolvable[0].Args) != 2 || d.Unresolvable[0].Args[1] != "sodium.jar" {
		t.Fatalf("hint 参数应是 (title, fileName)：%+v", d.Unresolvable[0])
	}
}

func TestModSyncDiffVersionMismatchNeverTouchesFiles(t *testing.T) {
	modSyncResetGateStateForTest() // skipped 是进程级一次性集合，-count=N 第二轮会撞在旧键上
	env := modSyncTestEnv(t)
	// 归一化名相同（fabric/mc 噪音词会被剔掉）、sha1 不同 → 版本不一致组
	writeJar(t, env.ModsDir, "anothermod-fabric-1.19.9.jar", "old-version-bytes")
	manifest := modSyncTestManifest([]any{
		modSyncTestWireEntry("pVer", "anothermod-fabric-1.20.1.jar", sha1Of("new-version-bytes"), 8,
			"https://cdn.modrinth.com/x/anothermod.jar", []any{"fabric"}, []any{"1.20.1"}),
	}, []any{})

	d := modSyncComputeDiff(manifest, env)
	if len(d.VersionDiff) != 1 || d.VersionDiff[0].Key != langModSyncDiffLine {
		t.Fatalf("应进 versionDiff 组：%+v", d.VersionDiff)
	}
	if len(d.SkipKeys) != 1 || d.SkipKeys[0] != "pVer@1.0" {
		t.Fatalf("skipKey 必须是 projectId@versionNumber：%v", d.SkipKeys)
	}
	if len(d.Downloadable) != 0 {
		t.Fatal("版本不一致组不得自动下载")
	}
}

func TestModSyncDiffDownloadableVersusManual(t *testing.T) {
	env := modSyncTestEnv(t)
	compatible := modSyncTestWireEntry("pOK", "okmod-1.0.jar", sha1Of("nope1"), 7,
		"https://cdn.modrinth.com/x/okmod.jar", []any{"fabric"}, []any{"1.20.1"})
	wrongLoader := modSyncTestWireEntry("pWR", "wrmod-1.0.jar", sha1Of("nope2"), 7,
		"https://cdn.modrinth.com/x/wrmod.jar", []any{"forge"}, []any{"1.20.1"})
	noURL := modSyncTestWireEntry("pNoURL", "nourl-1.0.jar", sha1Of("nope3"), 7,
		"", []any{"fabric"}, []any{"1.20.1"})
	manifest := modSyncTestManifest([]any{compatible, wrongLoader, noURL}, []any{"自写模组.jar"})

	d := modSyncComputeDiff(manifest, env)
	if len(d.Downloadable) != 1 || d.Downloadable[0].ProjectID != "pOK" {
		t.Fatalf("只有 loaders+gameVersions+url 全合格才可下载：%+v", d.Downloadable)
	}
	// 2 条不合格 + 1 条 unknownMods
	if len(d.ManualEntries) != 3 {
		t.Fatalf("不合格与 unknownMods 都应进手动组：%+v", d.ManualEntries)
	}
}

func TestModSyncDiffLegacyManifestNoSha1(t *testing.T) {
	env := modSyncTestEnv(t)
	p := writeJar(t, env.ModsDir, "legacy.jar", "abcdefgh")
	st, err := modSyncFileSize(p)
	if err != nil {
		t.Fatal(err)
	}
	// 无 sha1 → 精确文件名匹配即视为已装（:505-506）
	byName := modSyncTestWireEntry("pL1", "legacy.jar", "", st, "https://cdn.modrinth.com/x/legacy.jar",
		[]any{"fabric"}, []any{"1.20.1"})
	d := modSyncComputeDiff(modSyncTestManifest([]any{byName}, []any{}), env)
	if len(d.Downloadable)+len(d.ManualEntries) != 0 {
		t.Fatalf("精确文件名命中=已装：%+v", d)
	}

	// 无 sha1 且文件名不同、但归一名+字节数一致 → 仍视为已装（:511-518）
	byNorm := modSyncTestWireEntry("pL2", "legacy-1.0.jar", "", st, "https://cdn.modrinth.com/x/legacy.jar",
		[]any{"fabric"}, []any{"1.20.1"})
	d2 := modSyncComputeDiff(modSyncTestManifest([]any{byNorm}, []any{}), env)
	if len(d2.Downloadable)+len(d2.VersionDiff)+len(d2.ManualEntries) != 0 {
		t.Fatalf("归一名+大小一致应视为已装：%+v", d2)
	}
}

func TestModSyncNormalizeFileName(t *testing.T) {
	cases := []struct{ in, want string }{
		{"Sodium-fabric-mc1.20.1-0.5.3.jar", "sodium"},
		{"Iron-Chests-Mod-1.20.1.jar", "ironchestsmod"},
		{"Xaero_Minimap_Fabric_1.20.1.jar", "xaerominimap"},
		{"", ""},
		{"no-dot-jar", "nodotjar"},
	}
	for _, c := range cases {
		if got := modSyncNormalizeFileName(c.in); got != c.want {
			t.Errorf("normalize(%q)=%q want %q", c.in, got, c.want)
		}
	}
}

func TestModSyncCleanTextStripsSectionCodes(t *testing.T) {
	// ModSyncSelectScreen.cleanText（:175-177）：Java 源码里的 "\u00a7." 到正则阶段已是「§ + 任意一击」，
	// 所以 § 和它后面那个字符一起被吃掉——"§aName" → "Name"，不是 "aName"。
	if got := modSyncCleanText("§aName\tX"); got != "NameX" {
		t.Fatalf("cleanText=%q want %q", got, "NameX")
	}
	if got := modSyncCleanText("a§bC"); got != "aC" {
		t.Fatalf("cleanText=%q want %q", got, "aC")
	}
	if got := modSyncCleanText(""); got != "" {
		t.Fatalf("空串应原样返回：%q", got)
	}
}

func TestModSyncSkipKeySessionDedupe(t *testing.T) {
	modSyncResetGateStateForTest() // 本用例正是验"同 skipKey 只提示一次"，必须先清进程级集合
	env := modSyncTestEnv(t)
	// 归一化按 [^a-z]+ 切词：版本号那截是分隔符，所以 dupmod-1.0 与 dupmod-2.0 同基名=同一模组不同版本。
	// 大小刻意与清单 entry.size(5) 不一致，避免走"归一名+大小一致视为已装"的兜底。
	writeJar(t, env.ModsDir, "dupmod-1.0.jar", "v1-bytes")
	entry := modSyncTestWireEntry("pDup", "dupmod-2.0.jar", sha1Of("v2-bytes"), 5,
		"https://cdn.modrinth.com/x/dupmod.jar", []any{"fabric"}, []any{"1.20.1"})
	manifest := modSyncTestManifest([]any{entry}, []any{})

	first := modSyncComputeDiff(manifest, env)
	if len(first.VersionDiff) != 1 {
		t.Fatalf("首次应提示：%+v", first.VersionDiff)
	}
	second := modSyncComputeDiff(manifest, env)
	if len(second.VersionDiff) != 0 {
		t.Fatalf("同 skipKey 本会话只提示一次（:537-543）：%+v", second.VersionDiff)
	}
}

// ================= ModSyncGate 入口：前置校验（不发清单请求）=================
//
// 缺陷现场：HappyLink/互通镜像房也会弹"必装模组检查"。这类房在服务端的记录里没有
// hostCapabilities（modsmanifest 的 supported 恒 false），门控却在"取回清单之后"
// 才判出来 —— 先把 /room/mods/request 那一整套带重试的请求发完。
// 本组用例钉的是：supported=false 时 /room/mods/request 零命中，且提示键与旧路径一致。

// modSyncRoutes 记假服务端被命中的 route（httptest 每请求一个 goroutine，加锁）。
type modSyncRoutes struct {
	mu   sync.Mutex
	hits []string
}

func (r *modSyncRoutes) hit(route string) {
	r.mu.Lock()
	r.hits = append(r.hits, route)
	r.mu.Unlock()
}

func (r *modSyncRoutes) count(route string) int {
	r.mu.Lock()
	defer r.mu.Unlock()
	n := 0
	for _, h := range r.hits {
		if h == route {
			n++
		}
	}
	return n
}

func (r *modSyncRoutes) total() int {
	r.mu.Lock()
	defer r.mu.Unlock()
	return len(r.hits)
}

func (r *modSyncRoutes) all() []string {
	r.mu.Lock()
	defer r.mu.Unlock()
	return append([]string(nil), r.hits...)
}

// newModSyncGateApp 造一个走 httptest 假服务端、且已配好 MC 实例（env.configured()=true）的 App。
// 测试卫生：settingsPath 与 modsync.json 全落 t.TempDir()，绝不碰 %APPDATA%/VoxLinkApp。
func newModSyncGateApp(t *testing.T, routes *modSyncRoutes, serve func(w http.ResponseWriter, route string)) *App {
	t.Helper()
	a := newRelayTestApp(t, func(w http.ResponseWriter, r *http.Request) {
		route := r.URL.Query().Get("route")
		routes.hit(route)
		serve(w, route)
	})
	if !jsonOK(t, a.SaveModSyncSettings(modSyncGateSettingsJSON(t))) {
		t.Fatal("SaveModSyncSettings 失败")
	}
	modSyncResetGateStateForTest()
	modSyncTakePendingNote() // 清掉上一用例残留的待播报行
	t.Cleanup(modSyncResetGateStateForTest)
	t.Cleanup(func() { modSyncTakePendingNote() })
	return a
}

func modSyncGateSettingsJSON(t *testing.T) string {
	t.Helper()
	dir := t.TempDir()
	mods := filepath.Join(dir, "mods")
	if err := os.MkdirAll(mods, 0o755); err != nil {
		t.Fatal(err)
	}
	return `{"instances":[{"name":"隔离实例","loader":"fabric","gameVersion":"1.20.1","modsDir":"` +
		filepath.ToSlash(mods) + `"}],"activeInstance":"隔离实例","joinRequiredModsCheck":true,"hostModSyncPublish":true}`
}

func modSyncGateResp(t *testing.T, raw string) modSyncGateResponse {
	t.Helper()
	var env struct {
		OK   bool                `json:"ok"`
		Data modSyncGateResponse `json:"data"`
	}
	if err := json.Unmarshal([]byte(raw), &env); err != nil {
		t.Fatalf("门控结果解析失败：%v / %s", err, raw)
	}
	if !env.OK {
		t.Fatalf("门控应返回 ok:true，实得：%s", raw)
	}
	return env.Data
}

// modSyncManifestPayload /room/mods/request 的回包形状（服务端 modsManifestResponse 同构）。
func modSyncManifestPayload(mods []any, unknown []any) map[string]any {
	return map[string]any{
		"supported": true, "ready": true,
		"loader": "fabric", "mcVersion": "1.20.1",
		"mods": mods, "unknownMods": unknown,
		"protocolVersion": modSyncProtocolVersion,
	}
}

// ① HL/互通镜像房：前置校验就直通 —— 不发 /room/mods/request，提示键与旧 UNSUPPORTED 路径一致。
// 码用 6 位同字集码（服务端给 HL 租户房发的就是这种码，RoomCodeRouter 那一型拦不住）。
func TestModSyncGateHappyLinkRoomProceedsWithoutManifestRequest(t *testing.T) {
	routes := &modSyncRoutes{}
	a := newModSyncGateApp(t, routes, func(w http.ResponseWriter, route string) {
		switch route {
		case "/room/mods": // HL 记录里没有 hostCapabilities → supported=false
			sendJSON(w, map[string]any{"success": true,
				"data": map[string]any{"supported": false, "ready": false}})
		case "/room/mods/request":
			// 真被命中就说明前置校验失效：给一份会让门控弹窗的清单，让断言必然红。
			sendJSON(w, map[string]any{"success": true, "data": modSyncManifestPayload([]any{
				modSyncTestWireEntry("pBad", "bad.jar", sha1Of("bad-bytes"), 9,
					"https://cdn.modrinth.com/x/bad.jar", []any{"fabric"}, []any{"1.20.1"}),
			}, []any{})})
		default:
			t.Errorf("门控不该请求其它路由：%s", route)
			sendJSON(w, map[string]any{"success": false, "error": "UNKNOWN_ENDPOINT"})
		}
	})

	resp := modSyncGateResp(t, a.ModSyncGate(`{"code":"MNCB7Z","scope":"required"}`))
	if resp.Action != "proceed" {
		t.Fatalf("HL 房门控应直通，实得 action=%q outcome=%q", resp.Action, resp.Outcome)
	}
	if resp.Outcome != modSyncFetchUnsupported {
		t.Fatalf("直通原因应为 UNSUPPORTED，实得 %q", resp.Outcome)
	}
	if resp.PendingNote == nil || resp.PendingNote.Key != langLogUIModSyncUnsupported {
		t.Fatalf("提示行键应与取清单后那条 UNSUPPORTED 分支同键，实得 %+v", resp.PendingNote)
	}
	if resp.NoteLevel != 0 {
		t.Fatalf("提示级别应为 0（提示级），实得 %d", resp.NoteLevel)
	}
	if n := routes.count("/room/mods/request"); n != 0 {
		t.Fatalf("HL 房一条清单请求都不该发，实得 %d 次（全部命中：%v）", n, routes.all())
	}
	if n := routes.count("/room/mods"); n != 1 {
		t.Fatalf("前置校验应只问一次 /room/mods，实得 %d 次（%v）", n, routes.all())
	}
	// 待播报提示同样落一份（前端在进房后 flush）
	if lvl, key := modSyncTakePendingNote(); lvl != 0 || key != langLogUIModSyncUnsupported {
		t.Fatalf("pendingNote 未写入或键不符：level=%d key=%q", lvl, key)
	}
}

// ② 普通房且房主已开启（supported=true）：行为与改前一致 —— 照旧取清单、照旧弹窗给玩家选。
func TestModSyncGateNormalRoomWithManifestStillFetches(t *testing.T) {
	routes := &modSyncRoutes{}
	a := newModSyncGateApp(t, routes, func(w http.ResponseWriter, route string) {
		switch route {
		case "/room/mods":
			sendJSON(w, map[string]any{"success": true,
				"data": map[string]any{"supported": true, "ready": false}})
		case "/room/mods/request":
			sendJSON(w, map[string]any{"success": true, "data": modSyncManifestPayload([]any{
				modSyncTestWireEntry("pSodium", "sodium-0.5.jar", sha1Of("host-bytes"), 12,
					"https://cdn.modrinth.com/x/sodium-0.5.jar", []any{"fabric"}, []any{"1.20.1"}),
			}, []any{})})
		default:
			t.Errorf("非预期路由：%s", route)
			sendJSON(w, map[string]any{"success": false, "error": "UNKNOWN_ENDPOINT"})
		}
	})

	resp := modSyncGateResp(t, a.ModSyncGate(`{"code":"VXLNK2","scope":"required"}`))
	if resp.Action != "choose" || resp.Outcome != modSyncFetchManifest {
		t.Fatalf("房主已发布清单时仍应弹选择（行为不变），实得 action=%q outcome=%q", resp.Action, resp.Outcome)
	}
	if len(resp.Downloadable) != 1 || resp.Downloadable[0].ProjectID != "pSodium" {
		t.Fatalf("缺失项应进可下载分组：%+v", resp.Downloadable)
	}
	if resp.PendingNote != nil {
		t.Fatalf("正常门控不该带直通提示：%+v", resp.PendingNote)
	}
	if n := routes.count("/room/mods/request"); n < 1 {
		t.Fatalf("改前流程必须继续取清单，实得 %d 次（%v）", n, routes.all())
	}
	if n := routes.count("/room/mods"); n != 1 {
		t.Fatalf("前置校验只问一次，实得 %d 次（%v）", n, routes.all())
	}
	if got := modSyncGate.gated["VXLNK2"]; got {
		t.Fatal("门控结束必须撤销在途标记")
	}
}

// ③ 普通房但房主未开启上传（supported=false）：前置跳过 + 正确提示键 + 零清单请求 + 无在途残留。
func TestModSyncGateHostPublishingOffSkipsBeforeManifestRequest(t *testing.T) {
	routes := &modSyncRoutes{}
	a := newModSyncGateApp(t, routes, func(w http.ResponseWriter, route string) {
		if route == "/room/mods" {
			sendJSON(w, map[string]any{"success": true,
				"data": map[string]any{"supported": false, "ready": false}})
			return
		}
		t.Errorf("房主未开启上传时不应再碰 %s", route)
		sendJSON(w, map[string]any{"success": true, "data": modSyncManifestPayload([]any{}, []any{})})
	})

	resp := modSyncGateResp(t, a.ModSyncGate(`{"code":"HAPPY6","scope":"all"}`))
	if resp.Action != "proceed" || resp.Outcome != modSyncFetchUnsupported {
		t.Fatalf("应前置直通，实得 action=%q outcome=%q", resp.Action, resp.Outcome)
	}
	if resp.PendingNote == nil || resp.PendingNote.Key != langLogUIModSyncUnsupported || resp.NoteLevel != 0 {
		t.Fatalf("提示键/级别不符：%+v level=%d", resp.PendingNote, resp.NoteLevel)
	}
	if resp.Scope != "all" {
		t.Fatalf("直通结果仍要回显归一化后的 scope，实得 %q", resp.Scope)
	}
	if modSyncGate.gated["HAPPY6"] {
		t.Fatal("前置跳过不应留下在途标记")
	}
}

// 取清单后才判不支持的那条旧路必须留着：探测说支持、真正取的时候服务端改口。
func TestModSyncGateUnsupportedAfterFetchPathStillWorks(t *testing.T) {
	routes := &modSyncRoutes{}
	a := newModSyncGateApp(t, routes, func(w http.ResponseWriter, route string) {
		switch route {
		case "/room/mods":
			sendJSON(w, map[string]any{"success": true,
				"data": map[string]any{"supported": true, "ready": false}})
		case "/room/mods/request":
			sendJSON(w, map[string]any{"success": true,
				"data": map[string]any{"supported": false, "ready": false}})
		default:
			t.Errorf("非预期路由：%s", route)
		}
	})

	resp := modSyncGateResp(t, a.ModSyncGate(`{"code":"GATE22","scope":"required"}`))
	if resp.Action != "proceed" || resp.Outcome != modSyncFetchUnsupported {
		t.Fatalf("取清单后的 UNSUPPORTED 分支语义不变，实得 %+v", resp)
	}
	if resp.PendingNote == nil || resp.PendingNote.Key != langLogUIModSyncUnsupported {
		t.Fatalf("提示键不变：%+v", resp.PendingNote)
	}
	if routes.count("/room/mods/request") < 1 {
		t.Fatalf("这条路必须真的走到取清单：%v", routes.all())
	}
	if modSyncGate.gated["GATE22"] {
		t.Fatal("UNSUPPORTED 直通后应撤销在途标记")
	}
}

// 前置校验本身失败（网络/服务端错误）时不能拦人，也不能把"问不到"当成"不支持"。
func TestModSyncGatePreCheckInconclusiveKeepsOriginalFlow(t *testing.T) {
	routes := &modSyncRoutes{}
	a := newModSyncGateApp(t, routes, func(w http.ResponseWriter, route string) {
		switch route {
		case "/room/mods":
			sendJSON(w, map[string]any{"success": false, "error": "RATE_LIMITED", "message": "慢一点"})
		case "/room/mods/request":
			sendJSON(w, map[string]any{"success": true, "data": modSyncManifestPayload([]any{}, []any{})})
		default:
			t.Errorf("非预期路由：%s", route)
		}
	})

	resp := modSyncGateResp(t, a.ModSyncGate(`{"code":"PRB222","scope":"required"}`))
	if resp.Action != "proceed" || resp.Outcome != modSyncFetchEmpty {
		t.Fatalf("探测问不到时应照旧走取清单（空清单直通），实得 action=%q outcome=%q", resp.Action, resp.Outcome)
	}
	if routes.count("/room/mods/request") < 1 {
		t.Fatalf("门控绝不因前置探测失败而拦玩家：%v", routes.all())
	}
}

// 三条本端判据的行为与返回码不许变：它们必须在任何网络请求之前命中。
func TestModSyncGateLocalConditionsStillShortCircuitWithoutNetwork(t *testing.T) {
	routes := &modSyncRoutes{}
	a := newModSyncGateApp(t, routes, func(w http.ResponseWriter, route string) {
		t.Errorf("本端判据命中时一条请求都不该发：%s", route)
		sendJSON(w, map[string]any{"success": true, "data": map[string]any{"supported": true}})
	})

	// (1) 玩家已点绕过（:109-112）
	a.ModSyncBypass("BYPAS6")
	if resp := modSyncGateResp(t, a.ModSyncGate(`{"code":"BYPAS6","scope":"required"}`)); resp.Outcome != modSyncFetchBypassed {
		t.Fatalf("绕过态应直通 BYPASSED，实得 %q", resp.Outcome)
	}

	// (2) 没配 MC 实例 → NOT_CONFIGURED
	if !jsonOK(t, a.SaveModSyncSettings(`{"instances":[],"activeInstance":"","joinRequiredModsCheck":true,"hostModSyncPublish":true}`)) {
		t.Fatal("清空实例失败")
	}
	if resp := modSyncGateResp(t, a.ModSyncGate(`{"code":"NCFG44","scope":"required"}`)); resp.Outcome != modSyncFetchNotConfigured {
		t.Fatalf("无实例应直通 NOT_CONFIGURED，实得 %q", resp.Outcome)
	}

	// (3) 总开关关闭（:103-106）
	if !jsonOK(t, a.SaveModSyncSettings(`{"instances":[{"name":"隔离实例","loader":"fabric",`+
		`"gameVersion":"1.20.1","modsDir":"`+filepath.ToSlash(t.TempDir())+`/mods"}],`+
		`"activeInstance":"隔离实例","joinRequiredModsCheck":false,"hostModSyncPublish":true}`)) {
		t.Fatal("关开关失败")
	}
	if resp := modSyncGateResp(t, a.ModSyncGate(`{"code":"SWCH22","scope":"required"}`)); resp.Outcome != modSyncGateDisabled {
		t.Fatalf("开关关闭应直通 DISABLED，实得 %q", resp.Outcome)
	}

	if n := routes.total(); n != 0 {
		t.Fatalf("三条本端判据必须零请求，实得 %v", routes.all())
	}
}
