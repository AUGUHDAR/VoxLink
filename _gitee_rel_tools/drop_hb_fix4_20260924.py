# -*- coding: utf-8 -*-
"""清掉 relaynodes.go 里最后 4 处 hb*/lastSeen 残留"""
import os

p = r"D:\桌面\voxlink\server-go\relaynodes.go"
with open(p, "r", encoding="utf-8", newline="") as f:
    t = f.read()
nl = "\r\n" if "\r\n" in t else "\n"

reps = [
    ('sess := I(nm, "hbSessions", 0)', 'sess := I(nm, "statsSessions", 0)'),
    ('// 不会改 hbSessions —— 节点下次心跳时会报上来。',
     '// 不会改 statsSessions —— 节点下次 WS stats 上报时会带上来。'),
    ('// 自动回收：直接删除文件即可（容量已在此期间被 hbSessions 自然递减）。',
     '// 自动回收：直接删除文件即可（容量已在此期间被 statsSessions 自然递减）。'),
    ('// enabled + 在线(lastSeen ≤ 30s) + 有余量(sessions < maxSessions)；',
     '// enabled + 在线(WebSocket 长连接) + 有余量(sessions < maxSessions)；'),
]

ok, miss = 0, []
for old, new in reps:
    o = old.replace("\n", nl)
    if o not in t:
        miss.append(old[:60])
        continue
    t = t.replace(o, new.replace("\n", nl), 1)
    ok += 1

with open(p, "w", encoding="utf-8", newline="") as f:
    f.write(t)
print(f"relaynodes.go 收尾: {ok}/{len(reps)}")
for m in miss:
    print("  [未匹配]", m)
