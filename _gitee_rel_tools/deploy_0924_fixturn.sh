#!/bin/bash
# 修复 TURN 部署：写正确 config（无 trailing comma）+ 换新二进制 + 重启验证
exec 2>&1
UP=/root/upgrade_0924
TN=/www/wwwroot/TURN

echo "########## 1. 写入干净 config ##########"
cat > $TN/config.json <<'EOF'
{
  "listenPort": 37000,
  "nodeKey": "5bcec0f7aa39af26a4a3a9935be9ea92b74ef02c768e1da5b5c7d8255cac199e",
  "maxSessions": 50,
  "heartbeatUrl": "http://127.0.0.1:1145/api/v1/relay/node/heartbeat"
}
EOF
chown www:www $TN/config.json && chmod 640 $TN/config.json
python3 -c "import json;json.load(open('$TN/config.json'));print('config.json 合法')" 2>/dev/null || echo "警告: 无 python3，跳过校验"
cat $TN/config.json

echo "########## 2. 停旧 TURN ##########"
pkill -f 'voxlink-turn' 2>/dev/null
sleep 2
pkill -9 -f 'voxlink-turn' 2>/dev/null
sleep 1
pgrep -f 'voxlink-turn' >/dev/null && { echo "FATAL: 未停止"; exit 1; }
echo "已停止"

echo "########## 3. 换新二进制并启动 ##########"
install -m 755 -o www -g www $UP/voxlink-turn-linux-amd64 $TN/voxlink-turn
su -s /bin/bash www -c "cd $TN && nohup ./voxlink-turn-linux-amd64 >> /www/wwwlogs/go/TURN.log 2>&1 < /dev/null &"
sleep 3
pgrep -f 'voxlink-turn-linux-amd64' >/dev/null || { echo "FATAL: 启动失败"; tail -10 /www/wwwlogs/go/TURN.log; exit 1; }
echo "新 TURN RUNNING"

echo "########## 4. 验证 ##########"
sleep 4
tail -8 /www/wwwlogs/go/TURN.log
echo "--- server 侧节点状态 ---"
tail -4 /www/wwwlogs/go/VoxLink.log
