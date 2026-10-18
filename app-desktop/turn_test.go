package main

// turn_test.go — TURN 双栈离线回归：字节级对齐模组协议，不依赖任何在线节点。
//   - 自定义协议 v1：包封头/sessionId/角色/长度、TurnTcpChannel 回环分帧
//   - 标准 TURN（RFC 5766）：长项凭证、STUN 头/属性、MESSAGE-INTEGRITY、FINGERPRINT、
//     XOR-ADDRESS 编解码往返、ChannelData 包封
//   - BIND 重试与降级门限常量

import (
	"bytes"
	"crypto/hmac"
	"crypto/md5"
	"crypto/sha1"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"hash/crc32"
	"log"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func TestTurnV1PathCodec(t *testing.T) {
	var sid [16]byte
	for i := range sid {
		sid[i] = byte(i + 1)
	}
	c := turnPathCodec{sessionID: sid, fromRole: turnRoleGuest, toRole: turnRoleHost}
	frame := []byte{0x56, 0x4C, 0x03, 0, 0, 0, 1, 0, 0, 0, 2, 0, 3, 'a', 'b'}

	pkt := c.encode(frame)
	// 头 4 + sessionId 16 + fromRole + toRole + len(2) = 24
	if len(pkt) != 24+len(frame) {
		t.Fatalf("DATA envelope length: got %d want %d", len(pkt), 24+len(frame))
	}
	if pkt[0] != 0x56 || pkt[1] != 0x4C || pkt[2] != turnProtocolVersion || pkt[3] != turnTypeData {
		t.Fatalf("bad envelope header: %x", pkt[:4])
	}
	if !bytes.Equal(pkt[4:20], sid[:]) {
		t.Fatal("sessionId not carried verbatim")
	}
	if pkt[20] != turnRoleGuest || pkt[21] != turnRoleHost {
		t.Fatalf("role order must be from,to: got %d,%d", pkt[20], pkt[21])
	}
	if int(binary.BigEndian.Uint16(pkt[22:24])) != len(frame) {
		t.Fatal("payload length field wrong")
	}
	if got := c.decode(pkt); !bytes.Equal(got, frame) {
		t.Fatalf("round-trip mismatch: %x vs %x", got, frame)
	}

	// 负例：非本会话 / 非 DATA / 声明长度越界 / 过短
	other := turnPathCodec{sessionID: [16]byte{9}, fromRole: turnRoleGuest, toRole: turnRoleHost}
	if other.decode(pkt) != nil {
		t.Fatal("must reject foreign session id")
	}
	badType := append([]byte(nil), pkt...)
	badType[3] = turnTypeKeepalive
	if c.decode(badType) != nil {
		t.Fatal("must reject non-DATA type")
	}
	trunc := append([]byte(nil), pkt[:30]...)
	if c.decode(trunc) != nil {
		t.Fatal("must reject truncated payload")
	}
	if c.decode(pkt[:20]) != nil {
		t.Fatal("must reject undersized packet")
	}
	// 非本魔数的普通 UDP 包必须静默丢弃
	if c.decode([]byte("hello world, not turn")) != nil {
		t.Fatal("must reject non-TURN traffic")
	}
}

func TestTurnV1ControlPacketLayouts(t *testing.T) {
	// KEEPALIVE/UNBIND：头 4 + sessionId 16 + role 1 = 21
	var sid [16]byte
	sid[0] = 0xAB
	p := make([]byte, 21)
	turnPackHeader(p, turnTypeKeepalive)
	copy(p[4:20], sid[:])
	p[20] = turnRoleHost
	if p[3] != turnTypeKeepalive || p[20] != turnRoleHost || p[4] != 0xAB {
		t.Fatalf("keepalive layout wrong: %x", p)
	}
	// BIND：头 4 + sessionId16 + role1 + ticketLen2 + ticket
	ticket := []byte("tkt-123")
	b := make([]byte, 4+16+1+2+len(ticket))
	turnPackHeader(b, turnTypeBind)
	copy(b[4:20], sid[:])
	b[20] = turnRoleGuest
	binary.BigEndian.PutUint16(b[21:23], uint16(len(ticket)))
	copy(b[23:], ticket)
	if len(b) != 4+16+1+2+len(ticket) || int(binary.BigEndian.Uint16(b[21:23])) != len(ticket) {
		t.Fatalf("bind layout wrong: len=%d", len(b))
	}
	// 常量对齐模组
	if turnBindOK != 0 || turnBindBadTicket != 1 || turnBindTicketExpired != 2 ||
		turnBindSessionFull != 3 || turnBindRoleConflict != 4 || turnBindServerBusy != 5 {
		t.Fatal("BIND result codes drifted")
	}
	if turnKeepaliveInterval != 15*time.Second {
		t.Fatal("keepalive must be 15s (node kicks roles after 90s idle)")
	}
	if turnFetchMaxAttempts != 4 || turnFetchRetryDelay != 800*time.Millisecond ||
		turnNodeCacheTTL != 60*time.Second || turnPingProbeCount != 6 ||
		turnPingTimeout != 800*time.Millisecond || turnProbeTotalBudget != 4*time.Second {
		t.Fatal("relay list/probe constants drifted")
	}
}

func TestTurnTcpChannelLoopback(t *testing.T) {
	if testing.Short() {
		t.Skip("short 模式跳过 socket 测试")
	}
	// 本地"节点"：TCP 长度前缀分帧回显
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func(c net.Conn) {
				defer c.Close()
				head := make([]byte, 2)
				for {
					if _, err := io_ReadFull(c, head); err != nil {
						return
					}
					n := int(binary.BigEndian.Uint16(head))
					if n <= 0 || n > turnTcpFrameMax {
						return
					}
					body := make([]byte, n)
					if _, err := io_ReadFull(c, body); err != nil {
						return
					}
					if _, err := c.Write(append(head, body...)); err != nil {
						return
					}
				}
			}(c)
		}
	}()

	port := ln.Addr().(*net.TCPAddr).Port
	ch, err := turnTcpOpen("127.0.0.1", port, 0)
	if err != nil {
		t.Fatalf("turnTcpOpen: %v", err)
	}
	defer ch.close()

	client := ch.clientSocket()
	shimAddr := &net.UDPAddr{IP: net.ParseIP("127.0.0.1"), Port: ch.shimPort()}
	// 上层（session.endpoint()）就是往 shim 口写 UDP：必须被转成 TCP 帧并回环回来
	payload := []byte{0x56, 0x4C, 0x01, 0x03, 1, 2, 3, 4, 5}
	if _, err := client.WriteToUDP(payload, shimAddr); err != nil {
		t.Fatal(err)
	}
	buf := make([]byte, 64)
	_ = client.SetReadDeadline(time.Now().Add(3 * time.Second))
	n, _, err := client.ReadFromUDP(buf)
	if err != nil {
		t.Fatalf("no echo through tcp shim: %v", err)
	}
	if !bytes.Equal(buf[:n], payload) {
		t.Fatalf("echo mismatch: %x vs %x", buf[:n], payload)
	}
}

func TestTurnTcpFrameGuards(t *testing.T) {
	// 非法长度（0 / 超 FRAME_MAX）必须整链关闭而不是崩在循环里
	a, b := net.Pipe()
	go func() {
		head := make([]byte, 2)
		binary.BigEndian.PutUint16(head, turnTcpFrameMax+1)
		_, _ = a.Write(head)
		_ = a.Close()
	}()
	ch := &turnTcpChannel{tcp: b, client: nil, shim: nil, clientAddr: &net.UDPAddr{}}
	defer ch.close()
	done := make(chan struct{})
	go func() { ch.pumpTcpToUdp(); close(done) }()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("pumpTcpToUdp must terminate on bad frame length")
	}
	if !ch.closed.Load() {
		t.Fatal("bad frame must close the channel")
	}
}

func TestStdTurnLongTermKey(t *testing.T) {
	// key = MD5(username:realm:password)（RFC 5389 §15.4）
	got := stdTurnLongTermKey("1789000000:alice", "voxlink", "s3cr3t")
	sum := md5.Sum([]byte("1789000000:alice:voxlink:s3cr3t"))
	if !bytes.Equal(got, sum[:]) {
		t.Fatalf("long-term key mismatch: %x", got)
	}
	if len(got) != 16 {
		t.Fatal("MD5 digest must be 16 bytes")
	}
}

func TestStdTurnMessageBuildAndParse(t *testing.T) {
	key := stdTurnLongTermKey("u", "r", "p")
	b := newStdTurnMsg(stdTurnMtAllocateReq)
	b.putUint32(stdTurnAtRequestedTrans, stdTurnTransportUDP<<24)
	b.putStr(stdTurnAtUsername, "u")
	b.putStr(stdTurnAtRealm, "r")
	b.putStr(stdTurnAtNonce, "n-1")
	b.putStr(stdTurnAtSoftware, "voxlink")
	pkt := b.build(key)

	// 头部：type=0x0003、magic cookie、txID 与 builder 一致
	if got := binary.BigEndian.Uint16(pkt[0:2]); got != stdTurnMtAllocateReq {
		t.Fatalf("message type got 0x%04x", got)
	}
	if got := binary.BigEndian.Uint32(pkt[4:8]); got != stdTurnMagicCookie {
		t.Fatalf("magic cookie got 0x%08x", got)
	}
	if !bytes.Equal(pkt[8:20], b.txID[:]) {
		t.Fatal("transaction id mismatch")
	}
	// 声明长度必须与实包一致且 4 字节对齐
	declared := int(binary.BigEndian.Uint16(pkt[2:4]))
	if declared != len(pkt)-20 || declared%4 != 0 {
		t.Fatalf("length field %d vs actual %d", declared, len(pkt)-20)
	}

	m := stdTurnParse(pkt)
	if m == nil {
		t.Fatal("own packet must parse")
	}
	if m.typ != stdTurnMtAllocateReq {
		t.Fatalf("parsed type 0x%04x", m.typ)
	}
	if string(m.attr(stdTurnAtUsername)) != "u" || string(m.attr(stdTurnAtRealm)) != "r" ||
		string(m.attr(stdTurnAtNonce)) != "n-1" || string(m.attr(stdTurnAtSoftware)) != "voxlink" {
		t.Fatal("string attributes lost")
	}
	// REQUESTED-TRANSPORT 必须是 UDP(17) 左移 24 位编码
	rt := m.attr(stdTurnAtRequestedTrans)
	if len(rt) != 4 || binary.BigEndian.Uint32(rt)>>24 != stdTurnTransportUDP {
		t.Fatalf("requested transport encoding wrong: %x", rt)
	}
	// MESSAGE-INTEGRITY = HMAC-SHA1(key, 报文)，长度 20。
	// RFC 5389 §15.4 两步法：算 MI 时长度字段只"计到 MI 结束"（+24），
	// 之后才被 FINGERPRINT 改写为 +32。这里用标准库独立复算一遍。
	mi := m.attr(stdTurnAtMessageIntegrity)
	if len(mi) != 20 {
		t.Fatalf("MESSAGE-INTEGRITY must be 20 bytes, got %d", len(mi))
	}
	signed := append([]byte(nil), pkt[:len(pkt)-8-24]...)
	// 长度字段须"计到 MI 属性结束"：本段（头+属性）+ MI 属性 24 字节
	binary.BigEndian.PutUint16(signed[2:4], uint16(len(signed)-20+24))
	mac := hmac.New(sha1.New, key)
	mac.Write(signed)
	wantMac := mac.Sum(nil)
	if !bytes.Equal(mi, wantMac) {
		t.Fatalf("MESSAGE-INTEGRITY mismatch: got %x want %x", mi, wantMac)
	}
	// FINGERPRINT = CRC32(至 FP 前全部字节) ^ 0x5354554E
	fp := m.attr(stdTurnAtFingerprint)
	if len(fp) != 4 {
		t.Fatalf("FINGERPRINT must be 4 bytes, got %d", len(fp))
	}
	wantFp := crc32.ChecksumIEEE(pkt[:len(pkt)-8]) ^ stdTurnFpXor
	if binary.BigEndian.Uint32(fp) != wantFp {
		t.Fatalf("FINGERPRINT mismatch: got 0x%08x want 0x%08x", binary.BigEndian.Uint32(fp), wantFp)
	}
	// 属性遍历：长度字段记真实长度，补齐由填充字节负责（RFC 5389 §15.1），
	// 按 4 字节向上取整推进必须正好走到各属性边界并停在 FINGERPRINT 之前。
	off := 20
	seen := 0
	for off+4 <= len(pkt)-8 {
		at := binary.BigEndian.Uint16(pkt[off : off+2])
		al := int(binary.BigEndian.Uint16(pkt[off+2 : off+4]))
		if al%4 != 0 && off+4+al+(4-al%4) > len(pkt)-8 {
			t.Fatalf("attribute 0x%04x len %d padding runs past message end", at, al)
		}
		off += 4 + (al+3)&^3
		seen++
	}
	if seen == 0 || off != len(pkt)-8 {
		t.Fatalf("attribute walk misaligned: stopped at %d, expected %d (seen %d attrs)", off, len(pkt)-8, seen)
	}

	// 错误响应解析：ERROR-CODE = (v[2]&7)*100 + v[3]
	errMsg := newStdTurnMsg(stdTurnMtAllocateErr)
	errMsg.put(stdTurnAtErrorCode, []byte{0, 0, 4, 1, '4', '0', '1'}) // 4*100+1 = 401
	parsed := stdTurnParse(errMsg.build(nil))
	if parsed == nil || parsed.errorCode != 401 || parsed.errorReason != "401" {
		t.Fatalf("error code parse failed: %+v", parsed)
	}
	// 非 STUN 流量（ChannelData 首两位 01）与长度不足必须返回 nil
	if stdTurnParse([]byte{0x40, 0x00, 0, 4, 1, 2, 3, 4}) != nil {
		t.Fatal("ChannelData must not be parsed as STUN")
	}
	if stdTurnParse([]byte{0x00, 0x01, 0x00}) != nil {
		t.Fatal("undersized packet must be rejected")
	}
	if stdTurnParse([]byte{0x00, 0x01, 0x00, 0x00, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x99}) != nil {
		t.Fatal("wrong magic cookie must be rejected")
	}
}

func TestStdTurnXorAddressRoundTrip(t *testing.T) {
	b := newStdTurnMsg(stdTurnMtChannelBindReq)
	addr := &net.UDPAddr{IP: net.ParseIP("203.0.113.7").To4(), Port: 41240}
	b.putXorAddress(stdTurnAtXorPeerAddress, addr)
	m := stdTurnParse(b.build(nil))
	if m == nil {
		t.Fatal("parse failed")
	}
	got := stdTurnDecodeXorAddress(m.attr(stdTurnAtXorPeerAddress), m.txID)
	if got == nil || !got.IP.Equal(addr.IP) || got.Port != addr.Port {
		t.Fatalf("xor address round-trip failed: %v", got)
	}
	// 明文编码不得直接可见（必须真的异或过）：属性值 = 0, family, port(2), ip(4)
	raw := m.attr(stdTurnAtXorPeerAddress)
	if len(raw) != 8 {
		t.Fatalf("IPv4 XOR-MAPPED-ADDRESS must be 8 bytes, got %d", len(raw))
	}
	if raw[0] != 0 || raw[1] != 0x01 {
		t.Fatalf("address family header wrong: %x", raw[:2])
	}
	if raw[2] == byte(addr.Port>>8) && raw[3] == byte(addr.Port) {
		t.Fatal("port was not XOR-obfuscated")
	}
	if bytes.Contains(raw, []byte{203, 0, 113, 7}) {
		t.Fatal("IPv4 was not XOR-obfuscated")
	}
	// 畸形输入
	if stdTurnDecodeXorAddress(nil, [12]byte{}) != nil {
		t.Fatal("nil attribute must decode to nil")
	}
	if stdTurnDecodeXorAddress([]byte{0, 3, 0, 0, 1, 2, 3, 4}, [12]byte{}) != nil {
		t.Fatal("unknown family must decode to nil")
	}
	// IPv6 路径（后 12 字节 XOR txID）
	v6 := newStdTurnMsg(stdTurnMtChannelBindReq)
	v6addr := &net.UDPAddr{IP: net.ParseIP("2001:db8::1").To16(), Port: 40001}
	v6.putXorAddress(stdTurnAtXorPeerAddress, v6addr)
	m6 := stdTurnParse(v6.build(nil))
	got6 := stdTurnDecodeXorAddress(m6.attr(stdTurnAtXorPeerAddress), m6.txID)
	if got6 == nil || !got6.IP.Equal(v6addr.IP) || got6.Port != v6addr.Port {
		t.Fatalf("IPv6 xor address failed: %v", got6)
	}
}

func TestStdTurnChannelDataCodec(t *testing.T) {
	c := stdTurnPathCodec{channel: stdTurnChannelBase}
	frame := []byte{0x56, 0x4C, 0x08, 0, 0, 0, 0, 0, 0, 0, 0}
	pkt := c.encode(frame)
	if len(pkt) != 4+len(frame) {
		t.Fatalf("ChannelData must add a 4-byte header, got %d", len(pkt))
	}
	if binary.BigEndian.Uint16(pkt[0:2]) != stdTurnChannelBase {
		t.Fatal("channel number missing from header")
	}
	if got := c.decode(pkt); !bytes.Equal(got, frame) {
		t.Fatalf("round-trip mismatch: %x", got)
	}
	// 非本 channel / STUN 消息（首两位 00）/ 越界长度 → 丢弃
	other := stdTurnPathCodec{channel: 0x4001}
	if other.decode(pkt) != nil {
		t.Fatal("must drop frames of another channel")
	}
	stun := make([]byte, 24)
	binary.BigEndian.PutUint16(stun[0:2], stdTurnMtAllocateOK)
	if c.decode(stun) != nil {
		t.Fatal("STUN responses arriving on the data socket must be dropped")
	}
	bad := append([]byte(nil), pkt...)
	binary.BigEndian.PutUint16(bad[2:4], uint16(len(frame)+50))
	if c.decode(bad) != nil {
		t.Fatal("oversized declared length must be dropped")
	}
	if c.decode([]byte{0x40, 0x00}) != nil {
		t.Fatal("undersized packet must be dropped")
	}
	// 常量对齐
	if stdTurnChannelBase != 0x4000 || stdTurnDefaultLifetime != 600 {
		t.Fatal("std TURN constants drifted")
	}
}

func TestStdTurnRudpEnvelopeOverChannelData(t *testing.T) {
	// rudp DATA 帧经 ChannelData 包封后必须能原样剥回（数据面零改动的核心不变量）
	var sid [16]byte
	rc := newRudp(nil, nil)
	rc.setCodec(stdTurnPathCodec{channel: stdTurnChannelBase})
	frame := rudpEncode(rudpFrame{Type: rudpTypeData, Seq: 7, Ack: 3, Payload: []byte("mc-handshake")})
	wire := rc.codec.encode(frame)
	if got := rc.codec.decode(wire); !bytes.Equal(got, frame) {
		t.Fatal("rudp frame must survive ChannelData envelope")
	}
	if inner, ok := rudpDecode(rc.codec.decode(wire)); !ok || inner.Type != rudpTypeData || inner.Seq != 7 {
		t.Fatalf("unwrapped bytes must still be a valid rudp DATA frame: %+v ok=%v", inner, ok)
	}
	// TURN v1 包封同理
	rc2 := newRudp(nil, nil)
	var c turnPathCodec
	copy(c.sessionID[:], sid[:])
	c.fromRole, c.toRole = turnRoleHost, turnRoleGuest
	rc2.setCodec(c)
	wire2 := rc2.codec.encode(frame)
	back, ok := rudpDecode(rc2.codec.decode(wire2))
	if !ok || back.Type != rudpTypeData || back.Seq != 7 {
		t.Fatal("rudp frame must survive TURN DATA envelope")
	}
}

func TestRudpAuthInterop(t *testing.T) {
	// 认证帧：整帧（除魔数与 MAC 自身）参与 HMAC，收端必须验通
	key := punchAuthDeriveDirectKey("ROOM01", "joiner-9")
	rc := newRudp(nil, nil)
	rc.setAuthKey(key, true)
	frame := rudpEncode(rudpFrame{Type: rudpTypeData, Seq: 1, Ack: 0, Payload: []byte("x")})
	sealed := punchAuthAppendTrailer4(key, frame)
	if len(sealed) != len(frame)+4 {
		t.Fatal("auth trailer must append exactly 4 bytes")
	}
	if !punchAuthVerifyTrailer4(key, sealed) {
		t.Fatal("sealed frame must verify")
	}
	// 明文帧在认证模式下必须被丢弃（触发降级计数）
	rc.authDrop()
	rc.authDrop()
	if rc.armedAuthKey() == nil {
		t.Fatal("must not downgrade before 3 consecutive drops")
	}
	rc.authDrop()
	if rc.armedAuthKey() != nil {
		t.Fatal("3 consecutive unauthenticated frames must downgrade to plaintext")
	}
	// 不允许降级时保持认证
	rc2 := newRudp(nil, nil)
	rc2.setAuthKey(key, false)
	for i := 0; i < 5; i++ {
		rc2.authDrop()
	}
	if rc2.armedAuthKey() == nil {
		t.Fatal("auth must stay armed when downgrade is not allowed")
	}
}

// TestRudpSendChunkCapForTurnPath 发送分块上限必须按路径取值：
// TURN 包封路径下节点对 DATA payload 有 1400 硬限（超了整包静默丢弃），
// 1400 的分块加上 FEC 头 22B + 认证 MAC 4B 就会溢出——模组用 1374 兜住，这里逐字对齐。
func TestRudpSendChunkCapForTurnPath(t *testing.T) {
	if rudpMaxPayload != 1400 || rudpTurnMaxChunk != 1374 {
		t.Fatalf("chunk caps drifted: direct=%d turn=%d", rudpMaxPayload, rudpTurnMaxChunk)
	}

	peer, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.ParseIP("127.0.0.1"), Port: 0})
	if err != nil {
		t.Fatal(err)
	}
	defer peer.Close()
	self, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.ParseIP("127.0.0.1"), Port: 0})
	if err != nil {
		t.Fatal(err)
	}
	defer self.Close()

	rc := newRudp(self, peer.LocalAddr().(*net.UDPAddr))
	if rc.maxSendChunk() != rudpMaxPayload {
		t.Fatal("direct path must keep the 1400 chunk cap")
	}
	codec := stdTurnPathCodec{channel: stdTurnChannelBase}
	rc.setCodec(codec)
	rc.setAuthKey(punchAuthDeriveDirectKey("ROOM01", "joiner-9"), false)
	if rc.maxSendChunk() != rudpTurnMaxChunk {
		t.Fatalf("turn path chunk cap = %d, want %d", rc.maxSendChunk(), rudpTurnMaxChunk)
	}

	// 模组的 sendBytes 要求 running（ReliableUdpTransport.java:1275），所以真实链路里写一定发生在 Start 之后；
	// 这里也照实际时序先启动，再来验 TURN 路径的分块上限。
	rc.Start()
	t.Cleanup(func() { _ = rc.Close() })

	payload := bytes.Repeat([]byte("m"), 4*rudpTurnMaxChunk)
	if _, err := rc.Write(payload); err != nil {
		t.Fatal(err)
	}

	// 4 个 DATA + 1 个 FEC_XOR（组满 4 即发），逐包剥信封后内层帧都不得越过节点 1400。
	// 假对端从不 ACK，Start 之后重传定时器会反复补发同一 seq；keepalive 还复用 seq=0 ——
	// 所以按 (type,seq) 去重，并且只数数据面两种帧。
	buf := make([]byte, 4096)
	seen := map[[2]uint32]bool{}
	got := 0
	for {
		_ = peer.SetReadDeadline(time.Now().Add(300 * time.Millisecond))
		n, _, err := peer.ReadFromUDP(buf)
		if err != nil {
			break
		}
		inner := codec.decode(buf[:n])
		if inner == nil {
			t.Fatalf("packet %d is not ChannelData on our channel", got)
		}
		if len(inner) > 1400 {
			t.Fatalf("inner rudp frame is %d bytes; TURN node drops anything over 1400 silently", len(inner))
		}
		if n > rudpTurnMaxChunk+22+4+4 {
			t.Fatalf("wire datagram is %d bytes", n)
		}
		if len(inner) < 9 {
			t.Fatalf("inner frame too short: %d", len(inner))
		}
		typ := inner[2]
		if typ != rudpTypeData && typ != rudpTypeFecXor {
			continue
		}
		key := [2]uint32{uint32(typ), binary.BigEndian.Uint32(inner[5:9])}
		if seen[key] {
			continue
		}
		seen[key] = true
		got++
	}
	if got != 5 {
		t.Fatalf("want 4 DATA + 1 FEC for a full 4-chunk group, got %d distinct packets", got)
	}
}

// stdTurnFakeNode 假标准 TURN 节点：吞掉前 dropProbe 个无凭证探包，之后回 401 挑战，
// 带凭证的 Allocate 一律回 XOR-RELAYED-ADDRESS。记录收到的探包数。
type stdTurnFakeNode struct {
	addr   *net.UDPAddr
	probes atomic.Int32
	authOK atomic.Int32
}

func newStdTurnFakeNode(t *testing.T, dropProbe int, relayIP string, relayPort int) *stdTurnFakeNode {
	t.Helper()
	pc, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.ParseIP("127.0.0.1"), Port: 0})
	if err != nil {
		t.Fatal(err)
	}
	node := &stdTurnFakeNode{addr: pc.LocalAddr().(*net.UDPAddr)}
	go func() {
		buf := make([]byte, 2048)
		for {
			_ = pc.SetReadDeadline(time.Now().Add(60 * time.Second))
			n, from, err := pc.ReadFromUDP(buf)
			if err != nil {
				return
			}
			m := stdTurnParse(buf[:n])
			if m == nil || m.typ != stdTurnMtAllocateReq {
				continue
			}
			if m.attr(stdTurnAtUsername) == nil {
				if node.probes.Add(1) <= int32(dropProbe) {
					continue // 模拟 CGNAT 丢包
				}
				chal := newStdTurnMsg(stdTurnMtAllocateErr)
				chal.txID = m.txID
				chal.put(stdTurnAtErrorCode, []byte{0, 0, 4, 1, '4', '0', '1'})
				chal.putStr(stdTurnAtRealm, "voxlink")
				chal.putStr(stdTurnAtNonce, "nonce-1")
				_, _ = pc.WriteToUDP(chal.build(nil), from)
				continue
			}
			node.authOK.Add(1)
			ok := newStdTurnMsg(stdTurnMtAllocateOK)
			ok.txID = m.txID
			ok.putXorAddress(stdTurnAtXorRelayedAddr, &net.UDPAddr{IP: net.ParseIP(relayIP), Port: relayPort})
			ok.putUint32(stdTurnAtLifetime, stdTurnDefaultLifetime)
			_, _ = pc.WriteToUDP(ok.build(nil), from)
		}
	}()
	t.Cleanup(func() { _ = pc.Close() })
	return node
}

// TestStdTurnAllocateKeepsProbingAfterLostProbe CGNAT 下单个探包丢失不能判死整条中继：
// 前两发被吞仍要重试到第三发并成功。
func TestStdTurnAllocateKeepsProbingAfterLostProbe(t *testing.T) {
	node := newStdTurnFakeNode(t, 2, "198.51.100.7", 40000)
	s := stdTurnAllocate("127.0.0.1", node.addr.Port, "1789000000:alice", "s3cr3t", 400)
	if s == nil {
		t.Fatalf("allocate gave up after %d lost probes", node.probes.Load())
	}
	defer stdTurnClose(s)
	if got := node.probes.Load(); got != 3 {
		t.Fatalf("probe sends = %d, want 3", got)
	}
	relay := s.relayAddr.Load()
	if relay == nil || relay.String() != "198.51.100.7:40000" {
		t.Fatalf("relay addr = %v", relay)
	}
}

// TestStdTurnAllocateProbeIsBounded 全丢时探包次数有上界（不无限重发拖满 35s 预算）。
func TestStdTurnAllocateProbeIsBounded(t *testing.T) {
	node := newStdTurnFakeNode(t, 99, "198.51.100.7", 40000)
	start := time.Now()
	if s := stdTurnAllocate("127.0.0.1", node.addr.Port, "u", "p", 400); s != nil {
		stdTurnClose(s)
		t.Fatal("allocate must fail when the node never answers the challenge probe")
	}
	if got := node.probes.Load(); got > stdTurnProbeTries {
		t.Fatalf("probe sends = %d, want <= %d", got, stdTurnProbeTries)
	}
	if elapsed := time.Since(start); elapsed > 5*time.Second {
		t.Fatalf("bounded probing took %s", elapsed)
	}
}

// TestStdTurnFetchCredSurfacesServerErrorCode 凭证被拒时服务端错误码是唯一定位线索，
// 不能吞成一句"签发失败"（MISSING_FIELDS/INVALID_TOKEN/NODE_OFFLINE/RATE_LIMITED 各有含义）。
func TestStdTurnFetchCredSurfacesServerErrorCode(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusNotFound)
		_ = json.NewEncoder(w).Encode(map[string]any{
			"success": false, "error": "NODE_OFFLINE", "message": "标准 TURN 节点未启用",
		})
	}))
	t.Cleanup(srv.Close)

	a := NewApp()
	a.settingsPath = filepath.Join(t.TempDir(), "settings.json")
	a.mu.Lock()
	a.settings.ServerURL = srv.URL
	a.mu.Unlock()

	var logs bytes.Buffer
	oldOut, oldFlags := log.Writer(), log.Flags()
	log.SetOutput(&logs)
	log.SetFlags(0)
	defer func() { log.SetOutput(oldOut); log.SetFlags(oldFlags) }()

	if c := stdTurnFetchCred(a, "ROOM01", "client-1", "tok", "node-1"); c != nil {
		t.Fatal("rejected cred must still yield nil")
	}
	if !bytes.Contains(logs.Bytes(), []byte("NODE_OFFLINE")) {
		t.Fatalf("server error code must reach the net log, got %q", logs.String())
	}
}

// io_ReadFull 测试用：从 r 读满 buf。
func io_ReadFull(c net.Conn, buf []byte) (int, error) {
	total := 0
	for total < len(buf) {
		n, err := c.Read(buf[total:])
		total += n
		if err != nil {
			return total, err
		}
	}
	return total, nil
}

// 认证降级的开关只能出现在 TURN 那一处（本轮审计 F 条：直连 P2P 两处误传了 true）。
// 这条不验行为（行为上面已经钉住了），验的是"以后有人新加调用点时又顺手传了 true"——
// 这类缺陷正是从调用点渗回来的，而调用点散落在打洞/TURN 两条流程里，靠人记不住。
func TestAuthDowngradeFlagOnlyEnabledInTurnFlow(t *testing.T) {
	entries, err := os.ReadDir(".")
	if err != nil {
		t.Fatal(err)
	}
	var offenders []string
	for _, ent := range entries {
		name := ent.Name()
		if !strings.HasSuffix(name, ".go") || strings.HasSuffix(name, "_test.go") {
			continue
		}
		if name == "rudp.go" { // 定义处
			continue
		}
		b, err := os.ReadFile(name)
		if err != nil {
			t.Fatal(err)
		}
		for i, line := range strings.Split(string(b), "\n") {
			if strings.Contains(line, "setAuthKey(") && strings.Contains(line, ", true)") && name != "turn_flow.go" {
				offenders = append(offenders, fmt.Sprintf("%s:%d: %s", name, i+1, strings.TrimSpace(line)))
			}
		}
	}
	if len(offenders) > 0 {
		t.Fatalf("认证降级(allowDowngrade=true)只能由 TURN 流程开启，出现了新调用点：\n%s",
			strings.Join(offenders, "\n"))
	}
}
