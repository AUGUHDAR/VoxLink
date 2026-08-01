package icu.wuhui.voxlink.network;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

//debounce 打洞参数档位: DEFAULT适合大部分场景(8s快失败快切换), AGGRESSIVE适合极端硬对称NAT(20s长超时100端口范围)
public final class PunchProfile {
    private static final Logger LOGGER = LoggerFactory.getLogger("voxlink-punch");

    public final String name;
    public final int punchTimeoutMs;
    public final int firewallDetectCycles;
    public final int portPredictionMaxRange;
    public final int[] progressiveRanges;
    public final int cyclesPerRange;
    public final int easySymDualSocketCount;
    public final int easySymDualPortRange;

    private PunchProfile(String name, int punchTimeoutMs, int firewallDetectCycles,
                         int portPredictionMaxRange, int[] progressiveRanges, int cyclesPerRange,
                         int easySymDualSocketCount, int easySymDualPortRange) {
        this.name = name;
        this.punchTimeoutMs = punchTimeoutMs;
        this.firewallDetectCycles = firewallDetectCycles;
        this.portPredictionMaxRange = portPredictionMaxRange;
        this.progressiveRanges = progressiveRanges;
        this.cyclesPerRange = cyclesPerRange;
        this.easySymDualSocketCount = easySymDualSocketCount;
        this.easySymDualPortRange = easySymDualPortRange;
    }

    //debounce DEFAULT=1.0.7当前参数 大部分场景快速失败快速切换
    public static final PunchProfile DEFAULT = new PunchProfile(
            "DEFAULT",
            8000,
            20,
            20,
            new int[]{4, 8, 15, 20},
            2,
            25,
            20
    );

    //debounce AGGRESSIVE=1.0.1风格参数 极端硬对称NAT场景给足映射建立时间
    public static final PunchProfile AGGRESSIVE = new PunchProfile(
            "AGGRESSIVE",
            20000,
            50,
            100,
            new int[]{10, 25, 50, 75, 100},
            2,
            25,
            20
    );

    // HardSym×HardSym 专用: 指数扩展 range 20→50→100→200→500, 避免全 65535 端口扫导致流量暴涨/被运营商误判DDoS
    // 对齐 EasyTier 的 max_k2 衰减思想, 但限制最大 range=500 (约覆盖 1000 端口), 在成功率和流量间取平衡
    public static final PunchProfile HARDSYM = new PunchProfile(
            "HARDSYM",
            25000,
            60,
            500,
            new int[]{20, 50, 100, 200, 500},
            2,
            25,
            20
    );

    //debounce EASY_SYM_DUAL=EasySym×EasySym中间档 侧重端口预测 12s/±50
    public static final PunchProfile EASY_SYM_DUAL = new PunchProfile(
            "EASY_SYM_DUAL",
            12000,
            30,
            50,
            new int[]{5, 10, 20, 30, 50},
            2,
            25,
            50
    );

    //debounce 当前激活档位 默认DEFAULT 由ConnectionManager根据场景切换
    private static volatile PunchProfile current = DEFAULT;
    private static volatile String switchReason = "initial";

    public static PunchProfile current() {
        return current;
    }

    public static boolean isAggressive() {
        return current == AGGRESSIVE;
    }

    public static void switchTo(PunchProfile target, String reason) {
        if (target == null || target == current) return;
        PunchProfile old = current;
        current = target;
        switchReason = reason;
        LOGGER.info("[PunchProfile] Switch: {} -> {} reason: {}", old.name, target.name, reason);
    }

    public static void switchToAggressive(String reason) {
        switchTo(AGGRESSIVE, reason);
    }

    public static void switchToHardSym(String reason) {
        switchTo(HARDSYM, reason);
    }

    public static void switchToEasySymDual(String reason) {
        switchTo(EASY_SYM_DUAL, reason);
    }

    public static void switchToDefault(String reason) {
        switchTo(DEFAULT, reason);
    }

    public static String describe() {
        return current.name + "(timeout=" + current.punchTimeoutMs
                + "ms, cycles=" + current.firewallDetectCycles
                + ", range=" + current.portPredictionMaxRange + ")";
    }
}
