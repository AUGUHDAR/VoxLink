package icu.wuhui.voxlink.network;

import icu.wuhui.voxlink.VoxLinkMod;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class GlobalIPs {
    private GlobalIPs() {}

    private static final String[] IPV4_ENDPOINTS = {
            "https://api-ipv4.ip.sb/ip",
            "https://4.ipw.cn",
            "https://ipv4.icanhazip.com",
            "https://checkip.amazonaws.com"
    };

    private static final String[] IPV6_ENDPOINTS = {
            "https://api-ipv6.ip.sb/ip",
            "https://6.ipw.cn",
            "https://ipv6.icanhazip.com"
    };

    private static final int TIMEOUT_MS = 5000;
    private static final ExecutorService IP_FETCH_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "VoxLink-GlobalIP");
        t.setDaemon(true);
        return t;
    });

    public static CompletableFuture<String> fetchIPv4() {
        CompletableFuture<String> result = new CompletableFuture<>();
        tryFetchEndpoints(IPV4_ENDPOINTS, 0, result, "IPv4");
        return result.orTimeout(15, TimeUnit.SECONDS)
                .exceptionally(e -> null);
    }

    public static CompletableFuture<String> fetchIPv6() {
        CompletableFuture<String> result = new CompletableFuture<>();
        tryFetchEndpoints(IPV6_ENDPOINTS, 0, result, "IPv6");
        return result.orTimeout(15, TimeUnit.SECONDS)
                .exceptionally(e -> null);
    }

    private static void tryFetchEndpoints(String[] endpoints, int index,
                                          CompletableFuture<String> result, String label) {
        if (index >= endpoints.length) {
            VoxLinkMod.LOGGER.debug("[GlobalIP] {}端点全挂了，没拿到地址", label);
            result.completeExceptionally(new RuntimeException("No " + label + " address found"));
            return;
        }

        CompletableFuture.runAsync(() -> {
            String ip = fetchFromUrl(endpoints[index]);
            if (ip != null && isValidIp(ip, label)) {
                VoxLinkMod.LOGGER.info("[GlobalIP] {} address from {}: {}", label, endpoints[index], ip);
                result.complete(ip);
            } else {
                VoxLinkMod.LOGGER.debug("[GlobalIP] {}端点{}失败，试下一个", label, endpoints[index]);
                tryFetchEndpoints(endpoints, index + 1, result, label);
            }
        }, IP_FETCH_EXECUTOR);
    }

    private static String fetchFromUrl(String urlStr) {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(urlStr).toURL().openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "VoxLink/" + VoxLinkMod.MOD_VERSION);
            conn.setInstanceFollowRedirects(true);

            if (conn.getResponseCode() != 200) return null;

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String line = reader.readLine();
                return line != null ? line.trim() : null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isValidIp(String ip, String label) {
        if (ip == null || ip.isEmpty()) return false;
        try {
            InetAddress addr = InetAddress.getByName(ip);
            if ("IPv4".equals(label)) {
                return addr.getAddress().length == 4;
            } else {
                return addr.getAddress().length == 16;
            }
        } catch (Exception e) {
            return false;
        }
    }
}
