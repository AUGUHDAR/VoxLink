# -*- coding: utf-8 -*-
"""准备 A/B 对照测试 jar：A = JAVA_25（现状），B = JAVA_21（拟修复值）"""
import zipfile, shutil, re, os

SRC = r"D:\桌面\voxlink\成品\Forge\voxlink-1.1.7-26.3-forge.jar"
TMP = r"D:\桌面\voxlink\_gitee_rel_tools\_abtest"
os.makedirs(TMP, exist_ok=True)

A = os.path.join(TMP, "A_JAVA25.jar")
B = os.path.join(TMP, "B_JAVA21.jar")

shutil.copy2(SRC, A)

# 复制 A，替换其中 mixins.json 的 compatibilityLevel
zin = zipfile.ZipFile(SRC, "r")
zout = zipfile.ZipFile(B, "w", zipfile.ZIP_DEFLATED)
target = None
for item in zin.infolist():
    data = zin.read(item.filename)
    if item.filename.endswith("voxlink.mixins.json"):
        txt = data.decode("utf-8-sig")
        txt2 = re.sub(r'("compatibilityLevel"\s*:\s*)"[^"]+"', r'\1"JAVA_21"', txt)
        data = txt2.encode("utf-8")
        target = item.filename
    zout.writestr(item, data)
zin.close()
zout.close()

def show(p):
    with zipfile.ZipFile(p) as z:
        for n in z.namelist():
            if n.endswith("voxlink.mixins.json"):
                t = z.read(n).decode("utf-8-sig")
                m = re.search(r'"compatibilityLevel"\s*:\s*"([^"]+)"', t)
                print(f"  {os.path.basename(p)}: {n} -> {m.group(1)}")

print("A =", A, os.path.getsize(A))
print("B =", B, os.path.getsize(B))
print("替换条目:", target)
show(A)
show(B)
