# -*- coding: utf-8 -*-
"""修复 19 个老时代模板的 TicketListScreen/TicketDetailScreen：Extractor→GuiGraphics/render"""
import glob, os, sys

BASE = r"d:\桌面\voxlink"
GOLD = os.path.join(BASE, "fabric", "26.3")

OLD_IMPORT = "import net.minecraft.client.gui.GuiGraphicsExtractor;"
NEW_IMPORT = "import net.minecraft.client.gui.GuiGraphics;"
OLD_SIG = "public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {"
NEW_SIG = "public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {"
OLD_SUP = "super.extractRenderState(graphics, mouseX, mouseY, partialTick);"
NEW_SUP = "super.render(graphics, mouseX, mouseY, partialTick);"

FILES = [os.path.join("ui", "TicketListScreen.java"), os.path.join("ui", "TicketDetailScreen.java")]

# 老时代模板 = VoxLinkScreenBase 用 GuiGraphics 的
old_era = []
for f in sorted(glob.glob(os.path.join(BASE, "*", "*", "src", "main", "java", "icu", "wuhui", "voxlink", "ui", "VoxLinkScreenBase.java"))):
    t = open(f, "rb").read().decode("utf-8", "replace")
    if "import net.minecraft.client.gui.GuiGraphics;" in t and "GuiGraphicsExtractor" not in t:
        parts = os.path.relpath(f, BASE).split(os.sep)
        old_era.append(os.path.join(parts[0], parts[1]))

print("old-era templates:", len(old_era))
errors = []
fixed = 0
for tpl in old_era:
    for rel in FILES:
        p = os.path.join(BASE, tpl, "src", "main", "java", "icu", "wuhui", "voxlink", *rel.split(os.sep))
        raw = open(p, "rb").read()
        eol = "\r\n" if b"\r\n" in raw else "\n"
        t = raw.decode("utf-8").replace("\r\n", "\n")
        if NEW_IMPORT in t and OLD_IMPORT not in t:
            continue  # 已修
        ok = (t.count(OLD_IMPORT) == 1 and t.count(OLD_SIG) == 1 and t.count(OLD_SUP) == 1)
        if not ok:
            errors.append("%s/%s: import=%d sig=%d sup=%d" % (tpl, rel, t.count(OLD_IMPORT), t.count(OLD_SIG), t.count(OLD_SUP)))
            continue
        t = t.replace(OLD_IMPORT, NEW_IMPORT, 1).replace(OLD_SIG, NEW_SIG, 1).replace(OLD_SUP, NEW_SUP, 1)
        open(p, "wb").write(t.replace("\n", eol).encode("utf-8"))
        fixed += 1

print("fixed:", fixed, "errors:", len(errors))
for e in errors:
    print("  ERR", e)
sys.exit(1 if errors else 0)
