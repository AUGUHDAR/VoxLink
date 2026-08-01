package icu.wuhui.voxlink.room;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import icu.wuhui.voxlink.config.VoxLinkConfig;
import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.compat.GeyserCompat;
import icu.wuhui.voxlink.compat.ViaCompat;
import icu.wuhui.voxlink.network.ConnectionFallback;
import icu.wuhui.voxlink.network.ConnectionFallback.ConnectionMode;
import icu.wuhui.voxlink.network.ConnectionHelper;
import icu.wuhui.voxlink.network.P2PBridge;
import icu.wuhui.voxlink.network.SignalingClient;
import icu.wuhui.voxlink.network.StunProbe;
import icu.wuhui.voxlink.network.UPnPManager;
import icu.wuhui.voxlink.network.TopologyClient;
import icu.wuhui.voxlink.network.UdpHolePuncher;
import icu.wuhui.voxlink.network.ReliableUdpTransport;
import icu.wuhui.voxlink.terracotta.TerracottaManager;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;


public class RoomManager {
    private final SignalingClient signalingClient;
    private final TopologyClient topologyClient;
    private final ScheduledExecutorService scheduler;
    private final ConnectionManager connectionManager;
    final AtomicReference<RoomState> currentRoom = new AtomicReference<>(null);
    private volatile ScheduledFuture<?> heartbeatFuture;
    private volatile ScheduledFuture<?> signalPollFuture;
    private final java.util.concurrent.atomic.AtomicBoolean signalPollInFlight = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final AtomicInteger heartbeatFailCount = new AtomicInteger(0);
    private static final int MAX_HEARTBEAT_FAILS = 8;
    private static final long MIN_HEARTBEAT_INTERVAL = 5;
    private static final int CREATE_ROOM_TIMEOUT_SECONDS = 120;
    private static final int JOIN_ROOM_TIMEOUT_SECONDS = 90;
    private static final int INITIAL_SIGNAL_POLL_MS = 200;
    private static final int BACKOFF_MULTIPLIER = 2;
    private static final int MAX_SIGNAL_POLL_MS = 10000;
    private static final int JOINER_SIGNAL_POLL_MS = 200;
    private static final int NAT_UPDATE_DELAY_SEC = 2;
    private volatile long currentHeartbeatInterval;
    private volatile long currentSignalPollInterval;
    private final AtomicLong signalPollTimestamp = new AtomicLong(0);
    private final AtomicInteger heartbeatSeq = new AtomicInteger(0);
    private final AtomicInteger heartbeatGeneration = new AtomicInteger(0);
    final AtomicBoolean roomLostHandled = new AtomicBoolean(false);
    volatile boolean intentionalLeave = false;
    private volatile CompletableFuture<?> pendingCreateFuture;

    private final AtomicInteger pollCount = new AtomicInteger(0);

    static final RoomState PENDING = new RoomState(null);

    public RoomManager(SignalingClient signalingClient, TopologyClient topologyClient) {
        this.signalingClient = signalingClient;
        this.topologyClient = topologyClient;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(() -> {
                try {
                    r.run();
                } catch (Throwable e) {
                    VoxLinkMod.LOGGER.error("Scheduled task threw uncaught exception", e);
                }
            }, "VoxLink-RoomManager");
            t.setDaemon(true);
            return t;
        });
        this.connectionManager = new ConnectionManager(this, signalingClient, this.scheduler);
    }

    public void shutdown() {
        connectionManager.setConnectionCycleActive(false);
        ConnectionHelper.resetConnecting();
        //debounce 重置ConnectionState 防止dev热加载下次建房UI读到stale状态
        ConnectionState.reset();
        connectionManager.shutdown();
        stopScheduledTasks();
        scheduler.shutdownNow();
    }

    private void cancelPendingCreate() {
        CompletableFuture<?> f = pendingCreateFuture;
        if (f != null && !f.isDone()) {
            f.cancel(true);
            pendingCreateFuture = null;
        }
    }

    private static final Set<String> TRANSIENT_ERRORS = java.util.Set.of(
            "NETWORK_ERROR", "CDN_ERROR", "RATE_LIMITED"
    );

    private static final String GAME_VERSION = icu.wuhui.voxlink.VoxLinkConstants.GAME_VERSION;

    public CompletableFuture<RoomInfo> createRoom(String name, String password, int maxPlayers, int hostPort, boolean visible, String authType, String category) {
        if (!currentRoom.compareAndSet(null, PENDING)) {
            return CompletableFuture.failedFuture(new IllegalStateException(Component.translatable("voxlink.error.already_in_room_or_pending").getString()));
        }

        //debounce 先杀残留陶瓦 防止上次退出残留导致建房失败
        try { TerracottaManager.shutdown(); }
        catch (Exception e) { VoxLinkMod.LOGGER.warn("Failed to stop Terracotta before create room: {}", e.getMessage()); }

        int protocolVersion = ViaCompat.isViaLoaded() ? ViaCompat.getServerProtocolVersion() : 0;
        int peerPort = icu.wuhui.voxlink.network.PeerServer.getPort();

        VoxLinkMod.LOGGER.info("[createRoom] Instant create (NAT probe deferred)");
        CompletableFuture<NatResult> natFuture = CompletableFuture.completedFuture(
            new NatResult("unknown", hostPort, -1)
        );

        CompletableFuture.runAsync(() -> {
            try {
                VoxLinkMod.LOGGER.info("[createRoom] Background NAT probe started");
                String natType = "unknown";
                int effectivePort = hostPort;
                int geyserPort = -1;

                if (VoxLinkMod.getConfig().isAutoUPnP()) {
                    UPnPManager.UPnPResult upnpResult = UPnPManager.openPort(hostPort, name);
                    UPnPManager.UPnPResult upnpUdpResult = UPnPManager.openUdpPort(hostPort, name + "-UDP");
                    if (upnpResult.success()) {
                        natType = "open";
                        effectivePort = upnpResult.externalPort();
                    } else if (upnpUdpResult.success()) {
                        natType = "open";
                        effectivePort = upnpUdpResult.externalPort();
                    } else if (upnpResult.available() || upnpUdpResult.available()) {
                        natType = "moderate";
                    } else {
                        StunProbe.NatType stunNat = StunProbe.probeNatType(StunDetector.getStunServerGroups());
                        natType = (stunNat != null && stunNat.isSymmetric()) ? stunNat.key : "strict";
                    }
                    if (GeyserCompat.isGeyserLoaded()) {
                        int bedrockPort = GeyserCompat.getBedrockPort();
                        UPnPManager.UPnPResult geyserUpnp = UPnPManager.openUdpPort(bedrockPort, name + "-Bedrock");
                        geyserPort = geyserUpnp.success() ? geyserUpnp.externalPort() : bedrockPort;
                    }
                }
                if ("unknown".equals(natType) || "strict".equals(natType)) {
                    try {
                        StunProbe.ProbeResult probeResult = StunProbe.probeAsync(StunDetector.getStunServerGroups()).join();
                        if (probeResult != null && probeResult.natType != null) {
                            natType = probeResult.natType.key;
                            connectionManager.setStunProbeResult(probeResult);
                            VoxLinkMod.LOGGER.info("[createRoom] STUN probe result saved: NAT={}, reachable={}", natType, probeResult.reachableStunUrls.size());
                        }
                    } catch (Exception ex2) {
                        VoxLinkMod.LOGGER.warn("[createRoom] probeAsync failed: {}", ex2.getMessage());
                    }
                }
                VoxLinkMod.LOGGER.info("[createRoom] Background NAT probe done: natType={}, port={}", natType, effectivePort);

                RoomState state = currentRoom.get();
                if (state == null || state == PENDING || state.roomInfo == null) {
                    VoxLinkMod.LOGGER.info("[createRoom] Room not ready, retry NAT update in 2s");
                    final String finalNatType = natType;
                    scheduler.schedule(() -> {
                        RoomState st = currentRoom.get();
                        if (st != null && st != PENDING && st.roomInfo != null) {
                            st.roomInfo.setNatType(finalNatType);
                            VoxLinkMod.LOGGER.info("[createRoom] Delayed NAT type update: {}", finalNatType);
                            try {
                                signalingClient.updateRoom(st.roomInfo.getCode(), st.roomInfo.getToken(),
                                    st.roomInfo.getName(), null, st.roomInfo.getMaxPlayers(),
                                    st.roomInfo.isVisible(), st.roomInfo.getAuthType(), st.roomInfo.getCategory());
                            } catch (Exception e) {
                                VoxLinkMod.LOGGER.warn("[createRoom] Delayed NAT type update failed: {}", e.getMessage());
                            }
                        }
                    }, NAT_UPDATE_DELAY_SEC, TimeUnit.SECONDS);
                } else {
                    state.roomInfo.setNatType(natType);
try {
                        signalingClient.updateRoom(state.roomInfo.getCode(), state.roomInfo.getToken(),
                            state.roomInfo.getName(), null, state.roomInfo.getMaxPlayers(),
                            state.roomInfo.isVisible(), state.roomInfo.getAuthType(), state.roomInfo.getCategory());
                        VoxLinkMod.LOGGER.info("[createRoom] NAT type updated: {}", natType);
                    } catch (Exception e) {
                        VoxLinkMod.LOGGER.warn("[createRoom] NAT type update failed: {}", e.getMessage());
                    }
                }
            } catch (Exception e) {
                VoxLinkMod.LOGGER.warn("[createRoom] Background NAT probe failed: {}", e.getMessage());
            }
        });

        CompletableFuture<SignalingClient.ApiResponse> ipFuture = signalingClient.getPublicIp()
                .exceptionally(e -> {
                    VoxLinkMod.LOGGER.warn("[createRoom] Public IP fetch failed: {}, continue without IP", e.getMessage());
                    return new SignalingClient.ApiResponse(false, null, null, null);
                });

        CompletableFuture<RoomInfo> future = natFuture.thenCombine(ipFuture, (ctx, ipResponse) -> {
            String hostIp = null;
            String hostIpv6 = null;
            if (ipResponse.success && ipResponse.data != null) {
                if (ipResponse.data.has("ip") && !ipResponse.data.get("ip").isJsonNull()) {
                    hostIp = ipResponse.data.get("ip").getAsString();
                }
                if (ipResponse.data.has("ipv6") && !ipResponse.data.get("ipv6").isJsonNull()) {
                    hostIpv6 = ipResponse.data.get("ipv6").getAsString();
                }
            }
            VoxLinkMod.LOGGER.info("[createRoom] NAT+IP ready: nat={}, ip={}, ipv6={}", ctx.nat, hostIp, hostIpv6);
            if ((hostIpv6 == null || hostIpv6.isEmpty()) && StunDetector.verifyIPv6Connectivity()) {
                hostIpv6 = ConnectionFallback.getLocalGlobalIpv6();
                if (hostIpv6 != null) {
                    VoxLinkMod.LOGGER.info("[createRoom] Server returned no IPv6, using local global IPv6: {} (reachability unverified)", hostIpv6);
                }
            }
            return new CreateRoomResult(ctx, null, hostIp, hostIpv6);
        }).thenCompose(result -> {
            final String finalHostIp = result.hostIp;
            final String finalHostIpv6 = result.hostIpv6;
            final NatResult ctx = result.natResult;
            VoxLinkMod.LOGGER.info("[createRoom] Step 2: call API to create room");
            return signalingClient.createRoom(name, (password != null && !password.isEmpty()) ? password : null, maxPlayers, ctx.port, ctx.nat, ctx.geyserPort, visible, authType, category, protocolVersion, peerPort, finalHostIpv6, GAME_VERSION)
                    .thenApply(response -> {
                        VoxLinkMod.LOGGER.info("[createRoom] Step 2 done: success={}", response.success);
                        return new CreateRoomResult(ctx, response, finalHostIp, finalHostIpv6);
                    });
        }).thenApply(result -> {
            NatResult ctx = result.natResult;
            SignalingClient.ApiResponse response = result.apiResponse;
            if (!response.success) {
                if (TRANSIENT_ERRORS.contains(response.error)) {
                    currentRoom.compareAndSet(PENDING, null);
                    throw new TransientException(response.error + ": " + response.message);
                }
                currentRoom.compareAndSet(PENDING, null);
                String errMsg = response.error != null ? response.error : (response.message != null ? response.message : Component.translatable("voxlink.error.unknown").getString());
                if (response.message != null && !response.message.equals(response.error)) {
                    errMsg = response.error + ": " + response.message;
                }
                if ("QUEUED".equals(response.error) && response.queuePosition > 0) {
                    errMsg = "QUEUED:" + response.queuePosition;
                }
                throw new RuntimeException(errMsg);
            }
            if (response.data == null) {
                currentRoom.compareAndSet(PENDING, null);
                throw new RuntimeException(Component.translatable("voxlink.error.server_response_abnormal").getString());
            }
            String code = response.data.has("code") ? response.data.get("code").getAsString() : "";
            String hostToken = response.data.has("hostToken") ? response.data.get("hostToken").getAsString() : "";
            if (response.data.has("expiresIn") && !response.data.get("expiresIn").isJsonNull()) {
                long expiresIn = response.data.get("expiresIn").getAsLong();
                VoxLinkMod.LOGGER.info("Room created, expires in {}s", expiresIn);
            }

            RoomInfo roomInfo = new RoomInfo(code, name, password != null && !password.isEmpty(), maxPlayers, hostToken, true, ctx.port, ctx.nat);
            roomInfo.setHostIp(result.hostIp);
            roomInfo.setHostIpv6(result.hostIpv6);
            roomInfo.setBedrockPort(ctx.geyserPort > 0 ? ctx.geyserPort : -1);
            roomInfo.setCategory(category);
            roomInfo.setVisible(visible);
            RoomState state = new RoomState(roomInfo);
            if (!currentRoom.compareAndSet(PENDING, state)) {
                VoxLinkMod.LOGGER.warn("[createRoom] State cleared (timeout?), discard late result");
                return null;
            }

            if (response.data.has("nameApproved") && !response.data.get("nameApproved").isJsonNull()
                    && !response.data.get("nameApproved").getAsBoolean()) {
                roomInfo.setNameApproved(false);
            }

            intentionalLeave = false;
            roomLostHandled.set(false);
            heartbeatFailCount.set(0);
            startHeartbeat();
            startSignalPoll();

            String hostId = "host_" + code;
            topologyClient.onRoomJoined(code, hostToken, true, hostId, 0);

            try {
                int bridgePort = P2PBridge.startHostBridge(ctx.port).get(5, java.util.concurrent.TimeUnit.SECONDS);
                if (bridgePort > 0) {
                    VoxLinkMod.LOGGER.info("Host bridge started port={}, MC port: {}", bridgePort, ctx.port);
                } else {
                    VoxLinkMod.LOGGER.warn("Host bridge start failed, client needs direct MC port {}", ctx.port);
                }
            } catch (Exception e) {
                VoxLinkMod.LOGGER.warn("Host bridge start exception: {}", e.getMessage());
            }

            if (TerracottaManager.isBinaryReady()) {
                String tpName = Minecraft.getInstance().getUser().getName();
                TerracottaManager.createRoom(tpName)
                    .thenAccept(tc -> {
                        //debounce 房间已离开 关闭后台Terracotta进程避免泄漏
                        if (currentRoom.get() != state) {
                            VoxLinkMod.LOGGER.info("Terracotta room left, closing background process");
                            TerracottaManager.shutdown();
                            return;
                        }
                        roomInfo.setTerracottaCode(tc);
                        VoxLinkMod.LOGGER.info("Terracotta code: {}", tc);
                        //debounce 上传陶瓦房间号到服务器 joiner从joinRoom响应获取
                        signalingClient.updateTerracottaCode(roomInfo.getCode(), roomInfo.getToken(), tc)
                                .thenAccept(r -> {
                                    if (r.success) {
                                        VoxLinkMod.LOGGER.info("Terracotta code uploaded to server");
                                    } else {
                                        VoxLinkMod.LOGGER.warn("Terracotta code upload failed: {}", r.error);
                                    }
                                })
                                .exceptionally(e -> {
                                    VoxLinkMod.LOGGER.warn("Terracotta code upload exception: {}", e.getMessage());
                                    return null;
                                });
                        Minecraft.getInstance().execute(() -> {
                            Minecraft mc = Minecraft.getInstance();
                            if (mc.player != null) {
                                //debounce 不显示明文 只显示标签点击复制
                                mc.player.displayClientMessage(
                                    Component.translatable("voxlink.chat.terracotta_code_label", "")
                                        .append(Component.literal(ChatFormatting.AQUA.toString() + ChatFormatting.BOLD.toString()
                                                + "[" + Component.translatable("voxlink.chat.click_to_copy").getString() + "]")
                                            .withStyle(icu.wuhui.voxlink.ui.ChatCompat.styleWithCopy(tc,
                                                Component.translatable("voxlink.chat.click_to_copy")))), false);
                            }
                        });
                    })
                    .exceptionally(e -> {
                        VoxLinkMod.LOGGER.warn("Terracotta create failed, using VoxLink code only: {}", e.getMessage());
                        //debounce 陶瓦建房失败杀残留进程 防状态污染下次加入
                        try { icu.wuhui.voxlink.terracotta.TerracottaManager.shutdown(); } catch (Exception ex) { VoxLinkMod.LOGGER.debug("cleanup terracotta error: {}", ex.getMessage()); }
                        return null;
                    });
            }

            java.util.concurrent.atomic.AtomicReference<Boolean> ipv4Result = new java.util.concurrent.atomic.AtomicReference<>(null);
            java.util.concurrent.atomic.AtomicReference<Boolean> ipv6Result = new java.util.concurrent.atomic.AtomicReference<>(null);
            int checkCount = 0;
            if (result.hostIp != null && !result.hostIp.isEmpty()) {
                checkCount++;
            }
            if (result.hostIpv6 != null && !result.hostIpv6.isEmpty()) {
                checkCount++;
            }
            final int totalChecks = checkCount;
            final java.util.concurrent.atomic.AtomicInteger completedChecks = new java.util.concurrent.atomic.AtomicInteger(0);

            if (result.hostIp != null && !result.hostIp.isEmpty()) {
                roomInfo.setIpv4Status(RoomInfo.PortStatus.UNKNOWN);
                final String fIpv4 = result.hostIp;
                signalingClient.checkPortReachable(result.hostIp, ctx.port).thenAccept(checkResp -> {
                    boolean reachable = checkResp.success && checkResp.data != null
                            && checkResp.data.has("reachable") && checkResp.data.get("reachable").getAsBoolean();
                    roomInfo.setIpv4Status(reachable ? RoomInfo.PortStatus.REACHABLE : RoomInfo.PortStatus.UNREACHABLE);
                    VoxLinkMod.LOGGER.info("IPv4 port check: {}:{} = {}", fIpv4, ctx.port, reachable);
                    ipv4Result.set(reachable);
                    if (completedChecks.incrementAndGet() == totalChecks) {
                        warnPortBlockedCombined(ipv4Result.get(), ipv6Result.get(), fIpv4, result.hostIpv6);
                    }
                });
            } else {
                roomInfo.setIpv4Status(RoomInfo.PortStatus.NO_ADDRESS);
            }
            if (result.hostIpv6 != null && !result.hostIpv6.isEmpty()) {
                roomInfo.setIpv6Status(RoomInfo.PortStatus.UNKNOWN);
                final String fIpv6 = result.hostIpv6;
                signalingClient.checkPortReachable(result.hostIpv6, ctx.port).thenAccept(checkResp -> {
                    boolean reachable = checkResp.success && checkResp.data != null
                            && checkResp.data.has("reachable") && checkResp.data.get("reachable").getAsBoolean();
                    roomInfo.setIpv6Status(reachable ? RoomInfo.PortStatus.REACHABLE : RoomInfo.PortStatus.UNREACHABLE);
                    VoxLinkMod.LOGGER.info("IPv6 port check: [{}]:{} = {}", fIpv6, ctx.port, reachable);
                    ipv6Result.set(reachable);
                    if (completedChecks.incrementAndGet() == totalChecks) {
                        warnPortBlockedCombined(ipv4Result.get(), ipv6Result.get(), result.hostIp, fIpv6);
                    }
                });
            } else {
                roomInfo.setIpv6Status(RoomInfo.PortStatus.NO_ADDRESS);
            }

            return roomInfo;
        }).orTimeout(CREATE_ROOM_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS).exceptionally(e -> {
            cleanupCreateRoomResources(hostPort);
            VoxLinkMod.LOGGER.error("[createRoom] failed: {}", e.getMessage());
            if (e instanceof RuntimeException) throw (RuntimeException) e;
            throw new RuntimeException(e);
        });

        pendingCreateFuture = future;
        return future;
    }

    private void cleanupCreateRoomResources(int hostPort) {
        RoomState st = currentRoom.get();
        boolean wasPending = (st == PENDING);

if (wasPending) {
            currentRoom.compareAndSet(PENDING, null);
        } else if (st != null) {
            currentRoom.compareAndSet(st, null);
        }

        stopScheduledTasks();
        P2PBridge.disconnect();

        if (VoxLinkMod.getConfig().isAutoUPnP()) {
            UPnPManager.closePort(hostPort);
            if (GeyserCompat.isGeyserLoaded()) {
                UPnPManager.closeUdpPort(GeyserCompat.getBedrockPort());
            }
        }
        try {
            TerracottaManager.shutdown();
        } catch (Exception e) { VoxLinkMod.LOGGER.debug("cleanup terracotta error: {}", e.getMessage()); }
    }

    public CompletableFuture<RoomInfo> updateRoom(String code, String token, String name, String password, int maxPlayers, boolean visible, String authType, String category) {
        RoomState state = currentRoom.get();
        if (state == null || state == PENDING || state.roomInfo == null) {
            return CompletableFuture.failedFuture(new IllegalStateException(Component.translatable("voxlink.error.not_in_room").getString()));
        }
        return signalingClient.updateRoom(code, token, name, password, maxPlayers, visible, authType, category)
                .thenApply(response -> {
                    if (!response.success) {
                        String errMsg = response.error != null ? response.error : (response.message != null ? response.message : Component.translatable("voxlink.error.unknown").getString());
                        if (response.message != null && !response.message.equals(response.error)) {
                            errMsg = response.error + ": " + response.message;
                        }
                        throw new RuntimeException(errMsg);
                    }
                    RoomInfo ri = state.roomInfo;
                    if (name != null && !name.isEmpty()) ri.setName(name);
                    if (password != null) {
                        ri.setPassword(password);
                    }
                    ri.setMaxPlayers(maxPlayers);
                    ri.setVisible(visible);
                    if (authType != null) ri.setAuthType(authType);
                    if (category != null) ri.setCategory(category);
                    if (response.data != null && response.data.has("nameApproved") && !response.data.get("nameApproved").isJsonNull()
                            && !response.data.get("nameApproved").getAsBoolean()) {
                        ri.setNameApproved(false);
                    } else {
                        ri.setNameApproved(true);
                    }
                    return ri;
                })
                .exceptionally(e -> {
                    if (e instanceof RuntimeException) throw (RuntimeException) e;
                    throw new RuntimeException(e);
                });
    }

    public CompletableFuture<RoomInfo> joinRoom(String code, String password) {
        if (!currentRoom.compareAndSet(null, PENDING)) {
            return CompletableFuture.failedFuture(new IllegalStateException(Component.translatable("voxlink.error.already_in_room_or_pending").getString()));
        }

        //debounce 先杀残留陶瓦 防止上次退出残留导致加入失败
        try { TerracottaManager.shutdown(); }
        catch (Exception e) { VoxLinkMod.LOGGER.warn("Failed to stop Terracotta before join: {}", e.getMessage()); }

        if (code == null || code.isBlank()) {
            currentRoom.compareAndSet(PENDING, null);
            return CompletableFuture.failedFuture(new IllegalArgumentException(Component.translatable("voxlink.error.room_not_found").getString()));
        }

        final String normalizedCode = code.toUpperCase();
        if (!normalizedCode.matches("^[A-HJ-NP-Z2-9]{6}$")) {
            currentRoom.compareAndSet(PENDING, null);
            return CompletableFuture.failedFuture(new IllegalArgumentException(Component.translatable("voxlink.error.invalid_room_code").getString()));
        }

        connectionManager.setStunProbeResult(null);
        //debounce 换网络后失效STUN缓存 本地IP变了说明网络环境已切换
        if (StunProbe.isNetworkChanged()) {
            VoxLinkMod.LOGGER.info("[joinRoom] Network changed, invalidate STUN cache");
            StunProbe.invalidateCache();
        }
        StunProbe.ProbeResult cachedProbe = StunProbe.getCachedResult();
        if (cachedProbe != null) {
            connectionManager.setStunProbeResult(cachedProbe);
            connectionManager.getStunProbeFutureRef().set(null);
            VoxLinkMod.LOGGER.info("[joinRoom] Using cached STUN probe: NAT={}, reachable={}",
                    cachedProbe.natType.key, cachedProbe.reachableStunUrls.size());
        } else {
            CompletableFuture<StunProbe.ProbeResult> probeFuture = StunProbe.probeAsync(StunDetector.getStunServerGroups());
            connectionManager.getStunProbeFutureRef().set(probeFuture);
            probeFuture.thenAccept(result -> {
                connectionManager.setStunProbeResult(result);
                VoxLinkMod.LOGGER.info("[joinRoom] STUN probe done: NAT={}, reachable={}",
                        result.natType.key, result.reachableStunUrls.size());
            }).exceptionally(e -> {
                VoxLinkMod.LOGGER.warn("[joinRoom] STUN probe failed: {}", e.getMessage());
                return null;
            });
        }

        return signalingClient.joinRoom(normalizedCode, password)
                .thenApply(response -> {
                    if (!response.success) {
                        if (TRANSIENT_ERRORS.contains(response.error)) {
                            currentRoom.compareAndSet(PENDING, null);
                            throw new TransientException(response.error + ": " + response.message);
                        }
                        currentRoom.compareAndSet(PENDING, null);
                        String errMsg = response.error != null ? response.error : (response.message != null ? response.message : Component.translatable("voxlink.error.unknown").getString());
                        if (response.message != null && !response.message.equals(response.error)) {
                            errMsg = response.error + ": " + response.message;
                        }
                        throw new RuntimeException(errMsg);
                    }
                    if (response.data == null) {
                        currentRoom.compareAndSet(PENDING, null);
                        throw new RuntimeException(Component.translatable("voxlink.error.server_response_abnormal").getString());
                    }
                    String clientToken = response.data.has("clientToken") ? response.data.get("clientToken").getAsString() : "";
                    String clientId = response.data.has("clientId") ? response.data.get("clientId").getAsString() : "";
                    JsonObject roomData = response.data.has("room") && response.data.get("room").isJsonObject()
                            ? response.data.getAsJsonObject("room") : new JsonObject();

                    RoomInfo roomInfo = new RoomInfo(
                            roomData.has("code") ? roomData.get("code").getAsString() : "",
                            roomData.has("name") ? roomData.get("name").getAsString() : "VoxLink",
                            roomData.has("hasPassword") && roomData.get("hasPassword").getAsBoolean(),
                            roomData.has("maxPlayers") ? roomData.get("maxPlayers").getAsInt() : 20,
                            clientToken,
                            false,
                            roomData.has("hostPort") ? roomData.get("hostPort").getAsInt() : 25565,
                            roomData.has("natType") ? roomData.get("natType").getAsString() : "unknown"
                    );
                    roomInfo.setClientId(clientId);
                    if (roomData.has("bedrockPort") && !roomData.get("bedrockPort").isJsonNull()) {
                        roomInfo.setBedrockPort(roomData.get("bedrockPort").getAsInt());
                    }
                    if (roomData.has("protocolVersion") && !roomData.get("protocolVersion").isJsonNull()) {
                        roomInfo.setServerProtocolVersion(roomData.get("protocolVersion").getAsInt());
                    }
                    if (roomData.has("currentPlayers") && !roomData.get("currentPlayers").isJsonNull()) {
                        roomInfo.setCurrentPlayers(roomData.get("currentPlayers").getAsInt());
                    }
                    if (roomData.has("category") && !roomData.get("category").isJsonNull()) {
                        roomInfo.setCategory(roomData.get("category").getAsString());
                    }
                    if (roomData.has("loader") && !roomData.get("loader").isJsonNull()) {
                        roomInfo.setLoader(roomData.get("loader").getAsString());
                    }
                    if (roomData.has("authType") && !roomData.get("authType").isJsonNull()) {
                        roomInfo.setAuthType(roomData.get("authType").getAsString());
                    }
                    if (roomData.has("peerPort") && !roomData.get("peerPort").isJsonNull()) {
                        roomInfo.setPeerPort(roomData.get("peerPort").getAsInt());
                    }
                    //debounce 解析host上传的陶瓦房间号 供startDualP2P陶瓦侧使用
                    if (roomData.has("terracottaCode") && !roomData.get("terracottaCode").isJsonNull()) {
                        String tc = roomData.get("terracottaCode").getAsString();
                        if (tc != null && !tc.isEmpty()) {
                            roomInfo.setTerracottaCode(tc);
                            VoxLinkMod.LOGGER.info("[joinRoom] Got Terracotta code: {}", tc);
                        }
                    }

                    //debounce 协议协商: 解析host能力 老版本host无声明则视为legacy零能力
                    int hostProto = roomData.has("hostProtocolVersion") && !roomData.get("hostProtocolVersion").isJsonNull()
                            ? roomData.get("hostProtocolVersion").getAsInt() : 0;
                    java.util.Set<String> hostCaps = java.util.Collections.emptySet();
                    if (roomData.has("hostCapabilities") && roomData.get("hostCapabilities").isJsonArray()) {
                        hostCaps = new java.util.HashSet<>();
                        for (var c : roomData.getAsJsonArray("hostCapabilities")) {
                            if (!c.isJsonNull()) hostCaps.add(c.getAsString());
                        }
                    }
                    roomInfo.setHostCapabilities(hostProto, hostCaps);
                    if (hostProto > 0) {
                        VoxLinkMod.LOGGER.info("[joinRoom] Host capability: v{} caps={}", hostProto, hostCaps);
                    } else {
                        VoxLinkMod.LOGGER.info("[joinRoom] Host is legacy version, using direct connect mode");
                    }

                    RoomState state = new RoomState(roomInfo);
                    if (!currentRoom.compareAndSet(PENDING, state)) {
                        VoxLinkMod.LOGGER.warn("[joinRoom] State already cleared (timeout?), discarding late success");
                        return null;
                    }

                    intentionalLeave = false;
                    roomLostHandled.set(false);
                    heartbeatFailCount.set(0);
                    startHeartbeat();
                    startSignalPoll();

                    topologyClient.onRoomJoined(normalizedCode, clientToken, false, clientId, 0);

                    return roomInfo;
                }).orTimeout(JOIN_ROOM_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS).exceptionally(e -> {
                    if (currentRoom.compareAndSet(PENDING, null)) {
                        stopScheduledTasks();
                    }
                    //debounce joinRoom失败/超时杀残留陶瓦 防状态污染下次加入
                    try { icu.wuhui.voxlink.terracotta.TerracottaManager.shutdown(); } catch (Exception ex) { VoxLinkMod.LOGGER.debug("cleanup terracotta error: {}", ex.getMessage()); }
                    if (e instanceof RuntimeException) throw (RuntimeException) e;
                    throw new RuntimeException(e);
                });
    }

    public void leaveRoom() {
        intentionalLeave = true;
        stopScheduledTasks();
        roomLostHandled.set(true);
        connectionManager.setConnectionCycleActive(false);
        connectionManager.setReversePunchAttempted(false);
        ConnectionHelper.resetConnecting();
        ConnectionState.transitionTo(ConnectionState.DISCONNECTED, "用户主动离开");
        connectionManager.setStunProbeResult(null);
        connectionManager.getStunProbeFutureRef().set(null);
        //debounce 杀在途双P2P竞速 防陶瓦joinRoom后台残留
        connectionManager.killDualRace();
        //debounce 兜底重置双P2P状态 防止stale状态污染下次连接
        connectionManager.resetDualRaceState();
        //debounce 阶段四: 重置持续重试状态 为下次连接准备
        connectionManager.resetContinuousRetryState();
        //debounce 阶段三: 重置ICE Restart状态 为下次连接准备
        connectionManager.resetIceRestartState();
        RoomState state = currentRoom.getAndSet(null);
        if (state == null || state == PENDING) {
            cancelPendingCreate();
            //debounce U/码加入未完成时currentRoom仍为null 陶瓦进程可能残留 必须shutdown
            try { icu.wuhui.voxlink.terracotta.TerracottaManager.shutdown(); } catch (Exception e) { VoxLinkMod.LOGGER.debug("cleanup terracotta error: {}", e.getMessage()); }
            return;
        }

        cleanupRoomResources();
        try {
            performLeave(state);
        } catch (Exception e) {
            VoxLinkMod.LOGGER.warn("Leave room error: {}", e.getMessage());
        }
    }

    private void cleanupRoomResources() {
        try {
            connectionManager.clearActiveHolePunchers();
        } catch (Exception e) { VoxLinkMod.LOGGER.debug("cleanup punchers error: {}", e.getMessage()); }
        try {
            connectionManager.clearActiveUdpTransports();
        } catch (Exception e) { VoxLinkMod.LOGGER.debug("cleanup udp transports error: {}", e.getMessage()); }
        try {
            P2PBridge.disconnect();
        } catch (Exception e) { VoxLinkMod.LOGGER.debug("cleanup P2PBridge disconnect error: {}", e.getMessage()); }
        try {
            RoomState state = currentRoom.get();
            if (state != null && state != PENDING && state.roomInfo.isHost()) {
                int bridgePort = P2PBridge.getHostPort();
                if (bridgePort > 0) UPnPManager.closePort(bridgePort);
                UPnPManager.closePort(state.roomInfo.getHostPort());
                if (state.roomInfo.getBedrockPort() > 0) {
                    UPnPManager.closeUdpPort(state.roomInfo.getBedrockPort());
                }
            }
        } catch (Exception e) { VoxLinkMod.LOGGER.debug("cleanup UPnP error: {}", e.getMessage()); }
        try {
            topologyClient.onRoomLeft();
        } catch (Exception e) { VoxLinkMod.LOGGER.debug("cleanup topology error: {}", e.getMessage()); }
        try {
            TerracottaManager.shutdown();
        } catch (Exception e) { VoxLinkMod.LOGGER.debug("cleanup terracotta error: {}", e.getMessage()); }
    }

    public void leaveRoomSync() {
        intentionalLeave = true;
        stopScheduledTasks();
        roomLostHandled.set(true);
        connectionManager.setConnectionCycleActive(false);
        connectionManager.setReversePunchAttempted(false);
        ConnectionHelper.resetConnecting();
        connectionManager.setStunProbeResult(null);
        connectionManager.getStunProbeFutureRef().set(null);
        //debounce 兜底重置双P2P状态 与leaveRoom对称
        connectionManager.killDualRace();
        connectionManager.resetDualRaceState();
        //debounce 阶段四: 与leaveRoom对称 重置持续重试状态
        connectionManager.resetContinuousRetryState();
        //debounce 阶段三: 与leaveRoom对称 重置ICE Restart状态
        connectionManager.resetIceRestartState();
        RoomState state = currentRoom.getAndSet(null);
        if (state == null || state == PENDING) {
            cancelPendingCreate();
            //debounce U/码加入未完成时currentRoom仍为null 陶瓦进程可能残留 必须shutdown
            try { icu.wuhui.voxlink.terracotta.TerracottaManager.shutdown(); } catch (Exception e) { VoxLinkMod.LOGGER.debug("cleanup terracotta error: {}", e.getMessage()); }
            return;
        }
        //debounce 与leaveRoom对称 清理UPnP/Terracotta/P2PBridge/topology
        cleanupRoomResources();
        try {
            performLeave(state);
        } catch (Exception e) {
            VoxLinkMod.LOGGER.warn("Sync leave error: {}", e.getMessage());
        }
    }

    private void performLeave(RoomState state) {
        CompletableFuture<Void> leaveFuture;
        if (state.roomInfo.isHost()) {
            leaveFuture = signalingClient.leaveRoom(state.roomInfo.getCode(), state.roomInfo.getToken(), true)
                    .thenAccept(response -> {
                        if (!response.success) {
                            VoxLinkMod.LOGGER.warn("Server leave room failed: {}", response.error);
                        }
                    })
                    .exceptionally(e -> {
                        VoxLinkMod.LOGGER.warn("Server leave room failed: {}", e.getMessage());
                        return null;
                    });
        } else {
            leaveFuture = signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), false,
                            "disconnect", new JsonObject(), "host")
                    .exceptionally(e -> {
                        VoxLinkMod.LOGGER.warn("Send disconnect signal failed: {}", e.getMessage());
                        return null;
                    })
                    .thenCompose(v -> signalingClient.leaveRoom(state.roomInfo.getCode(), state.roomInfo.getToken(), false)
                            .thenAccept(response -> {
                                if (!response.success) {
                                    VoxLinkMod.LOGGER.warn("Server leave room failed: {}", response.error);
                                }
                            })
                            .exceptionally(e -> {
                                VoxLinkMod.LOGGER.warn("Server leave room failed: {}", e.getMessage());
                                return null;
                            }));
        }

        leaveFuture.whenComplete((v, ex) -> {
            if (state.roomInfo.isHost()) {
                int bridgePort = P2PBridge.getHostPort();
                if (VoxLinkMod.getConfig().isAutoUPnP()) {
                    if (bridgePort > 0) {
                        UPnPManager.closePort(bridgePort);
                    }
                    UPnPManager.closePort(state.roomInfo.getHostPort());
                    if (state.roomInfo.getBedrockPort() > 0) {
                        UPnPManager.closeUdpPort(state.roomInfo.getBedrockPort());
                    }
                }
            }

            P2PBridge.disconnect();

            topologyClient.onRoomLeft();
        });
    }

    public void closeRoom() {
        leaveRoom();
    }

    public void showRoomInfo(net.minecraft.commands.CommandSourceStack source) {
        RoomState state = currentRoom.get();
        if (state == null || state == PENDING) {
            source.sendSuccess(() -> Component.translatable("voxlink.error.not_in_room"), false);
            return;
        }
        RoomInfo info = state.roomInfo;
        source.sendSuccess(() -> Component.translatable("voxlink.room_info_detail",
                info.getName(), info.getCode(), info.getCurrentPlayers(),
                info.getMaxPlayers(), info.getNatType(),
                info.isHost() ? Component.translatable("voxlink.yes").getString() : Component.translatable("voxlink.no").getString()), false);
    }

    public RoomInfo getCurrentRoom() {
        RoomState state = currentRoom.get();
        return (state != null && state != PENDING) ? state.roomInfo : null;
    }

    //debounce 陶瓦连接成功后设置guest roomInfo
    public RoomInfo setupTerracottaGuestRoom(String roomCode) {
        RoomInfo roomInfo = new RoomInfo(roomCode, "Terracotta", false, 20, "", false, 0, "unknown");
        RoomState state = new RoomState(roomInfo);
        //debounce 用getAndSet而不是set 若旧VoxLink主机状态存在则清理其UPnP/socket资源
        RoomState old = currentRoom.getAndSet(state);
        if (old != null && old != PENDING && old.roomInfo.isHost()) {
            try { cleanupRoomResources(); } catch (Exception e) {
                VoxLinkMod.LOGGER.debug("Terracotta takeover cleanup old resources failed: {}", e.getMessage());
            }
        }
        intentionalLeave = false;
        roomLostHandled.set(true);
        return roomInfo;
    }

    public boolean isInRoom() {
        RoomState state = currentRoom.get();
        return state != null && state != PENDING;
    }

    private synchronized void stopScheduledTasks() {
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(false);
            heartbeatFuture = null;
        }
        if (signalPollFuture != null) {
            signalPollFuture.cancel(false);
            signalPollFuture = null;
        }
        connectionManager.stopAllConnectionWork();
    }

    private volatile String lastModerationStatus = "";
    private volatile String lastModeratedName = "";

    private synchronized void handleNameModerationUpdate(RoomState state, String status, String reason, String newName, boolean approved) {
        if (status == null || status.isEmpty()) return;

        if (status.equals(lastModerationStatus) && newName != null && newName.equals(lastModeratedName)) return;

        if (status.equals(lastModerationStatus) && !"approved".equals(status)) return;

        lastModerationStatus = status;
        if (newName != null) lastModeratedName = newName;

        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null) return;

        state.roomInfo.setNameApproved(approved);
        if (approved && newName != null && !newName.isEmpty() && !"name_pending_review".equals(newName)) {
            state.roomInfo.setName(newName);
        }

        mc.execute(() -> {
            if (mc.player == null) return;
            switch (status) {
                case "approved" -> {
                    mc.player.displayClientMessage(
                        Component.translatable("voxlink.chat.name_approved").withStyle(ChatFormatting.GREEN), false);
                    if (newName != null && !newName.isEmpty()) {
                        mc.player.displayClientMessage(
                            Component.literal("  " + newName).withStyle(ChatFormatting.GRAY), false);
                    }
                }
                case "rejected" -> {
                    String reasonText = reason != null && !reason.isEmpty() ? reason : Component.translatable("voxlink.chat.unknown_reason").getString();
                    mc.player.displayClientMessage(
                        Component.translatable("voxlink.chat.name_rejected_with_hint").withStyle(ChatFormatting.RED), false);
                    mc.player.displayClientMessage(
                        Component.translatable("voxlink.chat.reason_label", reasonText).withStyle(ChatFormatting.GRAY), false);
                }
                case "unavailable" -> {
                    mc.player.displayClientMessage(
                        Component.translatable("voxlink.chat.name_unavailable").withStyle(ChatFormatting.YELLOW), false);
                    mc.player.displayClientMessage(
                        Component.translatable("voxlink.chat.please_retry").withStyle(ChatFormatting.GRAY), false);
                }
            }
        });
    }

    volatile Runnable roomLostCallback;

    public void setRoomLostCallback(Runnable callback) {
        this.roomLostCallback = callback;
    }

    private volatile String roomLostReason = "";

    private void handleRoomLost() {
        handleRoomLost("HEARTBEAT_FAILED");
    }

    void handleRoomLost(String reason) {
        if (!roomLostHandled.compareAndSet(false, true)) return;
        connectionManager.setConnectionCycleActive(false);
        ConnectionHelper.resetConnecting();
        roomLostReason = reason;
        stopScheduledTasks();
        ConnectionState.reset();
        final RoomState captured = currentRoom.get();
        try {
        scheduler.execute(() -> {
            RoomState st = currentRoom.get();
            if (st == null || st == PENDING || st != captured) return;

            if (!st.roomInfo.isHost()) {
                try {
                    signalingClient.sendSignal(st.roomInfo.getCode(), st.roomInfo.getToken(), st.roomInfo.isHost(),
                            "disconnect", new com.google.gson.JsonObject(), "host");
                } catch (Exception e) {
                    VoxLinkMod.LOGGER.debug("Send disconnect signal failed on room lost: {}", e.getMessage());
                }
                try {
                    signalingClient.leaveRoom(st.roomInfo.getCode(), st.roomInfo.getToken(), false);
                } catch (Exception e) {
                    VoxLinkMod.LOGGER.debug("Leave failed on room lost: {}", e.getMessage());
                }
            }

            if (st.roomInfo.isHost()) {
                try {
                    signalingClient.leaveRoom(st.roomInfo.getCode(), st.roomInfo.getToken(), true);
                } catch (Exception e) {
                    VoxLinkMod.LOGGER.debug("Leave failed on room lost (host): {}", e.getMessage());
                }
                int bridgePort = P2PBridge.getHostPort();
                if (VoxLinkMod.getConfig().isAutoUPnP()) {
                    if (bridgePort > 0) {
                        UPnPManager.closePort(bridgePort);
                    }
                    UPnPManager.closePort(st.roomInfo.getHostPort());
                    if (st.roomInfo.getBedrockPort() > 0) {
                        UPnPManager.closeUdpPort(st.roomInfo.getBedrockPort());
                    }
                }
            }

            P2PBridge.disconnect();

            //debounce 房间丢失时关闭陶瓦 防止进程残留
            try { TerracottaManager.shutdown(); } catch (Exception ex) { VoxLinkMod.LOGGER.debug("handleRoomLost terracotta shutdown: {}", ex.getMessage()); }

            topologyClient.onRoomLeft();

            currentRoom.compareAndSet(captured, null);
            heartbeatFailCount.set(0);
            final boolean wasIntentional = intentionalLeave;
            if (roomLostCallback != null && !wasIntentional) {
                roomLostCallback.run();
            }
            intentionalLeave = false;
        });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            VoxLinkMod.LOGGER.warn("Scheduler closed, sync execute room lost cleanup");
            currentRoom.compareAndSet(captured, null);
            if (captured != null && captured != PENDING) {
                if (!captured.roomInfo.isHost()) {
                    try {
                        signalingClient.sendSignal(captured.roomInfo.getCode(), captured.roomInfo.getToken(), false,
                                "disconnect", new com.google.gson.JsonObject(), "host");
                    } catch (Exception ex) {
                        VoxLinkMod.LOGGER.debug("Send disconnect signal failed on room lost (sync fallback): {}", ex.getMessage());
                    }
                    try {
                        signalingClient.leaveRoom(captured.roomInfo.getCode(), captured.roomInfo.getToken(), false);
                    } catch (Exception ex) {
                        VoxLinkMod.LOGGER.debug("Leave failed on room lost (sync fallback): {}", ex.getMessage());
                    }
                }
                if (captured.roomInfo.isHost()) {
                    try {
                        signalingClient.leaveRoom(captured.roomInfo.getCode(), captured.roomInfo.getToken(), true);
                    } catch (Exception ex) {
                        VoxLinkMod.LOGGER.debug("Leave failed on room lost (sync fallback): {}", ex.getMessage());
                    }
                    int bridgePort = P2PBridge.getHostPort();
                    if (VoxLinkMod.getConfig().isAutoUPnP()) {
                        if (bridgePort > 0) UPnPManager.closePort(bridgePort);
                        UPnPManager.closePort(captured.roomInfo.getHostPort());
                        if (captured.roomInfo.getBedrockPort() > 0) UPnPManager.closeUdpPort(captured.roomInfo.getBedrockPort());
                    }
                }
            }
            P2PBridge.disconnect();
            //debounce 同步回退路径也关陶瓦
            try { TerracottaManager.shutdown(); } catch (Exception ex) { VoxLinkMod.LOGGER.debug("handleRoomLost sync terracotta shutdown: {}", ex.getMessage()); }
            topologyClient.onRoomLeft();
            if (!intentionalLeave) {
                notifyRoomLostActionBar(reason);
                //debounce 兜底也调callback 防止玩家未察觉被踢下次建房报已在房间
                if (roomLostCallback != null) {
                    try { roomLostCallback.run(); } catch (Exception ex) {
                        VoxLinkMod.LOGGER.debug("roomLostCallback exception (sync fallback): {}", ex.getMessage());
                    }
                }
            }
        }
    }

    private void notifyRoomLostActionBar(String reason) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.player == null) return;
            Component msg;
            if ("HOST_CLOSED".equals(reason) || "ROOM_CLOSED".equals(reason)) {
                msg = Component.translatable("voxlink.room_lost.host_closed");
            } else if ("HOST_DISCONNECTED".equals(reason)) {
                msg = Component.translatable("voxlink.room_lost.host_disconnected");
            } else if ("TOKEN_INVALID".equals(reason) || "INVALID_TOKEN".equals(reason)) {
                msg = Component.translatable("voxlink.room_closed");
            } else {
                msg = Component.translatable("voxlink.room_lost.default");
            }
            mc.player.displayClientMessage(
                    Component.translatable("voxlink.chat.error_prefix").withStyle(ChatFormatting.RED).append(msg), false);
            mc.player.displayClientMessage(
                    Component.translatable("voxlink.room_lost.hint").withStyle(ChatFormatting.GRAY), false);
        } catch (NoClassDefFoundError | Exception e) {
            VoxLinkMod.LOGGER.debug("Show chat message failed: {}", e.getMessage());
        }
    }

    public String getRoomLostReason() {
        return roomLostReason;
    }

    private synchronized void startHeartbeat() {
        ScheduledFuture<?> oldHeartbeat = heartbeatFuture;
        heartbeatFuture = null;
        if (oldHeartbeat != null) {
            oldHeartbeat.cancel(false);
        }
        heartbeatGeneration.incrementAndGet();
        heartbeatFailCount.set(0);
        heartbeatSeq.set(0);
        long interval = Math.max(VoxLinkMod.getConfig().getHeartbeatInterval(), MIN_HEARTBEAT_INTERVAL);
        currentHeartbeatInterval = interval;
        heartbeatFuture = scheduler.scheduleAtFixedRate(
                this::heartbeatTask, interval, interval, TimeUnit.SECONDS);
    }

    private void rescheduleHeartbeat(long newInterval) {
        if (currentRoom.get() == null) return;
        synchronized (this) {
            currentHeartbeatInterval = newInterval;
            ScheduledFuture<?> oldFuture = heartbeatFuture;
            heartbeatFuture = null;
            if (oldFuture != null) {
                oldFuture.cancel(false);
            }
            heartbeatFuture = scheduler.scheduleAtFixedRate(
                    this::heartbeatTask, newInterval, newInterval, TimeUnit.SECONDS);
        }
    }

    private void rescheduleSignalPoll(long newInterval) {
        if (currentRoom.get() == null) return;
        synchronized (this) {
            currentSignalPollInterval = newInterval;
            ScheduledFuture<?> oldFuture = signalPollFuture;
            signalPollFuture = null;
            if (oldFuture != null) {
                oldFuture.cancel(false);
            }
            scheduleSignalPoll();
        }
    }

    private void heartbeatTask() {
        try {
            RoomState state = currentRoom.get();
            if (state == null || state == PENDING) return;

            String natType = state.roomInfo.getNatType() != null ? state.roomInfo.getNatType() : "unknown";
            JsonObject peerLatency = topologyClient.pollAndGetPeerLatency();
            int seq = heartbeatSeq.incrementAndGet();
            final RoomState capturedState = state;
            int mcPlayerCount = 0;
            if (state.roomInfo.isHost()) {
                net.minecraft.server.MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
                if (server != null) {
                    mcPlayerCount = server.getPlayerList().getPlayerCount();
                }
            }

        signalingClient.heartbeat(state.roomInfo.getCode(), state.roomInfo.getToken(), state.roomInfo.isHost(),
                        natType, 0.1, peerLatency, seq, topologyClient.getOverlayManager().getLocalPort(), mcPlayerCount)
                .thenAccept(response -> {
                    if (currentRoom.get() != capturedState) return;
                    if (!response.success) {
                        if ("RATE_LIMITED".equals(response.error) || "CDN_ERROR".equals(response.error)) {
                            heartbeatFailCount.set(0);
                            long newInterval = Math.min(currentHeartbeatInterval * 2, 30);
                            if (response.retryAfter > 0) {
                                newInterval = Math.max(newInterval, response.retryAfter);
                            }
                            if (newInterval != currentHeartbeatInterval) {
                                VoxLinkMod.LOGGER.warn("Heartbeat {}/backoff to {}s", response.error, newInterval);
                                rescheduleHeartbeat(newInterval);
                            }
                            return;
                        }
                        if ("ROOM_EVICTED".equals(response.error)) {
                            VoxLinkMod.LOGGER.warn("Kicked by server (heartbeat)");
                            notifyRoomEvicted();
                            return;
                        }
                        if ("ROOM_NOT_FOUND".equals(response.error) || "ROOM_EXPIRED".equals(response.error)
                                || "INVALID_TOKEN".equals(response.error) || "ROOM_CLOSED".equals(response.error)) {
                            VoxLinkMod.LOGGER.warn("Room no longer exists (heartbeat): {}", response.error);
                            handleRoomLost("ROOM_CLOSED".equals(response.error) ? "HOST_CLOSED" :
                                    "INVALID_TOKEN".equals(response.error) ? "TOKEN_INVALID" : response.error);
                            return;
                        }
                        int fails = heartbeatFailCount.incrementAndGet();
                        VoxLinkMod.LOGGER.warn("Heartbeat failed ({}/{}): {}", fails, MAX_HEARTBEAT_FAILS, response.error);
                        if ("SERVER_403".equals(response.error) || "SERVER_404".equals(response.error)) {
                            VoxLinkMod.LOGGER.warn("Server temp error ({}), not counted as heartbeat fail", response.error);
                            heartbeatFailCount.decrementAndGet();
                        }
                        if (fails >= MAX_HEARTBEAT_FAILS) {
                            VoxLinkMod.LOGGER.error("Heartbeat failed too many times, room may be lost");
                            handleRoomLost();
                        }
                    } else {
                        heartbeatFailCount.set(0);

                        long baseInterval = Math.max(VoxLinkMod.getConfig().getHeartbeatInterval(), MIN_HEARTBEAT_INTERVAL);
                        if (currentHeartbeatInterval > baseInterval) {
                            VoxLinkMod.LOGGER.info("Heartbeat recovered, restore interval to {}s", baseInterval);
                            rescheduleHeartbeat(baseInterval);
                        }

                        if (response.data != null && response.data.has("topology") && !response.data.get("topology").isJsonNull()) {
                            JsonObject topoInstruction = response.data.getAsJsonObject("topology");
                            try {
                                Minecraft.getInstance().execute(() -> topologyClient.handleTopologyInstruction(topoInstruction));
                            } catch (NoClassDefFoundError e) {
                                // 服务端无MC类?                                topologyClient.handleTopologyInstruction(topoInstruction);
                            }
                        }

                        if (response.data != null && response.data.has("heartbeatInterval")) {
                            long serverInterval = response.data.get("heartbeatInterval").getAsLong();
                            long effectiveInterval = Math.max(serverInterval, MIN_HEARTBEAT_INTERVAL);
                            if (effectiveInterval != currentHeartbeatInterval) {
                                VoxLinkMod.LOGGER.info("Adjust heartbeat interval: server={}s, actual={}s", serverInterval, effectiveInterval);
                                rescheduleHeartbeat(effectiveInterval);
                            }
                        }

                        if (response.data != null && response.data.has("currentPlayers")) {
                            try {
                                int players = response.data.get("currentPlayers").getAsInt();
                                capturedState.roomInfo.setCurrentPlayers(players);
                            } catch (Exception ignored) {}
                        }

                        if (response.data != null && response.data.has("nameModerationStatus")) {
                            try {
                                String status = response.data.get("nameModerationStatus").getAsString();
                                String newName = response.data.has("name") ? response.data.get("name").getAsString() : null;
                                handleNameModerationUpdate(capturedState, status,
                                    response.data.has("nameModerationReason") ? response.data.get("nameModerationReason").getAsString() : null,
                                    newName, response.data.has("nameApproved") && response.data.get("nameApproved").getAsBoolean());
                            } catch (Exception ignored) {}
                        }
                    }
                })
                .exceptionally(e -> {
                    int fails = heartbeatFailCount.incrementAndGet();
                    VoxLinkMod.LOGGER.warn("Heartbeat exception ({}/{}): {}", fails, MAX_HEARTBEAT_FAILS, e.getMessage());
                    if (fails >= MAX_HEARTBEAT_FAILS) {
                        handleRoomLost();
                    }
                    return null;
                });
        } catch (Exception e) {
            VoxLinkMod.LOGGER.error("Heartbeat task sync error", e);
        }
    }

    private void startSignalPoll() {
        signalPollTimestamp.set(System.currentTimeMillis() - 10000);
        RoomState state = currentRoom.get();
        if (state != null && !state.roomInfo.isHost()) {
            currentSignalPollInterval = INITIAL_SIGNAL_POLL_MS;
        } else {
            currentSignalPollInterval = VoxLinkMod.getConfig().getSignalPollInterval();
        }
        scheduleSignalPoll();
    }

    private void scheduleSignalPoll() {
        scheduler.execute(this::doSignalPoll);
        signalPollFuture = scheduler.scheduleAtFixedRate(this::doSignalPoll,
                currentSignalPollInterval, currentSignalPollInterval, TimeUnit.MILLISECONDS);
    }

    private void doSignalPoll() {
        try {
            RoomState state = currentRoom.get();
            if (state == null || state == PENDING) return;
            if (!signalPollInFlight.compareAndSet(false, true)) return;
            final RoomState capturedState = state;
            final int seq = pollCount.incrementAndGet();
            final long startTime = System.currentTimeMillis();
            signalingClient.pollSignals(state.roomInfo.getCode(), state.roomInfo.getToken(),
                            state.roomInfo.isHost(), signalPollTimestamp.get())
                    .thenAccept(response -> {
                        if (currentRoom.get() != capturedState) {
                            signalPollInFlight.set(false);
                            return;
                        }
                        long elapsed = System.currentTimeMillis() - startTime;
                        if (seq <= 5 || elapsed > 5000 || !response.success) {
                            VoxLinkMod.LOGGER.info("[RoomManager] Signal poll #{}: {}ms, success={}, hasSignals={}",
                                seq, elapsed, response.success,
                                response.success && response.data != null && (response.data.has("s") || response.data.has("signals")));
                        }
                        try {
                            handleSignalPollResponse(response);
                        } catch (Exception e) {
                            VoxLinkMod.LOGGER.warn("Signal poll response handle error: {}", e.getMessage());
                        }
                    })
                    .exceptionally(e -> {
                        long elapsed = System.currentTimeMillis() - startTime;
                        VoxLinkMod.LOGGER.warn("Signal poll #{} error ({}ms): {}", seq, elapsed, e.getMessage());
                        return null;
                    })
                    .whenComplete((r, e) -> signalPollInFlight.set(false));
        } catch (Exception e) {
            signalPollInFlight.set(false);
            VoxLinkMod.LOGGER.error("Signal poll sync error", e);
        }
    }

    private void handleSignalPollResponse(SignalingClient.ApiResponse response) {
        if (response.success && response.data != null && (response.data.has("s") || response.data.has("signals"))) {
            String sigKey = response.data.has("s") ? "s" : "signals";
            String tsKey = response.data.has("ts") ? "ts" : "timestamp";
            if (!response.data.get(sigKey).isJsonArray()) return;
            var signals = response.data.getAsJsonArray(sigKey);
            VoxLinkMod.LOGGER.debug("[RoomManager] Signal poll: received {} signals", signals.size());
            for (var element : signals) {
                if (!element.isJsonObject()) continue;
                JsonObject signal = element.getAsJsonObject();
                String sigType = signal.has("type") ? signal.get("type").getAsString() : "unknown";
                VoxLinkMod.LOGGER.info("[RoomManager] Signal dispatch: type={}, from={}", sigType, signal.has("from") ? signal.get("from").getAsString() : "?");
                handleSignal(signal);
                if (signal.has("timestamp") && !signal.get("timestamp").isJsonNull()) {
                    signalPollTimestamp.accumulateAndGet(signal.get("timestamp").getAsLong(), Math::max);
                }
            }
            if (response.data.has(tsKey)) {
                signalPollTimestamp.accumulateAndGet(response.data.get(tsKey).getAsLong(), Math::max);
            }
            recoverSignalPollInterval();
        } else if (response.success && response.data != null && response.data.has("ts")) {
            signalPollTimestamp.accumulateAndGet(response.data.get("ts").getAsLong(), Math::max);
            recoverSignalPollInterval();
        } else if (!response.success) {
            VoxLinkMod.LOGGER.warn("[RoomManager] Signal poll failed: {} - {}", response.error, response.message);
            if ("RATE_LIMITED".equals(response.error) || "CDN_ERROR".equals(response.error)
                    || "SERVER_403".equals(response.error) || "SERVER_404".equals(response.error)) {
                backoffSignalPollInterval();
                return;
            }
            if ("ROOM_EVICTED".equals(response.error)) {
                VoxLinkMod.LOGGER.warn("Kicked by server (signal poll)");
                notifyRoomEvicted();
            } else if ("ROOM_NOT_FOUND".equals(response.error) || "ROOM_EXPIRED".equals(response.error) || "INVALID_TOKEN".equals(response.error) || "ROOM_CLOSED".equals(response.error)) {
                VoxLinkMod.LOGGER.warn("Room no longer exists on server: {}", response.error);
                handleRoomLost("ROOM_CLOSED".equals(response.error) ? "HOST_CLOSED" :
                        "INVALID_TOKEN".equals(response.error) ? "TOKEN_INVALID" : response.error);
            }
        } else if (response.success) {
            recoverSignalPollInterval();
        }
    }

    private void backoffSignalPollInterval() {
        long newInterval = Math.min(currentSignalPollInterval * BACKOFF_MULTIPLIER, MAX_SIGNAL_POLL_MS);
        if (newInterval != currentSignalPollInterval) {
            VoxLinkMod.LOGGER.warn("Signal poll rate limited/CDN error, backoff to {}ms", newInterval);
            rescheduleSignalPoll(newInterval);
        }
    }

    private void recoverSignalPollInterval() {
        RoomState state = currentRoom.get();
        boolean isJoiner = state != null && state != PENDING && !state.roomInfo.isHost();
        long normalInterval = isJoiner ? JOINER_SIGNAL_POLL_MS : VoxLinkMod.getConfig().getSignalPollInterval();
        if (currentSignalPollInterval != normalInterval) {
            currentSignalPollInterval = normalInterval;
            rescheduleSignalPoll(normalInterval);
        }
    }

    private void handleSignal(JsonObject signal) {
        if (!signal.has("type") || signal.get("type").isJsonNull() || !signal.has("from") || signal.get("from").isJsonNull()) {
            VoxLinkMod.LOGGER.warn("Skip malformed signal: missing type or from");
            return;
        }
        String type = signal.get("type").getAsString();
        String from = signal.get("from").getAsString();
        JsonObject data = signal.has("data") && signal.get("data").isJsonObject() ? signal.getAsJsonObject("data") : new JsonObject();

        VoxLinkMod.LOGGER.debug("Received signal: type={}, from={}", type, from);

        switch (type) {
            case "join_request" -> connectionManager.handleJoinRequest(from, data);
            case "holepunch_offer" -> connectionManager.handleHolePunchOffer(from, data);
            case "holepunch_mapped" -> connectionManager.handleHolepunchMapped(from, data);
            case "holepunch_answer" -> connectionManager.handleHolePunchAnswer(from, data);
            case "connected" -> handleConnected(from, data);
            case "disconnect" -> handleDisconnect(from, data);
            case "host_closing" -> handleHostClosing(from, data);
            case "room_evicted" -> handleRoomEvicted(from, data);

            case "punch_info" -> connectionManager.handlePunchInfo(from, data);
            case "peer_port" -> connectionManager.handlePeerPort(from, data);
            case "reverse_holepunch_offer" -> connectionManager.handleReverseHolepunchOffer(from, data);
            case "reverse_punch_info" -> connectionManager.handleReversePunchInfo(from, data);
            case "tcp_simopen_request" -> connectionManager.handleTcpSimopenRequest(from, data);

            case "relay_request" -> connectionManager.handleRelayRequest(from, data);
            case "relay_accept" -> connectionManager.handleRelayAccept(from, data);
            case "relay_declined" -> connectionManager.handleRelayDeclined(from, data);
            case "relay_setup" -> connectionManager.handleRelaySetup(from, data);
            case "relay_notify" -> connectionManager.handleRelayNotify(from, data);
            //debounce 阶段四: 对端取消连接 立即终止持续重试 老版本不发此信号
            case "cancel_connection" -> connectionManager.handleCancelConnection(from, data);
            //debounce 阶段三: 对端请求ICE Restart 重新打洞 老版本不发此信号
            case "ice_restart" -> connectionManager.handleIceRestart(from, data);

            case "room_name_approved" -> {
                RoomState st = currentRoom.get();
                if (st != null && st != PENDING) {
                    String approvedName = data.has("name") ? data.get("name").getAsString() : null;
                    handleNameModerationUpdate(st, "approved", null, approvedName, true);
                }
            }
            case "room_name_rejected" -> {
                RoomState st = currentRoom.get();
                if (st != null && st != PENDING) {
                    String rejectedName = data.has("name") ? data.get("name").getAsString() : null;
                    String rejectedReason = data.has("reason") ? data.get("reason").getAsString() : null;
                    handleNameModerationUpdate(st, "rejected", rejectedReason, rejectedName, false);
                }
            }
            case "room_name_unavailable" -> {
                RoomState st = currentRoom.get();
                if (st != null && st != PENDING) {
                    String unavailableName = data.has("name") ? data.get("name").getAsString() : null;
                    handleNameModerationUpdate(st, "unavailable", null, unavailableName, false);
                }
            }

            case "topology_optimization_done" -> topologyClient.handleTopologySignal(type, data);
            case "topology_change" -> topologyClient.handleTopologySignal(type, data);
            default -> VoxLinkMod.LOGGER.debug("Unknown signal type: {}", type);
        }
    }

    public ConnectionManager getConnectionManager() {
        return connectionManager;
    }

    public boolean isConnectionCycleActive() {
        return connectionManager.isConnectionCycleActive();
    }

    private void handleConnected(String from, JsonObject data) {
        VoxLinkMod.LOGGER.info("Peer connected: {}", from);
        RoomState st = currentRoom.get();
        if (st != null && st != PENDING && st.roomInfo.isHost()) {
            //debounce 对端已连接 停所有打洞(备用路径 桥建立时已停)
            connectionManager.stopAllPunchingAfterHostBridge();
            //debounce NameAndId同步操作OP列表 allowCommands控制访客权限 无需延时重试
        }
    }

    //debounce 房主OP独立设置: hostOp=true加OP hostOp=false撤回OP 访客OP由allowCommands=guestOp控制
    public void applyOpPolicy(net.minecraft.server.MinecraftServer server, boolean hostOp, boolean guestOp) {
        try {
            var playerList = server.getPlayerList();
            var hostProfile = server.getSingleplayerProfile();
            if (hostProfile == null) {
                VoxLinkMod.LOGGER.warn("[RoomManager] Host profile empty, skip OP policy");
                return;
            }
            net.minecraft.server.players.NameAndId hostName = new net.minecraft.server.players.NameAndId(hostProfile);
            if (hostOp) {
                playerList.getOps().add(new net.minecraft.server.players.ServerOpListEntry(
                        hostName, net.minecraft.server.permissions.LevelBasedPermissionSet.OWNER,
                        playerList.canBypassPlayerLimit(hostName)));
                VoxLinkMod.LOGGER.info("[RoomManager] Host OP: grant {}", hostProfile.name());
            } else {
                if (playerList.isOp(hostName)) {
                    playerList.deop(hostName);
                    VoxLinkMod.LOGGER.info("[RoomManager] Host OP: revoke {}", hostProfile.name());
                }
            }
            //debounce guestOp=false时清空所有访客OP(防allowCommands=true残留) guestOp=true由publishServer自动OP
            if (!guestOp) {
                String hostNameStr = hostProfile.name();
                for (var player : playerList.getPlayers()) {
                    String name = player.getName().getString();
                    if (name.equals(hostNameStr)) continue;
                    net.minecraft.server.players.NameAndId guestId = new net.minecraft.server.players.NameAndId(player.getGameProfile());
                    if (playerList.isOp(guestId)) {
                        playerList.deop(guestId);
                        playerList.sendPlayerPermissionLevel(player);
                        VoxLinkMod.LOGGER.info("[RoomManager] Visitor OP: revoke {}", name);
                    }
                }
            }
            //debounce 发送权限更新包给房主
            var hostPlayer = playerList.getPlayer(hostProfile.id());
            if (hostPlayer != null) {
                playerList.sendPlayerPermissionLevel(hostPlayer);
            }
        } catch (Exception e) {
            VoxLinkMod.LOGGER.warn("[RoomManager] OP policy apply failed: {}", e.getMessage());
        }
    }

    private void handleDisconnect(String from, JsonObject data) {
        VoxLinkMod.LOGGER.info("Peer disconnected: {}", from);
        RoomState state = currentRoom.get();
        if (state != null && state != PENDING && state.roomInfo.isHost() && from != null) {
            //debounce 清hostPunching 否则新join_request被排队卡住
            connectionManager.clearHostPunchingState();
            ReliableUdpTransport transport = connectionManager.removeUdpTransport(from);
            if (transport != null) {
                try { transport.close(); } catch (Exception ignored) {}
            }
        }
        if (state != null && state != PENDING && !state.roomInfo.isHost() && from != null && ("host".equals(from) || from.startsWith("host_"))) {
            //debounce host断开时关闭陶瓦 防止进程残留
            try { TerracottaManager.shutdown(); } catch (Exception ex) { VoxLinkMod.LOGGER.debug("handleDisconnect terracotta shutdown: {}", ex.getMessage()); }
            handleRoomLost("HOST_DISCONNECTED");
        }
    }

    private void handleHostClosing(String from, JsonObject data) {
        VoxLinkMod.LOGGER.info("Host is closing room");
        handleRoomLost("HOST_CLOSED");
    }

    private void handleRoomEvicted(String from, JsonObject data) {
        VoxLinkMod.LOGGER.warn("Kicked by server");
        notifyRoomEvicted();
    }

    private void notifyRoomEvicted() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            mc.execute(() -> {
                if (mc.player != null) {
                    mc.player.displayClientMessage(
                            Component.translatable("voxlink.chat.evicted_notice").withStyle(ChatFormatting.YELLOW), false);
                }
            });
        }
    }

    private void warnPortBlockedCombined(Boolean ipv4Ok, Boolean ipv6Ok, String ipv4, String ipv6) {
        boolean v4Blocked = ipv4Ok != null && !ipv4Ok;
        boolean v6Blocked = ipv6Ok != null && !ipv6Ok;
        if (!v4Blocked && !v6Blocked) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        mc.execute(() -> {
            if (mc.player == null) return;
            net.minecraft.network.chat.MutableComponent prefix = Component.translatable("voxlink.chat.error_prefix").withStyle(ChatFormatting.RED);
            net.minecraft.network.chat.MutableComponent msg;
            if (v4Blocked && v6Blocked) {
                msg = Component.translatable("voxlink.chat.both_unreachable");
            } else if (v4Blocked) {
                msg = Component.translatable("voxlink.chat.ipv4_unreachable");
            } else {
                msg = Component.translatable("voxlink.chat.ipv6_unreachable");
            }
            mc.player.displayClientMessage(prefix.append(msg), false);
        });
    }

    static class RoomState {
        final RoomInfo roomInfo;

        RoomState(RoomInfo roomInfo) {
            this.roomInfo = roomInfo;
        }
    }

    private record NatResult(String nat, int port, int geyserPort) {}
    private record CreateRoomResult(NatResult natResult, SignalingClient.ApiResponse apiResponse, String hostIp, String hostIpv6) {}

    private static class TransientException extends RuntimeException {
        TransientException(String message) { super(message); }
    }
}
