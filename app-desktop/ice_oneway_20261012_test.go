package main

// 单向死自愈与 ICE 重启限次的行为用例（2026-10-12）。
// 断的都是"守卫真生效"：摘掉判死、摘掉冷却、摘掉上限，用例必须变红。

import (
	"net"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func udpPair(t *testing.T) (*rudpConn, *net.UDPConn) {
	t.Helper()
	a, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4zero, Port: 0})
	if err != nil {
		t.Fatal(err)
	}
	b, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4zero, Port: 0})
	if err != nil {
		t.Fatal(err)
	}
	ba := b.LocalAddr().(*net.UDPAddr)
	// 绑在 0.0.0.0 的 socket，LocalAddr 的 IP 段是零地址，不能当目标；显式用回环
	c := newRudp(a, &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1).To4(), Port: ba.Port})
	t.Cleanup(func() {
		c.Close()
		_ = b.Close()
	})
	c.started.Store(true)
	return c, b
}

func TestOneWayDeadClosesAfterFiveHeartbeatsAndFiresIceOnce(t *testing.T) {
	c, peer := udpPair(t)

	var iceCount atomic.Int32
	c.setOnIceRestartRequested(func() { iceCount.Add(1) })

	// 曾经双向通过（模组 heartbeatEverRx 闸门），之后一直收不到东西
	c.heartbeatEverRx = true
	now := time.Now().UnixMilli()
	c.lastHeartbeatMs = now - 60_000
	c.lastRecv.Store(now - 60_000)

	for i := 0; i < rudpHeartbeatFailLimit; i++ {
		c.heartbeatTick()
	}

	if !c.closed.Load() {
		t.Fatalf("连续 %d 拍零入站必须判单向死并关闭（RUDP:1146-1152）", rudpHeartbeatFailLimit)
	}
	if p := c.closeMsg.Load(); p == nil || !strings.Contains(*p, "单向死") {
		t.Fatalf("关闭原因要带上单向死，让上层按判死走换道泵纪律，得到 %v", p)
	}
	// 收帧：判死这一拍先发 RESTART 再关（RUDP:1149-1151），所以这一批里必须出现类型 10
	seen := map[byte]bool{}
	buf := make([]byte, 64)
	for {
		_ = peer.SetReadDeadline(time.Now().Add(400 * time.Millisecond))
		n, _, err := peer.ReadFrom(buf)
		if err != nil || n < 3 {
			break
		}
		if buf[0] == 0x56 && buf[1] == 0x4C {
			seen[buf[2]] = true
		}
	}
	if !seen[rudpTypeRestart] {
		t.Fatalf("判死前必须主动发 RESTART 帧（RUDP:1149/517-528），实际收到类型=%v", seen)
	}
	if iceCount.Load() != 1 {
		t.Fatalf("一次会话只允许触发一次 ICE 重启（RUDP:505 的 CAS），实际 %d 次", iceCount.Load())
	}
}

// 从未收进过一个字节的链路不在这里判死（RUDP:1146 的 heartbeatEverRx 闸门），
// 交给 24s/60s 两条规则，免得刚建好还没起流的合法链路被 5 拍掐掉。
func TestNeverReceivedIsNotOneWayKilled(t *testing.T) {
	c, _ := udpPair(t)
	c.heartbeatEverRx = false
	now := time.Now().UnixMilli()
	c.lastHeartbeatMs = now - 60_000
	c.lastRecv.Store(now - 60_000)
	for i := 0; i < rudpHeartbeatFailLimit+8; i++ {
		c.heartbeatTick()
	}
	if c.closed.Load() {
		t.Fatal("从未收过字节不应按单向死判死")
	}
}

// 这一拍收到过包就把轮次清零（RUDP:1143-1145）——不能累计到旧账上。
func TestHeartbeatStreakResetsOnAnyInbound(t *testing.T) {
	c, _ := udpPair(t)
	c.heartbeatEverRx = true
	now := time.Now().UnixMilli()
	c.lastHeartbeatMs = now - 60_000
	c.lastRecv.Store(now - 60_000)
	for i := 0; i < rudpHeartbeatFailLimit-1; i++ {
		c.heartbeatTick()
	}
	if c.heartbeatFailStreak.Load() != rudpHeartbeatFailLimit-1 {
		t.Fatalf("前置条件：轮次应已累计到 %d，得到 %d", rudpHeartbeatFailLimit-1, c.heartbeatFailStreak.Load())
	}
	rx := time.Now().UnixMilli()
	c.lastRecv.Store(rx)       // 这一拍收到过包
	c.lastHeartbeatMs = rx - 1 // 锚点早于入站时刻（模组判据是严格 lastRecv > lastHeartbeatMs）
	c.heartbeatTick()
	if c.heartbeatFailStreak.Load() != 0 {
		t.Fatal("有入站就必须把失败轮次清零")
	}
	if c.closed.Load() {
		t.Fatal("活链路不该被关闭")
	}
}

func TestIceRestartCooldownAndMaxAttempts(t *testing.T) {
	oldNow, oldCd, oldMax := iceRestartNowMs, iceRestartCooldown, iceRestartMaxAttempts
	defer func() { iceRestartNowMs, iceRestartCooldown, iceRestartMaxAttempts = oldNow, oldCd, oldMax }()

	base := int64(1_000_000)
	var clock atomic.Int64
	clock.Store(base)
	iceRestartNowMs = func() int64 { return clock.Load() }
	iceRestartCooldown = 5000 * time.Millisecond
	iceRestartMaxAttempts = 3

	_, e := bgEngine(t)
	e.mu.Lock()
	e.turnSession = &turnSession{sessionIDHex: "x"} // 有 TURN 会话时必须先静默拆净（CM:14256-14259）
	e.mu.Unlock()

	e.guestIceRestart()
	e.mu.Lock()
	first := e.iceRestartAttempts
	turnGone := e.turnSession == nil
	e.mu.Unlock()
	if first != 1 {
		t.Fatalf("首次触发应计一次，得到 %d", first)
	}
	if !turnGone {
		t.Fatal("ICE 重启前必须静默拆掉 TURN 会话，否则中继导演被残留会话锁死")
	}

	// 冷却期内再触发：一次都不许加（连 ice_restart 信令都不该发）
	for i := 0; i < 5; i++ {
		e.guestIceRestart()
	}
	e.mu.Lock()
	afterCooldown := e.iceRestartAttempts
	e.mu.Unlock()
	if afterCooldown != 1 {
		t.Fatalf("5 秒冷却内的触发必须被忽略，实际计到 %d", afterCooldown)
	}

	// 逐次跨过冷却：第 1/2/3 次放行，第 4 次起放弃（CM:14271-14275）
	for want := 2; want <= 3; want++ {
		clock.Add(iceRestartCooldown.Milliseconds() + 1)
		e.guestIceRestart()
		e.mu.Lock()
		got := e.iceRestartAttempts
		e.mu.Unlock()
		if got != want {
			t.Fatalf("第 %d 次重启应被放行，实际计数 %d", want, got)
		}
	}
	e.mu.Lock()
	before := e.iceRestartAttempts
	lastBefore := e.lastIceRestartMs
	e.mu.Unlock()
	if before != 3 {
		t.Fatalf("前置条件：应已用满 3 次，实际 %d", before)
	}
	// 第 4 次：CM:14271-14275 走到 "reached max, give up"，既不推进冷却锚点也不重启
	clock.Add(iceRestartCooldown.Milliseconds() + 1)
	e.guestIceRestart()
	e.mu.Lock()
	after := e.iceRestartAttempts
	lastAfter := e.lastIceRestartMs
	e.mu.Unlock()
	if lastAfter != lastBefore {
		t.Fatal("超出上限后不得更新冷却锚点（模组只在放行分支里 set lastIceRestartTimeMs），" +
			"否则放弃状态会被后续触发一直续命")
	}
	// 模组是先自增再判上限（attempt = incrementAndGet(); if (attempt > 3) give up），
	// 所以计数会走到 4，但不得放行、不得更新冷却锚点。
	if after != before+1 {
		t.Fatalf("上限判定应在自增之后（CM:14271），得到 %d→%d", before, after)
	}

	// 退房复位：计数与锚点归零（CM:14181-14183，由 RoomManager.java:886 的退房路径调用）
	sess := newSession(e.app, "AB12CD", "tok", false)
	e.bindSession(sess, "cid-1")
	e.unbindSession(sess)
	e.mu.Lock()
	resetAttempts, resetLast := e.iceRestartAttempts, e.lastIceRestartMs
	e.mu.Unlock()
	if resetAttempts != 0 || resetLast != 0 {
		t.Fatalf("会话结束必须归零 ICE 重启计数，得到 %d/%d", resetAttempts, resetLast)
	}
}
