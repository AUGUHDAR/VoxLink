import icu.wuhui.voxlink.network.StdTurnClient;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 生产验证：StdTurnClient（真实源码）→ 162.251.94.31:3478 双端互绑全链路。
 * 流程：派生 secret（与 server-go/节点同算法）→ 手工签 TURN REST 凭证 →
 * A/B 两 session 各自 Allocate → 互 CreatePermission+ChannelBind → ChannelData 互发 echo。
 */
public class VerifyMain {
   static final String NODE_HOST = "162.251.94.31";
   static final int NODE_PORT = 3478;
   static final String NODE_KEY_HEX = "5bcec0f7aa39af26a4a3a9935be9ea92b74ef02c768e1da5b5c7d8255cac199e";

   public static void main(String[] args) throws Exception {
      // [1] 派生 secret：hex(HMAC-SHA256(nodeKey, "voxlink-stdturn-auth-v1"))
      byte[] nodeKey = hexToBytes(NODE_KEY_HEX);
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(nodeKey, "HmacSHA256"));
      byte[] secretBytes = mac.doFinal("voxlink-stdturn-auth-v1".getBytes("UTF-8"));
      String secret = bytesToHex(secretBytes);
      System.out.println("[1] derived secret=" + secret.substring(0, 16) + "...");

      // [2] 签 TURN REST 凭证（同 GenerateLongTermTURNRESTCredentials 算法）
      String[] credA = signCred(secret, "verifyA");
      String[] credB = signCred(secret, "verifyB");
      System.out.println("[2] credA user=" + credA[0] + " credB user=" + credB[0]);

      // [3] A Allocate
      StdTurnClient.StdTurnSession sa = StdTurnClient.allocate(NODE_HOST, NODE_PORT, credA[0], credA[1], 6000);
      if (sa == null) { fail("A allocate"); return; }
      System.out.println("[3] A allocated relay=" + sa.relayAddr);

      // [4] B Allocate
      StdTurnClient.StdTurnSession sb = StdTurnClient.allocate(NODE_HOST, NODE_PORT, credB[0], credB[1], 6000);
      if (sb == null) { fail("B allocate"); return; }
      System.out.println("[4] B allocated relay=" + sb.relayAddr);

      // [5] A→B / B→A ChannelBind（同 channel 0x4000 互不冲突——各在各的 allocation 上）
      if (!StdTurnClient.channelBind(sa, sb.relayAddr, StdTurnClient.CHANNEL_BASE, 6000)) { fail("A channelBind"); return; }
      System.out.println("[5a] A bound peer=" + sb.relayAddr);
      if (!StdTurnClient.channelBind(sb, sa.relayAddr, StdTurnClient.CHANNEL_BASE, 6000)) { fail("B channelBind"); return; }
      System.out.println("[5b] B bound peer=" + sa.relayAddr);

      // [6] ChannelData 数据面：A→B
      StdTurnClient.StdTurnPathCodec codecA = new StdTurnClient.StdTurnPathCodec(StdTurnClient.CHANNEL_BASE);
      StdTurnClient.StdTurnPathCodec codecB = new StdTurnClient.StdTurnPathCodec(StdTurnClient.CHANNEL_BASE);
      byte[] payload = "voxlink-stdturn-java-verify".getBytes("UTF-8");
      byte[] packet = codecA.encode(payload);
      sa.socket.send(new DatagramPacket(packet, packet.length, sa.endpoint()));
      byte[] buf = new byte[2048];
      DatagramPacket resp = new DatagramPacket(buf, buf.length);
      sb.socket.setSoTimeout(5000);
      sb.socket.receive(resp);
      byte[] got = codecB.decode(buf, resp.getLength());
      if (got == null || !new String(got, "UTF-8").equals("voxlink-stdturn-java-verify")) {
         fail("A→B ChannelData");
         return;
      }
      System.out.println("[6] A→B ChannelData ok (" + got.length + "B)");

      // [7] B→A echo
      byte[] echo = codecB.encode("echo-ok".getBytes("UTF-8"));
      sb.socket.send(new DatagramPacket(echo, echo.length, sb.endpoint()));
      resp.setLength(buf.length);
      sa.socket.setSoTimeout(5000);
      sa.socket.receive(resp);
      got = codecA.decode(buf, resp.getLength());
      if (got == null || !new String(got, "UTF-8").equals("echo-ok")) {
         fail("B→A ChannelData");
         return;
      }
      System.out.println("[7] B→A ChannelData echo ok");

      // [8] Refresh 保活（发了就算）+ 拆除
      StdTurnClient.refreshQuiet(sa, 600);
      StdTurnClient.refreshQuiet(sb, 600);
      StdTurnClient.close(sa);
      StdTurnClient.close(sb);
      System.out.println("[8] refresh+close ok");
      System.out.println("ALL PASS");
   }

   static String[] signCred(String secret, String userId) throws Exception {
      long expiry = System.currentTimeMillis() / 1000 + 600;
      String username = expiry + ":" + userId;
      Mac mac = Mac.getInstance("HmacSHA1");
      mac.init(new SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA1"));
      String password = Base64.getEncoder().encodeToString(mac.doFinal(username.getBytes("UTF-8")));
      return new String[]{username, password};
   }

   static void fail(String stage) { System.out.println("FAIL: " + stage); }

   static byte[] hexToBytes(String hex) {
      byte[] out = new byte[hex.length() / 2];
      for (int i = 0; i < out.length; i++) { out[i] = (byte)Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16); }
      return out;
   }

   static String bytesToHex(byte[] b) {
      StringBuilder sb = new StringBuilder();
      for (byte x : b) { sb.append(String.format("%02x", x)); }
      return sb.toString();
   }
}
