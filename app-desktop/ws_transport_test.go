package main

// ws_transport_test.go — WS 传输硬化参数与退避/判死状态机自检
// （对照 network/SignalingWsTransport.java 常量与 markUnavailable/canUse 语义）。

import (
	"encoding/json"
	"testing"
	"time"
)

// TestWsTransportConstants 常量与 Java 侧逐一对应（毫秒值等价 time.Duration）。
func TestWsTransportConstants(t *testing.T) {
	if wsWatchdogDead != 35*time.Second { // HEARTBEAT_WATCHDOG_MS=35000 (:46)
		t.Errorf("wsWatchdogDead = %v, want 35s", wsWatchdogDead)
	}
	if wsWatchdogCheckEvery != 10*time.Second { // scheduleAtFixedRate(…,10,10,SECONDS) (:109)
		t.Errorf("wsWatchdogCheckEvery = %v, want 10s", wsWatchdogCheckEvery)
	}
	if wsClientPingEvery != 15*time.Second { // CLIENT_PING_INTERVAL_SEC=15 (:49)
		t.Errorf("wsClientPingEvery = %v, want 15s", wsClientPingEvery)
	}
	if wsConnectTimeout != 3*time.Second { // CONNECT_TIMEOUT_MS=3000 (:51)
		t.Errorf("wsConnectTimeout = %v, want 3s", wsConnectTimeout)
	}
	want := []time.Duration{10 * time.Second, 30 * time.Second, 60 * time.Second} // BACKOFF_STEPS (:38)
	if len(wsBackoffSteps) != len(want) {
		t.Fatalf("wsBackoffSteps 档数 = %d, want 3", len(wsBackoffSteps))
	}
	for i := range want {
		if wsBackoffSteps[i] != want[i] {
			t.Errorf("wsBackoffSteps[%d] = %v, want %v", i, wsBackoffSteps[i], want[i])
		}
	}
}

func newTestSession() *session {
	app := NewApp()
	return newSession(app, "ABCDEF", "tok", false)
}

// TestWsMarkUnavailableBackoff 退避三档推进 10s→30s→60s 封顶
// （markUnavailable :333-338：step 递增、取 min(step, len-1) 档）。
func TestWsMarkUnavailableBackoff(t *testing.T) {
	s := newTestSession()
	now := time.Now().UnixMilli()

	for i, wantMs := range []int64{10000, 30000, 60000, 60000} {
		s.wsMarkUnavailable("test")
		next := s.wsNextRetryAt.Load()
		delay := next - now
		// 允许 1s 计时误差：延迟档位应为表内值。
		if delay < wantMs-1000 || delay > wantMs+1000 {
			t.Errorf("第 %d 次 markUnavailable 退避 = %dms, want %dms", i+1, delay, wantMs)
		}
		if s.wsCanUse() {
			t.Errorf("第 %d 次 markUnavailable 后 canUse 应为 false", i+1)
		}
	}

	// 拨号成功重置（onConnected :340-347）
	s.wsBackoffStep.Store(0)
	s.wsNextRetryAt.Store(0)
	if !s.wsCanUse() {
		t.Error("退避清零后 canUse 应为 true")
	}
}

// TestWsCanUseBlocksSend 退避窗口内 wsSend 直接返回不可用——调用方逐请求回落 HTTP
// （Java canUse :185-187 + request :206-208 语义）。
func TestWsCanUseBlocksSend(t *testing.T) {
	s := newTestSession()
	s.wsNextRetryAt.Store(time.Now().UnixMilli() + 60000)
	if f, ok := s.wsSend("/room/heartbeat", map[string]any{}); ok || f != nil {
		t.Error("退避窗口内 wsSend 必须返回 (nil,false)")
	}
}

// TestWsSendWithoutConn 未连接时 wsSend 返回不可用（不 panic）。
func TestWsSendWithoutConn(t *testing.T) {
	s := newTestSession()
	if f, ok := s.wsSend("/x", nil); ok || f != nil {
		t.Error("无连接时 wsSend 应返回 (nil,false)")
	}
}

// TestWsKillNowNoConn 无连接时判死信号是空操作（幂等安全）。
func TestWsKillNowNoConn(t *testing.T) {
	s := newTestSession()
	s.wsKillNow() // wsKill==nil 路径不得 panic
}

// TestWsNextRetryDelay 重连等待计算：窗口关闭前=max(1s, 剩余)（Java runReconnect :407
// Math.max(1000L, nextRetryAt-now)）；窗口已过或从未退避=0（可立即重连，:379 delay=0）。
func TestWsNextRetryDelay(t *testing.T) {
	s := newTestSession()
	if d := s.wsNextRetryDelay(); d != 0 {
		t.Errorf("初始应可立即重连, got %v", d)
	}
	s.wsNextRetryAt.Store(time.Now().UnixMilli() + 45_000)
	if d := s.wsNextRetryDelay(); d < 44*time.Second || d > 45*time.Second {
		t.Errorf("退避窗口内等待 = %v, want ≈45s", d)
	}
	s.wsNextRetryAt.Store(time.Now().UnixMilli() + 100) // 剩余 <1s → 抬到 1s 下限
	if d := s.wsNextRetryDelay(); d < time.Second || d > 10*time.Second {
		t.Errorf("等待下限应为 1s, got %v", d)
	}
	s.wsNextRetryAt.Store(time.Now().UnixMilli() - 1)
	if d := s.wsNextRetryDelay(); d != 0 {
		t.Errorf("窗口已过应返回 0, got %v", d)
	}
}

// TestWsFrameIDSemantics 帧 id 指针语义：区分"缺省 id"（Java 视作 -1、丢弃）与
// "id==0 推送帧"（Java handleFrame :418-439）。
func TestWsFrameIDSemantics(t *testing.T) {
	var push wsFrame
	if err := json.Unmarshal([]byte(`{"id":0,"push":"signals","data":{"s":[],"ts":1}}`), &push); err != nil {
		t.Fatal(err)
	}
	if push.ID == nil || *push.ID != 0 || push.Push == "" {
		t.Error("id=0 且带 push 应识别为服务端主动推送帧")
	}

	var noID wsFrame
	if err := json.Unmarshal([]byte(`{"push":"signals"}`), &noID); err != nil {
		t.Fatal(err)
	}
	if noID.ID != nil {
		t.Error("缺 id 字段应保持 nil（Java 侧按 -1 丢弃，不当推送吃）")
	}

	var resp wsFrame
	if err := json.Unmarshal([]byte(`{"id":123456789,"success":true}`), &resp); err != nil {
		t.Fatal(err)
	}
	if resp.ID == nil || *resp.ID != 123456789 {
		t.Error("响应帧 id 应精确回填")
	}
}

// TestWsNativePingAPI 说明性自检：客户端心跳使用 websocket.Conn.Ping（原生 opPing 控制帧，
// 库内 writeControl 路径），不是文本帧；这里仅校验 wsClientPingEvery 与
// wsResponseTimeout 的时序关系不吞掉 tick（15s 间隔 ≥ pong 等待上限无冲突由
// 独立 goroutine 保证——见 session.go clientPing 等价实现）。
func TestWsPingTimingInvariant(t *testing.T) {
	if wsClientPingEvery >= wsWatchdogDead {
		t.Errorf("主动 ping 间隔(%v)必须短于判死阈值(%v)，否则看门狗先于 pong 触发", wsClientPingEvery, wsWatchdogDead)
	}
	if wsClientPingEvery != 15*time.Second || wsWatchdogDead != 35*time.Second {
		t.Error("ping 间隔/判死阈值必须与 Java :49/:46 一致")
	}
}
