package icu.wuhui.voxlink.network;

public final class PunchStrategySelector {
   private static final int HARD_SYM_RELAY_CYCLE_THRESHOLD = 2;

   private PunchStrategySelector() {
   }

   public static PunchStrategy select(NatClass localNat, NatClass remoteNat, int cycle, boolean isLegacyPeer) {
      if (isLegacyPeer) {
         return PunchStrategy.DIRECT_ONLY;
      }

      if (localNat == null || remoteNat == null || localNat == NatClass.UNKNOWN || remoteNat == NatClass.UNKNOWN) {
         return PunchStrategy.DIRECT_ONLY;
      }

      if (localNat == NatClass.CONE && remoteNat == NatClass.HARD_SYM) {
         return cycle == 0 ? PunchStrategy.REVERSE_ONLY : PunchStrategy.REVERSE_THEN_FORWARD;
      }

      if (localNat == NatClass.CONE && remoteNat == NatClass.EASY_SYM) {
         return PunchStrategy.PARALLEL_FROM_START;
      }

      if ((localNat != NatClass.EASY_SYM || remoteNat != NatClass.HARD_SYM) && (localNat != NatClass.HARD_SYM || remoteNat != NatClass.EASY_SYM)) {
         if (localNat == NatClass.HARD_SYM && remoteNat == NatClass.HARD_SYM) {
            return cycle < 2 ? PunchStrategy.DIRECT_WITH_REVERSE_PARALLEL : PunchStrategy.RELAY_FALLBACK_FAST;
         } else {
            return !localNat.isSymmetric() && !remoteNat.isSymmetric() ? PunchStrategy.DIRECT_ONLY : PunchStrategy.DIRECT_WITH_REVERSE_PARALLEL;
         }
      } else {
         return PunchStrategy.REVERSE_FIRST;
      }
   }
}
