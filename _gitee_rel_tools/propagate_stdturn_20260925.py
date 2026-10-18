#!/usr/bin/env python3
# propagate_stdturn_20260925.py —— 标准 TURN（RFC 5766）客户端改造 28 模板传播
# 源：fabric/26.3（工作区已改）；范围：4 个修改文件锚点/整文件传播 + 1 个新文件复制
# 模式：归一化 \n 匹配，写回保持目标模板原行尾；锚点唯一性断言；幂等（已含新特征跳过）
import difflib
import glob
import os
import subprocess
import sys

REF = 'fabric/26.3'
FILES_COPY = [  # 归一化后与 26.3 改前全一致的文件 → 整文件复制
    'src/main/java/icu/wuhui/voxlink/network/ProtocolNegotiator.java',
    'src/main/java/icu/wuhui/voxlink/network/SignalingClient.java',
    'src/main/java/icu/wuhui/voxlink/network/TurnRelayClient.java',
]
FILE_CM = 'src/main/java/icu/wuhui/voxlink/room/ConnectionManager.java'
FILE_NEW = 'src/main/java/icu/wuhui/voxlink/network/StdTurnClient.java'

# 模板特有差异行预归一化（传播前先把变体行换成 26.3 改前文本，让锚点能命中；
# 最终文本由新块整体覆盖，预归一化只影响匹配过程）
PRE_NORMALIZE = {
    'fabric/26.2/': [
        ('            // 3轮重试: 弱网单轮全丢常见',
         '                        // 3 轮重试(每轮5发): 弱网单轮全丢很常见(实证 09-11 22:42 guest 5发全丢)'),
    ],
    'fabric/1.20.2_1.20.6/': [
        ('            // 3 轮重试(每轮5发): 弱网单轮全丢很常见(实证 09-11 22:42 guest 5发全丢)',
         '                        // 3 轮重试(每轮5发): 弱网单轮全丢很常见(实证 09-11 22:42 guest 5发全丢)'),
        ('         // 35s: bind 升级为 3 轮重试后最长 ~16s, 加上列表/探测/allocate 需要更大总预算',
         '                  // 35s: bind 升级为 3 轮重试后最长 ~16s, 加上列表/探测/allocate 需要更大总预算'),
    ],
}

def git_head(path):
    r = subprocess.run(['git', 'show', 'HEAD:' + path.replace(os.sep, '/')], capture_output=True)
    return r.stdout.decode('utf-8').replace('\r\n', '\n')

def read_norm(path):
    return open(path, encoding='utf-8', newline='').read().replace('\r\n', '\n')

def write_keep_eol(path, norm_text):
    if os.path.exists(path):
        old = open(path, 'rb').read()
        eol = b'\r\n' if b'\r\n' in old[:4000] else b'\n'
    else:
        # 新文件：跟随同模板既有 java 文件的行尾惯例
        sibling = os.path.join(os.path.dirname(path), 'UdpPath.java')
        eol = b'\n'
        if os.path.exists(sibling):
            ref = open(sibling, 'rb').read()
            eol = b'\r\n' if b'\r\n' in ref[:4000] else b'\n'
    open(path, 'wb').write(norm_text.replace('\n', eol.decode()).encode())

def merged_blocks(old_lines, new_lines, gap=2):
    """26.3 改前→新版的 opcodes，相邻间隔<gap 的合并为传播块。
    返回 [(old_a1, old_a2, new_b1, new_b2), ...]（行号均为 0 基半开区间）。"""
    sm = difflib.SequenceMatcher(None, old_lines, new_lines, autojunk=False)
    ops = [op for op in sm.get_opcodes() if op[0] != 'equal']
    blocks = []
    for tag, a1, a2, b1, b2 in ops:
        if blocks and a1 - blocks[-1][1] < gap:
            pa1, pa2, pb1, pb2 = blocks[-1]
            blocks[-1] = (pa1, a2, pb1, pb2)
        else:
            blocks.append((a1, a2, b1, b2))
    return blocks

def propagate_cm(dirs, old_lines, new_lines, dry=False):
    blocks = merged_blocks(old_lines, new_lines)
    print(f'ConnectionManager: {len(blocks)} 个传播块')
    stats = {}
    for d in dirs:
        p = d + FILE_CM
        text = read_norm(p)
        if 'stdTurnSession' in text:
            stats[d] = 'SKIP(已含)'
            continue
        whole = text
        # 预归一化：模板特有差异行先对齐 26.3 改前（仅影响锚点匹配过程）
        # 注意 glob 在 Windows 返回反斜杠，key 统一归一比较
        d_norm = d.replace('\\', '/')
        for old_v, new_v in PRE_NORMALIZE.get(d_norm, []):
            whole = whole.replace(old_v, new_v, 1)
        # 逆序应用：单行块锚点=行本身；多行块/插入块自适应扩上下文（2→4→8→16）；
        # insert 块最后 fallback：以插入点下一行文本为锚（唯一时在该行前插入）
        for a1, a2, b1, b2 in reversed(blocks):
            is_insert = a1 == a2
            single = (a2 - a1) == 1 and (b2 - b1) == 1
            done = False
            if single:
                anchor_txt = old_lines[a1]
                cnt = whole.count(anchor_txt)
                if cnt == 1:
                    whole = whole.replace(anchor_txt, new_lines[b1], 1)
                    done = True
            else:
                for ctx in (2, 4, 8, 16):
                    lo = max(0, a1 - ctx)
                    hi = min(len(old_lines), a2 + ctx)
                    anchor_txt = '\n'.join(old_lines[lo:hi])
                    cnt = whole.count(anchor_txt)
                    if cnt == 1:
                        replacement = '\n'.join(old_lines[lo:a1] + new_lines[b1:b2] + old_lines[a2:hi])
                        whole = whole.replace(anchor_txt, replacement, 1)
                        done = True
                        break
                    if cnt == 0 and ctx >= 8:
                        break
                if not done and is_insert:
                    # fallback：插入点下一行做锚（空行数差异等场景）
                    nxt = old_lines[a1] if a1 < len(old_lines) else None
                    if nxt and nxt.strip() and whole.count(nxt) == 1:
                        whole = whole.replace(nxt, '\n'.join(new_lines[b1:b2] + [nxt]), 1)
                        done = True
            if not done:
                stats[d] = f'FAIL(锚点不唯一/缺失 @旧行{a1})'
                break
        else:
            if not dry:
                write_keep_eol(p, whole)
            stats[d] = f'OK({len(blocks)}块)'
    return stats

def main():
    dry = '--dry' in sys.argv
    dirs = sorted(d for d in glob.glob('fabric/*/') + glob.glob('forge/*/') + glob.glob('neoforge/*/') if d != REF + '/')
    print(f'{len(dirs)} 个目标模板, dry={dry}')

    # 1) 整文件复制（归一化一致已验证）
    for f in FILES_COPY:
        src = read_norm(REF + '/' + f)
        ok = skip = 0
        for d in dirs:
            p = d + f
            if read_norm(p) == src:
                skip += 1
                continue
            if not dry:
                write_keep_eol(p, src)
            ok += 1
        print(f'{os.path.basename(f)}: 复制 {ok} 跳过 {skip}')

    # 2) 新文件 StdTurnClient.java 全铺
    src_new = read_norm(REF + '/' + FILE_NEW)
    ok = skip = 0
    for d in dirs:
        p = d + FILE_NEW
        if os.path.exists(p) and read_norm(p) == src_new:
            skip += 1
            continue
        if not dry:
            write_keep_eol(p, src_new)
        ok += 1
    print(f'StdTurnClient.java: 新铺 {ok} 跳过 {skip}')

    # 3) ConnectionManager 锚点块传播
    old_lines = git_head(f'{REF}/{FILE_CM}').split('\n')
    new_lines = read_norm(REF + '/' + FILE_CM).split('\n')
    stats = propagate_cm(dirs, old_lines, new_lines, dry)
    for d, s in stats.items():
        print(f'  {d} {s}')
    fails = [d for d, s in stats.items() if s.startswith('FAIL')]
    print(f'ConnectionManager 传播: 成功 {len(stats) - len(fails)}/{len(dirs)}, 失败 {len(fails)}')
    return 1 if fails else 0

if __name__ == '__main__':
    sys.exit(main())
