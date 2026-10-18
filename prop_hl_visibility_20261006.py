# -*- coding: utf-8 -*-
# 2026-10-06 互通 HL 房可见性修复 ×27 模板传播（1.20.2_1.20.6 已手改，跳过）
# 修 A: SignalingWsTransport 握手带 X-VoxLink-Version（WS 帧按握手头克隆分发，
#        缺头 = showHL/allowHostPeers 按旧端处理 → HL 房在 WS 模式下全隐藏）
# 修 B: RoomBrowserScreenBase 放行 HL 密码房（展示+隐藏计数排除 happylink）
import glob
import re
import sys

IO_KW = dict(encoding="utf-8", newline="")

A_WS = re.compile(
    r"(WebSocket\.Builder builder = wsHttpClient\(\)\.newWebSocketBuilder\(\)\r?\n)"
    r"(\s+)\.connectTimeout"
)
NEW_WS = (
    r"\g<1>\g<2>"
    r"// 版本头必须随 WS 握手带上：服务端把 WS 帧按握手请求头克隆后内部分发，\g<2>"
    r"// /room/list 的 HL 展示闸与 /room/join 的 allowHostPeers 都读这个头；\g<2>"
    r"// 缺了即按旧端处理，HL 房在 WS 模式下永远不可见（1.2.0 互通实跑实测）。\g<2>"
    r".header(\"X-VoxLink-Version\", VoxLinkMod.MOD_VERSION)\g<2>"
    r".connectTimeout"
)

A_FILTER = re.compile(r"\.filter\(r -> !r\.hasPassword\)")
NEW_FILTER = (
    "// 互通：HL 密码房照常展示可点加入（服务端对导入房免密码校验）；\n"
    "         // VoxLink 自身密码房仍隐藏（mod 浏览器无密码框，走\"输入房间号\"）\n"
    "         .filter(r -> !r.hasPassword || \"happylink\".equals(r.source))"
)

A_COUNT = re.compile(
    r"long hiddenByPassword = this\.allRooms\.stream\(\)"
    r"\.filter\(r -> r\.hasPassword\)\.count\(\);"
)
NEW_COUNT = (
    "long hiddenByPassword = this.allRooms.stream()"
    ".filter(r -> r.hasPassword && !\"happylink\".equals(r.source)).count();"
)

SKIP = "1.20.2_1.20.6"


def files(sub):
    pats = ["fabric/*/src/main/java/icu/wuhui/voxlink/" + sub,
            "forge/*/src/main/java/icu/wuhui/voxlink/" + sub,
            "neoforge/*/src/main/java/icu/wuhui/voxlink/" + sub]
    out = []
    for p in pats:
        out += glob.glob(p)
    return sorted(out)


def patch(path, pairs):
    for pat, rep, name in pairs:
        s = open(path, **IO_KW).read()
        m = pat.findall(s)
        if len(m) != 1:
            print("[FAIL] %s anchor %s count=%d" % (path, name, len(m)))
            return False
        s = pat.sub(rep, s, count=1)
        open(path, "w", **IO_KW).write(s)
    print("[OK] " + path)
    return True


def main():
    ok = True
    for f in files("network/SignalingWsTransport.java"):
        if SKIP in f:
            print("[SKIP] " + f)
            continue
        ok &= patch(f, [(A_WS, NEW_WS, "ws-builder")])
    for f in files("ui/RoomBrowserScreenBase.java"):
        if SKIP in f:
            print("[SKIP] " + f)
            continue
        ok &= patch(f, [(A_FILTER, NEW_FILTER, "filter"),
                        (A_COUNT, NEW_COUNT, "count")])
    print("ALL PASS" if ok else "HAS FAILURES")
    sys.exit(0 if ok else 1)


main()
