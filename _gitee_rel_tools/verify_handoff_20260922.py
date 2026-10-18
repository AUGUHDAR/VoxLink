import glob, os

base = r"d:\桌面\voxlink"
files = sorted(glob.glob(os.path.join(base, "*", "*", "src", "main", "resources", "assets", "voxlink", "lang", "*.json")))
keys = ["voxlink.logui.join_retry", "voxlink.logui.join_failed", "voxlink.connection.join_no_response"]
ok = 0
bad = []
langs = set()
for f in files:
    with open(f, encoding="utf-8") as fh:
        t = fh.read()
    langs.add(os.path.basename(f))
    missing = [k for k in keys if ('"%s"' % k) not in t]
    if missing:
        bad.append((f, missing))
    else:
        ok += 1
print("lang files total:", len(files))
print("all 3 keys present:", ok)
print("langs:", sorted(langs))
for f, m in bad:
    print("MISSING", f, m)
