package icu.wuhui.voxlink.modsync;

import java.nio.file.Path;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import icu.wuhui.voxlink.ui.VoxLinkColors;
import icu.wuhui.voxlink.ui.VoxLinkScreenBase;

/**
 * 必装模组选择/下载屏：勾选缺失模组 → 后台下载（按钮置灰防误操作）→
 * 全部成功进入强制重启屏；也可选择不下载直接加入（可能进不去）。
 */
public class ModSyncSelectScreen extends VoxLinkScreenBase {
   private static final int MAX_VISIBLE_ROWS = 6;

   private final String roomCode;
   private final List<ModSyncEntry> downloadable;
   private final List<String> unresolvable;
   private final List<String> versionDiff;
   private final Runnable onProceed;
   private final Runnable onCancel;

   private final boolean[] selected;
   private final List<Button> rowButtons = new java.util.ArrayList<>();
   private String statusText = "";
   private int statusColor = VoxLinkColors.MUTED;

   private enum State {SELECTING, DOWNLOADING}

   private volatile State state = State.SELECTING;
   private volatile boolean downloadAbort = false;
   private final java.util.Set<Integer> succeeded = new java.util.HashSet<>();

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
      this.selected = new boolean[downloadable.size()];
      for (int i = 0; i < this.selected.length; i++) {
         this.selected[i] = true;
      }
   }

   @Override
   protected void init() {
      super.init();
      this.clearOurWidgets();
      this.rowButtons.clear();
      int centerX = this.width / 2;
      int y = 44;
      int rows = Math.min(this.downloadable.size(), MAX_VISIBLE_ROWS);
      for (int i = 0; i < rows; i++) {
         final int idx = i;
         ModSyncEntry e = this.downloadable.get(i);
         Button btn = Button
            .builder(Component.literal(this.rowLabel(i)), b -> {
               if (ModSyncSelectScreen.this.state == State.SELECTING) {
                  ModSyncSelectScreen.this.selected[idx] = !ModSyncSelectScreen.this.selected[idx];
                  b.setMessage(Component.literal(ModSyncSelectScreen.this.rowLabel(idx)));
               }
            })
            .bounds(centerX - 130, y, 260, 18)
            .build();
         this.rowButtons.add(btn);
         this.addRenderableWidget(btn);
         y += 20;
      }

      int bottomY = Math.max(y + 4, this.height - 52);
      this.downloadBtn = Button
         .builder(
            Component.translatable("voxlink.modsync.download_selected", new Object[0]),
            b -> ModSyncSelectScreen.this.startDownload()
         )
         .bounds(centerX - 130, bottomY, 128, 20)
         .build();
      this.addRenderableWidget(this.downloadBtn);
      this.skipBtn = Button
         .builder(
            Component.translatable("voxlink.modsync.skip_join", new Object[0]),
            b -> ModSyncSelectScreen.this.onProceed.run()
         )
         .bounds(centerX + 2, bottomY, 128, 20)
         .build();
      this.addRenderableWidget(this.skipBtn);
      this.backBtn = Button
         .builder(Component.translatable("voxlink.modsync.back", new Object[0]), b -> ModSyncSelectScreen.this.onCancel.run())
         .bounds(centerX - 65, bottomY + 24, 130, 20)
         .build();
      this.addRenderableWidget(this.backBtn);
      this.applyStateToWidgets();
   }

   private Button downloadBtn;
   private Button skipBtn;
   private Button backBtn;

   private String rowLabel(int i) {
      ModSyncEntry e = this.downloadable.get(i);
      return (this.selected[i] ? "[√] " : "[ ] ")
         + clip(e.title + " " + e.versionNumber, 30)
         + " (" + humanSize(e.size) + ")";
   }

   private void applyStateToWidgets() {
      boolean selecting = this.state == State.SELECTING;
      for (Button b : this.rowButtons) {
         b.active = selecting;
      }

      this.downloadBtn.visible = true;
      this.downloadBtn.active = selecting;
      this.downloadBtn.setMessage(Component.translatable(selecting ? "voxlink.modsync.download_selected" : "voxlink.modsync.cancel_download"));
      this.skipBtn.active = selecting;
      this.backBtn.active = selecting;
      // 下载中：跳过/返回语义都收敛到取消下载（半途文件重启前不会加载）
      if (!selecting) {
         this.skipBtn.visible = false;
      } else {
         this.skipBtn.visible = true;
      }
   }

   private void startDownload() {
      if (this.state == State.DOWNLOADING) {
         // 第二次点击 = 取消
         this.downloadAbort = true;
         return;
      }

      this.state = State.DOWNLOADING;
      this.statusText = Component.translatable("voxlink.modsync.downloading", new Object[]{0, this.countSelected(), ""}).getString();
      this.statusColor = VoxLinkColors.WARNING;
      this.applyStateToWidgets();
      Thread worker = new Thread(() -> ModSyncSelectScreen.this.downloadLoop(), "VoxLink-ModSync-DL");
      worker.setDaemon(true);
      worker.start();
   }

   private int countSelected() {
      int n = 0;
      for (int i = 0; i < this.selected.length; i++) {
         if (this.selected[i] && !this.succeeded.contains(i)) {
            n++;
         }
      }

      return n;
   }

   private void downloadLoop() {
      Path modsDir = ModSyncEnv.getModsDir();
      int total = this.countSelected();
      int done = 0;
      String lastError = null;
      try {
         java.nio.file.Files.createDirectories(modsDir);
      } catch (Exception e) {
         this.finishFailed("mods dir unavailable: " + e.getMessage());
         return;
      }

      for (int i = 0; i < this.downloadable.size(); i++) {
         if (this.downloadAbort) {
            Minecraft.getInstance().execute(this::goCancelAfterAbort);
            return;
         }

         if (!this.selected[i] || this.succeeded.contains(i)) {
            continue;
         }

         ModSyncEntry e = this.downloadable.get(i);
         int idx = i;
         this.setStatus(
            Component.translatable("voxlink.modsync.downloading", new Object[]{done, total, clip(e.title, 24)}).getString(),
            VoxLinkColors.WARNING
         );
         try {
            ModrinthClient.downloadVerified(e.downloadUrl, e.sha512, e.fileName, modsDir);
            this.succeeded.add(idx);
            done++;
         } catch (Exception ex) {
            ModSyncLog.warn("download {} failed: {}", e.fileName, ex.toString());
            lastError = e.title;
         }
      }

      int okCount = this.succeeded.size();
      if (this.downloadAbort) {
         Minecraft.getInstance().execute(this::goCancelAfterAbort);
         return;
      }

      if (okCount > 0 && (lastError == null || this.countSelected() == 0)) {
         Minecraft.getInstance().execute(() -> Minecraft.getInstance().setScreen(new ModSyncRestartScreen(okCount)));
      } else {
         this.finishFailed(lastError);
      }
   }

   private void finishFailed(String err) {
      this.state = State.SELECTING;
      this.setStatus(
         Component.translatable("voxlink.modsync.download_failed", new Object[]{err == null ? "?" : clip(err, 40)}).getString(),
         VoxLinkColors.ERROR
      );
      Minecraft.getInstance().execute(this::initMcSafe);
   }

   private void goCancelAfterAbort() {
      this.state = State.SELECTING;
      this.onCancel.run();
   }

   private void initMcSafe() {
      Minecraft mc = Minecraft.getInstance();
      if (mc.screen == this) {
         this.clearOurWidgets();
         this.init();
      }
   }

   private void setStatus(String text, int color) {
      this.statusText = text;
      this.statusColor = color;
   }

   @Override
   public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
      super.extractRenderState(graphics, mouseX, mouseY, partialTick);
      int centerX = this.width / 2;
      this.drawCenteredString(graphics, this.title.getString(), centerX, 12, VoxLinkColors.WHITE);
      this.drawCenteredString(
         graphics,
         Component.translatable("voxlink.modsync.subtitle", new Object[]{this.roomCode}).getString(),
         centerX,
         26,
         VoxLinkColors.MUTED
      );
      int y = 44 + Math.min(this.downloadable.size(), MAX_VISIBLE_ROWS) * 20 + 2;
      if (!this.versionDiff.isEmpty()) {
         this.drawCenteredString(
            graphics,
            Component.translatable("voxlink.modsync.diff_header", new Object[0]).getString(),
            centerX,
            y,
            VoxLinkColors.WARNING
         );
         y += 10;
         y = this.drawLines(graphics, this.versionDiff, centerX, y, 3, VoxLinkColors.WARNING);
      }

      if (!this.unresolvable.isEmpty()) {
         this.drawCenteredString(
            graphics,
            Component.translatable("voxlink.modsync.unresolvable_header", new Object[0]).getString(),
            centerX,
            y,
            VoxLinkColors.ERROR
         );
         y += 10;
         y = this.drawLines(graphics, this.unresolvable, centerX, y, 3, VoxLinkColors.ERROR);
      }

      if (!this.statusText.isEmpty()) {
         this.drawCenteredString(graphics, this.statusText, centerX, Math.max(y, this.height - 70), this.statusColor);
      }

      if (this.state == State.SELECTING && !this.downloadable.isEmpty()) {
         long totalBytes = 0L;
         for (int i = 0; i < this.downloadable.size(); i++) {
            if (this.selected[i]) {
               totalBytes += this.downloadable.get(i).size;
            }
         }

         String sizeText = Component.translatable("voxlink.modsync.total_size", new Object[]{humanSize(totalBytes)}).getString();
         this.drawCenteredString(graphics, ChatFormatting.GRAY + sizeText, centerX, this.height - 58, VoxLinkColors.MUTED);
      }
   }

   private int drawLines(GuiGraphicsExtractor graphics, List<String> lines, int centerX, int y, int max, int color) {
      int shown = 0;
      for (String line : lines) {
         if (shown >= max) {
            this.drawCenteredString(graphics, "+" + (lines.size() - max) + " …", centerX, y, color);
            y += 10;
            break;
         }

         this.drawCenteredString(graphics, clip(line, 60), centerX, y, color);
         y += 10;
         shown++;
      }

      return y;
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
}
