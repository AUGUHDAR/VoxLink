# -*- coding: utf-8 -*-
"""工单客户端传播终检（修正断言数值）"""
import glob, hashlib, os, sys

BASE = r"d:\桌面\voxlink"
GOLD = os.path.join(BASE, "fabric", "26.3")

def rep(f):
    return os.path.relpath(f, BASE)

NEW_FILES = [
    os.path.join("network", "TicketClient.java"),
    os.path.join("ui", "TicketListScreen.java"),
    os.path.join("ui", "TicketDetailScreen.java"),
]
templates = []
for loader in ("fabric", "forge", "neoforge"):
    ldir = os.path.join(BASE, loader)
    for grp in sorted(os.listdir(ldir)):
        if os.path.isdir(os.path.join(ldir, grp)):
            templates.append(os.path.join(loader, grp))

miss = []
for rel in NEW_FILES:
    src = os.path.join(GOLD, "src", "main", "java", "icu", "wuhui", "voxlink", *rel.split(os.sep))
    h0 = hashlib.md5(open(src, "rb").read()).hexdigest()
    for t in templates:
        dst = os.path.join(BASE, t, "src", "main", "java", "icu", "wuhui", "voxlink", *rel.split(os.sep))
        if hashlib.md5(open(dst, "rb").read()).hexdigest() != h0:
            miss.append("newfile mismatch: " + rep(dst))

tsm_files = glob.glob(os.path.join(BASE, "*", "*", "src", "main", "java", "icu", "wuhui", "voxlink", "mixin", "TitleScreenMixin.java"))
fs_files = glob.glob(os.path.join(BASE, "*", "*", "src", "main", "java", "icu", "wuhui", "voxlink", "ui", "FeedbackScreen.java"))
ffs_files = glob.glob(os.path.join(BASE, "*", "*", "src", "main", "java", "icu", "wuhui", "voxlink", "ui", "FeedbackFormScreen.java"))
assert len(tsm_files) == 28 and len(fs_files) == 28 and len(ffs_files) == 28

for f in tsm_files:
    t = open(f, "rb").read().decode("utf-8", "replace").replace("\r\n", "\n")
    if t.count("pollOnceIfNeeded") != 1:
        miss.append("%s: pollOnceIfNeeded=%d" % (rep(f), t.count("pollOnceIfNeeded")))
    if t.count("voxlink.ticket.notify") != 1:
        miss.append("%s: ticket.notify=%d" % (rep(f), t.count("voxlink.ticket.notify")))
    # 时代一致性：文件内 setScreenAndShow 与 poll 回调的 screen 访问必须同代
    modern = "setScreenAndShow" in t
    if modern and "mc.gui.screen()" not in t:
        miss.append("%s: modern but no gui.screen" % rep(f))
    if not modern and "mc.gui.screen()" in t:
        miss.append("%s: old-era but has gui.screen" % rep(f))

for f in fs_files:
    t = open(f, "rb").read().decode("utf-8", "replace").replace("\r\n", "\n")
    if t.count("voxlink.ticket.my") != 1:
        miss.append("%s: ticket.my=%d" % (rep(f), t.count("voxlink.ticket.my")))
    if t.count("TicketListScreen") != 1:
        miss.append("%s: TicketListScreen=%d" % (rep(f), t.count("TicketListScreen")))

for f in ffs_files:
    t = open(f, "rb").read().decode("utf-8", "replace").replace("\r\n", "\n")
    if t.count("TicketClient.submit") != 1:
        miss.append("%s: TicketClient.submit=%d" % (rep(f), t.count("TicketClient.submit")))
    if t.count("createdTicketId") != 5:
        miss.append("%s: createdTicketId=%d" % (rep(f), t.count("createdTicketId")))
    if "FeedbackUploader.submit(" in t:
        miss.append("%s: old submit call remains" % rep(f))

print("final check:", "ALL PASS" if not miss else "%d issues" % len(miss))
for m in miss[:30]:
    print("  MISS", m)
sys.exit(0 if not miss else 1)
