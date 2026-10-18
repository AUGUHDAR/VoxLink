package main

// punch_predictor.go — 端口预测（逐值对齐模组 PortPredictor.java）：
// 线性回归 ×0.6 + delta 预测 ×0.4 加权融合；置信范围按样本数
// ≥10 → ±32；≥5 → ±64；≥3 → ±100；否则 ±200；钳制 [1024,65535]；
// generateTargetPorts 围绕预测值 ±range 逐偏移上下交替扫。

import "math"

const (
	predictorMinPort = 1024
	predictorMaxPort = 65535
)

// javaRound 等价 (int)Math.round(x)：Java 的 Math.round 是 floor(x+0.5)，
// Go 的 int(x+0.5) 是朝零截断，两者只在负数上分叉（-2.6：Java -3、截断 -2）。
// 端口预测的 delta 完全可能是负的（映射端口在往下走），所以四处取整必须同一口径，
// 否则预测值与模组差 1 个端口，扫的就是错的那一格。
func javaRound(x float64) int {
	return int(math.Floor(x + 0.5))
}

type predictResult struct {
	predictedPort int
	rangeHalf     int
	strategy      string
}

func predictorConfidenceRange(sampleCount int) int {
	if sampleCount >= 10 {
		return 32
	} else if sampleCount >= 5 {
		return 64
	} else if sampleCount >= 3 {
		return 100
	}
	return 200
}

func linearRegressionPredict(ports []int) int {
	if len(ports) == 0 {
		return -1
	}
	n := len(ports)
	if n == 1 {
		return ports[0]
	}
	var sumX, sumY, sumXY, sumXX float64
	for i, p := range ports {
		sumX += float64(i)
		sumY += float64(p)
		sumXY += float64(i) * float64(p)
		sumXX += float64(i * i)
	}
	denom := float64(n)*sumXX - sumX*sumX
	if absF(denom) < 1e-9 {
		return javaRound(sumY / float64(n))
	}
	slope := (float64(n)*sumXY - sumX*sumY) / denom
	intercept := (sumY - slope*sumX) / float64(n)
	return javaRound(slope*float64(n) + intercept)
}

// deltaPredict 截头去尾 1/4 后的 EMA(α=0.4)，预测 = 末样本 + delta。
func deltaPredict(ports []int) int {
	if len(ports) < 2 {
		return -1
	}
	deltas := make([]int, 0, len(ports)-1)
	for i := 1; i < len(ports); i++ {
		deltas = append(deltas, ports[i]-ports[i-1])
	}
	// 升序后截头去尾各 1/4（sorted.subList(trim, size-trim)）
	sorted := append([]int(nil), deltas...)
	for i := 1; i < len(sorted); i++ {
		for j := i; j > 0 && sorted[j] < sorted[j-1]; j-- {
			sorted[j], sorted[j-1] = sorted[j-1], sorted[j]
		}
	}
	trim := len(sorted) / 4
	trimmed := sorted[trim : len(sorted)-trim]
	if len(trimmed) == 0 {
		trimmed = sorted
	}
	ema := float64(trimmed[0])
	const alpha = 0.4
	for _, d := range trimmed[1:] {
		ema += alpha * (float64(d) - ema)
	}
	return ports[len(ports)-1] + javaRound(ema)
}

// predict 融合预测（单样本直接用该端口 range=200）。
func predict(ports []int) predictResult {
	if len(ports) == 0 {
		return predictResult{-1, 200, "no_samples"}
	}
	if len(ports) == 1 {
		return predictResult{ports[0], 200, "single_sample"}
	}
	lrPredicted := linearRegressionPredict(ports)
	deltaPredicted := deltaPredict(ports)
	var final int
	if lrPredicted > 0 && deltaPredicted > 0 {
		final = javaRound(float64(lrPredicted)*0.6 + float64(deltaPredicted)*0.4)
	} else if lrPredicted > 0 {
		final = lrPredicted
	} else {
		final = deltaPredicted
	}
	if final < predictorMinPort {
		final = predictorMinPort
	}
	if final > predictorMaxPort {
		final = predictorMaxPort
	}
	return predictResult{final, predictorConfidenceRange(len(ports)), "combined"}
}

// generateTargetPorts 围绕预测值 ±range 逐偏移上下交替（预测值本身在最前）。
func generateTargetPorts(predictedPort, r int) []int {
	targets := make([]int, 0, r*2+1)
	lo := maxInt(predictorMinPort, predictedPort-r)
	hi := minInt(predictorMaxPort, predictedPort+r)
	targets = append(targets, predictedPort)
	for off := 1; off <= r; off++ {
		up := predictedPort + off
		down := predictedPort - off
		if up <= hi {
			targets = append(targets, up)
		}
		if down >= lo {
			targets = append(targets, down)
		}
	}
	return targets
}
