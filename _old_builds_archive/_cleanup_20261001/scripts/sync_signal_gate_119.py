"""1.1.9 安全批量：给 28 个模板的 RoomManager.handleSignal 插入"信令来源门控"。

与 server-go/signal_auth.go 的服务端矩阵配套的第二道防线。锚点单一（debug 日志行 +
switch (type)），逐模板断言命中次数，任何未命中的模板都显式报出而不是静默跳过。
"""
import glob, io, sys

GATE = '''         // 信令来源门控（1.1.9，与 server-go/signal_auth.go 的服务端矩阵配套的第二道防线）：
         // 服务端把 from 绑成 token 推导出的真实身份 —— 房主发出的信令 from 恒为 "host"、
         // 房客恒为其 clientId、服务端注入恒为 "server"，三者客户端都无法伪造。
         // 修复前同房间任一成员发 host_closing 即可让全房（含房主）拆线；发
         // turn_alloc / relay_setup / peer_port / tcp_punch_info 即可把本端出连目标
         // 改写成它指定的地址，再把承载 MC 流量的隧道桥过去。
         RoomManager.RoomState sigState = this.currentRoom.get();
         if (sigState != null && sigState != PENDING) {
            boolean fromOk = "host".equals(from) || "server".equals(from);
            if (!fromOk && sigState.roomInfo.isHost()) {
               // 房主侧：新房客由 join_request 引入（此时尚未登记），其余必须是自家在册房客
               fromOk = "join_request".equals(type) || sigState.roomInfo.getPeer(from) != null;
            }
            if (!fromOk) {
               VoxLinkMod.LOGGER.warn("[Security] Discarded signal from unexpected source: type={}, from={}", type, from);
               return;
            }
         }

'''

targets = sorted(glob.glob("*/*/src/main/java/icu/wuhui/voxlink/room/RoomManager.java"))
if not targets:
    print("未找到任何模板路径"); sys.exit(1)

MARK = "// 信令来源门控（1.1.9"
ok, already, failed = [], [], []
for t in targets:
    s = io.open(t, encoding="utf-8").read()
    if MARK in s:
        already.append(t); continue
    anchor = '         VoxLinkMod.LOGGER.debug("Received signal: type={}, from={}", type, from);\n         switch (type) {'
    if s.count(anchor) != 1:
        failed.append("%s (锚点命中 %d 次)" % (t, s.count(anchor))); continue
    s = s.replace(anchor, '         VoxLinkMod.LOGGER.debug("Received signal: type={}, from={}", type, from);\n' + GATE + '         switch (type) {', 1)
    io.open(t, "w", encoding="utf-8").write(s)
    ok.append(t)

print("模板总数: %d" % len(targets))
print("本次插入: %d" % len(ok))
print("已存在(跳过): %d" % len(already))
if failed:
    print("失败，需人工处理:"); [print("   ", f) for f in failed]
    sys.exit(2)
print("全部模板均已带门控")
