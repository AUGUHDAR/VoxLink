package icu.wuhui.voxlink.ui;

import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.network.ConnectionHelper;
import icu.wuhui.voxlink.room.RoomInfo;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
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
    private static final int VOXLINK_STATUS_Y_OFFSET = 12;
    private static final int TERRACOTTA_STATUS_Y_OFFSET = 24;
    private static final int TICK_INTERVAL_MS = 500;
    private static final int COLOR_WHITE = 0xFFFFFFFF;
    private static final int COLOR_MUTED = 0xFFAAAAAA;
    private static final int COLOR_SUCCESS = 0xFF55FF55;
    private static final int COLOR_ERROR = 0xFFFF5555;
    private static final int COLOR_WARNING = 0xFFFFFF55;

    private final Screen parent;
    private final String roomCode;
    private final String password;
    private String statusMessage = "";
    private int statusColor = COLOR_WHITE;
    //双P2P状态
    private String voxlinkStatusKey = "";
    private int voxlinkStatusColor = COLOR_MUTED;
    private String terracottaStatusKey = "";
    private int terracottaStatusColor = COLOR_MUTED;
    private volatile boolean active = false;
    private boolean joinApiDone = false;
    private volatile java.util.concurrent.ScheduledExecutorService connectionScheduler;
    private java.util.concurrent.ScheduledFuture<?> connectionFuture;
    private int monitorTicks = 0;
    private static final int MAX_MONITOR_TICKS = 360;

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
            statusMessage = ChatFormatting.GREEN.toString() + Component.translatable("voxlink.browser.connected_entering").getString();
            statusColor = COLOR_SUCCESS;
            //debounce 真连上了 把房间状态改成已连接
            room.setConnectionMode(Component.translatable("voxlink.connection.connected"));
        } else if (room != null && room.isConnectionFailed()) {
            active = false;
            statusMessage = ChatFormatting.RED.toString() + Component.translatable("voxlink.connection.all_failed").getString();
            statusColor = COLOR_ERROR;
        } else if (room != null && room.getLocalBridgePort() > 0) {
            //debounce 桥已建 但MC还在握手
            statusMessage = ChatFormatting.YELLOW.toString() + Component.translatable("voxlink.connection.connecting").getString();
            statusColor = COLOR_WARNING;
        }

        int centerX = this.width / 2;
        int btnY = this.height / 2 + BTN_Y_OFFSET;

        if (!bridgeReady) {
            if (!joinApiDone || active) {
                this.addRenderableWidget(Button.builder(
                        Component.translatable("voxlink.cancel"),
                        button -> cancelJoin()
                ).bounds(centerX - HALF_BTN_W, btnY, BTN_W, BTN_H).build());
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

    private void startJoin() {
        active = true;
        statusMessage = Component.translatable("voxlink.attempting_join.joining").getString();
        statusColor = COLOR_WARNING;

        Minecraft mc = Minecraft.getInstance();
        String playerName = mc.getUser().getName();
        //双P2P: 房间码路由 + 并行竞速
        VoxLinkMod.getRoomManager().getConnectionManager().startDualP2P(roomCode, playerName, password, (channel, statusKey) -> {
            mc.execute(() -> {
                if (mc.screen != AttemptingJoinScreen.this) return;
                int color = colorForStatus(statusKey);
                if ("voxlink".equals(channel)) {
                    voxlinkStatusKey = statusKey;
                    voxlinkStatusColor = color;
                } else if ("terracotta".equals(channel)) {
                    terracottaStatusKey = statusKey;
                    terracottaStatusColor = color;
                }
            });
        })
                .thenAccept(v -> mc.execute(() -> {
                    if (mc.screen != AttemptingJoinScreen.this) return;
                    statusMessage = Component.translatable("voxlink.attempting_join.waiting_connection").getString();
                    statusColor = COLOR_MUTED;
                    startConnectionMonitor();
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
    }

    //状态颜色映射
    private int colorForStatus(String statusKey) {
        if (statusKey == null) return COLOR_MUTED;
        if (statusKey.endsWith(".connected") || statusKey.endsWith(".p2p_established")) return COLOR_SUCCESS;
        if (statusKey.endsWith(".all_failed") || statusKey.endsWith(".channel_failed")) return COLOR_ERROR;
        if (statusKey.endsWith(".status_cancelled")) return COLOR_MUTED;
        return COLOR_WARNING;
    }

    private void onFailed(String msg) {
        statusMessage = ChatFormatting.RED.toString() + msg;
        statusColor = COLOR_ERROR;
        active = false;
        stopConnectionMonitor();
        VoxLinkMod.getRoomManager().leaveRoom();
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
                        monitorActive.set(false);
                        mc.execute(() -> {
                            if (mc.screen != AttemptingJoinScreen.this) return;
                            onFailed(Component.translatable("voxlink.room_lost").getString());
                        });
                        if (connectionScheduler != null && !connectionScheduler.isShutdown()) connectionScheduler.shutdownNow();
                        return;
                    }
                    if (roomInfo.getLocalBridgePort() > 0) {
                        //debounce 桥已建 等待MC真正握手成功
                        if (ConnectionHelper.isMcTrulyConnected()) {
                            monitorActive.set(false);
                            //debounce 清理和状态更新不需要在屏幕线程
                            ConnectionHelper.clearConnectInitiated();
                            roomInfo.setConnectionMode(Component.translatable("voxlink.connection.connected"));
                            mc.execute(() -> {
                                if (mc.screen != AttemptingJoinScreen.this) return;
                                statusMessage = ChatFormatting.GREEN.toString() + Component.translatable("voxlink.browser.connected_entering").getString();
                                statusColor = COLOR_SUCCESS;
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
                        //debounce 桥已建 但还在握手 显示连接中
                        mc.execute(() -> {
                            if (mc.screen != AttemptingJoinScreen.this) return;
                            statusMessage = ChatFormatting.YELLOW.toString() + Component.translatable("voxlink.connection.connecting").getString();
                            statusColor = COLOR_WARNING;
                        });
                    }
                    if (roomInfo.isConnectionFailed() || monitorTicks >= MAX_MONITOR_TICKS) {
                        monitorActive.set(false);
                        mc.execute(() -> {
                            if (mc.screen != AttemptingJoinScreen.this) return;
                            onFailed(Component.translatable("voxlink.connection.all_failed").getString());
                        });
                        if (connectionScheduler != null && !connectionScheduler.isShutdown()) connectionScheduler.shutdownNow();
                        return;
                    }
                    Component connMode = roomInfo.getConnectionMode();
                    if (connMode != null && !connMode.getString().isEmpty()) {
                        mc.execute(() -> {
                            if (mc.screen != AttemptingJoinScreen.this) return;
                            statusMessage = ChatFormatting.GRAY.toString() + connMode.getString();
                            statusColor = COLOR_MUTED;
                        });
                    }
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
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        int centerX = this.width / 2;

        drawCenteredString(graphics, this.title.getString(), centerX, TITLE_Y, COLOR_WHITE);

        //debounce 不显示明文房间号 只画一个标签
        drawCenteredString(graphics, ChatFormatting.YELLOW.toString() + ChatFormatting.BOLD.toString()
                + Component.translatable("voxlink.chat.room_code_label").getString().trim(), centerX, this.height / 2 - ROOM_CODE_Y_OFFSET, COLOR_WARNING);

        if (!statusMessage.isEmpty()) {
            String clipped = statusMessage;
            int maxWidth = this.width - STATUS_MARGIN;
            if (fontWidth(statusMessage) > maxWidth) {
                while (fontWidth(clipped + "...") > maxWidth && clipped.length() > 0) {
                    clipped = clipped.substring(0, clipped.length() - 1);
                }
                clipped = clipped + "...";
            }
            drawCenteredString(graphics, clipped, centerX, this.height / 2, statusColor);
        }

        //双P2P状态行
        if (!voxlinkStatusKey.isEmpty()) {
            String label = Component.translatable("voxlink.dual.voxlink_label").getString();
            String status = Component.translatable(voxlinkStatusKey).getString();
            drawCenteredString(graphics, label + ": " + status, centerX, this.height / 2 + VOXLINK_STATUS_Y_OFFSET, voxlinkStatusColor);
        }
        if (!terracottaStatusKey.isEmpty()) {
            String label = Component.translatable("voxlink.dual.terracotta_label").getString();
            String status = Component.translatable(terracottaStatusKey).getString();
            drawCenteredString(graphics, label + ": " + status, centerX, this.height / 2 + TERRACOTTA_STATUS_Y_OFFSET, terracottaStatusColor);
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
