package main

// modsync_logfmt_test.go — 回归：ModSync 日志出口用 SLF4J 的 `{}` 占位
// （modsync_log.go 的 modSyncFormat，逐句对齐模组 ModSyncLog.java → LOGGER.info(msg, args)），
// 但曾有一批调用点被写成 Go 的 fmt 动词（`%s`/`%d`/`%v`）并把带模板的串直接丢进该出口：
// 出口只认 `{}`，于是动词原样透传、参数被静默丢弃，玩家在连接日志里看到
//   "[ModSync] gate: player bypassed, room=%s"
//   "[ModSync] proactive push %s ok (%d mods)"
// 这类没信息量的字面量。
//
// 本文件三档自检，均不写任何磁盘文件（测试卫生：不碰 %APPDATA%/VoxLinkApp）：
//   1) 静态扫源码：每个 modSyncLog* 调用点的格式串不得含 `%` 动词，且 `{}` 个数须与实参个数相等；
//   2) 行为单测：modSyncFormat 把玩家实际看到的模板渲染成干净字符串；
//   3) 端到端：走真实出口（app==nil → log.Printf 回落）捕获渲染结果，断言无残留占位符。

import (
	"bytes"
	"go/ast"
	"go/parser"
	"go/token"
	"log"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"testing"
)

// residualVerbRe 匹配任何 Go fmt 动词或 fmt 溢出错位符（%!、%s、%d、%v …）。
var residualVerbRe = regexp.MustCompile(`%(!|s|d|v|q|x|X|f|F|e|E|g|G|c|o|b|p|U|t)`)

// assertNoResidualPlaceholder 断言渲染后的串里不再含任何残留占位符（Go 动词 / fmt 错位 / 未替换的 {}）。
func assertNoResidualPlaceholder(t *testing.T, rendered string) {
	t.Helper()
	if residualVerbRe.MatchString(rendered) {
		t.Errorf("渲染结果仍含未替换的格式化占位符: %q", rendered)
	}
	if strings.Contains(rendered, "%!") {
		t.Errorf("渲染结果含 fmt 溢出错位符 %%!: %q", rendered)
	}
	if strings.Contains(rendered, "{}") {
		t.Errorf("渲染结果仍含未替换的 {} 占位（参数缺失）: %q", rendered)
	}
}

// TestModSyncFormatRendersReportedLines 用玩家截图里的两条真实模板验证渲染。
func TestModSyncFormatRendersReportedLines(t *testing.T) {
	got := modSyncFormat("gate: player bypassed, room={}", "ABC123")
	assertNoResidualPlaceholder(t, got)
	if got != "gate: player bypassed, room=ABC123" {
		t.Errorf("渲染不符: got=%q", got)
	}

	got2 := modSyncFormat("proactive push {} ok ({} mods)", "required", 7)
	assertNoResidualPlaceholder(t, got2)
	if !strings.Contains(got2, "push required ok (7 mods)") {
		t.Errorf("参数未代入: got=%q", got2)
	}

	// 对照：旧的错误写法（Go 动词直丢出口）本应把 %s 原样透传——证明动词确会被丢弃。
	if old := modSyncFormat("gate: player bypassed, room=%s", "ABC123"); old != "gate: player bypassed, room=%s" {
		t.Errorf("对照失败：动词应原样残留，实际 %q", old)
	}
}

// TestModSyncLogExitHasNoResidualPlaceholder 走真实出口，捕获渲染行，断言无残留占位符。
// app==nil 时 modSyncLog 回落到包级 log.Printf，不触碰磁盘，也不写玩家配置。
func TestModSyncLogExitHasNoResidualPlaceholder(t *testing.T) {
	// 强制走 nil-app 回落路径；测后复原，避免污染同进程其它用例。
	modSyncSvc.mu.Lock()
	prev := modSyncSvc.app
	modSyncSvc.app = nil
	modSyncSvc.mu.Unlock()
	defer func() {
		modSyncSvc.mu.Lock()
		modSyncSvc.app = prev
		modSyncSvc.mu.Unlock()
	}()

	var buf bytes.Buffer
	oldW := log.Writer()
	log.SetOutput(&buf)
	defer log.SetOutput(oldW)

	modSyncLogInfo("gate: player bypassed, room={}", "ABC123")
	modSyncLogInfo("proactive push {} ok ({} mods)", "required", 7)
	modSyncLogWarn("mods 数量超过 {}，超出部分未参与校验 (jars={}, disabled={})", 256, 300, 12)

	out := buf.String()
	assertNoResidualPlaceholder(t, out)
	for _, want := range []string{"room=ABC123", "push required ok (7 mods)", "数量超过 256"} {
		if !strings.Contains(out, want) {
			t.Errorf("出口渲染缺内容 %q，实际=%q", want, out)
		}
	}
}

// modSyncLogWrapperNames 是会把格式串交给 `{}` 出口的日志封装。
var modSyncLogWrapperNames = map[string]bool{
	"modSyncLogInfo":  true,
	"modSyncLogWarn":  true,
	"modSyncLogError": true,
}

// TestModSyncLogCallSitesUseBracePlaceholders 静态扫全部 modsync_*.go（非测试）：
// 每个 modSyncLog* 调用点的格式串不得含 Go fmt 动词（`%`），且 `{}` 个数须等于实参个数。
// 这直接在调用点锁死本轮缺陷：既防止重新引入 `%s`/`%d`/`%v`，也保证参数真的被传。
func TestModSyncLogCallSitesUseBracePlaceholders(t *testing.T) {
	files, err := filepath.Glob("modsync_*.go")
	if err != nil {
		t.Fatalf("glob 失败: %v", err)
	}
	var checked int
	fset := token.NewFileSet()
	for _, name := range files {
		if strings.HasSuffix(name, "_test.go") {
			continue
		}
		f, err := parser.ParseFile(fset, name, nil, 0)
		if err != nil {
			t.Fatalf("解析 %s 失败: %v", name, err)
		}
		ast.Inspect(f, func(n ast.Node) bool {
			call, ok := n.(*ast.CallExpr)
			if !ok {
				return true
			}
			fn, ok := call.Fun.(*ast.Ident)
			if !ok || !modSyncLogWrapperNames[fn.Name] {
				return true
			}
			pos := fset.Position(call.Pos())
			if len(call.Args) == 0 {
				t.Errorf("%s:%d 空 modSyncLog 调用", pos.Filename, pos.Line)
				return true
			}
			bl, ok := call.Args[0].(*ast.BasicLit)
			if !ok || bl.Kind != token.STRING {
				t.Errorf("%s:%d 格式串不是字符串字面量", pos.Filename, pos.Line)
				return true
			}
			format, err := strconv.Unquote(bl.Value)
			if err != nil {
				t.Errorf("%s:%d 无法解引用格式串: %v", pos.Filename, pos.Line, err)
				return true
			}
			checked++
			// 出口只认 `{}`：格式串里出现任何 `%` 动词都会原样透传、参数被丢。
			if residualVerbRe.MatchString(format) {
				t.Errorf("%s:%d 格式串仍含 Go fmt 动词（应改为 {}）: %q", pos.Filename, pos.Line, format)
			}
			// 占位符个数须与实参个数匹配，否则 `{}` 会残留（参数缺失）或多参被忽略。
			if braces, args := strings.Count(format, "{}"), len(call.Args)-1; braces != args {
				t.Errorf("%s:%d 占位符/参数不匹配: {}=%d args=%d fmt=%q", pos.Filename, pos.Line, braces, args, format)
			}
			return true
		})
	}
	if checked == 0 {
		t.Fatal("未检查到任何 modSyncLog 调用点（扫描器失效？）")
	}
	t.Logf("静态核对 %d 个 modSyncLog 调用点：无残留动词、占位符与参数一一对应", checked)
}
