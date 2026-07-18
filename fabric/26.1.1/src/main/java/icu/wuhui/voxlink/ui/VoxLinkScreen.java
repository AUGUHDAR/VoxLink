package icu.wuhui.voxlink.ui;

import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.room.RoomInfo;
import icu.wuhui.voxlink.terracotta.TerracottaBinary;
import icu.wuhui.voxlink.terracotta.TerracottaManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public class VoxLinkScreen extends VoxLinkScreenBase {
    private static final int SIDE_MARGIN = 20;
    private final Screen parent;
    private RoomInfo lastRenderedRoom = null;
    private boolean lastRenderedIsHost = false;
    private boolean needsRebuild = true;
    private long lastRebuildTime = 0;
    private boolean lastPausedState = false;

    private Button terracottaDownloadBtn;
    private Button pauseResumeBtn;
    private Button cancelDownloadBtn;

    //点击复制区域
    private final List<int[]> codeClickAreas = new ArrayList<>();
    private final List<String> codeClickTexts = new ArrayList<>();

    //布局常量
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
    private static final int COLOR_TITLE = 0xFFFFFFFF;
    private static final int COLOR_WARNING = 0xFFFFFF55;
    private static final int COLOR_GRAY = 0xFF888888;
    private static final int COLOR_MUTED = 0xFFAAAAAA;
    private static final int COLOR_ORANGE = 0xFFFFAA00;
    private static final int COLOR_SUCCESS = 0xFF55FF55;
    private static final int COLOR_INFO = 0xFF55FFFF;

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
        needsRebuild = true;
    }

    @Override
    public void tick() {
        RoomInfo currentRoom = VoxLinkMod.getRoomManager().getCurrentRoom();
        boolean currentIsHost = currentRoom != null && currentRoom.isHost();
        long now = System.currentTimeMillis();
        boolean stateChanged = !java.util.Objects.equals(currentRoom, lastRenderedRoom)
                || currentIsHost != lastRenderedIsHost;
        //暂停/取消状态变化需重建
        boolean downloadStateChanged = needsRebuild;
        if (!needsRebuild && TerracottaManager.isDownloading()) {
            boolean pausedNow = TerracottaManager.isDownloadPaused();
            if (pausedNow != lastPausedState) {
                downloadStateChanged = true;
                lastPausedState = pausedNow;
            }
        }
        if (!needsRebuild && !stateChanged && !downloadStateChanged && now - lastRebuildTime < 250) return;
        lastRenderedRoom = currentRoom;
        lastRenderedIsHost = currentIsHost;
        lastRebuildTime = now;
        rebuildWidgetsForState();

        //stage: 下载中文本进度由 extractRenderState 绘制, 无需更新按钮
        //下载完成或失败或取消: 触发重建
        if (!TerracottaManager.isDownloading() && !needsRebuild) {
            if (TerracottaManager.isBinaryReady()) {
                needsRebuild = true;
            }
            if (pauseResumeBtn != null) {
                needsRebuild = true;
            }
        }
    }

    private void rebuildWidgetsForState() {
        clearOurWidgets();
        int centerX = this.width / 2;

        RoomInfo currentRoom = VoxLinkMod.getRoomManager().getCurrentRoom();

        //底部按钮从下往上堆叠
        int bottomY = this.height - BOTTOM_MARGIN; //back
        int relayY = bottomY - BTN_H - GAP;
        //无房间时给中继提示文本留空间, 避免与配置按钮重叠
        int hintSpace = (currentRoom == null) ? RELAY_HINT_SPACE : 0;
        int configY = relayY - BTN_H - GAP - hintSpace;

        boolean platformSupported = TerracottaBinary.isPlatformSupported();
        //安卓不下载 .so (依赖启动器), 桌面端未就绪时显示下载按钮
        boolean showDownload = platformSupported && !TerracottaBinary.isAndroid() && !TerracottaManager.isBinaryReady();
        boolean isDownloading = TerracottaManager.isDownloading();
        int downloadY = configY - BTN_H - GAP;

        //顶部按钮数量 (决定顶部区域高度, 避免与下载/暂停按钮重叠)
        int topBtnCount;
        if (currentRoom != null) {
            topBtnCount = 1;
        } else if (isInSingleplayerWorld()) {
            topBtnCount = 1;
        } else {
            topBtnCount = 2;
        }
        int topSectionHeight = topBtnCount * BTN_H + (topBtnCount - 1) * GAP;
        //下载中给进度文本留空间
        int progressSpace = (showDownload && isDownloading) ? PROGRESS_TEXT_Y_OFFSET + CODE_CLICK_H + GAP : GAP;
        int bottomSectionTop = showDownload ? downloadY : configY;
        int topStartY = Math.min(this.height / 2 - TOP_OFFSET_Y, bottomSectionTop - topSectionHeight - progressSpace);
        topStartY = Math.max(topStartY, TOP_MIN_Y);

        if (currentRoom != null) {
            if (currentRoom.isHost()) {
                addRenderableWidget(Button.builder(
                        Component.translatable("voxlink.manage_room"),
                        button -> Minecraft.getInstance().setScreen(new ManageRoomScreen(VoxLinkScreen.this, currentRoom))
                ).bounds(centerX - BTN_W / 2, topStartY, BTN_W, BTN_H).build());
            }
        } else if (isInSingleplayerWorld()) {
            addRenderableWidget(Button.builder(
                    Component.translatable("voxlink.create_room"),
                    button -> Minecraft.getInstance().setScreen(new CreateRoomScreen(this))
            ).bounds(centerX - BTN_W / 2, topStartY, BTN_W, BTN_H).build());
        } else {
            addRenderableWidget(Button.builder(
                    Component.translatable("voxlink.join_by_code"),
                    button -> Minecraft.getInstance().setScreen(new JoinRoomScreen(this))
            ).bounds(centerX - BTN_W / 2, topStartY, BTN_W, BTN_H).build());

            addRenderableWidget(Button.builder(
                    Component.translatable("voxlink.browse_rooms"),
                    button -> Minecraft.getInstance().setScreen(new RoomBrowserScreenBase(this))
            ).bounds(centerX - BTN_W / 2, topStartY + BTN_H + GAP, BTN_W, BTN_H).build());
        }

        //下载陶瓦按钮 + 暂停/取消按钮
        if (showDownload) {
            if (isDownloading) {
                //下载中: 暂停/继续 + 取消 (并排一行), 进度用文本显示在上方
                terracottaDownloadBtn = null;
                boolean paused = TerracottaManager.isDownloadPaused();
                pauseResumeBtn = Button.builder(
                        Component.translatable(paused ? "voxlink.terracotta.resume" : "voxlink.terracotta.pause"),
                        button -> {
                            if (TerracottaManager.isDownloadPaused()) {
                                TerracottaManager.resumeDownload();
                            } else {
                                TerracottaManager.pauseDownload();
                            }
                            needsRebuild = true;
                        }
                ).bounds(centerX - BTN_W / 2, downloadY, HALF_BTN_W, BTN_H).build();
                addRenderableWidget(pauseResumeBtn);

                cancelDownloadBtn = Button.builder(
                        Component.translatable("voxlink.terracotta.cancel"),
                        button -> {
                            TerracottaManager.cancelDownload();
                            needsRebuild = true;
                        }
                ).bounds(centerX + GAP, downloadY, HALF_BTN_W, BTN_H).build();
                addRenderableWidget(cancelDownloadBtn);
            } else {
                //非下载中: 单个下载/重试按钮
                Component label;
                if (TerracottaManager.isDownloadFailed()) {
                    label = Component.translatable("voxlink.terracotta.download_failed");
                } else {
                    label = Component.translatable("voxlink.terracotta.download");
                }
                terracottaDownloadBtn = Button.builder(label, button -> startTerracottaDownload())
                        .bounds(centerX - BTN_W / 2, downloadY, BTN_W, BTN_H).build();
                addRenderableWidget(terracottaDownloadBtn);
                pauseResumeBtn = null;
                cancelDownloadBtn = null;
            }
        } else {
            terracottaDownloadBtn = null;
            pauseResumeBtn = null;
            cancelDownloadBtn = null;
        }

        //简单配置
        addRenderableWidget(Button.builder(
                Component.translatable("voxlink.terracotta.config"),
                button -> Minecraft.getInstance().setScreen(new TerracottaConfigScreen(this))
        ).bounds(centerX - BTN_W / 2, configY, BTN_W, BTN_H).build());

        //中继开关
        boolean relayOn = VoxLinkMod.getConfig().isRelayEnabled();
        Component connMode = currentRoom != null ? currentRoom.getConnectionMode() : null;
        boolean usingRelay = connMode != null && connMode.getString().contains(
                Component.translatable("voxlink.relay.connected_via").getString());
        Button relayBtn = Button.builder(
                Component.translatable("voxlink.relay.toggle",
                        Component.translatable(relayOn ? "voxlink.relay.on" : "voxlink.relay.off")),
                button -> {
                    boolean newVal = !VoxLinkMod.getConfig().isRelayEnabled();
                    VoxLinkMod.getConfig().setRelayEnabled(newVal);
                    VoxLinkMod.getConfig().save();
                    button.setMessage(Component.translatable("voxlink.relay.toggle",
                            Component.translatable(newVal ? "voxlink.relay.on" : "voxlink.relay.off")));
                }
        ).bounds(centerX - BTN_W / 2, relayY, BTN_W, BTN_H).build();
        if (usingRelay) relayBtn.active = false;
        addRenderableWidget(relayBtn);

        //返回
        addRenderableWidget(Button.builder(
                Component.translatable("voxlink.back"),
                button -> onClose()
        ).bounds(centerX - BTN_W / 2, bottomY, BTN_W, BTN_H).build());

        needsRebuild = false;
    }

    private static Component buildDownloadLabel(TerracottaBinary.DownloadProgress p) {
        //暂停状态
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
        if (pct < 0) pct = 0;
        String speedStr = p != null ? String.format("%.1f", p.speedBps / 1024.0 / 1024.0) : "0.0";
        return Component.translatable("voxlink.terracotta.downloading", pct, speedStr);
    }

    private void startTerracottaDownload() {
        if (TerracottaManager.isDownloading()) return;
        TerracottaManager.startDownload(progress -> {
            Minecraft.getInstance().execute(() -> {
                if (progress.failed || progress.done) {
                    needsRebuild = true;
                }
            });
        });
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int centerX = this.width / 2;
        drawCenteredString(graphics, this.title.getString(), centerX, TITLE_Y, COLOR_TITLE);

        RoomInfo currentRoom = VoxLinkMod.getRoomManager().getCurrentRoom();
        int maxWidth = this.width - SIDE_MARGIN;

        //底部按钮位置 (与 rebuildWidgetsForState 一致)
        int bottomY = this.height - BOTTOM_MARGIN;
        int relayY = bottomY - BTN_H - GAP;
        int configY = relayY - BTN_H - GAP - ((currentRoom == null) ? RELAY_HINT_SPACE : 0);
        int downloadY = configY - BTN_H - GAP;

        //清空点击区域
        codeClickAreas.clear();
        codeClickTexts.clear();

        if (currentRoom != null) {
            //有房间: 显示房间码+陶瓦房间号 (可点击复制) 不显示明文
            String codeText = Component.translatable("voxlink.chat.room_code_label").getString()
                    + net.minecraft.ChatFormatting.GREEN.toString() + net.minecraft.ChatFormatting.BOLD.toString()
                    + "[" + Component.translatable("voxlink.chat.click_to_copy").getString() + "]";
            drawCenteredClipped(graphics, codeText, centerX, CODE_Y, COLOR_WARNING, maxWidth);
            int codeW = this.font.width(codeText);
            codeClickAreas.add(new int[]{centerX - codeW / 2, CODE_Y, codeW, CODE_CLICK_H});
            codeClickTexts.add(currentRoom.getCode());

            String tcCode = currentRoom.getTerracottaCode();
            boolean hasTc = tcCode != null && !tcCode.isEmpty();
            int modeY = hasTc ? MODE_WITH_TC_Y : MODE_Y;

            if (hasTc) {
                String tcText = Component.translatable("voxlink.chat.terracotta_code_label", "").getString().trim()
                        + " " + net.minecraft.ChatFormatting.AQUA.toString() + net.minecraft.ChatFormatting.BOLD.toString()
                        + "[" + Component.translatable("voxlink.chat.click_to_copy").getString() + "]";
                drawCenteredString(graphics, tcText, centerX, TERRACOTTA_CODE_Y, COLOR_INFO);
                int tcW = this.font.width(tcText);
                codeClickAreas.add(new int[]{centerX - tcW / 2, TERRACOTTA_CODE_Y, tcW, CODE_CLICK_H});
                codeClickTexts.add(tcCode);
            }

            if (!currentRoom.isHost()) {
                Component connMode;
                if (Minecraft.getInstance().player != null) {
                    connMode = Component.translatable("voxlink.connection.connected");
                } else {
                    connMode = currentRoom.getConnectionMode();
                }
                if (connMode != null && !connMode.getString().isEmpty()) {
                    drawCenteredClipped(graphics, connMode.getString(), centerX, modeY, COLOR_GRAY, maxWidth);
                }
            }
        } else {
            //无房间: 中继提示显示在中继按钮正上方
            drawCenteredClipped(graphics,
                    Component.translatable("voxlink.relay.hint").getString(),
                    centerX, relayY - RELAY_HINT_Y_OFFSET, COLOR_GRAY, maxWidth);
            drawCenteredClipped(graphics,
                    Component.translatable("voxlink.relay.slogan").getString(),
                    centerX, relayY - RELAY_SLOGAN_Y_OFFSET, COLOR_MUTED, maxWidth);
        }

        //下载中: 进度文本显示在暂停/取消按钮上方
        if (TerracottaManager.isDownloading() && pauseResumeBtn != null) {
            TerracottaBinary.DownloadProgress p = TerracottaManager.getLastProgress();
            Component progressLabel = buildDownloadLabel(p);
            drawCenteredString(graphics, progressLabel.getString(), centerX,
                    downloadY - PROGRESS_TEXT_Y_OFFSET, COLOR_INFO);
        }

        //平台不支持陶瓦: 显示提示 (顶部)
        if (!TerracottaBinary.isPlatformSupported()) {
            drawCenteredClipped(graphics,
                    Component.translatable("voxlink.terracotta.unsupported_platform").getString(),
                    centerX, CODE_Y, COLOR_ORANGE, maxWidth);
        } else if (TerracottaBinary.isAndroid()) {
            //安卓: 检测启动器集成状态
            if (TerracottaManager.isBinaryReady()) {
                drawCenteredClipped(graphics,
                        Component.translatable("voxlink.terracotta.android_ready").getString(),
                        centerX, CODE_Y, COLOR_SUCCESS, maxWidth);
            } else {
                drawCenteredClipped(graphics,
                        Component.translatable("voxlink.terracotta.android_need_launcher").getString(),
                        centerX, CODE_Y, COLOR_ORANGE, maxWidth);
            }
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean processed) {
        if (processed) return super.mouseClicked(event, processed);
        double mx = event.x();
        double my = event.y();
        for (int i = 0; i < codeClickAreas.size(); i++) {
            int[] a = codeClickAreas.get(i);
            if (mx >= a[0] && mx < a[0] + a[2] && my >= a[1] && my < a[1] + a[3]) {
                String text = codeClickTexts.get(i);
                Minecraft.getInstance().keyboardHandler.setClipboard(text);
                if (Minecraft.getInstance().player != null) {
                    Minecraft.getInstance().player.sendSystemMessage(
                            Component.translatable("voxlink.chat.copied_to_clipboard", text));
                }
                return true;
            }
        }
        return super.mouseClicked(event, processed);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
