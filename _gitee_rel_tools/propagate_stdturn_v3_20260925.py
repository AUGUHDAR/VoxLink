#!/usr/bin/env python3
# propagate_stdturn_v3_20260925.py —— 复用 rebuild 脚本的精确替换对传播到 27 模板
# 优势：不用 diff 自动分块（v1 的锚点冲突根因），15 对精确文本替换，逐对幂等。
# 幂等顺序：先查 new 已存在→跳过；再查 old 命中恰好 1 次→替换；否则报错。
import glob
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from rebuild_stdturn_cm_20260925 import PAIRS, read_norm, write_keep_eol  # noqa: E402

REF = 'fabric/26.3'
FILE_CM = 'src/main/java/icu/wuhui/voxlink/room/ConnectionManager.java'
FILE_NEW = 'src/main/java/icu/wuhui/voxlink/network/StdTurnClient.java'

# 模板特有差异行预归一化（仅当替换对在该模板命中 <1 时才对症使用）
# 教训：old_v 若是完整行的子串会误伤（曾把 18 空格行改成 27 空格）——已禁用，改走逐模板诊断
PRE_NORMALIZE = {}

# per-模板替换对定制（该模板源文本与 26.3 改前存在真实差异，需要精确匹配其本地形态）
OVERRIDES = {
    'fabric/1.20.2_1.20.6/': {
        # 该模板 35s 注释行为 9 空格缩进（26.3 为 18 空格）
        'P6b 触发链收尾': (
            '''               .thenApply(r -> session);
         })
         // 35s: bind 升级为 3 轮重试后最长 ~16s, 加上列表/探测/allocate 需要更大总预算
''',
            '''               .thenApply(r -> session);
               })
               .thenApply(s -> (Object)s);
         })
                  // 35s: bind 升级为 3 轮重试后最长 ~16s, 加上列表/探测/allocate 需要更大总预算
''',
        ),
    },
}

def main():
    dry = '--dry' in sys.argv
    all_dirs = sorted(glob.glob('fabric/*/') + glob.glob('forge/*/') + glob.glob('neoforge/*/'))
    dirs = [d for d in all_dirs if d.replace('\\', '/').rstrip('/') != REF]
    print(f'{len(dirs)} 个目标模板（REF 已排除）')

    # 新文件 StdTurnClient.java 全铺（幂等）
    src_new = read_norm(REF + '/' + FILE_NEW)
    okn = skipn = 0
    for d in dirs:
        p = d + FILE_NEW
        if os.path.exists(p) and read_norm(p) == src_new:
            skipn += 1
            continue
        if not dry:
            write_keep_eol(p, src_new)
        okn += 1
    print(f'StdTurnClient.java: 新铺 {okn} 跳过 {skipn}')

    oks = fails = skips = 0
    for d in dirs:
        p = d + FILE_CM
        text = read_norm(p)
        d_norm = d.replace('\\', '/')
        # 已传播完成（15 对全部命中 new）→ 跳过
        if text.count('stdTurnSession') >= 10 and 'handleStdTurnAlloc' in text:
            skips += 1
            continue
        # 预归一化（仅对症模板）
        for old_v, new_v in PRE_NORMALIZE.get(d_norm, []):
            text = text.replace(old_v, new_v, 1)
        failed = []
        for desc, old_t, new_t, expect in PAIRS:
            if desc in OVERRIDES.get(d_norm, {}):
                old_t, new_t = OVERRIDES[d_norm][desc]
            # 幂等：new 已存在即视为已应用（new 常为 old+增量，不能用 old 判断，否则重复插入）
            if text.count(new_t) >= 1:
                continue
            cnt = text.count(old_t)
            if cnt != expect:
                failed.append(f'{desc}(old命中{cnt})')
                continue
            text = text.replace(old_t, new_t, 1)
        if failed:
            fails += 1
            print(f'{d} FAIL: {failed}')
        else:
            oks += 1
            if not dry:
                write_keep_eol(p, text)
            print(f'{d} OK')
    print(f'合计 OK {oks} / FAIL {fails} / SKIP {skips}')
    return 0 if fails == 0 else 1

if __name__ == '__main__':
    sys.exit(main())
