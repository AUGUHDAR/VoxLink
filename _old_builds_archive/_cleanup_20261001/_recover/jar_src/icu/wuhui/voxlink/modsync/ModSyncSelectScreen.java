package icu.wuhui.voxlink.modsync;

import icu.wuhui.voxlink.ui.VoxLinkScreenBase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

public class ModSyncSelectScreen extends VoxLinkScreenBase {
   private static final int ROW_H = 12;
   private static final int MIN_ROWS = 3;
   private final String roomCode;
   private final List<ModSyncEntry> downloadable;
   private final List<String> unresolvable;
   private final List<String> versionDiff;
   private final List<String> skipKeys;
   private final Runnable onProceed;
   private final Runnable onCancel;
   private final List<Object[]> lines = new ArrayList<>();
   private int page = 0;
   private int rowsPerPage = 3;
   private int listBottomY = 50;
   private Button downloadBtn;
   private Button joinBtn;
   private Button backBtn;
   private Button cancelBtn;
   private Button prevBtn;
   private Button nextBtn;
   private volatile ModSyncSelectScreen.State state = ModSyncSelectScreen.State.SELECTING;
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
   private int statusColor = -5592406;
   private final Set<Integer> succeededIdx = new HashSet<>();

   public ModSyncSelectScreen(
      String roomCode,
      String scope,
      List<ModSyncEntry> downloadable,
      List<String> unresolvable,
      List<String> versionDiff,
      List<String> skipKeys,
      Runnable onProceed,
      Runnable onCancel
   ) {
      super(Component.translatable("all".equals(scope) ? "voxlink.modsync.title_all" : "voxlink.modsync.title"));
      this.roomCode = roomCode;
      this.downloadable = downloadable;
      this.unresolvable = unresolvable;
      this.versionDiff = versionDiff;
      this.skipKeys = (List<String>)(skipKeys == null ? new ArrayList<>() : skipKeys);
      this.onProceed = onProceed;
      this.onCancel = onCancel;
      this.buildLines();
   }

   private void buildLines() {
      this.lines.clear();
      if (!this.downloadable.isEmpty()) {
         this.lines.add(new Object[]{0, Component.translatable("voxlink.modsync.missing_header", new Object[]{this.downloadable.size()}).getString()});

         for (ModSyncEntry e : this.downloadable) {
            this.lines
               .add(
                  new Object[]{
                     1,
                     cleanText(
                        cleanText(
                           cleanText(
                              Component.translatable("voxlink.modsync.entry_line", new Object[]{e.title, e.versionNumber, humanSize(e.size)}).getString()
                           )
                        )
                     )
                  }
               );
         }
      }

      if (!this.versionDiff.isEmpty()) {
         this.lines.add(new Object[]{0, Component.translatable("voxlink.modsync.diff_header").getString()});

         for (String d : this.versionDiff) {
            this.lines.add(new Object[]{1, cleanText(d)});
         }
      }

      if (!this.unresolvable.isEmpty()) {
         this.lines.add(new Object[]{0, Component.translatable("voxlink.modsync.unresolvable_header").getString()});

         for (String u : this.unresolvable) {
            this.lines.add(new Object[]{1, cleanText(u)});
         }
      }
   }

   static String cleanText(String s) {
      return s == null ? "" : s.replaceAll("§.", "").replaceAll("\\p{Cntrl}", "").trim();
   }

   @Override
   protected void init() {
      super.init();
      this.clearOurWidgets();
      int centerX = this.width / 2;
      int listTop = 50;
      int statusReserve = this.statusText.isEmpty() ? 0 : 13;
      int footerReserve = (this.state == ModSyncSelectScreen.State.SELECTING ? 78 : 46) + statusReserve;
      this.rowsPerPage = Math.max(3, (this.height - listTop - footerReserve) / 12);
      this.listBottomY = listTop + this.rowsPerPage * 12;
      int pages = this.pageCount();
      if (this.page >= pages) {
         this.page = Math.max(0, pages - 1);
      }

      boolean paging = pages > 1 && this.state == ModSyncSelectScreen.State.SELECTING;
      if (paging) {
         int py = this.height - footerReserve + 2;
         this.prevBtn = Button.builder(Component.translatable("voxlink.modsync.prev_page"), b -> this.flipPage(-1)).bounds(centerX - 90, py, 40, 16).build();
         this.addRenderableWidget(this.prevBtn);
         this.nextBtn = Button.builder(Component.translatable("voxlink.modsync.next_page"), b -> this.flipPage(1)).bounds(centerX + 50, py, 40, 16).build();
         this.addRenderableWidget(this.nextBtn);
      }

      if (this.state == ModSyncSelectScreen.State.SELECTING) {
         int by = this.height - 54;
         boolean hasDownloads = !this.downloadable.isEmpty();
         if (hasDownloads) {
            long selBytes = 0L;

            for (ModSyncEntry e : this.downloadable) {
               selBytes += e.size;
            }

            this.downloadBtn = Button.builder(
                  Component.translatable("voxlink.modsync.download_mods", new Object[]{this.downloadable.size(), humanSize(selBytes)}),
                  b -> this.startDownload()
               )
               .bounds(centerX - 130, by, 128, 20)
               .build();
            this.addRenderableWidget(this.downloadBtn);
         }

         this.joinBtn = Button.builder(
               Component.translatable(this.versionDiff.isEmpty() ? "voxlink.modsync.join_directly" : "voxlink.modsync.join_anyway"), b -> {
                  ModSyncGuestService.markSkipped(this.skipKeys);
                  this.onProceed.run();
               }
            )
            .bounds(hasDownloads ? centerX + 2 : centerX - 65, by, hasDownloads ? 128 : 130, 20)
            .build();
         this.addRenderableWidget(this.joinBtn);
         this.backBtn = Button.builder(Component.translatable("voxlink.modsync.back"), b -> this.onCancel.run()).bounds(centerX - 65, by + 24, 130, 20).build();
         this.addRenderableWidget(this.backBtn);
      } else {
         this.cancelBtn = Button.builder(Component.translatable("voxlink.modsync.cancel_download"), b -> {
            if (!this.downloadAbort) {
               this.downloadAbort = true;
               b.setMessage(Component.translatable("voxlink.modsync.cancelling"));
               b.active = false;
               this.statusText = Component.translatable("voxlink.modsync.cancelling").getString();
               this.statusColor = -5592406;
            }
         }).bounds(centerX - 65, this.height - 30, 130, 20).build();
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
      if (this.state != ModSyncSelectScreen.State.DOWNLOADING) {
         this.state = ModSyncSelectScreen.State.DOWNLOADING;
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
   }

   private void downloadLoop() {
      Path modsDir = ModSyncEnv.getModsDir();

      try {
         Files.createDirectories(modsDir);
      } catch (Exception var15) {
         this.failThrough(Component.translatable("voxlink.modsync.download_failed", new Object[]{"mods dir"}).getString());
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
         if (!isSafeFileName(e.fileName, modsDirNorm)) {
            ModSyncLog.warn("skip unsafe fileName entry: {} (title={})", e.fileName, e.title);
            this.unresolvable.add(e.fileName);
            lastError = e.title;
            this.overallPct = this.totalBytes > 0L ? (int)Math.min(100L, this.bytesDone * 100L / this.totalBytes) : 0;
         } else {
            try {
               long bytes = ModrinthClient.downloadVerified(
                  e.downloadUrl, e.sha512, e.sha1 != null && !e.sha1.isEmpty() ? e.sha1 : null, e.fileName, modsDir, () -> this.downloadAbort
               );
               synchronized (this.succeededIdx) {
                  this.succeededIdx.add(i);
               }

               this.doneFiles++;
               this.bytesDone += bytes;
               long elapsed = Math.max(1L, System.currentTimeMillis() - this.startMs);
               double speed = (double)this.bytesDone / ((double)elapsed / 1000.0);
               long remainB = Math.max(0L, this.totalBytes - this.bytesDone);
               this.etaSec = speed > 0.0 ? (long)((double)remainB / speed) : -1L;
            } catch (ModrinthClient.AbortedException var16) {
               ModSyncLog.info("download {} cancelled by user", e.fileName);
               this.abortThrough();
               return;
            } catch (Exception var17) {
               ModSyncLog.warn("download {} failed: {}", e.fileName, var17.toString());
               this.unresolvable.add(e.fileName);
               lastError = e.title;
            }

            this.overallPct = this.totalBytes > 0L ? (int)Math.min(100L, this.bytesDone * 100L / this.totalBytes) : 0;
         }
      }

      if (this.downloadAbort) {
         this.abortThrough();
      } else {
         int okCount = this.succeededIdx.size();
         if (okCount > 0 && lastError == null) {
            Minecraft.getInstance().execute(() -> Minecraft.getInstance().gui.setScreen(new ModSyncRestartScreen(okCount)));
         } else {
            this.failThrough(Component.translatable("voxlink.modsync.download_failed", new Object[]{lastError == null ? "?" : clip(lastError, 40)}).getString());
         }
      }
   }

   private void abortThrough() {
      this.state = ModSyncSelectScreen.State.SELECTING;
      this.statusText = Component.translatable("voxlink.modsync.download_cancelled").getString();
      this.statusColor = -5592406;
      this.downloadAbort = false;
      Minecraft.getInstance().execute(this::rebuildInPlace);
   }

   private void failThrough(String msg) {
      this.state = ModSyncSelectScreen.State.SELECTING;
      String hint = Component.translatable("voxlink.modsync.retry_hint").getString();
      this.statusText = hint.isEmpty() ? msg : msg + " " + hint;
      this.statusColor = -43691;
      Minecraft.getInstance().execute(this::rebuildInPlace);
   }

   public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
      super.extractRenderState(graphics, mouseX, mouseY, partialTick);
      int centerX = this.width / 2;
      this.drawCenteredClipped(graphics, this.title.getString(), centerX, 10, -1);
      this.drawCenteredClipped(graphics, Component.translatable("voxlink.modsync.subtitle", new Object[]{this.roomCode}).getString(), centerX, 24, -5592406);
      if (this.state == ModSyncSelectScreen.State.SELECTING) {
         this.renderList(graphics, centerX);
         int pages = this.pageCount();
         if (pages > 1) {
            this.drawCenteredClipped(
               graphics,
               Component.translatable("voxlink.modsync.page_ind", new Object[]{this.page + 1, pages}).getString(),
               centerX,
               this.height - 74,
               -5592406
            );
         }

         if (!this.statusText.isEmpty()) {
            this.drawCenteredClipped(graphics, this.statusText, centerX, Math.min(this.listBottomY + 2, this.height - 86), this.statusColor);
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
         boolean header = (Integer)line[0] == 0;
         String text = String.valueOf(line[1]);
         if (header) {
            this.drawCenteredClipped(graphics, text, centerX, y, -171);
         } else {
            this.drawCenteredClipped(graphics, text, centerX, y, -1);
         }

         y += 12;
      }
   }

   private void renderProgress(GuiGraphicsExtractor graphics, int centerX) {
      int top = Math.max(60, this.height / 2 - 52);
      this.drawCenteredClipped(graphics, Component.translatable("voxlink.modsync.progress_title").getString(), centerX, top, -1);
      int barW = Math.min(220, this.width - 40);
      int barY = top + 18;
      graphics.fill(centerX - barW / 2, barY, centerX + barW / 2, barY + 6, -13948111);
      int fillW = (int)((long)barW * (long)Math.max(0, Math.min(100, this.overallPct)) / 100L);
      if (fillW > 0) {
         graphics.fill(centerX - barW / 2, barY, centerX - barW / 2 + fillW, barY + 6, -11141291);
      }

      int y = barY + 14;
      this.drawCenteredClipped(
         graphics, Component.translatable("voxlink.modsync.progress_overall", new Object[]{this.overallPct + "%"}).getString(), centerX, y, -5592406
      );
      y += 12;
      int okNow;
      synchronized (this.succeededIdx) {
         okNow = this.succeededIdx.size();
      }

      this.drawCenteredClipped(
         graphics, Component.translatable("voxlink.modsync.progress_completed", new Object[]{okNow, this.totalFiles}).getString(), centerX, y, -1
      );
      y += 12;
      this.drawCenteredClipped(
         graphics,
         Component.translatable("voxlink.modsync.progress_current", new Object[]{cleanText(clip(this.currentName, 36))}).getString(),
         centerX,
         y,
         -171
      );
      y += 12;
      int remaining = Math.max(0, this.totalFiles - okNow);
      this.drawCenteredClipped(
         graphics, Component.translatable("voxlink.modsync.progress_remaining", new Object[]{remaining}).getString(), centerX, y, -5592406
      );
      y += 12;
      this.drawCenteredClipped(
         graphics, Component.translatable("voxlink.modsync.progress_eta", new Object[]{fmtEta(this.etaSec)}).getString(), centerX, y, -5592406
      );
   }

   public boolean shouldCloseOnEsc() {
      String key = this.state == ModSyncSelectScreen.State.DOWNLOADING ? "voxlink.modsync.esc_block_downloading" : "voxlink.modsync.esc_block_selecting";
      this.statusText = Component.translatable(key).getString();
      this.statusColor = -171;
      this.rebuildInPlace();
      return false;
   }

   static String clip(String s, int max) {
      if (s == null) {
         return "";
      } else {
         return s.length() <= max ? s : s.substring(0, max - 1) + "…";
      }
   }

   static String humanSize(long bytes) {
      if (bytes <= 0L) {
         return "?";
      } else {
         return bytes < 1048576L ? bytes / 1024L + "KB" : String.format("%.1fMB", (double)bytes / 1024.0 / 1024.0);
      }
   }

   static String fmtEta(long sec) {
      if (sec < 0L) {
         return "--:--";
      } else {
         long m = sec / 60L;
         if (m > 99L) {
            return "--:--";
         } else {
            long s = sec % 60L;
            return m + ":" + (s < 10L ? "0" : "") + s;
         }
      }
   }

   static boolean isSafeFileName(String fileName, Path modsDirNorm) {
      if (fileName == null || fileName.isEmpty()) {
         return false;
      } else if (fileName.contains("..")) {
         return false;
      } else if (fileName.indexOf(47) < 0 && fileName.indexOf(92) < 0) {
         if (fileName.indexOf(58) >= 0) {
            return false;
         } else {
            for (int i = 0; i < fileName.length(); i++) {
               if (Character.isISOControl(fileName.charAt(i))) {
                  return false;
               }
            }

            try {
               Path resolved = modsDirNorm.resolve(fileName).normalize();
               return resolved.startsWith(modsDirNorm);
            } catch (Exception var3) {
               return false;
            }
         }
      } else {
         return false;
      }
   }

   private static enum State {
      SELECTING,
      DOWNLOADING;
   }
}
