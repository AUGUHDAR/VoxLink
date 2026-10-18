# -*- coding: utf-8 -*-
"""TURN 日志取证 v2：只匹配 VoxLink 的 [Turn]/[RelayBridge]/RelayBridge/TurnTcpChannel/turn_ready/BIND_FAILED"""
import glob, os, re

LOGDIR = r"d:\桌面\voxlink\tools\voxlogs\logs"
PAT = re.compile(r"\[Turn\]|RelayBridge|TurnTcpChannel|TurnRelayClient|turn_ready|turn_alloc|BIND_FAILED|bind_result|BIND_RESULT", re.IGNORECASE)
FILES = sorted(glob.glob(os.path.join(LOGDIR, "**", "*.log"), recursive=True))

summary = {}
total = 0
for f in FILES:
    room = f.split(os.sep)[-2]
    lines = []
    with open(f, encoding="utf-8", errors="replace") as fh:
        for i, line in enumerate(fh, 1):
            if PAT.search(line):
                lines.append((i, line.rstrip()))
    if lines:
        total += len(lines)
        summary[(room, os.path.basename(f))] = lines

print("files:", len(FILES), "files with TURN lines:", len(summary), "total:", total)
print()

# 事件序列统计：把每行的关键事件抽出来
from collections import Counter
EV_PAT = re.compile(r"\[Turn\]\s*([^:(]{0,60})|RelayBridge[^:]*:\s*(.{0,40})|TurnTcpChannel[^:]*:\s*(.{0,40})")
ev = Counter()
for (room, name), lines in summary.items():
    for _, line in lines:
        m = EV_PAT.search(line)
        key = (m.group(1) or m.group(2) or m.group(3) or "?").strip()[:60] if m else line[:60]
        ev[key] += 1

for k, c in ev.most_common(40):
    print("%6d  %s" % (c, k))
