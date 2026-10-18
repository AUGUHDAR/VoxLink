#!/bin/bash
# 部署标准 TURN 凭证签发链路：server-go（cred API）+ TURN 节点（派生 secret + stdTurnPort 上报）
# 2026-09-25 凌晨
# 前置：scp 两个新二进制到 /root/upgrade_0924/
exec 2>&1
TN=/www/wwwroot/TURN
SN=/www/wwwroot/VoxLink
UP=/root/upgrade_0924

echo "########## 1. 备份 ##########"
cp -a $SN/voxlink-server-linux-amd64 $SN/voxlink-server-linux-amd64.bak-0925-cred
cp -a $TN/voxlink-turn $TN/voxlink-turn.bak-0925-cred
cp -a $TN/config.json $UP/config.json.bak-0925-cred

echo "########## 2. 停进程 ##########"
pkill -f 'voxlink-turn' 2>/dev/null || true
pkill -f 'voxlink-server-linux-amd64' 2>/dev/null || true
sleep 2
pkill -9 -f 'voxlink-turn' 2>/dev/null || true
pkill -9 -f 'voxlink-server-linux-amd64' 2>/dev/null || true
sleep 1
pgrep -f 'voxlink-turn|voxlink-server' >/dev/null && { echo "FATAL: 未停止"; exit 1; }
echo "已停止"

echo "########## 3. TURN config：删明文 authSecret（改 nodeKey 派生，与 server-go 零配置同步）##########"
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
    "relayMinPort": 37100,
    "relayMaxPort": 37999,
    "tcp": true,
    "maxAllocations": 50,
    "perUserAllocations": 4
  }
}
EOF
chown www:www $TN/config.json && chmod 600 $TN/config.json
python3 -c "import json; json.load(open('$TN/config.json'))" && echo "JSON 校验通过" || { echo "FATAL: JSON 非法"; exit 1; }

echo "########## 4. 装新二进制 ##########"
install -m 755 -o www -g www $UP/voxlink-turn-linux-amd64-cred $TN/voxlink-turn
install -m 755 -o www -g www $UP/voxlink-server-linux-amd64-cred $SN/voxlink-server-linux-amd64

echo "########## 5. 启动（先 server 后 TURN，TURN WS 要连 server）##########"
su -s /bin/bash www -c "cd $SN && nohup ./voxlink-server-linux-amd64 >> /www/wwwlogs/go/VoxLink.log 2>&1 < /dev/null &"
sleep 3
pgrep -f 'voxlink-server-linux-amd64' >/dev/null || { echo "FATAL: server 启动失败"; tail -10 /www/wwwlogs/go/VoxLink.log; exit 1; }
su -s /bin/bash www -c "cd $TN && nohup ./voxlink-turn-linux-amd64 >> /www/wwwlogs/go/TURN.log 2>&1 < /dev/null &"
sleep 3
pgrep -f 'voxlink-turn-linux-amd64' >/dev/null || { echo "FATAL: TURN 启动失败"; tail -10 /www/wwwlogs/go/TURN.log; exit 1; }
echo "双端 RUNNING"

echo "########## 6. 验证 ##########"
sleep 3
echo "--- TURN 日志 ---"
tail -6 /www/wwwlogs/go/TURN.log
echo "--- server 日志（节点接入） ---"
grep -E 'relay-ws|节点接入' /www/wwwlogs/go/VoxLink.log | tail -3
echo "--- cred 路由存在性（无 token 应 403/400 而非 404） ---"
curl -s -X POST http://127.0.0.1:1145/api/v1/relay/stdturn/cred -H 'Content-Type: application/json' -d '{"roomCode":"TEST","clientId":"x","token":"x","nodeId":"turn01"}' | head -3
echo ""
echo "--- 监听检查 ---"
ss -ulnp | grep -E ':37000|:3478' | head -3
ss -tlnp | grep -E ':1145|:37000|:3478' | head -4

# 回滚：恢复两个 .bak-0925-cred 二进制 + $UP/config.json.bak-0925-cred，重复 2/5 步
