package main

// fed_connect.go — 房客侧互通直连（source=="happylink" 的房）。
//
// 权威来源：
//   - room/ConnectionManager.java:10248-10274 connectFed(RoomState)
//   - network/P2PBridge.java:155-264 connectToHost / connectToHostWithRetry
//     :468-538 acceptJoinerConnectionPreconnected
//     :654-705 tryConnectWithRetry
//     :707-752 bridge（60s 读超时 + keepalive 的搬运单元）
//     :768-806 disconnect、:1735-1760 BridgePair
//   - room/ConnectionManager.java:14559-14565 —— 互通房"不吃陶瓦竞速"：join 回包即带 fedAddress，
//     直连 HL 中继，app 侧没有 Terracotta 通道，成功即等价于竞速胜出（connectionWon）。
//
// 完全绕开打洞/TURN/玩家中继：没有 holepunch_offer、没有 STUN、没有 rudp、没有中继节点分配，
// 只做"本地 127.0.0.1 监听 ↔ 中继 host:port 的纯 TCP 转发"。

import (
	"errors"
	"net"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// phaseFed 是 conn:state 的互通阶段名（前端需要 fed 的 label/trying 文案，见移植报告 hunk）。
const phaseFed = "fed"

// fed_setup / fed_failed：模组走 lang 键（ConnectionManager.java:10252,10260,10263,10271 →
// voxlink.connection.fed_setup / fed_failed），app 前端没有同一套 lang 目录，
// 这里直接用模组 lang 文件里同键的原文。
const (
	fedTextSetup  = "正在通过互通中继连接房间..."
	fedTextFailed = "互通房间连接失败"
)

// 搬运自 P2PBridge 的常量表（:28-45），逐个带行号。
const (
	fedJoinerConnectTimeout = 10 * time.Second       // :29 CONNECT_TIMEOUT（:681 用在 connect 上）
	fedJoinerInnerAttempts  = 3                      // :659 attempt = 1..3
	fedJoinerInnerRetryStep = 500 * time.Millisecond // :30 RETRY_DELAY_MS（:695 sleep(500*attempt)）
	fedJoinerOuterRetries   = 3                      // :232 result<=0 && attempt < 3（含首共 4 轮）
	fedJoinerOuterRetryStep = 1 * time.Second        // :239 sleep(1000L*(attempt+1)) → 1/2/3s
	fedJoinerBacklog        = 50                     // :209 ServerSocket(0, 50, "127.0.0.1") 的 backlog
	fedBridgeBufferSize     = 32768                  // :28 BUFFER_SIZE（:220-221,666-667 收发缓冲、:720 拷贝缓冲）
	fedBridgeReadTimeout    = 60 * time.Second       // :33 KEEPALIVE_SOCKET_TIMEOUT_MS（:711 setSoTimeout）
	fedAcceptPollTimeout    = 10 * time.Second       // :470 js.setSoTimeout(10000)
)

// ---- 桥对象 ----

// fedPair 对应 P2PBridge.BridgePair（:1735-1760）：一条 MC 客户端 ↔ 一条中继连接，成对关闭。
type fedPair struct {
	client net.Conn
	host   net.Conn
	closed atomic.Bool
}

func (p *fedPair) close() {
	if p.closed.CompareAndSwap(false, true) {
		closeQuietlyConn(p.client)
		closeQuietlyConn(p.host)
	}
}

// fedBridge 是模组那个静态 P2PBridge 的 joiner 侧在 app 里的实例（一份互通桥）。
// mu 对应 Java 的 synchronized (P2PBridge.class)。
type fedBridge struct {
	logf func(level, msg string)

	running   atomic.Bool // :46 running
	cancelled atomic.Bool // :47 cancelled

	mu              sync.Mutex
	joinerLn        net.Listener          // :50 joinerServer
	joinerPort      int                   // :51 joinerPort（Java 初值 -1）
	currentHost     string                // :55 currentHostIp
	currentHostPort int                   // :56 currentHostPort
	pairs           map[*fedPair]struct{} // :53 activePairs
}

// errNoFedLocalAddr 本地桥监听地址不可用（异常形态，正常 ListenTCP 不会走到）。
var errNoFedLocalAddr = errors.New("互通 joiner：本地桥地址不可用")

func newFedBridge(logf func(level, msg string)) *fedBridge {
	return &fedBridge{
		logf:       logf,
		joinerPort: -1, // :51 joinerPort 初值 -1
		pairs:      map[*fedPair]struct{}{},
		// currentHostIp/currentHostPort 是 Java 的普通 static 字段（:55-56），零值起步
	}
}

// sleepInterruptible 复刻 Java 的 Thread.sleep + "cancelled 即短路"：
// connectToHost 会把 cancelled 复位为 false（:156），所以这里按标志轮询、不用一次性关闭的通道，
// 免得同一 App 上的第二次互通加入被上一轮的断开永久卡死。返回 false 表示被 cancelled 打断
// （对应 :240-242、:696-699 的 cancelled/InterruptedException → -1/null）。
func (b *fedBridge) sleepInterruptible(d time.Duration) bool {
	deadline := time.Now().Add(d)
	for {
		if b.cancelled.Load() {
			return false
		}
		remain := time.Until(deadline)
		if remain <= 0 {
			return true
		}
		if remain > 100*time.Millisecond {
			remain = 100 * time.Millisecond
		}
		time.Sleep(remain)
	}
}

func (b *fedBridge) bridgeLog(level, msg string) {
	if b.logf != nil {
		b.logf(level, msg)
	}
}

// ---- 登记表（一 App 一份桥，语义等同模组的静态单例） ----

type fedJoinerRegistry struct {
	mu sync.Mutex
	m  map[*App]*fedBridge
}

func (r *fedJoinerRegistry) getOrCreate(a *App, logf func(level, msg string)) *fedBridge {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.m == nil {
		r.m = make(map[*App]*fedBridge)
	}
	b, ok := r.m[a]
	if !ok {
		b = newFedBridge(logf)
		r.m[a] = b
	}
	return b
}

func (r *fedJoinerRegistry) get(a *App) *fedBridge {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.m[a]
}

var fedJoiners = &fedJoinerRegistry{}

// fedJoinerForApp 测试/诊断用。
func fedJoinerForApp(a *App) *fedBridge { return fedJoiners.get(a) }

// ---- connectFed（ConnectionManager.java:10248-10274） ----

// connectFedIfHappyLink 对应 RoomManager.java:827-830：join 成功后若 source=="happylink"，
// 记录一行 fedAddress 日志并直接走 connectFed（在心跳/信令/topology 都起好之后，:824-826）。
func (a *App) connectFedIfHappyLink() {
	room := a.snapshotRoom()
	if !roomIsHappyLink(room) {
		return
	}
	a.netLog("info", "[joinRoom] Relay-tunnel room: fedAddress="+roomFedAddress(room)) // :828
	a.engine.connectFed(room)                                                          // :829
}

// connectFed 逐句对应 ConnectionManager.java:10248-10274。
// fedAddress 由服务端产出为 host + ":" + port（server-go/hlmaster.go:1431），
// 解析刻意用 LastIndex(':')（:10250）以容忍 IPv6 形态的字面地址。
func (e *connEngine) connectFed(room map[string]any) {
	addr := roomFedAddress(room)        // :10249 getFedAddress()
	sep := strings.LastIndex(addr, ":") // :10250
	if sep <= 0 || sep == len(addr)-1 { // :10251
		e.notifyState(phaseFed, statusFailed, "", fedTextFailed) // :10252 fed_failed(fatal)
		return
	}
	host := addr[:sep]                      // :10255
	port, err := strconv.Atoi(addr[sep+1:]) // :10258
	if err != nil {
		e.notifyState(phaseFed, statusFailed, "", fedTextFailed) // :10260
		return
	}
	e.notifyState(phaseFed, statusTrying, "", fedTextSetup) // :10263 fed_setup（非 fatal）

	go func() { // :10264 P2PBridge.connectToHost(...).thenAccept(localPort -> ...)
		localPort, localAddr := e.fedConnectToHost(host, port)
		if localPort > 0 {
			// :10266-10267 connectionWon / markConnectionEstablished：
			// 互通成功即通道胜出；app 侧无陶瓦/P2P 竞速并发（规格 §5.5），不再发起其它通道即为胜出。
			e.app.netLog("info", "互通中继链路已建立")
			// :10268 ConnectionHelper.connectToServer(localPort, roomInfo)：
			// app 不能替 MC 点"加入"，与 tunnel 类路径一致地把本地桥地址交给玩家填进 MC。
			e.notifyState(phaseFed, statusSuccess, localAddr, "")
		} else {
			// :10270-10271 resetConnecting + fed_failed(fatal)
			e.notifyState(phaseFed, statusFailed, "", fedTextFailed)
		}
	}()
}

// ---- P2PBridge.connectToHost 的移植 ----

// fedConnectToHost 建/复用 joiner 桥，返回本地桥端口（<=0 失败）与"要填进 MC 的地址"。
func (e *connEngine) fedConnectToHost(host string, port int) (int, string) {
	b := fedJoiners.getOrCreate(e.app, e.app.netLog)
	// 会话拆除即断桥：模组的 P2PBridge.disconnect() 由 ConnectionManager 的停止路径调用，
	// app 侧本轮不可改 connEngine.teardown，故挂在引擎 ctx 上（teardown 会 cancel 它）。
	if done := e.ctxDone(); done != nil {
		go func(dc <-chan struct{}, bb *fedBridge) {
			<-dc
			bb.disconnect()
		}(done, b)
	}
	jp := b.connectToHost(host, port)
	if jp <= 0 {
		return jp, ""
	}
	return jp, b.localAddr()
}

// connectToHost 对应 P2PBridge.connectToHost（:155-176）。
func (b *fedBridge) connectToHost(host string, port int) int {
	b.cancelled.Store(false) // :156
	if b.running.Load() {    // :157-165（isRunning() = running.get()，:845-847）
		if jp := b.currentJoinerPort(); jp > 0 {
			return jp // :160-162 已有桥直接复用
		}
		b.disconnect() // :164
	}
	if strings.Contains(host, ":") { // :167-173 IPv6 预解析，失败只告警不阻断
		if _, err := net.LookupIP(fedStripBrackets(host)); err != nil {
			b.bridgeLog("warn", "IPv6 地址预解析失败: "+err.Error())
		}
	}
	return b.connectToHostWithRetry(host, port, 0) // :175
}

// connectToHostWithRetry 对应 :178-264（外层重试：首连 + 最多 3 次重试，间隔 1000*(attempt+1) ms，
// 线程名 VoxLink-Retry / daemon）。Go 里整条递归跑在 connectFed 起的协程内，
// 打断语义由 cancelled 标志承担（:232,240-242），与 Java 完全一致。
func (b *fedBridge) connectToHostWithRetry(host string, port, attempt int) int {
	if b.cancelled.Load() { // :179-181
		return -1
	}
	if b.running.Load() { // :183-192
		if jp := b.currentJoinerPort(); jp > 0 {
			return jp
		}
	}
	b.running.Store(true) // :191

	result := b.attemptBridge(host, port, attempt) // :194-230 supplyAsync 体

	if result <= 0 && attempt < fedJoinerOuterRetries && !b.cancelled.Load() { // :232
		if !b.sleepInterruptible(time.Duration(attempt+1) * fedJoinerOuterRetryStep) { // :239
			return -1 // :240-242 cancelled → complete(-1)
		}
		if b.cancelled.Load() { // :240-243
			return -1
		}
		return b.connectToHostWithRetry(host, port, attempt+1) // :245
	}
	return result // :260-262
}

// attemptBridge 是 supplyAsync 的函数体（:195-230）。
func (b *fedBridge) attemptBridge(host string, port, attempt int) int {
	if b.cancelled.Load() { // :195-197
		return -1
	}
	b.bridgeLog("info", "互通 joiner：连接中继 "+host+":"+strconv.Itoa(port)+
		"（第 "+strconv.Itoa(attempt+1)+"/"+strconv.Itoa(fedJoinerOuterRetries+1)+" 轮）") // :200
	hostSocket := b.tryConnectWithRetry(host, port) // :201
	if hostSocket == nil {
		b.bridgeLog("error", "互通 joiner：无法连接中继 "+host+":"+strconv.Itoa(port)) // :203
		b.running.Store(false)                                                // :204
		return -1
	}
	b.bridgeLog("info", "互通 joiner：已连上中继") // :208

	ln, jp, err := b.listenLocal() // :209-210
	if err != nil {
		b.bridgeLog("error", "互通 joiner：本地桥创建失败: "+err.Error()) // :227
		closeQuietlyConn(hostSocket)
		b.running.Store(false) // :228
		return -1
	}
	b.setJoiner(ln, jp, host, port)               // :211-216
	b.bridgeLog("info", "互通 joiner：本地桥已就绪，转发至中继") // :218

	// :219-221 复用连接前再确认 nodelay/缓冲（tryConnectWithRetry 已设，这里幂等重设）
	fedTuneSocket(hostSocket)

	go b.acceptJoinerConnectionPreconnected(ln, hostSocket) // :222-225
	return jp
}

// listenLocal 对应 :209 new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))。
// 偏差说明：Go 的 listen backlog 在 socket 创建时由系统默认值决定，net 包不提供
// 传入 backlog=50 的入口（fedJoinerBacklog 常量仅作对照），行为差异仅为监听队列长度上限更大。
func (b *fedBridge) listenLocal() (net.Listener, int, error) {
	ln, err := net.ListenTCP("tcp", &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 0})
	if err != nil {
		return nil, 0, err
	}
	addr, ok := ln.Addr().(*net.TCPAddr)
	if !ok {
		_ = ln.Close()
		return nil, 0, errNoFedLocalAddr
	}
	return ln, addr.Port, nil
}

// tryConnectWithRetry 对应 :654-705：最多 3 次，connect 超时 10000ms，失败间 sleep(500*attempt)。
func (b *fedBridge) tryConnectWithRetry(host string, port int) net.Conn {
	if host == "" { // :655-657（host == null）
		return nil
	}
	for attempt := 1; attempt <= fedJoinerInnerAttempts; attempt++ {
		b.bridgeLog("info", "互通 joiner：拨号 "+host+":"+strconv.Itoa(port)+
			"（"+strconv.Itoa(attempt)+"/"+strconv.Itoa(fedJoinerInnerAttempts)+"）") // :663
		conn, err := fedDialResolved(host, port)
		if err == nil {
			b.bridgeLog("info", "互通 joiner：拨号成功") // :682
			return conn
		}
		if conn != nil { // :685-690
			closeQuietlyConn(conn)
		}
		b.bridgeLog("warn", "互通 joiner：拨号失败（"+strconv.Itoa(attempt)+"/"+
			strconv.Itoa(fedJoinerInnerAttempts)+"）: "+err.Error()) // :692
		if attempt < fedJoinerInnerAttempts { // :693-700
			if !b.sleepInterruptible(time.Duration(attempt) * fedJoinerInnerRetryStep) {
				return nil // :696-699 InterruptedException → 直接返回 null
			}
		}
	}
	return nil
}

// fedDialResolved 对应 :662-681 的单次连接：TCP_NODELAY + 收发缓冲 32768，
// 含 ':' 的 host 先去 IPv6 方括号再连（:670-679）。
func fedDialResolved(host string, port int) (net.Conn, error) {
	resolved := host
	if strings.Contains(host, ":") {
		if strings.HasPrefix(host, "[") && strings.Contains(host, "]") {
			bracketEnd := strings.Index(host, "]") // :672-673
			resolved = host[1:bracketEnd]
		}
	}
	conn, err := net.DialTimeout("tcp", net.JoinHostPort(resolved, strconv.Itoa(port)), fedJoinerConnectTimeout) // :681
	if err != nil {
		return conn, err
	}
	fedTuneSocket(conn) // :665-667
	return conn, nil
}

// fedTuneSocket 设 TCP_NODELAY 与 32768 收发缓冲（:219-221,485-487,665-667,441-443 同一组值）。
func fedTuneSocket(conn net.Conn) {
	if tc, ok := conn.(*net.TCPConn); ok {
		_ = tc.SetNoDelay(true)
		_ = tc.SetWriteBuffer(fedBridgeBufferSize)
		_ = tc.SetReadBuffer(fedBridgeBufferSize)
	}
}

// fedStripBrackets 去掉 IPv6 字面量的方括号（对应 :670-674 的预解析前置处理）。
func fedStripBrackets(host string) string {
	if strings.HasPrefix(host, "[") && strings.Contains(host, "]") {
		return host[1:strings.Index(host, "]")]
	}
	return host
}

// acceptJoinerConnectionPreconnected 对应 :468-538：本地桥 accept 循环。
// 第一名 MC 客户端复用预连好的中继 socket，之后每来一名客户端重新拨号中继（:490-495）。
// Java 靠 accept 超时(10s)轮询 running 标志；Go 无 accept 超时 API，改由 Close() 打断 Accept，
// 观察到的行为一致：拆除即刻退出循环。
func (b *fedBridge) acceptJoinerConnectionPreconnected(js net.Listener, preconnected net.Conn) {
	first := true // :474
	acceptFailed := false
	for b.running.Load() { // :477
		mcClient, err := js.Accept() // :480
		if err != nil {
			acceptFailed = true // :481-483 超时→continue 的语义由 running/Close 承担
			break
		}
		fedTuneSocket(mcClient)                       // :485-487
		b.bridgeLog("info", "互通 joiner：MC 客户端已连入本地桥") // :488

		var hostSocket net.Conn
		if first && preconnected != nil { // :490-493
			hostSocket = preconnected
			preconnected = nil
			first = false
		} else { // :494
			h, p := b.currentTarget()
			hostSocket = b.tryConnectWithRetry(h, p)
		}
		if hostSocket == nil { // :497-503
			b.bridgeLog("error", "互通 joiner：为该 MC 客户端建立中继通道失败")
			closeQuietlyConn(mcClient)
			continue
		}
		label, labelRev := fedBridgeLabels(hostSocket)       // :505-506
		pair := &fedPair{client: mcClient, host: hostSocket} // :507
		b.addPair(pair)                                      // :508
		go b.bridge(pair, pair.client, pair.host, label)     // :510
		go b.bridge(pair, pair.host, pair.client, labelRev)  // :511
	}
	if acceptFailed && preconnected != nil { // :519-524 catch 分支关闭预连接
		closeQuietlyConn(preconnected)
	}
	// :525-536 finally：关本地监听，并在"仍是这一条"时摘掉引用
	_ = js.Close()
	b.clearJoiner(js)
}

// fedBridgeLabels 按远端地址族取日志标签（:505-506 Inet6Address 判定）。
func fedBridgeLabels(hostSocket net.Conn) (string, string) {
	isV6 := false
	if ra, ok := hostSocket.RemoteAddr().(*net.TCPAddr); ok {
		isV6 = ra.IP.To4() == nil
	}
	if isV6 {
		return "MC->Host(IPv6)", "Host(IPv6)->MC"
	}
	return "MC->Host", "Host->MC"
}

// bridge 对应 :707-752 的单向搬运：60s 读超时（超时即 continue，只重查 running）、
// keepalive 打开、32768 缓冲；任一方向结束即成对关闭。
// 偏差说明：Java 在此记录首包并 notifyTrafficDetected()（:735-739），
// 那个标志只服务于 mod 的 host 桥首包看门狗，互通 joiner 侧无消费方，故不移植。
func (b *fedBridge) bridge(pair *fedPair, from, to net.Conn, label string) {
	if tc, ok := from.(*net.TCPConn); ok { // :712 setKeepAlive(true)
		_ = tc.SetKeepAlive(true)
	}
	buf := make([]byte, fedBridgeBufferSize) // :720
	for b.running.Load() {                   // :724（Go 无 isClosed() 查询，靠读写错误退出同一循环）
		_ = from.SetReadDeadline(time.Now().Add(fedBridgeReadTimeout)) // :711 setSoTimeout(60000)
		n, err := from.Read(buf)                                       // :727
		if n > 0 {
			if _, werr := to.Write(buf[:n]); werr != nil { // :741-742 write + flush
				break
			}
		}
		if err != nil {
			if isFedReadTimeout(err) { // :731-733 SocketTimeoutException → continue
				continue
			}
			break // :728-729 bytesRead == -1（EOF）/ 其它 IO 错误
		}
	}
	pair.close()       // :749
	b.removePair(pair) // :750
}

// disconnect 对应 :768-806（不打印调用栈，其余一致）。
func (b *fedBridge) disconnect() {
	b.cancelled.Store(true)               // :772
	b.running.Store(false)                // :773（:232 与 sleepInterruptible 据此立刻短路）
	for _, p := range b.snapshotPairs() { // :776-778
		p.close()
	}
	b.clearPairs()       // :780
	ln := b.takeJoiner() // :799-804 joinerServer.close() 并置空
	if ln != nil {
		_ = ln.Close()
	}
}

// ---- 状态存取（对应 Java 的 static 字段读写） ----

func (b *fedBridge) currentJoinerPort() int {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.joinerPort
}

func (b *fedBridge) setJoiner(ln net.Listener, port int, host string, hostPort int) {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.joinerLn = ln
	b.joinerPort = port
	b.currentHost = host
	b.currentHostPort = hostPort
}

func (b *fedBridge) currentTarget() (string, int) {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.currentHost, b.currentHostPort
}

// localAddr 是"要填进 Minecraft 的地址"，与其他 tunnel 类路径一致（127.0.0.1:<随机端口>）。
func (b *fedBridge) localAddr() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.joinerLn != nil {
		return b.joinerLn.Addr().String()
	}
	if b.joinerPort > 0 {
		return net.JoinHostPort(fedLocalHost, strconv.Itoa(b.joinerPort))
	}
	return ""
}

func (b *fedBridge) addPair(p *fedPair) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.pairs == nil {
		b.pairs = map[*fedPair]struct{}{}
	}
	b.pairs[p] = struct{}{}
}

func (b *fedBridge) removePair(p *fedPair) {
	b.mu.Lock()
	defer b.mu.Unlock()
	delete(b.pairs, p)
}

func (b *fedBridge) snapshotPairs() []*fedPair {
	b.mu.Lock()
	defer b.mu.Unlock()
	out := make([]*fedPair, 0, len(b.pairs))
	for p := range b.pairs {
		out = append(out, p)
	}
	return out
}

func (b *fedBridge) clearPairs() {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.pairs = map[*fedPair]struct{}{}
}

// clearJoiner 只摘"仍然是这一条"的监听（:531-535 的 joinerServer == js 判据）。
func (b *fedBridge) clearJoiner(js net.Listener) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.joinerLn == js {
		b.joinerLn = nil
		b.joinerPort = -1 // :534
	}
}

// takeJoiner 摘除并返回监听以便关闭。
func (b *fedBridge) takeJoiner() net.Listener {
	b.mu.Lock()
	defer b.mu.Unlock()
	ln := b.joinerLn
	b.joinerLn = nil
	b.joinerPort = -1 // :649（同样把 joinerPort 复位）
	return ln
}
