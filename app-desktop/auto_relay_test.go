package main

// auto_relay_test.go — 编排器的档位与门序行为用例。
//
// 为什么单独测这一层：模组的验收口径是"每项都能单独回退并让对应用例变红"。
// 编排器最容易被改坏的不是换道，而是门序——把"零收包"门挪到档位后面，
// 或者把 tierB 的 10 写成封顶值 50，用例必须当天就叫。

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"
	"time"
)

type captureSink struct {
	events []map[string]any
}

func (s *captureSink) Emit(event string, data any) {
	if m, ok := data.(map[string]any); ok {
		m["_event"] = event
		s.events = append(s.events, m)
	}
}

// newAutoRelayTestApp 会话装好、事件出口接上、API 指向假服务器（编排器 fireAutoTurn 会去拉节点列表，
// 绝不允许打到生产：测试只在虚假环境做）。
func newAutoRelayTestApp(t *testing.T) (*App, *captureSink) {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"success": false, "error": "RELAY_DISABLED"})
	}))
	t.Cleanup(srv.Close)

	a := NewApp()
	a.settingsPath = filepath.Join(t.TempDir(), "settings.json")
	a.settings.ServerURL = srv.URL
	sink := &captureSink{}
	a.AttachSink(sink, nil)
	a.Init()
	a.settings.ServerURL = srv.URL // Init 会重载设置，这里再钉住假基址

	room := map[string]any{"code": "AB12CD", "clientType": "app"}
	s := newSession(a, "AB12CD", "client-token", false)
	a.setState("in_room", "AB12CD", "client-token", false, room, s)
	a.engine.bindSession(s, "client-9")
	t.Cleanup(func() { a.engine.unbindSession(s) })
	return a, sink
}

func connEvents(sink *captureSink) []map[string]any {
	var out []map[string]any
	for _, e := range sink.events {
		if e["_event"] == "conn:state" {
			out = append(out, e)
		}
	}
	return out
}

// TestAutoRelayDirectorGates 三道"不该触发"的门：没开始打洞、时长不足、收过包。
func TestAutoRelayDirectorGates(t *testing.T) {
	a, sink := newAutoRelayTestApp(t)
	e := a.engine

	cases := []struct {
		name    string
		startMs int64
		recv    bool
	}{
		{"未开始打洞(punchUiStartMs=0)", 0, false},
		{"时长不足 10s", time.Now().UnixMilli() - 5_000, false},
		{"收过对端包（直连还有戏）", time.Now().UnixMilli() - 60_000, true},
	}
	for _, c := range cases {
		sink.events = nil
		e.mu.Lock()
		e.punchUiStartMs = c.startMs
		e.punchRecvEver = c.recv
		e.gLocalNat = natUnknown
		e.gRemoteNat = natUnknown
		e.predictionOffCount = 0
		e.mu.Unlock()
		e.autoRelayTickOnce(e.sess)
		if got := connEvents(sink); len(got) != 0 {
			t.Fatalf("%s：不该触发任何中继，实际 %v", c.name, got)
		}
	}
}

// TestAutoRelayTierCIsFallback 兜底档：无 NAT 证据、但打洞满 45s 且整会话零收包 → 触发 TURN 优先。
func TestAutoRelayTierCIsFallback(t *testing.T) {
	a, sink := newAutoRelayTestApp(t)
	e := a.engine
	e.mu.Lock()
	e.punchUiStartMs = time.Now().UnixMilli() - 46_000
	e.punchRecvEver = false
	e.gLocalNat = natUnknown
	e.gRemoteNat = natUnknown
	e.predictionOffCount = 0
	e.mu.Unlock()

	sink.events = nil // bindSession 会先播一条 p2p/trying，测的是这一拍的输出
	e.autoRelayTickOnce(e.sess)
	got := connEvents(sink)
	if len(got) == 0 {
		t.Fatal("tierC 兜底档未触发中继")
	}
	if got[0]["phase"] != phaseTurn || got[0]["status"] != statusTrying {
		t.Fatalf("中继优先序应为 TURN 先，实际 %v", got[0])
	}
	e.mu.Lock()
	turnTried, mode := e.autoRelayTurnAttempted, e.autoRelayMode
	e.mu.Unlock()
	if !turnTried || !mode {
		t.Fatalf("触发后尝试位与自动模式位应置真: turnTried=%v mode=%v", turnTried, mode)
	}
}

// TestAutoRelayTierBNotUsingPredictionCap tierB 的 PREDICTION_OFF 阈是 10，
// 与终局封顶 50 是两个并存的常量（模组 CM:12774 vs CM:390），写成 50 就该红。
func TestAutoRelayTierBNotUsingPredictionCap(t *testing.T) {
	if autoRelayTierBPredOff != 10 || predictionOffCap != 50 {
		t.Fatalf("两个常量必须并存且不同值: tierB=%d cap=%d", autoRelayTierBPredOff, predictionOffCap)
	}
	a, _ := newAutoRelayTestApp(t)
	e := a.engine

	// 9 次 PREDICTION_OFF + 31s：既不到 tierB 的 10，也够不到 tierC 的 45s → 不触发
	e.mu.Lock()
	e.punchUiStartMs = time.Now().UnixMilli() - 31_000
	e.punchRecvEver = false
	e.predictionOffCount = 9
	e.mu.Unlock()
	e.autoRelayTickOnce(e.sess)
	e.mu.Lock()
	fired := e.autoRelayTurnAttempted
	e.mu.Unlock()
	if fired {
		t.Fatal("predOff=9 时 tierB 不该触发（阈是 10，不是 50）")
	}

	// 第 10 次 + 同样 31s → 触发
	e.mu.Lock()
	e.predictionOffCount = 10
	e.mu.Unlock()
	e.autoRelayTickOnce(e.sess)
	e.mu.Lock()
	fired = e.autoRelayTurnAttempted
	e.mu.Unlock()
	if !fired {
		t.Fatal("predOff=10 且时长够 tierB 时应触发")
	}
}

// TestAutoRelayArmedRoundResetsAttempts 轮次推进要重置两条道的尝试位：
// 新一轮打洞 = 新一次中继机会，否则第二轮起永远只会"两道尽"直接失败。
func TestAutoRelayArmedRoundResetsAttempts(t *testing.T) {
	a, _ := newAutoRelayTestApp(t)
	e := a.engine
	e.mu.Lock()
	e.punchUiStartMs = time.Now().UnixMilli() - 60_000
	e.punchRecvEver = false
	e.autoRelayArmedRound = 3
	e.autoRelayTurnAttempted = true
	e.autoRelayPlayerAttempted = true
	e.retryRound = 4
	e.mu.Unlock()

	e.autoRelayTickOnce(e.sess)
	e.mu.Lock()
	round, turnTried, playerTried := e.autoRelayArmedRound, e.autoRelayTurnAttempted, e.autoRelayPlayerAttempted
	e.mu.Unlock()
	if round != 4 {
		t.Fatalf("armed 轮应记成当前轮 4，实际 %d", round)
	}
	if !turnTried {
		t.Fatal("轮次重置后本轮应重新从 TURN 道开始")
	}
	if playerTried {
		t.Fatal("本轮 TURN 优先，玩家中继位不该同时被置真")
	}
}
