"""成品目录 28 个 jar 的加载期风险体检。

针对的故障类型：**只在运行时才暴露、且日志里看不到的崩溃**。
典型就是 2026-09-21 遇到的：
    MixinInitialisationError: ... compatibility level JAVA_25 which is not recognised
该类异常在 Mixin bootstrap 阶段抛出，走 stderr；Forge 的 log4j 接不住、启动器又吞掉，
表现就是"崩了毫无反应、日志干净"。所以必须靠静态体检提前发现。

检查项
  A. mixin 配置：compatibilityLevel 是否低于所有 Mixin 实现的下限；minVersion；
     声明的 mixin 类是否都在；package 目录是否存在；refmap 声明 vs 文件
  B. 加载器元数据：mods.toml / neoforge.mods.toml / fabric.mod.json；Forge 的 MANIFEST MixinConfigs
  C. 字节码：所有 .class 的 major version 是否 ≤ 目标 java 版本
  D. zip 完整性
"""
import os
import re
import sys
import json
import zipfile

BASE = r"D:\桌面\voxlink"
OUT = os.path.join(BASE, "成品")
LOADER_DIRS = {"Fabric": "fabric", "Forge": "forge", "Neoforge": "neoforge"}

# 本地所有 Mixin 实现里最低的支持上限是 mixin-0.8.5 = JAVA_18。
# 保守要求 compatibilityLevel ≤ JAVA_18，这样对全部加载器都合法。
MAX_SAFE_LEVEL = 18
# 目标 java 版本 → class 文件 major
JAVA_MAJOR = {17: 61, 21: 65, 25: 69}


def java_version_of(loader, group):
    p = os.path.join(BASE, loader, group, "gradle.properties")
    if not os.path.isfile(p):
        return None
    t = open(p, "r", encoding="utf-8", errors="replace").read()
    m = re.search(r"^java_version\s*=\s*(\d+)", t, re.M)
    return int(m.group(1)) if m else None


def mod_version_of(loader, group):
    p = os.path.join(BASE, loader, group, "gradle.properties")
    if not os.path.isfile(p):
        return None
    t = open(p, "r", encoding="utf-8", errors="replace").read()
    m = re.search(r"^mod_version\s*=\s*(\S+)", t, re.M)
    return m.group(1) if m else None


def check(jar_path, loader_name, group):
    issues, warns = [], []
    z = zipfile.ZipFile(jar_path)
    names = z.namelist()

    # D. zip 完整性
    if z.testzip() is not None:
        issues.append("zip 损坏")

    # A. mixin 配置
    mixin_cfg = [n for n in names if n.endswith(".mixins.json")]
    if not mixin_cfg:
        issues.append("缺少 mixins.json")
        cfg = None
    else:
        raw = z.read(mixin_cfg[0]).decode("utf-8", "replace")
        try:
            cfg = json.loads(raw)
        except Exception as e:
            issues.append(f"mixins.json 解析失败: {e}")
            cfg = None
    if cfg:
        lv = cfg.get("compatibilityLevel")
        m = re.fullmatch(r"JAVA_(\d+)", lv or "")
        if not m:
            issues.append(f"compatibilityLevel 非法: {lv}")
        elif int(m.group(1)) > MAX_SAFE_LEVEL:
            issues.append(f"compatibilityLevel={lv} 超过安全上限 JAVA_{MAX_SAFE_LEVEL}"
                          f"（部分加载器的 Mixin 实现不认识，会静默崩溃）")
        mv = cfg.get("minVersion")
        if mv and tuple(int(x) for x in mv.split(".")) > (0, 8, 5):
            issues.append(f"minVersion={mv} 高于最低 Mixin 实现 0.8.5")
        pkg = (cfg.get("package") or "").replace(".", "/")
        if pkg and not any(n.startswith(pkg + "/") for n in names):
            issues.append(f"mixin package 目录不存在: {pkg}")
        missing = []
        for key in ("mixins", "client", "server"):
            for cls in cfg.get(key) or []:
                path = f"{pkg}/{cls.replace('.', '/')}.class"
                if path not in names:
                    missing.append(path)
        if missing:
            issues.append(f"声明的 mixin 类缺失 {len(missing)} 个: {missing[:4]}")
        rm = cfg.get("refmap")
        if rm and not any(n.endswith(rm) for n in names):
            warns.append(f"声明了 refmap={rm} 但 jar 内没有该文件")

    # B. 加载器元数据
    if loader_name == "Fabric":
        if "fabric.mod.json" not in names:
            issues.append("缺 fabric.mod.json")
        else:
            fmj = json.loads(z.read("fabric.mod.json").decode("utf-8", "replace"))
            exp = mod_version_of("fabric", group)
            if exp and fmj.get("version") != exp:
                issues.append(f"fabric.mod.json version={fmj.get('version')} != 期望 {exp}")
    elif loader_name == "Forge":
        if "META-INF/mods.toml" not in names:
            issues.append("缺 META-INF/mods.toml")
        try:
            mf = z.read("META-INF/MANIFEST.MF").decode("utf-8", "replace")
        except KeyError:
            mf = ""
            issues.append("缺 MANIFEST")
        if "MixinConfigs" not in mf:
            issues.append("MANIFEST 缺 MixinConfigs（Forge 靠它加载 mixin）")
    elif loader_name == "Neoforge":
        if "META-INF/neoforge.mods.toml" not in names and "META-INF/mods.toml" not in names:
            issues.append("缺 neoforge.mods.toml / mods.toml")

    # C. 字节码版本
    jv = java_version_of(loader_name.lower(), group)
    if jv:
        limit = JAVA_MAJOR.get(jv)
        if limit:
            worst, worst_name = 0, None
            for n in names:
                if not n.endswith(".class"):
                    continue
                try:
                    data = z.read(n)
                except Exception:
                    continue
                if len(data) < 8:
                    continue
                major = int.from_bytes(data[6:8], "big")
                if major > worst:
                    worst, worst_name = major, n
            if worst > limit:
                issues.append(f"字节码 major={worst} 超过 java_version={jv} 的 {limit} ({worst_name})")

    return issues, warns


print(f"{'jar':<44}{'结果':<8}说明")
print("-" * 100)
total = 0
bad = 0
for dname, lkey in LOADER_DIRS.items():
    d = os.path.join(OUT, dname)
    if not os.path.isdir(d):
        continue
    for f in sorted(os.listdir(d)):
        if not f.endswith(".jar"):
            continue
        total += 1
        m = re.match(r"^voxlink-1\.1\.7-(.+)-" + lkey + r"\.jar$", f)
        group = m.group(1) if m else "?"
        lname = dname
        try:
            issues, warns = check(os.path.join(d, f), lname, group)
        except Exception as e:
            issues, warns = [f"体检异常: {e}"], []
        if issues:
            bad += 1
            print(f"{f[:42]:<44}{'FAIL':<8}{issues[0]}")
            for extra in issues[1:]:
                print(f"{'':<44}{'':<8}  - {extra}")
        elif warns:
            print(f"{f[:42]:<44}{'WARN':<8}{warns[0]}")
        else:
            print(f"{f[:42]:<44}{'OK':<8}兼容等级={json.loads(zipfile.ZipFile(os.path.join(d, f)).read('voxlink.mixins.json').decode('utf-8')).get('compatibilityLevel')}")

print("-" * 100)
print(f"共体检 {total} 个 jar；FAIL {bad} 个")
sys.exit(1 if bad else 0)
