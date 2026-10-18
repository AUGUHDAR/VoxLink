#!/bin/bash
# 部署 server-go 0926i：纯观测补两条留痕，不改任何判定。
#   1) WS 帧因 inflight 超限被丢弃时打 [ws] drop frame route=... dropped=N（首条+每16条一次）
#   2) [reap] 的 hbAge 不再对 lastHeartbeat=0 的已关房打出 17 亿秒这种无意义值
exec 2>&1
SN=/www/wwwroot/VoxLink
UP=/root/upgrade_0926i
BIN=voxlink-server-linux-amd64-0926i
TS=$(date +%m%d-%H%M)
WANT_MD5=1e083e80b8b78a79a3b0767020ad8826

listener_pid() { ss -tlnp "sport = :1145" 2>/dev/null | grep -oE 'pid=[0-9]+' | head -1 | cut -d= -f2; }
stats() { curl -s -m 5 http://127.0.0.1:1145/api/v1/stats | head -c 170; }

echo "### 0. 基线 ###"
stats; echo
BP=$(grep -c 'panic:' /www/wwwlogs/go/VoxLink.log 2>/dev/null || echo 0)
echo "  panic 基线 = $BP"

echo "### 1. 备份 ###"
cp -a $SN/voxlink-server-linux-amd64 $SN/voxlink-server-linux-amd64.bak-$TS
ls -1 $SN/voxlink-server-linux-amd64.bak-$TS

echo "### 2. 按 PID 停（校验 exe） ###"
PID=$(listener_pid)
if [ -z "$PID" ]; then echo "1145 无人监听"; else
  EXE=$(readlink /proc/$PID/exe 2>/dev/null); echo "监听者 pid=$PID exe=$EXE"
  case "$EXE" in */voxlink-server-linux-amd64*) ;; *) echo "FATAL: 持有者不是本服务"; exit 1 ;; esac
  kill "$PID"; sleep 3
  kill -0 "$PID" 2>/dev/null && { kill -9 "$PID"; sleep 2; }
fi
ss -tlnp "sport = :1145" | grep -q 1145 && { echo "FATAL: 端口仍占用"; exit 1; }
echo "1145 已释放"

echo "### 3. 装包 ###"
install -m 755 -o www -g www $UP/$BIN $SN/voxlink-server-linux-amd64
GOT=$(md5sum $SN/voxlink-server-linux-amd64 | cut -d' ' -f1)
echo "md5 want=$WANT_MD5 got=$GOT"
[ "$GOT" != "$WANT_MD5" ] && { echo "FATAL: 落盘校验不一致"; exit 1; }
printf "  含 drop 留痕 -> %s\n" "$(grep -ac 'drop frame route=' $SN/voxlink-server-linux-amd64)"

echo "### 4. 启动 ###"
su -s /bin/bash www -c "cd $SN && nohup ./voxlink-server-linux-amd64 >> /www/wwwlogs/go/VoxLink.log 2>&1 < /dev/null & "
sleep 5
NEW=$(listener_pid)
[ -z "$NEW" ] && { echo "FATAL: 没起来"; tail -20 /www/wwwlogs/go/VoxLink.log; exit 1; }
echo "新 pid=$NEW exe md5=$(md5sum /proc/$NEW/exe | cut -d' ' -f1)"

echo "### 5. 健康 + 玩家在线核对 ###"
sleep 8
stats; echo
AP=$(grep -c 'panic:' /www/wwwlogs/go/VoxLink.log 2>/dev/null || echo 0)
echo "  panic 前=$BP 后=$AP"
echo "  大厅: $(curl -s -m 8 'http://127.0.0.1:1145/?route=/room/list&page=1&size=100' | python3 -c "
import sys, json
d = json.load(sys.stdin)['data']
print('total=%s codes=%s' % (d.get('total'), sorted(r.get('code','') for r in d.get('rooms') or [])))
")"
echo "  drop 留痕命中(刚起 13s，应为 0 或极少): $(tail -100 /www/wwwlogs/go/VoxLink.log | grep -c 'drop frame route=')"

echo
echo "回滚：cp -a $SN/voxlink-server-linux-amd64.bak-$TS $SN/voxlink-server-linux-amd64 && 重复 2/4 步"
