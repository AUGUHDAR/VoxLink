#!/usr/bin/env python3
"""
sync_auditfix_20260828.py

Propagate the audit fix from main template fabric/1.20.2_1.20.6 to other
non-26.x templates. Byte-exact copy allowed only when the HEAD version of
the target file is byte-identical to the HEAD version of the master file.

- master source: fabric/1.20.2_1.20.6/src/main/...
- targets: 18 templates (non 26.x)
- compare via `git show HEAD:<path>` SHA-256 hashes (NOT diff, NOT working tree)
- if HEAD hashes match: shutil.copyfile master WORKSPACE file to target
  (preserves bytes incl. line endings, no conversion)
- if HEAD hashes differ: skip and record (out of scope - 26.x era differences)
- per-file result: copied / skipped(HEAD different) / identical(workspace already
  matches master workspace)
- writes report to sync_auditfix_20260828_report.txt
"""

import hashlib
import os
import shutil
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(r"D:\桌面\voxlink")
MASTER = "fabric/1.20.2_1.20.6"
MASTER_SRC = f"{MASTER}/src/main"

# 18 target templates, non-26.x
TARGETS = [
    "fabric/1.20_1.20.1",
    "fabric/1.21.11",
    "fabric/1.21.6_1.21.8",
    "fabric/1.21.9_1.21.10",
    "fabric/1.21_1.21.5",
    "forge/1.20.2_1.20.4",
    "forge/1.20.6",
    "forge/1.20_1.20.1",
    "forge/1.21.11",
    "forge/1.21.6_1.21.8",
    "forge/1.21.9_1.21.10",
    "forge/1.21_1.21.5",
    "neoforge/1.20.1",
    "neoforge/1.20.4_1.20.6",
    "neoforge/1.21.11",
    "neoforge/1.21.6_1.21.8",
    "neoforge/1.21.9_1.21.10",
    "neoforge/1.21_1.21.5",
]

JAVA_FILES = [
    "java/icu/wuhui/voxlink/config/VoxLinkConfig.java",
    "java/icu/wuhui/voxlink/modsync/ModrinthClient.java",
    "java/icu/wuhui/voxlink/modsync/ModSyncManifestService.java",
    "java/icu/wuhui/voxlink/modsync/ModSyncFileHasher.java",
    "java/icu/wuhui/voxlink/modsync/ModSyncGuestService.java",
    "java/icu/wuhui/voxlink/modsync/ModSyncSelectScreen.java",
    "java/icu/wuhui/voxlink/room/ConnectionManager.java",
    "java/icu/wuhui/voxlink/room/RoomManager.java",
    "java/icu/wuhui/voxlink/network/P2PBridge.java",
    "java/icu/wuhui/voxlink/network/ReliableUdpTransport.java",
    "java/icu/wuhui/voxlink/network/SignalingWsTransport.java",
    "java/icu/wuhui/voxlink/network/UdpHolePuncher.java",
    "java/icu/wuhui/voxlink/network/StunProbe.java",
    "java/icu/wuhui/voxlink/network/UPnPManager.java",
    "java/icu/wuhui/voxlink/network/LogUploadManager.java",
    "java/icu/wuhui/voxlink/ui/AttemptingJoinScreen.java",
    "java/icu/wuhui/voxlink/ui/JoinRoomScreen.java",
    "java/icu/wuhui/voxlink/ui/RoomBrowserScreenBase.java",
    "java/icu/wuhui/voxlink/ui/TerracottaConfigScreen.java",
    "java/icu/wuhui/voxlink/ui/VoxLinkScreen.java",
    "java/icu/wuhui/voxlink/terracotta/TerracottaClient.java",
    "java/icu/wuhui/voxlink/terracotta/TerracottaProcess.java",
    "java/icu/wuhui/voxlink/terracotta/TerracottaNodeList.java",
    "java/icu/wuhui/voxlink/terracotta/TerracottaManager.java",
]

LANG_FILES = [
    "resources/assets/voxlink/lang/en_us.json",
    "resources/assets/voxlink/lang/zh_cn.json",
    "resources/assets/voxlink/lang/ar_sa.json",
    "resources/assets/voxlink/lang/de_de.json",
    "resources/assets/voxlink/lang/es_es.json",
    "resources/assets/voxlink/lang/fr_fr.json",
    "resources/assets/voxlink/lang/ja_jp.json",
    "resources/assets/voxlink/lang/ko_kr.json",
    "resources/assets/voxlink/lang/lzh.json",
    "resources/assets/voxlink/lang/pt_br.json",
    "resources/assets/voxlink/lang/ru_ru.json",
    "resources/assets/voxlink/lang/zh_hk.json",
    "resources/assets/voxlink/lang/zh_tw.json",
]

ALL_FILES = JAVA_FILES + LANG_FILES

# Per-class tracking
class_counts = {
    "java": {"copied": 0, "skipped": 0, "identical": 0, "missing_target": 0, "missing_master": 0},
    "lang": {"copied": 0, "skipped": 0, "identical": 0, "missing_target": 0, "missing_master": 0},
}

skipped_records = []  # list of (template, rel_path, reason)
identical_records = []  # list of (template, rel_path)
copied_records = []  # list of (template, rel_path)
missing_master_records = []  # list of (rel_path)


def git_show_blob(repo_root: Path, rel_path: str) -> bytes | None:
    """Return bytes of HEAD:<rel_path> or None if not tracked / missing."""
    spec = f"HEAD:{rel_path}"
    try:
        r = subprocess.run(
            ["git", "show", spec],
            cwd=str(repo_root),
            capture_output=True,
            check=False,
        )
    except FileNotFoundError:
        print("FATAL: git not found in PATH", file=sys.stderr)
        sys.exit(2)

    if r.returncode != 0:
        return None
    return r.stdout


def sha256(b: bytes) -> str:
    return hashlib.sha256(b).hexdigest()


def file_sha256(p: Path) -> str | None:
    if not p.exists():
        return None
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def classify(rel_path: str) -> str:
    return "lang" if rel_path.startswith("resources/") else "java"


def main() -> int:
    os.chdir(str(REPO_ROOT))

    # Pre-check: master workspace files must all exist
    for rel in ALL_FILES:
        wp = REPO_ROOT / MASTER_SRC / rel
        if not wp.exists():
            missing_master_records.append(rel)
            classify_kind = classify(rel)
            class_counts[classify_kind]["missing_master"] += 1
            print(f"WARN: master workspace missing: {MASTER_SRC}/{rel}")

    # For each target × file: compare HEAD bytes
    for tmpl in TARGETS:
        for rel in ALL_FILES:
            target_rel = f"{tmpl}/{MASTER_SRC.replace(MASTER + '/', '')}/{rel}"
            # actually target path = <tmpl>/src/main/<rel>
            target_rel = f"{tmpl}/src/main/{rel}"
            master_rel = f"{MASTER}/src/main/{rel}"

            master_head = git_show_blob(REPO_ROOT, master_rel)
            target_head = git_show_blob(REPO_ROOT, target_rel)

            if master_head is None:
                # master HEAD missing - skip safely
                kind = classify(rel)
                class_counts[kind]["missing_master"] += 1
                skipped_records.append((tmpl, rel, "master HEAD missing"))
                continue
            if target_head is None:
                kind = classify(rel)
                class_counts[kind]["missing_target"] += 1
                skipped_records.append((tmpl, rel, "target HEAD missing (file not tracked)"))
                continue

            # Byte-exact comparison (no whitespace/line-ending normalization)
            if sha256(master_head) != sha256(target_head):
                kind = classify(rel)
                class_counts[kind]["skipped"] += 1
                skipped_records.append((tmpl, rel, "HEAD differs"))
                continue

            # HEADs match: check if workspace already identical
            master_wp = REPO_ROOT / master_rel
            target_wp = REPO_ROOT / target_rel

            if not master_wp.exists():
                kind = classify(rel)
                class_counts[kind]["missing_master"] += 1
                skipped_records.append((tmpl, rel, "master workspace missing"))
                continue

            master_wp_sha = file_sha256(master_wp)
            target_wp_sha = file_sha256(target_wp) if target_wp.exists() else None

            if target_wp_sha == master_wp_sha:
                kind = classify(rel)
                class_counts[kind]["identical"] += 1
                identical_records.append((tmpl, rel))
                continue

            # Copy master workspace -> target workspace (byte-exact)
            target_wp.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(str(master_wp), str(target_wp))
            # Verify post-copy
            post_sha = file_sha256(target_wp)
            if post_sha != master_wp_sha:
                kind = classify(rel)
                class_counts[kind]["skipped"] += 1
                skipped_records.append((tmpl, rel, "post-copy verification failed"))
                continue
            kind = classify(rel)
            class_counts[kind]["copied"] += 1
            copied_records.append((tmpl, rel))

    # Write report
    report_path = REPO_ROOT / "sync_auditfix_20260828_report.txt"
    with open(report_path, "w", encoding="utf-8") as f:
        f.write("sync_auditfix_20260828 - report\n")
        f.write("=" * 60 + "\n")
        f.write(f"Master template: {MASTER}\n")
        f.write(f"Targets ({len(TARGETS)}):\n")
        for t in TARGETS:
            f.write(f"  - {t}\n")
        f.write(f"Java files: {len(JAVA_FILES)}\n")
        f.write(f"Lang files: {len(LANG_FILES)}\n")
        f.write("\n--- Counts by class ---\n")
        for cls, c in class_counts.items():
            f.write(f"{cls}: copied={c['copied']} skipped={c['skipped']} "
                    f"identical={c['identical']} missing_target={c['missing_target']} "
                    f"missing_master={c['missing_master']}\n")
        f.write("\n--- Copied ({} entries) ---\n".format(len(copied_records)))
        for t, r in copied_records:
            f.write(f"  {t}\t{r}\n")
        f.write("\n--- Identical already ({} entries) ---\n".format(len(identical_records)))
        for t, r in identical_records:
            f.write(f"  {t}\t{r}\n")
        f.write("\n--- Skipped ({} entries) ---\n".format(len(skipped_records)))
        for t, r, why in skipped_records:
            f.write(f"  {t}\t{r}\t{why}\n")
        f.write("\n--- Missing master workspace ({} entries) ---\n".format(len(missing_master_records)))
        for r in missing_master_records:
            f.write(f"  {r}\n")

    # Console summary
    print("=" * 60)
    print("SUMMARY")
    print("=" * 60)
    for cls, c in class_counts.items():
        print(f"  {cls}: copied={c['copied']} skipped={c['skipped']} "
              f"identical={c['identical']} missing_target={c['missing_target']} "
              f"missing_master={c['missing_master']}")
    print(f"Total skipped records: {len(skipped_records)}")
    print(f"Total copied records: {len(copied_records)}")
    print(f"Total identical records: {len(identical_records)}")
    print(f"Report written to: {report_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
