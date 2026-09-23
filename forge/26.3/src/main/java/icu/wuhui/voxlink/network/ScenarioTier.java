package icu.wuhui.voxlink.network;

/**
 * 场景分档器：用综合信号把连接场景分为硬/普通档，供调度层选模板与打法。
 * 只做场景分类，不改变任何模板/功能。纯 Java，无 MC API。
 */
public final class ScenarioTier {
   public enum Tier {
      // 双方都对称(CGNAT/移动4G5G)：打洞最难的档
      HARD_DUAL_SYM,
      // 单侧对称且伴有硬信号（可达低/端口漂移/预测落空复现）
      HARD_ONE_SYM,
      // 普通场景（双锥/easy），走轻量增强模板
      NORMAL
   }

   private ScenarioTier() {
   }

   /**
    * 综合信号判定场景档位。不靠单一信号定死模板：
    * - 双方对称 → 一定是硬双对称档（保守不降档）；
    * - 单侧对称 → 需再叠低可达/端口漂移/预测落空等硬信号才升为硬单对称档；
    * - 否则 → 普通档。
    */
   public static Tier classify(
      boolean localSymmetric,
      boolean remoteSymmetric,
      boolean lowReachable,
      boolean portDrift,
      boolean recurringPredictionOff
   ) {
      if (localSymmetric && remoteSymmetric) {
         return Tier.HARD_DUAL_SYM;
      }
      // 低可达/端口漂移/预测落空：即使两侧 NAT 标签看似锥或未知，也是硬 CGNAT 信号，按硬档处理
      if (lowReachable || portDrift || recurringPredictionOff) {
         return Tier.HARD_ONE_SYM;
      }
      if (localSymmetric || remoteSymmetric) {
         return Tier.HARD_ONE_SYM;
      }
      return Tier.NORMAL;
   }

   public static boolean isHard(Tier tier) {
      return tier == Tier.HARD_DUAL_SYM || tier == Tier.HARD_ONE_SYM;
   }

   public static String key(Tier tier) {
      return tier == null ? "normal" : tier.name().toLowerCase();
   }
}