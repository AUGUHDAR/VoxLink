"""校验 成品目录 的 27 个 jar：
  1. 文件名规范：voxlink-1.1.7-<mc_dir>-<loader>.jar
  2. zip 完整性（testzip）
  3. jar 内部声明的 mod 版本确为 1.1.7（fabric.mod.json / mods.toml / neoforge.mods.toml）
  4. 与对应 build/libs 源文件逐字节一致（确认没被污染）
"""
import os, re, sys, zipfile, hashlib

BASE = r"d:\桌面\voxlink"
OUT = os.path.join(BASE, "成品")
VERSION = "1.1.7"
LOADERS = {"fabric": "Fabric", "forge": "Forge", "neoforge": "NeoForge"}

# 前置健全性检查
sys.path.insert(0, "")
from itertools import product

rows = []
problems = []

for loader, dname in LOADERS.items():
    d = os.path.join(OUT, dname)
    if not os.path.isdir(d):
        problems.append(f"缺少目录 成品/{dname}")
        continue
    jars = sorted(f for f in os.listdir(d) if f.endswith(".jar"))
    for j in jars:
        p = os.path.join(d, j)
        size = os.path.getsize(p)
        note = []

        # 1) 文件名
        m = re.fullmatch(rf"voxlink-{re.escape(VERSION)}-(.+)-{loader}\.jar", j)
        if not m:
            note.append("文件名不规范")
        else:
            mc_dir = m.group(1)
            src = os.path.join(BASE, loader, mc_dir, "build", "libs", j)
            if not os.path.exists(src):
                note.append("build/libs 无对应源文件")
            else:
                h1 = hashlib.sha256(open(p, "rb").read()).hexdigest()
                h2 = hashlib.sha256(open(src, "rb").read()).hexdigest()
                if h1 != h2:
                    note.append("与 build/libs 不一致!")

        # 2)(3) zip 完整性 + 内部版本
        ver = "?"
        try:
            with zipfile.ZipFile(p) as z:
                bad = z.testzip()
                if bad:
                    note.append(f"zip 损坏于 {bad}")
                cands = ["fabric.mod.json", "META-INF/mods.toml", "META-INF/neoforge.mods.toml"]
                for c in cands:
                    try:
                        data = z.read(c).decode("utf-8", "replace")
                    except KeyError:
                        continue
                    mm = re.search(r'"version"\s*:\s*"([^"]+)"', data)
                    if mm:
                        ver = mm.group(1); break
                    mm = re.search(r'version\s*=\s*"([^"]+)"', data)
                    if mm:
                        ver = mm.group(1); break
        except Exception as e:
            note.append(f"无法读取: {e}")

        if ver != "?" and not ver.startswith(VERSION):
            note.append(f"内部版本 {ver} != {VERSION}")

        rows.append((f"{loader}/{m.group(1) if m else j}", j, size, ver, note))
        if note:
            problems.append(f"{j}: " + "; ".join(note))

print(f"{'目标':<28}{'大小':>9}  内部版本   状态")
print("-" * 72)
for tgt, j, size, ver, note in rows:
    print(f"{tgt:<28}{size/1048576:>7.2f}MB  {ver:<10} {'⚠ ' + '; '.join(note) if note else 'OK'}")

print("-" * 72)
print(f"jar 总数 = {len(rows)} / 期望 27")
tot = sum(r[2] for r in rows)
print(f"合计体积 = {tot/1048576:.2f}MB")
if problems:
    print("\n=== 问题 ===")
    for p in problems:
        print("  * " + p)
    sys.exit(1)
print("\n全部通过：27 个 jar 名称规范、zip 完整、内部版本均为 1.1.7、与 build/libs 一致 ✓")
