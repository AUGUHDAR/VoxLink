package icu.wuhui.voxlink;

import icu.wuhui.voxlink.network.ConnectionHelper;
import icu.wuhui.voxlink.network.PeerServer;
import icu.wuhui.voxlink.network.UPnPManager;
import icu.wuhui.voxlink.network.UpdateChecker;
import icu.wuhui.voxlink.room.RoomInfo;
import icu.wuhui.voxlink.room.RoomManager;
import icu.wuhui.voxlink.ui.AttemptingJoinScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.EndTick;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.Join;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.ServerStarting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;

public class VoxLinkClient implements ClientModInitializer {
   private static final int AUTO_LEAVE_DELAY_TICKS = 40;
   private int autoLeaveTicks = 0;

   public void onInitializeClient() {
      ServerLifecycleEvents.SERVER_STARTING.register((ServerStarting)server -> {
         if (server instanceof IntegratedServer && VoxLinkMod.getConfig().isOfflineMode()) {
            RoomManager rmStart = VoxLinkMod.getRoomManager();
            RoomInfo room = rmStart != null ? rmStart.getCurrentRoom() : null;
            if (room != null && room.isHost()) {
               server.setUsesAuthentication(false);
               VoxLinkMod.LOGGER.info("Host room offline mode enabled");
            }
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
                     mc.player.sendSystemMessage(Component.translatable("voxlink.chat.error_prefix").append(reasonMsg));
                     mc.player.sendSystemMessage(Component.translatable("voxlink.room_lost.hint"));
                  }
               });
            }
         });
      }

      PeerServer.refreshCache();
      if (VoxLinkMod.getConfig().isAutoUPnP()) {
         UPnPManager.tryMapAtStartup();
      }

      ClientPlayConnectionEvents.JOIN.register((Join)(handler, sender, client) -> UpdateChecker.checkOnce());
      ClientTickEvents.END_CLIENT_TICK
         .register(
            (EndTick)client -> {
               if (ConnectionHelper.isConnecting() && !(client.gui.screen() instanceof ConnectScreen) && !(client.gui.screen() instanceof AttemptingJoinScreen)
                  )
                {
                  ConnectionHelper.resetConnecting();
               }

               RoomManager rmRef = VoxLinkMod.getRoomManager();
               if (rmRef != null) {
                  RoomInfo room = rmRef.getCurrentRoom();
                  if (room != null) {
                     if (client.player == null
                        && client.getSingleplayerServer() == null
                        && room.getLocalBridgePort() > 0
                        && !(client.gui.screen() instanceof ConnectScreen)
                        && !(client.gui.screen() instanceof AttemptingJoinScreen)
                        && !ConnectionHelper.isConnecting()) {
                        this.autoLeaveTicks++;
                        if (this.autoLeaveTicks >= 40) {
                           VoxLinkMod.LOGGER.info("MC exited world, auto leave room (after {} ticks)", this.autoLeaveTicks);
                           this.autoLeaveTicks = 0;
                           rmRef.leaveRoom();
                        }
                     } else {
                        this.autoLeaveTicks = 0;
                     }
                  }
               }
            }
         );
   }
}
