package main

// 房主侧多 socket STUN 阵列的用例。参照物全部是 ConnectionManager.java 的 handlePunchInfo async 块：
// 规模 CM:3953-3983、双 STUN 收集 CM:4086-4260、补满 CM:4344-4385、
// 通告 CM:4389-4453、漂移重发 CM:4599-4689、扫窗带宽 CM:4547-4549、逐 socket 齐射 CM:4701-4849。

import (
	"encoding/json"
	"net"
	"net/http"
	"strings"
	"sync"
	"testing"
	"time"
)

func stunAddr(ip string, port int) *stunMappedAddr {
	return &stunMappedAddr{IP: ip, Port: port}
}

// arrayTestProfile 只带阵列要读的四个字段，避免依赖整张档位表的具体数值。
func arrayTestProfile(birthday, createIntervalMs int) *punchProfile {
	return &punchProfile{
		name:                     "ARRAYTEST",
		birthdaySocketCount:      birthday,
		hardSymSocketCount:       8,
		hostMultiSocketCount:     20,
		hostMultiMinSocketCount:  5,
		hostMultiBaseSocketCount: 3,
		socketCreateIntervalMs:   createIntervalMs,
		joinerMultiPortRange:     50,
	}
}

func udpGroup(t *testing.T, n int) []*net.UDPConn {
	t.Helper()
	out := make([]*net.UDPConn, 0, n)
	for i := 0; i < n; i++ {
		c, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 0})
		if err != nil {
			for _, x := range out {
				_ = x.Close()
			}
			t.Fatal(err)
		}
		out = append(out, c)
	}
	t.Cleanup(func() {
		for _, x := range out {
			_ = x.Close()
		}
	})
	return out
}

// 收集顺序 = socket 创建顺序；IPv6 在前时优先通告 IPv4（CM:4222-4226）；
// 双 STUN 同族不同端口 = 本端对称，delta 取第一组（CM:4242-4252）。
func TestProbeHostSocketArrayCollectAndSymmetry(t *testing.T) {
	group := udpGroup(t, 4)
	profile := arrayTestProfile(32, 0)
	// 每台都立刻回，避免真发 STUN
	dual := func(c *net.UDPConn, u1, u2 string) []*stunMappedAddr {
		switch c {
		case group[0]:
			return []*stunMappedAddr{stunAddr("2001:db8::1", 40000), stunAddr("45.67.89.40", 41000)}
		case group[1]:
			return []*stunMappedAddr{stunAddr("45.67.89.40", 42000), stunAddr("45.67.89.40", 42050)}
		case group[2]:
			return []*stunMappedAddr{nil, nil} // 全失败的 socket 不进通告（CM:4218）
		default:
			return []*stunMappedAddr{stunAddr("45.67.89.40", 43000), nil}
		}
	}
	arr := probeHostSocketArray(profile, group, []string{"stun:a", "stun:b"}, dual, nil)

	got := arr.ports()
	want := []int{41000, 42000, 43000} // 顺序 = 创建顺序，0 号取 IPv4 那个
	if len(got) != len(want) {
		t.Fatalf("通告端口数不对，期望 %v 得到 %v", want, got)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("通告顺序不对：第 %d 个期望 %d 得到 %d（%v）", i, want[i], got[i], got)
		}
	}
	if arr.ip() != "45.67.89.40" {
		t.Fatalf("mappedIp 取第一个选定地址（CM:4232-4236），得到 %q", arr.ip())
	}
	if !arr.symmetric {
		t.Fatal("1 号 socket 双 STUN 同族不同端口必须判本端对称（CM:4242-4244）")
	}
	if arr.delta != 50 {
		t.Fatalf("delta 取第一次见到的 42050-42000=50（CM:4246-4249），得到 %d", arr.delta)
	}
}

// 就绪门槛：够 min(birthdaySocketCount, N) 就收，不等慢的那几个（CM:4140/4184）。
func TestProbeHostSocketArrayStopsAtMinRequired(t *testing.T) {
	group := udpGroup(t, 4)
	profile := arrayTestProfile(2, 0) // minRequired = min(2,4) = 2
	var mu sync.Mutex
	calls := 0
	dual := func(c *net.UDPConn, u1, u2 string) []*stunMappedAddr {
		mu.Lock()
		calls++
		mu.Unlock()
		if c == group[2] || c == group[3] {
			time.Sleep(6 * time.Second) // 慢得赶不上趟
			return []*stunMappedAddr{stunAddr("1.1.1.1", 1), nil}
		}
		return []*stunMappedAddr{stunAddr("45.67.89.40", 50000), stunAddr("45.67.89.40", 50001)}
	}
	start := time.Now()
	arr := probeHostSocketArray(profile, group, []string{"stun:a", "stun:b"}, dual, nil)
	elapsed := time.Since(start)
	if elapsed > 3*time.Second {
		t.Fatalf("够了门槛就该收，不该等慢探测（等了 %v）", elapsed)
	}
	if len(arr.addrs) == 0 {
		t.Fatal("快的两台要进通告")
	}
	mu.Lock()
	defer mu.Unlock()
	if calls < 2 {
		t.Fatalf("四台都得发起探测，实际 %d", calls)
	}
}

// 全失败：模组只记 "All STUN queries failed"，通告载荷为空（CM:4264 + CM:4389 起不来）。
func TestProbeHostSocketArrayAllFailed(t *testing.T) {
	group := udpGroup(t, 3)
	profile := arrayTestProfile(32, 0)
	dual := func(c *net.UDPConn, u1, u2 string) []*stunMappedAddr {
		return []*stunMappedAddr{nil, nil}
	}
	arr := probeHostSocketArray(profile, group, []string{"stun:a", "stun:b"}, dual, nil)
	if len(arr.addrs) != 0 {
		t.Fatalf("全失败不该有通告地址，得到 %v", arr.ports())
	}
	if hostMappedPayload(arr, false, false, false) != nil {
		t.Fatal("没有映射时 holepunch_mapped 载荷必须为空")
	}
}

// 通告字段：对称 → hostSymmetric/hostEasySym/delta；|delta|>100 不算易对称；非对称一个都不发。
func TestHostMappedPayloadSymmetricFields(t *testing.T) {
	arr := &hostArrayResult{
		addrs:     []*stunMappedAddr{stunAddr("45.67.89.40", 41000), stunAddr("45.67.89.40", 41010)},
		symmetric: true,
		delta:     10,
	}
	p := hostMappedPayload(arr, false, false, false)
	if p["hostSymmetric"] != true || p["hostEasySym"] != true {
		t.Fatalf("易对称要同时给两个标志（CM:4395/4405），得到 %+v", p)
	}
	if p["hostMappedPortDelta"] != 10 { // CM:4411
		t.Fatalf("delta 要随通告走，得到 %+v", p)
	}
	if p["hostMappedPort"] != 41000 { // CM:4419 取第一个
		t.Fatalf("hostMappedPort 必须是 addrs[0]，得到 %+v", p)
	}
	ports, _ := p["hostMappedPorts"].([]int)
	if len(ports) != 2 { // CM:4437-4449
		t.Fatalf("hostMappedPorts 要带全量，得到 %+v", p["hostMappedPorts"])
	}

	arr2 := &hostArrayResult{
		addrs:     []*stunMappedAddr{stunAddr("45.67.89.40", 41000), stunAddr("45.67.89.40", 41500)},
		symmetric: true,
		delta:     500,
	}
	p2 := hostMappedPayload(arr2, false, false, false)
	if _, ok := p2["hostEasySym"]; ok {
		t.Fatalf("|delta|>100 不能报易对称（CM:4401），得到 %+v", p2)
	}

	p3 := hostMappedPayload(&hostArrayResult{addrs: []*stunMappedAddr{stunAddr("45.67.89.40", 41000)}}, false, false, false)
	for _, k := range []string{"hostSymmetric", "hostEasySym", "hostMappedPortDelta"} {
		if _, ok := p3[k]; ok {
			t.Fatalf("没检出对称就不该有 %s（CM:4391-4411），得到 %+v", k, p3)
		}
	}
	// CM:4391-4393：房间记录是对称时，即使阵列没检出也要报 hostSymmetric
	if hostMappedPayload(&hostArrayResult{addrs: []*stunMappedAddr{stunAddr("45.67.89.40", 41000)}}, true, false, false)["hostSymmetric"] != true {
		t.Fatal("natType 已判对称时必须带 hostSymmetric（CM:4393）")
	}
}

// 漂移重发包：hostSymmetric 恒 true，不带 delta、不带 hostLocalIp（CM:4631-4651）。
func TestHostDriftPayloadShape(t *testing.T) {
	arr := &hostArrayResult{
		addrs:     []*stunMappedAddr{stunAddr("45.67.89.40", 41000), stunAddr("45.67.89.40", 41010)},
		symmetric: true,
		delta:     10,
	}
	p := hostDriftPayload(arr)
	if p["hostSymmetric"] != true {
		t.Fatalf("重发包恒带 hostSymmetric=true（CM:4637），得到 %+v", p)
	}
	if _, ok := p["hostMappedPortDelta"]; ok {
		t.Fatalf("重发包不带 delta（CM:4631-4651 里没有这一项），得到 %+v", p)
	}
	if _, ok := p["hostLocalIp"]; ok {
		t.Fatalf("重发包不带 hostLocalIp，得到 %+v", p)
	}
	if got, _ := p["hostMappedPorts"].([]int); len(got) != 2 {
		t.Fatalf("重发包要带全量端口，得到 %+v", p["hostMappedPorts"])
	}
	if !hostDriftSyncNeeded(arr, false) {
		t.Fatal("检出对称就要开漂移重发（CM:4599）")
	}
	if hostDriftSyncNeeded(&hostArrayResult{addrs: []*stunMappedAddr{stunAddr("1.1.1.1", 1)}}, false) {
		t.Fatal("双锥不必重发（CM:4599 的或条件两边都不成立）")
	}
	if !hostDriftSyncNeeded(&hostArrayResult{addrs: []*stunMappedAddr{stunAddr("1.1.1.1", 1)}}, true) {
		t.Fatal("房间记录是对称时也开重发（CM:4599 右半边）")
	}
}

// 扫窗带宽：只有 joiner 自报对称或双方都对称才给（CM:4547-4549），否则只打已知端口。
func TestHostRoundPeerRangeGate(t *testing.T) {
	p := arrayTestProfile(32, 0)
	if got := hostRoundPeerRange(p, false, false, false); got != 0 {
		t.Fatalf("双锥要 0（只打已知端口），得到 %d", got)
	}
	if got := hostRoundPeerRange(p, true, false, false); got != p.joinerMultiPortRange {
		t.Fatalf("joiner 自报对称要给 joinerMultiPortRange=%d，得到 %d", p.joinerMultiPortRange, got)
	}
	if got := hostRoundPeerRange(p, false, true, true); got != p.joinerMultiPortRange {
		t.Fatalf("双对称要给带宽（CM:4547 hostDoubleSym），得到 %d", got)
	}
	if got := hostRoundPeerRange(p, false, true, false); got != 0 {
		t.Fatalf("只有本端对称、对端是锥型时不给带宽（CM:4549 判据是 joinerSym 或双对称），得到 %d", got)
	}
}

// 补满：检出对称才补，且补到 hardSymSocketCount（CM:4344-4385）。
func TestExtendHostSocketGroupFillsToTarget(t *testing.T) {
	group := udpGroup(t, 3)
	if got := len(extendHostSocketGroup(group, 3)); got != 3 {
		t.Fatalf("已经够数就不该再加，得到 %d", got)
	}
	bigger := extendHostSocketGroup(group, 8)
	if len(bigger) != 8 {
		t.Fatalf("补到目标数（CM:4348），得到 %d", len(bigger))
	}
	for _, c := range bigger {
		if c == nil {
			t.Fatal("补建失败要留空位？模组是少一个算一个，不能塞 nil")
		}
	}
}

// 逐 socket 齐射：组里每一台都要真发出去，任一台收到 PUNCH 即赢，其余收流但 socket 不关
// （CM:4701-4849：hostPunchers 整段复用，下一轮还用同一批源端口）。
func TestHostPunchRoundFiresEverySocketAndPicksOneWinner(t *testing.T) {
	if testing.Short() {
		t.Skip("short 模式跳过 socket 测试")
	}
	_, engine, _ := arrayEngine(t)
	peer := udpGroup(t, 1)[0]
	target := peer.LocalAddr().(*net.UDPAddr)
	group := udpGroup(t, 3)

	mapped := stunAddr("127.0.0.1", target.Port)
	profile := punchDefault
	params := punchParamsFromProfile(profile)
	params.timeoutMs = 1200

	// 假房客：记下所有来源端口，并向第一个来源回一发 PUNCH（单向判赢）
	seen := make(chan int, 32)
	stop := make(chan struct{})
	go func() {
		buf := make([]byte, 64)
		first := true
		for {
			select {
			case <-stop:
				return
			default:
			}
			_ = peer.SetReadDeadline(time.Now().Add(300 * time.Millisecond))
			n, from, err := peer.ReadFromUDP(buf)
			if err != nil {
				continue
			}
			if n < 3 || buf[0] != punchMagic0 || buf[1] != punchMagic1 {
				continue
			}
			seen <- from.Port
			if buf[2] == punchTypePunch && first {
				first = false
				_, _ = peer.WriteToUDP(buildPunchControl(punchTypePunch, 0x1234, nil), from)
			}
		}
	}()
	defer close(stop)

	winner, res := engine.hostPunchRound(group, profile, params, nil, mapped, 0, "guest-array")
	if winner == nil || res == nil || !res.success {
		t.Fatalf("有一台收到 PUNCH 就该判赢，得到 %+v / %+v", winner, res)
	}
	if winConn, _ := winner.win(); winConn == nil {
		t.Fatal("赢的 puncher 要交回它的 socket")
	}
	// 组里每台都发过包：源端口数 = 组大小
	sources := map[int]bool{}
	deadline := time.After(2 * time.Second)
collect:
	for len(sources) < len(group) {
		select {
		case p := <-seen:
			sources[p] = true
		case <-deadline:
			break collect
		}
	}
	if len(sources) < len(group) {
		t.Fatalf("阵列的意义就是每台源端口都开打，只见到 %d/%d 个源端口", len(sources), len(group))
	}
	for _, c := range group { // socket 必须还活着（下一轮复用）
		_ = c.SetReadDeadline(time.Now().Add(50 * time.Millisecond))
		buf := make([]byte, 4)
		if _, _, err := c.ReadFromUDP(buf); err != nil && isClosedPipeOrDeadline(err) {
			t.Fatalf("组内 socket 不能被打完一轮就关掉：%v", err)
		}
	}
}

func isClosedPipeOrDeadline(err error) bool {
	if ne, ok := err.(net.Error); ok && ne.Timeout() {
		return false // 超时说明 socket 还活着
	}
	return strings.Contains(err.Error(), "use of closed")
}

// 整条房主阵列：选规模 → 逐 socket 双 STUN → 检出对称就升级 NAT/档位并补满 socket →
// 发 holepunch_mapped（含 hostMappedPorts）。CM:3941-4457。
func TestHostMappedArrayUpgradesExtendsAndPublishes(t *testing.T) {
	_, engine, snapshot := arrayEngine(t)
	sock := udpGroup(t, 1)[0]
	peer := &hostPeer{id: "guest-p", mapped: stunAddr("98.76.54.32", 19000), hostMapped: stunAddr("45.67.89.40", 21000)}
	engine.mu.Lock()
	if engine.hPeers == nil {
		engine.hPeers = map[string]*hostPeer{}
	}
	engine.hPeers[peer.id] = peer
	engine.code, engine.token, engine.isHost = "HOST01", "host-token", true
	engine.hostNatClass = natCone
	engine.mu.Unlock()

	profile := arrayTestProfile(32, 0)
	profile.hostMultiSocketCount = 3
	profile.hostMultiBaseSocketCount = 3
	// 每台都同族不同端口 → 本端对称，|delta|=5 → 易对称（CM:4242-4252 / CM:4292）
	dual := func(c *net.UDPConn, u1, u2 string) []*stunMappedAddr {
		p := c.LocalAddr().(*net.UDPAddr).Port
		return []*stunMappedAddr{stunAddr("45.67.89.40", p), stunAddr("45.67.89.40", p+5)}
	}
	group, arr, after := engine.hostMappedArray(profile, sock, peer, natCone, natCone, dual)
	if len(group) != recommendProfile(natEasySym, natCone).hardSymSocketCount {
		t.Fatalf("CM:4344-4385：检出对称后要把组补到新档位的 hardSymSocketCount，实际 %d", len(group))
	}
	if len(arr.addrs) != 3 {
		t.Fatalf("补建的 socket 不做 STUN（CM:4352-4379 只 createSocket），通告仍应只有 3 个，得到 %d", len(arr.addrs))
	}
	if !arr.symmetric || arr.delta != 5 {
		t.Fatalf("对称与 delta 检出不对：%+v", arr)
	}
	engine.mu.Lock()
	nat := engine.hostNatClass
	tier := engine.scenarioTier
	engine.mu.Unlock()
	if nat != natEasySym {
		t.Fatalf("CM:4294/4298 要把本端升级成易对称，得到 %s", nat.String())
	}
	if !scenarioIsHard(tier) {
		t.Fatalf("CM:4306-4308 升级后要按硬档记档位，得到 %s", scenarioTierKey(tier))
	}
	if after == profile {
		t.Fatal("CM:4316-4318 检出对称后要按新 NAT 组合换档位")
	}

	var mapped map[string]any
	for _, s := range snapshot() {
		if s["type"] == "holepunch_mapped" {
			mapped, _ = s["data"].(map[string]any)
		}
	}
	if mapped == nil {
		t.Fatal("阵列做完必须发 holepunch_mapped（CM:4455-4457）")
	}
	if mapped["hostSymmetric"] != true || mapped["hostEasySym"] != true {
		t.Fatalf("通告要带对称与易对称两个标志（CM:4395/4405），得到 %+v", mapped)
	}
	rawPorts, _ := mapped["hostMappedPorts"].([]any)
	if len(rawPorts) != 3 {
		t.Fatalf("hostMappedPorts 要给房客 3 个源端口，得到 %v", mapped["hostMappedPorts"])
	}
	if mapped["hostMappedPortDelta"] != float64(5) {
		t.Fatalf("delta 要随通告走（CM:4411），得到 %v", mapped["hostMappedPortDelta"])
	}
}

// arrayEngine 起一个带信令桩的应用端引擎，返回"取已捕获信令"的快照函数。
// 桩的写法和 hostPunchInfoHarness（addr_filter_test.go:74-105）一致：sendSignal 是同步 HTTP，
// 返回后这条已经落到 handler 里。
func arrayEngine(t *testing.T) (*App, *connEngine, func() []map[string]any) {
	t.Helper()
	var mu = make(chan struct{}, 1)
	mu <- struct{}{}
	var got []map[string]any
	a := newRelayTestApp(t, func(w http.ResponseWriter, r *http.Request) {
		var s map[string]any
		_ = json.NewDecoder(r.Body).Decode(&s)
		<-mu
		got = append(got, s)
		mu <- struct{}{}
		sendJSON(w, map[string]any{"success": true, "data": map[string]any{}})
	})
	snapshot := func() []map[string]any {
		<-mu
		defer func() { mu <- struct{}{} }()
		return append([]map[string]any(nil), got...)
	}
	return a, a.engine, snapshot
}
