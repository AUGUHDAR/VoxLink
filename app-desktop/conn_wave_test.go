package main

// conn_wave_test.go — 直连波次接线层的行为用例。
// 全程不连生产：会话用 newAutoRelayTestApp 的假服务器，直连目标是本机 127.0.0.1 临时监听口。

import (
	"net"
	"strconv"
	"strings"
	"testing"
	"time"
)

func waveRoom(hostPort int) map[string]any {
	return map[string]any{"code": "AB12CD", "clientType": "app", "hostPort": hostPort}
}

func TestFbIsSameLanOnlyComparesFirstThreeOctets(t *testing.T) {
	my := getLocalIP()
	if my == "" {
		t.Skip("本机无可用 IPv4 出口")
	}
	parts := strings.Split(my, ".")
	if len(parts) != 4 {
		t.Skip("本机地址不是点分四段")
	}
	// 第四段变、前三段相同 → 同网段（StunDetector.java:135 只看前三段）
	fourth := (ai2(parts[3]) + 7) % 250
	same := parts[0] + "." + parts[1] + "." + parts[2] + "." + strconv.Itoa(fourth+1)
	if !fbIsSameLan(same) {
		t.Errorf("前三段相同应判同网段: my=%s host=%s", my, same)
	}
	third := (ai2(parts[2]) + 1) % 250
	diff := parts[0] + "." + parts[1] + "." + strconv.Itoa(third+1) + "." + parts[3]
	if fbIsSameLan(diff) {
		t.Errorf("第三段不同不应判同网段: my=%s host=%s", my, diff)
	}
	for _, bad := range []string{"", "not-an-ip", "1.2.3", "::1", "1.2.3.4.5"} {
		if fbIsSameLan(bad) {
			t.Errorf("非法/非四段地址必须为 false: %q", bad)
		}
	}
}

func ai2(s string) int {
	n, _ := strconv.Atoi(s)
	return n
}

func TestStoreGuestHostLocalIpOnlyIfEmpty(t *testing.T) {
	a, _ := newAutoRelayTestApp(t)
	e := a.engine
	e.mu.Lock()
	e.gHostLocalIp = ""
	e.mu.Unlock()

	// CM:2650 offer 支路无条件写入
	e.storeGuestHostLocalIp("192.168.1.5", false, "holepunch_offer")
	if got := e.guestHostLocalIpForTest(); got != "192.168.1.5" {
		t.Fatalf("offer 路径应无条件写入, got %q", got)
	}
	// CM:3284 mapped 支路只在尚未设置时写，不能覆盖 offer 的值
	e.storeGuestHostLocalIp("10.0.0.9", true, "holepunch_mapped")
	if got := e.guestHostLocalIpForTest(); got != "192.168.1.5" {
		t.Fatalf("mapped 支路不得覆盖已有值, got %q", got)
	}
	e.storeGuestHostLocalIp("", true, "holepunch_mapped")
	if got := e.guestHostLocalIpForTest(); got != "192.168.1.5" {
		t.Fatalf("空值不得写入, got %q", got)
	}
}

func TestRefreshGuestSameCgnatNeedsBothIPs(t *testing.T) {
	a, _ := newAutoRelayTestApp(t)
	e := a.engine

	e.mu.Lock()
	e.gMyMappedIP = ""
	e.gMapped = &stunMappedAddr{IP: "39.158.48.93", Port: 1234}
	e.gSameCgnat = false
	e.mu.Unlock()
	e.refreshGuestSameCgnat()
	if e.gSameCgnatForTest() {
		t.Fatal("本端映射未知时不得判定同 CGNAT")
	}

	e.mu.Lock()
	e.gMyMappedIP = "39.158.48.93"
	e.mu.Unlock()
	e.refreshGuestSameCgnat()
	if !e.gSameCgnatForTest() {
		t.Fatal("两端映射同公网 IP 应判定同 CGNAT（CM:2874）")
	}

	e.mu.Lock()
	e.gSameCgnat = false
	e.gMyMappedIP = "39.158.48.94"
	e.gMapped = &stunMappedAddr{IP: "39.158.48.93", Port: 1234}
	e.mu.Unlock()
	e.refreshGuestSameCgnat()
	if e.gSameCgnatForTest() {
		t.Fatal("公网 IP 不同绝不能判定同 CGNAT")
	}
}

// 门禁：既不同网段也不同 CGNAT 时，Wave1 一条都不拨（CM:6827）。
func TestWave1GateNoLocalIpNoCgnat(t *testing.T) {
	up := startTestListener(t)
	defer up.Close()
	a, sink := newAutoRelayTestApp(t)
	e := a.engine
	port := up.Addr().(*net.TCPAddr).Port
	a.setState("in_room", "AB12CD", "client-token", false, waveRoom(port), e.sess)

	e.mu.Lock()
	e.gHostLocalIp = "" // 没有内网 IP
	e.gSameCgnat = false
	e.hostPort = port
	e.mu.Unlock()
	e.refreshGuestSameCgnat()

	// 直连一条都不该发起：用假监听口的 accept 次数验证
	accepted := make(chan struct{}, 8)
	go func() {
		for {
			_ = up.(*net.TCPListener).SetDeadline(time.Now().Add(2 * time.Second))
			c, err := up.Accept()
			if err != nil {
				return
			}
			_ = c.Close()
			select {
			case accepted <- struct{}{}:
			default:
			}
		}
	}()
	go e.guestWave1Direct(e.gCycleForTest()) // 与打洞并行：这里异步起，用例只看它有没有拨出去
	time.Sleep(1200 * time.Millisecond)
	if n := len(accepted); n != 0 {
		t.Errorf("门禁未过时不应有 TCP 连接落进假监听口, accepted=%d", n)
	}
	if e.p2pGuestDone() {
		t.Error("未发起直连却占了胜负位")
	}
	_ = sink
}

// Wave1 胜出的落位：本地桥口可连、数据面穿透到假 MC、成功卡地址是本机桥口。
func TestGuestConnectViaBridgeBuildsLocalBridge(t *testing.T) {
	a, sink := newAutoRelayTestApp(t)
	e := a.engine

	upstream := startTestListener(t) // 假 MC：收到什么就回 "MC:"+原文
	defer upstream.Close()
	go func() {
		for {
			c, err := upstream.Accept()
			if err != nil {
				return
			}
			go func(c net.Conn) {
				defer c.Close()
				buf := make([]byte, 64)
				n, _ := c.Read(buf)
				_, _ = c.Write(append([]byte("MC:"), buf[:n]...))
			}(c)
		}
	}()
	port := upstream.Addr().(*net.TCPAddr).Port
	a.setState("in_room", "AB12CD", "client-token", false, waveRoom(port), e.sess)

	res := fbSuccess("IPv4", "127.0.0.1", port, fbModeIPv4Direct)
	e.guestConnectViaBridge(res, "test")

	if !e.p2pGuestDone() {
		t.Fatal("建桥成功应占用链路胜负位")
	}
	e.mu.Lock()
	addr := ""
	if e.gTcpLn != nil {
		addr = e.gTcpLn.Addr().String()
	}
	e.mu.Unlock()
	if addr == "" {
		t.Fatal("本地桥监听口未登记")
	}
	if !strings.HasPrefix(addr, "127.0.0.1:") {
		t.Errorf("成功地址必须是本机桥口, got %s", addr)
	}

	conn, err := net.DialTimeout("tcp", addr, 2*time.Second)
	if err != nil {
		t.Fatalf("本地桥不可连: %v", err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(3 * time.Second))
	if _, err := conn.Write([]byte("ping")); err != nil {
		t.Fatalf("写入本地桥失败: %v", err)
	}
	buf := make([]byte, 32)
	n, err := conn.Read(buf)
	if err != nil {
		t.Fatalf("读回本地桥失败: %v", err)
	}
	if got := string(buf[:n]); got != "MC:ping" {
		t.Errorf("数据面未穿透到假 MC, got %q", got)
	}

	// 成功卡必须是本机桥口，不能是房主地址（模组里 MC 在进程内，应用端不是）
	var last map[string]any
	for _, ev := range sink.events {
		if ev["_event"] == "conn:state" {
			last = ev
		}
	}
	if last == nil {
		t.Fatal("没有 conn:state 事件")
	}
	if last["status"] != statusSuccess || last["address"] != addr {
		t.Errorf("成功事件应为 {success,%s}, got %+v", addr, last)
	}

	e.guestTeardownP2P()
	if e.p2pGuestDone() {
		t.Error("拆桥后胜负位应复位")
	}
}

// 拨不通时既不占位也不退房：直连只是与打洞并行的一路。
func TestGuestConnectViaBridgeFailureKeepsPunching(t *testing.T) {
	a, _ := newAutoRelayTestApp(t)
	e := a.engine

	ln := startTestListener(t) // 立刻关掉 → 端口必然拒绝连接
	addr := ln.Addr().(*net.TCPAddr).Port
	ln.Close()

	res := fbSuccess("IPv4", "127.0.0.1", addr, fbModeIPv4Direct)
	done := make(chan struct{})
	go func() { e.guestConnectViaBridge(res, "test"); close(done) }()
	select {
	case <-done:
	case <-time.After(60 * time.Second):
		t.Fatal("失败支路未在重试上限内收敛")
	}
	if e.p2pGuestDone() {
		t.Error("拨不通不得占用胜负位（打洞与自动中继必须继续）")
	}
}

// CGNAT 即时直连的门禁三连（CM:3292）：周期未活跃 / 非同 CGNAT / 已连通都不许拨。
func TestCgnatDirectOnMappedGates(t *testing.T) {
	a, _ := newAutoRelayTestApp(t)
	e := a.engine
	up := startTestListener(t)
	defer up.Close()
	port := up.Addr().(*net.TCPAddr).Port

	accepted := make(chan struct{}, 8)
	go func() {
		for {
			_ = up.(*net.TCPListener).SetDeadline(time.Now().Add(2 * time.Second))
			c, err := up.Accept()
			if err != nil {
				return
			}
			_ = c.Close()
			select {
			case accepted <- struct{}{}:
			default:
			}
		}
	}()
	a.setState("in_room", "AB12CD", "client-token", false, waveRoom(port), e.sess)

	e.mu.Lock()
	e.gHostLocalIp = "127.0.0.1"
	e.hostPort = port
	e.gActive = false // 周期未活跃
	e.gSameCgnat = true
	e.mu.Unlock()
	e.guestCgnatDirectOnMapped()
	if len(accepted) != 0 {
		t.Error("周期未活跃时不得发起直连")
	}

	e.mu.Lock()
	e.gActive = true
	e.gSameCgnat = false // 非同 CGNAT
	e.mu.Unlock()
	e.guestCgnatDirectOnMapped()
	if len(accepted) != 0 {
		t.Error("非同 CGNAT 时不得发起内网直连")
	}

	e.mu.Lock()
	e.gSameCgnat = true
	e.gP2PDone = true // 已有链路
	e.mu.Unlock()
	e.guestCgnatDirectOnMapped()
	if len(accepted) != 0 {
		t.Error("已连通时不得再发起直连")
	}

	e.mu.Lock()
	e.gP2PDone = false
	e.mu.Unlock()
	e.guestCgnatDirectOnMapped()
	select {
	case <-accepted:
	case <-time.After(2 * time.Second):
		t.Fatal("三门都过时应发起一次直连")
	}
}

func startTestListener(t *testing.T) net.Listener {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	return ln
}

// 测试侧读位辅助（同包，只为把断言写得直白）。

func (e *connEngine) guestHostLocalIpForTest() string {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.gHostLocalIp
}

func (e *connEngine) gSameCgnatForTest() bool {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.gSameCgnat
}

func (e *connEngine) gCycleForTest() int {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.gCycle
}
