package icu.wuhui.voxlink.network;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.compat.GeyserCompat;
import icu.wuhui.voxlink.compat.ViaCompat;
import icu.wuhui.voxlink.room.RoomInfo;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

public class PeerServer {
    private static final Logger LOGGER = LoggerFactory.getLogger("voxlink-peer");
    private static final Gson GSON = new Gson();
    private static HttpServer httpServer;
    private static int port = -1;
    private static volatile String cachedGameVersion = "";
    private static volatile int cachedProtocolVersion = 0;
    private static volatile List<Map<String, String>> cachedMods = Collections.emptyList();

    //Java21+用虚拟线程, Java17 fallback到cachedThreadPool, --release 17编译兼容
    private static java.util.concurrent.ExecutorService newVirtualThreadExecutor() {
        try {
            var m = Executors.class.getMethod("newVirtualThreadPerTaskExecutor");
            return (java.util.concurrent.ExecutorService) m.invoke(null);
        } catch (Exception e) {
            return Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "voxlink-peer");
                t.setDaemon(true);
                return t;
            });
        }
    }

    public static synchronized int start() {
        if (httpServer != null) return port;
        try {
            httpServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            httpServer.setExecutor(newVirtualThreadExecutor());
            httpServer.createContext("/info", exchange -> {
                String query = exchange.getRequestURI().getQuery();
                String token = null;
                if (query != null) {
                    for (String param : query.split("&")) {
                        String[] kv = param.split("=", 2);
                        if (kv.length == 2 && "token".equals(kv[0])) {
                            token = java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
                            break;
                        }
                    }
                }
                RoomInfo room = VoxLinkMod.getRoomManager() != null ? VoxLinkMod.getRoomManager().getCurrentRoom() : null;
                if (room == null || token == null || !token.equals(room.getToken())) {
                    byte[] err = "{\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(403, err.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(err);
                    }
                    return;
                }
                JsonObject info = buildInfo();
                byte[] data = GSON.toJson(info).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, data.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(data);
                }
            });
            httpServer.start();
            port = httpServer.getAddress().getPort();
            refreshCache();
            LOGGER.info("Peer服务启动，端口{}", port);
            return port;
        } catch (IOException e) {
            LOGGER.error("Peer服务启动失败: {}", e.getMessage());
            return -1;
        }
    }

    public static synchronized void stop() {
        if (httpServer != null) {
            httpServer.stop(1);
            httpServer = null;
            port = -1;
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
                        try { cachedGameVersion = mc.getLaunchedVersion(); } catch (Exception ignored) {}
                    });
                }
            }
        } catch (NoClassDefFoundError e) {
        }
        try {
            cachedProtocolVersion = ViaCompat.isViaLoaded() ? ViaCompat.getServerProtocolVersion() : 0;
        } catch (Exception e) {
            LOGGER.debug("获取协议版本失败: {}", e.getMessage());
        }
        try {
            List<Map<String, String>> mods = new java.util.ArrayList<>();
            net.fabricmc.loader.api.FabricLoader.getInstance().getAllMods().forEach(mod -> {
                Map<String, String> entry = new LinkedHashMap<>();
                entry.put("id", mod.getMetadata().getId());
                entry.put("version", mod.getMetadata().getVersion().getFriendlyString());
                mods.add(entry);
            });
            cachedMods = Collections.unmodifiableList(mods);
        } catch (Exception e) {
            LOGGER.debug("获取mod列表失败: {}", e.getMessage());
        }
    }

    private static JsonObject buildInfo() {
        JsonObject info = new JsonObject();
        var rm = VoxLinkMod.getRoomManager();
        if (rm == null) {
            info.addProperty("error", "RoomManager not initialized");
            return info;
        }
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
