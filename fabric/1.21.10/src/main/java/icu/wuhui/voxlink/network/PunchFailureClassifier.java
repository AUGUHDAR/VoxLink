package icu.wuhui.voxlink.network;

//debounce 打洞失败原因分类器 根据PunchResult字段判定6种失败原因
public final class PunchFailureClassifier {
    private PunchFailureClassifier() {}

    public enum FailureReason {
        NO_RESPONSE,        // 对端完全无回包
        RESPONSE_NO_ACK,    // 收到PUNCH但ACK丢失
        ACK_TIMEOUT,        // ACK发出但本端没收到对端后续数据
        PARTIAL_SUCCESS,    // 部分socket成功部分失败
        PREDICTION_OFF,     // 端口预测偏差过大
        FIREWALL_DETECTED   // 防火墙阻断
    }

    //debounce 分类入口 由ConnectionManager在punch失败后调用
    public static FailureReason classify(PunchResult result) {
        if (result == null) return FailureReason.NO_RESPONSE;
        if (result.predictionDelta > 100) return FailureReason.PREDICTION_OFF;
        if (result.socketsReceivedPunch == 0 && result.socketsReceivedAck == 0) {
            if (result.firewallDetected) return FailureReason.FIREWALL_DETECTED;
            return FailureReason.NO_RESPONSE;
        }
        if (result.socketsReceivedPunch > 0 && result.socketsReceivedAck == 0) {
            return FailureReason.RESPONSE_NO_ACK;
        }
        if (result.socketsReceivedAck > 0 && result.successSocket == null) {
            return FailureReason.ACK_TIMEOUT;
        }
        if (result.socketsReceivedPunch > 0 && result.socketsReceivedPunch < result.socketsTried) {
            return FailureReason.PARTIAL_SUCCESS;
        }
        return FailureReason.NO_RESPONSE;
    }
}
