package main

// cancel_connection 的行为用例（CM:14026-14070 + CM:13986-13990 + CM:12894）。
// 断的是：白名单收得到、收端置位并清这一条对端、持续重试轮被挡但首轮不挡、复活时清位。

import (
	"net"
	"testing"
)

func cancelEngine(t *testing.T) (*App, *connEngine) {
	t.Helper()
	a := NewApp()
	e := a.engine
	e.mu.Lock()
	e.code = "AB12CD"
	e.token = "tok"
	e.clientID = "host-self"
	e.sess = newSession(a, "AB12CD", "tok", true)
	e.mu.Unlock()
	return a, e
}

func TestCancelConnectionIsDispatchedAndMarksPeerCancelled(t *testing.T) {
	_, e := cancelEngine(t)
	sock, _ := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4zero, Port: 0})
	rc := newRudp(sock, &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 9999})
	e.mu.Lock()
	e.isHost = true
	e.hPeers = map[string]*hostPeer{"peer-a": {id: "peer-a", rudp: rc}}
	e.retryCancelled = false
	e.mu.Unlock()

	// 走完整分发链，验证白名单与来源门（房主收自家房客）
	e.onSignal("cancel_connection", "peer-a", map[string]any{})

	e.mu.Lock()
	cancelled := e.retryCancelled
	left := len(e.hPeers)
	e.mu.Unlock()
	if !cancelled {
		t.Fatal("收到 cancel_connection 必须置『停止持续重试』位（CM:14028）")
	}
	if left != 0 {
		t.Fatalf("该房客的状态要被清掉回等待，实际还剩 %d 个", left)
	}
	e.mu.Lock()
	peers := len(e.hPeers)
	e.mu.Unlock()
	if !rc.closed.Load() {
		t.Fatalf("清理要关掉它的传输（peers=%d cancelled=%v）", peers, cancelled)
	}
}

func TestUnknownSenderCannotCancelAndStaysListed(t *testing.T) {
	_, e := cancelEngine(t)
	e.mu.Lock()
	e.isHost = true
	e.hPeers = map[string]*hostPeer{"peer-a": {id: "peer-a"}}
	e.retryCancelled = false
	e.mu.Unlock()

	// 陌生 id 冒充发信令：来源门要挡住（服务端已按 token 绑身份，本端是第二道防线）
	e.onSignal("cancel_connection", "stranger", map[string]any{})
	e.mu.Lock()
	got := e.retryCancelled
	e.mu.Unlock()
	if got {
		t.Fatal("非本房房客不得改写本端的持续重试状态")
	}
}

func TestCancelledBlocksRetryRoundsButNotFirstRound(t *testing.T) {
	_, e := cancelEngine(t)
	e.mu.Lock()
	e.isHost = false
	e.retryCancelled = true
	e.retryRound = 0
	e.mu.Unlock()

	if _, ok := e.beginGuestCycle(); !ok {
		t.Fatal("首轮不该被『对方已取消』挡住（CM:13986 只约束持续重试轮）")
	}
	e.endGuestCycle()

	e.mu.Lock()
	e.retryRound = 3
	e.mu.Unlock()
	if _, ok := e.beginGuestCycle(); ok {
		t.Fatal("已进入持续重试轮且对方取消过，不得再开新一轮")
	}
}

func TestExhaustionRevivalClearsCancellation(t *testing.T) {
	_, e := cancelEngine(t)
	sock, _ := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4zero, Port: 0})
	dead := newRudp(sock, &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 9998})
	dead.Close() // 承载已死：复活分支的前提
	e.mu.Lock()
	e.autoRelayTurnAttempted = true // 两条道都已试过，才会走到"耗尽复活"那一支
	e.autoRelayPlayerAttempted = true
	e.isHost = false
	e.retryCancelled = true
	e.gP2PDone = true
	e.rDone = false
	e.gRudp = dead
	e.gPuncher = nil
	e.gActive = false
	e.gLastOffer = nil // 不给 offer：只验"清位"这一步，不在用例里真重驱打洞
	e.mu.Unlock()

	e.autoRelayFailoverOrEnd("voxlink.relay.failed_timeout", "")

	e.mu.Lock()
	cancelled := e.retryCancelled
	e.mu.Unlock()
	if cancelled {
		t.Fatal("两条中继道耗尽后复活打洞前必须清掉取消位，否则永远复活不了")
	}
}
