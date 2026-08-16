import subprocess, os, sys, shutil

BASE = r"d:\桌面\voxlink"
OUT = os.path.join(BASE, "成品")
LOADERS = ["fabric", "forge", "neoforge"]

JDK21 = r"C:\Program Files\Java\jdk-21"
JDK25 = r"C:\Program Files\Java\jdk-25.0.2"

def is_26x(v):
    return v.startswith("26.")

def build_one(loader, ver):
    d = os.path.join(BASE, loader, ver)
    gradlew = os.path.join(d, "gradlew.bat")
    if not os.path.exists(gradlew):
        return ("SKIP", "no gradlew.bat")
    jdk = JDK25 if is_26x(ver) else JDK21
    env = os.environ.copy()
    env["JAVA_HOME"] = jdk
    env["PATH"] = os.path.join(jdk, "bin") + os.pathsep + env.get("PATH", "")
    print(f"[{loader}/{ver}] building (JAVA_HOME={os.path.basename(jdk)})...", flush=True)
    try:
        r = subprocess.run(
            [gradlew, "build", "-x", "test"],
            cwd=d, env=env,
            capture_output=True, text=True, encoding="utf-8", errors="replace",
            timeout=420
        )
        if r.returncode == 0 and "BUILD SUCCESSFUL" in r.stdout:
            return ("OK", "")
        err_lines = []
        for line in (r.stdout + r.stderr).splitlines():
            if "error:" in line.lower() or "FAIL" in line or "BUILD" in line:
                err_lines.append(line.strip()[:200])
        return ("FAIL", "\n    ".join(err_lines[-5:]) if err_lines else r.stdout[-500:])
    except subprocess.TimeoutExpired:
        return ("TIMEOUT", "")
    except Exception as e:
        return ("ERROR", str(e)[:200])

def copy_jar(loader, ver):
    bd = os.path.join(BASE, loader, ver, "build", "libs")
    if not os.path.isdir(bd):
        return None
    jars = [f for f in os.listdir(bd) if f.endswith(".jar") and "-sources" not in f and "-dev" not in f]
    if not jars:
        return None
    jars.sort(key=lambda f: os.path.getmtime(os.path.join(bd, f)), reverse=True)
    src = os.path.join(bd, jars[0])
    dst_dir = os.path.join(OUT, loader.capitalize())
    os.makedirs(dst_dir, exist_ok=True)
    dst = os.path.join(dst_dir, jars[0])
    shutil.copy2(src, dst)
    return jars[0]

results = []
for loader in LOADERS:
    for ver in sorted(os.listdir(os.path.join(BASE, loader))):
        status, detail = build_one(loader, ver)
        tag = "OK" if status == "OK" else f"FAIL({status})"
        copied = ""
        if status == "OK":
            copied = copy_jar(loader, ver) or "NO_JAR"
        print(f"  [{loader}/{ver}] {tag} -> {copied}" + (f" -- {detail}" if detail and status != "OK" else ""), flush=True)
        results.append((f"{loader}/{ver}", status, copied))

print("\n=== BUILD RESULTS ===")
ok = 0
for name, status, copied in results:
    if status == "OK":
        ok += 1
    print(f"  {name}: {status}" + (f" -> {copied}" if copied else ""))
print(f"\n{ok}/{len(results)} SUCCESS")
