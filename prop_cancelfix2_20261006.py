# -*- coding: utf-8 -*-
# 2026-10-06 越权关房强化轮(服务端cancel改写的客户端第二道防线):
#   S1 ConnectionManager.handleCancelConnection host分支补 TURN 对端清理(与 handleDisconnect 对齐)
#   S2 RoomManager.handleSignal: host_closing/room_evicted 只认 host/server 来源(注册房客不可触发终态)
# 配套服务端: server-go SignalAppend 房客 cancel_connection 改写 disconnect(signal_cancel_rewrite 开关)
import io, os, re, sys

ROOT = r"D:\桌面\voxlink"
FILES = []
for loader in ("fabric", "forge", "neoforge"):
    d = os.path.join(ROOT, loader)
    for v in sorted(os.listdir(d)):
        for n in ("ConnectionManager.java", "RoomManager.java"):
            p = os.path.join(d, v, "src", "main", "java", "icu", "wuhui", "voxlink", "room", n)
            if os.path.isfile(p):
                FILES.append((loader + "/" + v + "/" + n, p))

# ---- S1: handleCancelConnection host 分支, clearHostPunchingState 之后插 TURN 清理 ----
S1_OLD = re.compile(
    r"(对端取消不是本机连接失败：房主只清该房客回等待，绝不能终态关房)([\s\S]*?)"
    r"this\.clearHostPunchingState\(\);([\s\S]*?)"
    r"this\.abortPunchOnPeerDisconnect\(from\);"
)

def s1_repl(m):
    sep1, sep2 = m.group(2), m.group(3)
    if "isTurnPeer" in sep1 or "isTurnPeer" in sep2:
        return m.group(0)  # 已插入过(幂等)
    block = (
        "// 房客取消同样清TURN对端" + sep2 +
        "if (this.isTurnPeer(from)) {" + sep2 +
        "this.cleanupTurnQuietly();" + sep2 +
        "}" + sep2
    )
    return (m.group(1) + sep1 +
            "this.clearHostPunchingState();" + sep2 + block +
            "this.abortPunchOnPeerDisconnect(from);")

# ---- S2A: host_closing 来源门控 ----
S2A_OLD = re.compile(r'case "host_closing":([\s\S]*?)this\.handleHostClosing\(from, data\);')

def s2a_repl(m):
    sep = m.group(1)
    if "Discarded host_closing" in sep:
        return m.group(0)  # 幂等
    return (
        'case "host_closing":' + sep +
        '// 关房只认host/server，房客不可触发' + sep +
        'if (!"host".equals(from) && !"server".equals(from)) {' + sep +
        'VoxLinkMod.LOGGER.warn("[Security] Discarded host_closing from untrusted source: {}", from);' + sep +
        'break;' + sep +
        '}' + sep +
        'this.handleHostClosing(from, data);'
    )

# ---- S2B: room_evicted 来源门控 ----
S2B_OLD = re.compile(r'case "room_evicted":([\s\S]*?)this\.handleRoomEvicted\(from, data\);')

def s2b_repl(m):
    sep = m.group(1)
    if "Discarded room_evicted" in sep:
        return m.group(0)  # 幂等
    return (
        'case "room_evicted":' + sep +
        '// 驱逐通知只认host/server' + sep +
        'if (!"host".equals(from) && !"server".equals(from)) {' + sep +
        'VoxLinkMod.LOGGER.warn("[Security] Discarded room_evicted from untrusted source: {}", from);' + sep +
        'break;' + sep +
        '}' + sep +
        'this.handleRoomEvicted(from, data);'
    )

def apply(path, pat, repl, tag):
    with io.open(path, "r", encoding="utf-8", newline="") as f:
        s = f.read()
    hits = pat.findall(s)
    if len(hits) != 1:
        return (tag, "ANCHOR_COUNT=%d" % len(hits), False)
    ns = pat.sub(repl, s, count=1)
    if ns == s:
        return (tag, "NOCHANGE", False)
    with io.open(path, "w", encoding="utf-8", newline="") as f:
        f.write(ns)
    return (tag, "OK", True)

fails = []
for tag, p in FILES:
    if p.endswith("ConnectionManager.java"):
        r = apply(p, S1_OLD, s1_repl, tag + "/S1")
    else:
        r1 = apply(p, S2A_OLD, s2a_repl, tag + "/S2A")
        r2 = apply(p, S2B_OLD, s2b_repl, tag + "/S2B")
        for r in (r1, r2):
            if not r[2]:
                fails.append(r)
        continue
    if not r[2]:
        fails.append(r)
    print(r)

print("==== FAILS: %d ====" % len(fails))
for f in fails:
    print(f)

# ---- 终检: 28/28 三个特征齐全 ----
bad = []
for loader in ("fabric", "forge", "neoforge"):
    d = os.path.join(ROOT, loader)
    for v in sorted(os.listdir(d)):
        cm = os.path.join(d, v, "src", "main", "java", "icu", "wuhui", "voxlink", "room", "ConnectionManager.java")
        rm = os.path.join(d, v, "src", "main", "java", "icu", "wuhui", "voxlink", "room", "RoomManager.java")
        cs = open(cm, encoding="utf-8", newline="").read()
        rs = open(rm, encoding="utf-8", newline="").read()
        ok1 = len(re.findall(r"if \(this\.isTurnPeer\(from\)\) \{", cs)) >= 1
        ok2 = "Discarded host_closing" in rs
        ok3 = "Discarded room_evicted" in rs
        if not (ok1 and ok2 and ok3):
            bad.append((loader + "/" + v, ok1, ok2, ok3))
print("==== FINAL CHECK BAD: %d ====" % len(bad))
for b in bad:
    print(b)
sys.exit(1 if (fails or bad) else 0)
