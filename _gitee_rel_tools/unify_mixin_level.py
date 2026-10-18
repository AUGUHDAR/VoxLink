"""把所有目标的 voxlink.mixins.json 的 compatibilityLevel 统一为 JAVA_17。

背景（2026-09-21）：Forge 26.x 崩在
  MixinInitialisationError: Mixin config voxlink.mixins.json specifies
  compatibility level JAVA_25 which is not recognised
原因是各加载器自带的 Mixin 实现支持的等级上限不同：

    mixin-0.8.5（老 Forge）          最高 JAVA_18
    mixin-0.8.7（Forge 26.3）        最高 JAVA_21
    sponge-mixin 0.13~0.15.5（Fabric 旧） 最高 JAVA_22
    sponge-mixin 0.16.5+/0.17（Fabric 新）最高 JAVA_25

统一取 JAVA_17：对以上所有实现都合法，且不低于 MC 1.20+ 的运行时环境等级
（若声明高于环境等级，Mixin 会反向报错）。Mixin 不校验字节码版本
（实测 JAVA_21 + Java25 字节码可正常启动），因此高版本目标也安全。

脚本只改这一个键，其余内容原样保留，并逐一打印前后对比。
"""
import json
import os
import re
import sys

BASE = r"D:\桌面\voxlink"
LOADERS = ["fabric", "forge", "neoforge"]
TARGET = "JAVA_17"

changed = []
for loader in LOADERS:
    ldir = os.path.join(BASE, loader)
    if not os.path.isdir(ldir):
        continue
    for grp in sorted(os.listdir(ldir)):
        f = os.path.join(ldir, grp, "src", "main", "resources", "voxlink.mixins.json")
        if not os.path.isfile(f):
            continue
        raw = open(f, "r", encoding="utf-8").read()
        bom = raw.startswith("\ufeff")
        text = raw.lstrip("\ufeff")
        try:
            j = json.loads(text)
        except Exception as e:
            print(f"  !! 解析失败 {loader}/{grp}: {e}")
            continue
        old = j.get("compatibilityLevel")
        if old == TARGET:
            continue
        # 用正则替换，保留原文件的缩进与键顺序，避免 json.dumps 重排
        new_text, n = re.subn(
            r'("compatibilityLevel"\s*:\s*")([^"]*)(")',
            lambda m: m.group(1) + TARGET + m.group(3),
            text, count=1,
        )
        if n == 0:
            print(f"  !! {loader}/{grp} 里找不到 compatibilityLevel 键，跳过")
            continue
        open(f, "w", encoding="utf-8").write(("\ufeff" if bom else "") + new_text)
        changed.append((f"{loader}/{grp}", old, TARGET))

print(f"共修改 {len(changed)} 个文件\n")
for name, old, new in changed:
    print(f"  {name:<24} {old} -> {new}")

print("\n=== 复核：所有目标当前取值 ===")
bad = 0
for loader in LOADERS:
    ldir = os.path.join(BASE, loader)
    for grp in sorted(os.listdir(ldir)):
        f = os.path.join(ldir, grp, "src", "main", "resources", "voxlink.mixins.json")
        if not os.path.isfile(f):
            continue
        j = json.loads(open(f, "r", encoding="utf-8").read().lstrip("\ufeff"))
        v = j.get("compatibilityLevel")
        flag = "" if v == TARGET else "  <== 未统一"
        if v != TARGET:
            bad += 1
        print(f"  {loader}/{grp:<16} {v}{flag}")
print(f"\n不一致的 = {bad}")
sys.exit(1 if bad else 0)
