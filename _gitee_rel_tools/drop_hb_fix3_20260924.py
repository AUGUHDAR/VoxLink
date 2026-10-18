# -*- coding: utf-8 -*-
"""收尾：handlers_misc.go 两处 5-tab 清零块 + relaynodes_test.go 测试适配"""
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
    print(f"{fname}: 替换 {ok}/{len(reps)}")
    for m in miss:
        print(f"   [未匹配] {m}")


T5 = "\t\t\t\t\t"
T6 = "\t\t\t\t\t\t"

apply_crlf("handlers_misc.go", [
    (T5 + 'if host != S(nm, "host", "") || int64(port) != I(nm, "port", int64(0)) {\n'
     + T6 + '// 地址变了：旧心跳不再代表新地址的存活 → 清零等新心跳点亮\n'
     + T6 + 'nm["lastSeen"] = int64(0)\n'
     + T6 + 'nm["hbFrom"] = ""\n'
     + T5 + '}\n'
     + T5 + 'nm["host"] = host\n',
     T5 + 'nm["host"] = host\n'),

    (T5 + 'if p != I(nm, "port", int64(0)) {\n'
     + T6 + 'nm["lastSeen"] = int64(0)\n'
     + T6 + 'nm["hbFrom"] = ""\n'
     + T5 + '}\n'
     + T5 + 'nm["port"] = p\n',
     T5 + 'nm["port"] = p\n'),
])

apply_crlf("relaynodes_test.go", [
    ('\t\t\t"lastSeen":    now,\n'
     '\t\t\t"hbSessions":  sess,\n',
     '\t\t\t"statsSessions": sess,\n'),

    ('\t// 测试离线过滤：所有 lastSeen 改成 0\n'
     '\tfor _, n := range arr {\n'
     '\t\tif nm, ok := n.(map[string]any); ok {\n'
     '\t\t\tnm["lastSeen"] = int64(0)\n'
     '\t\t}\n'
     '\t}\n'
     '\t_ = DSSet("relay_nodes", arr)\n'
     '\tout = RelayNodesForList()\n',
     '\t// 测试离线过滤：这些测试节点从未建立 WebSocket 连接 → 应全部判为离线\n'
     '\tout = RelayNodesForList()\n'),
])
