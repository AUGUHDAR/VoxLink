package icu.wuhui.voxlink;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import icu.wuhui.voxlink.command.LanCommandRegistry;
import icu.wuhui.voxlink.command.LanHostRegistry;
import icu.wuhui.voxlink.config.LogUploadState;
import icu.wuhui.voxlink.config.VoxLinkConfig;
import icu.wuhui.voxlink.network.ConnectionFallback;
import icu.wuhui.voxlink.network.P2PBridge;
import icu.wuhui.voxlink.network.PeerServer;
import icu.wuhui.voxlink.network.SignalingClient;
import icu.wuhui.voxlink.network.StunProbe;
import icu.wuhui.voxlink.network.TopologyClient;
import icu.wuhui.voxlink.network.UdpHolePuncher;
import icu.wuhui.voxlink.room.RoomManager;
import icu.wuhui.voxlink.room.StunDetector;
import icu.wuhui.voxlink.terracotta.TerracottaManager;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.Disconnect;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.ServerStopping;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VoxLinkMod implements ModInitializer {
   public static final String MOD_ID = "voxlink";
   public static final Logger LOGGER = LoggerFactory.getLogger("voxlink");
   public static final String MOD_VERSION = FabricLoader.getInstance()
      .getModContainer("voxlink")
      .map(c -> c.getMetadata().getVersion().getFriendlyString())
      .orElse("1.0.0");
   private static volatile VoxLinkConfig config;
   private static volatile SignalingClient signalingClient;
   private static volatile RoomManager roomManager;
   private static volatile TopologyClient topologyClient;
   private static final AtomicBoolean shutdownDone = new AtomicBoolean(false);

   private static void doShutdown() {
      if (shutdownDone.compareAndSet(false, true)) {
         if (roomManager != null) {
            if (roomManager.isInRoom()) {
               try {
                  CompletableFuture.runAsync(roomManager::leaveRoomSync).exceptionally(e -> {
                     LOGGER.warn("leaveRoomSync async failed: {}", e.getMessage());
                     return null;
                  });
               } catch (Exception e) {
                  LOGGER.warn("leaveRoomSync submit failed: {}", e.getMessage());
               }
            }

            try {
               roomManager.shutdown();
            } catch (Exception e) {
               LOGGER.warn("roomManager.shutdown exception: {}", e.getMessage());
            }
         }

         try {
            if (topologyClient != null) {
               topologyClient.onRoomLeft();
            }
         } catch (Exception e) {
            LOGGER.warn("topologyClient.onRoomLeft exception: {}", e.getMessage());
         }

         try {
            if (signalingClient != null) {
               signalingClient.shutdown();
            }
         } catch (Exception e) {
            LOGGER.warn("signalingClient.shutdown exception: {}", e.getMessage());
         }

         try {
            P2PBridge.disconnect();
         } catch (Exception e) {
            LOGGER.warn("P2PBridge.disconnect exception: {}", e.getMessage());
         }

         try {
            PeerServer.stop();
         } catch (Exception e) {
            LOGGER.warn("PeerServer.stop exception: {}", e.getMessage());
         }

         try {
            StunProbe.shutdown();
         } catch (Exception e) {
            LOGGER.warn("StunProbe.shutdown exception: {}", e.getMessage());
         }

         try {
            ConnectionFallback.shutdown();
         } catch (Exception e) {
            LOGGER.warn("ConnectionFallback.shutdown exception: {}", e.getMessage());
         }

         try {
            UdpHolePuncher.shutdown();
         } catch (Exception e) {
            LOGGER.warn("UdpHolePuncher.shutdown exception: {}", e.getMessage());
         }

         try {
            TopologyClient.shutdown();
         } catch (Exception e) {
            LOGGER.warn("TopologyClient.shutdown exception: {}", e.getMessage());
         }

         try {
            TerracottaManager.shutdown();
         } catch (Exception e) {
            LOGGER.warn("TerracottaManager.shutdown exception: {}", e.getMessage());
         }
      }
   }

   public void onInitialize() {
      config = VoxLinkConfig.load();
      // 日志上传默认开启：内存开关以持久化配置初始化（用户在 GUI 主动关闭后保持关闭）
      LogUploadState.setLogUploadEnabled(config.isLogUploadEnabled());



      try {
         signalingClient = new SignalingClient(config);
      } catch (Exception e) {
         LOGGER.error("SignalingClient init failed, multiplayer unavailable: {}", e.getMessage());
         signalingClient = null;
      }

      if (signalingClient != null && FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) {
         topologyClient = new TopologyClient(signalingClient);
         roomManager = new RoomManager(signalingClient, topologyClient);

         try {
            StunProbe.probeAsync(StunDetector.getStunServerGroups());
         } catch (Exception e) {
            LOGGER.warn("STUN prefetch on init failed: {}", e.getMessage());
         }
      }

      CommandRegistrationCallback.EVENT
         .register(
            (CommandRegistrationCallback)(dispatcher, registryAccess, environment) -> {
               dispatcher.register(
                  (LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal("voxlink")
                           .then(Commands.literal("leave").executes(ctx -> {
                              if (roomManager != null) {
                                 roomManager.leaveRoom();
                              }

                              return 1;
                           })))
                        .then(Commands.literal("close").executes(ctx -> {
                           if (roomManager != null) {
                              roomManager.closeRoom();
                           }

                           return 1;
                        })))
                     .then(Commands.literal("info").executes(ctx -> {
                        if (roomManager != null) {
                           roomManager.showRoomInfo((CommandSourceStack)ctx.getSource());
                        }

                        return 1;
                     }))
               );
               LanCommandRegistry.register(dispatcher);
            }
         );
      ServerLifecycleEvents.SERVER_STOPPING.register((ServerStopping)server -> {
         if (server instanceof IntegratedServer) {
            LOGGER.info("Built-in server stopped, leaving room (network kept)");
            LanHostRegistry.clear();
            if (roomManager != null && roomManager.isInRoom()) {
               roomManager.leaveRoom("连接断开");
            }
         }
      });
      ClientPlayConnectionEvents.DISCONNECT.register((Disconnect)(handler, client) -> {
         if (roomManager != null && roomManager.isInRoom()) {
            roomManager.leaveRoom("连接断开");
         }

         try {
            TerracottaManager.shutdown();
         } catch (Exception e) {
            LOGGER.warn("Failed to stop Terracotta on world exit: {}", e.getMessage());
         }
      });
      Runtime.getRuntime().addShutdownHook(new Thread(VoxLinkMod::doShutdown, "VoxLink-ShutdownHook"));
      if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) {
         PeerServer.start();
         TerracottaManager.resumeDownloadIfPending();
      }

      LOGGER.info("VoxLink initialized");
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
