package icu.wuhui.voxlink.room;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import icu.wuhui.voxlink.VoxLinkMod;

import icu.wuhui.voxlink.network.NatClass;

import icu.wuhui.voxlink.platform.Text;

import icu.wuhui.voxlink.platform.ChatSink;

import java.util.List;

import java.util.concurrent.TimeUnit;

import icu.wuhui.voxlink.network.RelayBridge;
import icu.wuhui.voxlink.network.PunchTuner;
import icu.wuhui.voxlink.network.UdpPath;
import icu.wuhui.voxlink.network.PunchFailureClassifier;
import icu.wuhui.voxlink.platform.ClientThread;
import icu.wuhui.voxlink.network.ConnectionHelper;
import icu.wuhui.voxlink.network.TurnRelayClient;
import icu.wuhui.voxlink.network.UdpHolePuncher;
import icu.wuhui.voxlink.network.P2PBridge;
import java.net.DatagramSocket;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicBoolean;
import java.net.InetSocketAddress;

import icu.wuhui.voxlink.network.ReliableUdpTransport;

import icu.wuhui.voxlink.network.SignalingClient;

import icu.wuhui.voxlink.network.ConnectionFallback;

import icu.wuhui.voxlink.network.PunchProfile;

import icu.wuhui.voxlink.network.ProtocolNegotiator;

import java.util.Set;

import java.util.HashSet;

import java.util.Map;

import java.util.ArrayList;

import java.util.concurrent.ScheduledFuture;

import java.util.concurrent.CompletableFuture;

/** 中继导演: 中继请求/接受/ready/setup、自动中继编排、换道、验证看门狗、fallback。(Phase4 从 ConnectionManager 拆出) */
public final class RelayOrchestrator {
   private RelayOrchestrator() {
   }

   static boolean isRelayPeerFailed(ConnectionManager cm, String clientId) {

      Long t = cm.failedRelayPeers.get(clientId);

      if (t == null) {

         return false;

      } else if (System.currentTimeMillis() - t > 60000L) {

         cm.failedRelayPeers.remove(clientId, t);

         return false;

      } else {

         return true;

      }

   }
   /** TURN 判死只冷却 60s：瞬时故障不应判整个会话死刑，到期允许再试。 */
   static boolean turnUnhealthyActive(ConnectionManager cm) {
      return cm.autoRelayTurnUnhealthy
         && System.currentTimeMillis() - cm.autoRelayTurnUnhealthyAtMs < 60_000L;
   }

   /**
    * 服务端上报的 natType 是否属于"对称类"（含早期 API 遗留值 strict/moderate）。
    *
    * 必须整词判定，不能用 contains("strict")：StunProbe.NatType.key 里最常见的一档就叫
    * port_restricted_cone / restricted_cone，字面量里嵌着 "strict"（po-rt_re-strict-ed），
    * 子串匹配会把全部锥型候选/锥型目标误判成对称 NAT —— 线上 12 次 relay_request 里
    * 9 次 "No available Cone relay node" 就是这个假阳性的后果。
    */
   static boolean isSymmetricNatType(String nt) {
      if (nt == null) {
         return false;
      }
      String k = nt.trim().toLowerCase(java.util.Locale.ROOT);
      switch (k) {
         case "symmetric":
         case "symmetric_easy_inc":
         case "symmetric_easy_dec":
         case "strict": // 早期 API 遗留值，语义即对称/严格 NAT
            return true;
         case "unknown":
         case "":
         case "open":
         case "moderate": // 早期 API 里 open/moderate 都是锥型
         case "full_cone":
         case "restricted_cone":
         case "port_restricted_cone":
            return false;
         default:
            // 兜底：新出现的对称细分名（不含 cone 的 sym*）按对称处理
            return k.contains("sym") && !k.contains("cone");
      }
   }

   /** natType 是否"未知/空"（既不能当中继节点，也不能当被中继目标）。 */
   static boolean isUnknownNatType(String nt) {
      if (nt == null) {
         return true;
      }
      String k = nt.trim().toLowerCase(java.util.Locale.ROOT);
      return k.isEmpty() || k.equals("unknown");
   }

   /** 可当中继节点的 NAT：既非对称也非未知（锥型才算）。 */
   static boolean isConeNatType(String nt) {
      return !isSymmetricNatType(nt) && !isUnknownNatType(nt);
   }

   /** 需要中继的 NAT：对称类或未知（与 {@link #isConeNatType} 互补）。 */
   static boolean isRelayNeederNatType(String nt) {
      return !isConeNatType(nt);
   }
   static ConnectionFallback trackFallback(ConnectionManager cm, ConnectionFallback f) {

      cm.activeFallbacks.add(f);

      return f;

   }
   static void cancelAllFallbacks(ConnectionManager cm) {

      for (ConnectionFallback f : cm.activeFallbacks) {

         try {

            f.cancel();

         } catch (Exception var4) {

         }

      }



      cm.activeFallbacks.clear();

   }
   static void triggerManualRelay(ConnectionManager cm) {

      if (VoxLinkMod.getConfig().isRelayEnabled()) {

         RoomManager.RoomState state = cm.roomManager.currentRoom.get();

         if (state != null && state != RoomManager.PENDING) {

            if (!cm.manualRelayInProgress) {

               cm.manualRelayInProgress = true;
               icu.wuhui.voxlink.ui.UiLogBus.push(0, "voxlink.logui.player_relay");

               // 启动 90s 总超时：tryRelay 递归批次(8s/批) 多了会无限循环, 必须有兜底。
               cm.manualRelayDeadline = System.currentTimeMillis() + 90_000L;

               int currentRound = cm.continuousRetryRound.get();

               boolean isSymmetric = cm.stunProbeResult != null && cm.stunProbeResult.natType.isSymmetric();

               cm.nextRelayEligibleRound = currentRound + (isSymmetric ? 2 : 4);

               VoxLinkMod.LOGGER.info("[Relay] Manual relay triggered at round={}, next eligible round={}", currentRound, cm.nextRelayEligibleRound);

               cm.stopAllPunchingForRelay();

               state.roomInfo.setConnectionMode(Text.translatable("voxlink.relay.trying"));

               cm.tryRelay(state);

            }

         }

      }

   }
   static boolean isManualRelayInProgress(ConnectionManager cm) {

      return cm.manualRelayInProgress;

   }
   static int allocateRelayMuxChan(ConnectionManager cm) {
      return 1 + (Math.abs(cm.relayMuxChanCounter.getAndIncrement()) % 7);
   }
   static Text getRelayProgressText(ConnectionManager cm) {

      return cm.relayProgressText;

   }
   static void notifyRelayFailed(ConnectionManager cm) {

      cm.relayGlobalEpoch.incrementAndGet();
      if (cm.manualRelayInProgress) {

         cm.manualRelayInProgress = false;

         // 失败后把 nextRelayEligibleRound 拉回当前轮: 玩家立即可再点按钮或回到直连,

         // 与 "中继失败，继续尝试直连打洞..." 的按钮文案语义对齐。

         int currentRound = cm.continuousRetryRound.get();

         if (currentRound > 0) {

            cm.nextRelayEligibleRound = currentRound;

         }

         cm.relayProgressText = null;

         cm.manualRelayDeadline = 0L;

         VoxLinkMod.LOGGER.info("[Relay] Manual relay failed, resume hole punching");

      }

   }
   static void stopRelay(ConnectionManager cm) {

      VoxLinkMod.LOGGER.info("[Relay] stopRelay: cleanup relay state");

      cm.manualRelayInProgress = false;

      cm.clearRelayTracking();

      RelayBridge.getInstance(cm.scheduler).stopAllRelays();

      RoomManager.RoomState state = cm.roomManager.currentRoom.get();

      if (state != null && state != RoomManager.PENDING && state.roomInfo.isUsingRelay()) {

         state.roomInfo.setUsingRelay(false);

         ReliableUdpTransport t = cm.activeUdpTransports.remove("relay_cone");

         if (t != null) {

            try {

               t.close();

            } catch (Exception var4) {

            }

         }



         P2PBridge.disconnect();

      }

   }
   static void connectViaBridge(ConnectionManager cm, RoomManager.RoomState state, ConnectionFallback.ConnectResult result) {

      if (cm.dualRaceActive) {

         cm.killAllConnectionAttempts("terracotta");

      }



      cm.killAllConnectionAttempts();

      P2PBridge.cancelPendingUdpTimeouts();



      for (ReliableUdpTransport transport : cm.activeUdpTransports.values()) {

         try {

            transport.close();

         } catch (Exception var6) {

         }

      }



      cm.activeUdpTransports.clear();

      state.roomInfo.setConnectionMode(Text.translatable("voxlink.connection.punching"));

      String remoteHost = result.remoteHost;

      int remotePort = result.remotePort;

      if (result.mode == ConnectionFallback.ConnectionMode.IPV6_DIRECT) {

         P2PBridge.connectToHostIpv6(remoteHost, remotePort).thenAccept(localPort -> {

            if (localPort > 0) {

               if (cm.dualRaceActive) {

                  cm.claimVoxlinkDualWin();

               }



               cm.connectionWon.set(true);

               cm.markConnectionEstablished();



               cm.connectionCycleActive.set(false);

               cm.signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), false, "connected", new JsonObject(), "host");

               state.roomInfo.setConnectionMode(Text.translatable("voxlink.connection.bridge_setup"));

               ConnectionHelper.connectToServer(localPort, state.roomInfo);

               cm.notifyDualVoxlinkBridge(true);

            } else {

               cm.connectionCycleActive.set(false);

               ConnectionHelper.resetConnecting();

               state.roomInfo.setConnectionMode(Text.translatable("voxlink.connection.bridge_start_failed"), true);

               cm.signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), false, "disconnect", new JsonObject(), "host");

               if (!cm.dualRaceActive) {

                  cm.handleConnectViaBridgeFailed(state);

               }



               cm.notifyDualVoxlinkBridge(false);

            }

         });

      } else {

         P2PBridge.connectToHost(remoteHost, remotePort).thenAccept(localPort -> {

            if (localPort > 0) {

               if (cm.dualRaceActive) {

                  cm.claimVoxlinkDualWin();

               }



               cm.connectionWon.set(true);

               cm.markConnectionEstablished();



               cm.connectionCycleActive.set(false);

               cm.signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), false, "connected", new JsonObject(), "host");

               state.roomInfo.setConnectionMode(Text.translatable("voxlink.connection.bridge_setup"));

               ConnectionHelper.connectToServer(localPort, state.roomInfo);

               cm.notifyDualVoxlinkBridge(true);

            } else {

               cm.connectionCycleActive.set(false);

               ConnectionHelper.resetConnecting();

               state.roomInfo.setConnectionMode(Text.translatable("voxlink.connection.bridge_start_failed"), true);

               cm.signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), false, "disconnect", new JsonObject(), "host");

               if (!cm.dualRaceActive) {

                  cm.handleConnectViaBridgeFailed(state);

               }



               cm.notifyDualVoxlinkBridge(false);

            }

         });

      }

   }
   static void connectFed(ConnectionManager cm, RoomManager.RoomState state) {
      String addr = state.roomInfo.getFedAddress();
      int sep = addr == null ? -1 : addr.lastIndexOf(':');
      if (sep <= 0 || sep == addr.length() - 1) {
         state.roomInfo.setConnectionMode(Text.translatable("voxlink.connection.fed_failed"), true);
         return;
      }
      String host = addr.substring(0, sep);
      int port;
      try {
         port = Integer.parseInt(addr.substring(sep + 1));
      } catch (NumberFormatException e) {
         state.roomInfo.setConnectionMode(Text.translatable("voxlink.connection.fed_failed"), true);
         return;
      }
      state.roomInfo.setConnectionMode(Text.translatable("voxlink.connection.fed_setup"));
      P2PBridge.connectToHost(host, port).thenAccept(localPort -> {
         if (localPort > 0) {
            cm.connectionWon.set(true);
            cm.markConnectionEstablished();
            ConnectionHelper.connectToServer(localPort, state.roomInfo);
         } else {
            ConnectionHelper.resetConnecting();
            state.roomInfo.setConnectionMode(Text.translatable("voxlink.connection.fed_failed"), true);
         }
      });
   }
   static void handleConnectViaBridgeFailed(ConnectionManager cm, RoomManager.RoomState state) {

      cm.leaveRoomOnFailure(state);

   }
   static void startFallbackMonitor(ConnectionManager cm, ConnectionFallback fallback, RoomManager.RoomState state) {

      Thread t = new Thread(() -> {

         int loopCount = 0;



         while (!fallback.isSettled() && !fallback.isCancelled()) {

            if (loopCount >= 200) {

               VoxLinkMod.LOGGER.warn("Fallback monitor timeout (about 60s)");

               break;

            }



            Text status = fallback.getStatusText();

            if (status != null) {

               state.roomInfo.setConnectionMode(status);

            }



            loopCount++;



            try {

               Thread.sleep(300L);

            } catch (InterruptedException e) {

               return;

            }

         }



         Text finalStatus = fallback.getStatusText();

         if (finalStatus != null) {

            state.roomInfo.setConnectionMode(finalStatus);

         }

      }, "VoxLink-FallbackMonitor");

      t.setDaemon(true);

      t.start();

   }
   static void tryRelay(ConnectionManager cm, RoomManager.RoomState state) {
      tryRelay(cm, state, null);
   }

   /** requesterClientId 非空=按 relay_request 请求者定向派发(多 sym 修正)；null=旧扫描行为。 */
   static void tryRelay(ConnectionManager cm, RoomManager.RoomState state, String requesterClientId) {

      // 总超时: 90s 内没成功就停递归, 走 failed_timeout 分桶; 房客 20s 等待不受影响。
      if (cm.manualRelayInProgress && cm.manualRelayDeadline > 0L
         && System.currentTimeMillis() >= cm.manualRelayDeadline) {
         VoxLinkMod.LOGGER.warn("[Relay] Manual relay overall timeout (90s) reached, abort recursive tryRelay");
         cm.manualRelayDeadline = 0L;
         cm.notifyRelayFailed();
         cm.relayFailOrAuto(state, "voxlink.relay.failed_timeout");
         return;
      }

      if (!VoxLinkMod.getConfig().isRelayEnabled()) {

         VoxLinkMod.LOGGER.info("[Relay] Relay disabled by config, skip tryRelay");

         cm.notifyRelayFailed();

         cm.relayFailOrAuto(state, "voxlink.connection.relay_unavailable");

      } else if (cm.currentRelayPeer.get() != null) {

         VoxLinkMod.LOGGER.info("[Relay] Relay already trying (current={}), skip duplicate", cm.currentRelayPeer.get());

      } else {

         if (!state.roomInfo.isHost()) {

            if (cm.currentRelayPeer.get() != null) {

               return;

            }



            cm.currentRelayPeer.set("joiner_requesting");

            // 房客发起 relay_request 后下方槽位: "正在寻找适合中继的玩家…"
            cm.relayProgressText = Text.translatable("voxlink.relay.searching");

            JsonObject data = new JsonObject();

            data.addProperty("clientId", state.roomInfo.getClientId());

            cm.signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), false, "relay_request", data, "host");

            cm.relayFailoverTask = cm.scheduler.schedule(() -> {

               if (!cm.connectionWon.get()) {

                  cm.currentRelayPeer.set(null);

                  cm.relayFailoverTask = null;

                  cm.notifyRelayFailed();

                  cm.relayFailOrAuto(state, "voxlink.relay.failed_host_no_response");

               }

            }, 20L, TimeUnit.SECONDS);

         } else {

            List<RoomInfo.PeerInfo> candidates = new ArrayList<>();



            for (RoomInfo.PeerInfo p : state.roomInfo.getPeers()) {

               String nt = p.natType;

               if (nt != null

                  && p.mappedIp != null

                  && p.mappedPort > 0

                  && !cm.isRelayPeerFailed(p.clientId)

                  && cm.activeUdpTransports.get(p.clientId) != null

                  && isConeNatType(nt)) {

                  if (!ProtocolNegotiator.supportsRelay(p)) {

                     VoxLinkMod.LOGGER.info("[Relay] Candidate {} is legacy, skip", p.clientId);

                  } else if (!ProtocolNegotiator.supportsRelayMux(p) || !cm.activeUdpTransports.get(p.clientId).isMuxEnabled()) {

                     // v2 数据面门槛：候选者必须声明 relayMuxV1 且其传输已 mux 化，
                     // 否则走不了子通道（v1 裸流转发已被实证为串流污染，绝不降级回去）
                     VoxLinkMod.LOGGER.info("[Relay] Candidate {} lacks relayMuxV1/mux transport, skip", p.clientId);

                  } else {

                     candidates.add(p);

                  }

               }

            }



            if (candidates.isEmpty()) {
               // v3 全局大厅候选池：房内无候选不再是终局——找需中继的 sym 玩家后
               // 转全局池派发（大厅里开中继、非对称 NAT、声明 relayMuxV1 的其他房玩家）
               RoomInfo.PeerInfo sym = null;
               for (RoomInfo.PeerInfo p : state.roomInfo.getPeers()) {
                  String nt = p.natType;
                  if (!isRelayNeederNatType(nt)) {
                     continue;
                  }
                  if (!ProtocolNegotiator.supportsRelay(p)) {
                     continue;
                  }
                  sym = p;
                  break;
               }
               if (sym == null || sym.mappedIp == null) {
                  VoxLinkMod.LOGGER.warn("[Relay] No symmetric NAT player needs relay");
                  cm.notifyRelayFailed();
                  cm.relayFailOrAuto(state, "voxlink.connection.relay_unavailable");
                  return;
               }
               dispatchGlobalRelay(cm, state, sym);
               return;
            }
            // 阶段文本: 筛选到候选, 即将并行尝试 → "正在寻找适合中继的玩家…" 闪一瞬, 然后立刻被 trying_batch 覆盖。
            cm.relayProgressText = Text.translatable("voxlink.relay.searching");



            RelayBridge relayBridge = RelayBridge.getInstance(cm.scheduler);

            candidates.sort((a, b) -> {

               int loadA = relayBridge.getRelayCountForPeer(a.clientId);

               int loadB = relayBridge.getRelayCountForPeer(b.clientId);

               if (loadA != loadB) {

                  return Integer.compare(loadA, loadB);

               }



               ReliableUdpTransport ta = cm.activeUdpTransports.get(a.clientId);

               ReliableUdpTransport tb = cm.activeUdpTransports.get(b.clientId);

               return Long.compare(ta != null ? ta.getRtoMs() : Long.MAX_VALUE, tb != null ? tb.getRtoMs() : Long.MAX_VALUE);

            });

            int parallelN = Math.min(3, candidates.size());

            List<RoomInfo.PeerInfo> relayCandidates = candidates.subList(0, parallelN);

               // 多 sym 修正: 请求者才是被中继对象。旧逻辑扫"第一个 sym 玩家"，
               // 双 sym 房间会把 A 的中继建到 B 头上(A 超时, B 莫名被中继)
               RoomInfo.PeerInfo symPeer = null;

               if (requesterClientId != null) {

                  RoomInfo.PeerInfo req = state.roomInfo.getPeer(requesterClientId);

                  if (req != null && req.mappedIp != null && req.mappedPort > 0 && ProtocolNegotiator.supportsRelay(req)) {

                     symPeer = req;

                     VoxLinkMod.LOGGER.info("[Relay] targeting requester {} directly (multi-sym fix)", requesterClientId);

                  }

               }

               Iterator i = state.roomInfo.getPeers().iterator();



            while (true) {

               if (i.hasNext()) {

                  RoomInfo.PeerInfo p = (RoomInfo.PeerInfo)i.next();

                  String nt = p.natType;

                  if (!isRelayNeederNatType(nt)) {

                     continue;

                  }



                  if (!ProtocolNegotiator.supportsRelay(p)) {

                     VoxLinkMod.LOGGER.info("[Relay] Target Sym player {} is legacy, skip relay", p.clientId);

                     continue;

                  }



                  if (symPeer == null) {
                     symPeer = p;
                  }

               }



               if (symPeer == null || symPeer.mappedIp == null) {

                  VoxLinkMod.LOGGER.warn("[Relay] No symmetric NAT player needs relay");

                  cm.notifyRelayFailed();

                  cm.relayFailOrAuto(state, "voxlink.connection.relay_unavailable");

                  return;

               }



               cm.currentRelayPeer.set(relayCandidates.get(0).clientId);

               int batchNo = cm.relayBatchIndex.incrementAndGet();

               cm.relayBatchSize.set(parallelN);

               // 阶段文案: 房主分支每次开始新批次时显示「第 N 轮：M 个候选…」, 失败/成功时清空。
               cm.relayProgressText = Text.translatable("voxlink.relay.trying_batch",

                  new Object[]{Integer.toString(batchNo), Integer.toString(parallelN)});

               VoxLinkMod.LOGGER.info("[Relay] Parallel try {} Cone relays, target Sym={}", parallelN, symPeer.clientId);



               for (int ix = 0; ix < parallelN; ix++) {

                  RoomInfo.PeerInfo relay = relayCandidates.get(ix);

                  JsonObject setup = new JsonObject();

                  setup.addProperty("targetClientId", symPeer.clientId);

                  setup.addProperty("targetIp", symPeer.mappedIp);

                  setup.addProperty("targetPort", symPeer.mappedPort);
                  setup.addProperty("replyHostId", cm.relayPeerId(state));

                  cm.signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), true, "relay_setup", setup, relay.clientId);

                  JsonObject notify = new JsonObject();

                  notify.addProperty("relayIp", relay.mappedIp);

                  notify.addProperty("relayPort", relay.mappedPort);

                  cm.signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), true, "relay_notify", notify, symPeer.clientId);

                  VoxLinkMod.LOGGER

                     .info(

                        "[Relay] Concurrent #{} relay={} (load={}) {}:{}",

                        new Object[]{ix + 1, relay.clientId, relayBridge.getRelayCountForPeer(relay.clientId), relay.mappedIp, relay.mappedPort}

                     );

               }



               cm.relayFailoverTask = cm.scheduler.schedule(() -> {

                  if (!cm.connectionWon.get() && cm.roomManager.currentRoom.get() == state) {

                     VoxLinkMod.LOGGER.warn("[Relay] Parallel relay all timeout, mark {} failed", parallelN);



                     for (RoomInfo.PeerInfo r : relayCandidates) {

                        cm.failedRelayPeers.put(r.clientId, System.currentTimeMillis());

                     }



                     cm.currentRelayPeer.set(null);

                     cm.relayFailoverTask = null;

                     cm.tryRelay(state);

                  }

               }, 8L, TimeUnit.SECONDS);

               break;

            }

         }

      }

   }
   static void clearRelayTracking(ConnectionManager cm) {

      cm.relayGlobalEpoch.incrementAndGet();
      cm.currentRelayPeer.set(null);

      if (cm.relayFailoverTask != null) {

         cm.relayFailoverTask.cancel(false);

         cm.relayFailoverTask = null;

      }

   }
   static void handleRelayRequest(ConnectionManager cm, String from, JsonObject data) {

      RoomManager.RoomState state = cm.roomManager.currentRoom.get();

      if (state != null && state != RoomManager.PENDING) {

         String requestingClientId = data.has("clientId") ? data.get("clientId").getAsString() : from;

         boolean standbyRequest = data.has("standby") && data.get("standby").getAsBoolean();
         VoxLinkMod.LOGGER.info("[Relay] Received relay_request, requester={}, standby={}", requestingClientId, standbyRequest);

         RoomInfo.PeerInfo requestingPeerCheck = state.roomInfo.getPeer(requestingClientId);

         if (requestingPeerCheck != null && !ProtocolNegotiator.supportsRelay(requestingPeerCheck)) {

            VoxLinkMod.LOGGER.info("[Relay] Requester {} is legacy, no relay_request response", requestingClientId);

         } else {

            // 仅房主向玩家展示中继提示；房客收到 relay_request 不弹"房主"提示
            if (state.roomInfo.isHost() && !standbyRequest) {

               // 信令线程不可直调 GUI：包一层主线程调度

               // 节流: 多房客同时发 relay_request 时, 房主 toast 不再每请求一次弹一次, 改为 10s 一次。
               long nowMs = System.currentTimeMillis();
               boolean shouldShowNotice = (nowMs - cm.lastHostNoticeAt) >= 10_000L;
               if (shouldShowNotice) {
                  cm.lastHostNoticeAt = nowMs;
               }

               if (shouldShowNotice) {

                  ClientThread.runOnClientThread(() -> {

                     ChatSink.chat(Text.translatable("voxlink.relay.host_notice"));

                  });

               }

            }



            RoomInfo.PeerInfo requestingPeer = state.roomInfo.getPeer(requestingClientId);

            if (requestingPeer != null && requestingPeer.mappedIp != null && requestingPeer.mappedPort > 0) {

               // 玩家中继 v2 候选池（2026-10-05 用户裁决修订）：
               // 优先本房间内已连接的非对称 NAT 玩家（mux 数据面要求候选者与请求方 host
               // 之间有已建立传输）；跨房全局池需要"虚拟加入"对洞机制，下迭代接入同一套数据面。
            // v1 全局池路径（fetchGlobalRelayCandidates）已被实证为跨房间数据注入（候选者
            // 复用其连往自己房间 host 的传输转发），永久停用。
            cm.tryRelay(state, requestingClientId);

            } else {

               cm.signalingClient

                  .sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), true, "relay_declined", new JsonObject(), requestingClientId);

            }

         }

      }

   }
   static void dispatchRelaySetup(ConnectionManager cm, RoomManager.RoomState state, List<RoomInfo.PeerInfo> candidates, String requestingClientId, RoomInfo.PeerInfo requestingPeer) {

      RelayBridge relayBridge = RelayBridge.getInstance(cm.scheduler);

      candidates.sort((a, b) -> {

         int loadA = relayBridge.getRelayCountForPeer(a.clientId);

         int loadB = relayBridge.getRelayCountForPeer(b.clientId);

         return Integer.compare(loadA, loadB);

      });

      RoomInfo.PeerInfo relay = candidates.get(0);

      JsonObject setup = new JsonObject();

      setup.addProperty("targetClientId", requestingClientId);

      setup.addProperty("targetIp", requestingPeer.mappedIp);

      setup.addProperty("targetPort", requestingPeer.mappedPort);
      setup.addProperty("replyHostId", cm.relayPeerId(state));

      cm.signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), true, "relay_setup", setup, relay.clientId);

      JsonObject notify = new JsonObject();

      notify.addProperty("relayIp", relay.mappedIp);

      notify.addProperty("relayPort", relay.mappedPort);

      cm.signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), true, "relay_notify", notify, requestingClientId);

   }

   // ================= v3 全局大厅候选池（跨房玩家中继） =================

   /** 全局候选行（来自 /relay/candidates，caps 过滤后）。 */
   static RoomInfo.PeerInfo parseGlobalCandidate(JsonObject c) {
      RoomInfo.PeerInfo p = new RoomInfo.PeerInfo(c.has("clientId") ? c.get("clientId").getAsString() : "");
      p.natType = c.has("natType") ? c.get("natType").getAsString() : "unknown";
      p.mappedIp = c.has("mappedIp") ? c.get("mappedIp").getAsString() : "";
      p.mappedPort = c.has("mappedPort") ? c.get("mappedPort").getAsInt() : 0;
      return p;
   }

   static boolean isGlobalRelayCurrent(ConnectionManager cm, RoomManager.RoomState state, int epoch) {
      return cm.relayGlobalEpoch.get() == epoch && cm.roomManager.currentRoom.get() == state;
   }

   /** v3 派发：大厅全局候选（非本房、relayEnabled、非对称 NAT、caps 含 relayMuxV1）。 */
   static void dispatchGlobalRelay(ConnectionManager cm, RoomManager.RoomState state, RoomInfo.PeerInfo symPeer) {
      final String excludeRoom = state.roomInfo.getCode();
      final int globalEpoch = cm.relayGlobalEpoch.incrementAndGet();
      cm.signalingClient.getRelayCandidates(excludeRoom).thenAccept(resp -> {
         if (!isGlobalRelayCurrent(cm, state, globalEpoch)) {
            return;
         }
         List<RoomInfo.PeerInfo> global = new ArrayList<>();
         if (resp.success && resp.data != null && resp.data.has("candidates") && resp.data.get("candidates").isJsonArray()) {
            for (JsonElement el : resp.data.getAsJsonArray("candidates")) {
               if (!el.isJsonObject()) {
                  continue;
               }
               JsonObject c = el.getAsJsonObject();
               String caps = c.has("caps") ? c.get("caps").getAsString() : "";
               String nt = c.has("natType") ? c.get("natType").getAsString() : "";
               if (!caps.contains(ProtocolNegotiator.CAP_RELAY_MUX_V1)) {
                  continue;
               }
               if (!isConeNatType(nt)) {
                  continue;
               }
               RoomInfo.PeerInfo p = parseGlobalCandidate(c);
               if (!p.clientId.isEmpty() && !p.mappedIp.isEmpty() && p.mappedPort > 0) {
                  global.add(p);
               }
            }
         }

         if (global.isEmpty()) {
            VoxLinkMod.LOGGER.info("[Relay] Global lobby pool empty after relayMuxV1 filter (excluded failed={})", cm.failedRelayPeers.size());
            cm.failedRelayPeers.clear();
            cm.notifyRelayFailed();
            JsonObject decline = new JsonObject();
            decline.addProperty("reason", "voxlink.relay.failed_no_candidates");
            cm.signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), true, "relay_declined", decline, symPeer.clientId);
            return;
         }

         String hostIp = state.roomInfo.getMyMappedIp();
         int hostPort = state.roomInfo.getMyMappedPort();
         if (hostIp == null || hostIp.isEmpty() || hostPort <= 0) {
            VoxLinkMod.LOGGER.warn("[Relay] Global relay needs host mapped addr, missing");
            cm.notifyRelayFailed();
            JsonObject decline = new JsonObject();
            decline.addProperty("reason", "voxlink.connection.relay_unavailable");
            cm.signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), true, "relay_declined", decline, symPeer.clientId);
            return;
         }

         int parallelN = Math.min(3, global.size());
         cm.currentRelayPeer.set("global:" + global.get(0).clientId);
         int batchNo = cm.relayBatchIndex.incrementAndGet();
         cm.relayBatchSize.set(parallelN);
         cm.relayProgressText = Text.translatable("voxlink.relay.trying_batch",
            new Object[]{Integer.toString(batchNo), Integer.toString(parallelN)});

         for (int i = 0; i < parallelN; i++) {
            RoomInfo.PeerInfo cand = global.get(i);
            // ① host→候选者专用传输腿（独立 punch + mux，不复用任何既有连接）
            startXRelayLeg(cm, state, cand, globalEpoch);
            // ② 跨房 relay_setup：SignalAppend 自动转发到候选者所在房间（from=host）
            JsonObject setup = new JsonObject();
            setup.addProperty("targetClientId", symPeer.clientId);
            setup.addProperty("targetIp", symPeer.mappedIp);
            setup.addProperty("targetPort", symPeer.mappedPort);
            setup.addProperty("hostIp", hostIp);
            setup.addProperty("hostPort", hostPort);
            setup.addProperty("replyHostId", "host-" + state.roomInfo.getCode());
            setup.addProperty("crossRoom", true);
            cm.signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), true, "relay_setup", setup, cand.clientId);
            // ③ sym 房客直接打候选者（与 v2 房内派发同款语义）
            JsonObject notify = new JsonObject();
            notify.addProperty("relayIp", cand.mappedIp);
            notify.addProperty("relayPort", cand.mappedPort);
            cm.signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), true, "relay_notify", notify, symPeer.clientId);
            VoxLinkMod.LOGGER.info("[Relay] Global #{} candidate={} {}:{} (crossRoom relayMuxV1)",
               new Object[]{i + 1, cand.clientId, cand.mappedIp, cand.mappedPort});
         }

         // 8s 失败预算：无 accept 即按无候选收场（accept 到达会 clearRelayTracking 撤销本任务）
         cm.relayFailoverTask = cm.scheduler.schedule(() -> {
            if (!cm.connectionWon.get() && isGlobalRelayCurrent(cm, state, globalEpoch)) {
               VoxLinkMod.LOGGER.warn("[Relay] Global relay batch timeout (8s)");
               cm.currentRelayPeer.set(null);
               cm.relayFailoverTask = null;
               cm.notifyRelayFailed();
               JsonObject decline = new JsonObject();
               decline.addProperty("reason", "voxlink.relay.failed_no_candidates");
               cm.signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), true, "relay_declined", decline, symPeer.clientId);
            }
         }, 8L, TimeUnit.SECONDS);
      }).exceptionally(e -> {
         if (!isGlobalRelayCurrent(cm, state, globalEpoch)) {
            return null;
         }
         VoxLinkMod.LOGGER.warn("[Relay] Global candidates fetch failed: {}", e == null ? "?" : e.getMessage());
         cm.notifyRelayFailed();
         JsonObject decline = new JsonObject();
         decline.addProperty("reason", "voxlink.relay.failed_no_candidates");
         cm.signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), true, "relay_declined", decline, symPeer.clientId);
         return null;
      });
   }

   /** host→全局候选者的专用 mux 传输腿；就绪后挂 activeUdpTransports["xrelay-<id>"]。 */
   static void startXRelayLeg(ConnectionManager cm, RoomManager.RoomState state, RoomInfo.PeerInfo cand, int globalEpoch) {
      final String key = "xrelay_" + cand.clientId;
      if (!isGlobalRelayCurrent(cm, state, globalEpoch)) {
         return;
      }
      UdpHolePuncher puncher = new UdpHolePuncher();
      cm.applyPunchTemplate(puncher);
      try {
         puncher.createSocket();
      } catch (Exception e) {
         return;
      }
      cm.activeHolePunchers.put(key, puncher);
      puncher.punch(cand.mappedIp, cand.mappedPort)
         .orTimeout(8L, TimeUnit.SECONDS)
         .thenAccept(result -> {
            cm.activeHolePunchers.remove(key, puncher);
            if (!result.isSuccess() || !isGlobalRelayCurrent(cm, state, globalEpoch)) {
               puncher.close();
               return;
            }
            puncher.markSocketTransferred();
            ReliableUdpTransport t = new ReliableUdpTransport(result.getSuccessSocket(), new InetSocketAddress(cand.mappedIp, cand.mappedPort));
            t.enableMux();
            if (!isGlobalRelayCurrent(cm, state, globalEpoch)) {
               t.close();
               return;
            }
            cm.activeUdpTransports.put("xrelay-" + cand.clientId, t);
            t.start();
            VoxLinkMod.LOGGER.info("[Relay] xrelay leg to global candidate {} ready (mux)", cand.clientId);
         })
         .exceptionally(e -> {
            cm.activeHolePunchers.remove(key, puncher);
            puncher.close();
            return null;
         });
   }

   /** 候选者侧 v3：收到跨房 relay_setup → 建 host 专用 mux 腿 + sym 直连腿 → 桥接回执。 */
   static void handleCrossRoomRelaySetup(ConnectionManager cm, RoomManager.RoomState state, boolean setupAsHost,
                                         String setupReplyTo, String targetClientId, String targetIp, int targetPort, JsonObject data) {
      final String replyTo = setupAsHost && data.has("replyHostId")
         ? data.get("replyHostId").getAsString() : setupReplyTo;
      String hostIp = data.has("hostIp") ? data.get("hostIp").getAsString() : "";
      int hostPort = data.has("hostPort") ? data.get("hostPort").getAsInt() : 0;

      Runnable decline = () -> {
         cm.signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), setupAsHost,
            "relay_declined", new JsonObject(), replyTo);
      };
      if (hostIp.isEmpty() || hostPort <= 0) {
         VoxLinkMod.LOGGER.warn("[Relay] crossRoom setup without host addr, decline");
         decline.run();
         return;
      }

      // ① 打通到请求方房主（专用腿，双方均 enableMux——线上字节=子通道帧）
      UdpHolePuncher hp = new UdpHolePuncher();
      cm.applyPunchTemplate(hp);
      try {
         hp.createSocket();
      } catch (Exception e) {
         decline.run();
         return;
      }
      cm.activeHolePunchers.put("xleg_host", hp);
      hp.punch(hostIp, hostPort)
         .orTimeout(8L, TimeUnit.SECONDS)
         .thenAccept(hr -> {
            cm.activeHolePunchers.remove("xleg_host");
            if (!hr.isSuccess()) {
               hp.close();
               VoxLinkMod.LOGGER.info("[Relay] xleg to requester host {}:{} failed, decline", hostIp, hostPort);
               decline.run();
               return;
            }
            hp.markSocketTransferred();
            ReliableUdpTransport hostLeg = new ReliableUdpTransport(hr.getSuccessSocket(), new InetSocketAddress(hostIp, hostPort));
            hostLeg.enableMux();
            hostLeg.start();
            cm.activeUdpTransports.put("xleg", hostLeg);

            // ② sym 直连腿（与 v2 房内同款：端口预测打洞）
            UdpHolePuncher cp = new UdpHolePuncher();
            cm.applyPunchTemplate(cp);
            try {
               cp.createSocket();
            } catch (Exception e) {
               decline.run();
               return;
            }
            cm.activeHolePunchers.put("xleg_sym", cp);
            cp.punchWithPortPrediction(targetIp, targetPort, cm.punchProfile().coneBackupPortRange)
               .orTimeout(15L, TimeUnit.SECONDS)
               .thenAccept(cr -> {
                  cm.activeHolePunchers.remove("xleg_sym");
                  if (!cr.isSuccess()) {
                     cp.close();
                     decline.run();
                     return;
                  }
                  cp.markSocketTransferred();
                  ReliableUdpTransport peerLeg = new ReliableUdpTransport(cr.getSuccessSocket(), new InetSocketAddress(targetIp, targetPort));
                  peerLeg.start();

                  // ③ 桥接：host 腿 mux 子通道 ↔ sym 直连腿
                  final int muxChan = cm.allocateRelayMuxChan();
                  final String relayKey = "xmuxrelay-" + muxChan + "-" + (targetClientId != null ? targetClientId : "sym");
                  final ReliableUdpTransport fHostLeg = hostLeg;
                  RelayBridge.getInstance(cm.scheduler).startRelay(
                     relayKey,
                     "xhost-chan" + muxChan,
                     targetClientId != null ? targetClientId : "sym",
                     new RelayBridge.RelayLeg() {
                        @Override
                        public java.io.InputStream input() {
                           return fHostLeg.getSubstreamInput(muxChan);
                        }

                        @Override
                        public java.io.OutputStream output() {
                           return fHostLeg.getSubstreamOutput(muxChan);
                        }

                        @Override
                        public void close() {
                           try {
                              fHostLeg.closeSubstream(muxChan);
                           } catch (Exception e) {
                           }
                        }
                     },
                     RelayBridge.legOf(peerLeg)
                  );

                  // ④ 回执请求方房主（跨房 relay_accept → host-<CODE> 归一投递）
                  JsonObject reply = new JsonObject();
                  reply.addProperty("forClientId", targetClientId != null ? targetClientId : "sym");
                  reply.addProperty("chan", muxChan);
                  reply.addProperty("crossRoom", true);
                  cm.signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), setupAsHost, "relay_accept", reply, replyTo);
                  VoxLinkMod.LOGGER.info("[Relay] crossRoom relay armed: chan{} host-leg={} sym-leg={}:{}",
                     new Object[]{muxChan, hostLeg.isConnected(), targetIp, targetPort});
               })
               .exceptionally(e -> {
                  cm.activeHolePunchers.remove("xleg_sym");
                  cp.close();
                  decline.run();
                  return null;
               });
         })
         .exceptionally(e -> {
            cm.activeHolePunchers.remove("xleg_host");
            hp.close();
            decline.run();
            return null;
         });
   }
   static void handleRelayAccept(ConnectionManager cm, String from, JsonObject data) {

      RoomManager.RoomState state = cm.roomManager.currentRoom.get();

      if (state != null && state != RoomManager.PENDING) {

         String forClientId = data.has("forClientId") ? data.get("forClientId").getAsString() : null;

         // 玩家中继 v2：relay_accept 携带候选者在其 joiner 传输上分配的子通道号。
         // host 侧把该子通道桥到一个全新 MC socket（被中继房客的独立会话）。
         int muxChan = data.has("chan") ? data.get("chan").getAsInt() : 0;

         if (forClientId != null && muxChan > 0) {

            ReliableUdpTransport candidateTransport = cm.activeUdpTransports.get(from);

            if (candidateTransport == null) {
               // v3 跨房候选：专用传输腿挂在 xrelay-<clientId>
               candidateTransport = cm.activeUdpTransports.get("xrelay-" + from);
            }

            if (candidateTransport != null && candidateTransport.isMuxEnabled()) {

               int mcPort = state.roomInfo.getHostPort();

               P2PBridge.startUdpHostBridgeForRelayedClient(forClientId, candidateTransport, muxChan, mcPort, () ->

                  VoxLinkMod.LOGGER.info("[Relay] relayed session {} via {} chan{} closed", forClientId, from, muxChan)

               );

               VoxLinkMod.LOGGER.info("[Relay] relay_accept from {} for {} chan={}, subchannel bridge armed", from, forClientId, muxChan);

               JsonObject notify = new JsonObject();

               notify.addProperty("connected", true);

               cm.signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), true, "relay_notify", notify, forClientId);

            } else {

               // 候选者传输缺失/未 mux 化（如候选者刚好退出）：不通知 connected，
               // sym 侧 8s 超时自然走 failover 下一候选
               VoxLinkMod.LOGGER.warn("[Relay] relay_accept from {} but carrier transport missing/not mux, ignore", from);

            }

         } else if (forClientId != null) {

            // 旧版候选者（无 chan 字段）：v1 数据面已被实证为跨流污染，绝不接旧线

            VoxLinkMod.LOGGER.warn("[Relay] relay_accept without chan (legacy candidate {}), ignore (v1 relay data plane retired)", from);

         }

      }

   }
   static void handleRelayNotify(ConnectionManager cm, String from, JsonObject data) {

      RoomManager.RoomState state = cm.roomManager.currentRoom.get();

      if (state != null && state != RoomManager.PENDING) {

         if (cm.isTurnActive() && !cm.manualRelayInProgress) {
            if (VoxLinkMod.getConfig().isRelayEnabled() && cm.turnHotstandbyTransport == null) {
               String standbyIp = data.has("relayIp") ? data.get("relayIp").getAsString() : null;
               int standbyPort = data.has("relayPort") ? data.get("relayPort").getAsInt() : 0;
               if (standbyIp != null && standbyPort > 0) {
                  cm.establishHotstandby(state, standbyIp, standbyPort);
               }
            }
            VoxLinkMod.LOGGER.info("[Relay] Standby notify kept separate from active TURN state");
            return;
         }

         if (!cm.manualRelayInProgress) {
            VoxLinkMod.LOGGER.info("[Relay] Ignore late relay_notify after request completed");
            return;
         }

         if (data.has("connected") && data.get("connected").getAsBoolean()) {

            VoxLinkMod.LOGGER.info("[Relay] Received relay_notify(connected), relay ready");

            cm.relayConnectedSignaled = true;

            cm.clearRelayTracking();

            cm.connectionWon.set(true);

            // 成功路径补: 不重置 manualRelayInProgress 会让 AttemptingJoinScreen 误闪 3s 失败文字。
            cm.manualRelayInProgress = false;

            cm.relayProgressText = null;

            cm.manualRelayDeadline = 0L;

            state.roomInfo.setConnectionMode(Text.translatable("voxlink.relay.connected_via").withStyle(Text.Style.builder().color(Text.ChatFormat.YELLOW).build()));

            state.roomInfo.setUsingRelay(true);

         } else if (!VoxLinkMod.getConfig().isRelayEnabled()) {

            VoxLinkMod.LOGGER.info("[Relay] Relay disabled by config, reject relay_notify");

         } else {

            String relayIp = data.has("relayIp") ? data.get("relayIp").getAsString() : null;

            int relayPort = data.has("relayPort") ? data.get("relayPort").getAsInt() : 0;

            if (relayIp != null && relayPort > 0) {

               // TURN 已在承载：玩家中继仅作热备——punch 通中继玩家后只存不用，
               // 不进入下方建桥流程（防止与 TURN 桥双桥冲突）
               if (cm.isTurnActive() && cm.turnHotstandbyTransport == null) {
                  cm.establishHotstandby(state, relayIp, relayPort);
                  return;
               }

               VoxLinkMod.LOGGER.info("[Relay] Received relay_notify, punch to Cone {}:{}", relayIp, relayPort);

               state.roomInfo.setConnectionMode(Text.translatable("voxlink.relay.trying"));

               List<UdpHolePuncher> relayPunchers = new ArrayList<>();

               AtomicBoolean relayWon = new AtomicBoolean(false);

               String fRelayIp = relayIp;

               int fRelayPort = relayPort;



               for (int i = 0; i < cm.punchProfile().relaySocketCount; i++) {

                  UdpHolePuncher rp = new UdpHolePuncher();

                  cm.applyPunchTemplate(rp);



                  try {

                     rp.createSocket();

                  } catch (Exception e) {

                     continue;

                  }



                  relayPunchers.add(rp);

                  cm.activeHolePunchers.put("relay_to_cone_" + i, rp);

                  int idx = i;

                  rp.punch(fRelayIp, fRelayPort)

                     .orTimeout(15L, TimeUnit.SECONDS)

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

                              if (!relayWon.compareAndSet(false, true)) {

                                 try {

                                    rp.close();

                                 } catch (Exception var13x) {

                                 }

                              } else if (cm.connectionWon.compareAndSet(false, true)

                                 || cm.relayConnectedSignaled && !cm.activeUdpTransports.containsKey("relay_cone")) {

                                 VoxLinkMod.LOGGER.info("[Relay] Sym->Cone socket#{} punch success", idx);

                                 rp.markSocketTransferred();

                                 cm.stopAllPunchingAfterHostBridge();

                                 rp.stopPunch();



                                 for (UdpHolePuncher op : relayPunchers) {

                                    if (op != rp) {

                                       try {

                                          op.cancel();

                                          op.close();

                                       } catch (Exception var15) {

                                       }

                                    }

                                 }



                                 ReliableUdpTransport transport = new ReliableUdpTransport(socket, new InetSocketAddress(fRelayIp, fRelayPort));

                                 cm.activeUdpTransports.put("relay_cone", transport);

                                 transport.start();

                                 state.roomInfo.setConnectionMode(Text.translatable("voxlink.relay.connected_via"));

                                 state.roomInfo.setUsingRelay(true);

                                 cm.startUdpPunchBridge(state, transport);

                                 JsonObject readyData = new JsonObject();

                                 readyData.addProperty("clientId", state.roomInfo.getClientId());

                                 cm.signalingClient

                                    .sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), false, "relay_ready", readyData, "host")

                                    .exceptionally(e -> {

                                       VoxLinkMod.LOGGER.debug("relay_ready send failed: {}", e.getMessage());

                                       return null;

                                    });

                                 cm.scheduler.schedule(() -> {

                                    // 网络线程不可直调 GUI：包一层主线程调度

                                    ClientThread.runOnClientThread(() -> {

                                       ChatSink.chat(Text.translatable("voxlink.relay.connected_via"));

                                    });

                                 }, 2L, TimeUnit.SECONDS);

                              } else {

                                 try {

                                    rp.close();

                                 } catch (Exception var14) {

                                 }

                              }

                           }

                        }

                     )

                     .exceptionally(e -> {

                        if (!relayWon.get()) {

                           try {

                              rp.close();

                           } catch (Exception var6x) {

                           }



                           cm.activeHolePunchers.remove("relay_to_cone_" + idx);

                        }



                        return null;

                     });

               }



               cm.scheduler.schedule(() -> {

                  if (!relayWon.get() && !cm.connectionWon.get()) {

                     VoxLinkMod.LOGGER.warn("[Relay] Sym->Cone relay punch timeout (8s)");



                     for (UdpHolePuncher op : relayPunchers) {

                        try {

                           op.cancel();

                           op.close();

                        } catch (Exception var7x) {

                        }

                     }



                     cm.activeHolePunchers.entrySet().removeIf(e -> e.getKey().startsWith("relay_to_cone_"));

                     // 漏了这两步会让 manualRelayInProgress/currentRelayPeer 悬空:
                     // 导演每拍被 manualRelayInProgress 挡住, 下一次 fireAutoTurn 被静默吞
                     cm.clearRelayTracking();
                     cm.notifyRelayFailed();
                     cm.relayFailOrAuto(state, "voxlink.connection.relay_failed");

                  }

               }, 8L, TimeUnit.SECONDS);

            }

         }

      }

   }
   static void handleRelayDeclined(ConnectionManager cm, String from, JsonObject data) {

      RoomManager.RoomState state = cm.roomManager.currentRoom.get();
      if (state != null && state != RoomManager.PENDING && cm.isTurnActive() && !cm.manualRelayInProgress) {
         VoxLinkMod.LOGGER.info("[Relay] Standby player relay unavailable, keep active TURN state");
         return;
      }

      if (!cm.manualRelayInProgress) {
         VoxLinkMod.LOGGER.info("[Relay] Ignore late relay_declined after request completed");
         return;
      }

      cm.clearRelayTracking();

      cm.notifyRelayFailed();

      if (state != null && state != RoomManager.PENDING) {

         cm.relayFailOrAuto(state, "voxlink.connection.relay_failed");

      }

   }
   static String relayPeerId(ConnectionManager cm, RoomManager.RoomState state) {
      String cid = state != null ? state.roomInfo.getClientId() : null;
      if (cid != null && !cid.isEmpty()) {
         return cid;
      }
      return state != null && state.roomInfo.isHost() ? "host-" + state.roomInfo.getCode() : null;
   }
   static void scheduleRelayRegistrationRenewal(ConnectionManager cm, RoomManager.RoomState state, String natType, String ip, int port) {

      cm.scheduler

         .schedule(

            () -> {

               if (cm.roomManager.currentRoom.get() == state && !cm.connectionWon.get() && cm.relayPeerId(state) != null) {

                  if (ip != null && port > 0) {

                     boolean relayOk = VoxLinkMod.getConfig().isRelayEnabled();

                     cm.signalingClient

                        .registerRelayPeer(cm.relayPeerId(state), state.roomInfo.getCode(), natType, ip, port, relayOk,
                           ProtocolNegotiator.selfSupports(ProtocolNegotiator.CAP_RELAY_MUX_V1) ? ProtocolNegotiator.CAP_RELAY_MUX_V1 : "")

                        .thenRun(() -> cm.scheduleRelayRegistrationRenewal(state, natType, ip, port))

                        .exceptionally(e -> {

                           VoxLinkMod.LOGGER.debug("relay renew failed: {}", e.getMessage());

                           return null;

                        });

                  }

               }

            },

            60L,

            TimeUnit.SECONDS

         );

   }
   static void handleRelayReady(ConnectionManager cm, String from, JsonObject data) {

      RoomManager.RoomState state = cm.roomManager.currentRoom.get();

      if (state != null && state != RoomManager.PENDING) {

         VoxLinkMod.LOGGER.info("[Relay] Received relay_ready from joiner {}, mark relay connected", from);

         cm.clearRelayTracking();

         // 成功路径补: 与 handleRelayNotify 同步, 避免 AttemptingJoinScreen 误闪失败文字。
         cm.manualRelayInProgress = false;

         cm.relayProgressText = null;

         cm.manualRelayDeadline = 0L;

         cm.stopAllPunchingAfterHostBridge();

         cm.activeHolePunchers.entrySet().removeIf(e -> e.getKey().startsWith("hp_"));
         if (cm.connectionWon.compareAndSet(false, true)) {
            state.roomInfo.setConnectionMode(Text.translatable("voxlink.relay.connected_via").withStyle(Text.Style.builder().color(Text.ChatFormat.YELLOW).build()));

            state.roomInfo.setUsingRelay(true);

         }

      }

   }
   static void triggerTurnRelay(ConnectionManager cm) {
      cm.turnReadyApplied.set(false);
      if (cm.turnInProgress || cm.turnSession != null || cm.manualRelayInProgress) {
         return;
      }
      // 新 TURN 会话复位旧标记: turnSwitchedToP2p=true 会短路 20s turn_ready 兜底、
      // 自cancel 后台升级监视器、让按钮永久失效(1.1.5 TURN 审计 P1-3)
      cm.turnSwitchedToP2p = false;

      RoomManager.RoomState state = cm.roomManager.currentRoom.get();
      if (state == null || state == RoomManager.PENDING || state.roomInfo.isHost()) {
         return;
      }

      // 边缘情况：玩家点按钮的瞬间原打洞恰好打通——直连已赢，TURN 没有必要，直接放弃
      if (cm.connectionWon.get()) {
         VoxLinkMod.LOGGER.info("[Turn] direct connection already won, skip TURN request");
         return;
      }

      cm.turnInProgress = true;
      final int turnEpoch = cm.turnSessionEpoch.get();
      icu.wuhui.voxlink.ui.UiLogBus.push(0, "voxlink.logui.turn_relay");
      state.roomInfo.setConnectionMode(Text.translatable("voxlink.turn.connecting"));
      SignalingClient sc = cm.signalingClient;
      CompletableFuture
         .supplyAsync(() -> {
            List<TurnRelayClient.NodeInfo> nodes = TurnRelayClient.fetchNodeList(sc).join();
            if (nodes.isEmpty()) {
               throw new IllegalStateException("NO_NODES");
            }

            List<TurnRelayClient.ProbeResult> probed = TurnRelayClient.probeNodes(nodes).join();
            if (probed.isEmpty()) {
               throw new IllegalStateException("UNREACHABLE");
            }

            // 探测全超时不直接判死（弱网丢包下探测包全丢很常见）：照常 allocate+bind，
            // bind 自带重试，网络缓过来即可成功；真不通会以 BIND_FAILED_* 落到同一失败分支
            if (probed.get(0).rttMs < 0) {
               VoxLinkMod.LOGGER.warn("[Turn] all {} node probes timed out, attempting bind anyway (lossy network?)", probed.size());
            } else {
               VoxLinkMod.LOGGER.info("[Turn] guest picked node {} rtt={}ms load={}/{} (nodes={})",
                  probed.get(0).node.id, probed.get(0).rttMs, probed.get(0).node.sessions, probed.get(0).node.maxSessions, nodes.size());
               icu.wuhui.voxlink.ui.UiLogBus.push(0, "voxlink.logui.turn_node", probed.get(0).rttMs);
            }
            // 粘性：记住选中节点，下次选优享 8 折优惠（防近邻节点间反复横跳）
            TurnRelayClient.rememberGoodNode(probed.get(0).node.id);
            return probed.get(0).node;
         })
         .thenCompose(node -> {
            // 标准 TURN（双方 stdTurnV1 + 节点双栈 stdTurnPort>0）→ RFC 5766 流程；
            // 任一端旧版/节点未启用 → 自定义协议 v1（现有路径，零行为变化）
            if (node.stdTurnPort > 0 && ProtocolNegotiator.hostSupportsStdTurn(state.roomInfo)) {
               VoxLinkMod.LOGGER.info("[Turn] node {} stdTurnPort={}, host stdTurnV1: using standard TURN (RFC 5766)", node.id, node.stdTurnPort);
               return cm.startStdTurnGuestFlow(state, sc, node).thenApply(ok -> (Object)ok);
            }
            icu.wuhui.voxlink.ui.UiLogBus.push(0, "voxlink.logui.turn_allocating");
            return TurnRelayClient.allocate(sc, state.roomInfo.getCode(), state.roomInfo.getClientId(), state.roomInfo.getToken(), node.id)
               .thenCompose(alloc -> {
            if (alloc == null) {
               throw new IllegalStateException("ALLOC_FAILED");
            }

            // 竞态二次检查：列表+测延迟+allocate 耗费数秒，期间直连可能已打通
            if (cm.connectionWon.get() && cm.turnSession == null) {
               TurnRelayClient
                  .release(sc, state.roomInfo.getCode(), state.roomInfo.getClientId(), state.roomInfo.getToken(), alloc.sessionIdHex)
                  .exceptionally(e -> null);
               throw new IllegalStateException("P2P_WON");
            }

            byte[] sid = new byte[16];

            for (int i = 0; i < 16; i++) {
               sid[i] = (byte)Integer.parseInt(alloc.sessionIdHex.substring(i * 2, i * 2 + 2), 16);
            }

            TurnRelayClient.TurnSession session =
               new TurnRelayClient.TurnSession(alloc.sessionIdHex, sid, alloc.host, alloc.port, TurnRelayClient.ROLE_GUEST, alloc.guestTicket, alloc.expireSec);
                        // 3 轮重试(每轮5发): 弱网单轮全丢很常见(实证 09-11 22:42 guest 5发全丢)
            int code = TurnRelayClient.bindWithRetry(session, 3);
            // code=4 对齐 host 容忍: 首发响应丢失重放被判冲突, 会话实际已绑定
            if (code == TurnRelayClient.BIND_ROLE_CONFLICT) {
               code = TurnRelayClient.BIND_OK;
               VoxLinkMod.LOGGER.info("[Turn] guest bind ROLE_CONFLICT tolerated (session already bound)");
            }
            if (code != TurnRelayClient.BIND_OK) {
               session.unbind();
               throw new IllegalStateException("BIND_FAILED_" + code);
            }

            // 1.1.5 写回屏障: 35s 总超时触发的 teardown 已把 turnInProgress 置 false 时,
            // 异步链在此中止写回(否则"复活"出无看门狗的孤儿会话, 审计 P1-1)
            if (!cm.turnInProgress || turnEpoch != cm.turnSessionEpoch.get()) {
               session.unbind();
               VoxLinkMod.LOGGER.warn("[Turn] guest flow cancelled during bind (timeout teardown won), discard session");
               throw new IllegalStateException("TURN_CANCELLED");
            }

            cm.turnSession = session;
            cm.startTurnKeepalive(session);
            VoxLinkMod.LOGGER.info("[Turn] guest session {} bound via {}:{}, sending turn_alloc", alloc.sessionIdHex.substring(0, 8), alloc.host, alloc.port);
            UdpPath.Codec codec = new TurnRelayClient.TurnPathCodec(sid, TurnRelayClient.ROLE_GUEST, TurnRelayClient.ROLE_HOST);
            ReliableUdpTransport transport = new ReliableUdpTransport(session.socket, session.endpoint(), codec);
            // TURN 路径密钥现场重派生, 不沿用打洞阶段的 activePunchAuthKey:
            // 打洞阶段密钥可能陈旧(重进房/join_request 被 defer), 沿用会与 host 互丢
            // (实证 RBW6HX 1.1.4: TURN path up 后双方互丢 rudp-data, 30s 内链路死亡)
            byte[] turnAuthKey = cm.derivePunchAuthKey(state, null, true);
            if (turnAuthKey != null) {
               transport.setAuthKey(turnAuthKey);
               cm.activePunchAuthKey = turnAuthKey;
               VoxLinkMod.LOGGER.info("[PunchAuth] TURN transport auth enabled (guest side)");
            } else {
               VoxLinkMod.LOGGER.info("[PunchAuth] TURN transport without auth (host caps missing)");
            }
            // 互操作降级开关: 对端为旧引擎(未武装密钥)时连续认证失败自动回明文, 不再互丢致死
            transport.allowAuthDowngradeForInterop();
            // TURN载具补接ICE重启回调
            transport.setOnIceRestartRequested(cm::triggerIceRestart);

            cm.turnTransport = transport;
            cm.turnPeerId = "host";

            JsonObject data = new JsonObject();
            data.addProperty("sessionId", alloc.sessionIdHex);
            data.addProperty("host", alloc.host);
            data.addProperty("port", alloc.port);
            data.addProperty("ticket", alloc.hostTicket);
            data.addProperty("expire", alloc.expireSec);
            data.addProperty("clientId", state.roomInfo.getClientId());
            // 显式声明本端 punchAuthV1 能力: host 侧 TURN 建链时据此直接派生密钥,
            // 不再依赖 host 本地 peer 表(join_request 被 defer 时表项可能缺失/陈旧)
            data.addProperty("punchAuth", ProtocolNegotiator.selfSupports(ProtocolNegotiator.CAP_PUNCH_AUTH_V1));
            return sc
               .sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), false, "turn_alloc", data, "host")
               .thenApply(r -> session);
               })
               .thenApply(s -> (Object)s);
         })
                  // 35s: bind 升级为 3 轮重试后最长 ~16s, 加上列表/探测/allocate 需要更大总预算
         .orTimeout(35L, TimeUnit.SECONDS)
         .whenComplete((session, ex) -> {
            if (ex != null || session == null) {
               VoxLinkMod.LOGGER.warn("[Turn] guest flow failed: {}", ex != null ? ex.getMessage() : "null");
               cm.teardownTurn(state, "voxlink.turn.failed");
            } else {
               // host 数据面就绪回执有 20s 兜底（handleTurnReady 到达即 start+桥接）
               // 保险 v2: 20s 先强制补拉一次再判死; 信令不健康时延寿重查, 总窗≤65s(≤90s 有界)
               cm.scheduler.schedule(() -> cm.turnReadyWatchdog(state, 0), 20L, TimeUnit.SECONDS);
            }
         });
   }
   static void startAutoRelayDirector(ConnectionManager cm, RoomManager.RoomState state) {
      cm.stopAutoRelayDirector();
      cm.autoRelayDirectorTask = cm.scheduler.scheduleAtFixedRate(() -> {
         try {
            if (cm.connectionWon.get() || cm.roomManager.currentRoom.get() != state) {
               cm.stopAutoRelayDirector();
               return;
            }

            // 已有任何中继在途/在跑：不重复触发
            if (cm.isTurnActive() || cm.isTurnInProgress() || cm.manualRelayInProgress) {
               return;
            }

            long punchMs = cm.punchUiStartMs > 0L ? System.currentTimeMillis() - cm.punchUiStartMs : 0L;
            if (punchMs < 10_000L) {
               return;
            }

            // 零收包只作早档(tierA)前提：半开场景(收过控制包但 MC 建连无望)
            // 此前所有档位被一票否决，玩家无限空转打洞
            boolean recvEver = cm.sessionPunchRecvEver.get();
            boolean tierA = !recvEver && cm.isDeadNatCombo(state) && punchMs >= 15_000L;
            boolean tierB = (cm.sessionPredictionOffCount.get() >= 10 || cm.isSamePublicIpAsHost(state)) && punchMs >= 30_000L;
            boolean tierC = punchMs >= 45_000L;
            if (!(tierA || tierB || tierC)) {
               return;
            }

            // 轮次推进时重置尝试位
            int round = cm.continuousRetryRound.get();
            if (round != cm.autoRelayArmedRound) {
               cm.autoRelayArmedRound = round;
               cm.autoRelayTurnAttempted = false;
               cm.autoRelayPlayerAttempted = false;
            }
            if (cm.autoRelayTurnAttempted && cm.autoRelayTurnUnhealthy && !turnUnhealthyActive(cm)) {
               cm.autoRelayTurnAttempted = false;
               cm.autoRelayTurnUnhealthy = false;
            }
            boolean canFireTurn = !turnUnhealthyActive(cm) && !cm.autoRelayTurnAttempted;
            if (!canFireTurn && cm.autoRelayPlayerAttempted) {
               return;
            }

            VoxLinkMod.LOGGER.info(
               "[AutoRelay] evidence tier hit (punchMs={}, predOff={}, deadCombo={}, sameIp={}, recvEver={}), auto relay",
               punchMs, cm.sessionPredictionOffCount.get(), cm.isDeadNatCombo(state), cm.isSamePublicIpAsHost(state), recvEver
            );
            cm.fireAutoRelay(state);
         } catch (Exception e) {
            VoxLinkMod.LOGGER.debug("[AutoRelay] director tick error: {}", e.getMessage());
         }
      }, 1L, 1L, TimeUnit.SECONDS);
   }
   static void stopAutoRelayDirector(ConnectionManager cm) {
      ScheduledFuture<?> f = cm.autoRelayDirectorTask;
      if (f != null) {
         f.cancel(false);
         cm.autoRelayDirectorTask = null;
      }
   }
   static void fireAutoRelay(ConnectionManager cm, RoomManager.RoomState state) {
      if (!turnUnhealthyActive(cm) && !cm.autoRelayTurnAttempted) {
         cm.fireAutoTurn(state);
      } else if (!cm.autoRelayPlayerAttempted) {
         cm.fireAutoPlayerRelay(state);
      }
   }
   static void fireAutoTurn(ConnectionManager cm, RoomManager.RoomState state) {
      cm.autoRelayTurnAttempted = true;
      cm.autoRelayMode = true;
      icu.wuhui.voxlink.ui.UiLogBus.push(0, "voxlink.logui.auto_turn");
      state.roomInfo.setConnectionMode(Text.translatable("voxlink.auto_relay.turn"));
      VoxLinkMod.LOGGER.info("[AutoRelay] auto TURN fired");
      cm.triggerTurnRelay();
   }
   static void fireAutoPlayerRelay(ConnectionManager cm, RoomManager.RoomState state) {
      cm.autoRelayPlayerAttempted = true;
      cm.autoRelayMode = true;
      icu.wuhui.voxlink.ui.UiLogBus.push(0, "voxlink.logui.auto_player_relay");
      state.roomInfo.setConnectionMode(Text.translatable("voxlink.auto_relay.player"));
      VoxLinkMod.LOGGER.info("[AutoRelay] auto player relay fired");
      cm.triggerManualRelay();
   }
   static void relayFailOrAuto(ConnectionManager cm, RoomManager.RoomState state, String reasonKey) {
      if (cm.autoRelayMode) {
         cm.autoRelayFailoverOrEnd(state, reasonKey);
      } else {
         cm.showConnectFailed(state, reasonKey);
      }
   }
   static void autoRelayFailoverOrEnd(ConnectionManager cm, RoomManager.RoomState state, String reasonKey) {
      if (state == null || state == RoomManager.PENDING) {
         state = cm.roomManager.currentRoom.get();
      }
      if (state == null || state == RoomManager.PENDING) {
         cm.autoRelayMode = false;
         return;
      }

      if (!turnUnhealthyActive(cm) && !cm.autoRelayTurnAttempted) {
         cm.fireAutoTurn(state);
         return;
      }
      if (!cm.autoRelayPlayerAttempted) {
         cm.fireAutoPlayerRelay(state);
         return;
      }

      // 两条道都尽：不弹失败屏，交回打洞，诚实文案
      cm.autoRelayMode = false;
      VoxLinkMod.LOGGER.warn("[AutoRelay] both relay paths exhausted ({}), back to punching", reasonKey);
      icu.wuhui.voxlink.ui.UiLogBus.push(2, "voxlink.logui.auto_relay_exhausted");
      state.roomInfo.setConnectionMode(Text.translatable("voxlink.auto_relay.exhausted"));

      // 旧注释声称"持续重试仍活着"——实际首桥建立时 stopAllPunchingAfterHostBridge 已杀打洞,
      // 轮次随之冻结, 两道耗尽后会话静默挂死(无打洞/无中继/无失败屏)。房客侧诚实复位并复活:
      // 桥已拆/从未建立才复位 connectionWon; 再走与打洞失败同一条 enterContinuousRetryRound 链
      // (零收包终局/能力缺失由其内部分流); 复活不了至少给终态失败屏, 不再无声挂死
      if (!state.roomInfo.isHost()) {
         if (cm.connectionWon.get() && !cm.isTurnActive() && !cm.isManualRelayInProgress()
            && (P2PBridge.getJoinerCarrier() == null || !P2PBridge.getJoinerCarrier().isConnected())) {
            cm.connectionWon.set(false);
         }

         if (!cm.connectionWon.get() && !cm.isTurnActive() && !cm.isTurnInProgress()
            && !cm.isManualRelayInProgress() && !cm.connectionCycleActive.get()) {
            cm.stopAllPunchingForRelay();
            cm.continuousRetryCancelled.set(false);
            cm.connectionCycleActive.set(true);
            try {
               if (!cm.enterContinuousRetryRound(state)) {
                  cm.connectionCycleActive.set(false);
                  cm.showConnectFailedFinal(state, reasonKey);
               }
            } catch (Exception reviveEx) {
               cm.connectionCycleActive.set(false);
               VoxLinkMod.LOGGER.warn("[AutoRelay] revive punching failed: {}", String.valueOf(reviveEx));
               cm.showConnectFailedFinal(state, reasonKey);
            }
         }
      }
   }
   static void armRelayVerifyWatchdog(ConnectionManager cm, RoomManager.RoomState state, boolean turnPath) {
      cm.cancelRelayVerifyWatchdog();
      P2PBridge.resetJoinerDownlink();
      final long armMs = System.currentTimeMillis();
      cm.relayVerifyWatchdogTask = cm.scheduler.scheduleAtFixedRate(() -> {
         try {
            if (ConnectionHelper.isMcTrulyConnected()) {
               cm.cancelRelayVerifyWatchdog();
               cm.autoRelayMode = false;
               P2PBridge.stopJoinerJournal();
               if (!turnPath) {
                  // 玩家中继模式：起 10 分钟后台直连探测（本轮仅探测+日志，
                  // 无缝升级需 host 侧载具热切配套，随跨房虚拟加入迭代落地）
                  cm.startRelayBgProbe(state);
               }
               VoxLinkMod.LOGGER.info("[AutoRelay] relay verified ({} path, {}ms)", turnPath ? "turn" : "player", System.currentTimeMillis() - armMs);
               return;
            }

            if (!P2PBridge.isRunning() || cm.roomManager.currentRoom.get() != state) {
               cm.cancelRelayVerifyWatchdog();
               return;
            }

            if (P2PBridge.hasJoinerDownlink()) {
               // 验证后转下行保鲜盯守: 停滞即换道
               if (!P2PBridge.isJoinerDownlinkFresh(ConnectionManager.RELAY_DOWNLINK_FRESH_MS)) {
                  VoxLinkMod.LOGGER.warn("[AutoRelay] relay downlink stalled {}ms after first byte, failover", ConnectionManager.RELAY_DOWNLINK_FRESH_MS);
                  cm.cancelRelayVerifyWatchdog();
                  cm.autoRelayMode = false;
                  P2PBridge.stopJoinerJournal();
                  cm.onRelayVerifyDead(state, turnPath);
               }
               return;
            }

            if (System.currentTimeMillis() - armMs >= 25_000L) {
               cm.cancelRelayVerifyWatchdog();
               cm.onRelayVerifyDead(state, turnPath);
            }
         } catch (Exception e) {
            VoxLinkMod.LOGGER.debug("[AutoRelay] verify watchdog error: {}", e.getMessage());
         }
      }, 1L, 1L, TimeUnit.SECONDS);
   }
   static void cancelRelayVerifyWatchdog(ConnectionManager cm) {
      ScheduledFuture<?> f = cm.relayVerifyWatchdogTask;
      if (f != null) {
         f.cancel(false);
         cm.relayVerifyWatchdogTask = null;
      }
   }
   static void onRelayVerifyDead(ConnectionManager cm, RoomManager.RoomState state, boolean turnPath) {
      VoxLinkMod.LOGGER.warn("[AutoRelay] relay path verified DEAD ({}), seamless failover starting", turnPath ? "turn" : "player");
      icu.wuhui.voxlink.ui.UiLogBus.push(2, "voxlink.logui.auto_relay_dead");
      cm.carrierSwapFrom = turnPath ? "turn" : "player";
      if (turnPath) {
         cm.autoRelayTurnUnhealthy = true;
         cm.autoRelayTurnUnhealthyAtMs = System.currentTimeMillis();
         if (!cm.autoRelayPlayerAttempted) {
            cm.fireAutoPlayerRelay(state);
         } else {
            // 玩家中继也试过：诚实失败
            cm.teardownTurn(state, "voxlink.turn.failed");
         }
      } else {
         if (!turnUnhealthyActive(cm) && !cm.autoRelayTurnAttempted) {
            cm.fireAutoTurn(state);
         } else {
            cm.stopRelay();
            cm.autoRelayFailoverOrEnd(state, "voxlink.relay.failed");
         }
      }
   }
   static boolean tryCarrierSwap(ConnectionManager cm, RoomManager.RoomState state, ReliableUdpTransport newTransport) {
      String from = cm.carrierSwapFrom;
      if (from == null || !P2PBridge.isRunning() || P2PBridge.getJoinerCarrier() == null) {
         return false;
      }

      cm.carrierSwapFrom = null;
      byte[] uplinkJournal = P2PBridge.takeJoinerUplinkJournal();
      boolean swapped = P2PBridge.swapJoinerCarrier(newTransport, uplinkJournal);
      if (!swapped) {
         VoxLinkMod.LOGGER.warn("[AutoRelay] carrier swap failed (no local bridge?), falling back to fresh bridge");
         return false;
      }

      if (uplinkJournal != null && uplinkJournal.length > 0) {
         VoxLinkMod.LOGGER.info("[AutoRelay] uplink journal seeded {} bytes onto new carrier", uplinkJournal.length);
      }

      // 拆旧道
      if ("turn".equals(from)) {
         cm.teardownTurnQuietly();
      } else if ("player".equals(from)) {
         cm.stopRelay();
      }

      cm.armRelayVerifyWatchdog(state, !"player".equals(from));
      icu.wuhui.voxlink.ui.UiLogBus.push(1, "voxlink.logui.auto_relay_swapped");
      return true;
   }
   static void stopAutoRelayAll(ConnectionManager cm) {
      cm.cancelAutoRelayDirectorArm();
      cm.stopAutoRelayDirector();
      cm.cancelRelayVerifyWatchdog();
      cm.cancelRelayBgProbe();
      cm.pendingCarrierSwapTransport = null;
      cm.carrierSwapFrom = null;
      cm.autoRelayMode = false;
      cm.autoRelayTurnAttempted = false;
      cm.autoRelayTurnUnhealthy = false;
      cm.autoRelayPlayerAttempted = false;
      cm.autoRelayArmedRound = -1;
   }
   static void scheduleAutoRelayDirectorArm(ConnectionManager cm) {
      ScheduledFuture<?> old = cm.autoRelayArmWaiterTask;
      if (old != null) {
         old.cancel(false);
      }

      cm.autoRelayArmWaiterTask = cm.scheduler.scheduleWithFixedDelay(() -> {
         try {
            RoomManager.RoomState state = cm.roomManager.currentRoom.get();
            if (state == null || cm.connectionWon.get()) {
               cm.cancelAutoRelayDirectorArm();
               return;
            }

            if (state != RoomManager.PENDING) {
               cm.cancelAutoRelayDirectorArm();
               if (!state.roomInfo.isHost()) {
                  cm.startAutoRelayDirector(state);
               }
            }
         } catch (Exception e) {
            VoxLinkMod.LOGGER.debug("[AutoRelay] arm waiter error: {}", e.getMessage());
         }
      }, 1L, 1L, TimeUnit.SECONDS);
   }
   static void cancelAutoRelayDirectorArm(ConnectionManager cm) {
      ScheduledFuture<?> f = cm.autoRelayArmWaiterTask;
      if (f != null) {
         f.cancel(false);
         cm.autoRelayArmWaiterTask = null;
      }
   }
   static void startRelayBgProbe(ConnectionManager cm, RoomManager.RoomState state) {
      cm.cancelRelayBgProbe();
      final long deadlineMs = System.currentTimeMillis() + 600_000L;
      cm.relayBgProbeTask = cm.scheduler.scheduleAtFixedRate(() -> {
         try {
            if (cm.roomManager.currentRoom.get() != state || System.currentTimeMillis() > deadlineMs) {
               cm.cancelRelayBgProbe();
               return;
            }

            ReliableUdpTransport relayLeg = cm.activeUdpTransports.get("relay_cone");
            if (relayLeg == null || !relayLeg.isConnected()) {
               cm.cancelRelayBgProbe();
               return;
            }

            String ip = state.roomInfo.getHostMappedIp();
            int port = state.roomInfo.getHostMappedPort();
            if (ip == null || port <= 0) {
               return;
            }

            UdpHolePuncher probe = new UdpHolePuncher();
            cm.applyPunchTemplate(probe);
            try {
               probe.createSocket();
            } catch (Exception e) {
               return;
            }

            probe
               .punch(ip, port)
               .orTimeout(5L, TimeUnit.SECONDS)
               .thenAccept(r -> {
                  if (r.isSuccess()) {
                     VoxLinkMod.LOGGER.info("[Relay] bg direct probe: host answered — direct viable (seamless upgrade lands with cross-room iteration)");
                  }
                  probe.close();
               })
               .exceptionally(e -> {
                  probe.close();
                  return null;
               });
         } catch (Exception e) {
            VoxLinkMod.LOGGER.debug("[Relay] bg probe tick error: {}", e.getMessage());
         }
      }, 30L, 30L, TimeUnit.SECONDS);
   }
   static void cancelRelayBgProbe(ConnectionManager cm) {
      ScheduledFuture<?> f = cm.relayBgProbeTask;
      if (f != null) {
         f.cancel(false);
         cm.relayBgProbeTask = null;
      }
   }
   static void handleRelaySetup(ConnectionManager cm, String from, JsonObject data) {
      // 候选者若本身就是房主，它没有"自家房主"可回执：只能按 relay_setup 里的 replyHostId
      // 直投请求方房主（服务端把 to=host-<CODE> 落到对方房间并归一成该房房主的 pollerID）
      RoomManager.RoomState setupState = cm.roomManager.currentRoom.get();
      final boolean setupAsHost = setupState != null
         && setupState != RoomManager.PENDING
         && setupState.roomInfo.isHost();
      final String setupReplyTo = setupAsHost && data.has("replyHostId")
         ? data.get("replyHostId").getAsString()
         : "host";
      if (setupAsHost && "host".equals(setupReplyTo)) {
         VoxLinkMod.LOGGER.warn("[Relay] 被选为候选但对方未留 replyHostId（旧版房主），回执可能落到本房房主");
      }


      if (!VoxLinkMod.getConfig().isRelayEnabled()) {

         RoomManager.RoomState state = cm.roomManager.currentRoom.get();

         if (state != null && state != RoomManager.PENDING) {

            cm.signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), setupAsHost, "relay_declined", new JsonObject(), setupAsHost ? setupReplyTo : from);

         }

      } else {

         RoomManager.RoomState state0 = cm.roomManager.currentRoom.get();

         if (state0 != null && state0 != RoomManager.PENDING) {

            String targetClientId = data.has("targetClientId") ? data.get("targetClientId").getAsString() : null;

            String targetIp = data.has("targetIp") ? data.get("targetIp").getAsString() : null;

            int targetPort = data.has("targetPort") ? data.get("targetPort").getAsInt() : 0;

            if (targetIp != null && targetPort > 0) {

               // v3 跨房分支：请求来自他房房主（crossRoom 标记）——不复用本端 joiner
               // 传输（那连的是自己房主，v1 跨房注入的病根），改建专用 mux 腿
               if (data.has("crossRoom") && !data.get("crossRoom").isJsonNull() && data.get("crossRoom").getAsBoolean()) {
                  handleCrossRoomRelaySetup(cm, state0, setupAsHost, setupReplyTo, targetClientId, targetIp, targetPort, data);
                  return;
               }

               RoomManager.RoomState state = cm.roomManager.currentRoom.get();

               // 玩家中继 v2（2026-10-05）：被中继会话必须走本端"joiner"传输的 mux 子通道。
               // v1 曾在此处复用任意已连接传输转发裸流——包括连到别的房间 host 的传输，
               // 两条 MC 会话字节交错+InputStream 双读者互抢，从未真正可用（本地实验实证）。
               // 旧版 host（未声明 relayMuxV1）或本端传输未 mux 化：一律婉拒，绝不走 v1 坏接线。
               ReliableUdpTransport hostTransport = cm.activeUdpTransports.get("joiner");

               boolean muxUsable = hostTransport != null && hostTransport.isConnected() && hostTransport.isMuxEnabled();

               if (!muxUsable) {

                  if (!ProtocolNegotiator.hostSupportsRelayMux(state0.roomInfo)) {

                     VoxLinkMod.LOGGER.info("[Relay] host lacks relayMuxV1, decline relay_setup (v1 relay data plane was broken, never wire it)");

                  } else {

                     VoxLinkMod.LOGGER.warn("[Relay] own transport to host not mux-enabled, decline relay_setup");

                  }

                  cm.signalingClient.sendSignal(state0.roomInfo.getCode(), state0.roomInfo.getToken(), setupAsHost, "relay_declined", new JsonObject(), setupAsHost ? setupReplyTo : from);

                  return;

               }



               if (hostTransport != null) {

                  List<UdpHolePuncher> conePunchers = new ArrayList<>();

                  AtomicBoolean coneWon = new AtomicBoolean(false);

                  String fTargetIp = targetIp;

                  int fTargetPort = targetPort;

                  String fTargetClientId = targetClientId;

                  ReliableUdpTransport fHostTransport = hostTransport;



                  for (int i = 0; i < cm.punchProfile().relaySocketCount; i++) {

                     UdpHolePuncher cp = new UdpHolePuncher();

                     cm.applyPunchTemplate(cp);



                     try {

                        cp.createSocket();

                     } catch (Exception e) {

                        continue;

                     }



                     conePunchers.add(cp);

                     cm.activeHolePunchers.put("relay_to_sym_" + i, cp);

                     int idx = i;

                     cp.punchWithPortPrediction(fTargetIp, fTargetPort, cm.punchProfile().coneBackupPortRange)

                        .orTimeout(15L, TimeUnit.SECONDS)

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

                                 if (!coneWon.compareAndSet(false, true)) {

                                    try {

                                       cp.close();

                                    } catch (Exception var15x) {

                                    }

                                 } else {

                                    VoxLinkMod.LOGGER.info("[Relay] Cone->Sym socket#{} punch success", idx);

                                    cp.markSocketTransferred();



                                    for (UdpHolePuncher op : conePunchers) {

                                       if (op != cp) {

                                          try {

                                             op.cancel();

                                             op.close();

                                          } catch (Exception var16x) {

                                          }

                                       }

                                    }



                                    ReliableUdpTransport peerTransport = new ReliableUdpTransport(socket, new InetSocketAddress(fTargetIp, fTargetPort));

                                    peerTransport.start();

                                    cm.activeUdpTransports.put(fTargetClientId != null ? fTargetClientId : "sym_relayed", peerTransport);

                                    // v2：被中继会话挂到本端 joiner 传输的 mux 子通道（chan 1..7），
                                    // 与自己（chan 0）的 MC 会话字节级隔离；任一腿 EOF/死亡由 RelayBridge 双向收尾
                                    final int muxChan = cm.allocateRelayMuxChan();
                                    // key 带 clientId: chan 1..7 循环复用, 纯 chan key 会撞残留会话
                                    final String relayKey = "muxrelay-" + muxChan + "-" + (fTargetClientId != null ? fTargetClientId : "sym");
                                    RelayBridge.getInstance(cm.scheduler).startRelay(
                                       relayKey,
                                       "host-chan" + muxChan,
                                       fTargetClientId != null ? fTargetClientId : "sym",
                                       new RelayBridge.RelayLeg() {
                                          @Override
                                          public java.io.InputStream input() {
                                             return fHostTransport.getSubstreamInput(muxChan);
                                          }

                                          @Override
                                          public java.io.OutputStream output() {
                                             return fHostTransport.getSubstreamOutput(muxChan);
                                          }

                                          @Override
                                          public void close() {
                                             try {
                                                fHostTransport.closeSubstream(muxChan);
                                             } catch (Exception e) {
                                             }
                                          }
                                       },
                                       RelayBridge.legOf(peerTransport)
                                    );

                                    JsonObject reply = new JsonObject();

                                    reply.addProperty("forClientId", fTargetClientId != null ? fTargetClientId : "sym");

                                    reply.addProperty("chan", muxChan);

                                    cm.signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), setupAsHost, "relay_accept", reply, setupReplyTo);

                                 }

                              }

                           }

                        )

                        .exceptionally(e -> {

                           if (!coneWon.get()) {

                              try {

                                 cp.close();

                              } catch (Exception var6x) {

                              }



                              cm.activeHolePunchers.remove("relay_to_sym_" + idx);

                           }



                           return null;

                        });

                  }



                  cm.scheduler.schedule(() -> {

                     if (!coneWon.get()) {

                        VoxLinkMod.LOGGER.warn("[Relay] Cone->Sym relay punch timeout (8s)");



                        for (UdpHolePuncher op : conePunchers) {

                           try {

                              op.cancel();

                              op.close();

                           } catch (Exception var7x) {

                           }

                        }



                        cm.activeHolePunchers.entrySet().removeIf(e -> e.getKey().startsWith("relay_to_sym_"));

                        cm.signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), setupAsHost, "relay_declined", new JsonObject(), setupReplyTo);

                     }

                  }, 8L, TimeUnit.SECONDS);

               }

            }

         }

      }

   }
   static void onRelayDisconnected(ConnectionManager cm, String peerA, String peerB) {

      VoxLinkMod.LOGGER.warn("[Relay] Relay disconnect notify: {}<->{}", peerA, peerB);

      RoomManager.RoomState state = cm.roomManager.currentRoom.get();

      if (state != null && state != RoomManager.PENDING && !cm.connectionWon.get()) {

         if (cm.currentRelayPeer.get() != null) {

            cm.failedRelayPeers.put(cm.currentRelayPeer.get(), System.currentTimeMillis());

         }



         cm.clearRelayTracking();

         cm.scheduler.schedule(() -> {

            if (cm.roomManager.currentRoom.get() == state && !cm.connectionWon.get()) {

               VoxLinkMod.LOGGER.info("[Relay] Auto switch to backup relay...");

               cm.tryRelay(state);

            }

         }, 500L, TimeUnit.MILLISECONDS);

      }

   }
}
