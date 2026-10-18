package main

// tcp_punch.go — TCP 双向 SimOpen 打洞（对齐模组 TcpHolePuncher.java）：
// 窗口内 bind 同端口 connect 对端（CONNECT_TIMEOUT 3s / 窗口 10s / 间隔 50-250ms 抖动），
// 败转 listen（同端口 accept，远端 IP 校验，10s 窗口）。
// 编排（对齐 ConnectionManager.tryTcpHolePunch/handleTcpPunchInfo/handleTcpPunchGo）：
// guest（本端锥形，cycle≥1）发 tcp_punch_info{tcpPunchIp,tcpPunchPort} → host（锥形）
// 回 tcp_punch_go 并双方同时互连；赢 socket 直接桥进 MC。

import (
	"fmt"
	"math/rand"
	"net"
	"sync"
	"sync/atomic"
	"time"
)

const (
	tcpPunchConnectTimeout = 3 * time.Second
	tcpPunchConnectWindow  = 10 * time.Second
	tcpPunchListenWindow   = 10 * time.Second
	tcpPunchAcceptPoll     = 500 * time.Millisecond
)

// tcpSelectPunchPort 选取空闲端口作为双方约定的 punch 端口。
func tcpSelectPunchPort() int {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return -1
	}
	defer ln.Close()
	return ln.Addr().(*net.TCPAddr).Port
}

type tcpPuncher struct {
	stopped atomic.Bool
}

// stop 让 punch 的两个窗口在下一个轮询点退出（退房时腾出占住的端口）。
func (t *tcpPuncher) stop() { t.stopped.Store(true) }

// punch bind 同端口互连，败转 listen。wonFlag 已置位时提前放弃。
func (t *tcpPuncher) punch(remoteIP string, port, connectAttempts int, won func() bool) net.Conn {
	if remoteIP == "" || port <= 0 || port > 65535 {
		return nil
	}
	if c := t.connectWindow(remoteIP, port, connectAttempts, won); c != nil {
		return c
	}
	if t.stopped.Load() || won() {
		return nil
	}
	return t.listenWindow(port, remoteIP, won)
}

// connectWindow 窗口内 bind 同端口 connect 对端。
func (t *tcpPuncher) connectWindow(remoteIP string, port, attempts int, won func() bool) net.Conn {
	addr, err := net.ResolveTCPAddr("tcp", fmt.Sprintf("%s:%d", remoteIP, port))
	if err != nil {
		return nil
	}
	deadline := time.Now().Add(tcpPunchConnectWindow)
	if attempts <= 0 {
		attempts = 1
	}
	for attempt := 1; attempt <= attempts && !t.stopped.Load() && !won(); attempt++ {
		if time.Now().After(deadline) {
			break
		}
		local := &net.TCPAddr{Port: port}
		d := net.Dialer{Timeout: tcpPunchConnectTimeout, LocalAddr: local}
		conn, err := d.Dial("tcp", addr.String())
		if err == nil {
			return conn
		}
		time.Sleep(time.Duration(50+rand.Intn(200)) * time.Millisecond)
	}
	return nil
}

// listenWindow 同端口 accept，远端 IP 校验。
func (t *tcpPuncher) listenWindow(port int, remoteIP string, won func() bool) net.Conn {
	expectedIP := net.ParseIP(remoteIP)
	ln, err := net.Listen("tcp", fmt.Sprintf(":%d", port))
	if err != nil {
		return nil
	}
	defer ln.Close()
	deadline := time.Now().Add(tcpPunchListenWindow)
	for !t.stopped.Load() && !won() && time.Now().Before(deadline) {
		_ = ln.(*net.TCPListener).SetDeadline(time.Now().Add(tcpPunchAcceptPoll))
		conn, err := ln.Accept()
		if err != nil {
			continue
		}
		ra := conn.RemoteAddr().(*net.TCPAddr)
		if expectedIP != nil && !expectedIP.Equal(ra.IP) {
			_ = conn.Close()
			continue
		}
		if tc, ok := conn.(*net.TCPConn); ok {
			_ = tc.SetNoDelay(true)
		}
		return conn
	}
	return nil
}

// ---- 编排（引擎侧）----

// tcpPunchWatchdogDelay 邀约占位的看门狗时长（测试里可缩短，行为不变）。
var tcpPunchWatchdogDelay = 35 * time.Second

// trackTcpPuncher 登记在跑的 TCP 打洞器，退房时统一 stop（对应模组 activeHolePunchers 那本账）。
func (e *connEngine) trackTcpPuncher(p *tcpPuncher) {
	e.mu.Lock()
	e.tcpPunchers = append(e.tcpPunchers, p)
	e.mu.Unlock()
}

// tryTcpHolePunch guest 侧邀约：本端锥形、cycle≥1 且未连通时发 tcp_punch_info。
// go 丢失看门狗：35s 后释放占位，下轮继续邀约。
func (e *connEngine) tryTcpHolePunch(cycle int, localNat natClass, myMappedIP string) {
	if cycle < 1 || localNat != natCone || myMappedIP == "" {
		return
	}
	e.mu.Lock()
	if e.tcpPunchInFlight || e.gP2PDone {
		e.mu.Unlock()
		return
	}
	e.tcpPunchInFlight = true
	e.tcpPunchToken++
	tok := e.tcpPunchToken
	code, isHost := e.code, e.isHost
	e.mu.Unlock()
	if isHost || code == "" {
		e.mu.Lock()
		e.tcpPunchInFlight = false
		e.mu.Unlock()
		return
	}
	port := tcpSelectPunchPort()
	if port <= 0 {
		e.mu.Lock()
		e.tcpPunchInFlight = false
		e.mu.Unlock()
		return
	}
	delay := tcpPunchWatchdogDelay
	go func() {
		time.Sleep(delay)
		e.mu.Lock()
		// 令牌不符说明这一轮早就结束、新一轮已占位，僵尸看门狗不得清别人的占位
		if e.tcpPunchToken == tok {
			e.tcpPunchInFlight = false
		}
		e.mu.Unlock()
	}()
	_ = e.sendSignal("tcp_punch_info", map[string]any{
		"tcpPunchIp":   myMappedIP,
		"tcpPunchPort": port,
	}, "host")
	e.app.netLog("info", fmt.Sprintf("TCP 打洞邀约已发出（端口 %d）", port))
}

// hostOnTcpPunchInfo host 收邀约：本端锥形才参与；回 tcp_punch_go 并同时互连。
func (e *connEngine) hostOnTcpPunchInfo(from string, data map[string]any) {
	port := sigInt(data, "tcpPunchPort")
	joinerIP := sigStr(data, "tcpPunchIp")
	if port <= 0 || port > 65535 || joinerIP == "" {
		return
	}
	if e.p2pPeerConnected() {
		e.app.netLog("info", "直连已建立，忽略 tcp_punch_info")
		return
	}
	// 本端 NAT 分级为锥形才参与（模组 host 侧同款门控）
	e.mu.Lock()
	nat := e.hostNatClass
	e.mu.Unlock()
	if nat != natCone {
		return
	}
	_ = e.sendSignal("tcp_punch_go", map[string]any{"tcpPunchPort": port}, from)
	go func() {
		p := &tcpPuncher{}
		e.trackTcpPuncher(p)
		conn := p.punch(joinerIP, port, 5, func() bool { return e.p2pPeerConnected() })
		e.onTcpPunchSocket(conn, true, from)
	}()
	e.app.netLog("info", "TCP 打洞：已回 go，开始同时打开")
}

// guestOnTcpPunchGo guest 收 go：connect host 映射地址（1 次尝试）败转 listen。
func (e *connEngine) guestOnTcpPunchGo(data map[string]any) {
	port := sigInt(data, "tcpPunchPort")
	e.mu.Lock()
	inFlight := e.tcpPunchInFlight
	hostMapped := e.gMapped
	done := e.gP2PDone
	e.mu.Unlock()
	if !inFlight || done || port <= 0 || hostMapped == nil {
		return
	}
	targetIP := hostMapped.IP
	go func() {
		p := &tcpPuncher{}
		e.trackTcpPuncher(p)
		conn := p.punch(targetIP, port, 1, func() bool { return e.p2pGuestDone() })
		e.onTcpPunchSocket(conn, false, "")
	}()
	e.app.netLog("info", "TCP 打洞：开始同时打开")
}

// onTcpPunchSocket 赢 socket 桥接：host 直连本地 MC；guest 起本地代理监听。
func (e *connEngine) onTcpPunchSocket(conn net.Conn, isHost bool, peerID string) {
	if conn == nil {
		return
	}
	e.mu.Lock()
	sess := e.sess
	e.mu.Unlock()
	if sess == nil || sess.isDone() {
		_ = conn.Close()
		return
	}
	if isHost {
		e.mu.Lock()
		peer := e.hPeers[peerID]
		mcPort := e.hostPort
		e.mu.Unlock()
		if peer == nil || mcPort <= 0 {
			_ = conn.Close()
			return
		}
		local, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", mcPort), bridgeDialTimeout)
		if err != nil {
			_ = conn.Close()
			e.app.netLog("error", "TCP 打洞成功但本地 MC 端口连接失败")
			return
		}
		if tc, ok := local.(*net.TCPConn); ok {
			_ = tc.SetNoDelay(true)
		}
		e.mu.Lock()
		e.hTcpPairs = append(e.hTcpPairs, conn, local)
		e.mu.Unlock()
		go pumpTcpPair(conn, local)
		e.app.netLog("info", "TCP 打洞成功，host 桥已建立")
		return
	}
	// guest：本地代理监听，MC 连入即双向搬运
	if e.p2pGuestDone() {
		// 已有链路在跑（UDP 先赢或另一条 TCP），这条连接没人接手，必须当场关掉
		_ = conn.Close()
		return
	}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		_ = conn.Close()
		return
	}
	e.mu.Lock()
	if e.gP2PDone || e.sess == nil {
		e.mu.Unlock()
		_ = ln.Close()
		_ = conn.Close()
		return
	}
	e.gP2PDone = true
	e.gTcpLn = ln
	e.gTcpConn = conn
	e.mu.Unlock()
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return // 监听已随桥拆除而关闭
			}
			if tc, ok := c.(*net.TCPConn); ok {
				_ = tc.SetNoDelay(true)
			}
			go e.runTcpBridgePair(ln, conn, c)
		}
	}()
	addr := ln.Addr().String()
	e.app.netLog("info", "TCP 打洞成功，本地桥已建立")
	e.notifyState(phaseP2P, statusSuccess, addr, "tcp")
}

// runTcpBridgePair 搬运一条 MC 接入。conn 是所有接入共享的远端连接，
// 任一对结束（pumpTcpPair 会连 conn 一起关）之后继续 accept 只会把 MC 接到死连接上，
// 所以整条 TCP 桥随之作废并触发 ICE 重启。
func (e *connEngine) runTcpBridgePair(ln net.Listener, conn, mcConn net.Conn) {
	pumpTcpPair(mcConn, conn)
	e.tcpGuestBridgeDown(ln, conn)
}

// tcpGuestBridgeDown 拆除这条 TCP 桥并重新打洞；仅当它仍是引擎登记中的那一条，
// 避免旧桥的收尾把新一轮刚建立的桥一起拆掉。
func (e *connEngine) tcpGuestBridgeDown(ln net.Listener, conn net.Conn) {
	e.mu.Lock()
	current := e.gTcpLn == ln && e.gTcpConn == conn
	e.mu.Unlock()
	if !current {
		return
	}
	e.guestBridgeDown()
}

// pumpTcpPair 双向搬运两条 TCP 连接。
func pumpTcpPair(a, b net.Conn) {
	var once sync.Once
	stop := func() {
		once.Do(func() {
			_ = a.Close()
			_ = b.Close()
		})
	}
	go func() {
		buf := make([]byte, bridgeBufSize)
		for {
			n, err := a.Read(buf)
			if n > 0 {
				if _, werr := b.Write(buf[:n]); werr != nil {
					stop()
					return
				}
			}
			if err != nil {
				stop()
				return
			}
		}
	}()
	buf := make([]byte, bridgeBufSize)
	for {
		n, err := b.Read(buf)
		if n > 0 {
			if _, werr := a.Write(buf[:n]); werr != nil {
				stop()
				return
			}
		}
		if err != nil {
			stop()
			return
		}
	}
}
