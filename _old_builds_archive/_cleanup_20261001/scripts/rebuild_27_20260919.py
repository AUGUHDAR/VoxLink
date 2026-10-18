# -*- coding: utf-8 -*-
# 2026-09-19 日志修复轮：全量重出 27 罐（删除旧 jar 强制重建，规避 up-to-date）
import subprocess, os, time, shutil

BASE = r"D:\桌面\voxlink"
JDK21 = r"C:\Program Files\Java\jdk-21"
JDK25 = r"C:\Program Files\Java\jdk-25.0.2"

ok_n = 0
fails = []
for loader in ("fabric", "forge", "neoforge"):
    for vdir in sorted(os.listdir(os.path.join(BASE, loader))):
        jdk = JDK25 if vdir.startswith("26.") else JDK21
        d = os.path.join(BASE, loader, vdir)
        env = os.environ.copy()
        env["JAVA_HOME"] = jdk
        env["PATH"] = os.path.join(jdk, "bin") + os.pathsep + env.get("PATH", "")
        bd = os.path.join(d, "build", "libs")
        if os.path.isdir(bd):
            for f in os.listdir(bd):
                if f.endswith(".jar") and "-sources" not in f and "-dev" not in f:
                    os.remove(os.path.join(bd, f))
        r = subprocess.run([os.path.join(d, "gradlew.bat"), "build", "-x", "test", "--console=plain"],
                           cwd=d, env=env, capture_output=True, text=True, encoding="utf-8",
                           errors="replace", timeout=600)
        ok = r.returncode == 0 and "BUILD SUCCESSFUL" in r.stdout
        jar = None
        if ok and os.path.isdir(bd):
            jars = [f for f in os.listdir(bd) if f.endswith(".jar") and "-sources" not in f and "-dev" not in f]
            if jars:
                jars.sort(key=lambda f: os.path.getmtime(os.path.join(bd, f)), reverse=True)
                jar = jars[0]
                shutil.copy2(os.path.join(bd, jar), os.path.join(BASE, "成品", loader.capitalize(), jar))
        ok_n += ok
        if not ok:
            fails.append((loader, vdir))
        print(loader, vdir, "OK" if ok else "FAIL", "->", jar, flush=True)

print("done:", ok_n, "/ 27")
if fails:
    print("FAILS:", fails)
    raise SystemExit(1)
