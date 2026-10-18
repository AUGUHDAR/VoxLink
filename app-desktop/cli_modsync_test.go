//go:build cli

package main

// CLI 模组同步门控的行为用例（2026-10-12）：
// 此前 CLI 完全没有 ModSync 入口，而 UI 的门控位点在前端（JoinRoom 之前调 ModSyncGate），
// JoinRoom 自身不含这道门 → 启动器用 CLI 加模组房会**静默跳过必装差异检查**。
// 下面几条把"默认就门控、要选就不硬进"钉死：把默认值改成 off、或让 choose 也加入，用例必须变红。

import (
	"encoding/json"
	"strings"
	"testing"
	"time"
)

func stubGate(action string, dl []*modSyncEntry) func(string) (*cliGateOutcome, string) {
	return func(string) (*cliGateOutcome, string) {
		return &cliGateOutcome{
			Allowed:      action != "choose",
			Action:       action,
			Outcome:      "READY",
			Code:         "ABC123",
			Downloadable: dl,
			NotFound:     []*modSyncEntry{{Slug: "missing-mod"}},
		}, ""
	}
}

func TestCLIRoomJoinDefaultsToGateAndRefusesOnChoose(t *testing.T) {
	a := NewApp()
	joined := 0
	gateCalled := 0
	join := func(string) string { joined++; return `{"ok":true,"data":{}}` }
	gate := func(s string) (*cliGateOutcome, string) {
		gateCalled++
		return stubGate("choose", nil)(s)
	}
	out := a.cliJoinWith(`{"code":"ABC123"}`, gate, join,
		func(string) string { return "" }, func(string) string { return "" },
		func(time.Duration) (map[string]any, bool) { return nil, false }, func() string { return "" })

	if gateCalled != 1 {
		t.Fatalf("默认必须门控（没带 --modsync 也要跑 gate），实际调用 %d 次", gateCalled)
	}
	if joined != 0 {
		t.Fatalf("门控要选（choose）时不得加入，实际加入了 %d 次", joined)
	}
	var env struct {
		OK   bool `json:"ok"`
		Data struct {
			Reason string `json:"reason"`
			Joined *bool  `json:"joined"`
			Gate   struct {
				Action string `json:"action"`
			} `json:"gate"`
		} `json:"data"`
	}
	if err := json.Unmarshal([]byte(out), &env); err != nil {
		t.Fatalf("输出不是合法 JSON: %v / %s", err, out)
	}
	if env.Data.Reason != "MODSYNC_CHOOSE" || env.Data.Gate.Action != "choose" {
		t.Fatalf("期望 reason=MODSYNC_CHOOSE + gate.action=choose，得到 %s", out)
	}
	if env.Data.Joined == nil || *env.Data.Joined {
		t.Fatalf("joined 字段必须显式为 false，得到 %s", out)
	}
}

func TestCLIRoomJoinOffSkipsGateEntirely(t *testing.T) {
	a := NewApp()
	gated, joined := 0, 0
	out := a.cliJoinWith(`{"code":"ABC123","modsync":"off"}`,
		func(string) (*cliGateOutcome, string) { gated++; return nil, "" },
		func(string) string { joined++; return `{"ok":true,"data":{}}` },
		func(string) string { return "" }, func(string) string { return "" },
		func(time.Duration) (map[string]any, bool) { return nil, false }, func() string { return "" })
	if gated != 0 || joined != 1 {
		t.Fatalf("modsync=off 应完全不门控直接加入，得到 gated=%d joined=%d out=%s", gated, joined, out)
	}
}

func TestCLIRoomJoinSkipBypassesThenJoins(t *testing.T) {
	a := NewApp()
	var bypassed []string
	joined, gated := 0, 0
	out := a.cliJoinWith(`{"code":"ABC123","modsync":"skip"}`,
		func(string) (*cliGateOutcome, string) { gated++; return nil, "" },
		func(string) string { joined++; return `{"ok":true,"data":{}}` },
		func(code string) string { bypassed = append(bypassed, code); return "" },
		func(string) string { return "" },
		func(time.Duration) (map[string]any, bool) { return nil, false }, func() string { return "" })
	if gated != 0 || joined != 1 || len(bypassed) != 1 || bypassed[0] != "ABC123" {
		t.Fatalf("modsync=skip 应 bypass 本房再加入，得到 gated=%d joined=%d bypassed=%v out=%s", gated, joined, bypassed, out)
	}
}

func TestCLIRoomJoinApplyWaitsThenJoinsOnDone(t *testing.T) {
	a := NewApp()
	joined, starts, cancelled := 0, 0, 0
	gate := func(string) (*cliGateOutcome, string) {
		return &cliGateOutcome{Allowed: false, Action: "choose", Code: "ABC123",
			Downloadable: []*modSyncEntry{{Slug: "a"}, {Slug: "b"}}}, ""
	}
	out := a.cliJoinWith(`{"code":"ABC123","modsync":"apply"}`, gate,
		func(string) string { joined++; return `{"ok":true,"data":{"clientId":"x"}}` },
		func(string) string { return "" },
		func(string) string { starts++; return `{"ok":true,"data":{"started":2}}` },
		func(time.Duration) (map[string]any, bool) {
			return map[string]any{"outcome": "done", "downloaded": 2}, false
		},
		func() string { cancelled++; return "" })
	if starts != 1 || joined != 1 || cancelled != 0 {
		t.Fatalf("apply 成功路径应为 下载1次→等终局→加入，得到 starts=%d joined=%d cancelled=%d out=%s", starts, joined, cancelled, out)
	}
	if !strings.Contains(out, `"modSyncDownload"`) {
		t.Fatalf("apply 结果应带上下载终局供启动器核对： %s", out)
	}
}

func TestCLIRoomJoinApplyDoesNotJoinOnFailure(t *testing.T) {
	a := NewApp()
	joined, starts := 0, 0
	gate := func(string) (*cliGateOutcome, string) {
		return &cliGateOutcome{Allowed: false, Action: "choose", Code: "ABC123",
			Downloadable: []*modSyncEntry{{Slug: "a"}}}, ""
	}
	out := a.cliJoinWith(`{"code":"ABC123","modsync":"apply"}`, gate,
		func(string) string { joined++; return `{"ok":true,"data":{}}` },
		func(string) string { return "" },
		func(string) string { starts++; return `{"ok":true,"data":{"started":1}}` },
		func(time.Duration) (map[string]any, bool) {
			return map[string]any{"outcome": "failed", "downloaded": 0}, false
		}, func() string { return "" })
	if starts != 1 || joined != 0 {
		t.Fatalf("下载失败绝不能继续加入，得到 starts=%d joined=%d out=%s", starts, joined, out)
	}
	if !strings.Contains(out, "MODSYNC_DOWNLOAD_NOT_DONE") {
		t.Fatalf("失败应给出可判读 reason，得到 %s", out)
	}
}

func TestCLIRoomJoinApplyCancelsOnTimeout(t *testing.T) {
	a := NewApp()
	joined, cancelled := 0, 0
	gate := func(string) (*cliGateOutcome, string) {
		return &cliGateOutcome{Allowed: false, Action: "choose", Code: "ABC123",
			Downloadable: []*modSyncEntry{{Slug: "a"}}}, ""
	}
	out := a.cliJoinWith(`{"code":"ABC123","modsync":"apply","downloadTimeoutSec":1}`, gate,
		func(string) string { joined++; return `{"ok":true,"data":{}}` },
		func(string) string { return "" },
		func(string) string { return `{"ok":true,"data":{"started":1}}` },
		func(time.Duration) (map[string]any, bool) { return nil, true },
		func() string { cancelled++; return "" })
	if joined != 0 || cancelled != 1 {
		t.Fatalf("超时须取消且不加入，得到 joined=%d cancelled=%d out=%s", joined, cancelled, out)
	}
	if !strings.Contains(out, "DOWNLOAD_TIMEOUT") {
		t.Fatalf("超时错误码应为 DOWNLOAD_TIMEOUT，得到 %s", out)
	}
}

// TestDownloadStateExposesTerminalSnapshot：一次性进程拿不到事件流时，终局必须还能问出来。
func TestDownloadStateExposesTerminalSnapshot(t *testing.T) {
	a := NewApp()
	a.modSyncEmitDownloadFinal(map[string]any{"outcome": "done", "downloaded": 3})
	d := cliEnvelope(a.ModSyncDownloadState())
	if d == nil {
		t.Fatal("state 读取失败")
	}
	last, ok := d["last"].(map[string]any)
	if !ok || last["outcome"] != "done" {
		t.Fatalf("state 应带 last 终局快照，得到 %#v", d)
	}
}

// TestCLICommandsCoverEveryModSyncMethod：UI 侧每个模组同步方法都要有 CLI 入口（防"少做"回归）。
func TestCLICommandsCoverEveryModSyncMethod(t *testing.T) {
	need := map[string]string{
		"ModSyncGate":            "modsync gate",
		"ModSyncDownload":        "modsync download",
		"ModSyncDownloadState":   "modsync state",
		"ModSyncCancelDownload":  "modsync cancel",
		"ModSyncBypass":          "modsync bypass",
		"ModSyncClearGate":       "modsync clear",
		"ModSyncMarkSkipped":     "modsync skipped",
		"ModSyncTakePendingNote": "modsync note",
		"ModSyncHostStatus":      "modsync host-status",
		"ModSyncRebuild":         "modsync rebuild",
		"GetModSyncSettings":     "modsync settings",
		"SaveModSyncSettings":    "modsync settings save",
	}
	have := map[string]bool{}
	for _, c := range cliCommands {
		have[c.Path] = true
	}
	var missing []string
	for m, path := range need {
		if !have[path] {
			missing = append(missing, m+"→"+path)
		}
	}
	if len(missing) > 0 {
		t.Fatalf("UI 有而 CLI 没有的模组同步入口：%v", missing)
	}
}
