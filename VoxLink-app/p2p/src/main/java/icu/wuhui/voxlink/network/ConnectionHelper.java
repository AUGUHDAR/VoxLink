package icu.wuhui.voxlink.network;

import icu.wuhui.voxlink.app.AppContext;
import icu.wuhui.voxlink.room.RoomInfo;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

// APP端连接助手：纯标志管理 + 回调注入，替代MC的ConnectScreen
public final class ConnectionHelper {
    private ConnectionHelper() {}

    private static final java.util.concurrent.atomic.AtomicBoolean connecting = new java.util.concurrent.atomic.AtomicBoolean(false);

    private static volatile BiConsumer<Integer, RoomInfo> connectCallback;

    private static final ScheduledExecutorService RESET_SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "VoxLink-ConnReset");
        t.setDaemon(true);
        return t;
    });

    public static void setConnectCallback(BiConsumer<Integer, RoomInfo> callback) {
        connectCallback = callback;
    }

    public static void resetConnecting() {
        connecting.set(false);
    }

    public static boolean isConnecting() {
        return connecting.get();
    }

    public static void connectToServer(int localPort, RoomInfo roomInfo) {
        if (localPort > 0) {
            if (!connecting.compareAndSet(false, true)) {
                AppContext.LOGGER.warn("[ConnectionHelper] 已经在连接了，忽略重复调用");
                return;
            }
            ScheduledFuture<?> resetTask = null;
            try {
                roomInfo.setLocalBridgePort(localPort);
                if (connectCallback != null) {
                    connectCallback.accept(localPort, roomInfo);
                } else {
                    AppContext.LOGGER.warn("[ConnectionHelper] 没设connectCallback，APP层需注入");
                }
                resetTask = RESET_SCHEDULER.schedule(() -> {
                    if (connecting.get()) {
                        AppContext.LOGGER.info("[ConnectionHelper] 30秒超时，自动重置connecting标志");
                        connecting.set(false);
                    }
                }, 30, TimeUnit.SECONDS);
            } catch (Exception e) {
                connecting.set(false);
                AppContext.LOGGER.error("[ConnectionHelper] 连接失败: {}", e.getMessage());
            }
        } else {
            connecting.set(false);
            AppContext.LOGGER.error("[ConnectionHelper] localPort无效: {}", localPort);
        }
    }
}
