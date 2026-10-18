package icu.wuhui.voxlink.network;

public final class PunchStrategySelector {
   private PunchStrategySelector() {
   }

   public static PunchStrategy select(NatClass localNat, NatClass remoteNat, int cycle, boolean isLegacyPeer) {
      if (isLegacyPeer) {
         return PunchStrategy.DIRECT_ONLY;
      } else if (localNat == null || remoteNat == null) {
         return PunchStrategy.DIRECT_WITH_REVERSE_PARALLEL;
      } else if (localNat != NatClass.UNKNOWN && remoteNat != NatClass.UNKNOWN) {
         if ((localNat != NatClass.CONE || remoteNat != NatClass.EASY_SYM) && (localNat != NatClass.EASY_SYM || remoteNat != NatClass.CONE)) {
            if ((localNat != NatClass.CONE || remoteNat != NatClass.HARD_SYM) && (localNat != NatClass.HARD_SYM || remoteNat != NatClass.CONE)) {
               if ((localNat != NatClass.EASY_SYM || remoteNat != NatClass.HARD_SYM) && (localNat != NatClass.HARD_SYM || remoteNat != NatClass.EASY_SYM)) {
                  if (localNat == NatClass.HARD_SYM && remoteNat == NatClass.HARD_SYM) {
                     return PunchStrategy.DIRECT_WITH_REVERSE_PARALLEL;
                  } else if (localNat == NatClass.EASY_SYM && remoteNat == NatClass.EASY_SYM) {
                     return PunchStrategy.DIRECT_WITH_REVERSE_PARALLEL;
                  } else {
                     return localNat == NatClass.CONE && remoteNat == NatClass.CONE ? PunchStrategy.DIRECT_ONLY : PunchStrategy.DIRECT_WITH_REVERSE_PARALLEL;
                  }
               } else {
                  return PunchStrategy.REVERSE_FIRST;
               }
            } else {
               return cycle == 0 ? PunchStrategy.REVERSE_ONLY : PunchStrategy.REVERSE_THEN_FORWARD;
            }
         } else {
            return PunchStrategy.REVERSE_FIRST;
         }
      } else {
         NatClass known = localNat == NatClass.UNKNOWN ? remoteNat : localNat;
         return known.isSymmetric() ? PunchStrategy.REVERSE_FIRST : PunchStrategy.DIRECT_WITH_REVERSE_PARALLEL;
      }
   }
}
