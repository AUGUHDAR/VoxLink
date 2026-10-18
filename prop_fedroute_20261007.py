# -*- coding: utf-8 -*-
# 2026-10-07 互通房加入卡死修复传播:
#   HL导入码(HL+5位,7位)被6位VoxLink码正则拒绝 -> startDualP2P直接invalid_room_code,
#   connectFed永不触发 -> 大厅点HL房永远卡"信令服务器已连接"。
# 修三处: RoomCodeRouter(+FED_CODE/isFedCode/getRouteName) + startDualP2P fed分支 + joinRoom正则放行。
# 锚点全部单行定位; 行尾按锚点行实际取样(\n / \r\n / \r\r\n 显式生成), 不重建锚点区域。
import os, re, glob, sys

ROOT = r"D:\桌面\voxlink"
SRC = os.path.join("src", "main", "java")
REL_ROUTER = os.path.join(SRC, "icu", "wuhui", "voxlink", "terracotta", "RoomCodeRouter.java")
REL_CM     = os.path.join(SRC, "icu", "wuhui", "voxlink", "room", "ConnectionManager.java")
REL_RM     = os.path.join(SRC, "icu", "wuhui", "voxlink", "room", "RoomManager.java")

def eol_after(txt, idx):
    """返回锚点位置 idx 之后紧随的行尾字节串"""
    m = re.compile(r"\r\r\n|\r\n|\n").match(txt, idx)
    return m.group(0) if m else None

def line_span(txt, pattern):
    """定位唯一单行锚: 返回(行首, 行尾excl, 行尾符)。count!=1 抛错。"""
    ms = list(pattern.finditer(txt))
    if len(ms) != 1:
        raise SystemExit("anchor count=%d for %s" % (len(ms), pattern.pattern))
    m = ms[0]
    # 回退吞掉行首缩进由 pattern 自带; 行尾符从行文本末尾取
    eol_i = txt.find("\n", m.end() - 1)
    if eol_i == -1:
        raise SystemExit("anchor not newline-terminated")
    seg = txt[m.end()-1:eol_i+1]
    eol = "\r\r\n" if seg.endswith("\r\r\n") else ("\r\n" if seg.endswith("\r\n") else "\n")
    line_end = m.end() - 1 if txt[m.end()-1] != "\n" else m.end()
    # m.end()可能落在行内; 统一重算: 行尾=下一个\n前(含其前的\r)
    nl = txt.find("\n", m.start())
    if nl == -1: nl = len(txt)
    le = nl
    if txt[nl-1:nl] == "\n" and txt[nl-2:nl-1] == "\r" and txt[nl-3:nl-2] == "\r":
        le = nl - 2
    elif txt[nl-1:nl] == "\n" and txt[nl-2:nl-1] == "\r":
        le = nl - 1
    return m.start(), le, eol

def patch(path, skip_marker, edits):
    with open(path, encoding="utf-8", newline="") as f:
        txt = f.read()
    if skip_marker in txt:
        return "SKIP(already)"
    # 预检全部锚点 count==1
    for _, pat, _ in edits:
        n = len(list(pat.finditer(txt)))
        if n != 1:
            return "FAIL(count=%d: %s)" % (n, pat.pattern[:50])
    # 逐个应用(从后往前, 避免位置漂移)
    spans = []
    for mode, pat, _ in edits:
        ms = list(pat.finditer(txt))
        spans.append(ms[0])
    applied = []
    for (mode, pat, payload), m in reversed(list(zip(edits, spans))):
        nl = txt.find("\n", m.start())
        if nl == -1: nl = len(txt)
        if txt[nl-2:nl] == "\r\r":   eol, le = "\r\r\n", nl - 2
        elif txt[nl-1:nl] == "\r":   eol, le = "\r\n", nl - 1
        else:                        eol, le = "\n", nl
        if mode == "insert_after_line":
            ins = payload.replace("\n", eol)
            txt = txt[:nl+1] + ins + txt[nl+1:]
            applied.append("ins@%d" % m.start())
        elif mode == "insert_before_line":
            ins = payload.replace("\n", eol)
            txt = txt[:m.start()] + ins + txt[m.start():]
            applied.append("insb@%d" % m.start())
        elif mode == "replace_line":
            # payload[0] 接在锚行原缩进之后(不带缩进); payload[1:] 为新行, 补锚行缩进
            line_start = txt.rfind("\n", 0, m.start()) + 1
            indent = txt[line_start:m.start()]
            body0 = payload[0]
            rest = "".join(eol + indent + ln for ln in payload[1:])
            newbody = body0 + rest
            txt = txt[:m.start()] + newbody + txt[le:]
            applied.append("rep@%d" % m.start())
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write(txt)
    return "OK(" + ",".join(applied) + ")"

A1_COMMENT = "   // 互通房导入码：HL+HL原生码前5位(服务端hlImportCode)，恒7位不与6位VoxLink码冲突"
A1_LINE    = '   private static final Pattern FED_CODE = Pattern.compile("^HL[A-Z0-9]{5}$");'
A2_METHOD  = ("   public static boolean isFedCode(String code) {\n"
              "      return code == null ? false : FED_CODE.matcher(code.toUpperCase(Locale.ROOT)).matches();\n"
              "   }\n\n")
A3_OLD     = 'return isVoxLinkCode(code) ? "VoxLink" : "Unknown";'
A3_NEW     = ['return isFedCode(code) ? "HappyLink" : isVoxLinkCode(code) ? "VoxLink" : "Unknown";']

B_BLOCK = ("      } else if (RoomCodeRouter.isFedCode(roomCode)) {\n\n"
           "         // 互通房：join回包即带fedAddress，connectFed直连HL中继，不吃陶瓦竞速\n\n"
           '         statusCallback.accept("voxlink", "voxlink.connection.joining");\n\n'
           "         return this.startVoxLinkP2P(roomCode, password);\n\n")

C_NEW = ["// 互通房导入码(HL+5位)与VoxLink码(6位)都放行，由服务端join分流",
         'if (!normalizedCode.matches("^[A-HJ-NP-Z2-9]{6}$") && !icu.wuhui.voxlink.terracotta.RoomCodeRouter.isFedCode(normalizedCode)) {']

edits_router = [
    ("insert_after_line",  re.compile(r'private static final Pattern VOXLINK_CODE = Pattern\.compile\("\^\[A-HJ-NP-Z2-9\]\{6\}\$"\);'), A1_COMMENT + "\n" + A1_LINE + "\n"),
    ("insert_before_line", re.compile(r"   public static boolean shouldUseTerracotta\(String code\) \{"), A2_METHOD),
    ("replace_line",       re.compile(r'return isVoxLinkCode\(code\) \? "VoxLink" : "Unknown";'), A3_NEW),
]
edits_cm = [
    ("insert_before_line", re.compile(r"      \} else if \(!RoomCodeRouter\.isVoxLinkCode\(roomCode\)\) \{"), B_BLOCK),
]
edits_rm = [
    ("replace_line",       re.compile(r'if \(!normalizedCode\.matches\("\^\[A-HJ-NP-Z2-9\]\{6\}\$"\)\) \{'), C_NEW),
]

results = {}
for root in ["forge", "neoforge", "fabric"]:
    for d in sorted(glob.glob(os.path.join(ROOT, root, "*/"))):
        name = os.path.relpath(d, ROOT).strip("\\/")
        try:
            r1 = patch(os.path.join(d, REL_ROUTER), "isFedCode", edits_router)
            r2 = patch(os.path.join(d, REL_CM), "RoomCodeRouter.isFedCode(roomCode)", edits_cm)
            r3 = patch(os.path.join(d, REL_RM), "terracotta.RoomCodeRouter.isFedCode(normalizedCode)", edits_rm)
        except SystemExit as e:
            r1 = r2 = r3 = str(e)
        results[name] = (r1, r2, r3)
        print("%-24s R=%-22s CM=%-22s RM=%s" % (name, r1, r2, r3))

bad = [k for k, v in results.items() if any(not (x.startswith("OK") or x.startswith("SKIP")) for x in v)]
print("\nRESULT:", "ALL PASS" if not bad else "FAIL: %s" % bad)
sys.exit(0 if not bad else 1)
