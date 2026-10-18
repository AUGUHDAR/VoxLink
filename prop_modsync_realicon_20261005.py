# -*- coding: utf-8 -*-
"""ModSync 真名真图标+一键下载回流 28 模板传播（2026-10-05）
1) ModrinthClient/ModSyncEntry/ModSyncIconCache/ModSyncResolver 整拷（同构文件）
2) ModSyncSelectScreen 七处锚点编辑（簇分叉保留）
3) lang 逐模板合并：恢复被整拷覆盖丢的且仍被引用的 key + 新增 resolving
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

COPY_FILES = ["ModrinthClient.java", "ModSyncEntry.java", "ModSyncIconCache.java", "ModSyncResolver.java"]

def rd(p): return p.read_bytes().decode("utf-8")
def wr(p, s): p.write_bytes(s.encode("utf-8"))

def patch_anchors(text, edits, label):
    """edits: list of (old, new)。逐条断言 count==1 后替换。"""
    for i, (old, new) in enumerate(edits):
        c = text.count(old)
        if c != 1:
            print(f"!! {label} edit#{i} anchor count={c}"); sys.exit(1)
        text = text.replace(old, new)
    return text

def sel_edits():
    e = []
    # E1 字段
    e.append((
        "   private final List<Button> copyButtons = new ArrayList<>();\n   private int copiedResetTick = 0;\n   private Button copiedBtn = null;",
        "   private final List<Button> copyButtons = new ArrayList<>();\n   private int copiedResetTick = 0;\n   private Button copiedBtn = null;\n"
        "   private boolean resolveStarted = false;\n   private volatile boolean resolving = false;\n"
        "   /** 下载期间到达的回流结果：回到 SELECTING 时补入。 */\n"
        "   private final List<ModSyncEntry[]> pendingResolved = new ArrayList<>();",
    ))
    # E2 init 启动解析
    e.append((
        "         this.buildCopyButtons();",
        "         this.buildCopyButtons();\n"
        "         if (!this.resolveStarted && !this.manualEntries.isEmpty()) {\n"
        "            this.resolveStarted = true;\n"
        "            this.startResolve();\n"
        "         }\n",
    ))
    # E3 图标渲染
    old_icon = (
        "            ModSyncEntry e = (ModSyncEntry)line[2];\n"
        "            // 离线图标：确定性色块+首字母（手动安装条目无 MR 元数据可取真图）\n"
        "            String title = text.isEmpty() ? cleanText(e.fileName) : text;\n"
        "            int hue = Math.floorMod(title.hashCode(), CARD_COLORS.length);\n"
        "            graphics.fill(iconX, y + 3, iconX + 16, y + 19, CARD_COLORS[hue]);\n"
        "            String initial = title.isEmpty() ? \"?\" : title.substring(0, 1).toUpperCase(java.util.Locale.ROOT);\n"
        "            var mcFont = Minecraft.getInstance().font;\n"
        "            this.drawString(graphics, initial, iconX + 8 - mcFont.width(initial) / 2, y + 7, 0xFFFFFFFF);"
    )
    new_icon = (
        "            ModSyncEntry e = (ModSyncEntry)line[2];\n"
        "            String title = text.isEmpty() ? cleanText(e.fileName) : text;\n"
        "            int[][] iconRuns = ModSyncIconCache.runs(e.iconUrl);\n"
        "            if (iconRuns == null && !e.iconUrl.isEmpty()) {\n"
        "               ModSyncIconCache.prefetch(e.iconUrl);\n"
        "            }\n"
        "\n"
        "            if (iconRuns != null) {\n"
        "               for (int[] run : iconRuns) {\n"
        "                  graphics.fill(iconX + run[0], y + 3 + run[1], iconX + run[0] + run[2], y + 4 + run[1], run[3]);\n"
        "               }\n"
        "            } else {\n"
        "               // 兜底：确定性色块+首字母\n"
        "               int hue = Math.floorMod(title.hashCode(), CARD_COLORS.length);\n"
        "               graphics.fill(iconX, y + 3, iconX + 16, y + 19, CARD_COLORS[hue]);\n"
        "               String initial = title.isEmpty() ? \"?\" : title.substring(0, 1).toUpperCase(java.util.Locale.ROOT);\n"
        "               var mcFont = Minecraft.getInstance().font;\n"
        "               this.drawString(graphics, initial, iconX + 8 - mcFont.width(initial) / 2, y + 7, 0xFFFFFFFF);\n"
        "            }"
    )
    e.append((old_icon, new_icon))
    # E4 abortThrough
    e.append((
        "      this.state = State.SELECTING;\n      this.statusText = Component.translatable(\"voxlink.modsync.download_cancelled\").getString();",
        "      this.state = State.SELECTING;\n      this.applyPendingResolved();\n      this.statusText = Component.translatable(\"voxlink.modsync.download_cancelled\").getString();",
    ))
    # E5 failThrough
    e.append((
        "   private void failThrough(String msg) {\n      this.state = State.SELECTING;",
        "   private void failThrough(String msg) {\n      this.state = State.SELECTING;\n      this.applyPendingResolved();",
    ))
    # E6 新方法（tick 前）
    new_methods = (
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
        "   }\n"
        "\n"
        "   @Override\n"
        "   public void tick() {"
    )
    e.append(("   @Override\n   public void tick() {", new_methods))
    # E7 解析中提示
    e.append((
        "         if (!this.statusText.isEmpty()) {\n"
        "            this.drawCenteredClipped(graphics, this.statusText, centerX, Math.min(this.listBottomY + 2, this.height - 86), this.statusColor);\n"
        "         }",
        "         if (!this.statusText.isEmpty()) {\n"
        "            this.drawCenteredClipped(graphics, this.statusText, centerX, Math.min(this.listBottomY + 2, this.height - 86), this.statusColor);\n"
        "         } else if (this.resolving) {\n"
        "            this.drawCenteredClipped(graphics, Component.translatable(\"voxlink.modsync.resolving\").getString(), centerX, Math.min(this.listBottomY + 2, this.height - 86), VoxLinkColors.MUTED);\n"
        "         }",
    ))
    return e

def patch_crlf_safe(path, edits, label):
    raw = path.read_bytes().decode("utf-8")
    crlf = "\r\n" in raw
    text = raw.replace("\r\n", "\n")
    text = patch_anchors(text, edits, label)
    if crlf:
        text = text.replace("\n", "\r\n")
    path.write_bytes(text.encode("utf-8"))

# ---------- lang ----------
RESOLVING = {
    "en_us": "Matching mods on Modrinth…",
    "zh_cn": "正在 Modrinth 上识别模组…",
    "zh_hk": "正在 Modrinth 上識別模組…",
    "zh_tw": "正在 Modrinth 上識別模組…",
    "lzh": "方於 Modrinth 檢模組…",
    "ja_jp": "Modrinth でモッドを照合中…",
    "ko_kr": "Modrinth에서 모드 확인 중…",
    "de_de": "Mods werden auf Modrinth gesucht…",
    "fr_fr": "Recherche des mods sur Modrinth…",
    "es_es": "Buscando mods en Modrinth…",
    "pt_br": "Procurando mods no Modrinth…",
    "ru_ru": "Ищем моды на Modrinth…",
    "ar_sa": "جارٍ البحث عن المودات في Modrinth…",
}

def git_head(path):
    rel = path.resolve().relative_to(BASE.resolve()).as_posix()
    r = subprocess.run(["git", "show", f"HEAD:{rel}"], cwd=BASE, capture_output=True)
    return r.stdout.decode("utf-8-sig") if r.returncode == 0 else ""

def head_line_for(head_text, key):
    m = re.search(r'^([ \t]*)"' + re.escape(key) + r'"\s*:\s*.*$', head_text, re.M)
    if not m:
        return None
    line = m.group(0).rstrip()
    if not line.endswith(","):
        line += ","
    return "    " + line.strip()

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

def restore_lang(t):
    langdir = t / "src/main/resources/assets/voxlink/lang"
    srcs = (t / "src/main/java").rglob("*.java")
    all_src = "\n".join(p.read_text(encoding="utf-8", errors="replace") for p in srcs)
    keys = []
    if '"voxlink.loader."' in all_src:
        keys += ["voxlink.loader.fabric", "voxlink.loader.forge", "voxlink.loader.neoforge", "voxlink.loader.unknown"]
    if '"voxlink.game_type."' in all_src:
        keys += ["voxlink.game_type.survival", "voxlink.game_type.creative", "voxlink.game_type.adventure", "voxlink.game_type.spectator"]
    if t.relative_to(BASE).as_posix() == "forge/26.3":
        keys += ["voxlink.modsync.checking_all", "voxlink.modsync.title_all", "voxlink.signaling.degraded_badge"]
    if not keys:
        return 0, 0
    restored = added = 0
    for lf in sorted(langdir.glob("*.json")):
        wt = lf.read_bytes().decode("utf-8-sig")
        head = git_head(lf)
        for k in keys:
            if f'"{k}"' in wt:
                continue
            line = head_line_for(head, k) if head else None
            if line is None:
                print(f"  ?? {t.name}/{lf.name} {k}: HEAD 无此行，跳过")
                continue
            anchor = {"voxlink.loader.fabric": "voxlink.loader.all"}.get(k)
            if anchor is None:
                # 用 HEAD 中该 key 的前一个仍存在于工作区的 key 作锚
                anchor = None
                hk = re.findall(r'^[ \t]*"([^"]+)"\s*:', head, re.M)
                idx = hk.index(k) if k in hk else -1
                for j in range(idx - 1, -1, -1):
                    if f'"{hk[j]}"' in wt:
                        anchor = hk[j]; break
            if anchor is None:
                print(f"  ?? {t.name}/{lf.name} {k}: 无锚点，跳过")
                continue
            out = insert_after_key(wt, anchor, line)
            if out is None:
                print(f"  ?? {t.name}/{lf.name} {k}: 锚点行缺失 {anchor}")
                continue
            wt = out
            restored += 1
        # 新 key
        if '"voxlink.modsync.resolving"' not in wt:
            lang = lf.stem
            val = RESOLVING.get(lang) or RESOLVING["en_us"]
            line = '    "voxlink.modsync.resolving": ' + json.dumps(val, ensure_ascii=False) + ","
            anchor = "voxlink.modsync.copied" if '"voxlink.modsync.copied"' in wt else "voxlink.modsync.unresolvable_header"
            out = insert_after_key(wt, anchor, line)
            if out is None:
                print(f"  ?? {t.name}/{lf.name} resolving: 锚点缺失")
            else:
                wt = out
                added += 1
        # 校验 JSON 合法
        try:
            json.loads(wt)
        except Exception as ex:
            print(f"  !! {t.name}/{lf.name} JSON 损坏: {ex}")
            sys.exit(1)
        lf.write_bytes(wt.encode("utf-8"))
    return restored, added

def main():
    sel = sel_edits()
    n_java = n_lang_r = n_lang_a = 0
    for t in templates:
        rel = t.relative_to(BASE).as_posix()
        if rel == "fabric/1.20.2_1.20.6":
            # 基准：只补 lang（java 已手改）
            r, a = restore_lang(t)
            n_lang_r += r; n_lang_a += a
            print(f"[{rel}] lang restore={r} add={a} (基准,java已手改)")
            continue
        # 1) 同构文件整拷
        for f in COPY_FILES:
            src = REF / MODSYNC / f
            dst = t / MODSYNC / f
            dst.write_bytes(src.read_bytes())
        # 2) SelectScreen 锚点（已打过补丁则跳过，保证重跑幂等）
        sel_path = t / MODSYNC / "ModSyncSelectScreen.java"
        if "ModSyncResolver.resolveAsync" in sel_path.read_text(encoding="utf-8"):
            n_java += 1
        else:
            patch_crlf_safe(sel_path, sel, rel)
            n_java += 1
        # 3) lang
        r, a = restore_lang(t)
        n_lang_r += r; n_lang_a += a
        print(f"[{rel}] java+4copy+7edit, lang restore={r} add={a}")
    print(f"\nDONE templates={len(templates)} selectScreenPatched={n_java} langRestored={n_lang_r} langAdded={n_lang_a}")

if __name__ == "__main__":
    main()
