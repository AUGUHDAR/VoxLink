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
import icu.wuhui.voxlink.network.DataRouter;
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
    private final DataRouter dataRouter;
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
        this.dataRouter = new DataRouter(topologyClient.getOverlayManager(), signalingClient);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(() -> {
                try {
                    r.run();
                } catch (Throwable e) {
                    VoxLinkMod.LOGGER.error("调度任务抛出未捕获异常", e);
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
        connectionManager.shutdown();
        stopScheduledTasks();
        scheduler.shutdownNow();
    }

    public void cancelPending() {
        currentRoom.compareAndSet(PENDING, null);
        cancelPendingCreate();
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

    private static final String GAME_VERSION = "1.21.3";

    public CompletableFuture<RoomInfo> createRoom(String name, String password, int maxPlayers, int hostPort, boolean visible, String authType, String category) {
        if (!currentRoom.compareAndSet(null, PENDING)) {
            return CompletableFuture.failedFuture(new IllegalStateException(Component.translatable("voxlink.error.already_in_room_or_pending").getString()));
        }

        int protocolVersion = ViaCompat.isViaLoaded() ? ViaCompat.getServerProtocolVersion() : 0;
        int peerPort = icu.wuhui.voxlink.network.PeerServer.getPort();

        VoxLinkMod.LOGGER.info("[createRoom] 即时建房（NAT探测延后）");
        CompletableFuture<NatResult> natFuture = CompletableFuture.completedFuture(
            new NatResult("unknown", hostPort, -1)
        );

        CompletableFuture.runAsync(() -> {
            try {
                VoxLinkMod.LOGGER.info("[createRoom] 后台NAT探测开始");
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
                            VoxLinkMod.LOGGER.info("[createRoom] 保存STUN探测结果: NAT={}, 可达={}", natType, probeResult.reachableStunUrls.size());
                        }
                    } catch (Exception ex2) {
                        VoxLinkMod.LOGGER.warn("[createRoom] probeAsync失败: {}", ex2.getMessage());
                    }
                }
                VoxLinkMod.LOGGER.info("[createRoom] 后台NAT探测完成: natType={}, port={}", natType, effectivePort);

                RoomState state = currentRoom.get();
                if (state == null || state == PENDING || state.roomInfo == null) {
                    VoxLinkMod.LOGGER.info("[createRoom] 房间还没建好，2s后重试NAT更新");
                    final String finalNatType = natType;
                    scheduler.schedule(() -> {
                        RoomState st = currentRoom.get();
                        if (st != null && st != PENDING && st.roomInfo != null) {
                            st.roomInfo.setNatType(finalNatType);
                            VoxLinkMod.LOGGER.info("[createRoom] 延迟更新NAT类型: {}", finalNatType);
                            try {
                                signalingClient.updateRoom(st.roomInfo.getCode(), st.roomInfo.getToken(),
                                    st.roomInfo.getName(), null, st.roomInfo.getMaxPlayers(),
                                    st.roomInfo.isVisible(), st.roomInfo.getAuthType(), st.roomInfo.getCategory());
                            } catch (Exception e) {
                                VoxLinkMod.LOGGER.warn("[createRoom] 延迟更新NAT类型失败: {}", e.getMessage());
                            }
                        }
                    }, NAT_UPDATE_DELAY_SEC, TimeUnit.SECONDS);
                } else {
                    state.roomInfo.setNatType(natType);
try {
                        signalingClient.updateRoom(state.roomInfo.getCode(), state.roomInfo.getToken(),
                            state.roomInfo.getName(), null, state.roomInfo.getMaxPlayers(),
                            state.roomInfo.isVisible(), state.roomInfo.getAuthType(), state.roomInfo.getCategory());
                        VoxLinkMod.LOGGER.info("[createRoom] NAT类型更新: {}", natType);
                    } catch (Exception e) {
                        VoxLinkMod.LOGGER.warn("[createRoom] 更新NAT类型失败: {}", e.getMessage());
                    }
                }
            } catch (Exception e) {
                VoxLinkMod.LOGGER.warn("[createRoom] 后台NAT探测失败: {}", e.getMessage());
            }
        });

        CompletableFuture<SignalingClient.ApiResponse> ipFuture = signalingClient.getPublicIp()
                .exceptionally(e -> {
                    VoxLinkMod.LOGGER.warn("[createRoom] 获取公网IP失败: {}, 继续不带IP", e.getMessage());
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
            VoxLinkMod.LOGGER.info("[createRoom] NAT+IP都搞定了: nat={}, ip={}, ipv6={}", ctx.nat, hostIp, hostIpv6);
            if ((hostIpv6 == null || hostIpv6.isEmpty()) && StunDetector.verifyIPv6Connectivity()) {
                hostIpv6 = ConnectionFallback.getLocalGlobalIpv6();
                if (hostIpv6 != null) {
                    VoxLinkMod.LOGGER.info("[createRoom] 服务端没返回IPv6，用本地全局IPv6: {}（未验证可达性）", hostIpv6);
                }
            }
            return new CreateRoomResult(ctx, null, hostIp, hostIpv6);
        }).thenCompose(result -> {
            final String finalHostIp = result.hostIp;
            final String finalHostIpv6 = result.hostIpv6;
            final NatResult ctx = result.natResult;
            VoxLinkMod.LOGGER.info("[createRoom] 第2步: 调API建房");
            return signalingClient.createRoom(name, (password != null && !password.isEmpty()) ? password : null, maxPlayers, ctx.port, ctx.nat, ctx.geyserPort, visible, authType, category, protocolVersion, peerPort, finalHostIpv6, GAME_VERSION)
                    .thenApply(response -> {
                        VoxLinkMod.LOGGER.info("[createRoom] 第2步完成: success={}", response.success);
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
                VoxLinkMod.LOGGER.info("房间已建，{}秒后过期", expiresIn);
            }

            RoomInfo roomInfo = new RoomInfo(code, name, password != null && !password.isEmpty(), maxPlayers, hostToken, true, ctx.port, ctx.nat);
            roomInfo.setHostIp(result.hostIp);
            roomInfo.setHostIpv6(result.hostIpv6);
            roomInfo.setBedrockPort(ctx.geyserPort > 0 ? ctx.geyserPort : -1);
            roomInfo.setCategory(category);
            roomInfo.setVisible(visible);
            RoomState state = new RoomState(roomInfo);
            if (!currentRoom.compareAndSet(PENDING, state)) {
                VoxLinkMod.LOGGER.warn("[createRoom] 状态已被清空（超时？），丢弃迟到的结果");
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
            dataRouter.setRoomContext(code, hostToken, true);

            try {
                int bridgePort = P2PBridge.startHostBridge(ctx.port).get(5, java.util.concurrent.TimeUnit.SECONDS);
                if (bridgePort > 0) {
                    VoxLinkMod.LOGGER.info("主机桥启动 port={}, MC端口: {}", bridgePort, ctx.port);
                } else {
                    VoxLinkMod.LOGGER.warn("主机桥启动失败，客户端需直连MC端口 {}", ctx.port);
                }
            } catch (Exception e) {
                VoxLinkMod.LOGGER.warn("主机桥启动异常: {}", e.getMessage());
            }

            if (TerracottaManager.isBinaryReady()) {
                String tpName = Minecraft.getInstance().getUser().getName();
                TerracottaManager.createRoom(tpName)
                    .thenAccept(tc -> {
                        roomInfo.setTerracottaCode(tc);
                        VoxLinkMod.LOGGER.info("陶瓦房间号: {}", tc);
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
                        VoxLinkMod.LOGGER.warn("陶瓦开房失败, 仅用 VoxLink 房间号: {}", e.getMessage());
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
                    VoxLinkMod.LOGGER.info("IPv4端口检查: {}:{} = {}", fIpv4, ctx.port, reachable);
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
                    VoxLinkMod.LOGGER.info("IPv6端口检查: [{}]:{} = {}", fIpv6, ctx.port, reachable);
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
            VoxLinkMod.LOGGER.error("[createRoom] 失败: {}", e.getMessage());
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
        if (VoxLinkMod.getConfig().isVoiceChatCompat()) {
        }
        if (GeyserCompat.isGeyserLoaded()) {
        }
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
                    if (roomData.has("authType") && !roomData.get("authType").isJsonNull()) {
                        roomInfo.setAuthType(roomData.get("authType").getAsString());
                    }
                    if (roomData.has("peerPort") && !roomData.get("peerPort").isJsonNull()) {
                        roomInfo.setPeerPort(roomData.get("peerPort").getAsInt());
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
                    dataRouter.setRoomContext(normalizedCode, clientToken, false);

                    return roomInfo;
                }).orTimeout(JOIN_ROOM_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS).exceptionally(e -> {
                    if (currentRoom.compareAndSet(PENDING, null)) {
                        stopScheduledTasks();
                    }
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
        RoomState state = currentRoom.getAndSet(null);
        if (state == null || state == PENDING) {
            cancelPendingCreate();
            return;
        }

        cleanupRoomResources();
        try {
            performLeave(state);
        } catch (Exception e) {
            VoxLinkMod.LOGGER.warn("离开房间出错: {}", e.getMessage());
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
        RoomState state = currentRoom.getAndSet(null);
        if (state == null || state == PENDING) {
            cancelPendingCreate();
            return;
        }

        try {
            performLeave(state);
        } catch (Exception e) {
            VoxLinkMod.LOGGER.warn("同步离开出错: {}", e.getMessage());
        }
    }

    private void performLeave(RoomState state) {
        CompletableFuture<Void> leaveFuture;
        if (state.roomInfo.isHost()) {
            leaveFuture = signalingClient.leaveRoom(state.roomInfo.getCode(), state.roomInfo.getToken(), true)
                    .thenAccept(response -> {
                        if (!response.success) {
                            VoxLinkMod.LOGGER.warn("服务端离开房间失败: {}", response.error);
                        }
                    })
                    .exceptionally(e -> {
                        VoxLinkMod.LOGGER.warn("服务端离开房间失败: {}", e.getMessage());
                        return null;
                    });
        } else {
            leaveFuture = signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), false,
                            "disconnect", new JsonObject(), "host")
                    .exceptionally(e -> {
                        VoxLinkMod.LOGGER.warn("发送断开信号失败: {}", e.getMessage());
                        return null;
                    })
                    .thenCompose(v -> signalingClient.leaveRoom(state.roomInfo.getCode(), state.roomInfo.getToken(), false)
                            .thenAccept(response -> {
                                if (!response.success) {
                                    VoxLinkMod.LOGGER.warn("服务端离开房间失败: {}", response.error);
                                }
                            })
                            .exceptionally(e -> {
                                VoxLinkMod.LOGGER.warn("服务端离开房间失败: {}", e.getMessage());
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
                if (VoxLinkMod.getConfig().isVoiceChatCompat()) {
                }
                if (state.roomInfo.getBedrockPort() > 0) {
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
        currentRoom.set(state);
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
                        Component.translatable("voxlink.chat.name_approved"), false);
                    if (newName != null && !newName.isEmpty()) {
                        mc.player.displayClientMessage(
                            Component.literal("  " + newName).withStyle(ChatFormatting.GRAY), false);
                    }
                }
                case "rejected" -> {
                    String reasonText = reason != null && !reason.isEmpty() ? reason : Component.translatable("voxlink.chat.unknown_reason").getString();
                    mc.player.displayClientMessage(
                        Component.translatable("voxlink.chat.name_rejected_with_hint"), false);
                    mc.player.displayClientMessage(
                        Component.translatable("voxlink.chat.reason_label", reasonText), false);
                }
                case "unavailable" -> {
                    mc.player.displayClientMessage(
                        Component.translatable("voxlink.chat.name_unavailable"), false);
                    mc.player.displayClientMessage(
                        Component.translatable("voxlink.chat.please_retry"), false);
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
                    VoxLinkMod.LOGGER.debug("房间丢失时发送断开信号失败: {}", e.getMessage());
                }
                try {
                    signalingClient.leaveRoom(st.roomInfo.getCode(), st.roomInfo.getToken(), false);
                } catch (Exception e) {
                    VoxLinkMod.LOGGER.debug("房间丢失时离开失败: {}", e.getMessage());
                }
            }

            if (st.roomInfo.isHost()) {
                try {
                    signalingClient.leaveRoom(st.roomInfo.getCode(), st.roomInfo.getToken(), true);
                } catch (Exception e) {
                    VoxLinkMod.LOGGER.debug("房间丢失时离开失败(房主): {}", e.getMessage());
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
                if (VoxLinkMod.getConfig().isVoiceChatCompat()) {
                }
                if (st.roomInfo.getBedrockPort() > 0) {
                }
            }

            P2PBridge.disconnect();

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
            VoxLinkMod.LOGGER.warn("调度器已关闭，同步执行房间丢失清理");
            currentRoom.compareAndSet(captured, null);
            if (captured != null && captured != PENDING) {
                if (!captured.roomInfo.isHost()) {
                    try {
                        signalingClient.sendSignal(captured.roomInfo.getCode(), captured.roomInfo.getToken(), false,
                                "disconnect", new com.google.gson.JsonObject(), "host");
                    } catch (Exception ex) {
                        VoxLinkMod.LOGGER.debug("房间丢失时发送断开信号失败(同步兜底): {}", ex.getMessage());
                    }
                    try {
                        signalingClient.leaveRoom(captured.roomInfo.getCode(), captured.roomInfo.getToken(), false);
                    } catch (Exception ex) {
                        VoxLinkMod.LOGGER.debug("房间丢失时离开失败(同步兜底): {}", ex.getMessage());
                    }
                }
                if (captured.roomInfo.isHost()) {
                    try {
                        signalingClient.leaveRoom(captured.roomInfo.getCode(), captured.roomInfo.getToken(), true);
                    } catch (Exception ex) {
                        VoxLinkMod.LOGGER.debug("房间丢失时离开失败(同步兜底): {}", ex.getMessage());
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
            topologyClient.onRoomLeft();
            if (!intentionalLeave) {
                notifyRoomLostActionBar(reason);
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
                    Component.translatable("voxlink.chat.error_prefix").append(msg), false);
            mc.player.displayClientMessage(
                    Component.translatable("voxlink.room_lost.hint"), false);
        } catch (NoClassDefFoundError | Exception e) {
            VoxLinkMod.LOGGER.debug("显示聊天消息失败: {}", e.getMessage());
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

        signalingClient.heartbeat(state.roomInfo.getCode(), state.roomInfo.getToken(), state.roomInfo.isHost(),
                        natType, 0.1, peerLatency, seq, topologyClient.getOverlayManager().getLocalPort())
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
                                VoxLinkMod.LOGGER.warn("心跳 {}/退避到{}s", response.error, newInterval);
                                rescheduleHeartbeat(newInterval);
                            }
                            return;
                        }
                        if ("ROOM_EVICTED".equals(response.error)) {
                            VoxLinkMod.LOGGER.warn("房间被服务器踢出(心跳)");
                            notifyRoomEvicted();
                            return;
                        }
                        if ("ROOM_NOT_FOUND".equals(response.error) || "ROOM_EXPIRED".equals(response.error)
                                || "INVALID_TOKEN".equals(response.error) || "ROOM_CLOSED".equals(response.error)) {
                            VoxLinkMod.LOGGER.warn("房间已不存在(心跳): {}", response.error);
                            handleRoomLost("ROOM_CLOSED".equals(response.error) ? "HOST_CLOSED" :
                                    "INVALID_TOKEN".equals(response.error) ? "TOKEN_INVALID" : response.error);
                            return;
                        }
                        int fails = heartbeatFailCount.incrementAndGet();
                        VoxLinkMod.LOGGER.warn("心跳失败 ({}/{}): {}", fails, MAX_HEARTBEAT_FAILS, response.error);
                        if ("SERVER_403".equals(response.error) || "SERVER_404".equals(response.error)) {
                            VoxLinkMod.LOGGER.warn("服务端临时错误({})，不计入心跳失败", response.error);
                            heartbeatFailCount.decrementAndGet();
                        }
                        if (fails >= MAX_HEARTBEAT_FAILS) {
                            VoxLinkMod.LOGGER.error("心跳失败太多次，房间可能丢了");
                            handleRoomLost();
                        }
                    } else {
                        heartbeatFailCount.set(0);

                        long baseInterval = Math.max(VoxLinkMod.getConfig().getHeartbeatInterval(), MIN_HEARTBEAT_INTERVAL);
                        if (currentHeartbeatInterval > baseInterval) {
                            VoxLinkMod.LOGGER.info("心跳恢复，恢复间隔到{}s", baseInterval);
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
                                VoxLinkMod.LOGGER.info("调整心跳间隔: 服务端={}s, 实际={}s", serverInterval, effectiveInterval);
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
                    VoxLinkMod.LOGGER.warn("心跳异常 ({}/{}): {}", fails, MAX_HEARTBEAT_FAILS, e.getMessage());
                    if (fails >= MAX_HEARTBEAT_FAILS) {
                        handleRoomLost();
                    }
                    return null;
                });
        } catch (Exception e) {
            VoxLinkMod.LOGGER.error("心跳任务同步错误", e);
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
                            VoxLinkMod.LOGGER.info("[RoomManager] 信号轮询 #{}: {}ms, success={}, hasSignals={}",
                                seq, elapsed, response.success,
                                response.success && response.data != null && (response.data.has("s") || response.data.has("signals")));
                        }
                        try {
                            handleSignalPollResponse(response);
                        } catch (Exception e) {
                            VoxLinkMod.LOGGER.warn("信号轮询响应处理出错: {}", e.getMessage());
                        }
                    })
                    .exceptionally(e -> {
                        long elapsed = System.currentTimeMillis() - startTime;
                        VoxLinkMod.LOGGER.warn("信号轮询 #{} 错误 ({}ms): {}", seq, elapsed, e.getMessage());
                        return null;
                    })
                    .whenComplete((r, e) -> signalPollInFlight.set(false));
        } catch (Exception e) {
            signalPollInFlight.set(false);
            VoxLinkMod.LOGGER.error("信号轮询同步错误", e);
        }
    }

    private void handleSignalPollResponse(SignalingClient.ApiResponse response) {
        if (response.success && response.data != null && (response.data.has("s") || response.data.has("signals"))) {
            String sigKey = response.data.has("s") ? "s" : "signals";
            String tsKey = response.data.has("ts") ? "ts" : "timestamp";
            if (!response.data.get(sigKey).isJsonArray()) return;
            var signals = response.data.getAsJsonArray(sigKey);
            VoxLinkMod.LOGGER.debug("[RoomManager] 信号轮询: 收到{}条信号", signals.size());
            for (var element : signals) {
                if (!element.isJsonObject()) continue;
                JsonObject signal = element.getAsJsonObject();
                String sigType = signal.has("type") ? signal.get("type").getAsString() : "unknown";
                VoxLinkMod.LOGGER.info("[RoomManager] 信号分发: type={}, from={}", sigType, signal.has("from") ? signal.get("from").getAsString() : "?");
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
            VoxLinkMod.LOGGER.warn("[RoomManager] 信号轮询失败: {} - {}", response.error, response.message);
            if ("RATE_LIMITED".equals(response.error) || "CDN_ERROR".equals(response.error)
                    || "SERVER_403".equals(response.error) || "SERVER_404".equals(response.error)) {
                backoffSignalPollInterval();
                return;
            }
            if ("ROOM_EVICTED".equals(response.error)) {
                VoxLinkMod.LOGGER.warn("房间被服务器踢出(信号轮询)");
                notifyRoomEvicted();
            } else if ("ROOM_NOT_FOUND".equals(response.error) || "ROOM_EXPIRED".equals(response.error) || "INVALID_TOKEN".equals(response.error) || "ROOM_CLOSED".equals(response.error)) {
                VoxLinkMod.LOGGER.warn("房间在服务端已不存在: {}", response.error);
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
            VoxLinkMod.LOGGER.warn("信号轮询限流/CDN错误，退避到{}ms", newInterval);
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
            VoxLinkMod.LOGGER.warn("跳过格式错误的信号: 缺少type或from");
            return;
        }
        String type = signal.get("type").getAsString();
        String from = signal.get("from").getAsString();
        JsonObject data = signal.has("data") && signal.get("data").isJsonObject() ? signal.getAsJsonObject("data") : new JsonObject();

        VoxLinkMod.LOGGER.debug("收到信号: type={}, from={}", type, from);

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
            default -> VoxLinkMod.LOGGER.debug("未知信号类型: {}", type);
        }
    }

    public ConnectionManager getConnectionManager() {
        return connectionManager;
    }

    public boolean isConnectionCycleActive() {
        return connectionManager.isConnectionCycleActive();
    }

    public RoomState getRoomState() {
        return currentRoom.get();
    }

    public boolean compareAndSetRoomState(RoomState expect, RoomState update) {
        return currentRoom.compareAndSet(expect, update);
    }

    private void handleConnected(String from, JsonObject data) {
        VoxLinkMod.LOGGER.info("对端已连接: {}", from);
        RoomState st = currentRoom.get();
        if (st != null && st != PENDING && st.roomInfo.isHost()) {
            boolean guestOp = st.roomInfo.isGuestOp();
            scheduler.schedule(() -> {
                try {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc == null) return;
                    mc.execute(() -> {
                        try {
                            var server = mc.getSingleplayerServer();
                            if (server == null) return;
                            String hostName = mc.getUser().getName();
                            for (var player : server.getPlayerList().getPlayers()) {
                                String name = player.getName().getString();
                                if (name.equals(hostName)) continue;
                                String cmd = guestOp ? "op " + name : "deop " + name;
                                server.getCommands().performPrefixedCommand(
                                    server.createCommandSourceStack(), cmd);
                                VoxLinkMod.LOGGER.info("[RoomManager] {}访客: {}", guestOp ? "自动OP" : "自动DEOP", name);
                            }
                        } catch (Exception e) {
                            VoxLinkMod.LOGGER.warn("[RoomManager] 访客OP处理失败: {}", e.getMessage());
                        }
                    });
                } catch (Exception e) {
                        VoxLinkMod.LOGGER.warn("[RoomManager] handleConnected异常: {}", e.getMessage());
                    }
                }, NAT_UPDATE_DELAY_SEC, TimeUnit.SECONDS);
        }
    }

    private void handleDisconnect(String from, JsonObject data) {
        VoxLinkMod.LOGGER.info("对端已断开: {}", from);
        RoomState state = currentRoom.get();
        if (state != null && state != PENDING && state.roomInfo.isHost() && from != null) {
            UdpHolePuncher puncher = connectionManager.removeHolePuncher("host");
            if (puncher != null) puncher.close();
            ReliableUdpTransport transport = connectionManager.removeUdpTransport(from);
            if (transport != null) {
                try { transport.close(); } catch (Exception ignored) {}
            }
        }
        if (state != null && state != PENDING && !state.roomInfo.isHost() && from != null && ("host".equals(from) || from.startsWith("host_"))) {
            handleRoomLost("HOST_DISCONNECTED");
        }
    }

    private void handleHostClosing(String from, JsonObject data) {
        VoxLinkMod.LOGGER.info("主机正在关闭房间");
        handleRoomLost("HOST_CLOSED");
    }

    private void handleRoomEvicted(String from, JsonObject data) {
        VoxLinkMod.LOGGER.warn("房间被服务器驱逐");
        notifyRoomEvicted();
    }

    private void notifyRoomEvicted() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            mc.execute(() -> {
                if (mc.player != null) {
                    mc.player.displayClientMessage(
                            Component.translatable("voxlink.chat.evicted_notice"), false);
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
            net.minecraft.network.chat.MutableComponent prefix = Component.translatable("voxlink.chat.error_prefix");
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
