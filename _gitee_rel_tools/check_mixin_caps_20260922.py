# -*- coding: utf-8 -*-
"""直接从本地 mixin jar 读 CompatibilityLevel 实际支持的 JAVA 等级上限"""
import glob, re, zipfile

pats = [
    r"D:\桌面\voxlink\客户端测试\libraries\org\spongepowered\mixin\*\mixin-*.jar",
    r"D:\.gradle\caches\modules-2\files-2.1\org.spongepowered\mixin\*\*\mixin-*.jar",
]
seen = {}
for pat in pats:
    for j in glob.glob(pat):
        try:
            with zipfile.ZipFile(j) as z:
                for n in z.namelist():
                    if n.endswith("MixinEnvironment$CompatibilityLevel.class"):
                        lv = sorted({m.decode() for m in re.findall(rb"JAVA_\d+", z.read(n))},
                                    key=lambda s: int(s.split("_")[1]))
                        key = j.split("\\")[-1]
                        seen[key] = lv
        except Exception:
            pass

for k, v in sorted(seen.items()):
    print("%-28s %s" % (k, v))
