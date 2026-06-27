package icu.wuhui.voxlink.network;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class UdpHolePuncher {

    private static final Logger LOGGER = LoggerFactory.getLogger("voxlink-punch");

    private static final byte[] MAGIC = {0x56, 0x4C};
    private static final byte TYPE_PUNCH = 0x01;
    private static final byte TYPE_PUNCH_ACK = 0x02;
    private static final int PUNCH_INTERVAL_MS = 200;
    // 对齐对称NAT穿透手册: 典型打洞耗时约20s, 8s太短。50轮=10s后仍无回包才判防火墙
    private static final int PUNCH_TIMEOUT_MS = 20000;
    private static final int FIREWALL_DETECT_CYCLES = 50; // 10s@200ms, 留后半段给NAT建立映射
    public static final int PORT_PREDICTION_MAX_RANGE = 100;
    // 渐进扩展
    private static final int[] PROGRESSIVE_RANGES = {10, 25, 50, 75, 100};
    private static final int CYCLES_PER_RANGE = 2;
    public static void shutdown() {
        PUNCH_TIMEOUT_SCHEDULER.shutdown();
        try {
            if (!PUNCH_TIMEOUT_SCHEDULER.awaitTermination(2, TimeUnit.SECONDS)) {
                PUNCH_TIMEOUT_SCHEDULER.shutdownNow();
            }
        } catch (InterruptedException e) {
            PUNCH_TIMEOUT_SCHEDULER.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    private static final ScheduledExecutorService PUNCH_TIMEOUT_SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "VoxLink-PunchTimeout");
        t.setDaemon(true);
        return t;
    });

    private DatagramSocket socket;
    private final AtomicBoolean punching = new AtomicBoolean(false);
    private final AtomicBoolean holeOpen = new AtomicBoolean(false);
    private final AtomicBoolean remoteReceived = new AtomicBoolean(false);
    private final AtomicBoolean localConfirmed = new AtomicBoolean(false);
    private final AtomicBoolean completed = new AtomicBoolean(false);
    private volatile InetAddress remoteAddress;
    private volatile int remotePort;
    private volatile Thread recvThreadRef;
    private volatile Thread sendThreadRef;
    private volatile ScheduledFuture<?> timeoutFuture;

    private volatile boolean socketTransferred = false;
    private volatile CompletableFuture<DatagramSocket> activeResult;

    private volatile Consumer<InetSocketAddress> onPeerPunchReceived;
    private volatile java.util.List<UdpHolePuncher> socketGroup;

    public boolean isPunching() {
        return punching.get();
    }

    public DatagramSocket createSocket() throws SocketException {
        DatagramSocket old = socket;
        if (old != null && !old.isClosed()) old.close();
        socket = new DatagramSocket();
        socket.setSoTimeout(500);
        return socket;
    }

    public DatagramSocket createSocket(int preferredPort) throws SocketException {
        DatagramSocket old = socket;
        if (old != null && !old.isClosed()) old.close();
        try {
            socket = new DatagramSocket(preferredPort);
            socket.setSoTimeout(500);
            return socket;
        } catch (SocketException e) {
            return createSocket();
        }
    }

    public DatagramSocket getSocket() {
        return socket;
    }

    public StunProbe.PublicMappedAddress discoverMappedAddress(java.util.List<String> stunUrls) {
        return StunProbe.discoverMappedAddress(socket, stunUrls);
    }

    public StunProbe.PublicMappedAddress[] discoverMappedAddressDual(String stunUrl1, String stunUrl2) {
        return StunProbe.discoverMappedAddressDual(socket, stunUrl1, stunUrl2);
    }

    public CompletableFuture<DatagramSocket> punch(String remoteIp, int remotePort) {
        return punchWithPortPrediction(remoteIp, remotePort, 0);
    }

    public CompletableFuture<DatagramSocket> punchWithPortPrediction(String remoteIp, int basePort, int portRange) {
        return punchWithPortPrediction(remoteIp, basePort, portRange, false);
    }

    // EasyTier方式: 多socket各发3次到单一目标端口
    public CompletableFuture<DatagramSocket> punchMultiSocket(String remoteIp, int targetPort,
                                                              java.util.List<UdpHolePuncher> socketGroup,
                                                              java.util.concurrent.atomic.AtomicBoolean wonFlag) {
        punching.set(true);
        holeOpen.set(false);
        remoteReceived.set(false);
        localConfirmed.set(false);
        completed.set(false);

        try {
            this.remoteAddress = InetAddress.getByName(remoteIp);
            this.remotePort = targetPort;
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }

        CompletableFuture<DatagramSocket> result = new CompletableFuture<>();
        activeResult = result;
        this.socketGroup = socketGroup;
        final Object completionLock = new Object();
        final Consumer<InetSocketAddress> peerPunchCb = this.onPeerPunchReceived;

        byte[] data = new byte[3];
        data[0] = MAGIC[0];
        data[1] = MAGIC[1];
        data[2] = TYPE_PUNCH;

        int maxTotalCycles = PUNCH_TIMEOUT_MS / PUNCH_INTERVAL_MS;
        LOGGER.info("[UdpHolePuncher] 多socket发送启动: 目标={}:{}, sockets={}, 间隔={}ms",
                remoteIp, targetPort, socketGroup.size(), PUNCH_INTERVAL_MS);

        // 每个socket一个接收线程
        java.util.List<Thread> recvThreads = new java.util.ArrayList<>();
        for (int si = 0; si < socketGroup.size(); si++) {
            final UdpHolePuncher sp = socketGroup.get(si);
            final int sIdx = si;
            DatagramSocket ssock = sp.getSocket();
            if (ssock == null || ssock.isClosed()) continue;
            try { ssock.setSoTimeout(500); } catch (Exception ignored) {}
            Thread rt = new Thread(() -> {
                byte[] buf = new byte[64];
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                boolean peerPunchNotified = false;
                while (punching.get() && !holeOpen.get()) {
                    try {
                        ssock.receive(packet);
                        if (packet.getLength() < 3) continue;
                        if (buf[0] != MAGIC[0] || buf[1] != MAGIC[1]) continue;
                        byte type = buf[2];
                        if (type == TYPE_PUNCH) {
                            synchronized (completionLock) {
                                if (wonFlag.compareAndSet(false, true) && completed.compareAndSet(false, true)) {
                                    holeOpen.set(true);
                                    punching.set(false);
                                    socketTransferred = true;
                                    remoteAddress = packet.getAddress();
                                    remotePort = packet.getPort();
                                    LOGGER.info("[UdpHolePuncher] socket#{}收到PUNCH，打洞成功", sIdx);
                                    result.complete(ssock);
                                }
                            }
                            sp.sendControlTo(TYPE_PUNCH_ACK, packet.getAddress(), packet.getPort());
                        } else if (type == TYPE_PUNCH_ACK) {
                            synchronized (completionLock) {
                                if (wonFlag.compareAndSet(false, true) && completed.compareAndSet(false, true)) {
                                    holeOpen.set(true);
                                    punching.set(false);
                                    socketTransferred = true;
                                    remoteAddress = packet.getAddress();
                                    remotePort = packet.getPort();
                                    LOGGER.info("[UdpHolePuncher] socket#{}收到ACK，打洞成功", sIdx);
                                    result.complete(ssock);
                                }
                            }
                        }
                        if (!peerPunchNotified && peerPunchCb != null) {
                            peerPunchNotified = true;
                            try { peerPunchCb.accept(new InetSocketAddress(packet.getAddress(), packet.getPort())); } catch (Exception ignored) {}
                        }
                    } catch (SocketTimeoutException e) {
                    } catch (IOException e) {
                        if (punching.get()) return;
                    }
                }
            }, "VoxLink-PunchRecv-" + si);
            rt.setDaemon(true);
            recvThreads.add(rt);
            rt.start();
        }
        recvThreadRef = recvThreads.isEmpty() ? null : recvThreads.get(0);

        // 单发送线程: 每个socket发3次到目标端口
        final boolean skipFirewallCheck = socketGroup.size() <= 1;  // 单socket单端口=预测打洞, 无回包是正常的不是防火墙
        Thread sendThread = new Thread(() -> {
            int cycles = 0;
            long sendStartMs = System.currentTimeMillis();
            while (punching.get() && !holeOpen.get() && cycles < maxTotalCycles) {
                // 防火墙检测: 发了4秒还没收到任何回包 (单socket预测打洞跳过, 端口预测错误本就无回包)
                if (!skipFirewallCheck && cycles >= FIREWALL_DETECT_CYCLES && !remoteReceived.get()) {
                    long elapsed = System.currentTimeMillis() - sendStartMs;
                    LOGGER.warn("[UdpHolePuncher] 多socket防火墙检测: 发送{}轮/{}ms无回包，判定UDP被阻，提前终止", cycles, elapsed);
                    synchronized (completionLock) {
                        if (completed.compareAndSet(false, true)) {
                            punching.set(false);
                            result.completeExceptionally(new FirewallBlockedException("UDP blocked by firewall after " + elapsed + "ms (multi-socket)"));
                        }
                    }
                    return;
                }
                for (UdpHolePuncher sp : socketGroup) {
                    DatagramSocket s = sp.getSocket();
                    if (s == null || s.isClosed()) continue;
                    for (int r = 0; r < 3; r++) {
                        try {
                            DatagramPacket pkt = new DatagramPacket(data, data.length, remoteAddress, remotePort);
                            s.send(pkt);
                        } catch (IOException e) { }
                    }
                }
                cycles++;
                try {
                    Thread.sleep(PUNCH_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            LOGGER.info("[UdpHolePuncher] 多socket发送结束: cycles={}, holeOpen={}", cycles, holeOpen.get());
            if (!holeOpen.get() && punching.get()) {
                synchronized (completionLock) {
                    if (completed.compareAndSet(false, true)) {
                        punching.set(false);
                        result.completeExceptionally(new RuntimeException("UDP hole punch timeout"));
                    }
                }
            }
        }, "VoxLink-PunchSend");
        sendThread.setDaemon(true);
        sendThreadRef = sendThread;
        sendThread.start();

        ScheduledFuture<?> tf = PUNCH_TIMEOUT_SCHEDULER.schedule(() -> {
            if (!punching.get()) return;
            synchronized (completionLock) {
                if (completed.compareAndSet(false, true)) {
                    punching.set(false);
                    result.completeExceptionally(new RuntimeException("UDP hole punch timeout"));
                }
            }
        }, PUNCH_TIMEOUT_MS + 1000, TimeUnit.MILLISECONDS);
        timeoutFuture = tf;
        P2PBridge.registerPendingUdpTimeout(tf);

        return result;
    }

    private void sendControlTo(byte type, InetAddress addr, int port) {
        try {
            byte[] d = new byte[3];
            d[0] = MAGIC[0];
            d[1] = MAGIC[1];
            d[2] = type;
            DatagramPacket packet = new DatagramPacket(d, d.length, addr, port);
            socket.send(packet);
        } catch (IOException e) { }
    }

    public CompletableFuture<DatagramSocket> punchMultiPort(String remoteIp, java.util.List<Integer> targetPorts) {
        punching.set(true);
        holeOpen.set(false);
        remoteReceived.set(false);
        localConfirmed.set(false);
        completed.set(false);

        try {
            this.remoteAddress = InetAddress.getByName(remoteIp);
            this.remotePort = targetPorts.get(0);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }

        CompletableFuture<DatagramSocket> result = new CompletableFuture<>();
        activeResult = result;

        final Object completionLock = new Object();
        final Consumer<InetSocketAddress> peerPunchCb = this.onPeerPunchReceived;

        Thread recvThread = new Thread(() -> {
            byte[] buf = new byte[64];
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            boolean peerPunchNotified = false;
            int debugCount = 0;
            while (punching.get() && !holeOpen.get()) {
                try {
                    socket.receive(packet);
                    debugCount++;
                    if (debugCount <= 10) {
                        LOGGER.info("[UdpHolePuncher] 收到#{}: 来自{}:{}, len={}, bytes=[{},{},{}]",
                                debugCount, packet.getAddress().getHostAddress(), packet.getPort(),
                                packet.getLength(),
                                packet.getLength() > 0 ? (buf[0] & 0xFF) : -1,
                                packet.getLength() > 1 ? (buf[1] & 0xFF) : -1,
                                packet.getLength() > 2 ? (buf[2] & 0xFF) : -1);
                    }
                    if (packet.getLength() < 3) continue;
                    if (buf[0] != MAGIC[0] || buf[1] != MAGIC[1]) continue;
                    byte type = buf[2];
                    if (!packet.getAddress().equals(remoteAddress)) {
                        LOGGER.info("[UdpHolePuncher] CGNAT多IP: 接受来自{}:{} (期望IP{})",
                                packet.getAddress().getHostAddress(), packet.getPort(), remoteAddress.getHostAddress());
                        remoteAddress = packet.getAddress();
                        remotePort = packet.getPort();
                        if (!peerPunchNotified && peerPunchCb != null) {
                            peerPunchNotified = true;
                            try {
                                peerPunchCb.accept(new InetSocketAddress(packet.getAddress(), packet.getPort()));
                            } catch (Exception ignored) {}
                        }
                    }
                    if (packet.getPort() != remotePort) {
                        LOGGER.info("[UdpHolePuncher] 接受来自{}:{} (期望端口{})",
                                packet.getAddress().getHostAddress(), packet.getPort(), remotePort);
                        remotePort = packet.getPort();
                        if (!peerPunchNotified && peerPunchCb != null) {
                            peerPunchNotified = true;
                            try { peerPunchCb.accept(new InetSocketAddress(packet.getAddress(), packet.getPort())); } catch (Exception ignored) {}
                        }
                    }
                    if (type == TYPE_PUNCH) {
                        remoteReceived.set(true);
                        sendControl(TYPE_PUNCH_ACK);
                        synchronized (completionLock) {
                            if (localConfirmed.get() && completed.compareAndSet(false, true)) {
                                holeOpen.set(true);
                                punching.set(false);
                                socketTransferred = true;
                                result.complete(socket);
                            }
                        }
                    } else if (type == TYPE_PUNCH_ACK) {
                        localConfirmed.set(true);
                        synchronized (completionLock) {
                            if (remoteReceived.get() && completed.compareAndSet(false, true)) {
                                holeOpen.set(true);
                                punching.set(false);
                                socketTransferred = true;
                                result.complete(socket);
                            }
                        }
                    }
                } catch (SocketTimeoutException e) {
                } catch (IOException e) {
                    if (punching.get()) {
                        synchronized (completionLock) {
                            if (completed.compareAndSet(false, true)) {
                                punching.set(false);
                                result.completeExceptionally(e);
                            }
                        }
                    }
                    return;
                }
            }
        }, "VoxLink-PunchRecv");
        recvThread.setDaemon(true);
        recvThreadRef = recvThread;
        recvThread.start();

        Thread sendThread = new Thread(() -> {
            int cyclesPerformed = 0;
            int maxTotalCycles = PUNCH_TIMEOUT_MS / PUNCH_INTERVAL_MS;
            long sendStartMs = System.currentTimeMillis();
            byte[] data = new byte[3];
            data[0] = MAGIC[0];
            data[1] = MAGIC[1];
            data[2] = TYPE_PUNCH;
            LOGGER.info("[UdpHolePuncher] 多端口发送线程启动: 目标={}, 端口={}, 本地端口={}",
                    remoteAddress.getHostAddress(), targetPorts, socket.getLocalPort());
            while (punching.get() && !holeOpen.get() && cyclesPerformed < maxTotalCycles) {
                // 防火墙检测: 发了3秒还没收到任何回包
                if (cyclesPerformed >= FIREWALL_DETECT_CYCLES && !remoteReceived.get()) {
                    long elapsed = System.currentTimeMillis() - sendStartMs;
                    LOGGER.warn("[UdpHolePuncher] 多端口防火墙检测: 发送{}轮/{}ms无回包，判定UDP被阻，提前终止", cyclesPerformed, elapsed);
                    synchronized (completionLock) {
                        if (completed.compareAndSet(false, true)) {
                            punching.set(false);
                            result.completeExceptionally(new FirewallBlockedException("UDP blocked by firewall after " + elapsed + "ms (multi-port)"));
                        }
                    }
                    return;
                }
                for (int port : targetPorts) {
                    try {
                        DatagramPacket pkt = new DatagramPacket(data, data.length, remoteAddress, port);
                        socket.send(pkt);
                    } catch (IOException e) { }
                }
                cyclesPerformed++;
                try {
                    Thread.sleep(PUNCH_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            LOGGER.info("[UdpHolePuncher] 多端口发送线程结束: cycles={}, holeOpen={}, punching={}",
                    cyclesPerformed, holeOpen.get(), punching.get());
            if (!holeOpen.get() && punching.get()) {
                synchronized (completionLock) {
                    if (completed.compareAndSet(false, true)) {
                        punching.set(false);
                        result.completeExceptionally(new RuntimeException("UDP hole punch timeout"));
                    }
                }
            }
        }, "VoxLink-PunchSend");
        sendThread.setDaemon(true);
        sendThreadRef = sendThread;
        sendThread.start();

        ScheduledFuture<?> tf = PUNCH_TIMEOUT_SCHEDULER.schedule(() -> {
            synchronized (completionLock) {
                if (completed.compareAndSet(false, true)) {
                    punching.set(false);
                    result.completeExceptionally(new RuntimeException("UDP hole punch timeout"));
                }
            }
        }, PUNCH_TIMEOUT_MS + 2000, TimeUnit.MILLISECONDS);
        timeoutFuture = tf;
        P2PBridge.registerPendingUdpTimeout(tf);

        return result;
    }

    public CompletableFuture<DatagramSocket> punchWithPortPrediction(String remoteIp, int basePort, int portRange, boolean fixedRange) {
        punching.set(true);
        holeOpen.set(false);
        remoteReceived.set(false);
        localConfirmed.set(false);
        completed.set(false);

        try {
            this.remoteAddress = InetAddress.getByName(remoteIp);
            this.remotePort = basePort;
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }

        CompletableFuture<DatagramSocket> result = new CompletableFuture<>();
        activeResult = result;

        final Object completionLock = new Object();
        final boolean portPrediction = portRange > 0;
        final boolean useFixedRange = fixedRange;

        // 快照，线程安全
        final Consumer<InetSocketAddress> peerPunchCb = this.onPeerPunchReceived;

        Thread recvThread = new Thread(() -> {
            byte[] buf = new byte[64];
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            boolean peerPunchNotified = false;
            int debugCount = 0;
            while (punching.get() && !holeOpen.get()) {
                try {
                    socket.receive(packet);
                    debugCount++;
                    if (debugCount <= 10) {
                        LOGGER.info("[UdpHolePuncher] 收到#{}: 来自{}:{}, len={}, bytes=[{},{},{}]",
                                debugCount, packet.getAddress().getHostAddress(), packet.getPort(),
                                packet.getLength(),
                                packet.getLength() > 0 ? (buf[0] & 0xFF) : -1,
                                packet.getLength() > 1 ? (buf[1] & 0xFF) : -1,
                                packet.getLength() > 2 ? (buf[2] & 0xFF) : -1);
                    }
                    if (packet.getLength() < 3) continue;
                    if (buf[0] != MAGIC[0] || buf[1] != MAGIC[1]) continue;

                    byte type = buf[2];
                    if ((type == TYPE_PUNCH || type == TYPE_PUNCH_ACK) && remoteAddress != null) {
                        if (!packet.getAddress().equals(remoteAddress)) {
                            continue;
                        }
                        if (packet.getPort() != remotePort) {
                            LOGGER.info("[UdpHolePuncher] 接受来自{}:{} (期望端口{})",
                                    packet.getAddress().getHostAddress(), packet.getPort(), remotePort);
                            remotePort = packet.getPort();
                            if (!peerPunchNotified && peerPunchCb != null) {
                                peerPunchNotified = true;
                                try {
                                    peerPunchCb.accept(new InetSocketAddress(packet.getAddress(), packet.getPort()));
                                } catch (Exception ignored) {}
                            }
                        }
                    }
                    if (type == TYPE_PUNCH) {
                        remoteReceived.set(true);
                        sendControl(TYPE_PUNCH_ACK);
                        synchronized (completionLock) {
                            if (localConfirmed.get() && completed.compareAndSet(false, true)) {
                                holeOpen.set(true);
                                punching.set(false);
                                socketTransferred = true;
                                result.complete(socket);
                            }
                        }
                    } else if (type == TYPE_PUNCH_ACK) {
                        localConfirmed.set(true);
                        synchronized (completionLock) {
                            if (remoteReceived.get() && completed.compareAndSet(false, true)) {
                                holeOpen.set(true);
                                punching.set(false);
                                socketTransferred = true;
                                result.complete(socket);
                            }
                        }
                    }
                } catch (SocketTimeoutException e) {
                } catch (IOException e) {
                    if (punching.get()) {
                        synchronized (completionLock) {
                            if (completed.compareAndSet(false, true)) {
                                punching.set(false);
                                result.completeExceptionally(e);
                            }
                        }
                    }
                    return;
                }
            }
        }, "VoxLink-PunchRecv");
        recvThread.setDaemon(true);
        recvThreadRef = recvThread;
        recvThread.start();

        Thread sendThread = new Thread(() -> {
            int cyclesPerformed = 0;
            int maxTotalCycles = PUNCH_TIMEOUT_MS / PUNCH_INTERVAL_MS;
            int debugSendCount = 0;
            long sendStartMs = System.currentTimeMillis();
            LOGGER.info("[UdpHolePuncher] 发送线程启动: 目标={}, 端口={}, range={}, 本地端口={}",
                    remoteAddress != null ? remoteAddress.getHostAddress() : "null", remotePort, portRange, socket.getLocalPort());
            while (punching.get() && !holeOpen.get() && cyclesPerformed < maxTotalCycles) {
                // 防火墙检测: 发了3秒还没收到任何回包
                if (cyclesPerformed >= FIREWALL_DETECT_CYCLES && !remoteReceived.get()) {
                    long elapsed = System.currentTimeMillis() - sendStartMs;
                    LOGGER.warn("[UdpHolePuncher] 防火墙检测: 发送{}轮/{}ms无回包，判定UDP被阻，提前终止", cyclesPerformed, elapsed);
                    synchronized (completionLock) {
                        if (completed.compareAndSet(false, true)) {
                            punching.set(false);
                            result.completeExceptionally(new FirewallBlockedException("UDP blocked by firewall after " + elapsed + "ms"));
                        }
                    }
                    return;
                }
                if (portPrediction) {
                    int currentRange;
                    if (useFixedRange) {
                        currentRange = portRange;
                    } else {
                        // 渐进扩展
                        int rangeIdx = cyclesPerformed / CYCLES_PER_RANGE;
                        if (rangeIdx >= PROGRESSIVE_RANGES.length) {
                            rangeIdx = PROGRESSIVE_RANGES.length - 1;
                        }
                        currentRange = Math.min(PROGRESSIVE_RANGES[rangeIdx], portRange);
                    }
                    if (debugSendCount < 5) {
                        LOGGER.info("[UdpHolePuncher] 发送#{}: PUNCH到{}:{}±{} (cycle={}, fixed={}, 本地端口={})",
                                debugSendCount + 1, remoteAddress.getHostAddress(), basePort, currentRange, cyclesPerformed, useFixedRange, socket.getLocalPort());
                    }
                    sendControlMultiPort(TYPE_PUNCH, basePort, currentRange, cyclesPerformed);
                } else {
                    if (debugSendCount < 5) {
                        LOGGER.info("[UdpHolePuncher] 发送#{}: PUNCH到{}:{} (cycle={}, 本地端口={})",
                                debugSendCount + 1, remoteAddress.getHostAddress(), remotePort, cyclesPerformed, socket.getLocalPort());
                    }
                    sendControl(TYPE_PUNCH);
                }
                cyclesPerformed++;
                debugSendCount++;
                try {
                    Thread.sleep(PUNCH_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            LOGGER.info("[UdpHolePuncher] 发送线程结束: cyclesPerformed={}, holeOpen={}, punching={}",
                    cyclesPerformed, holeOpen.get(), punching.get());
            if (!holeOpen.get() && punching.get()) {
                synchronized (completionLock) {
                    if (completed.compareAndSet(false, true)) {
                        punching.set(false);
                        result.completeExceptionally(new RuntimeException("UDP hole punch timeout"));
                    }
                }
            }
        }, "VoxLink-PunchSend");
        sendThread.setDaemon(true);
        sendThreadRef = sendThread;
        sendThread.start();

        ScheduledFuture<?> tf = PUNCH_TIMEOUT_SCHEDULER.schedule(() -> {
            if (!punching.get()) return;
            synchronized (completionLock) {
                if (completed.compareAndSet(false, true)) {
                    punching.set(false);
                    result.completeExceptionally(new RuntimeException("UDP hole punch timeout"));
                }
            }
        }, PUNCH_TIMEOUT_MS + 1000, TimeUnit.MILLISECONDS);
        timeoutFuture = tf;
        P2PBridge.registerPendingUdpTimeout(tf);

        return result;
    }

    private void sendControl(byte type) {
        try {
            byte[] data = new byte[3];
            data[0] = MAGIC[0];
            data[1] = MAGIC[1];
            data[2] = type;
            DatagramPacket packet = new DatagramPacket(data, data.length, remoteAddress, remotePort);
            socket.send(packet);
        } catch (IOException e) {
            LOGGER.debug("[UdpHolePuncher] 发送失败: {}", e.getMessage());
        }
    }

    /**
     * EasyTier方式: Cone端用1个socket发到600-800个随机端口，每端口3次，1ms间隔。
     * 对称NAT打洞需要短时间密集发包建立映射: 3轮重复同一端口列表, 端口间1ms, 轮间10ms。
     * max_k2随round衰减: round>2时*2/round下限180, 避免后期浪费带宽。
     */
    private void sendControlMultiPort(byte type, int basePort, int portRange, int round) {
        byte[] data = new byte[3];
        data[0] = MAGIC[0];
        data[1] = MAGIC[1];
        data[2] = type;
        InetAddress addr = remoteAddress;
        if (addr == null) return;
        int centerPort = remotePort;

        boolean useRandomScan = portRange > 20;
        java.util.List<Integer> portsToSend = new java.util.ArrayList<>();
        portsToSend.add(centerPort);
        java.util.Random rnd = new java.util.Random();

        if (useRandomScan) {
            int maxK2 = 600 + rnd.nextInt(200);
            if (round > 2) {
                maxK2 = Math.max(maxK2 * 2 / round, 180);
            }
            java.util.Set<Integer> chosen = new java.util.HashSet<>();
            chosen.add(centerPort);
            int lowBound = Math.max(1, centerPort - portRange);
            int highBound = Math.min(65535, centerPort + portRange);
            int rangeSize = highBound - lowBound + 1;
            int maxRandom = Math.min(maxK2, rangeSize - 1);
            while (chosen.size() < maxRandom + 1) {
                int p = lowBound + rnd.nextInt(rangeSize);
                if (chosen.add(p)) {
                    portsToSend.add(p);
                }
            }
            java.util.Collections.shuffle(portsToSend.subList(1, portsToSend.size()), rnd);
        } else {
            for (int offset = 1; offset <= portRange; offset++) {
                int portLow = centerPort - offset;
                int portHigh = centerPort + offset;
                if (portLow > 0) portsToSend.add(portLow);
                if (portHigh <= 65535) portsToSend.add(portHigh);
            }
        }

        LOGGER.info("[UdpHolePuncher] sendControlMultiPort: 发到{}个端口(x3次x3轮, round={}): {} (中心={}, range=±{}, 随机={}, 本地端口={})",
                portsToSend.size(), round, portsToSend.subList(0, Math.min(10, portsToSend.size())),
                centerPort, portRange, useRandomScan, socket.getLocalPort());

        // 3轮重复, 每端口3次, 端口间1ms, 轮间10ms. 对称NAT需密集发包维持映射
        for (int roundPass = 0; roundPass < 3; roundPass++) {
            for (int i = 0; i < portsToSend.size(); i++) {
                int port = portsToSend.get(i);
                for (int r = 0; r < 3; r++) {
                    try {
                        DatagramPacket packet = new DatagramPacket(data, data.length, addr, port);
                        socket.send(packet);
                    } catch (IOException e) {
                    }
                }
                if (i < portsToSend.size() - 1) {
                    try { Thread.sleep(1); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); return; }
                }
            }
            if (roundPass < 2) {
                try { Thread.sleep(10); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); return; }
            }
        }
    }

    public void cancel() {
        if (punching.compareAndSet(true, false)) {
            CompletableFuture<DatagramSocket> r = activeResult;
            if (r != null && completed.compareAndSet(false, true)) {
                r.completeExceptionally(new CancellationException("punch cancelled"));
            }
        }
        ScheduledFuture<?> tf = timeoutFuture;
        if (tf != null) {
            tf.cancel(false);
            timeoutFuture = null;
        }
        if (socket != null && !socket.isClosed() && !socketTransferred) {
            socket.close();
        }
        if (recvThreadRef != null) recvThreadRef.interrupt();
        if (sendThreadRef != null) sendThreadRef.interrupt();
    }

    public void stopPunch() {
        if (punching.compareAndSet(true, false)) {
            CompletableFuture<DatagramSocket> r = activeResult;
            if (r != null && completed.compareAndSet(false, true)) {
                r.completeExceptionally(new CancellationException("punch stopped"));
            }
        }
        ScheduledFuture<?> tf = timeoutFuture;
        if (tf != null) {
            tf.cancel(false);
            timeoutFuture = null;
        }
        if (recvThreadRef != null) recvThreadRef.interrupt();
        if (sendThreadRef != null) sendThreadRef.interrupt();
        holeOpen.set(false);
        remoteReceived.set(false);
        localConfirmed.set(false);
    }

    public synchronized void updateTarget(String newIp, int newPort) {
        try {
            this.remoteAddress = InetAddress.getByName(newIp);
            this.remotePort = newPort;
            LOGGER.info("[UdpHolePuncher] 目标更新为{}:{}", newIp, newPort);
        } catch (Exception e) {
            LOGGER.warn("[UdpHolePuncher] 目标更新失败: {}", e.getMessage());
        }
    }

    public void setOnPeerPunchReceived(Consumer<InetSocketAddress> callback) {
        this.onPeerPunchReceived = callback;
    }

    public boolean isHoleOpen() {
        return holeOpen.get();
    }

    // 对称NAT端口可能不同
    public InetSocketAddress getActualRemoteAddress() {
        InetAddress addr = remoteAddress;
        int port = remotePort;
        if (addr == null || port <= 0) return null;
        return new InetSocketAddress(addr, port);
    }

    public void waitForRecvThreadExit() {
        Thread t = recvThreadRef;
        if (t != null && t.isAlive()) {
            try {
                t.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        Thread s = sendThreadRef;
        if (s != null && s.isAlive()) {
            try {
                s.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public void close() {
        if (punching.compareAndSet(true, false)) {
            CompletableFuture<DatagramSocket> r = activeResult;
            if (r != null && completed.compareAndSet(false, true)) {
                r.completeExceptionally(new CancellationException("punch closed"));
            }
        }
        ScheduledFuture<?> tf = timeoutFuture;
        if (tf != null) {
            tf.cancel(false);
            timeoutFuture = null;
        }
        // 关闭socket组
        java.util.List<UdpHolePuncher> group = socketGroup;
        if (group != null) {
            for (UdpHolePuncher sp : group) {
                DatagramSocket s = sp.getSocket();
                if (s != null && !s.isClosed() && !sp.socketTransferred) {
                    s.close();
                }
            }
            socketGroup = null;
        }
        if (socket != null && !socket.isClosed() && !socketTransferred) {
            socket.close();
        }
        if (recvThreadRef != null) recvThreadRef.interrupt();
        if (sendThreadRef != null) sendThreadRef.interrupt();
    }
}
