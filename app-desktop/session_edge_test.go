package main

// 会话生命周期边缘情况回归（对应本轮修的三条）：
//   1) 加入重试链在途时，第二次建房/加入必须被占位挡住，且失败后占位要交还；
//   2) ROOM_NOT_FOUND 要当致命码（房被回收后不能无限重拨、界面一直"运行中"）；
//   3) 会话自己死掉（被踢/房没了）也要跑收尾：撤销日志上传登记、交还数据面句柄与占位。
//
// 这些用例一律不真建房：占位是否交还直接问 requireIdleSession，
// 免得测试自己去触发 UPnP 组播 / STUN 出网（那是扰民行为，见项目纪律）。

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

func newEdgeTestApp(t *testing.T, handler http.HandlerFunc) *App {
	t.Helper()
	srv := httptest.NewServer(handler)
	t.Cleanup(srv.Close)
	a := NewApp()
	a.settingsPath = filepath.Join(t.TempDir(), "settings.json")
	off := false
	a.settings.AutoUPnP = &off // 双保险：任何路径都不去碰路由器
	a.AttachSink(&captureSink{}, nil)
	a.settings.ServerURL = srv.URL
	return a
}

// 1) 加入在途（服务端慢）→ 期间第二次加入必须被占位挡住；加入最终失败 → 占位必须交还。
func TestSessionSlotBlocksConcurrentStart(t *testing.T) {
	release := make(chan struct{})
	entered := make(chan struct{}, 1)
	a := newEdgeTestApp(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if r.URL.Query().Get("route") == "/room/join" {
			select {
			case entered <- struct{}{}:
			default:
			}
			<-release // 模拟一次很慢的加入
			_ = json.NewEncoder(w).Encode(map[string]any{"success": false, "error": "NETWORK"})
			return
		}
		_ = json.NewEncoder(w).Encode(map[string]any{"success": false, "error": "STOP"})
	})

	done := make(chan string, 1)
	go func() { done <- a.JoinRoom(`{"code":"AB23CD"}`) }()

	select {
	case <-entered:
	case <-time.After(3 * time.Second):
		t.Fatal("加入请求没到达假服务端")
	}

	if msg := a.JoinRoom(`{"code":"EF56GH"}`); !strings.Contains(msg, "SESSION_ACTIVE") {
		t.Errorf("加入在途时第二次加入应被占位挡住，实际：%s", msg)
	}
	if msg := a.CreateRoom(`{"name":"t","hostPort":45999}`); !strings.Contains(msg, "SESSION_ACTIVE") {
		t.Errorf("加入在途时建房应被占位挡住，实际：%s", msg)
	}

	close(release)
	<-done // 等第一次加入走完失败分支（重试链会因 release 已关而快速跑完）

	if msg := requireIdleSession(a); msg != "" {
		t.Errorf("加入失败后占位没交还，App 永远处于忙：%s", msg)
	}
	a.releaseSessionSlot()
}

// 2) 房被回收：服务端回 ROOM_NOT_FOUND，本端必须当致命错误（模组 RoomManager:1508 同判据）。
func TestRoomNotFoundIsFatal(t *testing.T) {
	for _, code := range []string{"ROOM_NOT_FOUND", "ROOM_EXPIRED", "ROOM_CLOSED", "ROOM_EVICTED", "INVALID_TOKEN"} {
		if !isFatalAuthError(code) {
			t.Errorf("%s 应判致命（否则房没了还在无限重拨，玩家拿不到失败提示）", code)
		}
	}
	for _, code := range []string{"NETWORK", "RATE_LIMITED", ""} {
		if isFatalAuthError(code) {
			t.Errorf("%q 不该判致命（瞬态错误要走退避重试）", code)
		}
	}
}

// 3) 会话自然结束（被踢/房消失）也要收尾：撤日志上传登记 + 交还数据面句柄 + 交还占位。
func TestClearSessionRunsTeardownHooks(t *testing.T) {
	a := newEdgeTestApp(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"success": false, "error": "STOP"})
	})
	resetLogUploadState()
	t.Cleanup(resetLogUploadState)

	room := map[string]any{"code": "AB23CD", "hostPort": 45999}
	a.startSession("AB23CD", "tok", true, room, "client-1")
	a.logUploadArm("AB23CD", true)
	setActiveAppForTransport(a)
	if activeAppForTransport.Load() == nil {
		t.Fatal("前置条件：数据面应已拿到当前会话 App")
	}

	a.mu.Lock()
	s := a.session
	a.mu.Unlock()
	if s == nil {
		t.Fatal("startSession 没建会话")
	}
	s.stop()
	a.clearSession(s)

	a.mu.Lock()
	state := a.state
	a.mu.Unlock()
	if state != "idle" {
		t.Errorf("state = %s, want idle", state)
	}
	if activeAppForTransport.Load() != nil {
		t.Error("会话结束后数据面仍指向旧 App（迟到包会往已死会话里灌事件）")
	}
	logUploadMu.Lock()
	armed := logActiveCodePtr != nil
	logUploadMu.Unlock()
	if armed {
		t.Error("会话自己死掉后日志上传没 disarm，90s 后仍会上传这一场样本（logActiveCodePtr 应为 nil）")
	}
	if msg := requireIdleSession(a); msg != "" {
		t.Errorf("clearSession 后占位没交还：%s", msg)
	}
	a.releaseSessionSlot()
}

// 4) Shutdown（关窗口 / Ctrl+C）必须通知服务端退房。
// 只停本地不发 /room/leave 的话，服务端要等心跳超时（room_ttl=300s）才回收，
// 这期间房间仍能被别的玩家凭房号加进来，进去就是"连接失败"——幽灵房。
func TestShutdownNotifiesServerOfLeave(t *testing.T) {
	var mu sync.Mutex
	var routes []string
	record := func(route string) {
		mu.Lock()
		routes = append(routes, route)
		mu.Unlock()
	}
	has := func(route string) bool {
		mu.Lock()
		defer mu.Unlock()
		for _, r := range routes {
			if r == route {
				return true
			}
		}
		return false
	}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		route := r.URL.Query().Get("route")
		record(route)
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"success": true, "data": map[string]any{}})
	}))
	defer srv.Close()

	a := NewApp()
	a.settingsPath = filepath.Join(t.TempDir(), "settings.json")
	off := false
	a.settings.AutoUPnP = &off
	a.AttachSink(&captureSink{}, nil)
	a.settings.ServerURL = srv.URL

	a.startSession("AB23CD", "hosttok", true, map[string]any{"code": "AB23CD", "hostPort": 45999}, "client-1")
	a.Shutdown()

	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) && !has("/room/leave") {
		time.Sleep(50 * time.Millisecond)
	}
	if !has("/room/leave") {
		mu.Lock()
		got := append([]string{}, routes...)
		mu.Unlock()
		t.Errorf("Shutdown 没通知服务端退房（幽灵房会活到心跳超时）；期间请求过的路由=%v", got)
	}
}
