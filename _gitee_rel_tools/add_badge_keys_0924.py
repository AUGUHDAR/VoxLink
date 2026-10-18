# -*- coding: utf-8 -*-
"""在 13 个语言文件的 voxlink.signaling.degraded 行后插入 degraded_badge key（幂等）。"""
import io
import os

LANG_DIR = r"d:\桌面\voxlink\forge\26.3\src\main\resources\assets\voxlink\lang"

BADGES = {
    "zh_cn": "信令断线：房间保活中，新玩家暂无法加入",
    "zh_hk": "信令斷線：房間保活中，新玩家暫無法加入",
    "zh_tw": "信令斷線：房間保活中，新玩家暫無法加入",
    "en_us": "Signaling offline: room kept alive, new joins paused",
    "ja_jp": "シグナリング切断中：部屋は維持、新規参加は停止",
    "ko_kr": "시그널링 끊김: 방 유지 중, 새 참여 일시 중지",
    "fr_fr": "Signalisation coupée : salle maintenue, nouvelles arrivées en pause",
    "de_de": "Signalisierung getrennt: Raum bleibt bestehen, neue Beitritte pausieren",
    "es_es": "Señalización caída: sala activa, nuevas entradas en pausa",
    "ru_ru": "Сигналинг прерван: комната активна, новые входы на паузе",
    "pt_br": "Sinalização caída: sala ativa, novas entradas pausadas",
    "ar_sa": "الإشارات مقطوعة: الغرفة محفوظة، الانضمامات الجديدة متوقفة",
    "lzh": "信令斷線：房已留，新人暫不得入",
}

for code, text in BADGES.items():
    path = os.path.join(LANG_DIR, code + ".json")
    with io.open(path, "r", encoding="utf-8") as f:
        content = f.read()
    if "voxlink.signaling.degraded_badge" in content:
        print("[skip] {} already has key".format(code))
        continue
    lines = content.split("\n")
    out = []
    inserted = False
    for line in lines:
        out.append(line)
        if '"voxlink.signaling.degraded"' in line and not inserted:
            indent = line[: len(line) - len(line.lstrip())]
            out.append(indent + '"voxlink.signaling.degraded_badge": "{}",'.format(text))
            inserted = True
    if not inserted:
        print("[FAIL] {} anchor line not found".format(code))
        continue
    with io.open(path, "w", encoding="utf-8", newline="") as f:
        f.write("\n".join(out))
    print("[ok] {}".format(code))

# 全量 JSON 语法校验
import json
for code in BADGES:
    path = os.path.join(LANG_DIR, code + ".json")
    try:
        with io.open(path, "r", encoding="utf-8") as f:
            json.load(f)
        print("[valid] {}".format(code))
    except Exception as e:
        print("[INVALID] {}: {}".format(code, e))
