# -*- coding: utf-8 -*-
# 2026-10-06 状态机完备性修复 ×28 模板传播
# 修1: canTransitionTo 非终态补 DISCONNECTED / 补 CONNECTED / CONNECTED 补 STUN_PROBE
# 修2: markConnectionEstablished 内推进 CONNECTED (直连/陶瓦/fed 路径此前永不进入已连接)
import glob, sys, io

ROOT = r"D:\桌面\voxlink"

# (old, new) 均为行内片段, 不含换行, 每处必须恰好出现 1 次
CS_EDITS = [
    ("case IDLE -> next == STUN_PROBE || next == DISCONNECTED || next == UDP_PUNCH || next == TRANSPORT_SETUP;",
     "case IDLE -> next == STUN_PROBE || next == DISCONNECTED || next == UDP_PUNCH || next == TRANSPORT_SETUP || next == CONNECTED;"),
    ("case STUN_PROBE -> next == SIGNAL_EXCHANGE || next == UDP_PUNCH || next == TCP_FALLBACK || next == FAILED;",
     "case STUN_PROBE -> next == SIGNAL_EXCHANGE || next == UDP_PUNCH || next == TCP_FALLBACK || next == FAILED || next == CONNECTED || next == DISCONNECTED;"),
    ("case SIGNAL_EXCHANGE -> next == UDP_PUNCH || next == TCP_FALLBACK || next == FAILED || next == TRANSPORT_SETUP;",
     "case SIGNAL_EXCHANGE -> next == UDP_PUNCH || next == TCP_FALLBACK || next == FAILED || next == TRANSPORT_SETUP || next == CONNECTED || next == DISCONNECTED;"),
    ("case UDP_PUNCH -> next == TRANSPORT_SETUP || next == TCP_FALLBACK || next == FAILED || next == CONNECTED || next == IDLE || next == STUN_PROBE || next == SIGNAL_EXCHANGE;",
     "case UDP_PUNCH -> next == TRANSPORT_SETUP || next == TCP_FALLBACK || next == FAILED || next == CONNECTED || next == IDLE || next == STUN_PROBE || next == SIGNAL_EXCHANGE || next == DISCONNECTED;"),
    ("case TCP_FALLBACK -> next == TRANSPORT_SETUP || next == FAILED || next == CONNECTED || next == STUN_PROBE || next == UDP_PUNCH || next == IDLE;",
     "case TCP_FALLBACK -> next == TRANSPORT_SETUP || next == FAILED || next == CONNECTED || next == STUN_PROBE || next == UDP_PUNCH || next == IDLE || next == DISCONNECTED;"),
    ("case TRANSPORT_SETUP -> next == CONNECTED || next == FAILED || next == UDP_PUNCH || next == IDLE;",
     "case TRANSPORT_SETUP -> next == CONNECTED || next == FAILED || next == UDP_PUNCH || next == IDLE || next == DISCONNECTED;"),
    ("case CONNECTED -> next == DISCONNECTED || next == UDP_PUNCH || next == IDLE;",
     "case CONNECTED -> next == DISCONNECTED || next == UDP_PUNCH || next == IDLE || next == STUN_PROBE;"),
]

CM_ANCHOR = "this.connectionEstablishedAtMs = System.currentTimeMillis();"
CM_INSERT = "ConnectionState.transitionTo(ConnectionState.CONNECTED, \"直连通路建立\");"

def line_ending_after(data: bytes, pos: int) -> bytes:
    """返回 pos 所在行的行尾序列(含 \\n), 兼容 LF / CRLF / CRCRLF"""
    eol = b"\n"
    i = data.find(eol, pos)
    if i == -1:
        i = len(data)
    j = i - 1
    while j >= pos and data[j:j+1] == b"\r":
        j -= 1
    return data[j+1:i+1]

def indent_before(data: bytes, pos: int) -> bytes:
    j = pos - 1
    while j >= 0 and data[j:j+1] in (b" ", b"\t"):
        j -= 1
    return data[j+1:pos]

def process(path: str) -> list:
    errs = []
    data = open(path, "rb").read()
    is_cs = path.endswith("ConnectionState.java")

    if is_cs:
        for old, new in CS_EDITS:
            n = data.count(old.encode())
            if n != 1:
                errs.append(f"anchor count={n}: {old[:60]}")
                continue
            data = data.replace(old.encode(), new.encode(), 1)
    else:
        n = data.count(CM_ANCHOR.encode())
        if n != 1:
            errs.append(f"anchor count={n}: {CM_ANCHOR[:60]}")
        else:
            pos = data.find(CM_ANCHOR.encode())
            eol = line_ending_after(data, pos)
            ind = indent_before(data, pos)
            ins = ind + CM_INSERT.encode() + eol
            insert_at = pos + len(CM_ANCHOR.encode()) + len(eol)
            # 幂等: 已插入过则跳过
            if data.find(CM_INSERT.encode()) == -1:
                data = data[:insert_at] + ins + data[insert_at:]

    if not errs:
        open(path, "wb").write(data)
    return errs

def main():
    templates = sorted(glob.glob(ROOT + "/fabric/*/") + glob.glob(ROOT + "/forge/*/") + glob.glob(ROOT + "/neoforge/*/"))
    fail = 0
    for t in templates:
        base = t + "src/main/java/icu/wuhui/voxlink/room/"
        for f in ("ConnectionState.java", "ConnectionManager.java"):
            p = base + f
            try:
                errs = process(p)
            except Exception as e:
                errs = [f"EXC {e}"]
            tag = "OK " if not errs else "FAIL"
            if errs:
                fail += 1
            print(f"[{tag}] {t.split('/')[-2]:>12}/{f}")
            for e in errs:
                print("      ", e)
    print(f"\ntotal templates={len(templates)} fail_files={fail}")
    sys.exit(1 if fail else 0)

if __name__ == "__main__":
    main()
