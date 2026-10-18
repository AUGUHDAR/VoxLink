#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
sync_relay113_20260830.py

Propagate the 7 "manual player relay" UX fixes from the master template
(fabric/1.21.11) to all 25 templates (fabric/forge/neoforge x MC versions).

The relay subsystem lives in two files (ConnectionManager.java and
AttemptingJoinScreen.java) and is structurally identical across all 25
templates — verified by per-template line counts (ConnectionManager.java is
13783 lines in every template; AttemptingJoinScreen.java is 711 lines in
every template).

The 7 fixes (in the user's spec order):

  Fix1: Progress feedback for the lower slot (AttemptingJoinScreen.render)
        ConnectionManager exposes a new getRelayProgressText() returning
        Component (or null). The slot renders that if non-null, else
        falls back to the original terracottaStatusText.

  Fix2: 90s overall timeout. ConnectionManager declares
        `manualRelayDeadline` (volatile long) and sets it in
        triggerManualRelay; tryRelay() checks the deadline at entry
        (covers initial call and recursion), aborts via
        notifyRelayFailed + showConnectFailed("voxlink.relay.failed_timeout").
        The joiner 20s wait is unchanged.

  Fix3: Failure-bucket reason keys:
        - voxlink.relay.failed_no_candidates (host branch empty
          candidates, was relay_unavailable)
        - voxlink.relay.failed_timeout (Fix2 deadline branch, new)
        - voxlink.relay.failed_host_no_response (joiner 20s wait,
          was relay_failed)
        - relay_unavailable stays for "global relayEnabled=false".

  Fix4: Cancel relay. New ConnectionManager.cancelManualRelay() resets
        state and nextRelayEligibleRound to the current round (immediate
        re-click or back to direct). AttemptingJoinScreen now swaps the
        "use_player_relay" button for "cancel" while
        isManualRelayInProgress()==true. New onCancelRelayClicked
        handler.

  Fix5: Success paths (handleRelayNotify connected branch and
        handleRelayReady) reset manualRelayInProgress=false so the UI
        does not flash the 3s "failed_retry_punch" toast.

  Fix6: notifyRelayFailed also resets nextRelayEligibleRound to the
        current round — failed click is immediately re-clickable
        (matches the existing failed_retry_punch wording).

  Fix7: handleRelayRequest host_notice throttled to 10s via a new
        volatile long lastHostNoticeAt — multi-guest flood protection.

  i18n: 13 lang files (per-template count verified via os.listdir) get 6
        new keys (relay.searching / relay.trying_batch / relay.cancel /
        relay.failed_no_candidates / relay.failed_timeout /
        relay.failed_host_no_response). zh_cn / en_us are written
        verbatim in the spec; the other 11 get a short, locally-flavoured
        translation matching the style of the existing relay.* keys.

26.x adaptation notes (avoided in the anchors):
  - 26.x chat API is `mc.player.sendSystemMessage(...)` (no second arg),
    while 1.21.x is `mc.player.displayClientMessage(..., false)`. The
    host_notice throttle anchor is placed BEFORE the
    `m.player.X(Component.translatable("voxlink.relay.host_notice"), ...)`
    line, so the same anchor text matches both forms (the X(...) call
    itself is not touched).
  - 26.2 uses `mc.gui.setScreen(...)` instead of `mc.setScreen(...)`,
    but no AttemptingJoinScreen anchor touches those lines (we only
    edit the relay-button block and the lower render slot).

Strategy (per repo convention: single-line / small-block anchor preferred,
count==1 asserted, newline='' for binary I/O, CRLF tolerated via LF-normalized
match — same shape as sync_modsync113_20260830.py and sync_punch113_20260830.py):

  * Each anchor is a small block (3-15 lines), the LF-normalized count
    must equal exactly 1 to assert idempotency safely.
  * On second run all anchors are absent (already applied) and the script
    is a no-op.

For each fix point the script asserts count==1 before replacement
(idempotent: a second run finds zero matches and is treated as a no-op).

After replacement, every file is overwritten preserving original line
endings (CRLF preserved on Windows-style sources, LF preserved on the
i18n JSONs).
"""

import io
import os
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))

TEMPLATES = sorted(
    [os.path.join("fabric", v) for v in os.listdir(os.path.join(ROOT, "fabric"))]
    + [os.path.join("forge", v) for v in os.listdir(os.path.join(ROOT, "forge"))]
    + [os.path.join("neoforge", v) for v in os.listdir(os.path.join(ROOT, "neoforge"))]
)
assert len(TEMPLATES) == 25, f"expect 25 templates, got {len(TEMPLATES)}"

# 13 language file basenames (verified via os.listdir in master template).
# User spec says 14 — master actually has 13 (zh 系简繁 + en + ja/ko/de/fr/
# es/pt/ru/ar + 文言 lzh). All 25 templates have exactly these 13.
LANG_BASENAMES = [
    "ar_sa.json",
    "de_de.json",
    "en_us.json",
    "es_es.json",
    "fr_fr.json",
    "ja_jp.json",
    "ko_kr.json",
    "lzh.json",
    "pt_br.json",
    "ru_ru.json",
    "zh_cn.json",
    "zh_hk.json",
    "zh_tw.json",
]


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


# ==========================================================================
# ConnectionManager.java — 8 anchors
# ==========================================================================

# Anchor CM-A1: insert 2 volatile fields after manualRelayInProgress.
ANCHOR_CM_A1 = (
    "private volatile boolean manualRelayInProgress = false;\n"
    "\n"
    "   private final AtomicBoolean connectionCycleActive = new AtomicBoolean(false);\n"
)

REPL_CM_A1 = (
    "private volatile boolean manualRelayInProgress = false;\n"
    "\n"
    "   /** 手动中继总超时：90s 到期后 tryRelay 递归立刻终止并走 failed_timeout 分桶。 */\n"
    "   private volatile long manualRelayDeadline = 0L;\n"
    "\n"
    "   /** 房主收到 relay_request 时 host_notice toast 节流：避免多房客同时请求刷屏。 */\n"
    "   private volatile long lastHostNoticeAt = 0L;\n"
    "\n"
    "   private final AtomicBoolean connectionCycleActive = new AtomicBoolean(false);\n"
)


# Anchor CM-A2: triggerManualRelay sets the 90s deadline after
# manualRelayInProgress=true. The 3-line block is unique in the file.
ANCHOR_CM_A2 = (
    "if (!this.manualRelayInProgress) {\n"
    "\n"
    "               this.manualRelayInProgress = true;\n"
    "\n"
    "               int currentRound = this.continuousRetryRound.get();\n"
)

REPL_CM_A2 = (
    "if (!this.manualRelayInProgress) {\n"
    "\n"
    "               this.manualRelayInProgress = true;\n"
    "\n"
    "               // 启动 90s 总超时：tryRelay 递归批次(8s/批) 多了会无限循环, 必须有兜底。\n"
    "               this.manualRelayDeadline = System.currentTimeMillis() + 90_000L;\n"
    "\n"
    "               int currentRound = this.continuousRetryRound.get();\n"
)


# Anchor CM-A3: tryRelay entry — insert deadline check before the
# !isRelayEnabled branch.
ANCHOR_CM_A3 = (
    "private void tryRelay(RoomManager.RoomState state) {\n"
    "\n"
    "      if (!VoxLinkMod.getConfig().isRelayEnabled()) {\n"
)

REPL_CM_A3 = (
    "private void tryRelay(RoomManager.RoomState state) {\n"
    "\n"
    "      // 总超时: 90s 内没成功就停递归, 走 failed_timeout 分桶; 房客 20s 等待不受影响。\n"
    "      if (this.manualRelayInProgress && this.manualRelayDeadline > 0L\n"
    "         && System.currentTimeMillis() >= this.manualRelayDeadline) {\n"
    "         VoxLinkMod.LOGGER.warn(\"[Relay] Manual relay overall timeout (90s) reached, abort recursive tryRelay\");\n"
    "         this.manualRelayDeadline = 0L;\n"
    "         this.notifyRelayFailed();\n"
    "         this.showConnectFailed(state, \"voxlink.relay.failed_timeout\");\n"
    "         return;\n"
    "      }\n"
    "\n"
    "      if (!VoxLinkMod.getConfig().isRelayEnabled()) {\n"
)


# Anchor CM-A4: tryRelay host branch empty candidates — switch to
# failed_no_candidates bucket.
ANCHOR_CM_A4 = (
    "if (candidates.isEmpty()) {\n"
    "\n"
    "               VoxLinkMod.LOGGER.info(\"[Relay] No available Cone relay node (excluded failed={})\", this.failedRelayPeers.size());\n"
    "\n"
    "               this.failedRelayPeers.clear();\n"
    "\n"
    "               this.notifyRelayFailed();\n"
    "\n"
    "               this.showConnectFailed(state, \"voxlink.connection.relay_unavailable\");\n"
    "\n"
    "               return;\n"
)

REPL_CM_A4 = (
    "if (candidates.isEmpty()) {\n"
    "\n"
    "               VoxLinkMod.LOGGER.info(\"[Relay] No available Cone relay node (excluded failed={})\", this.failedRelayPeers.size());\n"
    "\n"
    "               this.failedRelayPeers.clear();\n"
    "\n"
    "               this.notifyRelayFailed();\n"
    "\n"
    "               this.showConnectFailed(state, \"voxlink.relay.failed_no_candidates\");\n"
    "\n"
    "               return;\n"
)


# Anchor CM-A5: joiner 20s wait — switch to failed_host_no_response bucket.
ANCHOR_CM_A5 = (
    "this.relayFailoverTask = this.scheduler.schedule(() -> {\n"
    "\n"
    "               if (!this.connectionWon.get()) {\n"
    "\n"
    "                  this.currentRelayPeer.set(null);\n"
    "\n"
    "                  this.relayFailoverTask = null;\n"
    "\n"
    "                  this.notifyRelayFailed();\n"
    "\n"
    "                  this.showConnectFailed(state, \"voxlink.connection.relay_failed\");\n"
    "\n"
    "               }\n"
    "\n"
    "            }, 20L, TimeUnit.SECONDS);\n"
)

REPL_CM_A5 = (
    "this.relayFailoverTask = this.scheduler.schedule(() -> {\n"
    "\n"
    "               if (!this.connectionWon.get()) {\n"
    "\n"
    "                  this.currentRelayPeer.set(null);\n"
    "\n"
    "                  this.relayFailoverTask = null;\n"
    "\n"
    "                  this.notifyRelayFailed();\n"
    "\n"
    "                  this.showConnectFailed(state, \"voxlink.relay.failed_host_no_response\");\n"
    "\n"
    "               }\n"
    "\n"
    "            }, 20L, TimeUnit.SECONDS);\n"
)


# Anchor CM-A6: insert relayProgressText = "searching" before the host-branch
# sets currentRelayPeer for the joiner.
ANCHOR_CM_A6 = (
    "this.currentRelayPeer.set(\"joiner_requesting\");\n"
    "\n"
    "            JsonObject data = new JsonObject();\n"
)

REPL_CM_A6 = (
    "this.currentRelayPeer.set(\"joiner_requesting\");\n"
    "\n"
    "            // 房客发起 relay_request 后下方槽位: \"正在寻找适合中继的玩家…\"\n"
    "            this.relayProgressText = Component.translatable(\"voxlink.relay.searching\");\n"
    "\n"
    "            JsonObject data = new JsonObject();\n"
)


# Anchor CM-A7: tryRelay host-branch empty candidates -> fall-through to
# relayProgressText = "searching" then trying_batch. We append the
# searching stage right after the (now-bucketed) empty-candidates block,
# so the user's spec for the "searching" stage is wired in.
ANCHOR_CM_A7 = (
    "this.showConnectFailed(state, \"voxlink.relay.failed_no_candidates\");\n"
    "\n"
    "               return;\n"
    "\n"
    "            }\n"
)

REPL_CM_A7 = (
    "this.showConnectFailed(state, \"voxlink.relay.failed_no_candidates\");\n"
    "\n"
    "               return;\n"
    "\n"
    "            }\n"
    "            // 阶段文本: 筛选到候选, 即将并行尝试 → \"正在寻找适合中继的玩家…\" 闪一瞬, 然后立刻被 trying_batch 覆盖。\n"
    "            this.relayProgressText = Component.translatable(\"voxlink.relay.searching\");\n"
)


# Anchor CM-A8: setting currentRelayPeer + batchNo + trying_batch Component
# in host tryRelay loop. Indent is 15 spaces at this nesting level.
ANCHOR_CM_A8 = (
    "this.currentRelayPeer.set(relayCandidates.get(0).clientId);\n"
    "\n"
    "               VoxLinkMod.LOGGER.info(\"[Relay] Parallel try {} Cone relays, target Sym={}\", parallelN, symPeer.clientId);\n"
    "\n"
    "\n"
    "\n"
    "               for (int ix = 0; ix < parallelN; ix++) {\n"
)

REPL_CM_A8 = (
    "this.currentRelayPeer.set(relayCandidates.get(0).clientId);\n"
    "\n"
    "               int batchNo = this.relayBatchIndex.incrementAndGet();\n"
    "\n"
    "               this.relayBatchSize.set(parallelN);\n"
    "\n"
    "               // 阶段 Component: 房主分支每次开始新批次时显示「第 N 轮：M 个候选…」, 失败/成功时清空。\n"
    "               this.relayProgressText = Component.translatable(\"voxlink.relay.trying_batch\",\n"
    "\n"
    "                  new Object[]{Integer.toString(batchNo), Integer.toString(parallelN)});\n"
    "\n"
    "               VoxLinkMod.LOGGER.info(\"[Relay] Parallel try {} Cone relays, target Sym={}\", parallelN, symPeer.clientId);\n"
    "\n"
    "\n"
    "\n"
    "               for (int ix = 0; ix < parallelN; ix++) {\n"
)


# Anchor CM-A9: handleRelayNotify success path — reset manualRelayInProgress.
# The unique anchor is the `this.relayConnectedSignaled = true;` block +
# `clearRelayTracking()` + `connectionWon.set(true);`.
ANCHOR_CM_A9 = (
    "VoxLinkMod.LOGGER.info(\"[Relay] Received relay_notify(connected), relay ready\");\n"
    "\n"
    "            this.relayConnectedSignaled = true;\n"
    "\n"
    "            this.clearRelayTracking();\n"
    "\n"
    "            this.connectionWon.set(true);\n"
    "\n"
    "            state.roomInfo.setConnectionMode(Component.translatable(\"voxlink.relay.connected_via\").withStyle(ChatFormatting.YELLOW));\n"
)

REPL_CM_A9 = (
    "VoxLinkMod.LOGGER.info(\"[Relay] Received relay_notify(connected), relay ready\");\n"
    "\n"
    "            this.relayConnectedSignaled = true;\n"
    "\n"
    "            this.clearRelayTracking();\n"
    "\n"
    "            this.connectionWon.set(true);\n"
    "\n"
    "            // 成功路径补: 不重置 manualRelayInProgress 会让 AttemptingJoinScreen 误闪 3s 失败文字。\n"
    "            this.manualRelayInProgress = false;\n"
    "\n"
    "            this.relayProgressText = null;\n"
    "\n"
    "            this.manualRelayDeadline = 0L;\n"
    "\n"
    "            state.roomInfo.setConnectionMode(Component.translatable(\"voxlink.relay.connected_via\").withStyle(ChatFormatting.YELLOW));\n"
)


# Anchor CM-A10: handleRelayReady — reset manualRelayInProgress after
# clearRelayTracking(). Anchor: the "Received relay_ready" log line.
ANCHOR_CM_A10 = (
    "VoxLinkMod.LOGGER.info(\"[Relay] Received relay_ready from joiner {}, mark relay connected\", from);\n"
    "\n"
    "         this.clearRelayTracking();\n"
    "\n"
    "         this.stopAllPunchingAfterHostBridge();\n"
)

REPL_CM_A10 = (
    "VoxLinkMod.LOGGER.info(\"[Relay] Received relay_ready from joiner {}, mark relay connected\", from);\n"
    "\n"
    "         this.clearRelayTracking();\n"
    "\n"
    "         // 成功路径补: 与 handleRelayNotify 同步, 避免 AttemptingJoinScreen 误闪失败文字。\n"
    "         this.manualRelayInProgress = false;\n"
    "\n"
    "         this.relayProgressText = null;\n"
    "\n"
    "         this.manualRelayDeadline = 0L;\n"
    "\n"
    "         this.stopAllPunchingAfterHostBridge();\n"
)


# Anchor CM-A11: notifyRelayFailed — also reset nextRelayEligibleRound +
# relayProgressText + manualRelayDeadline.
ANCHOR_CM_A11 = (
    "private void notifyRelayFailed() {\n"
    "\n"
    "      if (this.manualRelayInProgress) {\n"
    "\n"
    "         this.manualRelayInProgress = false;\n"
    "\n"
    "         VoxLinkMod.LOGGER.info(\"[Relay] Manual relay failed, resume hole punching\");\n"
    "\n"
    "      }\n"
    "\n"
    "   }\n"
)

REPL_CM_A11 = (
    "private void notifyRelayFailed() {\n"
    "\n"
    "      if (this.manualRelayInProgress) {\n"
    "\n"
    "         this.manualRelayInProgress = false;\n"
    "\n"
    "         // 失败后把 nextRelayEligibleRound 拉回当前轮: 玩家立即可再点按钮或回到直连,\n"
    "\n"
    "         // 与 \"中继失败，继续尝试直连打洞...\" 的按钮文案语义对齐。\n"
    "\n"
    "         int currentRound = this.continuousRetryRound.get();\n"
    "\n"
    "         if (currentRound > 0) {\n"
    "\n"
    "            this.nextRelayEligibleRound = currentRound;\n"
    "\n"
    "         }\n"
    "\n"
    "         this.relayProgressText = null;\n"
    "\n"
    "         this.manualRelayDeadline = 0L;\n"
    "\n"
    "         VoxLinkMod.LOGGER.info(\"[Relay] Manual relay failed, resume hole punching\");\n"
    "\n"
    "      }\n"
    "\n"
    "   }\n"
)


# Anchor CM-A12: handleRelayRequest host_notice throttle — anchor is the
# "信令线程不可直调 GUI" comment + the `if (mc != null)` line. The
# 26.x anchor is the same comment text; the `m.player.X(...)` line
# below differs (displayClientMessage vs sendSystemMessage) but is not
# part of the anchor.
ANCHOR_CM_A12 = (
    "// 信令线程不可直调 GUI：包一层主线程调度\n"
    "\n"
    "               if (mc != null) {\n"
)

REPL_CM_A12 = (
    "// 信令线程不可直调 GUI：包一层主线程调度\n"
    "\n"
    "               // 节流: 多房客同时发 relay_request 时, 房主 toast 不再每请求一次弹一次, 改为 10s 一次。\n"
    "               long nowMs = System.currentTimeMillis();\n"
    "               boolean shouldShowNotice = (nowMs - this.lastHostNoticeAt) >= 10_000L;\n"
    "               if (shouldShowNotice) {\n"
    "                  this.lastHostNoticeAt = nowMs;\n"
    "               }\n"
    "\n"
    "               if (mc != null && shouldShowNotice) {\n"
)


# Anchor CM-A13: isManualRelayInProgress() — insert relayBatchIndex /
# relayBatchSize / relayProgressText fields and getRelayProgressText()
# + cancelManualRelay() between isManualRelayInProgress and
# getContinuousRetryRound.
ANCHOR_CM_A13 = (
    "public boolean isManualRelayInProgress() {\n"
    "\n"
    "      return this.manualRelayInProgress;\n"
    "\n"
    "   }\n"
    "\n"
    "\n"
    "\n"
    "   public int getContinuousRetryRound() {\n"
)

REPL_CM_A13 = (
    "public boolean isManualRelayInProgress() {\n"
    "\n"
    "      return this.manualRelayInProgress;\n"
    "\n"
    "   }\n"
    "\n"
    "\n"
    "\n"
    "   /** 当前批尝试号（用于下方槽位显示「第 N 轮：M 个候选…」）。无进行中时返回 0。 */\n"
    "\n"
    "   private final java.util.concurrent.atomic.AtomicInteger relayBatchIndex = new java.util.concurrent.atomic.AtomicInteger(0);\n"
    "\n"
    "\n"
    "\n"
    "   private final java.util.concurrent.atomic.AtomicInteger relayBatchSize = new java.util.concurrent.atomic.AtomicInteger(0);\n"
    "\n"
    "\n"
    "\n"
    "   /** 阶段 Component：searching / trying_batch / null。render() 会读它显示在下方槽位。 */\n"
    "\n"
    "   private volatile Component relayProgressText = null;\n"
    "\n"
    "\n"
    "\n"
    "   /** 中继进行中时下方槽位显示 Component（null 表示走原有的 terracotta 状态文本）。 */\n"
    "\n"
    "   public Component getRelayProgressText() {\n"
    "\n"
    "      return this.relayProgressText;\n"
    "\n"
    "   }\n"
    "\n"
    "\n"
    "\n"
    "   /** 取消手动中继：清理状态、reset nextRelayEligibleRound 允许立即重试或回到直连。 */\n"
    "\n"
    "   public void cancelManualRelay() {\n"
    "\n"
    "      VoxLinkMod.LOGGER.info(\"[Relay] Manual relay cancelled by user\");\n"
    "\n"
    "      this.manualRelayDeadline = 0L;\n"
    "\n"
    "      this.relayProgressText = null;\n"
    "\n"
    "      this.relayBatchIndex.set(0);\n"
    "\n"
    "      this.relayBatchSize.set(0);\n"
    "\n"
    "      if (this.relayFailoverTask != null) {\n"
    "\n"
    "         this.relayFailoverTask.cancel(false);\n"
    "\n"
    "         this.relayFailoverTask = null;\n"
    "\n"
    "      }\n"
    "\n"
    "      this.currentRelayPeer.set(null);\n"
    "\n"
    "      this.notifyRelayFailed();\n"
    "\n"
    "      // 立即可再点/回直连: 把 nextRelayEligibleRound 拉回当前轮。\n"
    "\n"
    "      int currentRound = this.continuousRetryRound.get();\n"
    "\n"
    "      if (currentRound > 0) {\n"
    "\n"
    "         this.nextRelayEligibleRound = currentRound;\n"
    "\n"
    "      }\n"
    "\n"
    "      RoomManager.RoomState state = this.roomManager.currentRoom.get();\n"
    "\n"
    "      if (state != null && state != RoomManager.PENDING) {\n"
    "\n"
    "         // 取消后让屏上方 connectionMode 走原有\"中继失败，继续尝试直连打洞\"语义，匹配按钮文案对齐。\n"
    "\n"
    "         state.roomInfo.setConnectionMode(Component.translatable(\"voxlink.relay.failed_retry_punch\"));\n"
    "\n"
    "      }\n"
    "\n"
    "   }\n"
    "\n"
    "\n"
    "\n"
    "   public int getContinuousRetryRound() {\n"
)


def fix_connection_manager(tmpl: str) -> int:
    """Apply 13 ConnectionManager anchors in order. Returns applied count."""
    rel = f"{tmpl}/src/main/java/icu/wuhui/voxlink/room/ConnectionManager.java"
    cnt = 0
    anchors = [
        (ANCHOR_CM_A1, REPL_CM_A1, "CM-A1 manualRelayDeadline+lastHostNoticeAt fields"),
        (ANCHOR_CM_A2, REPL_CM_A2, "CM-A2 triggerManualRelay set deadline"),
        (ANCHOR_CM_A3, REPL_CM_A3, "CM-A3 tryRelay entry deadline check"),
        (ANCHOR_CM_A4, REPL_CM_A4, "CM-A4 host branch failed_no_candidates"),
        (ANCHOR_CM_A5, REPL_CM_A5, "CM-A5 joiner 20s failed_host_no_response"),
        (ANCHOR_CM_A6, REPL_CM_A6, "CM-A6 joiner branch searching stage"),
        (ANCHOR_CM_A7, REPL_CM_A7, "CM-A7 host branch searching stage"),
        (ANCHOR_CM_A8, REPL_CM_A8, "CM-A8 host trying_batch Component"),
        (ANCHOR_CM_A9, REPL_CM_A9, "CM-A9 handleRelayNotify reset"),
        (ANCHOR_CM_A10, REPL_CM_A10, "CM-A10 handleRelayReady reset"),
        (ANCHOR_CM_A11, REPL_CM_A11, "CM-A11 notifyRelayFailed reset round"),
        (ANCHOR_CM_A12, REPL_CM_A12, "CM-A12 host_notice throttle"),
        (ANCHOR_CM_A13, REPL_CM_A13, "CM-A13 cancelManualRelay + progress getters"),
    ]
    for old, new, label in anchors:
        if replace_with_eol(rel, old, new, label):
            cnt += 1
    return cnt


# ==========================================================================
# AttemptingJoinScreen.java — 3 anchors
# ==========================================================================

# Anchor AJ-A1: relay-button area — swap "use_player_relay" for "cancel"
# while manualRelayInProgress. Anchor: the if-block's if/else chain.
ANCHOR_AJ_A1 = (
    "if (this.active && VoxLinkMod.getRoomManager().getConnectionManager().canShowRelayButton()) {\n"
    "               this.relayButtonVisible = true;\n"
    "               this.addRenderableWidget(\n"
    "                  Button.builder(Component.translatable(\"voxlink.relay.use_player_relay\"), button -> this.onRelayButtonClicked())\n"
    "                     .bounds(centerX - 100, btnY + 20 + 4, 200, 20)\n"
    "                     .build()\n"
    "               );\n"
    "            } else {\n"
    "               this.relayButtonVisible = false;\n"
    "            }\n"
)

REPL_AJ_A1 = (
    "if (this.active && VoxLinkMod.getRoomManager().getConnectionManager().canShowRelayButton()) {\n"
    "               this.relayButtonVisible = true;\n"
    "               this.addRenderableWidget(\n"
    "                  Button.builder(Component.translatable(\"voxlink.relay.use_player_relay\"), button -> this.onRelayButtonClicked())\n"
    "                     .bounds(centerX - 100, btnY + 20 + 4, 200, 20)\n"
    "                     .build()\n"
    "               );\n"
    "            } else if (this.active && VoxLinkMod.getRoomManager().getConnectionManager().isManualRelayInProgress()) {\n"
    "               // 中继进行中: 把 \"使用其他玩家进行中继\" 按钮换成 \"取消中继\" 出口。\n"
    "               this.relayButtonVisible = true;\n"
    "               this.addRenderableWidget(\n"
    "                  Button.builder(Component.translatable(\"voxlink.relay.cancel\"), button -> this.onCancelRelayClicked())\n"
    "                     .bounds(centerX - 100, btnY + 20 + 4, 200, 20)\n"
    "                     .build()\n"
    "               );\n"
    "            } else {\n"
    "               this.relayButtonVisible = false;\n"
    "            }\n"
)


# Anchor AJ-A2: insert onCancelRelayClicked() right after onRelayButtonClicked.
ANCHOR_AJ_A2 = (
    "private void onRelayButtonClicked() {\n"
    "      if (VoxLinkMod.getConfig().isRelayEnabled()) {\n"
    "         this.relayButtonVisible = false;\n"
    "         this.lastManualRelayInProgress = true;\n"
    "         this.clearOurWidgets();\n"
    "         this.init();\n"
    "         VoxLinkMod.getRoomManager().getConnectionManager().triggerManualRelay();\n"
    "      }\n"
    "   }\n"
)

REPL_AJ_A2 = (
    "private void onRelayButtonClicked() {\n"
    "      if (VoxLinkMod.getConfig().isRelayEnabled()) {\n"
    "         this.relayButtonVisible = false;\n"
    "         this.lastManualRelayInProgress = true;\n"
    "         this.clearOurWidgets();\n"
    "         this.init();\n"
    "         VoxLinkMod.getRoomManager().getConnectionManager().triggerManualRelay();\n"
    "      }\n"
    "   }\n"
    "\n"
    "   /** \"取消中继\"按钮: 调用 ConnectionManager.cancelManualRelay(), 然后刷新布局回到直连态。 */\n"
    "   private void onCancelRelayClicked() {\n"
    "      VoxLinkMod.getRoomManager().getConnectionManager().cancelManualRelay();\n"
    "      // 让 AttemptingJoinScreen 重新走 init: manualRelayInProgress=false 后, canShowRelayButton 会重新判定。\n"
    "      this.clearOurWidgets();\n"
    "      this.init();\n"
    "   }\n"
)


# Anchor AJ-A3: lower render slot — render relayProgressText when present,
# else fall back to terracottaStatusText.
ANCHOR_AJ_A3 = (
    "if (!this.terracottaStatusText.isEmpty()) {\n"
    "         String label = Component.translatable(\"voxlink.dual.terracotta_label\").getString();\n"
    "         String clipped = this.terracottaStatusText;\n"
    "         int maxWidth = this.width - 20;\n"
    "         if (this.fontWidth(label + \": \" + clipped) > maxWidth) {\n"
    "            while (this.fontWidth(label + \": \" + clipped + \"...\") > maxWidth && clipped.length() > 0) {\n"
    "               clipped = clipped.substring(0, clipped.length() - 1);\n"
    "            }\n"
    "\n"
    "            clipped = clipped + \"...\";\n"
    "         }\n"
    "\n"
    "         this.drawCenteredString(graphics, label + \": \" + clipped, centerX, this.height / 2 + 14, this.terracottaStatusColor);\n"
    "      }\n"
)

REPL_AJ_A3 = (
    "if (!this.terracottaStatusText.isEmpty()) {\n"
    "         // 中继进行中时下方槽位优先显示进度文本（搜索 / 第 N 轮…）,\n"
    "         // 没在进行中才走原有的 terracottaStatusText。\n"
    "         ConnectionManager cm = VoxLinkMod.getRoomManager().getConnectionManager();\n"
    "         Component relayProgress = cm != null ? cm.getRelayProgressText() : null;\n"
    "         if (relayProgress != null) {\n"
    "            String progressText = relayProgress.getString();\n"
    "            String clipped = progressText;\n"
    "            int maxWidth = this.width - 20;\n"
    "            if (this.fontWidth(clipped) > maxWidth) {\n"
    "               while (this.fontWidth(clipped + \"...\") > maxWidth && clipped.length() > 0) {\n"
    "                  clipped = clipped.substring(0, clipped.length() - 1);\n"
    "               }\n"
    "\n"
    "               clipped = clipped + \"...\";\n"
    "            }\n"
    "\n"
    "            this.drawCenteredString(graphics, clipped, centerX, this.height / 2 + 14, VoxLinkColors.WARNING);\n"
    "         } else {\n"
    "         String label = Component.translatable(\"voxlink.dual.terracotta_label\").getString();\n"
    "         String clipped = this.terracottaStatusText;\n"
    "         int maxWidth = this.width - 20;\n"
    "         if (this.fontWidth(label + \": \" + clipped) > maxWidth) {\n"
    "            while (this.fontWidth(label + \": \" + clipped + \"...\") > maxWidth && clipped.length() > 0) {\n"
    "               clipped = clipped.substring(0, clipped.length() - 1);\n"
    "            }\n"
    "\n"
    "            clipped = clipped + \"...\";\n"
    "         }\n"
    "\n"
    "         this.drawCenteredString(graphics, label + \": \" + clipped, centerX, this.height / 2 + 14, this.terracottaStatusColor);\n"
    "         }\n"
    "\n"
    "      }\n"
)


def fix_attempting_join_screen(tmpl: str) -> int:
    """Apply 3 AttemptingJoinScreen anchors in order. Returns applied count."""
    rel = f"{tmpl}/src/main/java/icu/wuhui/voxlink/ui/AttemptingJoinScreen.java"
    cnt = 0
    anchors = [
        (ANCHOR_AJ_A1, REPL_AJ_A1, "AJ-A1 cancel-button branch"),
        (ANCHOR_AJ_A2, REPL_AJ_A2, "AJ-A2 onCancelRelayClicked handler"),
        (ANCHOR_AJ_A3, REPL_AJ_A3, "AJ-A3 lower slot render progress"),
    ]
    for old, new, label in anchors:
        if replace_with_eol(rel, old, new, label):
            cnt += 1
    return cnt


# ==========================================================================
# i18n — 6 new keys appended after the existing `host_unknown` line in
# each of the 13 lang files. The anchor is the line that ends with the
# closing `"%s"` for host_unknown (unique per file), and we extend it
# with comma + 6 new keys + closing `}`.
# ==========================================================================

# Anchor = "<host_unknown line>\n}\n" (the final key + the closing brace).
# 13 lang files × 6 new keys.
LANG_HOST_UNKNOWN = {
    "ar_sa.json": '"voxlink.modsync.host_unknown": "%d مود غير مُعرَّف، على الضيوف تثبيتها يدوياً: %s"',
    "de_de.json": '"voxlink.modsync.host_unknown": "%d Mod(s) unbekannt, Gäste müssen sie manuell installieren: %s"',
    "en_us.json": '"voxlink.modsync.host_unknown": "%d mod(s) unrecognised, guests must install manually: %s"',
    "es_es.json": '"voxlink.modsync.host_unknown": "%d mod(s) no reconocido(s), los invitados deben instalarlos manualmente: %s"',
    "fr_fr.json": '"voxlink.modsync.host_unknown": "%d mod(s) non reconnu(s), les invités doivent les installer manuellement : %s"',
    "ja_jp.json": '"voxlink.modsync.host_unknown": "%d 件のmodが識別できません。ゲストは手動で導入してください：%s"',
    "ko_kr.json": '"voxlink.modsync.host_unknown": "%d개의 mod를 인식할 수 없습니다. 게스트가 수동으로 설치해야 합니다: %s"',
    "lzh.json": '"voxlink.modsync.host_unknown": "%d 款 mod 未能識，房客當手裝：%s"',
    "pt_br.json": '"voxlink.modsync.host_unknown": "%d mod(s) não reconhecido(s), convidados precisam instalar manualmente: %s"',
    "ru_ru.json": '"voxlink.modsync.host_unknown": "%d мод(ов) не распознано, гости должны установить вручную: %s"',
    "zh_cn.json": '"voxlink.modsync.host_unknown": "%d 个 mod 无法识别，房客需手动安装：%s"',
    "zh_hk.json": '"voxlink.modsync.host_unknown": "%d 個 mod 認唔到，房客要手動裝：%s"',
    "zh_tw.json": '"voxlink.modsync.host_unknown": "%d 個 mod 無法識別，房客須手動安裝：%s"',
}

# 6 new keys per language. Style matches the existing relay.* entries:
# short, friendly tone, %% placeholders for Component.translatable args.
LANG_NEW_KEYS = {
    "ar_sa.json": [
        '"voxlink.relay.searching": "جاري البحث عن لاعب يصلح للت relay..."',
        '"voxlink.relay.trying_batch": "الجولة %s: %s لاعب(ين) مرشح(ين)..."',
        '"voxlink.relay.cancel": "إلغاء الت relay، العودة إلى الاتصال المباشر"',
        '"voxlink.relay.failed_no_candidates": "فشل الت relay: لا يوجد لاعب متاح للت relay"',
        '"voxlink.relay.failed_timeout": "فشل الت relay: المرشحون لم يستجيبوا"',
        '"voxlink.relay.failed_host_no_response": "فشل الت relay: المضيف لم يستجب"',
    ],
    "de_de.json": [
        '"voxlink.relay.searching": "Suche nach einem Spieler zum Weiterleiten..."',
        '"voxlink.relay.trying_batch": "Runde %s: %s Kandidat(en)..."',
        '"voxlink.relay.cancel": "Relay abbrechen, zurück zu direkt"',
        '"voxlink.relay.failed_no_candidates": "Relay fehlgeschlagen: kein Relay-Spieler verfügbar"',
        '"voxlink.relay.failed_timeout": "Relay fehlgeschlagen: Kandidaten haben nicht geantwortet"',
        '"voxlink.relay.failed_host_no_response": "Relay fehlgeschlagen: Host hat nicht geantwortet"',
    ],
    "en_us.json": [
        '"voxlink.relay.searching": "Looking for a player to relay through..."',
        '"voxlink.relay.trying_batch": "Round %s: %s candidate player(s)..."',
        '"voxlink.relay.cancel": "Cancel relay, back to direct"',
        '"voxlink.relay.failed_no_candidates": "Relay failed: no relay player available"',
        '"voxlink.relay.failed_timeout": "Relay failed: candidate players didn\'t respond"',
        '"voxlink.relay.failed_host_no_response": "Relay failed: host didn\'t respond"',
    ],
    "es_es.json": [
        '"voxlink.relay.searching": "Buscando un jugador para retransmitir..."',
        '"voxlink.relay.trying_batch": "Ronda %s: %s candidato(s)..."',
        '"voxlink.relay.cancel": "Cancelar retransmisión, volver a directo"',
        '"voxlink.relay.failed_no_candidates": "Retransmisión fallida: no hay jugador disponible"',
        '"voxlink.relay.failed_timeout": "Retransmisión fallida: los candidatos no respondieron"',
        '"voxlink.relay.failed_host_no_response": "Retransmisión fallida: el anfitrión no respondió"',
    ],
    "fr_fr.json": [
        '"voxlink.relay.searching": "Recherche d\'un joueur pour relayer..."',
        '"voxlink.relay.trying_batch": "Tour %s : %s joueur(s) candidat(s)..."',
        '"voxlink.relay.cancel": "Annuler le relais, revenir au direct"',
        '"voxlink.relay.failed_no_candidates": "Relais échoué : aucun joueur disponible"',
        '"voxlink.relay.failed_timeout": "Relais échoué : les candidats n\'ont pas répondu"',
        '"voxlink.relay.failed_host_no_response": "Relais échoué : l\'hôte n\'a pas répondu"',
    ],
    "ja_jp.json": [
        '"voxlink.relay.searching": "中継してくれるプレイヤーを探しています…"',
        '"voxlink.relay.trying_batch": "ラウンド %s：候補 %s 人…"',
        '"voxlink.relay.cancel": "中継をキャンセルして直接接続に戻す"',
        '"voxlink.relay.failed_no_candidates": "中継失敗：中継可能なプレイヤーがいません"',
        '"voxlink.relay.failed_timeout": "中継失敗：候補が応答しませんでした"',
        '"voxlink.relay.failed_host_no_response": "中継失敗：ホストが応答しませんでした"',
    ],
    "ko_kr.json": [
        '"voxlink.relay.searching": "중계할 플레이어를 찾는 중..."',
        '"voxlink.relay.trying_batch": "%s라운드: 후보 %s명..."',
        '"voxlink.relay.cancel": "중계 취소, 직접 연결로 돌아가기"',
        '"voxlink.relay.failed_no_candidates": "중계 실패: 사용 가능한 중계 플레이어 없음"',
        '"voxlink.relay.failed_timeout": "중계 실패: 후보가 응답하지 않음"',
        '"voxlink.relay.failed_host_no_response": "중계 실패: 호스트가 응답하지 않음"',
    ],
    "lzh.json": [
        '"voxlink.relay.searching": "覓可為中繼之玩家…中"',
        '"voxlink.relay.trying_batch": "第 %s 輪：候 %s 人…"',
        '"voxlink.relay.cancel": "罷中繼，復直連"',
        '"voxlink.relay.failed_no_candidates": "中繼未成：無可為中繼之玩家"',
        '"voxlink.relay.failed_timeout": "中繼未成：候者未應"',
        '"voxlink.relay.failed_host_no_response": "中繼未成：房主未應"',
    ],
    "pt_br.json": [
        '"voxlink.relay.searching": "Procurando um jogador para retransmitir..."',
        '"voxlink.relay.trying_batch": "Rodada %s: %s candidato(s)..."',
        '"voxlink.relay.cancel": "Cancelar retransmissão, voltar ao direto"',
        '"voxlink.relay.failed_no_candidates": "Retransmissão falhou: nenhum jogador disponível"',
        '"voxlink.relay.failed_timeout": "Retransmissão falhou: candidatos não responderam"',
        '"voxlink.relay.failed_host_no_response": "Retransmissão falhou: anfitrião não respondeu"',
    ],
    "ru_ru.json": [
        '"voxlink.relay.searching": "Ищу игрока для ретрансляции..."',
        '"voxlink.relay.trying_batch": "Раунд %s: %s кандидат(ов)..."',
        '"voxlink.relay.cancel": "Отменить ретрансляцию, вернуться к прямому"',
        '"voxlink.relay.failed_no_candidates": "Ретрансляция не удалась: нет игроков"',
        '"voxlink.relay.failed_timeout": "Ретрансляция не удалась: кандидаты не ответили"',
        '"voxlink.relay.failed_host_no_response": "Ретрансляция не удалась: хост не ответил"',
    ],
    "zh_cn.json": [
        '"voxlink.relay.searching": "正在寻找适合中继的玩家…"',
        '"voxlink.relay.trying_batch": "第 %s 轮：%s 个候选玩家…"',
        '"voxlink.relay.cancel": "取消中继，回到直连"',
        '"voxlink.relay.failed_no_candidates": "中继失败：没有可用的中继玩家"',
        '"voxlink.relay.failed_timeout": "中继失败：候选玩家未响应"',
        '"voxlink.relay.failed_host_no_response": "中继失败：房主未响应"',
    ],
    "zh_hk.json": [
        '"voxlink.relay.searching": "搵緊適合中繼嘅玩家…"',
        '"voxlink.relay.trying_batch": "第 %s 輪：%s 個候選玩家…"',
        '"voxlink.relay.cancel": "取消中繼，回到直連"',
        '"voxlink.relay.failed_no_candidates": "中繼失敗：無可用嘅中繼玩家"',
        '"voxlink.relay.failed_timeout": "中繼失敗：候選玩家冇回應"',
        '"voxlink.relay.failed_host_no_response": "中繼失敗：房主冇回應"',
    ],
    "zh_tw.json": [
        '"voxlink.relay.searching": "正在尋找適合中繼的玩家…"',
        '"voxlink.relay.trying_batch": "第 %s 輪：%s 個候選玩家…"',
        '"voxlink.relay.cancel": "取消中繼，回到直連"',
        '"voxlink.relay.failed_no_candidates": "中繼失敗：沒有可用的中繼玩家"',
        '"voxlink.relay.failed_timeout": "中繼失敗：候選玩家未回應"',
        '"voxlink.relay.failed_host_no_response": "中繼失敗：房主未回應"',
    ],
}


def i18n_apply(tmpl: str) -> int:
    """Append 6 keys to each of 13 lang files for a given template. Returns count."""
    cnt = 0
    for basename in LANG_BASENAMES:
        rel = f"{tmpl}/src/main/resources/assets/voxlink/lang/{basename}"
        if not os.path.exists(os.path.join(ROOT, rel)):
            continue  # lang file not present in this template -> skip
        host_unknown_line = LANG_HOST_UNKNOWN[basename]
        old = host_unknown_line + "\n}\n"
        keys = LANG_NEW_KEYS[basename]
        new = (
            host_unknown_line + ",\n"
            + "    " + keys[0] + ",\n"
            + "    " + keys[1] + ",\n"
            + "    " + keys[2] + ",\n"
            + "    " + keys[3] + ",\n"
            + "    " + keys[4] + ",\n"
            + "    " + keys[5] + "\n"
            + "}\n"
        )
        if replace_with_eol(rel, old, new, f"i18n {basename}"):
            cnt += 1
    return cnt


def main():
    summary = []  # (template, label, action)

    for tmpl in TEMPLATES:
        # ConnectionManager anchors (13)
        cm = fix_connection_manager(tmpl)
        if cm:
            summary.append((tmpl, f"CM ({cm}/13)", "applied"))

        # AttemptingJoinScreen anchors (3)
        aj = fix_attempting_join_screen(tmpl)
        if aj:
            summary.append((tmpl, f"AJ ({aj}/3)", "applied"))

        # i18n: 13 lang files × 6 keys
        ci = i18n_apply(tmpl)
        if ci:
            summary.append((tmpl, f"i18n ({ci}/13)", "applied"))

    print(f"\n=== Summary: {len(summary)} template-fix groups applied across 25 templates ===")
    for t, lbl, action in summary:
        print(f"  {t:<30}  {lbl:<14}  {action}")
    print(f"\ntotal applied: {len(summary)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())