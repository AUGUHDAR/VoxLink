# -*- coding: utf-8 -*-
"""2026-08-30 版本号升级 1.1.2 -> 1.1.3（25 模板 gradle.properties）"""
import glob, io, os, sys

ROOT = os.path.dirname(os.path.abspath(__file__))
files = sorted(
    glob.glob(os.path.join(ROOT, "fabric", "*", "gradle.properties"))
    + glob.glob(os.path.join(ROOT, "forge", "*", "gradle.properties"))
    + glob.glob(os.path.join(ROOT, "neoforge", "*", "gradle.properties"))
)
assert len(files) == 25, f"expect 25 templates, got {len(files)}"

changed = 0
for fp in files:
    with io.open(fp, "r", encoding="utf-8", newline="") as f:
        text = f.read()
    needle = "mod_version=1.1.2-"
    repl = "mod_version=1.1.3-"
    cnt = text.count(needle)
    assert cnt == 1, f"{fp}: anchor count={cnt}"
    with io.open(fp, "w", encoding="utf-8", newline="") as f:
        f.write(text.replace(needle, repl))
    changed += 1
    print(f"OK {os.path.relpath(fp, ROOT)}")
print(f"done: {changed}/25")
