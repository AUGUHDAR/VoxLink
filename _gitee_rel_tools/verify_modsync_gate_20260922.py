# -*- coding: utf-8 -*-
"""独立校验：ModSync 门控可见化修复 28 模板全量断言"""
import glob, hashlib, json, os, sys

BASE = r"d:\桌面\voxlink"
fails = []

def check(cond, msg):
    if not cond:
        fails.append(msg)
        print("FAIL:", msg)

# 1) ModSyncGuestService
msg_files = sorted(glob.glob(os.path.join(BASE, "*", "*", "src", "main", "java", "icu", "wuhui", "voxlink", "modsync", "ModSyncGuestService.java")))
check(len(msg_files) == 28, "MSG file count %d" % len(msg_files))
clusters = {}
era = {}
for f in msg_files:
    raw = open(f, "rb").read()
    t = raw.decode("utf-8").replace("\r\n", "\n")
    rel = os.path.relpath(f, BASE)
    # NOT_READY: authoritative/中断/终局 3 处; BYPASSED: 循环头/两处 sleep 3 处; 其余各 1
    for needle, nm, expect in [
        ("public static void pushPendingGateNote()", "pushPendingGateNote", 1),
        ("private enum FetchOutcome", "FetchOutcome", 1),
        ('return new FetchResult(FetchOutcome.UNSUPPORTED, null);', "UNSUPPORTED return", 1),
        ('return new FetchResult(FetchOutcome.EMPTY, null);', "EMPTY return", 1),
        ('return new FetchResult(FetchOutcome.NOT_READY, null);', "NOT_READY return", 3),
        ('return new FetchResult(FetchOutcome.BYPASSED, null);', "BYPASSED return", 3),
        ('return new FetchResult(FetchOutcome.MANIFEST, m);', "MANIFEST return", 1),
        ('ModSyncLog.info("gate: room={} scope={}"', "gate entry log", 1),
        ('ModSyncLog.info("gate skip: {}"', "gate skip log", 1),
        ('ModSyncLog.info("gate: player bypassed', "bypass log", 1),
        ('pendingNoteKey = "voxlink.logui.modsync_unsupported";', "unsupported note", 1),
        ('pendingNoteKey = "voxlink.logui.modsync_fetch_failed";', "fetch_failed note", 1),
    ]:
        check(t.count(needle) == expect, "%s: %s count=%d expect=%d" % (rel, nm, t.count(needle), expect))
    check("if (manifest == null) {" not in t, rel + ": old null-branch still present")
    check('零打扰直通' not in t, rel + ": old javadoc remains")
    # 时代特征必须保留
    has_send = "mc.player.sendSystemMessage(" in t
    has_disp = "mc.player.displayClientMessage(" in t
    has_gui = "mc.gui.screen() != null" in t
    has_old = "mc.screen != null" in t
    era[rel] = (has_send, has_disp, has_gui, has_old)
    clusters.setdefault(hashlib.md5(raw).hexdigest(), []).append(rel)

check(len(clusters) == 3, "MSG clusters after edit = %d (expect 3)" % len(clusters))
for h, lst in clusters.items():
    print("cluster", h[:10], len(lst))

# 时代特征按簇一致性：每簇内完全一致（md5 已保证），跨簇应恰为 3 种形态
forms = set()
for rel, e in era.items():
    forms.add(e)
check(len(forms) == 3, "era forms = %d (expect 3)" % len(forms))
for fm in forms:
    print("era form:", fm)

# 2) AttemptingJoinScreen
ajs_files = sorted(glob.glob(os.path.join(BASE, "*", "*", "src", "main", "java", "icu", "wuhui", "voxlink", "ui", "AttemptingJoinScreen.java")))
check(len(ajs_files) == 28, "AJS file count %d" % len(ajs_files))
for f in ajs_files:
    t = open(f, "rb").read().decode("utf-8").replace("\r\n", "\n")
    rel = os.path.relpath(f, BASE)
    n1 = t.count('VoxLinkMod.LOGGER.info("[ModSync] gate: waiting for scope selection, room={}", this.roomCode);')
    n2 = t.count("icu.wuhui.voxlink.modsync.ModSyncGuestService.pushPendingGateNote();")
    check(n1 == 1, "%s: AJS gate log count=%d" % (rel, n1))
    check(n2 == 1, "%s: AJS drain call count=%d" % (rel, n2))
    check("import icu.wuhui.voxlink.VoxLinkMod;" in t, rel + ": import missing")

# 3) lang：key 存在 + JSON 合法
lang_files = sorted(glob.glob(os.path.join(BASE, "*", "*", "src", "main", "resources", "assets", "voxlink", "lang", "*.json")))
check(len(lang_files) == 364, "lang file count %d" % len(lang_files))
for f in lang_files:
    rel = os.path.relpath(f, BASE)
    try:
        d = json.load(open(f, encoding="utf-8"))
    except Exception as e:
        check(False, "%s: JSON broken: %s" % (rel, e))
        continue
    for k in ("voxlink.logui.modsync_unsupported", "voxlink.logui.modsync_fetch_failed"):
        check(k in d, "%s: missing %s" % (rel, k))
    check(d.get("voxlink.logui.modsync_unsupported"), rel + ": empty unsupported value")
    check(d.get("voxlink.logui.modsync_fetch_failed"), rel + ": empty fetch_failed value")

print()
print("RESULT:", "ALL PASS" if not fails else "%d FAILS" % len(fails))
sys.exit(1 if fails else 0)
