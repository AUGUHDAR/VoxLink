import subprocess, os, sys, shutil, time

BASE_FABRIC = r"d:\桌面\voxlink\fabric"
BASE_FORGE = r"d:\桌面\voxlink\forge"
BASE_NEOFORGE = r"d:\桌面\voxlink\neoforge"
OUT_FABRIC = r"d:\桌面\voxlink\成品\Fabric"
OUT_FORGE = r"d:\桌面\voxlink\成品\Forge"
OUT_NEOFORGE = r"d:\桌面\voxlink\成品\NeoForge"

JARS_26X = {"26.1_26.1.2", "26.2"}

FABRIC_JARS = ["1.20_1.20.1", "1.20.2_1.20.6", "1.21_1.21.5", "1.21.6_1.21.8",
               "1.21.9_1.21.10", "1.21.11", "26.1_26.1.2", "26.2"]
FORGE_JARS = ["1.20_1.20.1", "1.20.2_1.20.4", "1.20.6", "1.21_1.21.5", "1.21.6_1.21.8",
              "1.21.9_1.21.10", "1.21.11", "26.1_26.1.2", "26.2"]
NEOFORGE_JARS = ["1.20.1", "1.20.4_1.20.6", "1.21_1.21.5", "1.21.6_1.21.8",
                 "1.21.9_1.21.10", "1.21.11", "26.1_26.1.2", "26.2"]


def discover_jdk(major):
    """按 jdk-<major> 前缀探测本机 JDK；JAVA_HOME 版本匹配时优先。"""
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

os.makedirs(OUT_FABRIC, exist_ok=True)
os.makedirs(OUT_FORGE, exist_ok=True)
os.makedirs(OUT_NEOFORGE, exist_ok=True)
results = []
build_start_times = {}  # (label, ver) -> 构建开始时间戳，供拷贝阶段识别本次产物


def build_all(label, base_dir, jar_list):
    for jar in jar_list:
        d = os.path.join(base_dir, jar)
        gradlew = os.path.join(d, "gradlew.bat")
        if not os.path.exists(gradlew):
            results.append((f"{label}/{jar}", "SKIP", "no gradlew.bat"))
            continue
        jdk = JDK25 if jar in JARS_26X else JDK21
        env = os.environ.copy()
        env["JAVA_HOME"] = jdk
        env["PATH"] = os.path.join(jdk, "bin") + os.pathsep + env.get("PATH", "")
        print(f"[{label}/{jar}] building (JAVA_HOME={jdk})...", flush=True)
        t0 = time.time()
        build_start_times[(label, jar)] = t0
        try:
            r = subprocess.run(
                [gradlew, "build", "-x", "test", "--console=plain"],
                cwd=d, env=env,
                capture_output=True, text=True, encoding="utf-8", errors="replace",
                timeout=600
            )
            if r.returncode == 0 and "BUILD SUCCESSFUL" in r.stdout:
                results.append((f"{label}/{jar}", "OK", ""))
            else:
                err_lines = []
                for line in (r.stdout + r.stderr).splitlines():
                    if "error:" in line.lower() or "FAIL" in line or "BUILD" in line:
                        err_lines.append(line.strip()[:200])
                results.append((f"{label}/{jar}", "FAIL",
                                "\n    ".join(err_lines[-5:]) if err_lines else r.stdout[-500:]))
        except subprocess.TimeoutExpired:
            results.append((f"{label}/{jar}", "TIMEOUT", ""))
        except Exception as e:
            results.append((f"{label}/{jar}", "ERROR", str(e)[:200]))


def copy_newest_jar(label, base_dir, jar, t0):
    """只拷贝本次构建产生的 jar（mtime 晚于构建开始），防止残留旧包误拷。"""
    bd = os.path.join(base_dir, jar, "build", "libs")
    out_dir = OUT_DIRS[label]
    if not os.path.isdir(bd):
        print(f"  [{label}/{jar}] no build/libs dir")
        return
    fresh = [f for f in os.listdir(bd)
             if f.endswith(".jar") and "-sources" not in f and "-dev" not in f
             and os.path.getmtime(os.path.join(bd, f)) >= t0 - 5]
    if not fresh:
        print(f"  [{label}/{jar}] no freshly built jar (stale leftovers ignored)")
        return
    fresh.sort(key=lambda f: os.path.getmtime(os.path.join(bd, f)), reverse=True)
    src = os.path.join(bd, fresh[0])
    dst = os.path.join(out_dir, fresh[0])
    shutil.copy2(src, dst)
    print(f"  [{label}/{jar}] {fresh[0]}")


build_all("fabric", BASE_FABRIC, FABRIC_JARS)
build_all("forge", BASE_FORGE, FORGE_JARS)
build_all("neoforge", BASE_NEOFORGE, NEOFORGE_JARS)

print("\n=== BUILD RESULTS ===")
ok_count = 0
fail_statuses = ("FAIL", "TIMEOUT", "ERROR")
failed_count = 0
for label, status, detail in results:
    tag = "OK" if status == "OK" else f"FAIL({status})" if status in fail_statuses else "SKIP"
    print(f"  [{label}] {tag}" + (f" -- {detail}" if detail and status != "OK" else ""))
    if status == "OK":
        ok_count += 1
    elif status in fail_statuses:
        failed_count += 1
print(f"\n{ok_count} OK, {failed_count} FAILED, {len(results) - ok_count - failed_count} SKIPPED")

OUT_DIRS = {"fabric": OUT_FABRIC, "forge": OUT_FORGE, "neoforge": OUT_NEOFORGE}
BASE_DIRS = {"fabric": BASE_FABRIC, "forge": BASE_FORGE, "neoforge": BASE_NEOFORGE}
JAR_LISTS = {"fabric": FABRIC_JARS, "forge": FORGE_JARS, "neoforge": NEOFORGE_JARS}

if failed_count == 0:
    print("\n=== COPYING JARS ===")
    for label in ("fabric", "forge", "neoforge"):
        for jar in JAR_LISTS[label]:
            t0 = build_start_times.get((label, jar))
            if t0 is None:
                continue
            copy_newest_jar(label, BASE_DIRS[label], jar, t0)
    print("\nALL COPIED")

sys.exit(1 if failed_count else 0)
