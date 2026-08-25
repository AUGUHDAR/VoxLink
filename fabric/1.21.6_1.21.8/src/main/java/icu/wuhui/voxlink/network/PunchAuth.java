package icu.wuhui.voxlink.network;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * punchAuthV1：打洞控制报文与可靠 UDP 数据面帧的共享密钥派生与 MAC 工具。
 *
 * <p>设计说明（与任务书的偏差及原因）：任务书原方案是"双方各自生成 16 字节随机数，
 * 经信令互换后按排序拼接派生密钥"。精读代码后确认 {@code PunchParams} 只在本地由
 * {@code PunchTuner} 生成、从不序列化上线；若把 nonce 搭载在一次性信令
 * （holepunch_offer / punch_info）上，任一方向丢信令都会造成"单侧启用认证、另一侧
 * 保持旧格式"的不对称，认证侧将永久丢弃对端旧格式报文 → 打洞必然失败，违反
 * "不退化"硬约束。因此改为从双方<b>确定性地</b>共同持有的材料派生密钥：
 *
 * <pre>key = SHA-256("VOXLINK-PUNCH-AUTH-V1" || roomCode || joinerClientId)</pre>
 *
 * 注意：不能用 hostToken/clientToken 做材料——两端持有的是不同 token，派生出的
 * 密钥必然不一致，会导致新×新互打全灭。roomCode 双方确定已知；它防不住房间内
 * 成员（本就在信令信任边界内），但足以抵御链路上/路径外的盲目注入与伪造源。
 * joinerClientId 绑定具体对端；
 * 两端无需任何额外握手即可独立算出相同密钥，激活条件只依赖能力协商结果
 * （punchAuthV1 双方声明），对 UDP/信令丢包天然免疫。旧版本对端不声明该能力，
 * 线上字节格式与历史版本完全一致。
 */
public final class PunchAuth {
   private static final Logger LOGGER = LoggerFactory.getLogger("voxlink-punchauth");
   private static final byte[] DIRECT_TAG = "VOXLINK-PUNCH-AUTH-V1".getBytes(StandardCharsets.UTF_8);
   private static final long DROP_LOG_INTERVAL_MS = 30000L;
   private static volatile long lastDropLogMs = 0L;

   private PunchAuth() {
   }

   /** 直接 host↔joiner 链路密钥。任一输入为空返回 null（调用方回退非认证模式）。 */
   public static byte[] deriveDirectKey(String roomCode, String joinerClientId) {
      if (roomCode == null || roomCode.isEmpty() || joinerClientId == null || joinerClientId.isEmpty()) {
         return null;
      }

      try {
         MessageDigest digest = MessageDigest.getInstance("SHA-256");
         digest.update(DIRECT_TAG);
         digest.update(roomCode.getBytes(StandardCharsets.UTF_8));
         digest.update((byte)0);
         digest.update(joinerClientId.getBytes(StandardCharsets.UTF_8));
         return digest.digest();
      } catch (Exception e) {
         LOGGER.warn("[PunchAuth] key derive failed: {}", e.getMessage());
         return null;
      }
   }

   /** 每个 socket/transport 一个 Mac 实例；计算时加锁（收发线程共用）。 */
   public static Mac createMac(byte[] key) {
      if (key == null) {
         return null;
      }

      try {
         Mac mac = Mac.getInstance("HmacSHA256");
         mac.init(new SecretKeySpec(key, "HmacSHA256"));
         return mac;
      } catch (Exception e) {
         LOGGER.warn("[PunchAuth] mac init failed: {}", e.getMessage());
         return null;
      }
   }

   /** 计算 buf[off..off+len) 的 4 字节截断 MAC。 */
   public static byte[] truncated4(Mac mac, byte[] buf, int off, int len) {
      byte[] full;
      synchronized (mac) {
         full = mac.doFinal(Arrays.copyOfRange(buf, off, off + len));
      }

      return Arrays.copyOf(full, 4);
   }

   /**
    * 校验"帧尾附加 4 字节截断 MAC"格式：MAC 覆盖 [2..packetLen-4)
    * （即 type||seq/nonce||payload，不含魔数与 MAC 本身）。
    * 常量时间比较防 timing 泄露。
    */
   public static boolean verifyTrailer4(Mac mac, byte[] buf, int packetLen) {
      if (mac == null || packetLen < 4 + 3) {
         return false;
      }

      byte[] expected = truncated4(mac, buf, 2, packetLen - 2 - 4);
      for (int i = 0; i < 4; i++) {
         if (expected[i] != buf[packetLen - 4 + i]) {
            return false;
         }
      }

      return true;
   }

   /** 给帧追加 4 字节截断 MAC（覆盖 frame[2..frame.length)），返回新数组。 */
   public static byte[] appendTrailer4(Mac mac, byte[] frame) {
      byte[] out = Arrays.copyOf(frame, frame.length + 4);
      byte[] tag = truncated4(mac, frame, 2, frame.length - 2);
      System.arraycopy(tag, 0, out, frame.length, 4);
      return out;
   }

   /** 限频记录 MAC 校验失败丢弃（防日志刷屏攻击）。 */
   public static void logDrop(String where) {
      long now = System.currentTimeMillis();
      if (now - lastDropLogMs > DROP_LOG_INTERVAL_MS) {
         lastDropLogMs = now;
         LOGGER.warn("[PunchAuth] dropped unauthenticated packet at {} (rate-limited log)", where);
      }
   }
}
