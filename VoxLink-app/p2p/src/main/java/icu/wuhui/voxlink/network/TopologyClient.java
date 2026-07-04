package icu.wuhui.voxlink.network;

import com.google.gson.JsonObject;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

// APP端空壳
public class TopologyClient implements P2POverlayManager.PacketHandler {
    private final SignalingClient signalingClient;
    private final P2POverlayManager overlayManager;

    public TopologyClient(SignalingClient signalingClient) {
        this.signalingClient = signalingClient;
        this.overlayManager = new P2POverlayManager(null, 0);
    }

    public static void shutdown() {
    }

    public CompletableFuture<Void> onRoomJoined(String roomCode, String token, boolean isHost,
                                                  String clientId, int generation) {
        return CompletableFuture.completedFuture(null);
    }

    public JsonObject pollAndGetPeerLatency() {
        return new JsonObject();
    }

    public void handleTopologyInstruction(JsonObject instruction) {
    }

    public void handlePollInstructions(JsonObject response) {
    }

    public void handleTopologySignal(String type, JsonObject data) {
    }

    public void onRoomLeft() {
    }

    public boolean isOptimizing() {
        return false;
    }

    public void setOnOptimizingChanged(Consumer<Boolean> callback) {
    }

    public void setOnDataReceived(Consumer<JsonObject> callback) {
    }

    public int getKnownGeneration() {
        return 0;
    }

    public String getNodeId() {
        return null;
    }

    public P2POverlayManager getOverlayManager() {
        return overlayManager;
    }

    @Override
    public void onDataReceived(String from, String priority, JsonObject payload) {
    }

    @Override
    public void onLinkReady() {
    }

    @Override
    public void onLinkLost(String reason) {
    }
}
