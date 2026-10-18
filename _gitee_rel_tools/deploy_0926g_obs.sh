#!/bin/bash
# 部署 server-go 0926g：只加观测，不改行为。
#   - [slow]  路由耗时（HTTP 与 WS 共用分发点，默认阈值 2000ms，datastore: slow_log_ms）
#   - [reap]  房间回收判据（reason / 各 age / 房客数）
#   - ws_gone_close_sec 可调（默认 45，与旧行为恒等）
# 停进程仍按 :1145 的真实持有者 PID，并先校验 /proc/PID/exe 是本服务（pkill -x 超 15 字符会静默失配）。
exec 2>&1
SN=/www/wwwroot/VoxLink
UP=/root/upgrade_0926g
BIN=voxlink-server-linux-amd64-0926g
TS=$(date +%m%d-%H%M)
WANT_MD5=b26faba672f0c3713d67268c19d4ff75

listener_pid() { ss -tlnp "sport = :1145" 2>/dev/null | grep -oE 'pid=[0-9]+' | head -1 | cut -d= -f2; }
stats() { curl -s -m 5 http://127.0.0.1:1145/api/v1/stats | head -c 170; }

echo "### 0. 上线前快照 ###"
stats; echo
BEFORE_PANIC=$(grep -c 'panic:' /www/wwwlogs/go/VoxLink.log 2>/dev/null || echo 0)
echo "日志累计 panic 行数(基线) = $BEFORE_PANIC"

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
[ "$GOT" != "$WANT_MD5" ] && { echo "FATAL: 落盘校验不一致，回滚前先停手"; exit 1; }
for pat in '\[slow\] route=' '\[reap\] code=' 'ws_gone_close_sec' 'slow_log_ms'; do
  printf "  新包含埋点 %-22s -> %s\n" "$pat" "$(grep -ac "$pat" $SN/voxlink-server-linux-amd64)"
done

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
tail -200 /www/wwwlogs/go/VoxLink.log | grep -icE "panic|fatal|bind: address" | sed 's/^/近 200 行内 panic\/fatal\/bind 计数: /'

echo "### 6. 只读：玩家状态未受影响 ###"
python3 -c "
import json,glob
act=0; blocked=0
for f in glob.glob('/www/wwwroot/VoxLink/data/rooms/rooms/*.json'):
    try: r=json.load(open(f))
    except Exception: continue
    if not r.get('closed'): act+=1
    if r.get('adminNameBlocked'): blocked+=1
d=json.load(open('/www/wwwroot/VoxLink/data/data.json'))
print('  活跃房 =',act,' adminNameBlocked =',blocked,' 处置台账 =',len(d.get('blocked_rooms') or {}))
print('  slow_log_ms =',d.get('slow_log_ms','未设置(默认2000)'),' ws_gone_close_sec =',d.get('ws_gone_close_sec','未设置(默认45)'))
"

echo
echo "回滚：cp -a $SN/voxlink-server-linux-amd64.bak-$TS $SN/voxlink-server-linux-amd64 && 重复 2/4 步"
