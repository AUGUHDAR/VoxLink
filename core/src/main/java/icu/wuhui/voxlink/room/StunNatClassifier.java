package icu.wuhui.voxlink.room;

import icu.wuhui.voxlink.network.NatClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import icu.wuhui.voxlink.network.StunProbe;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/** STUN/NAT 分类: 探测结果持有 + 本地/远端 NAT 判定。(Phase4 从 ConnectionManager 拆出) */
public final class StunNatClassifier {
   private static final Logger LOG = LoggerFactory.getLogger("voxlink-stunnat");
   private volatile StunProbe.ProbeResult stunProbeResult;
   private final AtomicReference<CompletableFuture<StunProbe.ProbeResult>> stunProbeFutureRef = new AtomicReference<>();
   boolean remoteNatMismatchLogged;

   static List<String> stunUrlsProbedFirst(ConnectionManager cm) {
      List<String> urls = new ArrayList<>(StunDetector.getAllStunUrls());
      StunProbe.ProbeResult probe = cm.stunProbeResult;
      if (probe != null && probe.reachableStunUrls != null) {
         for (String u : probe.reachableStunUrls) {
            urls.remove(u);
            urls.add(0, u);
         }
      }
      return urls;
   }

   static StunProbe.ProbeResult getStunProbeResult(ConnectionManager cm) {

      return cm.stunProbeResult;

   }

   static void setStunProbeResult(ConnectionManager cm, StunProbe.ProbeResult result) {

      cm.stunProbeResult = result;

   }

   static AtomicReference<CompletableFuture<StunProbe.ProbeResult>> getStunProbeFutureRef(ConnectionManager cm) {

      return cm.stunProbeFutureRef;

   }

   static int natRank(NatClass c) {
      switch (c) {
         case CONE:
            return 1;
         case EASY_SYM:
            return 2;
         case HARD_SYM:
            return 3;
         default:
            return 0;
      }
   }

   static NatClass classifyLocalNat(ConnectionManager cm) {

      return cm.stunProbeResult != null && cm.stunProbeResult.natType != null

         ? NatClass.fromStunProbeResult(cm.stunProbeResult.natType)

         : NatClass.UNKNOWN;

   }

   static NatClass classifyRemoteNat(ConnectionManager cm, RoomManager.RoomState state) {

      if (state != null && state.roomInfo != null) {

         boolean hostSym = state.roomInfo.isHostSymmetric();

         boolean hostEasySym = state.roomInfo.isHostEasySym();

         if (hostSym) {

            return hostEasySym ? NatClass.EASY_SYM : NatClass.HARD_SYM;

         } else {

            return NatClass.UNKNOWN;

         }

      } else {

         return NatClass.UNKNOWN;

      }

   }

   static NatClass reportedNatClass(String raw) {
      if (raw != null && !raw.isEmpty()) {
         String k = raw.toLowerCase(java.util.Locale.ROOT);
         if (k.endsWith("_cone")) {
            return NatClass.CONE;
         } else if (k.startsWith("symmetric_easy")) {
            return NatClass.EASY_SYM;
         } else if (k.equals("symmetric")) {
            return NatClass.HARD_SYM;
         } else {
            return NatClass.UNKNOWN;
         }
      } else {
         return NatClass.UNKNOWN;
      }
   }

   static List<String> selectReachableStunGroup(ConnectionManager cm, int cycle) {

      if (cm.stunProbeResult != null && !cm.stunProbeResult.reachableStunUrls.isEmpty()) {

         List<String> reachable = cm.stunProbeResult.reachableStunUrls;

         int index = cycle % reachable.size();

         return List.of(reachable.get(index));

      } else {

         return StunDetector.getStunGroup(cycle % StunDetector.getStunGroupCount());

      }

   }

   static String filterAddressFamilyCandidate(ConnectionManager cm, String ip, int port, String tag) {

      if (ip == null || ip.isEmpty()) {

         return ip;

      }

      if (ip.indexOf(':') >= 0) {

         if (!StunDetector.verifyIPv6Connectivity()) {

            LOG
               .warn(
                  "[AddrFilter] 忽略不可达 IPv6 候选 {}:{} ({})：本端无全局IPv6 / dropped unreachable IPv6 candidate (no local global IPv6)",
                  new Object[]{ip, port, tag}
               );

            return null;

         }

         return ip;

      }

      if (ip.startsWith("127.") || ip.startsWith("0.") || "localhost".equals(ip)) {

         LOG.warn("[AddrFilter] 忽略不可达候选 {}:{} ({})：环回地址 / dropped loopback candidate", ip, port, tag);

         return null;

      }

      return ip;

   }

   static boolean isDeadNatCombo(ConnectionManager cm, RoomManager.RoomState state) {
      boolean localSym = cm.stunProbeResult != null && cm.stunProbeResult.natType.isSymmetric();
      boolean remoteSym = state != null && state.roomInfo != null && state.roomInfo.isHostSymmetric();
      return localSym && remoteSym;
   }

   static boolean isSamePublicIpAsHost(ConnectionManager cm, RoomManager.RoomState state) {
      if (state == null || state.roomInfo == null) {
         return false;
      }
      String mine = state.roomInfo.getMyMappedIp();
      String host = state.roomInfo.getHostMappedIp();
      return mine != null && host != null && mine.equals(host);
   }

   static NatClass classifyRemoteNatLogged(ConnectionManager cm, RoomManager.RoomState state) {
      NatClass observed = cm.classifyRemoteNat(state);
      if (state != null && state.roomInfo != null) {
         NatClass reported = reportedNatClass(state.roomInfo.getNatType());
         if (reported != observed && cm.remoteNatMismatchLogged.add(reported + "->" + observed)) {
            // 同一 (自报->观测) 组合本局只报一次：classifyRemoteNat 在探测/轮询/反向打洞多处被调
            LOG.info(
               "[NatClass] remote report differs: reported={} (raw={}) observed={} — 模板选择仍按观测",
               new Object[]{reported, state.roomInfo.getNatType(), observed}
            );
         }
      }

      return observed;
   }
}
