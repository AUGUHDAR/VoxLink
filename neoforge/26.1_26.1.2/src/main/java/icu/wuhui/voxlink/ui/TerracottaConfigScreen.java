package icu.wuhui.voxlink.ui;

import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.terracotta.TerracottaBinary;
import icu.wuhui.voxlink.terracotta.TerracottaManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class TerracottaConfigScreen extends VoxLinkScreenBase {
   private final Screen parent;
   private Button redownloadBtn;
   private Button deleteBinaryBtn;
   private Button pauseResumeBtn;
   private Button cancelBtn;
   private String statusMessage = "";
   private int statusColor = VoxLinkColors.WHITE;
   private boolean lastPausedState = false;
   private static final int BTN_W = 200;
   private static final int BTN_H = 20;
   private static final int GAP = 4;
   private static final int HALF_BTN_W = 98;
   private static final int MIN_FORM_HEIGHT = 44;
   private static final int TITLE_Y = 16;
   private static final int STATUS_LABEL_Y_OFFSET = 14;
   private static final int STATUS_MSG_Y_OFFSET = 6;

   public TerracottaConfigScreen(Screen parent) {
      super(Component.translatable("voxlink.terracotta.config"));
      this.parent = parent;
   }

   @Override
   protected void init() {
      super.init();
      int centerX = this.width / 2;
      boolean isDownloading = TerracottaManager.isDownloading();
      int itemCount = isDownloading ? 8 : 7;
      int formHeight = itemCount * 20 + (itemCount - 1) * 4;
      int y = Math.max(44, (this.height - formHeight) / 2);
      CycleButton<Boolean> updateCheckToggle = CycleButton.onOffBuilder(VoxLinkMod.getConfig().isUpdateCheckEnabled())
         .create(centerX - 100, y, 200, 20, Component.translatable("voxlink.update.check"), (btn, val) -> {
            VoxLinkMod.getConfig().setUpdateCheckEnabled(val);
            VoxLinkMod.getConfig().save();
         });
      this.addRenderableWidget(updateCheckToggle);
      CycleButton<Boolean> modSyncToggle = CycleButton.onOffBuilder(VoxLinkMod.getConfig().isJoinRequiredModsCheck())
         .create(centerX - 100, y + 24, 200, 20, Component.translatable("voxlink.modsync.toggle.join"), (btn, val) -> {
            VoxLinkMod.getConfig().setJoinRequiredModsCheck(val);
            VoxLinkMod.getConfig().save();
         });
      this.addRenderableWidget(modSyncToggle);
      // 简单配置：创建房间时自动收起UI（默认关）
      CycleButton<Boolean> autoCollapseToggle = CycleButton.onOffBuilder(VoxLinkMod.getConfig().isAutoCollapseCreateUi())
         .create(centerX - 100, y + 48, 200, 20, Component.translatable("voxlink.config.auto_collapse_create"), (btn, val) -> {
            VoxLinkMod.getConfig().setAutoCollapseCreateUi(val);
            VoxLinkMod.getConfig().save();
         });
      this.addRenderableWidget(autoCollapseToggle);
      boolean currentParallel = VoxLinkMod.getConfig().isParallelP2P();
      Button parallelToggle = Button.builder(
            Component.translatable(
               "voxlink.terracotta.toggle.join", new Object[]{Component.translatable(currentParallel ? "voxlink.terracotta.on" : "voxlink.terracotta.off")}
            ),
            button -> {
               boolean newVal = !VoxLinkMod.getConfig().isParallelP2P();
               VoxLinkMod.getConfig().setParallelP2P(newVal);
               VoxLinkMod.getConfig().save();
               button.setMessage(
                  Component.translatable(
                     "voxlink.terracotta.toggle.join", new Object[]{Component.translatable(newVal ? "voxlink.terracotta.on" : "voxlink.terracotta.off")}
                  )
               );
            }
         )
         .bounds(centerX - 100, y + 72, 200, 20)
         .build();
      // 下载中禁止切换，避免误以为本次下载/连接会立即应用新设置
      parallelToggle.active = !isDownloading && TerracottaManager.isBinaryReady();
      this.addRenderableWidget(parallelToggle);
      this.deleteBinaryBtn = Button.builder(Component.translatable("voxlink.terracotta.delete_binary"), button -> this.deleteBinary())
         .bounds(centerX - 100, y + 96, 200, 20)
         .build();
      this.deleteBinaryBtn.active = !isDownloading && TerracottaManager.isBinaryReady();
      this.addRenderableWidget(this.deleteBinaryBtn);
      int redownloadY = y + 120;
      Component redownloadLabel = this.buildRedownloadLabel();
      this.redownloadBtn = Button.builder(redownloadLabel, button -> this.startRedownload()).bounds(centerX - 100, redownloadY, 200, 20).build();
      this.redownloadBtn.active = !isDownloading && TerracottaManager.isBinaryReady();
      this.addRenderableWidget(this.redownloadBtn);
      if (isDownloading) {
         int pauseCancelY = y + 144;
         boolean paused = TerracottaManager.isDownloadPaused();
         this.pauseResumeBtn = Button.builder(Component.translatable(paused ? "voxlink.terracotta.resume" : "voxlink.terracotta.pause"), button -> {
            if (TerracottaManager.isDownloadPaused()) {
               TerracottaManager.resumeDownload();
            } else {
               TerracottaManager.pauseDownload();
            }

            this.lastPausedState = TerracottaManager.isDownloadPaused();
            if (this.pauseResumeBtn != null) {
               this.pauseResumeBtn.setMessage(Component.translatable(this.lastPausedState ? "voxlink.terracotta.resume" : "voxlink.terracotta.pause"));
            }
         }).bounds(centerX - 100, pauseCancelY, 98, 20).build();
         this.addRenderableWidget(this.pauseResumeBtn);
         this.cancelBtn = Button.builder(Component.translatable("voxlink.terracotta.cancel"), button -> {
            TerracottaManager.cancelDownload();
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> {
               if (mc.screen == this) {
                  this.init();
               }
            });
         }).bounds(centerX + 4, pauseCancelY, 98, 20).build();
         this.addRenderableWidget(this.cancelBtn);
         this.addRenderableWidget(
            Button.builder(Component.translatable("gui.done"), button -> Minecraft.getInstance().setScreen(this.parent))
               .bounds(centerX - 100, y + 168, 200, 20)
               .build()
         );
      } else {
         this.pauseResumeBtn = null;
         this.cancelBtn = null;
         this.addRenderableWidget(
            Button.builder(Component.translatable("gui.done"), button -> Minecraft.getInstance().setScreen(this.parent))
               .bounds(centerX - 100, y + 144, 200, 20)
               .build()
         );
      }
   }

   private Component buildRedownloadLabel() {
      if (TerracottaManager.isDownloading()) {
         if (TerracottaManager.isDownloadPaused()) {
            return Component.translatable("voxlink.terracotta.paused");
         }

         TerracottaBinary.DownloadProgress p = TerracottaManager.getLastProgress();
         if (p != null && p.stage != null) {
            if ("connecting".equals(p.stage)) {
               return Component.translatable("voxlink.terracotta.connecting");
            }

            if ("extracting".equals(p.stage)) {
               return Component.translatable("voxlink.terracotta.extracting");
            }

            if ("verifying".equals(p.stage)) {
               return Component.translatable("voxlink.terracotta.verifying");
            }
         }

         int pct = p != null ? p.percent : 0;
         if (pct < 0) {
            pct = 0;
         }

         String speedStr = p != null ? String.format("%.1f", p.speedBps / 1024.0 / 1024.0) : "0.0";
         return Component.translatable("voxlink.terracotta.downloading", new Object[]{pct, speedStr});
      } else {
         return TerracottaManager.isDownloadFailed()
            ? Component.translatable("voxlink.terracotta.download_failed")
            : Component.translatable("voxlink.terracotta.redownload");
      }
   }

   public void tick() {
      if (TerracottaManager.isDownloading() && this.redownloadBtn != null) {
         TerracottaBinary.DownloadProgress p = TerracottaManager.getLastProgress();
         if (TerracottaManager.isDownloadPaused()) {
            this.redownloadBtn.setMessage(Component.translatable("voxlink.terracotta.paused"));
         } else if (p != null && p.stage != null) {
            if ("connecting".equals(p.stage)) {
               this.redownloadBtn.setMessage(Component.translatable("voxlink.terracotta.connecting"));
            } else if ("extracting".equals(p.stage)) {
               this.redownloadBtn.setMessage(Component.translatable("voxlink.terracotta.extracting"));
            } else if ("verifying".equals(p.stage)) {
               this.redownloadBtn.setMessage(Component.translatable("voxlink.terracotta.verifying"));
            }
         } else if (p != null) {
            String speedStr = String.format("%.1f", p.speedBps / 1024.0 / 1024.0);
            int pct = p.percent < 0 ? 0 : p.percent;
            this.redownloadBtn.setMessage(Component.translatable("voxlink.terracotta.downloading", new Object[]{pct, speedStr}));
         }

         boolean pausedNow = TerracottaManager.isDownloadPaused();
         if (pausedNow != this.lastPausedState && this.pauseResumeBtn != null) {
            this.pauseResumeBtn.setMessage(Component.translatable(pausedNow ? "voxlink.terracotta.resume" : "voxlink.terracotta.pause"));
            this.lastPausedState = pausedNow;
         }
      }

      if (!TerracottaManager.isDownloading() && this.redownloadBtn != null && !this.redownloadBtn.active) {
         this.redownloadBtn.active = true;
         this.redownloadBtn.setMessage(this.buildRedownloadLabel());
         if (this.deleteBinaryBtn != null) {
            this.deleteBinaryBtn.active = true;
         }

         if (TerracottaManager.isBinaryReady() && !TerracottaManager.isDownloadFailed()) {
            this.statusMessage = Component.translatable("voxlink.terracotta.download_success").getString();
            this.statusColor = VoxLinkColors.SUCCESS;
         }

         Minecraft mc = Minecraft.getInstance();
         mc.execute(() -> {
            if (mc.screen == this) {
               this.init();
            }
         });
      }
   }

   private String statusKey() {
      if (TerracottaManager.isReady()) {
         return TerracottaManager.isException() ? "voxlink.terracotta.status.exception" : "voxlink.terracotta.status.running";
      } else {
         return TerracottaBinary.isReady() ? "voxlink.terracotta.status.ready" : "voxlink.terracotta.status.not_downloaded";
      }
   }

   private void deleteBinary() {
      try {
         TerracottaManager.shutdown();
      } catch (Exception e) {
         VoxLinkMod.LOGGER.warn("Failed to stop Terracotta before delete: {}", e.getMessage());
      }

      Path cacheDir = TerracottaBinary.getCacheDir();

      try {
         deleteRecursively(cacheDir);
         this.statusMessage = Component.translatable("voxlink.terracotta.binary_deleted").getString();
         this.statusColor = VoxLinkColors.SUCCESS;
      } catch (IOException e) {
         VoxLinkMod.LOGGER.warn("Failed to delete Terracotta: {}", e.getMessage());
         this.statusMessage = Component.translatable("voxlink.terracotta.download_failed").getString();
         this.statusColor = VoxLinkColors.ERROR;
      }

      Minecraft mc = Minecraft.getInstance();
      mc.execute(() -> {
         if (mc.screen == this) {
            this.init();
         }
      });
   }

   private static void deleteRecursively(Path path) throws IOException {
      if (Files.isDirectory(path)) {
         try (Stream<Path> stream = Files.list(path)) {
            for (Path p : stream.toList()) {
               deleteRecursively(p);
            }
         }
      }

      Files.deleteIfExists(path);
   }

   private void startRedownload() {
      if (!TerracottaManager.isDownloading()) {
         try {
            TerracottaManager.shutdown();
         } catch (Exception e) {
            VoxLinkMod.LOGGER.warn("Failed to stop Terracotta before re-download: {}", e.getMessage());
         }

         try {
            Files.deleteIfExists(TerracottaBinary.getBinaryPath());
         } catch (IOException var2) {
         }

         if (this.redownloadBtn != null) {
            this.redownloadBtn.active = false;
            this.redownloadBtn.setMessage(Component.translatable("voxlink.terracotta.connecting"));
         }

         if (this.deleteBinaryBtn != null) {
            this.deleteBinaryBtn.active = false;
         }

         TerracottaManager.startDownload(progress -> Minecraft.getInstance().execute(() -> {
            if (progress.failed) {
               if (this.redownloadBtn != null) {
                  this.redownloadBtn.active = true;
                  this.redownloadBtn.setMessage(Component.translatable("voxlink.terracotta.download_failed"));
               }

               if (this.deleteBinaryBtn != null) {
                  this.deleteBinaryBtn.active = true;
               }
            }
         }));
      }
   }

   public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
      super.extractRenderState(graphics, mouseX, mouseY, partialTick);
      int centerX = this.width / 2;
      this.drawCenteredClipped(graphics, this.title.getString(), centerX, 16, VoxLinkColors.WHITE);
      boolean isDownloading = TerracottaManager.isDownloading();
      int itemCount = isDownloading ? 8 : 7;
      int formHeight = itemCount * 20 + (itemCount - 1) * 4;
      int y = Math.max(44, (this.height - formHeight) / 2);
      Component statusLabel = Component.translatable("voxlink.terracotta.status_label", new Object[]{Component.translatable(this.statusKey())});
      this.drawCenteredClipped(graphics, statusLabel.getString(), centerX, y - 14, VoxLinkColors.MUTED);
      if (!this.statusMessage.isEmpty()) {
      }

   }

   public void onClose() {
      Minecraft.getInstance().setScreen(this.parent);
   }
}
