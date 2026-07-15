package icu.wuhui.voxlink.network;

import icu.wuhui.voxlink.VoxLinkMod;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 端口预测器：收窄对称NAT打洞目标范围 65536→64-512
 * 参考 EasyTier shuffled_port_vec + 生日攻击概率公式 P≈1-e^(-n²/N)
 * 组合策略：线性回归(整体趋势) + 差值序列(局部规律) + 中位数滤波(去离群)
 */
public final class PortPredictor {
    private PortPredictor() {}

    private static final int SAMPLES_HIGH = 10;
    private static final int RANGE_HIGH = 32;
    private static final int SAMPLES_MID = 5;
    private static final int RANGE_MID = 64;
    private static final int SAMPLES_LOW = 3;
    private static final int RANGE_LOW = 100;
    private static final int RANGE_DEFAULT = 200;
    private static final double EPSILON = 1e-9;
    private static final double ALPHA = 0.4;
    private static final double LR_WEIGHT = 0.6;
    private static final double DELTA_WEIGHT = 0.4;
    private static final int MIN_PORT = 1024;
    private static final int MAX_PORT = 65535;

    //预测置信区间：n个采样下, 端口预测误差范围
    //经验值：3采样±100, 5采样±64, 10采样±32
    private static int confidenceRange(int sampleCount) {
        if (sampleCount >= SAMPLES_HIGH) return RANGE_HIGH;
        if (sampleCount >= SAMPLES_MID) return RANGE_MID;
        if (sampleCount >= SAMPLES_LOW) return RANGE_LOW;
        return RANGE_DEFAULT;
    }

    /**
     * 线性回归预测下一个端口
     * 最小二乘法拟合 y = a*x + b, 预测 x=n 时的 y
     */
    public static int linearRegressionPredict(List<Integer> ports) {
        if (ports == null || ports.isEmpty()) return -1;
        int n = ports.size();
        if (n == 1) return ports.get(0);
        //x: 0,1,...,n-1; y: ports[i]
        double sumX = 0, sumY = 0, sumXY = 0, sumXX = 0;
        for (int i = 0; i < n; i++) {
            sumX += i;
            sumY += ports.get(i);
            sumXY += (double) i * ports.get(i);
            sumXX += (double) i * i;
        }
        double denom = n * sumXX - sumX * sumX;
        if (Math.abs(denom) < EPSILON) {
            //x无变化, 用均值
            return (int) Math.round(sumY / n);
        }
        double slope = (n * sumXY - sumX * sumY) / denom;
        double intercept = (sumY - slope * sumX) / n;
        int predicted = (int) Math.round(slope * n + intercept);
        VoxLinkMod.LOGGER.info("[PortPredictor] 线性回归: slope={}, intercept={}, predicted={} (n={})",
                String.format("%.2f", slope), String.format("%.2f", intercept), predicted, n);
        return predicted;
    }

    /**
     * 差值序列预测：分析相邻端口增量的规律
     * 中位数滤波去离群值, EMA平滑减抖动
     */
    public static int deltaPredict(List<Integer> ports) {
        if (ports == null || ports.size() < 2) return -1;
        List<Integer> deltas = new ArrayList<>();
        for (int i = 1; i < ports.size(); i++) {
            deltas.add(ports.get(i) - ports.get(i - 1));
        }
        //中位数滤波：去掉前后25%离群值
        List<Integer> sorted = new ArrayList<>(deltas);
        Collections.sort(sorted);
        int trim = sorted.size() / 4;
        List<Integer> trimmed = sorted.subList(trim, sorted.size() - trim);
        if (trimmed.isEmpty()) {
            trimmed = sorted;
        }
        //EMA平滑：近期权重更高 alpha=0.4
        double ema = trimmed.get(0);
        double alpha = ALPHA;
        for (int i = 1; i < trimmed.size(); i++) {
            ema = ema + alpha * (trimmed.get(i) - ema);
        }
        int delta = (int) Math.round(ema);
        int lastPort = ports.get(ports.size() - 1);
        int predicted = lastPort + delta;
        VoxLinkMod.LOGGER.info("[PortPredictor] 差值预测: delta={}, lastPort={}, predicted={} (n={})",
                delta, lastPort, predicted, ports.size());
        return predicted;
    }

    /**
     * 综合预测：线性回归 + 差值序列加权平均
     * 返回预测端口及置信范围 [+range, -range]
     */
    public static PredictResult predict(List<Integer> ports) {
        if (ports == null || ports.isEmpty()) {
            return new PredictResult(-1, RANGE_DEFAULT, "no_samples");
        }
        if (ports.size() == 1) {
            return new PredictResult(ports.get(0), RANGE_DEFAULT, "single_sample");
        }
        int lrPredicted = linearRegressionPredict(ports);
        int deltaPredicted = deltaPredict(ports);
        //加权平均：线性回归权重0.6, 差值预测权重0.4
        //线性回归考虑整体趋势更稳健, 差值预测对近期变化更敏感
        int finalPredicted;
        if (lrPredicted > 0 && deltaPredicted > 0) {
            finalPredicted = (int) Math.round(lrPredicted * LR_WEIGHT + deltaPredicted * DELTA_WEIGHT);
        } else if (lrPredicted > 0) {
            finalPredicted = lrPredicted;
        } else {
            finalPredicted = deltaPredicted;
        }
        //钳位到合法端口范围
        if (finalPredicted < MIN_PORT) finalPredicted = MIN_PORT;
        if (finalPredicted > MAX_PORT) finalPredicted = MAX_PORT;
        int range = confidenceRange(ports.size());
        VoxLinkMod.LOGGER.info("[PortPredictor] 综合: lr={}, delta={}, final={}, range=±{}",
                lrPredicted, deltaPredicted, finalPredicted, range);
        return new PredictResult(finalPredicted, range, "combined");
    }

    /**
     * 生成打洞目标端口列表：以预测端口为中心, ±range范围内
     * 用于birthday attack, 收窄65536→2*range+1个候选
     */
    public static List<Integer> generateTargetPorts(int predictedPort, int range) {
        List<Integer> targets = new ArrayList<>();
        int lo = Math.max(MIN_PORT, predictedPort - range);
        int hi = Math.min(MAX_PORT, predictedPort + range);
        //中心优先：从predictedPort向两侧扩展, 提高命中概率
        targets.add(predictedPort);
        for (int offset = 1; offset <= range; offset++) {
            int up = predictedPort + offset;
            int down = predictedPort - offset;
            if (up <= hi) targets.add(up);
            if (down >= lo) targets.add(down);
        }
        return targets;
    }

    public static class PredictResult {
        public final int predictedPort;
        public final int range;
        public final String strategy;

        PredictResult(int predictedPort, int range, String strategy) {
            this.predictedPort = predictedPort;
            this.range = range;
            this.strategy = strategy;
        }
    }
}
