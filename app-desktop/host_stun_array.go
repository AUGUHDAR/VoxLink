package main

// host_stun_array.go — 房主侧"多 socket + 逐 socket 双 STUN"阵列。
// 对位模组 ConnectionManager.handlePunchInfo 里的那个 async 块：
//   规模选择 CM:3953-3983 → punch_groups.go hostMultiSocketGroupCount
//   建 socket + 并行双 STUN CM:4035-4134
//   就绪轮询 CM:4140-4204（够 min(birthdaySocketCount,N) 或全部完成，硬截止 max(5000, N*间隔+3000)）
//   按创建顺序收集 + IPv4 优先 + 对称检出 CM:4208-4260
//   补到 hardSymSocketCount CM:4344-4385
//   holepunch_mapped 载荷 CM:4389-4453，漂移重发 CM:4599-4689
//   每轮目标端口带宽 CM:4547-4549
//
// 产出的 hostMappedPorts 是房客生日多端口扇出的输入（CM:3406-3442 消费；
// 应用端消费点 conn_engine.go guestOnMapped）。此前房主只报一个映射端口，
// 房客那侧的"多源端口撞对端锥端"等于没有输入。

import (
	"net"
	"strings"
	"sync"
	"time"
)

// hostArrayResult = CM:4007-4011 的 mappedAddrs / hostPunchSocketSymmetric / hostPunchSocketDelta。
type hostArrayResult struct {
	addrs     []*stunMappedAddr // 通告顺序 = socket 创建顺序（CM:4212-4238）
	symmetric bool              // CM:4244
	delta     int               // CM:4246-4249 第一组双 STUN 的端口差
}

func (r *hostArrayResult) ports() []int {
	out := make([]int, 0, len(r.addrs))
	for _, a := range r.addrs {
		out = append(out, a.Port) // CM:4441-4445
	}
	return out
}

// ip = CM:4232-4236 的 mappedIp：第一个选定通告地址的 IP。
func (r *hostArrayResult) ip() string {
	if len(r.addrs) == 0 {
		return ""
	}
	return r.addrs[0].IP
}

// dualStunFn 注入点：生产传 stunDiscoverMappedAddressDual，测试换成假应答。
type dualStunFn func(uc *net.UDPConn, url1, url2 string) []*stunMappedAddr

// sameIpFamily = CM:6236-6242：含 ':' 与否一致才算同族（任一侧缺省直接 false）。
func sameIpFamily(a, b string) bool {
	if a == "" || b == "" {
		return false
	}
	return strings.Contains(a, ":") == strings.Contains(b, ":")
}

// chooseHostAnnouncedAddr = CM:4222-4226 的三元嵌套原样展开：
// 先取第一个非 IPv6 的，再取第二个非 IPv6 的，然后退回第一个、第二个。
func chooseHostAnnouncedAddr(a0, a1 *stunMappedAddr) *stunMappedAddr {
	switch {
	case a0 != nil && !strings.Contains(a0.IP, ":"):
		return a0
	case a1 != nil && !strings.Contains(a1.IP, ":"):
		return a1
	case a0 != nil:
		return a0
	default:
		return a1
	}
}

// hostStunProbe 一个 socket 的双 STUN 回包（CM:4086 的 CompletableFuture<PublicMappedAddress[]>）。
type hostStunProbe struct {
	idx   int
	addrs []*stunMappedAddr
}

// probeHostSocketArray 给组里每个 socket 并行做一次双 STUN，按 CM:4140-4204 的就绪条件
// 等到"够数 / 全完成 / 硬截止"，再按 CM:4212-4260 顺序收集。
// urls 取 CM:4094 的前两台；不足两台时模组会在 supplyAsync 里抛 IndexOutOfBounds，
// 每个 future 都以异常收场 → 等价"全部失败"，这里按那个结果处理，不自创兜底。
func probeHostSocketArray(p *punchProfile, group []*net.UDPConn, urls []string, dual dualStunFn, done <-chan struct{}) *hostArrayResult {
	res := &hostArrayResult{}
	if len(group) == 0 {
		return res
	}
	if len(urls) < 2 || dual == nil {
		stunProbeLog("error", "[HostPunchInfo] All STUN queries failed") // CM:4266
		return res
	}
	fin := make(chan hostStunProbe, len(group))
	for i := range group {
		go func(i int) {
			d := dual(group[i], urls[0], urls[1]) // CM:4092-4096
			for len(d) < 2 {
				d = append(d, nil)
			}
			fin <- hostStunProbe{idx: i, addrs: d}
		}(i)
	}

	results := make([][]*stunMappedAddr, len(group)) // 只有本函数写，收包下标经 channel 传
	seen, success := 0, 0
	drain := func() {
		for {
			select {
			case pr := <-fin:
				results[pr.idx] = pr.addrs
				seen++
				if pr.addrs[0] != nil || pr.addrs[1] != nil {
					success++
				}
			default:
				return
			}
		}
	}

	minRequired := groupMinRequired(p, len(group))           // CM:4140
	deadline := groupStunDeadline(p, len(group), time.Now()) // CM:4142-4144
	stopped := false
	for time.Now().Before(deadline) { // CM:4148
		drain()
		if success >= minRequired || seen == len(group) {
			break
		}
		select {
		case <-done:
			stopped = true // CM:4196-4201 interrupt → break
		case <-time.After(100 * time.Millisecond): // CM:4194
		}
		if stopped {
			break
		}
	}
	drain() // 已完成的按 CM:4216 getNow(null) 的口径全部收下

	for i := 0; i < len(group); i++ { // CM:4212
		addrs := results[i]
		if addrs == nil {
			continue
		}
		if addrs[0] == nil && addrs[1] == nil {
			continue // CM:4218
		}
		if chosen := chooseHostAnnouncedAddr(addrs[0], addrs[1]); chosen != nil {
			res.addrs = append(res.addrs, chosen) // CM:4230
		}
		// CM:4242-4252：同族且两个映射端口不同 → 本端对称；delta 取第一次见到的差值
		if addrs[0] != nil && addrs[1] != nil && sameIpFamily(addrs[0].IP, addrs[1].IP) && addrs[0].Port != addrs[1].Port {
			res.symmetric = true
			if res.delta == 0 {
				res.delta = addrs[1].Port - addrs[0].Port
			}
		}
	}
	if len(res.addrs) == 0 {
		stunProbeLog("error", "[HostPunchInfo] All STUN queries failed") // CM:4266
	} else {
		stunProbeLog("info", "[HostPunchInfo] Socket array ready: %d/%d mapped, symmetric=%v delta=%d",
			len(res.addrs), len(group), res.symmetric, res.delta)
	}
	return res
}

// extendHostSocketGroup = CM:4344-4385：检出本端对称且组不够时补到 hardSymSocketCount。
// 补建的 socket 只当打洞源，不再做 STUN（模组这段就是只 createSocket 然后加进 hostPunchers）。
// 单个失败只是少一个（CM:4373-4377 catch 后继续循环），与建组时 break 的口径不同。
func extendHostSocketGroup(group []*net.UDPConn, target int) []*net.UDPConn {
	for len(group) < target {
		uc, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4zero, Port: 0})
		if err != nil {
			continue // CM:4373-4377
		}
		group = append(group, uc)
	}
	return group
}

// hostMappedPayload = CM:4389-4453 的 symData。
// roomNatSymmetric 对 CM:4393 的 isNatTypeSymmetric(roomInfo.getNatType())，
// sameCgnat/requestHostLocalIp 对 CM:4421。
func hostMappedPayload(r *hostArrayResult, roomNatSymmetric, sameCgnat, requestHostLocalIp bool) map[string]any {
	if r == nil || len(r.addrs) == 0 {
		return nil
	}
	m := map[string]any{}
	if r.symmetric || roomNatSymmetric {
		m["hostSymmetric"] = true // CM:4395
	}
	// CM:4401 hostEasySymMapped = 检出对称 且 |delta|<=100
	if r.symmetric && absInt(r.delta) <= 100 {
		m["hostEasySym"] = true // CM:4405
	}
	if r.symmetric && r.delta != 0 {
		m["hostMappedPortDelta"] = r.delta // CM:4411
	}
	m["hostMappedIp"] = r.ip()            // CM:4417
	m["hostMappedPort"] = r.addrs[0].Port // CM:4419
	m["hostMappedPorts"] = r.ports()      // CM:4437-4449
	if sameCgnat || requestHostLocalIp {  // CM:4421
		if lip := getLocalIP(); lip != "" {
			m["hostLocalIp"] = lip // CM:4427
		}
	}
	return m
}

// hostDriftPayload = CM:4631-4651 的重发包：不带 delta、不带 hostLocalIp，hostSymmetric 恒 true。
func hostDriftPayload(r *hostArrayResult) map[string]any {
	if r == nil || len(r.addrs) == 0 {
		return nil
	}
	m := map[string]any{
		"hostMappedIp":    r.ip(),          // CM:4633
		"hostMappedPort":  r.addrs[0].Port, // CM:4635
		"hostSymmetric":   true,            // CM:4637
		"hostMappedPorts": r.ports(),       // CM:4639-4651
	}
	if r.symmetric && absInt(r.delta) <= 100 {
		m["hostEasySym"] = true // CM:4653-4655
	}
	return m
}

// hostDriftSyncNeeded = CM:4599：阵列检出对称，或房间记录本来就是对称。
func hostDriftSyncNeeded(r *hostArrayResult, roomHostSymmetric bool) bool {
	return r != nil && (r.symmetric || roomHostSymmetric)
}

// 漂移重发的节流常量（CM:4601 / CM:4623）：最长 120 秒、每 3 秒最多一次。
const (
	hostDriftMaxDuration = 120 * time.Second // CM:4601 driftMaxDurMs=120000
	hostDriftResendGap   = 3 * time.Second   // CM:4623 nowMs-driftLastSentMs>=3000L
	hostDriftPollStep    = time.Second       // CM:4615 外层轮询节奏（模组是 300ms 主循环，这里取粗粒度不影响发送间隔）
)

// hostDriftResync = CM:4617-4689：打洞进行期间周期性把阵列结果重发给房客。
// 房客的端口预测靠这份通告，映射漂了（家宽 NAT 常见）就靠它纠偏。
// punching 由调用方给闭包（对位 CM:4617 的 hostPunchContext.punching）。
func (e *connEngine) hostDriftResync(peerID string, r *hostArrayResult, punching func() bool, stop <-chan struct{}) {
	if r == nil || len(r.addrs) == 0 {
		return
	}
	start := time.Now()
	lastSent := time.Time{}
	tick := time.NewTicker(hostDriftPollStep)
	defer tick.Stop()
	for {
		select {
		case <-stop:
			return
		case <-e.ctxDone():
			return
		case <-tick.C:
			if !punching() { // CM:4617
				return
			}
			now := time.Now()
			if now.Sub(lastSent) < hostDriftResendGap { // CM:4623
				continue
			}
			if now.Sub(start) >= hostDriftMaxDuration { // CM:4623 driftElapsed < 120000
				return
			}
			lastSent = now
			payload := hostDriftPayload(r)
			if payload == nil {
				return
			}
			if err := e.sendSignal("holepunch_mapped", payload, peerID); err != nil {
				e.app.netLog("warn", "[HostPunchInfo] drift resync send failed: "+err.Error()) // CM:4667
				continue
			}
			e.app.netLog("info", "[HostPunchInfo] drift resync: resend holepunch_mapped "+payloadIP(payload)+
				" ports="+itoa(len(r.addrs))+" elapsed_ms="+itoa(int(now.Sub(start)/time.Millisecond))) // CM:4673-4681
		}
	}
}

func payloadIP(m map[string]any) string {
	if s, ok := m["hostMappedIp"].(string); ok {
		return s
	}
	return ""
}

// hostPunchRound = CM:4701-4849 的一轮：组内每个 socket 并行做一次带预测的打洞，
// 第一个成功的赢（CM:4759 的 hostPunchWon CAS），其余当轮收流但 socket 不关——
// 模组的 hostPunchers 整段复用，下一轮还用同一批源端口（关了就要重测映射）。
// 没有赢家时返回第一个 puncher 和它的失败结果，供调用方喂 PunchTuner（CM:4737-4753）。
func (e *connEngine) hostPunchRound(group []*net.UDPConn, profile *punchProfile, params *punchParams,
	authKey []byte, mapped *stunMappedAddr, portRange int, peerID string) (*puncher, *punchResult) {
	if len(group) == 0 || mapped == nil {
		return nil, nil
	}
	// CM:4727 轮目标端口优先取 puncher 当前值（漂移纠偏后的），这里每轮都从 peer.mapped 现取，
	// 而 peer.mapped 由后续 punch_info 更新（conn_engine.go hostOnPunchInfo），语义一致。
	base := &net.UDPAddr{IP: net.ParseIP(mapped.IP), Port: mapped.Port}
	if base.IP == nil {
		return nil, nil
	}
	var mu sync.Mutex
	var winner *puncher
	var winRes *punchResult
	var first *puncher
	var firstRes *punchResult
	var all []*puncher
	var wg sync.WaitGroup
	for _, c := range group {
		wg.Add(1)
		go func(c *net.UDPConn) {
			defer wg.Done()
			p := newPuncher(c, profile, params, authKey)
			p.setLogf(e.app.netLog)
			p.setTarget(base)
			p.setOnPeer(func(addr *net.UDPAddr) { // CM:4479-4501：每个 socket 都要能上报对端实测地址
				_ = e.sendSignal("peer_port", map[string]any{"peer_ip": addr.IP.String(), "peer_port": addr.Port}, peerID)
			})
			res := p.punchPrediction(base.Port, portRange, false) // CM:4731
			mu.Lock()
			all = append(all, p)
			if first == nil {
				first, firstRes = p, res
			}
			if res != nil && res.success && winner == nil {
				winner, winRes = p, res // CM:4759 首个成功者占位
			}
			mu.Unlock()
		}(c)
	}
	wg.Wait()
	if winner != nil {
		for _, p := range all { // 后到的成功者也收掉（CM:4759-4767 输家 close 自己那条）
			if p != winner {
				p.cancel()
			}
		}
		return winner, winRes
	}
	for _, p := range all {
		p.cancel()
	}
	return first, firstRes
}

// closeGroupExcept 桥起来之后关掉其余源 socket（CM:4274-4284 / CM:4519-4531 的清理口径）。
func closeGroupExcept(group []*net.UDPConn, keep *net.UDPConn) {
	for _, c := range group {
		if c != nil && c != keep {
			_ = c.Close()
		}
	}
}

// hostMappedArray = CM:3941-4457 的整段：选规模 → 建组 → 逐 socket 双 STUN →
// 检出对称就升级 NAT/档位并补满 socket → 发 holepunch_mapped（含 hostMappedPorts）。
// 返回的 profile 可能已被升级分支换掉，调用方要用返回值而不是入参。
// dual 是双 STUN 的实现注入点（生产传 stunDiscoverMappedAddressDual）。
func (e *connEngine) hostMappedArray(profile *punchProfile, sock *net.UDPConn, peer *hostPeer,
	hostNat, joinerNat natClass, dual dualStunFn) ([]*net.UDPConn, *hostArrayResult, *punchProfile) {
	e.mu.Lock()
	joinerSym := peer.joinerSym
	sameCgnat := peer.sameCgnat
	wantLocalIp := peer.requestHostLocalIp
	e.mu.Unlock()

	hostSym := e.roomNatTypeSymmetric()                              // CM:3867
	portUnreachable := e.app.ipv4PortStatus().unreachableOrUnknown() // CM:3941-3943
	hardTier := scenarioIsHard(e.scenarioTier)                       // CM:3965-3967
	count := hostMultiSocketGroupCount(profile, hostSym, joinerSym, portUnreachable, hardTier)
	if count > 1 {
		e.app.netLog("info", "[HostPunchInfo] host socket 阵列规模="+itoa(count)+
			" hostSym="+boolStr(hostSym)+" joinerSym="+boolStr(joinerSym)+
			" portUnreachable="+boolStr(portUnreachable)+" hardTier="+boolStr(hardTier)) // CM:3947 同位
	}
	group := makeThrottledPunchSocketGroup(sock, count, socketCreateGap(profile), e.ctxDone()) // CM:4035-4134
	urls := stunUrlsProbedFirst(stunServerGroupsFromDetector(), stunGetCachedResult())         // CM:4094
	arr := probeHostSocketArray(profile, group, urls, dual, e.ctxDone())

	if len(arr.addrs) == 0 {
		// CM:4264 模组此时只记 "All STUN queries failed"，既不通告也不开打。
		// 应用端不跟着把打洞整段掐掉：现网房主在 STUN 全挂时仍能靠单 socket 被打穿
		// （1.1.9/1.2.0 的实测路径），退化成单 socket 是"不退步"而不是自创分支。
		e.app.netLog("warn", "[HostPunchInfo] All STUN queries failed，退化为单 socket 打洞")
		return []*net.UDPConn{sock}, arr, profile
	}

	if e.connectionWonFor(peer) { // CM:4268-4284：已经连上了就整组丢掉
		e.app.netLog("info", "[HostPunchInfo] Connection established, discard late socket punch task")
		closeGroupExcept(group, sock)
		return []*net.UDPConn{sock}, arr, profile
	}

	// CM:4288-4338：阵列自己看出本端对称（建房时那次单 socket 探测可能还没看出来）→ 升级判定
	if arr.symmetric && !e.roomHostSymmetric() {
		e.setRoomHostSymmetric(true) // CM:4290
		easySym := absInt(arr.delta) <= 100
		newLocal := natHardSym // CM:4294
		if easySym {
			newLocal = natEasySym
		}
		if hostNat != newLocal {
			e.app.netLog("info", "[HostPunchInfo] Update localNat "+hostNat.String()+" -> "+newLocal.String()+
				" (multi-socket symmetric detected, delta="+itoa(arr.delta)+")") // CM:4297
			e.mu.Lock()
			e.hostNatClass = newLocal                                                                        // CM:4298 applyLocalNatClass
			e.scenarioTier = scenarioClassify(newLocal.symmetric(), joinerNat.symmetric(), true, true, true) // CM:4306-4308
			tier := e.scenarioTier
			e.mu.Unlock()
			e.app.netLog("info", "[HostPunchInfo] tier -> "+scenarioTierKey(tier)+" (host symmetric known)") // CM:4310-4312
			profile = recommendProfile(newLocal, joinerNat)                                                  // CM:4316
			punchProfileSwitch(profile, "host_punch_sym_"+newLocal.String()+"x"+joinerNat.String())          // CM:4318
			e.app.netLog("info", "[HostPunchInfo] Probe upgrade: local="+newLocal.String()+" remote="+
				joinerNat.String()+" -> profile="+profile.name) // CM:4320-4324
			hostNat = newLocal
			if easySym && e.roomNatType() != "symmetric_easy_inc" { // CM:4330-4334
				e.setRoomNatType("symmetric_easy_inc")
				e.app.netLog("info", "[HostPunchInfo] Override cached NAT -> symmetric_easy_inc (multi-socket dual STUN detected)")
			}
		}
	}

	// CM:4344-4385：本端对称要把生日阵列补满
	if target, need := hardSymExtendTarget(profile, arr.symmetric, len(group)); need {
		before := len(group)
		group = extendHostSocketGroup(group, target)
		e.app.netLog("info", "[HostPunchInfo] Symmetric host, extend birthday attack "+
			itoa(before)+" -> "+itoa(len(group))+" sockets") // CM:4383
	}

	payload := hostMappedPayload(arr, hostSym, sameCgnat, wantLocalIp) // CM:4389-4449
	if payload != nil {
		e.app.netLog("info", "[HostPunchInfo] holepunch_mapped: "+payloadIP(payload)+
			" ports="+itoa(len(arr.addrs))+" (symmetric="+boolStr(arr.symmetric)+")") // CM:4451-4453
		if err := e.sendSignal("holepunch_mapped", payload, peer.id); err != nil {
			e.app.netLog("warn", "holepunch_mapped send failed: "+err.Error()) // CM:4461
		}
	}
	return group, arr, profile
}

// hostRoundPeerRange = CM:4547-4549：joiner 对称或双对称时才给扫窗带宽，否则 0（只打已知端口）。
// 模组注释写明理由：对称 CGNAT 上扫射会炸出 socket数×端口数 个未知映射并触发洪泛保护，双向全丢；
// 生日攻击靠多个源端口撞对端已知端口，不靠扫射。
func hostRoundPeerRange(p *punchProfile, joinerSymmetric, localSym, remoteSym bool) int {
	hostDoubleSym := localSym && remoteSym // CM:4547
	if joinerSymmetric || hostDoubleSym {
		return p.joinerMultiPortRange // CM:4549
	}
	return 0
}
