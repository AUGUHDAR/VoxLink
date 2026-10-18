package main

// punch.go — UDP 打洞引擎（对齐模组 UdpHolePuncher.java 全量语义）：
//
// 控制包：56 4C <type> <nonce_hi> <nonce_lo>（type: 1=PUNCH, 2=PUNCH_ACK）；
// punchAuthV1 启用时追加 4 字节截断 HMAC-SHA256（共 9 字节）。
// 判赢单向：收到对端 PUNCH 或 ACK 任一即成功（NIO 路径语义），赢 socket 移交 rudp。
// 来源判定：同 IP 任意端口；非认证模式下 IPv4 /16 一致视作 CGNAT 多 IP 也接受。
// 发送模式：classic（每 socket ×3 发目标端口）/ prediction（progressive range 随机扫窗）/
// multiPort（固定端口表逐个发）/ easySymBomb（窗口内全量广播）/ hardSymSpray（全端口洗牌喷洒 + PpsLimiter）。
// 防火墙检测：组模式 sent >= firewallDetectCycles 且零回包 → 提前判 firewall。
// Windows：向未映射端口发 UDP 触发 WSAECONNRESET，写错误必须忽略并重试（sendPkt 同款）。

import (
	"math/rand"
	"net"
	"sync"
	"sync/atomic"
	"time"
)

const (
	punchTypePunch    byte = 1
	punchTypePunchAck byte = 2

	punchMagic0 byte = 0x56
	punchMagic1 byte = 0x4C

	punchControlPlainLen = 5
	punchControlAuthLen  = 9
)

// udpSendTo 尽力发送：Windows UDP socket 的 ICMP 不可达锁存（WSAECONNRESET）
// 失败立即重试最多 3 次，错误全部吞掉（UdpHolePuncher.sendPkt 同款）。
func udpSendTo(conn *net.UDPConn, b []byte, addr *net.UDPAddr) {
	if conn == nil || addr == nil {
		return
	}
	for attempt := 0; attempt < 3; attempt++ {
		if _, err := conn.WriteToUDP(b, addr); err == nil {
			return
		}
	}
}

// ppsLimiter 每秒发包上限（UdpHolePuncher.PpsLimiter 同款令牌算法）。
type ppsLimiter struct {
	maxPps    int64
	startNano time.Time
	sent      int64
}

func newPpsLimiter(maxPps int) *ppsLimiter {
	if maxPps < 1 {
		maxPps = 1
	}
	return &ppsLimiter{maxPps: int64(maxPps), startNano: time.Now()}
}

func (l *ppsLimiter) beforeSend() {
	l.sent++
	elapsed := time.Since(l.startNano)
	allowed := elapsed.Nanoseconds() * l.maxPps / 1_000_000_000
	if l.sent > allowed {
		sleepNs := (l.sent - allowed) * 1_000_000_000 / l.maxPps
		time.Sleep(time.Duration(sleepNs))
	}
}

// puncher 一次打洞尝试（单 socket 或 socket 组的主控）。
type puncher struct {
	conn                  *net.UDPConn // 主 socket
	nonce                 uint16
	profile               *punchProfile
	params                *punchParams
	authKey               []byte // nil = 明文
	skipFirewallDetection bool
	logf                  func(level, msg string)

	mu     sync.Mutex
	remote *net.UDPAddr // 期望对端地址（CGNAT/漂移可纠偏）
	onPeer func(*net.UDPAddr)

	punching   atomic.Bool
	holeOpen   atomic.Bool
	completed  atomic.Bool
	remoteRecv atomic.Bool
	localConf  atomic.Bool
	recvPunch  atomic.Int32
	recvAck    atomic.Int32

	stopCh       chan struct{}
	stopOnce     sync.Once
	recvWg       sync.WaitGroup // 接收 goroutine 集合（socket 移交 rudp 前须退出）
	resultCh     chan *punchResult
	peerNotified atomic.Bool

	// 组模式：全部 socket（含主 socket）；cancel 时统一关闭（赢的除外）
	group      []*net.UDPConn
	winConn    *net.UDPConn
	actualAddr *net.UDPAddr

	// hardSym 喷洒洗牌表（跨轮共享）
	sprayTable []int
	sprayCur   int
}

func newPuncher(conn *net.UDPConn, profile *punchProfile, params *punchParams, authKey []byte) *puncher {
	if profile == nil {
		profile = punchProfileNow()
	}
	if params == nil {
		params = punchParamsFromProfile(profile)
	}
	return &puncher{
		conn:     conn,
		nonce:    punchRandomNonce(),
		profile:  profile,
		params:   params,
		authKey:  authKey,
		stopCh:   make(chan struct{}),
		resultCh: make(chan *punchResult, 1),
	}
}

func (p *puncher) setLogf(fn func(level, msg string)) { p.logf = fn }

func (p *puncher) log(level, msg string) {
	if p.logf != nil {
		p.logf(level, msg)
	}
}

// setOnPeer 注册"收到对端有效包"回调（只触发一次）。
func (p *puncher) setOnPeer(fn func(*net.UDPAddr)) {
	p.mu.Lock()
	p.onPeer = fn
	p.mu.Unlock()
}

// setTarget 设置/更新打洞目标（peer_port 信令漂移纠偏）。
func (p *puncher) setTarget(addr *net.UDPAddr) {
	if addr == nil {
		return
	}
	p.mu.Lock()
	p.remote = addr
	p.mu.Unlock()
}

func (p *puncher) target() *net.UDPAddr {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.remote
}

// ---- 有效参数（UdpHolePuncher.effectiveXxx 同款）----

func (p *puncher) effectiveTimeoutMs() int {
	if p.params.timeoutMs > 0 {
		return p.params.timeoutMs
	}
	return p.profile.punchTimeoutMs
}

func (p *puncher) effectivePortRange() int {
	if p.params.portRange > 0 {
		return p.params.portRange
	}
	return p.profile.portPredictionMaxRange
}

func (p *puncher) effectiveSendInterval() int {
	if p.params.sendInterval > 0 {
		return p.params.sendInterval
	}
	return p.profile.send.intervalMs
}

func (p *puncher) effectiveMinRounds() int {
	if p.params.sendMinRounds > 0 {
		return p.params.sendMinRounds
	}
	return p.profile.send.minRounds
}

func (p *puncher) effectiveMinPass() int {
	if p.params.sendMinPass > 0 {
		return p.params.sendMinPass
	}
	return p.profile.send.minPass
}

func (p *puncher) effectiveSkipDirectPunch() bool { return p.params.skipDirectPunch }

// ---- 控制包 ----

// buildPunchControl 构造打洞控制包：明文 5 字节；authKey 非 nil 时附 4 字节截断 MAC（9 字节）。
func buildPunchControl(typ byte, nonce uint16, authKey []byte) []byte {
	base := []byte{punchMagic0, punchMagic1, typ, byte(nonce >> 8), byte(nonce)}
	if authKey != nil {
		return punchAuthAppendTrailer4(authKey, base)
	}
	return base
}

// parsePunchControl 校验魔数与类型（允许可选认证尾），返回 type。
func parsePunchControl(b []byte) (byte, bool) {
	if len(b) < punchControlPlainLen || b[0] != punchMagic0 || b[1] != punchMagic1 {
		return 0, false
	}
	if b[2] != punchTypePunch && b[2] != punchTypePunchAck {
		return 0, false
	}
	if len(b) > punchControlPlainLen && len(b) != punchControlAuthLen {
		return 0, false
	}
	return b[2], true
}

// buildControl 认证模式追加 4 字节截断 MAC（覆盖 type||nonce）。
func (p *puncher) buildControl(typ byte) []byte {
	return buildPunchControl(typ, p.nonce, p.authKey)
}

// accept 来源判定：同 IP 恒收；非认证模式 IPv4 /16 一致（CGNAT 多 IP）接受并纠偏。
func (p *puncher) accept(from *net.UDPAddr, typ byte, authenticated bool) bool {
	remote := p.target()
	if remote == nil || from == nil || from.IP == nil {
		return false
	}
	if remote.IP.Equal(from.IP) {
		return true
	}
	if !authenticated && (typ == punchTypePunch || typ == punchTypePunchAck) && p.punching.Load() {
		e4, f4 := remote.IP.To4(), from.IP.To4()
		if e4 != nil && f4 != nil && e4[0] == f4[0] && e4[1] == f4[1] {
			p.mu.Lock()
			cp := *remote
			cp.IP = from.IP
			p.remote = &cp
			p.mu.Unlock()
			return true
		}
	}
	return false
}

// ---- 接收/判赢 ----

// startRecv 为一个 socket 起接收循环（组模式每 socket 一个）。
func (p *puncher) startRecv(c *net.UDPConn) {
	p.recvWg.Add(1)
	go func() {
		defer p.recvWg.Done()
		buf := make([]byte, 128)
		for p.punching.Load() && !p.holeOpen.Load() {
			_ = c.SetReadDeadline(time.Now().Add(time.Duration(p.profile.send.socketTimeoutMs) * time.Millisecond))
			n, from, err := c.ReadFromUDP(buf)
			if err != nil {
				select {
				case <-p.stopCh:
					return
				default:
					continue // 超时/WSAECONNRESET 等一律继续
				}
			}
			if n < 3 || buf[0] != punchMagic0 || buf[1] != punchMagic1 {
				continue
			}
			typ := buf[2]
			if typ != punchTypePunch && typ != punchTypePunchAck {
				continue
			}
			authenticated := false
			if p.authKey != nil {
				if n < punchControlAuthLen || !punchAuthVerifyTrailer4(p.authKey, buf[:n]) {
					continue
				}
				authenticated = true
			}
			if !p.accept(from, typ, authenticated) {
				continue
			}
			// 收到 PUNCH 或 ACK 都回 ACK（本端 nonce）
			udpSendTo(c, p.buildControl(punchTypePunchAck), from)

			if typ == punchTypePunch {
				p.recvPunch.Add(1)
				p.remoteRecv.Store(true)
			} else {
				p.recvAck.Add(1)
				p.localConf.Store(true)
			}

			// 首个对端有效包回调（peer_port 信令）
			if !p.peerNotified.Swap(true) {
				p.mu.Lock()
				fn := p.onPeer
				p.mu.Unlock()
				if fn != nil {
					go fn(from)
				}
			}

			// 判赢单向：收到 PUNCH 或 ACK 任一即成功（NIO 路径）
			if !p.completed.Swap(true) {
				p.holeOpen.Store(true)
				p.punching.Store(false)
				p.mu.Lock()
				p.winConn = c
				p.actualAddr = from
				p.mu.Unlock()
				p.notifyResult(&punchResult{
					success:      true,
					winConn:      c,
					socketsTried: 1,
					recvPunch:    int(p.recvPunch.Load()),
					recvAck:      int(p.recvAck.Load()),
				})
			}
		}
	}()
}

func (p *puncher) notifyResult(r *punchResult) {
	select {
	case p.resultCh <- r:
	default:
	}
}

// begin 复位状态并进入 punching。
func (p *puncher) begin() {
	p.punching.Store(true)
	p.holeOpen.Store(false)
	p.completed.Store(false)
	p.remoteRecv.Store(false)
	p.localConf.Store(false)
	p.recvPunch.Store(0)
	p.recvAck.Store(0)
	p.peerNotified.Store(false)
}

// waitResult 阻塞等待结果（成功/失败/取消）；返回前确保接收 goroutine 已退出
// （socket 移交 rudp 前避免双读）。
func (p *puncher) waitResult() *punchResult {
	r := <-p.resultCh
	p.stop()
	p.waitRecvStopped(2 * time.Second)
	return r
}

// waitResultTimeout 带外部超时的等待。
func (p *puncher) waitResultTimeout(d time.Duration) *punchResult {
	select {
	case r := <-p.resultCh:
		p.stop()
		p.waitRecvStopped(2 * time.Second)
		return r
	case <-time.After(d):
		p.stop()
		p.waitRecvStopped(2 * time.Second)
		return nil
	}
}

// waitRecvStopped 等接收 goroutine 退出（上限 timeout）。
func (p *puncher) waitRecvStopped(timeout time.Duration) {
	done := make(chan struct{})
	go func() {
		p.recvWg.Wait()
		close(done)
	}()
	t := time.NewTimer(timeout)
	defer t.Stop()
	select {
	case <-done:
	case <-t.C:
	}
}

// stop 停止收发循环（不关 socket）。幂等。
func (p *puncher) stop() {
	p.stopOnce.Do(func() {
		p.punching.Store(false)
		close(p.stopCh)
		if p.conn != nil {
			_ = p.conn.SetReadDeadline(time.Now())
		}
	})
}

// cancel 停止并关闭本组附加 socket（共享主 socket 与赢 socket 由调用方处置）。幂等。
func (p *puncher) cancel() {
	p.stop()
	p.mu.Lock()
	win := p.winConn
	group := p.group
	p.group = nil
	p.mu.Unlock()
	for _, c := range group {
		if c != nil && c != win && c != p.conn {
			_ = c.Close()
		}
	}
}

// win 返回赢 socket 与对端实际地址（成功后有效）。
func (p *puncher) win() (*net.UDPConn, *net.UDPAddr) {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.winConn, p.actualAddr
}

// ---- 发送模式 ----

// punchPrediction 单 socket 端口预测模式（punchWithPortPrediction 同款：
// progressive range 随机扫窗 / 固定范围；portRange<=0 时直打 remotePort）。
func (p *puncher) punchPrediction(basePort, portRange int, fixedRange bool) *punchResult {
	if t := p.target(); t == nil {
		return &punchResult{socketsTried: 1}
	} else {
		cp := *t
		cp.Port = basePort
		p.setTarget(&cp)
	}
	p.begin()
	go p.startRecv(p.conn)
	go p.sendLoopPrediction(portRange, fixedRange)
	return p.waitResultTimeout(time.Duration(p.effectiveTimeoutMs()+p.profile.send.extraWaitMs) * time.Millisecond)
}

// punchPorts 固定端口表模式（punchMultiPort 同款：每周期逐端口 1 发）。
func (p *puncher) punchPorts(ports []int) *punchResult {
	if len(ports) == 0 {
		return &punchResult{socketsTried: len(ports)}
	}
	p.begin()
	go p.startRecv(p.conn)
	go p.sendLoopPorts(ports)
	return p.waitResultTimeout(time.Duration(p.effectiveTimeoutMs()+p.profile.send.extraWaitLongMs) * time.Millisecond)
}

// punchGroup 多 socket 组模式（punchMultiSocket 同款：spray/bomb/classic + 防火墙检测）。
// conns[0] 必须等于 p.conn；sweepSpread>0 时组内 socket 分布到目标端口 ±spread。
func (p *puncher) punchGroup(conns []*net.UDPConn, sweepSpread int) *punchResult {
	if len(conns) == 0 {
		return &punchResult{}
	}
	p.group = conns
	p.begin()
	for _, c := range conns {
		p.startRecv(c)
	}
	go p.sendLoopGroup(conns, sweepSpread)
	return p.waitResultTimeout(time.Duration(p.effectiveTimeoutMs()+p.profile.send.extraWaitMs) * time.Millisecond)
}

// punchEasySymDual 双易对称对轰（punchEasySymDual 同款：
// easySymDualSocketCount 个 socket 全量广播 base±bombWindow）。
func (p *puncher) punchEasySymDual(target *net.UDPAddr) *punchResult {
	count := p.profile.easySymDualSocketCount
	conns := make([]*net.UDPConn, 0, count)
	if p.conn != nil {
		conns = append(conns, p.conn)
	}
	for len(conns) < count {
		c, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4zero, Port: 0})
		if err != nil {
			break
		}
		conns = append(conns, c)
	}
	if len(conns) == 0 {
		return &punchResult{}
	}
	if p.conn == nil {
		p.conn = conns[0]
	}
	p.group = conns // 使 cancel() 能回收本轮新建的对轰 socket
	p.setTarget(target)
	// 对轰配方参数（punchEasySymDual: bomb 模式取 RECIPE 窗口）
	bomb := clonePunchParams(p.params)
	bomb.easySymBomb = true
	bomb.bombWindow = p.profile.sym.easySymBombWindow
	bomb.bombRoundIntervalMs = p.profile.sym.easySymRoundIntervalMs
	bomb.bombDurationMs = p.profile.sym.easySymBombDurationMs
	p.params = bomb
	p.begin()
	for _, c := range conns {
		p.startRecv(c)
	}
	go p.sendLoopGroup(conns, 0)
	return p.waitResultTimeout(time.Duration(p.effectiveTimeoutMs()+p.profile.send.extraWaitMs) * time.Millisecond)
}

func (p *puncher) interrupted() bool {
	select {
	case <-p.stopCh:
		return true
	default:
		return false
	}
}

func (p *puncher) sleepInterruptible(d time.Duration) bool {
	if d <= 0 {
		return !p.interrupted()
	}
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-p.stopCh:
		return false
	case <-t.C:
		return true
	}
}

// sendLoopPrediction progressive range 扫窗发送（sendControlMultiPort 同款）。
func (p *puncher) sendLoopPrediction(portRange int, fixedRange bool) {
	maxTotalCycles := p.effectiveTimeoutMs() / maxInt(1, p.effectiveSendInterval())
	cycles := 0
	sendStart := time.Now()
	for p.punching.Load() && !p.holeOpen.Load() && cycles < maxTotalCycles && !p.interrupted() {
		if p.effectiveSkipDirectPunch() {
			// 防火墙判定的降级：只听不发
			if !p.sleepInterruptible(time.Duration(p.effectiveSendInterval()) * time.Millisecond) {
				return
			}
			cycles++
			continue
		}
		if portRange > 0 {
			var currentRange int
			if fixedRange {
				currentRange = portRange
			} else {
				// UHP:1253-1258：渐进窗要受**信令里对端上报的窗口**封顶，
				// 本端档位窗只作兜底。旧写法只用本端 effectivePortRange()，
				// 对端报了更窄的窗口时本端会过扫（多打无用包，还可能触发限速）。
				currentRange = progressiveSweepRange(p.profile, p.params, cycles, portRange)
			}
			p.sendControlMultiPort(currentRange, cycles)
		} else {
			p.sendControlSingle()
		}
		cycles++
		if !p.sleepInterruptible(time.Duration(p.effectiveSendInterval()) * time.Millisecond) {
			return
		}
	}
	p.failIfRunning(time.Since(sendStart).Milliseconds(), false, portRange > 0)
}

// sendLoopPorts 固定端口表发送（punchMultiPort send 线程同款）。
func (p *puncher) sendLoopPorts(ports []int) {
	maxTotalCycles := p.effectiveTimeoutMs() / maxInt(1, p.effectiveSendInterval())
	cycles := 0
	sendStart := time.Now()
	for p.punching.Load() && !p.holeOpen.Load() && cycles < maxTotalCycles && !p.interrupted() {
		if p.effectiveSkipDirectPunch() {
			if !p.sleepInterruptible(time.Duration(p.effectiveSendInterval()) * time.Millisecond) {
				return
			}
			cycles++
			continue
		}
		for _, port := range ports {
			p.sendTo(port)
		}
		cycles++
		if !p.sleepInterruptible(time.Duration(p.effectiveSendInterval()) * time.Millisecond) {
			return
		}
	}
	p.failIfRunning(time.Since(sendStart).Milliseconds(), false, false)
}

// sendLoopGroup 组发送（classic/spray/bomb + 防火墙检测）。
func (p *puncher) sendLoopGroup(conns []*net.UDPConn, sweepSpread int) {
	sprayMode := p.params.hardSymSpray
	bombMode := p.params.easySymBomb
	limiter := newPpsLimiter(p.profile.sym.maxPps)
	roundInterval := p.effectiveSendInterval()
	if bombMode {
		roundInterval = minInt(p.effectiveSendInterval(), maxInt(1, p.params.bombRoundIntervalMs))
	}
	maxTotalCycles := p.effectiveTimeoutMs() / maxInt(1, roundInterval)
	skipFirewallCheck := len(conns) <= 1 || p.skipFirewallDetection
	cycles := 0
	sendStart := time.Now()

	for p.punching.Load() && !p.holeOpen.Load() && cycles < maxTotalCycles && !p.interrupted() {
		if !skipFirewallCheck && cycles >= p.profile.firewallDetectCycles && !p.remoteRecv.Load() {
			elapsed := time.Since(sendStart).Milliseconds()
			p.log("warn", "多socket防火墙检测：发包无回包，UDP疑似被拦截，提前终止")
			p.completeFailure(&punchResult{
				socketsTried:     len(conns),
				recvPunch:        int(p.recvPunch.Load()),
				recvAck:          int(p.recvAck.Load()),
				elapsedMs:        elapsed,
				firewallDetected: true,
			})
			return
		}
		if p.effectiveSkipDirectPunch() {
			if !p.sleepInterruptible(time.Duration(roundInterval) * time.Millisecond) {
				return
			}
			cycles++
			continue
		}

		if sprayMode {
			cycles++
			if !p.sprayRound(conns, cycles, limiter) {
				return
			}
			if !p.sleepInterruptible(time.Duration(roundInterval) * time.Millisecond) {
				return
			}
			continue
		}

		if bombMode {
			// 每轮全量广播：每 socket 向对端 base±窗口各发 1 包
			for _, c := range conns {
				for off := -p.params.bombWindow; off <= p.params.bombWindow; off++ {
					dp := p.clampedRemotePort(off)
					p.sendConnTo(c, dp)
					limiter.beforeSend()
				}
			}
		} else {
			sweepSpan := 0
			if sweepSpread > 0 {
				sweepSpan = sweepSpread*2 + 1
			}
			sweepIdx := 0
			for _, c := range conns {
				destPort := p.remotePort()
				if sweepSpread > 0 {
					destPort = p.clampedRemotePort(sweepIdx%sweepSpan - sweepSpread)
					sweepIdx++
				}
				for r := 0; r < 3; r++ {
					p.sendConnTo(c, destPort)
				}
			}
		}

		cycles++
		sleepMs := p.effectiveSendInterval()
		if bombMode && time.Since(sendStart).Milliseconds() < int64(p.params.bombDurationMs) {
			sleepMs = p.params.bombRoundIntervalMs
		}
		if !p.sleepInterruptible(time.Duration(sleepMs) * time.Millisecond) {
			return
		}
	}
	p.failIfRunningN(len(conns), time.Since(sendStart).Milliseconds(), false, false)
}
func (p *puncher) sprayRound(conns []*net.UDPConn, round int, limiter *ppsLimiter) bool {
	if p.sprayTable == nil {
		table := make([]int, 65535)
		for i := range table {
			table[i] = i + 1
		}
		rand.Shuffle(len(table), func(i, j int) { table[i], table[j] = table[j], table[i] })
		p.sprayTable = table
		p.sprayCur = 0
	}
	for r := 0; r < p.params.sprayPacketsPerPort; r++ {
		limiter.beforeSend()
		p.sendTo(p.remotePort())
	}
	span := maxInt(1, p.params.sprayPortCountMax-p.params.sprayPortCountMin+1)
	base := p.params.sprayPortCountMin + rand.Intn(span)
	count := base
	if round > 2 {
		count = maxInt(base*p.params.sprayDecayNumerator/round, p.params.sprayDecayFloor)
	}
	for i := 0; i < count; i++ {
		port := p.nextSprayPort()
		c := conns[i%len(conns)]
		for r := 0; r < p.params.sprayPacketsPerPort; r++ {
			limiter.beforeSend()
			p.sendConnTo(c, port)
		}
		if p.params.sprayPortIntervalMs > 0 {
			if !p.sleepInterruptible(time.Duration(p.params.sprayPortIntervalMs) * time.Millisecond) {
				return false
			}
		}
	}
	return true
}

func (p *puncher) nextSprayPort() int {
	idx := p.sprayCur % len(p.sprayTable)
	p.sprayCur = idx + 1
	return p.sprayTable[idx]
}

// sendControlMultiPort 扫窗发送（sendControlMultiPort 同款：
// range>20 随机取 sweepWindowSize 个端口（必含中心），否则逐偏移上下交替；
// minRounds × minPass，端口间 sleepShort、轮间 sleepLong）。
func (p *puncher) sendControlMultiPort(portRange, round int) {
	data := p.buildControl(punchTypePunch)
	remote := p.target()
	if remote == nil {
		return
	}
	centerPort := remote.Port
	useRandomScan := portRange > 20
	portsToSend := []int{centerPort}
	if useRandomScan {
		lowBound := maxInt(1, centerPort-portRange)
		highBound := minInt(65535, centerPort+portRange)
		rangeSize := highBound - lowBound + 1
		maxRandom := minInt(p.profile.send.sweepWindowSize, rangeSize-1)
		chosen := map[int]bool{centerPort: true}
		for len(chosen) < maxRandom+1 {
			chosen[lowBound+rand.Intn(rangeSize)] = true
		}
		rest := make([]int, 0, len(chosen))
		for port := range chosen {
			if port != centerPort {
				rest = append(rest, port)
			}
		}
		rand.Shuffle(len(rest), func(i, j int) { rest[i], rest[j] = rest[j], rest[i] })
		portsToSend = append(portsToSend, rest...)
	} else {
		for off := 1; off <= portRange; off++ {
			if lo := centerPort - off; lo > 0 {
				portsToSend = append(portsToSend, lo)
			}
			if hi := centerPort + off; hi <= 65535 {
				portsToSend = append(portsToSend, hi)
			}
		}
	}

	for roundPass := 0; roundPass < p.effectiveMinRounds(); roundPass++ {
		remote := p.target()
		if remote == nil {
			return
		}
		for i, port := range portsToSend {
			addr := &net.UDPAddr{IP: remote.IP, Port: port}
			for r := 0; r < p.effectiveMinPass(); r++ {
				udpSendTo(p.conn, data, addr)
			}
			if i < len(portsToSend)-1 && p.profile.send.sleepShortMs > 0 {
				if !p.sleepInterruptible(time.Duration(p.profile.send.sleepShortMs) * time.Millisecond) {
					return
				}
			}
		}
		if roundPass < 2 && p.profile.send.sleepLongMs > 0 {
			if !p.sleepInterruptible(time.Duration(p.profile.send.sleepLongMs) * time.Millisecond) {
				return
			}
		}
	}
	_ = round
}

func (p *puncher) sendControlSingle() {
	p.sendTo(p.remotePort())
}

func (p *puncher) remotePort() int {
	if t := p.target(); t != nil {
		return t.Port
	}
	return 0
}

func (p *puncher) clampedRemotePort(off int) int {
	dp := p.remotePort() + off
	if dp < 1 {
		dp = 1
	} else if dp > 65535 {
		dp = 65535
	}
	return dp
}

func (p *puncher) sendTo(port int) {
	remote := p.target()
	if remote == nil {
		return
	}
	addr := &net.UDPAddr{IP: remote.IP, Port: port}
	udpSendTo(p.conn, p.buildControl(punchTypePunch), addr)
}

func (p *puncher) sendConnTo(c *net.UDPConn, port int) {
	remote := p.target()
	if remote == nil || c == nil {
		return
	}
	addr := &net.UDPAddr{IP: remote.IP, Port: port}
	udpSendTo(c, p.buildControl(punchTypePunch), addr)
}

// completeFailure 发送侧线程耗尽/防火墙判定的失败收尾。
func (p *puncher) completeFailure(fr *punchResult) {
	if !p.completed.Swap(true) {
		p.punching.Store(false)
		p.notifyResult(fr)
	}
}

func (p *puncher) failIfRunning(elapsed int64, firewall, prediction bool) {
	p.failIfRunningN(1, elapsed, firewall, prediction)
}

func (p *puncher) failIfRunningN(tried int, elapsed int64, firewall, prediction bool) {
	if !p.holeOpen.Load() && p.punching.Load() {
		fr := &punchResult{
			socketsTried:     tried,
			recvPunch:        int(p.recvPunch.Load()),
			recvAck:          int(p.recvAck.Load()),
			elapsedMs:        elapsed,
			firewallDetected: firewall,
		}
		if prediction {
			fr = fr.withPortPrediction()
		}
		p.completeFailure(fr)
	}
}

// punchRandomNonce 每个 socket 一个随机 nonce。
func punchRandomNonce() uint16 {
	var b [2]byte
	_, _ = cryptRandRead(b[:])
	return uint16(b[0])<<8 | uint16(b[1])
}

// punchListen 创建打洞 socket；preferredPort>0 时优先绑定（失败退随机端口）。
func punchListen(preferredPort int) (*net.UDPConn, error) {
	if preferredPort > 0 && preferredPort <= 65535 {
		if uc, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4zero, Port: preferredPort}); err == nil {
			return uc, nil
		}
	}
	return net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4zero, Port: 0})
}
