#!/bin/bash
# 从 Windows 凭据管理器取出 GitHub / Gitee 令牌，注入环境变量后执行给定命令。
# 令牌仅存在于当前进程环境，不落盘、不打印。
# 用法: bash with_tokens.sh <命令...>
#   例: bash with_tokens.sh node _gitee_rel_tools/gh_release.js --version=1.1.8
GC="git -c credential.helper= -c credential.helper=wincred credential fill"

GH_TOKEN=$(printf 'protocol=https\nhost=github.com\n\n' | GIT_TERMINAL_PROMPT=0 $GC | sed -n 's/^password=//p')
GT_TOKEN=$(printf 'protocol=https\nhost=gitee.com\n\n' | GIT_TERMINAL_PROMPT=0 $GC | sed -n 's/^password=//p')

if [ -z "$GH_TOKEN" ]; then echo "[warn] 未取到 GitHub 令牌" >&2; fi
if [ -z "$GT_TOKEN" ]; then echo "[warn] 未取到 Gitee 令牌" >&2; fi
echo "[tokens] GitHub 长度=${#GH_TOKEN}  Gitee 长度=${#GT_TOKEN}"

export GH_TOKEN GT_TOKEN
exec "$@"
