"""把 WS 半开修复传播到 28 个模板。

铁律（项目记忆）：
  - 传播前必须 md5 分组对账，确认各模板同名文件同源，整拷才安全
  - 有差异的模板不能盲拷，要单独 diff 适配
  - 传播后必须校验

本脚本只处理 SignalingWsTransport.java（纯 network 文件，历史上是整拷对象）。
"""
import os
import hashlib
import subprocess
import sys

BASE = r"D:\桌面\voxlink"
LOADERS = ["fabric", "forge", "neoforge"]
REL = os.path.join("src", "main", "java", "icu", "wuhui", "voxlink", "network",
                   "SignalingWsTransport.java")
CANON = os.path.join(BASE, "fabric", "26.3", REL)


def md5(p):
    return hashlib.md5(open(p, "rb").read()).hexdigest()


def head_bytes(rel):
    """取 git HEAD 版本内容（用 / 路径）。"""
    r = subprocess.run(["git", "show", "HEAD:" + rel.replace("\\", "/")],
                       cwd=BASE, capture_output=True)
    if r.returncode != 0:
        return None
    return r.stdout


targets = []
for loader in LOADERS:
    ldir = os.path.join(BASE, loader)
    for grp in sorted(os.listdir(ldir)):
        p = os.path.join(ldir, grp, REL)
        if os.path.isfile(p):
            targets.append((loader, grp, p))

print(f"目标文件 {len(targets)} 个\n")

# --- 1) 分组对账：比较 git HEAD 版本（原始态），确认同源 ---
head_groups = {}
for loader, grp, p in targets:
    rel = os.path.join(loader, grp, REL)
    h = head_bytes(rel)
    key = hashlib.md5(h).hexdigest()[:12] if h else "NO_HEAD"
    head_groups.setdefault(key, []).append(f"{loader}/{grp}")

print("=== git HEAD 原始态的分组 ===")
for k, v in head_groups.items():
    print(f"  {k}: {len(v)} 个")
    if len(v) <= 30:
        print("     " + ", ".join(v))

canon_rel = os.path.join("fabric", "26.3", REL)
canon_head = head_bytes(canon_rel)
canon_key = hashlib.md5(canon_head).hexdigest()[:12] if canon_head else "NO_HEAD"
same_src = set(head_groups.get(canon_key, []))
print(f"\n与 fabric/26.3 原始态同源的模板数 = {len(same_src)}")

# --- 2) 传播：只拷给"原始态与 canonical 一致"的模板 ---
new_content = open(CANON, "rb").read()
copied, skipped = [], []
for loader, grp, p in targets:
    name = f"{loader}/{grp}"
    if name == "fabric/26.3":
        continue
    if name not in same_src:
        skipped.append(name)
        continue
    cur = open(p, "rb").read()
    if cur == new_content:
        copied.append((name, "已是目标态"))
        continue
    open(p, "wb").write(new_content)
    copied.append((name, "已覆写"))

print(f"\n=== 传播结果 ===")
for n, s in copied:
    print(f"  {n:<26} {s}")
if skipped:
    print(f"\n!! 原始态与 canonical 不同源、未覆写（需人工 diff）: {len(skipped)}")
    for n in skipped:
        print("   " + n)

# --- 3) 校验 ---
print("\n=== 校验（应 28/28 一致）===")
bad = 0
for loader, grp, p in targets:
    if open(p, "rb").read() != new_content:
        bad += 1
        print(f"  !! {loader}/{grp} 与 canonical 不一致")
print(f"  不一致 {bad} 个 / 共 {len(targets)} 个")
sys.exit(1 if (bad or skipped) else 0)
