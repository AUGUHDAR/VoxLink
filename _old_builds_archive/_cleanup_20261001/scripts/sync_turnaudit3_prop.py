# -*- coding: utf-8 -*-
"""TURN审计修复 全模板传播: RUT整拷 + CM/RM 13锚点(行尾归一, 幂等, 逐模板报告)"""
import os, subprocess

REF262 = r'D:\桌面\voxlink\fabric\26.2\src\main\java\icu\wuhui\voxlink'
BASE = r'D:\桌面\voxlink'
applied, failed = [], []

def norm(b):
    return b.replace(b'\r\r\n', b'\n').replace(b'\r\n', b'\n').replace(b'\r', b'\n')

def patch(path, pairs, tag):
    with open(path, 'rb') as f:
        d = norm(f.read())
    cnt = 0
    for name, pat, rep in pairs:
        pat = pat.encode(); rep = rep.encode()
        if rep in d:
            continue
        if d.count(pat) != 1:
            failed.append(f"{tag}: [{name}] x{d.count(pat)}")
            continue
        d = d.replace(pat, rep, 1); cnt += 1
    with open(path, 'wb') as f:
        f.write(d)
    applied.append(f"{tag}: {cnt}/{len(pairs)}")

CM_PAIRS = [
 ("字段",
  "   private final AtomicBoolean connectionWon = new AtomicBoolean(false);",
  "   private final AtomicBoolean connectionWon = new AtomicBoolean(false);\n   // turn_ready 应用门: 信令双线程(WS push/HTTP poll)并发分发时防双建桥\n   private final AtomicBoolean turnReadyApplied = new AtomicBoolean(false);\n   // 热备保活句柄: teardown 必须取消, 否则每次热备泄漏一个15s任务\n   private volatile ScheduledFuture<?> hotstandbyKeepaliveTask;\n   // TURN bind/桥类长任务专用池, 防止阻塞信令单线程scheduler\n   private static final java.util.concurrent.ExecutorService TURN_BG_EXECUTOR =\n      java.util.concurrent.Executors.newCachedThreadPool(r -> {\n         Thread t = new Thread(r, \"VoxLink-TurnBg\");\n         t.setDaemon(true);\n         return t;\n      });"),
 ("guest code4",
  "            int code = TurnRelayClient.bindWithRetry(session, 3);\n            if (code != TurnRelayClient.BIND_OK) {\n               session.unbind();\n               throw new IllegalStateException(\"BIND_FAILED_\" + code);\n            }",
  "            int code = TurnRelayClient.bindWithRetry(session, 3);\n            // code=4 对齐 host 容忍: 首发响应丢失重放被判冲突, 会话实际已绑定\n            if (code == TurnRelayClient.BIND_ROLE_CONFLICT) {\n               code = TurnRelayClient.BIND_OK;\n               VoxLinkMod.LOGGER.info(\"[Turn] guest bind ROLE_CONFLICT tolerated (session already bound)\");\n            }\n            if (code != TurnRelayClient.BIND_OK) {\n               session.unbind();\n               throw new IllegalStateException(\"BIND_FAILED_\" + code);\n            }"),
 ("bind换池",
  "      this.scheduler.execute(() -> {\n          for (int i = 0; i < 16; i++) {\n             sid[i] = (byte)Integer.parseInt(sessionIdHex.substring(i * 2, i * 2 + 2), 16);\n          }",
  "      TURN_BG_EXECUTOR.execute(() -> {\n          for (int i = 0; i < 16; i++) {\n             sid[i] = (byte)Integer.parseInt(sessionIdHex.substring(i * 2, i * 2 + 2), 16);\n          }"),
 ("直连已赢nack",
  "         VoxLinkMod.LOGGER.info(\"[Turn] host already connected directly, ignore turn_alloc from {}\", from);\n         return;",
  "         VoxLinkMod.LOGGER.info(\"[Turn] host already connected directly, ignore turn_alloc from {}\", from);\n         this.sendTurnNack(state, from, \"direct_won\");\n         return;"),
 ("校验失败nack",
  "      if (sessionIdHex.length() != 32 || host.isEmpty() || port <= 0) {\n         return;\n      }",
  "      if (sessionIdHex.length() != 32 || host.isEmpty() || port <= 0) {\n         this.sendTurnNack(state, from, \"bad_alloc\");\n         return;\n      }"),
 ("bind失败nack",
  "             VoxLinkMod.LOGGER.warn(\"[Turn] host bind failed code={} sid={} endpoint={}:{}\", code, sessionIdHex.substring(0, 8), host, port);\n             session.unbind();\n             return;",
  "             VoxLinkMod.LOGGER.warn(\"[Turn] host bind failed code={} sid={} endpoint={}:{}\", code, sessionIdHex.substring(0, 8), host, port);\n             this.sendTurnNack(state, from, \"bind_failed\");\n             session.unbind();\n             return;"),
 ("turn_ready CAS门",
  "      this.turnInProgress = false;\n      transport.start();",
  "      if (!this.turnReadyApplied.compareAndSet(false, true)) {\n         return;\n      }\n\n      this.turnInProgress = false;\n      transport.start();"),
 ("handleTurnNack定义",
  "   /** TURN 会话保活：15s 一次 KEEPALIVE（节点 90s 无包踢角色）。句柄存字段，teardown 取消。 */",
  "   /** guest 收 turn_nack：host 明示拒绝/无法建立 TURN，提前拆线不等 20s 兜底。 */\n   public void handleTurnNack(String from, JsonObject data) {\n      RoomManager.RoomState state = this.roomManager.currentRoom.get();\n      ReliableUdpTransport transport = this.turnTransport;\n      if (state == null || state == RoomManager.PENDING || transport == null || transport.isConnected()) {\n         return;\n      }\n      String reason = data.has(\"reason\") ? data.get(\"reason\").getAsString() : \"unknown\";\n      VoxLinkMod.LOGGER.warn(\"[Turn] host nack ({}), tear down TURN early\", reason);\n      this.teardownTurn(state, \"voxlink.turn.failed\");\n   }\n\n   private void sendTurnNack(RoomManager.RoomState state, String from, String reason) {\n      try {\n         JsonObject nack = new JsonObject();\n         nack.addProperty(\"clientId\", state.roomInfo.getClientId());\n         nack.addProperty(\"reason\", reason);\n         this.signalingClient\n            .sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), true, \"turn_nack\", nack, from)\n            .exceptionally(e -> {\n               VoxLinkMod.LOGGER.debug(\"[Turn] turn_nack send failed: {}\", e.getMessage());\n               return null;\n            });\n      } catch (Exception e) {\n      }\n   }\n\n   /** TURN 会话保活：15s 一次 KEEPALIVE（节点 90s 无包踢角色）。句柄存字段，teardown 取消。 */"),
 ("teardown重置+取消保活",
  "   private void teardownTurn(RoomManager.RoomState state, String failKey) {",
  "   private void teardownTurn(RoomManager.RoomState state, String failKey) {\n      this.turnReadyApplied.set(false);\n      ScheduledFuture<?> hsk = this.hotstandbyKeepaliveTask;\n      if (hsk != null) {\n         hsk.cancel(false);\n         this.hotstandbyKeepaliveTask = null;\n      }"),
 ("新尝试重置",
  "   public void triggerTurnRelay() {",
  "   public void triggerTurnRelay() {\n      this.turnReadyApplied.set(false);"),
 ("转正分支清turn_host",
  "         this.activeUdpTransports.put(\"relay_cone\", standby);",
  "         this.activeUdpTransports.remove(\"turn_host\");\n         this.activeUdpTransports.put(\"relay_cone\", standby);"),
 ("热备保活句柄保存",
  "               this.scheduler.scheduleAtFixedRate(() -> {\n                  try {\n                     ReliableUdpTransport hs = this.turnHotstandbyTransport;",
  "               this.hotstandbyKeepaliveTask = this.scheduler.scheduleAtFixedRate(() -> {\n                  try {\n                     ReliableUdpTransport hs = this.turnHotstandbyTransport;"),
]

RM_PAIRS = [
 ("分发turn_nack",
  "            case \"turn_ready\":\n               this.connectionManager.handleTurnReady(from, data);\n               break;",
  "            case \"turn_ready\":\n               this.connectionManager.handleTurnReady(from, data);\n               break;\n            case \"turn_nack\":\n               this.connectionManager.handleTurnNack(from, data);\n               break;"),
]

# RUT: HEAD blob 为基准整拷(带 E1-E4 修复版)
def git_show(p):
    return subprocess.run(['git','show','HEAD:'+p], capture_output=True).stdout

REF_RUT = git_show('fabric/26.2/src/main/java/icu/wuhui/voxlink/network/ReliableUdpTransport.java')
FIXED_RUT = norm(open(os.path.join(REF262, 'network', 'ReliableUdpTransport.java'), 'rb').read())
assert b'sendPunchAck(UdpPath' in FIXED_RUT and FIXED_RUT != norm(REF_RUT)

for loader in ('fabric', 'neoforge', 'forge'):
    for d in sorted(os.listdir(os.path.join(BASE, loader))):
        tag = f"{loader}/{d}"
        root = os.path.join(BASE, loader, d, 'src', 'main', 'java', 'icu', 'wuhui', 'voxlink')
        # RUT
        rp = os.path.join(root, 'network', 'ReliableUdpTransport.java')
        cur = norm(open(rp, 'rb').read())
        if cur == FIXED_RUT:
            pass
        elif cur == norm(REF_RUT):
            open(rp, 'wb').write(FIXED_RUT)
            applied.append(f"{tag}: RUT整拷")
        else:
            failed.append(f"{tag}: RUT基准不匹配(内容异于HEAD且非修复版)")
        # CM + RM
        patch(os.path.join(root, 'room', 'ConnectionManager.java'), CM_PAIRS, tag)
        patch(os.path.join(root, 'room', 'RoomManager.java'), RM_PAIRS, tag)

print("=== 成功 ===")
print("\n".join(applied))
print("=== 失败 ===")
print("\n".join(failed) if failed else "(无)")
