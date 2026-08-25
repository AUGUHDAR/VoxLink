package icu.wuhui.voxlink.ui;

import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.config.LogUploadState;
import icu.wuhui.voxlink.network.PunchProfile;
import icu.wuhui.voxlink.room.ConnectionManager;
import icu.wuhui.voxlink.room.ConnectionState;
import icu.wuhui.voxlink.room.RoomInfo;
import icu.wuhui.voxlink.terracotta.TerracottaBinary;
import icu.wuhui.voxlink.terracotta.TerracottaManager;
import java.awt.Desktop;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.GuiGraphics;

public class VoxLinkScreen extends VoxLinkScreenBase {
   private static final int SIDE_MARGIN = 20;
   private final Screen parent;
   private RoomInfo lastRenderedRoom = null;
   private boolean lastRenderedIsHost = false;
   private boolean lastRenderedUsingRelay = false;
   private boolean needsRebuild = true;
   private long lastRebuildTime = 0L;
   private boolean lastPausedState = false;
   private Button terracottaDownloadBtn;
   private Button pauseResumeBtn;
   private Button cancelDownloadBtn;
      private final List<int[]> codeClickAreas = new ArrayList<>();
   private final List<String> codeClickTexts = new ArrayList<>();
   private static final int BTN_W = 200;
   private static final int BTN_H = 20;
   private static final int GAP = 4;
   private static final int BOTTOM_MARGIN = 28;
   private static final int HALF_BTN_W = 98;
   private static final int TOP_OFFSET_Y = 30;
   private static final int TOP_MIN_Y = 60;
   private static final int TITLE_Y = 20;
   private static final int CODE_Y = 36;
   private static final int MODE_Y = 50;
   private static final int TERRACOTTA_CODE_Y = 50;
   private static final int MODE_WITH_TC_Y = 64;
   private static final int RELAY_HINT_Y_OFFSET = 24;
   private static final int RELAY_SLOGAN_Y_OFFSET = 12;
   private static final int RELAY_HINT_SPACE = 28;
   private static final int PROGRESS_TEXT_Y_OFFSET = 12;
   private static final int CODE_CLICK_H = 9;
   private static final int COLOR_ORANGE = -22016;

   private static boolean isInSingleplayerWorld() {
      return Minecraft.getInstance().getSingleplayerServer() != null;
   }

   public VoxLinkScreen(Screen parent) {
      super(Component.translatable("voxlink.title"));
      this.parent = parent;
   }

   @Override
   protected void init() {
      super.init();
      this.needsRebuild = true;
   }

   public void tick() {
      RoomInfo currentRoom = VoxLinkMod.getRoomManager().getCurrentRoom();
      boolean currentIsHost = currentRoom != null && currentRoom.isHost();
      boolean currentUsingRelay = currentRoom != null && currentRoom.isUsingRelay();
      long now = System.currentTimeMillis();
      boolean stateChanged = !Objects.equals(currentRoom, this.lastRenderedRoom)
         || currentIsHost != this.lastRenderedIsHost
         || currentUsingRelay != this.lastRenderedUsingRelay;
      boolean downloadStateChanged = this.needsRebuild;
      if (!this.needsRebuild && TerracottaManager.isDownloading()) {
         boolean pausedNow = TerracottaManager.isDownloadPaused();
         if (pausedNow != this.lastPausedState) {
            downloadStateChanged = true;
            this.lastPausedState = pausedNow;
         }
      }

      if (this.needsRebuild || stateChanged || downloadStateChanged || now - this.lastRebuildTime >= 250L) {
         this.lastRenderedRoom = currentRoom;
         this.lastRenderedIsHost = currentIsHost;
         this.lastRenderedUsingRelay = currentUsingRelay;
         this.lastRebuildTime = now;
         this.rebuildWidgetsForState();
         if (!TerracottaManager.isDownloading() && !this.needsRebuild) {
            if (TerracottaManager.isBinaryReady()) {
               this.needsRebuild = true;
            }

            if (this.pauseResumeBtn != null) {
               this.needsRebuild = true;
            }
         }
      }
   }

   private void rebuildWidgetsForState() {
      this.clearOurWidgets();
      int centerX = this.width / 2;
      RoomInfo currentRoom = VoxLinkMod.getRoomManager().getCurrentRoom();
      int bottomY = this.height - 28;
      int relayY = bottomY - 20 - 4;
      int hintSpace = currentRoom == null ? 28 : 0;
      int uploadLogY = relayY - 20 - 4;
      int configY = uploadLogY - 20 - 4 - hintSpace;
      boolean platformSupported = TerracottaBinary.isPlatformSupported();
      boolean showDownload = platformSupported && !TerracottaManager.isBinaryReady();
      boolean isDownloading = TerracottaManager.isDownloading();
      int downloadY = configY - 20 - 4;
      int topBtnCount;
      if (currentRoom != null) {
         topBtnCount = 1;
      } else if (isInSingleplayerWorld()) {
         topBtnCount = 1;
      } else {
         topBtnCount = 3;
      }

      int topSectionHeight = topBtnCount * 20 + (topBtnCount - 1) * 4;
      int progressSpace = showDownload && isDownloading ? 25 : 4;
      int bottomSectionTop = showDownload ? downloadY : configY;
      int topStartY = Math.min(this.height / 2 - 30, bottomSectionTop - topSectionHeight - progressSpace);
      topStartY = Math.max(topStartY, 60);
      if (currentRoom != null) {
         if (currentRoom.isHost()) {
            this.addRenderableWidget(
               Button.builder(
                     Component.translatable("voxlink.manage_room"), button -> Minecraft.getInstance().setScreen(new ManageRoomScreen(this, currentRoom))
                  )
                  .bounds(centerX - 100, topStartY, 200, 20)
                  .build()
            );
         }
      } else if (isInSingleplayerWorld()) {
         this.addRenderableWidget(
            Button.builder(Component.translatable("voxlink.create_room"), button -> Minecraft.getInstance().setScreen(new CreateRoomScreen(this)))
               .bounds(centerX - 100, topStartY, 200, 20)
               .build()
         );
      } else {
         this.addRenderableWidget(
            Button.builder(Component.translatable("voxlink.join_by_code"), button -> Minecraft.getInstance().setScreen(new JoinRoomScreen(this)))
               .bounds(centerX - 100, topStartY, 200, 20)
               .build()
         );
         this.addRenderableWidget(
            Button.builder(Component.translatable("voxlink.browse_rooms"), button -> Minecraft.getInstance().setScreen(new RoomBrowserScreenBase(this)))
               .bounds(centerX - 100, topStartY + 20 + 4, 200, 20)
               .build()
         );
      }

      this.addRenderableWidget(
         Button.builder(Component.translatable("voxlink.website"), button -> this.openWebsite()).bounds(centerX - 100, topStartY + 48, 200, 20).build()
      );
      if (showDownload) {
         if (isDownloading) {
            this.terracottaDownloadBtn = null;
            boolean paused = TerracottaManager.isDownloadPaused();
            this.pauseResumeBtn = Button.builder(Component.translatable(paused ? "voxlink.terracotta.resume" : "voxlink.terracotta.pause"), button -> {
               if (TerracottaManager.isDownloadPaused()) {
                  TerracottaManager.resumeDownload();
               } else {
                  TerracottaManager.pauseDownload();
               }

               this.needsRebuild = true;
            }).bounds(centerX - 100, downloadY, 98, 20).build();
            this.addRenderableWidget(this.pauseResumeBtn);
            this.cancelDownloadBtn = Button.builder(Component.translatable("voxlink.terracotta.cancel"), button -> {
               TerracottaManager.cancelDownload();
               this.needsRebuild = true;
            }).bounds(centerX + 4, downloadY, 98, 20).build();
            this.addRenderableWidget(this.cancelDownloadBtn);
         } else {
            Component label;
            if (TerracottaManager.isDownloadFailed()) {
               label = Component.translatable("voxlink.terracotta.download_failed");
            } else {
               label = Component.translatable("voxlink.terracotta.download");
            }

            this.terracottaDownloadBtn = Button.builder(label, button -> this.startTerracottaDownload()).bounds(centerX - 100, downloadY, 200, 20).build();
            this.addRenderableWidget(this.terracottaDownloadBtn);
            this.pauseResumeBtn = null;
            this.cancelDownloadBtn = null;
         }
      } else {
         this.terracottaDownloadBtn = null;
         this.pauseResumeBtn = null;
         this.cancelDownloadBtn = null;
      }

      this.addRenderableWidget(
         Button.builder(Component.translatable("voxlink.terracotta.config"), button -> Minecraft.getInstance().setScreen(new TerracottaConfigScreen(this)))
            .bounds(centerX - 100, configY, 200, 20)
            .build()
      );
      boolean uploadLogOn = VoxLinkMod.getConfig().isLogUploadEnabled();
      Button uploadLogBtn = Button.builder(
            Component.translatable("voxlink.log_upload.toggle", new Object[]{Component.translatable(uploadLogOn ? "voxlink.log_upload.on" : "voxlink.log_upload.off")}),
            button -> {
               // 开关持久化到配置文件，不再用内存静态变量
               boolean newVal = !VoxLinkMod.getConfig().isLogUploadEnabled();
               VoxLinkMod.getConfig().setLogUploadEnabled(newVal);
               LogUploadState.setLogUploadEnabled(newVal);
               VoxLinkMod.getConfig().save();
               this.needsRebuild = true;
            }
         )
         .bounds(centerX - 100, uploadLogY, 200, 20)
         .build();
      this.addRenderableWidget(uploadLogBtn);
      boolean relayOn = VoxLinkMod.getConfig().isRelayEnabled();
      boolean usingRelay = currentRoom != null && currentRoom.isUsingRelay();
      Button relayBtn = Button.builder(
            Component.translatable("voxlink.relay.toggle", new Object[]{Component.translatable(relayOn ? "voxlink.relay.on" : "voxlink.relay.off")}),
            button -> {
               boolean newVal = !VoxLinkMod.getConfig().isRelayEnabled();
               VoxLinkMod.getConfig().setRelayEnabled(newVal);
               VoxLinkMod.getConfig().save();
               if (!newVal) {
                  VoxLinkMod.getRoomManager().getConnectionManager().stopRelay();
               }

               this.needsRebuild = true;
            }
         )
         .bounds(centerX - 100, relayY, 200, 20)
         .build();
      if (usingRelay) {
         relayBtn.active = false;
      }

      this.addRenderableWidget(relayBtn);
      this.addRenderableWidget(Button.builder(Component.translatable("voxlink.back"), button -> this.onClose()).bounds(centerX - 100, bottomY, 200, 20).build());
      this.needsRebuild = false;
   }

   private static Component buildDownloadLabel(TerracottaBinary.DownloadProgress p) {
      if (TerracottaManager.isDownloadPaused()) {
         return Component.translatable("voxlink.terracotta.paused");
      }

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
   }

   private void openWebsite() {
      try {
         String url = VoxLinkMod.getConfig().getServerUrl();
         if (url == null || url.isEmpty()) {
            url = "https://p2p.wuhui.icu";
         }

         if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
         }

         URI uri = URI.create(url);
         if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().browse(uri);
            return;
         }

         String os = System.getProperty("os.name", "").toLowerCase();
         String[] cmd;
         if (os.contains("win")) {
            cmd = new String[]{"rundll32", "url.dll,FileProtocolHandler", url};
         } else if (!os.contains("mac") && !os.contains("darwin")) {
            cmd = new String[]{"xdg-open", url};
         } else {
            cmd = new String[]{"open", url};
         }

         Runtime.getRuntime().exec(cmd);
      } catch (Exception e) {
         VoxLinkMod.LOGGER.warn("[VoxLinkScreen] Failed to open website: {}", e.getMessage());
      }
   }

   private void startTerracottaDownload() {
      if (!TerracottaManager.isDownloading()) {
         TerracottaManager.startDownload(progress -> Minecraft.getInstance().execute(() -> {
            if (progress.failed || progress.done) {
               this.needsRebuild = true;
            }
         }));
      }
   }

   public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
      super.render(graphics, mouseX, mouseY, partialTick);
      int centerX = this.width / 2;
      this.drawCenteredString(graphics, this.title.getString(), centerX, 20, VoxLinkColors.WHITE);
      RoomInfo currentRoom = VoxLinkMod.getRoomManager().getCurrentRoom();
      int maxWidth = this.width - 20;
      int bottomY = this.height - 28;
      int relayY = bottomY - 20 - 4;
      int uploadLogY = relayY - 20 - 4;
      int configY = uploadLogY - 20 - 4 - (currentRoom == null ? 28 : 0);
      int downloadY = configY - 20 - 4;
      this.codeClickAreas.clear();
      this.codeClickTexts.clear();
      if (currentRoom != null) {
         String codeText = Component.translatable("voxlink.chat.room_code_label").getString()
            + ChatFormatting.GREEN.toString()
            + ChatFormatting.BOLD.toString()
            + "["
            + Component.translatable("voxlink.chat.click_to_copy").getString()
            + "]";
         this.drawCenteredClipped(graphics, codeText, centerX, 36, VoxLinkColors.WARNING, maxWidth);
         int codeW = this.font.width(codeText);
         this.codeClickAreas.add(new int[]{centerX - codeW / 2, 36, codeW, 9});
         this.codeClickTexts.add(currentRoom.getCode());
         String tcCode = currentRoom.getTerracottaCode();
         boolean hasTc = tcCode != null && !tcCode.isEmpty();
         int modeY = hasTc ? 64 : 50;
         if (hasTc) {
            String tcText = Component.translatable("voxlink.chat.terracotta_code_label", new Object[]{""}).getString().trim()
               + " "
               + ChatFormatting.AQUA.toString()
               + ChatFormatting.BOLD.toString()
               + "["
               + Component.translatable("voxlink.chat.click_to_copy").getString()
               + "]";
            this.drawCenteredClipped(graphics, tcText, centerX, 50, VoxLinkColors.INFO, maxWidth);
            int tcW = this.font.width(tcText);
            this.codeClickAreas.add(new int[]{centerX - tcW / 2, 50, tcW, 9});
            this.codeClickTexts.add(tcCode);
         }

         if (!currentRoom.isHost()) {
            Component connMode;
            if (Minecraft.getInstance().player != null) {
               connMode = Component.translatable("voxlink.connection.connected");
            } else {
               connMode = currentRoom.getConnectionMode();
            }

            if (connMode != null && !connMode.getString().isEmpty()) {
               this.drawCenteredClipped(graphics, connMode.getString(), centerX, modeY, VoxLinkColors.GRAY, maxWidth);
               ConnectionState cs = ConnectionState.getCurrent();
               if (cs != ConnectionState.CONNECTED && cs != ConnectionState.IDLE && cs != ConnectionState.FAILED) {
                  StringBuilder detail = new StringBuilder();
                  detail.append(cs.displayName).append(" ").append(ConnectionState.getStateDurationMs() / 1000L).append("s");
                  if (PunchProfile.AGGRESSIVE == ConnectionManager.getInstance().getActivePunchProfile()) {
                     detail.append(" | AGGRESSIVE");
                  }

                  String natType = currentRoom.getNatType();
                  if (natType != null && (natType.contains("symmetric") || natType.contains("sym"))) {
                     detail.append(" | ").append(natType);
                  }

                  this.drawCenteredClipped(graphics, detail.toString(), centerX, modeY + 11, VoxLinkColors.MUTED, maxWidth);
               }
            }
         }
      } else {
         this.drawCenteredClipped(
            graphics, Component.translatable("voxlink.relay.hint").getString(), centerX, uploadLogY - RELAY_HINT_Y_OFFSET - 2, VoxLinkColors.GRAY, maxWidth
         );
         this.drawCenteredClipped(
            graphics, Component.translatable("voxlink.relay.slogan").getString(), centerX, uploadLogY - RELAY_SLOGAN_Y_OFFSET - 2, VoxLinkColors.MUTED, maxWidth
         );
      }

      if (TerracottaManager.isDownloading() && this.pauseResumeBtn != null) {
         TerracottaBinary.DownloadProgress p = TerracottaManager.getLastProgress();
         Component progressLabel = buildDownloadLabel(p);
         this.drawCenteredString(graphics, progressLabel.getString(), centerX, downloadY - 12, VoxLinkColors.INFO);
      }

      if (!TerracottaBinary.isPlatformSupported()) {
         this.drawCenteredClipped(graphics, Component.translatable("voxlink.terracotta.unsupported_platform").getString(), centerX, currentRoom != null ? configY - 12 : 36, COLOR_ORANGE, maxWidth);
      }
   }

   public boolean mouseClicked(double mouseX, double mouseY, int button) {
            double mx = mouseX;
      double my = mouseY;

      for (int i = 0; i < this.codeClickAreas.size(); i++) {
         int[] a = this.codeClickAreas.get(i);
         if (mx >= a[0] && mx < a[0] + a[2] && my >= a[1] && my < a[1] + a[3]) {
            String text = this.codeClickTexts.get(i);
            Minecraft.getInstance().keyboardHandler.setClipboard(text);
            if (Minecraft.getInstance().player != null) {
               Minecraft.getInstance().player.sendSystemMessage(Component.translatable("voxlink.chat.copied_to_clipboard", new Object[]{text}));
            }

            return true;
         }
      }

      return super.mouseClicked(mouseX, mouseY, button);
   }

   public void onClose() {
      Minecraft.getInstance().setScreen(this.parent);
   }
}
