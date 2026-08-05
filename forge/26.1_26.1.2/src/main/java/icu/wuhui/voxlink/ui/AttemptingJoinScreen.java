package icu.wuhui.voxlink.ui;

import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.network.ConnectionHelper;
import icu.wuhui.voxlink.room.RoomInfo;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class AttemptingJoinScreen extends VoxLinkScreenBase {
    private static final int BTN_W = 200;
    private static final int HALF_BTN_W = 100;
    private static final int BTN_H = 20;
    private static final int BTN_Y_OFFSET = 45;
    private static final int TITLE_Y = 15;
    private static final int ROOM_CODE_Y_OFFSET = 30;
    private static final int STATUS_MARGIN = 20;
    //debounce 删掉上方提示行 VoxLink/陶瓦两行上移到中间
    private static final int VOXLINK_STATUS_Y_OFFSET = 0;
    private static final int TERRACOTTA_STATUS_Y_OFFSET = 14;
    private static final int TICK_INTERVAL_MS = 500;
    private static final int RELAY_BTN_GAP = 4;
    private static final int RELAY_FAILED_MSG_MS = 3000;

    private final Screen parent;
    private final String roomCode;
    private final String password;
    //debounce 上方statusMessage已删除 只保留双P2P两行
    //双P2P状态 (text直接存显示文本 monitor轮询connectionMode动态更新VoxLink行)
    private String voxlinkStatusText = "";
    private int voxlinkStatusColor = VoxLinkColors.MUTED;
    private volatile boolean voxlinkFinal = false;
    private String terracottaStatusText = "";
    private int terracottaStatusColor = VoxLinkColors.MUTED;
    private volatile boolean terracottaFinal = false;
    private volatile boolean active = false;
    private boolean joinApiDone = false;
    private volatile boolean joinCompleted = false;
    private volatile java.util.concurrent.ScheduledExecutorService connectionScheduler;
    private java.util.concurrent.ScheduledFuture<?> connectionFuture;
    private int monitorTicks = 0;
    private static final int MAX_MONITOR_TICKS = 120;
    private int bridgeEstablishedAtTick = -1;
    private volatile boolean relayButtonVisible = false;
    private volatile long relayFailedMsgTime = 0;
    private volatile boolean lastManualRelayInProgress = false;

    public AttemptingJoinScreen(Screen parent, String roomCode, String password) {
        super(Component.translatable("voxlink.attempting_join"));
        this.parent = parent;
        this.roomCode = roomCode != null ? roomCode : "";
        this.password = password;
    }

    @Override
    protected void init() {
        super.init();

        RoomInfo room = VoxLinkMod.getRoomManager().getCurrentRoom();
        //debounce 用真实MC连接状态判定 不再用localBridgePort>0作"已连接"
        boolean bridgeReady = room != null && room.getLocalBridgePort() > 0 && ConnectionHelper.isMcTrulyConnected();
        if (bridgeReady) {
            active = false;
            //debounce 真连上了 把房间状态改成已连接
            room.setConnectionMode(Component.translatable("voxlink.connection.connected"));
            voxlinkFinal = true;
            voxlinkStatusText = Component.translatable("voxlink.dual.p2p_established").getString();
            voxlinkStatusColor = VoxLinkColors.SUCCESS;
        } else if (room != null && room.isConnectionFailed()) {
            active = false;
            voxlinkFinal = true;
            voxlinkStatusText = Component.translatable("voxlink.connection.all_failed").getString();
            voxlinkStatusColor = VoxLinkColors.ERROR;
        } else if (room != null && room.getLocalBridgePort() > 0) {
            //debounce 桥已建 但MC还在握手
            voxlinkStatusText = Component.translatable("voxlink.connection.bridge_setup").getString();
            voxlinkStatusColor = VoxLinkColors.WARNING;
        }

        int centerX = this.width / 2;
        int btnY = this.height / 2 + BTN_Y_OFFSET;

        if (!bridgeReady) {
            if (!joinApiDone || active) {
                this.addRenderableWidget(Button.builder(
                        Component.translatable("voxlink.cancel"),
                        button -> cancelJoin()
                ).bounds(centerX - HALF_BTN_W, btnY, BTN_W, BTN_H).build());
                //debounce 手动relay按钮: 打洞失败N轮后显示 玩家点击触发中继
                if (active && VoxLinkMod.getRoomManager().getConnectionManager().canShowRelayButton()) {
                    relayButtonVisible = true;
                    this.addRenderableWidget(Button.builder(
                            Component.translatable("voxlink.relay.use_player_relay"),
                            button -> onRelayButtonClicked()
                    ).bounds(centerX - HALF_BTN_W, btnY + BTN_H + RELAY_BTN_GAP, BTN_W, BTN_H).build());
                } else {
                    relayButtonVisible = false;
                }
            } else {
                this.addRenderableWidget(Button.builder(
                        Component.translatable("voxlink.back"),
                        button -> goBack()
                ).bounds(centerX - HALF_BTN_W, btnY, BTN_W, BTN_H).build());
            }
        }

        if (!joinApiDone) {
            joinApiDone = true;
            startJoin();
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return !active;
    }

    @Override
    public void onClose() {
        if (active) {
            cancelJoin();
        } else {
            goBack();
        }
    }

    private void goBack() {
        if (VoxLinkMod.getRoomManager().getCurrentRoom() != null) {
            VoxLinkMod.getRoomManager().leaveRoom();
        }
        Minecraft.getInstance().setScreen(parent);
    }

    private void cancelJoin() {
        active = false;
        VoxLinkMod.getRoomManager().leaveRoom();
        Minecraft.getInstance().setScreen(parent);
    }

    private void onRelayButtonClicked() {
        relayButtonVisible = false;
        lastManualRelayInProgress = true;
        clearOurWidgets();
        init();
        VoxLinkMod.getRoomManager().getConnectionManager().triggerManualRelay();
    }

    private void startJoin() {
        active = true;

        Minecraft mc = Minecraft.getInstance();
        String playerName = mc.getUser().getName();
        //双P2P: 房间码路由 + 并行竞速
        java.util.concurrent.CompletableFuture<Void> joinFuture = VoxLinkMod.getRoomManager().getConnectionManager().startDualP2P(roomCode, playerName, password, (channel, statusKey) -> {
            mc.execute(() -> {
                if (mc.screen != AttemptingJoinScreen.this) return;
                int color = colorForStatus(statusKey);
                String text = Component.translatable(statusKey).getString();
                if ("voxlink".equals(channel)) {
                    voxlinkStatusText = text;
                    voxlinkStatusColor = color;
                    //debounce 终态后monitor不再覆盖VoxLink行
                    if (statusKey.endsWith(".p2p_established") || statusKey.endsWith(".channel_failed") || statusKey.endsWith(".status_cancelled")) {
                        voxlinkFinal = true;
                    }
                } else if ("terracotta".equals(channel)) {
                    terracottaStatusText = text;
                    terracottaStatusColor = color;
                    if (statusKey.endsWith(".p2p_established") || statusKey.endsWith(".channel_failed") || statusKey.endsWith(".status_cancelled")) {
                        terracottaFinal = true;
                    }
                }
            });
        });
        //join完成前 monitor跳过房间丢失判定
        joinFuture.whenComplete((v, e) -> joinCompleted = true);
        joinFuture
                .thenAccept(v -> mc.execute(() -> {
                    if (mc.screen != AttemptingJoinScreen.this) return;
                }))
                .exceptionally(e -> {
                    mc.execute(() -> {
                        if (mc.screen != AttemptingJoinScreen.this) return;
                        Throwable cause = e;
                        while (cause.getCause() != null) cause = cause.getCause();
                        onFailed(extractErrorMessage(cause.getMessage()));
                    });
                    return null;
                });

        startConnectionMonitor();
    }

    //状态颜色映射
    private int colorForStatus(String statusKey) {
        if (statusKey == null) return VoxLinkColors.MUTED;
        if (statusKey.endsWith(".connected") || statusKey.endsWith(".p2p_established")) return VoxLinkColors.SUCCESS;
        if (statusKey.endsWith(".all_failed") || statusKey.endsWith(".channel_failed")) return VoxLinkColors.ERROR;
        if (statusKey.endsWith(".status_cancelled")) return VoxLinkColors.MUTED;
        return VoxLinkColors.WARNING;
    }

    private void onFailed(String msg) {
        //debounce 失败信息显示到VoxLink行(上方statusMessage已删除)
        voxlinkFinal = true;
        voxlinkStatusText = msg;
        voxlinkStatusColor = VoxLinkColors.ERROR;
        active = false;
        stopConnectionMonitor();
        RoomInfo room = VoxLinkMod.getRoomManager().getCurrentRoom();
        if (room == null || room.getLocalBridgePort() <= 0) {
            VoxLinkMod.getRoomManager().leaveRoom();
        }
        clearOurWidgets();
        init();
    }

    private void stopConnectionMonitor() {
        if (connectionFuture != null) {
            connectionFuture.cancel(false);
            connectionFuture = null;
        }
        if (connectionScheduler != null && !connectionScheduler.isShutdown()) {
            connectionScheduler.shutdownNow();
            connectionScheduler = null;
        }
    }

    private String extractErrorMessage(String msg) {
        if (msg == null) return Component.translatable("voxlink.error.unknown").getString();
        if (msg.contains("ROOM_NOT_FOUND")) return Component.translatable("voxlink.join_room.error.not_found").getString();
        if (msg.contains("ROOM_FULL")) return Component.translatable("voxlink.error.room_full").getString();
        if (msg.contains("WRONG_PASSWORD")) return Component.translatable("voxlink.error.wrong_password").getString();
        if (msg.contains("RATE_LIMITED")) return Component.translatable("voxlink.join_room.error.rate_limited").getString();
        if (msg.contains("NETWORK_ERROR")) return Component.translatable("voxlink.join_room.error.network").getString();
        if (msg.contains("ALREADY_IN_ROOM")) return Component.translatable("voxlink.error.already_in_room").getString();
        if (msg.contains("INVALID_ROOM_CODE")) return Component.translatable("voxlink.error.invalid_room_code").getString();
        if (msg.contains("QUEUED")) return Component.translatable("voxlink.join_room.error.server_busy").getString();
        return msg;
    }

    private void startConnectionMonitor() {
        stopConnectionMonitor();
        monitorTicks = 0;
        final java.util.concurrent.atomic.AtomicBoolean monitorActive = new java.util.concurrent.atomic.AtomicBoolean(true);
        connectionScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "VoxLink-JoinConnMonitor");
            t.setDaemon(true);
            return t;
        });
        Runnable monitor = new Runnable() {
            @Override
            public void run() {
                if (!monitorActive.get()) return;
                try {
                    Minecraft mc = Minecraft.getInstance();
                    monitorTicks++;
                    RoomInfo roomInfo = VoxLinkMod.getRoomManager().getCurrentRoom();
                    if (roomInfo == null) {
                        if (!joinCompleted) {
                            if (connectionFuture != null) connectionFuture.cancel(false);
                            if (monitorActive.get() && connectionScheduler != null && !connectionScheduler.isShutdown()) {
                                connectionFuture = connectionScheduler.schedule(() -> mc.execute(this), TICK_INTERVAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
                            }
                            return;
                        }
                        monitorActive.set(false);
                        mc.execute(() -> {
                            if (mc.screen != AttemptingJoinScreen.this) return;
                            onFailed(Component.translatable("voxlink.room_lost").getString());
                        });
                        if (connectionScheduler != null && !connectionScheduler.isShutdown()) connectionScheduler.shutdownNow();
                        return;
                    }
                    if (roomInfo.getLocalBridgePort() > 0) {
                        if (bridgeEstablishedAtTick < 0) {
                            bridgeEstablishedAtTick = monitorTicks;
                        }
                        //debounce 桥已建 等待MC真正握手成功
                        if (ConnectionHelper.isMcTrulyConnected()) {
                            monitorActive.set(false);
                            //debounce 清理和状态更新不需要在屏幕线程
                            ConnectionHelper.clearConnectInitiated();
                            roomInfo.setConnectionMode(Component.translatable("voxlink.connection.connected"));
                            mc.execute(() -> {
                                if (mc.screen != AttemptingJoinScreen.this) return;
                                voxlinkFinal = true;
                                voxlinkStatusText = Component.translatable("voxlink.dual.p2p_established").getString();
                                voxlinkStatusColor = VoxLinkColors.SUCCESS;
                                active = false;
                            });
                            if (connectionScheduler != null && !connectionScheduler.isShutdown()) connectionScheduler.shutdownNow();
                            return;
                        }
                        //debounce MC连接被拒
                        if (ConnectionHelper.isConnectionRejected()) {
                            monitorActive.set(false);
                            ConnectionHelper.clearConnectInitiated();
                            mc.execute(() -> {
                                if (mc.screen != AttemptingJoinScreen.this) return;
                                onFailed(Component.translatable("voxlink.connection.all_failed").getString());
                            });
                            if (connectionScheduler != null && !connectionScheduler.isShutdown()) connectionScheduler.shutdownNow();
                            return;
                        }
                        //debounce 桥已建 但还在握手 显示隧道建立中到VoxLink行
                        mc.execute(() -> {
                            if (mc.screen != AttemptingJoinScreen.this) return;
                            if (!voxlinkFinal) {
                                voxlinkStatusText = Component.translatable("voxlink.connection.bridge_setup").getString();
                                voxlinkStatusColor = VoxLinkColors.WARNING;
                            }
                        });
                    }
                    boolean bridgeBuiltNow = roomInfo.getLocalBridgePort() > 0;
                    boolean handshakeTimeout = bridgeBuiltNow && bridgeEstablishedAtTick >= 0
                            ? (monitorTicks - bridgeEstablishedAtTick >= MAX_MONITOR_TICKS)
                            : (monitorTicks >= MAX_MONITOR_TICKS);
                    //debounce 无限重试规范: 底层持续重试中不因UI硬超时判失败 仅底层终态(玩家取消/对端cancel)才失败
                    boolean persistentRetrying = VoxLinkMod.getRoomManager().getConnectionManager().isPersistentRetrying();
                    if (roomInfo.isConnectionFailed() || (handshakeTimeout && !persistentRetrying)) {
                        monitorActive.set(false);
                        if (handshakeTimeout) {
                            //握手超时 清理MC连接发起状态
                            ConnectionHelper.clearConnectInitiated();
                        }
                        mc.execute(() -> {
                            if (mc.screen != AttemptingJoinScreen.this) return;
                            onFailed(Component.translatable("voxlink.connection.all_failed").getString());
                        });
                        if (connectionScheduler != null && !connectionScheduler.isShutdown()) connectionScheduler.shutdownNow();
                        return;
                    }
                    //debounce VoxLink行未到终态时 用connectionMode(探测/打洞/重试)动态更新
                    Component connMode = roomInfo.getConnectionMode();
                    if (connMode != null && !connMode.getString().isEmpty()) {
                        mc.execute(() -> {
                            if (mc.screen != AttemptingJoinScreen.this) return;
                            if (!voxlinkFinal) {
                                voxlinkStatusText = connMode.getString();
                                voxlinkStatusColor = VoxLinkColors.WARNING;
                            }
                        });
                    }
                    //debounce 检测relay按钮显示状态变化 轮次达到时动态显示按钮
                    boolean shouldShowRelay = active && VoxLinkMod.getRoomManager()
                            .getConnectionManager().canShowRelayButton();
                    if (shouldShowRelay != relayButtonVisible) {
                        mc.execute(() -> {
                            if (mc.screen != AttemptingJoinScreen.this) return;
                            clearOurWidgets();
                            init();
                        });
                    }
                    //debounce 检测relay失败: manualRelayInProgress从true变false时显示3秒提示
                    boolean currentRelayInProgress = VoxLinkMod.getRoomManager()
                            .getConnectionManager().isManualRelayInProgress();
                    if (lastManualRelayInProgress && !currentRelayInProgress) {
                        relayFailedMsgTime = System.currentTimeMillis();
                    }
                    lastManualRelayInProgress = currentRelayInProgress;
                    if (connectionFuture != null) {
                        connectionFuture.cancel(false);
                    }
                    if (monitorActive.get() && connectionScheduler != null && !connectionScheduler.isShutdown()) {
                        connectionFuture = connectionScheduler.schedule(() -> mc.execute(this), TICK_INTERVAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
                    }
                } catch (Exception e) {
                    Minecraft mc = Minecraft.getInstance();
                    mc.execute(() -> {
                        if (mc.screen != AttemptingJoinScreen.this) return;
                        onFailed(Component.translatable("voxlink.connection.monitor_error").getString());
                    });
                }
            }
        };
        connectionFuture = connectionScheduler.schedule(() -> Minecraft.getInstance().execute(monitor), TICK_INTERVAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int centerX = this.width / 2;

        drawCenteredString(graphics, this.title.getString(), centerX, TITLE_Y, VoxLinkColors.WHITE);

        //debounce 不显示明文房间号 只画一个标签
        drawCenteredString(graphics, ChatFormatting.YELLOW.toString() + ChatFormatting.BOLD.toString()
                + Component.translatable("voxlink.chat.room_code_label").getString().trim(), centerX, this.height / 2 - ROOM_CODE_Y_OFFSET, VoxLinkColors.WARNING);

        //debounce 中继失败3秒提示 显示在房间号上方
        if (relayFailedMsgTime > 0) {
            long elapsed = System.currentTimeMillis() - relayFailedMsgTime;
            if (elapsed < RELAY_FAILED_MSG_MS) {
                drawCenteredString(graphics,
                        Component.translatable("voxlink.relay.failed_retry_punch").getString(),
                        centerX, this.height / 2 - ROOM_CODE_Y_OFFSET - 12, VoxLinkColors.WARNING);
            } else {
                relayFailedMsgTime = 0;
            }
        }

        //debounce 上方statusMessage已删除 只保留双P2P两行(已上移到中间)
        //双P2P状态行
        if (!voxlinkStatusText.isEmpty()) {
            String label = Component.translatable("voxlink.dual.voxlink_label").getString();
            String clipped = voxlinkStatusText;
            int maxWidth = this.width - STATUS_MARGIN;
            if (fontWidth(label + ": " + clipped) > maxWidth) {
                while (fontWidth(label + ": " + clipped + "...") > maxWidth && clipped.length() > 0) {
                    clipped = clipped.substring(0, clipped.length() - 1);
                }
                clipped = clipped + "...";
            }
            drawCenteredString(graphics, label + ": " + clipped, centerX, this.height / 2 + VOXLINK_STATUS_Y_OFFSET, voxlinkStatusColor);
        }
        if (!terracottaStatusText.isEmpty()) {
            String label = Component.translatable("voxlink.dual.terracotta_label").getString();
            String clipped = terracottaStatusText;
            int maxWidth = this.width - STATUS_MARGIN;
            if (fontWidth(label + ": " + clipped) > maxWidth) {
                while (fontWidth(label + ": " + clipped + "...") > maxWidth && clipped.length() > 0) {
                    clipped = clipped.substring(0, clipped.length() - 1);
                }
                clipped = clipped + "...";
            }
            drawCenteredString(graphics, label + ": " + clipped, centerX, this.height / 2 + TERRACOTTA_STATUS_Y_OFFSET, terracottaStatusColor);
        }
    }

    @Override
    public void removed() {
        super.removed();
        stopConnectionMonitor();
        RoomInfo room = VoxLinkMod.getRoomManager().getCurrentRoom();
        //debounce 桥已建就不取消 MC正在接管连接
        boolean bridgeBuilt = room != null && room.getLocalBridgePort() > 0;
        if (active && !bridgeBuilt) {
            cancelJoin();
        } else {
            active = false;
        }
    }
}
