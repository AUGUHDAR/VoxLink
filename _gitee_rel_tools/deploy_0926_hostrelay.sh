#!/bin/bash
# 部署 0926 第二轮服务端修复（只换 server-go，TURN 节点不动）：
#   1. 房主进玩家中继候选池：host-<房间号> 身份派生（留空或自报都须 hostToken 证明）
#   2. relay_accept 角色改 both；跨房投递把 to=host-<CODE> 落到该房并归一成 "host"
#   3. 候选池按 X-VoxLink-Version>=1.1.9 才下发房主候选（老房主接不住 replyHostId）
#   4. relayReservedIDRe 封 host- 连字符形态，防 peer id 冒充投递键
exec 2>&1
SN=/www/wwwroot/VoxLink
UP=/root/upgrade_0926b
TS=$(date +%m%d-%H%M)

echo "########## 0. 在线快照（部署会瞬断信令） ##########"
curl -s -m 5 http://127.0.0.1:1145/api/v1/stats | head -c 200; echo

echo "########## 1. 备份 ##########"
cp -a $SN/voxlink-server-linux-amd64 $SN/voxlink-server-linux-amd64.bak-$TS
ls -la $SN/voxlink-server-linux-amd64.bak-$TS

echo "########## 2. 停进程 ##########"
pkill -x voxlink-server-l && sleep 2
pgrep -x voxlink-server-l >/dev/null && { pkill -9 -x voxlink-server-l; sleep 1; }
pgrep -x voxlink-server-l >/dev/null && { echo "FATAL: 未停止"; exit 1; }
echo "已停止"

echo "########## 3. 装新二进制 ##########"
install -m 755 -o www -g www $UP/voxlink-server-linux-amd64-hostrelay $SN/voxlink-server-linux-amd64
md5sum $SN/voxlink-server-linux-amd64 $UP/voxlink-server-linux-amd64-hostrelay

echo "########## 4. 启动（优先走宝塔自己的启动方式，保证面板记账一致） ##########"
su -s /bin/bash www -c "cd $SN && nohup ./voxlink-server-linux-amd64 >> /www/wwwlogs/go/VoxLink.log 2>&1 < /dev/null &"
sleep 3
pgrep -x voxlink-server-l >/dev/null || { echo "FATAL: 启动失败"; tail -20 /www/wwwlogs/go/VoxLink.log; exit 1; }
echo "server RUNNING"

echo "########## 5. 验证 ##########"
sleep 4
curl -s -m 5 http://127.0.0.1:1145/api/v1/stats | head -c 160; echo
echo "--- TURN 节点 WS 重连 ---"
tail -20 /www/wwwlogs/go/VoxLink.log | grep -E 'relay-ws' | tail -2
echo "--- 候选池：无 token 自报 host-* 必须 INVALID_FIELDS ---"
curl -s -m 6 -X POST http://127.0.0.1:1145/api/v1/relay/register -H 'Content-Type: application/json' \
  -d '{"clientId":"host-ZZZ111","roomCode":"ZZZ111","natType":"full_cone","mappedIp":"45.67.89.9","mappedPort":25565,"relayEnabled":true}' | head -c 140; echo
echo "--- 候选池：老版本请求方看不到 host 候选（列表应不含 host-） ---"
curl -s -m 6 http://127.0.0.1:1145/api/v1/relay/candidates -H 'X-VoxLink-Version: 1.1.8-26.3' | head -c 200; echo
curl -s -m 6 http://127.0.0.1:1145/api/v1/relay/candidates -H 'X-VoxLink-Version: 1.1.9-26.3' | head -c 200; echo
echo "--- cred 链路与节点 ---"
curl -s -m 6 http://127.0.0.1:1145/api/v1/relay/list | head -c 200; echo

echo ""
echo "回滚：cp -a $SN/voxlink-server-linux-amd64.bak-$TS $SN/voxlink-server-linux-amd64 后重复 2/4 步"
