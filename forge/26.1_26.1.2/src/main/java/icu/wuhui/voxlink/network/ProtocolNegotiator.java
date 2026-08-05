package icu.wuhui.voxlink.network;

import icu.wuhui.voxlink.room.RoomInfo;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

//debounce 协议版本协商: 1.0.7+客户端能力声明 老版本无声明视为legacy零能力 走纯直连
public final class ProtocolNegotiator {
    public static final int PROTOCOL_VERSION_LEGACY = 0;
    public static final int PROTOCOL_VERSION_7 = 7;

    public static final String CAP_RELAY = "relay";
    public static final String CAP_ICE_RESTART = "ice_restart";
    public static final String CAP_CONTINUOUS_RETRY = "continuous_retry";

    //debounce 当前1.0.7客户端声明的能力集合
    public static final Set<String> CURRENT_CAPABILITIES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            CAP_RELAY, CAP_ICE_RESTART, CAP_CONTINUOUS_RETRY
    )));

    private ProtocolNegotiator() {}

    public static boolean isLegacyPeer(RoomInfo.PeerInfo peer) {
        if (peer == null) return true;
        return peer.protocolVersion == PROTOCOL_VERSION_LEGACY || peer.capabilities == null || peer.capabilities.isEmpty();
    }

    public static boolean supportsRelay(RoomInfo.PeerInfo peer) {
        if (peer == null) return false;
        return peer.capabilities != null && peer.capabilities.contains(CAP_RELAY);
    }

    public static boolean supportsIceRestart(RoomInfo.PeerInfo peer) {
        if (peer == null) return false;
        return peer.capabilities != null && peer.capabilities.contains(CAP_ICE_RESTART);
    }

    public static boolean supportsContinuousRetry(RoomInfo.PeerInfo peer) {
        if (peer == null) return false;
        return peer.capabilities != null && peer.capabilities.contains(CAP_CONTINUOUS_RETRY);
    }

    public static String describe(RoomInfo.PeerInfo peer) {
        if (peer == null) return "null";
        if (isLegacyPeer(peer)) return "legacy(v0)";
        return "v" + peer.protocolVersion + "+" + peer.capabilities;
    }
}
