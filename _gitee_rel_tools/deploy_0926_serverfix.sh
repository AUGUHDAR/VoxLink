#!/bin/bash
# 部署 0926 服务端修复（只换 server-go，TURN 节点不动）：
#   1. /relay/stdturn/cred 允许房主无 clientId 申请凭证（修标准中继 std_cred_failed）
#   2. /relay/register 限频键 ip -> ip|clientId（避免 CGNAT 同出口 IP 互相饿死）
#   3. 信令越权矩阵修正 + 高危类型强制 enforce
#   4. ticket/multipart 上传体积豁免 + 工单 client 白名单
exec 2>&1
SN=/www/wwwroot/VoxLink
UP=/root/upgrade_0926
TS=$(date +%m%d-%H%M)

echo "########## 0. 在线房间快照（部署会瞬断信令） ##########"
curl -s -m 5 http://127.0.0.1:1145/api/v1/stats | head -c 200; echo

echo "########## 1. 备份 ##########"
cp -a $SN/voxlink-server-linux-amd64 $SN/voxlink-server-linux-amd64.bak-$TS
ls -la $SN/voxlink-server-linux-amd64.bak-$TS

echo "########## 2. 停进程 ##########"
pkill -f 'voxlink-server-linux-amd64' 2>/dev/null || true
sleep 2
pkill -9 -f 'voxlink-server-linux-amd64' 2>/dev/null || true
sleep 1
pgrep -f 'voxlink-server-linux-amd64' >/dev/null && { echo "FATAL: 未停止"; exit 1; }
echo "已停止"

echo "########## 3. 装新二进制 ##########"
install -m 755 -o www -g www $UP/voxlink-server-linux-amd64-0926 $SN/voxlink-server-linux-amd64
md5sum $SN/voxlink-server-linux-amd64 $UP/voxlink-server-linux-amd64-0926

echo "########## 4. 启动（TURN 节点会自动重连 WS） ##########"
su -s /bin/bash www -c "cd $SN && nohup ./voxlink-server-linux-amd64 >> /www/wwwlogs/go/VoxLink.log 2>&1 < /dev/null &"
sleep 3
pgrep -f 'voxlink-server-linux-amd64' >/dev/null || { echo "FATAL: 启动失败"; tail -20 /www/wwwlogs/go/VoxLink.log; exit 1; }
echo "server RUNNING"

echo "########## 5. 验证 ##########"
sleep 4
echo "--- 健康 ---"
curl -s -m 5 http://127.0.0.1:1145/api/v1/stats | head -c 200; echo
echo "--- TURN 节点重连 ---"
tail -12 /www/wwwlogs/go/VoxLink.log | grep -Ei 'relay|节点|start|listen' || tail -4 /www/wwwlogs/go/VoxLink.log
echo "--- relay/list 应含 stdTurnPort ---"
curl -s -m 5 "http://127.0.0.1:1145/api/v1/relay/list" | head -c 300; echo
echo "--- cred 路由：缺 token 必须拒（不能 404/放行） ---"
curl -s -m 5 -X POST http://127.0.0.1:1145/api/v1/relay/stdturn/cred -H 'Content-Type: application/json' -d '{"roomCode":"NOPE","clientId":"","token":"bad","nodeId":"turn01"}' | head -c 200; echo
echo "--- 监听 ---"
ss -tlnp | grep ':1145' | head -2

echo ""
echo "回滚：cp -a $SN/voxlink-server-linux-amd64.bak-$TS $SN/voxlink-server-linux-amd64 后重复 2/4 步"
