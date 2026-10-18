#!/usr/bin/env bash
# 编译剩余 19 个未覆盖模板 (并行)
ROOT="D:/桌面/voxlink"
LOGD="$ROOT/_gitee_rel_tools"
TEMPLATES=(
  fabric/1.20.2_1.20.6 fabric/1.20.6 fabric/1.21.11 fabric/1.21.6_1.21.8 fabric/1.21.9_1.21.10 fabric/26.1_26.1.2
  forge/1.20.2_1.20.4 forge/1.20.6 forge/1.21.11 forge/1.21.6_1.21.8 forge/1.21.9_1.21.10 forge/1.21_1.21.5 forge/26.1_26.1.2
  neoforge/1.20.4_1.20.6 neoforge/1.21.11 neoforge/1.21.6_1.21.8 neoforge/1.21.9_1.21.10 neoforge/1.21_1.21.5 neoforge/26.1_26.1.2
)
PIDS=()
for t in "${TEMPLATES[@]}"; do
  LOG="$LOGD/build_$(echo "$t" | tr '/' '_').log"
  (
    cd "$ROOT/$t" && export GRADLE_USER_HOME=D:/.gradle && ./gradlew.bat compileJava > "$LOG" 2>&1
    echo "$t EXIT=$?" >> "$LOGD/compile_rest_summary.txt"
  ) &
  PIDS+=($!)
done
for p in "${PIDS[@]}"; do wait "$p"; done
echo "ALL_DONE" >> "$LOGD/compile_rest_summary.txt"
