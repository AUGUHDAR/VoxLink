package icu.wuhui.voxlink.network;

import com.google.gson.JsonObject;

// APP端空壳
public class DataRouter {
    public enum Priority {
        CRITICAL,
        STATUS,
        NORMAL
    }

    public DataRouter(P2POverlayManager overlayManager, SignalingClient signalingClient) {
    }

    public void setRoomContext(String roomCode, String token, boolean isHost) {
    }

    public void route(Priority priority, String type, JsonObject data, String toTarget) {
    }

    public static Priority classifyType(String type) {
        return Priority.NORMAL;
    }
}
