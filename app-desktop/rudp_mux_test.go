package main

// rudp_mux_test.go — mux 子通道（relayMuxV1）与死对端救援队列（F9）的行为验收：
//   - 线上字节：mux 模式 DATA 载荷 = [1字节chan][数据]，非 mux 模式逐字节保持历史格式；
//   - chan 0 与子通道之间不得串流（v1 中继数据面的病根就是两条会话挤一条有序流）；
//   - 1 字节载荷 = 该子通道 EOF；未注册的 1..7 早到 EOF 必须落位（F14），>7 视为垃圾；
//   - 熔断清窗不丢数据：积压进救援队列，且救援重传不受 didRetransmit 门控（否则 2..8 轮是死代码）。

import (
	"bytes"
	"io"
	"net"
	"testing"
	"time"
)

// newLocalUDP 本机监听一个 UDP 口（失败即 skip 掉 socket 类测试）。
func newLocalUDP(t *testing.T) *net.UDPConn {
	t.Helper()
	sock, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 0})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = sock.Close() })
	return sock
}

func udpAddrOfSock(t *testing.T, sock *net.UDPConn) *net.UDPAddr {
	t.Helper()
	addr, ok := sock.LocalAddr().(*net.UDPAddr)
	if !ok {
		t.Fatalf("local addr type = %T", sock.LocalAddr())
	}
	return addr
}

// startMuxPair 两端都 enableMux 后再 Start（模组要求 mux 只能在 start 前启用，
// 中途切换会让对端把 chan 字节当成 MC 数据读）。
func startMuxPair(t *testing.T) (a, b *rudpConn) {
	t.Helper()
	sa, sb := newLocalUDP(t), newLocalUDP(t)
	a = newRudp(sa, udpAddrOfSock(t, sb))
	b = newRudp(sb, udpAddrOfSock(t, sa))
	a.enableMux()
	b.enableMux()
	a.Start()
	b.Start()
	t.Cleanup(func() {
		_ = a.Close()
		_ = b.Close()
	})
	return a, b
}

// readSome 带超时的一次读（测试里避免永久阻塞）。
func readSome(t *testing.T, r io.Reader, timeout time.Duration) ([]byte, error) {
	t.Helper()
	type res struct {
		b   []byte
		err error
	}
	ch := make(chan res, 1)
	go func() {
		buf := make([]byte, 4096)
		n, err := r.Read(buf)
		if n > 0 {
			ch <- res{append([]byte(nil), buf[:n]...), err}
			return
		}
		ch <- res{nil, err}
	}()
	select {
	case got := <-ch:
		return got.b, got.err
	case <-time.After(timeout):
		t.Fatal("read timeout")
		return nil, nil
	}
}

// TestRudpMuxDataChunkWireFormat 逐字节核对：mux 分片前缀 chan，非 mux 完全不变。
func TestRudpMuxDataChunkWireFormat(t *testing.T) {
	if testing.Short() {
		t.Skip("short 模式跳过 socket 测试")
	}
	sa, sb := newLocalUDP(t), newLocalUDP(t)
	peer := udpAddrOfSock(t, sb)

	muxed := newRudp(sa, peer)
	muxed.enableMux()
	if !muxed.isMuxEnabled() {
		t.Fatal("isMuxEnabled 未随 enableMux 生效")
	}
	// 不 Start：手动置 started（等价模组 connected），避免 keepalive 混进抓包
	muxed.started.Store(true)
	if _, err := muxed.substream(3).Write([]byte("mc")); err != nil {
		t.Fatalf("substream write: %v", err)
	}

	buf := make([]byte, 1500)
	_ = sb.SetReadDeadline(time.Now().Add(2 * time.Second))
	n, _, err := sb.ReadFromUDP(buf)
	if err != nil {
		t.Fatalf("no DATA frame: %v", err)
	}
	frame, ok := rudpDecode(buf[:n])
	if !ok || frame.Type != rudpTypeData {
		t.Fatalf("frame = %+v ok=%v", frame, ok)
	}
	if want := []byte{3, 'm', 'c'}; !bytes.Equal(frame.Payload, want) {
		t.Fatalf("mux payload = %q, want chan 字节 0x03 前缀 + %q", frame.Payload, want[1:])
	}
	if n != 13+len(frame.Payload) {
		t.Fatalf("frame length = %d, want %d（13B DATA 头 + 载荷）", n, 13+len(frame.Payload))
	}

	// 非 mux 模式线上不得出现 chan 字节（互操作零影响的硬约束）
	plain := newRudp(sa, peer)
	plain.started.Store(true)
	if _, err := plain.Write([]byte("mc")); err != nil {
		t.Fatalf("plain write: %v", err)
	}
	_ = sb.SetReadDeadline(time.Now().Add(2 * time.Second))
	n, _, err = sb.ReadFromUDP(buf)
	if err != nil {
		t.Fatalf("no plain DATA frame: %v", err)
	}
	frame, ok = rudpDecode(buf[:n])
	if !ok || !bytes.Equal(frame.Payload, []byte("mc")) {
		t.Fatalf("non-mux payload = %q ok=%v, want 原样 \"mc\"", frame.Payload, ok)
	}
}

// TestRudpMuxSubstreamIsolation chan0 与子通道各自成流，互不串字节。
func TestRudpMuxSubstreamIsolation(t *testing.T) {
	if testing.Short() {
		t.Skip("short 模式跳过 socket 测试")
	}
	a, b := startMuxPair(t)

	if _, err := a.Write([]byte("MAIN")); err != nil {
		t.Fatal(err)
	}
	if _, err := a.substream(2).Write([]byte("RELAY")); err != nil {
		t.Fatal(err)
	}

	main, err := readSome(t, b, 5*time.Second)
	if err != nil || !bytes.Equal(main, []byte("MAIN")) {
		t.Fatalf("chan0 got %q err=%v, want MAIN", main, err)
	}
	relay, err := readSome(t, b.substream(2), 5*time.Second)
	if err != nil || !bytes.Equal(relay, []byte("RELAY")) {
		t.Fatalf("chan2 got %q err=%v, want RELAY", relay, err)
	}
}

// TestRudpMuxEarlyEofLands F14：EOF 早于本端注册也必须落位，等待方即刻见 EOF，
// 而不是挂在传输级判死上把整条 mux 传输连坐。chan>7 的野字节不落位。
func TestRudpMuxEarlyEofLands(t *testing.T) {
	if testing.Short() {
		t.Skip("short 模式跳过 socket 测试")
	}
	sa, sb := newLocalUDP(t), newLocalUDP(t)
	b := newRudp(sb, udpAddrOfSock(t, sa))
	b.enableMux()
	b.Start()
	defer func() { _ = b.Close() }()

	push := func(seq uint32, payload []byte) {
		frame := rudpEncode(rudpFrame{Type: rudpTypeData, Seq: seq, Ack: b.nextExpected.Load(), Payload: payload})
		udpSendTo(sa, frame, udpAddrOfSock(t, sb))
	}
	// seq0：chan 5 的早到 EOF（本端从没注册过 5）
	push(0, []byte{5})
	// 不能用 isSubstreamEof 当"落位了没"的等待条件：模组语义是 sc==null || sc.eof
	// （ReliableUdpTransport.java:177-181，"没这条通道"本就等同 EOF），它对未落位同样返回真。
	waitUntil(t, 2*time.Second, func() bool {
		b.recvMu.Lock()
		defer b.recvMu.Unlock()
		return b.subStreams[5] != nil && b.subStreams[5].eof
	}, "早到 EOF 未落位")
	b.recvMu.Lock()
	landed := b.subStreams[5] != nil && b.subStreams[5].eof
	b.recvMu.Unlock()
	if !landed {
		t.Fatal("早到 EOF 未落位：等待方会挂到传输级判死")
	}
	if _, err := readSome(t, b.getSubstreamInput(5), 2*time.Second); err != io.EOF {
		t.Fatalf("已落位的子通道读应立刻 EOF，got err=%v", err)
	}

	// seq1：chan 9 的 EOF 视为垃圾，不凭空建通道
	push(1, []byte{9})
	waitUntil(t, 2*time.Second, func() bool { return b.nextExpected.Load() >= 2 }, "seq1 未被投递")
	b.recvMu.Lock()
	garbage := b.subStreams[9]
	b.recvMu.Unlock()
	if garbage != nil {
		t.Fatal("chan>7 的 EOF 不应落位建通道")
	}

	// 非 mux 传输上 closeSubstream 是 no-op（线上没有 chan 语义）
	plain := newRudp(sa, udpAddrOfSock(t, sb))
	plain.started.Store(true)
	plain.closeSubstream(3)
	plain.sendMu.Lock()
	pendingAfter := len(plain.pending)
	plain.sendMu.Unlock()
	if pendingAfter != 0 {
		t.Fatalf("非 mux 模式 closeSubstream 不该发帧，pending=%d", pendingAfter)
	}
}

// TestRudpMuxCloseSubstreamPeerSeesEof 本端关子通道 → 对端读侧 EOF，父传输仍活着。
func TestRudpMuxCloseSubstreamPeerSeesEof(t *testing.T) {
	if testing.Short() {
		t.Skip("short 模式跳过 socket 测试")
	}
	a, b := startMuxPair(t)

	if _, err := a.substream(4).Write([]byte("tail")); err != nil {
		t.Fatal(err)
	}
	got, err := readSome(t, b.substream(4), 5*time.Second)
	if err != nil || !bytes.Equal(got, []byte("tail")) {
		t.Fatalf("got %q err=%v", got, err)
	}
	if err := a.substream(4).Close(); err != nil {
		t.Fatal(err)
	}
	if _, err := readSome(t, b.substream(4), 5*time.Second); err != io.EOF {
		t.Fatalf("对端应见 EOF，got err=%v", err)
	}
	if !b.isSubstreamEof(4) {
		t.Fatal("isSubstreamEof 应为 true")
	}
	// 关子通道绝不影响父传输与 chan0
	if !a.IsConnected() || !b.IsConnected() {
		t.Fatal("closeSubstream 把父传输连坐了")
	}
	if _, err := a.Write([]byte("after")); err != nil {
		t.Fatalf("chan0 写失败: %v", err)
	}
	main, err := readSome(t, b, 5*time.Second)
	if err != nil || !bytes.Equal(main, []byte("after")) {
		t.Fatalf("chan0 got %q err=%v", main, err)
	}
}

// TestRudpMuxChunkLenKeepsTurnCap TURN 路径的 1374 是给数据字节的预算，
// chan 字节缀在分片之后（模组 sendBytes 先算 chunkLen 再 +1），不是把预算吃掉 1。
func TestRudpMuxChunkLenKeepsTurnCap(t *testing.T) {
	sock := newLocalUDP(t)
	c := newRudp(sock, udpAddrOfSock(t, newLocalUDP(t)))
	c.setCodec(turnPathCodec{}) // codec 非 nil 即 TURN 路径
	c.enableMux()
	c.started.Store(true)
	if c.maxSendChunk() != rudpTurnMaxChunk {
		t.Fatalf("TURN 分块上限 = %d, want %d", c.maxSendChunk(), rudpTurnMaxChunk)
	}
	payload := bytes.Repeat([]byte{0x7A}, rudpTurnMaxChunk)
	if _, err := c.substream(1).Write(payload); err != nil {
		t.Fatalf("write: %v", err)
	}
	c.sendMu.Lock()
	var sizes []int
	for _, pp := range c.pending {
		sizes = append(sizes, len(pp.data))
	}
	c.sendMu.Unlock()
	if len(sizes) != 1 || sizes[0] != rudpTurnMaxChunk+1 {
		t.Fatalf("mux 分片长度 = %v, want 单个 %d（1374 数据 + 1 chan）", sizes, rudpTurnMaxChunk+1)
	}
}

// TestRudpDeadPeerRescueQueue F9：熔断清窗后积压进救援队列慢速重传，
// 且救援必须在 didRetransmit 门之外——pending 已被清空，写在里面轮次 2..8 全是死代码。
func TestRudpDeadPeerRescueQueue(t *testing.T) {
	if testing.Short() {
		t.Skip("short 模式跳过 socket 测试")
	}
	sa, sb := newLocalUDP(t), newLocalUDP(t)
	c := newRudp(sa, udpAddrOfSock(t, sb))
	c.started.Store(true)

	for _, p := range [][]byte{[]byte("A"), []byte("B"), []byte("C")} {
		if _, err := c.Write(p); err != nil {
			t.Fatal(err)
		}
	}

	// 熔断临界态：全程零收包（静默 23.5s，贴着 24s 判死之前）+ 已累计 19 轮重传
	stale := time.Now().Add(-23500 * time.Millisecond).UnixMilli()
	c.lastRecv.Store(stale)
	c.sendMu.Lock()
	for _, pp := range c.pending {
		pp.sendTime = time.Now().Add(-time.Minute)
	}
	c.deadPeerRounds = rudpDeadPeerBreakerRounds - 1
	c.deadPeerStreakStart = stale
	c.sendMu.Unlock()

	seqs := startDataSeqSink(t, sb)
	c.retransmitCheck()

	c.sendMu.Lock()
	rescueLen := len(c.rescue)
	pendingLen := len(c.pending)
	oldest := c.oldestUnacked
	next := c.nextSeq
	lastSeq := c.rescueLastSeq
	rounds := c.rescueRounds
	c.sendMu.Unlock()
	if rescueLen != 3 {
		t.Fatalf("救援队列 = %d, want 3（清窗不丢数据）", rescueLen)
	}
	if pendingLen != 0 {
		t.Fatalf("熔断应清空 pending，剩 %d", pendingLen)
	}
	// 基序号必须推进，否则写侧窗口判定永远阻塞（30s 后 transport stuck）
	if oldest != next {
		t.Fatalf("oldestUnacked = %d, want nextSeq %d", oldest, next)
	}
	if lastSeq != int64(next)-1 || rounds != 1 {
		t.Fatalf("救援段末尾 = %d rounds = %d, want %d/1", lastSeq, rounds, next-1)
	}
	if got := drainSeqs(seqs, 500*time.Millisecond); len(got) < 3 {
		t.Fatalf("首轮救援未发包: %v", got)
	}

	// 第二轮：pending 已空 → didRetransmit 恒 false，救援仍必须发包（本轮修复的验收点）
	c.sendMu.Lock()
	c.rescueLastResend = time.Now().Add(-rudpRescueInterval).UnixMilli() - 1000
	c.sendMu.Unlock()
	c.retransmitCheck()
	c.sendMu.Lock()
	rounds2 := c.rescueRounds
	c.sendMu.Unlock()
	if rounds2 != 2 {
		t.Fatalf("救援轮次 = %d, want 2（救援被 didRetransmit 门控住了）", rounds2)
	}
	if got := drainSeqs(seqs, 500*time.Millisecond); len(got) < 3 {
		t.Fatalf("第二轮救援未发包: %v", got)
	}

	// 累计 ack 越过救援段末尾 = 整段已被接收 → 停止救援
	c.processAck(next)
	c.sendMu.Lock()
	stillRescue := len(c.rescue)
	c.sendMu.Unlock()
	if stillRescue != 0 {
		t.Fatalf("ack 覆盖后救援队列应清空，剩 %d", stillRescue)
	}
	c.sendMu.Lock()
	c.rescueLastResend = 0
	c.sendMu.Unlock()
	c.retransmitCheck()
	if got := drainSeqs(seqs, 300*time.Millisecond); len(got) != 0 {
		t.Fatalf("救援已停却仍发包: %v", got)
	}
}

// TestRudpRescueRoundBudget 8 轮预算耗尽后不再发（真死链路的收敛行为同旧"直接丢弃"）。
func TestRudpRescueRoundBudget(t *testing.T) {
	if testing.Short() {
		t.Skip("short 模式跳过 socket 测试")
	}
	sa, sb := newLocalUDP(t), newLocalUDP(t)
	c := newRudp(sa, udpAddrOfSock(t, sb))
	c.started.Store(true)
	c.sendMu.Lock()
	c.rescue[7] = &rudpPending{data: []byte("Z"), sendTime: time.Now()}
	c.rescueLastSeq = 7
	c.rescueRounds = rudpRescueMaxRounds - 1
	c.rescueLastResend = 0
	c.sendMu.Unlock()

	seqs := startDataSeqSink(t, sb)
	c.retransmitCheck() // 第 8 轮（预算内最后一次）
	c.sendMu.Lock()
	rounds := c.rescueRounds
	c.sendMu.Unlock()
	if rounds != rudpRescueMaxRounds {
		t.Fatalf("轮次 = %d, want %d", rounds, rudpRescueMaxRounds)
	}

	c.sendMu.Lock()
	c.rescueLastResend = 0 // 强行满足 6s 间隔，验证预算耗尽后不再进发
	c.sendMu.Unlock()
	c.retransmitCheck()
	c.sendMu.Lock()
	rounds = c.rescueRounds
	c.sendMu.Unlock()
	if rounds != rudpRescueMaxRounds {
		t.Fatalf("超出 8 轮预算后仍在重传（rounds=%d）", rounds)
	}
	if got := drainSeqs(seqs, 300*time.Millisecond); len(got) != 1 {
		t.Fatalf("预算内应只发一轮救援包，收到 %v", got)
	}
}

// ---- 测试辅助 ----

func waitUntil(t *testing.T, timeout time.Duration, cond func() bool, msg string) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if cond() {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	if !cond() {
		t.Fatal("condition not met in time: " + msg)
	}
}

// startDataSeqSink 收集裸 socket 上收到的 DATA 帧序号。
func startDataSeqSink(t *testing.T, sb *net.UDPConn) chan uint32 {
	t.Helper()
	out := make(chan uint32, 512)
	go func() {
		buf := make([]byte, 1500)
		for {
			_ = sb.SetReadDeadline(time.Now().Add(3 * time.Second))
			n, _, err := sb.ReadFromUDP(buf)
			if err != nil {
				return
			}
			if f, ok := rudpDecode(buf[:n]); ok && f.Type == rudpTypeData {
				select {
				case out <- f.Seq:
				default:
				}
			}
		}
	}()
	return out
}

func drainSeqs(ch chan uint32, window time.Duration) []uint32 {
	var got []uint32
	deadline := time.After(window)
	for {
		select {
		case seq := <-ch:
			got = append(got, seq)
		case <-deadline:
			return got
		}
	}
}
