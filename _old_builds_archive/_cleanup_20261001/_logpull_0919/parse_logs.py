# -*- coding: utf-8 -*-
# 2026-09-19 日志挖掘轮：按版本分组 + 去重 + 错误模式聚合
import gzip, glob, json, os, re, hashlib
from collections import defaultdict

BASE = os.path.dirname(os.path.abspath(__file__))
OUT_VERSION = defaultdict(lambda: defaultdict(int))   # version -> pattern -> count
OUT_SAMPLE = defaultdict(dict)                        # version -> pattern -> (room, line)
META = {}

# 时间戳/变量归一化：去时间戳、去十六进制 id、去数字
def norm(line):
    s = line.strip()
    s = re.sub(r'^\[.*?\]\s*', '', s)                 # [时间] / [线程/级别]
    s = re.sub(r'\b[0-9a-fA-F]{8,}\b', '<hex>', s)    # 长 hex
    s = re.sub(r'\b\d{1,3}(\.\d{1,3}){3}\b', '<ip>', s)
    s = re.sub(r'\b\d{2,}\b', '<n>', s)
    s = re.sub(r'\s+', ' ', s)
    return s[:220]

# 忽略的噪音模式（非错误/高频心跳）
NOISE = re.compile(r'(heartbeat|KEEPALIVE|PING|PONG|geo\] fill|/signal/poll)', re.I)

for meta_path in sorted(glob.glob(os.path.join(BASE, '*/meta.json'))):
    room = os.path.basename(os.path.dirname(meta_path))
    try:
        meta = json.load(open(meta_path, encoding='utf-8'))
    except Exception:
        continue
    for e in meta.get('entries', []):
        ver = e.get('version', '?')
        role = e.get('role', '?')
        f = os.path.join(BASE, room, e.get('file', ''))
        if not os.path.isfile(f):
            continue
        try:
            data = gzip.open(f, 'rt', encoding='utf-8', errors='replace').read()
        except Exception as ex:
            OUT_VERSION[ver][f'<unreadable {ex}>'] += 1
            continue
        for line in data.splitlines():
            if not any(lv in line for lv in ('ERROR', 'WARN', 'FATAL', 'Exception')):
                continue
            if NOISE.search(line):
                continue
            key = f'[{role}] ' + norm(line)
            OUT_VERSION[ver][key] += 1
            if key not in OUT_SAMPLE[ver]:
                OUT_SAMPLE[ver][key] = (room, line.strip()[:300])

report = []
for ver in sorted(OUT_VERSION):
    report.append(f'\n===== 版本 {ver} =====')
    items = sorted(OUT_VERSION[ver].items(), key=lambda kv: -kv[1])
    for key, cnt in items:
        report.append(f'  x{cnt:<4} {key}')
        room, raw = OUT_SAMPLE[ver][key]
        report.append(f'        e.g. [{room}] {raw}')

open(os.path.join(BASE, '_report.txt'), 'w', encoding='utf-8').write('\n'.join(report))
print('versions:', sorted(OUT_VERSION))
print('total unique patterns:', sum(len(v) for v in OUT_VERSION.values()))
print('report -> _logpull_0919/_report.txt')
