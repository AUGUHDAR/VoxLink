#!/bin/bash
# 部署 server-go 0926k：工单回复幂等闸门 + 消息级撤回 + 版本图去重 + 两条留痕（0926i 内容一并带上）。
exec 2>&1
SN=/www/wwwroot/VoxLink
UP=/root/upgrade_0926k
BIN=voxlink-server-linux-amd64-0926k
TS=$(date +%m%d-%H%M)
WANT_MD5=eeae815625e92ddfbff863568bf2faf8

listener_pid() { ss -tlnp "sport = :1145" 2>/dev/null | grep -oE 'pid=[0-9]+' | head -1 | cut -d= -f2; }
stats() { curl -s -m 5 http://127.0.0.1:1145/api/v1/stats | head -c 170; }
lobby() { curl -s -m 8 'http://127.0.0.1:1145/?route=/room/list&page=1&size=100' | python3 -c "
import sys, json
d = json.load(sys.stdin)['data']
print('total=%s codes=%s' % (d.get('total'), sorted(r.get('code','') for r in d.get('rooms') or [])))
"; }

echo "### 0. 基线 ###"
stats; echo
echo "大厅(前): $(lobby)"
BP=$(grep -c 'panic:' /www/wwwlogs/go/VoxLink.log 2>/dev/null || echo 0)

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
for pat in '/ticket/retract' 'ticket_reply_dedupe_sec' 'drop frame route=' 'createdAt'; do
  printf "  新包含 %-24s -> %s\n" "$pat" "$(grep -ac "$pat" $SN/voxlink-server-linux-amd64)"
done

echo "### 4. 启动 ###"
su -s /bin/bash www -c "cd $SN && nohup ./voxlink-server-linux-amd64 >> /www/wwwlogs/go/VoxLink.log 2>&1 < /dev/null & "
sleep 5
NEW=$(listener_pid)
[ -z "$NEW" ] && { echo "FATAL: 没起来"; tail -20 /www/wwwlogs/go/VoxLink.log; exit 1; }
echo "新 pid=$NEW exe md5=$(md5sum /proc/$NEW/exe | cut -d' ' -f1)"

echo "### 5. 健康 ###"
sleep 8
stats; echo
AP=$(grep -c 'panic:' /www/wwwlogs/go/VoxLink.log 2>/dev/null || echo 0)
echo "panic 前=$BP 后=$AP"
sleep 20
echo "大厅(后): $(lobby)"

echo "### 6. 负向：不存在的工单撤回必须 404 且不写任何东西 ###"
curl -s -m 8 -X POST 'http://127.0.0.1:1145/?route=/ticket/retract' -H 'Content-Type: application/json' \
  -d '{"id":"nosuchid00000000","msg":"aaaa","secret":"x"}' | head -c 160; echo

echo
echo "回滚：cp -a $SN/voxlink-server-linux-amd64.bak-$TS $SN/voxlink-server-linux-amd64 && 重复 2/4 步"
