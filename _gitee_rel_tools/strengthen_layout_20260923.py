# -*- coding: utf-8 -*-
"""加强：官网/反馈行即使顺延，也绝不与陶瓦下载行重叠（28 模板）"""
import os
import re

BASE = r"D:\桌面\voxlink"
NL = r"\r?\n"

OLD = re.compile(
    r'            // 官网行优先紧贴底部栈；若顶部组被兜底抬高，则顺延到顶部组之下（宁可紧凑，绝不隐藏）' + NL +
    r'            this\.lyWebsite = Math\.max\(websiteRow, this\.lyTopStart \+ sectionH - L_ROW \+ L_STEP\);'
)

NEW = ('            // 官网/反馈行常驻，且绝不与陶瓦下载行重叠：\n'
       '            // 首选紧贴底部栈上方；顶部组被兜底抬高时顺延到其下方；顺延会压到下载行则退回顶部组下方最小间距\n'
       '            int wy = Math.max(websiteRow, this.lyTopStart + sectionH - L_ROW + L_STEP);\n'
       '            if (showDownloadRow && wy + L_ROW > downloadRow) {\n'
       '               wy = Math.max(websiteRow, this.lyTopStart + sectionH - L_ROW + L_GAP);\n'
       '            }\n\n'
       '            this.lyWebsite = wy;')

n, miss = 0, []
for loader in ("fabric", "forge", "neoforge"):
    root = os.path.join(BASE, loader)
    for ver in sorted(os.listdir(root)):
        p = os.path.join(root, ver, "src", "main", "java", "icu", "wuhui", "voxlink", "ui", "VoxLinkScreen.java")
        if not os.path.isfile(p):
            continue
        with open(p, "r", encoding="utf-8", newline="") as f:
            t = f.read()
        if "int wy = Math.max(websiteRow" in t:
            continue
        new, cnt = OLD.subn(lambda m: NEW, t, count=1)
        if cnt:
            with open(p, "w", encoding="utf-8", newline="") as f:
                f.write(new)
            n += 1
        else:
            miss.append(f"{loader}/{ver}")

print(f"已加强 {n} 个模板")
if miss:
    print("未匹配:", miss)
