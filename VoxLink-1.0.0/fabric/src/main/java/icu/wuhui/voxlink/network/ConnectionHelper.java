package icu.wuhui.voxlink.network;

import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.compat.ViaCompat;
import icu.wuhui.voxlink.room.RoomInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class ConnectionHelper {
    private ConnectionHelper() {}

    private static final java.util.concurrent.atomic.AtomicBoolean connecting = new java.util.concurrent.atomic.AtomicBoolean(false);

    private static final ScheduledExecutorService RESET_SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "VoxLink-ConnReset");
        t.setDaemon(true);
        return t;
    });

    public static void resetConnecting() {
        connecting.set(false);
    }

    public static boolean isConnecting() {
        return connecting.get();
    }

    public static void connectToServer(int localPort, RoomInfo roomInfo) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (localPort > 0) {
                if (!connecting.compareAndSet(false, true)) {
                    VoxLinkMod.LOGGER.warn("[ConnectionHelper] 已经在连接了，忽略重复调用");
                    return;
                }
                ScheduledFuture<?> resetTask = null;
                try {
                    roomInfo.setLocalBridgePort(localPort);
                    String addr = ViaCompat.buildViaAddress("127.0.0.1", localPort, roomInfo.getServerProtocolVersion());
                    ServerData serverData = new ServerData(roomInfo.getName(), addr, ServerData.Type.OTHER);
                    ConnectScreen.startConnecting(mc.screen, mc, ServerAddress.parseString(addr), serverData, false, null);

                    // 防卡住
                    resetTask = RESET_SCHEDULER.schedule(() -> {
                        if (connecting.get()) {
                            VoxLinkMod.LOGGER.info("[ConnectionHelper] 30秒超时，自动重置connecting标志");
                            connecting.set(false);
                        }
                    }, 30, TimeUnit.SECONDS);
                } catch (Exception e) {
                    connecting.set(false);
                    VoxLinkMod.LOGGER.error("[ConnectionHelper] 连接失败: {}", e.getMessage());
                    String mode = roomInfo.getConnectionMode().getString();
                    sendError(mc, Component.translatable("voxlink.chat.connection_failed_detail", mode == null || mode.isEmpty() ? Component.translatable("voxlink.connection.cannot_establish").getString() : mode).getString());
                    VoxLinkMod.getRoomManager().leaveRoom();
                    if (resetTask != null) resetTask.cancel(false);
                }
            } else {
                connecting.set(false);
                String mode = roomInfo.getConnectionMode().getString();
                sendError(mc, Component.translatable("voxlink.chat.connection_failed_detail", mode == null || mode.isEmpty() ? Component.translatable("voxlink.connection.cannot_establish").getString() : mode).getString());
                VoxLinkMod.getRoomManager().leaveRoom();
            }
        });
    }

    private static void sendError(Minecraft mc, String msg) {
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal(msg), false);
        }
    }
}
