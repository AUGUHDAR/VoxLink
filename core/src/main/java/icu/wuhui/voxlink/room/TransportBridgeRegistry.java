package icu.wuhui.voxlink.room;

import com.google.gson.JsonObject;
import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.network.NatClass;
import icu.wuhui.voxlink.network.P2PBridge;
import icu.wuhui.voxlink.network.ReliableUdpTransport;
import icu.wuhui.voxlink.network.UdpForwardBridge;
import icu.wuhui.voxlink.network.UdpHolePuncher;
import icu.wuhui.voxlink.network.ProtocolNegotiator;
import icu.wuhui.voxlink.network.LogUploadManager;
import icu.wuhui.voxlink.platform.Text;
import icu.wuhui.voxlink.room.RoomInfo;
import icu.wuhui.voxlink.network.ConnectionHelper;
import icu.wuhui.voxlink.room.ConnectionState;
import icu.wuhui.voxlink.network.StunProbe;
import icu.wuhui.voxlink.network.PunchAuth;
import icu.wuhui.voxlink.network.PunchProfile;
import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicBoolean;
import icu.wuhui.voxlink.platform.ChatSink;
import java.util.ArrayList;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Set;
import java.util.HashSet;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ExecutionException;
import java.net.DatagramPacket;
import java.util.concurrent.atomic.AtomicReference;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** 传输/桥注册: UDP socket 池、Transport 注册、voice 前向桥、桥建立入口。
 *  (Phase4 从 ConnectionManager 拆出) */
public final class TransportBridgeRegistry {
   private TransportBridgeRegistry() {
   }

   static UdpSocketArray getOrCreateUdpArray(ConnectionManager cm, int requiredSize, boolean isEasySym, List<String> stunUrls) {

      long now = System.currentTimeMillis();

      if (cm.cachedUdpArray != null) {

         if (cm.cachedUdpArray.isReusable(requiredSize, isEasySym, now)) {

            VoxLinkMod.LOGGER

               .info(

                  "[BirthdayPunch] Reuse cached socket array: {} sockets, age={}ms", cm.cachedUdpArray.punchers.size(), now - cm.cachedUdpArray.createTime

               );

            return cm.cachedUdpArray;

         }



         cm.cachedUdpArray.close();

         cm.cachedUdpArray = null;

      }



      List<UdpHolePuncher> punchers = new ArrayList<>();

      List<StunProbe.PublicMappedAddress> addrs = new ArrayList<>();

      List<CompletableFuture<Object[]>> futures = new ArrayList<>();

      int socketStunCount = Math.max(1, Math.min(cm.punchProfile().socketStunCount, stunUrls.size()));

      List<String> raceUrls = stunUrls.size() > socketStunCount ? new ArrayList<>(stunUrls.subList(0, socketStunCount)) : stunUrls;

      int createIntervalMs = Math.max(0, cm.punchProfile().socketCreateIntervalMs);



      for (int i = 0; i < requiredSize; i++) {

         int idx = i;

         UdpHolePuncher puncher = new UdpHolePuncher();

         cm.applyPunchTemplate(puncher);



         try {

            puncher.createSocket();

         } catch (Exception e) {

            VoxLinkMod.LOGGER.warn("[BirthdayPunch] Create socket #{} failed: {}", idx, e.getMessage());



            try {

               puncher.close();

            } catch (Exception var27) {

            }

            continue;

         }



         futures.add(CompletableFuture.supplyAsync(() -> {

            try {

               StunProbe.PublicMappedAddress[] race = StunProbe.discoverMappedAddressRace(puncher.getSocket(), raceUrls, 1);

               StunProbe.PublicMappedAddress addrx = race != null && race.length > 0 ? race[0] : null;

               if (addrx != null) {

                  return new Object[]{puncher, addrx};

               }



               try {

                  puncher.close();

               } catch (Exception var6x) {

               }



               return null;

            } catch (Exception e) {

               try {

                  puncher.close();

               } catch (Exception var5) {

               }



               return null;

            }

         }));

         if (createIntervalMs > 0) {

            try {

               Thread.sleep(createIntervalMs);

            } catch (InterruptedException ignored) {

               Thread.currentThread().interrupt();

               break;

            }

         }

      }



      int minRequired = Math.min(cm.punchProfile().birthdaySocketCount, requiredSize);

      AtomicInteger successCount = new AtomicInteger(0);

      AtomicInteger doneCount = new AtomicInteger(0);

      CompletableFuture<Void> gate = new CompletableFuture<>();



      for (CompletableFuture<Object[]> f : futures) {

         f.whenComplete((result, ex) -> {

            doneCount.incrementAndGet();

            if (result != null) {

               successCount.incrementAndGet();

            }



            if (successCount.get() >= minRequired || doneCount.get() == futures.size()) {

               gate.complete(null);

            }

         });

      }



      long gateTimeout = Math.max(5000L, (long)requiredSize * createIntervalMs + 3000L);



      try {

         gate.get(gateTimeout, TimeUnit.MILLISECONDS);

      } catch (TimeoutException var24) {

      } catch (InterruptedException e) {

         Thread.currentThread().interrupt();

      } catch (ExecutionException var26) {

      }



      for (int i = 0; i < futures.size(); i++) {

         try {

            Object[] result = futures.get(i).getNow(null);

            if (result != null) {

               UdpHolePuncher puncher = (UdpHolePuncher)result[0];

               StunProbe.PublicMappedAddress addr = (StunProbe.PublicMappedAddress)result[1];

               if (puncher.getSocket() != null && !puncher.getSocket().isClosed() && addr != null) {

                  punchers.add(puncher);

                  addrs.add(addr);

               } else if (puncher.getSocket() != null && !puncher.getSocket().isClosed()) {

                  try {

                     puncher.close();

                  } catch (Exception var23) {

                  }

               }

            }

         } catch (Exception var28) {

         }

      }



      for (CompletableFuture<Object[]> f : futures) {

         if (!f.isDone()) {

            f.cancel(true);

         }

      }



      if (punchers.isEmpty()) {

         return null;

      }



      UdpSocketArray array = new UdpSocketArray(punchers, addrs, isEasySym);

      cm.cachedUdpArray = array;

      VoxLinkMod.LOGGER.info("[BirthdayPunch] Create new socket array: {} sockets, easySym={}", punchers.size(), isEasySym);

      return array;

   }
   static void clearActiveUdpTransports(ConnectionManager cm) {

      for (ReliableUdpTransport t : cm.activeUdpTransports.values()) {

         try {

            t.close();

         } catch (Exception e) {

            VoxLinkMod.LOGGER.debug("cleanup udp transport close error: {}", e.getMessage());

         }

      }



      cm.activeUdpTransports.clear();

   }
   static ReliableUdpTransport removeUdpTransport(ConnectionManager cm, String key) {

      return cm.activeUdpTransports.remove(key);

   }
   static boolean hasUdpTransport(ConnectionManager cm, String key) {

      return key != null && cm.activeUdpTransports.containsKey(key);

   }
   static void notifyDualVoxlinkBridge(ConnectionManager cm, boolean success) {

      CompletableFuture<Void> f = cm.dualVoxlinkBridgeFuture;

      if (f != null) {

         if (success) {

            f.complete(null);

         } else {

            f.completeExceptionally(new RuntimeException("VoxLink桥建立失败"));

         }

      }

   }
   static void connectTerracottaToMC(ConnectionManager cm, String connectUrl, String roomCode) {

      if (connectUrl != null && !connectUrl.isEmpty()) {

         int localPort = cm.parsePortFromUrl(connectUrl);

         if (localPort <= 0) {

            throw new RuntimeException("陶瓦connectUrl解析端口失败: " + connectUrl);

         }



         RoomInfo roomInfo = cm.roomManager.getCurrentRoom();

         if (roomInfo == null) {

            roomInfo = cm.roomManager.setupTerracottaGuestRoom(roomCode);

         }



         ConnectionState.transitionTo(ConnectionState.TRANSPORT_SETUP, "陶瓦guest-ok port=" + localPort);

         roomInfo.setConnectionMode(Text.translatable("voxlink.connection.bridge_setup"));

         // 陶瓦通道必须同样激活 handoff 宽限：P2P 的四条桥路径都调了，唯独这里漏了，
         // 于是 GuestOK 后 26ms 到达的幽灵 leaveRoom 能把刚建好的桥拆掉（表现为 Connection refused）。
         cm.markConnectionEstablished();

         ConnectionHelper.connectToServer(localPort, roomInfo);

         VoxLinkMod.LOGGER.info("[DualP2P] Terracotta connect MC port={}", localPort);

      } else {

         throw new RuntimeException("陶瓦connectUrl为空 无法连接MC");

      }

   }
   static void startUdpPunchBridge(ConnectionManager cm, RoomManager.RoomState state, ReliableUdpTransport transport) {

      // 自动中继换道分支：旧道验证死后新道传输到达——热切换承载而非新建桥，
      // 本地桥/MC TCP 不动，重放上行日志，MC 服务器侧看到一条完整全新握手
      if (cm.tryCarrierSwap(state, transport)) {
         cm.stopAllPunchingAfterHostBridge();
         cm.connectionCycleActive.set(false);
         return;
      }

      if (!cm.terracottaWon && !cm.voxlinkSideDisabled) {

         int localPort = P2PBridge.startUdpJoinerBridge(transport);

         if (localPort > 0) {

            if (cm.dualRaceActive) {

               cm.claimVoxlinkDualWin();

            }



            cm.stopAllPunchingAfterHostBridge();

            cm.connectionCycleActive.set(false);

            ConnectionState.transitionTo(ConnectionState.CONNECTED, "Joiner桥接建立 port=" + localPort);

            cm.markConnectionEstablished();

            state.roomInfo.setConnectionMode(Text.translatable("voxlink.connection.bridge_setup"));

            ConnectionHelper.connectToServer(localPort, state.roomInfo);

            // 中继模式：起连接期上行日志（换道重放用）+ 25s 真连接验证窗
            if (state.roomInfo.isUsingRelay() || cm.isTurnActive()) {
               P2PBridge.startJoinerJournal();
               cm.armRelayVerifyWatchdog(state, cm.isTurnActive());
            }

            cm.notifyDualVoxlinkBridge(true);

         } else {

            cm.connectionCycleActive.set(false);

            ConnectionHelper.resetConnecting();

            ConnectionState.transitionTo(ConnectionState.FAILED, "桥接启动失败");

            state.roomInfo.setConnectionMode(Text.translatable("voxlink.connection.bridge_start_failed"), true);

            cm.sendDisconnectOnFailure(state);

            cm.notifyDualVoxlinkBridge(false);

            if (!cm.dualRaceActive) {

               cm.leaveRoomOnFailure(state);

            }

         }

      } else {

         try {

            transport.close();

         } catch (Exception var6) {

         }



         cm.notifyDualVoxlinkBridge(false);

      }

   }
   static void startHostUdpPunchBridge(ConnectionManager cm, RoomManager.RoomState state, String clientId, ReliableUdpTransport transport) {

      int mcPort = state.roomInfo.getHostPort();

      // 幽灵连接防护：punch成功只证明"对端→本端"单向可达(host收到joiner的PUNCH不代表host发出的包能到joiner，
      // 实锤案例recvAck=0挂假连接61秒)。此处不再立即宣布CONNECTED：
      // - 若此前已收到该client的"connected"就绪信令(双向证据)，立即补转CONNECTED；
      // - 否则保持TRANSPORT_SETUP与等待文案，等RoomManager.handleConnected收到就绪信令后再确认。
      // 兜底：若30s内桥上无首包且未确认，P2PBridge首包watchdog会自动requestIceRestart重打。

      boolean peerReadyBeforeBridge = clientId != null && cm.peerReadyConfirmations.remove(clientId);

      if (!peerReadyBeforeBridge && ConnectionState.getCurrent() != ConnectionState.CONNECTED) {

         state.roomInfo.setConnectionMode(Text.translatable("voxlink.connection.await_peer_ready"));

      }

      cm.stopAllPunchingAfterHostBridge();

      P2PBridge.startUdpHostBridgeForClient(clientId, transport, mcPort, () -> {

         ReliableUdpTransport t = cm.activeUdpTransports.remove(clientId);

         if (t != null) {

            try {

               t.close();

            } catch (Exception var5) {

            }

         }



         if (cm.roomManager.currentRoom.get() == state && state != RoomManager.PENDING) {

            VoxLinkMod.LOGGER.warn("[HostBridge] client={} bridge disconnected, reset connectionWon for host re-punch", clientId);

            LogUploadManager.onDisconnected();

            // 断的是 TURN 桥：立即释放 TURN 会话。否则 turnSession 残留会静默吞掉
            // 该房客后续重试的 turn_alloc，中继在本房间内永久失效
            if ((cm.turnSession != null || cm.stdTurnSession != null) && clientId.equals(cm.turnPeerId)) {

               VoxLinkMod.LOGGER.info("[Turn] host bridge down for {}, releasing TURN session", clientId);

               cm.teardownTurn(state, null);

            }

            cm.connectionWon.set(false);
            cm.connectionCycleActive.set(false);
            cm.hostPunchContexts.remove(clientId);
            ConnectionState.transitionTo(ConnectionState.IDLE, "Host桥断开 client=" + clientId);

            state.roomInfo.setConnectionMode(Text.translatable("voxlink.connection.punching"));

         }

      });

      // 桥已起、且joiner就绪信令先到：现在补转CONNECTED（双向证据齐备）

      if (peerReadyBeforeBridge) {

         cm.finishHostBridgeConnect(state, clientId);

      }

   }
   static void onPeerBridgeReady(ConnectionManager cm, RoomManager.RoomState state, String from) {

      if (state == null || state == RoomManager.PENDING || !state.roomInfo.isHost() || from == null) {

         return;

      }

      if (cm.activeUdpTransports.containsKey(from) || P2PBridge.hasActiveBridgePairs()) {

         cm.finishHostBridgeConnect(state, from);

      } else {

         // 就绪信令先于host桥建立到达：暂存，待startHostUdpPunchBridge时消费

         cm.peerReadyConfirmations.add(from);

         VoxLinkMod.LOGGER.info("[RoomManager] Peer {} ready signal arrived before host bridge, defer confirm", from);

      }

   }
   static void finishHostBridgeConnect(ConnectionManager cm, RoomManager.RoomState state, String clientId) {

      if (state == null || state == RoomManager.PENDING || clientId == null) {

         return;

      }

      if (cm.roomManager.currentRoom.get() != state
         || !(cm.activeUdpTransports.containsKey(clientId) || P2PBridge.hasActiveBridgePairs())) {

         VoxLinkMod.LOGGER.warn("[ConnState] Skip host CONNECTED confirm client={} (room/transport changed)", clientId);

         return;

      }

      if (ConnectionState.getCurrent() != ConnectionState.CONNECTED) {

         ConnectionState.transitionTo(ConnectionState.CONNECTED, "Host桥接就绪(对端就绪确认) client=" + clientId);

         state.roomInfo.setConnectionMode(Text.translatable("voxlink.connection.connected"));

      }

   }
   static void putTransportWithIcePool(ConnectionManager cm, String key, ReliableUdpTransport transport) {
      ReliableUdpTransport old = cm.activeUdpTransports.put(key, transport);

      if (old != null) {

         String oldKey = key + "_old";

         cm.oldUdpTransports.put(oldKey, old);

         cm.scheduler.schedule(() -> {

            ReliableUdpTransport t = cm.oldUdpTransports.remove(oldKey);

            if (t != null) {

               try {

                  t.close();

               } catch (Exception var4x) {

               }

            }

         }, 12L, TimeUnit.SECONDS);

      }

   }
   static void sendConfirmPackets(ConnectionManager cm, DatagramSocket socket, InetSocketAddress addr) {

      try {

         byte[] data = new byte[]{86, 76, 2};

         DatagramPacket pkt = new DatagramPacket(data, data.length, addr.getAddress(), addr.getPort());

         socket.send(pkt);

         socket.send(pkt);

         socket.send(pkt);

      } catch (Exception e) {

         VoxLinkMod.LOGGER.warn("[Transport] Ack packet send failed: {}", e.getMessage());

      }

   }
   static void establishUdpTransport(ConnectionManager cm, 

      RoomManager.RoomState state,

      DatagramSocket socket,

      UdpHolePuncher puncher,

      InetSocketAddress fallbackAddr,

      String transportKey,

      boolean isHost,

      String clientId

   ) {

      puncher.waitForRecvThreadExit();

      InetSocketAddress remoteAddr = puncher.getActualRemoteAddress();

      if (remoteAddr == null) {

         remoteAddr = fallbackAddr;

      }



      cm.sendConfirmPackets(socket, remoteAddr);

      ReliableUdpTransport transport = new ReliableUdpTransport(socket, remoteAddr);

      transport.setOnIceRestartRequested(cm::triggerIceRestart);

      // punchAuthV1：直连数据面与打洞控制面使用同一派生密钥（双方声明能力时启用）

      byte[] punchAuthKey = cm.derivePunchAuthKey(state, isHost ? clientId : null, !isHost);

      if (punchAuthKey != null) {

         transport.setAuthKey(punchAuthKey);

         VoxLinkMod.LOGGER.info("[PunchAuth] Transport auth enabled ({})", isHost ? "host side" : "joiner side");

      }

      // relayMuxV1（玩家中继 v2 数据面）：双方都声明该能力时传输进入 mux 模式，
      // 之后该传输可承载中继子通道（chan 1..7）与本端自有会话（chan 0）字节级隔离。
      // 必须在 start() 前启用（mux 改变线上载荷格式，不允许中途切换）。
      if (ProtocolNegotiator.selfSupports(ProtocolNegotiator.CAP_RELAY_MUX_V1)) {
         boolean peerMux = isHost
            ? cm.peerSupportsCapability(state, clientId, ProtocolNegotiator.CAP_RELAY_MUX_V1)
            : ProtocolNegotiator.hostSupportsRelayMux(state.roomInfo);
         if (peerMux) {
            transport.enableMux();
            VoxLinkMod.LOGGER.info("[RelayMux] transport '{}' mux enabled ({} side)", transportKey, isHost ? "host" : "joiner");
         }
      }



      cm.putTransportWithIcePool(transportKey, transport);

      // 连接成功事件: 稳定(2分钟)后不上传; 窗口内掉线仍会上传(失败诊断)

      icu.wuhui.voxlink.ui.UiLogBus.push(1, "voxlink.logui.success");
      LogUploadManager.onConnected();

      if (!isHost) {

         cm.connectionCycleActive.set(false);

         ConnectionHelper.resetConnecting();

      }



      transport.start();

      if (isHost) {

         ConnectionState.transitionTo(ConnectionState.TRANSPORT_SETUP, "Host ReliableUdp启动 client=" + clientId);

         cm.startHostUdpPunchBridge(state, clientId, transport);

      } else {

         ConnectionState.transitionTo(ConnectionState.TRANSPORT_SETUP, "Joiner ReliableUdp启动");

         cm.signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), false, "connected", new JsonObject(), "host");

         cm.startUdpPunchBridge(state, transport);

      }



      cm.startVoiceForwardBridge(transport, isHost);

   }
   static void startVoiceForwardBridge(ConnectionManager cm, ReliableUdpTransport transport, boolean isHost) {

      try {

         // host 侧本机语音/服务器已占端口，用出站中继；joiner 侧绑端口收本地客户端

         UdpForwardBridge bridge = new UdpForwardBridge(transport, isHost, 24454, 25565);

         if (bridge.isActive()) {

            cm.udpForwardBridges.add(bridge);

         } else {

            bridge.close();

         }

      } catch (Exception e) {

         VoxLinkMod.LOGGER.warn("[UdpForward] voice relay failed to start: {}", e.getMessage());

      }

   }
   static void closeVoiceForwardBridges(ConnectionManager cm) {

      for (UdpForwardBridge b : cm.udpForwardBridges) {

         try {

            b.close();

         } catch (Exception e) {

         }

      }

      cm.udpForwardBridges.clear();

   }
}
