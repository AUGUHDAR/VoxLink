# -*- coding: utf-8 -*-
# 2026-10-06 S1/S2 插入块内层缩进 +3 空格修正(对齐仓库3空格阶梯风格)
import io, os, re, sys

ROOT = r"D:\桌面\voxlink"

S1FIX = re.compile(
    r"(// 房客取消同样清TURN对端)(\s+)"
    r"(if \(this\.isTurnPeer\(from\)\) \{)(\s+)"
    r"(this\.cleanupTurnQuietly\(\);)(\s+)"
    r"(\})"
)

S2FIX = re.compile(
    r'(case "(?:host_closing|room_evicted)":\s*//[^\r\n]*)(\s+)'
    r'(if \(!"host"\.equals\(from\) && !"server"\.equals\(from\)\) \{)(\s+)'
    r'(VoxLinkMod\.LOGGER\.warn\("\[Security\] Discarded (?:host_closing|room_evicted) from untrusted source: \{\}", from\);)(\s+)'
    r"(break;)(\s+)"
    r"(\})"
)

def plus3(sep):
    # 分隔串以缩进空白结尾, 追加3空格即内层+1级
    return sep + "   "

def fix_cm(p):
    s = io.open(p, encoding="utf-8", newline="").read()
    hits = S1FIX.findall(s)
    if len(hits) != 1:
        return "CM_ANCHOR=%d" % len(hits)
    m = S1FIX.search(s)
    s2 = S1FIX.sub(lambda m: m.group(1) + m.group(2) + m.group(3) + plus3(m.group(4)) + m.group(5) + m.group(6) + m.group(7), s, count=1)
    if s2 == s:
        return "CM_NOCHANGE"
    io.open(p, "w", encoding="utf-8", newline="").write(s2)
    return "OK"

def fix_rm(p):
    s = io.open(p, encoding="utf-8", newline="").read()
    hits = S2FIX.findall(s)
    if len(hits) != 2:
        return "RM_ANCHOR=%d" % len(hits)
    def repl(m):
        return (m.group(1) + m.group(2) + m.group(3) + plus3(m.group(4)) + m.group(5)
                + plus3(m.group(6)) + m.group(7) + m.group(8) + m.group(9))
    s2 = S2FIX.sub(repl, s, count=2)
    if s2 == s:
        return "RM_NOCHANGE"
    io.open(p, "w", encoding="utf-8", newline="").write(s2)
    return "OK"

fails = []
for loader in ("fabric", "forge", "neoforge"):
    d = os.path.join(ROOT, loader)
    for v in sorted(os.listdir(d)):
        base = os.path.join(d, v, "src", "main", "java", "icu", "wuhui", "voxlink", "room")
        r1 = fix_cm(os.path.join(base, "ConnectionManager.java"))
        r2 = fix_rm(os.path.join(base, "RoomManager.java"))
        print(loader + "/" + v, r1, r2)
        if r1 != "OK":
            fails.append((loader + "/" + v, r1))
        if r2 != "OK":
            fails.append((loader + "/" + v, r2))
print("FAILS:", len(fails), fails)
sys.exit(1 if fails else 0)
