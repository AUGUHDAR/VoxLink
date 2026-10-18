# -*- coding: utf-8 -*-
"""工单客户端传播侦察：TitleScreenMixin / FeedbackScreen / FeedbackFormScreen 簇数"""
import glob, hashlib, os

BASE = r"d:\桌面\voxlink"
for name, pat in [
    ("TitleScreenMixin", os.path.join("mixin", "TitleScreenMixin.java")),
    ("FeedbackScreen", os.path.join("ui", "FeedbackScreen.java")),
    ("FeedbackFormScreen", os.path.join("ui", "FeedbackFormScreen.java")),
]:
    files = sorted(glob.glob(os.path.join(BASE, "*", "*", "src", "main", "java", "icu", "wuhui", "voxlink", *pat.split(os.sep))))
    clusters = {}
    for f in files:
        raw = open(f, "rb").read()
        norm = raw.replace(b"\r\n", b"\n").replace(b"\r", b"\n")
        clusters.setdefault(hashlib.md5(norm).hexdigest(), []).append(f)
    print("%s: %d files, %d clusters" % (name, len(files), len(clusters)))
    for h, lst in clusters.items():
        rep = lst[0]
        t = open(rep, "rb").read().decode("utf-8", "replace").replace("\r\n", "\n")
        gui = "gui.setScreen" in t
        ssas = "setScreenAndShow" in t
        print("  %s count=%d gui.setScreen=%s setScreenAndShow=%s" % (h[:10], len(lst), gui, ssas))
        for p in lst[:2]:
            print("     ", os.path.relpath(p, BASE))
    print()
