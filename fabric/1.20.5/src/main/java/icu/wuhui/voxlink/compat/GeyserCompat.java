package icu.wuhui.voxlink.compat;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GeyserCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger("voxlink-geyser");
    private static final int DEFAULT_BEDROCK_PORT = 19132;
    private static volatile Integer bedrockPort = null;

    private GeyserCompat() {}

    public static boolean isGeyserLoaded() {
        return FabricLoader.getInstance().isModLoaded("geyser-fabric")
                || FabricLoader.getInstance().isModLoaded("geyser");
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
}
