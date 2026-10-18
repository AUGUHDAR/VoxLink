# -*- coding: utf-8 -*-
"""删 HTTP 心跳：handlers_misc.go / pages.go / web/admin.html"""
import os

BASE = r"D:\桌面\voxlink\server-go"


def apply(fname, reps):
    p = os.path.join(BASE, fname)
    with open(p, "r", encoding="utf-8", newline="") as f:
        t = f.read()
    ok, miss = 0, []
    for old, new in reps:
        if old not in t:
            miss.append(old.strip().splitlines()[0][:70])
            continue
        t = t.replace(old, new, 1)
        ok += 1
    with open(p, "w", encoding="utf-8", newline="") as f:
        f.write(t)
    print(f"{fname}: 替换 {ok}/{len(reps)}")
    for m in miss:
        print(f"   [未匹配] {m}")


apply("handlers_misc.go", [
    ('\tRegisterRoute("/relay/node/heartbeat", "POST", handleRelayNodeHeartbeat)\n', ''),

    ('// POST /relay/node/heartbeat body {key, sessions, bytesIn, bytesOut, uptimeSec?}\n'
     '// 鉴权：按 key 匹配（spec §4）。key 不匹配回 ok:false（不泄露信息）。\n'
     'func handleRelayNodeHeartbeat(a *apiReq) {\n'
     '\tkey := strings.TrimSpace(S(a.body, "key", ""))\n'
     '\tif key == "" {\n'
     '\t\tOK(a.w, a.r, map[string]any{"ok": false})\n'
     '\t\treturn\n'
     '\t}\n'
     '\tsessions := cfgToInt64(a.body["sessions"])\n'
     '\tbytesIn := cfgToInt64(a.body["bytesIn"])\n'
     '\tbytesOut := cfgToInt64(a.body["bytesOut"])\n'
     '\tif errCode := RelayNodeHeartbeat(key, sessions, bytesIn, bytesOut, a.ip); errCode != "" {\n'
     '\t\t// key 不匹配：不暴露节点是否存在 → ok:false（spec §4 期望 {"ok":true}；非匹配吞掉）\n'
     '\t\tOK(a.w, a.r, map[string]any{"ok": false})\n'
     '\t\treturn\n'
     '\t}\n'
     '\tOK(a.w, a.r, map[string]any{"ok": true})\n'
     '}\n'
     '\n', ''),

    ('\t\t\t\tif host != S(nm, "host", "") || int64(port) != I(nm, "port", int64(0)) {\n'
     '\t\t\t\t\t// 地址变了：旧心跳不再代表新地址的存活 → 清零等新心跳点亮\n'
     '\t\t\t\t\tnm["lastSeen"] = int64(0)\n'
     '\t\t\t\t\tnm["hbFrom"] = ""\n'
     '\t\t\t\t}\n'
     '\t\t\t\tnm["host"] = host\n',
     '\t\t\t\tnm["host"] = host\n'),

    ('\t\t\t\tif p != I(nm, "port", int64(0)) {\n'
     '\t\t\t\t\tnm["lastSeen"] = int64(0)\n'
     '\t\t\t\t\tnm["hbFrom"] = ""\n'
     '\t\t\t\t}\n'
     '\t\t\t\tnm["port"] = p\n',
     '\t\t\t\tnm["port"] = p\n'),
])

apply("pages.go", [
    ('\tHbSessions, HbBytesIn, HbBytesOut int64\n'
     '\tHbAgo               int64  // 距最后心跳秒数；从未心跳为 -1\n'
     '\tHbFrom              string // 最近心跳来源 IP\n'
     '\tEnabled, Online     bool\n'
     '\tLastSeen, AddedAt   int64\n',
     '\tStatsSessions, StatsBytesIn, StatsBytesOut int64\n'
     '\tEnabled, Online     bool\n'
     '\tAddedAt             int64\n'),

    ('\t\t\tHbSessions:  I(n, "hbSessions", 0),\n'
     '\t\t\tHbBytesIn:   I(n, "hbBytesIn", 0),\n'
     '\t\t\tHbBytesOut:  I(n, "hbBytesOut", 0),\n'
     '\t\t\tEnabled:     B(n, "enabled", false),\n'
     '\t\t\tOnline:      B(n, "online", false),\n'
     '\t\t\tLastSeen:    I(n, "lastSeen", 0),\n'
     '\t\t\tHbAgo:       I(n, "hbAgo", -1),\n'
     '\t\t\tHbFrom:      S(n, "hbFrom", ""),\n'
     '\t\t\tAddedAt:     I(n, "addedAt", 0),\n',
     '\t\t\tStatsSessions: I(n, "statsSessions", 0),\n'
     '\t\t\tStatsBytesIn:  I(n, "statsBytesIn", 0),\n'
     '\t\t\tStatsBytesOut: I(n, "statsBytesOut", 0),\n'
     '\t\t\tEnabled:       B(n, "enabled", false),\n'
     '\t\t\tOnline:        B(n, "online", false),\n'
     '\t\t\tAddedAt:       I(n, "addedAt", 0),\n'),
])

apply(os.path.join("web", "admin.html"), [
    ('                    {{if ge $n.HbAgo 0}}<div class="muted small" style="margin-top:2px" title="{{T "relay.hb_from_hint"}}">{{T "relay.hb_prefix"}} {{$n.HbAgo}}s · {{$n.HbFrom}}</div>{{end}}\n',
     ''),
])
