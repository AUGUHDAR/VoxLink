//go:build cli

package main

// cli_modsync.go — 模组同步（ModSync）的 CLI 命令面 + `room join` 的门控接入。
//
// 为什么必须有：CLI 是给启动器/脚本用的（同一份 Go 核心，只差构建标签）。
// UI 侧的门控是前端在 JoinRoom 之前调 ModSyncGate（modsync_guest.go:564 的注释写明了这个位点），
// JoinRoom 自身不含这道门；CLI 此前没有 ModSync 任何入口 → 启动器加模组房时**完全跳过必装差异检查**。
//
// 输出纪律（用户 2026-10-12 裁决）：CLI 面向机器，不做 i18n 渲染 ——
// 文案一律以 {key,args} 形式给出，由调用方自己渲染；非核心字段一律可选，
// 调用方可以只读自己关心的部分，我们不会因为"你没用到某块"就报错。

import (
	"context"
	"encoding/json"
	"time"
)

// cliEnvelope 解出 {"ok":..,"data":{..}} 的 data；失败返回 nil。
func cliEnvelope(raw string) map[string]any {
	var j struct {
		OK   bool           `json:"ok"`
		Data map[string]any `json:"data"`
	}
	if err := json.Unmarshal([]byte(raw), &j); err != nil || !j.OK {
		return nil
	}
	return j.Data
}

func cliJSON(v any) string {
	b, err := json.Marshal(v)
	if err != nil {
		return "{}"
	}
	return string(b)
}

// cliGateOutcome ModSyncGate 的结构化结果（字段全部可选，按需要读）。
type cliGateOutcome struct {
	Allowed      bool              `json:"allowed"` // action != choose → 可以直接进
	Action       string            `json:"action"`  // proceed | choose
	Outcome      string            `json:"outcome"` // 与模组 ModSyncFetchOutcome 同键名
	Code         string            `json:"code"`
	Scope        string            `json:"scope"`
	Loader       string            `json:"loader"`
	MCVersion    string            `json:"mcVersion"`
	Downloadable []*modSyncEntry   `json:"downloadable,omitempty"`
	Incompatible []*modSyncEntry   `json:"incompatible,omitempty"`
	NotFound     []*modSyncEntry   `json:"notfound,omitempty"`
	Unresolvable []modSyncLangLine `json:"unresolvable,omitempty"`
	VersionDiff  []modSyncLangLine `json:"versionDiff,omitempty"`
	TotalBytes   int64             `json:"totalBytes"`
	PendingNote  *modSyncLangLine  `json:"pendingNote,omitempty"` // 直通时该播报的一行（key+args，不渲染）
	NoteLevel    int               `json:"noteLevel"`
}

// cliRunGate 跑一次门控并把结果结构化（gate 自身报错时把原始 JSON 透传回去）。
func (a *App) cliRunGate(payload string) (*cliGateOutcome, string) {
	raw := a.ModSyncGate(payload)
	d := cliEnvelope(raw)
	if d == nil {
		return nil, raw
	}
	var g modSyncGateResponse
	if b, err := json.Marshal(d); err != nil || json.Unmarshal(b, &g) != nil {
		return nil, raw
	}
	return &cliGateOutcome{
		Allowed:      g.Action != "choose",
		Action:       g.Action,
		Outcome:      g.Outcome,
		Code:         g.Code,
		Scope:        g.Scope,
		Loader:       g.Loader,
		MCVersion:    g.MCVersion,
		Downloadable: g.Downloadable,
		Incompatible: g.Incompatible,
		NotFound:     g.NotFound,
		Unresolvable: g.Unresolvable,
		VersionDiff:  g.VersionDiff,
		TotalBytes:   g.TotalBytes,
		PendingNote:  g.PendingNote,
		NoteLevel:    g.NoteLevel,
	}, ""
}

// cliDownloadWaitSec 轮询下载终局；返回终局载荷与是否超时。
func (a *App) cliDownloadWaitSec(timeout time.Duration) (map[string]any, bool) {
	deadline := time.Now().Add(timeout)
	for {
		d := cliEnvelope(a.ModSyncDownloadState())
		if d == nil {
			return nil, false
		}
		if running, _ := d["running"].(bool); !running {
			if last, ok := d["last"].(map[string]any); ok {
				return last, false
			}
			return map[string]any{"outcome": "unknown"}, false
		}
		if time.Now().After(deadline) {
			return nil, true
		}
		time.Sleep(300 * time.Millisecond)
	}
}

// cliJoin 是 `room join` 的实现：默认先走模组同步门控，与 UI 的顺序一致。
//
//	--modsync=gate（默认）  门控要选就直接不加入，返回差异分区，由启动器自己决定下一步
//	--modsync=apply         门控 → 下载可装项 → 等终局 → 成功才加入
//	--modsync=skip          记下跳过（ModSyncBypass）后直接加入
//	--modsync=off           完全不门控（等价旧行为，给不需要模组同步的启动器）
func (a *App) cliJoin(payload string) string {
	return a.cliJoinWith(payload, a.cliRunGate, a.JoinRoom, a.ModSyncBypass,
		a.ModSyncDownload, a.cliDownloadWaitSec, a.ModSyncCancelDownload)
}

// cliJoinWith 把三个外部动作注进来，纯逻辑可测（行为与上面完全一致，只是可断言）。
func (a *App) cliJoinWith(
	payload string,
	gate func(string) (*cliGateOutcome, string),
	join func(string) string,
	bypass func(string) string,
	download func(string) string,
	wait func(time.Duration) (map[string]any, bool),
	cancel func() string,
) string {
	mode := cliFieldString(payload, "modsync")
	if mode == "" {
		mode = "gate"
	}
	code := cliFieldString(payload, "code")
	waitSec := cliFieldInt(payload, "downloadTimeoutSec")
	if waitSec <= 0 {
		waitSec = 900
	}

	switch mode {
	case "off":
		return join(payload)
	case "skip":
		if code != "" {
			bypass(code)
		}
		return join(payload)
	case "gate", "apply":
	default:
		return wrapErr("INVALID_PARAMS", "modsync 只接受 gate|apply|skip|off")
	}

	g, raw := gate(payload)
	if g == nil {
		return raw // 门控自身报错：原样给出错误码，不掩盖
	}
	if g.Allowed {
		res := join(payload)
		// 直通提示：UI 是连接启动后经 ModSyncTakePendingNote 取一行；CLI 直接附在结果里，
		// 仍然只给 key+args，渲染交调用方（他不读这个字段也不影响加入成功）。
		if g.PendingNote != nil {
			var j map[string]any
			if json.Unmarshal([]byte(res), &j) == nil {
				j["pendingNote"] = g.PendingNote
				j["noteLevel"] = g.NoteLevel
				return cliJSON(j)
			}
		}
		return res
	}
	if mode == "gate" {
		return wrapOK(map[string]any{
			"joined": false,
			"reason": "MODSYNC_CHOOSE",
			"gate":   g,
			"next": []string{
				"room join --code=" + g.Code + " --modsync=apply",
				"room join --code=" + g.Code + " --modsync=skip",
				"modsync download --code=" + g.Code,
			},
		})
	}

	// apply：只装 downloadable；其余分区交给调用方决策（不强制玩家/启动器处理不可装项）
	if len(g.Downloadable) == 0 {
		return wrapErr("NOTHING_DOWNLOADABLE", "可自动安装的条目为 0，其余分区见 gate 结果")
	}
	start := download(cliJSON(map[string]any{"entries": g.Downloadable}))
	if cliEnvelope(start) == nil {
		return start // BUSY / NOT_CONFIGURED / INVALID_PARAMS 原样透传
	}
	last, timedOut := wait(time.Duration(waitSec) * time.Second)
	if timedOut {
		cancel()
		return wrapErr("DOWNLOAD_TIMEOUT", "下载超时，已请求取消")
	}
	outcome, _ := last["outcome"].(string)
	if outcome != "done" {
		return wrapOK(map[string]any{"joined": false, "reason": "MODSYNC_DOWNLOAD_NOT_DONE", "download": last, "gate": g})
	}
	res := join(payload)
	var j map[string]any
	if json.Unmarshal([]byte(res), &j) == nil {
		j["modSyncDownload"] = last
		return cliJSON(j)
	}
	return res
}

func cliFieldString(payload, field string) string {
	var j map[string]any
	if json.Unmarshal([]byte(payload), &j) != nil {
		return ""
	}
	if s, ok := j[field].(string); ok {
		return s
	}
	return ""
}

func cliFieldInt(payload, field string) int {
	var j map[string]any
	if json.Unmarshal([]byte(payload), &j) != nil {
		return 0
	}
	switch v := j[field].(type) {
	case float64:
		return int(v)
	case int:
		return v
	}
	return 0
}

// modsyncParams 里 code/scope 与 ModSyncGate 的入参同名，可整包直传。
var modsyncGateParams = []cliParam{
	{Name: "code", Field: "code", Kind: "string", Help: "6 位房号（VoxLink 房号才走门控）"},
	{Name: "scope", Field: "scope", Kind: "string", Help: "检查范围：required|all（留空按设置走）"},
}

var modsyncDownloadParams = []cliParam{
	{Name: "code", Field: "code", Kind: "string", Help: "6 位房号"},
	{Name: "scope", Field: "scope", Kind: "string", Help: "检查范围"},
	{Name: "downloadTimeoutSec", Field: "downloadTimeoutSec", Kind: "int", Default: 900, Help: "等下载终局的秒数"},
}

var modsyncWaitParams = []cliParam{
	{Name: "timeout-sec", Field: "timeoutSec", Kind: "int", Default: 900, Help: "等待秒数"},
}

var modsyncKeysParams = []cliParam{
	{Name: "keys", Field: "keys", Kind: "strings", Help: "版本冲突键，逗号分隔"},
}

var modsyncSettingsSaveParams = []cliParam{
	{Name: "json", Field: "jsonRaw", Kind: "string", Help: "modSyncSettingsView 的完整 JSON（可只给要改的块）"},
}

// init 把 modsync 域并进 cliCommands（命令表在 cli_commands.go，这里只追加，不动原有行）。
func init() {
	cliCommands = append(cliCommands,
		cliCommand{Path: "modsync gate", Summary: "跑加入前的必装模组门控（只查不动）", Params: modsyncGateParams,
			Run: func(a *App, _ context.Context, p string) (string, error) {
				g, raw := a.cliRunGate(p)
				if g == nil {
					return raw, nil
				}
				return wrapOK(g), nil
			}},
		cliCommand{Path: "modsync download", Summary: "门控后下载全部可自动安装条目，并等终局", Params: modsyncDownloadParams,
			Run: func(a *App, _ context.Context, p string) (string, error) {
				g, raw := a.cliRunGate(p)
				if g == nil {
					return raw, nil
				}
				if g.Allowed || len(g.Downloadable) == 0 {
					return wrapOK(map[string]any{"gate": g, "started": 0}), nil
				}
				start := a.ModSyncDownload(cliJSON(map[string]any{"entries": g.Downloadable}))
				if cliEnvelope(start) == nil {
					return start, nil
				}
				t := cliFieldInt(p, "downloadTimeoutSec")
				if t <= 0 {
					t = 900
				}
				last, timedOut := a.cliDownloadWaitSec(time.Duration(t) * time.Second)
				if timedOut {
					a.ModSyncCancelDownload()
					return wrapErr("DOWNLOAD_TIMEOUT", "下载超时，已请求取消"), nil
				}
				return wrapOK(map[string]any{"gate": g, "started": len(g.Downloadable), "download": last}), nil
			}},
		cliCommand{Path: "modsync state", Summary: "下载是否在跑 + 上一轮终局快照",
			Run: func(a *App, _ context.Context, _ string) (string, error) { return a.ModSyncDownloadState(), nil }},
		cliCommand{Path: "modsync wait", Summary: "等当前下载终局（不重新发起）", Params: modsyncWaitParams,
			Run: func(a *App, _ context.Context, p string) (string, error) {
				t := cliFieldInt(p, "timeoutSec")
				if t <= 0 {
					t = 900
				}
				last, timedOut := a.cliDownloadWaitSec(time.Duration(t) * time.Second)
				if timedOut {
					return wrapErr("DOWNLOAD_TIMEOUT", "仍在下载中"), nil
				}
				return wrapOK(map[string]any{"download": last}), nil
			}},
		cliCommand{Path: "modsync cancel", Summary: "取消进行中的下载",
			Run: func(a *App, _ context.Context, _ string) (string, error) { return a.ModSyncCancelDownload(), nil }},
		cliCommand{Path: "modsync bypass", Summary: "记下本房跳过门控（等价 UI 的直接进入）", Single: "code",
			Params: []cliParam{{Name: "code", Field: "code", Kind: "string", Help: "6 位房号"}},
			Run: func(a *App, _ context.Context, p string) (string, error) {
				return a.ModSyncBypass(singleOf(p, "code")), nil
			}},
		cliCommand{Path: "modsync clear", Summary: "清除本房门控状态", Single: "code",
			Params: []cliParam{{Name: "code", Field: "code", Kind: "string", Help: "6 位房号"}},
			Run: func(a *App, _ context.Context, p string) (string, error) {
				return a.ModSyncClearGate(singleOf(p, "code")), nil
			}},
		cliCommand{Path: "modsync skipped", Summary: "记住这些版本冲突键已被放行", Params: modsyncKeysParams,
			Run: func(a *App, _ context.Context, p string) (string, error) {
				var j struct {
					Keys []string `json:"keys"`
				}
				_ = json.Unmarshal([]byte(p), &j)
				return a.ModSyncMarkSkipped(cliJSON(j.Keys)), nil
			}},
		cliCommand{Path: "modsync note", Summary: "取直通提示一行（key+args，不渲染）",
			Run: func(a *App, _ context.Context, _ string) (string, error) { return a.ModSyncTakePendingNote(), nil }},
		cliCommand{Path: "modsync host-status", Summary: "房主侧清单服务状态",
			Run: func(a *App, _ context.Context, _ string) (string, error) { return a.ModSyncHostStatus(), nil }},
		cliCommand{Path: "modsync rebuild", Summary: "重建房主本地清单缓存",
			Run: func(a *App, _ context.Context, _ string) (string, error) { return a.ModSyncRebuild(), nil }},
		cliCommand{Path: "modsync settings", Summary: "读模组同步设置（实例/mods 目录/开关）",
			Run: func(a *App, _ context.Context, _ string) (string, error) { return a.GetModSyncSettings(), nil }},
		cliCommand{Path: "modsync settings save", Summary: "写模组同步设置", Params: modsyncSettingsSaveParams,
			Run: func(a *App, _ context.Context, p string) (string, error) {
				raw := cliFieldString(p, "jsonRaw")
				if raw == "" {
					return wrapErr("INVALID_PARAMS", "需要 --json=<modSyncSettingsView>"), nil
				}
				return a.SaveModSyncSettings(raw), nil
			}},
	)
}
