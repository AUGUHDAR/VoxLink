package main

// 语音转发桥的用例。参照物：
//   UdpForwardBridge.java :37-47（host 不绑端口、joiner 绑 loopback:port、绑不上就跳过）
//   :108-128（上行信封 = 2 字节大端目的端口 + 原载荷，>=1398 丢）
//   :130-145（下行按 destPort 匹配桥，交给本机客户端）
//   ConnectionManager.java :15755/15761-15785（只有打洞出来的直连传输挂桥；一个口都没绑上就关）
//   :13803 → :15789-15805（停一切连接工作时收桥）
//   ReliableUdpTransport.java :723-725 → :485-495（VOICE 帧交给语音回调）

import (
	"net"
	"sync"
	"testing"
	"time"
)

type voiceLog struct {
	mu   sync.Mutex
	rows []string
}

func (v *voiceLog) write(level, msg string) {
	v.mu.Lock()
	defer v.mu.Unlock()
	v.rows = append(v.rows, level+" "+msg)
}

func (v *voiceLog) has(sub string) bool {
	v.mu.Lock()
	defer v.mu.Unlock()
	for _, r := range v.rows {
		if len(r) >= len(sub) && contains(r, sub) {
			return true
		}
	}
	return false
}

func contains(s, sub string) bool {
	for i := 0; i+len(sub) <= len(s); i++ {
		if s[i:i+len(sub)] == sub {
			return true
		}
	}
	return false
}

// waitLocalService 等端口桥记下"最近给本机发过包的地址"（最多 3 秒）。
func waitLocalService(t *testing.T, br *udpForwardBridge, port int) {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		br.mu.Lock()
		var svc *net.UDPAddr
		for _, pb := range br.bridges {
			if pb.port == port {
				svc = pb.localService.Load()
			}
		}
		br.mu.Unlock()
		if svc != nil {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("桥始终没记下本机客户端地址，下行交付目标无从确定")
}

func loopbackUDP(t *testing.T, port int) *net.UDPConn {
	t.Helper()
	c, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: port})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = c.Close() })
	return c
}

func freeUDPPort(t *testing.T) int {
	t.Helper()
	c := loopbackUDP(t, 0)
	defer c.Close()
	return c.LocalAddr().(*net.UDPAddr).Port
}

// 上行：本机客户端发到绑定口的数据报，必须以 VOICE 帧出去，且前面加了 2 字节大端目的端口。
func TestVoiceUplinkAddsDestPortEnvelope(t *testing.T) {
	if testing.Short() {
		t.Skip("short 模式跳过 socket 测试")
	}
	a := loopbackUDP(t, 0)
	b := loopbackUDP(t, 0)
	addrA := a.LocalAddr().(*net.UDPAddr)
	rcB := newRudp(b, addrA)

	port := freeUDPPort(t)
	lg := &voiceLog{}
	br := newUdpForwardBridge(rcB, false, []int{port}, lg.write)
	if !br.isActive() {
		t.Fatal("joiner 侧必须绑上 loopback 端口")
	}
	t.Cleanup(br.close)

	client := loopbackUDP(t, 0)
	target := &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: port}
	if _, err := client.WriteToUDP([]byte("voice-payload"), target); err != nil {
		t.Fatal(err)
	}

	buf := make([]byte, 2048)
	_ = a.SetReadDeadline(time.Now().Add(3 * time.Second))
	n, _, err := a.ReadFromUDP(buf)
	if err != nil {
		t.Fatalf("桥没把上行送进传输: %v", err)
	}
	f, ok := rudpDecode(buf[:n])
	if !ok || f.Type != rudpTypeVoice {
		t.Fatalf("出去的必须是 VOICE 帧，得到 %+v ok=%v", f, ok)
	}
	wantPort := []byte{byte(port >> 8), byte(port)}
	if len(f.Payload) < 2 || f.Payload[0] != wantPort[0] || f.Payload[1] != wantPort[1] {
		t.Fatalf("信封前 2 字节必须是大端目的端口 %d，得到 %v", port, f.Payload[:min(4, len(f.Payload))])
	}
	if string(f.Payload[2:]) != "voice-payload" {
		t.Fatalf("载荷必须原样透传，得到 %q", f.Payload[2:])
	}
}

// 下行：收到 VOICE 帧后必须交付给最近给本机发过包的客户端（剥掉 2 字节端口头）。
// 这条同时钉住 rudp 收包分派：VOICE 帧只 markRecv 就丢，语音模块永远收不到东西。
func TestVoiceDownlinkDeliveredToLocalClient(t *testing.T) {
	if testing.Short() {
		t.Skip("short 模式跳过 socket 测试")
	}
	a := loopbackUDP(t, 0)
	b := loopbackUDP(t, 0)
	addrA := a.LocalAddr().(*net.UDPAddr)
	addrB := b.LocalAddr().(*net.UDPAddr)
	rcA := newRudp(a, addrB)
	rcA.Start()
	t.Cleanup(func() { _ = rcA.Close() })

	port := freeUDPPort(t)
	lg := &voiceLog{}
	br := newUdpForwardBridge(rcA, false, []int{port}, lg.write)
	t.Cleanup(br.close)

	// 本机客户端先发一包，桥据此记住回包地址（UFB:117 localService）
	client := loopbackUDP(t, 0)
	if _, err := client.WriteToUDP([]byte("hi"), &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: port}); err != nil {
		t.Fatal(err)
	}
	_ = b.SetReadDeadline(time.Now().Add(2 * time.Second))
	drain := make([]byte, 512)
	if n, _, err := b.ReadFromUDP(drain); err == nil { // 排掉刚才那包的上行信封
		_ = n
	}
	// 必须等桥把本机客户端地址记下来再发下行包：没记下时它会退回写给自己，
	// 用例就会偶发收不到（UFB:117 与 :136-139 的两条分支）。
	waitLocalService(t, br, port)

	env := make([]byte, 2+len("downlink-voice"))
	udpFwdWriteUint16(env, 0, port)
	copy(env[2:], "downlink-voice")
	raw := rudpEncode(rudpFrame{Type: rudpTypeVoice, Payload: env})
	if _, err := b.WriteToUDP(raw, addrA); err != nil {
		t.Fatal(err)
	}

	_ = client.SetReadDeadline(time.Now().Add(3 * time.Second))
	got := make([]byte, 512)
	n, _, err := client.ReadFromUDP(got)
	if err != nil {
		t.Fatalf("本地客户端没收到下行语音（VOICE 帧是否被交付？）: %v", err)
	}
	if string(got[:n]) != "downlink-voice" {
		t.Fatalf("下行必须剥掉 2 字节端口头，得到 %q", got[:n])
	}
}

// 超长载荷直接丢（UFB:114-116），并且不能把正常包一起带崩。
func TestVoiceOversizeDropped(t *testing.T) {
	if testing.Short() {
		t.Skip("short 模式跳过 socket 测试")
	}
	a := loopbackUDP(t, 0)
	b := loopbackUDP(t, 0)
	addrA := a.LocalAddr().(*net.UDPAddr)
	rcB := newRudp(b, addrA)

	port := freeUDPPort(t)
	br := newUdpForwardBridge(rcB, false, []int{port}, (&voiceLog{}).write)
	t.Cleanup(br.close)

	client := loopbackUDP(t, 0)
	target := &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: port}
	if _, err := client.WriteToUDP(make([]byte, udpFwdOversizeDropAt), target); err != nil {
		t.Fatal(err)
	}
	_ = a.SetReadDeadline(time.Now().Add(400 * time.Millisecond))
	buf := make([]byte, 4096)
	if n, _, err := a.ReadFromUDP(buf); err == nil {
		t.Fatalf("%d 字节的包必须被丢掉，实际收到 %d 字节", udpFwdOversizeDropAt, n)
	}
	// 同一条桥随后仍能转发正常包
	if _, err := client.WriteToUDP([]byte("ok"), target); err != nil {
		t.Fatal(err)
	}
	_ = a.SetReadDeadline(time.Now().Add(2 * time.Second))
	if _, _, err := a.ReadFromUDP(buf); err != nil {
		t.Fatalf("超长包不该把桥打死：正常包没出去: %v", err)
	}
}

// 一个端口都没绑上 → 桥不登记并立刻关掉（CM:15769-15777），startVoiceForwardBridge 返回 nil。
func TestStartVoiceBridgeClosesWhenNoPortBound(t *testing.T) {
	if testing.Short() {
		t.Skip("short 模式跳过 socket 测试")
	}
	blockers := make([]*net.UDPConn, 0, len(udpForwardVoicePorts))
	ok := true
	for _, p := range udpForwardVoicePorts {
		c, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: p})
		if err != nil {
			ok = false // 这台机器上该端口已被别的程序占着，换一个前提不成立的跑法没意义
			break
		}
		blockers = append(blockers, c)
	}
	if !ok {
		t.Skip("语音端口已被占用，无法构造『全部绑不上』的前提")
	}
	defer func() {
		for _, c := range blockers {
			_ = c.Close()
		}
	}()

	a := loopbackUDP(t, 0)
	b := loopbackUDP(t, 0)
	rc := newRudp(a, b.LocalAddr().(*net.UDPAddr))
	lg := &voiceLog{}
	if br := startVoiceForwardBridge(rc, false, lg.write); br != nil {
		t.Fatal("端口全被占时不该登记桥（CM:15769-15777）")
	}
	if !lg.has("[UdpForward] skip port") {
		t.Fatalf("绑不上端口必须留一行 skip 日志（UFB:47），得到 %v", lg.rows)
	}
	if rc.voiceHandler() != nil {
		t.Fatal("没登记的桥不得把语音回调留在传输上")
	}
}

// 收口：closeAllUdpForwardBridges 清空登记表并摘掉回调（CM:15789-15805）。
func TestCloseAllUdpForwardBridgesClearsHooks(t *testing.T) {
	if testing.Short() {
		t.Skip("short 模式跳过 socket 测试")
	}
	a := loopbackUDP(t, 0)
	b := loopbackUDP(t, 0)
	rc := newRudp(a, b.LocalAddr().(*net.UDPAddr))
	port := freeUDPPort(t)
	br := newUdpForwardBridge(rc, false, []int{port}, (&voiceLog{}).write)
	if !br.isActive() {
		t.Fatal("桥没绑上端口")
	}
	if rc.voiceHandler() == nil {
		t.Fatal("登记后传输必须挂上语音回调")
	}
	closeAllUdpForwardBridges()
	if rc.voiceHandler() != nil {
		t.Fatal("收口后回调必须摘掉")
	}
	udpForwardRegistry.mu.Lock()
	n := len(udpForwardRegistry.all)
	udpForwardRegistry.mu.Unlock()
	if n != 0 {
		t.Fatalf("收口后登记表必须空，实际 %d 条", n)
	}
}
