package icu.wuhui.voxlink.ui;

import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.room.RoomInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class AttemptingJoinScreen extends Screen {
    private final Screen parent;
    private final String roomCode;
    private final String password;
    private String statusMessage = "";
    private int statusColor = 0xFFFFFFFF;
    private volatile boolean active = false;
    private boolean joinApiDone = false;
    private boolean wasRemoved = false;
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
        this.clearWidgets();

        if (wasRemoved) {
            wasRemoved = false;
            if (VoxLinkMod.getRoomManager().getCurrentRoom() != null
                    && VoxLinkMod.getRoomManager().getCurrentRoom().isConnectionFailed()) {
                VoxLinkMod.getRoomManager().leaveRoom();
            }
        }

        RoomInfo room = VoxLinkMod.getRoomManager().getCurrentRoom();
        if (room != null && room.getLocalBridgePort() > 0) {
            active = false;
            statusMessage = "\u00a7a" + Component.translatable("voxlink.browser.connected_entering").getString();
            statusColor = 0xFF55FF55;
        } else if (room != null && room.isConnectionFailed()) {
            active = false;
            statusMessage = "\u00a7c" + Component.translatable("voxlink.connection.all_failed").getString();
            statusColor = 0xFFFF5555;
        }

        int centerX = this.width / 2;
        int btnY = this.height / 2 + 30;

        if (!joinApiDone || active) {
            this.addRenderableWidget(Button.builder(
                    Component.translatable("voxlink.cancel"),
                    button -> cancelJoin()
            ).bounds(centerX - 100, btnY, 200, 20).build());
        } else {
            this.addRenderableWidget(Button.builder(
                    Component.translatable("voxlink.back"),
                    button -> goBack()
            ).bounds(centerX - 100, btnY, 200, 20).build());
        }

        if (!joinApiDone) {
            joinApiDone = true;
            startJoin();
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
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
        // 直接回主菜单
        Minecraft.getInstance().setScreen(new net.minecraft.client.gui.screens.TitleScreen());
    }

    private void cancelJoin() {
        active = false;
        VoxLinkMod.getRoomManager().leaveRoom();
        Minecraft.getInstance().setScreen(new net.minecraft.client.gui.screens.TitleScreen());
    }

    private void startJoin() {
        active = true;
        statusMessage = Component.translatable("voxlink.attempting_join.joining").getString();
        statusColor = 0xFFFFFF55;

        Minecraft mc = Minecraft.getInstance();
        VoxLinkMod.getRoomManager().joinRoom(roomCode, password)
                .thenAccept(roomInfo -> mc.execute(() -> {
                    if (mc.screen != AttemptingJoinScreen.this) return;
                    if (roomInfo == null) {
                        onFailed(Component.translatable("voxlink.attempting_join.failed").getString());
                        return;
                    }
                    statusMessage = Component.translatable("voxlink.attempting_join.waiting_connection").getString();
                    statusColor = 0xFFAAAAAA;
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

    private void onFailed(String msg) {
        statusMessage = "\u00a7c" + msg;
        statusColor = 0xFFFF5555;
        active = false;
        stopConnectionMonitor();
        VoxLinkMod.getRoomManager().leaveRoom();
        clearWidgets();
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
                        monitorActive.set(false);
                        mc.execute(() -> {
                            if (mc.screen != AttemptingJoinScreen.this) return;
                            statusMessage = "\u00a7a" + Component.translatable("voxlink.browser.connected_entering").getString();
                            statusColor = 0xFF55FF55;
                            active = false;
                        });
                        if (connectionScheduler != null && !connectionScheduler.isShutdown()) connectionScheduler.shutdownNow();
                        return;
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
                            statusMessage = "\u00a77" + connMode.getString();
                            statusColor = 0xFFAAAAAA;
                        });
                    }
                    if (connectionFuture != null) {
                        connectionFuture.cancel(false);
                    }
                    if (monitorActive.get() && connectionScheduler != null && !connectionScheduler.isShutdown()) {
                        connectionFuture = connectionScheduler.schedule(() -> mc.execute(this), 500, java.util.concurrent.TimeUnit.MILLISECONDS);
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
        connectionFuture = connectionScheduler.schedule(() -> Minecraft.getInstance().execute(monitor), 500, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        int centerX = this.width / 2;

        graphics.drawCenteredString(this.font, this.title, centerX, 15, 0xFFFFFFFF);

        graphics.drawCenteredString(this.font, "\u00a7e\u00a7l" + roomCode, centerX, this.height / 2 - 30, 0xFFFFFF55);

        if (!statusMessage.isEmpty()) {
            String clipped = statusMessage;
            int maxWidth = this.width - 20;
            if (this.font.width(statusMessage) > maxWidth) {
                while (this.font.width(clipped + "...") > maxWidth && clipped.length() > 0) {
                    clipped = clipped.substring(0, clipped.length() - 1);
                }
                clipped = clipped + "...";
            }
            graphics.drawCenteredString(this.font, clipped, centerX, this.height / 2, statusColor);
        }
    }

    @Override
    public void removed() {
        super.removed();
        wasRemoved = true;
        stopConnectionMonitor();
        // 桥已建好别断
        RoomInfo room = VoxLinkMod.getRoomManager().getCurrentRoom();
        boolean bridgeReady = room != null && room.getLocalBridgePort() > 0;
        if (active && !bridgeReady) {
            cancelJoin();
        } else {
            active = false;
        }
    }
}
