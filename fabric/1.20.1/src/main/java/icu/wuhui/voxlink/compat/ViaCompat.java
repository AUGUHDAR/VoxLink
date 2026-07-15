package icu.wuhui.voxlink.compat;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ViaCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger("voxlink-via");

    private ViaCompat() {}

    public static boolean isViaLoaded() {
        return FabricLoader.getInstance().isModLoaded("viaversion");
    }

    public static boolean isViaFabricLoaded() {
        return FabricLoader.getInstance().isModLoaded("viafabric");
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
                    var last = list.get(list.size() - 1);
                    var version = last.getClass().getMethod("getVersion").invoke(last);
                    if (version instanceof Integer) return (Integer) version;
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
            LOGGER.warn("获取ViaVersion服务端协议失败: {}", e.getMessage());
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
            LOGGER.debug("获取玩家协议版本失败: {}", e.getMessage());
            return 0;
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
            LOGGER.debug("AddressParser失败，用直连后缀: {}", e.getMessage());
        }
        return host + "._v" + targetProtocol + ".viafabric:" + port;
    }
}
