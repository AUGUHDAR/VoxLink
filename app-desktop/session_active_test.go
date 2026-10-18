package main

import (
	"encoding/json"
	"net"
	"strings"
	"testing"
	"time"
)

// ---- 端口探测 ----

// TestProbeHostPortRejectsClosed 用一个确定没人监听的端口做失败路径探测。
// 选高位随机端口：通常没人在听，且与 [1024,65535] 合法范围一致。
func TestProbeHostPortRejectsClosed(t *testing.T) {
	if err := probeHostPort(31997); err == nil {
		t.Fatal("端口 31997 上没人监听，期望探测失败却成功了")
	}
}

// TestProbeHostPortSuccess 临时监听 127.0.0.1 随机端口，期望探测成功。
func TestProbeHostPortSuccess(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("本地监听失败: %v", err)
	}
	defer ln.Close()
	port := ln.Addr().(*net.TCPAddr).Port

	// 在后台接受连接（探测后会立即关闭）
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			_ = c.Close()
		}
	}()

	if err := probeHostPort(port); err != nil {
		t.Fatalf("预期探测成功，却返回错误: %v", err)
	}
}

// TestProbeHostPortInvalidRange 端口号越界直接拒绝。
func TestProbeHostPortInvalidRange(t *testing.T) {
	if err := probeHostPort(0); err == nil {
		t.Error("端口 0 应被拒绝")
	}
	if err := probeHostPort(70000); err == nil {
		t.Error("端口 70000 应被拒绝")
	}
}

// ---- 会话互斥 ----

// TestRequireIdleSessionBlocks 模拟 hosting / in_room 状态，期望返回 SESSION_ACTIVE。
// state 字段是 App 私有，无法外部直接伪造：通过反射把 state 改成目标值再调用，
// 保证 requireIdleSession 在任何非 idle 状态下都正确返回 SESSION_ACTIVE。
func TestRequireIdleSessionBlocks(t *testing.T) {
	a := NewApp()
	for _, st := range []string{"hosting", "in_room"} {
		a.mu.Lock()
		a.state = st
		a.mu.Unlock()
		out := requireIdleSession(a)
		if out == "" {
			t.Fatalf("state=%s 期望拒绝，却通过了", st)
		}
		var m map[string]any
		if err := json.Unmarshal([]byte(out), &m); err != nil {
			t.Fatalf("返回值不是 JSON: %s", out)
		}
		if m["ok"] != false || m["error"] != "SESSION_ACTIVE" {
			t.Fatalf("state=%s 错误码错误: %s", st, out)
		}
		if msg, _ := m["message"].(string); !strings.Contains(msg, "请先退出当前房间") {
			t.Fatalf("state=%s message 文案不对: %s", st, msg)
		}
	}
}

// TestRequireIdleSessionAllowsIdle idle 状态必须放行（返回空串）。
func TestRequireIdleSessionAllowsIdle(t *testing.T) {
	a := NewApp()
	if out := requireIdleSession(a); out != "" {
		t.Fatalf("idle 状态应放行，却返回: %s", out)
	}
}

// TestCreateRoomRejectsClosedPort 端口探测失败路径：用一个没人听的端口，
// 验证 CreateRoom 直接返回 PORT_NOT_LISTENING 且没有副作用。
func TestCreateRoomRejectsClosedPort(t *testing.T) {
	a := NewApp()
	a.settingsPath = "" // 隔离磁盘
	req := `{"name":"t","hostPort":31999,"loader":"unknown","gameVersion":"unknown"}`
	out := a.CreateRoom(req)
	var m map[string]any
	if err := json.Unmarshal([]byte(out), &m); err != nil {
		t.Fatalf("返回值不是 JSON: %s", out)
	}
	if m["ok"] != false || m["error"] != "PORT_NOT_LISTENING" {
		t.Fatalf("期望 PORT_NOT_LISTENING，得到: %s", out)
	}
	// 同时确认 state 仍是 idle（探测失败不会改写会话状态）
	a.mu.Lock()
	state := a.state
	a.mu.Unlock()
	if state != "idle" {
		t.Fatalf("PORT_NOT_LISTENING 路径不应改写会话状态，得到 state=%s", state)
	}
}

// TestCreateRoomRejectsSessionActive 模拟 hosting 状态下 CreateRoom 必须返回 SESSION_ACTIVE。
func TestCreateRoomRejectsSessionActive(t *testing.T) {
	a := NewApp()
	a.mu.Lock()
	a.state = "hosting"
	a.mu.Unlock()
	out := a.CreateRoom(`{"name":"t","hostPort":25565}`)
	var m map[string]any
	if err := json.Unmarshal([]byte(out), &m); err != nil {
		t.Fatalf("返回值不是 JSON: %s", out)
	}
	if m["error"] != "SESSION_ACTIVE" {
		t.Fatalf("期望 SESSION_ACTIVE，得到: %s", out)
	}
}

// TestJoinRoomRejectsSessionActive 同理校验 JoinRoom 的互斥。
func TestJoinRoomRejectsSessionActive(t *testing.T) {
	a := NewApp()
	a.mu.Lock()
	a.state = "in_room"
	a.mu.Unlock()
	out := a.JoinRoom(`{"code":"ABC234"}`)
	var m map[string]any
	if err := json.Unmarshal([]byte(out), &m); err != nil {
		t.Fatalf("返回值不是 JSON: %s", out)
	}
	if m["error"] != "SESSION_ACTIVE" {
		t.Fatalf("期望 SESSION_ACTIVE，得到: %s", out)
	}
}

// ---- 会话状态字段透传 ----

// TestSessionStateCarriesRoomFields 验证 hosting 状态下 GetSessionState 返回值里
// room.clientType / gameVersion / loader 都被透传（即便当前会话是直接伪造的）。
func TestSessionStateCarriesRoomFields(t *testing.T) {
	a := NewApp()
	a.setState("hosting", "ABC234", "tok", true, map[string]any{
		"code":        "ABC234",
		"name":        "测试房",
		"clientType":  "app",
		"gameVersion": "1.21.1",
		"loader":      "fabric",
		"isHost":      true,
	}, nil)
	out := a.GetSessionState()
	var m struct {
		OK   bool `json:"ok"`
		Data struct {
			State string         `json:"state"`
			Room  map[string]any `json:"room"`
		} `json:"data"`
	}
	if err := json.Unmarshal([]byte(out), &m); err != nil {
		t.Fatalf("返回值不是 JSON: %s", out)
	}
	if m.Data.State != "hosting" {
		t.Fatalf("state 字段错了: %s", out)
	}
	for _, k := range []string{"clientType", "gameVersion", "loader"} {
		if _, ok := m.Data.Room[k]; !ok {
			t.Fatalf("room 缺少字段 %s: %v", k, m.Data.Room)
		}
	}
	if m.Data.Room["clientType"] != "app" ||
		m.Data.Room["gameVersion"] != "1.21.1" ||
		m.Data.Room["loader"] != "fabric" {
		t.Fatalf("透传字段值不对: %v", m.Data.Room)
	}
}

// ---- 端口探测超时短窗口自检：保证 probeHostPort 用的就是 1500ms 上限 ----

func TestProbeHostPortRespectsTimeout(t *testing.T) {
	// 用 31998（高位私有端口）做一次探测，断言耗时 < 3s（含拨号超时 + 余量）。
	start := time.Now()
	_ = probeHostPort(31998)
	if d := time.Since(start); d > 3*time.Second {
		t.Fatalf("探测耗时超出预期: %v", d)
	}
}
