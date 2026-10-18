# -*- coding: utf-8 -*-
"""工单客户端传播：3新文件整拷 + TitleScreenMixin/FeedbackScreen(方法体替换x2变体) + FeedbackFormScreen(3锚点)"""
import glob, hashlib, os, re, sys

BASE = r"d:\桌面\voxlink"
GOLD = os.path.join(BASE, "fabric", "26.3")
errors = []
done = []

def load(p):
    raw = open(p, "rb").read()
    eol = "\r\n" if b"\r\n" in raw else "\n"
    return raw.decode("utf-8").replace("\r\n", "\n"), eol

def save(p, t, eol):
    open(p, "wb").write(t.replace("\n", eol).encode("utf-8"))

def rep(f):
    return os.path.relpath(f, BASE)

# ---------- 1) 新文件整拷（27 模板） ----------
NEW_FILES = [
    os.path.join("network", "TicketClient.java"),
    os.path.join("ui", "TicketListScreen.java"),
    os.path.join("ui", "TicketDetailScreen.java"),
]
templates = []
for loader in ("fabric", "forge", "neoforge"):
    ldir = os.path.join(BASE, loader)
    if os.path.isdir(ldir):
        for grp in sorted(os.listdir(ldir)):
            if os.path.isdir(os.path.join(ldir, grp)):
                templates.append(os.path.join(loader, grp))

for rel in NEW_FILES:
    src = os.path.join(GOLD, "src", "main", "java", "icu", "wuhui", "voxlink", *rel.split(os.sep))
    data = open(src, "rb").read()
    for t in templates:
        if t == os.path.join("fabric", "26.3"):
            continue
        dst = os.path.join(BASE, t, "src", "main", "java", "icu", "wuhui", "voxlink", *rel.split(os.sep))
        open(dst, "wb").write(data)
    done.append("copy %s -> 27" % rel)

# ---------- 2) TitleScreenMixin 方法体替换 ----------
TSM_OLD = re.compile(r"   private void onInit\(CallbackInfo ci\) \{.*?\n   \}", re.S)

TSM_NEW_OLD_ERA = """   private void onInit(CallbackInfo ci) {
      icu.wuhui.voxlink.network.TicketClient.pollOnceIfNeeded(() -> {
         Minecraft mc = Minecraft.getInstance();
         mc.execute(() -> {
            if (icu.wuhui.voxlink.network.TicketClient.hasUnread()
               && mc.screen instanceof TitleScreen) {
               mc.setScreen(mc.screen);
            }
         });
      });
      int buttonWidth = Math.max(20, Math.min(50, this.width / 4 - 10));
      int x = Math.min(this.width / 2 + 104, this.width - buttonWidth - 10);
      this.addRenderableWidget(
         Button.builder(Component.translatable("voxlink.title"), button -> Minecraft.getInstance().setScreen(new VoxLinkScreen(this)))
            .bounds(x, this.height / 4 + 48, buttonWidth, 20)
            .build()
      );
      // 左上角工单未读弹窗：点击进我的工单
      if (icu.wuhui.voxlink.network.TicketClient.hasUnread()) {
         this.addRenderableWidget(
            Button.builder(Component.translatable("voxlink.ticket.notify"), button ->
                  Minecraft.getInstance().setScreen(new icu.wuhui.voxlink.ui.TicketListScreen(this)))
               .bounds(8, 8, 100, 18)
               .build()
         );
      }
   }"""

TSM_NEW_26X = """   private void onInit(CallbackInfo ci) {
      icu.wuhui.voxlink.network.TicketClient.pollOnceIfNeeded(() -> {
         Minecraft mc = Minecraft.getInstance();
         mc.execute(() -> {
            if (icu.wuhui.voxlink.network.TicketClient.hasUnread()
               && mc.gui.screen() instanceof TitleScreen) {
               mc.gui.setScreen(mc.gui.screen());
            }
         });
      });
      int buttonWidth = Math.max(20, Math.min(50, this.width / 4 - 10));
      int x = Math.min(this.width / 2 + 104, this.width - buttonWidth - 10);
      this.addRenderableWidget(
         Button.builder(Component.translatable("voxlink.title"), button -> Minecraft.getInstance().setScreenAndShow(new VoxLinkScreen(this)))
            .bounds(x, this.height / 4 + 48, buttonWidth, 20)
            .build()
      );
      // 左上角工单未读弹窗：点击进我的工单
      if (icu.wuhui.voxlink.network.TicketClient.hasUnread()) {
         this.addRenderableWidget(
            Button.builder(Component.translatable("voxlink.ticket.notify"), button ->
                  Minecraft.getInstance().setScreenAndShow(new icu.wuhui.voxlink.ui.TicketListScreen(this)))
               .bounds(8, 8, 100, 18)
               .build()
         );
      }
   }"""

tsm_files = sorted(glob.glob(os.path.join(BASE, "*", "*", "src", "main", "java", "icu", "wuhui", "voxlink", "mixin", "TitleScreenMixin.java")))
assert len(tsm_files) == 28
for f in tsm_files:
    label = "TSM " + rep(f)
    if f == os.path.join(GOLD, "src", "main", "java", "icu", "wuhui", "voxlink", "mixin", "TitleScreenMixin.java"):
        done.append(label + ": gold skip")
        continue
    t, eol = load(f)
    if "pollOnceIfNeeded" in t:
        done.append(label + ": already done")
        continue
    if len(TSM_OLD.findall(t)) != 1:
        errors.append(label + ": onInit match=%d" % len(TSM_OLD.findall(t)))
        continue
    new_body = TSM_NEW_26X if "setScreenAndShow" in t else TSM_NEW_OLD_ERA
    t = TSM_OLD.sub(new_body.replace("\\", "\\\\"), t, count=1)
    save(f, t, eol)
    done.append(label + ": replaced (%s)" % ("26x" if new_body is TSM_NEW_26X else "old-era"))

# ---------- 3) FeedbackScreen init 方法体替换 ----------
FS_OLD = re.compile(r"   protected void init\(\) \{\n      super\.init\(\);\n      int centerX = this\.width / 2;\n      int colY = this\.height / 2 - 52;.*?\n   \}", re.S)

def fs_new(setter):
    return """   protected void init() {
      super.init();
      int centerX = this.width / 2;
      int colY = this.height / 2 - 64;
      this.addRenderableWidget(
         Button.builder(Component.translatable("voxlink.ticket.my"), button ->
               Minecraft.getInstance().%s(new TicketListScreen(new FeedbackScreen(this.parent))))
            .bounds(centerX - 100, colY, 200, 20)
            .build()
      );
      this.addRenderableWidget(
         Button.builder(Component.translatable("voxlink.fb.site"), button -> Minecraft.getInstance().%s(new FeedbackFormScreen(this, this.parent)))
            .bounds(centerX - 100, colY + 24, 200, 20)
            .build()
      );
      this.addRenderableWidget(
         Button.builder(Component.translatable("voxlink.fb.github"), button -> openBrowser(GITHUB_ISSUES_URL))
            .bounds(centerX - 100, colY + 48, 200, 20)
            .build()
      );
      this.addRenderableWidget(
         Button.builder(Component.translatable("voxlink.fb.gitee"), button -> openBrowser(GITEE_ISSUES_URL))
            .bounds(centerX - 100, colY + 72, 200, 20)
            .build()
      );
      this.addRenderableWidget(
         Button.builder(Component.translatable("voxlink.back"), button -> this.onClose())
            .bounds(centerX - 100, colY + 96, 200, 20)
            .build()
      );
   }""" % (setter, setter)

FS_NEW_OLD = fs_new("setScreen")
FS_NEW_26X = fs_new("gui.setScreen")

fs_files = sorted(glob.glob(os.path.join(BASE, "*", "*", "src", "main", "java", "icu", "wuhui", "voxlink", "ui", "FeedbackScreen.java")))
assert len(fs_files) == 28
for f in fs_files:
    label = "FS " + rep(f)
    if f == os.path.join(GOLD, "src", "main", "java", "icu", "wuhui", "voxlink", "ui", "FeedbackScreen.java"):
        done.append(label + ": gold skip")
        continue
    t, eol = load(f)
    if "voxlink.ticket.my" in t:
        done.append(label + ": already done")
        continue
    n = len(FS_OLD.findall(t))
    if n != 1:
        errors.append(label + ": init match=%d" % n)
        continue
    new_body = FS_NEW_26X if "gui.setScreen" in t else FS_NEW_OLD
    t = FS_OLD.sub(new_body.replace("\\", "\\\\"), t, count=1)
    save(f, t, eol)
    done.append(label + ": replaced (%s)" % ("26x" if new_body is FS_NEW_26X else "old-era"))

# ---------- 4) FeedbackFormScreen 3 锚点 ----------
FFS_SUBMIT_OLD = """      FeedbackUploader.submit(url, desc, buildClientInfo(), this.attachments, logs, result -> Minecraft.getInstance().execute(() -> {
         this.submitting = false;
         if (result.success) {
            this.submitted = true;
            this.attachments.clear();
            this.rateLimitedUntilMs = 0L;
         } else if ("RATE_LIMITED".equals(result.errorCode)) {
            int ra = result.retryAfterSec > 0 ? result.retryAfterSec : 600;
            this.rateLimitedUntilMs = System.currentTimeMillis() + ra * 1000L;
            this.statusMessage = "";
         } else if ("FEEDBACK_TOO_LARGE".equals(result.errorCode)) {
            this.setStatusKey("voxlink.fb.too_large", VoxLinkColors.ERROR);
         } else {"""

FFS_SUBMIT_NEW = """      icu.wuhui.voxlink.network.TicketClient.submit(url, desc, buildClientInfo(), files, result -> Minecraft.getInstance().execute(() -> {
         this.submitting = false;
         if (result.success) {
            this.submitted = true;
            this.createdTicketId = result.ticketId;
            this.attachments.clear();
            this.rateLimitedUntilMs = 0L;
         } else if ("RATE_LIMITED".equals(result.errorCode)) {
            int ra = result.retryAfterSec > 0 ? result.retryAfterSec : 600;
            this.rateLimitedUntilMs = System.currentTimeMillis() + ra * 1000L;
            this.statusMessage = "";
         } else if ("TICKET_TOO_LARGE".equals(result.errorCode)) {
            this.setStatusKey("voxlink.fb.too_large", VoxLinkColors.ERROR);
         } else {"""

FFS_FIELD_OLD = "   private boolean submitted = false;"
FFS_FIELD_NEW = """   private boolean submitted = false;
   /** 创建成功的工单号（成功态展示用） */
   private String createdTicketId;"""

FFS_DRAW_OLD = """      if (this.submitted) {
         this.drawCenteredClipped(graphics, Component.translatable("voxlink.fb.success").getString(), centerX, y, VoxLinkColors.SUCCESS);
         y += 10;"""

FFS_DRAW_NEW = """      if (this.submitted) {
         if (this.createdTicketId != null && !this.createdTicketId.isEmpty()) {
            this.drawCenteredClipped(graphics,
               Component.translatable("voxlink.ticket.created", this.createdTicketId).getString(), centerX, y, VoxLinkColors.SUCCESS);
         } else {
            this.drawCenteredClipped(graphics, Component.translatable("voxlink.fb.success").getString(), centerX, y, VoxLinkColors.SUCCESS);
         }
         y += 10;"""

ffs_files = sorted(glob.glob(os.path.join(BASE, "*", "*", "src", "main", "java", "icu", "wuhui", "voxlink", "ui", "FeedbackFormScreen.java")))
assert len(ffs_files) == 28
for f in ffs_files:
    label = "FFS " + rep(f)
    if f == os.path.join(GOLD, "src", "main", "java", "icu", "wuhui", "voxlink", "ui", "FeedbackFormScreen.java"):
        done.append(label + ": gold skip")
        continue
    t, eol = load(f)
    if "TicketClient.submit" in t:
        done.append(label + ": already done")
        continue
    ok = True
    for old, nm in [(FFS_SUBMIT_OLD, "submit"), (FFS_FIELD_OLD, "field"), (FFS_DRAW_OLD, "draw")]:
        if t.count(old) != 1:
            errors.append("%s: anchor %s count=%d" % (label, nm, t.count(old)))
            ok = False
    if not ok:
        continue
    t = t.replace(FFS_SUBMIT_OLD, FFS_SUBMIT_NEW, 1)
    t = t.replace(FFS_FIELD_OLD, FFS_FIELD_NEW, 1)
    t = t.replace(FFS_DRAW_OLD, FFS_DRAW_NEW, 1)
    save(f, t, eol)
    done.append(label + ": edited")

print("done:", len(done))
print("errors:", len(errors))
for e in errors:
    print("  ERR", e)

# ---------- 终检 ----------
miss = []
for rel in NEW_FILES:
    src = os.path.join(GOLD, "src", "main", "java", "icu", "wuhui", "voxlink", *rel.split(os.sep))
    h0 = hashlib.md5(open(src, "rb").read()).hexdigest()
    for t in templates:
        dst = os.path.join(BASE, t, "src", "main", "java", "icu", "wuhui", "voxlink", *rel.split(os.sep))
        if hashlib.md5(open(dst, "rb").read()).hexdigest() != h0:
            miss.append("newfile mismatch: " + rep(dst))

for f in tsm_files + fs_files + ffs_files:
    t = open(f, "rb").read().decode("utf-8", "replace").replace("\r\n", "\n")
    for marker in ["pollOnceIfNeeded", "voxlink.ticket.my", "TicketClient.submit", "createdTicketId"]:
        if marker in ("pollOnceIfNeeded",) and f in tsm_files and t.count(marker) != 1:
            miss.append("%s: %s count=%d" % (rep(f), marker, t.count(marker)))
        if marker == "voxlink.ticket.my" and f in fs_files and t.count(marker) != 1:
            miss.append("%s: %s count=%d" % (rep(f), marker, t.count(marker)))
        if marker in ("TicketClient.submit", "createdTicketId") and f in ffs_files and t.count(marker) != 2:
            miss.append("%s: %s count=%d" % (rep(f), marker, t.count(marker)))

print("final check:", "ALL PASS" if not miss and not errors else "%d issues" % (len(miss) + len(errors)))
for m in miss[:20]:
    print("  MISS", m)
sys.exit(0 if not miss and not errors else 1)
