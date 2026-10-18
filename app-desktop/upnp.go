package main

// upnp.go — IGD（家用路由器）端口映射，逐段移植模组 network/UPnPManager.java（1.2.0，610 行）。
//
// 只做 Java→Go 的形态转换：SSDP 发现、设备描述 XML 截取解析、SOAP AddPortMapping /
// DeletePortMapping、3600s 租约 + 50 分钟续租、停机清理、启动预发现。常量与尝试顺序全部照抄，
// 每条常量后带 `源文件:行号`。
//
// 平台口径：纯标准库（net / net/http / time），无 CGO、无 OS 专属调用，因此 desktop 与 cli
// 两个产物、Windows/Linux/macOS 三平台共用这一份文件，不需要 _windows.go/_other.go 拆分。
//
// 与模组的三处有意差异（全部在交付报告列明，不隐藏）：
//  1. 日志：模组在 LOGGER 里打印 gateway.controlUrl 与本机 IP（:290-293），本端所有网络日志
//     走 netLog 消敏口径，改为 i18n-neutral 标识串 + `port=`/`internal=` 形式，绝不落地址字面量。
//  2. DESCRIPTION/SOAP 响应体加了读取上限（模组 readAllBytes 无上限 :424/:504）。
//  3. closePort 的 removeIf 只摘键不 cancel 的"空转租约"（:126-131）如实复刻。

import (
	"bytes"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/url"
	"sort"
	"strings"
	"sync"
	"time"
)

// ---- 常量（UPnPManager.java）----

const (
	// upnpSSDPAddr SSDP 地址（UPnPManager.java:34）。
	upnpSSDPAddr = "239.255.255.250"
	// upnpSSDPPort SSDP 端口（:35）。
	upnpSSDPPort = 1900
	// upnpSearchTimeout 搜索超时 1500ms（:36；用于 setSoTimeout :334 与收包 deadline :339）。
	upnpSearchTimeout = 1500 * time.Millisecond
	// upnpHTTPTimeout DESCRIPTION/SOAP 的 connect+read 各 3000ms（:37，:419-420，:489-490）。
	upnpHTTPTimeout = 3000 * time.Millisecond
	// upnpCacheDuration 网关与本机 IP 的缓存时长 60s（:41 CACHE_DURATION=60000L；判过期 :237/:251）。
	upnpCacheDuration = 60 * time.Second
	// upnpRenewLead 续租提前量：租约 3600s，50 分钟续一次留 10 分钟余量（:46-48）。
	upnpRenewLead = 50 * time.Minute
	// upnpLeaseSeconds addPortMapping 的租约 3600s（:160、:204 实参）。
	upnpLeaseSeconds = 3600
	// upnpSSDPBufSize SSDP 收包缓冲 4096B（:337）。
	upnpSSDPBufSize = 4096
	// upnpJoinTimeout 发现线程最长等待 2000ms（:394 t.join(2000L)）。
	upnpJoinTimeout = 2000 * time.Millisecond
	// upnpReadBodyMax 响应体读取上限：模组无上限（:424/:504 readAllBytes），此处加闸防被异常设备拖爆内存。
	upnpReadBodyMax = 4 << 20
)

// upnpSearchMessages 两条 M-SEARCH 报文（:305-308），字节与顺序照抄：
// 先 device:InternetGatewayDevice:1，再 service:WANIPConnection:1。
var upnpSearchMessages = []string{
	"M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\nMAN: \"ssdp:discover\"\r\nMX: 3\r\n\r\n",
	"M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nST: urn:schemas-upnp-org:service:WANIPConnection:1\r\nMAN: \"ssdp:discover\"\r\nMX: 3\r\n\r\n",
}

// upnpGateway 网关三要素（record GatewayInfo，:605）。
type upnpGateway struct {
	baseURL  string
	controlU string
	svcType  string
}

// upnpResult 映射结果（record UPnPResult，:608）：available=是否找到网关，
// success=映射是否成功，externalPort=请求的外部端口。
type upnpResult struct {
	available    bool
	success      bool
	externalPort int
}

// ---- 进程级状态（模组是类级 static，这里包级 + 一把锁护住缓存/映射表/续租表）----

var (
	upnpMu           sync.Mutex
	upnpCachedGW     *upnpGateway // :39 cachedGateway
	upnpGWCacheMs    int64        // :42 gatewayCacheTime
	upnpCachedLocal  string       // :40 cachedLocalIp（"" 等价 null）
	upnpLocalCacheMs int64        // :43 localIpCacheTime
	upnpMappedPorts  = map[string]bool{}
	upnpRenewTasks   = map[string]int64{} // :56 key=`internal_external_protocol` → 续租任务 id
	upnpStopped      bool                 // :57 stopped
	upnpStartDone    bool                 // :45 startupAttempted
	upnpStartMu      sync.Mutex           // :266 synchronized (UPnPManager.class)
	upnpSched        *seqScheduler
	upnpSchedOnce    sync.Once
)

// upnpScheduler 续租调度器懒建（模组 :50-54 静态单线程 daemon 调度器）。
// 懒建只为不在"纯探测"路径上白起 goroutine，语义与静态无差别。
func upnpScheduler() *seqScheduler {
	upnpSchedOnce.Do(func() { upnpSched = newSeqScheduler() })
	return upnpSched
}

// ---- 对外入口（与模组 public static 方法一一对应）----

// upnpOpenPort TCP 映射（openPort(port, description)，:59-61）。
func upnpOpenPort(port int, description string) upnpResult {
	return upnpOpenPortProto(port, description, "TCP")
}

// upnpOpenUdpPort UDP 映射（openUdpPort，:63-65）。
func upnpOpenUdpPort(port int, description string) upnpResult {
	return upnpOpenPortProto(port, description, "UDP")
}

// upnpOpenPortProto 主体（openPort(port, description, protocol)，:67-99）。
// 协议非法时按模组抛 IllegalArgumentException（:68-70）：调用方只应传 TCP/UDP，
// 传错是编程错误，静默降级会把配置问题藏起来。
func upnpOpenPortProto(port int, description, protocol string) upnpResult {
	if protocol != "TCP" && protocol != "UDP" {
		panic(fmt.Sprintf("Invalid protocol: %s", protocol)) // :69
	}
	// 模组整段 try/catch(Exception) → LOGGER.error + 返回 (false,false,port)（:95-98）
	res, err := func() (upnpResult, error) {
		gw, err := upnpCachedGateway()
		if err != nil {
			return upnpResult{}, err
		}
		if gw == nil {
			upnpLog("info", "upnp_no_gateway") // :75
			return upnpResult{available: false, success: false, externalPort: port}, nil
		}
		localIP, err := upnpCachedLocalIP()
		if err != nil {
			return upnpResult{}, err
		}
		if localIP == "" {
			upnpLog("warn", "upnp_no_local_ip") // :80
			return upnpResult{available: true, success: false, externalPort: port}, nil
		}
		body := upnpBuildAddPortMapping(port, localIP, description, protocol)
		resp, err := upnpSendSOAP(gw, "AddPortMapping", body)
		if err != nil {
			return upnpResult{}, err
		}
		if resp != "" && !strings.Contains(resp, "errorCode") { // :85
			upnpMu.Lock()
			upnpMappedPorts[fmt.Sprintf("%d_%s", port, protocol)] = true // :86
			upnpMu.Unlock()
			upnpLog("info", fmt.Sprintf("upnp_port_opened protocol=%s port=%d", protocol, port)) // :87
			return upnpResult{available: true, success: true, externalPort: port}, nil
		}
		upnpLog("warn", fmt.Sprintf("upnp_port_open_failed protocol=%s port=%d", protocol, port)) // :90
		return upnpResult{available: true, success: false, externalPort: port}, nil
	}()
	if err != nil {
		upnpLog("error", "upnp_error err="+err.Error()) // :96
		return upnpResult{available: false, success: false, externalPort: port}
	}
	return res
}

// upnpClosePort 关 TCP 映射（closePort(port)，:101-103）。
func upnpClosePort(port int) { upnpClosePortProto(port, "TCP") }

// upnpCloseUdpPort 关 UDP 映射（closeUdpPort，:105-107）。
func upnpCloseUdpPort(port int) { upnpClosePortProto(port, "UDP") }

// upnpClosePortProto 主体（:109-132）。
func upnpClosePortProto(port int, protocol string) {
	key := fmt.Sprintf("%d_%s", port, protocol)
	upnpMu.Lock()
	_, mapped := upnpMappedPorts[key] // :110 containsKey
	upnpMu.Unlock()
	if mapped {
		// :111-123 try/catch(Exception)：失败只记日志，不影响后续租约键清理
		gw, err := upnpCachedGateway()
		if err != nil {
			upnpLog("error", "upnp_close_error err="+err.Error()) // :122
		} else if gw != nil { // :112-115 gateway==null → return（仍会走下面的 removeIf）
			body := upnpBuildDeletePortMapping(port, protocol)
			_, _ = upnpSendSOAP(gw, "DeletePortMapping", body) // :118 返回值不判（模组同样不判）
			upnpMu.Lock()
			delete(upnpMappedPorts, key) // :119
			upnpMu.Unlock()
			upnpLog("info", fmt.Sprintf("upnp_port_closed protocol=%s port=%d", protocol, port)) // :120
		}
	}
	// :126-131 renewTasks.keySet().removeIf(endsWith("_"+protocol) && contains("_"+port+"_"))
	// 忠实复刻一处模组的"漏"：removeIf 只摘键、**不 cancel** 对应的 ScheduledFuture。
	// 于是该续租任务永远留在调度器里，每次触发都走"键已不在 → 取消自身句柄取不到 → return"的空转分支
	// （scheduleWithFixedDelay 的排期不会被这种 return 终止）。每个关掉的端口漏一条空转排期。
	// 改成"关端口即停租约任务"属于行为变更（会影响正在续租的其它同名映射），需另行拍板，这里不动。
	upnpDropRenewKeys(port, protocol)
}

// upnpStop 停续租调度器并清空全部续租任务（stop()，:134-145）。
// 模组的 onShutdown/unload 路径**没有任何调用点**（grep 全仓仅定义处），
// 本端接线建议见交付报告（App.Shutdown 与 upnp 侧 leaveInternal）。
func upnpStop() {
	upnpMu.Lock()
	if upnpStopped { // :136-138 幂等
		upnpMu.Unlock()
		return
	}
	upnpStopped = true
	ids := make([]int64, 0, len(upnpRenewTasks))
	for _, id := range upnpRenewTasks {
		ids = append(ids, id)
	}
	upnpRenewTasks = map[string]int64{} // :143 renewTasks.clear()
	upnpMu.Unlock()
	if upnpSched != nil {
		for _, id := range ids {
			upnpSched.cancel(id) // :140-142 f.cancel(false)
		}
		upnpSched.shutdownNow() // :144 RENEW_SCHEDULER.shutdownNow()
	}
}

// upnpAddPortMapping 带 3600s 租约的 UDP 映射 + 50 分钟续租
// （addPortMapping(internalPort, externalPort)，:147-177）。
// 与 upnpOpenUdpPort 的区别：这条登记续租（唯一会续租的入口，模组调用点 CM:7451）。
func upnpAddPortMapping(internalPort, externalPort int) bool {
	key := fmt.Sprintf("%d_%d_UDP", internalPort, externalPort) // :148
	gw, err := upnpCachedGateway()
	if err != nil {
		upnpLog("error", "upnp_mapping_error err="+err.Error()) // :174
		return false
	}
	if gw == nil {
		upnpLog("info", "upnp_no_gateway") // :152
		return false
	}
	localIP, err := upnpCachedLocalIP()
	if err != nil {
		upnpLog("error", "upnp_mapping_error err="+err.Error())
		return false
	}
	if localIP == "" {
		upnpLog("warn", "upnp_no_local_ip") // :157
		return false
	}
	body := upnpBuildAddPortMappingLease(internalPort, externalPort, localIP, "VoxLink", "UDP", upnpLeaseSeconds) // :160
	resp, err := upnpSendSOAP(gw, "AddPortMapping", body)
	if err != nil {
		upnpLog("error", "upnp_mapping_error err="+err.Error())
		return false
	}
	if resp != "" && !strings.Contains(resp, "errorCode") { // :162
		upnpMu.Lock()
		upnpMappedPorts[fmt.Sprintf("%d_UDP", externalPort)] = true // :163
		upnpMu.Unlock()
		upnpScheduleRenewal(key, internalPort, externalPort) // :165 注释原文：避免 3600s 租约到期后静默失效
		return true
	}
	upnpLog("warn", fmt.Sprintf("upnp_udp_mapping_failed port=%d", externalPort)) // :168
	return false
}

// upnpAddPortMappingPort 内外部同端口（addPortMapping(port)，:232-234）。
func upnpAddPortMappingPort(port int) bool { return upnpAddPortMapping(port, port) }

// upnpScheduleRenewal 登记/替换一条映射的续租排期（:179-223）。
// 必须在持有 upnpMu 时调用？不必——本函数自己加锁，与模组的 ConcurrentHashMap 等价。
func upnpScheduleRenewal(key string, internalPort, externalPort int) {
	upnpMu.Lock()
	if upnpStopped { // :180-182 stop 之后不再排新租约
		upnpMu.Unlock()
		return
	}
	if old, ok := upnpRenewTasks[key]; ok { // :183-187 同名键重复 add 时先取消旧任务
		delete(upnpRenewTasks, key)
		upnpMu.Unlock()
		upnpScheduler().cancel(old)
		upnpMu.Lock()
	}
	id := upnpScheduler().scheduleRepeat(upnpRenewLead, upnpRenewLead, func() {
		upnpRenewTick(key, internalPort, externalPort) // :189-219
	})
	upnpRenewTasks[key] = id // :222
	upnpMu.Unlock()
}

// upnpRenewTick 一次续租（:190-218 的任务体）。
func upnpRenewTick(key string, internalPort, externalPort int) {
	mappedKey := fmt.Sprintf("%d_UDP", externalPort) // :190 判据
	upnpMu.Lock()
	_, stillMapped := upnpMappedPorts[mappedKey]
	if upnpStopped || !stillMapped {
		if id, ok := upnpRenewTasks[key]; ok { // :191-195 取不到键（已被 removeIf 摘走）时什么都不做
			delete(upnpRenewTasks, key)
			upnpMu.Unlock()
			if upnpSched != nil {
				upnpSched.cancel(id)
			}
			return
		}
		upnpMu.Unlock()
		return
	}
	upnpMu.Unlock()

	gw, gwErr := upnpCachedGateway() // :198
	ip, ipErr := upnpCachedLocalIP() // :199
	if gwErr != nil || ipErr != nil {
		upnpLog("warn", "upnp_renew_exception "+firstErr(gwErr, ipErr)) // :216-218
		return
	}
	if gw == nil || ip == "" { // :200-203
		upnpLog("warn", fmt.Sprintf("upnp_renew_skipped internal=%d external=%d", internalPort, externalPort))
		return
	}
	body := upnpBuildAddPortMappingLease(internalPort, externalPort, ip, "VoxLink", "UDP", upnpLeaseSeconds) // :204
	resp, err := upnpSendSOAP(gw, "AddPortMapping", body)
	if err != nil {
		upnpLog("warn", "upnp_renew_exception "+err.Error()) // :216-218
		return
	}
	if resp == "" || strings.Contains(resp, "errorCode") { // :206-213
		upnpLog("warn", fmt.Sprintf("upnp_renew_failed internal=%d external=%d", internalPort, externalPort))
		upnpMu.Lock()
		delete(upnpMappedPorts, mappedKey) // :208 "dropping mapping"
		if id, ok := upnpRenewTasks[key]; ok {
			delete(upnpRenewTasks, key)
			upnpMu.Unlock()
			if upnpSched != nil {
				upnpSched.cancel(id) // :209-212
			}
		} else {
			upnpMu.Unlock()
		}
		return
	}
	upnpLog("info", fmt.Sprintf("upnp_renew_ok internal=%d external=%d", internalPort, externalPort)) // :214
}

// upnpDropRenewKeys 复刻 :126-131 的 removeIf（只摘键，不 cancel；原因见 upnpClosePortProto）。
func upnpDropRenewKeys(port int, protocol string) {
	suffix := "_" + protocol
	infix := fmt.Sprintf("_%d_", port)
	upnpMu.Lock()
	defer upnpMu.Unlock()
	for k := range upnpRenewTasks {
		if strings.HasSuffix(k, suffix) && strings.Contains(k, infix) {
			delete(upnpRenewTasks, k)
		}
	}
}

// upnpCancelRenewal cancelRenewal(key)（:225-230）。模组内该方法当前无调用点，保留同名入口。
func upnpCancelRenewal(key string) {
	upnpMu.Lock()
	id, ok := upnpRenewTasks[key]
	if ok {
		delete(upnpRenewTasks, key)
	}
	upnpMu.Unlock()
	if ok && upnpSched != nil {
		upnpSched.cancel(id)
	}
}

// upnpTryMapAtStartup 启动期预发现（tryMapAtStartup，:264-301）：只暖网关/本机 IP 两个缓存，
// 不建任何映射；进程生命周期内只跑一次（:265-272 的双检锁）。
func upnpTryMapAtStartup() {
	upnpStartMu.Lock()
	if upnpStartDone {
		upnpStartMu.Unlock()
		return
	}
	upnpStartDone = true
	upnpStartMu.Unlock()

	upnpLog("info", "upnp_startup_prediscovery_begin") // :299
	// 模组 CompletableFuture.runAsync（:274）→ 一条不阻塞调用方的 goroutine
	// （VoxLinkClient.java:72-74 在客户端初始化线程上调用，绝不能卡住游戏/界面启动）
	go func() {
		t0 := time.Now()
		gw, err := upnpCachedGateway()
		if err != nil {
			upnpLog("warn", "upnp_startup_prediscovery_exception err="+err.Error()) // :294-295
			return
		}
		if gw == nil {
			upnpLog("info", fmt.Sprintf("upnp_startup_prediscovery_no_gateway elapsed_ms=%d", time.Since(t0).Milliseconds())) // :279-281
			return
		}
		ip, err := upnpCachedLocalIP()
		if err != nil {
			upnpLog("warn", "upnp_startup_prediscovery_exception err="+err.Error())
			return
		}
		if ip == "" {
			upnpLog("warn", fmt.Sprintf("upnp_startup_prediscovery_no_local_ip elapsed_ms=%d", time.Since(t0).Milliseconds())) // :284-288
			return
		}
		// 模组此处打印 controlUrl 与本机 IP（:290-293）；本端只报成功与耗时（消敏口径）
		upnpLog("info", fmt.Sprintf("upnp_startup_prediscovery_ok elapsed_ms=%d", time.Since(t0).Milliseconds()))
	}()
}

// ---- 缓存（:236-262）----

// upnpCachedGateway 命中 60s 内的缓存则复用，否则重新发现（:236-248）。
// 发现失败**不**写缓存（模组只在 gateway!=null 时刷新时间戳）→ 下次调用仍会重试 SSDP。
func upnpCachedGateway() (*upnpGateway, error) {
	upnpMu.Lock()
	if upnpCachedGW != nil && time.Now().UnixMilli()-upnpGWCacheMs < upnpCacheDuration.Milliseconds() {
		gw := upnpCachedGW
		upnpMu.Unlock()
		return gw, nil
	}
	upnpMu.Unlock()

	gw, err := upnpDiscoverGateway()
	if err != nil {
		return nil, err
	}
	if gw != nil {
		upnpMu.Lock()
		upnpCachedGW = gw
		upnpGWCacheMs = time.Now().UnixMilli()
		upnpMu.Unlock()
	}
	return gw, nil
}

// upnpCachedLocalIP 本机 IPv4 的 60s 缓存（:250-262）；返回 "" 等价模组的 null。
func upnpCachedLocalIP() (string, error) {
	upnpMu.Lock()
	if upnpCachedLocal != "" && time.Now().UnixMilli()-upnpLocalCacheMs < upnpCacheDuration.Milliseconds() {
		ip := upnpCachedLocal
		upnpMu.Unlock()
		return ip, nil
	}
	upnpMu.Unlock()

	ip, err := upnpLocalIP()
	if err != nil {
		return "", err
	}
	if ip != "" {
		upnpMu.Lock()
		upnpCachedLocal = ip
		upnpLocalCacheMs = time.Now().UnixMilli()
		upnpMu.Unlock()
	}
	return ip, nil
}

// ---- SSDP 发现（:303-413）----

// upnpDiscoverGateway 每个本机 IPv4 × 两条 ST 并发发一次 M-SEARCH，首个解析成功者胜
// （:303-413）。返回 nil 表示未发现（无网关/全无响应/全部解析失败），不当作错误。
func upnpDiscoverGateway() (*upnpGateway, error) {
	addrs, err := upnpLocalAddresses() // :304
	if err != nil {
		return nil, err
	}

	var (
		wg      sync.WaitGroup
		stateMu sync.Mutex
		found   *upnpGateway // :309 AtomicReference
		socks   []*net.UDPConn
		closed  bool // :312 AtomicBoolean socketsClosed
	)

	// :350-364 与 :397-410 是同一段体（模组在两处各写一遍）
	closeAll := func() {
		stateMu.Lock()
		closed = true
		for _, s := range socks {
			_ = s.Close()
		}
		socks = nil
		stateMu.Unlock()
	}

	for _, addr := range addrs { // :314
		for _, msg := range upnpSearchMessages { // :315
			stateMu.Lock()
			done := found != nil
			stateMu.Unlock()
			if done {
				break // :316-318 found 已定则不再起新搜索（模组的 break 只跳出内层 for，照抄）
			}
			wg.Add(1)
			// :320-386 "VoxLink-SSDP" daemon 线程
			go func(localIP net.IP, search string) {
				defer wg.Done()
				sock, err := net.ListenUDP("udp", &net.UDPAddr{IP: localIP}) // :324 new DatagramSocket(0, localAddr)
				if err != nil {
					return // :377 catch(Exception){} 静默
				}
				stateMu.Lock()
				if closed { // :325-331 别的线程已收口：关掉刚建的 socket 直接退出
					stateMu.Unlock()
					_ = sock.Close()
					return
				}
				socks = append(socks, sock)
				stateMu.Unlock()

				if _, err := sock.WriteToUDP([]byte(search), &net.UDPAddr{IP: net.ParseIP(upnpSSDPAddr), Port: upnpSSDPPort}); err != nil { // :336
					return // :377
				}
				deadline := time.Now().Add(upnpSearchTimeout) // :339 long deadline = now + 1500
				_ = sock.SetReadDeadline(deadline)            // :334 setSoTimeout(1500)
				buf := make([]byte, upnpSSDPBufSize)          // :337-338
				for time.Now().Before(deadline) {             // :341 while (now < deadline && found == null)
					stateMu.Lock()
					got := found != nil
					stateMu.Unlock()
					if got {
						return
					}
					n, _, err := sock.ReadFromUDP(buf)
					if err != nil {
						// :367-375 SocketTimeoutException → return；SocketException → debug 后 return。
						// Go 两者都到这里，且 debug 级日志在本端不落 IP，直接返回。
						return
					}
					response := string(buf[:n])
					location := upnpExtractHeader(response, "LOCATION") // :345
					if location == "" {
						continue
					}
					gw := upnpParseGateway(location) // :347
					if gw != nil {
						stateMu.Lock()
						if found == nil { // :349 compareAndSet(null, gateway)
							found = gw
						}
						stateMu.Unlock()
						closeAll() // :350-364
						return
					}
				}
			}(addr, msg)
		}
	}

	doneCh := make(chan struct{})
	go func() { wg.Wait(); close(doneCh) }()
	select { // :393-395 逐个 t.join(2000L)（模组每线程各等 2s；这里取整体 2s 上界，见报告差异说明）
	case <-doneCh:
	case <-time.After(upnpJoinTimeout):
	}
	closeAll() // :397-410

	stateMu.Lock()
	res := found
	stateMu.Unlock()
	return res, nil
}

// upnpLocalAddresses 本机非回环 IPv4 列表（getLocalAddresses，:579-598）。
// 过滤：!isLoopback() && isUp() && Inet4Address && !isLoopbackAddress()。
// 模组还判 !ni.isVirtual()：JDK 文档明确该方法当前实现恒返回 false（历史遗留 API），
// Go 侧无对应概念，等价于无此判据，不是省略。
func upnpLocalAddresses() ([]net.IP, error) {
	ifaces, err := net.Interfaces() // :581 NetworkInterface.getNetworkInterfaces()
	if err != nil {
		return nil, err
	}
	var out []net.IP
	for _, ifc := range ifaces {
		if ifc.Flags&net.FlagLoopback != 0 { // :585
			continue
		}
		if ifc.Flags&net.FlagUp == 0 { // :585 ni.isUp()
			continue
		}
		addrs, err := ifc.Addrs()
		if err != nil {
			continue // 模组的枚举器在构造期完成，不等价地抛；这里忽略读不到地址的网卡
		}
		for _, a := range addrs {
			var ip net.IP
			switch v := a.(type) {
			case *net.IPNet:
				ip = v.IP
			case *net.IPAddr:
				ip = v.IP
			}
			if ip == nil || ip.IsLoopback() { // :590 !addr.isLoopbackAddress()
				continue
			}
			if ip.To4() == nil { // :590 addr instanceof Inet4Address
				continue
			}
			out = append(out, ip)
		}
	}
	return out, nil
}

// upnpLocalIP 取枚举首项（getLocalIp，:600-603）。
// 模组没有"选默认路由所在网卡"之类的逻辑，就是网卡枚举顺序的第一项，照抄。
func upnpLocalIP() (string, error) {
	addrs, err := upnpLocalAddresses()
	if err != nil {
		return "", err
	}
	if len(addrs) == 0 {
		return "", nil
	}
	return addrs[0].String(), nil
}

// ---- 设备描述解析（:415-482）----

// upnpParseGateway GET location 指向的设备描述 XML，抽 serviceType + controlURL。
// 索引式截取逐条照抄（含命名空间前缀的兜底分支）：路由器实现五花八门（带前缀/不带前缀/
// 自闭合），模组这套字符串扫描是多年实测口径，换成 DOM 解析会改变宽容度。
func upnpParseGateway(location string) *upnpGateway {
	u, err := url.Parse(location) // :417 URI.create(location).toURL()
	if err != nil {
		upnpLog("debug", "upnp_gateway_parse_failed") // :477-479
		return nil
	}
	if u.Scheme != "http" && u.Scheme != "https" {
		// 模组强转 HttpURLConnection，非 http(s) 抛 ClassCastException → catch → null（:418）
		return nil
	}
	client := &http.Client{Timeout: upnpHTTPTimeout} // :419-420
	resp, err := client.Get(u.String())
	if err != nil {
		upnpLog("debug", "upnp_gateway_parse_failed")
		return nil
	}
	defer resp.Body.Close()
	b, err := io.ReadAll(io.LimitReader(resp.Body, upnpReadBodyMax)) // :423-424 readAllBytes
	if err != nil {
		upnpLog("debug", "upnp_gateway_parse_failed")
		return nil
	}
	doc := string(b)

	serviceType := ""
	controlURL := ""
	hasType, hasURL := false, false

	stIdx := strings.Index(doc, ":WANIPConnection:") // :431
	if stIdx < 0 {
		stIdx = strings.Index(doc, ":WANPPPConnection:") // :432-434
	}
	if stIdx >= 0 {
		// :437-440 lastIndexOf(tag, stIdx)：找 stIdx 之前最近的那个开标签
		typeStart := strings.LastIndex(doc[:stIdx+1], "<serviceType>")
		if typeStart < 0 {
			typeStart = strings.LastIndex(doc[:stIdx+1], ":serviceType>")
		}
		from := 0
		if typeStart >= 0 {
			from = typeStart
		}
		typeEnd := indexOfFrom(doc, "</serviceType>", from) // :442
		if typeEnd < 0 && typeStart >= 0 {                  // :443-448 命名空间闭合兜底
			gt := indexOfFrom(doc, ">", typeStart) // xml.indexOf(62, typeStart)
			if gt >= 0 {
				nsTypeEnd := indexOfFrom(doc, ":serviceType>", gt+1)
				if nsTypeEnd > 0 {
					typeEnd = strings.LastIndex(doc[:nsTypeEnd+1], "</")
				}
			}
		}
		if typeStart >= 0 && typeEnd > typeStart {
			gt := indexOfFrom(doc, ">", typeStart)
			if gt >= 0 && gt+1 <= typeEnd {
				serviceType = doc[gt+1 : typeEnd] // :451
				hasType = true
			}
		}

		urlFrom := 0
		if typeEnd >= 0 {
			urlFrom = typeEnd // :454 从 typeEnd 之后开始找
		}
		urlStart := indexOfFrom(doc, "<controlURL>", urlFrom)
		if urlStart < 0 {
			urlStart = indexOfFrom(doc, ":controlURL>", urlFrom) // :455-457
		}
		endFrom := 0
		if urlStart >= 0 {
			endFrom = urlStart
		}
		urlEnd := indexOfFrom(doc, "</controlURL>", endFrom) // :459
		if urlEnd < 0 && urlStart >= 0 {                     // :460-465
			gt := indexOfFrom(doc, ">", urlStart)
			if gt >= 0 {
				nsUrlEnd := indexOfFrom(doc, ":controlURL>", gt+1)
				if nsUrlEnd > 0 {
					urlEnd = strings.LastIndex(doc[:nsUrlEnd+1], "</")
				}
			}
		}
		if urlStart >= 0 && urlEnd > urlStart {
			gt := indexOfFrom(doc, ">", urlStart)
			if gt >= 0 && gt+1 <= urlEnd {
				controlURL = doc[gt+1 : urlEnd] // :468
				hasURL = true
			}
		}
	}

	if hasType && hasURL { // :472
		port := u.Port()
		if port == "" { // :473 url.getPort()!=-1 ? getPort() : getDefaultPort()
			if u.Scheme == "http" {
				port = "80"
			} else {
				port = "443"
			}
		}
		base := u.Scheme + "://" + u.Hostname() + ":" + port // :474
		return &upnpGateway{baseURL: base, controlU: controlURL, svcType: serviceType}
	}
	return nil
}

// ---- SOAP（:484-508）----

// upnpSendSOAP 向 controlURL POST 一个 SOAP 信封，返回响应体；
// 空串等价模组的两条 null 路径（HTTP>=400，:498-501）。
func upnpSendSOAP(gw *upnpGateway, action, body string) (string, error) {
	endpoint := gw.baseURL + gw.controlU // :485 纯字符串拼接（controlURL 通常是绝对路径形式 "/...xml"）
	req, err := http.NewRequest(http.MethodPost, endpoint, bytes.NewReader([]byte(body)))
	if err != nil {
		return "", err
	}
	req.Header.Set("Content-Type", "text/xml; charset=utf-8") // :491
	// :492 SOAPAction: "<serviceType#action>"
	req.Header.Set("SOAPAction", "\""+gw.svcType+"#"+action+"\"")
	client := &http.Client{Timeout: upnpHTTPTimeout} // :489-490
	resp, err := client.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 400 { // :498-501 不读错误流直接 null
		return "", nil
	}
	b, err := io.ReadAll(io.LimitReader(resp.Body, upnpReadBodyMax)) // :503-504
	if err != nil {
		return "", err
	}
	return string(b), nil
}

// ---- SOAP 请求体（:510-552）----

// upnpEscapeDesc 描述字段 XML 转义（:511-513 / :528-530）。替换顺序有意义：& 必须最先，
// 否则会把 "&lt" 再包一层。description 为 null（Go 用 nil 指针表达）时回落 "VoxLink"。
func upnpEscapeDesc(description *string) string {
	if description == nil { // :511 description != null ? ... : "VoxLink"
		return "VoxLink"
	}
	s := *description
	s = strings.ReplaceAll(s, "&", "&amp;")
	s = strings.ReplaceAll(s, "<", "&lt;")
	s = strings.ReplaceAll(s, ">", "&gt;")
	s = strings.ReplaceAll(s, "\"", "&quot;")
	s = strings.ReplaceAll(s, "'", "&apos;")
	return s
}

// upnpEnvelopeHead SOAP 信封头（:514 / :531 / :547 三处字面量完全相同，提取为一个常量）。
const upnpEnvelopeHead = "<?xml version=\"1.0\"?>\n" +
	"<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\n" +
	"<s:Body>"

// upnpBuildAddPortMapping 外部=内部、NewLeaseDuration=0（:510-525）。
func upnpBuildAddPortMapping(port int, localIP, description, protocol string) string {
	desc := description
	safeDesc := upnpEscapeDesc(&desc) // openPort 的 description 由调用方给（模组同样非 null）
	return upnpEnvelopeHead +
		"<m:AddPortMapping xmlns:m=\"urn:schemas-upnp-org:service:WANIPConnection:1\"><NewRemoteHost></NewRemoteHost><NewExternalPort>" +
		fmt.Sprint(port) + "</NewExternalPort><NewProtocol>" + protocol + "</NewProtocol><NewInternalPort>" +
		fmt.Sprint(port) + "</NewInternalPort><NewInternalClient>" + localIP +
		"</NewInternalClient><NewEnabled>1</NewEnabled><NewPortMappingDescription>" + safeDesc +
		"</NewPortMappingDescription><NewLeaseDuration>0</NewLeaseDuration></m:AddPortMapping></s:Body></s:Envelope>"
}

// upnpBuildAddPortMappingLease 内外端口可不同、带租约（:527-544）。
func upnpBuildAddPortMappingLease(internalPort, externalPort int, localIP, description, protocol string, leaseDuration int) string {
	desc := description
	safeDesc := upnpEscapeDesc(&desc)
	return upnpEnvelopeHead +
		"<m:AddPortMapping xmlns:m=\"urn:schemas-upnp-org:service:WANIPConnection:1\"><NewRemoteHost></NewRemoteHost><NewExternalPort>" +
		fmt.Sprint(externalPort) + "</NewExternalPort><NewProtocol>" + protocol + "</NewProtocol><NewInternalPort>" +
		fmt.Sprint(internalPort) + "</NewInternalPort><NewInternalClient>" + localIP +
		"</NewInternalClient><NewEnabled>1</NewEnabled><NewPortMappingDescription>" + safeDesc +
		"</NewPortMappingDescription><NewLeaseDuration>" + fmt.Sprint(leaseDuration) +
		"</NewLeaseDuration></m:AddPortMapping></s:Body></s:Envelope>"
}

// upnpBuildDeletePortMapping 删除映射（:546-552）。
func upnpBuildDeletePortMapping(port int, protocol string) string {
	return upnpEnvelopeHead +
		"<m:DeletePortMapping xmlns:m=\"urn:schemas-upnp-org:service:WANIPConnection:1\"><NewRemoteHost></NewRemoteHost><NewExternalPort>" +
		fmt.Sprint(port) + "</NewExternalPort><NewProtocol>" + protocol +
		"</NewProtocol></m:DeletePortMapping></s:Body></s:Envelope>"
}

// ---- SSDP 报文头抽取（:554-577）----

// upnpExtractHeader 大小写不敏感地在"行首"找 `header:`，取值并 trim；"" 等价模组的 null。
// 模组手写扫描（:559-564）要求命中位置是 i==0 或前一个字符是 '\n'，避免误吃报文体里的同名串。
func upnpExtractHeader(response, header string) string {
	searchLower := strings.ToLower(header) + ":"
	lower := strings.ToLower(response)
	idx := -1
	for i := 0; i < len(lower)-len(searchLower); i++ { // :559 边界同样照抄（末尾那点长度不检）
		if strings.HasPrefix(lower[i:], searchLower) && (i == 0 || lower[i-1] == '\n') { // :560
			idx = i
			break
		}
	}
	if idx < 0 {
		return ""
	}
	headerEnd := idx + len(searchLower) // :570
	rest := response[headerEnd:]
	end := strings.Index(rest, "\r\n") // :571
	if end < 0 {
		end = strings.Index(rest, "\n") // :572-574
	}
	if end > 0 { // :576 end > headerEnd 的等价判定（此处 end 是相对 headerEnd 的偏移）
		return strings.TrimSpace(rest[:end])
	}
	return ""
}

// ---- 小工具 ----

// indexOfFrom 等价 Java String.indexOf(str, fromIndex)。
func indexOfFrom(s, sub string, from int) int {
	if from < 0 {
		from = 0
	}
	if from > len(s) {
		return -1
	}
	i := strings.Index(s[from:], sub)
	if i < 0 {
		return -1
	}
	return from + i
}

func firstErr(a, b error) string {
	if a != nil {
		return a.Error()
	}
	if b != nil {
		return b.Error()
	}
	return ""
}

// upnpLogSink 由 App 启动时注入（hunk 见交付报告）；单测里可直接赋值捕获消息。
var upnpLogSink func(level, msg string)

// upnpLog UPnP 日志出口。未绑定 App 时（早期 CLI 调用/单测）走标准库 log，
// 并且同样过消敏函数——本模块的消息本身不含地址，这里是第二道闸。
func upnpLog(level, msg string) {
	if upnpLogSink != nil {
		upnpLogSink(level, msg)
		return
	}
	log.Printf("[%s] %s", level, sanitizeNetLogMessage(msg))
}

// ---- 单线程定时调度器（复刻 Executors.newSingleThreadScheduledExecutor 的关键语义）----
//
// 为什么不用 goroutine + time.AfterFunc 了事：模组的续租跑在**单线程**调度器上，
// 多条映射的续租彼此串行、且 cancel(false) 不打断正在执行的那次、只停止后续排期。
// 这个差别在"路由器 SOAP 接口慢/串行"时是实际行为（并发 AddPortMapping 会被 IGD 拒），
// 所以复刻成一个小的单 goroutine 到期队列，而不是各起各的定时器。

type seqTask struct {
	id        int64
	dueMs     int64
	period    time.Duration // <=0 表示一次性
	fn        func()
	running   bool
	cancelled bool  // cancel(false) 落在"正在执行"的那次上时置位，跑完后不再重排
	sequence  int64 // 同刻到期时按提交顺序稳定（ScheduledThreadPoolExecutor 的队列序）
}

type seqScheduler struct {
	mu      sync.Mutex
	tasks   map[int64]*seqTask
	wake    chan struct{}
	stopped bool
	nextID  int64
}

func newSeqScheduler() *seqScheduler {
	s := &seqScheduler{tasks: map[int64]*seqTask{}, wake: make(chan struct{}, 1)}
	go s.loop()
	return s
}

// scheduleRepeat 延迟 delay 后执行 fn，之后每 period 重复（对应
// scheduleWithFixedDelay(fn, delay, delay)，UPnPManager.java:188-221）；period<=0 为一次性。
func (s *seqScheduler) scheduleRepeat(delay, period time.Duration, fn func()) int64 {
	s.mu.Lock()
	if s.stopped {
		s.mu.Unlock()
		return 0
	}
	s.nextID++
	id := s.nextID
	s.tasks[id] = &seqTask{id: id, dueMs: time.Now().Add(delay).UnixMilli(), period: period, fn: fn, sequence: id}
	s.mu.Unlock()
	s.notify()
	return id
}

// cancel 等价 ScheduledFuture.cancel(false)：不打断在跑的那次，只停止后续排期。
// 正在执行时不能只从 map 里摘掉就算数——loop 跑完还会按 period 把它塞回去，
// 所以要在任务对象上留 cancelled 位（Java 的 FutureTask 状态机就是这个作用）。
func (s *seqScheduler) cancel(id int64) {
	if id == 0 {
		return
	}
	s.mu.Lock()
	if t := s.tasks[id]; t != nil {
		if t.running {
			t.cancelled = true
		} else {
			delete(s.tasks, id)
		}
	}
	s.mu.Unlock()
	s.notify()
}

// shutdownNow 等价 shutdownNow()：停调度、丢弃全部待办（:144）。
func (s *seqScheduler) shutdownNow() {
	s.mu.Lock()
	s.stopped = true
	s.tasks = map[int64]*seqTask{}
	s.mu.Unlock()
	s.notify()
}

func (s *seqScheduler) notify() {
	select {
	case s.wake <- struct{}{}:
	default:
	}
}

func (s *seqScheduler) loop() {
	for {
		s.mu.Lock()
		if s.stopped {
			s.mu.Unlock()
			return
		}
		due := s.pickDueLocked()
		s.mu.Unlock()

		if due == nil {
			select {
			case <-s.wake:
			case <-time.After(time.Second):
			}
			continue
		}

		wait := time.Duration(due.dueMs-time.Now().UnixMilli()) * time.Millisecond
		if wait < 0 {
			wait = 0
		}
		select {
		case <-s.wake:
			continue // 有新任务/取消，重算下一个到期项
		case <-time.After(wait):
		}

		s.mu.Lock()
		cur := s.tasks[due.id]
		if cur == nil || s.stopped { // 等待期间被 cancel 或已停机
			s.mu.Unlock()
			continue
		}
		cur.running = true
		s.mu.Unlock()

		// 单线程串行执行任务体（模组 ScheduledExecutor 的核心语义：任务彼此不并发）
		func() {
			defer func() {
				if r := recover(); r != nil {
					upnpLog("error", fmt.Sprintf("upnp_scheduler_task_panic: %v", r))
				}
			}()
			cur.fn()
		}()

		s.mu.Lock()
		delete(s.tasks, due.id)
		if cur.period > 0 && !s.stopped && !cur.cancelled {
			// FixedDelay：本次结束后再等一个周期（不是固定频率起算）
			s.tasks[due.id] = &seqTask{
				id:       due.id,
				dueMs:    time.Now().Add(cur.period).UnixMilli(),
				period:   cur.period,
				fn:       cur.fn,
				sequence: due.sequence,
			}
		}
		s.mu.Unlock()
	}
}

// pickDueLocked 取最早到期且不在执行中的任务；同刻按提交顺序稳定。
func (s *seqScheduler) pickDueLocked() *seqTask {
	var items []*seqTask
	for _, t := range s.tasks {
		if !t.running {
			items = append(items, t)
		}
	}
	if len(items) == 0 {
		return nil
	}
	sort.Slice(items, func(i, j int) bool {
		if items[i].dueMs != items[j].dueMs {
			return items[i].dueMs < items[j].dueMs
		}
		return items[i].sequence < items[j].sequence
	})
	return items[0]
}
