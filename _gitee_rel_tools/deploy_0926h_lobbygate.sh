#!/bin/bash
# 部署 server-go 0926h：大厅"就绪门"（create 后至少收到一次房主心跳才展示）
# + WS 断连快判阈值 45s→90s（对齐房主侧 8×5s 失败后 30s 探活的最坏节奏）。
# 停进程仍按 :1145 的真实持有者 PID，并先校验 /proc/PID/exe 是本服务。
exec 2>&1
SN=/www/wwwroot/VoxLink
UP=/root/upgrade_0926h
BIN=voxlink-server-linux-amd64-0926h
TS=$(date +%m%d-%H%M)
WANT_MD5=25e0d89060364af21830e6564320c3e5

listener_pid() { ss -tlnp "sport = :1145" 2>/dev/null | grep -oE 'pid=[0-9]+' | head -1 | cut -d= -f2; }
stats() { curl -s -m 5 http://127.0.0.1:1145/api/v1/stats | head -c 170; }
# 大厅条目数与房号清单（就绪门的验收口径：部前部后同一把尺子）
lobby() { curl -s -m 8 'http://127.0.0.1:1145/?route=/room/list&page=1&size=100'; }
lobby_sum() { python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)['data']
except Exception as e:
    print('ERR', e); raise SystemExit
print('total=%s codes=%s' % (d.get('total'), sorted(r.get('code','') for r in d.get('rooms') or [])))
"; }

echo "### 0. 上线前基线 ###"
stats; echo
echo "大厅(前): $(lobby | lobby_sum)"
BEFORE_PANIC=$(grep -c 'panic:' /www/wwwlogs/go/VoxLink.log 2>/dev/null || echo 0)

echo "### 1. 备份 ###"
cp -a $SN/voxlink-server-linux-amd64 $SN/voxlink-server-linux-amd64.bak-$TS
ls -1 $SN/voxlink-server-linux-amd64.bak-$TS

echo "### 2. 按 PID 停（校验 exe） ###"
PID=$(listener_pid)
if [ -z "$PID" ]; then
  echo "1145 无人监听，跳过停止"
else
  EXE=$(readlink /proc/$PID/exe 2>/dev/null)
  echo "监听者 pid=$PID exe=$EXE"
  case "$EXE" in
    */voxlink-server-linux-amd64*) ;;
    *) echo "FATAL: :1145 持有者不是本服务，拒绝 kill"; exit 1 ;;
  esac
  kill "$PID"; sleep 3
  kill -0 "$PID" 2>/dev/null && { echo "仍在，强杀"; kill -9 "$PID"; sleep 2; }
fi
ss -tlnp "sport = :1145" | grep -q 1145 && { echo "FATAL: 端口仍占用"; exit 1; }
echo "1145 已释放"

echo "### 3. 装新二进制 ###"
install -m 755 -o www -g www $UP/$BIN $SN/voxlink-server-linux-amd64
GOT=$(md5sum $SN/voxlink-server-linux-amd64 | cut -d' ' -f1)
echo "md5 want=$WANT_MD5 got=$GOT"
[ "$GOT" != "$WANT_MD5" ] && { echo "FATAL: 落盘校验不一致"; exit 1; }
printf "  含 createdAt 索引字段 -> %s\n" "$(grep -ac 'createdAt' $SN/voxlink-server-linux-amd64)"

echo "### 4. 启动 ###"
su -s /bin/bash www -c "cd $SN && nohup ./voxlink-server-linux-amd64 >> /www/wwwlogs/go/VoxLink.log 2>&1 < /dev/null & "
sleep 5
NEW=$(listener_pid)
[ -z "$NEW" ] && { echo "FATAL: 没起来"; tail -20 /www/wwwlogs/go/VoxLink.log; exit 1; }
RUN=$(md5sum /proc/$NEW/exe | cut -d' ' -f1)
echo "新 pid=$NEW 运行中 exe md5=$RUN"
[ "$RUN" != "$WANT_MD5" ] && echo "WARN: 运行中的 exe 与新包不一致"

echo "### 5. 服务健康 ###"
sleep 3
stats; echo
AFTER_PANIC=$(grep -c 'panic:' /www/wwwlogs/go/VoxLink.log 2>/dev/null || echo 0)
echo "panic 行数 前=$BEFORE_PANIC 后=$AFTER_PANIC"

echo "### 6. 就绪门验收：心跳恢复后大厅应回到同一批房号 ###"
# 重启会掐断 WS，房主 5s 内重发心跳；等 20s 让两轮心跳落地，再比对房号。
sleep 20
echo "大厅(后): $(lobby | lobby_sum)"
sleep 20
echo "大厅(再): $(lobby | lobby_sum)"

echo "### 7. 只读：阈值与埋点生效 ###"
python3 -c "
import json
d=json.load(open('/www/wwwroot/VoxLink/data/data.json'))
print('  ws_gone_close_sec =', d.get('ws_gone_close_sec','未设置(默认90)'))
print('  slow_log_ms       =', d.get('slow_log_ms','未设置(默认2000)'))
"
tail -400 /www/wwwlogs/go/VoxLink.log | grep -c '\[reap\]' | sed 's/^/  近 400 行 [reap] 条数: /'
tail -400 /www/wwwlogs/go/VoxLink.log | grep -c '\[slow\]' | sed 's/^/  近 400 行 [slow] 条数: /'

echo
echo "回滚：cp -a $SN/voxlink-server-linux-amd64.bak-$TS $SN/voxlink-server-linux-amd64 && 重复 2/4 步"
