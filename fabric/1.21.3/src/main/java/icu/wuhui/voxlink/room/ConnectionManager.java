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
import icu.wuhui.voxlink.network.ReliableUdpTransport;
import icu.wuhui.voxlink.terracotta.RoomCodeRouter;
import icu.wuhui.voxlink.terracotta.TerracottaManager;
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
    private static final int SOCKET_STAGGER_MS = 80;
    private static final int EXTRA_TIMEOUT_SEC = 5;
    private static final int AWAIT_TIMEOUT_SEC = 20;
    private static final int RELAY_GRACE_MS = 3000;
    private static final int PORT_RANGE_WIDE = 50;
    private static final int MAX_DELAY_MS = 10000;
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
    private static final int REVERSE_PUNCH_TIMEOUT_SEC = 30;
    private static final int STUN_PROBE_TIMEOUT_SEC = 10;
    private static final int RTT_SYNC_MAX_DELAY_MS = 15000;
    private static final byte PUNCH_ACK_TYPE = 0x02;
    private final java.util.Set<String> failedRelayPeers = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final AtomicReference<String> currentRelayPeer = new AtomicReference<>(null);
    private volatile ScheduledFuture<?> relayFailoverTask = null;
    private final AtomicBoolean connectionCycleActive = new AtomicBoolean(false);
    private final AtomicBoolean reversePunchAttempted = new AtomicBoolean(false);
    private final AtomicBoolean connectionWon = new AtomicBoolean(false);
    private static final int CONNECTION_TIMEOUT_SECONDS = 60;
    private static final int SYMMETRIC_CONNECTION_TIMEOUT_SECONDS = 90;
    private volatile ScheduledFuture<?> connectionTimeoutFuture;
    private volatile long connectionStartTimeMs;
    private volatile int connectionTimeoutSec;
    private volatile StunProbe.ProbeResult stunProbeResult;
    private final AtomicReference<CompletableFuture<StunProbe.ProbeResult>> stunProbeFutureRef = new AtomicReference<>();
    private volatile String lastPunchInfoId = "";
    private volatile boolean hostPunching = false;

    private static final int MAX_CONNECTION_CYCLES = 8;
    private static final int FALLBACK_CYCLES = 3;
    private static final int SYMMETRIC_NAT_CYCLES = 2;
    private static final int UDP_PUNCH_TIMEOUT_S = 8;
    private static final int CYCLE_RETRY_DELAY_MS = 1000;
    //指数退避
    private static final long[] BACKOFF_DELAYS_MS = {
        1000, 2000, 4000, 8000, 16000, 32000, 48000, 64000
    };
    private static final int UDP_PUNCH_MAX_ATTEMPTS = 3;
    private static final int UDP_PUNCH_RETRY_DELAY_MS = 1500;
    private static final int BIRTHDAY_SOCKET_COUNT = 32;
    private static final int HARD_SYM_SOCKET_COUNT = 84;

    // 修复9: 对称NAT打洞黑名单 + 全局串行锁, 对齐EasyTier BLACKLIST_TIMEOUT_SEC/SYM_PUNCH_LOCK
    // 失败对端加入黑名单1小时内不再尝试UDP打洞, 多对端同时打洞串行化避免资源竞争
    private final java.util.Map<String, Long> natPunchBlacklist = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long BLACKLIST_TIMEOUT_MS = 3600_000L; // 1小时
    private final Object symPunchLock = new Object();
    //黑名单: InetSocketAddress级, UDP3连失败1h, 直连失败5min
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
    private UdpSocketArray getOrCreateUdpArray(int requiredSize, boolean isEasySym, String stunUrl) {
        long now = System.currentTimeMillis();
        if (cachedUdpArray != null) {
            if (cachedUdpArray.isReusable(requiredSize, isEasySym, now)) {
                VoxLinkMod.LOGGER.info("[BirthdayPunch] 复用cached socket数组: {}个socket, age={}ms",
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
                    VoxLinkMod.LOGGER.warn("[BirthdayPunch] 创建socket #{} 失败: {}", idx, e.getMessage());
                    return null;
                }
                try {
                    StunProbe.PublicMappedAddress addr = puncher.discoverMappedAddress(java.util.List.of(stunUrl));
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
        //优化: allOf(15s)→early success, 凑够min(32, requiredSize)个就返回, 5s截止
        //joiner侧birthday attack启动提速: 不再被最慢的socket拖住
        int minRequired = Math.min(BIRTHDAY_SOCKET_COUNT, requiredSize);
        long deadline = System.currentTimeMillis() + TCP_CONNECT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            int done = 0, success = 0;
            for (CompletableFuture<Object[]> f : futures) {
                if (f.isDone()) {
                    done++;
                    try {
                        Object[] r = f.getNow(null);
                        if (r != null) success++;
                    } catch (Exception ignored) {}
                }
            }
            if (success >= minRequired || done == futures.size()) break;
            try { Thread.sleep(SOCKET_STAGGER_MS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
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
        VoxLinkMod.LOGGER.info("[BirthdayPunch] 创建新socket数组: {}个socket, easySym={}",
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

    public void clearActiveUdpTransports() {
        for (ReliableUdpTransport t : activeUdpTransports.values()) {
            try { t.close(); } catch (Exception e) { VoxLinkMod.LOGGER.debug("cleanup udp transport close error: {}", e.getMessage()); }
        }
        activeUdpTransports.clear();
    }

    public void stopPunchersAndTransports() {
        clearActiveHolePunchers();
        clearActiveUdpTransports();
    }

    // 修复9: 黑名单检查, 对齐EasyTier BLACKLIST_TIMEOUT_SEC=3600
    public boolean isPeerBlacklisted(String peerId) {
        if (peerId == null) return false;
        Long ts = natPunchBlacklist.get(peerId);
        return ts != null && System.currentTimeMillis() - ts < BLACKLIST_TIMEOUT_MS;
    }

    public void addPeerToBlacklist(String peerId) {
        if (peerId == null) return;
        natPunchBlacklist.put(peerId, System.currentTimeMillis());
        VoxLinkMod.LOGGER.info("[Connection] 对端{}加入打洞黑名单(1小时)", peerId);
    }

    public void clearPeerBlacklist(String peerId) {
        if (peerId != null) natPunchBlacklist.remove(peerId);
    }

    //地址黑名单
    public AddressBlacklist getAddressBlacklist() { return addressBlacklist; }

    public void clearAddressBlacklist() { addressBlacklist.clear(); }

    // 修复9: 对称NAT打洞串行锁, 对齐EasyTier SYM_PUNCH_LOCK
    // 多对端同时打洞时资源竞争, 串行化避免84 socket并发创建耗尽资源
    public Object getSymPunchLock() {
        return symPunchLock;
    }

    public void stopAllConnectionWork() {
        for (UdpHolePuncher puncher : activeHolePunchers.values()) {
            puncher.cancel();
            puncher.close();
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
        VoxLinkMod.LOGGER.info("[RoomManager] 收到join_request from {}, state={}", from, state != null && state != RoomManager.PENDING ? "active" : "null/pending");
        if (state == null || state == RoomManager.PENDING || !state.roomInfo.isHost()) return;

        if (hostPunching || activeHolePunchers.containsKey("host")) {
            if (retryCount >= 3) {
                VoxLinkMod.LOGGER.warn("[RoomManager] join_request重试次数用完，丢弃 {}", from);
                return;
            }
            if (activeUdpTransports.containsKey(from)) {
                VoxLinkMod.LOGGER.info("[RoomManager] 客户端{}已有活跃transport，忽略重复join_request", from);
                return;
            }
            VoxLinkMod.LOGGER.info("[RoomManager] 正在处理打洞，排队join_request from {} (重试{}/3)", from, retryCount + 1);
            int nextRetry = retryCount + 1;
            scheduler.schedule(() -> {
                RoomManager.RoomState st = roomManager.currentRoom.get();
                if (st != null && st != RoomManager.PENDING && st.roomInfo.isHost() && !hostPunching && !activeHolePunchers.containsKey("host")) {
                    if (activeUdpTransports.containsKey(from)) {
                        VoxLinkMod.LOGGER.info("[RoomManager] 客户端{}已连接，跳过排队重试", from);
                        return;
                    }
                    VoxLinkMod.LOGGER.info("[RoomManager] 重试排队的join_request from {}", from);
                    handleJoinRequest(from, data, nextRetry);
                }
            }, UDP_PUNCH_TIMEOUT_S + EXTRA_TIMEOUT_SEC, TimeUnit.SECONDS);
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
                                VoxLinkMod.LOGGER.info("[handleJoinRequest] API没返回IPv6，用本地IPv6: {}", localIpv6);
                            }
                        }
                        sendHolepunchOffer(st, from);
                    } else {
                        VoxLinkMod.LOGGER.warn("[RoomManager] IP查询期间房间状态变了，用原状态发offer");
                        sendHolepunchOffer(state, from);
                    }
                } else {
                    sendHolepunchOffer(state, from);
                }
            }).exceptionally(e -> {
                VoxLinkMod.LOGGER.warn("[RoomManager] handleJoinRequest中获取公网IP失败: {}", e.getMessage());
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
            VoxLinkMod.LOGGER.info("[RoomManager] 附带主机局域网IP: {}", localIp);
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
                    VoxLinkMod.LOGGER.warn("[RoomManager] 创建主机打洞socket失败: {}", e2.getMessage());
                    hostPuncher = null;
                }
            }
        } else {
            hostPuncher.stopPunch();
            VoxLinkMod.LOGGER.info("[RoomManager] 复用已有主机打洞socket(localPort={})", hostPuncher.getSocket().getLocalPort());
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
                    VoxLinkMod.LOGGER.info("[RoomManager] 主机NAT: {} — 8并发竞速STUN({}服务器)", fNatType != null ? fNatType : "null", allStun.size());
                    StunProbe.PublicMappedAddress[] top2 = StunProbe.discoverMappedAddressRace(
                            fHostPuncher.getSocket(), allStun, 2);
                    m1 = top2[0];
                    m2 = top2[1];
                } catch (Exception e) {
                    VoxLinkMod.LOGGER.warn("[RoomManager] 打洞socket STUN失败: {}", e.getMessage());
                }

                // 对称NAT时预创建84个birthday socket, 端口塞进holepunch_offer避免holepunch_mapped延迟
                // 参考DCUtR/P-PRE: 已知端口列表比端口扫描成功率高出数倍, 且消除信号轮询11s延迟
                if (fIsSymmetricOrUnknown && m1 != null && m2 != null) {
                    int birthdayCount = HARD_SYM_SOCKET_COUNT;
                    VoxLinkMod.LOGGER.info("[RoomManager] 对称NAT, 预创建{}个birthday socket纳入holepunch_offer", birthdayCount);
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
                        VoxLinkMod.LOGGER.warn("[RoomManager] birthday socket创建部分超时: {}", e.getMessage());
                    }
                    for (var f : bFutures) {
                        try {
                            StunProbe.PublicMappedAddress a = f.getNow(null);
                            if (a != null) birthdayAddrs.add(a);
                        } catch (Exception ignored) {}
                    }
                    VoxLinkMod.LOGGER.info("[RoomManager] 预创建{}个birthday socket完成, {}个有效", birthdayCount, birthdayAddrs.size());
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
                    VoxLinkMod.LOGGER.info("[RoomManager] 打洞socket STUN: 对称NAT ({} vs {})", mapped1.port(), mapped2.port());
                } else {
                    if (symOrUnknown && !StunDetector.isNatTypeSymmetric(fNatType)) {
                        VoxLinkMod.LOGGER.info("[RoomManager] 打洞socket STUN: 同端口{}，覆盖isSymmetricOrUnknown(原{})", mapped1.port(), fNatType);
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
                            VoxLinkMod.LOGGER.info("[RoomManager] MC端口STUN失败, 用NAT探测映射地址兜底: {}:{}", sr.mappedIp, sr.mappedPort);
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
                            VoxLinkMod.LOGGER.info("[RoomManager] P-PRE采样: {}次→序列={}, 综合预测port={}, range=±{}, delta={}",
                                    samples.size(), samples, pr.predictedPort, pr.range, reliableDelta);
                            delta = reliableDelta;
                        } else {
                            VoxLinkMod.LOGGER.warn("[RoomManager] P-PRE采样不足({}次), 回退2采样delta={}", samples.size(), delta);
                        }
                    }
                    fOfferData.addProperty("hostMappedPortDelta", delta);
                    fOfferData.addProperty("hostMappedPortRange", portRange);
                    VoxLinkMod.LOGGER.info("[RoomManager] 对称NAT端口偏移: delta={}, range=±{}", delta, portRange);
                }
                fState.roomInfo.setHostMappedAddress(mapped.ip(), mapped.port());
                fState.roomInfo.setHostEasySym(hostEasySym);
                VoxLinkMod.LOGGER.info("[RoomManager] 打洞socket STUN: ip={}, port={}, symmetric={}, easySym={}",
                        mapped.ip(), mapped.port(), symOrUnknown, hostEasySym);
            }

            // 对称NAT预创建的birthday socket端口塞进holepunch_offer, 消除holepunch_mapped信号延迟
            if (birthdayPorts != null && !birthdayPorts.isEmpty()) {
                com.google.gson.JsonArray portsArr = new com.google.gson.JsonArray();
                for (StunProbe.PublicMappedAddress bp : birthdayPorts) {
                    portsArr.add(bp.port());
                }
                fOfferData.add("hostBirthdayPorts", portsArr);
                VoxLinkMod.LOGGER.info("[RoomManager] holepunch_offer附带{}个birthday端口", birthdayPorts.size());
            }

            // RTT同步: 双方等到同一时刻发第一包, 提高对称NAT打洞命中率
            long syncTime = System.currentTimeMillis() + RELAY_GRACE_MS;
            fOfferData.addProperty("punchSyncTimeMs", syncTime);
            fState.roomInfo.setPunchSyncTimeMs(syncTime);
            VoxLinkMod.LOGGER.info("[RoomManager] RTT同步: punchSyncTimeMs={}", syncTime);

            VoxLinkMod.LOGGER.info("[RoomManager] 发holepunch_offer给{} (hostIp={}, hostIpv6={}, port={}, mappedIp={}, mappedPort={})",
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
                            VoxLinkMod.LOGGER.error("[RoomManager] 发holepunch_offer失败: {} - {}", response.error, response.message);
                        }
                    })
                    .exceptionally(e -> {
                        VoxLinkMod.LOGGER.error("[RoomManager] 发holepunch_offer网络错误: {}", e.getMessage());
                        return null;
                    });
        });
    }

    public void handleHolePunchOffer(String from, JsonObject data) {
        RoomManager.RoomState state = roomManager.currentRoom.get();
        VoxLinkMod.LOGGER.info("[RoomManager] 收到holepunch_offer, state={}", state != null && state != RoomManager.PENDING ? "active" : "null/pending");
        if (state == null || state == RoomManager.PENDING || state.roomInfo.isHost()) return;

        if (ConnectionHelper.isConnecting()) {
            VoxLinkMod.LOGGER.info("[RoomManager] 已在连接中，忽略holepunch_offer");
            return;
        }

        if (!connectionCycleActive.compareAndSet(false, true)) {
            VoxLinkMod.LOGGER.info("[RoomManager] 连接周期已在进行，忽略重复holepunch_offer");
            return;
        }
        connectionWon.set(false);  // 新join重置连接状态

        if (P2PBridge.isRunning()) {
            int existingPort = P2PBridge.getJoinerPort();
            String bridgeHostIp = data.has("hostIp") && !data.get("hostIp").isJsonNull() ? data.get("hostIp").getAsString() : state.roomInfo.getHostIp();
            if (existingPort > 0 && P2PBridge.isTargetMatch(bridgeHostIp, state.roomInfo.getHostPort())) {
                VoxLinkMod.LOGGER.info("[RoomManager] 桥已在跑且目标相同，忽略重复holepunch_offer");
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
            VoxLinkMod.LOGGER.info("[RoomManager] 更新roomInfo的hostMapped={}:{}", hostMappedIp, hostMappedPort);
        }

        String hostLocalIp = null;
        if (data.has("hostLocalIp") && !data.get("hostLocalIp").isJsonNull()) {
            hostLocalIp = data.get("hostLocalIp").getAsString();
            state.roomInfo.setHostLocalIp(hostLocalIp);
            VoxLinkMod.LOGGER.info("[RoomManager] 主机提供了局域网IP: {}", hostLocalIp);
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
            VoxLinkMod.LOGGER.info("[RoomManager] RTT同步: punchSyncTimeMs={} (距今{}ms)", punchSyncTime, punchSyncTime - System.currentTimeMillis());
        }

        // host预创建的birthday socket端口, 直接用于反向打洞, 无需等待holepunch_mapped
        java.util.List<Integer> hostBirthdayPorts = null;
        if (data.has("hostBirthdayPorts") && data.get("hostBirthdayPorts").isJsonArray()) {
            hostBirthdayPorts = new java.util.ArrayList<>();
            for (com.google.gson.JsonElement elem : data.getAsJsonArray("hostBirthdayPorts")) {
                hostBirthdayPorts.add(elem.getAsInt());
            }
            VoxLinkMod.LOGGER.info("[RoomManager] holepunch_offer包含{}个birthday端口", hostBirthdayPorts.size());
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
        if (probeFuture != null && stunProbeResult == null) {
            final RoomManager.RoomState fState = state;
            probeFuture.orTimeout(STUN_PROBE_TIMEOUT_SEC, java.util.concurrent.TimeUnit.SECONDS)
                .thenAccept(result -> {
                    stunProbeResult = result;
                    extendConnectionTimeoutIfNeeded(fState);
                    finishHandleHolePunchOffer(fState, from, finalHostIpv6, finalHostIp, finalHostPort,
                            finalHostMappedIp, finalHostMappedPort, finalHostSymmetric, finalHostMappedPortDelta);
                })
                .exceptionally(e -> {
                    VoxLinkMod.LOGGER.warn("[handleHolePunchOffer] STUN探测还没好，先继续");
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
                state.roomInfo.setConnectionMode(Component.translatable("voxlink.connection.connecting"));
            }
            VoxLinkMod.LOGGER.info("[handleHolePunchOffer] 用探测结果: NAT={}, 可达STUN={}",
                    stunProbeResult.natType.key, stunProbeResult.reachableStunUrls.size());

            String clientPublicIp = null;
            for (StunProbe.StunServerResult r : stunProbeResult.serverResults) {
                if (r.reachable && r.mappedIp != null) { clientPublicIp = r.mappedIp; break; }
            }
            if (clientPublicIp != null && finalHostIp != null && clientPublicIp.equals(finalHostIp)) {
                VoxLinkMod.LOGGER.warn("[handleHolePunchOffer] 同公网IP({}): 双方在同一CGNAT后，P2P直连基本没戏", clientPublicIp);
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
                VoxLinkMod.LOGGER.info("[handleHolePunchOffer] 用先到的holepunch_mapped: {}:{}", effectiveMappedIp, effectiveMappedPort);
            }
        }

        if (effectiveMappedIp == null || effectiveMappedPort <= 0) {
            connectionCycleActive.set(false);
            VoxLinkMod.LOGGER.info("[handleHolePunchOffer] offer没映射地址，等holepunch_mapped...");
            scheduler.schedule(() -> {
                if (connectionCycleActive.compareAndSet(false, true)) {
                    String mappedIp = state.roomInfo.getHostMappedIp();
                    int mappedPort = state.roomInfo.getHostMappedPort();
                    if (mappedIp == null || mappedPort <= 0) {
                        VoxLinkMod.LOGGER.warn("[handleHolePunchOffer] holepunch_mapped超时(12s)，没有映射地址也开搞");
                        runConnectionCycle(state, from, finalHostIpv6, finalHostIp, finalHostPort, null, 0, 0);
                    }
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

        VoxLinkMod.LOGGER.info("[RoomManager] 收到主机映射: {}:{} ports={} (sym={}, delta={})", hostMappedIp, hostMappedPort, hostMappedPorts, hostSymmetric, hostMappedPortDelta);
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
                    VoxLinkMod.LOGGER.info("[handleHolepunchMapped] 收到host回传的局域网IP: {}", receivedHostLocalIp);
                    // 如果当前正在连接周期中，立即尝试hostLocalIp
                    if (connectionCycleActive.get() && state.roomInfo.isSameCgnat() && !connectionWon.get()) {
                        int connectPort = state.roomInfo.getHostConnectPort() > 0 ? state.roomInfo.getHostConnectPort() : state.roomInfo.getHostPort();
                        int mcPort = state.roomInfo.getHostPort();
                        VoxLinkMod.LOGGER.info("[handleHolepunchMapped] CGNAT: 补充尝试hostLocalIp {}:{}", receivedHostLocalIp, connectPort);
                        ConnectionFallback localFallback = new ConnectionFallback();
                        localFallback.tryIpv4Direct(receivedHostLocalIp, connectPort).thenAccept(result -> {
                            if (roomManager.currentRoom.get() == state && result.success && connectionWon.compareAndSet(false, true)) {
                                VoxLinkMod.LOGGER.info("[handleHolepunchMapped] CGNAT hostLocalIp直连赢了");
                                connectViaBridge(state, result);
                            }
                        });
                        ConnectionFallback mcLocalFallback = new ConnectionFallback();
                        mcLocalFallback.tryIpv4Direct(receivedHostLocalIp, mcPort).thenAccept(result -> {
                            if (roomManager.currentRoom.get() == state && result.success && connectionWon.compareAndSet(false, true)) {
                                VoxLinkMod.LOGGER.info("[handleHolepunchMapped] CGNAT hostLocalIp MC端口赢了");
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
            VoxLinkMod.LOGGER.info("[RoomManager] 更新joiner打洞目标到 {}:{}", hostMappedIp, updatePort);
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
                        VoxLinkMod.LOGGER.info("[RoomManager] 多端口puncher#{}: {}:{}", i, fHostMappedIp, fExtraPort);
                        extraPuncher.punchWithPortPrediction(fHostMappedIp, fExtraPort, 30).thenAccept(socket -> {
                            if (!connectionWon.compareAndSet(false, true)) {
                                try { extraPuncher.close(); } catch (Exception ignored) {}
                                return;
                            }
                            extraPuncher.markSocketTransferred();
                            killAllConnectionAttempts();
                            extraPuncher.stopPunch();
                            final DatagramSocket winSocket = socket;
                            final UdpHolePuncher winPuncher = extraPuncher;
                            scheduler.submit(() -> {
                                try {
                                    establishUdpTransport(state, winSocket, winPuncher,
                                            new InetSocketAddress(fHostMappedIp, fExtraPort), "joiner", false, null);
                                } catch (Exception e) {
                                    VoxLinkMod.LOGGER.error("[RoomManager] 多端口transport失败: {}", e.getMessage());
                                    winPuncher.close();
                                }
                            });
                        }).exceptionally(e -> {
                            VoxLinkMod.LOGGER.debug("[RoomManager] 多端口puncher#{}打洞失败: {}", fIdx, e.getMessage());
                            activeHolePunchers.remove(key);
                            try { extraPuncher.close(); } catch (Exception ignored) {}
                            return null;
                        });
                    } catch (Exception e) {
                        VoxLinkMod.LOGGER.warn("[RoomManager] 创建多端口puncher#{}失败: {}", fIdx, e.getMessage());
                    }
                }
            }
        } else if (!connectionCycleActive.get() && !ConnectionHelper.isConnecting()) {
            VoxLinkMod.LOGGER.info("[RoomManager] 用映射地址启动连接周期");
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
            VoxLinkMod.LOGGER.info("[HostPunchInfo] joiner局域网IP: {}", joinerLocalIp);
        }
        if (joinerMappedIp != null) {
            String myPublicIp = state.roomInfo.getHostMappedIp();
            if (myPublicIp != null && myPublicIp.equals(joinerMappedIp)) {
                state.roomInfo.setSameCgnat(true);
                VoxLinkMod.LOGGER.warn("[HostPunchInfo] 同公网IP({}): 双方同一CGNAT", joinerMappedIp);
            }
        }
        boolean requestHostLocalIp = data.has("requestHostLocalIp") && data.get("requestHostLocalIp").getAsBoolean();

        // EasyTier DST_PORT_OFFSET: Joiner对称NAT时预测实际映射端
if (joinerMappedPortDelta != 0 && joinerMappedPort > 0) {
            int predicted = joinerMappedPort + joinerMappedPortDelta;
            if (predicted > 0 && predicted <= 65535) {
                VoxLinkMod.LOGGER.info("[HostPunchInfo] Joiner EasySym预测: STUN端口={} + delta={} → 预测端口={}", joinerMappedPort, joinerMappedPortDelta, predicted);
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
            VoxLinkMod.LOGGER.warn("[RoomManager] 无效的punch_info from {}: 没有映射地址", from);
            return;
        }

        if (connectionWon.get()) {
            VoxLinkMod.LOGGER.debug("[HostPunchInfo] already connected, ignoring punch_info");
            return;
        }

        String punchInfoId = joinerMappedIp + ":" + joinerMappedPort;
        if (hostPunching) {
            if (punchInfoId.equals(lastPunchInfoId)) {
                VoxLinkMod.LOGGER.debug("[RoomManager] 已在打同一目标，忽略重复punch_info");
                return;
            }
            VoxLinkMod.LOGGER.info("[RoomManager] 已在打洞，目标变了({} -> {})，忽略新punch_info以免CGNAT IP切换导致误重启", lastPunchInfoId, punchInfoId);
            return;
        }
        lastPunchInfoId = punchInfoId;

        VoxLinkMod.LOGGER.info("[RoomManager] 收到punch_info from {}: {}:{}", from, joinerMappedIp, joinerMappedPort);

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
                VoxLinkMod.LOGGER.warn("[HostPunchInfo] 双方对称NAT含HardSym(hostHard={}, joinerHard={}), UPnP UDP端口{}映射成功, 继续UDP打洞",
                        isHostHardSym, joinerHardSym, upnpPort);
            } else {
                VoxLinkMod.LOGGER.warn("[HostPunchInfo] 双方对称NAT含HardSym(hostHard={}, joinerHard={}), UPnP失败, 仍继续Birthday Attack+端口预测(失败再走Relay)",
                        isHostHardSym, joinerHardSym);
            }
        }
        if (isHostSym && joinerSym) {
            VoxLinkMod.LOGGER.info("[HostPunchInfo] 双方都是EasySym(端口可预测)，继续UDP打洞(EasyTier both_easy_sym)");
        }
        // 端口不可达或未检测完时用20个socket：UPnP伪成功场景，TCP兜底一定失败，需要比3个更多来提高birthday attack成功率
        // UNKNOWN表示端口检测还没完成（竞态条件），也按不可达处理
        RoomInfo.PortStatus portStatus = state.roomInfo.getIpv4Status();
        boolean portUnreachable = portStatus == RoomInfo.PortStatus.UNREACHABLE || portStatus == RoomInfo.PortStatus.UNKNOWN;
        if (portUnreachable && !isHostSym) {
            VoxLinkMod.LOGGER.info("[HostPunchInfo] 主机端口状态={}，升级为20 socket birthday attack", portStatus);
        }
        //修复崩溃: 84 socket Birthday Attack 仅用于 Sym×Sym (双方端口都不可预测)
        //Sym×Cone 对方端口固定, 只需少量socket覆盖房主映射端口; 84个socket×3线程=252线程导致进程资源耗尽崩溃
        final int HOST_MULTI_COUNT;
        if (isHostSym && joinerSym) {
            HOST_MULTI_COUNT = HARD_SYM_SOCKET_COUNT;
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
            VoxLinkMod.LOGGER.info("[HostPunchInfo] 并行启动{}/{}个socket+双STUN任务(无sleep)", createdCount, fHostMultiCount);

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
                VoxLinkMod.LOGGER.error("[HostPunchInfo] 所有STUN查询失败");
                return;
            }
            if (connectionWon.get()) {
                VoxLinkMod.LOGGER.info("[HostPunchInfo] 连接已建立，丢弃迟到的84-socket打洞任务");
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
                    VoxLinkMod.LOGGER.info("[HostPunchInfo] CGNAT场景附带hostLocalIp: {}", myLocalIp);
                }
            }
            JsonArray portsArray = new JsonArray();
            for (StunProbe.PublicMappedAddress a : mappedAddrs) portsArray.add(a.port());
            symData.add("hostMappedPorts", portsArray);
            VoxLinkMod.LOGGER.info("[HostPunchInfo] holepunch_mapped: {} ports={} (symmetric={})", mappedIp, portsArray, hostPunchSocketSymmetric);
            signalingClient.sendSignal(fState.roomInfo.getCode(), fState.roomInfo.getToken(),
                    true, "holepunch_mapped", symData, fFrom)
                    .exceptionally(e -> { VoxLinkMod.LOGGER.debug("holepunch_mapped发送失败: {}", e.getMessage()); return null; });

            hostPunching = true;
            final String clientId = fFrom;
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
                    VoxLinkMod.LOGGER.warn("[RoomManager] 主机打洞超时: {}", clientId);
                    hostPunching = false;
                    lastPunchInfoId = "";
                    activeHolePunchers.remove("host");
                    activeHolePunchers.entrySet().removeIf(e -> e.getKey().startsWith("host_"));
                    for (UdpHolePuncher p : hostPunchers) {
                        try { p.cancel(); p.close(); } catch (Exception ignored) {}
                    }
                }
            }, UDP_PUNCH_TIMEOUT_S + EXTRA_TIMEOUT_SEC, TimeUnit.SECONDS);

            boolean joinerSymmetric = fData.has("joinerSymmetric") && fData.get("joinerSymmetric").getAsBoolean();
            String hostNat = fState.roomInfo.getNatType();
            int hostPortRange = 0;
            if (joinerSymmetric && (StunDetector.isNatTypeSymmetric(hostNat) || hostPunchSocketSymmetric)) {
                hostPortRange = PORT_RANGE_DEFAULT;
            } else if (joinerSymmetric) {
                hostPortRange = PORT_RANGE_DEFAULT;
            }
            VoxLinkMod.LOGGER.info("[HostPunchInfo] {}个socket并行打洞到 {}:{} range=±{}", hostPunchers.size(), fJoinerMappedIp, fJoinerMappedPort, hostPortRange);

            //lazy触发: 等首包或5s兜底再开打, 避免无意义场景浪费
            P2PBridge.armLazyP2pDeadline();
            while (!connectionWon.get() && roomManager.currentRoom.get() == fState) {
                if (P2PBridge.shouldStartPunching()) {
                    if (!P2PBridge.isTrafficDetected()) {
                        VoxLinkMod.LOGGER.info("5s 无流量, 兜底启动打洞");
                    }
                    break;
                }
                try { Thread.sleep(SHORT_SLEEP_MS); } catch (InterruptedException e) { return; }
            }
            if (connectionWon.get() || roomManager.currentRoom.get() != fState) {
                VoxLinkMod.LOGGER.info("[HostPunchInfo] lazy期间已连接或房间变了，放弃打洞");
                for (UdpHolePuncher p : hostPunchers) {
                    try { p.close(); } catch (Exception ignored) {}
                }
                return;
            }

            for (int i = 0; i < hostPunchers.size(); i++) {
                final UdpHolePuncher mp = hostPunchers.get(i);
                final int idx = i;
                mp.punchWithPortPrediction(fJoinerMappedIp, fJoinerMappedPort, hostPortRange).thenAccept(socket -> {
                    if (!hostPunchWon.compareAndSet(false, true)) {
                        try { mp.close(); } catch (Exception ignored) {}
                        return;
                    }
                    if (roomManager.currentRoom.get() != fState || !connectionWon.compareAndSet(false, true)) {
                        try { mp.close(); } catch (Exception ignored) {}
                        return;
                    }
                    VoxLinkMod.LOGGER.info("[HostPunchInfo] Socket#{} 打洞成功！", idx);
                    mp.markSocketTransferred();
                    killAllConnectionAttempts();
                    mp.stopPunch();
                    final DatagramSocket winSocket = socket;
                    final UdpHolePuncher winPuncher = mp;

                    scheduler.submit(() -> {
                        try {
                            establishUdpTransport(fState, winSocket, winPuncher,
                                    new InetSocketAddress(fJoinerMappedIp, fJoinerMappedPort), clientId, true, clientId);
                        } catch (Exception e) {
                            VoxLinkMod.LOGGER.error("[HostPunchInfo] transport创建失败: {}", e.getMessage());
                            winPuncher.close();
                        }
                    });
                }).exceptionally(e -> {
                    VoxLinkMod.LOGGER.debug("[HostPunchInfo] Socket#{} 打洞失败: {}", idx, e.getMessage());
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

        VoxLinkMod.LOGGER.info("[RoomManager] 收到punch_info里的主机映射地址: {}:{}", hostMappedIp, hostMappedPort);
        state.roomInfo.setHostMappedAddress(hostMappedIp, hostMappedPort);

        UdpHolePuncher joinerPuncher = activeHolePunchers.get("joiner");
        if (joinerPuncher != null && connectionCycleActive.get()) {
            VoxLinkMod.LOGGER.info("[RoomManager] 更新joiner打洞目标到 {}:{}", hostMappedIp, hostMappedPort);
            joinerPuncher.updateTarget(hostMappedIp, hostMappedPort);
        }
    }

    public void handlePeerPort(String from, JsonObject data) {
        String peerIp = data.has("peer_ip") ? data.get("peer_ip").getAsString() : null;
        int peerPort = data.has("peer_port") ? data.get("peer_port").getAsInt() : 0;
        if (peerIp == null || peerPort <= 0) return;

        UdpHolePuncher puncher = activeHolePunchers.get(from.contains("host") ? "joiner" : "host");
        if (puncher == null) return;

        VoxLinkMod.LOGGER.info("[RoomManager] 收到peer_port信号: 更新目标到 {}:{}", peerIp, peerPort);
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
                VoxLinkMod.LOGGER.info("[ReversePunch] RTT同步等待: {}ms后启动主机打洞", delay);
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
            VoxLinkMod.LOGGER.info("[ReversePunch] 已有活跃transport给 {}，忽略reverse_holepunch_offer", from);
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
                VoxLinkMod.LOGGER.info("[ReversePunch] Joiner EasySym预测: {} + {} = {}", joinerMappedPort, joinerMappedPortDelta, predicted);
                joinerMappedPort = predicted;
            }
        }

        if (joinerMappedIp == null || joinerMappedPort == 0) {
            VoxLinkMod.LOGGER.warn("[ReversePunch] 无效的reverse_holepunch_offer: 没有映射地址");
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
                VoxLinkMod.LOGGER.warn("[ReversePunch] dual STUN失败: {}", e.getMessage());
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
                    .exceptionally(e -> { VoxLinkMod.LOGGER.debug("[ReversePunch] reverse_punch_info发送失败: {}", e.getMessage()); return null; });

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
                VoxLinkMod.LOGGER.info("[ReversePunch] 生日攻击模式: 主机打{}个joiner端口: {}", allJoinerPorts.size(), allJoinerPorts);
            } else if (fJoinerSymmetric) {
                hostPortRange = PORT_RANGE_WIDE;
            } else if (hostSymmetric) {
                hostPortRange = PORT_RANGE_DEFAULT;
            } else if ("moderate".equals(hostNat) || "port_restricted_cone".equals(hostNat)) {
                hostPortRange = UdpHolePuncher.PORT_PREDICTION_MAX_RANGE;
            } else {
                hostPortRange = PORT_RANGE_DEFAULT;
            }
            VoxLinkMod.LOGGER.info("[ReversePunch] 主机打洞到joiner {}（range=±{}, hostNat={}, joinerSym={}, birthdayPorts={}）",
                    fJoinerMappedIp, hostPortRange, hostNat, fJoinerSymmetric, allJoinerPorts.size() > 1 ? allJoinerPorts : "no");

            final String clientId = fFrom;
            final UdpHolePuncher currentPuncher = fPuncher;
            final String fRevJoinerMappedIp = fJoinerMappedIp;
            final int fRevJoinerMappedPort = fJoinerMappedPort;

            ScheduledFuture<?> punchTimeout = scheduler.schedule(() -> {
                if (activeHolePunchers.get("hostRev") == currentPuncher) {
                    VoxLinkMod.LOGGER.warn("[ReversePunch] 主机反向打洞超时: {}", clientId);
                    currentPuncher.cancel();
                    currentPuncher.close();
                    activeHolePunchers.remove("hostRev");
                }
            }, UDP_PUNCH_TIMEOUT_S + EXTRA_TIMEOUT_SEC, TimeUnit.SECONDS);

            java.util.concurrent.CompletableFuture<java.net.DatagramSocket> punchFuture;
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
                VoxLinkMod.LOGGER.info("[ReversePunch] 生日攻击: {}个端口扩展到{}个端口（范围 {}-{}）",
                        allJoinerPorts.size(), portList.size(),
                        allJoinerPorts.get(0) - PORT_RANGE_DEFAULT, allJoinerPorts.get(allJoinerPorts.size() - 1) + PORT_RANGE_DEFAULT);
                punchFuture = currentPuncher.punchMultiPort(fRevJoinerMappedIp, portList);
            } else {
                punchFuture = currentPuncher.punchWithPortPrediction(fRevJoinerMappedIp, fRevJoinerMappedPort, hostPortRange, true);
            }

            punchFuture.thenAccept(socket -> {
                if (roomManager.currentRoom.get() != fState || !connectionWon.compareAndSet(false, true)) {
                    currentPuncher.close();
                    return;
                }
                VoxLinkMod.LOGGER.info("[ReversePunch] 主机反向打洞成功，连接到joiner {}:{}", fRevJoinerMappedIp, fRevJoinerMappedPort);
                currentPuncher.markSocketTransferred();
                killAllConnectionAttempts();

                currentPuncher.stopPunch();
                final DatagramSocket hostPunchSocket = socket;
                final UdpHolePuncher hostPuncherRef = currentPuncher;

                scheduler.submit(() -> {
                    try {
                        establishUdpTransport(fState, hostPunchSocket, hostPuncherRef,
                                new InetSocketAddress(fRevJoinerMappedIp, fRevJoinerMappedPort), clientId, true, clientId);
                    } catch (Exception e) {
                        VoxLinkMod.LOGGER.error("[ReversePunch] 主机UDP transport创建失败: {}", e.getMessage());
                        hostPuncherRef.close();
                    }
                });
            }).exceptionally(e -> {
                punchTimeout.cancel(false);
                VoxLinkMod.LOGGER.warn("[ReversePunch] 主机反向打洞失败 {}: {}", clientId, e.getMessage());
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
            VoxLinkMod.LOGGER.info("[ReversePunch] 不在连接周期，忽略reverse_punch_info");
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
            VoxLinkMod.LOGGER.info("[ReversePunch] 已在反向打洞中，更新目标到{}:{}", hostMappedIp, updatePort);
            if (hostMappedIp != null && updatePort > 0) {
                existingPuncher.updateTarget(hostMappedIp, updatePort);
            }
            // 同步更新主joiner打洞目标，确保tryUdpPunch也用正确的host端口
            UdpHolePuncher joinerPuncher = activeHolePunchers.get("joiner");
            if (joinerPuncher != null && joinerPuncher.isPunching() && hostMappedIp != null && updatePort > 0) {
                VoxLinkMod.LOGGER.info("[ReversePunch] 同步更新主joiner打洞目标到{}:{}", hostMappedIp, updatePort);
                joinerPuncher.updateTarget(hostMappedIp, updatePort);
            }
            return;
        }

        if (hostMappedIp == null || hostMappedPort <= 0) {
            hostMappedIp = state.roomInfo.getHostMappedIp();
            hostMappedPort = state.roomInfo.getHostMappedPort();
        }
        if (hostMappedIp == null || hostMappedPort <= 0) {
            VoxLinkMod.LOGGER.warn("[ReversePunch] reverse_punch_info中没有主机映射地址");
            return;
        }

        if (hostSymmetric) {
            state.roomInfo.setHostSymmetric(true);
        }

        VoxLinkMod.LOGGER.info("[ReversePunch] Joiner收到reverse_punch_info: {}:{}（hostSym={}）", hostMappedIp, hostMappedPort, hostSymmetric);

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
                VoxLinkMod.LOGGER.info("[BirthdayPunch] 已在打洞中，更新目标到{}:{}，共{}个socket",
                        hostMappedIp, hostMappedPort, birthdayPunchers.size());
                for (UdpHolePuncher p : birthdayPunchers) {
                    if (p.isPunching()) {
                        p.updateTarget(hostMappedIp, hostMappedPort);
                    }
                }
                return;
            }
            VoxLinkMod.LOGGER.info("[BirthdayPunch] 启动生日攻击{}个socket打洞到 {}:{}",
                    birthdayPunchers.size(), hostMappedIp, hostMappedPort);
            startBirthdayPunchPhase2(state, birthdayPunchers, birthdayKeys, hostMappedIp, hostMappedPort, hostSymmetric, false);
            return;
        }

        UdpHolePuncher puncher = activeHolePunchers.get("joiner_reverse");
        if (puncher == null || puncher.getSocket() == null || puncher.getSocket().isClosed()) {
            VoxLinkMod.LOGGER.warn("[ReversePunch] 没有joiner_reverse puncher可用（puncher={}, socket={}, closed={}）",
                    puncher != null, puncher != null ? puncher.getSocket() != null : false,
                    puncher != null && puncher.getSocket() != null ? puncher.getSocket().isClosed() : false);
            showConnectFailedFinal(state);
            return;
        }

        VoxLinkMod.LOGGER.info("[ReversePunch] Joiner反向打洞状态: localPort={}, punching={}",
                puncher.getSocket().getLocalPort(), puncher.isPunching());

        puncher.setOnPeerPunchReceived(addr -> {
            VoxLinkMod.LOGGER.info("[ReversePunch] Joiner收到对端打洞包 {}:{} — 发peer_port信号", addr.getAddress().getHostAddress(), addr.getPort());
            String code = state.roomInfo.getCode();
            String token = state.roomInfo.getToken();
            JsonObject portData = new JsonObject();
            portData.addProperty("peer_ip", addr.getAddress().getHostAddress());
            portData.addProperty("peer_port", addr.getPort());
            signalingClient.sendSignal(code, token, false, "peer_port", portData, "host")
                    .exceptionally(e -> { VoxLinkMod.LOGGER.debug("peer_port信号发送失败: {}", e.getMessage()); return null; });
        });

        boolean joinerIsSymmetric = (stunProbeResult != null && stunProbeResult.natType.isSymmetric());
        int portRange = PORT_RANGE_DEFAULT;
        if (joinerIsSymmetric) {
            portRange = PORT_RANGE_DEFAULT;
            VoxLinkMod.LOGGER.info("[ReversePunch] Joiner是symmetric NAT — 用小范围(±30)打开NAT映射");
        } else if (hostSymmetric) {
            portRange = UdpHolePuncher.PORT_PREDICTION_MAX_RANGE;
        } else {
            portRange = PORT_RANGE_DEFAULT;
        }

        VoxLinkMod.LOGGER.info("[ReversePunch] Joiner打洞到主机 {}:{}（range=±{}, joinerSym={}, hostSym={}）",
                hostMappedIp, hostMappedPort, portRange, joinerIsSymmetric, hostSymmetric);

        final UdpHolePuncher finalPuncher = puncher;
        final String fHostMappedIp = hostMappedIp;
        final int fHostMappedPort = hostMappedPort;

        puncher.punchWithPortPrediction(hostMappedIp, hostMappedPort, portRange, true).thenAccept(socket -> {
            if (roomManager.currentRoom.get() != state || !connectionWon.compareAndSet(false, true)) {
                try { finalPuncher.close(); } catch (Exception ignored) {}
                return;
            }
            VoxLinkMod.LOGGER.info("[ReversePunch] Joiner反向打洞成功 {}:{}", fHostMappedIp, fHostMappedPort);
            finalPuncher.markSocketTransferred();
            killAllConnectionAttempts();

            finalPuncher.stopPunch();
            final DatagramSocket punchSocket = socket;
            final UdpHolePuncher puncherRef = finalPuncher;

            // 用实际收到包的地址，而非STUN映射地址（对称NAT端口会偏移）
            InetSocketAddress actualAddr = puncherRef.getActualRemoteAddress();
            if (actualAddr == null) actualAddr = new InetSocketAddress(fHostMappedIp, fHostMappedPort);
            final InetSocketAddress finalTargetAddr = actualAddr;
            VoxLinkMod.LOGGER.info("[ReversePunch] 实际目标地址: {} (STUN映射: {}:{})", finalTargetAddr, fHostMappedIp, fHostMappedPort);

            scheduler.submit(() -> {
                try {
                    establishUdpTransport(state, punchSocket, puncherRef,
                            finalTargetAddr, "joiner", false, null);
                } catch (Exception e) {
                    VoxLinkMod.LOGGER.error("[ReversePunch] Joiner UDP transport创建失败: {}", e.getMessage());
                    try { puncherRef.close(); } catch (Exception ignored) {}
                    showConnectFailedFinal(state);
                }
            });
        }).exceptionally(e -> {
            VoxLinkMod.LOGGER.warn("[ReversePunch] Joiner反向打洞失败: {}", e.getMessage());
            finalPuncher.cancel();
            finalPuncher.close();
            activeHolePunchers.remove("joiner_reverse");
            showConnectFailedFinal(state);
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

        VoxLinkMod.LOGGER.info("[TcpSimOpen] 主机收到joiner {}的请求，尝试TCP连接 {}:{}", from, joinerMappedIp, joinerMappedPort);
        int hostPort = P2PBridge.getHostPort() > 0 ? P2PBridge.getHostPort() : 25565;
        ConnectionFallback hostSimFallback = new ConnectionFallback();
        hostSimFallback.tryTcpSimultaneousOpen(joinerMappedIp, joinerMappedPort, hostPort).thenAccept(result -> {
            if (result.success && connectionWon.compareAndSet(false, true)) {
                VoxLinkMod.LOGGER.info("[TcpSimOpen] 主机通过TCP SimOpen连上joiner了！");
                RoomManager.RoomState st = roomManager.currentRoom.get();
                if (st != null) {
                    connectViaBridge(st, result);
                }
            } else if (result.success) {
                VoxLinkMod.LOGGER.info("[TcpSimOpen] TCP SimOpen成功但连接已被占，忽略");
            } else {
                VoxLinkMod.LOGGER.info("[TcpSimOpen] 主机TCP SimOpen失败: {}", result.failureReason);
            }
        });
    }

    public void handleHolePunchAnswer(String from, JsonObject data) {
        RoomManager.RoomState state = roomManager.currentRoom.get();
        if (state != null && state != RoomManager.PENDING && state.roomInfo.isHost()) {
            VoxLinkMod.LOGGER.info("收到打洞应答来自joiner {}", from);
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

    public void runConnectionCycle(RoomManager.RoomState state, String from, String hostIpv6, String hostIp, int hostPort, String hostMappedIp, int hostMappedPort, int cycle) {
        if (connectionWon.get()) {
            VoxLinkMod.LOGGER.info("[Connection] 已连接，跳过周期{}", cycle + 1);
            return;
        }
        int maxCycles = getEffectiveMaxCycles();
        if (cycle >= maxCycles) {
            if (connectionWon.get()) return;
            ConnectionState.transitionTo(ConnectionState.FAILED, "超过最大周期" + maxCycles);
            showConnectFailed(state);
            return;
        }

        connectionWon.set(false);
        ConnectionState.transitionTo(ConnectionState.STUN_PROBE, "周期" + (cycle + 1) + "/" + maxCycles);

        if (cycle == 0) {
            connectionStartTimeMs = System.currentTimeMillis();
            int timeoutSec = CONNECTION_TIMEOUT_SECONDS;
            boolean joinerSym = stunProbeResult != null && stunProbeResult.natType.isSymmetric();
            boolean hostSym = state.roomInfo.isHostSymmetric();
            if (joinerSym || hostSym) {
                timeoutSec = SYMMETRIC_CONNECTION_TIMEOUT_SECONDS;
                VoxLinkMod.LOGGER.info("[Connection] 一方对称NAT(joinerSym={}, hostSym={})，全局超时延长到{}s", joinerSym, hostSym, timeoutSec);
            }
            connectionTimeoutSec = timeoutSec;
            scheduleConnectionTimeout(state, timeoutSec);
        }

        int displayCycle = cycle + 1;
        state.roomInfo.setConnectionMode(Component.translatable("voxlink.connection.connecting"));

        // RTT同步: 双方等到约定的同步时刻再发包, 确保NAT映射在两侧同时建立
        long syncTime = state.roomInfo.getPunchSyncTimeMs();
        if (cycle == 0 && syncTime > 0) {
            long delay = syncTime - System.currentTimeMillis();
            if (delay > 0 && delay < MAX_DELAY_MS) {
                VoxLinkMod.LOGGER.info("[Connection] RTT同步等待: {}ms后同时发包", delay);
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
                VoxLinkMod.LOGGER.warn("[Connection] 全局超时({}s)，中止", finalTimeoutSec);
                showConnectFailedFinal(state);
            }
        }, finalTimeoutSec, TimeUnit.SECONDS);
    }

    //NAT探测完成后, 若发现对称NAT, 延长超时到90s
    private void extendConnectionTimeoutIfNeeded(RoomManager.RoomState state) {
        if (connectionStartTimeMs == 0 || stunProbeResult == null) return;
        if (!stunProbeResult.natType.isSymmetric()) return;
        if (connectionTimeoutSec >= SYMMETRIC_CONNECTION_TIMEOUT_SECONDS) return;
        long elapsedMs = System.currentTimeMillis() - connectionStartTimeMs;
        long remainingMs = SYMMETRIC_CONNECTION_TIMEOUT_SECONDS * 1000L - elapsedMs;
        if (remainingMs <= 0) return;
        connectionTimeoutSec = SYMMETRIC_CONNECTION_TIMEOUT_SECONDS;
        VoxLinkMod.LOGGER.info("[Connection] NAT探测完成发现对称NAT，全局超时延长到{}s(剩余{}ms)", SYMMETRIC_CONNECTION_TIMEOUT_SECONDS, remainingMs);
        scheduleConnectionTimeout(state, (int)(remainingMs / 1000) + 1);
    }

    public void tryConnectionStep(RoomManager.RoomState state, String from, String hostIpv6, String hostIp, int hostPort, String hostMappedIp, int hostMappedPort, int cycle, int displayCycle, int maxCycles, int step) {
        if (roomManager.currentRoom.get() != state) return;
        if (connectionWon.get()) {
            VoxLinkMod.LOGGER.info("[Connection] 已连接，跳过Wave步骤(cycle={}, step={})", cycle + 1, step);
            return;
        }

        switch (step) {
            case 0: {
                VoxLinkMod.LOGGER.info("[Connection] Wave 1: LAN+IPv6+UDP并行 (周期{}/{})", displayCycle, maxCycles);
                state.roomInfo.setConnectionMode(Component.translatable("voxlink.connection.connecting"));

                AtomicBoolean wave1Settled = new AtomicBoolean(false);
                java.util.List<CompletableFuture<?>> wave1Futures = new java.util.ArrayList<>();

                String hostLocalIp = state.roomInfo.getHostLocalIp();
                boolean sameLan = hostLocalIp != null && !hostLocalIp.isEmpty() && StunDetector.isSameLan(hostLocalIp);
                boolean sameCgnat = state.roomInfo.isSameCgnat();
                if (hostLocalIp != null && !hostLocalIp.isEmpty() && (sameLan || sameCgnat)) {
                    String reason = sameLan ? "LAN" : "CGNAT同公网IP";
                    VoxLinkMod.LOGGER.info("[Connection] Wave 1: 检测到{}(localIp={})，尝试直连", reason, hostLocalIp);
                    ConnectionFallback lanFallback = new ConnectionFallback();
                    wave1Futures.add(lanFallback.tryIpv4Direct(hostLocalIp, hostPort).thenAccept(result -> {
                        if (roomManager.currentRoom.get() == state && result.success && connectionWon.compareAndSet(false, true)) {
                            VoxLinkMod.LOGGER.info("[Connection] Wave 1: {}直连赢了", reason);
                            wave1Settled.set(true);
                            connectViaBridge(state, result);
                        } else if (roomManager.currentRoom.get() == state && result != null && !result.success) {
                            //黑名单: 直连失败
                            addressBlacklist.recordDirectFailure(new InetSocketAddress(hostLocalIp, hostPort));
                        }
                    }));
                    if (sameCgnat && !sameLan) {
                        int mcPort = state.roomInfo.getHostPort();
                        VoxLinkMod.LOGGER.info("[Connection] Wave 1: CGNAT还尝试localIp MC端口 {}:{}", hostLocalIp, mcPort);
                        ConnectionFallback mcFallback = new ConnectionFallback();
                        wave1Futures.add(mcFallback.tryIpv4Direct(hostLocalIp, mcPort).thenAccept(result -> {
                            if (roomManager.currentRoom.get() == state && result.success && connectionWon.compareAndSet(false, true)) {
                                VoxLinkMod.LOGGER.info("[Connection] Wave 1: CGNAT localIp MC端口赢了");
                                wave1Settled.set(true);
                                connectViaBridge(state, result);
                            }
                        }));
                    }
                }

                if (hostIpv6 != null && !hostIpv6.isEmpty() && StunDetector.verifyIPv6Connectivity()) {
                    VoxLinkMod.LOGGER.info("[Connection] Wave 1: 并行尝试IPv6直连");
                    ConnectionFallback ipv6Fallback = new ConnectionFallback();
                    wave1Futures.add(ipv6Fallback.tryIpv6Direct(hostIpv6, hostPort).thenAccept(result -> {
                        if (roomManager.currentRoom.get() == state && result.success && connectionWon.compareAndSet(false, true)) {
                            VoxLinkMod.LOGGER.info("[Connection] Wave 1: IPv6直连赢了");
                            wave1Settled.set(true);
                            connectViaBridge(state, result);
                        }
                    }));
                } else if (hostIpv6 != null && !hostIpv6.isEmpty()) {
                    VoxLinkMod.LOGGER.info("[Connection] Wave 1: 跳过IPv6（本地无IPv6连接）");
                }

                VoxLinkMod.LOGGER.info("[Connection] Wave 1: 尝试UDP打洞");
                tryUdpPunch(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles);
                if (reversePunchAttempted.compareAndSet(false, true)) {
                    VoxLinkMod.LOGGER.info("[Connection] Wave 1: 同时启动反向打洞");
                    startReversePunch(state);
                }

                if (!wave1Futures.isEmpty()) {
                    CompletableFuture.allOf(wave1Futures.toArray(new CompletableFuture[0])).thenAccept(v -> {
                        if (roomManager.currentRoom.get() != state) return;
                        if (!wave1Settled.get()) {
                            VoxLinkMod.LOGGER.info("[Connection] Wave 1 TCP全挂，进Wave 2");
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
                VoxLinkMod.LOGGER.info("[Connection] Wave 2: TCP兜底并行 (周期{}/{})", displayCycle, maxCycles);
                ConnectionState.transitionTo(ConnectionState.TCP_FALLBACK, "Wave 2 TCP兜底");
                state.roomInfo.setConnectionMode(Component.translatable("voxlink.connection.connecting"));

                AtomicBoolean wave2Settled = new AtomicBoolean(false);
                java.util.List<CompletableFuture<ConnectionFallback.ConnectResult>> wave2Futures = new java.util.ArrayList<>();

                if (hostMappedIp != null && !hostMappedIp.isEmpty() && hostMappedPort > 0) {
                    ConnectionFallback tcpSimFallback = new ConnectionFallback();
                    int simLocalPort = P2PBridge.getHostPort() > 0 ? P2PBridge.getHostPort() : hostPort;
                    String myMappedIp = state.roomInfo.getMyMappedIp();
                    int myMappedPort = state.roomInfo.getMyMappedPort();
                    if (myMappedIp != null && myMappedPort > 0 && signalingClient != null) {
                        JsonObject simReq = new JsonObject();
                        simReq.addProperty("joinerMappedIp", myMappedIp);
                        simReq.addProperty("joinerMappedPort", myMappedPort);
                        signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), false, "tcp_simopen_request", simReq, "host");
                        VoxLinkMod.LOGGER.info("[Connection] Wave 2: 发tcp_simopen_request给host ({}:{})", myMappedIp, myMappedPort);
                    }
                    // TCP连接bridge端口，不是NAT映射端口
                    int tcpTargetPort = hostPort > 0 ? hostPort : hostMappedPort;
                    wave2Futures.add(tcpSimFallback.tryTcpSimultaneousOpen(hostMappedIp, tcpTargetPort, simLocalPort));
                }

                if (hostMappedIp != null && !hostMappedIp.isEmpty() && hostMappedPort > 0) {
                    ConnectionFallback tcpMappedFallback = new ConnectionFallback();
                    // TCP直连也用bridge端口
                    int tcpDirectPort = hostPort > 0 ? hostPort : hostMappedPort;
                    wave2Futures.add(tcpMappedFallback.tryIpv4Direct(hostMappedIp, tcpDirectPort));
                }

                if (hostIp != null && !hostIp.isEmpty()) {
                    ConnectionFallback ipv4Fallback = new ConnectionFallback();
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
                        VoxLinkMod.LOGGER.info("[Connection] Wave 2: CGNAT场景尝试hostLocalIp {}:{}", hostLocalIp2, hostPort);
                        ConnectionFallback localFallback = new ConnectionFallback();
                        wave2Futures.add(localFallback.tryIpv4Direct(hostLocalIp2, hostPort));
                        int mcPort = state.roomInfo.getHostPort();
                        VoxLinkMod.LOGGER.info("[Connection] Wave 2: CGNAT场景尝试localIp MC端口 {}:{}", hostLocalIp2, mcPort);
                        ConnectionFallback mcLocalFallback = new ConnectionFallback();
                        wave2Futures.add(mcLocalFallback.tryIpv4Direct(hostLocalIp2, mcPort));
                    }
                }

                // Wave 2也尝试IPv6（如果Wave 1没成功的话）
                if (hostIpv6 != null && !hostIpv6.isEmpty() && StunDetector.verifyIPv6Connectivity()) {
                    VoxLinkMod.LOGGER.info("[Connection] Wave 2: 尝试IPv6直连");
                    ConnectionFallback ipv6Fallback2 = new ConnectionFallback();
                    wave2Futures.add(ipv6Fallback2.tryIpv6Direct(hostIpv6, hostPort));
                }

                if (wave2Futures.isEmpty()) {
                    VoxLinkMod.LOGGER.info("[Connection] Wave 2: 没TCP兜底可用，进下一周期");
                    advanceToNextCycle(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, maxCycles);
                    return;
                }

                for (CompletableFuture<ConnectionFallback.ConnectResult> future : wave2Futures) {
                    future.thenAccept(result -> {
                        if (roomManager.currentRoom.get() == state && result.success && connectionWon.compareAndSet(false, true)) {
                            VoxLinkMod.LOGGER.info("[Connection] Wave 2: {}赢了", result.errorCode);
                            connectViaBridge(state, result);
                        }
                    });
                }

                CompletableFuture.allOf(wave2Futures.toArray(new CompletableFuture[0])).thenAccept(v -> {
                    if (roomManager.currentRoom.get() != state) return;
                    if (!connectionWon.get()) {
                        VoxLinkMod.LOGGER.info("[Connection] Wave 2: TCP兜底全挂，进下一周期");
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
            VoxLinkMod.LOGGER.info("[Connection] 已连接，跳过UDP打洞(cycle={}, attempt={})", cycle + 1, attempt);
            return;
        }
        state.roomInfo.setConnectionMode(Component.translatable("voxlink.connection.connecting"));
        VoxLinkMod.LOGGER.info("[Connection] UDP打洞 周期{}/{}, 尝试{}/{}", displayCycle, maxCycles, attempt, UDP_PUNCH_MAX_ATTEMPTS);

        //优化: Sym×Sym不直接放弃, 先尝试Birthday Attack+端口预测, 失败再走Relay/TCP兜底
        //用户原话: "为什么会直接放弃呢？应该是先尝试直接进行打洞连接，然后再尝试使用另一个非对称NAT玩家进行中继"
        boolean joinerSym = stunProbeResult != null && stunProbeResult.natType.isSymmetric();
        boolean joinerHardSym = stunProbeResult != null && stunProbeResult.natType.isHardSymmetric();
        boolean hostSym = state.roomInfo.isHostSymmetric();
        boolean hostHardSym = hostSym && !state.roomInfo.isHostEasySym();
        if (joinerSym && hostSym) {
            if (joinerHardSym || hostHardSym) {
                VoxLinkMod.LOGGER.info("[Connection] 双方对称NAT含HardSym(joinerHard={}, hostHard={})，先试Birthday Attack+端口预测，失败再走Relay",
                        joinerHardSym, hostHardSym);
            } else {
                VoxLinkMod.LOGGER.info("[Connection] 双方都是EasySym(端口可预测)，继续UDP打洞(EasyTier both_easy_sym)");
            }
        }

        // 停洞复用socket
        // 复用socket防端口变。不再stop旧punch——让它在后台自然超时。
        UdpHolePuncher prev = activeHolePunchers.get("joiner");
        UdpHolePuncher puncher = activeHolePunchers.get("joiner_reuse");
        if (puncher != null && puncher.getSocket() != null && !puncher.getSocket().isClosed()) {
            activeHolePunchers.put("joiner", puncher);
            VoxLinkMod.LOGGER.info("[Connection] 复用打洞socket(port={})", puncher.getSocket().getLocalPort());
        } else {
            if (prev != null) try { prev.close(); } catch (Exception ignored) {}
            activeHolePunchers.remove("joiner_reuse");
            puncher = new UdpHolePuncher();
            try {
                puncher.createSocket();
            } catch (Exception e) {
                VoxLinkMod.LOGGER.error("[Connection] 创建UDP打洞socket失败: {}", e.getMessage());
                tryConnectionStep(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles, 1);
                return;
            }
            //UPnP主动映射, 对称NAT转FullCone, 降级不阻塞
            try {
                int upnpLocal = puncher.getSocket().getLocalPort();
                if (icu.wuhui.voxlink.network.UPnPManager.addPortMapping(upnpLocal)) {
                    VoxLinkMod.LOGGER.info("UPnP 映射成功 {} -> {}", upnpLocal, upnpLocal);
                }
            } catch (Exception e) {
                VoxLinkMod.LOGGER.warn("UPnP 映射失败, 降级原打洞流程: {}", e.getMessage());
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
                VoxLinkMod.LOGGER.info("[Connection] Joiner打洞socket STUN: 对称NAT ({} vs {}, delta={})", myMapped1.port(), myMapped2.port(), joinerMappedPortDelta);
            }
            myMappedAddr = myMapped2;
        } else {
            myMappedAddr = myMapped1 != null ? myMapped1 : myMapped2;
        }

        if (myMappedAddr == null) {
            myMappedAddr = puncher.discoverMappedAddress(StunDetector.getAllStunUrls());
        }

        if (myMappedAddr == null) {
            VoxLinkMod.LOGGER.warn("[Connection] 打洞socket STUN失败，试临时socket兜底(尝试{})", attempt);
            DatagramSocket tmp = null;
            try {
                tmp = new DatagramSocket();
                tmp.setSoTimeout(PROBE_SOCKET_TIMEOUT_MS);
                myMappedAddr = StunProbe.discoverMappedAddress(tmp, StunDetector.getAllStunUrls());
            } catch (Exception e) {
                VoxLinkMod.LOGGER.warn("[Connection] 临时socket STUN也挂了: {}", e.getMessage());
            } finally {
                if (tmp != null && !tmp.isClosed()) { tmp.close(); }
            }
        }
        if (myMappedAddr != null) {
            VoxLinkMod.LOGGER.info("[Connection] 我的映射地址: {}:{} (尝试{})", myMappedAddr.ip(), myMappedAddr.port(), attempt);
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
                VoxLinkMod.LOGGER.info("[Connection] punch_info带端口偏移: delta={}", joinerMappedPortDelta);
            }
            if (joinerPunchSocketSymmetric) {
                VoxLinkMod.LOGGER.info("[Connection] 用打洞socket双STUN结果覆盖joinerSymmetric=true");
            }
            String joinerLocalIp = StunDetector.getLocalIpAddress();
            if (joinerLocalIp != null && !joinerLocalIp.isEmpty()) {
                punchData.addProperty("joinerLocalIp", joinerLocalIp);
                VoxLinkMod.LOGGER.info("[Connection] punch_info附带joiner局域网IP: {}", joinerLocalIp);
            }
            if (state.roomInfo.isSameCgnat() && (state.roomInfo.getHostLocalIp() == null || state.roomInfo.getHostLocalIp().isEmpty())) {
                punchData.addProperty("requestHostLocalIp", true);
                VoxLinkMod.LOGGER.info("[Connection] CGNAT场景请求host发送局域网IP");
            }
            signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(),
                    false, "punch_info", punchData, "host");
        } else {
            VoxLinkMod.LOGGER.warn("[Connection] STUN绑定失败，没有映射地址(尝试{})", attempt);
            puncher.close();
            if (attempt < UDP_PUNCH_MAX_ATTEMPTS) {
                long delay = UDP_PUNCH_RETRY_DELAY_MS * (1L << Math.min(attempt - 1, 4));
                VoxLinkMod.LOGGER.info("[Connection] {}ms后重试UDP打洞(尝试{}/{})", delay, attempt + 1, UDP_PUNCH_MAX_ATTEMPTS);
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

        activeHolePunchers.put("joiner", puncher);

        puncher.setOnPeerPunchReceived(addr -> {
            String code = state.roomInfo.getCode();
            String token = state.roomInfo.getToken();
            JsonObject portData = new JsonObject();
            portData.addProperty("peer_ip", addr.getAddress().getHostAddress());
            portData.addProperty("peer_port", addr.getPort());
            signalingClient.sendSignal(code, token, false, "peer_port", portData, "host")
                    .exceptionally(e -> { VoxLinkMod.LOGGER.debug("peer_port信号发送失败: {}", e.getMessage()); return null; });
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
                VoxLinkMod.LOGGER.info("[Connection] EasySym端口预测: STUN端口={} + delta={} → 预测端口={}", targetPort, hostMappedPortDelta, predictedPort);
                targetPort = predictedPort;
            }
        }

        final String fTargetIp = targetIp;
        final int fTargetPort = targetPort;

        VoxLinkMod.LOGGER.info("[Connection] UDP打洞目标: {}:{} (hostMappedIp={}, hostMappedPort={}, delta={}, hostIp={}, hostPort={}, 尝试{})",
                fTargetIp, fTargetPort, hostMappedIp, hostMappedPort, hostMappedPortDelta, hostIp, hostPort, attempt);
        ConnectionState.transitionTo(ConnectionState.UDP_PUNCH, "尝试" + attempt + "/" + UDP_PUNCH_MAX_ATTEMPTS + " 目标" + fTargetIp + ":" + fTargetPort);

        if (targetIp == null || targetIp.isEmpty()) {
            VoxLinkMod.LOGGER.warn("[Connection] 没有目标IP，UDP打洞没法打");
            puncher.close();
            activeHolePunchers.remove("joiner");
            tryConnectionStep(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles, 1);
            return;
        }

        final UdpHolePuncher finalPuncher = puncher;
        //黑名单过滤
        final InetSocketAddress punchTargetAddr = new InetSocketAddress(fTargetIp, fTargetPort);
        if (addressBlacklist.isBlacklisted(punchTargetAddr)) {
            VoxLinkMod.LOGGER.info("[Connection] 目标 {}:{} 在黑名单, 跳过UDP打洞", fTargetIp, fTargetPort);
            finalPuncher.close();
            activeHolePunchers.remove("joiner");
            tryConnectionStep(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles, 1);
            return;
        }
        //双EasySym对打
        if (stunProbeResult != null && stunProbeResult.natType.isEasySymmetric() && state.roomInfo.isHostEasySym()) {
            VoxLinkMod.LOGGER.info("两端 EasySym, 启动双方对打 (25 socket × ±20)");
            final UdpHolePuncher dualPuncher = finalPuncher;
            dualPuncher.punchEasySymDual(fTargetIp, fTargetPort, stunProbeResult.natType, StunProbe.NatType.SYMMETRIC_EASY_INC)
                .thenAccept(socket -> {
                    if (roomManager.currentRoom.get() != state || !connectionWon.compareAndSet(false, true)) {
                        try { dualPuncher.close(); } catch (Exception ignored) {}
                        return;
                    }
                    dualPuncher.markSocketTransferred();
                    killAllConnectionAttempts();
                    dualPuncher.stopPunch();
                    InetSocketAddress dualAddr = dualPuncher.getActualRemoteAddress();
                    InetSocketAddress fallbackAddr = dualAddr != null ? dualAddr : punchTargetAddr;
                    scheduler.submit(() -> {
                        try {
                            establishUdpTransport(state, socket, dualPuncher, fallbackAddr, "joiner", false, null);
                        } catch (Exception e) {
                            VoxLinkMod.LOGGER.error("[Connection] EasySym对打transport失败: {}", e.getMessage());
                            dualPuncher.close();
                            showConnectFailedFinal(state);
                        }
                    });
                })
                .exceptionally(e -> {
                    VoxLinkMod.LOGGER.warn("[Connection] EasySym对打失败: {}", e.getMessage());
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
                portRange = UdpHolePuncher.PORT_PREDICTION_MAX_RANGE;
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
        VoxLinkMod.LOGGER.info("[Connection] 打洞模式: cycle={}, portRange={} (hostSym={}, joinerSym={})", cycle, portRange, state.roomInfo.isHostSymmetric(), joinerIsSymmetric);
        if (portRange > 0) {
            VoxLinkMod.LOGGER.info("[Connection] 端口预测(range=+/-{}) (尝试{}, hostSym={}, joinerSym={}, hostNat={})",
                    portRange, attempt, state.roomInfo.isHostSymmetric(), joinerIsSymmetric, state.roomInfo.getNatType());
        }
        int socketCount = 0;
        if (joinerIsSymmetric) {
            socketCount = state.roomInfo.isHostSymmetric() ? HARD_SYM_SOCKET_COUNT : JOINER_SYM_SOCKET_COUNT;
            VoxLinkMod.LOGGER.info("[Connection] joiner对称NAT, 创建{}个多socket打洞", socketCount);
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
                        .exceptionally(e -> { VoxLinkMod.LOGGER.debug("peer_port信号发送失败: {}", e.getMessage()); return null; });
            });
            multiSockets.add(sp);
            activeHolePunchers.put("joiner_ms_" + si, sp);
        }
        if (multiSockets.isEmpty()) {
            VoxLinkMod.LOGGER.info("[Connection] Cone端复用STUN socket打洞 (port={})", puncher.getSocket().getLocalPort());
            puncher.setOnPeerPunchReceived(addr -> {
                String code = state.roomInfo.getCode();
                String token = state.roomInfo.getToken();
                JsonObject portData = new JsonObject();
                portData.addProperty("peer_ip", addr.getAddress().getHostAddress());
                portData.addProperty("peer_port", addr.getPort());
                signalingClient.sendSignal(code, token, false, "peer_port", portData, "host")
                        .exceptionally(e -> { VoxLinkMod.LOGGER.debug("peer_port信号发送失败: {}", e.getMessage()); return null; });
            });
            puncher.punchWithPortPrediction(fTargetIp, fTargetPort, portRange).thenAccept(socket -> {
                if (roomManager.currentRoom.get() != state || !connectionWon.compareAndSet(false, true)) {
                    try { finalPuncher.close(); } catch (Exception ignored) {}
                    return;
                }
                String logTarget = fTargetIp != null && fTargetIp.contains(":") ? "[" + fTargetIp + "]:" + fTargetPort : fTargetIp + ":" + fTargetPort;
                VoxLinkMod.LOGGER.info("[Connection] UDP打洞成功 {} (尝试{})", logTarget, attempt);
                finalPuncher.markSocketTransferred();
                killAllConnectionAttempts();
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
                        VoxLinkMod.LOGGER.error("[Connection] 创建UDP传输失败: {}", e.getMessage());
                        try { puncherRef.close(); } catch (Exception ignored) {}
                        showConnectFailedFinal(state);
                    }
                });
            }).exceptionally(e -> {
                VoxLinkMod.LOGGER.warn("[Connection] UDP打洞失败(周期{}/{}, 尝试{}/{}): {}", displayCycle, maxCycles, attempt, UDP_PUNCH_MAX_ATTEMPTS, e.getMessage());
                finalPuncher.stopPunch();
                if (roomManager.currentRoom.get() != state) {
                    try { finalPuncher.close(); } catch (Exception ignored) {}
                    activeHolePunchers.remove("joiner");
                    connectionCycleActive.set(false);
                    ConnectionHelper.resetConnecting();
                    return null;
                }
                if ("punch stopped".equals(e.getMessage())) {
                    VoxLinkMod.LOGGER.info("[Connection] punch被主动停止，不重试");
                    return null;
                }
                if (activeHolePunchers.get("joiner") != finalPuncher) {
                    VoxLinkMod.LOGGER.info("[Connection] puncher已被替换，不重试");
                    return null;
                }
                //黑名单: 记录UDP失败
                addressBlacklist.recordUdpFailure(punchTargetAddr);
                // 防火墙检测: UDP被阻则跳过所有重试，直接进Wave 2 TCP
                if (e.getCause() instanceof icu.wuhui.voxlink.network.FirewallBlockedException || e instanceof icu.wuhui.voxlink.network.FirewallBlockedException) {
                    VoxLinkMod.LOGGER.warn("[Connection] 防火墙阻止UDP，跳过重试直接进Wave 2 TCP兜底");
                    try { finalPuncher.close(); } catch (Exception ignored) {}
                    activeHolePunchers.remove("joiner");
                    tryConnectionStep(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles, 1);
                    return null;
                }
                if (attempt < UDP_PUNCH_MAX_ATTEMPTS) {
                    long delay = UDP_PUNCH_RETRY_DELAY_MS * (1L << Math.min(attempt - 1, 4));
                    VoxLinkMod.LOGGER.info("[Connection] {}ms后重试UDP打洞(尝试{}/{})", delay, attempt + 1, UDP_PUNCH_MAX_ATTEMPTS);
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
            VoxLinkMod.LOGGER.info("[Connection] EasyTier方式: {}个socket打洞到 {}:{}", multiSockets.size(), fTargetIp, fTargetPort);
            final UdpHolePuncher leadPuncher = multiSockets.get(0);
            activeHolePunchers.put("joiner", leadPuncher);
            leadPuncher.punchMultiSocket(fTargetIp, fTargetPort, multiSockets, multiWon).thenAccept(socket -> {
                if (roomManager.currentRoom.get() != state || !connectionWon.compareAndSet(false, true)) {
                    for (UdpHolePuncher sp : multiSockets) { try { sp.close(); } catch (Exception ignored) {} }
                    return;
                }
                String logTarget = fTargetIp != null && fTargetIp.contains(":") ? "[" + fTargetIp + "]:" + fTargetPort : fTargetIp + ":" + fTargetPort;
                VoxLinkMod.LOGGER.info("[Connection] 多socket打洞成功 {} (尝试{})", logTarget, attempt);
                leadPuncher.markSocketTransferred();
                killAllConnectionAttempts();
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
                        VoxLinkMod.LOGGER.error("[Connection] 创建UDP传输失败: {}", e.getMessage());
                        try { puncherRef.close(); } catch (Exception ignored) {}
                        showConnectFailedFinal(state);
                    }
                });
            }).exceptionally(e -> {
                VoxLinkMod.LOGGER.warn("[Connection] 多socket打洞失败(周期{}/{}, 尝试{}/{}): {}", displayCycle, maxCycles, attempt, UDP_PUNCH_MAX_ATTEMPTS, e.getMessage());
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
                    VoxLinkMod.LOGGER.info("[Connection] punch被主动停止，不重试");
                    return null;
                }
                // 防火墙检测: UDP被阻则跳过所有重试，直接进Wave 2 TCP
                if (e.getCause() instanceof icu.wuhui.voxlink.network.FirewallBlockedException || e instanceof icu.wuhui.voxlink.network.FirewallBlockedException) {
                    VoxLinkMod.LOGGER.warn("[Connection] 防火墙阻止UDP(多socket)，跳过重试直接进Wave 2 TCP兜底");
                    for (UdpHolePuncher sp : multiSockets) { try { sp.close(); } catch (Exception ignored) {} }
                    for (int si = 0; si < multiSockets.size(); si++) activeHolePunchers.remove("joiner_ms_" + si);
                    activeHolePunchers.remove("joiner");
                    tryConnectionStep(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles, 1);
                    return null;
                }
                if (attempt < UDP_PUNCH_MAX_ATTEMPTS) {
                    long delay = UDP_PUNCH_RETRY_DELAY_MS * (1L << Math.min(attempt - 1, 4));
                    VoxLinkMod.LOGGER.info("[Connection] {}ms后重试UDP打洞(尝试{}/{})", delay, attempt + 1, UDP_PUNCH_MAX_ATTEMPTS);
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
            VoxLinkMod.LOGGER.info("[Connection] 已连接，不进下一周期");
            return;
        }
        if (cycle + 1 >= maxCycles) {
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
        int delayIdx = Math.min(cycle, BACKOFF_DELAYS_MS.length - 1);
        long delay = BACKOFF_DELAYS_MS[delayIdx];
        VoxLinkMod.LOGGER.info("[Connection] 周期{}/{}失败，{}秒后重试(退避)", cycle + 1, maxCycles, delay / 1000);
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
        VoxLinkMod.LOGGER.info("[ReversePunch] 并行启动反向打洞");
        state.roomInfo.setConnectionMode(Component.translatable("voxlink.connection.connecting"));
        connectionCycleActive.set(true);

        // 不杀正向打洞！正向和反向并行

        boolean isSymmetric = (stunProbeResult != null && stunProbeResult.natType.isSymmetric());
        boolean isEasySym = (stunProbeResult != null && stunProbeResult.natType.isEasySymmetric());

        if (isSymmetric) {
            int socketCount = isEasySym ? BIRTHDAY_SOCKET_COUNT : HARD_SYM_SOCKET_COUNT;
            VoxLinkMod.LOGGER.info("[ReversePunch] 检测到symmetric NAT — 生日攻击{}个socket（easySym={}）", socketCount, isEasySym);
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

        VoxLinkMod.LOGGER.info("[BirthdayPunch] 并行STUN {}个socket（目标={}：{}, easySym={}）",
                socketCount, fHostMappedIp, fHostMappedPort, isEasySym);

        // 修复6: 使用socket数组复用, 避免每次新建84 socket + 84次STUN
        // 30秒窗口内复用cached数组, STUN只对前4个socket做(取基线), 其余复用结果
        CompletableFuture.supplyAsync(() ->
                getOrCreateUdpArray(socketCount, isEasySym, StunDetector.getAllStunUrls().get(0))
        ).thenAccept(udpArray -> {
            if (roomManager.currentRoom.get() != state) return;

            if (udpArray == null || udpArray.punchers.isEmpty()) {
                VoxLinkMod.LOGGER.error("[BirthdayPunch] 所有STUN查询都失败了");
                showConnectFailedFinal(state);
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
                VoxLinkMod.LOGGER.error("[BirthdayPunch] 无有效mappedAddr");
                showConnectFailedFinal(state);
                return;
            }

            VoxLinkMod.LOGGER.info("[BirthdayPunch] 准备{}个socket，映射端口: {}", birthdayPunchers.size(), mappedPortList);

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
                            VoxLinkMod.LOGGER.error("[BirthdayPunch] 发送reverse_holepunch_offer失败: {}", response.error);
                        }
                    })
                    .exceptionally(e -> {
                        VoxLinkMod.LOGGER.error("[BirthdayPunch] 发送reverse_holepunch_offer失败: {}", e.getMessage());
                        return null;
                    });

            startBirthdayPunchPhase2(state, birthdayPunchers, birthdayKeys,
                    fHostMappedIp, fHostMappedPort, state.roomInfo.isHostSymmetric(), isEasySym);

            // 先发包才能收包
            VoxLinkMod.LOGGER.info("[BirthdayPunch] 立刻开始打洞到 {}:{}（不等reverse_punch_info）",
                    fHostMappedIp, fHostMappedPort);

            scheduler.schedule(() -> {
                if (connectionCycleActive.get() && roomManager.currentRoom.get() == state) {
                    VoxLinkMod.LOGGER.warn("[BirthdayPunch] 超时（未建立连接）");
                    for (UdpHolePuncher p : birthdayPunchers) { try { p.cancel(); p.close(); } catch (Exception ignored) {} }
                    activeHolePunchers.entrySet().removeIf(e -> e.getKey().startsWith("joiner_birthday_"));
                    showConnectFailedFinal(state);
                }
            }, UDP_PUNCH_TIMEOUT_S + EXTRA_TIMEOUT_SEC, TimeUnit.SECONDS);
        });
    }

    public void startSimpleReversePunch(RoomManager.RoomState state) {
        UdpHolePuncher puncher = new UdpHolePuncher();
        try {
            puncher.createSocket();
        } catch (Exception e) {
            VoxLinkMod.LOGGER.error("[ReversePunch] 创建打洞socket失败: {}", e.getMessage());
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
                VoxLinkMod.LOGGER.info("[ReversePunch] Joiner打洞socket STUN: 检测到symmetric（{} vs {}, delta={}）", myMapped1.port(), myMapped2.port(), joinerMappedPortDelta);
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
            VoxLinkMod.LOGGER.warn("[ReversePunch] STUN失败，无法反向打洞");
            puncher.close();
            activeHolePunchers.remove("joiner_reverse");
            return;
        }

        VoxLinkMod.LOGGER.info("[ReversePunch] Joiner映射地址: {}:{}（symmetric={}）", myMappedAddr.ip(), myMappedAddr.port(), joinerSymmetric);

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
                        VoxLinkMod.LOGGER.error("[ReversePunch] 发送reverse_holepunch_offer失败: {}", response.error);
                    }
                })
                .exceptionally(e -> {
                    VoxLinkMod.LOGGER.error("[ReversePunch] 发送reverse_holepunch_offer失败: {}", e.getMessage());
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
            VoxLinkMod.LOGGER.warn("[ReversePunch] 没有主机地址，无法立即打洞");
            return;
        }

        final String fHostMappedIp = hostMappedIp;
        final int fHostMappedPort = hostMappedPort;
        final UdpHolePuncher finalPuncher = puncher;

        int portRange = joinerSymmetric ? 30 : (state.roomInfo.isHostSymmetric() ? UdpHolePuncher.PORT_PREDICTION_MAX_RANGE : 30);
        VoxLinkMod.LOGGER.info("[ReversePunch] 立即打洞到 {}:{}（range=±{}）", fHostMappedIp, fHostMappedPort, portRange);

        puncher.setOnPeerPunchReceived(addr -> {
            String code = state.roomInfo.getCode();
            String token = state.roomInfo.getToken();
            JsonObject portData = new JsonObject();
            portData.addProperty("peer_ip", addr.getAddress().getHostAddress());
            portData.addProperty("peer_port", addr.getPort());
            signalingClient.sendSignal(code, token, false, "peer_port", portData, "host")
                    .exceptionally(e -> { VoxLinkMod.LOGGER.debug("peer_port信号发送失败: {}", e.getMessage()); return null; });
        });

        puncher.punchWithPortPrediction(fHostMappedIp, fHostMappedPort, portRange, true).thenAccept(socket -> {
            if (roomManager.currentRoom.get() != state || !connectionWon.compareAndSet(false, true)) {
                try { finalPuncher.close(); } catch (Exception ignored) {}
                return;
            }
            VoxLinkMod.LOGGER.info("[ReversePunch] Joiner反向打洞成功 {}:{}", fHostMappedIp, fHostMappedPort);
            finalPuncher.markSocketTransferred();
            killAllConnectionAttempts();

            finalPuncher.stopPunch();
            final DatagramSocket punchSocket = socket;
            final UdpHolePuncher puncherRef = finalPuncher;

            // 用实际收到包的地址，而非STUN映射地址（对称NAT端口会偏移）
            InetSocketAddress actualAddr = puncherRef.getActualRemoteAddress();
            if (actualAddr == null) actualAddr = new InetSocketAddress(fHostMappedIp, fHostMappedPort);
            final InetSocketAddress finalTargetAddr = actualAddr;
            VoxLinkMod.LOGGER.info("[ReversePunch] 实际目标地址: {} (STUN映射: {}:{})", finalTargetAddr, fHostMappedIp, fHostMappedPort);

            scheduler.submit(() -> {
                try {
                    establishUdpTransport(state, punchSocket, puncherRef,
                            finalTargetAddr, "joiner", false, null);
                } catch (Exception e) {
                    VoxLinkMod.LOGGER.error("[ReversePunch] Joiner UDP transport创建失败: {}", e.getMessage());
                    try { puncherRef.close(); } catch (Exception ignored) {}
                    showConnectFailedFinal(state);
                }
            });
        }).exceptionally(e -> {
            VoxLinkMod.LOGGER.debug("[ReversePunch] Joiner反向打洞失败(等reverse_punch_info更新目标后重试): {}", e.getMessage());
            // 不调showConnectFailedFinal，等reverse_punch_info更新目标后重试
return null;
        });

        // 反向打洞需覆盖信号投递延迟(~12s)+host STUN(3s), 超时设为30s而非13s
        scheduler.schedule(() -> {
            if (connectionCycleActive.get() && roomManager.currentRoom.get() == state && !connectionWon.get()) {
                VoxLinkMod.LOGGER.warn("[ReversePunch] 反向打洞超时");
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
                VoxLinkMod.LOGGER.info("[BirthdayPunch] Socket #{}收到对端打洞到 {}:{}", idx, addr.getAddress().getHostAddress(), addr.getPort());
            });

            int portRange = PORT_RANGE_DEFAULT;
            if (isEasySym) {
                portRange = EASY_SYM_PORT_RANGE;
            } else if (hostSymmetric) {
                portRange = PORT_RANGE_DEFAULT;
            } else {
                portRange = MIN_PORT_RANGE;
            }
            puncher.punchWithPortPrediction(hostMappedIp, hostMappedPort, portRange, true).thenAccept(socket -> {
                if (connectionWon.get()) {
                    try { puncher.close(); } catch (Exception ignored) {}
                    return;
                }
                if (!won.compareAndSet(false, true)) {
                    try { puncher.close(); } catch (Exception ignored) {}
                    return;
                }
                VoxLinkMod.LOGGER.info("[BirthdayPunch] Socket #{} 赢了！连接到 {}:{}", idx, hostMappedIp, hostMappedPort);

                for (int j = 0; j < birthdayPunchers.size(); j++) {
                    if (j != idx) {
                        try { birthdayPunchers.get(j).cancel(); birthdayPunchers.get(j).close(); } catch (Exception ignored) {}
                        activeHolePunchers.remove(birthdayKeys.get(j));
                    }
                }
                activeHolePunchers.remove(birthdayKeys.get(idx));

                UdpHolePuncher forwardPuncher = activeHolePunchers.remove("joiner");
                if (forwardPuncher != null) { try { forwardPuncher.cancel(); forwardPuncher.close(); } catch (Exception ignored) {} }

                puncher.stopPunch();
                final DatagramSocket punchSocket = socket;
                final UdpHolePuncher puncherRef = puncher;

                scheduler.submit(() -> {
                    try {
                        establishUdpTransport(state, punchSocket, puncherRef,
                                new InetSocketAddress(hostMappedIp, hostMappedPort), "joiner", false, null);
                    } catch (Exception e) {
                        VoxLinkMod.LOGGER.error("[BirthdayPunch] transport创建失败: {}", e.getMessage());
                        try { puncherRef.close(); } catch (Exception ignored) {}
                        showConnectFailedFinal(state);
                    }
                });
            }).exceptionally(e -> {
                VoxLinkMod.LOGGER.debug("[BirthdayPunch] Socket #{} 失败: {}", idx, e.getMessage());
                return null;
            });
        }

        scheduler.schedule(() -> {
            if (!won.get() && connectionCycleActive.get() && roomManager.currentRoom.get() == state) {
                VoxLinkMod.LOGGER.warn("[BirthdayPunch] 全部{}个socket超时", birthdayPunchers.size());
                for (int i = 0; i < birthdayPunchers.size(); i++) {
                    try { birthdayPunchers.get(i).cancel(); birthdayPunchers.get(i).close(); } catch (Exception ignored) {}
                    activeHolePunchers.remove(birthdayKeys.get(i));
                }
                showConnectFailedFinal(state);
            }
        }, UDP_PUNCH_TIMEOUT_S + EXTRA_TIMEOUT_SEC, TimeUnit.SECONDS);
    }

    public void connectViaBridge(RoomManager.RoomState state, ConnectionFallback.ConnectResult result) {
        killAllConnectionAttempts();
        P2PBridge.cancelPendingUdpTimeouts();
        for (ReliableUdpTransport transport : activeUdpTransports.values()) {
            try { transport.close(); } catch (Exception ignored) {}
        }
        activeUdpTransports.clear();
        state.roomInfo.setConnectionMode(Component.translatable("voxlink.connection.connecting"));

        String remoteHost = result.remoteHost;
        int remotePort = result.remotePort;

        if (result.mode == ConnectionMode.IPV6_DIRECT) {
            P2PBridge.connectToHostIpv6(remoteHost, remotePort)
                    .thenAccept(localPort -> {
                        if (localPort > 0) {
                            connectionCycleActive.set(false);
                            signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(),
                                    false, "connected", new JsonObject(), "host");
                            //debounce 桥已建 但MC还没真连上 显示连接中
                            state.roomInfo.setConnectionMode(Component.translatable("voxlink.connection.connecting"));
                            ConnectionHelper.connectToServer(localPort, state.roomInfo);
                        } else {
                            connectionCycleActive.set(false);
                            ConnectionHelper.resetConnecting();
                            state.roomInfo.setConnectionMode(Component.translatable("voxlink.connection.bridge_start_failed"), true);
                            signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(),
                                    false, "disconnect", new JsonObject(), "host");
                            handleConnectViaBridgeFailed(state);
                        }
                    });
        } else {
            P2PBridge.connectToHost(remoteHost, remotePort)
                    .thenAccept(localPort -> {
                        if (localPort > 0) {
                            connectionCycleActive.set(false);
                            signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(),
                                    false, "connected", new JsonObject(), "host");
                            //debounce 桥已建 但MC还没真连上 显示连接中
                            state.roomInfo.setConnectionMode(Component.translatable("voxlink.connection.connecting"));
                            ConnectionHelper.connectToServer(localPort, state.roomInfo);
                        } else {
                            connectionCycleActive.set(false);
                            ConnectionHelper.resetConnecting();
                            state.roomInfo.setConnectionMode(Component.translatable("voxlink.connection.bridge_start_failed"), true);
                            signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(),
                                    false, "disconnect", new JsonObject(), "host");
                            handleConnectViaBridgeFailed(state);
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
            VoxLinkMod.LOGGER.warn("调度器已关闭，同步执行leaveRoom");
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
                VoxLinkMod.LOGGER.warn("回退监控超时（约60秒）");
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

    public void showConnectFailed(RoomManager.RoomState state) {
        if (VoxLinkMod.getConfig().isRelayEnabled() && !state.roomInfo.isConnectionAttemptFailed()) {
            state.roomInfo.setConnectionAttemptFailed(true);
            boolean hostSym = state.roomInfo.isHostSymmetric() || (stunProbeResult != null && stunProbeResult.natType.isSymmetric());
            if (hostSym) {
                VoxLinkMod.LOGGER.info("[Connection] 房主对称NAT, 尝试P2P中继");
                state.roomInfo.setConnectionMode(Component.translatable("voxlink.relay.trying"));
                tryRelay(state);
                return;
            }
        }
        showConnectFailedFinal(state);
    }

    private void tryRelay(RoomManager.RoomState state) {
        // 防止并发重复relay
        if (currentRelayPeer.get() != null) {
            VoxLinkMod.LOGGER.info("[Relay] relay已在尝试中 (current={})，跳过重复", currentRelayPeer.get());
            return;
        }
        if (state.roomInfo.isHost()) {
            java.util.List<RoomInfo.PeerInfo> candidates = new java.util.ArrayList<>();
            for (RoomInfo.PeerInfo p : state.roomInfo.getPeers()) {
                String nt = p.natType;
                if (nt == null || p.mappedIp == null || p.mappedPort <= 0) continue;
                if (failedRelayPeers.contains(p.clientId)) continue;
                if (!nt.contains("sym") && !nt.contains("strict") && !nt.equals("unknown")) {
                    candidates.add(p);
                }
            }
            if (candidates.isEmpty()) {
                VoxLinkMod.LOGGER.info("[Relay] 无可用的Cone中继节点 (已排除失败={})", failedRelayPeers.size());
                failedRelayPeers.clear();
                showConnectFailedFinal(state);
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
                    symPeer = p;
                    break;
                }
            }
            if (symPeer == null || symPeer.mappedIp == null) {
                VoxLinkMod.LOGGER.warn("[Relay] 找不到需要中继的对称NAT玩家");
                showConnectFailedFinal(state);
                return;
            }
            currentRelayPeer.set(relayCandidates.get(0).clientId);
            VoxLinkMod.LOGGER.info("[Relay] 并行尝试{}个Cone中继, 目标Sym={}", parallelN, symPeer.clientId);
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
                VoxLinkMod.LOGGER.info("[Relay] 并发#{} relay={} (负载={}) {}:{}", i + 1, relay.clientId,
                        relayBridge.getRelayCountForPeer(relay.clientId), relay.mappedIp, relay.mappedPort);
            }
            //并行超时: 8s内无任一成功则全部标记失败
            relayFailoverTask = scheduler.schedule(() -> {
                if (!connectionWon.get() && roomManager.currentRoom.get() == state) {
                    VoxLinkMod.LOGGER.warn("[Relay] 并行中继全部超时, 标记{}个失败", parallelN);
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
                    showConnectFailedFinal(state);
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
        VoxLinkMod.LOGGER.info("[Relay] 收到relay_request, 请求者={}", requestingClientId);
        // 房主在world中，聊天栏提示
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(
                Component.translatable("voxlink.relay.host_notice"), false);
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
                VoxLinkMod.LOGGER.info("[Relay] 收到relay_notify(connected), 中继已就绪");
            clearRelayTracking();
            connectionWon.set(true);
            state.roomInfo.setConnectionMode(Component.translatable("voxlink.relay.connected_via"));
            return;
        }
        String relayIp = data.has("relayIp") ? data.get("relayIp").getAsString() : null;
        int relayPort = data.has("relayPort") ? data.get("relayPort").getAsInt() : 0;
        if (relayIp == null || relayPort <= 0) return;
        VoxLinkMod.LOGGER.info("[Relay] 收到relay_notify, 打洞到Cone {}:{}", relayIp, relayPort);
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
                .thenAccept(socket -> {
                    if (!relayWon.compareAndSet(false, true)) {
                        try { rp.close(); } catch (Exception ignored) {}
                        return;
                    }
                    if (!connectionWon.compareAndSet(false, true)) {
                        try { rp.close(); } catch (Exception ignored) {}
                        return;
                    }
                    VoxLinkMod.LOGGER.info("[Relay] Sym→Cone socket#{} 打洞成功", idx);
                    rp.markSocketTransferred();
                    killAllConnectionAttempts();
                    rp.stopPunch();
                    for (UdpHolePuncher op : relayPunchers) {
                        if (op != rp) { try { op.cancel(); op.close(); } catch (Exception ignored) {} }
                    }
                    ReliableUdpTransport transport = new ReliableUdpTransport(socket, new java.net.InetSocketAddress(fRelayIp, fRelayPort));
                    activeUdpTransports.put("relay_cone", transport);
                    transport.start();
                    state.roomInfo.setConnectionMode(Component.translatable("voxlink.relay.connected_via"));
                    startUdpPunchBridge(state, transport);
                    scheduler.schedule(() -> {
                        Minecraft mc = Minecraft.getInstance();
                        if (mc.player != null) {
                            mc.player.displayClientMessage(
                                Component.translatable("voxlink.relay.connected_via"), false);
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
                VoxLinkMod.LOGGER.warn("[Relay] Sym→Cone relay打洞超时(8s)");
                for (UdpHolePuncher op : relayPunchers) {
                    try { op.cancel(); op.close(); } catch (Exception ignored) {}
                }
                activeHolePunchers.entrySet().removeIf(e -> e.getKey().startsWith("relay_to_cone_"));
                showConnectFailedFinal(state);
            }
        }, SHORT_TIMEOUT_SEC, TimeUnit.SECONDS);
    }

    public void handleRelayDeclined(String from, JsonObject data) {
        clearRelayTracking();
        RoomManager.RoomState state = roomManager.currentRoom.get();
        if (state != null && state != RoomManager.PENDING) {
            showConnectFailedFinal(state);
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
                .thenAccept(socket -> {
                    if (!coneWon.compareAndSet(false, true)) {
                        try { cp.close(); } catch (Exception ignored) {}
                        return;
                    }
                    VoxLinkMod.LOGGER.info("[Relay] Cone→Sym socket#{} 打洞成功", idx);
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
                VoxLinkMod.LOGGER.warn("[Relay] Cone→Sym relay打洞超时(8s)");
                for (UdpHolePuncher op : conePunchers) {
                    try { op.cancel(); op.close(); } catch (Exception ignored) {}
                }
                activeHolePunchers.entrySet().removeIf(e -> e.getKey().startsWith("relay_to_sym_"));
                signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), false, "relay_declined", new JsonObject(), "host");
            }
        }, SHORT_TIMEOUT_SEC, TimeUnit.SECONDS);
    }

    public void onRelayDisconnected(String peerA, String peerB) {
        VoxLinkMod.LOGGER.warn("[Relay] 中继断开通知: {}<->{}", peerA, peerB);
        RoomManager.RoomState state = roomManager.currentRoom.get();
        if (state == null || state == RoomManager.PENDING || connectionWon.get()) return;
        if (currentRelayPeer.get() != null) {
            failedRelayPeers.add(currentRelayPeer.get());
        }
        clearRelayTracking();
        scheduler.schedule(() -> {
            if (roomManager.currentRoom.get() == state && !connectionWon.get()) {
                VoxLinkMod.LOGGER.info("[Relay] 自动切换备选中继...");
                tryRelay(state);
            }
        }, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    public void showConnectFailedFinal(RoomManager.RoomState state) {
        clearRelayTracking();
        failedRelayPeers.clear();
        if (connectionWon.get()) {
            VoxLinkMod.LOGGER.info("[Connection] 已连接，忽略showConnectFailedFinal");
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
                    mc.player.displayClientMessage(
                            Component.translatable("voxlink.chat.error_prefix")
                                    .append(Component.translatable("voxlink.connection.all_failed")), true);
                    if (state.roomInfo.isSameCgnat()) {
                        mc.player.displayClientMessage(
                                Component.translatable("voxlink.chat.same_cgnat_warning"), false);
                    }
                }
                try {
                    scheduler.execute(() -> roomManager.leaveRoom());
                } catch (java.util.concurrent.RejectedExecutionException ex) {
                    VoxLinkMod.LOGGER.warn("调度器已关闭，同步执行leaveRoom");
                    roomManager.leaveRoom();
                }
            });
        }
    }

    public void killAllConnectionAttempts() {
        connectionCycleActive.set(false);
        connectionWon.set(true);
        ConnectionState.reset();
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

    //双P2P: 按reason杀对应通道
    public void killAllConnectionAttempts(String reason) {
        if (reason == null) { killAllConnectionAttempts(); return; }
        switch (reason) {
            case "voxlink" -> killAllConnectionAttempts();
            case "terracotta" -> {
                try { TerracottaManager.setIdle(); }
                catch (Exception e) { VoxLinkMod.LOGGER.warn("[DualP2P] 陶瓦 setIdle 失败: {}", e.getMessage()); }
            }
            default -> killAllConnectionAttempts();
        }
        VoxLinkMod.LOGGER.info("[DualP2P] 终止 {} 侧连接尝试", reason);
    }

    //双P2P编排: 房间码路由 + 并行竞速
    public CompletableFuture<Void> startDualP2P(String roomCode, String playerName, String password,
                                                    java.util.function.BiConsumer<String, String> statusCallback) {
        if (RoomCodeRouter.isTerracottaCode(roomCode)) {
            //U/ 前缀 -> 仅 Terracotta 等待guest-ok后连接MC
            statusCallback.accept("terracotta", "voxlink.attempting_join.joining");
            return TerracottaManager.joinRoom(roomCode, playerName)
                .thenAccept(connectUrl -> {
                    //debounce 陶瓦guest-ok成功 用connectUrl连接MC
                    connectTerracottaToMC(connectUrl, roomCode);
                    statusCallback.accept("terracotta", "voxlink.connection.connecting");
                });
        }
        if (!RoomCodeRouter.isVoxLinkCode(roomCode)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                Component.translatable("voxlink.error.invalid_room_code").getString()));
        }
        //6位码 -> VoxLink P2P
        if (!TerracottaManager.isBinaryReady() || !VoxLinkMod.getConfig().isParallelP2P()) {
            //仅 VoxLink P2P
            statusCallback.accept("voxlink", "voxlink.connection.connecting");
            return startVoxLinkP2P(roomCode, password);
        }
        //双P2P并行
        statusCallback.accept("voxlink", "voxlink.connection.connecting");
        statusCallback.accept("terracotta", "voxlink.attempting_join.joining");
        //CAS守卫
        java.util.concurrent.atomic.AtomicBoolean won = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicInteger failed = new java.util.concurrent.atomic.AtomicInteger(0);
        CompletableFuture<Void> dualResult = new CompletableFuture<>();
        startVoxLinkP2P(roomCode, password)
            .whenComplete((r, e) -> {
                if (e == null) {
                    if (won.compareAndSet(false, true)) {
                        killAllConnectionAttempts("terracotta");
                        statusCallback.accept("voxlink", "voxlink.dual.p2p_established");
                        statusCallback.accept("terracotta", "voxlink.dual.status_cancelled");
                        dualResult.complete(null);
                    }
                } else if (!won.get()) {
                    statusCallback.accept("voxlink", "voxlink.dual.channel_failed");
                    if (failed.incrementAndGet() >= 2) dualResult.completeExceptionally(e);
                }
            });
        TerracottaManager.joinRoom(roomCode, playerName)
            .whenComplete((connectUrl, e) -> {
                if (e == null) {
                    if (won.compareAndSet(false, true)) {
                        killAllConnectionAttempts("voxlink");
                        //debounce 陶瓦guest-ok成功 用connectUrl连接MC
                        connectTerracottaToMC(connectUrl, roomCode);
                        statusCallback.accept("terracotta", "voxlink.dual.p2p_established");
                        statusCallback.accept("voxlink", "voxlink.dual.status_cancelled");
                        dualResult.complete(null);
                    }
                } else if (!won.get()) {
                    statusCallback.accept("terracotta", "voxlink.dual.channel_failed");
                    if (failed.incrementAndGet() >= 2) dualResult.completeExceptionally(e);
                }
            });
        return dualResult;
    }

    //debounce 解析陶瓦connectUrl端口并连接MC
    private void connectTerracottaToMC(String connectUrl, String roomCode) {
        if (connectUrl == null || connectUrl.isEmpty()) {
            VoxLinkMod.LOGGER.warn("[DualP2P] 陶瓦connectUrl为空 无法连接MC");
            return;
        }
        int localPort = parsePortFromUrl(connectUrl);
        if (localPort <= 0) {
            VoxLinkMod.LOGGER.warn("[DualP2P] 陶瓦connectUrl解析端口失败: {}", connectUrl);
            return;
        }
        RoomInfo roomInfo = roomManager.getCurrentRoom();
        if (roomInfo == null) {
            roomInfo = roomManager.setupTerracottaGuestRoom(roomCode);
        }
        //debounce 陶瓦guest-ok 桥已建 但MC还没真连上 显示连接中
        ConnectionState.transitionTo(ConnectionState.CONNECTED, "陶瓦guest-ok port=" + localPort);
        roomInfo.setConnectionMode(Component.translatable("voxlink.connection.connecting"));
        ConnectionHelper.connectToServer(localPort, roomInfo);
        VoxLinkMod.LOGGER.info("[DualP2P] 陶瓦连接MC port={}", localPort);
    }

    private static int parsePortFromUrl(String url) {
        if (url == null) return -1;
        int colonIdx = url.lastIndexOf(':');
        if (colonIdx < 0) return -1;
        try {
            return Integer.parseInt(url.substring(colonIdx + 1).trim());
        } catch (NumberFormatException e) {
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
            VoxLinkMod.LOGGER.debug("连接失败时发送disconnect失败: {}", e.getMessage());
        }
    }

    public void startUdpPunchBridge(RoomManager.RoomState state, ReliableUdpTransport transport) {
        int localPort = P2PBridge.startUdpJoinerBridge(transport);
        if (localPort > 0) {
            connectionCycleActive.set(false);
            ConnectionState.transitionTo(ConnectionState.CONNECTED, "Joiner桥接建立 port=" + localPort);
            //debounce 桥已建 但MC还没真连上 显示连接中
            state.roomInfo.setConnectionMode(Component.translatable("voxlink.connection.connecting"));
            ConnectionHelper.connectToServer(localPort, state.roomInfo);
        } else {
            connectionCycleActive.set(false);
            ConnectionHelper.resetConnecting();
            ConnectionState.transitionTo(ConnectionState.FAILED, "桥接启动失败");
            state.roomInfo.setConnectionMode(Component.translatable("voxlink.connection.bridge_start_failed"), true);
            sendDisconnectOnFailure(state);
            scheduler.execute(() -> {
                if (roomManager.currentRoom.get() == state && state != RoomManager.PENDING) {
                    roomManager.leaveRoom();
                }
            });
        }
    }

    public void startHostUdpPunchBridge(RoomManager.RoomState state, String clientId, ReliableUdpTransport transport) {
        int mcPort = state.roomInfo.getHostPort();
        ConnectionState.transitionTo(ConnectionState.CONNECTED, "Host桥接建立 client=" + clientId);
        state.roomInfo.setConnectionMode(Component.translatable("voxlink.connection.connected"));
        P2PBridge.startUdpHostBridgeForClient(clientId, transport, mcPort, () -> {
            ReliableUdpTransport t = activeUdpTransports.remove(clientId);
            if (t != null) {
                try { t.close(); } catch (Exception ignored) {}
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
            VoxLinkMod.LOGGER.warn("[Transport] 确认包发送失败: {}", e.getMessage());
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
        if (punchExecutor != null && !punchExecutor.isShutdown()) {
            punchExecutor.shutdown();
            try { punchExecutor.awaitTermination(AWAIT_TERM_SEC, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        }
        clearRelayTracking();
        failedRelayPeers.clear();
    }
}
