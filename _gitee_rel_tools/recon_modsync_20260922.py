import glob, hashlib, os, re

base = r"d:\桌面\voxlink"
# 1) ModSyncGuestService md5 分簇
files = sorted(glob.glob(os.path.join(base, "*", "*", "src", "main", "java", "icu", "wuhui", "voxlink", "modsync", "ModSyncGuestService.java")))
clusters = {}
for f in files:
    with open(f, "rb") as fh:
        h = hashlib.md5(fh.read()).hexdigest()
    clusters.setdefault(h, []).append(os.path.relpath(f, base))
print("ModSyncGuestService clusters:", len(clusters))
for h, lst in clusters.items():
    print(" ", h[:10], len(lst), lst[:3], "..." if len(lst) > 3 else "")

# 2) AttemptingJoinScreen 两个锚点唯一性
files2 = sorted(glob.glob(os.path.join(base, "*", "*", "src", "main", "java", "icu", "wuhui", "voxlink", "ui", "AttemptingJoinScreen.java")))
print()
print("AttemptingJoinScreen files:", len(files2))
bad1, bad2, dsp = [], [], 0
for f in files2:
    with open(f, encoding="utf-8") as fh:
        t = fh.read()
    n1 = t.count("this.modSyncChecking = true;")
    n2 = t.count("this.startConnectionMonitor();")
    if n1 != 1:
        bad1.append((os.path.relpath(f, base), n1))
    if n2 != 1:
        bad2.append((os.path.relpath(f, base), n2))
    # 双倍行距检测: 空行占比
    lines = t.split("\n")
    blanks = sum(1 for l in lines if not l.strip())
    if blanks > len(lines) * 0.35:
        dsp += 1
print("anchor 'this.modSyncChecking = true;' not-unique:", bad1)
print("anchor 'this.startConnectionMonitor();' not-unique:", bad2)
print("double-spaced AttemptingJoinScreen files:", dsp)

# 3) lang 文件是否逐字节相同
langs = sorted(glob.glob(os.path.join(base, "*", "*", "src", "main", "resources", "assets", "voxlink", "lang", "zh_cn.json")))
hset = set()
for f in langs:
    with open(f, "rb") as fh:
        hset.add(hashlib.md5(fh.read()).hexdigest())
print()
print("zh_cn.json distinct md5:", len(hset), "across", len(langs), "templates")
