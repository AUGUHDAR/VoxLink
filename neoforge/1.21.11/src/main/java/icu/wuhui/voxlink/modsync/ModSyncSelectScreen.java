package icu.wuhui.voxlink.modsync;

import icu.wuhui.voxlink.ui.VoxLinkColors;
import icu.wuhui.voxlink.ui.VoxLinkScreenBase;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 必装模组弹窗：两键式（下载模组 / 直接加入）。内容统一为虚拟条目列表分页渲染，
 * 行数按窗口高度自适应，任何分辨率不重叠；下载态切换为结构化进度面板
 * （总进度条/已完成/当前文件/剩余数量/预计剩余时间），全部文案取自语言文件。
 */
public class ModSyncSelectScreen extends VoxLinkScreenBase {
   private static final int ROW_H = 12;
   private static final int MIN_ROWS = 3;
   /** 模组条离线图标色板：按名称哈希取色，低饱和 MC 风格。 */
   private static final int[] CARD_COLORS = {
      0xFF4A6FA5, 0xFF5B8C5A, 0xFFB07D3F, 0xFF8A5BA5, 0xFFA55B5B, 0xFF3F8C8C
   };

   private final String roomCode;
   private final String scope;
   private final List<ModSyncEntry> downloadable;
   private final List<ModSyncEntry> manualEntries;
   private final List<String> unresolvable;
   private final List<String> versionDiff;
   private final List<String> skipKeys;
   private final Runnable onProceed;
   private final Runnable onCancel;

   /** 虚拟条目：kind 0=区块头 1=普通行 2=模组条名行(携带entry) 3=模组条小字行。 */
   private final List<Object[]> lines = new ArrayList<>();
   private int page = 0;
   private int rowsPerPage = MIN_ROWS;
   /** 当前列表区底端（最后一行下缘），状态文字画在它之下。 */
   private int listBottomY = 50;

   private Button downloadBtn;
   private Button joinBtn;
   private Button backBtn;
   private Button cancelBtn;
   private Button prevBtn;
   private Button nextBtn;
   private final List<Button> copyButtons = new ArrayList<>();
   private int copiedResetTick = 0;
   private Button copiedBtn = null;
   private boolean resolveStarted = false;
   private volatile boolean resolving = false;
   /** MR 有项目但无本端兼容版本的条目（版本不匹配组）。 */
   private final List<ModSyncEntry> incompatibleEntries = new ArrayList<>();
   /** 下载期间到达的回流结果 {原条目, 解析条目, bucket}：回到 SELECTING 时补入。 */
   private final List<Object[]> pendingResolved = new ArrayList<>();

   private enum State {SELECTING, DOWNLOADING}

   private volatile State state = State.SELECTING;
   private volatile boolean downloadAbort = false;
   private volatile int doneFiles = 0;
   private volatile int totalFiles = 0;
   private volatile long totalBytes = 0L;
   private volatile long bytesDone = 0L;
   private volatile long startMs = 0L;
   private volatile int overallPct = 0;
   private volatile long etaSec = -1L;
   private volatile String currentName = "";
   private String statusText = "";
   private int statusColor = VoxLinkColors.MUTED;
   private final java.util.Set<Integer> succeededIdx = new java.util.HashSet<>();

   public ModSyncSelectScreen(
      String roomCode,
      String scope,
      List<ModSyncEntry> downloadable,
            List<ModSyncEntry> manualEntries,
   
      List<String> unresolvable,

   
      List<String> versionDiff,
      List<String> skipKeys,
      Runnable onProceed,
      Runnable onCancel
   ) {
      // 标题按 scope 区分：必装检查 / 房主全部模组
      super(Component.translatable(
         ModSyncManifestService.SCOPE_ALL.equals(scope) ? "voxlink.modsync.title_all" : "voxlink.modsync.title"));
      this.roomCode = roomCode;
      this.scope = scope == null ? "" : scope;
      this.downloadable = downloadable;
            this.manualEntries = manualEntries;
   
      this.unresolvable = unresolvable;

   
      this.versionDiff = versionDiff;
      this.skipKeys = skipKeys == null ? new java.util.ArrayList<>() : skipKeys;
      this.onProceed = onProceed;
      this.onCancel = onCancel;
      // 手动条目按已知项目拆组：清单已知项目=版本不匹配组，其余=找不到组
      for (int i = this.manualEntries.size() - 1; i >= 0; i--) {
         ModSyncEntry me = this.manualEntries.get(i);
         if (!me.projectId.isEmpty() || !me.downloadUrl.isEmpty()) {
            this.manualEntries.remove(i);
            this.incompatibleEntries.add(me);
         }
      }

      this.buildLines();
   }

   /** 三类信息拍平成虚拟行流分页渲染；模组条占两行(kind2/3)。 */
   private void buildLines() {
      this.lines.clear();
      boolean allScope = ModSyncManifestService.SCOPE_ALL.equals(this.scope);
      if (!this.downloadable.isEmpty()) {
        this.lines.add(new Object[]{0, Component.translatable(
            allScope ? "voxlink.modsync.missing_header_all" : "voxlink.modsync.missing_header",
            this.downloadable.size()).getString()});
         for (ModSyncEntry e : this.downloadable) {
            this.addEntryCard(e);
         }
      }

      if (!this.incompatibleEntries.isEmpty()) {
         this.lines.add(new Object[]{0, Component.translatable("voxlink.modsync.incompatible_header", this.incompatibleEntries.size()).getString()});
         for (ModSyncEntry e : this.incompatibleEntries) {
            this.addEntryCard(e);
         }
      }

      if (!this.manualEntries.isEmpty()) {
         this.lines.add(new Object[]{0, Component.translatable("voxlink.modsync.notfound_header", this.manualEntries.size()).getString()});
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
      }
   }

      /** 模组条 = 名行(图标/名称/复制按钮) + 小字行(jar 原名·版本·大小)。 */
   private void addEntryCard(ModSyncEntry e) {
      String sub = cleanText(e.fileName);
      if (!e.versionNumber.isEmpty()) {
         sub = e.versionNumber + " · " + sub;
      }

      if (e.size > 0L) {
         sub = sub + " · " + humanSize(e.size);
      }

      this.lines.add(new Object[]{2, cleanText(e.title), e});
      this.lines.add(new Object[]{3, sub});
   }

/**
    * 清洗待绘制文本：mods 目录里的文件名可能带 § 样式代码甚至控制字符，
    * 原样绘制会把整行染色/花屏——显示层统一剥掉（数据层的原名不动，便于日志对账）。
    */
   static String cleanText(String s) {
      return s == null ? "" : s.replaceAll("\u00a7.", "").replaceAll("\\p{Cntrl}", "").trim();
   }

   @Override
   protected void init() {
      super.init();
      this.clearOurWidgets();
      int centerX = this.width / 2;
      int listTop = 50;
      // 状态文字（下载失败原因等）占列表底部之下的专属一行，计入预算防止压住最后一行
      int statusReserve = this.statusText.isEmpty() ? 0 : 13;
      int footerReserve = (this.state == State.SELECTING ? 78 : 46) + statusReserve;
      this.rowsPerPage = Math.max(MIN_ROWS, (this.height - listTop - footerReserve) / ROW_H);
      this.listBottomY = listTop + this.rowsPerPage * ROW_H;
      int pages = this.pageCount();
      if (this.page >= pages) {
         this.page = Math.max(0, pages - 1);
      }

      boolean paging = pages > 1 && this.state == State.SELECTING;
      if (paging) {
         int py = this.height - footerReserve + 2;
         this.prevBtn = Button.builder(Component.translatable("voxlink.modsync.prev_page"), b -> this.flipPage(-1))
            .bounds(centerX - 90, py, 40, 16).build();
         this.addRenderableWidget(this.prevBtn);
         this.nextBtn = Button.builder(Component.translatable("voxlink.modsync.next_page"), b -> this.flipPage(1))
            .bounds(centerX + 50, py, 40, 16).build();
         this.addRenderableWidget(this.nextBtn);
      }

      if (this.state == State.SELECTING) {
         this.buildCopyButtons();
         if (!this.resolveStarted && (!this.manualEntries.isEmpty() || !this.incompatibleEntries.isEmpty())) {
            this.resolveStarted = true;
            this.startResolve();
         }

         int by = this.height - 54;
         // 无可下载项（仅版本不一致/仅提示）时不给下载按钮，避免玩家对着空列表点"下载"
         boolean hasDownloads = !this.downloadable.isEmpty();
         if (hasDownloads) {
            long selBytes = 0L;
            for (ModSyncEntry e : this.downloadable) {
               selBytes += e.size;
            }

            this.downloadBtn = Button
               .builder(
                  Component.translatable(
                     "voxlink.modsync.download_mods",
                     this.downloadable.size(),
                     humanSize(selBytes)
                  ),
                  b -> this.startDownload()
               )
               .bounds(centerX - 130, by, 128, 20).build();
            this.addRenderableWidget(this.downloadBtn);
         }

         this.joinBtn = Button
            .builder(
               Component.translatable(
                  this.versionDiff.isEmpty() ? "voxlink.modsync.join_directly" : "voxlink.modsync.join_anyway"
               ),
               b -> {
                  ModSyncGuestService.markSkipped(this.skipKeys);
                  this.onProceed.run();
               }
            )
            .bounds(hasDownloads ? centerX + 2 : centerX - 65, by, hasDownloads ? 128 : 130, 20).build();
         this.addRenderableWidget(this.joinBtn);
         this.backBtn = Button
            .builder(Component.translatable("voxlink.modsync.back"), b -> this.onCancel.run())
            .bounds(centerX - 65, by + 24, 130, 20).build();
         this.addRenderableWidget(this.backBtn);
      } else {
         this.cancelBtn = Button
            .builder(Component.translatable("voxlink.modsync.cancel_download"), b -> {
               if (this.downloadAbort) {
                  return;
               }

               this.downloadAbort = true;
               // M21：即时反馈——按钮文案直接切到"正在取消..."，并 active=false 防重复点击；
               // 状态文字只是附属提示，按钮可见状态才是玩家最先注意的反馈。
               b.setMessage(Component.translatable("voxlink.modsync.cancelling"));
               b.active = false;
               this.statusText = Component.translatable("voxlink.modsync.cancelling").getString();
               this.statusColor = VoxLinkColors.MUTED;
            })
            .bounds(centerX - 65, this.height - 30, 130, 20).build();
         this.addRenderableWidget(this.cancelBtn);
      }
   }

   private int pageCount() {
      return Math.max(1, (this.lines.size() + this.rowsPerPage - 1) / this.rowsPerPage);
   }

   private void flipPage(int delta) {
      int pages = this.pageCount();
      this.page = Math.floorMod(this.page + delta, pages);
      this.rebuildInPlace();
   }

   private void rebuildInPlace() {
      Minecraft mc = Minecraft.getInstance();
      if (mc.screen == this) {
         this.clearOurWidgets();
         this.init();
      }
   }

   private void startDownload() {
      if (this.state == State.DOWNLOADING) {
         return;
      }

      this.state = State.DOWNLOADING;
      this.downloadAbort = false;
      this.totalFiles = this.downloadable.size();
      this.doneFiles = 0;
      this.bytesDone = 0L;
      this.startMs = System.currentTimeMillis();
      long tb = 0L;
      for (ModSyncEntry e : this.downloadable) {
         tb += e.size;
      }

      this.totalBytes = tb;
      this.currentName = "";
      Thread worker = new Thread(() -> this.downloadLoop(), "VoxLink-ModSync-DL");
      worker.setDaemon(true);
      worker.start();
      this.rebuildInPlace();
   }

   private void downloadLoop() {
      Path modsDir = ModSyncEnv.getModsDir();
      try {
         java.nio.file.Files.createDirectories(modsDir);
      } catch (Exception e) {
         this.failThrough(Component.translatable("voxlink.modsync.download_failed", "mods dir").getString());
         return;
      }

      String lastError = null;
      Path modsDirNorm = modsDir.normalize();
      for (int i = 0; i < this.downloadable.size(); i++) {
         if (this.downloadAbort) {
            this.abortThrough();
            return;
         }

         ModSyncEntry e = this.downloadable.get(i);
         this.currentName = e.title;

         // H6：fileName 消毒——拒绝 ../、绝对路径、路径分隔符、冒号、控制字符，
         // 防止恶意/错乱清单把文件写到 mods 目录之外。
         if (!isSafeFileName(e.fileName, modsDirNorm)) {
            ModSyncLog.warn("skip unsafe fileName entry: {} (title={})", e.fileName, e.title);
            this.unresolvable.add(e.fileName);
            lastError = e.title;
            this.overallPct = this.totalBytes > 0 ? (int)Math.min(100L, this.bytesDone * 100L / this.totalBytes) : 0;
            continue;
         }

         try {
            // M11+H5：用 ModrinthClient 新签名（已与并行代理对齐），返回实际下载并校验通过的字节数。
            // sha1 为空时传 null，由客户端决定是否放行校验。
            long bytes = ModrinthClient.downloadVerified(
               e.downloadUrl,
               e.sha512,
               (e.sha1 == null || e.sha1.isEmpty()) ? null : e.sha1,
               e.fileName,
               modsDir,
               () -> this.downloadAbort
            );
            synchronized (this.succeededIdx) {
               this.succeededIdx.add(i);
            }

            this.doneFiles++;
            this.bytesDone += bytes;
            long elapsed = Math.max(1L, System.currentTimeMillis() - this.startMs);
            double speed = this.bytesDone / (elapsed / 1000.0);
            long remainB = Math.max(0L, this.totalBytes - this.bytesDone);
            this.etaSec = speed > 0 ? (long)(remainB / speed) : -1L;
         } catch (ModrinthClient.AbortedException aborted) {
            // 玩家取消：静默收场，不算失败
            ModSyncLog.info("download {} cancelled by user", e.fileName);
            this.abortThrough();
            return;
         } catch (Exception ex) {
            ModSyncLog.warn("download {} failed: {}", e.fileName, ex.toString());
            this.unresolvable.add(e.fileName);
            lastError = e.title;
         }

         this.overallPct = this.totalBytes > 0 ? (int)Math.min(100L, this.bytesDone * 100L / this.totalBytes) : 0;
      }

      if (this.downloadAbort) {
         this.abortThrough();
         return;
      }

      final int okCount = this.succeededIdx.size();
      if (okCount > 0 && lastError == null) {
         Minecraft.getInstance().execute(() -> Minecraft.getInstance().setScreen(new ModSyncRestartScreen(okCount)));
      } else {
         final String err = lastError;
         this.failThrough(Component.translatable("voxlink.modsync.download_failed", err == null ? "?" : clip(err, 40)).getString());
      }
   }

   private void abortThrough() {
      // 取消下载=回到清单弹窗（SELECTING），玩家可重试下载/直接加入/返回。
      // 不再走 onCancel：那条链会取消整个加入流程（leaveRoom+返回），且一旦
      // 导航回调异常本屏就永久停在"正在取消"（按钮已禁用、无人重建 UI）。
      this.state = State.SELECTING;
      this.applyPendingResolved();
      this.statusText = Component.translatable("voxlink.modsync.download_cancelled").getString();
      this.statusColor = VoxLinkColors.MUTED;
      this.downloadAbort = false;
      Minecraft.getInstance().execute(this::rebuildInPlace);
   }

   private void failThrough(String msg) {
      this.state = State.SELECTING;
      this.applyPendingResolved();
      // M21：失败回到 SELECTING 后追加重试提示，避免玩家以为"卡死"了
      String hint = Component.translatable("voxlink.modsync.retry_hint").getString();
      this.statusText = hint.isEmpty() ? msg : (msg + " " + hint);
      this.statusColor = VoxLinkColors.ERROR;
      Minecraft.getInstance().execute(this::rebuildInPlace);
   }

   @Override
   public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
      super.render(graphics, mouseX, mouseY, partialTick);
      int centerX = this.width / 2;
      this.drawCenteredClipped(graphics, this.title.getString(), centerX, 10, VoxLinkColors.WHITE);
      this.drawCenteredClipped(graphics, Component.translatable(
         ModSyncManifestService.SCOPE_ALL.equals(this.scope) ? "voxlink.modsync.subtitle_all" : "voxlink.modsync.subtitle",
         this.roomCode).getString(), centerX, 24, VoxLinkColors.MUTED);

      if (this.state == State.SELECTING) {
         this.renderList(graphics, centerX);
         int pages = this.pageCount();
         if (pages > 1) {
            this.drawCenteredClipped(
               graphics,
               Component.translatable("voxlink.modsync.page_ind", this.page + 1, pages).getString(),
               centerX,
               this.height - 74,
               VoxLinkColors.MUTED
            );
         }

         if (!this.statusText.isEmpty()) {
            this.drawCenteredClipped(graphics, this.statusText, centerX, Math.min(this.listBottomY + 2, this.height - 86), this.statusColor);
         } else if (this.resolving) {
            this.drawCenteredClipped(graphics, Component.translatable("voxlink.modsync.resolving").getString(), centerX, Math.min(this.listBottomY + 2, this.height - 86), VoxLinkColors.MUTED);
         }
      } else {
         this.renderProgress(graphics, centerX);
      }
   }

   private void renderList(GuiGraphics graphics, int centerX) {
      int listTop = 50;
      int[] range = this.pageRange();
      int iconX = 10;
      int textX = iconX + 20;
      int copyW = 36;
      int copyX = this.width - 8 - copyW;
      int y = listTop;
      for (int i = range[0]; i < range[1]; i++) {
         Object[] line = this.lines.get(i);
         int kind = ((Integer)line[0]);
         String text = String.valueOf(line[1]);
         if (kind == 0) {
            this.drawCenteredClipped(graphics, text, centerX, y, VoxLinkColors.WARNING);
         } else if (kind == 1) {
            this.drawCenteredClipped(graphics, text, centerX, y, VoxLinkColors.WHITE);
         } else if (kind == 2) {
            ModSyncEntry e = (ModSyncEntry)line[2];
            String title = text.isEmpty() ? cleanText(e.fileName) : text;
            int[][] iconRuns = ModSyncIconCache.runs(e.iconUrl);
            if (iconRuns == null && !e.iconUrl.isEmpty()) {
               ModSyncIconCache.prefetch(e.iconUrl);
            }

            if (iconRuns != null) {
               for (int[] run : iconRuns) {
                  graphics.fill(iconX + run[0], y + 3 + run[1], iconX + run[0] + run[2], y + 4 + run[1], run[3]);
               }
            } else {
               // 兜底：确定性色块+首字母
               int hue = Math.floorMod(title.hashCode(), CARD_COLORS.length);
               graphics.fill(iconX, y + 3, iconX + 16, y + 19, CARD_COLORS[hue]);
               String initial = title.isEmpty() ? "?" : title.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
               var mcFont = Minecraft.getInstance().font;
               this.drawString(graphics, initial, iconX + 8 - mcFont.width(initial) / 2, y + 7, 0xFFFFFFFF);
            }
            this.drawString(graphics, clipToWidth(title, copyX - textX - 6), textX, y + 2, 0xFFFFFFFF);
         } else if (kind == 3) {
            this.drawString(graphics, clipToWidth(text, this.width - 8 - textX), textX, y + 2, VoxLinkColors.MUTED);
         }

         y += ROW_H;
      }
   }

   /** 当前页的行区间：模组条占两行(kind2/3)，切割必须落在条边界上。 */
   private int[] pageRange() {
      int from = this.page * this.rowsPerPage;
      int to = Math.min(this.lines.size(), from + this.rowsPerPage);
      if (to < this.lines.size() && this.kindAt(to - 1) == 3) {
         to--;
      }

      if (from > 0 && from < this.lines.size() && this.kindAt(from) == 3) {
         from++;
      }

      if (from >= to && from >= 2) {
         from -= 2;
      }

      return new int[]{from, Math.max(from, to)};
   }

   private int kindAt(int idx) {
      return idx >= 0 && idx < this.lines.size() ? ((Integer)this.lines.get(idx)[0]) : -1;
   }

   /** 为当前页的每个模组条生成复制按钮（按钮只占自己的条行，绝不压底部按钮区）。 */
   private void buildCopyButtons() {
      this.copyButtons.clear();
      this.copiedResetTick = 0;
      this.copiedBtn = null;
      int[] range = this.pageRange();
      int copyW = 36;
      int copyX = this.width - 8 - copyW;
      int y = 50;
      for (int i = range[0]; i < range[1]; i++) {
         Object[] line = this.lines.get(i);
         if (((Integer)line[0]) == 2) {
            ModSyncEntry e = (ModSyncEntry)line[2];
            String copyText = String.valueOf(line[1]).isEmpty() ? e.fileName : String.valueOf(line[1]);
            Button b = Button.builder(Component.translatable("voxlink.modsync.copy"), btn -> {
               Minecraft.getInstance().keyboardHandler.setClipboard(copyText);
               btn.setMessage(Component.translatable("voxlink.modsync.copied"));
               btn.active = false;
               this.copiedBtn = btn;
               this.copiedResetTick = 60;
            }).bounds(copyX, y + 1, copyW, 18).build();
            this.copyButtons.add(b);
            this.addRenderableWidget(b);
         }

         y += ROW_H;
      }
   }

   /** 后台 MR 兜底解析手动条目：真名/真图标原地更新，可下载者回流，按钮随之出现。 */
   private void startResolve() {
      this.resolving = true;
      List<ModSyncEntry> snapshot = new ArrayList<>(this.manualEntries);
      List<ModSyncEntry> incompatible = new ArrayList<>(this.incompatibleEntries);
      List<ModSyncEntry> known = new ArrayList<>(this.downloadable);
      ModSyncResolver.resolveAsync(snapshot, incompatible, known, new ModSyncResolver.Callback() {
         @Override
         public void onResolved(ModSyncEntry original, ModSyncEntry resolved, int bucket) {
            Minecraft.getInstance().execute(() -> ModSyncSelectScreen.this.applyResolved(original, resolved, bucket));
         }

         @Override
         public void onFinished(int resolvedCount) {
            ModSyncLog.info("resolve finished, {} became downloadable", resolvedCount);
            ModSyncSelectScreen.this.resolving = false;
         }
      });
   }

   private void applyResolved(ModSyncEntry original, ModSyncEntry resolved, int bucket) {
      if (this.state != State.SELECTING) {
         if (bucket == ModSyncResolver.BUCKET_DOWNLOADABLE || resolved != null) {
            this.pendingResolved.add(new Object[]{original, resolved, bucket});
         }

         return;
      }

      this.applyOne(original, resolved, bucket);
      // 名称/图标原地更新与分组移动统一刷新
      this.buildLines();
      this.rebuildInPlace();
   }

   private void applyOne(ModSyncEntry original, ModSyncEntry resolved, int bucket) {
      if (bucket == ModSyncResolver.BUCKET_DOWNLOADABLE && resolved != null) {
         if (original != null) {
            this.manualEntries.remove(original);
            this.incompatibleEntries.remove(original);
         }

         this.downloadable.add(resolved);
      } else if (bucket == ModSyncResolver.BUCKET_INCOMPATIBLE && original != null) {
         this.manualEntries.remove(original);
         if (!this.incompatibleEntries.contains(original)) {
            this.incompatibleEntries.add(original);
         }
      }
   }

   private void applyPendingResolved() {
      if (this.pendingResolved.isEmpty()) {
         return;
      }

      for (Object[] p : this.pendingResolved) {
         this.applyOne((ModSyncEntry)p[0], (ModSyncEntry)p[1], (Integer)p[2]);
      }

      this.pendingResolved.clear();
      this.buildLines();
   }

   @Override
   public void tick() {
      super.tick();
      if (this.copiedResetTick > 0 && --this.copiedResetTick == 0 && this.copiedBtn != null) {
         this.copiedBtn.setMessage(Component.translatable("voxlink.modsync.copy"));
         this.copiedBtn.active = true;
         this.copiedBtn = null;
      }
   }

   /** 左对齐按像素宽截断（超宽补省略号）。 */
   static String clipToWidth(String s, int maxW) {
      if (s == null || s.isEmpty()) {
         return "";
      }

      var font = Minecraft.getInstance().font;
      if (font.width(s) <= maxW) {
         return s;
      }

      String ellipsis = "…";
      while (!s.isEmpty() && font.width(s + ellipsis) > maxW) {
         s = s.substring(0, s.length() - 1);
      }

      return s + ellipsis;
   }

   private void renderProgress(GuiGraphics graphics, int centerX) {
      int top = Math.max(60, this.height / 2 - 52);
      this.drawCenteredClipped(graphics, Component.translatable("voxlink.modsync.progress_title").getString(), centerX, top, VoxLinkColors.WHITE);
      int barW = Math.min(220, this.width - 40);
      int barY = top + 18;
      graphics.fill(centerX - barW / 2, barY, centerX + barW / 2, barY + 6, 0xFF2B2B31);
      int fillW = (int)((long)barW * Math.max(0, Math.min(100, this.overallPct)) / 100L);
      if (fillW > 0) {
         graphics.fill(centerX - barW / 2, barY, centerX - barW / 2 + fillW, barY + 6, VoxLinkColors.SUCCESS);
      }

      int y = barY + 14;
      this.drawCenteredClipped(graphics, Component.translatable("voxlink.modsync.progress_overall", this.overallPct + "%").getString(), centerX, y, VoxLinkColors.MUTED);
      y += ROW_H;
      // M21：用真实成功数（succeededIdx.size()）而非 doneFiles，避免失败被计为完成
      int okNow;
      synchronized (this.succeededIdx) {
         okNow = this.succeededIdx.size();
      }

      this.drawCenteredClipped(graphics, Component.translatable("voxlink.modsync.progress_completed", okNow, this.totalFiles).getString(), centerX, y, VoxLinkColors.WHITE);
      y += ROW_H;
      this.drawCenteredClipped(graphics, Component.translatable("voxlink.modsync.progress_current", cleanText(clip(this.currentName, 36))).getString(), centerX, y, VoxLinkColors.WARNING);
      y += ROW_H;
      int remaining = Math.max(0, this.totalFiles - okNow);
      this.drawCenteredClipped(graphics, Component.translatable("voxlink.modsync.progress_remaining", remaining).getString(), centerX, y, VoxLinkColors.MUTED);
      y += ROW_H;
      this.drawCenteredClipped(graphics, Component.translatable("voxlink.modsync.progress_eta", fmtEta(this.etaSec)).getString(), centerX, y, VoxLinkColors.MUTED);
   }

   @Override
   public boolean shouldCloseOnEsc() {
      // 吞掉关闭的同时给出反馈——按 ESC 不会退出但玩家常以为"按了没反应"。
      // 下载中提示先取消；选择中提示用按钮返回。
      String key = this.state == State.DOWNLOADING
         ? "voxlink.modsync.esc_block_downloading"
         : "voxlink.modsync.esc_block_selecting";
      this.statusText = Component.translatable(key).getString();
      this.statusColor = VoxLinkColors.WARNING;
      this.rebuildInPlace();
      return false;
   }

   static String clip(String s, int max) {
      if (s == null) {
         return "";
      }

      return s.length() <= max ? s : s.substring(0, max - 1) + "…";
   }

   static String humanSize(long bytes) {
      if (bytes <= 0L) {
         return "?";
      }

      if (bytes < 1024L * 1024L) {
         return bytes / 1024L + "KB";
      }

      return String.format("%.1fMB", bytes / 1024.0 / 1024.0);
   }

   static String fmtEta(long sec) {
      if (sec < 0L) {
         return "--:--";
      }

      long m = sec / 60L;
      // M21：分钟数 > 99 时切换为占位符，避免显示爆炸式"199:59"误导玩家
      if (m > 99L) {
         return "--:--";
      }

      long s = sec % 60L;
      return m + ":" + (s < 10L ? "0" : "") + s;
   }

   /**
    * H6：校验 fileName 是否会落在 modsDir 之内。
    * 拒绝规则：null/空、含 ".."、含任何路径分隔符/冒号/控制字符、或解析后逃出 modsDir。
    */
   static boolean isSafeFileName(String fileName, Path modsDirNorm) {
      if (fileName == null || fileName.isEmpty()) {
         return false;
      }

      if (fileName.contains("..")) {
         return false;
      }

      if (fileName.indexOf('/') >= 0 || fileName.indexOf('\\') >= 0) {
         return false;
      }

      // Windows 盘符冒号、以及任何显式冒号都拒绝
      if (fileName.indexOf(':') >= 0) {
         return false;
      }

      // 控制字符（含 \0、\n、\r、\t 等）会扰乱文件系统/渲染
      for (int i = 0; i < fileName.length(); i++) {
         if (Character.isISOControl(fileName.charAt(i))) {
            return false;
         }
      }

      try {
         Path resolved = modsDirNorm.resolve(fileName).normalize();
         return resolved.startsWith(modsDirNorm);
      } catch (Exception ex) {
         return false;
      }
   }
}
