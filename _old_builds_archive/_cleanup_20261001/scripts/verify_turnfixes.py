# -*- coding: utf-8 -*-
"""Verify propagated fix snippets are present exactly once and byte-identical to fabric/26.2."""
import os

BASE = r"D:\桌面\voxlink"
CM = "src/main/java/icu/wuhui/voxlink/room/ConnectionManager.java"
RM = "src/main/java/icu/wuhui/voxlink/room/RoomManager.java"
PB = "src/main/java/icu/wuhui/voxlink/network/P2PBridge.java"
REF = ("fabric", "26.2")

SNIPS = {}

SNIPS["CM.1"] = """      if (this.connectionWon.get() && !this.isTurnActive()) {
         this.teardownTurn(state, "voxlink.turn.failed");
         return;
      }

      if (!this.turnReadyApplied.compareAndSet(false, true)) {
         return;
      }
"""

SNIPS["CM.2"] = """      this.activeUdpTransports.clear();

      this.teardownTurn(state, null);

      P2PBridge.connectToHostPreconnected(punched"""

SNIPS["CM.3"] = """   public void handleTurnNack(String from, JsonObject data) {
      RoomManager.RoomState state = this.roomManager.currentRoom.get();
      if (state == null || state == RoomManager.PENDING) {
         return;
      }
      ReliableUdpTransport transport = this.turnTransport;
      if (transport != null && transport.isConnected()) {
         return;
      }
      if (transport == null && !this.turnInProgress) {
         return;
      }
      String reason = data.has("reason") ? data.get("reason").getAsString() : "unknown";
      VoxLinkMod.LOGGER.warn("[Turn] host nack ({}), tear down TURN early", reason);
      this.teardownTurn(state, "voxlink.turn.failed");
   }
"""

SNIPS["CM.4a"] = """   private void cancelHotstandbyKeepalive() {
      ScheduledFuture<?> f = this.hotstandbyKeepaliveTask;
      if (f != null) {
         f.cancel(false);
         this.hotstandbyKeepaliveTask = null;
      }
   }
"""

SNIPS["CM.4b"] = """               this.cancelHotstandbyKeepalive();
               this.hotstandbyKeepaliveTask = this.scheduler.scheduleAtFixedRate(() -> {"""

SNIPS["CM.4c"] = """      this.turnSwitchedToP2p = true;
      this.cancelTurnBgMonitor();
      this.cancelTurnKeepalive();
      this.cancelHotstandbyKeepalive();
"""

SNIPS["CM.5"] = """      } catch (Exception e) {
         VoxLinkMod.LOGGER.debug("[Turn] turn_nack error: {}", String.valueOf(e));
      }
   }
"""

SNIPS["RM.7a"] = """   // WS补拉标记: 下次轮询强制HTTP
   private final AtomicBoolean forcePollOnce = new AtomicBoolean(false);
"""

SNIPS["RM.7b"] = """      this.registerSignalPushHandler();
      // WS 重连成功后单次补拉
      this.signalingClient.setWsReconnectedListener(this::catchUpSignalOnce);
"""

SNIPS["RM.7c"] = """   /** WS 重连后单次补拉。 */
   public void catchUpSignalOnce() {
      RoomManager.RoomState state = this.currentRoom.get();
      if (state == null || state == PENDING) {
         return;
      }
      this.forcePollOnce.set(true);
      this.scheduler.execute(this::doSignalPoll);
   }
"""

SNIPS["RM.8"] = """         // WS 健康即休眠：零请求，仅校正节拍
         if (this.signalingClient.isWsConnected() && !this.forcePollOnce.getAndSet(false)) {
            this.recoverSignalPollInterval();
            return;
         }
"""

SNIPS["PB.9"] = """         if (jp > 0) {
            try {
               punched.close();
            } catch (Exception ignored) {
            }
            LOGGER.warn("Joiner: bridge already on {}, reuse it, close new punched socket", jp);
            return CompletableFuture.completedFuture(jp);
         }

         disconnect();
      }
"""

FILE_OF = {k: {"CM": CM, "RM": RM, "PB": PB}[k.split(".")[0]] for k in SNIPS}

# also verify the OLD (pre-fix) forms are gone
OLD = {
    "CM.3": "guest 收 turn_nack：host 明示拒绝/无法建立 TURN，提前拆线不等 20s 兜底。",
    "RM.8": None,  # handled by dup check below
}
RM_DUP = """         // WS 健康即休眠：零请求，仅校正节拍
         if (this.signalingClient.isWsConnected()) {
            this.recoverSignalPollInterval();
            return;
         }

         // WS 健康即休眠：零请求，仅校正节拍
         if (this.signalingClient.isWsConnected()) {
            this.recoverSignalPollInterval();
            return;
         }
"""


def load(l, v, rel):
    with open(os.path.join(BASE, l, v, rel), "rb") as f:
        return f.read().decode("utf-8").replace("\r\n", "\n")


bad = 0
for l in ["fabric", "forge", "neoforge"]:
    for v in sorted(os.listdir(os.path.join(BASE, l))):
        if (l, v) == REF:
            continue
        texts = {rel: load(l, v, rel) for rel in {CM, RM, PB}}
        for k, s in SNIPS.items():
            rel = FILE_OF[k]
            n = texts[rel].count(s)
            if n != 1:
                print(f"BAD {l}/{v} {k}: count={n}")
                bad += 1
        if texts[RM].count(RM_DUP) != 0:
            print(f"BAD {l}/{v} RM.dup-block still present")
            bad += 1
        if "guest 收 turn_nack：host 明示拒绝/无法建立 TURN" in texts[CM]:
            print(f"BAD {l}/{v} CM.3 old comment still present")
            bad += 1

print("VERIFY:", "ALL 12 SNIPPETS x26 TEMPLARS OK" if bad == 0 else f"{bad} problems")
