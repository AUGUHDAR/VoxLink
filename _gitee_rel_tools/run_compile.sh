#!/bin/bash
# Compile helper for the VoxLink join-fix verification matrix.
# Usage: bash run_compile.sh <loader/version>
set -u
TMPL="$1"
ROOT="D:/桌面/voxlink"
LOG="$ROOT/_gitee_rel_tools/compile_${TMPL//\//_}.log"
mkdir -p "$ROOT/_gitee_rel_tools"
cd "$ROOT/$TMPL" || { echo "CD_FAIL: cannot cd to $ROOT/$TMPL" >> "$LOG"; exit 2; }
export GRADLE_USER_HOME=D:/.gradle
echo "=== compile start $(date) ===" >> "$LOG"
./gradlew.bat compileJava >> "$LOG" 2>&1
RC=$?
echo "=== compile end $(date) rc=$RC ===" >> "$LOG"
exit $RC
