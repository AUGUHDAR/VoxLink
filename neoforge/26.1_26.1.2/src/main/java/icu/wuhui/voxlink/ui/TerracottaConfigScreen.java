package icu.wuhui.voxlink.ui;

import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.config.LogUploadState;
import icu.wuhui.voxlink.terracotta.TerracottaBinary;
import icu.wuhui.voxlink.terracotta.TerracottaManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
   private Button terracottaDownloadBtn;
   private Button deleteBinaryBtn;
   private Button pauseResumeBtn;
   private Button cancelBtn;
   private final List<String[]> groupTitles = new ArrayList<>();
   private int statusRowY = 0;
   private String groupHint = null;
   private int doneBtnY = 0;
   private String statusMessage = "";
   private int statusColor = VoxLinkColors.WHITE;
   private boolean lastPausedState = false;
   private boolean lastTickDownloading = false;
   private boolean lastTickBinaryReady = false;
   /** 两次确认删除陶瓦：第一次点击仅置位并不删，3 秒内再点才真正执行。 */
   private boolean deleteConfirmArmed = false;
   private long deleteConfirmArmTime = 0L;
   private static final int BTN_W = 200;
   private static final long DELETE_CONFIRM_WINDOW_MS = 3000L;
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
      this.groupTitles.clear();
      int centerX = this.width / 2;
      boolean isDownloading = TerracottaManager.isDownloading();
      boolean binaryReady = TerracottaManager.isBinaryReady();
      // 两列分组(窄屏单列): 联机 / 数据 / 房间 / 陶瓦, 行高 24, 分组标题 12
      // 两列条件: 实际放得下两列(2*196+32), 否则单列——矮屏也优先两列防溢出
      boolean twoCol = this.width >= 424;
      int colW = twoCol ? 196 : Math.min(240, this.width - 40);
      int colL = twoCol ? centerX - 202 : centerX - colW / 2;
      int colR = twoCol ? centerX + 6 : centerX - colW / 2;
      int y = Math.max(40, 36);
      int rowH = 24;
      int titleH = 14;

      // [联机] 组标题右侧带说明小字(关闭后也无法使用他人中继)
      this.addGroupTitle(colL, y, "voxlink.config.group.connection");
      this.groupHint = Component.translatable("voxlink.relay.hint").getString();
      CycleButton<Boolean> relayToggle = CycleButton.onOffBuilder(VoxLinkMod.getConfig().isRelayEnabled())
         .create(colL, y + titleH, colW, 20, Component.translatable("voxlink.relay.toggle"), (btn, val) -> {
            VoxLinkMod.getConfig().setRelayEnabled(val);
            VoxLinkMod.getConfig().save();
            if (!val) {
               VoxLinkMod.getRoomManager().getConnectionManager().stopRelay();
            }
         });
      this.addRenderableWidget(relayToggle);
      if (twoCol) {
         CycleButton<Boolean> parallelToggle = CycleButton.onOffBuilder(VoxLinkMod.getConfig().isParallelP2P())
            .create(colR, y + titleH, colW, 20, Component.translatable("voxlink.terracotta.toggle.join"), (btn, val) -> {
               VoxLinkMod.getConfig().setParallelP2P(val);
               VoxLinkMod.getConfig().save();
            });
         parallelToggle.active = !isDownloading && binaryReady;
         this.addRenderableWidget(parallelToggle);
      } else {
         CycleButton<Boolean> parallelToggle = CycleButton.onOffBuilder(VoxLinkMod.getConfig().isParallelP2P())
            .create(colL, y + titleH + rowH, colW, 20, Component.translatable("voxlink.terracotta.toggle.join"), (btn, val) -> {
               VoxLinkMod.getConfig().setParallelP2P(val);
               VoxLinkMod.getConfig().save();
            });
         parallelToggle.active = !isDownloading && binaryReady;
         this.addRenderableWidget(parallelToggle);
      }
      y += titleH + rowH * (twoCol ? 1 : 2) + 8;

      // [数据]
      this.addGroupTitle(colL, y, "voxlink.config.group.data");
      CycleButton<Boolean> logUploadToggle = CycleButton.onOffBuilder(VoxLinkMod.getConfig().isLogUploadEnabled())
         .create(colL, y + titleH, colW, 20, Component.translatable("voxlink.log_upload.toggle"), (btn, val) -> {
            VoxLinkMod.getConfig().setLogUploadEnabled(val);
            LogUploadState.setLogUploadEnabled(val);
            VoxLinkMod.getConfig().save();
         });
      this.addRenderableWidget(logUploadToggle);
      if (twoCol) {
         CycleButton<Boolean> updateCheckToggle = CycleButton.onOffBuilder(VoxLinkMod.getConfig().isUpdateCheckEnabled())
            .create(colR, y + titleH, colW, 20, Component.translatable("voxlink.update.check"), (btn, val) -> {
               VoxLinkMod.getConfig().setUpdateCheckEnabled(val);
               VoxLinkMod.getConfig().save();
            });
         this.addRenderableWidget(updateCheckToggle);
      } else {
         CycleButton<Boolean> updateCheckToggle = CycleButton.onOffBuilder(VoxLinkMod.getConfig().isUpdateCheckEnabled())
            .create(colL, y + titleH + rowH, colW, 20, Component.translatable("voxlink.update.check"), (btn, val) -> {
               VoxLinkMod.getConfig().setUpdateCheckEnabled(val);
               VoxLinkMod.getConfig().save();
            });
         this.addRenderableWidget(updateCheckToggle);
      }
      y += titleH + rowH * (twoCol ? 1 : 2) + 8;

      // [房间]
      this.addGroupTitle(colL, y, "voxlink.config.group.room");
      CycleButton<Boolean> modSyncToggle = CycleButton.onOffBuilder(VoxLinkMod.getConfig().isJoinRequiredModsCheck())
         .create(colL, y + titleH, colW, 20, Component.translatable("voxlink.modsync.toggle.join"), (btn, val) -> {
            VoxLinkMod.getConfig().setJoinRequiredModsCheck(val);
            VoxLinkMod.getConfig().save();
         });
      this.addRenderableWidget(modSyncToggle);
      if (twoCol) {
         CycleButton<Boolean> autoCollapseToggle = CycleButton.onOffBuilder(VoxLinkMod.getConfig().isAutoCollapseCreateUi())
            .create(colR, y + titleH, colW, 20, Component.translatable("voxlink.config.auto_collapse_create"), (btn, val) -> {
               VoxLinkMod.getConfig().setAutoCollapseCreateUi(val);
               VoxLinkMod.getConfig().save();
            });
         this.addRenderableWidget(autoCollapseToggle);
      } else {
         CycleButton<Boolean> autoCollapseToggle = CycleButton.onOffBuilder(VoxLinkMod.getConfig().isAutoCollapseCreateUi())
            .create(colL, y + titleH + rowH, colW, 20, Component.translatable("voxlink.config.auto_collapse_create"), (btn, val) -> {
               VoxLinkMod.getConfig().setAutoCollapseCreateUi(val);
               VoxLinkMod.getConfig().save();
            });
         this.addRenderableWidget(autoCollapseToggle);
      }
      y += titleH + rowH * (twoCol ? 1 : 2) + 8;

      // [陶瓦]: 状态文字行(组标题右侧同行) + 动作按钮
      this.addGroupTitle(colL, y, "voxlink.config.group.terracotta");
      this.statusRowY = y;
      if (binaryReady || isDownloading) {
         this.deleteBinaryBtn = Button.builder(this.buildDeleteBinaryLabel(), button -> this.onDeleteBinaryClicked())
            .bounds(colL, this.statusRowY + titleH, colW, 20)
            .build();
         this.deleteBinaryBtn.active = !isDownloading && binaryReady;
         this.addRenderableWidget(this.deleteBinaryBtn);
         int rx = twoCol ? colR : colL;
         int ry = this.statusRowY + titleH + (twoCol ? 0 : rowH);
         Component redownloadLabel = this.buildRedownloadLabel();
         this.redownloadBtn = Button.builder(redownloadLabel, button -> this.startRedownload()).bounds(rx, ry, colW, 20).build();
         this.redownloadBtn.active = !isDownloading && binaryReady;
         this.addRenderableWidget(this.redownloadBtn);
      } else {
         // 未安装: 只显示可点的"下载陶瓦", 不渲染永远置灰的删除/重装
         Component label = Component.translatable(TerracottaManager.isDownloadFailed()
            ? "voxlink.terracotta.download_failed"
            : "voxlink.terracotta.download");
         this.terracottaDownloadBtn = Button.builder(label, button -> {
            // 点击立即置灰+变连接中, 防下载期间重复点击; 进度由 tick 刷新
            button.active = false;
            button.setMessage(Component.translatable("voxlink.terracotta.connecting"));
            TerracottaManager.startDownload(progress -> Minecraft.getInstance().execute(() -> {
            }));
         }).bounds(colL, this.statusRowY + titleH, colW, 20).build();
         this.addRenderableWidget(this.terracottaDownloadBtn);
      }
      if (isDownloading) {
         boolean paused = TerracottaManager.isDownloadPaused();
         this.pauseResumeBtn = Button.builder(Component.translatable(paused ? "voxlink.terracotta.resume" : "voxlink.terracotta.pause"), button -> {
            if (TerracottaManager.isDownloadPaused()) {
               TerracottaManager.resumeDownload();
            } else {
               TerracottaManager.pauseDownload();
            }
         }).bounds(colL, this.statusRowY + titleH + rowH, colW, 20).build();
         this.addRenderableWidget(this.pauseResumeBtn);
         this.cancelBtn = Button.builder(Component.translatable("voxlink.terracotta.cancel"), button -> {
            TerracottaManager.cancelDownload();
         }).bounds(colR, this.statusRowY + titleH + rowH, colW, 20).build();
         this.addRenderableWidget(this.cancelBtn);
      } else {
         this.pauseResumeBtn = null;
         this.cancelBtn = null;
      }
      y += titleH + 12 + 20 + rowH + 12;

      // [完成] 整宽置底
      this.doneBtnY = y;
      this.addRenderableWidget(
         Button.builder(Component.translatable("gui.done"), button -> Minecraft.getInstance().setScreen(this.parent))
            .bounds(centerX - 100, y, 200, 20)
            .build()
      );
   }

   private void addGroupTitle(int x, int y, String key) {
      this.groupTitles.add(new String[]{String.valueOf(x), String.valueOf(y), key});
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
      // 下载状态翻转(开始/结束)时重建按钮组: 防"下载中 100% 0.0MB/s"焊死在完成态
      boolean dlNow = TerracottaManager.isDownloading();
      boolean readyNow = TerracottaManager.isBinaryReady();
      if (dlNow != this.lastTickDownloading || readyNow != this.lastTickBinaryReady) {
         this.lastTickDownloading = dlNow;
         this.lastTickBinaryReady = readyNow;
         this.init();
         return;
      }

      // 二次确认超时(3 秒)后自动撤销"武装"状态,把按钮文案还原成"删除陶瓦"
      if (this.deleteConfirmArmed
         && this.deleteConfirmArmTime > 0L
         && System.currentTimeMillis() - this.deleteConfirmArmTime > DELETE_CONFIRM_WINDOW_MS) {
         this.deleteConfirmArmed = false;
         this.deleteConfirmArmTime = 0L;
         if (this.deleteBinaryBtn != null) {
            this.deleteBinaryBtn.setMessage(this.buildDeleteBinaryLabel());
         }
      }

      if (TerracottaManager.isDownloading() && this.terracottaDownloadBtn != null) {
         // 下载全程置灰(防乱按), 文案由下面进度分支刷新
         this.terracottaDownloadBtn.active = false;
         // 首次下载路径: "下载陶瓦"按钮实时变进度文案
         TerracottaBinary.DownloadProgress fp = TerracottaManager.getLastProgress();
         if (TerracottaManager.isDownloadPaused()) {
            this.terracottaDownloadBtn.setMessage(Component.translatable("voxlink.terracotta.paused"));
         } else if (fp != null && fp.stage != null) {
            if ("connecting".equals(fp.stage)) {
               this.terracottaDownloadBtn.setMessage(Component.translatable("voxlink.terracotta.connecting"));
            } else if ("extracting".equals(fp.stage)) {
               this.terracottaDownloadBtn.setMessage(Component.translatable("voxlink.terracotta.extracting"));
            } else if ("verifying".equals(fp.stage)) {
               this.terracottaDownloadBtn.setMessage(Component.translatable("voxlink.terracotta.verifying"));
            }
         } else if (fp != null) {
            String speedStr = String.format("%.1f", fp.speedBps / 1024.0 / 1024.0);
            int pct = fp.percent < 0 ? 0 : fp.percent;
            this.terracottaDownloadBtn.setMessage(Component.translatable("voxlink.terracotta.downloading", new Object[]{pct, speedStr}));
         }
      }

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

   private Component buildDeleteBinaryLabel() {
      return this.deleteConfirmArmed
         ? Component.translatable("voxlink.terracotta.delete_binary_confirm")
         : Component.translatable("voxlink.terracotta.delete_binary");
   }

   /** 两段式确认：第一次点击仅把按钮文案换成"再次点击确认删除"；3 秒内再点才执行删除。 */
   private void onDeleteBinaryClicked() {
      long now = System.currentTimeMillis();
      if (this.deleteConfirmArmed && now - this.deleteConfirmArmTime <= DELETE_CONFIRM_WINDOW_MS) {
         this.deleteConfirmArmed = false;
         this.deleteConfirmArmTime = 0L;
         this.deleteBinary();
      } else {
         this.deleteConfirmArmed = true;
         this.deleteConfirmArmTime = now;
         if (this.deleteBinaryBtn != null) {
            this.deleteBinaryBtn.setMessage(this.buildDeleteBinaryLabel());
         }
      }
   }

   private void deleteBinary() {
      try {
         TerracottaManager.shutdown();
      } catch (Exception e) {
         VoxLinkMod.LOGGER.warn("Failed to stop Terracotta before delete: {}", e.getMessage());
      }

      // 删除范围收窄:仅删陶瓦二进制与解压/下载产物子文件,不再删整个缓存根目录,
      // 以免误清掉 .voxlink/terracotta/ 下其他无关文件/目录
      Path cacheDir = TerracottaBinary.getCacheDir();
      Path binaryPath = TerracottaBinary.getBinaryPath();

      try {
         // 1. 陶瓦可执行/库本体
         if (binaryPath != null) {
            Files.deleteIfExists(binaryPath);
         }

         // 2. 同目录下的 .downloading 半成品压缩包与临时解压子目录
         if (Files.isDirectory(cacheDir)) {
            try (Stream<Path> stream = Files.list(cacheDir)) {
               for (Path p : stream.toList()) {
                  String name = p.getFileName().toString();
                  if (name.endsWith(".downloading") || name.startsWith("extract-")) {
                     deleteRecursively(p);
                  }
               }
            }
         }

         // 3. .download_pending 标记也一并清理,下次走完整流程而非续传
         try {
            Files.deleteIfExists(cacheDir.resolve(".download_pending"));
         } catch (IOException ignored) {
         }

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
            // 删完后重置一次确认态,避免下次进入屏幕时仍处于武装中
            this.deleteConfirmArmed = false;
            this.deleteConfirmArmTime = 0L;
            if (this.deleteBinaryBtn != null) {
               this.deleteBinaryBtn.setMessage(this.buildDeleteBinaryLabel());
            }
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
      for (String[] g : this.groupTitles) {
         this.drawString(graphics, Component.translatable(g[2]).getString(), Integer.parseInt(g[0]), Integer.parseInt(g[1]), VoxLinkColors.INFO);
      }
      // 联机组说明小字: 标题同行右侧
      if (this.groupHint != null && !this.groupTitles.isEmpty()) {
         String[] g0 = this.groupTitles.get(0);
         int hx = Integer.parseInt(g0[0]) + Minecraft.getInstance().font.width(Component.translatable(g0[2]).getString()) + 10;
         this.drawString(graphics, this.groupHint, hx, Integer.parseInt(g0[1]), VoxLinkColors.MUTED);
      }
      // 陶瓦状态行: 紧贴"陶瓦"标题文字右侧(同一行同基线)
      Component statusLabel = Component.translatable("voxlink.terracotta.status_label", new Object[]{Component.translatable(this.statusKey())});
      String[] tt = this.groupTitles.get(this.groupTitles.size() - 1);
      int titleX = Integer.parseInt(tt[0]);
      int titleTextW = Minecraft.getInstance().font.width(Component.translatable(tt[2]).getString());
      this.drawString(graphics, statusLabel.getString(), titleX + titleTextW + 8, Integer.parseInt(tt[1]), VoxLinkColors.MUTED);

      super.extractRenderState(graphics, mouseX, mouseY, partialTick);
      int centerX = this.width / 2;
      this.drawCenteredClipped(graphics, this.title.getString(), centerX, 16, VoxLinkColors.WHITE);
      if (!this.statusMessage.isEmpty()) {
         this.drawCenteredClipped(graphics, this.statusMessage, centerX, this.doneBtnY + 24, VoxLinkColors.WARNING, this.width - 20);
      }

   }

   public void removed() {
      // ESC/切屏撤销"再次点击确认删除"武装态：防止下次进屏按钮仍停留在确认文案
      this.deleteConfirmArmed = false;
      this.deleteConfirmArmTime = 0L;
   }

   public void onClose() {
      Minecraft.getInstance().setScreen(this.parent);
   }
}
