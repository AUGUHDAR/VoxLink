package icu.wuhui.voxlink.mixin;

import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.network.ConnectionHelper;
import icu.wuhui.voxlink.room.RoomInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.DirectJoinServerScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

@Mixin(DirectJoinServerScreen.class)
public abstract class DirectConnectMixin extends Screen {

    private static final Pattern ROOM_CODE_PATTERN = Pattern.compile("^[A-HJ-NP-Z2-9]{6}$");

    @Shadow
    private EditBox ipEdit;

    protected DirectConnectMixin(Component title) {
        super(title);
    }

    @Inject(method = "onSelect", at = @At("HEAD"), cancellable = true, require = 0)
    private void onDirectConnect(CallbackInfo ci) {
        String address = ipEdit.getValue().trim().toUpperCase();
        if (address.contains(".") || address.contains(":")) return;
        if (!ROOM_CODE_PATTERN.matcher(address).matches()) return;

        ci.cancel();

        Minecraft mc = Minecraft.getInstance();
        String code = address;

        var rm = VoxLinkMod.getRoomManager();
        if (rm == null) {
            if (mc.player != null) {
                mc.player.sendSystemMessage(Component.translatable("voxlink.error.not_available"));
            }
            return;
        }
        if (rm.isInRoom()) {
            if (mc.player != null) {
                mc.player.sendSystemMessage(Component.translatable("voxlink.chat.already_in_room_leave_first"));
            }
            return;
        }

        rm.joinRoom(code, null)
                .thenAccept(roomInfo -> mc.execute(() -> {
                    if (mc.player != null) {
                        mc.player.sendSystemMessage(Component.translatable("voxlink.chat.joined_waiting_host"));
                    }
                    startDirectConnectMonitor(mc);
                }))
                .exceptionally(e -> {
                    mc.execute(() -> {
                        Throwable cause = e;
                        while (cause.getCause() != null) cause = cause.getCause();
                        String msg = cause.getMessage();
                        if (msg == null) msg = Component.translatable("voxlink.error.unknown").getString();
                        if (msg.contains("ROOM_NOT_FOUND")) msg = Component.translatable("voxlink.error.room_not_found").getString();
                        else if (msg.contains("ROOM_FULL")) msg = Component.translatable("voxlink.error.room_full").getString();
                        else if (msg.contains("WRONG_PASSWORD")) msg = Component.translatable("voxlink.error.wrong_password").getString();
                        if (mc.player != null) {
                            mc.player.sendSystemMessage(Component.translatable("voxlink.chat.error", msg));
                        }
                    });
                    return null;
                });
    }

    @Unique
    private volatile ScheduledExecutorService monitorScheduler;
    @Unique
    private volatile ScheduledFuture<?> monitorFuture;
    @Unique
    private volatile int monitorTicks = 0;
    @Unique
    private static final int MAX_MONITOR_TICKS = 90;
    @Unique
    private static final int MONITOR_POLL_SEC = 1;
    @Unique
    private final AtomicBoolean monitorDone = new AtomicBoolean(false);

    private synchronized void startDirectConnectMonitor(Minecraft mc) {
        if (monitorScheduler == null || monitorScheduler.isShutdown()) {
            monitorScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "VoxLink-DirectConnect-Monitor");
                t.setDaemon(true);
                t.setUncaughtExceptionHandler((thread, ex) -> VoxLinkMod.LOGGER.error("监控线程挂了", ex));
                return t;
            });
        }
        monitorTicks = 0;
        monitorDone.set(false);
        Runnable monitor = new Runnable() {
            @Override
            public void run() {
                if (monitorDone.get()) return;
                monitorTicks++;
                var roomInfo = VoxLinkMod.getRoomManager().getCurrentRoom();
                if (roomInfo == null) {
                    if (monitorTicks < MAX_MONITOR_TICKS && !monitorDone.get()) {
                        monitorFuture = monitorScheduler.schedule(() -> mc.execute(this), MONITOR_POLL_SEC, TimeUnit.SECONDS);
                    }
                    return;
                }
                if (roomInfo.getLocalBridgePort() > 0) {
                    if (monitorDone.compareAndSet(false, true)) {
                        mc.execute(() -> {
                            if (mc.player != null) {
                                mc.player.sendSystemMessage(Component.translatable("voxlink.chat.connected_entering_game"));
                            }
                        });
                    }
                    return;
                }
                if (roomInfo.isConnectionFailed() || monitorTicks >= MAX_MONITOR_TICKS) {
                    if (monitorDone.compareAndSet(false, true)) {
                        mc.execute(() -> {
                            if (mc.player != null) {
                                Component connMode = roomInfo.getConnectionMode();
                                String reason = roomInfo.isConnectionFailed() && connMode != null
                                        ? connMode.getString()
                                        : Component.translatable("voxlink.connection.timeout_retry").getString();
                                if (reason == null || reason.isEmpty()) {
                                    reason = Component.translatable("voxlink.connection.all_failed").getString();
                                }
                                mc.player.sendSystemMessage(Component.translatable("voxlink.chat.connection_failed_detail", reason));
                            }
                            VoxLinkMod.getRoomManager().leaveRoom();
                        });
                    }
                    return;
                }
                if (!monitorDone.get() && monitorScheduler != null && !monitorScheduler.isShutdown()) {
                    monitorFuture = monitorScheduler.schedule(() -> mc.execute(this), MONITOR_POLL_SEC, TimeUnit.SECONDS);
                }
            }
        };
        monitorFuture = monitorScheduler.schedule(() -> mc.execute(monitor), MONITOR_POLL_SEC, TimeUnit.SECONDS);
    }

    @Inject(method = "removed", at = @At("HEAD"))
    private void onRemoved(CallbackInfo ci) {
        monitorDone.set(true);
        if (monitorFuture != null) {
            monitorFuture.cancel(false);
            monitorFuture = null;
        }
        if (monitorScheduler != null) {
            monitorScheduler.shutdownNow();
            monitorScheduler = null;
        }
    }
}
