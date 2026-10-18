package main

// rudp.go — 可靠 UDP 隧道（对齐模组 ReliableUdpTransport.java，帧格式逐字节互通）：
//
//	帧头 11B：56 4C + type(u8) + seq(u32 BE) + ack(u32 BE)
//	DATA/FEC_XOR 附加：payloadLen(u16 BE)；DATA 头 13B；FEC 头 13B + [13]=count
//	type: 1 PUNCH(收到回 56 4C 02 00 00) 2 PUNCH_ACK 3 DATA 4 ACK(纯11B)
//	      7 DISCONNECT 8 KEEPALIVE(11B，收到必回) 9 FEC_XOR
//	      10 RESTART(收到→ICE 重启 RUDP:718-722；本端判死时主动发 RUDP:517-528) 11 VOICE(忽略)
//
// 语义：累计 ACK（ack=nextExpectedSeq）、RTO RFC6298 夹 [100,800]ms、
// 重传退避 rto+min(retries,3)*250、乱序窗口 128、<512B 小包首发 50ms 后复制重发一次、
// KEEPALIVE 1s、静默>8s 降级、pending 且静默>24s 判死、>60s 硬超时、
// 心跳连续 5 拍零入站判"单向死"（发 RESTART → 触发 ICE 重启回调 → 关闭，RUDP:51、1136-1157）。
// FEC：发送按组 4 包 + XOR（ReliableUdpTransport.java:820-842 字节布局），
// 接收端收齐 3+XOR 恢复缺失包。连接建立第一件事：互发 KEEPALIVE（无 SYN 握手）。
//
// mux（relayMuxV1，玩家中继 v2 数据面）：启用后 DATA 载荷 = [1字节chan][数据]，
// chan0=本连接自有流（兼容旧读者），1..15=中继子通道，共享同一序号空间/窗口/重传；
// 载荷只有 1 字节（仅 chan）= 该子通道对端关闭（EOF）。未启用时线上字节与历史版本
// 逐字节一致（互操作零影响）。enableMux 必须在 Start 前调用——mux 改变线上载荷格式，
// 不允许中途切换。
//
// 死对端熔断：连续重传轮次达 20 轮且全程对端零收包、总静默超 23s 才清窗；清窗不丢数据，
// 积压转入救援队列慢速重传（每 6s 一轮，最多 8 轮），对端恢复后按其 nextExpectedSeq 无损续流。

import (
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

const (
	rudpTypePunch      byte = 1
	rudpTypePunchAck   byte = 2
	rudpTypeData       byte = 3
	rudpTypeAck        byte = 4
	rudpTypeDisconnect byte = 7
	rudpTypeKeepalive  byte = 8
	rudpTypeFecXor     byte = 9
	rudpTypeRestart    byte = 10
	rudpTypeVoice      byte = 11

	rudpHeaderSize  = 11
	rudpMaxPayload  = 1400
	rudpRecvBufSize = 1500
	// TURN 包封下 FEC 帧(22头+块+4MAC)必须 ≤ 节点 1400 上限，超了整包静默丢（模组 TURN_MAX_SEND_CHUNK）
	rudpTurnMaxChunk = 1374

	rudpFecGroupSize  = 4
	rudpRecvWindow    = 128 // 乱序窗口
	rudpBufChunksMax  = 512 // 交付队列上限（对齐模组 bufferedChunks）
	rudpSmallThresh   = 512
	rudpSmallDupDelay = 50 * time.Millisecond

	rudpTick            = 50 * time.Millisecond
	rudpKeepaliveEvery  = 1 * time.Second
	rudpRtoMinMs        = 100
	rudpRtoMaxMs        = 800
	rudpInitRtoMs       = 200
	rudpBackoffMs       = 250
	rudpSilenceDegrade  = 8 * time.Second
	rudpSilenceDead     = 24 * time.Second
	rudpSilenceHard     = 60 * time.Second
	rudpStuckWriteLimit = 30 * time.Second

	// 单向死判据：心跳连续 N 拍零入站即判死（模组 HEARTBEAT_FAIL_LIMIT=5，RUDP:51；
	// 拍距 = KEEPALIVE_INTERVAL_S 1s，RUDP:48，排拍见 RUDP:430）。每拍只看 lastRecv 有没有
	// 前移（RUDP:1143），前移即整体复位，所以这条走的是"发得出去、收不回来"的快速通道，
	// 比上面 24s/60s 两条静默判定先收敛。
	rudpHeartbeatFailLimit = 5

	// 死对端熔断：连续重传 20 轮、期间对端零收包且总静默超 23s 才清窗。
	// MIN_SILENCE 贴着上面的 24s 强制关闭取值——对端 keepalive 间隔仅 1s，
	// 活链接不可能 23s 零收包，熔断因此绝不早于旧行为掐断任何可能自愈的链路。
	rudpDeadPeerBreakerRounds     = 20
	rudpDeadPeerBreakerMinSilence = 23 * time.Second
	// 救援队列：清窗不丢数据，每 6s 慢速重传一轮、最多 8 轮；耗尽后收敛行为同旧"直接丢弃"
	rudpRescueInterval  = 6 * time.Second
	rudpRescueMaxRounds = 8

	// 端口漂移重绑：当前端口 6s 内收到过有效包时绝不切走（1.1.0-2 实测：噪声包把目标
	// 切到即将关闭的死映射，ACK 全进黑洞，host 重传 1130 次后 70s 断链）；
	// 候选新端口须在 5s 窗口内再次命中才真正 rebind。
	rudpCurrentPortAlive = 6 * time.Second
	rudpRebindConfirmWin = 5 * time.Second

	// 早到 EOF 只对 chan 1..7 落位，>7 视为垃圾
	rudpMuxEarlyEofMaxChan = 7
)

var (
	errRudpClosed = errors.New("rudp: 连接已关闭")
	errRudpStuck  = errors.New("rudp: 传输停滞（对端无响应）")
	// errRudpAborted 中继会话收尾打断阻塞读（Java thread.interrupt 的 Go 形态）
	errRudpAborted = errors.New("rudp: 读侧被会话收尾打断")

	// rudpPunchAckPb 收到 PUNCH 的回包：56 4C 02 00 00（模组 sendPunchAck 同款）
	rudpPunchAckPb = []byte{0x56, 0x4C, rudpTypePunchAck, 0, 0}
)

// ---- seq 运算（对齐模组 seqAfter/seqDiff，模 2^32）----

func seqAfter(a, b uint32) bool {
	return a > b && a-b < 1073741823 || a < b && b-a > 1073741823
}

func seqDiff(newer, older uint32) uint32 {
	return newer - older
}

// ---- 帧编解码 ----

type rudpFrame struct {
	Type       byte
	Seq        uint32
	Ack        uint32
	Payload    []byte // DATA 载荷 / FEC 的 XOR 载荷
	FecCount   int    // FEC_XOR：原始包个数
	FecLengths []int  // FEC_XOR：各原始包长度
}

// rudpEncode 编码一帧。PUNCH/PUNCH_ACK 为 5 字节明文控制包（56 4C type 00 00），
// 与模组 ReliableUdpTransport.sendPunchAck 逐字节一致。
func rudpEncode(f rudpFrame) []byte {
	switch f.Type {
	case rudpTypePunch, rudpTypePunchAck:
		return []byte{0x56, 0x4C, f.Type, byte(f.Seq >> 8), byte(f.Seq)}
	case rudpTypeData:
		out := make([]byte, 13+len(f.Payload))
		out[0], out[1] = 0x56, 0x4C
		out[2] = f.Type
		binary.BigEndian.PutUint32(out[3:7], f.Seq)
		binary.BigEndian.PutUint32(out[7:11], f.Ack)
		binary.BigEndian.PutUint16(out[11:13], uint16(len(f.Payload)))
		copy(out[13:], f.Payload)
		return out
	case rudpTypeFecXor:
		count := len(f.FecLengths)
		if f.FecCount > 0 {
			count = f.FecCount
		}
		bodyOff := 14 + count*2
		out := make([]byte, bodyOff+len(f.Payload))
		out[0], out[1] = 0x56, 0x4C
		out[2] = f.Type
		binary.BigEndian.PutUint32(out[3:7], f.Seq) // seq 字段 = groupId
		binary.BigEndian.PutUint32(out[7:11], 0)
		binary.BigEndian.PutUint16(out[11:13], uint16(len(f.Payload)))
		out[13] = byte(count)
		for i := 0; i < count; i++ {
			binary.BigEndian.PutUint16(out[14+i*2:16+i*2], uint16(f.FecLengths[i]))
		}
		copy(out[bodyOff:], f.Payload)
		return out
	default: // ACK/KEEPALIVE/DISCONNECT/RESTART/VOICE
		extra := 0
		if f.Type == rudpTypeVoice {
			extra = len(f.Payload)
		}
		out := make([]byte, rudpHeaderSize+extra)
		out[0], out[1] = 0x56, 0x4C
		out[2] = f.Type
		binary.BigEndian.PutUint32(out[3:7], f.Seq)
		binary.BigEndian.PutUint32(out[7:11], f.Ack)
		copy(out[rudpHeaderSize:], f.Payload)
		return out
	}
}

// rudpDecode 解码一帧；无法解析返回 ok=false。
func rudpDecode(b []byte) (f rudpFrame, ok bool) {
	if len(b) < 3 {
		return f, false
	}
	if b[0] != 0x56 || b[1] != 0x4C {
		return f, false
	}
	switch b[2] {
	case rudpTypePunch, rudpTypePunchAck:
		// 5 字节控制包：nonce 落在 Seq 位置便于回显
		if len(b) < 5 {
			return f, false
		}
		return rudpFrame{Type: b[2], Seq: uint32(binary.BigEndian.Uint16(b[3:5]))}, true
	case rudpTypeData:
		if len(b) < 13 {
			return f, false
		}
		pl := int(binary.BigEndian.Uint16(b[11:13]))
		if 13+pl > len(b) {
			return f, false
		}
		payload := make([]byte, pl)
		copy(payload, b[13:13+pl])
		return rudpFrame{
			Type:    rudpTypeData,
			Seq:     binary.BigEndian.Uint32(b[3:7]),
			Ack:     binary.BigEndian.Uint32(b[7:11]),
			Payload: payload,
		}, true
	case rudpTypeFecXor:
		if len(b) < 14 {
			return f, false
		}
		xorLen := int(binary.BigEndian.Uint16(b[11:13]))
		count := int(b[13])
		xorOff := 14 + count*2
		if count < 0 || xorOff+xorLen > len(b) {
			return f, false
		}
		lengths := make([]int, count)
		for i := 0; i < count; i++ {
			lengths[i] = int(binary.BigEndian.Uint16(b[14+i*2 : 16+i*2]))
		}
		payload := make([]byte, xorLen)
		copy(payload, b[xorOff:xorOff+xorLen])
		return rudpFrame{
			Type:       rudpTypeFecXor,
			Seq:        binary.BigEndian.Uint32(b[3:7]),
			Payload:    payload,
			FecCount:   count,
			FecLengths: lengths,
		}, true
	default:
		if len(b) < rudpHeaderSize {
			return f, false
		}
		return rudpFrame{
			Type:    b[2],
			Seq:     binary.BigEndian.Uint32(b[3:7]),
			Ack:     binary.BigEndian.Uint32(b[7:11]),
			Payload: append([]byte(nil), b[rudpHeaderSize:]...),
		}, true
	}
}

// ---- 连接 ----

type rudpPending struct {
	data     []byte
	sendTime time.Time
	retries  int
}

// rudpPathCodec 传输层"路径"包封编解码器（UdpPath.Codec 同款语义）：
// 直连路径 codec=nil（rudp 帧裸跑）；TURN 路径 codec=TurnPathCodec/StdTurnPathCodec。
type rudpPathCodec interface {
	encode(frame []byte) []byte
	decode(packet []byte) []byte
}

// rudpConn 一条可靠 UDP 会话，实现 io.Reader/io.Writer（字节流，供 TCP 桥搬运）。
type rudpConn struct {
	conn   *net.UDPConn
	remote atomic.Pointer[net.UDPAddr] // 发送目标（可随对端源地址漂移更新）

	// 路径包封（TURN 等中继路径；nil=直连裸跑；Start 前设置）
	codec rudpPathCodec

	// punchAuthV1：认证模式下所有帧附 4 字节截断 HMAC-SHA256
	authMu               sync.Mutex
	authKey              []byte
	allowAuthDowngrade   bool
	consecutiveAuthDrops int

	// 发送侧（sendMu 保护）
	sendMu         sync.Mutex
	nextSeq        uint32
	oldestUnacked  uint32
	pending        map[uint32]*rudpPending
	srtt           float64
	rttvar         float64
	rtoMs          int
	window         int
	failures       int
	degraded       bool
	lastAckSeq     uint32
	dupAckCount    int
	fecSendGroup   [][]byte
	fecSendGroupID int64
	ackNotify      chan struct{} // ACK 到达通知（容量 1）

	// 死对端熔断状态（仅 retransmitCheck 单线程 + sendMu 访问）：
	// streakStartMs 锚到本段连续重传起点，期间任何对端收包都会使 lastRecv 前移从而整体复位
	deadPeerRounds      int
	deadPeerStreakStart int64
	// 救援队列（sendMu 内访问）：熔断清出的积压。直接丢弃会让接收侧卡在 seq 空洞
	// （keepalive 照常流动，24s/60s 两条判死规则全失效），数据面静默死亡；
	// 对端恢复后按其 nextExpectedSeq 无损续流，累计 ack 越过救援段末尾即停。
	rescue           map[uint32]*rudpPending
	rescueLastSeq    int64 // 救援段最后一个序号；-1 = 无救援段
	rescueRounds     int
	rescueLastResend int64

	// 接收侧（recvMu 保护）
	recvMu       sync.Mutex
	nextExpected atomic.Uint32
	recvBuf      map[uint32][]byte
	buffered     int // 交付队列当前 chunk 数（各子通道与主流共享同一预算）
	fecGroups    map[uint32]map[uint32][]byte
	fecXor       map[uint32][]byte
	fecLens      map[uint32][]int
	fecMinGroup  uint32
	fecHasMin    bool
	chunks       [][]byte // 已按序待读载荷（mux 模式下即 chan 0）
	mainEof      bool     // chan 0 收到 1 字节 EOF 载荷
	dataNotify   chan struct{}

	// mux 子通道（recvMu 保护 map；子通道自身的 eof 亦在其中读写）
	muxEnabled atomic.Bool
	subStreams map[int]*rudpSubstream

	lastCurrentPortRecv atomic.Int64 // 当前发送端口最近一次收到有效包（漂移重绑的存活判据）
	pendingRebindPort   atomic.Int32 // 待确认的新端口，-1 = 无
	pendingRebindTime   atomic.Int64

	lastRecv  atomic.Int64
	closed    atomic.Bool
	started   atomic.Bool
	closeOnce sync.Once
	doneCh    chan struct{}
	closeMsg  atomic.Pointer[string]
	onClosed  func(reason string)
	onRestart func() // ICE 重启回调的历史入口名（下面那个槽的回落位，见 setOnRestart 注释）
	// ICE 重启回调（模组只有一个槽 onIceRestartRequested，RUDP:120；收对端 RESTART 与
	// 本端判死都走它）。模组由 ConnectionManager.java:15689 挂 CM.triggerIceRestart(14211)。
	onIceRestartRequested func()
	iceRestartTriggered   atomic.Bool // 一次会话只触发一次 ICE 重启（RUDP:102、505）
	logf                  func(level, msg string)

	// 单向死检测（模组 RUDP:113-116，只在心跳拍那一条线程上访问）：
	// streak 例外——认证降级要跨收包线程复位它（RUDP:391），故用原子。
	lastHeartbeatMs     int64 // 上一拍的锚点：lastRecv 超过它即算这一拍收到过包
	heartbeatFailStreak atomic.Int32
	heartbeatEverRx     bool // 从未收进过字节的不算单向死（RUDP:1146 的闸门）
}

// setLogf 挂上网络日志（模组 [ReliableUdp] 那批 WARN/INFO 是本模块唯一的现场证据来源：
// 熔断、救援轮次、端口漂移都只有日志能证明发生过）。不挂则静默，行为不变。
func (c *rudpConn) setLogf(fn func(level, msg string)) { c.logf = fn }

func (c *rudpConn) rlog(level, format string, args ...any) {
	if c.logf == nil {
		return
	}
	c.logf(level, "[ReliableUdp] "+fmt.Sprintf(format, args...))
}

// rudpSubstream mux 模式下的一条中继子通道（模组 ReliableUdpTransport.SubChannel）：
// 与 chan 0 共享序号空间/窗口/重传，因此获得与主流一致的可靠有序语义，
// 但队列与 EOF 各自独立——两条 MC 会话绝不能串进同一条字节流（v1 的病根）。
type rudpSubstream struct {
	conn   *rudpConn
	chanID int
	chunks [][]byte // conn.recvMu 保护
	eof    bool     // conn.recvMu 保护
}

// newRudp 在给定 UDP socket 与对端地址上建立会话（需调用 Start 启动）。
func newRudp(conn *net.UDPConn, remote *net.UDPAddr) *rudpConn {
	c := &rudpConn{
		conn:           conn,
		pending:        map[uint32]*rudpPending{},
		rtoMs:          rudpInitRtoMs,
		window:         64,
		fecSendGroupID: -1,
		recvBuf:        map[uint32][]byte{},
		fecGroups:      map[uint32]map[uint32][]byte{},
		fecXor:         map[uint32][]byte{},
		fecLens:        map[uint32][]int{},
		ackNotify:      make(chan struct{}, 1),
		dataNotify:     make(chan struct{}, 1),
		doneCh:         make(chan struct{}),
		rescue:         map[uint32]*rudpPending{},
		rescueLastSeq:  -1,
		subStreams:     map[int]*rudpSubstream{},
	}
	c.pendingRebindPort.Store(-1)
	if remote != nil {
		c.remote.Store(remote)
	}
	c.lastRecv.Store(time.Now().UnixMilli())
	// 判死锚点与 lastRecvTime 同时起步（模组字段初值 RUDP:96、114）：
	// 第一拍之前 lastRecv 必然 > lastHeartbeatMs，不会凭空累计失败轮次
	c.lastHeartbeatMs = time.Now().UnixMilli()
	return c
}

// setOnClosed 注册关闭回调（对端断开/判死/重传超限时触发一次）。
func (c *rudpConn) setOnClosed(fn func(reason string)) { c.onClosed = fn }

// setOnRestart 注册 ICE 重启回调（历史入口名：以前只挂在对端 RESTART 帧上，现在与本端判死共用，
// 见 setOnIceRestartRequested —— 模组侧只有一个槽 RUDP:120）。
func (c *rudpConn) setOnRestart(fn func()) { c.onRestart = fn }

// setOnIceRestartRequested 注册 ICE 重启回调（模组 setOnIceRestartRequested:448-450）。
// 模组只有这一个回调槽 onIceRestartRequested（RUDP:120），收对端 RESTART（:721）与三个判死点
// （心跳 :1150、60s :1176、24s :1182）都走它；接线位置 ConnectionManager.java:15689
// `transport.setOnIceRestartRequested(this::triggerIceRestart)`（CM.triggerIceRestart 见 :14211）。
// 本端历史上把这个槽叫 onRestart/setOnRestart（上面那个），triggerIceRestart 优先取本 setter、
// 没挂时回落到 onRestart，既有三处接线（conn_engine 的 guestIceRestart 与 hostRestartPeer、
// turn_flow 的 teardownTurn）不改也生效。
func (c *rudpConn) setOnIceRestartRequested(fn func()) { c.onIceRestartRequested = fn }

// requestIceRestart 引擎侧主动请求重启（模组 requestIceRestart:497-499，就是 triggerIceRestart 的公开外壳）。
func (c *rudpConn) requestIceRestart() { c.triggerIceRestart() }

// triggerIceRestart 通知引擎发起 ICE 重启（模组 triggerIceRestart:501-515）：
// 已关闭不触发（:502-504）、iceRestartTriggered 保证一条会话只触发一次（:505）、
// 回调抛异常不回灌传输（:510-512 的 try/catch → Go 的 recover）。
// 闸门与 CAS 一定在本线程做完再派发回调：判死路径派发完就 close（RUDP:1150-1151），
// 若把 closed 检查也丢进 goroutine，回调会被自己紧随的关闭吞掉、永不落地。
// 模组在调用线程上同步 run()；本端回调是引擎拆链路（hostRestartPeer/teardownTurn，会阻塞），
// 同步跑会拖死心跳拍与收包循环，故派发出去——语义次序（先判闸后执行）不变。
func (c *rudpConn) triggerIceRestart() {
	if c.closed.Load() { // :502
		return
	}
	if !c.iceRestartTriggered.CompareAndSwap(false, true) { // :505
		return
	}
	fn := c.onIceRestartRequested
	if fn == nil {
		fn = c.onRestart // 兼容只挂 setOnRestart 的历史接线
	}
	if fn == nil {
		return
	}
	go func() {
		defer func() {
			if r := recover(); r != nil {
				c.rlog("warn", "ICE Restart callback exception: %v", r) // :511
			}
		}()
		fn()
	}()
}

// sendRestart 主动发 RESTART 帧（模组 sendRestart:517-528）：11B 裸帧、type=10（:38）、
// seq=0、ack=0（:522-523），认证与路径包封走 sendFrame 统一出口（对应 :524-525 的
// finalizeFrame + sendOnPrimary）。调用点必须排在 close 之前——sendFrame 见 closed 即静默不发，
// 而模组 close() 也在 sendRestart 之后（RUDP:1149-1151）。
func (c *rudpConn) sendRestart() {
	c.sendFrame(rudpFrame{Type: rudpTypeRestart})
}

// setCodec 设置路径包封编解码器（须在 Start 前调用；nil=直连裸跑）。
func (c *rudpConn) setCodec(pc rudpPathCodec) { c.codec = pc }

// setAuthKey 启用 punchAuthV1 帧认证。allowDowngrade 只允许 TURN 那四条传输传 true
// （模组 ReliableUdpTransport.java:366-369：连续 3 帧未认证才回明文，供旧 TURN 服务端互操作；
// 直连 P2P 保持严格认证——它的源地址重绑只在裸跑路径生效，降级会被伪造包利用）。
func (c *rudpConn) setAuthKey(key []byte, allowDowngrade bool) {
	c.authMu.Lock()
	if key == nil {
		c.authKey = nil
	} else {
		c.authKey = append([]byte(nil), key...)
	}
	c.allowAuthDowngrade = allowDowngrade
	c.consecutiveAuthDrops = 0
	c.authMu.Unlock()
}

// armedAuthKey 取当前认证密钥（nil=明文）。
func (c *rudpConn) armedAuthKey() []byte {
	c.authMu.Lock()
	defer c.authMu.Unlock()
	return c.authKey
}

// authDrop 认证失败丢弃计数；连续 3 次且允许降级 → 回明文。
func (c *rudpConn) authDrop() {
	c.authMu.Lock()
	defer c.authMu.Unlock()
	if c.authKey == nil {
		return
	}
	c.consecutiveAuthDrops++
	if c.allowAuthDowngrade && c.consecutiveAuthDrops >= 3 {
		c.authKey = nil
		c.rlog("warn", "peer sends unauthenticated frames x%d, TURN interop downgrade to plaintext", c.consecutiveAuthDrops)
		// 降级与静默判死同拍竞速会差毫秒级把刚建立的明文链路判死：重置判死锚点，
		// 给明文通道一整个窗口（模组 handleAuthDrop 同一处改动）。
		// RUDP:391-392 重置的是两样：心跳失败轮次(:391) + lastRecvTime(:392)
		c.heartbeatFailStreak.Store(0)
		c.lastRecv.Store(time.Now().UnixMilli())
	}
}

// authOk 认证通过，复位计数。
func (c *rudpConn) authOk() {
	c.authMu.Lock()
	c.consecutiveAuthDrops = 0
	c.authMu.Unlock()
}

// Start 启动收发循环；连接建立第一件事：发一个 KEEPALIVE（模组 start() 即发，RUDP:415-421）。
// 1s 定时器挂的是 heartbeatTick 而非裸发 keepalive（RUDP:430）：保活与单向死判定共用这一拍。
func (c *rudpConn) Start() {
	c.started.Store(true)
	c.sendFrame(rudpFrame{Type: rudpTypeKeepalive})
	go c.recvLoop()
	go c.tickLoop()
	go c.keepaliveLoop()
}

// IsConnected 连接是否存活。
func (c *rudpConn) IsConnected() bool { return !c.closed.Load() }

// isConnected 对齐模组 ReliableUdpTransport.isConnected(434-436: connected && running)：
// 已 start 且未关闭。载具热切的排干判据（PB:1021-1023、PB:1036-1038）用的是这一语义——
// 未 start 的载具 connected 位没置起，不排干；与导出 IsConnected 的差别即 started 这一半。
func (c *rudpConn) isConnected() bool { return c.started.Load() && !c.closed.Load() }

// hasPendingOutbound 对齐模组 ReliableUdpTransport.hasPendingOutbound(438-441)：
// 出口是否还有未被确认的数据（含积压重传队列，pendingAcks 的 Go 形态即 pending）。
// 救援队列不计：模组里救援包已离开 pendingAcks（黑名单 #10"不入 pending"），
// 熔断开路后的慢速重传不该把换道排干堵死。
func (c *rudpConn) hasPendingOutbound() bool {
	c.sendMu.Lock()
	defer c.sendMu.Unlock()
	return len(c.pending) > 0
}

// RemoteAddr 当前发送目标（可能已随对端漂移更新）。
func (c *rudpConn) RemoteAddr() *net.UDPAddr { return c.remote.Load() }

// ---- mux 子通道（relayMuxV1，玩家中继 v2 数据面）----

// relayMuxCapability 能力名（模组 ProtocolNegotiator.CAP_RELAY_MUX_V1）。
const relayMuxCapability = "relayMuxV1"

// selfSupportsCap 本端能力表（app.go 的 appCapabilities）是否声明该能力。
// 表里没有 relayMuxV1 时 mux 永不启用，线上格式保持历史字节 —— 互操作零影响的默认安全侧。
func selfSupportsCap(cap string) bool {
	for _, c := range appCapabilities {
		if c == cap {
			return true
		}
	}
	return false
}

// peerCapsListContains 在册房客的能力表（hostPeer.caps，来自 join_request）是否含 cap。
func peerCapsListContains(caps []string, cap string) bool {
	for _, c := range caps {
		if c == cap {
			return true
		}
	}
	return false
}

// setupRelayMux 双方都声明 relayMuxV1 时让传输进入 mux 模式（模组 CM:15703-15714，
// 在 transport.start() 之前）。peerMux 由调用方按所在侧判定：
// host 侧查该房客 join_request 的能力表，joiner 侧查房主能力表（hostSupportsCap）。
func setupRelayMux(rc *rudpConn, peerMux bool, logf func(level, msg string), transportKey, side string) {
	if !selfSupportsCap(relayMuxCapability) || !peerMux {
		return
	}
	rc.enableMux()
	if logf != nil {
		logf("info", "[RelayMux] transport '"+transportKey+"' mux enabled ("+side+" side)")
	}
}

// enableMux 进入 mux 模式，由连接建立处的能力协商结果驱动（双方都声明 relayMuxV1 才调）。
// 必须在 Start 之前调用：mux 改变 DATA 载荷格式，中途切换对端会把 chan 字节当成 MC 数据读。
func (c *rudpConn) enableMux() { c.muxEnabled.Store(true) }

// isMuxEnabled 本传输是否已 mux 化（候选者侧能否承载被中继会话的判据之一）。
func (c *rudpConn) isMuxEnabled() bool { return c.muxEnabled.Load() }

// getSubstreamInput 子通道的入向流（惰性建通道，模组 computeIfAbsent）。
func (c *rudpConn) getSubstreamInput(ch int) io.Reader { return c.substream(ch) }

// getSubstreamOutput 子通道的出向流：写出的每个分片前会缀上 chan 字节。
func (c *rudpConn) getSubstreamOutput(ch int) io.Writer { return c.substream(ch) }

// substream 同上面两个入口，返回具体类型以便中继腿装配（读侧需要 abort 能力）。
func (c *rudpConn) substream(ch int) *rudpSubstream {
	c.recvMu.Lock()
	defer c.recvMu.Unlock()
	return c.subChannelLocked(ch)
}

// subChannelLocked 取/建子通道（须持 recvMu）。
func (c *rudpConn) subChannelLocked(ch int) *rudpSubstream {
	sc, ok := c.subStreams[ch]
	if !ok {
		sc = &rudpSubstream{conn: c, chanID: ch}
		c.subStreams[ch] = sc
	}
	return sc
}

// isSubstreamEof 子通道是否已被对端正常关闭（EOF 到达且缓冲读干）。
// 未建过的通道也报 EOF：没有队列就等不到字节，读侧继续挂着只会拖到传输级判死。
func (c *rudpConn) isSubstreamEof(ch int) bool {
	c.recvMu.Lock()
	defer c.recvMu.Unlock()
	sc, ok := c.subStreams[ch]
	return !ok || sc.eof
}

// closeSubstream 本端主动关闭子通道：发送仅含 chan 字节的 1 字节载荷作为 EOF 标记，
// 作为可靠 DATA 进重传队列（EOF 本身不能丢）。非 mux 模式没有 chan 语义，直接返回。
func (c *rudpConn) closeSubstream(ch int) {
	if !c.muxEnabled.Load() {
		return
	}
	c.sendMu.Lock()
	defer c.sendMu.Unlock()
	if c.closed.Load() || !c.started.Load() {
		return
	}
	seq := c.nextSeq
	c.nextSeq++
	payload := []byte{byte(ch)}
	c.pending[seq] = &rudpPending{data: payload, sendTime: time.Now()}
	c.sendDataPacket(seq, payload, false)
}

// rudpReasonOneWayDead 心跳判死的关闭原因（模组 heartbeatTick 判死点 RUDP:1146-1152）。
// 前缀"单向死"必须同时出现在 rudpReasonIsDead 的名单里，否则 RUDP:1148 那次 onLinkDead 不上报。
const rudpReasonOneWayDead = "单向死: 心跳连续 5 拍零入站"

func (c *rudpConn) closeWith(reason string) {
	c.closeMsg.CompareAndSwap(nil, &reason)
	// 判死四类（对端断开不算）交出去登记日志样本：模组是在 ReliableUdpTransport.java
	// :1148（心跳连续 5 拍零入站）/:1174（60s 硬超）/:1180（24s 静默且有待确认）
	// :1206（重传超限）四个判定点各调一次 onLinkDead，本端这四个判定都收敛到 closeWith，
	// 所以在这一个出口按 reason 分流，语义一一对应。
	if rudpReasonIsDead(reason) {
		transportLinkDead(reason)
	}
	_ = c.Close()
}

// rudpReasonIsDead 区分"判死"与"正常关闭/对端主动断开"：只有前者要触发立即上传。
// 名单逐项对应模组的四个 onLinkDead 判定点：单向死(RUDP:1148)、静默超60s(:1174)、
// 静默超24s(:1180)、重传超限(:1206)；"传输停滞"对应写侧 30s 无进展（模组在 sendBytes
// :1306-1307 只是抛 IOException，本端按判死形态收敛并上报）。
func rudpReasonIsDead(reason string) bool {
	switch {
	case strings.HasPrefix(reason, "单向死"),
		strings.HasPrefix(reason, "传输停滞"),
		strings.HasPrefix(reason, "静默超60s"),
		strings.HasPrefix(reason, "静默超24s"),
		strings.HasPrefix(reason, "seq "):
		return true
	}
	return false
}

// Close 发送 DISCONNECT 并关闭会话与底层 socket。幂等。
func (c *rudpConn) Close() error {
	c.closeOnce.Do(func() {
		// 先尽力发送 DISCONNECT（closed 置位后 sendFrame 会跳过发送）
		if addr := c.remote.Load(); addr != nil {
			udpSendTo(c.conn, rudpEncode(rudpFrame{Type: rudpTypeDisconnect}), addr)
		}
		c.closed.Store(true)
		close(c.doneCh)
		c.conn.SetReadDeadline(time.Now())
		_ = c.conn.Close()
		c.notify(c.dataNotify)
		c.notify(c.ackNotify)
		if fn := c.onClosed; fn != nil {
			msg := "closed"
			if p := c.closeMsg.Load(); p != nil {
				msg = *p
			}
			go fn(msg)
		}
	})
	return nil
}

func (c *rudpConn) notify(ch chan struct{}) {
	select {
	case ch <- struct{}{}:
	default:
	}
}

// sendFrame 尽力发送一帧到当前目标（写错误全部忽略：Windows WSAECONNRESET 等）。
// 帧序：rudp 帧 →（认证模式附 4 字节截断 MAC）→（路径包封 codec）。
func (c *rudpConn) sendFrame(f rudpFrame) {
	if c.closed.Load() {
		return
	}
	addr := c.remote.Load()
	if addr == nil {
		return
	}
	pkt := rudpEncode(f)
	if key := c.armedAuthKey(); key != nil {
		pkt = punchAuthAppendTrailer4(key, pkt)
	}
	if c.codec != nil {
		pkt = c.codec.encode(pkt)
	}
	udpSendTo(c.conn, pkt, addr)
}

// ---- 接收循环 ----

func (c *rudpConn) recvLoop() {
	buf := make([]byte, rudpRecvBufSize)
	for {
		select {
		case <-c.doneCh:
			return
		default:
		}
		_ = c.conn.SetReadDeadline(time.Now().Add(rudpTick))
		n, from, err := c.conn.ReadFromUDP(buf)
		if err != nil {
			if errors.Is(err, net.ErrClosed) {
				// 承载 socket 被外部关掉（TURN TCP 兜底的 close 就是这一类）：
				// 继续循环每次立即返回错误 = 单核 100% 空转且永不上报。按 Java 四个判死点
				// 之一的形态走 closeWith，让泵进 15s 换道窗而不是静默硬扛。
				c.closeWith("传输停滞: 承载 socket 已被关闭")
				return
			}
			select {
			case <-c.doneCh:
				return
			default:
				continue // 读超时/WSAECONNRESET 一律继续
			}
		}
		pkt := buf[:n]
		transportActivity() // ReliableUdpTransport.java:599：收到对端包即记活跃（内部 1s 节流）
		if c.codec != nil {
			frame := c.codec.decode(pkt)
			if frame == nil {
				continue
			}
			pkt = frame
		}
		// 认证模式：所有帧必须携带有效截断 MAC，失败一律丢弃（连续 3 帧未认证
		// 且允许降级时回明文——旧引擎互操作）
		if key := c.armedAuthKey(); key != nil {
			if len(pkt) < 9 || !punchAuthVerifyTrailer4(key, pkt) {
				c.authDrop()
				continue
			}
			c.authOk()
		}
		// 源地址重绑只看 packet 的来源、不碰帧体，所以放在帧长门（DATA 13B/FEC 14B）之外：
		// 模组 processDatagram 的顺序是 魔数 → MAC → maybeRebindRemote → 按类型判 frameMinLen
		if len(pkt) >= 3 && pkt[0] == 0x56 && pkt[1] == 0x4C {
			c.maybeRebindRemote(from)
		}
		frame, ok := rudpDecode(pkt)
		if !ok {
			continue
		}
		c.processFrame(frame)
	}
}

// maybeRebindRemote 对端端口漂移重绑（逐条对齐模组 maybeRebindRemote）。
// 1.1.9 修正：调用点必须在帧长门之外——跟着 frameMinLen 走的话 11~12B 的 DATA、
// 11~13B 的 FEC_XOR 就不再更新对端地址，玩家中途被 CGNAT 重映射端口时要白等一轮超时。
func (c *rudpConn) maybeRebindRemote(from *net.UDPAddr) {
	if from == nil {
		return
	}
	// 地址漂移只对无包封的直连路径生效：TURN 路径对端固定是中继节点
	if c.codec != nil {
		return
	}
	cur := c.remote.Load()
	if cur == nil {
		// 打洞期以 nil 建会话（模组构造器恒有对端地址，本端靠首个有效源起步）：
		// 只定目标，不置存活锚点，随后就按模组的漂移判据走
		c.remote.Store(from)
		return
	}
	nowMs := time.Now().UnixMilli()
	if cur.Port == from.Port && cur.IP.Equal(from.IP) {
		c.lastCurrentPortRecv.Store(nowMs)
		c.pendingRebindPort.Store(-1)
		return
	}
	if !cur.IP.Equal(from.IP) {
		return
	}
	// 1.0.0 兼容（其 remoteAddress 为 final、无 rebind）：当前端口仍存活（6s 内收到过有效包）
	// 时绝不 rebind。连接初期对端其它打洞 socket 的噪声包会把发送目标切到即将关闭的死映射，
	// ACK 全进黑洞（1.1.0-2 实测：host 重传 1130 次后 70s 断链）。
	// 仅当前端口真死（超时无包）才允许切换，保留真漂移兜底。
	if last := c.lastCurrentPortRecv.Load(); last > 0 && nowMs-last < rudpCurrentPortAlive.Milliseconds() {
		return
	}
	if int(c.pendingRebindPort.Load()) == from.Port &&
		nowMs-c.pendingRebindTime.Load() < rudpRebindConfirmWin.Milliseconds() {
		old := cur.Port
		c.remote.Store(from)
		c.lastCurrentPortRecv.Store(nowMs)
		c.pendingRebindPort.Store(-1)
		c.rlog("info", "Remote port drifted %d -> %d, rebind to actual peer socket (symmetric NAT)", old, from.Port)
	} else {
		c.pendingRebindPort.Store(int32(from.Port))
		c.pendingRebindTime.Store(nowMs)
	}
}

func (c *rudpConn) markRecv() { c.lastRecv.Store(time.Now().UnixMilli()) }

func (c *rudpConn) processFrame(f rudpFrame) {
	switch f.Type {
	case rudpTypePunch:
		c.markRecv()
		// 回 56 4C 02 00 00（模组 sendPunchAck 同款 5 字节）
		c.sendFrame(rudpFrame{Type: rudpTypePunchAck})
	case rudpTypePunchAck:
		c.markRecv()
	case rudpTypeData:
		c.handleData(f)
	case rudpTypeAck:
		c.markRecv()
		c.processAck(f.Ack)
	case rudpTypeDisconnect:
		c.closeWith("对端断开")
	case rudpTypeKeepalive:
		c.markRecv()
		c.resetFailures()
		// 收到必回一个 KEEPALIVE（ack=nextExpected，模组 sendKeepalive 同款）
		c.sendFrame(rudpFrame{Type: rudpTypeKeepalive, Ack: c.nextExpected.Load()})
	case rudpTypeFecXor:
		c.markRecv()
		c.handleFecXor(f)
	case rudpTypeRestart:
		c.markRecv()
		// 对端 RESTART：触发本端 ICE 重启（拆除重建），模组 case 10 是 RUDP:719-721：
		// 刷 lastRecvTime → INFO 日志 → triggerIceRestart()。
		// 走 triggerIceRestart 而不是直接调回调，本端才吃到模组的两个闸门：closed（:502-504）
		// 与一次会话只一次的 iceRestartTriggered（:505）——对端连发多帧 RESTART 只重挂一次。
		c.rlog("info", "Received peer RESTART signal, trigger ICE Restart")
		c.triggerIceRestart()
	case rudpTypeVoice:
		c.markRecv()
		c.handleVoice(f)
	}
}

func (c *rudpConn) handleData(f rudpFrame) {
	c.markRecv()
	c.processAck(f.Ack)
	payload := f.Payload
	if payload == nil {
		c.sendAck()
		return
	}
	seq := f.Seq
	groupID := seq / rudpFecGroupSize

	c.recvMu.Lock()
	// FEC 分组登记（收端永远登记，超窗清理）
	c.fecStoreLocked(groupID, seq, payload)

	expected := c.nextExpected.Load()
	switch {
	case seq == expected:
		if c.buffered >= rudpBufChunksMax {
			c.recvMu.Unlock()
			c.sendAck()
			return
		}
		c.deliverPayloadLocked(payload)
		for {
			nxt := c.nextExpected.Load()
			cached, okc := c.recvBuf[nxt]
			if !okc || c.buffered >= rudpBufChunksMax {
				break
			}
			delete(c.recvBuf, nxt)
			c.deliverPayloadLocked(cached)
		}
	case seqAfter(seq, expected) && seqDiff(seq, expected) < rudpRecvWindow:
		if _, dup := c.recvBuf[seq]; !dup {
			c.recvBuf[seq] = payload
		}
	default:
		// 旧包/超出乱序窗口：只 ACK 不交付
		c.recvMu.Unlock()
		c.sendAck()
		return
	}
	c.recvMu.Unlock()
	c.notify(c.dataNotify)

	c.sendAck()
	c.fecTryRecover(groupID)
}

// fecStoreLocked 登记 DATA 到 FEC 组并做窗口清理（须持 recvMu）。
func (c *rudpConn) fecStoreLocked(groupID, seq uint32, payload []byte) {
	g := c.fecGroups[groupID]
	if g == nil {
		g = map[uint32][]byte{}
		c.fecGroups[groupID] = g
	}
	g[seq] = payload
	if !c.fecHasMin || groupID < c.fecMinGroup {
		c.fecMinGroup = groupID
		c.fecHasMin = true
	}
	if len(c.fecGroups) > 20 {
		cutoff := c.fecMinGroup + 10
		for gid := range c.fecGroups {
			if gid < cutoff {
				delete(c.fecGroups, gid)
				delete(c.fecXor, gid)
				delete(c.fecLens, gid)
			}
		}
		if len(c.fecGroups) > 0 {
			minG := uint32(0xFFFFFFFF)
			for gid := range c.fecGroups {
				if gid < minG {
					minG = gid
				}
			}
			c.fecMinGroup = minG
		}
	}
}

// deliverPayloadLocked 收到完整 DATA 载荷后的统一投递口（须持 recvMu）：
// 非 mux 模式整块进主流；mux 模式按载荷首字节 chan 路由，chan0 进主流、其余进子通道。
// buffered/nextExpected 对每种载荷一律推进（模组 handleData 就是无条件计数），
// 因此 1 字节 EOF 也占一格预算，读侧读到才归还。
func (c *rudpConn) deliverPayloadLocked(payload []byte) {
	if !c.muxEnabled.Load() {
		c.chunks = append(c.chunks, payload)
		c.buffered++
		c.nextExpected.Add(1)
		return
	}
	if len(payload) == 0 {
		c.buffered++
		c.nextExpected.Add(1)
		return
	}
	ch := int(payload[0])
	if len(payload) == 1 {
		// 载荷只有 chan 字节 = 该子通道对端关闭（EOF）
		if sc, ok := c.subStreams[ch]; ok {
			sc.eof = true
		} else if ch == 0 {
			c.mainEof = true
		} else if ch >= 1 && ch <= rudpMuxEarlyEofMaxChan {
			// F14 早到 EOF 落位：对端先关而本端子通道还没注册（relay_setup 与 EOF 竞速）。
			// 不落位的话等待方会挂在传输级判死上——keepalive 照流、链路看着好好的，
			// 于是整条 mux 传输被一个死子通道拖到 24s/60s 才收敛，候选者自己的会话连坐。
			// >7 视为垃圾，不落位（不凭一个野字节凭空建通道）。
			c.subChannelLocked(ch).eof = true
		}
		c.buffered++
		c.nextExpected.Add(1)
		return
	}
	inner := payload[1:]
	if ch == 0 {
		c.chunks = append(c.chunks, inner)
	} else {
		sc := c.subChannelLocked(ch)
		sc.chunks = append(sc.chunks, inner)
	}
	c.buffered++
	c.nextExpected.Add(1)
}

// ---- 子通道流（mux 模式的中继腿端点）----

// read 子通道阻塞读（对齐模组 SubChannel.getInputStream().read）：
// 队列有字节先排干，之后才由 eof / 传输终止给出 EOF；无数据时每 500ms 复查一次。
// abort 关闭即退出（Java thread.interrupt 的 Go 形态，中继会话收尾用）。
func (s *rudpSubstream) read(p []byte, abort <-chan struct{}) (int, error) {
	if len(p) == 0 {
		return 0, nil
	}
	c := s.conn
	for {
		c.recvMu.Lock()
		if len(s.chunks) > 0 {
			chunk := s.chunks[0]
			n := copy(p, chunk)
			if n == len(chunk) {
				s.chunks = s.chunks[1:]
				if c.buffered > 0 {
					c.buffered--
				}
			} else {
				s.chunks[0] = chunk[n:]
			}
			c.recvMu.Unlock()
			return n, nil
		}
		eof := s.eof
		closed := c.closed.Load()
		c.recvMu.Unlock()
		if eof || closed {
			return 0, io.EOF
		}
		select {
		case <-abort:
			return 0, errRudpAborted
		case <-c.doneCh:
		case <-c.dataNotify:
		case <-time.After(500 * time.Millisecond):
		}
	}
}

// Read 实现 io.Reader（无 abort 通道形态）。
func (s *rudpSubstream) Read(p []byte) (int, error) { return s.read(p, nil) }

// Write 把字节作为本子通道的 DATA 写出（mux 分块前缀 chan 字节）。
func (s *rudpSubstream) Write(p []byte) (int, error) { return s.conn.writeChan(p, s.chanID) }

// Close 本端关闭子通道（发 1 字节 EOF），不动父传输。
func (s *rudpSubstream) Close() error {
	s.conn.closeSubstream(s.chanID)
	return nil
}

// fecTryRecover 收齐 3 个 DATA + XOR → 恢复缺失包（模组 tryFecRecovery 同款判定）。
func (c *rudpConn) fecTryRecover(groupID uint32) {
	c.recvMu.Lock()
	defer c.recvMu.Unlock()

	groupData := c.fecGroups[groupID]
	xorPayload := c.fecXor[groupID]
	origLens := c.fecLens[groupID]
	if groupData == nil || xorPayload == nil || origLens == nil {
		return
	}
	startSeq := groupID * rudpFecGroupSize
	missingSeq := uint32(0)
	missingIdx := -1
	received := 0
	for i := 0; i < rudpFecGroupSize; i++ {
		s := startSeq + uint32(i)
		if _, okd := groupData[s]; okd {
			received++
		} else if seqAfter(c.nextExpected.Load(), s) { // 已交付过的也算收到
			received++
		} else {
			missingSeq = s
			missingIdx = i
		}
	}
	switch {
	case received == rudpFecGroupSize-1 && missingIdx >= 0:
		recovered := append([]byte(nil), xorPayload...)
		for _, p := range groupData {
			n := len(p)
			if n > len(recovered) {
				n = len(recovered)
			}
			for i := 0; i < n; i++ {
				recovered[i] ^= p[i]
			}
		}
		origLen := len(recovered)
		if missingIdx < len(origLens) {
			origLen = origLens[missingIdx]
		}
		if origLen < len(recovered) {
			recovered = recovered[:origLen]
		}
		if c.buffered < rudpBufChunksMax {
			c.recvBuf[missingSeq] = recovered
			for {
				nxt := c.nextExpected.Load()
				cached, okc := c.recvBuf[nxt]
				if !okc || c.buffered >= rudpBufChunksMax {
					break
				}
				delete(c.recvBuf, nxt)
				c.deliverPayloadLocked(cached)
			}
		}
		delete(c.fecGroups, groupID)
		delete(c.fecXor, groupID)
		delete(c.fecLens, groupID)
	case received == rudpFecGroupSize:
		delete(c.fecGroups, groupID)
		delete(c.fecXor, groupID)
		delete(c.fecLens, groupID)
	}
}

func (c *rudpConn) handleFecXor(f rudpFrame) {
	c.recvMu.Lock()
	c.fecXor[f.Seq] = f.Payload
	c.fecLens[f.Seq] = f.FecLengths
	c.recvMu.Unlock()
	c.fecTryRecover(f.Seq)
}

func (c *rudpConn) sendAck() {
	c.sendFrame(rudpFrame{Type: rudpTypeAck, Ack: c.nextExpected.Load()})
}

// ---- 发送侧 ----

// processAck 累计 ACK：推进 oldestUnacked、更新 RTO、唤醒写侧。
func (c *rudpConn) processAck(ack uint32) {
	c.sendMu.Lock()
	if ack == c.lastAckSeq {
		c.dupAckCount++
		if c.dupAckCount >= 3 && len(c.pending) > 0 {
			if pp, okp := c.pending[c.oldestUnacked]; okp {
				pp.sendTime = time.Now()
				pp.retries++
				c.sendDataPacket(c.oldestUnacked, pp.data, false)
			}
			c.dupAckCount = 0
		}
	} else {
		c.lastAckSeq = ack
		c.dupAckCount = 0
	}

	for len(c.pending) > 0 && seqAfter(ack, c.oldestUnacked) {
		if pp, okp := c.pending[c.oldestUnacked]; okp {
			if pp.retries == 0 {
				c.updateRtoLocked(time.Since(pp.sendTime).Milliseconds())
			}
			delete(c.pending, c.oldestUnacked)
		}
		c.oldestUnacked++
		c.failures = 0
		c.degraded = false
	}

	// 救援完成：累计 ack 越过救援段末尾 = 整段已被接收，停止救援重传
	if len(c.rescue) > 0 && c.rescueLastSeq >= 0 && seqAfter(ack, uint32(c.rescueLastSeq)) {
		clear(c.rescue)
		c.rlog("info", "rescue queue fully acked, stop rescue retransmit")
	}

	c.sendMu.Unlock()
	c.notify(c.ackNotify)
}

// updateRtoLocked RFC6298（夹 [100,800]ms，对齐模组 updateRto）。
func (c *rudpConn) updateRtoLocked(sampleMs int64) {
	if sampleMs <= 0 {
		return
	}
	s := float64(sampleMs)
	if c.srtt <= 0 {
		c.srtt = s
		c.rttvar = s / 2
	} else {
		c.rttvar = (3*c.rttvar + absF(c.srtt-s)) / 4
		c.srtt = (7*c.srtt + s) / 8
	}
	rto := int(c.srtt + maxF(10, 4*c.rttvar))
	if rto < rudpRtoMinMs {
		rto = rudpRtoMinMs
	}
	if rto > rudpRtoMaxMs {
		rto = rudpRtoMaxMs
	}
	c.rtoMs = rto
}

func absF(v float64) float64 {
	if v < 0 {
		return -v
	}
	return v
}

func maxF(a, b float64) float64 {
	if a > b {
		return a
	}
	return b
}

func (c *rudpConn) resetFailures() {
	c.sendMu.Lock()
	c.failures = 0
	c.degraded = false
	c.sendMu.Unlock()
}

// sendDataPacket 编码并发送 DATA；小包首发 50ms 后复制重发一次（非重传路径）。
func (c *rudpConn) sendDataPacket(seq uint32, payload []byte, first bool) {
	c.sendFrame(rudpFrame{
		Type:    rudpTypeData,
		Seq:     seq,
		Ack:     c.nextExpected.Load(),
		Payload: payload,
	})
	if first && len(payload) < rudpSmallThresh && !c.degraded {
		dup := append([]byte(nil), payload...)
		time.AfterFunc(rudpSmallDupDelay, func() {
			if c.closed.Load() {
				return
			}
			c.sendFrame(rudpFrame{
				Type: rudpTypeData, Seq: seq, Ack: c.nextExpected.Load(), Payload: dup,
			})
		})
	}
}

// computeXorPayload 多包异或（对齐模组 computeXorPayload：按最长长度逐字节异或）。
func computeXorPayload(payloads [][]byte) []byte {
	maxLen := 0
	for _, p := range payloads {
		if len(p) > maxLen {
			maxLen = len(p)
		}
	}
	xor := make([]byte, maxLen)
	for _, p := range payloads {
		for i := 0; i < len(p); i++ {
			xor[i] ^= p[i]
		}
	}
	return xor
}

// sendFecFrame FEC_XOR 帧字节布局照模组 sendFecPacket（820-842 行）。
func (c *rudpConn) sendFecFrame(groupID uint32, xorPayload []byte, lengths []int) {
	c.sendFrame(rudpFrame{
		Type:       rudpTypeFecXor,
		Seq:        groupID,
		Payload:    xorPayload,
		FecCount:   len(lengths),
		FecLengths: lengths,
	})
}

// maxSendChunk 当前路径的发送分块上限：TURN 包封路径要给信封让出字节，否则节点按 1400 静默丢。
func (c *rudpConn) maxSendChunk() int {
	if c.codec != nil {
		return rudpTurnMaxChunk
	}
	return rudpMaxPayload
}

// Write 实现 io.Writer：按当前路径上限分片写入隧道（mux 模式下即 chan 0 的流）。
func (c *rudpConn) Write(p []byte) (int, error) {
	return c.writeChan(p, 0)
}

// writeChan 分片写入：mux 模式每个分片载荷前缀 1 字节 chan，非 mux 模式 chan 忽略
// （线上格式与历史版本逐字节一致）。分片长度仍按 currentMaxSendChunk 计算，
// chan 字节缀在分片之后（模组 sendBytes:1279-1289），TURN 路径因此上限仍是 1374 数据字节。
func (c *rudpConn) writeChan(p []byte, ch int) (int, error) {
	if !c.started.Load() || c.closed.Load() {
		return 0, errRudpClosed
	}
	written := 0
	for written < len(p) {
		end := written + c.maxSendChunk()
		if end > len(p) {
			end = len(p)
		}
		chunk := p[written:end]
		if c.muxEnabled.Load() {
			withChan := make([]byte, 0, len(chunk)+1)
			withChan = append(withChan, byte(ch))
			withChan = append(withChan, chunk...)
			chunk = withChan
		}
		if err := c.writeChunk(chunk); err != nil {
			return written, err
		}
		written = end
	}
	return written, nil
}

// writeChunk 发送一个分片：窗口控制 → 登记 pending → 发 DATA → FEC 组管理。
func (c *rudpConn) writeChunk(chunk []byte) error {
	c.sendMu.Lock()
	defer c.sendMu.Unlock()

	stuckStart := time.Time{}
	var stuckBase uint32
	for seqDiff(c.nextSeq, c.oldestUnacked) >= uint32(c.window) {
		if c.closed.Load() {
			return errRudpClosed
		}
		c.sendMu.Unlock()
		select {
		case <-c.doneCh:
			c.sendMu.Lock()
			return errRudpClosed
		case <-c.ackNotify:
		case <-time.After(time.Second):
		}
		c.sendMu.Lock()
		if c.closed.Load() {
			return errRudpClosed
		}
		if stuckStart.IsZero() || c.oldestUnacked != stuckBase {
			stuckStart = time.Now()
			stuckBase = c.oldestUnacked
		} else if time.Since(stuckStart) >= rudpStuckWriteLimit {
			go c.closeWith("传输停滞")
			return errRudpStuck
		}
	}

	seq := c.nextSeq
	c.nextSeq++
	data := append([]byte(nil), chunk...)
	c.pending[seq] = &rudpPending{data: data, sendTime: time.Now()}
	c.sendDataPacket(seq, data, true)

	// FEC 发送组：组满 4 包补发一个 XOR（模组默认开 FEC，组内同步发送保证帧序）
	groupID := int64(seq / rudpFecGroupSize)
	if groupID != c.fecSendGroupID {
		c.fecSendGroup = c.fecSendGroup[:0]
		c.fecSendGroupID = groupID
	}
	c.fecSendGroup = append(c.fecSendGroup, data)
	if len(c.fecSendGroup) == rudpFecGroupSize {
		group := make([][]byte, rudpFecGroupSize)
		copy(group, c.fecSendGroup)
		lengths := make([]int, rudpFecGroupSize)
		for i, g := range group {
			lengths[i] = len(g)
		}
		c.fecSendGroup = c.fecSendGroup[:0]
		c.sendFecFrame(uint32(groupID), computeXorPayload(group), lengths)
	}
	return nil
}

// tickLoop 重传/降级/判死循环（50ms）。
func (c *rudpConn) tickLoop() {
	t := time.NewTicker(rudpTick)
	defer t.Stop()
	for {
		select {
		case <-c.doneCh:
			return
		case <-t.C:
		}
		c.retransmitCheck()
	}
}

func (c *rudpConn) retransmitCheck() {
	if c.closed.Load() {
		return
	}
	now := time.Now()
	nowMs := now.UnixMilli()
	silenceMs := nowMs - c.lastRecv.Load()

	c.sendMu.Lock()
	defer c.sendMu.Unlock()

	if !c.degraded && len(c.pending) > 0 && c.failures >= 5 && silenceMs > rudpSilenceDegrade.Milliseconds() {
		c.degraded = true
	}

	// 两条判死先于重传块（模组 retransmitCheck 顶层顺序）：60s 硬超 → pending 非空且 24s 静默。
	// 救援期间 pending 已被清空，24s 那条永不触发——这正是"慢速重传不被判死打断"的根据；
	// 把救援并进 pending 会立刻破坏该性质。
	if silenceMs > rudpSilenceHard.Milliseconds() {
		c.closeWith("静默超60s")
		return
	}
	if len(c.pending) > 0 && silenceMs > rudpSilenceDead.Milliseconds() {
		c.closeWith("静默超24s且有待确认包，判对端已死")
		return
	}

	maxRetrans := 24000 / max(c.rtoMs, rudpRtoMinMs)
	maxRetrans = min(maxRetrans, 120)
	maxRetrans = max(maxRetrans, 10)

	// 熔断锚点：本段连续重传期间只要收到过对端任何包（lastRecv 前移）就整体复位，
	// 只有全程零收包的死路径才会持续累积轮次
	if rx := c.lastRecv.Load(); rx > c.deadPeerStreakStart {
		c.deadPeerStreakStart = rx
		c.deadPeerRounds = 0
	}

	didRetransmit := false
	for seq, pp := range c.pending {
		backoff := time.Duration(c.rtoMs+min(pp.retries, 3)*rudpBackoffMs) * time.Millisecond
		if now.Sub(pp.sendTime) <= backoff {
			continue
		}
		if pp.retries >= maxRetrans {
			c.closeWith(fmt.Sprintf("seq %d 重传超限", seq))
			return
		}
		pp.sendTime = now
		pp.retries++
		c.failures++
		c.sendDataPacket(seq, pp.data, false)
		didRetransmit = true
	}

	if didRetransmit {
		c.deadPeerRounds++
		if silenceMs > rudpDeadPeerBreakerMinSilence.Milliseconds() && c.deadPeerRounds >= rudpDeadPeerBreakerRounds {
			c.rlog("warn", "Peer unresponsive (%dms no rx, %d retransmits), rescuing %d pending packets",
				silenceMs, c.deadPeerRounds, len(c.pending))
			// 熔断清窗但不丢数据：积压整体转入救援队列
			clear(c.rescue)
			for seq, pp := range c.pending {
				c.rescue[seq] = pp
			}
			c.rescueLastSeq = int64(c.nextSeq) - 1
			c.rescueRounds = 0
			c.rescueLastResend = 0
			// 清空后必须把基序号推进到 nextSeq，否则 writeChunk 的窗口判定永远阻塞，
			// 写侧 30s 后必然 transport stuck
			clear(c.pending)
			c.oldestUnacked = c.nextSeq
			c.failures = 0
			c.dupAckCount = 0
			c.deadPeerRounds = 0
			c.deadPeerStreakStart = 0
		}
	}

	// 救援慢速重传：与上面的 didRetransmit 块同级、必须在它外面。熔断已把 pending 清空，
	// 下一拍 didRetransmit 恒 false，写在里面轮次 2..8 就是死代码（pcap 行为测试实证）、救援只剩一次机会。
	// rescueLastResend 置 0 使首轮随熔断当拍即发，此后每 6s 一轮，直到 ack 覆盖救援段末尾或 8 轮耗尽。
	if len(c.rescue) > 0 && c.rescueRounds < rudpRescueMaxRounds &&
		nowMs-c.rescueLastResend >= rudpRescueInterval.Milliseconds() {
		c.rescueLastResend = nowMs
		c.rescueRounds++
		// 沿用原 seq 按序重发：不入 pending、不推进 nextSeq，故不干扰正常序号空间也不产生新积压
		for _, seq := range sortedU32(c.rescue) {
			c.sendDataPacket(seq, c.rescue[seq].data, false)
		}
		c.rlog("info", "rescue retransmit round %d (%d packets)", c.rescueRounds, len(c.rescue))
	}
}

// sortedU32 救援队列按键升序（模组用 ConcurrentSkipListMap，投递顺序即序号顺序）
func sortedU32(m map[uint32]*rudpPending) []uint32 {
	keys := make([]uint32, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	sort.Slice(keys, func(i, j int) bool { return keys[i] < keys[j] })
	return keys
}

// sendKeepalive 发一帧 KEEPALIVE（模组 sendKeepalive:1121-1134）：11B 裸帧、type=8、
// seq=0、ack=nextExpectedSeq（:1127-1128），认证与路径包封走 sendFrame 统一出口
// （对应 :1129-1130 的 finalizeFrame + sendOnPrimary）。
// 守卫同 :1122 的 running && connected —— 本端 started 即 connected 位、closed 即 running 落。
func (c *rudpConn) sendKeepalive() {
	if !c.started.Load() || c.closed.Load() {
		return
	}
	c.sendFrame(rudpFrame{Type: rudpTypeKeepalive, Ack: c.nextExpected.Load()})
}

// sendKeepaliveFrame 对外的保活入口（模组 sendKeepaliveFrame:443-446："热备线路保活用，
// 不改变连接状态机"；调用点是 turn_bg.go 的 15s/15s 热备定时器，CM:12487）。
func (c *rudpConn) sendKeepaliveFrame() { c.sendKeepalive() }

// heartbeatTick 心跳拍：连续 rudpHeartbeatFailLimit 拍零入站判"单向死"
// （模组 heartbeatTick:1136-1157，逐句对照；拍距与调度见 RUDP:430、:48）。
// 判据只看"这一拍距内 lastRecv 有没有前移"（:1143）：前移就把轮次清零并认定这条道
// 曾经双向通过（:1144-1145），否则轮次 +1（:1146 的 ++streak）。
func (c *rudpConn) heartbeatTick() {
	if !c.started.Load() || c.closed.Load() {
		return // :1138-1140 !running || !connected
	}
	nowMs := time.Now().UnixMilli() // :1142
	if c.lastRecv.Load() > c.lastHeartbeatMs {
		// :1144-1145 复位两样：失败轮次 + "曾收到过"标记
		c.heartbeatFailStreak.Store(0)
		c.heartbeatEverRx = true
	} else if c.heartbeatEverRx && c.heartbeatFailStreak.Add(1) >= rudpHeartbeatFailLimit {
		// :1146 的 heartbeatEverRx 是必要闸门：从未收进过一个字节的（刚建、或纯单向可发）
		// 不在这里判死，交给 retransmitCheck 的 24s/60s，免得 5s 就掐掉还没起流的合法链路。
		c.rlog("warn", "One-way dead: %d heartbeats no rx, restart & re-punch", c.heartbeatFailStreak.Load())
		// 模组次序 :1147-1151 —— WARN → onLinkDead → sendRestart(:1149) →
		// triggerIceRestart(:1150) → close(:1151)，随后 return(:1152) 不再发这一拍的 keepalive。
		// 本端 onLinkDead 按 reason 分流收在 closeWith 里（见 rudpReasonIsDead），而 closeWith
		// 必须最后调：它一进来就置 closed，会让 sendRestart 的 sendFrame 静默不发这一帧。
		// 差出来的只是上报相对发包的位置，两者互不依赖。
		c.sendRestart()
		c.triggerIceRestart()
		c.closeWith(rudpReasonOneWayDead)
		return
	}

	// 活着的这一拍：把锚点推到本拍起点（:1155），再发 KEEPALIVE（:1156）
	c.lastHeartbeatMs = nowMs
	c.sendKeepalive()
}

// keepaliveLoop 1s 心跳拍循环。模组挂在 keepaliveTask 上的不是裸发 keepalive，
// 而是 heartbeatTick（RUDP:430），保活与单向死判定同一拍、先判死后发（:1142-1156），
// 所以这里只当时钟。
func (c *rudpConn) keepaliveLoop() {
	t := time.NewTicker(rudpKeepaliveEvery)
	defer t.Stop()
	for {
		select {
		case <-c.doneCh:
			return
		case <-t.C:
			c.heartbeatTick()
		}
	}
}

// Read 实现 io.Reader：阻塞读取按序到达的载荷块（mux 模式下即 chan 0 的自有流）。
// 连接关闭、或收到 chan0 的 1 字节 EOF 后队列排空即返回 io.EOF。
func (c *rudpConn) Read(p []byte) (int, error) { return c.readAbort(p, nil) }

// readAbort 同 Read，但 abort 通道关闭即刻退出阻塞读（中继会话收尾用）。
func (c *rudpConn) readAbort(p []byte, abort <-chan struct{}) (int, error) {
	if len(p) == 0 {
		return 0, nil
	}
	for {
		c.recvMu.Lock()
		if len(c.chunks) > 0 {
			chunk := c.chunks[0]
			n := copy(p, chunk)
			if n == len(chunk) {
				c.chunks = c.chunks[1:]
				if c.buffered > 0 {
					c.buffered--
				}
			} else {
				c.chunks[0] = chunk[n:]
			}
			c.recvMu.Unlock()
			return n, nil
		}
		eof := c.mainEof
		closed := c.closed.Load()
		c.recvMu.Unlock()
		if eof || closed {
			return 0, io.EOF
		}
		select {
		case <-abort:
			return 0, errRudpAborted
		case <-c.doneCh:
		case <-c.dataNotify:
		case <-time.After(500 * time.Millisecond):
		}
	}
}
