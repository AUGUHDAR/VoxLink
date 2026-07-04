package icu.wuhui.voxlink.network;

// APP端空壳：无UPnP，方法返回失败
public class UPnPManager {

    public record UPnPResult(boolean available, boolean success, int externalPort) {}

    public static UPnPResult openPort(int port, String description) {
        return new UPnPResult(false, false, 0);
    }

    public static UPnPResult openUdpPort(int port, String description) {
        return new UPnPResult(false, false, 0);
    }

    public static UPnPResult openPort(int port, String description, String protocol) {
        return new UPnPResult(false, false, 0);
    }

    public static void closePort(int port) {
        // APP端无UPnP
    }

    public static void closeUdpPort(int port) {
        // APP端无UPnP
    }

    public static void closePort(int port, String protocol) {
        // APP端无UPnP
    }

    public static String getExternalIp() {
        return null;
    }

    public static void invalidateCache() {
        // APP端无UPnP
    }

    public record GatewayInfo(String baseUrl, String controlUrl, String serviceType) {}
}
