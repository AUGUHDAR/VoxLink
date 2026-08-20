package icu.wuhui.voxlink;

import icu.wuhui.voxlink.network.ConnectionHelper;
import icu.wuhui.voxlink.network.PeerServer;
import icu.wuhui.voxlink.network.SignalingClient;
import icu.wuhui.voxlink.network.UpdateChecker;
import icu.wuhui.voxlink.network.StunProbe;
import icu.wuhui.voxlink.room.RoomInfo;
import icu.wuhui.voxlink.room.RoomManager;
import icu.wuhui.voxlink.room.StunDetector;
import icu.wuhui.voxlink.network.TopologyClient;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;

@Mod.EventBusSubscriber(modid = VoxLinkMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class VoxLinkClient {

    private static final int AUTO_LEAVE_DELAY_TICKS = 40;

    private static int autoLeaveTicks = 0;
    private static volatile boolean inited = false;

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(VoxLinkClient::initClient);
    }

    private static void initClient() {
        if (inited) return;
        inited = true;

        SignalingClient signalingClient = VoxLinkMod.getSignalingClient();
        if (signalingClient != null) {
            TopologyClient topologyClient = new TopologyClient(signalingClient);
            RoomManager roomManager = new RoomManager(signalingClient, topologyClient);
            VoxLinkMod.setTopologyClient(topologyClient);
            VoxLinkMod.setRoomManager(roomManager);
            //debounce 后台预取STUN 主菜单时即开始探测
            try {
                StunProbe.probeAsync(StunDetector.getStunServerGroups());
            } catch (Exception e) {
                VoxLinkMod.LOGGER.warn("STUN prefetch on init failed: {}", e.getMessage());
            }

            roomManager.setRoomLostCallback(() -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc != null) {
                    mc.execute(() -> {
                        if (mc.player != null) {
                            RoomManager rmRef = VoxLinkMod.getRoomManager();
                            String reason = rmRef != null ? rmRef.getRoomLostReason() : null;
                            Component reasonMsg = switch (reason != null ? reason : "") {
                                case "HOST_CLOSED" -> Component.translatable("voxlink.room_lost.host_closed");
                                case "HOST_DISCONNECTED" -> Component.translatable("voxlink.room_lost.host_disconnected");
                                case "ROOM_NOT_FOUND" -> Component.translatable("voxlink.room_lost.host_gone");
                                case "TOKEN_INVALID" -> Component.translatable("voxlink.room_closed");
                                case "ROOM_EXPIRED" -> Component.translatable("voxlink.room_lost.room_expired");
                                case "ROOM_EVICTED" -> Component.translatable("voxlink.room_lost.room_evicted");
                                default -> Component.translatable("voxlink.room_lost.default");
                            };
                            mc.player.sendSystemMessage(
                                    Component.translatable("voxlink.chat.error_prefix").withStyle(ChatFormatting.RED).append(reasonMsg));
                            mc.player.sendSystemMessage(
                                    Component.translatable("voxlink.room_lost.hint").withStyle(ChatFormatting.GRAY));
                        }
                    });
                }
            });
        }

        PeerServer.refreshCache();

        if (VoxLinkMod.getConfig().isAutoUPnP()) {
            icu.wuhui.voxlink.network.UPnPManager.tryMapAtStartup();
        }

        PeerServer.start();
        icu.wuhui.voxlink.terracotta.TerracottaManager.resumeDownloadIfPending();
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        if (event.getServer() instanceof IntegratedServer && VoxLinkMod.getConfig().isOfflineMode()) {
            //debounce 仅在主持房间时关认证 单人世界未开LAN不受影响
            RoomManager rmStart = VoxLinkMod.getRoomManager();
            RoomInfo room = rmStart != null ? rmStart.getCurrentRoom() : null;
            if (room != null && room.isHost()) {
                event.getServer().setUsesAuthentication(false);
                VoxLinkMod.LOGGER.info("Host room offline mode enabled");
            }
        }
    }

    @SubscribeEvent
    public static void onClientPlayerLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        //debounce 进世界时检查一次更新
        UpdateChecker.checkOnce();
    }

    @SubscribeEvent
    public static void onClientPlayerLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        RoomManager rm = VoxLinkMod.getRoomManager();
        if (rm != null && rm.isInRoom()) {
            rm.leaveRoom();
        }
        //debounce 兜底杀陶瓦 防止退出世界后残留
        try { icu.wuhui.voxlink.terracotta.TerracottaManager.shutdown(); }
        catch (Exception e) { VoxLinkMod.LOGGER.warn("Failed to stop Terracotta on world exit: {}", e.getMessage()); }
    }

    @SubscribeEvent
    public static void onClientTickPost(TickEvent.ClientTickEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        //debounce AttemptingJoinScreen也驱动连接 不能在它存在时清connecting状态
        if (ConnectionHelper.isConnecting()
                && !(client.screen instanceof net.minecraft.client.gui.screens.ConnectScreen)
                && !(client.screen instanceof icu.wuhui.voxlink.ui.AttemptingJoinScreen)) {
            ConnectionHelper.resetConnecting();
        }
        RoomManager rmRef = VoxLinkMod.getRoomManager();
        if (rmRef == null) return;
        RoomInfo room = rmRef.getCurrentRoom();
        if (room == null) return;
        if (client.player == null && client.getSingleplayerServer() == null && room.getLocalBridgePort() > 0
                && !(client.screen instanceof net.minecraft.client.gui.screens.ConnectScreen)
                && !(client.screen instanceof icu.wuhui.voxlink.ui.AttemptingJoinScreen)
                && !ConnectionHelper.isConnecting()) {
            autoLeaveTicks++;
            if (autoLeaveTicks >= AUTO_LEAVE_DELAY_TICKS) {
                VoxLinkMod.LOGGER.info("MC exited world, auto leave room (after {} ticks)", autoLeaveTicks);
                autoLeaveTicks = 0;
                rmRef.leaveRoom();
            }
        } else {
            autoLeaveTicks = 0;
        }
    }
}
