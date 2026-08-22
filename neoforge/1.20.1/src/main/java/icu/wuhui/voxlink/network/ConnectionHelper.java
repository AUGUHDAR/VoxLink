package icu.wuhui.voxlink.network;

import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.compat.ViaCompat;
import icu.wuhui.voxlink.room.RoomInfo;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;

public final class ConnectionHelper {
   private static final int CONNECT_RESET_SEC = 30;
   private static final AtomicBoolean connecting = new AtomicBoolean(false);
   private static volatile LocalPlayer prevPlayerStrong = null;
   private static volatile long connectInitiatedAt = 0L;
   private static volatile ScheduledFuture<?> resetTask = null;
   private static final ScheduledExecutorService RESET_SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "VoxLink-ConnReset");
      t.setDaemon(true);
      return t;
   });

   private ConnectionHelper() {
   }

   public static void resetConnecting() {
      connecting.set(false);
   }

   public static boolean isConnecting() {
      return connecting.get();
   }

   public static boolean isMcTrulyConnected() {
      if (connectInitiatedAt == 0L) {
         return false;
      } else if (System.currentTimeMillis() - connectInitiatedAt < 200L) {
         return false;
      } else {
         Minecraft mc = Minecraft.getInstance();
         ClientPacketListener cpl = mc.getConnection();
         if (cpl == null) {
            return false;
         } else {
            Connection conn = cpl.getConnection();
            if (conn != null && conn.isConnected()) {
               LocalPlayer cur = mc.player;
               return cur == null ? false : prevPlayerStrong == null || cur != prevPlayerStrong;
            } else {
               return false;
            }
         }
      }
   }

   public static boolean isConnectionRejected() {
      Minecraft mc = Minecraft.getInstance();
      if (mc.screen instanceof DisconnectedScreen) {
         return true;
      }

      ClientPacketListener cpl = mc.getConnection();
      return cpl != null && !cpl.getConnection().isConnected() && !(mc.screen instanceof ConnectScreen);
   }

   public static void clearConnectInitiated() {
      connectInitiatedAt = 0L;
      prevPlayerStrong = null;
      connecting.set(false);
      ScheduledFuture<?> t = resetTask;
      if (t != null) {
         t.cancel(false);
         resetTask = null;
      }
   }

   public static void connectToServer(int localPort, RoomInfo roomInfo) {
      Minecraft mc = Minecraft.getInstance();
      mc.execute(
         () -> {
            if (localPort > 0) {
               if (!connecting.compareAndSet(false, true)) {
                  VoxLinkMod.LOGGER.warn("[ConnectionHelper] Already connecting, ignore duplicate call");
                  return;
               }

               try {
                  prevPlayerStrong = mc.player;
                  long myStartAt = System.currentTimeMillis();
                  connectInitiatedAt = myStartAt;
                  roomInfo.setLocalBridgePort(localPort);
                  String addr = ViaCompat.buildViaAddress("127.0.0.1", localPort, roomInfo.getServerProtocolVersion());
                  ServerData serverData = createServerData(roomInfo.getName(), addr);
                  invokeStartConnecting(mc.screen, mc, addr, serverData);
                  resetTask = RESET_SCHEDULER.schedule(() -> {
                     if (connecting.get() && connectInitiatedAt == myStartAt) {
                        VoxLinkMod.LOGGER.info("[ConnectionHelper] 30s timeout, auto reset connecting flag");
                        connecting.set(false);
                     }
                  }, 30L, TimeUnit.SECONDS);
               } catch (Exception e) {
                  connecting.set(false);
                  clearConnectInitiated();
                  VoxLinkMod.LOGGER.error("[ConnectionHelper] Connection failed: {}", e.getMessage());
                  String mode = roomInfo.getConnectionMode().getString();
                  sendError(
                     mc,
                     Component.translatable(
                           "voxlink.chat.connection_failed_detail",
                           new Object[]{mode != null && !mode.isEmpty() ? mode : Component.translatable("voxlink.connection.cannot_establish").getString()}
                        )
                        .getString()
                  );
                  VoxLinkMod.getRoomManager().leaveRoom("连接失败");
               }
            } else {
               connecting.set(false);
               clearConnectInitiated();
               String mode = roomInfo.getConnectionMode().getString();
               sendError(
                  mc,
                  Component.translatable(
                        "voxlink.chat.connection_failed_detail",
                        new Object[]{mode != null && !mode.isEmpty() ? mode : Component.translatable("voxlink.connection.cannot_establish").getString()}
                     )
                     .getString()
               );
               VoxLinkMod.getRoomManager().leaveRoom("连接失败");
            }
         }
      );
   }

   private static ServerData createServerData(String name, String ip) throws Exception {
      Class<?> typeClass = null;
      Object otherType = null;
      try {
         typeClass = Class.forName("net.minecraft.client.multiplayer.ServerData$Type");
         otherType = Enum.valueOf((Class<? extends Enum>)typeClass, "OTHER");
      } catch (Throwable var20) {
      }
      Constructor<?>[] ctors = ServerData.class.getDeclaredConstructors();

      for (Constructor<?> c : ctors) {
         Class<?>[] p = c.getParameterTypes();
         if (p.length >= 3 && p[0] == String.class && p[1] == String.class && (typeClass != null ? typeClass.isAssignableFrom(p[2]) : p[2] == boolean.class)) {
            Object[] args = new Object[p.length];
            args[0] = name;
            args[1] = ip;
            args[2] = typeClass != null ? otherType : false;

            for (int i = 3; i < p.length; i++) {
               args[i] = p[i] == boolean.class ? false : null;
            }

            c.setAccessible(true);

            try {
               return (ServerData)c.newInstance(args);
            } catch (Exception var12) {
            }
         }
      }

      throw new RuntimeException("ServerData 构造函数未找到");
   }

   private static void invokeStartConnecting(Screen parent, Minecraft mc, String addr, ServerData serverData) throws Exception {
      ServerAddress serverAddress = ServerAddress.parseString(addr);
      // 按参数升序尝试, 优先命中真正最小重载, 避免撞上不发起连接的7参内部重载
      List<Method> candidates = new ArrayList<>();
      for (Method m : ConnectScreen.class.getDeclaredMethods()) {
         if (Modifier.isStatic(m.getModifiers())) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length >= 4 && p[0] == Screen.class && p[1] == Minecraft.class && p[2] == ServerAddress.class && p[3].isAssignableFrom(serverData.getClass())) {
               candidates.add(m);
            }
         }
      }
      candidates.sort(Comparator.comparingInt(Method::getParameterCount));
      for (Method m : candidates) {
         Class<?>[] p = m.getParameterTypes();
         Object[] args = new Object[p.length];
         args[0] = parent;
         args[1] = mc;
         args[2] = serverAddress;
         args[3] = serverData;
         for (int i = 4; i < p.length; i++) {
            args[i] = p[i] == boolean.class ? false : null;
         }
         m.setAccessible(true);
         try {
            m.invoke(null, args);
            VoxLinkMod.LOGGER.info("[ConnectionHelper] startConnecting success, signature param count={}", p.length);
            return;
         } catch (Exception e) {
            VoxLinkMod.LOGGER.warn("[ConnectionHelper] startConnecting signature matched but call failed: {}", e.getMessage());
         }
      }
      throw new RuntimeException("ConnectScreen.startConnecting 未找到可调用签名");
   }

   private static void sendError(Minecraft mc, String msg) {
      if (mc.player != null) {
         mc.player.sendSystemMessage(Component.literal(msg));
      }
   }
}
