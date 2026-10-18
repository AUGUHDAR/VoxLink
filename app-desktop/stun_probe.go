package main

// stun_probe.go — 模组 network/StunProbe.java（1.2.0，1640 行）并发探测面的逐值移植：
//
//   - 单/双/竞速/四路 mapped 地址探测（discoverMappedAddress / Dual / Race / Quad，:47/:172/:282/:377）
//   - 细分 NAT 判定：RFC5780 优先（:768），失败回落"两服务器 delta + 同服务器异端口确认"（:848），
//     对称再按 span/extra-bind 分级（gradeSymmetricSeverity :1023）与第三采样定方向（detectEasySymmetric :1097）
//   - 全服务器并发探测 + 2 个可达即提前收口（probe :612，CountDownLatch(2) 最长 10s）
//   - 顺序端口采样（samplePortsSequential :1349，先竞速选台再按间隔采样，供端口预测）
//   - 网络变更检测（isNetworkChanged :547）与 20s 热缓存（getCachedResult :567）
//   - 磁盘缓存集成（StunCache.save / clear，:544/:594，见 stun_cache.go）
//
// 常量逐个从源码抄录，**不做数值调整**。源码里有一批"声明了但方法体写死字面量"的死常量
// （如 PROBE_TIMEOUT_MS / DISCOVER_TIMEOUT_MS / SYM_DETECT_TIMEOUT_MS / CHANGE_IP_FLAG /
// CACHE_TTL_MS 等，全类只出现一次＝声明处），这里照抄常量并在注释标出真正生效的字面量位置，
// 以免后来者以为改这些常量有效果。
//
// Java→Go 的必要转换（不改变行为）：
//   - Java 的 socket.setSoTimeout() 是套接字状态；Go 只有单次操作级 deadline。这里用 stunSocket
//     显式携带"当前 soTimeout"，每次读前设 deadline，等价于 Java 的阻塞读超时；
//     因此 Java 退出时"恢复 originalTimeout"在本端天然是 no-op（下一次读一定会重设 deadline）。
//   - Java 的 CompletableFuture/ExecutorService → goroutine + 带缓冲通道（stunProbeFuture）。
//     模组 shutdown() 回收线程池（:501，VoxLinkMod.java:97 调用）在 Go 无对应资源，不移植。
//   - STUN 报文构造/解析复用 stun.go 的 stunBuildRequest/stunParseResponse（同一字节语义），
//     本文件不出现第二份 magic cookie 字面量。

import (
	"encoding/binary"
	"errors"
	"fmt"
	"log"
	"math/rand"
	"net"
	"strconv"
	"strings"
	"sync"
	"time"
)

// ---- StunProbe.java:22-42 声明的全部常量（值逐条抄录）----
const (
	stunProbeTimeoutMs         = 8000   // :22 PROBE_TIMEOUT_MS —— 死常量；probeSingleServer 写死 8000（:668）
	stunDiscoverTimeoutMs      = 800    // :23 DISCOVER_TIMEOUT_MS —— 死常量；各探测方法写死 800（:84/:208/:315/:416）
	stunDualStunTimeoutMs      = 800    // :24 DUAL_STUN_TIMEOUT_MS —— 死常量（同上）
	stunInitialRtoMs           = 200    // :25 STUN_INITIAL_RTO_MS —— 死常量；resendDelay 写死 200（:495）
	stunMaxRetransmissions     = 2      // :26 STUN_MAX_RETRANSMISSIONS —— 死常量；循环写死 <=2（:112/:119）
	stunMaxRtoMs               = 800    // :27 STUN_MAX_RTO_MS —— 死常量；resendDelay 写死 min(…,800)（:496）
	stunRecvPollMs             = 100    // :29 STUN_RECV_POLL_MS —— 死常量；各方法 setSoTimeout(100)（:52/:177/:289/:384/:1356）
	stunDefaultPort            = 3478   // :30 STUN_DEFAULT_PORT —— 死常量；parseStunUrl 写死 3478（:1320/:1343/:1345）
	stunEasySymPortDeltaTh     = 100    // :31 EASY_SYM_PORT_DELTA_THRESHOLD —— 生效（:1078/:1082）
	stunEasySymPortDeltaFallbk = 200    // :32 EASY_SYM_PORT_DELTA_FALLBACK —— 死常量；判定处内联 `? 100 : 200`（:990/:1126）
	stunSymGradeSpanEasyMax    = 15     // :33 SYM_GRADE_SPAN_EASY_MAX —— 生效（:1062）
	stunSymGradeSampleServers  = 4      // :34 SYM_GRADE_SAMPLE_SERVERS —— 生效（:1030）
	stunAttrChangeRequest      = 3      // :35 ATTR_CHANGE_REQUEST —— 死常量；createBindingRequestWithChange 内联 0,3（:724-725）
	stunChangeIpFlag           = 4      // :36 CHANGE_IP_FLAG —— 死常量；同函数内联 `? 4 : 0`（:711）
	stunChangePortFlag         = 2      // :37 CHANGE_PORT_FLAG —— 死常量；同函数内联 `? 2 : 0`（:711）
	stunRfc5780MaxTries        = 3      // :38 RFC5780_MAX_TRIES —— 死常量；detectNatType 内联 >=3（:863）
	stunProbeCacheTtlMs        = 300000 // :39 CACHE_TTL_MS —— 本类死常量，磁盘 TTL 由 StunCache.java:16 同一数值负责
	stunWarmReuseTtlMs         = 20000  // :40 WARM_REUSE_TTL_MS —— 生效（:569）
	stunTimeoutMultiplier      = 2      // :41 TIMEOUT_MULTIPLIER —— 死常量，全类无引用
	stunSymDetectTimeoutMs     = 1500   // :42 SYM_DETECT_TIMEOUT_MS —— 死常量；detectEasySymmetric 内联 1500（:1113）

	// 方法体内写死的字面量（源码里没有名字，逐值抄在这里以便对齐）：
	stunRecvBufLen        = 576               // 各 receive 缓冲区（:99/:1379 等）
	stunRfc5780SoToMs     = 800               // detectNatTypeWithRfc5780 入口 setSoTimeout(800)（:773）
	stunTryRecvSoToMs     = 800               // tryRecvMapped 内 setSoTimeout(800)（:1515）
	stunTryRecvRestoreMs  = 8000              // tryRecvMapped 收尾**硬**恢复 8000（不是保存值，:1521 quirk，照抄）
	stunDeltaWindowMs     = 8000              // detectNatType 两服务器采样窗口（:859/:887）
	stunAltPortMaxTries   = 3                 // 同服务器异端口确认 alt=1..3（:939）
	stunProbeLatchNeed    = 2                 // probe(): 两个可达即收口（:622）
	stunProbeLatchWaitMs  = 10000             // probe(): 最长等待（:633）
	stunErrorResponseType = 0x0111            // sendChangeRequest 里判 273（:753）
	stunBindingSuccess    = int(stunRespType) // 257：直接取 stun.go 常量，本文件不重复写魔法数
	stunAltPortThresholdT = 100               // EasySym 回退 threshold 有效分支（:990）
	stunAltPortFallbackT  = 200               // EasySym 回退 threshold 兜底分支（:990）
)

// stunJitterFactor = StunProbe.java:28 STUN_JITTER_FACTOR 0.2（resendDelay 内联 0.2，:497）。
const stunJitterFactor = 0.2

// ---- 日志出口 ----
//
// 与 modsync 的 modSyncLog 同构：App 在场时走 netLog（进前端连接日志，出口会脱敏 IP/端口，
// 这是应用侧隐私策略，优先于逐字日志文本），否则回落 log.Printf。
// 探测层不反向依赖 App，注入方式与 puncher.setLogf 一致。
var (
	stunLogMu  sync.RWMutex
	stunLogFn  = func(level, msg string) { log.Printf("[%s] %s", level, msg) }
	stunLogTag = "[StunProbe] "
)

// SetStunProbeLogf 注入网络日志出口（集成点：App 初始化时传 (*App).netLog）。
func SetStunProbeLogf(fn func(level, msg string)) {
	if fn == nil {
		return
	}
	stunLogMu.Lock()
	stunLogFn = fn
	stunLogMu.Unlock()
}

func stunProbeLog(level, format string, args ...any) {
	msg := stunLogTag + fmt.Sprintf(format, args...)
	stunLogMu.RLock()
	fn := stunLogFn
	stunLogMu.RUnlock()
	fn(level, msg)
}

// ---- NAT 细分类型（StunProbe.java:1551-1589 NatType）----
//
// 注意源码里有 10 个枚举常量但只有 7 个不同 key：
// EASY_SYM_INC 与 SYMMETRIC_EASY_INC 同为 "symmetric_easy_inc"、EASY_SYM_DEC 同理、
// HARD_SYM 与 SYMMETRIC 同为 "symmetric"。isSymmetric/isEasySymmetric/isHardSymmetric/canHolePunch
// 全部按**枚举身份**比较，不按 key，所以这里保留成 10 个身份 + 重复 key，不能合并。
type stunNatType int

const (
	natTypeUnknown            stunNatType = iota // :1552 "unknown"
	natTypeFullCone                              // :1553 "full_cone"
	natTypeRestrictedCone                        // :1554 "restricted_cone"
	natTypePortRestrictedCone                    // :1555 "port_restricted_cone"
	natTypeSymmetricEasyInc                      // :1556 "symmetric_easy_inc"
	natTypeSymmetricEasyDec                      // :1557 "symmetric_easy_dec"
	natTypeSymmetric                             // :1558 "symmetric"
	natTypeEasySymInc                            // :1559 "symmetric_easy_inc"（gradeSymmetricSeverity 用）
	natTypeEasySymDec                            // :1560 "symmetric_easy_dec"（同上）
	natTypeHardSym                               // :1561 "symmetric"（同上）
)

// stunNatTypeKeys 声明序即 NatType.values() 序，parseNatType 按此序首个匹配（:602-610）。
var stunNatTypeKeys = map[stunNatType]string{
	natTypeUnknown:            "unknown",
	natTypeFullCone:           "full_cone",
	natTypeRestrictedCone:     "restricted_cone",
	natTypePortRestrictedCone: "port_restricted_cone",
	natTypeSymmetricEasyInc:   "symmetric_easy_inc",
	natTypeSymmetricEasyDec:   "symmetric_easy_dec",
	natTypeSymmetric:          "symmetric",
	natTypeEasySymInc:         "symmetric_easy_inc",
	natTypeEasySymDec:         "symmetric_easy_dec",
	natTypeHardSym:            "symmetric",
}

var stunNatTypeOrder = []stunNatType{
	natTypeUnknown,
	natTypeFullCone,
	natTypeRestrictedCone,
	natTypePortRestrictedCone,
	natTypeSymmetricEasyInc,
	natTypeSymmetricEasyDec,
	natTypeSymmetric,
	natTypeEasySymInc,
	natTypeEasySymDec,
	natTypeHardSym,
}

// key 对应 NatType.key（:1563）。
func (t stunNatType) key() string {
	if k, ok := stunNatTypeKeys[t]; ok {
		return k
	}
	return "unknown"
}

func (t stunNatType) symmetric() bool { // :1569 isSymmetric（按身份）
	return t == natTypeSymmetric || t == natTypeSymmetricEasyInc || t == natTypeSymmetricEasyDec ||
		t == natTypeEasySymInc || t == natTypeEasySymDec || t == natTypeHardSym
}

func (t stunNatType) easySymmetric() bool { // :1578 isEasySymmetric
	return t == natTypeSymmetricEasyInc || t == natTypeSymmetricEasyDec || t == natTypeEasySymInc || t == natTypeEasySymDec
}

func (t stunNatType) hardSymmetric() bool { // :1582 isHardSymmetric
	return t == natTypeSymmetric || t == natTypeHardSym
}

// canHolePunch :1586：只有 SYMMETRIC / HARD_SYM 判不可打洞（easy 系两身份都可打洞）。
func (t stunNatType) canHolePunch() bool {
	return t != natTypeSymmetric && t != natTypeHardSym
}

// stunNatTypeFromKey 对应 parseNatType（:602）：按声明序找首个 key 相同的身份，无则 UNKNOWN。
func stunNatTypeFromKey(key string) stunNatType {
	for _, t := range stunNatTypeOrder {
		if stunNatTypeKeys[t] == key {
			return t
		}
	}
	return natTypeUnknown
}

// natClassFromStunNatType 对应 NatClass.fromStunProbeResult（NatClass.java:13-34）：
// 细粒度身份收敛到 4 档，供 recommendProfile/selectStrategy 用。
func natClassFromStunNatType(t stunNatType) natClass {
	switch t {
	case natTypeFullCone, natTypeRestrictedCone, natTypePortRestrictedCone:
		return natCone
	case natTypeSymmetricEasyInc, natTypeSymmetricEasyDec, natTypeEasySymInc, natTypeEasySymDec:
		return natEasySym
	case natTypeSymmetric, natTypeHardSym:
		return natHardSym
	default:
		return natUnknown
	}
}

// ---- 结果结构 ----

// stunServerResult = StunProbe.StunServerResult（:1617-1639）。
// Java 的 mappedIp 可为 null，Go 用 ""（判定处 `mappedIp != null` → `MappedIP != ""`）。
type stunServerResult struct {
	URL        string // :1618
	Host       string // :1619
	Port       int    // :1620
	Reachable  bool   // :1621
	LatencyMs  int64  // :1622（-1 = 未测得）
	MappedIP   string // :1623
	MappedPort int    // :1624
}

// stunProbeResult = StunProbe.ProbeResult（:1594-1612）。
type stunProbeResult struct {
	NatType stunNatType
	Servers []stunServerResult
	// ReachableStunURLs 构造时由 reachable 的 url 顺序生成（:1602-1610）。
	ReachableStunURLs []string
}

// stunProbeFuture ≈ CompletableFuture<ProbeResult>（getNow/join 语义与 Java 一致）。
type stunProbeFuture struct {
	ch chan *stunProbeResult
}

func newStunProbeFuture() *stunProbeFuture {
	return &stunProbeFuture{ch: make(chan *stunProbeResult, 1)}
}

// complete 完成一次（重复调用只生效一次，Java 的 CompletableFuture 同语义）。
func (f *stunProbeFuture) complete(r *stunProbeResult) {
	select {
	case f.ch <- r:
	default:
	}
}

// getNow 未完成时返回 def（Java getNow(def)）。
func (f *stunProbeFuture) getNow(def *stunProbeResult) *stunProbeResult {
	select {
	case r := <-f.ch:
		// 回填，保证后续 join/getNow 仍拿得到值
		select {
		case f.ch <- r:
		default:
		}
		return r
	default:
		return def
	}
}

// join 阻塞取结果（Java join）。
func (f *stunProbeFuture) join() *stunProbeResult { return <-f.ch }

// ---- socket 包装 ----

// stunSocket 携带"当前 soTimeout"，等价 Java DatagramSocket 的 setSoTimeout 状态。
type stunSocket struct {
	uc        *net.UDPConn
	soTimeout time.Duration
}

func newStunSocket(uc *net.UDPConn, soTimeout time.Duration) *stunSocket {
	return &stunSocket{uc: uc, soTimeout: soTimeout}
}

func (s *stunSocket) setSoTimeout(d time.Duration) { s.soTimeout = d }

func (s *stunSocket) send(req []byte, raddr *net.UDPAddr) error {
	if s.uc == nil || raddr == nil {
		return errors.New("stun: socket 未就绪")
	}
	_, err := s.uc.WriteToUDP(req, raddr)
	return err
}

func (s *stunSocket) recv(buf []byte) (int, *net.UDPAddr, error) {
	if s.uc == nil {
		return 0, nil, errors.New("stun: socket 未就绪")
	}
	if s.soTimeout > 0 {
		_ = s.uc.SetReadDeadline(time.Now().Add(s.soTimeout))
	}
	n, from, err := s.uc.ReadFromUDP(buf)
	return n, from, err
}

func (s *stunSocket) localPort() int {
	if s.uc == nil {
		return 0
	}
	if a, ok := s.uc.LocalAddr().(*net.UDPAddr); ok {
		return a.Port
	}
	return 0
}

// stunIsTimeout 区分"读超时"与"其它 IO 错误"（Java 分别 catch SocketTimeoutException / IOException）。
func stunIsTimeout(err error) bool {
	var ne net.Error
	return errors.As(err, &ne) && ne.Timeout()
}

// stunNoResend 是 Java `Long.MAX_VALUE` 哨兵（:92/:119/:236/:333/:440/:1399）的等价物：永不再重传。
var stunNoResend = time.Time{}.Add(time.Duration(1<<63 - 1))

// ---- URL 解析（parseStunUrl :1306-1347）----

type stunParsedURL struct {
	host string
	port int
}

// stunParseURL 返回 ok=false 对应 Java 的 null（无括号的裸 IPv6 等）。
func stunParseURL(stunURL string) (stunParsedURL, bool) {
	stripped := strings.ReplaceAll(stunURL, "stun:", "")
	stripped = strings.ReplaceAll(stripped, "stuns:", "")
	if strings.HasPrefix(stripped, "[") {
		if bracketEnd := strings.IndexByte(stripped, ']'); bracketEnd > 0 {
			host := stripped[1:bracketEnd]
			port := 0
			if len(stripped) > bracketEnd+2 && stripped[bracketEnd+1] == ':' {
				if p, err := strconv.Atoi(stripped[bracketEnd+2:]); err == nil {
					port = p
				}
			}
			if port > 0 {
				return stunParsedURL{host, port}, true
			}
			return stunParsedURL{host, stunDefaultPort}, true
		}
	}
	if strings.Contains(stripped, ":") {
		lastColon := strings.LastIndex(stripped, ":")
		if lastColon > 0 && lastColon < len(stripped)-1 {
			host := stripped[:lastColon]
			portStr := stripped[lastColon+1:]
			if strings.Contains(host, ":") {
				stunProbeLog("warn", "IPv6 without brackets, ambiguous, ignore: %s", stunURL)
				return stunParsedURL{}, false
			}
			if p, err := strconv.Atoi(portStr); err == nil {
				if p > 0 && p <= 65535 {
					return stunParsedURL{host, p}, true
				}
			}
		}
		return stunParsedURL{stripped, stunDefaultPort}, true
	}
	return stunParsedURL{stripped, stunDefaultPort}, true
}

// stunResolveHost 对应 InetAddress.getByName(parsed.host) + new InetSocketAddress(host, port)。
func stunResolveHost(host string, port int) (*net.UDPAddr, error) {
	return net.ResolveUDPAddr("udp", net.JoinHostPort(host, strconv.Itoa(port)))
}

// ---- 报文与事务匹配 ----

// stunMatchTransaction = matchTransaction（:480-492）：比对事务域 [8,20)。
func stunMatchTransaction(data, request []byte) bool {
	if len(data) < 20 {
		return false
	}
	for i := 8; i < 20; i++ {
		if data[i] != request[i] {
			return false
		}
	}
	return true
}

// stunBuildRequestWithChange = createBindingRequestWithChange（:710-733）：
// 20B 头 + CHANGE-REQUEST(0x0003, len 4)，flags = changeIp?4:0 | changePort?2:0（源码内联字面量）。
func stunBuildRequestWithChange(changeIP, changePort bool) []byte {
	flags := 0
	if changeIP {
		flags |= stunChangeIpFlag
	}
	if changePort {
		flags |= stunChangePortFlag
	}
	req := make([]byte, 28)
	req[0], req[1] = 0x00, 0x01
	req[2], req[3] = 0x00, 0x08
	// cookie 只从 stun.go 的 stunMagicCookie 取（0x2112A442），本文件不出现第二份字面量
	binary.BigEndian.PutUint32(req[4:8], stunMagicCookie)
	tid := make([]byte, 12)
	if _, err := cryptRandRead(tid); err != nil {
		for i := range tid {
			tid[i] = byte(time.Now().UnixNano() >> (uint(i) * 8))
		}
	}
	copy(req[8:20], tid)
	req[20], req[21] = 0x00, byte(stunAttrChangeRequest)
	req[22], req[23] = 0x00, 0x04
	req[24], req[25], req[26], req[27] = 0, 0, 0, byte(flags)
	return req
}

// stunResendDelay = resendDelay（:494-499）：RTO=200<<n 上限 800，抖动 ±20%（(int) 截断）。
func stunResendDelay(retransmissions int) int {
	rto := stunInitialRtoMs << retransmissions
	if rto > stunMaxRtoMs {
		rto = stunMaxRtoMs
	}
	jitter := int(float64(rto) * stunJitterFactor)
	return rto - jitter + rand.Intn(2*jitter+1)
}

// ---- 单路探测（discoverMappedAddress :47-170）----

// stunDiscoverMappedAddress 在同一 socket 上并发对多个 STUN 发 Binding 请求，
// 800ms 窗口内最多 3 次发送（1 初始 + 2 重传），任一响应命中即返回。
func stunDiscoverMappedAddress(uc *net.UDPConn, stunURLs []string) *stunMappedAddr {
	sk := newStunSocket(uc, stunRecvPollMs*time.Millisecond)
	stunProbeLog("info", "Start probing, %d STUN servers, socket port=%d, timeout=%dms", len(stunURLs), sk.localPort(), 800)

	var reqs [][]byte
	var targets []*net.UDPAddr
	attempted := 0
	for _, url := range stunURLs {
		parsed, ok := stunParseURL(url)
		if !ok {
			continue
		}
		// Java 在解析成功后自增 attempted，再 getByName；解析失败只 warn 且不计数（:64-76）
		attempted++
		raddr, err := stunResolveHost(parsed.host, parsed.port)
		if err != nil {
			stunProbeLog("warn", "%s resolve failed: %s", url, err.Error())
			continue
		}
		reqs = append(reqs, stunBuildRequest())
		targets = append(targets, raddr)
	}

	if len(reqs) == 0 {
		stunProbeLog("warn", "Mapped address probe failed: attempted=%d, timeouts=0, errors=0", attempted)
		return nil
	}

	start := time.Now()
	deadline := start.Add(stunDiscoverTimeoutMs * time.Millisecond)
	sendCounts := make([]int, len(reqs))
	nextResend := make([]time.Time, len(reqs))
	for i := range reqs {
		if err := sk.send(reqs[i], targets[i]); err != nil {
			// Java 这里赋 Long.MAX_VALUE，但下一行无条件覆盖（:92 vs :96）—— 死写，照抄语义
			nextResend[i] = stunNoResend
		}
		sendCounts[i] = 1
		nextResend[i] = start.Add(time.Duration(stunResendDelay(1)) * time.Millisecond)
	}

	buf := make([]byte, stunRecvBufLen)
	for {
		if !time.Now().Before(deadline) {
			stunProbeLog("warn", "Mapped address probe failed: attempted=%d, timeouts=%d, errors=0", attempted, len(reqs))
			return nil
		}
		now := time.Now()
		for i := range reqs {
			if sendCounts[i] <= stunMaxRetransmissions && !now.Before(nextResend[i]) {
				_ = sk.send(reqs[i], targets[i]) // Java 吞掉发送异常（:113-116）
				sendCounts[i]++
				if sendCounts[i] <= stunMaxRetransmissions {
					nextResend[i] = now.Add(time.Duration(stunResendDelay(sendCounts[i])) * time.Millisecond)
				} else {
					nextResend[i] = stunNoResend
				}
			}
		}

		n, _, err := sk.recv(buf)
		if err != nil {
			if stunIsTimeout(err) {
				continue
			}
			return nil // Java: IOException → return null（:127-129）
		}
		data := append([]byte(nil), buf[:n]...)
		var matched []byte
		for _, req := range reqs {
			if len(data) >= 20 && stunMatchTransaction(data, req) {
				matched = req
				break
			}
		}
		if matched != nil {
			if m, perr := stunParseResponse(data, matched); perr == nil {
				stunProbeLog("info", "Mapped address: %s:%d (sends=1)", m.IP, m.Port)
				return m
			}
		}
	}
}

// ---- 双路（discoverMappedAddressDual :172-280）----

// stunDiscoverMappedAddressDual 两个 STUN 同 socket 并发；结果按入参下标对齐，缺失项
// 退化为单路探测（Java 同样在超时后对仍为 null 的下标再各跑一次 discoverMappedAddress）。
func stunDiscoverMappedAddressDual(uc *net.UDPConn, stunURL1, stunURL2 string) []*stunMappedAddr {
	results := []*stunMappedAddr{nil, nil}
	sk := newStunSocket(uc, stunRecvPollMs*time.Millisecond)

	u1, ok1 := stunParseURL(stunURL1)
	u2, ok2 := stunParseURL(stunURL2)
	if !ok1 || !ok2 {
		if ok1 {
			results[0] = stunDiscoverMappedAddress(uc, []string{stunURL1})
		}
		if ok2 {
			results[1] = stunDiscoverMappedAddress(uc, []string{stunURL2})
		}
		return results
	}

	stunProbeLog("info", "Parallel dual STUN: %s + %s, socket port=%d", stunURL1, stunURL2, sk.localPort())

	req1 := stunBuildRequest()
	req2 := stunBuildRequest()
	addr1, err1 := stunResolveHost(u1.host, u1.port)
	addr2, err2 := stunResolveHost(u2.host, u2.port)
	if err1 != nil || err2 != nil {
		stunProbeLog("warn", "Parallel dual STUN exception: %s", firstStunErr(err1, err2).Error())
		return results
	}
	// Java 的发送异常冒泡到外层 catch：记一条 warn 后直接返回当前结果（不做兜底重探，:203-204/:267-268）
	if err := sk.send(req1, addr1); err != nil {
		stunProbeLog("warn", "Parallel dual STUN exception: %s", err.Error())
		return results
	}
	if err := sk.send(req2, addr2); err != nil {
		stunProbeLog("warn", "Parallel dual STUN exception: %s", err.Error())
		return results
	}

	buf := make([]byte, stunRecvBufLen)
	start := time.Now()
	deadline := start.Add(stunDualStunTimeoutMs * time.Millisecond)
	nextResend := start.Add(time.Duration(stunResendDelay(1)) * time.Millisecond)
	got := 0

loop:
	for {
		if got >= 2 || !time.Now().Before(deadline) {
			if results[0] == nil {
				results[0] = stunDiscoverMappedAddress(uc, []string{stunURL1})
			}
			if results[1] == nil {
				results[1] = stunDiscoverMappedAddress(uc, []string{stunURL2})
			}
			break loop
		}
		now := time.Now()
		if !now.Before(nextResend) {
			if results[0] == nil {
				if err := sk.send(req1, addr1); err != nil {
					stunProbeLog("warn", "Parallel dual STUN exception: %s", err.Error())
					break loop
				}
			}
			if results[1] == nil {
				if err := sk.send(req2, addr2); err != nil {
					stunProbeLog("warn", "Parallel dual STUN exception: %s", err.Error())
					break loop
				}
			}
			nextResend = stunNoResend
		}
		n, _, err := sk.recv(buf)
		if err != nil {
			if stunIsTimeout(err) {
				continue
			}
			// Java 的非超时 IO 异常冒泡到外层 catch：记一条 warn 后走收尾兜底（:267-268）
			stunProbeLog("warn", "Parallel dual STUN exception: %s", err.Error())
			if results[0] == nil {
				results[0] = stunDiscoverMappedAddress(uc, []string{stunURL1})
			}
			if results[1] == nil {
				results[1] = stunDiscoverMappedAddress(uc, []string{stunURL2})
			}
			break loop
		}
		data := append([]byte(nil), buf[:n]...)
		if len(data) >= 20 {
			if results[0] == nil && stunMatchTransaction(data, req1) {
				if m, perr := stunParseResponse(data, req1); perr == nil {
					results[0] = m
					got++
					continue // Java：命中 0 号后不再尝试 1 号（:254）
				}
			}
			if results[1] == nil && stunMatchTransaction(data, req2) {
				if m, perr := stunParseResponse(data, req2); perr == nil {
					results[1] = m
					got++
				}
			}
		}
	}

	stunProbeLog("info", "Dual STUN results: [0]=%s, [1]=%s", stunMappedAddrDesc(results[0]), stunMappedAddrDesc(results[1]))
	return results
}

// ---- 竞速（discoverMappedAddressRace :282-375）----

// stunDiscoverMappedAddressRace 向全部 STUN 各发一次，谁先答谁进结果（前 need 个），
// 注意结果**按到达顺序紧凑存放**（results[got]），不与服务器下标对齐。
// Java 的任何一个 IO 异常都会中断整个探测（catch → warn → 带部分结果返回），本端一致。
func stunDiscoverMappedAddressRace(uc *net.UDPConn, stunURLs []string, need int) []*stunMappedAddr {
	results := make([]*stunMappedAddr, need)
	if len(stunURLs) == 0 || need <= 0 {
		return results
	}
	got := stunRaceLoop(uc, stunURLs, need, results)
	stunProbeLog("info", "Race done: got %d/%d", got, need)
	return results
}

func stunRaceLoop(uc *net.UDPConn, stunURLs []string, need int, results []*stunMappedAddr) int {
	sk := newStunSocket(uc, stunRecvPollMs*time.Millisecond)

	n := len(stunURLs)
	reqs := make([][]byte, n)
	addrs := make([]*net.UDPAddr, n)

	stunProbeLog("info", "Parallel race %d STUN take %d , socket port=%d", n, need, sk.localPort())

	got := 0
	for i := 0; i < n; i++ {
		p, ok := stunParseURL(stunURLs[i])
		if !ok {
			continue
		}
		reqs[i] = stunBuildRequest()
		a, err := stunResolveHost(p.host, p.port)
		if err != nil {
			stunProbeLog("warn", "Parallel race exception: %s", err.Error())
			return got
		}
		addrs[i] = a
		if err := sk.send(reqs[i], a); err != nil {
			stunProbeLog("warn", "Parallel race exception: %s", err.Error())
			return got
		}
	}

	buf := make([]byte, stunRecvBufLen)
	start := time.Now()
	deadline := start.Add(stunDiscoverTimeoutMs * time.Millisecond)
	nextResend := start.Add(time.Duration(stunResendDelay(1)) * time.Millisecond)
	answered := make([]bool, n)

	for got < need && time.Now().Before(deadline) {
		now := time.Now()
		if !now.Before(nextResend) {
			for i := 0; i < n; i++ {
				if reqs[i] != nil && !answered[i] {
					if err := sk.send(reqs[i], addrs[i]); err != nil {
						stunProbeLog("warn", "Parallel race exception: %s", err.Error())
						return got
					}
				}
			}
			nextResend = stunNoResend
		}
		rd, _, err := sk.recv(buf)
		if err != nil {
			if stunIsTimeout(err) {
				continue
			}
			stunProbeLog("warn", "Parallel race exception: %s", err.Error())
			return got
		}
		data := append([]byte(nil), buf[:rd]...)
		if len(data) >= 20 {
			for i := 0; i < n; i++ {
				if reqs[i] != nil && !answered[i] && stunMatchTransaction(data, reqs[i]) {
					if m, perr := stunParseResponse(data, reqs[i]); perr == nil {
						answered[i] = true
						results[got] = m
						got++
						stunProbeLog("info", "Race #%d: %s -> %s:%d", got, stunURLs[i], m.IP, m.Port)
					}
					break
				}
			}
		}
	}
	return got
}

// ---- 四路（discoverMappedAddressQuad :377-478）----

// stunDiscoverMappedAddressQuad 四台 STUN 并发；兜底只对下标 0/1 再各跑一次单路
// （源码 :424 `for (int i = 0; i < 2; i++)` —— 2/3 号缺失时不兜底，照抄）。
func stunDiscoverMappedAddressQuad(uc *net.UDPConn, u1, u2, u3, u4 string) []*stunMappedAddr {
	sk := newStunSocket(uc, stunRecvPollMs*time.Millisecond)
	urls := []string{u1, u2, u3, u4}
	results := make([]*stunMappedAddr, 4)

	stunProbeLog("info", "Parallel 4 STUN: %s+%s+%s+%s, socket port=%d", u1, u2, u3, u4, sk.localPort())

	reqs := make([][]byte, 4)
	addrs := make([]*net.UDPAddr, 4)
	okFlags := make([]bool, 4)
	validCount := 0
	aborted := false
	for i := 0; i < 4 && !aborted; i++ {
		p, ok := stunParseURL(urls[i])
		if !ok {
			continue
		}
		reqs[i] = stunBuildRequest()
		a, err := stunResolveHost(p.host, p.port)
		if err != nil {
			stunProbeLog("warn", "Parallel 4 STUN exception: %s", err.Error())
			aborted = true
			break
		}
		addrs[i] = a
		okFlags[i] = true
		if err := sk.send(reqs[i], a); err != nil {
			stunProbeLog("warn", "Parallel 4 STUN exception: %s", err.Error())
			aborted = true
			break
		}
		validCount++
	}
	if validCount == 0 || aborted {
		goto done
	}

	{
		buf := make([]byte, stunRecvBufLen)
		start := time.Now()
		deadline := start.Add(stunDiscoverTimeoutMs * time.Millisecond)
		nextResend := start.Add(time.Duration(stunResendDelay(1)) * time.Millisecond)
		got := 0

	loop2:
		for {
			if got >= validCount || !time.Now().Before(deadline) {
				for i := 0; i < 2; i++ {
					if results[i] == nil && okFlags[i] {
						results[i] = stunDiscoverMappedAddress(uc, []string{urls[i]})
					}
				}
				break loop2
			}
			now := time.Now()
			if !now.Before(nextResend) {
				for i := 0; i < 4; i++ {
					if reqs[i] != nil && results[i] == nil {
						if err := sk.send(reqs[i], addrs[i]); err != nil {
							stunProbeLog("warn", "Parallel 4 STUN exception: %s", err.Error())
							break loop2
						}
					}
				}
				nextResend = stunNoResend
			}
			n, _, err := sk.recv(buf)
			if err != nil {
				if stunIsTimeout(err) {
					continue
				}
				stunProbeLog("warn", "Parallel 4 STUN exception: %s", err.Error())
				break loop2
			}
			data := append([]byte(nil), buf[:n]...)
			if len(data) >= 20 {
				for i := 0; i < 4; i++ {
					if results[i] == nil && reqs[i] != nil && stunMatchTransaction(data, reqs[i]) {
						if m, perr := stunParseResponse(data, reqs[i]); perr == nil {
							results[i] = m
							got++
						}
						break
					}
				}
			}
		}
	}

done:
	stunProbeLog("info", "4 STUN results: [0]=%s, [1]=%s, [2]=%s, [3]=%s",
		stunMappedAddrDesc(results[0]), stunMappedAddrDesc(results[1]), stunMappedAddrDesc(results[2]), stunMappedAddrDesc(results[3]))
	return results
}

func firstStunErr(errs ...error) error {
	for _, e := range errs {
		if e != nil {
			return e
		}
	}
	return errors.New("unknown")
}

// stunMappedAddrDesc 日志用（Java 直接拼对象，靠 toString；这里显式格式化，脱敏策略仍由出口兜底）。
func stunMappedAddrDesc(a *stunMappedAddr) string {
	if a == nil {
		return "null"
	}
	return fmt.Sprintf("%s:%d", a.IP, a.Port)
}

// ---- 全量探测（probe :612-651）----

// stunProbeSingleServer = probeSingleServer（:657-698）：独立 socket、8000ms 单次收发、测延迟。
func stunProbeSingleServer(stunURL string) stunServerResult {
	parsed, ok := stunParseURL(stunURL)
	if !ok {
		// Java 四参构造（:660）→ host=null、port=0、latency=-1
		return stunServerResult{URL: stunURL, Reachable: false, LatencyMs: -1}
	}
	res := stunServerResult{URL: stunURL, Host: parsed.host, Port: parsed.port, Reachable: false, LatencyMs: -1}

	uc, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4zero, Port: 0})
	if err != nil {
		stunProbeLog("debug", "%s unreachable: %s", stunURL, err.Error())
		return res
	}
	defer uc.Close()
	sk := newStunSocket(uc, stunProbeTimeoutMs*time.Millisecond)

	raddr, err := stunResolveHost(parsed.host, parsed.port)
	if err != nil {
		stunProbeLog("debug", "%s unreachable: %s", stunURL, err.Error())
		return res
	}

	req := stunBuildRequest()
	sendStart := time.Now()
	if err := sk.send(req, raddr); err != nil {
		stunProbeLog("debug", "%s unreachable: %s", stunURL, err.Error())
		return res
	}
	buf := make([]byte, stunRecvBufLen)
	n, _, err := sk.recv(buf)
	if err != nil {
		stunProbeLog("debug", "%s unreachable: %s", stunURL, err.Error())
		return res
	}
	latency := time.Since(sendStart).Milliseconds()
	data := append([]byte(nil), buf[:n]...)
	res.Reachable = true
	res.LatencyMs = latency
	if m, perr := stunParseResponse(data, req); perr == nil {
		stunProbeLog("debug", "%s reachable, latency=%dms, mapped=%s:%d", stunURL, latency, m.IP, m.Port)
		res.MappedIP = m.IP
		res.MappedPort = m.Port
	} else {
		stunProbeLog("debug", "%s has response but no mapped address", stunURL)
	}
	return res
}

// stunProbeServerGroups = probe（:612-651）：所有分组的所有 URL 并发探测，
// 两个可达即提前收口（最长 10s），未完成的按 Java getNow 的占位值计入。
func stunProbeServerGroups(groups [][]string) *stunProbeResult {
	stunSetLastProbeLocalIP(stunDetectLocalIP())

	var urls []string
	for _, group := range groups {
		for _, u := range group {
			urls = append(urls, u)
		}
	}

	results := make([]*stunServerResult, len(urls))
	var mu sync.Mutex
	reachableCh := make(chan struct{}, len(urls))
	var wg sync.WaitGroup
	for i, u := range urls {
		wg.Add(1)
		go func(idx int, url string) {
			defer wg.Done()
			r := stunProbeSingleServer(url)
			mu.Lock()
			results[idx] = &r
			mu.Unlock()
			if r.Reachable {
				select {
				case reachableCh <- struct{}{}:
				default:
				}
			}
		}(i, u)
	}

	// CountDownLatch(2) + await(10s)：够 2 个可达或超时即往下走，不等全部完成
	got := 0
	deadline := time.After(stunProbeLatchWaitMs * time.Millisecond)
	for got < stunProbeLatchNeed {
		select {
		case <-reachableCh:
			got++
		case <-deadline:
			got = stunProbeLatchNeed
		}
	}

	all := make([]stunServerResult, 0, len(urls))
	mu.Lock()
	for _, r := range results {
		if r == nil {
			// Java: f.getNow(new StunServerResult("", "", 0, false, -1L, null, 0))（:642）
			all = append(all, stunServerResult{Reachable: false, LatencyMs: -1})
			continue
		}
		all = append(all, *r)
	}
	mu.Unlock()
	// 未完成的探测让 goroutine 自行收尾（等价 Java 的守护线程池 + getNow 占位），
	// 本函数不阻塞等它们，因此不 wg.Wait()。

	natType := stunDetectNatType(all)
	reachableCount := 0
	var reachableURLs []string
	for _, r := range all {
		if r.Reachable {
			reachableCount++
			reachableURLs = append(reachableURLs, r.URL)
		}
	}
	stunProbeLog("info", "NAT=%s, reachable=%d/%d", natType.key(), reachableCount, len(all))
	return &stunProbeResult{NatType: natType, Servers: all, ReachableStunURLs: reachableURLs}
}

// stunProbeAsync = probeAsync（:576-600）：后台探测 + 热缓存 + 磁盘缓存落盘。
func stunProbeAsync(groups [][]string) *stunProbeFuture {
	f := newStunProbeFuture()
	go func() {
		result := stunProbeServerGroups(groups)
		stunSetCachedResult(result)
		if len(result.Servers) > 0 {
			var saveURLs []string
			var save *stunServerResult
			for i := range result.Servers {
				r := result.Servers[i]
				if r.Reachable {
					saveURLs = append(saveURLs, r.URL)
					if save == nil && r.MappedIP != "" && r.MappedPort > 0 {
						save = &result.Servers[i]
					}
				}
			}
			if save != nil {
				stunCacheSave(result.NatType.key(), save.MappedIP, save.MappedPort, saveURLs)
			}
		}
		f.complete(result)
	}()
	return f
}

// stunProbeNatType = probeNatType（:700-708）：探测失败返回 unknown（Java 返回 null，
// Go 用零值 natTypeUnknown 表达"未判定"，调用处已按 key=="unknown" 处理）。
func stunProbeNatType(groups [][]string) stunNatType {
	return stunProbeServerGroups(groups).NatType
}

// ---- 热缓存 / 网络变更（:538-574）----

var (
	stunWarmMu        sync.Mutex
	stunWarmResult    *stunProbeResult
	stunWarmStampMs   int64
	stunLastIPMu      sync.Mutex
	stunLastProbeIP   string
	stunLastIPSetOnce bool
)

// stunGetCachedResult = getCachedResult（:567-570）：只认 20s 内的上一次探测结果。
func stunGetCachedResult() *stunProbeResult {
	stunWarmMu.Lock()
	defer stunWarmMu.Unlock()
	if stunWarmResult != nil && time.Now().UnixMilli()-stunWarmStampMs < stunWarmReuseTtlMs {
		return stunWarmResult
	}
	return nil
}

// stunSetCachedResult = setCachedResult（:572-574）。
func stunSetCachedResult(result *stunProbeResult) {
	stunWarmMu.Lock()
	stunWarmResult = result
	stunWarmStampMs = time.Now().UnixMilli()
	stunWarmMu.Unlock()
}

// stunInvalidateCache = invalidateCache（:542-545）：热缓存 + 磁盘缓存一起清。
func stunInvalidateCache() {
	stunWarmMu.Lock()
	stunWarmResult = nil
	stunWarmStampMs = 0
	stunWarmMu.Unlock()
	stunCacheClear()
}

// stunLastProbeLocalIP = getLastProbeLocalIp（:538-540）。
func stunLastProbeLocalIP() string {
	stunLastIPMu.Lock()
	defer stunLastIPMu.Unlock()
	return stunLastProbeIP
}

func stunSetLastProbeLocalIP(ip string) {
	stunLastIPMu.Lock()
	stunLastProbeIP = ip
	stunLastIPSetOnce = true
	stunLastIPMu.Unlock()
}

// stunNetworkChanged = isNetworkChanged（:547-556）。
// 源码 M7 注释（:550-551）是有意保留的修正：detectLocalIp 失败返回 "unknown"，
// 若 last 也是 "unknown" 直接短路会让"未知→已知"这一变化信号永远丢失，
// 因此 last 为空/unknown 时，只要当前拿到真 IP 就算变更；一直是 unknown 不算。
func stunNetworkChanged() bool {
	stunLastIPMu.Lock()
	last := stunLastProbeIP
	known := stunLastIPSetOnce
	stunLastIPMu.Unlock()
	current := stunDetectLocalIP()
	if !known || last == "" || last == "unknown" {
		return current != "unknown"
	}
	return last != current
}

// stunDetectLocalIP = detectLocalIp（:558-565）：连 8.8.8.8:53 取路由选出的源地址，失败 "unknown"。
func stunDetectLocalIP() string {
	conn, err := net.Dial("udp", "8.8.8.8:53")
	if err != nil {
		return "unknown"
	}
	defer conn.Close()
	addr, ok := conn.LocalAddr().(*net.UDPAddr)
	if !ok {
		return "unknown"
	}
	return addr.IP.String()
}

// ---- RFC5780 细分（detectNatTypeWithRfc5780 :768-846 / sendChangeRequest :735-766）----

// stunSendChangeRequest 发 CHANGE-REQUEST；返回响应来源地址即"服务器按我们要求换了源"，
// 返回 ok=false 表示无响应/错误响应/事务不匹配（Java 全部返回 null）。
func stunSendChangeRequest(sk *stunSocket, stunHost string, stunPort int, changeIP, changePort bool) (*net.UDPAddr, bool) {
	raddr, err := stunResolveHost(stunHost, stunPort)
	if err != nil {
		stunProbeLog("debug", "CHANGE-REQUEST failed: %s", err.Error())
		return nil, false
	}
	req := stunBuildRequestWithChange(changeIP, changePort)
	if err := sk.send(req, raddr); err != nil {
		stunProbeLog("debug", "CHANGE-REQUEST failed: %s", err.Error())
		return nil, false
	}
	buf := make([]byte, stunRecvBufLen)
	n, from, err := sk.recv(buf)
	if err != nil {
		return nil, false // SocketTimeoutException → null（:745-747）
	}
	resp := append([]byte(nil), buf[:n]...)
	if len(resp) >= 20 && stunMatchTransaction(resp, req) {
		typ := int(resp[0])<<8 | int(resp[1])
		if typ == stunErrorResponseType {
			stunProbeLog("info", "STUN error response, RFC5780 not supported")
			return nil, false
		}
		if typ != stunBindingSuccess {
			return nil, false
		}
		return from, true
	}
	return nil, false
}

// stunTryRecvMapped = tryRecvMapped（:1513-1529）：读超时 800ms，收尾**硬**恢复 8000ms（:1521）。
// 这个"恢复成写死值而不是保存值"是源码 quirk：调用它之后，外层 RFC5780 序列的 800ms 变成 8000ms。
func stunTryRecvMapped(sk *stunSocket, req []byte, raddr *net.UDPAddr) *stunMappedAddr {
	buf := make([]byte, stunRecvBufLen)
	sk.setSoTimeout(stunTryRecvSoToMs * time.Millisecond)
	n, _, err := sk.recv(buf)
	sk.setSoTimeout(stunTryRecvRestoreMs * time.Millisecond)
	if err != nil {
		return nil
	}
	resp := append([]byte(nil), buf[:n]...)
	m, perr := stunParseResponse(resp, req)
	if perr != nil {
		return nil
	}
	return m
}

// stunDetectNatTypeRFC5780 = detectNatTypeWithRfc5780（:768-846）。ok=false 表示"未判定/回落 delta 法"。
func stunDetectNatTypeRFC5780(sk *stunSocket, stunHost string, stunPort, altStunPort int) (stunNatType, bool) {
	// Java 入口保存 originalTimeout 并在 finally 恢复（:769-773/:838-844），
	// 中途 tryRecvMapped 把套接字超时硬改成 8000 也照样被这里恢复回入口值。
	original := sk.soTimeout
	defer sk.setSoTimeout(original)
	sk.setSoTimeout(stunRfc5780SoToMs * time.Millisecond)

	addr, err := stunResolveHost(stunHost, stunPort)
	if err != nil {
		return natTypeUnknown, false
	}
	altAddr, err := stunResolveHost(stunHost, altStunPort)
	if err != nil {
		return natTypeUnknown, false
	}

	req1 := stunBuildRequest()
	if err := sk.send(req1, addr); err != nil {
		return natTypeUnknown, false
	}
	buf := make([]byte, stunRecvBufLen)
	n, _, err := sk.recv(buf)
	if err != nil {
		if stunIsTimeout(err) {
			stunProbeLog("info", "RFC5780 Step1 no response, fallback")
		} else {
			// 非超时的 IO 异常走外层 catch（:835-837），日志文案与 Java 对齐
			stunProbeLog("debug", "RFC5780 exception: %s", err.Error())
		}
		return natTypeUnknown, false
	}
	resp1 := append([]byte(nil), buf[:n]...)
	m1, perr := stunParseResponse(resp1, req1)
	if perr != nil || m1 == nil {
		stunProbeLog("info", "RFC5780 Step1 parse failed, fallback")
		return natTypeUnknown, false
	}

	if _, ok := stunSendChangeRequest(sk, stunHost, stunPort, false, true); ok {
		req3 := stunBuildRequest()
		if err := sk.send(req3, altAddr); err != nil {
			return natTypeUnknown, false
		}
		buf3 := make([]byte, stunRecvBufLen)
		_, _, err := sk.recv(buf3)
		if err == nil {
			stunProbeLog("info", "RFC5780 determined FullCone (CHANGE-REQUEST success + altPort response)")
			return natTypeFullCone, true
		}
		if !stunIsTimeout(err) {
			// Java: 非 SocketTimeoutException 冒泡到外层 catch → 返回 null（:835-837）
			stunProbeLog("debug", "RFC5780 exception: %s", err.Error())
			return natTypeUnknown, false
		}
		stunProbeLog("info", "RFC5780 determined RestrictedCone (CHANGE-REQUEST success + altPort no response)")
		return natTypeRestrictedCone, true
	}

	reqSym := stunBuildRequest()
	if err := sk.send(reqSym, altAddr); err != nil {
		return natTypeUnknown, false
	}
	mSym := stunTryRecvMapped(sk, reqSym, altAddr)
	if mSym != nil && mSym.Port != m1.Port {
		stunProbeLog("info", "RFC5780 detected symmetric NAT (%d vs %d), fallback to delta method", m1.Port, mSym.Port)
		return natTypeUnknown, false
	}

	reqRe := stunBuildRequest()
	if err := sk.send(reqRe, addr); err != nil {
		return natTypeUnknown, false
	}
	mRe := stunTryRecvMapped(sk, reqRe, addr)
	if mRe != nil && mRe.Port != m1.Port && mRe.Port != 0 {
		stunProbeLog("info", "RFC5780 re-sample main port: %d -> %d, symmetric NAT, fallback to delta method", m1.Port, mRe.Port)
		return natTypeUnknown, false
	}
	stunProbeLog("info", "RFC5780 determined PortRestricted (CHANGE-REQUEST no response, non-symmetric)")
	return natTypePortRestrictedCone, true
}

// ---- 主判定：RFC5780 → 两服务器 delta → 异端口确认 → 分级（detectNatType :848-1020）----

func stunDetectNatType(results []stunServerResult) stunNatType {
	var reachable []stunServerResult
	for _, r := range results {
		if r.Reachable && r.MappedIP != "" {
			reachable = append(reachable, r)
		}
	}
	if len(reachable) < 2 {
		stunProbeLog("info", "Not enough reachable servers (%d), cannot determine NAT type", len(reachable))
		return natTypeUnknown
	}

	uc, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4zero, Port: 0})
	if err != nil {
		stunProbeLog("debug", "NAT detection failed: %s", err.Error())
		return natTypeUnknown
	}
	defer uc.Close()
	sk := newStunSocket(uc, stunProbeTimeoutMs*time.Millisecond)

	tries := 0
	for _, s := range reachable {
		if tries >= stunRfc5780MaxTries {
			break
		}
		tries++
		if t, ok := stunDetectNatTypeRFC5780(sk, s.Host, s.Port, s.Port+1); ok {
			stunProbeLog("info", "RFC5780 determined: %s -> %s", s.URL, t.key())
			return t
		}
	}
	stunProbeLog("info", "RFC5780 all failed (%d tried), fallback to two-server delta method", tries)

	first, second := reachable[0], reachable[1]
	firstAddr, err := stunResolveHost(first.Host, first.Port)
	if err != nil {
		return natTypeUnknown
	}
	secondAddr, err := stunResolveHost(second.Host, second.Port)
	if err != nil {
		return natTypeUnknown
	}
	req1 := stunBuildRequest()
	req2 := stunBuildRequest()
	sk.setSoTimeout(stunDeltaWindowMs * time.Millisecond)
	if err := sk.send(req1, firstAddr); err != nil {
		return natTypeUnknown
	}
	if err := sk.send(req2, secondAddr); err != nil {
		return natTypeUnknown
	}

	var mapped1, mapped2 *stunMappedAddr
	buf := make([]byte, stunRecvBufLen)
	deadline := time.Now().Add(stunDeltaWindowMs * time.Millisecond)

	// 只读两包（Java `for (int i = 0; i < 2 && now < deadline; i++)`）：
	// 两包都命中才算得出，否则 UNKNOWN —— 这是源码行为，不做"多读几包"的加固。
	for i := 0; i < 2 && time.Now().Before(deadline); i++ {
		n, _, err := sk.recv(buf)
		if err != nil {
			if stunIsTimeout(err) {
				break
			}
			return natTypeUnknown
		}
		respData := append([]byte(nil), buf[:n]...)
		if mapped1 == nil {
			if ma, e1 := stunParseResponse(respData, req1); e1 == nil {
				mapped1 = ma
			} else if ma, e2 := stunParseResponse(respData, req2); e2 == nil {
				mapped2 = ma
			}
		} else {
			if ma, e2 := stunParseResponse(respData, req2); e2 == nil {
				mapped2 = ma
			} else if ma, e1 := stunParseResponse(respData, req1); e1 == nil {
				mapped1 = ma
			}
		}
	}

	if mapped1 == nil || mapped2 == nil {
		return natTypeUnknown
	}
	if mapped1.IP == mapped2.IP && mapped1.Port == mapped2.Port {
		stunProbeLog("info", "Non-symmetric NAT, mapped addresses same (%s:%d)", mapped1.IP, mapped1.Port)
		return natTypePortRestrictedCone
	}

	stunProbeLog("info", "Mapped differs across servers (%s:%d vs %s:%d), confirm symmetric via same-server alt-port",
		mapped1.IP, mapped1.Port, mapped2.IP, mapped2.Port)

	symConfirmed := false
	for alt := 1; alt <= stunAltPortMaxTries && !symConfirmed; alt++ {
		altPort := first.Port + alt
		if altPort > 65535 {
			break
		}
		altAddr, err := stunResolveHost(first.Host, altPort)
		if err != nil {
			break // Java: 解析/发送异常 → break 整个确认循环（:965-967）
		}
		reqAlt := stunBuildRequest()
		if err := sk.send(reqAlt, altAddr); err != nil {
			break
		}
		bufAlt := make([]byte, stunRecvBufLen)
		n, _, err := sk.recv(bufAlt)
		if err != nil {
			if stunIsTimeout(err) {
				continue
			}
			break
		}
		respAlt := append([]byte(nil), bufAlt[:n]...)
		if mAlt, perr := stunParseResponse(respAlt, reqAlt); perr == nil {
			stunProbeLog("info", "Same-server alt-port %s:%d mapped %s:%d", first.Host, altPort, mAlt.IP, mAlt.Port)
			if mAlt.Port != mapped1.Port {
				symConfirmed = true
			}
		}
	}

	if !symConfirmed {
		stunProbeLog("info", "Same-server alt-port not confirmed, treat as PortRestricted (per-destination mapping stable)")
		return natTypePortRestrictedCone
	}

	stunProbeLog("info", "Confirmed symmetric NAT (same-server alt-port differs), determine direction")
	if t, ok := stunGradeSymmetricSeverity(sk, reachable, mapped1.Port); ok {
		return t
	}
	if t, ok := stunDetectEasySymmetric(sk, mapped1.Port, reachable); ok {
		return t
	}

	// 两样本兜底（:984-1008）
	twoDiff := mapped2.Port - mapped1.Port
	absDiff := twoDiff
	if absDiff < 0 {
		absDiff = -absDiff
	}
	if mapped1.IP != mapped2.IP {
		stunProbeLog("info", "EasySym fallback: public IP differs (%s vs %s), determined HardSym", mapped1.IP, mapped2.IP)
		return natTypeSymmetric
	}
	// 源码写死 `mapped1.port > 0 && mapped2.port > 0 ? 100 : 200`（:990）：兜底 200 只在端口非法时生效
	threshold := stunAltPortFallbackT
	if mapped1.Port > 0 && mapped2.Port > 0 {
		threshold = stunAltPortThresholdT
	}
	if absDiff > 0 && absDiff < threshold {
		fallback := natTypeSymmetricEasyDec
		if twoDiff > 0 {
			fallback = natTypeSymmetricEasyInc
		}
		stunProbeLog("info", "EasySym fallback (3-sample failed): %d->%d diff=%d, threshold=%d, determined %s",
			mapped1.Port, mapped2.Port, twoDiff, threshold, fallback.key())
		return fallback
	}
	stunProbeLog("info", "EasySym fallback: %d->%d diff=%d>=%d, threshold=%d, determined HardSym",
		mapped1.Port, mapped2.Port, absDiff, threshold, threshold)
	return natTypeSymmetric
}

// stunGradeSymmetricSeverity = gradeSymmetricSeverity（:1023-1095）：
// 多服务器竞速取样本 → span≤15 判"易"，再用 extra bind 的端口增量定方向；ok=false 走旧路径。
func stunGradeSymmetricSeverity(sk *stunSocket, reachable []stunServerResult, refPort int) (stunNatType, bool) {
	var urls []string
	for _, r := range reachable {
		urls = append(urls, r.URL)
	}
	n := stunSymGradeSampleServers
	if len(urls) < n {
		n = len(urls)
	}
	if n < 2 {
		return natTypeUnknown, false
	}

	samples := stunDiscoverMappedAddressRace(sk.uc, urls[:n], n)
	var ports []int
	for _, s := range samples {
		if s != nil && s.Port > 0 {
			ports = append(ports, s.Port)
		}
	}
	if len(ports) < 2 {
		stunProbeLog("info", "Sym grade: only %d/%d samples, fallback to legacy path", len(ports), n)
		return natTypeUnknown, false
	}

	minP, maxP := ports[0], ports[0]
	for _, p := range ports {
		if p < minP {
			minP = p
		}
		if p > maxP {
			maxP = p
		}
	}
	span := maxP - minP
	stunProbeLog("info", "Sym grade: samples=%v, span=%d (easy<=%d)", ports, span, stunSymGradeSpanEasyMax)
	if span > stunSymGradeSpanEasyMax {
		return natTypeHardSym, true
	}

	extra := stunDiscoverMappedAddress(sk.uc, []string{urls[0]})
	if extra == nil || extra.Port <= 0 {
		stunProbeLog("info", "Sym grade: extra bind failed, determined HardSym")
		return natTypeHardSym, true
	}
	delta := extra.Port - refPort
	stunProbeLog("info", "Sym grade extra bind: ref=%d, extra=%d, delta=%d, threshold=%d",
		refPort, extra.Port, delta, stunEasySymPortDeltaTh)
	if delta > 0 && delta < stunEasySymPortDeltaTh {
		return natTypeEasySymInc, true
	}
	if delta < 0 && delta > -stunEasySymPortDeltaTh {
		return natTypeEasySymDec, true
	}
	if delta == 0 {
		return natTypeEasySymInc, true
	}
	return natTypeHardSym, true
}

// stunDetectEasySymmetric = detectEasySymmetric（:1097-1158）：第三样本（第 3 台，没有则复用第 1 台）
// 与首样本端口差定方向；ok=false 表示未判定（Java 返回 null）。
func stunDetectEasySymmetric(sk *stunSocket, basePort int, reachable []stunServerResult) (stunNatType, bool) {
	var third stunServerResult
	switch {
	case len(reachable) >= 3:
		third = reachable[2]
	case len(reachable) < 1:
		stunProbeLog("info", "EasySym detection: no STUN available, skip 3rd sample")
		return natTypeUnknown, false
	default:
		third = reachable[0]
		stunProbeLog("info", "EasySym detection: only %d reachable STUN, reuse %s:%d for 3rd sample", len(reachable), third.Host, third.Port)
	}

	sk.setSoTimeout(stunSymDetectTimeoutMs * time.Millisecond)
	thirdAddr, err := stunResolveHost(third.Host, third.Port)
	if err != nil {
		return natTypeUnknown, false
	}
	req := stunBuildRequest()
	if err := sk.send(req, thirdAddr); err != nil {
		return natTypeUnknown, false
	}
	buf := make([]byte, stunRecvBufLen)
	n, _, err := sk.recv(buf)
	if err != nil {
		stunProbeLog("debug", "EasySym detection failed: %s", err.Error())
		return natTypeUnknown, false
	}
	respData := append([]byte(nil), buf[:n]...)
	extra, perr := stunParseResponse(respData, req)
	if perr != nil || extra == nil {
		return natTypeUnknown, false
	}

	diff := extra.Port - basePort
	absDiff := diff
	if absDiff < 0 {
		absDiff = -absDiff
	}
	// 同样是写死的 `basePort>0 && extraPort>0 ? 100 : 200`（:1126）
	threshold := stunEasySymPortDeltaFallbk
	if basePort > 0 && extra.Port > 0 {
		threshold = stunEasySymPortDeltaTh
	}
	stunProbeLog("info", "EasySym detection (same socket 3rd server): basePort=%d, extraPort=%d, diff=%d, threshold=%d",
		basePort, extra.Port, diff, threshold)
	if diff > 0 && diff < threshold {
		stunProbeLog("info", "EasySym increment (port+%d, threshold=%d)", diff, threshold)
		return natTypeSymmetricEasyInc, true
	}
	if diff < 0 && diff > -threshold {
		stunProbeLog("info", "EasySym decrement (port%d, threshold=%d)", diff, threshold)
		return natTypeSymmetricEasyDec, true
	}
	if absDiff >= threshold {
		stunProbeLog("info", "EasySym detection: diff=%d>=%d, determined HardSym", absDiff, threshold)
		return natTypeSymmetric, true
	}
	stunProbeLog("info", "EasySym detection: diff=0 (NAT reuses port), still EasySym")
	return natTypeSymmetricEasyInc, true
}

// ---- 顺序端口采样（samplePortsSequential :1349-1511）----

// stunSamplePortsSequential 先对全部 STUN 竞速取第一台应答者，随后**只**对那一台
// 按 intervalMs 间隔顺序采 count-1 次（每次 800ms 窗口、最多 3 发）。
// 注意与 stun.go 的 stunSamplePorts 不同：那个是"服务器轮转、无间隔"，不等同于本函数。
func stunSamplePortsSequential(uc *net.UDPConn, stunURLs []string, count, intervalMs int) []int {
	if len(stunURLs) == 0 {
		return nil
	}
	var ports []int
	sk := newStunSocket(uc, stunRecvPollMs*time.Millisecond)

	selectedHost := ""
	selectedPort := stunDefaultPort
	n := len(stunURLs)
	parsed := make([]stunParsedURL, n)
	reqs := make([][]byte, n)
	addrs := make([]*net.UDPAddr, n)

	// Java 把"逐台发送 + 竞速取第一答"整体包在同一个 try 里（:1366-1429）：
	// 解析或发送的 IO 异常会终止整个选择阶段（catch 只记一条 debug），本端用 aborted 标记同义实现。
	aborted := false
	for i := 0; i < n && !aborted; i++ {
		p, ok := stunParseURL(stunURLs[i])
		if !ok {
			continue
		}
		parsed[i] = p
		reqs[i] = stunBuildRequest()
		a, err := stunResolveHost(p.host, p.port)
		if err != nil {
			stunProbeLog("debug", "P-PRE race exception: %s", err.Error())
			aborted = true
			break
		}
		addrs[i] = a
		if err := sk.send(reqs[i], a); err != nil {
			stunProbeLog("debug", "P-PRE race exception: %s", err.Error())
			aborted = true
			break
		}
	}

	buf := make([]byte, stunRecvBufLen)
	raceStart := time.Now()
	deadline := raceStart.Add(stunDiscoverTimeoutMs * time.Millisecond)
	nextResend := raceStart.Add(time.Duration(stunResendDelay(1)) * time.Millisecond)

	for !aborted && selectedHost == "" && time.Now().Before(deadline) {
		now := time.Now()
		if !now.Before(nextResend) {
			for i := 0; i < n; i++ {
				if reqs[i] != nil {
					if err := sk.send(reqs[i], addrs[i]); err != nil {
						stunProbeLog("debug", "P-PRE race exception: %s", err.Error())
						aborted = true
						break
					}
				}
			}
			nextResend = stunNoResend
		}
		if aborted {
			break
		}
		rd, _, err := sk.recv(buf)
		if err != nil {
			if stunIsTimeout(err) {
				continue
			}
			stunProbeLog("debug", "P-PRE race exception: %s", err.Error())
			aborted = true
			break
		}
		respData := append([]byte(nil), buf[:rd]...)
		if len(respData) >= 20 {
			for i := 0; i < n; i++ {
				if reqs[i] != nil && stunMatchTransaction(respData, reqs[i]) {
					if ma, perr := stunParseResponse(respData, reqs[i]); perr == nil {
						selectedHost = parsed[i].host
						selectedPort = parsed[i].port
						ports = append(ports, ma.Port)
						stunProbeLog("info", "P-PRE race selected STUN: %s:%d, first port=%d", selectedHost, selectedPort, ma.Port)
					}
					break
				}
			}
		}
	}

	if selectedHost == "" {
		stunProbeLog("warn", "P-PRE race no response, sampling failed")
		return ports
	}

	sk.setSoTimeout(stunRecvPollMs * time.Millisecond)
	for i := 1; i < count; i++ {
		req := stunBuildRequest()
		// 解析在每次采样的 try 内（:1451）：DNS 抖动只打掉当次采样，不影响后续
		selAddr, err := stunResolveHost(selectedHost, selectedPort)
		if err != nil {
			stunProbeLog("debug", "P-PRE sample #%d/%d failed: %s", i+1, count, err.Error())
			continue
		}
		sendPktErr := sk.send(req, selAddr)
		if sendPktErr != nil {
			stunProbeLog("debug", "P-PRE sample #%d/%d failed: %s", i+1, count, sendPktErr.Error())
			continue
		}
		sampleStart := time.Now()
		sampleDeadline := sampleStart.Add(stunDiscoverTimeoutMs * time.Millisecond)
		sendCount := 1
		resendAt := sampleStart.Add(time.Duration(stunResendDelay(1)) * time.Millisecond)
		recvBuf := make([]byte, stunRecvBufLen)
		sampleFailed := false
		for time.Now().Before(sampleDeadline) {
			now := time.Now()
			if !now.Before(resendAt) && sendCount <= stunMaxRetransmissions {
				if err := sk.send(req, selAddr); err != nil {
					stunProbeLog("debug", "P-PRE sample #%d/%d failed: %s", i+1, count, err.Error())
					sampleFailed = true
					break
				}
				sendCount++
				if sendCount <= stunMaxRetransmissions {
					resendAt = now.Add(time.Duration(stunResendDelay(sendCount)) * time.Millisecond)
				} else {
					resendAt = stunNoResend
				}
			}
			nn, _, err := sk.recv(recvBuf)
			if err != nil {
				if stunIsTimeout(err) {
					continue
				}
				stunProbeLog("debug", "P-PRE sample #%d/%d failed: %s", i+1, count, err.Error())
				sampleFailed = true
				break
			}
			respData := append([]byte(nil), recvBuf[:nn]...)
			if len(respData) >= 20 {
				if ma, perr := stunParseResponse(respData, req); perr == nil {
					ports = append(ports, ma.Port)
					break
				}
			}
		}
		if sampleFailed {
			// Java 的异常 catch 覆盖了循环末尾的 sleep，故失败那一次不排间隔（:1494-1496）
			continue
		}
		if i < count-1 {
			time.Sleep(time.Duration(intervalMs) * time.Millisecond)
		}
	}
	return ports
}

// ---- 调用侧派生：可达优先重排（ConnectionManager.java:947-963）----

// stunUrlsProbedFirst = stunUrlsProbedFirst：把本会话实测可达的 URL 前置，
// 静态表前几位是境内服务器，海外网络下 quad/兜底探测每轮白烧 4×800ms。
func stunUrlsProbedFirst(groups [][]string, probe *stunProbeResult) []string {
	var urls []string
	for _, g := range groups {
		urls = append(urls, g...)
	}
	if probe != nil {
		for _, u := range probe.ReachableStunURLs {
			rest := urls[:0]
			for _, x := range urls {
				if x != u {
					rest = append(rest, x)
				}
			}
			urls = append([]string{u}, rest...)
		}
	}
	return urls
}

// stunServerGroupsFromDetector = StunDetector.getStunServerGroups()（StunDetector.java:13-27）。
// 模组是 13 组、每组 1 台；app 现有 stun.go:28 的 stunServers 是另一份 6 台列表，两者不同源，
// 接线时按本表提供探测分组（详见交付报告的分歧条目）。
func stunServerGroupsFromDetector() [][]string {
	return [][]string{
		{"stun:stun.miwifi.com"},
		{"stun:stun.hitv.com"},
		{"stun:stun.chat.bilibili.com"},
		{"stun:stun.l.google.com:19302"},
		{"stun:stun1.l.google.com:19302"},
		{"stun:stun2.l.google.com:19302"},
		{"stun:stun3.l.google.com:19302"},
		{"stun:stun4.l.google.com:19302"},
		{"stun:stun.nextcloud.com"},
		{"stun:stun.nfon.net"},
		{"stun:stun.freeswitch.org"},
		{"stun:stun.sipnet.com"},
		{"stun:stun.voipbuster.com"},
	}
}

// stunAllURLs = StunDetector.getAllStunUrls()（StunDetector.java:52-54）。
func stunAllURLs() []string {
	var out []string
	for _, g := range stunServerGroupsFromDetector() {
		out = append(out, g...)
	}
	return out
}
