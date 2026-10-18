//go:build cli

package main

// CLI 命令面的健壮性回归。起因是真机测出来的崩溃：
//
//	voxlink room update --name "改名试试"
//	panic: interface conversion: interface {} is nil, not bool
//	  main.cliParam.bind(...) cli_commands.go:32
//
// room update 的 --public 是"可选开关"（不写 Default），而 bind 里对 bool/int 参数
// 用硬类型断言取默认值 → 只要声明了 Kind:"bool" 却没给 Default，命令在**绑定阶段**就炸，
// 启动器连一行 JSON 都读不到。这类问题不该靠人肉记着"哪个参数没写 Default"。

import (
	"flag"
	"io"
	"path/filepath"
	"strings"
	"testing"
)

// 1) 每条命令的每个参数都必须能安全绑定 + 读取（含"没给 Default"的情况）。
func TestCLIAllParamsBindSafely(t *testing.T) {
	if len(cliCommands) == 0 {
		t.Fatal("命令表是空的")
	}
	seen := map[string]bool{}
	for _, cmd := range cliCommands {
		seen[cmd.Path] = true
		for _, p := range cmd.Params {
			fs := flag.NewFlagSet(cmd.Path, flag.ContinueOnError)
			fs.SetOutput(io.Discard)
			func() {
				defer func() {
					if r := recover(); r != nil {
						t.Errorf("%s 的 --%s 绑定阶段 panic：%v（Kind=%q Default=%#v）",
							cmd.Path, p.Name, r, p.Kind, p.Default)
					}
				}()
				p.bind(fs)
				if _, err := p.read(fs); err != nil {
					t.Errorf("%s 的 --%s 未赋值时 read 报错：%v", cmd.Path, p.Name, err)
				}
			}()
			if fs.Lookup(p.Name) == nil {
				t.Errorf("%s 的 --%s 没被注册进 FlagSet", cmd.Path, p.Name)
			}
		}
	}
	// 命令面不能少（本轮交付的都在）
	// describe 不在命令表里（它是 cli_main.go 的内置命令），所以只核对表内该有的
	for _, want := range []string{"room create", "room join", "room update", "lobby list",
		"net stun", "net ports", "settings save", "ticket submit"} {
		if !seen[want] {
			t.Errorf("命令表缺 %q", want)
		}
	}
}

// 2) 复现崩溃场景本身：无会话时 room update 必须回错误信封，而不是 panic。
func TestCLIRoomUpdateWithoutSessionReturnsError(t *testing.T) {
	a := NewApp()
	a.settingsPath = filepath.Join(t.TempDir(), "settings.json")
	out := a.SendRoomUpdate(`{"name":"改名试试"}`)
	if !strings.Contains(out, "NO_SESSION") {
		t.Fatalf("无会话改房应回 NO_SESSION，实际：%s", out)
	}
	if !strings.Contains(out, `"ok":false`) {
		t.Fatalf("错误信封必须 ok:false，实际：%s", out)
	}
}

// 3) 可选开关不传时不能污染请求体：buildPayload 只回传被显式设置的参数。
func TestCLIPayloadOmitsUnsetFlags(t *testing.T) {
	cmd := cliCommands[0]
	for i, c := range cliCommands {
		if c.Path == "room update" {
			cmd = cliCommands[i]
			break
		}
	}
	fs := flag.NewFlagSet(cmd.Path, flag.ContinueOnError)
	fs.SetOutput(io.Discard)
	for _, p := range cmd.Params {
		p.bind(fs)
	}
	if err := fs.Parse([]string{"--name", "只改名"}); err != nil {
		t.Fatal(err)
	}
	payload, err := buildPayload(&cmd, fs, "")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(payload, `"name"`) {
		t.Fatalf("payload 缺 name：%s", payload)
	}
	// --public 没传 → 不能带 visible（否则会把玩家的房悄悄改成不公开）
	if strings.Contains(payload, "visible") {
		t.Errorf("未传的 --public 不该出现在请求体里：%s", payload)
	}
	if strings.Contains(payload, "password") {
		t.Errorf("未传的 --password 不该出现在请求体里：%s", payload)
	}
}
