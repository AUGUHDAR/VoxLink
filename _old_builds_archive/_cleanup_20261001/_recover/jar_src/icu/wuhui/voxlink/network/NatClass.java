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
      } else {
         switch (natType) {
            case FULL_CONE:
            case RESTRICTED_CONE:
            case PORT_RESTRICTED_CONE:
               return CONE;
            case SYMMETRIC_EASY_INC:
            case SYMMETRIC_EASY_DEC:
            case EASY_SYM_INC:
            case EASY_SYM_DEC:
               return EASY_SYM;
            case SYMMETRIC:
            case HARD_SYM:
               return HARD_SYM;
            default:
               return UNKNOWN;
         }
      }
   }

   public static PunchProfile recommendProfile(NatClass local, NatClass remote) {
      return recommendProfile(local, remote, ScenarioTier.Tier.NORMAL);
   }

   public static PunchProfile recommendProfile(NatClass local, NatClass remote, ScenarioTier.Tier tier) {
      if (local == null || remote == null || local == UNKNOWN || remote == UNKNOWN) {
         return PunchProfile.AGGRESSIVE;
      } else if (local == HARD_SYM && remote == HARD_SYM) {
         return PunchProfile.HARDSYM;
      } else if ((local != EASY_SYM || remote != HARD_SYM) && (local != HARD_SYM || remote != EASY_SYM)) {
         if (local == EASY_SYM && remote == EASY_SYM) {
            return PunchProfile.EASY_SYM_DUAL;
         } else if (local == CONE && remote == CONE) {
            return PunchProfile.FAST_LANE;
         } else if ((local != CONE || remote != EASY_SYM) && (local != EASY_SYM || remote != CONE)) {
            return (local != CONE || remote != HARD_SYM) && (local != HARD_SYM || remote != CONE) ? PunchProfile.DEFAULT : PunchProfile.AGGRESSIVE;
         } else {
            return PunchProfile.V100;
         }
      } else {
         return PunchProfile.HARDSYM;
      }
   }
}
