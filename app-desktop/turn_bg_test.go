package main

// turn_bg.go 的行为用例（2026-10-12）：TURN 建立后的后台升直连 + 玩家中继热备。
// 每条都要能区分新旧行为：把守卫摘掉（比如房主侧不判 armed、或拆 TURN 时不转正热备）必须变红。

import (
	"net"
	"sync/atomic"
	"testing"
	"time"
)

func bgEngine(t *testing.T) (*App, *connEngine) {
	t.Helper()
	a := NewApp()
	a.settingsPath = "/tmp/none.json"
	e := newConnEngine(a)
	e.code = "AB12CD"
	e.token = "tok"
	e.clientID = "cid-1"
	return a, e
}

// livePair 造一条"连着"的 rudpConn（IsConnected 只判 closed），并返回对端 socket 便于打数据。
func livePair(t *testing.T) (*rudpConn, *net.UDPConn, *net.UDPAddr) {
	t.Helper()
	sa, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4zero, Port: 0})
	if err != nil {
		t.Fatal(err)
	}
	sb, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4zero, Port: 0})
	if err != nil {
		t.Fatal(err)
	}
	sba := sb.LocalAddr().(*net.UDPAddr)
	tr := newRudp(sa, &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1).To4(), Port: sba.Port})
	t.Cleanup(func() {
		tr.Close()
		_ = sb.Close()
	})
	return tr, sb, &net.UDPAddr{IP: sa.LocalAddr().(*net.UDPAddr).IP, Port: sa.LocalAddr().(*net.UDPAddr).Port}
}

func TestTurnBgMonitorArmsOnceAndGivesUpAtDeadline(t *testing.T) {
	oldNow := turnBgNowMs
	oldWin := turnBgWindow
	defer func() { turnBgNowMs, turnBgWindow = oldNow, oldWin }()

	base := int64(1_000_000)
	var clock atomic.Int64
	clock.Store(base)
	turnBgNowMs = func() int64 { return clock.Load() }
	turnBgWindow = 1000 * time.Second

	a, e := bgEngine(t)
	tr, _, _ := livePair(t)
	e.mu.Lock()
	e.turnTransport = tr
	e.mu.Unlock()

	e.startTurnBgMonitor()
	first := e.turnBgDeadlineMs
	if first != base+1000*1000 {
		t.Fatalf("窗口应按 CM:12258 = now+600_000（此处 dial 成 1000s）建立，得到 %d", first)
	}
	// 二次调用必须被 CM:12254 的守卫挡掉（不重置窗口、不装第二个定时器）
	e.startTurnBgMonitor()
	if e.turnBgDeadlineMs != first {
		t.Fatalf("监视器被重复装配：deadline 从 %d 变成 %d", first, e.turnBgDeadlineMs)
	}

	e.turnBgTick()
	if e.turnP2pGivenUp {
		t.Fatal("未到窗口不得放弃后台升直连（CM:12266）")
	}
	clock.Store(first + 1)
	e.turnBgTick()
	if !e.turnP2pGivenUp {
		t.Fatal("超过 10 分钟窗口必须置 turnP2pGivenUp（CM:12266-12271）")
	}

	// TURN 塌了：撤监视器并拆 TURN（CM:12273-12279）
	clock.Store(base)
	e.mu.Lock()
	e.turnP2pGivenUp = false
	e.turnTransport = nil
	e.mu.Unlock()
	e.turnBgTick()
	if e.turnBgMonitorStop != nil {
		t.Fatal("TURN 不在时监视器必须自撤")
	}
	_ = a
}

func TestAttemptBgDirectPunchRoleSplit(t *testing.T) {
	_, e := bgEngine(t)

	// 房主：纯信号驱动，本函数一律不动手（CM:12334-12338）
	e.mu.Lock()
	e.isHost = true
	e.turnBgAttempts = 0
	e.mu.Unlock()
	e.attemptBgDirectPunch()
	if e.turnBgAttempts != 0 {
		t.Fatalf("房主不应主动起后台 puncher，实际起了 %d 次", e.turnBgAttempts)
	}

	// 房客：对端地址未知就跳过本拍（CM:12321）
	e.mu.Lock()
	e.isHost = false
	e.gMapped = nil
	e.turnBgAttempts = 0
	e.mu.Unlock()
	e.attemptBgDirectPunch()
	if e.turnBgAttempts != 0 {
		t.Fatal("对端映射未知时不得起 puncher")
	}

	// 房客：有映射就起（并给房主发 turn_bg_punch）
	e.mu.Lock()
	e.gMapped = &stunMappedAddr{IP: "192.0.2.7", Port: 41000}
	e.gMyMappedIP = "192.0.2.9"
	e.gMyMappedPort = 41001
	e.turnBgAttempts = 0
	e.mu.Unlock()
	e.attemptBgDirectPunch()
	if e.turnBgAttempts != 1 {
		t.Fatalf("房客应每拍起一次后台 puncher，实际 %d 次", e.turnBgAttempts)
	}
}

func TestHandleTurnBgPunchGuards(t *testing.T) {
	payload := map[string]any{"ip": "192.0.2.7", "port": float64(41000)}
	cases := []struct {
		name  string
		armed bool
		given bool
		swit  bool
		host  bool
		bad   bool
		want  int
	}{
		{"未装监视器", false, false, false, true, false, 0},
		{"已放弃", true, true, false, true, false, 0},
		{"已切过直连", true, false, true, true, false, 0},
		{"载荷缺地址", true, false, false, true, true, 0},
		{"非房主", true, false, false, false, false, 0},
		{"正常派发", true, false, false, true, false, 1},
	}
	for _, c := range cases {
		_, e := bgEngine(t)
		e.mu.Lock()
		e.isHost = c.host
		e.sess = newSession(e.app, "AB12CD", "tok", c.host)
		if c.armed {
			e.turnBgMonitorStop = func() {}
		}
		e.turnP2pGivenUp = c.given
		e.turnSwitchedToP2p = c.swit
		e.turnBgAttempts = 0
		e.mu.Unlock()
		if c.bad {
			e.handleTurnBgPunch(map[string]any{})
		} else {
			e.handleTurnBgPunch(payload)
		}
		if e.turnBgAttempts != c.want {
			t.Fatalf("%s：期望起 puncher %d 次，实际 %d", c.name, c.want, e.turnBgAttempts)
		}
	}
}

func TestRelayNotifyDuringTurnBuildsStandbyNotSecondBridge(t *testing.T) {
	_, e := bgEngine(t)
	tr, _, _ := livePair(t)
	e.mu.Lock()
	e.isHost = false
	e.turnTransport = tr
	e.sess = newSession(e.app, "AB12CD", "tok", false)
	e.mu.Unlock()

	e.guestOnRelayNotify("host", map[string]any{"relayIp": "192.0.2.30", "relayPort": float64(45000)})

	if e.turnStandbyAttempts != 1 {
		t.Fatalf("TURN 承载中的 relay_notify 必须走热备建立（CM:11150-11161），实际驱动 %d 次", e.turnStandbyAttempts)
	}
	e.mu.Lock()
	sock := e.rSock
	puncher := e.rPuncher
	e.mu.Unlock()
	if sock != nil || puncher != nil {
		t.Fatal("热备路径不得建第二条中继桥/中继打洞")
	}
}

func TestTeardownTurnPromotesLiveHotstandby(t *testing.T) {
	_, e := bgEngine(t)
	turnTr, _, _ := livePair(t)
	standby, _, _ := livePair(t)

	// 桥要有本地 MC 可拨（应用端 MC 在进程外，等价替换点）
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	mcPort := ln.Addr().(*net.TCPAddr).Port
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			_ = c.Close()
		}
	}()

	failSeen := ""
	e.relayFailForTest = func(reason string) { failSeen = reason }

	e.mu.Lock()
	e.isHost = false
	e.sess = newSession(e.app, "AB12CD", "tok", false)
	e.hostIp = "127.0.0.1"
	e.hostPort = mcPort
	e.turnTransport = turnTr
	e.turnHotstandby = standby
	e.turnBgPunchWon = true
	e.turnSwitchedToP2p = true
	e.rDone = true
	e.mu.Unlock()

	e.teardownTurn(true)

	e.mu.Lock()
	gotRudp := e.rRudp
	gotBridge := e.rBridge
	sb := e.turnHotstandby
	won := e.turnBgPunchWon
	switched := e.turnSwitchedToP2p
	e.mu.Unlock()

	if gotRudp != standby {
		t.Fatalf("TURN 塌掉且热备活着时必须转正（CM:12592-12633），rRudp=%v", gotRudp != nil)
	}
	if gotBridge == nil {
		t.Fatal("转正后必须起本地桥（模组 startUdpPunchBridge 的等价收尾）")
	}
	if sb != nil {
		t.Fatal("热备已交接，字段必须清空")
	}
	if won || switched {
		t.Fatal("转正后后台升级位与已切换位要复位，否则下一轮 CAS 恒失败（模组 P1-4）")
	}
	if failSeen != "" {
		t.Fatalf("转正这一轮不得报失败，实际报了 %q", failSeen)
	}
}

func TestTeardownTurnWithoutStandbyStillReportsFailure(t *testing.T) {
	_, e := bgEngine(t)
	turnTr, _, _ := livePair(t)
	failSeen := ""
	e.relayFailForTest = func(reason string) { failSeen = reason }

	e.mu.Lock()
	e.isHost = false
	e.sess = newSession(e.app, "AB12CD", "tok", false)
	e.turnTransport = turnTr
	e.mu.Unlock()

	e.teardownTurn(true)
	if failSeen == "" {
		t.Fatal("没有热备可转正时，仍须按原路报失败（不能把失败吞掉）")
	}
	e.mu.Lock()
	tr := e.turnTransport
	e.mu.Unlock()
	if tr != nil {
		t.Fatal("TURN 传输必须被拆掉")
	}
}
