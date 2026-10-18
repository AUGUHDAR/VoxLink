package main

// NAT 三行文案的用例。参照物：AttemptingJoinScreen.java:900-933、
// NatLabels.displayName（NatLabels.java:86-91）、getConnectionDifficultyKey（CM:1064-1120）。

import "testing"

func TestConnectionDifficultyKeyMatrix(t *testing.T) {
	cases := []struct {
		l, r natClass
		want string
	}{
		{natCone, natCone, "voxlink.nat.diff_easy"},         // CM:1094-1096
		{natHardSym, natHardSym, "voxlink.nat.diff_hell"},   // CM:1098-1100
		{natHardSym, natCone, "voxlink.nat.diff_hard"},      // CM:1102-1104 任一侧 hard_sym
		{natCone, natHardSym, "voxlink.nat.diff_hard"},      // 同上，另一侧
		{natEasySym, natEasySym, "voxlink.nat.diff_hard"},   // CM:1106-1108 双对称（非 hard）
		{natEasySym, natCone, "voxlink.nat.diff_medium"},    // CM:1110-1112 单侧对称
		{natCone, natUnknown, "voxlink.nat.diff_medium"},    // CM:1076-1078 已知侧是锥
		{natEasySym, natUnknown, "voxlink.nat.diff_hell"},   // CM:1080-1082 已知侧对称
		{natUnknown, natUnknown, "voxlink.nat.diff_medium"}, // CM:1084-1086 两边都未知
		{natUnknown, natHardSym, "voxlink.nat.diff_hell"},   // CM:1074 取非未知那一侧
	}
	for _, c := range cases {
		if got := connectionDifficultyKey(c.l, c.r); got != c.want {
			t.Errorf("%v x %v 期望 %s，得到 %s", c.l, c.r, c.want, got)
		}
	}
}

func TestNatDisplaySnapshotKeys(t *testing.T) {
	a := NewApp()
	a.mu.Lock()
	a.state = "in_room"
	a.isHost = false
	a.room = map[string]any{"natType": "port_restricted_cone", "modVersion": "1.2.0"}
	e := a.engine
	e.mu.Lock()
	e.sess = &session{app: a}
	e.isHost = false
	e.gLocalNat = natCone
	e.gRemoteNat = natUnknown
	e.mu.Unlock()
	a.mu.Unlock()

	snap := e.natDisplaySnapshot(natCone, natUnknown)
	if snap["labelMineKey"] != "voxlink.nat.label_mine" || snap["labelOpponentKey"] != "voxlink.nat.label_opponent" ||
		snap["labelDifficultyKey"] != "voxlink.nat.label_difficulty" {
		t.Fatalf("三行标签键必须与模组一致（:924-926），得到 %+v", snap)
	}
	if snap["mineKey"] != "voxlink.nat.cone" {
		t.Fatalf("已归类的本端要用粗粒度文案（NatLabels.java:86-91），得到 %v", snap["mineKey"])
	}
	// 对端归类为 UNKNOWN 时回落原始细粒度串（:909-910 的显示层归一就是这个目的）
	if snap["opponentKey"] != "voxlink.nat.port_restricted_cone" {
		t.Fatalf("房客侧对端文案要取房间的 natType（:912），得到 %v", snap["opponentKey"])
	}
	if snap["doubt"] != true {
		t.Fatalf("任一侧未知要置存疑（:917-919），得到 %v", snap["doubt"])
	}
	if snap["difficultyKey"] != "voxlink.nat.diff_medium" {
		t.Fatalf("锥型×未知应为中等（CM:1076-1078），得到 %v", snap["difficultyKey"])
	}
	if snap["hostModVersion"] != "1.2.0" {
		t.Fatalf("房主版本要取房间记录里的 modVersion（:930），得到 %v", snap["hostModVersion"])
	}

	// 房主没上报版本时给"未知"文案键（:931）
	a.mu.Lock()
	a.room["modVersion"] = ""
	a.mu.Unlock()
	snap2 := e.natDisplaySnapshot(natCone, natCone)
	if snap2["hostModVersionKey"] != "voxlink.host_version_unknown" {
		t.Fatalf("缺版本时要给 voxlink.host_version_unknown，得到 %+v", snap2)
	}
	if snap2["doubt"] != false {
		t.Fatalf("两侧都已知不该报存疑，得到 %v", snap2["doubt"])
	}

	// 房主侧不取房间 natType 当对端（:912 的 !isHost 条件）
	a.mu.Lock()
	a.isHost = true
	a.room["natType"] = "symmetric"
	a.mu.Unlock()
	e.mu.Lock()
	e.isHost = true
	e.mu.Unlock()
	snap3 := e.natDisplaySnapshot(natUnknown, natUnknown)
	if v, _ := snap3["opponentKey"].(string); v == "voxlink.nat.symmetric" {
		t.Fatal("房主侧的对端文案不该读自己房间的 natType（模组只在房客侧读，:912）")
	}
}
