package icu.wuhui.voxlink.network;

import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.compat.ViaCompat;
import icu.wuhui.voxlink.room.RoomInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class ConnectionHelper {
    private ConnectionHelper() {}

    private static final int CONNECT_RESET_SEC = 30;
    private static final java.util.concurrent.atomic.AtomicBoolean connecting = new java.util.concurrent.atomic.AtomicBoolean(false);

    //debounce 记录发起startConnecting前的player引用 用于判断真正切换到新连接
    //改强引用 WeakReference会被GC回收导致误判 cur==prev 短路返回true
    private static volatile LocalPlayer prevPlayerStrong = null;
    private static volatile long connectInitiatedAt = 0;
    //debounce 30s超时任务改字段 clearConnectInitiated能取消 避免旧任务污染新连接
    private static volatile ScheduledFuture<?> resetTask = null;

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

    //debounce 真正连上对方MC: cpl非null + socket活着 + player非null + player引用已变化
    public static boolean isMcTrulyConnected() {
        if (connectInitiatedAt == 0) return false;
        //至少等200ms 让startConnecting生效
        if (System.currentTimeMillis() - connectInitiatedAt < 200) return false;
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener cpl = mc.getConnection();
        if (cpl == null) return false;
        net.minecraft.network.Connection conn = cpl.getConnection();
        if (conn == null || !conn.isConnected()) return false;
        LocalPlayer cur = mc.player;
        if (cur == null) return false;
        //如果发起前player非null 必须等player引用变化才算新连接
        if (prevPlayerStrong != null && cur == prevPlayerStrong) return false;
        return true;
    }

    //debounce 连接被对方拒绝或断开 (出现DisconnectedScreen)
    public static boolean isConnectionRejected() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() instanceof DisconnectedScreen) return true;
        ClientPacketListener cpl = mc.getConnection();
        if (cpl != null && !cpl.getConnection().isConnected()) return true;
        return false;
    }

    //debounce 连接已完成 进入世界后清除发起状态 同时重置connecting避免CAS挡后续连接
    public static void clearConnectInitiated() {
        connectInitiatedAt = 0;
        prevPlayerStrong = null;
        connecting.set(false);
        //debounce 取消挂起的resetTask 避免旧任务把新连接的connecting误置false
        ScheduledFuture<?> t = resetTask;
        if (t != null) { t.cancel(false); resetTask = null; }
    }

    public static void connectToServer(int localPort, RoomInfo roomInfo) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (localPort > 0) {
                if (!connecting.compareAndSet(false, true)) {
                    VoxLinkMod.LOGGER.warn("[ConnectionHelper] 已经在连接了，忽略重复调用");
                    return;
                }
                try {
                    //记录发起前的player引用 用于后续真实连接判定
                    prevPlayerStrong = mc.player;
                    final long myStartAt = System.currentTimeMillis();
                    connectInitiatedAt = myStartAt;
                    roomInfo.setLocalBridgePort(localPort);
                    String addr = ViaCompat.buildViaAddress("127.0.0.1", localPort, roomInfo.getServerProtocolVersion());
                    ServerData serverData = createServerData(roomInfo.getName(), addr);
                    invokeStartConnecting(mc.gui.screen(), mc, addr, serverData);

                    //防卡住 owner校验防旧任务污染新连接
                    resetTask = RESET_SCHEDULER.schedule(() -> {
                        if (connecting.get() && connectInitiatedAt == myStartAt) {
                            VoxLinkMod.LOGGER.info("[ConnectionHelper] 30秒超时，自动重置connecting标志");
                            connecting.set(false);
                        }
                    }, CONNECT_RESET_SEC, TimeUnit.SECONDS);
                } catch (Exception e) {
                    connecting.set(false);
                    clearConnectInitiated();
                    VoxLinkMod.LOGGER.error("[ConnectionHelper] 连接失败: {}", e.getMessage());
                    String mode = roomInfo.getConnectionMode().getString();
                    sendError(mc, Component.translatable("voxlink.chat.connection_failed_detail", mode == null || mode.isEmpty() ? Component.translatable("voxlink.connection.cannot_establish").getString() : mode).getString());
                    VoxLinkMod.getRoomManager().leaveRoom();
                }
            } else {
                connecting.set(false);
                clearConnectInitiated();
                String mode = roomInfo.getConnectionMode().getString();
                sendError(mc, Component.translatable("voxlink.chat.connection_failed_detail", mode == null || mode.isEmpty() ? Component.translatable("voxlink.connection.cannot_establish").getString() : mode).getString());
                VoxLinkMod.getRoomManager().leaveRoom();
            }
        });
    }

    //跨版本反射构造ServerData, 兼容1.20~26.x构造签名变化
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ServerData createServerData(String name, String ip) throws Exception {
        Class<?> typeClass = Class.forName("net.minecraft.client.multiplayer.ServerData$Type");
        Object otherType = Enum.valueOf((Class<? extends Enum>) typeClass, "OTHER");
        Constructor<?>[] ctors = ServerData.class.getDeclaredConstructors();
        for (Constructor<?> c : ctors) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length >= 3 && p[0] == String.class && p[1] == String.class && typeClass.isAssignableFrom(p[2])) {
                Object[] args = new Object[p.length];
                args[0] = name; args[1] = ip; args[2] = otherType;
                for (int i = 3; i < p.length; i++) {
                    args[i] = p[i] == boolean.class ? false : null;
                }
                c.setAccessible(true);
                try {
                    return (ServerData) c.newInstance(args);
                } catch (Exception ignored) {}
            }
        }
        throw new RuntimeException("ServerData 构造函数未找到");
    }

    //跨版本反射调ConnectScreen.startConnecting, 兼容1.20~26.x签名变化
    private static void invokeStartConnecting(Screen parent, Minecraft mc, String addr, ServerData serverData) throws Exception {
        ServerAddress serverAddress = ServerAddress.parseString(addr);
        for (Method m : ConnectScreen.class.getDeclaredMethods()) {
            if (!"startConnecting".equals(m.getName())) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length < 4) continue;
            if (p[0] != Screen.class || p[1] != Minecraft.class) continue;
            if (p[2] != ServerAddress.class || !p[3].isAssignableFrom(serverData.getClass())) continue;
            Object[] args = new Object[p.length];
            args[0] = parent; args[1] = mc; args[2] = serverAddress; args[3] = serverData;
            for (int i = 4; i < p.length; i++) {
                args[i] = p[i] == boolean.class ? false : null;
            }
            m.setAccessible(true);
            try {
                m.invoke(null, args);
                VoxLinkMod.LOGGER.info("[ConnectionHelper] startConnecting 调用成功, 签名参数数={}", p.length);
                return;
            } catch (Exception e) {
                VoxLinkMod.LOGGER.warn("[ConnectionHelper] startConnecting 签名匹配但调用失败: {}", e.getMessage());
            }
        }
        throw new RuntimeException("ConnectScreen.startConnecting 未找到匹配签名");
    }

    private static void sendError(Minecraft mc, String msg) {
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal(msg));
        }
    }
}
