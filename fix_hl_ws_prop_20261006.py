# -*- coding: utf-8 -*-
# 修复 prop_hl_visibility 第一版在 27 个 SignalingWsTransport 注入的坏块（v2）。
# 坏块实况（dump 实证）：注释三段/.header(\\"...\\")/.connectTimeout 被 15 空格拼成一行。
# 策略：从 "// 版本头必须随" 起、到 ".connectTimeout(...);" 止整体重建为多行。
import glob
import re
import sys

IO_KW = dict(encoding="utf-8", newline="")

BROKEN = re.compile(
    r"( +)// 版本头必须随 WS 握手带上：.*?\.connectTimeout\(Duration\.ofMillis\(CONNECT_TIMEOUT_MS\)\);"
)

C1 = "// 版本头必须随 WS 握手带上：服务端把 WS 帧按握手请求头克隆后内部分发，"
C2 = "// /room/list 的 HL 展示闸与 /room/join 的 allowHostPeers 都读这个头；"
C3 = "// 缺了即按旧端处理，HL 房在 WS 模式下永远不可见（1.2.0 互通实跑实测）。"
H = '.header("X-VoxLink-Version", VoxLinkMod.MOD_VERSION)'
T = ".connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS));"


def eol_of(s):
    return "\r\n" if "\r\n" in s else "\n"


files = []
for loader in ("fabric", "forge", "neoforge"):
    files += glob.glob(f"{loader}/*/src/main/java/icu/wuhui/voxlink/network/SignalingWsTransport.java")

ok = True
for f in sorted(files):
    if "1.20.2_1.20.6" in f:
        print("[SKIP hand-edited] " + f)
        continue
    s = open(f, **IO_KW).read()
    m = BROKEN.search(s)
    if not m:
        print("[FAIL no-broken-block] " + f)
        ok = False
        continue
    eol = eol_of(s)
    ind = m.group(1)
    fixed = eol.join([ind + C1, ind + C2, ind + C3, ind + H, ind + T])
    s2 = s[: m.start()] + fixed + s[m.end():]
    open(f, "w", **IO_KW).write(s2)
    chk = open(f, **IO_KW).read()
    good = (
        H + eol in chk
        and T in chk
        and C1 + eol in chk
        and '\\"' not in chk
        and chk.count("版本头必须随 WS 握手带上") == 1
    )
    print(("[OK] " if good else "[BAD] ") + f)
    ok &= good

print("ALL PASS" if ok else "HAS FAILURES")
sys.exit(0 if ok else 1)
