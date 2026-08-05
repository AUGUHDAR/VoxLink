package icu.wuhui.voxlink.room;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.network.AddressBlacklist;
import icu.wuhui.voxlink.network.ConnectionFallback;
import icu.wuhui.voxlink.network.ConnectionFallback.ConnectionMode;
import icu.wuhui.voxlink.network.ConnectionHelper;
import icu.wuhui.voxlink.network.P2PBridge;
import icu.wuhui.voxlink.network.RelayBridge;
import icu.wuhui.voxlink.network.SignalingClient;
import icu.wuhui.voxlink.network.StunProbe;
import icu.wuhui.voxlink.network.UPnPManager;
import icu.wuhui.voxlink.network.UdpHolePuncher;
import icu.wuhui.voxlink.network.PunchProfile;
import icu.wuhui.voxlink.network.NatClass;
import icu.wuhui.voxlink.network.PunchResult;
import icu.wuhui.voxlink.network.PunchFailureClassifier;
import icu.wuhui.voxlink.network.PunchStrategy;
import icu.wuhui.voxlink.network.PunchStrategySelector;
import icu.wuhui.voxlink.network.PunchTuner;
import icu.wuhui.voxlink.network.ReliableUdpTransport;
import icu.wuhui.voxlink.terracotta.RoomCodeRouter;
import icu.wuhui.voxlink.terracotta.TerracottaBinary;
import icu.wuhui.voxlink.terracotta.TerracottaManager;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;


public class ConnectionManager {
    private final RoomManager roomManager;
    private final SignalingClient signalingClient;
    private final ScheduledExecutorService scheduler;
    private final java.util.concurrent.ExecutorService punchExecutor;

    private final ConcurrentHashMap<String, UdpHolePuncher> activeHolePunchers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReliableUdpTransport> activeUdpTransports = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReliableUdpTransport> oldUdpTransports = new ConcurrentHashMap<>();
    private static final int ICE_POOL_RETAIN_SECONDS = 12;
    private static final int HOST_MULTI_MIN = 5;
    private static final int HOST_MULTI_DEFAULT = 20;
    private static final int TCP_CONNECT_TIMEOUT_MS = 5000;
    private static final int SHORT_SLEEP_MS = 100;
    private static final int PORT_RANGE_DEFAULT = 30;
    private static final int EXTRA_TIMEOUT_SEC = 5;
    private static final int AWAIT_TIMEOUT_SEC = 20;
    private static final int RELAY_GRACE_MS = 3000;
    private static final int PORT_RANGE_WIDE = 50;
    private static final int MAX_DELAY_MS = 6000;
    private static final int PROBE_SOCKET_TIMEOUT_MS = 1000;
    private static final int PORT_RANGE_MAX = 100;
    private static final int MAX_FALLBACK_LOOPS = 200;
    private static final int FALLBACK_SLEEP_MS = 300;
    private static final int MAX_RELAY_CANDIDATES = 3;
    private static final int SHORT_TIMEOUT_SEC = 8;
    private static final int RELAY_SOCKET_COUNT = 5;
    private static final int RELAY_SETUP_TIMEOUT_SEC = 15;
    private static final int POLL_INTERVAL_MS = 500;
    private static final int AWAIT_TERM_SEC = 2;
    private static final int EASY_SYM_PORT_RANGE = 20;
    private static final int MIN_PORT_RANGE = 3;
    private static final int JOINER_SYM_SOCKET_COUNT = 50;
    private static final int REVERSE_PUNCH_TIMEOUT_SEC = 20;
    private static final int STUN_PROBE_TIMEOUT_SEC = 10;
    private static final int RTT_SYNC_MAX_DELAY_MS = 8000;
    private static final byte PUNCH_ACK_TYPE = 0x02;
    private final java.util.Set<String> failedRelayPeers = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final AtomicReference<String> currentRelayPeer = new AtomicReference<>(null);
    private volatile ScheduledFuture<?> relayFailoverTask = null;
    //debounce 手动relay: 玩家点击按钮触发 非自动
    private volatile int nextRelayEligibleRound = 0;
    private volatile boolean manualRelayInProgress = false;
    private final AtomicBoolean connectionCycleActive = new AtomicBoolean(false);
    private final AtomicBoolean reversePunchAttempted = new AtomicBoolean(false);
    private final AtomicBoolean connectionWon = new AtomicBoolean(false);
    //debounce 跟踪所有进行中的TCP兜底 连接成功后统一cancel 避免残留重试空打(该关的及时关)
    private final java.util.List<ConnectionFallback> activeFallbacks = new java.util.concurrent.CopyOnWriteArrayList<>();

    private ConnectionFallback trackFallback(ConnectionFallback f) {
        activeFallbacks.add(f);
        return f;
    }

    private void cancelAllFallbacks() {
        for (ConnectionFallback f : activeFallbacks) {
            try { f.cancel(); } catch (Exception ignored) {}
        }
        activeFallbacks.clear();
    }

    //debounce 阶段四: 持续重试至玩家取消 1.0.7+双方有CAP_CONTINUOUS_RETRY才启用 老版本走原maxCycles放弃
    //debounce 无限重试 对齐陶瓦逻辑 未打通前不罢休 仅玩家退出才停
    private static final int CONTINUOUS_RETRY_MAX_ROUNDS = Integer.MAX_VALUE;
    private final AtomicBoolean continuousRetryCancelled = new AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicInteger continuousRetryRound = new java.util.concurrent.atomic.AtomicInteger(0);
    //debounce 失败历史感知: 连续同原因失败计数 连续3次切策略避免陷入同一失败模式
    private volatile icu.wuhui.voxlink.network.PunchFailureClassifier.FailureReason lastFailureReason;
    private final java.util.concurrent.atomic.AtomicInteger consecutiveFailureCount = new java.util.concurrent.atomic.AtomicInteger(0);
    //debounce 阶段三: ICE Restart快速重连 1.0.7+双方有CAP_ICE_RESTART才启用 老版本收到信号忽略
    private static final int ICE_RESTART_MAX_ATTEMPTS = 3;
    private static final long ICE_RESTART_COOLDOWN_MS = 5000;
    private final java.util.concurrent.atomic.AtomicInteger iceRestartAttempts = new java.util.concurrent.atomic.AtomicInteger(0);
    private final java.util.concurrent.atomic.AtomicLong lastIceRestartTimeMs = new java.util.concurrent.atomic.AtomicLong(0);
    //debounce 阶段三: 保存最近一次runConnectionCycle参数 供ICE Restart重新触发
    private volatile RoomManager.RoomState savedConnectionState;
    private volatile String savedConnectionFrom = "";
    private volatile String savedConnectionHostIpv6;
    private volatile String savedConnectionHostIp;
    private volatile int savedConnectionHostPort;
    private volatile String savedConnectionHostMappedIp;
    private volatile int savedConnectionHostMappedPort;
    private static final int CONNECTION_TIMEOUT_SECONDS = 45;
    private static final int SYMMETRIC_CONNECTION_TIMEOUT_SECONDS = 75;
    private volatile ScheduledFuture<?> connectionTimeoutFuture;
    //debounce 安全兜底: 防止connectionCycleActive卡死导致永久停在"探测网络"
    private volatile ScheduledFuture<?> connectionCycleSafetyTimeout;
    private volatile long connectionStartTimeMs;
    private volatile int connectionTimeoutSec;
    private volatile StunProbe.ProbeResult stunProbeResult;
    private final AtomicReference<CompletableFuture<StunProbe.ProbeResult>> stunProbeFutureRef = new AtomicReference<>();
    private volatile String lastPunchInfoId = "";
    private volatile boolean hostPunching = false;
    //debounce 双P2P时追踪VoxLink桥建立 等桥建好才算赢 语义对齐陶瓦guest-ok
    private volatile CompletableFuture<Void> dualVoxlinkBridgeFuture;
    private static final int DUAL_VOXLINK_BRIDGE_TIMEOUT_SEC = 120;
    //debounce 双P2P竞速状态 集中管理胜负判定 避免类级标志与局部CAS冲突
    private volatile boolean dualRaceActive = false;
    private volatile boolean terracottaWon = false;
    private volatile boolean voxlinkWon = false;
    private volatile boolean voxlinkSideDisabled = false;
    private volatile CompletableFuture<Void> dualResultRef;
    private final java.util.concurrent.atomic.AtomicInteger dualFailedCount = new java.util.concurrent.atomic.AtomicInteger(0);
    //debounce 智能打洞4层闭环 上次punch结果+NAT分类+relay预查
    private volatile PunchResult lastPunchResult;
    private volatile NatClass localNatClass = NatClass.UNKNOWN;
    private volatile NatClass remoteNatClass = NatClass.UNKNOWN;
    private volatile CompletableFuture<Void> relayPrefetchFuture;

    private static final int MAX_CONNECTION_CYCLES = 3;
    private static final int FALLBACK_CYCLES = 3;
    private static final int SYMMETRIC_NAT_CYCLES = 2;
    private static final int UDP_PUNCH_TIMEOUT_S = 8;
    //debounce punch_info等待超时 对端不回punch_info时清理host socket 防后续join永久排队
    private static final int PUNCH_INFO_WAIT_TIMEOUT_S = 15;
    private static final int CYCLE_RETRY_DELAY_MS = 1000;
    //指数退避
    private static final long[] BACKOFF_DELAYS_MS = {
        1000, 2000, 4000
    };
    private static final int UDP_PUNCH_MAX_ATTEMPTS = 3;
    private static final int UDP_PUNCH_RETRY_DELAY_MS = 800;
    //debounce join_request排队重试间隔 punch 8s超时+2s余量
    private static final int JOIN_QUEUE_RETRY_SEC = 10;
    private static final int BIRTHDAY_SOCKET_COUNT = 32;
    private static final int HARD_SYM_SOCKET_COUNT = 84;

    //地址黑名单: InetSocketAddress级, UDP3连失败1h, 直连失败5min
    private final AddressBlacklist addressBlacklist = new AddressBlacklist();

    // 修复6: 生日攻击socket预创建与复用, 对齐EasyTier prepare_udp_array
    // 30秒窗口内复用socket数组, 避免每次打洞新建84 socket + 84次STUN探测超时
    private volatile UdpSocketArray cachedUdpArray;
    private static final long UDP_ARRAY_REUSE_WINDOW_MS = 30_000L;

    // 修复6: socket数组复用容器, 避免重复STUN探测
    private static class UdpSocketArray {
        final java.util.List<UdpHolePuncher> punchers;
        final java.util.List<StunProbe.PublicMappedAddress> mappedAddrs;
        final long createTime;
        final boolean isEasySym;

        UdpSocketArray(java.util.List<UdpHolePuncher> punchers,
                       java.util.List<StunProbe.PublicMappedAddress> mappedAddrs,
                       boolean isEasySym) {
            this.punchers = new java.util.ArrayList<>(punchers);
            this.mappedAddrs = new java.util.ArrayList<>(mappedAddrs);
            this.createTime = System.currentTimeMillis();
            this.isEasySym = isEasySym;
        }

        boolean isReusable(int requiredSize, boolean requiredEasySym, long now) {
            if (now - createTime >= UDP_ARRAY_REUSE_WINDOW_MS) return false;
            if (isEasySym != requiredEasySym) return false;
            if (punchers.size() < requiredSize) return false;
            for (UdpHolePuncher p : punchers) {
                if (p.getSocket() == null || p.getSocket().isClosed()) return false;
                if (p.isPunching()) return false;
            }
            return true;
        }

        void close() {
            for (UdpHolePuncher p : punchers) {
                try { p.close(); } catch (Exception ignored) {}
            }
            punchers.clear();
            mappedAddrs.clear();
        }
    }

    // 修复6: 获取或创建可复用的socket数组
    private UdpSocketArray getOrCreateUdpArray(int requiredSize, boolean isEasySym, java.util.List<String> stunUrls) {
        long now = System.currentTimeMillis();
        if (cachedUdpArray != null) {
            if (cachedUdpArray.isReusable(requiredSize, isEasySym, now)) {
                VoxLinkMod.LOGGER.info("[BirthdayPunch] Reuse cached socket array: {} sockets, age={}ms",
                        cachedUdpArray.punchers.size(), now - cachedUdpArray.createTime);
                return cachedUdpArray;
            } else {
                cachedUdpArray.close();
                cachedUdpArray = null;
            }
        }

        // 每个socket必须独立STUN, 获取各自映射端口. birthday attack依赖端口多样性
        java.util.List<UdpHolePuncher> punchers = new java.util.ArrayList<>();
        java.util.List<StunProbe.PublicMappedAddress> addrs = new java.util.ArrayList<>();
        java.util.List<CompletableFuture<Object[]>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < requiredSize; i++) {
            final int idx = i;
            futures.add(CompletableFuture.supplyAsync(() -> {
                UdpHolePuncher puncher = new UdpHolePuncher();
                try {
                    puncher.createSocket();
                } catch (Exception e) {
                    VoxLinkMod.LOGGER.warn("[BirthdayPunch] Create socket #{} failed: {}", idx, e.getMessage());
                    return null;
                }
                try {
                    StunProbe.PublicMappedAddress[] race = StunProbe.discoverMappedAddressRace(puncher.getSocket(), stunUrls, 1);
                    StunProbe.PublicMappedAddress addr = (race != null && race.length > 0) ? race[0] : null;
                    if (addr != null) {
                        return new Object[]{puncher, addr};
                    } else {
                        try { puncher.close(); } catch (Exception ignored) {}
                        return null;
                    }
                } catch (Exception e) {
                    try { puncher.close(); } catch (Exception ignored) {}
                    return null;
                }
            }));
        }
        //凑够min(32,requiredSize)即返回, 零空转
        int minRequired = Math.min(BIRTHDAY_SOCKET_COUNT, requiredSize);
        java.util.concurrent.atomic.AtomicInteger successCount = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger doneCount = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.CompletableFuture<Void> gate = new java.util.concurrent.CompletableFuture<>();
        for (CompletableFuture<Object[]> f : futures) {
            f.whenComplete((result, ex) -> {
                doneCount.incrementAndGet();
                if (result != null) successCount.incrementAndGet();
                if (successCount.get() >= minRequired || doneCount.get() == futures.size()) {
                    gate.complete(null);
                }
            });
        }
        try {
            gate.get(TCP_CONNECT_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            //超时用已完成结果
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException e) {
            //gate不会异常完成
        }

        for (int i = 0; i < futures.size(); i++) {
            try {
                Object[] result = futures.get(i).getNow(null);
                if (result != null) {
                    UdpHolePuncher puncher = (UdpHolePuncher) result[0];
                    StunProbe.PublicMappedAddress addr = (StunProbe.PublicMappedAddress) result[1];
                    if (puncher.getSocket() != null && !puncher.getSocket().isClosed() && addr != null) {
                        punchers.add(puncher);
                        addrs.add(addr);
                    } else if (puncher.getSocket() != null && !puncher.getSocket().isClosed()) {
                        try { puncher.close(); } catch (Exception ignored) {}
                    }
                }
            } catch (Exception ignored) {}
        }
        //取消所有未完成的future, 避免线程泄漏
        for (CompletableFuture<Object[]> f : futures) {
            if (!f.isDone()) f.cancel(true);
        }
        if (punchers.isEmpty()) return null;
        UdpSocketArray array = new UdpSocketArray(punchers, addrs, isEasySym);
        cachedUdpArray = array;
        VoxLinkMod.LOGGER.info("[BirthdayPunch] Create new socket array: {} sockets, easySym={}",
                punchers.size(), isEasySym);
        return array;
    }

    /**
     * P-PRE delta计算: 中位数滤波(去离群值) + EMA平滑(减抖动)
     * 根据端口序列计算可靠的相邻增量, 用于对称NAT端口预测
     */
    static int calculatePortDelta(java.util.List<Integer> samples) {
        if (samples == null || samples.size() < 2) return 1;
        java.util.List<Integer> deltas = new java.util.ArrayList<>();
        for (int i = 1; i < samples.size(); i++) {
            deltas.add(samples.get(i) - samples.get(i - 1));
        }
        // 中位数滤波: 排序后去掉前后25%的离群值
        java.util.Collections.sort(deltas);
        int trim = deltas.size() / 4;
        java.util.List<Integer> trimmed = deltas.subList(trim, deltas.size() - trim);
        // EMA平滑: 越近的样本权重越高(alpha=0.3), 递推式避免浮点累积
        double ema = trimmed.get(0);
        double alpha = 0.4;
        for (int i = 1; i < trimmed.size(); i++) {
            ema = ema + alpha * (trimmed.get(i) - ema);
        }
        int result = (int) Math.round(ema);
        return result > 0 ? result : 1;
    }

    private static volatile ConnectionManager instance;

    public static ConnectionManager getInstance() { return instance; }

    public ConnectionManager(RoomManager roomManager, SignalingClient signalingClient, ScheduledExecutorService scheduler) {
        this.roomManager = roomManager;
        this.signalingClient = signalingClient;
        this.scheduler = scheduler;
        instance = this;
        this.punchExecutor = java.util.concurrent.Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "VoxLink-HostPunch");
            t.setDaemon(true);
            return t;
        });
    }

    public boolean isConnectionCycleActive() {
        return connectionCycleActive.get();
    }

    public StunProbe.ProbeResult getStunProbeResult() {
        return stunProbeResult;
    }

    public void setStunProbeResult(StunProbe.ProbeResult result) {
        this.stunProbeResult = result;
    }

    public AtomicReference<CompletableFuture<StunProbe.ProbeResult>> getStunProbeFutureRef() {
        return stunProbeFutureRef;
    }

    public void setConnectionCycleActive(boolean value) {
        connectionCycleActive.set(value);
    }

    //debounce 安全兜底: 120秒无进展自动重置connectionCycleActive 防止卡死
    private void scheduleConnectionCycleSafety(RoomManager.RoomState state) {
        if (connectionCycleSafetyTimeout != null) connectionCycleSafetyTimeout.cancel(false);
        connectionCycleSafetyTimeout = scheduler.schedule(() -> {
            if (connectionCycleActive.get() && !connectionWon.get() && roomManager.currentRoom.get() == state) {
                //debounce 无限重试规范: 持续重试中不触发安全兜底 未打通前不罢休 仅玩家取消才停
                if (isPersistentRetrying()) {
                    VoxLinkMod.LOGGER.info("[Connection] Safety timeout skipped: persistent retrying round={}", continuousRetryRound.get());
                    return;
                }
                VoxLinkMod.LOGGER.warn("[Connection] Safety timeout (120s), connection cycle stuck, auto-reset");
                connectionCycleActive.set(false);
                showConnectFailed(state);
            }
        }, 120, TimeUnit.SECONDS);
    }

    private void cancelConnectionCycleSafety() {
        if (connectionCycleSafetyTimeout != null) {
            connectionCycleSafetyTimeout.cancel(false);
            connectionCycleSafetyTimeout = null;
        }
    }

    public void setReversePunchAttempted(boolean value) {
        reversePunchAttempted.set(value);
    }

    public void clearActiveHolePunchers() {
        for (String key : new java.util.ArrayList<>(activeHolePunchers.keySet())) {
            UdpHolePuncher p = activeHolePunchers.remove(key);
            if (p != null) {
                try { p.close(); } catch (Exception e) { VoxLinkMod.LOGGER.debug("cleanup puncher close error: {}", e.getMessage()); }
            }
        }
        activeHolePunchers.clear();
    }

    //debounce P2P连接成功后清理主机侧打洞状态 释放socket 避免二次join_request被卡
    public void clearHostPunchingState() {
        hostPunching = false;
        UdpHolePuncher hp = activeHolePunchers.remove("host");
        if (hp != null) {
            try { hp.close(); } catch (Exception e) { VoxLinkMod.LOGGER.debug("host puncher close error: {}", e.getMessage()); }
        }
    }

    //debounce 主机桥已建立 停所有打洞 不发cancel 不清transport 避免误杀已建桥
    public void stopAllPunchingAfterHostBridge() {
        continuousRetryCancelled.set(true);
        connectionCycleActive.set(false);
        connectionWon.set(true);
        //debounce 连接已赢 立即停掉所有TCP兜底重试 避免残留SimOpen空打到超时(该关的及时关)
        cancelAllFallbacks();
        if (connectionTimeoutFuture != null) {
            connectionTimeoutFuture.cancel(false);
            connectionTimeoutFuture = null;
        }
        for (UdpHolePuncher puncher : activeHolePunchers.values()) {
            try { puncher.cancel(); } catch (Exception ignored) {}
            try { puncher.stopPunch(); } catch (Exception ignored) {}
            try { puncher.close(); } catch (Exception ignored) {}
        }
        activeHolePunchers.clear();
        hostPunching = false;
        lastPunchInfoId = "";
    }

    //debounce 手动relay专用: 停打洞不设终态标志 中继失败后可恢复打洞
    private void stopAllPunchingForRelay() {
        connectionCycleActive.set(false);
        if (connectionTimeoutFuture != null) {
            connectionTimeoutFuture.cancel(false);
            connectionTimeoutFuture = null;
        }
        for (UdpHolePuncher puncher : activeHolePunchers.values()) {
            try { puncher.cancel(); } catch (Exception ignored) {}
            try { puncher.stopPunch(); } catch (Exception ignored) {}
            try { puncher.close(); } catch (Exception ignored) {}
        }
        activeHolePunchers.clear();
        hostPunching = false;
        lastPunchInfoId = "";
    }

    public boolean canShowRelayButton() {
        if (!VoxLinkMod.getConfig().isRelayEnabled()) return false;
        if (manualRelayInProgress) return false;
        if (isLegacyPeer()) return false;
        RoomManager.RoomState state = roomManager.currentRoom.get();
        if (state == null || state == RoomManager.PENDING) return false;
        int round = continuousRetryRound.get();
        if (round < 2) return false;
        if (nextRelayEligibleRound == 0) {
            boolean isSymmetric = stunProbeResult != null && stunProbeResult.natType.isSymmetric();
            nextRelayEligibleRound = isSymmetric ? 2 : 3;
        }
        return round >= nextRelayEligibleRound;
    }

    public void triggerManualRelay() {
        RoomManager.RoomState state = roomManager.currentRoom.get();
        if (state == null || state == RoomManager.PENDING) return;
        if (manualRelayInProgress) return;
        manualRelayInProgress = true;
        int currentRound = continuousRetryRound.get();
        boolean isSymmetric = stunProbeResult != null && stunProbeResult.natType.isSymmetric();
        nextRelayEligibleRound = currentRound + (isSymmetric ? 2 : 4);
        VoxLinkMod.LOGGER.info("[Relay] Manual relay triggered at round={}, next eligible round={}",
                currentRound, nextRelayEligibleRound);
        stopAllPunchingForRelay();
        state.roomInfo.setConnectionMode(Component.translatable("voxlink.relay.trying"));
        tryRelay(state);
    }

    public boolean isManualRelayInProgress() {
        return manualRelayInProgress;
    }

    public int getContinuousRetryRound() {
        return continuousRetryRound.get();
    }

    //debounce 阶段四: UI层查询是否处于持续重试中 供AttemptingJoinScreen/DirectConnectMixin跳过硬超时失败
    public boolean isPersistentRetrying() {
        return continuousRetryRound.get() > 0 && !continuousRetryCancelled.get();
    }

    private void notifyRelayFailed() {
        if (manualRelayInProgress) {
            manualRelayInProgress = false;
            VoxLinkMod.LOGGER.info("[Relay] Manual relay failed, resume hole punching");
        }
    }

    public void clearActiveUdpTransports() {
        for (ReliableUdpTransport t : activeUdpTransports.values()) {
            try { t.close(); } catch (Exception e) { VoxLinkMod.LOGGER.debug("cleanup udp transport close error: {}", e.getMessage()); }
        }
        activeUdpTransports.clear();
    }

    public void stopAllConnectionWork() {
        //debounce 重置hostPunching 防止scheduler shutdownNow后punchTimeout任务丢弃导致永久true
        hostPunching = false;
        lastPunchInfoId = "";
        //debounce 统一停TCP兜底 避免残留重试空打
        cancelAllFallbacks();
        for (UdpHolePuncher puncher : activeHolePunchers.values()) {
            try { puncher.cancel(); puncher.close(); } catch (Exception ignored) {}
        }
        activeHolePunchers.clear();
        for (ReliableUdpTransport transport : activeUdpTransports.values()) {
            try { transport.close(); } catch (Exception ignored) {}
        }
        activeUdpTransports.clear();
    }

    public UdpHolePuncher removeHolePuncher(String key) {
        return activeHolePunchers.remove(key);
    }

    public ReliableUdpTransport removeUdpTransport(String key) {
        return activeUdpTransports.remove(key);
    }

    public void handleJoinRequest(String from, JsonObject data) {
        handleJoinRequest(from, data, 0);
    }

    public void handleJoinRequest(String from, JsonObject data, int retryCount) {
        RoomManager.RoomState state = roomManager.currentRoom.get();
        VoxLinkMod.LOGGER.info("[RoomManager] Received join_request from {}, state={}", from, state != null && state != RoomManager.PENDING ? "active" : "null/pending");
        if (state == null || state == RoomManager.PENDING || !state.roomInfo.isHost()) return;

        //debounce 协议协商: 解析新joiner能力 服务端在join_request信号中附带 老版本无字段则视为legacy
        if (data.has("clientProtocolVersion") && !data.get("clientProtocolVersion").isJsonNull()) {
            int joinerProto = data.get("clientProtocolVersion").getAsInt();
            java.util.Set<String> joinerCaps = java.util.Collections.emptySet();
            if (data.has("clientCapabilities") && data.get("clientCapabilities").isJsonArray()) {
                joinerCaps = new java.util.HashSet<>();
                for (var c : data.getAsJsonArray("clientCapabilities")) {
                    if (!c.isJsonNull()) joinerCaps.add(c.getAsString());
                }
            }
            state.roomInfo.addOrUpdatePeer(from, null, null, 0, joinerProto, joinerCaps);
            VoxLinkMod.LOGGER.info("[handleJoinRequest] joiner={} capability: v{} caps={}", from, joinerProto, joinerCaps);
        }

        if (hostPunching || activeHolePunchers.containsKey("host")) {
            if (retryCount >= 3) {
                VoxLinkMod.LOGGER.warn("[RoomManager] join_request retry exhausted, discard {}", from);
                return;
            }
            if (activeUdpTransports.containsKey(from)) {
                VoxLinkMod.LOGGER.info("[RoomManager] Client {} has active transport, ignore duplicate join_request", from);
                return;
            }
            VoxLinkMod.LOGGER.info("[RoomManager] Punching in progress, queue join_request from {} (retry {}/3)", from, retryCount + 1);
            int nextRetry = retryCount + 1;
            scheduler.schedule(() -> {
                RoomManager.RoomState st = roomManager.currentRoom.get();
                if (st == null || st == RoomManager.PENDING || !st.roomInfo.isHost()) return;
                if (activeUdpTransports.containsKey(from)) {
                    VoxLinkMod.LOGGER.info("[RoomManager] Client {} connected, skip queued retry", from);
                    return;
                }
                //debounce 无论条件是否满足都重试 让handleJoinRequest自己判断是排队还是处理
                VoxLinkMod.LOGGER.info("[RoomManager] Retry queued join_request from {}", from);
                handleJoinRequest(from, data, nextRetry);
            }, JOIN_QUEUE_RETRY_SEC, TimeUnit.SECONDS);
            return;
        }

        String hostIp = state.roomInfo.getHostIp();
        String hostIpv6 = state.roomInfo.getHostIpv6();
        boolean needIp = (hostIp == null || hostIp.isEmpty());
        boolean needIpv6 = (hostIpv6 == null || hostIpv6.isEmpty());

        if (needIp || needIpv6) {
            signalingClient.getPublicIp().thenAccept(ipResponse -> {
                if (ipResponse.success && ipResponse.data != null) {
                    RoomManager.RoomState st = roomManager.currentRoom.get();
                    if (st != null && st != RoomManager.PENDING && st.roomInfo.isHost()) {
                        if (ipResponse.data.has("ip") && !ipResponse.data.get("ip").isJsonNull()) {
                            if (st.roomInfo.getHostIp() == null || st.roomInfo.getHostIp().isEmpty()) {
                                st.roomInfo.setHostIp(ipResponse.data.get("ip").getAsString());
                            }
                        }
                        if (ipResponse.data.has("ipv6") && !ipResponse.data.get("ipv6").isJsonNull()) {
                            if (st.roomInfo.getHostIpv6() == null || st.roomInfo.getHostIpv6().isEmpty()) {
                                st.roomInfo.setHostIpv6(ipResponse.data.get("ipv6").getAsString());
                            }
                        }
                        if ((st.roomInfo.getHostIpv6() == null || st.roomInfo.getHostIpv6().isEmpty()) && StunDetector.verifyIPv6Connectivity()) {
                            String localIpv6 = ConnectionFallback.getLocalGlobalIpv6();
                            if (localIpv6 != null) {
                                st.roomInfo.setHostIpv6(localIpv6);
                                VoxLinkMod.LOGGER.info("[handleJoinRequest] API returned no IPv6, using local IPv6: {}", localIpv6);
                            }
                        }
                        sendHolepunchOffer(st, from);
                    } else {
                        VoxLinkMod.LOGGER.warn("[RoomManager] Room state changed during IP query, use original state for offer");
                        sendHolepunchOffer(state, from);
                    }
                } else {
                    sendHolepunchOffer(state, from);
                }
            }).exceptionally(e -> {
                VoxLinkMod.LOGGER.warn("[RoomManager] Get public IP failed in handleJoinRequest: {}", e.getMessage());
                sendHolepunchOffer(state, from);
                return null;
            });
            return;
        }
        sendHolepunchOffer(state, from);
    }

    public void sendHolepunchOffer(RoomManager.RoomState state, String from) {
        connectionWon.set(false);
        JsonObject offerData = new JsonObject();
        if (state.roomInfo.getHostIp() != null && !state.roomInfo.getHostIp().isEmpty()) {
            offerData.addProperty("hostIp", state.roomInfo.getHostIp());
        }
        if (state.roomInfo.getHostIpv6() != null && !state.roomInfo.getHostIpv6().isEmpty()) {
            offerData.addProperty("hostIpv6", state.roomInfo.getHostIpv6());
        }
        int bridgePort = P2PBridge.getHostPort();
        int connectPort = bridgePort > 0 ? bridgePort : state.roomInfo.getHostPort();
        offerData.addProperty("hostPort", connectPort);

        String localIp = StunDetector.getLocalIpAddress();
        if (localIp != null && !localIp.isEmpty()) {
            offerData.addProperty("hostLocalIp", localIp);
            state.roomInfo.setHostLocalIp(localIp);
            VoxLinkMod.LOGGER.info("[RoomManager] Include host LAN IP: {}", localIp);
        }

        // 复用已有socket，保持STUN绑定有效
        UdpHolePuncher hostPuncher = activeHolePunchers.get("host");
        if (hostPuncher == null || hostPuncher.getSocket() == null || hostPuncher.getSocket().isClosed()) {
            hostPuncher = new UdpHolePuncher();
            try {
                int mcPort = state.roomInfo.getHostPort();
                hostPuncher.createSocket(mcPort);
                activeHolePunchers.put("host", hostPuncher);
            } catch (Exception e) {
                try {
                    hostPuncher.createSocket();
                    activeHolePunchers.put("host", hostPuncher);
                } catch (Exception e2) {
                    VoxLinkMod.LOGGER.warn("[RoomManager] Create host punch socket failed: {}", e2.getMessage());
                    hostPuncher = null;
                }
            }
        } else {
            hostPuncher.stopPunch();
            VoxLinkMod.LOGGER.info("[RoomManager] Reuse existing host punch socket (localPort={})", hostPuncher.getSocket().getLocalPort());
        }

        String natType = state.roomInfo.getNatType();
        boolean isSymmetricOrUnknown = StunDetector.isNatTypeSymmetric(natType) || "unknown".equals(natType) || natType == null;

        // 房主注册到全局relay候选池
        if (stunProbeResult != null && state.roomInfo.getClientId() != null) {
            String hostMappedIp = null;
            int hostMappedPort = 0;
            for (StunProbe.StunServerResult sr : stunProbeResult.serverResults) {
                if (sr.reachable && sr.mappedIp != null && sr.mappedPort > 0) {
                    hostMappedIp = sr.mappedIp;
                    hostMappedPort = sr.mappedPort;
                    break;
                }
            }
            if (hostMappedIp != null && hostMappedPort > 0) {
                boolean relayOk = VoxLinkMod.getConfig().isRelayEnabled();
                signalingClient.registerRelayPeer(state.roomInfo.getClientId(), state.roomInfo.getCode(),
                        stunProbeResult.natType.key, hostMappedIp, hostMappedPort, relayOk);
            }
        }

        // 异步STUN，不阻塞信号轮询
        final UdpHolePuncher fHostPuncher = hostPuncher;
        final boolean fIsSymmetricOrUnknown = isSymmetricOrUnknown;
        final String fNatType = natType;
        final JsonObject fOfferData = offerData;
        final RoomManager.RoomState fState = state;
        final String fFrom = from;
        final int fConnectPort = connectPort;

        CompletableFuture.supplyAsync(() -> {
            StunProbe.PublicMappedAddress m1 = null, m2 = null;
            java.util.List<StunProbe.PublicMappedAddress> birthdayAddrs = null;
            if (fHostPuncher != null) {
                try {
                    java.util.List<String> allStun = StunDetector.getAllStunUrls();
                    VoxLinkMod.LOGGER.info("[RoomManager] Host NAT: {} — 8 concurrent STUN ({} servers)", fNatType != null ? fNatType : "null", allStun.size());
                    StunProbe.PublicMappedAddress[] top2 = StunProbe.discoverMappedAddressRace(
                            fHostPuncher.getSocket(), allStun, 2);
                    m1 = top2[0];
                    m2 = top2[1];
                } catch (Exception e) {
                    VoxLinkMod.LOGGER.warn("[RoomManager] Punch socket STUN failed: {}", e.getMessage());
                }

                // 对称NAT时预创建84个birthday socket, 端口塞进holepunch_offer避免holepunch_mapped延迟
                // 参考DCUtR/P-PRE: 已知端口列表比端口扫描成功率高出数倍, 且消除信号轮询11s延迟
                if (fIsSymmetricOrUnknown && m1 != null && m2 != null) {
                    int birthdayCount = HARD_SYM_SOCKET_COUNT;
                    VoxLinkMod.LOGGER.info("[RoomManager] Symmetric NAT, pre-create {} birthday sockets into holepunch_offer", birthdayCount);
                    birthdayAddrs = new java.util.ArrayList<>();
                    java.util.List<CompletableFuture<StunProbe.PublicMappedAddress>> bFutures = new java.util.ArrayList<>();
                    for (int i = 0; i < birthdayCount; i++) {
                        final int idx = i;
                        bFutures.add(CompletableFuture.supplyAsync(() -> {
                            UdpHolePuncher bp = new UdpHolePuncher();
                            try { bp.createSocket(); }
                            catch (Exception e) { return null; }
                            StunProbe.PublicMappedAddress[] race = StunProbe.discoverMappedAddressRace(
                                    bp.getSocket(), StunDetector.getAllStunUrls(), 1);
                            StunProbe.PublicMappedAddress addr = race[0];
                            if (addr != null) {
                                String key = "host_birthday_" + idx;
                                activeHolePunchers.put(key, bp);
                                return addr;
                            }
                            try { bp.close(); } catch (Exception ignored) {}
                            return null;
                        }));
                    }
                    try {
                        CompletableFuture.allOf(bFutures.toArray(new CompletableFuture[0]))
                                .get(AWAIT_TIMEOUT_SEC, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        VoxLinkMod.LOGGER.warn("[RoomManager] Birthday socket create partial timeout: {}", e.getMessage());
                    }
                    for (var f : bFutures) {
                        try {
                            StunProbe.PublicMappedAddress a = f.getNow(null);
                            if (a != null) birthdayAddrs.add(a);
                        } catch (Exception ignored) {}
                    }
                    VoxLinkMod.LOGGER.info("[RoomManager] Pre-create {} birthday sockets done, {} valid", birthdayCount, birthdayAddrs.size());
                }
            }
            return new Object[]{m1, m2, birthdayAddrs};
        }).thenAccept(result -> {
            StunProbe.PublicMappedAddress mapped1 = (StunProbe.PublicMappedAddress) result[0];
            StunProbe.PublicMappedAddress mapped2 = (StunProbe.PublicMappedAddress) result[1];
            @SuppressWarnings("unchecked")
            java.util.List<StunProbe.PublicMappedAddress> birthdayPorts = (java.util.List<StunProbe.PublicMappedAddress>) result[2];
            StunProbe.PublicMappedAddress mapped = null;
            boolean punchSocketSymmetric = false;
            boolean symOrUnknown = fIsSymmetricOrUnknown;

            if (mapped1 != null && mapped2 != null) {
                if (mapped1.port() != mapped2.port()) {
                    punchSocketSymmetric = true;
                    VoxLinkMod.LOGGER.info("[RoomManager] Punch socket STUN: symmetric NAT ({} vs {})", mapped1.port(), mapped2.port());
                } else {
                    if (symOrUnknown && !StunDetector.isNatTypeSymmetric(fNatType)) {
                        VoxLinkMod.LOGGER.info("[RoomManager] Punch socket STUN: same port {}, override isSymmetricOrUnknown (was {})", mapped1.port(), fNatType);
                        symOrUnknown = false;
                    }
                }
                mapped = mapped2;
            } else {
                mapped = mapped1 != null ? mapped1 : mapped2;
            }
            if (punchSocketSymmetric) {
                symOrUnknown = true;
            }

            if (mapped != null) {
                fOfferData.addProperty("hostMappedIp", mapped.ip());
                fOfferData.addProperty("hostMappedPort", mapped.port());
            } else {
                // MC端口UDP被防火墙挡了, 用NAT探测时的动态socket映射地址兜底
                if (stunProbeResult != null && !stunProbeResult.serverResults.isEmpty()) {
                    for (StunProbe.StunServerResult sr : stunProbeResult.serverResults) {
                        if (sr.reachable && sr.mappedIp != null && sr.mappedPort > 0) {
                            fOfferData.addProperty("hostMappedIp", sr.mappedIp);
                            fOfferData.addProperty("hostMappedPort", sr.mappedPort);
                            mapped = new StunProbe.PublicMappedAddress(sr.mappedIp, sr.mappedPort);
                            VoxLinkMod.LOGGER.info("[RoomManager] MC port STUN failed, fallback to NAT probe mapped address: {}:{}", sr.mappedIp, sr.mappedPort);
                            break;
                        }
                    }
                }
            }
            if (mapped != null) {
                if (symOrUnknown) {
                    fOfferData.addProperty("hostSymmetric", true);
                }
                // EasyTier: 区分EasySym(端口可预测)与HardSym。EasySym×EasySym 可打洞，HardSym才放弃
                boolean hostEasySym = punchSocketSymmetric && StunDetector.isEasySymmetric(fNatType);
                if (hostEasySym) {
                    fOfferData.addProperty("hostEasySym", true);
                }
                // P-PRE端口预测: 10次连续采样替代2次, PortPredictor综合预测(线性回归+差值序列)
                if (mapped1 != null && mapped2 != null && mapped1.port() != mapped2.port()) {
                    int delta = mapped2.port() - mapped1.port();
                    int portRange = PORT_RANGE_MAX;
                    // 同socket同服务器连续采样10次(100ms间隔), 获取端口序列用于精确预测
                    if (fHostPuncher != null && fHostPuncher.getSocket() != null) {
                        java.util.List<Integer> samples = StunProbe.samplePortsSequential(
                                fHostPuncher.getSocket(), StunDetector.getAllStunUrls(), 10, 100);
                        if (samples.size() >= 5) {
                            //优化: PortPredictor综合预测, 收窄目标范围 65536→64-512
                            icu.wuhui.voxlink.network.PortPredictor.PredictResult pr =
                                    icu.wuhui.voxlink.network.PortPredictor.predict(samples);
                            int reliableDelta = icu.wuhui.voxlink.network.PortPredictor.deltaPredict(samples)
                                    - samples.get(samples.size() - 1);
                            if (reliableDelta <= 0) reliableDelta = calculatePortDelta(samples);
                            portRange = pr.range;
                            VoxLinkMod.LOGGER.info("[RoomManager] P-PRE samples: {} times -> sequence={}, combined predict port={}, range=±{}, delta={}",
                                    samples.size(), samples, pr.predictedPort, pr.range, reliableDelta);
                            delta = reliableDelta;
                        } else {
                            VoxLinkMod.LOGGER.warn("[RoomManager] P-PRE insufficient samples ({}), fallback to 2-sample delta={}", samples.size(), delta);
                        }
                    }
                    fOfferData.addProperty("hostMappedPortDelta", delta);
                    fOfferData.addProperty("hostMappedPortRange", portRange);
                    VoxLinkMod.LOGGER.info("[RoomManager] Symmetric NAT port delta: delta={}, range=±{}", delta, portRange);
                }
                fState.roomInfo.setHostMappedAddress(mapped.ip(), mapped.port());
                fState.roomInfo.setHostEasySym(hostEasySym);
                VoxLinkMod.LOGGER.info("[RoomManager] Punch socket STUN: ip={}, port={}, symmetric={}, easySym={}",
                        mapped.ip(), mapped.port(), symOrUnknown, hostEasySym);
            }

            // 对称NAT预创建的birthday socket端口塞进holepunch_offer, 消除holepunch_mapped信号延迟
            if (birthdayPorts != null && !birthdayPorts.isEmpty()) {
                com.google.gson.JsonArray portsArr = new com.google.gson.JsonArray();
                for (StunProbe.PublicMappedAddress bp : birthdayPorts) {
                    portsArr.add(bp.port());
                }
                fOfferData.add("hostBirthdayPorts", portsArr);
                VoxLinkMod.LOGGER.info("[RoomManager] holepunch_offer with {} birthday ports", birthdayPorts.size());
            }

            // RTT同步: 双方等到同一时刻发第一包, 提高对称NAT打洞命中率
            long syncTime = System.currentTimeMillis() + RELAY_GRACE_MS;
            fOfferData.addProperty("punchSyncTimeMs", syncTime);
            fState.roomInfo.setPunchSyncTimeMs(syncTime);
            VoxLinkMod.LOGGER.info("[RoomManager] RTT sync: punchSyncTimeMs={}", syncTime);

            VoxLinkMod.LOGGER.info("[RoomManager] Send holepunch_offer to {} (hostIp={}, hostIpv6={}, port={}, mappedIp={}, mappedPort={})",
                    fFrom,
                    fState.roomInfo.getHostIp() != null ? fState.roomInfo.getHostIp() : "none",
                    fState.roomInfo.getHostIpv6() != null ? fState.roomInfo.getHostIpv6() : "none",
                    fConnectPort,
                    mapped != null ? mapped.ip() : "none",
                    mapped != null ? mapped.port() : 0);
            signalingClient.sendSignal(fState.roomInfo.getCode(), fState.roomInfo.getToken(), true,
                    "holepunch_offer", fOfferData, fFrom)
                    .thenAccept(response -> {
                        if (!response.success) {
                            VoxLinkMod.LOGGER.error("[RoomManager] Send holepunch_offer failed: {} - {}", response.error, response.message);
                        }
                    })
                    .exceptionally(e -> {
                        VoxLinkMod.LOGGER.error("[RoomManager] Send holepunch_offer network error: {}", e.getMessage());
                        return null;
                    });

            //debounce punch_info等待超时 对端不回punch_info时清理host socket 防后续join永久排队
            final String waitClientId = fFrom;
            scheduler.schedule(() -> {
                if (!hostPunching && activeHolePunchers.containsKey("host")) {
                    UdpHolePuncher hp = activeHolePunchers.remove("host");
                    if (hp != null) {
                        try { hp.close(); } catch (Exception ignored) {}
                    }
                    activeHolePunchers.entrySet().removeIf(e -> e.getKey().startsWith("host_"));
                    lastPunchInfoId = "";
                    VoxLinkMod.LOGGER.info("[RoomManager] Wait punch_info timeout ({}s), cleanup host socket client={}", PUNCH_INFO_WAIT_TIMEOUT_S, waitClientId);
                }
            }, PUNCH_INFO_WAIT_TIMEOUT_S, TimeUnit.SECONDS);
        });
    }

    public void handleHolePunchOffer(String from, JsonObject data) {
        RoomManager.RoomState state = roomManager.currentRoom.get();
        VoxLinkMod.LOGGER.info("[RoomManager] Received holepunch_offer, state={}", state != null && state != RoomManager.PENDING ? "active" : "null/pending");
        if (state == null || state == RoomManager.PENDING || state.roomInfo.isHost()) return;
        //debounce 双P2P模式 VoxLink侧已被杀或Terracotta已赢 不再重启VoxLink
        if (voxlinkSideDisabled || terracottaWon) {
            VoxLinkMod.LOGGER.info("[DualP2P] VoxLink disabled or Terracotta won, ignore holepunch_offer");
            return;
        }

        if (connectionWon.get() && P2PBridge.isRunning()) {
            VoxLinkMod.LOGGER.info("[RoomManager] Already connected, ignore holepunch_offer");
            return;
        }

        if (ConnectionHelper.isConnecting() && connectionCycleActive.get()) {
            VoxLinkMod.LOGGER.info("[RoomManager] Already connecting with active cycle, ignore holepunch_offer");
            return;
        }

        if (!connectionCycleActive.compareAndSet(false, true)) {
            VoxLinkMod.LOGGER.info("[RoomManager] Connection cycle in progress, ignore duplicate holepunch_offer");
            return;
        }
        connectionWon.set(false);  // 新join重置连接状态
        scheduleConnectionCycleSafety(state);

        if (P2PBridge.isRunning()) {
            int existingPort = P2PBridge.getJoinerPort();
            String bridgeHostIp = data.has("hostIp") && !data.get("hostIp").isJsonNull() ? data.get("hostIp").getAsString() : state.roomInfo.getHostIp();
            if (existingPort > 0 && P2PBridge.isTargetMatch(bridgeHostIp, state.roomInfo.getHostPort())) {
                VoxLinkMod.LOGGER.info("[RoomManager] Bridge running with same target, ignore duplicate holepunch_offer");
                connectionCycleActive.set(false);
                ConnectionHelper.resetConnecting();
                return;
            }
            P2PBridge.disconnect();
        }

        String hostIp = null;
        if (data.has("hostIp") && !data.get("hostIp").isJsonNull() && !data.get("hostIp").getAsString().isEmpty()) {
            hostIp = data.get("hostIp").getAsString();
        }
        String hostIpv6 = null;
        int connectPort = state.roomInfo.getHostPort();
        if (data.has("hostPort") && !data.get("hostPort").isJsonNull()) {
            connectPort = data.get("hostPort").getAsInt();
        }
        if (data.has("hostIpv6") && !data.get("hostIpv6").isJsonNull() && !data.get("hostIpv6").getAsString().isEmpty()) {
            hostIpv6 = data.get("hostIpv6").getAsString();
        }

        String hostMappedIp = null;
        int hostMappedPort = 0;
        if (data.has("hostMappedIp") && !data.get("hostMappedIp").isJsonNull()) {
            hostMappedIp = data.get("hostMappedIp").getAsString();
        }
        if (data.has("hostMappedPort") && !data.get("hostMappedPort").isJsonNull()) {
            hostMappedPort = data.get("hostMappedPort").getAsInt();
        }

        if (hostMappedIp != null && !hostMappedIp.isEmpty() && hostMappedPort > 0) {
            state.roomInfo.setHostMappedAddress(hostMappedIp, hostMappedPort);
            VoxLinkMod.LOGGER.info("[RoomManager] Update roomInfo hostMapped={}:{}", hostMappedIp, hostMappedPort);
        }

        String hostLocalIp = null;
        if (data.has("hostLocalIp") && !data.get("hostLocalIp").isJsonNull()) {
            hostLocalIp = data.get("hostLocalIp").getAsString();
            state.roomInfo.setHostLocalIp(hostLocalIp);
            VoxLinkMod.LOGGER.info("[RoomManager] Host provided LAN IP: {}", hostLocalIp);
        }

        final String finalHostIp = hostIp;
        final String finalHostIpv6 = hostIpv6;
        final int finalHostPort = connectPort;
        final String finalHostMappedIp = hostMappedIp;
        final int finalHostMappedPort = hostMappedPort;
        final boolean finalHostSymmetric = data.has("hostSymmetric") && !data.get("hostSymmetric").isJsonNull() && data.get("hostSymmetric").getAsBoolean();
        final boolean finalHostEasySym = data.has("hostEasySym") && !data.get("hostEasySym").isJsonNull() && data.get("hostEasySym").getAsBoolean();
        final int finalHostMappedPortDelta = data.has("hostMappedPortDelta") && !data.get("hostMappedPortDelta").isJsonNull() ? data.get("hostMappedPortDelta").getAsInt() : 0;
        final int finalHostMappedPortRange = data.has("hostMappedPortRange") && !data.get("hostMappedPortRange").isJsonNull() ? data.get("hostMappedPortRange").getAsInt() : 100;
        if (finalHostMappedPortRange != 100) {
            state.roomInfo.setHostMappedPortRange(finalHostMappedPortRange);
        }
        final String finalHostLocalIp = hostLocalIp;
        final long punchSyncTime = data.has("punchSyncTimeMs") ? data.get("punchSyncTimeMs").getAsLong() : 0;
        if (punchSyncTime > 0) {
            state.roomInfo.setPunchSyncTimeMs(punchSyncTime);
            VoxLinkMod.LOGGER.info("[RoomManager] RTT sync: punchSyncTimeMs={} ({}ms ago)", punchSyncTime, punchSyncTime - System.currentTimeMillis());
        }

        // host预创建的birthday socket端口, 直接用于反向打洞, 无需等待holepunch_mapped
        java.util.List<Integer> hostBirthdayPorts = null;
        if (data.has("hostBirthdayPorts") && data.get("hostBirthdayPorts").isJsonArray()) {
            hostBirthdayPorts = new java.util.ArrayList<>();
            for (com.google.gson.JsonElement elem : data.getAsJsonArray("hostBirthdayPorts")) {
                hostBirthdayPorts.add(elem.getAsInt());
            }
            VoxLinkMod.LOGGER.info("[RoomManager] holepunch_offer contains {} birthday ports", hostBirthdayPorts.size());
        }
        final java.util.List<Integer> fHostBirthdayPorts = hostBirthdayPorts;

        if (hostIp != null && !hostIp.isEmpty()) state.roomInfo.setHostIp(hostIp);
        if (hostIpv6 != null && !hostIpv6.isEmpty()) state.roomInfo.setHostIpv6(hostIpv6);
        if (connectPort > 0) state.roomInfo.setHostConnectPort(connectPort);
        if (finalHostEasySym) state.roomInfo.setHostEasySym(true);
        if (fHostBirthdayPorts != null) {
            state.roomInfo.setHostBirthdayPorts(fHostBirthdayPorts);
        }

        CompletableFuture<StunProbe.ProbeResult> probeFuture = stunProbeFutureRef.get();
        if (probeFuture != null && stunProbeResult == null && !probeFuture.isDone()) {
            final RoomManager.RoomState fState = state;
            probeFuture.orTimeout(STUN_PROBE_TIMEOUT_SEC, java.util.concurrent.TimeUnit.SECONDS)
                .thenAccept(result -> {
                    stunProbeResult = result;
                    extendConnectionTimeoutIfNeeded(fState);
                    finishHandleHolePunchOffer(fState, from, finalHostIpv6, finalHostIp, finalHostPort,
                            finalHostMappedIp, finalHostMappedPort, finalHostSymmetric, finalHostMappedPortDelta);
                })
                .exceptionally(e -> {
                    VoxLinkMod.LOGGER.warn("[handleHolePunchOffer] STUN probe not ready after {}s, continue without NAT data", STUN_PROBE_TIMEOUT_SEC);
                    finishHandleHolePunchOffer(fState, from, finalHostIpv6, finalHostIp, finalHostPort,
                            finalHostMappedIp, finalHostMappedPort, finalHostSymmetric, finalHostMappedPortDelta);
                    return null;
                });
            return;
        }
        finishHandleHolePunchOffer(state, from, finalHostIpv6, finalHostIp, finalHostPort,
                finalHostMappedIp, finalHostMappedPort, finalHostSymmetric, finalHostMappedPortDelta);
    }

    private void finishHandleHolePunchOffer(RoomManager.RoomState state, String from, String finalHostIpv6, String finalHostIp, int finalHostPort,
                                                String finalHostMappedIp, int finalHostMappedPort, boolean finalHostSymmetric, int finalHostMappedPortDelta) {
        if (stunProbeResult != null) {
            if (stunProbeResult.natType.isSymmetric()) {
                state.roomInfo.setConnectionMode(Component.translatable("voxlink.connection.probing"));
            }
            VoxLinkMod.LOGGER.info("[handleHolePunchOffer] Use probe result: NAT={}, reachable STUN={}",
                    stunProbeResult.natType.key, stunProbeResult.reachableStunUrls.size());

            String clientPublicIp = null;
            for (StunProbe.StunServerResult r : stunProbeResult.serverResults) {
                if (r.reachable && r.mappedIp != null) { clientPublicIp = r.mappedIp; break; }
            }
            if (clientPublicIp != null && finalHostIp != null && clientPublicIp.equals(finalHostIp)) {
                VoxLinkMod.LOGGER.warn("[handleHolePunchOffer] Same public IP ({}): both behind same CGNAT, P2P direct unlikely", clientPublicIp);
                state.roomInfo.setSameCgnat(true);
            }
            if (clientPublicIp != null) {
                int clientMappedPort = 0;
                for (StunProbe.StunServerResult r : stunProbeResult.serverResults) {
                    if (r.reachable && r.mappedPort > 0) { clientMappedPort = r.mappedPort; break; }
                }
                state.roomInfo.setMyMappedIp(clientPublicIp);
                state.roomInfo.setMyMappedPort(clientMappedPort);
            }
        }

        // offer设sym不覆盖mapped
        if (finalHostSymmetric) {
            state.roomInfo.setHostSymmetric(true);
        }
        if (finalHostMappedPortDelta != 0) {
            state.roomInfo.setHostMappedPortDelta(finalHostMappedPortDelta);
        }

        String effectiveMappedIp = finalHostMappedIp;
        int effectiveMappedPort = finalHostMappedPort;
        if (effectiveMappedIp == null || effectiveMappedPort <= 0) {
            String cachedMappedIp = state.roomInfo.getHostMappedIp();
            int cachedMappedPort = state.roomInfo.getHostMappedPort();
            if (cachedMappedIp != null && cachedMappedPort > 0) {
                effectiveMappedIp = cachedMappedIp;
                effectiveMappedPort = cachedMappedPort;
                VoxLinkMod.LOGGER.info("[handleHolePunchOffer] Use first holepunch_mapped: {}:{}", effectiveMappedIp, effectiveMappedPort);
            }
        }

        if (effectiveMappedIp == null || effectiveMappedPort <= 0) {
            connectionCycleActive.set(false);
            VoxLinkMod.LOGGER.info("[handleHolePunchOffer] offer has no mapped address, wait for holepunch_mapped...");
            scheduler.schedule(() -> {
                if (connectionCycleActive.compareAndSet(false, true)) {
                    String mappedIp = state.roomInfo.getHostMappedIp();
                    int mappedPort = state.roomInfo.getHostMappedPort();
                    if (mappedIp == null || mappedPort <= 0) {
                        VoxLinkMod.LOGGER.warn("[handleHolePunchOffer] holepunch_mapped timeout (12s), start without mapped address");
                        runConnectionCycle(state, from, finalHostIpv6, finalHostIp, finalHostPort, null, 0, 0);
                    }
                } else {
                    VoxLinkMod.LOGGER.debug("[handleHolePunchOffer] CAS failed, new offer in progress");
                }
            }, ICE_POOL_RETAIN_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
            return;
        }

        runConnectionCycle(state, from, finalHostIpv6, finalHostIp, finalHostPort, effectiveMappedIp, effectiveMappedPort, 0);
    }

    public void handleHolepunchMapped(String from, JsonObject data) {
        RoomManager.RoomState state = roomManager.currentRoom.get();
        if (state == null || state == RoomManager.PENDING || state.roomInfo.isHost()) return;

        String hostMappedIp = null;
        int hostMappedPort = 0;
        if (data.has("hostMappedIp") && !data.get("hostMappedIp").isJsonNull()) {
            hostMappedIp = data.get("hostMappedIp").getAsString();
        }
        if (data.has("hostMappedPort") && !data.get("hostMappedPort").isJsonNull()) {
            hostMappedPort = data.get("hostMappedPort").getAsInt();
        }
        boolean hostSymmetric = data.has("hostSymmetric") && !data.get("hostSymmetric").isJsonNull() && data.get("hostSymmetric").getAsBoolean();
        boolean hostEasySym = data.has("hostEasySym") && !data.get("hostEasySym").isJsonNull() && data.get("hostEasySym").getAsBoolean();
        int hostMappedPortDelta = data.has("hostMappedPortDelta") && !data.get("hostMappedPortDelta").isJsonNull() ? data.get("hostMappedPortDelta").getAsInt() : 0;
        int hostMappedPortRange = data.has("hostMappedPortRange") && !data.get("hostMappedPortRange").isJsonNull() ? data.get("hostMappedPortRange").getAsInt() : 100;

        java.util.List<Integer> hostMappedPorts = new java.util.ArrayList<>();
        if (data.has("hostMappedPorts") && data.get("hostMappedPorts").isJsonArray()) {
            for (var elem : data.getAsJsonArray("hostMappedPorts")) {
                hostMappedPorts.add(elem.getAsInt());
            }
        }

        if (hostMappedIp == null || (hostMappedPort <= 0 && hostMappedPorts.isEmpty())) return;

        if (hostMappedPorts.isEmpty()) hostMappedPorts.add(hostMappedPort);

        VoxLinkMod.LOGGER.info("[RoomManager] Received host mapped: {}:{} ports={} (sym={}, delta={})", hostMappedIp, hostMappedPort, hostMappedPorts, hostSymmetric, hostMappedPortDelta);
        state.roomInfo.setHostMappedAddress(hostMappedIp, hostMappedPort);
        if (hostSymmetric) {
            state.roomInfo.setHostSymmetric(true);
        }
        if (hostEasySym) {
            state.roomInfo.setHostEasySym(true);
        }
        if (hostMappedPortDelta != 0) {
            state.roomInfo.setHostMappedPortDelta(hostMappedPortDelta);
        }
        if (hostMappedPortRange != 100) {
            state.roomInfo.setHostMappedPortRange(hostMappedPortRange);
        }
        // 注册host peer信息供relay选择
        if (hostMappedIp != null && hostMappedPort > 0) {
            String hostNatType = hostSymmetric ? (hostEasySym ? "symmetric_easy_inc" : "symmetric") : "full_cone";
            state.roomInfo.addOrUpdatePeer(from, hostNatType, hostMappedIp, hostMappedPort);
        }

        // CGNAT: 处理host回传的hostLocalIp
        if (data.has("hostLocalIp") && !data.get("hostLocalIp").isJsonNull()) {
            String receivedHostLocalIp = data.get("hostLocalIp").getAsString();
            if (receivedHostLocalIp != null && !receivedHostLocalIp.isEmpty()) {
                String existingLocalIp = state.roomInfo.getHostLocalIp();
                if (existingLocalIp == null || existingLocalIp.isEmpty()) {
                    state.roomInfo.setHostLocalIp(receivedHostLocalIp);
                    VoxLinkMod.LOGGER.info("[handleHolepunchMapped] Received host LAN IP: {}", receivedHostLocalIp);
                    // 如果当前正在连接周期中，立即尝试hostLocalIp
                    if (connectionCycleActive.get() && state.roomInfo.isSameCgnat() && !connectionWon.get()) {
                        int connectPort = state.roomInfo.getHostConnectPort() > 0 ? state.roomInfo.getHostConnectPort() : state.roomInfo.getHostPort();
                        int mcPort = state.roomInfo.getHostPort();
                        VoxLinkMod.LOGGER.info("[handleHolepunchMapped] CGNAT: also try hostLocalIp {}:{}", receivedHostLocalIp, connectPort);
                        ConnectionFallback localFallback = trackFallback(new ConnectionFallback());
                        localFallback.tryIpv4Direct(receivedHostLocalIp, connectPort).thenAccept(result -> {
                            if (roomManager.currentRoom.get() == state && result.success && connectionWon.compareAndSet(false, true)) {
                                VoxLinkMod.LOGGER.info("[handleHolepunchMapped] CGNAT hostLocalIp direct connect won");
                                connectViaBridge(state, result);
                            }
                        });
                        ConnectionFallback mcLocalFallback = trackFallback(new ConnectionFallback());
                        mcLocalFallback.tryIpv4Direct(receivedHostLocalIp, mcPort).thenAccept(result -> {
                            if (roomManager.currentRoom.get() == state && result.success && connectionWon.compareAndSet(false, true)) {
                                VoxLinkMod.LOGGER.info("[handleHolepunchMapped] CGNAT hostLocalIp MC port won");
                                connectViaBridge(state, result);
                            }
                        });
                    }
                }
            }
        }

        UdpHolePuncher joinerPuncher = activeHolePunchers.get("joiner");
        if (joinerPuncher != null && connectionCycleActive.get()) {
            // 更新主puncher到第一个端
int updatePort = hostMappedPorts.get(0);
            if (hostMappedPortDelta != 0) {
                int predicted = updatePort + hostMappedPortDelta;
                if (predicted > 0 && predicted <= 65535) updatePort = predicted;
            }
            VoxLinkMod.LOGGER.info("[RoomManager] Update joiner punch target to {}:{}", hostMappedIp, updatePort);
            joinerPuncher.updateTarget(hostMappedIp, updatePort);

            // 多端口：为额外端口创建并行puncher（最多5个，避免带宽爆炸
int maxExtra = Math.min(hostMappedPorts.size(), 6);
             for (int i = 1; i < maxExtra; i++) {
                final int fIdx = i;
                int extraPort = hostMappedPorts.get(i);
                if (hostMappedPortDelta != 0) {
                    int predicted = extraPort + hostMappedPortDelta;
                    if (predicted > 0 && predicted <= 65535) extraPort = predicted;
                }
                String key = "joiner_extra_" + fIdx;
                if (!activeHolePunchers.containsKey(key)) {
                    UdpHolePuncher extraPuncher = new UdpHolePuncher();
                    try {
                        extraPuncher.createSocket();
                        activeHolePunchers.put(key, extraPuncher);
                        final int fExtraPort = extraPort;
                        final String fHostMappedIp = hostMappedIp;
                        VoxLinkMod.LOGGER.info("[RoomManager] Multi-port puncher#{}: {}:{}", i, fHostMappedIp, fExtraPort);
                        extraPuncher.punchWithPortPrediction(fHostMappedIp, fExtraPort, 30).thenAccept(result -> {
                            if (!result.isSuccess()) {
                                PunchFailureClassifier.FailureReason reason = PunchFailureClassifier.classify(result);
                                lastPunchResult = result.withReason(reason);
                                PunchTuner.nextParams(PunchProfile.current(), localNatClass, remoteNatClass, 1, MAX_CONNECTION_CYCLES, reason, lastPunchResult);
                                VoxLinkMod.LOGGER.info("[ConnectionManager] Punch failed reason={} recvPunch={} recvAck={}", reason, result.socketsReceivedPunch, result.socketsReceivedAck);
                                return;
                            }
                            DatagramSocket socket = result.getSuccessSocket();
                            if (!connectionWon.compareAndSet(false, true)) {
                                try { extraPuncher.close(); } catch (Exception ignored) {}
                                return;
                            }
                            extraPuncher.markSocketTransferred();
                            stopAllPunchingAfterHostBridge();
                            extraPuncher.stopPunch();
                            final DatagramSocket winSocket = socket;
                            final UdpHolePuncher winPuncher = extraPuncher;
                            scheduler.submit(() -> {
                                try {
                                    establishUdpTransport(state, winSocket, winPuncher,
                                            new InetSocketAddress(fHostMappedIp, fExtraPort), "joiner", false, null);
                                } catch (Exception e) {
                                    VoxLinkMod.LOGGER.error("[RoomManager] Multi-port transport failed: {}", e.getMessage());
                                    winPuncher.close();
                                }
                            });
                        }).exceptionally(e -> {
                            VoxLinkMod.LOGGER.debug("[RoomManager] Multi-port puncher#{} punch failed: {}", fIdx, e.getMessage());
                            activeHolePunchers.remove(key);
                            try { extraPuncher.close(); } catch (Exception ignored) {}
                            return null;
                        });
                    } catch (Exception e) {
                        VoxLinkMod.LOGGER.warn("[RoomManager] Create multi-port puncher#{} failed: {}", fIdx, e.getMessage());
                    }
                }
            }
        } else if (!connectionCycleActive.get() && !ConnectionHelper.isConnecting()) {
            VoxLinkMod.LOGGER.info("[RoomManager] Start connection cycle with mapped address");
            if (connectionCycleActive.compareAndSet(false, true)) {
                String hostIp = state.roomInfo.getHostIp();
                String hostIpv6 = state.roomInfo.getHostIpv6();
                int hostPort = state.roomInfo.getHostConnectPort() > 0 ? state.roomInfo.getHostConnectPort() : state.roomInfo.getHostPort();
                int cyclePort = hostMappedPort;
                if (hostMappedPortDelta != 0) {
                    int predicted = hostMappedPort + hostMappedPortDelta;
                    if (predicted > 0 && predicted <= 65535) cyclePort = predicted;
                }
                runConnectionCycle(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, cyclePort, 0);
            }
        }
    }

    public void handlePunchInfo(String from, JsonObject data) {
        RoomManager.RoomState state = roomManager.currentRoom.get();
        if (state == null || state == RoomManager.PENDING) return;

        if (state.roomInfo.isHost()) {
            handleHostPunchInfo(state, from, data);
        } else {
            handleJoinerPunchInfo(state, from, data);
        }
    }

    public void handleHostPunchInfo(RoomManager.RoomState state, String from, JsonObject data) {
        String joinerMappedIp = data.has("joinerMappedIp") ? data.get("joinerMappedIp").getAsString() : null;
        int joinerMappedPort = data.has("joinerMappedPort") ? data.get("joinerMappedPort").getAsInt() : 0;
        int joinerMappedPortDelta = data.has("joinerMappedPortDelta") && !data.get("joinerMappedPortDelta").isJsonNull() ? data.get("joinerMappedPortDelta").getAsInt() : 0;

        // CGNAT: 保存joiner的局域网IP，并检测同公网IP
        String joinerLocalIp = null;
        if (data.has("joinerLocalIp") && !data.get("joinerLocalIp").isJsonNull()) {
            joinerLocalIp = data.get("joinerLocalIp").getAsString();
            state.roomInfo.setJoinerLocalIp(joinerLocalIp);
            VoxLinkMod.LOGGER.info("[HostPunchInfo] Joiner LAN IP: {}", joinerLocalIp);
        }
        if (joinerMappedIp != null) {
            String myPublicIp = state.roomInfo.getHostMappedIp();
            if (myPublicIp != null && myPublicIp.equals(joinerMappedIp)) {
                state.roomInfo.setSameCgnat(true);
                VoxLinkMod.LOGGER.warn("[HostPunchInfo] Same public IP ({}): both behind same CGNAT", joinerMappedIp);
            }
        }
        boolean requestHostLocalIp = data.has("requestHostLocalIp") && data.get("requestHostLocalIp").getAsBoolean();

        // EasyTier DST_PORT_OFFSET: Joiner对称NAT时预测实际映射端
if (joinerMappedPortDelta != 0 && joinerMappedPort > 0) {
            int predicted = joinerMappedPort + joinerMappedPortDelta;
            if (predicted > 0 && predicted <= 65535) {
                VoxLinkMod.LOGGER.info("[HostPunchInfo] Joiner EasySym prediction: STUN port={} + delta={} -> predicted port={}", joinerMappedPort, joinerMappedPortDelta, predicted);
                joinerMappedPort = predicted;
            }
        }

        // 清理旧的 host 打洞器
        java.util.List<UdpHolePuncher> hostMultiPunchers = new java.util.ArrayList<>();
        activeHolePunchers.entrySet().removeIf(e -> {
            if (e.getKey().startsWith("host_")) {
                UdpHolePuncher p = e.getValue();
                if (p != null) {
                    try { p.stopPunch(); } catch (Exception ignored) {}
                    try { p.close(); } catch (Exception ignored) {}
                }
                hostMultiPunchers.add(p);
                return true;
            }
            return false;
        });
        boolean isActive = false;
        for (UdpHolePuncher p : hostMultiPunchers) {
            if (p != null && p.isPunching()) {
                isActive = true;
                break;
            }
        }
        hostPunching = isActive;

        VoxLinkMod.LOGGER.info("[HostPunchInfo] called: joinerMapped={}:{}, delta={}, hostPunching={}, bridgeRunning={}, hostSym={}", joinerMappedIp, joinerMappedPort, joinerMappedPortDelta, hostPunching, P2PBridge.isRunning(), StunDetector.isNatTypeSymmetric(state.roomInfo.getNatType()));

        if (joinerMappedIp == null || joinerMappedPort == 0) {
            VoxLinkMod.LOGGER.warn("[RoomManager] Invalid punch_info from {}: no mapped address", from);
            return;
        }

        if (connectionWon.get()) {
            VoxLinkMod.LOGGER.debug("[HostPunchInfo] already connected, ignoring punch_info");
            return;
        }

        String punchInfoId = joinerMappedIp + ":" + joinerMappedPort;
        if (hostPunching) {
            if (punchInfoId.equals(lastPunchInfoId)) {
                VoxLinkMod.LOGGER.debug("[RoomManager] Already punching same target, ignore duplicate punch_info");
                return;
            }
            VoxLinkMod.LOGGER.info("[RoomManager] Already punching, target changed ({} -> {}), ignore new punch_info to avoid CGNAT IP switch false restart", lastPunchInfoId, punchInfoId);
            return;
        }
        lastPunchInfoId = punchInfoId;

        VoxLinkMod.LOGGER.info("[RoomManager] Received punch_info from {}: {}:{}", from, joinerMappedIp, joinerMappedPort);

        // 多socket并行打洞：symmetric NAT用84个socket（Birthday Attack）
        boolean isHostSym = StunDetector.isNatTypeSymmetric(state.roomInfo.getNatType()) || "unknown".equals(state.roomInfo.getNatType()) || state.roomInfo.getNatType() == null;
        boolean isHostHardSym = StunDetector.isHardSymmetric(state.roomInfo.getNatType());
        // EasyTier: 仅当至少一方是HardSym(端口不可预测)才放弃。EasySym×EasySym 端口可预测，可打洞
        boolean joinerSym = data.has("joinerSymmetric") && data.get("joinerSymmetric").getAsBoolean();
        boolean joinerEasySym = data.has("joinerEasySym") && !data.get("joinerEasySym").isJsonNull() && data.get("joinerEasySym").getAsBoolean();
        // 注册peer信息供relay选择
        if (joinerMappedIp != null && joinerMappedPort > 0) {
            String peerNatType = joinerSym ? (joinerEasySym ? "symmetric_easy_inc" : "symmetric") : "full_cone";
            state.roomInfo.addOrUpdatePeer(from, peerNatType, joinerMappedIp, joinerMappedPort);
        }
        boolean joinerHardSym = joinerSym && !joinerEasySym;
        if (isHostSym && joinerSym && (isHostHardSym || joinerHardSym)
                && !"unknown".equals(state.roomInfo.getNatType()) && state.roomInfo.getNatType() != null) {
            //优化: 双HardSym先试UPnP端口映射(EasyTier prefer_port_mapping), 失败也继续Birthday Attack+端口预测
            //用户原话: "应该是先尝试直接进行打洞连接，然后再尝试使用另一个非对称NAT玩家进行中继"
            int upnpPort = state.roomInfo.getHostPort() > 0 ? state.roomInfo.getHostPort() : 51600;
            icu.wuhui.voxlink.network.UPnPManager.UPnPResult upnpResult =
                    icu.wuhui.voxlink.network.UPnPManager.openUdpPort(upnpPort, "VoxLink-HardSym");
            if (upnpResult.success()) {
                VoxLinkMod.LOGGER.warn("[HostPunchInfo] Both symmetric NAT with HardSym (hostHard={}, joinerHard={}), UPnP UDP port {} mapped, continue UDP punch",
                        isHostHardSym, joinerHardSym, upnpPort);
            } else {
                VoxLinkMod.LOGGER.warn("[HostPunchInfo] Both symmetric NAT with HardSym(hostHard={}, joinerHard={}), UPnP failed, continue Birthday Attack+port prediction (fallback to Relay on failure)",
                        isHostHardSym, joinerHardSym);
            }
        }
        if (isHostSym && joinerSym) {
            VoxLinkMod.LOGGER.info("[HostPunchInfo] Both EasySym (port predictable), continue UDP punch (EasyTier both_easy_sym)");
        }
        // 端口不可达或未检测完时用20个socket：UPnP伪成功场景，TCP兜底一定失败，需要比3个更多来提高birthday attack成功率
        // UNKNOWN表示端口检测还没完成（竞态条件），也按不可达处理
        RoomInfo.PortStatus portStatus = state.roomInfo.getIpv4Status();
        boolean portUnreachable = portStatus == RoomInfo.PortStatus.UNREACHABLE || portStatus == RoomInfo.PortStatus.UNKNOWN;
        if (portUnreachable && !isHostSym) {
            VoxLinkMod.LOGGER.info("[HostPunchInfo] Host port status={}, upgrade to 20 socket birthday attack", portStatus);
        }
        //修复崩溃: 84 socket Birthday Attack 仅用于 Sym×Sym (双方端口都不可预测)
        //Sym×Cone 对方端口固定, 只需少量socket覆盖房主映射端口; 84个socket×3线程=252线程导致进程资源耗尽崩溃
        //NIO Selector 单线程改造后, 84 socket 仅需 1 线程, 消除资源耗尽风险
        final int HOST_MULTI_COUNT;
        if (isHostSym && joinerSym) {
            HOST_MULTI_COUNT = HARD_SYM_SOCKET_COUNT;
            // HardSym×HardSym 自动升档: 指数 range 20→50→100→200→500, 覆盖更大端口空间
            icu.wuhui.voxlink.network.PunchProfile.switchToHardSym("Sym×Sym");
        } else if (isHostSym) {
            HOST_MULTI_COUNT = HOST_MULTI_MIN;
        } else if (portUnreachable) {
            HOST_MULTI_COUNT = HOST_MULTI_DEFAULT;
        } else {
            HOST_MULTI_COUNT = 3;
        }

        // 异步创建socket+STUN，不阻塞信号轮询
        final RoomManager.RoomState fState = state;
        final String fFrom = from;
        final JsonObject fData = data;
        final boolean fRequestHostLocalIp = requestHostLocalIp;
        final int fHostMultiCount = HOST_MULTI_COUNT;
        final String fJoinerMappedIp = joinerMappedIp;
        final int fJoinerMappedPort = joinerMappedPort;

        CompletableFuture.runAsync(() -> {
            java.util.List<UdpHolePuncher> hostPunchers = new java.util.ArrayList<>();
            java.util.List<StunProbe.PublicMappedAddress> mappedAddrs = new java.util.ArrayList<>();
            boolean hostPunchSocketSymmetric = false;

            // 清理旧的host socket, 避免handleHostPunchInfo反复调用导致socket泄漏
            UdpHolePuncher oldHost = activeHolePunchers.remove("host");
            if (oldHost != null) { try { oldHost.close(); } catch (Exception ignored) {} }

            java.util.List<CompletableFuture<StunProbe.PublicMappedAddress[]>> stunFutures = new java.util.ArrayList<>();
            int createdCount = 0;
            for (int i = 0; i < fHostMultiCount; i++) {
                UdpHolePuncher p = new UdpHolePuncher();
                try {
                    if (i == 0) {
                        p.createSocket(fState.roomInfo.getHostPort());
                    } else {
                        p.createSocket();
                    }
                } catch (Exception e) {
                    try { p.createSocket(); } catch (Exception e2) { continue; }
                }
                hostPunchers.add(p);
                activeHolePunchers.put("host_" + i, p);
                createdCount++;
                final UdpHolePuncher fp = p;
                final int idx = i;
                stunFutures.add(CompletableFuture.supplyAsync(() -> {
                    //优化: discoverMappedAddressDual 并发2个STUN, 比2次顺序快一倍(800ms vs 1.6s)
                    StunProbe.PublicMappedAddress[] dual = StunProbe.discoverMappedAddressDual(
                            fp.getSocket(),
                            StunDetector.getAllStunUrls().get(0),
                            StunDetector.getAllStunUrls().get(1));
                    VoxLinkMod.LOGGER.info("[HostPunchInfo] Socket#{} STUN(dual): {} vs {} (localPort={})", idx,
                            dual[0] != null ? dual[0].port() : -1, dual[1] != null ? dual[1].port() : -1, fp.getSocket().getLocalPort());
                    return dual;
                }));
            }
            VoxLinkMod.LOGGER.info("[HostPunchInfo] Parallel start {}/{} socket+dual STUN tasks (no sleep)", createdCount, fHostMultiCount);

            //优化: early success - 收集到足够端口(>=32或全部完成)就继续, 不等最慢的STUN拖累
            int minRequired = Math.min(BIRTHDAY_SOCKET_COUNT, fHostMultiCount);
            long stunDeadline = System.currentTimeMillis() + TCP_CONNECT_TIMEOUT_MS;
            while (System.currentTimeMillis() < stunDeadline) {
                int done = 0, success = 0;
                for (CompletableFuture<StunProbe.PublicMappedAddress[]> f : stunFutures) {
                    if (f.isDone()) {
                        done++;
                        try {
                            StunProbe.PublicMappedAddress[] r = f.getNow(null);
                            if (r != null && (r[0] != null || r[1] != null)) success++;
                        } catch (Exception ignored) {}
                    }
                }
                if (success >= minRequired || done == stunFutures.size()) break;
                try { Thread.sleep(SHORT_SLEEP_MS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
            String mappedIp = null;
            for (int i = 0; i < stunFutures.size(); i++) {
                try {
                    StunProbe.PublicMappedAddress[] addrs = stunFutures.get(i).getNow(null);
                    if (addrs != null && addrs[1] != null) {
                        mappedAddrs.add(addrs[1]);
                        if (mappedIp == null) mappedIp = addrs[1].ip();
                        if (addrs[0] != null && addrs[0].port() != addrs[1].port()) hostPunchSocketSymmetric = true;
                    }
                } catch (Exception ignored) {}
            }

            if (mappedAddrs.isEmpty()) {
                VoxLinkMod.LOGGER.error("[HostPunchInfo] All STUN queries failed");
                return;
            }
            if (connectionWon.get()) {
                VoxLinkMod.LOGGER.info("[HostPunchInfo] Connection established, discard late 84-socket punch task");
                for (UdpHolePuncher p : hostPunchers) {
                    try { p.close(); } catch (Exception ignored) {}
                }
                return;
            }

            // 发送所有映射端口给Joiner
            JsonObject symData = new JsonObject();
            if (hostPunchSocketSymmetric || StunDetector.isNatTypeSymmetric(fState.roomInfo.getNatType()) || "unknown".equals(fState.roomInfo.getNatType()) || fState.roomInfo.getNatType() == null) {
                symData.addProperty("hostSymmetric", true);
            }
            boolean hostEasySymMapped = hostPunchSocketSymmetric && StunDetector.isEasySymmetric(fState.roomInfo.getNatType());
            if (hostEasySymMapped) {
                symData.addProperty("hostEasySym", true);
            }
            symData.addProperty("hostMappedIp", mappedIp);
            symData.addProperty("hostMappedPort", mappedAddrs.get(0).port());
            if (fRequestHostLocalIp || fState.roomInfo.isSameCgnat()) {
                String myLocalIp = StunDetector.getLocalIpAddress();
                if (myLocalIp != null && !myLocalIp.isEmpty()) {
                    symData.addProperty("hostLocalIp", myLocalIp);
                    VoxLinkMod.LOGGER.info("[HostPunchInfo] CGNAT scenario include hostLocalIp: {}", myLocalIp);
                }
            }
            JsonArray portsArray = new JsonArray();
            for (StunProbe.PublicMappedAddress a : mappedAddrs) portsArray.add(a.port());
            symData.add("hostMappedPorts", portsArray);
            VoxLinkMod.LOGGER.info("[HostPunchInfo] holepunch_mapped: {} ports={} (symmetric={})", mappedIp, portsArray, hostPunchSocketSymmetric);
            signalingClient.sendSignal(fState.roomInfo.getCode(), fState.roomInfo.getToken(),
                    true, "holepunch_mapped", symData, fFrom)
                    .exceptionally(e -> { VoxLinkMod.LOGGER.debug("holepunch_mapped send failed: {}", e.getMessage()); return null; });

            hostPunching = true;
            final String clientId = fFrom;
            //debounce host侧状态机同步: 开始打洞先推UDP_PUNCH 后续TRANSPORT_SETUP/CONNECTED才是合法转换
            ConnectionState.transitionTo(ConnectionState.UDP_PUNCH, "Host开始打洞 client=" + clientId);
            java.util.concurrent.atomic.AtomicBoolean hostPunchWon = new java.util.concurrent.atomic.AtomicBoolean(false);

            for (UdpHolePuncher p : hostPunchers) {
                p.setOnPeerPunchReceived(addr -> {
                    String code = fState.roomInfo.getCode();
                    String token = fState.roomInfo.getToken();
                    JsonObject portData = new JsonObject();
                    portData.addProperty("peer_ip", addr.getAddress().getHostAddress());
                    portData.addProperty("peer_port", addr.getPort());
                    signalingClient.sendSignal(code, token, true, "peer_port", portData, fFrom)
                            .exceptionally(e -> { VoxLinkMod.LOGGER.debug("peer_port signal failed: {}", e.getMessage()); return null; });
                });
            }

            ScheduledFuture<?> punchTimeout = scheduler.schedule(() -> {
                if (hostPunching) {
                    //debounce 60s兜底清理 打洞失败已由thenAccept清理 此处只防异常泄漏
                    VoxLinkMod.LOGGER.info("[HostPunchInfo] 60s fallback cleanup host socket client={}", clientId);
                    hostPunching = false;
                    lastPunchInfoId = "";
                    activeHolePunchers.remove("host");
                    activeHolePunchers.entrySet().removeIf(e -> e.getKey().startsWith("host_"));
                    for (UdpHolePuncher p : hostPunchers) {
                        try { p.cancel(); p.close(); } catch (Exception ignored) {}
                    }
                }
            }, 60, TimeUnit.SECONDS);

            boolean joinerSymmetric = fData.has("joinerSymmetric") && fData.get("joinerSymmetric").getAsBoolean();
            String hostNat = fState.roomInfo.getNatType();
            int hostPortRange = 0;
            if (joinerSymmetric && (StunDetector.isNatTypeSymmetric(hostNat) || hostPunchSocketSymmetric)) {
                hostPortRange = PORT_RANGE_DEFAULT;
            } else if (joinerSymmetric) {
                hostPortRange = PORT_RANGE_DEFAULT;
            }
            VoxLinkMod.LOGGER.info("[HostPunchInfo] {} sockets parallel punch to {}:{} range=±{}", hostPunchers.size(), fJoinerMappedIp, fJoinerMappedPort, hostPortRange);

            //lazy触发: 等首包或5s兜底再开打, 避免无意义场景浪费
            P2PBridge.armLazyP2pDeadline();
            while (!connectionWon.get() && roomManager.currentRoom.get() == fState) {
                if (P2PBridge.shouldStartPunching()) {
                    if (!P2PBridge.isTrafficDetected()) {
                        VoxLinkMod.LOGGER.info("5s no traffic, fallback start punch");
                    }
                    break;
                }
                try { Thread.sleep(SHORT_SLEEP_MS); } catch (InterruptedException e) { return; }
            }
            if (connectionWon.get() || roomManager.currentRoom.get() != fState) {
                VoxLinkMod.LOGGER.info("[HostPunchInfo] Connected or room changed during lazy, abort punch");
                for (UdpHolePuncher p : hostPunchers) {
                    try { p.close(); } catch (Exception ignored) {}
                }
                return;
            }

            for (int i = 0; i < hostPunchers.size(); i++) {
                final UdpHolePuncher mp = hostPunchers.get(i);
                final int idx = i;
                mp.punchWithPortPrediction(fJoinerMappedIp, fJoinerMappedPort, hostPortRange).thenAccept(result -> {
                    if (!result.isSuccess()) {
                        PunchFailureClassifier.FailureReason reason = PunchFailureClassifier.classify(result);
                        lastPunchResult = result.withReason(reason);
                        PunchTuner.nextParams(PunchProfile.current(), localNatClass, remoteNatClass, 1, MAX_CONNECTION_CYCLES, reason, lastPunchResult);
                        VoxLinkMod.LOGGER.info("[ConnectionManager] Punch failed reason={} recvPunch={} recvAck={}", reason, result.socketsReceivedPunch, result.socketsReceivedAck);
                        //debounce 打洞失败立即清理hostPunching 让joiner下一个punch_info能重新触发打洞
                        hostPunching = false;
                        lastPunchInfoId = "";
                        activeHolePunchers.remove("host");
                        activeHolePunchers.entrySet().removeIf(e -> e.getKey().startsWith("host_"));
                        try { mp.close(); } catch (Exception ignored) {}
                        return;
                    }
                    DatagramSocket socket = result.getSuccessSocket();
                    if (!hostPunchWon.compareAndSet(false, true)) {
                        try { mp.close(); } catch (Exception ignored) {}
                        return;
                    }
                    if (roomManager.currentRoom.get() != fState || !connectionWon.compareAndSet(false, true)) {
                        try { mp.close(); } catch (Exception ignored) {}
                        return;
                    }
                    VoxLinkMod.LOGGER.info("[HostPunchInfo] Socket#{} punch success!", idx);
                    mp.markSocketTransferred();
                    stopAllPunchingAfterHostBridge();
                    mp.stopPunch();
                    final DatagramSocket winSocket = socket;
                    final UdpHolePuncher winPuncher = mp;

                    scheduler.submit(() -> {
                        try {
                            establishUdpTransport(fState, winSocket, winPuncher,
                                    new InetSocketAddress(fJoinerMappedIp, fJoinerMappedPort), clientId, true, clientId);
                        } catch (Exception e) {
                            VoxLinkMod.LOGGER.error("[HostPunchInfo] Transport create failed: {}", e.getMessage());
                            winPuncher.close();
                        }
                    });
                }).exceptionally(e -> {
                    VoxLinkMod.LOGGER.debug("[HostPunchInfo] Socket#{} punch failed: {}", idx, e.getMessage());
                    return null;
                });
            }
        }, punchExecutor);
    }

    public void handleJoinerPunchInfo(RoomManager.RoomState state, String from, JsonObject data) {
        String hostMappedIp = null;
        int hostMappedPort = 0;
        if (data.has("hostMappedIp") && !data.get("hostMappedIp").isJsonNull()) {
            hostMappedIp = data.get("hostMappedIp").getAsString();
        }
        if (data.has("hostMappedPort") && !data.get("hostMappedPort").isJsonNull()) {
            hostMappedPort = data.get("hostMappedPort").getAsInt();
        }

        if (hostMappedIp == null || hostMappedPort <= 0) return;

        VoxLinkMod.LOGGER.info("[RoomManager] Host mapped address in punch_info: {}:{}", hostMappedIp, hostMappedPort);
        state.roomInfo.setHostMappedAddress(hostMappedIp, hostMappedPort);

        UdpHolePuncher joinerPuncher = activeHolePunchers.get("joiner");
        if (joinerPuncher != null && connectionCycleActive.get()) {
            VoxLinkMod.LOGGER.info("[RoomManager] Update joiner punch target to {}:{}", hostMappedIp, hostMappedPort);
            joinerPuncher.updateTarget(hostMappedIp, hostMappedPort);
        }
    }

    public void handlePeerPort(String from, JsonObject data) {
        String peerIp = data.has("peer_ip") ? data.get("peer_ip").getAsString() : null;
        int peerPort = data.has("peer_port") ? data.get("peer_port").getAsInt() : 0;
        if (peerIp == null || peerPort <= 0) return;

        UdpHolePuncher puncher = activeHolePunchers.get(from.contains("host") ? "joiner" : "host");
        if (puncher == null) return;

        VoxLinkMod.LOGGER.info("[RoomManager] Received peer_port signal: update target to {}:{}", peerIp, peerPort);
        puncher.updateTarget(peerIp, peerPort);
    }

    public void handleReverseHolepunchOffer(String from, JsonObject data) {
        RoomManager.RoomState state = roomManager.currentRoom.get();
        if (state == null || state == RoomManager.PENDING || !state.roomInfo.isHost()) return;

        // RTT同步: 等到约定的发包时刻再开始启动打洞
        long syncTime = state.roomInfo.getPunchSyncTimeMs();
        if (syncTime > 0) {
            long delay = syncTime - System.currentTimeMillis();
            if (delay > 0 && delay < RTT_SYNC_MAX_DELAY_MS) {
                VoxLinkMod.LOGGER.info("[ReversePunch] RTT sync wait: start host punch after {}ms", delay);
                scheduler.schedule(() -> {
                    if (roomManager.currentRoom.get() == state && !connectionWon.get()) {
                        handleReverseHolepunchOfferDelayed(from, data);
                    }
                }, delay, TimeUnit.MILLISECONDS);
                return;
            }
        }
        handleReverseHolepunchOfferDelayed(from, data);
    }

    private void handleReverseHolepunchOfferDelayed(String from, JsonObject data) {
        RoomManager.RoomState state = roomManager.currentRoom.get();
        if (state == null || state == RoomManager.PENDING || !state.roomInfo.isHost()) return;

        if (activeUdpTransports.containsKey(from)) {
            VoxLinkMod.LOGGER.info("[ReversePunch] Active transport to {}, ignore reverse_holepunch_offer", from);
            return;
        }

        String joinerMappedIp = data.has("joinerMappedIp") ? data.get("joinerMappedIp").getAsString() : null;
        int joinerMappedPort = data.has("joinerMappedPort") ? data.get("joinerMappedPort").getAsInt() : 0;
        boolean joinerSymmetric = data.has("joinerSymmetric") && data.get("joinerSymmetric").getAsBoolean();
        int joinerMappedPortDelta = data.has("joinerMappedPortDelta") && !data.get("joinerMappedPortDelta").isJsonNull() ? data.get("joinerMappedPortDelta").getAsInt() : 0;
        // 注册joiner peer信息供relay选择
        if (joinerMappedIp != null && joinerMappedPort > 0) {
            String joinerNatType = joinerSymmetric ? "symmetric" : "full_cone";
            state.roomInfo.addOrUpdatePeer(from, joinerNatType, joinerMappedIp, joinerMappedPort);
        }

        // EasyTier DST_PORT_OFFSET: Joiner对称NAT时预测实际映射端
if (joinerMappedPortDelta != 0 && joinerMappedPort > 0) {
            int predicted = joinerMappedPort + joinerMappedPortDelta;
            if (predicted > 0 && predicted <= 65535) {
                VoxLinkMod.LOGGER.info("[ReversePunch] Joiner EasySym prediction: {} + {} = {}", joinerMappedPort, joinerMappedPortDelta, predicted);
                joinerMappedPort = predicted;
            }
        }

        if (joinerMappedIp == null || joinerMappedPort == 0) {
            VoxLinkMod.LOGGER.warn("[ReversePunch] Invalid reverse_holepunch_offer: no mapped address");
            return;
        }

        VoxLinkMod.LOGGER.info("[ReversePunch] Host received reverse_holepunch_offer from {}: {}:{} (joinerSym={})", from, joinerMappedIp, joinerMappedPort, joinerSymmetric);

        UdpHolePuncher existingReverse = activeHolePunchers.get("hostRev");
        if (existingReverse != null && existingReverse.isPunching()) {
            VoxLinkMod.LOGGER.info("[ReversePunch] already reverse punching, update target to {}:{}", joinerMappedIp, joinerMappedPort);
            existingReverse.updateTarget(joinerMappedIp, joinerMappedPort);
            return;
        }

        UdpHolePuncher puncher = new UdpHolePuncher();
        try {
            puncher.createSocket();
        } catch (Exception e) {
            VoxLinkMod.LOGGER.error("[ReversePunch] create socket failed: {}", e.getMessage());
            return;
        }
        VoxLinkMod.LOGGER.info("[ReversePunch] Host reverse punch socket: localPort={}", puncher.getSocket().getLocalPort());
        activeHolePunchers.put("hostRev", puncher);

        // 异步STUN，不阻塞信号轮询
        final RoomManager.RoomState fState = state;
        final String fFrom = from;
        final JsonObject fData = data;
        final UdpHolePuncher fPuncher = puncher;
        final String fJoinerMappedIp = joinerMappedIp;
        final int fJoinerMappedPort = joinerMappedPort;
        final boolean fJoinerSymmetric = joinerSymmetric;

        punchExecutor.execute(() -> {
            VoxLinkMod.LOGGER.info("[ReversePunch] dual STUN on reverse socket...");
            StunProbe.PublicMappedAddress m1 = null, m2 = null;
            try {
                m1 = fPuncher.discoverMappedAddress(java.util.List.of(StunDetector.getAllStunUrls().get(0)));
                VoxLinkMod.LOGGER.info("[ReversePunch] Host reverse STUN #1: ip={}, port={} (localPort={})",
                        m1 != null ? m1.ip() : "null", m1 != null ? m1.port() : -1, fPuncher.getSocket().getLocalPort());
                m2 = fPuncher.discoverMappedAddress(java.util.List.of(StunDetector.getAllStunUrls().get(1)));
                VoxLinkMod.LOGGER.info("[ReversePunch] Host reverse STUN #2: ip={}, port={} (localPort={})",
                        m2 != null ? m2.ip() : "null", m2 != null ? m2.port() : -1, fPuncher.getSocket().getLocalPort());
            } catch (Exception e) {
                VoxLinkMod.LOGGER.warn("[ReversePunch] Dual STUN failed: {}", e.getMessage());
            }
            StunProbe.PublicMappedAddress hostMapped1 = m1;
            StunProbe.PublicMappedAddress hostMapped2 = m2;
            boolean hostPunchSocketSymmetric = false;
            StunProbe.PublicMappedAddress hostMapped = null;
            if (hostMapped1 != null && hostMapped2 != null) {
                if (hostMapped1.port() != hostMapped2.port()) {
                    hostPunchSocketSymmetric = true;
                    VoxLinkMod.LOGGER.info("[ReversePunch] Host punch socket STUN: symmetric detected ({} vs {})", hostMapped1.port(), hostMapped2.port());
                }
                hostMapped = hostMapped2;
            } else {
                hostMapped = hostMapped1 != null ? hostMapped1 : hostMapped2;
            }

            JsonObject punchData = new JsonObject();
            if (hostMapped != null) {
                punchData.addProperty("hostMappedIp", hostMapped.ip());
                punchData.addProperty("hostMappedPort", hostMapped.port());
            }
            boolean hostSym = hostPunchSocketSymmetric || StunDetector.isNatTypeSymmetric(fState.roomInfo.getNatType());
            if (hostSym) {
                punchData.addProperty("hostSymmetric", true);
            }
            boolean hostEasySymRev = hostPunchSocketSymmetric && StunDetector.isEasySymmetric(fState.roomInfo.getNatType());
            if (hostEasySymRev) {
                punchData.addProperty("hostEasySym", true);
            }

            signalingClient.sendSignal(fState.roomInfo.getCode(), fState.roomInfo.getToken(),
                    true, "reverse_punch_info", punchData, fFrom)
                    .exceptionally(e -> { VoxLinkMod.LOGGER.debug("[ReversePunch] reverse_punch_info send failed: {}", e.getMessage()); return null; });

            fPuncher.setOnPeerPunchReceived(addr -> {
                String code = fState.roomInfo.getCode();
                String token = fState.roomInfo.getToken();
                JsonObject portData = new JsonObject();
                portData.addProperty("peer_ip", addr.getAddress().getHostAddress());
                portData.addProperty("peer_port", addr.getPort());
                signalingClient.sendSignal(code, token, true, "peer_port", portData, fFrom)
                        .exceptionally(e -> { VoxLinkMod.LOGGER.debug("peer_port signal failed: {}", e.getMessage()); return null; });
            });

            String hostNat = fState.roomInfo.getNatType();
            boolean hostSymmetric = StunDetector.isNatTypeSymmetric(hostNat);

            java.util.List<Integer> allJoinerPorts = new java.util.ArrayList<>();
            if (fData.has("joinerMappedPorts") && fData.get("joinerMappedPorts").isJsonArray()) {
                for (var elem : fData.getAsJsonArray("joinerMappedPorts")) {
                    allJoinerPorts.add(elem.getAsInt());
                }
            }
            if (allJoinerPorts.isEmpty()) {
                allJoinerPorts.add(fJoinerMappedPort);
            }

            int hostPortRange = PORT_RANGE_DEFAULT;
            if (hostSymmetric && fJoinerSymmetric) {
                hostPortRange = 0;
            } else if (fJoinerSymmetric && allJoinerPorts.size() > 1) {
                hostPortRange = 0;
                VoxLinkMod.LOGGER.info("[ReversePunch] Birthday attack mode: host punch {} joiner ports: {}", allJoinerPorts.size(), allJoinerPorts);
            } else if (fJoinerSymmetric) {
                hostPortRange = PORT_RANGE_WIDE;
            } else if (hostSymmetric) {
                hostPortRange = PORT_RANGE_DEFAULT;
            } else if ("moderate".equals(hostNat) || "port_restricted_cone".equals(hostNat)) {
                hostPortRange = PunchProfile.current().portPredictionMaxRange;
            } else {
                hostPortRange = PORT_RANGE_DEFAULT;
            }
            VoxLinkMod.LOGGER.info("[ReversePunch] Host punch to joiner {} (range=±{}, hostNat={}, joinerSym={}, birthdayPorts={})",
                    fJoinerMappedIp, hostPortRange, hostNat, fJoinerSymmetric, allJoinerPorts.size() > 1 ? allJoinerPorts : "no");

            final String clientId = fFrom;
            final UdpHolePuncher currentPuncher = fPuncher;
            final String fRevJoinerMappedIp = fJoinerMappedIp;
            final int fRevJoinerMappedPort = fJoinerMappedPort;

            ScheduledFuture<?> punchTimeout = scheduler.schedule(() -> {
                if (activeHolePunchers.get("hostRev") == currentPuncher) {
                    VoxLinkMod.LOGGER.warn("[ReversePunch] Host reverse punch timeout: {}", clientId);
                    currentPuncher.cancel();
                    currentPuncher.close();
                    activeHolePunchers.remove("hostRev");
                }
            }, UDP_PUNCH_TIMEOUT_S + EXTRA_TIMEOUT_SEC, TimeUnit.SECONDS);

            java.util.concurrent.CompletableFuture<PunchResult> punchFuture;
            if (allJoinerPorts.size() > 1) {
                // per-dest端口偏移
                java.util.Set<Integer> expandedPorts = new java.util.LinkedHashSet<>();
                for (int port : allJoinerPorts) {
                    for (int offset = -PORT_RANGE_DEFAULT; offset <= PORT_RANGE_DEFAULT; offset++) {
                        int p = port + offset;
                        if (p > 0 && p <= 65535) expandedPorts.add(p);
                    }
                }
                java.util.List<Integer> portList = new java.util.ArrayList<>(expandedPorts);
                VoxLinkMod.LOGGER.info("[ReversePunch] Birthday attack: {} ports expanded to {} ports (range {}-{})",
                        allJoinerPorts.size(), portList.size(),
                        allJoinerPorts.get(0) - PORT_RANGE_DEFAULT, allJoinerPorts.get(allJoinerPorts.size() - 1) + PORT_RANGE_DEFAULT);
                punchFuture = currentPuncher.punchMultiPort(fRevJoinerMappedIp, portList);
            } else {
                punchFuture = currentPuncher.punchWithPortPrediction(fRevJoinerMappedIp, fRevJoinerMappedPort, hostPortRange, true);
            }

            punchFuture.thenAccept(result -> {
                if (!result.isSuccess()) {
                    PunchFailureClassifier.FailureReason reason = PunchFailureClassifier.classify(result);
                    lastPunchResult = result.withReason(reason);
                    PunchTuner.nextParams(PunchProfile.current(), localNatClass, remoteNatClass, 1, MAX_CONNECTION_CYCLES, reason, lastPunchResult);
                    VoxLinkMod.LOGGER.info("[ConnectionManager] Punch failed reason={} recvPunch={} recvAck={}", reason, result.socketsReceivedPunch, result.socketsReceivedAck);
                    return;
                }
                DatagramSocket socket = result.getSuccessSocket();
                if (roomManager.currentRoom.get() != fState || !connectionWon.compareAndSet(false, true)) {
                    currentPuncher.close();
                    return;
                }
                VoxLinkMod.LOGGER.info("[ReversePunch] Host reverse punch success, connected to joiner {}:{}", fRevJoinerMappedIp, fRevJoinerMappedPort);
                currentPuncher.markSocketTransferred();
                stopAllPunchingAfterHostBridge();

                currentPuncher.stopPunch();
                final DatagramSocket hostPunchSocket = socket;
                final UdpHolePuncher hostPuncherRef = currentPuncher;

                scheduler.submit(() -> {
                    try {
                        establishUdpTransport(fState, hostPunchSocket, hostPuncherRef,
                                new InetSocketAddress(fRevJoinerMappedIp, fRevJoinerMappedPort), clientId, true, clientId);
                    } catch (Exception e) {
                        VoxLinkMod.LOGGER.error("[ReversePunch] Host UDP transport create failed: {}", e.getMessage());
                        hostPuncherRef.close();
                    }
                });
            }).exceptionally(e -> {
                punchTimeout.cancel(false);
                VoxLinkMod.LOGGER.warn("[ReversePunch] Host reverse punch failed {}: {}", clientId, e.getMessage());
                currentPuncher.cancel();
                currentPuncher.close();
                activeHolePunchers.remove("hostRev");
                return null;
            });
        });
    }

    public void handleReversePunchInfo(String from, JsonObject data) {
        RoomManager.RoomState state = roomManager.currentRoom.get();
        if (state == null || state == RoomManager.PENDING || state.roomInfo.isHost()) return;

        if (!connectionCycleActive.get()) {
            VoxLinkMod.LOGGER.info("[ReversePunch] Not in connection cycle, ignore reverse_punch_info");
            return;
        }

        String hostMappedIp = null;
        int hostMappedPort = 0;
        if (data.has("hostMappedIp") && !data.get("hostMappedIp").isJsonNull()) {
            hostMappedIp = data.get("hostMappedIp").getAsString();
        }
        if (data.has("hostMappedPort") && !data.get("hostMappedPort").isJsonNull()) {
            hostMappedPort = data.get("hostMappedPort").getAsInt();
        }
        boolean hostSymmetric = data.has("hostSymmetric") && data.get("hostSymmetric").getAsBoolean();
        boolean hostEasySym = data.has("hostEasySym") && !data.get("hostEasySym").isJsonNull() && data.get("hostEasySym").getAsBoolean();
        if (hostSymmetric) state.roomInfo.setHostSymmetric(true);
        if (hostEasySym) state.roomInfo.setHostEasySym(true);

        UdpHolePuncher existingPuncher = activeHolePunchers.get("joiner_reverse");
        if (existingPuncher != null && existingPuncher.isPunching()) {
            // 用delta预测
            int updatePort = hostMappedPort;
            int delta = state.roomInfo.getHostMappedPortDelta();
            if (hostMappedPort > 0 && delta != 0) {
                int predicted = hostMappedPort + delta;
                if (predicted > 0 && predicted <= 65535) updatePort = predicted;
            }
            VoxLinkMod.LOGGER.info("[ReversePunch] Already reverse punching, update target to {}:{}", hostMappedIp, updatePort);
            if (hostMappedIp != null && updatePort > 0) {
                existingPuncher.updateTarget(hostMappedIp, updatePort);
            }
            // 同步更新主joiner打洞目标，确保tryUdpPunch也用正确的host端口
            UdpHolePuncher joinerPuncher = activeHolePunchers.get("joiner");
            if (joinerPuncher != null && joinerPuncher.isPunching() && hostMappedIp != null && updatePort > 0) {
                VoxLinkMod.LOGGER.info("[ReversePunch] Sync update main joiner punch target to {}:{}", hostMappedIp, updatePort);
                joinerPuncher.updateTarget(hostMappedIp, updatePort);
            }
            return;
        }

        if (hostMappedIp == null || hostMappedPort <= 0) {
            hostMappedIp = state.roomInfo.getHostMappedIp();
            hostMappedPort = state.roomInfo.getHostMappedPort();
        }
        if (hostMappedIp == null || hostMappedPort <= 0) {
            VoxLinkMod.LOGGER.warn("[ReversePunch] No host mapped address in reverse_punch_info");
            return;
        }

        if (hostSymmetric) {
            state.roomInfo.setHostSymmetric(true);
        }

        VoxLinkMod.LOGGER.info("[ReversePunch] Joiner received reverse_punch_info: {}:{} (hostSym={})", hostMappedIp, hostMappedPort, hostSymmetric);

        java.util.List<UdpHolePuncher> birthdayPunchers = new java.util.ArrayList<>();
        java.util.List<String> birthdayKeys = new java.util.ArrayList<>();
        for (var entry : activeHolePunchers.entrySet()) {
            if (entry.getKey().startsWith("joiner_birthday_")) {
                birthdayPunchers.add(entry.getValue());
                birthdayKeys.add(entry.getKey());
            }
        }

        if (!birthdayPunchers.isEmpty()) {
            boolean anyPunching = birthdayPunchers.stream().anyMatch(UdpHolePuncher::isPunching);
            if (anyPunching) {
                VoxLinkMod.LOGGER.info("[BirthdayPunch] Already punching, update target to {}:{}, {} sockets total",
                        hostMappedIp, hostMappedPort, birthdayPunchers.size());
                for (UdpHolePuncher p : birthdayPunchers) {
                    if (p.isPunching()) {
                        p.updateTarget(hostMappedIp, hostMappedPort);
                    }
                }
                return;
            }
            VoxLinkMod.LOGGER.info("[BirthdayPunch] Start birthday attack {} sockets punch to {}:{}",
                    birthdayPunchers.size(), hostMappedIp, hostMappedPort);
            startBirthdayPunchPhase2(state, birthdayPunchers, birthdayKeys, hostMappedIp, hostMappedPort, hostSymmetric, false);
            return;
        }

        UdpHolePuncher puncher = activeHolePunchers.get("joiner_reverse");
        if (puncher == null || puncher.getSocket() == null || puncher.getSocket().isClosed()) {
            VoxLinkMod.LOGGER.warn("[ReversePunch] No joiner_reverse puncher available (puncher={}, socket={}, closed={})",
                    puncher != null, puncher != null ? puncher.getSocket() != null : false,
                    puncher != null && puncher.getSocket() != null ? puncher.getSocket().isClosed() : false);
            showConnectFailed(state);
            return;
        }

        VoxLinkMod.LOGGER.info("[ReversePunch] Joiner reverse punch state: localPort={}, punching={}",
                puncher.getSocket().getLocalPort(), puncher.isPunching());

        puncher.setOnPeerPunchReceived(addr -> {
            VoxLinkMod.LOGGER.info("[ReversePunch] Joiner received peer punch packet {}:{} — send peer_port signal", addr.getAddress().getHostAddress(), addr.getPort());
            String code = state.roomInfo.getCode();
            String token = state.roomInfo.getToken();
            JsonObject portData = new JsonObject();
            portData.addProperty("peer_ip", addr.getAddress().getHostAddress());
            portData.addProperty("peer_port", addr.getPort());
            signalingClient.sendSignal(code, token, false, "peer_port", portData, "host")
                    .exceptionally(e -> { VoxLinkMod.LOGGER.debug("peer_port signal send failed: {}", e.getMessage()); return null; });
        });

        boolean joinerIsSymmetric = (stunProbeResult != null && stunProbeResult.natType.isSymmetric());
        int portRange = PORT_RANGE_DEFAULT;
        if (joinerIsSymmetric) {
            portRange = PORT_RANGE_DEFAULT;
            VoxLinkMod.LOGGER.info("[ReversePunch] Joiner is symmetric NAT — use small range (±30) to open NAT mapping");
        } else if (hostSymmetric) {
            portRange = PunchProfile.current().portPredictionMaxRange;
        } else {
            portRange = PORT_RANGE_DEFAULT;
        }

        VoxLinkMod.LOGGER.info("[ReversePunch] Joiner punch to host {}:{} (range=±{}, joinerSym={}, hostSym={})",
                hostMappedIp, hostMappedPort, portRange, joinerIsSymmetric, hostSymmetric);

        final UdpHolePuncher finalPuncher = puncher;
        final String fHostMappedIp = hostMappedIp;
        final int fHostMappedPort = hostMappedPort;

        puncher.punchWithPortPrediction(hostMappedIp, hostMappedPort, portRange, true).thenAccept(result -> {
            if (!result.isSuccess()) {
                PunchFailureClassifier.FailureReason reason = PunchFailureClassifier.classify(result);
                lastPunchResult = result.withReason(reason);
                PunchTuner.nextParams(PunchProfile.current(), localNatClass, remoteNatClass, 1, MAX_CONNECTION_CYCLES, reason, lastPunchResult);
                VoxLinkMod.LOGGER.info("[ConnectionManager] Punch failed reason={} recvPunch={} recvAck={}", reason, result.socketsReceivedPunch, result.socketsReceivedAck);
                return;
            }
            DatagramSocket socket = result.getSuccessSocket();
            if (roomManager.currentRoom.get() != state || !connectionWon.compareAndSet(false, true)) {
                try { finalPuncher.close(); } catch (Exception ignored) {}
                return;
            }
            VoxLinkMod.LOGGER.info("[ReversePunch] Joiner reverse punch success {}:{}", fHostMappedIp, fHostMappedPort);
            finalPuncher.markSocketTransferred();
            stopAllPunchingAfterHostBridge();

            finalPuncher.stopPunch();
            final DatagramSocket punchSocket = socket;
            final UdpHolePuncher puncherRef = finalPuncher;

            // 用实际收到包的地址，而非STUN映射地址（对称NAT端口会偏移）
            InetSocketAddress actualAddr = puncherRef.getActualRemoteAddress();
            if (actualAddr == null) actualAddr = new InetSocketAddress(fHostMappedIp, fHostMappedPort);
            final InetSocketAddress finalTargetAddr = actualAddr;
            VoxLinkMod.LOGGER.info("[ReversePunch] Actual target address: {} (STUN mapping: {}:{})", finalTargetAddr, fHostMappedIp, fHostMappedPort);

            scheduler.submit(() -> {
                try {
                    establishUdpTransport(state, punchSocket, puncherRef,
                            finalTargetAddr, "joiner", false, null);
                } catch (Exception e) {
                    VoxLinkMod.LOGGER.error("[ReversePunch] Joiner UDP transport create failed: {}", e.getMessage());
                    try { puncherRef.close(); } catch (Exception ignored) {}
                    showConnectFailed(state);
                }
            });
        }).exceptionally(e -> {
            VoxLinkMod.LOGGER.warn("[ReversePunch] Joiner reverse punch failed: {}", e.getMessage());
            finalPuncher.cancel();
            finalPuncher.close();
            activeHolePunchers.remove("joiner_reverse");
            showConnectFailed(state);
            return null;
        });
    }

    public void handleTcpSimopenRequest(String from, JsonObject data) {
        String joinerMappedIp = data.has("joinerMappedIp") ? data.get("joinerMappedIp").getAsString() : null;
        int joinerMappedPort = data.has("joinerMappedPort") ? data.get("joinerMappedPort").getAsInt() : 0;
        if (joinerMappedIp == null || joinerMappedPort == 0) return;

        if (connectionWon.get()) {
            VoxLinkMod.LOGGER.info("[TcpSimOpen] already connected, ignoring tcp_simopen_request");
            return;
        }

        int hostPort = P2PBridge.getHostPort() > 0 ? P2PBridge.getHostPort() : 25565;
        //debounce host桥已listening时hostPort被自己占用 SimOpen client bind必失败 跳过避免无效重试
        if (P2PBridge.getHostPort() > 0) {
            VoxLinkMod.LOGGER.info("[TcpSimOpen] Host bridge listening port={}, skip SimOpen", hostPort);
            return;
        }
        VoxLinkMod.LOGGER.info("[TcpSimOpen] Host received joiner {} request, try TCP connect {}:{}", from, joinerMappedIp, joinerMappedPort);
        ConnectionFallback hostSimFallback = trackFallback(new ConnectionFallback());
        hostSimFallback.tryTcpSimultaneousOpen(joinerMappedIp, joinerMappedPort, hostPort).thenAccept(result -> {
            if (result.success && connectionWon.compareAndSet(false, true)) {
                VoxLinkMod.LOGGER.info("[TcpSimOpen] Host connected to joiner via TCP SimOpen!");
                RoomManager.RoomState st = roomManager.currentRoom.get();
                if (st != null) {
                    connectViaBridge(st, result);
                }
            } else if (result.success) {
                VoxLinkMod.LOGGER.info("[TcpSimOpen] TCP SimOpen success but connection occupied, ignore");
            } else {
                VoxLinkMod.LOGGER.info("[TcpSimOpen] Host TCP SimOpen failed: {}", result.failureReason);
            }
        });
    }

    public void handleHolePunchAnswer(String from, JsonObject data) {
        RoomManager.RoomState state = roomManager.currentRoom.get();
        if (state != null && state != RoomManager.PENDING && state.roomInfo.isHost()) {
            VoxLinkMod.LOGGER.info("Received punch ack from joiner {}", from);
        }
    }

    public int getEffectiveMaxCycles() {
        if (stunProbeResult != null) {
            int reachable = stunProbeResult.reachableStunUrls.size();
            if (stunProbeResult.natType.isSymmetric()) return Math.max(SYMMETRIC_NAT_CYCLES, Math.min(reachable, SYMMETRIC_NAT_CYCLES));
            return Math.max(1, Math.min(reachable, MAX_CONNECTION_CYCLES));
        }
        return FALLBACK_CYCLES;
    }

    //debounce Layer1 基于StunProbe结果映射本地NAT分类
    private NatClass classifyLocalNat() {
        if (stunProbeResult == null || stunProbeResult.natType == null) return NatClass.UNKNOWN;
        return NatClass.fromStunProbeResult(stunProbeResult.natType);
    }

    //debounce Layer1 基于host对称标志+easySym标志映射远端NAT分类
    private NatClass classifyRemoteNat(RoomManager.RoomState state) {
        if (state == null || state.roomInfo == null) return NatClass.UNKNOWN;
        boolean hostSym = state.roomInfo.isHostSymmetric();
        boolean hostEasySym = state.roomInfo.isHostEasySym();
        if (hostSym) {
            return hostEasySym ? NatClass.EASY_SYM : NatClass.HARD_SYM;
        }
        //debounce 非sym且NAT类型已确认 判定为CONE 未确认时UNKNOWN
        String nt = state.roomInfo.getNatType();
        if (nt == null || nt.isEmpty() || "unknown".equals(nt)) return NatClass.UNKNOWN;
        return NatClass.CONE;
    }

    //debounce Layer2 HardSym×HardSym场景预查relay候选
    private boolean shouldPrefetchRelay(NatClass local, NatClass remote) {
        return local.isSymmetric() || remote.isSymmetric();
    }

    //debounce Layer2 异步预查relay候选 触发早期topology poll让tryRelay有候选数据 失败时零等待切relay
    private CompletableFuture<Void> prefetchRelayCandidates(RoomManager.RoomState state) {
        if (state == null || state.roomInfo == null) return CompletableFuture.completedFuture(null);
        final String code = state.roomInfo.getCode();
        final String token = state.roomInfo.getToken();
        if (code == null || token == null) return CompletableFuture.completedFuture(null);
        return signalingClient.pollTopology(code, token, false, 0)
                .thenAccept(resp -> VoxLinkMod.LOGGER.info("[Connection] Layer2 relay pre-check topology done"))
                .exceptionally(e -> { VoxLinkMod.LOGGER.warn("[Connection] Layer2 relay pre-check topology failed: {}", e.getMessage()); return null; });
    }

    //debounce 老版本检测 host不支持relay视为legacy 与1.0.7一致走DIRECT_ONLY
    private boolean isLegacyPeer() {
        RoomManager.RoomState state = roomManager.currentRoom.get();
        if (state == null || state.roomInfo == null) return false;
        return !state.roomInfo.hostSupportsRelay();
    }

    public void runConnectionCycle(RoomManager.RoomState state, String from, String hostIpv6, String hostIp, int hostPort, String hostMappedIp, int hostMappedPort, int cycle) {
        //debounce 阶段三: 保存参数供ICE Restart重新触发连接
        this.savedConnectionState = state;
        this.savedConnectionFrom = from != null ? from : "";
        this.savedConnectionHostIpv6 = hostIpv6;
        this.savedConnectionHostIp = hostIp;
        this.savedConnectionHostPort = hostPort;
        this.savedConnectionHostMappedIp = hostMappedIp;
        this.savedConnectionHostMappedPort = hostMappedPort;
        if (connectionWon.get()) {
            VoxLinkMod.LOGGER.info("[Connection] Connected, skip cycle {}", cycle + 1);
            return;
        }
        int maxCycles = getEffectiveMaxCycles();
        if (cycle >= maxCycles) {
            if (connectionWon.get()) return;
            //debounce 阶段四: 双方支持持续重试且玩家未取消 → 重置cycle=0新一轮 持续重试至玩家取消
            if (shouldContinuousRetry(state)) {
                int round = continuousRetryRound.incrementAndGet();
                //debounce 每轮升档 DEFAULT→AGGRESSIVE→HARDSYM 到顶后保持 增加极端NAT命中率
                escalateProfileForRound(round);
                VoxLinkMod.LOGGER.info("[Connection] Cycle {} done, both support persistent retry (round={}, level={}), reset cycle from 0",
                        maxCycles, round, PunchProfile.describe());
                ConnectionState.transitionTo(ConnectionState.STUN_PROBE, "持续重试 round " + round);
                //debounce 持续重试不设全局超时 仅玩家取消或对端cancel才停 避免60s打断无限重试
                connectionStartTimeMs = System.currentTimeMillis();
                if (connectionTimeoutFuture != null) {
                    connectionTimeoutFuture.cancel(false);
                    connectionTimeoutFuture = null;
                }
                state.roomInfo.setConnectionMode(Component.translatable("voxlink.connection.retry_round", round));
                tryConnectionStep(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, 0, 1, maxCycles, 0);
                return;
            }
            ConnectionState.transitionTo(ConnectionState.FAILED, "超过最大周期" + maxCycles);
            showConnectFailed(state);
            return;
        }

        connectionWon.set(false);
        ConnectionState.transitionTo(ConnectionState.STUN_PROBE, "周期" + (cycle + 1) + "/" + maxCycles);

        if (cycle == 0) {
            //debounce Layer1 NAT分类 cycle0首次分类后续复用
            localNatClass = classifyLocalNat();
            remoteNatClass = classifyRemoteNat(state);
            PunchProfile recommended = NatClass.recommendProfile(localNatClass, remoteNatClass);
            PunchProfile.switchTo(recommended, "nat_matrix_" + localNatClass + "x" + remoteNatClass);
            VoxLinkMod.LOGGER.info("[Connection] Layer1 NAT classification: local={} remote={} -> profile={}",
                    localNatClass, remoteNatClass, PunchProfile.describe());
            //debounce Layer2 cycle0异步预查relay候选 HardSym×HardSym场景 失败时零等待切relay
            if (shouldPrefetchRelay(localNatClass, remoteNatClass) && relayPrefetchFuture == null) {
                relayPrefetchFuture = prefetchRelayCandidates(state)
                        .exceptionally(e -> { VoxLinkMod.LOGGER.warn("[Connection] Layer2 relay pre-check failed: {}", e.getMessage()); return null; });
            }
            connectionStartTimeMs = System.currentTimeMillis();
            int timeoutSec = CONNECTION_TIMEOUT_SECONDS;
            boolean joinerSym = stunProbeResult != null && stunProbeResult.natType.isSymmetric();
            boolean hostSym = state.roomInfo.isHostSymmetric();
            if (joinerSym || hostSym) {
                timeoutSec = SYMMETRIC_CONNECTION_TIMEOUT_SECONDS;
                VoxLinkMod.LOGGER.info("[Connection] One side symmetric NAT (joinerSym={}, hostSym={}), global timeout extended to {}s", joinerSym, hostSym, timeoutSec);
            }
            connectionTimeoutSec = timeoutSec;
            //debounce 持续重试中不设全局超时 避免打断无限重试 首次连接仍保留60s/90s超时
            if (continuousRetryRound.get() == 0) {
                scheduleConnectionTimeout(state, timeoutSec);
            }
        }

        int displayCycle = cycle + 1;
        //debounce 区分阶段提示: cycle0首次STUN探测显示探测中 后续显示打洞中
        if (cycle == 0 && stunProbeResult == null) {
            state.roomInfo.setConnectionMode(Component.translatable("voxlink.connection.probing"));
            final RoomManager.RoomState probingState = state;
            scheduler.schedule(() -> {
                if (roomManager.currentRoom.get() == probingState && !connectionWon.get()) {
                    Component current = probingState.roomInfo.getConnectionMode();
                    if (current != null && current.getString()
                            .equals(Component.translatable("voxlink.connection.probing").getString())) {
                        probingState.roomInfo.setConnectionMode(
                            Component.translatable("voxlink.connection.punching"));
                    }
                }
            }, 15, TimeUnit.SECONDS);
        } else {
            state.roomInfo.setConnectionMode(Component.translatable("voxlink.connection.punching"));
        }

        // RTT同步: 双方等到约定的同步时刻再发包, 确保NAT映射在两侧同时建立
        long syncTime = state.roomInfo.getPunchSyncTimeMs();
        if (cycle == 0 && syncTime > 0) {
            long delay = syncTime - System.currentTimeMillis();
            if (delay > 0 && delay < MAX_DELAY_MS) {
                VoxLinkMod.LOGGER.info("[Connection] RTT sync wait: send packets after {}ms", delay);
                scheduler.schedule(() -> {
                    if (connectionCycleActive.get() && roomManager.currentRoom.get() == state) {
                        tryConnectionStep(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles, 0);
                    }
                }, delay, TimeUnit.MILLISECONDS);
                return;
            }
        }
        tryConnectionStep(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles, 0);
    }

    //设置全局超时
    private void scheduleConnectionTimeout(RoomManager.RoomState state, int timeoutSec) {
        if (connectionTimeoutFuture != null) connectionTimeoutFuture.cancel(false);
        final int finalTimeoutSec = timeoutSec;
        connectionTimeoutFuture = scheduler.schedule(() -> {
            if (connectionWon.get()) return;
            if (connectionCycleActive.get() && roomManager.currentRoom.get() == state) {
                VoxLinkMod.LOGGER.warn("[Connection] Global timeout ({}s), enter failure handling (persistent retry/relay/final)", finalTimeoutSec);
                //debounce 超时走showConnectFailed而非Final 让continuous retry和relay有机会接管
                showConnectFailed(state);
            }
        }, finalTimeoutSec, TimeUnit.SECONDS);
    }

    //NAT探测完成后, 若发现对称NAT, 延长超时到90s
    private void extendConnectionTimeoutIfNeeded(RoomManager.RoomState state) {
        if (connectionStartTimeMs == 0 || stunProbeResult == null) return;
        boolean localSym = stunProbeResult.natType.isSymmetric();
        boolean hostSym = state.roomInfo.isHostSymmetric();
        if (!localSym && !hostSym) return;
        if (connectionTimeoutSec >= SYMMETRIC_CONNECTION_TIMEOUT_SECONDS) return;
        long elapsedMs = System.currentTimeMillis() - connectionStartTimeMs;
        long remainingMs = SYMMETRIC_CONNECTION_TIMEOUT_SECONDS * 1000L - elapsedMs;
        if (remainingMs <= 0) return;
        connectionTimeoutSec = SYMMETRIC_CONNECTION_TIMEOUT_SECONDS;
        VoxLinkMod.LOGGER.info("[Connection] NAT probe found symmetric NAT (local or remote), global timeout extended to {}s ({}ms left)", SYMMETRIC_CONNECTION_TIMEOUT_SECONDS, remainingMs);
        scheduleConnectionTimeout(state, (int)(remainingMs / 1000) + 1);
    }

    public void tryConnectionStep(RoomManager.RoomState state, String from, String hostIpv6, String hostIp, int hostPort, String hostMappedIp, int hostMappedPort, int cycle, int displayCycle, int maxCycles, int step) {
        if (roomManager.currentRoom.get() != state) return;
        if (connectionWon.get()) {
            VoxLinkMod.LOGGER.info("[Connection] Connected, skip Wave step (cycle={}, step={})", cycle + 1, step);
            return;
        }

        switch (step) {
            case 0: {
                VoxLinkMod.LOGGER.info("[Connection] Wave 1: LAN+IPv6+UDP parallel (cycle {}/{})", displayCycle, maxCycles);
                state.roomInfo.setConnectionMode(Component.translatable("voxlink.connection.punching"));

                AtomicBoolean wave1Settled = new AtomicBoolean(false);
                java.util.List<CompletableFuture<?>> wave1Futures = new java.util.ArrayList<>();

                String hostLocalIp = state.roomInfo.getHostLocalIp();
                boolean sameLan = hostLocalIp != null && !hostLocalIp.isEmpty() && StunDetector.isSameLan(hostLocalIp);
                boolean sameCgnat = state.roomInfo.isSameCgnat();
                if (hostLocalIp != null && !hostLocalIp.isEmpty() && (sameLan || sameCgnat)) {
                    String reason = sameLan ? "LAN" : "CGNAT同公网IP";
                    VoxLinkMod.LOGGER.info("[Connection] Wave 1: Detected {} (localIp={}), try direct connect", reason, hostLocalIp);
                    ConnectionFallback lanFallback = trackFallback(new ConnectionFallback());
                    wave1Futures.add(lanFallback.tryIpv4Direct(hostLocalIp, hostPort).thenAccept(result -> {
                        if (roomManager.currentRoom.get() == state && result.success && connectionWon.compareAndSet(false, true)) {
                            VoxLinkMod.LOGGER.info("[Connection] Wave 1: {} direct connect won", reason);
                            wave1Settled.set(true);
                            connectViaBridge(state, result);
                        } else if (roomManager.currentRoom.get() == state && result != null && !result.success) {
                            //黑名单: 直连失败
                            addressBlacklist.recordDirectFailure(new InetSocketAddress(hostLocalIp, hostPort));
                        }
                    }));
                    if (sameCgnat && !sameLan) {
                        int mcPort = state.roomInfo.getHostPort();
                        VoxLinkMod.LOGGER.info("[Connection] Wave 1: CGNAT also try localIp MC port {}:{}", hostLocalIp, mcPort);
                        ConnectionFallback mcFallback = trackFallback(new ConnectionFallback());
                        wave1Futures.add(mcFallback.tryIpv4Direct(hostLocalIp, mcPort).thenAccept(result -> {
                            if (roomManager.currentRoom.get() == state && result.success && connectionWon.compareAndSet(false, true)) {
                                VoxLinkMod.LOGGER.info("[Connection] Wave 1: CGNAT localIp MC port won");
                                wave1Settled.set(true);
                                connectViaBridge(state, result);
                            }
                        }));
                    }
                }

                if (hostIpv6 != null && !hostIpv6.isEmpty() && StunDetector.verifyIPv6Connectivity()) {
                    VoxLinkMod.LOGGER.info("[Connection] Wave 1: Parallel try IPv6 direct connect");
                    ConnectionFallback ipv6Fallback = trackFallback(new ConnectionFallback());
                    wave1Futures.add(ipv6Fallback.tryIpv6Direct(hostIpv6, hostPort).thenAccept(result -> {
                        if (roomManager.currentRoom.get() == state && result.success && connectionWon.compareAndSet(false, true)) {
                            VoxLinkMod.LOGGER.info("[Connection] Wave 1: IPv6 direct connect won");
                            wave1Settled.set(true);
                            connectViaBridge(state, result);
                        }
                    }));
                } else if (hostIpv6 != null && !hostIpv6.isEmpty()) {
                    VoxLinkMod.LOGGER.info("[Connection] Wave 1: Skip IPv6 (no local IPv6 connectivity)");
                }

                //debounce Layer4 策略调度 按NAT组合+cycle+老版本标志选4种策略
                PunchStrategy strategy = PunchStrategySelector.select(localNatClass, remoteNatClass, cycle, isLegacyPeer());
                VoxLinkMod.LOGGER.info("[Connection] Layer4 smart schedule cycle={} strategy={} localNat={} remoteNat={}",
                        cycle, strategy, localNatClass, remoteNatClass);
                switch (strategy) {
                    case DIRECT_ONLY:
                        //debounce 与1.0.7一致 纯正向
                        VoxLinkMod.LOGGER.info("[Connection] Wave 1: Try UDP punch (DIRECT_ONLY)");
                        tryUdpPunch(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles);
                        break;
                    case DIRECT_WITH_REVERSE_PARALLEL:
                        //debounce cycle0正向 cycle1+并行逆向
                        VoxLinkMod.LOGGER.info("[Connection] Wave 1: Try UDP punch (DIRECT_WITH_REVERSE_PARALLEL cycle={})", cycle);
                        tryUdpPunch(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles);
                        if (cycle >= 1 && reversePunchAttempted.compareAndSet(false, true)) {
                            VoxLinkMod.LOGGER.info("[Connection] Wave 1: cycle{} start reverse punch (parallel)", cycle);
                            startReversePunch(state);
                        }
                        break;
                    case REVERSE_FIRST:
                        //debounce 首轮先逆向再正向
                        if (cycle == 0 && reversePunchAttempted.compareAndSet(false, true)) {
                            VoxLinkMod.LOGGER.info("[Connection] Wave 1: REVERSE_FIRST start reverse punch first");
                            startReversePunch(state);
                        }
                        VoxLinkMod.LOGGER.info("[Connection] Wave 1: Try UDP punch (REVERSE_FIRST cycle={})", cycle);
                        tryUdpPunch(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles);
                        break;
                    case RELAY_FALLBACK_FAST:
                        //debounce cycle>=2立即切relay 不等所有cycle用完
                        if (cycle < 2) {
                            VoxLinkMod.LOGGER.info("[Connection] Wave 1: Try UDP punch (RELAY_FALLBACK_FAST cycle={})", cycle);
                            tryUdpPunch(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles);
                            if (reversePunchAttempted.compareAndSet(false, true)) {
                                VoxLinkMod.LOGGER.info("[Connection] Wave 1: RELAY_FALLBACK_FAST cycle{} start reverse punch", cycle);
                                startReversePunch(state);
                            }
                        } else {
                            VoxLinkMod.LOGGER.info("[Connection] Layer4 RELAY_FALLBACK_FAST cycle{}>=2 switch to relay immediately", cycle);
                            tryRelay(state);
                            return;
                        }
                        break;
                }

                if (!wave1Futures.isEmpty()) {
                    CompletableFuture.allOf(wave1Futures.toArray(new CompletableFuture[0])).thenAccept(v -> {
                        if (roomManager.currentRoom.get() != state) return;
                        if (!wave1Settled.get()) {
                            VoxLinkMod.LOGGER.info("[Connection] Wave 1 TCP all failed, enter Wave 2");
                            tryConnectionStep(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles, 1);
                        }
                    });
                } else {
                    scheduler.schedule(() -> {
                        if (roomManager.currentRoom.get() == state && connectionCycleActive.get()) {
                            tryConnectionStep(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles, 1);
                        }
                    }, EXTRA_TIMEOUT_SEC, TimeUnit.SECONDS);
                }
                return;
            }

            case 1: {
                VoxLinkMod.LOGGER.info("[Connection] Wave 2: TCP fallback parallel (cycle{}/{})", displayCycle, maxCycles);
                ConnectionState.transitionTo(ConnectionState.TCP_FALLBACK, "Wave 2 TCP兜底");
                state.roomInfo.setConnectionMode(Component.translatable("voxlink.connection.punching"));

                AtomicBoolean wave2Settled = new AtomicBoolean(false);
                java.util.List<CompletableFuture<ConnectionFallback.ConnectResult>> wave2Futures = new java.util.ArrayList<>();

                if (hostMappedIp != null && !hostMappedIp.isEmpty() && hostMappedPort > 0) {
                    ConnectionFallback tcpSimFallback = trackFallback(new ConnectionFallback());
                    int simLocalPort = P2PBridge.getHostPort() > 0 ? P2PBridge.getHostPort() : hostPort;
                    String myMappedIp = state.roomInfo.getMyMappedIp();
                    int myMappedPort = state.roomInfo.getMyMappedPort();
                    if (myMappedIp != null && myMappedPort > 0 && signalingClient != null) {
                        JsonObject simReq = new JsonObject();
                        simReq.addProperty("joinerMappedIp", myMappedIp);
                        simReq.addProperty("joinerMappedPort", myMappedPort);
                        signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), false, "tcp_simopen_request", simReq, "host");
                        VoxLinkMod.LOGGER.info("[Connection] Wave 2: Send tcp_simopen_request to host ({}:{})", myMappedIp, myMappedPort);
                    }
                    // TCP连接bridge端口，不是NAT映射端口
                    int tcpTargetPort = hostPort > 0 ? hostPort : hostMappedPort;
                    wave2Futures.add(tcpSimFallback.tryTcpSimultaneousOpen(hostMappedIp, tcpTargetPort, simLocalPort));
                }

                if (hostMappedIp != null && !hostMappedIp.isEmpty() && hostMappedPort > 0) {
                    ConnectionFallback tcpMappedFallback = trackFallback(new ConnectionFallback());
                    // TCP直连也用bridge端口
                    int tcpDirectPort = hostPort > 0 ? hostPort : hostMappedPort;
                    wave2Futures.add(tcpMappedFallback.tryIpv4Direct(hostMappedIp, tcpDirectPort));
                }

                if (hostIp != null && !hostIp.isEmpty()) {
                    ConnectionFallback ipv4Fallback = trackFallback(new ConnectionFallback());
                    final String fDirectIp = hostIp;
                    final int fDirectPort = hostPort;
                    wave2Futures.add(ipv4Fallback.tryIpv4Direct(hostIp, hostPort).whenComplete((result, ex) -> {
                        if (ex == null && result != null && !result.success && roomManager.currentRoom.get() == state) {
                            //黑名单: 直连失败
                            addressBlacklist.recordDirectFailure(new InetSocketAddress(fDirectIp, fDirectPort));
                        }
                    }));
                }

                // CGNAT同公网IP场景：额外尝试hostLocalIp（公网IP直连会因hairpin NAT失败）
                if (state.roomInfo.isSameCgnat()) {
                    String hostLocalIp2 = state.roomInfo.getHostLocalIp();
                    if (hostLocalIp2 != null && !hostLocalIp2.isEmpty()) {
                        VoxLinkMod.LOGGER.info("[Connection] Wave 2: CGNAT scenario try hostLocalIp {}:{}", hostLocalIp2, hostPort);
                        ConnectionFallback localFallback = trackFallback(new ConnectionFallback());
                        wave2Futures.add(localFallback.tryIpv4Direct(hostLocalIp2, hostPort));
                        int mcPort = state.roomInfo.getHostPort();
                        VoxLinkMod.LOGGER.info("[Connection] Wave 2: CGNAT scenario try localIp MC port {}:{}", hostLocalIp2, mcPort);
                        ConnectionFallback mcLocalFallback = trackFallback(new ConnectionFallback());
                        wave2Futures.add(mcLocalFallback.tryIpv4Direct(hostLocalIp2, mcPort));
                    }
                }

                // Wave 2也尝试IPv6（如果Wave 1没成功的话）
                if (hostIpv6 != null && !hostIpv6.isEmpty() && StunDetector.verifyIPv6Connectivity()) {
                    VoxLinkMod.LOGGER.info("[Connection] Wave 2: Try IPv6 direct connection");
                    ConnectionFallback ipv6Fallback2 = trackFallback(new ConnectionFallback());
                    wave2Futures.add(ipv6Fallback2.tryIpv6Direct(hostIpv6, hostPort));
                }

                if (wave2Futures.isEmpty()) {
                    VoxLinkMod.LOGGER.info("[Connection] Wave 2: No TCP fallback available, enter next cycle");
                    advanceToNextCycle(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, maxCycles);
                    return;
                }

                for (CompletableFuture<ConnectionFallback.ConnectResult> future : wave2Futures) {
                    future.thenAccept(result -> {
                        if (roomManager.currentRoom.get() == state && result.success && connectionWon.compareAndSet(false, true)) {
                            VoxLinkMod.LOGGER.info("[Connection] Wave 2: {} won", result.errorCode);
                            connectViaBridge(state, result);
                        }
                    });
                }

                CompletableFuture.allOf(wave2Futures.toArray(new CompletableFuture[0])).thenAccept(v -> {
                    if (roomManager.currentRoom.get() != state) return;
                    if (!connectionWon.get()) {
                        VoxLinkMod.LOGGER.info("[Connection] Wave 2: All TCP fallbacks failed, enter next cycle");
                        advanceToNextCycle(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, maxCycles);
                    }
                });
                return;
            }

            default:
                advanceToNextCycle(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, maxCycles);
        }
    }

    public void tryUdpPunch(RoomManager.RoomState state, String from, String hostIpv6, String hostIp, int hostPort, String hostMappedIp, int hostMappedPort, int cycle, int displayCycle, int maxCycles) {
        tryUdpPunch(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles, 1);
    }

    public void tryUdpPunch(RoomManager.RoomState state, String from, String hostIpv6, String hostIp, int hostPort, String hostMappedIp, int hostMappedPort, int cycle, int displayCycle, int maxCycles, int attempt) {
        if (connectionWon.get()) {
            VoxLinkMod.LOGGER.info("[Connection] Already connected, skip UDP punch (cycle={}, attempt={})", cycle + 1, attempt);
            return;
        }
        state.roomInfo.setConnectionMode(Component.translatable("voxlink.connection.connecting"));
        VoxLinkMod.LOGGER.info("[Connection] UDP punch cycle{}/{}, attempt{}/{}", displayCycle, maxCycles, attempt, UDP_PUNCH_MAX_ATTEMPTS);

        //优化: Sym×Sym不直接放弃, 先尝试Birthday Attack+端口预测, 失败再走Relay/TCP兜底
        //用户原话: "为什么会直接放弃呢？应该是先尝试直接进行打洞连接，然后再尝试使用另一个非对称NAT玩家进行中继"
        boolean joinerSym = stunProbeResult != null && stunProbeResult.natType.isSymmetric();
        boolean joinerHardSym = stunProbeResult != null && stunProbeResult.natType.isHardSymmetric();
        boolean hostSym = state.roomInfo.isHostSymmetric();
        boolean hostHardSym = hostSym && !state.roomInfo.isHostEasySym();
        if (joinerSym && hostSym) {
            if (joinerHardSym || hostHardSym) {
                VoxLinkMod.LOGGER.info("[Connection] Both symmetric NAT with HardSym (joinerHard={}, hostHard={}), try Birthday Attack+port prediction first, fallback to Relay on failure",
                        joinerHardSym, hostHardSym);
            } else {
                VoxLinkMod.LOGGER.info("[Connection] Both EasySym (port predictable), continue UDP punch (EasyTier both_easy_sym)");
            }
        }

        // 停洞复用socket
        // 复用socket防端口变。不再stop旧punch——让它在后台自然超时。
        UdpHolePuncher prev = activeHolePunchers.get("joiner");
        UdpHolePuncher puncher = activeHolePunchers.get("joiner_reuse");
        if (puncher != null && puncher.getSocket() != null && !puncher.getSocket().isClosed()) {
            activeHolePunchers.put("joiner", puncher);
            VoxLinkMod.LOGGER.info("[Connection] Reuse punch socket (port={})", puncher.getSocket().getLocalPort());
        } else {
            if (prev != null) try { prev.close(); } catch (Exception ignored) {}
            activeHolePunchers.remove("joiner_reuse");
            puncher = new UdpHolePuncher();
            try {
                puncher.createSocket();
            } catch (Exception e) {
                VoxLinkMod.LOGGER.error("[Connection] Failed to create UDP punch socket: {}", e.getMessage());
                tryConnectionStep(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles, 1);
                return;
            }
            //UPnP主动映射, 对称NAT转FullCone, 降级不阻塞
            try {
                int upnpLocal = puncher.getSocket().getLocalPort();
                if (icu.wuhui.voxlink.network.UPnPManager.addPortMapping(upnpLocal)) {
                    VoxLinkMod.LOGGER.info("UPnP mapping success {} -> {}", upnpLocal, upnpLocal);
                }
            } catch (Exception e) {
                VoxLinkMod.LOGGER.warn("UPnP mapping failed, fallback to original punch flow: {}", e.getMessage());
            }
            activeHolePunchers.put("joiner", puncher);
            activeHolePunchers.put("joiner_reuse", puncher);
        }

        // 必须在打洞socket STUN
        StunProbe.PublicMappedAddress myMappedAddr = null;
        boolean joinerPunchSocketSymmetric = false;
        int joinerMappedPortDelta = 0;

        //并行双STUN: 省一半时间
        // 修复2: 4个STUN并发, 取前2个成功响应比对, 提高对称NAT检测冗余
        // 旧逻辑仅2个STUN, 任一不可达即降级单测无法判定对称; 新逻辑4个并发容错更强
        java.util.List<String> quadStun = StunDetector.getAllStunUrls();
        StunProbe.PublicMappedAddress[] quadResult = StunProbe.discoverMappedAddressQuad(
                puncher.getSocket(), quadStun.get(0), quadStun.get(1), quadStun.get(2), quadStun.get(3));
        StunProbe.PublicMappedAddress myMapped1 = quadResult[0] != null ? quadResult[0] : (quadResult[2] != null ? quadResult[2] : quadResult[3]);
        StunProbe.PublicMappedAddress myMapped2 = quadResult[1] != null ? quadResult[1] : (quadResult[3] != null ? quadResult[3] : quadResult[2]);
        if (myMapped1 != null && myMapped2 != null) {
            if (myMapped1.port() != myMapped2.port()) {
                joinerPunchSocketSymmetric = true;
                joinerMappedPortDelta = myMapped2.port() - myMapped1.port();
                VoxLinkMod.LOGGER.info("[Connection] Joiner punch socket STUN: symmetric NAT ({} vs {}, delta={})", myMapped1.port(), myMapped2.port(), joinerMappedPortDelta);
            }
            myMappedAddr = myMapped2;
        } else {
            myMappedAddr = myMapped1 != null ? myMapped1 : myMapped2;
        }

        if (myMappedAddr == null) {
            myMappedAddr = puncher.discoverMappedAddress(StunDetector.getAllStunUrls());
        }

        if (myMappedAddr == null) {
            VoxLinkMod.LOGGER.warn("[Connection] Punch socket STUN failed, try temp socket fallback (attempt{})", attempt);
            DatagramSocket tmp = null;
            try {
                tmp = new DatagramSocket();
                tmp.setSoTimeout(PROBE_SOCKET_TIMEOUT_MS);
                myMappedAddr = StunProbe.discoverMappedAddress(tmp, StunDetector.getAllStunUrls());
            } catch (Exception e) {
                VoxLinkMod.LOGGER.warn("[Connection] Temp socket STUN also failed: {}", e.getMessage());
            } finally {
                if (tmp != null && !tmp.isClosed()) { tmp.close(); }
            }
        }
        if (myMappedAddr != null) {
            VoxLinkMod.LOGGER.info("[Connection] My mapped address: {}:{} (attempt{})", myMappedAddr.ip(), myMappedAddr.port(), attempt);
            // 注册到全局relay候选池
            if (state.roomInfo.getClientId() != null && attempt == 1) {
                String myNatType = stunProbeResult != null ? stunProbeResult.natType.key : "unknown";
                boolean relayOk = VoxLinkMod.getConfig().isRelayEnabled();
                signalingClient.registerRelayPeer(state.roomInfo.getClientId(), state.roomInfo.getCode(),
                        myNatType, myMappedAddr.ip(), myMappedAddr.port(), relayOk);
            }
            JsonObject punchData = new JsonObject();
            punchData.addProperty("joinerMappedIp", myMappedAddr.ip());
            punchData.addProperty("joinerMappedPort", myMappedAddr.port());
            // 通知host做端口预测
boolean joinerSymmetric = (stunProbeResult != null && stunProbeResult.natType.isSymmetric()) || joinerPunchSocketSymmetric;
            if (joinerSymmetric) {
                punchData.addProperty("joinerSymmetric", true);
            }
            // EasySym标志：让host知道joiner端口可预测，EasySym×EasySym可打洞
            boolean joinerEasySym = (stunProbeResult != null && stunProbeResult.natType.isEasySymmetric()) || joinerPunchSocketSymmetric;
            if (joinerEasySym) {
                punchData.addProperty("joinerEasySym", true);
            }
            if (joinerMappedPortDelta != 0) {
                punchData.addProperty("joinerMappedPortDelta", joinerMappedPortDelta);
                VoxLinkMod.LOGGER.info("[Connection] punch_info with port offset: delta={}", joinerMappedPortDelta);
            }
            if (joinerPunchSocketSymmetric) {
                VoxLinkMod.LOGGER.info("[Connection] Override joinerSymmetric=true with punch socket dual STUN result");
            }
            String joinerLocalIp = StunDetector.getLocalIpAddress();
            if (joinerLocalIp != null && !joinerLocalIp.isEmpty()) {
                punchData.addProperty("joinerLocalIp", joinerLocalIp);
                VoxLinkMod.LOGGER.info("[Connection] punch_info includes joiner LAN IP: {}", joinerLocalIp);
            }
            if (state.roomInfo.isSameCgnat() && (state.roomInfo.getHostLocalIp() == null || state.roomInfo.getHostLocalIp().isEmpty())) {
                punchData.addProperty("requestHostLocalIp", true);
                VoxLinkMod.LOGGER.info("[Connection] CGNAT scenario request host to send LAN IP");
            }
            signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(),
                    false, "punch_info", punchData, "host");
        } else {
            VoxLinkMod.LOGGER.warn("[Connection] STUN binding failed, no mapped address (attempt{})", attempt);
            puncher.close();
            if (attempt < UDP_PUNCH_MAX_ATTEMPTS) {
                long delay = UDP_PUNCH_RETRY_DELAY_MS * (1L << Math.min(attempt - 1, 4));
                VoxLinkMod.LOGGER.info("[Connection] Retry UDP punch after {}ms (attempt {}/{})", delay, attempt + 1, UDP_PUNCH_MAX_ATTEMPTS);
                scheduler.schedule(() -> {
                    if (roomManager.currentRoom.get() == state && connectionCycleActive.get()) {
                        tryUdpPunch(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles, attempt + 1);
                    }
                }, delay, TimeUnit.MILLISECONDS);
            } else {
                tryConnectionStep(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles, 1);
            }
            return;
        }

        //debounce STUN探测期间连接可能已成功 二次检查避免竞态启动新puncher
        if (connectionWon.get()) {
            VoxLinkMod.LOGGER.info("[Connection] Connection succeeded during STUN probe, abort attempt{}", attempt);
            puncher.close();
            return;
        }

        activeHolePunchers.put("joiner", puncher);

        puncher.setOnPeerPunchReceived(addr -> {
            String code = state.roomInfo.getCode();
            String token = state.roomInfo.getToken();
            JsonObject portData = new JsonObject();
            portData.addProperty("peer_ip", addr.getAddress().getHostAddress());
            portData.addProperty("peer_port", addr.getPort());
            signalingClient.sendSignal(code, token, false, "peer_port", portData, "host")
                    .exceptionally(e -> { VoxLinkMod.LOGGER.debug("peer_port signal send failed: {}", e.getMessage()); return null; });
        });

        String effectiveMappedIp = state.roomInfo.getHostMappedIp();
        int effectiveMappedPort = state.roomInfo.getHostMappedPort();
        if (effectiveMappedIp == null || effectiveMappedPort <= 0) {
            effectiveMappedIp = hostMappedIp;
            effectiveMappedPort = hostMappedPort;
        }

        String targetIp = effectiveMappedIp != null ? effectiveMappedIp : hostIp;
        int targetPort = effectiveMappedPort > 0 ? effectiveMappedPort : hostPort;

        // EasyTier DST_PORT_OFFSET方案：symmetric NAT方向性端口预测
int hostMappedPortDelta = state.roomInfo.getHostMappedPortDelta();
        if (state.roomInfo.isHostSymmetric() && hostMappedPortDelta != 0 && targetPort > 0) {
            int predictedPort = targetPort + hostMappedPortDelta;
            if (predictedPort > 0 && predictedPort <= 65535) {
                VoxLinkMod.LOGGER.info("[Connection] EasySym port prediction: STUN port={} + delta={} -> predicted port={}", targetPort, hostMappedPortDelta, predictedPort);
                targetPort = predictedPort;
            }
        }

        final String fTargetIp = targetIp;
        final int fTargetPort = targetPort;

        VoxLinkMod.LOGGER.info("[Connection] UDP punch target: {}:{} (hostMappedIp={}, hostMappedPort={}, delta={}, hostIp={}, hostPort={}, attempt{})",
                fTargetIp, fTargetPort, hostMappedIp, hostMappedPort, hostMappedPortDelta, hostIp, hostPort, attempt);
        ConnectionState.transitionTo(ConnectionState.UDP_PUNCH, "尝试" + attempt + "/" + UDP_PUNCH_MAX_ATTEMPTS + " 目标" + fTargetIp + ":" + fTargetPort);

        if (targetIp == null || targetIp.isEmpty()) {
            VoxLinkMod.LOGGER.warn("[Connection] No target IP, UDP punch cannot proceed");
            puncher.close();
            activeHolePunchers.remove("joiner");
            tryConnectionStep(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles, 1);
            return;
        }

        final UdpHolePuncher finalPuncher = puncher;
        //黑名单过滤
        final InetSocketAddress punchTargetAddr = new InetSocketAddress(fTargetIp, fTargetPort);
        if (addressBlacklist.isBlacklisted(punchTargetAddr)) {
            VoxLinkMod.LOGGER.info("[Connection] Target {}:{} in blacklist, skip UDP punch", fTargetIp, fTargetPort);
            finalPuncher.close();
            activeHolePunchers.remove("joiner");
            tryConnectionStep(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles, 1);
            return;
        }
        //双EasySym对打
        if (stunProbeResult != null && stunProbeResult.natType.isEasySymmetric() && state.roomInfo.isHostEasySym()) {
            //debounce 每轮socket数递增: round0用25 round1+用50 增加生日攻击命中概率
            int dualSocketCount = continuousRetryRound.get() > 0 ? 50 : 25;
            VoxLinkMod.LOGGER.info("Both EasySym, start mutual punch ({} socket x +/-20)", dualSocketCount);
            final UdpHolePuncher dualPuncher = finalPuncher;
            dualPuncher.punchEasySymDual(fTargetIp, fTargetPort, stunProbeResult.natType, StunProbe.NatType.SYMMETRIC_EASY_INC, dualSocketCount)
                .thenAccept(result -> {
                    if (!result.isSuccess()) {
                        //debounce Layer3 失败分类+下轮参数调节
                        PunchFailureClassifier.FailureReason reason = PunchFailureClassifier.classify(result);
                        lastPunchResult = result.withReason(reason);
                        VoxLinkMod.LOGGER.info("[Connection] cycle={} EasySym mutual punch failed reason={} recvPunch={} recvAck={}",
                                cycle, reason, result.socketsReceivedPunch, result.socketsReceivedAck);
                        PunchTuner.nextParams(PunchProfile.current(), localNatClass, remoteNatClass,
                                cycle + 1, maxCycles, reason, lastPunchResult);
                        //debounce 保留原exceptionally的失败处理逻辑 不退化
                        addressBlacklist.recordUdpFailure(punchTargetAddr);
                        dualPuncher.stopPunch();
                        if (roomManager.currentRoom.get() != state) {
                            connectionCycleActive.set(false);
                            ConnectionHelper.resetConnecting();
                            return;
                        }
                        tryConnectionStep(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles, 1);
                        return;
                    }
                    DatagramSocket socket = result.getSuccessSocket();
                    if (roomManager.currentRoom.get() != state || !connectionWon.compareAndSet(false, true)) {
                        try { dualPuncher.close(); } catch (Exception ignored) {}
                        return;
                    }
                    dualPuncher.markSocketTransferred();
                    stopAllPunchingAfterHostBridge();
                    dualPuncher.stopPunch();
                    InetSocketAddress dualAddr = dualPuncher.getActualRemoteAddress();
                    InetSocketAddress fallbackAddr = dualAddr != null ? dualAddr : punchTargetAddr;
                    scheduler.submit(() -> {
                        try {
                            establishUdpTransport(state, socket, dualPuncher, fallbackAddr, "joiner", false, null);
                        } catch (Exception e) {
                            VoxLinkMod.LOGGER.error("[Connection] EasySym mutual punch transport failed: {}", e.getMessage());
                            dualPuncher.close();
                            showConnectFailed(state);
                        }
                    });
                })
                .exceptionally(e -> {
                    VoxLinkMod.LOGGER.warn("[Connection] EasySym mutual punch failed: {}", e.getMessage());
                    addressBlacklist.recordUdpFailure(punchTargetAddr);
                    dualPuncher.stopPunch();
                    if (roomManager.currentRoom.get() != state) {
                        connectionCycleActive.set(false);
                        ConnectionHelper.resetConnecting();
                        return null;
                    }
                    tryConnectionStep(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles, 1);
                    return null;
                });
            return;
        }
        // 端口预测
        boolean joinerIsSymmetric = (stunProbeResult != null && stunProbeResult.natType.isSymmetric()) || joinerPunchSocketSymmetric;
        // open也可能有NAT
        boolean hostConfirmedNonSymmetric = !state.roomInfo.isHostSymmetric() && ("moderate".equals(state.roomInfo.getNatType()) || "port_restricted_cone".equals(state.roomInfo.getNatType()) || "restricted_cone".equals(state.roomInfo.getNatType()) || "full_cone".equals(state.roomInfo.getNatType()));

        int portRange = PORT_RANGE_DEFAULT;
        int hostMappedPortRange = state.roomInfo.getHostMappedPortRange();
        if (joinerIsSymmetric && hostConfirmedNonSymmetric) {
            portRange = 0;
        } else if (state.roomInfo.isHostSymmetric()) {
            if (hostMappedPortDelta != 0) {
                portRange = hostMappedPortRange > 0 ? hostMappedPortRange : PORT_RANGE_WIDE;
            } else if (cycle == 0) {
                portRange = PORT_RANGE_MAX;
            } else {
                portRange = PunchProfile.current().portPredictionMaxRange;
            }
        } else {
            if (cycle == 0) {
                portRange = PORT_RANGE_DEFAULT;
            } else if (cycle == 1) {
                portRange = PORT_RANGE_WIDE;
            } else {
                portRange = PORT_RANGE_MAX;
            }
        }
        VoxLinkMod.LOGGER.info("[Connection] Punch mode: cycle={}, portRange={} (hostSym={}, joinerSym={})", cycle, portRange, state.roomInfo.isHostSymmetric(), joinerIsSymmetric);
        if (portRange > 0) {
            VoxLinkMod.LOGGER.info("[Connection] Port prediction (range=+/-{}) (attempt{}, hostSym={}, joinerSym={}, hostNat={})",
                    portRange, attempt, state.roomInfo.isHostSymmetric(), joinerIsSymmetric, state.roomInfo.getNatType());
        }
        int socketCount = 0;
        if (joinerIsSymmetric) {
            socketCount = state.roomInfo.isHostSymmetric() ? HARD_SYM_SOCKET_COUNT : JOINER_SYM_SOCKET_COUNT;
            VoxLinkMod.LOGGER.info("[Connection] Joiner symmetric NAT, create {} multi-socket punches", socketCount);
        }
        java.util.List<UdpHolePuncher> multiSockets = new java.util.ArrayList<>();
        java.util.concurrent.atomic.AtomicBoolean multiWon = new java.util.concurrent.atomic.AtomicBoolean(false);
        for (int si = 0; si < socketCount; si++) {
            UdpHolePuncher sp = new UdpHolePuncher();
            try { sp.createSocket(); } catch (Exception e) { continue; }
            sp.setOnPeerPunchReceived(addr -> {
                String code = state.roomInfo.getCode();
                String token = state.roomInfo.getToken();
                JsonObject portData = new JsonObject();
                portData.addProperty("peer_ip", addr.getAddress().getHostAddress());
                portData.addProperty("peer_port", addr.getPort());
                signalingClient.sendSignal(code, token, false, "peer_port", portData, "host")
                        .exceptionally(e -> { VoxLinkMod.LOGGER.debug("peer_port signal send failed: {}", e.getMessage()); return null; });
            });
            multiSockets.add(sp);
            activeHolePunchers.put("joiner_ms_" + si, sp);
        }
        if (multiSockets.isEmpty()) {
            VoxLinkMod.LOGGER.info("[Connection] Cone side reuse STUN socket punch (port={})", puncher.getSocket().getLocalPort());
            puncher.setOnPeerPunchReceived(addr -> {
                String code = state.roomInfo.getCode();
                String token = state.roomInfo.getToken();
                JsonObject portData = new JsonObject();
                portData.addProperty("peer_ip", addr.getAddress().getHostAddress());
                portData.addProperty("peer_port", addr.getPort());
                signalingClient.sendSignal(code, token, false, "peer_port", portData, "host")
                        .exceptionally(e -> { VoxLinkMod.LOGGER.debug("peer_port signal send failed: {}", e.getMessage()); return null; });
            });
            puncher.punchWithPortPrediction(fTargetIp, fTargetPort, portRange).thenAccept(result -> {
                if (!result.isSuccess()) {
                    //debounce Layer3 失败分类+下轮参数调节
                    PunchFailureClassifier.FailureReason reason = PunchFailureClassifier.classify(result);
                    lastPunchResult = result.withReason(reason);
                    VoxLinkMod.LOGGER.info("[Connection] cycle={} UDP punch failed (attempt{}/{}) reason={} recvPunch={} recvAck={}",
                            cycle, attempt, UDP_PUNCH_MAX_ATTEMPTS, reason, result.socketsReceivedPunch, result.socketsReceivedAck);
                    PunchTuner.nextParams(PunchProfile.current(), localNatClass, remoteNatClass,
                            cycle + 1, maxCycles, reason, lastPunchResult);
                    //debounce 保留原exceptionally的失败处理逻辑 不退化
                    finalPuncher.stopPunch();
                    if (roomManager.currentRoom.get() != state) {
                        try { finalPuncher.close(); } catch (Exception ignored) {}
                        activeHolePunchers.remove("joiner");
                        connectionCycleActive.set(false);
                        ConnectionHelper.resetConnecting();
                        return;
                    }
                    if (activeHolePunchers.get("joiner") != finalPuncher) {
                        VoxLinkMod.LOGGER.info("[Connection] Puncher replaced, no retry");
                        return;
                    }
                    addressBlacklist.recordUdpFailure(punchTargetAddr);
                    //debounce 防火墙检测: UDP被阻则跳过所有重试 直接进Wave 2 TCP
                    if (result.firewallDetected) {
                        VoxLinkMod.LOGGER.warn("[Connection] Firewall blocked UDP, skip retry enter Wave 2 TCP fallback");
                        try { finalPuncher.close(); } catch (Exception ignored) {}
                        activeHolePunchers.remove("joiner");
                        tryConnectionStep(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles, 1);
                        return;
                    }
                    if (attempt < UDP_PUNCH_MAX_ATTEMPTS) {
                        long delay = UDP_PUNCH_RETRY_DELAY_MS * (1L << Math.min(attempt - 1, 4));
                        VoxLinkMod.LOGGER.info("[Connection] Retry UDP punch after {}ms (attempt {}/{})", delay, attempt + 1, UDP_PUNCH_MAX_ATTEMPTS);
                        scheduler.schedule(() -> {
                            if (roomManager.currentRoom.get() == state && connectionCycleActive.get() && activeHolePunchers.get("joiner") == finalPuncher) {
                                tryUdpPunch(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles, attempt + 1);
                            }
                        }, delay, TimeUnit.MILLISECONDS);
                    } else {
                        tryConnectionStep(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles, 1);
                    }
                    return;
                }
                DatagramSocket socket = result.getSuccessSocket();
                if (roomManager.currentRoom.get() != state || !connectionWon.compareAndSet(false, true)) {
                    try { finalPuncher.close(); } catch (Exception ignored) {}
                    return;
                }
                String logTarget = fTargetIp != null && fTargetIp.contains(":") ? "[" + fTargetIp + "]:" + fTargetPort : fTargetIp + ":" + fTargetPort;
                VoxLinkMod.LOGGER.info("[Connection] UDP punch success {} (attempt {})", logTarget, attempt);
                finalPuncher.markSocketTransferred();
                stopAllPunchingAfterHostBridge();
                finalPuncher.stopPunch();
                final DatagramSocket punchSocket = socket;
                final UdpHolePuncher puncherRef = finalPuncher;
                InetSocketAddress actualAddr = puncherRef.getActualRemoteAddress();
                if (actualAddr == null) actualAddr = new InetSocketAddress(fTargetIp, fTargetPort);
                final InetSocketAddress finalTargetAddr = actualAddr;
                scheduler.submit(() -> {
                    try {
                        establishUdpTransport(state, punchSocket, puncherRef,
                                finalTargetAddr, "joiner", false, null);
                    } catch (Exception e) {
                        VoxLinkMod.LOGGER.error("[Connection] Create UDP transport failed: {}", e.getMessage());
                        try { puncherRef.close(); } catch (Exception ignored) {}
                        showConnectFailed(state);
                    }
                });
            }).exceptionally(e -> {
                VoxLinkMod.LOGGER.warn("[Connection] UDP punch failed (cycle {}/{}, attempt {}/{}): {}", displayCycle, maxCycles, attempt, UDP_PUNCH_MAX_ATTEMPTS, e.getMessage());
                finalPuncher.stopPunch();
                if (roomManager.currentRoom.get() != state) {
                    try { finalPuncher.close(); } catch (Exception ignored) {}
                    activeHolePunchers.remove("joiner");
                    connectionCycleActive.set(false);
                    ConnectionHelper.resetConnecting();
                    return null;
                }
                if ("punch stopped".equals(e.getMessage())) {
                    VoxLinkMod.LOGGER.info("[Connection] Punch actively stopped, no retry");
                    return null;
                }
                if (activeHolePunchers.get("joiner") != finalPuncher) {
                    VoxLinkMod.LOGGER.info("[Connection] Puncher replaced, no retry");
                    return null;
                }
                //黑名单: 记录UDP失败
                addressBlacklist.recordUdpFailure(punchTargetAddr);
                // 防火墙检测: UDP被阻则跳过所有重试，直接进Wave 2 TCP
                if (e.getCause() instanceof icu.wuhui.voxlink.network.FirewallBlockedException || e instanceof icu.wuhui.voxlink.network.FirewallBlockedException) {
                    VoxLinkMod.LOGGER.warn("[Connection] Firewall blocked UDP, skip retry enter Wave 2 TCP fallback");
                    try { finalPuncher.close(); } catch (Exception ignored) {}
                    activeHolePunchers.remove("joiner");
                    tryConnectionStep(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles, 1);
                    return null;
                }
                if (attempt < UDP_PUNCH_MAX_ATTEMPTS) {
                    long delay = UDP_PUNCH_RETRY_DELAY_MS * (1L << Math.min(attempt - 1, 4));
                    VoxLinkMod.LOGGER.info("[Connection] Retry UDP punch after {}ms (attempt {}/{})", delay, attempt + 1, UDP_PUNCH_MAX_ATTEMPTS);
                    scheduler.schedule(() -> {
                        if (roomManager.currentRoom.get() == state && connectionCycleActive.get() && activeHolePunchers.get("joiner") == finalPuncher) {
                            tryUdpPunch(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles, attempt + 1);
                        }
                    }, delay, TimeUnit.MILLISECONDS);
                } else {
                    tryConnectionStep(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles, 1);
                }
                return null;
            });
        } else {
            VoxLinkMod.LOGGER.info("[Connection] EasyTier method: {} sockets punch to {}:{}", multiSockets.size(), fTargetIp, fTargetPort);
            final UdpHolePuncher leadPuncher = multiSockets.get(0);
            activeHolePunchers.put("joiner", leadPuncher);
            leadPuncher.punchMultiSocket(fTargetIp, fTargetPort, multiSockets, multiWon).thenAccept(result -> {
                if (!result.isSuccess()) {
                    //debounce Layer3 失败分类+下轮参数调节
                    PunchFailureClassifier.FailureReason reason = PunchFailureClassifier.classify(result);
                    lastPunchResult = result.withReason(reason);
                    VoxLinkMod.LOGGER.info("[Connection] cycle={} multi-socket punch failed reason={} recvPunch={} recvAck={}",
                            cycle, reason, result.socketsReceivedPunch, result.socketsReceivedAck);
                    PunchTuner.nextParams(PunchProfile.current(), localNatClass, remoteNatClass,
                            cycle + 1, maxCycles, reason, lastPunchResult);
                    //debounce 保留原exceptionally的失败处理逻辑 不退化
                    for (UdpHolePuncher sp : multiSockets) { try { sp.stopPunch(); } catch (Exception ignored) {} }
                    for (UdpHolePuncher sp : multiSockets) { try { sp.close(); } catch (Exception ignored) {} }
                    for (int si = 0; si < multiSockets.size(); si++) activeHolePunchers.remove("joiner_ms_" + si);
                    activeHolePunchers.remove("joiner");
                    if (roomManager.currentRoom.get() != state) {
                        connectionCycleActive.set(false);
                        ConnectionHelper.resetConnecting();
                        return;
                    }
                    //debounce 防火墙检测: UDP被阻则跳过所有重试 直接进Wave 2 TCP
                    if (result.firewallDetected) {
                        VoxLinkMod.LOGGER.warn("[Connection] Firewall blocked UDP (multi-socket), skip retry enter Wave 2 TCP fallback");
                        tryConnectionStep(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles, 1);
                        return;
                    }
                    if (attempt < UDP_PUNCH_MAX_ATTEMPTS) {
                        long delay = UDP_PUNCH_RETRY_DELAY_MS * (1L << Math.min(attempt - 1, 4));
                        VoxLinkMod.LOGGER.info("[Connection] Retry UDP punch after {}ms (attempt {}/{})", delay, attempt + 1, UDP_PUNCH_MAX_ATTEMPTS);
                        scheduler.schedule(() -> {
                            if (roomManager.currentRoom.get() == state && connectionCycleActive.get()) {
                                tryUdpPunch(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles, attempt + 1);
                            }
                        }, delay, TimeUnit.MILLISECONDS);
                    } else {
                        tryConnectionStep(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles, 1);
                    }
                    return;
                }
                DatagramSocket socket = result.getSuccessSocket();
                if (roomManager.currentRoom.get() != state || !connectionWon.compareAndSet(false, true)) {
                    for (UdpHolePuncher sp : multiSockets) { try { sp.close(); } catch (Exception ignored) {} }
                    return;
                }
                String logTarget = fTargetIp != null && fTargetIp.contains(":") ? "[" + fTargetIp + "]:" + fTargetPort : fTargetIp + ":" + fTargetPort;
                VoxLinkMod.LOGGER.info("[Connection] Multi-socket punch success {} (attempt {})", logTarget, attempt);
                leadPuncher.markSocketTransferred();
                stopAllPunchingAfterHostBridge();
                for (UdpHolePuncher sp : multiSockets) {
                    if (sp.getSocket() != socket) { try { sp.stopPunch(); sp.close(); } catch (Exception ignored) {} }
                }
                final DatagramSocket punchSocket = socket;
                final UdpHolePuncher puncherRef = leadPuncher;
                InetSocketAddress actualAddr = puncherRef.getActualRemoteAddress();
                if (actualAddr == null) actualAddr = new InetSocketAddress(fTargetIp, fTargetPort);
                final InetSocketAddress finalTargetAddr = actualAddr;
                scheduler.submit(() -> {
                    try {
                        establishUdpTransport(state, punchSocket, puncherRef,
                                finalTargetAddr, "joiner", false, null);
                    } catch (Exception e) {
                        VoxLinkMod.LOGGER.error("[Connection] Create UDP transport failed: {}", e.getMessage());
                        try { puncherRef.close(); } catch (Exception ignored) {}
                        showConnectFailed(state);
                    }
                });
            }).exceptionally(e -> {
                VoxLinkMod.LOGGER.warn("[Connection] Multi-socket punch failed (cycle {}/{}, attempt {}/{}): {}", displayCycle, maxCycles, attempt, UDP_PUNCH_MAX_ATTEMPTS, e.getMessage());
                for (UdpHolePuncher sp : multiSockets) { try { sp.stopPunch(); } catch (Exception ignored) {} }
                if (roomManager.currentRoom.get() != state) {
                    for (UdpHolePuncher sp : multiSockets) { try { sp.close(); } catch (Exception ignored) {} }
                    for (int si = 0; si < multiSockets.size(); si++) activeHolePunchers.remove("joiner_ms_" + si);
                    activeHolePunchers.remove("joiner");
                    connectionCycleActive.set(false);
                    ConnectionHelper.resetConnecting();
                    return null;
                }
                if ("punch stopped".equals(e.getMessage())) {
                    VoxLinkMod.LOGGER.info("[Connection] Punch actively stopped, no retry");
                    return null;
                }
                // 防火墙检测: UDP被阻则跳过所有重试，直接进Wave 2 TCP
                if (e.getCause() instanceof icu.wuhui.voxlink.network.FirewallBlockedException || e instanceof icu.wuhui.voxlink.network.FirewallBlockedException) {
                    VoxLinkMod.LOGGER.warn("[Connection] Firewall blocked UDP (multi-socket), skip retry enter Wave 2 TCP fallback");
                    for (UdpHolePuncher sp : multiSockets) { try { sp.close(); } catch (Exception ignored) {} }
                    for (int si = 0; si < multiSockets.size(); si++) activeHolePunchers.remove("joiner_ms_" + si);
                    activeHolePunchers.remove("joiner");
                    tryConnectionStep(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles, 1);
                    return null;
                }
                if (attempt < UDP_PUNCH_MAX_ATTEMPTS) {
                    long delay = UDP_PUNCH_RETRY_DELAY_MS * (1L << Math.min(attempt - 1, 4));
                    VoxLinkMod.LOGGER.info("[Connection] Retry UDP punch after {}ms (attempt {}/{})", delay, attempt + 1, UDP_PUNCH_MAX_ATTEMPTS);
                    scheduler.schedule(() -> {
                        if (roomManager.currentRoom.get() == state && connectionCycleActive.get()) {
                            tryUdpPunch(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles, attempt + 1);
                        }
                    }, delay, TimeUnit.MILLISECONDS);
                } else {
                    tryConnectionStep(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles, 1);
                }
                return null;
            });
        }
    }

    public void advanceToNextCycle(RoomManager.RoomState state, String from, String hostIpv6, String hostIp, int hostPort, String hostMappedIp, int hostMappedPort, int cycle, int maxCycles) {
        if (connectionWon.get()) {
            VoxLinkMod.LOGGER.info("[Connection] Connected, skip next cycle");
            return;
        }
        if (cycle + 1 >= maxCycles) {
            //debounce 持续重试: 双方1.0.7+支持CAP_CONTINUOUS_RETRY时 重置cycle=0无限重试到玩家取消
            if (enterContinuousRetryRound(state)) return;
            showConnectFailed(state);
            return;
        }
        for (java.util.Map.Entry<String, UdpHolePuncher> entry : activeHolePunchers.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("joiner")) {
                UdpHolePuncher p = entry.getValue();
                try { p.stopPunch(); } catch (Exception ignored) {}
                //joiner_reverse保留给反向打洞复用
                if (key.startsWith("joiner_ms_")) {
                    try { p.close(); } catch (Exception ignored) {}
                }
            }
        }
        //debounce 智能档位切换: 首轮失败+硬对称NAT场景 切到AGGRESSIVE让下轮用激进参数 兼容极端场景
        if (cycle == 0 && !PunchProfile.isAggressive()) {
            boolean hostSym = state.roomInfo.isHostSymmetric();
            boolean localSym = (stunProbeResult != null && stunProbeResult.natType.isSymmetric());
            if (hostSym || localSym) {
                PunchProfile.switchToAggressive("首轮失败+" + (hostSym ? "HostSym" : "LocalSym"));
                VoxLinkMod.LOGGER.info("[Connection] Detected extreme symmetric NAT, switch to aggressive level next round: {}", PunchProfile.describe());
            }
        }
        //debounce 失败历史感知: 连续3次同原因失败切策略 避免陷入同一失败模式
        if (lastPunchResult != null && lastPunchResult.reason != null) {
            if (lastPunchResult.reason == lastFailureReason) {
                int count = consecutiveFailureCount.incrementAndGet();
                if (count >= 3) {
                    VoxLinkMod.LOGGER.warn("[Connection] Consecutive {} failures with same reason ({}), switch strategy",
                            count, lastFailureReason);
                    switch (lastFailureReason) {
                        case NO_RESPONSE -> PunchProfile.switchToHardSym("consecutive_no_response");
                        case FIREWALL_DETECTED -> { /* 防火墙不切档 relay路径处理 */ }
                        default -> PunchProfile.switchToAggressive("consecutive_" + lastFailureReason);
                    }
                    consecutiveFailureCount.set(0);
                }
            } else {
                lastFailureReason = lastPunchResult.reason;
                consecutiveFailureCount.set(1);
            }
        }
        int delayIdx = Math.min(cycle, BACKOFF_DELAYS_MS.length - 1);
        long delay = BACKOFF_DELAYS_MS[delayIdx];
        VoxLinkMod.LOGGER.info("[Connection] Cycle {}/{} failed, retry in {}s (backoff level={})", cycle + 1, maxCycles, delay / 1000, PunchProfile.describe());
        scheduler.schedule(() -> {
            if (roomManager.currentRoom.get() == state && state != RoomManager.PENDING) {
                runConnectionCycle(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle + 1);
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    public java.util.List<String> selectReachableStunGroup(int cycle) {
        if (stunProbeResult != null && !stunProbeResult.reachableStunUrls.isEmpty()) {
            java.util.List<String> reachable = stunProbeResult.reachableStunUrls;
            int index = cycle % reachable.size();
            return java.util.List.of(reachable.get(index));
        }
        return StunDetector.getStunGroup(cycle % StunDetector.getStunGroupCount());
    }

    public void startReversePunch(RoomManager.RoomState state) {
        VoxLinkMod.LOGGER.info("[ReversePunch] Parallel start reverse punch");
        state.roomInfo.setConnectionMode(Component.translatable("voxlink.connection.punching"));
        connectionCycleActive.set(true);

        // 不杀正向打洞！正向和反向并行

        boolean isSymmetric = (stunProbeResult != null && stunProbeResult.natType.isSymmetric());
        boolean isEasySym = (stunProbeResult != null && stunProbeResult.natType.isEasySymmetric());

        if (isSymmetric) {
            int socketCount = isEasySym ? BIRTHDAY_SOCKET_COUNT : HARD_SYM_SOCKET_COUNT;
            VoxLinkMod.LOGGER.info("[ReversePunch] Detected symmetric NAT — birthday attack {} sockets (easySym={})", socketCount, isEasySym);
            startBirthdayPunch(state, socketCount, isEasySym);
        } else {
            startSimpleReversePunch(state);
        }
    }

    public void startBirthdayPunch(RoomManager.RoomState state) {
        startBirthdayPunch(state, BIRTHDAY_SOCKET_COUNT, false);
    }

    public void startBirthdayPunch(RoomManager.RoomState state, int socketCount, boolean isEasySym) {
        String hostMappedIp = state.roomInfo.getHostMappedIp();
        int hostMappedPort = state.roomInfo.getHostMappedPort();
        if (hostMappedIp == null || hostMappedPort <= 0) {
            hostMappedIp = state.roomInfo.getHostIp();
            hostMappedPort = state.roomInfo.getHostPort() > 0 ? state.roomInfo.getHostPort() : 51600;
        }
        final String fHostMappedIp = hostMappedIp;
        final int fHostMappedPort = hostMappedPort;

        VoxLinkMod.LOGGER.info("[BirthdayPunch] Parallel STUN {} sockets (target={}:{}, easySym={})",
                socketCount, fHostMappedIp, fHostMappedPort, isEasySym);

        // 修复6: 使用socket数组复用, 避免每次新建84 socket + 84次STUN
        // 30秒窗口内复用cached数组, STUN只对前4个socket做(取基线), 其余复用结果
        CompletableFuture.supplyAsync(() ->
                getOrCreateUdpArray(socketCount, isEasySym, StunDetector.getAllStunUrls())
        ).thenAccept(udpArray -> {
            if (roomManager.currentRoom.get() != state) return;

            if (udpArray == null || udpArray.punchers.isEmpty()) {
                VoxLinkMod.LOGGER.error("[BirthdayPunch] All STUN queries failed");
                showConnectFailed(state);
                return;
            }

            java.util.List<UdpHolePuncher> birthdayPunchers = udpArray.punchers;
            java.util.List<StunProbe.PublicMappedAddress> mappedAddresses = udpArray.mappedAddrs;
            java.util.List<String> mappedPortList = new java.util.ArrayList<>();
            java.util.List<String> birthdayKeys = new java.util.ArrayList<>();

            for (int i = 0; i < birthdayPunchers.size(); i++) {
                String key = "joiner_birthday_" + i;
                birthdayKeys.add(key);
                activeHolePunchers.put(key, birthdayPunchers.get(i));
                StunProbe.PublicMappedAddress addr = i < mappedAddresses.size() ? mappedAddresses.get(i) : null;
                mappedPortList.add(addr != null ? String.valueOf(addr.port()) : "0");
            }

            // 取第一个有效mappedAddr作为primary (非采样socket的mappedAddr为0.0.0.0:0)
            StunProbe.PublicMappedAddress primaryAddr = null;
            for (StunProbe.PublicMappedAddress addr : mappedAddresses) {
                if (addr != null && addr.port() > 0) {
                    primaryAddr = addr;
                    break;
                }
            }
            if (primaryAddr == null) {
                VoxLinkMod.LOGGER.error("[BirthdayPunch] No valid mappedAddr");
                showConnectFailed(state);
                return;
            }

            VoxLinkMod.LOGGER.info("[BirthdayPunch] Prepare {} sockets, mapped ports: {}", birthdayPunchers.size(), mappedPortList);

            JsonObject offerData = new JsonObject();
            offerData.addProperty("joinerMappedIp", primaryAddr.ip());
            offerData.addProperty("joinerMappedPort", primaryAddr.port());
            offerData.addProperty("joinerSymmetric", true);
            if (isEasySym) {
                offerData.addProperty("joinerEasySym", true);
            }
            offerData.add("joinerMappedPorts", new com.google.gson.JsonArray());
            for (StunProbe.PublicMappedAddress addr : mappedAddresses) {
                if (addr != null && addr.port() > 0) {
                    offerData.getAsJsonArray("joinerMappedPorts").add(addr.port());
                }
            }

            signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(),
                    false, "reverse_holepunch_offer", offerData, "host")
                    .thenAccept(response -> {
                        if (!response.success) {
                            VoxLinkMod.LOGGER.error("[BirthdayPunch] Send reverse_holepunch_offer failed: {}", response.error);
                        }
                    })
                    .exceptionally(e -> {
                        VoxLinkMod.LOGGER.error("[BirthdayPunch] Send reverse_holepunch_offer failed: {}", e.getMessage());
                        return null;
                    });

            startBirthdayPunchPhase2(state, birthdayPunchers, birthdayKeys,
                    fHostMappedIp, fHostMappedPort, state.roomInfo.isHostSymmetric(), isEasySym);

            // 先发包才能收包
            VoxLinkMod.LOGGER.info("[BirthdayPunch] Start punch to {}:{} immediately (no wait for reverse_punch_info)",
                    fHostMappedIp, fHostMappedPort);

            scheduler.schedule(() -> {
                if (connectionCycleActive.get() && roomManager.currentRoom.get() == state) {
                    VoxLinkMod.LOGGER.warn("[BirthdayPunch] Timeout (no connection established)");
                    for (UdpHolePuncher p : birthdayPunchers) { try { p.cancel(); p.close(); } catch (Exception ignored) {} }
                    activeHolePunchers.entrySet().removeIf(e -> e.getKey().startsWith("joiner_birthday_"));
                    showConnectFailed(state);
                }
            }, UDP_PUNCH_TIMEOUT_S + EXTRA_TIMEOUT_SEC, TimeUnit.SECONDS);
        });
    }

    public void startSimpleReversePunch(RoomManager.RoomState state) {
        UdpHolePuncher puncher = new UdpHolePuncher();
        try {
            puncher.createSocket();
        } catch (Exception e) {
            VoxLinkMod.LOGGER.error("[ReversePunch] Create punch socket failed: {}", e.getMessage());
            return;
        }
        activeHolePunchers.put("joiner_reverse", puncher);

        //并行双STUN
        // 修复2: 4个STUN并发, 取前2个成功响应比对, 提高对称NAT检测冗余
        // 旧逻辑仅2个STUN, 任一不可达即降级单测无法判定对称; 新逻辑4个并发容错更强
        java.util.List<String> quadStun = StunDetector.getAllStunUrls();
        StunProbe.PublicMappedAddress[] quadResult = StunProbe.discoverMappedAddressQuad(
                puncher.getSocket(), quadStun.get(0), quadStun.get(1), quadStun.get(2), quadStun.get(3));
        StunProbe.PublicMappedAddress myMapped1 = quadResult[0] != null ? quadResult[0] : (quadResult[2] != null ? quadResult[2] : quadResult[3]);
        StunProbe.PublicMappedAddress myMapped2 = quadResult[1] != null ? quadResult[1] : (quadResult[3] != null ? quadResult[3] : quadResult[2]);
        boolean joinerSymmetric = false;
        int joinerMappedPortDelta = 0;
        StunProbe.PublicMappedAddress myMappedAddr = null;
        if (myMapped1 != null && myMapped2 != null) {
            if (myMapped1.port() != myMapped2.port()) {
                joinerSymmetric = true;
                joinerMappedPortDelta = myMapped2.port() - myMapped1.port();
                VoxLinkMod.LOGGER.info("[ReversePunch] Joiner punch socket STUN: detected symmetric ({} vs {}, delta={})", myMapped1.port(), myMapped2.port(), joinerMappedPortDelta);
            }
            myMappedAddr = myMapped2;
        } else {
            myMappedAddr = myMapped1 != null ? myMapped1 : myMapped2;
        }
        if (myMappedAddr == null) {
            myMappedAddr = puncher.discoverMappedAddress(
                    StunDetector.getAllStunUrls());
        }

        if (myMappedAddr == null) {
            VoxLinkMod.LOGGER.warn("[ReversePunch] STUN failed, cannot reverse punch");
            puncher.close();
            activeHolePunchers.remove("joiner_reverse");
            return;
        }

        VoxLinkMod.LOGGER.info("[ReversePunch] Joiner mapped address: {}:{} (symmetric={})", myMappedAddr.ip(), myMappedAddr.port(), joinerSymmetric);

        JsonObject offerData = new JsonObject();
        offerData.addProperty("joinerMappedIp", myMappedAddr.ip());
        offerData.addProperty("joinerMappedPort", myMappedAddr.port());
        if (joinerSymmetric || (stunProbeResult != null && stunProbeResult.natType.isSymmetric())) {
            offerData.addProperty("joinerSymmetric", true);
        }
        if ((stunProbeResult != null && stunProbeResult.natType.isEasySymmetric()) || joinerSymmetric) {
            offerData.addProperty("joinerEasySym", true);
        }
        if (joinerMappedPortDelta != 0) {
            offerData.addProperty("joinerMappedPortDelta", joinerMappedPortDelta);
        }

        signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(),
                false, "reverse_holepunch_offer", offerData, "host")
                .thenAccept(response -> {
                    if (!response.success) {
                        VoxLinkMod.LOGGER.error("[ReversePunch] Failed to send reverse_holepunch_offer: {}", response.error);
                    }
                })
                .exceptionally(e -> {
                    VoxLinkMod.LOGGER.error("[ReversePunch] Failed to send reverse_holepunch_offer: {}", e.getMessage());
                    return null;
                });

        // 立即打洞，不等reverse_punch_info
        String hostMappedIp = state.roomInfo.getHostMappedIp();
        int hostMappedPort = state.roomInfo.getHostMappedPort();
        if (hostMappedIp == null || hostMappedPort <= 0) {
            hostMappedIp = state.roomInfo.getHostIp();
            hostMappedPort = state.roomInfo.getHostPort() > 0 ? state.roomInfo.getHostPort() : 51600;
        }
        if (hostMappedIp == null || hostMappedIp.isEmpty()) {
            VoxLinkMod.LOGGER.warn("[ReversePunch] No host address, cannot punch immediately");
            return;
        }

        final String fHostMappedIp = hostMappedIp;
        final int fHostMappedPort = hostMappedPort;
        final UdpHolePuncher finalPuncher = puncher;

        int portRange = joinerSymmetric ? 30 : (state.roomInfo.isHostSymmetric() ? PunchProfile.current().portPredictionMaxRange : 30);
        VoxLinkMod.LOGGER.info("[ReversePunch] Punch to {}:{} immediately (range=±{})", fHostMappedIp, fHostMappedPort, portRange);

        puncher.setOnPeerPunchReceived(addr -> {
            String code = state.roomInfo.getCode();
            String token = state.roomInfo.getToken();
            JsonObject portData = new JsonObject();
            portData.addProperty("peer_ip", addr.getAddress().getHostAddress());
            portData.addProperty("peer_port", addr.getPort());
            signalingClient.sendSignal(code, token, false, "peer_port", portData, "host")
                    .exceptionally(e -> { VoxLinkMod.LOGGER.debug("peer_port signal send failed: {}", e.getMessage()); return null; });
        });

        puncher.punchWithPortPrediction(fHostMappedIp, fHostMappedPort, portRange, true).thenAccept(result -> {
            if (!result.isSuccess()) {
                PunchFailureClassifier.FailureReason reason = PunchFailureClassifier.classify(result);
                lastPunchResult = result.withReason(reason);
                PunchTuner.nextParams(PunchProfile.current(), localNatClass, remoteNatClass, 1, MAX_CONNECTION_CYCLES, reason, lastPunchResult);
                VoxLinkMod.LOGGER.info("[ConnectionManager] Punch failed reason={} recvPunch={} recvAck={}", reason, result.socketsReceivedPunch, result.socketsReceivedAck);
                return;
            }
            DatagramSocket socket = result.getSuccessSocket();
            if (roomManager.currentRoom.get() != state || !connectionWon.compareAndSet(false, true)) {
                try { finalPuncher.close(); } catch (Exception ignored) {}
                return;
            }
            VoxLinkMod.LOGGER.info("[ReversePunch] Joiner reverse punch success {}:{}", fHostMappedIp, fHostMappedPort);
            finalPuncher.markSocketTransferred();
            stopAllPunchingAfterHostBridge();

            finalPuncher.stopPunch();
            final DatagramSocket punchSocket = socket;
            final UdpHolePuncher puncherRef = finalPuncher;

            // 用实际收到包的地址，而非STUN映射地址（对称NAT端口会偏移）
            InetSocketAddress actualAddr = puncherRef.getActualRemoteAddress();
            if (actualAddr == null) actualAddr = new InetSocketAddress(fHostMappedIp, fHostMappedPort);
            final InetSocketAddress finalTargetAddr = actualAddr;
            VoxLinkMod.LOGGER.info("[ReversePunch] Actual target address: {} (STUN mapping: {}:{})", finalTargetAddr, fHostMappedIp, fHostMappedPort);

            scheduler.submit(() -> {
                try {
                    establishUdpTransport(state, punchSocket, puncherRef,
                            finalTargetAddr, "joiner", false, null);
                } catch (Exception e) {
                    VoxLinkMod.LOGGER.error("[ReversePunch] Joiner UDP transport create failed: {}", e.getMessage());
                    try { puncherRef.close(); } catch (Exception ignored) {}
                    showConnectFailed(state);
                }
            });
        }).exceptionally(e -> {
            VoxLinkMod.LOGGER.debug("[ReversePunch] Joiner reverse punch failed (wait for reverse_punch_info to update target then retry): {}", e.getMessage());
            // 不调showConnectFailedFinal，等reverse_punch_info更新目标后重试
return null;
        });

        // 反向打洞需覆盖信号投递延迟(~12s)+host STUN(3s), 超时设为30s而非13s
        scheduler.schedule(() -> {
            if (connectionCycleActive.get() && roomManager.currentRoom.get() == state && !connectionWon.get()) {
                VoxLinkMod.LOGGER.warn("[ReversePunch] Reverse punch timeout");
                UdpHolePuncher rp = activeHolePunchers.remove("joiner_reverse");
                if (rp != null) { rp.cancel(); rp.close(); }
            }
        }, REVERSE_PUNCH_TIMEOUT_SEC, TimeUnit.SECONDS);
    }

    public void startBirthdayPunchPhase2(RoomManager.RoomState state, java.util.List<UdpHolePuncher> birthdayPunchers,
                                           java.util.List<String> birthdayKeys, String hostMappedIp, int hostMappedPort, boolean hostSymmetric,
                                           boolean isEasySym) {
        java.util.concurrent.atomic.AtomicBoolean won = new java.util.concurrent.atomic.AtomicBoolean(false);

        for (int i = 0; i < birthdayPunchers.size(); i++) {
            UdpHolePuncher puncher = birthdayPunchers.get(i);
            if (puncher.getSocket() == null || puncher.getSocket().isClosed()) continue;

            final int idx = i;
            puncher.setOnPeerPunchReceived(addr -> {
                if (won.get()) return;
                VoxLinkMod.LOGGER.info("[BirthdayPunch] Socket #{} received peer punch to {}:{}", idx, addr.getAddress().getHostAddress(), addr.getPort());
            });

            int portRange = PORT_RANGE_DEFAULT;
            if (isEasySym) {
                portRange = EASY_SYM_PORT_RANGE;
            } else if (hostSymmetric) {
                portRange = PORT_RANGE_DEFAULT;
            } else {
                portRange = MIN_PORT_RANGE;
            }
            puncher.punchWithPortPrediction(hostMappedIp, hostMappedPort, portRange, true).thenAccept(result -> {
                if (!result.isSuccess()) {
                    PunchFailureClassifier.FailureReason reason = PunchFailureClassifier.classify(result);
                    lastPunchResult = result.withReason(reason);
                    PunchTuner.nextParams(PunchProfile.current(), localNatClass, remoteNatClass, 1, MAX_CONNECTION_CYCLES, reason, lastPunchResult);
                    VoxLinkMod.LOGGER.info("[ConnectionManager] Punch failed reason={} recvPunch={} recvAck={}", reason, result.socketsReceivedPunch, result.socketsReceivedAck);
                    return;
                }
                DatagramSocket socket = result.getSuccessSocket();
                if (!connectionWon.compareAndSet(false, true)) {
                    try { puncher.close(); } catch (Exception ignored) {}
                    return;
                }
                if (!won.compareAndSet(false, true)) {
                    try { puncher.close(); } catch (Exception ignored) {}
                    return;
                }
                VoxLinkMod.LOGGER.info("[BirthdayPunch] Socket #{} won! Connected to {}:{}", idx, hostMappedIp, hostMappedPort);
                puncher.markSocketTransferred();
                //debounce 停所有打洞 含birthday组+forward 不发cancel 不重置状态
                stopAllPunchingAfterHostBridge();
                for (UdpHolePuncher sp : birthdayPunchers) {
                    if (sp != puncher) { try { sp.stopPunch(); sp.close(); } catch (Exception ignored) {} }
                }
                puncher.stopPunch();
                final DatagramSocket punchSocket = socket;
                final UdpHolePuncher puncherRef = puncher;

                scheduler.submit(() -> {
                    try {
                        establishUdpTransport(state, punchSocket, puncherRef,
                                new InetSocketAddress(hostMappedIp, hostMappedPort), "joiner", false, null);
                    } catch (Exception e) {
                        VoxLinkMod.LOGGER.error("[BirthdayPunch] Transport create failed: {}", e.getMessage());
                        try { puncherRef.close(); } catch (Exception ignored) {}
                        showConnectFailed(state);
                    }
                });
            }).exceptionally(e -> {
                VoxLinkMod.LOGGER.debug("[BirthdayPunch] Socket #{} failed: {}", idx, e.getMessage());
                return null;
            });
        }

        scheduler.schedule(() -> {
            if (!won.get() && connectionCycleActive.get() && roomManager.currentRoom.get() == state) {
                VoxLinkMod.LOGGER.warn("[BirthdayPunch] All {} sockets timeout", birthdayPunchers.size());
                for (int i = 0; i < birthdayPunchers.size(); i++) {
                    try { birthdayPunchers.get(i).cancel(); birthdayPunchers.get(i).close(); } catch (Exception ignored) {}
                    activeHolePunchers.remove(birthdayKeys.get(i));
                }
                showConnectFailed(state);
            }
        }, UDP_PUNCH_TIMEOUT_S + EXTRA_TIMEOUT_SEC, TimeUnit.SECONDS);
    }

    public void connectViaBridge(RoomManager.RoomState state, ConnectionFallback.ConnectResult result) {
        //debounce 双P2P模式 VoxLink建桥前先停Terracotta 避免隧道建好却没用
        if (dualRaceActive) {
            killAllConnectionAttempts("terracotta");
        }
        killAllConnectionAttempts();
        P2PBridge.cancelPendingUdpTimeouts();
        for (ReliableUdpTransport transport : activeUdpTransports.values()) {
            try { transport.close(); } catch (Exception ignored) {}
        }
        activeUdpTransports.clear();
        state.roomInfo.setConnectionMode(Component.translatable("voxlink.connection.punching"));

        String remoteHost = result.remoteHost;
        int remotePort = result.remotePort;

        if (result.mode == ConnectionMode.IPV6_DIRECT) {
            P2PBridge.connectToHostIpv6(remoteHost, remotePort)
                    .thenAccept(localPort -> {
                        if (localPort > 0) {
                            connectionCycleActive.set(false);
                            signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(),
                                    false, "connected", new JsonObject(), "host");
                            state.roomInfo.setConnectionMode(Component.translatable("voxlink.connection.bridge_setup"));
                            ConnectionHelper.connectToServer(localPort, state.roomInfo);
                            notifyDualVoxlinkBridge(true);
                        } else {
                            connectionCycleActive.set(false);
                            ConnectionHelper.resetConnecting();
                            state.roomInfo.setConnectionMode(Component.translatable("voxlink.connection.bridge_start_failed"), true);
                            signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(),
                                    false, "disconnect", new JsonObject(), "host");
                            if (!dualRaceActive) {
                                handleConnectViaBridgeFailed(state);
                            }
                            notifyDualVoxlinkBridge(false);
                        }
                    });
        } else {
            P2PBridge.connectToHost(remoteHost, remotePort)
                    .thenAccept(localPort -> {
                        if (localPort > 0) {
                            connectionCycleActive.set(false);
                            signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(),
                                    false, "connected", new JsonObject(), "host");
                            state.roomInfo.setConnectionMode(Component.translatable("voxlink.connection.bridge_setup"));
                            ConnectionHelper.connectToServer(localPort, state.roomInfo);
                            notifyDualVoxlinkBridge(true);
                        } else {
                            connectionCycleActive.set(false);
                            ConnectionHelper.resetConnecting();
                            state.roomInfo.setConnectionMode(Component.translatable("voxlink.connection.bridge_start_failed"), true);
                            signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(),
                                    false, "disconnect", new JsonObject(), "host");
                            if (!dualRaceActive) {
                                handleConnectViaBridgeFailed(state);
                            }
                            notifyDualVoxlinkBridge(false);
                        }
                    });
        }
    }

    public void handleConnectViaBridgeFailed(RoomManager.RoomState state) {
        if (roomManager.currentRoom.get() != state || state == RoomManager.PENDING) return;
        try {
            scheduler.execute(() -> {
                if (roomManager.currentRoom.get() == state && state != RoomManager.PENDING) {
                    roomManager.leaveRoom();
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ex) {
            VoxLinkMod.LOGGER.warn("Scheduler closed, sync execute leaveRoom");
            if (roomManager.currentRoom.get() == state && state != RoomManager.PENDING) {
                roomManager.leaveRoom();
            }
        }
    }

    public void startFallbackMonitor(ConnectionFallback fallback, RoomManager.RoomState state) {
        Thread t = new Thread(() -> {
            int loopCount = 0;
            while (!fallback.isSettled() && !fallback.isCancelled()) {
                if (loopCount >= MAX_FALLBACK_LOOPS) {
                VoxLinkMod.LOGGER.warn("Fallback monitor timeout (about 60s)");
                    break;
                }
                Component status = fallback.getStatusText();
                if (status != null) {
                    state.roomInfo.setConnectionMode(status);
                }
                loopCount++;
                try { Thread.sleep(FALLBACK_SLEEP_MS); } catch (InterruptedException e) { return; }
            }
            Component finalStatus = fallback.getStatusText();
            if (finalStatus != null) {
                state.roomInfo.setConnectionMode(finalStatus);
            }
        }, "VoxLink-FallbackMonitor");
        t.setDaemon(true);
        t.start();
    }

    //debounce 持续重试: 双方1.0.7+支持CAP_CONTINUOUS_RETRY时 重置cycle=0无限重试到玩家取消
    //复用savedConnection参数 超时/advanceToNextCycle/runConnectionCycle三处入口共用
    private boolean enterContinuousRetryRound(RoomManager.RoomState state) {
        if (connectionWon.get()) return false;
        if (!shouldContinuousRetry(state)) return false;
        if (savedConnectionState != state) {
            VoxLinkMod.LOGGER.warn("[Connection] Persistent retry aborted: saved params mismatch current room");
            return false;
        }
        int round = continuousRetryRound.incrementAndGet();
        escalateProfileForRound(round);
        VoxLinkMod.LOGGER.info("[Connection] Enter persistent retry round={}, level={}, reset cycle from 0",
                round, PunchProfile.describe());
        ConnectionState.transitionTo(ConnectionState.STUN_PROBE, "持续重试 round " + round);
        //debounce 持续重试取消全局超时 避免打断无限重试
        if (connectionTimeoutFuture != null) {
            connectionTimeoutFuture.cancel(false);
            connectionTimeoutFuture = null;
        }
        state.roomInfo.setConnectionMode(Component.translatable("voxlink.connection.retry_round", round));
        int maxCycles = getEffectiveMaxCycles();
        tryConnectionStep(state, savedConnectionFrom, savedConnectionHostIpv6, savedConnectionHostIp,
                savedConnectionHostPort, savedConnectionHostMappedIp, savedConnectionHostMappedPort,
                0, 1, maxCycles, 0);
        return true;
    }

    public void showConnectFailed(RoomManager.RoomState state) {
        if (roomManager.currentRoom.get() != state || state == RoomManager.PENDING) return;
        //debounce 持续重试优先: 双方支持CAP_CONTINUOUS_RETRY时 重置cycle无限重试 不走relay/final
        //用户原话: 在玩家未自己取消前, 一直持续打洞
        if (enterContinuousRetryRound(state)) return;
        //中继由玩家手动点击按钮触发(triggerManualRelay) 不在此自动调用
        showConnectFailedFinal(state);
    }

    private void tryRelay(RoomManager.RoomState state) {
        // 防止并发重复relay
        if (currentRelayPeer.get() != null) {
            VoxLinkMod.LOGGER.info("[Relay] Relay already trying (current={}), skip duplicate", currentRelayPeer.get());
            return;
        }
        if (state.roomInfo.isHost()) {
            java.util.List<RoomInfo.PeerInfo> candidates = new java.util.ArrayList<>();
            for (RoomInfo.PeerInfo p : state.roomInfo.getPeers()) {
                String nt = p.natType;
                if (nt == null || p.mappedIp == null || p.mappedPort <= 0) continue;
                if (failedRelayPeers.contains(p.clientId)) continue;
                if (!nt.contains("sym") && !nt.contains("strict") && !nt.equals("unknown")) {
                    //debounce 协议协商: 候选中继节点必须支持relay能力 老版本joiner无法处理relay_setup
                    if (!icu.wuhui.voxlink.network.ProtocolNegotiator.supportsRelay(p)) {
                        VoxLinkMod.LOGGER.info("[Relay] Candidate {} is legacy, skip", p.clientId);
                        continue;
                    }
                    candidates.add(p);
                }
            }
            if (candidates.isEmpty()) {
                VoxLinkMod.LOGGER.info("[Relay] No available Cone relay node (excluded failed={})", failedRelayPeers.size());
                failedRelayPeers.clear();
                notifyRelayFailed();
                showConnectFailed(state);
                return;
            }
            // 负载感知排序：优先选中继任务少的peer（通过RelayBridge查询）
            RelayBridge relayBridge = RelayBridge.getInstance(scheduler);
            candidates.sort((a, b) -> {
                int loadA = relayBridge.getRelayCountForPeer(a.clientId);
                int loadB = relayBridge.getRelayCountForPeer(b.clientId);
                return Integer.compare(loadA, loadB);
            });
            //优化: 并行failover, 同时选top 3候选发relay_setup+relay_notify, 任一成功即CAS
            //用户原话: "最好并行处理，这是能提高速度最快方法"
            int parallelN = Math.min(MAX_RELAY_CANDIDATES, candidates.size());
            java.util.List<RoomInfo.PeerInfo> relayCandidates = candidates.subList(0, parallelN);
            RoomInfo.PeerInfo symPeer = null;
            for (RoomInfo.PeerInfo p : state.roomInfo.getPeers()) {
                String nt = p.natType;
                if (nt != null && (nt.contains("sym") || nt.contains("strict") || nt.equals("unknown"))) {
                    //debounce 协议协商: 目标Sym玩家也必须支持relay 老版本无法处理relay_notify
                    if (!icu.wuhui.voxlink.network.ProtocolNegotiator.supportsRelay(p)) {
                        VoxLinkMod.LOGGER.info("[Relay] Target Sym player {} is legacy, skip relay", p.clientId);
                        continue;
                    }
                    symPeer = p;
                    break;
                }
            }
            if (symPeer == null || symPeer.mappedIp == null) {
                VoxLinkMod.LOGGER.warn("[Relay] No symmetric NAT player needs relay");
                notifyRelayFailed();
                showConnectFailed(state);
                return;
            }
            currentRelayPeer.set(relayCandidates.get(0).clientId);
            VoxLinkMod.LOGGER.info("[Relay] Parallel try {} Cone relays, target Sym={}", parallelN, symPeer.clientId);
            //并行: 同时给top N个relay发relay_setup, 同时给sym发多个relay_notify
            for (int i = 0; i < parallelN; i++) {
                RoomInfo.PeerInfo relay = relayCandidates.get(i);
                JsonObject setup = new JsonObject();
                setup.addProperty("targetClientId", symPeer.clientId);
                setup.addProperty("targetIp", symPeer.mappedIp);
                setup.addProperty("targetPort", symPeer.mappedPort);
                signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), true, "relay_setup", setup, relay.clientId);
                JsonObject notify = new JsonObject();
                notify.addProperty("relayIp", relay.mappedIp);
                notify.addProperty("relayPort", relay.mappedPort);
                signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), true, "relay_notify", notify, symPeer.clientId);
                VoxLinkMod.LOGGER.info("[Relay] Concurrent #{} relay={} (load={}) {}:{}", i + 1, relay.clientId,
                        relayBridge.getRelayCountForPeer(relay.clientId), relay.mappedIp, relay.mappedPort);
            }
            //并行超时: 8s内无任一成功则全部标记失败
            relayFailoverTask = scheduler.schedule(() -> {
                if (!connectionWon.get() && roomManager.currentRoom.get() == state) {
                    VoxLinkMod.LOGGER.warn("[Relay] Parallel relay all timeout, mark {} failed", parallelN);
                    for (RoomInfo.PeerInfo r : relayCandidates) {
                        failedRelayPeers.add(r.clientId);
                    }
                    currentRelayPeer.set(null);
                    relayFailoverTask = null;
                    tryRelay(state);
                }
            }, SHORT_TIMEOUT_SEC, TimeUnit.SECONDS);
        } else {
            if (currentRelayPeer.get() != null) return;
            currentRelayPeer.set("joiner_requesting");
            JsonObject data = new JsonObject();
            data.addProperty("clientId", state.roomInfo.getClientId());
            signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), false, "relay_request", data, "host");
            relayFailoverTask = scheduler.schedule(() -> {
                if (!connectionWon.get()) {
                    currentRelayPeer.set(null);
                    relayFailoverTask = null;
                    notifyRelayFailed();
                    showConnectFailed(state);
                }
            }, AWAIT_TIMEOUT_SEC, TimeUnit.SECONDS);
        }
    }

    // relay成功/失败时清理跟踪状态
    private void clearRelayTracking() {
        currentRelayPeer.set(null);
        if (relayFailoverTask != null) {
            relayFailoverTask.cancel(false);
            relayFailoverTask = null;
        }
    }

    public void handleRelayRequest(String from, JsonObject data) {
        RoomManager.RoomState state = roomManager.currentRoom.get();
        if (state == null || state == RoomManager.PENDING) return;
        String requestingClientId = data.has("clientId") ? data.get("clientId").getAsString() : from;
        VoxLinkMod.LOGGER.info("[Relay] Received relay_request, requester={}", requestingClientId);
        //debounce 协议协商: 请求者为老版本(legacy)时不响应relay 老版本无法处理relay_accept/relay_declined
        RoomInfo.PeerInfo requestingPeerCheck = state.roomInfo.getPeer(requestingClientId);
        if (requestingPeerCheck != null && !icu.wuhui.voxlink.network.ProtocolNegotiator.supportsRelay(requestingPeerCheck)) {
            VoxLinkMod.LOGGER.info("[Relay] Requester {} is legacy, no relay_request response", requestingClientId);
            return;
        }
        // 房主在world中，聊天栏提示
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendSystemMessage(
                Component.translatable("voxlink.relay.host_notice"));
        }
        RoomInfo.PeerInfo requestingPeer = state.roomInfo.getPeer(requestingClientId);
        if (requestingPeer == null || requestingPeer.mappedIp == null || requestingPeer.mappedPort <= 0) {
            signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), false, "relay_declined", new JsonObject(), requestingClientId);
            return;
        }
        java.util.List<RoomInfo.PeerInfo> candidates = new java.util.ArrayList<>();
        for (RoomInfo.PeerInfo p : state.roomInfo.getPeers()) {
            if (p.clientId.equals(requestingClientId)) continue;
            if (p.mappedIp == null || p.mappedPort <= 0) continue;
            if (activeUdpTransports.get(p.clientId) == null) continue;
            if (failedRelayPeers.contains(p.clientId)) continue;
            String nt = p.natType;
            if (nt != null && !nt.contains("sym") && !nt.contains("strict") && !nt.equals("unknown")) {
                //debounce 协议协商: 中继候选必须支持relay 老版本joiner无法处理relay_setup
                if (!icu.wuhui.voxlink.network.ProtocolNegotiator.supportsRelay(p)) continue;
                candidates.add(p);
            }
        }
        if (candidates.isEmpty()) {
            signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), false, "relay_declined", new JsonObject(), requestingClientId);
            return;
        }
        RelayBridge relayBridge = RelayBridge.getInstance(scheduler);
        candidates.sort((a, b) -> {
            int loadA = relayBridge.getRelayCountForPeer(a.clientId);
            int loadB = relayBridge.getRelayCountForPeer(b.clientId);
            return Integer.compare(loadA, loadB);
        });
        RoomInfo.PeerInfo relay = candidates.get(0);
        JsonObject setup = new JsonObject();
        setup.addProperty("targetClientId", requestingClientId);
        setup.addProperty("targetIp", requestingPeer.mappedIp);
        setup.addProperty("targetPort", requestingPeer.mappedPort);
        signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), true, "relay_setup", setup, relay.clientId);
        JsonObject notify = new JsonObject();
        notify.addProperty("relayIp", relay.mappedIp);
        notify.addProperty("relayPort", relay.mappedPort);
        signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), true, "relay_notify", notify, requestingClientId);
    }

    public void handleRelayAccept(String from, JsonObject data) {
        RoomManager.RoomState state = roomManager.currentRoom.get();
        if (state == null || state == RoomManager.PENDING) return;
        String forClientId = data.has("forClientId") ? data.get("forClientId").getAsString() : null;
        if (forClientId != null) {
            JsonObject notify = new JsonObject();
            notify.addProperty("connected", true);
            signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), true, "relay_notify", notify, forClientId);
        }
    }

    public void handleRelayNotify(String from, JsonObject data) {
        RoomManager.RoomState state = roomManager.currentRoom.get();
        if (state == null || state == RoomManager.PENDING) return;
        if (data.has("connected") && data.get("connected").getAsBoolean()) {
                VoxLinkMod.LOGGER.info("[Relay] Received relay_notify(connected), relay ready");
            clearRelayTracking();
            connectionWon.set(true);
            state.roomInfo.setConnectionMode(Component.translatable("voxlink.relay.connected_via").withStyle(ChatFormatting.YELLOW));
            state.roomInfo.setUsingRelay(true);
            return;
        }
        String relayIp = data.has("relayIp") ? data.get("relayIp").getAsString() : null;
        int relayPort = data.has("relayPort") ? data.get("relayPort").getAsInt() : 0;
        if (relayIp == null || relayPort <= 0) return;
        VoxLinkMod.LOGGER.info("[Relay] Received relay_notify, punch to Cone {}:{}", relayIp, relayPort);
        state.roomInfo.setConnectionMode(Component.translatable("voxlink.relay.trying"));

        java.util.List<UdpHolePuncher> relayPunchers = new java.util.ArrayList<>();
        java.util.concurrent.atomic.AtomicBoolean relayWon = new java.util.concurrent.atomic.AtomicBoolean(false);
        String fRelayIp = relayIp;
        int fRelayPort = relayPort;

        for (int i = 0; i < RELAY_SOCKET_COUNT; i++) {
            UdpHolePuncher rp = new UdpHolePuncher();
            try { rp.createSocket(); } catch (Exception e) { continue; }
            relayPunchers.add(rp);
            activeHolePunchers.put("relay_to_cone_" + i, rp);
            final int idx = i;
            rp.punch(fRelayIp, fRelayPort)
                .orTimeout(RELAY_SETUP_TIMEOUT_SEC, TimeUnit.SECONDS)
                .thenAccept(result -> {
                    if (!result.isSuccess()) {
                        PunchFailureClassifier.FailureReason reason = PunchFailureClassifier.classify(result);
                        lastPunchResult = result.withReason(reason);
                        PunchTuner.nextParams(PunchProfile.current(), localNatClass, remoteNatClass, 1, MAX_CONNECTION_CYCLES, reason, lastPunchResult);
                        VoxLinkMod.LOGGER.info("[ConnectionManager] Punch failed reason={} recvPunch={} recvAck={}", reason, result.socketsReceivedPunch, result.socketsReceivedAck);
                        return;
                    }
                    DatagramSocket socket = result.getSuccessSocket();
                    if (!relayWon.compareAndSet(false, true)) {
                        try { rp.close(); } catch (Exception ignored) {}
                        return;
                    }
                    if (!connectionWon.compareAndSet(false, true)) {
                        try { rp.close(); } catch (Exception ignored) {}
                        return;
                    }
                    VoxLinkMod.LOGGER.info("[Relay] Sym->Cone socket#{} punch success", idx);
                    rp.markSocketTransferred();
                    stopAllPunchingAfterHostBridge();
                    rp.stopPunch();
                    for (UdpHolePuncher op : relayPunchers) {
                        if (op != rp) { try { op.cancel(); op.close(); } catch (Exception ignored) {} }
                    }
                    ReliableUdpTransport transport = new ReliableUdpTransport(socket, new java.net.InetSocketAddress(fRelayIp, fRelayPort));
                    activeUdpTransports.put("relay_cone", transport);
                    transport.start();
                    state.roomInfo.setConnectionMode(Component.translatable("voxlink.relay.connected_via"));
                    state.roomInfo.setUsingRelay(true);
                    startUdpPunchBridge(state, transport);
                    scheduler.schedule(() -> {
                        Minecraft mc = Minecraft.getInstance();
                        if (mc.player != null) {
                            mc.player.sendSystemMessage(
                                Component.translatable("voxlink.relay.connected_via"));
                        }
                    }, AWAIT_TERM_SEC, TimeUnit.SECONDS);
                })
                .exceptionally(e -> {
                    if (!relayWon.get()) {
                        try { rp.close(); } catch (Exception ignored) {}
                        activeHolePunchers.remove("relay_to_cone_" + idx);
                    }
                    return null;
                });
        }

        //优化: relay超时18s→8s, 与host侧并行failover对齐
        scheduler.schedule(() -> {
            if (!relayWon.get() && !connectionWon.get()) {
                VoxLinkMod.LOGGER.warn("[Relay] Sym->Cone relay punch timeout (8s)");
                for (UdpHolePuncher op : relayPunchers) {
                    try { op.cancel(); op.close(); } catch (Exception ignored) {}
                }
                activeHolePunchers.entrySet().removeIf(e -> e.getKey().startsWith("relay_to_cone_"));
                showConnectFailed(state);
            }
        }, SHORT_TIMEOUT_SEC, TimeUnit.SECONDS);
    }

    public void handleRelayDeclined(String from, JsonObject data) {
        clearRelayTracking();
        RoomManager.RoomState state = roomManager.currentRoom.get();
        if (state != null && state != RoomManager.PENDING) {
            showConnectFailed(state);
        }
    }

    public void handleRelaySetup(String from, JsonObject data) {
        if (!VoxLinkMod.getConfig().isRelayEnabled()) {
            var state = roomManager.currentRoom.get();
            if (state == null || state == RoomManager.PENDING) return;
            signalingClient.sendSignal(state.roomInfo.getCode(),
                    state.roomInfo.getToken(), false, "relay_declined", new JsonObject(), from);
            return;
        }
        var state0 = roomManager.currentRoom.get();
        if (state0 == null || state0 == RoomManager.PENDING) return;
        String targetClientId = data.has("targetClientId") ? data.get("targetClientId").getAsString() : null;
        String targetIp = data.has("targetIp") ? data.get("targetIp").getAsString() : null;
        int targetPort = data.has("targetPort") ? data.get("targetPort").getAsInt() : 0;
        if (targetIp == null || targetPort <= 0) return;
        RoomManager.RoomState state = roomManager.currentRoom.get();
        ReliableUdpTransport hostTransport = activeUdpTransports.get("joiner");
        if (hostTransport == null || !hostTransport.isConnected()) {
            for (var entry : activeUdpTransports.entrySet()) {
                if (entry.getValue().isConnected()) { hostTransport = entry.getValue(); break; }
            }
        }
        if (hostTransport == null) return;

        java.util.List<UdpHolePuncher> conePunchers = new java.util.ArrayList<>();
        java.util.concurrent.atomic.AtomicBoolean coneWon = new java.util.concurrent.atomic.AtomicBoolean(false);
        String fTargetIp = targetIp;
        int fTargetPort = targetPort;
        String fTargetClientId = targetClientId;
        final ReliableUdpTransport fHostTransport = hostTransport;

        for (int i = 0; i < RELAY_SOCKET_COUNT; i++) {
            UdpHolePuncher cp = new UdpHolePuncher();
            try { cp.createSocket(); } catch (Exception e) { continue; }
            conePunchers.add(cp);
            activeHolePunchers.put("relay_to_sym_" + i, cp);
            final int idx = i;
            cp.punchWithPortPrediction(fTargetIp, fTargetPort, 10)
                .orTimeout(RELAY_SETUP_TIMEOUT_SEC, TimeUnit.SECONDS)
                .thenAccept(result -> {
                    if (!result.isSuccess()) {
                        PunchFailureClassifier.FailureReason reason = PunchFailureClassifier.classify(result);
                        lastPunchResult = result.withReason(reason);
                        PunchTuner.nextParams(PunchProfile.current(), localNatClass, remoteNatClass, 1, MAX_CONNECTION_CYCLES, reason, lastPunchResult);
                        VoxLinkMod.LOGGER.info("[ConnectionManager] Punch failed reason={} recvPunch={} recvAck={}", reason, result.socketsReceivedPunch, result.socketsReceivedAck);
                        return;
                    }
                    DatagramSocket socket = result.getSuccessSocket();
                    if (!coneWon.compareAndSet(false, true)) {
                        try { cp.close(); } catch (Exception ignored) {}
                        return;
                    }
                    VoxLinkMod.LOGGER.info("[Relay] Cone->Sym socket#{} punch success", idx);
                    cp.markSocketTransferred();
                    for (UdpHolePuncher op : conePunchers) {
                        if (op != cp) { try { op.cancel(); op.close(); } catch (Exception ignored) {} }
                    }
                    ReliableUdpTransport peerTransport = new ReliableUdpTransport(socket, new java.net.InetSocketAddress(fTargetIp, fTargetPort));
                    peerTransport.start();
                    activeUdpTransports.put(fTargetClientId != null ? fTargetClientId : "sym_relayed", peerTransport);
                    RelayBridge.getInstance(scheduler).startRelay("host", fTargetClientId != null ? fTargetClientId : "sym", fHostTransport, peerTransport);
                    JsonObject reply = new JsonObject();
                    reply.addProperty("forClientId", fTargetClientId != null ? fTargetClientId : "sym");
                    signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), false, "relay_accept", reply, "host");
                })
                .exceptionally(e -> {
                    if (!coneWon.get()) {
                        try { cp.close(); } catch (Exception ignored) {}
                        activeHolePunchers.remove("relay_to_sym_" + idx);
                    }
                    return null;
                });
        }

        scheduler.schedule(() -> {
            if (!coneWon.get()) {
                VoxLinkMod.LOGGER.warn("[Relay] Cone->Sym relay punch timeout (8s)");
                for (UdpHolePuncher op : conePunchers) {
                    try { op.cancel(); op.close(); } catch (Exception ignored) {}
                }
                activeHolePunchers.entrySet().removeIf(e -> e.getKey().startsWith("relay_to_sym_"));
                signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), false, "relay_declined", new JsonObject(), "host");
            }
        }, SHORT_TIMEOUT_SEC, TimeUnit.SECONDS);
    }

    public void onRelayDisconnected(String peerA, String peerB) {
        VoxLinkMod.LOGGER.warn("[Relay] Relay disconnect notify: {}<->{}", peerA, peerB);
        RoomManager.RoomState state = roomManager.currentRoom.get();
        if (state == null || state == RoomManager.PENDING || connectionWon.get()) return;
        if (currentRelayPeer.get() != null) {
            failedRelayPeers.add(currentRelayPeer.get());
        }
        clearRelayTracking();
        scheduler.schedule(() -> {
            if (roomManager.currentRoom.get() == state && !connectionWon.get()) {
                VoxLinkMod.LOGGER.info("[Relay] Auto switch to backup relay...");
                tryRelay(state);
            }
        }, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    public void showConnectFailedFinal(RoomManager.RoomState state) {
        clearRelayTracking();
        failedRelayPeers.clear();
        cancelConnectionCycleSafety();
        //debounce 桥已建/已赢时忽略 防止竞速收尾后残留路径误杀正在握手的连接(远古断连bug根因)
        int bridgePort = (state != null && state.roomInfo != null) ? state.roomInfo.getLocalBridgePort() : -1;
        if (connectionWon.get() || voxlinkWon || terracottaWon || bridgePort > 0) {
            VoxLinkMod.LOGGER.info("[Connection] Bridge established/won, ignore showConnectFailedFinal (connWon={} voxlinkWon={} terracottaWon={} bridgePort={})",
                    connectionWon.get(), voxlinkWon, terracottaWon, bridgePort);
            return;
        }
        //debounce 无限重试规范: 持续重试中一律不显示失败/不退房 仅玩家取消或对端cancel才停
        //注: 对端cancel路径(handleCancelConnection)已先置continuousRetryCancelled=true 不会误伤
        if (continuousRetryRound.get() > 0 && !continuousRetryCancelled.get()) {
            if (state != null && state.roomInfo != null) {
                state.roomInfo.setConnectionMode(Component.translatable("voxlink.connection.retrying"));
            }
            return;
        }
        //debounce 双P2P模式 VoxLink侧失败只记失败状态 不leaveRoom 不影响Terracotta
        if (dualRaceActive && !terracottaWon) {
            if (state != null && state.roomInfo != null) {
                state.roomInfo.setConnectionMode(Component.translatable("voxlink.connection.cannot_establish"));
            }
            voxlinkSideDisabled = true;
            if (dualResultRef != null && dualFailedCount.incrementAndGet() >= 2) {
                dualResultRef.completeExceptionally(new RuntimeException("所有连接方式失败"));
            }
            return;
        }
        if (connectionWon.get()) {
            VoxLinkMod.LOGGER.info("[Connection] Connected, ignore showConnectFailedFinal");
            return;
        }
        connectionCycleActive.set(false);
        connectionWon.set(false);
        ConnectionState.transitionTo(ConnectionState.FAILED, "所有连接方式失败");
        if (connectionTimeoutFuture != null) {
            connectionTimeoutFuture.cancel(false);
            connectionTimeoutFuture = null;
        }
        ConnectionHelper.resetConnecting();
        state.roomInfo.setConnectionMode(Component.translatable("voxlink.connection.all_failed"), true);
        sendDisconnectOnFailure(state);
        P2PBridge.disconnect();
        P2PBridge.cancelPendingUdpTimeouts();
        for (UdpHolePuncher puncher : activeHolePunchers.values()) {
            puncher.cancel();
            puncher.close();
        }
        activeHolePunchers.clear();
        for (ReliableUdpTransport transport : activeUdpTransports.values()) {
            try { transport.close(); } catch (Exception ignored) {}
        }
        activeUdpTransports.clear();
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            mc.execute(() -> {
                if (mc.player != null) {
                    mc.player.sendSystemMessage(
                            Component.translatable("voxlink.chat.error_prefix")
                                    .append(Component.translatable("voxlink.connection.all_failed")));
                    if (state.roomInfo.isSameCgnat()) {
                        mc.player.sendSystemMessage(
                                Component.translatable("voxlink.chat.same_cgnat_warning"));
                    }
                }
                try {
                    scheduler.execute(() -> roomManager.leaveRoom());
                } catch (java.util.concurrent.RejectedExecutionException ex) {
                    VoxLinkMod.LOGGER.warn("Scheduler closed, sync execute leaveRoom");
                    roomManager.leaveRoom();
                }
            });
        }
    }

    public void killAllConnectionAttempts() {
        //debounce 连接已成功时不发cancel 避免对端误判为失败
        boolean alreadyWon = connectionWon.get();
        if (!alreadyWon && !continuousRetryCancelled.getAndSet(true) && continuousRetryRound.get() > 0) {
            sendCancelConnectionSignal();
        } else {
            continuousRetryCancelled.set(true);
        }
        connectionCycleActive.set(false);
        connectionWon.set(true);
        //debounce 连接已成功时不reset状态 避免后续transitionTo报非法状态转换
        if (!alreadyWon) {
            ConnectionState.reset();
        }
        if (connectionTimeoutFuture != null) {
            connectionTimeoutFuture.cancel(false);
            connectionTimeoutFuture = null;
        }
        cancelConnectionCycleSafety();
        for (UdpHolePuncher puncher : activeHolePunchers.values()) {
            try { puncher.cancel(); } catch (Exception ignored) {}
            try { puncher.stopPunch(); } catch (Exception ignored) {}
            try { puncher.close(); } catch (Exception ignored) {}
        }
        activeHolePunchers.clear();
        //debounce 统一清UDP池 避免被杀侧残留recv线程和端口
        for (ReliableUdpTransport t : activeUdpTransports.values()) { try { t.close(); } catch (Exception ignored) {} }
        activeUdpTransports.clear();
        for (ReliableUdpTransport t : oldUdpTransports.values()) { try { t.close(); } catch (Exception ignored) {} }
        oldUdpTransports.clear();
        //debounce 统一停TCP兜底 避免残留重试空打
        cancelAllFallbacks();
        hostPunching = false;
        lastPunchInfoId = "";
    }

    //双P2P: 按reason杀对应通道
    public void killAllConnectionAttempts(String reason) {
        if (reason == null) { killAllConnectionAttempts(); return; }
        switch (reason) {
            case "voxlink" -> {
                killAllConnectionAttempts();
                P2PBridge.disconnect();
            }
            case "terracotta" -> {
                //debounce 同步等setIdle完成 避免旧状态污染下一次join
                try {
                    TerracottaManager.setIdle().orTimeout(3, TimeUnit.SECONDS).join();
                } catch (Exception e) {
                    VoxLinkMod.LOGGER.warn("[DualP2P] Terracotta setIdle timeout: {}", e.getMessage());
                }
                TerracottaManager.clearLastState();
            }
            default -> killAllConnectionAttempts();
        }
        VoxLinkMod.LOGGER.info("[DualP2P] Abort {} side connection attempt", reason);
    }

    //debounce 关屏/取消时杀两侧P2P 让资源立即释放
    public void killDualRace() {
        dualRaceActive = false;
        voxlinkSideDisabled = true;
        killAllConnectionAttempts("voxlink");
        killAllConnectionAttempts("terracotta");
        CompletableFuture<Void> bf = dualVoxlinkBridgeFuture;
        if (bf != null && !bf.isDone()) {
            bf.completeExceptionally(new RuntimeException("用户取消"));
        }
        dualVoxlinkBridgeFuture = null;
        dualResultRef = null;
        dualFailedCount.set(0);
        terracottaWon = false;
        voxlinkWon = false;
    }

    //debounce 双P2P成功后立即重置状态 防止stale状态污染下次连接
    public void resetDualRaceState() {
        dualRaceActive = false;
        terracottaWon = false;
        voxlinkWon = false;
        voxlinkSideDisabled = false;
        dualFailedCount.set(0);
        dualResultRef = null;
        dualVoxlinkBridgeFuture = null;
    }

    //debounce 阶段四: 重置持续重试状态 leaveRoom时调用 为下次连接准备
    public void resetContinuousRetryState() {
        continuousRetryCancelled.set(false);
        continuousRetryRound.set(0);
        //debounce 清失败历史
        lastFailureReason = null;
        consecutiveFailureCount.set(0);
    }

    //debounce 阶段四: 检测双方是否都支持持续重试且玩家未取消 1.0.7+双方有CAP_CONTINUOUS_RETRY才启用
    private boolean shouldContinuousRetry(RoomManager.RoomState state) {
        if (continuousRetryCancelled.get()) {
            VoxLinkMod.LOGGER.info("[Connection] Player cancelled persistent retry");
            return false;
        }
        //debounce 无限重试 不再有轮次上限 仅玩家取消或对端cancel才停
        if (state == null || state == RoomManager.PENDING || state.roomInfo == null) return false;
        if (state.roomInfo.isHost()) {
            //debounce host视角: 检查所有joiner都支持continuous_retry 老版本joiner无声明视为legacy零能力
            if (state.roomInfo.getPeers().isEmpty()) return false;
            return state.roomInfo.getPeers().stream()
                    .allMatch(icu.wuhui.voxlink.network.ProtocolNegotiator::supportsContinuousRetry);
        } else {
            //debounce joiner视角: 检查host是否支持 老版本host视为legacy零能力
            if (state.roomInfo.isHostLegacy()) return false;
            return state.roomInfo.getHostCapabilities()
                    .contains(icu.wuhui.voxlink.network.ProtocolNegotiator.CAP_CONTINUOUS_RETRY);
        }
    }

    //debounce 每轮升档 DEFAULT→AGGRESSIVE→HARDSYM 到顶后保持 增加极端NAT命中率
    private void escalateProfileForRound(int round) {
        icu.wuhui.voxlink.network.PunchProfile current = PunchProfile.current();
        icu.wuhui.voxlink.network.PunchProfile target;
        if (current == PunchProfile.HARDSYM) {
            return;
        } else if (current == PunchProfile.AGGRESSIVE) {
            target = PunchProfile.HARDSYM;
        } else {
            target = PunchProfile.AGGRESSIVE;
        }
        PunchProfile.switchTo(target, "continuous_retry_round_" + round);
    }

    //debounce 阶段四: 接收对端cancel信号 立即终止持续重试 避免对端等心跳超时
    public void handleCancelConnection(String from, JsonObject data) {
        VoxLinkMod.LOGGER.info("[Connection] Received cancel_connection signal from peer ({}), abort persistent retry", from);
        continuousRetryCancelled.set(true);
        RoomManager.RoomState state = roomManager.currentRoom.get();
        if (state == null || state == RoomManager.PENDING) return;
        showConnectFailedFinal(state);
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null) {
            mc.execute(() -> mc.player.sendSystemMessage(
                    Component.translatable("voxlink.chat.error_prefix")
                            .append(Component.translatable("voxlink.connection.peer_cancelled"))));
        }
    }

    //debounce 阶段四: 主动给对端发cancel信号 让对端立即终止重试 老版本对端无影响(不识别该信号类型 忽略)
    private void sendCancelConnectionSignal() {
        try {
            RoomManager.RoomState state = roomManager.currentRoom.get();
            if (state == null || state == RoomManager.PENDING || state.roomInfo == null) return;
            String target = state.roomInfo.isHost() ? "all" : "host";
            signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(),
                    false, "cancel_connection", new JsonObject(), target);
            VoxLinkMod.LOGGER.info("[Connection] Sent cancel_connection signal to peer ({})", target);
        } catch (Exception e) {
            VoxLinkMod.LOGGER.warn("[Connection] Send cancel_connection signal failed: {}", e.getMessage());
        }
    }

    //debounce 阶段三: 重置ICE Restart状态 leaveRoom时调用
    public void resetIceRestartState() {
        iceRestartAttempts.set(0);
        lastIceRestartTimeMs.set(0);
        savedConnectionState = null;
    }

    //debounce 阶段三: 信令通道收到对端ice_restart信号 1.0.7+双方有CAP_ICE_RESTART才发送 老版本不会发
    public void handleIceRestart(String from, JsonObject data) {
        VoxLinkMod.LOGGER.info("[Connection] Received ice_restart signal from peer ({})", from);
        RoomManager.RoomState state = roomManager.currentRoom.get();
        if (state == null || state == RoomManager.PENDING || state.roomInfo == null) {
            VoxLinkMod.LOGGER.info("[Connection] Received ice_restart but left room, ignore");
            return;
        }
        performIceRestart(state, "收到对端ice_restart信号");
    }

    //debounce 阶段三: 传输层断开时触发(ReliableUdpTransport回调) 双方支持才启用
    public void triggerIceRestart() {
        RoomManager.RoomState state = roomManager.currentRoom.get();
        if (state == null || state == RoomManager.PENDING || state.roomInfo == null) {
            VoxLinkMod.LOGGER.info("[Connection] Transport disconnected but left room, no ICE Restart");
            return;
        }
        //debounce 检查对端是否支持ice_restart 老版本对端无能力声明走原close逻辑
        if (!shouldIceRestart(state)) {
            VoxLinkMod.LOGGER.info("[Connection] Peer does not support ice_restart, go original disconnect logic");
            return;
        }
        performIceRestart(state, "传输层断开");
        //debounce 通知对端也重启 双通道并行提高成功率
        sendIceRestartSignal(state);
    }

    //debounce 阶段三: 检测双方是否都支持ICE Restart 1.0.7+双方有CAP_ICE_RESTART才启用
    private boolean shouldIceRestart(RoomManager.RoomState state) {
        if (state == null || state == RoomManager.PENDING || state.roomInfo == null) return false;
        if (state.roomInfo.isHost()) {
            if (state.roomInfo.getPeers().isEmpty()) return false;
            return state.roomInfo.getPeers().stream()
                    .allMatch(icu.wuhui.voxlink.network.ProtocolNegotiator::supportsIceRestart);
        } else {
            if (state.roomInfo.isHostLegacy()) return false;
            return state.roomInfo.getHostCapabilities()
                    .contains(icu.wuhui.voxlink.network.ProtocolNegotiator.CAP_ICE_RESTART);
        }
    }

    //debounce 阶段三: 核心逻辑 重置连接状态+重新触发runConnectionCycle 防抖3次上限5秒间隔
    private void performIceRestart(RoomManager.RoomState state, String reason) {
        //debounce 防抖: 5秒内不重复触发 避免传输层断开+信令通道信号同时到达导致重复
        long now = System.currentTimeMillis();
        long last = lastIceRestartTimeMs.get();
        if (now - last < ICE_RESTART_COOLDOWN_MS) {
            VoxLinkMod.LOGGER.info("[Connection] ICE Restart cooldown (within {}ms), ignore trigger ({})", ICE_RESTART_COOLDOWN_MS, reason);
            return;
        }
        //debounce 上限: 3次重启都失败走放弃 避免无限循环
        int attempt = iceRestartAttempts.incrementAndGet();
        if (attempt > ICE_RESTART_MAX_ATTEMPTS) {
            VoxLinkMod.LOGGER.warn("[Connection] ICE Restart reached max {}, give up", ICE_RESTART_MAX_ATTEMPTS);
            return;
        }
        lastIceRestartTimeMs.set(now);
        VoxLinkMod.LOGGER.info("[Connection] ICE Restart trigger ({}/{}): {}", attempt, ICE_RESTART_MAX_ATTEMPTS, reason);

        //debounce 重置连接状态 准备新一轮打洞
        connectionWon.set(false);
        connectionCycleActive.set(false);
        reversePunchAttempted.set(false);
        dualRaceActive = false;
        terracottaWon = false;
        voxlinkWon = false;
        voxlinkSideDisabled = false;
        dualVoxlinkBridgeFuture = null;
        if (connectionTimeoutFuture != null) {
            connectionTimeoutFuture.cancel(false);
            connectionTimeoutFuture = null;
        }
        stopAllConnectionWork();
        clearRelayTracking();
        ConnectionState.transitionTo(ConnectionState.STUN_PROBE, "ICE Restart " + attempt + "/" + ICE_RESTART_MAX_ATTEMPTS);

        //debounce 通知用户
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null) {
            mc.execute(() -> mc.player.sendSystemMessage(
                    Component.translatable("voxlink.chat.error_prefix")
                            .append(Component.translatable("voxlink.connection.ice_restart"))));
        }

        //debounce 用保存的参数重新触发连接 cycle=0从头开始
        RoomManager.RoomState savedState = savedConnectionState;
        if (savedState == null || savedState != state || savedConnectionHostIp == null) {
            VoxLinkMod.LOGGER.warn("[Connection] ICE Restart no saved params, cannot re-trigger");
            return;
        }
        connectionStartTimeMs = System.currentTimeMillis();
        int timeoutSec = CONNECTION_TIMEOUT_SECONDS;
        connectionTimeoutSec = timeoutSec;
        state.roomInfo.setConnectionMode(Component.translatable("voxlink.connection.connecting"));
        scheduleConnectionTimeout(state, timeoutSec);
        //debounce 异步触发避免阻塞回调线程(传输层断开时可能在retransmit线程)
        scheduler.schedule(() -> {
            if (roomManager.currentRoom.get() != savedState) return;
            runConnectionCycle(savedState, savedConnectionFrom, savedConnectionHostIpv6,
                    savedConnectionHostIp, savedConnectionHostPort, savedConnectionHostMappedIp,
                    savedConnectionHostMappedPort, 0);
        }, 500, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    //debounce 阶段三: 主动给对端发ice_restart信号 让对端也重新打洞 老版本对端不识别该信号忽略
    private void sendIceRestartSignal(RoomManager.RoomState state) {
        try {
            if (state.roomInfo == null) return;
            String target = state.roomInfo.isHost() ? "all" : "host";
            signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(),
                    false, "ice_restart", new JsonObject(), target);
            VoxLinkMod.LOGGER.info("[Connection] Sent ice_restart signal to peer ({})", target);
        } catch (Exception e) {
            VoxLinkMod.LOGGER.warn("[Connection] Send ice_restart signal failed: {}", e.getMessage());
        }
    }

    //debounce 通知双P2P的VoxLink桥建立结果 joiner桥建好/失败时调用
    public void notifyDualVoxlinkBridge(boolean success) {
        CompletableFuture<Void> f = dualVoxlinkBridgeFuture;
        if (f == null) return;
        if (success) {
            f.complete(null);
        } else {
            f.completeExceptionally(new RuntimeException("VoxLink桥建立失败"));
        }
    }

    //双P2P编排: 房间码路由 + 并行竞速
    public CompletableFuture<Void> startDualP2P(String roomCode, String playerName, String password,
                                                    java.util.function.BiConsumer<String, String> statusCallback) {
        //debounce 入口重置上次残留状态 防止双P2P失败后dualRaceActive/voxlinkSideDisabled残留
        //污染后续VoxLink-only连接(connectViaBridge误杀陶瓦侧/handleHolePunchOffer忽略offer)
        resetDualRaceState();
        VoxLinkMod.LOGGER.info("[DualP2P] startDualP2P roomCode={} parallel={} isTerracotta={} isVoxLink={}",
                roomCode, VoxLinkMod.getConfig().isParallelP2P(),
                RoomCodeRouter.isTerracottaCode(roomCode), RoomCodeRouter.isVoxLinkCode(roomCode));
        if (RoomCodeRouter.isTerracottaCode(roomCode)) {
            //U/ 前缀 -> 仅 Terracotta 等待guest-ok后连接MC
            VoxLinkMod.LOGGER.info("[DualP2P] Go pure Terracotta path");
            //debounce 前置杀残留陶瓦 防上次加入残留导致本次失败
            try { TerracottaManager.shutdown(); } catch (Exception e) { VoxLinkMod.LOGGER.debug("pre-cleanup terracotta error: {}", e.getMessage()); }
            statusCallback.accept("terracotta", "voxlink.attempting_join.joining");
            return TerracottaManager.joinRoom(roomCode, playerName)
                .thenAccept(connectUrl -> {
                    //debounce 陶瓦guest-ok成功 用connectUrl连接MC
                    connectTerracottaToMC(connectUrl, roomCode);
                    statusCallback.accept("terracotta", "voxlink.connection.bridge_setup");
                });
        }
        if (!RoomCodeRouter.isVoxLinkCode(roomCode)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                Component.translatable("voxlink.error.invalid_room_code").getString()));
        }
        //6位码 -> VoxLink P2P
        if (!VoxLinkMod.getConfig().isParallelP2P()) {
            //仅 VoxLink P2P
            statusCallback.accept("voxlink", "voxlink.connection.joining");
            return startVoxLinkP2P(roomCode, password);
        }
        //debounce 等待陶瓦下载完成 未就绪则降级VoxLink-only
        return TerracottaManager.waitForDownload().thenCompose(ready -> {
            VoxLinkMod.LOGGER.info("[DualP2P] waitForDownload returned ready={} binaryReady={}", ready, TerracottaBinary.isReady());
            if (!ready) {
                statusCallback.accept("voxlink", "voxlink.connection.joining");
                return startVoxLinkP2P(roomCode, password);
            }
            return runDualP2PRace(roomCode, playerName, password, statusCallback);
        });
    }

    //debounce 双P2P竞速主体 从startDualP2P抽出 便于下载等待后调用
    private CompletableFuture<Void> runDualP2PRace(String roomCode, String playerName, String password,
                                                       java.util.function.BiConsumer<String, String> statusCallback) {
        VoxLinkMod.LOGGER.info("[DualP2P] Dual P2P race started roomCode={}", roomCode);
        //双P2P并行
        statusCallback.accept("voxlink", "voxlink.connection.joining");
        statusCallback.accept("terracotta", "voxlink.attempting_join.joining");
        //CAS守卫
        java.util.concurrent.atomic.AtomicBoolean won = new java.util.concurrent.atomic.AtomicBoolean(false);
        //debounce 用类字段记录dualResult和failed计数 让showConnectFailedFinal能感知双P2P
        dualRaceActive = true;
        terracottaWon = false;
        voxlinkWon = false;
        voxlinkSideDisabled = false;
        dualFailedCount.set(0);
        CompletableFuture<Void> dualResult = new CompletableFuture<>();
        dualResultRef = dualResult;
        //debounce VoxLink侧等到桥建立才算赢 和陶瓦guest-ok语义对齐
        dualVoxlinkBridgeFuture = new CompletableFuture<>();
        final CompletableFuture<Void> bridgeFuture = dualVoxlinkBridgeFuture;
        scheduler.schedule(() -> {
            //debounce 持续重试中不判VoxLink侧失败 无限重试期间桥迟早会建 120s超时只约束无持续重试场景
            if (!bridgeFuture.isDone() && !isPersistentRetrying()) {
                bridgeFuture.completeExceptionally(new RuntimeException("VoxLink桥建立超时"));
            }
        }, DUAL_VOXLINK_BRIDGE_TIMEOUT_SEC, TimeUnit.SECONDS);
        //debounce joinRoom先完成 获取陶瓦房间号 后并行: VoxLink打洞 + 陶瓦join
        CompletableFuture<Void> joinFuture = startVoxLinkP2P(roomCode, password);
        //debounce joinRoom失败时更新UI状态 防止永久卡在"正在探测网络环境..."
        joinFuture.whenComplete((v, joinErr) -> {
            if (joinErr != null) {
                VoxLinkMod.LOGGER.warn("[DualP2P] VoxLink joinRoom failed: {}", joinErr.getMessage());
                statusCallback.accept("voxlink", "voxlink.dual.channel_failed");
            }
        });
        joinFuture
            .thenCompose(v -> bridgeFuture)
            .whenComplete((r, e) -> {
                dualVoxlinkBridgeFuture = null;
                if (e == null) {
                    if (won.compareAndSet(false, true)) {
                            voxlinkWon = true;
                            killAllConnectionAttempts("terracotta");
                            statusCallback.accept("voxlink", "voxlink.dual.p2p_established");
                            statusCallback.accept("terracotta", "voxlink.dual.status_cancelled");
                            dualResult.complete(null);
                            resetDualRaceState();
                        } else {
                            voxlinkSideDisabled = true;
                            killAllConnectionAttempts("voxlink");
                            P2PBridge.disconnect();
                            statusCallback.accept("voxlink", "voxlink.dual.status_cancelled");
                        }
                } else if (!won.get()) {
                    //debounce 无限重试规范: 持续重试中VoxLink侧不判失败 不设禁用标志 等无限重试继续
                    if (isPersistentRetrying()) {
                        VoxLinkMod.LOGGER.info("[DualP2P] VoxLink bridge failed but persistent retrying, keep retrying (round={})", continuousRetryRound.get());
                        return;
                    }
                    statusCallback.accept("voxlink", "voxlink.dual.channel_failed");
                    //debounce VoxLink侧失败 设禁用标志 防止handleHolePunchOffer重启
                    voxlinkSideDisabled = true;
                    if (dualFailedCount.incrementAndGet() >= 2) dualResult.completeExceptionally(e);
                }
            });
        //debounce 陶瓦侧: joinRoom完成后用陶瓦房间号(非VoxLink码)join陶瓦
        joinFuture
            .thenCompose(v -> {
                RoomInfo ri = roomManager.getCurrentRoom();
                String tc = ri != null ? ri.getTerracottaCode() : null;
                VoxLinkMod.LOGGER.info("[DualP2P] Terracotta branch triggered terracottaCode={} binaryReady={}", tc, TerracottaBinary.isReady());
                if (tc == null || tc.isEmpty()) {
                    return CompletableFuture.failedFuture(new RuntimeException("host未上传陶瓦房间号"));
                }
                return TerracottaManager.joinRoom(tc, playerName);
            })
            .whenComplete((connectUrl, e) -> {
                if (e == null) {
                    if (won.compareAndSet(false, true)) {
                        terracottaWon = true;
                        killAllConnectionAttempts("voxlink");
                        //debounce 立即complete bridgeFuture 让VoxLink链路同步收尾 不再等60s超时
                        voxlinkSideDisabled = true;
                        if (bridgeFuture != null && !bridgeFuture.isDone()) {
                            bridgeFuture.completeExceptionally(new RuntimeException("Terracotta已赢 VoxLink放弃"));
                        }
                        try {
                            //debounce 陶瓦guest-ok成功 用connectUrl连接MC
                            connectTerracottaToMC(connectUrl, roomCode);
                            statusCallback.accept("terracotta", "voxlink.dual.p2p_established");
                            statusCallback.accept("voxlink", "voxlink.dual.status_cancelled");
                            dualResult.complete(null);
                            resetDualRaceState();
                        } catch (Exception ex) {
                            VoxLinkMod.LOGGER.error("[DualP2P] Terracotta connect MC failed: {}", ex.getMessage());
                            statusCallback.accept("terracotta", "voxlink.dual.channel_failed");
                            dualResult.completeExceptionally(ex);
                        }
                    }
                } else if (!won.get()) {
                    //debounce 提取错误详情透传到日志 便于调试
                    Throwable cause = e;
                    while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
                    VoxLinkMod.LOGGER.warn("[DualP2P] Terracotta side failed: {}", cause.getMessage());
                    statusCallback.accept("terracotta", "voxlink.dual.channel_failed");
                    if (dualFailedCount.incrementAndGet() >= 2) dualResult.completeExceptionally(e);
                }
            });
        return dualResult;
    }

    //debounce 解析陶瓦connectUrl端口并连接MC
    private void connectTerracottaToMC(String connectUrl, String roomCode) {
        if (connectUrl == null || connectUrl.isEmpty()) {
            throw new RuntimeException("陶瓦connectUrl为空 无法连接MC");
        }
        int localPort = parsePortFromUrl(connectUrl);
        if (localPort <= 0) {
            throw new RuntimeException("陶瓦connectUrl解析端口失败: " + connectUrl);
        }
        RoomInfo roomInfo = roomManager.getCurrentRoom();
        if (roomInfo == null) {
            roomInfo = roomManager.setupTerracottaGuestRoom(roomCode);
        }
        //debounce 推TRANSPORT_SETUP 等MC真连上后再推CONNECTED 避免握手失败时非法状态转换
        ConnectionState.transitionTo(ConnectionState.TRANSPORT_SETUP, "陶瓦guest-ok port=" + localPort);
        roomInfo.setConnectionMode(Component.translatable("voxlink.connection.bridge_setup"));
        ConnectionHelper.connectToServer(localPort, roomInfo);
        VoxLinkMod.LOGGER.info("[DualP2P] Terracotta connect MC port={}", localPort);
    }

    private static int parsePortFromUrl(String url) {
        if (url == null) return -1;
        try {
            //debounce 用URI解析 兼容IPv6和无端口场景
            java.net.URI u = java.net.URI.create(url.contains("://") ? url : "tcp://" + url);
            int port = u.getPort();
            if (port > 0) return port;
            //debounce 无端口=MC默认端口 Terracotta 0.4.x行为
            return 25565;
        } catch (Exception e) {
            return -1;
        }
    }

    //复用现有 joinRoom
    private CompletableFuture<Void> startVoxLinkP2P(String roomCode, String password) {
        return roomManager.joinRoom(roomCode, password).thenAccept(r -> {});
    }

    public void sendDisconnectOnFailure(RoomManager.RoomState state) {
        try {
            signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(),
                    false, "disconnect", new JsonObject(), "host");
        } catch (Exception e) {
            VoxLinkMod.LOGGER.debug("Send disconnect failed on connection failure: {}", e.getMessage());
        }
    }

    public void startUdpPunchBridge(RoomManager.RoomState state, ReliableUdpTransport transport) {
        if (terracottaWon || voxlinkSideDisabled) {
            try { transport.close(); } catch (Exception ignored) {}
            notifyDualVoxlinkBridge(false);
            return;
        }
        int localPort = P2PBridge.startUdpJoinerBridge(transport);
        if (localPort > 0) {
            //debounce joiner桥建立 停所有打洞 不发cancel 不清transport 避免误杀已建桥
            stopAllPunchingAfterHostBridge();
            connectionCycleActive.set(false);
            ConnectionState.transitionTo(ConnectionState.CONNECTED, "Joiner桥接建立 port=" + localPort);
            //debounce 桥已建 但MC还没真连上 显示隧道建立中
            state.roomInfo.setConnectionMode(Component.translatable("voxlink.connection.bridge_setup"));
            ConnectionHelper.connectToServer(localPort, state.roomInfo);
            notifyDualVoxlinkBridge(true);
        } else {
            connectionCycleActive.set(false);
            ConnectionHelper.resetConnecting();
            ConnectionState.transitionTo(ConnectionState.FAILED, "桥接启动失败");
            state.roomInfo.setConnectionMode(Component.translatable("voxlink.connection.bridge_start_failed"), true);
            sendDisconnectOnFailure(state);
            notifyDualVoxlinkBridge(false);
            if (!dualRaceActive) {
                scheduler.execute(() -> {
                    if (roomManager.currentRoom.get() == state && state != RoomManager.PENDING) {
                        roomManager.leaveRoom();
                    }
                });
            }
        }
    }

    public void startHostUdpPunchBridge(RoomManager.RoomState state, String clientId, ReliableUdpTransport transport) {
        int mcPort = state.roomInfo.getHostPort();
        ConnectionState.transitionTo(ConnectionState.CONNECTED, "Host桥接建立 client=" + clientId);
        state.roomInfo.setConnectionMode(Component.translatable("voxlink.connection.connected"));
        stopAllPunchingAfterHostBridge();
        P2PBridge.startUdpHostBridgeForClient(clientId, transport, mcPort, () -> {
            ReliableUdpTransport t = activeUdpTransports.remove(clientId);
            if (t != null) {
                try { t.close(); } catch (Exception ignored) {}
            }
            //debounce 桥断开(transport stuck/对端离开)后重置连接状态 让主机能响应joiner重连的新punch_info
            if (roomManager.currentRoom.get() == state && state != RoomManager.PENDING) {
                VoxLinkMod.LOGGER.warn("[HostBridge] client={} bridge disconnected, reset connectionWon for host re-punch", clientId);
                connectionWon.set(false);
                connectionCycleActive.set(false);
                hostPunching = false;
                lastPunchInfoId = "";
                ConnectionState.transitionTo(ConnectionState.IDLE, "Host桥断开 client=" + clientId);
                state.roomInfo.setConnectionMode(Component.translatable("voxlink.connection.punching"));
            }
        });
    }

    public void putTransportWithIcePool(String key, ReliableUdpTransport transport) {
        ReliableUdpTransport old = activeUdpTransports.put(key, transport);
        if (old != null) {
            String oldKey = key + "_old";
            oldUdpTransports.put(oldKey, old);
            scheduler.schedule(() -> {
                ReliableUdpTransport t = oldUdpTransports.remove(oldKey);
                if (t != null) try { t.close(); } catch (Exception ignored) {}
            }, ICE_POOL_RETAIN_SECONDS, TimeUnit.SECONDS);
        }
    }

    public void sendConfirmPackets(DatagramSocket socket, InetSocketAddress addr) {
        try {
            byte[] data = new byte[]{0x56, 0x4C, PUNCH_ACK_TYPE};
            java.net.DatagramPacket pkt = new java.net.DatagramPacket(data, data.length, addr.getAddress(), addr.getPort());
            socket.send(pkt); socket.send(pkt); socket.send(pkt);
        } catch (Exception e) {
            VoxLinkMod.LOGGER.warn("[Transport] Ack packet send failed: {}", e.getMessage());
        }
    }

    //打洞成功→建transport→桥接
    public void establishUdpTransport(RoomManager.RoomState state, DatagramSocket socket, UdpHolePuncher puncher,
                                       InetSocketAddress fallbackAddr, String transportKey,
                                       boolean isHost, String clientId) throws Exception {
        puncher.waitForRecvThreadExit();
        InetSocketAddress remoteAddr = puncher.getActualRemoteAddress();
        if (remoteAddr == null) {
            remoteAddr = fallbackAddr;
        }
        sendConfirmPackets(socket, remoteAddr);
        ReliableUdpTransport transport = new ReliableUdpTransport(socket, remoteAddr);
        //debounce 阶段三: 注册ICE Restart回调 传输层断开时触发重新打洞
        transport.setOnIceRestartRequested(this::triggerIceRestart);
        putTransportWithIcePool(transportKey, transport);
        if (!isHost) {
            connectionCycleActive.set(false);
            ConnectionHelper.resetConnecting();
        }
        transport.start();
        if (isHost) {
            ConnectionState.transitionTo(ConnectionState.TRANSPORT_SETUP, "Host ReliableUdp启动 client=" + clientId);
            //优化: 去掉sleep(500), transport.start()已同步启动recvThread, 无需等待
            startHostUdpPunchBridge(state, clientId, transport);
        } else {
            ConnectionState.transitionTo(ConnectionState.TRANSPORT_SETUP, "Joiner ReliableUdp启动");
            //优化: 去掉sleep(300), transport.start()已同步启动recvThread, 无需等待
            signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(),
                    false, "connected", new JsonObject(), "host");
            startUdpPunchBridge(state, transport);
        }
    }

    public void shutdown() {
        //debounce 取消挂起的connectionTimeoutFuture 防止scheduler关闭前最后时刻触发
        if (connectionTimeoutFuture != null) {
            connectionTimeoutFuture.cancel(false);
            connectionTimeoutFuture = null;
        }
        //debounce 关闭cachedUdpArray 防止dev热加载累积socket泄漏
        if (cachedUdpArray != null) {
            try { cachedUdpArray.close(); } catch (Exception ignored) {}
            cachedUdpArray = null;
        }
        stopAllConnectionWork();
        if (punchExecutor != null && !punchExecutor.isShutdown()) {
            punchExecutor.shutdown();
            try { punchExecutor.awaitTermination(AWAIT_TERM_SEC, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        }
        clearRelayTracking();
        failedRelayPeers.clear();
    }
}
