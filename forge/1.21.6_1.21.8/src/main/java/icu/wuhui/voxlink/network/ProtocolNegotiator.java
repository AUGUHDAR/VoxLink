package icu.wuhui.voxlink.network;

import icu.wuhui.voxlink.room.RoomInfo;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class ProtocolNegotiator {
   public static final int PROTOCOL_VERSION_LEGACY = 0;
   public static final int PROTOCOL_VERSION_7 = 7;
   public static final String CAP_RELAY = "relay";
   public static final String CAP_ICE_RESTART = "ice_restart";
   public static final String CAP_CONTINUOUS_RETRY = "continuous_retry";
   /**
    * P0/P1 安全修复新增能力：UDP 打洞控制报文与可靠 UDP 数据面帧附带
    * HMAC-SHA256 截断 MAC（密钥由房间令牌派生）。仅在双方都广播该能力时启用；
    * 任一端为旧版本时线上字节格式与历史版本完全一致。
    */
   public static final String CAP_PUNCH_AUTH_V1 = "punchAuthV1";
   /** P2P overlay 链路握手/中继报文来源认证能力（同样双方协商启用）。 */
   public static final String CAP_OVERLAY_AUTH_V1 = "overlayAuthV1";
   public static final Set<String> CURRENT_CAPABILITIES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList("relay", "ice_restart", "continuous_retry", CAP_PUNCH_AUTH_V1, CAP_OVERLAY_AUTH_V1)));

   private ProtocolNegotiator() {
   }

   public static boolean isLegacyPeer(RoomInfo.PeerInfo peer) {
      return peer == null ? true : peer.protocolVersion == 0 || peer.capabilities == null || peer.capabilities.isEmpty();
   }

   public static boolean supportsRelay(RoomInfo.PeerInfo peer) {
      return peer == null ? false : peer.capabilities != null && peer.capabilities.contains("relay");
   }

   public static boolean supportsIceRestart(RoomInfo.PeerInfo peer) {
      return peer == null ? false : peer.capabilities != null && peer.capabilities.contains("ice_restart");
   }

   public static boolean supportsContinuousRetry(RoomInfo.PeerInfo peer) {
      return peer == null ? false : peer.capabilities != null && peer.capabilities.contains("continuous_retry");
   }

   /** 对端是否声明 punchAuthV1（决定本端是否可进入打洞/数据面 MAC 认证模式的一半条件）。 */
   public static boolean supportsPunchAuth(RoomInfo.PeerInfo peer) {
      return peer != null && peer.capabilities != null && peer.capabilities.contains(CAP_PUNCH_AUTH_V1);
   }

   /** 房主侧能力是否声明 punchAuthV1（加入方视角）。 */
   public static boolean hostSupportsPunchAuth(RoomInfo room) {
      return room != null && !room.isHostLegacy() && room.getHostCapabilities().contains(CAP_PUNCH_AUTH_V1);
   }

   /** 对端是否声明 overlayAuthV1。 */
   public static boolean supportsOverlayAuth(RoomInfo.PeerInfo peer) {
      return peer != null && peer.capabilities != null && peer.capabilities.contains(CAP_OVERLAY_AUTH_V1);
   }

   /** 本端自身能力表是否含指定能力（新构建恒真，保留入口以便未来按配置降级）。 */
   public static boolean selfSupports(String capability) {
      return capability != null && CURRENT_CAPABILITIES.contains(capability);
   }

   public static String describe(RoomInfo.PeerInfo peer) {
      if (peer == null) {
         return "null";
      } else {
         return isLegacyPeer(peer) ? "legacy(v0)" : "v" + peer.protocolVersion + "+" + peer.capabilities;
      }
   }
}
