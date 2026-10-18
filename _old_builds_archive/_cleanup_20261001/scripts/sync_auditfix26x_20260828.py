#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
sync_auditfix26x_20260828.py

Propagates the audit-fix wave from main template (fabric/1.20.2_1.20.6) to the
six 26.x era templates.  Each target file is either copied verbatim (if the
HEAD-to-HEAD diff vs the main template, ignoring whitespace, is empty — i.e.
the difference is only blank-line compression) or copied with 26.x API
transforms applied.

Rules (validated against the 26.2 merged jar via javap):
  * import net.minecraft.client.gui.GuiGraphics; -> GuiGraphicsExtractor;
  * render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick)
        -> extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick)
  * super.render( -> super.extractRenderState(
  * mc.screen -> mc.gui.screen()
  * Minecraft.getInstance().screen -> Minecraft.getInstance().gui.screen()
  * Minecraft.getInstance().setScreen(X) -> Minecraft.getInstance().gui.setScreen(X)
  * mc.setScreen(X) -> mc.gui.setScreen(X)

For 26.1 templates: the file is first diffed HEAD-vs-HEAD (whitespace
insensitive); if there is a non-empty diff, the same rules are applied.
The actual API shape used by 26.1 HEAD is also accounted for, so e.g. if
26.1 already uses extractRenderState the import/render rules become no-ops
while the setScreen/screen() rules still fire.

This script does NOT git-add anything and does NOT run any build.
"""
import os
import re
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(r"D:/桌面/voxlink").resolve()
MAIN = "fabric/1.20.2_1.20.6"
TARGETS = [
    "fabric/26.1_26.1.2",
    "fabric/26.2",
    "forge/26.1_26.1.2",
    "forge/26.2",
    "neoforge/26.1_26.1.2",
    "neoforge/26.2",
]

# 29 java files (relative to src/main/java/icu/wuhui/voxlink/)
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

# 13 lang files (relative to src/main/resources/assets/voxlink/lang/)
LANG_FILES = [
    "en_us.json", "zh_cn.json", "ar_sa.json", "de_de.json", "es_es.json",
    "fr_fr.json", "ja_jp.json", "ko_kr.json", "lzh.json", "pt_br.json",
    "ru_ru.json", "zh_hk.json", "zh_tw.json",
]

JAVA_BASE = "src/main/java/icu/wuhui/voxlink"
LANG_BASE = "src/main/resources/assets/voxlink/lang"


def git_show(rev: str, path: str) -> bytes:
    """Return git-tracked file bytes at rev, or None if missing."""
    res = subprocess.run(
        ["git", "show", f"{rev}:{path}"],
        cwd=str(REPO_ROOT), capture_output=True,
    )
    if res.returncode != 0:
        return None
    return res.stdout


def workspace_read(path: str) -> bytes:
    full = REPO_ROOT / path
    return full.read_bytes()


def normalize_for_diff(data: bytes) -> bytes:
    """Collapse all whitespace for diff comparison."""
    text = data.decode("utf-8", errors="replace")
    return re.sub(rb"\s+", b" ", text.encode("utf-8"))


def apply_26x_transforms(data: bytes, applied: list, target_head: bytes) -> bytes:
    """Apply the 26.x API replacement rules in place.  `applied` collects
    human-readable rule labels that fired.  `target_head` is the file at
    target's HEAD — used to decide which rules to actually run, because
    26.1 is a mixed era: for a rule we apply it only if the target HEAD
    already uses the new form of the API (e.g. has 'extractRenderState'
    but the audit-fix content from main uses 'render(GuiGraphics ...)')."""
    text = data.decode("utf-8")
    head_text = target_head.decode("utf-8") if target_head is not None else ""

    # Detect which API form the target HEAD already uses.
    def has(needle: str) -> bool:
        return needle in head_text

    has_gge = has("import net.minecraft.client.gui.GuiGraphicsExtractor;")
    has_extract_rs = "extractRenderState(" in head_text
    has_gui_setScreen = "gui.setScreen(" in head_text
    has_gui_screen = ".gui.screen()" in head_text

    # Target uses NEW setScreen form if its HEAD has gui.setScreen anywhere
    # (it might also still have plain setScreen in unrelated places, so we
    # rely on the new-form presence as the positive signal).
    target_on_new_setScreen = has_gui_setScreen
    # The .screen getter and .setScreen() setter change together in the
    # 26.2 refactor: any target HEAD that uses the new form for one uses
    # the new form for the other as well.  Some files only exercise one
    # of the two (e.g. TerracottaConfigScreen uses gui.setScreen but
    # never calls .screen; RoomManager uses .gui.screen() but never
    # opens a screen).  Either new-form occurrence is the positive
    # signal that this file lives on the new API.
    target_on_new_screen = has_gui_setScreen or has_gui_screen

    # ---- Rule 1: import line ----
    # Transform only if the target HEAD is already on the Extractor API
    # (i.e. expects GuiGraphicsExtractor).  For 26.1 mixed era where the
    # target HEAD still uses GuiGraphics;, leave the import alone.
    if "import net.minecraft.client.gui.GuiGraphics;" in text and has_gge:
        text = text.replace("import net.minecraft.client.gui.GuiGraphics;",
                            "import net.minecraft.client.gui.GuiGraphicsExtractor;")
        applied.append("import:GuiGraphics->GuiGraphicsExtractor")

    # ---- Rule 2: render(GuiGraphics ...) signature ----
    sig_old = "render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick)"
    sig_new = "extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick)"
    if sig_old in text and has_extract_rs:
        text = text.replace(sig_old, sig_new)
        applied.append("render(GuiGraphics...)->extractRenderState(...)")

    # ---- Rule 3: super.render -> super.extractRenderState ----
    if "super.render(" in text and has_extract_rs:
        text = text.replace("super.render(", "super.extractRenderState(")
        applied.append("super.render->super.extractRenderState")

    # ---- Rule 4: setScreen transformations ----
    if "Minecraft.getInstance().setScreen(" in text and target_on_new_setScreen:
        text = text.replace("Minecraft.getInstance().setScreen(",
                            "Minecraft.getInstance().gui.setScreen(")
        applied.append("Minecraft.getInstance().setScreen->gui.setScreen")

    if "mc.setScreen(" in text and target_on_new_setScreen:
        text = text.replace("mc.setScreen(", "mc.gui.setScreen(")
        applied.append("mc.setScreen->mc.gui.setScreen")

    import re as _re
    pat_mcN = _re.compile(r"\bmc(\d+)\.setScreen\(")
    if pat_mcN.search(text) and target_on_new_setScreen:
        text = pat_mcN.sub(r"mc\1.gui.setScreen(", text)
        applied.append("mcN.setScreen->mcN.gui.setScreen (N>=0)")

    # ---- Rule 5: screen() transformations ----
    if "Minecraft.getInstance().screen" in text and target_on_new_screen:
        text = text.replace("Minecraft.getInstance().screen",
                            "Minecraft.getInstance().gui.screen()")
        applied.append("Minecraft.getInstance().screen->gui.screen()")

    if "mc.screen" in text and target_on_new_screen:
        text = text.replace("mc.screen", "mc.gui.screen()")
        applied.append("mc.screen->mc.gui.screen()")

    pat_mcNs = _re.compile(r"\bmc(\d+)\.screen\b")
    if pat_mcNs.search(text) and target_on_new_screen:
        text = pat_mcNs.sub(r"mc\1.gui.screen()", text)
        applied.append("mcN.screen->mcN.gui.screen() (N>=0)")

    return text.encode("utf-8")


def main():
    summary = []  # (template, rel_path, action, rules)
    overall = {t: {"copy": 0, "transform": 0} for t in TARGETS}

    for target in TARGETS:
        # java files
        for rel in JAVA_FILES:
            main_path = f"{MAIN}/{JAVA_BASE}/{rel}"
            tgt_path = f"{target}/{JAVA_BASE}/{rel}"
            main_head = git_show("HEAD", main_path)
            main_ws = workspace_read(main_path)
            if main_head is None or main_ws is None:
                print(f"SKIP (missing main): {main_path}", file=sys.stderr)
                continue

            tgt_head = git_show("HEAD", tgt_path)
            if tgt_head is None:
                print(f"SKIP (missing target HEAD): {tgt_path}", file=sys.stderr)
                continue

            # Decision rule per task: compare main HEAD vs target HEAD
            # (whitespace-insensitive).  If equal -> only blank-line
            # compression differs -> copy main workspace verbatim.  If not
            # equal -> era API differences exist -> apply transforms to main
            # workspace content.
            head_diff_empty = (
                normalize_for_diff(main_head) == normalize_for_diff(tgt_head)
            )

            if head_diff_empty:
                action = "copy"
                data = main_ws
                rules = []
            else:
                action = "transform"
                rules = []
                data = apply_26x_transforms(main_ws, rules, tgt_head)

            # Write to workspace of target (overwriting).
            full = REPO_ROOT / tgt_path
            full.parent.mkdir(parents=True, exist_ok=True)
            with open(full, "wb") as f:
                f.write(data)

            overall[target][action] = overall[target].get(action, 0) + 1
            summary.append((target, rel, action, rules))

        # lang files
        for rel in LANG_FILES:
            main_path = f"{MAIN}/{LANG_BASE}/{rel}"
            tgt_path = f"{target}/{LANG_BASE}/{rel}"
            main_head = git_show("HEAD", main_path)
            main_ws = workspace_read(main_path)
            if main_head is None or main_ws is None:
                print(f"SKIP (missing main): {main_path}", file=sys.stderr)
                continue
            tgt_head = git_show("HEAD", tgt_path)
            if tgt_head is None:
                print(f"SKIP (missing target HEAD): {tgt_path}", file=sys.stderr)
                continue
            head_diff_empty = (
                normalize_for_diff(main_head) == normalize_for_diff(tgt_head)
            )
            if head_diff_empty:
                action = "copy"
                data = main_ws
                rules = []
            else:
                action = "transform"
                rules = []
                data = apply_26x_transforms(main_ws, rules, tgt_head)
            full = REPO_ROOT / tgt_path
            full.parent.mkdir(parents=True, exist_ok=True)
            with open(full, "wb") as f:
                f.write(data)
            overall[target][action] = overall[target].get(action, 0) + 1
            summary.append((target, rel, action, rules))

    # ---- Per-file log ----
    print("=== Per-file actions ===")
    for target, rel, action, rules in summary:
        rule_str = ",".join(rules) if rules else "-"
        print(f"{target}\t{rel}\t{action}\t{rule_str}")

    # ---- Per-template totals ----
    print("\n=== Per-template totals ===")
    for t, c in overall.items():
        print(f"{t}: copy={c.get('copy',0)}  transform={c.get('transform',0)}")


if __name__ == "__main__":
    main()
