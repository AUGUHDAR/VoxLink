# -*- coding: utf-8 -*-
"""把 fabric/26.3 的两处 UI 修复传播到其余 27 个模板

改动一 FeedbackFormScreen：新增引导三行 + 去掉 tips_title 绘制 + 提示行数常量
改动二 VoxLinkScreen   ：官网/反馈行不再被陶瓦下载挤掉（三级降级 + 常驻行）

策略：锚点正则替换（避开 graphics API 类名差异），每个模板逐个报告。
"""
import os
import re

BASE = r"D:\桌面\voxlink"
LANGS = None
SRC = ("fabric", "26.3")

NL = r"\r?\n"


def load(p):
    with open(p, "r", encoding="utf-8", newline="") as f:
        return f.read()


def save(p, t):
    with open(p, "w", encoding="utf-8", newline="") as f:
        f.write(t)


# ===== FeedbackFormScreen =====
FB_PAIRS = [
    ("常量", re.compile(r'   private static final int MAX_TIP_EXAMPLES = 4;'),
     '   private static final int MAX_TIP_EXAMPLES = 3;\n'
     '   /** 提示区固定行数：引导标题 + 建议反馈 + 问题反馈 + 写作引导 */\n'
     '   private static final int FIXED_TIP_LINES = 4;'),

    ("drawTips 注释", re.compile(r'   /\*\* 发前自检提示与示例 \*/'),
     '   /** 引导：这里既能提建议也能报问题；其后是原有的写作引导与示例 */'),

    ("drawTips 主体", re.compile(
        r'      int y = this\.tipsY;' + NL +
        r'      this\.drawCenteredClipped\(graphics, Component\.translatable\("voxlink\.fb\.tips_title"\)\.getString\(\), centerX, y, VoxLinkColors\.WARNING\);' + NL +
        r'      y \+= TIP_LINE_H;' + NL +
        r'      this\.drawCenteredClipped\(graphics, Component\.translatable\("voxlink\.fb\.tips_body"\)\.getString\(\), centerX, y, VoxLinkColors\.GRAY\);' + NL +
        r'      y \+= TIP_LINE_H;'),
     '      int y = this.tipsY;\n'
     '      this.drawCenteredClipped(graphics, Component.translatable("voxlink.fb.guide_title").getString(), centerX, y, VoxLinkColors.WARNING);\n'
     '      y += TIP_LINE_H;\n'
     '      this.drawCenteredClipped(graphics, Component.translatable("voxlink.fb.guide_sug").getString(), centerX, y, VoxLinkColors.GRAY);\n'
     '      y += TIP_LINE_H;\n'
     '      this.drawCenteredClipped(graphics, Component.translatable("voxlink.fb.guide_bug").getString(), centerX, y, VoxLinkColors.GRAY);\n'
     '      y += TIP_LINE_H;\n'
     '      this.drawCenteredClipped(graphics, Component.translatable("voxlink.fb.tips_body").getString(), centerX, y, VoxLinkColors.GRAY);\n'
     '      y += TIP_LINE_H;'),

    ("computeLayout 行数", re.compile(r'\(2 \+ this\.tipExamples\) \* TIP_LINE_H'),
     '(FIXED_TIP_LINES + this.tipExamples) * TIP_LINE_H'),
]

# ===== VoxLinkScreen =====
VL_PAIRS = [
    ("topRows", re.compile(
        r'      int topRows = currentRoom == null && !isInSingleplayerWorld\(\) \? 3 : 1;' + NL +
        r'      topRows \+= 1; // 官网按钮恒为最后一行'),
     '      int topRows = currentRoom == null && !isInSingleplayerWorld() ? 2 : 1;'),

    ("for 循环", re.compile(
        r'      for \(int attempt = 0; attempt < 2; attempt\+\+\) \{' + NL +
        r'         int back = this\.height - L_BOTTOM_MARGIN;' + NL +
        r'         int relay = back - L_STEP;' + NL +
        r'         int uploadLog = relay - L_STEP;' + NL +
        r'         boolean decor = currentRoom == null && attempt == 0;' + NL +
        r'         int config = decor \? uploadLog - L_STEP - L_DECOR_H : uploadLog - L_STEP;' + NL +
        r'         int downloadRow = showDownloadRow \? config - L_STEP : Integer\.MIN_VALUE;'),
     '      // 降级顺序：0) 带装饰带  1) 去装饰带  2) 去装饰带且未装陶瓦时再砍配置行\n'
     '      int maxAttempt = showDownloadRow ? 2 : 1;\n'
     '      for (int attempt = 0; attempt <= maxAttempt; attempt++) {\n'
     '         int back = this.height - L_BOTTOM_MARGIN;\n'
     '         int relay = back - L_STEP;\n'
     '         int uploadLog = relay - L_STEP;\n'
     '         boolean decor = currentRoom == null && attempt == 0;\n'
     '         boolean dropConfig = attempt >= 2 && showDownloadRow;\n'
     '         int config = dropConfig ? Integer.MIN_VALUE\n'
     '               : (decor ? uploadLog - L_STEP - L_DECOR_H : uploadLog - L_STEP);\n'
     '         int stackBase = dropConfig ? uploadLog : config;\n'
     '         int downloadRow = showDownloadRow ? stackBase - L_STEP : Integer.MIN_VALUE;'),

    ("stackTop/limit/ideal", re.compile(
        r'         int stackTop = showDownloadRow \? downloadRow : config;' + NL +
        r'         int reserve = \(downloading \? L_PROGRESS_RESERVE : 0\) \+ \(platformNote \? L_PLATFORM_NOTE_RESERVE : 0\);' + NL +
        r'         int limit = stackTop - reserve;' + NL +
        r'         int ideal = Math\.min\(this\.height / 2 - 30, limit - sectionH\);' + NL +
        r'         if \(ideal >= headerFloor \|\| attempt == 1\) \{'),
     '         int stackTop = showDownloadRow ? downloadRow : stackBase;\n'
     '         // 官网/反馈行是常驻入口：恒占底部栈之上的一行，任何分辨率都不裁掉\n'
     '         int websiteRow = stackTop - L_STEP;\n'
     '         int reserve = (downloading ? L_PROGRESS_RESERVE : 0) + (platformNote ? L_PLATFORM_NOTE_RESERVE : 0);\n'
     '         int limit = Math.min(stackTop - reserve, websiteRow - L_GAP);\n'
     '         int ideal = Math.min(this.height / 2 - 30, limit - sectionH);\n'
     '         if (ideal >= headerFloor || attempt == maxAttempt) {'),

    ("lyWebsite 裁剪", re.compile(
        r'            this\.lyWebsite = this\.lyTopStart \+ sectionH - L_ROW;' + NL +
        r'            // 极矮屏强制提交时官网行会与下载行同位叠合（240 高实测同 y=116）：' + NL +
        r'            // 裁掉官网行保下载行——下载是更关键的 CTA（与"装饰先裁"同一准则）' + NL +
        r'            if \(showDownloadRow && this\.lyDownloadRow != Integer\.MIN_VALUE && this\.lyWebsite >= this\.lyDownloadRow - L_ROW\) \{' + NL +
        r'               this\.lyWebsite = Integer\.MIN_VALUE;' + NL +
        r'            \}'),
     '            // 官网行优先紧贴底部栈；若顶部组被兜底抬高，则顺延到顶部组之下（宁可紧凑，绝不隐藏）\n'
     '            this.lyWebsite = Math.max(websiteRow, this.lyTopStart + sectionH - L_ROW + L_STEP);'),

    ("platformNote", re.compile(r'               this\.lyPlatformNote = config - 14;'),
     '               this.lyPlatformNote = stackBase - 14;'),

    ("config 按钮常驻判断", re.compile(
        r'      this\.addRenderableWidget\(' + NL +
        r'         Button\.builder\(Component\.translatable\("voxlink\.terracotta\.config"\), ([^\n]*?)\)' + NL +
        r'            \.bounds\(centerX - 100, this\.lyConfig, 200, 20\)' + NL +
        r'            \.build\(\)' + NL +
        r'      \);'),
     lambda m: ('      if (this.lyConfig != Integer.MIN_VALUE) {\n'
                '         this.addRenderableWidget(\n'
                '            Button.builder(Component.translatable("voxlink.terracotta.config"), ' + m.group(1) + ')\n'
                '               .bounds(centerX - 100, this.lyConfig, 200, 20)\n'
                '               .build()\n'
                '         );\n'
                '      }')),
]


def run(name, pairs, files):
    print(f"\n===== {name} =====")
    tot_ok = 0
    for p in files:
        t = load(p)
        results = []
        for tag, pat, rep in pairs:
            if isinstance(rep, str):
                new, n = pat.subn(lambda m, r=rep: r, t, count=1)
                err = n == 0
            else:
                new, n = pat.subn(rep, t, count=1)
                err = n == 0
            if n > 0:
                t = new
            results.append((tag, n))
        failed = [tag for tag, n in results if n == 0]
        if failed:
            print(f"  [部分失败] {p.replace(BASE + chr(92), '')} -> 未匹配: {failed}")
        else:
            tot_ok += 1
        save(p, t)
    print(f"  全部锚点命中：{tot_ok} 个模板")


targets = []
for loader in ("fabric", "forge", "neoforge"):
    root = os.path.join(BASE, loader)
    for ver in sorted(os.listdir(root)):
        if not os.path.isdir(os.path.join(root, ver)):
            continue
        if (loader, ver) == SRC:
            continue
        targets.append((loader, ver))

fb_files = [os.path.join(BASE, l, v, "src", "main", "java", "icu", "wuhui", "voxlink", "ui", "FeedbackFormScreen.java")
            for l, v in targets]
vl_files = [os.path.join(BASE, l, v, "src", "main", "java", "icu", "wuhui", "voxlink", "ui", "VoxLinkScreen.java")
            for l, v in targets]
fb_files = [p for p in fb_files if os.path.isfile(p)]
vl_files = [p for p in vl_files if os.path.isfile(p)]

print(f"目标模板数：FeedbackFormScreen {len(fb_files)} 个，VoxLinkScreen {len(vl_files)} 个")
run("FeedbackFormScreen", FB_PAIRS, fb_files)
run("VoxLinkScreen", VL_PAIRS, vl_files)
