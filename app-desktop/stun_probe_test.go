package main

// stun_probe_test.go — stun_probe.go 的离线校验（只打 127.0.0.1，不碰真实 STUN）。
//
// 覆盖点：
//   - parseStunUrl 的分支语义（含裸 IPv6 判非法、默认端口 3478、括号 IPv6 带端口）
//   - resendDelay 的 RTO/抖动区间（200<<n 上限 800、±20%）
//   - NatType 的 10 身份 / 7 key 重复关系与按身份判定的谓词（合并身份就会错）
//   - 单/双/竞速/四路探测对本地伪 STUN 服务器的行为（结果下标对齐 vs 紧凑存放）
//   - samplePortsSequential 的"先竞速选台、再对同一台顺序采样"
//   - stunUrlsProbedFirst 的可达前置重排

import (
	"encoding/binary"
	"net"
	"testing"
	"time"
)

// stunTestBuildResponse 按 RFC 手算一条 Binding Success Response（XOR-MAPPED-ADDRESS）。
// 端口域 = port ^ 0x2112，IPv4 域 = ip ^ stunMagicCookie —— 与 StunProbe.java 的
// parseXorMappedAddress（:1248/:1255）同式，cookie 只从 stun.go 取。
func stunTestBuildResponse(req []byte, ip string, port int) []byte {
	v4 := net.ParseIP(ip).To4()
	ipUint := binary.BigEndian.Uint32(v4)
	resp := make([]byte, 32)
	binary.BigEndian.PutUint16(resp[0:2], uint16(stunRespType))
	binary.BigEndian.PutUint16(resp[2:4], 12)
	binary.BigEndian.PutUint32(resp[4:8], stunMagicCookie)
	copy(resp[8:20], req[8:20])
	resp[20] = 0x00
	resp[21] = 0x20 // XOR-MAPPED-ADDRESS
	resp[22] = 0x00
	resp[23] = 0x08
	resp[24] = 0x00
	resp[25] = 0x01 // family IPv4
	binary.BigEndian.PutUint16(resp[26:28], uint16(port^int(stunMagicCookie>>16)))
	binary.BigEndian.PutUint32(resp[28:32], ipUint^stunMagicCookie)
	return resp
}

// stunTestServer 起一个只答 Binding Request 的本地伪 STUN，返回 "stun:127.0.0.1:<port>"。
// mappedIP/mappedPort 是它**上报**的映射地址（与监听端口无关，便于断言下标对齐）。
func stunTestServer(t *testing.T, mappedIP string, mappedPort int) string {
	t.Helper()
	uc, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 0})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = uc.Close() })
	go func() {
		buf := make([]byte, 576)
		for {
			n, from, err := uc.ReadFromUDP(buf)
			if err != nil {
				return
			}
			if n < 20 || binary.BigEndian.Uint16(buf[0:2]) != stunReqType {
				continue
			}
			resp := stunTestBuildResponse(buf[:n], mappedIP, mappedPort)
			_, _ = uc.WriteToUDP(resp, from)
		}
	}()
	la := uc.LocalAddr().(*net.UDPAddr)
	return "stun:127.0.0.1:" + itoa(la.Port)
}

func TestStunParseURLBranches(t *testing.T) {
	cases := []struct {
		in     string
		host   string
		port   int
		wantOK bool
	}{
		{"stun:stun.miwifi.com", "stun.miwifi.com", 3478, true},            // :1345 无端口 → 默认 3478
		{"stun:stun.l.google.com:19302", "stun.l.google.com", 19302, true}, // :1334-1338
		{"stuns:example.org:5349", "example.org", 5349, true},              // stuns: 前缀同样剥掉（:1307）
		{"stun:127.0.0.1:1", "127.0.0.1", 1, true},
		{"stun:[2001:db8::1]", "2001:db8::1", 3478, true},        // 括号形态无端口 → 3478（:1320）
		{"stun:[2001:db8::1]:19302", "2001:db8::1", 19302, true}, // 括号带端口（:1313-1320）
		{"stun:2001:db8::1", "", 0, false},                       // 裸 IPv6：主机段仍含冒号 → 判歧义丢弃（:1329-1331）
	}
	for _, c := range cases {
		got, ok := stunParseURL(c.in)
		if ok != c.wantOK {
			t.Fatalf("%s: ok=%v，期望 %v（got %+v）", c.in, ok, c.wantOK, got)
		}
		if !c.wantOK {
			continue
		}
		if got.host != c.host || got.port != c.port {
			t.Fatalf("%s: 解析成 %s:%d，期望 %s:%d", c.in, got.host, got.port, c.host, c.port)
		}
	}
}

func TestStunResendDelayRanges(t *testing.T) {
	// rto=200<<n 上限 800；jitter=(int)(rto*0.2)；返回 rto-jitter + [0, 2*jitter]
	for _, c := range []struct {
		n        int
		min, max int
	}{
		{1, 320, 480}, // rto=400, jitter=80
		{2, 640, 960}, // rto=800（1600 被上限截），jitter=160
		{3, 640, 960}, // 再翻倍仍被 800 截住（:496 的 min）
	} {
		for i := 0; i < 200; i++ {
			d := stunResendDelay(c.n)
			if d < c.min || d > c.max {
				t.Fatalf("resendDelay(%d)=%d 落在 [%d,%d] 之外", c.n, d, c.min, c.max)
			}
		}
	}
}

func TestStunNatTypeIdentitiesAndKeys(t *testing.T) {
	// 10 身份 / 7 key：重复 key 必须保留（源码就是这么写的），谓词按身份而非 key
	if stunNatTypeKeys[natTypeEasySymInc] != stunNatTypeKeys[natTypeSymmetricEasyInc] {
		t.Fatal("EASY_SYM_INC 与 SYMMETRIC_EASY_INC 的 key 必须同为 symmetric_easy_inc（:1556/:1559）")
	}
	if stunNatTypeKeys[natTypeHardSym] != stunNatTypeKeys[natTypeSymmetric] {
		t.Fatal("HARD_SYM 与 SYMMETRIC 的 key 必须同为 symmetric（:1558/:1561）")
	}
	if !natTypeHardSym.hardSymmetric() || natTypeHardSym.canHolePunch() {
		t.Fatal("HARD_SYM 应判硬对称且不可打洞（:1582/:1586）")
	}
	if !natTypeEasySymInc.easySymmetric() || !natTypeEasySymInc.canHolePunch() {
		t.Fatal("EASY_SYM_INC 应判易对称且可打洞（:1578/:1586）")
	}
	if natTypeSymmetricEasyInc.canHolePunch() == false {
		t.Fatal("SYMMETRIC_EASY_INC 不在 canHolePunch 的排除名单里（:1586 只排 SYMMETRIC/HARD_SYM）")
	}
	// parseNatType 按声明序首个匹配（:602-610）
	if got := stunNatTypeFromKey("symmetric_easy_inc"); got != natTypeSymmetricEasyInc {
		t.Fatalf("parseNatType(symmetric_easy_inc) = %v，期望声明序在前的 SYMMETRIC_EASY_INC", got)
	}
	if got := stunNatTypeFromKey("symmetric"); got != natTypeSymmetric {
		t.Fatalf("parseNatType(symmetric) = %v，期望 SYMMETRIC", got)
	}
	if got := stunNatTypeFromKey("别的什么"); got != natTypeUnknown {
		t.Fatalf("未知串应回落 UNKNOWN，实得 %v", got)
	}
}

func TestNatClassFromStunNatTypeMatrix(t *testing.T) {
	cases := map[stunNatType]natClass{
		natTypeFullCone:           natCone,
		natTypeRestrictedCone:     natCone,
		natTypePortRestrictedCone: natCone,
		natTypeSymmetricEasyInc:   natEasySym,
		natTypeSymmetricEasyDec:   natEasySym,
		natTypeEasySymInc:         natEasySym,
		natTypeEasySymDec:         natEasySym,
		natTypeSymmetric:          natHardSym,
		natTypeHardSym:            natHardSym,
		natTypeUnknown:            natUnknown,
	}
	for in, want := range cases {
		if got := natClassFromStunNatType(in); got != want {
			t.Fatalf("%v → %v，期望 %v（NatClass.java:13-34）", in, got, want)
		}
	}
}

func TestStunDiscoverMappedAddressSingle(t *testing.T) {
	srv := stunTestServer(t, "203.0.113.9", 41000)
	uc := newLocalUDP(t)
	got := stunDiscoverMappedAddress(uc, []string{srv})
	if got == nil {
		t.Fatal("本地伪 STUN 有响应却返回 nil")
	}
	if got.IP != "203.0.113.9" || got.Port != 41000 {
		t.Fatalf("解出 %s:%d，期望 203.0.113.9:41000", got.IP, got.Port)
	}
}

func TestStunDiscoverMappedAddressDualKeepsIndexAlignment(t *testing.T) {
	srvA := stunTestServer(t, "203.0.113.1", 41001)
	srvB := stunTestServer(t, "203.0.113.2", 41002)
	uc := newLocalUDP(t)
	res := stunDiscoverMappedAddressDual(uc, srvA, srvB)
	if len(res) != 2 || res[0] == nil || res[1] == nil {
		t.Fatalf("双路结果 %+v，期望两项都在", res)
	}
	// Dual 与 Race 的区别就在这里：Dual 按入参下标对齐（:252/:261）
	if res[0].Port != 41001 || res[1].Port != 41002 {
		t.Fatalf("下标错位：[0]=%d [1]=%d", res[0].Port, res[1].Port)
	}
}

func TestStunDiscoverMappedAddressRaceCompactsResults(t *testing.T) {
	urls := []string{
		stunTestServer(t, "203.0.113.1", 42001),
		stunTestServer(t, "203.0.113.2", 42002),
		stunTestServer(t, "203.0.113.3", 42003),
	}
	uc := newLocalUDP(t)
	res := stunDiscoverMappedAddressRace(uc, urls, 2)
	if len(res) != 2 {
		t.Fatalf("长度 %d，期望 need=2", len(res))
	}
	// Race 紧凑存放到 results[got]（:351），不做下标对齐：前 need 项都应被填满
	if res[0] == nil || res[1] == nil {
		t.Fatalf("竞速结果有空位: %+v", res)
	}
	if res[0].Port == res[1].Port {
		t.Fatalf("两项端口相同（%d），说明只收到一台的应答", res[0].Port)
	}
	for _, r := range res {
		if r.IP != "203.0.113.1" && r.IP != "203.0.113.2" && r.IP != "203.0.113.3" {
			t.Fatalf("出现预期外的映射地址 %s", r.IP)
		}
	}
}

func TestStunDiscoverMappedAddressQuadAllFour(t *testing.T) {
	s0 := stunTestServer(t, "203.0.113.1", 43001)
	s1 := stunTestServer(t, "203.0.113.2", 43002)
	s2 := stunTestServer(t, "203.0.113.3", 43003)
	s3 := stunTestServer(t, "203.0.113.4", 43004)
	uc := newLocalUDP(t)
	res := stunDiscoverMappedAddressQuad(uc, s0, s1, s2, s3)
	want := []int{43001, 43002, 43003, 43004}
	for i, w := range want {
		if res[i] == nil {
			t.Fatalf("[%d] 为空，期望 %d（Quad 按服务器下标存放，:457）", i, w)
		}
		if res[i].Port != w {
			t.Fatalf("[%d]=%d，期望 %d", i, res[i].Port, w)
		}
	}
}

func TestStunDiscoverNoResponseReturnsNil(t *testing.T) {
	// 先占一个端口再关掉：没有任何 STUN 应答时，800ms 窗口后必须返回 nil（不伪造地址）
	probe, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 0})
	if err != nil {
		t.Fatal(err)
	}
	dead := "stun:127.0.0.1:" + itoa(probe.LocalAddr().(*net.UDPAddr).Port)
	_ = probe.Close()

	uc := newLocalUDP(t)
	start := time.Now()
	if got := stunDiscoverMappedAddress(uc, []string{dead}); got != nil {
		t.Fatalf("无应答却拿到 %+v", got)
	}
	if elapsed := time.Since(start); elapsed > 3*time.Second {
		t.Fatalf("无应答路径耗时 %v，远超 800ms 窗口 + 兜底", elapsed)
	}
}

func TestStunSamplePortsSequentialSelectsOneServer(t *testing.T) {
	srv := stunTestServer(t, "203.0.113.7", 44556)
	uc := newLocalUDP(t)
	ports := stunSamplePortsSequential(uc, []string{srv}, 3, 10)
	if len(ports) == 0 {
		t.Fatal("一个样本都没采到")
	}
	for _, p := range ports {
		if p != 44556 {
			t.Fatalf("样本端口 %d，期望全部 44556", p)
		}
	}
}

func TestStunUrlsProbedFirstMovesReachableToFront(t *testing.T) {
	groups := [][]string{{"a"}, {"b"}, {"c"}}
	probe := &stunProbeResult{ReachableStunURLs: []string{"c"}}
	got := stunUrlsProbedFirst(groups, probe)
	if len(got) != 3 || got[0] != "c" {
		t.Fatalf("重排结果 %v，期望 c 前置（ConnectionManager.java:953-963）", got)
	}
	if got[1] != "a" || got[2] != "b" {
		t.Fatalf("其余项相对顺序被改变: %v", got)
	}
	if all := stunUrlsProbedFirst(groups, nil); len(all) != 3 || all[0] != "a" {
		t.Fatalf("无探测结果时应保持静态表顺序，实得 %v", all)
	}
}

func TestStunDetectorGroupsCountMatchesJava(t *testing.T) {
	g := stunServerGroupsFromDetector()
	if len(g) != 13 {
		t.Fatalf("分组数 %d，StunDetector.java:13-27 是 13 组", len(g))
	}
	if all := stunAllURLs(); len(all) != 13 {
		t.Fatalf("URL 总数 %d，期望 13（每组 1 台）", len(all))
	}
	if g[0][0] != "stun:stun.miwifi.com" || g[3][0] != "stun:stun.l.google.com:19302" {
		t.Fatalf("表内容与设计不符: %v", g)
	}
}

func TestStunWarmCacheTTL(t *testing.T) {
	// WARM_REUSE_TTL_MS = 20000（:40/:569）：写入后立刻可读，超时的读不到
	res := &stunProbeResult{NatType: natTypeFullCone}
	stunSetCachedResult(res)
	if stunGetCachedResult() != res {
		t.Fatal("热缓存刚写就读不到")
	}
	stunWarmMu.Lock()
	stunWarmStampMs = time.Now().UnixMilli() - stunWarmReuseTtlMs - 1
	stunWarmMu.Unlock()
	if stunGetCachedResult() != nil {
		t.Fatal("超过 20s 仍在复用热缓存")
	}
}

func TestStunNetworkChangedUnknownToKnown(t *testing.T) {
	// 源码 M7 修正（:550-555）：last 为 unknown/未设置时，当前拿到真 IP 要算变更
	stunLastIPMu.Lock()
	stunLastProbeIP = "unknown"
	stunLastIPSetOnce = true
	stunLastIPMu.Unlock()
	if !stunNetworkChanged() {
		t.Skip("本机取不到非 unknown 本地地址（无可用路由），此断言在当前环境不适用")
	}
	stunSetLastProbeLocalIP(stunDetectLocalIP())
	if stunNetworkChanged() {
		t.Fatal("同一地址不该判成网络变更")
	}
}
