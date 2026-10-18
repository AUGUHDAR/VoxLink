#!/bin/bash
# 部署 server-go 0926e：后台房间管理页重做（名字泄漏修复 + NAT 归一徽章 + 卡片排版）。
# 停进程仍按 :1145 的真实持有者 PID，并先校验 /proc/PID/exe 是本服务（pkill -x 超 15 字符会静默失配）。
exec 2>&1
SN=/www/wwwroot/VoxLink
UP=/root/upgrade_0926e
BIN=voxlink-server-linux-amd64-0926e
TS=$(date +%m%d-%H%M)
WANT_MD5=1efe039f822bb7749655f29b1124fcad

listener_pid() { ss -tlnp "sport = :1145" 2>/dev/null | grep -oE 'pid=[0-9]+' | head -1 | cut -d= -f2; }
stats() { curl -s -m 5 http://127.0.0.1:1145/api/v1/stats | head -c 170; }

echo "### 0. 上线前快照 ###"
stats; echo

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
tail -200 /www/wwwlogs/go/VoxLink.log | grep -icE "panic|fatal|bind: address" | sed 's/^/近 200 行内 panic\/fatal\/bind 计数: /'

echo "### 6. 新前端资产确实生效（公开静态资源，只读） ###"
CSS=$(curl -s -m 6 "http://127.0.0.1:1145/assets/style.css?v=13")
for pat in "admin-room-facts" "flex-wrap:wrap" "badge-gray"; do
  printf "  %-18s -> %s\n" "$pat" "$(printf '%s' "$CSS" | grep -c "$pat")"
done
printf "  %-18s -> %s\n" "旧 v=11 是否还在被引用" "$(curl -s -m 6 'http://127.0.0.1:1145/?' | grep -c 'style.css?v=11')"

echo "### 7. 未影响在线（只读核对） ###"
python3 -c "
import json,glob
b=0;t=0
for f in glob.glob('/www/wwwroot/VoxLink/data/rooms/rooms/*.json'):
    try: r=json.load(open(f))
    except Exception: continue
    if not r.get('closed'): t+=1
    if r.get('adminNameBlocked'): b+=1
d=json.load(open('/www/wwwroot/VoxLink/data/data.json'))
print('  活跃房 =',t,' adminNameBlocked =',b,' 处置台账 =',len(d.get('blocked_rooms') or {}))
"

echo
echo "回滚：cp -a $SN/voxlink-server-linux-amd64.bak-$TS $SN/voxlink-server-linux-amd64 && 重复 2/4 步"
