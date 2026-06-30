package icu.wuhui.voxlink.network;

import com.google.gson.JsonObject;
import icu.wuhui.voxlink.VoxLinkMod;
import net.minecraft.client.Minecraft;
import icu.wuhui.voxlink.network.P2POverlayManager.PacketHandler;
import icu.wuhui.voxlink.network.P2POverlayManager.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class TopologyClient implements PacketHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("voxlink-topology");
    public static void shutdown() {
        TOPOLOGY_DELAY_EXECUTOR.shutdown();
        try {
            if (!TOPOLOGY_DELAY_EXECUTOR.awaitTermination(2, TimeUnit.SECONDS)) {
                TOPOLOGY_DELAY_EXECUTOR.shutdownNow();
            }
        } catch (InterruptedException e) {
            TOPOLOGY_DELAY_EXECUTOR.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    private static final ScheduledExecutorService TOPOLOGY_DELAY_EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "VoxLink-Topology-Delay");
        t.setDaemon(true);
        return t;
    });

    private final P2POverlayManager overlayManager;
    private final SignalingClient signalingClient;

    private final AtomicInteger knownGeneration = new AtomicInteger(0);
    private final AtomicBoolean optimizing = new AtomicBoolean(false);
    private final AtomicBoolean reportedReady = new AtomicBoolean(false);

    private volatile String roomCode;
    private volatile String token;
    private volatile boolean isHost;
    private volatile String nodeId;

    private volatile Consumer<Boolean> onOptimizingChanged;
    private volatile Consumer<JsonObject> onDataReceived;

    public TopologyClient(SignalingClient signalingClient) {
        this.signalingClient = signalingClient;
        this.overlayManager = new P2POverlayManager(getOrCreateNodeId(), 0);
    }

    public CompletableFuture<Void> onRoomJoined(String roomCode, String token, boolean isHost,
                                                  String clientId, int generation) {
        this.roomCode = roomCode;
        this.token = token;
        this.isHost = isHost;
        this.nodeId = isHost ? "host_" + roomCode : "client_" + clientId;
        this.knownGeneration.set(generation);
        this.reportedReady.set(false);

        try {
            overlayManager.start(this);
            overlayManager.setNodeId(this.nodeId);
            LOGGER.info("P2P overlay启动，节点{}", nodeId);
        } catch (IOException e) {
            LOGGER.warn("P2P overlay启动失败，保持直连: {}", e.getMessage());
        }

        return CompletableFuture.completedFuture(null);
    }

    private volatile String cachedUpstreamId;
    private volatile String cachedDownstreamId;

    public JsonObject pollAndGetPeerLatency() {
        JsonObject peerLatency = new JsonObject();

        if (overlayManager.getRole() == Role.NONE) return peerLatency;

        int ul = overlayManager.getUpstreamLatency();
        int dl = overlayManager.getDownstreamLatency();

        if (ul > 0 && cachedUpstreamId != null) {
            peerLatency.addProperty(cachedUpstreamId, ul);
        }
        if (dl > 0 && cachedDownstreamId != null) {
            peerLatency.addProperty(cachedDownstreamId, dl);
        }

        return peerLatency;
    }

    public void handleTopologyInstruction(JsonObject instruction) {
        if (instruction == null || instruction.isJsonNull()) {
            return;
        }

        String action = (instruction.has("action") && !instruction.get("action").isJsonNull())
                ? instruction.get("action").getAsString() : "";
        int gen = instruction.has("generation") ? instruction.get("generation").getAsInt() : 0;

        if (gen <= knownGeneration.get()) {
            return;
        }
        knownGeneration.set(gen);
        reportedReady.set(false);

        LOGGER.info("处理拓扑指令: action={}, gen={}", action, gen);

        switch (action) {
            case "become_head":
                handleBecomeHead(instruction);
                break;
            case "connect_to":
                handleConnectTo(instruction);
                break;
            case "direct_server":
                handleDirectServer(instruction);
                break;
            default:
                LOGGER.warn("未知拓扑指令: {}", action);
        }
    }

    public void handlePollInstructions(JsonObject response) {
        if (response == null) return;

        if (response.has("optimizing") && response.get("optimizing").getAsBoolean()) {
            setOptimizing(true);
        }

        if (response.has("mode") && "direct_fallback".equals(response.get("mode").getAsString())) {
            if (overlayManager.getRole() != Role.NONE) {
                overlayManager.switchToDirectMode();
            }
        }

        if (response.has("instructions") && response.get("instructions").isJsonArray()) {
            var arr = response.getAsJsonArray("instructions");
            for (var elem : arr) {
                if (elem.isJsonObject()) {
                    handleTopologyInstruction(elem.getAsJsonObject());
                }
            }
        }
    }

    private void executeOnClientThread(Runnable action) {
        try {
            Minecraft.getInstance().execute(action);
        } catch (NoClassDefFoundError e) {
            action.run();
        }
    }

    public void handleTopologySignal(String type, JsonObject data) {
        switch (type) {
            case "topology_optimization_done":
                setOptimizing(false);
                LOGGER.info("拓扑优化完成");
                break;
            case "topology_change":
                if (roomCode != null && token != null) {
                    signalingClient.pollTopology(roomCode, token, isHost, knownGeneration.get())
                            .thenAccept(response -> {
                                if (response.success && response.data != null) {
                                    executeOnClientThread(() -> handlePollInstructions(response.data));
                                }
                            });
                }
                break;
        }
    }

    public void onRoomLeft() {
        overlayManager.stop();
        this.roomCode = null;
        this.token = null;
        this.nodeId = null;
        this.cachedUpstreamId = null;
        this.cachedDownstreamId = null;
        knownGeneration.set(0);
        setOptimizing(false);
        reportedReady.set(false);
    }

    public boolean isOptimizing() {
        return optimizing.get();
    }

    public void setOnOptimizingChanged(Consumer<Boolean> callback) {
        this.onOptimizingChanged = callback;
    }

    public void setOnDataReceived(Consumer<JsonObject> callback) {
        this.onDataReceived = callback;
    }

    public int getKnownGeneration() {
        return knownGeneration.get();
    }

    public String getNodeId() {
        return nodeId;
    }

    public P2POverlayManager getOverlayManager() {
        return overlayManager;
    }

    private void handleBecomeHead(JsonObject instruction) {
        String downstream = instruction.has("downstream") && !instruction.get("downstream").isJsonNull()
                ? instruction.get("downstream").getAsString() : null;
        String downstreamIp = instruction.has("downstream_ip") ? instruction.get("downstream_ip").getAsString() : null;
        int downstreamPort = instruction.has("downstream_port") ? instruction.get("downstream_port").getAsInt() : 0;
        cachedDownstreamId = downstream;
        overlayManager.becomeHead(downstream, downstreamIp, downstreamPort);
        setOptimizing(true);
        TOPOLOGY_DELAY_EXECUTOR.schedule(this::reportLinkReady, 1000, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private void handleConnectTo(JsonObject instruction) {
        String upstream = instruction.has("upstream") ? instruction.get("upstream").getAsString() : "";
        String upstreamIp = instruction.has("upstream_ip") ? instruction.get("upstream_ip").getAsString() : "0.0.0.0";
        int upstreamPort = instruction.has("upstream_port") ? instruction.get("upstream_port").getAsInt() : 0;
        String downstream = instruction.has("downstream") && !instruction.get("downstream").isJsonNull()
                ? instruction.get("downstream").getAsString() : null;

        cachedUpstreamId = upstream;
        cachedDownstreamId = downstream;

        overlayManager.connectUpstream(upstream, upstreamIp, upstreamPort);
        if (downstream != null) {
            int downstreamPort = instruction.has("downstream_port") ? instruction.get("downstream_port").getAsInt() : 0;
            String downstreamIp = instruction.has("downstream_ip") ? instruction.get("downstream_ip").getAsString() : null;
            overlayManager.setDownstream(downstream, downstreamIp, downstreamPort);
        }

        setOptimizing(true);

        TOPOLOGY_DELAY_EXECUTOR.schedule(this::reportLinkReady, 500, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private void handleDirectServer(JsonObject instruction) {
        String reason = instruction.has("reason") ? instruction.get("reason").getAsString() : "unknown";
        LOGGER.info("切换到直连模式，原因: {}", reason);
        overlayManager.switchToDirectMode();
        setOptimizing(true);
        reportLinkReady();
    }

    private void reportLinkReady() {
        if (reportedReady.getAndSet(true)) return;
        if (roomCode == null || token == null) return;
        signalingClient.reportLinkReady(roomCode, token, isHost)
                .thenAccept(response -> {
                    if (response.success) {
                        LOGGER.info("链路就绪上报成功");
                    } else {
                        LOGGER.warn("链路就绪上报失败: {}", response.error);
                    }
                });
    }

    private void setOptimizing(boolean value) {
        boolean prev = optimizing.getAndSet(value);
        if (prev != value && onOptimizingChanged != null) {
            onOptimizingChanged.accept(value);
        }
    }

    @Override
    public void onDataReceived(String from, String priority, JsonObject payload) {
        LOGGER.debug("收到P2P数据，from={}, priority={}", from, priority);
        if (onDataReceived != null) {
            onDataReceived.accept(payload);
        }
    }

    @Override
    public void onLinkReady() {
        LOGGER.info("P2P链路就绪");
        reportLinkReady();
    }

    @Override
    public void onLinkLost(String reason) {
        LOGGER.warn("P2P链路断了: {}", reason);
        overlayManager.switchToDirectMode();
    }

    private static String getOrCreateNodeId() {
        return java.util.UUID.randomUUID().toString().substring(0, 8);
    }
}