package main

// session_ws_transport_test.go — 客户端 WS 通道自己的一条端到端（假服务端在进程内）。
//
// 为什么要这条：隔离装置里"把限流抬到不会 429"反而一条信令都收不到，而 HTTP 轮询正常。
// 服务端那半边已有回归测试（VoxLink-server/ws_signal_push_test.go：订阅+推送+定向+错 token）。
// 这条补客户端：/signal/poll 帧要按服务端认的形状发出去（含 Content-Type），
// 响应帧要能回填给 wsSend，服务端主动 push 的 signals 帧要进 handleSignals 并派发到引擎。

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/coder/websocket"
)

type fakeSignalServer struct {
	mu      sync.Mutex
	frames  []map[string]any
	pushed  int
	polls   int
	tokenOK bool
}

func (f *fakeSignalServer) handler(code string) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/ws", func(w http.ResponseWriter, r *http.Request) {
		conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{InsecureSkipVerify: true})
		if err != nil {
			return
		}
		defer conn.Close(websocket.StatusNormalClosure, "")
		ctx := context.Background()
		for {
			_, data, err := conn.Read(ctx)
			if err != nil {
				return
			}
			var fr map[string]any
			if json.Unmarshal(data, &fr) != nil {
				continue
			}
			f.mu.Lock()
			f.frames = append(f.frames, fr)
			f.mu.Unlock()

			route, _ := fr["route"].(string)
			idRaw, _ := fr["id"].(float64)
			id := int64(idRaw)
			body, _ := json.Marshal(fr["body"])
			var b map[string]any
			_ = json.Unmarshal(body, &b)

			switch route {
			case "/signal/poll":
				f.mu.Lock()
				f.polls++
				n := f.polls
				f.mu.Unlock()
				// 第一次只回空，第二次起回一条真信令（模拟 push 与 poll 两条路）
				sigs := []any{}
				if n >= 2 {
					sigs = append(sigs, map[string]any{
						"id": "abc123", "from": "host", "type": "peer_port",
						"data":      map[string]any{"peer_ip": "203.0.113.7", "peer_port": 25565},
						"timestamp": time.Now().UnixMilli(),
					})
				}
				resp := map[string]any{"id": id, "success": true,
					"data": map[string]any{"s": sigs, "ts": time.Now().UnixMilli()}}
				out, _ := json.Marshal(resp)
				_ = conn.Write(ctx, websocket.MessageText, out)
				if n >= 2 {
					f.mu.Lock()
					f.pushed++
					f.mu.Unlock()
					push, _ := json.Marshal(map[string]any{"id": 0, "push": "signals", "success": true,
						"data": map[string]any{"s": []any{map[string]any{
							"id": "def456", "from": "joiner", "type": "relay_notify",
							"data":      map[string]any{"relayIp": "203.0.113.9", "relayPort": 40001},
							"timestamp": time.Now().UnixMilli() + 1,
						}}, "ts": time.Now().UnixMilli() + 1}})
					_ = conn.Write(ctx, websocket.MessageText, push)
				}
			default:
				out, _ := json.Marshal(map[string]any{"id": id, "success": true, "data": map[string]any{}})
				_ = conn.Write(ctx, websocket.MessageText, out)
			}
		}
	})
	return mux
}

func TestSessionWSTransportPollsAndDispatchesPush(t *testing.T) {
	a, sink := newAutoRelayTestApp(t)
	fake := &fakeSignalServer{}
	srv := httptest.NewServer(fake.handler("AB12CD"))
	defer srv.Close()

	a.settings.ServerURL = srv.URL
	s := newSession(a, "AB12CD", "host-token", true)
	a.setState("in_room", "AB12CD", "host-token", true, map[string]any{"code": "AB12CD"}, s)

	done := make(chan struct{})
	go func() { _ = s.wsLoop(); close(done) }()
	t.Cleanup(func() {
		s.stop()
		select {
		case <-done:
		case <-time.After(3 * time.Second):
		}
	})

	// 等到至少两轮 poll（wsPollInterval 级），再看信号有没有被派发
	deadline := time.Now().Add(25 * time.Second)
	sawPeerPort, sawRelayNotify := false, false
	for time.Now().Before(deadline) {
		var evTypes []string
		for _, e := range sink.events {
			if e["_event"] == "signal" {
				if t, ok := e["type"].(string); ok {
					evTypes = append(evTypes, t)
				}
			}
		}
		sawPeerPort = containsStr(evTypes, "peer_port")
		sawRelayNotify = containsStr(evTypes, "relay_notify")
		if sawPeerPort && sawRelayNotify {
			break
		}
		time.Sleep(200 * time.Millisecond)
	}

	fake.mu.Lock()
	frames := len(fake.frames)
	polls := fake.polls
	var first map[string]any
	if frames > 0 {
		first = fake.frames[0]
	}
	fake.mu.Unlock()

	if frames == 0 {
		t.Fatal("假服务端一帧都没收到：客户端 WS 根本没发请求帧")
	}
	if !sawPeerPort {
		t.Errorf("poll 响应里的 peer_port 没被派发（事件=%v）", sinkTypes(sink))
	}
	if !sawRelayNotify {
		t.Errorf("服务端 push 的 relay_notify 没被派发（事件=%v）", sinkTypes(sink))
	}
	// 帧形状必须是服务端认的样子：id/route/method/body，且 method 为 POST
	if rt, _ := first["route"].(string); rt != "/signal/poll" {
		t.Errorf("第一帧 route=%q，应为 /signal/poll（first=%v）", rt, first)
	}
	if m, _ := first["method"].(string); !strings.EqualFold(m, "POST") {
		t.Errorf("帧 method=%q，服务端 serveFrame 缺省会按 POST 处理，显式写明才不依赖默认", m)
	}
	if polls < 2 {
		t.Errorf("只 poll 了 %d 次，25s 窗口内应多轮", polls)
	}
}

func containsStr(xs []string, want string) bool {
	for _, x := range xs {
		if x == want {
			return true
		}
	}
	return false
}

func sinkTypes(sink *captureSink) []string {
	out := []string{}
	for _, e := range sink.events {
		if e["_event"] == "signal" {
			if t, ok := e["type"].(string); ok {
				out = append(out, t)
			}
		}
	}
	return out
}

// WS 一切正常、但推送与 WS 轮询都"看起来没信令"时，HTTP 安全网必须补拉到 ——
// 模组的 WS_SAFETY_NET_TICKS（RoomManager.java:1700-1710 + 1829-1841）就是为这个黑洞准备的。
func TestWSSafetyNetPollsHTTPWhileWsHealthy(t *testing.T) {
	old := wsSafetyNetEvery
	wsSafetyNetEvery = 60 * time.Millisecond
	t.Cleanup(func() { wsSafetyNetEvery = old })

	netPolls := 0
	var mu sync.Mutex
	mux := http.NewServeMux()
	mux.HandleFunc("/ws", func(w http.ResponseWriter, r *http.Request) {
		conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{InsecureSkipVerify: true})
		if err != nil {
			return
		}
		defer conn.Close(websocket.StatusNormalClosure, "")
		ctx := context.Background()
		for {
			_, data, err := conn.Read(ctx)
			if err != nil {
				return
			}
			var fr map[string]any
			if json.Unmarshal(data, &fr) != nil {
				continue
			}
			// WS 通道"活着"，但永远回空集：模拟推送漏收/半开黑洞。
			// ts 必须回调用方自己的 since（真服务端无新信令时就是这个口径，
			// signalmanager.go:194-233），否则是假服务端在推游标，测的不是产品行为。
			id, _ := fr["id"].(float64)
			since := int64(0)
			if b, okc := fr["body"].(map[string]any); okc {
				if v, okv := b["since"].(float64); okv {
					since = int64(v)
				}
			}
			out, _ := json.Marshal(map[string]any{"id": int64(id), "success": true,
				"data": map[string]any{"s": []any{}, "ts": since}})
			_ = conn.Write(ctx, websocket.MessageText, out)
		}
	})
	mux.HandleFunc("/", func(w http.ResponseWriter, req *http.Request) {
		if req.URL.Query().Get("route") != "/signal/poll" {
			_, _ = w.Write([]byte(`{"success":true,"data":{}}`))
			return
		}
		mu.Lock()
		if req.URL.Query().Get("route") != "/signal/poll" {
			_, _ = w.Write([]byte(`{"success":true,"data":{}}`))
			return
		}
		netPolls++
		n := netPolls
		mu.Unlock()
		sigs := []any{}
		if n >= 1 {
			sigs = append(sigs, map[string]any{
				"id": "net-1", "from": "host", "type": "peer_port",
				"data":      map[string]any{"peer_ip": "203.0.113.8", "peer_port": 25565},
				"timestamp": time.Now().UnixMilli(),
			})
		}
		ts := time.Now().UnixMilli()
		if len(sigs) == 0 {
			ts = time.Now().UnixMilli() - 1000
		}
		out, _ := json.Marshal(map[string]any{"success": true,
			"data": map[string]any{"s": sigs, "ts": ts}})
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write(out)
	})
	srv := httptest.NewServer(mux)
	defer srv.Close()

	a, sink := newAutoRelayTestApp(t)
	a.settings.ServerURL = srv.URL
	s := newSession(a, "CD4321", "tok", false)
	a.setState("in_room", "CD4321", "tok", false, map[string]any{"code": "CD4321"}, s)

	done := make(chan struct{})
	go func() { s.signalLoop(); close(done) }()
	t.Cleanup(func() {
		s.stop()
		select {
		case <-done:
		case <-time.After(3 * time.Second):
		}
	})

	waitFor(t, 8*time.Second, func() bool { return containsStr(sinkTypes(sink), "peer_port") },
		"WS 健康但黑洞时，HTTP 安全网没有把信令补拉回来")
	mu.Lock()
	got := netPolls
	mu.Unlock()
	if got == 0 {
		t.Fatal("安全网一次都没跑过 HTTP 轮询")
	}
}

// lobby 推送只带 {"ts":服务端此刻}。旧写法把它当信令推进 since，于是"比这帧更早写入"的
// 真信令被永久跳过——模组靠 RoomManager.java:1791-1796 的 isJsonArray 门挡住。
// 这条用例钉的就是这个顺序：先推一帧 lobby（ts=T+500），再推一帧信令（timestamp=T）。
func TestLobbyPushMustNotAdvanceSignalCursor(t *testing.T) {
	base := time.Now().UnixMilli()
	var mu sync.Mutex
	var pollSince []int64
	pushed := false
	mux := http.NewServeMux()
	mux.HandleFunc("/ws", func(w http.ResponseWriter, r *http.Request) {
		conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{InsecureSkipVerify: true})
		if err != nil {
			return
		}
		defer conn.Close(websocket.StatusNormalClosure, "")
		ctx := context.Background()
		writer := func(obj any) { out, _ := json.Marshal(obj); _ = conn.Write(ctx, websocket.MessageText, out) }
		sendPushes := func() {
			mu.Lock()
			if pushed {
				mu.Unlock()
				return
			}
			pushed = true
			mu.Unlock()
			// 1) lobby 推送：data 里没有 s 数组，只有服务端此刻的 ts
			writer(map[string]any{"id": 0, "push": "lobby", "success": true,
				"data": map[string]any{"ts": base + 500}})
			// 2) 紧接着一封真信令，timestamp 比那帧 lobby 的 ts 更早
			writer(map[string]any{"id": 0, "push": "signals", "success": true,
				"data": map[string]any{
					"s": []any{map[string]any{
						"id": "sig-1", "from": "host", "type": "peer_port",
						"data":      map[string]any{"peer_ip": "203.0.113.11", "peer_port": 25565},
						"timestamp": base,
					}},
					"ts": base,
				}})
		}
		go func() {
			for {
				_, data, err := conn.Read(ctx)
				if err != nil {
					return
				}
				var fr map[string]any
				if json.Unmarshal(data, &fr) != nil {
					continue
				}
				id, _ := fr["id"].(float64)
				if rt, _ := fr["route"].(string); rt == "/signal/poll" {
					if b, ok := fr["body"].(map[string]any); ok {
						if v, ok := b["since"].(float64); ok {
							mu.Lock()
							pollSince = append(pollSince, int64(v))
							n := len(pollSince)
							mu.Unlock()
							if n >= 2 {
								sendPushes()
							}
						}
					}
				}
				writer(map[string]any{"id": int64(id), "success": true,
					"data": map[string]any{"s": []any{}, "ts": base - 9000}})
			}
		}()
		for {
			time.Sleep(200 * time.Millisecond)
		}
	})
	mux.HandleFunc("/", func(w http.ResponseWriter, req *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"success":true,"data":{}}`))
	})
	srv := httptest.NewServer(mux)
	defer srv.Close()

	a, sink := newAutoRelayTestApp(t)
	a.settings.ServerURL = srv.URL
	s := newSession(a, "EF6789", "tok", false)
	a.setState("in_room", "EF6789", "tok", false, map[string]any{"code": "EF6789"}, s)

	done := make(chan struct{})
	go func() { _ = s.wsLoop(); close(done) }()
	t.Cleanup(func() {
		s.stop()
		select {
		case <-done:
		case <-time.After(3 * time.Second):
		}
	})

	// wsPollInterval=5s：要采到"推送之后再发出去的那一帧"，至少得等过 4 个周期
	waitFor(t, 30*time.Second, func() bool {
		mu.Lock()
		defer mu.Unlock()
		return len(pollSince) >= 4
	}, "没采到足够多的轮询帧")
	mu.Lock()
	defer mu.Unlock()
	// 真信令的最大 timestamp 就是 base；游标越过它说明 lobby 推送那帧被当成了信令，
	// 之后服务端按 ts>since 过滤，比它早的信令就再也拉不到了。
	for i, v := range pollSince {
		if v > base+1 {
			t.Fatalf("第 %d 帧 poll 的 since=%d 越过了真实信令的最大时间戳 %d（lobby 推送顶上去的，差 %dms）",
				i+1, v, base, v-base)
		}
	}
	if !containsStr(sinkTypes(sink), "peer_port") {
		t.Error("真信令本身也要派发出去（不能把 signals 帧一起挡掉）")
	}
}
