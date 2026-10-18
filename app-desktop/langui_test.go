package main

import (
	"strings"
	"testing"
)

// 语言包：文件名 → 代码、auto 解析、兜底链，都必须可预测（前端整片文案依赖它）。
func TestLangPackResolution(t *testing.T) {
	if len(availableLangs()) != 13 {
		t.Fatalf("应有 13 份语言包，实际 %d：%v", len(availableLangs()), availableLangs())
	}
	if p := langPackOf("zh_cn"); p == nil || len(p) < 400 {
		t.Fatalf("zh_cn 没加载或键太少：%d", len(p))
	}
	cases := []struct{ setting, sys, want string }{
		{"zh_tw", "en-US", "zh_tw"}, // 用户显式选择优先于系统语言
		{"auto", "en-US", "en_us"},
		{"auto", "zh-TW", "zh_tw"},
		{"auto", "zh-HK", "zh_tw"},
		{"auto", "ja", "ja_jp"},
		{"auto", "pt-BR", "pt_br"},
		{"auto", "de", "de_de"},
		{"auto", "zz_ZZ", "zh_cn"}, // 认不出的系统语言 → 回落中文
		{"乱写的", "en", "en_us"},     // 非法设置 → 按 auto 走
	}
	for _, c := range cases {
		if got := resolveLangCode(c.setting, c.sys); got != c.want {
			t.Errorf("resolveLangCode(%q,%q)=%q want %q", c.setting, c.sys, got, c.want)
		}
	}
	a := NewApp()
	a.settingsPath = "/tmp/does-not-matter/settings.json"
	a.settings.Language = "auto"
	if !jsonOKish(a.GetSettings(), `"language":"auto"`) {
		t.Error("GetSettings 没带 language")
	}
	if !jsonOKish(a.GetSettings(), `"languages"`) {
		t.Error("GetSettings 没带可选语言清单")
	}
	if got := a.SaveSettings(`{"language":"fr_fr"}`); !jsonOKish(got, `"language":"fr_fr"`) {
		t.Errorf("保存合法语言失败：%s", snippet(got))
	}
	a.settings.Language = "auto"
	if got := a.SaveSettings(`{"language":"不存在的语言"}`); !jsonOKish(got, `"language":"auto"`) {
		t.Errorf("非法语言应被忽略而不是写进去：%s", snippet(got))
	}
}

func jsonOKish(s, needle string) bool { return strings.Contains(s, needle) }

// snippet 只截前 160 字节用于报错，避免把整份语言表打进测试输出。
func snippet(s string) string {
	r := []rune(s)
	if len(r) > 160 {
		return string(r[:160]) + "…"
	}
	return s
}
