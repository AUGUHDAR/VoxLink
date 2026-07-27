package icu.wuhui.voxlink.network;

//debounce NAT分类枚举 用于智能打洞档位矩阵
public enum NatClass {
    UNKNOWN,
    CONE,
    EASY_SYM,
    HARD_SYM;

    public boolean isSymmetric() {
        return this == EASY_SYM || this == HARD_SYM;
    }

    //debounce 从StunProbe.NatType映射 按实际枚举名switch
    public static NatClass fromStunProbeResult(StunProbe.NatType natType) {
        if (natType == null) {
            return UNKNOWN;
        }
        switch (natType) {
            case FULL_CONE:
            case RESTRICTED_CONE:
            case PORT_RESTRICTED_CONE:
                return CONE;
            case SYMMETRIC_EASY_INC:
            case SYMMETRIC_EASY_DEC:
                return EASY_SYM;
            case SYMMETRIC:
                return HARD_SYM;
            default:
                return UNKNOWN;
        }
    }

    //debounce 7种NAT组合矩阵 决定推荐档位
    public static PunchProfile recommendProfile(NatClass local, NatClass remote) {
        if (local == null || remote == null || local == UNKNOWN || remote == UNKNOWN) {
            return PunchProfile.AGGRESSIVE;
        }
        if (local == HARD_SYM && remote == HARD_SYM) {
            return PunchProfile.HARDSYM;
        }
        if (local == EASY_SYM && remote == EASY_SYM) {
            return PunchProfile.EASY_SYM_DUAL;
        }
        if ((local == HARD_SYM && remote == EASY_SYM) || (local == EASY_SYM && remote == HARD_SYM)) {
            return PunchProfile.AGGRESSIVE;
        }
        if (local == HARD_SYM || remote == HARD_SYM) {
            return PunchProfile.AGGRESSIVE;
        }
        //debounce Cone×Cone或EasySym×Cone
        return PunchProfile.DEFAULT;
    }
}
