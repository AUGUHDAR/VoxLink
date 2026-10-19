package icu.wuhui.voxlink.room;

import icu.wuhui.voxlink.config.VoxLinkConfig;
import icu.wuhui.voxlink.network.NatClass;
import icu.wuhui.voxlink.network.PunchProfile;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import icu.wuhui.voxlink.network.UdpHolePuncher;
import icu.wuhui.voxlink.network.PunchFailureClassifier;
import icu.wuhui.voxlink.network.ProtocolNegotiator;
import icu.wuhui.voxlink.network.PunchParams;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.HashSet;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import icu.wuhui.voxlink.network.PunchAuth;
import icu.wuhui.voxlink.VoxLinkMod;

/** 打洞档位/模板: 参数选择与失败分类。(Phase4 从 ConnectionManager 拆出) */
public final class PunchProfileCtrl {
   private PunchProfileCtrl() {
   }

   static PunchProfile getActivePunchProfile(ConnectionManager cm) {

      return cm.punchProfile();

   }
   static void applyPunchTemplate(ConnectionManager cm, UdpHolePuncher puncher) {

      if (puncher != null) {

         puncher.setProfile(cm.punchProfile());

         puncher.setPunchParams(cm.activePunchParams);

         // punchAuthV1：双方声明能力时启用打洞控制面 MAC 认证；否则 null=旧线上格式

         puncher.setAuthKey(cm.activePunchAuthKey);

      }

   }
   static byte[] derivePunchAuthKey(ConnectionManager cm, RoomManager.RoomState state, String peerClientId, boolean peerIsHost) {

      try {

         if (state == null || state.roomInfo == null) {

            return null;

         }



         boolean peerSupports;

         if (peerIsHost) {

            peerSupports = ProtocolNegotiator.hostSupportsPunchAuth(state.roomInfo);

         } else {

            RoomInfo.PeerInfo peer = state.roomInfo.getPeer(peerClientId);

            peerSupports = ProtocolNegotiator.supportsPunchAuth(peer);

         }



         if (!peerSupports) {

            return null;

         }



         // 密钥材料=房间码+加入者ID（两端一致；token 两端不同不可用）

         String code = state.roomInfo.getCode();

         String joinerId = peerIsHost ? state.roomInfo.getClientId() : peerClientId;

         return PunchAuth.deriveDirectKey(code, joinerId);

      } catch (Exception e) {

         return null;

      }

   }
   static void setupPunchAuthForPeer(ConnectionManager cm, RoomManager.RoomState state, String peerClientId, boolean peerIsHost) {

      byte[] key = cm.derivePunchAuthKey(state, peerClientId, peerIsHost);

      cm.activePunchAuthKey = key;

      if (key != null) {

         VoxLinkMod.LOGGER.info("[PunchAuth] Auth mode armed for peer {} ({})", new Object[]{peerClientId != null ? peerClientId : "host", peerIsHost ? "isHost" : "isJoiner"});

      }

   }
   static void switchPunchProfile(ConnectionManager cm, PunchProfile target, String reason) {

      if (target != null && target != cm.activePunchProfile) {

         long now = System.currentTimeMillis();

         if (cm.lastProfileSwitchMs != 0L && now - cm.lastProfileSwitchMs < 20000L) {

            // 节流丢弃必须留痕：28 个会话日志里 recommendProfile 命中 108 次，
            // 却只有 54 次 Instance switch —— 差值全被这里静默 return 吞掉，
            // 于是"模板有没有生效"从日志上根本区分不出是判定不给力还是被节流挡住。
            VoxLinkMod.LOGGER.info(
               "[PunchProfile] switch dropped by 20s throttle: wanted {}, {}ms left in cooldown, staying on {}",
               new Object[]{target.name, 20000L - (now - cm.lastProfileSwitchMs), cm.activePunchProfile.name}
            );
            return;

         }



         cm.lastProfileSwitchMs = now;

         PunchProfile old = cm.activePunchProfile;

         cm.activePunchProfile = target;

         VoxLinkMod.LOGGER.info("[PunchProfile] Instance switch: {} -> {} reason: {}", old, target.name, reason);

      }

   }
   static boolean punchSkipDirect(ConnectionManager cm) {

      PunchParams p = cm.activePunchParams;

      return p != null && p.skipDirectPunch;

   }
   static void escalateProfileForRound(ConnectionManager cm, int round) {

      PunchProfile current = cm.punchProfile();

      if (current != PunchProfile.HARDSYM) {

         PunchProfile target;

         if (current == PunchProfile.AGGRESSIVE) {

            target = PunchProfile.HARDSYM;

         } else {

            target = PunchProfile.AGGRESSIVE;

         }



         cm.switchPunchProfile(target, "continuous_retry_round_" + round);

      }

   }
   static String getConnectionDifficultyKey(ConnectionManager cm) {

      NatClass l = cm.localNatClass;

      NatClass r = cm.remoteNatClass;

      // 有一方未知: 按已知侧最坏推断, 难度后由UI加(存疑)

      if (l == NatClass.UNKNOWN || r == NatClass.UNKNOWN) {

         NatClass known = l == NatClass.UNKNOWN ? r : l;

         if (known == NatClass.CONE) {

            return "voxlink.nat.diff_medium";

         } else if (known.isSymmetric()) {

            return "voxlink.nat.diff_hell";

         } else {

            return "voxlink.nat.diff_medium";

         }

      }



      if (l == NatClass.CONE && r == NatClass.CONE) {

         return "voxlink.nat.diff_easy";

      } else if (l == NatClass.HARD_SYM && r == NatClass.HARD_SYM) {

         return "voxlink.nat.diff_hell";

      } else if (l == NatClass.HARD_SYM || r == NatClass.HARD_SYM) {

         return "voxlink.nat.diff_hard";

      } else if (l.isSymmetric() && r.isSymmetric()) {

         return "voxlink.nat.diff_hard";

      } else if (l.isSymmetric() || r.isSymmetric()) {

         return "voxlink.nat.diff_medium";

      } else {

         return "voxlink.nat.diff_medium";

      }

   }
   static int calculatePortDelta(List<Integer> samples) {

      if (samples != null && samples.size() >= 2) {

         List<Integer> deltas = new ArrayList<>();



         for (int i = 1; i < samples.size(); i++) {

            deltas.add(samples.get(i) - samples.get(i - 1));

         }



         Collections.sort(deltas);

         int trim = deltas.size() / 4;

         List<Integer> trimmed = deltas.subList(trim, deltas.size() - trim);

         double ema = trimmed.get(0).intValue();

         double alpha = 0.4;



         for (int i = 1; i < trimmed.size(); i++) {

            ema += alpha * (trimmed.get(i).intValue() - ema);

         }



         int result = (int)Math.round(ema);

         return result > 0 ? result : 1;

      } else {

         return 1;

      }

   }
   static void recordPunchFailure(ConnectionManager cm, PunchFailureClassifier.FailureReason reason, int recvPunch, int recvAck) {
      VoxLinkMod.LOGGER.info(
         "[ConnectionManager] Punch failed reason={} recvPunch={} recvAck={}",
         new Object[]{reason, recvPunch, recvAck}
      );
      if (reason == PunchFailureClassifier.FailureReason.PREDICTION_OFF) {
                  int count = cm.sessionPredictionOffCount.incrementAndGet();

                  // 只在跨阈值瞬间打一条(此前 count>=CAP 每次都打, 实测单会话刷 350 条 50→400)
         if (count == ConnectionManager.PREDICTION_OFF_CAP) {
            VoxLinkMod.LOGGER.warn(
               "[UdpHolePuncher] PREDICTION_OFF cap reached ({}), stop direct punching this session (relay/TURN takes over)",
               count
            );
         }
      }
   }
   static boolean shouldPrefetchRelay(ConnectionManager cm, NatClass local, NatClass remote, RoomManager.RoomState state) {
      // 同CGNAT回环打洞命中率两极分化, 中继拓扑提前预热备胎
      boolean sameCgnat = state != null && state.roomInfo != null && state.roomInfo.isSameCgnat();
      return local.isSymmetric() || remote.isSymmetric() || sameCgnat;
   }
   static CompletableFuture<Void> prefetchRelayCandidates(ConnectionManager cm, RoomManager.RoomState state) {

      if (state != null && state.roomInfo != null) {

         String code = state.roomInfo.getCode();

         String token = state.roomInfo.getToken();

         return code != null && token != null

            ? cm.signalingClient

               .pollTopology(code, token, false, 0)

               .thenAccept(resp -> VoxLinkMod.LOGGER.info("[Connection] Layer2 relay pre-check topology done"))

               .exceptionally(e -> {

                  VoxLinkMod.LOGGER.warn("[Connection] Layer2 relay pre-check topology failed: {}", e.getMessage());

                  return null;

               })

            : CompletableFuture.completedFuture(null);

      } else {

         return CompletableFuture.completedFuture(null);

      }

   }
   static boolean sameIpFamily(String a, String b) {
      if (a == null || b == null) {
         return false;
      } else {
         return a.indexOf(58) >= 0 == b.indexOf(58) >= 0;
      }
   }
}
