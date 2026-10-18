# -*- coding: utf-8 -*-
# 2026-10-05 越权关房修复传播: handleCancelConnection host分支 + leaveRoomOnFailure 不再关房
import io, os, re, sys

ROOT = r"D:\桌面\voxlink"
TEMPLATES = []
for loader in ("fabric", "forge", "neoforge"):
    d = os.path.join(ROOT, loader)
    for v in sorted(os.listdir(d)):
        p = os.path.join(d, v, "src", "main", "java", "icu", "wuhui", "voxlink", "room", "ConnectionManager.java")
        if os.path.isfile(p):
            TEMPLATES.append((loader + "/" + v, p))

BASE = "fabric/1.20.2_1.20.6"  # 已手工改好

# 锚点A: leaveRoomOnFailure 旧方法体(关房) —— 空白用 [\s\S]*? 宽容, 行尾用 \r?\n
A_OLD = re.compile(
    r"private void leaveRoomOnFailure\(RoomManager\.RoomState state\)\s*\{\s*"
    r"if \(this\.roomManager\.currentRoom\.get\(\) == state && state != RoomManager\.PENDING\)\s*\{\s*"
    r"if \(state\.roomInfo != null && state\.roomInfo\.isHost\(\)\)\s*\{\s*"
    r"try\s*\{\s*"
    r"this\.scheduler\.execute\(\(\) -> this\.roomManager\.leaveRoom\(\"连接失败\"\)\);?\s*"
    r"\}\s*catch \(RejectedExecutionException ex\)\s*\{\s*"
    r"VoxLinkMod\.LOGGER\.warn\(\"Scheduler closed, sync execute leaveRoom\"\);?\s*"
    r"this\.roomManager\.leaveRoom\(\"连接失败\"\);?\s*"
    r"\}\s*\}\s*\}\s*\}"
)
# 锚点B: handleCancelConnection 房客独占段(缺 host 分支)
B_OLD = re.compile(
    r"state\.roomInfo\.removePeer\(from\);\s*"
    r"this\.showConnectFailedFinal\(state, \"voxlink\.connection\.peer_cancelled\"\);\s*"
    r"Minecraft mc = Minecraft\.getInstance\(\);\s*"
    r"if \(mc != null && mc\.player != null\)\s*\{\s*"
    r"mc\.execute\(\s*"
    r"\(\) -> mc\.player\s*"
    r"\.sendSystemMessage\(Component\.translatable\(\"voxlink\.chat\.error_prefix\"\)\.append\(Component\.translatable\(\"voxlink\.connection\.peer_cancelled\"\)\)\)\s*"
    r"\);\s*"
    r"\}\s*"
    r"\}\s*"
    r"\}"
)
# 锚点B2: 1.21.x 时代形态(displayClientMessage 变体)
B_OLD2 = re.compile(
    r"state\.roomInfo\.removePeer\(from\);\s*"
    r"this\.showConnectFailedFinal\(state, \"voxlink\.connection\.peer_cancelled\"\);\s*"
    r"Minecraft mc = Minecraft\.getInstance\(\);\s*"
    r"if \(mc != null && mc\.player != null\)\s*\{\s*"
    r"mc\.execute\(\s*"
    r"\(\) -> mc\.player\s*"
    r"\.displayClientMessage\(Component\.translatable\(\"voxlink\.chat\.error_prefix\"\)\.append\(Component\.translatable\(\"voxlink\.connection\.peer_cancelled\"\)\), false\)\s*"
    r"\);\s*"
    r"\}\s*"
    r"\}\s*"
    r"\}"
)

def nl_of(s):
    return "\r\n" if "\r\n" in s else "\n"

def block(lines, indent):
    # lines: 不含缩进的代码行列表 → 按双空行风格(与基准模板一致)拼接
    out = []
    for ln in lines:
        out.append(indent + ln if ln else "")
    return ("\n\n" + "").join([])  # unused

def build_A(nl, ind):
    L = [
        "private void leaveRoomOnFailure(RoomManager.RoomState state) {",
        "",
        "   if (this.roomManager.currentRoom.get() == state && state != RoomManager.PENDING) {",
        "",
        "      if (state.roomInfo != null && state.roomInfo.isHost()) {",
        "",
        "         // 房主侧连接失败≠房间失败：回IDLE继续等人，关房只能由房主本人操作触发",
        "",
        "         // (修复前此分支leaveRoom→服务端强制关房，任何房客失败/取消都能关掉整个房间)",
        "",
        "         this.connectionCycleActive.set(false);",
        "",
        "         this.connectionWon.set(false);",
        "",
        "         ConnectionState.transitionTo(ConnectionState.IDLE, \"连接失败,回等待\");",
        "",
        "      }",
        "",
        "   }",
        "",
        "}",
    ]
    return nl.join(ind + x for x in L)

def build_B(nl, ind, msg21=False):
    # ind 为方法体一级缩进(3空格)基准; 内部层级在行内自带相对缩进
    if msg21:
        send_line = "               .displayClientMessage(Component.translatable(\"voxlink.chat.error_prefix\").append(Component.translatable(\"voxlink.connection.peer_cancelled\")), false)"
    else:
        send_line = "               .sendSystemMessage(Component.translatable(\"voxlink.chat.error_prefix\").append(Component.translatable(\"voxlink.connection.peer_cancelled\")))"
    L = [
        "state.roomInfo.removePeer(from);",
        "",
        "   if (state.roomInfo.isHost()) {",
        "",
        "      // 对端取消不是本机连接失败：房主只清该房客回等待，绝不能终态关房",
        "",
        "      this.clearHostPunchingState();",
        "",
        "      this.abortPunchOnPeerDisconnect(from);",
        "",
        "      ReliableUdpTransport transport = this.removeUdpTransport(from);",
        "",
        "      if (transport != null) {",
        "",
        "         try {",
        "",
        "            transport.close();",
        "",
        "         } catch (Exception e) {",
        "",
        "         }",
        "",
        "      }",
        "",
        "      icu.wuhui.voxlink.ui.UiLogBus.push(2, \"voxlink.logui.peer_left\");",
        "",
        "   } else {",
        "",
        "      this.showConnectFailedFinal(state, \"voxlink.connection.peer_cancelled\");",
        "",
        "      Minecraft mc = Minecraft.getInstance();",
        "",
        "      if (mc != null && mc.player != null) {",
        "",
        "         mc.execute(",
        "",
        "            () -> mc.player",
        "",
        send_line,
        "",
        "         );",
        "",
        "      }",
        "",
        "   }",
        "",
        "}",
    ]
    return nl.join(ind + x for x in L)

changed, skipped, failed = [], [], []
for name, p in TEMPLATES:
    with io.open(p, encoding="utf-8", newline="") as fh:
        s = fh.read()
    if name == BASE:
        skipped.append((name, "baseline"))
        continue
    if "对端取消不是本机连接失败" in s:
        skipped.append((name, "already-fixed"))
        continue
    nl = nl_of(s)
    mA = A_OLD.search(s)
    mB = B_OLD.search(s)
    mB2 = None if mB else B_OLD2.search(s)
    if mB2:
        mB = mB2
    if not mA or not mB:
        failed.append((name, "anchor-A" if not mA else "anchor-B"))
        continue
    # 替换顺序: 先 B(位置靠后) 再 A, 免偏移
    indB = "         "  # 方法体内一级(9空格, 与基准一致: 方法体缩进3级)
    # 用原文实测缩进: removePeer 行的缩进
    line_start = s.rfind("\n", 0, mB.start()) + 1
    indB = s[line_start:mB.start()] or "         "
    newB = build_B(nl, indB, msg21=(mB2 is not None))
    s2 = s[:mB.start()] + newB + s[mB.end():]
    mA2 = A_OLD.search(s2)
    if not mA2:
        failed.append((name, "anchor-A-after-B"))
        continue
    line_startA = s2.rfind("\n", 0, mA2.start()) + 1
    indA = s2[line_startA:mA2.start()] or "   "
    newA = build_A(nl, indA)
    s3 = s2[:mA2.start()] + newA + s2[mA2.end():]
    with io.open(p, "w", encoding="utf-8", newline="") as fh:
        fh.write(s3)
    changed.append(name)

print("CHANGED", len(changed))
for c in changed: print("  +", c)
print("SKIPPED", len(skipped))
for c, r in skipped: print("  =", c, r)
print("FAILED", len(failed))
for c, r in failed: print("  !", c, r)
sys.exit(1 if failed else 0)
