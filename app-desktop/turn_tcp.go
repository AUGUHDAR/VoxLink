package main

// turn_tcp.go — TURN TCP 兜底承载（对齐模组 TurnTcpChannel.java）：
// 本地回环 UDP shim ↔ 节点 TCP 长连接；帧 = 2 字节大端长度 + 与 UDP 同构报文（≤2048）。
// 对上层透明：TurnSession 的 socket 指向 shim 回环口，bind/keepalive/rudp 零感知。

import (
	"encoding/binary"
	"fmt"
	"net"
	"sync"
	"sync/atomic"
	"time"
)

const (
	turnTcpFrameMax       = 2048
	turnTcpConnectTimeout = 3 * time.Second
	turnTcpShimPoll       = 100 * time.Millisecond
)

type turnTcpChannel struct {
	tcp        net.Conn
	client     *net.UDPConn // 上层用的回环口
	shim       *net.UDPConn // 与 TCP 之间的回环口
	clientAddr *net.UDPAddr
	closed     atomic.Bool
	writeMu    sync.Mutex
}

// turnTcpOpen 建立到节点的 TCP 通道；preferLocalPort>0 时 client 口复用原 UDP 端口
// （保住节点侧会话身份连续性，端口被抢则退随机口）。
func turnTcpOpen(nodeHost string, nodePort, preferLocalPort int) (*turnTcpChannel, error) {
	tcp, err := net.DialTimeout("tcp", fmt.Sprintf("%s:%d", nodeHost, nodePort), turnTcpConnectTimeout)
	if err != nil {
		return nil, err
	}
	if tc, ok := tcp.(*net.TCPConn); ok {
		_ = tc.SetNoDelay(true)
	}

	var client *net.UDPConn
	if preferLocalPort > 0 {
		if uc, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.ParseIP("127.0.0.1"), Port: preferLocalPort}); err == nil {
			client = uc
		}
	}
	if client == nil {
		uc, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.ParseIP("127.0.0.1"), Port: 0})
		if err != nil {
			_ = tcp.Close()
			return nil, err
		}
		client = uc
	}

	shim, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.ParseIP("127.0.0.1"), Port: 0})
	if err != nil {
		_ = client.Close()
		_ = tcp.Close()
		return nil, err
	}
	_ = shim.SetReadDeadline(time.Now().Add(turnTcpShimPoll))
	_ = client.SetReadDeadline(time.Now().Add(turnTcpShimPoll))

	clientAddr := &net.UDPAddr{IP: net.ParseIP("127.0.0.1"), Port: client.LocalAddr().(*net.UDPAddr).Port}
	ch := &turnTcpChannel{tcp: tcp, client: client, shim: shim, clientAddr: clientAddr}
	go ch.pumpUdpToTcp()
	go ch.pumpTcpToUdp()
	return ch, nil
}

func (ch *turnTcpChannel) clientSocket() *net.UDPConn { return ch.client }
func (ch *turnTcpChannel) shimPort() int {
	if a, ok := ch.shim.LocalAddr().(*net.UDPAddr); ok {
		return a.Port
	}
	return 0
}

// pumpUdpToTcp shim 收包 → TCP 帧。任一方向断开即整体关闭（链路已死）。
func (ch *turnTcpChannel) pumpUdpToTcp() {
	buf := make([]byte, turnTcpFrameMax)
	for !ch.closed.Load() {
		_ = ch.shim.SetReadDeadline(time.Now().Add(turnTcpShimPoll))
		n, _, err := ch.shim.ReadFromUDP(buf)
		if err != nil {
			if ch.closed.Load() {
				return
			}
			continue
		}
		if n <= 0 {
			continue
		}
		ch.writeMu.Lock()
		head := make([]byte, 2)
		binary.BigEndian.PutUint16(head, uint16(n))
		_, werr := ch.tcp.Write(append(head, buf[:n]...))
		ch.writeMu.Unlock()
		if werr != nil {
			if !ch.closed.Load() {
				ch.close()
			}
			return
		}
	}
}

func (ch *turnTcpChannel) pumpTcpToUdp() {
	head := make([]byte, 2)
	for !ch.closed.Load() {
		if _, err := turnTcpReadFull(ch.tcp, head); err != nil {
			if !ch.closed.Load() {
				ch.close()
			}
			return
		}
		length := int(binary.BigEndian.Uint16(head))
		if length == 0 || length > turnTcpFrameMax {
			ch.close()
			return
		}
		pkt := make([]byte, length)
		if _, err := turnTcpReadFull(ch.tcp, pkt); err != nil {
			if !ch.closed.Load() {
				ch.close()
			}
			return
		}
		_, _ = ch.shim.WriteToUDP(pkt, ch.clientAddr)
	}
}

func turnTcpReadFull(c net.Conn, buf []byte) (int, error) {
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

// close 幂等关闭：TCP 死亡时 client 口随之关，rudp 收包循环据此退出。
// 半构造状态（任一句柄为 nil，如 shim 创建失败前）也必须安全。
func (ch *turnTcpChannel) close() {
	if ch.closed.Swap(true) {
		return
	}
	if ch.tcp != nil {
		_ = ch.tcp.Close()
	}
	if ch.shim != nil {
		_ = ch.shim.Close()
	}
	if ch.client != nil {
		_ = ch.client.Close()
	}
}
