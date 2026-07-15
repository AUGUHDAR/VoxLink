package icu.wuhui.voxlink.network;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

//地址黑名单: UDP连续失败3次拉黑1h, 直连失败拉黑5min
public final class AddressBlacklist {
    private static final long UDP_BLACKLIST_MS = 3600_000L; //1h
    private static final long DIRECT_BLACKLIST_MS = 300_000L; //5min
    private static final int UDP_FAIL_THRESHOLD = 3;

    private final Map<InetSocketAddress, Long> expireAt = new ConcurrentHashMap<>();
    private final Map<InetSocketAddress, AtomicInteger> udpFailCount = new ConcurrentHashMap<>();

    public boolean isBlacklisted(InetSocketAddress addr) {
        if (addr == null) return false;
        Long exp = expireAt.get(addr);
        if (exp == null) return false;
        if (System.currentTimeMillis() >= exp) {
            expireAt.remove(addr, exp);
            return false;
        }
        return true;
    }

    //连续3次才拉黑1h
    public void recordUdpFailure(InetSocketAddress addr) {
        if (addr == null) return;
        AtomicInteger c = udpFailCount.computeIfAbsent(addr, k -> new AtomicInteger(0));
        int n = c.incrementAndGet();
        if (n >= UDP_FAIL_THRESHOLD) {
            expireAt.put(addr, System.currentTimeMillis() + UDP_BLACKLIST_MS);
            udpFailCount.remove(addr);
        }
    }

    //直接拉黑5min
    public void recordDirectFailure(InetSocketAddress addr) {
        if (addr == null) return;
        expireAt.put(addr, System.currentTimeMillis() + DIRECT_BLACKLIST_MS);
        udpFailCount.remove(addr);
    }

    public void clear() {
        expireAt.clear();
        udpFailCount.clear();
    }
}
