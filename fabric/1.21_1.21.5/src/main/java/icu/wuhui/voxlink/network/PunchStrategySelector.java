package icu.wuhui.voxlink.network;

//debounce 策略选择器 按双方NAT组合+cycle+老版本标志选4种策略
public final class PunchStrategySelector {
    //debounce HardSym×HardSym切relay的cycle阈值
    private static final int HARD_SYM_RELAY_CYCLE_THRESHOLD = 2;

    private PunchStrategySelector() {}

    public static PunchStrategy select(NatClass localNat, NatClass remoteNat, int cycle, boolean isLegacyPeer) {
        //debounce 老版本对端强制DIRECT_ONLY 与1.0.7一致
        if (isLegacyPeer) {
            return PunchStrategy.DIRECT_ONLY;
        }

        //debounce 任一未知 用AGGRESSIVE+DIRECT_ONLY保守首轮
        if (localNat == null || remoteNat == null
                || localNat == NatClass.UNKNOWN || remoteNat == NatClass.UNKNOWN) {
            return PunchStrategy.DIRECT_ONLY;
        }

        //debounce EasySym×HardSym或反向 逆向更可能成功
        if ((localNat == NatClass.EASY_SYM && remoteNat == NatClass.HARD_SYM)
                || (localNat == NatClass.HARD_SYM && remoteNat == NatClass.EASY_SYM)) {
            return PunchStrategy.REVERSE_FIRST;
        }

        //debounce HardSym×HardSym cycle<2并行 2+切relay
        if (localNat == NatClass.HARD_SYM && remoteNat == NatClass.HARD_SYM) {
            if (cycle < HARD_SYM_RELAY_CYCLE_THRESHOLD) {
                return PunchStrategy.DIRECT_WITH_REVERSE_PARALLEL;
            }
            return PunchStrategy.RELAY_FALLBACK_FAST;
        }

        //debounce Sym×Cone 正向为主 cycle 1+并行逆向
        if (localNat.isSymmetric() || remoteNat.isSymmetric()) {
            return PunchStrategy.DIRECT_WITH_REVERSE_PARALLEL;
        }

        //debounce Cone×Cone 纯正向
        return PunchStrategy.DIRECT_ONLY;
    }
}
