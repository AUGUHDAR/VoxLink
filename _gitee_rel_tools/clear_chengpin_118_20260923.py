# -*- coding: utf-8 -*-
"""清空 成品/ 下所有 jar 前先备份到 _old_builds_archive/，Server/App 非 jar 产物不动。"""
import os
import shutil
import datetime

BASE = r"D:\桌面\voxlink"
OUT = os.path.join(BASE, "成品")
ARCH = os.path.join(BASE, "_old_builds_archive", "chengpin_jars_20260923_118")

os.makedirs(ARCH, exist_ok=True)

moved, skipped = [], []
for sub in os.listdir(OUT):
    d = os.path.join(OUT, sub)
    if not os.path.isdir(d):
        continue
    for f in os.listdir(d):
        src = os.path.join(d, f)
        if not os.path.isfile(src) or not f.endswith(".jar"):
            skipped.append(f"{sub}/{f}")
            continue
        tgt_dir = os.path.join(ARCH, sub)
        os.makedirs(tgt_dir, exist_ok=True)
        shutil.copy2(src, os.path.join(tgt_dir, f))   # 先备份
        os.remove(src)                                 # 备份成功后才删
        moved.append(f"{sub}/{f}")

print(f"备份目录: {ARCH}")
print(f"已备份并清空 {len(moved)} 个 jar:")
for m in moved:
    print("   -", m)
print(f"\n保留未动 {len(skipped)} 个非 jar 文件:")
for s in skipped:
    print("   *", s)

# 复核：成品 下已无 jar
left = []
for sub in os.listdir(OUT):
    d = os.path.join(OUT, sub)
    if os.path.isdir(d):
        left += [f for f in os.listdir(d) if f.endswith(".jar")]
print("\n复核：成品 目录残留 jar 数 =", len(left))
