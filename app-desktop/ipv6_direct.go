package main

// ipv6_direct.go — 直连兜底链，逐段移植模组 network/ConnectionFallback.java（1.2.0，459 行）：
// IPv6 直连 / IPv4 直连 / TCP 同时开放（simultaneous open）、IPv6 可达性验证、
// 本机 GUA（全局单播）提取、状态文案。
//
// 本端此前完全没有 IPv6 这条线（app-desktop 里只有 fed_connect.go 的 IPv6 字面量解析与
// stun.go 的 XOR-Mapped-IPv6 编解码），这里按模组的常量与尝试顺序补齐。
//
// 只做 Java→Go 的形态转换：
//   - CompletableFuture<ConnectResult> → 缓冲 1 的只读 channel（thenAccept / allOf 的等价写法）
//   - Executors.newCachedThreadPool → goroutine（同样按需起、无上限）
//   - AtomicBoolean won/settled/cancelled → atomic.Bool（CAS 语义保留）
//   - Component.translatable(key) → 本仓既有口径的中文字面量 + 行尾 lang 键注释
//     （同 fed_connect.go:31-33、auto_relay.go:43 的做法）
//   - 异常分类（SocketTimeoutException / NoRouteToHostException / ConnectException / IOException）
//     → Go 错误分类，映射表见 classifyDialError
//   - IPv6 与 IPv4 两条直连在模组里是逐行重复的两段（:71-130 / :132-191）；这里合成一个
//     fbDirectAttempt，差异全部走参数（codePrefix/label/mode/空地址码），行为逐分支对齐。
//
// 平台口径：纯标准库 net，Windows/Linux/macOS 共用一份文件，无 OS 专属调用。
// 唯一平台相关差异：SO_REUSEADDR（见 tryTcpSimultaneousOpen 内注释），已在交付报告列明。

import (
	"errors"
	"log"
	"math/rand"
	"net"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// ---- 常量（ConnectionFallback.java）----

const (
	// fbSocketTimeout 拨号超时 3000ms（:30 SOCKET_TIMEOUT；:82/:143/:358 用的是同值字面量）。
	fbSocketTimeout = 3000 * time.Millisecond
	// fbConnectTimeout 2000ms（:31 SOCKET_CONNECT_TIMEOUT_MS）：模组唯一的使用点是
	// IPv6 可达性探测的 connect 超时（:209）。
	fbConnectTimeout = 2000 * time.Millisecond
	// fbSimOpenWindow TCP 同时开放总窗口 10s（:32 TCP_SIMOPEN_WINDOW_MS，:315 now+10000）。
	fbSimOpenWindow = 10 * time.Second
	// fbSimOpenMaxAttempts 最多 5 次（:33 TCP_SIMOPEN_MAX_ATTEMPTS，:320 循环条件与 :388 break 条件）。
	fbSimOpenMaxAttempts = 5
	// fbIPv6CheckCache IPv6 可达性结果缓存 60s（:40 IPV6_CHECK_CACHE_MS，:195 判过期）。
	fbIPv6CheckCache = 60 * time.Second
	// fbDNSServerPort 可达性探测端口 53（:209 InetSocketAddress(target, 53)）。
	fbDNSServerPort = 53
	// fbSimOpenBackoffMax 每次重试前的随机退避上界（:393 Thread.sleep(10 + rng.nextInt(90))）。
	fbSimOpenBackoffBase   = 10 * time.Millisecond
	fbSimOpenBackoffJitter = 90 * time.Millisecond
	// fbShutdownWait 线程池退出的最长等待 2s（:46 awaitTermination(2, SECONDS)）。
	fbShutdownWait = 2 * time.Second
)

// fbIPv6TestTargets IPv6 可达性验证目标（:205）：Google DNS over IPv6，顺序照抄（先 8888 再 8844）。
var fbIPv6TestTargets = []string{"2001:4860:4860::8888", "2001:4860:4860::8844"}

// 状态文案：模组走 Component.translatable(lang 键)，本端沿用仓库既有做法——直接用
// lang/zh_cn.json 同键原文，键名写在行尾。接线 i18n 时统一改走查表。
const (
	txtConnProbing     = "正在探测网络环境..."     // voxlink.connection.probing（:73/:134/:304）
	txtConnBridgeSetup = "隧道已打通，正在建立连接..." // voxlink.connection.bridge_setup（:86/:147/:360）
	txtConnTimeout     = "连接超时"            // voxlink.connection.timeout（:95/:156）
	txtConnFailed      = "连接失败"            // voxlink.connection.failed（:99/:105/:108/:113/:160/:166/:169/:173/:401）
	txtConnEmpty       = ""                // Component.empty()（:37 初值）
)

// ---- 连接模式（enum ConnectionMode，:448-458）----

type fbConnectionMode int

const (
	fbModeNone       fbConnectionMode = iota // Java 的 null（failed/cancelled 结果里 mode==null，:440/:444）
	fbModeIPv6Direct                         // IPV6_DIRECT("voxlink.mode.ipv6_direct") :449
	fbModeIPv4Direct                         // IPV4_DIRECT("voxlink.mode.ipv4_direct") :450
	fbModeUDPPunch                           // UDP_PUNCH("voxlink.mode.udp_punch") :451
)

// fbModeTranslationKey 模式对应的 lang 键（模组 ConnectionMode.translationKey，:453-457）。
func fbModeTranslationKey(m fbConnectionMode) string {
	switch m {
	case fbModeIPv6Direct:
		return "voxlink.mode.ipv6_direct"
	case fbModeIPv4Direct:
		return "voxlink.mode.ipv4_direct"
	case fbModeUDPPunch:
		return "voxlink.mode.udp_punch"
	}
	return ""
}

// ---- 结果（class ConnectResult，:414-446）----

type fbConnectResult struct {
	success       bool
	errorCode     string // :417-418
	failureReason string // :416
	label         string // :418（"IPv6"/"IPv4"/"TCP-SimOpen"）
	remoteHost    string // :419
	remotePort    int    // :420
	mode          fbConnectionMode
}

// fbSuccess 对应 ConnectResult.success(label, remoteHost, remotePort, mode)（:435-437）。
func fbSuccess(label, remoteHost string, remotePort int, mode fbConnectionMode) fbConnectResult {
	return fbConnectResult{success: true, label: label, remoteHost: remoteHost, remotePort: remotePort, mode: mode}
}

// fbFailed 对应 ConnectResult.failed(errorCode, reason)（:439-441）。
func fbFailed(errorCode, reason string) fbConnectResult {
	return fbConnectResult{success: false, errorCode: errorCode, failureReason: reason}
}

// fbCancelled 对应 ConnectResult.cancelled()（:443-445）。
func fbCancelled() fbConnectResult {
	return fbConnectResult{success: false, errorCode: "CANCELLED", failureReason: "Connection cancelled"}
}

// ---- 实例：一路兜底尝试 ----

// connFallback 一路兜底尝试的胜负/取消/状态位（对应实例字段 :34-37）。
// 模组每条尝试都 new 一个独立实例（ConnectionManager 里 10 处 trackFallback(new ConnectionFallback())），
// 所以 won/settled 只表示"这一路"的胜负；跨路胜负由调用方的 connectionWon CAS 决定
// （CM:6837/6863/6889/7246 等）。
type connFallback struct {
	cancelled atomic.Bool
	settled   atomic.Bool
	won       atomic.Bool

	statusMu  sync.Mutex
	statusTxt string // :37 volatile Component statusText
}

// fbNew 新建一路兜底尝试（模组默认构造器 + :37 的初值 Component.empty()）。
func fbNew() *connFallback { return &connFallback{statusTxt: txtConnEmpty} }

// cancel 对应 cancel()（:55-57）。
func (f *connFallback) cancel() { f.cancelled.Store(true) }

// fbStatusText 对应 getStatusText()（:59-61）。
// 模组返回的是 Component.empty()（非 null），所以调用侧等价物（startFallbackMonitor，
// CM:10284-10346）不能把空串当"无状态"跳过——那里 setConnectionMode(empty) 会真的写进房间信息。
func (f *connFallback) fbStatusText() string {
	f.statusMu.Lock()
	defer f.statusMu.Unlock()
	return f.statusTxt
}

func (f *connFallback) setStatus(s string) {
	f.statusMu.Lock()
	f.statusTxt = s
	f.statusMu.Unlock()
}

func (f *connFallback) isSettled() bool   { return f.settled.Load() }   // :63-65
func (f *connFallback) isCancelled() bool { return f.cancelled.Load() } // :67-69

// fbPreCheck :75 / :136 / :307 三处相同的入口守卫（won/cancelled/settled 任一为真 → cancelled）。
func (f *connFallback) fbPreCheck() bool {
	return !f.won.Load() && !f.cancelled.Load() && !f.settled.Load()
}

// ---- 直连（tryIpv6Direct :71-130 与 tryIpv4Direct :132-191 的合体实现）----

// fbDirectAttempt 一次 TCP 可达性探测：建 socket → TCP_NODELAY → connect(3000ms) → 立即关闭。
// 成功后本路置 won+settled；探测到的连接随即丢弃，真正的数据面由调用方 connectViaBridge 另建
// （模组同口径，app 侧现状见 conn_engine.go:1829 的"探测成功即关闭，玩家自己用 MC 连"）。
//
// 参数即两条链的全部差异：codePrefix（"IPV6"/"IPV4"，用于错误码）、label（"IPv6"/"IPv4"，
// 用于日志与 reason）、mode（IPV6_DIRECT/IPV4_DIRECT）。
func (f *connFallback) fbDirectAttempt(host string, port int, codePrefix, label string, mode fbConnectionMode) fbConnectResult {
	if !f.fbPreCheck() { // :75/:136
		return fbCancelled()
	}
	// InetAddress.getByName 容忍 "[::1]:port" 形态的方括号（RoomManager/Signaling 下发的
	// hostIpv6 有时带括号，fed_connect.go:369-376 也有同款去括号处理），这里先剥掉
	host = strings.Trim(host, "[]") // 只剥方括号字符，不影响 "::1" 这类裸地址
	ip := net.ParseIP(host)         // :81/:142 InetAddress.getByName(host)
	if ip == nil {
		// 模组：getByName 抛 UnknownHostException（IOException 子类）→ EXCEPTION 分支（:111-114/:172-175）
		f.setStatus(txtConnFailed)
		return fbFailed(codePrefix+"_EXCEPTION", label+" exception: unknown host")
	}
	d := net.Dialer{Timeout: fbSocketTimeout} // :82/:143 connect(..., 3000)
	conn, err := d.Dial("tcp", net.JoinHostPort(host, strconv.Itoa(port)))
	if err == nil {
		if tc, ok := conn.(*net.TCPConn); ok {
			_ = tc.SetNoDelay(true) // :79-80 / :140-141 setTcpNoDelay(true)
		}
		_ = conn.Close() // :83-84 / :144-145 探测成功即关（socket=null 使 finally 不再重复关）
		// 模组的 "IPv6 connected: [host]:port" 日志带地址（:85/:146），本端只记成功标识
		fbLog("info", "fallback_direct_ok kind="+codePrefix)
		f.setStatus(txtConnBridgeSetup)         // :86/:147
		if !f.won.CompareAndSwap(false, true) { // :87/:148
			return fbCancelled()
		}
		f.settled.Store(true)                     // :91/:152
		return fbSuccess(label, host, port, mode) // :92/:153
	}

	kind, msg := classifyDialError(err)
	switch kind {
	case dialTimeout: // :93-96 / :154-157
		f.setStatus(txtConnTimeout)
		fbLog("info", "fallback_direct_timeout kind="+codePrefix)
		return fbFailed(codePrefix+"_TIMEOUT", label+" timeout")
	case dialNoRoute: // :97-100 / :158-161
		f.setStatus(txtConnFailed)
		fbLog("info", "fallback_direct_no_route kind="+codePrefix)
		return fbFailed(codePrefix+"_NO_ROUTE", label+" no route")
	case dialRefused: // :101-106 / :162-167
		f.setStatus(txtConnFailed)
		fbLog("info", "fallback_direct_refused kind="+codePrefix)
		return fbFailed(codePrefix+"_REFUSED", label+" refused")
	case dialConnectErr: // :107-110 / :168-171（ConnectException 但消息不含 "Connection refused"）
		f.setStatus(txtConnFailed)
		fbLog("info", "fallback_direct_connect_failed kind="+codePrefix)
		return fbFailed(codePrefix+"_ERROR", label+" failed")
	default: // :111-114 / :172-175（其余 IOException，含 DNS 失败）
		f.setStatus(txtConnFailed)
		fbLog("info", "fallback_direct_exception kind="+codePrefix)
		return fbFailed(codePrefix+"_EXCEPTION", label+" exception: "+msg)
	}
}

// tryIpv6Direct IPv6 直连（:71-130）。
func (f *connFallback) tryIpv6Direct(hostIpv6 string, hostPort int) <-chan fbConnectResult {
	out := make(chan fbConnectResult, 1)
	if hostIpv6 == "" { // :72（null 或 ""，Go 空串等价）
		out <- fbFailed("NO_IPV6", "没有IPv6地址") // :128
		close(out)
		return out
	}
	f.setStatus(txtConnProbing) // :73
	fbInFlight.Add(1)
	go func() {
		defer fbInFlight.Add(-1)
		defer close(out)
		out <- f.fbDirectAttempt(hostIpv6, hostPort, "IPV6", "IPv6", fbModeIPv6Direct)
	}()
	return out
}

// tryIpv4Direct IPv4 直连（:132-191）。
func (f *connFallback) tryIpv4Direct(hostIp string, hostPort int) <-chan fbConnectResult {
	out := make(chan fbConnectResult, 1)
	if hostIp == "" { // :133
		out <- fbFailed("NO_IPV4", "没有IPv4地址") // :189
		close(out)
		return out
	}
	f.setStatus(txtConnProbing) // :134
	fbInFlight.Add(1)
	go func() {
		defer fbInFlight.Add(-1)
		defer close(out)
		out <- f.fbDirectAttempt(hostIp, hostPort, "IPV4", "IPv4", fbModeIPv4Direct)
	}()
	return out
}

// ---- TCP 同时开放（tryTcpSimultaneousOpen，:302-412）----

// tryTcpSimultaneousOpen 从本地固定端口向对端映射端口反复 connect，
// 与对端的 connect 撞在一起时两侧各拿到一个已建连接（同时开放）。
// 窗口 10s、最多 5 次、本地口被占则退到随机口（randomPortMode）。
//
// SO_REUSEADDR 差异（不隐藏）：模组在 :327 显式 setReuseAddress(true)，
// 这是同时开放能绑上"正被入站 SYN 占用端口"的关键；Go 的 net.Dialer 在拨号路径上
// 不设 SO_REUSEADDR，而纯标准库单文件无法跨三平台设置该选项
// （syscall.SetsockoptInt 的 fd 参数类型在各 OS 不同，需要 _windows.go/_unix.go 拆分，
// 本轮文件所有权不含该拆分）——因此这里只绑本地端口、不置 reuse，
// 在真网络下 SimOpen 成功率可能低于模组，需要接 OS 文件对才能补齐。
func (f *connFallback) tryTcpSimultaneousOpen(remoteIP string, remotePort, localPort int) <-chan fbConnectResult {
	out := make(chan fbConnectResult, 1)
	if remoteIP == "" { // :303
		out <- fbFailed("NO_IP", "No remote IP") // :410
		close(out)
		return out
	}
	f.setStatus(txtConnProbing) // :304
	fbInFlight.Add(1)
	go func() {
		defer fbInFlight.Add(-1)
		defer close(out)
		if !f.fbPreCheck() { // :307
			out <- fbCancelled()
			return
		}
		if net.ParseIP(remoteIP) == nil { // :309-313 Bad addr
			out <- fbFailed("TCP_SIMOPEN_FAILED", "Bad addr: unknown host")
			return
		}
		deadline := time.Now().Add(fbSimOpenWindow)            // :315
		attempts := 0                                          // :316
		rng := rand.New(rand.NewSource(time.Now().UnixNano())) // :317 new Random()
		randomPortMode := false                                // :318

		for time.Now().Before(deadline) && attempts < fbSimOpenMaxAttempts && f.fbPreCheck() { // :320
			attempts++                                // :321
			d := net.Dialer{Timeout: fbSocketTimeout} // :358 connect(..., 3000)；TCP_NODELAY 为 Go 默认开（:326）
			if !randomPortMode {
				// :330 clientSocket.bind(new InetSocketAddress(randomPortMode ? 0 : localPort))
				// 随机口模式下 Go 不写 LocalAddr 即为系统挑选的临时口（等价 bind(0)）
				d.LocalAddr = &net.TCPAddr{Port: localPort}
			}
			conn, err := d.Dial("tcp", net.JoinHostPort(remoteIP, strconv.Itoa(remotePort)))
			if err == nil {
				f.setStatus(txtConnBridgeSetup)         // :360
				if !f.won.CompareAndSwap(false, true) { // :361-369
					_ = conn.Close()
					out <- fbCancelled()
					return
				}
				f.settled.Store(true)                                                       // :372
				_ = conn.Close()                                                            // :373 同时开放探到即弃（模组 close 后交给 connectViaBridge 另建）
				fbLog("info", "fallback_simopen_connected attempt="+strconv.Itoa(attempts)) // :359
				// :374 注意：模组此处 mode 给的是 IPV4_DIRECT（没有为 SimOpen 单列模式），label 才是 "TCP-SimOpen"，照抄
				out <- fbSuccess("TCP-SimOpen", remoteIP, remotePort, fbModeIPv4Direct)
				return
			}

			// :331-342 本地口被占（bind 失败）：首见时切随机口并重试
			if isBindError(err) && !randomPortMode {
				randomPortMode = true                                                 // :333
				fbLog("info", "fallback_simopen_local_port_occupied_use_random_port") // :334（模组带 localPort 数字，端口号在本端属消敏范围）
				continue
			}
			// :344-352 随机口模式下仍 bind 失败 → 直接判死
			if isBindError(err) && randomPortMode {
				fbLog("info", "fallback_simopen_bind_fail")                                   // :344
				out <- fbFailed("TCP_SIMOPEN_BIND_FAIL", "本地端口"+strconv.Itoa(localPort)+"被占") // :352
				return
			}

			// :375-398 连接类失败
			kind, msg := classifyDialError(err)
			fbLog("info", "fallback_simopen_attempt_failed attempt="+strconv.Itoa(attempts)+" kind="+dialKindName(kind)) // :376
			// :377 EADDRNOTAVAIL（"Cannot assign requested address"）：本地口不可用 → 退随机口。
			// 模组判的是 Java 原文大小写；Go 各平台给的小写形态不同（Linux 全小写），
			// 这里统一转小写判，避免该兜底分支在 Go 侧永不生效（同一错误进同一分支，语义不变）。
			if !randomPortMode && strings.Contains(strings.ToLower(msg), "cannot assign requested address") {
				randomPortMode = true
			}
			if !f.fbPreCheck() || !time.Now().Before(deadline) || attempts >= fbSimOpenMaxAttempts { // :388-390
				break
			}
			// :393 Thread.sleep(10 + rng.nextInt(90))；模组 InterruptedException → interrupt + break，
			// Go 无中断语义，用取消位等价退出（:394-397）
			interval := fbSimOpenBackoffBase + time.Duration(rng.Intn(int(fbSimOpenBackoffJitter/time.Millisecond)))*time.Millisecond
			if f.cancelled.Load() {
				break
			}
			time.Sleep(interval)
		}

		f.setStatus(txtConnFailed)                                                                            // :401
		out <- fbFailed("TCP_SIMOPEN_FAILED", "TCP SimOpen failed after "+strconv.Itoa(attempts)+" attempts") // :402
	}()
	return out
}

// ---- IPv6 可达性（:193-269）----

var (
	fbIPv6Mu       sync.Mutex // :193 synchronized（verifyIPv6Connectivity 是类级同步方法）
	fbIPv6Cached   *bool      // :38 ipv6ConnectivityCached
	fbIPv6CachedMs int64      // :39 ipv6ConnectivityCheckTime
	// fbInFlight 在途尝试计数：复刻 FALLBACK_EXECUTOR 的"活跃任务数"，供 fbShutdown 等 2s
	//（:25-29 newCachedThreadPool + :46 awaitTermination）。
	fbInFlight atomic.Int64
)

// fbVerifyIPv6Connectivity 先看本机有无 GUA，再真连一次 IPv6 DNS:53（:193-223），结果缓存 60s。
// 注意模组把"有无地址"与"能否连通"合进同一个缓存值，且无地址时直接缓存 false 不再探测。
// 调用点：StunDetector.verifyIPv6Connectivity()（StunDetector.java:116-126，**又加了一层 60s 缓存**）、
// RoomManager.java:279 与 ConnectionManager.java:6881/7218/1796。
func fbVerifyIPv6Connectivity() bool {
	fbIPv6Mu.Lock()
	defer fbIPv6Mu.Unlock()
	now := time.Now().UnixMilli()
	if fbIPv6Cached != nil && now-fbIPv6CachedMs < fbIPv6CheckCache.Milliseconds() { // :195-197
		return *fbIPv6Cached
	}
	if !fbHasIPv6Connectivity() { // :199-203
		res := false
		fbIPv6Cached, fbIPv6CachedMs = &res, now
		return false
	}
	for _, target := range fbIPv6TestTargets { // :207-217
		d := net.Dialer{Timeout: fbConnectTimeout} // :209 connect(..., 2000)
		conn, err := d.Dial("tcp", net.JoinHostPort(target, itoa(fbDNSServerPort)))
		if err == nil {
			_ = conn.Close()
			fbLog("info", "ipv6_connectivity_check_ok") // :210（模组打 target，本端不打：地址不得出口）
			res := true
			fbIPv6Cached, fbIPv6CachedMs = &res, now
			return true
		}
		fbLog("debug", "ipv6_connectivity_check_failed") // :215
	}
	fbLog("info", "ipv6_connectivity_check_all_failed") // :219
	res := false
	fbIPv6Cached, fbIPv6CachedMs = &res, now
	return false
}

// fbHasIPv6Connectivity 只看本机有没有"非回环、非链路本地、非 ULA(fc/fd)"的 IPv6 地址（:225-269）。
// 名字里的 connectivity 其实不含任何发包验证——模组如此，保持同名同逻辑。
// 注意模组只排 link-local（fe80::/10），**不**排 site-local（:250 只是打日志），
// 因为 Java 的 Inet6Address.isSiteLocalAddress() 对现行地址恒 false（IPv6 site-local 已废弃）。
func fbHasIPv6Connectivity() bool {
	ifaces, err := net.Interfaces() // :227
	if err != nil {
		fbLog("warn", "ipv6_check_error err="+err.Error()) // :264-265
		return false
	}
	for _, ifc := range ifaces {
		if ifc.Flags&net.FlagLoopback != 0 { // :231 !ni.isLoopback()
			continue
		}
		if ifc.Flags&net.FlagUp == 0 { // :231 ni.isUp()
			continue
		}
		addrs, err := ifc.Addrs()
		if err != nil {
			continue // 模组用枚举器，读地址不会二次抛；等价忽略
		}
		for _, a := range addrs {
			ip := addrIP(a)
			if ip == nil || ip.To4() != nil { // :236 addr instanceof Inet6Address
				continue
			}
			if ip.IsLoopback() || ip.IsLinkLocalUnicast() { // :236 !isLoopbackAddress() && !isLinkLocalAddress()
				continue
			}
			s := stripIPv6Zone(ip.String())                                 // :237-242 去掉 '%',zone
			if !strings.HasPrefix(s, "fd") && !strings.HasPrefix(s, "fc") { // :254
				fbLog("info", "ipv6_usable_global_address_found") // :255（模组带网卡名，本端不带：网卡名会暴露本机拓扑）
				return true
			}
		}
	}
	fbLog("info", "ipv6_no_usable_global_address") // :263
	return false
}

// fbGetLocalGlobalIPv6 取本机第一个 GUA（getLocalGlobalIpv6，:271-300）。
// 与 fbHasIPv6Connectivity 的差别只有：不打日志、异常全静默（:296 catch 空体）、返回地址。
// 模组按网卡枚举顺序返回第一项，没有"默认路由优先/有效期最长"之类的择优逻辑，照抄。
// 调用点：RoomManager.java:280（服务端没回 IPv6 时兜底）、ConnectionManager.java:1798（房主侧同兜底）。
func fbGetLocalGlobalIPv6() string {
	ifaces, err := net.Interfaces()
	if err != nil {
		return ""
	}
	for _, ifc := range ifaces {
		if ifc.Flags&net.FlagLoopback != 0 { // :277
			continue
		}
		if ifc.Flags&net.FlagUp == 0 {
			continue
		}
		addrs, err := ifc.Addrs()
		if err != nil {
			continue
		}
		for _, a := range addrs {
			ip := addrIP(a)
			if ip == nil || ip.To4() != nil { // :282 Inet6Address
				continue
			}
			if ip.IsLoopback() || ip.IsLinkLocalUnicast() { // :282
				continue
			}
			ipv6 := stripIPv6Zone(ip.String())                                    // :283-287
			if !strings.HasPrefix(ipv6, "fd") && !strings.HasPrefix(ipv6, "fc") { // :289
				return ipv6
			}
		}
	}
	return "" // :299 null
}

// ---- 停机（shutdown，:42-53）----

// fbShutdown 对应 VoxLinkMod.doShutdown() 里的 ConnectionFallback.shutdown()（VoxLinkMod.java:103）。
// 模组语义：shutdown() 不再接受新任务 → awaitTermination(2s) → 超时才 shutdownNow()。
// Go 侧没有"池"，用 fbInFlight 计数复刻同一件事：最多等 2s 让在途尝试自己收口
// （每条尝试的 connect 超时是 2-3s，量级一致），到点不强杀（模组也不强杀，daemon 线程随 JVM 走），
// 然后清掉 IPv6 可达性缓存，避免新会话沿用上一个网络的判据。
func fbShutdown() {
	deadline := time.Now().Add(fbShutdownWait)
	for fbInFlight.Load() > 0 && time.Now().Before(deadline) { // :46 awaitTermination(2, SECONDS)
		time.Sleep(20 * time.Millisecond)
	}
	fbIPv6Mu.Lock()
	fbIPv6Cached = nil
	fbIPv6CachedMs = 0
	fbIPv6Mu.Unlock()
}

// ---- 错误分类 ----

type dialErrorKind int

const (
	dialUnknown    dialErrorKind = iota // 落模组的 IOException 分支
	dialTimeout                         // SocketTimeoutException
	dialNoRoute                         // NoRouteToHostException
	dialRefused                         // ConnectException 且消息含 "Connection refused"
	dialConnectErr                      // ConnectException 的其他情形
)

func dialKindName(k dialErrorKind) string {
	switch k {
	case dialTimeout:
		return "timeout"
	case dialNoRoute:
		return "no_route"
	case dialRefused:
		return "refused"
	case dialConnectErr:
		return "connect_error"
	}
	return "exception"
}

// classifyDialError Java 异常 → Go error 的映射表（本文件唯一的翻译决策点，逐条给依据）：
//
//	SocketTimeoutException    → net.Error 且 Timeout()==true                 → dialTimeout
//	NoRouteToHostException    → 消息含 "no route to host"（EHOSTUNREACH）      → dialNoRoute
//	                          注：ENETUNREACH 在 Java 是 NetworkUnreachableException
//	                          （SocketException 子类，不是 NoRouteToHostException），
//	                          模组走 IOException 分支，故这里同样不并入 NO_ROUTE
//	ConnectException(refused) → 消息含 "connection refused"（ECONNREED）      → dialRefused
//	ConnectException(other)   → *net.OpError 且 Op=="dial" 且非 DNS 错误       → dialConnectErr
//	IOException（其余）        → DNS 失败、权限、网卡掉线等                      → dialUnknown
//
// 判 "Connection refused" 时把消息转小写：Java 给 "Connection refused"、Go 给 "connection refused"，
// 模组是大小写敏感 contains（:104），这里为保持"同一个错误进同一个分支"而放宽大小写。
func classifyDialError(err error) (dialErrorKind, string) {
	if err == nil {
		return dialUnknown, ""
	}
	msg := err.Error()
	low := strings.ToLower(msg)

	var ne net.Error
	if errors.As(err, &ne) && ne.Timeout() { // :93/:154
		return dialTimeout, msg
	}
	if strings.Contains(low, "no route to host") { // :97/:158
		return dialNoRoute, msg
	}
	// :104/:165 模组判的是 Java 规范化后的 "Connection refused"——JVM 在 Windows/Linux/macOS
	// 上都给这一个串；Go 各 OS 不同：类 Unix 给 "connection refused"，
	// Windows 给 "…actively refused it"（WSAECONNREFUSED 的 Winsock 文案）。
	// 两种都判，否则同一个"端口没人听"在三平台上会落进不同分支。
	if strings.Contains(low, "connection refused") || strings.Contains(low, "actively refused") {
		return dialRefused, msg
	}
	var dnsErr *net.DNSError
	if errors.As(err, &dnsErr) { // getByName 失败：Java 在解析阶段抛 → IOException 分支（:111/:172）
		return dialUnknown, msg
	}
	var opErr *net.OpError
	if errors.As(err, &opErr) && opErr.Op == "dial" { // :101/:162 ConnectException 的等价面
		return dialConnectErr, msg
	}
	return dialUnknown, msg
}

// isBindError 判定"本地端口绑定失败"（模组靠把 bind() 单独 try 出来识别，:331）。
// Go 的 Dialer 把 bind 与 connect 合进一次调用，只能按错误消息特征判：
// EADDRINUSE 在 Linux/macOS 给 "address already in use"，Windows 给 WSAEADDRINUSE
// （Go 翻成 "Only one usage of each socket address..."）。
// 这是本文件里唯一"靠消息文本判错误"的地方，无法在没有真网络的环境下验证 Windows 分支。
func isBindError(err error) bool {
	if err == nil {
		return false
	}
	if low := strings.ToLower(err.Error()); strings.Contains(low, "address already in use") ||
		strings.Contains(low, "only one usage of each socket address") {
		return true
	}
	return false
}

// ---- 小工具 ----

func addrIP(a net.Addr) net.IP {
	switch v := a.(type) {
	case *net.IPNet:
		return v.IP
	case *net.IPAddr:
		return v.IP
	}
	return nil
}

// stripIPv6Zone 去掉 Go/Java 地址串里的 "%zone" 后缀（模组 :238-242 indexOf('%') 截断）。
func stripIPv6Zone(s string) string {
	if i := strings.IndexByte(s, '%'); i >= 0 {
		return s[:i]
	}
	return s
}

// fbLogSink 由 App 启动时注入（hunk 见交付报告）。
var fbLogSink func(level, msg string)

// fbLog 兜底链日志出口。
// 模组在这里打了大量 `[host]:port` 形态的地址（:85/:94/:103/:112/:146/:155/:164/:173/:356/:359/:376），
// 本端消敏规则不允许，一律换成标识串；具体成功/失败原因由 channel 里的错误码承载给上层。
func fbLog(level, msg string) {
	if fbLogSink != nil {
		fbLogSink(level, msg)
		return
	}
	log.Printf("[%s] %s", level, sanitizeNetLogMessage(msg))
}
