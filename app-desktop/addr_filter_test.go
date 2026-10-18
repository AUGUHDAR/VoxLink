package main

// 地址族过滤与同 CGNAT 判定（审计 C/D 条）。
//
// 模组的实测结论写在源码注释里："本端无全局IPv6时丢弃host发布的IPv6候选，
// 避免朝不可达地址空转47轮(QWA6D3/S822QF)"。app 的打洞 socket 只绑 IPv4，
// 而对端可能是跑在双栈上的模组房，它会把 IPv6 映射通告过来——没有这层过滤，
// 玩家看到的就是"连接中"一直转，中继兜底被一路推迟。

import (
	"encoding/json"
	"net/http"
	"strings"
	"testing"
	"time"
)

// withIPv6Connectivity 把 fbVerifyIPv6Connectivity 的 60s 缓存钉成固定值，
// 让过滤结果只取决于"本端有没有全局 IPv6"这个前提，而不是测试机的网卡。
func withIPv6Connectivity(t *testing.T, v bool) {
	t.Helper()
	fbIPv6Mu.Lock()
	prev, prevMs := fbIPv6Cached, fbIPv6CachedMs
	res := v
	fbIPv6Cached, fbIPv6CachedMs = &res, time.Now().UnixMilli()
	fbIPv6Mu.Unlock()
	t.Cleanup(func() {
		fbIPv6Mu.Lock()
		fbIPv6Cached, fbIPv6CachedMs = prev, prevMs
		fbIPv6Mu.Unlock()
	})
}

func TestFilterAddressFamilyCandidate(t *testing.T) {
	a := NewApp()
	a.settingsPath = ""
	e := a.engine

	t.Run("本端无全局IPv6时丢弃IPv6候选", func(t *testing.T) {
		withIPv6Connectivity(t, false)
		if got := e.filterAddressFamilyCandidate("2001:db8::1", 25565, "test"); got != "" {
			t.Fatalf("IPv6 候选应被丢弃，得到 %q", got)
		}
	})
	t.Run("本端有全局IPv6时保留IPv6候选", func(t *testing.T) {
		withIPv6Connectivity(t, true)
		if got := e.filterAddressFamilyCandidate("2001:db8::1", 25565, "test"); got != "2001:db8::1" {
			t.Fatalf("IPv6 候选应保留，得到 %q", got)
		}
	})
	t.Run("环回与 0. 地址一律丢弃", func(t *testing.T) {
		withIPv6Connectivity(t, true)
		for _, ip := range []string{"127.0.0.1", "0.0.0.0", "localhost"} {
			if got := e.filterAddressFamilyCandidate(ip, 25565, "test"); got != "" {
				t.Fatalf("%s 应被丢弃，得到 %q", ip, got)
			}
		}
	})
	t.Run("公网 IPv4 保留", func(t *testing.T) {
		withIPv6Connectivity(t, false)
		if got := e.filterAddressFamilyCandidate("45.67.89.40", 25565, "test"); got != "45.67.89.40" {
			t.Fatalf("公网 IPv4 应保留，得到 %q", got)
		}
	})
}

type capturedSignal struct {
	Type string         `json:"type"`
	To   string         `json:"to"`
	Data map[string]any `json:"data"`
}

// hostPunchInfoHarness 起一台假信令服，把 hostOnPunchInfo 发出去的信号收回来。
func hostPunchInfoHarness(t *testing.T, peer *hostPeer) func(map[string]any) []capturedSignal {
	t.Helper()
	var mu = make(chan struct{}, 1)
	mu <- struct{}{}
	var got []capturedSignal
	a := newRelayTestApp(t, func(w http.ResponseWriter, r *http.Request) {
		var s capturedSignal
		_ = json.NewDecoder(r.Body).Decode(&s)
		<-mu
		got = append(got, s)
		mu <- struct{}{}
		sendJSON(w, map[string]any{"success": true, "data": map[string]any{}})
	})
	e := a.engine
	e.mu.Lock()
	if e.hPeers == nil {
		e.hPeers = map[string]*hostPeer{}
	}
	e.hPeers[peer.id] = peer
	e.code, e.token, e.isHost = "HOST01", "host-token", true
	e.mu.Unlock()

	return func(data map[string]any) []capturedSignal {
		e.hostOnPunchInfo(peer.id, data)
		// sendSignal 是同步 HTTP，返回后已经落到 handler
		<-mu
		defer func() { mu <- struct{}{} }()
		out := append([]capturedSignal(nil), got...)
		got = nil
		return out
	}
}

func TestHostOnPunchInfoFamilyFilterAndSameCgnat(t *testing.T) {
	withIPv6Connectivity(t, false)

	t.Run("不可达 IPv6 候选：整条 punch_info 丢掉，不回 holepunch_mapped", func(t *testing.T) {
		peer := &hostPeer{id: "guest-9", hostMapped: &stunMappedAddr{IP: "45.67.89.40", Port: 21000}}
		send := hostPunchInfoHarness(t, peer)
		out := send(map[string]any{"joinerMappedIp": "2001:db8::1", "joinerMappedPort": 19000})
		for _, s := range out {
			if s.Type == "holepunch_mapped" {
				t.Fatalf("被过滤的候选不应回 holepunch_mapped：%+v", s)
			}
		}
		if peer.mapped != nil {
			t.Fatalf("被丢弃的 punch_info 不该写进 peer.mapped：%+v", peer.mapped)
		}
	})

	t.Run("同一公网 IP：判定同 CGNAT，阵列通告按 CM:4421 带出 hostLocalIp", func(t *testing.T) {
		if getLocalIP() == "" {
			t.Skip("这台机器没有默认路由出口（CI/无网卡环境），hostLocalIp 无从断言")
		}
		if lip := getLocalIP(); lip == "" {
			t.Skip("这台机器取不到局域网地址（无默认路由），hostLocalIp 断言无从谈起")
		}
		peer := &hostPeer{id: "guest-8", hostMapped: &stunMappedAddr{IP: "45.67.89.40", Port: 21000}}
		send := hostPunchInfoHarness(t, peer)
		out := send(map[string]any{"joinerMappedIp": "45.67.89.40", "joinerMappedPort": 19000})
		if !peer.sameCgnat {
			t.Fatal("两端映射同一公网 IP 必须判同 CGNAT（CM:3659-3665）")
		}
		for _, s := range out {
			if s.Type == "holepunch_mapped" {
				t.Fatal("模组把通告放在多 socket 阵列之后（CM:4389-4457），信令处理这条不该再发")
			}
		}
		// CM:4421 的门现在由阵列读：sameCgnat 或房客索要才发 hostLocalIp
		arr := &hostArrayResult{addrs: []*stunMappedAddr{{IP: "45.67.89.40", Port: 21000}}}
		p := hostMappedPayload(arr, false, peer.sameCgnat, peer.requestHostLocalIp)
		if lip, _ := p["hostLocalIp"].(string); lip == "" {
			t.Fatal("同 CGNAT 时必须把 hostLocalIp 交给房客（公网映射打不通，只有内网直连能用）")
		}
	})

	t.Run("既没索要也不同 CGNAT：不发 hostLocalIp", func(t *testing.T) {
		peer := &hostPeer{id: "guest-7", hostMapped: &stunMappedAddr{IP: "45.67.89.40", Port: 21000}}
		send := hostPunchInfoHarness(t, peer)
		send(map[string]any{"joinerMappedIp": "98.76.54.32", "joinerMappedPort": 19000})
		if peer.sameCgnat {
			t.Fatal("公网 IP 不同不该判同 CGNAT")
		}
		arr := &hostArrayResult{addrs: []*stunMappedAddr{{IP: "45.67.89.40", Port: 21000}}}
		p := hostMappedPayload(arr, false, peer.sameCgnat, peer.requestHostLocalIp)
		if _, ok := p["hostLocalIp"]; ok {
			t.Fatalf("CM:4421 只在索要或同 CGNAT 时才发 hostLocalIp，实际 %+v", p)
		}
		if hip, _ := p["hostMappedIp"].(string); strings.Contains(hip, "2001:") {
			t.Fatalf("通告不该带 IPv6：%+v", p)
		}
	})

	t.Run("房客索要 requestHostLocalIp：照发", func(t *testing.T) {
		if getLocalIP() == "" {
			t.Skip("这台机器没有默认路由出口（CI/无网卡环境），hostLocalIp 无从断言")
		}
		peer := &hostPeer{id: "guest-6", hostMapped: &stunMappedAddr{IP: "45.67.89.40", Port: 21000}}
		send := hostPunchInfoHarness(t, peer)
		send(map[string]any{"joinerMappedIp": "98.76.54.32", "joinerMappedPort": 19000, "requestHostLocalIp": true})
		if !peer.requestHostLocalIp {
			t.Fatal("CM:3993 要把索要和同 CGNAT 记进本 peer，通告才发得出局域网地址")
		}
		arr := &hostArrayResult{addrs: []*stunMappedAddr{{IP: "45.67.89.40", Port: 21000}}}
		p := hostMappedPayload(arr, false, peer.sameCgnat, peer.requestHostLocalIp)
		if lip, _ := p["hostLocalIp"].(string); lip == "" {
			t.Fatal("requestHostLocalIp=true 时必须带 hostLocalIp")
		}
	})
}
