package icu.wuhui.voxlink.network;

public final class ScenarioTier {
   private ScenarioTier() {
   }

   public static ScenarioTier.Tier classify(
      boolean localSymmetric, boolean remoteSymmetric, boolean lowReachable, boolean portDrift, boolean recurringPredictionOff
   ) {
      if (localSymmetric && remoteSymmetric) {
         return ScenarioTier.Tier.HARD_DUAL_SYM;
      } else if (lowReachable || portDrift || recurringPredictionOff) {
         return ScenarioTier.Tier.HARD_ONE_SYM;
      } else {
         return !localSymmetric && !remoteSymmetric ? ScenarioTier.Tier.NORMAL : ScenarioTier.Tier.HARD_ONE_SYM;
      }
   }

   public static boolean isHard(ScenarioTier.Tier tier) {
      return tier == ScenarioTier.Tier.HARD_DUAL_SYM || tier == ScenarioTier.Tier.HARD_ONE_SYM;
   }

   public static String key(ScenarioTier.Tier tier) {
      return tier == null ? "normal" : tier.name().toLowerCase();
   }

   public static enum Tier {
      HARD_DUAL_SYM,
      HARD_ONE_SYM,
      NORMAL;
   }
}
