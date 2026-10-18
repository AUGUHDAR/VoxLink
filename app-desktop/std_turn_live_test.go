package main

// std_turn_live_test.go — 对**真实 TURN 节点**的标准 RFC 5766 链路实测。
//
// 为什么单独存在：仓库里其它 TURN 用例都是假节点（自建 UDP 应答），能验报文格式，
// 验不了"一个真 TURN 服务器愿不愿意接受这套报文"。本用例用现网同款的 pion v5 节点
// （voxlink-lab 的 turn-node，standardTurn.enabled=true）走完整链路：
// 信令 /relay/stdturn/cred 取凭证 → ALLOCATE（401 探 realm/nonce → 带凭证重试）
// → 拿 RELAY-DATA → CHANNEL BIND → 用通道把字节中继给一个 UDP 回声端 → 收回回声。
//
// 默认跳过：需要外部先把节点、信令服与一个活房间准备好（见台账"标准 TURN 实测"一节）。
// 开启方式（四个环境变量必须都给）：
//
//	VOXLIVE_TURN=1 VOXLIVE_BASE=http://127.0.0.1:1145 \
//	VOXLIVE_ROOM=<6位房号> VOXLIVE_TOKEN=<该房 hostToken 或 clientToken> \
//	VOXLIVE_NODE=<节点 id> go test -run TestStdTurnLiveAgainstRealNode
import (
	"fmt"
	"net"
	"os"
	"strconv"
	"testing"
	"time"
)

func liveEnv(t *testing.T) (base, room, token, node string) {
	t.Helper()
	if os.Getenv("VOXLIVE_TURN") != "1" {
		t.Skip("需要真实 TURN 节点：设 VOXLIVE_TURN=1 并给出 BASE/ROOM/TOKEN/NODE")
	}
	base, room, token, node = os.Getenv("VOXLIVE_BASE"), os.Getenv("VOXLIVE_ROOM"), os.Getenv("VOXLIVE_TOKEN"), os.Getenv("VOXLIVE_NODE")
	if base == "" || room == "" || token == "" || node == "" {
		t.Fatal("VOXLIVE_BASE/ROOM/TOKEN/NODE 缺一不可")
	}
	return
}

func TestStdTurnLiveAgainstRealNode(t *testing.T) {
	base, room, token, node := liveEnv(t)

	a := NewApp()
	a.settingsPath = t.TempDir() + "/settings.json"
	a.mu.Lock()
	a.settings.ServerURL = base
	a.mu.Unlock()

	cred := stdTurnFetchCred(a, room, "live-test-client", token, node)
	if cred == nil {
		t.Fatalf("/relay/stdturn/cred 没拿到凭证（节点 %s 是否有 stdTurnPort？日志见 standard-turn）", node)
	}
	if cred.Port <= 0 || cred.Username == "" || cred.Password == "" {
		t.Fatalf("凭证字段不全: %+v", cred)
	}

	// 回声端：节点会把中继到的字节投到这里，再用同一条 UDP 回一个标记。
	echoAddr := startEchoUDP(t)

	sess := stdTurnAllocate(cred.Host, cred.Port, cred.Username, cred.Password, 6000)
	if sess == nil {
		t.Fatalf("ALLOCATE 失败：真节点拒绝了本端的长项凭证报文（用户名=%s）", cred.Username)
	}
	defer stdTurnClose(sess)

	relay := sess.relayAddr.Load()
	if relay == nil {
		t.Fatal("ALLOCATE 成功但没有 RELAY-DATA 中继地址")
	}
	if relay.IP.String() != cred.Host && !relay.IP.IsLoopback() {
		t.Logf("中继地址 %s（节点宣告 %s）", relay.String(), cred.Host)
	}

	peer := &net.UDPAddr{IP: net.ParseIP(echoAddr.IP.String()), Port: echoAddr.Port}
	if peer.IP.To4() == nil {
		t.Fatalf("回声端地址应为 IPv4: %s", peer)
	}
	if !stdTurnChannelBind(sess, peer, 0x4000, 6000) {
		t.Fatal("CHANNEL BIND 被节点拒绝（权限或属性不合规）")
	}

	// 通道帧：2 字节通道号 + 2 字节长度 + 载荷（RFC 5766 §11）。
	marker := "STDTURN-LIVE-" + strconv.FormatInt(time.Now().Unix(), 10)
	frame := append([]byte{0x40, 0x00, byte(len(marker) >> 8), byte(len(marker))}, []byte(marker)...)
	if _, err := sess.sock.WriteTo(frame, sess.serverAddr); err != nil {
		t.Fatalf("通过通道发送失败: %v", err)
	}

	got := <-readOne(sess.sock, 6*time.Second)
	// 回程可能是通道帧（同通道），也可能是 SEND 格式，这里只要求载荷里含标记。
	if !containsMarker(got, marker) {
		t.Fatalf("回声没穿过中继回来，收到 %d 字节: %q", len(got), string(got[:min(len(got), 60)]))
	}
	fmt.Printf("标准 TURN 真节点链路通过：relay=%s 通道=0x4000 标记=%s\n", relay.String(), marker)
}

func startEchoUDP(t *testing.T) *net.UDPAddr {
	t.Helper()
	pc, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4zero, Port: 0})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = pc.Close() })
	go func() {
		buf := make([]byte, 2048)
		for {
			n, from, err := pc.ReadFromUDP(buf)
			if err != nil {
				return
			}
			_, _ = pc.WriteToUDP(buf[:n], from)
		}
	}()
	return &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: pc.LocalAddr().(*net.UDPAddr).Port}
}

func readOne(sock *net.UDPConn, wait time.Duration) chan []byte {
	out := make(chan []byte, 1)
	go func() {
		_ = sock.SetReadDeadline(time.Now().Add(wait))
		buf := make([]byte, 2048)
		n, _, err := sock.ReadFromUDP(buf)
		if err != nil {
			out <- nil
			return
		}
		out <- buf[:n]
	}()
	return out
}

func containsMarker(b []byte, marker string) bool {
	return len(b) >= len(marker) && indexOf(b, []byte(marker)) >= 0
}

func indexOf(hay, needle []byte) int {
	for i := 0; i+len(needle) <= len(hay); i++ {
		if string(hay[i:i+len(needle)]) == string(needle) {
			return i
		}
	}
	return -1
}
