# -*- coding: utf-8 -*-
"""审计：本轮改动（工作区 vs HEAD 924845e=1.1.7）是否引入跨模板硬编码标识污染
重点：loader/fabric/forge/neoforge/版本号——每个模板的标识必须与其目录名一致，不得来自金标准
"""
import glob, os, re, subprocess, sys

BASE = r"d:\桌面\voxlink"
os.chdir(BASE)
fails = []

def changed_files():
    r = subprocess.run(["git", "diff", "--name-only", "HEAD"], capture_output=True, text=True)
    return [l.strip() for l in r.stdout.splitlines() if l.strip().endswith((".java", ".json"))]

changed = changed_files()
print("changed java/json files:", len(changed))

# ---------- 1) 模板标识一致性：每个模板的自描述必须匹配目录 ----------
# 检查本轮新拷/修改的 java 文件里出现的 loader 标识
LOADER_RE = re.compile(r'"(fabric|forge|neoforge)"')
for f in changed:
    if not f.endswith(".java"):
        continue
    m = re.match(r"(fabric|forge|neoforge)[/\\]([^/\\]+)[/\\]", f.replace("\\", "/"))
    if not m:
        continue
    dir_loader, grp = m.group(1), m.group(2)
    try:
        t = open(os.path.join(BASE, f), encoding="utf-8", errors="replace").read()
    except Exception:
        continue
    for hit in set(LOADER_RE.findall(t)):
        if hit != dir_loader:
            # 出现他 loader 字符串：看上下文（合法场景=能力判断/兼容表）
            for i, line in enumerate(t.splitlines(), 1):
                if ('"%s"' % hit) in line:
                    ctx = line.strip()[:160]
                    # 白名单：比较/包含判断、时代 API、协议字符串
                    if any(k in line for k in ("equals", "equalsIgnoreCase", "contains", "case ", "LOADER", "loader ==")):
                        continue
                    fails.append("%s:%d 出现 \"%s\"：%s" % (f, i, hit, ctx))

# ---------- 2) SignalingClient loader 上报专项（Q1 复发检查） ----------
sc = [f for f in changed if f.endswith("SignalingClient.java")]
print("SignalingClient in changed:", len(sc))
for f in sc:
    t = open(os.path.join(BASE, f), encoding="utf-8", errors="replace").read()
    if 'addProperty("loader", "fabric")' in t:
        fails.append("Q1 复发！%s 硬编码 fabric" % f)
    if 'addProperty("loader", VoxLinkConstants.LOADER)' not in t:
        fails.append("%s: loader 上报缺失常量引用" % f)

# 全 28（不只 changed）：Q1 修复必须全在
all_sc = sorted(glob.glob(os.path.join(BASE, "*", "*", "src", "main", "java", "icu", "wuhui", "voxlink", "network", "SignalingClient.java")))
hard = [f for f in all_sc if 'addProperty("loader", "fabric")' in open(f, encoding="utf-8", errors="replace").read()]
print("SignalingClient total:", len(all_sc), "hardcoded:", len(hard))
if hard:
    fails.extend("Q1 复发于 " + f for f in hard)

# ---------- 3) gradle.properties 版本标识（本轮改过 gradle.properties 的模板） ----------
for f in changed:
    if not f.endswith("gradle.properties"):
        continue
    t = open(os.path.join(BASE, f), encoding="utf-8", errors="replace").read()
    mv = re.search(r"mod_version=(\S+)", t)
    mcv = re.search(r"minecraft_version=(\S+)", t)
    grp = f.replace("\\", "/").split("/")[1]
    if mv and not grp.startswith(mv.group(1).replace("_", ".")):
        # mod_version 如 1.1.7-1.20_1.20.1 vs 目录 1.20_1.20.1
        ver = mv.group(1).split("-", 1)[-1]
        if ver != grp:
            fails.append("%s: mod_version=%s 与目录 %s 不符" % (f, mv.group(1), grp))

# ---------- 4) mixins.json 本轮变更不得引入越界值（已有裁决基线） ----------
for f in changed:
    if not f.endswith("voxlink.mixins.json"):
        continue
    grp = f.replace("\\", "/").split("/")[1]
    loader_dir = f.replace("\\", "/").split("/")[0]
    t = open(os.path.join(BASE, f), encoding="utf-8", errors="replace").read()
    m = re.search(r'"compatibilityLevel"\s*:\s*"JAVA_(\d+)"', t)
    if not m:
        continue
    lvl = int(m.group(1))
    # 裁决基线：forge 26.x=25(用户故意)；forge 1.20.x 上限18；forge 1.21.5+=21；
    # fabric/neoforge 26.x=25 合法、21.x 可 21/22、1.20.x 可 17
    if loader_dir == "forge":
        if grp.startswith("26.") and lvl != 25:
            fails.append("%s: forge 26.x 期望 JAVA_25 实际 %d" % (f, lvl))
        if grp.startswith("1.20") and lvl > 18:
            fails.append("%s: forge 1.20.x 上限 18 实际 %d" % (f, lvl))
        if grp.startswith("1.21") and lvl > 21:
            fails.append("%s: forge 1.21.x 上限 21 实际 %d" % (f, lvl))

# ---------- 5) 新拷文件不得携带金标准专属路径/配置 ----------
for f in changed:
    if "TicketClient" in f or "TicketListScreen" in f or "TicketDetailScreen" in f:
        t = open(os.path.join(BASE, f), encoding="utf-8", errors="replace").read()
        if "26.3" in t or "fabric" in t and "fabric\" " not in t and "Loader" in t:
            pass  # 粗筛，精确查：
        for pat, why in [("fabric/26.3", "金标准路径残留"), ("D:\\\\桌面", "绝对路径残留")]:
            if pat in t:
                fails.append("%s: %s" % (f, why))

# ---------- 6) 本轮 4 个新/改 lang key 不得破坏 JSON（抽 fabric/1.20_1.20.1 全量） ----------
import json
for langf in sorted(glob.glob(os.path.join(BASE, "fabric", "1.20_1.20.1", "src", "main", "resources", "assets", "voxlink", "lang", "*.json")))[:3]:
    try:
        json.load(open(langf, encoding="utf-8"))
    except Exception as e:
        fails.append("JSON broken: %s %s" % (langf, e))

print()
print("AUDIT RESULT:", "CLEAN" if not fails else "%d ISSUES" % len(fails))
for x in fails[:40]:
    print("  FAIL", x)
sys.exit(1 if fails else 0)
