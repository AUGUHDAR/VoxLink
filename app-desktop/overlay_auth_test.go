package main

// overlayAuthV1 的线上验签用例。参照物：network/P2POverlayManager.java
//   :223-234 setLinkKeyForPeer（注入即强制验签）
//   :237-256 signPacket（对"不含 mac"的规范 JSON 做 HMAC-SHA256，前 8 字节小写 hex）
//   :259-285 verifyAndStripPacket（先剥 mac 再重算，MessageDigest.isEqual 比 US-ASCII）
//   :350-395 processPacket（按 from 查密钥；命中就验，失败丢包并计数）
// 模组侧 28 个模板都没有 setLinkKeyForPeer 的调用点，本用例钉的是"注入后的行为"，
// 这样声明能力（ProtocolNegotiator.java:45 的 CURRENT_CAPABILITIES）才有依据。

import (
	"strings"
	"testing"
	"time"
)

func overlaySignedDatagram(t *testing.T, body string, key []byte) []byte {
	t.Helper()
	packet, err := ovParseObject([]byte(body))
	if err != nil {
		t.Fatalf("报文构造失败: %v", err)
	}
	overlaySignPacket(packet, key)
	return overlayDatagram(t, string(packet.marshalGson()))
}

// 已配置密钥的来源：签名正确才交付，签名不符或缺 mac 一律丢弃并计数。
func TestOverlayAuthVerifiesConfiguredPeer(t *testing.T) {
	up := newLocalUDP(t)
	down := newLocalUDP(t)
	upAddr := udpAddrOfSock(t, up)
	downAddr := udpAddrOfSock(t, down)

	h := &recordingOverlayHandler{}
	m := startOverlay(t, "M", h)
	m.connectUpstream("U", upAddr.IP.String(), upAddr.Port)
	m.setDownstream("D", downAddr.IP.String(), downAddr.Port)
	// 排掉 connectUpstream 自己发出的握手，避免和后面的转发混在一起
	if hs := readOverlayDatagram(t, up, 2*time.Second); hs != "" && !strings.Contains(hs, "handshake") {
		t.Fatalf("connectUpstream 应先发握手，实际 %q", hs)
	}

	key := []byte("voxlink-overlay-link-key")
	m.setLinkKeyForPeer("PEER", key)

	// 1) 带正确 mac：交付给本节点 handler
	good := overlaySignedDatagram(t, `{"type":"data_relay","from":"PEER","seq":101,"to":"M","priority":"L1","payload":{"a":1}}`, key)
	before := m.authDropCount.Load()
	m.processPacket(good, downAddr)
	if m.authDropCount.Load() != before {
		t.Fatalf("签名正确的报文被丢弃了（drop %d→%d）", before, m.authDropCount.Load())
	}
	if rec := h.records(); len(rec) != 1 || rec[0].from != "PEER" {
		t.Fatalf("合法报文应交付本节点，实际 %+v", rec)
	}

	// 2) 签名后篡改内容：mac 对不上必须丢包（重放/篡改都走这条）
	tampered := overlaySignedDatagram(t, `{"type":"data_relay","from":"PEER","seq":102,"to":"M","priority":"L1","payload":{"a":1}}`, key)
	tamperedJSON := strings.Replace(string(overlayInflated(t, tampered)), `"seq":102`, `"seq":999`, 1)
	if tamperedJSON == "" {
		t.Fatal("拆不出明文，用例前提不成立")
	}
	before = m.authDropCount.Load()
	m.processPacket(overlayDatagram(t, tamperedJSON), downAddr)
	if m.authDropCount.Load() != before+1 {
		t.Fatalf("篡改报文必须丢包并计数（:367），drop %d→%d", before, m.authDropCount.Load())
	}
	if rec := h.records(); len(rec) != 1 {
		t.Fatalf("篡改报文不得交付，实际 %+v", rec)
	}

	// 3) 已配置来源却不带 mac：按 Java 的 claimed==null 处理为失败（:264-267）
	before = m.authDropCount.Load()
	m.processPacket(overlayDatagram(t, `{"type":"data_relay","from":"PEER","seq":103,"to":"M","priority":"L1","payload":{"a":1}}`), downAddr)
	if m.authDropCount.Load() != before+1 {
		t.Fatalf("缺 mac 的来源必须丢包（:367），drop %d→%d", before, m.authDropCount.Load())
	}

	// 4) 未配置密钥的来源保持旧行为：不验签、照常交付（:61-62/:221）
	m.processPacket(overlayDatagram(t, `{"type":"data_relay","from":"OTHER","seq":104,"to":"M","priority":"L1","payload":{"a":1}}`), downAddr)
	if rec := h.records(); len(rec) != 2 || rec[1].from != "OTHER" {
		t.Fatalf("未配置来源应保持旧行为，实际 %+v", rec)
	}
}

// overlayInflated 把线上数据报还原成明文 JSON（长度前缀 + gzip，POM:673-683/:748-753）。
func overlayInflated(t *testing.T, datagram []byte) string {
	t.Helper()
	s := overlayDecompress(datagram)
	if s == "" {
		t.Fatal("解压失败")
	}
	return s
}

// overlayLinkMacHex 的截断口径：8 字节小写 hex（POM:243-253）。
func TestOverlayLinkMacTruncation(t *testing.T) {
	key := []byte("k")
	packet, err := ovParseObject([]byte(`{"type":"ping","from":"A","seq":1}`))
	if err != nil {
		t.Fatal(err)
	}
	full := packet.marshalGson()
	mac := overlayLinkMacHex(key, full)
	if len(mac) != 16 {
		t.Fatalf("mac 必须是前 8 字节的 hex（16 个字符），得到 %q", mac)
	}
	for _, c := range mac {
		if !strings.ContainsRune("0123456789abcdef", c) {
			t.Fatalf("mac 必须小写十六进制，得到 %q", mac)
		}
	}
	// 签名一次即可，重复调用不得再加 mac
	overlaySignPacket(packet, key)
	again := packet.marshalGson()
	overlaySignPacket(packet, key)
	if string(packet.marshalGson()) != string(again) {
		t.Fatal("已带 mac 的报文不得重复签（POM:239）")
	}
	if !overlayVerifyAndStripPacket(packet, key) {
		t.Fatal("自己签的报文必须验得过")
	}
}
