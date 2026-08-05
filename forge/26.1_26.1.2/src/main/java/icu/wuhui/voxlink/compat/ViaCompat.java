package icu.wuhui.voxlink.compat;

import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ViaCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger("voxlink-via");

    private ViaCompat() {}

    public static boolean isViaLoaded() {
        return ModList.isLoaded("viaversion");
    }

    public static boolean isViaFabricLoaded() {
        return ModList.isLoaded("viafabric");
    }

    public static int getServerProtocolVersion() {
        if (!isViaLoaded()) return 0;
        try {
            var viaClass = Class.forName("com.viaversion.viaversion.api.Via");
            Object api;
            try {
                var managerMethod = viaClass.getMethod("getManager");
                api = managerMethod.invoke(null);
                var protoManager = api.getClass().getMethod("getProtocolManager").invoke(api);
                var supported = protoManager.getClass().getMethod("getSupportedProtocolVersions").invoke(protoManager);
                if (supported instanceof java.util.List<?> list && !list.isEmpty()) {
                    //debounce List不保证按版本号排序 取最大值而非最后一个
                    int maxVersion = -1;
                    for (var v : list) {
                        var version = v.getClass().getMethod("getVersion").invoke(v);
                        if (version instanceof Integer vi && vi > maxVersion) maxVersion = vi;
                    }
                    if (maxVersion > 0) return maxVersion;
                }
                return 0;
            } catch (NoSuchMethodException e) {
                api = viaClass.getMethod("getAPI").invoke(null);
                var serverVersion = api.getClass().getMethod("getServerVersion").invoke(api);
                var highest = serverVersion.getClass().getMethod("highestSupportedProtocolVersion").invoke(serverVersion);
                var version = highest.getClass().getMethod("getVersion").invoke(highest);
                if (version instanceof Integer) return (Integer) version;
                return 0;
            }
        } catch (Throwable e) {
            LOGGER.warn("Failed to get ViaVersion server protocol: {}", e.getMessage());
            return 0;
        }
    }

    public static int getPlayerProtocolVersion(java.util.UUID uuid) {
        if (!isViaLoaded()) return 0;
        try {
            var viaClass = Class.forName("com.viaversion.viaversion.api.Via");
            Object api;
            try {
                var managerMethod = viaClass.getMethod("getManager");
                api = managerMethod.invoke(null);
                var protoManager = api.getClass().getMethod("getProtocolManager").invoke(api);
                var protoVersion = protoManager.getClass().getMethod("getProtocolVersion", java.util.UUID.class).invoke(protoManager, uuid);
                var version = protoVersion.getClass().getMethod("getVersion").invoke(protoVersion);
                if (version instanceof Integer) return (Integer) version;
                return 0;
            } catch (NoSuchMethodException e) {
                api = viaClass.getMethod("getAPI").invoke(null);
                var protoVersion = api.getClass().getMethod("getPlayerProtocolVersion", java.util.UUID.class).invoke(api, uuid);
                var version = protoVersion.getClass().getMethod("getVersion").invoke(protoVersion);
                if (version instanceof Integer) return (Integer) version;
                return 0;
            }
        } catch (Throwable e) {
            LOGGER.debug("Failed to get player protocol version: {}", e.getMessage());
            //debounce 反射失败返回-1 与"未加载"的0区分 调用方可针对性处理
            return -1;
        }
    }

    public static String buildViaAddress(String host, int port, int targetProtocol) {
        if (!isViaFabricLoaded() || targetProtocol <= 0) {
            return host + ":" + port;
        }
        try {
            Class<?> parserClass;
            try {
                parserClass = Class.forName("com.viaversion.fabric.common.util.AddressParser");
            } catch (ClassNotFoundException ex) {
                parserClass = Class.forName("com.viaversion.fabric.common.AddressParser");
            }
            var parsed = parserClass.getMethod("parse", String.class).invoke(null, host + ":" + port);
            var suffix = parsed.getClass().getMethod("getSuffixWithOptions").invoke(parsed);
            if (suffix instanceof String && !((String) suffix).isEmpty()) {
                return host + "." + suffix + ":" + port;
            }
        } catch (Throwable e) {
            LOGGER.debug("AddressParser failed, using direct suffix: {}", e.getMessage());
        }
        return host + "._v" + targetProtocol + ".viafabric:" + port;
    }
}
