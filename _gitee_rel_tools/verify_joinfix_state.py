#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Read-only verification: check whether each of the 27 target templates already
contains every change point of the join-retry fix (source = fabric/26.3).
Does NOT modify any file. Produces a matrix for the team lead to decide.
"""
import os, re, json, hashlib

ROOT = r"D:\桌面\voxlink"
SRC = os.path.join(ROOT, "fabric", "26.3")

# (loader, platform, version) for the 27 targets
TARGETS = [
    ("fabric", "1.20.2_1.20.6"), ("fabric", "1.20_1.20.1"), ("fabric", "1.21.11"),
    ("fabric", "1.21.6_1.21.8"), ("fabric", "1.21.9_1.21.10"), ("fabric", "1.21_1.21.5"),
    ("fabric", "26.1_26.1.2"), ("fabric", "26.2"),
    ("forge", "1.20.2_1.20.4"), ("forge", "1.20.6"), ("forge", "1.20_1.20.1"),
    ("forge", "1.21.11"), ("forge", "1.21.6_1.21.8"), ("forge", "1.21.9_1.21.10"),
    ("forge", "1.21_1.21.5"), ("forge", "26.1_26.1.2"), ("forge", "26.2"), ("forge", "26.3"),
    ("neoforge", "1.20.1"), ("neoforge", "1.20.4_1.20.6"), ("neoforge", "1.21.11"),
    ("neoforge", "1.21.6_1.21.8"), ("neoforge", "1.21.9_1.21.10"), ("neoforge", "1.21_1.21.5"),
    ("neoforge", "26.1_26.1.2"), ("neoforge", "26.2"), ("neoforge", "26.3"),
]

def java_path(loader, ver, rel):
    return os.path.join(ROOT, loader, ver, "src", "main", "java", "icu", "wuhui", "voxlink", rel)

def join_room_scope(text):
    """Return substring from joinRoom method start to EOF (joinRoom is last method
    containing the transient/errMsg throws)."""
    m = re.search(r"public\s+CompletableFuture<RoomInfo>\s+joinRoom\s*\(", text)
    if not m:
        return None
    return text[m.start():]

def check_a1(text):
    # desired: `static class TransientException` (not private)
    has_private_static = "private static class TransientException" in text
    has_static = re.search(r"(?<!private )\bstatic\s+class\s+TransientException", text) is not None
    if has_private_static:
        return "HAS_PRIVATE_STATIC"  # needs fix
    if has_static:
        return "OK"
    return "MISSING"

def check_a2(text):
    scope = join_room_scope(text)
    if scope is None:
        return "NO_JOINROOM"
    # within joinRoom, count transient throw
    n_throw = len(re.findall(r"throw\s+new\s+RoomManager\.TransientException\(response\.error\s*\+\s*\": \"\s*\+\s*response\.message\)\s*;", scope))
    n_warn = scope.count('[joinRoom] transient failure')
    if n_throw == 1 and n_warn >= 1:
        return "OK"
    return f"throw={n_throw} warn={n_warn}"

def check_a3(text):
    scope = join_room_scope(text)
    if scope is None:
        return "NO_JOINROOM"
    n_throw = len(re.findall(r"throw\s+new\s+RuntimeException\(errMsg\)\s*;", scope))
    n_warn = scope.count('[joinRoom] join rejected')
    if n_throw == 1 and n_warn >= 1:
        return "OK"
    return f"throw={n_throw} warn={n_warn}"

def check_b1(text):
    if "private final ScheduledExecutorService scheduler;" not in text:
        return "NO_SCHEDULER"
    if "JOIN_TRANSIENT_MAX_ATTEMPTS" in text and "JOIN_RETRY_BACKOFF_MS" in text and "joinAttemptGeneration" in text:
        return "OK"
    return "MISSING_FIELDS"

def check_b2(text):
    if "startVoxLinkP2PAttempt" in text:
        return "OK"
    # old form present?
    if re.search(r"private\s+CompletableFuture<Void>\s+startVoxLinkP2P\([^)]*\)\s*\{\s*return\s+this\.roomManager\.joinRoom\([^)]*\)\.thenAccept\(r\s*->\s*\{\}\)\s*;\s*\}", text, re.S):
        return "OLD_PRESENT_NEEDS_FIX"
    return "UNKNOWN"

def check_b3(text):
    # both killAllConnectionAttempts overloads must have the increment as first body stmt
    noarg = re.search(r"public\s+void\s+killAllConnectionAttempts\(\)\s*\{", text)
    witharg = re.search(r"public\s+void\s+killAllConnectionAttempts\(String\s+reason\)\s*\{", text)
    if not noarg or not witharg:
        return "MISSING_METHOD"
    # check increment appears within first 3 lines after each '{'
    def first_body_has_inc(m):
        after = text[m.end(): m.end()+200]
        # find the next non-empty statement region
        return "joinAttemptGeneration.incrementAndGet()" in after[:200]
    ok = first_body_has_inc(noarg) and first_body_has_inc(witharg)
    return "OK" if ok else "MISSING_INCREMENT"

def check_c1(text):
    if "joinNoResponseTicks" in text:
        return "OK"
    return "MISSING"

def check_c2(text):
    # bounded wait signature
    if "joinNoResponseTicks = AttemptingJoinScreen.this.monitorTicks" in text and "monitorTicks - AttemptingJoinScreen.this.joinNoResponseTicks >= 60" in text:
        return "OK"
    return "MISSING"

def check_c3(text):
    n = text.count("joinNoResponseTicks = -1;")
    if n >= 2:
        return "OK"
    return f"count={n}"

def check_d(lang_target_dir, lang_src_dir):
    keys = ["voxlink.logui.join_retry", "voxlink.logui.join_failed", "voxlink.connection.join_no_response"]
    results = {}
    files = [f for f in os.listdir(lang_src_dir) if f.endswith(".json")]
    for fn in files:
        sf = os.path.join(lang_src_dir, fn)
        tf = os.path.join(lang_target_dir, fn)
        if not os.path.exists(tf):
            results[fn] = "MISSING_FILE"
            continue
        try:
            with open(sf, "rb") as f:
                sh = hashlib.md5(f.read()).hexdigest()
            with open(tf, "rb") as f:
                th = hashlib.md5(f.read()).hexdigest()
        except Exception as e:
            results[fn] = f"ERR {e}"
            continue
        if sh == th:
            results[fn] = "OK_IDENTICAL"
            continue
        # different -> check keys
        try:
            with open(tf, "r", encoding="utf-8") as f:
                data = json.load(f)
            missing = [k for k in keys if k not in data]
            results[fn] = "OK_KEYS" if not missing else f"MISSING_KEYS:{missing}"
        except Exception as e:
            results[fn] = f"JSON_ERR {e}"
    return results

def main():
    src_rm = open(java_path("fabric","26.3","room/RoomManager.java"), encoding="utf-8").read()
    src_cm = open(java_path("fabric","26.3","room/ConnectionManager.java"), encoding="utf-8").read()
    src_aj = open(java_path("fabric","26.3","ui/AttemptingJoinScreen.java"), encoding="utf-8").read()
    src_lang = os.path.join(ROOT, "fabric", "26.3", "src", "main", "resources", "assets", "voxlink", "lang")

    print("SOURCE self-check:")
    print("  A1", check_a1(src_rm), "| A2", check_a2(src_rm), "| A3", check_a3(src_rm))
    print("  B1", check_b1(src_cm), "| B2", check_b2(src_cm), "| B3", check_b3(src_cm))
    print("  C1", check_c1(src_aj), "| C2", check_c2(src_aj), "| C3", check_c3(src_aj))
    print()

    header = f"{'TEMPLATE':<34} {'A1':<8} {'A2':<10} {'A3':<10} {'B1':<8} {'B2':<12} {'B3':<10} {'C1':<5} {'C2':<8} {'C3':<8}"
    print(header)
    summary = {}
    for loader, ver in TARGETS:
        if ver == "26.3_fabric_src_placeholder":
            continue
        rm = java_path(loader, ver, "room/RoomManager.java")
        cm = java_path(loader, ver, "room/ConnectionManager.java")
        aj = java_path(loader, ver, "ui/AttemptingJoinScreen.java")
        langd = os.path.join(ROOT, loader, ver, "src", "main", "resources", "assets", "voxlink", "lang")
        tname = f"{loader}/{ver}"
        if not (os.path.exists(rm) and os.path.exists(cm) and os.path.exists(aj)):
            print(f"{tname:<34} FILE_MISSING")
            summary[tname] = "FILE_MISSING"
            continue
        rmt = open(rm, encoding="utf-8").read()
        cmt = open(cm, encoding="utf-8").read()
        ajt = open(aj, encoding="utf-8").read()
        a1 = check_a1(rmt); a2 = check_a2(rmt); a3 = check_a3(rmt)
        b1 = check_b1(cmt); b2 = check_b2(cmt); b3 = check_b3(cmt)
        c1 = check_c1(ajt); c2 = check_c2(ajt); c3 = check_c3(ajt)
        dres = check_d(langd, src_lang)
        d_ok = all(v.startswith("OK") for v in dres.values())
        d_bad = [f"{k}:{v}" for k,v in dres.items() if not v.startswith("OK")]
        print(f"{tname:<34} {a1:<8} {a2:<10} {a3:<10} {b1:<8} {b2:<12} {b3:<10} {c1:<5} {c2:<8} {c3:<8}  D:{'OK' if d_ok else 'BAD('+str(len(d_bad))+')'}")
        summary[tname] = dict(A1=a1,A2=a2,A3=a3,B1=b1,B2=b2,B3=b3,C1=c1,C2=c2,C3=c3,D=dres)
    # write json
    with open(os.path.join(ROOT, "_gitee_rel_tools", "verify_joinfix_state.json"), "w", encoding="utf-8") as f:
        json.dump(summary, f, ensure_ascii=False, indent=2)
    print("\nWrote _gitee_rel_tools/verify_joinfix_state.json")

if __name__ == "__main__":
    main()
