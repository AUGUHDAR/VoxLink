package main

// nat_labels_test.go — NatLabels 归一层的映射表与"每种未收录值只报一次"行为。
// 断言的键值全部逐字对照 NatLabels.java:30-50 与 app-desktop/lang/en_us.json。

import (
	"os"
	"strings"
	"sync"
	"testing"
)

func TestNatLangKeyRawTable(t *testing.T) {
	cases := []struct{ raw, want string }{
		{"unknown", "voxlink.nat.unknown"},
		{"full_cone", "voxlink.nat.full_cone"},
		{"restricted_cone", "voxlink.nat.restricted_cone"},
		{"port_restricted_cone", "voxlink.nat.port_restricted_cone"},
		{"symmetric_easy_inc", "voxlink.nat.symmetric_easy_inc"},
		{"symmetric_easy_dec", "voxlink.nat.symmetric_easy_dec"},
		{"symmetric", "voxlink.nat.symmetric"},
		{"open", "voxlink.nat.open"}, // 早期 API 遗留值（:40）
		{"moderate", "voxlink.nat.moderate"},
		{"strict", "voxlink.nat.strict"},
		// 归一方式：trim + 小写（:61）
		{"  FULL_CONE ", "voxlink.nat.full_cone"},
		{"", "voxlink.nat.unknown"},
		{"没见过的类型", "voxlink.nat.unknown"},
	}
	for _, c := range cases {
		if got := natLangKey(c.raw); got != c.want {
			t.Fatalf("natLangKey(%q) = %s，期望 %s", c.raw, got, c.want)
		}
	}
}

func TestNatLangKeysAllExistInLangFile(t *testing.T) {
	// 归一层存在的意义就是"键一定在 13 份 lang 文件里"，键丢了这里就该红
	data, err := os.ReadFile("lang/en_us.json")
	if err != nil {
		t.Skip("lang/en_us.json 不可读（工作目录不是包目录）")
	}
	// lang/*.json 自 1.2.0 起是分组嵌套结构，查键必须走与运行时同一条展平路径
	table := flattenLangJSON(data)
	if table == nil {
		t.Fatalf("lang/en_us.json 解析失败")
	}
	var keys []string
	for _, v := range natRawToLangKey {
		keys = append(keys, v)
	}
	for _, v := range natClassToLangKey {
		keys = append(keys, v)
	}
	keys = append(keys, natLangUnknownKey)
	for _, k := range keys {
		if _, ok := table[k]; !ok {
			t.Fatalf("语言键 %s 不在 lang/en_us.json 里", k)
		}
		if !strings.HasPrefix(k, "voxlink.nat.") {
			t.Fatalf("语言键 %s 前缀不对", k)
		}
	}
}

func TestNatIsSpecific(t *testing.T) {
	if !natIsSpecific("full_cone") {
		t.Fatal("full_cone 应判为具体类型")
	}
	if natIsSpecific("unknown") || natIsSpecific("") || natIsSpecific("乱写的") {
		t.Fatal("回退 unknown 的输入不该判为具体类型（:76-78）")
	}
}

func TestNatRawKeyOfProbe(t *testing.T) {
	if got := natRawKeyOfProbe(nil); got != "" {
		t.Fatalf("nil 探测结果应给空串（Java 的 null，:81-83），实得 %q", got)
	}
	if got := natRawKeyOfProbe(&stunProbeResult{NatType: natTypeEasySymInc}); got != "symmetric_easy_inc" {
		t.Fatalf("rawKey = %q，期望 symmetric_easy_inc（重复 key 也按身份取）", got)
	}
}

func TestNatDisplayNameKeyBranches(t *testing.T) {
	// Java 的三元（:87-89）：已归类且非 UNKNOWN → 粗粒度；UNKNOWN 与 null 是两条不同分支
	cone, unk := natCone, natUnknown
	if got := natDisplayNameKey(&cone, "full_cone"); got != "voxlink.nat.cone" {
		t.Fatalf("CONE 应走粗粒度，实得 %s", got)
	}
	// NatClass.UNKNOWN 走的是 raw 那一支：Java :87-89 的三元是
	// (natClass != null && natClass != UNKNOWN) ? 粗粒度 : langKey(raw)，
	// 所以 UNKNOWN + 具体 raw 必须显示细粒度文案，不是 unknown。
	if got := natDisplayNameKey(&unk, "full_cone"); got != "voxlink.nat.full_cone" {
		t.Fatalf("UNKNOWN 应回落 raw 细粒度文案，实得 %s", got)
	}
	// UNKNOWN + 不可展示的 raw 才是 unknown 文案
	if got := natDisplayNameKey(&unk, ""); got != "voxlink.nat.unknown" {
		t.Fatalf("UNKNOWN + 空 raw 应回退 unknown，实得 %s", got)
	}
	// natClass == null → 回落 raw 的细粒度文案
	if got := natDisplayNameKey(nil, "full_cone"); got != "voxlink.nat.full_cone" {
		t.Fatalf("null 类应回落细粒度，实得 %s", got)
	}
	if got := natNameOfRawKey("strict"); got != "voxlink.nat.strict" {
		t.Fatalf("nameOfRaw(strict) = %s", got)
	}
}

func TestNatUnrecognizedWarnsOncePerValue(t *testing.T) {
	var mu sync.Mutex
	var lines []string
	old := captureStunLogForTest(func(level, msg string) {
		if level == "warn" {
			mu.Lock()
			lines = append(lines, msg)
			mu.Unlock()
		}
	})
	defer old()

	for i := 0; i < 5; i++ {
		if got := natLangKey("totally_unknown_type_a"); got != natLangUnknownKey {
			t.Fatalf("未收录值应回退 unknown 键，实得 %s", got)
		}
	}
	mu.Lock()
	n := len(lines)
	mu.Unlock()
	if n != 1 {
		t.Fatalf("同一未收录值报了 %d 条日志，期望 1 条（:64-66）", n)
	}
	if n == 1 && !strings.Contains(lines[0], "totally_unknown_type_a") {
		t.Fatalf("日志里要带原始串本身，实得 %q", lines[0])
	}

	// 另一个不同的未收录值 → 再报一条（"每个不同值一次"）
	mu.Lock()
	lines = nil
	mu.Unlock()
	_ = natLangKey("totally_unknown_type_b")
	mu.Lock()
	n = len(lines)
	mu.Unlock()
	if n != 1 {
		t.Fatalf("新的未收录值应有独立的一条提示，实得 %d 条", n)
	}
}

// captureStunLogForTest 临时替换日志出口，返回还原函数。
func captureStunLogForTest(fn func(level, msg string)) func() {
	stunLogMu.Lock()
	prev := stunLogFn
	stunLogFn = fn
	stunLogMu.Unlock()
	return func() {
		stunLogMu.Lock()
		stunLogFn = prev
		stunLogMu.Unlock()
	}
}
