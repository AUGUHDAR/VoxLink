# -*- coding: utf-8 -*-
"""复现 VoxLinkScreen.computeLayout 修复后的逻辑，穷举高度/场景验证：
   1) 官网+反馈行永远有位置（不会被裁）
   2) 官网+反馈行不与陶瓦下载行重叠
   3) 不与顶部按钮组、底部其他行重叠
"""
L_ROW = 20
L_GAP = 4
L_STEP = L_ROW + L_GAP
L_BOTTOM_MARGIN = 28
L_DECOR_H = 28
L_PROGRESS_RESERVE = 16
L_PLATFORM_NOTE_RESERVE = 15


def compute(height, has_room, is_host, singleplayer, show_download, downloading, platform_note):
    topRows = 2 if (not has_room and not singleplayer) else 1
    sectionH = topRows * L_ROW + (topRows - 1) * L_GAP
    headerFloor = 40 if not has_room else (64 if is_host else 84)
    maxAttempt = 2 if show_download else 1

    for attempt in range(maxAttempt + 1):
        back = height - L_BOTTOM_MARGIN
        relay = back - L_STEP
        uploadLog = relay - L_STEP
        decor = (not has_room) and attempt == 0
        dropConfig = attempt >= 2 and show_download
        config = None if dropConfig else (uploadLog - L_STEP - L_DECOR_H if decor else uploadLog - L_STEP)
        stackBase = uploadLog if dropConfig else config
        downloadRow = stackBase - L_STEP if show_download else None
        stackTop = downloadRow if show_download else stackBase
        websiteRow = stackTop - L_STEP
        reserve = (L_PROGRESS_RESERVE if downloading else 0) + (L_PLATFORM_NOTE_RESERVE if platform_note else 0)
        limit = min(stackTop - reserve, websiteRow - L_GAP)
        ideal = min(height // 2 - 30, limit - sectionH)
        if ideal >= headerFloor or attempt == maxAttempt:
            topStart = max(headerFloor + 4, ideal)
            topBottom = topStart + sectionH - L_ROW
            wy = max(websiteRow, topBottom + L_STEP)
            if show_download and wy + L_ROW > downloadRow:
                wy = max(websiteRow, topBottom + L_GAP)
            website = wy
            return dict(back=back, relay=relay, uploadLog=uploadLog, config=config,
                        downloadRow=downloadRow, topStart=topStart, sectionH=sectionH,
                        website=website, topRows=topRows, decor=decor,
                        attempt=attempt, dropConfig=dropConfig)
    return None


def span(y):
    return (y, y + L_ROW) if y is not None else None


def overlaps(a, b):
    if a is None or b is None:
        return False
    return a[0] < b[1] and b[0] < a[1]


bad = []
cases = 0
for height in range(180, 601, 1):
    for has_room in (True, False):
        for is_host in ((True, False) if has_room else (False,)):
            for singleplayer in ((True, False) if not has_room else (False,)):
                for show_download in (True, False):
                    for downloading in ((True, False) if show_download else (False,)):
                        for platform_note in (True, False):
                            cases += 1
                            r = compute(height, has_room, is_host, singleplayer,
                                        show_download, downloading, platform_note)
                            if r is None:
                                bad.append((height, has_room, is_host, show_download, downloading,
                                            platform_note, "布局计算返回空"))
                                continue
                            w = span(r["website"])
                            d = span(r["downloadRow"])
                            top = (r["topStart"], r["topStart"] + r["sectionH"])
                            others = [span(r["config"]), span(r["uploadLog"]), span(r["relay"]), span(r["back"])]

                            if r["website"] < 0 or r["website"] + L_ROW > height:
                                bad.append((height, has_room, is_host, show_download, downloading,
                                            platform_note, f"官网行越界 y={r['website']}"))
                            if overlaps(w, d):
                                bad.append((height, has_room, is_host, show_download, downloading,
                                            platform_note, f"与下载行重叠 website={r['website']} download={r['downloadRow']}"))
                            if overlaps(w, top):
                                bad.append((height, has_room, is_host, show_download, downloading,
                                            platform_note, f"与顶部组重叠 website={r['website']} top={top}"))
                            for i, o in enumerate(others):
                                if overlaps(w, o):
                                    bad.append((height, has_room, is_host, show_download, downloading,
                                                platform_note, f"与底部第{i}行重叠 website={r['website']} row={o}"))

print(f"共模拟 {cases} 种组合")
if bad:
    import re as _re
    from collections import Counter
    kinds = Counter()
    for x in bad:
        kinds[_re.sub(r"=[-\d ]+$", "", x[6]).strip()] += 1
    print(f"!! 发现 {len(bad)} 个问题：")
    for k, v in kinds.most_common():
        print(f"     {k}: {v}")
    hs = sorted({x[0] for x in bad})
    print(f"   涉及高度范围: {hs[0]} ~ {hs[-1]}（共 {len(hs)} 个高度值）")
    ok_heights = [h for h in range(180, 601) if h not in set(hs)]
    if ok_heights:
        print(f"   无问题的高度: {ok_heights[0]} ~ {ok_heights[-1]}（共 {len(ok_heights)} 个）")
    vanish = [x for x in bad if "越界" in x[6] or "返回空" in x[6]]
    print(f"   >>> 其中「官网+反馈行消失/越界」的案例数: {len(vanish)}")
else:
    print("✅ 全部通过：官网+反馈行在所有组合下都有位置、且不与任何行重叠")
