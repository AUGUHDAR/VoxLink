"""1.1.7 全量构建 —— 健壮版。

相比前几版的改进：
  1. 每个目标的完整输出单独留档到 _gitee_rel_tools/build_logs/，失败时能直接看真实报错
     （之前的版本只截取 6 行，导致 21 个目标失败却看不到原因）
  2. 失败自动重试一次（守护进程竞争/瞬态锁问题在重试后通常自愈）
  3. 逐目标计时，便于定位慢目标
  4. 已存在的合规 jar 可选跳过（--resume）避免重复劳动

用法：python build_117.py [--resume]
"""
import subprocess, os, sys, shutil, time

BASE = r"d:\桌面\voxlink"
OUT = os.path.join(BASE, "成品")
LOGDIR = os.path.join(BASE, "_gitee_rel_tools", "build_logs")
LOADERS = ["fabric", "forge", "neoforge"]
VERSION = "1.1.7"
RESUME = "--resume" in sys.argv

os.makedirs(LOGDIR, exist_ok=True)


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


def expected_jar(loader, ver):
    """该目标应产出的 jar 名（从 build.gradle 的 mod_version 推不出来，用目录名规则）"""
    return f"voxlink-{VERSION}-{ver}-{loader}.jar"


def run_once(loader, ver, jdk, attempt):
    d = os.path.join(BASE, loader, ver)
    gradlew = os.path.join(d, "gradlew.bat")
    env = os.environ.copy()
    env["JAVA_HOME"] = jdk
    env["PATH"] = os.path.join(jdk, "bin") + os.pathsep + env.get("PATH", "")
    t0 = time.time()
    r = subprocess.run(
        [gradlew, "clean", "build", "-x", "test", "--console=plain"],
        cwd=d, env=env, capture_output=True, text=True,
        encoding="utf-8", errors="replace", timeout=3600
    )
    dur = time.time() - t0
    logfile = os.path.join(LOGDIR, f"{loader}__{ver}.attempt{attempt}.log")
    with open(logfile, "w", encoding="utf-8") as f:
        f.write(r.stdout)
        f.write("\n\n===== STDERR =====\n")
        f.write(r.stderr)
    ok = r.returncode == 0 and "BUILD SUCCESSFUL" in r.stdout
    return ok, dur, logfile, r


def copy_jar(loader, ver):
    bd = os.path.join(BASE, loader, ver, "build", "libs")
    if not os.path.isdir(bd):
        return None
    for f in os.listdir(bd):
        if f.endswith(".jar") and "-sources" not in f and "-dev" not in f and f.startswith(f"voxlink-{VERSION}-"):
            dst_dir = os.path.join(OUT, loader.capitalize())
            os.makedirs(dst_dir, exist_ok=True)
            shutil.copy2(os.path.join(bd, f), os.path.join(dst_dir, f))
            return f
    return None


def already_done(loader, ver):
    p = os.path.join(OUT, loader.capitalize(), expected_jar(loader, ver))
    return os.path.exists(p)


results = []
t_all = time.time()
for loader in LOADERS:
    for ver in sorted(os.listdir(os.path.join(BASE, loader))):
        if not os.path.isdir(os.path.join(BASE, loader, ver)):
            continue
        name = f"{loader}/{ver}"
        if RESUME and already_done(loader, ver):
            print(f"  [{name}] 已存在产物，跳过 (--resume)", flush=True)
            results.append((name, "OK", expected_jar(loader, ver), 0))
            continue
        jdk = JDK25 if ver.startswith("26.") else JDK21
        print(f"[{name}] building (JAVA_HOME={os.path.basename(jdk)})...", flush=True)

        ok = False
        dur = 0
        logfile = ""
        for attempt in (1, 2):
            try:
                ok, dur, logfile, r = run_once(loader, ver, jdk, attempt)
            except subprocess.TimeoutExpired:
                ok, dur, logfile = False, 3600, "(timeout)"
            if ok:
                break
            # 失败时提取关键行便于看日志
            print(f"  [{name}] 第 {attempt} 次失败（{dur:.0f}s），日志 {os.path.basename(logfile)}", flush=True)
            if attempt == 1:
                time.sleep(6)
        if not ok:
            results.append((name, "FAIL", "", dur))
            print(f"  [{name}] FAIL   {dur:.0f}s", flush=True)
            continue

        got = copy_jar(loader, ver)
        results.append((name, "OK", got or "NO_JAR", dur))
        print(f"  [{name}] OK -> {got}   {dur:.0f}s", flush=True)

print("\n=== BUILD RESULTS ===")
ok_n = fail_n = 0
for name, status, got, dur in results:
    if status == "OK":
        ok_n += 1
    else:
        fail_n += 1
    print(f"  {name}: {status}" + (f" -> {got}" if got else "") + f"   {dur:.0f}s")
total = time.time() - t_all
print(f"\n{ok_n} OK, {fail_n} FAILED")
print(f"总耗时 {total:.0f}s ({total / 60:.1f} 分钟)")
sys.exit(1 if fail_n else 0)
