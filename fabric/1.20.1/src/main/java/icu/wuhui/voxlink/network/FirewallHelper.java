package icu.wuhui.voxlink.network;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// 纯出站，无需防火墙
public class FirewallHelper {
    private static final Logger LOGGER = LoggerFactory.getLogger("voxlink-firewall");

    public static FirewallResult tryOpenFirewall(int port) {
        LOGGER.debug("防火墙操作已禁用，纯出站模式不需要");
        return new FirewallResult(false, "disabled");
    }

    public static FirewallResult tryOpenUdpFirewall(int port) {
        LOGGER.debug("防火墙操作已禁用，纯出站模式不需要");
        return new FirewallResult(false, "disabled");
    }

    public static FirewallResult tryOpenFirewall(int port, String protocol) {
        LOGGER.debug("防火墙操作已禁用，纯出站模式不需要");
        return new FirewallResult(false, "disabled");
    }

    public static void closeFirewall(int port) {}

    public static void closeUdpFirewall(int port) {}

    public static void closeFirewall(int port, String protocol) {}

    public record FirewallResult(boolean success, String errorMessage) {}
}
