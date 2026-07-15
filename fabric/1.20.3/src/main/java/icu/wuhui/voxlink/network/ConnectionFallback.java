package icu.wuhui.voxlink.network;

import net.minecraft.network.chat.Component;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Inet6Address;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.util.Enumeration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class ConnectionFallback {
    private static final Logger LOGGER = LoggerFactory.getLogger("voxlink-fallback");
    private static final ExecutorService FALLBACK_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "VoxLink-Fallback");
        t.setDaemon(true);
        return t;
    });
    private static final int SOCKET_TIMEOUT = 3000;
    private static final int SOCKET_CONNECT_TIMEOUT_MS = 2000;
    //对齐EasyTier: 10s窗口, 最多5次, 每次3s超时, 10-100ms退避
    private static final int TCP_SIMOPEN_WINDOW_MS = 10000;
    private static final int TCP_SIMOPEN_MAX_ATTEMPTS = 5;

    public static void shutdown() {
        FALLBACK_EXECUTOR.shutdown();
        try {
            if (!FALLBACK_EXECUTOR.awaitTermination(2, TimeUnit.SECONDS)) {
                FALLBACK_EXECUTOR.shutdownNow();
            }
        } catch (InterruptedException e) {
            FALLBACK_EXECUTOR.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public enum ConnectionMode {
        IPV6_DIRECT("voxlink.mode.ipv6_direct"),
        IPV4_DIRECT("voxlink.mode.ipv4_direct"),
        UDP_PUNCH("voxlink.mode.udp_punch");

        public final String translationKey;
        ConnectionMode(String translationKey) { this.translationKey = translationKey; }
    }

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean settled = new AtomicBoolean(false);
    private volatile Component statusText = Component.empty();

    public void cancel() { cancelled.set(true); }
    public Component getStatusText() { return statusText; }
    public boolean isSettled() { return settled.get(); }
    public boolean isCancelled() { return cancelled.get(); }

    public CompletableFuture<ConnectResult> tryIpv6Direct(String hostIpv6, int hostPort) {
        if (hostIpv6 == null || hostIpv6.isEmpty()) {
            return CompletableFuture.completedFuture(ConnectResult.failed("NO_IPV6", "没有IPv6地址"));
        }

        statusText = Component.translatable("voxlink.connection.connecting");

        return CompletableFuture.supplyAsync(() -> {
            if (cancelled.get() || settled.get()) return ConnectResult.cancelled();

            Socket socket = null;
            try {
                socket = new Socket();
                socket.setTcpNoDelay(true);
                InetAddress addr = InetAddress.getByName(hostIpv6);
                socket.connect(new InetSocketAddress(addr, hostPort), SOCKET_TIMEOUT);
                socket.close();
                socket = null;
                LOGGER.info("IPv6连上了: [{}]:{}", hostIpv6, hostPort);
                statusText = Component.translatable("voxlink.connection.connecting");
                settled.set(true);
                cancelled.set(true);
                return ConnectResult.success("IPv6", hostIpv6, hostPort, ConnectionMode.IPV6_DIRECT);
            } catch (java.net.SocketTimeoutException e) {
                LOGGER.info("IPv6 timeout: [{}]:{}", hostIpv6, hostPort);
                statusText = Component.translatable("voxlink.connection.connecting");
                return ConnectResult.failed("IPV6_TIMEOUT", "IPv6 timeout");
            } catch (java.net.NoRouteToHostException e) {
                LOGGER.info("IPv6没路由: [{}]:{}", hostIpv6, hostPort);
                statusText = Component.translatable("voxlink.connection.connecting");
                return ConnectResult.failed("IPV6_NO_ROUTE", "IPv6 no route");
            } catch (java.net.ConnectException e) {
                String msg = e.getMessage();
                LOGGER.info("IPv6连不上: [{}]:{} - {}", hostIpv6, hostPort, msg);
                if (msg != null && msg.contains("Connection refused")) {
                    statusText = Component.translatable("voxlink.connection.connecting");
                    return ConnectResult.failed("IPV6_REFUSED", "IPv6 refused");
                }
                statusText = Component.translatable("voxlink.connection.connecting");
                return ConnectResult.failed("IPV6_ERROR", "IPv6 failed");
            } catch (IOException e) {
                LOGGER.info("IPv6异常: [{}]:{} - {}", hostIpv6, hostPort, e.getMessage());
                statusText = Component.translatable("voxlink.connection.connecting");
                return ConnectResult.failed("IPV6_EXCEPTION", "IPv6 exception: " + e.getMessage());
            } finally {
                if (socket != null) try { socket.close(); } catch (Exception ignored) {}
            }
        }, FALLBACK_EXECUTOR);
    }

    public CompletableFuture<ConnectResult> tryIpv4Direct(String hostIp, int hostPort) {
        if (hostIp == null || hostIp.isEmpty()) {
            return CompletableFuture.completedFuture(ConnectResult.failed("NO_IPV4", "没有IPv4地址"));
        }

        statusText = Component.translatable("voxlink.connection.connecting");

        return CompletableFuture.supplyAsync(() -> {
            if (cancelled.get() || settled.get()) return ConnectResult.cancelled();

            Socket socket = null;
            try {
                socket = new Socket();
                socket.setTcpNoDelay(true);
                InetAddress addr = InetAddress.getByName(hostIp);
                socket.connect(new InetSocketAddress(addr, hostPort), SOCKET_TIMEOUT);
                socket.close();
                socket = null;
                LOGGER.info("IPv4连上了: {}:{}", hostIp, hostPort);
                statusText = Component.translatable("voxlink.connection.connecting");
                settled.set(true);
                cancelled.set(true);
                return ConnectResult.success("IPv4", hostIp, hostPort, ConnectionMode.IPV4_DIRECT);
            } catch (java.net.SocketTimeoutException e) {
                LOGGER.info("IPv4超时: {}:{}", hostIp, hostPort);
                statusText = Component.translatable("voxlink.connection.connecting");
                return ConnectResult.failed("IPV4_TIMEOUT", "IPv4 timeout");
            } catch (java.net.NoRouteToHostException e) {
                LOGGER.info("IPv4没路由: {}:{}", hostIp, hostPort);
                statusText = Component.translatable("voxlink.connection.connecting");
                return ConnectResult.failed("IPV4_NO_ROUTE", "IPv4 no route");
            } catch (java.net.ConnectException e) {
                String msg = e.getMessage();
                LOGGER.info("IPv4连不上: {}:{} - {}", hostIp, hostPort, msg);
                if (msg != null && msg.contains("Connection refused")) {
                    statusText = Component.translatable("voxlink.connection.connecting");
                    return ConnectResult.failed("IPV4_REFUSED", "IPv4 refused");
                }
                statusText = Component.translatable("voxlink.connection.connecting");
                return ConnectResult.failed("IPV4_ERROR", "IPv4 failed");
            } catch (IOException e) {
                LOGGER.info("IPv4异常: {}:{} - {}", hostIp, hostPort, e.getMessage());
                statusText = Component.translatable("voxlink.connection.connecting");
                return ConnectResult.failed("IPV4_EXCEPTION", "IPv4 exception: " + e.getMessage());
            } finally {
                if (socket != null) try { socket.close(); } catch (Exception ignored) {}
            }
        }, FALLBACK_EXECUTOR);
    }

    private static volatile Boolean ipv6ConnectivityCached = null;
    private static volatile long ipv6ConnectivityCheckTime = 0;
    private static final long IPV6_CHECK_CACHE_MS = 60_000; // 缓存60秒

    /**
     * 实际验证IPv6连通性：尝试TCP连接到已知IPv6服务器
     * 结果缓存60秒，避免重复检查
     */
    public static boolean verifyIPv6Connectivity() {
        long now = System.currentTimeMillis();
        if (ipv6ConnectivityCached != null && (now - ipv6ConnectivityCheckTime) < IPV6_CHECK_CACHE_MS) {
            return ipv6ConnectivityCached;
        }
        if (!hasIPv6Connectivity()) {
            ipv6ConnectivityCached = false;
            ipv6ConnectivityCheckTime = now;
            return false;
        }
        // 尝试连接Google DNS IPv6 (2001:4860:4860::8888) 的53端口
        String[] testTargets = {"2001:4860:4860::8888", "2001:4860:4860::8844"};
        for (String target : testTargets) {
            try {
                java.net.Socket sock = new java.net.Socket();
                sock.connect(new java.net.InetSocketAddress(target, 53), SOCKET_CONNECT_TIMEOUT_MS);
                sock.close();
                LOGGER.info("[IPv6] 连通性验证成功: {}", target);
                ipv6ConnectivityCached = true;
                ipv6ConnectivityCheckTime = now;
                return true;
            } catch (Exception e) {
                LOGGER.debug("[IPv6] 连通性验证失败 {}: {}", target, e.getMessage());
            }
        }
        LOGGER.info("[IPv6] 连通性验证失败：所有目标不可达");
        ipv6ConnectivityCached = false;
        ipv6ConnectivityCheckTime = now;
        return false;
    }

    /**
     * 清除IPv6连通性缓存（网络变化时调用）
     */
    public static void resetIPv6Cache() {
        ipv6ConnectivityCached = null;
        ipv6ConnectivityCheckTime = 0;
    }

    public static boolean hasIPv6Connectivity() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet6Address inet6 && !addr.isLoopbackAddress() && !addr.isLinkLocalAddress()) {
                        String ip = addr.getHostAddress();
                        int scopeIdx = ip.indexOf('%');
                        if (scopeIdx >= 0) ip = ip.substring(0, scopeIdx);
                        LOGGER.info("[IPv6] Found IPv6 addr: {} on interface {} (loopback={}, linkLocal={}, siteLocal={}, ULA={})",
                                ip, ni.getName(), addr.isLoopbackAddress(), addr.isLinkLocalAddress(),
                                addr.isSiteLocalAddress(), ip.startsWith("fd") || ip.startsWith("fc"));
                        if (!ip.startsWith("fd") && !ip.startsWith("fc")) {
                            return true;
                        }
                    }
                }
            }
            LOGGER.info("[IPv6] No usable global IPv6 address found");
        } catch (Exception e) {
            LOGGER.warn("[IPv6] Error checking IPv6: {}", e.getMessage());
        }
        return false;
    }

    public static String getLocalGlobalIpv6() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet6Address && !addr.isLoopbackAddress() && !addr.isLinkLocalAddress()) {
                        String ipv6 = addr.getHostAddress();
                        int scopeIdx = ipv6.indexOf('%');
                        if (scopeIdx >= 0) ipv6 = ipv6.substring(0, scopeIdx);
                        if (!ipv6.startsWith("fd") && !ipv6.startsWith("fc")) {
                            return ipv6;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    public CompletableFuture<ConnectResult> tryTcpSimultaneousOpen(String remoteIp, int remotePort, int localPort) {
        if (remoteIp == null || remoteIp.isEmpty()) {
            return CompletableFuture.completedFuture(ConnectResult.failed("NO_IP", "No remote IP"));
        }

        statusText = Component.translatable("voxlink.connection.connecting");

        return CompletableFuture.supplyAsync(() -> {
            if (cancelled.get() || settled.get()) return ConnectResult.cancelled();

            InetAddress addr;
            try {
                addr = InetAddress.getByName(remoteIp);
            } catch (Exception e) {
                return ConnectResult.failed("TCP_SIMOPEN_FAILED", "Bad addr: " + e.getMessage());
            }

            long deadline = System.currentTimeMillis() + TCP_SIMOPEN_WINDOW_MS;
            int attempts = 0;
            java.util.Random rng = new java.util.Random();

            //对齐EasyTier try_connect_to_remote: 10s窗口内最多5次, 每次3s超时, 10-100ms退避
            while (System.currentTimeMillis() < deadline
                    && attempts < TCP_SIMOPEN_MAX_ATTEMPTS
                    && !cancelled.get() && !settled.get()) {
                attempts++;
                Socket clientSocket = null;
                try {
                    clientSocket = new Socket();
                    clientSocket.setTcpNoDelay(true);
                    clientSocket.setReuseAddress(true);
                    try {
                        clientSocket.bind(new InetSocketAddress(localPort));
                    } catch (IOException bindEx) {
                        LOGGER.info("TCP SimOpen: 本地端口{}被占了，用随机端口", localPort);
                    }
                    LOGGER.info("TCP SimOpen: 第{}/{}次 从端口{}连到{}:{}",
                            attempts, TCP_SIMOPEN_MAX_ATTEMPTS, clientSocket.getLocalPort(), remoteIp, remotePort);
                    clientSocket.connect(new InetSocketAddress(addr, remotePort), SOCKET_TIMEOUT);
                    LOGGER.info("TCP SimOpen: 第{}次连上了 {}:{}", attempts, remoteIp, remotePort);
                    statusText = Component.translatable("voxlink.connection.connecting");
                    settled.set(true);
                    cancelled.set(true);
                    clientSocket.close();
                    return ConnectResult.success("TCP-SimOpen", remoteIp, remotePort, ConnectionMode.IPV4_DIRECT);
                } catch (Exception e) {
                    LOGGER.info("TCP SimOpen第{}次失败: {}:{} - {}", attempts, remoteIp, remotePort, e.getMessage());
                    if (clientSocket != null) try { clientSocket.close(); } catch (Exception ignored) {}
                    if (cancelled.get() || settled.get()) break;
                    if (System.currentTimeMillis() >= deadline || attempts >= TCP_SIMOPEN_MAX_ATTEMPTS) break;
                    try {
                        Thread.sleep(10 + rng.nextInt(90));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            statusText = Component.translatable("voxlink.connection.connecting");
            return ConnectResult.failed("TCP_SIMOPEN_FAILED", "TCP SimOpen failed after " + attempts + " attempts");
        }, FALLBACK_EXECUTOR);
    }

    public static class ConnectResult {
        public final boolean success;
        public final String failureReason;
        public final String errorCode;
        public final String label;
        public final String remoteHost;
        public final int remotePort;
        public final ConnectionMode mode;

        private ConnectResult(boolean success, String errorCode, String failureReason, String label, String remoteHost, int remotePort, ConnectionMode mode) {
            this.success = success; this.errorCode = errorCode; this.failureReason = failureReason;
            this.label = label; this.remoteHost = remoteHost; this.remotePort = remotePort; this.mode = mode;
        }
        public static ConnectResult success(String label, String remoteHost, int remotePort, ConnectionMode mode) {
            return new ConnectResult(true, null, null, label, remoteHost, remotePort, mode);
        }
        public static ConnectResult failed(String errorCode, String reason) {
            return new ConnectResult(false, errorCode, reason, null, null, 0, null);
        }
        public static ConnectResult cancelled() {
            return new ConnectResult(false, "CANCELLED", "Connection cancelled", null, null, 0, null);
        }
    }
}
