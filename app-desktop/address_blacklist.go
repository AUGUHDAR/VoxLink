package main

// address_blacklist.go — 模组 network/AddressBlacklist.java（1.2.0，73 行）的移植。
//
// 语义（逐值抄录，禁止改动）：
//   - UDP 打洞失败：600s 窗口内累计 3 次 → 拉黑 1 小时（:8-11）
//   - 同轮去重：4s 内对同一目标的重复回调只计 1 次（:12-16 注释：一次轮次里多个
//     socket/子 puncher 会几乎同时失败，例如 EasySym 的 25 个子 socket，不去重就会单轮触发拉黑）
//   - 直连失败：直接拉黑 5 分钟并清零 UDP 计数（:9/:67-72）
//   - 到期即删（isBlacklisted 里惰性删除，:32-34 的 remove(addr, exp) 只在值未变时删）
//
// **只观测、不拦截**：这是源码写死的性质，必须原样保留 ——
//   - 模组里 isBlacklisted 的唯一调用点是 UdpHolePuncher.observeBlacklistedTarget
//     （UdpHolePuncher.java:222-230），方法注释就写着"拉黑仅观测: 不拦截照常打洞"（:221），
//     命中只打一条 warn，打洞照常发（调用点 :386/:923/:1129 与 ConnectionManager.java:7791）。
//   - 没有任何路径因为 isBlacklisted 而跳过/中止打洞。
//   - 告警限频：同一目标 30s 最多一条（UdpHolePuncher.java:232-240，热循环下曾单会话刷 7000 条）。
//
// 写入点（本端接线时按这些语义调用，仍然不得用来拦截）：
//   - recordUdpFailure：UdpHolePuncher.failAndRecord（:201-219）——只在
//     NO_RESPONSE / FIREWALL_DETECTED / PREDICTION_OFF 三种失败原因下计一次，
//     "可达但握手未完成的失败"不记（源码注释 :196-200，避免误伤抖动链路）。
//   - recordDirectFailure：ConnectionManager.java:6847（IPv4 直连 wave1 失败）、
//     :7182（ipv4Fallback.tryIpv4Direct 失败），两处都还要求"房间状态仍是当前会话"。

import (
	"fmt"
	"net"
	"sync"
	"time"
)

const (
	udpBlacklistMs    = int64(3600000) // AddressBlacklist.java:8 UDP_BLACKLIST_MS（1 小时）
	directBlacklistMs = int64(300000)  // :9 DIRECT_BLACKLIST_MS（5 分钟）
	udpFailThreshold  = int64(3)       // :10 UDP_FAIL_THRESHOLD
	failWindowMs      = int64(600000)  // :11 FAIL_WINDOW_MS（10 分钟）
	sameRoundDedupeMs = int64(4000)    // :16 SAME_ROUND_DEDUPE_MS（同轮去重窗口）

	blacklistWarnIntervalMs = int64(30000) // UdpHolePuncher.java:237 告警限频窗口
)

// addressBlacklist = AddressBlacklist（:17-19）：单例，两张表。
type addressBlacklist struct {
	mu       sync.Mutex
	expireAt map[string]int64    // 目标 → 过期时戳
	udpState map[string][3]int64 // [0]=窗口内失败次数 [1]=窗口起始 [2]=最近一次计入失败的时戳（:41 注释）
	warnAt   map[string]int64    // UdpHolePuncher.BLACKLIST_WARN_AT（:232-240）
}

var (
	addressBlacklistOnce sync.Once
	addressBlacklistIns  *addressBlacklist
)

// addressBlacklistGet = AddressBlacklist.get()（:21-23）。
func addressBlacklistGet() *addressBlacklist {
	addressBlacklistOnce.Do(func() {
		addressBlacklistIns = &addressBlacklist{
			expireAt: make(map[string]int64),
			udpState: make(map[string][3]int64),
			warnAt:   make(map[string]int64),
		}
	})
	return addressBlacklistIns
}

// blacklistKey 目标标识。Java 用 InetSocketAddress 的 equals（host+port）；
// 本端打洞目标一律是已解析的 IP（net.UDPAddr），所以用 IP:port 字符串等价。
func blacklistKey(addr *net.UDPAddr) string {
	if addr == nil {
		return ""
	}
	return net.JoinHostPort(addr.IP.String(), fmt.Sprint(addr.Port))
}

// isBlacklisted = isBlacklisted(InetSocketAddress)（:25-39）。
// 注意：调用方只能拿它打日志，不能拿它决定"跳过打洞"。
func (b *addressBlacklist) isBlacklisted(addr *net.UDPAddr) bool {
	if addr == nil {
		return false
	}
	key := blacklistKey(addr)
	now := time.Now().UnixMilli()
	b.mu.Lock()
	defer b.mu.Unlock()
	exp, ok := b.expireAt[key]
	if !ok {
		return false
	}
	if now >= exp {
		// Java 的 remove(key, value)：仍是同一过期值才删（:33）
		if b.expireAt[key] == exp {
			delete(b.expireAt, key)
		}
		return false
	}
	return true
}

// recordUdpFailure = recordUdpFailure（:41-65）。
func (b *addressBlacklist) recordUdpFailure(addr *net.UDPAddr) {
	if addr == nil {
		return
	}
	key := blacklistKey(addr)
	now := time.Now().UnixMilli()
	b.mu.Lock()
	defer b.mu.Unlock()
	st, ok := b.udpState[key]
	if !ok || now-st[1] > failWindowMs {
		st = [3]int64{0, now, 0}
	}
	// 同一轮次（短时间内的重复回调）只计一次，防止多 socket 扫描放大计数（:50-53）
	if now-st[2] < sameRoundDedupeMs {
		b.udpState[key] = st
		return
	}
	st[0]++
	st[2] = now
	if st[0] >= udpFailThreshold {
		b.expireAt[key] = now + udpBlacklistMs
		delete(b.udpState, key) // Java compute 返回 null
		return
	}
	b.udpState[key] = st
}

// recordDirectFailure = recordDirectFailure（:67-72）。
func (b *addressBlacklist) recordDirectFailure(addr *net.UDPAddr) {
	if addr == nil {
		return
	}
	key := blacklistKey(addr)
	b.mu.Lock()
	defer b.mu.Unlock()
	b.expireAt[key] = time.Now().UnixMilli() + directBlacklistMs
	delete(b.udpState, key)
}

// observeBlacklistedTarget = UdpHolePuncher.observeBlacklistedTarget（:221-230）。
// "拉黑仅观测: 不拦截照常打洞" —— 这里唯一的作用是打一条限频 warn。
func observeBlacklistedTarget(addr *net.UDPAddr) {
	if addr == nil || addr.Port <= 0 {
		return
	}
	if addressBlacklistGet().isBlacklisted(addr) {
		warnBlacklistedOnce(addr)
	}
}

// warnBlacklistedOnce = UdpHolePuncher.warnBlacklistedOnce（:232-240）：同一目标 30s 最多一条。
func warnBlacklistedOnce(addr *net.UDPAddr) {
	b := addressBlacklistGet()
	key := blacklistKey(addr)
	now := time.Now().UnixMilli()
	b.mu.Lock()
	last, seen := b.warnAt[key]
	if !seen || now-last >= blacklistWarnIntervalMs {
		b.warnAt[key] = now
		b.mu.Unlock()
		stunLogMu.RLock()
		fn := stunLogFn
		stunLogMu.RUnlock()
		fn("warn", "[Punch] target "+key+" blacklisted (observation-only), punching anyway")
		return
	}
	b.mu.Unlock()
}
