package icu.wuhui.voxlink.ui;

import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.config.LogUploadState;
import icu.wuhui.voxlink.network.PunchProfile;
import icu.wuhui.voxlink.room.ConnectionManager;
import icu.wuhui.voxlink.room.ConnectionState;
import icu.wuhui.voxlink.room.RoomInfo;
import icu.wuhui.voxlink.terracotta.TerracottaBinary;
import icu.wuhui.voxlink.terracotta.TerracottaManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

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
   private static final int HALF_BTN_W = 98;
   private static final int L_ROW = 20;
   private static final int L_GAP = 4;
   private static final int L_STEP = 24;
   private static final int L_BOTTOM_MARGIN = 28;
   private static final int L_DECOR_H = 28;
   private static final int L_PROGRESS_RESERVE = 16;
   private static final int L_PLATFORM_NOTE_RESERVE = 15;
   private boolean layoutValid = false;
   private boolean decorVisible = false;
   private int lyTopStart;
   private int lyWebsite;
   private int lyDownloadRow = Integer.MIN_VALUE;
   private int lyConfig;
   private int lyUploadLog;
   private int lyRelay;
   private int lyBack;
   private int lyProgressText = Integer.MIN_VALUE;
   private int lyHintText = Integer.MIN_VALUE;
   private int lySloganText = Integer.MIN_VALUE;
   private int lyPlatformNote = Integer.MIN_VALUE;
   private static final int CODE_CLICK_H = 9;
   private static final int COLOR_ORANGE = -22016;

   private static boolean isInSingleplayerWorld() {
      return Minecraft.getInstance().getSingleplayerServer() != null;
   }

   private void computeLayout() {
      RoomInfo currentRoom = VoxLinkMod.getRoomManager().getCurrentRoom();
      boolean platformSupported = TerracottaBinary.isPlatformSupported();
      boolean showDownloadRow = platformSupported && !TerracottaManager.isBinaryReady();
      boolean downloading = showDownloadRow && TerracottaManager.isDownloading();
      boolean platformNote = !platformSupported;
      int topRows = currentRoom == null && !isInSingleplayerWorld() ? 2 : 1;
      int sectionH = topRows * 20 + (topRows - 1) * 4;
      int headerFloor = currentRoom == null ? 40 : (currentRoom.isHost() ? 64 : 84);
      this.lyProgressText = Integer.MIN_VALUE;
      this.lyHintText = Integer.MIN_VALUE;
      this.lySloganText = Integer.MIN_VALUE;
      this.lyPlatformNote = Integer.MIN_VALUE;
      int maxAttempt = showDownloadRow ? 2 : 1;

      for (int attempt = 0; attempt <= maxAttempt; attempt++) {
         int back = this.height - 28;
         int relay = back - 24;
         int uploadLog = relay - 24;
         boolean decor = currentRoom == null && attempt == 0;
         boolean dropConfig = attempt >= 2 && showDownloadRow;
         int config = dropConfig ? Integer.MIN_VALUE : (decor ? uploadLog - 24 - 28 : uploadLog - 24);
         int stackBase = dropConfig ? uploadLog : config;
         int downloadRow = showDownloadRow ? stackBase - 24 : Integer.MIN_VALUE;
         int stackTop = showDownloadRow ? downloadRow : stackBase;
         int websiteRow = stackTop - 24;
         if (showDownloadRow && downloading) {
            websiteRow = downloadRow - 13 - 4 - 20;
         }

         int reserve = (downloading ? 40 : 0) + (platformNote ? 15 : 0);
         int limit = Math.min(stackTop - reserve, websiteRow - 4);
         int ideal = Math.min(this.height / 2 - 30, limit - sectionH);
         if (ideal >= headerFloor || attempt == maxAttempt) {
            this.decorVisible = decor;
            this.lyBack = back;
            this.lyRelay = relay;
            this.lyUploadLog = uploadLog;
            this.lyConfig = config;
            this.lyDownloadRow = downloadRow;
            this.lyTopStart = Math.max(headerFloor + 4, ideal);
            int wy = Math.max(websiteRow, this.lyTopStart + sectionH - 20 + 24);
            if (showDownloadRow && wy + 20 > downloadRow) {
               wy = Math.max(websiteRow, this.lyTopStart + sectionH - 20 + 4);
            }

            if (showDownloadRow && downloading && wy + 20 > downloadRow - 13 - 4) {
               wy = Math.max(websiteRow, Math.min(wy, downloadRow - 13 - 4 - 20));
            }

            this.lyWebsite = wy;
            if (decor) {
               this.lySloganText = uploadLog - 14;
               this.lyHintText = uploadLog - 26;
            }

            if (downloading) {
               this.lyProgressText = downloadRow - 13;
            }

            if (platformNote) {
               this.lyPlatformNote = stackBase - 14;
            }
            break;
         }
      }

      this.layoutValid = true;
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
      this.computeLayout();
      int centerX = this.width / 2;
      RoomInfo currentRoom = VoxLinkMod.getRoomManager().getCurrentRoom();
      boolean platformSupported = TerracottaBinary.isPlatformSupported();
      boolean showDownload = platformSupported && !TerracottaManager.isBinaryReady();
      boolean isDownloading = TerracottaManager.isDownloading();
      int topStartY = this.lyTopStart;
      if (currentRoom != null) {
         if (currentRoom.isHost()) {
            this.addRenderableWidget(
               Button.builder(
                     Component.translatable("voxlink.manage_room"), button -> Minecraft.getInstance().gui.setScreen(new ManageRoomScreen(this, currentRoom))
                  )
                  .bounds(centerX - 100, topStartY, 200, 20)
                  .build()
            );
         }
      } else if (isInSingleplayerWorld()) {
         this.addRenderableWidget(
            Button.builder(
                  Component.translatable(CreateFlowState.isActive() ? "voxlink.create_flow.back_label" : "voxlink.create_room"),
                  button -> Minecraft.getInstance()
                        .gui
                        .setScreen((Screen)(CreateFlowState.isActive() ? new CreatingRoomScreen(new CreateRoomScreen(this)) : new CreateRoomScreen(this)))
               )
               .bounds(centerX - 100, topStartY, 200, 20)
               .build()
         );
      } else {
         this.addRenderableWidget(
            Button.builder(Component.translatable("voxlink.join_by_code"), button -> Minecraft.getInstance().gui.setScreen(new JoinRoomScreen(this)))
               .bounds(centerX - 100, topStartY, 200, 20)
               .build()
         );
         this.addRenderableWidget(
            Button.builder(Component.translatable("voxlink.browse_rooms"), button -> Minecraft.getInstance().gui.setScreen(new RoomBrowserScreenBase(this)))
               .bounds(centerX - 100, topStartY + 24, 200, 20)
               .build()
         );
      }

      if (this.lyWebsite != Integer.MIN_VALUE) {
         this.addRenderableWidget(
            Button.builder(Component.translatable("voxlink.website"), button -> Minecraft.getInstance().gui.setScreen(new RelatedLinksScreen(this)))
               .bounds(centerX - 100, this.lyWebsite, 98, 20)
               .build()
         );
         this.addRenderableWidget(
            Button.builder(Component.translatable("voxlink.feedback"), button -> Minecraft.getInstance().gui.setScreen(new FeedbackScreen(this)))
               .bounds(centerX + 4, this.lyWebsite, 98, 20)
               .build()
         );
      }

      if (!showDownload || this.lyDownloadRow == Integer.MIN_VALUE) {
         this.terracottaDownloadBtn = null;
         this.pauseResumeBtn = null;
         this.cancelDownloadBtn = null;
      } else if (isDownloading) {
         this.terracottaDownloadBtn = null;
         boolean paused = TerracottaManager.isDownloadPaused();
         this.pauseResumeBtn = Button.builder(Component.translatable(paused ? "voxlink.terracotta.resume" : "voxlink.terracotta.pause"), button -> {
            if (TerracottaManager.isDownloadPaused()) {
               TerracottaManager.resumeDownload();
            } else {
               TerracottaManager.pauseDownload();
            }

            this.needsRebuild = true;
         }).bounds(centerX - 100, this.lyDownloadRow, 98, 20).build();
         this.addRenderableWidget(this.pauseResumeBtn);
         this.cancelDownloadBtn = Button.builder(Component.translatable("voxlink.terracotta.cancel"), button -> {
            TerracottaManager.cancelDownload();
            this.needsRebuild = true;
         }).bounds(centerX + 4, this.lyDownloadRow, 98, 20).build();
         this.addRenderableWidget(this.cancelDownloadBtn);
      } else {
         Component label;
         if (TerracottaManager.isDownloadFailed()) {
            label = Component.translatable("voxlink.terracotta.download_failed");
         } else {
            label = Component.translatable("voxlink.terracotta.download");
         }

         this.terracottaDownloadBtn = Button.builder(label, button -> this.startTerracottaDownload())
            .bounds(centerX - 100, this.lyDownloadRow, 200, 20)
            .build();
         this.addRenderableWidget(this.terracottaDownloadBtn);
         this.pauseResumeBtn = null;
         this.cancelDownloadBtn = null;
      }

      if (this.lyConfig != Integer.MIN_VALUE) {
         this.addRenderableWidget(
            Button.builder(
                  Component.translatable("voxlink.terracotta.config"), button -> Minecraft.getInstance().gui.setScreen(new TerracottaConfigScreen(this))
               )
               .bounds(centerX - 100, this.lyConfig, 200, 20)
               .build()
         );
      }

      boolean uploadLogOn = VoxLinkMod.getConfig().isLogUploadEnabled();
      Button uploadLogBtn = Button.builder(
            Component.translatable(
               "voxlink.log_upload.toggle", new Object[]{Component.translatable(uploadLogOn ? "voxlink.log_upload.on" : "voxlink.log_upload.off")}
            ),
            button -> {
               boolean newVal = !VoxLinkMod.getConfig().isLogUploadEnabled();
               VoxLinkMod.getConfig().setLogUploadEnabled(newVal);
               LogUploadState.setLogUploadEnabled(newVal);
               VoxLinkMod.getConfig().save();
               this.needsRebuild = true;
            }
         )
         .bounds(centerX - 100, this.lyUploadLog, 200, 20)
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
         .bounds(centerX - 100, this.lyRelay, 200, 20)
         .build();
      if (usingRelay) {
         relayBtn.active = false;
      }

      this.addRenderableWidget(relayBtn);
      this.addRenderableWidget(
         Button.builder(Component.translatable("voxlink.back"), button -> this.onClose()).bounds(centerX - 100, this.lyBack, 200, 20).build()
      );
      this.needsRebuild = false;
   }

   private static Component buildDownloadLabel(TerracottaBinary.DownloadProgress p) {
      if (TerracottaManager.isDownloadPaused()) {
         return Component.translatable("voxlink.terracotta.paused");
      } else {
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

         String speedStr = p != null ? String.format("%.1f", (double)p.speedBps / 1024.0 / 1024.0) : "0.0";
         return Component.translatable("voxlink.terracotta.downloading", new Object[]{pct, speedStr});
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

   public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
      if (!this.layoutValid) {
         this.computeLayout();
      }

      super.extractRenderState(graphics, mouseX, mouseY, partialTick);
      int centerX = this.width / 2;
      this.drawCenteredString(graphics, this.title.getString(), centerX, 20, -1);
      RoomInfo currentRoom = VoxLinkMod.getRoomManager().getCurrentRoom();
      int maxWidth = this.width - 20;
      this.codeClickAreas.clear();
      this.codeClickTexts.clear();
      if (currentRoom != null) {
         String codeText = Component.translatable("voxlink.chat.room_code_label").getString()
            + ChatFormatting.GREEN.toString()
            + ChatFormatting.BOLD.toString()
            + "["
            + Component.translatable("voxlink.chat.click_to_copy").getString()
            + "]";
         this.drawCenteredClipped(graphics, codeText, centerX, 36, -171, maxWidth);
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
            this.drawCenteredClipped(graphics, tcText, centerX, 50, -5592321, maxWidth);
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
               this.drawCenteredClipped(graphics, connMode.getString(), centerX, modeY, -7829368, maxWidth);
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

                  this.drawCenteredClipped(graphics, detail.toString(), centerX, modeY + 11, -5592406, maxWidth);
               }
            }
         }

         if (VoxLinkMod.getRoomManager().isSignalingDegraded()) {
            int badgeY = modeY + 11;
            if (!currentRoom.isHost()) {
               ConnectionState csNow = ConnectionState.getCurrent();
               if (csNow != ConnectionState.CONNECTED && csNow != ConnectionState.IDLE && csNow != ConnectionState.FAILED) {
                  badgeY = modeY + 22;
               }
            }

            this.drawCenteredClipped(graphics, Component.translatable("voxlink.signaling.degraded_badge").getString(), centerX, badgeY, -171, maxWidth);
         }
      } else if (this.decorVisible) {
         if (this.lyHintText != Integer.MIN_VALUE) {
            this.drawCenteredClipped(graphics, Component.translatable("voxlink.relay.hint").getString(), centerX, this.lyHintText, -7829368, maxWidth);
         }

         if (this.lySloganText != Integer.MIN_VALUE) {
            this.drawCenteredClipped(graphics, Component.translatable("voxlink.relay.slogan").getString(), centerX, this.lySloganText, -5592406, maxWidth);
         }
      }

      if (this.lyProgressText != Integer.MIN_VALUE && this.pauseResumeBtn != null) {
         TerracottaBinary.DownloadProgress p = TerracottaManager.getLastProgress();
         Component progressLabel = buildDownloadLabel(p);
         this.drawCenteredString(graphics, progressLabel.getString(), centerX, this.lyProgressText, -5592321);
      }

      if (this.lyPlatformNote != Integer.MIN_VALUE) {
         this.drawCenteredClipped(
            graphics, Component.translatable("voxlink.terracotta.unsupported_platform").getString(), centerX, this.lyPlatformNote, -22016, maxWidth
         );
      }
   }

   public boolean mouseClicked(MouseButtonEvent event, boolean processed) {
      if (processed) {
         return super.mouseClicked(event, processed);
      } else {
         double mx = event.x();
         double my = event.y();

         for (int i = 0; i < this.codeClickAreas.size(); i++) {
            int[] a = this.codeClickAreas.get(i);
            if (mx >= (double)a[0] && mx < (double)(a[0] + a[2]) && my >= (double)a[1] && my < (double)(a[1] + a[3])) {
               String text = this.codeClickTexts.get(i);
               Minecraft.getInstance().keyboardHandler.setClipboard(text);
               if (Minecraft.getInstance().player != null) {
                  Minecraft.getInstance().player.sendSystemMessage(Component.translatable("voxlink.chat.copied_to_clipboard", new Object[]{text}));
               }

               return true;
            }
         }

         return super.mouseClicked(event, processed);
      }
   }

   public void onClose() {
      Minecraft.getInstance().gui.setScreen(this.parent);
   }
}
