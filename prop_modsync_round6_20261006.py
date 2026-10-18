# -*- coding: utf-8 -*-
"""第六轮：GuestService参数反序修复 + SelectScreen scope全文案 + Resolver搜索加强 + lang 5改2增"""
import sys, json, re
from pathlib import Path

BASE = Path(r"D:\桌面\voxlink")
REF = BASE / "fabric" / "1.20.2_1.20.6"
MODSYNC = "src/main/java/icu/wuhui/voxlink/modsync"

templates = []
for grp in ["fabric", "forge", "neoforge"]:
    for d in sorted((BASE / grp).iterdir()):
        if d.is_dir() and not d.name.startswith("_"):
            templates.append(d)

def patch(path, label, fn):
    raw = path.read_bytes().decode("utf-8")
    crlf = "\r\n" in raw
    s = raw.replace("\r\n", "\n")
    s2, changed = fn(s)
    if not changed:
        return "skip"
    if crlf:
        s2 = s2.replace("\n", "\r\n")
    path.write_bytes(s2.encode("utf-8"))
    return "ok"

def sub1(pattern, repl, s, label):
    pat = re.compile(pattern)
    n = len(pat.findall(s))
    if n != 1:
        print(f"!! {label} x{n}: {pattern[:60]}")
        sys.exit(1)
    return pat.sub(repl, s, count=1)

def fix_guest(s):
    if re.search(r"new ModSyncSelectScreen\(\n\s*roomCode,\n\s*fetchScope,", s):
        return s, False
    s = sub1(r"(new ModSyncSelectScreen\(\n)(\s*)fetchScope,\n\2roomCode,", r"\1\2roomCode,\n\2fetchScope,", s, "gs-swap")
    return s, True

def patch_sel(s):
    if "missing_header_all" in s and "this.scope = " in s:
        return s, False
    s = sub1(r"(   private final String roomCode;\n)", r"\1   private final String scope;\n", s, "sel-field")
    s = sub1(r"(      this\.roomCode = roomCode;\n)", r"\1      this.scope = scope == null ? \"\" : scope;\n", s, "sel-ctor")
    s = sub1(r"(      this\.lines\.clear\(\);\n)",
             r"\1      boolean allScope = ModSyncManifestService.SCOPE_ALL.equals(this.scope);\n", s, "sel-clear")
    s = sub1(r'this\.lines\.add\(new Object\[\]\{0, Component\.translatable\("voxlink\.modsync\.missing_header", this\.downloadable\.size\(\)\)\.getString\(\)\}\);',
             'this.lines.add(new Object[]{0, Component.translatable(\n'
             '            allScope ? "voxlink.modsync.missing_header_all" : "voxlink.modsync.missing_header",\n'
             '            this.downloadable.size()).getString()});', s, "sel-missing")
    s = sub1(r'this\.lines\.add\(new Object\[\]\{0, Component\.translatable\("voxlink\.modsync\.incompatible_header"\)\.getString\(\)\}\);',
             'this.lines.add(new Object[]{0, Component.translatable("voxlink.modsync.incompatible_header", this.incompatibleEntries.size()).getString()});', s, "sel-incompat")
    s = sub1(r'this\.lines\.add\(new Object\[\]\{0, Component\.translatable\("voxlink\.modsync\.notfound_header"\)\.getString\(\)\}\);',
             'this.lines.add(new Object[]{0, Component.translatable("voxlink.modsync.notfound_header", this.manualEntries.size()).getString()});', s, "sel-notfound")
    s = sub1(r'this\.drawCenteredClipped\(graphics, Component\.translatable\("voxlink\.modsync\.subtitle", this\.roomCode\)\.getString\(\), centerX, 24, VoxLinkColors\.MUTED\);',
             'this.drawCenteredClipped(graphics, Component.translatable(\n'
             '         ModSyncManifestService.SCOPE_ALL.equals(this.scope) ? "voxlink.modsync.subtitle_all" : "voxlink.modsync.subtitle",\n'
             '         this.roomCode).getString(), centerX, 24, VoxLinkColors.MUTED);', s, "sel-subtitle")
    return s, True

# ---------- lang ----------
T = {
 "subtitle_all": {
  "en_us": "Joining room %s: mods that differ from the host's",
  "zh_cn": "加入房间 %s：以下为与房主相差的模组",
  "zh_hk": "加入房間 %s：以下為與房主相差的模組",
  "zh_tw": "加入房間 %s：以下為與房主相差的模組",
  "lzh": "入房 %s：下者與房主異模組也",
  "ja_jp": "部屋 %s に参加：ホストと異なるモッド",
  "ko_kr": "방 %s 참여: 호스트와 다른 모드",
  "de_de": "Raum %s beitreten: Mods, die vom Host abweichen",
  "fr_fr": "Rejoindre le salon %s : mods différents de ceux de l'hôte",
  "es_es": "Uniéndote a la sala %s: mods que difieren de los del anfitrión",
  "pt_br": "Entrando na sala %s: mods que diferem dos do host",
  "ru_ru": "Вход в комнату %s: моды, отличающиеся от хоста",
  "ar_sa": "الانضمام إلى الغرفة %s: مودات تختلف عن مودات المضيف",
 },
 "missing_header_all": {
  "en_us": "Missing mods (%s):",
  "zh_cn": "缺失的模组（%s）：",
  "zh_hk": "缺失的模組（%s）：",
  "zh_tw": "缺失的模組（%s）：",
  "lzh": "缺模組（%s）：",
  "ja_jp": "不足しているモッド（%s）：",
  "ko_kr": "누락된 모드 (%s):",
  "de_de": "Fehlende Mods (%s):",
  "fr_fr": "Mods manquants (%s) :",
  "es_es": "Mods faltantes (%s):",
  "pt_br": "Mods faltando (%s):",
  "ru_ru": "Отсутствующие моды (%s):",
  "ar_sa": "مودات ناقصة (%s):",
 },
 "gate_failed_warn": {
  "en_us": "[VoxLink] Mod check failed, joining directly. Please verify your mods match.",
  "zh_cn": "[VoxLink] 模组检查失败，已直接加入，请自行确认 Mod 一致",
  "zh_hk": "[VoxLink] 模組檢查失敗，已直接加入，請自行確認模組一致",
  "zh_tw": "[VoxLink] 模組檢查失敗，已直接加入，請自行確認模組一致",
  "lzh": "[VoxLink] 模組之檢不成，已直入，請自核",
  "ja_jp": "[VoxLink] モッド確認に失敗したため直接参加しました。モッドを自分で確認してください。",
  "ko_kr": "[VoxLink] 모드 확인 실패, 바로 참여합니다. 모드를 직접 확인해 주세요.",
  "de_de": "[VoxLink] Mod-Prüfung fehlgeschlagen, direkter Beitritt. Bitte Mods selbst abgleichen.",
  "fr_fr": "[VoxLink] Échec de la vérification des mods, connexion directe. Vérifiez vos mods.",
  "es_es": "[VoxLink] Falló la revisión de mods, uniéndote directamente. Verifica tus mods.",
  "pt_br": "[VoxLink] Falha na checagem de mods, entrando direto. Confira seus mods.",
  "ru_ru": "[VoxLink] Проверка модов не удалась, вход напрямую. Сверьте моды вручную.",
  "ar_sa": "[VoxLink] فشل فحص المودات، تم الانضمام مباشرة. تحقق من موداتك.",
 },
 "progress_title": {
  "en_us": "Downloading mods", "zh_cn": "正在下载模组", "zh_hk": "正在下載模組", "zh_tw": "正在下載模組",
  "lzh": "方取模組", "ja_jp": "モッドをダウンロード中", "ko_kr": "모드 다운로드 중",
  "de_de": "Mods werden heruntergeladen", "fr_fr": "Téléchargement des mods",
  "es_es": "Descargando mods", "pt_br": "Baixando mods", "ru_ru": "Загрузка модов",
  "ar_sa": "جارٍ تنزيل المودات",
 },
 "restart_line1": {
  "en_us": "%s mod(s) downloaded into the mods folder.",
  "zh_cn": "已下载 %s 个模组到 mods 文件夹。",
  "zh_hk": "已下載 %s 個模組到 mods 資料夾。",
  "zh_tw": "已下載 %s 個模組到 mods 資料夾。",
  "lzh": "已取 %s 模組入 mods 夾。",
  "ja_jp": "%s 個のモッドを mods フォルダにダウンロードしました。",
  "ko_kr": "%s개 모드를 mods 폴더에 내려받았습니다.",
  "de_de": "%s Mod(s) in den mods-Ordner geladen.",
  "fr_fr": "%s mod(s) téléchargés dans le dossier mods.",
  "es_es": "%s mod(s) descargados en la carpeta mods.",
  "pt_br": "%s mod(s) baixados para a pasta mods.",
  "ru_ru": "%s мод(ов) загружено в папку mods.",
  "ar_sa": "تم تنزيل %s مود إلى مجلد mods.",
 },
 "incompatible_header": {
  "en_us": "No files for your Minecraft version (%s) — install manually:",
  "zh_cn": "以下模组没有适配你版本的文件（%s）：",
  "zh_hk": "以下模組沒有適配你版本的檔案（%s）：",
  "zh_tw": "以下模組沒有適配你版本的檔案（%s）：",
  "lzh": "下者無合爾版之檔（%s）：",
  "ja_jp": "あなたのバージョン用ファイルがありません（%s）。手動での導入が必要：",
  "ko_kr": "이 버전용 파일이 없습니다(%s). 직접 설치해야 합니다:",
  "de_de": "Keine Dateien für deine Version (%s) – manuell laden:",
  "fr_fr": "Aucun fichier pour votre version (%s) — installation manuelle :",
  "es_es": "Sin archivos para tu versión (%s): instálalos manualmente:",
  "pt_br": "Sem arquivos para a sua versão (%s) — instale manualmente:",
  "ru_ru": "Нет файлов для вашей версии (%s) — установите вручную:",
  "ar_sa": "لا توجد ملفات لإصدارك (%s)، ثبّتها يدويًا:",
 },
 "notfound_header": {
  "en_us": "Not found on Modrinth or CurseForge (%s) — search manually:",
  "zh_cn": "以下模组在 Modrinth 和 CurseForge 上都找不到（%s）：",
  "zh_hk": "以下模組在 Modrinth 和 CurseForge 上找不到（%s）：",
  "zh_tw": "以下模組在 Modrinth 和 CurseForge 上找不到（%s）：",
  "lzh": "下者 Modrinth、CurseForge 皆無（%s）：",
  "ja_jp": "Modrinth にも CurseForge にも見つかりません（%s）。手動で探して導入を：",
  "ko_kr": "Modrinth와 CurseForge에 모두 없습니다(%s). 직접 찾아 설치하세요:",
  "de_de": "Nicht auf Modrinth oder CurseForge (%s) – manuell suchen:",
  "fr_fr": "Introuvables sur Modrinth et CurseForge (%s) — recherche manuelle :",
  "es_es": "No en Modrinth ni CurseForge (%s): búscalos manualmente:",
  "pt_br": "Não no Modrinth nem CurseForge (%s) — procure manualmente:",
  "ru_ru": "Нет на Modrinth и CurseForge (%s) — найдите вручную:",
  "ar_sa": "غير موجودة على Modrinth أو CurseForge (%s)، ابحث يدويًا:",
 },
}

def insert_after_key(text, after_key, line):
    pat = re.compile(r'^[ \t]*"' + re.escape(after_key) + r'"\s*:', re.M)
    m = pat.search(text)
    if not m:
        return None
    nl_idx = text.find("\n", m.end())
    if nl_idx == -1:
        return None
    nl = "\r\n" if text[nl_idx - 1:nl_idx] == "\r" else "\n"
    return text[:nl_idx + 1] + line + nl + text[nl_idx + 1:]

def replace_value_line(text, key, newval):
    pat = re.compile(r'^([ \t]*)"' + re.escape(key) + r'"(\s*):\s*.*$', re.M)
    m = pat.search(text)
    if not m:
        return None
    line = m.group(1) + json.dumps(key, ensure_ascii=False) + m.group(2) + ": " + json.dumps(newval, ensure_ascii=False) + ","
    return text[:m.start()] + line + text[m.end():]

NEW_AFTER = {"subtitle_all": "voxlink.modsync.subtitle", "missing_header_all": "voxlink.modsync.missing_header"}
UPDATES = ["gate_failed_warn", "progress_title", "restart_line1", "incompatible_header", "notfound_header"]

def patch_lang(t):
    ch = 0
    for lf in sorted((t / "src/main/resources/assets/voxlink/lang").glob("*.json")):
        wt = lf.read_bytes().decode("utf-8-sig")
        lang = lf.stem
        for key in UPDATES:
            val = (T[key].get(lang) or T[key]["en_us"])
            out = replace_value_line(wt, "voxlink.modsync." + key, val)
            if out is None:
                print(f"  ?? {t.name}/{lf.name} {key} 缺行")
                continue
            if out != wt:
                wt = out
                ch += 1
        for key, anchor in NEW_AFTER.items():
            if f'"{ "voxlink.modsync." + key }"' in wt:
                continue
            val = T[key].get(lang) or T[key]["en_us"]
            line = "    " + json.dumps("voxlink.modsync." + key, ensure_ascii=False) + ": " + json.dumps(val, ensure_ascii=False) + ","
            out = insert_after_key(wt, anchor, line)
            if out is None:
                print(f"  ?? {t.name}/{lf.name} {key} 锚点缺失")
                continue
            wt = out
            ch += 1
        try:
            json.loads(wt)
        except Exception as ex:
            print(f"  !! {t.name}/{lf.name} JSON 损坏: {ex}")
            sys.exit(1)
        lf.write_bytes(wt.encode("utf-8"))
    return ch

def main():
    for t in templates:
        rel = t.relative_to(BASE).as_posix()
        is_ref = rel == "fabric/1.20.2_1.20.6"
        if not is_ref:
            (t / MODSYNC / "ModSyncResolver.java").write_bytes((REF / MODSYNC / "ModSyncResolver.java").read_bytes())
        r1 = "skip(本就正确)" if is_ref else patch(t / MODSYNC / "ModSyncGuestService.java", rel, fix_guest)
        r2 = "skip(已手改)" if is_ref else patch(t / MODSYNC / "ModSyncSelectScreen.java", rel, patch_sel)
        n = patch_lang(t)
        print(f"[{rel}] guest修复:{r1} sel:{r2} lang:{n}")
    print("\nDONE")

if __name__ == "__main__":
    main()
