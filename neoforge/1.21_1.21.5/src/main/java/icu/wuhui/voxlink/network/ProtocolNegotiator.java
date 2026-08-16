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
   public static final Set<String> CURRENT_CAPABILITIES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList("relay", "ice_restart", "continuous_retry")));

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

   public static String describe(RoomInfo.PeerInfo peer) {
      if (peer == null) {
         return "null";
      } else {
         return isLegacyPeer(peer) ? "legacy(v0)" : "v" + peer.protocolVersion + "+" + peer.capabilities;
      }
   }
}
