package icu.wuhui.voxlink.room;

import com.google.gson.JsonObject;
import icu.wuhui.voxlink.network.PunchAuth;
import icu.wuhui.voxlink.network.UdpHolePuncher;
import icu.wuhui.voxlink.network.AddressBlacklist;
import java.util.ArrayList;
import icu.wuhui.voxlink.network.UdpPath;
import icu.wuhui.voxlink.network.P2PBridge;
import icu.wuhui.voxlink.network.RelayBridge;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import icu.wuhui.voxlink.network.StunProbe;

import icu.wuhui.voxlink.VoxLinkMod;

import icu.wuhui.voxlink.network.NatClass;

import icu.wuhui.voxlink.platform.Text;

import icu.wuhui.voxlink.platform.ChatSink;

import java.util.List;

import java.util.concurrent.TimeUnit;

import icu.wuhui.voxlink.network.StdTurnClient;

import icu.wuhui.voxlink.network.TurnRelayClient;

import icu.wuhui.voxlink.network.ReliableUdpTransport;

import icu.wuhui.voxlink.network.SignalingClient;

import icu.wuhui.voxlink.network.ProtocolNegotiator;

import icu.wuhui.voxlink.platform.ClientThread;

import java.util.concurrent.ScheduledFuture;

import java.util.concurrent.CompletableFuture;

import java.util.concurrent.atomic.AtomicBoolean;

/** TURN 会话生命周期: alloc/ready/nack、看门狗、keepalive、hotstandby、ICE restart。(Phase4 从 ConnectionManager 拆出) */
public final class TurnCarrier {
   private TurnCarrier() {
   }

   static CompletableFuture<Boolean> startStdTurnGuestFlow(ConnectionManager cm, RoomManager.RoomState state, SignalingClient sc, TurnRelayClient.NodeInfo node) {
      icu.wuhui.voxlink.ui.UiLogBus.push(0, "voxlink.logui.turn_allocating");
      return CompletableFuture.supplyAsync(() -> {
         final int turnEpoch = cm.turnSessionEpoch.get();
         StdTurnClient.StdCred cred = StdTurnClient
            .fetchCred(sc, state.roomInfo.getCode(), state.roomInfo.getClientId(), state.roomInfo.getToken(), node.id)
            .join();
         if (cred == null) {
            throw new IllegalStateException("STD_CRED_FAILED");
         }

         // 竞态二次检查：列表+探测+cred 耗费数秒，期间直连可能已打通
         if (cm.connectionWon.get() && cm.stdTurnSession == null) {
            throw new IllegalStateException("P2P_WON");
         }

         StdTurnClient.StdTurnSession session = StdTurnClient.allocate(cred.host, cred.port, cred.username, cred.password, 8000);
         if (session == null) {
            throw new IllegalStateException("STD_ALLOC_FAILED");
         }

         // 写回屏障：35s 总超时的 teardown 先到时中止（对齐自定义协议 1.1.5 屏障语义）
         if (!cm.turnInProgress || turnEpoch != cm.turnSessionEpoch.get()) {
            StdTurnClient.close(session);
            VoxLinkMod.LOGGER.warn("[Turn] stdTurn guest flow cancelled during allocate (timeout teardown won), discard session");
            throw new IllegalStateException("TURN_CANCELLED");
         }

         cm.stdTurnSession = session;
         startStdTurnKeepalive(cm, session);
         cm.turnPeerId = "host";
         VoxLinkMod.LOGGER.info("[Turn] stdTurn guest allocated relay={} via {}:{}, sending turn_alloc", session.relayAddr, cred.host, cred.port);
         return session;
      }, ConnectionManager.TURN_BG_EXECUTOR).thenCompose(session -> {
         JsonObject data = new JsonObject();
         data.addProperty("stdTurn", true);
         data.addProperty("nodeId", node.id);
         data.addProperty("nodeHost", node.host);
         data.addProperty("stdTurnPort", node.stdTurnPort);
         data.addProperty("relayHost", session.relayAddr.getAddress().getHostAddress());
         data.addProperty("relayPort", session.relayAddr.getPort());
         data.addProperty("clientId", state.roomInfo.getClientId());
         data.addProperty("punchAuth", ProtocolNegotiator.selfSupports(ProtocolNegotiator.CAP_PUNCH_AUTH_V1));
         return sc
            .sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), false, "turn_alloc", data, "host")
            .thenApply(r -> Boolean.TRUE);
      });
   }
   static void handleTurnAlloc(ConnectionManager cm, String from, JsonObject data) {
      RoomManager.RoomState state = cm.roomManager.currentRoom.get();
      if (state == null || state == RoomManager.PENDING || !state.roomInfo.isHost()) {
         return;
      }

      // 标准 TURN 分支（guest 声明 stdTurn）：RFC 5766 流程，独立处理
      if (data.has("stdTurn") && !data.get("stdTurn").isJsonNull() && data.get("stdTurn").getAsBoolean()) {
         handleStdTurnAlloc(cm, state, from, data);
         return;
      }

      // 边缘情况：guest 点中继期间直连恰好打通——host 桥已按直连建立，
      // 此时再建 TURN 桥会同 clientId 双桥冲突，静默忽略（guest 兜底超时自动释放会话）
      if (cm.connectionWon.get() && !cm.isTurnActive()) {
         VoxLinkMod.LOGGER.info("[Turn] host already connected directly, ignore turn_alloc from {}", from);
         cm.sendTurnNack(state, from, "direct_won");
         return;
      }

      if (cm.turnSession != null || !data.has("sessionId") || !data.has("ticket")) {
         return;
      }

      String sessionIdHex = data.get("sessionId").getAsString();
      String host = data.has("host") ? data.get("host").getAsString() : "";
      int port = data.has("port") ? data.get("port").getAsInt() : 0;
      String ticket = data.get("ticket").getAsString();
      long expire = data.has("expire") ? data.get("expire").getAsLong() : 0L;
      if (sessionIdHex.length() != 32 || host.isEmpty() || port <= 0) {
         cm.sendTurnNack(state, from, "bad_alloc");
         return;
      }

      byte[] sid = new byte[16];

      // 1.1.5 TURN 可靠性: bind(3轮×5发, 弱网最长~16s)移出信令分发线程——
      // 单线程 scheduler 被阻塞期间房主收不到任何信令(其他房客 join/disconnect 全排队, 审计 P1-2)
      ConnectionManager.TURN_BG_EXECUTOR.execute(() -> {
          for (int i = 0; i < 16; i++) {
             sid[i] = (byte)Integer.parseInt(sessionIdHex.substring(i * 2, i * 2 + 2), 16);
          }

          TurnRelayClient.TurnSession session =
             new TurnRelayClient.TurnSession(sessionIdHex, sid, host, port, TurnRelayClient.ROLE_HOST, ticket, expire);
          final int allocEpoch = cm.turnSessionEpoch.get();
                // 3 轮重试(每轮5发, 总预算~16s < guest 的 20s turn_ready 等待):
          // 实证 09-11 23:28 host 单轮 5 发无一到达 turn01, guest 同会话一次即中——纯弱网丢包
          int code = TurnRelayClient.bindWithRetry(session, 3);
          // code=4 ROLE_CONFLICT: 首发bind已被节点接受但响应丢失, 重放被判冲突——会话已绑定, 视为成功
          if (code == TurnRelayClient.BIND_ROLE_CONFLICT) {
             code = TurnRelayClient.BIND_OK;
             VoxLinkMod.LOGGER.info("[Turn] host bind ROLE_CONFLICT (first bind accepted, response lost), proceeding");
          }
          if (code != TurnRelayClient.BIND_OK) {
             VoxLinkMod.LOGGER.warn("[Turn] host bind failed code={} sid={} endpoint={}:{}", code, sessionIdHex.substring(0, 8), host, port);
             cm.sendTurnNack(state, from, "bind_failed");
             session.unbind();
             return;
          }

          // 写回屏障: bind 异步窗口内 teardown/清理可能先到(ICE restart/对端取消/退房), 防孤儿复活
          if (allocEpoch != cm.turnSessionEpoch.get() || cm.turnSession != null
             || cm.roomManager.currentRoom.get() != state) {
             session.unbind();
             VoxLinkMod.LOGGER.warn("[Turn] host alloc stale (teardown/room changed during bind), discard orphan session");
             return;
          }

          cm.turnSession = session;
          cm.turnPeerId = from;
          startTurnKeepalive(cm, session);
          UdpPath.Codec codec = new TurnRelayClient.TurnPathCodec(sid, TurnRelayClient.ROLE_HOST, TurnRelayClient.ROLE_GUEST);
          ReliableUdpTransport transport = new ReliableUdpTransport(session.socket, session.endpoint(), codec);
          // TURN 路径密钥现场重派生, 不沿用 activePunchAuthKey:
          // ①打洞阶段密钥可能陈旧(重进房/join_request 被 defer 未重派);
          // ②turn_alloc 携带 joiner 当前权威 clientId 与显式 punchAuth 声明,
          //   据此派生与 guest 侧对称一致(实证互丢 bug: RBW6HX 1.1.4)
          String turnPeerId = data.has("clientId") ? data.get("clientId").getAsString() : null;
          byte[] turnAuthKey;
          if (data.has("punchAuth") && !data.get("punchAuth").isJsonNull() && data.get("punchAuth").getAsBoolean()) {
             turnAuthKey = turnPeerId != null && !turnPeerId.isEmpty() && state.roomInfo.getCode() != null
                ? PunchAuth.deriveDirectKey(state.roomInfo.getCode(), turnPeerId)
                : null;
          } else {
             // 旧版 guest 未声明: 回退 peer 表判定(保持旧行为)
             turnAuthKey = cm.derivePunchAuthKey(state, turnPeerId, false);
          }
          if (turnAuthKey != null) {
             transport.setAuthKey(turnAuthKey);
             cm.activePunchAuthKey = turnAuthKey;
             VoxLinkMod.LOGGER.info("[PunchAuth] TURN transport auth enabled (host side, peer {})", turnPeerId);
          } else {
             VoxLinkMod.LOGGER.info("[PunchAuth] TURN transport without auth (peer {} legacy/caps missing)", turnPeerId);
          }
          // 互操作降级开关: 旧引擎 guest(桌面 App/旧版 mod)无密钥时连续认证失败自动回明文
          // (实证 09-11 23:03: host armed 而对端明文, path up 同秒互丢, 19s 后桥死)
          transport.allowAuthDowngradeForInterop();
          // TURN载具补接ICE重启回调
          transport.setOnIceRestartRequested(cm::triggerIceRestart);

          cm.turnTransport = transport;
          transport.start();
          cm.activeUdpTransports.put(from, transport);
          cm.startHostUdpPunchBridge(state, from, transport);

          JsonObject ready = new JsonObject();
          ready.addProperty("clientId", state.roomInfo.getClientId());
          cm.signalingClient
             .sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), true, "turn_ready", ready, from)
             .exceptionally(e -> {
                // turn_ready 发不出去 guest 必然 20s 兜底 teardown：必须 WARN 可见（曾因白名单缺失被 debug 吞掉）
                VoxLinkMod.LOGGER.warn("[Turn] turn_ready send failed: {}", e.getMessage());
                return null;
             });
             // 投递保险 v2: 5s×12 有界重发, 回执即停(单次补发盖不住 30s 级限流退避)
             scheduleTurnReadyResend(cm, state, from, ready, session, false, System.currentTimeMillis(), 0);
          startTurnBgMonitor(cm, state);
          VoxLinkMod.LOGGER.info("[Turn] host path up via {}:{} (peer={})", host, port, from);
          icu.wuhui.voxlink.ui.UiLogBus.push(1, "voxlink.logui.success");
      });
   }
   static void handleStdTurnAlloc(ConnectionManager cm, RoomManager.RoomState state, String from, JsonObject data) {
      // 边缘情况：guest 点中继期间直连恰好打通——同自定义协议语义，静默忽略+nack
      if (cm.connectionWon.get() && !cm.isTurnActive()) {
         VoxLinkMod.LOGGER.info("[Turn] host already connected directly, ignore stdTurn turn_alloc from {}", from);
         cm.sendTurnNack(state, from, "direct_won");
         return;
      }
      if (cm.turnSession != null || cm.stdTurnSession != null) {
         return;
      }
      if (!data.has("relayHost") || !data.has("relayPort") || !data.has("nodeId") || !data.has("nodeHost") || !data.has("stdTurnPort")) {
         cm.sendTurnNack(state, from, "bad_alloc");
         return;
      }
      String guestRelayHost = data.get("relayHost").getAsString();
      int guestRelayPort = data.get("relayPort").getAsInt();
      String nodeId = data.get("nodeId").getAsString();
      int stdTurnPort = data.get("stdTurnPort").getAsInt();
      if (guestRelayHost.isEmpty() || guestRelayPort <= 0 || nodeId.isEmpty() || stdTurnPort <= 0) {
         cm.sendTurnNack(state, from, "bad_alloc");
         return;
      }

      // cred+allocate+ChannelBind 全是同步网络事务：必须离开信令分发线程（审计 P1-2）
      ConnectionManager.TURN_BG_EXECUTOR.execute(() -> {
         final int allocEpoch = cm.turnSessionEpoch.get();
         StdTurnClient.StdCred cred;
         try {
            cred = StdTurnClient
               .fetchCred(cm.signalingClient, state.roomInfo.getCode(), state.roomInfo.getClientId(), state.roomInfo.getToken(), nodeId)
               .join();
         } catch (Exception e) {
            VoxLinkMod.LOGGER.warn("[Turn] stdTurn host cred fetch (node={}) exception: {}", nodeId, String.valueOf(e));
            cred = null;
         }
         if (cred == null) {
            VoxLinkMod.LOGGER.warn("[Turn] stdTurn host cred fetch failed (node={})", nodeId);
            cm.sendTurnNack(state, from, "std_cred_failed");
            return;
         }
         StdTurnClient.StdTurnSession session = StdTurnClient.allocate(cred.host, cred.port, cred.username, cred.password, 8000);
         if (session == null) {
            VoxLinkMod.LOGGER.warn("[Turn] stdTurn host allocate failed via {}:{}", cred.host, cred.port);
            cm.sendTurnNack(state, from, "std_alloc_failed");
            return;
         }
         InetSocketAddress peer = new InetSocketAddress(guestRelayHost, guestRelayPort);
         if (peer.getAddress() == null) {
            // 不可解析地址会让 putXorAddress NPE 越过 IOException-only catch 杀掉池线程
            VoxLinkMod.LOGGER.warn("[Turn] stdTurn host guest relay host unresolvable: {}", guestRelayHost);
            StdTurnClient.close(session);
            cm.sendTurnNack(state, from, "std_bind_failed");
            return;
         }
         if (!StdTurnClient.channelBind(session, peer, StdTurnClient.CHANNEL_BASE, 8000)) {
            VoxLinkMod.LOGGER.warn("[Turn] stdTurn host ChannelBind to guest relay {} failed", peer);
            StdTurnClient.close(session);
            cm.sendTurnNack(state, from, "std_bind_failed");
            return;
         }
         // 竞态写回屏障：异步期间可能已直连获胜/会话被拆/teardown 先到(置null后旧检查查不出)
         if (cm.turnSession != null || cm.stdTurnSession != null
            || allocEpoch != cm.turnSessionEpoch.get()
            || cm.roomManager.currentRoom.get() != state) {
            StdTurnClient.close(session);
            return;
         }

         cm.stdTurnSession = session;
         cm.turnPeerId = from;
         startStdTurnKeepalive(cm, session);
         UdpPath.Codec codec = new StdTurnClient.StdTurnPathCodec(StdTurnClient.CHANNEL_BASE);
         ReliableUdpTransport transport = new ReliableUdpTransport(session.socket, session.endpoint(), codec);
         // TURN 路径密钥派生：与自定义协议 handleTurnAlloc 完全同口径
         // （turn_alloc 携带 joiner 当前权威 clientId 与显式 punchAuth 声明）
         String turnPeerId = data.has("clientId") ? data.get("clientId").getAsString() : null;
         byte[] turnAuthKey;
         if (data.has("punchAuth") && !data.get("punchAuth").isJsonNull() && data.get("punchAuth").getAsBoolean()) {
            turnAuthKey = turnPeerId != null && !turnPeerId.isEmpty() && state.roomInfo.getCode() != null
               ? PunchAuth.deriveDirectKey(state.roomInfo.getCode(), turnPeerId)
               : null;
         } else {
            turnAuthKey = cm.derivePunchAuthKey(state, turnPeerId, false);
         }
         if (turnAuthKey != null) {
            transport.setAuthKey(turnAuthKey);
            cm.activePunchAuthKey = turnAuthKey;
            VoxLinkMod.LOGGER.info("[PunchAuth] stdTurn transport auth enabled (host side, peer {})", turnPeerId);
         } else {
            VoxLinkMod.LOGGER.info("[PunchAuth] stdTurn transport without auth (peer {} legacy/caps missing)", turnPeerId);
         }
         transport.allowAuthDowngradeForInterop();
         // TURN载具补接ICE重启回调
         transport.setOnIceRestartRequested(cm::triggerIceRestart);

         cm.turnTransport = transport;
         transport.start();
         cm.activeUdpTransports.put(from, transport);
         cm.startHostUdpPunchBridge(state, from, transport);

         JsonObject ready = new JsonObject();
         ready.addProperty("stdTurn", true);
         ready.addProperty("relayHost", session.relayAddr.getAddress().getHostAddress());
         ready.addProperty("relayPort", session.relayAddr.getPort());
         ready.addProperty("clientId", state.roomInfo.getClientId());
         cm.signalingClient
            .sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), true, "turn_ready", ready, from)
            .exceptionally(e -> {
               VoxLinkMod.LOGGER.warn("[Turn] stdTurn turn_ready send failed: {}", e.getMessage());
               return null;
            });
         // 投递保险 v2: 5s×12 有界重发, 回执即停
         scheduleTurnReadyResend(cm, state, from, ready, session, true, System.currentTimeMillis(), 0);
         startTurnBgMonitor(cm, state);
         VoxLinkMod.LOGGER.info("[Turn] stdTurn host path up (channel 0x4000 ↔ {}, peer={})", peer, from);
         icu.wuhui.voxlink.ui.UiLogBus.push(1, "voxlink.logui.success");
      });
   }
   static void scheduleTurnReadyResend(ConnectionManager cm, 
      RoomManager.RoomState state, String from, JsonObject ready, Object sessionRef, boolean stdTurn, long pathUpMs, int round
   ) {
      cm.scheduler.schedule(() -> {
         try {
            boolean sessionCurrent = stdTurn
               ? cm.stdTurnSession == sessionRef && cm.turnPeerId != null && cm.turnPeerId.equals(from)
               : cm.turnSession == sessionRef && cm.turnPeerId == from;
            if (!sessionCurrent) {
               return;
            }
            ReliableUdpTransport t = cm.turnTransport;
            if (t != null && t.getLastRecvTime() > pathUpMs) {
               return;
            }
            if (round >= 12) {
               VoxLinkMod.LOGGER.warn("[Turn] turn_ready insurance exhausted ({} resends/60s), stop", round);
               return;
            }
            cm.signalingClient
               .sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), true, "turn_ready", ready, from)
               .exceptionally(e -> {
                  VoxLinkMod.LOGGER.debug("[Turn] turn_ready resend failed: {}", e.getMessage());
                  return null;
               });
            VoxLinkMod.LOGGER.info("[Turn] turn_ready resent {}/12 (delivery insurance)", round + 1);
            scheduleTurnReadyResend(cm, state, from, ready, sessionRef, stdTurn, pathUpMs, round + 1);
         } catch (Exception e) {
            VoxLinkMod.LOGGER.debug("[Turn] turn_ready insurance error: {}", e.getMessage());
         }
      }, 5L, java.util.concurrent.TimeUnit.SECONDS);
   }
   static void turnReadyWatchdog(ConnectionManager cm, RoomManager.RoomState state, int round) {
      boolean noReady = !cm.turnReadyApplied.get()
         && (cm.turnTransport == null || !cm.turnTransport.isConnected());
      if (!noReady || cm.turnSwitchedToP2p || cm.roomManager.currentRoom.get() != state) {
         return;
      }
      long waited = round == 0 ? 20L : 25L + (round - 1) * 20L;
      if (round == 0) {
         VoxLinkMod.LOGGER.warn("[Turn] no turn_ready in 20s, force catch-up poll, recheck in 5s");
         cm.roomManager.catchUpSignalOnce();
         cm.scheduler.schedule(() -> turnReadyWatchdog(cm, state, 1), 5L, TimeUnit.SECONDS);
         return;
      }
      boolean signalUnhealthy = !cm.signalingClient.isWsConnected()
         || cm.roomManager.isSignalingDegraded()
         || cm.roomManager.isSignalPollBackedOff();
      if (signalUnhealthy && round < 3) {
         VoxLinkMod.LOGGER.warn("[Turn] still no turn_ready at {}s, signal degraded, extend wait", waited);
         cm.roomManager.catchUpSignalOnce();
         cm.scheduler.schedule(() -> turnReadyWatchdog(cm, state, round + 1), 20L, TimeUnit.SECONDS);
         return;
      }
      VoxLinkMod.LOGGER.warn("[Turn] guest got no turn_ready in {}s (host dead / signal lost), teardown", waited);
      icu.wuhui.voxlink.ui.UiLogBus.push(2, "voxlink.logui.turn_no_ready");
      teardownTurn(cm, state, "voxlink.turn.failed");
   }
   static void startTurnKeepalive(ConnectionManager cm, TurnRelayClient.TurnSession session) {
      cancelTurnKeepalive(cm);
      cm.turnKeepaliveTask = cm.scheduler.scheduleAtFixedRate(() -> {
         try {
            session.sendKeepalive();
         } catch (Exception e) {
         }
      }, 15L, 15L, TimeUnit.SECONDS);
   }
   static void startStdTurnKeepalive(ConnectionManager cm, StdTurnClient.StdTurnSession session) {
      cancelTurnKeepalive(cm);
      cm.turnKeepaliveTask = cm.scheduler.scheduleAtFixedRate(() -> {
         try {
            StdTurnClient.refreshQuiet(session, StdTurnClient.DEFAULT_LIFETIME_SEC);
            StdTurnClient.channelBindQuiet(session);
         } catch (Exception e) {
         }
      }, 240L, 240L, TimeUnit.SECONDS);
   }
   static void cancelTurnKeepalive(ConnectionManager cm) {
      ScheduledFuture<?> f = cm.turnKeepaliveTask;
      if (f != null) {
         f.cancel(false);
         cm.turnKeepaliveTask = null;
      }
   }
   static void cancelHotstandbyKeepalive(ConnectionManager cm) {
      ScheduledFuture<?> f = cm.hotstandbyKeepaliveTask;
      if (f != null) {
         f.cancel(false);
         cm.hotstandbyKeepaliveTask = null;
      }
   }
   static void startTurnBgMonitor(ConnectionManager cm, RoomManager.RoomState state) {
      if (cm.turnBgMonitorTask != null || cm.turnP2pGivenUp) {
         return;
      }

      cm.turnBgDeadlineMs = System.currentTimeMillis() + 600_000L;
      cm.turnBgMonitorTask = cm.scheduler.scheduleAtFixedRate(() -> {
         try {
            if (cm.turnSwitchedToP2p) {
               cancelTurnBgMonitor(cm);
               return;
            }

            if (System.currentTimeMillis() > cm.turnBgDeadlineMs) {
               VoxLinkMod.LOGGER.info("[Turn] 10min bg P2P window exhausted, give up until rejoin");
               cm.turnP2pGivenUp = true;
               cancelTurnBgMonitor(cm);
               return;
            }

            ReliableUdpTransport t = cm.turnTransport;
            if (t == null || !t.isConnected()) {
               // TURN 意外死亡（60s 静默熔断等）：自动 teardown（热备转正+程序化重连），不再等 MC 层超时
               cancelTurnBgMonitor(cm);
               teardownTurn(cm, state, null);
               return;
            }

            int tick = cm.turnBgTicks.incrementAndGet();
            // 玩家中继热备相位：60s 一次（奇数 tick），与 30s 直连打洞错开
            // 热备探测门: TURN 数据面活着才值得做玩家中继热备(会话已死时空转打洞+刷日志, 实测30s一次)
            boolean turnCarrying = cm.turnTransport != null && cm.turnTransport.isConnected();
            if (tick % 2 == 1 && turnCarrying && cm.turnHotstandbyTransport == null) {
               attemptBgPlayerRelay(cm, state);
            }

            attemptBgDirectPunch(cm, state);
         } catch (Exception e) {
            VoxLinkMod.LOGGER.debug("[Turn] bg monitor tick error: {}", e.getMessage());
         }
      }, 30L, 30L, TimeUnit.SECONDS);
   }
   static void cancelTurnBgMonitor(ConnectionManager cm) {
      ScheduledFuture<?> f = cm.turnBgMonitorTask;
      if (f != null) {
         f.cancel(false);
         cm.turnBgMonitorTask = null;
      }
   }
   static void attemptBgPlayerRelay(ConnectionManager cm, RoomManager.RoomState state) {
      JsonObject data = new JsonObject();
      data.addProperty("clientId", state.roomInfo.getClientId());
      cm.signalingClient
         .sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), false, "relay_request", data, "host")
         .exceptionally(e -> null);
      VoxLinkMod.LOGGER.info("[Turn] bg player-relay standby probe requested");
   }
   static void attemptBgDirectPunch(ConnectionManager cm, RoomManager.RoomState state) {
      boolean isHost = state.roomInfo.isHost();
      String myIp = state.roomInfo.getMyMappedIp();
      int myPort = state.roomInfo.getMyMappedPort();
      String peerIp = isHost ? null : state.roomInfo.getHostMappedIp();
      int peerPort = isHost ? 0 : state.roomInfo.getHostMappedPort();
      if (!isHost && (peerIp == null || peerPort <= 0)) {
         return;
      }

      if (!isHost && myIp != null && myPort > 0) {
         JsonObject data = new JsonObject();
         data.addProperty("ip", myIp);
         data.addProperty("port", myPort);
         cm.signalingClient
            .sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), false, "turn_bg_punch", data, "host")
            .exceptionally(e -> null);
      }

      if (isHost) {
         // host 端等 guest 的 turn_bg_punch 信号带来 guest 地址后由 handleTurnBgPunch 发起 punch；
         // host 主动侧第一轮无 guest 地址可用（join 请求映射散在上下文中），依赖 guest 周期信号驱动。
         return;
      }

      startBgDirectPuncher(cm, state, peerIp, peerPort);
   }
   static void startBgDirectPuncher(ConnectionManager cm, RoomManager.RoomState state, String ip, int port) {
      UdpHolePuncher puncher = new UdpHolePuncher();
      cm.applyPunchTemplate(puncher);

      try {
         puncher.createSocket();
      } catch (Exception e) {
         return;
      }

      puncher
         .punch(ip, port)
         .orTimeout(5L, TimeUnit.SECONDS)
         .thenAccept(result -> {
            if (result.isSuccess()) {
               onBgDirectPath(cm, state, result.getSuccessSocket(), new InetSocketAddress(ip, port));
            } else {
               puncher.close();
            }
         })
         .exceptionally(e -> {
            puncher.close();
            return null;
         });
   }
   static void handleTurnBgPunch(ConnectionManager cm, String from, JsonObject data) {
      RoomManager.RoomState state = cm.roomManager.currentRoom.get();
      if (state == null || state == RoomManager.PENDING || !state.roomInfo.isHost()) {
         return;
      }

      if (cm.turnBgMonitorTask == null || cm.turnP2pGivenUp || cm.turnSwitchedToP2p) {
         return;
      }

      String ip = data.has("ip") ? data.get("ip").getAsString() : null;
      int port = data.has("port") ? data.get("port").getAsInt() : 0;
      if (ip != null && port > 0) {
         startBgDirectPuncher(cm, state, ip, port);
      }
   }
   static void onBgDirectPath(ConnectionManager cm, RoomManager.RoomState state, DatagramSocket socket, InetSocketAddress addr) {
      if (!cm.turnBgPunchWon.compareAndSet(false, true)) {
         try {
            socket.close();
         } catch (Exception e) {
         }
         return;
      }

      ReliableUdpTransport t = cm.turnTransport;
      if (t == null || !t.isConnected()) {
         try {
            socket.close();
         } catch (Exception e) {
         }
         return;
      }

      VoxLinkMod.LOGGER.info("[Turn] bg direct path established: {}, observing stability", addr);
      t.addSecondaryPath(socket, addr, null);
      cm.turnSwitchStreak.set(0);
      cm.turnSwitchWatchTask = cm.scheduler.scheduleAtFixedRate(() -> {
         try {
            ReliableUdpTransport transport = cm.turnTransport;
            if (transport == null) {
               cancelTurnSwitchWatch(cm);
               return;
            }

            long lastRx = transport.getSecondaryLastRxMs();
            boolean alive = lastRx > 0L && System.currentTimeMillis() - lastRx < 2000L;
            if (alive) {
               int streak = cm.turnSwitchStreak.incrementAndGet();
               if (streak >= 20) {
                  cancelTurnSwitchWatch(cm);
                  transport.promoteSecondaryPath();
                  cm.scheduler.schedule(() -> {
                     try {
                        transport.dropInactivePath();
                     } catch (Exception e) {
                     }

                     finishTurnRelease(cm, state);
                  }, 11L, TimeUnit.SECONDS);
                  VoxLinkMod.LOGGER.info("[Turn] smooth switch to direct P2P done, releasing TURN in 11s");
               }
            } else {
               cm.turnSwitchStreak.set(0);
            }
         } catch (Exception e) {
            VoxLinkMod.LOGGER.debug("[Turn] switch watch error: {}", e.getMessage());
         }
      }, 1L, 1L, TimeUnit.SECONDS);
   }
   static void establishHotstandby(ConnectionManager cm, RoomManager.RoomState state, String relayIp, int relayPort) {
      if (cm.turnHotstandbyTransport != null) {
         return;
      }

      UdpHolePuncher puncher = new UdpHolePuncher();
      cm.applyPunchTemplate(puncher);

      try {
         puncher.createSocket();
      } catch (Exception e) {
         return;
      }

      puncher
         .punch(relayIp, relayPort)
         .orTimeout(10L, TimeUnit.SECONDS)
         .thenAccept(result -> {
            if (result.isSuccess() && cm.isTurnActive() && cm.turnHotstandbyTransport == null) {
               puncher.markSocketTransferred();
               ReliableUdpTransport standby = new ReliableUdpTransport(result.getSuccessSocket(), new InetSocketAddress(relayIp, relayPort));
               standby.start();
               cm.turnHotstandbyTransport = standby;
               VoxLinkMod.LOGGER.info("[Turn] player-relay hot standby established via {}:{}", relayIp, relayPort);
               // 热备保活：中继玩家桥与沿途 NAT 都需要持续流量
               cancelHotstandbyKeepalive(cm);
               cm.hotstandbyKeepaliveTask = cm.scheduler.scheduleAtFixedRate(() -> {
                  try {
                     ReliableUdpTransport hs = cm.turnHotstandbyTransport;
                     if (hs != null && hs.isConnected()) {
                        hs.sendKeepaliveFrame();
                     }
                  } catch (Exception e) {
                  }
               }, 15L, 15L, TimeUnit.SECONDS);
               // host 知情（仅提示；host 侧 joiner 桥本就存在，无需动作）
               JsonObject stby = new JsonObject();
               stby.addProperty("clientId", state.roomInfo.getClientId());
               cm.signalingClient
                  .sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), false, "turn_stby", stby, "host")
                  .exceptionally(e -> null);
            } else {
               puncher.close();
            }
         })
         .exceptionally(e -> {
            puncher.close();
            return null;
         });
   }
   static void handleTurnStby(ConnectionManager cm, String from, JsonObject data) {
      RoomManager.RoomState state = cm.roomManager.currentRoom.get();
      if (state == null || state == RoomManager.PENDING || !state.roomInfo.isHost()) {
         return;
      }

      ClientThread.runOnClientThread(() -> {
         ChatSink.chat(Text.translatable("voxlink.turn.standby"));
      });
   }
   static void cancelTurnSwitchWatch(ConnectionManager cm) {
      ScheduledFuture<?> f = cm.turnSwitchWatchTask;
      if (f != null) {
         f.cancel(false);
         cm.turnSwitchWatchTask = null;
      }
   }
   static void finishTurnRelease(ConnectionManager cm, RoomManager.RoomState state) {
      cm.turnSessionEpoch.incrementAndGet();
      cm.turnSwitchedToP2p = true;
      cancelTurnBgMonitor(cm);
      cancelTurnKeepalive(cm);
      cancelHotstandbyKeepalive(cm);
      TurnRelayClient.TurnSession session = cm.turnSession;
      cm.turnSession = null;
      if (session != null) {
         session.unbind();
      }

      ReliableUdpTransport standby = cm.turnHotstandbyTransport;
      cm.turnHotstandbyTransport = null;
      if (standby != null) {
         try {
            standby.close();
         } catch (Exception e) {
         }
      }

      try {
         TurnRelayClient
            .release(
               cm.signalingClient,
               state.roomInfo.getCode(),
               state.roomInfo.getClientId(),
               state.roomInfo.getToken(),
               session != null ? session.sessionIdHex : "")
            .exceptionally(e -> null);
      } catch (Exception e) {
      }

      state.roomInfo.setUsingRelay(false);
      state.roomInfo.setConnectionMode(Text.translatable("voxlink.turn.switched_p2p"));
   }
   static void teardownTurn(ConnectionManager cm, RoomManager.RoomState state, String failKey) {
      cm.turnSessionEpoch.incrementAndGet();
      cm.turnReadyApplied.set(false);
      ScheduledFuture<?> hsk = cm.hotstandbyKeepaliveTask;
      if (hsk != null) {
         hsk.cancel(false);
         cm.hotstandbyKeepaliveTask = null;
      }
      VoxLinkMod.LOGGER
         .info("[Turn] teardown: session={}, carrying={}, failKey={}", cm.turnSession != null, cm.turnTransport != null && cm.turnTransport.isConnected(), failKey);
      cm.turnInProgress = false;
      cancelTurnBgMonitor(cm);
      cancelTurnSwitchWatch(cm);
      cancelTurnKeepalive(cm);
      TurnRelayClient.TurnSession session = cm.turnSession;
      cm.turnSession = null;
      if (session != null) {
         session.unbind();
      }
      StdTurnClient.StdTurnSession stdSession = cm.stdTurnSession;
      cm.stdTurnSession = null;
      if (stdSession != null) {
         StdTurnClient.close(stdSession);
      }

      ReliableUdpTransport transport = cm.turnTransport;
      cm.turnTransport = null;
      boolean turnWasCarrying = transport != null && transport.isConnected();

      // 热备转正：TURN 断开必然伴随 MC 断线（桥的流绑定 transport，架构性约束），
      // 此刻把备用 G1 通路转正建桥并程序化重连——把"手动退出重进"压到几秒自愈。
      // 仅房客侧（发起方）；host 侧 joiner 桥本就存在，guest 重连的流量自然经中继玩家桥回 host。
      if (turnWasCarrying
         && state != null
         && !state.roomInfo.isHost()
         && cm.turnHotstandbyTransport != null
         && cm.turnHotstandbyTransport.isConnected()) {
         ReliableUdpTransport standby = cm.turnHotstandbyTransport;
         cm.turnHotstandbyTransport = null;
         if (transport != null) {
            try {
               transport.close();
            } catch (Exception e) {
            }
         }

         if (state != null && session != null) {
            try {
               TurnRelayClient
                  .release(
                     cm.signalingClient,
                     state.roomInfo.getCode(),
                     state.roomInfo.getClientId(),
                     state.roomInfo.getToken(),
                     session.sessionIdHex)
                  .exceptionally(e -> null);
            } catch (Exception e) {
            }
         }

         VoxLinkMod.LOGGER.info("[Turn] TURN dead, promoting player-relay hot standby and reconnecting");
         cm.connectionWon.set(false);
         cm.relayConnectedSignaled = false;
         cm.turnPeerId = null;
         cm.turnBgPunchWon.set(false);
         cm.activeUdpTransports.remove("turn_host");
         cm.activeUdpTransports.put("relay_cone", standby);
         state.roomInfo.setUsingRelay(true);
         state.roomInfo.setConnectionMode(Text.translatable("voxlink.relay.connected_via"));
         // startUdpJoinerBridge 检测旧桥 transport 已死会重建；随后 connectToServer 程序化重连
         cm.startUdpPunchBridge(state, standby);
         cm.connectionWon.set(true);
         return;
      }

      if (transport != null) {
         try {
            transport.close();
         } catch (Exception e) {
         }
      }

      if (cm.turnPeerId != null) {
         cm.activeUdpTransports.remove("turn_host");
         if (state != null && state.roomInfo.isHost() && !"host".equals(cm.turnPeerId)) {
            ReliableUdpTransport t = cm.activeUdpTransports.get(cm.turnPeerId);
            if (t != null && t == transport) {
               cm.activeUdpTransports.remove(cm.turnPeerId);
            }
         }
      }

      cm.turnPeerId = null;
      cm.turnBgPunchWon.set(false);
      if (state != null && session != null) {
         try {
            TurnRelayClient
               .release(
                  cm.signalingClient,
                  state.roomInfo.getCode(),
                  state.roomInfo.getClientId(),
                  state.roomInfo.getToken(),
                  session.sessionIdHex)
               .exceptionally(e -> null);
         } catch (Exception e) {
         }
      }

      if (failKey != null && state != null) {
         // 自动中继模式：TURN 失败不弹失败屏，交编排器换道（玩家中继）或回打洞
         if (cm.autoRelayMode) {
            cm.autoRelayFailoverOrEnd(state, failKey);
         } else {
            cm.showConnectFailed(state, failKey);
         }
      }
   }
   static void teardownTurnQuietly(ConnectionManager cm) {
      try {
         cm.turnSessionEpoch.incrementAndGet();
         cancelTurnBgMonitor(cm);
         cancelTurnKeepalive(cm);
         cancelHotstandbyKeepalive(cm);
         TurnRelayClient.TurnSession session = cm.turnSession;
         cm.turnSession = null;
         if (session != null) {
            session.unbind();
         }

         ReliableUdpTransport old = cm.turnTransport;
         cm.turnTransport = null;
         if (old != null) {
            old.close();
         }
         StdTurnClient.StdTurnSession stdSession = cm.stdTurnSession;
         cm.stdTurnSession = null;
         if (stdSession != null) {
            StdTurnClient.close(stdSession);
         }

         ReliableUdpTransport standby = cm.turnHotstandbyTransport;
         cm.turnHotstandbyTransport = null;
         if (standby != null) {
            standby.close();
         }

         cm.turnInProgress = false;
         cm.turnPeerId = null;
         cm.turnBgPunchWon.set(false);
         RoomManager.RoomState state = cm.roomManager.currentRoom.get();
         if (state != null && state != RoomManager.PENDING) {
            state.roomInfo.setConnectionMode(Text.translatable("voxlink.auto_relay.player"));
         }
      } catch (Exception e) {
         VoxLinkMod.LOGGER.debug("[AutoRelay] teardown old turn error: {}", e.getMessage());
      }
   }
   static void cleanupTurnQuietly(ConnectionManager cm) {
      try {
         cm.turnSessionEpoch.incrementAndGet();
         cancelTurnBgMonitor(cm);
         cancelTurnSwitchWatch(cm);
         cancelTurnKeepalive(cm);
         cancelHotstandbyKeepalive(cm);
         TurnRelayClient.TurnSession session = cm.turnSession;
         cm.turnSession = null;
         if (session != null) {
            session.unbind();
         }

         // 对齐 teardownTurnQuietly: 置 null 不 close 会让传输内部重传/心跳任务继续空转
         ReliableUdpTransport staleTransport = cm.turnTransport;
         cm.turnTransport = null;
         if (staleTransport != null) {
            try {
               staleTransport.close();
            } catch (Exception e) {
            }
         }
         StdTurnClient.StdTurnSession stdSession = cm.stdTurnSession;
         cm.stdTurnSession = null;
         if (stdSession != null) {
            StdTurnClient.close(stdSession);
         }
         ReliableUdpTransport standby = cm.turnHotstandbyTransport;
         cm.turnHotstandbyTransport = null;
         if (standby != null) {
            try {
               standby.close();
            } catch (Exception e) {
            }
         }

         cm.turnInProgress = false;
         cm.turnPeerId = null;
         // 会话终结即复位"本周期放弃 P2P 升级"标记（"退出重进重置"的实际落点）
         cm.turnP2pGivenUp = false;
         cm.turnSwitchedToP2p = false;
         // 漏复位会让下一会话的后台 P2P 升级 CAS 恒失败, 直连升级永久失效(审计 P1-4)
         cm.turnBgPunchWon.set(false);
      } catch (Exception e) {
      }
   }
   static void handleIceRestart(ConnectionManager cm, String from, JsonObject data) {

      VoxLinkMod.LOGGER.info("[Connection] Received ice_restart signal from peer ({})", from);

      RoomManager.RoomState state = cm.roomManager.currentRoom.get();

      if (state != null && state != RoomManager.PENDING && state.roomInfo != null) {

         performIceRestart(cm, state, "收到对端ice_restart信号");

      } else {

         VoxLinkMod.LOGGER.info("[Connection] Received ice_restart but left room, ignore");

      }

   }
   static void triggerIceRestart(ConnectionManager cm) {

      RoomManager.RoomState state = cm.roomManager.currentRoom.get();

      if (state == null || state == RoomManager.PENDING || state.roomInfo == null) {

         VoxLinkMod.LOGGER.info("[Connection] Transport disconnected but left room, no ICE Restart");

      } else if (!shouldIceRestart(cm, state)) {

         VoxLinkMod.LOGGER.info("[Connection] Peer does not support ice_restart, go original disconnect logic");

      } else {

         performIceRestart(cm, state, "传输层断开");

         sendIceRestartSignal(cm, state);

      }

   }
   static boolean shouldIceRestart(ConnectionManager cm, RoomManager.RoomState state) {

      if (state == null || state == RoomManager.PENDING || state.roomInfo == null) {

         return false;

      } else if (state.roomInfo.isHost()) {

         return state.roomInfo.getPeers().isEmpty() ? false : state.roomInfo.getPeers().stream().allMatch(ProtocolNegotiator::supportsIceRestart);

      } else {

         return state.roomInfo.isHostLegacy() ? false : state.roomInfo.getHostCapabilities().contains("ice_restart");

      }

   }
   static void performIceRestart(ConnectionManager cm, RoomManager.RoomState state, String reason) {
      // ICE重启将停所有传输：TURN会话先静默拆净，防stdTurnSession残留锁死自动中继导演
      if (cm.turnSession != null || cm.stdTurnSession != null) {
         teardownTurnQuietly(cm);
      }

      long now = System.currentTimeMillis();

      long last = cm.lastIceRestartTimeMs.get();

      if (now - last < 5000L) {

         VoxLinkMod.LOGGER.info("[Connection] ICE Restart cooldown (within {}ms), ignore trigger ({})", 5000L, reason);

      } else {

         int attempt = cm.iceRestartAttempts.incrementAndGet();

         if (attempt > 3) {

            VoxLinkMod.LOGGER.warn("[Connection] ICE Restart reached max {}, give up", 3);

         } else {

            cm.lastIceRestartTimeMs.set(now);

            VoxLinkMod.LOGGER.info("[Connection] ICE Restart trigger ({}/{}): {}", new Object[]{attempt, 3, reason});

            cm.connectionWon.set(false);

            cm.connectionCycleActive.set(false);

            cm.reversePunchAttempted.set(false);

            cm.dualRaceActive = false;

            cm.terracottaWon = false;

            cm.voxlinkWon = false;

            cm.voxlinkSideDisabled = false;

            cm.dualVoxlinkBridgeFuture = null;

            if (cm.connectionTimeoutFuture != null) {

               cm.connectionTimeoutFuture.cancel(false);

               cm.connectionTimeoutFuture = null;

            }



            cm.stopAllConnectionWork();

            cm.clearRelayTracking();

            ConnectionState.transitionTo(ConnectionState.STUN_PROBE, "ICE Restart " + attempt + "/3");

            ClientThread.runOnClientThread(

               () -> ChatSink.chat(Text.translatable("voxlink.chat.error_prefix").append(Text.translatable("voxlink.connection.ice_restart")))

            );



            RoomManager.RoomState savedStateRef = cm.savedConnectionState;
            if ((savedStateRef == null || savedStateRef != state || cm.savedConnectionHostIp == null)
               && state.roomInfo != null && !state.roomInfo.isHost()
               && state.roomInfo.getHostIp() != null && !state.roomInfo.getHostIp().isEmpty()) {
               cm.savedConnectionState = state;
               if (cm.savedConnectionFrom == null || cm.savedConnectionFrom.isEmpty()) {
                  cm.savedConnectionFrom = "host";
               }

               cm.savedConnectionHostIpv6 = state.roomInfo.getHostIpv6();
               cm.savedConnectionHostIp = state.roomInfo.getHostIp();
               cm.savedConnectionHostPort = state.roomInfo.getHostPort();
               cm.savedConnectionHostMappedIp = state.roomInfo.getHostMappedIp();
               cm.savedConnectionHostMappedPort = state.roomInfo.getHostMappedPort();
               savedStateRef = state;
               VoxLinkMod.LOGGER.info("[Connection] ICE Restart rebuild saved params from room info");
            }

            final RoomManager.RoomState savedState = savedStateRef;
            if (savedState != null && savedState == state && cm.savedConnectionHostIp != null) {
               cm.connectionStartTimeMs = System.currentTimeMillis();
               int timeoutSec = cm.punchProfile().connectionTimeoutSec;
               cm.connectionTimeoutSec = timeoutSec;
               state.roomInfo.setConnectionMode(Text.translatable("voxlink.connection.connecting"));
               cm.scheduleConnectionTimeout(state, timeoutSec);
               cm.scheduler
                  .schedule(
                     () -> {
                        if (cm.roomManager.currentRoom.get() == savedState) {
                           cm.runConnectionCycle(
                              savedState,
                              cm.savedConnectionFrom,
                              cm.savedConnectionHostIpv6,
                              cm.savedConnectionHostIp,
                              cm.savedConnectionHostPort,
                              cm.savedConnectionHostMappedIp,
                              cm.savedConnectionHostMappedPort,
                              0
                           );
                        }
                     },
                     500L,
                     TimeUnit.MILLISECONDS
                  );
            } else if (state.roomInfo != null && state.roomInfo.isHost()) {
               VoxLinkMod.LOGGER.info("[Connection] ICE Restart (host): state cleaned, wait for joiner punch_info re-trigger");
            } else {
               VoxLinkMod.LOGGER.warn("[Connection] ICE Restart no saved params, cannot re-trigger");
            }

            // 重挂自动中继导演: stopAutoRelayAll 已随 stopAllConnectionWork 拆掉它,
            // 不重挂则重打洞失败后只剩无中继的无限打洞(手动按钮已退役)
            if (!state.roomInfo.isHost() && !cm.connectionWon.get()) {
               cm.startAutoRelayDirector(state);
            }

         }

      }

   }
   static void sendIceRestartSignal(ConnectionManager cm, RoomManager.RoomState state) {

      try {

         if (state.roomInfo == null) {

            return;

         }



         String target = state.roomInfo.isHost() ? "all" : "host";

         cm.signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), false, "ice_restart", new JsonObject(), target);

         VoxLinkMod.LOGGER.info("[Connection] Sent ice_restart signal to peer ({})", target);

      } catch (Exception e) {

         VoxLinkMod.LOGGER.warn("[Connection] Send ice_restart signal failed: {}", e.getMessage());

      }

   }
   static void resetIceRestartState(ConnectionManager cm) {

      cm.iceRestartAttempts.set(0);

      cm.lastIceRestartTimeMs.set(0L);

      cm.savedConnectionState = null;

   }
   static void handleTurnReady(ConnectionManager cm, String from, JsonObject data) {
      // 标准 TURN 分支：ChannelBind(host relay) → 建 transport → 桥接
      if (data.has("stdTurn") && !data.get("stdTurn").isJsonNull() && data.get("stdTurn").getAsBoolean()) {
         handleStdTurnReady(cm, from, data);
         return;
      }
      RoomManager.RoomState state = cm.roomManager.currentRoom.get();
      ReliableUdpTransport transport = cm.turnTransport;
      if (state == null || state == RoomManager.PENDING || transport == null || transport.isConnected()) {
         return;
      }

      if (cm.connectionWon.get() && !cm.isTurnActive()) {
         teardownTurn(cm, state, "voxlink.turn.failed");
         return;
      }

      if (!cm.turnReadyApplied.compareAndSet(false, true)) {
         return;
      }

      cm.turnInProgress = false;
      transport.start();
      cm.activeUdpTransports.put("turn_host", transport);
      // 置已连接信号标志：TURN 建立前遗留的 in-flight 打洞回调若迟到成功，
      // 会被 handleRelayNotify 的 CAS+该标志组合拦下，防止重复建桥
      cm.relayConnectedSignaled = true;
      cm.connectionWon.set(true);
      cm.manualRelayInProgress = false;
      cm.relayProgressText = null;
      cm.manualRelayDeadline = 0L;
      state.roomInfo.setConnectionMode(Text.translatable("voxlink.relay.connected_via").withStyle(Text.Style.builder().color(Text.ChatFormat.YELLOW).build()));
      state.roomInfo.setUsingRelay(true);
      cm.startUdpPunchBridge(state, transport);
      startTurnBgMonitor(cm, state);
      VoxLinkMod.LOGGER.info("[Turn] guest path up, bridge starting");
      icu.wuhui.voxlink.ui.UiLogBus.push(0, "voxlink.logui.turn_established");
      icu.wuhui.voxlink.ui.UiLogBus.push(1, "voxlink.logui.success");
   }
   static void handleStdTurnReady(ConnectionManager cm, String from, JsonObject data) {
      RoomManager.RoomState state = cm.roomManager.currentRoom.get();
      StdTurnClient.StdTurnSession session = cm.stdTurnSession;
      if (state == null || state == RoomManager.PENDING || session == null) {
         return;
      }

      if (cm.connectionWon.get() && !cm.isTurnActive()) {
         teardownTurn(cm, state, "voxlink.turn.failed");
         return;
      }

      if (!cm.turnReadyApplied.compareAndSet(false, true)) {
         return;
      }
      if (!data.has("relayHost") || !data.has("relayPort")) {
         teardownTurn(cm, state, "voxlink.turn.failed");
         return;
      }
      String relayHost = data.get("relayHost").getAsString();
      int relayPort = data.get("relayPort").getAsInt();

      // ChannelBind 同步事务（socket 读）必须离开信令分发线程（对齐 host bind 的 P1-2 教训）
      ConnectionManager.TURN_BG_EXECUTOR.execute(() -> {
         InetSocketAddress peer = new InetSocketAddress(relayHost, relayPort);
         if (peer.getAddress() == null) {
            VoxLinkMod.LOGGER.warn("[Turn] stdTurn guest relay host unresolvable: {}", relayHost);
            teardownTurn(cm, state, "voxlink.turn.failed");
            return;
         }
         if (!StdTurnClient.channelBind(session, peer, StdTurnClient.CHANNEL_BASE, 8000)) {
            VoxLinkMod.LOGGER.warn("[Turn] stdTurn guest ChannelBind to {} failed", peer);
            teardownTurn(cm, state, "voxlink.turn.failed");
            return;
         }
         // 竞态屏障：ChannelBind 期间 teardown 可能已拆会话
         if (cm.stdTurnSession != session) {
            return;
         }

         cm.turnInProgress = false;
         UdpPath.Codec codec = new StdTurnClient.StdTurnPathCodec(StdTurnClient.CHANNEL_BASE);
         ReliableUdpTransport transport = new ReliableUdpTransport(session.socket, session.endpoint(), codec);
         // TURN 路径密钥现场重派生（同自定义协议：不沿用打洞阶段 activePunchAuthKey）
         byte[] turnAuthKey = cm.derivePunchAuthKey(state, null, true);
         if (turnAuthKey != null) {
            transport.setAuthKey(turnAuthKey);
            cm.activePunchAuthKey = turnAuthKey;
            VoxLinkMod.LOGGER.info("[PunchAuth] stdTurn transport auth enabled (guest side)");
         } else {
            VoxLinkMod.LOGGER.info("[PunchAuth] stdTurn transport without auth (host caps missing)");
         }
         transport.allowAuthDowngradeForInterop();
         // TURN载具补接ICE重启回调
         transport.setOnIceRestartRequested(cm::triggerIceRestart);

         cm.turnTransport = transport;
         transport.start();
         cm.activeUdpTransports.put("turn_host", transport);
         cm.relayConnectedSignaled = true;
         cm.connectionWon.set(true);
         cm.manualRelayInProgress = false;
         cm.relayProgressText = null;
         cm.manualRelayDeadline = 0L;
         state.roomInfo.setConnectionMode(Text.translatable("voxlink.relay.connected_via").withStyle(Text.Style.builder().color(Text.ChatFormat.YELLOW).build()));
         state.roomInfo.setUsingRelay(true);
         cm.startUdpPunchBridge(state, transport);
         startTurnBgMonitor(cm, state);
         VoxLinkMod.LOGGER.info("[Turn] stdTurn guest path up (channel 0x4000 ↔ {}), bridge starting", peer);
         icu.wuhui.voxlink.ui.UiLogBus.push(0, "voxlink.logui.turn_established");
      });
   }
   static void handleTurnNack(ConnectionManager cm, String from, JsonObject data) {
      RoomManager.RoomState state = cm.roomManager.currentRoom.get();
      if (state == null || state == RoomManager.PENDING) {
         return;
      }
      ReliableUdpTransport transport = cm.turnTransport;
      if (transport != null && transport.isConnected()) {
         return;
      }
      if (transport == null && !cm.turnInProgress) {
         return;
      }
      String reason = data.has("reason") ? data.get("reason").getAsString() : "unknown";
      VoxLinkMod.LOGGER.warn("[Turn] host nack ({}), tear down TURN early", reason);
      teardownTurn(cm, state, "voxlink.turn.failed");
   }
}
