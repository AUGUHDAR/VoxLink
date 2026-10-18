package icu.wuhui.voxlink;

import icu.wuhui.voxlink.command.LanHostRegistry;
import icu.wuhui.voxlink.network.ConnectionHelper;
import icu.wuhui.voxlink.network.PeerServer;
import icu.wuhui.voxlink.network.SignalingClient;
import icu.wuhui.voxlink.network.StunProbe;
import icu.wuhui.voxlink.network.TopologyClient;
import icu.wuhui.voxlink.network.UPnPManager;
import icu.wuhui.voxlink.network.UpdateChecker;
import icu.wuhui.voxlink.room.RoomInfo;
import icu.wuhui.voxlink.room.RoomManager;
import icu.wuhui.voxlink.room.StunDetector;
import icu.wuhui.voxlink.terracotta.TerracottaManager;
import icu.wuhui.voxlink.ui.AttemptingJoinScreen;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingIn;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingOut;
import net.minecraftforge.event.TickEvent.ClientTickEvent.Post;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@EventBusSubscriber(
   modid = "voxlink",
   value = {Dist.CLIENT},
   bus = Bus.FORGE
)
public class VoxLinkClient {
   private static final int AUTO_LEAVE_DELAY_TICKS = 40;
   private static int autoLeaveTicks = 0;
   private static volatile boolean inited = false;
   private static volatile boolean wasInWorld = false;

   @SubscribeEvent
   public static void onClientSetup(FMLClientSetupEvent event) {
      event.enqueueWork(VoxLinkClient::initClient);
   }

   private static void initClient() {
      if (!inited) {
         inited = true;
         SignalingClient signalingClient = VoxLinkMod.getSignalingClient();
         if (signalingClient != null) {
            TopologyClient topologyClient = new TopologyClient(signalingClient);
            RoomManager roomManager = new RoomManager(signalingClient, topologyClient);
            VoxLinkMod.setTopologyClient(topologyClient);
            VoxLinkMod.setRoomManager(roomManager);

            try {
               StunProbe.probeAsync(StunDetector.getStunServerGroups());
            } catch (Exception var4) {
               VoxLinkMod.LOGGER.warn("STUN prefetch on init failed: {}", var4.getMessage());
            }

            roomManager.setRoomLostCallback(() -> {
               Minecraft mc = Minecraft.getInstance();
               if (mc != null) {
                  mc.execute(() -> {
                     if (mc.player != null) {
                        RoomManager rmRef = VoxLinkMod.getRoomManager();
                        String reason = rmRef != null ? rmRef.getRoomLostReason() : null;
                        String s0$ = reason != null ? reason : "";

                        Component reasonMsg = switch (s0$) {
                           case "HOST_CLOSED" -> Component.translatable("voxlink.room_lost.host_closed");
                           case "HOST_DISCONNECTED" -> Component.translatable("voxlink.room_lost.host_disconnected");
                           case "ROOM_NOT_FOUND" -> Component.translatable("voxlink.room_lost.host_gone");
                           case "TOKEN_INVALID" -> Component.translatable("voxlink.room_closed");
                           case "ROOM_EXPIRED" -> Component.translatable("voxlink.room_lost.room_expired");
                           case "ROOM_EVICTED" -> Component.translatable("voxlink.room_lost.room_evicted");
                           default -> Component.translatable("voxlink.room_lost.default");
                        };
                        mc.player.sendSystemMessage(Component.translatable("voxlink.chat.error_prefix").withStyle(ChatFormatting.RED).append(reasonMsg));
                        mc.player.sendSystemMessage(Component.translatable("voxlink.room_lost.hint").withStyle(ChatFormatting.GRAY));
                     }
                  });
               }
            });
         }

         PeerServer.refreshCache();
         if (VoxLinkMod.getConfig().isAutoUPnP()) {
            UPnPManager.tryMapAtStartup();
         }

         PeerServer.start();
         TerracottaManager.resumeDownloadIfPending();
      }
   }

   @SubscribeEvent
   public static void onServerStarting(ServerStartingEvent event) {
      if (event.getServer() instanceof IntegratedServer && VoxLinkMod.getConfig().isOfflineMode()) {
         RoomManager rmStart = VoxLinkMod.getRoomManager();
         RoomInfo room = rmStart != null ? rmStart.getCurrentRoom() : null;
         if (room != null && room.isHost()) {
            event.getServer().setUsesAuthentication(false);
            VoxLinkMod.LOGGER.info("Host room offline mode enabled");
         }

         if (event.getServer() instanceof IntegratedServer) {
            LanHostRegistry.scheduleCapture(event.getServer());
         }
      }
   }

   @SubscribeEvent
   public static void onClientPlayerLoggingIn(LoggingIn event) {
      UpdateChecker.checkOnce();
      wasInWorld = true;
   }

   @SubscribeEvent
   public static void onClientPlayerLoggingOut(LoggingOut event) {
      if (wasInWorld) {
         wasInWorld = false;
         RoomManager rm = VoxLinkMod.getRoomManager();
         if (rm != null && rm.isInRoom()) {
            rm.leaveRoom("退出世界");
         }

         try {
            TerracottaManager.shutdown();
         } catch (Exception var3) {
            VoxLinkMod.LOGGER.warn("Failed to stop Terracotta on world exit: {}", var3.getMessage());
         }
      }
   }

   @SubscribeEvent
   public static void onClientTickPost(Post event) {
      Minecraft client = Minecraft.getInstance();
      if (ConnectionHelper.isConnecting() && !(client.gui.screen() instanceof ConnectScreen) && !(client.gui.screen() instanceof AttemptingJoinScreen)) {
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
               autoLeaveTicks++;
               if (autoLeaveTicks >= 40) {
                  VoxLinkMod.LOGGER.info("MC exited world, auto leave room (after {} ticks)", autoLeaveTicks);
                  autoLeaveTicks = 0;
                  rmRef.leaveRoom("检测到已退出世界，自动离开");
               }
            } else {
               autoLeaveTicks = 0;
            }
         }
      }
   }
}
