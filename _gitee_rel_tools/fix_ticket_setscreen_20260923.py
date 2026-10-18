# -*- coding: utf-8 -*-
"""修复 19 个老时代模板的 gui.setScreen → setScreen（Ticket 两文件）"""
import os, sys

BASE = r"d:\桌面\voxlink"
OLD = "Minecraft.getInstance().gui.setScreen("
NEW = "Minecraft.getInstance().setScreen("
FILES = [os.path.join("ui", "TicketListScreen.java"), os.path.join("ui", "TicketDetailScreen.java")]

old_era = []
import glob
for f in sorted(glob.glob(os.path.join(BASE, "*", "*", "src", "main", "java", "icu", "wuhui", "voxlink", "ui", "VoxLinkScreenBase.java"))):
    t = open(f, "rb").read().decode("utf-8", "replace")
    if "import net.minecraft.client.gui.GuiGraphics;" in t and "GuiGraphicsExtractor" not in t:
        parts = os.path.relpath(f, BASE).split(os.sep)
        old_era.append(os.path.join(parts[0], parts[1]))

errors = []
fixed = 0
for tpl in old_era:
    for rel in FILES:
        p = os.path.join(BASE, tpl, "src", "main", "java", "icu", "wuhui", "voxlink", *rel.split(os.sep))
        raw = open(p, "rb").read()
        eol = "\r\n" if b"\r\n" in raw else "\n"
        t = raw.decode("utf-8").replace("\r\n", "\n")
        n = t.count(OLD)
        if n == 0:
            continue
        if NEW in t:
            errors.append("%s/%s: mixed state" % (tpl, rel))
            continue
        t = t.replace(OLD, NEW)
        open(p, "wb").write(t.replace("\n", eol).encode("utf-8"))
        fixed += 1

print("old-era:", len(old_era), "fixed files:", fixed, "errors:", len(errors))
for e in errors:
    print("  ERR", e)
sys.exit(1 if errors else 0)
