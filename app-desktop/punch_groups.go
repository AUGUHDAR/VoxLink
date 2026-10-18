package main

// punch_groups.go — 档位中"已声明但引擎从未读"的计时层 / socket 组层的可复用实现。
//
// 逐值对齐：
//   room/ConnectionManager.java（下称 CM）
//   network/UdpHolePuncher.java（下称 UHP）
//   network/PunchProfile.java
// 本文件不改动 punch_profile.go / punch.go / conn_engine.go / punch_strategy.go，
// 只提供纯函数 + 一个可取消计时器；接线点见交付清单。
// 编译由人工执行（agent 不编译）。

import (
	"net"
	"sync"
	"time"
)

// ============================================================================
// 1. 有效最大周期数（CM:6214-6228 getEffectiveMaxCycles）
// ============================================================================

// effectiveMaxCycles = CM:6214-6232。probe 为 nil 即 Java 的
// stunProbeResult == null（CM:6216/6226）。
func effectiveMaxCycles(p *punchProfile, probe *stunProbeResult) int {
	if probe != nil {
		reachable := len(probe.ReachableStunURLs) // CM:6218 reachableStunUrls.size()
		if probe.NatType.symmetric() {            // CM:6220 natType.isSymmetric()
			// CM:6222 源码字面就是 reachable * 2 / 2（恒等式），照抄不改写、不"化简"。
			return maxInt(2, minInt(reachable*2/2, p.maxSymCycles))
		}
		return maxInt(1, minInt(reachable, p.maxCycles)) // CM:6224
	}
	return p.fallbackCycles // CM:6228；8 套档位该值均为 3（punch_profile.go:123-145 末位）
}

// ============================================================================
// 2. 渐进扫窗上限：受对端上报窗口压制（UHP:1253-1258）
// ============================================================================

// profileEffectivePortRange = UHP:163-166 effectivePortRange()。
// 与 punch.go:166-171 的 (*puncher).effectivePortRange 同式，这里做成自由函数供组层复用。
func profileEffectivePortRange(p *punchProfile, params *punchParams) int {
	if params != nil && params.portRange > 0 { // UHP:165
		return params.portRange
	}
	return p.portPredictionMaxRange // UHP:165 后半
}

// progressiveSweepRange = UHP:1253-1258（非 fixedRange 分支）。
// peerRange 即 UHP:1111 punchWithPortPrediction 的形参 portRange ——
// 对端上报的预测窗口，不是本端档位窗口。
func progressiveSweepRange(p *punchProfile, params *punchParams, cycle, peerRange int) int {
	if len(p.progressiveRanges) == 0 {
		return 0
	}
	idx := cycle / maxInt(1, p.cyclesPerRange) // UHP:1253
	if idx >= len(p.progressiveRanges) {       // UHP:1254-1256
		idx = len(p.progressiveRanges) - 1
	}
	upper := peerRange // UHP:1258 portRange > 0 ?
	if upper <= 0 {
		upper = profileEffectivePortRange(p, params) // UHP:1258 : effectivePortRange()
	}
	return minInt(p.progressiveRanges[idx], upper) // UHP:1258
}

// ============================================================================
// 3. socket 组构造（计数 + 建 socket 节流 + 组就绪门槛）
// ============================================================================

// socketCreateGap = CM:513 / CM:4116：Math.max(0, socketCreateIntervalMs)。
// 所有档位该值都是 50ms（punch_profile.go:123-145 第 24 位）。
func socketCreateGap(p *punchProfile) time.Duration {
	return time.Duration(maxInt(0, p.socketCreateIntervalMs)) * time.Millisecond
}

// hostMultiSocketGroupCount = CM:3953-3983 的 HOST_MULTI_COUNT 判定，分支顺序照抄：
//
//	CM:3955-3959  hostSym && joinerSym → hardSymSocketCount(84)，并切 HARDSYM
//	CM:3961-3973  仅 hostSym → hardTier ? hardSymSocketCount(84)
//	                                     : max(hostMultiMinSocketCount(5), hostMultiSocketCount(20))
//	CM:3975-3977  portUnreachable → hostMultiSocketCount(20；V100 为 84)
//	CM:3979-3981  其余 → hostMultiBaseSocketCount(3)
//
// hardTier 对应 CM:3965-3967（scenarioTier ∈ {HARD_DUAL_SYM, HARD_ONE_SYM}），
// 用 scenario_tier.go:61-63 的 scenarioIsHard 求得后传入。
func hostMultiSocketGroupCount(p *punchProfile, hostSym, joinerSym, portUnreachable, hardTier bool) int {
	switch {
	case hostSym && joinerSym:
		return p.hardSymSocketCount // CM:3957
	case hostSym:
		if hardTier {
			return p.hardSymSocketCount // CM:3971
		}
		return maxInt(p.hostMultiMinSocketCount, p.hostMultiSocketCount) // CM:3973
	case portUnreachable:
		return p.hostMultiSocketCount // CM:3977
	default:
		return p.hostMultiBaseSocketCount // CM:3981
	}
}

// joinerSymSocketGroupCount = CM:8011-8021。socketCount 只在 joinerIsSymmetric 时非 0
// （CM:8013；非对称时 Java 留 0，见 CM:8011）。
func joinerSymSocketGroupCount(p *punchProfile, joinerSym, hostSym bool) int {
	if !joinerSym {
		return 0 // CM:8011 int socketCount = 0
	}
	if hostSym {
		return p.hardSymSocketCount // CM:8015-8017
	}
	return p.joinerSymSocketCount // CM:8019，所有档位 50
}

// easySymMutualGroupCount = CM:7795。
// retryRound 即 CM:7795 的 continuousRetryRound.get()（app 侧 e.retryRound，conn_engine.go:1466）。
func easySymMutualGroupCount(p *punchProfile, retryRound int) int {
	if retryRound > 0 {
		return p.easySymMutualRetrySocketCount // CM:7795 真分支，50
	}
	return p.easySymMutualSocketCount // CM:7795 假分支，25
}

// relaySocketGroupCount = CM:11177 / CM:13255 的循环上界（所有档位 relaySocketCount=5）。
func relaySocketGroupCount(p *punchProfile) int {
	return p.relaySocketCount
}

// birthdayGroupCount = CM:9254 startBirthdayPunch(state) 的单参重载。
func birthdayGroupCount(p *punchProfile) int {
	return p.birthdaySocketCount // CM:9254；所有档位 32，V100 为 84
}

// reversePunchGroupCount = CM:9230-9244。注意 CM:9230 先 switchTo(V100,"sym_birthday")，
// 计数取的是 V100 档位而不是进入前的档位。
func reversePunchGroupCount(p *punchProfile, isEasySym bool) int {
	if isEasySym {
		return p.birthdaySocketCount // CM:9232 真分支
	}
	return p.hardSymSocketCount // CM:9232 假分支
}

// preBirthdayGroupCount = CM:2088-2092：natType 对称或未知，且两台服务器都拿到映射时，
// 预先开 hardSymSocketCount(84) 个生日 socket。
func preBirthdayGroupCount(p *punchProfile, symmetricOrUnknown, bothMapped bool) int {
	if symmetricOrUnknown && bothMapped {
		return p.hardSymSocketCount // CM:2090
	}
	return 0
}

// hardSymExtendTarget = CM:4344-4348：探测出 host 侧对称且已有 socket 不足时补到该数。
// 返回 need=false 表示不补。
func hardSymExtendTarget(p *punchProfile, hostPunchSocketSymmetric bool, have int) (target int, need bool) {
	if !hostPunchSocketSymmetric {
		return have, false
	}
	if have >= p.hardSymSocketCount {
		return have, false // CM:4344 size < hardSymSocketCount 不成立
	}
	return p.hardSymSocketCount, true // CM:4348
}

// groupMinRequired = CM:617 / CM:4140：组就绪门槛 min(birthdaySocketCount, 目标数)。
func groupMinRequired(p *punchProfile, targetCount int) int {
	return minInt(p.birthdaySocketCount, targetCount)
}

// groupStunDeadline = CM:4142-4144：
// now + max(5000ms, count * max(0, socketCreateIntervalMs) + 3000ms)。
func groupStunDeadline(p *punchProfile, count int, from time.Time) time.Time {
	created := int64(count) * int64(maxInt(0, p.socketCreateIntervalMs)) // CM:4144
	budgetMs := int64(5000)                                              // CM:4144 Math.max(5000L, .)
	if created+3000 > budgetMs {
		budgetMs = created + 3000
	}
	return from.Add(time.Duration(budgetMs) * time.Millisecond)
}

// stunRaceUrls = CM:509-511：
// socketStunCount = max(1, min(profile.socketStunCount, len(stunUrls)))；
// 只有 stunUrls 更长时才截断，否则原表返回。
func stunRaceUrls(p *punchProfile, stunUrls []string) []string {
	n := maxInt(1, minInt(p.socketStunCount, len(stunUrls))) // CM:509
	if len(stunUrls) > n {
		out := make([]string, len(stunUrls[:n])) // CM:511 new ArrayList<>(subList(0,n))
		copy(out, stunUrls[:n])
		return out
	}
	return stunUrls // CM:511 后半
}

// makeThrottledPunchSocketGroup 与 conn_engine.go:1485 makePunchSocketGroup 同构，
// 差别是每建一个 socket 之间按 socketCreateIntervalMs 停顿（CM:513-515 + CM:597-611、
// CM:4116-4132）。CM:4124-4130 被中断即 break：这里同样立刻收尾返回已建成的部分。
// first 复用为组内第 0 个（对应 CM:4051-4053 只有 i==0 绑房主端口）。
func makeThrottledPunchSocketGroup(first *net.UDPConn, count int, gap time.Duration, done <-chan struct{}) []*net.UDPConn {
	if count < 1 {
		count = 1
	}
	group := make([]*net.UDPConn, 0, count)
	if first != nil {
		group = append(group, first)
	}
	for len(group) < count {
		uc, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4zero, Port: 0})
		if err != nil {
			break // CM:531-547 / CM:4061-4073：建不出就少一个，不补
		}
		group = append(group, uc)
		if gap > 0 { // CM:597 / CM:4118
			if done != nil {
				select {
				case <-done:
					return group // CM:4124-4130 interrupt → break
				case <-time.After(gap):
				}
			} else {
				time.Sleep(gap)
			}
		}
	}
	return group
}

// ============================================================================
// 4. 每轮 / 每次的超时与重试
// ============================================================================

// hostRoundParams = CM:4041-4045 / CM:4360-4366 / CM:4569-4583：
// 从档位取参后把 timeoutMs 压到 hostRoundTimeoutMs
// （按 DEFAULT/AGGRESSIVE/HARDSYM/EASY_SYM_DUAL/V100/FAST_LANE/RELIABLE_CONE/WIDE_SWEEP
//
//	顺序 = 8000/10000/12000/8000/8000/6000/10000/12000，punch_profile.go:123-145 第 25 位）。
func hostRoundParams(p *punchProfile) *punchParams {
	params := punchParamsFromProfile(p)
	params.timeoutMs = minInt(params.timeoutMs, p.hostRoundTimeoutMs) // CM:4043
	return params
}

// hostRoundParamsWithPeerRange = CM:4573-4579：hostPortRange>0 时把发送门槛降到 1 轮 1 过。
func hostRoundParamsWithPeerRange(p *punchProfile, peerRange int) *punchParams {
	params := hostRoundParams(p)
	if peerRange > 0 { // CM:4573
		params.sendMinRounds = 1 // CM:4575
		params.sendMinPass = 1   // CM:4577
	}
	return params
}

// punchMaxAttempts = CM:8131/8197/8363/8413 读的 profile.punchMaxAttempts
// （3/4/5/3/4/3/4/5，见 punch_profile.go:123-145 第 29 位）。
func punchMaxAttempts(p *punchProfile) int { return p.punchMaxAttempts }

// punchRetryDelay = CM:8199 / 8415 / 8627 / 8877 / 9013：
// delay = punchRetryDelayMs * (1L << min(attempt - 1, 4))，attempt 从 1 起。
// 上限移位 4 ⇒ 最大 16 倍；800→12800、600→9600、500→8000、700→11200ms。
// shift<0 的钳位只为避开 Go 的负移位 panic：Java 侧 attempt 恒 >=1，
// 该分支在 Java 里不可达（详见交付清单"未确定"）。
func punchRetryDelay(p *punchProfile, attempt int) time.Duration {
	shift := attempt - 1
	if shift > 4 {
		shift = 4 // CM:8199 Math.min(attempt - 1, 4)
	}
	if shift < 0 {
		shift = 0
	}
	return time.Duration(int64(p.punchRetryDelayMs)<<uint(shift)) * time.Millisecond
}

// shouldRetryPunch = CM:8197 / 8413 / 8625 / 8875 / 9011：attempt < punchMaxAttempts。
func shouldRetryPunch(p *punchProfile, attempt int) bool {
	return attempt < p.punchMaxAttempts
}

// ============================================================================
// 5. 会话级总超时（connectionTimeoutSec / symmetricConnectionTimeoutSec）
// ============================================================================

// sessionDeadline 返回本次会话的总超时秒数。
//
// isSym  = CM:6601 joinerSym（本地 stunProbeResult.natType.isSymmetric()）
// hostSym = CM:6603 state.roomInfo.isHostSymmetric()
// 两者任一为真 → symmetricConnectionTimeoutSec，否则 connectionTimeoutSec
// （CM:6599 / CM:6605-6607）。
// 档位值 connectionTimeoutSec / symmetricConnectionTimeoutSec（同上的 8 档顺序）
// = 45/50/60/45/40/35/50/65 与 75/85/100/75/70/60/80/110
// （punch_profile.go:123-145 第 27、28 位）。
//
// 注：CM:6596-6607 与 CM:6743-6751 的公式都只看"是否对称"，不看 STUN 可达数；
// 任务里要求的 stunReach 形参在 Java 侧无对应读取点，故不入公式（见"未确定"）。
func sessionDeadline(p *punchProfile, isSym, hostSym bool) int {
	if isSym || hostSym { // CM:6605
		return p.symmetricConnectionTimeoutSec // CM:6607
	}
	return p.connectionTimeoutSec // CM:6599
}

// sessionDeadlineFromProbe = CM:6599-6607 的取数包装：probe 为 nil 时 isSym=false
// （CM:6601 的 `stunProbeResult != null &&` 短路）。
func sessionDeadlineFromProbe(p *punchProfile, probe *stunProbeResult, hostSym bool) int {
	isSym := probe != nil && probe.NatType.symmetric() // CM:6601
	return sessionDeadline(p, isSym, hostSym)
}

// extendSessionTimeout = CM:6733-6767 extendConnectionTimeoutIfNeeded 的纯计算部分。
// 返回 remaining=true 时按 CM:6757 重新排期 (int)(remainingMs/1000)+1 秒。
// connectionStartMs==0 或 probe==nil 直接不延长（CM:6735）。
func extendSessionTimeout(p *punchProfile, probe *stunProbeResult, hostSym bool, currentTimeoutSec int, connectionStart time.Time, now time.Time) (newTimeoutSec int, rescheduleSec int, remaining bool) {
	if connectionStart.IsZero() || probe == nil { // CM:6735 != 0L / != null
		return currentTimeoutSec, 0, false
	}
	isSym := probe.NatType.symmetric() // CM:6737
	if !isSym && !hostSym {            // CM:6741
		return currentTimeoutSec, 0, false
	}
	if currentTimeoutSec >= p.symmetricConnectionTimeoutSec { // CM:6743
		return currentTimeoutSec, 0, false
	}
	elapsedMs := now.Sub(connectionStart).Milliseconds() // CM:6745
	remainingMs := int64(p.symmetricConnectionTimeoutSec)*1000 - elapsedMs
	if remainingMs <= 0 { // CM:6749
		return currentTimeoutSec, 0, false
	}
	// CM:6757 (int)(remainingMs / 1000L) + 1
	return p.symmetricConnectionTimeoutSec, int(remainingMs/1000) + 1, true
}

// sessionTimer 对应 CM:6701-6729 的 connectionTimeoutFuture：
// 同一时刻只挂一个，重新排期先取消旧的（CM:6703-6707），
// 触发时按 CM:6715-6725 的三道门复核后才回调。
type sessionTimer struct {
	mu     sync.Mutex
	timer  *time.Timer
	won    func() bool // CM:6715 connectionWon.get()
	active func() bool // CM:6717 connectionCycleActive.get()
	same   func() bool // CM:6717 roomManager.currentRoom.get() == state
}

func newSessionTimer(won, active, same func() bool) *sessionTimer {
	return &sessionTimer{won: won, active: active, same: same}
}

// arm = CM:6701-6727。timeoutSec<=0 与 Java 一样仍会排期（Java 未做 <=0 判断），
// 但 Go 的 time.AfterFunc 非正数会立即触发，故由调用方保证传 CM:6599/6607 的原值。
// sessionTimeoutUnit 只是把"秒"这个单位抽出来给用例拨表（值恒为 time.Second，语义不变）。
var sessionTimeoutUnit = time.Second

func (t *sessionTimer) arm(timeoutSec int, onTimeout func(finalTimeoutSec int)) {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.timer != nil { // CM:6703
		t.timer.Stop() // CM:6705 cancel(false)：已在跑的回调不抢占
	}
	sec := timeoutSec // CM:6711 finalTimeoutSec
	t.timer = time.AfterFunc(time.Duration(sec)*sessionTimeoutUnit, func() {
		if t.won != nil && t.won() { // CM:6715
			return
		}
		if t.active != nil && !t.active() { // CM:6717
			return
		}
		if t.same != nil && !t.same() { // CM:6717
			return
		}
		if onTimeout != nil { // CM:6719-6721 showConnectFailed(global_timeout)
			onTimeout(sec)
		}
	})
}

// cancel 停掉挂着的定时器；不回调。CM:6703-6707 的旧 future 由下一次 arm 顶掉，
// 会话正常结束/胜利时须显式 cancel（app 侧目前无此清理点）。
func (t *sessionTimer) cancel() {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.timer != nil {
		t.timer.Stop()
		t.timer = nil
	}
}

// ============================================================================
// 6. 反向打洞退避与窗口
// ============================================================================

// reverseBackoffDelaysMs = CM:376 BACKOFF_DELAYS_MS = {1000, 2000, 4000}。
var reverseBackoffDelaysMs = []int64{1000, 2000, 4000}

// reverseBackoffDelay = CM:9161-9163：
// idx = min(cycle, len-1)，越界取最后一档（cycle 从 0 计）。
func reverseBackoffDelay(cycle int) time.Duration {
	idx := cycle
	if idx > len(reverseBackoffDelaysMs)-1 {
		idx = len(reverseBackoffDelaysMs) - 1 // CM:9161
	}
	if idx < 0 {
		idx = 0
	}
	return time.Duration(reverseBackoffDelaysMs[idx]) * time.Millisecond // CM:9163
}

// reverseWindow = CM:5495 / 9442 / 10090 的 reverseWindowSec（40/45/55/40/35/30/40/60）。
func reverseWindow(p *punchProfile) time.Duration {
	return time.Duration(p.reverseWindowSec) * time.Second
}
