package main

// overlay.go — P2P 覆盖网链式转发（逐句对齐模组 network/P2POverlayManager.java，1.2.0，779 行）。
//
// 线上数据报格式（:748-753 framePacket / :700-727 decompress）：
//
//	[4 字节大端 payloadLen][gzip(JSON)]
//
// JSON 报文用 Gson 的紧凑形式序列化，成员按插入顺序 —— overlayAuthV1 的 MAC 覆盖的正是
// "去掉 mac 字段之后的那段字节"（:237-285），所以这里自带一个保序 JSON（ovObject），
// 绝不能用 map[string]any：Go 的 map 序列化按字典序，与 Gson 的插入序不一致，MAC 必然对不上，
// 转发时也会改变线上字节。
//
// 报文类型：handshake / ping / pong / data_relay（:375-391）。
// 角色：NONE / CHAIN_HEAD / CHAIN_MIDDLE / CHAIN_TAIL（:773-778）。
// 保活：每 2s 一拍，向上下游各发一个 ping（seq 共享自增计数器，dir 区分 up/down，:579-635）；
// 连续 5 拍无 pong → onLinkLost("upstream_timeout"/"downstream_timeout")。
// 去重：data_relay 以 "from:seq" 记窗口（上限 1000 条、60s 过期、单源占比上限 400 条），
// 三处安全修复（PER_SOURCE_SEEN_CAP :45/:478、解压上限 :47/:712、握手来源约束 :398-432）全部保留。
//
// 与模组的既有事实（不是本端的取舍）：sendPacket（:637）与 signPacket（:237）、
// setLinkKeyForPeer（:223）在 28 个模板里都没有调用点 —— 模组只验签、不注入密钥、
// 不主动发起 data_relay，data_relay 只做转发。本端照此实现，接线见交付报告。

import (
	"bytes"
	"compress/gzip"
	"crypto/hmac"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// ---- 常量表（file:line 全部指 P2POverlayManager.java） ----

const (
	overlayMaxPacketSize      = 32768                   // :39 MAX_PACKET_SIZE（模组声明但无消费点，保留以对齐）
	overlayReadBufferSize     = 65536                   // :40 READ_BUFFER_SIZE（:327 readLoop 缓冲）
	overlayPingInterval       = 2 * time.Second         // :41 PING_INTERVAL_SEC=2（:133 scheduleAtFixedRate(2,2,SECONDS)）
	overlayMaxPingFailures    = 5                       // :42 MAX_PING_FAILURES（:586/:613 以字面量 5 判定）
	overlayMaxSeenSeqSize     = 1000                    // :43 MAX_SEEN_SEQ_SIZE（:484-497 以字面量 1000 判定）
	overlayPerSourceSeenCap   = 400                     // :45 PER_SOURCE_SEEN_CAP（安全修复：单源占窗上限）
	overlayMaxDecompressedLen = 256 * 1024              // :47 MAX_DECOMPRESSED_BYTES（安全修复：解压放大上限）
	overlaySeenCutoffMs       = int64(60000)            // :485 窗口清理：早于 now-60s 的条目先淘汰
	overlayReadTimeout        = 1000 * time.Millisecond // :90 setSoTimeout(1000)
	overlayIOThreads          = 2                       // :97 newFixedThreadPool(2)
	overlayAuthMacBytes       = 8                       // :249 取 HMAC-SHA256 前 8 字节
	overlayAuthDropLogEvery   = 50                      // :368 drops==1 || drops%50==0 才 WARN
	overlayGzipReadChunk      = 8192                    // :732 readBounded 的读块大小
)

// overlaySeenEvictCutoff 是 :485 的 cutoff 表达式，单独命名只为让 handleDataRelay 的三步
// 淘汰流程读起来与 Java 一致（数值同 overlaySeenCutoffMs）。
const overlaySeenEvictCutoff = overlaySeenCutoffMs

// ---- 角色（:773-778 Role 枚举，顺序一致） ----

type overlayRole int

const (
	overlayRoleNone overlayRole = iota
	overlayRoleChainHead
	overlayRoleChainMiddle
	overlayRoleChainTail
)

func (r overlayRole) String() string {
	switch r {
	case overlayRoleChainHead:
		return "CHAIN_HEAD"
	case overlayRoleChainMiddle:
		return "CHAIN_MIDDLE"
	case overlayRoleChainTail:
		return "CHAIN_TAIL"
	}
	return "NONE"
}

// ---- 保序 JSON（Gson JsonObject 的等价物） ----

// ovObject 保序 JSON 对象：keys 记录插入顺序，vals 存值。
// 值域：string / json.Number（原样字面量，等价 Gson 的 LazilyParsedNumber）/ bool / nil /
// *ovObject / []any。
type ovObject struct {
	keys []string
	vals map[string]any
}

func newOVObject() *ovObject {
	return &ovObject{vals: map[string]any{}}
}

func (o *ovObject) has(key string) bool {
	if o == nil {
		return false
	}
	_, ok := o.vals[key]
	return ok
}

// isJsonNull 复刻 JsonObject 成员的 isJsonNull()（JSON null）。
func ovIsJSONNull(v any) bool { return v == nil }

func (o *ovObject) get(key string) any {
	if o == nil {
		return nil
	}
	return o.vals[key]
}

func (o *ovObject) put(key string, v any) {
	if _, ok := o.vals[key]; !ok {
		o.keys = append(o.keys, key)
	}
	o.vals[key] = v
}

func (o *ovObject) putString(key, v string) { o.put(key, v) }

func (o *ovObject) putBool(key string, v bool) { o.put(key, v) }

// putInt 用十进制字面量存数字（Gson addProperty(String, Number) 的序列化结果一致）。
func (o *ovObject) putInt(key string, v int) { o.put(key, json.Number(strconv.Itoa(v))) }

// remove 复刻 JsonObject.remove(key)（成员消失，其余顺序不变）。
func (o *ovObject) remove(key string) {
	if _, ok := o.vals[key]; !ok {
		return
	}
	delete(o.vals, key)
	for i, k := range o.keys {
		if k == key {
			o.keys = append(o.keys[:i], o.keys[i+1:]...)
			return
		}
	}
}

// ovString 复刻 Gson 的 getAsString：原始串/数字字面量/布尔文本；非原始类型返回 ok=false
// （模组侧抛 UnsupportedOperationException，被 processPacket 的 try/catch 吞掉＝丢包）。
func ovString(v any) (string, bool) {
	switch t := v.(type) {
	case string:
		return t, true
	case json.Number:
		return t.String(), true
	case bool:
		if t {
			return "true", true
		}
		return "false", true
	}
	return "", false
}

// ovInt 复刻 JsonPrimitive.getAsInt（Gson 走 Number.intValue()，等价于按 double 截断）。
// 模组遇到非数字成员会抛异常并被 processPacket 的 try/catch 吞掉＝整包丢弃；
// 本端取值器一律给默认值（差异已在交付报告中说明，不改线上报文）。
func ovInt(v any, def int) int {
	switch t := v.(type) {
	case json.Number:
		f, err := t.Float64()
		if err != nil {
			return def
		}
		return int(f)
	case string:
		if i, err := strconv.Atoi(t); err == nil {
			return i
		}
		return def
	}
	return def
}

// ovParseObject 解析线上 JSON 为保序对象（顶层非对象时报错＝模组 cast 失败＝丢包）。
func ovParseObject(data []byte) (*ovObject, error) {
	dec := json.NewDecoder(bytes.NewReader(data))
	dec.UseNumber()
	tok, err := dec.Token()
	if err != nil {
		return nil, err
	}
	d, ok := tok.(json.Delim)
	if !ok || d != '{' {
		return nil, errors.New("overlay: JSON 顶层不是对象")
	}
	return ovParseObjectBody(dec)
}

func ovParseObjectBody(dec *json.Decoder) (*ovObject, error) {
	o := newOVObject()
	for dec.More() {
		kt, err := dec.Token()
		if err != nil {
			return nil, err
		}
		key, ok := kt.(string)
		if !ok {
			return nil, errors.New("overlay: 成员名不是字符串")
		}
		v, err := ovParseValue(dec)
		if err != nil {
			return nil, err
		}
		o.put(key, v)
	}
	if _, err := dec.Token(); err != nil {
		return nil, err
	}
	return o, nil
}

func ovParseValue(dec *json.Decoder) (any, error) {
	tok, err := dec.Token()
	if err != nil {
		return nil, err
	}
	if d, ok := tok.(json.Delim); ok {
		switch d {
		case '{':
			return ovParseObjectBody(dec)
		case '[':
			arr := []any{}
			for dec.More() {
				v, err := ovParseValue(dec)
				if err != nil {
					return nil, err
				}
				arr = append(arr, v)
			}
			if _, err := dec.Token(); err != nil {
				return nil, err
			}
			return arr, nil
		default:
			return nil, errors.New("overlay: 非法定界符")
		}
	}
	return tok, nil
}

// toMap 把保序对象摊平成 app 侧惯用的 map[string]any（仅交给消费者，不参与线上序列化）。
func (o *ovObject) toMap() map[string]any {
	out := make(map[string]any, len(o.keys))
	for _, key := range o.keys {
		out[key] = ovToPlain(o.vals[key])
	}
	return out
}

func ovToPlain(v any) any {
	switch t := v.(type) {
	case *ovObject:
		return t.toMap()
	case json.Number:
		if f, err := t.Float64(); err == nil {
			return f
		}
		return t.String()
	case []any:
		out := make([]any, 0, len(t))
		for _, item := range t {
			out = append(out, ovToPlain(item))
		}
		return out
	}
	return v
}

// marshalGson 复刻 new Gson().toJson(JsonObject)：紧凑、插入顺序、HTML-safe 转义
// （Gson 默认把 & ' = < > 写成 \u00xx，这是 overlayAuthV1 签名字节的一部分）。
func (o *ovObject) marshalGson() []byte {
	var sb strings.Builder
	ovWriteObject(&sb, o)
	return []byte(sb.String())
}

func ovWriteObject(sb *strings.Builder, o *ovObject) {
	sb.WriteByte('{')
	for i, key := range o.keys {
		if i > 0 {
			sb.WriteByte(',')
		}
		sb.WriteString(ovQuote(key))
		sb.WriteByte(':')
		ovWriteValue(sb, o.vals[key])
	}
	sb.WriteByte('}')
}

func ovWriteValue(sb *strings.Builder, v any) {
	switch t := v.(type) {
	case nil:
		sb.WriteString("null")
	case bool:
		if t {
			sb.WriteString("true")
		} else {
			sb.WriteString("false")
		}
	case json.Number:
		sb.WriteString(t.String())
	case string:
		sb.WriteString(ovQuote(t))
	case *ovObject:
		ovWriteObject(sb, t)
	case []any:
		sb.WriteByte('[')
		for i, item := range t {
			if i > 0 {
				sb.WriteByte(',')
			}
			ovWriteValue(sb, item)
		}
		sb.WriteByte(']')
	}
}

// ovQuote Gson 的字符串写出规则：HTML-safe 五个字符 + 标准转义 + 控制字符 \u00xx。
func ovQuote(s string) string {
	var b strings.Builder
	b.Grow(len(s) + 2)
	b.WriteByte('"')
	for _, r := range s {
		switch r {
		case '&':
			b.WriteString(`\u0026`)
		case '\'':
			b.WriteString(`\u0027`)
		case '=':
			b.WriteString(`\u003d`)
		case '<':
			b.WriteString(`\u003c`)
		case '>':
			b.WriteString(`\u003e`)
		case '"':
			b.WriteString(`\"`)
		case '\\':
			b.WriteString(`\\`)
		default:
			if r < 0x20 {
				fmt.Fprintf(&b, "\\u%04x", r)
			} else {
				b.WriteRune(r)
			}
		}
	}
	b.WriteByte('"')
	return b.String()
}

// ---- 处理器接口（:755-761 PacketHandler） ----

type overlayPacketHandler interface {
	onDataReceived(from, priority string, payload *ovObject)
	onLinkReady()
	onLinkLost(reason string)
}

// ---- 待回 ping（:763-771 PendingPing） ----

type ovPendingPing struct {
	seq       int32
	timestamp int64
}

type ovPacketJob struct {
	data []byte
	from *net.UDPAddr
}

// ---- 覆盖网管理器 ----

type overlayManager struct {
	logf func(level, msg string)

	nodeID    string // :55（setNodeId 可改）
	localPort int    // :56

	socket   *net.UDPConn
	socketMu sync.Mutex

	running atomic.Bool // :49
	role    atomic.Int32
	// :50-54 上下游地址（AtomicReference → atomic.Pointer）
	upstreamAddr   atomic.Pointer[net.UDPAddr]
	downstreamAddr atomic.Pointer[net.UDPAddr]

	mu           sync.Mutex // 保护 upstreamId/downstreamId/nodeId/handler
	upstreamID   string
	downstreamID string
	handler      overlayPacketHandler

	packetSeq atomic.Int32 // :57

	seenMu  sync.Mutex       // :58 seenSeq（ConcurrentHashMap，本端与清理流程需要整体锁）
	seenSeq map[string]int64 // key: "from:seq" → 首次见到的毫秒时间

	linkKeyMu sync.Mutex // :64 linkKeysByNode（nodeId → 密钥，Java 存已 init 的 Mac）
	linkKeys  map[string][]byte

	authDropCount atomic.Int32 // :65
	// :66 DECOMPRESS_DROP_COUNT 是 static：跨实例共享，本端同样用包级变量
	//（overlayDecompressDropCount）

	upstreamLatency        atomic.Int32                  // :70（-1 起）
	downstreamLatency      atomic.Int32                  // :71
	pendingUpstreamPing    atomic.Pointer[ovPendingPing] // :72
	pendingDownstreamPing  atomic.Pointer[ovPendingPing] // :73
	upstreamPingFailures   atomic.Int32                  // :74
	downstreamPingFailures atomic.Int32                  // :75

	done      chan struct{} // ioExecutor/pingScheduler 的停机信号（start 建、stop 关）
	doneMu    sync.Mutex
	queueMu   sync.Mutex
	queue     []*ovPacketJob
	queueWake chan struct{}
}

// takeDone 取出并清空停机信号（幂等：第二次调用返回 nil，不会重复 close）。
func (m *overlayManager) takeDone() chan struct{} {
	m.doneMu.Lock()
	defer m.doneMu.Unlock()
	d := m.done
	m.done = nil
	return d
}

func (m *overlayManager) currentDone() chan struct{} {
	m.doneMu.Lock()
	defer m.doneMu.Unlock()
	return m.done
}

// overlayDecompressDropCount 对应 :66 的 static AtomicInteger DECOMPRESS_DROP_COUNT。
var overlayDecompressDropCount atomic.Int32

// overlayNodeIDSalt 用于复刻 :78 的 "node_" + System.identityHashCode(this)：
// Java 取的是对象身份哈希（进程内唯一、无稳定语义），Go 没有等价物，用单调计数代替，
// 同样是"只要求唯一、不要求可预测"。差异已在交付报告中说明。
var overlayNodeIDSalt atomic.Int64

// newOverlayManager 对应 :77-80。nodeID 为空时用 "node_<计数>"（模组 :78 的兜底形态）。
func newOverlayManager(nodeID string, port int, logf func(level, msg string)) *overlayManager {
	id := nodeID
	if id == "" {
		id = "node_" + strconv.FormatInt(overlayNodeIDSalt.Add(1), 10)
	}
	m := &overlayManager{
		logf:      logf,
		nodeID:    id,
		localPort: port,
		seenSeq:   map[string]int64{},
		linkKeys:  map[string][]byte{},
	}
	// :70-71 latency 初值 -1（"尚未测得"），pollAndGetPeerLatency 只上报 >0 的值
	m.upstreamLatency.Store(-1)
	m.downstreamLatency.Store(-1)
	return m
}

func (m *overlayManager) rlog(level, format string, args ...any) {
	if m.logf == nil {
		return
	}
	m.logf(level, "[Overlay] "+fmt.Sprintf(format, args...))
}

func (m *overlayManager) currentHandler() overlayPacketHandler {
	m.mu.Lock()
	h := m.handler
	m.mu.Unlock()
	return h
}

func (m *overlayManager) currentNodeID() string {
	m.mu.Lock()
	id := m.nodeID
	m.mu.Unlock()
	return id
}

func (m *overlayManager) currentSocket() *net.UDPConn {
	m.socketMu.Lock()
	s := m.socket
	m.socketMu.Unlock()
	return s
}

// start 对应 :82-136：绑端口（wildcard，SO_REUSEADDR 语义见注释）→ 起 2 个处理 worker →
// running=true → 起收包循环 → 起 2s ping → 记日志。
func (m *overlayManager) start(handler overlayPacketHandler) error {
	if m.running.Load() {
		return nil
	}
	m.mu.Lock()
	m.handler = handler
	m.mu.Unlock()

	// 模组 :87-90 用 DatagramSocket(null)+setReuseAddress(true)+bind(wildcard:port)+soTimeout(1000)。
	// Go 的 ListenUDP 默认不带 SO_REUSEADDR（Windows 上表现为同端口重复绑定会失败），
	// 这里保持 stdlib 行为：绑定失败＝端口被占＝走下面的 bind failed 分支（与模组一致地
	// 抛 IOException，由 TopologyClient 记 warn 并保持直连）。
	sock, err := net.ListenUDP("udp", &net.UDPAddr{Port: m.localPort})
	if err != nil {
		m.rlog("error", "Overlay UDP port %d bind failed: %s", m.localPort, err.Error())
		return fmt.Errorf("failed to bind overlay socket: %w", err)
	}
	m.socketMu.Lock()
	m.socket = sock
	m.socketMu.Unlock()

	m.doneMu.Lock()
	done := make(chan struct{})
	m.done = done
	m.doneMu.Unlock()
	m.queueWake = make(chan struct{}, 1)
	m.running.Store(true)

	// ioExecutor：2 个 worker + 无界队列（Java newFixedThreadPool 的 LinkedBlockingQueue）。
	for i := 0; i < overlayIOThreads; i++ {
		go m.ioWorker(done)
	}
	go m.readLoop()
	go m.pingLoop()

	m.rlog("info", "P2P overlay started, port %d, node %s", m.getLocalPort(), m.currentNodeID())
	return nil
}

// connectUpstream 对应 :138-154。
func (m *overlayManager) connectUpstream(peerID, host string, port int) {
	if !m.running.Load() {
		return
	}
	if host == "" {
		return
	}
	addr := ovResolve(host, port)
	m.upstreamAddr.Store(addr)
	m.mu.Lock()
	m.upstreamID = peerID
	m.mu.Unlock()
	if m.downstreamAddr.Load() == nil {
		m.role.Store(int32(overlayRoleChainTail))
	} else {
		m.role.Store(int32(overlayRoleChainMiddle))
	}
	m.rlog("info", "Upstream connection: %s at %s:%d role %s", peerID, host, port, m.getRole())
	m.sendHandshake(peerID, addr)
}

// setDownstream 对应 :156-172。
func (m *overlayManager) setDownstream(peerID, host string, port int) {
	if !m.running.Load() {
		return
	}
	if host == "" {
		return
	}
	addr := ovResolve(host, port)
	m.downstreamAddr.Store(addr)
	m.mu.Lock()
	m.downstreamID = peerID
	m.mu.Unlock()
	m.rlog("info", "Downstream set: %s at %s:%d", peerID, host, port)
	role := m.getRole()
	if role != overlayRoleChainHead && role != overlayRoleChainMiddle {
		if m.upstreamAddr.Load() != nil {
			m.role.Store(int32(overlayRoleChainMiddle))
		} else {
			m.role.Store(int32(overlayRoleChainTail))
		}
	}
}

// becomeHead 对应 :174-191。
func (m *overlayManager) becomeHead(downstreamPeerID, downstreamHost string, downstreamPort int) {
	if !m.running.Load() {
		return
	}
	m.role.Store(int32(overlayRoleChainHead))
	m.upstreamAddr.Store(nil)
	m.mu.Lock()
	m.upstreamID = ""
	m.mu.Unlock()
	if downstreamPeerID != "" && downstreamHost != "" {
		addr := ovResolve(downstreamHost, downstreamPort)
		m.downstreamAddr.Store(addr)
		m.mu.Lock()
		m.downstreamID = downstreamPeerID
		m.mu.Unlock()
		m.sendHandshake(downstreamPeerID, addr)
	} else {
		m.downstreamAddr.Store(nil)
		m.mu.Lock()
		m.downstreamID = ""
		m.mu.Unlock()
	}
	m.rlog("info", "Became chain head, downstream: %s", ovOrNilString(downstreamPeerID))
}

// switchToDirectMode 对应 :193-200（不判 running）。
func (m *overlayManager) switchToDirectMode() {
	m.role.Store(int32(overlayRoleNone))
	m.upstreamAddr.Store(nil)
	m.downstreamAddr.Store(nil)
	m.mu.Lock()
	m.upstreamID = ""
	m.downstreamID = ""
	m.mu.Unlock()
	m.rlog("info", "Switched to direct mode")
}

func (m *overlayManager) getUpstreamLatency() int   { return int(m.upstreamLatency.Load()) }
func (m *overlayManager) getDownstreamLatency() int { return int(m.downstreamLatency.Load()) }
func (m *overlayManager) getRole() overlayRole      { return overlayRole(m.role.Load()) }

// setNodeId 对应 :214-216。
func (m *overlayManager) setNodeID(id string) {
	m.mu.Lock()
	m.nodeID = id
	m.mu.Unlock()
}

// setLinkKeyForPeer 对应 :223-234（overlayAuthV1：注入后该来源的报文必须带有效 mac）。
func (m *overlayManager) setLinkKeyForPeer(peerNodeID string, key []byte) {
	if peerNodeID == "" {
		return
	}
	if key == nil {
		m.linkKeyMu.Lock()
		delete(m.linkKeys, peerNodeID)
		m.linkKeyMu.Unlock()
		return
	}
	// 模组用 PunchAuth.createMac(key)（HmacSHA256 init），失败返回 null 即不登记。
	// SHA-256 的 HMAC 在 Go 侧不会失败，为保持"空密钥不登记"的形态仍做判空。
	if len(key) == 0 {
		return
	}
	mac := make([]byte, len(key))
	copy(mac, key)
	m.linkKeyMu.Lock()
	m.linkKeys[peerNodeID] = mac
	m.linkKeyMu.Unlock()
}

func (m *overlayManager) linkKeyFor(nodeID string) []byte {
	if nodeID == "" {
		return nil
	}
	m.linkKeyMu.Lock()
	key := m.linkKeys[nodeID]
	m.linkKeyMu.Unlock()
	return key
}

// overlayLinkMacHex 取 HMAC-SHA256 的前 8 字节并转小写 hex（:248-251）。
func overlayLinkMacHex(key, payload []byte) string {
	mac := hmac.New(sha256.New, key)
	mac.Write(payload)
	full := mac.Sum(nil)
	n := overlayAuthMacBytes
	if len(full) < n {
		n = len(full)
	}
	return hex.EncodeToString(full[:n])
}

// overlaySignPacket 对应 :237-256：给报文附加 overlayAuthV1 截断 MAC
// （对"不含 mac 字段"的规范 JSON 签名）。已带 mac 的报文不重复签。
func overlaySignPacket(packet *ovObject, key []byte) {
	if key == nil || packet.has("mac") {
		return
	}
	full := packet.marshalGson()
	packet.putString("mac", overlayLinkMacHex(key, full))
}

// overlayVerifyAndStripPacket 对应 :259-285：校验并剥离 mac 字段，false＝丢弃。
func overlayVerifyAndStripPacket(packet *ovObject, key []byte) bool {
	if key == nil {
		return true
	}
	var claimed string
	ok := false
	if packet.has("mac") {
		claimed, ok = ovString(packet.get("mac"))
	}
	if !ok {
		// 缺 mac 或 mac 不是原始类型（Java：claimed==null → false）
		return false
	}
	packet.remove("mac")
	expect := overlayLinkMacHex(key, packet.marshalGson())
	// 模组用 MessageDigest.isEqual 比较 ASCII 字节（常量时间，防 timing 侧信道）
	return hmacEqualASCII(expect, claimed)
}

// hmacEqualASCII MessageDigest.isEqual 的等价物：先比长度、再常量时间比字节。
func hmacEqualASCII(a, b string) bool {
	if len(a) != len(b) {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(a), []byte(b)) == 1
}

// stop 对应 :287-312。
func (m *overlayManager) stop() {
	m.running.Store(false)
	m.upstreamAddr.Store(nil)
	m.downstreamAddr.Store(nil)
	m.mu.Lock()
	m.upstreamID = ""
	m.downstreamID = ""
	m.mu.Unlock()

	if sock := m.currentSocket(); sock != nil {
		_ = sock.Close()
	}
	m.socketMu.Lock()
	m.socket = nil
	m.socketMu.Unlock()

	if done := m.takeDone(); done != nil {
		close(done)
	}

	m.role.Store(int32(overlayRoleNone))
	m.seenMu.Lock()
	m.seenSeq = map[string]int64{}
	m.seenMu.Unlock()
	m.linkKeyMu.Lock()
	m.linkKeys = map[string][]byte{}
	m.linkKeyMu.Unlock()
	m.rlog("info", "P2P overlay stopped")
}

// getLocalPort 对应 :314-324。
func (m *overlayManager) getLocalPort() int {
	sock := m.currentSocket()
	if sock != nil {
		if addr, ok := sock.LocalAddr().(*net.UDPAddr); ok && addr.Port != 0 {
			return addr.Port
		}
		return m.localPort
	}
	return m.localPort
}

// ---- 收包 ----

func (m *overlayManager) ioWorker(done chan struct{}) {
	for {
		m.queueMu.Lock()
		var job *ovPacketJob
		if len(m.queue) > 0 {
			job = m.queue[0]
			m.queue = m.queue[1:]
		}
		m.queueMu.Unlock()
		if job == nil {
			select {
			case <-done:
				return
			case <-m.queueWake:
				continue
			}
		}
		m.processPacket(job.data, job.from)
	}
}

// submit 入队（无界，同 Java 的 LinkedBlockingQueue），绝不阻塞收包循环。
func (m *overlayManager) submit(data []byte, from *net.UDPAddr) {
	m.queueMu.Lock()
	m.queue = append(m.queue, &ovPacketJob{data: data, from: from})
	m.queueMu.Unlock()
	select {
	case m.queueWake <- struct{}{}:
	default:
	}
}

// readLoop 对应 :326-348。
// 差异（仅日志）：Java 的 soTimeout 每秒抛一次 SocketTimeoutException，落到 :342 的
// catch(Exception) 打 debug —— Fabric 默认日志级别下不可见。本端 netLog 会把每一行
// 推给界面，1 行/秒的刷屏是实打实的行为退化，因此超时静默 continue，其它错误仍按
// 模组文案打 debug。
func (m *overlayManager) readLoop() {
	buf := make([]byte, overlayReadBufferSize)
	for m.running.Load() {
		sock := m.currentSocket()
		if sock == nil {
			return
		}
		_ = sock.SetReadDeadline(time.Now().Add(overlayReadTimeout))
		n, from, err := sock.ReadFromUDP(buf)
		if err != nil {
			if !m.running.Load() {
				return
			}
			var ne net.Error
			if errors.As(err, &ne) && ne.Timeout() {
				continue
			}
			m.rlog("debug", "Overlay read socket temp error: %s", err.Error())
			continue
		}
		data := make([]byte, n)
		copy(data, buf[:n])
		m.submit(data, from)
	}
}

// processPacket 对应 :350-395。
func (m *overlayManager) processPacket(data []byte, fromAddr *net.UDPAddr) {
	defer func() {
		// 模组的 try/catch 包住整段处理：任何异常=丢包（:392-394）
		if r := recover(); r != nil {
			m.rlog("debug", "Overlay packet process failed: %v", r)
		}
	}()

	if fromAddr == nil {
		return
	}
	jsonStr := overlayDecompress(data)
	if jsonStr == "" {
		return
	}
	packet, err := ovParseObject([]byte(jsonStr))
	if err != nil {
		m.rlog("debug", "Overlay packet process failed: %s", err.Error())
		return
	}
	typeStr, _ := ovString(packet.get("type"))
	fromStr, _ := ovString(packet.get("from"))

	// overlayAuthV1：已注入链路密钥的来源强制验签（失败丢弃并计数）；未配置来源保持旧行为
	linkKey := m.linkKeyFor(fromStr)
	if linkKey != nil && !overlayVerifyAndStripPacket(packet, linkKey) {
		drops := m.authDropCount.Add(1)
		if drops == 1 || drops%overlayAuthDropLogEvery == 0 {
			m.rlog("warn", "Overlay packet from configured peer %s failed MAC verification (drop #%d)", fromStr, drops)
		}
		return
	}

	switch typeStr {
	case "handshake":
		m.handleHandshake(packet, fromAddr)
	case "ping":
		m.handlePing(packet, fromStr, fromAddr)
	case "pong":
		m.handlePong(packet)
	case "data_relay":
		fromDir := m.determineDirection(fromAddr)
		m.handleDataRelay(packet, fromDir, fromAddr)
	default:
		m.rlog("debug", "Unknown overlay packet type: %s", typeStr)
	}
}

// handleHandshake 对应 :397-418。
func (m *overlayManager) handleHandshake(packet *ovObject, fromAddr *net.UDPAddr) {
	// 安全修复：握手来源约束（:398-406）——链路已配置对端时，只接受同一 IP 的握手。
	if !m.isKnownLinkAddress(fromAddr) {
		drops := m.authDropCount.Add(1)
		m.rlog("warn", "Rejected overlay handshake from unknown source %s (drop #%d)", fromAddr.String(), drops)
		return
	}
	peerID, _ := ovString(packet.get("from"))
	m.rlog("info", "Received downstream handshake: %s", peerID)
	role := m.getRole()
	if role == overlayRoleChainHead || role == overlayRoleChainMiddle {
		m.downstreamAddr.Store(fromAddr)
		m.mu.Lock()
		m.downstreamID = peerID
		m.mu.Unlock()
	}
	if h := m.currentHandler(); h != nil {
		h.onLinkReady()
	}
}

// isKnownLinkAddress 对应 :425-432（只校验 downstream 槽位：协议上握手只由下游发给上游）。
func (m *overlayManager) isKnownLinkAddress(fromAddr *net.UDPAddr) bool {
	if fromAddr == nil {
		return false
	}
	down := m.downstreamAddr.Load()
	if down == nil {
		return true
	}
	return ovSameHost(down, fromAddr)
}

// ovSameHost 对应 :434-436（比 IP，不比端口：NAT 重写端口允许漂移）。
func ovSameHost(a, b *net.UDPAddr) bool {
	if a == nil || b == nil {
		return false
	}
	if a.IP == nil || b.IP == nil {
		return false
	}
	return a.IP.Equal(b.IP)
}

// ovAddrEqual 对应 InetSocketAddress.equals（IP+端口）。
func ovAddrEqual(a, b *net.UDPAddr) bool {
	if a == nil || b == nil {
		return false
	}
	if a.Port != b.Port {
		return false
	}
	if (a.IP == nil) != (b.IP == nil) {
		return false
	}
	if a.IP == nil {
		return true
	}
	return a.IP.Equal(b.IP)
}

// handlePing 对应 :438-448。
func (m *overlayManager) handlePing(packet *ovObject, from string, senderAddr *net.UDPAddr) {
	pong := newOVObject()
	pong.putString("type", "pong")
	pong.putString("from", m.currentNodeID())
	seq := 0
	if packet.has("seq") {
		seq = ovInt(packet.get("seq"), 0)
	}
	pong.putInt("seq", seq)
	if packet.has("dir") {
		dir, _ := ovString(packet.get("dir"))
		pong.putString("dir", dir)
	}
	m.sendPacketTo(pong, senderAddr)
	_ = from // 模组也只用到 senderAddr（:447）
}

// handlePong 对应 :450-471。
func (m *overlayManager) handlePong(packet *ovObject) {
	now := time.Now().UnixMilli()
	pingSeq := int32(-1)
	if packet.has("seq") {
		pingSeq = int32(ovInt(packet.get("seq"), -1))
	}
	dir := "up"
	if packet.has("dir") {
		if s, ok := ovString(packet.get("dir")); ok {
			dir = s
		}
	}
	if dir == "down" {
		pending := m.pendingDownstreamPing.Load()
		if pending != nil && pingSeq == pending.seq {
			latency := int32(now - pending.timestamp)
			m.downstreamLatency.Store(latency)
			m.pendingDownstreamPing.CompareAndSwap(pending, nil)
			m.downstreamPingFailures.Store(0)
		}
		return
	}
	pending := m.pendingUpstreamPing.Load()
	if pending != nil && pingSeq == pending.seq {
		latency := int32(now - pending.timestamp)
		m.upstreamLatency.Store(latency)
		m.pendingUpstreamPing.CompareAndSwap(pending, nil)
		m.upstreamPingFailures.Store(0)
	}
}

// handleDataRelay 对应 :473-528。
func (m *overlayManager) handleDataRelay(packet *ovObject, fromDirection string, fromAddr *net.UDPAddr) {
	from, _ := ovString(packet.get("from"))
	seq := 0
	if packet.has("seq") {
		seq = ovInt(packet.get("seq"), 0)
	}
	// 安全修复：单源占比限制（:476-480）
	if m.countSeenForSource(from) >= overlayPerSourceSeenCap {
		return
	}

	dedupKey := from + ":" + strconv.Itoa(seq)
	now := time.Now().UnixMilli()
	m.seenMu.Lock()
	if _, dup := m.seenSeq[dedupKey]; dup {
		m.seenMu.Unlock()
		return
	}
	m.seenSeq[dedupKey] = now
	m.evictSeenLocked(now)
	m.seenMu.Unlock()

	to := ""
	toPresent := false
	if packet.has("to") && !ovIsJSONNull(packet.get("to")) {
		to, _ = ovString(packet.get("to"))
		toPresent = true
	}
	priority := "L2"
	if packet.has("priority") {
		priority, _ = ovString(packet.get("priority"))
	}
	payload := newOVObject()
	if packet.has("payload") {
		obj, ok := packet.get("payload").(*ovObject)
		if !ok {
			// 模组 getAsJsonObject 抛 UnsupportedOperationException → 整包丢弃
			return
		}
		payload = obj
	}

	nodeID := m.currentNodeID()
	if h := m.currentHandler(); h != nil && (!toPresent || to == nodeID) {
		h.onDataReceived(from, priority, payload)
	}

	if !toPresent || to != nodeID {
		switch fromDirection {
		case "upstream":
			m.forwardToDownstream(packet)
		case "downstream":
			m.forwardToUpstream(packet)
		default:
			up := m.upstreamAddr.Load()
			down := m.downstreamAddr.Load()
			if ovAddrEqual(up, fromAddr) {
				m.forwardToDownstream(packet)
			} else if ovAddrEqual(down, fromAddr) {
				m.forwardToUpstream(packet)
			}
		}
	}
}

// evictSeenLocked 对应 :484-503 的三步淘汰：先按 60s 过期删，够不到就按时间排序补删。
func (m *overlayManager) evictSeenLocked(now int64) {
	if len(m.seenSeq) <= overlayMaxSeenSeqSize {
		return
	}
	cutoff := now - overlaySeenEvictCutoff
	for k, v := range m.seenSeq {
		if len(m.seenSeq) <= overlayMaxSeenSeqSize {
			break
		}
		if v < cutoff {
			delete(m.seenSeq, k)
		}
	}
	if len(m.seenSeq) <= overlayMaxSeenSeqSize {
		return
	}
	type kv struct {
		key string
		ts  int64
	}
	sorted := make([]kv, 0, len(m.seenSeq))
	for k, v := range m.seenSeq {
		sorted = append(sorted, kv{k, v})
	}
	sort.Slice(sorted, func(i, j int) bool { return sorted[i].ts < sorted[j].ts })
	toRemove := len(m.seenSeq) - overlayMaxSeenSeqSize
	for i := 0; i < toRemove; i++ {
		delete(m.seenSeq, sorted[i].key)
	}
}

// countSeenForSource 对应 :530-546（前缀匹配 "from:"，够 400 即早退）。
func (m *overlayManager) countSeenForSource(from string) int {
	if from == "" {
		return 0
	}
	prefix := from + ":"
	count := 0
	m.seenMu.Lock()
	defer m.seenMu.Unlock()
	for key := range m.seenSeq {
		if strings.HasPrefix(key, prefix) {
			count++
			if count >= overlayPerSourceSeenCap {
				break
			}
		}
	}
	return count
}

func (m *overlayManager) forwardToDownstream(packet *ovObject) {
	if down := m.downstreamAddr.Load(); down != nil {
		m.sendPacketTo(packet, down)
	}
}

func (m *overlayManager) forwardToUpstream(packet *ovObject) {
	if up := m.upstreamAddr.Load(); up != nil {
		m.sendPacketTo(packet, up)
	}
}

// determineDirection 对应 :562-570。
func (m *overlayManager) determineDirection(fromAddr *net.UDPAddr) string {
	up := m.upstreamAddr.Load()
	down := m.downstreamAddr.Load()
	if up != nil && ovAddrEqual(up, fromAddr) {
		return "upstream"
	}
	if down != nil && ovAddrEqual(down, fromAddr) {
		return "downstream"
	}
	return "unknown"
}

// sendHandshake 对应 :572-577。
func (m *overlayManager) sendHandshake(peerID string, addr *net.UDPAddr) {
	handshake := newOVObject()
	handshake.putString("type", "handshake")
	handshake.putString("from", m.currentNodeID())
	m.sendPacketTo(handshake, addr)
	_ = peerID
}

// pingTask 对应 :579-635。
func (m *overlayManager) pingTask() {
	if !m.running.Load() || m.getRole() == overlayRoleNone {
		return
	}
	nodeID := m.currentNodeID()

	if up := m.upstreamAddr.Load(); up != nil {
		currentUp := m.pendingUpstreamPing.Load()
		if currentUp != nil {
			failures := m.upstreamPingFailures.Add(1)
			if failures >= overlayMaxPingFailures {
				m.rlog("warn", "Upstream link broken, %d consecutive ping failures", failures)
				m.upstreamPingFailures.Store(0)
				m.pendingUpstreamPing.CompareAndSwap(currentUp, nil)
				if h := m.currentHandler(); h != nil {
					h.onLinkLost("upstream_timeout")
				}
			}
		} else {
			m.upstreamPingFailures.Store(0)
		}
		seq := m.packetSeq.Add(1)
		m.pendingUpstreamPing.Store(&ovPendingPing{seq: seq, timestamp: time.Now().UnixMilli()})
		ping := newOVObject()
		ping.putString("type", "ping")
		ping.putString("from", nodeID)
		ping.putInt("seq", int(seq))
		ping.putString("dir", "up")
		m.sendPacketTo(ping, up)
	}

	if down := m.downstreamAddr.Load(); down != nil {
		currentDown := m.pendingDownstreamPing.Load()
		if currentDown != nil {
			failures := m.downstreamPingFailures.Add(1)
			if failures >= overlayMaxPingFailures {
				m.rlog("warn", "Downstream link broken, %d consecutive ping failures", failures)
				m.downstreamPingFailures.Store(0)
				m.pendingDownstreamPing.CompareAndSwap(currentDown, nil)
				if h := m.currentHandler(); h != nil {
					h.onLinkLost("downstream_timeout")
				}
			}
		} else {
			m.downstreamPingFailures.Store(0)
		}
		seq := m.packetSeq.Add(1)
		m.pendingDownstreamPing.Store(&ovPendingPing{seq: seq, timestamp: time.Now().UnixMilli()})
		ping := newOVObject()
		ping.putString("type", "ping")
		ping.putString("from", nodeID)
		ping.putInt("seq", int(seq))
		ping.putString("dir", "down")
		m.sendPacketTo(ping, down)
	}
}

func (m *overlayManager) pingLoop() {
	t := time.NewTicker(overlayPingInterval)
	defer t.Stop()
	for {
		select {
		case <-m.done:
			return
		case <-t.C:
			m.pingTask()
		}
	}
}

// sendPacket 对应 :637-657（按角色选路；模组当前没有调用点，保留以便上层接入）。
func (m *overlayManager) sendPacket(packet *ovObject) {
	switch m.getRole() {
	case overlayRoleChainHead:
		m.sendPacketToDownstream(packet)
	case overlayRoleChainTail:
		m.sendPacketToUpstream(packet)
	case overlayRoleChainMiddle:
		to := ""
		toPresent := false
		if packet.has("to") && !ovIsJSONNull(packet.get("to")) {
			to, _ = ovString(packet.get("to"))
			toPresent = true
		}
		m.mu.Lock()
		upID, downID := m.upstreamID, m.downstreamID
		m.mu.Unlock()
		switch {
		case !toPresent:
			m.sendPacketToUpstream(packet)
			m.sendPacketToDownstream(packet)
		case to == upID:
			m.sendPacketToUpstream(packet)
		case to == downID:
			m.sendPacketToDownstream(packet)
		default:
			m.sendPacketToUpstream(packet)
			m.sendPacketToDownstream(packet)
		}
	}
}

func (m *overlayManager) sendPacketToUpstream(packet *ovObject) {
	if up := m.upstreamAddr.Load(); up != nil {
		m.sendPacketTo(packet, up)
	}
}

func (m *overlayManager) sendPacketToDownstream(packet *ovObject) {
	if down := m.downstreamAddr.Load(); down != nil {
		m.sendPacketTo(packet, down)
	}
}

// sendPacketTo 对应 :673-683：JSON → gzip → 4 字节长度头 → UDP 发送；异常只记 debug。
func (m *overlayManager) sendPacketTo(packet *ovObject, addr *net.UDPAddr) {
	sock := m.currentSocket()
	if sock == nil || addr == nil {
		return
	}
	payload, err := overlayCompress(string(packet.marshalGson()))
	if err != nil {
		m.rlog("debug", "Send packet to %s failed: %s", addr.String(), err.Error())
		return
	}
	framed := overlayFramePacket(payload)
	if _, err := sock.WriteToUDP(framed, addr); err != nil {
		m.rlog("debug", "Send packet to %s failed: %s", addr.String(), err.Error())
	}
}

// ---- 帧编解码（:685-753） ----

func overlayCompress(data string) ([]byte, error) {
	var bos bytes.Buffer
	gw, err := gzip.NewWriterLevel(&bos, gzip.DefaultCompression)
	if err != nil {
		return nil, err
	}
	if _, err := gw.Write([]byte(data)); err != nil {
		_ = gw.Close()
		return nil, err
	}
	if err := gw.Close(); err != nil {
		return nil, err
	}
	return bos.Bytes(), nil
}

// overlayDecompress 对应 :700-727（长度头校验 + 有界解压，超限计数并丢包）。
func overlayDecompress(data []byte) string {
	if len(data) < 4 {
		return ""
	}
	payloadLen := int(int32(binary.BigEndian.Uint32(data[:4])))
	if payloadLen <= 0 || payloadLen > len(data)-4 {
		return ""
	}
	out, ok := overlayReadBounded(data[4:4+payloadLen], overlayMaxDecompressedLen)
	if !ok {
		drops := overlayDecompressDropCount.Add(1)
		overlayLogDecompressDrop(overlayMaxDecompressedLen, drops)
		return ""
	}
	return string(out)
}

func overlayLogDecompressDrop(limit int, drops int32) {
	// :715 用的是 LOGGER.warn（static logger）；本端管理器可能已停，直接走包级日志出口。
	if defaultOverlayLog != nil {
		defaultOverlayLog("warn", fmt.Sprintf("[Overlay] Overlay packet decompressed size exceeded %d bytes, dropped (drop #%d)", limit, drops))
	}
}

// defaultOverlayLog 由持有 overlayManager 的一方挂上（模组这里是 static LOGGER，
// 本端要接进 app 的网络日志才有现场证据）。
var defaultOverlayLog func(level, msg string)

// overlayReadBounded 对应 :729-746：按 8192 块读，累计超过 limit 返回 !ok。
func overlayReadBounded(compressed []byte, limit int) ([]byte, bool) {
	gr, err := gzip.NewReader(bytes.NewReader(compressed))
	if err != nil {
		return nil, false
	}
	defer gr.Close()
	var bos bytes.Buffer
	chunk := make([]byte, overlayGzipReadChunk)
	total := 0
	for {
		n, err := gr.Read(chunk)
		if n > 0 {
			total += n
			if total > limit {
				return nil, false
			}
			bos.Write(chunk[:n])
		}
		if err == io.EOF {
			return bos.Bytes(), true
		}
		if err != nil {
			return nil, false
		}
	}
}

// overlayFramePacket 对应 :748-753：[4 字节大端长度][gzip 载荷]。
func overlayFramePacket(compressed []byte) []byte {
	buf := make([]byte, 4+len(compressed))
	binary.BigEndian.PutUint32(buf[:4], uint32(len(compressed)))
	copy(buf[4:], compressed)
	return buf
}

// ---- 小工具 ----

// ovResolve 把 host:port 解析成 UDP 地址。解析失败返回"未解析地址"（IP=nil，端口保留），
// 与 Java 的 unresolved InetSocketAddress 同形态：槽位照样占住（角色判定、握手来源约束
// 都按 IP 判定，unresolved 的 IP==null → ovSameHost 恒 false），发包时失败记 debug。
func ovResolve(host string, port int) *net.UDPAddr {
	addr, err := net.ResolveUDPAddr("udp", net.JoinHostPort(host, strconv.Itoa(port)))
	if err != nil {
		return &net.UDPAddr{Port: port}
	}
	return addr
}

func ovOrNilString(s string) string {
	if s == "" {
		return "none"
	}
	return s
}
