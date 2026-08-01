package icu.wuhui.voxlink;

import icu.wuhui.voxlink.command.LanCommandRegistry;
import icu.wuhui.voxlink.config.VoxLinkConfig;
import icu.wuhui.voxlink.network.P2PBridge;
import icu.wuhui.voxlink.network.PeerServer;
import icu.wuhui.voxlink.network.SignalingClient;
import icu.wuhui.voxlink.network.TopologyClient;
import icu.wuhui.voxlink.room.RoomManager;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

import static net.minecraft.commands.Commands.literal;

@Mod(VoxLinkMod.MOD_ID)
public class VoxLinkMod {
    public static final String MOD_ID = "voxlink";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static final String MOD_VERSION = ModList.get().getModContainerById(MOD_ID)
            .map(c -> c.getModInfo().getVersion().toString())
            .orElse("1.0.0");

    //debounce 多线程读(ShutdownHook/Client) 主线程写 加volatile
    private static volatile VoxLinkConfig config;
    private static volatile SignalingClient signalingClient;
    private static volatile RoomManager roomManager;
    private static volatile TopologyClient topologyClient;

    private static final AtomicBoolean shutdownDone = new AtomicBoolean(false);
    private static final int SHUTDOWN_DELAY_MS = 200;

    public VoxLinkMod(IEventBus modEventBus) {
        LOGGER.info("VoxLink NeoForge initializing (dist={})", FMLEnvironment.dist);

        config = VoxLinkConfig.load();
        //debounce SignalingClient构造失败不崩整个mod 客户端联机功能退化但不影响游戏
        try {
            signalingClient = new SignalingClient(config);
        } catch (Exception e) {
            LOGGER.error("SignalingClient init failed, multiplayer unavailable: {}", e.getMessage());
            signalingClient = null;
        }

        NeoForge.EVENT_BUS.addListener(VoxLinkMod::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(VoxLinkMod::onServerStopping);

        Runtime.getRuntime().addShutdownHook(new Thread(VoxLinkMod::doShutdown, "VoxLink-ShutdownHook"));

        LOGGER.info("VoxLink initialized");
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        var dispatcher = event.getDispatcher();
        dispatcher.register(literal("voxlink")
                .then(literal("leave").executes(ctx -> {
                    if (roomManager != null) roomManager.leaveRoom();
                    return 1;
                }))
                .then(literal("close").executes(ctx -> {
                    if (roomManager != null) roomManager.closeRoom();
                    return 1;
                }))
                .then(literal("info").executes(ctx -> {
                    if (roomManager != null) roomManager.showRoomInfo(ctx.getSource());
                    return 1;
                }))
        );
        //debounce 房主管理访客命令 LAN模式下host始终可用
        LanCommandRegistry.register(dispatcher);
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        if (event.getServer() instanceof net.minecraft.client.server.IntegratedServer) {
            VoxLinkMod.LOGGER.info("Built-in server stopped, leaving room (network kept)");
            if (roomManager != null && roomManager.isInRoom()) {
                roomManager.leaveRoom();
            }
        }
    }

    private static void doShutdown() {
        if (!shutdownDone.compareAndSet(false, true)) return;
        if (roomManager != null) {
            if (roomManager.isInRoom()) {
                //debounce leaveRoomSync包2s硬超时 防ShutdownHook阻塞JVM退出
                java.util.concurrent.Future<?> leaveFuture = java.util.concurrent.CompletableFuture.runAsync(roomManager::leaveRoomSync);
                try {
                    leaveFuture.get(2, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception e) {
                    LOGGER.warn("leaveRoomSync timeout, force continue shutdown: {}", e.getMessage());
                }
                try {
                    Thread.sleep(SHUTDOWN_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            try { roomManager.shutdown(); } catch (Exception e) { LOGGER.warn("roomManager.shutdown exception: {}", e.getMessage()); }
        }
        try { if (topologyClient != null) topologyClient.onRoomLeft(); } catch (Exception e) { LOGGER.warn("topologyClient.onRoomLeft exception: {}", e.getMessage()); }
        try {
            if (signalingClient != null) {
                signalingClient.shutdown();
            }
        } catch (Exception e) { LOGGER.warn("signalingClient.shutdown exception: {}", e.getMessage()); }
        try { P2PBridge.disconnect(); } catch (Exception e) { LOGGER.warn("P2PBridge.disconnect exception: {}", e.getMessage()); }
        try { PeerServer.stop(); } catch (Exception e) { LOGGER.warn("PeerServer.stop exception: {}", e.getMessage()); }
        try { icu.wuhui.voxlink.network.StunProbe.shutdown(); } catch (Exception e) { LOGGER.warn("StunProbe.shutdown exception: {}", e.getMessage()); }
        try { icu.wuhui.voxlink.network.ConnectionFallback.shutdown(); } catch (Exception e) { LOGGER.warn("ConnectionFallback.shutdown exception: {}", e.getMessage()); }
        try { icu.wuhui.voxlink.network.UdpHolePuncher.shutdown(); } catch (Exception e) { LOGGER.warn("UdpHolePuncher.shutdown exception: {}", e.getMessage()); }
        try { icu.wuhui.voxlink.network.TopologyClient.shutdown(); } catch (Exception e) { LOGGER.warn("TopologyClient.shutdown exception: {}", e.getMessage()); }
        try { icu.wuhui.voxlink.terracotta.TerracottaManager.shutdown(); } catch (Exception e) { LOGGER.warn("TerracottaManager.shutdown exception: {}", e.getMessage()); }
    }

    public static VoxLinkConfig getConfig() {
        return config;
    }

    public static SignalingClient getSignalingClient() {
        return signalingClient;
    }

    public static RoomManager getRoomManager() {
        return roomManager;
    }

    public static void setRoomManager(RoomManager rm) {
        roomManager = rm;
    }

    public static TopologyClient getTopologyClient() {
        return topologyClient;
    }

    public static void setTopologyClient(TopologyClient tc) {
        topologyClient = tc;
    }
}
