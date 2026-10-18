#!/usr/bin/env python3
# rebuild_stdturn_cm_20260925.py —— 重建 fabric/26.3 ConnectionManager 的标准 TURN 集成
# 背景：误执行 restore 回滚了 26.3 源集成改动（未提交，不可逆），本脚本从会话编辑记录重建。
# 设计：bind 段缩进保持原样（Java 不敏感），P6 拆为 4 处小替换而非整段重排 → 风险最低。
# 幂等：已含 stdTurnSession 则跳过。
import subprocess
import sys

REF = 'fabric/26.3/src/main/java/icu/wuhui/voxlink/room/ConnectionManager.java'

def read_norm(p):
    return open(p, encoding='utf-8', newline='').read().replace('\r\n', '\n')

def write_keep_eol(path, norm_text):
    old = open(path, 'rb').read()
    eol = b'\r\n' if b'\r\n' in old[:4000] else b'\n'
    open(path, 'wb').write(norm_text.replace('\n', eol.decode()).encode())

# ---------------- 新代码段 ----------------

STDGFLOW = '''   /**
    * 标准 TURN guest 流程（RFC 5766）：签 cred → Allocate → 发 turn_alloc（带本端 relay 地址）
    * → 等 host turn_ready（handleStdTurnReady 里 ChannelBind + 建 transport + 桥接）。
    */
   private CompletableFuture<Boolean> startStdTurnGuestFlow(RoomManager.RoomState state, SignalingClient sc, TurnRelayClient.NodeInfo node) {
      icu.wuhui.voxlink.ui.UiLogBus.push(0, "voxlink.logui.turn_allocating");
      return CompletableFuture.supplyAsync(() -> {
         StdTurnClient.StdCred cred = StdTurnClient
            .fetchCred(sc, state.roomInfo.getCode(), state.roomInfo.getClientId(), state.roomInfo.getToken(), node.id)
            .join();
         if (cred == null) {
            throw new IllegalStateException("STD_CRED_FAILED");
         }

         // 竞态二次检查：列表+探测+cred 耗费数秒，期间直连可能已打通
         if (this.connectionWon.get() && this.stdTurnSession == null) {
            throw new IllegalStateException("P2P_WON");
         }

         StdTurnClient.StdTurnSession session = StdTurnClient.allocate(cred.host, cred.port, cred.username, cred.password, 8000);
         if (session == null) {
            throw new IllegalStateException("STD_ALLOC_FAILED");
         }

         // 写回屏障：35s 总超时的 teardown 先到时中止（对齐自定义协议 1.1.5 屏障语义）
         if (!this.turnInProgress) {
            StdTurnClient.close(session);
            VoxLinkMod.LOGGER.warn("[Turn] stdTurn guest flow cancelled during allocate (timeout teardown won), discard session");
            throw new IllegalStateException("TURN_CANCELLED");
         }

         this.stdTurnSession = session;
         this.startStdTurnKeepalive(session);
         this.turnPeerId = "host";
         VoxLinkMod.LOGGER.info("[Turn] stdTurn guest allocated relay={} via {}:{}, sending turn_alloc", session.relayAddr, cred.host, cred.port);
         return session;
      }, TURN_BG_EXECUTOR).thenCompose(session -> {
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

'''

STDALLOC = '''   /**
    * host 收 stdTurn turn_alloc（RFC 5766）：签 cred → Allocate → ChannelBind(guest relay)
    * → 建 transport → 起 host 桥 → 回 turn_ready（带本端 relay 地址）。
    * 与自定义协议 handleTurnAlloc 平行；任一步失败回 turn_nack，guest 兜底超时自拆。
    */
   private void handleStdTurnAlloc(RoomManager.RoomState state, String from, JsonObject data) {
      // 边缘情况：guest 点中继期间直连恰好打通——同自定义协议语义，静默忽略+nack
      if (this.connectionWon.get() && !this.isTurnActive()) {
         VoxLinkMod.LOGGER.info("[Turn] host already connected directly, ignore stdTurn turn_alloc from {}", from);
         this.sendTurnNack(state, from, "direct_won");
         return;
      }
      if (this.turnSession != null || this.stdTurnSession != null) {
         return;
      }
      if (!data.has("relayHost") || !data.has("relayPort") || !data.has("nodeId") || !data.has("nodeHost") || !data.has("stdTurnPort")) {
         this.sendTurnNack(state, from, "bad_alloc");
         return;
      }
      String guestRelayHost = data.get("relayHost").getAsString();
      int guestRelayPort = data.get("relayPort").getAsInt();
      String nodeId = data.get("nodeId").getAsString();
      int stdTurnPort = data.get("stdTurnPort").getAsInt();
      if (guestRelayHost.isEmpty() || guestRelayPort <= 0 || nodeId.isEmpty() || stdTurnPort <= 0) {
         this.sendTurnNack(state, from, "bad_alloc");
         return;
      }

      // cred+allocate+ChannelBind 全是同步网络事务：必须离开信令分发线程（审计 P1-2）
      TURN_BG_EXECUTOR.execute(() -> {
         StdTurnClient.StdCred cred;
         try {
            cred = StdTurnClient
               .fetchCred(this.signalingClient, state.roomInfo.getCode(), state.roomInfo.getClientId(), state.roomInfo.getToken(), nodeId)
               .join();
         } catch (Exception e) {
            cred = null;
         }
         if (cred == null) {
            VoxLinkMod.LOGGER.warn("[Turn] stdTurn host cred fetch failed (node={})", nodeId);
            this.sendTurnNack(state, from, "std_cred_failed");
            return;
         }
         StdTurnClient.StdTurnSession session = StdTurnClient.allocate(cred.host, cred.port, cred.username, cred.password, 8000);
         if (session == null) {
            VoxLinkMod.LOGGER.warn("[Turn] stdTurn host allocate failed via {}:{}", cred.host, cred.port);
            this.sendTurnNack(state, from, "std_alloc_failed");
            return;
         }
         InetSocketAddress peer = new InetSocketAddress(guestRelayHost, guestRelayPort);
         if (!StdTurnClient.channelBind(session, peer, StdTurnClient.CHANNEL_BASE, 8000)) {
            VoxLinkMod.LOGGER.warn("[Turn] stdTurn host ChannelBind to guest relay {} failed", peer);
            StdTurnClient.close(session);
            this.sendTurnNack(state, from, "std_bind_failed");
            return;
         }
         // 竞态写回屏障：异步期间可能已直连获胜/会话被拆
         if (this.turnSession != null || this.stdTurnSession != null) {
            StdTurnClient.close(session);
            return;
         }

         this.stdTurnSession = session;
         this.turnPeerId = from;
         this.startStdTurnKeepalive(session);
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
            turnAuthKey = this.derivePunchAuthKey(state, turnPeerId, false);
         }
         if (turnAuthKey != null) {
            transport.setAuthKey(turnAuthKey);
            this.activePunchAuthKey = turnAuthKey;
            VoxLinkMod.LOGGER.info("[PunchAuth] stdTurn transport auth enabled (host side, peer {})", turnPeerId);
         } else {
            VoxLinkMod.LOGGER.info("[PunchAuth] stdTurn transport without auth (peer {} legacy/caps missing)", turnPeerId);
         }
         transport.allowAuthDowngradeForInterop();

         this.turnTransport = transport;
         transport.start();
         this.activeUdpTransports.put(from, transport);
         this.startHostUdpPunchBridge(state, from, transport);

         JsonObject ready = new JsonObject();
         ready.addProperty("stdTurn", true);
         ready.addProperty("relayHost", session.relayAddr.getAddress().getHostAddress());
         ready.addProperty("relayPort", session.relayAddr.getPort());
         ready.addProperty("clientId", state.roomInfo.getClientId());
         this.signalingClient
            .sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), true, "turn_ready", ready, from)
            .exceptionally(e -> {
               VoxLinkMod.LOGGER.warn("[Turn] stdTurn turn_ready send failed: {}", e.getMessage());
               return null;
            });
         // 投递保险重发（对齐自定义协议 1.1.5：单发无重试是硬伤）
         JsonObject readyResend = ready;
         StdTurnClient.StdTurnSession sessionRef = session;
         this.scheduler.schedule(() -> {
            if (this.stdTurnSession == sessionRef && this.turnPeerId != null && this.turnPeerId.equals(from)) {
               this.signalingClient
                  .sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), true, "turn_ready", readyResend, from)
                  .exceptionally(e -> {
                     VoxLinkMod.LOGGER.debug("[Turn] stdTurn turn_ready resend failed: {}", e.getMessage());
                     return null;
                  });
               VoxLinkMod.LOGGER.info("[Turn] stdTurn turn_ready resent (delivery insurance)");
            }
         }, 4L, java.util.concurrent.TimeUnit.SECONDS);
         this.startTurnBgMonitor(state);
         VoxLinkMod.LOGGER.info("[Turn] stdTurn host path up (channel 0x4000 ↔ {}, peer={})", peer, from);
         icu.wuhui.voxlink.ui.UiLogBus.push(1, "voxlink.logui.success");
      });
   }

'''

STDREADY = '''   /** guest 收 stdTurn turn_ready：ChannelBind(host relay) → 建 transport → 桥接进 MC。 */
   private void handleStdTurnReady(String from, JsonObject data) {
      RoomManager.RoomState state = this.roomManager.currentRoom.get();
      StdTurnClient.StdTurnSession session = this.stdTurnSession;
      if (state == null || state == RoomManager.PENDING || session == null) {
         return;
      }

      if (this.connectionWon.get() && !this.isTurnActive()) {
         this.teardownTurn(state, "voxlink.turn.failed");
         return;
      }

      if (!this.turnReadyApplied.compareAndSet(false, true)) {
         return;
      }
      if (!data.has("relayHost") || !data.has("relayPort")) {
         this.teardownTurn(state, "voxlink.turn.failed");
         return;
      }
      String relayHost = data.get("relayHost").getAsString();
      int relayPort = data.get("relayPort").getAsInt();

      // ChannelBind 同步事务（socket 读）必须离开信令分发线程（对齐 host bind 的 P1-2 教训）
      TURN_BG_EXECUTOR.execute(() -> {
         InetSocketAddress peer = new InetSocketAddress(relayHost, relayPort);
         if (!StdTurnClient.channelBind(session, peer, StdTurnClient.CHANNEL_BASE, 8000)) {
            VoxLinkMod.LOGGER.warn("[Turn] stdTurn guest ChannelBind to {} failed", peer);
            this.teardownTurn(state, "voxlink.turn.failed");
            return;
         }
         // 竞态屏障：ChannelBind 期间 teardown 可能已拆会话
         if (this.stdTurnSession != session) {
            return;
         }

         this.turnInProgress = false;
         UdpPath.Codec codec = new StdTurnClient.StdTurnPathCodec(StdTurnClient.CHANNEL_BASE);
         ReliableUdpTransport transport = new ReliableUdpTransport(session.socket, session.endpoint(), codec);
         // TURN 路径密钥现场重派生（同自定义协议：不沿用打洞阶段 activePunchAuthKey）
         byte[] turnAuthKey = this.derivePunchAuthKey(state, null, true);
         if (turnAuthKey != null) {
            transport.setAuthKey(turnAuthKey);
            this.activePunchAuthKey = turnAuthKey;
            VoxLinkMod.LOGGER.info("[PunchAuth] stdTurn transport auth enabled (guest side)");
         } else {
            VoxLinkMod.LOGGER.info("[PunchAuth] stdTurn transport without auth (host caps missing)");
         }
         transport.allowAuthDowngradeForInterop();

         this.turnTransport = transport;
         transport.start();
         this.activeUdpTransports.put("turn_host", transport);
         this.relayConnectedSignaled = true;
         this.connectionWon.set(true);
         this.manualRelayInProgress = false;
         this.relayProgressText = null;
         this.manualRelayDeadline = 0L;
         state.roomInfo.setConnectionMode(Component.translatable("voxlink.relay.connected_via").withStyle(ChatFormatting.YELLOW));
         state.roomInfo.setUsingRelay(true);
         this.startUdpPunchBridge(state, transport);
         this.startTurnBgMonitor(state);
         VoxLinkMod.LOGGER.info("[Turn] stdTurn guest path up (channel 0x4000 ↔ {}), bridge starting", peer);
         icu.wuhui.voxlink.ui.UiLogBus.push(0, "voxlink.logui.turn_established");
      });
   }

'''

KEEPALIVE = '''   /**
    * 标准 TURN 保活：240s 周期 Refresh(allocation 600s) + ChannelBind 重发(channel 10min，
    * permission 随刷)。全部"发了就算"（幂等，丢一次下周补；socket 已交 RUDP，
    * 响应被 codec 丢弃属预期，见 StdTurnClient.refreshQuiet/channelBindQuiet）。
    */
   private void startStdTurnKeepalive(StdTurnClient.StdTurnSession session) {
      this.cancelTurnKeepalive();
      this.turnKeepaliveTask = this.scheduler.scheduleAtFixedRate(() -> {
         try {
            StdTurnClient.refreshQuiet(session, StdTurnClient.DEFAULT_LIFETIME_SEC);
            StdTurnClient.channelBindQuiet(session);
         } catch (Exception e) {
         }
      }, 240L, 240L, TimeUnit.SECONDS);
   }

'''

# ---------------- 替换对 ----------------
# (描述, old, new, 期望命中次数)
PAIRS = [
    ('P1 import',
     'import icu.wuhui.voxlink.network.TurnRelayClient;\n',
     'import icu.wuhui.voxlink.network.TurnRelayClient;\nimport icu.wuhui.voxlink.network.StdTurnClient;\n', 1),
    ('P2 字段',
     '   private volatile TurnRelayClient.TurnSession turnSession = null;\n',
     '   private volatile TurnRelayClient.TurnSession turnSession = null;\n   /** 标准 TURN 会话（RFC 5766，与 turnSession 互斥——同一会话只走一种协议栈）。 */\n   private volatile StdTurnClient.StdTurnSession stdTurnSession = null;\n', 1),
    ('P3 中继入口判定',
     '      if (this.turnInProgress || this.turnSession != null) {\n',
     '      if (this.turnInProgress || this.turnSession != null || this.stdTurnSession != null) {\n', 1),
    ('P4 isTurnActive',
     '      return this.turnSession != null && !this.turnSwitchedToP2p;\n',
     '      return (this.turnSession != null || this.stdTurnSession != null) && !this.turnSwitchedToP2p;\n', 1),
    ('P5 isTurnPeer',
     '      return from != null && this.turnSession != null && from.equals(this.turnPeerId);\n',
     '      return from != null && (this.turnSession != null || this.stdTurnSession != null) && from.equals(this.turnPeerId);\n', 1),
    ('P6a 触发链分叉头',
     '''         .thenCompose(node -> {
            icu.wuhui.voxlink.ui.UiLogBus.push(0, "voxlink.logui.turn_allocating");
            return TurnRelayClient.allocate(sc, state.roomInfo.getCode(), state.roomInfo.getClientId(), state.roomInfo.getToken(), node.id);
         })
         .thenCompose(alloc -> {
''',
     '''         .thenCompose(node -> {
            // 标准 TURN（双方 stdTurnV1 + 节点双栈 stdTurnPort>0）→ RFC 5766 流程；
            // 任一端旧版/节点未启用 → 自定义协议 v1（现有路径，零行为变化）
            if (node.stdTurnPort > 0 && ProtocolNegotiator.hostSupportsStdTurn(state.roomInfo)) {
               VoxLinkMod.LOGGER.info("[Turn] node {} stdTurnPort={}, host stdTurnV1: using standard TURN (RFC 5766)", node.id, node.stdTurnPort);
               return this.startStdTurnGuestFlow(state, sc, node).thenApply(ok -> (Object)ok);
            }
            icu.wuhui.voxlink.ui.UiLogBus.push(0, "voxlink.logui.turn_allocating");
            return TurnRelayClient.allocate(sc, state.roomInfo.getCode(), state.roomInfo.getClientId(), state.roomInfo.getToken(), node.id)
               .thenCompose(alloc -> {
''', 1),
    ('P6b 触发链收尾',
     '''               .thenApply(r -> session);
         })
                  // 35s: bind 升级为 3 轮重试后最长 ~16s, 加上列表/探测/allocate 需要更大总预算
''',
     '''               .thenApply(r -> session);
               })
               .thenApply(s -> (Object)s);
         })
                  // 35s: bind 升级为 3 轮重试后最长 ~16s, 加上列表/探测/allocate 需要更大总预算
''', 1),
    ('P6c whenComplete 兜底条件',
     '                  if (this.turnTransport != null && !this.turnTransport.isConnected() && !this.turnSwitchedToP2p) {\n',
     '''                  // stdTurn 分支：turnTransport 要等 turn_ready 才建——null 即未就绪
                  boolean noReady = this.stdTurnSession != null
                     ? (this.turnTransport == null || !this.turnTransport.isConnected())
                     : (this.turnTransport == null || !this.turnTransport.isConnected());
                  if (noReady && !this.turnSwitchedToP2p) {
''', 1),
    ('P7 startStdTurnGuestFlow 插入',
     '   /** host 收 turn_alloc：自动 BIND hostTicket → 建 transport → 起 host 桥 → 回 turn_ready。房主无任何 UI。 */\n',
     STDGFLOW + '   /** host 收 turn_alloc：自动 BIND hostTicket → 建 transport → 起 host 桥 → 回 turn_ready。房主无任何 UI。 */\n', 1),
    ('P8 handleTurnAlloc stdTurn 分支',
     '''   public void handleTurnAlloc(String from, JsonObject data) {
      RoomManager.RoomState state = this.roomManager.currentRoom.get();
      if (state == null || state == RoomManager.PENDING || !state.roomInfo.isHost()) {
         return;
      }
''',
     '''   public void handleTurnAlloc(String from, JsonObject data) {
      RoomManager.RoomState state = this.roomManager.currentRoom.get();
      if (state == null || state == RoomManager.PENDING || !state.roomInfo.isHost()) {
         return;
      }

      // 标准 TURN 分支（guest 声明 stdTurn）：RFC 5766 流程，独立处理
      if (data.has("stdTurn") && !data.get("stdTurn").isJsonNull() && data.get("stdTurn").getAsBoolean()) {
         this.handleStdTurnAlloc(state, from, data);
         return;
      }
''', 1),
    ('P9 handleStdTurnAlloc 插入',
     '   /** guest 收 turn_ready：host 数据面就绪 → start 自己的 TURN transport → 桥接进 MC。 */\n',
     STDALLOC + '   /** guest 收 turn_ready：host 数据面就绪 → start 自己的 TURN transport → 桥接进 MC。 */\n', 1),
    ('P10 handleTurnReady stdTurn 分支',
     '''   public void handleTurnReady(String from, JsonObject data) {
      RoomManager.RoomState state = this.roomManager.currentRoom.get();
      ReliableUdpTransport transport = this.turnTransport;
''',
     '''   public void handleTurnReady(String from, JsonObject data) {
      // 标准 TURN 分支：ChannelBind(host relay) → 建 transport → 桥接
      if (data.has("stdTurn") && !data.get("stdTurn").isJsonNull() && data.get("stdTurn").getAsBoolean()) {
         this.handleStdTurnReady(from, data);
         return;
      }
      RoomManager.RoomState state = this.roomManager.currentRoom.get();
      ReliableUdpTransport transport = this.turnTransport;
''', 1),
    ('P11 handleStdTurnReady 插入',
     '   public void handleTurnNack(String from, JsonObject data) {',
     STDREADY + '   public void handleTurnNack(String from, JsonObject data) {', 1),
    ('P12 startStdTurnKeepalive 插入',
     '   private void cancelTurnKeepalive() {',
     KEEPALIVE + '   private void cancelTurnKeepalive() {', 1),
    ('P13 teardown stdTurn 清理',
     '''      this.cancelTurnKeepalive();
      TurnRelayClient.TurnSession session = this.turnSession;
      this.turnSession = null;
      if (session != null) {
         session.unbind();
      }
''',
     '''      this.cancelTurnKeepalive();
      TurnRelayClient.TurnSession session = this.turnSession;
      this.turnSession = null;
      if (session != null) {
         session.unbind();
      }
      StdTurnClient.StdTurnSession stdSession = this.stdTurnSession;
      this.stdTurnSession = null;
      if (stdSession != null) {
         StdTurnClient.close(stdSession);
      }
''', 1),
    ('P14 断线释放条件',
     '            if (this.turnSession != null && clientId.equals(this.turnPeerId)) {',
     '            if ((this.turnSession != null || this.stdTurnSession != null) && clientId.equals(this.turnPeerId)) {', 1),
    ('P15 give-up 保留条件',
     '      if (this.turnSession != null && this.turnTransport != null && this.turnTransport.isConnected()) {',
     '      if ((this.turnSession != null || this.stdTurnSession != null) && this.turnTransport != null && this.turnTransport.isConnected()) {', 1),
]

def main():
    dry = '--dry' in sys.argv
    text = read_norm(REF)
    ok = True
    applied = skipped = 0
    for desc, old_t, new_t, expect in PAIRS:
        cnt = text.count(old_t)
        if cnt == expect:
            text = text.replace(old_t, new_t, 1)
            applied += 1
            print(f'APPLY {desc}')
        elif cnt == 0 and text.count(new_t) >= 1:
            skipped += 1
            print(f'SKIP  {desc}（已应用）')
        else:
            print(f'FAIL  {desc}: old 命中 {cnt} 次（期望 {expect}），new 命中 {text.count(new_t)} 次')
            ok = False
    if not ok:
        print('有失败项，未写入')
        return 1
    if not dry:
        write_keep_eol(REF, text)
        print(f'已写入 26.3（应用 {applied} / 跳过 {skipped}）')
    else:
        print(f'dry：将应用 {applied} / 跳过 {skipped}')
    return 0

if __name__ == '__main__':
    sys.exit(main())
