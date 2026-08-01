import subprocess, os, shutil

BASE_FABRIC = r"d:\桌面\voxlink\fabric"
BASE_NEOFORGE = r"d:\桌面\voxlink\neoforge"
OUT_FABRIC = r"d:\桌面\voxlink\成品\Fabric"
OUT_NEOFORGE = r"d:\桌面\voxlink\成品\NeoForge"
JDK21 = r"C:\Program Files\Java\jdk-21"
JDK25 = r"C:\Program Files\Java\jdk-25.0.2"
JARS_26X = {"26.1_26.1.2", "26.2"}

FABRIC_JARS = ["1.20_1.20.1", "1.20.2_1.20.6", "1.21_1.21.5", "1.21.6_1.21.8",
               "1.21.9_1.21.10", "1.21.11", "26.1_26.1.2", "26.2"]
NEOFORGE_JARS = ["1.20.1", "1.20.4_1.20.6", "1.21_1.21.5", "1.21.6_1.21.8",
                 "1.21.9_1.21.10", "1.21.11", "26.1_26.1.2", "26.2"]

os.makedirs(OUT_FABRIC, exist_ok=True)
os.makedirs(OUT_NEOFORGE, exist_ok=True)
results = []


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
        try:
            r = subprocess.run(
                [gradlew, "build", "-x", "test"],
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


build_all("fabric", BASE_FABRIC, FABRIC_JARS)
build_all("neoforge", BASE_NEOFORGE, NEOFORGE_JARS)

print("\n=== BUILD RESULTS ===")
ok_count = 0
for label, status, detail in results:
    tag = "OK" if status == "OK" else f"FAIL({status})"
    print(f"  [{label}] {tag}" + (f" -- {detail}" if detail and status != "OK" else ""))
    if status == "OK":
        ok_count += 1
print(f"\n{ok_count}/{len(results)} SUCCESS")

OUT_DIRS = {"fabric": OUT_FABRIC, "neoforge": OUT_NEOFORGE}
BASE_DIRS = {"fabric": BASE_FABRIC, "neoforge": BASE_NEOFORGE}
JAR_LISTS = {"fabric": FABRIC_JARS, "neoforge": NEOFORGE_JARS}

if ok_count == len(results):
    print("\n=== COPYING JARS ===")
    for label in ("fabric", "neoforge"):
        out_dir = OUT_DIRS[label]
        for jar in JAR_LISTS[label]:
            bd = os.path.join(BASE_DIRS[label], jar, "build", "libs")
            if not os.path.isdir(bd):
                print(f"  [{label}/{jar}] no build/libs dir")
                continue
            jars = [f for f in os.listdir(bd)
                    if f.endswith(".jar") and "-sources" not in f and "-dev" not in f]
            if not jars:
                print(f"  [{label}/{jar}] no jar found")
                continue
            jars.sort(key=lambda f: os.path.getmtime(os.path.join(bd, f)), reverse=True)
            src = os.path.join(bd, jars[0])
            dst = os.path.join(out_dir, jars[0])
            shutil.copy2(src, dst)
            print(f"  [{label}/{jar}] {jars[0]}")
    print("\nALL COPIED")
