package main

// scenario_tier.go — 模组 network/ScenarioTier.java（1.2.0，52 行）的场景分档器。
//
// 只做场景分类，不改变任何模板/功能（源码注释 :3-5，纯 Java 无 MC API）。
// 调度层用它决定"硬档"：ConnectionManager.java 里四处重算
//   - handleHolepunchMapped（:3216-3224）：低可达时升档
//   - hostPunchInfo 路径（:4304-4308）：localSym/remoteSym + 三个硬信号全 true
//   - ReversePunch 路径（:5298-5302）：同上
//   - cycle 起点（:6551-6559）：tierReach∈(0,3] 记低可达
// 消费点是 ConnectionManager.java:3965-3967 的 hardTier 判定（硬档 → 不降档/走宽扫模板）。
// 会话复位时打回 NORMAL（ConnectionManager.java:13950-13956）。
//
// NatClass.recommendProfile 明确不读 Tier（NatClass.java:36-38 的注释：第三个形参当年就是死参），
// 所以接线时不要把这里的返回值塞进 profile 选择矩阵。

import "strings"

// scenarioTier = ScenarioTier.Tier（:8-15）。
type scenarioTier int

const (
	// scenarioTierUnset 对应 Java 的 null（Go 枚举零值必须能表达"尚未判定"）：
	// isHard → false、key → "normal"，与 Java 对 null 的两个处理逐一对齐（:46-47/:50-52）。
	scenarioTierUnset       scenarioTier = iota
	scenarioTierHardDualSym              // :10 双方都对称（CGNAT/移动 4G5G）：打洞最难的档
	scenarioTierHardOneSym               // :12 单侧对称且伴有硬信号（可达低/端口漂移/预测落空复现）
	scenarioTierNormal                   // :14 普通场景（双锥/easy），走轻量增强模板
)

func (t scenarioTier) String() string {
	switch t {
	case scenarioTierHardDualSym:
		return "HARD_DUAL_SYM"
	case scenarioTierHardOneSym:
		return "HARD_ONE_SYM"
	case scenarioTierNormal:
		return "NORMAL"
	}
	return ""
}

// scenarioClassify = ScenarioTier.classify（:26-44）。分支顺序照抄：
// 双对称 → 硬双对称；否则低可达/端口漂移/预测落空任一 → 硬单对称；
// 否则单侧对称 → 硬单对称；否则普通。
func scenarioClassify(localSymmetric, remoteSymmetric, lowReachable, portDrift, recurringPredictionOff bool) scenarioTier {
	if localSymmetric && remoteSymmetric {
		return scenarioTierHardDualSym
	}
	// 低可达/端口漂移/预测落空：即使两侧 NAT 标签看似锥或未知，也是硬 CGNAT 信号，按硬档处理（:36）
	if lowReachable || portDrift || recurringPredictionOff {
		return scenarioTierHardOneSym
	}
	if localSymmetric || remoteSymmetric {
		return scenarioTierHardOneSym
	}
	return scenarioTierNormal
}

// scenarioIsHard = isHard(tier)（:46-48）。
func scenarioIsHard(t scenarioTier) bool {
	return t == scenarioTierHardDualSym || t == scenarioTierHardOneSym
}

// scenarioTierKey = key(tier)（:50-52）：Java 是 tier.name().toLowerCase()，
// null 时给 "normal"。这三个名字里没有字母 I，默认 Locale 与 ROOT 结果一致，
// 所以 strings.ToLower 无歧义。
func scenarioTierKey(t scenarioTier) string {
	if t == scenarioTierUnset {
		return "normal"
	}
	return strings.ToLower(t.String())
}
