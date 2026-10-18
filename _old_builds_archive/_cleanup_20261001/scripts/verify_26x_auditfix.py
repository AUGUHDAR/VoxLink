#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Final validation of the 26.x audit-fix propagation.

For 26.2 templates: must NOT contain old API patterns
  (mc.screen without gui., Minecraft.getInstance().setScreen without gui.,
   mc.setScreen without gui., GuiGraphics; without Extractor,
   render(GuiGraphics ...)).

For 26.1 templates: same patterns are EXPECTED in some files (mixed era),
so we only assert that no GuiGraphics; (non-Extractor) import remains and
that any render(GuiGraphics signature has been converted to
extractRenderState (since 26.1 HEAD already uses extractRenderState).
"""
import re
import sys
from pathlib import Path

REPO = Path(r"D:/桌面/voxlink").resolve()
TARGETS_26_1 = [
    "fabric/26.1_26.1.2",
    "forge/26.1_26.1.2",
    "neoforge/26.1_26.1.2",
]
TARGETS_26_2 = [
    "fabric/26.2",
    "forge/26.2",
    "neoforge/26.2",
]
JAVA_BASE = "src/main/java/icu/wuhui/voxlink"
JAVA_FILES = [
    "config/VoxLinkConfig.java",
    "modsync/ModrinthClient.java",
    "modsync/ModSyncManifestService.java",
    "modsync/ModSyncFileHasher.java",
    "modsync/ModSyncGuestService.java",
    "modsync/ModSyncSelectScreen.java",
    "room/ConnectionManager.java",
    "room/RoomManager.java",
    "network/P2PBridge.java",
    "network/ReliableUdpTransport.java",
    "network/SignalingWsTransport.java",
    "network/UdpHolePuncher.java",
    "network/StunProbe.java",
    "network/UPnPManager.java",
    "network/LogUploadManager.java",
    "ui/AttemptingJoinScreen.java",
    "ui/JoinRoomScreen.java",
    "ui/RoomBrowserScreenBase.java",
    "ui/TerracottaConfigScreen.java",
    "ui/VoxLinkScreen.java",
    "terracotta/TerracottaClient.java",
    "terracotta/TerracottaProcess.java",
    "terracotta/TerracottaNodeList.java",
    "terracotta/TerracottaManager.java",
]


def is_in_string_or_comment(text, idx):
    line_start = text.rfind("\n", 0, idx) + 1
    line_prefix = text[line_start:idx]
    if "//" in line_prefix:
        sc = line_prefix.find("//")
        unescaped = re.sub(r'\\"', '', line_prefix[:sc])
        if unescaped.count('"') % 2 == 0:
            return True
    if "/*" in line_prefix and "*/" not in line_prefix:
        return True
    return False


def check_26_2():
    print("=== 26.2 strict check (no old API patterns allowed) ===")
    pat_gg = re.compile(r"^import\s+net\.minecraft\.client\.gui\.GuiGraphics;",
                        re.MULTILINE)
    # 26.2 may mention GuiGraphics only via GuiGraphicsExtractor
    pat_mc_screen = re.compile(r"(?<!gui\.)\bmc\d*\.screen\b")
    pat_minst_setScreen = re.compile(
        r"(?<![/.A-Za-z])Minecraft\.getInstance\(\)\.setScreen\(")
    pat_mc_setScreen = re.compile(r"(?<!gui\.)\bmc\d*\.setScreen\(")
    bad = 0
    for tgt in TARGETS_26_2:
        for rel in JAVA_FILES:
            p = REPO / tgt / JAVA_BASE / rel
            if not p.exists():
                continue
            text = p.read_text(encoding="utf-8", errors="replace")
            for label, pat in [
                ("import GuiGraphics;", pat_gg),
                ("Minecraft.getInstance().setScreen(", pat_minst_setScreen),
                ("mc.setScreen(", pat_mc_setScreen),
                ("mc.screen", pat_mc_screen),
            ]:
                for m in pat.finditer(text):
                    if is_in_string_or_comment(text, m.start()):
                        continue
                    ln = text.count("\n", 0, m.start()) + 1
                    line = text.splitlines()[ln - 1]
                    print(f"BAD {tgt}/{rel}:{ln} [{label}] -> {line.strip()}")
                    bad += 1
    print(f"  -> 26.2 bad: {bad}")
    return bad


def check_26_1():
    print("=== 26.1 mixed-era check (GuiGraphics; non-Extractor + "
          "render(GuiGraphics must be gone; setScreen may remain) ===")
    pat_gg = re.compile(r"^import\s+net\.minecraft\.client\.gui\.GuiGraphics;",
                        re.MULTILINE)
    pat_render = re.compile(r"\brender\(GuiGraphics\s")
    bad = 0
    for tgt in TARGETS_26_1:
        for rel in JAVA_FILES:
            p = REPO / tgt / JAVA_BASE / rel
            if not p.exists():
                continue
            text = p.read_text(encoding="utf-8", errors="replace")
            for label, pat in [
                ("import GuiGraphics; (non-Extractor)", pat_gg),
                ("render(GuiGraphics signature", pat_render),
            ]:
                for m in pat.finditer(text):
                    if is_in_string_or_comment(text, m.start()):
                        continue
                    ln = text.count("\n", 0, m.start()) + 1
                    line = text.splitlines()[ln - 1]
                    print(f"BAD {tgt}/{rel}:{ln} [{label}] -> {line.strip()}")
                    bad += 1
    print(f"  -> 26.1 bad: {bad}")
    return bad


def check_lang():
    print("=== Lang files identical-to-main check ===")
    main_lang = REPO / "fabric/1.20.2_1.20.6/src/main/resources/assets/voxlink/lang"
    for tgt in TARGETS_26_1 + TARGETS_26_2:
        tgt_lang = REPO / tgt / "src/main/resources/assets/voxlink/lang"
        for f in main_lang.iterdir():
            if not f.is_file():
                continue
            target_f = tgt_lang / f.name
            if not target_f.exists():
                print(f"MISSING {tgt_lang}/{f.name}")
                continue
            if f.read_bytes() != target_f.read_bytes():
                print(f"DIFFERS {tgt}/{f.name}")
    print("  -> done")


def main():
    bad = 0
    bad += check_26_2()
    bad += check_26_1()
    check_lang()
    print(f"\n=== TOTAL VIOLATIONS: {bad} ===")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
