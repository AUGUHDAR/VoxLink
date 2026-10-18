# -*- coding: utf-8 -*-
"""Propagate fabric/26.2 TURN/poll/punched-socket fixes to the other 25+1 templates.
Anchor-based, count-asserted. No positional inserts. Never touches fabric/26.2."""
import os, re, sys

BASE = r"D:\桌面\voxlink"
CM = "src/main/java/icu/wuhui/voxlink/room/ConnectionManager.java"
RM = "src/main/java/icu/wuhui/voxlink/room/RoomManager.java"
PB = "src/main/java/icu/wuhui/voxlink/network/P2PBridge.java"
REF = ("fabric", "26.2")

results = {}   # (l,v) -> {fixid: status}
failures = []


def load(path):
    with open(path, "rb") as f:
        d = f.read()
    txt = d.decode("utf-8")
    crlf = "\r\n" in txt
    if crlf:
        txt = txt.replace("\r\n", "\n")
    return txt, crlf


def save(path, txt, crlf):
    if crlf:
        txt = txt.replace("\n", "\r\n")
    with open(path, "wb") as f:
        f.write(txt.encode("utf-8"))


def rep1(txt, old, new, fixid, tag):
    """Replace exactly-once occurrence; returns (txt, status)."""
    n = txt.count(old)
    if n != 1:
        return txt, f"ANCHORFAIL(count={n})"
    return txt.replace(old, new, 1), "OK"


def insert_after(txt, anchor, insertion, fixid, tag):
    n = txt.count(anchor)
    if n != 1:
        return txt, f"ANCHORFAIL(count={n})"
    return txt.replace(anchor, anchor + insertion, 1), "OK"


def insert_before(txt, anchor, insertion, fixid, tag):
    n = txt.count(anchor)
    if n != 1:
        return txt, f"ANCHORFAIL(count={n})"
    return txt.replace(anchor, insertion + anchor, 1), "OK"


# ---------------- ConnectionManager fixes ----------------

CM_READY_OLD = """      if (!this.turnReadyApplied.compareAndSet(false, true)) {
"""
CM_READY_GUARD = """      if (this.connectionWon.get() && !this.isTurnActive()) {
         this.teardownTurn(state, "voxlink.turn.failed");
         return;
      }

"""

CM_NACK_OLD = """   /** guest 收 turn_nack：host 明示拒绝/无法建立 TURN，提前拆线不等 20s 兜底。 */
   public void handleTurnNack(String from, JsonObject data) {
      RoomManager.RoomState state = this.roomManager.currentRoom.get();
      ReliableUdpTransport transport = this.turnTransport;
      if (state == null || state == RoomManager.PENDING || transport == null || transport.isConnected()) {
         return;
      }
      String reason = data.has("reason") ? data.get("reason").getAsString() : "unknown";
      VoxLinkMod.LOGGER.warn("[Turn] host nack ({}), tear down TURN early", reason);
      this.teardownTurn(state, "voxlink.turn.failed");
   }
"""
CM_NACK_NEW = """   /** guest 收 turn_nack：提前拆线。 */
   public void handleTurnNack(String from, JsonObject data) {
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

CM_NACK_CATCH_OLD = """               VoxLinkMod.LOGGER.debug("[Turn] turn_nack send failed: {}", e.getMessage());
               return null;
            });
      } catch (Exception e) {
      }
   }
"""
CM_NACK_CATCH_NEW = """               VoxLinkMod.LOGGER.debug("[Turn] turn_nack send failed: {}", e.getMessage());
               return null;
            });
      } catch (Exception e) {
         VoxLinkMod.LOGGER.debug("[Turn] turn_nack error: {}", String.valueOf(e));
      }
   }
"""

CM_CANCEL_KEEPALIVE = """   private void cancelTurnKeepalive() {
      ScheduledFuture<?> f = this.turnKeepaliveTask;
      if (f != null) {
         f.cancel(false);
         this.turnKeepaliveTask = null;
      }
   }
"""
CM_CANCEL_HS = """
   private void cancelHotstandbyKeepalive() {
      ScheduledFuture<?> f = this.hotstandbyKeepaliveTask;
      if (f != null) {
         f.cancel(false);
         this.hotstandbyKeepaliveTask = null;
      }
   }"""

CM_HS_ASSIGN = "               this.hotstandbyKeepaliveTask = this.scheduler.scheduleAtFixedRate(() -> {"
CM_HS_CANCEL_CALL = "               this.cancelHotstandbyKeepalive();\n"

CM_FINISH_OLD = """      this.turnSwitchedToP2p = true;
      this.cancelTurnBgMonitor();
      this.cancelTurnKeepalive();
"""
CM_FINISH_NEW = """      this.turnSwitchedToP2p = true;
      this.cancelTurnBgMonitor();
      this.cancelTurnKeepalive();
      this.cancelHotstandbyKeepalive();
"""

CM_TCP_PAT = re.compile(r"(this\.activeUdpTransports\.clear\(\);)\n+(\s*P2PBridge\.connectToHostPreconnected\(punched)")
CM_TCP_REP = r"\1\n\n      this.teardownTurn(state, null);\n\n\2"


def fix_cm(txt):
    st = {}

    # FIX1: handleTurnReady connectionWon guard
    m = re.search(r"public void handleTurnReady\(String from, JsonObject data\) \{.*?\n   \}", txt, re.S)
    if m and "connectionWon.get() && !this.isTurnActive()" in m.group(0):
        st["1"] = "ALREADY"
    else:
        txt, s = insert_before(txt, CM_READY_OLD, CM_READY_GUARD, "1", "readyGuard")
        st["1"] = s

    # FIX2: connectViaPunchedTcp teardownTurn after clear()
    if re.search(r"activeUdpTransports\.clear\(\);\n+\s*this\.teardownTurn\(state, null\);", txt):
        st["2"] = "ALREADY"
    else:
        ms = CM_TCP_PAT.findall(txt)
        if len(ms) != 1:
            st["2"] = f"ANCHORFAIL(count={len(ms)})"
        else:
            txt = CM_TCP_PAT.sub(CM_TCP_REP, txt, count=1)
            st["2"] = "OK"

    # FIX3: handleTurnNack whole-method replace (incl. comment compression)
    if "transport == null && !this.turnInProgress" in txt:
        st["3"] = "ALREADY"
    else:
        txt, s = rep1(txt, CM_NACK_OLD, CM_NACK_NEW, "3", "nack")
        st["3"] = s

    # FIX4a: cancelHotstandbyKeepalive method after cancelTurnKeepalive
    if "cancelHotstandbyKeepalive" in txt:
        st["4a"] = "ALREADY"
    else:
        txt, s = rep1(txt, CM_CANCEL_KEEPALIVE + "\n", CM_CANCEL_KEEPALIVE + CM_CANCEL_HS + "\n", "4a", "hsMethod")
        st["4a"] = s

    # FIX4b: cancel before hotstandby keepalive assignment
    if txt.count(CM_HS_CANCEL_CALL.strip()) and "cancelHotstandbyKeepalive();\n               this.hotstandbyKeepaliveTask" in txt:
        st["4b"] = "ALREADY"
    else:
        txt, s = insert_before(txt, CM_HS_ASSIGN, CM_HS_CANCEL_CALL, "4b", "hsAssign")
        st["4b"] = s

    # FIX4c: finishTurnRelease cancel
    if CM_FINISH_NEW in txt:
        st["4c"] = "ALREADY"
    else:
        txt, s = rep1(txt, CM_FINISH_OLD, CM_FINISH_NEW, "4c", "finish")
        st["4c"] = s

    # FIX5: sendTurnNack catch debug log
    if "turn_nack error: {}" in txt:
        st["5"] = "ALREADY"
    else:
        txt, s = rep1(txt, CM_NACK_CATCH_OLD, CM_NACK_CATCH_NEW, "5", "nackCatch")
        st["5"] = s

    return txt, st


# ---------------- RoomManager fixes ----------------

RM_FIELD_ANCHOR = "   private final AtomicBoolean signalPollInFlight = new AtomicBoolean(false);\n"
RM_FIELD_INS = """   // WS补拉标记: 下次轮询强制HTTP
   private final AtomicBoolean forcePollOnce = new AtomicBoolean(false);
"""

RM_REG_ANCHOR = "      this.registerSignalPushHandler();\n"
RM_REG_INS = """      // WS 重连成功后单次补拉
      this.signalingClient.setWsReconnectedListener(this::catchUpSignalOnce);
"""

RM_SCHED_ANCHOR = """      this.signalPollFuture = this.scheduler.scheduleAtFixedRate(this::doSignalPoll, interval, interval, TimeUnit.MILLISECONDS);
   }
"""
RM_CATCHUP_INS = """
   /** WS 重连后单次补拉。 */
   public void catchUpSignalOnce() {
      RoomManager.RoomState state = this.currentRoom.get();
      if (state == null || state == PENDING) {
         return;
      }
      this.forcePollOnce.set(true);
      this.scheduler.execute(this::doSignalPoll);
   }
"""

RM_WS_BLOCK = """         // WS 健康即休眠：零请求，仅校正节拍
         if (this.signalingClient.isWsConnected()) {
            this.recoverSignalPollInterval();
            return;
         }
"""
RM_WS_DUP = RM_WS_BLOCK + "\n" + RM_WS_BLOCK
RM_WS_NEW = """         // WS 健康即休眠：零请求，仅校正节拍
         if (this.signalingClient.isWsConnected() && !this.forcePollOnce.getAndSet(false)) {
            this.recoverSignalPollInterval();
            return;
         }
"""


def fix_rm(txt):
    st = {}

    if "catchUpSignalOnce" in txt:
        st["7"] = "ALREADY"
        st["8"] = "ALREADY" if "forcePollOnce.getAndSet(false)" in txt else "MISSING8"
        return txt, st

    # FIX7a: field
    txt, s = insert_after(txt, RM_FIELD_ANCHOR, RM_FIELD_INS, "7a", "field")
    st["7a"] = s

    # FIX7b: WS reconnect listener registration
    txt, s = insert_after(txt, RM_REG_ANCHOR, RM_REG_INS, "7b", "listener")
    st["7b"] = s

    # FIX7c: catchUpSignalOnce method after scheduleSignalPoll
    txt, s = rep1(txt, RM_SCHED_ANCHOR, RM_SCHED_ANCHOR + RM_CATCHUP_INS, "7c", "method")
    st["7c"] = s
    st["7"] = "OK" if (st.get("7a") == "OK" and st.get("7b") == "OK" and st.get("7c") == "OK") else "PARTIAL"

    # FIX8: doSignalPoll WS short-circuit guard (+ de-dup)
    if txt.count(RM_WS_DUP) == 1:
        txt = txt.replace(RM_WS_DUP, RM_WS_NEW, 1)
        st["8"] = "OK"
    else:
        single = txt.count(RM_WS_BLOCK)
        if single == 1:
            txt, s = rep1(txt, RM_WS_BLOCK, RM_WS_NEW, "8", "wsblock")
            st["8"] = s
        else:
            st["8"] = f"ANCHORFAIL(dup={txt.count(RM_WS_DUP)},single={single})"

    return txt, st


# ---------------- P2PBridge fix ----------------

PB_OLD = """   public static synchronized CompletableFuture<Integer> connectToHostPreconnected(Socket punched, String hostIp, int hostPort) {
      cancelled.set(false);
      tcpJoinerBridgeConnectedV4.set(false);
      if (isRunning()) {
         int jp = joinerPort;
         if (jp > 0) {
            return CompletableFuture.completedFuture(jp);
         }

         disconnect();
      }
"""
PB_NEW = """   public static synchronized CompletableFuture<Integer> connectToHostPreconnected(Socket punched, String hostIp, int hostPort) {
      cancelled.set(false);
      tcpJoinerBridgeConnectedV4.set(false);
      if (isRunning()) {
         int jp = joinerPort;
         if (jp > 0) {
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


def fix_pb(txt):
    st = {}
    if "reuse it, close new punched socket" in txt:
        st["9"] = "ALREADY"
    else:
        txt, s = rep1(txt, PB_OLD, PB_NEW, "9", "punched")
        st["9"] = s
    return txt, st


def main():
    dry = "--dry" in sys.argv
    only = None
    for a in sys.argv[1:]:
        if a.startswith("--only="):
            only = a.split("=", 1)[1]
    targets = []
    for l in ["fabric", "forge", "neoforge"]:
        for v in sorted(os.listdir(os.path.join(BASE, l))):
            if (l, v) == REF:
                continue
            targets.append((l, v))

    for (l, v) in targets:
        key = f"{l}/{v}"
        if only and key != only:
            continue
        root = os.path.join(BASE, l, v)
        row = {}
        for name, rel, fn in [("CM", CM, fix_cm), ("RM", RM, fix_rm), ("PB", PB, fix_pb)]:
            p = os.path.join(root, rel)
            txt, crlf = load(p)
            txt2, st = fn(txt)
            for k, s in st.items():
                row[f"{name}.{k}"] = s
                if s.startswith("ANCHORFAIL") or s.startswith("MISSING"):
                    failures.append((key, name, k, s))
            if txt2 != txt and not dry:
                save(p, txt2, crlf)
        results[key] = row

    # print matrix
    ids = ["CM.1", "CM.2", "CM.3", "CM.4a", "CM.4b", "CM.4c", "CM.5",
           "RM.7a", "RM.7b", "RM.7c", "RM.8", "PB.9"]
    print("template\t" + "\t".join(ids))
    for key in sorted(results):
        r = results[key]
        print(key + "\t" + "\t".join(r.get(i, "-") for i in ids))
    print()
    if failures:
        print("FAILURES:")
        for f in failures:
            print("  ", f)
    else:
        print("no anchor failures")
    nfiles = sum(1 for key in results for i in ids if results[key].get(i) == "OK")
    print(f"applied fix instances: {nfiles}")


if __name__ == "__main__":
    main()
