import glob, hashlib, os, difflib

base = r"d:\桌面\voxlink"
files = sorted(glob.glob(os.path.join(base, "*", "*", "src", "main", "java", "icu", "wuhui", "voxlink", "modsync", "ModSyncGuestService.java")))
reps = {}
for f in files:
    with open(f, "rb") as fh:
        h = hashlib.md5(fh.read()).hexdigest()
    reps.setdefault(h, f)

print("=== cluster representatives ===")
for h, f in reps.items():
    print(h[:10], os.path.relpath(f, base))

hs = list(reps)
print()
for i in range(1, len(hs)):
    with open(reps[hs[0]], encoding="utf-8") as fh:
        a = fh.read().splitlines()
    with open(reps[hs[i]], encoding="utf-8") as fh:
        b = fh.read().splitlines()
    print(f"=== diff {os.path.basename(os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(reps[hs[0]]))))))} vs rep{i} ===")
    for line in difflib.unified_diff(a, b, lineterm="", n=1):
        if line.startswith(("+++", "---", "@@")):
            continue
        print(line)

# zh_cn.json 两个变体
langs = sorted(glob.glob(os.path.join(base, "*", "*", "src", "main", "resources", "assets", "voxlink", "lang", "zh_cn.json")))
variants = {}
for f in langs:
    with open(f, "rb") as fh:
        h = hashlib.md5(fh.read()).hexdigest()
    variants.setdefault(h, []).append(os.path.relpath(f, base))
print()
print("=== zh_cn.json variants ===")
for h, lst in variants.items():
    print(h[:10], len(lst), lst[:4], "..." if len(lst) > 4 else "")
if len(variants) == 2:
    (h1, l1), (h2, l2) = variants.items()
    with open(os.path.join(base, l1[0]), encoding="utf-8") as fh:
        a = fh.read().splitlines()
    with open(os.path.join(base, l2[0]), encoding="utf-8") as fh:
        b = fh.read().splitlines()
    print(f"=== diff {l1[0]} <-> {l2[0]} ===")
    for line in difflib.unified_diff(a, b, lineterm="", n=0):
        if line.startswith(("+++", "---", "@@")):
            continue
        print(line)
