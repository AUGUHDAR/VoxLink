package main

// 本机 /info 服务的接线用例。参照物：network/PeerServer.java
//   :54-107 起服务（回环 + 随机端口）、:63-97 /info 与令牌校验、:109-121 停服、:123-125 getPort
//   RoomManager.java:162 取端口 → SignalingClient.java:185-186 随 create_room 上报
// 此前这块代码写好了却没有任何生产调用点，等于模组有、应用端没有。

import (
	"encoding/json"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync"
	"testing"
)

// 建房前有"端口必须有人在听"的守卫（portprobe.go），测试得自己把假 MC 挂上。
func listenFakeMC(t *testing.T) int {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = ln.Close() })
	return ln.Addr().(*net.TCPAddr).Port
}

func peerTestApp(t *testing.T) *App {
	t.Helper()
	a := NewApp()
	a.settingsPath = filepath.Join(t.TempDir(), "settings.json")
	t.Cleanup(stopPeerServer)
	return a
}

// 起服务：端口大于 0、重复调用幂等返回同一端口、停服后复位 -1。
func TestPeerServerStartIsIdempotentAndStops(t *testing.T) {
	a := peerTestApp(t)
	port := startPeerServer(a)
	if port <= 0 {
		t.Fatalf("没起到本机回环端口，得到 %d", port)
	}
	if again := startPeerServer(a); again != port {
		t.Fatalf("重复启动必须复用既有端口（:54-57），得到 %d 而不是 %d", again, port)
	}
	if peerServerPort() != port {
		t.Fatalf("getPort 必须返回当前端口（:123-125），得到 %d", peerServerPort())
	}
	stopPeerServer()
	if peerServerPort() != -1 {
		t.Fatalf("停服后端口要复位 -1（:109-121），得到 %d", peerServerPort())
	}
}

// 令牌校验：不带、带错都 403 且错误体逐字节一致；带对才返回房间信息，键序同 addProperty。
func TestPeerInfoTokenGateAndFieldOrder(t *testing.T) {
	a := peerTestApp(t)
	a.mu.Lock()
	a.state = "hosting"
	a.code = "QW4RTY"
	a.token = "tok-abc"
	a.isHost = true
	a.room = map[string]any{
		"code": "QW4RTY", "name": "测试=房间", "currentPlayers": 1, "maxPlayers": 20,
		"hasPassword": false, "category": "survival", "natType": "unknown",
		"visible": true, "hostPort": 25565, "bedrockPort": 0, "protocolVersion": 7,
		"gameVersion": "1.20.1", "serverProtocolVersion": 765, "peerPort": 0,
	}
	a.mu.Unlock()

	// 生产路径里 provider 是在建房/加入时挂上的（app.go CreateRoom / session_wiring.go），
	// 这里同序挂上再起服务。
	setPeerCacheProvider(a.peerCacheFromApp)
	port := startPeerServer(a)
	if port <= 0 {
		t.Fatalf("服务没起来: %d", port)
	}
	base := "http://127.0.0.1:" + itoa(port) + peerInfoPath

	for name, url := range map[string]string{
		"没带 token": base,
		"带错 token": base + "?token=nope",
	} {
		resp, err := http.Get(url)
		if err != nil {
			t.Fatalf("%s: %v", name, err)
		}
		body, _ := io.ReadAll(resp.Body)
		resp.Body.Close()
		if resp.StatusCode != http.StatusForbidden || string(body) != peerUnauthorized {
			t.Fatalf("%s 必须 403 且错误体逐字节为 %s，实际 %d %q", name, peerUnauthorized, resp.StatusCode, body)
		}
	}

	resp, err := http.Get(base + "?token=tok-abc")
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("令牌对了却返回 %d: %s", resp.StatusCode, body)
	}
	got := string(body)
	if !strings.HasPrefix(got, `{"hasRoom":true,"code":"QW4RTY","name":`) {
		t.Fatalf("字段顺序必须与模组 addProperty 一致（:165-195），得到 %s", got[:min(len(got), 120)])
	}
	// Gson 默认 HTML-safe 转义：房间名里的 = 要写成转义形态
	if !strings.Contains(got, `测试\u003d房间`) {
		t.Fatalf("Gson 的 HTML 转义要保持（房间名含 = 时），得到 %s", got)
	}
	var decoded map[string]any
	if err := json.Unmarshal(body, &decoded); err != nil {
		t.Fatalf("响应不是合法 JSON: %v", err)
	}
	if decoded["currentPlayers"] != float64(1) || decoded["maxPlayers"] != float64(20) ||
		decoded["gameVersion"] != "1.20.1" || decoded["voxlinkVersion"] != appVersion {
		t.Fatalf("信息体取值不对: %s", got)
	}
	// 模组的 protocolVersion 取的是 room.getServerProtocolVersion()（PS:185），不是本端协议号
	if decoded["protocolVersion"] != float64(765) {
		t.Fatalf("protocolVersion 必须是房间记录里的服务端协议号（PS:185），得到 %s", got)
	}
	if decoded["serverProtocolVersion"] != float64(0) || decoded["geyserLoaded"] != false {
		t.Fatalf("本端没有对应物的两项必须留模组的初值（0/false），得到 %s", got)
	}
}

// 建房要把这个端口报上去（RoomManager.java:162 → SignalingClient.java:185-186）。
func TestCreateRoomReportsPeerPort(t *testing.T) {
	var mu sync.Mutex
	var body map[string]any
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var b map[string]any
		_ = json.NewDecoder(r.Body).Decode(&b)
		mu.Lock()
		body = b
		mu.Unlock()
		sendJSON(w, map[string]any{"success": true, "data": map[string]any{
			"code": "ZZ11XX", "hostToken": "t", "name": "n", "hostIp": "1.2.3.4", "hostPort": 25565,
		}})
	}))
	defer srv.Close()

	a := peerTestApp(t)
	a.mu.Lock()
	a.settings.ServerURL = srv.URL
	a.mu.Unlock()
	mcPort := listenFakeMC(t)
	a.CreateRoom(`{"name":"peer-port","hostPort":` + itoa(mcPort) + `}`)

	mu.Lock()
	got := body
	mu.Unlock()
	if got == nil {
		t.Fatal("建房请求没落到桩上")
	}
	pp, _ := got["peerPort"].(float64)
	if int(pp) != peerServerPort() || pp <= 0 {
		t.Fatalf("create_room 必须带上本机 /info 端口（应为 %d），实际 %v", peerServerPort(), got["peerPort"])
	}
}
