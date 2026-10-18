package main

// caps_test.go — 能力表装配与 legacy 判定的自检（对齐模组 ProtocolNegotiator.java / RoomInfo.java）。

import (
	"reflect"
	"testing"
)

// TestCurrentCapabilitiesDeclared 本端最终声明的能力集合：
// relay / ice_restart / continuous_retry / punchAuthV1 / overlayAuthV1 / stdTurnV1 / relayMuxV1；
// overlayAuthV1 与模组一致无条件声明（ProtocolNegotiator.java:45），验签路径已在收包处生效
// （overlay_auth_test.go）；modSyncV1 走配置门控另行追加（SignalingClient.java:202-211），
// 所以不得出现在这份静态表里。
func TestCurrentCapabilitiesDeclared(t *testing.T) {
	want := []string{
		capNameRelay,
		capNameIceRestart,
		capNameContinuousRetry,
		capNamePunchAuthV1,
		capNameOverlayAuthV1,
		capNameStdTurnV1,
		capNameRelayMuxV1,
	}
	got := currentCapabilities()
	if !reflect.DeepEqual(got, want) {
		t.Errorf("currentCapabilities() = %v, want %v", got, want)
	}
	if !reflect.DeepEqual(appCapabilities, want) {
		t.Errorf("appCapabilities = %v, want %v", appCapabilities, want)
	}
	for _, banned := range []string{capNameModSyncV1} {
		for _, c := range got {
			if c == banned {
				t.Errorf("配置门控能力 %s 不得写进静态表", c)
			}
		}
	}
}

// TestCapabilityNamesByteExact 能力名与模组 ProtocolNegotiator.java:12-37 逐字节一致。
func TestCapabilityNamesByteExact(t *testing.T) {
	pairs := map[string]string{
		capNameRelay:           "relay",
		capNameIceRestart:      "ice_restart",
		capNameContinuousRetry: "continuous_retry",
		capNamePunchAuthV1:     "punchAuthV1",
		capNameOverlayAuthV1:   "overlayAuthV1",
		capNameModSyncV1:       "modSyncV1",
		capNameStdTurnV1:       "stdTurnV1",
		capNameRelayMuxV1:      "relayMuxV1",
	}
	for got, want := range pairs {
		if got != want {
			t.Errorf("能力名不符: got %q want %q", got, want)
		}
	}
}

// TestHostIsLegacy 房主 legacy 判定（RoomInfo.java:243-245）：
// hostProtocolVersion==0 或能力表空 → legacy；HL 导入房形态（0/空）同判 legacy。
func TestHostIsLegacy(t *testing.T) {
	cases := []struct {
		name string
		room map[string]any
		want bool
	}{
		{"nil room", nil, true},
		{"缺 protocol 字段（JSON 无该键=0）", map[string]any{"hostCapabilities": []any{"relay"}}, true},
		{"proto 0", map[string]any{"hostProtocolVersion": float64(0), "hostCapabilities": []any{"relay"}}, true},
		{"proto 7 空 caps", map[string]any{"hostProtocolVersion": float64(7), "hostCapabilities": []any{}}, true},
		{"proto 7 有 caps", map[string]any{"hostProtocolVersion": float64(7), "hostCapabilities": []any{"relay"}}, false},
		{"int 型 proto（非 JSON 路径）", map[string]any{"hostProtocolVersion": 7, "hostCapabilities": []any{"stdTurnV1"}}, false},
	}
	for _, c := range cases {
		if got := hostIsLegacy(c.room); got != c.want {
			t.Errorf("%s: hostIsLegacy = %v, want %v", c.name, got, c.want)
		}
	}
}

// TestHostSupportsCapLegacyGate 能力查询叠加 legacy 门（ProtocolNegotiator.java:65-92）：
// 旧版房主即便数据里混入 cap 也不采信。
func TestHostSupportsCapLegacyGate(t *testing.T) {
	legacyWithCap := map[string]any{
		"hostProtocolVersion": float64(0),
		"hostCapabilities":    []any{"stdTurnV1", "relay"},
	}
	if hostSupportsCap(legacyWithCap, "stdTurnV1") {
		t.Error("legacy 房主（proto 0）能力必须不采信")
	}
	modern := map[string]any{
		"hostProtocolVersion": float64(7),
		"hostCapabilities":    []any{"stdTurnV1", "relayMuxV1"},
	}
	if !hostSupportsCap(modern, "stdTurnV1") {
		t.Error("新版房主 stdTurnV1 判定应为 true")
	}
	if !hostSupportsCap(modern, "relayMuxV1") {
		t.Error("新版房主 relayMuxV1 判定应为 true")
	}
	if hostSupportsCap(modern, "overlayAuthV1") {
		t.Error("未声明能力判定应为 false")
	}
	if !hostSupportsStdTurn(modern) {
		t.Error("hostSupportsStdTurn(modern) 应为 true")
	}
	if hostSupportsStdTurn(nil) {
		t.Error("hostSupportsStdTurn(nil) 应为 false")
	}
}

// TestPeerDataIsLegacy join_request 视角的加入者 legacy 判定（ProtocolNegotiator.java:43-45）：
// 服务端仅为新客户端附带 clientProtocolVersion/clientCapabilities（roommanager.go:865-869），
// 缺字段即 proto 0/空 caps → legacy。
func TestPeerDataIsLegacy(t *testing.T) {
	cases := []struct {
		name string
		data map[string]any
		want bool
	}{
		{"nil", nil, true},
		{"老客户端：两字段皆缺", map[string]any{"clientId": "abc"}, true},
		{"proto 0", map[string]any{"clientProtocolVersion": float64(0), "clientCapabilities": []any{"relay"}}, true},
		{"proto 7 空 caps", map[string]any{"clientProtocolVersion": float64(7), "clientCapabilities": []any{}}, true},
		{"proto 7 无 caps 字段", map[string]any{"clientProtocolVersion": float64(7)}, true},
		{"proto 7 有 caps", map[string]any{"clientProtocolVersion": float64(7), "clientCapabilities": []any{"punchAuthV1"}}, false},
	}
	for _, c := range cases {
		if got := peerDataIsLegacy(c.data); got != c.want {
			t.Errorf("%s: peerDataIsLegacy = %v, want %v", c.name, got, c.want)
		}
	}
}

// TestPeerCapsLegacy 已登记形态（hostPeer.caps + protocolVersion）的同规则判定。
func TestPeerCapsLegacy(t *testing.T) {
	if !peerCapsLegacy(nil, 0) {
		t.Error("nil caps + proto 0 应为 legacy")
	}
	if !peerCapsLegacy([]string{"relay"}, 0) {
		t.Error("proto 0 应为 legacy")
	}
	if !peerCapsLegacy([]string{}, 7) {
		t.Error("空 caps 应为 legacy")
	}
	if peerCapsLegacy([]string{"relayMuxV1"}, 7) {
		t.Error("proto 7 + 非空 caps 不应为 legacy")
	}
}

// TestPeerCapsContain 信令 data 能力数组查询保持纯 contains 语义（不加 legacy 门，
// 对齐 ProtocolNegotiator.supportsXxx(PeerInfo) :47-87）。
func TestPeerCapsContain(t *testing.T) {
	data := map[string]any{"clientCapabilities": []any{"relay", "relayMuxV1"}}
	if !peerCapsContain(data, "relayMuxV1") {
		t.Error("应命中 relayMuxV1")
	}
	if peerCapsContain(data, "modSyncV1") {
		t.Error("不应命中未声明的 modSyncV1")
	}
	if peerCapsContain(map[string]any{}, "relay") {
		t.Error("无字段应为 false")
	}
}
