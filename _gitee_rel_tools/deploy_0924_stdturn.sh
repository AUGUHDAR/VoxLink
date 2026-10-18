#!/bin/bash
# 部署标准 TURN（pion v5）双栈节点（2026-09-24 晚，第二弹）
# 前置：scp TURN/GO/voxlink-turn-linux-amd64 → /root/upgrade_0924/voxlink-turn-linux-amd64-stdturn
# 变更：config.json 加 standardTurn 段（含新生成 authSecret）+ ufw 放行 3478 + relay 段
exec 2>&1
TN=/www/wwwroot/TURN
UP=/root/upgrade_0924

echo "########## 0. 现状 ##########"
ls -la $TN/ | grep -E "voxlink-turn|config"

echo "########## 1. 备份 ##########"
cp -a $TN/voxlink-turn $TN/voxlink-turn.bak-0924-stdturn
cp -a $TN/config.json $TN/config.json.bak-0924-stdturn
cp -a $TN/voxlink-turn $UP/voxlink-turn.bak-before-stdturn

echo "########## 2. 停旧进程 ##########"
pkill -f 'voxlink-turn' 2>/dev/null || true
sleep 2
pkill -9 -f 'voxlink-turn' 2>/dev/null || true
sleep 1
pgrep -f 'voxlink-turn' >/dev/null && { echo "FATAL: 未停止"; exit 1; }
echo "已停止"

echo "########## 3. 新配置（保留 nodeKey，加 standardTurn 段）##########"
SECRET=$(openssl rand -hex 32)
cat > $TN/config.json <<EOF
{
  "listenPort": 37000,
  "nodeKey": "5bcec0f7aa39af26a4a3a9935be9ea92b74ef02c768e1da5b5c7d8255cac199e",
  "maxSessions": 50,
  "heartbeatUrl": "http://127.0.0.1:1145/api/v1/relay/node/heartbeat",
  "standardTurn": {
    "enabled": true,
    "port": 3478,
    "publicIp": "162.251.94.31",
    "realm": "voxlink",
    "authSecret": "$SECRET",
    "relayMinPort": 37100,
    "relayMaxPort": 37999,
    "tcp": true,
    "maxAllocations": 50,
    "perUserAllocations": 4
  }
}
EOF
chown www:www $TN/config.json && chmod 600 $TN/config.json
python3 -c "import json; json.load(open('$TN/config.json'))" && echo "JSON 校验通过" || { echo "FATAL: JSON 非法，回滚配置"; cp -a $TN/config.json.bak-0924-stdturn $TN/config.json; exit 1; }

echo "########## 4. 防火墙放行 ##########"
ufw allow 3478/tcp
ufw allow 3478/udp
ufw allow 37100:37999/udp
ufw allow 37100:37999/tcp
ufw status | grep -E '3478|37100' | head -6

echo "########## 5. 装新二进制并启动 ##########"
install -m 755 -o www -g www $UP/voxlink-turn-linux-amd64-stdturn $TN/voxlink-turn
rm -f $TN/voxlink-turn-linux-amd64
ln -s voxlink-turn $TN/voxlink-turn-linux-amd64
chown -h www:www $TN/voxlink-turn-linux-amd64
su -s /bin/bash www -c "cd $TN && nohup ./voxlink-turn-linux-amd64 >> /www/wwwlogs/go/TURN.log 2>&1 < /dev/null &"
sleep 3
if ! pgrep -f 'voxlink-turn-linux-amd64' >/dev/null; then
  echo "启动失败，回滚旧二进制+旧配置"
  cp -a $TN/voxlink-turn.bak-0924-stdturn $TN/voxlink-turn
  cp -a $TN/config.json.bak-0924-stdturn $TN/config.json
  su -s /bin/bash www -c "cd $TN && nohup ./voxlink-turn >> /www/wwwlogs/go/TURN.log 2>&1 < /dev/null &"
  sleep 3
  pgrep -f voxlink-turn >/dev/null && echo "回滚完成，旧版运行中" || echo "FATAL: 回滚也失败"
  tail -15 /www/wwwlogs/go/TURN.log
  exit 1
fi
echo "新 TURN RUNNING"

echo "########## 6. 验证 ##########"
sleep 2
tail -8 /www/wwwlogs/go/TURN.log
echo "--- 监听检查 ---"
ss -ulnp | grep -E ':37000|:3478' || echo "UDP 监听异常!"
ss -tlnp | grep -E ':37000|:3478' || echo "TCP 监听异常!"
echo "--- server 侧节点状态 ---"
tail -3 /www/wwwlogs/go/VoxLink.log

# 回滚：cp -a $TN/voxlink-turn.bak-0924-stdturn $TN/voxlink-turn && cp -a $TN/config.json.bak-0924-stdturn $TN/config.json && 重复 2/5 步
