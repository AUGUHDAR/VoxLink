package main

// 建房页现在能就地新建/删除 MC 实例，也能切"当前实例"和两个必装开关。
// 这些全部走同一个 SaveModSyncSettings，而它是**整体覆盖**语义
// （modsync_env.go:585 直接 new 一个 modSyncSettings 存盘）——
// 所以任何一次写回漏带字段，玩家就会"只是想换个开关，结果 mods 目录被清空"。
// 这里把四种写回都按前端 create.js 的拼法演一遍，钉住不丢数据。

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func newModSyncSettingsApp(t *testing.T) *App {
	t.Helper()
	a := NewApp()
	a.settingsPath = filepath.Join(t.TempDir(), "settings.json")
	return a
}

func getModSyncView(t *testing.T, a *App) map[string]any {
	t.Helper()
	var m map[string]any
	if err := json.Unmarshal([]byte(a.GetModSyncSettings()), &m); err != nil {
		t.Fatalf("GetModSyncSettings 不是合法 JSON: %v", err)
	}
	if ok, _ := m["ok"].(bool); !ok {
		t.Fatalf("GetModSyncSettings 失败: %v", m)
	}
	data, _ := m["data"].(map[string]any)
	return data
}

func TestSaveModSyncSettingsKeepsUntouchedFields(t *testing.T) {
	a := newModSyncSettingsApp(t)

	// 1) 前端"保存实例"：带全量 instances + activeInstance + 两个开关
	first := `{"instances":[{"name":"1.20.1 科技","loader":"fabric","gameVersion":"1.20.1","modsDir":"C:/mc/a/mods"}],
	           "activeInstance":"1.20.1 科技","joinRequiredModsCheck":true,"hostModSyncPublish":false}`
	if got := a.SaveModSyncSettings(first); !jsonOK(t, got) {
		t.Fatalf("首次保存失败: %s", got)
	}
	v := getModSyncView(t, a)
	if v["activeInstance"] != "1.20.1 科技" {
		t.Fatalf("activeInstance = %v", v["activeInstance"])
	}
	if v["modsDir"] != "C:/mc/a/mods" {
		t.Fatalf("modsDir 应回显当前实例目录，得 %v", v["modsDir"])
	}

	// 2) 只切"当前实例"（下拉换选）：必须把已有 instances 原样带回去
	insts, _ := json.Marshal(v["instances"])
	a.SaveModSyncSettings(`{"instances":` + string(insts) + `,"activeInstance":"","joinRequiredModsCheck":true,"hostModSyncPublish":false}`)

	// 3) 只改一个开关（建房页那两个 switch）：目录与实例不能被抹掉
	second := getModSyncView(t, a)
	insts2, _ := json.Marshal(second["instances"])
	a.SaveModSyncSettings(`{"instances":` + string(insts2) + `,"activeInstance":"","joinRequiredModsCheck":false,"hostModSyncPublish":true}`)
	after := getModSyncView(t, a)
	list, _ := after["instances"].([]any)
	if len(list) != 1 {
		t.Fatalf("改开关后实例被清空了：instances=%v", after["instances"])
	}
	if after["joinRequiredModsCheck"] != false || after["hostModSyncPublish"] != true {
		t.Fatalf("开关没写进去: %v", after)
	}

	// 4) 删除实例：前端传过滤后的列表 + activeInstance 置空
	a.SaveModSyncSettings(`{"instances":[],"activeInstance":"","joinRequiredModsCheck":false,"hostModSyncPublish":true}`)
	gone := getModSyncView(t, a)
	if l, _ := gone["instances"].([]any); len(l) != 0 {
		t.Fatalf("删除后仍有实例: %v", gone["instances"])
	}
	if gone["configured"] != false {
		t.Errorf("没有实例时应 configured=false（建房页据此提示不生效）")
	}
}

// 磁盘上手工写的坏实例（没名字或没目录）不该把整份设置带崩——服务端侧同款"跳过而非报错"。
func TestSaveModSyncSettingsSkipsBrokenInstances(t *testing.T) {
	a := newModSyncSettingsApp(t)
	out := a.SaveModSyncSettings(`{"instances":[{"name":"","modsDir":"C:/x"},{"name":"ok","modsDir":"C:/ok/mods"}],
	                              "activeInstance":"ok","joinRequiredModsCheck":true,"hostModSyncPublish":true}`)
	if !jsonOK(t, out) {
		t.Fatalf("保存失败: %s", out)
	}
	v := getModSyncView(t, a)
	list, _ := v["instances"].([]any)
	if len(list) != 1 {
		t.Fatalf("空名字的实例应被跳过，得 %v", v["instances"])
	}
}

func jsonOK(t *testing.T, s string) bool {
	t.Helper()
	var m map[string]any
	if err := json.Unmarshal([]byte(s), &m); err != nil {
		return false
	}
	ok, _ := m["ok"].(bool)
	return ok
}

// 测试卫生：modsync 的设置与缓存文件必须跟着 App.settingsPath 走。
// 本轮翻车现场：一条 Go 测试调 SaveModSyncSettings，就把 "ok" 这个假实例
// 写进了玩家真实的 %APPDATA%/VoxLinkApp/modsync.json（因为路径恒取 defaultSettingsPath()）。
func TestModSyncFilesFollowAppSettingsDir(t *testing.T) {
	dir := t.TempDir()
	a := NewApp()
	a.settingsPath = filepath.Join(dir, "settings.json")

	if !jsonOK(t, a.SaveModSyncSettings(`{"instances":[{"name":"隔离实例","loader":"fabric",
		"gameVersion":"1.20.1","modsDir":"`+filepath.ToSlash(dir)+`/mods"}],
		"activeInstance":"隔离实例","joinRequiredModsCheck":true,"hostModSyncPublish":true}`)) {
		t.Fatal("SaveModSyncSettings 失败")
	}
	if _, err := os.Stat(filepath.Join(dir, "modsync.json")); err != nil {
		t.Fatalf("modsync.json 没落在 App 的 settingsPath 同目录：%v", err)
	}
	// 缓存文件同理：mods 目录不是 .../mods 时退回"应用配置目录"，那个目录也必须是 App 的
	cache := modSyncCacheFileForDir(dir, "", "隔离实例")
	if !strings.HasPrefix(strings.ToLower(filepath.ToSlash(cache)), strings.ToLower(filepath.ToSlash(dir))) {
		t.Errorf("缓存文件跑到 App 目录外了：%s", cache)
	}
	if got := modSyncSvc.dir; got != dir {
		t.Errorf("绑定目录没跟随 App.settingsPath：%q", got)
	}
}
