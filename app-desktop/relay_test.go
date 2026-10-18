package main

// relay_test.go — 玩家中继的房主侧契约（对齐模组 1.2.0 的 v2 数据面与房内候选裁决）：
//   - 候选只从**本房成员**里挑：已建立且开启 mux 的锥型中继者（CM:10577-10615）；
//     跨房全局池那条路已被模组判为跨房串流污染并退役（CM:10879-10883）
//   - 一批最多 3 个并行、先按承载量再按 RTO 排序（CM:10639-10663）
//   - 单批 8 秒无结果整批进 60 秒黑名单并递归下一批，总闸 90 秒（CM:10511-10793）
//   - relay_setup 带 replyHostId，relay_accept 必须转告自家房客
//   - relay_accept 必须带 chan 且候选者传输已 mux 化，否则一律不报 connected

import (
	"encoding/json"
	"net"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"
	"time"
)

func newRelayTestApp(t *testing.T, handler http.HandlerFunc) *App {
	t.Helper()
	srv := httptest.NewServer(handler)
	t.Cleanup(srv.Close)
	a := NewApp()
	a.settingsPath = filepath.Join(t.TempDir(), "settings.json")
	a.mu.Lock()
	a.settings.ServerURL = srv.URL
	a.mu.Unlock()
	return a
}

func sendJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(v)
}

// TestFetchRelayCandidatesUsesGlobalPoolWithExcludeRoom 不带 roomCode（带了就只回本房成员），
// 整房排除靠 excludeRoom=<本房>。
// ---- 房内候选挑选与批次机器（对齐 CM:10577-10793；跨房全局池已退役）----

func relayPeerForTest(id string, ip string, port int, nat natClass, caps []string, mux bool, rto int) *hostPeer {
	sock, _ := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4zero, Port: 0})
	rc := newRudp(sock, &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 1})
	if mux {
		rc.enableMux()
	}
	rc.rtoMs = rto
	return &hostPeer{id: id, mapped: &stunMappedAddr{IP: ip, Port: port}, natClass: nat, caps: caps, rudp: rc}
}

// TestPickRelayCandidatesEligibility 六道硬门槛逐条断言（CM:10581-10611）：
// 无映射/无传输/对称或未知 NAT/缺 relay 能力/缺 relayMuxV1 或传输未 mux 化/发起人自己/60 秒黑名单。
func TestPickRelayCandidatesEligibility(t *testing.T) {
	a := NewApp()
	e := a.engine
	ok := func(id string) *hostPeer {
		return relayPeerForTest(id, "203.0.113.10", 40010, natCone,
			[]string{capNameRelay, relayMuxCapability}, true, 300)
	}
	peers := map[string]*hostPeer{
		"good":        ok("good"),
		"noMapped":    func() *hostPeer { q := ok("noMapped"); q.mapped = nil; return q }(),
		"noTransport": func() *hostPeer { q := ok("noTransport"); q.rudp = nil; return q }(),
		"symmetric": relayPeerForTest("symmetric", "203.0.113.11", 40011, natHardSym,
			[]string{capNameRelay, relayMuxCapability}, true, 100),
		"unknownNat": relayPeerForTest("unknownNat", "203.0.113.12", 40012, natUnknown,
			[]string{capNameRelay, relayMuxCapability}, true, 100),
		"legacyRelay": relayPeerForTest("legacyRelay", "203.0.113.13", 40013, natCone,
			[]string{relayMuxCapability}, true, 100),
		"noMuxCap": relayPeerForTest("noMuxCap", "203.0.113.14", 40014, natCone,
			[]string{capNameRelay}, true, 100),
		"muxOff": relayPeerForTest("muxOff", "203.0.113.15", 40015, natCone,
			[]string{capNameRelay, relayMuxCapability}, false, 100),
		"requester": ok("requester"),
	}
	e.mu.Lock()
	e.code = "HOST01"
	e.hPeers = peers
	e.mu.Unlock()

	got := e.pickRelayCandidates("requester")
	if len(got) != 1 || got[0].id != "good" {
		var ids []string
		for _, g := range got {
			ids = append(ids, g.id)
		}
		t.Fatalf("候选集应为 [good]，实际 %v", ids)
	}

	// 60 秒黑名单（CM:210/:417-435）：被拉黑的成员不得再进候选
	e.relayMarkFailed("good")
	if got := e.pickRelayCandidates("requester"); len(got) != 0 {
		t.Fatal("刚被拉黑的候选不得再次入选")
	}
	// 全空时按 CM:10621-10623 清黑名单（让下一次能重新面对全量候选）
	e.relayClearFailures()
	if got := e.pickRelayCandidates("requester"); len(got) != 1 {
		t.Fatalf("清账后应重新可选，实际 %d", len(got))
	}
}

// TestPickRelayCandidatesOrderAndCap 先按承载量升序、再看 RTO（CM:10639-10659），
// 一批最多 3 个（CM:10661-10663）。
func TestPickRelayCandidatesOrderAndCap(t *testing.T) {
	a := NewApp()
	e := a.engine
	caps := []string{capNameRelay, relayMuxCapability}
	e.mu.Lock()
	e.code = "HOST02"
	e.hPeers = map[string]*hostPeer{
		"p1": relayPeerForTest("p1", "203.0.113.21", 40021, natCone, caps, true, 500),
		"p2": relayPeerForTest("p2", "203.0.113.22", 40022, natCone, caps, true, 100),
		"p3": relayPeerForTest("p3", "203.0.113.23", 40023, natCone, caps, true, 300),
		"p4": relayPeerForTest("p4", "203.0.113.24", 40024, natCone, caps, true, 200),
	}
	e.mu.Unlock()

	got := e.pickRelayCandidates("someone")
	if len(got) != maxRelayCandidates {
		t.Fatalf("一批应截到 3 个，实际 %d", len(got))
	}
	want := []string{"p2", "p4", "p3"} // RTO 升序（承载都是 0）
	for i, id := range want {
		if got[i].id != id {
			t.Fatalf("第 %d 位应为 %s（RTO 升序），实际 %s", i+1, id, got[i].id)
		}
	}
}

// TestRelayBatchTimeoutBlacklistsAndRecurses 一批 8 秒没人成桥：整批拉黑（CM:10779-10786）
// 并递归下一批（CM:10789）；下一批已无候选时按 CM:10627 走"无候选"收口。
func TestRelayBatchTimeoutBlacklistsAndRecurses(t *testing.T) {
	oldTimeout := relayBatchTimeout
	relayBatchTimeout = 60 * time.Millisecond
	defer func() { relayBatchTimeout = oldTimeout }()

	a := NewApp()
	e := a.engine
	caps := []string{capNameRelay, relayMuxCapability}
	// 给 4 个合格候选：一批上限 3，所以第一批用掉 3 个、超时拉黑后，
	// 递归的第二批必须只剩第 4 个可选——这样才能真验证"拉黑整批 + 递归下一批"。
	e.mu.Lock()
	e.code = "HOST03"
	e.hPeers = map[string]*hostPeer{
		"s1": relayPeerForTest("s1", "203.0.113.31", 40031, natCone, caps, true, 100),
		"s2": relayPeerForTest("s2", "203.0.113.32", 40032, natCone, caps, true, 110),
		"s3": relayPeerForTest("s3", "203.0.113.33", 40033, natCone, caps, true, 120),
		"s4": relayPeerForTest("s4", "203.0.113.34", 40034, natCone, caps, true, 130),
	}
	e.mu.Unlock()

	// 批超时守卫要求"还是开局那个会话"（CM:10771 的 currentRoom.get() == state），
	// 所以这里必须真给引擎绑一个会话，不能塞 nil。
	sess := newSession(a, "HOST03", "tok", true)
	e.mu.Lock()
	e.sess = sess
	e.mu.Unlock()

	batches := 0
	e.relayBeginManualRelay()
	e.relayDispatchOnce(sess, "target", "203.0.113.99", 40099, func(batch []*hostPeer, index, size int) {
		batches++
	})
	deadline := time.Now().Add(2 * time.Second)
	for batches < 2 && time.Now().Before(deadline) {
		time.Sleep(20 * time.Millisecond)
	}
	if batches < 2 {
		t.Fatalf("批次超时后必须递归下一批，实际只派发 %d 次", batches)
	}
	for _, id := range []string{"s1", "s2", "s3"} {
		if !e.relayPeerFailed(id) {
			t.Fatalf("超时的整批候选 %s 要写进 60 秒黑名单（CM:10777-10779）", id)
		}
	}
	if e.relayPeerFailed("s4") {
		t.Fatal("第二批的候选不该被上一批的超时牵连")
	}
	if got := e.relayCurrentPeer(); got != "s4" {
		t.Fatalf("第二批应把在池中继者记成 s4，实际 %q", got)
	}
	e.relayEndManualRelay()
}

// TestRelayOverallDeadlineStopsDispatch 90 秒总闸到期：不再派发，直接走 failed_timeout（CM:10511-10517）。
func TestRelayOverallDeadlineStopsDispatch(t *testing.T) {
	a := NewApp()
	e := a.engine
	caps := []string{capNameRelay, relayMuxCapability}
	e.mu.Lock()
	e.code = "HOST04"
	e.hPeers = map[string]*hostPeer{
		"x": relayPeerForTest("x", "203.0.113.41", 40041, natCone, caps, true, 100),
	}
	e.mu.Unlock()

	e.relayBeginManualRelay()
	e.mu.Lock()
	m := relayCandidatesOf(e)
	m.deadlineMs = time.Now().UnixMilli() - 1 // 拨到已过期
	e.mu.Unlock()

	sent := 0
	e.relayDispatchOnce(nil, "target", "203.0.113.99", 40099, func(batch []*hostPeer, index, size int) { sent++ })
	if sent != 0 {
		t.Fatal("总闸到期后不得再派发任何候选")
	}
	e.relayEndManualRelay()
}

// TestHostOnRelayAcceptForwardsConnected 候选者回执 relay_accept 后，房主要把
// "桥已就绪"转成 relay_notify{connected} 投给当初发起请求的房客。
func TestHostOnRelayAcceptForwardsConnected(t *testing.T) {
	type sig struct {
		Code   string         `json:"code"`
		Token  string         `json:"token"`
		IsHost bool           `json:"isHost"`
		Type   string         `json:"type"`
		To     string         `json:"to"`
		Data   map[string]any `json:"data"`
	}
	var got []sig
	a := newRelayTestApp(t, func(w http.ResponseWriter, r *http.Request) {
		var s sig
		_ = json.NewDecoder(r.Body).Decode(&s)
		got = append(got, s)
		sendJSON(w, map[string]any{"success": true, "data": map[string]any{}})
	})
	e := a.engine
	e.mu.Lock()
	if e.hPeers == nil {
		e.hPeers = map[string]*hostPeer{}
	}
	e.code, e.token, e.isHost = "HOST01", "host-token", true
	e.mu.Unlock()

	// 玩家中继 v2：host 侧接一条被中继会话，必须有该候选者的、已 mux 化的 joiner 传输。
	uc, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4zero, Port: 0})
	if err != nil {
		t.Fatalf("建测试 UDP socket 失败: %v", err)
	}
	t.Cleanup(func() { _ = uc.Close() })
	candRudp := newRudp(uc, nil)
	candRudp.enableMux()
	e.mu.Lock()
	e.hPeers["cand-1"] = &hostPeer{id: "cand-1", rudp: candRudp}
	e.mu.Unlock()

	// 旧版候选者不带 chan：v1 裸流已被实证为跨流污染，一律不接
	e.hostOnRelayAccept("cand-1", map[string]any{"forClientId": "guest-9"})
	if len(got) != 0 {
		t.Fatalf("chan-less relay_accept must be ignored (v1 retired), signals=%+v", got)
	}

	e.hostOnRelayAccept("cand-1", map[string]any{"forClientId": "guest-9", "chan": 1})
	if len(got) != 1 {
		t.Fatalf("signals = %+v, want exactly one relay_notify", got)
	}
	s := got[0]
	if s.Type != "relay_notify" || s.To != "guest-9" || s.Code != "HOST01" || !s.IsHost {
		t.Fatalf("forwarded signal = %+v", s)
	}
	if s.Data["connected"] != true {
		t.Fatalf("relay_notify must carry connected=true, got %+v", s.Data)
	}

	// 缺 forClientId 就无从转告，不能广播
	e.hostOnRelayAccept("cand-1", map[string]any{"chan": 2})
	if len(got) != 1 {
		t.Fatalf("blank forClientId must not send anything, signals=%+v", got)
	}

	// 候选者传输未 mux 化（例如它其实是旧版）：不报 connected，让被中继侧超时换下一个候选
	e.mu.Lock()
	e.hPeers["cand-old"] = &hostPeer{id: "cand-old", rudp: newRudp(uc, nil)}
	e.mu.Unlock()
	e.hostOnRelayAccept("cand-old", map[string]any{"forClientId": "guest-9", "chan": 3})
	if len(got) != 1 {
		t.Fatalf("non-mux candidate must not report connected, signals=%+v", got)
	}
}

// 注册进候选池时上报的 natType：判 cone 必须连公网 IP 一起比。
// 服务端候选门槛是"含 sym 的不进池"（relaymanager.go:343），
// 所以把对称 NAT 报错成 cone 不是显示问题，而是让别人把流量投递到一台收不到包的候选上。
func TestNatTypeFromSamplesRequiresPublicIPEquality(t *testing.T) {
	sa := func(ip string, port int) stunMappedAddr { return stunMappedAddr{IP: ip, Port: port} }
	cases := []struct {
		name   string
		sample []stunMappedAddr
		want   string
	}{
		{"同 IP 同端口", []stunMappedAddr{sa("1.1.1.1", 20000), sa("1.1.1.1", 20000)}, "port_restricted_cone"},
		{"混族推不出结论", []stunMappedAddr{sa("1.1.1.1", 20000), sa("2400:320::1", 20000)}, "unknown"},
		{"同 IP 漂移", []stunMappedAddr{sa("1.1.1.1", 20000), sa("1.1.1.1", 20400)}, "symmetric"},
		{"异 IP 同端口", []stunMappedAddr{sa("1.1.1.1", 20000), sa("2.2.2.2", 20000)}, "symmetric"},
		{"单样本", []stunMappedAddr{sa("1.1.1.1", 20000)}, "unknown"},
		{"无样本", nil, "unknown"},
	}
	for _, c := range cases {
		if got := natTypeFromSamples(c.sample, ""); got != c.want {
			t.Errorf("%s: got %q want %q", c.name, got, c.want)
		}
	}
	// 无样本才用旧值兜底（有样本时旧值不能盖掉实测结果）
	if got := natTypeFromSamples(nil, "port_restricted_cone"); got != "port_restricted_cone" {
		t.Errorf("空样本应沿用旧值, got %q", got)
	}
	if got := natTypeFromSamples([]stunMappedAddr{sa("1.1.1.1", 20000), sa("2.2.2.2", 20000)}, "port_restricted_cone"); got != "symmetric" {
		t.Errorf("有样本时旧值不该盖掉实测, got %q", got)
	}
}
