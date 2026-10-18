"""1.1.7 全量 clean 构建 —— 与 build_all_versions.py 同逻辑，但强制 clean。

为什么必须 clean：
    build_all_versions.py 用 `gradlew build`，Gradle 对未变更输入判定 UP-TO-DATE 时
    不会重新打包 jar，导致 copy_jar 的"mtime >= 本次构建开始时间"校验落空（输出 NO_JAR），
    最终成品目录一个 jar 都不会有。
    clean build 强制重新编译打包，确保产物确凿来自当前源码。

产物：成品/{Fabric,Forge,Neoforge}/voxlink-1.1.7-<mc_dir>-<loader>.jar （共 27 个）
"""
import subprocess, os, sys, shutil, time

BASE = r"d:\桌面\voxlink"
OUT = os.path.join(BASE, "成品")
LOADERS = ["fabric", "forge", "neoforge"]


def discover_jdk(major):
    roots = [os.environ.get("JAVA_HOME"), r"C:\Program Files\Java",
             r"C:\Program Files\Eclipse Adoptium"]
    best = None
    for root in roots:
        if not root or not os.path.isdir(root):
            continue
        name = os.path.basename(root.rstrip("\\/")).lower()
        if f"jdk-{major}" in name or name == f"jdk{major}":
            return root
        try:
            entries = sorted(os.listdir(root))
        except OSError:
            continue
        for entry in entries:
            p = os.path.join(root, entry)
            if os.path.isdir(p) and f"jdk-{major}" in entry.lower() and os.path.isdir(os.path.join(p, "bin")):
                if best is None or entry > best[0]:
                    best = (entry, p)
    return best[1] if best else None


JDK21 = discover_jdk(21) or r"C:\Program Files\Java\jdk-21"
JDK25 = discover_jdk(25) or r"C:\Program Files\Java\jdk-25.0.2"
print(f"JDK21 = {JDK21}", flush=True)
print(f"JDK25 = {JDK25}", flush=True)


def is_26x(v):
    return v.startswith("26.")


def build_one(loader, ver):
    d = os.path.join(BASE, loader, ver)
    gradlew = os.path.join(d, "gradlew.bat")
    if not os.path.exists(gradlew):
        return ("SKIP", "no gradlew.bat", None)
    jdk = JDK25 if is_26x(ver) else JDK21
    env = os.environ.copy()
    env["JAVA_HOME"] = jdk
    env["PATH"] = os.path.join(jdk, "bin") + os.pathsep + env.get("PATH", "")
    print(f"[{loader}/{ver}] clean build (JAVA_HOME={os.path.basename(jdk)})...", flush=True)
    t0 = time.time()
    try:
        r = subprocess.run(
            [gradlew, "clean", "build", "-x", "test", "--console=plain"],
            cwd=d, env=env,
            capture_output=True, text=True, encoding="utf-8", errors="replace",
            timeout=1800
        )
        if r.returncode == 0 and "BUILD SUCCESSFUL" in r.stdout:
            return ("OK", "", t0)
        err_lines = []
        for line in (r.stdout + r.stderr).splitlines():
            if "error:" in line.lower() or "FAIL" in line or "BUILD" in line:
                err_lines.append(line.strip()[:200])
        return ("FAIL", "\n    ".join(err_lines[-6:]) if err_lines else r.stdout[-500:], t0)
    except subprocess.TimeoutExpired:
        return ("TIMEOUT", "", t0)
    except Exception as e:
        return ("ERROR", str(e)[:200], t0)


def copy_jar(loader, ver, t0):
    bd = os.path.join(BASE, loader, ver, "build", "libs")
    if not os.path.isdir(bd):
        return None
    expected = None
    for f in os.listdir(bd):
        if f.endswith(".jar") and "-sources" not in f and "-dev" not in f and f.startswith(f"voxlink-{VERSION}-"):
            expected = f
            break
    if not expected:
        return None
    src = os.path.join(bd, expected)
    dst_dir = os.path.join(OUT, loader.capitalize())
    os.makedirs(dst_dir, exist_ok=True)
    dst = os.path.join(dst_dir, expected)
    shutil.copy2(src, dst)
    fresh = os.path.getmtime(src) >= t0
    return (expected, fresh)


VERSION = "1.1.7"
results = []
for loader in LOADERS:
    for ver in sorted(os.listdir(os.path.join(BASE, loader))):
        if not os.path.isdir(os.path.join(BASE, loader, ver)):
            continue
        status, detail, t0 = build_one(loader, ver)
        tag = "OK" if status == "OK" else f"FAIL({status})" if status != "SKIP" else "SKIP"
        copied = ""
        if status == "OK":
            got = copy_jar(loader, ver, t0)
            if got is None:
                copied = "NO_JAR"
            else:
                copied = got[0] + ("" if got[1] else "  [警告: jar 非本次生成]")
        print(f"  [{loader}/{ver}] {tag} -> {copied}" + (f" -- {detail}" if detail and status != "OK" else ""), flush=True)
        results.append((f"{loader}/{ver}", status, copied))

print("\n=== BUILD RESULTS ===")
ok = 0
failed = 0
for name, status, copied in results:
    if status == "OK":
        ok += 1
    elif status != "SKIP":
        failed += 1
    print(f"  {name}: {status}" + (f" -> {copied}" if copied else ""))
print(f"\n{ok} OK, {failed} FAILED, {len(results) - ok - failed} SKIPPED")
sys.exit(1 if failed else 0)
