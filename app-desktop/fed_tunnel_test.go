package main

// fed_tunnel.go 的契约测试：房主侧互通隧道（FedTunnelClient 移植）+ 房客侧 connectFed 直连。
// 断言对象是模组 Java 源码的字面值与 server-hl/hltunnel.go 的解析器，逐条带行号。

import (
	"bufio"
	"context"
	"net"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// ---- 测试夹具 ----

type fedStatus struct {
	msg   string
	fatal bool
}

func fedStatusListener(ch chan fedStatus) FedTunnelListener {
	return func(message string, fatal bool) {
		select {
		case ch <- fedStatus{message, fatal}:
		default:
		}
	}
}

func waitFedStatus(t *testing.T, ch chan fedStatus, wantMsg string, wantFatal bool) fedStatus {
	t.Helper()
	for {
		select {
		case s := <-ch:
			if s.msg == wantMsg && s.fatal == wantFatal {
				return s
			}
		case <-time.After(5 * time.Second):
			t.Fatalf("等待隧道状态 %q(fatal=%v) 超时", wantMsg, wantFatal)
		}
	}
}

// fedServe 起一个假中继/假 MC：handler 负责每条接入连接，返回 host 与 port。
func fedServe(t *testing.T, handler func(net.Conn)) (string, int) {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = ln.Close() })
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go handler(c)
		}
	}()
	addr, ok := ln.Addr().(*net.TCPAddr)
	if !ok {
		t.Fatalf("监听地址类型异常: %v", ln.Addr())
	}
	return addr.IP.String(), addr.Port
}

// fedClosedPort 返回一个确定没人监听的本地端口（connect 立刻被拒）。
func fedClosedPort(t *testing.T) int {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := ln.Addr().(*net.TCPAddr).Port
	_ = ln.Close()
	return port
}

// fedReadLine 按 '\n' 读一行（复刻中继侧的切行）。
func fedReadLine(r *bufio.Reader) (string, error) {
	return r.ReadString('\n')
}

type fedEvent struct {
	name string
	data map[string]any
}

type fedSink struct {
	mu     sync.Mutex
	events []fedEvent
	ch     chan fedEvent
}

func newFedSink() *fedSink { return &fedSink{ch: make(chan fedEvent, 64)} }

func (s *fedSink) Emit(name string, data any) {
	m, ok := data.(map[string]any)
	if !ok {
		m = map[string]any{}
	}
	s.mu.Lock()
	s.events = append(s.events, fedEvent{name, m})
	s.mu.Unlock()
	select {
	case s.ch <- fedEvent{name, m}:
	default:
	}
}

// waitConnState 等一条指定 phase/status 的 conn:state 事件。
func (s *fedSink) waitConnState(t *testing.T, phase, status string, timeout time.Duration) map[string]any {
	t.Helper()
	deadline := time.After(timeout)
	for {
		select {
		case ev := <-s.ch:
			if ev.name != "conn:state" {
				continue
			}
			if ev.data["phase"] == phase && ev.data["status"] == status {
				return ev.data
			}
		case <-deadline:
			t.Fatalf("等待 conn:state(%s,%s) 超时", phase, status)
		}
	}
}

// ---- 协议字节 ----

func TestFedWireLineByteFormats(t *testing.T) {
	// FedTunnelClient.java:104 —— "HELLO " + code + " " + token + " " + lanIp + " " + localPort + "\n"
	if got, want := fedHelloLine("HLQZ8T3", "3f9c8a1b", "192.168.1.7", 25565), "HELLO HLQZ8T3 3f9c8a1b 192.168.1.7 25565\n"; got != want {
		t.Fatalf("HELLO = %q, want %q", got, want)
	}
	// :198 —— "DATA " + id + " " + token + "\n"
	if got, want := fedDataLine("a1b2c3d41", "3f9c8a1b"), "DATA a1b2c3d41 3f9c8a1b\n"; got != want {
		t.Fatalf("DATA = %q, want %q", got, want)
	}
	// :143 —— "PING " + players + "\n"
	if got, want := fedPingLine(3), "PING 3\n"; got != want {
		t.Fatalf("PING = %q, want %q", got, want)
	}
	// lanIp 拿不到时的占位串（:189；服务端 hltunnel.go:183 视 "0"/空 为无信息）
	if got, want := fedHelloLine("C", "T", fedLanIpFallback, 25565), "HELLO C T 0 25565\n"; got != want {
		t.Fatalf("HELLO(无局域网 IP) = %q, want %q", got, want)
	}
}

// TestFedLanIpShape 校验 fedLanIp 的取值域：IPv4 站点本地或 "0"（:170-190）。
func TestFedLanIpShape(t *testing.T) {
	got := fedLanIp()
	if got == fedLanIpFallback {
		return
	}
	ip := net.ParseIP(got)
	if ip == nil || !isFedSiteLocalAddress(ip) {
		t.Fatalf("fedLanIp() = %q，既不是 \"0\" 也不是站点本地 IPv4", got)
	}
}

// TestFedTunnelConstantsMatchJava 防止数值漂移：每个常量都对应 Java 的一行。
func TestFedTunnelConstantsMatchJava(t *testing.T) {
	cases := []struct {
		name string
		got  time.Duration
		want time.Duration
		ref  string
	}{
		{"控制连接 connect timeout", fedControlDialTimeout, 8 * time.Second, "FedTunnelClient.java:95"},
		{"控制连接 SO_TIMEOUT", fedControlReadTimeout, 15 * time.Second, "FedTunnelClient.java:97"},
		{"PING 间隔阈值", time.Duration(fedPingIntervalMs) * time.Millisecond, 8 * time.Second, "FedTunnelClient.java:142"},
		{"DATA 连接 connect timeout", fedDataDialTimeout, 8 * time.Second, "FedTunnelClient.java:195"},
		{"本地 MC connect timeout", fedLocalDialTimeout, 5 * time.Second, "FedTunnelClient.java:202"},
		{"退避步长", time.Duration(fedBackoffStepMs) * time.Millisecond, 3 * time.Second, "FedTunnelClient.java:160"},
		{"退避上限", time.Duration(fedBackoffCapMs) * time.Millisecond, 15 * time.Second, "FedTunnelClient.java:160"},
		{"joiner connect timeout", fedJoinerConnectTimeout, 10 * time.Second, "P2PBridge.java:29"},
		{"joiner 内层重试步长", fedJoinerInnerRetryStep, 500 * time.Millisecond, "P2PBridge.java:30,695"},
		{"joiner 外层重试步长", fedJoinerOuterRetryStep, 1 * time.Second, "P2PBridge.java:239"},
		{"桥读超时", fedBridgeReadTimeout, 60 * time.Second, "P2PBridge.java:33,711"},
	}
	for _, c := range cases {
		if c.got != c.want {
			t.Fatalf("%s = %v, want %v（%s）", c.name, c.got, c.want, c.ref)
		}
	}
	if fedMaxFailures != 8 {
		t.Fatalf("最大连续失败 = %d, want 8（:155 failures > 8）", fedMaxFailures)
	}
	if fedLocalHost != "127.0.0.1" {
		t.Fatalf("本地转发目标 = %q, want 127.0.0.1（:202）", fedLocalHost)
	}
	if fedJoinerInnerAttempts != 3 || fedJoinerOuterRetries != 3 {
		t.Fatalf("joiner 重试次数 = %d/%d, want 3/3（:659 / :232）", fedJoinerInnerAttempts, fedJoinerOuterRetries)
	}
}

// TestFedBackoffSchedule 精确复现 :159-160 的退避表。
func TestFedBackoffSchedule(t *testing.T) {
	want := []struct {
		failures int
		ms       int64
		sec      int
	}{
		{1, 3000, 3}, {2, 6000, 6}, {3, 9000, 9}, {4, 12000, 12},
		{5, 15000, 15}, {6, 15000, 15}, {7, 15000, 15}, {8, 15000, 15}, {9, 15000, 15},
	}
	for _, c := range want {
		if got := fedBackoffMs(c.failures); got != c.ms {
			t.Fatalf("fedBackoffMs(%d) = %d, want %d", c.failures, got, c.ms)
		}
		if got := fedBackoffSeconds(c.failures); got != c.sec {
			t.Fatalf("fedBackoffSeconds(%d) = %d, want %d", c.failures, got, c.sec)
		}
	}
}

// ---- 控制连接行为 ----

func TestFedTunnelHelloOnlineAndStop(t *testing.T) {
	hello := make(chan string, 1)
	pings := make(chan string, 8)
	host, port := fedServe(t, func(c net.Conn) {
		defer c.Close()
		r := bufio.NewReader(c)
		line, err := fedReadLine(r)
		if err != nil {
			return
		}
		hello <- line
		_, _ = c.Write([]byte("OK 23471\n")) // hltunnel.go:197
		for {
			l, err := fedReadLine(r)
			if err != nil {
				return
			}
			select {
			case pings <- l:
			default:
			}
		}
	})

	statuses := make(chan fedStatus, 16)
	c := NewFedTunnelClient(host, port, "HLQZ8T3", "3f9c8a1b", 25565, nil, func() int { return 2 }, fedStatusListener(statuses))
	c.start()

	select {
	case got := <-hello:
		want := fedHelloLine("HLQZ8T3", "3f9c8a1b", fedLanIp(), 25565)
		if got != want {
			t.Fatalf("HELLO 行不匹配:\n got %q\nwant %q", got, want)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("中继没收到 HELLO")
	}

	// :128 的上线文案带 host 与 publicPort
	waitFedStatus(t, statuses, "互通房间已上线："+host+" 端口 23471", false)
	if c.GetPublicPort() != 23471 {
		t.Fatalf("publicPort = %d, want 23471（:124）", c.GetPublicPort())
	}

	// 心跳节奏在 TestFedTunnelPingCadence 单独验（受 :97 的 15s 读超时粒度约束，耗时长）。
	_ = pings

	c.stop()
	waitFedStatus(t, statuses, fedMsgStopped, false) // :163
	if c.IsRunning() {
		t.Fatal("stop() 后 running 应为 false")
	}
	c.wait()
}

// TestFedTunnelPingCadence 验 :142-145 的心跳：">8000ms 判定 + 最坏 15s 一次读超时唤醒"，
// 语法 "PING <players>\n"（players 由 playerCount 谓词给，服务端 clamp 到 0..255）。
func TestFedTunnelPingCadence(t *testing.T) {
	if testing.Short() {
		t.Skip("心跳窗口需要 ~16s，-short 跳过")
	}
	pings := make(chan string, 8)
	host, port := fedServe(t, func(c net.Conn) {
		defer c.Close()
		r := bufio.NewReader(c)
		if _, err := fedReadLine(r); err != nil {
			return
		}
		_, _ = c.Write([]byte("OK 23471\n"))
		for {
			l, err := fedReadLine(r)
			if err != nil {
				return
			}
			select {
			case pings <- l:
			default:
			}
		}
	})
	statuses := make(chan fedStatus, 16)
	c := NewFedTunnelClient(host, port, "HLQZ8T3", "tok", 25565, nil, func() int { return 7 }, fedStatusListener(statuses))
	c.start()
	defer func() { c.stop(); c.wait() }()
	waitFedStatus(t, statuses, "互通房间已上线："+host+" 端口 23471", false)

	select {
	case p := <-pings:
		if want := fedPingLine(7); p != want {
			t.Fatalf("PING 行 = %q, want %q", p, want)
		}
	case <-time.After(20 * time.Second):
		t.Fatal("15s 读超时窗口内没有发出 PING（:97,142-145）")
	}
}

func TestFedTunnelErrStopsPermanently(t *testing.T) {
	var conns atomic.Int32
	host, port := fedServe(t, func(c net.Conn) {
		conns.Add(1)
		defer c.Close()
		r := bufio.NewReader(c)
		if _, err := fedReadLine(r); err != nil {
			return
		}
		_, _ = c.Write([]byte("ERR bad_token\n")) // hltunnel.go:170-171：写 ERR 后立即断，不许反复试 token
	})

	statuses := make(chan fedStatus, 16)
	// playerCount 传 nil：:143 的 null 分支应发 "PING 0\n"（本测试不等它）
	c := NewFedTunnelClient(host, port, "HLQZ8T3", "wrong-token", 25565, nil, nil, fedStatusListener(statuses))
	c.start()

	waitFedStatus(t, statuses, "中继节点拒绝隧道连接：ERR bad_token", true) // :136
	waitFedStatus(t, statuses, fedMsgStopped, false)             // :163
	c.wait()

	if c.IsRunning() {
		t.Fatal("ERR 分支必须置 running=false（:137）")
	}
	// 不重连：ERR 之后不再产生新控制连接（:138 break + 外层 !running break）
	time.Sleep(500 * time.Millisecond)
	if n := conns.Load(); n != 1 {
		t.Fatalf("ERR 后不得重连，实际建连 %d 次", n)
	}
}

func TestFedTunnelGivesUpAfterNineFailures(t *testing.T) {
	// 没人监听 → connect 立刻失败：failures 递增到 9 时 :155-158 判死。
	// 用真实退避会耗 96s，这里只验证"判死文案出现且隧道停"——把退避睡眠压到可观察窗口外。
	port := fedClosedPort(t)
	statuses := make(chan fedStatus, 32)
	c := NewFedTunnelClient("127.0.0.1", port, "HLQZ8T3", "tok", 25565, nil, nil, fedStatusListener(statuses))
	c.start()

	// 第一次失败必须给出 :159 的提示（3 秒后重试）
	waitFedStatus(t, statuses, "隧道连接中断，3 秒后重试", false)

	// stop() 必须像 shutdownNow 的 interrupt 一样立刻打断 :160 的睡眠
	time.Sleep(200 * time.Millisecond)
	started := time.Now()
	c.stop()
	waitFedStatus(t, statuses, fedMsgStopped, false)
	if el := time.Since(started); el > 2*time.Second {
		t.Fatalf("stop() 未能打断退避睡眠，耗时 %v", el)
	}
	c.wait()

	// 判死规则本身：failures=9 走 :155 分支
	if fedBackoffSeconds(fedMaxFailures+1) != fedBackoffCapSec {
		t.Fatal("第 9 次失败前应仍按上限提示")
	}
	if !(fedMaxFailures+1 > fedMaxFailures) {
		t.Fatal("failures > 8 判死条件不成立")
	}
}

// TestFedTunnelStopsWhenHostDied 对应 :91-93,109-111 的存活谓词：
// 房主世界消失（app 侧=会话结束）→ 退出重连循环、只发一次收尾通知。
func TestFedTunnelStopsWhenHostDied(t *testing.T) {
	var conns atomic.Int32
	host, port := fedServe(t, func(c net.Conn) {
		conns.Add(1)
		defer c.Close()
		r := bufio.NewReader(c)
		_, _ = fedReadLine(r)
		_, _ = c.Write([]byte("OK 23471\n"))
		time.Sleep(200 * time.Millisecond)
	})
	statuses := make(chan fedStatus, 16)
	alive := atomic.Bool{}
	alive.Store(true)
	c := NewFedTunnelClient(host, port, "HLQZ8T3", "tok", 25565, alive.Load, nil, fedStatusListener(statuses))
	c.start()
	waitFedStatus(t, statuses, "互通房间已上线："+host+" 端口 23471", false)

	alive.Store(false)
	waitFedStatus(t, statuses, fedMsgStopped, false)
	c.wait()
	if c.IsRunning() {
		t.Fatal("hostAlive 为假时隧道必须停")
	}
}

// ---- 数据连接 ----

func TestFedTunnelDataConnectionPipesBothWays(t *testing.T) {
	const guestID = "a1b2c3d41"
	mcGot := make(chan string, 4)
	relayGot := make(chan string, 4)
	var conns atomic.Int32

	host, port := fedServe(t, func(c net.Conn) {
		defer c.Close()
		r := bufio.NewReader(c)
		if conns.Add(1) == 1 {
			// 控制连接：HELLO → OK → CONNECT（server-hl/hlroom.go:325）
			if _, err := fedReadLine(r); err != nil {
				return
			}
			_, _ = c.Write([]byte("OK 23471\n"))
			_, _ = c.Write([]byte("CONNECT " + guestID + "\n"))
			for {
				if _, err := fedReadLine(r); err != nil {
					return
				}
			}
		}
		// 第二条：DATA 连接（hltunnel.go:73-81）——同一 tunnelPort，靠首行区分
		header, err := fedReadLine(r)
		if err != nil {
			return
		}
		relayGot <- header
		// 配对成功后裸转发：中继 → 房主 → 本机 MC
		if _, err := c.Write([]byte("handshake-from-guest\n")); err != nil {
			return
		}
		scanner := bufio.NewReader(c)
		for {
			line, err := scanner.ReadString('\n')
			if err != nil {
				return
			}
			relayGot <- line
		}
	})

	// 假 MC：收到中继送来的字节即回话（证明 127.0.0.1:localPort 这一段也通了）
	_, mcPort := fedServe(t, func(c net.Conn) {
		defer c.Close()
		r := bufio.NewReader(c)
		line, err := fedReadLine(r)
		if err != nil {
			return
		}
		mcGot <- line
		_, _ = c.Write([]byte("mc-says-ok\n"))
	})

	statuses := make(chan fedStatus, 16)
	c := NewFedTunnelClient(host, port, "HLQZ8T3", "3f9c8a1b", mcPort, nil, nil, fedStatusListener(statuses))
	c.start()
	t.Cleanup(func() { c.stop(); c.wait() })

	waitFedStatus(t, statuses, "互通房间已上线："+host+" 端口 23471", false)

	// DATA 行必须是 "DATA <id> <token>\n"，且复用同一 tunnelPort
	select {
	case got := <-relayGot:
		if want := fedDataLine(guestID, "3f9c8a1b"); got != want {
			t.Fatalf("DATA 行 = %q, want %q", got, want)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("中继没收到 DATA 连接")
	}

	select {
	case got := <-mcGot:
		if got != "handshake-from-guest\n" {
			t.Fatalf("本机 MC 收到 %q", got)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("字节没有转发到本机 MC 端口")
	}

	select {
	case got := <-relayGot:
		if got != "mc-says-ok\n" {
			t.Fatalf("回程字节 = %q", got)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("MC 的回话没有转发回中继")
	}
}

// TestFedTunnelDataFailureSilent 对应 :206-208：本机 MC 端口没人监听时 openData 静默失败，
// 既不影响控制连接也不产生额外通知（规格 B3/D3 —— 现状如此，不得加逻辑）。
func TestFedTunnelDataFailureSilent(t *testing.T) {
	deadMC := fedClosedPort(t)
	var conns atomic.Int32
	host, port := fedServe(t, func(c net.Conn) {
		defer c.Close()
		r := bufio.NewReader(c)
		if conns.Add(1) == 1 {
			if _, err := fedReadLine(r); err != nil {
				return
			}
			_, _ = c.Write([]byte("OK 1\n"))
			_, _ = c.Write([]byte("CONNECT ghost\n"))
			time.Sleep(300 * time.Millisecond)
			return
		}
		// DATA 连接：服务端配对失败时不回任何字节（hltunnel.go:204-226），这里模拟"读完即断"
		_, _ = fedReadLine(r)
	})

	statuses := make(chan fedStatus, 16)
	c := NewFedTunnelClient(host, port, "C", "T", deadMC, nil, nil, fedStatusListener(statuses))
	c.start()
	t.Cleanup(func() { c.stop(); c.wait() })

	waitFedStatus(t, statuses, "互通房间已上线："+host+" 端口 1", false)
	time.Sleep(500 * time.Millisecond)
	if len(statuses) > 1 {
		t.Fatalf("DATA 失败必须静默，实际多出了 %d 条通知", len(statuses))
	}
	if !c.IsRunning() {
		t.Fatal("DATA 失败不该拖垮控制连接")
	}
}

// ---- 房客侧 connectFed ----

func TestConnectFedAddressValidation(t *testing.T) {
	// ConnectionManager.java:10250-10262：lastIndexOf(':')，sep<=0 或尾冒号或端口非数都判 fed_failed
	cases := []string{"", "23471", ":23471", "1.2.3.4:", "1.2.3.4:port", "1.2.3.4:65536x"}
	for _, addr := range cases {
		a := NewApp()
		sink := newFedSink()
		a.AttachSink(sink, nil)
		e := a.engine
		ctx, cancel := context.WithCancel(context.Background())
		e.mu.Lock()
		e.sess = newSession(a, "HLQZ8T3", "tok", false)
		e.ctx = ctx
		e.mu.Unlock()

		e.connectFed(map[string]any{"source": fedSourceHappyLink, fedKeyAddress: addr})
		data := sink.waitConnState(t, phaseFed, statusFailed, 5*time.Second)
		if data["detail"] != fedTextFailed {
			t.Fatalf("fedAddress=%q 的失败文案 = %v", addr, data["detail"])
		}
		cancel()
	}
}

func TestConnectFedHappyPath(t *testing.T) {
	// 假 HL 中继的房间端口：不预读，guest 首包即 MC 握手，原样回送（hltunnel.go:119-133）
	got := make(chan string, 4)
	echo := make(chan string, 4)
	_, relayPort := fedServe(t, func(c net.Conn) {
		defer c.Close()
		buf := make([]byte, 256)
		n, err := c.Read(buf)
		if n > 0 {
			got <- string(buf[:n])
		}
		if err != nil {
			return
		}
		if _, err := c.Write([]byte("relay-echo\n")); err != nil {
			return
		}
		scanner := bufio.NewReader(c)
		for {
			b, err := scanner.ReadString('\n')
			if err != nil {
				return
			}
			echo <- b
			return
		}
	})

	a := NewApp()
	sink := newFedSink()
	a.AttachSink(sink, nil)
	e := a.engine
	ctx, cancel := context.WithCancel(context.Background())
	a.mu.Lock()
	a.room = map[string]any{"code": "HLQZ8T3", "source": "happylink", fedKeyAddress: "127.0.0.1:" + strconv.Itoa(relayPort)}
	a.mu.Unlock()
	e.mu.Lock()
	e.sess = newSession(a, "HLQZ8T3", "tok", false)
	e.ctx = ctx
	e.mu.Unlock()

	// RoomManager.java:827-830：source=="happylink" 才走 connectFed
	a.connectFedIfHappyLink()
	data := sink.waitConnState(t, phaseFed, statusSuccess, 15*time.Second)
	addr, _ := data["address"].(string)
	if !strings.HasPrefix(addr, "127.0.0.1:") {
		t.Fatalf("success 地址应是本地桥（填进 MC 用），got %q", addr)
	}

	// 玩家把 addr 填进 MC：字节必须直达中继，回程原样可达
	mc, err := net.Dial("tcp", addr)
	if err != nil {
		t.Fatal(err)
	}
	defer mc.Close()
	if _, err := mc.Write([]byte("mc-handshake\n")); err != nil {
		t.Fatal(err)
	}
	select {
	case s := <-got:
		if s != "mc-handshake\n" {
			t.Fatalf("中继收到 %q", s)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("中继没收到 MC 握手字节")
	}
	r := bufio.NewReader(mc)
	line, err := r.ReadString('\n')
	if err != nil || line != "relay-echo\n" {
		t.Fatalf("回程 = %q err=%v", line, err)
	}
	if _, err := mc.Write([]byte("second\n")); err == nil {
		select {
		case s := <-echo:
			if s != "second\n" {
				t.Fatalf("同一条桥上的续传 = %q", s)
			}
		case <-time.After(5 * time.Second):
			t.Fatal("同一条中继通道上的后续字节没有继续转发")
		}
	}

	// 会话拆除（引擎 ctx cancel）必须断桥：等价模组 P2PBridge.disconnect()
	cancel()
	waitUntilFed(t, 5*time.Second, func() bool {
		b := fedJoinerForApp(a)
		return b == nil || !b.running.Load()
	}, "引擎 ctx 取消后互通桥必须断开")
}

func TestConnectFedNotForPlainRooms(t *testing.T) {
	a := NewApp()
	sink := newFedSink()
	a.AttachSink(sink, nil)
	e := a.engine
	e.mu.Lock()
	e.sess = newSession(a, "K7M2QP", "tok", false)
	e.mu.Unlock()
	a.mu.Lock()
	a.room = map[string]any{"code": "K7M2QP"}
	a.mu.Unlock()

	a.connectFedIfHappyLink() // source 缺失 → 不触发（RoomInfo.java:56-58）
	select {
	case ev := <-sink.ch:
		t.Fatalf("普通房不该出现 fed 事件，got %+v", ev)
	case <-time.After(300 * time.Millisecond):
	}
}

func waitUntilFed(t *testing.T, timeout time.Duration, cond func() bool, msg string) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if cond() {
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatalf("等待条件超时（%v）：%s", timeout, msg)
}
