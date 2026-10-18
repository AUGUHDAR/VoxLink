package main

// nat_display.go — 加入过程中的三行 NAT 文案与房主版本行。
// 对位模组 ui/AttemptingJoinScreen.java:900-933：
//   本端/对端标签走 NatLabels.displayName（network/NatLabels.java:86-91）
//   对端原始串只在房客侧取（:912 的 !natRoom.isHost() 条件）
//   难度键走 ConnectionManager.getConnectionDifficultyKey（CM:1064-1120）
//   任一侧未知时难度文案后加 voxlink.nat.doubt（:917-919）
//   房主版本行未上报时显示 voxlink.host_version_unknown（:930-932）
//
// 本端只出"键 + 是否存疑"，不拼句子：界面用 VL.t 渲染（13 语言），CLI 按裁决不渲染文案。

// connectionDifficultyKey = CM:1064-1120，分支顺序照抄。
func connectionDifficultyKey(l, r natClass) string {
	if l == natUnknown || r == natUnknown {
		known := l // CM:1074 三元的另一支
		if l == natUnknown {
			known = r
		}
		if known == natCone {
			return "voxlink.nat.diff_medium" // CM:1078
		}
		if known.symmetric() {
			return "voxlink.nat.diff_hell" // CM:1082
		}
		return "voxlink.nat.diff_medium" // CM:1086
	}
	switch {
	case l == natCone && r == natCone:
		return "voxlink.nat.diff_easy" // CM:1096
	case l == natHardSym && r == natHardSym:
		return "voxlink.nat.diff_hell" // CM:1100
	case l == natHardSym || r == natHardSym:
		return "voxlink.nat.diff_hard" // CM:1104
	case l.symmetric() && r.symmetric():
		return "voxlink.nat.diff_hard" // CM:1108
	case l.symmetric() || r.symmetric():
		return "voxlink.nat.diff_medium" // CM:1112
	}
	return "voxlink.nat.diff_medium" // CM:1116
}

// natDisplaySnapshot 组装三行文案的键。local/remote 是本端与对端的 NAT 归类，
// hostModVersion 从房间记录取（服务端字段名就是 modVersion，roommanager.go:521）。
func (e *connEngine) natDisplaySnapshot(local, remote natClass) map[string]any {
	localRaw := natRawKeyOfProbe(stunGetCachedResult()) // :913 rawKey(cm.getStunProbeResult())
	remoteRaw := ""
	isHost := false
	if e != nil {
		e.mu.Lock()
		isHost = e.isHost
		e.mu.Unlock()
	}
	if !isHost { // :912 只有房客侧取房间 natType（房主侧那行显示自己的）
		if e != nil && e.app != nil {
			remoteRaw = e.roomNatType()
		}
	}
	l, r := local, remote
	anyUnknown := l == natUnknown || r == natUnknown // :908
	snap := map[string]any{
		"labelOpponentKey":   "voxlink.nat.label_opponent",   // :924
		"labelMineKey":       "voxlink.nat.label_mine",       // :925
		"labelDifficultyKey": "voxlink.nat.label_difficulty", // :926
		"opponentKey":        natDisplayNameKey(&r, remoteRaw),
		"mineKey":            natDisplayNameKey(&l, localRaw),
		"difficultyKey":      connectionDifficultyKey(l, r),
		"doubt":              anyUnknown, // :917-919 追加"存疑"
	}
	if e != nil && e.app != nil {
		if hv := e.app.roomHostModVersion(); hv != "" {
			snap["hostModVersion"] = hv
			snap["hostModVersionKey"] = "" // 有值时不需要"未知"文案键
		} else {
			snap["hostModVersionKey"] = "voxlink.host_version_unknown" // :931
		}
		snap["labelHostVersionKey"] = "voxlink.host_version_label" // :932
	}
	return snap
}

// roomHostModVersion 取房间记录里房主上报的模组版本（房主自己那侧同样有这个键，取不到给空）。
func (a *App) roomHostModVersion() string {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.room == nil {
		return ""
	}
	return roomStr(a.room, "modVersion")
}
