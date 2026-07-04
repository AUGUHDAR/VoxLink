package icu.wuhui.voxlink.network;

import com.google.gson.JsonObject;
import java.io.IOException;

// APP端空壳
public class P2POverlayManager {
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

    public P2POverlayManager(String nodeId, int port) {
    }

    public void start(PacketHandler handler) throws IOException {
    }

    public void connectUpstream(String peerId, String host, int port) {
    }

    public void setDownstream(String peerId, String host, int port) {
    }

    public void becomeHead(String downstreamPeerId, String downstreamHost, int downstreamPort) {
    }

    public void switchToDirectMode() {
    }

    public void sendData(String targetNodeId, String priority, JsonObject payload) {
    }

    public int getUpstreamLatency() {
        return 0;
    }

    public int getDownstreamLatency() {
        return 0;
    }

    public Role getRole() {
        return Role.NONE;
    }

    public void setNodeId(String id) {
    }

    public void stop() {
    }

    public int getLocalPort() {
        return 0;
    }
}
