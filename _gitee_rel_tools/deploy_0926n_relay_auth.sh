#!/bin/bash
# 部署 server-go 0926n：#43 两条服务端修复 + 模板渲染错误不再静默。
# ① /relay/release 归属判定：allocate 时把 clientId 记进会话，release 按它判；
#    此前固定拿空串比对，房客释放自己的 TURN 会话必 403，会话槽占到 TTL 才回收。
# ② topology_optimization_done 的收件人从拓扑节点 id 映射成订阅者身份（host / 裸 cid），
#    此前这条信令对全房所有人都不可达。
# ③ renderIndex/renderAdmin 不再吞 Execute 错误（半截页面也会在日志里留 [tpl] 痕）。
exec 2>&1
SN=/www/wwwroot/VoxLink
UP=/root/upgrade_0926n
BIN=voxlink-server-linux-amd64-0926n
TS=$(date +%m%d-%H%M)
WANT_MD5=20d7955129c7d96178aafa42fe87e5e5

listener_pid() { ss -tlnp "sport = :1145" 2>/dev/null | grep -oE 'pid=[0-9]+' | head -1 | cut -d= -f2; }
stats() { curl -s -m 5 http://127.0.0.1:1145/api/v1/stats | head -c 170; }

echo "### 0. 基线 ###"
stats; echo
BP=$(grep -c 'panic:' /www/wwwlogs/go/VoxLink.log 2>/dev/null || echo 0)
echo "  磁盘上工单/反馈目录数（应与部署后一致，一条都不能少）: $(ls -1 $SN/data/tickets 2>/dev/null | wc -l) / $(ls -1 $SN/data/feedback 2>/dev/null | wc -l)"

echo "### 1. 备份 ###"
cp -a $SN/voxlink-server-linux-amd64 $SN/voxlink-server-linux-amd64.bak-$TS
ls -1 $SN/voxlink-server-linux-amd64.bak-$TS

echo "### 2. 按 PID 停（校验 exe） ###"
PID=$(listener_pid)
if [ -z "$PID" ]; then echo "1145 无人监听"; else
  EXE=$(readlink /proc/$PID/exe 2>/dev/null); echo "监听者 pid=$PID exe=$EXE"
  case "$EXE" in */voxlink-server-linux-amd64*) ;; *) echo "FATAL: 持有者不是本服务"; exit 1 ;; esac
  kill "$PID"; sleep 3
  kill -0 "$PID" 2>/dev/null && { kill -9 "$PID"; sleep 2; }
fi
ss -tlnp "sport = :1145" | grep -q 1145 && { echo "FATAL: 端口仍占用"; exit 1; }
echo "1145 已释放"

echo "### 3. 装包 ###"
install -m 755 -o www -g www $UP/$BIN $SN/voxlink-server-linux-amd64
GOT=$(md5sum $SN/voxlink-server-linux-amd64 | cut -d' ' -f1)
echo "md5 want=$WANT_MD5 got=$GOT"
[ "$GOT" != "$WANT_MD5" ] && { echo "FATAL: 落盘校验不一致"; exit 1; }
for pat in 'relayReleaseOwnsSession' 'topoNodeAsPoller' '[tpl]' 'fa-comments-o'; do
  printf "  新包内嵌模板含 %-20s -> %s\n" "$pat" "$(grep -ac "$pat" $SN/voxlink-server-linux-amd64)"
done

echo "### 4. 启动 ###"
su -s /bin/bash www -c "cd $SN && nohup ./voxlink-server-linux-amd64 >> /www/wwwlogs/go/VoxLink.log 2>&1 < /dev/null & "
sleep 5
NEW=$(listener_pid)
[ -z "$NEW" ] && { echo "FATAL: 没起来"; tail -20 /www/wwwlogs/go/VoxLink.log; exit 1; }
echo "新 pid=$NEW exe md5=$(md5sum /proc/$NEW/exe | cut -d' ' -f1)"

echo "### 5. 健康 + 数据未动 ###"
sleep 8
stats; echo
AP=$(grep -c 'panic:' /www/wwwlogs/go/VoxLink.log 2>/dev/null || echo 0)
echo "  panic 前=$BP 后=$AP"
echo "  工单/反馈目录数: $(ls -1 $SN/data/tickets 2>/dev/null | wc -l) / $(ls -1 $SN/data/feedback 2>/dev/null | wc -l)"
tail -6 /www/wwwlogs/go/VoxLink.log

echo
echo "回滚：cp -a $SN/voxlink-server-linux-amd64.bak-$TS $SN/voxlink-server-linux-amd64 && 重复 2/4 步"
echo "验收（要人看一眼）：后台「工单」页刷新后，工单卡下面应重新出现「无法回复的旧反馈 (4)」那张卡，删除按钮回来。"
