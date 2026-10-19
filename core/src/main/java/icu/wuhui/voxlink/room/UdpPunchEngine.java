package icu.wuhui.voxlink.room;

import com.google.gson.JsonObject;
import icu.wuhui.voxlink.network.LogUploadManager;
import java.util.concurrent.ScheduledFuture;
import java.util.LinkedHashSet;
import java.util.Map.Entry;
import icu.wuhui.voxlink.network.ConnectionFallback;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import icu.wuhui.voxlink.network.ConnectionHelper;
import icu.wuhui.voxlink.network.ProtocolNegotiator;
import icu.wuhui.voxlink.network.PunchFailureClassifier;
import icu.wuhui.voxlink.network.UPnPManager;
import icu.wuhui.voxlink.network.AddressBlacklist;
import icu.wuhui.voxlink.network.NatLabels;
import icu.wuhui.voxlink.room.StunDetector;
import java.util.concurrent.CompletableFuture;
import java.util.Collections;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import icu.wuhui.voxlink.VoxLinkMod;

import icu.wuhui.voxlink.network.NatClass;

import icu.wuhui.voxlink.platform.Text;

import icu.wuhui.voxlink.platform.ChatSink;

import java.util.List;

import java.util.concurrent.TimeUnit;

import icu.wuhui.voxlink.network.UdpHolePuncher;

import icu.wuhui.voxlink.network.PunchAuth;

import icu.wuhui.voxlink.network.PunchParams;

import icu.wuhui.voxlink.network.PunchProfile;

import icu.wuhui.voxlink.network.PunchResult;

import icu.wuhui.voxlink.network.PunchStrategy;

import icu.wuhui.voxlink.network.PunchStrategySelector;

import icu.wuhui.voxlink.network.PunchTuner;

import icu.wuhui.voxlink.network.ReliableUdpTransport;

import icu.wuhui.voxlink.network.P2PBridge;

import icu.wuhui.voxlink.network.ScenarioTier;

import icu.wuhui.voxlink.network.StunProbe;

import icu.wuhui.voxlink.network.PortPredictor;

import icu.wuhui.voxlink.platform.ClientThread;

import java.net.DatagramSocket;

import java.net.InetSocketAddress;

import java.net.InetAddress;

import java.util.ArrayList;

import java.util.Map;

import java.util.Set;

import java.util.concurrent.ConcurrentHashMap;

import java.util.concurrent.atomic.AtomicInteger;

import java.util.concurrent.atomic.AtomicBoolean;

/** UDP 打洞引擎: offer/mapped/punch_info 收发、反向打洞、host 打洞上下文。(Phase4 从 ConnectionManager 拆出) */
public final class UdpPunchEngine {
   private UdpPunchEngine() {
   }

   static void clearActiveHolePunchers(ConnectionManager cm) {

      for (String key : new ArrayList<>(cm.activeHolePunchers.keySet())) {

         UdpHolePuncher p = cm.activeHolePunchers.remove(key);

         if (p != null) {

            try {

               p.close();

            } catch (Exception e) {

               VoxLinkMod.LOGGER.debug("cleanup puncher close error: {}", e.getMessage());

            }

         }

      }



      cm.activeHolePunchers.clear();

   }
   static void clearHostPunchingState(ConnectionManager cm) {

      cm.clearHostPunchContexts();
      UdpHolePuncher hp = cm.activeHolePunchers.remove("host");

      if (hp != null) {

         try {

            hp.close();

         } catch (Exception e) {

            VoxLinkMod.LOGGER.debug("host puncher close error: {}", e.getMessage());

         }

      }

   }
   static void stopAllPunchingAfterHostBridge(ConnectionManager cm) {

      cm.continuousRetryCancelled.set(true);

      cm.connectionCycleActive.set(false);

      cm.connectionWon.set(true);

      cm.cancelAllFallbacks();

      if (cm.connectionTimeoutFuture != null) {

         cm.connectionTimeoutFuture.cancel(false);

         cm.connectionTimeoutFuture = null;

      }



      for (UdpHolePuncher puncher : cm.activeHolePunchers.values()) {

         try {

            puncher.cancel();

         } catch (Exception var6) {

         }



         try {

            puncher.stopPunch();

         } catch (Exception var5) {

         }



         try {

            puncher.close();

         } catch (Exception var4) {

         }

      }



      cm.activeHolePunchers.clear();
      cm.clearHostPunchContexts();

      if (cm.dualRaceActive && !cm.terracottaWon) {

         cm.killAllConnectionAttempts("terracotta");

      }

   }
   static void stopAllPunchingForRelay(ConnectionManager cm) {

      cm.connectionCycleActive.set(false);

      if (cm.connectionTimeoutFuture != null) {

         cm.connectionTimeoutFuture.cancel(false);

         cm.connectionTimeoutFuture = null;

      }



      for (UdpHolePuncher puncher : cm.activeHolePunchers.values()) {

         try {

            puncher.cancel();

         } catch (Exception var6) {

         }



         try {

            puncher.stopPunch();

         } catch (Exception var5) {

         }



         try {

            puncher.close();

         } catch (Exception var4) {

         }

      }



      cm.activeHolePunchers.clear();
      cm.clearHostPunchContexts();

   }
   static UdpHolePuncher removeHolePuncher(ConnectionManager cm, String key) {

      return cm.activeHolePunchers.remove(key);

   }
   static void sendHolepunchOffer(ConnectionManager cm, RoomManager.RoomState state, String from) {

      cm.connectionWon.set(false);

      JsonObject offerData = new JsonObject();

      if (state.roomInfo.getHostIp() != null && !state.roomInfo.getHostIp().isEmpty()) {

         offerData.addProperty("hostIp", state.roomInfo.getHostIp());

      }



      if (state.roomInfo.getHostIpv6() != null && !state.roomInfo.getHostIpv6().isEmpty()) {

         offerData.addProperty("hostIpv6", state.roomInfo.getHostIpv6());

      }



      int bridgePort = P2PBridge.getHostPort();

      int connectPort = bridgePort > 0 ? bridgePort : state.roomInfo.getHostPort();

      offerData.addProperty("hostPort", connectPort);

      String localIp = StunDetector.getLocalIpAddress();

      if (localIp != null && !localIp.isEmpty()) {

         offerData.addProperty("hostLocalIp", localIp);

         state.roomInfo.setHostLocalIp(localIp);

         VoxLinkMod.LOGGER.info("[RoomManager] Include host LAN IP: {}", localIp);

      }



      UdpHolePuncher hostPuncher = cm.activeHolePunchers.get("host");

      if (hostPuncher != null && hostPuncher.getSocket() != null && !hostPuncher.getSocket().isClosed()) {

         hostPuncher.stopPunch();

         VoxLinkMod.LOGGER.info("[RoomManager] Reuse existing host punch socket (localPort={})", hostPuncher.getSocket().getLocalPort());

      } else {

         hostPuncher = new UdpHolePuncher();

         cm.applyPunchTemplate(hostPuncher);



         try {

            int mcPort = state.roomInfo.getHostPort();

            hostPuncher.createSocket(mcPort);

            cm.activeHolePunchers.put("host", hostPuncher);

         } catch (Exception e) {

            try {

               hostPuncher.createSocket();

               cm.activeHolePunchers.put("host", hostPuncher);

            } catch (Exception e2) {

               VoxLinkMod.LOGGER.warn("[RoomManager] Create host punch socket failed: {}", e2.getMessage());

               hostPuncher = null;

            }

         }

      }



      String natType = state.roomInfo.getNatType();

      boolean isSymmetricOrUnknown = StunDetector.isNatTypeSymmetric(natType);

      if (cm.stunProbeResult != null && (state.roomInfo.getClientId() != null || state.roomInfo.isHost())) {

         String hostMappedIp = null;

         int hostMappedPort = 0;



         for (StunProbe.StunServerResult sr : cm.stunProbeResult.serverResults) {

            if (sr.reachable && sr.mappedIp != null && sr.mappedPort > 0) {

               hostMappedIp = sr.mappedIp;

               hostMappedPort = sr.mappedPort;

               break;

            }

         }



         if (hostMappedIp != null && hostMappedPort > 0) {

            boolean relayOk = VoxLinkMod.getConfig().isRelayEnabled();

            cm.signalingClient

               .registerRelayPeer(

                  cm.relayPeerId(state), state.roomInfo.getCode(), cm.stunProbeResult.natType.key, hostMappedIp, hostMappedPort, relayOk

               );

            cm.scheduleRelayRegistrationRenewal(state, cm.stunProbeResult.natType.key, hostMappedIp, hostMappedPort);

         }

      }



      UdpHolePuncher fHostPuncher = hostPuncher;

      boolean fIsSymmetricOrUnknown = isSymmetricOrUnknown;

      String fNatType = natType;

      JsonObject fOfferData = offerData;

      RoomManager.RoomState fState = state;

      String fFrom = from;

      int fConnectPort = connectPort;

      CompletableFuture.<Object[]>supplyAsync(() -> {

            StunProbe.PublicMappedAddress m1 = null;

            StunProbe.PublicMappedAddress m2 = null;

            List<StunProbe.PublicMappedAddress> birthdayAddrs = null;

            if (fHostPuncher != null) {

               try {

                  List<String> allStun = cm.stunUrlsProbedFirst();

                  VoxLinkMod.LOGGER.info("[RoomManager] Host NAT: {} — 8 concurrent STUN ({} servers)", fNatType != null ? fNatType : "null", allStun.size());

                  StunProbe.PublicMappedAddress[] top2 = StunProbe.discoverMappedAddressRace(fHostPuncher.getSocket(), allStun, 2);

                  m1 = top2[0];

                  m2 = top2[1];

               } catch (Exception e) {

                  VoxLinkMod.LOGGER.warn("[RoomManager] Punch socket STUN failed: {}", e.getMessage());

               }



               if (fIsSymmetricOrUnknown && m1 != null && m2 != null) {

                  int birthdayCount = cm.punchProfile().hardSymSocketCount;

                  VoxLinkMod.LOGGER.info("[RoomManager] Symmetric NAT, pre-create {} birthday sockets into holepunch_offer", birthdayCount);

                  birthdayAddrs = new ArrayList<>();

                  List<CompletableFuture<StunProbe.PublicMappedAddress>> bFutures = new ArrayList<>();



                  for (int i = 0; i < birthdayCount; i++) {

                     int idx = i;

                     bFutures.add(CompletableFuture.supplyAsync(() -> {

                        UdpHolePuncher bp = new UdpHolePuncher();

                        cm.applyPunchTemplate(bp);



                        try {

                           bp.createSocket();

                        } catch (Exception e) {

                           return null;

                        }



                        StunProbe.PublicMappedAddress[] race = StunProbe.discoverMappedAddressRace(bp.getSocket(), cm.stunUrlsProbedFirst(), 1);

                        StunProbe.PublicMappedAddress addr = race[0];

                        if (addr != null) {

                           String key = "host_birthday_" + idx;

                           cm.activeHolePunchers.put(key, bp);

                           return addr;

                        }



                        try {

                           bp.close();

                        } catch (Exception var6x) {

                        }



                        return null;

                     }));

                  }



                  try {

                     CompletableFuture.allOf(bFutures.toArray(new CompletableFuture[0])).get(5L, TimeUnit.SECONDS);

                  } catch (Exception e) {

                     VoxLinkMod.LOGGER.warn("[RoomManager] Birthday socket create partial timeout: {}", e.getMessage());

                  }



                  for (CompletableFuture<StunProbe.PublicMappedAddress> f : bFutures) {

                     try {

                        StunProbe.PublicMappedAddress a = f.getNow(null);

                        if (a != null) {

                           birthdayAddrs.add(a);

                        }

                     } catch (Exception var12) {

                     }

                  }



                  VoxLinkMod.LOGGER.info("[RoomManager] Pre-create {} birthday sockets done, {} valid", birthdayCount, birthdayAddrs.size());

               }

            }



            return new Object[]{m1, m2, birthdayAddrs};

         })

         .thenAccept(

            result -> {

               StunProbe.PublicMappedAddress mapped1 = (StunProbe.PublicMappedAddress)result[0];

               StunProbe.PublicMappedAddress mapped2 = (StunProbe.PublicMappedAddress)result[1];

               List<StunProbe.PublicMappedAddress> birthdayPorts = (List<StunProbe.PublicMappedAddress>)result[2];

               StunProbe.PublicMappedAddress mapped = null;
               boolean punchSocketSymmetric = false;
               boolean symOrUnknown = fIsSymmetricOrUnknown;
               if (mapped1 != null && mapped2 != null) {
                  boolean sameFamily = cm.sameIpFamily(mapped1.ip(), mapped2.ip());
                  if (sameFamily && mapped1.port() != mapped2.port()) {
                     punchSocketSymmetric = true;
                     VoxLinkMod.LOGGER.info("[RoomManager] Punch socket STUN: symmetric NAT ({} vs {})", mapped1.port(), mapped2.port());
                  } else if (sameFamily && symOrUnknown && !StunDetector.isNatTypeSymmetric(fNatType)) {
                     VoxLinkMod.LOGGER.info("[RoomManager] Punch socket STUN: same port {}, override isSymmetricOrUnknown (was {})", mapped1.port(), fNatType);
                     symOrUnknown = false;
                  } else if (!sameFamily) {
                     VoxLinkMod.LOGGER.info("[RoomManager] Punch socket STUN: mixed IP family ({} vs {}), skip symmetric compare", mapped1.ip(), mapped2.ip());
                  }

                  mapped = mapped1.ip().contains(":") != mapped2.ip().contains(":")
                     ? (mapped1.ip().contains(":") ? mapped2 : mapped1)
                     : mapped2;
               } else {
                  mapped = mapped1 != null ? mapped1 : mapped2;
               }




               if (punchSocketSymmetric) {

                  symOrUnknown = true;

               }



               if (mapped != null) {

                  fOfferData.addProperty("hostMappedIp", mapped.ip());

                  fOfferData.addProperty("hostMappedPort", mapped.port());

               } else if (cm.stunProbeResult != null && !cm.stunProbeResult.serverResults.isEmpty()) {

                  for (StunProbe.StunServerResult sr : cm.stunProbeResult.serverResults) {

                     if (sr.reachable && sr.mappedIp != null && sr.mappedPort > 0) {

                        fOfferData.addProperty("hostMappedIp", sr.mappedIp);

                        fOfferData.addProperty("hostMappedPort", sr.mappedPort);

                        mapped = new StunProbe.PublicMappedAddress(sr.mappedIp, sr.mappedPort);

                        VoxLinkMod.LOGGER.info("[RoomManager] MC port STUN failed, fallback to NAT probe mapped address: {}:{}", sr.mappedIp, sr.mappedPort);

                        break;

                     }

                  }

               }



               if (mapped != null) {

                  if (symOrUnknown) {

                     fOfferData.addProperty("hostSymmetric", true);

                  }



                  boolean hostEasySym = punchSocketSymmetric

                     && mapped1 != null

                     && mapped2 != null

                     && Math.abs(mapped2.port() - mapped1.port()) <= 100;

                  if (hostEasySym) {

                     fOfferData.addProperty("hostEasySym", true);

                  }



                  if (punchSocketSymmetric) {
                     int delta = mapped2.port() - mapped1.port();

                     int portRange = cm.punchProfile().maxPortRange;

                     if (fHostPuncher != null && fHostPuncher.getSocket() != null) {

                        List<Integer> samples = StunProbe.samplePortsSequential(fHostPuncher.getSocket(), cm.stunUrlsProbedFirst(), 10, 100);

                        if (samples.size() >= 5) {

                           PortPredictor.PredictResult pr = PortPredictor.predict(samples);

                           int reliableDelta = PortPredictor.deltaPredict(samples) - samples.get(samples.size() - 1);

                           if (reliableDelta <= 0) {

                              reliableDelta = cm.calculatePortDelta(samples);

                           }



                           portRange = pr.range;

                           VoxLinkMod.LOGGER

                              .info(

                                 "[RoomManager] P-PRE samples: {} times -> sequence={}, combined predict port={}, range=±{}, delta={}",

                                 new Object[]{samples.size(), samples, pr.predictedPort, pr.range, reliableDelta}

                              );

                           delta = reliableDelta;

                        } else {

                           VoxLinkMod.LOGGER.warn("[RoomManager] P-PRE insufficient samples ({}), fallback to 2-sample delta={}", samples.size(), delta);

                        }

                     }



                     fOfferData.addProperty("hostMappedPortDelta", delta);

                     fOfferData.addProperty("hostMappedPortRange", portRange);

                     VoxLinkMod.LOGGER.info("[RoomManager] Symmetric NAT port delta: delta={}, range=±{}", delta, portRange);

                  }



                  fState.roomInfo.setHostMappedAddress(mapped.ip(), mapped.port());

                  fState.roomInfo.setHostEasySym(hostEasySym);

                  VoxLinkMod.LOGGER

                     .info(

                        "[RoomManager] Punch socket STUN: ip={}, port={}, symmetric={}, easySym={}",

                        new Object[]{mapped.ip(), mapped.port(), symOrUnknown, hostEasySym}

                     );

               }



               if (birthdayPorts != null && !birthdayPorts.isEmpty()) {

                  JsonArray portsArr = new JsonArray();



                  for (StunProbe.PublicMappedAddress bp : birthdayPorts) {

                     portsArr.add(bp.port());

                  }



                  fOfferData.add("hostBirthdayPorts", portsArr);

                  VoxLinkMod.LOGGER.info("[RoomManager] holepunch_offer with {} birthday ports", birthdayPorts.size());

               }



               long syncTime = System.currentTimeMillis() + 3000L;

               fOfferData.addProperty("punchSyncTimeMs", syncTime);

               fOfferData.addProperty("punchSyncSentAtMs", System.currentTimeMillis());

               fState.roomInfo.setPunchSyncSentAtMs(System.currentTimeMillis());

               fState.roomInfo.setPunchSyncTimeMs(syncTime);

               VoxLinkMod.LOGGER.info("[RoomManager] RTT sync: punchSyncTimeMs={}", syncTime);

               VoxLinkMod.LOGGER

                  .info(

                     "[RoomManager] Send holepunch_offer to {} (hostIp={}, hostIpv6={}, port={}, mappedIp={}, mappedPort={})",

                     new Object[]{

                        fFrom,

                        fState.roomInfo.getHostIp() != null ? fState.roomInfo.getHostIp() : "none",

                        fState.roomInfo.getHostIpv6() != null ? fState.roomInfo.getHostIpv6() : "none",

                        fConnectPort,

                        mapped != null ? mapped.ip() : "none",

                        mapped != null ? mapped.port() : 0

                     }

                  );

               cm.signalingClient

                  .sendSignal(fState.roomInfo.getCode(), fState.roomInfo.getToken(), true, "holepunch_offer", fOfferData, fFrom)

                  .thenAccept(response -> {

                     if (!response.success) {

                        VoxLinkMod.LOGGER.error("[RoomManager] Send holepunch_offer failed: {} - {}", response.error, response.message);

                     }

                  })

                  .exceptionally(e -> {

                     VoxLinkMod.LOGGER.error("[RoomManager] Send holepunch_offer network error: {}", e.getMessage());

                     return null;

                  });

               String waitClientId = fFrom;

               cm.scheduler.schedule(() -> {
                  HostPunchContext waitCtx = cm.hostPunchContexts.get(waitClientId);
                  if ((waitCtx == null || !waitCtx.punching) && cm.activeHolePunchers.containsKey("host")) {
                     UdpHolePuncher hp = cm.activeHolePunchers.remove("host");
                     if (hp != null) {
                        try {
                           hp.close();
                        } catch (Exception var4x) {
                        }
                     }

                     String waitPrefix = cm.peerPunchPrefix(waitClientId);
                     cm.activeHolePunchers.entrySet().removeIf(e -> e.getKey().startsWith(waitPrefix));
                     cm.hostPunchContexts.remove(waitClientId);

                     VoxLinkMod.LOGGER.info("[RoomManager] Wait punch_info timeout ({}s), cleanup host socket client={}", 15, waitClientId);

                  }

               }, 15L, TimeUnit.SECONDS);

            }

         );

   }
   static void handleHolePunchOffer(ConnectionManager cm, String from, JsonObject data) {

      RoomManager.RoomState state = cm.roomManager.currentRoom.get();

      VoxLinkMod.LOGGER.info("[RoomManager] Received holepunch_offer, state={}", state != null && state != RoomManager.PENDING ? "active" : "null/pending");

      // 陈旧信号守卫：会话已终态(断开/失败)且无活跃连接周期时，丢弃迟到的holepunch_offer，
      // 避免把已关闭的会话重新唤醒造成"Invalid state transition ... 326991ms"

      ConnectionState currentStateNow = ConnectionState.getCurrent();

      if ((currentStateNow == ConnectionState.DISCONNECTED || currentStateNow == ConnectionState.FAILED)
         && !cm.connectionCycleActive.get()
         && !ConnectionHelper.isConnecting()) {

         VoxLinkMod.LOGGER
            .warn(
               "[handleHolePunchOffer] Drop stale offer from={} in terminal state {} (stateAge={}ms)",
               new Object[]{from, currentStateNow, ConnectionState.getStateDurationMs()}
            );

         return;

      }

      // 封顶生效期: 不再重启打洞波次, 让位中继/TURN
      if (cm.sessionPredictionOffCount.get() >= ConnectionManager.PREDICTION_OFF_CAP && !cm.sessionPunchRecvEver.get()
         && !cm.connectionWon.get()) {
         VoxLinkMod.LOGGER.warn("[handleHolePunchOffer] PREDICTION_OFF cap active ({}), drop offer (relay/TURN takes over)", cm.sessionPredictionOffCount.get());
         return;
      }
      if (state != null && state != RoomManager.PENDING && !state.roomInfo.isHost()) {

         // punchAuthV1：房主声明了能力才启用打洞/数据面 MAC 认证（joiner 视角）

         cm.setupPunchAuthForPeer(state, null, true);

         if (cm.voxlinkSideDisabled || cm.terracottaWon) {

            VoxLinkMod.LOGGER.info("[DualP2P] VoxLink disabled or Terracotta won, ignore holepunch_offer");

         } else if (cm.connectionWon.get() && P2PBridge.isRunning()) {

            VoxLinkMod.LOGGER.info("[RoomManager] Already connected, ignore holepunch_offer");

         } else if (ConnectionHelper.isConnecting() && cm.connectionCycleActive.get()) {

            VoxLinkMod.LOGGER.info("[RoomManager] Already connecting with active cycle, ignore holepunch_offer");

         } else if (!cm.connectionCycleActive.compareAndSet(false, true)) {

            VoxLinkMod.LOGGER.info("[RoomManager] Connection cycle in progress, ignore duplicate holepunch_offer");

         } else {

            cm.connectionWon.set(false);

            cm.scheduleConnectionCycleSafety(state);

            if (P2PBridge.isRunning()) {

               int existingPort = P2PBridge.getJoinerPort();

               String bridgeHostIp = data.has("hostIp") && !data.get("hostIp").isJsonNull() ? data.get("hostIp").getAsString() : state.roomInfo.getHostIp();

               if (existingPort > 0 && P2PBridge.isTargetMatch(bridgeHostIp, state.roomInfo.getHostPort())) {

                  VoxLinkMod.LOGGER.info("[RoomManager] Bridge running with same target, ignore duplicate holepunch_offer");

                  cm.connectionCycleActive.set(false);

                  ConnectionHelper.resetConnecting();

                  return;

               }



               P2PBridge.disconnect();

            }



            String hostIp = null;

            if (data.has("hostIp") && !data.get("hostIp").isJsonNull() && !data.get("hostIp").getAsString().isEmpty()) {

               hostIp = data.get("hostIp").getAsString();

            }



            String hostIpv6 = null;

            int connectPort = state.roomInfo.getHostPort();

            if (data.has("hostPort") && !data.get("hostPort").isJsonNull()) {

               connectPort = data.get("hostPort").getAsInt();

            }



            if (data.has("hostIpv6") && !data.get("hostIpv6").isJsonNull() && !data.get("hostIpv6").getAsString().isEmpty()) {

               hostIpv6 = data.get("hostIpv6").getAsString();

            }



            // 地址族过滤：本端无全局IPv6时丢弃host发布的IPv6候选，避免朝不可达地址空转47轮(QWA6D3/S822QF)

            if (hostIpv6 != null && cm.filterAddressFamilyCandidate(hostIpv6, connectPort, "holepunch_offer:hostIpv6") == null) {

               VoxLinkMod.LOGGER.warn("[handleHolePunchOffer] 忽略 1 个不可达 IPv6 候选(hostIpv6)，本端无全局IPv6");

               hostIpv6 = null;

            }



            String hostMappedIp = null;

            int hostMappedPort = 0;

            if (data.has("hostMappedIp") && !data.get("hostMappedIp").isJsonNull()) {

               hostMappedIp = data.get("hostMappedIp").getAsString();

            }



            if (data.has("hostMappedPort") && !data.get("hostMappedPort").isJsonNull()) {

               hostMappedPort = data.get("hostMappedPort").getAsInt();

            }



            if (hostMappedIp != null && !hostMappedIp.isEmpty() && hostMappedPort > 0) {

               state.roomInfo.setHostMappedAddress(hostMappedIp, hostMappedPort);

               VoxLinkMod.LOGGER.info("[RoomManager] Update roomInfo hostMapped={}:{}", hostMappedIp, hostMappedPort);

            }



            String hostLocalIp = null;

            if (data.has("hostLocalIp") && !data.get("hostLocalIp").isJsonNull()) {

               hostLocalIp = data.get("hostLocalIp").getAsString();

               state.roomInfo.setHostLocalIp(hostLocalIp);

               VoxLinkMod.LOGGER.info("[RoomManager] Host provided LAN IP: {}", hostLocalIp);

            }



            String finalHostIp = hostIp;

            String finalHostIpv6 = hostIpv6;

            int finalHostPort = connectPort;

            String finalHostMappedIp = hostMappedIp;

            int finalHostMappedPort = hostMappedPort;

            boolean finalHostSymmetric = data.has("hostSymmetric") && !data.get("hostSymmetric").isJsonNull() && data.get("hostSymmetric").getAsBoolean();

            boolean finalHostEasySym = data.has("hostEasySym") && !data.get("hostEasySym").isJsonNull() && data.get("hostEasySym").getAsBoolean();

            int finalHostMappedPortDelta = data.has("hostMappedPortDelta") && !data.get("hostMappedPortDelta").isJsonNull()

               ? data.get("hostMappedPortDelta").getAsInt()

               : 0;

            int finalHostMappedPortRange = data.has("hostMappedPortRange") && !data.get("hostMappedPortRange").isJsonNull()

               ? data.get("hostMappedPortRange").getAsInt()

               : 100;

            if (finalHostMappedPortRange != 100) {

               state.roomInfo.setHostMappedPortRange(finalHostMappedPortRange);

            }



            long punchSyncTime = data.has("punchSyncTimeMs") ? data.get("punchSyncTimeMs").getAsLong() : 0L;

            if (punchSyncTime > 0L) {

               state.roomInfo.setPunchSyncTimeMs(punchSyncTime);

               VoxLinkMod.LOGGER.info("[RoomManager] RTT sync: punchSyncTimeMs={} ({}ms ago)", punchSyncTime, punchSyncTime - System.currentTimeMillis());

            }



            if (data.has("punchSyncSentAtMs")) {

               state.roomInfo.setJoinerOfferRecvMs(System.currentTimeMillis());

            }



            List<Integer> hostBirthdayPorts = null;

            if (data.has("hostBirthdayPorts") && data.get("hostBirthdayPorts").isJsonArray()) {

               hostBirthdayPorts = new ArrayList<>();



               for (JsonElement elem : data.getAsJsonArray("hostBirthdayPorts")) {

                  hostBirthdayPorts.add(elem.getAsInt());

               }



               VoxLinkMod.LOGGER.info("[RoomManager] holepunch_offer contains {} birthday ports", hostBirthdayPorts.size());

            }



            List<Integer> fHostBirthdayPorts = hostBirthdayPorts;

            if (hostIp != null && !hostIp.isEmpty()) {

               state.roomInfo.setHostIp(hostIp);

            }



            if (hostIpv6 != null && !hostIpv6.isEmpty()) {

               state.roomInfo.setHostIpv6(hostIpv6);

            }



            if (connectPort > 0) {

               state.roomInfo.setHostConnectPort(connectPort);

            }



            if (finalHostEasySym) {

               state.roomInfo.setHostEasySym(true);

            }



            if (fHostBirthdayPorts != null) {

               state.roomInfo.setHostBirthdayPorts(fHostBirthdayPorts);

            }



            CompletableFuture<StunProbe.ProbeResult> probeFuture = cm.stunProbeFutureRef.get();

            if (probeFuture != null && cm.stunProbeResult == null && !probeFuture.isDone()) {

               RoomManager.RoomState fState = state;

               String fFrom = from;

               String fIpv6 = finalHostIpv6;

               String fIp = finalHostIp;

               int fPort = finalHostPort;

               String fMappedIp = finalHostMappedIp;

               int fMappedPort = finalHostMappedPort;

               boolean fSym = finalHostSymmetric;

               int fDelta = finalHostMappedPortDelta;

               probeFuture.thenAccept(

                  result -> {

                     cm.stunProbeResult = result;

                     cm.applyProbeResultToActiveConnection(fState, fIp);

                     cm.extendConnectionTimeoutIfNeeded(fState);

                  }

               );

            }



            cm.finishHandleHolePunchOffer(

               state, from, finalHostIpv6, finalHostIp, finalHostPort, finalHostMappedIp, finalHostMappedPort, finalHostSymmetric, finalHostMappedPortDelta

            );

         }

      }

   }
   static void finishHandleHolePunchOffer(ConnectionManager cm, 

      RoomManager.RoomState state,

      String from,

      String finalHostIpv6,

      String finalHostIp,

      int finalHostPort,

      String finalHostMappedIp,

      int finalHostMappedPort,

      boolean finalHostSymmetric,

      int finalHostMappedPortDelta

   ) {

      if (cm.stunProbeResult != null) {

         if (cm.stunProbeResult.natType.isSymmetric()) {

            state.roomInfo.setConnectionMode(Text.translatable("voxlink.connection.probing"));

         }



         VoxLinkMod.LOGGER

            .info(

               "[handleHolePunchOffer] Use probe result: NAT={}, reachable STUN={}",

               cm.stunProbeResult.natType.key,

               cm.stunProbeResult.reachableStunUrls.size()

            );

         String clientPublicIp = null;



         for (StunProbe.StunServerResult r : cm.stunProbeResult.serverResults) {

            if (r.reachable && r.mappedIp != null) {

               clientPublicIp = r.mappedIp;

               break;

            }

         }



         if (clientPublicIp != null && finalHostIp != null && clientPublicIp.equals(finalHostIp)) {

            VoxLinkMod.LOGGER.warn("[handleHolePunchOffer] Same public IP ({}): both behind same CGNAT, P2P direct unlikely", clientPublicIp);

            state.roomInfo.setSameCgnat(true);

         }



         if (clientPublicIp != null) {

            int clientMappedPort = 0;



            for (StunProbe.StunServerResult r : cm.stunProbeResult.serverResults) {

               if (r.reachable && r.mappedPort > 0) {

                  clientMappedPort = r.mappedPort;

                  break;

               }

            }



            state.roomInfo.setMyMappedIp(clientPublicIp);

            state.roomInfo.setMyMappedPort(clientMappedPort);

         }

      }



      if (finalHostSymmetric) {

         state.roomInfo.setHostSymmetric(true);

      }



      if (finalHostMappedPortDelta != 0) {

         state.roomInfo.setHostMappedPortDelta(finalHostMappedPortDelta);

      }



      String effectiveMappedIp = finalHostMappedIp;

      int effectiveMappedPort = finalHostMappedPort;

      if (effectiveMappedIp == null || effectiveMappedPort <= 0) {

         String cachedMappedIp = state.roomInfo.getHostMappedIp();

         int cachedMappedPort = state.roomInfo.getHostMappedPort();

         if (cachedMappedIp != null && cachedMappedPort > 0) {

            effectiveMappedIp = cachedMappedIp;

            effectiveMappedPort = cachedMappedPort;

            VoxLinkMod.LOGGER.info("[handleHolePunchOffer] Use first holepunch_mapped: {}:{}", effectiveMappedIp, effectiveMappedPort);

         }

      }



      if (effectiveMappedIp != null && effectiveMappedPort > 0) {

         cm.runConnectionCycle(state, from, finalHostIpv6, finalHostIp, finalHostPort, effectiveMappedIp, effectiveMappedPort, 0);

      } else {

         cm.connectionCycleActive.set(false);

         VoxLinkMod.LOGGER.info("[handleHolePunchOffer] offer has no mapped address, wait for holepunch_mapped...");

         cm.scheduler.schedule(() -> {

            if (cm.connectionCycleActive.compareAndSet(false, true)) {

               String mappedIp = state.roomInfo.getHostMappedIp();

               int mappedPort = state.roomInfo.getHostMappedPort();

               if (mappedIp == null || mappedPort <= 0) {

                  VoxLinkMod.LOGGER.warn("[handleHolePunchOffer] holepunch_mapped timeout (12s), start without mapped address");

                  cm.runConnectionCycle(state, from, finalHostIpv6, finalHostIp, finalHostPort, null, 0, 0);

               }

            } else {

               VoxLinkMod.LOGGER.debug("[handleHolePunchOffer] CAS failed, new offer in progress");

            }

         }, 12L, TimeUnit.SECONDS);

      }

   }
   static void handleHolepunchMapped(ConnectionManager cm, String from, JsonObject data) {
      // 封顶生效期: 不再重启打洞波次, 让位中继/TURN
      if (cm.sessionPredictionOffCount.get() >= ConnectionManager.PREDICTION_OFF_CAP && !cm.sessionPunchRecvEver.get()
         && !cm.connectionWon.get()) {
         VoxLinkMod.LOGGER.warn("[handleHolepunchMapped] PREDICTION_OFF cap active ({}), drop signal (relay/TURN takes over)", cm.sessionPredictionOffCount.get());
         return;
      }

      RoomManager.RoomState state = cm.roomManager.currentRoom.get();

      if (state != null && state != RoomManager.PENDING && !state.roomInfo.isHost()) {

         String hostMappedIp = null;

         int hostMappedPort = 0;

         if (data.has("hostMappedIp") && !data.get("hostMappedIp").isJsonNull()) {

            hostMappedIp = data.get("hostMappedIp").getAsString();

         }



         if (data.has("hostMappedPort") && !data.get("hostMappedPort").isJsonNull()) {

            hostMappedPort = data.get("hostMappedPort").getAsInt();

         }



         boolean hostSymmetric = data.has("hostSymmetric") && !data.get("hostSymmetric").isJsonNull() && data.get("hostSymmetric").getAsBoolean();

         boolean hostEasySym = data.has("hostEasySym") && !data.get("hostEasySym").isJsonNull() && data.get("hostEasySym").getAsBoolean();

         int hostMappedPortDelta = data.has("hostMappedPortDelta") && !data.get("hostMappedPortDelta").isJsonNull()

            ? data.get("hostMappedPortDelta").getAsInt()

            : 0;

         int hostMappedPortRange = data.has("hostMappedPortRange") && !data.get("hostMappedPortRange").isJsonNull()

            ? data.get("hostMappedPortRange").getAsInt()

            : 100;

         List<Integer> hostMappedPorts = new ArrayList<>();

         boolean samePorts = false;

         if (data.has("hostMappedPorts") && data.get("hostMappedPorts").isJsonArray()) {

            for (JsonElement elem : data.getAsJsonArray("hostMappedPorts")) {

               hostMappedPorts.add(elem.getAsInt());

            }

         }



         if (hostMappedIp != null && (hostMappedPort > 0 || !hostMappedPorts.isEmpty())) {

            if (hostMappedPorts.isEmpty()) {

               hostMappedPorts.add(hostMappedPort);

            }



            samePorts = cm.lastHostMappedPorts != null && cm.lastHostMappedPorts.equals(hostMappedPorts);

            if (samePorts) {

               VoxLinkMod.LOGGER.debug("[RoomManager] host mapped ports unchanged, skip rebuild");

            }



            VoxLinkMod.LOGGER

               .info(

                  "[RoomManager] Received host mapped: {}:{} ports={} (sym={}, delta={})",

                  new Object[]{hostMappedIp, hostMappedPort, hostMappedPorts, hostSymmetric, hostMappedPortDelta}

               );

            state.roomInfo.setHostMappedAddress(hostMappedIp, hostMappedPort);

            cm.lastHostMappedPorts = new ArrayList<>(hostMappedPorts);

            if (hostSymmetric) {
               state.roomInfo.setHostSymmetric(true);
               NatClass newRemote = hostEasySym ? NatClass.EASY_SYM : NatClass.HARD_SYM;
               cm.applyRemoteNatClass(newRemote, "host_symmetric");
               VoxLinkMod.LOGGER.info("[handleHolepunchMapped] Update remoteNat {} -> {} (sym=true)", NatClass.CONE, newRemote);

               ScenarioTier.Tier prevTier = cm.scenarioTier;

               int rReach = cm.stunProbeResult != null ? cm.stunProbeResult.reachableStunUrls.size() : 0;

               cm.scenarioTier = ScenarioTier.classify(

                  cm.localNatClass.isSymmetric(), cm.remoteNatClass.isSymmetric(),

                  rReach > 0 && rReach <= 3, false, false);

               if (cm.scenarioTier != prevTier) {

                  VoxLinkMod.LOGGER.info("[handleHolepunchMapped] tier {} -> {} (host symmetric known)", ScenarioTier.key(prevTier), ScenarioTier.key(cm.scenarioTier));

               }

               PunchProfile recommended = NatClass.recommendProfile(cm.localNatClass, cm.remoteNatClass);

               if (recommended != cm.punchProfile()) {

                  cm.switchPunchProfile(recommended, "nat_matrix_" + cm.localNatClass + "x" + cm.remoteNatClass + "_after_mapped");

               }

            }



            if (hostEasySym) {

               state.roomInfo.setHostEasySym(true);

            }



            if (hostMappedPortDelta != 0) {

               state.roomInfo.setHostMappedPortDelta(hostMappedPortDelta);

            }



            if (hostMappedPortRange != 100) {

               state.roomInfo.setHostMappedPortRange(hostMappedPortRange);

            }



            if (hostMappedIp != null && hostMappedPort > 0) {

               String hostNatType = hostSymmetric ? (hostEasySym ? "symmetric_easy_inc" : "symmetric") : "full_cone";

               state.roomInfo.addOrUpdatePeer(from, hostNatType, hostMappedIp, hostMappedPort);

            }



            if (data.has("hostLocalIp") && !data.get("hostLocalIp").isJsonNull()) {

               String receivedHostLocalIp = data.get("hostLocalIp").getAsString();

               if (receivedHostLocalIp != null && !receivedHostLocalIp.isEmpty()) {

                  String existingLocalIp = state.roomInfo.getHostLocalIp();

                  if (existingLocalIp == null || existingLocalIp.isEmpty()) {

                     state.roomInfo.setHostLocalIp(receivedHostLocalIp);

                     VoxLinkMod.LOGGER.info("[handleHolepunchMapped] Received host LAN IP: {}", receivedHostLocalIp);

                     if (cm.connectionCycleActive.get() && state.roomInfo.isSameCgnat() && !cm.connectionWon.get()) {

                        int connectPort = state.roomInfo.getHostConnectPort() > 0 ? state.roomInfo.getHostConnectPort() : state.roomInfo.getHostPort();

                        int mcPort = state.roomInfo.getHostPort();

                        VoxLinkMod.LOGGER.info("[handleHolepunchMapped] CGNAT: also try hostLocalIp {}:{}", receivedHostLocalIp, connectPort);

                        ConnectionFallback localFallback = cm.trackFallback(new ConnectionFallback());

                        localFallback.tryIpv4Direct(receivedHostLocalIp, connectPort).thenAccept(result -> {

                           if (cm.roomManager.currentRoom.get() == state && result.success && cm.connectionWon.compareAndSet(false, true)) {

                              VoxLinkMod.LOGGER.info("[handleHolepunchMapped] CGNAT hostLocalIp direct connect won");

                              cm.connectViaBridge(state, result);

                           }

                        });

                        ConnectionFallback mcLocalFallback = cm.trackFallback(new ConnectionFallback());

                        mcLocalFallback.tryIpv4Direct(receivedHostLocalIp, mcPort).thenAccept(result -> {

                           if (cm.roomManager.currentRoom.get() == state && result.success && cm.connectionWon.compareAndSet(false, true)) {

                              VoxLinkMod.LOGGER.info("[handleHolepunchMapped] CGNAT hostLocalIp MC port won");

                              cm.connectViaBridge(state, result);

                           }

                        });

                     }

                  }

               }

            }



            UdpHolePuncher joinerPuncher = cm.activeHolePunchers.get("joiner");

            if (joinerPuncher != null && cm.connectionCycleActive.get()) {

               int updatePort = hostMappedPorts.get(0);

               VoxLinkMod.LOGGER

                  .info(

                     "[RoomManager] Punch host mapped ports raw={} (delta={} skipped: 同socket双STUN差值非跨socket步长), target={}:{}",

                     new Object[]{hostMappedPorts, hostMappedPortDelta, hostMappedIp, updatePort}

                  );

               joinerPuncher.updateTarget(hostMappedIp, updatePort);

               if (!samePorts && hostMappedPorts.size() > 1) {

                  List<String> staleExtra = new ArrayList<>();



                  for (String k : cm.activeHolePunchers.keySet()) {

                     if (k.startsWith("joiner_extra_")) {

                        staleExtra.add(k);

                     }

                  }



                  if (!staleExtra.isEmpty()) {

                     VoxLinkMod.LOGGER.info("[RoomManager] Drift resync: remove {} stale extra punchers, rebuild with latest ports", staleExtra.size());



                     for (String k : staleExtra) {

                        UdpHolePuncher sp = cm.activeHolePunchers.remove(k);

                        if (sp != null) {

                           try {

                              sp.cancel();

                              sp.close();

                           } catch (Exception var22) {

                           }

                        }

                     }

                  }

               }



               // 同CGNAT回环命中率低, 加密生日端口覆盖
               int maxExtraCap = state.roomInfo.isSameCgnat() ? 12 : 6;
               int maxExtra = Math.min(hostMappedPorts.size(), maxExtraCap);



               for (int i = 1; i < maxExtra; i++) {

                  int fIdx = i;

                  int extraPort = hostMappedPorts.get(i);

                  String key = "joiner_extra_" + fIdx;

                  if (!cm.activeHolePunchers.containsKey(key)) {

                     UdpHolePuncher extraPuncher = new UdpHolePuncher();

                     cm.applyPunchTemplate(extraPuncher);



                     try {

                        extraPuncher.createSocket();

                        cm.activeHolePunchers.put(key, extraPuncher);

                        int fExtraPort = extraPort;

                        String fHostMappedIp = hostMappedIp;

                        VoxLinkMod.LOGGER.info("[RoomManager] Multi-port puncher#{}: {}:{}", new Object[]{i, fHostMappedIp, fExtraPort});

                        int extraRange = cm.punchProfile().joinerMultiPortRange;

                        extraPuncher.punchWithPortPrediction(fHostMappedIp, fExtraPort, extraRange)

                           .thenAccept(

                              result -> {

                                 if (!result.isSuccess()) {

                                    PunchFailureClassifier.FailureReason reason = PunchFailureClassifier.classify(result);

                                    cm.lastPunchResult = result.withReason(reason);

                                    cm.activePunchParams = 

                                       PunchTuner.nextParams(

                                          cm.punchProfile(), cm.localNatClass, cm.remoteNatClass, 1, 8, reason, cm.lastPunchResult

                                       )

                                    ;

                                    cm.recordPunchFailure(reason, result.socketsReceivedPunch, result.socketsReceivedAck);

                                 } else {

                                    DatagramSocket socket = result.getSuccessSocket();

                                    if (!cm.connectionWon.compareAndSet(false, true)) {

                                       try {

                                          extraPuncher.close();

                                       } catch (Exception var9x) {

                                       }

                                    } else {

                                       extraPuncher.markSocketTransferred();

                                       cm.stopAllPunchingAfterHostBridge();

                                       extraPuncher.stopPunch();

                                       DatagramSocket winSocket = socket;

                                       UdpHolePuncher winPuncher = extraPuncher;

                                       cm.scheduler

                                          .submit(

                                             () -> {

                                                try {

                                                   cm.establishUdpTransport(

                                                      state, winSocket, winPuncher, new InetSocketAddress(fHostMappedIp, fExtraPort), "joiner", false, null

                                                   );

                                                } catch (Exception ex) {

                                                   VoxLinkMod.LOGGER.error("[RoomManager] Multi-port transport failed: {}", ex.getMessage());

                                                   winPuncher.close();

                                                }

                                             }

                                          );

                                    }

                                 }

                              }

                           )

                           .exceptionally(ex -> {

                              VoxLinkMod.LOGGER.debug("[RoomManager] Multi-port puncher#{} punch failed: {}", fIdx, ex.getMessage());

                              cm.activeHolePunchers.remove(key);



                              try {

                                 extraPuncher.close();

                              } catch (Exception var6x) {

                              }



                              return null;

                           });

                     } catch (Exception e) {

                        VoxLinkMod.LOGGER.warn("[RoomManager] Create multi-port puncher#{} failed: {}", fIdx, e.getMessage());

                     }

                  }

               }

            } else if (!cm.connectionCycleActive.get() && !ConnectionHelper.isConnecting()) {

               VoxLinkMod.LOGGER.info("[RoomManager] Start connection cycle with mapped address");

               if (cm.connectionCycleActive.compareAndSet(false, true)) {

                  String hostIp = state.roomInfo.getHostIp();

                  String hostIpv6 = state.roomInfo.getHostIpv6();

                  int hostPort = state.roomInfo.getHostConnectPort() > 0 ? state.roomInfo.getHostConnectPort() : state.roomInfo.getHostPort();

                  int cyclePort = hostMappedPort;

                  if (hostMappedPortDelta != 0) {

                     int predicted = hostMappedPort + hostMappedPortDelta;

                     if (predicted > 0 && predicted <= 65535) {

                        cyclePort = predicted;

                     }

                  }



                  cm.runConnectionCycle(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, cyclePort, 0);

               }

            }

         }

      }

   }
   static void handlePunchInfo(ConnectionManager cm, String from, JsonObject data) {

      RoomManager.RoomState state = cm.roomManager.currentRoom.get();

      if (state != null && state != RoomManager.PENDING) {

         if (state.roomInfo.isHost()) {

            cm.handleHostPunchInfo(state, from, data);

         } else {

            cm.handleJoinerPunchInfo(state, from, data);

         }

      }

   }
   static void handleHostPunchInfo(ConnectionManager cm, RoomManager.RoomState state, String from, JsonObject data) {
      String joinerMappedIp = data.has("joinerMappedIp") ? data.get("joinerMappedIp").getAsString() : null;

      int joinerMappedPort = data.has("joinerMappedPort") ? data.get("joinerMappedPort").getAsInt() : 0;

      int joinerMappedPortDelta = data.has("joinerMappedPortDelta") && !data.get("joinerMappedPortDelta").isJsonNull()

         ? data.get("joinerMappedPortDelta").getAsInt()

         : 0;

      // 地址族过滤：joiner把IPv6当mapped上报而本端(host)无全局IPv6时，丢弃该候选不进入打洞(S822QF)

      if (joinerMappedIp != null && cm.filterAddressFamilyCandidate(joinerMappedIp, joinerMappedPort, "punch_info:joinerMapped") == null) {

         VoxLinkMod.LOGGER.warn("[HostPunchInfo] Drop punch_info from {}: candidate address family unreachable locally", from);

         return;

      }

      String joinerLocalIp = null;

      if (data.has("joinerLocalIp") && !data.get("joinerLocalIp").isJsonNull()) {

         joinerLocalIp = data.get("joinerLocalIp").getAsString();

         state.roomInfo.setJoinerLocalIp(joinerLocalIp);

         VoxLinkMod.LOGGER.info("[HostPunchInfo] Joiner LAN IP: {}", joinerLocalIp);

      }



      if (joinerMappedIp != null) {

         String myPublicIp = state.roomInfo.getHostMappedIp();

         if (myPublicIp != null && myPublicIp.equals(joinerMappedIp)) {

            state.roomInfo.setSameCgnat(true);

            VoxLinkMod.LOGGER.warn("[HostPunchInfo] Same public IP ({}): both behind same CGNAT", joinerMappedIp);

         }

      }



      boolean requestHostLocalIp = data.has("requestHostLocalIp") && data.get("requestHostLocalIp").getAsBoolean();

      if (joinerMappedPortDelta != 0 && joinerMappedPort > 0) {

         VoxLinkMod.LOGGER

            .info(

               "[HostPunchInfo] Punch joiner raw port={} (delta={} skipped: 同socket双STUN差值非跨socket步长)",

               new Object[]{joinerMappedPort, joinerMappedPortDelta}

            );

      }



      HostPunchContext ctx = cm.hostPunchContext(from);
      String peerPrefix = cm.peerPunchPrefix(from);
      boolean isActive = false;

      for (UdpHolePuncher existing : cm.activeHolePunchers.values()) {
         if (existing != null && existing.isPunching()) {
            isActive = true;
            break;
         }
      }

      if (!isActive) {
         long punchAgeMs = System.currentTimeMillis() - ctx.punchStartMs;
         boolean hostGroupAlive = false;

         for (String key : cm.activeHolePunchers.keySet()) {
            if (key.startsWith(peerPrefix)) {
               hostGroupAlive = true;
               break;
            }
         }

         if (punchAgeMs >= 0L && punchAgeMs < 120000L && hostGroupAlive && ctx.punchInfoId != null && !ctx.punchInfoId.isEmpty()) {
            isActive = true;
         }
      }

      ctx.punching = isActive;

      VoxLinkMod.LOGGER

         .info(

            "[HostPunchInfo] called: joinerMapped={}:{}, delta={}, hostPunching={}, bridgeRunning={}, hostSym={}",

            new Object[]{

               joinerMappedIp,

               joinerMappedPort,

               joinerMappedPortDelta,

               ctx.punching,
               P2PBridge.isRunning(),

               state.roomInfo.isHostSymmetric()

            }

         );

      if (joinerMappedIp == null || joinerMappedPort == 0) {

         VoxLinkMod.LOGGER.warn("[RoomManager] Invalid punch_info from {}: no mapped address", from);

      } else if (cm.connectionWon.get()) {

         VoxLinkMod.LOGGER.debug("[HostPunchInfo] already connected, ignoring punch_info");

      } else {

         String punchInfoId = joinerMappedIp + ":" + joinerMappedPort;
         if (ctx.punching) {
            if (punchInfoId.equals(ctx.punchInfoId)) {

               VoxLinkMod.LOGGER.debug("[RoomManager] Already punching same target, ignore duplicate punch_info");

               return;

            }



            if (System.currentTimeMillis() - ctx.punchStartMs < 3000L) {
               VoxLinkMod.LOGGER
                  .info(
                     "[RoomManager] Already punching, target changed ({} -> {}) within {}ms, ignore to avoid CGNAT IP switch false restart",
                     new Object[]{ctx.punchInfoId, punchInfoId, 3000}
                  );
               return;
            }



            VoxLinkMod.LOGGER

               .info(

                  "[RoomManager] Punching {}ms no success, target changed ({} -> {}), restart with new target",

                  new Object[]{System.currentTimeMillis() - ctx.punchStartMs, ctx.punchInfoId, punchInfoId}
               );

            String[] lastParts = ctx.punchInfoId.split(":");
            if (lastParts.length == 2 && lastParts[0].equals(joinerMappedIp)) {
               ctx.punchInfoId = punchInfoId;



               for (UdpHolePuncher hp : cm.activeHolePunchers.values()) {

                  if (hp != null && hp.getSocket() != null && !hp.getSocket().isClosed()) {

                     hp.updateTarget(joinerMappedIp, joinerMappedPort);

                  }

               }



               VoxLinkMod.LOGGER

                  .info(

                     "[RoomManager] joiner mapped port drift ({} -> {}), update target, keep socket group stable",

                     new Object[]{lastParts[1], joinerMappedPort}

                  );

               return;

            }



            ctx.punching = false;
            String fCtxPrefix = peerPrefix;
            cm.activeHolePunchers.entrySet().removeIf(e -> {
               if (e.getKey().startsWith(fCtxPrefix)) {

                  UdpHolePuncher p = e.getValue();

                  if (p != null) {

                     try {

                        p.stopPunch();

                     } catch (Exception var5x) {

                     }



                     try {

                        p.close();

                     } catch (Exception var4x) {

                     }

                  }



                  return true;

               } else {

                  return false;

               }

            });

         }

         ctx.punchInfoId = punchInfoId;
         ctx.punchStartMs = System.currentTimeMillis();
         LogUploadManager.schedulePunchUpload();

         VoxLinkMod.LOGGER.info("[RoomManager] Received punch_info from {}: {}:{}", new Object[]{from, joinerMappedIp, joinerMappedPort});

         boolean isHostSym = StunDetector.isNatTypeSymmetric(state.roomInfo.getNatType());

         boolean isHostHardSym = StunDetector.isHardSymmetric(state.roomInfo.getNatType());

         boolean joinerSym = data.has("joinerSymmetric") && data.get("joinerSymmetric").getAsBoolean();

         boolean joinerEasySym = data.has("joinerEasySym") && !data.get("joinerEasySym").isJsonNull() && data.get("joinerEasySym").getAsBoolean();

         if (joinerMappedIp != null && joinerMappedPort > 0) {

            String peerNatType = joinerSym ? (joinerEasySym ? "symmetric_easy_inc" : "symmetric") : "full_cone";

            state.roomInfo.addOrUpdatePeer(from, peerNatType, joinerMappedIp, joinerMappedPort);

         }



         boolean joinerHardSym = joinerSym && !joinerEasySym;

         if (isHostSym

            && joinerSym

            && (isHostHardSym || joinerHardSym)

            && !"unknown".equals(state.roomInfo.getNatType())

            && state.roomInfo.getNatType() != null) {

            int upnpPort = state.roomInfo.getHostPort() > 0 ? state.roomInfo.getHostPort() : 51600;

            UPnPManager.UPnPResult upnpResult = UPnPManager.openUdpPort(upnpPort, "VoxLink-HardSym");

            if (upnpResult.success()) {

               VoxLinkMod.LOGGER

                  .warn(

                     "[HostPunchInfo] Both symmetric NAT with HardSym (hostHard={}, joinerHard={}), UPnP UDP port {} mapped, continue UDP punch",

                     new Object[]{isHostHardSym, joinerHardSym, upnpPort}

                  );

            } else {

               VoxLinkMod.LOGGER

                  .warn(

                     "[HostPunchInfo] Both symmetric NAT with HardSym(hostHard={}, joinerHard={}), UPnP failed, continue Birthday Attack+port prediction (fallback to Relay on failure)",

                     isHostHardSym,

                     joinerHardSym

                  );

            }

         }



         if (isHostSym && joinerSym) {

            VoxLinkMod.LOGGER.info("[HostPunchInfo] Both EasySym (port predictable), continue UDP punch (EasyTier both_easy_sym)");

         }



         RoomInfo.PortStatus portStatus = state.roomInfo.getIpv4Status();

         boolean portUnreachable = portStatus == RoomInfo.PortStatus.UNREACHABLE || portStatus == RoomInfo.PortStatus.UNKNOWN;

         if (portUnreachable && !isHostSym) {

            VoxLinkMod.LOGGER.info("[HostPunchInfo] Host port status={}, upgrade to 20 socket birthday attack", portStatus);

         }



         int HOST_MULTI_COUNT;

         if (isHostSym && joinerSym) {

            HOST_MULTI_COUNT = cm.punchProfile().hardSymSocketCount;

            cm.switchPunchProfile(PunchProfile.HARDSYM, "Sym×Sym");

         } else if (isHostSym) {

            // 硬档(对称)用生日攻击全量socket(84)撞端口, 普通档仍走各自模板配置

            boolean hardTier = cm.scenarioTier == ScenarioTier.Tier.HARD_DUAL_SYM

               || cm.scenarioTier == ScenarioTier.Tier.HARD_ONE_SYM;

            HOST_MULTI_COUNT = hardTier

               ? cm.punchProfile().hardSymSocketCount

               : Math.max(cm.punchProfile().hostMultiMinSocketCount, cm.punchProfile().hostMultiSocketCount);

         } else if (portUnreachable) {

            HOST_MULTI_COUNT = cm.punchProfile().hostMultiSocketCount;

         } else {

            HOST_MULTI_COUNT = cm.punchProfile().hostMultiBaseSocketCount;

         }



         RoomManager.RoomState fState = state;

         String fFrom = from;

         JsonObject fData = data;

         boolean fRequestHostLocalIp = requestHostLocalIp;

         int fHostMultiCount = HOST_MULTI_COUNT;

         String fJoinerMappedIp = joinerMappedIp;

         int fJoinerMappedPort = joinerMappedPort;

         CompletableFuture.runAsync(

            () -> {

               List<UdpHolePuncher> hostPunchers = new ArrayList<>();

               List<StunProbe.PublicMappedAddress> mappedAddrs = new ArrayList<>();

               boolean hostPunchSocketSymmetric = false;

               int hostPunchSocketDelta = 0;

               UdpHolePuncher oldHost = cm.activeHolePunchers.remove("host");

               if (oldHost != null) {

                  try {

                     oldHost.close();

                  } catch (Exception var50) {

                  }

               }



               List<CompletableFuture<StunProbe.PublicMappedAddress[]>> stunFutures = new ArrayList<>();

               int createdCount = 0;



               for (int i = 0; i < fHostMultiCount; i++) {

                  UdpHolePuncher p = new UdpHolePuncher();

                  cm.applyPunchTemplate(p);

                  PunchParams hostRoundParams = PunchParams.fromProfile(cm.punchProfile());

                  hostRoundParams.timeoutMs = Math.min(hostRoundParams.timeoutMs, cm.punchProfile().hostRoundTimeoutMs);

                  p.setPunchParams(hostRoundParams);



                  try {

                     if (i == 0) {

                        p.createSocket(fState.roomInfo.getHostPort());

                     } else {

                        p.createSocket();

                     }

                  } catch (Exception e) {

                     try {

                        p.createSocket();

                     } catch (Exception e2) {

                        continue;

                     }

                  }



                  hostPunchers.add(p);
                  cm.activeHolePunchers.put("hp_" + fFrom + "_" + i, p);

                  createdCount++;

                  UdpHolePuncher fp = p;

                  int idx = i;

                  stunFutures.add(

                     CompletableFuture.supplyAsync(

                        () -> {

                           StunProbe.PublicMappedAddress[] dual = StunProbe.discoverMappedAddressDual(

                              fp.getSocket(), cm.stunUrlsProbedFirst().get(0), cm.stunUrlsProbedFirst().get(1)

                           );

                           VoxLinkMod.LOGGER

                              .info(

                                 "[HostPunchInfo] Socket#{} STUN(dual): {} vs {} (localPort={})",

                                 new Object[]{idx, dual[0] != null ? dual[0].port() : -1, dual[1] != null ? dual[1].port() : -1, fp.getSocket().getLocalPort()}

                              );

                           return dual;

                        }

                     )

                  );

                  int createIntervalMs = Math.max(0, cm.punchProfile().socketCreateIntervalMs);

                  if (createIntervalMs > 0) {

                     try {

                        Thread.sleep(createIntervalMs);

                     } catch (InterruptedException ignored) {

                        Thread.currentThread().interrupt();

                        break;

                     }

                  }

               }



               VoxLinkMod.LOGGER.info("[HostPunchInfo] Parallel start {}/{} socket+dual STUN tasks (no sleep)", createdCount, fHostMultiCount);

               int minRequired = Math.min(cm.punchProfile().birthdaySocketCount, fHostMultiCount);

               long stunDeadline = System.currentTimeMillis()

                  + Math.max(5000L, (long)fHostMultiCount * Math.max(0, cm.punchProfile().socketCreateIntervalMs) + 3000L);



               while (System.currentTimeMillis() < stunDeadline) {

                  int done = 0;

                  int success = 0;



                  for (CompletableFuture<StunProbe.PublicMappedAddress[]> f : stunFutures) {

                     if (f.isDone()) {

                        done++;



                        try {

                           StunProbe.PublicMappedAddress[] r = f.getNow(null);

                           if (r != null && (r[0] != null || r[1] != null)) {

                              success++;

                           }

                        } catch (Exception var54) {

                        }

                     }

                  }



                  if (success >= minRequired || done == stunFutures.size()) {

                     break;

                  }



                  try {

                     Thread.sleep(100L);

                  } catch (InterruptedException ie) {

                     Thread.currentThread().interrupt();

                     break;

                  }

               }



               String mappedIp = null;



               for (int i = 0; i < stunFutures.size(); i++) {

                  try {

                     StunProbe.PublicMappedAddress[] addrs = stunFutures.get(i).getNow(null);

                     if (addrs != null && (addrs[0] != null || addrs[1] != null)) {

                        // 双栈时优先通告 IPv4 映射(常规访客为IPv4), 避免把IPv6映射通告给IPv4访客导致打不中

                        StunProbe.PublicMappedAddress chosen = addrs[0] != null && addrs[0].ip().indexOf(58) < 0

                           ? addrs[0]

                           : (addrs[1] != null && addrs[1].ip().indexOf(58) < 0 ? addrs[1] : (addrs[0] != null ? addrs[0] : addrs[1]));

                        if (chosen != null) {

                           mappedAddrs.add(chosen);

                           if (mappedIp == null) {

                              mappedIp = chosen.ip();

                           }

                        }



                        if (addrs[0] != null && addrs[1] != null && cm.sameIpFamily(addrs[0].ip(), addrs[1].ip()) && addrs[0].port() != addrs[1].port()) {

                           hostPunchSocketSymmetric = true;

                           if (hostPunchSocketDelta == 0) {

                              hostPunchSocketDelta = addrs[1].port() - addrs[0].port();

                           }

                        }

                     }

                  } catch (Exception var49) {

                  }

               }



               if (mappedAddrs.isEmpty()) {

                  VoxLinkMod.LOGGER.error("[HostPunchInfo] All STUN queries failed");

               } else if (cm.connectionWon.get()) {

                  VoxLinkMod.LOGGER.info("[HostPunchInfo] Connection established, discard late 84-socket punch task");



                  for (UdpHolePuncher p : hostPunchers) {

                     try {

                        p.close();

                     } catch (Exception var45) {

                     }

                  }

               } else {

                   if (hostPunchSocketSymmetric && !fState.roomInfo.isHostSymmetric()) {

                      fState.roomInfo.setHostSymmetric(true);

                      boolean hostPunchEasySym = Math.abs(hostPunchSocketDelta) <= 100;

                      NatClass newLocal = hostPunchEasySym ? NatClass.EASY_SYM : NatClass.HARD_SYM;

                      if (cm.localNatClass != newLocal) {
                         VoxLinkMod.LOGGER.info("[HostPunchInfo] Update localNat {} -> {} (multi-socket symmetric detected, delta={})", new Object[]{cm.localNatClass, newLocal, hostPunchSocketDelta});
                         if (cm.applyLocalNatClass(newLocal, "multi_socket_sym")) {
                            cm.applyRemoteNatClass(cm.classifyRemoteNatLogged(fState), "multi_socket_sym");
                         }

                         // 用新 NAT 类重算硬场景档位，确保硬档仍走 V100 而非被降级为 AGGRESSIVE/DEFAULT

                         ScenarioTier.Tier prevTier = cm.scenarioTier;

                         cm.scenarioTier = ScenarioTier.classify(

                            cm.localNatClass.isSymmetric(), cm.remoteNatClass.isSymmetric(), true, true, true);

                         if (cm.scenarioTier != prevTier) {

                            VoxLinkMod.LOGGER.info("[HostPunchInfo] tier {} -> {} (host symmetric known)", ScenarioTier.key(prevTier), ScenarioTier.key(cm.scenarioTier));

                         }

                         PunchProfile recommended = NatClass.recommendProfile(cm.localNatClass, cm.remoteNatClass);

                         cm.switchPunchProfile(recommended, "host_punch_sym_" + cm.localNatClass + "x" + cm.remoteNatClass);

                         VoxLinkMod.LOGGER

                            .info("[HostPunchInfo] Probe upgrade: local={} remote={} -> profile={}",

                               new Object[]{cm.localNatClass, cm.remoteNatClass, cm.punchProfile().describeInstance()});

                      }



                      if (hostPunchEasySym && !"symmetric_easy_inc".equals(fState.roomInfo.getNatType())) {

                         fState.roomInfo.setNatType("symmetric_easy_inc");

                         VoxLinkMod.LOGGER.info("[HostPunchInfo] Override cached NAT {} -> symmetric_easy_inc (multi-socket dual STUN detected)", "cached");

                      }

                   }



                  // 对称host生日攻击需足量多源端口(第八/九轮实证84可撞锥端), 探测出对称后补够再统一开打

                  if (hostPunchSocketSymmetric && hostPunchers.size() < cm.punchProfile().hardSymSocketCount) {

                     int before = hostPunchers.size();

                     int targetCount = cm.punchProfile().hardSymSocketCount;



                     for (int extra = hostPunchers.size(); extra < targetCount; extra++) {

                        try {

                           UdpHolePuncher ep = new UdpHolePuncher();

                           cm.applyPunchTemplate(ep);

                           PunchParams eparams = PunchParams.fromProfile(cm.punchProfile());

                           eparams.timeoutMs = Math.min(eparams.timeoutMs, cm.punchProfile().hostRoundTimeoutMs);

                           ep.setProfile(cm.punchProfile());

                           ep.setPunchParams(eparams);

                           ep.createSocket();

                           hostPunchers.add(ep);
                           cm.activeHolePunchers.put("hp_" + fFrom + "_" + (hostPunchers.size() - 1), ep);

                        } catch (Exception e) {

                           VoxLinkMod.LOGGER.debug("[HostPunchInfo] extra socket create failed: {}", e.getMessage());

                        }

                     }



                     VoxLinkMod.LOGGER.info("[HostPunchInfo] Symmetric host, extend birthday attack {} -> {} sockets", before, hostPunchers.size());

                  }



                  JsonObject symData = new JsonObject();

                  if (hostPunchSocketSymmetric

                     || StunDetector.isNatTypeSymmetric(fState.roomInfo.getNatType())) {

                     symData.addProperty("hostSymmetric", true);

                  }



                  boolean hostEasySymMapped = hostPunchSocketSymmetric && Math.abs(hostPunchSocketDelta) <= 100;

                  if (hostEasySymMapped) {

                     symData.addProperty("hostEasySym", true);

                  }

                  if (hostPunchSocketSymmetric && hostPunchSocketDelta != 0) {

                     symData.addProperty("hostMappedPortDelta", hostPunchSocketDelta);

                  }



                  symData.addProperty("hostMappedIp", mappedIp);

                  symData.addProperty("hostMappedPort", mappedAddrs.get(0).port());

                  if (fRequestHostLocalIp || fState.roomInfo.isSameCgnat()) {

                     String myLocalIp = StunDetector.getLocalIpAddress();

                     if (myLocalIp != null && !myLocalIp.isEmpty()) {

                        symData.addProperty("hostLocalIp", myLocalIp);

                        VoxLinkMod.LOGGER.info("[HostPunchInfo] CGNAT scenario include hostLocalIp: {}", myLocalIp);

                     }

                  }



                  JsonArray portsArray = new JsonArray();



                  for (StunProbe.PublicMappedAddress a : mappedAddrs) {

                     portsArray.add(a.port());

                  }



                  symData.add("hostMappedPorts", portsArray);

                  VoxLinkMod.LOGGER

                     .info("[HostPunchInfo] holepunch_mapped: {} ports={} (symmetric={})", new Object[]{mappedIp, portsArray, hostPunchSocketSymmetric});

                  cm.signalingClient

                     .sendSignal(fState.roomInfo.getCode(), fState.roomInfo.getToken(), true, "holepunch_mapped", symData, fFrom)

                     .exceptionally(e -> {

                        VoxLinkMod.LOGGER.debug("holepunch_mapped send failed: {}", e.getMessage());

                        return null;

                     });

                  cm.hostPunchContext(fFrom).punching = true;
                  cm.hostPunchContext(fFrom).punchStartMs = System.currentTimeMillis();
                  String clientId = fFrom;

                  ConnectionState.transitionTo(ConnectionState.UDP_PUNCH, "Host开始打洞 client=" + clientId);

                  AtomicBoolean hostPunchWon = new AtomicBoolean(false);



                  for (UdpHolePuncher p : hostPunchers) {

                     p.setOnPeerPunchReceived(addr -> {

                        cm.sessionPunchRecvEver.set(true);

                        String code = fState.roomInfo.getCode();

                        String token = fState.roomInfo.getToken();

                        JsonObject portData = new JsonObject();

                        portData.addProperty("peer_ip", addr.getAddress().getHostAddress());

                        portData.addProperty("peer_port", addr.getPort());

                        cm.signalingClient.sendSignal(code, token, true, "peer_port", portData, fFrom).exceptionally(e -> {

                           VoxLinkMod.LOGGER.debug("peer_port signal failed: {}", e.getMessage());

                           return null;

                        });

                     });

                  }



                  ScheduledFuture<?> punchTimeout = cm.scheduler.schedule(() -> {
                     HostPunchContext timeoutCtx = cm.hostPunchContexts.get(clientId);
                     if (timeoutCtx != null && timeoutCtx.punching) {
                        VoxLinkMod.LOGGER.info("[HostPunchInfo] 120s fallback cleanup host socket client={}", clientId);
                        timeoutCtx.punching = false;
                        cm.activeHolePunchers.remove("host");
                        String timeoutPrefix = cm.peerPunchPrefix(clientId);
                        cm.activeHolePunchers.entrySet().removeIf(e -> e.getKey().startsWith(timeoutPrefix));
                        cm.hostPunchContexts.remove(clientId);



                        for (UdpHolePuncher px : hostPunchers) {

                           try {

                              px.cancel();

                              px.close();

                           } catch (Exception var6x) {

                           }

                        }

                     }

                  }, 120L, TimeUnit.SECONDS);

                  boolean joinerSymmetric = fData.has("joinerSymmetric") && fData.get("joinerSymmetric").getAsBoolean();

                  // EasyTier方式(1.0.0兼容): 对称端多socket只发对端已知端口, 不扫范围。

                  // 对称CGNAT上扫射会炸出 socket数×端口数 个未知映射并触发洪泛保护, 双向全丢(GBNPLE实证);

                  // 生日攻击靠多个源端口撞对端cone已知端口, 不靠扫射。

                  // 双对称时对端已知端口只是其对STUN的假映射, 必须扫其上报端口±带宽; joinerSym标记可能晚到, 以双方NAT为准

                  boolean hostDoubleSym = cm.localNatClass.isSymmetric() && cm.remoteNatClass.isSymmetric();

                  int hostPortRange = joinerSymmetric || hostDoubleSym ? cm.punchProfile().joinerMultiPortRange : 0;



                  // 探测升级(如多socket双STUN检出对称NAT切换V100/HARDSYM)后刷新整组模板,

                  // 避免socket创建时套用的旧DEFAULT参数贯穿全部轮次

                  for (UdpHolePuncher rp : hostPunchers) {

                     if (rp == null || rp.getSocket() == null || rp.getSocket().isClosed()) {

                        continue;

                     }



                     rp.setProfile(cm.punchProfile());

                     PunchParams roundParams = PunchParams.fromProfile(cm.punchProfile());

                     roundParams.timeoutMs = Math.min(roundParams.timeoutMs, cm.punchProfile().hostRoundTimeoutMs);

                     if (hostPortRange > 0) {

                        roundParams.sendMinRounds = 1;

                        roundParams.sendMinPass = 1;

                     }



                     rp.setPunchParams(roundParams);

                  }



                  VoxLinkMod.LOGGER

                     .info(

                        "[HostPunchInfo] {} sockets parallel punch to {}:{} range=±{} (joinerSym={})",

                        new Object[]{hostPunchers.size(), fJoinerMappedIp, fJoinerMappedPort, hostPortRange, joinerSymmetric}

                     );

                  boolean hostDriftSync = hostPunchSocketSymmetric || fState.roomInfo.isHostSymmetric();

                  long driftMaxDurMs = 120000L;

                  long driftStartMs = System.currentTimeMillis();

                  long driftLastSentMs = 0L;



                  if (!cm.connectionWon.get() && cm.roomManager.currentRoom.get() == fState) {

                     long punchGroupDeadline = System.currentTimeMillis() + 120000L;



                     while (!cm.connectionWon.get() && cm.roomManager.currentRoom.get() == fState && System.currentTimeMillis() < punchGroupDeadline) {

                        if (hostDriftSync && cm.hostPunchContext(fFrom).punching && !mappedAddrs.isEmpty()) {

                           long nowMs = System.currentTimeMillis();

                           long driftElapsed = nowMs - driftStartMs;

                           if (nowMs - driftLastSentMs >= 3000L && driftElapsed < driftMaxDurMs) {

                              driftLastSentMs = nowMs;



                              try {

                                 JsonObject rsync = new JsonObject();

                                 rsync.addProperty("hostMappedIp", mappedIp);

                                 rsync.addProperty("hostMappedPort", mappedAddrs.get(0).port());

                                 rsync.addProperty("hostSymmetric", true);

                                 JsonArray rsyncPorts = new JsonArray();



                                 for (StunProbe.PublicMappedAddress a : mappedAddrs) {

                                    rsyncPorts.add(a.port());

                                 }



                                 rsync.add("hostMappedPorts", rsyncPorts);

                                 if (hostEasySymMapped) {

                                    rsync.addProperty("hostEasySym", true);

                                 }



                                 cm.signalingClient

                                    .sendSignal(fState.roomInfo.getCode(), fState.roomInfo.getToken(), true, "holepunch_mapped", rsync, fFrom)

                                    .exceptionally(e -> {

                                       VoxLinkMod.LOGGER.debug("holepunch_mapped resync send failed: {}", e.getMessage());

                                       return null;

                                    });

                                 VoxLinkMod.LOGGER

                                    .info(

                                       "[HostPunchInfo] drift resync: resend holepunch_mapped {}:{} ports={} (elapsed={}ms)",

                                       new Object[]{mappedIp, mappedAddrs.get(0).port(), rsyncPorts, driftElapsed}

                                    );

                              } catch (Exception var52) {

                              }

                           }

                        }



                        boolean anyAlive = false;

                        boolean anyPunchable = false;

                        List<CompletableFuture<?>> roundFutures = new ArrayList<>();



                        for (int i = 0; i < hostPunchers.size(); i++) {

                           UdpHolePuncher mp = hostPunchers.get(i);

                           if (mp.isSocketTransferred() || mp.getSocket() == null || mp.getSocket().isClosed()) {

                              continue;

                           }

                           anyAlive = true;



                           // PREDICTION_OFF 会话级封顶: 停止无意义直连(配合 1.1.5 的
                           // round=3 自动 TURN, 此处静默让位中继; 降频轮询保留漂移恢复可能)
                           if (cm.sessionPredictionOffCount.get() >= ConnectionManager.PREDICTION_OFF_CAP && !cm.sessionPunchRecvEver.get()) {
                              continue;
                           }
                           anyPunchable = true;

                           int idx = i;

                           // 热循环修复②: 用 puncher 当前端口(updateTarget 漂移纠偏后的值)而非入组时冻结的
                           // fJoinerMappedPort——punchWithPortPrediction 入口会把 remotePort 覆盖回传入值,
                           // 冻结旧端口等于每轮都击穿端口漂移纠偏
                           int roundTargetPort = mp.getRemotePort() > 0 ? mp.getRemotePort() : fJoinerMappedPort;

                           roundFutures.add(

                              mp.punchWithPortPrediction(fJoinerMappedIp, roundTargetPort, hostPortRange)

                                 .thenAccept(

                                    result -> {

                                       if (!result.isSuccess()) {

                                          PunchFailureClassifier.FailureReason reason = PunchFailureClassifier.classify(result);

                                          cm.lastPunchResult = result.withReason(reason);

                                          cm.activePunchParams = 

                                             PunchTuner.nextParams(

                                                cm.punchProfile(), cm.localNatClass, cm.remoteNatClass, 1, 8, reason, cm.lastPunchResult

                                             )

                                          ;

                                          cm.recordPunchFailure(reason, result.socketsReceivedPunch, result.socketsReceivedAck);

                                       } else {

                                          DatagramSocket socket = result.getSuccessSocket();

                                          if (!hostPunchWon.compareAndSet(false, true)) {

                                             try {

                                                mp.close();

                                             } catch (Exception var13x) {

                                             }

                                          } else if (cm.roomManager.currentRoom.get() == fState && cm.connectionWon.compareAndSet(false, true)) {

                                             VoxLinkMod.LOGGER.info("[HostPunchInfo] Socket#{} punch success!", idx);

                                             mp.markSocketTransferred();

                                             cm.stopAllPunchingAfterHostBridge();

                                             mp.stopPunch();

                                             DatagramSocket winSocket = socket;

                                             UdpHolePuncher winPuncher = mp;

                                             cm.scheduler

                                                .submit(

                                                   () -> {

                                                      try {

                                                         cm.establishUdpTransport(

                                                            fState,

                                                            winSocket,

                                                            winPuncher,

                                                            new InetSocketAddress(fJoinerMappedIp, fJoinerMappedPort),

                                                            clientId,

                                                            true,

                                                            clientId

                                                         );

                                                      } catch (Exception e) {

                                                         VoxLinkMod.LOGGER.error("[HostPunchInfo] Transport create failed: {}", e.getMessage());

                                                         winPuncher.close();

                                                      }

                                                   }

                                                );

                                          } else {

                                             try {

                                                mp.close();

                                             } catch (Exception var14x) {

                                             }

                                          }

                                       }

                                    }

                                 )

                                 .exceptionally(e -> {

                                    VoxLinkMod.LOGGER.debug("[HostPunchInfo] Socket#{} punch failed: {}", idx, e.getMessage());

                                    return null;

                                 })

                           );

                        }



                        if (!anyPunchable) {

                           if (!anyAlive) {

                              break;

                           }

                           // socket 还活着但目标全被拉黑: 降频轮询等 updateTarget 端口漂移纠偏
                           // (新端口不在黑名单即可恢复真打洞), 不再每 300ms 空转刷日志
                           try {

                              Thread.sleep(2000L);

                           } catch (InterruptedException ie) {

                              Thread.currentThread().interrupt();

                              break;

                           }

                           continue;

                        }



                        try {

                           CompletableFuture.allOf(roundFutures.toArray(new CompletableFuture[0])).join();

                        } catch (Exception var15x) {

                        }



                        if (cm.connectionWon.get() || cm.roomManager.currentRoom.get() != fState) {

                           break;

                        }



                        try {

                           Thread.sleep(300L);

                        } catch (InterruptedException ie) {

                           Thread.currentThread().interrupt();

                           break;

                        }

                     }

                  } else {

                     VoxLinkMod.LOGGER.info("[HostPunchInfo] Connected or room changed during lazy, abort punch");



                     for (UdpHolePuncher p : hostPunchers) {

                        try {

                           p.close();

                        } catch (Exception var47) {

                        }

                     }

                  }

               }

            },

            cm.punchExecutor

         );

      }

   }
   static void handleJoinerPunchInfo(ConnectionManager cm, RoomManager.RoomState state, String from, JsonObject data) {

      String hostMappedIp = null;

      int hostMappedPort = 0;

      if (data.has("hostMappedIp") && !data.get("hostMappedIp").isJsonNull()) {

         hostMappedIp = data.get("hostMappedIp").getAsString();

      }



      if (data.has("hostMappedPort") && !data.get("hostMappedPort").isJsonNull()) {

         hostMappedPort = data.get("hostMappedPort").getAsInt();

      }



      if (hostMappedIp != null && hostMappedPort > 0) {

         VoxLinkMod.LOGGER.info("[RoomManager] Host mapped address in punch_info: {}:{}", hostMappedIp, hostMappedPort);

         state.roomInfo.setHostMappedAddress(hostMappedIp, hostMappedPort);

         UdpHolePuncher joinerPuncher = cm.activeHolePunchers.get("joiner");

         if (joinerPuncher != null && cm.connectionCycleActive.get()) {

            VoxLinkMod.LOGGER.info("[RoomManager] Update joiner punch target to {}:{}", hostMappedIp, hostMappedPort);

            joinerPuncher.updateTarget(hostMappedIp, hostMappedPort);

         }

      }

   }
   static void handleReverseHolepunchOffer(ConnectionManager cm, String from, JsonObject data) {

      RoomManager.RoomState state = cm.roomManager.currentRoom.get();

      if (state != null && state != RoomManager.PENDING && state.roomInfo.isHost()) {

         long syncTime = state.roomInfo.getPunchSyncTimeMs();

         if (syncTime > 0L) {

            long delay = syncTime - System.currentTimeMillis();

            if (delay > 0L && delay < 8000L) {

               VoxLinkMod.LOGGER.info("[ReversePunch] RTT sync wait: start host punch after {}ms", delay);

               cm.scheduler.schedule(() -> {

                  if (cm.roomManager.currentRoom.get() == state && !cm.connectionWon.get()) {

                     cm.handleReverseHolepunchOfferDelayed(from, data);

                  }

               }, delay, TimeUnit.MILLISECONDS);

               return;

            }

         }



         cm.handleReverseHolepunchOfferDelayed(from, data);

      }

   }
   static void handleReverseHolepunchOfferDelayed(ConnectionManager cm, String from, JsonObject data) {

      RoomManager.RoomState state = cm.roomManager.currentRoom.get();

      if (state != null && state != RoomManager.PENDING && state.roomInfo.isHost()) {

         if (cm.activeUdpTransports.containsKey(from)) {

            VoxLinkMod.LOGGER.info("[ReversePunch] Active transport to {}, ignore reverse_holepunch_offer", from);

         } else {

            String joinerMappedIp = data.has("joinerMappedIp") ? data.get("joinerMappedIp").getAsString() : null;

            int joinerMappedPort = data.has("joinerMappedPort") ? data.get("joinerMappedPort").getAsInt() : 0;

            boolean joinerSymmetric = data.has("joinerSymmetric") && data.get("joinerSymmetric").getAsBoolean();

            int joinerMappedPortDelta = data.has("joinerMappedPortDelta") && !data.get("joinerMappedPortDelta").isJsonNull()

               ? data.get("joinerMappedPortDelta").getAsInt()

               : 0;

            // 地址族过滤：host发布的候选对端不可达时丢弃(reverse方向同理)

            if (joinerMappedIp != null && cm.filterAddressFamilyCandidate(joinerMappedIp, joinerMappedPort, "reverse_offer:joinerMapped") == null) {

               VoxLinkMod.LOGGER.warn("[ReversePunch] Drop reverse offer from {}: candidate address family unreachable locally", from);

               return;

            }

            if (joinerMappedIp != null && joinerMappedPort > 0) {

               String joinerNatType = joinerSymmetric ? "symmetric" : "full_cone";

               state.roomInfo.addOrUpdatePeer(from, joinerNatType, joinerMappedIp, joinerMappedPort);

            }



            if (joinerMappedPortDelta != 0 && joinerMappedPort > 0) {

               VoxLinkMod.LOGGER

                  .info("[ReversePunch] Punch joiner raw port={} (delta={} skipped: 同socket双STUN差值非跨socket步长)", new Object[]{joinerMappedPort, joinerMappedPortDelta});

            }



            if (joinerMappedIp != null && joinerMappedPort != 0) {

               VoxLinkMod.LOGGER

                  .info(

                     "[ReversePunch] Host received reverse_holepunch_offer from {}: {}:{} (joinerSym={})",

                     new Object[]{from, joinerMappedIp, joinerMappedPort, joinerSymmetric}

                  );

               UdpHolePuncher existingReverse = cm.activeHolePunchers.get("hostRev");

               if (existingReverse != null && existingReverse.isPunching()) {

                  VoxLinkMod.LOGGER.info("[ReversePunch] already reverse punching, update target to {}:{}", joinerMappedIp, joinerMappedPort);

                  existingReverse.updateTarget(joinerMappedIp, joinerMappedPort);

               } else {

                  UdpHolePuncher puncher = new UdpHolePuncher();

                  cm.applyPunchTemplate(puncher);



                  try {

                     puncher.createSocket();

                  } catch (Exception e) {

                     // H9: createSocket 失败裸 return 会让打洞占用/超时悬挂,后续 join_request
                     // 被排队饿死。此处做最小收敛:清掉该 clientId 的 hostPunchContext,
                     // 若没有其他活跃打洞则复位 connectionCycleActive 并取消超时,
                     // 通知房客快速失败重试,避免无限等待。
                     VoxLinkMod.LOGGER.error("[ReversePunch] create socket failed: {}", e.getMessage());

                     try { puncher.close(); } catch (Exception closeEx) { }

                     cm.hostPunchContexts.remove(from);

                     if (!cm.isAnyHostPunching() && cm.connectionCycleActive.get()) {

                        cm.connectionCycleActive.set(false);

                        if (cm.connectionTimeoutFuture != null) {

                           try { cm.connectionTimeoutFuture.cancel(false); } catch (Exception cancelEx) { }

                           cm.connectionTimeoutFuture = null;

                        }

                     }

                     // 不再向房客发 disconnect：一次 createSocket 失败可能是暂时性的（端口竞争等），
                     // 发信号会把房客的持续重试整个杀掉（handleDisconnect→abortPunchOnPeerDisconnect），
                     // 等于把"无限打洞"修没了。只做本端收敛，房客下一轮 join_request 重放会重新触发。

                     return;

                  }



                  VoxLinkMod.LOGGER.info("[ReversePunch] Host reverse punch socket: localPort={}", puncher.getSocket().getLocalPort());

                  cm.hostRevPunchPeer = from;

                  cm.activeHolePunchers.put("hostRev", puncher);

                  RoomManager.RoomState fState = state;

                  String fFrom = from;

                  JsonObject fData = data;

                  UdpHolePuncher fPuncher = puncher;

                  String fJoinerMappedIp = joinerMappedIp;

                  int fJoinerMappedPort = joinerMappedPort;

                  boolean fJoinerSymmetric = joinerSymmetric;

                  cm.punchExecutor

                     .execute(

                        () -> {

                           VoxLinkMod.LOGGER.info("[ReversePunch] dual STUN on reverse socket...");

                           StunProbe.PublicMappedAddress m1 = null;

                           StunProbe.PublicMappedAddress m2 = null;



                           try {

                              m1 = fPuncher.discoverMappedAddress(List.of(cm.stunUrlsProbedFirst().get(0)));

                              VoxLinkMod.LOGGER

                                 .info(

                                    "[ReversePunch] Host reverse STUN #1: ip={}, port={} (localPort={})",

                                    new Object[]{m1 != null ? m1.ip() : "null", m1 != null ? m1.port() : -1, fPuncher.getSocket().getLocalPort()}

                                 );

                              m2 = fPuncher.discoverMappedAddress(List.of(cm.stunUrlsProbedFirst().get(1)));

                              VoxLinkMod.LOGGER

                                 .info(

                                    "[ReversePunch] Host reverse STUN #2: ip={}, port={} (localPort={})",

                                    new Object[]{m2 != null ? m2.ip() : "null", m2 != null ? m2.port() : -1, fPuncher.getSocket().getLocalPort()}

                                 );

                           } catch (Exception e) {

                              VoxLinkMod.LOGGER.warn("[ReversePunch] Dual STUN failed: {}", e.getMessage());

                           }



                           StunProbe.PublicMappedAddress hostMapped1 = m1;

                           StunProbe.PublicMappedAddress hostMapped2 = m2;

                           boolean hostPunchSocketSymmetric = false;

                           int hostRevDelta = 0;

                           StunProbe.PublicMappedAddress hostMapped = null;

                           if (hostMapped1 != null && hostMapped2 != null) {

                              if (cm.sameIpFamily(hostMapped1.ip(), hostMapped2.ip()) && hostMapped1.port() != hostMapped2.port()) {

                                 hostPunchSocketSymmetric = true;

                                 hostRevDelta = hostMapped2.port() - hostMapped1.port();

                                 VoxLinkMod.LOGGER

                                    .info("[ReversePunch] Host punch socket STUN: symmetric detected ({} vs {}, delta={})", new Object[]{hostMapped1.port(), hostMapped2.port(), hostRevDelta});

                              }



                              hostMapped = hostMapped2;

                           } else {

                              hostMapped = hostMapped1 != null ? hostMapped1 : hostMapped2;

                           }



                           if (hostPunchSocketSymmetric && !fState.roomInfo.isHostSymmetric()) {

                              fState.roomInfo.setHostSymmetric(true);

                              boolean hostRevEasySym = Math.abs(hostRevDelta) <= 100;

                              NatClass newLocal = hostRevEasySym ? NatClass.EASY_SYM : NatClass.HARD_SYM;

                              if (cm.localNatClass != newLocal) {
                                 VoxLinkMod.LOGGER
                                    .info("[ReversePunch] Update localNat {} -> {} (reverse socket symmetric detected)", cm.localNatClass, newLocal);
                                 if (cm.applyLocalNatClass(newLocal, "reverse_socket_sym")) {
                                    cm.applyRemoteNatClass(cm.classifyRemoteNatLogged(fState), "reverse_socket_sym");
                                 }

                                 // 用新 NAT 类重算硬场景档位，确保硬档仍走 V100 而非被降级为 AGGRESSIVE/DEFAULT

                                 ScenarioTier.Tier prevTier = cm.scenarioTier;

                                 cm.scenarioTier = ScenarioTier.classify(

                                    cm.localNatClass.isSymmetric(), cm.remoteNatClass.isSymmetric(), true, true, true);

                                 if (cm.scenarioTier != prevTier) {

                                    VoxLinkMod.LOGGER.info("[ReversePunch] tier {} -> {} (reverse socket symmetric known)", ScenarioTier.key(prevTier), ScenarioTier.key(cm.scenarioTier));

                                 }

                                 PunchProfile recommended = NatClass.recommendProfile(cm.localNatClass, cm.remoteNatClass);

                                 cm.switchPunchProfile(recommended, "reverse_socket_" + cm.localNatClass + "x" + cm.remoteNatClass);

                              }

                           }



                           JsonObject punchData = new JsonObject();

                           if (hostMapped != null) {

                              punchData.addProperty("hostMappedIp", hostMapped.ip());

                              punchData.addProperty("hostMappedPort", hostMapped.port());

                           }



                           boolean hostSym = hostPunchSocketSymmetric || fState.roomInfo.isHostSymmetric() || StunDetector.isNatTypeSymmetric(fState.roomInfo.getNatType());

                           if (hostSym) {

                              punchData.addProperty("hostSymmetric", true);

                           }



                           boolean hostEasySymRev = hostPunchSocketSymmetric && Math.abs(hostRevDelta) <= 100;

                           if (hostEasySymRev) {

                              punchData.addProperty("hostEasySym", true);

                           }



                           cm.signalingClient

                              .sendSignal(fState.roomInfo.getCode(), fState.roomInfo.getToken(), true, "reverse_punch_info", punchData, fFrom)

                              .exceptionally(e -> {

                                 VoxLinkMod.LOGGER.debug("[ReversePunch] reverse_punch_info send failed: {}", e.getMessage());

                                 return null;

                              });

                           fPuncher.setOnPeerPunchReceived(addr -> {

                              cm.sessionPunchRecvEver.set(true);

                              String code = fState.roomInfo.getCode();

                              String token = fState.roomInfo.getToken();

                              JsonObject portData = new JsonObject();

                              portData.addProperty("peer_ip", addr.getAddress().getHostAddress());

                              portData.addProperty("peer_port", addr.getPort());

                              cm.signalingClient.sendSignal(code, token, true, "peer_port", portData, fFrom).exceptionally(e -> {

                                 VoxLinkMod.LOGGER.debug("peer_port signal failed: {}", e.getMessage());

                                 return null;

                              });

                           });

                           String hostNat = fState.roomInfo.getNatType();

                           boolean hostSymmetric = hostPunchSocketSymmetric || StunDetector.isNatTypeSymmetric(hostNat) || fState.roomInfo.isHostSymmetric();

                           List<Integer> allJoinerPorts = new ArrayList<>();

                           if (fData.has("joinerMappedPorts") && fData.get("joinerMappedPorts").isJsonArray()) {

                              for (JsonElement elem : fData.getAsJsonArray("joinerMappedPorts")) {

                                 allJoinerPorts.add(elem.getAsInt());

                              }

                           }



                           if (allJoinerPorts.isEmpty()) {

                              allJoinerPorts.add(fJoinerMappedPort);

                           }



                           int hostPortRange = cm.punchProfile().defaultPortRange;

                           if (hostSymmetric && fJoinerSymmetric) {

                              hostPortRange = 0;

                           } else if (fJoinerSymmetric && allJoinerPorts.size() > 1) {

                              hostPortRange = 0;

                              VoxLinkMod.LOGGER

                                 .info("[ReversePunch] Birthday attack mode: host punch {} joiner ports: {}", allJoinerPorts.size(), allJoinerPorts);

                            } else if (fJoinerSymmetric) {

                               hostPortRange = cm.punchProfile().widePortRange;

                            } else if (hostSymmetric) {

                                                              // 对称NAT反向socket端口随目标漂移, 按实测delta扩预测带宽
                               if (Math.abs(hostRevDelta) > hostPortRange) {
                                  hostPortRange = Math.min(Math.abs(hostRevDelta) * 2, cm.punchProfile().maxPortRange);
                                  VoxLinkMod.LOGGER.info("[ReversePunch] host sym delta {} exceeds range, expand to ±{}", hostRevDelta, hostPortRange);
                               }
                           } else if (!"moderate".equals(hostNat) && !"port_restricted_cone".equals(hostNat)) {

                               hostPortRange = cm.punchProfile().defaultPortRange;

                            } else {

                               hostPortRange = cm.punchProfile().portPredictionMaxRange;

                            }



                           VoxLinkMod.LOGGER

                              .info(

                                 "[ReversePunch] Host punch to joiner {} (range=±{}, hostNat={}, joinerSym={}, birthdayPorts={})",

                                 new Object[]{fJoinerMappedIp, hostPortRange, hostNat, fJoinerSymmetric, allJoinerPorts.size() > 1 ? allJoinerPorts : "no"}

                              );

                           String clientId = fFrom;

                           UdpHolePuncher currentPuncher = fPuncher;

                           String fRevJoinerMappedIp = fJoinerMappedIp;

                           int fRevJoinerMappedPort = fJoinerMappedPort;

                           // 刷新reverse puncher模板(创建时可能还是旧profile)并保底12s,

                           // 与joiner反向socket的纠偏后窗口充分重叠

                           currentPuncher.setProfile(cm.punchProfile());

                           PunchParams hostRevParams = PunchParams.fromProfile(cm.punchProfile());

                           hostRevParams.timeoutMs = Math.max(hostRevParams.timeoutMs, 12000);

                           currentPuncher.setPunchParams(hostRevParams);

                           ScheduledFuture<?> punchTimeout = cm.scheduler.schedule(() -> {

                              if (cm.activeHolePunchers.get("hostRev") == currentPuncher) {

                                 VoxLinkMod.LOGGER.warn("[ReversePunch] Host reverse punch timeout: {}", clientId);

                                 currentPuncher.cancel();

                                 currentPuncher.close();

                                 cm.activeHolePunchers.remove("hostRev");

                                 cm.hostRevPunchPeer = null;

                              }

                           }, cm.punchProfile().reverseWindowSec, TimeUnit.SECONDS);

                           CompletableFuture<PunchResult> punchFuture;

                           if (allJoinerPorts.size() > 1) {

                              Set<Integer> expandedPorts = new LinkedHashSet<>();



                              for (int port : allJoinerPorts) {

                                 for (int offset = -cm.punchProfile().defaultPortRange; offset <= cm.punchProfile().defaultPortRange; offset++) {

                                    int p = port + offset;

                                    if (p > 0 && p <= 65535) {

                                       expandedPorts.add(p);

                                    }

                                 }

                              }



                              List<Integer> portList = new ArrayList<>(expandedPorts);

                              VoxLinkMod.LOGGER

                                 .info(

                                    "[ReversePunch] Birthday attack: {} ports expanded to {} ports (range {}-{})",

                                    new Object[]{

                                       allJoinerPorts.size(),

                                       portList.size(),

                                       allJoinerPorts.get(0) - cm.punchProfile().defaultPortRange,

                                       allJoinerPorts.get(allJoinerPorts.size() - 1) + cm.punchProfile().defaultPortRange

                                    }

                                 );

                              punchFuture = currentPuncher.punchMultiPort(fRevJoinerMappedIp, portList);

                           } else {

                              punchFuture = currentPuncher.punchWithPortPrediction(fRevJoinerMappedIp, fRevJoinerMappedPort, hostPortRange, true);

                           }



                           punchFuture.thenAccept(

                                 result -> {

                                    if (!result.isSuccess()) {

                                       PunchFailureClassifier.FailureReason reason = PunchFailureClassifier.classify(result);

                                       cm.lastPunchResult = result.withReason(reason);

                                       cm.activePunchParams = 

                                          PunchTuner.nextParams(

                                             cm.punchProfile(), cm.localNatClass, cm.remoteNatClass, 1, 8, reason, cm.lastPunchResult

                                          )

                                       ;

                                       cm.recordPunchFailure(reason, result.socketsReceivedPunch, result.socketsReceivedAck);

                                    } else {

                                       DatagramSocket socket = result.getSuccessSocket();

                                       if (cm.roomManager.currentRoom.get() == fState && cm.connectionWon.compareAndSet(false, true)) {

                                          VoxLinkMod.LOGGER

                                             .info(

                                                "[ReversePunch] Host reverse punch success, connected to joiner {}:{}",

                                                fRevJoinerMappedIp,

                                                fRevJoinerMappedPort

                                             );

                                          currentPuncher.markSocketTransferred();

                                          cm.stopAllPunchingAfterHostBridge();

                                          currentPuncher.stopPunch();

                                          DatagramSocket hostPunchSocket = socket;

                                          UdpHolePuncher hostPuncherRef = currentPuncher;

                                          cm.scheduler

                                             .submit(

                                                () -> {

                                                   try {

                                                      cm.establishUdpTransport(

                                                         fState,

                                                         hostPunchSocket,

                                                         hostPuncherRef,

                                                         new InetSocketAddress(fRevJoinerMappedIp, fRevJoinerMappedPort),

                                                         clientId,

                                                         true,

                                                         clientId

                                                      );

                                                   } catch (Exception e) {

                                                      VoxLinkMod.LOGGER.error("[ReversePunch] Host UDP transport create failed: {}", e.getMessage());

                                                      hostPuncherRef.close();

                                                   }

                                                }

                                             );

                                       } else {

                                          currentPuncher.close();

                                       }

                                    }

                                 }

                              )

                              .exceptionally(e -> {

                                 punchTimeout.cancel(false);

                                 VoxLinkMod.LOGGER.warn("[ReversePunch] Host reverse punch failed {}: {}", clientId, e.getMessage());

                                 currentPuncher.cancel();

                                 currentPuncher.close();

                                 cm.activeHolePunchers.remove("hostRev");

                                 return null;

                              });

                        }

                     );

               }

            } else {

               VoxLinkMod.LOGGER.warn("[ReversePunch] Invalid reverse_holepunch_offer: no mapped address");

            }

         }

      }

   }
   static void handleReversePunchInfo(ConnectionManager cm, String from, JsonObject data) {

      RoomManager.RoomState state = cm.roomManager.currentRoom.get();

      if (state != null && state != RoomManager.PENDING && !state.roomInfo.isHost()) {

         if (!cm.connectionCycleActive.get()) {

            VoxLinkMod.LOGGER.info("[ReversePunch] Not in connection cycle, ignore reverse_punch_info");

         } else {

            String hostMappedIp = null;

            int hostMappedPort = 0;

            if (data.has("hostMappedIp") && !data.get("hostMappedIp").isJsonNull()) {

               hostMappedIp = data.get("hostMappedIp").getAsString();

            }



            if (data.has("hostMappedPort") && !data.get("hostMappedPort").isJsonNull()) {

               hostMappedPort = data.get("hostMappedPort").getAsInt();

            }



            boolean hostSymmetric = data.has("hostSymmetric") && data.get("hostSymmetric").getAsBoolean();

            boolean hostEasySym = data.has("hostEasySym") && !data.get("hostEasySym").isJsonNull() && data.get("hostEasySym").getAsBoolean();

            if (hostSymmetric) {

               state.roomInfo.setHostSymmetric(true);

            }



            if (hostEasySym) {

               state.roomInfo.setHostEasySym(true);

            }



            UdpHolePuncher existingPuncher = cm.activeHolePunchers.get("joiner_reverse");

            if (existingPuncher != null && existingPuncher.isPunching()) {

               int updatePort = hostMappedPort;

               int delta = state.roomInfo.getHostMappedPortDelta();

               if (hostMappedPort > 0 && delta != 0) {

                  int predicted = hostMappedPort + delta;

                  if (predicted > 0 && predicted <= 65535) {

                     updatePort = predicted;

                  }

               }



               VoxLinkMod.LOGGER.info("[ReversePunch] Already reverse punching, update target to {}:{}", hostMappedIp, updatePort);

               if (hostMappedIp != null && updatePort > 0) {

                  existingPuncher.updateTarget(hostMappedIp, updatePort);

               }



               UdpHolePuncher joinerPuncher = cm.activeHolePunchers.get("joiner");

               if (joinerPuncher != null && joinerPuncher.isPunching() && hostMappedIp != null && updatePort > 0) {

                  VoxLinkMod.LOGGER.info("[ReversePunch] Sync update main joiner punch target to {}:{}", hostMappedIp, updatePort);

                  joinerPuncher.updateTarget(hostMappedIp, updatePort);

               }

            } else {

               if (hostMappedIp == null || hostMappedPort <= 0) {

                  hostMappedIp = state.roomInfo.getHostMappedIp();

                  hostMappedPort = state.roomInfo.getHostMappedPort();

               }



               if (hostMappedIp != null && hostMappedPort > 0) {

                  if (hostSymmetric) {

                     state.roomInfo.setHostSymmetric(true);

                  }



                  VoxLinkMod.LOGGER

                     .info("[ReversePunch] Joiner received reverse_punch_info: {}:{} (hostSym={})", new Object[]{hostMappedIp, hostMappedPort, hostSymmetric});

                  List<UdpHolePuncher> birthdayPunchers = new ArrayList<>();

                  List<String> birthdayKeys = new ArrayList<>();



                  for (Entry<String, UdpHolePuncher> entry : cm.activeHolePunchers.entrySet()) {

                     if (entry.getKey().startsWith("joiner_birthday_")) {

                        birthdayPunchers.add(entry.getValue());

                        birthdayKeys.add(entry.getKey());

                     }

                  }



                  if (!birthdayPunchers.isEmpty()) {

                     boolean anyPunching = birthdayPunchers.stream().anyMatch(UdpHolePuncher::isPunching);

                     if (anyPunching) {

                        VoxLinkMod.LOGGER

                           .info(

                              "[BirthdayPunch] Already punching, update target to {}:{}, {} sockets total",

                              new Object[]{hostMappedIp, hostMappedPort, birthdayPunchers.size()}

                           );



                        for (UdpHolePuncher p : birthdayPunchers) {

                           if (p.isPunching()) {

                              p.updateTarget(hostMappedIp, hostMappedPort);

                           }

                        }

                     } else {

                        VoxLinkMod.LOGGER

                           .info(

                              "[BirthdayPunch] Start birthday attack {} sockets punch to {}:{}",

                              new Object[]{birthdayPunchers.size(), hostMappedIp, hostMappedPort}

                           );

                        cm.startBirthdayPunchPhase2(state, birthdayPunchers, birthdayKeys, hostMappedIp, hostMappedPort, hostSymmetric, false);

                     }

                  } else {

                     UdpHolePuncher puncher = cm.activeHolePunchers.get("joiner_reverse");

                     if (puncher != null && puncher.getSocket() != null && !puncher.getSocket().isClosed()) {

                        VoxLinkMod.LOGGER

                           .info(

                              "[ReversePunch] Joiner reverse punch state: localPort={}, punching={}", puncher.getSocket().getLocalPort(), puncher.isPunching()

                           );

                        puncher.setOnPeerPunchReceived(

                           addr -> {

                              cm.sessionPunchRecvEver.set(true);

                              VoxLinkMod.LOGGER

                                 .info(

                                    "[ReversePunch] Joiner received peer punch packet {}:{} — send peer_port signal",

                                    addr.getAddress().getHostAddress(),

                                    addr.getPort()

                                 );

                              String code = state.roomInfo.getCode();

                              String token = state.roomInfo.getToken();

                              JsonObject portData = new JsonObject();

                              portData.addProperty("peer_ip", addr.getAddress().getHostAddress());

                              portData.addProperty("peer_port", addr.getPort());

                              cm.signalingClient.sendSignal(code, token, false, "peer_port", portData, "host").exceptionally(e -> {

                                 VoxLinkMod.LOGGER.debug("peer_port signal send failed: {}", e.getMessage());

                                 return null;

                              });

                           }

                        );

                        boolean joinerIsSymmetric = cm.stunProbeResult != null && cm.stunProbeResult.natType.isSymmetric();

                        int portRange = cm.punchProfile().defaultPortRange;

                        if (joinerIsSymmetric) {

                           portRange = cm.punchProfile().defaultPortRange;

                           VoxLinkMod.LOGGER.info("[ReversePunch] Joiner is symmetric NAT — use small range (±30) to open NAT mapping");

                        } else if (hostSymmetric) {
                           portRange = cm.punchProfile().portPredictionMaxRange;
                           int hostDelta = state.roomInfo.getHostMappedPortDelta();
                           if (hostDelta > portRange) {
                              portRange = Math.min(hostDelta * 2, cm.punchProfile().maxPortRange);
                              VoxLinkMod.LOGGER.info("[ReversePunch] host sym delta {} exceeds range, expand to ±{}", hostDelta, portRange);
                           }
                        } else {
                           portRange = cm.punchProfile().defaultPortRange;
                        }



                        VoxLinkMod.LOGGER

                           .info(

                              "[ReversePunch] Joiner punch to host {}:{} (range=±{}, joinerSym={}, hostSym={})",

                              new Object[]{hostMappedIp, hostMappedPort, portRange, joinerIsSymmetric, hostSymmetric}

                           );

                        UdpHolePuncher finalPuncher = puncher;

                        String fHostMappedIp = hostMappedIp;

                        int fHostMappedPort = hostMappedPort;

                        puncher.punchWithPortPrediction(hostMappedIp, hostMappedPort, portRange, true)

                           .thenAccept(

                              result -> {

                                 if (!result.isSuccess()) {

                                    PunchFailureClassifier.FailureReason reason = PunchFailureClassifier.classify(result);

                                    cm.lastPunchResult = result.withReason(reason);

                                    cm.activePunchParams = 

                                       PunchTuner.nextParams(

                                          cm.punchProfile(), cm.localNatClass, cm.remoteNatClass, 1, 8, reason, cm.lastPunchResult

                                       )

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

                                       } catch (Exception var11) {

                                       }

                                    }

                                 }

                              }

                           )

                           .exceptionally(e -> {

                              VoxLinkMod.LOGGER.warn("[ReversePunch] Joiner reverse punch failed: {}", e.getMessage());

                              finalPuncher.cancel();

                              finalPuncher.close();

                              cm.activeHolePunchers.remove("joiner_reverse");

                              cm.showConnectFailed(state, "voxlink.connection.reverse_punch_failed");

                              return null;

                           });

                     } else {

                        VoxLinkMod.LOGGER

                           .warn(

                              "[ReversePunch] No joiner_reverse puncher available (puncher={}, socket={}, closed={})",

                              new Object[]{

                                 puncher != null,

                                 puncher != null ? puncher.getSocket() != null : false,

                                 puncher != null && puncher.getSocket() != null ? puncher.getSocket().isClosed() : false

                              }

                           );

                        cm.showConnectFailed(state, "voxlink.connection.reverse_punch_failed");

                     }

                  }

               } else {

                  VoxLinkMod.LOGGER.warn("[ReversePunch] No host mapped address in reverse_punch_info");

               }

            }

         }

      }

   }
   static void handleTcpSimopenRequest(ConnectionManager cm, String from, JsonObject data) {

      String joinerMappedIp = data.has("joinerMappedIp") ? data.get("joinerMappedIp").getAsString() : null;

      int joinerMappedPort = data.has("joinerMappedPort") ? data.get("joinerMappedPort").getAsInt() : 0;

      if (joinerMappedIp != null && joinerMappedPort != 0) {

         if (cm.connectionWon.get()) {

            VoxLinkMod.LOGGER.info("[TcpSimOpen] already connected, ignoring tcp_simopen_request");

         } else {

            int hostPort = P2PBridge.getHostPort() > 0 ? P2PBridge.getHostPort() : 25565;

            if (P2PBridge.getHostPort() > 0) {

               VoxLinkMod.LOGGER.info("[TcpSimOpen] Host bridge listening port={}, skip SimOpen", hostPort);

            } else {

               VoxLinkMod.LOGGER

                  .info("[TcpSimOpen] Host received joiner {} request, try TCP connect {}:{}", new Object[]{from, joinerMappedIp, joinerMappedPort});

               ConnectionFallback hostSimFallback = cm.trackFallback(new ConnectionFallback());

               hostSimFallback.tryTcpSimultaneousOpen(joinerMappedIp, joinerMappedPort, hostPort).thenAccept(result -> {

                  if (result.success && cm.connectionWon.compareAndSet(false, true)) {

                     VoxLinkMod.LOGGER.info("[TcpSimOpen] Host connected to joiner via TCP SimOpen!");

                     RoomManager.RoomState st = cm.roomManager.currentRoom.get();

                     if (st != null) {

                        cm.connectViaBridge(st, result);

                     }

                  } else if (result.success) {

                     VoxLinkMod.LOGGER.info("[TcpSimOpen] TCP SimOpen success but connection occupied, ignore");

                  } else {

                     VoxLinkMod.LOGGER.info("[TcpSimOpen] Host TCP SimOpen failed: {}", result.failureReason);

                  }

               });

            }

         }

      }

   }
   static void handleHolePunchAnswer(ConnectionManager cm, String from, JsonObject data) {

      RoomManager.RoomState state = cm.roomManager.currentRoom.get();

      if (state != null && state != RoomManager.PENDING && state.roomInfo.isHost()) {

         VoxLinkMod.LOGGER.info("Received punch ack from joiner {}", from);

      }

   }
   static String peerPunchPrefix(ConnectionManager cm, String peerId) {
      return "hp_" + peerId + "_";
   }
   static HostPunchContext hostPunchContext(ConnectionManager cm, String peerId) {
      return cm.hostPunchContexts.computeIfAbsent(peerId != null ? peerId : "", k -> new HostPunchContext());
   }
   static boolean isAnyHostPunching(ConnectionManager cm) {
      for (HostPunchContext c : cm.hostPunchContexts.values()) {
         if (c.punching) {
            return true;
         }
      }

      return false;
   }
   static void clearHostPunchContexts(ConnectionManager cm) {
      cm.hostPunchContexts.clear();
   }
   static void expireStaleHostPunchContexts(ConnectionManager cm) {

      long nowMs = System.currentTimeMillis();

      for (Entry<String, HostPunchContext> e : cm.hostPunchContexts.entrySet()) {

         HostPunchContext c = e.getValue();

         if (c == null || !c.punching) {

            continue;

         }

         String prefix = cm.peerPunchPrefix(e.getKey());

         boolean groupAlive = false;

         for (String k : cm.activeHolePunchers.keySet()) {

            if (k.startsWith(prefix)) {

               groupAlive = true;

               break;

            }

         }

         if (!groupAlive && nowMs - c.punchStartMs > 5000L && nowMs - c.punchStartMs < 300000L) {

            c.punching = false;

            VoxLinkMod.LOGGER.info("[HostPunch] Expire stale punching flag for client {} (punch group gone)", e.getKey());

         }

      }

   }
}
