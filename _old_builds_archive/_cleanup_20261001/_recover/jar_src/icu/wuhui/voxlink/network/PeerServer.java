package icu.wuhui.voxlink.network;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.compat.GeyserCompat;
import icu.wuhui.voxlink.compat.ViaCompat;
import icu.wuhui.voxlink.room.RoomInfo;
import icu.wuhui.voxlink.room.RoomManager;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PeerServer {
   private static final Logger LOGGER = LoggerFactory.getLogger("voxlink-peer");
   private static final Gson GSON = new Gson();
   private static HttpServer httpServer;
   private static int port = -1;
   private static ExecutorService peerExecutor = null;
   private static volatile String cachedGameVersion = "";
   private static volatile int cachedProtocolVersion = 0;
   private static volatile List<Map<String, String>> cachedMods = Collections.emptyList();

   private static ExecutorService newVirtualThreadExecutor() {
      try {
         Method m = Executors.class.getMethod("newVirtualThreadPerTaskExecutor");
         return (ExecutorService)m.invoke(null);
      } catch (Exception var1) {
         return Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "voxlink-peer");
            t.setDaemon(true);
            return t;
         });
      }
   }

   public static synchronized int start() {
      if (httpServer != null) {
         return port;
      } else {
         CountDownLatch ready = new CountDownLatch(1);
         int[] result = new int[]{-1};
         Thread creator = new Thread(
            null,
            () -> {
               try {
                  httpServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
                  peerExecutor = newVirtualThreadExecutor();
                  httpServer.setExecutor(peerExecutor);
                  httpServer.createContext(
                     "/info",
                     exchange -> {
                        String query = exchange.getRequestURI().getQuery();
                        String token = null;
                        if (query != null) {
                           for (String param : query.split("&")) {
                              String[] kv = param.split("=", 2);
                              if (kv.length == 2 && "token".equals(kv[0])) {
                                 token = URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
                                 break;
                              }
                           }
                        }

                        RoomInfo room = VoxLinkMod.getRoomManager() != null ? VoxLinkMod.getRoomManager().getCurrentRoom() : null;
                        boolean tokenValid = room != null
                           && token != null
                           && MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), room.getToken().getBytes(StandardCharsets.UTF_8));
                        if (!tokenValid) {
                           byte[] err = "{\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8);
                           exchange.getResponseHeaders().set("Content-Type", "application/json");
                           exchange.sendResponseHeaders(403, (long)err.length);

                           try (OutputStream os = exchange.getResponseBody()) {
                              os.write(err);
                           }
                        } else {
                           JsonObject info = buildInfo();
                           byte[] data = GSON.toJson(info).getBytes(StandardCharsets.UTF_8);
                           exchange.getResponseHeaders().set("Content-Type", "application/json");
                           exchange.sendResponseHeaders(200, (long)data.length);

                           try (OutputStream os = exchange.getResponseBody()) {
                              os.write(data);
                           }
                        }
                     }
                  );
                  httpServer.start();
                  port = httpServer.getAddress().getPort();
                  refreshCache();
                  result[0] = port;
                  LOGGER.info("Peer server started, port {}", port);
               } catch (IOException var6) {
                  LOGGER.error("Peer server start failed: {}", var6.getMessage());
                  result[0] = -1;
               } finally {
                  ready.countDown();
               }
            },
            "voxlink-peer-starter"
         );
         creator.setDaemon(true);
         creator.start();

         try {
            if (!ready.await(5L, TimeUnit.SECONDS)) {
               LOGGER.error("Peer server start timed out");
               return -1;
            }
         } catch (InterruptedException var4) {
            Thread.currentThread().interrupt();
            return -1;
         }

         return result[0];
      }
   }

   public static synchronized void stop() {
      if (httpServer != null) {
         httpServer.stop(1);
         httpServer = null;
         port = -1;
      }

      if (peerExecutor != null) {
         peerExecutor.shutdown();
         peerExecutor.shutdownNow();
         peerExecutor = null;
      }
   }

   public static int getPort() {
      return port;
   }

   public static void refreshCache() {
      try {
         Minecraft mc = Minecraft.getInstance();
         if (mc != null) {
            if (mc.isSameThread()) {
               cachedGameVersion = mc.getLaunchedVersion();
            } else {
               mc.execute(() -> {
                  try {
                     cachedGameVersion = mc.getLaunchedVersion();
                  } catch (Exception var2x) {
                  }
               });
            }
         }
      } catch (NoClassDefFoundError var3) {
      }

      try {
         cachedProtocolVersion = ViaCompat.isViaLoaded() ? ViaCompat.getServerProtocolVersion() : 0;
      } catch (Exception var2) {
         LOGGER.debug("Failed to get protocol version: {}", var2.getMessage());
      }

      try {
         List<Map<String, String>> mods = new ArrayList<>();
         ModList.getMods().forEach(mod -> {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("id", mod.getModId());
            entry.put("version", mod.getVersion().toString());
            mods.add(entry);
         });
         cachedMods = Collections.unmodifiableList(mods);
      } catch (Exception var1) {
         LOGGER.debug("Failed to get mod list: {}", var1.getMessage());
      }
   }

   private static JsonObject buildInfo() {
      JsonObject info = new JsonObject();
      RoomManager rm = VoxLinkMod.getRoomManager();
      if (rm == null) {
         info.addProperty("error", "RoomManager not initialized");
         return info;
      } else {
         RoomInfo room = rm.getCurrentRoom();
         if (room != null) {
            info.addProperty("hasRoom", true);
            info.addProperty("code", room.getCode());
            info.addProperty("name", room.getName());
            info.addProperty("currentPlayers", room.getCurrentPlayers());
            info.addProperty("maxPlayers", room.getMaxPlayers());
            info.addProperty("hasPassword", room.hasPassword());
            info.addProperty("category", room.getCategory() != null ? room.getCategory() : "other");
            info.addProperty("natType", room.getNatType());
            info.addProperty("hostPort", room.getHostPort());
            info.addProperty("bedrockPort", room.getBedrockPort());
            info.addProperty("protocolVersion", room.getServerProtocolVersion());
         } else {
            info.addProperty("hasRoom", false);
         }

         info.addProperty("gameVersion", cachedGameVersion);
         info.addProperty("serverProtocolVersion", cachedProtocolVersion);
         info.addProperty("geyserLoaded", GeyserCompat.isGeyserLoaded());
         info.addProperty("voxlinkVersion", VoxLinkMod.MOD_VERSION);
         info.add("mods", GSON.toJsonTree(cachedMods));
         return info;
      }
   }
}
