# -*- coding: utf-8 -*-
# 2026-10-05 模组条改造传播: Entry/GuestService/SelectScreen 锚点替换 + lang 整拷
import io, os, re, shutil, sys

ROOT = r"D:\桌面\voxlink"
TMPL = []
for loader in ("fabric", "forge", "neoforge"):
    d = os.path.join(ROOT, loader)
    for v in sorted(os.listdir(d)):
        if not os.path.isdir(os.path.join(d, v, "src")):
            continue
        TMPL.append((loader + "/" + v, os.path.join(d, v)))

BASE = "fabric/1.20.2_1.20.6"
LANGS = ["ar_sa","de_de","en_us","es_es","fr_fr","ja_jp","ko_kr","lzh","pt_br","ru_ru","zh_cn","zh_hk","zh_tw"]

changed, failed = [], []

def rx(pat, flags=0):
    return re.compile(pat, flags)

# ---------- SelectScreen ----------
S = {}
S["fields"] = rx(r"(private final List<ModSyncEntry> downloadable;\n)(   private final List<String> unresolvable;)")
S["kinddoc"] = rx(r"/\*\* 虚拟条目：kind 0=区块头 1=普通行。 \*/")
S["btnfields"] = rx(r"(   private Button nextBtn;\n)")
S["colors"] = rx(r"(   private static final int MIN_ROWS = 3;\n)")
S["ctorparam"] = rx(r"(List<ModSyncEntry> downloadable,\n([ \t]*))List<String> unresolvable,")
S["ctorassign"] = rx(r"(this\.downloadable = downloadable;\n([ \t]*))this\.unresolvable = unresolvable;")
S["dlloop"] = rx(r"for \(ModSyncEntry e : this\.downloadable\) \{\s*this\.lines\.add\(new Object\[\]\{\s*1,\s*cleanText\(cleanText\(cleanText\(Component\.translatable\(\"voxlink\.modsync\.entry_line\", e\.title, e\.versionNumber, humanSize\(e\.size\)\)\.getString\(\)\)\)\)\s*\}\);\s*\}")
S["unres"] = rx(r"if \(!this\.unresolvable\.isEmpty\(\)\) \{\s*this\.lines\.add\(new Object\[\]\{0, Component\.translatable\(\"voxlink\.modsync\.unresolvable_header\"\)\.getString\(\)\}\);\s*for \(String u : this\.unresolvable\) \{\s*this\.lines\.add\(new Object\[\]\{1, cleanText\(u\)\}\);\s*\}\s*\}")
S["bldoc"] = rx(r"/\*\* 把三类信息拍平成虚拟行流，分页只面对这一种结构，杜绝重叠。 \*/")
S["cleandoc"] = rx(r"(/\*\*[\s*]*清洗待绘制文本)")
S["initbtn"] = rx(r"(if \(this\.state == State\.SELECTING\) \{\n)([ \t]*)(int by = this\.height - 54;)")
S["renderlist"] = rx(r"private void renderList\((GuiGraphics(?:Extractor)?) graphics, int centerX\) \{[\s\S]*?\n   \}\n\n   private void renderProgress")

NEW_RENDERLIST = (
   "private void renderList({TYPE} graphics, int centerX) {{\n"
   "      int listTop = 50;\n"
   "      int[] range = this.pageRange();\n"
   "      int iconX = 10;\n"
   "      int textX = iconX + 20;\n"
   "      int copyW = 36;\n"
   "      int copyX = this.width - 8 - copyW;\n"
   "      int y = listTop;\n"
   "      for (int i = range[0]; i < range[1]; i++) {{\n"
   "         Object[] line = this.lines.get(i);\n"
   "         int kind = ((Integer)line[0]);\n"
   "         String text = String.valueOf(line[1]);\n"
   "         if (kind == 0) {{\n"
   "            this.drawCenteredClipped(graphics, text, centerX, y, VoxLinkColors.WARNING);\n"
   "         }} else if (kind == 1) {{\n"
   "            this.drawCenteredClipped(graphics, text, centerX, y, VoxLinkColors.WHITE);\n"
   "         }} else if (kind == 2) {{\n"
   "            ModSyncEntry e = (ModSyncEntry)line[2];\n"
   "            // 离线图标：确定性色块+首字母（手动安装条目无 MR 元数据可取真图）\n"
   "            String title = text.isEmpty() ? cleanText(e.fileName) : text;\n"
   "            int hue = Math.floorMod(title.hashCode(), CARD_COLORS.length);\n"
   "            graphics.fill(iconX, y + 3, iconX + 16, y + 19, CARD_COLORS[hue]);\n"
   "            String initial = title.isEmpty() ? \"?\" : title.substring(0, 1).toUpperCase(java.util.Locale.ROOT);\n"
   "            var mcFont = Minecraft.getInstance().font;\n"
   "            this.drawString(graphics, initial, iconX + 8 - mcFont.width(initial) / 2, y + 7, 0xFFFFFFFF);\n"
   "            this.drawString(graphics, clipToWidth(title, copyX - textX - 6), textX, y + 2, 0xFFFFFFFF);\n"
   "         }} else if (kind == 3) {{\n"
   "            this.drawString(graphics, clipToWidth(text, this.width - 8 - textX), textX, y + 2, VoxLinkColors.MUTED);\n"
   "         }}\n"
   "\n"
   "         y += ROW_H;\n"
   "      }}\n"
   "   }}\n"
   "\n"
   "   /** 当前页的行区间：模组条占两行(kind2/3)，切割必须落在条边界上。 */\n"
   "   private int[] pageRange() {{\n"
   "      int from = this.page * this.rowsPerPage;\n"
   "      int to = Math.min(this.lines.size(), from + this.rowsPerPage);\n"
   "      if (to < this.lines.size() && this.kindAt(to - 1) == 3) {{\n"
   "         to--;\n"
   "      }}\n"
   "\n"
   "      if (from > 0 && from < this.lines.size() && this.kindAt(from) == 3) {{\n"
   "         from++;\n"
   "      }}\n"
   "\n"
   "      if (from >= to && from >= 2) {{\n"
   "         from -= 2;\n"
   "      }}\n"
   "\n"
   "      return new int[]{{from, Math.max(from, to)}};\n"
   "   }}\n"
   "\n"
   "   private int kindAt(int idx) {{\n"
   "      return idx >= 0 && idx < this.lines.size() ? ((Integer)this.lines.get(idx)[0]) : -1;\n"
   "   }}\n"
   "\n"
   "   /** 为当前页的每个模组条生成复制按钮（按钮只占自己的条行，绝不压底部按钮区）。 */\n"
   "   private void buildCopyButtons() {{\n"
   "      this.copyButtons.clear();\n"
   "      this.copiedResetTick = 0;\n"
   "      this.copiedBtn = null;\n"
   "      int[] range = this.pageRange();\n"
   "      int copyW = 36;\n"
   "      int copyX = this.width - 8 - copyW;\n"
   "      int y = 50;\n"
   "      for (int i = range[0]; i < range[1]; i++) {{\n"
   "         Object[] line = this.lines.get(i);\n"
   "         if (((Integer)line[0]) == 2) {{\n"
   "            ModSyncEntry e = (ModSyncEntry)line[2];\n"
   "            String copyText = String.valueOf(line[1]).isEmpty() ? e.fileName : String.valueOf(line[1]);\n"
   "            Button b = Button.builder(Component.translatable(\"voxlink.modsync.copy\"), btn -> {{\n"
   "               Minecraft.getInstance().keyboardHandler.setClipboard(copyText);\n"
   "               btn.setMessage(Component.translatable(\"voxlink.modsync.copied\"));\n"
   "               btn.active = false;\n"
   "               this.copiedBtn = btn;\n"
   "               this.copiedResetTick = 60;\n"
   "            }}).bounds(copyX, y + 1, copyW, 18).build();\n"
   "            this.copyButtons.add(b);\n"
   "            this.addRenderableWidget(b);\n"
   "         }}\n"
   "\n"
   "         y += ROW_H;\n"
   "      }}\n"
   "   }}\n"
   "\n"
   "   @Override\n"
   "   public void tick() {{\n"
   "      super.tick();\n"
   "      if (this.copiedResetTick > 0 && --this.copiedResetTick == 0 && this.copiedBtn != null) {{\n"
   "         this.copiedBtn.setMessage(Component.translatable(\"voxlink.modsync.copy\"));\n"
   "         this.copiedBtn.active = true;\n"
   "         this.copiedBtn = null;\n"
   "      }}\n"
   "   }}\n"
   "\n"
   "   /** 左对齐按像素宽截断（超宽补省略号）。 */\n"
   "   static String clipToWidth(String s, int maxW) {{\n"
   "      if (s == null || s.isEmpty()) {{\n"
   "         return \"\";\n"
   "      }}\n"
   "\n"
   "      var font = Minecraft.getInstance().font;\n"
   "      if (font.width(s) <= maxW) {{\n"
   "         return s;\n"
   "      }}\n"
   "\n"
   "      String ellipsis = \"…\";\n"
   "      while (!s.isEmpty() && font.width(s + ellipsis) > maxW) {{\n"
   "         s = s.substring(0, s.length() - 1);\n"
   "      }}\n"
   "\n"
   "      return s + ellipsis;\n"
   "   }}\n"
   "\n"
   "   private void renderProgress"
)

def apply_select(tdir, gtype):
    p = os.path.join(tdir, "src", "main", "java", "icu", "wuhui", "voxlink", "modsync", "ModSyncSelectScreen.java")
    s = io.open(p, encoding="utf-8", newline="").read()
    nl = "\r\n" if "\r\n" in s else "\n"
    s = s.replace("\r\n", "\n")
    def rep1(m, s, repl):
        if not m:
            return None
        return s[:m.start()] + repl + s[m.end():]

    m = S["fields"].search(s)
    if not m: return "S-fields"
    s = s[:m.start()] + m.group(1) + "   private final List<ModSyncEntry> manualEntries;\n" + m.group(2) + s[m.end():]

    m = S["kinddoc"].search(s)
    if not m: return "S-kinddoc"
    s = s[:m.start()] + "/** 虚拟条目：kind 0=区块头 1=普通行 2=模组条名行(携带entry) 3=模组条小字行。 */" + s[m.end():]

    m = S["btnfields"].search(s)
    if not m: return "S-btnfields"
    s = s[:m.end()] + "   private final List<Button> copyButtons = new ArrayList<>();\n   private int copiedResetTick = 0;\n   private Button copiedBtn = null;\n" + s[m.end():]

    m = S["colors"].search(s)
    if not m: return "S-colors"
    ins = ("   /** 模组条离线图标色板：按名称哈希取色，低饱和 MC 风格。 */\n"
           "   private static final int[] CARD_COLORS = {\n"
           "      0xFF4A6FA5, 0xFF5B8C5A, 0xFFB07D3F, 0xFF8A5BA5, 0xFFA55B5B, 0xFF3F8C8C\n"
           "   };\n")
    s = s[:m.end()] + ins + s[m.end():]

    m = S["ctorparam"].search(s)
    if not m: return "S-ctorparam"
    s = s[:m.start()] + m.group(1) + m.group(2) + "List<ModSyncEntry> manualEntries,\n   " + s[m.end():]

    m = S["ctorassign"].search(s)
    if not m: return "S-ctorassign"
    s = s[:m.start()] + m.group(1) + m.group(2) + "this.manualEntries = manualEntries;\n   " + s[m.end():]

    m = S["dlloop"].search(s)
    if not m: return "S-dlloop"
    s = s[:m.start()] + "for (ModSyncEntry e : this.downloadable) {\n            this.addEntryCard(e);\n         }" + s[m.end():]

    m = S["unres"].search(s)
    if not m: return "S-unres"
    s = (s[:m.start()] +
      "if (!this.manualEntries.isEmpty() || !this.unresolvable.isEmpty()) {\n"
      "         this.lines.add(new Object[]{0, Component.translatable(\"voxlink.modsync.unresolvable_header\").getString()});\n"
      "         for (ModSyncEntry e : this.manualEntries) {\n"
      "            this.addEntryCard(e);\n"
      "         }\n"
      "\n"
      "         for (String u : this.unresolvable) {\n"
      "            this.lines.add(new Object[]{1, cleanText(u)});\n"
      "         }\n"
      "      }") + s[m.end():]

    m = S["bldoc"].search(s)
    if not m: return "S-bldoc"
    s = s[:m.start()] + "/** 三类信息拍平成虚拟行流分页渲染；模组条占两行(kind2/3)。 */" + s[m.end():]

    m = S["cleandoc"].search(s)
    if not m: return "S-cleandoc"
    addcard = (
      "   /** 模组条 = 名行(图标/名称/复制按钮) + 小字行(jar 原名·版本·大小)。 */\n"
      "   private void addEntryCard(ModSyncEntry e) {\n"
      "      String sub = cleanText(e.fileName);\n"
      "      if (!e.versionNumber.isEmpty()) {\n"
      "         sub = e.versionNumber + \" · \" + sub;\n"
      "      }\n"
      "\n"
      "      if (e.size > 0L) {\n"
      "         sub = sub + \" · \" + humanSize(e.size);\n"
      "      }\n"
      "\n"
      "      this.lines.add(new Object[]{2, cleanText(e.title), e});\n"
      "      this.lines.add(new Object[]{3, sub});\n"
      "   }\n"
      "\n")
    s = s[:m.start()] + addcard + m.group(1) + s[m.end():]

    m = S["initbtn"].search(s)
    if not m: return "S-initbtn"
    s = s[:m.start()] + m.group(1) + m.group(2) + "this.buildCopyButtons();\n" + m.group(2) + m.group(3) + s[m.end():]

    m = S["renderlist"].search(s)
    if not m: return "S-renderlist"
    newseg = NEW_RENDERLIST.replace("{TYPE}", gtype).replace("{{", "{").replace("}}", "}")
    s = s[:m.start()] + newseg + s[m.end():]

    with io.open(p, "w", encoding="utf-8", newline="") as fh:
        fh.write(s.replace("\n", nl))
    return None

# ---------- GuestService ----------
G = {}
G["field"] = rx(r"(public final List<ModSyncEntry> downloadable = new ArrayList<>\(\);\n)([ \t]*)(public final List<String> unresolvable = new ArrayList<>\(\);)")
G["log"] = rx(r"\"guest diff: downloadable=\{\} versionDiff=\{\} unresolvable=\{\} titles=\{\}\",\s*new Object\[\]\{diff\.downloadable\.size\(\), diff\.versionDiff\.size\(\), diff\.unresolvable\.size\(\),\s*ModSyncSelectScreen\.clip\(joinTitles\(diff\), 200\)\}\s*\);\s*if \(diff\.downloadable\.isEmpty\(\) && diff\.unresolvable\.isEmpty\(\) && diff\.versionDiff\.isEmpty\(\)\) \{")
G["call"] = rx(r"(diff\.downloadable,\n([ \t]*))diff\.unresolvable,")
G["unknown"] = rx(r"// 未知模组：房主侧在 MR 查不到的 jar，仅提示（我们没有任何渠道让房客下到它们）\s*if \(manifest\.has\(\"unknownMods\"\) && manifest\.get\(\"unknownMods\"\)\.isJsonArray\(\)\) \{\s*for \(var el : manifest\.getAsJsonArray\(\"unknownMods\"\)\) \{\s*if \(el\.isJsonPrimitive\(\)\) \{\s*out\.unresolvable\.add\(el\.getAsString\(\)\);\s*\}\s*\}\s*\}")
G["compat"] = rx(r"// 房主的构建版本与本端 MC/加载器不一致且不允许再查 MR —— 转手动安装提示\s*out\.unresolvable\.add\(entry\.title \+ \" \(\" \+ entry\.versionNumber \+ \"\)\"\);")
G["join"] = rx(r"(sb\.append\(e\.title\)\.append\('@'\)\.append\(e\.versionNumber\);\s*\}\n\n)([ 	]*for \(String s : diff\.versionDiff\) \{)")

def apply_guest(tdir):
    p = os.path.join(tdir, "src", "main", "java", "icu", "wuhui", "voxlink", "modsync", "ModSyncGuestService.java")
    s = io.open(p, encoding="utf-8", newline="").read()
    s = s.replace("\r\n", "\n")

    m = G["field"].search(s)
    if not m: return "G-field"
    s = s[:m.start()] + m.group(1) + m.group(2) + "/** 需手动安装的条目（MR 未收录/兼容性不符）：模组条展示+复制。 */\n   " + m.group(3) + s[m.end():]

    m = G["log"].search(s)
    if not m: return "G-log"
    s = (s[:m.start()] +
      "\"guest diff: downloadable={} versionDiff={} manual={} unresolvable={} titles={}\",\n"
      "               new Object[]{diff.downloadable.size(), diff.versionDiff.size(), diff.manualEntries.size(), diff.unresolvable.size(),\n"
      "                  ModSyncSelectScreen.clip(joinTitles(diff), 200)}\n"
      "            );\n"
      "            if (diff.downloadable.isEmpty() && diff.manualEntries.isEmpty() && diff.unresolvable.isEmpty() && diff.versionDiff.isEmpty()) {") + s[m.end():]

    m = G["call"].search(s)
    if not m: return "G-call"
    s = s[:m.start()] + m.group(1) + m.group(2) + "diff.manualEntries,\n   " + s[m.end():]

    m = G["unknown"].search(s)
    if not m: return "G-unknown"
    s = (s[:m.start()] +
      "// 未知模组：房主侧在 MR 查不到的 jar，无自动下载渠道 → 结构化条目（模组条展示/复制）\n"
      "      if (manifest.has(\"unknownMods\") && manifest.get(\"unknownMods\").isJsonArray()) {\n"
      "         for (var el : manifest.getAsJsonArray(\"unknownMods\")) {\n"
      "            if (el.isJsonPrimitive()) {\n"
      "               out.manualEntries.add(ModSyncEntry.fromFileName(el.getAsString()));\n"
      "            }\n"
      "         }\n"
      "      }") + s[m.end():]

    m = G["compat"].search(s)
    if not m: return "G-compat"
    s = (s[:m.start()] +
      "// 房主的构建版本与本端 MC/加载器不一致且不允许再查 MR —— 转手动安装条目\n"
      "            out.manualEntries.add(entry);") + s[m.end():]

    m = G["join"].search(s)
    if not m: return "G-join"
    s = (s[:m.start()] + m.group(1) +
      "      for (ModSyncEntry e : diff.manualEntries) {\n"
      "         if (n++ > 0) {\n"
      "            sb.append(\", \");\n"
      "         }\n"
      "\n"
      "         sb.append('?').append(e.title);\n"
      "      }\n"
      "\n"
      + m.group(2)) + s[m.end():]

    with io.open(p, "w", encoding="utf-8", newline="") as fh:
        fh.write(s)
    return None

# ---------- Entry ----------
E_ANCHOR = rx(r"(      s = s\.replace\('-', ' '\)\.replace\('_', ' '\)\.trim\(\);\n      return s;\n   \}\n\n)(   public JsonObject toJson\(\) \{)")
E_NEW = (
   "   /** unknownMods 文件名 → 手动安装条目：无可下载信息，仅名称/原名供展示与复制。 */\n"
   "   public static ModSyncEntry fromFileName(String fileName) {\n"
   "      ModSyncEntry e = new ModSyncEntry();\n"
   "      e.fileName = fileName == null ? \"\" : fileName;\n"
   "      String guess = guessTitleFromFileName(e.fileName);\n"
   "      if (guess.isEmpty()) {\n"
   "         String bare = e.fileName;\n"
   "         int i = bare.toLowerCase(java.util.Locale.ROOT).lastIndexOf(\".jar\");\n"
   "         if (i > 0) {\n"
   "            bare = bare.substring(0, i);\n"
   "         }\n"
   "\n"
   "         guess = bare;\n"
   "      }\n"
   "\n"
   "      e.title = guess;\n"
   "      return e;\n"
   "   }\n"
   "\n"
)

def gtype_of(t):
    if "26.1" in t or "26.2" in t or "26.3" in t:
        return "GuiGraphicsExtractor"
    return "GuiGraphics"

for name, tdir in TMPL:
    if name == BASE:
        continue
    try:
        sel_p = os.path.join(tdir, 'src', 'main', 'java', 'icu', 'wuhui', 'voxlink', 'modsync', 'ModSyncSelectScreen.java')
        already_sel = 'manualEntries' in io.open(sel_p, encoding='utf-8').read()
        e1 = None if already_sel else apply_select(tdir, gtype_of(name))
        e2 = apply_guest(tdir)
        # Entry
        p = os.path.join(tdir, "src", "main", "java", "icu", "wuhui", "voxlink", "modsync", "ModSyncEntry.java")
        s = io.open(p, encoding="utf-8", newline="").read().replace("\r\n", "\n")
        if "fromFileName" in s:
            e3 = None
            m = None
        else:
            m = E_ANCHOR.search(s)
        if not m and e3 is None:
            e3 = None
        elif not m:
            e3 = "E-anchor"
        else:
            s = s[:m.start()] + m.group(1) + E_NEW + m.group(2) + s[m.end():]
            with io.open(p, "w", encoding="utf-8", newline="") as fh:
                fh.write(s)
            e3 = None
        bad = [x for x in (e1, e2, e3) if x]
        if bad:
            failed.append((name, ",".join(bad)))
        else:
            # lang 整拷
            src_lang = os.path.join(ROOT, BASE, "src", "main", "resources", "assets", "voxlink", "lang")
            dst_lang = os.path.join(tdir, "src", "main", "resources", "assets", "voxlink", "lang")
            for lf in LANGS:
                shutil.copyfile(os.path.join(src_lang, lf + ".json"), os.path.join(dst_lang, lf + ".json"))
            changed.append(name)
    except Exception as ex:
        failed.append((name, "EXC:" + str(ex)[:80]))

print("CHANGED", len(changed))
print("FAILED", len(failed))
for c, r in failed: print("  !", c, r)
sys.exit(1 if failed else 0)
