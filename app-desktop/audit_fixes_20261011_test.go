package main

// audit_fixes_20261011_test.go — 2026-10-10 台账 §5.2/5.3 复核后仍成立项的行为用例。
//
// 每条都写成"回退即变红"：删掉对应修复，用例必须失败，而不是只断言代码长相。
// 全程只用本机临时端口与假服务器，不碰生产、不碰真实 %APPDATA%。

import (
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

func newTestUDPConn(t *testing.T) *net.UDPConn {
	t.Helper()
	c, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatalf("监听 UDP 失败: %v", err)
	}
	t.Cleanup(func() { _ = c.Close() })
	return c
}

func newTestUDPAddr(t *testing.T) *net.UDPAddr {
	t.Helper()
	ua, err := net.ResolveUDPAddr("udp", "127.0.0.1:40124")
	if err != nil {
		t.Fatalf("解析地址失败: %v", err)
	}
	return ua
}

// L3：房主重复发 relay_notify 时，同轮次第二条不得再建隧道（CM:11239-11241 + CM:10528-10530）。
func TestRelayPunchFlowDropsDuplicateNotify(t *testing.T) {
	a, _ := newAutoRelayTestApp(t)
	e := a.engine

	cases := []struct {
		name  string
		setup func(*rudpConn)
	}{
		{"同轮次已有一条在途", func(*rudpConn) { e.mu.Lock(); e.rPunchLiveAttempt = 7; e.mu.Unlock() }},
		{"隧道已在册", func(rc *rudpConn) { e.mu.Lock(); e.rRudp = rc; e.mu.Unlock() }},
		{"直连已赢", func(*rudpConn) { e.mu.Lock(); e.gP2PDone = true; e.mu.Unlock() }},
	}
	for _, c := range cases {
		liveRudp := newRudp(newTestUDPConn(t), newTestUDPAddr(t))
		e.mu.Lock()
		e.rAttempt, e.rDone, e.rInFlight = 7, false, true
		e.gP2PDone, e.rRudp, e.rBridge, e.rSock, e.rPunchLiveAttempt = false, nil, nil, nil, 0
		e.mu.Unlock()
		c.setup(liveRudp)

		done := make(chan struct{})
		go func() { e.relayPunchFlow(7, "127.0.0.1", 40123); close(done) }()
		select {
		case <-done:
		case <-time.After(2 * time.Second):
			t.Fatalf("%s：重复 relay_notify 仍在建第二条隧道（会覆盖句柄并泄漏 socket+rudp 协程）", c.name)
		}

		e.mu.Lock()
		leaked := e.rSock != nil || e.rBridge != nil || (c.name != "隧道已在册" && e.rRudp != nil)
		e.mu.Unlock()
		if leaked {
			t.Fatalf("%s：被丢弃的一条不应写入任何隧道句柄", c.name)
		}
	}
}

// L1：房主侧 TCP 打洞赢下的成对连接必须被引擎记账，退房即关（模组 trackFallback 那本账）。
func TestHostTcpPunchPairClosedOnTeardown(t *testing.T) {
	a, _ := newAutoRelayTestApp(t)
	e := a.engine

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("假 MC 监听失败: %v", err)
	}
	defer ln.Close()
	accepted := make(chan net.Conn, 4)
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			accepted <- c
			go func(rc net.Conn) { _, _ = io.Copy(io.Discard, rc) }(c)
		}
	}()

	e.mu.Lock()
	e.isHost = true
	e.hostPort = ln.Addr().(*net.TCPAddr).Port
	e.hPeers = map[string]*hostPeer{"joiner-1": {id: "joiner-1"}}
	e.mu.Unlock()

	remote, tunnel := net.Pipe()
	defer remote.Close()
	e.onTcpPunchSocket(tunnel, true, "joiner-1")

	e.mu.Lock()
	pairs := len(e.hTcpPairs)
	e.mu.Unlock()
	if pairs != 2 {
		t.Fatalf("host 侧 TCP 桥两条连接都要登记，实际 %d", pairs)
	}

	e.teardown()

	_ = remote.SetReadDeadline(time.Now().Add(2 * time.Second))
	if _, err := remote.Read(make([]byte, 8)); err == nil {
		t.Fatal("teardown 后通往对端的 TCP 桥仍然活着（退房后残留一条桥）")
	}
	select {
	case mc := <-accepted:
		_ = mc.SetReadDeadline(time.Now().Add(2 * time.Second))
		if _, err := mc.Read(make([]byte, 8)); err == nil {
			t.Fatal("teardown 后通往本地 MC 端口的桥仍然活着")
		}
	case <-time.After(2 * time.Second):
		t.Fatal("引擎没有拨通假 MC 端口")
	}
}

// L2：承载 socket 被外部关掉时，rudp 必须判死上报，而不是空等到 60s 硬超。
func TestRudpDiesWhenCarrierSocketClosed(t *testing.T) {
	sock := newTestUDPConn(t)
	rc := newRudp(sock, newTestUDPAddr(t))
	t.Cleanup(func() { _ = rc.Close() })

	reasons := make(chan string, 4)
	rc.setOnClosed(func(reason string) {
		select {
		case reasons <- reason:
		default:
		}
	})
	rc.Start()

	// turn_tcp.go 的 TCP 兜底收尾就是直接关底层 socket，不经 rudp.Close
	if err := sock.Close(); err != nil {
		t.Fatalf("关闭承载失败: %v", err)
	}
	select {
	case r := <-reasons:
		if !strings.HasPrefix(r, "传输停滞") {
			t.Fatalf("应按判死四类上报，实际 reason=%q", r)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("承载 socket 被外部关闭后 rudp 没有判死（旧行为：读循环每次立即出错→单核空转且永不上报）")
	}
}

// L7：直连探测进行期间第二次调用必须被挡下（旧写法锁只覆盖到起协程前）。
func TestTryDirectRejectsSecondProbeWhileRunning(t *testing.T) {
	a, _ := newAutoRelayTestApp(t)
	e := a.engine

	// TEST-NET-1 不可达，拨号占满 directDialTimeout，探测本体确实在跑
	e.mu.Lock()
	e.hostIp, e.hostPort = "192.0.2.1", 45001
	e.mu.Unlock()

	if err := e.tryDirect(); err != nil {
		t.Fatalf("第一次探测应放行: %v", err)
	}
	err := e.tryDirect()
	if err == nil {
		t.Fatal("探测仍在进行时应拒绝第二次（否则前端会看到两套 conn:state 交替）")
	}
	if !strings.Contains(err.Error(), "已在进行中") {
		t.Fatalf("拒绝文案不对: %v", err)
	}
}

// L8：邀约看门狗只能清自己那一轮的占位，不得抹掉新一轮。
func TestTcpPunchWatchdogOnlyClearsItsOwnRound(t *testing.T) {
	old := tcpPunchWatchdogDelay
	defer func() { tcpPunchWatchdogDelay = old }()

	a, _ := newAutoRelayTestApp(t)
	e := a.engine

	// 第一轮 200ms 看门狗、第二轮 2s：中间这段窗口里只有旧看门狗到期
	tcpPunchWatchdogDelay = 200 * time.Millisecond
	e.tryTcpHolePunch(1, natCone, "1.2.3.4")
	e.mu.Lock()
	first, inFlight := e.tcpPunchToken, e.tcpPunchInFlight
	e.mu.Unlock()
	if !inFlight || first == 0 {
		t.Fatalf("首轮邀约未占位: inFlight=%v token=%d", inFlight, first)
	}

	e.mu.Lock()
	e.tcpPunchInFlight = false
	e.mu.Unlock()
	tcpPunchWatchdogDelay = 2 * time.Second
	e.tryTcpHolePunch(2, natCone, "1.2.3.4")

	time.Sleep(500 * time.Millisecond)
	e.mu.Lock()
	second, still := e.tcpPunchToken, e.tcpPunchInFlight
	e.mu.Unlock()
	if second == first {
		t.Fatal("第二轮没有换令牌")
	}
	if !still {
		t.Fatal("旧看门狗抹掉了新一轮的邀约占位（同一房客会同时出两次邀约）")
	}

	time.Sleep(2 * time.Second)
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.tcpPunchInFlight {
		t.Fatal("本轮看门狗应释放自己的占位")
	}
}

// L6：MC 拨第二条时不得把在跑的第一条打死。模组的 accept 线程自己就在跑泵
// （PB:1126-1127 上行泵 inline），根本没有"第二条被 accept 后接管"这一步；
// 旧写法 go b.pump + setConn(新, 旧) 会让旧泵的收尾通过 b.tcp.Load() 关掉新连接，
// 反过来也在新连接落地瞬间关死旧连接。
func TestGuestBridgeSecondConnectDoesNotKillFirst(t *testing.T) {
	rc := newRudp(newTestUDPConn(t), newTestUDPAddr(t))
	t.Cleanup(func() { _ = rc.Close() })
	rc.Start()

	var mu sync.Mutex
	downCount := 0
	addr, b, err := startGuestBridge(rc, func() {
		mu.Lock()
		downCount++
		mu.Unlock()
	})
	if err != nil {
		t.Fatalf("本地桥启动失败: %v", err)
	}

	c1, err := net.Dial("tcp", addr)
	if err != nil {
		t.Fatalf("MC 首次接入失败: %v", err)
	}
	defer c1.Close()
	waitFor(t, 2*time.Second, func() bool { return b.pumpLive.Load() }, "第一条 MC 连接没有起泵")

	if c2, err := net.Dial("tcp", addr); err == nil {
		defer c2.Close()
	}
	// 第一条必须还活着：读到超时 = 活着；读到非超时错误 = 被收尾关掉了
	_ = c1.SetReadDeadline(time.Now().Add(800 * time.Millisecond))
	if _, err := c1.Read(make([]byte, 16)); err != nil {
		var ne net.Error
		if !errors.As(err, &ne) || !ne.Timeout() {
			t.Fatalf("第二条 MC 连接把第一条打死了: %v", err)
		}
	}

	mu.Lock()
	down := downCount
	mu.Unlock()
	if down != 0 {
		t.Fatalf("第一条还在跑就回调了 downCb（%d 次），会触发整条隧道重打洞", down)
	}
}

// C4 复核结论：不是缺陷。台账写的是"data:null 会把 out 置零值"，实测不成立——
// encoding/json 把 null 解进 struct 是文档规定的 no-op。这两条用例留作行为钉桩：
// 服务端没给 data 时不许动调用方已有的值，给了必须覆盖。
func TestApiNullPayloadKeepsOutUntouched(t *testing.T) {
	type payload struct {
		SessionID string `json:"sessionId"`
		Port      int    `json:"port"`
	}
	for name, body := range map[string]string{
		"absent": `{"success":true}`,
		"null":   `{"success":true,"data":null}`,
	} {
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Content-Type", "application/json")
			_, _ = io.WriteString(w, body)
		}))
		out := payload{SessionID: "keep-me", Port: 7}
		err := newAPIClient().post(srv.URL, "/turn/allocate", nil, &out)
		srv.Close()
		if err != nil {
			t.Fatalf("%s：请求应成功: %v", name, err)
		}
		if out.SessionID != "keep-me" || out.Port != 7 {
			t.Fatalf("%s：服务端没给 data 却把调用方的值清零了（%+v）——排查时分不清\"没返回\"和\"返回了空\"", name, out)
		}
	}
}

// 假服务器返回的就是 success 信封，确认 post 仍会解析非空 data。
func TestApiRealPayloadStillDecodes(t *testing.T) {
	type payload struct {
		SessionID string `json:"sessionId"`
	}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"success":true,"data":{"sessionId":"abc"}}`)
	}))
	defer srv.Close()
	out := payload{SessionID: "stale"}
	if err := newAPIClient().post(srv.URL, "/turn/allocate", nil, &out); err != nil {
		t.Fatalf("请求应成功: %v", err)
	}
	if out.SessionID != "abc" {
		t.Fatalf("有 data 时必须覆盖调用方旧值，实际 %+v", out)
	}
}

// Layer2 中继预热的门（CM:6391-6394）：只有对称 NAT 或同层 CGNAT 才预热，且一次进程只热一次。
func TestRelayPrefetchGate(t *testing.T) {
	a, _ := newAutoRelayTestApp(t)
	e := a.engine
	cases := []struct {
		local, remote natClass
		sameCgnat     bool
		want          bool
		why           string
	}{
		{natCone, natCone, false, false, "锥对锥该直连赢，不该多打一次拓扑"},
		{natHardSym, natCone, false, true, "本端对称"},
		{natCone, natEasySym, false, true, "对端易对称"},
		{natCone, natCone, true, true, "两端同在一层 CGNAT"},
		{natUnknown, natUnknown, false, false, "分级未知不猜"},
	}
	for _, c := range cases {
		e.mu.Lock()
		e.relayPrefetched, e.gSameCgnat = false, c.sameCgnat
		e.mu.Unlock()
		if got := e.shouldPrefetchRelay(c.local, c.remote); got != c.want {
			t.Errorf("%s：local=%v remote=%v sameCgnat=%v 期望 %v 实际 %v", c.why, c.local, c.remote, c.sameCgnat, c.want, got)
		}
	}
	e.mu.Lock()
	e.relayPrefetched = true
	e.mu.Unlock()
	if e.shouldPrefetchRelay(natHardSym, natHardSym) {
		t.Error("预热只做一次（模组 relayPrefetchFuture==null 同口径）")
	}
}

// 预热门放行后，拓扑 poll 真的只发一次；锥对锥时一次都不发。
func TestTopologyPrefetchFiresOnce(t *testing.T) {
	var mu sync.Mutex
	polls := 0
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Query().Get("route") == "/topology/poll" {
			mu.Lock()
			polls++
			mu.Unlock()
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"success":true,"data":{"instructions":[]}}`)
	}))
	defer srv.Close()

	a := NewApp()
	a.settingsPath = filepath.Join(t.TempDir(), "settings.json")
	a.settings.ServerURL = srv.URL
	sink := &captureSink{}
	a.AttachSink(sink, nil)
	a.Init()
	a.settings.ServerURL = srv.URL

	t.Cleanup(func() { topologyPrefetched.Store(false) })
	topologyPrefetched.Store(false)

	tcl := installTopologyClient(a)
	tcl.onRoomJoined("AB12CD", "tok", false, "client-9", 0)
	t.Cleanup(func() { appTopologyClient.Store(nil) })

	for i := 0; i < 5; i++ {
		topologyPrefetchRelay()
	}
	mu.Lock()
	got := polls
	mu.Unlock()
	if got != 1 {
		t.Fatalf("预热应只发一次 /topology/poll，实际 %d 次", got)
	}
}

// CM:3160-3208：房主公布的映射端口列表必须收下来（模组据此开生日打洞），
// 没给列表时退回单端口；漂移判定用逐位比较（Java 的 List.equals）。
func TestGuestStoresHostMappedPorts(t *testing.T) {
	my := getLocalIP()
	if my == "" {
		t.Skip("本机无可用 IPv4 出口")
	}
	a, _ := newAutoRelayTestApp(t)
	e := a.engine

	e.guestOnMapped(map[string]any{
		"hostMappedIp":    my,
		"hostMappedPort":  26001,
		"hostMappedPorts": []any{float64(26001), float64(26002), float64(26003)},
	})
	e.mu.Lock()
	got := append([]int(nil), e.gHostMappedPorts...)
	last := append([]int(nil), e.gLastHostMappedPorts...)
	e.mu.Unlock()
	if len(got) != 3 || got[0] != 26001 || got[2] != 26003 {
		t.Fatalf("应存下三个端口，实际 %v", got)
	}
	if !intSlicesEqual(got, last) {
		t.Fatalf("首次收到的那串要成为漂移基准：got=%v last=%v", got, last)
	}

	// 漂移：换一串 → 基准随之更新
	e.guestOnMapped(map[string]any{
		"hostMappedIp":    my,
		"hostMappedPort":  27001,
		"hostMappedPorts": []any{float64(27001), float64(27002)},
	})
	e.mu.Lock()
	got2 := append([]int(nil), e.gHostMappedPorts...)
	last2 := append([]int(nil), e.gLastHostMappedPorts...)
	e.mu.Unlock()
	if len(got2) != 2 || !intSlicesEqual(got2, last2) || intSlicesEqual(got, got2) {
		t.Fatalf("端口漂移没跟住：old=%v new=%v last=%v", got, got2, last2)
	}

	// 不带列表的旧对端 → 退回单端口（CM:3178-3180）
	e.guestOnMapped(map[string]any{"hostMappedIp": my, "hostMappedPort": 28001})
	e.mu.Lock()
	got3 := append([]int(nil), e.gHostMappedPorts...)
	e.mu.Unlock()
	if len(got3) != 1 || got3[0] != 28001 {
		t.Fatalf("缺列表应退回单端口，实际 %v", got3)
	}
}

func TestIntSlicesEqualMatchesListEquals(t *testing.T) {
	cases := []struct {
		a, b []int
		want bool
	}{
		{nil, nil, true},
		{[]int{1, 2}, []int{1, 2}, true},
		{[]int{1, 2}, []int{1, 2, 3}, false},
		{[]int{1, 2, 3}, []int{1, 3, 2}, false},
		{[]int{}, nil, true},
	}
	for _, c := range cases {
		if got := intSlicesEqual(c.a, c.b); got != c.want {
			t.Errorf("intSlicesEqual(%v,%v)=%v 期望 %v", c.a, c.b, got, c.want)
		}
	}
}

// ---- CM:3406-3425 生日多端口扇出 ----

// fakePunchPeer 起一个"房主映射端口"：收到 PUNCH(56 4C 01) 就回 PUNCH_ACK。
// testPeerIP 打洞候选里的环回会被 [AddrFilter] 挡掉（真实网络里也不可能朝自己打洞），
// 所以假房主要绑在本机的 LAN 地址上。
func testPeerIP(t *testing.T) string {
	t.Helper()
	ip := getLocalIP()
	if ip == "" {
		t.Skip("本机无可用 IPv4 出口")
	}
	return ip
}

func fakePunchPeer(t *testing.T) int {
	t.Helper()
	c, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.ParseIP(testPeerIP(t))})
	if err != nil {
		t.Fatalf("假房主端口监听失败: %v", err)
	}
	t.Cleanup(func() { _ = c.Close() })
	go func() {
		buf := make([]byte, 2048)
		for {
			n, from, err := c.ReadFromUDP(buf)
			if err != nil {
				return
			}
			if n >= 3 && buf[0] == punchMagic0 && buf[1] == punchMagic1 && buf[2] == punchTypePunch {
				_, _ = c.WriteToUDP([]byte{punchMagic0, punchMagic1, punchTypePunchAck, 0, 0, 0, 0, 0, 0, 0, 0}, from)
			}
		}
	}()
	return c.LocalAddr().(*net.UDPAddr).Port
}

func guestTemplateForTest(e *connEngine, gen int) *guestPunchTemplate {
	profile := recommendProfile(natCone, natHardSym)
	params := tunerNextParams(profile, natCone, natHardSym, 0, profile.maxCycles, nil, nil)
	return &guestPunchTemplate{gen: gen, profile: profile, params: params}
}

// 端口串漂移（房主 NAT 换了映射）时，旧的 extra 必须撤掉并按新串重建（CM:3356-3400）。
func TestBirthdayDriftRebuildsExtraPunchers(t *testing.T) {
	ip := testPeerIP(t)
	a, _ := newAutoRelayTestApp(t)
	e := a.engine

	profile := recommendProfile(natCone, natCone)
	gen, _ := e.beginGuestCycle()
	e.mu.Lock()
	e.gPuncher = newPuncher(newTestUDPConn(t), profile,
		tunerNextParams(profile, natCone, natCone, 0, 1, nil, nil), nil)
	e.gPunchTemplate = guestTemplateForTest(e, gen)
	e.mu.Unlock()

	arrOf := func(base, n int) []any {
		out := make([]any, n)
		for i := range out {
			out[i] = float64(base + i)
		}
		return out
	}
	e.guestOnMapped(map[string]any{"hostMappedIp": ip, "hostMappedPort": 42000, "hostMappedPorts": arrOf(42000, 3)})
	e.mu.Lock()
	first := make([]*guestExtraPuncher, 0, len(e.gExtraPunchers))
	for _, ep := range e.gExtraPunchers {
		first = append(first, ep)
	}
	e.mu.Unlock()
	if len(first) != 2 {
		t.Fatalf("第一轮应登记 2 路 extra，实际 %d", len(first))
	}

	// 换一串端口 → 旧的两路必须被关并从表里换掉
	e.guestOnMapped(map[string]any{"hostMappedIp": ip, "hostMappedPort": 43000, "hostMappedPorts": arrOf(43000, 3)})
	e.mu.Lock()
	second := make([]*guestExtraPuncher, 0, len(e.gExtraPunchers))
	for _, ep := range e.gExtraPunchers {
		second = append(second, ep)
	}
	e.mu.Unlock()
	if len(second) != 2 {
		t.Fatalf("漂移后仍应登记 2 路，实际 %d", len(second))
	}
	for _, ep := range first {
		sawClosed := ep.closed.Load()
		if !sawClosed {
			for tries := 0; tries < 40 && !ep.closed.Load(); tries++ {
				time.Sleep(25 * time.Millisecond)
			}
			sawClosed = ep.closed.Load()
		}
		if !sawClosed {
			t.Errorf("漂移后旧的 %s 没有被关闭（会继续朝作废端口喷洒）", ep.key)
		}
		for _, np := range second {
			if np == ep {
				t.Errorf("旧的 %s 被当成新的一路留下了", ep.key)
			}
		}
	}
	e.guestCloseExtraPunchers("test 收尾")
}

// 房主公布多端口时，extras 必须真的能赢下隧道（赢的不是主 puncher），
// 且赢的那一路收尾干净（登记表摘掉、socket 归属交给 rudp）。
func TestBirthdayExtraPuncherWinsTunnel(t *testing.T) {
	a, sink := newAutoRelayTestApp(t)
	e := a.engine

	ip := testPeerIP(t)
	ports := []int{fakePunchPeer(t), fakePunchPeer(t), fakePunchPeer(t)}
	arr := make([]any, len(ports))
	for i, p := range ports {
		arr[i] = float64(p)
	}

	// 主 puncher 存在但不起跑：它永远不可能赢，用来证明隧道是 extra 建立的
	primarySock, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.ParseIP(ip)})
	if err != nil {
		t.Fatalf("主 puncher socket 失败: %v", err)
	}
	t.Cleanup(func() { _ = primarySock.Close() })
	primary := newPuncher(primarySock, recommendProfile(natCone, natCone),
		tunerNextParams(recommendProfile(natCone, natCone), natCone, natCone, 0, 1, nil, nil), nil)
	primary.setTarget(&net.UDPAddr{IP: net.ParseIP(ip), Port: ports[0]})

	gen, _ := e.beginGuestCycle()
	e.mu.Lock()
	e.gPuncher = primary
	e.gPunchSock = primarySock
	e.gPunchTemplate = guestTemplateForTest(e, gen)
	e.mu.Unlock()

	e.guestOnMapped(map[string]any{
		"hostMappedIp":    ip,
		"hostMappedPort":  ports[0],
		"hostMappedPorts": arr,
		"hostLocalIp":     "",
	})

	waitFor(t, 15*time.Second, func() bool {
		e.mu.Lock()
		defer e.mu.Unlock()
		return e.gP2PDone
	}, "extra 生日 puncher 没有建立隧道")

	if primary.completed.Load() {
		t.Fatal("隧道应由 extra 建立，主 puncher 没跑却判赢了")
	}
	e.mu.Lock()
	left := len(e.gExtraPunchers)
	rudp := e.gRudp
	e.mu.Unlock()
	if rudp == nil {
		t.Fatal("gP2PDone 却没有 rudp 承载")
	}
	if left != 0 {
		t.Errorf("赢/败的 extra 都该从登记表摘掉，实际还剩 %d 路", left)
	}
	ok := false
	for _, ev := range connEvents(sink) {
		addr, _ := ev["address"].(string)
		if ev["phase"] == "p2p" && ev["status"] == "success" && strings.HasPrefix(addr, "127.0.0.1:") {
			ok = true
		}
	}
	if !ok {
		t.Fatalf("成功事件必须给本机桥地址，实际 %v", connEvents(sink))
	}

	e.teardown()
	e.mu.Lock()
	afterTeardown := e.gExtraPunchers
	e.mu.Unlock()
	if afterTeardown != nil {
		t.Errorf("teardown 后 extras 表应为 nil，实际 %v", afterTeardown)
	}
}

// 端口数上限按同 CGNAT 加密到 12、否则 6（CM:3407），第 0 个归主 puncher。
func TestBirthdayCapFollowsSameCgnat(t *testing.T) {
	cases := []struct {
		name      string
		sameCgnat bool
		want      int
	}{
		{"普通锥型 cap=6", false, 5},      // 除第 0 个（主 puncher）外 5 路
		{"同层 CGNAT cap=12", true, 11}, // CM:3407 回环命中率低，覆盖加密
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			ip := testPeerIP(t)
			a, _ := newAutoRelayTestApp(t) // 每例一份会话：teardown 会把 sess 置 nil
			e := a.engine

			arr := make([]any, 13)
			for i := range arr {
				arr[i] = float64(41000 + i) // 无人监听的端口，只数登记不数赢
			}
			profile := recommendProfile(natCone, natCone)
			gen, _ := e.beginGuestCycle()
			e.mu.Lock()
			e.gP2PDone = false
			e.gSameCgnat = c.sameCgnat
			e.gExtraPunchers = nil
			e.gPuncher = newPuncher(newTestUDPConn(t), profile,
				tunerNextParams(profile, natCone, natCone, 0, 1, nil, nil), nil)
			e.gPunchTemplate = guestTemplateForTest(e, gen)
			if c.sameCgnat {
				e.gMyMappedIP = ip // 两端映射同一个公网 IP = 同层 CGNAT
			}
			e.mu.Unlock()

			e.guestOnMapped(map[string]any{
				"hostMappedIp":    ip,
				"hostMappedPort":  41000,
				"hostMappedPorts": arr,
			})
			e.mu.Lock()
			got := len(e.gExtraPunchers)
			e.mu.Unlock()
			if got != c.want {
				t.Errorf("sameCgnat=%v 期望 %d 路 extra，实际 %d", c.sameCgnat, c.want, got)
			}
			e.guestCloseExtraPunchers("test 收尾")
		})
	}
}

// 退房必须把在途的生日 puncher 一起收掉：每一路都自带 socket，
// 不登记不关闭就会在退房后继续朝作废端口喷洒（模组的 stopAllConnectionWork 全数 cancel+close）。
func TestTeardownClosesInFlightExtraPunchers(t *testing.T) {
	ip := testPeerIP(t)
	a, _ := newAutoRelayTestApp(t)
	e := a.engine

	profile := recommendProfile(natCone, natCone)
	gen, _ := e.beginGuestCycle()
	e.mu.Lock()
	e.gPuncher = newPuncher(newTestUDPConn(t), profile,
		tunerNextParams(profile, natCone, natCone, 0, 1, nil, nil), nil)
	e.gPunchTemplate = guestTemplateForTest(e, gen)
	e.mu.Unlock()

	arr := make([]any, 4)
	for i := range arr {
		arr[i] = float64(44000 + i) // 没人监听：这几路会一直打在途，正好用来验收尾
	}
	e.guestOnMapped(map[string]any{"hostMappedIp": ip, "hostMappedPort": 44000, "hostMappedPorts": arr})

	e.mu.Lock()
	live := make([]*guestExtraPuncher, 0, len(e.gExtraPunchers))
	for _, ep := range e.gExtraPunchers {
		live = append(live, ep)
	}
	e.mu.Unlock()
	if len(live) != 3 {
		t.Fatalf("应登记 3 路在途 extra，实际 %d", len(live))
	}

	e.teardown()

	for _, ep := range live {
		if !ep.closed.Load() {
			t.Errorf("%s（端口 %d）在退房后仍在喷洒", ep.key, ep.port)
		}
	}
}
