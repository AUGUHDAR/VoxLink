//go:build cli

package main

// cli_commands.go — CLI 命令表：解析、--help 与 describe --json 都从这一张表出来，
// 保证"文档"与"程序实际接受什么"永远一致（启动器应当读 describe 而不是抄文档）。

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"runtime"
	"strings"
)

// cliParam 一个命令行参数：--kebab-name 映射到流程层 JSON 字段 camelCase。
type cliParam struct {
	Name    string // 参数名（不带 --）
	Field   string // 流程层字段名
	Kind    string // string|int|bool|strings
	Default any
	Help    string
}

func (p cliParam) bind(fs *flag.FlagSet) {
	// Default 用类型断言取，不能硬断言：room update 的 --public 这类"可选开关"
	// 刻意不写 Default，硬断言会在绑定阶段直接 panic（interface conversion: nil, not bool），
	// 玩家/启动器连一行 JSON 都拿不到。缺省一律按零值，且 buildPayload 只回传
	// 被显式设置过的参数（fs.Visit），所以零值默认不会污染请求体。
	switch p.Kind {
	case "int":
		def, _ := p.Default.(int)
		fs.Int(p.Name, def, p.Help)
	case "bool":
		def, _ := p.Default.(bool)
		fs.Bool(p.Name, def, p.Help)
	case "strings":
		fs.String(p.Name, "", p.Help)
	default:
		def, _ := p.Default.(string)
		fs.String(p.Name, def, p.Help)
	}
}

// read 取回被设置过的参数值（strings 类型按逗号切分）。
func (p cliParam) read(fs *flag.FlagSet) (any, error) {
	switch p.Kind {
	case "int":
		v := fs.Lookup(p.Name).Value.String()
		var n int
		if _, err := fmt.Sscanf(v, "%d", &n); err != nil {
			return nil, fmt.Errorf("%s 需要整数，收到 %q", p.Name, v)
		}
		return n, nil
	case "bool":
		return fs.Lookup(p.Name).Value.String() == "true", nil
	case "strings":
		raw := fs.Lookup(p.Name).Value.String()
		out := []string{}
		for _, s := range strings.Split(raw, ",") {
			if s = strings.TrimSpace(s); s != "" {
				out = append(out, s)
			}
		}
		return out, nil
	default:
		return fs.Lookup(p.Name).Value.String(), nil
	}
}

// cliCommand 一条命令。Single 非空表示流程层收的是裸字符串（如房号/工单号）而非 JSON 对象。
type cliCommand struct {
	Path    string
	Summary string
	Single  string // 取 payload 里哪个字段作为裸字符串参数
	Params  []cliParam
	Run     func(app *App, ctx context.Context, payload string) (string, error)
}

// roomCreateParams 建房参数（与 UI 版同一套字段名）。
var roomCreateParams = []cliParam{
	{Name: "name", Field: "name", Kind: "string", Help: "房间名"},
	{Name: "port", Field: "hostPort", Kind: "int", Default: 25565, Help: "本地游戏端口"},
	{Name: "password", Field: "password", Kind: "string", Help: "房间密码（可空）"},
	{Name: "category", Field: "category", Kind: "string", Default: "other", Help: "分类标签"},
	{Name: "loader", Field: "loader", Kind: "string", Default: "unknown", Help: "加载器名，如实上报"},
	{Name: "game-version", Field: "gameVersion", Kind: "string", Default: "unknown", Help: "游戏版本"},
	{Name: "public", Field: "visible", Kind: "bool", Default: true, Help: "是否公开进大厅"},
	// clientTag 给后台/大厅显示"这是哪个客户端"（模组侧显示的是 forge/neoforge 这类加载器标签）。
	// 上限 10 个字（含中文），清洗后随本次 create 上报；不传则用 `settings save --client-tag` 的持久值。
	{Name: "client-tag", Field: "clientTag", Kind: "string",
		Help: "服务端显示的客户端标签，≤10 字（例：--client-tag=pcl2 / --client-tag=星光启动器）"},
}

var roomJoinParams = []cliParam{
	{Name: "code", Field: "code", Kind: "string", Help: "6 位房号"},
	{Name: "password", Field: "password", Kind: "string", Help: "房间密码"},
	{Name: "modsync", Field: "modsync", Kind: "string", Default: "gate",
		Help: "必装模组门控：gate|apply|skip|off（默认 gate：需要装东西就返回差异分区、不加入）"},
	{Name: "downloadTimeoutSec", Field: "downloadTimeoutSec", Kind: "int", Default: 900,
		Help: "modsync=apply 时等下载终局的秒数"},
}

var lobbyListParams = []cliParam{
	{Name: "page", Field: "page", Kind: "int", Default: 1, Help: "页码"},
	{Name: "size", Field: "size", Kind: "int", Default: 20, Help: "每页条数"},
	{Name: "category", Field: "category", Kind: "string", Help: "按分类过滤"},
	{Name: "loader", Field: "loader", Kind: "string", Help: "按加载器过滤"},
	{Name: "search", Field: "search", Kind: "string", Help: "按房间名模糊搜索"},
}

var roomUpdateParams = []cliParam{
	{Name: "name", Field: "name", Kind: "string", Help: "新房间名"},
	{Name: "category", Field: "category", Kind: "string", Help: "新分类"},
	{Name: "public", Field: "visible", Kind: "bool", Help: "是否公开"},
	{Name: "password", Field: "password", Kind: "string", Help: "新密码（空串=清除）"},
}

var ticketSubmitParams = []cliParam{
	{Name: "text", Field: "description", Kind: "string", Help: "问题描述"},
	{Name: "files", Field: "files", Kind: "strings", Help: "附件路径，逗号分隔"},
}

var ticketReplyParams = []cliParam{
	{Name: "id", Field: "id", Kind: "string", Help: "工单号"},
	{Name: "text", Field: "text", Kind: "string", Help: "追问内容"},
	{Name: "files", Field: "files", Kind: "strings", Help: "附件路径，逗号分隔"},
}

var ticketRetractParams = []cliParam{
	{Name: "id", Field: "id", Kind: "string", Help: "工单号"},
	{Name: "msg", Field: "msg", Kind: "string", Help: "要撤回的消息号"},
}

// cliCommands 全表。新增命令只加这里，别在 run() 里写分支。
var cliCommands = []cliCommand{
	{Path: "room create", Summary: "创建房间并开启会话", Params: roomCreateParams,
		Run: func(a *App, _ context.Context, p string) (string, error) { return a.CreateRoom(p), nil }},
	{Path: "room join", Summary: "加入房间并开启会话（默认先过必装模组门控）", Params: roomJoinParams,
		Run: func(a *App, _ context.Context, p string) (string, error) { return a.cliJoin(p), nil }},
	{Path: "room leave", Summary: "离开当前房间",
		Run: func(a *App, _ context.Context, _ string) (string, error) { return a.LeaveRoom(), nil }},
	{Path: "room status", Summary: "当前会话状态",
		Run: func(a *App, _ context.Context, _ string) (string, error) { return a.GetSessionState(), nil }},
	{Path: "room info", Summary: "查询房间公开信息", Single: "code",
		Params: []cliParam{{Name: "code", Field: "code", Kind: "string", Help: "6 位房号"}},
		Run:    func(a *App, _ context.Context, p string) (string, error) { return a.RoomInfo(singleOf(p, "code")), nil }},
	{Path: "room update", Summary: "修改自己房间的信息", Params: roomUpdateParams,
		Run: func(a *App, _ context.Context, p string) (string, error) { return a.SendRoomUpdate(p), nil }},
	{Path: "room mods", Summary: "查询房间模组清单", Single: "code",
		Params: []cliParam{{Name: "code", Field: "code", Kind: "string", Help: "6 位房号"}},
		Run: func(a *App, _ context.Context, p string) (string, error) {
			return a.GetRoomMods(singleOf(p, "code")), nil
		}},

	{Path: "lobby list", Summary: "列出大厅房间", Params: lobbyListParams,
		Run: func(a *App, _ context.Context, p string) (string, error) { return a.ListRooms(p), nil }},
	{Path: "lobby categories", Summary: "列出可用分类",
		Run: func(a *App, _ context.Context, _ string) (string, error) { return a.GetCategories(), nil }},

	{Path: "settings get", Summary: "读取设置",
		Run: func(a *App, _ context.Context, _ string) (string, error) { return a.GetSettings(), nil }},
	{Path: "settings save", Summary: "保存设置",
		Params: []cliParam{
			{Name: "theme", Field: "theme", Kind: "string", Help: "light|dark"},
			{Name: "client-tag", Field: "clientTag", Kind: "string",
				Help: "持久客户端标签（≤10 字）；room create --client-tag 只影响当次"},
			{Name: "relay-enabled", Field: "relayEnabled", Kind: "bool", Default: true,
				Help: "是否允许中继（默认开）"},
		},
		Run: func(a *App, _ context.Context, p string) (string, error) { return a.SaveSettings(p), nil }},

	{Path: "relay status", Summary: "中继服务是否可用",
		Run: func(a *App, _ context.Context, _ string) (string, error) { return a.RelayStatus(), nil }},
	{Path: "relay list", Summary: "中继节点列表",
		Run: func(a *App, _ context.Context, _ string) (string, error) { return a.RelayList(), nil }},

	{Path: "net stun", Summary: "服务端看到的本机公网地址",
		Run: func(a *App, _ context.Context, _ string) (string, error) { return a.GetStun(), nil }},
	{Path: "net ports", Summary: "本机在监听的游戏端口",
		Run: func(a *App, _ context.Context, _ string) (string, error) { return a.DetectMcPorts(), nil }},

	{Path: "ticket list", Summary: "本机工单列表",
		Run: func(a *App, _ context.Context, _ string) (string, error) { return a.TicketList(), nil }},
	{Path: "ticket poll", Summary: "轮询未读与状态",
		Run: func(a *App, _ context.Context, _ string) (string, error) { return a.TicketPoll(), nil }},
	{Path: "ticket submit", Summary: "提交工单", Params: ticketSubmitParams,
		Run: func(a *App, _ context.Context, p string) (string, error) { return a.TicketSubmit(p), nil }},
	{Path: "ticket detail", Summary: "工单详情", Single: "id",
		Params: []cliParam{{Name: "id", Field: "id", Kind: "string", Help: "工单号"}},
		Run: func(a *App, _ context.Context, p string) (string, error) {
			return a.TicketDetail(singleOf(p, "id")), nil
		}},
	{Path: "ticket reply", Summary: "追问", Params: ticketReplyParams,
		Run: func(a *App, _ context.Context, p string) (string, error) { return a.TicketReply(p), nil }},
	{Path: "ticket retract", Summary: "撤回自己最后一条追问", Params: ticketRetractParams,
		Run: func(a *App, _ context.Context, p string) (string, error) { return a.TicketRetract(p), nil }},
	{Path: "ticket viewed", Summary: "标记已读", Single: "id",
		Params: []cliParam{{Name: "id", Field: "id", Kind: "string", Help: "工单号"}},
		Run: func(a *App, _ context.Context, p string) (string, error) {
			return a.TicketViewed(singleOf(p, "id")), nil
		}},
	{Path: "ticket delete", Summary: "本地软删除工单", Single: "id",
		Params: []cliParam{{Name: "id", Field: "id", Kind: "string", Help: "工单号"}},
		Run: func(a *App, _ context.Context, p string) (string, error) {
			return a.TicketDelete(singleOf(p, "id")), nil
		}},
}

// lookupCommand 用最长前缀匹配 "域 动作"，返回剩余参数。
func lookupCommand(args []string) (*cliCommand, []string, error) {
	two := strings.Join(args[:min(2, len(args))], " ")
	one := args[0]
	for i := range cliCommands {
		c := &cliCommands[i]
		if c.Path == two || (len(strings.Split(c.Path, " ")) == 1 && c.Path == one) {
			return c, args[len(strings.Split(c.Path, " ")):], nil
		}
	}
	return nil, nil, fmt.Errorf("未知命令 %q，用 --help 或 describe --json 看可用命令", one)
}

// singleOf 从 payload JSON 里取一个裸字符串字段。
func singleOf(payload, field string) string {
	var m map[string]any
	if err := json.Unmarshal([]byte(payload), &m); err != nil {
		return ""
	}
	s, _ := m[field].(string)
	return strings.TrimSpace(s)
}

// describePayload 机器可读自述：命令表 + 版本 + 协议号 + 能力清单。
func describePayload() string {
	cmds := make([]map[string]any, 0, len(cliCommands))
	for _, c := range cliCommands {
		ps := make([]map[string]any, 0, len(c.Params))
		for _, p := range c.Params {
			ps = append(ps, map[string]any{
				"name": "--" + p.Name, "field": p.Field, "kind": p.Kind,
				"default": p.Default, "help": p.Help,
			})
		}
		cmds = append(cmds, map[string]any{
			"command": c.Path, "summary": c.Summary, "params": ps,
		})
	}
	return wrapOK(map[string]any{
		"version":         appVersion,
		"protocolVersion": clientProtocolVersion,
		"capabilities":    appCapabilities,
		"build":           "cli",
		"os":              runtime.GOOS,
		"arch":            runtime.GOARCH,
		"envelope":        "{ok,data} / {ok:false,error,code,message}",
		"exitCodes":       map[string]any{"0": "ok", "1": "business failure", "2": "usage error"},
		"globalFlags": []map[string]any{
			{"name": "--events", "kind": "string", "help": "none|ndjson，长任务事件流"},
			{"name": "--timeout", "kind": "duration", "help": "驻留上限，如 90s；0=不限"},
			{"name": "--json", "kind": "string", "help": "直接给参数 JSON 对象"},
		},
		"events":   knownEvents(),
		"commands": cmds,
	})
}

// knownEvents 事件名清单（前端与启动器按这个分支）。
func knownEvents() []map[string]any {
	return []map[string]any{
		{"event": "session:state", "fields": []string{"state", "room"}},
		{"event": "conn:state", "fields": []string{"phase", "status", "address", "detail"}},
		{"event": "signal", "fields": []string{"type", "from", "data", "timestamp"}},
		{"event": "net:log", "fields": []string{"level", "msg", "ts"}},
	}
}

func printUsage(w io.Writer) {
	fmt.Fprintln(w, "VoxLink CLI "+appVersion+" — 给启动器集成的无界面联机客户端")
	fmt.Fprintln(w)
	fmt.Fprintln(w, "用法: voxlink-cli <域> <动作> [参数]        一条命令 = 一行 JSON")
	fmt.Fprintln(w, "      voxlink-cli describe --json      机器可读命令表/协议号/能力清单")
	fmt.Fprintln(w, "      voxlink-cli --version            版本与协议号")
	fmt.Fprintln(w)
	fmt.Fprintln(w, "长任务（建房/加房）:")
	fmt.Fprintln(w, "      --events ndjson  结果行之后持续推事件，直到 SIGINT/SIGTERM 或 --timeout")
	fmt.Fprintln(w, "      --timeout 90s    驻留上限")
	fmt.Fprintln(w)
	fmt.Fprintln(w, "全局: --json '{...}' 直接给参数；逐项参数覆盖其中同名字段")
	fmt.Fprintln(w)
	fmt.Fprintln(w, "命令:")
	for _, c := range cliCommands {
		names := make([]string, 0, len(c.Params))
		for _, p := range c.Params {
			names = append(names, "--"+p.Name)
		}
		line := fmt.Sprintf("  %-20s %s", c.Path, c.Summary)
		if len(names) > 0 {
			line += "  [" + strings.Join(names, " ") + "]"
		}
		fmt.Fprintln(w, line)
	}
	fmt.Fprintln(w)
	fmt.Fprintln(w, "退出码: 0=成功  1=业务失败  2=用法错误")
}

func cliGOOS() string   { return runtime.GOOS }
func cliGOARCH() string { return runtime.GOARCH }
