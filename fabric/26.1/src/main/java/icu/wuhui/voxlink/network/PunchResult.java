package icu.wuhui.voxlink.network;

import java.net.DatagramSocket;

//debounce 打洞结果值对象 含成功socket或失败诊断信息 供PunchFailureClassifier+PunchTuner使用
public final class PunchResult {
    public final DatagramSocket successSocket;
    public final int socketsTried;
    public final int socketsReceivedPunch;
    public final int socketsReceivedAck;
    public final int predictionDelta;
    public final long elapsedMs;
    public final boolean firewallDetected;
    public final PunchFailureClassifier.FailureReason reason;

    private PunchResult(DatagramSocket socket, int tried, int recvPunch, int recvAck,
                        int delta, long elapsed, boolean firewall, PunchFailureClassifier.FailureReason reason) {
        this.successSocket = socket;
        this.socketsTried = tried;
        this.socketsReceivedPunch = recvPunch;
        this.socketsReceivedAck = recvAck;
        this.predictionDelta = delta;
        this.elapsedMs = elapsed;
        this.firewallDetected = firewall;
        this.reason = reason;
    }

    public static PunchResult success(DatagramSocket socket, int tried, int recvPunch, int recvAck,
                                       int delta, long elapsed) {
        return new PunchResult(socket, tried, recvPunch, recvAck, delta, elapsed, false, null);
    }

    public static PunchResult failure(int tried, int recvPunch, int recvAck, int delta,
                                       long elapsed, boolean firewall) {
        return new PunchResult(null, tried, recvPunch, recvAck, delta, elapsed, firewall, null);
    }

    public boolean isSuccess() { return successSocket != null; }
    public DatagramSocket getSuccessSocket() { return successSocket; }

    public PunchResult withReason(PunchFailureClassifier.FailureReason newReason) {
        return new PunchResult(successSocket, socketsTried, socketsReceivedPunch,
                socketsReceivedAck, predictionDelta, elapsedMs, firewallDetected, newReason);
    }
}
