# -*- coding: utf-8 -*-
"""ModSync 第三轮：网络失败重试+放宽搜索+cap192+scope标题/文案统一
1) 整拷 ModrinthClient/ModSyncResolver（其余3文件未变顺带统一）
2) 锚点：ManifestService cap、SelectScreen scope、GuestService 传参、AttemptingJoinScreen 文案
3) lang：resolving/notfound 双平台措辞 + title_all/checking_all（值取自 forge/26.3）
"""
import sys, json, re
from pathlib import Path

BASE = Path(r"D:\桌面\voxlink")
REF = BASE / "fabric" / "1.20.2_1.20.6"
F263 = BASE / "forge" / "26.3"
MODSYNC = "src/main/java/icu/wuhui/voxlink/modsync"

templates = []
for grp in ["fabric", "forge", "neoforge"]:
    for d in sorted((BASE / grp).iterdir()):
        if d.is_dir() and not d.name.startswith("_"):
            templates.append(d)

COPY_FILES = ["ModrinthClient.java", "ModSyncResolver.java", "CurseForgeClient.java", "ModSyncIconCache.java"]

RESOLVING = {
    "en_us": "Matching mods on Modrinth / CurseForge…",
    "zh_cn": "正在匹配模组（Modrinth / CurseForge）…",
    "zh_hk": "正在匹配模組（Modrinth / CurseForge）…",
    "zh_tw": "正在匹配模組（Modrinth / CurseForge）…",
    "lzh": "方於 Modrinth、CurseForge 檢模組…",
    "ja_jp": "Modrinth / CurseForge でモッドを照合中…",
    "ko_kr": "Modrinth / CurseForge에서 모드 확인 중…",
    "de_de": "Mods werden auf Modrinth / CurseForge gesucht…",
    "fr_fr": "Recherche des mods sur Modrinth / CurseForge…",
    "es_es": "Buscando mods en Modrinth / CurseForge…",
    "pt_br": "Procurando mods no Modrinth / CurseForge…",
    "ru_ru": "Ищем моды на Modrinth / CurseForge…",
    "ar_sa": "جارٍ البحث عن المودات في Modrinth / CurseForge…",
}
NOTFOUND = {
    "en_us": "Not found on Modrinth or CurseForge — search and install manually:",
    "zh_cn": "以下模组在 Modrinth 和 CurseForge 上都找不到，请手动搜索安装：",
    "zh_hk": "以下模組在 Modrinth 和 CurseForge 上找不到，請手動搜尋安裝：",
    "zh_tw": "以下模組在 Modrinth 和 CurseForge 上找不到，請手動搜尋安裝：",
    "lzh": "下者 Modrinth、CurseForge 皆無，請自尋：",
    "ja_jp": "Modrinth にも CurseForge にも見つかりません。手動で探して導入を：",
    "ko_kr": "Modrinth와 CurseForge에 모두 없어 직접 찾아 설치해야 합니다:",
    "de_de": "Nicht auf Modrinth oder CurseForge – bitte manuell suchen:",
    "fr_fr": "Introuvables sur Modrinth et CurseForge — recherchez-les manuellement :",
    "es_es": "No están en Modrinth ni CurseForge: búscalos manualmente:",
    "pt_br": "Não estão no Modrinth nem no CurseForge — procure manualmente:",
    "ru_ru": "Нет ни на Modrinth, ни на CurseForge — найдите и установите вручную:",
    "ar_sa": "غير موجودة على Modrinth أو CurseForge، ابحث عنها يدويًا:",
}

def patch_text(path, label, fn):
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

def sub1(pattern, repl, s, label, flags=0):
    pat = re.compile(pattern, flags)
    n = len(pat.findall(s))
    if n != 1:
        print(f"!! {label} pattern x{n}: {pattern[:60]}")
        sys.exit(1)
    return pat.sub(repl, s, count=1)

def patch_manifest(s):
    if "UNKNOWN_MODS_CAP = 192" in s:
        return s, False
    s = sub1(r"UNKNOWN_MODS_CAP = 64;", "UNKNOWN_MODS_CAP = 192;", s, "cap")
    return s, True

def patch_sel(s):
    if '"voxlink.modsync.title_all"' in s and "String scope," in s:
        return s, False
    s = sub1(r"(   public ModSyncSelectScreen\(\n      String roomCode,\n)",
             r"\1      String scope,\n", s, "sel-ctor")
    s = sub1(r'      super\(Component\.translatable\("voxlink\.modsync\.title"\)\);',
             '      // 标题按 scope 区分：必装检查 / 房主全部模组\n'
             '      super(Component.translatable(\n'
             '         ModSyncManifestService.SCOPE_ALL.equals(scope) ? "voxlink.modsync.title_all" : "voxlink.modsync.title"));',
             s, "sel-super")
    return s, True

def patch_guest(s):
    if re.search(r"new ModSyncSelectScreen\(\s*\n\s*roomCode,\s*\n\s*fetchScope,", s):
        return s, False
    s = sub1(r"(new ModSyncSelectScreen\(\n)(\s*)roomCode,", r"\1\2fetchScope,\n\2roomCode,", s, "gs-call")
    return s, True

def patch_join(s):
    if "voxlink.modsync.checking_all" in s:
        return s, False
    s = sub1(r'( *)this\.voxlinkStatusText = Component\.translatable\("voxlink\.modsync\.checking"\)\.getString\(\);',
             lambda m: (f'{m.group(1)}// 文案按 scope 区分：必装 / 全部\n'
                        f'{m.group(1)}this.voxlinkStatusText = Component.translatable(\n'
                        f'{m.group(1)}   "all".equals(scope) ? "voxlink.modsync.checking_all" : "voxlink.modsync.checking").getString();'),
             s, "join-check")
    return s, True

def f263_value(lang, key):
    p = F263 / "src/main/resources/assets/voxlink/lang" / f"{lang}.json"
    try:
        d = json.loads(p.read_text(encoding="utf-8-sig"))
        return d.get(key)
    except Exception:
        return None

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

def patch_lang(t):
    langdir = t / "src/main/resources/assets/voxlink/lang"
    ch = 0
    for lf in sorted(langdir.glob("*.json")):
        wt = lf.read_bytes().decode("utf-8-sig")
        lang = lf.stem
        # 值替换
        for key, table in [("voxlink.modsync.resolving", RESOLVING), ("voxlink.modsync.notfound_header", NOTFOUND)]:
            val = table.get(lang) or table["en_us"]
            out = replace_value_line(wt, key, val)
            if out is None:
                print(f"  ?? {t.name}/{lf.name} {key} 缺行")
                continue
            if out != wt:
                wt = out
                ch += 1
        # 新键（值取 forge/26.3 既有翻译）
        for key, anchor in [("voxlink.modsync.title_all", "voxlink.modsync.title"),
                            ("voxlink.modsync.checking_all", "voxlink.modsync.checking")]:
            if f'"{key}"' in wt:
                continue
            val = f263_value(lang, key)
            if val is None:
                val = f263_value("en_us", key)
            if val is None:
                print(f"  ?? {t.name}/{lf.name} {key} 无源值")
                continue
            line = "    " + json.dumps(key, ensure_ascii=False) + ": " + json.dumps(val, ensure_ascii=False) + ","
            out = insert_after_key(wt, anchor, line)
            if out is None:
                print(f"  ?? {t.name}/{lf.name} {key} 锚点缺失 {anchor}")
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
        is_f263 = rel == "forge/26.3"
        if not is_ref:
            for f in COPY_FILES:
                (t / MODSYNC / f).write_bytes((REF / MODSYNC / f).read_bytes())
        r1 = patch_text(t / MODSYNC / "ModSyncManifestService.java", rel, patch_manifest)
        r2 = "skip(ref/f263 已有)" if (is_ref or is_f263) else patch_text(t / MODSYNC / "ModSyncSelectScreen.java", rel, patch_sel)
        r3 = "skip(已有)" if is_f263 else patch_text(t / MODSYNC / "ModSyncGuestService.java", rel, patch_guest)
        r4 = "skip(已有)" if is_f263 else patch_text(t / "src/main/java/icu/wuhui/voxlink/ui/AttemptingJoinScreen.java", rel, patch_join)
        n = patch_lang(t)
        print(f"[{rel}] manifest:{r1} sel:{r2} guest:{r3} join:{r4} lang:{n}")
    print("\nDONE")

if __name__ == "__main__":
    main()
