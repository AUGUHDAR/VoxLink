#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
sync_punch113_20260830.py

Propagate two punch-optimization changes from the master template
(fabric/1.21.11) to all 25 templates (fabric/forge/neoforge x MC versions):

  P0 (PREDICTION_OFF cap=50):
    - New session-level counter `sessionPredictionOffCount` + constant
      `PREDICTION_OFF_CAP = 50` (anchored after ZERO_RECV_FINAL_ROUND_LIMIT).
    - New helper `recordPunchFailure(reason, recvPunch, recvAck)` that
      preserves the original "[ConnectionManager] Punch failed reason=... "
      log and increments the counter on PREDICTION_OFF (logging a
      "[UdpHolePuncher] PREDICTION_OFF cap reached (N), abort punch" line
      once it hits the cap).
    - Replace 8 existing LOGGER.info("[ConnectionManager] Punch failed
      reason=...") sites with this.recordPunchFailure(...) -- same indent
      per site, so each anchor has count==1.
    - Add cap-check at top of `runConnectionCycle` else-branch: when cap
      reached, transitionTo(FAILED) + showConnectFailed to short-circuit.

  P5 (cancel 85s global timeout on success):
    - At "[Connection] Already connected, skip UDP punch" site AND at
      "[Connection] Connected, skip Wave step" site: cancel
      connectionTimeoutFuture (mirrors the existing pattern in
      killAllConnectionAttempts).

Strategy (per repo convention: single-line anchor preferred, count==1
asserted, newline='' for I/O, CRLF tolerated via LF-normalized match):
  * Each anchor is a small block (1-5 lines), the LF-normalized count
    must equal exactly 1 to assert idempotency safely.
  * On second run all anchors are absent (already applied) and the script
    is a no-op.

For each fix point the script asserts count==1 before replacement
(idempotent: a second run finds zero matches and is treated as a no-op).

After replacement, every file is overwritten preserving original line
endings.
"""

import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))

TEMPLATES = sorted(
    [os.path.join("fabric", v) for v in os.listdir(os.path.join(ROOT, "fabric"))]
    + [os.path.join("forge", v) for v in os.listdir(os.path.join(ROOT, "forge"))]
    + [os.path.join("neoforge", v) for v in os.listdir(os.path.join(ROOT, "neoforge"))]
)
assert len(TEMPLATES) == 25, f"expect 25 templates, got {len(TEMPLATES)}"


def read_file_bytes(rel_path: str) -> bytes:
    fp = os.path.join(ROOT, rel_path)
    with io.open(fp, "rb") as f:
        return f.read()


def write_file_bytes(rel_path: str, data: bytes) -> None:
    fp = os.path.join(ROOT, rel_path)
    with io.open(fp, "wb") as f:
        f.write(data)


def detect_eol(data: bytes) -> bytes:
    """Return b'\\r\\n' if file uses CRLF predominantly, else b'\\n'."""
    crlf = data.count(b"\r\n")
    lf = data.count(b"\n") - crlf
    return b"\r\n" if crlf >= lf else b"\n"


def replace_with_eol(rel_path: str, old_lines: str, new_lines: str, label: str) -> bool:
    """Replace `old_lines` (joined with \\n) with `new_lines` (joined with \\n),
    in a file that may use CRLF or LF. count==1 must hold. Idempotent: if the
    new block already exists (in either form), returns False (no-op)."""
    data = read_file_bytes(rel_path)
    eol = detect_eol(data)

    # Normalize current file to LF for matching
    text = data.decode("utf-8")
    text_lf = text.replace("\r\n", "\n")

    old_lf = old_lines.replace("\r\n", "\n")
    new_lf = new_lines.replace("\r\n", "\n")

    if new_lf in text_lf:
        return False
    cnt = text_lf.count(old_lf)
    if cnt == 0:
        print(f"  SKIP (no anchor)  {label}  ::  {rel_path}")
        return False
    assert cnt == 1, f"{rel_path}: anchor count={cnt} for {label}"

    # Replace in LF-normalized text, then convert back to original EOL
    text_lf_new = text_lf.replace(old_lf, new_lf, 1)
    if eol == b"\r\n":
        text_new = text_lf_new.replace("\n", "\r\n")
    else:
        text_new = text_lf_new

    write_file_bytes(rel_path, text_new.encode("utf-8"))
    return True


# ----------------------------------------------------------------------
# P0 anchors
# ----------------------------------------------------------------------

# Anchor 1: insert field + constant block right after
#   `private final AtomicBoolean sessionPunchRecvEver = new AtomicBoolean(false);`
ANCHOR_P0_FIELD = (
    "private final AtomicBoolean sessionPunchRecvEver = new AtomicBoolean(false);\n"
)

REPL_P0_FIELD = (
    "private final AtomicBoolean sessionPunchRecvEver = new AtomicBoolean(false);\n"
    "\n"
    "   // PREDICTION_OFF 封顶: birthday attack 风暴(joiner 进程/网络异常导致 host 永收不到 PUNCH 时)\n"
    "   // 同会话连续累计 50 次 PREDICTION_OFF 后提前终止打洞, 防止 2 秒 2520 次 PUNCH / 420 次日志烧带宽 + 刷爆日志。\n"
    "   // 与 ZERO_RECV_FINAL_ROUND_LIMIT 正交: 后者管\"整个会话零收包\", 本常量管\"PREDICTION_OFF 累计频次\"。\n"
    "   private static final int PREDICTION_OFF_CAP = 50;\n"
    "\n"
    "   private final AtomicInteger sessionPredictionOffCount = new AtomicInteger(0);\n"
)


# Anchor 2: helper method recordPunchFailure inserted right before
#   `private void scheduleConnectionTimeout(RoomManager.RoomState state, int timeoutSec) {`
ANCHOR_P0_METHOD = "private void scheduleConnectionTimeout(RoomManager.RoomState state, int timeoutSec) {\n"

REPL_P0_METHOD = (
    "// PREDICTION_OFF 封顶记账: 每次 punch 失败调用此方法, 若 reason=PREDICTION_OFF 则自增会话级计数。\n"
    "   // 原始日志保留, 维持可观测性。abort 由调用方在每个 site 决定(本方法只负责计数 + 撞线日志)。\n"
    "   private void recordPunchFailure(PunchFailureClassifier.FailureReason reason, int recvPunch, int recvAck) {\n"
    "      VoxLinkMod.LOGGER.info(\n"
    "         \"[ConnectionManager] Punch failed reason={} recvPunch={} recvAck={}\",\n"
    "         new Object[]{reason, recvPunch, recvAck}\n"
    "      );\n"
    "      if (reason == PunchFailureClassifier.FailureReason.PREDICTION_OFF) {\n"
    "         int count = this.sessionPredictionOffCount.incrementAndGet();\n"
    "         if (count >= PREDICTION_OFF_CAP) {\n"
    "            VoxLinkMod.LOGGER.warn(\n"
    "               \"[UdpHolePuncher] PREDICTION_OFF cap reached ({}), abort punch\",\n"
    "               count\n"
    "            );\n"
    "         }\n"
    "      }\n"
    "   }\n"
    "\n"
    "\n"
    "\n"
    "   private void scheduleConnectionTimeout(RoomManager.RoomState state, int timeoutSec) {\n"
)


# Anchors 3-10: replace each of 8 LOGGER.info("[ConnectionManager] Punch failed reason=..." )
# sites with this.recordPunchFailure(...). Each site has a unique indentation block.

ANCHOR_P0_SITE_1 = (
    "VoxLinkMod.LOGGER\n"
    "\n"
    "                                       .info(\n"
    "\n"
    "                                          \"[ConnectionManager] Punch failed reason={} recvPunch={} recvAck={}\",\n"
    "\n"
    "                                          new Object[]{reason, result.socketsReceivedPunch, result.socketsReceivedAck}\n"
    "\n"
    "                                       );\n"
    "\n"
    "                                 } else {\n"
    "\n"
    "                                    DatagramSocket socket = result.getSuccessSocket();\n"
    "\n"
    "                                    if (!this.connectionWon.compareAndSet(false, true)) {\n"
    "\n"
    "                                       try {\n"
    "\n"
    "                                          extraPuncher.close();\n"
)

REPL_P0_SITE_1 = (
    "this.recordPunchFailure(reason, result.socketsReceivedPunch, result.socketsReceivedAck);\n"
    "\n"
    "                                 } else {\n"
    "\n"
    "                                    DatagramSocket socket = result.getSuccessSocket();\n"
    "\n"
    "                                    if (!this.connectionWon.compareAndSet(false, true)) {\n"
    "\n"
    "                                       try {\n"
    "\n"
    "                                          extraPuncher.close();\n"
)


ANCHOR_P0_SITE_2 = (
    "VoxLinkMod.LOGGER\n"
    "\n"
    "                                             .info(\n"
    "\n"
    "                                                \"[ConnectionManager] Punch failed reason={} recvPunch={} recvAck={}\",\n"
    "\n"
    "                                                new Object[]{reason, result.socketsReceivedPunch, result.socketsReceivedAck}\n"
    "\n"
    "                                             );\n"
    "\n"
    "                                       } else {\n"
    "\n"
    "                                          DatagramSocket socket = result.getSuccessSocket();\n"
    "\n"
    "                                          if (!hostPunchWon.compareAndSet(false, true)) {\n"
)

REPL_P0_SITE_2 = (
    "this.recordPunchFailure(reason, result.socketsReceivedPunch, result.socketsReceivedAck);\n"
    "\n"
    "                                       } else {\n"
    "\n"
    "                                          DatagramSocket socket = result.getSuccessSocket();\n"
    "\n"
    "                                          if (!hostPunchWon.compareAndSet(false, true)) {\n"
)


ANCHOR_P0_SITE_3 = (
    "VoxLinkMod.LOGGER\n"
    "\n"
    "                                          .info(\n"
    "\n"
    "                                             \"[ConnectionManager] Punch failed reason={} recvPunch={} recvAck={}\",\n"
    "\n"
    "                                             new Object[]{reason, result.socketsReceivedPunch, result.socketsReceivedAck}\n"
    "\n"
    "                                          );\n"
    "\n"
    "                                    } else {\n"
    "\n"
    "                                       DatagramSocket socket = result.getSuccessSocket();\n"
    "\n"
    "                                       if (this.roomManager.currentRoom.get() == fState && this.connectionWon.compareAndSet(false, true)) {\n"
)

REPL_P0_SITE_3 = (
    "this.recordPunchFailure(reason, result.socketsReceivedPunch, result.socketsReceivedAck);\n"
    "\n"
    "                                    } else {\n"
    "\n"
    "                                       DatagramSocket socket = result.getSuccessSocket();\n"
    "\n"
    "                                       if (this.roomManager.currentRoom.get() == fState && this.connectionWon.compareAndSet(false, true)) {\n"
)


ANCHOR_P0_SITE_4 = (
    "VoxLinkMod.LOGGER\n"
    "\n"
    "                                       .info(\n"
    "\n"
    "                                          \"[ConnectionManager] Punch failed reason={} recvPunch={} recvAck={}\",\n"
    "\n"
    "                                          new Object[]{reason, result.socketsReceivedPunch, result.socketsReceivedAck}\n"
    "\n"
    "                                       );\n"
    "\n"
    "                                 } else {\n"
    "\n"
    "                                    DatagramSocket socket = result.getSuccessSocket();\n"
    "\n"
    "                                    if (this.roomManager.currentRoom.get() == state && this.connectionWon.compareAndSet(false, true)) {\n"
    "\n"
    "                                       VoxLinkMod.LOGGER.info(\"[ReversePunch] Joiner reverse punch success {}:{}\", fHostMappedIp, fHostMappedPort);\n"
)

REPL_P0_SITE_4 = (
    "this.recordPunchFailure(reason, result.socketsReceivedPunch, result.socketsReceivedAck);\n"
    "\n"
    "                                 } else {\n"
    "\n"
    "                                    DatagramSocket socket = result.getSuccessSocket();\n"
    "\n"
    "                                    if (this.roomManager.currentRoom.get() == state && this.connectionWon.compareAndSet(false, true)) {\n"
    "\n"
    "                                       VoxLinkMod.LOGGER.info(\"[ReversePunch] Joiner reverse punch success {}:{}\", fHostMappedIp, fHostMappedPort);\n"
)


ANCHOR_P0_SITE_5 = (
    "VoxLinkMod.LOGGER\n"
    "\n"
    "                              .info(\n"
    "\n"
    "                                 \"[ConnectionManager] Punch failed reason={} recvPunch={} recvAck={}\",\n"
    "\n"
    "                                 new Object[]{reason, result.socketsReceivedPunch, result.socketsReceivedAck}\n"
    "\n"
    "                              );\n"
    "\n"
    "                        } else {\n"
    "\n"
    "                           DatagramSocket socket = result.getSuccessSocket();\n"
    "\n"
    "                           if (this.roomManager.currentRoom.get() == state && this.connectionWon.compareAndSet(false, true)) {\n"
)

REPL_P0_SITE_5 = (
    "this.recordPunchFailure(reason, result.socketsReceivedPunch, result.socketsReceivedAck);\n"
    "\n"
    "                        } else {\n"
    "\n"
    "                           DatagramSocket socket = result.getSuccessSocket();\n"
    "\n"
    "                           if (this.roomManager.currentRoom.get() == state && this.connectionWon.compareAndSet(false, true)) {\n"
)


ANCHOR_P0_SITE_6 = (
    "VoxLinkMod.LOGGER\n"
    "\n"
    "                           .info(\n"
    "\n"
    "                              \"[ConnectionManager] Punch failed reason={} recvPunch={} recvAck={}\",\n"
    "\n"
    "                              new Object[]{reason, result.socketsReceivedPunch, result.socketsReceivedAck}\n"
    "\n"
    "                           );\n"
    "\n"
    "                     } else {\n"
    "\n"
    "                        DatagramSocket socket = result.getSuccessSocket();\n"
    "\n"
    "                        if (!this.connectionWon.compareAndSet(false, true)) {\n"
)

REPL_P0_SITE_6 = (
    "this.recordPunchFailure(reason, result.socketsReceivedPunch, result.socketsReceivedAck);\n"
    "\n"
    "                     } else {\n"
    "\n"
    "                        DatagramSocket socket = result.getSuccessSocket();\n"
    "\n"
    "                        if (!this.connectionWon.compareAndSet(false, true)) {\n"
)


ANCHOR_P0_SITE_7 = (
    "VoxLinkMod.LOGGER\n"
    "\n"
    "                                 .info(\n"
    "\n"
    "                                    \"[ConnectionManager] Punch failed reason={} recvPunch={} recvAck={}\",\n"
    "\n"
    "                                    new Object[]{reason, result.socketsReceivedPunch, result.socketsReceivedAck}\n"
    "\n"
    "                                 );\n"
    "\n"
    "                           } else {\n"
)

REPL_P0_SITE_7 = (
    "this.recordPunchFailure(reason, result.socketsReceivedPunch, result.socketsReceivedAck);\n"
    "\n"
    "                           } else {\n"
)


ANCHOR_P0_SITE_8 = (
    "VoxLinkMod.LOGGER\n"
    "\n"
    "                                    .info(\n"
    "\n"
    "                                       \"[ConnectionManager] Punch failed reason={} recvPunch={} recvAck={}\",\n"
    "\n"
    "                                       new Object[]{reason, result.socketsReceivedPunch, result.socketsReceivedAck}\n"
    "\n"
    "                                    );\n"
    "\n"
    "                              } else {\n"
)

REPL_P0_SITE_8 = (
    "this.recordPunchFailure(reason, result.socketsReceivedPunch, result.socketsReceivedAck);\n"
    "\n"
    "                              } else {\n"
)


# Anchor 11: insert cap-check at top of runConnectionCycle else-branch.
# Anchor = the `else {` opening of the connection-won guard.
ANCHOR_P0_CAPCHECK = (
    "if (this.connectionWon.get()) {\n"
    "\n"
    "         VoxLinkMod.LOGGER.info(\"[Connection] Connected, skip cycle {}\", cycle + 1);\n"
    "\n"
    "      } else {\n"
    "\n"
    "         int maxCycles = this.getEffectiveMaxCycles();\n"
)

REPL_P0_CAPCHECK = (
    "if (this.connectionWon.get()) {\n"
    "\n"
    "         VoxLinkMod.LOGGER.info(\"[Connection] Connected, skip cycle {}\", cycle + 1);\n"
    "\n"
    "      } else {\n"
    "\n"
    "         // PREDICTION_OFF 封顶快速失败: birthday attack 风暴下避免无限空转 + 日志被刷爆。\n"
    "         // 与 ZERO_RECV_FINAL_ROUND_LIMIT 正交, 此处用相同终态结论确保对端停手 + 进入 fallback。\n"
    "         if (this.sessionPredictionOffCount.get() >= PREDICTION_OFF_CAP) {\n"
    "            VoxLinkMod.LOGGER.warn(\"[UdpHolePuncher] PREDICTION_OFF cap reached ({}), abort punch\", this.sessionPredictionOffCount.get());\n"
    "            ConnectionState.transitionTo(ConnectionState.FAILED, \"PREDICTION_OFF 封顶\");\n"
    "            this.showConnectFailed(state, \"voxlink.connection.max_cycles_exceeded\");\n"
    "         } else {\n"
    "\n"
    "         int maxCycles = this.getEffectiveMaxCycles();\n"
)


# Anchor 12: close the new `if/else` at end of method (one extra `}` before runConnectionCycle closing brace)
ANCHOR_P0_CAPCHECK_CLOSE = (
    "this.tryConnectionStep(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles, 0);\n"
    "\n"
    "         }\n"
    "\n"
    "      }\n"
    "\n"
    "   }\n"
)

REPL_P0_CAPCHECK_CLOSE = (
    "this.tryConnectionStep(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles, 0);\n"
    "\n"
    "         }\n"
    "\n"
    "         }\n"
    "\n"
    "      }\n"
    "\n"
    "   }\n"
)


# ----------------------------------------------------------------------
# P5 anchors
# ----------------------------------------------------------------------

# Anchor P5-A: cancel timer at "[Connection] Already connected, skip UDP punch" site
ANCHOR_P5_ALREADY_CONNECTED = (
    "if (this.connectionWon.get()) {\n"
    "\n"
    "         VoxLinkMod.LOGGER.info(\"[Connection] Already connected, skip UDP punch (cycle={}, attempt={})\", cycle + 1, attempt);\n"
    "\n"
    "      } else {\n"
    "\n"
    "         state.roomInfo.setConnectionMode(Component.translatable(\"voxlink.connection.connecting\"));\n"
)

REPL_P5_ALREADY_CONNECTED = (
    "if (this.connectionWon.get()) {\n"
    "\n"
    "         VoxLinkMod.LOGGER.info(\"[Connection] Already connected, skip UDP punch (cycle={}, attempt={})\", cycle + 1, attempt);\n"
    "\n"
    "         // 连接成功后取消 85s 全局超时计时器, 防止干扰后续重连 / 下一轮\n"
    "         if (this.connectionTimeoutFuture != null) { this.connectionTimeoutFuture.cancel(false); this.connectionTimeoutFuture = null; }\n"
    "\n"
    "      } else {\n"
    "\n"
    "         state.roomInfo.setConnectionMode(Component.translatable(\"voxlink.connection.connecting\"));\n"
)


# Anchor P5-B: cancel timer at "[Connection] Connected, skip Wave step" site
ANCHOR_P5_WAVE_STEP = (
    "if (this.connectionWon.get()) {\n"
    "\n"
    "            VoxLinkMod.LOGGER.info(\"[Connection] Connected, skip Wave step (cycle={}, step={})\", cycle + 1, step);\n"
    "\n"
    "         } else {\n"
    "\n"
    "            switch (step) {\n"
)

REPL_P5_WAVE_STEP = (
    "if (this.connectionWon.get()) {\n"
    "\n"
    "            VoxLinkMod.LOGGER.info(\"[Connection] Connected, skip Wave step (cycle={}, step={})\", cycle + 1, step);\n"
    "\n"
    "            // 连接成功后取消 85s 全局超时计时器, 防止干扰后续重连 / 下一轮\n"
    "            if (this.connectionTimeoutFuture != null) { this.connectionTimeoutFuture.cancel(false); this.connectionTimeoutFuture = null; }\n"
    "\n"
    "         } else {\n"
    "\n"
    "            switch (step) {\n"
)


def main():
    summary = []  # (template, label, action)

    for tmpl in TEMPLATES:
        cm_rel = f"{tmpl}/src/main/java/icu/wuhui/voxlink/room/ConnectionManager.java"

        # P0 anchors
        if replace_with_eol(cm_rel, ANCHOR_P0_FIELD, REPL_P0_FIELD, "p0 field+constant"):
            summary.append((tmpl, "p0 field+constant", "applied"))

        if replace_with_eol(cm_rel, ANCHOR_P0_METHOD, REPL_P0_METHOD, "p0 helper method"):
            summary.append((tmpl, "p0 helper method", "applied"))

        # 8 LOGGER.info site replacements
        sites = [
            ("p0 site1 (3351)", ANCHOR_P0_SITE_1, REPL_P0_SITE_1),
            ("p0 site2 (4634)", ANCHOR_P0_SITE_2, REPL_P0_SITE_2),
            ("p0 site3 (5444)", ANCHOR_P0_SITE_3, REPL_P0_SITE_3),
            ("p0 site4 (5867)", ANCHOR_P0_SITE_4, REPL_P0_SITE_4),
            ("p0 site5 (9494)", ANCHOR_P0_SITE_5, REPL_P0_SITE_5),
            ("p0 site6 (9722)", ANCHOR_P0_SITE_6, REPL_P0_SITE_6),
            ("p0 site7 (10981)", ANCHOR_P0_SITE_7, REPL_P0_SITE_7),
            ("p0 site8 (11375)", ANCHOR_P0_SITE_8, REPL_P0_SITE_8),
        ]
        for label, anchor, repl in sites:
            if replace_with_eol(cm_rel, anchor, repl, label):
                summary.append((tmpl, label, "applied"))

        # Cap-check open + close
        if replace_with_eol(cm_rel, ANCHOR_P0_CAPCHECK, REPL_P0_CAPCHECK, "p0 cap-check open"):
            summary.append((tmpl, "p0 cap-check open", "applied"))

        if replace_with_eol(cm_rel, ANCHOR_P0_CAPCHECK_CLOSE, REPL_P0_CAPCHECK_CLOSE, "p0 cap-check close"):
            summary.append((tmpl, "p0 cap-check close", "applied"))

        # P5 anchors
        if replace_with_eol(cm_rel, ANCHOR_P5_ALREADY_CONNECTED, REPL_P5_ALREADY_CONNECTED, "p5 Already connected"):
            summary.append((tmpl, "p5 Already connected", "applied"))

        if replace_with_eol(cm_rel, ANCHOR_P5_WAVE_STEP, REPL_P5_WAVE_STEP, "p5 Wave step"):
            summary.append((tmpl, "p5 Wave step", "applied"))

    print(f"\n=== Summary: {len(summary)} anchor replacements applied across 25 templates ===")
    for t, lbl, action in summary:
        print(f"  {t:<30}  {lbl:<28}  {action}")
    print(f"\ntotal applied: {len(summary)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())