# -*- coding: utf-8 -*-
"""1.1.8 反馈引导文案修订（客户端 28 模板 × 13 语言 + 网页端 13 语言）

规则（严格遵守用户裁决）：
  - 只「删」用户点名的两处：tips_title（审视自己）、tips_ex4（走 Issues）
  - 只「加」三处新引导：guide_title / guide_sug / guide_bug
  - 其余原有文案（tips_body、tips_ex1~ex3）一字不动
"""
import json
import os

BASE = r"D:\桌面\voxlink"
LANGS = ["en_us", "zh_cn", "zh_tw", "zh_hk", "ja_jp", "ko_kr",
         "fr_fr", "de_de", "es_es", "ru_ru", "pt_br", "ar_sa", "lzh"]

NEW = {
    "guide_title": {
        "en_us": "You can post suggestions and problems here",
        "zh_cn": "你可以在这里反馈你的建议和问题",
        "zh_tw": "你可以在這裡回報你的建議和問題",
        "zh_hk": "你可以喺呢度回報你嘅建議同問題",
        "ja_jp": "ここで提案も不具合も報告できます",
        "ko_kr": "여기에서 제안과 문제를 모두 알려주세요",
        "fr_fr": "Vous pouvez ici proposer des idées et signaler des problèmes",
        "de_de": "Hier kannst du Vorschläge und Probleme melden",
        "es_es": "Aquí puedes enviar sugerencias y problemas",
        "ru_ru": "Здесь можно оставить и предложения, и проблемы",
        "pt_br": "Aqui você pode enviar sugestões e problemas",
        "ar_sa": "يمكنك هنا إرسال اقتراحاتك ومشاكلك",
        "lzh": "凡建議與問難，皆可陳於此",
    },
    "guide_sug": {
        "en_us": "Suggestion: e.g. \"Could you add feature XX?\"",
        "zh_cn": "建议反馈：比如「能不能做 XX 功能」",
        "zh_tw": "建議回報：例如「能不能做 XX 功能」",
        "zh_hk": "建議回報：例如「可唔可以做 XX 功能」",
        "ja_jp": "提案：例「○○の機能を追加できますか」",
        "ko_kr": "제안: 예) \"XX 기능을 만들어 주실 수 있나요?\"",
        "fr_fr": "Suggestion : p. ex. « Pouvez-vous ajouter la fonction XX ? »",
        "de_de": "Vorschlag: z. B. „Könnt ihr Funktion XX einbauen?“",
        "es_es": "Sugerencia: p. ej. «¿Podrían añadir la función XX?»",
        "ru_ru": "Предложение: например «Сделайте функцию XX»",
        "pt_br": "Sugestão: ex. \"Vocês podem adicionar a função XX?\"",
        "ar_sa": "الاقتراحات: مثل «هل يمكن إضافة ميزة كذا؟»",
        "lzh": "建議者：如「可增某能乎」",
    },
    "guide_bug": {
        "en_us": "Problem: e.g. \"Could you fix this bug?\" — describe the cause and how to reproduce, tick the log option or attach logs",
        "zh_cn": "问题反馈：比如「能不能修修这个 bug」——描述问题原因与复现过程，最好勾选或附带日志",
        "zh_tw": "問題回報：例如「能不能修修這個 bug」——描述問題原因與重現過程，最好勾選或附上日誌",
        "zh_hk": "問題回報：例如「可唔可以修修呢個 bug」——描述問題原因同重現過程，最好勾選或附上日誌",
        "ja_jp": "不具合：例「このバグを直せますか」——原因と再現手順を書き、ログの添付がおすすめ",
        "ko_kr": "문제: 예) \"이 버그를 고쳐 주실 수 있나요?\" — 원인과 재현 과정을 적고, 로그 첨부를 권장",
        "fr_fr": "Problème : p. ex. « Pouvez-vous corriger ce bug ? » — décrivez la cause et la reproduction, joignez les logs",
        "de_de": "Problem: z. B. „Könnt ihr diesen Bug beheben?“ — nenne Ursache und Reproduktion, Logs gern anhängen",
        "es_es": "Problema: p. ej. «¿Podrían arreglar este bug?» — describe la causa y cómo reproducirlo, adjunta los registros",
        "ru_ru": "Проблема: например «Исправьте этот баг» — опишите причину и шаги воспроизведения, приложите логи",
        "pt_br": "Problema: ex. \"Vocês podem corrigir este bug?\" — descreva a causa e como reproduzir, anexe os logs",
        "ar_sa": "المشاكل: مثل «هل يمكن إصلاح هذا الخطأ؟» — اذكر السبب وخطوات إعادة الحدوث، ويُفضّل إرفاق السجلات",
        "lzh": "問難者：如「可修此疵乎」——當述其因與重現之序，宜附日誌",
    },
}


def patch_file(path, prefix, stats):
    """prefix: 'voxlink.fb.'（客户端）或 'fb.'（网页端）"""
    with open(path, "r", encoding="utf-8") as f:
        lines = f.readlines()
    text = "".join(lines)
    if f'"{prefix}guide_title"' in text:
        stats["skip"] += 1
        return

    out, removed, inserted = [], 0, False
    for ln in lines:
        s = ln.strip()
        if s.startswith(f'"{prefix}tips_title"') or s.startswith(f'"{prefix}tips_ex4"'):
            removed += 1
            continue
        if not inserted and s.startswith(f'"{prefix}tips_body"'):
            indent = ln[: len(ln) - len(ln.lstrip())]
            for k in ("guide_title", "guide_sug", "guide_bug"):
                val = json.dumps(NEW[k][lang], ensure_ascii=False)
                out.append(f'{indent}"{prefix}{k}": {val},\n')
            inserted = True
        out.append(ln)

    if not inserted:
        stats["fail"] += 1
        print(f"  [失败] 未找到锚点 tips_body: {path}")
        return

    new_text = "".join(out)
    json.loads(new_text)          # 合法性校验，不合法直接抛错不落盘
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write(new_text)
    stats["ok"] += 1
    stats["removed"] += removed


stats = {"ok": 0, "skip": 0, "fail": 0, "removed": 0}

print("=== 客户端：28 模板 × 13 语言 ===")
for loader in ("fabric", "forge", "neoforge"):
    root = os.path.join(BASE, loader)
    for ver in sorted(os.listdir(root)):
        d = os.path.join(root, ver, "src", "main", "resources", "assets", "voxlink", "lang")
        if not os.path.isdir(d):
            continue
        for lang in LANGS:
            p = os.path.join(d, lang + ".json")
            if os.path.isfile(p):
                patch_file(p, "voxlink.fb.", stats)

print(f"  客户端完成：修改 {stats['ok']} / 跳过 {stats['skip']} / 失败 {stats['fail']}，共删 {stats['removed']} 行")

print("\n=== 网页端：server-go/web/lang × 13 语言 ===")
web = os.path.join(BASE, "server-go", "web", "lang")
wstats = {"ok": 0, "skip": 0, "fail": 0, "removed": 0}
if os.path.isdir(web):
    for lang in LANGS:
        p = os.path.join(web, lang + ".json")
        if os.path.isfile(p):
            patch_file(p, "fb.", wstats)
    print(f"  网页端完成：修改 {wstats['ok']} / 跳过 {wstats['skip']} / 失败 {wstats['fail']}，共删 {wstats['removed']} 行")
else:
    print("  [警告] 未找到 server-go/web/lang")
