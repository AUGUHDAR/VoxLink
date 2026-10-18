package main

// fed_tunnel.go — 房主侧互通隧道客户端，逐语句移植
// fabric/1.20_1.20.1/src/main/java/icu/wuhui/voxlink/network/FedTunnelClient.java（266 行）。
//
// 协议（权威：server-hl/hltunnel.go:59-103,164-201,204-226）：裸 TCP、换行分隔的 UTF-8 文本行，
// 一条控制连接注册房间（HELLO code token lanIp localPort → OK publicPort），中继下发 CONNECT <id>
// 时为本玩家新开一条 DATA 连接（DATA <id> <token>），与本机 MC 端口双向裸转发。
// 没有 WebSocket、没有长度前缀、没有二进制帧。

import (
	"bufio"
	"bytes"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// 全部数值来自 FedTunnelClient.java，逐个带行号；改动任何一个都会改变与 HL 中继的时序契约。
const (
	// fedControlDialTimeout 控制连接 TCP 握手上限——socket.connect(addr, 8000)（:95）
	fedControlDialTimeout = 8 * time.Second
	// fedControlReadTimeout readLine 的阻塞上限——setSoTimeout(15000)（:97）；
	// 控制循环正是靠它醒来发 PING，所以 PING 的实际粒度是 ">8000ms 判定 + 最坏 15s 一次"。
	fedControlReadTimeout = 15 * time.Second
	// fedPingIntervalMs 心跳判定阈值——now - lastPing > 8000（:142，严格大于）
	fedPingIntervalMs = int64(8000)
	// fedMaxFailures 连续失败上限——failures > 8 即判死（:155），即最多连续重试 8 次
	fedMaxFailures = 8
	// fedBackoffStepMs / fedBackoffCapMs 退避——min(15000, 3000*failures)（:160）
	fedBackoffStepMs = int64(3000)
	fedBackoffCapMs  = int64(15000)
	// fedBackoffCapSec 退避提示秒数——min(15, 3*failures)（:159）
	fedBackoffCapSec = 15
	// fedDataDialTimeout DATA 连接握手中继——up.connect(..., 8000)（:195）
	fedDataDialTimeout = 8 * time.Second
	// fedLocalDialTimeout 连接本机 MC 端口——local.connect(..., 5000)（:202）
	fedLocalDialTimeout = 5 * time.Second
	// fedLocalHost 转发目标恒为回环地址（:202 硬编码 "127.0.0.1"）
	fedLocalHost = "127.0.0.1"
	// fedLanIpFallback 拿不到局域网 IP 时的占位串（:189）；服务端把 "0"/空视为无信息
	// （server-hl/hltunnel.go:183-185）。
	fedLanIpFallback = "0"
	// fedInitialPublicPort publicPort 初值 -1（:55）。
	fedInitialPublicPort = -1
)

// 状态文案：与 FedTunnelClient.java 的硬编码中文串逐字一致（:128,136,156,159,163）。
// 这些串不进 lang 文件、只走日志（规格 6.3 / D4）。
const (
	fedMsgOnlineFmt    = "互通房间已上线：%s 端口 %d"   // :128
	fedMsgRejectedFmt  = "中继节点拒绝隧道连接：%s"      // :136
	fedMsgGiveUp       = "无法连接中继节点，互通隧道已停止"   // :156
	fedMsgRetryFmt     = "隧道连接中断，%d 秒后重试"     // :159
	fedMsgStopped      = "互通隧道已停止"            // :163
	fedLoggerName      = "VoxLink-FedTunnel"  // :37
	fedThreadPoolName  = "voxlink-fed-tunnel" // :49
	fedThreadPipeName  = "voxlink-fed-pipe"   // :224
	fedLineReadChunk   = 4096                 // 单次 socket 读缓冲（readLine 的下层语义）
	fedPipeCopyBufSize = 32 * 1024            // transferTo 的默认拷贝粒度（:219,229）
)

// FedTunnelListener 对应 FedTunnelClient.Listener（:33-35）。
// 回调发生在隧道线程上（非主线程），实现必须自身并发安全且不阻塞。
type FedTunnelListener func(message string, fatal bool)

// FedTunnelClient 房主侧 HL 镜像隧道。
type FedTunnelClient struct {
	host       string // :39
	tunnelPort int    // :40
	code       string // :41（HELLO 第 1 字段：fedCode 或回退的本房码）
	token      string // :42
	localPort  int    // :43 本机 MC 端口

	hostAlive   func() bool       // :44 BooleanSupplier
	playerCount func() int        // :45 IntSupplier（可为 null → 发 0，:143）
	listener    FedTunnelListener // :46

	running  atomic.Bool   // :47 AtomicBoolean(true)
	quit     chan struct{} // stop() 的 pool.shutdownNow()：打断退避睡眠
	quitOnce sync.Once
	wg       sync.WaitGroup // :48 newCachedThreadPool 上提交的任务集合

	mu         sync.Mutex
	control    net.Conn     // :54 volatile Socket control
	publicPort atomic.Int64 // :55 volatile int publicPort = -1
}

// NewFedTunnelClient 对应 :57-67 的构造（参数顺序一致：host, tunnelPort, code, token, localPort,
// hostAlive, playerCount, listener）。running 初值 true、publicPort 初值 -1。
func NewFedTunnelClient(host string, tunnelPort int, code, token string, localPort int,
	hostAlive func() bool, playerCount func() int, listener FedTunnelListener) *FedTunnelClient {
	c := &FedTunnelClient{
		host:        host,
		tunnelPort:  tunnelPort,
		code:        code,
		token:       token,
		localPort:   localPort,
		hostAlive:   hostAlive,
		playerCount: playerCount,
		listener:    listener,
		quit:        make(chan struct{}),
	}
	c.running.Store(true)                           // :47
	c.publicPort.Store(int64(fedInitialPublicPort)) // :55
	return c
}

// start 对应 :69-71——把 runLoop 丢进池里，本身不阻塞。
func (c *FedTunnelClient) start() {
	c.wg.Add(1)
	go func() {
		defer c.wg.Done()
		c.runLoop()
	}()
}

// stop 对应 :73-77，顺序不可变：先置 running=false，再关控制 socket 打断阻塞的 readLine，
// 最后关闭 quit 通道（等价 shutdownNow 的 interrupt，立刻打断退避睡眠）。
// 注意与 Java 一致：不主动关在途 DATA 连接（那边的 daemon 线程靠 JVM 退出回收；
// Go 无 daemon 线程，由 pipe() 里的 quit 看门狗拆除。
func (c *FedTunnelClient) stop() {
	c.running.Store(false)                  // :74
	c.closeControl()                        // :75 closeQuietly(this.control)
	c.quitOnce.Do(func() { close(c.quit) }) // :76 pool.shutdownNow()
}

// IsRunning 对应 :79-81。
func (c *FedTunnelClient) IsRunning() bool { return c.running.Load() }

// wait 等待池内全部任务退出（stop() 之后调用）。Java 靠 setDaemon(true) 让残余线程随 JVM 消失，
// Go 没有守护线程，这里给出显式收束点供停用/测试用。
func (c *FedTunnelClient) wait() { c.wg.Wait() }

// GetPublicPort 对应 :83-85。VoxLink 侧无任何调用者（规格 D2）：纯信息位，只进上线文案。
func (c *FedTunnelClient) GetPublicPort() int { return int(c.publicPort.Load()) }

// ---- 控制连接（:87-164） ----

func (c *FedTunnelClient) runLoop() {
	failures := 0 // :89
	for c.running.Load() {
		if !c.alive() { // :91-93
			break
		}
		connected, err := c.controlSession() // :94-147（try-with-resources：会话内自关）
		if connected {
			failures = 0 // :99 TCP 连上即清零，不等 HELLO 被接受
		}
		if err != nil { // :148-150
			fedDebug(fmt.Sprintf("control connection ended: %v", err))
		}
		if !c.running.Load() { // :151-153
			break
		}
		failures++                     // :154
		if failures > fedMaxFailures { // :155-158
			c.notify(fedMsgGiveUp, true)
			break
		}
		c.notify(fmt.Sprintf(fedMsgRetryFmt, fedBackoffSeconds(failures)), false)      // :159
		if !c.sleepQuietly(time.Duration(fedBackoffMs(failures)) * time.Millisecond) { // :160
			// stop() 打断睡眠：Java 里 interrupt 后回到 while(running) 判假退出，
			// 同样落到 :162-163 的收尾，这里保持等价（break 而非 return）。
			break
		}
	}
	c.running.Store(false)         // :162
	c.notify(fedMsgStopped, false) // :163
}

// controlSession 建立一次控制连接并跑到断开为止。返回值 connected 表示"TCP 握手成功"
// （Java 在 :98-99 connect 之后立刻赋 control 并清零 failures，后续任何一步失败都不回退这个事实）。
func (c *FedTunnelClient) controlSession() (bool, error) {
	addr := net.JoinHostPort(c.host, strconv.Itoa(c.tunnelPort))
	conn, err := net.DialTimeout("tcp", addr, fedControlDialTimeout) // :95
	if err != nil {
		return false, err
	}
	if tc, ok := conn.(*net.TCPConn); ok { // :96 setTcpNoDelay(true)
		_ = tc.SetNoDelay(true)
	}
	c.mu.Lock()
	c.control = conn // :98
	c.mu.Unlock()
	// :94 try-with-resources：会话退出即关；同时只在"仍是本条"时摘引用，
	// 以免 stop() 关到下一条连接（Java 的 volatile 赋值有同样的竞态，语义保持）。
	defer func() {
		c.mu.Lock()
		if c.control == conn {
			c.control = nil
		}
		c.mu.Unlock()
		_ = conn.Close()
	}()

	w := bufio.NewWriter(conn)                                                                           // :101 BufferedWriter(OutputStreamWriter(UTF_8))
	br := bufio.NewReader(conn)                                                                          // :102 BufferedReader(InputStreamReader(UTF_8))
	if _, err := io.WriteString(w, fedHelloLine(c.code, c.token, fedLanIp(), c.localPort)); err != nil { // :104
		return true, err
	}
	if err := w.Flush(); err != nil { // :105
		return true, err
	}

	reader := &fedLineReader{br: br, conn: conn}
	lastPing := time.Now().UnixMilli() // :107
	for c.running.Load() {             // :108
		if !c.alive() { // :109-111
			return true, nil
		}
		line, ok, rerr := reader.next() // :112-117
		if rerr != nil {
			return true, rerr
		}
		if ok { // :118
			stop, herr := c.handleControlLine(line)
			if herr != nil {
				return true, herr
			}
			if stop { // :138 break
				return true, nil
			}
		}
		now := time.Now().UnixMilli()         // :141
		if now-lastPing > fedPingIntervalMs { // :142
			if _, err := io.WriteString(w, fedPingLine(c.fedPlayers())); err != nil { // :143
				return true, err
			}
			if err := w.Flush(); err != nil { // :144
				return true, err
			}
			lastPing = now // :145
		}
	}
	return true, nil
}

// fedPlayers 对应 :143 的 `this.playerCount == null ? 0 : playerCount.getAsInt()`。
func (c *FedTunnelClient) fedPlayers() int {
	if c.playerCount == nil {
		return 0
	}
	return c.playerCount()
}

// handleControlLine 处理一行服务端指令（:119-140）。返回 stop=true 表示 ERR 分支要求永久停止。
func (c *FedTunnelClient) handleControlLine(raw string) (bool, error) {
	line := strings.TrimSpace(raw) // :119 line.trim()
	switch {
	case strings.HasPrefix(line, "OK"): // :120-128
		parts := strings.Fields(line) // :121 line.split("\\s+")
		if len(parts) > 1 {           // :122
			if p, perr := strconv.Atoi(parts[1]); perr == nil { // :123-126 吞 NumberFormatException
				c.publicPort.Store(int64(p))
			}
		}
		c.notify(fmt.Sprintf(fedMsgOnlineFmt, c.host, c.GetPublicPort()), false) // :128
	case strings.HasPrefix(line, "CONNECT"): // :129-134
		parts := strings.Fields(line) // :130
		if len(parts) > 1 {           // :131
			id := parts[1] // :132 不透明字符串：idBase(8位hex)+自增序号(hex)，绝不解析
			c.wg.Add(1)
			go func() { // :133 pool.submit(() -> openData(id))，不在读线程内联
				defer c.wg.Done()
				c.openData(id)
			}()
		}
	case strings.HasPrefix(line, "ERR"): // :135-139
		c.notify(fmt.Sprintf(fedMsgRejectedFmt, line), true)
		c.running.Store(false) // :137 永久停止，不重连（服务端写 ERR 后立即 close，:171 不许反复试 token）
		return true, nil
	}
	return false, nil
}

func (c *FedTunnelClient) alive() bool { // :166-168
	return c.hostAlive == nil || c.hostAlive()
}

// ---- 协议行构造（字节级权威，见规格 §10.1） ----

// fedHelloLine = "HELLO <code> <token> <lanIp> <localPort>\n"（:104，单空格分隔）。
// 字段数不足 3 会被服务端静默丢弃且永不自愈（hltunnel.go:66,82 的 mode=2 读干即弃）——
// 顺序与分隔符绝不许动。
func fedHelloLine(code, token, lanIp string, localPort int) string {
	return fmt.Sprintf("HELLO %s %s %s %d\n", code, token, lanIp, localPort)
}

// fedDataLine = "DATA <id> <token>\n"（:198）。
func fedDataLine(id, token string) string {
	return fmt.Sprintf("DATA %s %s\n", id, token)
}

// fedPingLine = "PING <players>\n"（:143）。服务端 clamp 到 0..255（hltunnel.go:93）。
func fedPingLine(players int) string {
	return fmt.Sprintf("PING %d\n", players)
}

// ---- 退避表（:159-160） ----

// fedBackoffMs = min(15000, 3000*failures)：failures=1..8 → 3/6/9/12/15/15/15/15 秒。
func fedBackoffMs(failures int) int64 {
	v := fedBackoffStepMs * int64(failures)
	if v > fedBackoffCapMs {
		v = fedBackoffCapMs
	}
	return v
}

// fedBackoffSeconds = min(15, 3*failures)：提示文案里的秒数，与 fedBackoffMs 同族但独立计算。
func fedBackoffSeconds(failures int) int {
	v := 3 * failures
	if v > fedBackoffCapSec {
		v = fedBackoffCapSec
	}
	return v
}

// ---- 数据连接（:192-236） ----

// openData 为一名玩家开一条 DATA 连接并桥到本机 MC 端口。
// 与 Java 一致：任何失败都静默忽略（:206-208 注释"玩家可能已经断开"）——
// 服务端 DATA 配对失败时也不回任何字节（hltunnel.go:204-226），玩家只表现为连不上。
func (c *FedTunnelClient) openData(id string) {
	up, err := net.DialTimeout("tcp", net.JoinHostPort(c.host, strconv.Itoa(c.tunnelPort)), fedDataDialTimeout) // :194-195
	if err != nil {
		return
	}
	defer up.Close()                     // :194 try-with-resources
	if tc, ok := up.(*net.TCPConn); ok { // :196
		_ = tc.SetNoDelay(true)
	}
	if _, err := io.WriteString(up, fedDataLine(id, c.token)); err != nil { // :198-199
		return
	}

	local, err := net.DialTimeout("tcp", net.JoinHostPort(fedLocalHost, strconv.Itoa(c.localPort)), fedLocalDialTimeout) // :201-202
	if err != nil {
		return
	}
	defer local.Close()                     // :201 嵌套 try-with-resources
	if tc, ok := local.(*net.TCPConn); ok { // :203
		_ = tc.SetNoDelay(true)
	}
	// :204 pipe(up, local)：无读超时（数据连接不设 SO_TIMEOUT），不设缓冲复制上限，双向裸搬运
	c.pipe(up, local)
}

// pipe 对应 :211-236：另起一条线程搬运 a→b（voxlink-fed-pipe，Java 是 daemon 裸线程），
// 本线程搬运 b→a；任一起手即关两端。quit 看门狗是 Go 侧对 setDaemon(true)（:225）的等价实现：
// stop() 之后不再有悬挂的搬运协程。
func (c *FedTunnelClient) pipe(a, b net.Conn) {
	var once sync.Once
	closeBoth := func() { once.Do(func() { closeQuietlyConn(a); closeQuietlyConn(b) }) }

	unwatch := make(chan struct{})
	go func() { // 线程名 voxlink-fed-pipe（:224）对应的搬运协程 + 停止看门狗
		select {
		case <-c.quit:
			closeBoth()
		case <-unwatch:
		}
	}()

	copied := make(chan struct{})
	go func() { // :217-224 ai.transferTo(bo)
		defer close(copied)
		buf := make([]byte, fedPipeCopyBufSize)
		_, _ = io.CopyBuffer(b, a, buf)
	}()

	buf := make([]byte, fedPipeCopyBufSize)
	_, _ = io.CopyBuffer(a, b, buf) // :229 bi.transferTo(ao)

	closeBoth() // :234-235 closeQuietly(a); closeQuietly(b)
	close(unwatch)
	<-copied
}

// ---- 通用小工具 ----

func (c *FedTunnelClient) closeControl() { // :75 closeQuietly(this.control)
	c.mu.Lock()
	conn := c.control
	c.mu.Unlock()
	closeQuietlyConn(conn)
}

func closeQuietlyConn(s net.Conn) { // :238-246
	if s != nil {
		_ = s.Close()
	}
}

// sleepQuietly 对应 :248-254：可被 stop() 打断的睡眠。返回 false 表示被打断。
func (c *FedTunnelClient) sleepQuietly(d time.Duration) bool {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-c.quit:
		return false
	case <-t.C:
		return true
	}
}

// notify 对应 :256-265：先无条件 info，再回调 listener 并吞掉其异常。
func (c *FedTunnelClient) notify(message string, fatal bool) {
	suffix := ""
	if fatal {
		suffix = " (fatal)"
	}
	log.Printf("[%s] [fed-tunnel] %s%s", fedLoggerName, message, suffix) // :37,257
	if c.listener != nil {
		defer func() { _ = recover() }() // :261-263 catch (Exception ignored)
		c.listener(message, fatal)
	}
}

// fedDebug 对应 :149 的 LOGGER.debug("[fed-tunnel] control connection ended: {}")。
func fedDebug(msg string) {
	log.Printf("[%s] [fed-tunnel] %s", fedLoggerName, msg)
}

// ---- 局域网 IP（:170-190） ----

// fedLanIp 取第一个"启用且非回环"网卡上的第一个站点本地地址；拿不到返回 "0"。
// Java 用的是 Inet4Address.isSiteLocalAddress()（10/8、172.16/12、192.168/16），
// Inet6Address.isSiteLocalAddress() 恒为 false（IPv6 site-local 早已废弃），故这里只认 IPv4。
func fedLanIp() string {
	ifaces, err := net.Interfaces() // :172 NetworkInterface.getNetworkInterfaces()
	if err != nil {
		return fedLanIpFallback // :186-189 拿不到就回 "0"
	}
	for _, iface := range ifaces {
		if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 { // :175
			continue
		}
		addrs, err := iface.Addrs() // :179 ni.getInetAddresses()
		if err != nil {
			continue
		}
		for _, a := range addrs {
			ip, _, err := net.ParseCIDR(a.String())
			if err != nil {
				continue
			}
			if isFedSiteLocalAddress(ip) { // :181
				return ip.String() // :182 a.getHostAddress()
			}
		}
	}
	return fedLanIpFallback // :189
}

// isFedSiteLocalAddress 等价 Java Inet4Address.isSiteLocalAddress()。
func isFedSiteLocalAddress(ip net.IP) bool {
	v4 := ip.To4()
	if v4 == nil {
		return false
	}
	switch {
	case v4[0] == 10:
		return true
	case v4[0] == 172 && v4[1] >= 16 && v4[1] <= 31:
		return true
	case v4[0] == 192 && v4[1] == 168:
		return true
	}
	return false
}

// ---- 控制连接的"一次一行"读取 ----

// fedLineReader 复刻 BufferedReader.readLine() + setSoTimeout(15000) 的组合语义：
//   - 一次调用至多返回一行（Java 的内层循环每轮只处理一行，然后做 PING 判定）；
//   - 15s 读超时 → 返回 present=false（:115-116 的 SocketTimeoutException 只是心跳检查点）；
//   - 未凑齐一行的字节留在缓冲里，下次继续拼（readLine 的跨读拼接语义）；
//   - EOF/其它 IO 错误 → 返回 error，会话结束走重连。
//
// 刻意不用 bufio.Scanner：Scanner 有 64KB 行上限且超限后的流语义会被吞掉，
// 与 Java 的 BufferedReader（无行上限）不一致。
type fedLineReader struct {
	br      *bufio.Reader
	conn    net.Conn
	partial []byte
	lines   []string
}

func (r *fedLineReader) next() (string, bool, error) {
	if len(r.lines) > 0 {
		line := r.lines[0]
		r.lines = r.lines[1:]
		return line, true, nil
	}
	chunk := make([]byte, fedLineReadChunk)
	_ = r.conn.SetReadDeadline(time.Now().Add(fedControlReadTimeout)) // :97
	n, err := r.br.Read(chunk)
	if n > 0 {
		r.feed(chunk[:n])
	}
	if len(r.lines) > 0 {
		line := r.lines[0]
		r.lines = r.lines[1:]
		return line, true, nil
	}
	if err != nil {
		if isFedReadTimeout(err) {
			return "", false, nil
		}
		return "", false, err
	}
	return "", false, nil
}

func (r *fedLineReader) feed(b []byte) {
	r.partial = append(r.partial, b...)
	for {
		i := bytes.IndexByte(r.partial, '\n') // :112 服务端也是按 '\n' 切行
		if i < 0 {
			return
		}
		r.lines = append(r.lines, string(r.partial[:i]))
		r.partial = r.partial[i+1:]
	}
}

// isFedReadTimeout 判定 socket 读超时（Java 的 SocketTimeoutException）。
func isFedReadTimeout(err error) bool {
	if err == nil {
		return false
	}
	if errors.Is(err, os.ErrDeadlineExceeded) {
		return true
	}
	var ne net.Error
	return errors.As(err, &ne) && ne.Timeout()
}
