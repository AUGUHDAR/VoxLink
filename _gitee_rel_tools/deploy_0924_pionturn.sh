#!/bin/bash
# 部署 pion/turn allocation 模型重写版 TURN 节点（2026-09-24 晚）
# 前置：scp TURN/GO/voxlink-turn-linux-amd64 → /root/upgrade_0924/voxlink-turn-linux-amd64-pion
# 注意：config.json 不动（寿命全是代码常量，无配置项）
exec 2>&1
TN=/www/wwwroot/TURN
UP=/root/upgrade_0924

echo "########## 1. 备份现役二进制 ##########"
cp -a $TN/voxlink-turn $TN/voxlink-turn.bak-0924-pion
cp -a $TN/voxlink-turn $UP/voxlink-turn.bak-before-pion

echo "########## 2. 停旧进程 ##########"
pkill -f 'voxlink-turn' 2>/dev/null || true
sleep 2
pkill -9 -f 'voxlink-turn' 2>/dev/null || true
sleep 1
pgrep -f 'voxlink-turn' >/dev/null && { echo "FATAL: 未停止"; exit 1; }
echo "已停止"

echo "########## 3. 装新二进制并启动 ##########"
install -m 755 -o www -g www $UP/voxlink-turn-linux-amd64-pion $TN/voxlink-turn
su -s /bin/bash www -c "cd $TN && nohup ./voxlink-turn-linux-amd64 >> /www/wwwlogs/go/TURN.log 2>&1 < /dev/null &"
sleep 3
pgrep -f 'voxlink-turn-linux-amd64' >/dev/null || { echo "FATAL: 启动失败"; tail -10 /www/wwwlogs/go/TURN.log; exit 1; }
echo "新 TURN RUNNING"

echo "########## 4. 验证 ##########"
sleep 4
tail -6 /www/wwwlogs/go/TURN.log
echo "--- server 侧节点状态 ---"
tail -3 /www/wwwlogs/go/VoxLink.log
ss -ulnp | grep 37000 || echo "UDP 37000 未监听!"

# 回滚：cp -a $TN/voxlink-turn.bak-0924-pion $TN/voxlink-turn && 重复 2-3 步（装旧二进制）
