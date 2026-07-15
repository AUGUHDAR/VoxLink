package icu.wuhui.voxlink.compat;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

public final class GeyserCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger("voxlink-geyser");
    private static final int DEFAULT_BEDROCK_PORT = 19132;
    private static volatile Integer bedrockPort = null;

    private GeyserCompat() {}

    public static boolean isGeyserLoaded() {
        return FabricLoader.getInstance().isModLoaded("geyser-fabric")
                || FabricLoader.getInstance().isModLoaded("geyser");
    }

    public static boolean isFloodgateLoaded() {
        return FabricLoader.getInstance().isModLoaded("floodgate")
                || FabricLoader.getInstance().isModLoaded("floodgate-fabric");
    }

    public static boolean isBedrockPlayer(UUID uuid) {
        if (uuid == null) return false;
        if (!isGeyserLoaded() && !isFloodgateLoaded()) return false;
        try {
            var geyserApiClass = Class.forName("org.geysermc.geyser.api.GeyserApi");
            var api = geyserApiClass.getMethod("api").invoke(null);
            if (api != null) {
                var connection = api.getClass().getMethod("connectionByUuid", UUID.class).invoke(api, uuid);
                return connection != null;
            }
        } catch (Throwable e) {
            LOGGER.debug("GeyserApi.connectionByUuid() 挂了: {}", e.getMessage());
        }
        if (isFloodgateLoaded()) {
            try {
                var floodgateApiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
                Object floodgateApi;
                try {
                    var getInstance = floodgateApiClass.getMethod("getInstance");
                    floodgateApi = getInstance.invoke(null);
                } catch (NoSuchMethodException ex) {
                    var instanceField = floodgateApiClass.getField("INSTANCE");
                    floodgateApi = instanceField.get(null);
                }
                if (floodgateApi != null) {
                    var isFloodgatePlayer = floodgateApi.getClass().getMethod("isFloodgatePlayer", UUID.class);
                    return Boolean.TRUE.equals(isFloodgatePlayer.invoke(floodgateApi, uuid));
                }
            } catch (Throwable e) {
                LOGGER.debug("FloodgateApi.isFloodgatePlayer() 挂了: {}", e.getMessage());
            }
        }
        return false;
    }

    public static boolean isBedrockPlayer(String playerName) {
        if (!isGeyserLoaded()) return false;
        try {
            var geyserApiClass = Class.forName("org.geysermc.geyser.api.GeyserApi");
            var apiMethod = geyserApiClass.getMethod("api");
            var api = apiMethod.invoke(null);
            if (api == null) return false;
            var connections = (java.util.List<?>) api.getClass().getMethod("onlineConnections").invoke(api);
            for (var conn : connections) {
                var name = conn.getClass().getMethod("name").invoke(conn);
                if (playerName.equalsIgnoreCase((String) name)) return true;
            }
        } catch (Throwable e) {
            LOGGER.debug("检查基岩版玩家状态失败 {}: {}", playerName, e.getMessage());
        }
        return false;
    }

    public static int getBedrockPort() {
        if (!isGeyserLoaded()) return -1;
        try {
            var geyserApiClass = Class.forName("org.geysermc.geyser.api.GeyserApi");
            var api = geyserApiClass.getMethod("api").invoke(null);
            if (api != null) {
                var listener = api.getClass().getMethod("bedrockListener").invoke(api);
                if (listener != null) {
                    var port = (int) listener.getClass().getMethod("port").invoke(listener);
                    if (port > 0) {
                        bedrockPort = port;
                        return port;
                    }
                }
            }
        } catch (Throwable e) {
            LOGGER.debug("GeyserApi.bedrockListener() 失败，试GeyserImpl: {}", e.getMessage());
        }
        try {
            var geyserImpl = Class.forName("org.geysermc.geyser.GeyserImpl")
                    .getMethod("getInstance").invoke(null);
            if (geyserImpl == null) return DEFAULT_BEDROCK_PORT;
            var config = geyserImpl.getClass().getMethod("config").invoke(geyserImpl);
            var bedrockConfig = config.getClass().getMethod("bedrock").invoke(config);
            var port = (int) bedrockConfig.getClass().getMethod("port").invoke(bedrockConfig);
            bedrockPort = port > 0 ? port : DEFAULT_BEDROCK_PORT;
            return bedrockPort;
        } catch (Throwable e) {
            LOGGER.warn("读Geyser基岩端口失败，用默认{}: {}", DEFAULT_BEDROCK_PORT, e.getMessage());
            return DEFAULT_BEDROCK_PORT;
        }
    }

    public static void setBedrockPort(int port) {
        bedrockPort = port;
    }

    public static GeyserInfo getGeyserInfo() {
        if (!isGeyserLoaded()) return null;
        return new GeyserInfo(getBedrockPort());
    }

    public record GeyserInfo(int bedrockPort) {}
}
