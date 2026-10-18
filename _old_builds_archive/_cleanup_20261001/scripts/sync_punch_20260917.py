# -*- coding: utf-8 -*-
"""09-17 打洞增强波传播: RUT/StunProbe/UdpHolePuncher/PunchParams/PunchProfile 整拷
+ NatClass 锚点 + TcpHolePuncher 新文件 + ConnectionManager/RoomManager/P2PBridge 锚点
NatClass/CM/RM/P2PBridge 26.2 与旧模板结构分叉, 统一走锚点; 其余 HEAD 基线整拷。
"""
import os, subprocess

BASE = r'D:\桌面\voxlink'
V = 'src/main/java/icu/wuhui/voxlink/'
T262 = os.path.join(BASE, 'fabric', '26.2', V)

def norm(b): return b.replace(b'\r\r\n', b'\n').replace(b'\r\n', b'\n').replace(b'\r', b'\n')
def git_show(p): return subprocess.run(['git', 'show', 'HEAD:' + p], capture_output=True).stdout

applied, failed = [], []

WHOLE = ['network/ReliableUdpTransport.java', 'network/StunProbe.java',
         'network/UdpHolePuncher.java', 'network/PunchParams.java', 'network/PunchProfile.java']

for rel in WHOLE:
    fixed = norm(open(os.path.join(T262, rel), 'rb').read())
    # 基准=26.3 现文件(上一波传播后的全库统一态), 而非 HEAD(已含更早修复)
    ref = norm(open(os.path.join(BASE, 'fabric', '26.3', V, rel), 'rb').read())
    if fixed == ref:
        applied.append(f'(跳过) {rel}: 26.3 已同'); continue
    for loader in ('fabric', 'neoforge', 'forge'):
        for d in sorted(os.listdir(os.path.join(BASE, loader))):
            if loader == 'fabric' and d == '26.2':
                continue
            p = os.path.join(BASE, loader, d, V, rel)
            if not os.path.isfile(p):
                failed.append(f"{loader}/{d}: 缺 {rel}"); continue
            cur = norm(open(p, 'rb').read())
            if cur == fixed:
                continue
            if cur == ref:
                open(p, 'wb').write(fixed)
                applied.append(f"{loader}/{d}: {os.path.basename(rel)}")
            else:
                failed.append(f"{loader}/{d}: {os.path.basename(rel)} 基准不匹配")

# NatClass: 两处 case 锚点
NAT_PAIRS = [
 ("case插入1",
  "         case SYMMETRIC_EASY_INC:\n         case SYMMETRIC_EASY_DEC:\n            return EASY_SYM;",
  "         case SYMMETRIC_EASY_INC:\n         case SYMMETRIC_EASY_DEC:\n         case EASY_SYM_INC:\n         case EASY_SYM_DEC:\n            return EASY_SYM;"),
 ("case插入2",
  "         case SYMMETRIC:\n            return HARD_SYM;",
  "         case SYMMETRIC:\n         case HARD_SYM:\n            return HARD_SYM;"),
]

for loader in ('fabric', 'neoforge', 'forge'):
    for d in sorted(os.listdir(os.path.join(BASE, loader))):
        if loader == 'fabric' and d == '26.2':
            continue
        p = os.path.join(BASE, loader, d, V, 'network/NatClass.java')
        if not os.path.isfile(p):
            continue
        raw = open(p, 'rb').read()
        dc = norm(raw)
        eol = b'\r\n' if b'\r\n' in raw[:4000] else b'\n'
        if b'EASY_SYM_INC' in dc:
            continue
        cnt = 0
        okall = True
        for name, old, new in NAT_PAIRS:
            ob, nb = old.encode(), new.encode()
            if nb in dc:
                continue
            if dc.count(ob) != 1:
                okall = False
                failed.append(f"{loader}/{d}: NatClass[{name}] x{dc.count(ob)}")
                break
            dc = dc.replace(ob, nb, 1)
            cnt += 1
        if okall and cnt:
            open(p, 'wb').write(dc if eol == b'\n' else dc.replace(b'\n', b'\r\n'))
            applied.append(f"{loader}/{d}: NatClass {cnt}")

# TcpHolePuncher: 新文件整拷
tcp_src = open(os.path.join(T262, 'network/TcpHolePuncher.java'), 'rb').read()
for loader in ('fabric', 'neoforge', 'forge'):
    for d in sorted(os.listdir(os.path.join(BASE, loader))):
        p = os.path.join(BASE, loader, d, V, 'network/TcpHolePuncher.java')
        if os.path.isfile(p):
            continue
        os.makedirs(os.path.dirname(p), exist_ok=True)
        open(p, 'wb').write(tcp_src)
        applied.append(f"{loader}/{d}: TcpHolePuncher新建")

print("=== 成功", len(applied), "===")
print("\n".join(applied))
print("=== 失败", len(failed), "===")
print("\n".join(failed) if failed else "(无)")
