# -*- coding: utf-8 -*-
"""彻底删除 HTTP 轮询心跳：在线判定只保留 WebSocket 长连接（server-go）

改动要点：
  - 删除 turnHeartbeatTTL / lastSeen / hbFrom / hbAgo（时间戳语义）
  - 在线判定一律 relayNodeWSOnline（连接在即在线）
  - HTTP 心跳入口 RelayNodeHeartbeat → 收窄为 RelayNodeWSStats（只存负载统计，不碰在线判定）
  - 负载统计字段改名 hbSessions/hbBytesIn/hbBytesOut → statsSessions/statsBytesIn/statsBytesOut
"""
import os

BASE = r"D:\桌面\voxlink\server-go"

ROOT = "relaynodes.go"
WS = "relaynodews.go"

ROOT_REPS = [
    # 1) 文件头注释
    ('//   - 节点表：DS["relay_nodes"] = []any（每条含 id/name/host/port/maxSessions/\n'
     '//     enabled/key/lastSeen/hbSessions/hbBytesIn/hbBytesOut/addedAt）。',
     '//   - 节点表：DS["relay_nodes"] = []any（每条含 id/name/host/port/maxSessions/\n'
     '//     enabled/key/statsSessions/statsBytesIn/statsBytesOut/addedAt）。'),

    ('//   - 心跳：RelayNodeHeartbeat 按 key 匹配更新 lastSeen/hb*，30s 无心跳即离线。',
     '//   - 在线判定：只看 /relay/node/ws 的 WebSocket 长连接（HTTP 轮询心跳已彻底移除）。'),

    # 2) 常量：删除心跳 TTL
    ('\t// turnHeartbeatTTL 30s 无心跳视为离线（spec §4）。\n\tturnHeartbeatTTL = 30\n', ''),

    # 3) 节点初始化字段
    ('\t\t"lastSeen":    int64(0),\n'
     '\t\t"hbSessions":  int64(0),\n'
     '\t\t"hbBytesIn":   int64(0),\n'
     '\t\t"hbBytesOut":  int64(0),\n',
     '\t\t"statsSessions": int64(0),\n'
     '\t\t"statsBytesIn":  int64(0),\n'
     '\t\t"statsBytesOut": int64(0),\n'),

    # 4) 地址变更不再清零心跳
    ('// 地址串未显式带端口时保留节点原端口；host/port 实际变化时清零 lastSeen（旧心跳不再代表新地址的存活）。',
     '// 地址串未显式带端口时保留节点原端口。'),

    ('\t\tif host != S(nm, "host", "") || int64(port) != I(nm, "port", int64(0)) {\n'
     '\t\t\tnm["lastSeen"] = int64(0)\n'
     '\t\t\tnm["hbFrom"] = ""\n'
     '\t\t}\n'
     '\t\tnm["host"] = host\n',
     '\t\tnm["host"] = host\n'),

    # 5) RelayNodesList：在线只按 WS，去掉心跳字段
    ('// RelayNodesList 返回模板/JSON 行（含在线状态）。行结构：\n'
     '// {id, name, host, port, sessions, maxSessions, enabled, online, key, hbSessions, hbBytesIn, hbBytesOut, lastSeen, addedAt}\n'
     'func RelayNodesList() []map[string]any {\n'
     '\tarr := relayNodesAll()\n'
     '\tout := make([]map[string]any, 0, len(arr))\n'
     '\tnow := Now()\n'
     '\tfor _, n := range arr {\n'
     '\t\tnm, ok := n.(map[string]any)\n'
     '\t\tif !ok {\n'
     '\t\t\tcontinue\n'
     '\t\t}\n'
     '\t\tlastSeen := I(nm, "lastSeen", 0)\n'
     '\t\tonline := (lastSeen > 0 && (now-lastSeen) <= turnHeartbeatTTL) || relayNodeWSOnline(S(nm, "id", ""))\n'
     '\t\thb := I(nm, "hbSessions", 0)\n'
     '\t\t// 心跳年龄：从未心跳过为 -1（模板据此隐藏心跳信息行）\n'
     '\t\thbAgo := int64(-1)\n'
     '\t\tif lastSeen > 0 {\n'
     '\t\t\thbAgo = now - lastSeen\n'
     '\t\t\tif hbAgo < 0 {\n'
     '\t\t\t\thbAgo = 0\n'
     '\t\t\t}\n'
     '\t\t}\n'
     '\t\t// 显式未发布会话计数（粗略：内存索引命中数；不同步时取 hbSessions）\n'
     '\t\tsess := hb\n'
     '\t\tif cnt := relayCountActiveSessionsForNode(S(nm, "id", "")); cnt > sess {\n'
     '\t\t\tsess = cnt\n'
     '\t\t}\n'
     '\t\tout = append(out, map[string]any{\n'
     '\t\t\t"id":          S(nm, "id", ""),\n'
     '\t\t\t"name":        S(nm, "name", ""),\n'
     '\t\t\t"host":        S(nm, "host", ""),\n'
     '\t\t\t"port":        I(nm, "port", int64(turnDefaultPort)),\n'
     '\t\t\t"sessions":    sess,\n'
     '\t\t\t"maxSessions": I(nm, "maxSessions", 0),\n'
     '\t\t\t"enabled":     B(nm, "enabled", false),\n'
     '\t\t\t"online":      online,\n'
     '\t\t\t"key":         S(nm, "key", ""),\n'
     '\t\t\t"hbSessions":  hb,\n'
     '\t\t\t"hbBytesIn":   I(nm, "hbBytesIn", 0),\n'
     '\t\t\t"hbBytesOut":  I(nm, "hbBytesOut", 0),\n'
     '\t\t\t"lastSeen":    lastSeen,\n'
     '\t\t\t"hbAgo":       hbAgo,\n'
     '\t\t\t"hbFrom":      S(nm, "hbFrom", ""),\n'
     '\t\t\t"addedAt":     I(nm, "addedAt", 0),\n'
     '\t\t})\n'
     '\t}\n',

     '// RelayNodesList 返回模板/JSON 行（含在线状态）。在线与否只看 WebSocket 长连接。\n'
     '// 行结构：\n'
     '// {id, name, host, port, sessions, maxSessions, enabled, online, key, '
     'statsSessions, statsBytesIn, statsBytesOut, addedAt}\n'
     'func RelayNodesList() []map[string]any {\n'
     '\tarr := relayNodesAll()\n'
     '\tout := make([]map[string]any, 0, len(arr))\n'
     '\tfor _, n := range arr {\n'
     '\t\tnm, ok := n.(map[string]any)\n'
     '\t\tif !ok {\n'
     '\t\t\tcontinue\n'
     '\t\t}\n'
     '\t\tst := I(nm, "statsSessions", 0)\n'
     '\t\t// 显式未发布会话计数（粗略：内存索引命中数；不同步时取 statsSessions）\n'
     '\t\tsess := st\n'
     '\t\tif cnt := relayCountActiveSessionsForNode(S(nm, "id", "")); cnt > sess {\n'
     '\t\t\tsess = cnt\n'
     '\t\t}\n'
     '\t\tout = append(out, map[string]any{\n'
     '\t\t\t"id":             S(nm, "id", ""),\n'
     '\t\t\t"name":           S(nm, "name", ""),\n'
     '\t\t\t"host":           S(nm, "host", ""),\n'
     '\t\t\t"port":           I(nm, "port", int64(turnDefaultPort)),\n'
     '\t\t\t"sessions":       sess,\n'
     '\t\t\t"maxSessions":    I(nm, "maxSessions", 0),\n'
     '\t\t\t"enabled":        B(nm, "enabled", false),\n'
     '\t\t\t"online":         relayNodeWSOnline(S(nm, "id", "")),\n'
     '\t\t\t"key":            S(nm, "key", ""),\n'
     '\t\t\t"statsSessions":  st,\n'
     '\t\t\t"statsBytesIn":   I(nm, "statsBytesIn", 0),\n'
     '\t\t\t"statsBytesOut":  I(nm, "statsBytesOut", 0),\n'
     '\t\t\t"addedAt":        I(nm, "addedAt", 0),\n'
     '\t\t})\n'
     '\t}\n'),

    # 6) RelayNodeOnline：只看 WS
    ('\tlastSeen := I(nm, "lastSeen", 0)\n'
     '\tif lastSeen <= 0 {\n'
     '\t\tif !relayNodeWSOnline(nodeID) {\n'
     '\t\t\treturn false\n'
     '\t\t}\n'
     '\t} else if Now()-lastSeen > turnHeartbeatTTL && !relayNodeWSOnline(nodeID) {\n'
     '\t\treturn false\n'
     '\t}\n'
     '\treturn true\n',
     '\t// 在线判定只看 WebSocket 长连接（HTTP 轮询心跳已彻底移除）\n'
     '\treturn relayNodeWSOnline(nodeID)\n'),

    # 7) 心跳函数 → 收窄为 WS 负载统计
    ('// ---- 心跳 ----\n'
     '\n'
     '// RelayNodeHeartbeat 按 key 匹配更新 lastSeen / hb*（spec §4）。fromIP 为心跳来源\n'
     '// IP（仅展示用，帮助管理员发现「密钥在别处心跳、地址却指向别处」的灵魂在线）。\n'
     '// 失败原因：\n'
     '//   NODE_NOT_FOUND：key 不匹配任何节点；调用方按 ok:false 返回（不泄露信息）。\n'
     '//   OK：成功。\n'
     'func RelayNodeHeartbeat(key string, sessions, bytesIn, bytesOut int64, fromIP string) string {\n'
     '\tif key == "" {\n'
     '\t\treturn "NODE_NOT_FOUND"\n'
     '\t}\n'
     '\tarr := relayNodesAll()\n'
     '\tfound := false\n'
     '\tfor i, n := range arr {\n'
     '\t\tnm, ok := n.(map[string]any)\n'
     '\t\tif !ok {\n'
     '\t\t\tcontinue\n'
     '\t\t}\n'
     '\t\tif S(nm, "key", "") != key {\n'
     '\t\t\tcontinue\n'
     '\t\t}\n'
     '\t\tfound = true\n'
     '\t\tnm["lastSeen"] = Now()\n'
     '\t\tnm["hbFrom"] = fromIP\n'
     '\t\tif sessions >= 0 {\n'
     '\t\t\tnm["hbSessions"] = sessions\n'
     '\t\t}\n'
     '\t\tif bytesIn >= 0 {\n'
     '\t\t\tnm["hbBytesIn"] = bytesIn\n'
     '\t\t}\n'
     '\t\tif bytesOut >= 0 {\n'
     '\t\t\tnm["hbBytesOut"] = bytesOut\n'
     '\t\t}\n'
     '\t\tarr[i] = nm\n'
     '\t\tbreak\n'
     '\t}\n'
     '\tif !found {\n'
     '\t\treturn "NODE_NOT_FOUND"\n'
     '\t}\n'
     '\tif err := DSSet("relay_nodes", arr); err != nil {\n'
     '\t\tlog.Printf("[relay] heartbeat 持久化失败（已记录到内存但下次启动会丢失）: %v", err)\n'
     '\t}\n'
     '\treturn ""\n'
     '}\n',

     '// ---- WS 负载统计 ----\n'
     '\n'
     '// RelayNodeWSStats 按 key 匹配更新 WS 上报的负载统计（会话数/字节数）。\n'
     '// 注意：这里不再记录任何时间戳——在线判定完全由 WebSocket 长连接决定，\n'
     '// 因此不存在「心跳很久没来却仍显示在线」这类矛盾状态。\n'
     '// 失败原因：NODE_NOT_FOUND：key 不匹配任何节点。\n'
     'func RelayNodeWSStats(key string, sessions, bytesIn, bytesOut int64) string {\n'
     '\tif key == "" {\n'
     '\t\treturn "NODE_NOT_FOUND"\n'
     '\t}\n'
     '\tarr := relayNodesAll()\n'
     '\tfound := false\n'
     '\tfor i, n := range arr {\n'
     '\t\tnm, ok := n.(map[string]any)\n'
     '\t\tif !ok {\n'
     '\t\t\tcontinue\n'
     '\t\t}\n'
     '\t\tif S(nm, "key", "") != key {\n'
     '\t\t\tcontinue\n'
     '\t\t}\n'
     '\t\tfound = true\n'
     '\t\tif sessions >= 0 {\n'
     '\t\t\tnm["statsSessions"] = sessions\n'
     '\t\t}\n'
     '\t\tif bytesIn >= 0 {\n'
     '\t\t\tnm["statsBytesIn"] = bytesIn\n'
     '\t\t}\n'
     '\t\tif bytesOut >= 0 {\n'
     '\t\t\tnm["statsBytesOut"] = bytesOut\n'
     '\t\t}\n'
     '\t\tarr[i] = nm\n'
     '\t\tbreak\n'
     '\t}\n'
     '\tif !found {\n'
     '\t\treturn "NODE_NOT_FOUND"\n'
     '\t}\n'
     '\tif err := DSSet("relay_nodes", arr); err != nil {\n'
     '\t\tlog.Printf("[relay] stats 持久化失败（已记录到内存但下次启动会丢失）: %v", err)\n'
     '\t}\n'
     '\treturn ""\n'
     '}\n'),

    # 8) 候选节点筛选：只看 WS
    ('\t\tlastSeen := I(nm, "lastSeen", 0)\n'
     '\t\tstale := lastSeen <= 0 || now-lastSeen > turnHeartbeatTTL\n'
     '\t\tif stale && !relayNodeWSOnline(S(nm, "id", "")) {\n'
     '\t\t\tcontinue\n'
     '\t\t}\n',
     '\t\tif !relayNodeWSOnline(S(nm, "id", "")) {\n'
     '\t\t\tcontinue\n'
     '\t\t}\n'),

    # 9) 候选节点负载取值改名
    ('\t\thb := I(nm, "hbSessions", 0)\n'
     '\t\tsess := hb\n',
     '\t\tsess := I(nm, "statsSessions", 0)\n'),
]

WS_REPS = [
    ('\t// 记一次 lastSeen：Admin 模板按 lastSeen>0 判定是否显示心跳行\n'
     '\tip := ClientIP(r)\n'
     '\tif ip == "0.0.0.0" {\n'
     '\t\tip = ""\n'
     '\t}\n'
     '\t_ = RelayNodeHeartbeat(key, -1, -1, -1, "ws:"+ip)\n'
     '\tlog.Printf("[relay-ws] 节点接入 id=%s src=%s", nodeID, ip)\n',
     '\tip := ClientIP(r)\n'
     '\tif ip == "0.0.0.0" {\n'
     '\t\tip = ""\n'
     '\t}\n'
     '\tlog.Printf("[relay-ws] 节点接入 id=%s src=%s（在线状态即由此连接决定）", nodeID, ip)\n'),

    ('\t\t// 值缺失传 -1（RelayNodeHeartbeat 对负值跳过更新）\n',
     '\t\t// 值缺失传 -1（RelayNodeWSStats 对负值跳过更新）\n'),

    ('\t\t_ = RelayNodeHeartbeat(key, getI("sessions"), getI("bytesIn"), getI("bytesOut"), "ws:"+ip)\n',
     '\t\t_ = RelayNodeWSStats(key, getI("sessions"), getI("bytesIn"), getI("bytesOut"))\n'),

    ('// relayNodeWSOnline WS 在线判定；与 lastSeen 判定取或（三处在线计算共用）。',
     '// relayNodeWSOnline 节点在线判定——唯一依据（HTTP 轮询心跳已移除）。'),
]


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


apply(ROOT, ROOT_REPS)
apply(WS, WS_REPS)
