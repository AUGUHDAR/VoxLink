# -*- coding: utf-8 -*-
"""TURN 双 bug 修复传播：fabric/26.3(已修) 整拷至 27 模板，保留各目标 EOL；幂等"""
import glob, hashlib, os, sys

BASE = r"d:\桌面\voxlink"
GOLD = os.path.join(BASE, "fabric", "26.3", "src", "main", "java", "icu", "wuhui", "voxlink", "network", "ReliableUdpTransport.java")

FIX_MARKERS = [
    "TURN_MAX_SEND_CHUNK = 1374",
    "private int currentMaxSendChunk()",
    "private void processDatagram(byte[] rx, int rxLen,",
    "byte[] frame = path.codec.decode(rx, rxLen);",
    "int chunkLen = Math.min(this.currentMaxSendChunk()",
    "if (payload.length > this.currentMaxSendChunk())",
]

gold_raw = open(GOLD, "rb").read()
gold_norm = gold_raw.replace(b"\r\n", b"\n")

# 金标准自检
for m in FIX_MARKERS:
    n = gold_norm.decode("utf-8").count(m)
    assert n == 1, "gold marker %r count=%d" % (m, n)
# 金标准必须是 LF（侦察结果如此）
assert b"\r\n" not in gold_raw, "gold is not LF?!"

targets = sorted(glob.glob(os.path.join(BASE, "*", "*", "src", "main", "java", "icu", "wuhui", "voxlink", "network", "ReliableUdpTransport.java")))
assert len(targets) == 28, len(targets)

done, errors = [], []
for f in targets:
    rel = os.path.relpath(f, BASE)
    if f == GOLD:
        done.append(rel + ": gold, skip")
        continue
    raw = open(f, "rb").read()
    norm = raw.replace(b"\r\n", b"\n")
    if norm == gold_norm:
        done.append(rel + ": already fixed, skip")
        continue
    # 幂等：已含修复标记则跳过（防重复写）
    t = norm.decode("utf-8", errors="replace")
    if FIX_MARKERS[0] in t and FIX_MARKERS[2] in t:
        done.append(rel + ": markers present but content differs! CHECK")
        errors.append(rel + ": partial fix state")
        continue
    eol = b"\r\n" if b"\r\n" in raw else b"\n"
    out = gold_norm.replace(b"\n", eol)
    open(f, "wb").write(out)
    done.append(rel + ": copied (EOL=%s)" % ("CRLF" if eol == b"\r\n" else "LF"))

# 终检：全 28 归一化同簇 + 标记各 1 次
clusters = set()
for f in targets:
    norm = open(f, "rb").read().replace(b"\r\n", b"\n")
    clusters.add(hashlib.md5(norm).hexdigest())
    t = norm.decode("utf-8", errors="replace")
    for m in FIX_MARKERS:
        if t.count(m) != 1:
            errors.append("%s: marker %r count=%d" % (os.path.relpath(f, BASE), m, t.count(m)))

print("clusters after propagation:", len(clusters))
print("done:", len(done))
for d in done:
    print("  ", d)
print("errors:", len(errors))
for e in errors:
    print("  ERR", e)
sys.exit(1 if errors or len(clusters) != 1 else 0)
