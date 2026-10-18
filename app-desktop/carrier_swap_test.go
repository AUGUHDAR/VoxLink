package main

// carrier_swap_test.go — joiner 侧载具热切 + 上行日志（模组 P2PBridge.java:913-1076 移植）的状态面测试：
// journal 的 nil/空切片语义分叉、溢出停账、换道重启（F4）、热切前置门与幂等、真实 loopback 传输上的
// 换载（种子写入 → 载具替换 → 旧载具解除暂停后关闭）。

import (
	"net"
	"testing"
)

// resetJoinerSwapState 用例间复位桥层热切/journal 全局态（对应 Java 静态字段，Go 测试须自清）。
func resetJoinerSwapState(t *testing.T) {
	t.Helper()
	joinerSwapMu.Lock()
	joinerServer = nil
	joinerPort = -1
	joinerCarrier.Store(nil)
	joinerBridgeRef = nil
	joinerPumpPaused.Store(false)
	joinerSessionAlive.Store(false)
	joinerSwapNotifyAllLocked() // close 旧唤醒通道并换新，避免上个用例的通道状态串味
	joinerSwapMu.Unlock()
	resetJoinerDownlink()
	journalMu.Lock()
	joinerUplinkJournal = nil
	joinerJournalActive.Store(false)
	joinerJournalOverflow.Store(false)
	journalMu.Unlock()
}

func TestJoinerJournalTakeNilVsEmpty(t *testing.T) {
	resetJoinerSwapState(t)

	// 从未开账：take 返回非 nil 空切片（PB:963-964 null → new byte[0]）
	got := takeJoinerUplinkJournal()
	if got == nil {
		t.Fatal("未开账 take 不得为 nil（nil 只属于溢出态）")
	}
	if len(got) != 0 {
		t.Fatalf("未开账 take 应为空, got %d bytes", len(got))
	}

	// 开账后未记字节：同样空而非 nil
	startJoinerJournal()
	got = takeJoinerUplinkJournal()
	if got == nil || len(got) != 0 {
		t.Fatalf("开账空账 take = %v, want 非 nil 空切片", got)
	}

	// 记账 → 快照；take 不停账（PB:960-968 + 黑名单 #4）
	journalUplink([]byte{0x10, 0x00, 0x05}, 0, 3)
	snap := takeJoinerUplinkJournal()
	if len(snap) != 3 || snap[0] != 0x10 {
		t.Fatalf("快照 = %x, want 3 字节握手前缀", snap)
	}
	journalUplink([]byte{0x68}, 0, 1)
	snap2 := takeJoinerUplinkJournal()
	if len(snap2) != 4 {
		t.Fatalf("take 后记账应继续（不停账）, got %d bytes", len(snap2))
	}

	// 快照不随后续写入变化（toByteArray 拷贝语义）
	if len(snap) != 3 {
		t.Fatalf("先前快照被后续写入污染")
	}
}

func TestJoinerJournalOverflow(t *testing.T) {
	resetJoinerSwapState(t)
	startJoinerJournal()

	big := make([]byte, 200000)
	journalUplink(big, 0, len(big))
	if got := takeJoinerUplinkJournal(); len(got) != 200000 {
		t.Fatalf("第一段记账 = %d, want 200000", len(got))
	}
	// 200000 + 100000 > 262144 → 溢出（PB:978-982）
	journalUplink(big, 0, 100000)
	if got := takeJoinerUplinkJournal(); got != nil {
		t.Fatalf("溢出后 take 必须为 nil（黑名单 #5）, got %d bytes", len(got))
	}
	if joinerJournalActive.Load() {
		t.Fatal("溢出即停账（PB:980 active=false）")
	}
	// 溢出后 journalUplink no-op
	journalUplink(big, 0, 10)
	if got := takeJoinerUplinkJournal(); got != nil {
		t.Fatal("溢出后不得再入账")
	}
	// 换道重启（startJoinerJournal）清溢出位——F4 的复位形态（PB:948-954）
	startJoinerJournal()
	if got := takeJoinerUplinkJournal(); got == nil || len(got) != 0 {
		t.Fatalf("重启后 take = %v, want 非 nil 空切片", got)
	}
	journalUplink([]byte("z"), 0, 1)
	if got := takeJoinerUplinkJournal(); len(got) != 1 || got[0] != 'z' {
		t.Fatalf("重启后记账 = %x, want z", got)
	}
}

func TestJoinerJournalInactiveNoop(t *testing.T) {
	resetJoinerSwapState(t)
	// 未开账（active=false）：journalUplink 整体 no-op（PB:971-973），且不得凭空建账
	journalUplink([]byte{1, 2, 3}, 0, 3)
	journalMu.Lock()
	created := joinerUplinkJournal != nil
	journalMu.Unlock()
	if created {
		t.Fatal("停账态 journalUplink 必须 no-op（需求 6）")
	}
	// stopJoinerJournal 只停账不清缓冲（PB:956-958）
	startJoinerJournal()
	journalUplink([]byte{9}, 0, 1)
	stopJoinerJournal()
	journalUplink([]byte{9}, 0, 1)
	if got := takeJoinerUplinkJournal(); len(got) != 1 {
		t.Fatalf("stop 后不得再记, got %d", len(got))
	}
}

func TestJoinerDownlinkFlag(t *testing.T) {
	resetJoinerSwapState(t)
	if hasJoinerDownlink() {
		t.Fatal("初值应为 false（PB:924）")
	}
	markJoinerDownlink()
	if !hasJoinerDownlink() {
		t.Fatal("mark 后应为 true")
	}
	resetJoinerDownlink()
	if hasJoinerDownlink() {
		t.Fatal("reset 后应为 false")
	}
}

func TestSwapJoinerCarrierGuards(t *testing.T) {
	resetJoinerSwapState(t)

	// newTransport == nil → false（PB:1004-1006）
	if swapJoinerCarrier(nil, nil) {
		t.Fatal("nil 传输必须返回 false")
	}
	// 无本地桥（joinerServer==nil）→ false（PB:1008-1010）
	if swapJoinerCarrier(&rudpConn{}, nil) {
		t.Fatal("无桥必须返回 false（需求 4）")
	}

	// 登记桥后：oldT == newTransport → 幂等 true，且不动任何状态（PB:1012-1014）
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	same := &rudpConn{}
	registerJoinerBridge(nil, ln, same)
	if !swapJoinerCarrier(same, nil) {
		t.Fatal("同载具应幂等返回 true")
	}
	if joinerPumpPaused.Load() {
		t.Fatal("同载具 no-op 不得留下暂停位")
	}
	unregisterJoinerBridge(nil, ln)
	joinerSwapMu.Lock()
	if joinerServer != nil || joinerPort != -1 || joinerSessionAlive.Load() {
		joinerSwapMu.Unlock()
		t.Fatal("摘除登记应复位 joinerServer/joinerPort/sessionAlive")
	}
	joinerSwapMu.Unlock()
}

func newLoopRudpPairForSwap(t *testing.T) (a, b *rudpConn) {
	t.Helper()
	sa, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 0})
	if err != nil {
		t.Fatal(err)
	}
	sb, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 0})
	if err != nil {
		t.Fatal(err)
	}
	a = newRudp(sa, sb.LocalAddr().(*net.UDPAddr))
	b = newRudp(sb, sa.LocalAddr().(*net.UDPAddr))
	t.Cleanup(func() {
		_ = a.Close()
		_ = b.Close()
	})
	return a, b
}

// TestSwapJoinerCarrierOnWire 换道全序列（PB:1003-1076）：旧载具在跑、新载具未 start、
// 种子 = 连接期上行日志快照。断言：成功返回、载具替换、新载具被 swap 内部 Start、
// 种子写入无错、旧载具在解除暂停后被关、journal 已整段重启（F4）。
func TestSwapJoinerCarrierOnWire(t *testing.T) {
	if testing.Short() {
		t.Skip("short 模式跳过 socket 测试")
	}
	resetJoinerSwapState(t)

	old, next := newLoopRudpPairForSwap(t)
	old.Start() // 旧载具：started && !closed，无积压 → 两阶段排干都直接放行

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	registerJoinerBridge(nil, ln, old)
	defer unregisterJoinerBridge(nil, ln)

	// 连接期记账：握手字节进 journal
	startJoinerJournal()
	handshake := []byte{0x10, 0x00, 0x05, 'h', 'i'}
	journalUplink(handshake, 0, len(handshake))
	seed := takeJoinerUplinkJournal()
	if len(seed) != len(handshake) {
		t.Fatalf("seed 快照 = %d bytes, want %d", len(seed), len(handshake))
	}

	if !swapJoinerCarrier(next, seed) {
		t.Fatal("swap 应成功")
	}
	if getJoinerCarrier() != next {
		t.Fatal("载具引用未替换")
	}
	if !next.started.Load() {
		t.Fatal("swap 必须 Start 新载具（PB:1049）")
	}
	if joinerPumpPaused.Load() {
		t.Fatal("swap 返回后泵必须解除暂停（PB:1064）")
	}
	if old.IsConnected() {
		t.Fatal("旧载具必须被关（PB:1066-1072）")
	}
	// F4：换道即重启 journal——take 得到的是非 nil 空账，旧种子不得残留
	got := takeJoinerUplinkJournal()
	if got == nil {
		t.Fatal("重启后的 journal 不该是溢出态")
	}
	if len(got) != 0 {
		t.Fatalf("二次换道的快照含旧种子（F4 回归）: %d bytes", len(got))
	}
	// 重启后立刻可记：二次换道只重放换道之后的新字节
	journalUplink([]byte("after"), 0, 5)
	if got := takeJoinerUplinkJournal(); string(got) != "after" {
		t.Fatalf("重启后记账 = %q, want after", got)
	}
}

// TestSwapNullSeedProceeds 溢出会话（seed=nil）换道仍执行，只是不重放（PB:1051 判空跳过）。
func TestSwapNullSeedProceeds(t *testing.T) {
	if testing.Short() {
		t.Skip("short 模式跳过 socket 测试")
	}
	resetJoinerSwapState(t)

	old, next := newLoopRudpPairForSwap(t)
	old.Start()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	registerJoinerBridge(nil, ln, old)
	defer unregisterJoinerBridge(nil, ln)

	if !swapJoinerCarrier(next, nil) {
		t.Fatal("nil 种子（溢出口）换道应照常执行")
	}
	if getJoinerCarrier() != next {
		t.Fatal("载具未替换")
	}
	// 溢出态本身被 startJoinerJournal 复位（PB:952）——与 swap 内 F4 重启一致
	if joinerJournalOverflow.Load() {
		t.Fatal("swap 重启 journal 后溢出位应清零")
	}
}

// TestRudpPendingPrimitives 载具热切的两条传输原语（ReliableUdpTransport.java:434-441）：
// isConnected = started && !closed；hasPendingOutbound = 未被确认队列非空。
func TestRudpPendingPrimitives(t *testing.T) {
	if testing.Short() {
		t.Skip("short 模式跳过 socket 测试")
	}
	sa, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 0})
	if err != nil {
		t.Fatal(err)
	}
	sb, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 0})
	if err != nil {
		t.Fatal(err)
	}
	a := newRudp(sa, sb.LocalAddr().(*net.UDPAddr))
	t.Cleanup(func() { _ = a.Close() })

	if a.isConnected() {
		t.Fatal("未 start 的载具不得算已连接（PB:1021 排干门）")
	}
	a.Start()
	if !a.isConnected() {
		t.Fatal("started 且未关 → isConnected")
	}
	if a.hasPendingOutbound() {
		t.Fatal("空 pending 不该算积压")
	}
	// 直接抹掉对端 socket（不走 rudpConn.Close，免得 DISCONNECT 帧反把本端关掉）：
	// 包发出去但永远等不到 ACK → pending 非空，正是排干循环要盯的状态。
	_ = sb.Close()
	if _, err := a.Write([]byte("drain me")); err != nil {
		t.Fatalf("write: %v", err)
	}
	if !a.hasPendingOutbound() {
		t.Fatal("未确认包必须让 hasPendingOutbound=true")
	}
}
