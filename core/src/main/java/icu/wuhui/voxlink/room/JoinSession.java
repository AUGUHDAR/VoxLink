package icu.wuhui.voxlink.room;

import com.google.gson.JsonObject;
import icu.wuhui.voxlink.VoxLinkConstants;
import icu.wuhui.voxlink.platform.ClientThread;
import icu.wuhui.voxlink.network.UdpHolePuncher;
import icu.wuhui.voxlink.network.LogUploadManager;
import java.net.URI;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import icu.wuhui.voxlink.VoxLinkMod;

import icu.wuhui.voxlink.network.NatClass;

import icu.wuhui.voxlink.platform.Text;

import icu.wuhui.voxlink.platform.ChatSink;

import java.util.List;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.CompletableFuture;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.ArrayList;

import icu.wuhui.voxlink.network.P2PBridge;

import icu.wuhui.voxlink.network.ReliableUdpTransport;

import icu.wuhui.voxlink.network.ProtocolNegotiator;

import icu.wuhui.voxlink.network.ConnectionHelper;

/** 加入/退房/终态: join 请求、取消、失败终局、队列重放、零收包判死。(Phase4 从 ConnectionManager 拆出) */
public final class JoinSession {
   private JoinSession() {
   }

   static void stopAllConnectionWork(ConnectionManager cm) {

      cm.stopAutoRelayAll();

      cm.clearHostPunchContexts();
      cm.cancelAllFallbacks();



      for (UdpHolePuncher puncher : cm.activeHolePunchers.values()) {

         try {

            puncher.cancel();

            puncher.close();

         } catch (Exception var5) {

         }

      }



      cm.activeHolePunchers.clear();



      for (ReliableUdpTransport transport : cm.activeUdpTransports.values()) {

         try {

            transport.close();

         } catch (Exception var4) {

         }

      }



      cm.activeUdpTransports.clear();

   }
   static void handleJoinRequest(ConnectionManager cm, String from, JsonObject data) {

      cm.handleJoinRequest(from, data, 0);

   }
   static void handlePeerPort(ConnectionManager cm, String from, JsonObject data) {

      String peerIp = data.has("peer_ip") ? data.get("peer_ip").getAsString() : null;

      int peerPort = data.has("peer_port") ? data.get("peer_port").getAsInt() : 0;

      if (peerIp != null && peerPort > 0) {

         UdpHolePuncher puncher = cm.activeHolePunchers.get(from.contains("host") ? "joiner" : "host");

         if (puncher != null) {

            VoxLinkMod.LOGGER.info("[RoomManager] Received peer_port signal: update target to {}:{}", peerIp, peerPort);

            puncher.updateTarget(peerIp, peerPort);

         }

      }

   }
   static void showConnectFailed(ConnectionManager cm, RoomManager.RoomState state) {

      cm.showConnectFailed(state, "voxlink.connection.all_failed");

   }
   static void showConnectFailedFinal(ConnectionManager cm, RoomManager.RoomState state) {

      cm.showConnectFailedFinal(state, "voxlink.connection.all_failed");

   }
   static void leaveRoomOnFailure(ConnectionManager cm, RoomManager.RoomState state) {
   
      if (cm.roomManager.currentRoom.get() == state && state != RoomManager.PENDING) {
   
         if (state.roomInfo != null && state.roomInfo.isHost()) {
   
            // 房主侧连接失败≠房间失败：回IDLE继续等人，关房只能由房主本人操作触发
   
            // (修复前此分支leaveRoom→服务端强制关房，任何房客失败/取消都能关掉整个房间)
   
            cm.connectionCycleActive.set(false);
   
            cm.connectionWon.set(false);
   
            ConnectionState.transitionTo(ConnectionState.IDLE, "连接失败,回等待");
   
         }
   
      }
   
   }
   static void killAllConnectionAttempts(ConnectionManager cm) {
      cm.joinAttemptGeneration.incrementAndGet();


      boolean alreadyWon = cm.connectionWon.get();

      if (!alreadyWon && !cm.continuousRetryCancelled.getAndSet(true) && cm.continuousRetryRound.get() > 0) {

         cm.sendCancelConnectionSignal();

      } else {

         cm.continuousRetryCancelled.set(true);

      }



      cm.connectionCycleActive.set(false);

      cm.connectionWon.set(true);

      if (!alreadyWon) {

         ConnectionState.reset();

      }



      if (cm.connectionTimeoutFuture != null) {

         cm.connectionTimeoutFuture.cancel(false);

         cm.connectionTimeoutFuture = null;

      }



      cm.cancelConnectionCycleSafety();



      for (UdpHolePuncher puncher : cm.activeHolePunchers.values()) {

         try {

            puncher.cancel();

         } catch (Exception var9) {

         }



         try {

            puncher.stopPunch();

         } catch (Exception var8) {

         }



         try {

            puncher.close();

         } catch (Exception var7) {

         }

      }



      cm.activeHolePunchers.clear();
      cm.hostRevPunchPeer = null;



      for (ReliableUdpTransport t : cm.activeUdpTransports.values()) {

         try {

            t.close();

         } catch (Exception var6) {

         }

      }



      cm.activeUdpTransports.clear();



      for (ReliableUdpTransport t : cm.oldUdpTransports.values()) {

         try {

            t.close();

         } catch (Exception var5) {

         }

      }



      cm.oldUdpTransports.clear();
      cm.cancelAllFallbacks();
      cm.clearHostPunchContexts();
      cm.closeVoiceForwardBridges();
      cm.peerReadyConfirmations.clear();
      cm.queuedJoinReplays.clear();

   }
   static void killDualRace(ConnectionManager cm) {

      cm.dualRaceActive = false;

      cm.voxlinkSideDisabled = true;

      cm.killAllConnectionAttempts("voxlink");

      cm.killAllConnectionAttempts("terracotta");

      CompletableFuture<Void> bf = cm.dualVoxlinkBridgeFuture;

      if (bf != null && !bf.isDone()) {

         bf.completeExceptionally(new RuntimeException("用户取消"));

      }



      cm.dualVoxlinkBridgeFuture = null;

      cm.dualResultRef = null;

      cm.dualFailedCount.set(0);

      cm.terracottaWon = false;

      cm.voxlinkWon = false;

      cm.dualRaceWon.set(false);

   }
   static void handleCancelConnection(ConnectionManager cm, String from, JsonObject data) {

      VoxLinkMod.LOGGER.info("[Connection] Received cancel_connection signal from peer ({}), abort persistent retry", from);

      cm.continuousRetryCancelled.set(true);

      RoomManager.RoomState state = cm.roomManager.currentRoom.get();

      if (state != null && state != RoomManager.PENDING) {

                  state.roomInfo.removePeer(from);
         
            if (state.roomInfo.isHost()) {
         
               // 对端取消不是本机连接失败：房主只清该房客回等待，绝不能终态关房
         
               cm.clearHostPunchingState();
         
               // 房客取消同样清TURN对端
         
               if (cm.isTurnPeer(from)) {
         
                  cm.cleanupTurnQuietly();
         
               }
         
               cm.abortPunchOnPeerDisconnect(from);
         
               ReliableUdpTransport transport = cm.removeUdpTransport(from);
         
               if (transport != null) {
         
                  try {
         
                     transport.close();
         
                  } catch (Exception e) {
         
                  }
         
               }
         
               icu.wuhui.voxlink.ui.UiLogBus.push(2, "voxlink.logui.peer_left");
         
            } else {
         
               cm.showConnectFailedFinal(state, "voxlink.connection.peer_cancelled");
         
               ClientThread.runOnClientThread(
         
                  () -> ChatSink.chat(Text.translatable("voxlink.chat.error_prefix").append(Text.translatable("voxlink.connection.peer_cancelled"))))
         
               ;
         
            }
         
         }
   }
   static void notifyAllPeersGone(ConnectionManager cm) {

      RoomManager.RoomState state = cm.roomManager.currentRoom.get();

      if (state != null && state != RoomManager.PENDING && state.roomInfo.isHost()) {

         if (state.roomInfo.hasRecentPeer(60000L)) {

            VoxLinkMod.LOGGER.info("[Connection] Peers still active in signaling, skip all-peers-gone");

         } else if (!state.roomInfo.hasEverHadPeer()) {

            VoxLinkMod.LOGGER.info("[Connection] No peer ever joined, keep waiting");

         } else if (ConnectionState.getCurrent() == ConnectionState.IDLE) {

            VoxLinkMod.LOGGER.info("[Connection] IDLE state, clear stale flags and keep waiting");

            cm.connectionCycleActive.set(false);

            cm.continuousRetryRound.set(0);

         } else {

            boolean inConnection = cm.connectionCycleActive.get() || cm.continuousRetryRound.get() > 0;

            cm.continuousRetryCancelled.set(true);

            VoxLinkMod.LOGGER.info("[Connection] All peers gone (host alone), inConnection={}, abort persistent retry", inConnection);

            state.roomInfo.clearPeers();

            // "对端全走光"不是"本机连接失败"：一律回 IDLE 继续等人。
            // 修复前这里在 inConnection 分支走"连接失败终态"→ leaveRoom，
            // 于是房客加入失败离开，会把房主自己的房间关掉。
               cm.connectionCycleActive.set(false);

               ConnectionState.transitionTo(ConnectionState.IDLE, "对方离开,回等待");

         }

      }

   }
   static void sendCancelConnectionSignal(ConnectionManager cm) {

      try {

         RoomManager.RoomState state = cm.roomManager.currentRoom.get();

         if (state == null || state == RoomManager.PENDING || state.roomInfo == null) {

            return;

         }



         String target = state.roomInfo.isHost() ? "all" : "host";

         cm.signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), false, "cancel_connection", new JsonObject(), target);

         VoxLinkMod.LOGGER.info("[Connection] Sent cancel_connection signal to peer ({})", target);

      } catch (Exception e) {

         VoxLinkMod.LOGGER.warn("[Connection] Send cancel_connection signal failed: {}", e.getMessage());

      }

   }
   static int parsePortFromUrl(String url) {

      if (url == null) {

         return -1;

      }



      try {

         URI u = URI.create(url.contains("://") ? url : "tcp://" + url);

         int port = u.getPort();

         return port > 0 ? port : 25565;

      } catch (Exception e) {

         return -1;

      }

   }
   static void sendDisconnectOnFailure(ConnectionManager cm, RoomManager.RoomState state) {

      try {

         cm.signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), false, "disconnect", new JsonObject(), "host");

      } catch (Exception e) {

         VoxLinkMod.LOGGER.debug("Send disconnect failed on connection failure: {}", e.getMessage());

      }

   }
   static void abortPunchOnPeerDisconnect(ConnectionManager cm, String from) {

      RoomManager.RoomState state = cm.roomManager.currentRoom.get();

      if (state == null || state == RoomManager.PENDING || !state.roomInfo.isHost()) {

         return;

      }

      cm.cancelQueuedJoinReplays(from);

      boolean punchingBusy = cm.connectionCycleActive.get() || cm.continuousRetryRound.get() > 0 || cm.isAnyHostPunching();

      // 仅当断开的对端正是当前打洞/建链目标时才整体中止，避免多peer房间误杀其他会话

      boolean targetsThisPeer = cm.hostPunchContexts.containsKey(from)
         || cm.activeUdpTransports.containsKey(from)
         || from.equals(cm.hostRevPunchPeer)
         || from.equals(cm.savedConnectionFrom);

      if (!punchingBusy || !targetsThisPeer || cm.connectionWon.get()) {

         return;

      }

      VoxLinkMod.LOGGER.warn("[ConnState] 对端 {} 已断开/取消连接，立即中止本轮打洞与持续重试，回IDLE / peer cancelled, stop punching now", from);

      cm.continuousRetryCancelled.set(true);

      cm.killAllConnectionAttempts("peer_disconnect");

      cm.peerReadyConfirmations.remove(from);

      state.roomInfo.setConnectionMode(Text.translatable("voxlink.connection.peer_left"), true);

   }
   static void registerQueuedJoinReplay(ConnectionManager cm, String from, ScheduledFuture<?> future) {

      if (from == null || future == null) {

         return;

      }

      List<ScheduledFuture<?>> list = cm.queuedJoinReplays.computeIfAbsent(from, k -> new CopyOnWriteArrayList<>());

      list.add(future);

   }
   static void cancelQueuedJoinReplays(ConnectionManager cm, String from) {

      if (from == null) {

         return;

      }

      cm.queuedJoinPayloads.remove(from);

      List<ScheduledFuture<?>> list = cm.queuedJoinReplays.remove(from);

      if (list != null && !list.isEmpty()) {

         VoxLinkMod.LOGGER.info("[RoomManager] Invalidate {} queued join_request replay(s) for disconnected peer {}", list.size(), from);

         for (ScheduledFuture<?> f : list) {

            f.cancel(false);

         }

      }

   }
   static void flushQueuedJoinRequestsAfterWin(ConnectionManager cm) {
      if (cm.queuedJoinPayloads.isEmpty()) {
         return;
      }
      for (String peer : cm.queuedJoinPayloads.keySet().toArray(new String[0])) {
         JsonObject payload = cm.queuedJoinPayloads.remove(peer);
         cm.cancelQueuedJoinReplays(peer);
         RoomManager.RoomState st = cm.roomManager.currentRoom.get();
         if (st == null || st == RoomManager.PENDING || !st.roomInfo.isHost() || peer == null) {
            continue;
         }
         VoxLinkMod.LOGGER.info("[RoomManager] Host connection won, flush queued join_request from {}", peer);
         cm.handleJoinRequest(peer, payload != null ? payload : new JsonObject(), 0);
      }
   }
   static void scheduleDeferredJoinReplay(ConnectionManager cm, RoomManager.RoomState state, String from, JsonObject data, int waitCount) {

      // H3a: 闭包创建时捕获房间码,执行时校验,避免换房后旧任务在新房间重放
      String capturedRoomCode = state.roomInfo.getCode();

      ScheduledFuture<?> future = cm.scheduler.schedule(() -> {

         try {

            RoomManager.RoomState st = cm.roomManager.currentRoom.get();

            if (st == null || st == RoomManager.PENDING || !st.roomInfo.isHost()) {

               return;

            }

            if (capturedRoomCode == null || !capturedRoomCode.equals(st.roomInfo.getCode())) {

               VoxLinkMod.LOGGER.warn("[RoomManager] Drop deferred join_request from {}: room changed (captured={}, current={})", new Object[]{from, capturedRoomCode, st.roomInfo.getCode()});

               return;

            }

            if (cm.activeUdpTransports.containsKey(from)) {

               VoxLinkMod.LOGGER.info("[RoomManager] Client {} connected, drop deferred join_request", from);

               return;

            }

            cm.expireStaleHostPunchContexts();

            boolean busy = cm.isAnyHostPunching() || cm.connectionCycleActive.get();

            if (!busy) {

               VoxLinkMod.LOGGER.info("[RoomManager] Punch cycle ended, replay deferred join_request from {}", from);

               cm.handleJoinRequest(from, data, 0);

            } else if (waitCount < 12) {

               cm.scheduleDeferredJoinReplay(st, from, data, waitCount + 1);

            } else {

               // H3b: 排队满120s仍无可用打洞槽位,通知房客放弃等待,避免无限挂死
               VoxLinkMod.LOGGER.warn("[RoomManager] Deferred join_request from {} dropped after 120s of waiting, notify peer to give up", from);

               try {

                  JsonObject giveUpData = new JsonObject();

                  giveUpData.addProperty("reason", "join_queue_timeout");

                  cm.signalingClient.sendSignal(st.roomInfo.getCode(), st.roomInfo.getToken(), st.roomInfo.isHost(), "disconnect", giveUpData, from);

               } catch (Exception sigEx) {

                  VoxLinkMod.LOGGER.debug("[RoomManager] Send join_queue_timeout disconnect failed: {}", sigEx.getMessage());

               }

            }

         } catch (Exception ex) {

            VoxLinkMod.LOGGER.warn("[RoomManager] Deferred join_request replay error: {}", ex.getMessage());

         }

      }, ConnectionManager.JOIN_QUEUE_RETRY_SEC, TimeUnit.SECONDS);

      cm.registerQueuedJoinReplay(from, future);

   }
   static void declareZeroRecvFinalFailure(ConnectionManager cm, RoomManager.RoomState state) {

      if (cm.connectionWon.get() || cm.voxlinkWon || cm.terracottaWon) {

         return;

      }

      VoxLinkMod.LOGGER
         .warn(
            "[ConnState] 打洞最终失败：双向零收包(持续重试{}轮全程recvPunch=0/recvAck=0)，当前网络环境无法直连，建议开启中继或更换网络 / Punch finally failed: zero packets received in both directions, try relay or another network",
            new Object[]{cm.continuousRetryRound.get()}
         );

// TURN 中继已就位时: 中继正在承载数据面, 绝不能发 give-up/清状态——

      // give-up 的 disconnect 信号会让 host 释放 TURN 会话, 把唯一可用的通路拆掉。

      // 只停直连打洞, 会话保持在中继上(后台重试已被下面 continuousRetryCancelled 一并停掉, 符合"零收包无法直连"的事实)

      if ((cm.turnSession != null || cm.stdTurnSession != null) && cm.turnTransport != null && cm.turnTransport.isConnected()) {

         VoxLinkMod.LOGGER.info("[Turn] relay is up and carrying the session, keep relay alive instead of declaring final failure");

         cm.connectionCycleActive.set(false);

         for (UdpHolePuncher puncher : cm.activeHolePunchers.values()) {

            try {

               puncher.cancel();

            } catch (Exception e) {

            }

            try {

               puncher.close();

            } catch (Exception e) {

            }

         }

         cm.activeHolePunchers.clear();

         cm.clearHostPunchContexts();

         return;
      }



      cm.continuousRetryCancelled.set(true);

      cm.connectionCycleActive.set(false);

      ConnectionState.transitionTo(ConnectionState.FAILED, "持续重试耗尽且双向零收包");
      icu.wuhui.voxlink.ui.UiLogBus.push(3, "voxlink.connection.zero_recv_failed");
      // 后台重试耗尽=真终态: 无条件上传失败样本(2026-10-05 补: 此前该路径零上传, 玩家中继/后台重试失败样本全丢)
      LogUploadManager.onTerminalFailure();

      ConnectionHelper.resetConnecting();

      for (UdpHolePuncher puncher : cm.activeHolePunchers.values()) {

         try {

            puncher.cancel();

         } catch (Exception e) {

         }

         try {

            puncher.close();

         } catch (Exception e) {

         }

      }

      cm.activeHolePunchers.clear();

      cm.clearHostPunchContexts();

      if (state != null && state != RoomManager.PENDING && state.roomInfo != null) {

         state.roomInfo.setConnectionMode(Text.translatable("voxlink.connection.zero_recv_failed"), true);

         cm.notifyPeerGiveUpOnZeroRecv(state);

      }

   }
   static void notifyPeerGiveUpOnZeroRecv(ConnectionManager cm, RoomManager.RoomState state) {

      try {

         JsonObject giveUpData = new JsonObject();

         giveUpData.addProperty("reason", "zero_recv_final");

         String target = state.roomInfo.isHost() ? "all" : "host";

         cm.signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), state.roomInfo.isHost(), "disconnect", giveUpData, target);

         VoxLinkMod.LOGGER.info("[Connection] Sent give-up (disconnect, reason=zero_recv_final) to {}, peer will stop punching too", target);

      } catch (Exception e) {

         VoxLinkMod.LOGGER.debug("[Connection] Send give-up signal failed: {}", e.getMessage());

      }

   }
   static void handleIncompatibleVersion(ConnectionManager cm, String from, JsonObject data) {

      String hostVer = data.has("hostVersion") && !data.get("hostVersion").isJsonNull() ? data.get("hostVersion").getAsString() : "unknown";

      String myVer = VoxLinkConstants.GAME_VERSION;

      VoxLinkMod.LOGGER.warn("[ConnState] Host MC version incompatible: host={} you={} / 与房主版本不匹配", hostVer, myVer);

      Text mismatchMsg = Text.translatable("voxlink.chat.error_prefix")
         .append(Text.translatable("voxlink.connection.mc_mismatch", hostVer, myVer));

      ClientThread.runOnClientThread(() -> {

         ChatSink.chat(mismatchMsg.withStyle(Text.Style.builder().color(Text.ChatFormat.RED).build()));

      });

      RoomManager.RoomState state = cm.roomManager.currentRoom.get();

      if (state != null && state != RoomManager.PENDING && !state.roomInfo.isHost()) {

         state.roomInfo.setConnectionMode(Text.translatable("voxlink.connection.mc_mismatch_mode"), true);

         ConnectionState.transitionTo(ConnectionState.FAILED, "与房主MC版本不兼容 host=" + hostVer);

         cm.leaveRoomOnFailure(state);

      }

   }
   static boolean peerSupportsCapability(ConnectionManager cm, RoomManager.RoomState state, String clientId, String capability) {
      if (state == null || clientId == null) {
         return false;
      }
      RoomInfo.PeerInfo peer = state.roomInfo.getPeer(clientId);
      return peer != null && peer.capabilities != null && peer.capabilities.contains(capability);
   }
   static void shutdown(ConnectionManager cm) {

      if (cm.connectionTimeoutFuture != null) {

         cm.connectionTimeoutFuture.cancel(false);

         cm.connectionTimeoutFuture = null;

      }



      if (cm.cachedUdpArray != null) {

         try {

            cm.cachedUdpArray.close();

         } catch (Exception var3) {

         }



         cm.cachedUdpArray = null;

      }



      cm.stopAllConnectionWork();

      if (cm.punchExecutor != null && !cm.punchExecutor.isShutdown()) {

         cm.punchExecutor.shutdown();



         try {

            cm.punchExecutor.awaitTermination(2L, TimeUnit.SECONDS);

         } catch (InterruptedException var2) {

         }

      }



      cm.clearRelayTracking();

      cm.failedRelayPeers.clear();

   }
}
