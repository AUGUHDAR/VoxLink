# -*- coding: utf-8 -*-
"""各时代 VoxLinkScreenBase 渲染方法签名侦察"""
import glob, hashlib, os, re

BASE = r"d:\桌面\voxlink"
files = sorted(glob.glob(os.path.join(BASE, "*", "*", "src", "main", "java", "icu", "wuhui", "voxlink", "ui", "VoxLinkScreenBase.java")))
clusters = {}
for f in files:
    raw = open(f, "rb").read()
    norm = raw.replace(b"\r\n", b"\n")
    t = norm.decode("utf-8", "replace")
    m = re.search(r"import net\.minecraft\.client\.gui\.(\w+);", t)
    imp = m.group(1) if m else "?"
    m2 = re.search(r"protected void (render|extractRenderState)\((\w+) ", t)
    sig = "%s(%s)" % (m2.group(1), m2.group(2)) if m2 else "?"
    clusters.setdefault((imp, sig), []).append(os.path.relpath(f, BASE).split(os.sep)[0] + "/" + os.path.relpath(f, BASE).split(os.sep)[1])

for (imp, sig), lst in clusters.items():
    print("%s %s -> %d 模板: %s" % (imp, sig, len(lst), ", ".join(lst)))
