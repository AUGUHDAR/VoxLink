package icu.wuhui.voxlink.compat;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GeyserCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger("voxlink-geyser");
    private static final int DEFAULT_BEDROCK_PORT = 19132;
    private static volatile Integer bedrockPort = null;
    //debounce isGeyserLoaded缓存 避免每次调用都走FabricLoader
    private static volatile Boolean cachedLoaded = null;

    private GeyserCompat() {}

    public static boolean isGeyserLoaded() {
        Boolean cached = cachedLoaded;
        if (cached != null) return cached;
        boolean loaded = FabricLoader.getInstance().isModLoaded("geyser-fabric")
                || FabricLoader.getInstance().isModLoaded("geyser");
        cachedLoaded = loaded;
        return loaded;
    }

    public static int getBedrockPort() {
        //debounce 缓存命中直接返回 避免重复反射调用
        Integer cached = bedrockPort;
        if (cached != null) return cached;
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
            //debounce 反射失败打印堆栈 便于排查Geyser API变化
            LOGGER.warn("读Geyser基岩端口失败，用默认{}: {}", DEFAULT_BEDROCK_PORT, e.toString(), e);
            return DEFAULT_BEDROCK_PORT;
        }
    }
}
