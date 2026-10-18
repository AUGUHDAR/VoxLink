package main

// fed_state_test.go — 互通（fed / Happy Link）状态层契约测试。
// 断言对象是模组侧的逐字段规则：RoomManager.java:358-369 / 766-771 / 1113-1140、
// RoomInfo.java:53-125、RoomCodeRouter.java:8-10,28-30。

import "testing"

func TestApplyFedCreatePayloadNodeModeHasCode(t *testing.T) {
	room := map[string]any{"code": "K7M2QP"}
	// server-go/hlmaster.go:986-992：Node 直连模式的 fed 带 code（白糖侧生成的 HL 房码）
	mirror := applyFedCreatePayload(room, map[string]any{
		"host":       "hl-node.example",
		"tunnelPort": float64(9000),
		"token":      "3f9c8a1b2c3d4e5f",
		"port":       float64(23471),
		"code":       "HLQZ8T3",
	})
	if !mirror {
		t.Fatal("port>0 && token!=\"\" 必须判定为镜像房（RoomInfo.java:83-85）")
	}
	if got := roomStr(room, fedKeyHost); got != "hl-node.example" {
		t.Fatalf("fedHost = %q", got)
	}
	if got := roomInt(room, fedKeyTunnelPort); got != 9000 {
		t.Fatalf("fedTunnelPort = %d", got)
	}
	if got := roomStr(room, fedKeyToken); got != "3f9c8a1b2c3d4e5f" {
		t.Fatalf("fedToken = %q", got)
	}
	if got := roomInt(room, fedKeyPort); got != 23471 {
		t.Fatalf("fedPort = %d", got)
	}
	// HELLO 用 fed.code（RoomManager.java:1116；服务端按 code 查房 hltunnel.go:164-167）
	if got := fedHelloCode(room); got != "HLQZ8T3" {
		t.Fatalf("fedHelloCode = %q, want HLQZ8T3", got)
	}
}

func TestApplyFedCreatePayloadTenantModeFallsBackToOwnCode(t *testing.T) {
	room := map[string]any{"code": "K7M2QP"}
	// server-go/hlmaster.go:1028-1033：Go 租户模式的 fed 根本没有 code 键——
	// 镜像房以 VoxLink 本房码注册（hlmaster.go:1017-1022 的 room_open.code），回退是正确行为。
	if _, ok := room[fedKeyCode]; ok {
		t.Fatal("解析前不应有 fedCode 键")
	}
	mirror := applyFedCreatePayload(room, map[string]any{
		"host":       "47.117.189.43",
		"tunnelPort": float64(9000),
		"token":      "tok",
		"port":       float64(23500),
	})
	if !mirror {
		t.Fatal("租户模式同样必须起隧道")
	}
	if got := roomStr(room, fedKeyCode); got != "" {
		t.Fatalf("fedCode 缺省应为空串, got %q", got)
	}
	if got := fedHelloCode(room); got != "K7M2QP" {
		t.Fatalf("fedHelloCode 应回退本房码, got %q", got)
	}
}

func TestApplyFedCreatePayloadDefaultsAndGates(t *testing.T) {
	// 缺省值逐字段固化（RoomManager.java:361-365）
	room := map[string]any{"code": "ABCDEF"}
	if applyFedCreatePayload(room, map[string]any{}) {
		t.Fatal("空 fed 对象不该判定为镜像（port=0 && token=\"\"）")
	}
	if roomStr(room, fedKeyHost) != "" || roomInt(room, fedKeyTunnelPort) != 0 ||
		roomStr(room, fedKeyToken) != "" || roomInt(room, fedKeyPort) != 0 || roomStr(room, fedKeyCode) != "" {
		t.Fatalf("缺省值不对齐 Java: %+v", room)
	}

	// 启动门是 isFedMirror()，不是 fed!=null
	room2 := map[string]any{"code": "ABCDEF"}
	if applyFedCreatePayload(room2, map[string]any{"host": "h", "token": "t", "tunnelPort": 9000}) {
		t.Fatal("port 缺失(=0) 不得起隧道")
	}
	if applyFedCreatePayload(room2, map[string]any{"host": "h", "port": 23471, "tunnelPort": 9000}) {
		t.Fatal("token 空不得起隧道")
	}

	// D7：判据刻意不看 fedHost —— host 缺失时仍是镜像房（连 "" 主机的退避循环照 Java 保留）
	room3 := map[string]any{"code": "ABCDEF"}
	if !applyFedCreatePayload(room3, map[string]any{"port": 23471, "token": "t"}) {
		t.Fatal("isFedMirror() 不得校验 fedHost")
	}

	// 非对象 / 缺失：Java 的 has("fed") && isJsonObject() 为假时整块跳过（:359）
	room4 := map[string]any{"code": "ABCDEF"}
	if applyFedCreatePayload(room4, "fed?") || applyFedCreatePayload(room4, nil) {
		t.Fatal("非 JSON 对象/缺失必须整体跳过")
	}
	if _, ok := room4[fedKeyHost]; ok {
		t.Fatal("跳过时不得写入任何 fed 键")
	}
	if applyFedCreatePayload(nil, map[string]any{"port": 1, "token": "t"}) {
		t.Fatal("room 为 nil 不应 panic 或判真")
	}
}

func TestRoomIsHappyLinkIsExactAndCaseSensitive(t *testing.T) {
	cases := []struct {
		source string
		want   bool
	}{
		{"happylink", true},  // RoomInfo.java:57 精确相等
		{"HappyLink", false}, // 大小写敏感
		{"happylink ", false},
		{"", false},
	}
	for _, c := range cases {
		room := map[string]any{"source": c.source}
		if got := roomIsHappyLink(room); got != c.want {
			t.Fatalf("source=%q isHappyLink=%v, want %v", c.source, got, c.want)
		}
	}
	if roomIsHappyLink(map[string]any{}) || roomIsHappyLink(nil) {
		t.Fatal("缺 source 时应为 false")
	}
}

func TestApplyFedJoinPayloadNormalizesNull(t *testing.T) {
	// RoomInfo.java:64-74 的 setter 把 null 归一成 ""
	room := map[string]any{"source": nil, "fedAddress": nil}
	applyFedJoinPayload(room)
	if roomStr(room, fedKeySource) != "" || roomStr(room, fedKeyAddress) != "" {
		t.Fatalf("归一失败: %+v", room)
	}

	room2 := map[string]any{}
	applyFedJoinPayload(room2)
	if v, ok := room2[fedKeySource]; !ok || v != "" {
		t.Fatalf("source 键应存在且为空串, got %+v", room2)
	}

	room3 := map[string]any{"source": "happylink", "fedAddress": "47.117.189.43:23471"}
	applyFedJoinPayload(room3)
	if !roomIsHappyLink(room3) || roomFedAddress(room3) != "47.117.189.43:23471" {
		t.Fatalf("正常值不得被改写: %+v", room3)
	}
	applyFedJoinPayload(nil) // 不 panic
}

func TestFedCodePatternsMatchRoomCodeRouter(t *testing.T) {
	fed := []struct {
		code string
		want bool
	}{
		{"HLQZ8T3", true},   // hlImportCode = "HL"+前5位（hlmaster.go:907-914），恒 7 位
		{"hlqz8t3", true},   // isFedCode 先 ToUpper(Locale.ROOT)（RoomCodeRouter.java:29）
		{"HLABCDE", true},   // [A-Z0-9]：宽于服务端字母表，刻意不收紧（规格 4.3）
		{"HLI1O0S", true},   // 同上：服务端不可能生成，仍放行交给服务端判 ROOM_NOT_FOUND
		{"HLQZ8T", false},   // 6 位
		{"HLQZ8T34", false}, // 8 位
		{"K7M2QP", false},   // 普通 VoxLink 码
		{"", false},
		{"HLQZ8T!", false}, // 符号
	}
	for _, c := range fed {
		if got := IsFedCode(c.code); got != c.want {
			t.Fatalf("IsFedCode(%q) = %v, want %v", c.code, got, c.want)
		}
	}

	vl := []struct {
		code string
		want bool
	}{
		{"K7M2QP", true},
		{"k7m2qp", true},
		{"K7M2QI", false}, // [A-HJ-NP-Z2-9] 去 I/O
		{"K7M2QO", false},
		{"K7M2Q1", false}, // 去 0/1
		{"K7M2QPX", false},
	}
	for _, c := range vl {
		if got := IsVoxLinkCode(c.code); got != c.want {
			t.Fatalf("IsVoxLinkCode(%q) = %v, want %v", c.code, got, c.want)
		}
	}
}

// TestValidateJoinRoomCode 对应 RoomManager.java:655-661 的放行判据：6 位 VoxLink 码或 7 位互通码。
func TestValidateJoinRoomCode(t *testing.T) {
	// RoomManager.java:655-661 的放行判据是两条正则的"或"：^[A-HJ-NP-Z2-9]{6}$ 或 RoomCodeRouter.isFedCode。
	// H 与 L 都在 VoxLink 字符集里，所以 6 位的 "HLQZ8T" 本身就是合法 VoxLink 房号——
	// 它不是互通码（互通码固定 7 位），但照样放行，由服务端 join 分流。
	for _, ok := range []string{"K7M2QP", "k7m2qp", "HLQZ8T3", "hlqz8t3", "HLQZ8T"} {
		if !ValidateJoinRoomCode(ok) {
			t.Fatalf("%q 应放行", ok)
		}
	}
	for _, bad := range []string{"", "ABC", "K7M2QI", "HLQZ8T34", "U/ABCD-1234-5678-90AB"} {
		if ValidateJoinRoomCode(bad) {
			t.Fatalf("%q 应拒绝", bad)
		}
	}
	if !IsVoxLinkCode("HLQZ8T") || IsFedCode("HLQZ8T") {
		t.Fatal("HLQZ8T 应判为 VoxLink 码而非互通码")
	}
}

func TestFedRouteName(t *testing.T) {
	if got := FedRouteName("HLQZ8T3"); got != "HappyLink" {
		t.Fatalf("route = %q", got)
	}
	if got := FedRouteName("K7M2QP"); got != "VoxLink" {
		t.Fatalf("route = %q", got)
	}
	if got := FedRouteName("zzz"); got != "Unknown" {
		t.Fatalf("route = %q", got)
	}
}

func TestAppStartAndStopFedTunnel(t *testing.T) {
	a := NewApp()
	room := map[string]any{"code": "K7M2QP"}
	applyFedCreatePayload(room, map[string]any{
		"host": "relay.invalid", "tunnelPort": 9000, "token": "tok", "port": 23471, "code": "HLQZ8T3",
	})
	if !roomIsFedMirror(room) {
		t.Fatal("应先判定为镜像房")
	}

	// 模组的 hostAlive 谓词是"单人世界仍在"（RoomManager.java:1119），app 侧对应"本端房会话仍存活"
	// （fed_state.go fedHostAlive）。会话没起来时隧道会按 Java 的 alive() 判据直接退出，
	// 那不是 bug，是语义——所以这里先装一个在跑的会话。
	a.setState("hosting", "K7M2QP", "host-token", true, room, newSession(a, "K7M2QP", "host-token", true))

	a.startFedTunnelForRoom(room, 25565)
	t.Cleanup(a.stopFedTunnel)

	c := fedTunnelForApp(a)
	if c == nil {
		t.Fatal("隧道未登记")
	}
	if c.host != "relay.invalid" || c.tunnelPort != 9000 || c.token != "tok" || c.localPort != 25565 {
		t.Fatalf("凭据传参错位: %+v", []any{c.host, c.tunnelPort, c.token, c.localPort})
	}
	// RoomManager.java:1116-1118：HELLO 的 code 用 fedCode（无则本房码）
	if c.code != "HLQZ8T3" {
		t.Fatalf("HELLO code = %q, want HLQZ8T3", c.code)
	}
	if !c.IsRunning() {
		t.Fatal("start() 后应在运行（:69-71）")
	}
	if c.GetPublicPort() != fedInitialPublicPort {
		t.Fatalf("publicPort 初值应为 -1（:55）, got %d", c.GetPublicPort())
	}
	if c.hostAlive == nil || c.playerCount == nil {
		t.Fatal("规格 §1.10：app 侧两个谓词不可留 null")
	}
	// hostAlive 谓词两段都要验：app 有会话=真，无会话=假（对应 mc.getSingleplayerServer()==null）。
	// 置回 idle 会让隧道线程按 alive() 退出循环，所以下面 stop() 的断言依然成立。
	if !c.hostAlive() {
		t.Fatal("装好会话后 hostAlive 应为 true")
	}
	a.setState("idle", "", "", false, nil, nil)
	if c.hostAlive() {
		t.Fatal("无会话时 hostAlive 应为 false")
	}
	if got := c.fedPlayers(); got != 0 {
		t.Fatalf("无房客时 playerCount = %d, want 0", got)
	}

	a.stopFedTunnel()
	if c.IsRunning() {
		t.Fatal("stop() 后 running 应为 false（:74）")
	}
	if fedTunnelForApp(a) != nil {
		t.Fatal("stopFedTunnel 应摘除登记（:1135-1136）")
	}
	a.stopFedTunnel() // 幂等：:1137 t != nil 判空
}

func TestAppFedHostAliveTracksSession(t *testing.T) {
	a := NewApp()
	alive := a.fedHostAlive()
	if alive() {
		t.Fatal("idle 时应为 false")
	}
	s := newSession(a, "K7M2QP", "tok", true)
	a.mu.Lock()
	a.session = s
	a.mu.Unlock()
	if !alive() {
		t.Fatal("会话存活时应为 true")
	}
	s.closeDone()
	if alive() {
		t.Fatal("会话结束后应为 false")
	}
	a.mu.Lock()
	a.session = nil
	a.mu.Unlock()
}
