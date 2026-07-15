package icu.wuhui.voxlink.network;

import com.google.gson.JsonObject;
import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.network.P2POverlayManager.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DataRouter {
    private static final Logger LOGGER = LoggerFactory.getLogger("voxlink-router");

    public enum Priority {
        CRITICAL,
        STATUS,
        NORMAL
    }

    private final P2POverlayManager overlayManager;
    private final SignalingClient signalingClient;

    private volatile String roomCode;
    private volatile String token;
    private volatile boolean isHost;

    public DataRouter(P2POverlayManager overlayManager, SignalingClient signalingClient) {
        this.overlayManager = overlayManager;
        this.signalingClient = signalingClient;
    }

    public void setRoomContext(String roomCode, String token, boolean isHost) {
        this.roomCode = roomCode;
        this.token = token;
        this.isHost = isHost;
    }

    public void route(Priority priority, String type, JsonObject data, String toTarget) {
        switch (priority) {
            case CRITICAL:
                routeToServer(type, data, toTarget);
                break;
            case STATUS:
            case NORMAL:
                routeToP2P(priority, type, data, toTarget);
                break;
        }
    }

    private void routeToServer(String type, JsonObject data, String toTarget) {
        if (roomCode == null || token == null) {
            LOGGER.warn("没法路由到服务器，没有房间上下文");
            return;
        }

        signalingClient.sendSignal(roomCode, token, isHost, type, data, toTarget != null ? toTarget : "")
                .thenAccept(response -> {
                    if (!response.success) {
                        LOGGER.warn("关键信号'{}'发送失败: {}", type, response.error);
                    }
                })
                .exceptionally(ex -> { LOGGER.error("关键信号投递失败", ex); return null; });
    }

    private void routeToP2P(Priority priority, String type, JsonObject data, String toTarget) {
        if (overlayManager.getRole() == Role.NONE) {
            LOGGER.debug("P2P不可用，走服务器: {}", type);
            routeToServer(type, data, toTarget);
            return;
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("type", type);
        payload.add("data", data);

        String priorityStr = (priority == Priority.STATUS) ? "L1" : "L2";
        overlayManager.sendData(toTarget, priorityStr, payload);
    }

    public static Priority classifyType(String type) {
        if (type == null) return Priority.CRITICAL;

        switch (type) {
            case "room_create":
            case "room_join":
            case "room_leave":
            case "room_update":
            case "host_closing":
            case "join_request":
            case "initial_signal":
            case "holepunch_info":
            case "ai_moderation":
            case "admin_config_sync":
            case "room_list_change":
                return Priority.CRITICAL;

            case "player_online_status":
            case "input_state":
            case "typing_indicator":
                return Priority.STATUS;

            case "room_announcement":
            case "data_sync":
            case "status_update":
            default:
                return Priority.NORMAL;
        }
    }
}
