//go:build cli

package main

// cli_main.go — CLI 版入口（go build -tags cli）。与 UI 版共用同一套流程层与连接引擎，
// 区别只在出口：结果走 stdout 的单行 JSON 信封，长任务事件按 NDJSON 逐行走 stdout，
// 引擎自身日志走 stderr（log.Printf 默认出口），因此 stdout 永远是干净可 pipe 的数据。
//
// 面向启动器集成的约定：
//   - 一条命令 = 一行 JSON 结果；退出码 0=ok，1=业务失败，2=用法错误；
//   - --events ndjson 进入长任务驻留模式：结果行之后持续推事件，直到收到 SIGINT/SIGTERM
//     或 --timeout 到点；
//   - 不弹对话框、不读 stdin，所有输入走参数（--files 直接给附件路径）。

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"os"
	"os/signal"
	"strings"
	"sync"
	"syscall"
	"time"
)

func main() {
	os.Exit(run(os.Args[1:]))
}

func run(args []string) int {
	if len(args) == 0 || args[0] == "-h" || args[0] == "--help" || args[0] == "help" {
		printUsage(os.Stdout)
		return 0
	}
	if args[0] == "--version" || args[0] == "version" {
		emitLine(wrapOK(map[string]any{
			"version":         appVersion,
			"protocolVersion": clientProtocolVersion,
			"capabilities":    appCapabilities,
			"build":           "cli",
			"os":              cliGOOS(),
			"arch":            cliGOARCH(),
		}))
		return 0
	}
	if args[0] == "describe" {
		emitLine(describePayload())
		return 0
	}

	cmd, rest, err := lookupCommand(args)
	if err != nil {
		emitLine(wrapErr("USAGE", err.Error()))
		return 2
	}

	fs := flag.NewFlagSet(cmd.Path, flag.ContinueOnError)
	fs.SetOutput(io.Discard)
	for _, p := range cmd.Params {
		p.bind(fs)
	}
	events := fs.String("events", "none", "none|ndjson：长任务事件流")
	timeout := fs.Duration("timeout", 0, "长任务驻留上限（如 90s、5m），0=不限")
	jsonArg := fs.String("json", "", "参数 JSON 对象；逐项参数会覆盖其中的同名字段")
	if err := fs.Parse(rest); err != nil {
		emitLine(wrapErr("USAGE", strings.TrimPrefix(err.Error(), cmd.Path+" flag: ")))
		return 2
	}
	if leftover := fs.Args(); len(leftover) > 0 {
		emitLine(wrapErr("USAGE", "多余的位置参数: "+strings.Join(leftover, " ")))
		return 2
	}

	payload, err := buildPayload(cmd, fs, *jsonArg)
	if err != nil {
		emitLine(wrapErr("INVALID_PARAMS", err.Error()))
		return 2
	}

	app := NewApp()
	sink := newStdoutSink(*events == "ndjson")
	app.AttachSink(sink, nil) // CLI 版无图形层：工单附件一律用 --files 给路径
	app.Init()

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	out, callErr := cmd.Run(app, ctx, payload)
	if callErr != nil {
		emitLine(wrapErr("INTERNAL", callErr.Error()))
		return 1
	}
	emitLine(normalizeEnvelope(out))

	// 驻留模式：结果已出口，之后只推事件，直到信号或超时。
	if *events == "ndjson" {
		waitForExit(ctx, app, *timeout)
	}
	if !isOKEnvelope(out) {
		return 1
	}
	return 0
}

// ---- 参数拼装 ----

// buildPayload 先取 --json 的对象，再用实际给出（flag.Visit 只遍历被设置的flag）的逐项参数覆盖。
func buildPayload(cmd *cliCommand, fs *flag.FlagSet, jsonArg string) (string, error) {
	m := map[string]any{}
	if strings.TrimSpace(jsonArg) != "" {
		if err := json.Unmarshal([]byte(jsonArg), &m); err != nil {
			return "", fmt.Errorf("--json 不是合法 JSON 对象: %w", err)
		}
	}
	set := map[string]bool{}
	fs.Visit(func(f *flag.Flag) { set[f.Name] = true })
	for _, p := range cmd.Params {
		if !set[p.Name] {
			continue
		}
		v, err := p.read(fs)
		if err != nil {
			return "", fmt.Errorf("参数 --%s: %w", p.Name, err)
		}
		m[p.Field] = v
	}
	if len(m) == 0 {
		return "", nil
	}
	b, err := json.Marshal(m)
	if err != nil {
		return "", fmt.Errorf("参数序列化失败: %w", err)
	}
	return string(b), nil
}

// ---- 输出 ----

// ndjsonSink 把核心层事件按行写 stdout；live=false 时全部丢弃（一次性命令只要结果行）。
type ndjsonSink struct {
	mu   sync.Mutex
	out  io.Writer
	live bool
}

func newStdoutSink(live bool) *ndjsonSink {
	return &ndjsonSink{out: os.Stdout, live: live}
}

func (s *ndjsonSink) Emit(event string, data any) {
	if !s.live {
		return
	}
	m := map[string]any{"event": event, "ts": time.Now().UnixMilli()}
	switch v := data.(type) {
	case map[string]any:
		for k, item := range v {
			m[k] = item
		}
	default:
		m["data"] = data
	}
	b, err := json.Marshal(m)
	if err != nil {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	fmt.Fprintln(s.out, string(b))
}

func emitLine(line string) {
	os.Stdout.WriteString(line)
	os.Stdout.WriteString("\n")
}

// normalizeEnvelope 给错误补机器可读 code，并压成单行。
func normalizeEnvelope(out string) string {
	var env map[string]any
	if err := json.Unmarshal([]byte(out), &env); err != nil {
		return wrapErr("INTERNAL", "结果解析失败")
	}
	if code, ok := env["error"].(string); ok && code != "" {
		env["code"] = code
	}
	b, err := json.Marshal(env)
	if err != nil {
		return wrapErr("INTERNAL", "结果序列化失败")
	}
	return string(b)
}

func isOKEnvelope(out string) bool {
	var env struct {
		OK bool `json:"ok"`
	}
	if err := json.Unmarshal([]byte(out), &env); err != nil {
		return false
	}
	return env.OK
}

// waitForExit 长任务驻留：等信号或 --timeout 到点，随后停会话再退出，避免留下半个房间。
func waitForExit(ctx context.Context, app *App, timeout time.Duration) {
	if timeout > 0 {
		tctx, cancel := context.WithTimeout(ctx, timeout)
		defer cancel()
		ctx = tctx
	}
	<-ctx.Done()
	app.Shutdown()
}
