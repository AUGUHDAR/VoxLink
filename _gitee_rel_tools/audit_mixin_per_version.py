"""在 Gradle 缓存里按路径定位各 MC/Forge 版本实际解析到的 Mixin 实现版本。

缓存路径里通常带 MC 版本或 Forge 版本，据此反推"哪个目标用哪个 Mixin"。
"""
import os
import re

ROOTS = [
    r"D:\.gradle\caches\forge_gradle",
    r"D:\.gradle\caches\minecraftforge",
    r"D:\.gradle\caches\neoformruntime",
    r"D:\.gradle\caches\modules-2\files-2.1\net.minecraftforge",
    r"D:\.gradle\caches\modules-2\files-2.1\net.neoforged",
]

MIXIN_RE = re.compile(r"^(mixin|sponge-mixin)-[\d.].*\.jar$", re.I)
rows = []

for root in ROOTS:
    if not os.path.isdir(root):
        continue
    for dirpath, dirs, files in os.walk(root):
        if dirpath.count(os.sep) - root.count(os.sep) > 8:
            dirs[:] = []
            continue
        for f in files:
            if MIXIN_RE.match(f) and "sources" not in f and "javadoc" not in f:
                rel = os.path.relpath(os.path.join(dirpath, f), root)
                rows.append((rel, f))

# 去重
seen = set()
uniq = []
for rel, f in rows:
    key = (rel, f)
    if key in seen:
        continue
    seen.add(key)
    uniq.append((rel, f))

uniq.sort()
print(f"缓存内 Mixin 实现 {len(uniq)} 处\n")

# 按 mixin 版本分组，聚合它出现过的路径（路径里含 MC/Forge 版本线索）
byver = {}
for rel, f in uniq:
    m = re.match(r"^(mixin|sponge-mixin)-([\d.]+)", f, re.I)
    ver = (m.group(1) + "-" + m.group(2)) if m else f
    byver.setdefault(ver, []).append(rel)

for ver in sorted(byver):
    paths = byver[ver]
    print(f"### {ver}  （{len(paths)} 处）")
    # 抽取路径里的版本号片段作为线索
    hints = set()
    for p in paths:
        for h in re.findall(r"\b(\d+\.\d+(?:\.\d+)?(?:-\d+\.\d+\.\d+)?)\b", p):
            hints.add(h)
    print("   版本线索:", ", ".join(sorted(hints)[:18]) or "(无)")
    print("   示例路径:", paths[0][:150])
    print()
