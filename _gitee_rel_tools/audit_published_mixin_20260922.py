# -*- coding: utf-8 -*-
"""读已发布 1.1.7 jar 内的 mixins.json compatibilityLevel（玩家实际在跑的值）"""
import glob, os, re, zipfile

base = r"d:\桌面\voxlink\成品"
jars = sorted(glob.glob(os.path.join(base, "*", "voxlink-1.1.7*.jar")))
print("jars found:", len(jars))
for j in jars:
    n = os.path.basename(j)
    try:
        with zipfile.ZipFile(j) as z:
            t = z.read("voxlink.mixins.json").decode("utf-8", "replace")
            m = re.search(r'"compatibilityLevel"\s*:\s*"([^"]+)"', t)
            print("%-44s %s" % (n, m.group(1) if m else "??"))
    except Exception as e:
        print("%-44s ERR %s" % (n, e))
