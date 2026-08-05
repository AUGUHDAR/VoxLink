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
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Iterator;
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
    //debounce 保留static兼容旧引用 运行时通过PunchProfile切换档位 动态适配极端场景
    private static final int PUNCH_TIMEOUT_MS = PunchProfile.DEFAULT.punchTimeoutMs;
    private static final int FIREWALL_DETECT_CYCLES = PunchProfile.DEFAULT.firewallDetectCycles;
    public static final int PORT_PREDICTION_MAX_RANGE = PunchProfile.DEFAULT.portPredictionMaxRange;
    private static final int[] PROGRESSIVE_RANGES = PunchProfile.DEFAULT.progressiveRanges;
    private static final int CYCLES_PER_RANGE = 2;
    //EasySym对打
    private static final int EASY_SYM_DUAL_SOCKET_COUNT = 25;
    private static final int EASY_SYM_DUAL_PORT_RANGE = 20;
    private static final int PUNCH_SOCKET_TIMEOUT_MS = 500;
    private static final int EXTRA_WAIT_MS = 1000;
    private static final int EXTRA_WAIT_LONG_MS = 2000;
    private static final int JITTER_BASE_MS = 600;
    private static final int JITTER_RANGE_MS = 200;
    private static final int MAX_DIVISOR = 2;
    private static final int MIN_FLOOR = 180;
    private static final int MIN_ROUNDS = 3;
    private static final int MIN_PASS = 3;
    private static final int SLEEP_SHORT_MS = 1;
    private static final int SLEEP_LONG_MS = 10;
    public static void shutdown() {
        PUNCH_TIMEOUT_SCHEDULER.shutdown();
        PUNCH_TIMEOUT_SCHEDULER.shutdownNow();
    }
    private static final ScheduledExecutorService PUNCH_TIMEOUT_SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "VoxLink-PunchTimeout");
        t.setDaemon(true);
        return t;
    });

    private DatagramSocket socket;

    // NIO 模式需要替换 socket 为 channel 绑定的 socket, 包内可见
    void replaceSocket(DatagramSocket newSocket) {
        DatagramSocket old = this.socket;
        if (old != null && old != newSocket && !old.isClosed()) {
            try { old.close(); } catch (Exception ignored) {}
        }
        this.socket = newSocket;
    }
    private final AtomicBoolean punching = new AtomicBoolean(false);
    private final AtomicBoolean holeOpen = new AtomicBoolean(false);
    private final AtomicBoolean remoteReceived = new AtomicBoolean(false);
    private final AtomicBoolean localConfirmed = new AtomicBoolean(false);
    private final AtomicBoolean completed = new AtomicBoolean(false);
    private volatile InetAddress remoteAddress;
    private volatile int remotePort;
    //debounce 改List支持punchMultiSocket的多recvThread cancel时全部interrupt
    private volatile java.util.List<Thread> recvThreadsRef = null;
    private volatile Thread sendThreadRef;
    private volatile ScheduledFuture<?> timeoutFuture;

    private volatile boolean socketTransferred = false;

    public void markSocketTransferred() {
        this.socketTransferred = true;
    }

    private volatile CompletableFuture<PunchResult> activeResult;

    private volatile Consumer<InetSocketAddress> onPeerPunchReceived;
    private volatile java.util.List<UdpHolePuncher> socketGroup;

    public boolean isPunching() {
        return punching.get();
    }

    public DatagramSocket createSocket() throws SocketException {
        DatagramSocket old = socket;
        if (old != null && !old.isClosed()) old.close();
        socket = new DatagramSocket();
        socket.setSoTimeout(PUNCH_SOCKET_TIMEOUT_MS);
        return socket;
    }

    public DatagramSocket createSocket(int preferredPort) throws SocketException {
        DatagramSocket old = socket;
        if (old != null && !old.isClosed()) old.close();
        try {
            socket = new DatagramSocket(preferredPort);
            socket.setSoTimeout(PUNCH_SOCKET_TIMEOUT_MS);
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

    public CompletableFuture<PunchResult> punch(String remoteIp, int remotePort) {
        return punchWithPortPrediction(remoteIp, remotePort, 0);
    }

    public CompletableFuture<PunchResult> punchWithPortPrediction(String remoteIp, int basePort, int portRange) {
        return punchWithPortPrediction(remoteIp, basePort, portRange, false);
    }

    // EasyTier方式: 多socket各发3次到单一目标端口
    public CompletableFuture<PunchResult> punchMultiSocket(String remoteIp, int targetPort,
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

        CompletableFuture<PunchResult> result = new CompletableFuture<>();
        activeResult = result;
        this.socketGroup = socketGroup;
        final Object completionLock = new Object();
        final Consumer<InetSocketAddress> peerPunchCb = this.onPeerPunchReceived;
        //debounce 打洞诊断计数 接收PUNCH/ACK分别计数+起始时间 用于PunchResult
        final int[] recvPunchCounter = {0};
        final int[] recvAckCounter = {0};
        final long startTime = System.currentTimeMillis();
        final int socketsTried = socketGroup.size();

        byte[] data = new byte[3];
        data[0] = MAGIC[0];
        data[1] = MAGIC[1];
        data[2] = TYPE_PUNCH;

        int maxTotalCycles = PunchProfile.current().punchTimeoutMs / PUNCH_INTERVAL_MS;
        //debounce 阶段六P2: 加入profile便于排查档位切换问题
        LOGGER.info("[UdpHolePuncher] Multi-socket send start: target={}:{}, sockets={}, interval={}ms, profile={}",
                remoteIp, targetPort, socketGroup.size(), PUNCH_INTERVAL_MS, PunchProfile.describe());

        // NIO Selector 单线程管所有 socket, 避免 84 socket × 3 线程 = 252 线程资源耗尽
        // 对齐 EasyTier UdpSocketArray 单线程事件驱动模型
        java.util.List<DatagramChannel> channels = new java.util.ArrayList<>();
        java.util.Map<DatagramChannel, UdpHolePuncher> channelToPuncher = new java.util.HashMap<>();
        java.util.Map<DatagramChannel, Integer> channelToIndex = new java.util.HashMap<>();
        Selector selector = null;
        try {
            selector = Selector.open();
            for (int si = 0; si < socketGroup.size(); si++) {
                UdpHolePuncher sp = socketGroup.get(si);
                DatagramSocket ssock = sp.getSocket();
                if (ssock == null || ssock.isClosed()) continue;
                DatagramChannel ch = ssock.getChannel();
                if (ch == null) {
                    // 旧 DatagramSocket 无 channel, 需要重新创建为 channel 绑同端口
                    ch = DatagramChannel.open();
                    ch.configureBlocking(false);
                    ch.bind(ssock.getLocalSocketAddress());
                    // 关闭旧 socket, 用 channel 的 socket 替代
                    try { ssock.close(); } catch (Exception ignored) {}
                    sp.replaceSocket(ch.socket());
                } else {
                    ch.configureBlocking(false);
                }
                ch.register(selector, SelectionKey.OP_READ);
                channels.add(ch);
                channelToPuncher.put(ch, sp);
                channelToIndex.put(ch, si);
            }
        } catch (IOException e) {
            LOGGER.warn("[UdpHolePuncher] NIO Selector init failed, fallback to multi-thread mode: {}", e.getMessage());
            // 回退旧多线程路径
            return punchMultiSocketLegacy(remoteIp, targetPort, socketGroup, wonFlag);
        }

        final Selector finalSelector = selector;
        final java.util.Map<DatagramChannel, UdpHolePuncher> finalChannelToPuncher = channelToPuncher;
        final java.util.Map<DatagramChannel, Integer> finalChannelToIndex = channelToIndex;
        final java.util.List<DatagramChannel> finalChannels = channels;

        // 单线程 Selector 接收循环
        Thread recvThread = new Thread(() -> {
            ByteBuffer buf = ByteBuffer.allocate(64);
            boolean peerPunchNotified = false;
            try {
                while (punching.get() && !holeOpen.get()) {
                    int ready = finalSelector.select(500);  // 500ms 超时, 便于检查 punching 标志
                    if (ready == 0) continue;
                    Iterator<SelectionKey> it = finalSelector.selectedKeys().iterator();
                    while (it.hasNext()) {
                        SelectionKey key = it.next();
                        it.remove();
                        if (!key.isReadable()) continue;
                        DatagramChannel ch = (DatagramChannel) key.channel();
                        buf.clear();
                        InetSocketAddress from;
                        try {
                            from = (InetSocketAddress) ch.receive(buf);
                        } catch (IOException e) {
                            continue;
                        }
                        if (from == null || buf.position() < 3) continue;
                        buf.flip();
                        byte b0 = buf.get(0), b1 = buf.get(1), b2 = buf.get(2);
                        if (b0 != MAGIC[0] || b1 != MAGIC[1]) continue;
                        UdpHolePuncher sp = finalChannelToPuncher.get(ch);
                        int sIdx = finalChannelToIndex.get(ch);
                        if (b2 == TYPE_PUNCH) {
                            recvPunchCounter[0]++;
                            synchronized (completionLock) {
                                if (wonFlag.compareAndSet(false, true) && completed.compareAndSet(false, true)) {
                                    holeOpen.set(true);
                                    punching.set(false);
                                    sp.socketTransferred = true;
                                    remoteAddress = from.getAddress();
                                    remotePort = from.getPort();
                                    LOGGER.info("[UdpHolePuncher] socket#{} received PUNCH, punch success (NIO)", sIdx);
                                    long elapsed = System.currentTimeMillis() - startTime;
                                    result.complete(PunchResult.success(sp.getSocket(), socketsTried,
                                            recvPunchCounter[0], recvAckCounter[0], 0, elapsed));
                                }
                            }
                            sp.sendControlTo(TYPE_PUNCH_ACK, from.getAddress(), from.getPort());
                        } else if (b2 == TYPE_PUNCH_ACK) {
                            recvAckCounter[0]++;
                            synchronized (completionLock) {
                                if (wonFlag.compareAndSet(false, true) && completed.compareAndSet(false, true)) {
                                    holeOpen.set(true);
                                    punching.set(false);
                                    sp.socketTransferred = true;
                                    remoteAddress = from.getAddress();
                                    remotePort = from.getPort();
                                    LOGGER.info("[UdpHolePuncher] socket#{} received ACK, punch success (NIO)", sIdx);
                                    long elapsed = System.currentTimeMillis() - startTime;
                                    result.complete(PunchResult.success(sp.getSocket(), socketsTried,
                                            recvPunchCounter[0], recvAckCounter[0], 0, elapsed));
                                }
                            }
                        }
                        if (!peerPunchNotified && peerPunchCb != null) {
                            peerPunchNotified = true;
                            try { peerPunchCb.accept(from); } catch (Exception ignored) {}
                        }
                    }
                }
            } catch (IOException e) {
                LOGGER.debug("[UdpHolePuncher] Selector receive exception: {}", e.getMessage());
            } finally {
                try { finalSelector.close(); } catch (IOException ignored) {}
                // 不关闭 channel, 因为底层 socket 可能已被 transferred
            }
        }, "VoxLink-PunchRecvNIO");
        recvThread.setDaemon(true);
        recvThreadsRef = java.util.Collections.singletonList(recvThread);
        recvThread.start();

        // 单发送线程: 每个socket发3次到目标端口
        final boolean skipFirewallCheck = socketGroup.size() <= 1;  // 单socket单端口=预测打洞, 无回包是正常的不是防火墙
        Thread sendThread = new Thread(() -> {
            int cycles = 0;
            long sendStartMs = System.currentTimeMillis();
            while (punching.get() && !holeOpen.get() && cycles < maxTotalCycles) {
                // 防火墙检测: 发了4秒还没收到任何回包 (单socket预测打洞跳过, 端口预测错误本就无回包)
                if (!skipFirewallCheck && cycles >= PunchProfile.current().firewallDetectCycles && !remoteReceived.get()) {
                    long elapsed = System.currentTimeMillis() - sendStartMs;
                    LOGGER.warn("[UdpHolePuncher] Multi-socket firewall check: sent {} cycles/{}ms no reply, UDP blocked, abort early", cycles, elapsed);
                    synchronized (completionLock) {
                        if (completed.compareAndSet(false, true)) {
                            punching.set(false);
                            result.complete(PunchResult.failure(socketsTried, recvPunchCounter[0],
                                    recvAckCounter[0], 0, elapsed, true));
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
            LOGGER.info("[UdpHolePuncher] Multi-socket send end: cycles={}, holeOpen={}", cycles, holeOpen.get());
            if (!holeOpen.get() && punching.get()) {
                synchronized (completionLock) {
                    if (completed.compareAndSet(false, true)) {
                        punching.set(false);
                        long elapsed = System.currentTimeMillis() - startTime;
                        result.complete(PunchResult.failure(socketsTried, recvPunchCounter[0],
                                recvAckCounter[0], 0, elapsed, false));
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
                    long elapsed = System.currentTimeMillis() - startTime;
                    result.complete(PunchResult.failure(socketsTried, recvPunchCounter[0],
                            recvAckCounter[0], 0, elapsed, false));
                }
            }
        }, PunchProfile.current().punchTimeoutMs + EXTRA_WAIT_MS, TimeUnit.MILLISECONDS);
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

    public CompletableFuture<PunchResult> punchMultiPort(String remoteIp, java.util.List<Integer> targetPorts) {
        punching.set(true);
        holeOpen.set(false);
        remoteReceived.set(false);
        localConfirmed.set(false);
        completed.set(false);

        //debounce 阶段六P2: 前置上下文日志 多端口预测打洞的target/range/profile 便于调试
        LOGGER.info("[UdpHolePuncher] punchMultiPort start: target={}, port count={}, range={}~{}, profile={}",
                remoteIp, targetPorts.size(),
                targetPorts.isEmpty() ? -1 : targetPorts.get(0),
                targetPorts.isEmpty() ? -1 : targetPorts.get(targetPorts.size() - 1),
                PunchProfile.describe());

        try {
            this.remoteAddress = InetAddress.getByName(remoteIp);
            this.remotePort = targetPorts.get(0);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }

        CompletableFuture<PunchResult> result = new CompletableFuture<>();
        activeResult = result;

        final Object completionLock = new Object();
        final Consumer<InetSocketAddress> peerPunchCb = this.onPeerPunchReceived;
        //debounce 打洞诊断计数 接收PUNCH/ACK分别计数+起始时间 用于PunchResult
        final int[] recvPunchCounter = {0};
        final int[] recvAckCounter = {0};
        final long startTime = System.currentTimeMillis();
        final int socketsTried = targetPorts.size();

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
                        LOGGER.info("[UdpHolePuncher] Received #{}: from {}:{}, len={}, bytes=[{},{},{}]",
                                debugCount, packet.getAddress().getHostAddress(), packet.getPort(),
                                packet.getLength(),
                                packet.getLength() > 0 ? (buf[0] & 0xFF) : -1,
                                packet.getLength() > 1 ? (buf[1] & 0xFF) : -1,
                                packet.getLength() > 2 ? (buf[2] & 0xFF) : -1);
                    }
                    if (packet.getLength() < 3) continue;
                    if (buf[0] != MAGIC[0] || buf[1] != MAGIC[1]) continue;
                    byte type = buf[2];
                    //debounce 打洞完成后冻结地址/端口 防止对端败北socket的迟到包把remotePort改到死端口
                    if (!completed.get() && !packet.getAddress().equals(remoteAddress)) {
                        LOGGER.info("[UdpHolePuncher] CGNAT multi-IP: accept from {}:{} (expected IP {})",
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
                    if (!completed.get() && packet.getPort() != remotePort) {
                        LOGGER.info("[UdpHolePuncher] Accept from {}:{} (expected port {})",
                                packet.getAddress().getHostAddress(), packet.getPort(), remotePort);
                        remotePort = packet.getPort();
                        if (!peerPunchNotified && peerPunchCb != null) {
                            peerPunchNotified = true;
                            try { peerPunchCb.accept(new InetSocketAddress(packet.getAddress(), packet.getPort())); } catch (Exception ignored) {}
                        }
                    }
                    if (type == TYPE_PUNCH) {
                        recvPunchCounter[0]++;
                        remoteReceived.set(true);
                        sendControl(TYPE_PUNCH_ACK);
                        synchronized (completionLock) {
                            if (localConfirmed.get() && completed.compareAndSet(false, true)) {
                                holeOpen.set(true);
                                punching.set(false);
                                socketTransferred = true;
                                long elapsed = System.currentTimeMillis() - startTime;
                                result.complete(PunchResult.success(socket, socketsTried,
                                        recvPunchCounter[0], recvAckCounter[0], 0, elapsed));
                            }
                        }
                    } else if (type == TYPE_PUNCH_ACK) {
                        recvAckCounter[0]++;
                        localConfirmed.set(true);
                        synchronized (completionLock) {
                            if (remoteReceived.get() && completed.compareAndSet(false, true)) {
                                holeOpen.set(true);
                                punching.set(false);
                                socketTransferred = true;
                                long elapsed = System.currentTimeMillis() - startTime;
                                result.complete(PunchResult.success(socket, socketsTried,
                                        recvPunchCounter[0], recvAckCounter[0], 0, elapsed));
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
        recvThreadsRef = java.util.Collections.singletonList(recvThread);
        recvThread.start();

        Thread sendThread = new Thread(() -> {
            int cyclesPerformed = 0;
            int maxTotalCycles = PunchProfile.current().punchTimeoutMs / PUNCH_INTERVAL_MS;
            long sendStartMs = System.currentTimeMillis();
            byte[] data = new byte[3];
            data[0] = MAGIC[0];
            data[1] = MAGIC[1];
            data[2] = TYPE_PUNCH;
            LOGGER.info("[UdpHolePuncher] Multi-port send thread start: target={}, port={}, local port={}",
                    remoteAddress.getHostAddress(), targetPorts, socket.getLocalPort());
            while (punching.get() && !holeOpen.get() && cyclesPerformed < maxTotalCycles) {
                //debounce 防火墙检测: 仅在打洞80%时间仍无回包时判定 避免端口预测难被误判
                if (cyclesPerformed >= maxTotalCycles * 4 / 5 && !remoteReceived.get()) {
                    long elapsed = System.currentTimeMillis() - sendStartMs;
                    LOGGER.warn("[UdpHolePuncher] Multi-port firewall check: sent {} cycles/{}ms no reply, UDP blocked, abort early", cyclesPerformed, elapsed);
                    synchronized (completionLock) {
                        if (completed.compareAndSet(false, true)) {
                            punching.set(false);
                            result.complete(PunchResult.failure(socketsTried, recvPunchCounter[0],
                                    recvAckCounter[0], 0, elapsed, true));
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
            LOGGER.info("[UdpHolePuncher] Multi-port send thread end: cycles={}, holeOpen={}, punching={}",
                    cyclesPerformed, holeOpen.get(), punching.get());
            if (!holeOpen.get() && punching.get()) {
                synchronized (completionLock) {
                    if (completed.compareAndSet(false, true)) {
                        punching.set(false);
                        long elapsed = System.currentTimeMillis() - startTime;
                        result.complete(PunchResult.failure(socketsTried, recvPunchCounter[0],
                                recvAckCounter[0], 0, elapsed, false));
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
                    long elapsed = System.currentTimeMillis() - startTime;
                    result.complete(PunchResult.failure(socketsTried, recvPunchCounter[0],
                            recvAckCounter[0], 0, elapsed, false));
                }
            }
        }, PunchProfile.current().punchTimeoutMs + EXTRA_WAIT_LONG_MS, TimeUnit.MILLISECONDS);
        timeoutFuture = tf;
        P2PBridge.registerPendingUdpTimeout(tf);

        return result;
    }

    public CompletableFuture<PunchResult> punchWithPortPrediction(String remoteIp, int basePort, int portRange, boolean fixedRange) {
        punching.set(true);
        holeOpen.set(false);
        remoteReceived.set(false);
        localConfirmed.set(false);
        completed.set(false);

        //debounce 阶段六P2: 前置上下文日志 端口预测打洞的target/range/fixed/profile 便于调试
        LOGGER.info("[UdpHolePuncher] punchWithPortPrediction start: target={}:{}, range={}, fixed={}, profile={}",
                remoteIp, basePort, portRange, fixedRange, PunchProfile.describe());

        try {
            this.remoteAddress = InetAddress.getByName(remoteIp);
            this.remotePort = basePort;
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }

        CompletableFuture<PunchResult> result = new CompletableFuture<>();
        activeResult = result;

        final Object completionLock = new Object();
        final boolean portPrediction = portRange > 0;
        final boolean useFixedRange = fixedRange;

        // 快照，线程安全
        final Consumer<InetSocketAddress> peerPunchCb = this.onPeerPunchReceived;
        //debounce 打洞诊断计数 接收PUNCH/ACK分别计数+起始时间 用于PunchResult
        final int[] recvPunchCounter = {0};
        final int[] recvAckCounter = {0};
        final long startTime = System.currentTimeMillis();
        final int socketsTried = 1;

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
                        LOGGER.info("[UdpHolePuncher] Received #{}: from {}:{}, len={}, bytes=[{},{},{}]",
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
                        //debounce 打洞完成后冻结端口 防止对端败北socket的迟到包把remotePort改到死端口
                        if (!completed.get() && packet.getPort() != remotePort) {
                            LOGGER.info("[UdpHolePuncher] Accept from {}:{} (expected port {})",
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
                        recvPunchCounter[0]++;
                        remoteReceived.set(true);
                        sendControl(TYPE_PUNCH_ACK);
                        synchronized (completionLock) {
                            if (localConfirmed.get() && completed.compareAndSet(false, true)) {
                                holeOpen.set(true);
                                punching.set(false);
                                socketTransferred = true;
                                long elapsed = System.currentTimeMillis() - startTime;
                                result.complete(PunchResult.success(socket, socketsTried,
                                        recvPunchCounter[0], recvAckCounter[0], 0, elapsed));
                            }
                        }
                    } else if (type == TYPE_PUNCH_ACK) {
                        recvAckCounter[0]++;
                        localConfirmed.set(true);
                        synchronized (completionLock) {
                            if (remoteReceived.get() && completed.compareAndSet(false, true)) {
                                holeOpen.set(true);
                                punching.set(false);
                                socketTransferred = true;
                                long elapsed = System.currentTimeMillis() - startTime;
                                result.complete(PunchResult.success(socket, socketsTried,
                                        recvPunchCounter[0], recvAckCounter[0], 0, elapsed));
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
        recvThreadsRef = java.util.Collections.singletonList(recvThread);
        recvThread.start();

        Thread sendThread = new Thread(() -> {
            int cyclesPerformed = 0;
            int maxTotalCycles = PunchProfile.current().punchTimeoutMs / PUNCH_INTERVAL_MS;
            int debugSendCount = 0;
            long sendStartMs = System.currentTimeMillis();
            LOGGER.info("[UdpHolePuncher] Send thread start: target={}, port={}, range={}, local port={}",
                    remoteAddress != null ? remoteAddress.getHostAddress() : "null", remotePort, portRange, socket.getLocalPort());
            while (punching.get() && !holeOpen.get() && cyclesPerformed < maxTotalCycles) {
                //debounce 端口预测打洞: 猜错端口无回包是常态 不做防火墙检测 让持续重试继续尝试
                if (portPrediction) {
                    int currentRange;
                    if (useFixedRange) {
                        currentRange = portRange;
                    } else {
                        // 渐进扩展
                        int rangeIdx = cyclesPerformed / CYCLES_PER_RANGE;
                        if (rangeIdx >= PunchProfile.current().progressiveRanges.length) {
                            rangeIdx = PunchProfile.current().progressiveRanges.length - 1;
                        }
                        currentRange = Math.min(PunchProfile.current().progressiveRanges[rangeIdx], portRange);
                    }
                    if (debugSendCount < 5) {
                        LOGGER.info("[UdpHolePuncher] Send #{}: PUNCH to {}:{}±{} (cycle={}, fixed={}, local port={})",
                                debugSendCount + 1, remoteAddress.getHostAddress(), basePort, currentRange, cyclesPerformed, useFixedRange, socket.getLocalPort());
                    }
                    sendControlMultiPort(TYPE_PUNCH, basePort, currentRange, cyclesPerformed);
                } else {
                    if (debugSendCount < 5) {
                        LOGGER.info("[UdpHolePuncher] Send #{}: PUNCH to {}:{} (cycle={}, local port={})",
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
            LOGGER.info("[UdpHolePuncher] Send thread end: cyclesPerformed={}, holeOpen={}, punching={}",
                    cyclesPerformed, holeOpen.get(), punching.get());
            if (!holeOpen.get() && punching.get()) {
                synchronized (completionLock) {
                    if (completed.compareAndSet(false, true)) {
                        punching.set(false);
                        long elapsed = System.currentTimeMillis() - startTime;
                        result.complete(PunchResult.failure(socketsTried, recvPunchCounter[0],
                                recvAckCounter[0], 0, elapsed, false));
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
                    long elapsed = System.currentTimeMillis() - startTime;
                    result.complete(PunchResult.failure(socketsTried, recvPunchCounter[0],
                            recvAckCounter[0], 0, elapsed, false));
                }
            }
        }, PunchProfile.current().punchTimeoutMs + EXTRA_WAIT_MS, TimeUnit.MILLISECONDS);
        timeoutFuture = tf;
        P2PBridge.registerPendingUdpTimeout(tf);

        return result;
    }

    //EasySym对打: 双方各开25 socket × ±20端口, 单边命中即整体成功
    public CompletableFuture<PunchResult> punchEasySymDual(
            String remoteIp, int remoteBasePort,
            StunProbe.NatType localNat, StunProbe.NatType remoteNat) {
        return punchEasySymDual(remoteIp, remoteBasePort, localNat, remoteNat, EASY_SYM_DUAL_SOCKET_COUNT);
    }

    //debounce 每轮socket数递增: 调用方根据continuousRetryRound传值 round0用25 round1+用50
    public CompletableFuture<PunchResult> punchEasySymDual(
            String remoteIp, int remoteBasePort,
            StunProbe.NatType localNat, StunProbe.NatType remoteNat,
            int socketCount) {
        final int effectiveSocketCount = socketCount > 0 ? socketCount : EASY_SYM_DUAL_SOCKET_COUNT;
        //debounce 阶段六P2: 加入profile便于排查档位切换问题
        LOGGER.info("[UdpHolePuncher] EasySym mutual punch start: target={}:{}, sockets={}, range=+/-{}, local={}, remote={}, profile={}",
                remoteIp, remoteBasePort, effectiveSocketCount, EASY_SYM_DUAL_PORT_RANGE,
                localNat.key, remoteNat.key, PunchProfile.describe());

        java.util.List<UdpHolePuncher> punchers = new java.util.ArrayList<>();
        for (int i = 0; i < effectiveSocketCount; i++) {
            UdpHolePuncher p = new UdpHolePuncher();
            try {
                p.createSocket();
            } catch (SocketException e) {
                LOGGER.warn("[UdpHolePuncher] EasySym socket#{} create failed: {}", i, e.getMessage());
                continue;
            }
            punchers.add(p);
        }

        if (punchers.isEmpty()) {
            return CompletableFuture.failedFuture(new SocketException("EasySym对打: 无可用socket"));
        }

        final java.util.List<UdpHolePuncher> punchersFinal = punchers;
        final java.util.List<CompletableFuture<PunchResult>> futures = new java.util.ArrayList<>();
        for (UdpHolePuncher p : punchers) {
            futures.add(p.punchWithPortPrediction(remoteIp, remoteBasePort, EASY_SYM_DUAL_PORT_RANGE, true));
        }

        final CompletableFuture<PunchResult> result = new CompletableFuture<>();
        final java.util.concurrent.atomic.AtomicInteger remaining =
                new java.util.concurrent.atomic.AtomicInteger(futures.size());
        //debounce 聚合失败诊断 tried/recvPunch/recvAck按子任务汇总
        final java.util.concurrent.atomic.AtomicInteger recvPunchSum =
                new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.atomic.AtomicInteger recvAckSum =
                new java.util.concurrent.atomic.AtomicInteger(0);
        final long startTime = System.currentTimeMillis();
        final int socketsTried = punchersFinal.size();

        for (int i = 0; i < futures.size(); i++) {
            final int idx = i;
            futures.get(i).whenComplete((pr, ex) -> {
                if (pr != null && pr.isSuccess()) {
                    //CAS守卫: 首个命中即整体成功
                    if (result.complete(pr)) {
                        LOGGER.info("[UdpHolePuncher] EasySym socket#{} hit, cancel others", idx);
                        for (int j = 0; j < punchersFinal.size(); j++) {
                            if (j != idx) {
                                punchersFinal.get(j).cancel();
                            }
                        }
                    } else {
                        //debounce 输家的socket未被transfer走 cancel不关 主动close防泄漏
                        try { pr.getSuccessSocket().close(); } catch (Exception ignored) {}
                    }
                } else {
                    if (pr != null) {
                        recvPunchSum.addAndGet(pr.socketsReceivedPunch);
                        recvAckSum.addAndGet(pr.socketsReceivedAck);
                    }
                    if (remaining.decrementAndGet() == 0 && !result.isDone()) {
                        //debounce 失败路径也清punchers 避免socket泄漏
                        for (UdpHolePuncher p : punchersFinal) {
                            try { p.close(); } catch (Exception ignored) {}
                        }
                        if (ex != null) {
                            result.completeExceptionally(ex);
                        } else {
                            long elapsed = System.currentTimeMillis() - startTime;
                            result.complete(PunchResult.failure(socketsTried, recvPunchSum.get(),
                                    recvAckSum.get(), 0, elapsed, false));
                        }
                    }
                }
            });
        }

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
            LOGGER.debug("[UdpHolePuncher] Send failed: {}", e.getMessage());
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
            int maxK2 = JITTER_BASE_MS + rnd.nextInt(JITTER_RANGE_MS);
            if (round > 2) {
                maxK2 = Math.max(maxK2 * MAX_DIVISOR / round, MIN_FLOOR);
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

        LOGGER.info("[UdpHolePuncher] sendControlMultiPort: send to {} ports (x3 times x3 rounds, round={}): {} (center={}, range=+/-{}, random={}, local port={})",
                portsToSend.size(), round, portsToSend.subList(0, Math.min(10, portsToSend.size())),
                centerPort, portRange, useRandomScan, socket.getLocalPort());

        // 3轮重复, 每端口3次, 端口间1ms, 轮间10ms. 对称NAT需密集发包维持映射
        for (int roundPass = 0; roundPass < MIN_ROUNDS; roundPass++) {
            for (int i = 0; i < portsToSend.size(); i++) {
                int port = portsToSend.get(i);
                for (int r = 0; r < MIN_PASS; r++) {
                    try {
                        DatagramPacket packet = new DatagramPacket(data, data.length, addr, port);
                        socket.send(packet);
                    } catch (IOException e) {
                    }
                }
                if (i < portsToSend.size() - 1) {
                    try { Thread.sleep(SLEEP_SHORT_MS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); return; }
                }
            }
            if (roundPass < 2) {
                try { Thread.sleep(SLEEP_LONG_MS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); return; }
            }
        }
    }

    public void cancel() {
        if (punching.compareAndSet(true, false)) {
            CompletableFuture<PunchResult> r = activeResult;
            if (r != null && completed.compareAndSet(false, true)) {
                r.completeExceptionally(new CancellationException("punch cancelled"));
            }
        }
        ScheduledFuture<?> tf = timeoutFuture;
        if (tf != null) {
            tf.cancel(false);
            timeoutFuture = null;
        }
        //debounce 与close()对称 也清socketGroup 避免socket泄漏
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
        java.util.List<Thread> rts = recvThreadsRef;
        if (rts != null) {
            for (Thread t : rts) {
                if (t != null) t.interrupt();
            }
        }
        if (sendThreadRef != null) sendThreadRef.interrupt();
    }

    public void stopPunch() {
        if (punching.compareAndSet(true, false)) {
            CompletableFuture<PunchResult> r = activeResult;
            if (r != null && completed.compareAndSet(false, true)) {
                r.completeExceptionally(new CancellationException("punch stopped"));
            }
        }
        ScheduledFuture<?> tf = timeoutFuture;
        if (tf != null) {
            tf.cancel(false);
            timeoutFuture = null;
        }
        java.util.List<Thread> rts = recvThreadsRef;
        if (rts != null) {
            for (Thread t : rts) {
                if (t != null) t.interrupt();
            }
        }
        if (sendThreadRef != null) sendThreadRef.interrupt();
        holeOpen.set(false);
        remoteReceived.set(false);
        localConfirmed.set(false);
    }

    public synchronized void updateTarget(String newIp, int newPort) {
        try {
            this.remoteAddress = InetAddress.getByName(newIp);
            this.remotePort = newPort;
            LOGGER.info("[UdpHolePuncher] Target updated to {}:{}", newIp, newPort);
        } catch (Exception e) {
            LOGGER.warn("[UdpHolePuncher] Target update failed: {}", e.getMessage());
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
        java.util.List<Thread> rts = recvThreadsRef;
        if (rts != null) {
            for (Thread t : rts) {
                if (t != null && t.isAlive()) {
                    try {
                        t.join(2000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
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
            CompletableFuture<PunchResult> r = activeResult;
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
        java.util.List<Thread> rts = recvThreadsRef;
        if (rts != null) {
            for (Thread t : rts) {
                if (t != null) t.interrupt();
            }
        }
        if (sendThreadRef != null) sendThreadRef.interrupt();
    }

    /**
     * 旧版多线程 punchMultiSocket 实现, 作为 NIO Selector 初始化失败时的回退路径.
     * 保留此方法确保极端环境下仍能工作 (如某些 JVM 不支持 DatagramChannel).
     */
    private CompletableFuture<PunchResult> punchMultiSocketLegacy(String remoteIp, int targetPort,
                                                                      java.util.List<UdpHolePuncher> socketGroup,
                                                                      java.util.concurrent.atomic.AtomicBoolean wonFlag) {
        CompletableFuture<PunchResult> result = new CompletableFuture<>();
        activeResult = result;
        this.socketGroup = socketGroup;
        final Object completionLock = new Object();
        final Consumer<InetSocketAddress> peerPunchCb = this.onPeerPunchReceived;
        //debounce 打洞诊断计数 接收PUNCH/ACK分别计数+起始时间 用于PunchResult
        final int[] recvPunchCounter = {0};
        final int[] recvAckCounter = {0};
        final long startTime = System.currentTimeMillis();
        final int socketsTried = socketGroup.size();

        byte[] data = new byte[3];
        data[0] = MAGIC[0];
        data[1] = MAGIC[1];
        data[2] = TYPE_PUNCH;

        int maxTotalCycles = PunchProfile.current().punchTimeoutMs / PUNCH_INTERVAL_MS;
        LOGGER.info("[UdpHolePuncher] Multi-socket send start (Legacy): target={}:{}, sockets={}, profile={}",
                remoteIp, targetPort, socketGroup.size(), PunchProfile.describe());

        java.util.List<Thread> recvThreads = new java.util.ArrayList<>();
        for (int si = 0; si < socketGroup.size(); si++) {
            final UdpHolePuncher sp = socketGroup.get(si);
            final int sIdx = si;
            DatagramSocket ssock = sp.getSocket();
            if (ssock == null || ssock.isClosed()) continue;
            try { ssock.setSoTimeout(PUNCH_SOCKET_TIMEOUT_MS); } catch (Exception ignored) {}
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
                            recvPunchCounter[0]++;
                            synchronized (completionLock) {
                                if (wonFlag.compareAndSet(false, true) && completed.compareAndSet(false, true)) {
                                    holeOpen.set(true);
                                    punching.set(false);
                                    sp.socketTransferred = true;
                                    remoteAddress = packet.getAddress();
                                    remotePort = packet.getPort();
                                    LOGGER.info("[UdpHolePuncher] socket#{} received PUNCH, punch success (Legacy)", sIdx);
                                    long elapsed = System.currentTimeMillis() - startTime;
                                    result.complete(PunchResult.success(ssock, socketsTried,
                                            recvPunchCounter[0], recvAckCounter[0], 0, elapsed));
                                }
                            }
                            sp.sendControlTo(TYPE_PUNCH_ACK, packet.getAddress(), packet.getPort());
                        } else if (type == TYPE_PUNCH_ACK) {
                            recvAckCounter[0]++;
                            synchronized (completionLock) {
                                if (wonFlag.compareAndSet(false, true) && completed.compareAndSet(false, true)) {
                                    holeOpen.set(true);
                                    punching.set(false);
                                    sp.socketTransferred = true;
                                    remoteAddress = packet.getAddress();
                                    remotePort = packet.getPort();
                                    LOGGER.info("[UdpHolePuncher] socket#{} received ACK, punch success (Legacy)", sIdx);
                                    long elapsed = System.currentTimeMillis() - startTime;
                                    result.complete(PunchResult.success(ssock, socketsTried,
                                            recvPunchCounter[0], recvAckCounter[0], 0, elapsed));
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
            }, "VoxLink-PunchRecv-Legacy-" + si);
            rt.setDaemon(true);
            recvThreads.add(rt);
            recvThreadsRef = recvThreads;
            rt.start();
        }
        recvThreadsRef = recvThreads.isEmpty() ? null : recvThreads;

        final boolean skipFirewallCheck = socketGroup.size() <= 1;
        Thread sendThread = new Thread(() -> {
            int cycles = 0;
            long sendStartMs = System.currentTimeMillis();
            while (punching.get() && !holeOpen.get() && cycles < maxTotalCycles) {
                if (!skipFirewallCheck && cycles >= PunchProfile.current().firewallDetectCycles && !remoteReceived.get()) {
                    long elapsed = System.currentTimeMillis() - sendStartMs;
                    LOGGER.warn("[UdpHolePuncher] Multi-socket firewall check (Legacy): sent {} cycles/{}ms no reply, UDP blocked", cycles, elapsed);
                    synchronized (completionLock) {
                        if (completed.compareAndSet(false, true)) {
                            punching.set(false);
                            result.complete(PunchResult.failure(socketsTried, recvPunchCounter[0],
                                    recvAckCounter[0], 0, elapsed, true));
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
            if (!holeOpen.get() && punching.get()) {
                synchronized (completionLock) {
                    if (completed.compareAndSet(false, true)) {
                        punching.set(false);
                        long elapsed = System.currentTimeMillis() - startTime;
                        result.complete(PunchResult.failure(socketsTried, recvPunchCounter[0],
                                recvAckCounter[0], 0, elapsed, false));
                    }
                }
            }
        }, "VoxLink-PunchSend-Legacy");
        sendThread.setDaemon(true);
        sendThreadRef = sendThread;
        sendThread.start();

        ScheduledFuture<?> tf = PUNCH_TIMEOUT_SCHEDULER.schedule(() -> {
            if (!punching.get()) return;
            synchronized (completionLock) {
                if (completed.compareAndSet(false, true)) {
                    punching.set(false);
                    long elapsed = System.currentTimeMillis() - startTime;
                    result.complete(PunchResult.failure(socketsTried, recvPunchCounter[0],
                            recvAckCounter[0], 0, elapsed, false));
                }
            }
        }, PunchProfile.current().punchTimeoutMs + EXTRA_WAIT_MS, TimeUnit.MILLISECONDS);
        timeoutFuture = tf;
        P2PBridge.registerPendingUdpTimeout(tf);

        return result;
    }
}
