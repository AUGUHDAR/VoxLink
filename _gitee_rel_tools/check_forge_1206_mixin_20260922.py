# -*- coding: utf-8 -*-
"""下载 forge 1.20.6 installer 并读其 mixin 依赖"""
import io, json, os, re, urllib.request, zipfile

CACHE = r"d:\桌面\voxlink\_gitee_rel_tools\forge_installers"
os.makedirs(CACHE, exist_ok=True)
name = "forge-1.20.6-50.2.10-installer.jar"
local = os.path.join(CACHE, name)
if not os.path.exists(local):
    url = "https://maven.minecraftforge.net/net/minecraftforge/forge/1.20.6-50.2.10/" + name
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (voxlink-audit)"})
    with urllib.request.urlopen(req, timeout=120) as r, open(local, "wb") as f:
        f.write(r.read())
with zipfile.ZipFile(local) as z:
    for entry in ("version.json", "install_profile.json"):
        if entry not in z.namelist():
            continue
        j = json.loads(z.read(entry).decode("utf-8", "replace"))
        deps = [ (lib.get("name") if isinstance(lib, dict) else lib)
                 for lib in (j.get("libraries") or [])
                 if isinstance((lib.get("name") if isinstance(lib, dict) else lib), str)
                 and "mixin" in (lib.get("name") if isinstance(lib, dict) else lib).lower() ]
        print(entry, "->", deps)
