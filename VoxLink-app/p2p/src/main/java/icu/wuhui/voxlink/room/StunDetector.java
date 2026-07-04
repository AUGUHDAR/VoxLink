package icu.wuhui.voxlink.room;

import icu.wuhui.voxlink.app.AppContext;
import icu.wuhui.voxlink.network.ConnectionFallback;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.List;

public final class StunDetector {
    private StunDetector() {}

    //国内外混合12个, 兼容国外玩家(国内不通的国外可能通, 竞速下自动忽略超时的)
    private static final List<List<String>> STUN_SERVER_GROUPS = List.of(
            List.of("stun:stun.miwifi.com"),
            List.of("stun:stun.hitv.com"),
            List.of("stun:stun.chat.bilibili.com"),
            List.of("stun:stun.l.google.com:19302"),
            List.of("stun:stun3.l.google.com:19302"),
            List.of("stun:stun1.l.google.com:19302"),
            List.of("stun:stun.nextcloud.com"),
            List.of("stun:stun.nfon.net"),
            List.of("stun:stun.freeswitch.org"),
            List.of("stun:stun.syncthing.net"),
            List.of("stun:stun.ekiga.net"),
            List.of("stun:stun.sipnet.com")
    );

    public static List<String> getStunGroup(int index) {
        return STUN_SERVER_GROUPS.get(index % STUN_SERVER_GROUPS.size());
    }

    public static int getStunGroupCount() {
        return STUN_SERVER_GROUPS.size();
    }

    public static List<String> getAllStunUrls() {
        return STUN_SERVER_GROUPS.stream().flatMap(java.util.Collection::stream).toList();
    }

    public static List<List<String>> getStunServerGroups() {
        return STUN_SERVER_GROUPS;
    }

    public static boolean isNatTypeSymmetric(String natType) {
        return "symmetric".equals(natType) || "symmetric_easy_inc".equals(natType) || "symmetric_easy_dec".equals(natType);
    }

    // EasySym (端口可预测)：symmetric_easy_inc/dec。与 isNatTypeSymmetric 对齐，供host侧读String NAT类型用
    public static boolean isEasySymmetric(String natType) {
        return "symmetric_easy_inc".equals(natType) || "symmetric_easy_dec".equals(natType);
    }

    // HardSym (端口不可预测)：plain symmetric
    public static boolean isHardSymmetric(String natType) {
        return "symmetric".equals(natType);
    }

    //缓存localIp
    private static volatile String cachedLocalIp;
    private static volatile long localIpCacheTime;
    private static final long LOCAL_IP_CACHE_MS = 30_000;

    public static String getLocalIpAddress() {
        long now = System.currentTimeMillis();
        if (cachedLocalIp != null && now - localIpCacheTime < LOCAL_IP_CACHE_MS) {
            return cachedLocalIp;
        }
        String ip = probeLocalIpAddress();
        if (ip != null) {
            cachedLocalIp = ip;
            localIpCacheTime = now;
        }
        return ip;
    }

    private static String probeLocalIpAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback() || ni.isVirtual() || !ni.isUp()) continue;
                Enumeration<InetAddress> addrEnum = ni.getInetAddresses();
                while (addrEnum.hasMoreElements()) {
                    InetAddress addr = addrEnum.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        String ip = addr.getHostAddress();
                        if (!ip.startsWith("127.")) {
                            return ip;
                        }
                    }
                }
            }
        } catch (Exception e) {
            AppContext.LOGGER.warn("[StunDetector] 获取本地IP失败: {}", e.getMessage());
        }
        return null;
    }

    //缓存IPv6可达性
    private static volatile Boolean cachedIpv6Reachable;
    private static volatile long ipv6CacheTime;
    private static final long IPV6_CACHE_MS = 60_000;

    public static boolean verifyIPv6Connectivity() {
        long now = System.currentTimeMillis();
        if (cachedIpv6Reachable != null && now - ipv6CacheTime < IPV6_CACHE_MS) {
            return cachedIpv6Reachable;
        }
        boolean result = ConnectionFallback.verifyIPv6Connectivity();
        cachedIpv6Reachable = result;
        ipv6CacheTime = now;
        return result;
    }

    public static boolean isSameLan(String hostLocalIp) {
        String myLocalIp = getLocalIpAddress();
        if (myLocalIp == null || hostLocalIp == null) return false;
        try {
            String[] myParts = myLocalIp.split("\\.");
            String[] hostParts = hostLocalIp.split("\\.");
            if (myParts.length == 4 && hostParts.length == 4) {
                boolean same = myParts[0].equals(hostParts[0]) && myParts[1].equals(hostParts[1]) && myParts[2].equals(hostParts[2]);
                if (same) {
                    AppContext.LOGGER.info("[StunDetector] 内网检测: myIp={} hostIp={} 同一内网", myLocalIp, hostLocalIp);
                }
                return same;
            }
        } catch (Exception e) {
            AppContext.LOGGER.warn("[StunDetector] 内网检测失败: {}", e.getMessage());
        }
        return false;
    }

    public static void invalidateCache() {
        cachedLocalIp = null;
        cachedIpv6Reachable = null;
    }
}
