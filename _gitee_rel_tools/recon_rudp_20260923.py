# -*- coding: utf-8 -*-
"""ReliableUdpTransport 28 模板哈希分簇（传播前侦察）"""
import glob, hashlib, os

BASE = r"d:\桌面\voxlink"
files = sorted(glob.glob(os.path.join(BASE, "*", "*", "src", "main", "java", "icu", "wuhui", "voxlink", "network", "ReliableUdpTransport.java")))
print("files:", len(files))

# 按内容归一化（\r\n→\n）后 md5 分簇
clusters = {}
for f in files:
    raw = open(f, "rb").read()
    norm = raw.replace(b"\r\n", b"\n")
    h = hashlib.md5(norm).hexdigest()
    clusters.setdefault(h, []).append((os.path.relpath(f, BASE), "CRLF" if b"\r\n" in raw else "LF"))

for h, lst in clusters.items():
    eols = {e for _, e in lst}
    print("cluster", h[:10], "count=%d" % len(lst), "EOL=%s" % eols)
    for p, _ in lst[:3]:
        print("   ", p)
    if len(lst) > 3:
        print("    ...")
