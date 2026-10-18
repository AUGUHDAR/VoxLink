package main

// udp_forward.go — 通用 UDP 转发桥（逐句对齐模组 network/UdpForwardBridge.java，1.2.0，155 行）
// + 承载它的 ReliableUdpTransport 语音口（network/ReliableUdpTransport.java:452-495）。
//
// 用途：把"本机局域网服务端口的 UDP"（语音 24454 / MC 25565，ConnectionManager.java:15767
// `new UdpForwardBridge(transport, isHost, 24454, 25565)`）经已打通的 ReliableUdp 数据面
// best-effort 双向搬到对端同端口。纯兼容件：没有它零影响，不打新洞。
//
// host/joiner 的非对称绑法（:17-20 类注释，务必照抄）：
//   - joiner(hostSide=false)：绑 loopback:<port> 收本地客户端（voice-chat 等）发包并中继；
//   - host(hostSide=true) ：不绑端口（本机语音/服务端已占用），用一个临时出站 socket 与本机服务对话，
//     回包写回"最近给本机服务发过包的地址"（:94-95 localService）。
//
// 线上信封（:24 PORT_HEADER_LEN、:85-88/:118-120）：
//
//	VOICE 帧载荷 = [2 字节大端 端口][原始 UDP 载荷]
//
// 即 rudp 的 VOICE 帧（type=11，11 字节头 + 载荷，无 payloadLen 字段）里再套一层端口头。
// 收发两侧都以"本桥的 port"回填端口头（:119 writeUint16(envelope, 0, this.port)），
// 收到的信封按头部端口分诊给对应 PortBridge（:52-62）。

import (
	"encoding/binary"
	"errors"
	"fmt"
	"net"
	"sync"
	"sync/atomic"
)

const (
	udpFwdMaxVoicePayload = 1400 // :23 MAX_VOICE_PAYLOAD（同时也是单包上限）
	udpFwdPortHeaderLen   = 2    // :24 PORT_HEADER_LEN
	// :114 的丢包门：收包长度 >= MAX_VOICE_PAYLOAD-PORT_HEADER_LEN(=1398) 直接丢弃
	udpFwdOversizeDropAt = udpFwdMaxVoicePayload - udpFwdPortHeaderLen
)

// ---- rudpConn 的语音口（ReliableUdpTransport.java:452-495 的对应实现） ----
//
// 说明：这两个方法本应长在 rudp.go 的 rudpConn 上（与模组同构），但本轮文件归属规定
// 我不能改 rudp.go，故：方法定义在本文件（Go 允许同包任意文件为类型加方法），
// 回调存储用包级表按 *rudpConn 索引。rudp.go 侧只需两行接线（交付报告 hunk R1/R2）：
// processFrame 的 rudpTypeVoice 分支从"忽略"改为 c.handleVoice(f)，
// 且 Close 时 c.clearVoiceHandler()。

var rudpVoiceHooks sync.Map // *rudpConn → func([]byte)

// setOnVoiceData 对应 ReliableUdpTransport.java:452-454（传 nil 即摘掉）。
func (c *rudpConn) setOnVoiceData(fn func([]byte)) {
	if c == nil {
		return
	}
	if fn == nil {
		rudpVoiceHooks.Delete(c)
		return
	}
	rudpVoiceHooks.Store(c, fn)
}

func (c *rudpConn) voiceHandler() func([]byte) {
	if c == nil {
		return nil
	}
	v, ok := rudpVoiceHooks.Load(c)
	if !ok {
		return nil
	}
	fn, _ := v.(func([]byte))
	return fn
}

// clearVoiceHandler 关闭时清表项（防 *rudpConn 长期驻留在 sync.Map 里）。
func (c *rudpConn) clearVoiceHandler() { rudpVoiceHooks.Delete(c) }

// sendVoice 对应 :462-483。
// 构造 socket 检查放宽：TURN→直连平滑切换后旧 TURN socket 已关闭，但主路径已 promote 到
// 直连 socket —— 只看旧 socket 会让语音永久哑掉（模组审计 P2-4 的教训，:463-464 注释）。
// 本端 rudpConn 只有一个 socket 字段，故 anySendable 落在"socket 存在且未关"。
func (c *rudpConn) sendVoice(payload []byte) {
	if payload == nil {
		return
	}
	if c.conn == nil || c.closed.Load() {
		return
	}
	if len(payload) > c.maxSendChunk() { // :469 按当前主路径的发送分块上限
		c.rlog("debug", "Voice payload too large (%d), dropped", len(payload))
		return
	}
	c.sendFrame(rudpFrame{Type: rudpTypeVoice, Payload: payload})
}

// handleVoice 对应 :485-495：len>11（即载荷非空）才交给回调，回调异常绝不影响 MC 数据面。
func (c *rudpConn) handleVoice(f rudpFrame) {
	h := c.voiceHandler()
	if h == nil || len(f.Payload) == 0 {
		return
	}
	defer func() {
		if r := recover(); r != nil {
			c.rlog("warn", "voice handler exception: %v", r)
		}
	}()
	h(f.Payload)
}

// ---- 转发桥 ----

type udpForwardBridge struct {
	transport *rudpConn
	hostSide  bool

	mu      sync.Mutex
	bridges []*udpForwardPortBridge

	closed atomic.Bool
	logf   func(level, msg string)
}

// udpForwardRegistry 对应 ConnectionManager.java:164 的
// `List<UdpForwardBridge> udpForwardBridges`（本端不能改 conn_engine.go，故放在包级表，
// closeAllUdpForwardBridges 即 CM:15789-15805）。
var udpForwardRegistry = struct {
	mu  sync.Mutex
	all []*udpForwardBridge
}{}

func newUdpForwardBridge(transport *rudpConn, hostSide bool, localPorts []int, logf func(level, msg string)) *udpForwardBridge {
	b := &udpForwardBridge{transport: transport, hostSide: hostSide, logf: logf}
	for _, port := range localPorts {
		var sock *net.UDPConn
		var err error
		if hostSide {
			// host 出站中继：不绑服务端口，仅建一个临时 socket 与本机服务对话（:37-39）
			sock, err = net.ListenUDP("udp", &net.UDPAddr{Port: 0})
		} else {
			// joiner：绑 loopback:port 收本地客户端发包（:40 InetAddress.getLoopbackAddress()）
			sock, err = net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: port})
		}
		if err != nil {
			b.log("warn", "[UdpForward] skip port %d (%s): %s", port, udpFwdModeName(hostSide), err.Error())
			continue
		}
		pb := &udpForwardPortBridge{socket: sock, port: port, owner: b}
		pb.running.Store(true)
		b.mu.Lock()
		b.bridges = append(b.bridges, pb)
		b.mu.Unlock()
		pb.start()
		b.log("info", "[UdpForward] voice relay %s on loopback:%d", udpFwdModeName(hostSide), port)
	}

	if len(b.bridges) > 0 {
		transport.setOnVoiceData(func(envelope []byte) {
			if envelope == nil || len(envelope) < udpFwdPortHeaderLen || b.closed.Load() {
				return
			}
			destPort := int(udpFwdReadUint16(envelope, 0))
			b.mu.Lock()
			targets := make([]*udpForwardPortBridge, len(b.bridges))
			copy(targets, b.bridges)
			b.mu.Unlock()
			for _, t := range targets {
				if t.port == destPort {
					t.deliver(envelope)
					break
				}
			}
		})
		udpForwardRegistry.mu.Lock()
		udpForwardRegistry.all = append(udpForwardRegistry.all, b)
		udpForwardRegistry.mu.Unlock()
	}
	return b
}

// udpFwdModeName 对应 :45/:47 的 `hostSide ? "host-relay" : "bound"`。
func udpFwdModeName(hostSide bool) string {
	if hostSide {
		return "host-relay"
	}
	return "bound"
}

func (b *udpForwardBridge) log(level, format string, args ...any) {
	if b.logf == nil {
		return
	}
	// 与模组一致：这一族日志文本自带 [UdpForward] 前缀（:45/:47/:77/:124/:143），
	// format 里已含，级别由调用点显式给出（LOGGER.info/warn/debug）。
	b.logf(level, fmt.Sprintf(format, args...))
}

// isActive 对应 :66-68。
func (b *udpForwardBridge) isActive() bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	return len(b.bridges) > 0
}

// 模组写死的两个语音端口（CM:15767：24454 与 25565）。
var udpForwardVoicePorts = []int{24454, 25565}

// startVoiceForwardBridge = CM:15761-15785：给一条刚建立的传输挂语音转发桥。
// 只挂在打洞出来的直连传输上——模组唯一的调用点在 establishUdpTransport 末尾（CM:15755），
// TURN 传输（CM:11678/11864/12001/12143）与玩家中继传输（CM:11273/13345）都不挂。
// 一个端口都没绑上就立刻关掉（CM:15769-15777）；起桥异常按 CM:15779-15783 只留一行 warn。
func startVoiceForwardBridge(transport *rudpConn, isHost bool, logf func(level, msg string)) *udpForwardBridge {
	if transport == nil {
		return nil
	}
	defer func() {
		if r := recover(); r != nil && logf != nil {
			logf("warn", fmt.Sprintf("[UdpForward] voice relay failed to start: %v", r))
		}
	}()
	b := newUdpForwardBridge(transport, isHost, udpForwardVoicePorts, logf)
	if !b.isActive() {
		b.close()
		return nil
	}
	return b
}

// close 对应 :70-79（幂等）。
func (b *udpForwardBridge) close() {
	if !b.closed.CompareAndSwap(false, true) {
		return
	}
	b.transport.setOnVoiceData(nil)
	b.mu.Lock()
	targets := make([]*udpForwardPortBridge, len(b.bridges))
	copy(targets, b.bridges)
	b.mu.Unlock()
	for _, t := range targets {
		t.close()
	}
	b.log("info", "[UdpForward] voice relay closed")

	udpForwardRegistry.mu.Lock()
	for i, x := range udpForwardRegistry.all {
		if x == b {
			udpForwardRegistry.all = append(udpForwardRegistry.all[:i], udpForwardRegistry.all[i+1:]...)
			break
		}
	}
	udpForwardRegistry.mu.Unlock()
}

// closeAllUdpForwardBridges 对应 ConnectionManager.java:15789-15805（在
// killAllConnectionAttempts 里被调用，CM:13803）。
func closeAllUdpForwardBridges() {
	udpForwardRegistry.mu.Lock()
	all := udpForwardRegistry.all
	udpForwardRegistry.all = nil
	udpForwardRegistry.mu.Unlock()
	for _, b := range all {
		b.close()
	}
}

// ---- 端口桥 ----

type udpForwardPortBridge struct {
	socket *net.UDPConn
	port   int
	owner  *udpForwardBridge

	running atomic.Bool
	// hostSide：记录最近给本机服务发过包的地址（即本机服务端），回包写回它（:94-95）
	localService atomic.Pointer[net.UDPAddr]
}

func (pb *udpForwardPortBridge) start() {
	go pb.receiveLoop()
}

// receiveLoop 对应 :108-128。
func (pb *udpForwardPortBridge) receiveLoop() {
	buf := make([]byte, udpFwdMaxVoicePayload)
	for pb.running.Load() {
		sock := pb.socket
		if sock == nil {
			return
		}
		n, from, err := sock.ReadFromUDP(buf)
		if err != nil {
			if !pb.running.Load() || errors.Is(err, net.ErrClosed) {
				return
			}
			pb.owner.log("debug", "[UdpForward] recv loop error on port %d: %s", pb.port, err.Error())
			continue
		}
		if n >= udpFwdOversizeDropAt { // :114-116
			continue
		}
		if from != nil {
			pb.localService.Store(from)
		}
		envelope := make([]byte, udpFwdPortHeaderLen+n)
		udpFwdWriteUint16(envelope, 0, pb.port) // :119 回填本桥端口
		copy(envelope[udpFwdPortHeaderLen:], buf[:n])
		pb.owner.transport.sendVoice(envelope) // :121
	}
}

// deliver 对应 :130-145：收到对端 VOICE 帧 → 交给本地。
// host 写回本机服务（localService），joiner 写回本地客户端（同样是 localService，
// 未建立时兜底 loopback:port —— 模组 :135 已把原来的死三元表达式清理掉）。
func (pb *udpForwardPortBridge) deliver(envelope []byte) {
	payloadLen := len(envelope) - udpFwdPortHeaderLen
	if payloadLen < 0 {
		return
	}
	payload := make([]byte, payloadLen)
	copy(payload, envelope[udpFwdPortHeaderLen:])
	target := pb.localService.Load()
	if target == nil {
		target = &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: pb.port}
	}
	if _, err := pb.socket.WriteToUDP(payload, target); err != nil {
		pb.owner.log("debug", "[UdpForward] deliver failed on port %d: %s", pb.port, err.Error())
	}
}

func (pb *udpForwardPortBridge) close() {
	pb.running.Store(false)
	if pb.socket != nil {
		_ = pb.socket.Close()
	}
}

// ---- 端口头读写（:81-88） ----

func udpFwdReadUint16(buf []byte, off int) uint16 {
	return binary.BigEndian.Uint16(buf[off : off+2])
}

func udpFwdWriteUint16(buf []byte, off int, value int) {
	buf[off] = byte(value >> 8)
	buf[off+1] = byte(value)
}
