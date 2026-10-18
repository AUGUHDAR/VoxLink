package main

// overlay_test.go — P2POverlayManager 移植验收（只用回环，绝不碰真实配置目录/网络）：
//   - 线上帧：[4B 大端长度][gzip]，长度头与边界判据逐项对齐 :700-727；
//   - 解压上限（安全修复 :47/:712）超限必须丢包并计数；
//   - Gson 形态 JSON：成员按插入顺序 + HTML-safe 转义（overlayAuthV1 的签名字节依赖它）；
//   - overlayAuthV1：签名→线上→验签→剥离 mac 的闭环，篡改/缺 mac 必须失败；
//   - 握手来源约束（:398-432）、头→上下游登记（:410-413）、ping/pong 时延（:450-471）；
//   - data_relay 的去重与方向转发（:473-528），含 to==自己 只交付不转发。

import (
	"bytes"
	"compress/gzip"
	"encoding/binary"
	"net"
	"strings"
	"sync"
	"testing"
	"time"
)

type recordingOverlayHandler struct {
	mu        sync.Mutex
	data      []ovDataRecord
	linkReady int
	linkLost  []string
}

type ovDataRecord struct {
	from     string
	priority string
	payload  map[string]any
}

func (h *recordingOverlayHandler) onDataReceived(from, priority string, payload *ovObject) {
	h.mu.Lock()
	h.data = append(h.data, ovDataRecord{from: from, priority: priority, payload: payload.toMap()})
	h.mu.Unlock()
}

func (h *recordingOverlayHandler) onLinkReady() {
	h.mu.Lock()
	h.linkReady++
	h.mu.Unlock()
}

func (h *recordingOverlayHandler) onLinkLost(reason string) {
	h.mu.Lock()
	h.linkLost = append(h.linkLost, reason)
	h.mu.Unlock()
}

func (h *recordingOverlayHandler) readyCount() int {
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.linkReady
}

func (h *recordingOverlayHandler) lostReasons() []string {
	h.mu.Lock()
	defer h.mu.Unlock()
	out := make([]string, len(h.linkLost))
	copy(out, h.linkLost)
	return out
}

func (h *recordingOverlayHandler) records() []ovDataRecord {
	h.mu.Lock()
	defer h.mu.Unlock()
	out := make([]ovDataRecord, len(h.data))
	copy(out, h.data)
	return out
}

func testOverlayLog(string, string) {}

// startOverlay 起一个绑定随机回环端口的覆盖网实例。
func startOverlay(t *testing.T, nodeID string, h overlayPacketHandler) *overlayManager {
	t.Helper()
	m := newOverlayManager(nodeID, 0, testOverlayLog)
	if err := m.start(h); err != nil {
		t.Fatalf("overlay start: %v", err)
	}
	m.setNodeID(nodeID)
	t.Cleanup(m.stop)
	return m
}

func overlayDatagram(t *testing.T, json string) []byte {
	t.Helper()
	payload, err := overlayCompress(json)
	if err != nil {
		t.Fatalf("compress: %v", err)
	}
	return overlayFramePacket(payload)
}

func TestOverlayFrameHeaderIsBigEndianLength(t *testing.T) {
	framed := overlayFramePacket([]byte{0x01, 0x02, 0x03})
	want := []byte{0, 0, 0, 3, 1, 2, 3}
	if !bytes.Equal(framed, want) {
		t.Fatalf("framePacket=%v want=%v", framed, want)
	}
	if got := binary.BigEndian.Uint32(framed[:4]); got != 3 {
		t.Fatalf("length header=%d want 3", got)
	}
}

func TestOverlayDecompressBoundaries(t *testing.T) {
	body, err := overlayCompress(`{"type":"ping","from":"A","seq":7,"dir":"up"}`)
	if err != nil {
		t.Fatal(err)
	}
	good := overlayFramePacket(body)
	if got := overlayDecompress(good); got != `{"type":"ping","from":"A","seq":7,"dir":"up"}` {
		t.Fatalf("round trip=%q", got)
	}
	// :701 短于 4 字节头
	if overlayDecompress([]byte{1, 2, 3}) != "" {
		t.Fatal("短于长度头必须丢")
	}
	// :706 payloadLen<=0
	bad := append([]byte{}, good...)
	binary.BigEndian.PutUint32(bad[:4], 0)
	if overlayDecompress(bad) != "" {
		t.Fatal("payloadLen=0 必须丢")
	}
	binary.BigEndian.PutUint32(bad[:4], 1)
	binary.BigEndian.PutUint32(bad[4:8], 0x7FFFFF00) // 负数（Java getInt 是带符号读）
	if overlayDecompress(bad) != "" {
		t.Fatal("负 payloadLen 必须丢")
	}
	// payloadLen > len-4（截断）
	truncated := append([]byte{}, good...)
	binary.BigEndian.PutUint32(truncated[:4], uint32(len(good)+16))
	if overlayDecompress(truncated) != "" {
		t.Fatal("长度超界必须丢")
	}
}

func TestOverlayDecompressZipBombGuard(t *testing.T) {
	// 256KB 上限（:47/:712）：300KB 高度可压内容必须在超限后丢包并计数
	before := overlayDecompressDropCount.Load()
	var big strings.Builder
	for i := 0; i < 300*1024; i++ {
		big.WriteByte('A')
	}
	payload, err := overlayCompress(big.String())
	if err != nil {
		t.Fatal(err)
	}
	if len(payload) >= 16*1024 {
		t.Fatalf("用例内容不够可压（%d 字节），换不进 zip-bomb 场景", len(payload))
	}
	if got := overlayDecompress(overlayFramePacket(payload)); got != "" {
		t.Fatalf("超限解压必须返回空，得到长度 %d", len(got))
	}
	if after := overlayDecompressDropCount.Load(); after <= before {
		t.Fatalf("超限丢包未计数：before=%d after=%d", before, after)
	}
}

func TestOverlayGsonJSONOrderAndEscape(t *testing.T) {
	o := newOVObject()
	o.putString("type", "handshake")
	o.putString("from", "a=b&c<d>e'f")
	o.putInt("seq", 7)
	want := `{"type":"handshake","from":"a\u003db\u0026c\u003cd\u003ee\u0027f","seq":7}`
	if got := string(o.marshalGson()); got != want {
		t.Fatalf("marshalGson=\n%s\nwant\n%s", got, want)
	}
	// 解析保序：线上成员顺序是签名字节的一部分
	src := `{"b":1,"a":"x","c":{"z":0,"y":[1,2,"3"]}}`
	p, err := ovParseObject([]byte(src))
	if err != nil {
		t.Fatal(err)
	}
	if got := string(p.marshalGson()); got != src {
		t.Fatalf("解析后再序列化变了序：\n%s\nwant\n%s", got, src)
	}
	// 顶层非对象 = 模组 cast 失败 = 丢包
	if _, err := ovParseObject([]byte(`[1,2]`)); err == nil {
		t.Fatal("顶层数组必须报错")
	}
}

func TestOverlayAuthSignVerifyRoundTrip(t *testing.T) {
	key := []byte("link-key-material")
	p := newOVObject()
	p.putString("type", "ping")
	p.putString("from", "nodeA")
	p.putInt("seq", 3)
	p.putString("dir", "up")
	overlaySignPacket(p, key)
	mac, ok := ovString(p.get("mac"))
	if !ok || len(mac) != overlayAuthMacBytes*2 {
		t.Fatalf("mac 字段异常: %q", mac)
	}
	// 已带 mac 不重复签
	overlaySignPacket(p, key)
	if v := p.get("mac"); func() bool { s, _ := ovString(v); return s != mac }() {
		t.Fatal("重复签名改了 mac")
	}

	wire := string(p.marshalGson())
	rx, err := ovParseObject([]byte(wire))
	if err != nil {
		t.Fatal(err)
	}
	if !overlayVerifyAndStripPacket(rx, key) {
		t.Fatal("合法报文验签失败")
	}
	if rx.has("mac") {
		t.Fatal("验签后 mac 必须被剥离（转发时不能带上游的 mac）")
	}
	// 缺 mac
	if overlayVerifyAndStripPacket(newOVObject(), key) {
		t.Fatal("缺 mac 必须判失败")
	}
	// 篡改
	tx, _ := ovParseObject([]byte(wire))
	tx.putString("from", "nodeB")
	if overlayVerifyAndStripPacket(tx, key) {
		t.Fatal("篡改后必须判失败")
	}
	// 未配置密钥的来源保持旧行为（:260-262）
	nb, _ := ovParseObject([]byte(wire))
	if !overlayVerifyAndStripPacket(nb, nil) {
		t.Fatal("无密钥时应放行")
	}
	if !nb.has("mac") {
		t.Fatal("无密钥时不能剥 mac（保持线上原样）")
	}
}

func TestOverlayHandshakeRegistersDownstreamAndReadiesLink(t *testing.T) {
	hA := &recordingOverlayHandler{}
	mA := startOverlay(t, "A", hA)
	hB := &recordingOverlayHandler{}
	mB := startOverlay(t, "B", hB)

	// A 起链头（此刻还没有下游），B 挂到 A 上：B 变 TAIL 并向 A 发握手
	mA.becomeHead("", "", 0)
	mB.connectUpstream("A", "127.0.0.1", mA.getLocalPort())
	if !waitOverlay(2*time.Second, func() bool { return hA.readyCount() >= 1 }) {
		t.Fatalf("A 未收到握手（role=%s）", mA.getRole())
	}
	if mB.getRole() != overlayRoleChainTail {
		t.Fatalf("B role=%s want CHAIN_TAIL", mB.getRole())
	}
	// 握手不改接收方的角色：A 仍是 becomeHead 给的 CHAIN_HEAD
	if mA.getRole() != overlayRoleChainHead {
		t.Fatalf("A role=%s want CHAIN_HEAD", mA.getRole())
	}
	// :410-413 只有 CHAIN_HEAD/CHAIN_MIDDLE 登记 downstream 槽位，登记的是来源地址本身
	if down := mA.downstreamAddr.Load(); down == nil || down.Port != mB.getLocalPort() {
		t.Fatalf("A 未登记 downstream 槽位: %v", down)
	}

	// ping/pong：A 有 downstream → pingTask 发 dir=down，B 回 pong，A 记时延
	mA.pingTask()
	if !waitOverlay(3*time.Second, func() bool { return mA.getDownstreamLatency() != -1 }) {
		t.Fatalf("A 未测到下游时延（pending=%v）", mA.pendingDownstreamPing.Load())
	}
	if mA.getUpstreamLatency() != -1 {
		t.Fatalf("链头不该有上游时延: %d", mA.getUpstreamLatency())
	}
}

func TestOverlayHandshakeRejectedFromUnknownSource(t *testing.T) {
	h := &recordingOverlayHandler{}
	m := startOverlay(t, "H", h)
	other := newLocalUDP(t)
	otherAddr := udpAddrOfSock(t, other)

	// 引导期（downstream 未配置）保持旧行为：任何来源都放行，否则首连建立不起来（:428-431）
	before := h.readyCount()
	m.processPacket(overlayDatagram(t, `{"type":"handshake","from":"BOOT"}`), otherAddr)
	if h.readyCount() == before {
		t.Fatal("downstream 未配置时握手应放行（引导期兼容）")
	}

	// 已配置 downstream=127.0.0.1:9：模组只比 IP（NAT 重写端口允许漂移），
	// 所以同 IP 不同端口的握手必须放行；换端口不会被视为陌生来源
	m.setDownstream("D", "127.0.0.1", 9)
	role := m.getRole()
	before = h.readyCount()
	m.processPacket(overlayDatagram(t, `{"type":"handshake","from":"SAME-IP"}`), otherAddr)
	if h.readyCount() != before+1 {
		t.Fatalf("IP 一致的握手应放行（role=%s）", role)
	}
	if role == overlayRoleChainHead || role == overlayRoleChainMiddle {
		if m.downstreamAddr.Load().Port != otherAddr.Port {
			t.Fatalf("HEAD/MIDDLE 应用来源地址登记 downstream: %v", m.downstreamAddr.Load())
		}
	} else if m.downstreamAddr.Load().Port != 9 {
		t.Fatalf("其他角色不该改写 downstream 槽位: %v", m.downstreamAddr.Load())
	}

	// 换了 IP（TEST-NET-1，非本机任何地址）→ 陌生来源必须拒绝，且丢包计数 +1（:398-406）
	dropsBefore := m.authDropCount.Load()
	m.processPacket(overlayDatagram(t, `{"type":"handshake","from":"EVIL"}`),
		&net.UDPAddr{IP: net.ParseIP("192.0.2.5"), Port: 5555})
	if m.authDropCount.Load() != dropsBefore+1 {
		t.Error("陌生来源的握手未被拒绝计数")
	}
	if m.downstreamAddr.Load().Port != 9 && (role == overlayRoleNone || role == overlayRoleChainTail) {
		t.Fatalf("陌生来源不能占用 downstream 槽位: %v", m.downstreamAddr.Load())
	}
}

func TestOverlayDataRelayDedupAndForward(t *testing.T) {
	up := newLocalUDP(t)
	down := newLocalUDP(t)
	upAddr := udpAddrOfSock(t, up)
	downAddr := udpAddrOfSock(t, down)

	h := &recordingOverlayHandler{}
	m := startOverlay(t, "M", h)
	m.connectUpstream("U", upAddr.IP.String(), upAddr.Port)
	m.setDownstream("D", downAddr.IP.String(), downAddr.Port)
	if m.getRole() != overlayRoleChainMiddle {
		t.Fatalf("role=%s want CHAIN_MIDDLE", m.getRole())
	}

	payload := `{"type":"data_relay","from":"X","seq":11,"to":"someone-else","priority":"L1","payload":{"k":"v"}}`
	datagram := overlayDatagram(t, payload)

	// connectUpstream 自己会向上游发一个握手，先排掉它，下面读到的才是转发的 data_relay
	if hs := readOverlayDatagram(t, up, 2*time.Second); !strings.Contains(hs, `"type":"handshake"`) {
		t.Fatalf("connectUpstream 应先发握手，实际读到 %q", hs)
	}

	// 来自 downstream → 转发到 upstream
	m.processPacket(datagram, downAddr)
	got := readOverlayDatagram(t, up, 2*time.Second)
	if got == "" || !strings.Contains(got, `"type":"data_relay"`) {
		t.Fatalf("upstream 未收到转发: %q", got)
	}
	// 线上 JSON 的成员顺序必须与收到时一致（Gson 顺序敏感）
	if !strings.HasPrefix(got, `{"type":"data_relay","from":"X","seq":11`) {
		t.Fatalf("转发改变了成员顺序: %q", got)
	}
	// 同 from:seq 重复 → 去重，不再转发
	m.processPacket(datagram, downAddr)
	if extra := readOverlayDatagram(t, up, 300*time.Millisecond); extra != "" {
		t.Fatalf("重复 seq 被二次转发: %q", extra)
	}

	// to==本节点：交付给 handler，且不转发
	self := `{"type":"data_relay","from":"Y","seq":12,"to":"M","priority":"L2","payload":{"a":1}}`
	m.processPacket(overlayDatagram(t, self), downAddr)
	records := h.records()
	if len(records) != 1 || records[0].from != "Y" || records[0].priority != "L2" {
		t.Fatalf("交付记录不符: %+v", records)
	}
	if v, ok := records[0].payload["a"]; !ok || v.(float64) != 1 {
		t.Fatalf("payload 未透传: %+v", records[0].payload)
	}
	if extra := readOverlayDatagram(t, up, 300*time.Millisecond); extra != "" {
		t.Fatalf("to==自己不应转发: %q", extra)
	}
}

func TestOverlayPingFailureReportsLinkLost(t *testing.T) {
	down := newLocalUDP(t)
	downAddr := udpAddrOfSock(t, down)
	h := &recordingOverlayHandler{}
	m := startOverlay(t, "L", h)
	m.becomeHead("D", downAddr.IP.String(), downAddr.Port)

	// 连续 5 拍无 pong（每拍都换一个新 pending）→ 第 5 拍判死
	for i := 0; i < overlayMaxPingFailures+1; i++ {
		m.pingTask()
	}
	if !waitOverlay(2*time.Second, func() bool { return len(h.lostReasons()) > 0 }) {
		t.Fatalf("未上报链路丢失: failures=%d", m.downstreamPingFailures.Load())
	}
	if got := h.lostReasons()[0]; got != "downstream_timeout" {
		t.Fatalf("reason=%s want downstream_timeout", got)
	}
}

// ---- 测试辅助 ----

func waitOverlay(d time.Duration, cond func() bool) bool {
	deadline := time.Now().Add(d)
	for time.Now().Before(deadline) {
		if cond() {
			return true
		}
		time.Sleep(10 * time.Millisecond)
	}
	return cond()
}

// readOverlayDatagram 读一个 UDP 包并解出 JSON；超时返回 ""。
func readOverlayDatagram(t *testing.T, sock *net.UDPConn, d time.Duration) string {
	t.Helper()
	_ = sock.SetReadDeadline(time.Now().Add(d))
	buf := make([]byte, overlayReadBufferSize)
	n, _, err := sock.ReadFromUDP(buf)
	if err != nil {
		return ""
	}
	return overlayDecompress(buf[:n])
}

var _ = gzip.BestSpeed
