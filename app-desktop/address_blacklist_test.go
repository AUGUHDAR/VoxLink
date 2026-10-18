package main

// address_blacklist_test.go — AddressBlacklist 的阈值/窗口/去重与"只观测不拦截"性质。
// 同包内直接铺内部表状态来免掉真实等待（4s 去重窗 / 1h 与 5min 过期）。

import (
	"net"
	"strings"
	"sync"
	"testing"
	"time"
)

func blAddr(ip string, port int) *net.UDPAddr {
	return &net.UDPAddr{IP: net.ParseIP(ip), Port: port}
}

func TestAddressBlacklistThresholdAndDedupe(t *testing.T) {
	b := addressBlacklistGet()
	addr := blAddr("192.0.2.10", 40001)
	key := blacklistKey(addr)
	now := time.Now().UnixMilli()

	// 清干净可能来自其它用例的状态
	b.mu.Lock()
	delete(b.expireAt, key)
	delete(b.udpState, key)
	b.mu.Unlock()

	// 同一轮次（<4s）连打 5 次：只计 1 次（:51-53 的多 socket 放大防护）
	for i := 0; i < 5; i++ {
		b.recordUdpFailure(addr)
	}
	b.mu.Lock()
	st := b.udpState[key]
	b.mu.Unlock()
	if st[0] != 1 {
		t.Fatalf("同轮 5 次失败计成 %d 次，期望 1（SAME_ROUND_DEDUPE_MS=4000，:16）", st[0])
	}
	if b.isBlacklisted(addr) {
		t.Fatal("只计了 1 次就拉黑，阈值应为 3（UDP_FAIL_THRESHOLD，:10）")
	}

	// 手动把时戳推成"上一轮"：再失败两次 → 第 3 次触发拉黑 1 小时
	b.mu.Lock()
	st[2] = now - sameRoundDedupeMs - 1
	st[1] = now - 1000 // 仍在 600s 窗口内
	b.udpState[key] = st
	b.mu.Unlock()
	b.recordUdpFailure(addr) // 第 2 次
	if b.isBlacklisted(addr) {
		t.Fatal("2 次就拉黑了")
	}
	b.mu.Lock()
	st = b.udpState[key]
	st[2] = now - sameRoundDedupeMs - 1
	b.udpState[key] = st
	b.mu.Unlock()
	b.recordUdpFailure(addr) // 第 3 次 → 命中阈值
	if !b.isBlacklisted(addr) {
		t.Fatal("窗口内 3 次失败必须拉黑")
	}
	b.mu.Lock()
	exp := b.expireAt[key]
	_, counting := b.udpState[key]
	b.mu.Unlock()
	if got := exp - now; got < udpBlacklistMs || got > udpBlacklistMs+60000 {
		t.Fatalf("拉黑时长 %dms，期望 3600000ms 起（UDP_BLACKLIST_MS，:8；上界含计时误差）", got)
	}
	if counting {
		t.Fatal("命中阈值后应清掉计数状态（Java compute 返回 null，:59）")
	}
}

func TestAddressBlacklistWindowReset(t *testing.T) {
	b := addressBlacklistGet()
	addr := blAddr("192.0.2.11", 40002)
	key := blacklistKey(addr)
	now := time.Now().UnixMilli()

	b.mu.Lock()
	delete(b.expireAt, key)
	// 窗口起始在 600s 之前、且已有 2 次计数 —— 下一次失败要当新窗口重计（:46-48）
	b.udpState[key] = [3]int64{2, now - failWindowMs - 1, now - sameRoundDedupeMs - 1}
	b.mu.Unlock()

	b.recordUdpFailure(addr)
	b.mu.Lock()
	st := b.udpState[key]
	exp, blacklisted := b.expireAt[key]
	b.mu.Unlock()
	if blacklisted {
		t.Fatalf("跨窗口后应重计，不该拉黑（expireAt=%d）", exp)
	}
	if st[0] != 1 {
		t.Fatalf("新窗口计数 = %d，期望 1（FAIL_WINDOW_MS=600000，:11）", st[0])
	}
}

func TestAddressBlacklistDirectFailureFiveMinutesAndClearsCounter(t *testing.T) {
	b := addressBlacklistGet()
	addr := blAddr("192.0.2.12", 40003)
	key := blacklistKey(addr)
	now := time.Now().UnixMilli()

	b.mu.Lock()
	b.udpState[key] = [3]int64{2, now, now}
	delete(b.expireAt, key)
	b.mu.Unlock()

	b.recordDirectFailure(addr)

	b.mu.Lock()
	exp := b.expireAt[key]
	_, counting := b.udpState[key]
	b.mu.Unlock()
	if got := exp - now; got < directBlacklistMs || got > directBlacklistMs+60000 {
		t.Fatalf("直连失败拉黑时长 %dms，期望 300000ms（DIRECT_BLACKLIST_MS，:9；上界含计时误差）", got)
	}
	if counting {
		t.Fatal("直连失败要清零 UDP 计数（:70 remove）")
	}
	if !b.isBlacklisted(addr) {
		t.Fatal("刚记录的直连失败应处于拉黑期")
	}
}

func TestAddressBlacklistLazyExpire(t *testing.T) {
	b := addressBlacklistGet()
	addr := blAddr("192.0.2.13", 40004)
	key := blacklistKey(addr)
	b.mu.Lock()
	b.expireAt[key] = time.Now().UnixMilli() - 1 // 已过期
	b.mu.Unlock()
	if b.isBlacklisted(addr) {
		t.Fatal("过期后仍判拉黑")
	}
	b.mu.Lock()
	_, still := b.expireAt[key]
	b.mu.Unlock()
	if still {
		t.Fatal("isBlacklisted 应惰性删除过期项（:33）")
	}
}

// TestObserveBlacklistedTargetNeverBlocks 钉住源码性质："拉黑仅观测，不拦截照常打洞"
// （UdpHolePuncher.java:221-230）。这里断言两件事：函数无返回值可用作拦截判据、
// 命中只产生一条限频 warn，且不改动任何黑名单/计数状态。
func TestObserveBlacklistedTargetNeverBlocks(t *testing.T) {
	b := addressBlacklistGet()
	addr := blAddr("192.0.2.14", 40005)
	key := blacklistKey(addr)
	now := time.Now().UnixMilli()
	b.mu.Lock()
	b.expireAt[key] = now + udpBlacklistMs
	delete(b.udpState, key)
	delete(b.warnAt, key)
	b.mu.Unlock()

	var mu sync.Mutex
	var lines []string
	restore := captureStunLogForTest(func(level, msg string) {
		mu.Lock()
		lines = append(lines, level+"|"+msg)
		mu.Unlock()
	})
	defer restore()

	observeBlacklistedTarget(addr)
	observeBlacklistedTarget(addr) // 30s 内第二条要被限频（UdpHolePuncher.java:237）

	mu.Lock()
	got := append([]string(nil), lines...)
	mu.Unlock()
	if len(got) != 1 {
		t.Fatalf("告警条数 %d，期望限频后只剩 1 条：%v", len(got), got)
	}
	if !strings.Contains(got[0], "observation-only") {
		t.Fatalf("告警文案应表明仅观测，实得 %q", got[0])
	}
	if !strings.HasPrefix(got[0], "warn|") {
		t.Fatalf("应为 warn 级，实得 %q", got[0])
	}

	// 观测不得改变拉黑/计数状态
	b.mu.Lock()
	exp := b.expireAt[key]
	_, counting := b.udpState[key]
	b.mu.Unlock()
	if exp != now+udpBlacklistMs || counting {
		t.Fatal("observeBlacklistedTarget 改了状态：它只该读，不该写")
	}

	// 未拉黑目标：零日志、零副作用
	b.mu.Lock()
	delete(b.expireAt, key)
	b.mu.Unlock()
	mu.Lock()
	lines = nil
	mu.Unlock()
	observeBlacklistedTarget(addr)
	mu.Lock()
	n := len(lines)
	mu.Unlock()
	if n != 0 {
		t.Fatalf("目标未拉黑却产生 %d 条日志", n)
	}

	// 空目标：静默返回（:223-225）
	observeBlacklistedTarget(nil)
	observeBlacklistedTarget(&net.UDPAddr{IP: net.ParseIP("192.0.2.15"), Port: 0})
}

func TestAddressBlacklistNilAddressIgnored(t *testing.T) {
	b := addressBlacklistGet()
	b.recordUdpFailure(nil)
	b.recordDirectFailure(nil)
	if b.isBlacklisted(nil) {
		t.Fatal("nil 目标应恒判未拉黑（:26-28/:43/:68）")
	}
}
