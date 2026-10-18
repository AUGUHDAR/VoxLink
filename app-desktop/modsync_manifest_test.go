package main

// modsync_manifest_test.go — 房主侧不依赖 Modrinth 的部分：
// 状态哈希缓存键、磁盘缓存读写/坏缓存丢弃、依赖提取与根集判定、scope 归一。
// 断言逐条对应 ModSyncManifestService.java。

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func TestModSyncNormalizeScope(t *testing.T) {
	// :77-79：非 "all" 一律按 required
	for in, want := range map[string]string{
		"all": modSyncScopeAll, "required": modSyncScopeRequired,
		"": modSyncScopeRequired, "ALL": modSyncScopeRequired, "junk": modSyncScopeRequired,
	} {
		if got := modSyncNormalizeScope(in); got != want {
			t.Errorf("normalizeScope(%q)=%q want %q", in, got, want)
		}
	}
}

func TestModSyncRequiredDepIds(t *testing.T) {
	v := map[string]any{"dependencies": []any{
		map[string]any{"dependency_type": "required", "project_id": "A"},
		"not-an-object",
		map[string]any{"dependency_type": "optional", "project_id": "B"},
	}}
	got := modSyncRequiredDepIds(v)
	if len(got) != 1 || got[0] != "A" {
		t.Fatalf("只收 required 且有 project_id 的：%v", got)
	}
	if len(modSyncRequiredDepIds(nil)) != 0 || len(modSyncRequiredDepIds(map[string]any{})) != 0 {
		t.Fatal("缺 dependencies 返回空表（:373-375）")
	}
}

func TestModSyncIsClientRequiredRoot(t *testing.T) {
	// 元数据缺失：fail-open 判否（:397-405）
	if modSyncIsClientRequiredRoot(nil, "P") {
		t.Fatal("缺失元数据不得作根")
	}
	for side, want := range map[string]bool{
		"required": true, "optional": false, "unsupported": false, "": true,
	} {
		if got := modSyncIsClientRequiredRoot(map[string]any{"client_side": side}, "P"); got != want {
			t.Errorf("client_side=%q → %v want %v", side, got, want)
		}
	}
}

func TestModSyncEmptyScopeManifestShape(t *testing.T) {
	env := modSyncEnv{Loader: "fabric", GameVersion: "1.20.1"}
	m := modSyncEmptyScopeManifest(env)
	b, err := json.Marshal(m)
	if err != nil {
		t.Fatal(err)
	}
	var probe map[string]any
	if err := json.Unmarshal(b, &probe); err != nil {
		t.Fatal(err)
	}
	if jstr(probe, "protocolVersion") != "modSync.v1" {
		t.Fatalf("protocolVersion 必须是 modSync.v1（:413）")
	}
	if jstr(probe, "loader") != "fabric" || jstr(probe, "mcVersion") != "1.20.1" {
		t.Fatalf("loader/mcVersion 取 ModSyncEnv 四元组：%+v", probe)
	}
	if arr := jarr(probe, "mods"); arr == nil || len(arr) != 0 {
		t.Fatal("mods 必须是空数组")
	}
	if _, ok := probe["unknownMods"]; ok {
		t.Fatal("骨架阶段不带 unknownMods 键（对应 Java 早期返回路径）")
	}
}

func TestModSyncDiskCacheRoundTripAndGuards(t *testing.T) {
	dir := t.TempDir()
	cacheFile := filepath.Join(dir, "voxlink", "modsync_cache.json")

	root := &modSyncCacheRoot{
		Required: modSyncEmptyScopeManifest(modSyncEnv{Loader: "fabric", GameVersion: "1.20.1"}),
		All:      modSyncEmptyScopeManifest(modSyncEnv{Loader: "fabric", GameVersion: "1.20.1"}),
	}
	e := newModSyncEntry()
	e.ProjectID = "P"
	e.DownloadURL = "https://cdn.modrinth.com/x/P.jar"
	root.All.Mods = append(root.All.Mods, e)
	n := 3
	root.JarCount = &n

	modSyncWriteDiskCache(cacheFile, root, "statehash-1")
	if _, err := os.ReadFile(cacheFile); err != nil {
		t.Fatalf("缓存必须落盘（含父目录创建）：%v", err)
	}
	// tmp 文件不应残留
	if _, err := os.Stat(cacheFile + ".tmp"); err == nil {
		t.Fatal(".tmp 残留：改名未生效")
	}

	if got := modSyncReadDiskCache(cacheFile, "statehash-1"); got == nil {
		t.Fatal("同 stateHash 应命中")
	} else if modSyncModsLen(got.All) != 1 {
		t.Fatalf("all 档应回读 1 条：%+v", got.All)
	}
	if got := modSyncReadDiskCache(cacheFile, "statehash-2"); got != nil {
		t.Fatal("stateHash 不匹配必须 miss（:169-171）")
	}

	// 历史坏缓存：两档 mods 全空 → 丢弃重建（:175-181）
	bad := &modSyncCacheRoot{
		Required: modSyncEmptyScopeManifest(modSyncEnv{}),
		All:      modSyncEmptyScopeManifest(modSyncEnv{}),
	}
	modSyncWriteDiskCache(cacheFile, bad, "statehash-3")
	if got := modSyncReadDiskCache(cacheFile, "statehash-3"); got != nil {
		t.Fatal("两档皆空的坏缓存必须被丢弃")
	}
}

func TestModSyncReadDiskCacheToleratesGarbage(t *testing.T) {
	dir := t.TempDir()
	f := filepath.Join(dir, "modsync_cache.json")
	for _, payload := range []string{"", "{", "[]", `{"stateHash":"x"}`, `{"stateHash":"x","required":{},"all":{}}`} {
		if err := os.WriteFile(f, []byte(payload), 0o644); err != nil {
			t.Fatal(err)
		}
		if got := modSyncReadDiskCache(f, "x"); got != nil {
			t.Errorf("残缺缓存应 miss：%s", payload)
		}
	}
	if got := modSyncReadDiskCache(filepath.Join(dir, "missing.json"), "x"); got != nil {
		t.Fatal("文件不存在应 miss")
	}
}

func TestModSyncUnknownModsCapConstant(t *testing.T) {
	// 1.2.0 把 64 提到 192（ModSyncManifestService.java:42），别被"优化"回去
	if modSyncUnknownModsCap != 192 {
		t.Fatalf("UNKNOWN_MODS_CAP=%d want 192", modSyncUnknownModsCap)
	}
	if modSyncMaxDepDepth != 8 {
		t.Fatalf("MAX_DEP_DEPTH=%d want 8", modSyncMaxDepDepth)
	}
	if modSyncMaxJars != 1024 || modSyncHashBuf != 64*1024 {
		t.Fatalf("扫描/哈希缓冲常量与 Java(:18-19) 不一致")
	}
}
