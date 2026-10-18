# -*- coding: utf-8 -*-
"""TURN 会话日志取证：从 tools/voxlogs/logs 全部日志提取 TURN 相关轨迹"""
import glob, os, re

LOGDIR = r"d:\桌面\voxlink\tools\voxlogs\logs"
PAT = re.compile(r"turn|TURN|relay_session|BIND_FAILED|allocat|中继", re.IGNORECASE)
FILES = sorted(glob.glob(os.path.join(LOGDIR, "**", "*.log"), recursive=True))
print("files:", len(FILES))

summary = {}
turn_lines_total = 0
for f in FILES:
    room = f.split(os.sep)[-2]
    ver = "?"
    lines = []
    try:
        with open(f, encoding="utf-8", errors="replace") as fh:
            for i, line in enumerate(fh, 1):
                if PAT.search(line):
                    lines.append((i, line.rstrip()))
                    m = re.search(r"VoxLink[^\]]*\]? ?(\d+\.\d+\.\d+)", line)
    except Exception as e:
        print("ERR", f, e)
        continue
    if lines:
        turn_lines_total += len(lines)
        # 版本探测：日志前 200 行找 mod version
        with open(f, encoding="utf-8", errors="replace") as fh:
            head = "".join(fh.readline() for _ in range(200))
        mv = re.search(r"(?:mod[ _-]?version|VoxLink)[:= ]+(\d+\.\d+\.\d+[-\w.]*)", head, re.IGNORECASE)
        summary[(room, os.path.basename(f))] = (len(lines), lines)

print("files with turn lines:", len(summary), "total turn lines:", turn_lines_total)
print()

# 关键事件统计
KEY_EVENTS = [
    "BIND_FAILED", "bind failed", "turn_ready", "turn_allocat", "申请", "TURN 通道",
    "RelayBridge", "Relay] ", "allocation", "channel_failed", "CONNECT", "connect",
]
from collections import Counter
ev_counter = Counter()
for (room, name), (n, lines) in summary.items():
    for _, line in lines:
        for ev in KEY_EVENTS:
            if ev.lower() in line.lower():
                ev_counter[ev] += 1
                break
        else:
            ev_counter["(other)"] += 1

for ev, c in ev_counter.most_common(20):
    print("%6d  %s" % (c, ev))

print()
print("=== 每文件的 TURN 行数 ===")
for (room, name), (n, lines) in sorted(summary.items(), key=lambda x: -x[1][0]):
    print("%-12s %-40s %d 行" % (room, name, n))

# 抽一个行数最多的文件看完整轨迹（前 60 条）
if summary:
    top = max(summary.items(), key=lambda x: x[1][0])
    room, name = top[0]
    n, lines = top[1]
    print()
    print("=== TOP: %s %s（%d 行，前 60 条）===" % (room, name, n))
    for i, (ln, line) in enumerate(lines[:60]):
        print("L%-6d %s" % (ln, line[:180]))
