import subprocess, os, sys, shutil, glob

BASE = r"d:\桌面\voxlink\fabric"
OUT = r"d:\桌面\voxlink\成品"
VERSIONS = ["1.20","1.20.1","1.20.2","1.20.3","1.20.4","1.20.5","1.20.6",
            "1.21.0","1.21.1","1.21.2","1.21.3","1.21.4","1.21.5","1.21.6",
            "1.21.7","1.21.8","1.21.9","1.21.10","1.21.11",
            "26.1","26.1.1","26.1.2","26.2"]

#debounce TRAE的PATH里java是JRE无compiler 必须显式设JAVA_HOME
JDK21 = r"C:\Program Files\Java\jdk-21"
JDK25 = r"C:\Program Files\Java\jdk-25.0.2"
VERS_26X = {"26.1","26.1.1","26.1.2","26.2"}

os.makedirs(OUT, exist_ok=True)

results = []
for ver in VERSIONS:
    d = os.path.join(BASE, ver)
    gradlew = os.path.join(d, "gradlew.bat")
    if not os.path.exists(gradlew):
        results.append((ver, "SKIP", "no gradlew.bat"))
        continue
    jdk = JDK25 if ver in VERS_26X else JDK21
    env = os.environ.copy()
    env["JAVA_HOME"] = jdk
    env["PATH"] = os.path.join(jdk, "bin") + os.pathsep + env.get("PATH","")
    print(f"[{ver}] building (JAVA_HOME={jdk})...", flush=True)
    try:
        r = subprocess.run(
            [gradlew, "build", "-x", "test"],
            cwd=d, env=env,
            capture_output=True, text=True, encoding="utf-8", errors="replace",
            timeout=300
        )
        if r.returncode == 0 and "BUILD SUCCESSFUL" in r.stdout:
            results.append((ver, "OK", ""))
        else:
            err_lines = []
            for line in (r.stdout + r.stderr).splitlines():
                if "error:" in line.lower() or "FAIL" in line or "BUILD" in line:
                    err_lines.append(line.strip()[:200])
            results.append((ver, "FAIL", "\n    ".join(err_lines[-5:]) if err_lines else r.stdout[-500:]))
    except subprocess.TimeoutExpired:
        results.append((ver, "TIMEOUT", ""))
    except Exception as e:
        results.append((ver, "ERROR", str(e)[:200]))

print("\n=== BUILD RESULTS ===")
ok_count = 0
for ver, status, detail in results:
    tag = "OK" if status == "OK" else f"FAIL({status})"
    print(f"  [{ver}] {tag}" + (f" -- {detail}" if detail and status != "OK" else ""))
    if status == "OK":
        ok_count += 1
print(f"\n{ok_count}/{len(VERSIONS)} SUCCESS")

if ok_count == len(VERSIONS):
    print("\n=== COPYING JARS ===")
    for ver in VERSIONS:
        bd = os.path.join(BASE, ver, "build", "libs")
        if not os.path.isdir(bd):
            print(f"  [{ver}] no build/libs dir")
            continue
        jars = [f for f in os.listdir(bd) if f.endswith(".jar") and "-sources" not in f and "-dev" not in f]
        if not jars:
            print(f"  [{ver}] no jar found")
            continue
        # pick latest by mtime
        jars.sort(key=lambda f: os.path.getmtime(os.path.join(bd, f)), reverse=True)
        src = os.path.join(bd, jars[0])
        dst = os.path.join(OUT, jars[0])
        shutil.copy2(src, dst)
        print(f"  [{ver}] {jars[0]}")
    print("\nALL COPIED")
