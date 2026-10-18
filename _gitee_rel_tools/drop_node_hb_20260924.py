# -*- coding: utf-8 -*-
"""节点端（TURN/GO）：彻底移除 HTTP 轮询心跳，只保留 WS 长连接上报"""
import os

BASE = r"D:\桌面\voxlink\TURN\GO"


def apply(fname, reps, must=True):
    p = os.path.join(BASE, fname)
    with open(p, "r", encoding="utf-8", newline="") as f:
        t = f.read()
    nl = "\r\n" if "\r\n" in t else "\n"
    ok, miss = 0, []
    for old, new in reps:
        o = old.replace("\n", nl)
        n = new.replace("\n", nl)
        if o not in t:
            miss.append(old.strip().splitlines()[0][:70])
            continue
        t = t.replace(o, n, 1)
        ok += 1
    with open(p, "w", encoding="utf-8", newline="") as f:
        f.write(t)
    print(f"{fname}: 替换 {ok}/{len(reps)}")
    for m in miss:
        print(f"   [未匹配] {m}")


# ---------- ws.go ----------
apply("ws.go", [
    ('// ws.go —— 节点 WS 事件化上报（替代 10s 无条件 HTTP 心跳）。\n'
     '//\n'
     '// 事件策略：会话数变更即报；字节变化置脏、30s 有脏才刷；空闲零数据帧。\n'
     '// 保活：节点 25s WS ping（控制帧极小）；服务端 ping 经自定义 handler 刷新读死线。\n'
     '// 可用性：连不上/断线自动回落 HTTP 心跳（heartbeat.go 经 wsActive 静默），恢复后 HTTP 自动静默。\n',
     '// ws.go —— 节点 WS 事件化上报（唯一的存活/负载上报通道，HTTP 轮询心跳已彻底移除）。\n'
     '//\n'
     '// 事件策略：会话数变更即报；字节变化置脏、30s 有脏才刷；空闲零数据帧。\n'
     '// 保活：节点 25s WS ping（控制帧极小）；服务端 ping 经自定义 handler 刷新读死线。\n'
     '// 可用性：断线自动退避重连——在线状态由这条长连接是否存在决定。'),

    ('type WSReporter struct {\n'
     '\turl      string\n'
     '\tkey      string\n'
     '\tmgr      *SessionManager\n'
     '\twsActive *atomic.Bool // 与 HTTP Heartbeater 共享：true 时 HTTP 跳过\n'
     '}\n'
     '\n'
     'func newWSReporter(heartbeatURL, key string, mgr *SessionManager, wsActive *atomic.Bool) *WSReporter {\n'
     '\treturn &WSReporter{\n'
     '\t\turl:      wsURLFromHeartbeat(heartbeatURL),\n'
     '\t\tkey:      key,\n'
     '\t\tmgr:      mgr,\n'
     '\t\twsActive: wsActive,\n'
     '\t}\n'
     '}',
     'type WSReporter struct {\n'
     '\turl string\n'
     '\tkey string\n'
     '\tmgr *SessionManager\n'
     '}\n'
     '\n'
     'func newWSReporter(heartbeatURL, key string, mgr *SessionManager) *WSReporter {\n'
     '\treturn &WSReporter{\n'
     '\t\turl: wsURLFromHeartbeat(heartbeatURL),\n'
     '\t\tkey: key,\n'
     '\t\tmgr: mgr,\n'
     '\t}\n'
     '}'),

    ('\t\tconnected := r.session(ctx)\n'
     '\t\tr.wsActive.Store(false)\n',
     '\t\tconnected := r.session(ctx)\n'),

    ('\tr.wsActive.Store(true)\n'
     '\tlog.Printf("[ws] 已连接，事件化上报开启: %s", r.url)\n',
     '\tlog.Printf("[ws] 已连接，事件化上报开启: %s", r.url)\n'),
])

# ---------- main.go ----------
apply("main.go", [
    ('\t// 心跳：WS 事件化上报优先；HTTP 降级为断线兜底（WS 激活时静默）\n'
     '\tvar wsActive atomic.Bool\n'
     '\thb := newHeartbeater(cfg.HeartbeatURL, cfg.NodeKey, cfg.HeartbeatIntervalSec, mgr)\n'
     '\thb.SetWsActive(&wsActive)\n'
     '\tgo hb.Run(ctx)\n'
     '\tgo newWSReporter(cfg.HeartbeatURL, cfg.NodeKey, mgr, &wsActive).Run(ctx)\n',
     '\t// 上报：只走 WebSocket 长连接（HTTP 轮询心跳已彻底移除）\n'
     '\tgo newWSReporter(cfg.HeartbeatURL, cfg.NodeKey, mgr).Run(ctx)\n'),

    ('\tHeartbeatURL          string `json:"heartbeatUrl"`           // 信令 /relay/node/heartbeat\n'
     '\tHeartbeatIntervalSec  int    `json:"heartbeatIntervalSec"`   // 默认 10\n',
     '\tHeartbeatURL          string `json:"heartbeatUrl"`           // 信令 WS 地址源（/relay/node/heartbeat 去尾换 /ws）\n'),
])

# ---------- 删除 heartbeat.go ----------
hb = os.path.join(BASE, "heartbeat.go")
if os.path.isfile(hb):
    os.remove(hb)
    print("heartbeat.go: 已删除")
else:
    print("heartbeat.go: 不存在（已删）")
