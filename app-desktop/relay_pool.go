package main

// relay_pool.go — 玩家中继候选池注册。
//
// 为什么必须存在：候选者侧数据面（relay_setup/relay_accept/mux 子通道）早已实现，
// 但本端从没调用过 /relay/register，别人永远挑不到我们 —— 功能是半截的。
// 房主尤其要紧：房主往往公网映射最好，却因为"房主没有 clientId"整块被挡在候选池外
// （服务端为此专门做了 host-<房间号> 派生 + hostToken 证明，见 relaymanager.go:144-215）。
//
// 节奏照抄模组：会话内注册一次 + 每 60s 续约（CM:11445 `60L, TimeUnit.SECONDS`），
// 服务端 peer TTL 是 120s（relaymanager.go:14），60s 续约正好留一倍余量。
// 频控：服务端按 ip+clientId 每分钟 ≤6 次（relaymanager.go:200-205 的教训是"按裸 IP 计
// 会让同一 CGNAT 下的房主与房客互相挤额度"），我们的 60s 周期天然合规。

import (
	"context"
	"net"
	"strings"
	"time"
)

const (
	relayRenewInterval = 60 * time.Second
	relayProbeTimeout  = 1500 * time.Millisecond
	relayProbeTries    = 2
	relayProbeServers  = 3
)

// relayPeerID 房主用服务端派生身份 host-<房号>，房客用自己在 join 响应里拿到的 clientId。
// 房主自报的形态必须与之一致，否则服务端的 hostToken 证明分支不会生效（relaymanager.go:176-186）。
func (e *connEngine) relayPeerID() string {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.isHost {
		if e.code == "" {
			return ""
		}
		return "host-" + e.code
	}
	return e.clientID
}

// relaySelfMapped 本端可对外暴露的映射地址：房客直接用打洞链路上已经采到的样本，
// 房主没有打洞链，就单独探一次（探不到就不注册——服务端要求 mappedIp 必须是公网 IP）。
func (e *connEngine) relaySelfMapped() *stunMappedAddr {
	e.mu.Lock()
	m := e.poolMapped
	nat := e.poolNatType
	e.mu.Unlock()
	if m != nil && m.valid() {
		return m
	}
	// 现场探测（房主路径 / 房客还没采到样本时）
	sock, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4zero, Port: 0})
	if err != nil {
		return nil
	}
	defer sock.Close()
	samples := stunSampleSeries(sock, stunServers, relayProbeServers, relayProbeTries, relayProbeTimeout)
	if len(samples) == 0 {
		return nil
	}
	m2 := samples[0]
	natType := natTypeFromSamples(samples, nat)
	e.mu.Lock()
	e.poolMapped = &m2
	e.poolNatType = natType
	e.mu.Unlock()
	return &m2
}

// natTypeFromSamples 把样本折算成服务端认的 natType 串：公网 IP 与端口都一致→cone，
// 漂移→symmetric，无样本→沿用旧值。
func natTypeFromSamples(samples []stunMappedAddr, fallback string) string {
	if len(samples) == 0 {
		if fallback == "" {
			return "unknown"
		}
		return fallback
	}
	// 样本不足两个推不出"映射端口会不会随目的地变化"，模组在这种情况报 unknown
	// （它的键取自 StunProbe 的 7 种细粒度结果，样本不够即 unknown；CM:2030-2036 注册的就是那个键）。
	// 旧实现把单样本也当成 delta=0 → "cone"，而 "cone" 不在服务端白名单（server-go/util.go:73-81），
	// 后台只能渲染成"未知·灰"，还会让自己以"优质锥型"身份进中继候选池被别人选中。
	if len(samples) < 2 {
		return "unknown"
	}
	// 判 cone 必须连公网 IP 一起比（StunProbe:927）：只看端口时，"两台服务器同端口、
	// 不同公网 IP"会被报成 cone —— 服务端候选池的门槛是"含 sym 的一律不进"
	// （relaymanager.go:343），于是这台对称 NAT 机器会以"好客"身份被别人选中当中继，
	// 而它的映射根本收不到别人自外向内的包。
	a, b := samples[0], samples[len(samples)-1]
	if strings.Contains(a.IP, ":") != strings.Contains(b.IP, ":") {
		// 混族（一台服务器给 v4、另一台给 v6）推不出结论，模组的说法是
		// "mixed IP family, skip symmetric compare"（CM:2225）——既然不比，就不能宣称锥型。
		return "unknown"
	}
	if a.IP == b.IP && stunDeltaFromSamples(samples) == 0 {
		// 键名按模组取值：两服务器比对"完全一致"在 StunProbe 里返回的是
		// PORT_RESTRICTED_CONE（:927-929），"full_cone" 只有走 RFC-5780 change-request
		// 行为探测才拿得到（:808）。这里没有做那次探测，所以不能冒领 full_cone——
		// 2026-10-11 生产大厅只读复查也对得上：9 个模组房上报的清一色 port_restricted_cone。
		// 服务端候选门槛只看"含 sym 的不进池"（relaymanager.go:343），两个键都合格。
		return "port_restricted_cone"
	}
	return "symmetric"
}

// registerRelayPeer 向候选池登记本端。relayEnabled 关掉时仍然注册但带 relayEnabled=false，
// 与模组一致：开关的语义由服务端候选过滤（roommanager 侧按 relayEnabled 选人）承担，
// 本端在"用别人中继"与"被别人中继"两处各自再判一次。
func (e *connEngine) registerRelayPeer() bool {
	id := e.relayPeerID()
	mapped := e.relaySelfMapped()
	if id == "" || mapped == nil || !mapped.valid() {
		return false
	}
	e.mu.Lock()
	code, token := e.code, e.token
	nat := e.poolNatType
	e.mu.Unlock()
	if code == "" || token == "" {
		return false
	}
	if nat == "" {
		nat = "unknown"
	}
	body := map[string]any{
		"clientId":     id,
		"roomCode":     code,
		"natType":      nat,
		"mappedIp":     mapped.IP,
		"mappedPort":   mapped.Port,
		"relayEnabled": e.app.relayEnabled(),
		"token":        token,
	}
	err := e.app.api.post(e.app.baseURL(), "/relay/register", body, nil)
	if err != nil {
		// 候选池注册失败不影响本端联机，只影响"别人能不能借我中继"——留痕但不升级为错误。
		e.app.netLog("warn", "中继候选池注册失败: "+relaySafeErr(err))
		return false
	}
	return true
}

// relaySafeErr 错误里可能带服务端地址，出口统一去掉（应用端安全纪律：日志永不出现公网 IP/节点地址）。
func relaySafeErr(err error) string {
	if err == nil {
		return ""
	}
	if ae, ok := err.(*APIError); ok {
		return ae.Code
	}
	return "NETWORK"
}

// unregisterRelayPeer 退房/关房时注销。注销失败无妨：服务端按 TTL 120s 自然回收，
// 但不注销会让候选池里留一个僵尸 peer，别人挑到它之后连接超时（0926 就踩过这一条）。
func (e *connEngine) unregisterRelayPeer() {
	id := e.relayPeerID()
	e.mu.Lock()
	code, token := e.code, e.token
	e.mu.Unlock()
	if id == "" || code == "" {
		return
	}
	_ = e.app.api.post(e.app.baseURL(), "/relay/unregister", map[string]any{
		"clientId": id,
		"roomCode": code,
		"token":    token,
	}, nil)
}

// startRelayPool 会话期注册循环：立刻试一次，然后每 60s 续约（模组 CM:11445 的自续节奏）。
// 续约门与模组同判据：会话仍在、本端还没连上（连上后不必再占候选额度）、且仍持有 peer 身份。
// 首次失败不放弃：房主侧映射地址可能要等 STUN 采样回来才拿得到。
func (e *connEngine) startRelayPool() {
	e.stopRelayPoolLoop()

	ctx, cancel := context.WithCancel(context.Background())
	e.mu.Lock()
	e.poolCancel = cancel
	e.mu.Unlock()

	go func() {
		t := time.NewTicker(relayRenewInterval)
		defer t.Stop()
		e.relayPoolTick()
		for {
			select {
			case <-ctx.Done():
				return
			case <-t.C:
				e.relayPoolTick()
			}
		}
	}()
}

// relayPoolTick 一拍：条件不满足就什么也不做（连上之后不再续约，映射地址没就绪就下一拍再试）。
func (e *connEngine) relayPoolTick() {
	e.mu.Lock()
	alive := e.sess != nil
	won := e.gP2PDone || e.rDone || e.turnActiveLocked()
	e.mu.Unlock()
	if !alive || won {
		return
	}
	e.registerRelayPeer()
}

// stopRelayPool 停循环并注销（幂等）。
func (e *connEngine) stopRelayPool() {
	e.mu.Lock()
	cancel := e.poolCancel
	e.poolCancel = nil
	e.mu.Unlock()
	if cancel != nil {
		cancel()
	}
	e.unregisterRelayPeer()
}

// stopRelayPoolLoop 只停循环不注销（会话已被服务端回收的场景用）。
func (e *connEngine) stopRelayPoolLoop() {
	e.mu.Lock()
	cancel := e.poolCancel
	e.poolCancel = nil
	e.mu.Unlock()
	if cancel != nil {
		cancel()
	}
}
