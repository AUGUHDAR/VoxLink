package main

// conn_wave.go — 房客侧"直连波次"接线层。
//
// 模组每轮连接周期都会和 UDP 打洞**并行**提交一批 TCP 直连尝试（Wave1/Wave2），
// 谁先连上谁胜出；这批尝试在本端此前从未被调用——ipv6_direct.go 的
// tryIpv4Direct/tryIpv6Direct/tryTcpSimultaneousOpen 与 tcp_punch.go 的
// tryTcpHolePunch 都是"函数已移植、调度层缺失"的死代码，表现就是同内网/同 CGNAT
// 的玩家明明能直连却只能空转打洞，最后靠中继或干脆连不上。这里按模组逐语句接上：
//
//	CM:2644-2652  handleHolePunchOffer 存 hostLocalIp
//	CM:2872-2874 / CM:2994-2996 两端映射同一公网 IP → sameCgnat
//	CM:3278-3330  handleHolepunchMapped 的 CGNAT 即时直连（connectPort + MC port 两条）
//	CM:6819-6900  Wave1(step0)：hostLocalIp:hostConnectPort、hostLocalIp:mcPort、IPv6
//	CM:7118-7256  Wave2(step1)：TCP 打洞、tcp_simopen_request、SimOpen、IPv4 直连 ×3、IPv6
//	CM:7073-7099  wave1 全败 → 进 wave2；wave2 全败 → 下一周期
//	CM:10096-10240 connectViaBridge → P2PBridge.connectToHost（:155-265）
//	CM:6136-6170  host 侧 tcp_simopen_request
//	StunDetector.java:128-150 isSameLan（IPv4 前三段相同）
//
// 与模组的一处刻意差异（写在 guestConnectViaBridge 注释里，报告时也如实说明）：
// 模组是"先占 connectionWon 再建桥，建桥失败就退房"；本端改成"拨通才占位"，
// 因为这里占位会同时掐掉仍在跑的 UDP 打洞和自动中继导演，失败退房对玩家的伤害更大。

import (
	"context"
	"fmt"
	"net"
	"strconv"
	"strings"
	"sync"
	"time"
)

// ---- 模组 RoomInfo 两个字段在引擎里的落位辅助 ----

// fbIsSameLan 本机 IPv4 与房主内网 IP 是否同 /24（StunDetector.java:128-150）。
// 模组只比对点分四段的前三段，任何一段不同或不是四段都算 false。
func fbIsSameLan(hostLocalIp string) bool {
	myLocalIp := getLocalIP()
	if myLocalIp == "" || hostLocalIp == "" {
		return false // :130 两侧任一为 null → false
	}
	myParts := strings.Split(myLocalIp, ".")
	hostParts := strings.Split(hostLocalIp, ".")
	if len(myParts) != 4 || len(hostParts) != 4 {
		return false // :134
	}
	same := myParts[0] == hostParts[0] && myParts[1] == hostParts[1] && myParts[2] == hostParts[2]
	return same // 模组在 :137 命中时打一条 LAN check 日志，本端由调用方记录
}

// storeGuestHostLocalIp 存房主内网 IP（CM:2648-2650 / CM:3284-3288）。
// onlyIfEmpty 对应 CM:3284 的"已设过就不再覆盖"：holepunch_mapped 那条支路只在
// 尚未拿到时才写，offer 那条（2650）无条件写。
func (e *connEngine) storeGuestHostLocalIp(ip string, onlyIfEmpty bool, src string) {
	if ip == "" {
		return
	}
	e.mu.Lock()
	if onlyIfEmpty && e.gHostLocalIp != "" {
		e.mu.Unlock()
		return
	}
	e.gHostLocalIp = ip
	e.mu.Unlock()
	e.app.netLog("info", "收到房主内网 IP: "+ip+"（"+src+"）") // CM:2652 Host provided LAN IP
}

// refreshGuestSameCgnat 两端映射公网 IP 相同 → 同一个 CGNAT（CM:2872-2874/2994-2996）。
// 本端映射地址是房客在打洞流程里现采的，所以这个判定的落点比模组晚一拍，
// 结论与写入口径一致：只比较 IP 段，不比端口。
func (e *connEngine) refreshGuestSameCgnat() {
	e.mu.Lock()
	mine := e.gMyMappedIP
	host := ""
	if e.gMapped != nil {
		host = e.gMapped.IP
	}
	changed := false
	if mine != "" && host != "" && mine == host && !e.gSameCgnat {
		e.gSameCgnat = true
		changed = true
	}
	e.mu.Unlock()
	if changed {
		// CM:2872 原文：Same public IP ({}): both behind same CGNAT, P2P direct unlikely
		e.app.netLog("warn", "[HolePunchOffer] Same public IP ("+mine+"): both behind same CGNAT, P2P direct unlikely")
	}
}

// guestDirectTargets 取一轮波次要用的目标地址快照（锁外使用，避免与拨号同锁）。
func (e *connEngine) guestDirectTargets() (hostLocalIp string, sameCgnat bool, hostIp string, hostConnectPort, mcPort int, hostMappedIp string, hostMappedPort int, myMappedIp string, myMappedPort int) {
	e.mu.Lock()
	hostLocalIp = e.gHostLocalIp
	sameCgnat = e.gSameCgnat
	hostIp, hostConnectPort = e.hostIp, e.hostPort
	hostMappedIp, hostMappedPort = "", 0
	if e.gMapped != nil {
		hostMappedIp, hostMappedPort = e.gMapped.IP, e.gMapped.Port
	}
	myMappedIp, myMappedPort = e.gMyMappedIP, e.gMyMappedPort
	e.mu.Unlock()
	// mcPort 取房间记录里的真实游戏端口（CM:6855 getHostPort()），
	// hostConnectPort 取 offer 里的桥/转发端口（CM:2754 setHostConnectPort(offer.hostPort)）。
	// 应用端房主没有独立桥端口，两者通常同值——同值时 MC 端口那条尝试会在波次里被跳过。
	if room := e.app.snapshotRoom(); room != nil {
		if p := roomInt(room, "hostPort"); p > 0 {
			mcPort = p
		}
	}
	if mcPort <= 0 {
		mcPort = hostConnectPort
	}
	return
}

// ---- connectViaBridge：拨通房主 TCP 后架本地桥（CM:10096-10240 + P2PBridge:155-265）----

const (
	// directOuterRetryMax 外层重建次数（connectToHostWithRetry :244 attempt<3）。
	directOuterRetryMax = 3
	// directInnerRetryMax 单次拨号的内部重试（tryConnectWithRetry :659 共 3 次）。
	directInnerRetryMax = 3
	// directSocketTimeout 拨号超时 3000ms（:680 socket.connect(addr, SOCKET_TIMEOUT)）。
	directSocketTimeout = 3 * time.Second
	// directBufSize 发送/接收缓冲 32768（:666-667）。
	directBufSize = 32768
)

// guestConnectViaBridge 直连胜出后的建桥：拨房主 → 本地 127.0.0.1 随机口 → 双向搬运。
// 差异说明见文件头：拨通（含 P2PBridge 的内层 3 次重试）之后才占 connectionWon
// 等价位，占位成功后才掐 UDP 打洞；拨不通就原样交回，打洞与自动中继导演继续跑。
func (e *connEngine) guestConnectViaBridge(res fbConnectResult, cycleLabel string) {
	if res.remoteHost == "" || res.remotePort <= 0 {
		return
	}
	if e.p2pGuestDone() {
		return // CM:10100 之后各支路的 connectionWon 已被别的道占掉
	}

	conn := directDialWithRetry(e.app.netLog, res.remoteHost, res.remotePort)
	if conn == nil {
		// 模组这里会 connectionWon 已置位 → send disconnect → leaveRoomOnFailure（CM:10176/10230）。
		// 本端未占位，故不退房：直连只是并行的一路，UDP 打洞还在跑。
		e.app.netLog("warn", "直连探测成功但建桥拨号失败，继续打洞（"+res.remoteHost+":"+strconv.Itoa(res.remotePort)+"）")
		return
	}

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		_ = conn.Close()
		e.app.netLog("warn", "直连本地桥监听口创建失败: "+err.Error())
		return
	}

	e.mu.Lock()
	// 与既有 TCP 打洞道用同一组字段：gP2PDone 就是模组的 connectionWon。
	if e.gP2PDone || e.sess == nil {
		e.mu.Unlock()
		_ = ln.Close()
		_ = conn.Close()
		return
	}
	e.gP2PDone = true
	e.gTcpLn = ln
	e.gTcpConn = conn
	puncher := e.gPuncher
	e.mu.Unlock()

	// CM:10108-10121 killAllConnectionAttempts + 关闭在跑的 UDP 承载
	if puncher != nil {
		puncher.cancel()
	}

	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return // 监听已随桥拆除而关闭（同 onTcpPunchSocket）
			}
			if tc, ok := c.(*net.TCPConn); ok {
				_ = tc.SetNoDelay(true)
			}
			go e.runTcpBridgePair(ln, conn, c)
		}
	}()

	addr := ln.Addr().String()
	mode := "ipv4_direct"
	if res.mode == fbModeIPv6Direct {
		mode = "ipv6_direct"
	}
	e.app.netLog("info", "直连成功（"+res.label+"→"+res.remoteHost+":"+strconv.Itoa(res.remotePort)+"），本地桥 "+addr)
	e.notifyState(phaseP2P, statusSuccess, addr, mode) // CM:10141-10152 markConnectionEstablished + connectToServer
}

// directDialWithRetry 拨号：内层 3 次 3s 超时（tryConnectWithRetry :654-690），
// 外层最多再来 3 轮、每轮前睡 1s*(attempt+1)（connectToHostWithRetry :239-262）。
func directDialWithRetry(log func(string, string), host string, port int) net.Conn {
	for outer := 0; outer <= directOuterRetryMax; outer++ {
		if outer > 0 {
			time.Sleep(time.Duration(outer) * time.Second) // :244 attempt<3 → 1s/2s/3s
		}
		for attempt := 1; attempt <= directInnerRetryMax; attempt++ {
			addr := net.JoinHostPort(host, strconv.Itoa(port))
			log("info", "Joiner: connecting to "+addr+" (attempt "+strconv.Itoa(attempt)+"/"+strconv.Itoa(directInnerRetryMax)+")") // :663
			d := net.Dialer{Timeout: directSocketTimeout}
			conn, err := d.Dial("tcp", addr)
			if err == nil {
				if tc, ok := conn.(*net.TCPConn); ok {
					_ = tc.SetNoDelay(true) // :665
					// :666-667 setSendBufferSize/setReceiveBufferSize(32768)
					_ = tc.SetWriteBuffer(directBufSize)
					_ = tc.SetReadBuffer(directBufSize)
				}
				return conn
			}
		}
		log("error", "Joiner: failed to connect to host "+net.JoinHostPort(host, strconv.Itoa(port))) // :206
	}
	return nil
}

// ---- Wave1（CM:6819-6900，step0 与 UDP 打洞并行提交）----

// guestWave1Direct Wave1 的三条直连 future + "全败才进 Wave2" 的衔接（CM:7073-7099）。
// 模组在 step0 里先提交直连、同一帧再起打洞，两边并行；本端由 guestPunchFlow 之前
// 以 goroutine 起这条线，同样与打洞并行。
func (e *connEngine) guestWave1Direct(gen int) {
	hostLocalIp, sameCgnat, _, hostConnectPort, mcPort, _, _, _, _ := e.guestDirectTargets()
	sameLan := fbIsSameLan(hostLocalIp) // CM:6823

	var futures []<-chan fbConnectResult
	// CM:6827 hostLocalIp 非空 && (sameLan || sameCgnat)
	if hostLocalIp != "" && (sameLan || sameCgnat) {
		reason := "CGNAT同公网IP"
		if sameLan {
			reason = "LAN"
		}
		e.app.netLog("info", "[Connection] Wave 1: Detected "+reason+" (localIp="+hostLocalIp+"), try direct connect") // CM:6831
		lanFb := fbNew()
		futures = append(futures, e.waveResultChan(gen, lanFb.tryIpv4Direct(hostLocalIp, hostConnectPort), func(r fbConnectResult) {
			if !r.success && e.genValid(gen) {
				// CM:6845-6847 只有这条记黑名单
				e.recordDirectFailureAddr(hostLocalIp, hostConnectPort)
			}
		}, "Wave 1: "+reason+" 直连胜出"))

		if sameCgnat && !sameLan && mcPort > 0 && mcPort != hostConnectPort {
			// CM:6853-6870 CGNAT（非同网段）额外补一次 MC 原生端口尝试。
			// 应用端 hostConnectPort 与 mcPort 同值时不重复拨同一个口。
			e.app.netLog("info", "[Connection] Wave 1: CGNAT also try localIp MC port "+hostLocalIp+":"+strconv.Itoa(mcPort))
			mcFb := fbNew()
			futures = append(futures, e.waveResultChan(gen, mcFb.tryIpv4Direct(hostLocalIp, mcPort), nil, "Wave 1: CGNAT localIp MC port won"))
		}
	}
	if ipv6 := e.guestHostIpv6(); ipv6 != "" && fbVerifyIPv6Connectivity() {
		// CM:6881-6900
		v6Fb := fbNew()
		futures = append(futures, e.waveResultChan(gen, v6Fb.tryIpv6Direct(ipv6, hostConnectPort), nil, "Wave 1: IPv6 direct won"))
	}

	if len(futures) == 0 {
		// CM:7091-7099 wave1 空集：5s 后进 Wave2
		time.Sleep(5 * time.Second)
		e.guestWave2Direct(gen)
		return
	}
	settleWaveFutures(gen, futures, wave1SettleWait)
	if e.p2pGuestDone() {
		return
	}
	e.guestWave2Direct(gen) // CM:7081
}

// wave1SettleWait 只是本端给 wave1 收口设的上限，防止 allOf 语义下某一拨号 goroutine
// 长期不返回把 Wave2 永远挡住（模组的 CompletableFuture.allOf 无超时，靠 85s 全局超时兜底，
// 而本端的全局超时还没接线，这里先用逐波上限）。
const wave1SettleWait = 40 * time.Second

// ---- Wave2（CM:7118-7256）----

func (e *connEngine) guestWave2Direct(gen int) {
	hostLocalIp, sameCgnat, hostIp, hostConnectPort, mcPort, hostMappedIp, hostMappedPort, myMappedIp, myMappedPort := e.guestDirectTargets()

	// CM:7122 TCP 双向打洞（模组门控 cycle>=1，首轮只发邀约不参与；这里传当前轮次）
	e.mu.Lock()
	round := e.retryRound
	localNat := e.gLocalNat
	e.mu.Unlock()
	e.tryTcpHolePunch(round, localNat, myMappedIp)

	var futures []<-chan fbConnectResult
	if hostMappedIp != "" && hostMappedPort > 0 {
		// CM:7126-7154 先发 tcp_simopen_request 让房主也朝本端映射口发起 SimOpen
		if myMappedIp != "" && myMappedPort > 0 {
			_ = e.sendSignal("tcp_simopen_request", map[string]any{
				"joinerMappedIp":   myMappedIp,
				"joinerMappedPort": myMappedPort,
			}, "host")
			e.app.netLog("info", fmt.Sprintf("[Connection] Wave 2: Send tcp_simopen_request to host (%s:%d)", myMappedIp, myMappedPort)) // CM:7144
		}
		simLocalPort := e.guestSimLocalPort()
		simFb := fbNew()
		tcpTargetPort := hostConnectPort
		if tcpTargetPort <= 0 {
			tcpTargetPort = hostMappedPort
		}
		futures = append(futures, e.waveResultChan(gen, simFb.tryTcpSimultaneousOpen(hostMappedIp, tcpTargetPort, simLocalPort), nil, "Wave 2: SimOpen won"))

		// CM:7158-7166 直接 TCP 连房主映射地址
		tcpMappedFb := fbNew()
		tcpDirectPort := hostConnectPort
		if tcpDirectPort <= 0 {
			tcpDirectPort = hostMappedPort
		}
		futures = append(futures, e.waveResultChan(gen, tcpMappedFb.tryIpv4Direct(hostMappedIp, tcpDirectPort), nil, "Wave 2: mapped IPv4 direct won"))
	}
	if hostIp != "" {
		// CM:7170-7186 公网 IP + 房间端口（失败记黑名单，CM:7182）
		ipv4Fb := fbNew()
		futures = append(futures, e.waveResultChan(gen, ipv4Fb.tryIpv4Direct(hostIp, hostConnectPort), func(r fbConnectResult) {
			if !r.success && e.genValid(gen) {
				e.recordDirectFailureAddr(hostIp, hostConnectPort)
			}
		}, "Wave 2: IPv4 direct won"))
	}
	if sameCgnat && hostLocalIp != "" {
		// CM:7192-7212 CGNAT 场景再来一遍内网 IP（桥端口 + MC 端口）
		e.app.netLog("info", "[Connection] Wave 2: CGNAT scenario try hostLocalIp "+hostLocalIp+":"+strconv.Itoa(hostConnectPort))
		localFb := fbNew()
		futures = append(futures, e.waveResultChan(gen, localFb.tryIpv4Direct(hostLocalIp, hostConnectPort), nil, "Wave 2: CGNAT localIp won"))
		if mcPort > 0 && mcPort != hostConnectPort {
			e.app.netLog("info", "[Connection] Wave 2: CGNAT scenario try localIp MC port "+hostLocalIp+":"+strconv.Itoa(mcPort))
			mcLocalFb := fbNew()
			futures = append(futures, e.waveResultChan(gen, mcLocalFb.tryIpv4Direct(hostLocalIp, mcPort), nil, "Wave 2: CGNAT MC port won"))
		}
	}
	if ipv6 := e.guestHostIpv6(); ipv6 != "" && fbVerifyIPv6Connectivity() {
		// CM:7218-7226
		v6Fb := fbNew()
		futures = append(futures, e.waveResultChan(gen, v6Fb.tryIpv6Direct(ipv6, hostConnectPort), nil, "Wave 2: IPv6 won"))
	}

	if len(futures) == 0 {
		e.app.netLog("info", "[Connection] Wave 2: No TCP fallback available, enter next cycle") // CM:7230
		return
	}
	settleWaveFutures(gen, futures, wave2SettleWait)
	// CM:7260-7268 allOf 后未赢 → advanceToNextCycle。本端"下一周期"由 guestPunchFlow
	// 的轮次循环承担（持续重试），波次每轮重挂见 guestWaveRound。
}

const wave2SettleWait = 40 * time.Second

// waveResultChan 把一路 future 的落位接成"胜出即建桥"（CM:6837-6843 / 7246-7250），
// onFail 承接模组的 recordDirectFailure，winLog 承接模组的胜负日志。
func (e *connEngine) waveResultChan(gen int, resCh <-chan fbConnectResult, onFail func(fbConnectResult), winLog string) <-chan fbConnectResult {
	out := make(chan fbConnectResult, 1)
	go func() {
		res, ok := <-resCh
		if !ok {
			return
		}
		if res.success {
			if !e.genValid(gen) || e.p2pGuestDone() {
				out <- res
				return
			}
			e.app.netLog("info", "[Connection] "+winLog)
			e.guestConnectViaBridge(res, winLog)
		} else if onFail != nil {
			onFail(res)
		}
		out <- res
		close(out)
	}()
	return out
}

// settleWaveFutures 等价 CompletableFuture.allOf：等这一波全部落位。
func settleWaveFutures(gen int, futures []<-chan fbConnectResult, limit time.Duration) {
	var wg sync.WaitGroup
	ctx, cancel := context.WithTimeout(context.Background(), limit)
	defer cancel()
	for _, f := range futures {
		wg.Add(1)
		go func(ch <-chan fbConnectResult) {
			defer wg.Done()
			select {
			case _, ok := <-ch:
				_ = ok
			case <-ctx.Done():
			}
		}(f)
	}
	wg.Wait()
}

// ---- handleHolepunchMapped 的 CGNAT 即时直连（CM:3292-3330）----

// guestCgnatDirectOnMapped 打洞进行中收到新 mapped 且带内网 IP 时的即时补连：
// 门禁三连 connectionCycleActive && isSameCgnat && !connectionWon（CM:3292），
// 两条尝试分别打 hostConnectPort 与 MC 端口。
func (e *connEngine) guestCgnatDirectOnMapped() {
	e.mu.Lock()
	cycleActive := e.gActive
	sameCgnat := e.gSameCgnat
	done := e.gP2PDone
	gen := e.gCycle
	ip := e.gHostLocalIp
	e.mu.Unlock()
	if !cycleActive || !sameCgnat || done || ip == "" {
		return
	}
	_, _, _, hostConnectPort, mcPort, _, _, _, _ := e.guestDirectTargets()
	e.app.netLog("info", "[handleHolepunchMapped] CGNAT: also try hostLocalIp "+ip+":"+strconv.Itoa(hostConnectPort)) // CM:3298
	fb1 := fbNew()
	e.waveResultChan(gen, fb1.tryIpv4Direct(ip, hostConnectPort), nil, "handleHolepunchMapped CGNAT hostLocalIp direct connect won")
	if mcPort > 0 && mcPort != hostConnectPort {
		fb2 := fbNew()
		e.waveResultChan(gen, fb2.tryIpv4Direct(ip, mcPort), nil, "handleHolepunchMapped CGNAT hostLocalIp MC port won")
	}
}

// ---- 小工具 ----

// guestHostIpv6 offer 里的房主 IPv6（模组 hostIpv6 参数）。应用端目前只在
// offer 带该字段时才有值；没有就走 IPv4 那条线。
func (e *connEngine) guestHostIpv6() string {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.gOffer == nil {
		return ""
	}
	return sigStr(e.gOffer, "hostIpv6")
}

// guestSimLocalPort SimOpen 想占的本地口（CM:7128 P2PBridge.getHostPort()>0 ? 它 : hostPort）。
// 应用端房客没有常驻 TCP 监听口，返回 0 交给系统选：拿 MC 端口去 bind 只会
// 恒定 EADDRINUSE，把这条支路变成永远失败。模组在随机口模式下（:333）本来就是这么退让的。
func (e *connEngine) guestSimLocalPort() int {
	return 0
}

// recordDirectFailureAddr 把 TCP 直连失败落到观察式黑名单（CM:6847/7182）。
// 模组的键是 InetSocketAddress(host,port)，本端黑名单键是 UDPAddr，取 host+port 同键。
func (e *connEngine) recordDirectFailureAddr(host string, port int) {
	ip := net.ParseIP(strings.Trim(strings.TrimPrefix(host, "["), "]"))
	if ip == nil {
		return
	}
	b := addressBlacklistGet()
	b.recordDirectFailure(&net.UDPAddr{IP: ip, Port: port})
}

// ---- host 侧 tcp_simopen_request（CM:6136-6170）----

func (e *connEngine) hostOnTcpSimopenRequest(from string, data map[string]any) {
	joinerIp := sigStr(data, "joinerMappedIp")
	joinerPort := sigInt(data, "joinerMappedPort")
	if joinerIp == "" || joinerPort == 0 {
		return
	}
	e.mu.Lock()
	peer := e.hPeers[from]
	// 模组的门是全局 connectionWon（CM:6145）；房主侧每个房客一条链路，
	// 等价判据是这个房客已经有在跑的 rudp 承载。
	connected := peer != nil && peer.rudp != nil
	e.mu.Unlock()
	if connected {
		e.app.netLog("info", "[TcpSimOpen] already connected, ignoring tcp_simopen_request") // CM:6146
		return
	}
	hostPort := e.hostSimOpenPort()
	e.app.netLog("info", "[TcpSimOpen] Host received joiner "+from+" request, try TCP connect "+joinerIp+":"+strconv.Itoa(joinerPort)) // CM:6154
	fb := fbNew()
	resCh := fb.tryTcpSimultaneousOpen(joinerIp, joinerPort, hostPort)
	go func() {
		res, ok := <-resCh
		if !ok {
			return
		}
		if res.success {
			// CM:6159-6165 房主侧胜出不建本地桥：房主的游戏服务就在本机 MC 端口，
			// 房客那条 TCP 会自己连进来，这里只记一条日志（模组同分支走 connectViaBridge，
			// 它在房主侧建的 joiner 桥对本端 MC 没有任何作用，故不落）。
			e.app.netLog("info", "[TcpSimOpen] Host connected to joiner via TCP SimOpen!")
			return
		}
		e.app.netLog("info", "[TcpSimOpen] Host TCP SimOpen failed: "+res.failureReason) // CM:6168
	}()
}

// hostSimOpenPort 房主发起 SimOpen 时想占的本地口：房间端口（CM:6148 P2PBridge.getHostPort()>0 ? 它 : 25565）。
// 应用端房主侧没有常驻 TCP 监听口（MC 自己占着 25565），返回 0 交给系统选口，
// 否则 bind 会直接撞 MC 的监听口并把这条支路变成恒定失败。
func (e *connEngine) hostSimOpenPort() int {
	return 0
}
