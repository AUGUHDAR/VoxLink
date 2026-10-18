# -*- coding: utf-8 -*-
"""ModSync 门控修复编译矩阵：串行 compileJava，判定看 BUILD SUCCESSFUL 不只看退出码"""
import os, subprocess, sys

BASE = r"d:\桌面\voxlink"
TARGETS = [
    r"fabric\1.20.2_1.20.6",   # MSG 簇A 代表(sendSystemMessage)
    r"fabric\1.21.11",          # MSG 簇B 代表(displayClientMessage)
    r"fabric\26.2",             # MSG 簇C 成员
    r"forge\1.20_1.20.1",       # 簇A 成员
    r"neoforge\1.20.1",         # 簇A 成员
    r"fabric\1.20_1.20.1",      # 簇A 成员
    r"fabric\1.21_1.21.5",      # 簇B 成员
    r"forge\26.3",              # 簇C 成员
    r"neoforge\26.3",           # 簇C 成员
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
print("MATRIX:", "ALL PASS (9/9)" if not fails else "%d FAILED: %s" % (len(fails), [f[0] for f in fails]))
sys.exit(1 if fails else 0)
