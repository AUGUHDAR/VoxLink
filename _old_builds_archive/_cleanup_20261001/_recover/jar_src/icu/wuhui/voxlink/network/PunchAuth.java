package icu.wuhui.voxlink.network;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PunchAuth {
   private static final Logger LOGGER = LoggerFactory.getLogger("voxlink-punchauth");
   private static final byte[] DIRECT_TAG = "VOXLINK-PUNCH-AUTH-V1".getBytes(StandardCharsets.UTF_8);
   private static final long DROP_LOG_INTERVAL_MS = 30000L;
   private static volatile long lastDropLogMs = 0L;

   private PunchAuth() {
   }

   public static byte[] deriveDirectKey(String roomCode, String joinerClientId) {
      if (roomCode != null && !roomCode.isEmpty() && joinerClientId != null && !joinerClientId.isEmpty()) {
         try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(DIRECT_TAG);
            digest.update(roomCode.getBytes(StandardCharsets.UTF_8));
            digest.update((byte)0);
            digest.update(joinerClientId.getBytes(StandardCharsets.UTF_8));
            return digest.digest();
         } catch (Exception var3) {
            LOGGER.warn("[PunchAuth] key derive failed: {}", var3.getMessage());
            return null;
         }
      } else {
         return null;
      }
   }

   public static Mac createMac(byte[] key) {
      if (key == null) {
         return null;
      } else {
         try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac;
         } catch (Exception var2) {
            LOGGER.warn("[PunchAuth] mac init failed: {}", var2.getMessage());
            return null;
         }
      }
   }

   public static byte[] truncated4(Mac mac, byte[] buf, int off, int len) {
      byte[] full;
      synchronized (mac) {
         full = mac.doFinal(Arrays.copyOfRange(buf, off, off + len));
      }

      return Arrays.copyOf(full, 4);
   }

   public static boolean verifyTrailer4(Mac mac, byte[] buf, int packetLen) {
      if (mac != null && packetLen >= 7) {
         byte[] expected = truncated4(mac, buf, 2, packetLen - 2 - 4);

         for (int i = 0; i < 4; i++) {
            if (expected[i] != buf[packetLen - 4 + i]) {
               return false;
            }
         }

         return true;
      } else {
         return false;
      }
   }

   public static byte[] appendTrailer4(Mac mac, byte[] frame) {
      byte[] out = Arrays.copyOf(frame, frame.length + 4);
      byte[] tag = truncated4(mac, frame, 2, frame.length - 2);
      System.arraycopy(tag, 0, out, frame.length, 4);
      return out;
   }

   public static void logDrop(String where) {
      long now = System.currentTimeMillis();
      if (now - lastDropLogMs > 30000L) {
         lastDropLogMs = now;
         LOGGER.warn("[PunchAuth] dropped unauthenticated packet at {} (rate-limited log)", where);
      }
   }
}
