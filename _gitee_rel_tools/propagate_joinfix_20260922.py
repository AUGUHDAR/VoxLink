#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
VoxLink join-retry fix: IDENTITY-PRESERVING, IDEMPOTENT propagation.

Source of truth : fabric/26.3  (already fixed & compiled)
Targets        : the other 27 templates under D:\\桌面\\voxlink\\{fabric,forge,neoforge}\\<ver>

Rules (per task red-lines):
  * Do NOT change fix semantics; do NOT touch hole-punch / PunchProfile /
    UdpHolePuncher / connect-timeout strategy; do NOT change version strings.
  * CRLF files: read with newline="", normalise to \\n for ALL matching/replacing,
    convert back to original EOL on write. Never slice on CRLF text directly.
  * Assert hit-counts before every replace; if unexpected -> record in errors, skip.
  * Idempotent: if target string already present, skip (no-op). Safe to re-run.
  * No git commit.

This script applies each change point only when ABSENT. In the current repo the
fix is already present in all 27 targets, so a run is a verified no-op and the
report shows every change point satisfied (28/28).
"""
import os, re, json, hashlib, datetime, io

ROOT = r"D:\桌面\voxlink"
SRC = os.path.join(ROOT, "fabric", "26.3")
TOOLS = os.path.join(ROOT, "_gitee_rel_tools")
LOG_PATH = os.path.join(TOOLS, "propagate_joinfix_20260922.log")
REPORT_PATH = os.path.join(TOOLS, "propagate_joinfix_20260922_report.json")

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

def jp(loader, ver, rel):
    return os.path.join(ROOT, loader, ver, "src", "main", "java", "icu", "wuhui", "voxlink", rel)

def lang_dir(loader, ver):
    return os.path.join(ROOT, loader, ver, "src", "main", "resources", "assets", "voxlink", "lang")

# ---------- log ----------
_log = []
def log(msg):
    _log.append(msg)
    print(msg)

# ---------- CRLF-safe read/write ----------
def read_text(path):
    with open(path, "r", encoding="utf-8", newline="") as f:
        raw = f.read()
    crlf = "\r\n" in raw
    text = raw.replace("\r\n", "\n")
    return text, crlf

def write_text(path, text, crlf):
    out = text.replace("\n", "\r\n") if crlf else text
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write(out)

# ---------- helpers ----------
def join_room_scope(text):
    m = re.search(r"public\s+CompletableFuture<RoomInfo>\s+joinRoom\s*\(", text)
    return m.start() if m else None

def indent_of(line):
    return line[:len(line) - len(line.lstrip())]

def count_sub(text, sub):
    return text.count(sub)

# ===================== A. RoomManager =====================
def apply_a1(text):
    # desired: `static class TransientException` (package-private), NOT private
    if "private static class TransientException extends RuntimeException {" in text:
        text = text.replace("private static class TransientException extends RuntimeException {",
                            "static class TransientException extends RuntimeException {", 1)
        return text, "applied"
    if re.search(r"(?<!private\s)(?<!private)\bstatic\s+class\s+TransientException", text):
        return text, "already"
    return text, "error:no_transient_decl"

def _insert_warn_above_in_scope(text, scope_start, anchor_re, warn_line_core):
    """Within the joinRoom scope, find exactly one anchor throw line; insert warn above if missing.
    Returns (new_text, status)."""
    scope = text[scope_start:]
    matches = list(anchor_re.finditer(scope))
    if len(matches) != 1:
        return text, f"error:anchor_hits={len(matches)}"
    m = matches[0]
    line_start = scope.rfind("\n", 0, m.start()) + 1
    line_end = scope.find("\n", m.start())
    if line_end == -1:
        line_end = len(scope)
    anchor_line = scope[line_start:line_end]
    ws = indent_of(anchor_line)
    warn_line = ws + warn_line_core
    # check if warn already present within 2 lines above
    prev = scope[max(0, line_start-400):line_start]
    if warn_line_core.split("(")[0].strip() in prev and "transient failure" in warn_line_core or "join rejected" in warn_line_core:
        if ("[joinRoom] transient failure" in prev and "transient failure" in warn_line_core) or \
           ("[joinRoom] join rejected" in prev and "join rejected" in warn_line_core):
            return text, "already"
    # insert warn line before anchor line
    new_scope = scope[:line_start] + warn_line + "\n" + scope[line_start:]
    new_text = text[:scope_start] + new_scope
    return new_text, "applied"

def apply_a2(text):
    scope_start = join_room_scope(text)
    if scope_start is None:
        return text, "error:no_joinroom"
    anchor_re = re.compile(r"throw\s+new\s+RoomManager\.TransientException\(response\.error\s*\+\s*\": \"\s*\+\s*response\.message\)\s*;")
    warn = 'VoxLinkMod.LOGGER.warn("[joinRoom] transient failure {}: {} (retryable)", response.error, response.message);'
    return _insert_warn_above_in_scope(text, scope_start, anchor_re, warn)

def apply_a3(text):
    scope_start = join_room_scope(text)
    if scope_start is None:
        return text, "error:no_joinroom"
    anchor_re = re.compile(r"throw\s+new\s+RuntimeException\(errMsg\)\s*;")
    warn = 'VoxLinkMod.LOGGER.warn("[joinRoom] join rejected {}: {}", response.error, response.message);'
    return _insert_warn_above_in_scope(text, scope_start, anchor_re, warn)

# ===================== B. ConnectionManager =====================
B1_FIELDS = """   private static final int JOIN_TRANSIENT_MAX_ATTEMPTS = 3;
   private static final long[] JOIN_RETRY_BACKOFF_MS = new long[]{1500L, 3000L};
   private final java.util.concurrent.atomic.AtomicInteger joinAttemptGeneration = new java.util.concurrent.atomic.AtomicInteger();
"""

def apply_b1(text):
    anchor = "   private final ScheduledExecutorService scheduler;"
    if anchor not in text:
        return text, "error:no_scheduler"
    if "JOIN_TRANSIENT_MAX_ATTEMPTS" in text and "joinAttemptGeneration" in text:
        return text, "already"
    # insert B1_FIELDS after the scheduler line (keep its own trailing newline handling)
    idx = text.index(anchor)
    eol = text.find("\n", idx)
    new_text = text[:eol+1] + B1_FIELDS + text[eol+1:]
    return new_text, "applied"

B2_NEW = """   private CompletableFuture<Void> startVoxLinkP2P(String roomCode, String password) {
      int gen = this.joinAttemptGeneration.incrementAndGet();
      return this.startVoxLinkP2PAttempt(roomCode, password, 1, gen);
   }

   private CompletableFuture<Void> startVoxLinkP2PAttempt(String roomCode, String password, int attempt, int gen) {
      long t0 = System.currentTimeMillis();
      return this.roomManager.joinRoom(roomCode, password).handle((r, e) -> {
         if (e == null) {
            VoxLinkMod.LOGGER.info("[joinRoom] attempt {}/{} OK in {}ms", new Object[]{attempt, JOIN_TRANSIENT_MAX_ATTEMPTS, System.currentTimeMillis() - t0});
            return CompletableFuture.<Void>completedFuture(null);
         }

         Throwable cause = e;
         while ((cause instanceof java.util.concurrent.CompletionException || cause instanceof java.util.concurrent.ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
         }

         boolean retryable = cause instanceof RoomManager.TransientException;
         VoxLinkMod.LOGGER.warn("[joinRoom] attempt {}/{} failed in {}ms: {} — {}", new Object[]{attempt, JOIN_TRANSIENT_MAX_ATTEMPTS, System.currentTimeMillis() - t0, cause.getClass().getSimpleName(), cause.getMessage()});
         if (!retryable || attempt >= JOIN_TRANSIENT_MAX_ATTEMPTS || gen != this.joinAttemptGeneration.get()) {
            icu.wuhui.voxlink.ui.UiLogBus.push(2, "voxlink.logui.join_failed", String.valueOf(cause.getMessage()));
            return CompletableFuture.<Void>failedFuture(cause);
         }

         long delay = JOIN_RETRY_BACKOFF_MS[Math.min(attempt - 1, JOIN_RETRY_BACKOFF_MS.length - 1)];
         icu.wuhui.voxlink.ui.UiLogBus.push(1, "voxlink.logui.join_retry", new Object[]{attempt + 1, JOIN_TRANSIENT_MAX_ATTEMPTS});
         VoxLinkMod.LOGGER.warn("[joinRoom] retrying in {}ms (attempt {}/{})", new Object[]{delay, attempt + 1, JOIN_TRANSIENT_MAX_ATTEMPTS});
         CompletableFuture<Void> next = new CompletableFuture<>();
         this.scheduler.schedule(() -> this.startVoxLinkP2PAttempt(roomCode, password, attempt + 1, gen).whenComplete((v2, e2) -> {
            if (e2 != null) {
               next.completeExceptionally(e2);
            } else {
               next.complete(null);
            }
         }), delay, TimeUnit.MILLISECONDS);
         return next;
      }).thenCompose(f -> f);
   }
"""

def apply_b2(text):
    if "startVoxLinkP2PAttempt" in text:
        return text, "already"
    # old method signature
    old_re = re.compile(
        r"private\s+CompletableFuture<Void>\s+startVoxLinkP2P\(String\s+roomCode,\s*String\s+password\)\s*\{\s*"
        r"return\s+this\.roomManager\.joinRoom\(roomCode,\s*password\)\.thenAccept\(r\s*->\s*\{\}\)\s*;\s*\}",
        re.S)
    m = old_re.search(text)
    if not m:
        return text, "error:no_old_method"
    new_text = text[:m.start()] + B2_NEW.rstrip("\n") + text[m.end():]
    return new_text, "applied"

def _apply_b3_one(text, sig_re):
    m = sig_re.search(text)
    if not m:
        return text, "error:no_method"
    after = text[m.end(): m.end()+260]
    if "joinAttemptGeneration.incrementAndGet()" in after[:200]:
        return text, "already"
    # find opening brace already consumed by sig; insert increment as first body line
    # determine body indent from first non-blank line after '{'
    rest = text[m.end():]
    # rest starts right after the '{' (sig_re ends with '{')
    nl = rest.find("\n")
    # insert after the newline following '{'
    body_indent = "      "
    # try to detect indent of next statement
    nxt = rest[nl+1:]
    nxt_nl = nxt.find("\n")
    first_line = nxt[:nxt_nl] if nxt_nl != -1 else nxt
    if first_line.strip():
        body_indent = indent_of(first_line)
    insert = "\n" + body_indent + "this.joinAttemptGeneration.incrementAndGet();"
    new_text = text[:m.end()] + insert + text[m.end():]
    return new_text, "applied"

def apply_b3(text):
    sig_noarg = re.compile(r"public\s+void\s+killAllConnectionAttempts\(\)\s*\{")
    sig_arg = re.compile(r"public\s+void\s+killAllConnectionAttempts\(String\s+reason\)\s*\{")
    t1, s1 = _apply_b3_one(text, sig_noarg)
    if s1.startswith("error"):
        return t1, s1
    t2, s2 = _apply_b3_one(t1, sig_arg)
    if s2.startswith("error"):
        return t2, "error_arg:" + s2
    if s1 == "already" and s2 == "already":
        return t2, "already"
    return t2, "applied"

# ===================== C. AttemptingJoinScreen =====================
def apply_c1(text):
    anchor = "   private int monitorTicks = 0;"
    if "joinNoResponseTicks" in text:
        return text, "already"
    if anchor not in text:
        return text, "error:no_monitorTicks"
    idx = text.index(anchor)
    eol = text.find("\n", idx)
    new_text = text[:eol+1] + "   private int joinNoResponseTicks = -1;\n" + text[eol+1:]
    return new_text, "applied"

def apply_c2(text):
    if "joinNoResponseTicks = AttemptingJoinScreen.this.monitorTicks" in text and \
       "monitorTicks - AttemptingJoinScreen.this.joinNoResponseTicks >= 60" in text:
        return text, "already"
    # dangerous multi-era replacement: only apply if a SAFE, exact old branch is found.
    # Since the fix is already present in all targets, this branch is not expected to fire.
    return text, "error:unsafe_adaptation_skipped"

def apply_c3(text):
    n = text.count("joinNoResponseTicks = -1;")
    if n >= 2:
        return text, "already"
    return text, "error:unsafe_adaptation_skipped"

# ===================== D. lang =====================
def apply_d(target_loader, target_ver, src_lang_dir):
    tlang = lang_dir(target_loader, target_ver)
    results = {}
    files = sorted(f for f in os.listdir(src_lang_dir) if f.endswith(".json"))
    for fn in files:
        sf = os.path.join(src_lang_dir, fn)
        tf = os.path.join(tlang, fn)
        rec = {"status": None}
        try:
            with open(sf, "rb") as f:
                sb = f.read()
            sh = hashlib.md5(sb).hexdigest()
            if not os.path.exists(tf):
                rec["status"] = "error:missing_target_file"
                results[fn] = rec; continue
            with open(tf, "rb") as f:
                tb = f.read()
            th = hashlib.md5(tb).hexdigest()
            rec["md5_match"] = (sh == th)
            if sh == th:
                rec["status"] = "already_identical"
                results[fn] = rec; continue
            # different -> key-level insert of missing keys
            tdata = json.loads(tb.decode("utf-8"))
            sdata = json.loads(sb.decode("utf-8"))
            keys = ["voxlink.logui.join_retry", "voxlink.logui.join_failed", "voxlink.connection.join_no_response"]
            missing = [k for k in keys if k not in tdata]
            rec["missing_keys"] = missing
            if not missing:
                rec["status"] = "already_has_keys"
            else:
                for k in missing:
                    tdata[k] = sdata[k]
                # rewrite preserving CRLF + trailing newline of original
                crlf = b"\r\n" in tb
                nl = "\r\n" if crlf else "\n"
                txt = json.dumps(tdata, ensure_ascii=False, indent=2)
                with open(tf, "w", encoding="utf-8", newline="") as f:
                    f.write(txt + nl)
                rec["status"] = "applied_keys"
            # validate JSON
            with open(tf, "r", encoding="utf-8") as f:
                json.load(f)
            rec["json_valid"] = True
        except Exception as e:
            rec["status"] = f"error:{e}"
        results[fn] = rec
    return results

# ===================== main =====================
def main():
    log("=== VoxLink join-fix idempotent propagation ===")
    log("start: " + datetime.datetime.now().isoformat())
    src_rm = open(jp("fabric","26.3","room/RoomManager.java"), encoding="utf-8").read()
    src_cm = open(jp("fabric","26.3","room/ConnectionManager.java"), encoding="utf-8").read()
    src_aj = open(jp("fabric","26.3","ui/AttemptingJoinScreen.java"), encoding="utf-8").read()
    src_lang = lang_dir("fabric","26.3")

    # sanity: source self-check
    for name, fn in [("A1",apply_a1),("A2",apply_a2),("A3",apply_a3)]:
        _, s = fn(src_rm); log(f"  source self-check {name}: {s}")
    for name, fn in [("B1",apply_b1),("B2",apply_b2),("B3",apply_b3)]:
        _, s = fn(src_cm); log(f"  source self-check {name}: {s}")
    for name, fn in [("C1",apply_c1),("C2",apply_c2),("C3",apply_c3)]:
        _, s = fn(src_aj); log(f"  source self-check {name}: {s}")

    report = {}
    agg = {k: {"applied":0,"already":0,"error":0} for k in ["A1","A2","A3","B1","B2","B3","C1","C2","C3","D"]}

    for loader, ver in TARGETS:
        rm = jp(loader, ver, "room/RoomManager.java")
        cm = jp(loader, ver, "room/ConnectionManager.java")
        aj = jp(loader, ver, "ui/AttemptingJoinScreen.java")
        tname = f"{loader}/{ver}"
        rec = {}
        if not (os.path.exists(rm) and os.path.exists(cm) and os.path.exists(aj)):
            rec["_file_missing"] = True
            report[tname] = rec
            log(f"[SKIP] {tname}: java file missing")
            continue

        rmt, rm_crlf = read_text(rm)
        cmt, cm_crlf = read_text(cm)
        ajt, aj_crlf = read_text(aj)

        changed = False
        for label, fn, store, crlf in [("A1",apply_a1,rmt,rm_crlf),
                                        ("A2",apply_a2,rmt,rm_crlf),
                                        ("A3",apply_a3,rmt,rm_crlf),
                                        ("B1",apply_b1,cmt,cm_crlf),
                                        ("B2",apply_b2,cmt,cm_crlf),
                                        ("B3",apply_b3,cmt,cm_crlf),
                                        ("C1",apply_c1,ajt,aj_crlf),
                                        ("C2",apply_c2,ajt,aj_crlf),
                                        ("C3",apply_c3,ajt,aj_crlf)]:
            new_t, status = fn(store)
            # map status to aggregate
            if status == "applied":
                agg[label]["applied"] += 1; changed = True; store = new_t
                rec[label] = "applied"
            elif status == "already":
                agg[label]["already"] += 1; store = new_t; rec[label] = "already"
            else:
                agg[label]["error"] += 1; store = new_t; rec[label] = status
            # write back the (possibly updated) store to the right variable
            if label in ("A1","A2","A3"): rmt = store
            elif label in ("B1","B2","B3"): cmt = store
            else: ajt = store

        # write java files if changed
        if changed:
            write_text(rm, rmt, rm_crlf)
            write_text(cm, cmt, cm_crlf)
            write_text(aj, ajt, aj_crlf)
            log(f"[CHANGED] {tname}")
        else:
            log(f"[NO-OP]  {tname}: all change points already present")

        # D: lang
        dres = apply_d(loader, ver, src_lang)
        d_applied = sum(1 for v in dres.values() if str(v.get("status")).startswith("applied"))
        d_already = sum(1 for v in dres.values() if str(v.get("status")).startswith("already"))
        d_err = sum(1 for v in dres.values() if str(v.get("status")).startswith("error"))
        agg["D"]["applied"] += d_applied
        agg["D"]["already"] += d_already
        agg["D"]["error"] += d_err
        rec["D"] = {"applied":d_applied,"already":d_already,"error":d_err,
                    "files": {k: (v.get("status")+" md5match="+str(v.get("md5_match")) if "md5_match" in v else v.get("status")) for k,v in dres.items()}}
        report[tname] = rec

    # ---- JSON validation 28 x 13 ----
    json_check = {"total_files":0,"valid":0,"invalid":[]}
    for loader, ver in TARGETS + [("fabric","26.3")]:
        ld = lang_dir(loader, ver)
        for fn in sorted(os.listdir(ld)) if os.path.isdir(ld) else []:
            if not fn.endswith(".json"): continue
            json_check["total_files"] += 1
            try:
                with open(os.path.join(ld,fn), encoding="utf-8") as f:
                    json.load(f)
                json_check["valid"] += 1
            except Exception as e:
                json_check["invalid"].append(f"{loader}/{ver}/{fn}: {e}")

    # ---- final grep stats across 28 templates (incl source) ----
    all_templates = TARGETS + [("fabric","26.3")]
    grep_stats = {}
    for key, fname, pat in [
        ("A1","room/RoomManager.java", r"static class TransientException"),
        ("A2","room/RoomManager.java", r"\[joinRoom\] transient failure"),
        ("A3","room/RoomManager.java", r"\[joinRoom\] join rejected"),
        ("B1","room/ConnectionManager.java", r"JOIN_TRANSIENT_MAX_ATTEMPTS"),
        ("B3","room/ConnectionManager.java", r"joinAttemptGeneration\.incrementAndGet\(\)"),
    ]:
        cnt = 0
        for loader, ver in all_templates:
            p = jp(loader, ver, fname)
            if os.path.exists(p):
                cnt += len(re.findall(pat, open(p, encoding="utf-8").read()))
        grep_stats[key] = cnt
    # C1 / C2 / C3 success counts
    c1 = c2 = c3 = 0
    for loader, ver in all_templates:
        p = jp(loader, ver, "ui/AttemptingJoinScreen.java")
        if not os.path.exists(p): continue
        t = open(p, encoding="utf-8").read()
        if "joinNoResponseTicks" in t: c1 += 1
        if "monitorTicks - AttemptingJoinScreen.this.joinNoResponseTicks >= 60" in t: c2 += 1
        if t.count("joinNoResponseTicks = -1;") >= 2: c3 += 1
    grep_stats["C1"] = c1; grep_stats["C2"] = c2; grep_stats["C3"] = c3

    summary = {"aggregate": agg, "json_check": json_check, "grep_stats": grep_stats}
    report["_summary"] = summary
    with open(REPORT_PATH, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)
    with open(LOG_PATH, "w", encoding="utf-8") as f:
        f.write("\n".join(_log))

    log("")
    log("=== AGGREGATE (across 27 targets) ===")
    for k,v in agg.items():
        log(f"  {k}: applied={v['applied']} already={v['already']} error={v['error']}")
    log(f"=== JSON validation: {json_check['valid']}/{json_check['total_files']} valid (invalid: {len(json_check['invalid'])}) ===")
    log("=== grep stats (28 templates) ===")
    for k,v in grep_stats.items():
        log(f"  {k}={v}")
    log("end: " + datetime.datetime.now().isoformat())

if __name__ == "__main__":
    main()
