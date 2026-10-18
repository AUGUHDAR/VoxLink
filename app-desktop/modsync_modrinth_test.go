package main

// modsync_modrinth_test.go — 纯函数侧的镜像映射/消毒/取文件规则，
// 对照 ModrinthClient.java 的 mirrorDownloadUrl(:626-648)、
// ensureSafeFileName(:671-686)、primaryFile(:589-612)、isServerOnly(:454-474)、
// escape(:650-652)、parseRetryAfter(:575-586)。网络路径不在单测里碰。

import (
	"net/http"
	"strings"
	"testing"
)

func TestModrinthMirrorDownloadURLMapping(t *testing.T) {
	cases := []struct{ in, want string }{
		{"https://cdn.modrinth.com/data/P1/versions/V1/a.jar",
			"https://mod.mcimirror.top/data/P1/versions/V1/a.jar"},
		{"https://edge.forgecdn.net/files/123/456/mod.jar",
			"https://mod.mcimirror.top/files/123/456/mod.jar"},
		{"https://mediafilez.forgecdn.net/files/1/2/mod.jar?token=x",
			"https://mod.mcimirror.top/files/1/2/mod.jar?token=x"},
		{"https://media.forgecdn.net/files/9/9/m.jar", "https://mod.mcimirror.top/files/9/9/m.jar"},
		{"https://x-service.overwolf.wtf/files/a.jar", "https://mod.mcimirror.top/files/a.jar"},
		// 非 CDN 域原样返回
		{"https://example.com/a.jar", "https://example.com/a.jar"},
		{"", ""},
	}
	for _, c := range cases {
		if got := modrinthMirrorDownloadURL(c.in); got != c.want {
			t.Errorf("mirrorDownloadURL(%q)=%q want %q", c.in, got, c.want)
		}
	}
}

func TestModrinthAPURLChainOrder(t *testing.T) {
	urls := modrinthAPIURLs("/version_files")
	if len(urls) != 2 {
		t.Fatalf("双源链应是 2 项：%v", urls)
	}
	// 镜像在前（短超时优先），官方兜底在后
	if urls[0] != modrinthMirrorAPI+"/version_files" {
		t.Fatalf("首源应为镜像：%v", urls)
	}
	if urls[1] != modrinthAPI+"/version_files" {
		t.Fatalf("末源应为官方：%v", urls)
	}
}

func TestModrinthEnsureSafeFileName(t *testing.T) {
	bad := []string{"", "  ", "../a.jar", "a/b.jar", `a\b.jar`, "c:a.jar", "a\x01b.jar"}
	for _, name := range bad {
		if err := modrinthEnsureSafeFileName(name); err == nil {
			t.Errorf("应拒绝 fileName=%q", name)
		}
	}
	for _, name := range []string{"a.jar", "Mod 1.2 [x].jar", "a-b_c.jar"} {
		if err := modrinthEnsureSafeFileName(name); err != nil {
			t.Errorf("应放行 fileName=%q: %v", name, err)
		}
	}
}

func TestModSyncSafeFileNameRejectsEscape(t *testing.T) {
	dir := t.TempDir()
	if modSyncSafeFileName("..\\evil.jar", dir) {
		t.Error("必须拒绝带路径分隔符的名字")
	}
	if !modSyncSafeFileName("ok.jar", dir) {
		t.Error("普通文件名应放行")
	}
}

func TestModrinthPrimaryFile(t *testing.T) {
	v := map[string]any{"files": []any{
		map[string]any{"filename": "a.jar"},
		map[string]any{"filename": "b.jar", "primary": true},
	}}
	if f := modrinthPrimaryFile(v); f == nil || jstr(f, "filename") != "b.jar" {
		t.Fatalf("应取 primary=true：%v", f)
	}
	v2 := map[string]any{"files": []any{map[string]any{"filename": "a.jar"}}}
	if f := modrinthPrimaryFile(v2); jstr(f, "filename") != "a.jar" {
		t.Fatal("无 primary 时取首个（:589-612）")
	}
	if modrinthPrimaryFile(nil) != nil || modrinthPrimaryFile(map[string]any{}) != nil {
		t.Fatal("无 files 数组应返回 nil")
	}
}

func TestModrinthIsServerOnly(t *testing.T) {
	if !modrinthIsServerOnly(map[string]any{"client_side": "unsupported"}) {
		t.Error("client_side=unsupported 必须是 server-only")
	}
	if modrinthIsServerOnly(map[string]any{"client_side": "required"}) {
		t.Error("required 不是 server-only")
	}
	if modrinthIsServerOnly(map[string]any{"client_side": "optional"}) {
		t.Error("optional 不是 server-only")
	}
	if !modrinthIsServerOnly(map[string]any{"environment": []any{"server_only"}}) {
		t.Error("environment 数组含 server_only 要认出（:464-471）")
	}
	if modrinthIsServerOnly(nil) {
		t.Error("nil 项目按 Java 返回 false")
	}
	// client_side 存在时优先于 environment（Java 的 if 顺序）
	if modrinthIsServerOnly(map[string]any{"client_side": "required", "environment": []any{"server_only"}}) {
		t.Error("client_side 分支应短路 environment 分支")
	}
}

func TestModrinthEscape(t *testing.T) {
	if got := modrinthEscape(`a"b\c`); got != `a\"b\\c` {
		t.Fatalf("escape=%q", got)
	}
}

func TestParseRetryAfter(t *testing.T) {
	h := http.Header{}
	if got := parseRetryAfter(&http.Response{Header: h}); got != 3 {
		t.Fatalf("缺省应为 3，实得 %d", got)
	}
	h.Set("Retry-After", " 7 ")
	if got := parseRetryAfter(&http.Response{Header: h}); got != 7 {
		t.Fatalf("应解析出 7，实得 %d", got)
	}
	h.Set("Retry-After", "wed, 21 Oct")
	if got := parseRetryAfter(&http.Response{Header: h}); got != 3 {
		t.Fatalf("非法值应回落 3，实得 %d", got)
	}
	// 换源退避上限 min(3, Retry-After)
	if min64(3, 99) != 3 {
		t.Fatal("min64 语义不符")
	}
}

func TestModrinthUserAgentShape(t *testing.T) {
	ua := modrinthUserAgent()
	if !strings.HasPrefix(ua, "VoxLink/") || !strings.HasSuffix(ua, " (modsync)") {
		t.Fatalf("UA 必须保持模组侧格式 VoxLink/<版本> (modsync)：%q", ua)
	}
}
