# -*- coding: utf-8 -*-
"""
2026-08-31 TURN 修复传播：中继失败(turn_ready/turn_bg_punch 白名单+host桥清理+日志) + 按钮闪烁(punchUiStartMs)
从 26.2 参考实现传播到全部 25 模板。锚点 count==1 断言，CRLF/LF 自适应，失败即跳过并汇总。
"""
import os, sys, io

ROOT = os.path.dirname(os.path.abspath(__file__))
LOADERS = ["fabric", "forge", "neoforge"]

# (相对路径, [(old, new), ...])
CM = "src/main/java/icu/wuhui/voxlink/room/ConnectionManager.java"
RM = "src/main/java/icu/wuhui/voxlink/room/RoomManager.java"
AJS = "src/main/java/icu/wuhui/voxlink/ui/AttemptingJoinScreen.java"

CHANGES_CM = [
# CM1 字段
("""   private volatile long connectionStartTimeMs;

   private volatile int connectionTimeoutSec;
""",
"""   private volatile long connectionStartTimeMs;

   /** UI"使用中继"按钮计时基准：打洞首周期只设一次，持续重试轮/ICE重启不重置；0=本会话尚未开始打洞。 */
   private volatile long punchUiStartMs;

   private volatile int connectionTimeoutSec;
"""),
# CM2 getter
("""   /** 打洞开始时刻（UI 的 20 秒"使用中继"按钮计时基准）。 */
   public long getConnectionStartTimeMs() {
      return this.connectionStartTimeMs;
   }
""",
"""   /** 打洞开始时刻（UI 的 20 秒"使用中继"按钮计时基准）。 */
   public long getConnectionStartTimeMs() {
      return this.connectionStartTimeMs;
   }

   /** UI 按钮专用打洞计时基准：会话首周期设一次、轮次/重启不复位；0=尚未开始（防 0/旧值误判成已超 20s）。 */
   public long getPunchUiStartMs() {
      return this.punchUiStartMs;
   }
"""),
# CM3 set-once（cycle==0 站点，靠 joinerSym 上下文与 ICE 站点区分）
("""               this.connectionStartTimeMs = System.currentTimeMillis();

               int timeoutSec = this.punchProfile().connectionTimeoutSec;

               boolean joinerSym = this.stunProbeResult != null && this.stunProbeResult.natType.isSymmetric();
""",
"""               this.connectionStartTimeMs = System.currentTimeMillis();

               if (this.punchUiStartMs == 0L) {

                  this.punchUiStartMs = this.connectionStartTimeMs;

               }

               int timeoutSec = this.punchProfile().connectionTimeoutSec;

               boolean joinerSym = this.stunProbeResult != null && this.stunProbeResult.natType.isSymmetric();
"""),
# CM4 startDualP2P 复位（双 connectionWon.set(false) 为独特锚点）
("""      this.resetContinuousRetryState();

      this.connectionWon.set(false);

      this.connectionWon.set(false);
""",
"""      this.resetContinuousRetryState();

      // 新连接会话：清 UI 按钮计时基准，防止上一会话残留值让"使用中继"按钮立即闪现
      this.punchUiStartMs = 0L;

      this.connectionWon.set(false);

      this.connectionWon.set(false);
"""),
# CM5 选节点日志
("""            List<TurnRelayClient.ProbeResult> probed = TurnRelayClient.probeNodes(nodes).join();
            if (probed.isEmpty() || probed.get(0).rttMs < 0) {
               throw new IllegalStateException("UNREACHABLE");
            }

            return probed.get(0).node;
""",
"""            List<TurnRelayClient.ProbeResult> probed = TurnRelayClient.probeNodes(nodes).join();
            if (probed.isEmpty() || probed.get(0).rttMs < 0) {
               throw new IllegalStateException("UNREACHABLE");
            }

            VoxLinkMod.LOGGER.info("[Turn] guest picked node {} rtt={}ms (nodes={})", probed.get(0).node.id, probed.get(0).rttMs, nodes.size());
            return probed.get(0).node;
"""),
# CM6 bind+alloc 日志
("""            this.turnSession = session;
            this.startTurnKeepalive(session);
            UdpPath.Codec codec = new TurnRelayClient.TurnPathCodec(sid, TurnRelayClient.ROLE_GUEST, TurnRelayClient.ROLE_HOST);
""",
"""            this.turnSession = session;
            this.startTurnKeepalive(session);
            VoxLinkMod.LOGGER.info("[Turn] guest session {} bound via {}:{}, sending turn_alloc", alloc.sessionIdHex.substring(0, 8), alloc.host, alloc.port);
            UdpPath.Codec codec = new TurnRelayClient.TurnPathCodec(sid, TurnRelayClient.ROLE_GUEST, TurnRelayClient.ROLE_HOST);
"""),
# CM7 watchdog 日志
("""                  if (this.turnTransport != null && !this.turnTransport.isConnected() && !this.turnSwitchedToP2p) {
                     this.teardownTurn(state, "voxlink.turn.failed");
                  }
""",
"""                  if (this.turnTransport != null && !this.turnTransport.isConnected() && !this.turnSwitchedToP2p) {
                     VoxLinkMod.LOGGER.warn("[Turn] guest got no turn_ready in 20s (host dead / signal lost), teardown");
                     this.teardownTurn(state, "voxlink.turn.failed");
                  }
"""),
# CM8 turn_ready 发送失败 WARN
("""         .exceptionally(e -> {
            VoxLinkMod.LOGGER.debug("[Turn] turn_ready send failed: {}", e.getMessage());
            return null;
         });
""",
"""         .exceptionally(e -> {
            // turn_ready 发不出去 guest 必然 20s 兜底 teardown：必须 WARN 可见（曾因白名单缺失被 debug 吞掉）
            VoxLinkMod.LOGGER.warn("[Turn] turn_ready send failed: {}", e.getMessage());
            return null;
         });
"""),
# CM9 teardown 入口日志
("""   private void teardownTurn(RoomManager.RoomState state, String failKey) {
      this.turnInProgress = false;
""",
"""   private void teardownTurn(RoomManager.RoomState state, String failKey) {
      VoxLinkMod.LOGGER
         .info("[Turn] teardown: session={}, carrying={}, failKey={}", this.turnSession != null, this.turnTransport != null && this.turnTransport.isConnected(), failKey);
      this.turnInProgress = false;
"""),
# CM10 host 桥断开清理 TURN 会话
("""            VoxLinkMod.LOGGER.warn("[HostBridge] client={} bridge disconnected, reset connectionWon for host re-punch", clientId);

            LogUploadManager.onDisconnected();

            this.connectionWon.set(false);
""",
"""            VoxLinkMod.LOGGER.warn("[HostBridge] client={} bridge disconnected, reset connectionWon for host re-punch", clientId);

            LogUploadManager.onDisconnected();

            // 断的是 TURN 桥：立即释放 TURN 会话。否则 turnSession 残留会静默吞掉
            // 该房客后续重试的 turn_alloc，中继在本房间内永久失效
            if (this.turnSession != null && clientId.equals(this.turnPeerId)) {

               VoxLinkMod.LOGGER.info("[Turn] host bridge down for {}, releasing TURN session", clientId);

               this.teardownTurn(state, null);

            }

            this.connectionWon.set(false);
"""),
# CM11 isTurnPeer
("""   public boolean isTurnP2pGivenUp() {
      return this.turnP2pGivenUp;
   }
""",
"""   public boolean isTurnP2pGivenUp() {
      return this.turnP2pGivenUp;
   }

   /** from 是否为本端当前 TURN 对端（guest 侧恒为 "host"，host 侧为 joiner clientId）。多房客时防误杀他人会话。 */
   public boolean isTurnPeer(String from) {
      return from != null && this.turnSession != null && from.equals(this.turnPeerId);
   }
"""),
# CM12 cleanupTurnQuietly 复位 givenUp
("""         this.turnInProgress = false;
         this.turnPeerId = null;
      } catch (Exception e) {
      }
   }
""",
"""         this.turnInProgress = false;
         this.turnPeerId = null;
         // 会话终结即复位"本周期放弃 P2P 升级"标记（"退出重进重置"的实际落点）
         this.turnP2pGivenUp = false;
      } catch (Exception e) {
      }
   }
"""),
]

CHANGES_RM = [
# RM1 handleDisconnect 守卫
("""      VoxLinkMod.LOGGER.info("Peer disconnected: {}", from);
      this.connectionManager.cleanupTurnQuietly();
""",
"""      VoxLinkMod.LOGGER.info("Peer disconnected: {}", from);
      // 仅当断线者恰是本端 TURN 对端才清理；多房客时其他房客断线不能杀掉存活的 TURN 会话
      if (this.connectionManager.isTurnPeer(from)) {
         this.connectionManager.cleanupTurnQuietly();
      }
"""),
# RM2 cleanupRoomResources 挂 TURN 清理
("""      try {
         this.connectionManager.clearActiveUdpTransports();
      } catch (Exception e) {
         VoxLinkMod.LOGGER.debug("cleanup udp transports error: {}", e.getMessage());
      }
""",
"""      try {
         this.connectionManager.clearActiveUdpTransports();
      } catch (Exception e) {
         VoxLinkMod.LOGGER.debug("cleanup udp transports error: {}", e.getMessage());
      }

      try {
         // 正常退房也要释放 TURN 会话（此前只在收到对端 disconnect 信令时清理，keepalive 会泄漏）
         this.connectionManager.cleanupTurnQuietly();
      } catch (Exception e) {
         VoxLinkMod.LOGGER.debug("cleanup turn error: {}", e.getMessage());
      }
"""),
]

CHANGES_AJS = [
# AJS1 按钮计时
("""                  icu.wuhui.voxlink.room.ConnectionManager cmTurn = VoxLinkMod.getRoomManager().getConnectionManager();
                  long punchMs = System.currentTimeMillis() - cmTurn.getConnectionStartTimeMs();
                  if (punchMs >= 20000L
""",
"""                  // TURN"使用中继"显隐：打洞 ≥20s 先查一次服务端开关（30s 缓存），开启且未在使用/未放弃才显示。
                  // 计时用 punchUiStartMs（会话首周期只设一次）：connectionStartTimeMs 在首周期前为 0/残留旧值
                  // 会让 now-0 天文数字被误判成"已超20s"→按钮刚进屏就闪现又消失；持续重试每轮还会复位再消失20s。
                  // TURN 中途失败 teardown 后 turnInProgress/turnActive 复位，本条件自然重新成立（按钮重现）。
                  icu.wuhui.voxlink.room.ConnectionManager cmTurn = VoxLinkMod.getRoomManager().getConnectionManager();
                  long punchStartMs = cmTurn.getPunchUiStartMs();
                  long punchMs = punchStartMs > 0L ? System.currentTimeMillis() - punchStartMs : 0L;
                  if (punchMs >= 20000L
"""),
]


def apply_changes(path, changes, report, tag):
    if not os.path.isfile(path):
        report.append(("MISSING", path, tag))
        return False
    with io.open(path, "r", encoding="utf-8", newline="") as f:
        text = f.read()
    eol = "\r\n" if "\r\n" in text else "\n"
    ok_all = True
    for i, (old, new) in enumerate(changes, 1):
        old_t = old.replace("\n", eol)
        new_t = new.replace("\n", eol)
        n = text.count(old_t)
        if n != 1:
            report.append(("ANCHOR%d count=%d" % (i, n), path, tag))
            ok_all = False
            continue
        if new_t in text:
            continue  # 已应用过
        text = text.replace(old_t, new_t)
    if ok_all:
        with io.open(path, "w", encoding="utf-8", newline="") as f:
            f.write(text)
    return ok_all


def main():
    report = []
    done, fail = 0, 0
    for loader in LOADERS:
        base = os.path.join(ROOT, loader)
        if not os.path.isdir(base):
            continue
        for ver in sorted(os.listdir(base)):
            tpl = os.path.join(base, ver)
            if not os.path.isdir(os.path.join(tpl, "src")):
                continue
            if loader == "fabric" and ver == "26.2":
                continue  # 参考模板已手改
            tag = "%s/%s" % (loader, ver)
            r1 = apply_changes(os.path.join(tpl, CM), CHANGES_CM, report, tag)
            r2 = apply_changes(os.path.join(tpl, RM), CHANGES_RM, report, tag)
            r3 = apply_changes(os.path.join(tpl, AJS), CHANGES_AJS, report, tag)
            if r1 and r2 and r3:
                done += 1
                print("OK   %s" % tag)
            else:
                fail += 1
                print("FAIL %s" % tag)
    print("\n==== 完成 %d, 失败 %d ====" % (done, fail))
    for kind, path, tag in report:
        print("  [%s] %s (%s)" % (kind, os.path.relpath(path, ROOT), tag))
    sys.exit(1 if fail else 0)


if __name__ == "__main__":
    main()
