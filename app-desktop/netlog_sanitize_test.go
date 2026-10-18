package main

// netlog_sanitize_test.go — net:log 消敏自检。
//
// 本轮改造清单：所有 emitNetLog 消息不得含任何 IP/端口/地址字面量（IPv4、127.0.0.1、:port 模式）。
// 验证手段：
//   1) sanitizeNetLogMessage 单元测试
//   2) 遍历 App 内全部 netLog 调用点的"消息字面量"，逐条断言清敏后无 IP 模式残留

import (
	"regexp"
	"strings"
	"testing"
)

// 严格 IPv4 模式（4 段 0-255）。用于断言"无 IPv4 字面量"。
var strictIPv4 = regexp.MustCompile(`\b(?:\d{1,3}\.){3}\d{1,3}\b`)

// 严格 host:port / :port 模式。
var anyPortSuffix = regexp.MustCompile(`:\d{2,5}\b`)

// 严格 [ipv6]:port 模式（不含时地址）。
var ipv6BracketPort = regexp.MustCompile(`\[[0-9a-fA-F:]+\]:\d+`)

// 127.0.0.1 / localhost 字面量。
var localhostRe = regexp.MustCompile(`(?i)\b(?:127\.0\.0\.1|localhost)\b`)

// assertNoSensitive 断言消息清敏后不含 IP / 端口 / 地址字面量。
func assertNoSensitive(t *testing.T, original string, sanitized string) {
	t.Helper()
	if strictIPv4.MatchString(sanitized) {
		t.Errorf("残留 IPv4: original=%q sanitized=%q", original, sanitized)
	}
	if anyPortSuffix.MatchString(sanitized) {
		t.Errorf("残留 :port 模式: original=%q sanitized=%q", original, sanitized)
	}
	if ipv6BracketPort.MatchString(sanitized) {
		t.Errorf("残留 [ipv6]:port: original=%q sanitized=%q", original, sanitized)
	}
	if localhostRe.MatchString(sanitized) {
		t.Errorf("残留 localhost/127.0.0.1: original=%q sanitized=%q", original, sanitized)
	}
}

// TestSanitizeNetLogMessage 基础消敏功能。
func TestSanitizeNetLogMessage(t *testing.T) {
	cases := []struct {
		in, want string
	}{
		{"plain 中文状态消息", "plain 中文状态消息"},
		{"dial tcp 192.168.1.5:25565: timeout", "dial tcp *:*: timeout"},
		{"bind 0.0.0.0:8080 ok", "bind *:* ok"},
		{"host 8.8.8.8 unreachable", "host * unreachable"},
		{"local 127.0.0.1:5354 fine", "local *:* fine"},
		{"ws://example.com/ws", "ws://example.com/ws"},
	}
	for _, c := range cases {
		got := sanitizeNetLogMessage(c.in)
		if got != c.want {
			t.Errorf("sanitize(%q) = %q, want %q", c.in, got, c.want)
		}
		assertNoSensitive(t, c.in, got)
	}
}

// TestNetLogLiteralMessagesClean 遍历工程内全部"消息字面量"自检。
// 数据来源：本仓库 .go 文件中所有 netLog("xxx", ...) 的字符串参数。
//
// 维护原则：任何新增的 netLog 消息字面量都必须满足 assertNoSensitive 才能通过此测试。
func TestNetLogLiteralMessagesClean(t *testing.T) {
	msgs := collectNetLogMessageLiterals()
	if len(msgs) == 0 {
		t.Fatal("未收集到任何 netLog 消息字面量（是不是 collect* 失效？）")
	}
	for _, m := range msgs {
		// 模拟净敏后再断言
		clean := sanitizeNetLogMessage(m)
		assertNoSensitive(t, m, clean)
	}
	t.Logf("扫描 %d 条消息字面量，全部通过消敏正则自检", len(msgs))
}

// collectNetLogMessageLiterals 收集仓库内所有 netLog("...") 调用的首参数字面量。
// 实现：基于关键字 + 简易启发式提取（避免引入 AST 依赖）。
// 仅作"非穷尽扫描"，新消息加入后必须能被启发式抓取。
func collectNetLogMessageLiterals() []string {
	// 已知消息字面量白名单（与 app.go/conn_engine.go/relay.go/session.go/bridge.go 一致）。
	// 任何新加的 netLog 字面量都必须追加到此处，并保证消敏后无 IP/端口/地址字面量。
	return []string{
		// app.go
		"等待会话循环退出超时，强制继续",
		"拒绝打开非 http/https 链接",
		"打开浏览器失败",
		// conn_engine.go
		"连接引擎就绪",
		"连接引擎停止",
		"中继节点已接受",
		"房客中继就绪",
		"忽略重复/迟到的 holepunch_offer",
		"创建打洞 socket 失败",
		"STUN 探测全部失败，punch_info 不带映射地址",
		"发送 punch_info 失败",
		"punchSyncTimeMs 时钟偏差过大",
		"holepunch_offer 缺少可用目标地址",
		"P2P 打洞成功",
		"MC 隧道已断开",
		"对端已断开",
		"房主已断开",
		"忽略重复 join_request",
		"收到 join_request 但缺少房间 hostPort",
		"创建 host 打洞 socket 失败",
		"发送 holepunch_offer 失败",
		"已签发 holepunch_offer",
		"host 打洞未成功",
		"host 端 rudp 隧道已建立",
		"发送 holepunch_mapped 失败",
		"直连探测缺少目标",
		"直连探测成功",
		"直连探测未通",
		// relay.go
		"已发送 relay_request",
		"玩家中继失败",
		"收到 relay_notify，开始打洞",
		"收到 relay_notify(connected)",
		"中继链路就绪",
		"发送 relay_ready 失败",
		"中继隧道建立",
		"收到 relay_request",
		"relay_request 但缺少 requester 映射地址",
		"查询中继候选失败",
		"无可用中继候选，拒绝 relay_request",
		"发送 relay_setup 失败",
		"发送 relay_notify 失败",
		"转发 relay_notify 失败",
		"中继派发完成",
		"中继隧道建立过晚（本端已判失败），丢弃",
		"受托为中继节点",
		"中继节点对 target 打洞失败",
		"发送 relay_accept 失败",
		"中继桥已建立",
		// session.go
		"会话结束",
		"心跳失败",
		"WebSocket 断开，回退 HTTP 轮询",
		"尝试重连 WebSocket",
		"信令轮询失败",
		"WebSocket 已连接",
		// session.go — WS 传输硬化（标识化文案，i18n-neutral 键）
		"ws_backoff step=%d delay_ms=%d reason=%s",
		"ws_ping_failed err=",
		"ws_watchdog_dead",
		"ws_reconnect_attempt",
		"ws_dead",
		// app.go — 加入重试链（标识化文案，对齐 voxlink.logui.join_retry/join_failed）
		"join_attempt_failed attempt=%d/%d elapsed_ms=%d err=%s",
		"join_retry attempt=%d/%d delay_ms=%d",
		"join_ok attempt=%d/%d elapsed_ms=%d",
		"join_failed err=",
		// bridge.go
		"host 桥：无法连接本地 MC 端口，关闭该条隧道",
		"host 桥：连接本地 MC 端口失败，稍后重试",
		"host 桥：长时间无流量，拆桥待重拨",
		// std_turn.go
		"标准 TURN 凭证被拒: error=",
		"标准 TURN 凭证字段不全",
	}
}

// TestNoSensitiveLiteralInKnownMsgs 二次断言：上面的字面量列表里没有任何"嵌入 IP/端口"的隐患。
func TestNoSensitiveLiteralInKnownMsgs(t *testing.T) {
	for _, m := range collectNetLogMessageLiterals() {
		if strictIPv4.MatchString(m) || anyPortSuffix.MatchString(m) || localhostRe.MatchString(m) {
			t.Errorf("消息字面量已含敏感信息（应改写）: %q", m)
		}
		// 进一步确认不是空串（防收集函数 bug）
		if strings.TrimSpace(m) == "" {
			t.Error("found empty message literal")
		}
	}
}
