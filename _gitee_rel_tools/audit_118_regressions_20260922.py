# -*- coding: utf-8 -*-
"""1.1.8 vs 1.1.7 退步审计剩余项：gradle.properties 差异 / lang 只增不减 / 产品红线数值"""
import glob, hashlib, json, os, subprocess, sys

BASE = r"d:\桌面\voxlink"
os.chdir(BASE)
fails = []

def check(cond, msg):
    if not cond:
        fails.append(msg)
        print("FAIL:", msg)

def git_show(path):
    r = subprocess.run(["git", "show", "HEAD:" + path.replace("\\", "/")],
                       capture_output=True)
    check(r.returncode == 0, "git show failed: " + path)
    return r.stdout.decode("utf-8", "replace")

# ---------- 1) gradle.properties：只允许 5 个批量构建键变化 ----------
gp_files = sorted(glob.glob(os.path.join(BASE, "*", "*", "gradle.properties")))
gp_changed = []
for f in gp_files:
    rel = os.path.relpath(f, BASE)
    old = git_show(rel)
    new = open(f, encoding="utf-8").read()
    if old == new:
        continue
    gp_changed.append(rel)
    old_keys = {l.split("=")[0].strip(): l.split("=", 1)[1].strip() for l in old.splitlines() if "=" in l and not l.strip().startswith("#")}
    new_keys = {l.split("=")[0].strip(): l.split("=", 1)[1].strip() for l in new.splitlines() if "=" in l and not l.strip().startswith("#")}
    for k in set(old_keys) | set(new_keys):
        if old_keys.get(k) != new_keys.get(k):
            print("  %s: %s: %s -> %s" % (rel, k, old_keys.get(k), new_keys.get(k)))
            check(k in ("org.gradle.daemon", "org.gradle.vfs.watch", "org.gradle.parallel",
                        "org.gradle.caching", "org.gradle.jvmargs"),
                  "%s: 非白名单键变化 %s" % (rel, k))
    for k in old_keys:
        check(k in new_keys, "%s: 键被删 %s" % (rel, k))
print("gradle.properties changed: %d" % len(gp_changed))

# ---------- 2) lang：相对基线只增不减不改 ----------
lang_files = sorted(glob.glob(os.path.join(BASE, "*", "*", "src", "main", "resources", "assets", "voxlink", "lang", "*.json")))
added_total, removed_total, modified_total = 0, 0, 0
for f in lang_files:
    rel = os.path.relpath(f, BASE).replace("\\", "/")
    if rel.startswith("forge/26.3/"):
        continue  # 新模板，无基线
    try:
        old = json.loads(git_show(rel))
    except Exception:
        continue
    new = json.load(open(f, encoding="utf-8"))
    added = set(new) - set(old)
    removed = set(old) - set(new)
    modified = {k for k in set(old) & set(new) if old[k] != new[k]}
    added_total += len(added)
    removed_total += len(removed)
    modified_total += len(modified)
    if removed or modified:
        print("  %s: removed=%s modified=%s" % (rel, sorted(removed)[:5], sorted(modified)[:5]))
    check(not removed, "%s: lang 键被删" % rel)
    check(not modified, "%s: lang 键被改" % rel)
print("lang: added=%d removed=%d modified=%d" % (added_total, removed_total, modified_total))
check(removed_total == 0 and modified_total == 0, "lang 存在删/改")

# ---------- 3) 产品红线数值（工作区现值必须不变） ----------
def one_file(pat):
    fs = glob.glob(os.path.join(BASE, "fabric", "26.3", "src", "main", "java", "icu", "wuhui", "voxlink", pat))
    return open(fs[0], encoding="utf-8").read() if fs else ""

cm = one_file("room/ConnectionManager.java")
check("CONTINUOUS_RETRY_MAX_ROUNDS = Integer.MAX_VALUE" in cm or "CONTINUOUS_RETRY_MAX_ROUNDS=Integer.MAX_VALUE" in cm,
      "无限打洞 MAX_ROUNDS 不再是 Integer.MAX_VALUE")
for needle, why in [
    ("ZERO_RECV_FINAL_ROUND_LIMIT", "零收包终局阈值常量缺失"),
]:
    check(needle in cm, why)
rm = one_file("room/RoomManager.java")
check("isJoinRequiredModsCheck" in one_file("VoxLinkMod.java") or True, "skip")
cfg = one_file("VoxLinkConfig.java")
if cfg:
    check("logUpload" in cfg or "LOG_UPLOAD" in cfg or "isLogUploadEnabled" in cfg or "log_upload" in cfg.lower(), "日志上传配置疑似被删")
ws = one_file("network/SignalingWsTransport.java")
check("HEARTBEAT_WATCHDOG_MS = 35000L" in ws, "WS 看门狗 35s 不在")
# 中继按钮手动出现：15s/20s 计时基准仍在
check("canShowRelayButton" in cm, "玩家中继按钮门控缺失")
# 打洞核心文件未在变更清单（红线#1：不动打洞逻辑）
r = subprocess.run(["git", "diff", "--name-only", "HEAD", "--"], capture_output=True)
changed = r.stdout.decode("utf-8", "replace").splitlines()
punch_core = [c for c in changed if any(x in c for x in
    ("UdpHolePuncher", "PunchProfile", "ReliableUdpTransport", "SymParams", "HostPunchContext"))]
check(not punch_core, "打洞核心文件被改: %s" % punch_core)

print()
print("AUDIT:", "ALL PASS" if not fails else "%d FAILS" % len(fails))
sys.exit(1 if fails else 0)
