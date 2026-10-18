# -*- coding: utf-8 -*-
"""从缓存的 Forge 安装包 version.json 读各版本实际 mixin 依赖版本"""
import json, os, re, zipfile

CACHE = r"d:\桌面\voxlink\_gitee_rel_tools\forge_installers"
for name in sorted(os.listdir(CACHE)):
    if not name.endswith(".jar"):
        continue
    try:
        with zipfile.ZipFile(os.path.join(CACHE, name)) as z:
            dep = None
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
            print("%-40s %s" % (name, dep))
    except Exception as e:
        print("%-40s ERR %s" % (name, e))
