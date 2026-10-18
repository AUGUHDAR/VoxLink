# -*- coding: utf-8 -*-
"""TURN 修复编译矩阵：串行 compileJava"""
import os, subprocess, sys

BASE = r"d:\桌面\voxlink"
TARGETS = [
    r"fabric\26.2",        # CRLF 变体
    r"fabric\1.20.2_1.20.6",
    r"fabric\1.21.11",
    r"forge\1.20_1.20.1",
    r"forge\26.3",
    r"neoforge\1.20.1",
    r"neoforge\26.3",
]

env = dict(os.environ)
env["GRADLE_USER_HOME"] = r"D:\.gradle"
results = []
for t in TARGETS:
    d = os.path.join(BASE, t)
    print("==== %s ====" % t, flush=True)
    p = subprocess.run(
        [os.path.join(d, "gradlew.bat"), "compileJava", "--console=plain"],
        cwd=d, env=env, capture_output=True, text=True, timeout=900)
    out = (p.stdout or "") + (p.stderr or "")
    ok = "BUILD SUCCESSFUL" in out
    results.append((t, ok, p.returncode))
    print("   -> %s (exit=%d)" % ("OK" if ok else "FAILED", p.returncode), flush=True)

print()
fails = [r for r in results if not r[1]]
for t, ok, rc in results:
    print("%-24s %s exit=%d" % (t, "BUILD SUCCESSFUL" if ok else "NO SUCCESS MARK", rc))
print()
print("MATRIX:", "ALL PASS (7/7)" if not fails else "%d FAILED: %s" % (len(fails), [f[0] for f in fails]))
sys.exit(1 if fails else 0)
