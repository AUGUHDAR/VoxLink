package main

import (
	"encoding/json"
	"net/url"
	"path/filepath"
	"strings"
	"testing"
)

// ---- URL 拼接 ----

func TestBuildRequestURL(t *testing.T) {
	got, err := buildRequestURL("https://link.voxdev.top", "/room/list", url.Values{
		"page": {"1"}, "size": {"20"}, "clientType": {"mod"},
	})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if !strings.HasPrefix(got, "https://link.voxdev.top/?") {
		t.Fatalf("bad prefix: %s", got)
	}
	for _, want := range []string{"route=%2Froom%2Flist", "page=1", "size=20", "clientType=mod"} {
		if !strings.Contains(got, want) {
			t.Fatalf("URL %s missing %s", got, want)
		}
	}
}

func TestBuildRequestURLRejectsBadScheme(t *testing.T) {
	if _, err := buildRequestURL("ftp://example.com", "/room/list", nil); err == nil {
		t.Fatal("expected error for ftp scheme")
	}
	if _, err := buildRequestURL("file:///etc/passwd", "/room/list", nil); err == nil {
		t.Fatal("expected error for file scheme")
	}
}

func TestWSURLFrom(t *testing.T) {
	cases := map[string]string{
		"https://link.voxdev.top":    "wss://link.voxdev.top/ws",
		"https://link.voxdev.top/":   "wss://link.voxdev.top/ws",
		"http://127.0.0.1:8080":    "ws://127.0.0.1:8080/ws",
		"http://127.0.0.1:8080/xx": "ws://127.0.0.1:8080/xx/ws",
	}
	for in, want := range cases {
		if got := wsURLFrom(in); got != want {
			t.Errorf("wsURLFrom(%q) = %q, want %q", in, got, want)
		}
	}
}

// ---- 信封解析 ----

func TestEnvelopeSuccessParse(t *testing.T) {
	var env envelope
	raw := []byte(`{"success":true,"data":{"code":"ABC123","expiresIn":3600}}`)
	if err := json.Unmarshal(raw, &env); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if !env.Success || len(env.Data) == 0 {
		t.Fatalf("bad envelope: %+v", env)
	}
	var data struct {
		Code      string `json:"code"`
		ExpiresIn int    `json:"expiresIn"`
	}
	if err := json.Unmarshal(env.Data, &data); err != nil {
		t.Fatalf("data unmarshal: %v", err)
	}
	if data.Code != "ABC123" || data.ExpiresIn != 3600 {
		t.Fatalf("bad data: %+v", data)
	}
}

func TestEnvelopeErrorParse(t *testing.T) {
	var env envelope
	raw := []byte(`{"success":false,"error":"ROOM_EXPIRED","message":"房间已过期"}`)
	if err := json.Unmarshal(raw, &env); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if env.Success || env.Error != "ROOM_EXPIRED" || env.Message != "房间已过期" {
		t.Fatalf("bad envelope: %+v", env)
	}
}

func TestWrapFormats(t *testing.T) {
	ok := wrapOK(map[string]any{"enabled": true})
	var m map[string]any
	if err := json.Unmarshal([]byte(ok), &m); err != nil {
		t.Fatalf("wrapOK not json: %v", err)
	}
	if m["ok"] != true {
		t.Fatalf("wrapOK missing ok=true: %s", ok)
	}

	e := wrapErr("NETWORK", "超时")
	var em map[string]any
	if err := json.Unmarshal([]byte(e), &em); err != nil {
		t.Fatalf("wrapErr not json: %v", err)
	}
	if em["ok"] != false || em["error"] != "NETWORK" || em["message"] != "超时" {
		t.Fatalf("bad wrapErr: %s", e)
	}
}

func TestWrapCallErrPassthrough(t *testing.T) {
	ae := &APIError{Code: "ROOM_FULL", Message: "房间已满", Status: 400}
	out := wrapCallErr(ae)
	if !strings.Contains(out, "ROOM_FULL") || !strings.Contains(out, "房间已满") {
		t.Fatalf("server error not passed through: %s", out)
	}
	out = wrapCallErr(errNetworkStub{})
	if !strings.Contains(out, `"NETWORK"`) {
		t.Fatalf("plain error should map to NETWORK: %s", out)
	}
}

type errNetworkStub struct{}

func (errNetworkStub) Error() string { return "dial timeout" }

// ---- 设置读写 ----

func TestSettingsRoundTrip(t *testing.T) {
	a := NewApp()
	a.settingsPath = filepath.Join(t.TempDir(), "settings.json")

	got := a.GetSettings()
	if !strings.Contains(got, `"serverUrl":"https://link.voxdev.top"`) || !strings.Contains(got, `"theme":"light"`) {
		t.Fatalf("defaults wrong: %s", got)
	}
	if !strings.Contains(got, `"version":"`+appVersion+`"`) {
		t.Fatalf("version wrong: %s", got)
	}

	// 契约：SaveSettings 忽略参数里的 serverUrl，只应用 theme；返回 serverUrl 恒为默认
	resp := a.SaveSettings(`{"serverUrl":"https://example.com/","theme":"dark"}`)
	if !strings.Contains(resp, `"ok":true`) || !strings.Contains(resp, `"serverUrl":"https://link.voxdev.top"`) || !strings.Contains(resp, `"theme":"dark"`) {
		t.Fatalf("save response wrong: %s", resp)
	}

	// 重新加载，验证落盘（theme 落盘，serverUrl 恒为默认）
	b := NewApp()
	b.settingsPath = a.settingsPath
	b.loadSettings()
	got2 := b.GetSettings()
	if !strings.Contains(got2, `"serverUrl":"https://link.voxdev.top"`) || !strings.Contains(got2, `"theme":"dark"`) {
		t.Fatalf("persisted settings wrong: %s", got2)
	}
}

func TestSaveSettingsIgnoresServerURL(t *testing.T) {
	a := NewApp()
	a.settingsPath = filepath.Join(t.TempDir(), "settings.json")
	// 契约：serverUrl 收到也忽略（即使非法值也不报错、不应用）
	resp := a.SaveSettings(`{"serverUrl":"javascript:alert(1)","theme":"dark"}`)
	if !strings.Contains(resp, `"ok":true`) {
		t.Fatalf("serverUrl should be ignored, got: %s", resp)
	}
	if !strings.Contains(resp, `"serverUrl":"https://link.voxdev.top"`) {
		t.Fatalf("serverUrl must stay default: %s", resp)
	}
}

func TestValidateRoomCode(t *testing.T) {
	if !validateRoomCode("ABC234") {
		t.Error("ABC234 should be valid")
	}
	for _, bad := range []string{"ABC23", "ABC2I1", "ABC201", "abcd23", "ABC2345"} {
		if validateRoomCode(bad) {
			t.Errorf("%q should be invalid", bad)
		}
	}
}

// ---- 本地搜索过滤 ----

func TestFilterRooms(t *testing.T) {
	rooms := []map[string]any{
		{"name": "生存小队"},
		{"name": "Redstone World"},
		{"name": "redstone 测试"},
	}
	got := filterRooms(rooms, "REDSTONE")
	if len(got) != 2 {
		t.Fatalf("expected 2 matches, got %d", len(got))
	}
	if got := filterRooms(rooms, "不存在"); len(got) != 0 {
		t.Fatalf("expected 0 matches, got %d", len(got))
	}
}

// ---- 会话状态 ----

func TestSessionStateIdle(t *testing.T) {
	a := NewApp()
	got := a.GetSessionState()
	if !strings.Contains(got, `"state":"idle"`) || !strings.Contains(got, `"room":null`) {
		t.Fatalf("idle state wrong: %s", got)
	}
}

func TestLeaveRoomWhenIdle(t *testing.T) {
	a := NewApp()
	resp := a.LeaveRoom()
	if !strings.Contains(resp, `"left":false`) {
		t.Fatalf("leave when idle wrong: %s", resp)
	}
}

// ---- 真实网络（-short 跳过）----

func TestListRoomsLive(t *testing.T) {
	if testing.Short() {
		t.Skip("short 模式跳过真实网络请求")
	}
	a := NewApp()
	a.settingsPath = filepath.Join(t.TempDir(), "settings.json")
	resp := a.ListRooms(`{"page":1,"size":5}`)
	var m struct {
		OK   bool `json:"ok"`
		Data struct {
			Total int `json:"total"`
		} `json:"data"`
		Error   string `json:"error"`
		Message string `json:"message"`
	}
	if err := json.Unmarshal([]byte(resp), &m); err != nil {
		t.Fatalf("response not json: %s", resp)
	}
	if !m.OK {
		t.Skipf("生产服务器不可用或请求失败: %s %s", m.Error, m.Message)
	}
	t.Logf("生产服务器连接成功，房间总数=%d", m.Data.Total)
}
