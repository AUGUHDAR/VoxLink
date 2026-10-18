package main

// stun_rfc_test.go — STUN 字节的"外部事实"校验。
//
// 为什么单独建一个文件：原有 engine_test.go 里的 STUN 用例用 stunMagicCookie 常量拼伪服务器，
// 常量本身写错（曾经写成 0x2112A44D）时两边一起错，测试全绿而线上全崩。
// 这里改为断言 RFC 5389 的字面量与一段手算的黄金报文，常量再写错就会红。

import (
	"encoding/binary"
	"net"
	"testing"
)

func TestStunMagicCookieIsRFCValue(t *testing.T) {
	if stunMagicCookie != 0x2112A442 {
		t.Fatalf("magic cookie = 0x%08X，RFC 5389 规定 0x2112A442", stunMagicCookie)
	}
	// 模组 StunProbe.java 用的是十进制 554869826，两端必须同值才能解出同一个地址。
	if got, want := int64(stunMagicCookie), int64(554869826); got != want {
		t.Fatalf("magic cookie %d 与模组侧 %d 不一致", got, want)
	}
}

func TestStunRequestCarriesCookieLiteral(t *testing.T) {
	req := stunBuildRequest()
	if len(req) != 20 {
		t.Fatalf("请求长度 %d，应为 20", len(req))
	}
	want := []byte{0x21, 0x12, 0xA4, 0x42}
	got := req[4:8]
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("请求第 %d 字节 = 0x%02X，应为 0x%02X（cookie 字面量）", 4+i, got[i], want[i])
		}
	}
	if binary.BigEndian.Uint16(req[0:2]) != stunReqType {
		t.Fatalf("消息类型 = 0x%04X，应为 0x0001", binary.BigEndian.Uint16(req[0:2]))
	}
}

// TestStunParseGoldenResponse 解一段按 RFC 手算的响应：
// XOR-MAPPED-ADDRESS 指向 203.0.113.9:46012。
// 端口域 = 46012^0x2112 = 0x92AE；IPv4 域 = 0xCB007109^0x2112A442 = 0xEA12D54B。
func TestStunParseGoldenResponse(t *testing.T) {
	txid := []byte{0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C}
	req := make([]byte, 20)
	binary.BigEndian.PutUint16(req[0:2], stunReqType)
	copy(req[4:8], []byte{0x21, 0x12, 0xA4, 0x42})
	copy(req[8:20], txid)

	resp := []byte{
		0x01, 0x01, 0x00, 0x0C,
		0x21, 0x12, 0xA4, 0x42,
		0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C,
		// XOR-MAPPED-ADDRESS: family=1, port=0x92AE, ip=0xEA12D54B
		0x00, 0x20, 0x00, 0x08, 0x00, 0x01, 0x92, 0xAE, 0xEA, 0x12, 0xD5, 0x4B,
	}
	addr, err := stunParseResponse(resp, req)
	if err != nil {
		t.Fatalf("黄金响应解析失败: %v", err)
	}
	if addr.IP != "203.0.113.9" || addr.Port != 46012 {
		t.Fatalf("解出 %s:%d，期望 203.0.113.9:46012", addr.IP, addr.Port)
	}
}

// TestStunRejectsForeignCookie 别的 cookie（例如历史误用的 0x2112A44D）必须判事务不匹配，
// 不能被当成合法响应——上一版正是"自己发自己解"所以没人发现。
func TestStunRejectsForeignCookie(t *testing.T) {
	req := stunBuildRequest()
	resp := []byte{
		0x01, 0x01, 0x00, 0x0C,
		0x21, 0x12, 0xA4, 0x4D, // 错 cookie
		req[8], req[9], req[10], req[11], req[12], req[13], req[14], req[15], req[16], req[17], req[18], req[19],
		0x00, 0x20, 0x00, 0x08, 0x00, 0x01, 0x92, 0xAE, 0xEA, 0x12, 0xD5, 0x4B,
	}
	if _, err := stunParseResponse(resp, req); err == nil {
		t.Fatal("错 cookie 的响应被接受了")
	}
}

// TestNetUDPAddrHostPortRejectsNonLiteral 域名/非法端口一律返回 nil：
// 标准 TURN 的两处 ChannelBind 依赖这个 nil 来做 F12 防护（模组 1.2.0 同源修复），
// 否则会拿 nil IP 去 putXorAddress，直接崩进程。
func TestNetUDPAddrHostPortRejectsNonLiteral(t *testing.T) {
	cases := []struct {
		host string
		port int
		ok   bool
	}{
		{"192.0.2.7", 40000, true},
		{"2001:db8::1", 40000, true},
		{"evil.com", 40000, false},
		{"192.0.2.7", 0, false},
		{"", 40000, false},
	}
	for _, c := range cases {
		got := netUDPAddrHostPort(c.host, c.port)
		if c.ok && (got == nil || got.IP == nil) {
			t.Fatalf("%s:%d 应解析成功", c.host, c.port)
		}
		if !c.ok && got != nil {
			t.Fatalf("%s:%d 应返回 nil，实际 %+v", c.host, c.port, got)
		}
	}
	if a := net.ParseIP("999.1.1.1"); a != nil {
		t.Fatal("net.ParseIP 对越界四段组返回了非 nil，用例前提变了")
	}
}
