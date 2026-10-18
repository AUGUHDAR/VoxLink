#!/usr/bin/env bash
# 本地产物脚本：CLI 版三平台（本机交叉编译）+ UI 版 Windows（本机只能产这一个）。
# UI 版的 Linux/macOS 必须走 CI（需要各平台 CGO 工具链），见 .github/workflows/release.yml。
#
#   bash scripts/build-all.sh [版本号]      # 例：bash scripts/build-all.sh 1.2.0
# 产物落在 dist/，命名与 CI 一致：VoxLink-{UI|CLI}-{os}-{arch}[-vX.Y.Z][.exe]
set -euo pipefail
cd "$(dirname "$0")/.."

VER="${1:-}"
if [ -z "$VER" ]; then
  VER=$(grep -o 'appVersion[[:space:]]*=[[:space:]]*"[^"]*"' app.go | head -1 | sed 's/.*"\(.*\)"/\1/' | sed 's/-beta//')
fi
mkdir -p dist
rm -f dist/VoxLink-* 2>/dev/null || true

cli_ext() { [ "$1" = "windows" ] && echo ".exe" || echo ""; }

mb() { awk -v b="$1" 'BEGIN{printf "%.2f", b/1048576}'; }

echo "== CLI 版（纯 Go，交叉编译）=="
for t in windows/amd64 linux/amd64 linux/arm64 darwin/arm64 darwin/amd64; do
  os=${t%/*}; arch=${t#*/}
  out="dist/VoxLink-CLI-${os}-${arch}-v${VER}$(cli_ext "$os")"
  GOOS=$os GOARCH=$arch CGO_ENABLED=0 go build -trimpath -tags cli -ldflags "-s -w" -o "$out" .
  printf '  %-44s %8s MB\n' "$(basename "$out")" "$(mb "$(stat -c%s "$out")")"
done

if command -v upx >/dev/null 2>&1; then
  echo "== UPX（只压 CLI）=="
  for f in dist/VoxLink-CLI-*; do
    before=$(stat -c%s "$f")
    upx --best --lzma -q "$f"
    printf '  %-44s %6s → %6s MB\n' "$(basename "$f")" "$(mb "$before")" "$(mb "$(stat -c%s "$f")")"
  done
else
  echo "!! 本机没有 upx，跳过压缩（CI 会压；本机 CLI 体积约为 UI 的 63%，达不到 1/2 —— Go 的 TLS 栈下限就在那儿）"
fi

echo "== UI 版（本机仅 Windows）=="
if [ "$(go env GOOS)" = "windows" ]; then
  out="dist/VoxLink-UI-windows-amd64-v${VER}.exe"
  go build -trimpath -tags "desktop,production" -ldflags "-s -w -H windowsgui" -o "$out" .
  printf '  %-44s %8s MB\n' "$(basename "$out")" "$(mb "$(stat -c%s "$out")")"
  if command -v upx >/dev/null 2>&1; then
    echo "   (UI 版刻意不过 UPX：面向玩家双击分发，误报代价太高)"
  fi
else
  echo "  跳过：当前不是 Windows"
fi

echo "== 校验和 =="
( cd dist && sha256sum VoxLink-* > SHA256SUMS-v${VER}.txt && cat SHA256SUMS-v${VER}.txt | head -20 )
echo "完成：$(ls dist | wc -l) 个文件在 dist/"
