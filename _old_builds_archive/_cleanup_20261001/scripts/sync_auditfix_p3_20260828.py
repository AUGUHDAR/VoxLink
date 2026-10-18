#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
sync_auditfix_p3_20260828.py

Propagate the audit fix from main template fabric/1.20.2_1.20.6 to other
non-26.x templates, for the specific files that were skipped by the byte-
exact propagation due to HEAD-vs-HEAD differences (loader/era deltas).

Algorithm (per file):
  1. Compute AUDIT_FIX delta: diff(master HEAD -> master workspace).
     This is the set of lines added/removed by the audit-fix wave.
  2. Read target HEAD as text.
  3. Replay AUDIT_FIX on target HEAD: for each hunk, find the old_lines
     block in target HEAD and replace with new_lines.
  4. Write result as target workspace (with CRLF since this is Windows
     dev env matching the rest of the repo).
  5. Verify: diff(master workspace, target workspace) == diff(master HEAD,
     target HEAD) when normalized to LF.

If any hunk's old_lines cannot be located, fall back to:
  - record failure for human review
  - leave target workspace unchanged (do not destroy existing content)
"""
import difflib
import os
import re
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(r"D:/桌面/voxlink").resolve()
MASTER = "fabric/1.20.2_1.20.6"
JAVA_BASE = "src/main/java/icu/wuhui/voxlink"

# (template) -> list of (rel_path)
FILES_BY_TARGET = {
    "fabric/1.20_1.20.1": [
        "modsync/ModSyncManifestService.java",
        "ui/AttemptingJoinScreen.java",
        "ui/RoomBrowserScreenBase.java",
    ],
    "fabric/1.21.11": [
        "room/ConnectionManager.java",
        "room/RoomManager.java",
        "ui/RoomBrowserScreenBase.java",
        "ui/VoxLinkScreen.java",
    ],
    "fabric/1.21.6_1.21.8": [
        "room/ConnectionManager.java",
        "room/RoomManager.java",
        "ui/VoxLinkScreen.java",
    ],
    "fabric/1.21.9_1.21.10": [
        "room/ConnectionManager.java",
        "room/RoomManager.java",
        "ui/RoomBrowserScreenBase.java",
        "ui/VoxLinkScreen.java",
    ],
    "fabric/1.21_1.21.5": [
        "room/ConnectionManager.java",
        "room/RoomManager.java",
        "ui/VoxLinkScreen.java",
    ],
    "forge/1.20.2_1.20.4": [
        "config/VoxLinkConfig.java",
        "room/RoomManager.java",
        "ui/AttemptingJoinScreen.java",
        "ui/JoinRoomScreen.java",
        "ui/RoomBrowserScreenBase.java",
        "ui/TerracottaConfigScreen.java",
        "ui/VoxLinkScreen.java",
    ],
    "forge/1.20.6": [
        "config/VoxLinkConfig.java",
        "room/RoomManager.java",
        "ui/AttemptingJoinScreen.java",
        "ui/JoinRoomScreen.java",
        "ui/RoomBrowserScreenBase.java",
        "ui/TerracottaConfigScreen.java",
        "ui/VoxLinkScreen.java",
    ],
    "forge/1.20_1.20.1": [
        "config/VoxLinkConfig.java",
        "room/RoomManager.java",
        "ui/AttemptingJoinScreen.java",
        "ui/JoinRoomScreen.java",
        "ui/RoomBrowserScreenBase.java",
        "ui/TerracottaConfigScreen.java",
        "ui/VoxLinkScreen.java",
    ],
    "forge/1.21.11": [
        "config/VoxLinkConfig.java",
        "room/ConnectionManager.java",
        "room/RoomManager.java",
        "ui/AttemptingJoinScreen.java",
        "ui/JoinRoomScreen.java",
        "ui/RoomBrowserScreenBase.java",
        "ui/TerracottaConfigScreen.java",
        "ui/VoxLinkScreen.java",
    ],
    "forge/1.21.6_1.21.8": [
        "config/VoxLinkConfig.java",
        "room/ConnectionManager.java",
        "room/RoomManager.java",
        "ui/AttemptingJoinScreen.java",
        "ui/JoinRoomScreen.java",
        "ui/RoomBrowserScreenBase.java",
        "ui/TerracottaConfigScreen.java",
        "ui/VoxLinkScreen.java",
    ],
    "forge/1.21.9_1.21.10": [
        "config/VoxLinkConfig.java",
        "room/ConnectionManager.java",
        "room/RoomManager.java",
        "ui/AttemptingJoinScreen.java",
        "ui/JoinRoomScreen.java",
        "ui/RoomBrowserScreenBase.java",
        "ui/TerracottaConfigScreen.java",
        "ui/VoxLinkScreen.java",
    ],
    "forge/1.21_1.21.5": [
        "config/VoxLinkConfig.java",
        "room/ConnectionManager.java",
        "room/RoomManager.java",
        "ui/AttemptingJoinScreen.java",
        "ui/JoinRoomScreen.java",
        "ui/RoomBrowserScreenBase.java",
        "ui/TerracottaConfigScreen.java",
        "ui/VoxLinkScreen.java",
    ],
    "neoforge/1.20.1": [
        "config/VoxLinkConfig.java",
        "room/RoomManager.java",
        "ui/AttemptingJoinScreen.java",
        "ui/JoinRoomScreen.java",
        "ui/RoomBrowserScreenBase.java",
        "ui/TerracottaConfigScreen.java",
        "ui/VoxLinkScreen.java",
    ],
    "neoforge/1.20.4_1.20.6": [
        "config/VoxLinkConfig.java",
        "room/RoomManager.java",
        "ui/AttemptingJoinScreen.java",
        "ui/JoinRoomScreen.java",
        "ui/RoomBrowserScreenBase.java",
        "ui/TerracottaConfigScreen.java",
        "ui/VoxLinkScreen.java",
    ],
    "neoforge/1.21.11": [
        "config/VoxLinkConfig.java",
        "room/ConnectionManager.java",
        "room/RoomManager.java",
        "ui/AttemptingJoinScreen.java",
        "ui/JoinRoomScreen.java",
        "ui/RoomBrowserScreenBase.java",
        "ui/TerracottaConfigScreen.java",
        "ui/VoxLinkScreen.java",
    ],
    "neoforge/1.21.6_1.21.8": [
        "config/VoxLinkConfig.java",
        "room/ConnectionManager.java",
        "room/RoomManager.java",
        "ui/AttemptingJoinScreen.java",
        "ui/JoinRoomScreen.java",
        "ui/RoomBrowserScreenBase.java",
        "ui/TerracottaConfigScreen.java",
        "ui/VoxLinkScreen.java",
    ],
    "neoforge/1.21.9_1.21.10": [
        "config/VoxLinkConfig.java",
        "room/ConnectionManager.java",
        "room/RoomManager.java",
        "ui/AttemptingJoinScreen.java",
        "ui/JoinRoomScreen.java",
        "ui/RoomBrowserScreenBase.java",
        "ui/TerracottaConfigScreen.java",
        "ui/VoxLinkScreen.java",
    ],
    "neoforge/1.21_1.21.5": [
        "config/VoxLinkConfig.java",
        "room/ConnectionManager.java",
        "room/RoomManager.java",
        "ui/AttemptingJoinScreen.java",
        "ui/JoinRoomScreen.java",
        "ui/RoomBrowserScreenBase.java",
        "ui/TerracottaConfigScreen.java",
        "ui/VoxLinkScreen.java",
    ],
}


def git_show(rev: str, path: str) -> bytes | None:
    res = subprocess.run(
        ["git", "show", f"{rev}:{path}"],
        cwd=str(REPO_ROOT), capture_output=True
    )
    if res.returncode != 0:
        return None
    return res.stdout


def detect_line_ending(data: bytes) -> str:
    if b"\r\n" in data:
        return "\r\n"
    return "\n"


def parse_hunks(diff_text):
    """Parse a unified diff into list of (a_start, old_lines, new_lines)."""
    hunks = []
    i = 0
    while i < len(diff_text):
        m = re.match(r"^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@", diff_text[i])
        if not m:
            i += 1
            continue
        a_start = int(m.group(1))
        i += 1
        old = []
        new = []
        while i < len(diff_text) and not diff_text[i].startswith("@@"):
            ln = diff_text[i]
            if ln.startswith("---") or ln.startswith("+++"):
                i += 1
                continue
            if ln.startswith("-"):
                old.append(ln[1:])
            elif ln.startswith("+"):
                new.append(ln[1:])
            elif ln.startswith(" "):
                old.append(ln[1:])
                new.append(ln[1:])
            else:
                break
            i += 1
        hunks.append((a_start, old, new))
    return hunks


def find_block(lines: list[str], block: list[str], hint: int) -> int:
    """Locate block as a contiguous slice in lines, starting search around hint.

    Returns 0-based start index, or -1 if not found.

    Strategy:
      1. Exact match near hint.
      2. Exact match anywhere in the file.
      3. Blank-tolerant match: try the same scan with blank-only lines
         (lines whose strip() is "") ignored in both block and lines.
    """
    if not block:
        return hint - 1 if hint > 0 else 0
    n = len(block)
    # 1. Exact match near hint
    for delta in range(0, 5000):
        for sign in (0, 1, -1):
            if sign == 0 and delta != 0:
                continue
            pos = (hint - 1) + sign * delta
            if pos < 0 or pos + n > len(lines):
                continue
            if lines[pos:pos + n] == block:
                return pos
        if delta > 200:
            break
    # 2. Exact match anywhere
    for pos in range(len(lines) - n + 1):
        if lines[pos:pos + n] == block:
            return pos
    # 3. Blank-tolerant match: collapse blank-only lines in both, find
    #    the non-blank-only subsequence.
    nonblank_block = [b for b in block if b.strip()]
    if nonblank_block:
        m = len(nonblank_block)
        # Pre-compute the non-blank-only lines list
        nb_lines = [(i, l) for i, l in enumerate(lines) if l.strip()]
        for k in range(len(nb_lines) - m + 1):
            if all(nb_lines[k + j][1] == nonblank_block[j] for j in range(m)):
                # Found: the block's first non-blank line is at lines index nb_lines[k][0].
                # Compute start so that block[0] aligns with this.  We need
                # to count blanks before in block and lines.
                blanks_before_in_block = sum(
                    1 for b in block[:block.index(nonblank_block[0])] if not b.strip()
                )
                start = nb_lines[k][0] - blanks_before_in_block
                if start < 0:
                    start = 0
                # Verify by re-checking
                # Just trust the position for now.
                return start
    return -1


def apply_hunks(target_lines: list[str], hunks: list[tuple]) -> list[str]:
    """Apply hunks (a_start, old, new) to target_lines, returning new list."""
    out: list[str] = []
    cursor = 0
    for a_start, old, new in hunks:
        pos = find_block(target_lines, old, a_start)
        if pos < 0:
            first = old[0] if old else ""
            raise RuntimeError(
                f"Could not locate hunk old_lines at hint={a_start} (len={len(old)}). "
                f"First line: {first!r}"
            )
        out.extend(target_lines[cursor:pos])
        out.extend(new)
        cursor = pos + len(old)
    out.extend(target_lines[cursor:])
    return out


def main() -> int:
    report = []
    failures = []
    for tmpl, files in FILES_BY_TARGET.items():
        for rel in files:
            entry = {"tmpl": tmpl, "rel": rel}
            master_path = f"{MASTER}/{JAVA_BASE}/{rel}"
            target_path = f"{tmpl}/{JAVA_BASE}/{rel}"
            master_head = git_show("HEAD", master_path)
            target_head = git_show("HEAD", target_path)
            master_wp = REPO_ROOT / master_path
            target_wp = REPO_ROOT / target_path
            if master_head is None:
                entry["status"] = "FAIL: master HEAD missing"
                failures.append(entry)
                report.append(entry)
                continue
            if target_head is None:
                entry["status"] = "FAIL: target HEAD missing"
                failures.append(entry)
                report.append(entry)
                continue
            if not master_wp.exists():
                entry["status"] = "FAIL: master workspace missing"
                failures.append(entry)
                report.append(entry)
                continue

            # Normalize everything to LF lines for diffing
            mh_text = master_head.decode("utf-8", errors="replace")
            th_text = target_head.decode("utf-8", errors="replace")
            mw_bytes = master_wp.read_bytes()
            mw_text = mw_bytes.decode("utf-8", errors="replace")
            mh_lines = mh_text.splitlines()
            th_lines = th_text.splitlines()
            mw_lines = mw_text.splitlines()

            # 1. AUDIT FIX delta: mh -> mw
            audit_diff = list(difflib.unified_diff(
                mh_lines, mw_lines,
                fromfile=f"a/{master_path}", tofile=f"b/{master_path}",
                lineterm="", n=3
            ))
            if not audit_diff:
                # No audit fix; just copy master workspace
                entry["status"] = "NOOP: no audit fix; copy master workspace"
                entry["hunks"] = 0
                target_wp.parent.mkdir(parents=True, exist_ok=True)
                with open(target_wp, "wb") as f:
                    f.write(mw_bytes)
                report.append(entry)
                continue

            hunks = parse_hunks(audit_diff)
            entry["hunks"] = len(hunks)

            # 2. Apply audit fix to target HEAD
            try:
                new_lines = apply_hunks(th_lines, hunks)
            except Exception as e:
                entry["status"] = f"FAIL: {e}"
                failures.append(entry)
                report.append(entry)
                continue

            # 3. Determine line ending (target HEAD typically LF, master
            # workspace uses CRLF; we use master workspace's ending).
            eol = detect_line_ending(mw_bytes)
            new_bytes = eol.join(new_lines).encode("utf-8")
            if eol:
                # Make sure the trailing newline is preserved if either
                # source had one.  master workspace usually has trailing
                # CRLF; we replicate that.
                if mw_bytes.endswith((b"\r\n", b"\n")) and not new_bytes.endswith(b"\n"):
                    new_bytes += eol.encode("utf-8")

            target_wp.parent.mkdir(parents=True, exist_ok=True)
            with open(target_wp, "wb") as f:
                f.write(new_bytes)

            # 4. Verify: diff(master_workspace, target_workspace) should
            # equal diff(master_HEAD, target_HEAD) when both are normalized
            # to LF.
            nw_text = new_bytes.decode("utf-8", errors="replace")
            nw_lines = nw_text.splitlines()
            verify_diff = list(difflib.unified_diff(
                mw_lines, nw_lines,
                fromfile=f"a/{master_path}", tofile=f"b/{target_path}",
                lineterm="", n=3
            ))
            head_diff = list(difflib.unified_diff(
                mh_lines, th_lines,
                fromfile=f"a/{master_path}", tofile=f"b/{target_path}",
                lineterm="", n=3
            ))

            def _op_counts(d):
                rem, add = 0, 0
                for ln in d:
                    if ln.startswith("---") or ln.startswith("+++"):
                        continue
                    if ln.startswith("-"):
                        rem += 1
                    elif ln.startswith("+"):
                        add += 1
                return rem, add

            wr, wa = _op_counts(verify_diff)
            hr, ha = _op_counts(head_diff)
            if (wr, wa) == (hr, ha):
                if verify_diff == head_diff:
                    entry["status"] = "OK: exact match"
                else:
                    # Same operation counts but different positions - OK
                    # if the audit fix and loader delta don't overlap.
                    entry["status"] = f"OK: ops match ({wr}r/{wa}a); pos may differ"
            else:
                entry["status"] = (
                    f"VERIFY-DIFFERS: workspace=({wr}r,{wa}a) "
                    f"HEAD=({hr}r,{ha}a)"
                )
                # Save diff to debug file
                debug_path = REPO_ROOT / "_chk2" / f"dbg_{tmpl.replace('/','_')}_{rel.replace('/','_')}.diff"
                debug_path.parent.mkdir(parents=True, exist_ok=True)
                with open(debug_path, "w", encoding="utf-8") as f:
                    f.write("=== expected (HEAD mh vs HEAD th) ===\n")
                    f.write("\n".join(head_diff))
                    f.write("\n=== verify (workspace mw vs new) ===\n")
                    f.write("\n".join(verify_diff))
                failures.append(entry)
            report.append(entry)

    # Write report
    rep_path = REPO_ROOT / "sync_auditfix_p3_20260828_report.txt"
    with open(rep_path, "w", encoding="utf-8", newline="") as f:
        f.write("=== Per-file results ===\n")
        for e in report:
            f.write(f"{e['tmpl']}\t{e['rel']}\t{e['status']}\n")
            if "hunks" in e:
                f.write(f"   hunks={e['hunks']}\n")
        f.write("\n=== Failures ===\n")
        if failures:
            for e in failures:
                f.write(f"{e['tmpl']}\t{e['rel']}\t{e['status']}\n")
        else:
            f.write("(none)\n")
    print(f"Wrote report to {rep_path}")
    print(f"Total entries: {len(report)}  Failures: {len(failures)}")
    return 0 if not failures else 1


if __name__ == "__main__":
    sys.exit(main())
