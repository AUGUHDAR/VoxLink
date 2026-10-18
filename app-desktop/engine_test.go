package main

// engine_test.go — 连接引擎单元与集成测试：
//   - rudp 帧编码/解码 roundtrip（含 FEC_XOR 字节布局）
//   - FEC XOR 组恢复（3+XOR → 缺失包还原）
//   - punch 控制包字节、56 4C 02 <nonce> 回包、双端打洞互通
//   - STUN 报文构造 + XOR-MAPPED-ADDRESS 解析
//   - netstat 解析器 + 真实本机 netstat 实测
//   - 本机两条 rudp 实例经 127.0.0.1 UDP 互跑数据/ACK/FEC/KEEPALIVE

import (
	"bytes"
	"crypto/rand"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"net"
	"os/exec"
	"runtime"
	"strings"
	"sync"
	"testing"
	"time"
)

// ---- rudp 帧编解码 ----

func TestRudpFrameEncodeDecodeRoundtrip(t *testing.T) {
	payload := []byte("hello voxlink rudp")
	frames := []rudpFrame{
		{Type: rudpTypePunch, Seq: 0x1234},
		{Type: rudpTypePunchAck, Seq: 0x5678},
		{Type: rudpTypeData, Seq: 42, Ack: 17, Payload: payload},
		{Type: rudpTypeAck, Seq: 0, Ack: 99},
		{Type: rudpTypeKeepalive, Seq: 0, Ack: 7},
		{Type: rudpTypeDisconnect},
		{Type: rudpTypeFecXor, Seq: 3, Payload: []byte{1, 2, 3, 4}, FecCount: 4, FecLengths: []int{10, 20, 30, 4}},
	}
	for _, f := range frames {
		enc := rudpEncode(f)
		got, ok := rudpDecode(enc)
		if !ok {
			t.Fatalf("decode failed for type %d", f.Type)
		}
		if got.Type != f.Type || got.Seq != f.Seq || got.Ack != f.Ack {
			t.Fatalf("roundtrip mismatch: want %+v got %+v", f, got)
		}
		if f.Type == rudpTypeData || f.Type == rudpTypeFecXor {
			if !bytes.Equal(got.Payload, f.Payload) {
				t.Fatalf("payload mismatch: want %v got %v", f.Payload, got.Payload)
			}
		}
		if f.Type == rudpTypeFecXor {
			if got.FecCount != f.FecCount || !equalInts(got.FecLengths, f.FecLengths) {
				t.Fatalf("fec metadata mismatch: want %v/%v got %v/%v", f.FecCount, f.FecLengths, got.FecCount, got.FecLengths)
			}
		}
	}
}

func equalInts(a, b []int) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

// TestRudpDataHeaderBytes 校验 DATA 帧头 13 字节布局（模组 ReliableUdpTransport.java sendDataPacket）。
func TestRudpDataHeaderBytes(t *testing.T) {
	enc := rudpEncode(rudpFrame{Type: rudpTypeData, Seq: 0x01020304, Ack: 0x0A0B0C0D, Payload: []byte{0xAA, 0xBB}})
	want := []byte{
		0x56, 0x4C, 0x03,
		0x01, 0x02, 0x03, 0x04, // seq BE32
		0x0A, 0x0B, 0x0C, 0x0D, // ack BE32
		0x00, 0x02, // payloadLen BE16
		0xAA, 0xBB,
	}
	if !bytes.Equal(enc, want) {
		t.Fatalf("DATA header bytes mismatch:\n got %x\nwant %x", enc, want)
	}
}

// TestRudpFecFrameBytes 校验 FEC_XOR 帧字节布局（模组 sendFecPacket 820-842 行）。
func TestRudpFecFrameBytes(t *testing.T) {
	xorPayload := []byte{0x11, 0x22, 0x33}
	enc := rudpEncode(rudpFrame{Type: rudpTypeFecXor, Seq: 5, Payload: xorPayload, FecCount: 4, FecLengths: []int{100, 200, 300, 3}})
	// 56 4C 09 | groupId=5(BE32) | 0 | xorLen=3(BE16) | count=4 | 0064 00C8 012C 0003 | payload
	want := []byte{
		0x56, 0x4C, 0x09,
		0x00, 0x00, 0x00, 0x05,
		0x00, 0x00, 0x00, 0x00,
		0x00, 0x03, 0x04,
		0x00, 0x64, 0x00, 0xC8, 0x01, 0x2C, 0x00, 0x03,
		0x11, 0x22, 0x33,
	}
	if !bytes.Equal(enc, want) {
		t.Fatalf("FEC frame bytes mismatch:\n got %x\nwant %x", enc, want)
	}
}

// TestRudpPunchAckFiveBytes rudp 侧收到 PUNCH 必须回 56 4C 02 00 00（5 字节）。
func TestRudpPunchAckFiveBytes(t *testing.T) {
	if got := rudpEncode(rudpFrame{Type: rudpTypePunchAck}); !bytes.Equal(got, []byte{0x56, 0x4C, 0x02, 0x00, 0x00}) {
		t.Fatalf("PUNCH_ACK must be 56 4C 02 00 00, got %x", got)
	}
}

// ---- punch 控制包 ----

func TestPunchControlBytes(t *testing.T) {
	// 明文 5 字节：56 4C + type + nonce(u16 BE)
	pkt := buildPunchControl(punchTypePunch, 0xBEEF, nil)
	want := []byte{0x56, 0x4C, 0x01, 0xBE, 0xEF}
	if !bytes.Equal(pkt, want) {
		t.Fatalf("PUNCH bytes mismatch: got %x want %x", pkt, want)
	}
	if typ, ok := parsePunchControl(pkt); !ok || typ != punchTypePunch {
		t.Fatalf("parse PUNCH failed: %d %v", typ, ok)
	}
	// ACK 回包：模组语义为"本端自己的 nonce"，不回显对端 nonce
	ack := buildPunchControl(punchTypePunchAck, 0x1111, nil)
	wantAck := []byte{0x56, 0x4C, 0x02, 0x11, 0x11}
	if !bytes.Equal(ack, wantAck) {
		t.Fatalf("ACK mismatch: got %x want %x", ack, wantAck)
	}
	// punchAuthV1：附 4 字节截断 HMAC-SHA256，共 9 字节，可校验可解析
	key := punchAuthDeriveDirectKey("ABCDEF", "joiner-1")
	if key == nil {
		t.Fatal("deriveDirectKey returned nil")
	}
	authPkt := buildPunchControl(punchTypePunch, 0xBEEF, key)
	if len(authPkt) != punchControlAuthLen {
		t.Fatalf("auth control must be %d bytes, got %d", punchControlAuthLen, len(authPkt))
	}
	if !punchAuthVerifyTrailer4(key, authPkt) {
		t.Fatal("auth trailer verify failed on freshly built packet")
	}
	if punchAuthVerifyTrailer4([]byte("wrong-key-32-bytes-long0padding!!"), authPkt) {
		t.Fatal("auth trailer must reject a wrong key")
	}
	// 非法包
	for _, bad := range [][]byte{
		{0x56},                      // 过短
		{0x57, 0x4C, 0x01, 0, 0},    // 魔数错
		{0x56, 0x4C, 0x09, 0, 0},    // 类型错
		{0x56, 0x4C, 0x01, 0},       // 长度不足
		{0x56, 0x4C, 0x01, 0, 0, 0}, // 长度既非 5 也非 9
	} {
		if _, ok := parsePunchControl(bad); ok {
			t.Fatalf("should reject %x", bad)
		}
	}
}

// TestPunchAckAndWinOnSocket 真实 socket：对端发 PUNCH → 本端回 5 字节 ACK（本端 nonce）
// 并单向判赢（模组 NIO 路径语义）。
func TestPunchAckAndWinOnSocket(t *testing.T) {
	if testing.Short() {
		t.Skip("short 模式跳过 socket 测试")
	}
	a, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 0})
	if err != nil {
		t.Fatal(err)
	}
	defer a.Close()
	b, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 0})
	if err != nil {
		t.Fatal(err)
	}
	defer b.Close()

	tb := b.LocalAddr().(*net.UDPAddr)
	p := newPuncher(a, nil, nil, nil)
	p.setTarget(tb)
	resCh := make(chan *punchResult, 1)
	go func() { resCh <- p.punchPrediction(tb.Port, 0, false) }()

	// 从 B 发 PUNCH（nonce 0x1234）
	punch := buildPunchControl(punchTypePunch, 0x1234, nil)
	if _, err := b.WriteToUDP(punch, a.LocalAddr().(*net.UDPAddr)); err != nil {
		t.Fatal(err)
	}
	// B 收到 5 字节 ACK（nonce 为本端生成，不做回显）。
	// 注意：A 的发包循环也在同时向 B 发 PUNCH，故读到 ACK 为止。
	buf := make([]byte, 64)
	_ = b.SetReadDeadline(time.Now().Add(4 * time.Second))
	var n int
	var typ byte
	for {
		var err error
		n, _, err = b.ReadFromUDP(buf)
		if err != nil {
			t.Fatalf("no ACK received: %v", err)
		}
		var ok bool
		typ, ok = parsePunchControl(buf[:n])
		if ok && typ == punchTypePunchAck {
			break
		}
		_ = b.SetReadDeadline(time.Now().Add(4 * time.Second))
	}
	if n != punchControlPlainLen {
		t.Fatalf("ACK must be %d bytes, got %d: %x", punchControlPlainLen, n, buf[:n])
	}
	// 单向判赢：收到 PUNCH 即成功
	select {
	case res := <-resCh:
		if res == nil || !res.success {
			t.Fatalf("punch should win on received PUNCH: %+v", res)
		}
		if res.recvPunch != 1 {
			t.Fatalf("recvPunch counter expected 1, got %d", res.recvPunch)
		}
		win, actual := p.win()
		if win != a {
			t.Fatal("winning socket should be the shared socket")
		}
		if actual == nil || actual.Port != tb.Port {
			t.Fatalf("actual remote addr mismatch: %v", actual)
		}
	case <-time.After(8 * time.Second):
		t.Fatal("punch did not complete")
	}
}

// TestPunchAuthDropsPlainPacket 启用 punchAuthV1 后，明文控制包必须被丢弃（不判赢）；
// 携带有效截断 MAC 的包才判赢。
func TestPunchAuthDropsPlainPacket(t *testing.T) {
	if testing.Short() {
		t.Skip("short 模式跳过 socket 测试")
	}
	a, _ := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 0})
	defer a.Close()
	b, _ := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 0})
	defer b.Close()

	key := punchAuthDeriveDirectKey("ZZZ999", "peer-x")
	profile := punchFastLane // 6s 超时，测试窗口可控
	params := punchParamsFromProfile(profile)
	params.timeoutMs = 1500
	params.sendInterval = 200
	tb := b.LocalAddr().(*net.UDPAddr)
	p := newPuncher(a, profile, params, key)
	p.setTarget(tb)
	resCh := make(chan *punchResult, 1)
	go func() { resCh <- p.punchPrediction(tb.Port, 0, false) }()

	// 明文 PUNCH：应被丢弃，不判赢
	if _, err := b.WriteToUDP(buildPunchControl(punchTypePunch, 0x1234, nil), a.LocalAddr().(*net.UDPAddr)); err != nil {
		t.Fatal(err)
	}
	select {
	case res := <-resCh:
		if res != nil && res.success {
			t.Fatal("plain packet must be dropped when auth is armed")
		}
	case <-time.After(2500 * time.Millisecond):
		t.Fatal("no result from puncher")
	}

	// 认证包：应判赢
	p2 := newPuncher(a, profile, params, key)
	p2.setTarget(tb)
	res2 := make(chan *punchResult, 1)
	go func() { res2 <- p2.punchPrediction(tb.Port, 0, false) }()
	if _, err := b.WriteToUDP(buildPunchControl(punchTypePunch, 0x4321, key), a.LocalAddr().(*net.UDPAddr)); err != nil {
		t.Fatal(err)
	}
	select {
	case res := <-res2:
		if res == nil || !res.success {
			t.Fatalf("authenticated packet should win: %+v", res)
		}
	case <-time.After(8 * time.Second):
		t.Fatal("authenticated packet did not win")
	}
}

// TestPunchTwoLocalSocketsBothWin 本机双打洞器互打，双向都应判胜。
func TestPunchTwoLocalSocketsBothWin(t *testing.T) {
	if testing.Short() {
		t.Skip("short 模式跳过 socket 测试")
	}
	a, _ := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 0})
	defer a.Close()
	b, _ := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 0})
	defer b.Close()

	ab := b.LocalAddr().(*net.UDPAddr)
	ba := a.LocalAddr().(*net.UDPAddr)
	pa := newPuncher(a, nil, nil, nil)
	pa.setTarget(ab)
	pb := newPuncher(b, nil, nil, nil)
	pb.setTarget(ba)

	var wg sync.WaitGroup
	oks := make([]bool, 2)
	wg.Add(2)
	go func() {
		defer wg.Done()
		res := pa.punchPrediction(ab.Port, 0, false)
		oks[0] = res != nil && res.success
	}()
	go func() {
		defer wg.Done()
		res := pb.punchPrediction(ba.Port, 0, false)
		oks[1] = res != nil && res.success
	}()
	done := make(chan struct{})
	go func() { wg.Wait(); close(done) }()
	select {
	case <-done:
	case <-time.After(30 * time.Second):
		t.Fatal("punch deadlock on loopback")
	}
	for i, ok := range oks {
		if !ok {
			t.Fatalf("puncher %d failed to win", i)
		}
	}
}

// TestPunchGroupKeepsSharedSocket 组模式（硬对称喷洒/易对称对轰）互打：双方都应判胜，
// 且 cancel() 绝不能关掉跨轮次复用的共享主 socket。
func TestPunchGroupKeepsSharedSocket(t *testing.T) {
	if testing.Short() {
		t.Skip("short 模式跳过 socket 测试")
	}
	a, _ := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 0})
	defer a.Close()
	b, _ := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 0})
	defer b.Close()

	profile := punchHardSym
	params := punchParamsFromProfile(profile)
	params.timeoutMs = 4000
	params.hardSymSpray = false // 本机回环用 classic 组语义即可验证 socket 生命周期
	params.easySymBomb = false

	ab := b.LocalAddr().(*net.UDPAddr)
	ba := a.LocalAddr().(*net.UDPAddr)
	pa := newPuncher(a, profile, params, nil)
	pa.setTarget(ab)
	pb := newPuncher(b, profile, params, nil)
	pb.setTarget(ba)

	var wg sync.WaitGroup
	oks := make([]bool, 2)
	wg.Add(2)
	go func() {
		defer wg.Done()
		res := pa.punchGroup(makePunchSocketGroup(a, 4), 0)
		oks[0] = res != nil && res.success
	}()
	go func() {
		defer wg.Done()
		res := pb.punchGroup(makePunchSocketGroup(b, 4), 0)
		oks[1] = res != nil && res.success
	}()
	done := make(chan struct{})
	go func() { wg.Wait(); close(done) }()
	select {
	case <-done:
	case <-time.After(25 * time.Second):
		t.Fatal("group punch deadlock on loopback")
	}
	for i, ok := range oks {
		if !ok {
			t.Fatalf("group puncher %d failed to win", i)
		}
	}
	// cancel 后共享 socket 必须仍可用（下一轮次要继续复用）
	pa.cancel()
	pb.cancel()
	if _, err := a.WriteToUDP([]byte("ping"), ab); err != nil {
		t.Fatalf("shared socket A must stay open after cancel: %v", err)
	}
	if _, err := b.WriteToUDP([]byte("ping"), ba); err != nil {
		t.Fatalf("shared socket B must stay open after cancel: %v", err)
	}
	// makePunchSocketGroup 首元素必须是共享 socket
	if g := makePunchSocketGroup(a, 3); len(g) != 3 || g[0] != a {
		t.Fatal("socket group must lead with the shared socket")
	}
}

// ---- 打洞引擎常量与算法（docs/launcher-integration.md §7 验收表）----

func TestPunchTunerConstants(t *testing.T) {
	// §7.1 PunchTuner 9 常量
	if tunerMaxPortRange != 500 || tunerMaxTimeoutMs != 30000 || tunerMinSendIntervalMs != 50 ||
		tunerLateCycleTimeoutMs != 5000 || tunerAckRetriesOnTimeout != 3 ||
		tunerPredictionDeltaThreshold != 100 || tunerPortRangeMultiplier != 2 ||
		tunerTimeoutIncrementMs != 4000 || tunerSendIntervalDivisor != 2 {
		t.Fatal("PunchTuner constants drifted")
	}

	// NO_RESPONSE：portRange×2 封顶 500、timeout+4000 封顶 30000、minRounds/minPass=3
	p := tunerNextParams(punchDefault, natCone, natCone, 0, punchDefault.maxCycles,
		ptrFailure(failureNoResponse), nil)
	if p.portRange != 200 || p.timeoutMs != 16000 || p.sendMinRounds != 3 || p.sendMinPass != 3 {
		t.Fatalf("NO_RESPONSE tuning mismatch: %+v", p)
	}
	// 上限保护
	p2 := punchParamsFromProfile(punchWideSweep) // portRange=800
	_ = p2
	pMax := tunerNextParams(punchHardSym, natHardSym, natHardSym, 0, punchHardSym.maxCycles,
		ptrFailure(failureNoResponse), nil)
	if pMax.portRange > tunerMaxPortRange || pMax.timeoutMs > tunerMaxTimeoutMs {
		t.Fatalf("tuner caps violated: %+v", pMax)
	}

	// PREDICTION_OFF：切 HARDSYM 配方 + 扩窗
	pOff := tunerNextParams(punchDefault, natCone, natCone, 0, punchDefault.maxCycles,
		ptrFailure(failurePredictionOff), nil)
	if !pOff.hardSymSpray || pOff.sendInterval != sendWide.intervalMs {
		t.Fatalf("PREDICTION_OFF must switch to HARDSYM recipe: %+v", pOff)
	}
	if pOff.portRange != minInt(punchHardSym.portPredictionMaxRange*tunerPortRangeMultiplier, tunerMaxPortRange) {
		t.Fatalf("PREDICTION_OFF portRange mismatch: %d", pOff.portRange)
	}

	// RESPONSE_NO_ACK：发包间隔减半、下限 50
	pNoAck := tunerNextParams(punchWeakProfileForTest(), natCone, natCone, 0, 8,
		ptrFailure(failureResponseNoAck), nil)
	if pNoAck.sendInterval != 125 || pNoAck.sendMinPass != 3 {
		t.Fatalf("RESPONSE_NO_ACK tuning mismatch: %+v", pNoAck)
	}

	// FIREWALL_DETECTED：跳过直连发包
	pFw := tunerNextParams(punchDefault, natCone, natCone, 0, punchDefault.maxCycles,
		ptrFailure(failureFirewallDetected), nil)
	if !pFw.skipDirectPunch {
		t.Fatal("FIREWALL_DETECTED must set skipDirectPunch")
	}

	// ACK_TIMEOUT：ackRetries=3
	pAck := tunerNextParams(punchDefault, natCone, natCone, 0, punchDefault.maxCycles,
		ptrFailure(failureAckTimeout), nil)
	if pAck.ackRetries != 3 || pAck.sendMinPass != 3 {
		t.Fatalf("ACK_TIMEOUT tuning mismatch: %+v", pAck)
	}

	// PARTIAL_SUCCESS：复用成功 socket + 成功端口区间
	part := &punchResult{success: true, predictionDelta: 42, elapsedMs: 100}
	pPart := tunerNextParams(punchDefault, natCone, natCone, 0, punchDefault.maxCycles,
		ptrFailure(failurePartialSuccess), part)
	if !pPart.reuseSuccessfulSockets || pPart.successfulPortRange != [2]int{37, 47} {
		t.Fatalf("PARTIAL_SUCCESS tuning mismatch: %+v", pPart)
	}

	// 后期轮次限制：cycle >= maxCycles-2 且 timeout>5000 → timeout 收缩
	pLate := tunerNextParams(punchDefault, natCone, natCone, punchDefault.maxCycles-2, punchDefault.maxCycles,
		nil, nil)
	if pLate.timeoutMs != 9000 {
		t.Fatalf("late-cycle timeout expected 12000*3/4=9000, got %d", pLate.timeoutMs)
	}
}

func punchWeakProfileForTest() *punchProfile {
	// sendInterval=250（SEND_WEAK 模板）→ 减半 125
	return punchReliableCone
}

func ptrFailure(r punchFailureReason) *punchFailureReason { return &r }

func TestPunchProfileTemplates(t *testing.T) {
	// §7.2 五套发包模板（11 参数逐值）
	type row struct {
		name string
		got  sendParams
		want sendParams
	}
	rows := []row{
		{"SEND_DEFAULT", sendDefault, sendParams{200, 500, 1000, 2000, 600, 200, 3, 3, 1, 10, 800}},
		{"SEND_DEFAULT_FAST", sendDefaultFast, sendParams{200, 500, 1000, 2000, 600, 200, 1, 2, 1, 5, 800}},
		{"SEND_SPRINT", sendSprint, sendParams{100, 300, 600, 1200, 400, 150, 1, 1, 1, 3, 400}},
		{"SEND_WIDE", sendWide, sendParams{150, 500, 1000, 2000, 500, 200, 1, 2, 1, 5, 800}},
		{"SEND_WEAK", sendWeak, sendParams{250, 800, 1500, 3000, 500, 250, 3, 3, 2, 8, 600}},
	}
	for _, r := range rows {
		if r.got != r.want {
			t.Fatalf("%s template drifted: got %+v want %+v", r.name, r.got, r.want)
		}
	}

	// §7.3 SymParams RECIPE（11 值，含 maxPps=3000）
	want := symParams{25, 20, 100, 5000, 600, 800, 3, 1, 2, 180, 3000}
	if punchRecipe != want {
		t.Fatalf("RECIPE drifted: %+v", punchRecipe)
	}
	if punchDefault.sym.maxPps != 3000 {
		t.Fatal("all profiles must carry RECIPE maxPps=3000")
	}

	// 档位关键字段（PunchProfile.java 静态实例抽样）
	if punchDefault.punchTimeoutMs != 12000 || punchDefault.firewallDetectCycles != 30 ||
		punchDefault.portPredictionMaxRange != 100 || punchDefault.maxCycles != 8 {
		t.Fatalf("DEFAULT profile drifted: %+v", punchDefault)
	}
	if punchHardSym.portPredictionMaxRange != 500 || punchHardSym.punchTimeoutMs != 30000 ||
		punchHardSym.hardSymSocketCount != 84 {
		t.Fatalf("HARDSYM profile drifted: %+v", punchHardSym)
	}
	if punchEasySymDual.easySymDualSocketCount != 25 || punchEasySymDual.easySymDualPortRange != 50 {
		t.Fatalf("EASY_SYM_DUAL profile drifted: %+v", punchEasySymDual)
	}
	if punchWideSweep.portPredictionMaxRange != 800 || punchWideSweep.punchTimeoutMs != 35000 {
		t.Fatalf("WIDE_SWEEP profile drifted: %+v", punchWideSweep)
	}
}

func TestPortPredictor(t *testing.T) {
	// §7.4 单样本：直接用该端口，range=200
	if r := predict([]int{25565}); r.predictedPort != 25565 || r.rangeHalf != 200 || r.strategy != "single_sample" {
		t.Fatalf("single-sample predict mismatch: %+v", r)
	}
	// 空样本
	if r := predict(nil); r.predictedPort != -1 || r.rangeHalf != 200 || r.strategy != "no_samples" {
		t.Fatalf("no-sample predict mismatch: %+v", r)
	}
	// 置信范围按样本数
	for _, tc := range []struct{ n, want int }{{10, 32}, {11, 32}, {5, 64}, {6, 64}, {3, 100}, {4, 100}, {2, 200}} {
		ports := make([]int, tc.n)
		for i := range ports {
			ports[i] = 20000 + i // 递增样本，预测值恒 >0
		}
		if r := predict(ports); r.rangeHalf != tc.want {
			t.Fatalf("confidence range n=%d expected ±%d got ±%d", tc.n, tc.want, r.rangeHalf)
		}
	}
	// 线性回归：严格 +100/样本 → 预测下一个
	ports := []int{20000, 20100, 20200, 20300, 20400}
	if got := linearRegressionPredict(ports); got != 20500 {
		t.Fatalf("linear regression expected 20500, got %d", got)
	}
	// delta 预测：末样本 + 平滑 delta
	if got := deltaPredict(ports); got != 20500 {
		t.Fatalf("delta predict expected 20500, got %d", got)
	}
	// 融合：lr×0.6 + delta×0.4（两者一致时等于自身）
	r := predict(ports)
	if r.predictedPort != 20500 || r.strategy != "combined" {
		t.Fatalf("combined predict mismatch: %+v", r)
	}
	// 钳制下限 1024
	small := []int{500, 400, 300}
	if pr := predict(small); pr.predictedPort < 1024 {
		t.Fatalf("prediction must clamp to >=1024, got %d", pr.predictedPort)
	}

	// generateTargetPorts：预测值在最前，逐偏移上下交替，且不越 [1024,65535]
	targets := generateTargetPorts(20000, 3)
	wantSeq := []int{20000, 20001, 19999, 20002, 19998, 20003, 19997}
	if len(targets) != len(wantSeq) {
		t.Fatalf("target count mismatch: %d vs %d", len(targets), len(wantSeq))
	}
	for i := range wantSeq {
		if targets[i] != wantSeq[i] {
			t.Fatalf("targets[%d]=%d want %d", i, targets[i], wantSeq[i])
		}
	}
	edge := generateTargetPorts(1025, 200)
	for _, p := range edge {
		if p < 1024 || p > 65535 {
			t.Fatalf("target port out of range: %d", p)
		}
	}
}

func TestNatClassAndStrategyMatrix(t *testing.T) {
	// §7 派生：NAT 分级（StunProbe 两服务器比对，927-929 判 cone、986-1000 判 easy/hard）
	sa := func(ip string, port int) stunMappedAddr { return stunMappedAddr{IP: ip, Port: port} }
	if got := natClassFromDelta([]stunMappedAddr{sa("1.1.1.1", 20000), sa("1.1.1.1", 20000)}); got != natCone {
		t.Fatalf("同 IP 同端口必须是 cone, got %v", got)
	}
	if got := natClassFromDelta([]stunMappedAddr{sa("1.1.1.1", 20000), sa("1.1.1.1", 20050)}); got != natEasySym {
		t.Fatalf("同 IP、|delta|<100 才是 easy sym, got %v", got)
	}
	if got := natClassFromDelta([]stunMappedAddr{sa("1.1.1.1", 20000), sa("1.1.1.1", 19850)}); got != natHardSym {
		t.Fatalf("|delta|>=100 must be hard sym, got %v", got)
	}
	if got := natClassFromDelta([]stunMappedAddr{sa("1.1.1.1", 20000)}); got != natUnknown {
		t.Fatalf("single sample must be unknown, got %v", got)
	}
	// 这两条是本轮补的判据：只看端口时都会往"好打"的方向错。
	if got := natClassFromDelta([]stunMappedAddr{sa("1.1.1.1", 20000), sa("2.2.2.2", 20000)}); got != natHardSym {
		t.Fatalf("端口相同但公网 IP 不同：模组判对称(StunProbe:927 要 IP 也相等), got %v", got)
	}
	if got := natClassFromDelta([]stunMappedAddr{sa("1.1.1.1", 20000), sa("2.2.2.2", 20050)}); got != natHardSym {
		t.Fatalf("IP 不同一律 hard(StunProbe:986-988 先拦 IP，不看 delta), got %v", got)
	}
	// 阈值：两个端口都有效才是 100，否则 200（StunProbe:990）。
	if got := natClassFromDelta([]stunMappedAddr{sa("1.1.1.1", 20000), sa("1.1.1.1", 20150)}); got != natHardSym {
		t.Fatalf("同 IP、|delta|=150 在 100 阈值下是 hard, got %v", got)
	}

	// 档位推荐矩阵抽样
	if got := recommendProfile(natUnknown, natCone); got != punchAggressive {
		t.Fatalf("unknown must fall to AGGRESSIVE, got %s", got.name)
	}
	if got := recommendProfile(natHardSym, natHardSym); got != punchHardSym {
		t.Fatalf("hard×hard must be HARDSYM, got %s", got.name)
	}
	if got := recommendProfile(natEasySym, natHardSym); got != punchHardSym {
		t.Fatalf("easy×hard must be HARDSYM, got %s", got.name)
	}
	if got := recommendProfile(natEasySym, natEasySym); got != punchEasySymDual {
		t.Fatalf("easy×easy must be EASY_SYM_DUAL, got %s", got.name)
	}
	if got := recommendProfile(natCone, natCone); got != punchFastLane {
		t.Fatalf("cone×cone must be FAST_LANE, got %s", got.name)
	}
	if got := recommendProfile(natCone, natEasySym); got != punchV100 {
		t.Fatalf("cone×easy must be V100, got %s", got.name)
	}
	if got := recommendProfile(natCone, natHardSym); got != punchAggressive {
		t.Fatalf("cone×hard must be AGGRESSIVE, got %s", got.name)
	}

	// 策略矩阵抽样
	if got := selectStrategy(natCone, natCone, 0, false); got != strategyDirectOnly {
		t.Fatalf("cone×cone must be DIRECT_ONLY, got %d", got)
	}
	if got := selectStrategy(natCone, natHardSym, 0, false); got != strategyReverseOnly {
		t.Fatalf("cone×hard cycle0 must be REVERSE_ONLY, got %d", got)
	}
	if got := selectStrategy(natCone, natHardSym, 1, false); got != strategyReverseThenForward {
		t.Fatalf("cone×hard cycle1 must be REVERSE_THEN_FORWARD, got %d", got)
	}
	if got := selectStrategy(natHardSym, natHardSym, 0, false); got != strategyDirectWithReverseParallel {
		t.Fatalf("hard×hard must be parallel, got %d", got)
	}
	if got := selectStrategy(natCone, natEasySym, 0, false); got != strategyReverseFirst {
		t.Fatalf("cone×easy must be REVERSE_FIRST, got %d", got)
	}
	if got := selectStrategy(natUnknown, natHardSym, 0, false); got != strategyReverseFirst {
		t.Fatalf("unknown vs known-symmetric must be REVERSE_FIRST, got %d", got)
	}
	if got := selectStrategy(natUnknown, natCone, 0, false); got != strategyDirectWithReverseParallel {
		t.Fatalf("unknown vs cone must be parallel, got %d", got)
	}
	if got := selectStrategy(natCone, natCone, 0, true); got != strategyDirectOnly {
		t.Fatalf("legacy peer must be DIRECT_ONLY, got %d", got)
	}
}

func TestFailureClassifier(t *testing.T) {
	// 零收包 + 端口预测活跃 → PREDICTION_OFF
	if got := classifyPunchFailure(&punchResult{portPredictionActive: true}); got != failurePredictionOff {
		t.Fatalf("expected PREDICTION_OFF, got %v", got)
	}
	// predictionDelta>100 → PREDICTION_OFF
	if got := classifyPunchFailure(&punchResult{predictionDelta: 101, recvPunch: 1}); got != failurePredictionOff {
		t.Fatalf("expected PREDICTION_OFF by delta, got %v", got)
	}
	// 零收包 + 防火墙 → FIREWALL_DETECTED
	if got := classifyPunchFailure(&punchResult{firewallDetected: true}); got != failureFirewallDetected {
		t.Fatalf("expected FIREWALL_DETECTED, got %v", got)
	}
	// 零收包 → NO_RESPONSE
	if got := classifyPunchFailure(&punchResult{}); got != failureNoResponse {
		t.Fatalf("expected NO_RESPONSE, got %v", got)
	}
	// 收到 PUNCH 无 ACK → RESPONSE_NO_ACK
	if got := classifyPunchFailure(&punchResult{recvPunch: 1}); got != failureResponseNoAck {
		t.Fatalf("expected RESPONSE_NO_ACK, got %v", got)
	}
	// 只有 ACK 且未判赢 → ACK_TIMEOUT
	if got := classifyPunchFailure(&punchResult{recvAck: 1}); got != failureAckTimeout {
		t.Fatalf("expected ACK_TIMEOUT, got %v", got)
	}
	// 部分 socket 收到 PUNCH → PARTIAL_SUCCESS
	if got := classifyPunchFailure(&punchResult{success: true, recvPunch: 1, recvAck: 1, socketsTried: 4}); got != failurePartialSuccess {
		t.Fatalf("expected PARTIAL_SUCCESS, got %v", got)
	}
	// nil 结果 → NO_RESPONSE
	if got := classifyPunchFailure(nil); got != failureNoResponse {
		t.Fatalf("nil result must be NO_RESPONSE, got %v", got)
	}
}

func TestFinalityConstants(t *testing.T) {
	// §7.5 终局常量：零收包 20 轮 / 预测关闭封顶 50，二者正交
	if zeroRecvFinalRoundLimit != 20 {
		t.Fatalf("ZERO_RECV_FINAL_ROUND_LIMIT must be 20, got %d", zeroRecvFinalRoundLimit)
	}
	if predictionOffCap != 50 {
		t.Fatalf("PREDICTION_OFF_CAP must be 50, got %d", predictionOffCap)
	}
}

func TestPunchAuthKeyDerivation(t *testing.T) {
	// key = SHA-256(tag || roomCode || 0x00 || joinerClientId)；任一输入空 → nil
	k1 := punchAuthDeriveDirectKey("ABC123", "peer-1")
	k2 := punchAuthDeriveDirectKey("ABC123", "peer-1")
	k3 := punchAuthDeriveDirectKey("ABC124", "peer-1")
	if len(k1) != 32 {
		t.Fatalf("key must be 32 bytes, got %d", len(k1))
	}
	if !bytes.Equal(k1, k2) {
		t.Fatal("derivation must be deterministic")
	}
	if bytes.Equal(k1, k3) {
		t.Fatal("room code must affect the key")
	}
	if punchAuthDeriveDirectKey("", "peer-1") != nil || punchAuthDeriveDirectKey("ABC123", "") != nil {
		t.Fatal("empty inputs must yield nil")
	}
	// trailer 覆盖 [2..len)：帧首 2 字节魔数不参与 MAC
	frame := []byte{0x56, 0x4C, 0x03, 0x01, 0x02}
	withTag := punchAuthAppendTrailer4(k1, frame)
	if !bytes.Equal(withTag[:len(frame)], frame) {
		t.Fatal("trailer must not alter the original frame bytes")
	}
	if !punchAuthVerifyTrailer4(k1, withTag) {
		t.Fatal("verify failed")
	}
	withTag[len(frame)-1] ^= 0xFF
	if punchAuthVerifyTrailer4(k1, withTag) {
		t.Fatal("tampered frame must fail verify")
	}
}

// ---- STUN ----

func TestStunBuildRequest(t *testing.T) {
	req := stunBuildRequest()
	if len(req) != 20 {
		t.Fatalf("request must be 20 bytes, got %d", len(req))
	}
	if req[0] != 0 || req[1] != 1 || req[2] != 0 || req[3] != 0 {
		t.Fatalf("bad header: % x", req[:4])
	}
	if binary.BigEndian.Uint32(req[4:8]) != stunMagicCookie {
		t.Fatalf("bad magic cookie: %x", req[4:8])
	}
	// txid 随机
	req2 := stunBuildRequest()
	if bytes.Equal(req[8:], req2[8:]) {
		t.Fatal("txid should be random")
	}
}

// TestStunParseXorMapped 构造 Binding Success Response 并解析 XOR-MAPPED-ADDRESS。
func TestStunParseXorMapped(t *testing.T) {
	req := stunBuildRequest()
	ip := net.IPv4(1, 2, 3, 4)
	port := 40000

	resp := make([]byte, 20+12)
	binary.BigEndian.PutUint16(resp[0:2], stunRespType)
	binary.BigEndian.PutUint16(resp[2:4], 12) // 一个 8B 属性 + 4B 头
	copy(resp[4:20], req[4:20])               // cookie + txid
	// XOR-MAPPED-ADDRESS (0x0020)，len=8
	off := 20
	binary.BigEndian.PutUint16(resp[off:off+2], 0x0020)
	binary.BigEndian.PutUint16(resp[off+2:off+4], 8)
	resp[off+4] = 0 // reserved
	resp[off+5] = 1 // IPv4
	xorPort := port ^ 0x2112
	binary.BigEndian.PutUint16(resp[off+6:off+8], uint16(xorPort))
	xored := binary.BigEndian.Uint32(ip.To4()) ^ stunMagicCookie
	binary.BigEndian.PutUint32(resp[off+8:off+12], xored)

	got, err := stunParseResponse(resp, req)
	if err != nil {
		t.Fatalf("parse failed: %v", err)
	}
	if got.IP != "1.2.3.4" || got.Port != port {
		t.Fatalf("parsed %s:%d, want 1.2.3.4:%d", got.IP, got.Port, port)
	}

	// txid 不匹配必须拒绝
	badReq := stunBuildRequest()
	if _, err := stunParseResponse(resp, badReq); err == nil {
		t.Fatal("mismatched txid should be rejected")
	}
}

// TestStunParseMappedFallback MAPPED-ADDRESS (0x0001) 兜底解析。
func TestStunParseMappedFallback(t *testing.T) {
	req := stunBuildRequest()
	resp := make([]byte, 20+12)
	binary.BigEndian.PutUint16(resp[0:2], stunRespType)
	binary.BigEndian.PutUint16(resp[2:4], 12)
	copy(resp[4:20], req[4:20])
	off := 20
	binary.BigEndian.PutUint16(resp[off:off+2], 0x0001)
	binary.BigEndian.PutUint16(resp[off+2:off+4], 8)
	resp[off+5] = 1
	binary.BigEndian.PutUint16(resp[off+6:off+8], uint16(54321))
	ip := net.IPv4(9, 8, 7, 6)
	copy(resp[off+8:off+12], ip.To4())

	got, err := stunParseResponse(resp, req)
	if err != nil {
		t.Fatalf("parse failed: %v", err)
	}
	if got.IP != "9.8.7.6" || got.Port != 54321 {
		t.Fatalf("parsed %s:%d", got.IP, got.Port)
	}
}

// TestStunQueryAgainstFakeServer 对本地伪造 STUN 服务器做完整查询。
func TestStunQueryAgainstFakeServer(t *testing.T) {
	if testing.Short() {
		t.Skip("short 模式跳过 socket 测试")
	}
	srv, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 0})
	if err != nil {
		t.Fatal(err)
	}
	defer srv.Close()
	go func() {
		buf := make([]byte, 576)
		for {
			n, from, err := srv.ReadFromUDP(buf)
			if err != nil {
				return
			}
			req := append([]byte(nil), buf[:n]...)
			resp := make([]byte, 32)
			binary.BigEndian.PutUint16(resp[0:2], stunRespType)
			binary.BigEndian.PutUint16(resp[2:4], 12)
			copy(resp[4:20], req[4:20])
			binary.BigEndian.PutUint16(resp[20:22], 0x0020)
			binary.BigEndian.PutUint16(resp[22:24], 8)
			resp[25] = 1
			// 回显来源端口，IP 用 203.0.113.7
			xorPort := from.Port ^ 0x2112
			binary.BigEndian.PutUint16(resp[26:28], uint16(xorPort))
			xorIP := binary.BigEndian.Uint32(net.IPv4(203, 0, 113, 7).To4()) ^ stunMagicCookie
			binary.BigEndian.PutUint32(resp[28:32], xorIP)
			_, _ = srv.WriteToUDP(resp, from)
		}
	}()

	uc, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 0})
	if err != nil {
		t.Fatal(err)
	}
	defer uc.Close()
	got, err := stunQuery(uc, srv.LocalAddr().String(), 2, time.Second)
	if err != nil {
		t.Fatalf("stunQuery failed: %v", err)
	}
	if got.Port != uc.LocalAddr().(*net.UDPAddr).Port {
		t.Fatalf("mapped port %d != local port %d", got.Port, uc.LocalAddr().(*net.UDPAddr).Port)
	}
}

// ---- FEC 组恢复 ----

// TestFecRecoveryThreePlusXor 丢 1/4 包场景：3 DATA + XOR → 恢复缺失包并按序交付。
func TestFecRecoveryThreePlusXor(t *testing.T) {
	if testing.Short() {
		t.Skip("short 模式跳过 socket 测试")
	}
	sock, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 0})
	if err != nil {
		t.Fatal(err)
	}
	defer sock.Close()
	c := newRudp(sock, nil) // 不 Start：手动喂帧，remote 为 nil 时 ACK 发送为 no-op

	// 从 seq 0 开始的第 0 组（模组 seq 从 0 计，groupId = seq/4）
	groupID := uint32(0)
	base := groupID * rudpFecGroupSize
	payloads := [][]byte{
		[]byte("alpha-payload-000"),
		[]byte("beta-payload-111111"),
		[]byte("gamma-payload-22"),
		[]byte("delta-payload-33333333"),
	}
	// 丢 seq=base+2
	for i, p := range payloads {
		if i == 2 {
			continue
		}
		c.handleData(rudpFrame{Type: rudpTypeData, Seq: base + uint32(i), Ack: 0, Payload: p})
	}
	// FEC_XOR 到达 → 触发恢复
	xor := computeXorPayload(payloads)
	c.handleFecXor(rudpFrame{
		Type: rudpTypeFecXor, Seq: groupID, Payload: xor,
		FecCount: 4, FecLengths: []int{len(payloads[0]), len(payloads[1]), len(payloads[2]), len(payloads[3])},
	})

	// 按序读出 4 块（前 3 块正常交付 + 恢复块），带超时防挂
	for i, want := range payloads {
		buf := make([]byte, 128)
		n, err := readWithTimeout(t, c, buf)
		if err != nil {
			t.Fatalf("read chunk %d: %v", i, err)
		}
		if !bytes.Equal(buf[:n], want) {
			t.Fatalf("chunk %d mismatch: got %q want %q", i, buf[:n], want)
		}
	}
	if c.nextExpected.Load() != base+4 {
		t.Fatalf("nextExpected = %d, want %d", c.nextExpected.Load(), base+4)
	}
}

// readWithTimeout 带超时的单次 Read（防止测试挂死）。
func readWithTimeout(t *testing.T, c *rudpConn, buf []byte) (int, error) {
	t.Helper()
	type res struct {
		n   int
		err error
	}
	ch := make(chan res, 1)
	go func() {
		n, err := c.Read(buf)
		ch <- res{n, err}
	}()
	select {
	case r := <-ch:
		return r.n, r.err
	case <-time.After(5 * time.Second):
		return 0, fmt.Errorf("read timeout (5s)")
	}
}

// TestComputeXorPayload XOR 计算按最长长度逐字节。
func TestComputeXorPayload(t *testing.T) {
	x := computeXorPayload([][]byte{{0x0F, 0x00}, {0xFF, 0xFF, 0x01}, {0x00}})
	want := []byte{0xF0, 0xFF, 0x01}
	if !bytes.Equal(x, want) {
		t.Fatalf("xor = %x want %x", x, want)
	}
}

// ---- rudp 本机集成：数据/ACK/FEC/KEEPALIVE ----

// startRudpPair 在 127.0.0.1 上建两条互通的 rudp 实例。
func startRudpPair(t *testing.T) (a, b *rudpConn) {
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
	a.Start()
	b.Start()
	t.Cleanup(func() {
		_ = a.Close()
		_ = b.Close()
	})
	return a, b
}

func readFull(t *testing.T, c *rudpConn, want []byte) {
	t.Helper()
	got := make([]byte, len(want))
	_, err := readAtLeastRudp(c, got)
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	if !bytes.Equal(got, want) {
		t.Fatalf("data mismatch: got %d bytes prefix %q", len(got), got[:min(16, len(got))])
	}
}

// readAtLeastRudp 循环 Read 直到填满 buf。
func readAtLeastRudp(c *rudpConn, buf []byte) (int, error) {
	total := 0
	for total < len(buf) {
		n, err := c.Read(buf[total:])
		if err != nil {
			return total, err
		}
		if n == 0 {
			return total, fmt.Errorf("zero read")
		}
		total += n
	}
	return total, nil
}

// TestRudpLocalPairTransfer 双向大数据传输（覆盖 DATA/累计 ACK/多 FEC 组）。
func TestRudpLocalPairTransfer(t *testing.T) {
	if testing.Short() {
		t.Skip("short 模式跳过 socket 测试")
	}
	a, b := startRudpPair(t)

	// 30KB：21+ 个分片 → 至少 5 个 FEC 组（FEC 组满 4 包即发 XOR）
	payloadA := make([]byte, 30*1024)
	if _, err := rand.Read(payloadA); err != nil {
		t.Fatal(err)
	}
	payloadB := make([]byte, 7*1024+123) // 含尾部分片
	if _, err := rand.Read(payloadB); err != nil {
		t.Fatal(err)
	}

	errCh := make(chan error, 2)
	go func() {
		_, err := a.Write(payloadA)
		if err == nil {
			readFull(t, b, payloadA)
		}
		errCh <- err
	}()
	go func() {
		_, err := b.Write(payloadB)
		if err == nil {
			readFull(t, a, payloadB)
		}
		errCh <- err
	}()
	for i := 0; i < 2; i++ {
		select {
		case err := <-errCh:
			if err != nil {
				t.Fatalf("transfer %d failed: %v", i, err)
			}
		case <-time.After(10 * time.Second):
			t.Fatal("transfer timeout")
		}
	}

	// 累计 ACK 生效：发送侧 pending 应清空
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		a.sendMu.Lock()
		aEmpty := len(a.pending) == 0 && a.oldestUnacked == a.nextSeq
		a.sendMu.Unlock()
		b.sendMu.Lock()
		bEmpty := len(b.pending) == 0 && b.oldestUnacked == b.nextSeq
		b.sendMu.Unlock()
		if aEmpty && bEmpty {
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatal("pending ACKs not drained")
}

// TestRudpFirstFrameIsKeepalive 连接建立第一件事：互发 KEEPALIVE（11B，type=8）。
func TestRudpFirstFrameIsKeepalive(t *testing.T) {
	if testing.Short() {
		t.Skip("short 模式跳过 socket 测试")
	}
	sa, _ := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 0})
	defer sa.Close()
	sb, _ := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 0})
	defer sb.Close()

	a := newRudp(sa, sb.LocalAddr().(*net.UDPAddr))
	a.Start() // start() 即发 KEEPALIVE

	buf := make([]byte, 64)
	_ = sb.SetReadDeadline(time.Now().Add(2 * time.Second))
	n, _, err := sb.ReadFromUDP(buf)
	if err != nil {
		t.Fatalf("no keepalive: %v", err)
	}
	want := []byte{0x56, 0x4C, rudpTypeKeepalive, 0, 0, 0, 0, 0, 0, 0, 0}
	if !bytes.Equal(buf[:n], want) {
		t.Fatalf("first frame mismatch: got %x want %x", buf[:n], want)
	}
}

// TestRudpKeepaliveReply 收到 KEEPALIVE 必须回一个 KEEPALIVE。
func TestRudpKeepaliveReply(t *testing.T) {
	if testing.Short() {
		t.Skip("short 模式跳过 socket 测试")
	}
	// a 为 rudp，b 为裸 socket 读取端（避免与 b 的 recvLoop 竞争）
	sa, _ := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 0})
	defer sa.Close()
	sb, _ := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 0})
	defer sb.Close()
	a := newRudp(sa, sb.LocalAddr().(*net.UDPAddr))
	a.Start()
	defer a.Close()

	// 丢弃 Start() 发出的首个 KEEPALIVE
	buf := make([]byte, 64)
	_ = sb.SetReadDeadline(time.Now().Add(2 * time.Second))
	if _, _, err := sb.ReadFromUDP(buf); err != nil {
		t.Fatalf("no initial keepalive: %v", err)
	}

	// 发一个 KEEPALIVE（ack=5）给 a，a 必须回 KEEPALIVE（ack = a.nextExpected = 0）
	ka := rudpEncode(rudpFrame{Type: rudpTypeKeepalive, Ack: 5})
	if _, err := sb.WriteToUDP(ka, sa.LocalAddr().(*net.UDPAddr)); err != nil {
		t.Fatal(err)
	}
	deadline := time.Now().Add(3 * time.Second)
	for {
		_ = sb.SetReadDeadline(time.Now().Add(2 * time.Second))
		n, _, err := sb.ReadFromUDP(buf)
		if err != nil {
			t.Fatalf("no keepalive reply: %v", err)
		}
		if f, ok := rudpDecode(buf[:n]); ok && f.Type == rudpTypeKeepalive {
			if f.Ack != 0 {
				t.Fatalf("keepalive reply ack = %d, want 0 (a.nextExpected)", f.Ack)
			}
			return
		}
		if time.Now().After(deadline) {
			t.Fatal("keepalive reply not observed")
		}
	}
}

// TestRudpDisconnectClose 关闭一侧 → 对端收到 DISCONNECT 并进入 EOF。
func TestRudpDisconnectClose(t *testing.T) {
	if testing.Short() {
		t.Skip("short 模式跳过 socket 测试")
	}
	a, b := startRudpPair(t)
	closedCh := make(chan string, 1)
	b.setOnClosed(func(reason string) { closedCh <- reason })
	_ = a.Close()
	select {
	case <-closedCh:
	case <-time.After(3 * time.Second):
		t.Fatal("peer disconnect not observed")
	}
	// b 侧 Read 应在排空后返回 EOF
	buf := make([]byte, 16)
	for {
		_, err := b.Read(buf)
		if err != nil {
			if !strings.Contains(err.Error(), "EOF") {
				t.Fatalf("expected EOF, got %v", err)
			}
			break
		}
	}
}

// TestBridgeEndToEnd 全链路集成：MC 客户端 → guest 本地桥 → rudp 隧道 →
// host lazy 桥 → 本地 MC 服务端（握手字节先送进隧道，回程字节原路返回）。
func TestBridgeEndToEnd(t *testing.T) {
	if testing.Short() {
		t.Skip("short 模式跳过 socket 测试")
	}
	// 假 MC 服务端
	mc, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer mc.Close()
	mcConnCh := make(chan net.Conn, 1)
	mcRecv := make(chan []byte, 4)
	go func() {
		conn, err := mc.Accept()
		if err != nil {
			return
		}
		mcConnCh <- conn
		buf := make([]byte, 4096)
		for {
			n, err := conn.Read(buf)
			if n > 0 {
				mcRecv <- append([]byte(nil), buf[:n]...)
			}
			if err != nil {
				return
			}
		}
	}()

	a, b := startRudpPair(t)
	logf := func(level, msg string) { t.Logf("[bridge %s] %s", level, msg) }
	startHostLazyBridge(b, mc.Addr().(*net.TCPAddr).Port, logf)
	guestAddr, _, err := startGuestBridge(a, nil)
	if err != nil {
		t.Fatalf("guest bridge: %v", err)
	}

	// MC 客户端连入 guest 本地桥
	client, err := net.DialTimeout("tcp", guestAddr, 2*time.Second)
	if err != nil {
		t.Fatalf("dial guest bridge: %v", err)
	}
	defer client.Close()
	if tc, ok := client.(*net.TCPConn); ok {
		_ = tc.SetNoDelay(true)
	}

	// 握手字节必须立刻送进隧道并抵达 MC 服务端
	handshake := []byte{0x10, 0x00, 0x05, 'h', 'e', 'l', 'l', 'o'} // 仿 MC 握手包
	if _, err := client.Write(handshake); err != nil {
		t.Fatal(err)
	}
	select {
	case got := <-mcRecv:
		if !bytes.Equal(got, handshake) {
			t.Fatalf("handshake mismatch: got %x want %x", got, handshake)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("handshake never reached MC server")
	}

	// MC 服务端回包 → 经隧道回到客户端
	var mcConn net.Conn
	select {
	case mcConn = <-mcConnCh:
	case <-time.After(2 * time.Second):
		t.Fatal("MC server saw no connection")
	}
	reply := []byte{0x03, 0x00, 'o', 'k'}
	if _, err := mcConn.Write(reply); err != nil {
		t.Fatal(err)
	}
	_ = client.SetReadDeadline(time.Now().Add(5 * time.Second))
	buf := make([]byte, 64)
	n, err := client.Read(buf)
	if err != nil {
		t.Fatalf("client read reply: %v", err)
	}
	if !bytes.Equal(buf[:n], reply) {
		t.Fatalf("reply mismatch: got %x want %x", buf[:n], reply)
	}
}

// ---- netstat 解析器 ----

func TestParseNetstatListening(t *testing.T) {
	sample := `
活动连接

  协议  本地地址          外部地址        状态           PID
  TCP    0.0.0.0:25565          0.0.0.0:0              LISTENING       22456
  TCP    127.0.0.1:5354         0.0.0.0:0              LISTENING       5120
  TCP    [::]:25565             [::]:0                 LISTENING       22456
  TCP    192.168.1.5:49700      93.184.216.34:443      ESTABLISHED     9910
  TCP    0.0.0.0:135            0.0.0.0:0              LISTENING       1044
  UDP    0.0.0.0:5353           *:*                                    712
`
	entries := parseNetstatListening(sample)
	if len(entries) != 3 {
		t.Fatalf("expected 3 entries, got %d: %+v", len(entries), entries)
	}
	byPort := map[int]int{}
	for _, e := range entries {
		byPort[e.Port] = e.Pid
	}
	if byPort[25565] != 22456 || byPort[5354] != 5120 || byPort[135] != 1044 {
		t.Fatalf("bad entries: %+v", entries)
	}
}

// ---- MC 进程特征匹配 ----

func TestIsMcProcessLine(t *testing.T) {
	cases := []struct {
		cmd  string
		want bool
	}{
		{"java -Xms2G -jar minecraft.jar", true},                     // minecraft
		{"C:\\Users\\foo\\.minecraft\\launcher\\launcher.exe", true}, // .minecraft
		{"java -Dfabric_loader net.fabricmc.fabricmc.launch", true},  // net.fabricmc
		{"java -Dfabric.installer -jar forge-installer.jar", true},   // fabric
		{"java -jar forge-1.20.jar", true},                           // forge
		{"java -jar neoforge-installer.jar", true},                   // neoforge
		{"java -cp launchwrapper-1.12.jar", true},                    // launchwrapper
		{"java -cp netscape.javascript -jar other.jar", false},       // 不含 MC 关键词
		{"notepad.exe some random args", false},                      // 非 java 命令
		{"", false},                                                  // 空
		{"powershell -NoProfile -Command Get-Process", false},        // 非 MC
	}
	for _, c := range cases {
		if got := isMcProcessLine(c.cmd); got != c.want {
			t.Errorf("isMcProcessLine(%q) = %v, want %v", c.cmd, got, c.want)
		}
	}
}

// ---- wmic / PowerShell CSV 解析样例 ----

func TestParseWmicJavaCSV(t *testing.T) {
	// wmic 输出示例（CommandLine,ProcessId 字段顺序按字母排序）
	sample := `CommandLine,ProcessId
"java -Xms2G -jar minecraft.jar",22456
"powershell -NoProfile -Command xyz",1234
`
	entries, err := parseWmicJavaCSV(sample)
	if err != nil {
		t.Fatalf("parse failed: %v", err)
	}
	if len(entries) != 2 {
		t.Fatalf("expected 2 entries, got %d", len(entries))
	}
	if entries[0].Pid != 22456 || entries[1].Pid != 1234 {
		t.Fatalf("bad pids: %+v", entries)
	}
	if !strings.Contains(entries[0].CommandLine, "minecraft.jar") {
		t.Fatalf("bad cmdline: %q", entries[0].CommandLine)
	}

	// 空输出
	if _, err := parseWmicJavaCSV(""); err == nil {
		t.Error("empty input should error")
	}
	// 表头缺失
	if _, err := parseWmicJavaCSV("ProcessId\n1234\n"); err == nil {
		t.Error("missing CommandLine header should error")
	}
	// PID 非法 → 跳过该行
	entries2, _ := parseWmicJavaCSV("CommandLine,ProcessId\n\"x\",not-a-number\n\"y\",42\n")
	if len(entries2) != 1 || entries2[0].Pid != 42 {
		t.Fatalf("should skip bad PID, got %+v", entries2)
	}
}

func TestParsePowerShellJavaCSV(t *testing.T) {
	// PowerShell ConvertTo-Csv 输出示例
	sample := `"ProcessId","CommandLine"
"22456","java -jar minecraft.jar"
"7777","java -cp forge.jar"
`
	entries, err := parsePowerShellJavaCSV(sample)
	if err != nil {
		t.Fatalf("parse failed: %v", err)
	}
	if len(entries) != 2 {
		t.Fatalf("expected 2, got %d", len(entries))
	}
	if entries[0].Pid != 22456 || entries[1].Pid != 7777 {
		t.Fatalf("bad pids: %+v", entries)
	}
	if !strings.Contains(entries[0].CommandLine, "minecraft") {
		t.Fatalf("bad cmdline: %q", entries[0].CommandLine)
	}

	// 表头缺失
	if _, err := parsePowerShellJavaCSV("\"ProcessId\"\n1\n"); err == nil {
		t.Error("missing CommandLine header should error")
	}
}

// ---- DetectMcPorts 真实环境 ----

// requireWindowsMCPorts mc_ports.go 的进程/端口枚举目前是 Windows 实现
// （wmic → powershell 兜底 + netstat -ano -p TCP）。Linux/macOS 的等价实现还没做
// （任务：三平台产物那一轮补 ss/lsof//proc 与 macOS lsof 变体），
// 所以这三个"跑真实命令"的用例在非 Windows 上必须跳过——不是断言写错，是被测对象在该平台不存在。
func requireWindowsMCPorts(t *testing.T) {
	t.Helper()
	if runtime.GOOS != "windows" {
		t.Skipf("mc_ports 枚举目前是 Windows 实现，%s/%s 上跳过", runtime.GOOS, runtime.GOARCH)
	}
}

func TestDetectMcPortsLive(t *testing.T) {
	requireWindowsMCPorts(t)
	if testing.Short() {
		t.Skip("short 模式跳过真实命令执行")
	}
	entries, err := detectMcPorts()
	if err != nil {
		t.Fatalf("detectMcPorts: %v", err)
	}
	t.Logf("本机检测到 %d 个 MC 端口", len(entries))
	for i, e := range entries {
		if i >= 15 {
			t.Logf("... 共 %d 条", len(entries))
			break
		}
		t.Logf("  port=%-6d pid=%-7d", e.Port, e.Pid)
	}
	// 结构合法性（即便 0 个也应返回非 nil 空切片）
	if entries == nil {
		t.Fatal("entries must be non-nil")
	}
	for _, e := range entries {
		if e.Port <= 0 || e.Port > 65535 || e.Pid <= 0 {
			t.Fatalf("bad entry: %+v", e)
		}
	}
	// 契约：返回条目不应超过 mcMaxResult
	if len(entries) > mcMaxResult {
		t.Fatalf("result cap broken: %d > %d", len(entries), mcMaxResult)
	}
	// 端口必须升序
	for i := 1; i < len(entries); i++ {
		if entries[i].Port < entries[i-1].Port {
			t.Fatalf("not sorted: %+v", entries)
		}
	}
}

// TestDetectMcPortsBinding 绑定方法返回契约格式 {ok,data:{ports:[...]}}，且无 name 字段。
func TestDetectMcPortsBinding(t *testing.T) {
	requireWindowsMCPorts(t)
	if testing.Short() {
		t.Skip("short 模式跳过真实命令执行")
	}
	a := NewApp()
	resp := a.DetectMcPorts()
	var m struct {
		OK   bool `json:"ok"`
		Data struct {
			Ports []mcPortEntry `json:"ports"`
		} `json:"data"`
	}
	if err := json.Unmarshal([]byte(resp), &m); err != nil {
		t.Fatalf("not json: %s", resp)
	}
	if !m.OK {
		t.Fatalf("expected ok: %s", resp)
	}
	t.Logf("DetectMcPorts 返回 %d 个端口", len(m.Data.Ports))
	// 序列化时不应有 name 字段（mcPortEntry 无该字段）
	rawPorts, _ := json.Marshal(m.Data.Ports)
	if strings.Contains(string(rawPorts), `"name"`) {
		t.Fatalf("ports should not contain name field: %s", rawPorts)
	}
}

// ---- netstat 真实输出交叉验证（解析器 vs 原始输出行数）----

func TestParseNetstatAgainstRealOutput(t *testing.T) {
	requireWindowsMCPorts(t)
	if testing.Short() {
		t.Skip("short 模式跳过真实命令执行")
	}
	out, err := exec.Command("netstat", "-ano", "-p", "TCP").Output()
	if err != nil {
		t.Fatalf("netstat failed: %v", err)
	}
	entries := parseNetstatListening(string(out))
	if len(entries) == 0 {
		t.Fatal("real netstat output yielded zero LISTENING entries")
	}
	seen := map[int]bool{}
	for _, e := range entries {
		if seen[e.Port] {
			t.Fatalf("port %d duplicated (dedup failed)", e.Port)
		}
		seen[e.Port] = true
	}
	t.Logf("真实 netstat 解析出 %d 个 LISTENING 端口", len(entries))
}

// ---- 引擎杂项 ----

func TestSeqArithmetic(t *testing.T) {
	if !seqAfter(1, 0) || !seqAfter(0, 0xFFFFFFFF) {
		t.Fatal("seqAfter wraparound broken")
	}
	if seqAfter(0, 1) {
		t.Fatal("seqAfter inverted")
	}
	if seqDiff(5, 2) != 3 {
		t.Fatal("seqDiff broken")
	}
	if seqDiff(2, 0xFFFFFFFF) != 3 {
		t.Fatal("seqDiff wraparound broken")
	}
}

// (int)Math.round(x) 就是 floor(x+0.5)；Go 的 int(x+0.5) 朝零截断，只在负数上分叉。
// 端口在往下走时 delta 是负的，取整口径错了预测值就差一个端口——扫的正是错的那一格。
func TestJavaRoundMatchesMathRound(t *testing.T) {
	cases := []struct {
		x    float64
		want int
	}{
		{-2.6, -3}, {-2.4, -2}, {-1.5, -1}, {-0.6, -1},
		{-0.4, 0}, {-0.5, 0}, {0.0, 0}, {0.4, 0}, {0.5, 1}, {2.6, 3},
	}
	for _, c := range cases {
		if got := javaRound(c.x); got != c.want {
			t.Errorf("javaRound(%v)=%d want %d（floor(x+0.5)）", c.x, got, c.want)
		}
	}
	// 递减端口序列：旧实现的手写校正在 (-0.5,0) 这一段会把 0 折成 -1
	ports := []int{20100, 20099, 20098, 20097}
	if got := deltaPredict(ports); got != 20096 {
		t.Fatalf("等差递减端口应预测末位再减 1, got %d", got)
	}
	if got := deltaPredict([]int{20100, 20099, 20098, 20097, 20096, 20095, 20094, 20093}); got != 20092 {
		t.Fatalf("8 样本等差递减应预测 20092, got %d", got)
	}
}
