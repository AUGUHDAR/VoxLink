package icu.wuhui.voxlink.compat;

import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GeyserCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger("voxlink-geyser");
    private static final int DEFAULT_BEDROCK_PORT = 19132;
    private static volatile Integer bedrockPort = null;
    //debounce isGeyserLoaded缓存 避免每次调用都走ModList
    private static volatile Boolean cachedLoaded = null;

    private GeyserCompat() {}

    public static boolean isGeyserLoaded() {
        Boolean cached = cachedLoaded;
        if (cached != null) return cached;
        boolean loaded = ModList.get().isLoaded("geyser-fabric")
                || ModList.get().isLoaded("geyser");
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
            var api = invokeAccessible(geyserApiClass, null, "api");
            if (api != null) {
                var listener = invokeAccessible(api.getClass(), api, "bedrockListener");
                if (listener != null) {
                    var port = (int) invokeAccessible(listener.getClass(), listener, "port");
                    if (port > 0) {
                        bedrockPort = port;
                        return port;
                    }
                }
            }
        } catch (Throwable e) {
            LOGGER.debug("GeyserApi.bedrockListener() failed, trying GeyserImpl: {}", e.getMessage());
        }
        try {
            var impl = Class.forName("org.geysermc.geyser.GeyserImpl");
            var geyserImpl = invokeAccessible(impl, null, "getInstance");
            if (geyserImpl == null) return DEFAULT_BEDROCK_PORT;
            var config = invokeAccessible(geyserImpl.getClass(), geyserImpl, "config");
            var bedrockConfig = invokeAccessible(config.getClass(), config, "bedrock");
            var port = (int) invokeAccessible(bedrockConfig.getClass(), bedrockConfig, "port");
            bedrockPort = port > 0 ? port : DEFAULT_BEDROCK_PORT;
            return bedrockPort;
        } catch (Throwable e) {
            //debounce 反射失败打印堆栈 便于排查Geyser API变化
            LOGGER.warn("Failed to read Geyser bedrock port, using default {}: {}", DEFAULT_BEDROCK_PORT, e.toString(), e);
            return DEFAULT_BEDROCK_PORT;
        }
    }

    /** 反射调无参方法：先 trySetAccessible 再 invoke，避免 IllegalAccessException */
    private static Object invokeAccessible(Class<?> owner, Object target, String name) throws Exception {
        java.lang.reflect.Method m = owner.getMethod(name);
        m.trySetAccessible();
        return m.invoke(target);
    }
}
