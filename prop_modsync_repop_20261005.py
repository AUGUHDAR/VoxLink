# -*- coding: utf-8 -*-
# 2026-10-05 ModSync 弹窗禁记忆修复: bypass 收窄为在途作废, 每次加入重新弹获取页
import io, os, re, sys

ROOT = r"D:\桌面\voxlink"
TEMPLATES = []
for loader in ("fabric", "forge", "neoforge"):
    d = os.path.join(ROOT, loader)
    for v in sorted(os.listdir(d)):
        g = os.path.join(d, v, "src", "main", "java", "icu", "wuhui", "voxlink", "modsync", "ModSyncGuestService.java")
        a = os.path.join(d, v, "src", "main", "java", "icu", "wuhui", "voxlink", "ui", "AttemptingJoinScreen.java")
        if os.path.isfile(g) and os.path.isfile(a):
            TEMPLATES.append((loader + "/" + v, g, a))

BASE = "fabric/1.20.2_1.20.6"

# 锚1: clearGateFor 方法体(只清 GATED)
M_CLEAR = re.compile(
    r"(/\*\* 加入失败后清除门控缓存：下次再加入可重新看到下载选择窗。 \*/\s*"
    r"public static void clearGateFor\(String roomCode\) \{\s*"
    r"if \(roomCode != null\) \{\s*"
    r"GATED_THIS_LAUNCH\.remove\(roomCode\);)(\s*)(\} Portions?)",
    re.IGNORECASE
)
M_CLEAR = re.compile(
    r"(/\*\* [^*]*?清除门控缓存[^*]*? \*/\s*"
    r"public static void clearGateFor\(String roomCode\) \{\s*"
    r"if \(roomCode != null\) \{\s*"
    r"GATED_THIS_LAUNCH\.remove\(roomCode\);)(\s*)(\})"
)
CLEAR_NEW_HEAD = (
    "/* 清该房门控会话状态（在途标记+bypass）：新一次加入必须重新弹获取模组页。 */\n"
    "   public static void clearGateFor(String roomCode) {\n"
    "      if (roomCode != null) {\n"
    "         GATED_THIS_LAUNCH.remove(roomCode);"
)

# 锚2: bypass javadoc
M_BYPASS = re.compile(r"/\*\* 玩家在获取清单页点\"直接进入\"或取消加入：本次启动内该房间跳过检查，在途回调丢弃。 \*/")

# 锚3: shouldSkipGate javadoc (行首含 * 字符, 用 [\s*]* 容忍)
M_SKIP = re.compile(r"/\*\*[\s*]*是否跳过门控：只认玩家在获取清单页明确点过的\"跳过检查\"（BYPASSED_THIS_LAUNCH）。[\s*]*不再做任何\"本次启动已弹过就不再弹\"的记忆——房主随时可能增删必装 mod，[\s*]*每次加入都重新拉清单重新判定；弹窗是否展示由 diff 结果决定（无差异不弹，无感知）。[\s*]*\*/")

# 锚4: AttemptingJoinScreen 两参构造
M_CTOR = re.compile(
    r"(public AttemptingJoinScreen\(Screen parent, String roomCode, String password\) \{\s*"
    r"this\(parent, roomCode, password, false\);)(\s*)(\})"
)

changed, skipped, failed = [], [], []
for name, g, a in TEMPLATES:
    if name == BASE:
        skipped.append((name, "baseline"))
        continue
    if "重新弹获取模组页" in io.open(g, encoding="utf-8").read():
        skipped.append((name, "already-fixed"))
        continue
    sg = io.open(g, encoding="utf-8", newline="").read()
    sa = io.open(a, encoding="utf-8", newline="").read()
    nl_g = "\r\n" if "\r\n" in sg else "\n"
    nl_a = "\r\n" if "\r\n" in sa else "\n"
    bad = []

    m1 = M_CLEAR.search(sg)
    if not m1:
        bad.append("clearGateFor")
    else:
        lead = m1.group(2).lstrip("\r\n")
        ins = (nl_g + lead + "BYPASSED_THIS_LAUNCH.remove(roomCode);" + nl_g + lead)
        new_head = M_CLEAR.sub(lambda mm: mm.group(1) + ins + mm.group(3), sg, count=1)
        # 替换 javadoc(原 doc 里含"加入失败后清除门控缓存"字样已被 group(1) 携带, 换新注释)
        sg2 = new_head
        # 把旧 javadoc 换成新的
        old_doc = re.search(r"/\*\* [^*]*?清除门控缓存[^*]*? \*/", sg2)
        if old_doc:
            sg2 = sg2[:old_doc.start()] + "/* 清该房门控会话状态（在途标记+bypass）：新一次加入必须重新弹获取模组页。 */" + sg2[old_doc.end():]
        sg = sg2

    m2 = M_BYPASS.search(sg)
    if not m2:
        bad.append("bypass-doc")
    else:
        sg = sg[:m2.start()] + "/* 获取页\"直接进入\"/取消：仅作废本次在途清单回调，不跨加入记忆。 */" + sg[m2.end():]

    m3 = M_SKIP.search(sg)
    if not m3:
        bad.append("skip-doc")
    else:
        newdoc = (
            "/*\n"
            "   * 门控跳过仅存在于单次加入流程内（bypass 后由新流程入口 clearGateFor 重置）——\n"
            "   * 每次加入都重新拉清单重新判定；弹窗是否展示由 diff 结果决定（无差异不弹，无感知）。\n"
            "   */"
        ).replace("\n", nl_g)
        sg = sg[:m3.start()] + newdoc + sg[m3.end():]

    m4 = M_CTOR.search(sa)
    if not m4:
        bad.append("ctor")
    else:
        lead = m4.group(2).lstrip("\r\n")
        ins = (nl_a + lead + "// 新一次加入：清上次 bypass，获取模组页每次都重新弹" + nl_a
               + lead + "icu.wuhui.voxlink.modsync.ModSyncGuestService.clearGateFor(this.roomCode);" + nl_a + lead)
        sa = sa[:m4.start(2)] + ins + sa[m4.start(2):][len(m4.group(2)):] if False else sa[: m4.start(2)] + ins + sa[m4.end(2):]

    if bad:
        failed.append((name, ",".join(bad)))
        continue

    with io.open(g, "w", encoding="utf-8", newline="") as fh:
        fh.write(sg)
    with io.open(a, "w", encoding="utf-8", newline="") as fh:
        fh.write(sa)
    changed.append(name)

print("CHANGED", len(changed))
for c in changed: print("  +", c)
print("SKIPPED", len(skipped))
for c, r in skipped: print("  =", c, r)
print("FAILED", len(failed))
for c, r in failed: print("  !", c, r)
sys.exit(1 if failed else 0)
