#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
sync_ui113_20260830.py

Propagate two audit-fix changes from the master template (fabric/1.21.11) to
all 25 templates (fabric/forge/neoforge x MC versions):

  Fix1: Gate voxlink.relay.host_notice (in ConnectionManager.handleRelayRequest)
        and voxlink.relay.started (in RelayBridge.startRelay) local hints with
        isHost(), so guests never see host-only prompts.

  Fix2: Tighten UI for short-height resolutions:
        - RoomBrowserScreenBase.getCardHeight() -> 36 when height<360 else 48
        - RoomBrowserScreenBase: skip loader badge when cardH<48 (avoid overflow)
        - RoomBrowserScreenBase: skip hiddenHint on height<360
        - ManageRoomScreen: clamp status Y to height-12 on short screens
        - VoxLinkScreen.computeLayout: keep lyTopStart >= headerFloor+4

Strategy (per repo convention: single-line anchor preferred, count==1
asserted, newline='' for I/O, CRLF tolerated via regex):
  * For most fixes the anchor is a single-line snippet (count==1 assert).
  * Multi-line anchors are written as regex with \r?\n so they work on CRLF,
    LF, and mixed files.
  * Idempotent: a second run finds zero matches for the OLD anchor and is a
    no-op for that fix point.

For each fix point the script asserts count==1 before replacement (idempotent:
a second run finds zero matches and is treated as a no-op for that anchor).

After replacement, every file is overwritten preserving original line endings.
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


def is_121(template: str) -> bool:
    """1.21.x templates use displayClientMessage(... false)."""
    # Accept both forward-slash and backslash separators (os.path.join on
    # Windows uses backslash). Split on either.
    import re as _re
    parts = _re.split(r"[\\/]", template)
    ver = parts[-1] if parts else ""
    return ver.startswith("1.21")


# ----------------------------------------------------------------------
# Fix1a: ConnectionManager.handleRelayRequest — guard host_notice
# ----------------------------------------------------------------------

# Anchor — choose by API variant. Anchor includes the closing braces that
# follow the message call so the replacement is structurally complete.
ANCHOR_HOST_NOTICE_121 = (
    "} else {\n"
    "\n"
    "            Minecraft mc = Minecraft.getInstance();\n"
    "\n"
    "            // 信令线程不可直调 GUI：包一层主线程调度\n"
    "\n"
    "            if (mc != null) {\n"
    "\n"
    "               mc.execute(() -> {\n"
    "\n"
    "                  Minecraft m = Minecraft.getInstance();\n"
    "\n"
    "                  if (m.player != null) {\n"
    "\n"
    "                     m.player.displayClientMessage(Component.translatable(\"voxlink.relay.host_notice\"), false);\n"
    "\n"
    "                  }\n"
    "\n"
    "               });\n"
    "\n"
    "            }\n"
)

REPL_HOST_NOTICE_121 = (
    "} else {\n"
    "\n"
    "            // 仅房主向玩家展示中继提示；房客收到 relay_request 不弹\"房主\"提示\n"
    "            if (state.roomInfo.isHost()) {\n"
    "\n"
    "               Minecraft mc = Minecraft.getInstance();\n"
    "\n"
    "               // 信令线程不可直调 GUI：包一层主线程调度\n"
    "\n"
    "               if (mc != null) {\n"
    "\n"
    "                  mc.execute(() -> {\n"
    "\n"
    "                     Minecraft m = Minecraft.getInstance();\n"
    "\n"
    "                     if (m.player != null) {\n"
    "\n"
    "                        m.player.displayClientMessage(Component.translatable(\"voxlink.relay.host_notice\"), false);\n"
    "\n"
    "                     }\n"
    "\n"
    "                  });\n"
    "\n"
    "               }\n"
    "\n"
    "            }\n"
)

ANCHOR_HOST_NOTICE_OLD = (
    "} else {\n"
    "\n"
    "            Minecraft mc = Minecraft.getInstance();\n"
    "\n"
    "            // 信令线程不可直调 GUI：包一层主线程调度\n"
    "\n"
    "            if (mc != null) {\n"
    "\n"
    "               mc.execute(() -> {\n"
    "\n"
    "                  Minecraft m = Minecraft.getInstance();\n"
    "\n"
    "                  if (m.player != null) {\n"
    "\n"
    "                     m.player.sendSystemMessage(Component.translatable(\"voxlink.relay.host_notice\"));\n"
    "\n"
    "                  }\n"
    "\n"
    "               });\n"
    "\n"
    "            }\n"
)

REPL_HOST_NOTICE_OLD = (
    "} else {\n"
    "\n"
    "            // 仅房主向玩家展示中继提示；房客收到 relay_request 不弹\"房主\"提示\n"
    "            if (state.roomInfo.isHost()) {\n"
    "\n"
    "               Minecraft mc = Minecraft.getInstance();\n"
    "\n"
    "               // 信令线程不可直调 GUI：包一层主线程调度\n"
    "\n"
    "               if (mc != null) {\n"
    "\n"
    "                  mc.execute(() -> {\n"
    "\n"
    "                     Minecraft m = Minecraft.getInstance();\n"
    "\n"
    "                     if (m.player != null) {\n"
    "\n"
    "                        m.player.sendSystemMessage(Component.translatable(\"voxlink.relay.host_notice\"));\n"
    "\n"
    "                     }\n"
    "\n"
    "                  });\n"
    "\n"
    "               }\n"
    "\n"
    "            }\n"
)

# ----------------------------------------------------------------------
# Fix1b: RelayBridge.startRelay — guard relay.started
# ----------------------------------------------------------------------

ANCHOR_RELAY_STARTED_121 = (
    "            session.startForwarding();\n"
    "            LOGGER.info(\"[Relay] Relay started: {} (A={}, B={})\", new Object[]{relayKey, peerAId, peerBId});\n"
    "            // 网络线程不可直调 GUI：包一层主线程调度\n"
    "            Minecraft mc = Minecraft.getInstance();\n"
    "            if (mc != null) {\n"
    "               mc.execute(() -> {\n"
    "                  Minecraft m = Minecraft.getInstance();\n"
    "                  if (m.player != null) {\n"
    "                     m.player.displayClientMessage(Component.translatable(\"voxlink.relay.started\"), false);\n"
    "                  }\n"
    "               });\n"
    "            }\n"
)

REPL_RELAY_STARTED_121 = (
    "            session.startForwarding();\n"
    "            LOGGER.info(\"[Relay] Relay started: {} (A={}, B={})\", new Object[]{relayKey, peerAId, peerBId});\n"
    "            // 仅房主向玩家展示 relay.started 提示；防未来误投给房客\n"
    "            RoomInfo currentRoom = VoxLinkMod.getRoomManager() != null ? VoxLinkMod.getRoomManager().getCurrentRoom() : null;\n"
    "            if (currentRoom != null && currentRoom.isHost()) {\n"
    "               // 网络线程不可直调 GUI：包一层主线程调度\n"
    "               Minecraft mc = Minecraft.getInstance();\n"
    "               if (mc != null) {\n"
    "                  mc.execute(() -> {\n"
    "                     Minecraft m = Minecraft.getInstance();\n"
    "                     if (m.player != null) {\n"
    "                        m.player.displayClientMessage(Component.translatable(\"voxlink.relay.started\"), false);\n"
    "                     }\n"
    "                  });\n"
    "               }\n"
    "            }\n"
)

ANCHOR_RELAY_STARTED_OLD = (
    "            session.startForwarding();\n"
    "            LOGGER.info(\"[Relay] Relay started: {} (A={}, B={})\", new Object[]{relayKey, peerAId, peerBId});\n"
    "            // 网络线程不可直调 GUI：包一层主线程调度\n"
    "            Minecraft mc = Minecraft.getInstance();\n"
    "            if (mc != null) {\n"
    "               mc.execute(() -> {\n"
    "                  Minecraft m = Minecraft.getInstance();\n"
    "                  if (m.player != null) {\n"
    "                     m.player.sendSystemMessage(Component.translatable(\"voxlink.relay.started\"));\n"
    "                  }\n"
    "               });\n"
    "            }\n"
)

REPL_RELAY_STARTED_OLD = (
    "            session.startForwarding();\n"
    "            LOGGER.info(\"[Relay] Relay started: {} (A={}, B={})\", new Object[]{relayKey, peerAId, peerBId});\n"
    "            // 仅房主向玩家展示 relay.started 提示；防未来误投给房客\n"
    "            RoomInfo currentRoom = VoxLinkMod.getRoomManager() != null ? VoxLinkMod.getRoomManager().getCurrentRoom() : null;\n"
    "            if (currentRoom != null && currentRoom.isHost()) {\n"
    "               // 网络线程不可直调 GUI：包一层主线程调度\n"
    "               Minecraft mc = Minecraft.getInstance();\n"
    "               if (mc != null) {\n"
    "                  mc.execute(() -> {\n"
    "                     Minecraft m = Minecraft.getInstance();\n"
    "                     if (m.player != null) {\n"
    "                        m.player.sendSystemMessage(Component.translatable(\"voxlink.relay.started\"));\n"
    "                     }\n"
    "                  });\n"
    "               }\n"
    "            }\n"
)

# Single-line anchor for adding the imports to RelayBridge.
ANCHOR_RB_IMPORT = "import icu.wuhui.voxlink.room.ConnectionManager;\n"
REPL_RB_IMPORT = (
    "import icu.wuhui.voxlink.VoxLinkMod;\n"
    "import icu.wuhui.voxlink.room.ConnectionManager;\n"
    "import icu.wuhui.voxlink.room.RoomInfo;\n"
)


# ----------------------------------------------------------------------
# Fix2a: RoomBrowserScreenBase.getCardHeight()
# ----------------------------------------------------------------------

ANCHOR_CARD_HEIGHT = "      return 48;\n"
REPL_CARD_HEIGHT = (
    "      // 小高度屏幕：卡片缩为 36 以让出底部空间给分页/状态/提示/按钮\n"
    "      return this.height < 360 ? 36 : 48;\n"
)


# ----------------------------------------------------------------------
# Fix2b: RoomBrowserScreenBase — conditional loader badge
# ----------------------------------------------------------------------

ANCHOR_LOADER_BADGE = (
    "            graphics.fill(x + cardW - loaderW - 3, y + 35, x + cardW - 3, y + 45, COLOR_CAT_BADGE_BG);\n"
    "            this.drawString(graphics, ChatFormatting.GRAY.toString() + loaderLabel, x + cardW - loaderW - 1, y + 36, VoxLinkColors.CAT_BADGE_TEXT);\n"
)

REPL_LOADER_BADGE = (
    "            // 小卡片（cardH<48）跳过 loader 角标以免溢出\n"
    "            if (cardH >= 48) {\n"
    "               graphics.fill(x + cardW - loaderW - 3, y + 35, x + cardW - 3, y + 45, COLOR_CAT_BADGE_BG);\n"
    "               this.drawString(graphics, ChatFormatting.GRAY.toString() + loaderLabel, x + cardW - loaderW - 1, y + 36, VoxLinkColors.CAT_BADGE_TEXT);\n"
    "            }\n"
)


# ----------------------------------------------------------------------
# Fix2c: RoomBrowserScreenBase — hide hiddenHint on short screens
# ----------------------------------------------------------------------

ANCHOR_HIDDEN_HINT = (
    "      if (this.hiddenPasswordCount > 0 && this.displayedRooms.isEmpty()) {\n"
)
REPL_HIDDEN_HINT = (
    "      if (this.hiddenPasswordCount > 0 && this.displayedRooms.isEmpty() && this.height >= 360) {\n"
)


# ----------------------------------------------------------------------
# Fix2d: ManageRoomScreen — clamp status Y
# ----------------------------------------------------------------------

ANCHOR_STATUS_Y = (
    "         int formHeight = 230;\n"
    "         int y = Math.max(24, (this.height - formHeight) / 2);\n"
    "         this.drawCenteredString(graphics, clipped, centerX, y + 186, this.statusColor);\n"
)
REPL_STATUS_Y = (
    "         int formHeight = 230;\n"
    "         int y = Math.max(24, (this.height - formHeight) / 2);\n"
    "         // 矮屏时夹住状态行 Y，避免状态跑到按钮之上或屏幕外\n"
    "         int statusY = Math.min(y + 186, this.height - 12);\n"
    "         this.drawCenteredString(graphics, clipped, centerX, statusY, this.statusColor);\n"
)


# ----------------------------------------------------------------------
# Fix2e: VoxLinkScreen.computeLayout — lyTopStart >= headerFloor+4
# ----------------------------------------------------------------------

ANCHOR_LYTOPSTART = "            this.lyTopStart = Math.max(headerFloor, ideal);\n"
REPL_LYTOPSTART = (
    "            // 头部文字与第一行按钮至少留 4px 间距，避免极端矮屏时头部文字与按钮粘连\n"
    "            this.lyTopStart = Math.max(headerFloor + 4, ideal);\n"
)


def main():
    summary = []  # (template, label, action)

    for tmpl in TEMPLATES:
        cm_rel = f"{tmpl}/src/main/java/icu/wuhui/voxlink/room/ConnectionManager.java"
        rb_rel = f"{tmpl}/src/main/java/icu/wuhui/voxlink/network/RelayBridge.java"
        rbs_rel = f"{tmpl}/src/main/java/icu/wuhui/voxlink/ui/RoomBrowserScreenBase.java"
        mr_rel = f"{tmpl}/src/main/java/icu/wuhui/voxlink/ui/ManageRoomScreen.java"
        vl_rel = f"{tmpl}/src/main/java/icu/wuhui/voxlink/ui/VoxLinkScreen.java"

        # Fix1a: host_notice guard
        if is_121(tmpl):
            anchor, repl = ANCHOR_HOST_NOTICE_121, REPL_HOST_NOTICE_121
        else:
            anchor, repl = ANCHOR_HOST_NOTICE_OLD, REPL_HOST_NOTICE_OLD
        if replace_with_eol(cm_rel, anchor, repl, "fix1a host_notice"):
            summary.append((tmpl, "fix1a host_notice", "applied"))

        # Fix1b: relay.started guard (multi-line)
        if is_121(tmpl):
            anchor, repl = ANCHOR_RELAY_STARTED_121, REPL_RELAY_STARTED_121
        else:
            anchor, repl = ANCHOR_RELAY_STARTED_OLD, REPL_RELAY_STARTED_OLD
        if replace_with_eol(rb_rel, anchor, repl, "fix1b relay.started"):
            summary.append((tmpl, "fix1b relay.started", "applied"))

        # Fix1b: ensure RelayBridge has VoxLinkMod + RoomInfo imports
        if replace_with_eol(rb_rel, ANCHOR_RB_IMPORT, REPL_RB_IMPORT, "fix1b RelayBridge imports"):
            summary.append((tmpl, "fix1b RelayBridge imports", "applied"))

        # Fix2a: getCardHeight
        if replace_with_eol(rbs_rel, ANCHOR_CARD_HEIGHT, REPL_CARD_HEIGHT, "fix2a getCardHeight"):
            summary.append((tmpl, "fix2a getCardHeight", "applied"))

        # Fix2b: conditional loader badge
        if replace_with_eol(rbs_rel, ANCHOR_LOADER_BADGE, REPL_LOADER_BADGE, "fix2b loader badge"):
            summary.append((tmpl, "fix2b loader badge", "applied"))

        # Fix2c: hide hiddenHint on short screens
        if replace_with_eol(rbs_rel, ANCHOR_HIDDEN_HINT, REPL_HIDDEN_HINT, "fix2c hiddenHint gate"):
            summary.append((tmpl, "fix2c hiddenHint gate", "applied"))

        # Fix2d: ManageRoomScreen status Y clamp
        if replace_with_eol(mr_rel, ANCHOR_STATUS_Y, REPL_STATUS_Y, "fix2d status Y clamp"):
            summary.append((tmpl, "fix2d status Y clamp", "applied"))

        # Fix2e: VoxLinkScreen lyTopStart min gap
        if replace_with_eol(vl_rel, ANCHOR_LYTOPSTART, REPL_LYTOPSTART, "fix2e lyTopStart min gap"):
            summary.append((tmpl, "fix2e lyTopStart min gap", "applied"))

    print(f"\n=== Summary: {len(summary)} anchor replacements applied across 25 templates ===")
    for t, lbl, action in summary:
        print(f"  {t:<30}  {lbl:<28}  {action}")
    print(f"\ntotal applied: {len(summary)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())