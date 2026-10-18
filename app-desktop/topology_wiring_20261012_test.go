package main

// 2026-10-12：拓扑/多人网状整块此前是"已移植、零生产调用点"——installTopologyClient 只有测试在调，
// getTopologyClient() 在真实进程里恒 nil，于是 Layer2 预热、/topology/poll、overlay 启动、
// 心跳的 natType/load/peerLatency/overlayPort 四个键全部静默失效（nil-safe 早退，所以连报错都没有）。
// 下面三条用例把"生产路径真的接线"钉住：摘掉安装点、摘掉心跳任一键、摘掉信令路由，用例必须变红。

import (
	"io"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"sync"
	"testing"
)

func newWiredApp(t *testing.T) (*App, *topologyClient) {
	t.Helper()
	var mu sync.Mutex
	polls := 0
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Query().Get("route") == "/topology/poll" {
			mu.Lock()
			polls++
			mu.Unlock()
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"success":true,"data":{"signals":[],"ts":1}}`)
	}))
	t.Cleanup(srv.Close)

	prev := appTopologyClient.Swap(nil)
	t.Cleanup(func() { appTopologyClient.Store(prev) })

	a := NewApp()
	a.settingsPath = filepath.Join(t.TempDir(), "settings.json")
	a.settings.ServerURL = srv.URL
	a.AttachSink(&captureSink{}, nil)
	a.Init()
	a.settings.ServerURL = srv.URL
	return a, getTopologyClient()
}

// TestNewAppInstallsTopologyClient：装不上客户端，预热就永远不发请求。
func TestNewAppInstallsTopologyClient(t *testing.T) {
	a, tcl := newWiredApp(t)
	if tcl == nil {
		t.Fatal("NewApp 没有安装拓扑客户端（真实进程里预热/轮询/overlay/心跳四键都会静默早退）")
	}
	topologyPrefetched.Store(false)
	t.Cleanup(func() { topologyPrefetched.Store(false) })

	topologyPrefetchRelay()
	if !topologyPrefetched.Load() {
		t.Fatal("topologyPrefetchRelay 被 nil 客户端挡在闸门外：说明生产路径没接线")
	}
	// 客户端一旦装上，轮询走的是同一条 /topology/poll
	tcl.onRoomJoined("AB12CD", "tok", true, "", 0)
	t.Cleanup(topologyOnRoomLeft)
	if a == nil {
		t.Fatal("app 为空")
	}
}

// TestHeartbeatBodyCarriesTopologyFields：服务端 roommanager.go:1181-1186 只认这四个键。
func TestHeartbeatBodyCarriesTopologyFields(t *testing.T) {
	a, _ := newWiredApp(t)
	a.mu.Lock()
	a.code = "AB12CD"
	a.token = "tok"
	a.isHost = true
	a.room = map[string]any{"natType": "port_restricted_cone"}
	a.mu.Unlock()

	s := newSession(a, "AB12CD", "tok", true)
	body := s.heartbeatBody(7)

	for _, k := range []string{"natType", "load", "peerLatency", "overlayPort"} {
		if _, ok := body[k]; !ok {
			t.Fatalf("心跳体缺少键 %s（服务端按这四个键写拓扑节点，缺了本端就永远是不可用节点）", k)
		}
	}
	if body["natType"] != "port_restricted_cone" {
		t.Fatalf("心跳 natType 应为房间实测值，得到 %v", body["natType"])
	}
	if l, ok := body["load"].(float64); !ok || l != 0.1 {
		t.Fatalf("心跳 load 应与模组 heartbeatTask 一致为 0.1，得到 %#v", body["load"])
	}
	if _, ok := body["peerLatency"].(map[string]int); !ok {
		t.Fatalf("peerLatency 应为 map[string]int，得到 %#v", body["peerLatency"])
	}
	if _, ok := body["overlayPort"].(int); !ok {
		t.Fatalf("overlayPort 应为 int，得到 %#v", body["overlayPort"])
	}
	for _, k := range []string{"code", "token", "isHost", "seq", "currentInterval", "mcPlayerCount"} {
		if _, ok := body[k]; !ok {
			t.Fatalf("心跳体丢了原有键 %s（心跳是房间存活的唯一凭据）", k)
		}
	}
}

// TestTopologySignalRoutedFromSignalDispatch：信令分发处必须把 topology_* 交给拓扑客户端
// （模组 RoomManager 在同一个分发点做这件事）。
func TestTopologySignalRoutedFromSignalDispatch(t *testing.T) {
	a, tcl := newWiredApp(t)
	if tcl == nil {
		t.Fatal("拓扑客户端未安装")
	}
	a.mu.Lock()
	a.code = "AB12CD"
	a.token = "tok"
	a.isHost = true
	a.room = map[string]any{}
	a.mu.Unlock()

	tcl.setOptimizing(true)
	if !tcl.isOptimizing() {
		t.Fatal("前置条件不成立：优化标志没置上")
	}
	s := newSession(a, "AB12CD", "tok", true)
	s.handleSignals(signalsData{
		S:  []signalMsg{{Type: "topology_optimization_done", From: "server", Timestamp: 2, Data: map[string]any{}}},
		TS: 2,
	})
	if tcl.isOptimizing() {
		t.Fatal("topology_optimization_done 没有进入拓扑客户端：分发处的路由被摘掉了")
	}
}
