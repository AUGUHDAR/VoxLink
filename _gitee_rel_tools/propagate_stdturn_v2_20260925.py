#!/usr/bin/env python3
# propagate_stdturn_v2_20260925.py —— 标准 TURN 传播 v2（大段整体替换，修复 v1 锚点冲突）
# v1 缺陷：diff 自动分块的 insert 与 replace 相邻，逆序应用时前块改动破坏后块锚点 →
#         27 模板全部丢失 else 分支。v2 改为「大段整体替换」：triggerTurnRelay 注释起
#         到 handleTurnReady 方法尾整段一次替换，区域内零锚点冲突。
# 流程：PHASE1 恢复 27 模板 ConnectionManager 到 git HEAD（保持原行尾）
#       PHASE2 应用 8 个精确替换对（唯一性断言 + 幂等）
import glob
import os
import subprocess
import sys

REF = 'fabric/26.3'
FILE_CM = 'src/main/java/icu/wuhui/voxlink/room/ConnectionManager.java'
FILE_NEW = 'src/main/java/icu/wuhui/voxlink/network/StdTurnClient.java'

def read_norm(path):
    return open(path, encoding='utf-8', newline='').read().replace('\r\n', '\n')

def write_keep_eol(path, norm_text, eol_hint=None):
    if os.path.exists(path):
        old = open(path, 'rb').read()
        eol = b'\r\n' if b'\r\n' in old[:4000] else b'\n'
    else:
        sibling = os.path.join(os.path.dirname(path), 'UdpPath.java')
        eol = b'\n'
        if os.path.exists(sibling):
            ref = open(sibling, 'rb').read()
            eol = b'\r\n' if b'\r\n' in ref[:4000] else b'\n'
    open(path, 'wb').write(norm_text.replace('\n', eol.decode()).encode())

def git_head_text(path):
    return subprocess.run(['git', 'show', 'HEAD:' + path.replace(os.sep, '/')], capture_output=True).stdout.decode('utf-8').replace('\r\n', '\n')

def slice_between(text, start_marker, end_marker, include_end=False):
    i = text.index(start_marker)
    j = text.index(end_marker, i)
    return text[i:j if not include_end else j + len(end_marker)]

def build_pairs():
    """生成 (描述, old_text, new_text) 替换对（old 取自 git HEAD，new 取自 26.3 工作区）。"""
    old = git_head_text(f'{REF}/{FILE_CM}')
    new = read_norm(f'{REF}/{FILE_CM}')
    pairs = []

    # P1: import
    pairs.append((
        'import StdTurnClient',
        'import icu.wuhui.voxlink.network.TurnRelayClient;\n',
        'import icu.wuhui.voxlink.network.TurnRelayClient;\nimport icu.wuhui.voxlink.network.StdTurnClient;\n',
    ))
    # P2: 字段
    pairs.append((
        'stdTurnSession 字段',
        '   private volatile TurnRelayClient.TurnSession turnSession = null;\n',
        '   private volatile TurnRelayClient.TurnSession turnSession = null;\n   /** 标准 TURN 会话（RFC 5766，与 turnSession 互斥——同一会话只走一种协议栈）。 */\n   private volatile StdTurnClient.StdTurnSession stdTurnSession = null;\n',
    ))
    # P3-P5: 单行条件扩展
    pairs.append((
        '玩家中继入口判定',
        '      if (this.turnInProgress || this.turnSession != null) {\n',
        '      if (this.turnInProgress || this.turnSession != null || this.stdTurnSession != null) {\n',
    ))
    pairs.append((
        'isTurnActive',
        '      return this.turnSession != null && !this.turnSwitchedToP2p;\n',
        '      return (this.turnSession != null || this.stdTurnSession != null) && !this.turnSwitchedToP2p;\n',
    ))
    pairs.append((
        'isTurnPeer',
        '      return from != null && this.turnSession != null && from.equals(this.turnPeerId);\n',
        '      return from != null && (this.turnSession != null || this.stdTurnSession != null) && from.equals(this.turnPeerId);\n',
    ))
    # P6: triggerTurnRelay~handleTurnReady 整段替换（大段一次替换，消除 v1 锚点冲突）
    END_MARK = '   public void handleTurnNack(String from, JsonObject data) {'
    pairs.append((
        'triggerTurnRelay~handleTurnReady 大段',
        slice_between(old, '   /**\n    * 房客点击"使用中继"', END_MARK),
        slice_between(new, '   /**\n    * 房客点击"使用中继"', END_MARK),
    ))
    # P7: startStdTurnKeepalive（插在 startTurnKeepalive 之后、cancelTurnKeepalive 之前）
    anchor_keepalive = '   private void cancelTurnKeepalive() {'
    new_keepalive = slice_between(new, '   /**\n    * 标准 TURN 保活', anchor_keepalive)
    pairs.append((
        'startStdTurnKeepalive 插入',
        anchor_keepalive,
        new_keepalive + anchor_keepalive,
    ))
    # P8: teardownTurn stdTurn 清理（锚点含 turnSession 拆解块，唯一）
    teardown_old = ('      TurnRelayClient.TurnSession session = this.turnSession;\n'
                    '      this.turnSession = null;\n'
                    '      if (session != null) {\n'
                    '         session.unbind();\n'
                    '      }\n')
    teardown_new = teardown_old + (
        '      StdTurnClient.StdTurnSession stdSession = this.stdTurnSession;\n'
        '      this.stdTurnSession = null;\n'
        '      if (stdSession != null) {\n'
        '         StdTurnClient.close(stdSession);\n'
        '      }\n')
    pairs.append(('teardownTurn stdTurn 清理', teardown_old, teardown_new))
    # P9: 断线释放（host 桥断）
    pairs.append((
        '断线释放 cond',
        '            if (this.turnSession != null && clientId.equals(this.turnPeerId)) {',
        '            if ((this.turnSession != null || this.stdTurnSession != null) && clientId.equals(this.turnPeerId)) {',
    ))
    # P10: give-up 保留 cond
    pairs.append((
        'give-up 保留 cond',
        '      if (this.turnSession != null && this.turnTransport != null && this.turnTransport.isConnected()) {',
        '      if ((this.turnSession != null || this.stdTurnSession != null) && this.turnTransport != null && this.turnTransport.isConnected()) {',
    ))
    return pairs

# 模板特有差异行预归一化（传播前对齐 26.3 改前文本，仅影响锚点匹配）
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

def main():
    dry = '--dry' in sys.argv
    restore = '--restore' in sys.argv
    # glob 在 Windows 返回反斜杠路径，REF 比较必须归一化（否则 26.3 源会被误恢复！）
    all_dirs = sorted(glob.glob('fabric/*/') + glob.glob('forge/*/') + glob.glob('neoforge/*/'))
    dirs = [d for d in all_dirs if d.replace('\\', '/').rstrip('/') != REF]
    print(f'{len(dirs)} 个目标模板（REF 已排除: {len(all_dirs) - len(dirs)}）, dry={dry}, restore={restore}')

    # PHASE 1：恢复（v1 污染清理）——把 ConnectionManager 还原为 git HEAD 内容
    if restore:
        n = 0
        for d in dirs:
            p = d + FILE_CM
            head = git_head_text(f'{REF}/{FILE_CM}')
            if read_norm(p) != head:
                if not dry:
                    write_keep_eol(p, head)
                n += 1
        print(f'恢复 {n} 个模板的 ConnectionManager 到改前状态')
        if dry:
            print('（dry 模式未写入）')
        return 0

    pairs = build_pairs()
    print(f'{len(pairs)} 个替换对')
    oks = fails = skips = 0
    for d in dirs:
        p = d + FILE_CM
        whole = read_norm(p)
        if 'stdTurnSession' in whole:
            print(f'{d} SKIP(已含)')
            skips += 1
            continue
        # 预归一化：模板特有差异行先对齐 26.3 改前（仅影响锚点匹配过程）
        d_norm = d.replace('\\', '/')
        for old_v, new_v in PRE_NORMALIZE.get(d_norm, []):
            whole = whole.replace(old_v, new_v, 1)
        failed = []
        for desc, old_t, new_t in pairs:
            cnt = whole.count(old_t)
            if cnt != 1:
                failed.append(f'{desc}(命中{cnt})')
                continue
            whole = whole.replace(old_t, new_t, 1)
        if failed:
            fails += 1
            print(f'{d} FAIL: {failed}')
        else:
            oks += 1
            if not dry:
                write_keep_eol(p, whole)
            print(f'{d} OK({len(pairs)}对)')
    print(f'合计 OK {oks} / FAIL {fails} / SKIP {skips}')
    return 0 if fails == 0 else 1

if __name__ == '__main__':
    sys.exit(main())
