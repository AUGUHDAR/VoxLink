package icu.wuhui.voxlink;

import icu.wuhui.voxlink.config.VoxLinkConfig;
import icu.wuhui.voxlink.network.P2PBridge;
import icu.wuhui.voxlink.network.PeerServer;
import icu.wuhui.voxlink.network.SignalingClient;
import icu.wuhui.voxlink.room.RoomManager;
import icu.wuhui.voxlink.network.TopologyClient;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import java.util.concurrent.atomic.AtomicBoolean;

import static net.minecraft.commands.Commands.literal;

public class VoxLinkMod implements ModInitializer {
    public static final String MOD_ID = "voxlink";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static final String MOD_VERSION = FabricLoader.getInstance().getModContainer(MOD_ID)
            .map(c -> c.getMetadata().getVersion().getFriendlyString())
            .orElse("1.0.0");

    //debounce 多线程读（ShutdownHook/Client线程） 主线程写 加volatile保证可见性
    private static volatile VoxLinkConfig config;
    private static volatile SignalingClient signalingClient;
    private static volatile RoomManager roomManager;
    private static volatile TopologyClient topologyClient;

    private static final AtomicBoolean shutdownDone = new AtomicBoolean(false);
    private static final int SHUTDOWN_DELAY_MS = 200;

    private static void doShutdown() {
        if (!shutdownDone.compareAndSet(false, true)) return;
        if (roomManager != null) {
            if (roomManager.isInRoom()) {
                //debounce leaveRoomSync包2s硬超时 防止ShutdownHook永久阻塞JVM退出
                java.util.concurrent.Future<?> leaveFuture = java.util.concurrent.CompletableFuture.runAsync(roomManager::leaveRoomSync);
                try {
                    leaveFuture.get(2, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception e) {
                    LOGGER.warn("leaveRoomSync超时 强制继续shutdown: {}", e.getMessage());
                }
                try {
                    Thread.sleep(SHUTDOWN_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            roomManager.shutdown();
        }
        if (topologyClient != null) topologyClient.onRoomLeft();
        if (signalingClient != null) {
            signalingClient.shutdown();
        }
        P2PBridge.disconnect();
        PeerServer.stop();
        icu.wuhui.voxlink.network.StunProbe.shutdown();
        icu.wuhui.voxlink.network.ConnectionFallback.shutdown();
        icu.wuhui.voxlink.network.UdpHolePuncher.shutdown();
        icu.wuhui.voxlink.network.TopologyClient.shutdown();
        icu.wuhui.voxlink.terracotta.TerracottaManager.shutdown();
    }

    @Override
    public void onInitialize() {
        config = VoxLinkConfig.load();
        //debounce SignalingClient构造失败不崩整个mod 客户端联机功能退化但不影响游戏
        try {
            signalingClient = new SignalingClient(config);
        } catch (Exception e) {
            LOGGER.error("SignalingClient初始化失败 联机功能不可用: {}", e.getMessage());
            signalingClient = null;
        }

        if (signalingClient != null && FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) {
            topologyClient = new TopologyClient(signalingClient);
            roomManager = new RoomManager(signalingClient, topologyClient);
        }

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
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
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (server instanceof net.minecraft.client.server.IntegratedServer) {
                VoxLinkMod.LOGGER.info("内置服务器停止，退房间（网络不断）");
                if (roomManager != null && roomManager.isInRoom()) {
                    roomManager.leaveRoom();
                }
            }
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            if (roomManager != null && roomManager.isInRoom()) {
                roomManager.leaveRoom();
            }
        });

        Runtime.getRuntime().addShutdownHook(new Thread(VoxLinkMod::doShutdown, "VoxLink-ShutdownHook"));

        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) {
            PeerServer.start();
            icu.wuhui.voxlink.terracotta.TerracottaManager.resumeDownloadIfPending();
        }

        LOGGER.info("VoxLink初始化完成");
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

    public static TopologyClient getTopologyClient() {
        return topologyClient;
    }
}