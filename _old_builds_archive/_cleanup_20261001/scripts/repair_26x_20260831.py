# -*- coding: utf-8 -*-
"""2026-08-31 修复 forge/26.2 + neoforge/26.2（r3 统一 CRLF 写入造成混合行尾+重复插入）：
五文件已回滚 HEAD，此处按序重放 r1→r2→r3(CM)→r4，全程双 eol 探测 + 双 eol 幂等。
"""
import os, sys, io, importlib.util

ROOT = os.path.dirname(os.path.abspath(__file__))

def load(name):
    spec = importlib.util.spec_from_file_location(name, os.path.join(ROOT, name + ".py"))
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m

r1 = load("sync_turnfix_20260831")
r2 = load("sync_turnfix_r2_20260831")
r3 = load("sync_uifix_r3_20260831")
r4 = load("sync_uifix_r4_20260831")

CM = "src/main/java/icu/wuhui/voxlink/room/ConnectionManager.java"
AJS = "src/main/java/icu/wuhui/voxlink/ui/AttemptingJoinScreen.java"
VLS = "src/main/java/icu/wuhui/voxlink/ui/VoxLinkScreen.java"
TCS = "src/main/java/icu/wuhui/voxlink/ui/TerracottaConfigScreen.java"
MRS = "src/main/java/icu/wuhui/voxlink/ui/ManageRoomScreen.java"

PLAN = {
    CM: r1.CHANGES_CM + [(r2.OLD_HB, r2.NEW_HB)] + list(r3.CHANGES[r3.CM]),
    AJS: list(r1.CHANGES_AJS) + [(r2.OLD_CLICK, r2.NEW_CLICK)] + list(r4.AJS_PAIRS),
    VLS: list(r4.VLS_PAIRS),
    TCS: list(r4.TCS_PAIRS),
    MRS: list(r4.MRS_PAIRS),
}


def apply(path, pairs, report):
    with io.open(path, "r", encoding="utf-8", newline="") as f:
        text = f.read()
    ok = True
    changed = False
    for i, (old, new) in enumerate(pairs, 1):
        # 幂等：new 的任一 eol 变体已存在即跳过
        if new.replace("\n", "\n") in text or new.replace("\n", "\r\n") in text:
            continue
        hit = False
        for eol in ("\n", "\r\n"):
            old_t = old.replace("\n", eol)
            new_t = new.replace("\n", eol)
            if text.count(old_t) == 1:
                text = text.replace(old_t, new_t)
                changed = True
                hit = True
                break
        if not hit:
            report.append(("ANCHOR%d" % i, path))
            ok = False
    if ok and changed:
        with io.open(path, "w", encoding="utf-8", newline="") as f:
            f.write(text)
    return ok


def main():
    report = []
    for tpl in ["forge/26.2", "neoforge/26.2"]:
        r = True
        for rel, pairs in PLAN.items():
            r = apply(os.path.join(ROOT, tpl, rel), pairs, report) and r
        print(("OK   " if r else "FAIL ") + tpl)
    for kind, path in report:
        print("  [%s] %s" % (kind, path))
    sys.exit(1 if report else 0)


if __name__ == "__main__":
    main()
