package main

// 有效轮数与"按对端上报窗口封顶扫窗"的用例（CM:6214-6228 / UHP:1253-1258）。
// 这两处是"档位参数声明了却没人读"的典型：把读取点摘回 profile.maxCycles / 本端窗口，用例必须变红。

import (
	"net"
	"testing"
)

func probeWith(sym bool, reachable int) *stunProbeResult {
	urls := make([]string, 0, reachable)
	for i := 0; i < reachable; i++ {
		urls = append(urls, "stun"+string(rune('a'+i))+".example")
	}
	nt := natTypeFullCone
	if sym {
		nt = natTypeSymmetric // CM:6220 natType.isSymmetric()
	}
	return &stunProbeResult{NatType: nt, ReachableStunURLs: urls}
}

func TestEffectiveMaxCyclesMatchesMod(t *testing.T) {
	// DEFAULT 档：maxCycles=4、maxSymCycles=6、fallbackCycles=3（punch_profile.go:123 首行）
	p := punchDefault
	if got := effectiveMaxCycles(p, nil); got != p.fallbackCycles {
		t.Fatalf("无 STUN 结果应用 fallbackCycles=%d，得到 %d", p.fallbackCycles, got)
	}
	// 非对称：max(1, min(reachable, maxCycles))
	if got := effectiveMaxCycles(p, probeWith(false, 2)); got != 2 {
		t.Fatalf("reachable=2 非对称应为 2，得到 %d", got)
	}
	if got := effectiveMaxCycles(p, probeWith(false, 9)); got != p.maxCycles {
		t.Fatalf("可达数超过 maxCycles 时应截到 %d，得到 %d", p.maxCycles, got)
	}
	// 对称：max(2, min(reachable*2/2, maxSymCycles))——CM:6222 的 reachable*2/2 是源码字面恒等式
	if got := effectiveMaxCycles(p, probeWith(true, 1)); got != 2 {
		t.Fatalf("对称且可达数很小时下限应为 2，得到 %d", got)
	}
	if got := effectiveMaxCycles(p, probeWith(true, 9)); got != p.maxSymCycles {
		t.Fatalf("对称时应截到 maxSymCycles=%d，得到 %d", p.maxSymCycles, got)
	}
}

func TestProgressiveSweepRangeCappedByPeerWindow(t *testing.T) {
	p := punchDefault
	params := punchParamsFromProfile(p)
	prog := p.progressiveRanges[0]

	// 对端上报了更窄的窗口 → 必须按对端封顶（旧实现只用本端窗口，会过扫）
	if got := progressiveSweepRange(p, params, 0, 10); got != minInt(10, prog) {
		t.Fatalf("对端窗口 10 应封顶扫窗，得到 %d", got)
	}
	// 对端没给窗口 → 退回本端有效窗口（UHP:1258 的 : 分支）
	fallback := profileEffectivePortRange(p, params)
	if got := progressiveSweepRange(p, params, 0, 0); got != minInt(prog, fallback) {
		t.Fatalf("无对端窗口时应取 min(渐进窗, 本端有效窗)=%d，得到 %d", minInt(prog, fallback), got)
	}
	// 后期轮次要走到更宽的渐进档，但仍受对端窗口压制
	last := len(p.progressiveRanges) - 1
	late := last * maxInt(1, p.cyclesPerRange)
	if got := progressiveSweepRange(p, params, late, 10); got != minInt(p.progressiveRanges[last], 10) {
		t.Fatalf("末档仍要被对端窗口封顶，得到 %d", got)
	}
}

func TestHostSocketGroupCountsAreActuallyRead(t *testing.T) {
	// 这几项目前在应用端"声明了没人读"，接线后必须能取到模组的数
	p := punchDefault
	if got := relaySocketGroupCount(p); got != p.relaySocketCount {
		t.Fatalf("中继打洞 socket 数应为档位的 %d，得到 %d", p.relaySocketCount, got)
	}
	if got := birthdayGroupCount(p); got != p.birthdaySocketCount {
		t.Fatalf("生日组应为 %d，得到 %d", p.birthdaySocketCount, got)
	}
	if got := socketCreateGap(p); got.Milliseconds() != int64(p.socketCreateIntervalMs) {
		t.Fatalf("建 socket 节流应为 %dms，得到 %v", p.socketCreateIntervalMs, got)
	}
	if got := reverseBackoffDelay(0); got.Milliseconds() != 1000 {
		t.Fatalf("反向退避第 0 拍应为 1000ms（CM:376），得到 %v", got)
	}
	if got := reverseBackoffDelay(9); got.Milliseconds() != 4000 {
		t.Fatalf("越界应取末档 4000ms，得到 %v", got)
	}
}

// 节流建组：数量对、可被取消打断、第一把就是调用方给的 socket
func TestMakeThrottledPunchSocketGroup(t *testing.T) {
	first, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4zero, Port: 0})
	if err != nil {
		t.Fatal(err)
	}
	defer first.Close()
	group := makeThrottledPunchSocketGroup(first, 3, 0, nil)
	if len(group) != 3 || group[0] != first {
		t.Fatalf("应建 3 把且首把复用调用方 socket，实际 %d 把", len(group))
	}
	for _, c := range group[1:] {
		_ = c.Close()
	}
}
