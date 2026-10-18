# -*- coding: utf-8 -*-
"""1.1.8 发布前两步：修正 forge/26.x mixin 兼容级别 + 28 个项目版本号 1.1.7 -> 1.1.8"""
import os
import re

BASE = r"D:\桌面\voxlink"
LOADERS = ["fabric", "forge", "neoforge"]

# --- 1. mixins.json: forge/26.x 的 JAVA_25 -> JAVA_21 ---
MIXIN_TARGETS = [
    ("forge", "26.1_26.1.2"),
    ("forge", "26.2"),
    ("forge", "26.3"),
]

print("=== 1. mixins.json 修正 ===")
for loader, ver in MIXIN_TARGETS:
    p = os.path.join(BASE, loader, ver, "src", "main", "resources", "voxlink.mixins.json")
    raw = open(p, "rb").read()
    txt = raw.decode("utf-8-sig")
    old = re.search(r'"compatibilityLevel"\s*:\s*"([^"]+)"', txt)
    before = old.group(1) if old else "NONE"
    if before == "JAVA_21":
        print(f"  {loader}/{ver}: 已是 JAVA_21，跳过")
        continue
    new_txt = re.sub(r'("compatibilityLevel"\s*:\s*)"[^"]+"', r'\1"JAVA_21"', txt)
    # 保留 BOM（若原文件有）
    out = (b"\xef\xbb\xbf" if raw.startswith(b"\xef\xbb\xbf") else b"") + new_txt.encode("utf-8")
    open(p, "wb").write(out)
    chk = re.search(r'"compatibilityLevel"\s*:\s*"([^"]+)"', open(p, encoding="utf-8-sig").read())
    print(f"  {loader}/{ver}: {before} -> {chk.group(1)}")

# --- 2. gradle.properties: mod_version 1.1.7-XXX -> 1.1.8-XXX ---
print("\n=== 2. mod_version 升级 ===")
n_ok = 0
for loader in LOADERS:
    root = os.path.join(BASE, loader)
    for ver in sorted(os.listdir(root)):
        d = os.path.join(root, ver)
        gp = os.path.join(d, "gradle.properties")
        if not os.path.isfile(gp):
            continue
        raw = open(gp, "rb").read()
        txt = raw.decode("utf-8-sig")
        m = re.search(r"^mod_version=(.+)$", txt, re.M)
        if not m:
            print(f"  {loader}/{ver}: 无 mod_version，跳过")
            continue
        cur = m.group(1).strip()
        if cur.startswith("1.1.8-"):
            print(f"  {loader}/{ver}: 已是 {cur}，跳过")
            n_ok += 1
            continue
        new_cur = "1.1.8-" + cur.split("-", 1)[1] if "-" in cur else "1.1.8"
        new_txt = re.sub(r"^mod_version=.+$", f"mod_version={new_cur}", txt, count=1, flags=re.M)
        out = (b"\xef\xbb\xbf" if raw.startswith(b"\xef\xbb\xbf") else b"") + new_txt.encode("utf-8")
        open(gp, "wb").write(out)
        chk = re.search(r"^mod_version=(.+)$", open(gp, encoding="utf-8-sig").read(), re.M).group(1).strip()
        print(f"  {loader}/{ver}: {cur} -> {chk}")
        n_ok += 1

print(f"\n完成：{n_ok} 个项目版本号就绪")
