package icu.wuhui.voxlink;

import icu.wuhui.voxlink.network.ConnectionHelper;
import icu.wuhui.voxlink.network.PeerServer;
import icu.wuhui.voxlink.room.RoomInfo;
import icu.wuhui.voxlink.room.RoomManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;

public class VoxLinkClient implements ClientModInitializer {

    private static final int AUTO_LEAVE_DELAY_TICKS = 40;

    private int autoLeaveTicks = 0;

    @Override
    public void onInitializeClient() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            if (server instanceof IntegratedServer && VoxLinkMod.getConfig().isOfflineMode()) {
                server.setUsesAuthentication(false);
                VoxLinkMod.LOGGER.info("离线模式已开启");
            }
        });

        RoomManager rm = VoxLinkMod.getRoomManager();
        if (rm != null) {
            rm.setRoomLostCallback(() -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc != null) {
                    mc.execute(() -> {
                        if (mc.player != null) {
                            RoomManager rmRef = VoxLinkMod.getRoomManager();
                            String reason = rmRef != null ? rmRef.getRoomLostReason() : null;
                            Component reasonMsg = switch (reason != null ? reason : "") {
                                case "HOST_CLOSED" -> Component.translatable("voxlink.room_lost.host_closed");
                                case "HOST_DISCONNECTED" -> Component.translatable("voxlink.room_lost.host_disconnected");
                                case "TOKEN_INVALID" -> Component.translatable("voxlink.room_closed");
                                case "ROOM_EXPIRED" -> Component.translatable("voxlink.room_lost.room_expired");
                                case "ROOM_EVICTED" -> Component.translatable("voxlink.room_lost.room_evicted");
                                default -> Component.translatable("voxlink.room_lost.default");
                            };
                            mc.player.sendSystemMessage(
                                    Component.translatable("voxlink.chat.error_prefix").append(reasonMsg));
                            mc.player.sendSystemMessage(
                                    Component.translatable("voxlink.room_lost.hint"));
                        }
                    });
                }
            });
        }

        PeerServer.refreshCache();

        //优化: 启动即尝试UPnP预发现网关, 开房时无延迟
        if (VoxLinkMod.getConfig().isAutoUPnP()) {
            icu.wuhui.voxlink.network.UPnPManager.tryMapAtStartup();
        }

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (ConnectionHelper.isConnecting() && !(client.screen instanceof net.minecraft.client.gui.screens.ConnectScreen)) {
                ConnectionHelper.resetConnecting();
            }
            RoomManager rmRef = VoxLinkMod.getRoomManager();
            if (rmRef == null) return;
            RoomInfo room = rmRef.getCurrentRoom();
            if (room == null) return;
            if (client.player == null && client.getSingleplayerServer() == null && room.getLocalBridgePort() > 0
                    && !(client.screen instanceof net.minecraft.client.gui.screens.ConnectScreen)
                    && !(client.screen instanceof icu.wuhui.voxlink.ui.AttemptingJoinScreen)) {
                autoLeaveTicks++;
                if (autoLeaveTicks >= AUTO_LEAVE_DELAY_TICKS) {
                    VoxLinkMod.LOGGER.info("MC退出世界，自动退房间({}tick后)", autoLeaveTicks);
                    autoLeaveTicks = 0;
                    rmRef.leaveRoom();
                }
            } else {
                autoLeaveTicks = 0;
            }
        });
    }
}