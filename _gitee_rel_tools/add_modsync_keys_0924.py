# -*- coding: utf-8 -*-
"""在 13 个语言文件的 voxlink.modsync.title 行后插入 title_all / checking_all key（幂等）。"""
import io
import os
import json

LANG_DIR = r"d:\桌面\voxlink\forge\26.3\src\main\resources\assets\voxlink\lang"

# (title_all, checking_all) 按 13 语言
TEXTS = {
    "zh_cn": ("房主模组同步检查（全部）", "正在获取房主全部模组..."),
    "zh_hk": ("房主模組同步檢查（全部）", "正在獲取房主全部模組..."),
    "zh_tw": ("房主模組同步檢查（全部）", "正在獲取房主全部模組..."),
    "en_us": ("Host mod sync check (all mods)", "Fetching all host mods..."),
    "ja_jp": ("ホストMod同期チェック（全部）", "ホストの全Modを取得中..."),
    "ko_kr": ("호스트 모드 동기화 확인 (전체)", "호스트의 모든 모드 가져오는 중..."),
    "fr_fr": ("Vérif. sync mods hôte (tous)", "Récupération de tous les mods de l'hôte..."),
    "de_de": ("Mod-Sync-Prüfung Host (alle)", "Alle Host-Mods werden geladen..."),
    "es_es": ("Comprobación de mods del anfitrión (todos)", "Obteniendo todos los mods del anfitrión..."),
    "ru_ru": ("Проверка модов хоста (все)", "Получение всех модов хоста..."),
    "pt_br": ("Verificação de mods do anfitrião (todos)", "Obtendo todos os mods do anfitrião..."),
    "ar_sa": ("فحص مزامنةmods المضيف (الكل)", "جارٍ جلب جميع mods المضيف..."),
    "lzh": ("核驗主人諸模（皆）", "方取主人諸模..."),
}

for code, (title_all, checking_all) in TEXTS.items():
    path = os.path.join(LANG_DIR, code + ".json")
    with io.open(path, "r", encoding="utf-8") as f:
        content = f.read()
    if "voxlink.modsync.title_all" in content:
        print("[skip] {}".format(code))
        continue
    lines = content.split("\n")
    out = []
    inserted = 0
    for line in lines:
        out.append(line)
        if '"voxlink.modsync.title"' in line and inserted == 0:
            indent = line[: len(line) - len(line.lstrip())]
            out.append(indent + '"voxlink.modsync.title_all": "{}",'.format(title_all))
            inserted += 1
        elif '"voxlink.modsync.checking"' in line and inserted == 1:
            indent = line[: len(line) - len(line.lstrip())]
            out.append(indent + '"voxlink.modsync.checking_all": "{}",'.format(checking_all))
            inserted += 1
    if inserted < 2:
        print("[FAIL] {} anchors found={}".format(code, inserted))
        continue
    with io.open(path, "w", encoding="utf-8", newline="") as f:
        f.write("\n".join(out))
    print("[ok] {}".format(code))

for code in TEXTS:
    path = os.path.join(LANG_DIR, code + ".json")
    try:
        with io.open(path, "r", encoding="utf-8") as f:
            json.load(f)
        print("[valid] {}".format(code))
    except Exception as e:
        print("[INVALID] {}: {}".format(code, e))
