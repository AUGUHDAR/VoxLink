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
      if (local == null || remote == null || local == UNKNOWN || remote == UNKNOWN) {
         return PunchProfile.AGGRESSIVE;
      } else if (local == HARD_SYM && remote == HARD_SYM) {
         return PunchProfile.HARDSYM;
      } else if (local == EASY_SYM && remote == EASY_SYM) {
         return PunchProfile.EASY_SYM_DUAL;
      } else if ((local != HARD_SYM || remote != EASY_SYM) && (local != EASY_SYM || remote != HARD_SYM)) {
         return local != HARD_SYM && remote != HARD_SYM ? PunchProfile.DEFAULT : PunchProfile.AGGRESSIVE;
      } else {
         return PunchProfile.AGGRESSIVE;
      }
   }
}
