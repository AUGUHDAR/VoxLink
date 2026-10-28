package icu.wuhui.voxlink.room;

import com.google.gson.JsonObject;

import icu.wuhui.voxlink.VoxLinkMod;

import icu.wuhui.voxlink.network.NatClass;

import icu.wuhui.voxlink.platform.Text;

import net.minecraft.network.chat.Component;

import icu.wuhui.voxlink.platform.ChatSink;

import java.util.List;

import java.util.concurrent.TimeUnit;

import icu.wuhui.voxlink.network.PunchProfile;
import icu.wuhui.voxlink.terracotta.RoomCodeRouter;
import icu.wuhui.voxlink.terracotta.TerracottaManager;
import icu.wuhui.voxlink.terracotta.TerracottaBinary;
import icu.wuhui.voxlink.network.PunchTuner;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import icu.wuhui.voxlink.network.StunProbe;
import icu.wuhui.voxlink.network.ProtocolNegotiator;
import icu.wuhui.voxlink.network.PortPredictor;
import java.util.concurrent.atomic.AtomicInteger;
import icu.wuhui.voxlink.network.PunchStrategy;
import icu.wuhui.voxlink.network.PunchStrategySelector;
import icu.wuhui.voxlink.network.P2PBridge;
import icu.wuhui.voxlink.network.PunchFailureClassifier;
import java.util.ArrayList;
import java.util.Set;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;

import icu.wuhui.voxlink.network.PunchParams;

import icu.wuhui.voxlink.network.PunchResult;

import icu.wuhui.voxlink.network.ScenarioTier;

import icu.wuhui.voxlink.network.UdpHolePuncher;

import icu.wuhui.voxlink.network.SignalingClient;

import icu.wuhui.voxlink.network.ConnectionFallback;

import java.util.concurrent.ScheduledFuture;

import java.util.concurrent.CompletableFuture;

import java.util.concurrent.atomic.AtomicBoolean;

import java.util.concurrent.atomic.AtomicReference;

import java.util.function.BiConsumer;

/** 连接周期导演: 多轮推进/竞速/dual race/生日打洞编排。(Phase4 从 ConnectionManager 拆出) */
public final class PunchCycleDirector {
   private PunchCycleDirector() {
   }

   static PunchProfile punchProfile(ConnectionManager cm) {

      PunchProfile p = cm.activePunchProfile;

      return p != null ? p : PunchProfile.DEFAULT;

   }
   static void runConnectionCycle(ConnectionManager cm, 

      RoomManager.RoomState state, String from, String hostIpv6, String hostIp, int hostPort, String hostMappedIp, int hostMappedPort, int cycle

   ) {

      cm.savedConnectionState = state;

      cm.savedConnectionFrom = from != null ? from : "";

      cm.savedConnectionHostIpv6 = hostIpv6;

      cm.savedConnectionHostIp = hostIp;

      cm.savedConnectionHostPort = hostPort;

      cm.savedConnectionHostMappedIp = hostMappedIp;

      cm.savedConnectionHostMappedPort = hostMappedPort;

      if (cm.connectionWon.get()) {

         VoxLinkMod.LOGGER.info("[Connection] Connected, skip cycle {}", cycle + 1);

      } else {

         // PREDICTION_OFF 封顶快速失败: birthday attack 风暴下避免无限空转 + 日志被刷爆。
         // 与 ConnectionManager.ZERO_RECV_FINAL_ROUND_LIMIT 正交, 此处用相同终态结论确保对端停手 + 进入 fallback。
         if (cm.sessionPredictionOffCount.get() >= ConnectionManager.PREDICTION_OFF_CAP && !cm.sessionPunchRecvEver.get()) {
            VoxLinkMod.LOGGER.warn("[UdpHolePuncher] PREDICTION_OFF cap reached ({}), abort punch", cm.sessionPredictionOffCount.get());
            ConnectionState.transitionTo(ConnectionState.FAILED, "PREDICTION_OFF 封顶");
            cm.showConnectFailed(state, "voxlink.connection.max_cycles_exceeded");
         } else {

         int maxCycles = cm.getEffectiveMaxCycles();

         if (cycle >= maxCycles) {

            if (!cm.connectionWon.get()) {

               if (cm.continuousRetryRound.get() >= ConnectionManager.ZERO_RECV_FINAL_ROUND_LIMIT && !cm.sessionPunchRecvEver.get()) {

               // 持续重试已打满且全会话从未收到对端任何UDP包：给出终态结论并让对端也停手(WB4V72/MTNFW4)

               cm.declareZeroRecvFinalFailure(state);

            } else if (cm.shouldContinuousRetry(state)) {

                  int round = cm.continuousRetryRound.incrementAndGet();

                  cm.escalateProfileForRound(round);

                  VoxLinkMod.LOGGER

                     .info(

                        "[Connection] Cycle {} done, both support persistent retry (round={}, level={}), reset cycle from 0",

                        new Object[]{maxCycles, round, cm.punchProfile().describeInstance()}

                     );

                  ConnectionState.transitionTo(ConnectionState.STUN_PROBE, "持续重试 round " + round);

                  cm.connectionStartTimeMs = System.currentTimeMillis();

                  if (cm.connectionTimeoutFuture != null) {

                     cm.connectionTimeoutFuture.cancel(false);

                     cm.connectionTimeoutFuture = null;

                  }



                  state.roomInfo.setConnectionMode(Text.translatable("voxlink.connection.retry_round", new Object[]{round}));

                  cm.tryConnectionStep(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, 0, 1, maxCycles, 0);

               } else {

                  ConnectionState.transitionTo(ConnectionState.FAILED, "超过最大周期" + maxCycles);

                  cm.showConnectFailed(state, "voxlink.connection.max_cycles_exceeded");

               }

            }

         } else {

            cm.connectionWon.set(false);

            ConnectionState.transitionTo(ConnectionState.STUN_PROBE, "周期" + (cycle + 1) + "/" + maxCycles);

            if (cycle == 0) {
               if (cm.continuousRetryRound.get() == 0) {
                  cm.activePunchParams = null;
               }

               cm.applyLocalNatClass(cm.classifyLocalNat(), "cycle_start");
               cm.applyRemoteNatClass(cm.classifyRemoteNatLogged(state), "cycle_start");

               PunchProfile recommended;

               int tierReach = cm.stunProbeResult != null ? cm.stunProbeResult.reachableStunUrls.size() : 0;

               boolean tierLowReach = tierReach > 0 && tierReach <= 3;

               boolean tierLocalSym = cm.localNatClass.isSymmetric();

               boolean tierRemoteSym = cm.remoteNatClass.isSymmetric() || state.roomInfo.isHostSymmetric();

               cm.scenarioTier = ScenarioTier.classify(tierLocalSym, tierRemoteSym, tierLowReach, false, false);

               recommended = NatClass.recommendProfile(cm.localNatClass, cm.remoteNatClass);

               cm.switchPunchProfile(recommended, "nat_matrix_" + cm.localNatClass + "x" + cm.remoteNatClass);

               icu.wuhui.voxlink.ui.UiLogBus.push(0, "voxlink.logui.probing");
               VoxLinkMod.LOGGER

                  .info(

                     "[Connection] Layer1 NAT classification: local={} remote={} tier={} -> profile={}",

                     new Object[]{cm.localNatClass, cm.remoteNatClass, ScenarioTier.key(cm.scenarioTier), cm.punchProfile().describeInstance()}

                  );

               if (cm.shouldPrefetchRelay(cm.localNatClass, cm.remoteNatClass, state) && cm.relayPrefetchFuture == null) {

                  cm.relayPrefetchFuture = cm.prefetchRelayCandidates(state).exceptionally(e -> {

                     VoxLinkMod.LOGGER.warn("[Connection] Layer2 relay pre-check failed: {}", e.getMessage());

                     return null;

                  });

               }



               cm.connectionStartTimeMs = System.currentTimeMillis();

               if (cm.punchUiStartMs == 0L) {

                  cm.punchUiStartMs = cm.connectionStartTimeMs;

               }

               icu.wuhui.voxlink.ui.UiLogBus.push(0, "voxlink.logui.punching", cycle + 1);
               int timeoutSec = cm.punchProfile().connectionTimeoutSec;

               boolean joinerSym = cm.stunProbeResult != null && cm.stunProbeResult.natType.isSymmetric();

               boolean hostSym = state.roomInfo.isHostSymmetric();

               if (joinerSym || hostSym) {

                  timeoutSec = cm.punchProfile().symmetricConnectionTimeoutSec;

                  VoxLinkMod.LOGGER

                     .info(

                        "[Connection] One side symmetric NAT (joinerSym={}, hostSym={}), global timeout extended to {}s",

                        new Object[]{joinerSym, hostSym, timeoutSec}

                     );

               }



               cm.connectionTimeoutSec = timeoutSec;

               if (cm.continuousRetryRound.get() == 0) {

                  cm.scheduleConnectionTimeout(state, timeoutSec);

               }

            }



            int displayCycle = cycle + 1;

            if (cycle == 0 && cm.stunProbeResult == null) {

               state.roomInfo.setConnectionMode(Text.translatable("voxlink.connection.probing"));

               RoomManager.RoomState probingState = state;

               cm.scheduler.schedule(() -> {

                  if (cm.roomManager.currentRoom.get() == probingState && !cm.connectionWon.get()) {

                     Text current = probingState.roomInfo.getConnectionMode();

                     if (current != null && current.toPlainString().equals(Text.translatable("voxlink.connection.probing").toPlainString())) {

                        probingState.roomInfo.setConnectionMode(Text.translatable("voxlink.connection.punching"));

                     }

                  }

               }, 15L, TimeUnit.SECONDS);

            } else {

               state.roomInfo.setConnectionMode(Text.translatable("voxlink.connection.punching"));

            }



            cm.tryConnectionStep(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles, 0);

         }

         }

      }

   }
   static void tryConnectionStep(ConnectionManager cm, 

      RoomManager.RoomState state,

      String from,

      String hostIpv6,

      String hostIp,

      int hostPort,

      String hostMappedIp,

      int hostMappedPort,

      int cycle,

      int displayCycle,

      int maxCycles,

      int step

   ) {

      if (cm.roomManager.currentRoom.get() == state) {

         if (cm.connectionWon.get()) {

            VoxLinkMod.LOGGER.info("[Connection] Connected, skip Wave step (cycle={}, step={})", cycle + 1, step);

            // 连接成功后取消 85s 全局超时计时器, 防止干扰后续重连 / 下一轮
            if (cm.connectionTimeoutFuture != null) { cm.connectionTimeoutFuture.cancel(false); cm.connectionTimeoutFuture = null; }

         } else {

            switch (step) {

               case 0:

                  icu.wuhui.voxlink.ui.UiLogBus.push(0, "voxlink.logui.lan");
                  VoxLinkMod.LOGGER.info("[Connection] Wave 1: LAN+IPv6+UDP parallel (cycle {}/{})", displayCycle, maxCycles);

                  state.roomInfo.setConnectionMode(Text.translatable("voxlink.connection.punching"));

                  AtomicBoolean wave1Settled = new AtomicBoolean(false);

                  List<CompletableFuture<?>> wave1Futures = new ArrayList<>();

                  String hostLocalIp = state.roomInfo.getHostLocalIp();

                  boolean sameLan = hostLocalIp != null && !hostLocalIp.isEmpty() && StunDetector.isSameLan(hostLocalIp);

                  boolean sameCgnat = state.roomInfo.isSameCgnat();

                  if (hostLocalIp != null && !hostLocalIp.isEmpty() && (sameLan || sameCgnat)) {

                     String reason = sameLan ? "LAN" : "CGNAT同公网IP";

                     VoxLinkMod.LOGGER.info("[Connection] Wave 1: Detected {} (localIp={}), try direct connect", reason, hostLocalIp);

                     ConnectionFallback lanFallback = cm.trackFallback(new ConnectionFallback());

                     wave1Futures.add(lanFallback.tryIpv4Direct(hostLocalIp, hostPort).thenAccept(result -> {

                        if (cm.roomManager.currentRoom.get() == state && result.success && cm.connectionWon.compareAndSet(false, true)) {

                           VoxLinkMod.LOGGER.info("[Connection] Wave 1: {} direct connect won", reason);

                           wave1Settled.set(true);

                           cm.connectViaBridge(state, result);

                        } else if (cm.roomManager.currentRoom.get() == state && result != null && !result.success) {

                           cm.addressBlacklist.recordDirectFailure(new InetSocketAddress(hostLocalIp, hostPort));

                        }

                     }));

                     if (sameCgnat && !sameLan) {

                        int mcPort = state.roomInfo.getHostPort();

                        VoxLinkMod.LOGGER.info("[Connection] Wave 1: CGNAT also try localIp MC port {}:{}", hostLocalIp, mcPort);

                        ConnectionFallback mcFallback = cm.trackFallback(new ConnectionFallback());

                        wave1Futures.add(mcFallback.tryIpv4Direct(hostLocalIp, mcPort).thenAccept(result -> {

                           if (cm.roomManager.currentRoom.get() == state && result.success && cm.connectionWon.compareAndSet(false, true)) {

                              VoxLinkMod.LOGGER.info("[Connection] Wave 1: CGNAT localIp MC port won");

                              wave1Settled.set(true);

                              cm.connectViaBridge(state, result);

                           }

                        }));

                     }

                  }



                  if (hostIpv6 != null && !hostIpv6.isEmpty() && StunDetector.verifyIPv6Connectivity()) {

                     VoxLinkMod.LOGGER.info("[Connection] Wave 1: Parallel try IPv6 direct connect");

                     ConnectionFallback ipv6Fallback = cm.trackFallback(new ConnectionFallback());

                     wave1Futures.add(ipv6Fallback.tryIpv6Direct(hostIpv6, hostPort).thenAccept(result -> {

                        if (cm.roomManager.currentRoom.get() == state && result.success && cm.connectionWon.compareAndSet(false, true)) {

                           VoxLinkMod.LOGGER.info("[Connection] Wave 1: IPv6 direct connect won");

                           wave1Settled.set(true);

                           cm.connectViaBridge(state, result);

                        }

                     }));

                  } else if (hostIpv6 != null && !hostIpv6.isEmpty()) {

                     VoxLinkMod.LOGGER.info("[Connection] Wave 1: Skip IPv6 (no local IPv6 connectivity)");

                  }



                  PunchStrategy strategy = PunchStrategySelector.select(cm.localNatClass, cm.remoteNatClass, cycle, cm.isLegacyPeer());

                  VoxLinkMod.LOGGER

                     .info(

                        "[Connection] Layer4 smart schedule cycle={} strategy={} localNat={} remoteNat={}",

                        new Object[]{cycle, strategy, cm.localNatClass, cm.remoteNatClass}

                     );

                  if (cm.punchSkipDirect()) {

                     VoxLinkMod.LOGGER.info("[Connection] Wave 1: Skip UDP punch (tuner FIREWALL_DETECTED)");

                  } else {

                     switch (strategy) {

                        case DIRECT_ONLY:

                           VoxLinkMod.LOGGER.info("[Connection] Wave 1: Try UDP punch (DIRECT_ONLY)");

                           cm.tryUdpPunch(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles);

                           // 1.0.0兼容: cycle 0 即并行启动反向打洞。锥形joiner的reverse socket是

                           // 对称host场景的赢面主路径(1.0.0秒进实证), 拖到cycle>=1时host对称NAT

                           // 端口池已漂移(7CSAJA: 25271→25526), ±100窗口必然脱靶且CGNAT已限流

                           if (cycle == 0 && cm.reversePunchAttempted.compareAndSet(false, true)) {

                              VoxLinkMod.LOGGER.info("[Connection] Wave 1: cycle0 parallel reverse punch (1.0.0 compat)");

                              cm.startReversePunch(state);

                           }

                           break;

                        case DIRECT_WITH_REVERSE_PARALLEL:

                           VoxLinkMod.LOGGER.info("[Connection] Wave 1: Try UDP punch (DIRECT_WITH_REVERSE_PARALLEL cycle={})", cycle);

                           cm.tryUdpPunch(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles);

                           if (cycle >= 1 && cm.reversePunchAttempted.compareAndSet(false, true)) {

                              VoxLinkMod.LOGGER.info("[Connection] Wave 1: cycle{} start reverse punch (parallel)", cycle);

                              cm.startReversePunch(state);

                           }

                           break;

                        case REVERSE_FIRST:

                           if (cycle == 0 && cm.reversePunchAttempted.compareAndSet(false, true)) {

                              VoxLinkMod.LOGGER.info("[Connection] Wave 1: REVERSE_FIRST start reverse punch first");

                              cm.startReversePunch(state);

                           }



                           VoxLinkMod.LOGGER.info("[Connection] Wave 1: Try UDP punch (REVERSE_FIRST cycle={})", cycle);

                           cm.tryUdpPunch(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles);

                           break;

                        case RELAY_FALLBACK_FAST:

                           if (cycle < 2) {

                              VoxLinkMod.LOGGER.info("[Connection] Wave 1: Try UDP punch (RELAY_FALLBACK_FAST cycle={})", cycle);

                              cm.tryUdpPunch(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles);

                              if (cycle >= 1 && cm.reversePunchAttempted.compareAndSet(false, true)) {

                                 VoxLinkMod.LOGGER.info("[Connection] Wave 1: RELAY_FALLBACK_FAST cycle{} start reverse birthday", cycle);

                                 cm.startReversePunch(state);

                              }

                           } else {

                              VoxLinkMod.LOGGER.info("[Connection] Layer4 RELAY_FALLBACK_FAST cycle{}>=2 keep punch (relay manual only)", cycle);

                              if (cm.reversePunchAttempted.compareAndSet(false, true)) {

                                 cm.startReversePunch(state);

                              }



                              cm.tryUdpPunch(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles);

                           }

                           break;

                        case REVERSE_ONLY:

                           if (cm.reversePunchAttempted.compareAndSet(false, true)) {

                              VoxLinkMod.LOGGER.info("[Connection] Wave 1: REVERSE_ONLY start reverse punch (skip forward)");

                              cm.startReversePunch(state);

                           }

                           break;

                        case PARALLEL_FROM_START:

                           VoxLinkMod.LOGGER.info("[Connection] Wave 1: PARALLEL_FROM_START forward+reverse (cycle={})", cycle);

                           if (cm.reversePunchAttempted.compareAndSet(false, true)) {

                              VoxLinkMod.LOGGER.info("[Connection] Wave 1: PARALLEL_FROM_START start reverse punch");

                              cm.startReversePunch(state);

                           }



                           cm.tryUdpPunch(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles);

                           break;

                        case REVERSE_THEN_FORWARD:

                           if (cm.reversePunchAttempted.compareAndSet(false, true)) {

                              VoxLinkMod.LOGGER.info("[Connection] Wave 1: REVERSE_THEN_FORWARD start reverse punch");

                              cm.startReversePunch(state);

                           }



                           VoxLinkMod.LOGGER.info("[Connection] Wave 1: REVERSE_THEN_FORWARD forward+reverse (cycle={})", cycle);

                           cm.tryUdpPunch(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles);

                     }

                  }



                  if (!wave1Futures.isEmpty()) {

                     CompletableFuture.allOf(wave1Futures.toArray(new CompletableFuture[0])).thenAccept(v -> {

                        if (cm.roomManager.currentRoom.get() == state) {

                           if (!wave1Settled.get()) {

                              VoxLinkMod.LOGGER.info("[Connection] Wave 1 TCP all failed, enter Wave 2");

                              cm.tryConnectionStep(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles, 1);

                           }

                        }

                     });

                  } else {

                     cm.scheduler.schedule(() -> {

                        if (cm.roomManager.currentRoom.get() == state && cm.connectionCycleActive.get()) {

                           cm.tryConnectionStep(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles, 1);

                        }

                     }, 5L, TimeUnit.SECONDS);

                  }



                  return;

               case 1:

                  icu.wuhui.voxlink.ui.UiLogBus.push(2, "voxlink.logui.tcp_fallback");
                  VoxLinkMod.LOGGER.info("[Connection] Wave 2: TCP fallback parallel (cycle{}/{})", displayCycle, maxCycles);

                  ConnectionState.transitionTo(ConnectionState.TCP_FALLBACK, "Wave 2 TCP兜底");

                  state.roomInfo.setConnectionMode(Text.translatable("voxlink.connection.punching"));

                  new AtomicBoolean(false);

                  List<CompletableFuture<ConnectionFallback.ConnectResult>> wave2Futures = new ArrayList<>();

                  // TCP 双向打洞附加尝试: UDP 败一轮后才启动

                  cm.tryTcpHolePunch(state, hostMappedIp, cycle);

                  if (hostMappedIp != null && !hostMappedIp.isEmpty() && hostMappedPort > 0) {

                     ConnectionFallback tcpSimFallback = cm.trackFallback(new ConnectionFallback());

                     int simLocalPort = P2PBridge.getHostPort() > 0 ? P2PBridge.getHostPort() : hostPort;

                     String myMappedIp = state.roomInfo.getMyMappedIp();

                     int myMappedPort = state.roomInfo.getMyMappedPort();

                     if (myMappedIp != null && myMappedPort > 0 && cm.signalingClient != null) {

                        JsonObject simReq = new JsonObject();

                        simReq.addProperty("joinerMappedIp", myMappedIp);

                        simReq.addProperty("joinerMappedPort", myMappedPort);

                        cm.signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), false, "tcp_simopen_request", simReq, "host");

                        VoxLinkMod.LOGGER.info("[Connection] Wave 2: Send tcp_simopen_request to host ({}:{})", myMappedIp, myMappedPort);

                     }



                     int tcpTargetPort = hostPort > 0 ? hostPort : hostMappedPort;

                     wave2Futures.add(tcpSimFallback.tryTcpSimultaneousOpen(hostMappedIp, tcpTargetPort, simLocalPort));

                  }



                  if (hostMappedIp != null && !hostMappedIp.isEmpty() && hostMappedPort > 0) {

                     ConnectionFallback tcpMappedFallback = cm.trackFallback(new ConnectionFallback());

                     int tcpDirectPort = hostPort > 0 ? hostPort : hostMappedPort;

                     wave2Futures.add(tcpMappedFallback.tryIpv4Direct(hostMappedIp, tcpDirectPort));

                  }



                  if (hostIp != null && !hostIp.isEmpty()) {

                     ConnectionFallback ipv4Fallback = cm.trackFallback(new ConnectionFallback());

                     String fDirectIp = hostIp;

                     int fDirectPort = hostPort;

                     wave2Futures.add(ipv4Fallback.tryIpv4Direct(hostIp, hostPort).whenComplete((result, ex) -> {

                        if (ex == null && result != null && !result.success && cm.roomManager.currentRoom.get() == state) {

                           cm.addressBlacklist.recordDirectFailure(new InetSocketAddress(fDirectIp, fDirectPort));

                        }

                     }));

                  }



                  if (state.roomInfo.isSameCgnat()) {

                     String hostLocalIp2 = state.roomInfo.getHostLocalIp();

                     if (hostLocalIp2 != null && !hostLocalIp2.isEmpty()) {

                        VoxLinkMod.LOGGER.info("[Connection] Wave 2: CGNAT scenario try hostLocalIp {}:{}", hostLocalIp2, hostPort);

                        ConnectionFallback localFallback = cm.trackFallback(new ConnectionFallback());

                        wave2Futures.add(localFallback.tryIpv4Direct(hostLocalIp2, hostPort));

                        int mcPort = state.roomInfo.getHostPort();

                        VoxLinkMod.LOGGER.info("[Connection] Wave 2: CGNAT scenario try localIp MC port {}:{}", hostLocalIp2, mcPort);

                        ConnectionFallback mcLocalFallback = cm.trackFallback(new ConnectionFallback());

                        wave2Futures.add(mcLocalFallback.tryIpv4Direct(hostLocalIp2, mcPort));

                     }

                  }



                  if (hostIpv6 != null && !hostIpv6.isEmpty() && StunDetector.verifyIPv6Connectivity()) {

                     VoxLinkMod.LOGGER.info("[Connection] Wave 2: Try IPv6 direct connection");

                     ConnectionFallback ipv6Fallback2 = cm.trackFallback(new ConnectionFallback());

                     wave2Futures.add(ipv6Fallback2.tryIpv6Direct(hostIpv6, hostPort));

                  }



                  if (wave2Futures.isEmpty()) {

                     VoxLinkMod.LOGGER.info("[Connection] Wave 2: No TCP fallback available, enter next cycle");

                     cm.advanceToNextCycle(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, maxCycles);

                     return;

                  }



                  for (CompletableFuture<ConnectionFallback.ConnectResult> future : wave2Futures) {

                     future.thenAccept(result -> {

                        if (cm.roomManager.currentRoom.get() == state && result.success && cm.connectionWon.compareAndSet(false, true)) {

                           VoxLinkMod.LOGGER.info("[Connection] Wave 2: {} won", result.errorCode);

                           cm.connectViaBridge(state, result);

                        }

                     });

                  }



                  CompletableFuture.allOf(wave2Futures.toArray(new CompletableFuture[0])).thenAccept(v -> {

                     if (cm.roomManager.currentRoom.get() == state) {

                        if (!cm.connectionWon.get()) {

                           VoxLinkMod.LOGGER.info("[Connection] Wave 2: All TCP fallbacks failed, enter next cycle");

                           cm.advanceToNextCycle(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, maxCycles);

                        }

                     }

                  });

                  return;

               default:

                  cm.advanceToNextCycle(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, maxCycles);

            }

         }

      }

   }
   static void tryUdpPunch(ConnectionManager cm, 

      RoomManager.RoomState state,

      String from,

      String hostIpv6,

      String hostIp,

      int hostPort,

      String hostMappedIp,

      int hostMappedPort,

      int cycle,

      int displayCycle,

      int maxCycles

   ) {

      cm.tryUdpPunch(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles, 1);

   }
   static void advanceToNextCycle(ConnectionManager cm, 

      RoomManager.RoomState state, String from, String hostIpv6, String hostIp, int hostPort, String hostMappedIp, int hostMappedPort, int cycle, int maxCycles

   ) {

      if (cm.connectionWon.get()) {

         VoxLinkMod.LOGGER.info("[Connection] Connected, skip next cycle");

      } else if (cycle + 1 >= maxCycles) {

         if (!cm.enterContinuousRetryRound(state)) {

            cm.showConnectFailed(state, "voxlink.connection.max_cycles_exceeded");

         }

      } else {

         for (Entry<String, UdpHolePuncher> entry : cm.activeHolePunchers.entrySet()) {

            String key = entry.getKey();

            if (key.startsWith("joiner")) {

               UdpHolePuncher p = entry.getValue();



               try {

                  p.stopPunch();

               } catch (Exception var16) {

               }



               if (key.startsWith("joiner_ms_")) {

                  try {

                     p.close();

                  } catch (Exception var15) {

                  }

               }

            }

         }



         if (cycle == 0 && cm.punchProfile() == PunchProfile.DEFAULT) {

            boolean hostSym = state.roomInfo.isHostSymmetric();

            boolean localSym = cm.stunProbeResult != null && cm.stunProbeResult.natType.isSymmetric();

            if (hostSym || localSym) {

               cm.switchPunchProfile(PunchProfile.AGGRESSIVE, "首轮失败+" + (hostSym ? "HostSym" : "LocalSym"));

               VoxLinkMod.LOGGER.info("[Connection] Detected extreme symmetric NAT, switch to aggressive level next round: {}", cm.punchProfile().describeInstance());

            }

         }



         if (cm.lastPunchResult != null && cm.lastPunchResult.reason != null) {

            if (cm.lastPunchResult.reason == cm.lastFailureReason) {

               int count = cm.consecutiveFailureCount.incrementAndGet();

               if (count >= 3) {

                  VoxLinkMod.LOGGER.warn("[Connection] Consecutive {} failures with same reason ({}), switch strategy", count, cm.lastFailureReason);

                  switch (cm.lastFailureReason) {

                     case NO_RESPONSE:

                        cm.switchPunchProfile(PunchProfile.HARDSYM, "consecutive_no_response");

                     case FIREWALL_DETECTED:

                        break;

                     default:

                        cm.switchPunchProfile(PunchProfile.AGGRESSIVE, "consecutive_" + cm.lastFailureReason);

                  }



                  cm.consecutiveFailureCount.set(0);

               }

            } else {

               cm.lastFailureReason = cm.lastPunchResult.reason;

               cm.consecutiveFailureCount.set(1);

            }

         }



         int delayIdx = Math.min(cycle, ConnectionManager.BACKOFF_DELAYS_MS.length - 1);

         long delay = ConnectionManager.BACKOFF_DELAYS_MS[delayIdx];

         VoxLinkMod.LOGGER

            .info(

               "[Connection] Cycle {}/{} failed, retry in {}s (backoff level={})", new Object[]{cycle + 1, maxCycles, delay / 1000L, cm.punchProfile().describeInstance()}

            );

         cm.scheduler.schedule(() -> {

            if (cm.roomManager.currentRoom.get() == state && state != RoomManager.PENDING) {

               cm.runConnectionCycle(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle + 1);

            }

         }, delay, TimeUnit.MILLISECONDS);

      }

   }
   static void startReversePunch(ConnectionManager cm, RoomManager.RoomState state) {

      icu.wuhui.voxlink.ui.UiLogBus.push(0, "voxlink.logui.reverse");
      VoxLinkMod.LOGGER.info("[ReversePunch] Parallel start reverse punch");

      state.roomInfo.setConnectionMode(Text.translatable("voxlink.connection.punching"));

      cm.connectionCycleActive.set(true);

      boolean isSymmetric = cm.stunProbeResult != null && cm.stunProbeResult.natType.isSymmetric();

      boolean isEasySym = cm.stunProbeResult != null && cm.stunProbeResult.natType.isEasySymmetric();

      boolean hostSym = state.roomInfo != null && (state.roomInfo.isHostSymmetric() || StunDetector.isNatTypeSymmetric(state.roomInfo.getNatType()));

      if (!isSymmetric) {

         cm.startSimpleReversePunch(state);

      } else {

         cm.switchPunchProfile(PunchProfile.V100, "sym_birthday");

         int socketCount = isEasySym ? cm.punchProfile().birthdaySocketCount : cm.punchProfile().hardSymSocketCount;

         VoxLinkMod.LOGGER

            .info(

               "[ReversePunch] Detected local symmetric (local={}, host={}) — birthday attack {} sockets (easySym={})",

               new Object[]{isSymmetric, hostSym, socketCount, isEasySym}

            );

         cm.startBirthdayPunch(state, socketCount, isEasySym);

      }

   }
   static void startBirthdayPunch(ConnectionManager cm, RoomManager.RoomState state) {

      cm.startBirthdayPunch(state, cm.punchProfile().birthdaySocketCount, false);

   }
   static void startSimpleReversePunch(ConnectionManager cm, RoomManager.RoomState state) {

      UdpHolePuncher puncher = new UdpHolePuncher();

      cm.applyPunchTemplate(puncher);



      try {

         puncher.createSocket();

      } catch (Exception e) {

         VoxLinkMod.LOGGER.error("[ReversePunch] Create punch socket failed: {}", e.getMessage());

         return;

      }



      cm.activeHolePunchers.put("joiner_reverse", puncher);

      List<String> quadStun = cm.stunUrlsProbedFirst();

      StunProbe.PublicMappedAddress[] quadResult = StunProbe.discoverMappedAddressQuad(

         puncher.getSocket(), quadStun.get(0), quadStun.get(1), quadStun.get(2), quadStun.get(3)

      );

      StunProbe.PublicMappedAddress myMapped1 = quadResult[0] != null ? quadResult[0] : (quadResult[2] != null ? quadResult[2] : quadResult[3]);

      StunProbe.PublicMappedAddress myMapped2 = quadResult[1] != null ? quadResult[1] : (quadResult[3] != null ? quadResult[3] : quadResult[2]);

      boolean joinerSymmetric = false;

      int joinerMappedPortDelta = 0;

      StunProbe.PublicMappedAddress myMappedAddr = null;

      if (myMapped1 != null && myMapped2 != null) {

         if (cm.sameIpFamily(myMapped1.ip(), myMapped2.ip()) && myMapped1.port() != myMapped2.port()) {

            joinerSymmetric = true;

            joinerMappedPortDelta = myMapped2.port() - myMapped1.port();

            VoxLinkMod.LOGGER

               .info(

                  "[ReversePunch] Joiner punch socket STUN: detected symmetric ({} vs {}, delta={})",

                  new Object[]{myMapped1.port(), myMapped2.port(), joinerMappedPortDelta}

               );

         }



         myMappedAddr = myMapped2;

      } else {

         myMappedAddr = myMapped1 != null ? myMapped1 : myMapped2;

      }



      if (myMappedAddr == null) {

         myMappedAddr = puncher.discoverMappedAddress(cm.stunUrlsProbedFirst());

      }



      if (myMappedAddr == null) {

         VoxLinkMod.LOGGER.warn("[ReversePunch] STUN failed, cannot reverse punch");

         puncher.close();

         cm.activeHolePunchers.remove("joiner_reverse");

      } else {

         VoxLinkMod.LOGGER

            .info("[ReversePunch] Joiner mapped address: {}:{} (symmetric={})", new Object[]{myMappedAddr.ip(), myMappedAddr.port(), joinerSymmetric});

         if (joinerSymmetric) {

            VoxLinkMod.LOGGER.info("[ReversePunch] Reverse socket STUN detected symmetric, upgrade to birthday attack");



            try {

               puncher.close();

            } catch (Exception var17) {

            }



            cm.activeHolePunchers.remove("joiner_reverse");

            cm.startBirthdayPunch(state, cm.punchProfile().hardSymSocketCount, false);

         } else {

            JsonObject offerData = new JsonObject();

            offerData.addProperty("joinerMappedIp", myMappedAddr.ip());

            offerData.addProperty("joinerMappedPort", myMappedAddr.port());

            if (joinerSymmetric || cm.stunProbeResult != null && cm.stunProbeResult.natType.isSymmetric()) {

               offerData.addProperty("joinerSymmetric", true);

            }



            if (cm.stunProbeResult != null && cm.stunProbeResult.natType.isEasySymmetric() || joinerSymmetric) {

               offerData.addProperty("joinerEasySym", true);

            }



            if (joinerMappedPortDelta != 0) {

               offerData.addProperty("joinerMappedPortDelta", joinerMappedPortDelta);

            }



            cm.signalingClient

               .sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), false, "reverse_holepunch_offer", offerData, "host")

               .thenAccept(response -> {

                  if (!response.success) {

                     VoxLinkMod.LOGGER.error("[ReversePunch] Failed to send reverse_holepunch_offer: {}", response.error);

                  }

               })

               .exceptionally(e -> {

                  VoxLinkMod.LOGGER.error("[ReversePunch] Failed to send reverse_holepunch_offer: {}", e.getMessage());

                  return null;

               });

            String hostMappedIp = state.roomInfo.getHostMappedIp();

            int hostMappedPort = state.roomInfo.getHostMappedPort();

            if (hostMappedIp == null || hostMappedPort <= 0) {

               hostMappedIp = state.roomInfo.getHostIp();

               hostMappedPort = state.roomInfo.getHostPort() > 0 ? state.roomInfo.getHostPort() : 51600;

            }



            if (hostMappedIp != null && !hostMappedIp.isEmpty()) {

               String fHostMappedIp = hostMappedIp;

               int fHostMappedPort = hostMappedPort;

               UdpHolePuncher finalPuncher = puncher;

               int portRange = state.roomInfo.isHostSymmetric() || joinerSymmetric

                  ? cm.punchProfile().portPredictionMaxRange

                  : cm.punchProfile().portPredictionMaxRange;

               // 反向socket起始中心可能是陈旧offer端口, 要等reverse_punch_info/holepunch_mapped纠偏后

               // 才落在新鲜窗口; V100默认8s超时会被纠偏耗掉一半, 延长到15s保证纠偏后有充足轮次

               PunchParams simpleRevParams = PunchParams.fromProfile(cm.punchProfile());

               simpleRevParams.timeoutMs = Math.max(simpleRevParams.timeoutMs, 15000);

               finalPuncher.setPunchParams(simpleRevParams);

               VoxLinkMod.LOGGER.info("[ReversePunch] Punch to {}:{} immediately (range=±{})", new Object[]{fHostMappedIp, fHostMappedPort, portRange});

               puncher.setOnPeerPunchReceived(addr -> {

                  cm.sessionPunchRecvEver.set(true);

                  String code = state.roomInfo.getCode();

                  String token = state.roomInfo.getToken();

                  JsonObject portData = new JsonObject();

                  portData.addProperty("peer_ip", addr.getAddress().getHostAddress());

                  portData.addProperty("peer_port", addr.getPort());

                  cm.signalingClient.sendSignal(code, token, false, "peer_port", portData, "host").exceptionally(e -> {

                     VoxLinkMod.LOGGER.debug("peer_port signal send failed: {}", e.getMessage());

                     return null;

                  });

               });

               puncher.punchWithPortPrediction(fHostMappedIp, fHostMappedPort, portRange, true)

                  .thenAccept(

                     result -> {

                        if (!result.isSuccess()) {

                           PunchFailureClassifier.FailureReason reason = PunchFailureClassifier.classify(result);

                           cm.lastPunchResult = result.withReason(reason);

                           cm.activePunchParams = 

                              PunchTuner.nextParams(cm.punchProfile(), cm.localNatClass, cm.remoteNatClass, 1, 8, reason, cm.lastPunchResult)

                           ;

                           cm.recordPunchFailure(reason, result.socketsReceivedPunch, result.socketsReceivedAck);

                        } else {

                           DatagramSocket socket = result.getSuccessSocket();

                           if (cm.roomManager.currentRoom.get() == state && cm.connectionWon.compareAndSet(false, true)) {

                              VoxLinkMod.LOGGER.info("[ReversePunch] Joiner reverse punch success {}:{}", fHostMappedIp, fHostMappedPort);

                              finalPuncher.markSocketTransferred();

                              cm.stopAllPunchingAfterHostBridge();

                              finalPuncher.stopPunch();

                              DatagramSocket punchSocket = socket;

                              UdpHolePuncher puncherRef = finalPuncher;

                              InetSocketAddress actualAddr = puncherRef.getActualRemoteAddress();

                              if (actualAddr == null) {

                                 actualAddr = new InetSocketAddress(fHostMappedIp, fHostMappedPort);

                              }



                              InetSocketAddress finalTargetAddr = actualAddr;

                              VoxLinkMod.LOGGER

                                 .info(

                                    "[ReversePunch] Actual target address: {} (STUN mapping: {}:{})",

                                    new Object[]{finalTargetAddr, fHostMappedIp, fHostMappedPort}

                                 );

                              cm.scheduler.submit(() -> {

                                 try {

                                    cm.establishUdpTransport(state, punchSocket, puncherRef, finalTargetAddr, "joiner", false, null);

                                 } catch (Exception e) {

                                    VoxLinkMod.LOGGER.error("[ReversePunch] Joiner UDP transport create failed: {}", e.getMessage());



                                    try {

                                       puncherRef.close();

                                    } catch (Exception var7x) {

                                    }



                                    cm.showConnectFailed(state, "voxlink.connection.transport_failed");

                                 }

                              });

                           } else {

                              try {

                                 finalPuncher.close();

                              } catch (Exception var11x) {

                              }

                           }

                        }

                     }

                  )

                  .exceptionally(

                     e -> {

                        VoxLinkMod.LOGGER

                           .debug("[ReversePunch] Joiner reverse punch failed (wait for reverse_punch_info to update target then retry): {}", e.getMessage());

                        return null;

                     }

                  );

               cm.scheduler.schedule(() -> {

                  if (cm.connectionCycleActive.get() && cm.roomManager.currentRoom.get() == state && !cm.connectionWon.get()) {

                     VoxLinkMod.LOGGER.warn("[ReversePunch] Reverse punch timeout");

                     UdpHolePuncher rp = cm.activeHolePunchers.remove("joiner_reverse");

                     if (rp != null) {

                        rp.cancel();

                        rp.close();

                     }

                  }

               }, 20L, TimeUnit.SECONDS);

            } else {

               VoxLinkMod.LOGGER.warn("[ReversePunch] No host address, cannot punch immediately");

            }

         }

      }

   }
   static void startBirthdayPunchPhase2(ConnectionManager cm, 

      RoomManager.RoomState state,

      List<UdpHolePuncher> birthdayPunchers,

      List<String> birthdayKeys,

      String hostMappedIp,

      int hostMappedPort,

      boolean hostSymmetric,

      boolean isEasySym

   ) {

      AtomicBoolean won = new AtomicBoolean(false);



      for (int i = 0; i < birthdayPunchers.size(); i++) {

         UdpHolePuncher puncher = birthdayPunchers.get(i);

         if (puncher.getSocket() != null && !puncher.getSocket().isClosed()) {

            int idx = i;

            puncher.setOnPeerPunchReceived(

               addr -> {

                  cm.sessionPunchRecvEver.set(true);

                  if (!won.get()) {

                     VoxLinkMod.LOGGER

                        .info("[BirthdayPunch] Socket #{} received peer punch to {}:{}", new Object[]{idx, addr.getAddress().getHostAddress(), addr.getPort()});

                  }

               }

            );

            int portRange = cm.punchProfile().defaultPortRange;

            if (isEasySym) {

               portRange = cm.punchProfile().easySymPortRange;

            } else if (hostSymmetric) {

               portRange = cm.punchProfile().defaultPortRange;

            } else {

               portRange = cm.punchProfile().minPortRange;

            }



            puncher.punchWithPortPrediction(hostMappedIp, hostMappedPort, portRange, true)

               .thenAccept(

                  result -> {

                     if (!result.isSuccess()) {

                        PunchFailureClassifier.FailureReason reason = PunchFailureClassifier.classify(result);

                        cm.lastPunchResult = result.withReason(reason);

                        cm.activePunchParams = 

                           PunchTuner.nextParams(cm.punchProfile(), cm.localNatClass, cm.remoteNatClass, 1, 8, reason, cm.lastPunchResult)

                        ;

                        cm.recordPunchFailure(reason, result.socketsReceivedPunch, result.socketsReceivedAck);

                     } else {

                        DatagramSocket socket = result.getSuccessSocket();

                        if (!cm.connectionWon.compareAndSet(false, true)) {

                           try {

                              puncher.close();

                           } catch (Exception var13x) {

                           }

                        } else if (!won.compareAndSet(false, true)) {

                           try {

                              puncher.close();

                           } catch (Exception var14) {

                           }

                        } else {

                           VoxLinkMod.LOGGER.info("[BirthdayPunch] Socket #{} won! Connected to {}:{}", new Object[]{idx, hostMappedIp, hostMappedPort});

                           puncher.markSocketTransferred();

                           cm.stopAllPunchingAfterHostBridge();



                           for (UdpHolePuncher sp : birthdayPunchers) {

                              if (sp != puncher) {

                                 try {

                                    sp.stopPunch();

                                    sp.close();

                                 } catch (Exception var15) {

                                 }

                              }

                           }



                           puncher.stopPunch();

                           DatagramSocket punchSocket = socket;

                           UdpHolePuncher puncherRef = puncher;

                           cm.scheduler

                              .submit(

                                 () -> {

                                    try {

                                       cm.establishUdpTransport(

                                          state, punchSocket, puncherRef, new InetSocketAddress(hostMappedIp, hostMappedPort), "joiner", false, null

                                       );

                                    } catch (Exception e) {

                                       VoxLinkMod.LOGGER.error("[BirthdayPunch] Transport create failed: {}", e.getMessage());



                                       try {

                                          puncherRef.close();

                                       } catch (Exception var8x) {

                                       }



                                       cm.showConnectFailed(state, "voxlink.connection.transport_failed");

                                    }

                                 }

                              );

                        }

                     }

                  }

               )

               .exceptionally(e -> {

                  VoxLinkMod.LOGGER.debug("[BirthdayPunch] Socket #{} failed: {}", idx, e.getMessage());

                  return null;

               });

         }

      }



      cm.scheduler.schedule(() -> {

         if (!won.get() && cm.connectionCycleActive.get() && cm.roomManager.currentRoom.get() == state) {

            VoxLinkMod.LOGGER.warn("[BirthdayPunch] All {} sockets timeout", birthdayPunchers.size());



            for (int ix = 0; ix < birthdayPunchers.size(); ix++) {

               try {

                  birthdayPunchers.get(ix).cancel();

                  birthdayPunchers.get(ix).close();

               } catch (Exception var7x) {

               }



               cm.activeHolePunchers.remove(birthdayKeys.get(ix));

            }



            cm.showConnectFailed(state, "voxlink.connection.punch_timeout");

         }

      }, cm.punchProfile().reverseWindowSec, TimeUnit.SECONDS);

   }
   static boolean claimVoxlinkDualWin(ConnectionManager cm) {

      if (!cm.dualRaceActive) {

         return true;

      } else if (cm.dualRaceWon.compareAndSet(false, true)) {

         cm.voxlinkWon = true;

         return true;

      } else {

         return false;

      }

   }
   static CompletableFuture<Void> startDualP2P(ConnectionManager cm, String roomCode, String playerName, String password, BiConsumer<String, String> statusCallback) {

      cm.resetConnectionStateForNextP2P();
      icu.wuhui.voxlink.ui.UiLogBus.reset();
      icu.wuhui.voxlink.ui.UiLogBus.push(0, "voxlink.logui.preparing");
      if (cm.signalingClient != null && cm.signalingClient.isWsConnected()) {
         icu.wuhui.voxlink.ui.UiLogBus.push(0, "voxlink.logui.signaling_ready");
      }

      cm.resetDualRaceState();

      cm.resetContinuousRetryState();

      // 新连接会话：清 UI 按钮计时基准，防止上一会话残留值让"使用中继"按钮立即闪现
      cm.punchUiStartMs = 0L;

      cm.connectionWon.set(false);

      cm.connectionWon.set(false);

      cm.connectionEstablishedAtMs = 0L;

      // 智能自动中继：新会话复位编排器状态（房客侧打洞期间持续评估触发条件）
      cm.stopAutoRelayAll();

      RoomManager.RoomState directorState = cm.roomManager.currentRoom.get();
      if (directorState == null || directorState == RoomManager.PENDING) {
         // joinRoom 在本调用之后才发起, 房间态可能数秒后才就绪: 一次性+1s 挂载会整场落空
         cm.scheduleAutoRelayDirectorArm();
      } else {
         cm.startAutoRelayDirector(directorState);
      }

      VoxLinkMod.LOGGER

         .info(

            "[DualP2P] startDualP2P roomCode={} parallel={} isTerracotta={} isVoxLink={}",

            new Object[]{roomCode, VoxLinkMod.getConfig().isParallelP2P(), RoomCodeRouter.isTerracottaCode(roomCode), RoomCodeRouter.isVoxLinkCode(roomCode)}

         );

      if (RoomCodeRouter.isTerracottaCode(roomCode)) {

         VoxLinkMod.LOGGER.info("[DualP2P] Go pure Terracotta path");



         try {

            TerracottaManager.shutdown();

         } catch (Exception e) {

            VoxLinkMod.LOGGER.debug("pre-cleanup terracotta error: {}", e.getMessage());

         }



         statusCallback.accept("terracotta", "voxlink.attempting_join.joining");

         return TerracottaManager.joinRoom(roomCode, playerName).thenAccept(connectUrl -> {

            cm.connectTerracottaToMC(connectUrl, roomCode);

            statusCallback.accept("terracotta", "voxlink.connection.bridge_setup");

         });

      } else if (RoomCodeRouter.isFedCode(roomCode)) {

         // 互通房：join回包即带fedAddress，connectFed直连HL中继，不吃陶瓦竞速

         statusCallback.accept("voxlink", "voxlink.connection.joining");

         return cm.startVoxLinkP2P(roomCode, password);

      } else if (!RoomCodeRouter.isVoxLinkCode(roomCode)) {

         return CompletableFuture.failedFuture(new IllegalArgumentException(Component.translatable("voxlink.error.invalid_room_code").getString()));

      } else if (!VoxLinkMod.getConfig().isParallelP2P()) {

         statusCallback.accept("voxlink", "voxlink.connection.joining");

         return cm.startVoxLinkP2P(roomCode, password);

      } else {

         return TerracottaManager.waitForDownload().thenCompose(ready -> {

            VoxLinkMod.LOGGER.info("[DualP2P] waitForDownload returned ready={} binaryReady={}", ready, TerracottaBinary.isReady());

            if (!ready) {

               statusCallback.accept("voxlink", "voxlink.connection.joining");

               return cm.startVoxLinkP2P(roomCode, password);

            } else {

               return cm.runDualP2PRace(roomCode, playerName, password, statusCallback);

            }

         });

      }

   }
   static CompletableFuture<Void> runDualP2PRace(ConnectionManager cm, String roomCode, String playerName, String password, BiConsumer<String, String> statusCallback) {

      VoxLinkMod.LOGGER.info("[DualP2P] Dual P2P race started roomCode={}", roomCode);

      statusCallback.accept("voxlink", "voxlink.connection.joining");

      statusCallback.accept("terracotta", "voxlink.attempting_join.joining");

      cm.dualRaceActive = true;

      cm.terracottaWon = false;

      cm.voxlinkWon = false;

      cm.voxlinkSideDisabled = false;

      cm.dualFailedCount.set(0);

      cm.dualRaceWon.set(false);

      CompletableFuture<Void> dualResult = new CompletableFuture<>();

      cm.dualResultRef = dualResult;

      cm.dualVoxlinkBridgeFuture = new CompletableFuture<>();

      CompletableFuture<Void> bridgeFuture = cm.dualVoxlinkBridgeFuture;

      cm.scheduler.schedule(() -> {

         if (!bridgeFuture.isDone() && !cm.isPersistentRetrying()) {

            bridgeFuture.completeExceptionally(new RuntimeException("VoxLink桥建立超时"));

         }

      }, 120L, TimeUnit.SECONDS);

      CompletableFuture<Void> joinFuture = cm.startVoxLinkP2P(roomCode, password);

      joinFuture.whenComplete((v, joinErr) -> {

         if (joinErr != null) {

            VoxLinkMod.LOGGER.warn("[DualP2P] VoxLink joinRoom failed: {}", joinErr.getMessage());

            statusCallback.accept("voxlink", "voxlink.dual.channel_failed");

         }

      });

      joinFuture.<Void>thenCompose(v -> bridgeFuture).whenComplete((r, e) -> {

         cm.dualVoxlinkBridgeFuture = null;

         if (e == null) {

            if (cm.voxlinkWon) {

               cm.killAllConnectionAttempts("terracotta");

               statusCallback.accept("voxlink", "voxlink.dual.p2p_established");

               statusCallback.accept("terracotta", "voxlink.dual.status_cancelled");

               dualResult.complete(null);

               cm.resetDualRaceState();

            } else {

               cm.voxlinkSideDisabled = true;

               cm.killAllConnectionAttempts("voxlink");

               P2PBridge.disconnect();

               statusCallback.accept("voxlink", "voxlink.dual.status_cancelled");

            }

         } else if (!cm.dualRaceWon.get()) {

            if (cm.isPersistentRetrying()) {

               VoxLinkMod.LOGGER.info("[DualP2P] VoxLink bridge failed but persistent retrying, keep retrying (round={})", cm.continuousRetryRound.get());

               return;

            }



            statusCallback.accept("voxlink", "voxlink.dual.channel_failed");

            cm.voxlinkSideDisabled = true;

            if (cm.dualFailedCount.incrementAndGet() >= 2) {

               dualResult.completeExceptionally(e);

            }

         }

      });

      joinFuture.<String>thenCompose(v -> {

         RoomInfo ri = cm.roomManager.getCurrentRoom();

         String tc = ri != null ? ri.getTerracottaCode() : null;

         VoxLinkMod.LOGGER.info("[DualP2P] Terracotta branch triggered terracottaCode={} binaryReady={}", tc, TerracottaBinary.isReady());

         return tc != null && !tc.isEmpty() ? TerracottaManager.joinRoom(tc, playerName) : CompletableFuture.failedFuture(new RuntimeException("host未上传陶瓦房间号"));

      }).whenComplete((connectUrl, e) -> {

         if (e == null) {

            if (cm.dualRaceWon.compareAndSet(false, true)) {

               cm.terracottaWon = true;

               if (cm.connectionWon.get()) {

                  VoxLinkMod.LOGGER.warn("[DualP2P] Terracotta won CAS but VoxLink bridge built, skip takeover");

                  return;

               }



               cm.killAllConnectionAttempts("voxlink");

               cm.voxlinkSideDisabled = true;

               if (bridgeFuture != null && !bridgeFuture.isDone()) {

                  bridgeFuture.completeExceptionally(new RuntimeException("Terracotta已赢 VoxLink放弃"));

               }



               try {

                  cm.connectTerracottaToMC(connectUrl, roomCode);

                  statusCallback.accept("terracotta", "voxlink.dual.p2p_established");

                  statusCallback.accept("voxlink", "voxlink.dual.status_cancelled");

                  dualResult.complete(null);

                  // 此处不得复位竞态状态：terracottaWon/voxlinkSideDisabled 一旦被清，
                  // 随后到达的 turn_ready 会绕过互斥守卫再发起一次 connectToServer，
                  // 表现为"闪一下（25565 refused）又连上（TURN 端口）"两层叠加。
                  // 竞态字段统一由 leaveRoom 与新会话入口清理。

               } catch (Exception ex) {

                  VoxLinkMod.LOGGER.error("[DualP2P] Terracotta connect MC failed: {}", ex.getMessage());

                  statusCallback.accept("terracotta", "voxlink.dual.channel_failed");

                  dualResult.completeExceptionally(ex);

               }

            }

         } else if (!cm.dualRaceWon.get()) {

            Throwable cause = e;



            while (cause.getCause() != null && cause.getCause() != cause) {

               cause = cause.getCause();

            }



            VoxLinkMod.LOGGER.warn("[DualP2P] Terracotta side failed: {}", cause.getMessage());

            statusCallback.accept("terracotta", "voxlink.dual.channel_failed");

            if (cm.dualFailedCount.incrementAndGet() >= 2) {

               dualResult.completeExceptionally(e);

            }

         }

      });

      return dualResult;

   }
   static CompletableFuture<Void> startVoxLinkP2P(ConnectionManager cm, String roomCode, String password) {
      int gen = cm.joinAttemptGeneration.incrementAndGet();
      return cm.startVoxLinkP2PAttempt(roomCode, password, 1, gen);
   }
   static CompletableFuture<Void> startVoxLinkP2PAttempt(ConnectionManager cm, String roomCode, String password, int attempt, int gen) {
      long t0 = System.currentTimeMillis();
      return cm.roomManager.joinRoom(roomCode, password).handle((r, e) -> {
         if (e == null) {
            VoxLinkMod.LOGGER.info("[joinRoom] attempt {}/{} OK in {}ms", new Object[]{attempt, ConnectionManager.JOIN_TRANSIENT_MAX_ATTEMPTS, System.currentTimeMillis() - t0});
            return CompletableFuture.<Void>completedFuture(null);
         }

         Throwable cause = e;
         while ((cause instanceof java.util.concurrent.CompletionException || cause instanceof java.util.concurrent.ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
         }

         boolean retryable = cause instanceof RoomManager.TransientException;
         VoxLinkMod.LOGGER.warn("[joinRoom] attempt {}/{} failed in {}ms: {} — {}", new Object[]{attempt, ConnectionManager.JOIN_TRANSIENT_MAX_ATTEMPTS, System.currentTimeMillis() - t0, cause.getClass().getSimpleName(), cause.getMessage()});
         if (!retryable || attempt >= ConnectionManager.JOIN_TRANSIENT_MAX_ATTEMPTS || gen != cm.joinAttemptGeneration.get()) {
            icu.wuhui.voxlink.ui.UiLogBus.push(2, "voxlink.logui.join_failed", String.valueOf(cause.getMessage()));
            return CompletableFuture.<Void>failedFuture(cause);
         }

         long delay = ConnectionManager.JOIN_RETRY_BACKOFF_MS[Math.min(attempt - 1, ConnectionManager.JOIN_RETRY_BACKOFF_MS.length - 1)];
         icu.wuhui.voxlink.ui.UiLogBus.push(1, "voxlink.logui.join_retry", new Object[]{attempt + 1, ConnectionManager.JOIN_TRANSIENT_MAX_ATTEMPTS});
         VoxLinkMod.LOGGER.warn("[joinRoom] retrying in {}ms (attempt {}/{})", new Object[]{delay, attempt + 1, ConnectionManager.JOIN_TRANSIENT_MAX_ATTEMPTS});
         CompletableFuture<Void> next = new CompletableFuture<>();
         cm.scheduler.schedule(() -> cm.startVoxLinkP2PAttempt(roomCode, password, attempt + 1, gen).whenComplete((v2, e2) -> {
            if (e2 != null) {
               next.completeExceptionally(e2);
            } else {
               next.complete(null);
            }
         }), delay, TimeUnit.MILLISECONDS);
         return next;
      }).thenCompose(f -> f);
   }
   static void escalateProfileForRound(ConnectionManager cm, int round) {
      PunchProfileCtrl.escalateProfileForRound(cm, round);
   }
   static int getEffectiveMaxCycles(ConnectionManager cm) {

      if (cm.stunProbeResult != null) {

         int reachable = cm.stunProbeResult.reachableStunUrls.size();

         return cm.stunProbeResult.natType.isSymmetric()

            ? Math.max(2, Math.min(reachable * 2 / 2, cm.punchProfile().maxSymCycles))

            : Math.max(1, Math.min(reachable, cm.punchProfile().maxCycles));

      } else {

         return cm.punchProfile().fallbackCycles;

      }

   }
}
