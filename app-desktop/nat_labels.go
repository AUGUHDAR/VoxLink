package main

// nat_labels.go — 模组 network/NatLabels.java（1.2.0，97 行）的文案归一层移植。
//
// 定位与约束（照源码，不做扩大）：
//   - 纯显示层。Java 侧注释明确"只做显示，不参与任何打洞/中继判定（判定见 NatClass 与 StunDetector）"
//     （NatLabels.java:13-21）。本端同样：任何决策代码都不许读这里的返回值。
//   - 输入两类串：StunProbe.NatType 的细粒度 key（stun_probe.go 的 stunNatType.key()）、
//     服务端下发的原始 natType（含早期 API 的 open/moderate/strict，:39-42）。
//   - 未收录的串一律回退 voxlink.nat.unknown，并且**每个不同的原始值只 warn 一次**
//     （:27 UNRECOGNIZED 集合 + :64-66），避免"界面显示未知、但没人知道为什么"。
//   - lang 键已在 app-desktop/lang/*.json（13 份，470 键，从模组原样拷来），
//     voxlink.nat.* 全部命中：unknown/cone/easy_sym/hard_sym/full_cone/restricted_cone/
//     port_restricted_cone/symmetric_easy_inc/symmetric_easy_dec/symmetric/open/moderate/strict。
//
// Java→Go 的唯一转换：Java 用 Component.translatable(key).getString() 就地渲染成文本；
// 本端后端没有 lang 渲染层（既有惯例是"后端只发语言键，前端按 zh_cn 表渲染"，
// 见 modsync_log.go:20-25 与 frontend/dist/assets/js/modsync.js）。
// 因此这里的 displayName/nameOfRaw 对应函数返回**语言键**而不是成品字符串，
// 函数名带 Key 后缀标出这一层差别；映射表逐条与 Java 静态块一致。

import (
	"fmt"
	"strings"
	"sync"
)

// natLangUnknownKey = NatLabels.UNKNOWN_KEY（:23）。
const natLangUnknownKey = "voxlink.nat.unknown"

// natRawToLangKey = RAW_TO_LANG_KEY（:30-43）。
var natRawToLangKey = map[string]string{
	"unknown":              natLangUnknownKey,
	"full_cone":            "voxlink.nat.full_cone",
	"restricted_cone":      "voxlink.nat.restricted_cone",
	"port_restricted_cone": "voxlink.nat.port_restricted_cone",
	"symmetric_easy_inc":   "voxlink.nat.symmetric_easy_inc",
	"symmetric_easy_dec":   "voxlink.nat.symmetric_easy_dec",
	"symmetric":            "voxlink.nat.symmetric",
	// 早期 API 规范遗留值，服务端 natTypeValid 白名单仍会透传（:39）
	"open":     "voxlink.nat.open",
	"moderate": "voxlink.nat.moderate",
	"strict":   "voxlink.nat.strict",
}

// natClassToLangKey = CLASS_TO_LANG_KEY（:45-50）：粗粒度档（带打洞语义）的语言键。
// NatClass 在本端已有等价枚举 natClass（punch_strategy.go:7-14）。
var natClassToLangKey = map[natClass]string{
	natCone:    "voxlink.nat.cone",
	natEasySym: "voxlink.nat.easy_sym",
	natHardSym: "voxlink.nat.hard_sym",
	natUnknown: natLangUnknownKey,
}

// natUnrecognized = UNRECOGNIZED（:27）：每个未收录原始值只提示一次。
var (
	natLabelMu     sync.Mutex
	natUnrecognize = map[string]struct{}{}
)

func natLabelLogWarn(msg string) {
	stunLogMu.RLock()
	fn := stunLogFn
	stunLogMu.RUnlock()
	fn("warn", "[NatLabels] "+msg)
}

// natLangKey = langKey(rawNatType)（:57-73）：null/空/未收录一律回退 unknown；
// 归一方式是 trim + Locale.ROOT 小写（:61）。
func natLangKey(rawNatType string) string {
	if rawNatType == "" {
		return natLangUnknownKey
	}
	normalized := strings.ToLower(strings.TrimSpace(rawNatType))
	if key, ok := natRawToLangKey[normalized]; ok {
		return key
	}
	natLabelMu.Lock()
	_, seen := natUnrecognize[normalized]
	if !seen {
		natUnrecognize[normalized] = struct{}{}
	}
	natLabelMu.Unlock()
	if !seen {
		// Java 原文："未收录的 NAT 类型 '{}', 界面回退为未知(请补 voxlink.nat 文案)"（:65）
		natLabelLogWarn(fmt.Sprintf("未收录的 NAT 类型 '%s', 界面回退为未知(请补 voxlink.nat 文案)", rawNatType))
	}
	return natLangUnknownKey
}

// natIsSpecific = isSpecific(rawNatType)（:76-78）：是否是可用于展示的明确 NAT 串。
func natIsSpecific(rawNatType string) bool {
	return natLangKey(rawNatType) != natLangUnknownKey
}

// natRawKeyOfProbe = rawKey(ProbeResult)（:81-83）：探测结果里的原始 NAT 串。
// Java 未探测到时返回 null，Go 用 ""（调用处判空语义相同）。
func natRawKeyOfProbe(probe *stunProbeResult) string {
	if probe == nil {
		return ""
	}
	return probe.NatType.key()
}

// natDisplayNameKey = displayName(NatClass, raw)（:86-91）的"键"版本：
// 已归类（非 UNKNOWN）时用粗粒度文案，未归类时回落原始细粒度文案。
//
// 形参用 *natClass 是为了保住 Java 的两个不同分支：
//   - natClass == null（调用方压根没归类）→ langKey(raw)，raw 具体时能显示细粒度文案；
//   - natClass == NatClass.UNKNOWN（归过类但归不出来）→ CLASS_TO_LANG_KEY[UNKNOWN]
//     即 voxlink.nat.unknown，**不再**看 raw（:87-89 的三元）。
//
// 传值枚举会把这两支合并成一支、改掉 UNKNOWN 场景的显示结果，所以这里必须用指针。
func natDisplayNameKey(natClass *natClass, rawNatType string) string {
	if natClass != nil && *natClass != natUnknown {
		if key, ok := natClassToLangKey[*natClass]; ok {
			return key
		}
		return natLangUnknownKey
	}
	return natLangKey(rawNatType)
}

// natNameOfRawKey = nameOfRaw(rawNatType)（:94-96）的"键"版本：只有原始串的场合
// （主页状态行 / 大厅卡片）。
func natNameOfRawKey(rawNatType string) string {
	return natLangKey(rawNatType)
}
