package main

// stun.go — RFC5389 STUN Binding 的最小实现，字节格式与模组 StunProbe.java 一致：
// 请求 20B：00 01 00 00 + magic cookie(21 12 A4 42) + 随机 12B txid（SOFTWARE 留空）。
// 响应解析 XOR-MAPPED-ADDRESS(0x0020)/0x8020/MAPPED-ADDRESS(0x0001)，
// 端口 XOR 0x2112、IPv4 XOR cookie 全字（0x2112A442），与 StunProbe 的 port^8466 / ip^554869826 同值。

import (
	"crypto/rand"
	"encoding/binary"
	"errors"
	"fmt"
	"net"
	"time"
)

const (
	// stunMagicCookie 是 RFC 5389 规定的固定值 0x2112A442（模组 StunProbe.java 里的 554869826）。
	// 这里曾经写成 0x2112A44D：请求发出去公网服务器不认（直接超时），
	// 且解析时逐字节比对 [4:20] 会把标准响应判成事务不匹配 —— NAT 探测、打洞、中继全链路失效。
	stunMagicCookie uint32 = 0x2112A442
	stunReqType     uint16 = 0x0001 // Binding Request
	stunRespType    uint16 = 0x0101 // Binding Success Response
	stunHeaderLen          = 20
)

// 内嵌公共 STUN 服务器（与模组默认列表风格一致，4-6 个）。
var stunServers = []string{
	"stun.qq.com:3478",
	"stun.miwifi.com:3478",
	"stun1.l.google.com:19302",
	"stun2.l.google.com:19302",
	"stun.cloudflare.com:3478",
	"stun.syncthing.net:3478",
}

// stunMappedAddr 一次 STUN 探测得到的映射地址。
type stunMappedAddr struct {
	IP   string
	Port int
}

func (a stunMappedAddr) valid() bool { return a.IP != "" && a.Port > 0 }

// stunBuildRequest 构造 Binding Request，返回报文（txid 内嵌于 [8..20)）。
func stunBuildRequest() []byte {
	req := make([]byte, stunHeaderLen)
	binary.BigEndian.PutUint16(req[0:2], stunReqType)
	binary.BigEndian.PutUint16(req[2:4], 0) // msg length = 0，无属性（SOFTWARE 留空）
	binary.BigEndian.PutUint32(req[4:8], stunMagicCookie)
	if _, err := rand.Read(req[8:20]); err != nil {
		// crypto/rand 失败极罕见；退化为时间戳填充，仍保证 txid 非全零
		ts := uint64(time.Now().UnixNano())
		for i := 8; i < 20; i++ {
			req[i] = byte(ts >> (uint(i-8) % 64 % 8 * 8))
		}
	}
	return req
}

// stunTxidOf 取请求里的 cookie+txid（响应校验用，同 StunProbe 的 data[4..20) 比对）。
func stunTxidOf(req []byte) []byte { return req[4:20] }

var errStunMismatch = errors.New("stun: 事务ID不匹配")

// stunParseResponse 解析 Binding Success Response，返回 XOR-MAPPED-ADDRESS。
// 字节语义与 StunProbe.parseBindingResponse 完全一致。
func stunParseResponse(data, req []byte) (*stunMappedAddr, error) {
	if len(data) < stunHeaderLen {
		return nil, fmt.Errorf("stun: 响应过短 (%d)", len(data))
	}
	if binary.BigEndian.Uint16(data[0:2]) != stunRespType {
		return nil, fmt.Errorf("stun: 非法响应类型 0x%04x", binary.BigEndian.Uint16(data[0:2]))
	}
	for i := 4; i < 20; i++ {
		if data[i] != req[i] {
			return nil, errStunMismatch
		}
	}
	msgLen := int(binary.BigEndian.Uint16(data[2:4]))
	if max := len(data) - 20; msgLen > max {
		msgLen = max
	}
	offset := 20
	for offset+4 <= len(data) && offset-20 < msgLen {
		attrType := binary.BigEndian.Uint16(data[offset : offset+2])
		attrLen := int(binary.BigEndian.Uint16(data[offset+2 : offset+4]))
		if offset+4+attrLen > len(data) {
			break
		}
		switch attrType {
		case 0x0020, 0x8020: // XOR-MAPPED-ADDRESS / 旧兼容
			return stunParseXorMapped(data, offset, attrLen, req)
		case 0x0001: // MAPPED-ADDRESS
			return stunParseMapped(data, offset, attrLen)
		}
		offset += 4 + attrLen
		if attrLen%4 != 0 {
			offset += 4 - attrLen%4
		}
	}
	return nil, errors.New("stun: 响应无 MAPPED 属性")
}

func stunParseXorMapped(data []byte, off, attrLen int, req []byte) (*stunMappedAddr, error) {
	if attrLen < 8 || off+8 > len(data) {
		return nil, errors.New("stun: XOR 属性过短")
	}
	family := data[off+5]
	// RFC5389：端口与 magic cookie 高 16 位（0x2112）异或（模组 port ^ 8466 同款）
	xorPort := int(binary.BigEndian.Uint16(data[off+6 : off+8]))
	port := xorPort ^ int(stunMagicCookie>>16)
	switch family {
	case 1: // IPv4
		if off+12 > len(data) {
			return nil, errors.New("stun: IPv4 XOR 属性过短")
		}
		xorIP := binary.BigEndian.Uint32(data[off+8 : off+12])
		ip := xorIP ^ stunMagicCookie
		return &stunMappedAddr{
			IP:   fmt.Sprintf("%d.%d.%d.%d", byte(ip>>24), byte(ip>>16), byte(ip>>8), byte(ip)),
			Port: port,
		}, nil
	case 2: // IPv6：与 req[4:20] 逐字节异或
		if attrLen < 20 || off+24 > len(data) {
			return nil, errors.New("stun: IPv6 XOR 属性过短")
		}
		ipBytes := make([]byte, 16)
		for i := 0; i < 16; i++ {
			ipBytes[i] = data[off+8+i] ^ req[4+i]
		}
		ip := net.IP(ipBytes)
		return &stunMappedAddr{IP: ip.String(), Port: port}, nil
	}
	return nil, fmt.Errorf("stun: 未知地址族 %d", family)
}

func stunParseMapped(data []byte, off, attrLen int) (*stunMappedAddr, error) {
	if attrLen < 8 || off+12 > len(data) {
		return nil, errors.New("stun: MAPPED 属性过短")
	}
	if data[off+5] != 1 {
		return nil, errors.New("stun: MAPPED 非 IPv4")
	}
	return &stunMappedAddr{
		IP:   fmt.Sprintf("%d.%d.%d.%d", data[off+8], data[off+9], data[off+10], data[off+11]),
		Port: int(binary.BigEndian.Uint16(data[off+6 : off+8])),
	}, nil
}

// stunQuery 对单个 STUN 服务器发送请求，带重试，返回映射地址。
func stunQuery(uc *net.UDPConn, server string, attempts int, timeout time.Duration) (*stunMappedAddr, error) {
	raddr, err := net.ResolveUDPAddr("udp", server)
	if err != nil {
		return nil, fmt.Errorf("stun: 解析 %s 失败: %w", server, err)
	}
	var lastErr error
	for i := 0; i < attempts; i++ {
		req := stunBuildRequest()
		if _, err := uc.WriteToUDP(req, raddr); err != nil {
			lastErr = err
			continue
		}
		buf := make([]byte, 576)
		deadline := time.Now().Add(timeout)
		for {
			uc.SetReadDeadline(deadline)
			n, _, err := uc.ReadFromUDP(buf)
			if err != nil {
				lastErr = err
				break // 本轮超时
			}
			if n >= stunHeaderLen && binary.BigEndian.Uint16(buf[0:2]) == stunRespType {
				if addr, perr := stunParseResponse(buf[:n], req); perr == nil {
					uc.SetReadDeadline(time.Time{})
					return addr, nil
				} else if !errors.Is(perr, errStunMismatch) {
					lastErr = perr
				}
				// txid 不匹配（迟到的旧响应）→ 继续读
			}
			if time.Now().After(deadline) {
				break
			}
		}
	}
	uc.SetReadDeadline(time.Time{})
	if lastErr == nil {
		lastErr = errors.New("stun: 无响应")
	}
	return nil, lastErr
}

// stunSampleSeries 在同一 socket 上对最多 n 个可达服务器采样映射地址，
// 返回样本序列（用于端口增量 delta 估计与预测）。单个样本最多 tries 次尝试。
func stunSampleSeries(uc *net.UDPConn, servers []string, n, tries int, timeout time.Duration) []stunMappedAddr {
	if n <= 0 || n > len(servers) {
		n = len(servers)
	}
	out := make([]stunMappedAddr, 0, n)
	for i := 0; i < n && i < len(servers); i++ {
		if a, err := stunQuery(uc, servers[i], tries, timeout); err == nil && a.valid() {
			out = append(out, *a)
		}
	}
	return out
}

// stunDeltaFromSamples 估计同一 socket 多次映射的端口增量（供端口预测），
// 语义对齐模组 hostMappedPortDelta：末次样本端口 - 首次样本端口。
func stunDeltaFromSamples(samples []stunMappedAddr) int {
	if len(samples) < 2 {
		return 0
	}
	return samples[len(samples)-1].Port - samples[0].Port
}

// stunSamplePorts 顺序采样端口序列（模组 StunProbe.samplePortsSequential 同款）：
// 服务器轮转取满 count 个样本；单服务器 tries 次尝试。用于端口预测回归。
func stunSamplePorts(uc *net.UDPConn, servers []string, count, tries int, timeout time.Duration) []int {
	if len(servers) == 0 || count <= 0 {
		return nil
	}
	ports := make([]int, 0, count)
	for i := 0; len(ports) < count; i++ {
		if i > count*3 {
			break
		}
		if a, err := stunQuery(uc, servers[i%len(servers)], tries, timeout); err == nil && a.valid() {
			ports = append(ports, a.Port)
		}
	}
	return ports
}

// stunPredictForOffer 对称 NAT 时对打洞 socket 顺序采样并预测（模组 RoomManager
// P-PRE 同款：≥5 样本才可信；reliableDelta<=0 时退回两采样差）。
// 返回 delta 与 ±range；样本不足返回 (0,0)。
func stunPredictForOffer(uc *net.UDPConn) (delta, r int) {
	samples := stunSamplePorts(uc, stunServers, 10, 1, 300*time.Millisecond)
	if len(samples) < 5 {
		return 0, 0
	}
	pr := predict(samples)
	reliable := deltaPredict(samples) - samples[len(samples)-1]
	if reliable <= 0 {
		reliable = samples[len(samples)-1] - samples[0]
	}
	return reliable, pr.rangeHalf
}
