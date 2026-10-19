package icu.wuhui.voxlink.room;

import com.google.gson.JsonObject;
import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.network.NatClass;
import icu.wuhui.voxlink.network.ConnectionHelper;
import icu.wuhui.voxlink.platform.Text;
import icu.wuhui.voxlink.platform.ChatSink;
import java.util.List;
import java.net.InetSocketAddress;
import java.io.IOException;
import java.io.OutputStream;
import icu.wuhui.voxlink.network.PunchProfile;
import icu.wuhui.voxlink.network.ReliableUdpTransport;
import icu.wuhui.voxlink.network.P2PBridge;
import icu.wuhui.voxlink.network.TcpHolePuncher;
import icu.wuhui.voxlink.network.RelayBridge;
import icu.wuhui.voxlink.network.UdpPath;
import icu.wuhui.voxlink.network.StunProbe;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.net.Socket;
import java.util.concurrent.TimeUnit;

/** TCP 打洞支线: SimOpen/TCP 握手与建桥。(Phase4 从 ConnectionManager 拆出) */
public final class TcpPunchEngine {
   private TcpPunchEngine() {
   }

   static void handleTcpPunchInfo(ConnectionManager cm, String from, JsonObject data) {

      RoomManager.RoomState state = cm.roomManager.currentRoom.get();

      if (state == null || state == RoomManager.PENDING || !state.roomInfo.isHost()) {

         return;

      }



      if (cm.connectionWon.get() || cm.localNatClass != NatClass.CONE) {

         VoxLinkMod.LOGGER

            .info("[TcpPunch] host skip tcp_punch_info (won={}, nat={})", new Object[]{cm.connectionWon.get(), cm.localNatClass});

         return;

      }



      int port = data.has("tcpPunchPort") ? data.get("tcpPunchPort").getAsInt() : 0;

      String joinerIp = data.has("tcpPunchIp") ? data.get("tcpPunchIp").getAsString() : null;

      if (port <= 0 || port > 65535 || joinerIp == null || joinerIp.isEmpty()) {

         return;

      }



      JsonObject go = new JsonObject();

      go.addProperty("tcpPunchPort", port);

      cm.signalingClient

         .sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), true, "tcp_punch_go", go, from)

         .exceptionally(e -> {

            VoxLinkMod.LOGGER.info("[TcpPunch] tcp_punch_go send failed: {}", e.getMessage());

            return null;

         });



      TcpHolePuncher puncher = new TcpHolePuncher();

      CompletableFuture<Socket> f = puncher.punchAsync(joinerIp, port, 5, cm.connectionWon);

      if (f != null) {

         f.whenComplete((socket, ex) -> cm.onTcpPunchSocket(state, socket, true));

      }



      VoxLinkMod.LOGGER.info("[TcpPunch] host go: connect {}:{} (10s window)", new Object[]{joinerIp, port});

   }
   static void handleTcpPunchGo(ConnectionManager cm, String from, JsonObject data) {

      RoomManager.RoomState state = cm.roomManager.currentRoom.get();

      if (state == null || state == RoomManager.PENDING || state.roomInfo.isHost()) {

         return;

      }



      if (cm.connectionWon.get()) {

         cm.tcpPunchInFlight.set(false);

         return;

      }



      int port = data.has("tcpPunchPort") ? data.get("tcpPunchPort").getAsInt() : 0;

      String targetIp = cm.savedConnectionHostMappedIp;

      if (port <= 0 || port > 65535 || targetIp == null || targetIp.isEmpty()) {

         cm.tcpPunchInFlight.set(false);

         return;

      }



      TcpHolePuncher puncher = new TcpHolePuncher();

      CompletableFuture<Socket> f = puncher.punchAsync(targetIp, port, 1, cm.connectionWon);

      if (f == null) {

         cm.tcpPunchInFlight.set(false);

         return;

      }



      f.whenComplete((socket, ex) -> {

         cm.tcpPunchInFlight.set(false);

         cm.onTcpPunchSocket(state, socket, false);

      });

      VoxLinkMod.LOGGER.info("[TcpPunch] joiner go: connect {}:{} then listen", new Object[]{targetIp, port});

   }
   static void tryTcpHolePunch(ConnectionManager cm, RoomManager.RoomState state, String hostMappedIp, int cycle) {

      if (cycle < 1 || cm.connectionWon.get() || state.roomInfo.isHost() || cm.localNatClass != NatClass.CONE) {

         return;

      }



      if (hostMappedIp == null || hostMappedIp.isEmpty() || cm.signalingClient == null) {

         return;

      }



      String myIp = state.roomInfo.getMyMappedIp();

      if (myIp == null || myIp.isEmpty()) {

         return;

      }



      if (!cm.tcpPunchInFlight.compareAndSet(false, true)) {

         return;

      }



      int punchPort = TcpHolePuncher.selectPunchPort();

      if (punchPort <= 0) {

         cm.tcpPunchInFlight.set(false);

         return;

      }



      JsonObject info = new JsonObject();

      info.addProperty("tcpPunchIp", myIp);

      info.addProperty("tcpPunchPort", punchPort);

      // go 丢失看门狗: 释放占位, 下轮继续邀约

      cm.scheduler.schedule(() -> cm.tcpPunchInFlight.set(false), 35L, TimeUnit.SECONDS);

      cm.signalingClient

         .sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), false, "tcp_punch_info", info, "host")

         .exceptionally(e -> {

            VoxLinkMod.LOGGER.info("[TcpPunch] tcp_punch_info send failed: {}", e.getMessage());

            return null;

         });

      VoxLinkMod.LOGGER.info("[TcpPunch] offer port {} to host {} (cycle {})", new Object[]{punchPort, hostMappedIp, cycle});

   }
   static void onTcpPunchSocket(ConnectionManager cm, RoomManager.RoomState state, Socket socket, boolean isHost) {

      if (socket == null || cm.roomManager.currentRoom.get() != state) {

         if (socket != null) {

            try {

               socket.close();

            } catch (Exception ignored) {

            }

         }



         return;

      }



      if (!cm.connectionWon.compareAndSet(false, true)) {

         // 失败不拉黑, 静默等下轮

         try {

            socket.close();

         } catch (Exception ignored) {

         }



         return;

      }



      if (isHost) {

         int mcPort = state.roomInfo.getHostPort();

         if (P2PBridge.bridgePunchedHostSocket(socket, mcPort)) {

            VoxLinkMod.LOGGER.info("[TcpPunch] host bridged punched socket to MC port {}", mcPort);

         } else {

            cm.connectionWon.set(false);

         }

      } else {

         cm.connectViaPunchedTcp(state, socket);

      }

   }
   static void connectViaPunchedTcp(ConnectionManager cm, RoomManager.RoomState state, Socket punched) {

      cm.killAllConnectionAttempts();

      P2PBridge.cancelPendingUdpTimeouts();

      for (ReliableUdpTransport transport : cm.activeUdpTransports.values()) {

         try {

            transport.close();

         } catch (Exception ignored) {

         }

      }



      cm.activeUdpTransports.clear();

      cm.teardownTurn(state, null);

      P2PBridge.connectToHostPreconnected(punched, cm.savedConnectionHostMappedIp, cm.savedConnectionHostPort).thenAccept(localPort -> {

         if (localPort > 0) {

            if (cm.dualRaceActive) {

               cm.claimVoxlinkDualWin();

            }



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
