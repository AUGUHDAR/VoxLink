package icu.wuhui.voxlink.network;

//debounce 单次打洞参数 可被PunchTuner动态覆盖 不修改全局PunchProfile
public final class PunchParams {
    public int portRange;
    public int timeoutMs;
    public int sendInterval;
    public int ackRetries;
    public boolean skipDirectPunch;
    public boolean reuseSuccessfulSockets;
    public int[] successfulPortRange;

    public PunchParams(int portRange, int timeoutMs, int sendInterval, int ackRetries,
                       boolean skipDirectPunch, boolean reuseSuccessfulSockets, int[] successfulPortRange) {
        this.portRange = portRange;
        this.timeoutMs = timeoutMs;
        this.sendInterval = sendInterval;
        this.ackRetries = ackRetries;
        this.skipDirectPunch = skipDirectPunch;
        this.reuseSuccessfulSockets = reuseSuccessfulSockets;
        this.successfulPortRange = successfulPortRange;
    }

    public static PunchParams fromProfile(PunchProfile profile) {
        return new PunchParams(
                profile.portPredictionMaxRange,
                profile.punchTimeoutMs,
                200,    // PUNCH_INTERVAL_MS 默认值 与UdpHolePuncher.PUNCH_INTERVAL_MS对齐
                1,
                false,
                false,
                null
        );
    }
}
