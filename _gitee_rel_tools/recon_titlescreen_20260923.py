# -*- coding: utf-8 -*-
"""TitleScreenMixin 28 模板分簇 + setScreen 写法侦察"""
import glob, hashlib, os

BASE = r"d:\桌面\voxlink"
files = sorted(glob.glob(os.path.join(BASE, "*", "*", "src", "main", "java", "icu", "wuhui", "voxlink", "mixin", "TitleScreenMixin.java")))
print("files:", len(files))
clusters = {}
for f in files:
    raw = open(f, "rb").read()
    norm = raw.replace(b"\r\n", b"\n")
    clusters.setdefault(hashlib.md5(norm).hexdigest(), []).append(f)

for h, lst in clusters.items():
    rep = lst[0]
    t = open(rep, "rb").read().decode("utf-8").replace("\r\n", "\n")
    setline = [l.strip() for l in t.split("\n") if "setScreen" in l and "Minecraft" in l]
    print("cluster %s count=%d" % (h[:10], len(lst)))
    print("   setScreen:", setline[:1])
    for p in lst[:4]:
        print("   ", os.path.relpath(p, BASE))
    if len(lst) > 4:
        print("    ... +%d" % (len(lst) - 4))
    print()
