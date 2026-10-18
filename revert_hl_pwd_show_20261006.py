# -*- coding: utf-8 -*-
# 回滚 2026-10-06 的 HL 密码房展示改动（用户裁决：密码房就是要隐藏，密码就是要挡人）。
# 恢复 applyFilter 的 !hasPassword 过滤与 hiddenPasswordCount 原状，×28 模板。
import glob
import re
import sys

IO_KW = dict(encoding="utf-8", newline="")

C1 = "// 互通：HL 密码房照常展示可点加入（服务端对导入房免密码校验）；"
C2 = "// VoxLink 自身密码房仍隐藏（mod 浏览器无密码框，走\"输入房间号\"）"
FILTER_BAD = '.filter(r -> !r.hasPassword || "happylink".equals(r.source))'
FILTER_OK = ".filter(r -> !r.hasPassword)"
COUNT_BAD = '.filter(r -> r.hasPassword && !"happylink".equals(r.source)).count();'
COUNT_OK = ".filter(r -> r.hasPassword).count();"

files = []
for loader in ("fabric", "forge", "neoforge"):
    files += glob.glob(f"{loader}/*/src/main/java/icu/wuhui/voxlink/ui/RoomBrowserScreenBase.java")

ok = True
for f in sorted(files):
    s = open(f, **IO_KW).read()
    orig = s
    # 1) 删两行注释（含行首缩进与行尾 EOL，兼容 \n / \r\n）
    s = re.sub(
        r"[ \t]*" + re.escape(C1) + r"\r?\n[ \t]*" + re.escape(C2) + r"\r?\n",
        "",
        s,
        count=1,
    )
    # 2) 过滤器还原
    if s.count(FILTER_BAD) != 1:
        print("[FAIL filter] " + f)
        ok = False
        continue
    s = s.replace(FILTER_BAD, FILTER_OK, 1)
    # 3) 计数还原
    if s.count(COUNT_BAD) != 1:
        print("[FAIL count] " + f)
        ok = False
        continue
    s = s.replace(COUNT_BAD, COUNT_OK, 1)
    open(f, "w", **IO_KW).write(s)
    chk = open(f, **IO_KW).read()
    good = (
        chk.count(FILTER_OK) >= 1
        and chk.count(FILTER_BAD) == 0
        and chk.count(COUNT_BAD) == 0
        and chk.count(C1) == 0
        and chk.count(C2) == 0
    )
    print(("[OK] " if good else "[BAD] ") + f)
    ok &= good

print("ALL PASS" if ok else "HAS FAILURES")
sys.exit(0 if ok else 1)
