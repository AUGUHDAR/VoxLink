package icu.wuhui.voxlink.network;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class P2PBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger("voxlink-p2p");
    private static final int BUFFER_SIZE = 32768;
    private static final int CONNECT_TIMEOUT = 10000;
    private static final int MAX_RETRY = 3;

    private static final AtomicBoolean running = new AtomicBoolean(false);
    private static final AtomicBoolean cancelled = new AtomicBoolean(false);
    private static volatile ExecutorService bridgeExecutor;
    private static volatile ServerSocket hostServer;
    private static volatile ServerSocket joinerServer = null;
    private static volatile int joinerPort = -1;
    private static volatile int hostPort = -1;
    private static final List<BridgePair> activePairs = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<ScheduledFuture<?>> pendingUdpTimeouts = new CopyOnWriteArrayList<>();

    private static volatile String currentHostIp;
    private static volatile int currentHostPort;

    public static synchronized CompletableFuture<Integer> startHostBridge(int minecraftPort) {
        cancelled.set(false);
        tcpJoinerBridgeConnectedV4.set(false);
        tcpJoinerBridgeConnectedV6.set(false);
        if (running.get()) {
            ServerSocket hs = hostServer;
            if (hs != null && !hs.isClosed()) {
                return CompletableFuture.completedFuture(hs.getLocalPort());
            }
            disconnect();
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                ServerSocket ss = new ServerSocket(0, 50, InetAddress.getByName("0.0.0.0"));
                int bridgePort = ss.getLocalPort();
                LOGGER.info("Host bridge listening on port {}, forwarding to MC port {}", bridgePort, minecraftPort);
                ExecutorService exec = getOrCreateExecutor();
                exec.submit(() -> acceptHostConnections(ss, minecraftPort));
                synchronized (P2PBridge.class) {
                    running.set(true);
                    hostServer = ss;
                    hostPort = bridgePort;
                }
                return bridgePort;
            } catch (IOException e) {
                LOGGER.error("Failed to start host bridge: {}", e.getMessage());
                running.set(false);
                return -1;
            }
        }, getOrCreateExecutor());
    }

    private static void acceptHostConnections(ServerSocket ss, int minecraftPort) {
        try {
            ss.setSoTimeout(1000);
        } catch (IOException ignored) {}
        while (running.get()) {
            Socket clientSocket = null;
            try {
                try {
                    clientSocket = ss.accept();
                } catch (java.net.SocketTimeoutException e) {
                    continue;
                }
                clientSocket.setTcpNoDelay(true);
                clientSocket.setSendBufferSize(BUFFER_SIZE);
                clientSocket.setReceiveBufferSize(BUFFER_SIZE);
                LOGGER.info("P2P client connected from {}", clientSocket.getRemoteSocketAddress());

                Socket mcSocket = new Socket();
                mcSocket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), minecraftPort), CONNECT_TIMEOUT);
                mcSocket.setTcpNoDelay(true);
                mcSocket.setSendBufferSize(BUFFER_SIZE);
                mcSocket.setReceiveBufferSize(BUFFER_SIZE);

                BridgePair pair = new BridgePair(clientSocket, mcSocket);
                activePairs.add(pair);
                ExecutorService exec = getOrCreateExecutor();
                exec.submit(() -> bridge(pair, pair.client, pair.mc, "P2P->MC"));
                exec.submit(() -> bridge(pair, pair.mc, pair.client, "MC->P2P"));
            } catch (IOException e) {
                if (clientSocket != null) try { clientSocket.close(); } catch (IOException ignored) {}
                if (running.get()) {
                    LOGGER.error("Error accepting P2P connection: {}", e.getMessage());
                }
            }
        }
    }

    public static synchronized CompletableFuture<Integer> connectToHost(String hostIp, int hostPort) {
        cancelled.set(false);
        tcpJoinerBridgeConnectedV4.set(false);
        if (isRunning()) {
            int jp = joinerPort;
            if (jp > 0) return CompletableFuture.completedFuture(jp);
            disconnect();
        }
        if (hostIp != null && hostIp.contains(":")) {
            try {
                InetAddress.getByName(hostIp);
            } catch (Exception e) {
                LOGGER.warn("IPv6 address pre-resolution failed for {}: {}", hostIp, e.getMessage());
            }
        }
        return connectToHostWithRetry(hostIp, hostPort, 0);
    }

    private static CompletableFuture<Integer> connectToHostWithRetry(String hostIp, int hostPort, int attempt) {
        if (cancelled.get()) return CompletableFuture.completedFuture(-1);
        synchronized (P2PBridge.class) {
            if (isRunning()) {
                int jp = joinerPort;
                if (jp > 0) return CompletableFuture.completedFuture(jp);
            }
            running.set(true);
        }
        return CompletableFuture.supplyAsync(() -> {
            if (cancelled.get()) return -1;
            try {
                LOGGER.info("Joiner: connecting to host {}:{} (attempt {}/{})", hostIp, hostPort, attempt + 1, MAX_RETRY + 1);
                Socket hostSocket = tryConnectWithRetry(hostIp, hostPort);
                if (hostSocket == null) {
                    LOGGER.error("Joiner: failed to connect to host {}:{}", hostIp, hostPort);
                    running.set(false);
                    return -1;
                }
                LOGGER.info("Joiner: connected to host {}:{}", hostIp, hostPort);

                ServerSocket js = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
                int jp = js.getLocalPort();
                synchronized (P2PBridge.class) {
                    joinerServer = js;
                    joinerPort = jp;
                    currentHostIp = hostIp;
                    currentHostPort = hostPort;
                }
                LOGGER.info("Joiner: local bridge on port {}, tunneling to host {}:{}", jp, hostIp, hostPort);

                hostSocket.setTcpNoDelay(true);
                hostSocket.setSendBufferSize(BUFFER_SIZE);
                hostSocket.setReceiveBufferSize(BUFFER_SIZE);

                final ServerSocket serv = js;
                ExecutorService exec = getOrCreateExecutor();
                exec.submit(() -> acceptJoinerConnectionPreconnected(serv, hostSocket));

                return jp;
            } catch (IOException e) {
                LOGGER.error("Joiner: failed to create local bridge (attempt {}/{}): {}", attempt + 1, MAX_RETRY + 1, e.getMessage());
                running.set(false);
                return -1;
            }
        }, getOrCreateExecutor()).thenCompose(result -> {
            if (result > 0 || attempt >= MAX_RETRY || cancelled.get()) return CompletableFuture.completedFuture(result);
            CompletableFuture<Integer> retry = new CompletableFuture<>();
            Thread t = new Thread(() -> {
                if (cancelled.get()) { retry.complete(-1); return; }
                try {
                    Thread.sleep(1000L * (attempt + 1));
                    if (cancelled.get()) { retry.complete(-1); return; }
                    connectToHostWithRetry(hostIp, hostPort, attempt + 1)
                            .whenComplete((v, ex) -> {
                                if (ex != null) retry.completeExceptionally(ex);
                                else retry.complete(v);
                            });
                } catch (InterruptedException e) {
                    retry.complete(-1);
                }
            }, "VoxLink-Retry");
            t.setDaemon(true);
            t.start();
            return retry;
        });
    }

    public static synchronized CompletableFuture<Integer> connectToHostIpv6(String hostIpv6, int hostPort) {
        cancelled.set(false);
        tcpJoinerBridgeConnectedV6.set(false);
        if (isRunning()) {
            int jp = joinerPort;
            if (jp > 0) return CompletableFuture.completedFuture(jp);
            disconnect();
        }
        return connectToHostIpv6WithRetry(hostIpv6, hostPort, 0);
    }

    private static CompletableFuture<Integer> connectToHostIpv6WithRetry(String hostIpv6, int hostPort, int attempt) {
        if (cancelled.get()) return CompletableFuture.completedFuture(-1);
        synchronized (P2PBridge.class) {
            if (isRunning()) {
                int jp = joinerPort;
                if (jp > 0) return CompletableFuture.completedFuture(jp);
            }
            running.set(true);
        }
        return CompletableFuture.supplyAsync(() -> {
            if (cancelled.get()) return -1;
            try {
                LOGGER.info("Joiner: connecting to host [{}]:{} (attempt {}/{})", hostIpv6, hostPort, attempt + 1, MAX_RETRY + 1);
                Socket hostSocket = tryConnectWithRetry(hostIpv6, hostPort);
                if (hostSocket == null) {
                    LOGGER.error("Joiner: failed to connect to host [{}]:{}", hostIpv6, hostPort);
                    running.set(false);
                    return -1;
                }
                LOGGER.info("Joiner: connected to host [{}]:{}", hostIpv6, hostPort);

                ServerSocket js = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
                int jp = js.getLocalPort();
                synchronized (P2PBridge.class) {
                    joinerServer = js;
                    joinerPort = jp;
                    currentHostIp = hostIpv6;
                    currentHostPort = hostPort;
                }
                LOGGER.info("Joiner: local bridge on port {}, tunneling to host [{}]:{}", jp, hostIpv6, hostPort);

                hostSocket.setTcpNoDelay(true);
                hostSocket.setSendBufferSize(BUFFER_SIZE);
                hostSocket.setReceiveBufferSize(BUFFER_SIZE);

                final ServerSocket serv = js;
                ExecutorService exec = getOrCreateExecutor();
                exec.submit(() -> acceptJoinerConnectionPreconnected(serv, hostSocket));

                return jp;
            } catch (IOException e) {
                LOGGER.error("Joiner: failed to create IPv6 local bridge (attempt {}/{}): {}", attempt + 1, MAX_RETRY + 1, e.getMessage());
                running.set(false);
                return -1;
            }
        }, getOrCreateExecutor()).thenCompose(result -> {
            if (result > 0 || attempt >= MAX_RETRY || cancelled.get()) return CompletableFuture.completedFuture(result);
            CompletableFuture<Integer> retry = new CompletableFuture<>();
            Thread t = new Thread(() -> {
                if (cancelled.get()) { retry.complete(-1); return; }
                try {
                    Thread.sleep(1000L * (attempt + 1));
                    if (cancelled.get()) { retry.complete(-1); return; }
                    connectToHostIpv6WithRetry(hostIpv6, hostPort, attempt + 1)
                            .whenComplete((v, ex) -> {
                                if (ex != null) retry.completeExceptionally(ex);
                                else retry.complete(v);
                            });
                } catch (InterruptedException e) {
                    retry.complete(-1);
                }
            }, "VoxLink-Retry6");
            t.setDaemon(true);
            t.start();
            return retry;
        });
    }

    private static final AtomicBoolean tcpJoinerBridgeConnectedV4 = new AtomicBoolean(false);
    private static final AtomicBoolean tcpJoinerBridgeConnectedV6 = new AtomicBoolean(false);

    private static void acceptJoinerConnectionPreconnected(ServerSocket js, Socket hostSocket) {
        try {
            js.setSoTimeout(10000);
        } catch (IOException ignored) {}
        try {
            Socket mcClient = js.accept();
            mcClient.setTcpNoDelay(true);
            mcClient.setSendBufferSize(BUFFER_SIZE);
            mcClient.setReceiveBufferSize(BUFFER_SIZE);
            LOGGER.info("Joiner: MC client connected to local bridge (pre-connected)");

            String label = hostSocket.getInetAddress() instanceof java.net.Inet6Address ? "MC->Host(IPv6)" : "MC->Host";
            String labelRev = hostSocket.getInetAddress() instanceof java.net.Inet6Address ? "Host(IPv6)->MC" : "Host->MC";

            BridgePair pair = new BridgePair(mcClient, hostSocket);
            activePairs.add(pair);
            ExecutorService exec = getOrCreateExecutor();
            exec.submit(() -> bridge(pair, pair.client, pair.mc, label));
            exec.submit(() -> bridge(pair, pair.mc, pair.client, labelRev));
        } catch (IOException e) {
            if (running.get()) {
                LOGGER.error("Joiner: error accepting MC connection: {}", e.getMessage());
            }
            try { hostSocket.close(); } catch (IOException ignored) {}
        } finally {
            try { js.close(); } catch (IOException ignored) {}
            synchronized (P2PBridge.class) {
                if (joinerServer == js) { joinerServer = null; joinerPort = -1; }
            }
        }
    }

    private static void acceptJoinerConnections(ServerSocket js) {
        try {
            js.setSoTimeout(1000);
        } catch (IOException ignored) {}
        while (running.get() && !tcpJoinerBridgeConnectedV4.get()) {
            try {
                Socket mcClient;
                try {
                    mcClient = js.accept();
                } catch (java.net.SocketTimeoutException e) {
                    continue;
                }
                mcClient.setTcpNoDelay(true);
                mcClient.setSendBufferSize(BUFFER_SIZE);
                mcClient.setReceiveBufferSize(BUFFER_SIZE);
                LOGGER.info("Joiner: MC client connected to local bridge");

                Socket hostSocket = tryConnectWithRetry(currentHostIp, currentHostPort);
                if (hostSocket == null) {
                    LOGGER.error("Joiner: failed to connect to host {}:{}", currentHostIp, currentHostPort);
                    try { mcClient.close(); } catch (IOException ignored) {}
                    continue;
                }

                tcpJoinerBridgeConnectedV4.set(true);
                BridgePair pair = new BridgePair(mcClient, hostSocket);
                activePairs.add(pair);
                ExecutorService exec = getOrCreateExecutor();
                exec.submit(() -> bridge(pair, pair.client, pair.mc, "MC->Host"));
                exec.submit(() -> bridge(pair, pair.mc, pair.client, "Host->MC"));
            } catch (IOException e) {
                if (running.get()) {
                    LOGGER.error("Joiner: error accepting MC connection: {}", e.getMessage());
                }
            }
        }
        tcpJoinerBridgeConnectedV4.set(false);
        try { js.close(); } catch (IOException ignored) {}
        synchronized (P2PBridge.class) {
            if (joinerServer == js) { joinerServer = null; joinerPort = -1; }
        }
    }

    private static void acceptJoinerConnectionsIpv6(ServerSocket js, String hostIpv6, int hostPort) {
        try {
            js.setSoTimeout(1000);
        } catch (IOException ignored) {}
        while (running.get() && !tcpJoinerBridgeConnectedV6.get()) {
            try {
                Socket mcClient;
                try {
                    mcClient = js.accept();
                } catch (java.net.SocketTimeoutException e) {
                    continue;
                }
                mcClient.setTcpNoDelay(true);
                mcClient.setSendBufferSize(BUFFER_SIZE);
                mcClient.setReceiveBufferSize(BUFFER_SIZE);
                LOGGER.info("Joiner: MC client connected to IPv6 local bridge");

                Socket hostSocket = tryConnectWithRetry(hostIpv6, hostPort);
                if (hostSocket == null) {
                    LOGGER.error("Joiner: failed to connect to host [{}]:{}", hostIpv6, hostPort);
                    try { mcClient.close(); } catch (IOException ignored) {}
                    continue;
                }

                tcpJoinerBridgeConnectedV6.set(true);
                BridgePair pair = new BridgePair(mcClient, hostSocket);
                activePairs.add(pair);
                ExecutorService exec = getOrCreateExecutor();
                exec.submit(() -> bridge(pair, pair.client, pair.mc, "MC->Host(IPv6)"));
                exec.submit(() -> bridge(pair, pair.mc, pair.client, "Host(IPv6)->MC"));
            } catch (IOException e) {
                if (running.get()) {
                    LOGGER.error("Joiner: error accepting MC connection (IPv6): {}", e.getMessage());
                }
            }
        }
        tcpJoinerBridgeConnectedV6.set(false);
        try { js.close(); } catch (IOException ignored) {}
        synchronized (P2PBridge.class) {
            if (joinerServer == js) { joinerServer = null; joinerPort = -1; }
        }
    }

    private static Socket tryConnectWithRetry(String host, int port) {
        if (host == null) {
            return null;
        }
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            Socket socket = null;
            try {
                LOGGER.info("Joiner: connecting to {}:{} (attempt {}/{})", host, port, attempt, MAX_RETRY);
                socket = new Socket();
                socket.setTcpNoDelay(true);
                socket.setSendBufferSize(BUFFER_SIZE);
                socket.setReceiveBufferSize(BUFFER_SIZE);
                InetSocketAddress addr;
                String resolvedHost = host;
                if (host.contains(":")) {
                    if (host.startsWith("[") && host.contains("]")) {
                        int bracketEnd = host.indexOf(']');
                        resolvedHost = host.substring(1, bracketEnd);
                    }
                    addr = new InetSocketAddress(InetAddress.getByName(resolvedHost), port);
                } else {
                    addr = new InetSocketAddress(host, port);
                }
                socket.connect(addr, CONNECT_TIMEOUT);
                LOGGER.info("Joiner: connected to {}:{}", host, port);
                return socket;
            } catch (IOException e) {
                if (socket != null) try { socket.close(); } catch (IOException ignored) {}
                LOGGER.warn("Joiner: connect attempt {}/{} failed for {}:{} - {}", attempt, MAX_RETRY, host, port, e.getMessage());
                if (attempt < MAX_RETRY) {
                    try { Thread.sleep(1000 * attempt); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
            }
        }
        return null;
    }

    private static void bridge(BridgePair pair, Socket from, Socket to, String label) {
        LOGGER.info("[Bridge] {} started (from={}, to={})", label, from.getRemoteSocketAddress(), to.getRemoteSocketAddress());
        try {
            from.setSoTimeout(60000);
        } catch (IOException ignored) {}
        try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            boolean firstPacket = true;
            while (running.get() && !from.isClosed() && !to.isClosed()) {
                try {
                    bytesRead = in.read(buffer);
                    if (bytesRead == -1) break;
                } catch (java.net.SocketTimeoutException e) {
                    continue;
                }
                if (firstPacket) {
                    LOGGER.info("[Bridge] {} first data: {} bytes", label, bytesRead);
                    firstPacket = false;
                }
                out.write(buffer, 0, bytesRead);
                out.flush();
            }
        } catch (IOException e) {
            if (running.get()) {
                LOGGER.info("Bridge {} closed: {}", label, e.getMessage());
            }
        } finally {
            pair.close();
            activePairs.remove(pair);
        }
    }

    public static void registerPendingUdpTimeout(ScheduledFuture<?> future) {
        if (future != null) pendingUdpTimeouts.add(future);
    }

    public static void cancelPendingUdpTimeouts() {
        for (ScheduledFuture<?> f : pendingUdpTimeouts) {
            f.cancel(false);
        }
        pendingUdpTimeouts.clear();
    }

    public static synchronized void disconnect() {
        StackTraceElement[] st = new Throwable().getStackTrace();
        String caller = st.length > 1 ? st[1].getClassName() + "." + st[1].getMethodName() : "?";
        LOGGER.info("[P2PBridge] disconnect调用 from {}", caller);
        cancelled.set(true);
        running.set(false);

        // 先TCP后UDP
        for (BridgePair pair : activePairs) {
            pair.close();
        }
        activePairs.clear();

        for (ReliableUdpTransport transport : activeUdpTransports) {
            try { transport.close(); } catch (Exception ignored) {}
        }
        activeUdpTransports.clear();

        cancelPendingUdpTimeouts();

        try {
            if (hostServer != null && !hostServer.isClosed()) hostServer.close();
        } catch (IOException ignored) {}
        try {
            if (joinerServer != null && !joinerServer.isClosed()) joinerServer.close();
        } catch (IOException ignored) {}

        hostServer = null;
        hostPort = -1;
        joinerServer = null;
        joinerPort = -1;
        currentHostIp = null;
        currentHostPort = 0;
        joinerBridgeConnected.set(false);
        tcpJoinerBridgeConnectedV4.set(false);
        tcpJoinerBridgeConnectedV6.set(false);

        ExecutorService oldExecutor = bridgeExecutor;
        bridgeExecutor = null;
        if (oldExecutor != null && !oldExecutor.isShutdown()) {
            oldExecutor.shutdown();
            try {
                if (!oldExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                    LOGGER.warn("bridge executor 3s没停干净，强杀");
                    oldExecutor.shutdownNow();
                    if (!oldExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                        LOGGER.warn("bridge executor强杀后还在跑 :(");
                    }
                }
            } catch (InterruptedException e) {
                oldExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private static synchronized ExecutorService getOrCreateExecutor() {
        if (bridgeExecutor == null || bridgeExecutor.isShutdown()) {
            bridgeExecutor = new ThreadPoolExecutor(0, 16, 60L, TimeUnit.SECONDS,
                    new SynchronousQueue<>(), r -> {
                        Thread t = new Thread(r, "VoxLink-Bridge");
                        t.setDaemon(true);
                        return t;
                    }, new ThreadPoolExecutor.CallerRunsPolicy());
        }
        return bridgeExecutor;
    }

    public static int getHostPort() {
        return hostPort;
    }

    public static int getJoinerPort() {
        return joinerPort;
    }

    public static boolean isRunning() {
        return running.get();
    }

    public static boolean isTargetMatch(String hostIp, int port) {
        if (currentHostPort != port) return false;
        if (hostIp != null && !hostIp.equals(currentHostIp)) return false;
        return true;
    }

    private static final CopyOnWriteArrayList<ReliableUdpTransport> activeUdpTransports = new CopyOnWriteArrayList<>();
    private static final AtomicBoolean currentJoinerMcSocketLock = new AtomicBoolean(false);
    private static volatile Socket currentJoinerMcSocket = null;
    private static final AtomicBoolean joinerBridgeConnected = new AtomicBoolean(false);

    public static synchronized int startUdpJoinerBridge(ReliableUdpTransport transport) {
        cancelled.set(false);
        joinerBridgeConnected.set(false);
        if (running.get() && joinerPort > 0) return joinerPort;
        try {
            if (joinerServer != null && !joinerServer.isClosed()) {
                joinerServer.close();
            }
            joinerServer = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            joinerPort = joinerServer.getLocalPort();
            running.set(true);
            activeUdpTransports.add(transport);
            transport.start();
            getOrCreateExecutor().execute(() -> acceptUdpJoinerConnections(joinerServer, transport));
            LOGGER.info("UDP joiner bridge started on port {}", joinerPort);
            return joinerPort;
        } catch (IOException e) {
            LOGGER.error("Failed to start UDP joiner bridge: {}", e.getMessage());
            running.set(false);
            if (joinerServer != null && !joinerServer.isClosed()) {
                try { joinerServer.close(); } catch (IOException ignored) {}
            }
            joinerServer = null;
            joinerPort = -1;
            return -1;
        }
    }

    private static void acceptUdpJoinerConnections(ServerSocket js, ReliableUdpTransport transport) {
        try { js.setSoTimeout(1000); } catch (IOException ignored) {}
        while (running.get() && !js.isClosed() && transport.isConnected() && !joinerBridgeConnected.get()) {
            try {
                Socket mcClient;
                try { mcClient = js.accept(); } catch (java.net.SocketTimeoutException e) { continue; }
                mcClient.setTcpNoDelay(true);
                mcClient.setSendBufferSize(BUFFER_SIZE);
                mcClient.setReceiveBufferSize(BUFFER_SIZE);

                if (currentJoinerMcSocketLock.compareAndSet(false, true)) {
                    try {
                        Socket oldMcSocket = currentJoinerMcSocket;
                        if (oldMcSocket != null && !oldMcSocket.isClosed()) {
                            LOGGER.info("UDP: Closing previous MC client connection");
                            try { oldMcSocket.close(); } catch (IOException ignored) {}
                        }
                        currentJoinerMcSocket = mcClient;
                    } finally {
                        currentJoinerMcSocketLock.set(false);
                    }
                }
                joinerBridgeConnected.set(true);

                LOGGER.info("UDP: MC client connected to joiner bridge");
                ExecutorService exec = getOrCreateExecutor();
                exec.submit(() -> bridgeUdpToMc(transport, mcClient, null));
                bridgeMcToUdp(transport, mcClient, null);
            } catch (IOException e) {
                if (running.get() && !js.isClosed()) LOGGER.warn("UDP joiner bridge accept error: {}", e.getMessage());
            }
        }
        joinerBridgeConnected.set(false);
        try { js.close(); } catch (IOException ignored) {}
        synchronized (P2PBridge.class) {
            if (joinerServer == js) { joinerServer = null; joinerPort = -1; }
        }
    }

    public static synchronized void startUdpHostBridgeForClient(String clientId, ReliableUdpTransport transport, int mcPort, Runnable onClose) {
        cancelled.set(false);
        activeUdpTransports.add(transport);
        transport.start();
        running.set(true);

        getOrCreateExecutor().execute(() -> {
            final Socket[] mcSocketRef = new Socket[1];
            final Runnable onCloseFinal = onClose;
            try {
                Socket mcSocket = new Socket();
                mcSocketRef[0] = mcSocket;
                mcSocket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), mcPort), CONNECT_TIMEOUT);
                mcSocket.setTcpNoDelay(true);
                mcSocket.setSendBufferSize(BUFFER_SIZE);
                mcSocket.setReceiveBufferSize(BUFFER_SIZE);
                LOGGER.info("UDP host bridge for client {} connected to MC server on port {}", clientId, mcPort);

                ExecutorService exec = getOrCreateExecutor();
                final Socket mcSocketFinal = mcSocket;
                exec.submit(() -> bridgeUdpToMc(transport, mcSocketFinal, onCloseFinal));
                bridgeMcToUdp(transport, mcSocketFinal, onCloseFinal);
            } catch (IOException e) {
                LOGGER.error("UDP host bridge for client {} failed to connect to MC server: {}", clientId, e.getMessage());
                try { transport.close(); } catch (Exception ignored) {}
                Socket s = mcSocketRef[0];
                if (s != null) try { s.close(); } catch (IOException ignored) {}
                activeUdpTransports.remove(transport);
                if (onCloseFinal != null) onCloseFinal.run();
            }
        });
    }

    private static void bridgeUdpToMc(ReliableUdpTransport transport, Socket mcSocket, Runnable onClose) {
        InputStream udpIn = transport.getInputStream();
        OutputStream mcOut = null;
        int bytesRead = 0;
        try {
            mcOut = mcSocket.getOutputStream();
            byte[] buffer = new byte[BUFFER_SIZE];
            while (running.get() && transport.isConnected() && !mcSocket.isClosed()
                    && (bytesRead = udpIn.read(buffer)) != -1) {
                mcOut.write(buffer, 0, bytesRead);
                mcOut.flush();
            }
            LOGGER.info("UDP->MC退出: running={} conn={} mcClosed={} read={}",
                    running.get(), transport.isConnected(), mcSocket.isClosed(), bytesRead);
        } catch (IOException e) {
            if (running.get()) LOGGER.info("UDP->MC bridge closed: {}", e.getMessage());
        } finally {
            //不关mcOut→不关mcSocket
            try { mcSocket.shutdownOutput(); } catch (IOException ignored) {}
        }
    }

    private static void bridgeMcToUdp(ReliableUdpTransport transport, Socket mcSocket, Runnable onClose) {
        InputStream mcIn = null;
        OutputStream udpOut = null;
        int bytesRead = 0;
        try {
            mcIn = mcSocket.getInputStream();
            udpOut = transport.getOutputStream();
            byte[] buffer = new byte[BUFFER_SIZE];
            while (running.get() && transport.isConnected() && !mcSocket.isClosed()
                    && (bytesRead = mcIn.read(buffer)) != -1) {
                udpOut.write(buffer, 0, bytesRead);
                udpOut.flush();
            }
            LOGGER.info("MC->UDP退出: running={} conn={} mcClosed={} read={}",
                    running.get(), transport.isConnected(), mcSocket.isClosed(), bytesRead);
        } catch (IOException e) {
            if (running.get()) LOGGER.info("MC->UDP bridge closed: {}", e.getMessage());
        } finally {
            try { mcSocket.close(); } catch (IOException ignored) {}
            try { transport.close(); } catch (Exception ignored) {}
            activeUdpTransports.remove(transport);
            if (onClose != null) onClose.run();
        }
    }

    private static class BridgePair {
        final Socket client;
        final Socket mc;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        BridgePair(Socket client, Socket mc) {
            this.client = client;
            this.mc = mc;
        }

        void close() {
            if (!closed.compareAndSet(false, true)) return;
            try { if (!client.isClosed()) client.close(); } catch (IOException ignored) {}
            try { if (!mc.isClosed()) mc.close(); } catch (IOException ignored) {}
        }
    }
}
