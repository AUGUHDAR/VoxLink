# -*- coding: utf-8 -*-
"""修复 handlers_misc.go（CRLF 感知）+ 清理测试文件中的心跳引用"""
import os

BASE = r"D:\桌面\voxlink\server-go"


def apply_crlf(fname, reps):
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
    print(f"{fname}: 替换 {ok}/{len(reps)} (nl={'CRLF' if nl == chr(13)+chr(10) else 'LF'})")
    for m in miss:
        print(f"   [未匹配] {m}")


apply_crlf("handlers_misc.go", [
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

# ---- 测试文件 ----
apply_crlf("relaynodews_test.go", [
    ('//   - 好 key：auth → ok → stats 帧更新 lastSeen/hb*，hub 在线判定生效',
     '//   - 好 key：auth → ok → stats 帧更新 stats*，hub 在线判定生效'),
    ('\tif I(node, "hbSessions", 0) != 3 || I(node, "hbBytesIn", 0) != 111 || I(node, "hbBytesOut", 0) != 222 {\n'
     '\t\tt.Fatalf("stats 未落库: sessions=%v in=%v out=%v", I(node, "hbSessions", -1), I(node, "hbBytesIn", -1), I(node, "hbBytesOut", -1))',
     '\tif I(node, "statsSessions", 0) != 3 || I(node, "statsBytesIn", 0) != 111 || I(node, "statsBytesOut", 0) != 222 {\n'
     '\t\tt.Fatalf("stats 未落库: sessions=%v in=%v out=%v", I(node, "statsSessions", -1), I(node, "statsBytesIn", -1), I(node, "statsBytesOut", -1))'),
])
