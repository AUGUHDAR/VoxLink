# -*- coding: utf-8 -*-
# 2026-10-05 ModSync 门控前置: 房主未声明 modSyncV1 时跳过选择窗直接开连
import io, os, re, sys

ROOT = r"D:\桌面\voxlink"
TEMPLATES = []
for loader in ("fabric", "forge", "neoforge"):
    d = os.path.join(ROOT, loader)
    for v in sorted(os.listdir(d)):
        p = os.path.join(d, v, "src", "main", "java", "icu", "wuhui", "voxlink", "ui", "AttemptingJoinScreen.java")
        if os.path.isfile(p):
            TEMPLATES.append((loader + "/" + v, p))

BASE = "fabric/1.20.2_1.20.6"

ANCHOR = re.compile(
    r"(&&\s*!icu\.wuhui\.voxlink\.modsync\.ModSyncGuestService\.shouldSkipGate\(this\.roomCode\)\)\s*\{)(\s*)(this\.modSyncChecking = true;)"
)

INJECT_LINES = [
    "icu.wuhui.voxlink.room.RoomInfo curInfo = VoxLinkMod.getRoomManager().getCurrentRoom();",
    "if (curInfo != null && !curInfo.getHostCapabilities().contains(icu.wuhui.voxlink.network.ProtocolNegotiator.CAP_MOD_SYNC_V1)) {",
    "// 房主未开启模组上传(建房caps声明已定谳)：不弹选择窗，直接开连",
    "VoxLinkMod.LOGGER.info(\"[ModSync] host has no modSyncV1 cap, skip gate, room={}\", this.roomCode);",
    "icu.wuhui.voxlink.ui.UiLogBus.push(2, \"voxlink.logui.modsync_unsupported\");",
    "this.doStartDualP2P();",
    "return;",
    "}",
    "",
]

changed, skipped, failed = [], [], []
for name, p in TEMPLATES:
    with io.open(p, encoding="utf-8", newline="") as fh:
        s = fh.read()
    if name == BASE:
        skipped.append((name, "baseline"))
        continue
    if "host has no modSyncV1 cap" in s:
        skipped.append((name, "already-fixed"))
        continue
    m = ANCHOR.search(s)
    if not m:
        failed.append((name, "anchor"))
        continue
    nl = "\r\n" if "\r\n" in s else "\n"
    indent = m.group(2)  # this.modSyncChecking 行的缩进(含换行前导)
    lead = indent.lstrip("\r\n")
    block = nl + nl.join(lead + x for x in INJECT_LINES) + nl + lead
    s2 = s[: m.start()] + m.group(1) + block + m.group(3) + s[m.end():]
    with io.open(p, "w", encoding="utf-8", newline="") as fh:
        fh.write(s2)
    changed.append(name)

print("CHANGED", len(changed))
for c in changed: print("  +", c)
print("SKIPPED", len(skipped))
for c, r in skipped: print("  =", c, r)
print("FAILED", len(failed))
for c, r in failed: print("  !", c, r)
sys.exit(1 if failed else 0)
