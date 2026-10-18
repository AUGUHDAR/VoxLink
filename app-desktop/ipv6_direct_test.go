package main

// ipv6_direct_test.go — 直连兜底链自检。
//
// 覆盖：常量、Java 异常→Go 错误的映射表、loopback 上的成功/拒连分支、
// 空地址与取消的短路、TCP 同时开放的 5 次上限、GUA 提取的形状约束、
// IPv6 可达性的 60s 缓存分支（**不发真外网包**），以及本模块日志字面量的消敏自检。
//
// 覆盖不到（要真网络/真双栈链路）：GUA 是否真能被外网回连、NoRouteToHost 与
// "network is unreachable" 在各平台的分流、SimOpen 在真实 NAT 下的成功率与 SO_REUSEADDR 缺失的影响。

import (
	"errors"
	"net"
	"strings"
	"sync"
	"testing"
	"time"
)

// TestFallbackConstants 与 ConnectionFallback.java 逐条对齐。
func TestFallbackConstants(t *testing.T) {
	cases := []struct {
		name string
		got  any
		want any
	}{
		{"SOCKET_TIMEOUT", fbSocketTimeout, 3000 * time.Millisecond},             // :30
		{"SOCKET_CONNECT_TIMEOUT_MS", fbConnectTimeout, 2000 * time.Millisecond}, // :31
		{"TCP_SIMOPEN_WINDOW_MS", fbSimOpenWindow, 10 * time.Second},             // :32
		{"TCP_SIMOPEN_MAX_ATTEMPTS", fbSimOpenMaxAttempts, 5},                    // :33
		{"IPV6_CHECK_CACHE_MS", fbIPv6CheckCache, 60 * time.Second},              // :40
		{"探测端口", fbDNSServerPort, 53},                                            // :209
		{"shutdown 等待", fbShutdownWait, 2 * time.Second},                         // :46
	}
	for _, c := range cases {
		if c.got != c.want {
			t.Errorf("%s = %v, want %v", c.name, c.got, c.want)
		}
	}
	// :205 两个目标与顺序
	if len(fbIPv6TestTargets) != 2 || fbIPv6TestTargets[0] != "2001:4860:4860::8888" || fbIPv6TestTargets[1] != "2001:4860:4860::8844" {
		t.Errorf("IPv6 探测目标不符: %v", fbIPv6TestTargets)
	}
	for _, s := range fbIPv6TestTargets {
		if ip := net.ParseIP(s); ip == nil || ip.To4() != nil {
			t.Errorf("探测目标必须是 IPv6 字面量: %q", s)
		}
	}
}

// TestFallbackStatusTextKeys 状态文案与模组 lang 键原文一致（走 Component.translatable 的位置）。
func TestFallbackStatusTextKeys(t *testing.T) {
	// 只断言"四条都存在且互不相同、初值为空"，具体汉字由 lang 文件负责（不在此测试里硬绑）
	texts := []string{txtConnProbing, txtConnBridgeSetup, txtConnTimeout, txtConnFailed}
	seen := map[string]bool{}
	for _, x := range texts {
		if x == "" {
			t.Error("状态文案不应为空串")
		}
		if seen[x] {
			t.Errorf("状态文案重复: %q", x)
		}
		seen[x] = true
	}
	f := fbNew()
	if f.fbStatusText() != txtConnEmpty { // :37 Component.empty()
		t.Errorf("初值应为 empty，得 %q", f.fbStatusText())
	}
}

// TestFallbackModeTranslationKeys enum ConnectionMode 的键（:448-458）。
func TestFallbackModeTranslationKeys(t *testing.T) {
	want := map[fbConnectionMode]string{
		fbModeIPv6Direct: "voxlink.mode.ipv6_direct",
		fbModeIPv4Direct: "voxlink.mode.ipv4_direct",
		fbModeUDPPunch:   "voxlink.mode.udp_punch",
		fbModeNone:       "",
	}
	for m, k := range want {
		if got := fbModeTranslationKey(m); got != k {
			t.Errorf("mode=%d 键=%q want %q", m, got, k)
		}
	}
}

type fakeTimeoutErr struct{}

func (fakeTimeoutErr) Error() string   { return "dial tcp: i/o timeout" }
func (fakeTimeoutErr) Timeout() bool   { return true }
func (fakeTimeoutErr) Temporary() bool { return true }

// TestClassifyDialError Java 四类异常 → Go 错误的分流表。
func TestClassifyDialError(t *testing.T) {
	cases := []struct {
		name string
		err  error
		want dialErrorKind
	}{
		{"SocketTimeoutException", fakeTimeoutErr{}, dialTimeout}, // :93
		{"NoRouteToHostException(Linux)", errors.New("connect: no route to host"), dialNoRoute},
		{"NoRouteToHostException(Windows)", errors.New("No route to host"), dialNoRoute},
		{"ConnectException refused", &net.OpError{Op: "dial", Err: errors.New("connection refused")}, dialRefused},
		{"ConnectException refused(Windows)", &net.OpError{Op: "dial", Err: errors.New("No connection could be made because the target machine actively refused it.")}, dialRefused},
		{"ConnectException other", &net.OpError{Op: "dial", Err: errors.New("socket permission denied")}, dialConnectErr},
		{"IOException DNS", &net.DNSError{Err: "no such host", Name: "a.example"}, dialUnknown},
		{"IOException other", errors.New("broken pipe"), dialUnknown},
		{"nil", nil, dialUnknown},
	}
	for _, c := range cases {
		got, _ := classifyDialError(c.err)
		if got != c.want {
			t.Errorf("%s: got %v want %v", c.name, got, c.want)
		}
	}
	if dialKindName(dialRefused) != "refused" || dialKindName(dialUnknown) != "exception" {
		t.Error("dialKindName 名称不符")
	}
}

// TestIsBindError 本地口被占的判据（SimOpen 的随机口退让分支）。
func TestIsBindError(t *testing.T) {
	if !isBindError(errors.New("bind: address already in use")) {
		t.Error("类 Unix EADDRINUSE 应判 bind 失败")
	}
	if !isBindError(errors.New("bind: Only one usage of each socket address (protocol/network address/port) is normally permitted.")) {
		t.Error("Windows WSAEADDRINUSE 应判 bind 失败")
	}
	if isBindError(nil) || isBindError(errors.New("connect: connection refused")) {
		t.Error("非 bind 类错误不应误判")
	}
}

// TestTryIpv4DirectSuccessOnLoopback loopback 上打通：成功分支的 won/settled/状态三步（:140-153）。
func TestTryIpv4DirectSuccessOnLoopback(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Skipf("loopback 监听不可用: %v", err)
	}
	defer ln.Close()
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			_ = c.Close()
		}
	}()
	port := ln.Addr().(*net.TCPAddr).Port

	f := fbNew()
	res := <-f.tryIpv4Direct("127.0.0.1", port)
	if !res.success {
		t.Fatalf("loopback 直连应成功，得 %+v", res)
	}
	if res.label != "IPv4" || res.mode != fbModeIPv4Direct || res.remotePort != port { // :153
		t.Errorf("结果字段不符: %+v", res)
	}
	if f.fbStatusText() != txtConnBridgeSetup { // :147
		t.Errorf("成功后状态文案 = %q", f.fbStatusText())
	}
	if !f.isSettled() || f.isCancelled() { // :152
		t.Error("成功应置 settled")
	}
	// 第二条路再来一次：模组里每路各自 new，won 只属于本路；这里验 won 已占位时返回 cancelled
	res2 := <-f.tryIpv4Direct("127.0.0.1", port)
	if res2.success || res2.errorCode != "CANCELLED" { // :75/:136 → :185
		t.Errorf("本路已赢后再走应 cancelled，得 %+v", res2)
	}
}

// TestTryIpv4DirectRefusedOnLoopback 端口没人听 → REFUSED（:162-167）。
func TestTryIpv4DirectRefusedOnLoopback(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Skipf("loopback 监听不可用: %v", err)
	}
	port := ln.Addr().(*net.TCPAddr).Port
	_ = ln.Close() // 立刻关：之后的 connect 必然被拒

	f := fbNew()
	res := <-f.tryIpv4Direct("127.0.0.1", port)
	if res.success {
		t.Fatalf("无人监听的端口不应成功: %+v", res)
	}
	if res.errorCode != "IPV4_REFUSED" { // Windows/Linux/macOS 的两种文案都已在 classifyDialError 覆盖
		t.Errorf("期望 IPV4_REFUSED，实得 %q（reason=%q）", res.errorCode, res.failureReason)
	}
	if f.fbStatusText() != txtConnFailed { // :166
		t.Errorf("失败状态文案 = %q", f.fbStatusText())
	}
	if f.isSettled() {
		t.Error("失败不应置 settled")
	}
}

// TestTryDirectEmptyHost 空地址短路（:128 / :189），且渠道必须是"已关闭且只有一个值"。
func TestTryDirectEmptyHost(t *testing.T) {
	f := fbNew()
	ch := f.tryIpv6Direct("", 25565) // 同一个渠道里读完两个返回值：再调一次是新渠道，测不出关闭语义
	v, ok := <-ch
	if !ok || v.errorCode != "NO_IPV6" || v.failureReason != "没有IPv6地址" {
		t.Errorf("IPv6 空地址 %+v ok=%v", v, ok)
	}
	if _, more := <-ch; more {
		t.Error("channel 应已关闭")
	}
	f2 := fbNew()
	v2, ok2 := <-f2.tryIpv4Direct("", 25565)
	if !ok2 || v2.errorCode != "NO_IPV4" || v2.failureReason != "没有IPv4地址" {
		t.Errorf("IPv4 空地址 %+v ok=%v", v2, ok2)
	}
	// 空地址路径不得把状态改成 probing（模组在 if 之外才 setStatus）
	if f.fbStatusText() != txtConnEmpty || f2.fbStatusText() != txtConnEmpty {
		t.Error("空地址时不应进入 probing 状态")
	}
}

// TestFallbackCancelBeforeAttempt cancel() 之后三路都应 cancelled（:55-57 + :75/:136/:307）。
func TestFallbackCancelBeforeAttempt(t *testing.T) {
	f := fbNew()
	f.cancel()
	if !f.isCancelled() {
		t.Error("cancel 位未置")
	}
	for name, ch := range map[string]<-chan fbConnectResult{
		"ipv4": f.tryIpv4Direct("127.0.0.1", 25565),
		"ipv6": f.tryIpv6Direct("2001:db8::1", 25565), // 文档地址，且被 cancelled 短路，不会真发出去
		"sim":  f.tryTcpSimultaneousOpen("127.0.0.1", 25565, 0),
	} {
		res := <-ch
		if res.errorCode != "CANCELLED" || res.success {
			t.Errorf("%s 分支应 cancelled，得 %+v", name, res)
		}
	}
}

// TestTryTcpSimultaneousOpenShortCircuits 空地址与坏地址两条短路（:410 / :312）。
func TestTryTcpSimultaneousOpenShortCircuits(t *testing.T) {
	f := fbNew()
	res := <-f.tryTcpSimultaneousOpen("", 25565, 0)
	if res.errorCode != "NO_IP" || res.failureReason != "No remote IP" {
		t.Errorf("空 remoteIp: %+v", res)
	}
	f2 := fbNew()
	res2 := <-f2.tryTcpSimultaneousOpen("not-an-ip", 25565, 0)
	if res2.errorCode != "TCP_SIMOPEN_FAILED" || !strings.HasPrefix(res2.failureReason, "Bad addr: ") {
		t.Errorf("坏地址: %+v", res2)
	}
}

// TestTryTcpSimultaneousOpenAttemptCap 无人监听时打满 5 次即止（:320/:388/:402）。
func TestTryTcpSimultaneousOpenAttemptCap(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Skipf("loopback 监听不可用: %v", err)
	}
	port := ln.Addr().(*net.TCPAddr).Port
	_ = ln.Close()

	f := fbNew()
	start := time.Now()
	res := <-f.tryTcpSimultaneousOpen("127.0.0.1", port, 0)
	if res.success || res.errorCode != "TCP_SIMOPEN_FAILED" {
		t.Fatalf("应失败收口，得 %+v", res)
	}
	if !strings.Contains(res.failureReason, "after 5 attempts") { // :402 的次数串
		t.Errorf("尝试次数记账不符: %q", res.failureReason)
	}
	if d := time.Since(start); d > 5*time.Second {
		t.Errorf("5 次上限应远早于 10s 窗口结束，实耗 %v", d)
	}
	if f.fbStatusText() != txtConnFailed { // :401
		t.Errorf("SimOpen 失败后状态 = %q", f.fbStatusText())
	}
}

// TestFallbackIPv6ScanShape 本机地址扫描的**形状**约束（不断言环境结果）。
// 真能否连通需要真双栈网络，这里只保证判据不误收链路本地/ULA/带 zone 的地址。
func TestFallbackIPv6ScanShape(t *testing.T) {
	gua := fbGetLocalGlobalIPv6()
	if gua != "" {
		ip := net.ParseIP(gua)
		if ip == nil || ip.To4() != nil {
			t.Errorf("getLocalGlobalIPv6 应返回纯 IPv6，得 %q", gua)
		}
		if strings.Contains(gua, "%") { // :238-242 必须已去掉 zone
			t.Errorf("不应带 zone 后缀: %q", gua)
		}
		if strings.HasPrefix(gua, "fd") || strings.HasPrefix(gua, "fc") { // :254/:289 排除 ULA
			t.Errorf("不应返回 ULA: %q", gua)
		}
		if ip.IsLinkLocalUnicast() { // fe80::/10
			t.Errorf("不应返回链路本地地址: %q", gua)
		}
	}
	_ = fbHasIPv6Connectivity() // 只要求不 panic、返回布尔
}

// TestStripIPv6Zone 去 zone 的截断口径。
func TestStripIPv6Zone(t *testing.T) {
	if got := stripIPv6Zone("fe80::1%eth0"); got != "fe80::1" {
		t.Errorf("strip = %q", got)
	}
	if got := stripIPv6Zone("2001:db8::1"); got != "2001:db8::1" {
		t.Errorf("无 zone 时不应改动: %q", got)
	}
}

// TestVerifyIPv6ConnectivityCacheBranch 60s 缓存分支（:195-197）：
// 命中缓存时**绝不**外拨，因此这条测试不产生任何真外网连接。
func TestVerifyIPv6ConnectivityCacheBranch(t *testing.T) {
	restoreFallbackIPv6Cache(t)

	tr := true
	fa := false
	now := time.Now().UnixMilli()
	fbIPv6Mu.Lock()
	fbIPv6Cached, fbIPv6CachedMs = &tr, now
	fbIPv6Mu.Unlock()
	if !fbVerifyIPv6Connectivity() {
		t.Error("缓存为 true 时应直接返回 true")
	}
	fbIPv6Mu.Lock()
	fbIPv6Cached, fbIPv6CachedMs = &fa, now
	fbIPv6Mu.Unlock()
	if fbVerifyIPv6Connectivity() {
		t.Error("缓存为 false 时应直接返回 false")
	}

	// 过期后必须重算（重算会走 hasIPv6Connectivity，不外拨则不外拨；
	// 为不产生外网流量，这里只验证"缓存过期判据本身"）
	fbIPv6Mu.Lock()
	fbIPv6Cached, fbIPv6CachedMs = &tr, now-fbIPv6CheckCache.Milliseconds()-1
	fbIPv6Mu.Unlock()
	if now-fbIPv6CachedMs < fbIPv6CheckCache.Milliseconds() {
		t.Error("过期判据算反了")
	}
}

func restoreFallbackIPv6Cache(t *testing.T) {
	t.Helper()
	fbIPv6Mu.Lock()
	oldV, oldMs := fbIPv6Cached, fbIPv6CachedMs
	fbIPv6Mu.Unlock()
	t.Cleanup(func() {
		fbIPv6Mu.Lock()
		fbIPv6Cached, fbIPv6CachedMs = oldV, oldMs
		fbIPv6Mu.Unlock()
	})
}

// TestFbInFlightAccounting 在途计数随尝试增减（shutdown 等 2s 的地基）。
func TestFbInFlightAccounting(t *testing.T) {
	base := fbInFlight.Load()
	f := fbNew()
	// 空地址路径不起 goroutine，不该计数
	<-f.tryIpv4Direct("", 25565)
	if fbInFlight.Load() != base {
		t.Errorf("空地址路径不应计数: %d → %d", base, fbInFlight.Load())
	}
	ch := f.tryIpv4Direct("127.0.0.1", 25565)
	<-ch
	// goroutine 收尾与 close 同批 defer，收到结果后计数可能还差一瞬，给它一次让出机会
	deadline := time.Now().Add(time.Second)
	for fbInFlight.Load() != base && time.Now().Before(deadline) {
		time.Sleep(5 * time.Millisecond)
	}
	if fbInFlight.Load() != base {
		t.Errorf("尝试收口后计数应回到基线 %d，实为 %d", base, fbInFlight.Load())
	}
}

// TestFbShutdownBounded fbShutdown 必须在 2s 上限内返回（:46 awaitTermination）。
func TestFbShutdownBounded(t *testing.T) {
	restoreFallbackIPv6Cache(t)
	start := time.Now()
	fbShutdown()
	if d := time.Since(start); d > 3*time.Second {
		t.Errorf("停机耗时 %v，超过 awaitTermination(2s) 的量级", d)
	}
	fbIPv6Mu.Lock()
	cleared := fbIPv6Cached == nil
	fbIPv6Mu.Unlock()
	if !cleared {
		t.Error("停机应清掉 IPv6 可达性缓存")
	}
}

// TestFallbackLogLiteralsSurviveSanitizer 本模块日志字面量的消敏自检。
// 模组在这些位置打了 host:port / [IPv6]:port（:85/:94/:103/:112/:146/:155/:164/:173/:356/:359/:376），
// 本端全部换成标识串；接线到 netLog 后必须过同一套正则。
func TestFallbackLogLiteralsSurviveSanitizer(t *testing.T) {
	literals := []string{
		"fallback_direct_ok kind=IPV6",
		"fallback_direct_ok kind=IPV4",
		"fallback_direct_timeout kind=IPV6",
		"fallback_direct_no_route kind=IPV4",
		"fallback_direct_refused kind=IPV4",
		"fallback_direct_connect_failed kind=IPV6",
		"fallback_direct_exception kind=IPV4",
		"fallback_simopen_connected attempt=1",
		"fallback_simopen_local_port_occupied_use_random_port",
		"fallback_simopen_bind_fail",
		"fallback_simopen_attempt_failed attempt=5 kind=refused",
		"ipv6_connectivity_check_ok",
		"ipv6_connectivity_check_failed",
		"ipv6_connectivity_check_all_failed",
		"ipv6_usable_global_address_found",
		"ipv6_no_usable_global_address",
		"ipv6_check_error err=argument list too long",
	}
	for _, m := range literals {
		clean := sanitizeNetLogMessage(m)
		assertNoSensitive(t, m, clean)
		if clean != m {
			t.Errorf("字面量不该被消敏改写（写法越界）: %q → %q", m, clean)
		}
	}
	// 带错误消息的那条：错误串里混进地址时必须被兜底消掉
	dirty := "ipv6_check_error err=dial tcp 192.0.2.7:53: connect: no route to host"
	assertNoSensitive(t, dirty, sanitizeNetLogMessage(dirty))
}

// TestFallbackSinkWiring fbLog 注入的 sink 收到原始标识串。
func TestFallbackSinkWiring(t *testing.T) {
	var mu sync.Mutex
	var got []string
	fbLogSink = func(level, msg string) {
		mu.Lock()
		got = append(got, level+"|"+msg)
		mu.Unlock()
	}
	t.Cleanup(func() { fbLogSink = nil })
	fbLog("info", "fallback_direct_ok kind=IPV6")
	mu.Lock()
	defer mu.Unlock()
	if len(got) != 1 || got[0] != "info|fallback_direct_ok kind=IPV6" {
		t.Errorf("sink 收到 %v", got)
	}
}
