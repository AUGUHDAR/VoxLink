#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
sync_auditfix2_20260830.py

Propagate 5 audit-fix changes from the master template (fabric/1.21.11) to
all 25 templates (fabric/forge/neoforge x MC versions):

  Fix1: AttemptingJoinScreen — gate roomInfo==null on startJoin() having run.
        Add a `joinInitiated` volatile flag, set in startJoin(); monitor
        short-circuits the roomInfo==null branch when not initiated.
  Fix2: CreateRoomScreen.simplifyError — default branch no longer leaks raw
        exception message; falls back to `voxlink.error.unknown` and logs
        the original message at WARN.
  Fix3: ManageRoomScreen.updateRoom().exceptionally() — same fallback:
        friendly key + LOGGER.error already present.
  Fix4: TerracottaConfigScreen.deleteBinary — two-step confirm button (label
        flips to "再次点击确认删除" / `voxlink.terracotta.delete_binary_confirm`
        after first tap, real delete only on a second tap within 3 s). Delete
        scope narrowed: only the binary file + `*.downloading` archives +
        `extract-*` temp dirs + `.download_pending` marker; never the
        whole CACHE_DIR root.
  Fix5: AttemptingJoinScreen.startConnectionMonitor — catch now flips
        `monitorActive.set(false)` to avoid an infinite exception loop
        when the screen has already been torn down.

Plus a lang change in all 13 lang files across all 25 templates:
  Add `voxlink.terracotta.delete_binary_confirm` translation right after
  `voxlink.terracotta.delete_binary`.

Strategy (per repo convention: single-line anchor preferred, count==1
asserted, newline='' for I/O, CRLF tolerated via regex):
  * Anchors are matched as substrings after LF normalization.
  * Multi-line anchors are written as one string with embedded \\n; the
    replace helper normalizes the file text to LF before matching and
    rewrites the file in its original EOL after the substitution.
  * Idempotent: a second run finds zero matches for the OLD anchor (or
    already finds the NEW anchor in the lang insertion path), and prints
    no-op lines instead of double-applying.
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

# Lang files present in every template.
LANG_FILES = (
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
)

# Translation per language code (without .json suffix).
CONFIRM_TEXT = {
    "ar_sa": "انقر مرة أخرى لتأكيد الحذف",
    "de_de": "Erneut klicken, um Löschen zu bestätigen",
    "en_us": "Click again to confirm deletion",
    "es_es": "Pulsa otra vez para confirmar el borrado",
    "fr_fr": "Cliquez à nouveau pour confirmer la suppression",
    "ja_jp": "もう一度クリックして削除を確認",
    "ko_kr": "삭제 확인을 위해 다시 클릭",
    "lzh": "再點一次以確認刪除",
    "pt_br": "Clique novamente para confirmar a exclusão",
    "ru_ru": "Нажмите ещё раз для подтверждения удаления",
    "zh_cn": "再次点击确认删除",
    "zh_hk": "再次點擊確認刪除",
    "zh_tw": "再次點擊確認刪除",
}


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


def replace_with_eol(rel_path: str, old_lines: str, new_lines: str, label: str) -> str:
    """Replace `old_lines` (joined with \\n) with `new_lines` (joined with \\n),
    in a file that may use CRLF or LF. count==1 must hold. Idempotent: if the
    new block already exists (in either form), returns 'skip-noop'. Other
    possible return values: 'applied', 'skip-missing'."""
    data = read_file_bytes(rel_path)
    eol = detect_eol(data)

    # Normalize current file to LF for matching
    text = data.decode("utf-8")
    text_lf = text.replace("\r\n", "\n")

    old_lf = old_lines.replace("\r\n", "\n")
    new_lf = new_lines.replace("\r\n", "\n")

    if new_lf in text_lf:
        return "skip-noop"
    cnt = text_lf.count(old_lf)
    if cnt == 0:
        return "skip-missing"
    assert cnt == 1, f"{rel_path}: anchor count={cnt} for {label}"

    # Replace in LF-normalized text, then convert back to original EOL
    text_lf_new = text_lf.replace(old_lf, new_lf, 1)
    if eol == b"\r\n":
        text_new = text_lf_new.replace("\n", "\r\n")
    else:
        text_new = text_lf_new

    write_file_bytes(rel_path, text_new.encode("utf-8"))
    return "applied"


# ----------------------------------------------------------------------
# Fix1a: AttemptingJoinScreen — add `joinInitiated` field after joinApiDone
# ----------------------------------------------------------------------

ANCHOR_JOIN_INIT_FIELD = (
    "   private volatile boolean active = false;\n"
    "   private boolean joinApiDone = false;\n"
    "   private volatile boolean joinCompleted = false;\n"
)

REPL_JOIN_INIT_FIELD = (
    "   private volatile boolean active = false;\n"
    "   private boolean joinApiDone = false;\n"
    "   /** startJoin() 已正式进入：用于避免屏幕刚打开/startJoin 还没执行的间隙 getCurrentRoom()=null 被误判为房间丢失。 */\n"
    "   private volatile boolean joinInitiated = false;\n"
    "   private volatile boolean joinCompleted = false;\n"
)


# ----------------------------------------------------------------------
# Fix1b: AttemptingJoinScreen — set joinInitiated in startJoin()
# ----------------------------------------------------------------------

ANCHOR_JOIN_START = (
    "   private void startJoin() {\n"
    "      this.active = true;\n"
    "      LogUploadManager.arm(this.roomCode, false);\n"
)

REPL_JOIN_START = (
    "   private void startJoin() {\n"
    "      this.active = true;\n"
    "      this.joinInitiated = true;\n"
    "      LogUploadManager.arm(this.roomCode, false);\n"
)


# ----------------------------------------------------------------------
# Fix1c: AttemptingJoinScreen — gate roomInfo==null branch on joinInitiated
# ----------------------------------------------------------------------

ANCHOR_ROOM_LOST_GATE = (
    "               try {\n"
    "                  Minecraft mc = Minecraft.getInstance();\n"
    "                  AttemptingJoinScreen.this.monitorTicks++;\n"
    "                  RoomInfo roomInfo = VoxLinkMod.getRoomManager().getCurrentRoom();\n"
    "                  if (roomInfo == null) {\n"
    "                     if (!AttemptingJoinScreen.this.joinCompleted) {\n"
)

REPL_ROOM_LOST_GATE = (
    "               try {\n"
    "                  Minecraft mc = Minecraft.getInstance();\n"
    "                  AttemptingJoinScreen.this.monitorTicks++;\n"
    "                  RoomInfo roomInfo = VoxLinkMod.getRoomManager().getCurrentRoom();\n"
    "                  if (roomInfo == null) {\n"
    "                     // 屏幕刚打开/startJoin 还没执行的间隙 getCurrentRoom() 本来就为 null；\n"
    "                     // 仅在 startJoin() 已被调用后才视为房间丢失，未发起则本轮跳过检查\n"
    "                     if (!AttemptingJoinScreen.this.joinInitiated) {\n"
    "                        if (AttemptingJoinScreen.this.connectionFuture != null) {\n"
    "                           AttemptingJoinScreen.this.connectionFuture.cancel(false);\n"
    "                        }\n"
    "\n"
    "                        if (monitorActive.get()\n"
    "                           && AttemptingJoinScreen.this.connectionScheduler != null\n"
    "                           && !AttemptingJoinScreen.this.connectionScheduler.isShutdown()) {\n"
    "                           AttemptingJoinScreen.this.connectionFuture = AttemptingJoinScreen.this.connectionScheduler\n"
    "                              .schedule(() -> mc.execute(this), 500L, TimeUnit.MILLISECONDS);\n"
    "                        }\n"
    "\n"
    "                        return;\n"
    "                     }\n"
    "\n"
    "                     if (!AttemptingJoinScreen.this.joinCompleted) {\n"
)


# ----------------------------------------------------------------------
# Fix5: AttemptingJoinScreen — catch block stops monitor
# ----------------------------------------------------------------------

# Variant A: mc.screen (legacy + 26.1_26.1.2)
ANCHOR_MONITOR_CATCH = (
    "               } catch (Exception e) {\n"
    "                  Minecraft mc = Minecraft.getInstance();\n"
    "                  mc.execute(() -> {\n"
    "                     if (mc.screen == AttemptingJoinScreen.this) {\n"
    "                        AttemptingJoinScreen.this.onFailed(Component.translatable(\"voxlink.connection.monitor_error\").getString());\n"
    "                     }\n"
    "                  });\n"
    "               }\n"
)

REPL_MONITOR_CATCH = (
    "               } catch (Exception e) {\n"
    "                  Minecraft mc = Minecraft.getInstance();\n"
    "                  monitorActive.set(false);\n"
    "                  mc.execute(() -> {\n"
    "                     if (mc.screen == AttemptingJoinScreen.this) {\n"
    "                        AttemptingJoinScreen.this.onFailed(Component.translatable(\"voxlink.connection.monitor_error\").getString());\n"
    "                     }\n"
    "                  });\n"
    "               }\n"
)

# Variant B: mc.gui.screen() (26.2)
ANCHOR_MONITOR_CATCH_26_2 = (
    "               } catch (Exception e) {\n"
    "                  Minecraft mc = Minecraft.getInstance();\n"
    "                  mc.execute(() -> {\n"
    "                     if (mc.gui.screen() == AttemptingJoinScreen.this) {\n"
    "                        AttemptingJoinScreen.this.onFailed(Component.translatable(\"voxlink.connection.monitor_error\").getString());\n"
    "                     }\n"
    "                  });\n"
    "               }\n"
)

REPL_MONITOR_CATCH_26_2 = (
    "               } catch (Exception e) {\n"
    "                  Minecraft mc = Minecraft.getInstance();\n"
    "                  monitorActive.set(false);\n"
    "                  mc.execute(() -> {\n"
    "                     if (mc.gui.screen() == AttemptingJoinScreen.this) {\n"
    "                        AttemptingJoinScreen.this.onFailed(Component.translatable(\"voxlink.connection.monitor_error\").getString());\n"
    "                     }\n"
    "                  });\n"
    "               }\n"
)


# ----------------------------------------------------------------------
# Fix2: CreateRoomScreen.simplifyError — default branch fallback
# ----------------------------------------------------------------------

ANCHOR_SIMPLIFY_DEFAULT = (
    "      } else {\n"
    "         return msg.contains(\"PARSE_ERROR\") ? Component.translatable(\"voxlink.error.server_response_abnormal\").getString() : msg;\n"
    "      }\n"
    "   }\n"
    "\n"
    "   private void sendChatMessages(Minecraft mc, RoomInfo roomInfo) {\n"
)

REPL_SIMPLIFY_DEFAULT = (
    "      } else {\n"
    "         // 未匹配分类:不直接外露底层原文,统一兜底为 voxlink.error.unknown;原文写入日志便于排查\n"
    "         if (msg.contains(\"PARSE_ERROR\")) {\n"
    "            return Component.translatable(\"voxlink.error.server_response_abnormal\").getString();\n"
    "         } else {\n"
    "            VoxLinkMod.LOGGER.warn(\"[CreateRoom] simplifyError unmatched: {}\", msg);\n"
    "            return Component.translatable(\"voxlink.error.unknown\").getString();\n"
    "         }\n"
    "      }\n"
    "   }\n"
    "\n"
    "   private void sendChatMessages(Minecraft mc, RoomInfo roomInfo) {\n"
)


# ----------------------------------------------------------------------
# Fix3: ManageRoomScreen.exceptionally — default fallback
# ----------------------------------------------------------------------

ANCHOR_MRS_EXCEPTION = (
    "               String msg = cause.getMessage();\n"
    "               VoxLinkMod.LOGGER.error(\"Room update failed: {}\", msg, cause);\n"
    "               String finalMsg = msg != null ? msg : Component.translatable(\"voxlink.error.unknown\").getString();\n"
)

REPL_MRS_EXCEPTION = (
    "               String msg = cause.getMessage();\n"
    "               VoxLinkMod.LOGGER.error(\"Room update failed: {}\", msg, cause);\n"
    "               // 兜底文案,避免直接外露底层异常原文;LOG 已记录,排查用日志即可\n"
    "               String finalMsg = Component.translatable(\"voxlink.error.unknown\").getString();\n"
)


# ----------------------------------------------------------------------
# Fix4a: TerracottaConfigScreen — add confirm-flag fields and constant
# ----------------------------------------------------------------------

ANCHOR_TCS_FIELDS = (
    "   private boolean lastPausedState = false;\n"
    "   private static final int BTN_W = 200;\n"
)

REPL_TCS_FIELDS = (
    "   private boolean lastPausedState = false;\n"
    "   /** 两次确认删除陶瓦：第一次点击仅置位并不删，3 秒内再点才真正执行。 */\n"
    "   private boolean deleteConfirmArmed = false;\n"
    "   private long deleteConfirmArmTime = 0L;\n"
    "   private static final int BTN_W = 200;\n"
    "   private static final long DELETE_CONFIRM_WINDOW_MS = 3000L;\n"
)


# ----------------------------------------------------------------------
# Fix4b: TerracottaConfigScreen — delete button uses helper + click handler
# ----------------------------------------------------------------------

ANCHOR_TCS_BTN = (
    "      this.deleteBinaryBtn = Button.builder(Component.translatable(\"voxlink.terracotta.delete_binary\"), button -> this.deleteBinary())\n"
    "         .bounds(centerX - 100, y + 96, 200, 20)\n"
    "         .build();\n"
)

REPL_TCS_BTN = (
    "      this.deleteBinaryBtn = Button.builder(this.buildDeleteBinaryLabel(), button -> this.onDeleteBinaryClicked())\n"
    "         .bounds(centerX - 100, y + 96, 200, 20)\n"
    "         .build();\n"
)


# ----------------------------------------------------------------------
# Fix4c: TerracottaConfigScreen — replace deleteBinary body
# ----------------------------------------------------------------------

# Variant A: mc.screen (legacy + 26.1_26.1.2)
ANCHOR_TCS_DELETE = (
    "   private void deleteBinary() {\n"
    "      try {\n"
    "         TerracottaManager.shutdown();\n"
    "      } catch (Exception e) {\n"
    "         VoxLinkMod.LOGGER.warn(\"Failed to stop Terracotta before delete: {}\", e.getMessage());\n"
    "      }\n"
    "\n"
    "      Path cacheDir = TerracottaBinary.getCacheDir();\n"
    "\n"
    "      try {\n"
    "         deleteRecursively(cacheDir);\n"
    "         this.statusMessage = Component.translatable(\"voxlink.terracotta.binary_deleted\").getString();\n"
    "         this.statusColor = VoxLinkColors.SUCCESS;\n"
    "      } catch (IOException e) {\n"
    "         VoxLinkMod.LOGGER.warn(\"Failed to delete Terracotta: {}\", e.getMessage());\n"
    "         this.statusMessage = Component.translatable(\"voxlink.terracotta.download_failed\").getString();\n"
    "         this.statusColor = VoxLinkColors.ERROR;\n"
    "      }\n"
    "\n"
    "      Minecraft mc = Minecraft.getInstance();\n"
    "      mc.execute(() -> {\n"
    "         if (mc.screen == this) {\n"
    "            this.init();\n"
    "         }\n"
    "      });\n"
    "   }\n"
)

REPL_TCS_DELETE = (
    "   private Component buildDeleteBinaryLabel() {\n"
    "      return this.deleteConfirmArmed\n"
    "         ? Component.translatable(\"voxlink.terracotta.delete_binary_confirm\")\n"
    "         : Component.translatable(\"voxlink.terracotta.delete_binary\");\n"
    "   }\n"
    "\n"
    "   /** 两段式确认：第一次点击仅把按钮文案换成\"再次点击确认删除\"；3 秒内再点才执行删除。 */\n"
    "   private void onDeleteBinaryClicked() {\n"
    "      long now = System.currentTimeMillis();\n"
    "      if (this.deleteConfirmArmed && now - this.deleteConfirmArmTime <= DELETE_CONFIRM_WINDOW_MS) {\n"
    "         this.deleteConfirmArmed = false;\n"
    "         this.deleteConfirmArmTime = 0L;\n"
    "         this.deleteBinary();\n"
    "      } else {\n"
    "         this.deleteConfirmArmed = true;\n"
    "         this.deleteConfirmArmTime = now;\n"
    "         if (this.deleteBinaryBtn != null) {\n"
    "            this.deleteBinaryBtn.setMessage(this.buildDeleteBinaryLabel());\n"
    "         }\n"
    "      }\n"
    "   }\n"
    "\n"
    "   private void deleteBinary() {\n"
    "      try {\n"
    "         TerracottaManager.shutdown();\n"
    "      } catch (Exception e) {\n"
    "         VoxLinkMod.LOGGER.warn(\"Failed to stop Terracotta before delete: {}\", e.getMessage());\n"
    "      }\n"
    "\n"
    "      // 删除范围收窄:仅删陶瓦二进制与解压/下载产物子文件,不再删整个缓存根目录,\n"
    "      // 以免误清掉 .voxlink/terracotta/ 下其他无关文件/目录\n"
    "      Path cacheDir = TerracottaBinary.getCacheDir();\n"
    "      Path binaryPath = TerracottaBinary.getBinaryPath();\n"
    "\n"
    "      try {\n"
    "         // 1. 陶瓦可执行/库本体\n"
    "         if (binaryPath != null) {\n"
    "            Files.deleteIfExists(binaryPath);\n"
    "         }\n"
    "\n"
    "         // 2. 同目录下的 .downloading 半成品压缩包与临时解压子目录\n"
    "         if (Files.isDirectory(cacheDir)) {\n"
    "            try (Stream<Path> stream = Files.list(cacheDir)) {\n"
    "               for (Path p : stream.toList()) {\n"
    "                  String name = p.getFileName().toString();\n"
    "                  if (name.endsWith(\".downloading\") || name.startsWith(\"extract-\")) {\n"
    "                     deleteRecursively(p);\n"
    "                  }\n"
    "               }\n"
    "            }\n"
    "         }\n"
    "\n"
    "         // 3. .download_pending 标记也一并清理,下次走完整流程而非续传\n"
    "         try {\n"
    "            Files.deleteIfExists(cacheDir.resolve(\".download_pending\"));\n"
    "         } catch (IOException ignored) {\n"
    "         }\n"
    "\n"
    "         this.statusMessage = Component.translatable(\"voxlink.terracotta.binary_deleted\").getString();\n"
    "         this.statusColor = VoxLinkColors.SUCCESS;\n"
    "      } catch (IOException e) {\n"
    "         VoxLinkMod.LOGGER.warn(\"Failed to delete Terracotta: {}\", e.getMessage());\n"
    "         this.statusMessage = Component.translatable(\"voxlink.terracotta.download_failed\").getString();\n"
    "         this.statusColor = VoxLinkColors.ERROR;\n"
    "      }\n"
    "\n"
    "      Minecraft mc = Minecraft.getInstance();\n"
    "      mc.execute(() -> {\n"
    "         if (mc.screen == this) {\n"
    "            // 删完后重置一次确认态,避免下次进入屏幕时仍处于武装中\n"
    "            this.deleteConfirmArmed = false;\n"
    "            this.deleteConfirmArmTime = 0L;\n"
    "            if (this.deleteBinaryBtn != null) {\n"
    "               this.deleteBinaryBtn.setMessage(this.buildDeleteBinaryLabel());\n"
    "            }\n"
    "            this.init();\n"
    "         }\n"
    "      });\n"
    "   }\n"
)

# Variant B: mc.gui.screen() (26.2)
ANCHOR_TCS_DELETE_26_2 = (
    "   private void deleteBinary() {\n"
    "      try {\n"
    "         TerracottaManager.shutdown();\n"
    "      } catch (Exception e) {\n"
    "         VoxLinkMod.LOGGER.warn(\"Failed to stop Terracotta before delete: {}\", e.getMessage());\n"
    "      }\n"
    "\n"
    "      Path cacheDir = TerracottaBinary.getCacheDir();\n"
    "\n"
    "      try {\n"
    "         deleteRecursively(cacheDir);\n"
    "         this.statusMessage = Component.translatable(\"voxlink.terracotta.binary_deleted\").getString();\n"
    "         this.statusColor = VoxLinkColors.SUCCESS;\n"
    "      } catch (IOException e) {\n"
    "         VoxLinkMod.LOGGER.warn(\"Failed to delete Terracotta: {}\", e.getMessage());\n"
    "         this.statusMessage = Component.translatable(\"voxlink.terracotta.download_failed\").getString();\n"
    "         this.statusColor = VoxLinkColors.ERROR;\n"
    "      }\n"
    "\n"
    "      Minecraft mc = Minecraft.getInstance();\n"
    "      mc.execute(() -> {\n"
    "         if (mc.gui.screen() == this) {\n"
    "            this.init();\n"
    "         }\n"
    "      });\n"
    "   }\n"
)

REPL_TCS_DELETE_26_2 = (
    "   private Component buildDeleteBinaryLabel() {\n"
    "      return this.deleteConfirmArmed\n"
    "         ? Component.translatable(\"voxlink.terracotta.delete_binary_confirm\")\n"
    "         : Component.translatable(\"voxlink.terracotta.delete_binary\");\n"
    "   }\n"
    "\n"
    "   /** 两段式确认：第一次点击仅把按钮文案换成\"再次点击确认删除\"；3 秒内再点才执行删除。 */\n"
    "   private void onDeleteBinaryClicked() {\n"
    "      long now = System.currentTimeMillis();\n"
    "      if (this.deleteConfirmArmed && now - this.deleteConfirmArmTime <= DELETE_CONFIRM_WINDOW_MS) {\n"
    "         this.deleteConfirmArmed = false;\n"
    "         this.deleteConfirmArmTime = 0L;\n"
    "         this.deleteBinary();\n"
    "      } else {\n"
    "         this.deleteConfirmArmed = true;\n"
    "         this.deleteConfirmArmTime = now;\n"
    "         if (this.deleteBinaryBtn != null) {\n"
    "            this.deleteBinaryBtn.setMessage(this.buildDeleteBinaryLabel());\n"
    "         }\n"
    "      }\n"
    "   }\n"
    "\n"
    "   private void deleteBinary() {\n"
    "      try {\n"
    "         TerracottaManager.shutdown();\n"
    "      } catch (Exception e) {\n"
    "         VoxLinkMod.LOGGER.warn(\"Failed to stop Terracotta before delete: {}\", e.getMessage());\n"
    "      }\n"
    "\n"
    "      // 删除范围收窄:仅删陶瓦二进制与解压/下载产物子文件,不再删整个缓存根目录,\n"
    "      // 以免误清掉 .voxlink/terracotta/ 下其他无关文件/目录\n"
    "      Path cacheDir = TerracottaBinary.getCacheDir();\n"
    "      Path binaryPath = TerracottaBinary.getBinaryPath();\n"
    "\n"
    "      try {\n"
    "         // 1. 陶瓦可执行/库本体\n"
    "         if (binaryPath != null) {\n"
    "            Files.deleteIfExists(binaryPath);\n"
    "         }\n"
    "\n"
    "         // 2. 同目录下的 .downloading 半成品压缩包与临时解压子目录\n"
    "         if (Files.isDirectory(cacheDir)) {\n"
    "            try (Stream<Path> stream = Files.list(cacheDir)) {\n"
    "               for (Path p : stream.toList()) {\n"
    "                  String name = p.getFileName().toString();\n"
    "                  if (name.endsWith(\".downloading\") || name.startsWith(\"extract-\")) {\n"
    "                     deleteRecursively(p);\n"
    "                  }\n"
    "               }\n"
    "            }\n"
    "         }\n"
    "\n"
    "         // 3. .download_pending 标记也一并清理,下次走完整流程而非续传\n"
    "         try {\n"
    "            Files.deleteIfExists(cacheDir.resolve(\".download_pending\"));\n"
    "         } catch (IOException ignored) {\n"
    "         }\n"
    "\n"
    "         this.statusMessage = Component.translatable(\"voxlink.terracotta.binary_deleted\").getString();\n"
    "         this.statusColor = VoxLinkColors.SUCCESS;\n"
    "      } catch (IOException e) {\n"
    "         VoxLinkMod.LOGGER.warn(\"Failed to delete Terracotta: {}\", e.getMessage());\n"
    "         this.statusMessage = Component.translatable(\"voxlink.terracotta.download_failed\").getString();\n"
    "         this.statusColor = VoxLinkColors.ERROR;\n"
    "      }\n"
    "\n"
    "      Minecraft mc = Minecraft.getInstance();\n"
    "      mc.execute(() -> {\n"
    "         if (mc.gui.screen() == this) {\n"
    "            // 删完后重置一次确认态,避免下次进入屏幕时仍处于武装中\n"
    "            this.deleteConfirmArmed = false;\n"
    "            this.deleteConfirmArmTime = 0L;\n"
    "            if (this.deleteBinaryBtn != null) {\n"
    "               this.deleteBinaryBtn.setMessage(this.buildDeleteBinaryLabel());\n"
    "            }\n"
    "            this.init();\n"
    "         }\n"
    "      });\n"
    "   }\n"
)


# ----------------------------------------------------------------------
# Fix4d: TerracottaConfigScreen — auto-disarm confirm in tick()
# ----------------------------------------------------------------------

ANCHOR_TCS_TICK = (
    "   public void tick() {\n"
    "      if (TerracottaManager.isDownloading() && this.redownloadBtn != null) {\n"
)

REPL_TCS_TICK = (
    "   public void tick() {\n"
    "      // 二次确认超时(3 秒)后自动撤销\"武装\"状态,把按钮文案还原成\"删除陶瓦\"\n"
    "      if (this.deleteConfirmArmed\n"
    "         && this.deleteConfirmArmTime > 0L\n"
    "         && System.currentTimeMillis() - this.deleteConfirmArmTime > DELETE_CONFIRM_WINDOW_MS) {\n"
    "         this.deleteConfirmArmed = false;\n"
    "         this.deleteConfirmArmTime = 0L;\n"
    "         if (this.deleteBinaryBtn != null) {\n"
    "            this.deleteBinaryBtn.setMessage(this.buildDeleteBinaryLabel());\n"
    "         }\n"
    "      }\n"
    "\n"
    "      if (TerracottaManager.isDownloading() && this.redownloadBtn != null) {\n"
)


# ----------------------------------------------------------------------
# Lang key insertion
# ----------------------------------------------------------------------

def is_26_2(template: str) -> bool:
    """26.2 templates use Minecraft.getInstance().gui.screen() instead of .screen."""
    import re as _re
    parts = _re.split(r"[\\/]", template)
    ver = parts[-1] if parts else ""
    return ver == "26.2"


def insert_lang_key(rel_path: str, lang_code: str) -> str:
    """Insert voxlink.terracotta.delete_binary_confirm right after
    voxlink.terracotta.delete_binary line. Idempotent."""
    data = read_file_bytes(rel_path)
    eol = detect_eol(data)
    text = data.decode("utf-8")
    text_lf = text.replace("\r\n", "\n")

    key = "voxlink.terracotta.delete_binary_confirm"
    if (f'"{key}":' in text_lf):
        return "skip-noop"

    target_line = '"voxlink.terracotta.delete_binary":'
    cnt = text_lf.count(target_line)
    if cnt == 0:
        return "skip-missing"
    assert cnt == 1, f"{rel_path}: delete_binary count={cnt}"

    text_lines = text_lf.split("\n")
    new_text_lf_lines = []
    inserted = False
    for line in text_lines:
        new_text_lf_lines.append(line)
        if (not inserted) and target_line in line:
            # Find current value-end to keep the same style.
            quote_close = line.rfind('"')
            tail = ""
            if quote_close > 0 and line.find('"', quote_close + 1) == -1:
                # value ended before line end -> comma likely on this line
                tail = line[quote_close + 1:]
            insert_line = f'    "{key}": "{CONFIRM_TEXT[lang_code]}",'
            new_text_lf_lines.append(insert_line)
            inserted = True
    assert inserted, f"{rel_path}: failed to insert lang key"

    text_lf_new = "\n".join(new_text_lf_lines)
    if eol == b"\r\n":
        text_new = text_lf_new.replace("\n", "\r\n")
    else:
        text_new = text_lf_new

    write_file_bytes(rel_path, text_new.encode("utf-8"))
    return "applied"


def main():
    summary = []  # (template_or_lang, label, action)
    failures = []

    for tmpl in TEMPLATES:
        aj_rel = f"{tmpl}/src/main/java/icu/wuhui/voxlink/ui/AttemptingJoinScreen.java"
        cr_rel = f"{tmpl}/src/main/java/icu/wuhui/voxlink/ui/CreateRoomScreen.java"
        mr_rel = f"{tmpl}/src/main/java/icu/wuhui/voxlink/ui/ManageRoomScreen.java"
        tc_rel = f"{tmpl}/src/main/java/icu/wuhui/voxlink/ui/TerracottaConfigScreen.java"
        is_26_2_tmpl = is_26_2(tmpl)

        # Fix1a + 1b + 1c + 5: AttemptingJoinScreen
        for anchor, repl, label in [
            (ANCHOR_JOIN_INIT_FIELD, REPL_JOIN_INIT_FIELD, "fix1a joinInitiated field"),
            (ANCHOR_JOIN_START, REPL_JOIN_START, "fix1b startJoin sets joinInitiated"),
            (ANCHOR_ROOM_LOST_GATE, REPL_ROOM_LOST_GATE, "fix1c roomInfo-null gate"),
        ]:
            try:
                action = replace_with_eol(aj_rel, anchor, repl, label)
            except AssertionError as ex:
                failures.append((aj_rel, label, str(ex)))
                action = "fail"
            summary.append((aj_rel, label, action))

        # Fix5: variant by mc.screen vs mc.gui.screen() (26.2)
        if is_26_2_tmpl:
            mc_anchor, mc_repl, mc_label = ANCHOR_MONITOR_CATCH_26_2, REPL_MONITOR_CATCH_26_2, "fix5 monitor catch stop (26.2)"
        else:
            mc_anchor, mc_repl, mc_label = ANCHOR_MONITOR_CATCH, REPL_MONITOR_CATCH, "fix5 monitor catch stop"
        try:
            action = replace_with_eol(aj_rel, mc_anchor, mc_repl, mc_label)
        except AssertionError as ex:
            failures.append((aj_rel, mc_label, str(ex)))
            action = "fail"
        summary.append((aj_rel, mc_label, action))

        # Fix2: CreateRoomScreen
        try:
            action = replace_with_eol(cr_rel, ANCHOR_SIMPLIFY_DEFAULT, REPL_SIMPLIFY_DEFAULT, "fix2 simplifyError fallback")
        except AssertionError as ex:
            failures.append((cr_rel, "fix2 simplifyError fallback", str(ex)))
            action = "fail"
        summary.append((cr_rel, "fix2 simplifyError fallback", action))

        # Fix3: ManageRoomScreen
        try:
            action = replace_with_eol(mr_rel, ANCHOR_MRS_EXCEPTION, REPL_MRS_EXCEPTION, "fix3 updateRoom exceptionally fallback")
        except AssertionError as ex:
            failures.append((mr_rel, "fix3 updateRoom exceptionally fallback", str(ex)))
            action = "fail"
        summary.append((mr_rel, "fix3 updateRoom exceptionally fallback", action))

        # Fix4: TerracottaConfigScreen
        for anchor, repl, label in [
            (ANCHOR_TCS_FIELDS, REPL_TCS_FIELDS, "fix4a TCS confirm fields"),
            (ANCHOR_TCS_BTN, REPL_TCS_BTN, "fix4b TCS delete btn"),
            (ANCHOR_TCS_TICK, REPL_TCS_TICK, "fix4d TCS tick disarm"),
        ]:
            try:
                action = replace_with_eol(tc_rel, anchor, repl, label)
            except AssertionError as ex:
                failures.append((tc_rel, label, str(ex)))
                action = "fail"
            summary.append((tc_rel, label, action))

        # Fix4c: variant by mc.screen vs mc.gui.screen() (26.2)
        if is_26_2_tmpl:
            tc_anchor, tc_repl, tc_label = ANCHOR_TCS_DELETE_26_2, REPL_TCS_DELETE_26_2, "fix4c TCS deleteBinary body (26.2)"
        else:
            tc_anchor, tc_repl, tc_label = ANCHOR_TCS_DELETE, REPL_TCS_DELETE, "fix4c TCS deleteBinary body"
        try:
            action = replace_with_eol(tc_rel, tc_anchor, tc_repl, tc_label)
        except AssertionError as ex:
            failures.append((tc_rel, tc_label, str(ex)))
            action = "fail"
        summary.append((tc_rel, tc_label, action))

        # Lang insertion
        for lang_file in LANG_FILES:
            lang_code = lang_file[:-5]
            lang_rel = f"{tmpl}/src/main/resources/assets/voxlink/lang/{lang_file}"
            try:
                action = insert_lang_key(lang_rel, lang_code)
            except AssertionError as ex:
                failures.append((lang_rel, f"lang {lang_code}", str(ex)))
                action = "fail"
            summary.append((lang_rel, f"lang insert {lang_code}", action))

    # Print summary
    applied = [s for s in summary if s[2] == "applied"]
    skipped_noop = [s for s in summary if s[2] == "skip-noop"]
    skipped_missing = [s for s in summary if s[2] == "skip-missing"]
    failed = [s for s in summary if s[2] == "fail"]

    print(f"\n=== sync_auditfix2_20260830 summary ===")
    print(f"templates:           {len(TEMPLATES)}")
    print(f"total anchors:       {len(summary)}")
    print(f"applied:             {len(applied)}")
    print(f"skip-noop (idempotent): {len(skipped_noop)}")
    print(f"skip-missing:        {len(skipped_missing)}")
    print(f"failed:              {len(failed)}")

    if skipped_missing:
        print("\n-- skip-missing (anchor not found, please inspect) --")
        for rel, lbl, _ in skipped_missing:
            print(f"  {rel}  ::  {lbl}")

    if failed:
        print("\n-- failed --")
        for rel, lbl, _ in failed:
            print(f"  {rel}  ::  {lbl}")
        for rel, lbl, msg in failures:
            print(f"  detail {rel}  ::  {lbl}  ::  {msg}")
        return 1

    return 0


if __name__ == "__main__":
    sys.exit(main())