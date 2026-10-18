package main

// punch_strategy.go — NAT 分级与打洞策略矩阵（对齐模组 NatClass.java /
// PunchStrategySelector.java）。NAT 分级取自 StunProbe 两服务器比对：
// 公网 IP 与端口都相同才是 cone；IP 不同直接 hard；同 IP 时 |delta|<100 才是 easySym。

type natClass int

const (
	natUnknown natClass = iota
	natCone
	natEasySym
	natHardSym
)

func (c natClass) String() string {
	switch c {
	case natCone:
		return "CONE"
	case natEasySym:
		return "EASY_SYM"
	case natHardSym:
		return "HARD_SYM"
	}
	return "UNKNOWN"
}

func (c natClass) symmetric() bool { return c == natEasySym || c == natHardSym }

// natClassFromDelta 两服务器映射比对分级（StunProbe 的 delta 兜底路径：
// 927-929 判 cone、986-1000 判 easy/hard）。
//
// 两个判据都带公网 IP，不是只看端口：
//   - IP 与端口都相同才是 cone。只看端口时，"对两台服务器映射到同一端口但公网 IP 不同"
//     会被误判成 cone，而模组在这种情况直接判对称（IP 都换了，映射随目的地变）。
//   - IP 不同就一定是 hard：模组在 986-988 先拦 IP 不等，压根不看 delta。
//     旧实现按 |d|<100 判 easySym，等于给跨 IP 的对称 NAT 发了一张"好打"的通行证，
//     recommendProfile 随之从 HARDSYM 的宽扫掉到窄扫模板，是实打实的连通率退步。
//
// 阈值取模组的口径：两个端口都有效才是 100，否则 200（StunProbe:990）。
// 样本不足两个 → unknown（模组样本缺一律 UNKNOWN）。
func natClassFromDelta(samples []stunMappedAddr) natClass {
	if len(samples) < 2 {
		return natUnknown
	}
	a, b := samples[0], samples[len(samples)-1]
	if a.IP == b.IP && a.Port == b.Port {
		return natCone
	}
	if a.IP != b.IP {
		return natHardSym
	}
	twoDiff := b.Port - a.Port
	absDiff := twoDiff
	if absDiff < 0 {
		absDiff = -absDiff
	}
	threshold := 100
	if !(a.Port > 0 && b.Port > 0) {
		threshold = 200
	}
	if absDiff > 0 && absDiff < threshold {
		return natEasySym
	}
	return natHardSym
}

// natClassFromRoomType 房间上报 natType 字符串 → 分级。
func natClassFromRoomType(t string) natClass {
	switch t {
	case "full_cone", "restricted_cone", "port_restricted_cone", "cone":
		return natCone
	case "symmetric_easy_inc", "symmetric_easy_dec", "symmetric_easy":
		return natEasySym
	case "symmetric", "symmetric_hard":
		return natHardSym
	}
	return natUnknown
}

// recommendProfile (localNat,remoteNat) 排列矩阵（NatClass.recommendProfile 同款）。
func recommendProfile(local, remote natClass) *punchProfile {
	if local == natUnknown || remote == natUnknown {
		return punchAggressive
	}
	if local == natHardSym && remote == natHardSym {
		return punchHardSym
	}
	if (local == natEasySym && remote == natHardSym) || (local == natHardSym && remote == natEasySym) {
		return punchHardSym
	}
	if local == natEasySym && remote == natEasySym {
		return punchEasySymDual
	}
	if local == natCone && remote == natCone {
		return punchFastLane
	}
	if (local == natCone && remote == natEasySym) || (local == natEasySym && remote == natCone) {
		return punchV100
	}
	if (local == natCone && remote == natHardSym) || (local == natHardSym && remote == natCone) {
		return punchAggressive
	}
	return punchDefault
}

type punchStrategy int

const (
	strategyDirectOnly punchStrategy = iota
	strategyDirectWithReverseParallel
	strategyReverseFirst
	strategyRelayFallbackFast
	strategyReverseOnly
	strategyParallelFromStart
	strategyReverseThenForward
)

// selectStrategy PunchStrategySelector.select 同款矩阵。
// isLegacyPeer：老版本对端强制 DIRECT_ONLY。
func selectStrategy(localNat, remoteNat natClass, cycle int, isLegacyPeer bool) punchStrategy {
	if isLegacyPeer {
		return strategyDirectOnly
	}
	if localNat == natUnknown || remoteNat == natUnknown {
		if localNat == natUnknown || remoteNat == natUnknown {
			known := remoteNat
			if localNat != natUnknown {
				known = localNat
			}
			if known.symmetric() {
				return strategyReverseFirst
			}
		}
		return strategyDirectWithReverseParallel
	}
	if (localNat == natCone && remoteNat == natEasySym) || (localNat == natEasySym && remoteNat == natCone) {
		return strategyReverseFirst
	}
	if (localNat == natCone && remoteNat == natHardSym) || (localNat == natHardSym && remoteNat == natCone) {
		if cycle == 0 {
			return strategyReverseOnly
		}
		return strategyReverseThenForward
	}
	if (localNat == natEasySym && remoteNat == natHardSym) || (localNat == natHardSym && remoteNat == natEasySym) {
		return strategyReverseFirst
	}
	if localNat == natHardSym && remoteNat == natHardSym {
		return strategyDirectWithReverseParallel
	}
	if localNat == natEasySym && remoteNat == natEasySym {
		return strategyDirectWithReverseParallel
	}
	if localNat == natCone && remoteNat == natCone {
		return strategyDirectOnly
	}
	return strategyDirectWithReverseParallel
}
