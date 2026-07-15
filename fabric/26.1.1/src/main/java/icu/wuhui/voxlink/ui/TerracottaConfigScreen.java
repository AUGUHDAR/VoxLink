package icu.wuhui.voxlink.ui;

import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.terracotta.TerracottaBinary;
import icu.wuhui.voxlink.terracotta.TerracottaManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class TerracottaConfigScreen extends VoxLinkScreenBase {
    private final Screen parent;
    private Button redownloadBtn;
    private Button pauseResumeBtn;
    private Button cancelBtn;
    private String statusMessage = "";
    private int statusColor = COLOR_WHITE;
    private boolean lastPausedState = false;

    private static final int BTN_W = 200;
    private static final int BTN_H = 20;
    private static final int GAP = 4;
    private static final int HALF_BTN_W = 98;
    private static final int MIN_FORM_HEIGHT = 44;
    private static final int TITLE_Y = 16;
    private static final int STATUS_LABEL_Y_OFFSET = 14;
    private static final int STATUS_MSG_Y_OFFSET = 6;
    private static final int COLOR_WHITE = 0xFFFFFFFF;
    private static final int COLOR_SUCCESS = 0xFF55FF55;
    private static final int COLOR_ERROR = 0xFFFF5555;
    private static final int COLOR_MUTED = 0xFFAAAAAA;

    public TerracottaConfigScreen(Screen parent) {
        super(Component.translatable("voxlink.terracotta.config"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        int centerX = this.width / 2;
        boolean isDownloading = TerracottaManager.isDownloading();
        //下载中: 5 项 (toggle, clear, redownload, pause/cancel, done)
        //非下载: 4 项 (toggle, clear, redownload, done)
        int itemCount = isDownloading ? 5 : 4;
        int formHeight = itemCount * BTN_H + (itemCount - 1) * GAP;
        int y = Math.max(MIN_FORM_HEIGHT, (this.height - formHeight) / 2);

        CycleButton<Boolean> parallelToggle = CycleButton.onOffBuilder(VoxLinkMod.getConfig().isParallelP2P())
                .create(centerX - BTN_W / 2, y, BTN_W, BTN_H,
                        Component.translatable("voxlink.terracotta.parallel_p2p"),
                        (btn, val) -> {
                            VoxLinkMod.getConfig().setParallelP2P(val);
                            VoxLinkMod.getConfig().save();
                        });
        addRenderableWidget(parallelToggle);

        Button clearCacheBtn = Button.builder(
                Component.translatable("voxlink.terracotta.clear_cache"),
                button -> clearCache()
        ).bounds(centerX - BTN_W / 2, y + BTN_H + GAP, BTN_W, BTN_H).build();
        addRenderableWidget(clearCacheBtn);

        int redownloadY = y + (BTN_H + GAP) * 2;
        Component redownloadLabel = buildRedownloadLabel();
        redownloadBtn = Button.builder(redownloadLabel, button -> startRedownload())
                .bounds(centerX - BTN_W / 2, redownloadY, BTN_W, BTN_H).build();
        redownloadBtn.active = !isDownloading;
        addRenderableWidget(redownloadBtn);

        if (isDownloading) {
            int pauseCancelY = y + (BTN_H + GAP) * 3;
            boolean paused = TerracottaManager.isDownloadPaused();
            pauseResumeBtn = Button.builder(
                    Component.translatable(paused ? "voxlink.terracotta.resume" : "voxlink.terracotta.pause"),
                    button -> {
                        if (TerracottaManager.isDownloadPaused()) {
                            TerracottaManager.resumeDownload();
                        } else {
                            TerracottaManager.pauseDownload();
                        }
                        lastPausedState = TerracottaManager.isDownloadPaused();
                        if (pauseResumeBtn != null) {
                            pauseResumeBtn.setMessage(Component.translatable(
                                    lastPausedState ? "voxlink.terracotta.resume" : "voxlink.terracotta.pause"));
                        }
                    }
            ).bounds(centerX - BTN_W / 2, pauseCancelY, HALF_BTN_W, BTN_H).build();
            addRenderableWidget(pauseResumeBtn);

            cancelBtn = Button.builder(
                    Component.translatable("voxlink.terracotta.cancel"),
                    button -> {
                        TerracottaManager.cancelDownload();
                        Minecraft.getInstance().execute(() -> this.init());
                    }
            ).bounds(centerX + GAP, pauseCancelY, HALF_BTN_W, BTN_H).build();
            addRenderableWidget(cancelBtn);

            addRenderableWidget(Button.builder(
                    Component.translatable("gui.done"),
                    button -> Minecraft.getInstance().setScreen(parent)
            ).bounds(centerX - BTN_W / 2, y + (BTN_H + GAP) * 4, BTN_W, BTN_H).build());
        } else {
            pauseResumeBtn = null;
            cancelBtn = null;
            addRenderableWidget(Button.builder(
                    Component.translatable("gui.done"),
                    button -> Minecraft.getInstance().setScreen(parent)
            ).bounds(centerX - BTN_W / 2, y + (BTN_H + GAP) * 3, BTN_W, BTN_H).build());
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
            if (pct < 0) pct = 0;
            String speedStr = p != null ? String.format("%.1f", p.speedBps / 1024.0 / 1024.0) : "0.0";
            return Component.translatable("voxlink.terracotta.downloading", pct, speedStr);
        } else if (TerracottaManager.isDownloadFailed()) {
            return Component.translatable("voxlink.terracotta.download_failed");
        } else {
            return Component.translatable("voxlink.terracotta.redownload");
        }
    }

    @Override
    public void tick() {
        if (TerracottaManager.isDownloading() && redownloadBtn != null) {
            TerracottaBinary.DownloadProgress p = TerracottaManager.getLastProgress();
            if (TerracottaManager.isDownloadPaused()) {
                redownloadBtn.setMessage(Component.translatable("voxlink.terracotta.paused"));
            } else if (p != null && p.stage != null) {
                if ("connecting".equals(p.stage)) {
                    redownloadBtn.setMessage(Component.translatable("voxlink.terracotta.connecting"));
                } else if ("extracting".equals(p.stage)) {
                    redownloadBtn.setMessage(Component.translatable("voxlink.terracotta.extracting"));
                } else if ("verifying".equals(p.stage)) {
                    redownloadBtn.setMessage(Component.translatable("voxlink.terracotta.verifying"));
                }
            } else if (p != null) {
                String speedStr = String.format("%.1f", p.speedBps / 1024.0 / 1024.0);
                int pct = p.percent < 0 ? 0 : p.percent;
                redownloadBtn.setMessage(
                    Component.translatable("voxlink.terracotta.downloading", pct, speedStr));
            }
            //暂停/继续按钮文字更新
            boolean pausedNow = TerracottaManager.isDownloadPaused();
            if (pausedNow != lastPausedState && pauseResumeBtn != null) {
                pauseResumeBtn.setMessage(Component.translatable(
                        pausedNow ? "voxlink.terracotta.resume" : "voxlink.terracotta.pause"));
                lastPausedState = pausedNow;
            }
        }
        //下载结束 (完成/失败/取消): 重建页面
        if (!TerracottaManager.isDownloading() && redownloadBtn != null && !redownloadBtn.active) {
            redownloadBtn.active = true;
            redownloadBtn.setMessage(buildRedownloadLabel());
            if (TerracottaManager.isBinaryReady() && !TerracottaManager.isDownloadFailed()) {
                statusMessage = Component.translatable("voxlink.terracotta.download_success").getString();
                statusColor = COLOR_SUCCESS;
            }
            //重建移除暂停/取消按钮
            Minecraft.getInstance().execute(() -> this.init());
        }
    }

    private String statusKey() {
        if (TerracottaManager.isReady()) {
            if (TerracottaManager.isException()) {
                return "voxlink.terracotta.status.exception";
            }
            return "voxlink.terracotta.status.running";
        } else if (TerracottaBinary.isReady()) {
            return "voxlink.terracotta.status.ready";
        }
        return "voxlink.terracotta.status.not_downloaded";
    }

    private void clearCache() {
        Path cacheDir = TerracottaBinary.getCacheDir();
        try {
            deleteRecursively(cacheDir);
            statusMessage = Component.translatable("voxlink.terracotta.cache_cleared").getString();
            statusColor = COLOR_SUCCESS;
        } catch (IOException e) {
            VoxLinkMod.LOGGER.warn("清除陶瓦缓存失败: {}", e.getMessage());
            statusMessage = Component.translatable("voxlink.terracotta.download_failed").getString();
            statusColor = COLOR_ERROR;
        }
        Minecraft.getInstance().execute(() -> this.init());
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (var stream = Files.list(path)) {
                for (Path p : stream.toList()) {
                    deleteRecursively(p);
                }
            }
        }
        Files.deleteIfExists(path);
    }

    private void startRedownload() {
        if (TerracottaManager.isDownloading()) return;
        try {
            Files.deleteIfExists(TerracottaBinary.getBinaryPath());
        } catch (IOException ignored) {}
        if (redownloadBtn != null) {
            redownloadBtn.active = false;
            redownloadBtn.setMessage(Component.translatable("voxlink.terracotta.connecting"));
        }
        TerracottaManager.startDownload(progress -> {
            Minecraft.getInstance().execute(() -> {
                if (progress.failed) {
                    if (redownloadBtn != null) {
                        redownloadBtn.active = true;
                        redownloadBtn.setMessage(Component.translatable("voxlink.terracotta.download_failed"));
                    }
                }
            });
        });
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int centerX = this.width / 2;
        drawCenteredClipped(graphics, this.title.getString(), centerX, TITLE_Y, COLOR_WHITE);

        boolean isDownloading = TerracottaManager.isDownloading();
        int itemCount = isDownloading ? 5 : 4;
        int formHeight = itemCount * BTN_H + (itemCount - 1) * GAP;
        int y = Math.max(MIN_FORM_HEIGHT, (this.height - formHeight) / 2);

        Component statusLabel = Component.translatable("voxlink.terracotta.status_label",
                Component.translatable(statusKey()));
        drawCenteredClipped(graphics, statusLabel.getString(), centerX, y - STATUS_LABEL_Y_OFFSET, COLOR_MUTED);

        if (!statusMessage.isEmpty()) {
            drawCenteredClipped(graphics, statusMessage, centerX, y + formHeight + STATUS_MSG_Y_OFFSET, statusColor);
        }
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
