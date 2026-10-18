# -*- coding: utf-8 -*-
"""补齐 22 个模板中 drawTips 上方注释（老版本变体）"""
import os

BASE = r"D:\桌面\voxlink"
OLD = "/** 发前提示：要求 + 低价值示例 */"
NEW = "/** 引导：这里既能提建议也能报问题；其后是原有的写作引导与示例 */"

n = 0
left = []
for loader in ("fabric", "forge", "neoforge"):
    root = os.path.join(BASE, loader)
    for ver in sorted(os.listdir(root)):
        p = os.path.join(root, ver, "src", "main", "java", "icu", "wuhui", "voxlink", "ui", "FeedbackFormScreen.java")
        if not os.path.isfile(p):
            continue
        with open(p, "r", encoding="utf-8", newline="") as f:
            t = f.read()
        if OLD in t:
            t = t.replace(OLD, NEW, 1)
            with open(p, "w", encoding="utf-8", newline="") as f:
                f.write(t)
            n += 1
        elif NEW not in t:
            left.append(f"{loader}/{ver}")

print(f"注释已更新 {n} 个模板")
if left:
    print("仍未符合:", left)
