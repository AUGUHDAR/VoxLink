# -*- coding: utf-8 -*-
"""修正版：读 Forge 安装包 version.json 里的真实 mixin 依赖（不排除 sponge）"""
import json, os, re, zipfile

CACHE = r"d:\桌面\voxlink\_gitee_rel_tools\forge_installers"
for name in sorted(os.listdir(CACHE)):
    if not name.endswith(".jar"):
        continue
    try:
        with zipfile.ZipFile(os.path.join(CACHE, name)) as z:
            deps = []
            for entry in ("version.json", "install_profile.json"):
                if entry not in z.namelist():
                    continue
                j = json.loads(z.read(entry).decode("utf-8", "replace"))
                for lib in (j.get("libraries") or []):
                    n = lib.get("name") if isinstance(lib, dict) else lib
                    if isinstance(n, str) and "mixin" in n.lower():
                        deps.append(n)
            print("%-40s %s" % (name, deps if deps else "NONE"))
    except Exception as e:
        print("%-40s ERR %s" % (name, e))

print()
print("=== 本地已存在的 mixin jar 及其上限 ===")
import glob
for j in sorted(glob.glob(r"D:\桌面\voxlink\客户端测试\libraries\org\spongepowered\mixin\*\mixin-*.jar")):
    with zipfile.ZipFile(j) as z:
        for n in z.namelist():
            if n.endswith("MixinEnvironment$CompatibilityLevel.class"):
                lv = sorted({m.decode() for m in re.findall(rb"JAVA_\d+", z.read(n))},
                            key=lambda s: int(s.split("_")[1]))
                print("%-60s cap=%s" % (j.split("\\")[-1], lv[-1] if lv else "?"))
