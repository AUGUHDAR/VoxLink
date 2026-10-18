package main

// 端口可达性探测的用例。参照物：SignalingClient.java:397-403（请求）、
// RoomManager.java:467-524（建房后的状态迁移）、RoomInfo.java:678-682（枚举）、
// ConnectionManager.java:3941-3943（消费：portUnreachable 决定房主阵列规模）。

import (
	"encoding/json"
	"net/http"
	"strings"
	"testing"
	"time"
)

func waitPortStatus(t *testing.T, got func() portStatus, want portStatus) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		if got() == want {
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatalf("端口状态没落到 %s，实际 %s", want.key(), got().key())
}

// 阵列的门槛语义：UNREACHABLE 与 UNKNOWN 都算"端口打不通"（CM:3943），REACHABLE 不算。
func TestPortStatusUnreachableGate(t *testing.T) {
	for _, s := range []portStatus{portStatusUnknown, portStatusUnreachable} {
		if !s.unreachableOrUnknown() {
			t.Fatalf("%s 必须算端口不通（CM:3943）", s.key())
		}
	}
	for _, s := range []portStatus{portStatusReachable, portStatusNoAddress} {
		if s.unreachableOrUnknown() {
			t.Fatalf("%s 不该算端口不通（CM:3943 只有 UNREACHABLE/UNKNOWN）", s.key())
		}
	}
}

// 请求形态与判据：route=/stun/check、body 带 ip/port/action，
// 只有 data.reachable=true 才算可达（RoomManager.java:487-490）。
func TestCheckPortReachableRequestAndVerdict(t *testing.T) {
	type captured struct {
		route string
		body  map[string]any
	}
	var got captured
	a := newRelayTestApp(t, func(w http.ResponseWriter, r *http.Request) {
		got.route = r.URL.Query().Get("route")
		_ = json.NewDecoder(r.Body).Decode(&got.body)
		sendJSON(w, map[string]any{"success": true, "data": map[string]any{"reachable": true}})
	})
	if !a.checkPortReachable("45.67.89.40", 25565) {
		t.Fatal("服务端说可达就得判可达")
	}
	if got.route != "/stun/check" {
		t.Fatalf("要打到 /stun/check（server-go api.go:301 的 check_port 路由），实际 %q", got.route)
	}
	if got.body["ip"] != "45.67.89.40" || got.body["action"] != "check_port" {
		t.Fatalf("请求体要带 ip 与 action=check_port（SignalingClient.java:399-401），得到 %+v", got.body)
	}
	if p, _ := got.body["port"].(float64); p != 25565 {
		t.Fatalf("端口没带上，得到 %+v", got.body)
	}

	// 缺 reachable 字段 = 模组判据里的 false（:488-490 has("reachable") 不成立）
	a2 := newRelayTestApp(t, func(w http.ResponseWriter, r *http.Request) {
		sendJSON(w, map[string]any{"success": true, "data": map[string]any{}})
	})
	if a2.checkPortReachable("1.1.1.1", 2) {
		t.Fatal("data 里没有 reachable 时必须判不可达")
	}
	if a2.checkPortReachable("", 2) || a2.checkPortReachable("1.1.1.1", 0) {
		t.Fatal("没有地址或端口时不该发探测")
	}
}

// 建房后的状态迁移：有 ipv4 走异步探测、没有则落 NO_ADDRESS（:481/:500/:504/:523）。
func TestProbeCreatedRoomPortsStateTransition(t *testing.T) {
	defer resetPortStatusOnLeave()
	var hit []string
	a := newRelayTestApp(t, func(w http.ResponseWriter, r *http.Request) {
		var body map[string]any
		_ = json.NewDecoder(r.Body).Decode(&body)
		ip, _ := body["ip"].(string)
		hit = append(hit, ip)
		sendJSON(w, map[string]any{"success": true, "data": map[string]any{"reachable": !strings.Contains(ip, ":")}})
	})
	a.probeCreatedRoomPorts("45.67.89.40", "2001:db8::1", 25565)
	waitPortStatus(t, a.ipv4PortStatus, portStatusReachable)
	waitPortStatus(t, a.ipv6PortStatus, portStatusUnreachable)

	// 无地址的一侧直接 NO_ADDRESS，不发探测
	b := newRelayTestApp(t, func(w http.ResponseWriter, r *http.Request) {
		sendJSON(w, map[string]any{"success": true, "data": map[string]any{"reachable": true}})
	})
	b.probeCreatedRoomPorts("", "", 25565)
	if b.ipv4PortStatus() != portStatusNoAddress || b.ipv6PortStatus() != portStatusNoAddress {
		t.Fatalf("没有公网地址要落 NO_ADDRESS（:500/:523），得到 %s / %s",
			b.ipv4PortStatus().key(), b.ipv6PortStatus().key())
	}
	// 退房清状态：不清会让下一间房沿用上一间的结论
	resetPortStatusOnLeave()
	if a.ipv4PortStatus() != portStatusUnknown {
		t.Fatalf("退房后要回到 UNKNOWN，得到 %s", a.ipv4PortStatus().key())
	}
}

// 阵列规模要读这个结论：端口不可达时从 3 升到 20（CM:3975-3977）。
func TestPortStatusFeedsHostArrayCount(t *testing.T) {
	defer resetPortStatusOnLeave()
	p := arrayTestProfile(32, 0)
	app := NewApp()
	app.probeCreatedRoomPorts("", "", 25565) // 无地址 → NO_ADDRESS（不算"不可达"）
	if got := hostMultiSocketGroupCount(p, false, false, app.ipv4PortStatus().unreachableOrUnknown(), false); got != p.hostMultiBaseSocketCount {
		t.Fatalf("NO_ADDRESS 时按基线 3 台，得到 %d", got)
	}
	appIpv4PortStatus.Store(int32(portStatusUnreachable))
	if got := hostMultiSocketGroupCount(p, false, false, app.ipv4PortStatus().unreachableOrUnknown(), false); got != p.hostMultiSocketCount {
		t.Fatalf("端口不可达要升到 hostMultiSocketCount=%d，得到 %d", p.hostMultiSocketCount, got)
	}
}
