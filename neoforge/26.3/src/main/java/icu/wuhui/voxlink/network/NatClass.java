package icu.wuhui.voxlink.network;

public enum NatClass {
   UNKNOWN,
   CONE,
   EASY_SYM,
   HARD_SYM;

   public boolean isSymmetric() {
      return this == EASY_SYM || this == HARD_SYM;
   }

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

   public static PunchProfile recommendProfile(NatClass local, NatClass remote) {
      return recommendProfile(local, remote, ScenarioTier.Tier.NORMAL);
   }

   public static PunchProfile recommendProfile(NatClass local, NatClass remote, ScenarioTier.Tier tier) {
      // 纯(localNat,remoteNat)排列矩阵: 不再按"硬档→V100"一刀切, 每个组合都有明确归宿。
      // 双对称统一用宽扫对称模板(难侧扫易侧窄带宽破局, 不转中继/仅手动中继)。
      if (local == null || remote == null || local == UNKNOWN || remote == UNKNOWN) {
         return PunchProfile.AGGRESSIVE;
      } else if (local == HARD_SYM && remote == HARD_SYM) {
         return PunchProfile.HARDSYM;
      } else if (local == EASY_SYM && remote == HARD_SYM || local == HARD_SYM && remote == EASY_SYM) {
         // 异对称(EASY×HARD): 难侧扫易侧上报端口带宽, 宽扫对称模板
         return PunchProfile.HARDSYM;
      } else if (local == EASY_SYM && remote == EASY_SYM) {
         return PunchProfile.EASY_SYM_DUAL;
      } else if (local == CONE && remote == CONE) {
         return PunchProfile.FAST_LANE;
      } else if (local == CONE && remote == EASY_SYM || local == EASY_SYM && remote == CONE) {
         // 锥×易对称: 锥侧稳定端口, 对称侧反扫锥, 用V100高密度自洽
         return PunchProfile.V100;
      } else if (local == CONE && remote == HARD_SYM || local == HARD_SYM && remote == CONE) {
         return PunchProfile.AGGRESSIVE;
      } else {
         return PunchProfile.DEFAULT;
      }
   }
}
