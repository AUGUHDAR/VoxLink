# -*- coding: utf-8 -*-
"""独立核对 join 修复在 28 模板的命中率（不信代理自述）"""
import os

BASE = r"D:\桌面\voxlink"
os.chdir(BASE)

roots = []
for loader in ("fabric", "forge", "neoforge"):
    if not os.path.isdir(loader):
        continue
    for d in sorted(os.listdir(loader)):
        p = os.path.join(loader, d)
        if os.path.isfile(os.path.join(p, "src", "main", "java", "icu", "wuhui", "voxlink", "room", "ConnectionManager.java")):
            roots.append(p.replace(os.sep, "/"))

print("cwd =", os.getcwd())
print("模板数 =", len(roots))

markers = {
    "A1 RoomManager 可见性": ("room/RoomManager.java", "   static class TransientException"),
    "A2 warn transient": ("room/RoomManager.java", "[joinRoom] transient failure"),
    "A3 warn rejected": ("room/RoomManager.java", "[joinRoom] join rejected"),
    "B1 重试字段": ("room/ConnectionManager.java", "JOIN_TRANSIENT_MAX_ATTEMPTS = 3"),
    "B2 重试主体": ("room/ConnectionManager.java", "startVoxLinkP2PAttempt"),
    "B3 代际作废": ("room/ConnectionManager.java", "joinAttemptGeneration.incrementAndGet()"),
    "C1 屏幕字段": ("ui/AttemptingJoinScreen.java", "joinNoResponseTicks"),
    "C2 屏幕有界等待": ("ui/AttemptingJoinScreen.java", "join_no_response"),
}

for name, (rel, needle) in markers.items():
    hits, miss = [], []
    for r in roots:
        f = os.path.join(r, "src/main/java/icu/wuhui/voxlink", rel)
        try:
            t = open(f, encoding="utf-8", errors="replace").read()
        except Exception as e:
            miss.append(r + " <读取失败>")
            continue
        if needle in t:
            hits.append(r)
        else:
            miss.append(r)
    line = "%-18s 命中 %2d/%d" % (name, len(hits), len(roots))
    if miss:
        line += "  缺: " + ", ".join(miss)
    print(line)

langs = ["ar_sa", "de_de", "en_us", "es_es", "fr_fr", "ja_jp", "ko_kr",
         "lzh", "pt_br", "ru_ru", "zh_cn", "zh_hk", "zh_tw"]
keys = ["voxlink.logui.join_retry", "voxlink.logui.join_failed", "voxlink.connection.join_no_response"]
total = len(roots) * len(langs)
for k in keys:
    ok = 0
    for r in roots:
        for L in langs:
            f = os.path.join(r, "src/main/resources/assets/voxlink/lang", L + ".json")
            try:
                if k in open(f, encoding="utf-8", errors="replace").read():
                    ok += 1
            except Exception:
                pass
    print("lang %-42s %d/%d" % (k, ok, total))
