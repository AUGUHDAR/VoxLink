package main

// punch_profile.go — 打洞档位/自适应调参（逐值对齐模组 PunchProfile.java / PunchParams.java /
// PunchTuner.java / PunchResult.java / PunchFailureClassifier.java，禁止改动任何数值）。

import (
	"net"
	"sync"
)

// sendParams 11 参数（字段序即 PunchProfile.SendParams 构造序）。
type sendParams struct {
	intervalMs      int
	socketTimeoutMs int
	extraWaitMs     int
	extraWaitLongMs int
	jitterBaseMs    int
	jitterRangeMs   int
	minRounds       int
	minPass         int
	sleepShortMs    int
	sleepLongMs     int
	sweepWindowSize int
}

// symParams 对称 NAT 分级配方（EasyTier 量化值，PunchProfile.RECIPE）。
type symParams struct {
	easySymBombSockets     int
	easySymBombWindow      int
	easySymRoundIntervalMs int
	easySymBombDurationMs  int
	hardSymSprayPortMin    int
	hardSymSprayPortMax    int
	hardSymPacketsPerPort  int
	hardSymPortIntervalMs  int
	hardSymDecayNumerator  int
	hardSymDecayFloor      int
	maxPps                 int
}

var punchRecipe = symParams{25, 20, 100, 5000, 600, 800, 3, 1, 2, 180, 3000}

var (
	sendDefault     = sendParams{200, 500, 1000, 2000, 600, 200, 3, 3, 1, 10, 800}
	sendDefaultFast = sendParams{200, 500, 1000, 2000, 600, 200, 1, 2, 1, 5, 800}
	sendSprint      = sendParams{100, 300, 600, 1200, 400, 150, 1, 1, 1, 3, 400}
	sendWide        = sendParams{150, 500, 1000, 2000, 500, 200, 1, 2, 1, 5, 800}
	sendWeak        = sendParams{250, 800, 1500, 3000, 500, 250, 3, 3, 2, 8, 600}
)

// punchProfile 一套打洞档位（字段序即 PunchProfile 构造序）。
type punchProfile struct {
	name                          string
	punchTimeoutMs                int
	firewallDetectCycles          int
	portPredictionMaxRange        int
	progressiveRanges             []int
	cyclesPerRange                int
	easySymDualSocketCount        int
	easySymDualPortRange          int
	defaultPortRange              int
	widePortRange                 int
	maxPortRange                  int
	minPortRange                  int
	easySymPortRange              int
	hostMultiSocketCount          int
	hostMultiMinSocketCount       int
	hostMultiBaseSocketCount      int
	hardSymSocketCount            int
	birthdaySocketCount           int
	joinerSymSocketCount          int
	relaySocketCount              int
	joinerMultiPortRange          int
	easySymMutualSocketCount      int
	easySymMutualRetrySocketCount int
	coneBackupPortRange           int
	socketStunCount               int
	socketCreateIntervalMs        int
	hostRoundTimeoutMs            int
	reverseWindowSec              int
	connectionTimeoutSec          int
	symmetricConnectionTimeoutSec int
	punchMaxAttempts              int
	punchRetryDelayMs             int
	maxCycles                     int
	maxSymCycles                  int
	fallbackCycles                int
	send                          sendParams
	sym                           symParams
}

func newPunchProfile(name string, v []int, prog []int, s sendParams) *punchProfile {
	p := &punchProfile{
		name:              name,
		progressiveRanges: prog,
		send:              s,
		sym:               punchRecipe,
	}
	fields := []*int{
		&p.punchTimeoutMs, &p.firewallDetectCycles, &p.portPredictionMaxRange,
		&p.cyclesPerRange, &p.easySymDualSocketCount, &p.easySymDualPortRange,
		&p.defaultPortRange, &p.widePortRange, &p.maxPortRange, &p.minPortRange,
		&p.easySymPortRange, &p.hostMultiSocketCount, &p.hostMultiMinSocketCount,
		&p.hostMultiBaseSocketCount, &p.hardSymSocketCount, &p.birthdaySocketCount,
		&p.joinerSymSocketCount, &p.relaySocketCount, &p.joinerMultiPortRange,
		&p.easySymMutualSocketCount, &p.easySymMutualRetrySocketCount,
		&p.coneBackupPortRange, &p.socketStunCount, &p.socketCreateIntervalMs,
		&p.hostRoundTimeoutMs, &p.reverseWindowSec, &p.connectionTimeoutSec,
		&p.symmetricConnectionTimeoutSec, &p.punchMaxAttempts, &p.punchRetryDelayMs,
		&p.maxCycles, &p.maxSymCycles, &p.fallbackCycles,
	}
	for i, f := range fields {
		if i < len(v) {
			*f = v[i]
		}
	}
	return p
}

// 8 套档位（数值逐项对齐 PunchProfile.java 静态实例）。
var (
	punchDefault = newPunchProfile("DEFAULT", []int{
		12000, 30, 100, 1, 25, 20, 30, 50, 100, 3, 20, 20, 5, 3, 84, 32, 50, 5, 30, 25, 50, 10, 2, 50, 8000, 40, 45, 75, 3, 800, 8, 6, 3,
	}, []int{10, 25, 50, 75, 100}, sendDefault)
	punchAggressive = newPunchProfile("AGGRESSIVE", []int{
		20000, 50, 100, 1, 25, 20, 30, 50, 100, 3, 20, 20, 5, 3, 84, 32, 50, 5, 30, 25, 50, 10, 2, 50, 10000, 45, 50, 85, 4, 600, 10, 8, 3,
	}, []int{10, 25, 50, 75, 100}, sendDefaultFast)
	punchHardSym = newPunchProfile("HARDSYM", []int{
		30000, 60, 500, 2, 25, 20, 30, 50, 500, 3, 20, 84, 5, 3, 84, 32, 50, 5, 30, 25, 50, 10, 2, 50, 12000, 55, 60, 100, 5, 500, 12, 10, 3,
	}, []int{20, 50, 100, 200, 500}, sendWide)
	punchEasySymDual = newPunchProfile("EASY_SYM_DUAL", []int{
		12000, 30, 50, 2, 25, 50, 20, 50, 50, 3, 20, 25, 5, 3, 84, 32, 50, 5, 30, 25, 50, 10, 2, 50, 8000, 40, 45, 75, 3, 800, 8, 6, 3,
	}, []int{5, 10, 20, 30, 50}, sendDefault)
	punchV100 = newPunchProfile("V100", []int{
		8000, 38, 100, 2, 25, 20, 30, 50, 100, 3, 20, 84, 20, 3, 84, 84, 50, 5, 30, 25, 50, 10, 2, 50, 8000, 35, 40, 70, 4, 600, 10, 8, 3,
	}, []int{10, 25, 50, 75, 100}, sendDefault)
	punchFastLane = newPunchProfile("FAST_LANE", []int{
		6000, 15, 20, 1, 25, 20, 30, 50, 100, 3, 20, 20, 5, 3, 84, 32, 50, 5, 30, 25, 50, 10, 2, 50, 6000, 30, 35, 60, 3, 800, 8, 6, 3,
	}, []int{5, 10, 20}, sendSprint)
	punchReliableCone = newPunchProfile("RELIABLE_CONE", []int{
		15000, 40, 40, 1, 25, 20, 30, 50, 100, 3, 20, 20, 5, 3, 84, 32, 50, 5, 30, 25, 50, 10, 2, 50, 10000, 40, 50, 80, 4, 700, 10, 8, 3,
	}, []int{5, 10, 20, 40}, sendWeak)
	punchWideSweep = newPunchProfile("WIDE_SWEEP", []int{
		35000, 70, 800, 1, 25, 20, 30, 50, 800, 3, 20, 84, 5, 3, 84, 32, 50, 5, 30, 25, 50, 10, 2, 50, 12000, 60, 65, 110, 5, 500, 12, 10, 3,
	}, []int{100, 200, 400, 800}, sendWide)
)

// 全局当前档位（PunchProfile.current / switchTo）。
var (
	punchProfileMu      sync.Mutex
	punchProfileCurrent = punchDefault
	punchProfileReason  = "initial"
)

func punchProfileSwitch(target *punchProfile, reason string) {
	punchProfileMu.Lock()
	defer punchProfileMu.Unlock()
	if target != nil && target != punchProfileCurrent {
		old := punchProfileCurrent
		punchProfileCurrent = target
		punchProfileReason = reason
		_ = old
	}
}

func punchProfileNow() *punchProfile {
	punchProfileMu.Lock()
	defer punchProfileMu.Unlock()
	return punchProfileCurrent
}

func (p *punchProfile) describe() string {
	return p.name + "(timeout=" + itoa(p.punchTimeoutMs) + "ms, cycles=" + itoa(p.firewallDetectCycles) + ", range=" + itoa(p.portPredictionMaxRange) + ")"
}

func itoa(v int) string {
	if v == 0 {
		return "0"
	}
	neg := v < 0
	if neg {
		v = -v
	}
	var b [12]byte
	i := len(b)
	for v > 0 {
		i--
		b[i] = byte('0' + v%10)
		v /= 10
	}
	if neg {
		i--
		b[i] = '-'
	}
	return string(b[i:])
}

// ---- punchParams（PunchParams.java）----

type punchParams struct {
	portRange              int
	timeoutMs              int
	sendInterval           int
	ackRetries             int
	skipDirectPunch        bool
	reuseSuccessfulSockets bool
	successfulPortRange    [2]int
	sendMinRounds          int
	sendMinPass            int
	hardSymSpray           bool
	sprayPortCountMin      int
	sprayPortCountMax      int
	sprayPacketsPerPort    int
	sprayPortIntervalMs    int
	sprayDecayNumerator    int
	sprayDecayFloor        int
	easySymBomb            bool
	bombWindow             int
	bombRoundIntervalMs    int
	bombDurationMs         int
}

func punchParamsFromProfile(p *punchProfile) *punchParams {
	pp := &punchParams{
		portRange:           p.portPredictionMaxRange,
		timeoutMs:           p.punchTimeoutMs,
		sendInterval:        p.send.intervalMs,
		ackRetries:          1,
		sprayPortCountMin:   p.sym.hardSymSprayPortMin,
		sprayPortCountMax:   p.sym.hardSymSprayPortMax,
		sprayPacketsPerPort: p.sym.hardSymPacketsPerPort,
		sprayPortIntervalMs: p.sym.hardSymPortIntervalMs,
		sprayDecayNumerator: p.sym.hardSymDecayNumerator,
		sprayDecayFloor:     p.sym.hardSymDecayFloor,
		bombWindow:          p.sym.easySymBombWindow,
		bombRoundIntervalMs: p.sym.easySymRoundIntervalMs,
		bombDurationMs:      p.sym.easySymBombDurationMs,
	}
	if p == punchHardSym {
		pp.hardSymSpray = true
	}
	if p == punchEasySymDual {
		pp.easySymBomb = true
	}
	return pp
}

func clonePunchParams(src *punchParams) *punchParams {
	cp := *src
	return &cp
}

// ---- PunchTuner（9 常量验收表逐值对齐）----

const (
	tunerMaxPortRange             = 500
	tunerMaxTimeoutMs             = 30000
	tunerMinSendIntervalMs        = 50
	tunerLateCycleTimeoutMs       = 5000
	tunerAckRetriesOnTimeout      = 3
	tunerPredictionDeltaThreshold = 100
	tunerPortRangeMultiplier      = 2
	tunerTimeoutIncrementMs       = 4000
	tunerSendIntervalDivisor      = 2
)

type punchFailureReason int

const (
	failureNoResponse punchFailureReason = iota
	failureResponseNoAck
	failureAckTimeout
	failurePartialSuccess
	failurePredictionOff
	failureFirewallDetected
)

func (r punchFailureReason) String() string {
	switch r {
	case failureNoResponse:
		return "NO_RESPONSE"
	case failureResponseNoAck:
		return "RESPONSE_NO_ACK"
	case failureAckTimeout:
		return "ACK_TIMEOUT"
	case failurePartialSuccess:
		return "PARTIAL_SUCCESS"
	case failurePredictionOff:
		return "PREDICTION_OFF"
	case failureFirewallDetected:
		return "FIREWALL_DETECTED"
	}
	return "UNKNOWN"
}

// classifyPunchFailure PunchFailureClassifier.classify 同款判定序。
func classifyPunchFailure(r *punchResult) punchFailureReason {
	if r == nil {
		return failureNoResponse
	}
	if r.portPredictionActive && r.recvPunch == 0 && r.recvAck == 0 && !r.firewallDetected {
		return failurePredictionOff
	}
	if r.predictionDelta > tunerPredictionDeltaThreshold {
		return failurePredictionOff
	}
	if r.recvPunch == 0 && r.recvAck == 0 {
		if r.firewallDetected {
			return failureFirewallDetected
		}
		return failureNoResponse
	}
	if r.recvPunch > 0 && r.recvAck == 0 {
		return failureResponseNoAck
	}
	if r.recvAck > 0 && !r.success {
		return failureAckTimeout
	}
	if r.recvPunch > 0 && r.recvPunch < r.socketsTried {
		return failurePartialSuccess
	}
	return failureNoResponse
}

// tunerNextParams PunchTuner.nextParams 同款（cycle 从 0 计，maxCycles 为档位 maxCycles）。
// lastFailure 为 nil 表示上一轮成功/无失败记录（Java 的 lastFailure == null 路径）。
func tunerNextParams(currentProfile *punchProfile, local, remote natClass, cycle, maxCycles int, lastFailure *punchFailureReason, lastResult *punchResult) *punchParams {
	params := punchParamsFromProfile(currentProfile)
	if lastFailure == nil {
		return applyLateCycleLimit(params, cycle, maxCycles)
	}

	switch *lastFailure {
	case failureNoResponse:
		params.portRange = minInt(params.portRange*tunerPortRangeMultiplier, tunerMaxPortRange)
		params.timeoutMs = minInt(params.timeoutMs+tunerTimeoutIncrementMs, tunerMaxTimeoutMs)
		params.sendMinRounds = 3
		params.sendMinPass = 3
	case failurePredictionOff:
		params = punchParamsFromProfile(punchHardSym)
		params.portRange = minInt(params.portRange*tunerPortRangeMultiplier, tunerMaxPortRange)
		params.sendMinRounds = 3
		params.sendMinPass = 3
	case failureResponseNoAck:
		params.sendInterval = maxInt(params.sendInterval/tunerSendIntervalDivisor, tunerMinSendIntervalMs)
		params.sendMinPass = 3
	case failureFirewallDetected:
		params.skipDirectPunch = true
	case failurePartialSuccess:
		if lastResult != nil && lastResult.predictionDelta > 0 {
			params.reuseSuccessfulSockets = true
			params.successfulPortRange = [2]int{lastResult.predictionDelta - 5, lastResult.predictionDelta + 5}
		}
	case failureAckTimeout:
		params.ackRetries = tunerAckRetriesOnTimeout
		params.sendMinPass = 3
	}

	if lastResult != nil && lastResult.elapsedMs > 0 {
		if lastResult.elapsedMs > 10000 {
			params.timeoutMs = minInt(params.timeoutMs*3/2, tunerMaxTimeoutMs)
		} else if lastResult.elapsedMs < 3000 && *lastFailure == failureNoResponse {
			params.portRange = minInt(params.portRange*tunerPortRangeMultiplier, tunerMaxPortRange)
		}
	}

	return applyLateCycleLimit(params, cycle, maxCycles)
}

func applyLateCycleLimit(params *punchParams, cycle, maxCycles int) *punchParams {
	if cycle >= maxCycles-2 && params.timeoutMs > tunerLateCycleTimeoutMs {
		params.timeoutMs = maxInt(tunerLateCycleTimeoutMs, params.timeoutMs*3/4)
	}
	return params
}

func minInt(a, b int) int {
	if a < b {
		return a
	}
	return b
}

func maxInt(a, b int) int {
	if a > b {
		return a
	}
	return b
}

// ---- punchResult（PunchResult.java）----

type punchResult struct {
	success              bool
	winConn              *net.UDPConn // 成功 socket（成功时非空）
	socketsTried         int
	recvPunch            int
	recvAck              int
	predictionDelta      int
	elapsedMs            int64
	firewallDetected     bool
	portPredictionActive bool
}

func (r *punchResult) withPortPrediction() *punchResult {
	if r == nil {
		return nil
	}
	cp := *r
	cp.portPredictionActive = true
	return &cp
}
