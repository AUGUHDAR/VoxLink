# -*- coding: utf-8 -*-
# 2026-10-07 逆向 prop_fedroute 首轮 v2(缩进18空格实态+裸\n行尾)。
# 块删除按固定LF文本; A3/C 行还原用正则自适应缩进, 行尾采样相邻行。写回前校验全部归零。
import os, re, glob, sys

ROOT = r"D:\桌面\voxlink"
SRC = os.path.join("src", "main", "java")
FILES = {
    "router": os.path.join(SRC, "icu", "wuhui", "voxlink", "terracotta", "RoomCodeRouter.java"),
    "cm":     os.path.join(SRC, "icu", "wuhui", "voxlink", "room", "ConnectionManager.java"),
    "rm":     os.path.join(SRC, "icu", "wuhui", "voxlink", "room", "RoomManager.java"),
}

A1 = ('   // 互通房导入码：HL+HL原生码前5位(服务端hlImportCode)，恒7位不与6位VoxLink码冲突\n'
      '   private static final Pattern FED_CODE = Pattern.compile("^HL[A-Z0-9]{5}$");\n')
A2 = ('   public static boolean isFedCode(String code) {\n'
      '      return code == null ? false : FED_CODE.matcher(code.toUpperCase(Locale.ROOT)).matches();\n'
      '   }\n\n')
B  = ('      } else if (RoomCodeRouter.isFedCode(roomCode)) {\n\n'
      '         // 互通房：join回包即带fedAddress，connectFed直连HL中继，不吃陶瓦竞速\n\n'
      '         statusCallback.accept("voxlink", "voxlink.connection.joining");\n\n'
      '         return this.startVoxLinkP2P(roomCode, password);\n\n')

RE_A3 = re.compile(r'[ \t]*return isFedCode\(code\) \? "HappyLink" : isVoxLinkCode\(code\) \? "VoxLink" : "Unknown";\n')
RE_C  = re.compile(r'[ \t]*// 互通房导入码\(HL\+5位\)与VoxLink码\(6位\)都放行，由服务端join分流\n[ \t]*if \(!normalizedCode\.matches\("\^\[A-HJ-NP-Z2-9\]\{6\}\$"\) && !icu\.wuhui\.voxlink\.terracotta\.RoomCodeRouter\.isFedCode\(normalizedCode\)\) \{\n')

def eol_at(txt, nl_idx):
    if txt[nl_idx-2:nl_idx] == "\r\r": return "\r\r\n"
    if txt[nl_idx-1:nl_idx] == "\r": return "\r\n"
    return "\n"

def del_block(txt, blk, tag):
    c = txt.count(blk)
    if c > 1: raise SystemExit("%s: block x%d not unique" % (tag, c))
    return txt.replace(blk, "") if c else txt

def revert(path, tag, marks):
    with open(path, encoding="utf-8", newline="") as f:
        txt = f.read()
    if not any(m in txt for m in marks):
        return "SKIP(clean)"
    if tag.endswith("/router"):
        txt = del_block(txt, A1, tag+"/A1")
        txt = del_block(txt, A2, tag+"/A2")
        ms = list(RE_A3.finditer(txt))
        if len(ms) != 1: raise SystemExit("%s/A3 count=%d" % (tag, len(ms)))
        m = ms[0]; nl = txt.find("\n", m.end())
        eol = eol_at(txt, nl) if nl != -1 else "\n"
        txt = txt[:m.start()] + '         return isVoxLinkCode(code) ? "VoxLink" : "Unknown";' + eol + txt[m.end():]
    elif tag.endswith("/cm"):
        txt = del_block(txt, B, tag+"/B")
    else:
        ms = list(RE_C.finditer(txt))
        if len(ms) != 1: raise SystemExit("%s/C count=%d" % (tag, len(ms)))
        m = ms[0]
        nl = txt.find("\n", m.end())
        eol = eol_at(txt, nl) if nl != -1 else "\n"
        txt = txt[:m.start()] + '         if (!normalizedCode.matches("^[A-HJ-NP-Z2-9]{6}$")) {' + eol + txt[m.end():]
    for probe in ("FED_CODE", "isFedCode"):
        if probe in txt: raise SystemExit("%s: %s still present" % (tag, probe))
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write(txt)
    return "REVERTED"

bad = []
for root in ["forge", "neoforge", "fabric"]:
    for d in sorted(glob.glob(os.path.join(ROOT, root, "*/"))):
        name = os.path.relpath(d, ROOT).replace(os.sep, "/")
        if name == "fabric/26.2":
            print("%-24s SKIP(hand-edited)" % name); continue
        try:
            r1 = revert(os.path.join(d, FILES["router"]), name+"/router", ["FED_CODE"])
            r2 = revert(os.path.join(d, FILES["cm"]),     name+"/cm",     ["isFedCode(roomCode)"])
            r3 = revert(os.path.join(d, FILES["rm"]),     name+"/rm",     ["isFedCode(normalizedCode)"])
            print("%-24s R=%-14s CM=%-14s RM=%s" % (name, r1, r2, r3))
        except SystemExit as e:
            bad.append(name); print("%-24s FAIL %s" % (name, e))
print("\nRESULT:", "ALL REVERTED" if not bad else "FAIL %s" % bad)
sys.exit(0 if not bad else 1)
