"""Q1 修复：建房上报的 loader 硬编码 "fabric" -> 使用 VoxLinkConstants.LOADER。

背景：SignalingClient.java 建房请求里写死 body.addProperty("loader", "fabric")，
28 个模板全一样（整文件拷贝传播时把 fabric 版带过去了），导致网站房间卡片
不管什么加载器都显示 Fabric。

铁律（来自项目记忆）：
  - 单行锚点 + count==1 断言
  - 幂等守卫：已改过就跳过，重跑安全
  - 读写用 newline='' 保持原 EOL
  - 改完必须校验：28/28 命中，且 import 到位
"""
import os
import re
import sys

BASE = r"D:\桌面\voxlink"
LOADERS = ["fabric", "forge", "neoforge"]

OLD = 'body.addProperty("loader", "fabric");'
NEW = 'body.addProperty("loader", VoxLinkConstants.LOADER);'
IMPORT = "import icu.wuhui.voxlink.VoxLinkConstants;"

changed = []
errors = []

for loader in LOADERS:
    ldir = os.path.join(BASE, loader)
    for grp in sorted(os.listdir(ldir)):
        f = os.path.join(ldir, grp, "src", "main", "java", "icu", "wuhui", "voxlink",
                         "network", "SignalingClient.java")
        if not os.path.isfile(f):
            continue
        raw = open(f, "r", encoding="utf-8", newline="").read()
        eol = "\r\n" if "\r\n" in raw else "\n"
        text = raw

        if NEW in text:                       # 幂等守卫
            changed.append((f"{loader}/{grp}", "已是目标态"))
            continue

        n = text.count(OLD)
        if n != 1:
            errors.append(f"{loader}/{grp}: 锚点命中 {n} 次（期望 1）")
            continue

        text = text.replace(OLD, NEW)

        # 确保 import 存在（SignalingClient 与 VoxLinkConstants 同包树下不同包）
        if IMPORT not in text:
            m = re.search(r"^package [^\n]*\n", text)
            if not m:
                errors.append(f"{loader}/{grp}: 找不到 package 行，无法插 import")
                continue
            # 插在最后一个 import 之后，保持分组
            ims = list(re.finditer(r"^import [^\n]*\n", text, re.M))
            if ims:
                pos = ims[-1].end()
                text = text[:pos] + IMPORT + eol + text[pos:]
            else:
                text = text[:m.end()] + eol + IMPORT + eol + text[m.end():]

        open(f, "w", encoding="utf-8", newline="").write(text)
        changed.append((f"{loader}/{grp}", "已修改"))

print(f"处理 {len(changed)} 个文件，错误 {len(errors)} 个\n")
for name, st in changed:
    print(f"  {name:<24} {st}")
if errors:
    print("\n=== 错误 ===")
    for e in errors:
        print("  " + e)

# ---- 收尾校验：28/28 ----
print("\n=== 校验 ===")
bad = 0
for loader in LOADERS:
    ldir = os.path.join(BASE, loader)
    for grp in sorted(os.listdir(ldir)):
        f = os.path.join(ldir, grp, "src", "main", "java", "icu", "wuhui", "voxlink",
                         "network", "SignalingClient.java")
        if not os.path.isfile(f):
            continue
        t = open(f, "r", encoding="utf-8", newline="").read()
        ok = (NEW in t) and (OLD not in t) and (IMPORT in t)
        if not ok:
            bad += 1
            print(f"  !! {loader}/{grp} 未达标 (NEW={NEW in t} OLD残留={OLD in t} IMPORT={IMPORT in t})")
print(f"  未达标 {bad} 个")
sys.exit(1 if (bad or errors) else 0)
