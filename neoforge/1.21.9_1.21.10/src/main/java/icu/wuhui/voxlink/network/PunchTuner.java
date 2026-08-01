package icu.wuhui.voxlink.network;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

//debounce 动态参数调节器 根据失败原因+cycle输出下轮PunchParams
public final class PunchTuner {
    private static final Logger LOGGER = LoggerFactory.getLogger("voxlink-punch");

    //debounce 调整上限 避免参数膨胀
    private static final int MAX_PORT_RANGE = 500;
    private static final int MAX_TIMEOUT_MS = 30000;
    private static final int MIN_SEND_INTERVAL_MS = 50;
    private static final int LATE_CYCLE_TIMEOUT_MS = 5000;
    private static final int ACK_RETRIES_ON_TIMEOUT = 3;
    private static final int PREDICTION_DELTA_THRESHOLD = 100;
    private static final int PORT_RANGE_MULTIPLIER = 2;
    private static final int TIMEOUT_INCREMENT_MS = 4000;
    private static final int SEND_INTERVAL_DIVISOR = 2;

    private PunchTuner() {}

    public static PunchParams nextParams(PunchProfile currentProfile,
                                          NatClass localNat, NatClass remoteNat,
                                          int cycle, int maxCycles,
                                          PunchFailureClassifier.FailureReason lastFailure,
                                          PunchResult lastResult) {
        PunchParams params = PunchParams.fromProfile(currentProfile);

        if (lastFailure == null) {
            // 首轮，用档位默认参数
            return applyLateCycleLimit(params, cycle, maxCycles);
        }

        switch (lastFailure) {
            case NO_RESPONSE:
                params.portRange = Math.min(params.portRange * PORT_RANGE_MULTIPLIER, MAX_PORT_RANGE);
                params.timeoutMs = Math.min(params.timeoutMs + TIMEOUT_INCREMENT_MS, MAX_TIMEOUT_MS);
                break;
            case PREDICTION_OFF:
                PunchProfile.switchToHardSym("prediction_off");
                params = PunchParams.fromProfile(PunchProfile.current());
                break;
            case RESPONSE_NO_ACK:
                params.sendInterval = Math.max(params.sendInterval / SEND_INTERVAL_DIVISOR, MIN_SEND_INTERVAL_MS);
                break;
            case FIREWALL_DETECTED:
                params.skipDirectPunch = true;
                break;
            case PARTIAL_SUCCESS:
                if (lastResult != null && lastResult.predictionDelta > 0) {
                    params.reuseSuccessfulSockets = true;
                    params.successfulPortRange = new int[]{lastResult.predictionDelta - 5, lastResult.predictionDelta + 5};
                }
                break;
            case ACK_TIMEOUT:
                params.ackRetries = ACK_RETRIES_ON_TIMEOUT;
                break;
        }

        //debounce 网络质量感知: 用elapsedMs近似RTT 高延迟时timeout加大 快速失败时portRange加大
        if (lastResult != null && lastResult.elapsedMs > 0) {
            if (lastResult.elapsedMs > 10000) {
                params.timeoutMs = Math.min(params.timeoutMs * 3 / 2, MAX_TIMEOUT_MS);
            } else if (lastResult.elapsedMs < 3000 && lastFailure == PunchFailureClassifier.FailureReason.NO_RESPONSE) {
                params.portRange = Math.min(params.portRange * PORT_RANGE_MULTIPLIER, MAX_PORT_RANGE);
            }
        }

        params = applyLateCycleLimit(params, cycle, maxCycles);
        LOGGER.info("[PunchTuner] cycle={} failure={} -> portRange={} timeout={} sendInterval={} ackRetries={} skipDirect={} reuse={}",
                cycle, lastFailure, params.portRange, params.timeoutMs, params.sendInterval,
                params.ackRetries, params.skipDirectPunch, params.reuseSuccessfulSockets);
        return params;
    }

    //debounce cycle后期强制timeout上限 避免总等待
    private static PunchParams applyLateCycleLimit(PunchParams params, int cycle, int maxCycles) {
        if (cycle >= maxCycles - 2) {
            if (params.timeoutMs > LATE_CYCLE_TIMEOUT_MS) {
                params.timeoutMs = LATE_CYCLE_TIMEOUT_MS;
            }
        }
        return params;
    }
}
