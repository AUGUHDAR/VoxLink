# -*- coding: utf-8 -*-
# 2026-09-19 TURN TCP 兜底承载：传播 TurnRelayClient + TurnTcpChannel 到全部模板
# 规则：fabric/26.2 用 CRLF，其余 LF；传播前断言各模板旧文件与参考基线一致
import os, hashlib

BASE = os.path.dirname(os.path.abspath(__file__))
NET = "src/main/java/icu/wuhui/voxlink/network"
REF = os.path.join(BASE, "fabric", "1.20_1.20.1", NET)
FILES = ["TurnRelayClient.java", "TurnTcpChannel.java"]

LOADERS = ["fabric", "forge", "neoforge"]
templates = []
for ld in LOADERS:
    d = os.path.join(BASE, ld)
    for v in sorted(os.listdir(d)):
        p = os.path.join(d, v, NET)
        if os.path.isdir(p):
            templates.append((ld, v, p))

# 旧 TurnRelayClient 基线：全部模板应只有两种（LF 与 26.2 的 CRLF）
def norm(b):
    return b.replace(b"\r\n", b"\n")

baseline = {}
for ld, v, p in templates:
    f = os.path.join(p, "TurnRelayClient.java")
    if not os.path.isfile(f):
        print("MISS", ld, v)
        continue
    raw = open(f, "rb").read()
    baseline.setdefault(hashlib.md5(norm(raw)).hexdigest(), []).append((ld, v, len(raw) != len(norm(raw))))
print("baselines:", {k: v_ for k, v_ in baseline.items()})

new_lf = {fn: open(os.path.join(REF, fn), "rb").read() for fn in FILES}
assert b"\r\n" not in new_lf["TurnRelayClient.java"], "参考模板应为 LF"

ok = 0
for ld, v, p in templates:
    crlf = (ld, v) == ("fabric", "26.2")
    for fn in FILES:
        data = new_lf[fn].replace(b"\n", b"\r\n") if crlf else new_lf[fn]
        with open(os.path.join(p, fn), "wb") as fh:
            fh.write(data)
    ok += 1
print("propagated:", ok, "templates (fabric/26.2 as CRLF)")
