# -*- coding: utf-8 -*-
"""修正用户裁决边界：26.2+ → 26.1+（ZCode 记忆/索引 + TRAE 项目记忆）"""
import io, sys

FILES = [
    r"C:\Users\19430\.zcode\cli\memories\projects\voxlink-9a9f46dba867d0dd\memory\voxlink-user-rulings-jvm-and-nohotfix-20260922.md",
    r"C:\Users\19430\.zcode\cli\memories\projects\voxlink-9a9f46dba867d0dd\memory\MEMORY.md",
    r"C:\Users\19430\.trae-cn\memory\projects\-d----voxlink--p2-a7e51d6763a02d303c09\project_memory.md",
]
bad = 0
for p in FILES:
    with io.open(p, "r", encoding="utf-8") as f:
        t = f.read()
    n = t.count("26.2+")
    if n == 0:
        print("no 26.2+ in", p.split("\\")[-1], "(skip)")
        continue
    t2 = t.replace("26.2+", "26.1+")
    with io.open(p, "w", encoding="utf-8", newline="") as f:
        f.write(t2)
    print("%s: replaced %d" % (p.split("\\")[-1], n))
    if "26.2+" in t2:
        bad += 1

print("RESULT:", "OK" if bad == 0 else "RESIDUE %d" % bad)
sys.exit(1 if bad else 0)
