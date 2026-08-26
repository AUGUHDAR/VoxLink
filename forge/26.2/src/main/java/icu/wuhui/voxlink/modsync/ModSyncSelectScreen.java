package icu.wuhui.voxlink.modsync;

import icu.wuhui.voxlink.ui.VoxLinkColors;
import icu.wuhui.voxlink.ui.VoxLinkScreenBase;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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

   private final String roomCode;
   private final List<ModSyncEntry> downloadable;
   private final List<String> unresolvable;
   private final List<String> versionDiff;
   private final Runnable onProceed;
   private final Runnable onCancel;

   /** 虚拟条目：kind 0=区块头 1=普通行。 */
   private final List<Object[]> lines = new ArrayList<>();
   private int page = 0;
   private int rowsPerPage = MIN_ROWS;

   private Button downloadBtn;
   private Button joinBtn;
   private Button backBtn;
   private Button cancelBtn;
   private Button prevBtn;
   private Button nextBtn;

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
      List<ModSyncEntry> downloadable,
      List<String> unresolvable,
      List<String> versionDiff,
      Runnable onProceed,
      Runnable onCancel
   ) {
      super(Component.translatable("voxlink.modsync.title"));
      this.roomCode = roomCode;
      this.downloadable = downloadable;
      this.unresolvable = unresolvable;
      this.versionDiff = versionDiff;
      this.onProceed = onProceed;
      this.onCancel = onCancel;
      this.buildLines();
   }

   /** 把三类信息拍平成虚拟行流，分页只面对这一种结构，杜绝重叠。 */
   private void buildLines() {
      this.lines.clear();
      if (!this.downloadable.isEmpty()) {
        this.lines.add(new Object[]{0, Component.translatable("voxlink.modsync.missing_header", this.downloadable.size()).getString()});
         for (ModSyncEntry e : this.downloadable) {
            this.lines.add(new Object[]{
               1,
               Component.translatable("voxlink.modsync.entry_line", e.title, e.versionNumber, humanSize(e.size)).getString()
            });
         }
      }

      if (!this.versionDiff.isEmpty()) {
         this.lines.add(new Object[]{0, Component.translatable("voxlink.modsync.diff_header").getString()});
         for (String d : this.versionDiff) {
            this.lines.add(new Object[]{1, d});
         }
      }

      if (!this.unresolvable.isEmpty()) {
         this.lines.add(new Object[]{0, Component.translatable("voxlink.modsync.unresolvable_header").getString()});
         for (String u : this.unresolvable) {
            this.lines.add(new Object[]{1, u});
         }
      }
   }

   @Override
   protected void init() {
      super.init();
      this.clearOurWidgets();
      int centerX = this.width / 2;
      int listTop = 50;
      int footerReserve = this.state == State.SELECTING ? 78 : 46;
      this.rowsPerPage = Math.max(MIN_ROWS, (this.height - listTop - footerReserve) / ROW_H);
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
         int by = this.height - 54;
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
         this.joinBtn = Button
            .builder(Component.translatable("voxlink.modsync.join_directly"), b -> this.onProceed.run())
            .bounds(centerX + 2, by, 128, 20).build();
         this.addRenderableWidget(this.joinBtn);
         this.backBtn = Button
            .builder(Component.translatable("voxlink.modsync.back"), b -> this.onCancel.run())
            .bounds(centerX - 65, by + 24, 130, 20).build();
         this.addRenderableWidget(this.backBtn);
      } else {
         this.cancelBtn = Button
            .builder(Component.translatable("voxlink.modsync.cancel_download"), b -> {
               this.downloadAbort = true;
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
      if (mc.gui.screen() == this) {
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
      for (int i = 0; i < this.downloadable.size(); i++) {
         if (this.downloadAbort) {
            this.abortThrough();
            return;
         }

         ModSyncEntry e = this.downloadable.get(i);
         this.currentName = e.title;
         try {
            ModrinthClient.downloadVerified(e.downloadUrl, e.sha512, e.fileName, modsDir);
            synchronized (this.succeededIdx) {
               this.succeededIdx.add(i);
            }

            this.doneFiles++;
            this.bytesDone += e.size;
            long elapsed = Math.max(1L, System.currentTimeMillis() - this.startMs);
            double speed = this.bytesDone / (elapsed / 1000.0);
            long remainB = Math.max(0L, this.totalBytes - this.bytesDone);
            this.etaSec = speed > 0 ? (long)(remainB / speed) : -1L;
         } catch (Exception ex) {
            ModSyncLog.warn("download {} failed: {}", e.fileName, ex.toString());
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
         Minecraft.getInstance().execute(() -> Minecraft.getInstance().gui.setScreen(new ModSyncRestartScreen(okCount)));
      } else {
         final String err = lastError;
         this.failThrough(Component.translatable("voxlink.modsync.download_failed", err == null ? "?" : clip(err, 40)).getString());
      }
   }

   private void abortThrough() {
      this.state = State.SELECTING;
      Minecraft.getInstance().execute(() -> {
         this.onCancel.run();
      });
   }

   private void failThrough(String msg) {
      this.state = State.SELECTING;
      this.statusText = msg;
      this.statusColor = VoxLinkColors.ERROR;
      Minecraft.getInstance().execute(this::rebuildInPlace);
   }

   @Override
   public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
      super.extractRenderState(graphics, mouseX, mouseY, partialTick);
      int centerX = this.width / 2;
      this.drawCenteredClipped(graphics, this.title.getString(), centerX, 10, VoxLinkColors.WHITE);
      this.drawCenteredClipped(graphics, Component.translatable("voxlink.modsync.subtitle", this.roomCode).getString(), centerX, 24, VoxLinkColors.MUTED);

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
            this.drawCenteredClipped(graphics, this.statusText, centerX, this.height - 86, this.statusColor);
         }
      } else {
         this.renderProgress(graphics, centerX);
      }
   }

   private void renderList(GuiGraphicsExtractor graphics, int centerX) {
      int listTop = 50;
      int from = this.page * this.rowsPerPage;
      int to = Math.min(this.lines.size(), from + this.rowsPerPage);
      int y = listTop;
      for (int i = from; i < to; i++) {
         Object[] line = this.lines.get(i);
         boolean header = ((Integer)line[0]) == 0;
         String text = String.valueOf(line[1]);
         if (header) {
            this.drawCenteredClipped(graphics, text, centerX, y, VoxLinkColors.WARNING);
         } else {
            this.drawCenteredClipped(graphics, text, centerX, y, VoxLinkColors.WHITE);
         }

         y += ROW_H;
      }
   }

   private void renderProgress(GuiGraphicsExtractor graphics, int centerX) {
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
      this.drawCenteredClipped(graphics, Component.translatable("voxlink.modsync.progress_completed", this.doneFiles, this.totalFiles).getString(), centerX, y, VoxLinkColors.WHITE);
      y += ROW_H;
      this.drawCenteredClipped(graphics, Component.translatable("voxlink.modsync.progress_current", clip(this.currentName, 36)).getString(), centerX, y, VoxLinkColors.WARNING);
      y += ROW_H;
      int remaining = Math.max(0, this.totalFiles - this.doneFiles);
      this.drawCenteredClipped(graphics, Component.translatable("voxlink.modsync.progress_remaining", remaining).getString(), centerX, y, VoxLinkColors.MUTED);
      y += ROW_H;
      this.drawCenteredClipped(graphics, Component.translatable("voxlink.modsync.progress_eta", fmtEta(this.etaSec)).getString(), centerX, y, VoxLinkColors.MUTED);
   }

   @Override
   public boolean shouldCloseOnEsc() {
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
      long s = sec % 60L;
      return m + ":" + (s < 10L ? "0" : "") + s;
   }
}
