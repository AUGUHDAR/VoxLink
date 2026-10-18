#!/bin/bash
# 部署 server-go（0926 第三轮：候选池仅跨房 excludeRoom）。
# 停进程改用"按 :1145 的真实持有者 PID"——上一版脚本里 pkill -x 的模式名有 16 字符，
# Linux 进程名只比对前 15 个字符，导致旧进程根本没被停掉（新实例 bind 失败退出）。
exec 2>&1
SN=/www/wwwroot/VoxLink
UP=/root/upgrade_0926c
TS=$(date +%m%d-%H%M)

listener_pid() { ss -tlnp "sport = :1145" 2>/dev/null | grep -oE 'pid=[0-9]+' | head -1 | cut -d= -f2; }

echo "### 0. 在线快照 ###"
curl -s -m 5 http://127.0.0.1:1145/api/v1/stats | head -c 160; echo

echo "### 1. 备份 ###"
cp -a $SN/voxlink-server-linux-amd64 $SN/voxlink-server-linux-amd64.bak-$TS
ls -la $SN/voxlink-server-linux-amd64.bak-$TS

echo "### 2. 按 PID 停（校验 exe 确实是本服务，防误杀） ###"
PID=$(listener_pid)
if [ -z "$PID" ]; then
  echo "1145 无人监听（可能已自行退出），跳过停止"
else
  EXE=$(readlink /proc/$PID/exe 2>/dev/null)
  echo "监听者 pid=$PID exe=$EXE"
  case "$EXE" in
    */voxlink-server-linux-amd64*) ;;
    *) echo "FATAL: :1145 持有者不是本服务，拒绝 kill"; exit 1 ;;
  esac
  kill "$PID"; sleep 3
  kill -0 "$PID" 2>/dev/null && { echo "还在，强杀"; kill -9 "$PID"; sleep 2; }
fi
ss -tlnp "sport = :1145" | grep -q 1145 && { echo "FATAL: 端口仍被占用"; exit 1; }
echo "1145 已释放"

echo "### 3. 装新二进制 ###"
install -m 755 -o www -g www $UP/voxlink-server-linux-amd64-pool $SN/voxlink-server-linux-amd64
md5sum $SN/voxlink-server-linux-amd64 $UP/voxlink-server-linux-amd64-pool

echo "### 4. 启动 ###"
su -s /bin/bash www -c "cd $SN && nohup ./voxlink-server-linux-amd64 >> /www/wwwlogs/go/VoxLink.log 2>&1 < /dev/null & " 
sleep 4
NEW=$(listener_pid)
[ -z "$NEW" ] && { echo "FATAL: 没起来"; tail -15 /www/wwwlogs/go/VoxLink.log; exit 1; }
echo "新 pid=$NEW  exe_md5=$(md5sum /proc/$NEW/exe | cut -d' ' -f1)"

echo "### 5. 验证 ###"
sleep 3
curl -s -m 5 http://127.0.0.1:1145/api/v1/stats | head -c 140; echo
tail -6 /www/wwwlogs/go/VoxLink.log | grep -E 'relay-ws|已启动' | tail -2
echo "--- excludeRoom 生效（同一池，两种请求对照） ---"
curl -s -m 6 http://127.0.0.1:1145/api/v1/relay/candidates -H 'X-VoxLink-Version: 1.1.9-26.3' | head -c 200; echo
R=$(ls /www/wwwroot/VoxLink/data/relay_peers/*.json 2>/dev/null | head -1 | xargs -r grep -o '"roomCode":"[^"]*"' | cut -d'"' -f4)
echo "拿现有 peer 的房间 [$R] 作为排除项："
curl -s -m 6 "http://127.0.0.1:1145/api/v1/relay/candidates?excludeRoom=$R" -H 'X-VoxLink-Version: 1.1.9-26.3' | head -c 200; echo
echo "--- 冒充仍被拒 ---"
curl -s -m 6 -X POST http://127.0.0.1:1145/api/v1/relay/register -H 'Content-Type: application/json' \
 -d '{"clientId":"host-FAKE1","roomCode":"FAKE1","natType":"full_cone","mappedIp":"45.67.89.9","mappedPort":25565,"relayEnabled":true}' | head -c 120; echo

echo ""
echo "回滚：cp -a $SN/voxlink-server-linux-amd64.bak-$TS $SN/voxlink-server-linux-amd64 && 重复 2/4 步"
