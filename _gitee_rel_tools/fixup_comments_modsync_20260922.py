# -*- coding: utf-8 -*-
"""注释合规修正：两条超 20 字的 // 注释缩短；3 代表改后簇内重拷"""
import glob, hashlib, os, sys

BASE = r"d:\桌面\voxlink"
GOLD = os.path.join(BASE, "fabric", "26.3", "src", "main", "java", "icu", "wuhui", "voxlink", "modsync", "ModSyncGuestService.java")

FIXES = [
    ("// 每次门控重置在途提示；入口留一行，真机日志可据此判定 gate 是否被调用",
     "// 重置在途提示；gate 入口留痕供真机定谳"),
    ("// 直通原因一律留痕；房主不支持/拉取失败再挂面板提示，由加入屏在连接启动后播报",
     "// 直通原因留痕；两类挂面板提示"),
]

def load(p):
    raw = open(p, "rb").read().decode("utf-8")
    eol = "\r\n" if "\r\n" in raw else "\n"
    return raw.replace("\r\n", "\n"), eol

def save(p, t, eol):
    open(p, "wb").write(t.replace("\n", eol).encode("utf-8"))

msg_files = sorted(glob.glob(os.path.join(BASE, "*", "*", "src", "main", "java", "icu", "wuhui", "voxlink", "modsync", "ModSyncGuestService.java")))
clusters = {}
for f in msg_files:
    clusters.setdefault(hashlib.md5(open(f, "rb").read()).hexdigest(), []).append(f)

errors = []
for h, lst in clusters.items():
    rep = GOLD if GOLD in lst else lst[0]
    t, eol = load(rep)
    ok = True
    for old, new in FIXES:
        n = t.count(old)
        if n == 0 and t.count(new) == 1:
            continue  # 已修过
        if n != 1:
            errors.append("%s: fix anchor count=%d" % (os.path.relpath(rep, BASE), n))
            ok = False
    if not ok:
        continue
    for old, new in FIXES:
        t = t.replace(old, new, 1)
    save(rep, t, eol)
    data = open(rep, "rb").read()
    for f in lst:
        if f != rep:
            open(f, "wb").write(data)

# 终检：全 28 份无长注释残留、短注释恰 1 处
bad = 0
for f in msg_files:
    t = open(f, "rb").read().decode("utf-8").replace("\r\n", "\n")
    if FIXES[0][0] in t or FIXES[1][0] in t:
        print("RESIDUE:", os.path.relpath(f, BASE))
        bad += 1
    if t.count(FIXES[0][1]) != 1 or t.count(FIXES[1][1]) != 1:
        print("SHORT MISS:", os.path.relpath(f, BASE))
        bad += 1

print("errors:", len(errors), "bad:", bad)
for e in errors:
    print("  ERR", e)
sys.exit(1 if (errors or bad) else 0)
