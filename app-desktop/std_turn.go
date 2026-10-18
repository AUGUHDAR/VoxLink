package main

// std_turn.go — 标准 TURN 客户端（RFC 5766/8656 + RFC 5389 STUN，逐段对齐模组
// StdTurnClient.java，对接 pion/turn v5 节点 :3478）：
//   - 无节点侧配对：两端各自 Allocate 得 relay 地址，经信令交换后互建
//     CreatePermission + ChannelBind，数据面走 ChannelData（4 字节头）
//   - 凭证：TURN REST 时间窗（信令 /relay/stdturn/cred 签发），长项凭证
//     key = MD5(username:realm:password)，MESSAGE-INTEGRITY = HMAC-SHA1
//   - 保活：Refresh(lifetime=600) 240s 周期重发；ChannelBind 10min 幂等刷新

import (
	"crypto/hmac"
	"crypto/md5"
	"crypto/sha1"
	"encoding/binary"
	"errors"
	"fmt"
	"hash/crc32"
	"net"
	"sync/atomic"
	"time"
)

const (
	stdTurnMtBindReq        uint16 = 0x0001
	stdTurnMtAllocateReq    uint16 = 0x0003
	stdTurnMtAllocateOK     uint16 = 0x0103
	stdTurnMtAllocateErr    uint16 = 0x0113
	stdTurnMtRefreshReq     uint16 = 0x0004
	stdTurnMtRefreshOK      uint16 = 0x0104
	stdTurnMtRefreshErr     uint16 = 0x0114
	stdTurnMtCreatePermReq  uint16 = 0x0008
	stdTurnMtCreatePermOK   uint16 = 0x0108
	stdTurnMtCreatePermErr  uint16 = 0x0118
	stdTurnMtChannelBindReq uint16 = 0x0009
	stdTurnMtChannelBindOK  uint16 = 0x0109
	stdTurnMtChannelBindErr uint16 = 0x0119

	stdTurnAtMappedAddress    uint16 = 0x0001
	stdTurnAtUsername         uint16 = 0x0006
	stdTurnAtMessageIntegrity uint16 = 0x0008
	stdTurnAtErrorCode        uint16 = 0x0009
	stdTurnAtChannelNumber    uint16 = 0x000C
	stdTurnAtLifetime         uint16 = 0x000D
	stdTurnAtXorPeerAddress   uint16 = 0x0012
	stdTurnAtRealm            uint16 = 0x0014
	stdTurnAtNonce            uint16 = 0x0015
	stdTurnAtXorRelayedAddr   uint16 = 0x0016
	stdTurnAtRequestedTrans   uint16 = 0x0019
	stdTurnAtXorMappedAddress uint16 = 0x0020
	stdTurnAtSoftware         uint16 = 0x8022
	stdTurnAtFingerprint      uint16 = 0x8028

	stdTurnMagicCookie     uint32 = 0x2112A442
	stdTurnFpXor           uint32 = 0x5354554E
	stdTurnTransportUDP    uint32 = 17
	stdTurnChannelBase     uint16 = 0x4000
	stdTurnDefaultLifetime uint32 = 600

	stdTurnHeader      = 20
	stdTurnTxMaxRounds = 4
	stdTurnTxRto       = 500 * time.Millisecond

	stdTurnProbeTries       = 3
	stdTurnProbeRoundBudget = 3 * time.Second
)

// stdTurnSession 标准 TURN 会话：Allocate 成功即持有 relay 地址；ChannelBind 后进入数据面。
type stdTurnSession struct {
	sock       *net.UDPConn
	serverAddr *net.UDPAddr
	username   string
	authKey    []byte
	realm      string
	nonce      string

	relayAddr   atomic.Pointer[net.UDPAddr]
	peerAddr    atomic.Pointer[net.UDPAddr]
	channel     atomic.Int32
	lifetimeSec atomic.Int64
}

func (s *stdTurnSession) endpoint() *net.UDPAddr { return s.serverAddr }

// ---- 消息构建 ----

type stdTurnMsgBuilder struct {
	typ   uint16
	txID  [12]byte
	attrs []byte
}

func newStdTurnMsg(typ uint16) *stdTurnMsgBuilder {
	var txID [12]byte
	_, _ = cryptRandRead(txID[:])
	return &stdTurnMsgBuilder{typ: typ, txID: txID}
}

func (b *stdTurnMsgBuilder) put(at uint16, v []byte) {
	head := make([]byte, 4)
	binary.BigEndian.PutUint16(head[0:2], at)
	binary.BigEndian.PutUint16(head[2:4], uint16(len(v)))
	b.attrs = append(b.attrs, head...)
	b.attrs = append(b.attrs, v...)
	if pad := (4 - len(v)%4) % 4; pad > 0 {
		b.attrs = append(b.attrs, make([]byte, pad)...)
	}
}

func (b *stdTurnMsgBuilder) putStr(at uint16, v string) { b.put(at, []byte(v)) }

func (b *stdTurnMsgBuilder) putUint32(at uint16, v uint32) {
	var buf [4]byte
	binary.BigEndian.PutUint32(buf[:], v)
	b.put(at, buf[:])
}

func (b *stdTurnMsgBuilder) putXorAddress(at uint16, addr *net.UDPAddr) {
	ip := addr.IP.To4()
	if ip == nil {
		ip = addr.IP.To16()
	}
	family := byte(0x02)
	if len(ip) == 4 {
		family = 0x01
	}
	v := make([]byte, 0, 20)
	v = append(v, 0, family)
	port := uint16(addr.Port) ^ uint16(stdTurnMagicCookie>>16)
	v = append(v, byte(port>>8), byte(port))
	if len(ip) == 4 {
		x := binary.BigEndian.Uint32(ip) ^ stdTurnMagicCookie
		var xb [4]byte
		binary.BigEndian.PutUint32(xb[:], x)
		v = append(v, xb[:]...)
	} else {
		for i := 0; i < 16; i++ {
			var x byte
			if i < 4 {
				x = byte(stdTurnMagicCookie >> (24 - i*8))
			} else {
				x = b.txID[i-4]
			}
			v = append(v, ip[i]^x)
		}
	}
	b.put(at, v)
}

// build 序列化：withIntegrity 时先算 MESSAGE-INTEGRITY 再算 FINGERPRINT（RFC 5389 §15.4/§15.5）。
func (b *stdTurnMsgBuilder) build(integrityKey []byte) []byte {
	if integrityKey == nil {
		return b.finish(b.attrs, len(b.attrs), false)
	}
	lenWithMi := len(b.attrs) + 24 // MI = 4 字节属性头 + 20 字节摘要
	head := b.header(lenWithMi)
	miInput := append(append([]byte(nil), head...), b.attrs...)
	mi := stdTurnHmacSha1(integrityKey, miInput)
	withMi := make([]byte, len(b.attrs)+24)
	copy(withMi, b.attrs)
	off := len(b.attrs)
	binary.BigEndian.PutUint16(withMi[off:], stdTurnAtMessageIntegrity)
	binary.BigEndian.PutUint16(withMi[off+2:], 20)
	copy(withMi[off+4:], mi)
	return b.finish(withMi, len(withMi)+8, true)
}

func (b *stdTurnMsgBuilder) finish(attrBytes []byte, totalLen int, withFp bool) []byte {
	head := b.header(totalLen)
	out := append(append([]byte(nil), head...), attrBytes...)
	if !withFp {
		return out
	}
	fp := crc32.ChecksumIEEE(out) ^ stdTurnFpXor
	tail := make([]byte, 8)
	binary.BigEndian.PutUint16(tail[0:2], stdTurnAtFingerprint)
	binary.BigEndian.PutUint16(tail[2:4], 4)
	binary.BigEndian.PutUint32(tail[4:8], fp)
	return append(out, tail...)
}

func (b *stdTurnMsgBuilder) header(length int) []byte {
	h := make([]byte, stdTurnHeader)
	binary.BigEndian.PutUint16(h[0:2], b.typ)
	binary.BigEndian.PutUint16(h[2:4], uint16(length))
	binary.BigEndian.PutUint32(h[4:8], stdTurnMagicCookie)
	copy(h[8:20], b.txID[:])
	return h
}

// ---- 消息解析 ----

type stdTurnMessage struct {
	typ         uint16
	txID        [12]byte
	attrs       map[uint16][]byte
	errorCode   int
	errorReason string
}

func (m *stdTurnMessage) attr(at uint16) []byte { return m.attrs[at] }

func stdTurnParse(buf []byte) *stdTurnMessage {
	if len(buf) < stdTurnHeader || buf[0]&0xC0 != 0 {
		return nil
	}
	if binary.BigEndian.Uint32(buf[4:8]) != stdTurnMagicCookie {
		return nil
	}
	m := &stdTurnMessage{
		typ:   binary.BigEndian.Uint16(buf[0:2]),
		attrs: map[uint16][]byte{},
	}
	copy(m.txID[:], buf[8:20])
	off := stdTurnHeader
	declared := int(binary.BigEndian.Uint16(buf[2:4]))
	end := len(buf)
	if stdTurnHeader+declared < end {
		end = stdTurnHeader + declared
	}
	for off+4 <= end {
		at := binary.BigEndian.Uint16(buf[off : off+2])
		alen := int(binary.BigEndian.Uint16(buf[off+2 : off+4]))
		if off+4+alen > end {
			break
		}
		v := buf[off+4 : off+4+alen]
		m.attrs[at] = append([]byte(nil), v...)
		if at == stdTurnAtErrorCode && alen >= 4 {
			m.errorCode = int(v[2]&0x07)*100 + int(v[3])
			if alen > 4 {
				m.errorReason = string(v[4:])
			}
		}
		off += 4 + (alen+3)&^3
	}
	return m
}

// ---- 同步事务（allocate~channelBind 阶段 socket 独占读）----

// stdTurnTransact 发送并等响应：RTO ×3 倍增重传，匹配 txId 的响应返回；超时 nil。
func stdTurnTransact(s *stdTurnSession, req *stdTurnMsgBuilder, integrityKey []byte, totalBudget time.Duration) *stdTurnMessage {
	pkt := req.build(integrityKey)
	deadline := time.Now().Add(totalBudget)
	buf := make([]byte, 2048)
	rto := stdTurnTxRto
	round := 0

	for time.Now().Before(deadline) && round < stdTurnTxMaxRounds {
		udpSendTo(s.sock, pkt, s.serverAddr)
		round++
		roundEnd := time.Now().Add(rto)
		if deadline.Before(roundEnd) {
			roundEnd = deadline
		}
		for time.Now().Before(roundEnd) {
			_ = s.sock.SetReadDeadline(roundEnd)
			n, _, err := s.sock.ReadFromUDP(buf)
			if err != nil {
				break
			}
			m := stdTurnParse(buf[:n])
			if m != nil && m.txID == req.txID {
				return m
			}
		}
		rto *= 3
	}
	return nil
}

// ---- 公开 API ----

// stdTurnLongTermKey 长项凭证 key：MD5(username:realm:password)（RFC 5389 §15.4）。
func stdTurnLongTermKey(username, realm, password string) []byte {
	sum := md5.Sum([]byte(username + ":" + realm + ":" + password))
	return sum[:]
}

// stdTurnAllocate：无凭证探 401 拿 realm/nonce（最多 3 发）→ 带凭证重试 → relay 地址。
func stdTurnAllocate(host string, port int, username, password string, timeoutMs int) *stdTurnSession {
	sock, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4zero, Port: 0})
	if err != nil {
		return nil
	}
	defer func() {
		// 失败路径关闭；成功路径 socket 归会话
	}()
	serverAddr, err := net.ResolveUDPAddr("udp", fmt.Sprintf("%s:%d", host, port))
	if err != nil {
		_ = sock.Close()
		return nil
	}
	budget := time.Duration(timeoutMs) * time.Millisecond

	// 第一轮：无凭证 → 401 + REALM + NONCE（单发探包会被 CGNAT 丢包判死整条中继，故最多 3 发 × 3s）
	tmp := &stdTurnSession{sock: sock, serverAddr: serverAddr, username: username}
	var challenge *stdTurnMessage
	for try := 0; try < stdTurnProbeTries && challenge == nil; try++ {
		probe := newStdTurnMsg(stdTurnMtAllocateReq)
		probe.putUint32(stdTurnAtRequestedTrans, stdTurnTransportUDP<<24)
		probe.putStr(stdTurnAtSoftware, "voxlink")
		challenge = stdTurnTransact(tmp, probe, nil, min(budget, stdTurnProbeRoundBudget))
	}
	if challenge == nil || challenge.typ != stdTurnMtAllocateErr || challenge.errorCode != 401 {
		_ = sock.Close()
		return nil
	}
	realmB := challenge.attr(stdTurnAtRealm)
	nonceB := challenge.attr(stdTurnAtNonce)
	if realmB == nil || nonceB == nil {
		_ = sock.Close()
		return nil
	}
	realm := string(realmB)
	nonce := string(nonceB)
	key := stdTurnLongTermKey(username, realm, password)

	session := &stdTurnSession{sock: sock, serverAddr: serverAddr, username: username, authKey: key, realm: realm, nonce: nonce}
	session.lifetimeSec.Store(int64(stdTurnDefaultLifetime))

	// 第二轮：带凭证 Allocate
	req := newStdTurnMsg(stdTurnMtAllocateReq)
	req.putUint32(stdTurnAtRequestedTrans, stdTurnTransportUDP<<24)
	req.putStr(stdTurnAtUsername, username)
	req.putStr(stdTurnAtRealm, realm)
	req.putStr(stdTurnAtNonce, nonce)
	req.putStr(stdTurnAtSoftware, "voxlink")
	ok := stdTurnTransact(session, req, key, budget)
	if ok == nil || ok.typ == stdTurnMtAllocateErr || ok.typ != stdTurnMtAllocateOK {
		_ = sock.Close()
		return nil
	}
	relayed := ok.attr(stdTurnAtXorRelayedAddr)
	relayAddr := stdTurnDecodeXorAddress(relayed, ok.txID)
	if relayAddr == nil {
		_ = sock.Close()
		return nil
	}
	session.relayAddr.Store(relayAddr)
	if life := ok.attr(stdTurnAtLifetime); life != nil && len(life) == 4 {
		session.lifetimeSec.Store(int64(binary.BigEndian.Uint32(life)))
	}
	return session
}

// stdTurnChannelBind CreatePermission（对端 relay IP）+ ChannelBind（对端 relay 地址 → channel）。
func stdTurnChannelBind(s *stdTurnSession, peerAddr *net.UDPAddr, channel uint16, timeoutMs int) bool {
	budget := time.Duration(timeoutMs) * time.Millisecond

	perm := newStdTurnMsg(stdTurnMtCreatePermReq)
	perm.putStr(stdTurnAtUsername, s.username)
	perm.putStr(stdTurnAtRealm, s.realm)
	perm.putStr(stdTurnAtNonce, s.nonce)
	perm.putXorAddress(stdTurnAtXorPeerAddress, &net.UDPAddr{IP: peerAddr.IP, Port: 0})
	pm := stdTurnTransact(s, perm, s.authKey, budget)
	if pm == nil || pm.typ != stdTurnMtCreatePermOK {
		return false
	}

	bind := newStdTurnMsg(stdTurnMtChannelBindReq)
	bind.putStr(stdTurnAtUsername, s.username)
	bind.putStr(stdTurnAtRealm, s.realm)
	bind.putStr(stdTurnAtNonce, s.nonce)
	var chNum [4]byte
	binary.BigEndian.PutUint16(chNum[0:2], channel)
	bind.put(stdTurnAtChannelNumber, chNum[:])
	bind.putXorAddress(stdTurnAtXorPeerAddress, peerAddr)
	bm := stdTurnTransact(s, bind, s.authKey, budget)
	if bm == nil || bm.typ != stdTurnMtChannelBindOK {
		return false
	}
	s.peerAddr.Store(peerAddr)
	s.channel.Store(int32(channel))
	return true
}

// stdTurnRefreshQuiet Refresh 保活（发了就算；lifetimeSec=0 即删除 allocation）。
// socket 已交给 RUDP 后响应包会被 codec.decode 丢弃，无影响。
func stdTurnRefreshQuiet(s *stdTurnSession, lifetimeSec uint32) {
	req := newStdTurnMsg(stdTurnMtRefreshReq)
	req.putStr(stdTurnAtUsername, s.username)
	req.putStr(stdTurnAtRealm, s.realm)
	req.putStr(stdTurnAtNonce, s.nonce)
	req.putUint32(stdTurnAtLifetime, lifetimeSec)
	pkt := req.build(s.authKey)
	udpSendTo(s.sock, pkt, s.serverAddr)
}

// stdTurnChannelBindQuiet ChannelBind 保活重发（channel 10min 幂等刷新）。
func stdTurnChannelBindQuiet(s *stdTurnSession) {
	peerAddr := s.peerAddr.Load()
	ch := uint16(s.channel.Load())
	if peerAddr == nil || ch == 0 {
		return
	}
	bind := newStdTurnMsg(stdTurnMtChannelBindReq)
	bind.putStr(stdTurnAtUsername, s.username)
	bind.putStr(stdTurnAtRealm, s.realm)
	bind.putStr(stdTurnAtNonce, s.nonce)
	var chNum [4]byte
	binary.BigEndian.PutUint16(chNum[0:2], ch)
	bind.put(stdTurnAtChannelNumber, chNum[:])
	bind.putXorAddress(stdTurnAtXorPeerAddress, peerAddr)
	pkt := bind.build(s.authKey)
	udpSendTo(s.sock, pkt, s.serverAddr)
}

// stdTurnClose 拆除：Refresh(lifetime=0) + 关 socket。
func stdTurnClose(s *stdTurnSession) {
	if s == nil {
		return
	}
	stdTurnRefreshQuiet(s, 0)
	_ = s.sock.Close()
}

// stdTurnCred 信令签发的 TURN REST 凭证。
type stdTurnCred struct {
	Host     string
	Port     int
	Username string
	Password string
	Expire   int64
}

// stdTurnFetchCred 拉取凭证：房间 token 鉴权，节点须已启用标准 TURN（stdTurnPort>0）。
func stdTurnFetchCred(a *App, roomCode, clientID, token, nodeID string) *stdTurnCred {
	var data struct {
		Host     string `json:"host"`
		Port     int    `json:"port"`
		Username string `json:"username"`
		Password string `json:"password"`
		Expire   int64  `json:"expire"`
	}
	body := map[string]any{"roomCode": roomCode, "clientId": clientID, "token": token, "nodeId": nodeID}
	if err := a.api.post(a.baseURL(), "/relay/stdturn/cred", body, &data); err != nil {
		// 错误码（MISSING_FIELDS/INVALID_TOKEN/NODE_OFFLINE/RATE_LIMITED）是定位中继故障的唯一线索
		code, msg := "NETWORK", err.Error()
		var ae *APIError
		if errors.As(err, &ae) {
			code, msg = ae.Code, ae.Message
		}
		a.netLog("warn", "标准 TURN 凭证被拒: error="+code+" message="+msg)
		return nil
	}
	if data.Host == "" || data.Port <= 0 || data.Username == "" || data.Password == "" {
		a.netLog("warn", "标准 TURN 凭证字段不全")
		return nil
	}
	return &stdTurnCred{
		Host: data.Host, Port: data.Port, Username: data.Username,
		Password: data.Password, Expire: data.Expire,
	}
}

// stdTurnPathCodec ChannelData 包封：| channel(2) | length(2) | payload |（RFC 5766 §11.4）。
type stdTurnPathCodec struct {
	channel uint16
}

func (c stdTurnPathCodec) encode(frame []byte) []byte {
	out := make([]byte, 4+len(frame))
	binary.BigEndian.PutUint16(out[0:2], c.channel)
	binary.BigEndian.PutUint16(out[2:4], uint16(len(frame)))
	copy(out[4:], frame)
	return out
}

func (c stdTurnPathCodec) decode(packet []byte) []byte {
	if len(packet) < 4 {
		return nil
	}
	ch := binary.BigEndian.Uint16(packet[0:2])
	if ch < stdTurnChannelBase || ch > 0x7FFF || ch != c.channel {
		return nil
	}
	payloadLen := int(binary.BigEndian.Uint16(packet[2:4]))
	if 4+payloadLen > len(packet) {
		return nil
	}
	return append([]byte(nil), packet[4:4+payloadLen]...)
}

// ---- 工具 ----

func stdTurnDecodeXorAddress(v []byte, txID [12]byte) *net.UDPAddr {
	if len(v) < 8 || v[0] != 0 {
		return nil
	}
	port := int(binary.BigEndian.Uint16(v[2:4])) ^ int(stdTurnMagicCookie>>16)
	if v[1] == 0x01 {
		x := binary.BigEndian.Uint32(v[4:8]) ^ stdTurnMagicCookie
		var ip [4]byte
		binary.BigEndian.PutUint32(ip[:], x)
		return &net.UDPAddr{IP: ip[:], Port: port}
	}
	if v[1] == 0x02 && len(v) >= 20 {
		ip := make([]byte, 16)
		for i := 0; i < 16; i++ {
			var x byte
			if i < 4 {
				x = byte(stdTurnMagicCookie >> (24 - i*8))
			} else {
				x = txID[i-4]
			}
			ip[i] = v[4+i] ^ x
		}
		return &net.UDPAddr{IP: ip, Port: port}
	}
	return nil
}

func stdTurnHmacSha1(key, data []byte) []byte {
	mac := hmac.New(sha1.New, key)
	mac.Write(data)
	return mac.Sum(nil)
}
