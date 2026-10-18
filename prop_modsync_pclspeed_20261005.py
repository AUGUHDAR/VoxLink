# -*- coding: utf-8 -*-
"""ModSync 第二轮（PCL 提速+CF免key+四分区UI）28 模板传播
1) ModrinthClient/ModSyncEntry/ModSyncIconCache/ModSyncResolver/CurseForgeClient 整拷
2) ModSyncSelectScreen 三处改造（字段/构造拆组/buildLines正则替换/方法块替换）
3) lang：新增 incompatible/notfound/notes_header，删除 diff/unresolvable_header
"""
import subprocess, sys, json, re
from pathlib import Path

BASE = Path(r"D:\桌面\voxlink")
REF = BASE / "fabric" / "1.20.2_1.20.6"
MODSYNC = "src/main/java/icu/wuhui/voxlink/modsync"

templates = []
for grp in ["fabric", "forge", "neoforge"]:
    for d in sorted((BASE / grp).iterdir()):
        if d.is_dir() and not d.name.startswith("_"):
            templates.append(d)

COPY_FILES = ["ModrinthClient.java", "ModSyncEntry.java", "ModSyncIconCache.java",
              "ModSyncResolver.java", "CurseForgeClient.java"]

def rd(p): return p.read_bytes().decode("utf-8")
def wr(p, s): p.write_bytes(s.encode("utf-8"))

# ---------- SelectScreen ----------
OLD_FIELDS = (
    "   private boolean resolveStarted = false;\n"
    "   private volatile boolean resolving = false;\n"
    "   /** 下载期间到达的回流结果：回到 SELECTING 时补入。 */\n"
    "   private final List<ModSyncEntry[]> pendingResolved = new ArrayList<>();"
)
NEW_FIELDS = (
    "   private boolean resolveStarted = false;\n"
    "   private volatile boolean resolving = false;\n"
    "   /** MR 有项目但无本端兼容版本的条目（版本不匹配组）。 */\n"
    "   private final List<ModSyncEntry> incompatibleEntries = new ArrayList<>();\n"
    "   /** 下载期间到达的回流结果 {原条目, 解析条目, bucket}：回到 SELECTING 时补入。 */\n"
    "   private final List<Object[]> pendingResolved = new ArrayList<>();"
)

OLD_CTOR = (
    "      this.onProceed = onProceed;\n"
    "      this.onCancel = onCancel;\n"
    "      this.buildLines();"
)
NEW_CTOR = (
    "      this.onProceed = onProceed;\n"
    "      this.onCancel = onCancel;\n"
    "      // 手动条目按已知项目拆组：清单已知项目=版本不匹配组，其余=找不到组\n"
    "      for (int i = this.manualEntries.size() - 1; i >= 0; i--) {\n"
    "         ModSyncEntry me = this.manualEntries.get(i);\n"
    "         if (!me.projectId.isEmpty() || !me.downloadUrl.isEmpty()) {\n"
    "            this.manualEntries.remove(i);\n"
    "            this.incompatibleEntries.add(me);\n"
    "         }\n"
    "      }\n"
    "\n"
    "      this.buildLines();"
)

NEW_BUILDLINES_BODY = """      this.lines.clear();
      if (!this.downloadable.isEmpty()) {
        this.lines.add(new Object[]{0, Component.translatable("voxlink.modsync.missing_header", this.downloadable.size()).getString()});
         for (ModSyncEntry e : this.downloadable) {
            this.addEntryCard(e);
         }
      }

      if (!this.incompatibleEntries.isEmpty()) {
         this.lines.add(new Object[]{0, Component.translatable("voxlink.modsync.incompatible_header").getString()});
         for (ModSyncEntry e : this.incompatibleEntries) {
            this.addEntryCard(e);
         }
      }

      if (!this.manualEntries.isEmpty()) {
         this.lines.add(new Object[]{0, Component.translatable("voxlink.modsync.notfound_header").getString()});
         for (ModSyncEntry e : this.manualEntries) {
            this.addEntryCard(e);
         }
      }

      if (!this.unresolvable.isEmpty() || !this.versionDiff.isEmpty()) {
         this.lines.add(new Object[]{0, Component.translatable("voxlink.modsync.notes_header").getString()});
         for (String u : this.unresolvable) {
            this.lines.add(new Object[]{1, cleanText(u)});
         }

         for (String d : this.versionDiff) {
            this.lines.add(new Object[]{1, cleanText(d)});
         }
      }"""

# 方法块（round-1 状态，28 模板一致）→ round-2
OLD_METHODS = (
    "   /** 后台 MR 兜底解析手动条目：真名/真图标原地更新，可下载者回流，按钮随之出现。 */\n"
    "   private void startResolve() {\n"
    "      this.resolving = true;\n"
    "      List<ModSyncEntry> snapshot = new ArrayList<>(this.manualEntries);\n"
    "      ModSyncResolver.resolveAsync(snapshot, new ModSyncResolver.Callback() {\n"
    "         @Override\n"
    "         public void onResolved(ModSyncEntry original, ModSyncEntry downloadable) {\n"
    "            Minecraft.getInstance().execute(() -> ModSyncSelectScreen.this.applyResolved(original, downloadable));\n"
    "         }\n"
    "\n"
    "         @Override\n"
    "         public void onFinished(int resolvedCount) {\n"
    "            ModSyncLog.info(\"resolve finished, {} became downloadable\", resolvedCount);\n"
    "            ModSyncSelectScreen.this.resolving = false;\n"
    "         }\n"
    "      });\n"
    "   }\n"
    "\n"
    "   private void applyResolved(ModSyncEntry original, ModSyncEntry downloadable) {\n"
    "      if (this.state != State.SELECTING) {\n"
    "         if (downloadable != null) {\n"
    "            this.pendingResolved.add(new ModSyncEntry[]{original, downloadable});\n"
    "         }\n"
    "\n"
    "         return;\n"
    "      }\n"
    "\n"
    "      if (downloadable == null) {\n"
    "         // 仅真名/真图标原地更新\n"
    "         this.buildLines();\n"
    "         this.rebuildInPlace();\n"
    "         return;\n"
    "      }\n"
    "\n"
    "      this.manualEntries.remove(original);\n"
    "      this.downloadable.add(downloadable);\n"
    "      this.buildLines();\n"
    "      this.rebuildInPlace();\n"
    "   }\n"
    "\n"
    "   private void applyPendingResolved() {\n"
    "      if (this.pendingResolved.isEmpty()) {\n"
    "         return;\n"
    "      }\n"
    "\n"
    "      for (ModSyncEntry[] pair : this.pendingResolved) {\n"
    "         this.manualEntries.remove(pair[0]);\n"
    "         this.downloadable.add(pair[1]);\n"
    "      }\n"
    "\n"
    "      this.pendingResolved.clear();\n"
    "      this.buildLines();\n"
    "   }"
)
NEW_METHODS = (
    "   /** 后台 MR 兜底解析手动条目：真名/真图标原地更新，可下载者回流，按钮随之出现。 */\n"
    "   private void startResolve() {\n"
    "      this.resolving = true;\n"
    "      List<ModSyncEntry> snapshot = new ArrayList<>(this.manualEntries);\n"
    "      snapshot.addAll(this.incompatibleEntries);\n"
    "      ModSyncResolver.resolveAsync(snapshot, new ModSyncResolver.Callback() {\n"
    "         @Override\n"
    "         public void onResolved(ModSyncEntry original, ModSyncEntry resolved, int bucket) {\n"
    "            Minecraft.getInstance().execute(() -> ModSyncSelectScreen.this.applyResolved(original, resolved, bucket));\n"
    "         }\n"
    "\n"
    "         @Override\n"
    "         public void onFinished(int resolvedCount) {\n"
    "            ModSyncLog.info(\"resolve finished, {} became downloadable\", resolvedCount);\n"
    "            ModSyncSelectScreen.this.resolving = false;\n"
    "         }\n"
    "      });\n"
    "   }\n"
    "\n"
    "   private void applyResolved(ModSyncEntry original, ModSyncEntry resolved, int bucket) {\n"
    "      if (this.state != State.SELECTING) {\n"
    "         if (bucket == ModSyncResolver.BUCKET_DOWNLOADABLE || resolved != null) {\n"
    "            this.pendingResolved.add(new Object[]{original, resolved, bucket});\n"
    "         }\n"
    "\n"
    "         return;\n"
    "      }\n"
    "\n"
    "      this.applyOne(original, resolved, bucket);\n"
    "      // 名称/图标原地更新与分组移动统一刷新\n"
    "      this.buildLines();\n"
    "      this.rebuildInPlace();\n"
    "   }\n"
    "\n"
    "   private void applyOne(ModSyncEntry original, ModSyncEntry resolved, int bucket) {\n"
    "      if (bucket == ModSyncResolver.BUCKET_DOWNLOADABLE && resolved != null) {\n"
    "         this.manualEntries.remove(original);\n"
    "         this.incompatibleEntries.remove(original);\n"
    "         this.downloadable.add(resolved);\n"
    "      } else if (bucket == ModSyncResolver.BUCKET_INCOMPATIBLE) {\n"
    "         this.manualEntries.remove(original);\n"
    "         if (!this.incompatibleEntries.contains(original)) {\n"
    "            this.incompatibleEntries.add(original);\n"
    "         }\n"
    "      }\n"
    "   }\n"
    "\n"
    "   private void applyPendingResolved() {\n"
    "      if (this.pendingResolved.isEmpty()) {\n"
    "         return;\n"
    "      }\n"
    "\n"
    "      for (Object[] p : this.pendingResolved) {\n"
    "         this.applyOne((ModSyncEntry)p[0], (ModSyncEntry)p[1], (Integer)p[2]);\n"
    "      }\n"
    "\n"
    "      this.pendingResolved.clear();\n"
    "      this.buildLines();\n"
    "   }"
)

def patch_sel(path, label):
    raw = path.read_bytes().decode("utf-8")
    crlf = "\r\n" in raw
    text = raw.replace("\r\n", "\n")
    if "incompatibleEntries" in text:
        return "skip"
    n0 = text.count(OLD_FIELDS); n1 = text.count(OLD_CTOR); n3 = text.count(OLD_METHODS)
    if n0 != 1 or n1 != 1 or n3 != 1:
        print(f"!! {label} anchors fields={n0} ctor={n1} methods={n3}")
        sys.exit(1)
    text = text.replace(OLD_FIELDS, NEW_FIELDS)
    text = text.replace(OLD_CTOR, NEW_CTOR)
    text = text.replace(OLD_METHODS, NEW_METHODS)
    # buildLines 方法体正则替换
    pat = re.compile(r"(   private void buildLines\(\) \{\n)(.*?)(\n   \}\n)", re.S)
    if len(pat.findall(text)) != 1:
        print(f"!! {label} buildLines match != 1")
        sys.exit(1)
    text = pat.sub(lambda m: m.group(1) + NEW_BUILDLINES_BODY + m.group(3), text, count=1)
    if "voxlink.modsync.diff_header" in text or "voxlink.modsync.unresolvable_header" in text:
        print(f"!! {label} 旧 header 残留")
        sys.exit(1)
    if crlf:
        text = text.replace("\n", "\r\n")
    path.write_bytes(text.encode("utf-8"))
    return "ok"

# ---------- lang ----------
NEW_KEYS = {
    "incompatible_header": {
        "en_us": "No files for your Minecraft version — install these manually:",
        "zh_cn": "以下模组没有适配你版本的文件，请手动下载安装：",
        "zh_hk": "以下模組沒有適配你版本的檔案，請手動下載安裝：",
        "zh_tw": "以下模組沒有適配你版本的檔案，請手動下載安裝：",
        "lzh": "下者無合爾版之檔，請自取：",
        "ja_jp": "あなたのバージョン用ファイルがなく、手動での導入が必要：",
        "ko_kr": "이 버전용 파일이 없어 직접 설치해야 합니다:",
        "de_de": "Keine Dateien für deine Version – bitte manuell laden:",
        "fr_fr": "Aucun fichier pour votre version — installation manuelle :",
        "es_es": "Sin archivos para tu versión: instálalos manualmente:",
        "pt_br": "Sem arquivos para a sua versão — instale manualmente:",
        "ru_ru": "Нет файлов для вашей версии — установите вручную:",
        "ar_sa": "لا توجد ملفات لإصدارك، ثبّتها يدويًا:",
    },
    "notfound_header": {
        "en_us": "Not on Modrinth — search these by name and install manually:",
        "zh_cn": "以下模组在 Modrinth 上找不到，请按名称手动搜索安装：",
        "zh_hk": "以下模組在 Modrinth 上找不到，請按名稱手動搜尋安裝：",
        "zh_tw": "以下模組在 Modrinth 上找不到，請按名稱手動搜尋安裝：",
        "lzh": "下者 Modrinth 所無，請自尋而裝：",
        "ja_jp": "Modrinth に見つからないため、名前で検索して手動導入を：",
        "ko_kr": "Modrinth에 없어 직접 찾아 설치해야 합니다:",
        "de_de": "Nicht auf Modrinth – bitte manuell suchen:",
        "fr_fr": "Introuvables sur Modrinth — recherchez-les manuellement :",
        "es_es": "No están en Modrinth: búscalos manualmente:",
        "pt_br": "Não estão no Modrinth — procure manualmente:",
        "ru_ru": "Нет на Modrinth — найдите и установите вручную:",
        "ar_sa": "غير موجودة على Modrinth، ابحث عنها يدويًا:",
    },
    "notes_header": {
        "en_us": "Notes:",
        "zh_cn": "提示：",
        "zh_hk": "提示：",
        "zh_tw": "提示：",
        "lzh": "示：",
        "ja_jp": "ヒント：",
        "ko_kr": "참고:",
        "de_de": "Hinweise:",
        "fr_fr": "À noter :",
        "es_es": "Aviso:",
        "pt_br": "Avisos:",
        "ru_ru": "Примечания:",
        "ar_sa": "ملاحظات:",
    },
}
REMOVE_KEYS = ["voxlink.modsync.diff_header", "voxlink.modsync.unresolvable_header"]

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

def patch_lang(t):
    langdir = t / "src/main/resources/assets/voxlink/lang"
    added = removed = 0
    for lf in sorted(langdir.glob("*.json")):
        wt = lf.read_bytes().decode("utf-8-sig")
        lang = lf.stem
        for rk in REMOVE_KEYS:
            pat = re.compile(r'^[ \t]*"' + re.escape(rk) + r'"[^\n]*\r?\n', re.M)
            if pat.search(wt):
                wt = pat.sub("", wt, count=1)
                removed += 1
        if '"voxlink.modsync.incompatible_header"' not in wt:
            for short, table in NEW_KEYS.items():
                key = "voxlink.modsync." + short
                if f'"{key}"' in wt:
                    continue
                val = table.get(lang) or table["en_us"]
                line = "    " + json.dumps(key, ensure_ascii=False) + ": " + json.dumps(val, ensure_ascii=False) + ","
                out = insert_after_key(wt, "voxlink.modsync.missing_header", line)
                if out is None:
                    print(f"  ?? {t.name}/{lf.name} {key}: 锚点缺失")
                    continue
                wt = out
                added += 1
        try:
            json.loads(wt)
        except Exception as ex:
            print(f"  !! {t.name}/{lf.name} JSON 损坏: {ex}")
            sys.exit(1)
        lf.write_bytes(wt.encode("utf-8"))
    return added, removed

def main():
    for t in templates:
        rel = t.relative_to(BASE).as_posix()
        if rel == "fabric/1.20.2_1.20.6":
            a, r = patch_lang(t)
            print(f"[{rel}] lang add={a} remove={r} (基准,java已手改)")
            continue
        for f in COPY_FILES:
            (t / MODSYNC / f).write_bytes((REF / MODSYNC / f).read_bytes())
        st = patch_sel(t / MODSYNC / "ModSyncSelectScreen.java", rel)
        a, r = patch_lang(t)
        print(f"[{rel}] java {st}, lang add={a} remove={r}")
    print("\nDONE")

if __name__ == "__main__":
    main()
