package icu.wuhui.voxlink;

import com.mojang.brigadier.CommandDispatcher;
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
import icu.wuhui.voxlink.terracotta.TerracottaManager;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod("voxlink")
public class VoxLinkMod {
   public static final String MOD_ID = "voxlink";
   public static final Logger LOGGER = LoggerFactory.getLogger("voxlink");
   public static final String MOD_VERSION = ModList.getModContainerById("voxlink").map(c -> c.getModInfo().getVersion().toString()).orElse("1.0.0");
   private static volatile VoxLinkConfig config;
   private static volatile SignalingClient signalingClient;
   private static volatile RoomManager roomManager;
   private static volatile TopologyClient topologyClient;
   private static final AtomicBoolean shutdownDone = new AtomicBoolean(false);

   public VoxLinkMod(FMLJavaModLoadingContext context) {
      LOGGER.info("VoxLink Forge initializing (dist={})", FMLEnvironment.dist);
      config = VoxLinkConfig.load();
      LogUploadState.setLogUploadEnabled(config.isLogUploadEnabled());

      try {
         signalingClient = new SignalingClient(config);
      } catch (Exception var3) {
         LOGGER.error("SignalingClient init failed, multiplayer unavailable: {}", var3.getMessage());
         signalingClient = null;
      }

      RegisterCommandsEvent.BUS.addListener(VoxLinkMod::onRegisterCommands);
      ServerStoppingEvent.BUS.addListener(VoxLinkMod::onServerStopping);
      FMLClientSetupEvent.getBus(context.getModBusGroup()).addListener(VoxLinkClient::onClientSetup);
      Runtime.getRuntime().addShutdownHook(new Thread(VoxLinkMod::doShutdown, "VoxLink-ShutdownHook"));
      LOGGER.info("VoxLink initialized");
   }

   private static void onRegisterCommands(RegisterCommandsEvent event) {
      CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
      dispatcher.register(
         (LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal("voxlink").then(Commands.literal("leave").executes(ctx -> {
            if (roomManager != null) {
               roomManager.leaveRoom("/voxlink leave 命令");
            }

            return 1;
         }))).then(Commands.literal("close").executes(ctx -> {
            if (roomManager != null) {
               roomManager.closeRoom("/voxlink close 命令");
            }

            return 1;
         }))).then(Commands.literal("info").executes(ctx -> {
            if (roomManager != null) {
               roomManager.showRoomInfo((CommandSourceStack)ctx.getSource());
            }

            return 1;
         }))
      );
      LanCommandRegistry.register(dispatcher);
   }

   private static void onServerStopping(ServerStoppingEvent event) {
      if (event.getServer() instanceof IntegratedServer) {
         LOGGER.info("Built-in server stopped, leaving room (network kept)");
         LanHostRegistry.clear();
         if (roomManager != null && roomManager.isInRoom()) {
            roomManager.leaveRoom("集成服务器停止");
         }
      }
   }

   private static void doShutdown() {
      if (shutdownDone.compareAndSet(false, true)) {
         if (roomManager != null) {
            if (roomManager.isInRoom()) {
               try {
                  CompletableFuture.runAsync(roomManager::leaveRoomSync).exceptionally(e -> {
                     LOGGER.warn("leaveRoomSync async failed: {}", e.getMessage());
                     return null;
                  });
               } catch (Exception var11) {
                  LOGGER.warn("leaveRoomSync submit failed: {}", var11.getMessage());
               }
            }

            try {
               roomManager.shutdown();
            } catch (Exception var10) {
               LOGGER.warn("roomManager.shutdown exception: {}", var10.getMessage());
            }
         }

         try {
            if (topologyClient != null) {
               topologyClient.onRoomLeft();
            }
         } catch (Exception var9) {
            LOGGER.warn("topologyClient.onRoomLeft exception: {}", var9.getMessage());
         }

         try {
            if (signalingClient != null) {
               signalingClient.shutdown();
            }
         } catch (Exception var8) {
            LOGGER.warn("signalingClient.shutdown exception: {}", var8.getMessage());
         }

         try {
            P2PBridge.disconnect();
         } catch (Exception var7) {
            LOGGER.warn("P2PBridge.disconnect exception: {}", var7.getMessage());
         }

         try {
            PeerServer.stop();
         } catch (Exception var6) {
            LOGGER.warn("PeerServer.stop exception: {}", var6.getMessage());
         }

         try {
            StunProbe.shutdown();
         } catch (Exception var5) {
            LOGGER.warn("StunProbe.shutdown exception: {}", var5.getMessage());
         }

         try {
            ConnectionFallback.shutdown();
         } catch (Exception var4) {
            LOGGER.warn("ConnectionFallback.shutdown exception: {}", var4.getMessage());
         }

         try {
            UdpHolePuncher.shutdown();
         } catch (Exception var3) {
            LOGGER.warn("UdpHolePuncher.shutdown exception: {}", var3.getMessage());
         }

         try {
            TopologyClient.shutdown();
         } catch (Exception var2) {
            LOGGER.warn("TopologyClient.shutdown exception: {}", var2.getMessage());
         }

         try {
            TerracottaManager.shutdown();
         } catch (Exception var1) {
            LOGGER.warn("TerracottaManager.shutdown exception: {}", var1.getMessage());
         }
      }
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
