package main

// modsync_entry_test.go — Entry DTO 的线上字段名/顺序与文件名猜名规则回归。
// 断言的每条期望都能在 Java 里指到行号（ModSyncEntry.java）。

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestModSyncEntryWireFieldNamesAndOrder(t *testing.T) {
	e := newModSyncEntry()
	e.ProjectID = "P1"
	e.Slug = "sodium"
	e.Title = "Sodium"
	e.VersionNumber = "0.5.3"
	e.FileName = "sodium-fabric-mc1.20.1-0.5.3.jar"
	e.DownloadURL = "https://cdn.modrinth.com/data/P1/sodium.jar"
	e.Sha1 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
	e.Sha512 = "bb"
	e.IconURL = "https://cdn.modrinth.com/icon.png"
	e.Size = 1024000
	e.Loaders = []string{"fabric"}
	e.GameVersions = []string{"1.20.1"}
	// 不序列化字段（ModSyncEntry.java:20-25）
	e.foundOn = "cf"
	e.depProjectID = []string{"X"}
	e.cfDepID = []string{"1:2"}

	b, err := json.Marshal(e)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	raw := string(b)
	// toJson 的键序（:148-167）：projectId,slug,title,versionNumber,fileName,url,
	// sha1,sha512,iconUrl,size,loaders,gameVersions
	want := `{"projectId":"P1","slug":"sodium","title":"Sodium","versionNumber":"0.5.3",` +
		`"fileName":"sodium-fabric-mc1.20.1-0.5.3.jar","url":"https://cdn.modrinth.com/data/P1/sodium.jar",` +
		`"sha1":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","sha512":"bb",` +
		`"iconUrl":"https://cdn.modrinth.com/icon.png","size":1024000,"loaders":["fabric"],"gameVersions":["1.20.1"]}`
	if raw != want {
		t.Fatalf("线上 JSON 与期望不一致\n got: %s\nwant: %s", raw, want)
	}
	for _, forbidden := range []string{"foundOn", "depProjectIds", "cfDepIds", "downloadUrl", "DownloadURL"} {
		if strings.Contains(raw, forbidden) {
			t.Fatalf("不该序列化 %s：%s", forbidden, raw)
		}
	}
}

func TestModSyncGuessTitleFromFileName(t *testing.T) {
	cases := []struct{ in, want string }{
		{"sodium-fabric-mc1.20.1-0.5.3.jar", "sodium"},
		{"memoryleakfix-1.19-1.20-1.1.5.jar", "memoryleakfix"},
		{"Iron-Chests-Mod-1.20.1.jar", "Iron Chests Mod"},
		{"jei-1.20.1-forge-12.0.0.19.jar", "jei"},
		{"some_mod_2.0_fabric.jar", "some mod"},
		{"plainname.jar", "plainname"},
		{"", ""},
		{".jar", ".jar"}, // lastIndexOf==0 时不截断（:79-81）
	}
	for _, c := range cases {
		if got := modSyncGuessTitleFromFileName(c.in); got != c.want {
			t.Errorf("guessTitle(%q)=%q want %q", c.in, got, c.want)
		}
	}
}

func TestModSyncEntryFromFileName(t *testing.T) {
	e := modSyncEntryFromFileName("Unknown-Mod-1.2.jar")
	if e.FileName != "Unknown-Mod-1.2.jar" {
		t.Fatalf("fileName 应原样保留：%s", e.FileName)
	}
	if e.Title == "" {
		t.Fatal("title 不应为空")
	}
	// 猜名失败时回落去 .jar 的裸名（ModSyncEntry.java:94-102）
	if bare := modSyncEntryFromFileName("x.jar"); bare.Title != "x" {
		t.Fatalf("bare title=%q want x", bare.Title)
	}
}

func TestModSyncEntryFromWireTitleFallback(t *testing.T) {
	got := modSyncEntryFromWire(map[string]any{"projectId": "P", "slug": "S"})
	if got.Title != "S" {
		t.Fatalf("title 应回落 slug，实得 %q", got.Title)
	}
	got2 := modSyncEntryFromWire(map[string]any{"projectId": "P"})
	if got2.Title != "P" {
		t.Fatalf("title 应回落 projectId，实得 %q", got2.Title)
	}
	if got2.Size != 0 {
		t.Fatalf("size=%d want 0（:184 缺键按 0）", got2.Size)
	}
	// loaders/gameVersions 缺键时为空表而非 nil（strList :194-205）
	if got2.Loaders == nil || len(got2.Loaders) != 0 {
		t.Fatalf("loaders 应为空表")
	}
	if got2.GameVersions == nil {
		t.Fatalf("gameVersions 应为空表")
	}
}

func TestModSyncEntryFromVersionUsesPrimaryFile(t *testing.T) {
	version := map[string]any{
		"project_id":     "P1",
		"version_number": "1.0",
		"loaders":        []any{"fabric"},
		"game_versions":  []any{"1.20.1"},
		"dependencies":   []any{},
		"files": []any{
			map[string]any{"filename": "a.jar", "url": "https://cdn.modrinth.com/a.jar", "size": float64(10)},
			map[string]any{
				"filename": "b.jar", "url": "https://cdn.modrinth.com/b.jar", "size": float64(20), "primary": true,
				"hashes": map[string]any{"sha1": "s1", "sha512": "s2"},
			},
		},
	}
	project := map[string]any{"slug": "slug1", "title": "Title1", "icon_url": "icon"}
	e := modSyncEntryFromVersion(version, project)
	if e.FileName != "b.jar" || e.DownloadURL != "https://cdn.modrinth.com/b.jar" {
		t.Fatalf("应取 primary 文件，实得 %s/%s", e.FileName, e.DownloadURL)
	}
	if e.Size != 20 || e.Sha1 != "s1" || e.Sha512 != "s2" {
		t.Fatalf("primary 文件字段不对：%+v", e)
	}
	if e.Title != "Title1" || e.Slug != "slug1" || e.IconURL != "icon" {
		t.Fatalf("project 元数据未合入：%+v", e)
	}
}

func TestModSyncEntryFromVersionNilVersion(t *testing.T) {
	e := modSyncEntryFromVersion(nil, map[string]any{"title": "X"})
	if e.ProjectID != "" || e.FileName != "" {
		t.Fatalf("version==nil 时返回空条目（:32-34）")
	}
}
