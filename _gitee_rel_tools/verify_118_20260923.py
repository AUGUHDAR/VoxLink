# -*- coding: utf-8 -*-
"""1.1.8 产物全量校验：数量 / 命名 / 内嵌版本 / mixin 兼容级别 / zip 完整性"""
import os
import re
import json
import zipfile

BASE = r"D:\桌面\voxlink"
OUT = os.path.join(BASE, "成品")
VERSION = "1.1.8"

# 期望目标：loader -> [目录名]
EXPECT = {}
for loader in ("fabric", "forge", "neoforge"):
    EXPECT[loader] = sorted(
        d for d in os.listdir(os.path.join(BASE, loader))
        if os.path.isdir(os.path.join(BASE, loader, d))
    )

# forge/26.x 应为 JAVA_21（本次修复点），其余应为 JAVA_17
def expected_level(loader, ver):
    if loader == "forge" and ver.startswith("26."):
        return "JAVA_21"
    return "JAVA_17"

rows, problems = [], []
total = 0
for loader, vers in EXPECT.items():
    for ver in vers:
        total += 1
        name = f"voxlink-{VERSION}-{ver}-{loader}.jar"
        p = os.path.join(OUT, loader.capitalize(), name)
        if not os.path.isfile(p):
            problems.append(f"[缺文件] {loader}/{ver} -> {name}")
            continue
        size = os.path.getsize(p)
        try:
            with zipfile.ZipFile(p) as z:
                bad = z.testzip()
                if bad:
                    problems.append(f"[zip损坏] {name} @ {bad}")
                # 内嵌 mod 版本
                mvf = [n for n in z.namelist() if n.endswith("voxlink.mixins.json")]
                lvl = "?"
                if mvf:
                    t = z.read(mvf[0]).decode("utf-8-sig")
                    m = re.search(r'"compatibilityLevel"\s*:\s*"([^"]+)"', t)
                    lvl = m.group(1) if m else "NONE"
                emb = "?"
                for cand, key in (("fabric.mod.json", "version"), ("mods.toml", "version")):
                    mm = [n for n in z.namelist() if n.endswith(cand)]
                    if not mm:
                        continue
                    raw = z.read(mm[0]).decode("utf-8-sig")
                    if key == "version" and cand == "fabric.mod.json":
                        emb = json.loads(raw).get("version", "?")
                    else:
                        for line in raw.splitlines():
                            if re.match(r"\s*version\s*=", line):
                                emb = line.split("=", 1)[1].strip().strip('"')
                                break
                    break
                want_lvl = expected_level(loader, ver)
                if lvl != want_lvl:
                    problems.append(f"[mixin级别错] {name}: {lvl} != 期望 {want_lvl}")
                if emb != f"{VERSION}-{ver}":
                    problems.append(f"[内嵌版本错] {name}: '{emb}' != 期望 {VERSION}-{ver}")
                rows.append((loader, ver, lvl, emb, size))
        except Exception as e:
            problems.append(f"[异常] {name}: {e}")

print(f"=== 1.1.8 产物校验（期望 {total} 个）===")
print(f"{'loader':<10}{'mc版本':<18}{'mixin':<10}{'内嵌版本':<26}{'大小KB'}")
for loader, ver, lvl, emb, size in rows:
    print(f"{loader:<10}{ver:<18}{lvl:<10}{emb:<26}{size/1024:.0f}")

print(f"\n实际产物数 = {len(rows)} / {total}")
if problems:
    print(f"\n!!! 发现 {len(problems)} 个问题：")
    for x in problems:
        print("   ", x)
else:
    print("\n✅ 全部通过：数量、命名、内嵌版本、mixin 级别、zip 完整性均无异常")
