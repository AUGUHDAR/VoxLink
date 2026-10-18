package main

// relay_v2_test.go — 玩家中继 v2 转发器（模组 RelayBridge.java 2026-10-05 重写）验收：
//   - 只做"流↔流"搬运，任一方向 EOF/出错即整个会话收尾、双腿互关（CAS 幂等）；
//   - 重复 relayKey 静默忽略（幂等屏障）、legA==legB 拒绝；
//   - 子通道腿收尾只 closeSubstream，绝不关父传输（候选者自己的 chan0 会话不能被连坐）；
//   - chan 1..7 轮转分配，0 永不参与。

import (
	"bytes"
	"io"
	"sync"
	"testing"
	"time"
)

// testStream 内存版 bridgeStream：读写两个方向分开记账，读端按模组读侧语义（短轮询 + abort）。
//
// 早先这里只有一个 queue：in() 从它取、out() 往它塞。于是"测试喂进去的 handshake"
// 会被 A→B 泵搬进对端的同一个 queue，而 B→A 泵又把那个 queue 当作自己的读侧搬回来——
// 两条泵在两个 queue 之间把同一批字节无限对倒。Windows 上靠时序碰巧收敛（断言赶在下一次
// 对倒之前看到字节），linux runner 上时序不同就露馅：B队列="loginhandshake"、A队列=""。
// 那是夹具自己造了个回环，不是转发器的缺陷，所以修的是这里。
type testStream struct {
	mu      sync.Mutex
	toPump  [][]byte // 测试喂给泵读侧的字节
	arrived [][]byte // 泵写出来的字节（断言读这里）
	eof     bool
	closes  int
	done    chan struct{}
}

func newTestStream() *testStream {
	return &testStream{done: make(chan struct{})}
}

func (s *testStream) in(p []byte, abort <-chan struct{}) (int, error) {
	for {
		s.mu.Lock()
		if len(s.toPump) > 0 {
			chunk := s.toPump[0]
			n := copy(p, chunk)
			if n == len(chunk) {
				s.toPump = s.toPump[1:]
			} else {
				s.toPump[0] = chunk[n:]
			}
			s.mu.Unlock()
			return n, nil
		}
		eof := s.eof
		s.mu.Unlock()
		if eof {
			return 0, io.EOF
		}
		select {
		case <-abort:
			return 0, errRudpAborted
		case <-time.After(5 * time.Millisecond):
		}
	}
}

func (s *testStream) out(p []byte) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.arrived = append(s.arrived, append([]byte(nil), p...))
	return len(p), nil
}

// feed 由测试调用：往这条腿的"来流"里放字节，等泵把它搬到对端。
func (s *testStream) feed(p []byte) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.toPump = append(s.toPump, append([]byte(nil), p...))
}

func (s *testStream) close() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.closes++
	s.eof = true
}

func (s *testStream) sendEOF() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.eof = true
}

func (s *testStream) readQueue() []byte {
	s.mu.Lock()
	defer s.mu.Unlock()
	var all []byte
	for _, chunk := range s.arrived {
		all = append(all, chunk...)
	}
	return all
}

func (s *testStream) closeCount() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.closes
}

func (s *testStream) leg() *relayLeg {
	return relayLegOf(&bridgeStream{
		in:     s.in,
		out:    s.out,
		closer: s.close,
		done:   s.done,
	})
}

func discardLog(string, string) {}

func newTestRelayBridge() *relayBridge {
	// 单例守卫（模组 getInstance(null) != null）：测试里先把它建起来，会话收尾才会摘表
	getRelayBridge(discardLog)
	return &relayBridge{logf: discardLog, active: map[string]*relaySession{}}
}

func waitFor(t *testing.T, timeout time.Duration, cond func() bool, msg string) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if cond() {
			return
		}
		time.Sleep(5 * time.Millisecond)
	}
	t.Fatalf("timeout: %s", msg)
}

// TestRelayBridgeV2ForwardsBothDirections 双向搬运 + 任一方向 EOF 即双腿互关。
func TestRelayBridgeV2ForwardsBothDirections(t *testing.T) {
	b := newTestRelayBridge()
	legA, legB := newTestStream(), newTestStream()
	b.startRelay("muxrelay-1-guest-9", "host-chan1", "guest-9", legA.leg(), legB.leg())

	legA.feed([]byte("handshake"))
	legB.feed([]byte("login"))
	// 首轮搬运单独等，超时就把"泵到底跑没跑"的证据打出来：
	// 这条用例在 linux runner 上红过一次，只有一句 timeout 的话谁也定位不了（Windows 本机复现不出）。
	deadline := time.Now().Add(5 * time.Second)
	for {
		if bytes.Contains(legB.readQueue(), []byte("handshake")) &&
			bytes.Contains(legA.readQueue(), []byte("login")) {
			break
		}
		if time.Now().After(deadline) {
			b.mu.Lock()
			sessions := len(b.active)
			var fwd bool
			if sess := b.active["muxrelay-1-guest-9"]; sess != nil {
				fwd = sess.forwarding.Load()
			}
			b.mu.Unlock()
			t.Fatalf("timeout: 双向字节未搬运; sessions=%d forwarding=%v A队列=%q B队列=%q A.closes=%d B.closes=%d",
				sessions, fwd, legA.readQueue(), legB.readQueue(), legA.closeCount(), legB.closeCount())
		}
		time.Sleep(5 * time.Millisecond)
	}

	// 到位之后再验一次"没有多余动作"：A 腿只应收到 B 发出的字节，反过来同理。
	// 泵要是把已经搬走的字节又搬回原腿（自我回环），玩家侧就是重复包/握手两次。
	time.Sleep(100 * time.Millisecond)
	if got := string(legA.readQueue()); got != "login" {
		t.Fatalf("A 腿只该收到 B 的字节，实际 %q", got)
	}
	if got := string(legB.readQueue()); got != "handshake" {
		t.Fatalf("B 腿只该收到 A 的字节，实际 %q", got)
	}

	// A 方向 EOF → 整个会话收尾，两条腿都被关一次
	legA.sendEOF()
	waitFor(t, 2*time.Second, func() bool {
		b.mu.Lock()
		defer b.mu.Unlock()
		return len(b.active) == 0
	}, "会话未从表里摘除")
	waitFor(t, time.Second, func() bool { return legA.closes+legB.closes >= 1 }, "腿未被关闭")
	if legB.closes != 1 {
		t.Fatalf("B 腿应被收尾恰好一次，closes=%d", legB.closes)
	}
	if legA.closes != 1 {
		t.Fatalf("A 腿应被关闭恰好一次（CAS 幂等），closes=%d", legA.closes)
	}
}

// TestRelayBridgeRejectsInvalidAndDuplicateKey 参数校验与重复 key 幂等屏障。
func TestRelayBridgeRejectsInvalidAndDuplicateKey(t *testing.T) {
	b := newTestRelayBridge()
	same := newTestStream().leg()

	b.startRelay("bad-key", "a", "b", nil, same)
	b.startRelay("", "a", "b", newTestStream().leg(), same)
	// legA == legB：同一对象双向泵会自己抢自己的字节，模组直接拒绝
	b.startRelay("self", "a", "b", same, same)
	if len(b.active) != 0 {
		t.Fatalf("非法参数不应建会话，active=%d", len(b.active))
	}

	legA, legB := newTestStream(), newTestStream()
	b.startRelay("muxrelay-2-guestA", "host-chan2", "guestA", legA.leg(), legB.leg())
	count := b.relayCountForPeer("guestA")
	if count != 1 {
		t.Fatalf("relayCountForPeer = %d, want 1", count)
	}
	if b.relayCountForPeer("nobody") != 0 {
		t.Fatal("无关对端计数应为 0")
	}

	// 重复 key：静默忽略（同一 key 起两套泵会互抢字节把流切碎）
	second := newTestStream().leg()
	b.startRelay("muxrelay-2-guestA", "host-chan2", "guestA", newTestStream().leg(), second)
	if len(b.active) != 1 {
		t.Fatalf("重复 relayKey 应被忽略，active=%d", len(b.active))
	}

	b.stopAllRelays()
	waitFor(t, 2*time.Second, func() bool { return len(b.active) == 0 }, "stopAllRelays 未清空")
	if legA.closes != 1 || legB.closes != 1 {
		t.Fatalf("stopAllRelays 应各关一次双腿: A=%d B=%d", legA.closes, legB.closes)
	}
}

// TestRelayBridgeV2OverMuxSubstream 真实 mux 传输上的 v2 接线：
// 被中继会话走 chan1，与候选者自己的 chan0 会话字节级隔离；
// 子通道 EOF 时双腿互关，但父传输必须活着继续承载 chan0。
func TestRelayBridgeV2OverMuxSubstream(t *testing.T) {
	if testing.Short() {
		t.Skip("short 模式跳过 socket 测试")
	}
	// 候选者 ↔ host（mux）
	candidateToHost, hostSide := startMuxPair(t)
	// 候选者 ↔ 被中继房客（整传输腿）
	candidateToGuest, relayedGuest := startRudpPair(t)

	b := newTestRelayBridge()
	b.startRelay("muxrelay-1-relayed-7", "host-chan1", "relayed-7",
		relayLegOf(rudpMuxRelayStream(candidateToHost, 1)),
		relayLegOf(rudpBridgeStream(candidateToGuest)))

	// host → 子通道 → 被中继房客
	if _, err := hostSide.substream(1).Write([]byte("mc-handshake")); err != nil {
		t.Fatal(err)
	}
	got, err := readSome(t, relayedGuest, 5*time.Second)
	if err != nil || !bytes.Equal(got, []byte("mc-handshake")) {
		t.Fatalf("被中继房客收到 %q err=%v", got, err)
	}
	// 反向
	if _, err := relayedGuest.Write([]byte("login-response")); err != nil {
		t.Fatal(err)
	}
	back, err := readSome(t, hostSide.getSubstreamInput(1), 5*time.Second)
	if err != nil || !bytes.Equal(back, []byte("login-response")) {
		t.Fatalf("host 子通道收到 %q err=%v", back, err)
	}

	// host 关掉子通道 → 会话收尾：双腿互关，专打传输关闭，父传输不受牵连
	hostSide.closeSubstream(1)
	waitFor(t, 3*time.Second, func() bool { return !candidateToGuest.IsConnected() }, "专打传输未被收尾")
	if !candidateToHost.IsConnected() {
		t.Fatal("子通道收尾把父传输（候选者自己的会话）连坐了")
	}
	if !candidateToHost.isMuxEnabled() {
		t.Fatal("父传输 mux 状态异常")
	}
	if !candidateToHost.isSubstreamEof(1) {
		t.Fatal("子通道未被标记 EOF")
	}

	// chan0 仍然可用（v2 的核心目的：两条 MC 会话互不干扰）
	if _, err := candidateToHost.Write([]byte("own-session")); err != nil {
		t.Fatalf("chan0 写失败: %v", err)
	}
	own, err := readSome(t, hostSide, 5*time.Second)
	if err != nil || !bytes.Equal(own, []byte("own-session")) {
		t.Fatalf("chan0 收到 %q err=%v", own, err)
	}
	if len(b.active) != 0 {
		t.Fatalf("会话应已从表里摘除，active=%d", len(b.active))
	}
}

// TestAllocateRelayMuxChan 1..7 轮转，0 永不参与（chan0 是本连接自有流）。
func TestAllocateRelayMuxChan(t *testing.T) {
	seen := map[int]bool{}
	for i := 0; i < 40; i++ {
		ch := allocateRelayMuxChan()
		if ch < 1 || ch > 7 {
			t.Fatalf("allocateRelayMuxChan = %d, want 1..7", ch)
		}
		seen[ch] = true
	}
	if len(seen) != 7 {
		t.Fatalf("轮转未覆盖 1..7: %v", seen)
	}
}

// TestRelayBridgeAbortUnblocksPump 会话收尾必须能把阻塞中的读侧叫醒：
// Go 没有 thread.interrupt，done 通道关闭后泵即刻退出（否则一条腿会挂到传输判死）。
func TestRelayBridgeAbortUnblocksPump(t *testing.T) {
	b := newTestRelayBridge()
	legA, legB := newTestStream(), newTestStream()
	b.startRelay("abort-test", "a", "b", legA.leg(), legB.leg())
	waitFor(t, time.Second, func() bool { return len(b.active) == 1 }, "会话未建立")

	b.stopAllRelays()
	waitFor(t, 2*time.Second, func() bool {
		return legA.closes == 1 && legB.closes == 1
	}, "双腿未在 stopAllRelays 里收尾")
}
