package icu.wuhui.voxlink.network;

/**
 * 打洞策略选择器（调度层）：按双方 NAT 组合 + cycle 选策略。
 * 完整按 (localNat, remoteNat) 排列矩阵派发，每个组合都有归宿。
 * UNKNOWN 不"死等DIRECT_ONLY"，而是正+反并行并逐步扩窗（常用实现做法）。
 */
public final class PunchStrategySelector {
   private PunchStrategySelector() {
   }

   public static PunchStrategy select(NatClass localNat, NatClass remoteNat, int cycle, boolean isLegacyPeer) {
      // 老版本对端强制 DIRECT_ONLY，与历史一致
      if (isLegacyPeer) {
         return PunchStrategy.DIRECT_ONLY;
      }
      if (localNat == null || remoteNat == null) {
         return PunchStrategy.DIRECT_WITH_REVERSE_PARALLEL;
      }

      // 任一侧 UNKNOWN：不把它当死锥，正+反并行、让 PunchTuner 逐步扩窗；
      // 若对端已知对称，则 unknown 侧按对称陪打（反向优先）。
      if (localNat == NatClass.UNKNOWN || remoteNat == NatClass.UNKNOWN) {
         NatClass known = localNat == NatClass.UNKNOWN ? remoteNat : localNat;
         if (known.isSymmetric()) {
            return PunchStrategy.REVERSE_FIRST;
         }
         return PunchStrategy.DIRECT_WITH_REVERSE_PARALLEL;
      }

      // 已知双方
      // cone vs symmetric(EASY/HARD): forward 必败, cone稳定端口是唯一稳赢面 -> reverse-first
      if (localNat == NatClass.CONE && remoteNat == NatClass.EASY_SYM
         || localNat == NatClass.EASY_SYM && remoteNat == NatClass.CONE) {
         return PunchStrategy.REVERSE_FIRST;
      }
      if (localNat == NatClass.CONE && remoteNat == NatClass.HARD_SYM
         || localNat == NatClass.HARD_SYM && remoteNat == NatClass.CONE) {
         return cycle == 0 ? PunchStrategy.REVERSE_ONLY : PunchStrategy.REVERSE_THEN_FORWARD;
      }
      // 异对称(EASY×HARD / HARD×EASY): 难侧扫易侧窄带宽, reverse优先
      if (localNat == NatClass.EASY_SYM && remoteNat == NatClass.HARD_SYM
         || localNat == NatClass.HARD_SYM && remoteNat == NatClass.EASY_SYM) {
         return PunchStrategy.REVERSE_FIRST;
      }
      // 双对称: 正反并行死磕P2P(不自动转中继)
      if (localNat == NatClass.HARD_SYM && remoteNat == NatClass.HARD_SYM) {
         return PunchStrategy.DIRECT_WITH_REVERSE_PARALLEL;
      }
      if (localNat == NatClass.EASY_SYM && remoteNat == NatClass.EASY_SYM) {
         return PunchStrategy.DIRECT_WITH_REVERSE_PARALLEL;
      }
      // 双锥: 纯正向直达
      if (localNat == NatClass.CONE && remoteNat == NatClass.CONE) {
         return PunchStrategy.DIRECT_ONLY;
      }
      return PunchStrategy.DIRECT_WITH_REVERSE_PARALLEL;
   }
}