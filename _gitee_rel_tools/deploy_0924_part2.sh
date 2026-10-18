#!/bin/bash
# deploy_0924 续接（备份已于上轮完成）：停旧进程 → 替换 → 启动 → 验证
exec 2>&1
UP=/root/upgrade_0924
VL=/www/wwwroot/VoxLink
TN=/www/wwwroot/TURN

echo "########## 2. 停止旧进程 ##########"
pkill -f 'voxlink-server-linux-amd64' 2>/dev/null
pkill -f 'voxlink-turn' 2>/dev/null
sleep 2
if pgrep -f 'voxlink-server-linux-amd64|voxlink-turn' >/dev/null; then
  pkill -9 -f 'voxlink-server-linux-amd64' 2>/dev/null
  pkill -9 -f 'voxlink-turn' 2>/dev/null
  sleep 1
fi
if pgrep -f 'voxlink-server-linux-amd64|voxlink-turn' >/dev/null; then
  echo "FATAL: 旧进程未停止，放弃升级（未做任何替换）"
  ps -eo pid,user,args | grep -E 'voxlink-(server|turn)' | grep -v grep
  exit 1
fi
echo "旧进程已全部停止"

echo "########## 3. 替换二进制与配置 ##########"
install -m 755 -o www -g www $UP/voxlink-server-linux-amd64 $VL/voxlink-server-linux-amd64
install -m 755 -o www -g www $UP/voxlink-turn-linux-amd64 $TN/voxlink-turn
sed -i -e '/"heartbeatIntervalSec"/d' -e 's/\("heartbeatUrl":.*\),$/\1/' $TN/config.json
echo "--- 新 config.json ---"
cat $TN/config.json

echo "########## 4. 启动 server ##########"
su -s /bin/bash www -c "cd $VL && nohup ./voxlink-server-linux-amd64 >> /www/wwwlogs/go/VoxLink.log 2>&1 < /dev/null &"
sleep 3
if ! pgrep -f 'voxlink-server-linux-amd64' >/dev/null; then
  echo "FATAL: 新 server 启动失败，回滚旧版"
  tail -20 /www/wwwlogs/go/VoxLink.log
  cp -f $VL/voxlink-server-linux-amd64.bak-0924 $VL/voxlink-server-linux-amd64
  chown www:www $VL/voxlink-server-linux-amd64 && chmod 755 $VL/voxlink-server-linux-amd64
  su -s /bin/bash www -c "cd $VL && nohup ./voxlink-server-linux-amd64 >> /www/wwwlogs/go/VoxLink.log 2>&1 < /dev/null &"
  exit 1
fi
echo "server RUNNING: $(pgrep -f voxlink-server-linux-amd64 | tr '\n' ' ')"

echo "########## 5. 启动 TURN ##########"
su -s /bin/bash www -c "cd $TN && nohup ./voxlink-turn-linux-amd64 >> /www/wwwlogs/go/TURN.log 2>&1 < /dev/null &"
sleep 3
if ! pgrep -f 'voxlink-turn-linux-amd64' >/dev/null; then
  echo "FATAL: 新 TURN 启动失败，回滚旧版"
  tail -20 /www/wwwlogs/go/TURN.log
  cp -f $TN/voxlink-turn.bak-0924 $TN/voxlink-turn
  chown www:www $TN/voxlink-turn && chmod 755 $TN/voxlink-turn
  cp -f $UP/config.json.bak-0924 $TN/config.json
  su -s /bin/bash www -c "cd $TN && nohup ./voxlink-turn-linux-amd64 >> /www/wwwlogs/go/TURN.log 2>&1 < /dev/null &"
  exit 1
fi
echo "turn RUNNING"

echo "########## 6. 验证 ##########"
curl -s -o /dev/null -w "信令 HTTP: %{http_code}\n" http://127.0.0.1:1145/
sleep 6
echo "--- VoxLink.log 最新 12 行 ---"
tail -12 /www/wwwlogs/go/VoxLink.log
echo "--- TURN.log 最新 12 行 ---"
tail -12 /www/wwwlogs/go/TURN.log
echo "--- 进程 ---"
ps -eo pid,user,args | grep -E 'voxlink-(server|turn)' | grep -v grep
echo "--- UDP/TCP 监听 ---"
ss -ulnp 2>/dev/null | grep -E '1145|37000'
ss -tlnp 2>/dev/null | grep -E '1145|37000'
echo "########## 部署完成 ##########"
