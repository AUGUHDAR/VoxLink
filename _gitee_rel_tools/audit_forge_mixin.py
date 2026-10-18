"""从 Forge 官方安装包内嵌的 version.json 读出每个 Forge 版本实际依赖的 Mixin 版本，
并给出该 Mixin 实现的 CompatibilityLevel 上限，从而判断对应 jar 的 compatibilityLevel 是否越界。

不靠推测：Forge 安装包里的 version.json 就是启动器实际会用的库清单。
"""
import io
import json
import os
import re
import urllib.request
import zipfile

PAIRS = [
    ("1.20.4", "49.2.8"), ("1.20.6", "50.2.10"), ("1.21.5", "55.1.11"),
    ("1.21.8", "58.1.20"), ("1.21.10", "60.1.13"), ("1.21.11", "61.1.14"),
    ("26.1.2", "64.1.0"), ("26.2", "65.1.0"), ("26.3", "66.0.2"),
]
CACHE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "forge_installers")
os.makedirs(CACHE, exist_ok=True)

LEVEL_RE = re.compile(rb"JAVA_\d+")


def mixin_caps(jar_path):
    """返回 {mixin实现jar名: 最高支持等级}"""
    out = {}
    try:
        with zipfile.ZipFile(jar_path) as z:
            for n in z.namelist():
                if n.endswith("MixinEnvironment$CompatibilityLevel.class"):
                    lv = sorted({m.decode() for m in LEVEL_RE.findall(z.read(n))},
                                key=lambda s: int(s.split("_")[1]))
                    out[n] = lv[-1] if lv else "?"
    except Exception:
        pass
    return out


print(f"{'Forge':<16}{'Mixin 依赖':<40}{'上限':<10}")
print("-" * 72)
results = {}
for mc, fv in PAIRS:
    name = f"forge-{mc}-{fv}-installer.jar"
    local = os.path.join(CACHE, name)
    if not os.path.exists(local):
        url = f"https://maven.minecraftforge.net/net/minecraftforge/forge/{mc}-{fv}/{name}"
        try:
            with urllib.request.urlopen(url, timeout=180) as r, open(local, "wb") as f:
                f.write(r.read())
        except Exception as e:
            print(f"{mc}-{fv:<10}下载失败: {e}")
            continue
    dep = None
    try:
        with zipfile.ZipFile(local) as z:
            for entry in ("version.json", "install_profile.json"):
                if entry not in z.namelist():
                    continue
                j = json.loads(z.read(entry).decode("utf-8", "replace"))
                for lib in (j.get("libraries") or []):
                    n = lib.get("name") if isinstance(lib, dict) else lib
                    if isinstance(n, str) and re.search(r"(^|:)mixin:", n) and "sponge" not in n:
                        dep = n
                        break
                if dep:
                    break
    except Exception as e:
        print(f"{mc}-{fv:<10}读取失败: {e}")
        continue

    # 从本地已下载的 mixin jar 里查它的上限
    cap = "?"
    if dep:
        ver = dep.split(":")[-1]
        for cand in [rf"D:\桌面\voxlink\客户端测试\libraries\org\spongepowered\mixin\{ver}\mixin-{ver}.jar",
                     rf"D:\.gradle\caches\modules-2\files-2.1\org.spongepowered\mixin\{ver}"]:
            if os.path.isfile(cand):
                caps = mixin_caps(cand)
                cap = list(caps.values())[0] if caps else "?"
                break
            if os.path.isdir(cand):
                for r2, _d, fs2 in os.walk(cand):
                    for f2 in fs2:
                        if f2.endswith(".jar"):
                            caps = mixin_caps(os.path.join(r2, f2))
                            if caps:
                                cap = list(caps.values())[0]
                                break
    results[(mc, fv)] = (dep, cap)
    print(f"{mc}-{fv:<10}{str(dep):<40}{str(cap):<10}")

print()
print("=== 结论 ===")
for (mc, fv), (dep, cap) in results.items():
    if cap == "JAVA_18":
        print(f"  Forge {mc}-{fv}: Mixin 上限 JAVA_18 -> 凡 compatibilityLevel > JAVA_18 的 jar 都会崩")
