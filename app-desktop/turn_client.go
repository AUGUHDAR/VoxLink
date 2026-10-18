package main

// turn_client.go — TURN 中继客户端·自定义协议 v1（对齐模组 TurnRelayClient.java）：
// 拉节点列表 → 应用层 UDP PING 测延迟 → 选最低延迟 → allocate → BIND（3 轮×5 发 +
// TCP 兜底）→ 会话保活 15s；rudp 帧经 TurnPathCodec 包进 TURN DATA。

import (
	"encoding/binary"
	"encoding/hex"
	"fmt"
	"net"
	"sync"
	"time"
)

const (
	turnProtocolVersion byte = 1

	turnTypePing         byte = 1
	turnTypePong         byte = 2
	turnTypeBind         byte = 3
	turnTypeBindResult   byte = 4
	turnTypeData         byte = 5
	turnTypeKeepalive    byte = 6
	turnTypeKeepaliveAck byte = 7
	turnTypeUnbind       byte = 8

	turnRoleHost  byte = 1
	turnRoleGuest byte = 2

	turnBindOK            = 0
	turnBindBadTicket     = 1
	turnBindTicketExpired = 2
	turnBindSessionFull   = 3
	turnBindRoleConflict  = 4
	turnBindServerBusy    = 5

	turnPingProbeCount    = 6
	turnPingTimeout       = 800 * time.Millisecond
	turnProbeTotalBudget  = 4 * time.Second
	turnBindTimeout       = 4600 * time.Millisecond
	turnKeepaliveInterval = 15 * time.Second
	turnFetchMaxAttempts  = 4
	turnFetchRetryDelay   = 800 * time.Millisecond
	turnNodeCacheTTL      = 60 * time.Second
)

// ---- 节点列表（fetchNodeList + 60s 缓存 + 4 次重试）----

type turnNodeInfo struct {
	ID          string
	Name        string
	Host        string
	Port        int
	StdTurnPort int
}

type turnProbeResult struct {
	node  turnNodeInfo
	rttMs int
}

var (
	turnNodeCacheMu   sync.Mutex
	turnNodeCache     []turnNodeInfo
	turnNodeCacheBase string
	turnNodeCacheAt   time.Time
)

func turnFetchNodeList(a *App) ([]turnNodeInfo, error) {
	base := a.baseURL()
	turnNodeCacheMu.Lock()
	if base == turnNodeCacheBase && time.Since(turnNodeCacheAt) < turnNodeCacheTTL && len(turnNodeCache) > 0 {
		hit := append([]turnNodeInfo(nil), turnNodeCache...)
		turnNodeCacheMu.Unlock()
		return hit, nil
	}
	turnNodeCacheMu.Unlock()

	var lastErr error
	for attempt := 1; attempt <= turnFetchMaxAttempts; attempt++ {
		var data struct {
			Nodes []struct {
				ID          string `json:"id"`
				Name        string `json:"name"`
				Host        string `json:"host"`
				Port        int    `json:"port"`
				StdTurnPort int    `json:"stdTurnPort"`
			} `json:"nodes"`
		}
		if err := a.api.get(base, "/relay/list", nil, &data); err != nil {
			lastErr = err
		} else {
			nodes := make([]turnNodeInfo, 0, len(data.Nodes))
			for _, n := range data.Nodes {
				if n.ID != "" && n.Host != "" && n.Port > 0 {
					name := n.Name
					if name == "" {
						name = n.ID
					}
					nodes = append(nodes, turnNodeInfo{ID: n.ID, Name: name, Host: n.Host, Port: n.Port, StdTurnPort: n.StdTurnPort})
				}
			}
			if len(nodes) > 0 {
				turnNodeCacheMu.Lock()
				turnNodeCache = nodes
				turnNodeCacheBase = base
				turnNodeCacheAt = time.Now()
				turnNodeCacheMu.Unlock()
				return nodes, nil
			}
			lastErr = fmt.Errorf("relay_list 为空")
		}
		if attempt < turnFetchMaxAttempts {
			time.Sleep(turnFetchRetryDelay)
		}
	}
	// 全败回退旧缓存
	turnNodeCacheMu.Lock()
	staleOK := base == turnNodeCacheBase && len(turnNodeCache) > 0
	stale := append([]turnNodeInfo(nil), turnNodeCache...)
	turnNodeCacheMu.Unlock()
	if staleOK {
		return stale, nil
	}
	if lastErr == nil {
		lastErr = fmt.Errorf("无可用 TURN 节点")
	}
	return nil, lastErr
}

// turnProbeNodes 并行探测全部节点：每节点 6 次 PING 取最小 RTT；不通 rtt=-1，按 RTT 升序。
func turnProbeNodes(nodes []turnNodeInfo) []turnProbeResult {
	results := make([]turnProbeResult, len(nodes))
	var wg sync.WaitGroup
	for i := range nodes {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			results[i] = turnProbeOne(nodes[i])
		}(i)
	}
	wg.Wait()
	// 稳定排序：rtt 升序，不可达排最后
	for i := 1; i < len(results); i++ {
		for j := i; j > 0; j-- {
			ra, rb := results[j-1].rttMs, results[j].rttMs
			if ra < 0 {
				ra = 1 << 30
			}
			if rb < 0 {
				rb = 1 << 30
			}
			if ra <= rb {
				break
			}
			results[j-1], results[j] = results[j], results[j-1]
		}
	}
	return results
}

// turnProbeOne 单节点 6 次探测取最小；每次独立 socket 免粘连，超时/异常算不通。
func turnProbeOne(node turnNodeInfo) turnProbeResult {
	best := -1
	deadline := time.Now().Add(turnProbeTotalBudget)
	for i := 0; i < turnPingProbeCount && time.Now().Before(deadline); i++ {
		s, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4zero, Port: 0})
		if err != nil {
			continue
		}
		func() {
			defer s.Close()
			raddr, err := net.ResolveUDPAddr("udp", fmt.Sprintf("%s:%d", node.Host, node.Port))
			if err != nil {
				return
			}
			var nonceB [4]byte
			_, _ = cryptRandRead(nonceB[:])
			nonce := binary.BigEndian.Uint32(nonceB[:])
			ts := time.Now().UnixMilli()
			p := make([]byte, 20)
			turnPackHeader(p, turnTypePing)
			binary.BigEndian.PutUint32(p[4:8], nonce)
			binary.BigEndian.PutUint64(p[8:16], uint64(ts))
			t0 := time.Now()
			if _, err := s.WriteToUDP(p, raddr); err != nil {
				return
			}
			buf := make([]byte, 64)
			for time.Since(t0) < turnPingTimeout {
				_ = s.SetReadDeadline(time.Now().Add(turnPingTimeout - time.Since(t0)))
				n, _, err := s.ReadFromUDP(buf)
				if err != nil {
					return
				}
				if n >= 24 && buf[0] == 0x56 && buf[1] == 0x4C && buf[3] == turnTypePong && binary.BigEndian.Uint32(buf[4:8]) == nonce {
					rtt := int(time.Since(t0).Milliseconds())
					if best < 0 || rtt < best {
						best = rtt
					}
					return
				}
			}
		}()
	}
	return turnProbeResult{node: node, rttMs: best}
}

// ---- allocate / release（HTTP）----

type turnAllocation struct {
	SessionIDHex string
	Host         string
	Port         int
	HostTicket   string
	GuestTicket  string
	ExpireSec    int64
}

func turnAllocate(a *App, roomCode, clientID, token, nodeID string) (*turnAllocation, error) {
	var data struct {
		SessionIDHex string `json:"sessionId"`
		Host         string `json:"host"`
		Port         int    `json:"port"`
		HostTicket   string `json:"hostTicket"`
		GuestTicket  string `json:"guestTicket"`
		Expire       int64  `json:"expire"`
	}
	body := map[string]any{"roomCode": roomCode, "clientId": clientID, "token": token, "nodeId": nodeID}
	if err := a.api.post(a.baseURL(), "/relay/allocate", body, &data); err != nil {
		return nil, err
	}
	if len(data.SessionIDHex) != 32 || data.Host == "" || data.Port <= 0 || data.GuestTicket == "" || data.HostTicket == "" {
		return nil, fmt.Errorf("allocate 响应不完整")
	}
	return &turnAllocation{
		SessionIDHex: data.SessionIDHex, Host: data.Host, Port: data.Port,
		HostTicket: data.HostTicket, GuestTicket: data.GuestTicket, ExpireSec: data.Expire,
	}, nil
}

func turnRelease(a *App, roomCode, clientID, token, sessionIDHex string) {
	body := map[string]any{"roomCode": roomCode, "clientId": clientID, "token": token, "sessionId": sessionIDHex}
	_ = a.api.post(a.baseURL(), "/relay/release", body, nil)
}

// ---- 会话与 BIND ----

type turnSession struct {
	sessionIDHex string
	sessionID    [16]byte
	role         byte
	ticket       string
	expireSec    int64

	mu    sync.Mutex
	sock  *net.UDPConn
	host  string
	port  int
	tcpCh *turnTcpChannel
	bound bool
}

func newTurnSession(alloc *turnAllocation, role byte, ticket string) *turnSession {
	sid, _ := hex.DecodeString(alloc.SessionIDHex)
	var sid16 [16]byte
	copy(sid16[:], sid)
	s := &turnSession{
		sessionIDHex: alloc.SessionIDHex,
		sessionID:    sid16,
		role:         role,
		ticket:       ticket,
		expireSec:    alloc.ExpireSec,
		host:         alloc.Host,
		port:         alloc.Port,
	}
	if uc, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4zero, Port: 0}); err == nil {
		s.sock = uc
	}
	return s
}

// endpoint 当前 TURN 节点端点（TCP 兜底后为 shim 回环口）。
func (s *turnSession) endpoint() *net.UDPAddr {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.endpointLocked()
}

// endpointLocked 取端点；调用方须已持有 s.mu（sync.Mutex 不可重入）。
func (s *turnSession) endpointLocked() *net.UDPAddr {
	return &net.UDPAddr{IP: net.ParseIP(s.host), Port: s.port}
}

// socket 当前数据面 socket（TCP 兜底激活后为 shim 回环口）。
func (s *turnSession) socket() *net.UDPConn {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.sock
}

func (s *turnSession) isBound() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.bound
}

// sendKeepalive 会话保活（节点 90s 无包踢角色）。
func (s *turnSession) sendKeepalive() {
	s.mu.Lock()
	sock := s.sock
	bound := s.bound
	ep := s.endpointLocked()
	s.mu.Unlock()
	if sock == nil || !bound {
		return
	}
	p := make([]byte, 21)
	turnPackHeader(p, turnTypeKeepalive)
	copy(p[4:20], s.sessionID[:])
	p[20] = s.role
	udpSendTo(sock, p, ep)
}

// unbind 发 UNBIND 并关 socket（切换成功释放 TURN / 断线清理共用）。
func (s *turnSession) unbind() {
	s.mu.Lock()
	sock := s.sock
	bound := s.bound
	ep := s.endpointLocked()
	tcpCh := s.tcpCh
	s.bound = false
	s.mu.Unlock()
	if sock != nil && bound {
		p := make([]byte, 21)
		turnPackHeader(p, turnTypeUnbind)
		copy(p[4:20], s.sessionID[:])
		p[20] = s.role
		udpSendTo(sock, p, ep)
	}
	if tcpCh != nil {
		tcpCh.close()
	}
	if sock != nil {
		_ = sock.Close()
	}
}

// turnBindWithRetry 带外层重试的 BIND（1.1.5）：单轮 5 发共 4.6s 全丢在弱网很常见；
// 整轮失败隔 1s 再来一轮，rounds 轮内任一成功即返回；非超时类失败码原样返回。
// UDP 轮次耗尽仍无响应（code=5 UDP 黑洞实证）→ TCP 兜底。
func turnBindWithRetry(s *turnSession, rounds int, logf func(level, msg string)) int {
	if rounds < 1 {
		rounds = 1
	}
	s.mu.Lock()
	sock := s.sock
	s.mu.Unlock()
	if sock == nil {
		return turnBindServerBusy
	}
	code := turnBindServerBusy
	for attempt := 1; attempt <= rounds; attempt++ {
		code = turnBindOnce(s)
		if code != turnBindServerBusy {
			return code
		}
		if attempt < rounds {
			if logf != nil {
				logf("warn", fmt.Sprintf("TURN bind 整轮丢包（%d/%d），1s 后重试", attempt, rounds))
			}
			time.Sleep(time.Second)
		}
	}
	return turnEngageTcpFallback(s, code, logf)
}

// turnEngageTcpFallback TCP 兜底：UDP 全丢时经本地 shim 转走 TCP 长连接（同端口）。
// 只改 session 的 sock/host/port，bind/keepalive/rudp 零感知。
func turnEngageTcpFallback(s *turnSession, udpCode int, logf func(level, msg string)) int {
	s.mu.Lock()
	tcpCh := s.tcpCh
	preferPort := 0
	if s.sock != nil {
		if a, ok := s.sock.LocalAddr().(*net.UDPAddr); ok {
			preferPort = a.Port
		}
	}
	host, port := s.host, s.port
	s.mu.Unlock()
	if tcpCh != nil {
		return udpCode
	}
	if logf != nil {
		logf("warn", "TURN UDP bind 无响应，启用 TCP 兜底承载")
	}
	if s.sock != nil {
		_ = s.sock.Close()
	}
	ch, err := turnTcpOpen(host, port, preferPort)
	if err != nil {
		if logf != nil {
			logf("warn", "TURN TCP 兜底连接失败: "+err.Error())
		}
		return udpCode
	}
	s.mu.Lock()
	s.tcpCh = ch
	s.sock = ch.clientSocket()
	s.host = "127.0.0.1"
	s.port = ch.shimPort()
	s.mu.Unlock()
	code := turnBindServerBusy
	for attempt := 1; attempt <= 2; attempt++ {
		code = turnBindOnce(s)
		if code != turnBindServerBusy {
			break
		}
		if attempt < 2 {
			time.Sleep(time.Second)
		}
	}
	if logf != nil {
		if code == turnBindOK {
			logf("warn", "TURN TCP 兜底 bind 成功（UDP 通路已死）")
		} else {
			logf("warn", fmt.Sprintf("TURN TCP 兜底 bind 失败 code=%d", code))
		}
	}
	return code
}

// turnBindOnce 单轮 BIND：0/900/1800/2700/3600ms 五次重发，总窗口 4.6s。
func turnBindOnce(s *turnSession) int {
	s.mu.Lock()
	sock := s.sock
	ep := s.endpointLocked()
	s.mu.Unlock()
	if sock == nil {
		return turnBindServerBusy
	}
	ticket := []byte(s.ticket)
	p := make([]byte, 4+16+1+2+len(ticket))
	turnPackHeader(p, turnTypeBind)
	copy(p[4:20], s.sessionID[:])
	p[20] = s.role
	binary.BigEndian.PutUint16(p[21:23], uint16(len(ticket)))
	copy(p[23:], ticket)

	deadline := time.Now().Add(turnBindTimeout)
	buf := make([]byte, 64)
	sent := 0
	nextSendAt := time.Time{}
	for time.Now().Before(deadline) {
		now := time.Now()
		if sent < 5 && now.After(nextSendAt) {
			udpSendTo(sock, p, ep)
			sent++
			nextSendAt = now.Add(900 * time.Millisecond)
		}
		_ = sock.SetReadDeadline(time.Now().Add(100 * time.Millisecond))
		n, _, err := sock.ReadFromUDP(buf)
		if err != nil {
			continue
		}
		if n >= 22 && buf[0] == 0x56 && buf[1] == 0x4C && buf[3] == turnTypeBindResult {
			var sid [16]byte
			copy(sid[:], buf[4:20])
			if sid == s.sessionID && buf[20] == s.role {
				code := int(buf[21])
				s.mu.Lock()
				s.bound = code == turnBindOK
				s.mu.Unlock()
				return code
			}
		}
	}
	return turnBindServerBusy
}

// ---- UdpPath 编解码：rudp 帧 ↔ TURN DATA ----

type turnPathCodec struct {
	sessionID [16]byte
	fromRole  byte
	toRole    byte
}

func (c turnPathCodec) encode(frame []byte) []byte {
	out := make([]byte, 4+16+1+1+2+len(frame))
	turnPackHeader(out, turnTypeData)
	copy(out[4:20], c.sessionID[:])
	out[20] = c.fromRole
	out[21] = c.toRole
	binary.BigEndian.PutUint16(out[22:24], uint16(len(frame)))
	copy(out[24:], frame)
	return out
}

func (c turnPathCodec) decode(packet []byte) []byte {
	if len(packet) < 24 || packet[0] != 0x56 || packet[1] != 0x4C || packet[3] != turnTypeData {
		return nil
	}
	for i := 0; i < 16; i++ {
		if packet[4+i] != c.sessionID[i] {
			return nil
		}
	}
	payloadLen := int(binary.BigEndian.Uint16(packet[22:24]))
	if 24+payloadLen > len(packet) {
		return nil
	}
	return append([]byte(nil), packet[24:24+payloadLen]...)
}

func turnPackHeader(buf []byte, typ byte) {
	buf[0] = 0x56
	buf[1] = 0x4C
	buf[2] = turnProtocolVersion
	buf[3] = typ
}
