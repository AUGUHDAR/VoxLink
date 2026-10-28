package icu.wuhui.voxlink.ui;

import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.config.LogUploadState;
import icu.wuhui.voxlink.network.NatLabels;
import icu.wuhui.voxlink.network.PunchProfile;
import icu.wuhui.voxlink.platform.Text;
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
import net.minecraft.client.gui.GuiGraphics;
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

   // ===== 垂直预算布局：init 与 render 共用同一份几何，任何分辨率下文字与按钮互不侵入 =====
   private static final int L_ROW = 20;
   private static final int L_GAP = 4;
   private static final int L_STEP = L_ROW + L_GAP;
   private static final int L_BOTTOM_MARGIN = 28;
   /** 房间为空时，上传日志行上方的"中继说明+口号"装饰带高度。 */
   private static final int L_DECOR_H = 28;
   /** 下载进行中在下载行上方预留给进度文字的带高。 */
   private static final int L_PROGRESS_RESERVE = 16;
   /** 平台不支持文案在配置行上方预留给文案的带高。 */
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

   private static boolean isInSingleplayerWorld() {
      return Minecraft.getInstance().getSingleplayerServer() != null;
   }

   // ===== 原版融入: 暂停菜单同款模糊底 + 信息面板 + 整行可点击 =====
   /** 面板半透明暗底(模糊之上仍保有分组感)。 */
   private static final int PANEL_FILL = 0x66000000;
   /** 面板 1px 描边(原版冷灰)。 */
   private static final int PANEL_BORDER = 0xFF3E4C55;
   /** 可点击行悬停底条(原版列表条目同款白底高亮)。 */
   private static final int ROW_HOVER_FILL = 0x30FFFFFF;

   private void computeLayout() {
      RoomInfo currentRoom = VoxLinkMod.getRoomManager().getCurrentRoom();
      boolean platformSupported = TerracottaBinary.isPlatformSupported();
      boolean showDownloadRow = platformSupported && !TerracottaManager.isBinaryReady();
      boolean downloading = showDownloadRow && TerracottaManager.isDownloading();
      boolean platformNote = !platformSupported;

      // 头部安全线：无房间只让标题(20..29)；有房间要避开房号(36..45)/陶瓦码(50..59)，
      // 非房主还有连接状态两行(64..79)
      int headerFloor = currentRoom == null ? 40 : (currentRoom.isHost() ? 64 : 84);

      this.lyProgressText = Integer.MIN_VALUE;
      this.lyHintText = Integer.MIN_VALUE;
      this.lySloganText = Integer.MIN_VALUE;
      this.lyPlatformNote = Integer.MIN_VALUE;

      // 自底向上三段固定：设置|返回 -> 相关链接|反馈 -> 主操作栈(1~2 行) -> 陶瓦下载区
      // 主操作行贴列排布，装饰带(hint/slogan)有富余空间才出现，不再插进按钮栈中间
      this.lyBack = this.height - L_BOTTOM_MARGIN;
      this.lyWebsite = this.lyBack - L_STEP;
      int mainRows = currentRoom == null ? (isInSingleplayerWorld() ? 1 : 2) : 1;
      int mainLastRow = this.lyWebsite - L_STEP;
      int mainFirstRow = mainLastRow - (mainRows - 1) * L_STEP;
      this.lyDownloadRow = showDownloadRow ? mainFirstRow - L_STEP : Integer.MIN_VALUE;

      int reserve = (downloading ? L_PROGRESS_RESERVE : 0) + (platformNote ? L_PLATFORM_NOTE_RESERVE : 0);
      int zoneTop = showDownloadRow ? this.lyDownloadRow : mainFirstRow;
      int decorCeiling = zoneTop - reserve - L_GAP;
      this.decorVisible = decorCeiling - L_DECOR_H >= headerFloor + 4;
      if (currentRoom != null) {
         // 有房: 装饰带固定贴房码面板之下
         int panelBottom = currentRoom.isHost() ? 64 : 84;
         this.lyHintText = panelBottom + 12;
         this.lySloganText = this.lyHintText + 12;
      } else if (this.decorVisible) {
         this.lySloganText = decorCeiling - 4;
         this.lyHintText = this.lySloganText - 12;
      }

      if (downloading) {
         this.lyProgressText = this.lyDownloadRow - 13;
      }

      if (platformNote) {
         this.lyPlatformNote = mainFirstRow - 14;
      }

      this.lyTopStart = Math.max(headerFloor + 4, mainFirstRow);
      this.layoutValid = true;
   }

   public VoxLinkScreen(Screen parent) {
      super(Component.translatable("voxlink.title"));
      this.parent = parent;
   }

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
      // 主操作栈贴 website 行向上排（与 computeLayout 的 mainRows 推导一致）
      int mainLastRow = this.lyWebsite - L_STEP;
      int mainFirstRow = mainLastRow - (currentRoom == null ? (isInSingleplayerWorld() ? 1 : 2) - 1 : 0) * L_STEP;
      if (currentRoom != null) {
         if (currentRoom.isHost()) {
            this.addRenderableWidget(
               Button.builder(
                     Component.translatable("voxlink.manage_room"), button -> Minecraft.getInstance().setScreen(new ManageRoomScreen(this, currentRoom))
                  )
                  .bounds(centerX - 100, mainLastRow, 200, 20)
                  .build()
            );
         }
      } else if (isInSingleplayerWorld()) {
         this.addRenderableWidget(
            Button.builder(
                  Component.translatable(CreateFlowState.isActive() ? "voxlink.create_flow.back_label" : "voxlink.create_room"),
                  button -> Minecraft.getInstance().setScreen(CreateFlowState.isActive() ? new CreatingRoomScreen(new CreateRoomScreen(this)) : new CreateRoomScreen(this))
               )
               .bounds(centerX - 100, mainLastRow, 200, 20)
               .build()
         );
      } else {
         this.addRenderableWidget(
            Button.builder(Component.translatable("voxlink.join_by_code"), button -> Minecraft.getInstance().setScreen(new JoinRoomScreen(this)))
               .bounds(centerX - 100, mainFirstRow, 200, 20)
               .build()
         );
         this.addRenderableWidget(
            Button.builder(Component.translatable("voxlink.browse_rooms"), button -> Minecraft.getInstance().setScreen(new RoomBrowserScreenBase(this)))
               .bounds(centerX - 100, mainLastRow, 200, 20)
               .build()
         );
      }

      if (currentRoom == null) {
         // 无房: 相关链接 | 反馈
         this.addRenderableWidget(
            Button.builder(Component.translatable("voxlink.website"), button -> Minecraft.getInstance().setScreen(new RelatedLinksScreen(this))).bounds(centerX - 102, this.lyWebsite, HALF_BTN_W, 20).build()
         );
         this.addRenderableWidget(
            Button.builder(Component.translatable("voxlink.feedback"), button -> Minecraft.getInstance().setScreen(new FeedbackScreen(this))).bounds(centerX + 4, this.lyWebsite, HALF_BTN_W, 20).build()
         );
      }
      // 设置 | 返回 —— 半宽对称行(有房时是仅有的两个次级入口)
      this.addRenderableWidget(
         Button.builder(Component.translatable("voxlink.terracotta.config"), button -> Minecraft.getInstance().setScreen(new TerracottaConfigScreen(this))).bounds(centerX - 102, this.lyBack, HALF_BTN_W, 20).build()
      );
      this.addRenderableWidget(
         Button.builder(Component.translatable("voxlink.back"), button -> this.onClose()).bounds(centerX + 4, this.lyBack, HALF_BTN_W, 20).build()
      );
      if (currentRoom == null && showDownload && this.lyDownloadRow != Integer.MIN_VALUE) {
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
            }).bounds(centerX - 102, this.lyDownloadRow, HALF_BTN_W, 20).build();
            this.addRenderableWidget(this.pauseResumeBtn);
            this.cancelDownloadBtn = Button.builder(Component.translatable("voxlink.terracotta.cancel"), button -> {
               TerracottaManager.cancelDownload();
               this.needsRebuild = true;
            }).bounds(centerX + 4, this.lyDownloadRow, HALF_BTN_W, 20).build();
            this.addRenderableWidget(this.cancelDownloadBtn);
         } else {
            Component label = Component.translatable(TerracottaManager.isDownloadFailed()
               ? "voxlink.terracotta.download_failed"
               : "voxlink.terracotta.download");

            this.terracottaDownloadBtn = Button.builder(label, button -> this.startTerracottaDownload()).bounds(centerX - 100, this.lyDownloadRow, 200, 20).build();
            this.addRenderableWidget(this.terracottaDownloadBtn);
            this.pauseResumeBtn = null;
            this.cancelDownloadBtn = null;
         }
      } else {
         this.terracottaDownloadBtn = null;
         this.pauseResumeBtn = null;
         this.cancelDownloadBtn = null;
      }

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



   private void startTerracottaDownload() {
      if (!TerracottaManager.isDownloading()) {
         TerracottaManager.startDownload(progress -> Minecraft.getInstance().execute(() -> {
            if (progress.failed || progress.done) {
               this.needsRebuild = true;
            }
         }));
      }
   }

   private static final int CODE_CLICK_H = 9;
   private static final int COLOR_ORANGE = -22016;

   /** 行悬停判定: mouseX/Y 落在 [x0,x1)x[y-3,y+12) 行条内。 */
   private boolean isRowHovered(double mx, double my, int x0, int x1, int textY) {
      return mx >= x0 && mx < x1 && my >= textY - 3 && my < textY + 12;
   }

   @Override
   public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
      if (!this.layoutValid) {
         this.computeLayout();
      }

      super.render(graphics, mouseX, mouseY, partialTick);
      int centerX = this.width / 2;
      this.drawCenteredString(graphics, this.title.getString(), centerX, 20, VoxLinkColors.WHITE);
      RoomInfo currentRoom = VoxLinkMod.getRoomManager().getCurrentRoom();
      int maxWidth = this.width - 20;
      this.codeClickAreas.clear();
      this.codeClickTexts.clear();
      if (currentRoom != null) {
         String tcCode = currentRoom.getTerracottaCode();
         boolean hasTc = tcCode != null && !tcCode.isEmpty();
         // 信息面板: 包住房码/陶瓦码/连接状态行, 半透明暗底+1px 描边(不遮信息, 只做分组)
         int panelX0 = centerX - 125;
         int panelX1 = centerX + 125;
         int modeY = hasTc ? 64 : 50;
         boolean isGuest = !currentRoom.isHost();
         int detailY = modeY + 11;
         int infoBottom = isGuest ? detailY + 14 : (hasTc ? 59 : 45);
         int panelY1 = Math.min(infoBottom + 4, this.lyTopStart - 2);
         graphics.fill(panelX0, 30, panelX1, panelY1, PANEL_FILL);
         this.outlineBorder(graphics, panelX0, 30, panelX1 - panelX0, panelY1 - 30, PANEL_BORDER);
         int innerL = panelX0 + 4;
         int innerR = panelX1 - 4;
         int panelInnerW = innerR - innerL;

         // 房码行: 整行可点击(hover 底条), 点击区=面板内整行
         String codeText = Component.translatable("voxlink.chat.room_code_label").getString()
            + ChatFormatting.GREEN.toString()
            + ChatFormatting.BOLD.toString()
            + "["
            + Component.translatable("voxlink.chat.click_to_copy").getString()
            + "]";
         boolean codeHover = this.isRowHovered(mouseX, mouseY, innerL, innerR, 36);
         if (codeHover) {
            graphics.fill(innerL, 33, innerR, 47, ROW_HOVER_FILL);
         }
         this.drawCenteredClipped(graphics, codeText, centerX, 36, VoxLinkColors.WARNING, panelInnerW);
         int codeW = this.font.width(codeText);
         this.codeClickAreas.add(new int[]{innerL, 33, innerR - innerL, 14});
         this.codeClickTexts.add(currentRoom.getCode());
         if (hasTc) {
            String tcText = Component.translatable("voxlink.chat.terracotta_code_label", new Object[]{""}).getString().trim()
               + " "
               + ChatFormatting.AQUA.toString()
               + ChatFormatting.BOLD.toString()
               + "["
               + Component.translatable("voxlink.chat.click_to_copy").getString()
               + "]";
            boolean tcHover = this.isRowHovered(mouseX, mouseY, innerL, innerR, 50);
            if (tcHover) {
               graphics.fill(innerL, 47, innerR, 61, ROW_HOVER_FILL);
            }
            this.drawCenteredClipped(graphics, tcText, centerX, 50, VoxLinkColors.INFO, panelInnerW);
            int tcW = this.font.width(tcText);
            this.codeClickAreas.add(new int[]{innerL, 47, innerR - innerL, 14});
            this.codeClickTexts.add(tcCode);
         }

         if (isGuest) {
            Text connMode;
            if (Minecraft.getInstance().player != null) {
               connMode = Text.translatable("voxlink.connection.connected");
            } else {
               connMode = currentRoom.getConnectionMode();
            }

            if (connMode != null && !connMode.toPlainString().isEmpty()) {
               this.drawCenteredClipped(graphics, connMode.toPlainString(), centerX, modeY, VoxLinkColors.GRAY, panelInnerW);
               ConnectionState cs = ConnectionState.getCurrent();
               if (cs != ConnectionState.CONNECTED && cs != ConnectionState.IDLE && cs != ConnectionState.FAILED) {
                  StringBuilder detail = new StringBuilder();
                  detail.append(cs.displayName).append(" ").append(ConnectionState.getStateDurationMs() / 1000L).append("s");
                  if (PunchProfile.AGGRESSIVE == ConnectionManager.getInstance().getActivePunchProfile()) {
                     detail.append(" | AGGRESSIVE");
                  }

                  String natType = currentRoom.getNatType();
                  if (NatLabels.isSpecific(natType)) {
                     detail.append(" | ").append(NatLabels.nameOfRaw(natType));
                  }

                  this.drawCenteredClipped(graphics, detail.toString(), centerX, modeY + 11, VoxLinkColors.MUTED, panelInnerW);
               }
            }
         }
      } else if (currentRoom != null && this.lyHintText != Integer.MIN_VALUE) {
         // 有房: 装饰带画在房码面板之下(位置由 computeLayout 的 headerFloor 段给出)
         if (this.lyHintText != Integer.MIN_VALUE) {
            this.drawCenteredClipped(graphics, Component.translatable("voxlink.relay.hint").getString(), centerX, this.lyHintText, VoxLinkColors.GRAY, maxWidth);
         }

         if (this.lySloganText != Integer.MIN_VALUE) {
            this.drawCenteredClipped(
               graphics, Component.translatable("voxlink.relay.slogan").getString(), centerX, this.lySloganText, VoxLinkColors.MUTED, maxWidth
            );
         }
      } else if (this.decorVisible) {
         if (this.lyHintText != Integer.MIN_VALUE) {
            this.drawCenteredClipped(graphics, Component.translatable("voxlink.relay.hint").getString(), centerX, this.lyHintText, VoxLinkColors.GRAY, maxWidth);
         }

         if (this.lySloganText != Integer.MIN_VALUE) {
            this.drawCenteredClipped(
               graphics, Component.translatable("voxlink.relay.slogan").getString(), centerX, this.lySloganText, VoxLinkColors.MUTED, maxWidth
            );
         }
      }

      if (this.lyProgressText != Integer.MIN_VALUE && this.pauseResumeBtn != null) {
         TerracottaBinary.DownloadProgress p = TerracottaManager.getLastProgress();
         Component progressLabel = buildDownloadLabel(p);
         this.drawCenteredString(graphics, progressLabel.getString(), centerX, this.lyProgressText, VoxLinkColors.INFO);
      }

      if (this.lyPlatformNote != Integer.MIN_VALUE) {
         this.drawCenteredClipped(graphics, Component.translatable("voxlink.terracotta.unsupported_platform").getString(), centerX, this.lyPlatformNote, COLOR_ORANGE, maxWidth);
      }
   }

   public boolean mouseClicked(MouseButtonEvent event, boolean processed) {
      if (processed) {
         return super.mouseClicked(event, processed);
      }

      double mx = event.x();
      double my = event.y();

      for (int i = 0; i < this.codeClickAreas.size(); i++) {
         int[] a = this.codeClickAreas.get(i);
         if (mx >= a[0] && mx < a[0] + a[2] && my >= a[1] && my < a[1] + a[3]) {
            String text = this.codeClickTexts.get(i);
            Minecraft.getInstance().keyboardHandler.setClipboard(text);
            if (Minecraft.getInstance().player != null) {
               Minecraft.getInstance().player.displayClientMessage(Component.translatable("voxlink.chat.copied_to_clipboard", new Object[]{text}), false);
            }

            return true;
         }
      }

      return super.mouseClicked(event, processed);
   }
   public void onClose() {
      Minecraft.getInstance().setScreen(this.parent);
   }
}
