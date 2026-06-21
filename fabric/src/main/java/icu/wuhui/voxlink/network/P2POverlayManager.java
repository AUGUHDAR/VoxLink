package icu.wuhui.voxlink.network;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import icu.wuhui.voxlink.VoxLinkMod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class P2POverlayManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("voxlink-overlay");
    private static final Gson GSON = new Gson();
    private static final int MAX_PACKET_SIZE = 32768;
    private static final int READ_BUFFER_SIZE = 65536;
    private static final int PING_INTERVAL_SEC = 2;
    private static final int MAX_PING_FAILURES = 5;

    public enum Role {
        NONE,
        CHAIN_HEAD,
        CHAIN_MIDDLE,
        CHAIN_TAIL
    }

    public interface PacketHandler {
        void onDataReceived(String from, String priority, JsonObject payload);
        void onLinkReady();
        void onLinkLost(String reason);
    }

    private DatagramSocket socket;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<Role> role = new AtomicReference<>(Role.NONE);

    private final AtomicReference<InetSocketAddress> upstreamAddr = new AtomicReference<>(null);
    private volatile String upstreamId;

    private final AtomicReference<InetSocketAddress> downstreamAddr = new AtomicReference<>(null);
    private volatile String downstreamId;

    private String nodeId;
    private final int localPort;
    private final AtomicInteger packetSeq = new AtomicInteger(0);

    private final ConcurrentHashMap<String, Boolean> seenSeq = new ConcurrentHashMap<>();

    private ExecutorService ioExecutor;
    private ScheduledExecutorService pingScheduler;
    private volatile PacketHandler handler;

    private final AtomicInteger upstreamLatency = new AtomicInteger(-1);
    private final AtomicInteger downstreamLatency = new AtomicInteger(-1);
    private final AtomicReference<PendingPing> pendingUpstreamPing = new AtomicReference<>(null);
    private final AtomicReference<PendingPing> pendingDownstreamPing = new AtomicReference<>(null);
    private final AtomicInteger upstreamPingFailures = new AtomicInteger(0);
    private final AtomicInteger downstreamPingFailures = new AtomicInteger(0);

    private static class PendingPing {
        final int seq;
        final long timestamp;
        PendingPing(int seq, long timestamp) { this.seq = seq; this.timestamp = timestamp; }
    }

    public P2POverlayManager(String nodeId, int port) {
        this.nodeId = (nodeId != null) ? nodeId : "node_" + System.identityHashCode(this);
        this.localPort = port;
    }

    public void start(PacketHandler handler) throws IOException {
        if (running.get()) return;
        this.handler = handler;
        try {
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(localPort));
            socket.setSoTimeout(1000);
        } catch (SocketException e) {
            LOGGER.error("overlay UDP端口{}绑定失败: {}", localPort, e.getMessage());
            throw new IOException("Failed to bind overlay socket", e);
        }
        try {
            ioExecutor = Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "VoxLink-Overlay-IO");
                t.setDaemon(true);
                return t;
            });
            pingScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "VoxLink-Overlay-Ping");
                t.setDaemon(true);
                return t;
            });
        } catch (Exception e) {
            if (ioExecutor != null) ioExecutor.shutdownNow();
            if (pingScheduler != null) pingScheduler.shutdownNow();
            if (socket != null && !socket.isClosed()) socket.close();
            throw new IOException("Failed to create executors", e);
        }
        running.set(true);
        try {
            ioExecutor.submit(this::readLoop);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            LOGGER.error("overlay读循环提交失败: {}", e.getMessage());
            stop();
            throw new IOException("Failed to submit overlay read loop", e);
        }
        pingScheduler.scheduleAtFixedRate(this::pingTask, PING_INTERVAL_SEC, PING_INTERVAL_SEC, TimeUnit.SECONDS);
        LOGGER.info("P2P overlay启动，端口{}，节点{}", socket.getLocalPort(), nodeId);
    }

    public void connectUpstream(String peerId, String host, int port) {
        if (!running.get()) return;
        if (host == null) return;
        InetSocketAddress addr = new InetSocketAddress(host, port);
        upstreamAddr.set(addr);
        upstreamId = peerId;
        if (downstreamAddr.get() == null) {
            role.set(Role.CHAIN_TAIL);
        } else {
            role.set(Role.CHAIN_MIDDLE);
        }
        LOGGER.info("上游连接: {} at {}:{} 角色{}", peerId, host, port, role.get());

        sendHandshake(peerId, addr);
    }

    public void setDownstream(String peerId, String host, int port) {
        if (!running.get()) return;
        if (host == null) return;
        InetSocketAddress addr = new InetSocketAddress(host, port);
        downstreamAddr.set(addr);
        downstreamId = peerId;
        LOGGER.info("下游设置: {} at {}:{}", peerId, host, port);

        if (role.get() == Role.CHAIN_HEAD || role.get() == Role.CHAIN_MIDDLE) {
        } else if (upstreamAddr.get() != null) {
            role.set(Role.CHAIN_MIDDLE);
        } else {
            role.set(Role.CHAIN_TAIL);
        }
    }

    public void becomeHead(String downstreamPeerId, String downstreamHost, int downstreamPort) {
        if (!running.get()) return;
        role.set(Role.CHAIN_HEAD);
        upstreamAddr.set(null);
        upstreamId = null;

        if (downstreamPeerId != null && downstreamHost != null) {
            InetSocketAddress addr = new InetSocketAddress(downstreamHost, downstreamPort);
            downstreamAddr.set(addr);
            downstreamId = downstreamPeerId;
            sendHandshake(downstreamPeerId, addr);
        } else {
            downstreamAddr.set(null);
            downstreamId = null;
        }
        LOGGER.info("成为链头，下游: {}", downstreamPeerId != null ? downstreamPeerId : "none");
    }

    public void switchToDirectMode() {
        role.set(Role.NONE);
        upstreamAddr.set(null);
        upstreamId = null;
        downstreamAddr.set(null);
        downstreamId = null;
        LOGGER.info("切换到直连模式");
    }

    public void sendData(String targetNodeId, String priority, JsonObject payload) {
        if (!running.get()) return;
        if (nodeId == null || nodeId.isEmpty()) {
            LOGGER.warn("发不了P2P数据，nodeId没设");
            return;
        }
        JsonObject packet = new JsonObject();
        packet.addProperty("type", "data_relay");
        packet.addProperty("seq", packetSeq.incrementAndGet());
        packet.addProperty("from", nodeId);
        if (targetNodeId != null) {
            packet.addProperty("to", targetNodeId);
        }
        packet.addProperty("priority", priority);
        packet.add("payload", payload);

        sendPacket(packet);
    }

    public int getUpstreamLatency() {
        return upstreamLatency.get();
    }

    public int getDownstreamLatency() {
        return downstreamLatency.get();
    }

    public Role getRole() {
        return role.get();
    }

    public void setNodeId(String id) {
        this.nodeId = id;
    }

    public void stop() {
        running.set(false);
        upstreamAddr.set(null);
        downstreamAddr.set(null);
        upstreamId = null;
        downstreamId = null;
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        socket = null;
        if (pingScheduler != null) {
            pingScheduler.shutdownNow();
            pingScheduler = null;
        }
        if (ioExecutor != null) {
            ioExecutor.shutdownNow();
            ioExecutor = null;
        }
        role.set(Role.NONE);
        seenSeq.clear();
        LOGGER.info("P2P overlay停了");
    }

    public int getLocalPort() {
        if (socket != null && !socket.isClosed()) {
            try { return socket.getLocalPort(); } catch (Exception e) { return localPort; }
        }
        return localPort;
    }

    private void readLoop() {
        byte[] buf = new byte[READ_BUFFER_SIZE];
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet);
                byte[] data = new byte[packet.getLength()];
                System.arraycopy(packet.getData(), packet.getOffset(), data, 0, packet.getLength());
                ioExecutor.submit(() -> processPacket(data, packet.getSocketAddress()));
            } catch (SocketException e) {
                if (!running.get()) {
                    break;
                }
                LOGGER.debug("overlay读socket临时错误: {}", e.getMessage());
            } catch (Exception e) {
                if (running.get()) {
                    LOGGER.debug("overlay读错误: {}", e.getMessage());
                }
            }
        }
    }

    private void processPacket(byte[] data, java.net.SocketAddress fromAddr) {
        try {
            if (!(fromAddr instanceof InetSocketAddress)) {
                return;
            }
            InetSocketAddress inetAddr = (InetSocketAddress) fromAddr;

            String json = decompress(data);
            if (json == null) return;
            JsonObject packet = GSON.fromJson(json, JsonObject.class);

            String type = packet.has("type") ? packet.get("type").getAsString() : "";
            String from = packet.has("from") ? packet.get("from").getAsString() : "";

            switch (type) {
                case "handshake":
                    handleHandshake(packet, inetAddr);
                    break;
                case "ping":
                    handlePing(packet, from, inetAddr);
                    break;
                case "pong":
                    handlePong(packet);
                    break;
                case "data_relay":
                    String fromDir = determineDirection(inetAddr);
                    handleDataRelay(packet, fromDir, inetAddr);
                    break;
                default:
                    LOGGER.debug("未知overlay包类型: {}", type);
            }
        } catch (Exception e) {
            LOGGER.debug("overlay包处理失败: {}", e.getMessage());
        }
    }

    private void handleHandshake(JsonObject packet, InetSocketAddress fromAddr) {
        String peerId = packet.has("from") ? packet.get("from").getAsString() : "";
        LOGGER.info("收到下游握手: {}", peerId);
        if (role.get() == Role.CHAIN_HEAD || role.get() == Role.CHAIN_MIDDLE) {
            downstreamAddr.set(fromAddr);
            downstreamId = peerId;
        }
        if (handler != null) {
            handler.onLinkReady();
        }
    }

    private void handlePing(JsonObject packet, String from, InetSocketAddress senderAddr) {
        JsonObject pong = new JsonObject();
        pong.addProperty("type", "pong");
        pong.addProperty("from", nodeId);
        pong.addProperty("seq", packet.has("seq") ? packet.get("seq").getAsLong() : 0);
        if (packet.has("dir")) {
            pong.addProperty("dir", packet.get("dir").getAsString());
        }
        sendPacketTo(pong, senderAddr);
    }

    private void handlePong(JsonObject packet) {
        long now = System.currentTimeMillis();
        int pingSeq = packet.has("seq") ? packet.get("seq").getAsInt() : -1;
        String dir = packet.has("dir") ? packet.get("dir").getAsString() : "up";

        if ("down".equals(dir)) {
            PendingPing pending = pendingDownstreamPing.get();
            if (pending != null && pingSeq == pending.seq) {
                int latency = (int) (now - pending.timestamp);
                downstreamLatency.set(latency);
                pendingDownstreamPing.compareAndSet(pending, null);
                downstreamPingFailures.set(0);
            }
        } else {
            PendingPing pending = pendingUpstreamPing.get();
            if (pending != null && pingSeq == pending.seq) {
                int latency = (int) (now - pending.timestamp);
                upstreamLatency.set(latency);
                pendingUpstreamPing.compareAndSet(pending, null);
                upstreamPingFailures.set(0);
            }
        }
    }

    private void handleDataRelay(JsonObject packet, String fromDirection, InetSocketAddress fromAddr) {
        String from = packet.has("from") ? packet.get("from").getAsString() : "";
        int seq = packet.has("seq") ? packet.get("seq").getAsInt() : 0;
        String dedupKey = from + ":" + seq;
        if (seenSeq.putIfAbsent(dedupKey, Boolean.TRUE) != null) {
            return;
        }
        if (seenSeq.size() > 1000) {
            int toRemove = seenSeq.size() / 2;
            java.util.Iterator<String> it = seenSeq.keySet().iterator();
            for (int i = 0; i < toRemove && it.hasNext(); i++) {
                it.next();
                it.remove();
            }
        }

        String to = packet.has("to") && !packet.get("to").isJsonNull() ? packet.get("to").getAsString() : null;
        String priority = packet.has("priority") ? packet.get("priority").getAsString() : "L2";
        JsonObject payload = packet.has("payload") ? packet.getAsJsonObject("payload") : new JsonObject();

        if (to == null || to.equals(nodeId)) {
            if (handler != null) {
                handler.onDataReceived(from, priority, payload);
            }
        }

        if (to == null || !to.equals(nodeId)) {
            if ("upstream".equals(fromDirection)) {
                forwardToDownstream(packet);
            } else if ("downstream".equals(fromDirection)) {
                forwardToUpstream(packet);
            } else {
                InetSocketAddress up = upstreamAddr.get();
                InetSocketAddress down = downstreamAddr.get();
                if (up != null && up.equals(fromAddr)) {
                    forwardToDownstream(packet);
                } else if (down != null && down.equals(fromAddr)) {
                    forwardToUpstream(packet);
                }
            }
        }
    }

    private void forwardToDownstream(JsonObject packet) {
        InetSocketAddress down = downstreamAddr.get();
        if (down != null) {
            sendPacketTo(packet, down);
        }
    }

    private void forwardToUpstream(JsonObject packet) {
        InetSocketAddress up = upstreamAddr.get();
        if (up != null) {
            sendPacketTo(packet, up);
        }
    }

    private String determineDirection(InetSocketAddress fromAddr) {
        InetSocketAddress up = upstreamAddr.get();
        InetSocketAddress down = downstreamAddr.get();
        if (up != null && up.equals(fromAddr)) {
            return "upstream";
        }
        if (down != null && down.equals(fromAddr)) {
            return "downstream";
        }
        return "unknown";
    }

    private void sendHandshake(String peerId, InetSocketAddress addr) {
        JsonObject handshake = new JsonObject();
        handshake.addProperty("type", "handshake");
        handshake.addProperty("from", nodeId);
        sendPacketTo(handshake, addr);
    }

    private void pingTask() {
        if (!running.get() || role.get() == Role.NONE) return;

        InetSocketAddress up = upstreamAddr.get();
        if (up != null) {
            PendingPing currentUp = pendingUpstreamPing.get();
            if (currentUp != null) {
                int failures = upstreamPingFailures.incrementAndGet();
                if (failures >= MAX_PING_FAILURES) {
                    LOGGER.warn("上游链路断了，连续{}次ping失败", failures);
                    upstreamPingFailures.set(0);
                    pendingUpstreamPing.compareAndSet(currentUp, null);
                    if (handler != null) {
                        handler.onLinkLost("upstream_timeout");
                    }
                }
            } else {
                upstreamPingFailures.set(0);
            }
            int seq = packetSeq.incrementAndGet();
            pendingUpstreamPing.set(new PendingPing(seq, System.currentTimeMillis()));
            JsonObject ping = new JsonObject();
            ping.addProperty("type", "ping");
            ping.addProperty("from", nodeId);
            ping.addProperty("seq", seq);
            ping.addProperty("dir", "up");
            sendPacketTo(ping, up);
        }

        InetSocketAddress down = downstreamAddr.get();
        if (down != null) {
            PendingPing currentDown = pendingDownstreamPing.get();
            if (currentDown != null) {
                int failures = downstreamPingFailures.incrementAndGet();
                if (failures >= MAX_PING_FAILURES) {
                    LOGGER.warn("下游链路断了，连续{}次ping失败", failures);
                    downstreamPingFailures.set(0);
                    pendingDownstreamPing.compareAndSet(currentDown, null);
                    if (handler != null) {
                        handler.onLinkLost("downstream_timeout");
                    }
                }
            } else {
                downstreamPingFailures.set(0);
            }
            int seq = packetSeq.incrementAndGet();
            pendingDownstreamPing.set(new PendingPing(seq, System.currentTimeMillis()));
            JsonObject ping = new JsonObject();
            ping.addProperty("type", "ping");
            ping.addProperty("from", nodeId);
            ping.addProperty("seq", seq);
            ping.addProperty("dir", "down");
            sendPacketTo(ping, down);
        }
    }

    private void sendPacket(JsonObject packet) {
        Role r = role.get();
        if (r == Role.CHAIN_HEAD) {
            sendPacketToDownstream(packet);
        } else if (r == Role.CHAIN_TAIL) {
            sendPacketToUpstream(packet);
        } else if (r == Role.CHAIN_MIDDLE) {
            String to = packet.has("to") && !packet.get("to").isJsonNull() ? packet.get("to").getAsString() : null;
            if (to == null) {
                sendPacketToUpstream(packet);
                sendPacketToDownstream(packet);
            } else if (to.equals(upstreamId)) {
                sendPacketToUpstream(packet);
            } else if (to.equals(downstreamId)) {
                sendPacketToDownstream(packet);
            } else {
                sendPacketToUpstream(packet);
                sendPacketToDownstream(packet);
            }
        }
    }

    private void sendPacketToUpstream(JsonObject packet) {
        InetSocketAddress up = upstreamAddr.get();
        if (up != null) {
            sendPacketTo(packet, up);
        }
    }

    private void sendPacketToDownstream(JsonObject packet) {
        InetSocketAddress down = downstreamAddr.get();
        if (down != null) {
            sendPacketTo(packet, down);
        }
    }

    private void sendPacketTo(JsonObject packet, InetSocketAddress addr) {
        try {
            String json = GSON.toJson(packet);
            byte[] payload = compress(json);
            byte[] framed = framePacket(payload);
            DatagramPacket dp = new DatagramPacket(framed, framed.length, addr);
            socket.send(dp);
        } catch (Exception e) {
            LOGGER.debug("发包到{}失败: {}", addr, e.getMessage());
        }
    }

    static byte[] compress(String data) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (GZIPOutputStream gos = new GZIPOutputStream(bos)) {
                gos.write(data.getBytes(StandardCharsets.UTF_8));
            }
            return bos.toByteArray();
        } catch (IOException e) {
            LOGGER.error("overlay数据压缩失败: {}", e.getMessage());
            throw new RuntimeException("Compression failed", e);
        }
    }

    static String decompress(byte[] data) {
        if (data.length < 4) return null;
        try {
            int payloadLen = ByteBuffer.wrap(data, 0, 4).getInt();
            if (payloadLen <= 0 || payloadLen > data.length - 4) return null;

            ByteArrayInputStream bis = new ByteArrayInputStream(data, 4, payloadLen);
            try (GZIPInputStream gis = new GZIPInputStream(bis)) {
                byte[] decompressed = gis.readAllBytes();
                return new String(decompressed, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            return null;
        }
    }

    static byte[] framePacket(byte[] compressed) {
        ByteBuffer buf = ByteBuffer.allocate(4 + compressed.length);
        buf.putInt(compressed.length);
        buf.put(compressed);
        return buf.array();
    }
}