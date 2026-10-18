"""1.1.7 全量并行构建。

设计依据（实测得出）：
  - daemon=true 不可用于批量：常驻守护进程长期占用 D:\\.gradle\\caches\\journal-1\\journal-1.lock，
    连续构建 5 个后必现 "拒绝访问" 秒失败。
  - 因此批量场景：不用守护进程（单次 JVM，用完即退，内存立即释放），速度来自并行度。
  - 并发数受内存约束：每个构建 -Xmx1536m，故用内存守卫动态等待。

用法：
    python build_117_parallel.py [--jobs=4] [--resume] [--only=forge]
"""
import subprocess, os, sys, shutil, time, ctypes, threading
from concurrent.futures import ThreadPoolExecutor, as_completed

BASE = r"d:\桌面\voxlink"
OUT = os.path.join(BASE, "成品")
LOGDIR = os.path.join(BASE, "_gitee_rel_tools", "build_logs")
LOADERS = ["fabric", "forge", "neoforge"]
VERSION = "1.1.9"

JOBS = 4
RESUME = "--resume" in sys.argv
ONLY = None
for a in sys.argv:
    if a.startswith("--jobs="):
        JOBS = int(a.split("=")[1])
    if a.startswith("--only="):
        ONLY = a.split("=")[1]
MIN_FREE_GB = 2.0          # 低于此值就等内存回收后再启动新构建

os.makedirs(LOGDIR, exist_ok=True)
print_lock = threading.Lock()


def plog(s):
    with print_lock:
        print(s, flush=True)


class MEMORYSTATUSEX(ctypes.Structure):
    _fields_ = [("dwLength", ctypes.c_ulong), ("dwMemoryLoad", ctypes.c_ulong),
                ("ullTotalPhys", ctypes.c_ulonglong), ("ullAvailPhys", ctypes.c_ulonglong),
                ("ullTotalPageFile", ctypes.c_ulonglong), ("ullAvailPageFile", ctypes.c_ulonglong),
                ("ullTotalVirtual", ctypes.c_ulonglong), ("ullAvailVirtual", ctypes.c_ulonglong),
                ("ullAvailExtendedVirtual", ctypes.c_ulonglong)]


def free_gb():
    m = MEMORYSTATUSEX()
    m.dwLength = ctypes.sizeof(m)
    ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(m))
    return m.ullAvailPhys / (1024 ** 3)


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
plog(f"并发度 = {JOBS} | JDK21 = {JDK21} | JDK25 = {JDK25} | 内存守卫阈值 = {MIN_FREE_GB}GB")


def expected_jar(loader, ver):
    return f"voxlink-{VERSION}-{ver}-{loader}.jar"


def already_done(loader, ver):
    return os.path.exists(os.path.join(OUT, loader.capitalize(), expected_jar(loader, ver)))


def wait_for_memory(need=MIN_FREE_GB, timeout=900):
    t0 = time.time()
    while free_gb() < need:
        if time.time() - t0 > timeout:
            plog(f"  [警告] 等待内存超时，仍继续（当前 {free_gb():.1f}GB）")
            return
        time.sleep(4)


def run_once(loader, ver, jdk, attempt):
    d = os.path.join(BASE, loader, ver)
    env = os.environ.copy()
    env["JAVA_HOME"] = jdk
    env["PATH"] = os.path.join(jdk, "bin") + os.pathsep + env.get("PATH", "")
    t0 = time.time()
    r = subprocess.run(
        [os.path.join(d, "gradlew.bat"), "clean", "build", "-x", "test", "--console=plain"],
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
    return ok, dur, logfile


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


def do_one(loader, ver):
    name = f"{loader}/{ver}"
    if RESUME and already_done(loader, ver):
        plog(f"  [{name}] 已存在产物，跳过")
        # 上报成 SKIP 而不是 OK：--resume 把"没编"算成成功，2026-09-27 实测把
        # 13 个缺失产物报成 "28 OK, 0 FAILED"（每条 0s），成品/ 留下一半新一半旧。
        return (name, "SKIP", expected_jar(loader, ver), 0)

    jdk = JDK25 if ver.startswith("26.") else JDK21
    wait_for_memory()
    plog(f"[{name}] 开始（可用内存 {free_gb():.1f}GB）")
    ok = False
    dur = 0
    for attempt in (1, 2):
        try:
            ok, dur, logfile = run_once(loader, ver, jdk, attempt)
        except subprocess.TimeoutExpired:
            ok, dur, logfile = False, 3600, "(timeout)"
        if ok:
            break
        plog(f"  [{name}] 第 {attempt} 次失败（{dur:.0f}s），日志 {os.path.basename(logfile)}")
        if attempt == 1:
            time.sleep(6)
    if not ok:
        return (name, "FAIL", "", dur)
    got = copy_jar(loader, ver)
    plog(f"  [{name}] OK -> {got}   {dur:.0f}s")
    return (name, "OK", got or "NO_JAR", dur)


targets = []
for loader in LOADERS:
    if ONLY and loader != ONLY:
        continue
    for ver in sorted(os.listdir(os.path.join(BASE, loader))):
        if os.path.isdir(os.path.join(BASE, loader, ver)):
            targets.append((loader, ver))

t_all = time.time()
results = []
with ThreadPoolExecutor(max_workers=JOBS) as ex:
    futs = {ex.submit(do_one, l, v): (l, v) for l, v in targets}
    for fu in as_completed(futs):
        try:
            results.append(fu.result())
        except Exception as e:
            l, v = futs[fu]
            results.append((f"{l}/{v}", "FAIL", f"exception: {e}", 0))

print("\n=== BUILD RESULTS ===")
ok_n = sum(1 for r in results if r[1] == "OK")
skip_n = sum(1 for r in results if r[1] == "SKIP")
fail_n = len(results) - ok_n - skip_n
order = {f"{l}/{v}": i for i, (l, v) in enumerate(targets)}
for name, status, got, dur in sorted(results, key=lambda x: order.get(x[0], 999)):
    print(f"  {name}: {status}" + (f" -> {got}" if got else "") + f"   {dur:.0f}s")
total = time.time() - t_all
print(f"\n{ok_n} OK, {skip_n} SKIP(未编译), {fail_n} FAILED")
if skip_n:
    print("提示：有 SKIP 说明这次 --resume 跳过了已存在的产物，成品/ 可能是新旧混合；"
          "要一次干净的集合就去掉 --resume 全量重编。")
print(f"总耗时 {total:.0f}s ({total / 60:.1f} 分钟)   并发度 {JOBS}")
sys.exit(1 if fail_n else 0)
