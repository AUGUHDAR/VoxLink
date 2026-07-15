package icu.wuhui.voxlink.network;

import icu.wuhui.voxlink.VoxLinkMod;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * P2P玩家互助中继: 当两个对称NAT玩家无法直连时, 通过房间内非对称NAT玩家中转数据。
 * 服务器零负载, 中转节点可开关, 支持多中继负载分拆。
 * 
 * 架构:
 *   Sym1 ──(P2P)──► Relay(Cone) ──(P2P)──► Sym2
 *   RelayBridge 在 Relay 侧把两个 transport 的流互相转发
 */
public class RelayBridge {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("VoxLink-Relay");
    private static final int MONITOR_INITIAL_DELAY_SEC = 5;
    private static final int MONITOR_INTERVAL_SEC = 5;
    private static final int RELAY_BUFFER_SIZE = 65536;

    private final ScheduledExecutorService scheduler;
    private final Map<String, RelaySession> activeRelays = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledFuture<?> monitorTask;

    private static volatile RelayBridge instance;

    public static RelayBridge getInstance(ScheduledExecutorService scheduler) {
        if (instance == null) {
            synchronized (RelayBridge.class) {
                if (instance == null) {
                    instance = new RelayBridge(scheduler);
                }
            }
        }
        return instance;
    }

    private RelayBridge(ScheduledExecutorService scheduler) {
        this.scheduler = scheduler;
    }

    /**
     * 作为中转节点: 在两个已连接的peer之间转发数据
     */
    public void startRelay(String peerAId, String peerBId,
                           ReliableUdpTransport transportA, ReliableUdpTransport transportB) {
        String relayKey = peerAId + "<->" + peerBId;
        RelaySession session = new RelaySession(peerAId, peerBId, transportA, transportB);
        if (activeRelays.putIfAbsent(relayKey, session) != null) {
            LOGGER.info("[Relay] 中继会话已存在: {}", relayKey);
            return;
        }
        session.startForwarding();
        LOGGER.info("[Relay] 中继启动: {} (A={}, B={})", relayKey, peerAId, peerBId);

        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.translatable("voxlink.relay.started"));
        }

        if (running.compareAndSet(false, true)) {
            monitorTask = scheduler.scheduleAtFixedRate(this::monitorRelays, MONITOR_INITIAL_DELAY_SEC, MONITOR_INTERVAL_SEC, TimeUnit.SECONDS);
        }
    }

    public void stopRelay(String peerAId, String peerBId) {
        String relayKey = peerAId + "<->" + peerBId;
        RelaySession session = activeRelays.remove(relayKey);
        if (session != null) {
            session.stop();
            LOGGER.info("[Relay] 中继停止: {}", relayKey);
        }
    }

    public void stopAll() {
        for (Map.Entry<String, RelaySession> entry : activeRelays.entrySet()) {
            entry.getValue().stop();
            LOGGER.info("[Relay] 中继停止: {}", entry.getKey());
        }
        activeRelays.clear();
        if (monitorTask != null) {
            monitorTask.cancel(false);
            monitorTask = null;
        }
        running.set(false);
    }

    public int getActiveRelayCount() {
        return activeRelays.size();
    }

    public boolean isRelayingFor(String peerId) {
        for (RelaySession session : activeRelays.values()) {
            if (session.peerAId.equals(peerId) || session.peerBId.equals(peerId)) {
                return true;
            }
        }
        return false;
    }

    public int getRelayCountForPeer(String peerId) {
        int count = 0;
        for (RelaySession session : activeRelays.values()) {
            if (session.peerAId.equals(peerId) || session.peerBId.equals(peerId)) {
                count++;
            }
        }
        return count;
    }

    private void monitorRelays() {
        java.util.List<String> deadRelays = new java.util.ArrayList<>();
        activeRelays.entrySet().removeIf(entry -> {
            RelaySession s = entry.getValue();
            if (!s.transportA.isConnected() || !s.transportB.isConnected()) {
                LOGGER.warn("[Relay] 中继会话断开: {}", entry.getKey());
                deadRelays.add(entry.getKey());
                s.stop();
                return true;
            }
            return false;
        });

        if (activeRelays.isEmpty() && running.compareAndSet(true, false)) {
            if (monitorTask != null) {
                monitorTask.cancel(false);
                monitorTask = null;
            }
            LOGGER.info("[Relay] 所有无中继会话, 监控停止");
        }

        // 通知 ConnectionManager 中继断开（触发自动切换）
        if (!deadRelays.isEmpty()) {
            for (String key : deadRelays) {
                String[] parts = key.split("<->");
                if (parts.length == 2) {
                    try {
                        icu.wuhui.voxlink.room.ConnectionManager cm = icu.wuhui.voxlink.room.ConnectionManager.getInstance();
                        if (cm != null) cm.onRelayDisconnected(parts[0], parts[1]);
                    } catch (Exception ignored) {}
                }
            }
        }
    }

    private static class RelaySession {
        final String peerAId;
        final String peerBId;
        final ReliableUdpTransport transportA;
        final ReliableUdpTransport transportB;
        volatile boolean forwarding = true;
        Thread threadAB;
        Thread threadBA;

        RelaySession(String peerAId, String peerBId,
                     ReliableUdpTransport transportA, ReliableUdpTransport transportB) {
            this.peerAId = peerAId;
            this.peerBId = peerBId;
            this.transportA = transportA;
            this.transportB = transportB;
        }

        void startForwarding() {
            InputStream inA = transportA.getInputStream();
            OutputStream outA = transportA.getOutputStream();
            InputStream inB = transportB.getInputStream();
            OutputStream outB = transportB.getOutputStream();

            threadAB = new Thread(() -> {
                byte[] buf = new byte[RELAY_BUFFER_SIZE];
                while (forwarding) {
                    try {
                        int n = inA.read(buf);
                        if (n > 0) outB.write(buf, 0, n);
                        else if (n < 0) break;
                    } catch (Exception e) {
                        if (forwarding) LOGGER.debug("[Relay] A→B异常: {}", e.getMessage());
                        break;
                    }
                }
                forwarding = false;
            }, "VoxLink-Relay-A2B");
            threadAB.setDaemon(true);

            threadBA = new Thread(() -> {
                byte[] buf = new byte[RELAY_BUFFER_SIZE];
                while (forwarding) {
                    try {
                        int n = inB.read(buf);
                        if (n > 0) outA.write(buf, 0, n);
                        else if (n < 0) break;
                    } catch (Exception e) {
                        if (forwarding) LOGGER.debug("[Relay] B→A异常: {}", e.getMessage());
                        break;
                    }
                }
                forwarding = false;
            }, "VoxLink-Relay-B2A");
            threadBA.setDaemon(true);

            threadAB.start();
            threadBA.start();
        }

        void stop() {
            forwarding = false;
            if (threadAB != null) threadAB.interrupt();
            if (threadBA != null) threadBA.interrupt();
        }
    }
}
