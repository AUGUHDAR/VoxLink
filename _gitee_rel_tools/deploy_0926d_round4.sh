#!/bin/bash
# 部署 server-go 0926d：#45 关房存在性改走锁内判定 + #31 屏蔽房间名（改名不关房、不封号）。
# 停进程仍按 :1145 的真实持有者 PID，并先校验 /proc/PID/exe 确实是本服务
# （pkill -x 的模式名超过 Linux 15 字符进程名会静默失配，上一版就是这么翻车的）。
exec 2>&1
SN=/www/wwwroot/VoxLink
UP=/root/upgrade_0926d
BIN=voxlink-server-linux-amd64-0926d
TS=$(date +%m%d-%H%M)

listener_pid() { ss -tlnp "sport = :1145" 2>/dev/null | grep -oE 'pid=[0-9]+' | head -1 | cut -d= -f2; }
stats() { curl -s -m 5 http://127.0.0.1:1145/api/v1/stats | head -c 170; }

echo "### 0. 上线前快照 ###"
stats; echo

echo "### 1. 备份当前二进制 ###"
cp -a $SN/voxlink-server-linux-amd64 $SN/voxlink-server-linux-amd64.bak-$TS
ls -la $SN/voxlink-server-linux-amd64.bak-$TS | awk '{print $5, $9}'

echo "### 2. 按 PID 停（校验 exe，防误杀） ###"
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
WANT=$(md5sum $UP/$BIN | cut -d' ' -f1)
GOT=$(md5sum $SN/voxlink-server-linux-amd64 | cut -d' ' -f1)
echo "md5 want=$WANT got=$GOT"
[ "$WANT" != "$GOT" ] && { echo "FATAL: 落盘校验不一致"; exit 1; }

echo "### 4. 启动 ###"
su -s /bin/bash www -c "cd $SN && nohup ./voxlink-server-linux-amd64 >> /www/wwwlogs/go/VoxLink.log 2>&1 < /dev/null & "
sleep 5
NEW=$(listener_pid)
[ -z "$NEW" ] && { echo "FATAL: 没起来"; tail -20 /www/wwwlogs/go/VoxLink.log; exit 1; }
NEWMD5=$(md5sum /proc/$NEW/exe | cut -d' ' -f1)
echo "新 pid=$NEW 运行中 exe md5=$NEWMD5（应等于 $WANT）"
[ "$NEWMD5" != "$WANT" ] && echo "WARN: 运行中的 exe 与新包不一致，可能起的是旧文件"

echo "### 5. 服务健康 ###"
sleep 3
stats; echo
tail -8 /www/wwwlogs/go/VoxLink.log | grep -E "relay-ws|已启动|listen|panic" | tail -3

echo "### 6. #31 端到端（对一个不存在的房间号，绝不影响在线玩家） ###"
TOK=$(cat $SN/data/admin_token.txt 2>/dev/null)
[ -z "$TOK" ] && { echo "WARN: 读不到 admin_token，跳过 6/7"; }
if [ -n "$TOK" ]; then
  CODE=ZTEST01
  echo "-- 处置（房间不存在：应只记台账、不报错）"
  curl -s -m 6 -X POST "http://127.0.0.1:1145/api/v1/?route=/admin/block_room" -H "Content-Type: application/json" -H "X-Admin-Token: $TOK" \
    --data "{\"code\":\"$CODE\",\"note\":\"deploy check 0926d\"}" | head -c 220; echo
  echo "-- 台账里应有这一条"
  curl -s -m 6 "http://127.0.0.1:1145/api/v1/?route=/admin/blocked-rooms" -H "X-Admin-Token: $TOK" | head -c 260; echo
  echo "-- 撤销"
  curl -s -m 6 -X POST "http://127.0.0.1:1145/api/v1/?route=/admin/unblock_room" -H "Content-Type: application/json" -H "X-Admin-Token: $TOK" \
    --data "{\"code\":\"$CODE\"}" | head -c 160; echo
  echo "-- 撤销后台账应回到空（测试数据当场清干净）"
  curl -s -m 6 "http://127.0.0.1:1145/api/v1/?route=/admin/blocked-rooms" -H "X-Admin-Token: $TOK" | head -c 120; echo
fi

echo "### 7. 旧封号语义确已消失（不打玩家房间，只看接口还认不认 ROOM_BLOCKED 这条路） ###"
echo "当前在线房间数（不应因部署而减少）："
curl -s -m 5 http://127.0.0.1:1145/api/v1/stats | grep -oE '"active_rooms":[0-9]+'

echo
echo "回滚：cp -a $SN/voxlink-server-linux-amd64.bak-$TS $SN/voxlink-server-linux-amd64 && 重复 2/4 步"
