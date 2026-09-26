package icu.wuhui.voxlink.network;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.zip.CRC32;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 标准 TURN 客户端（RFC 5766/8656 + RFC 5389 STUN，对接 pion/turn v5 节点 :3478）。
 * 与自定义协议 v1（TurnRelayClient）并列的第二中继栈：
 *   - 无节点侧配对：两端各自 Allocate 得 relay 地址，经信令交换后互建
 *     CreatePermission + ChannelBind，数据面走 ChannelData（4 字节头）
 *   - 凭证：TURN REST 时间窗（信令 /relay/stdturn/cred 签发），长项凭证
 *     key = MD5(username:realm:password)，MESSAGE-INTEGRITY = HMAC-SHA1
 *   - 保活：Refresh(lifetime=600) 周期重发（发了就算，300s 周期对 600s 寿命有冗余）；
 *     ChannelBind 10min 刷新（permission 随之刷新，RFC 5766 §11/§8）
 *   - 数据面经 StdTurnPathCodec 对接 UdpPath/ReliableUdpTransport（传输层零改动）
 * 零 MC 依赖：可独立 javac 编译验证。
 */
public class StdTurnClient {
   private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("VoxLink-StdTurn");

   // ---- STUN 消息类型（RFC 5389 §6 + RFC 5766 §13 方法/类编码） ----
   private static final int MT_BIND_REQ = 0x0001;
   private static final int MT_ALLOCATE_REQ = 0x0003;
   private static final int MT_ALLOCATE_OK = 0x0103;
   private static final int MT_ALLOCATE_ERR = 0x0113;
   private static final int MT_REFRESH_REQ = 0x0004;
   private static final int MT_REFRESH_OK = 0x0104;
   private static final int MT_REFRESH_ERR = 0x0114;
   private static final int MT_CREATE_PERM_REQ = 0x0008;
   private static final int MT_CREATE_PERM_OK = 0x0108;
   private static final int MT_CREATE_PERM_ERR = 0x0118;
   private static final int MT_CHANNEL_BIND_REQ = 0x0009;
   private static final int MT_CHANNEL_BIND_OK = 0x0109;
   private static final int MT_CHANNEL_BIND_ERR = 0x0119;

   // ---- 属性类型 ----
   private static final int AT_MAPPED_ADDRESS = 0x0001;
   private static final int AT_USERNAME = 0x0006;
   private static final int AT_MESSAGE_INTEGRITY = 0x0008;
   private static final int AT_ERROR_CODE = 0x0009;
   private static final int AT_CHANNEL_NUMBER = 0x000C;
   private static final int AT_LIFETIME = 0x000D;
   private static final int AT_XOR_PEER_ADDRESS = 0x0012;
   private static final int AT_REALM = 0x0014;
   private static final int AT_NONCE = 0x0015;
   private static final int AT_XOR_RELAYED_ADDRESS = 0x0016;
   private static final int AT_REQUESTED_TRANSPORT = 0x0019;
   private static final int AT_XOR_MAPPED_ADDRESS = 0x0020;
   private static final int AT_SOFTWARE = 0x8022;
   private static final int AT_FINGERPRINT = 0x8028;

   private static final int MAGIC_COOKIE = 0x2112A442;
   private static final int FP_XOR = 0x5354554E;
   private static final int TRANSPORT_UDP = 17;
   public static final int CHANNEL_BASE = 0x4000;
   public static final int DEFAULT_LIFETIME_SEC = 600;

   private static final int STUN_HEADER = 20;
   private static final int TX_MAX_ROUNDS = 4;      // 500/1500/3500/7500ms，总 ~12s 预算内
   private static final long TX_RTO_MS = 500L;

   // ---- 会话 ----

   /** 标准 TURN 会话：Allocate 成功即持有 relay 地址；ChannelBind 后进入数据面。 */
   public static class StdTurnSession {
      public final DatagramSocket socket;
      public final InetSocketAddress serverAddr;
      public final String username;
      final byte[] authKey;      // MD5(username:realm:password)
      String realm;
      String nonce;
      public volatile InetSocketAddress relayAddr;   // 本端 relay 地址（Allocate 结果）
      public volatile InetSocketAddress peerAddr;    // 对端 relay 地址（ChannelBind 目标）
      public volatile int channel = -1;              // ChannelBind 成功后的 channel 号
      public volatile long lifetimeSec = DEFAULT_LIFETIME_SEC;

      StdTurnSession(DatagramSocket socket, InetSocketAddress serverAddr, String username, byte[] authKey) {
         this.socket = socket;
         this.serverAddr = serverAddr;
         this.username = username;
         this.authKey = authKey;
      }

      public InetSocketAddress endpoint() {
         return this.serverAddr;
      }
   }

   // ---- STUN 消息构建器 ----

   private static final class MsgBuilder {
      final int type;
      final byte[] txId = new byte[12];
      final java.io.ByteArrayOutputStream attrs = new java.io.ByteArrayOutputStream();
      int attrsLen;

      MsgBuilder(int type) {
         this.type = type;
         java.util.concurrent.ThreadLocalRandom.current().nextBytes(this.txId);
      }

      void putStr(int at, String v) {
         put(at, v.getBytes(StandardCharsets.UTF_8));
      }

      void putUint32(int at, long v) {
         put(at, new byte[]{(byte)(v >> 24), (byte)(v >> 16), (byte)(v >> 8), (byte)v});
      }

      void put(int at, byte[] v) {
         try {
            this.attrs.write(new byte[]{(byte)(at >> 8), (byte)at, (byte)(v.length >> 8), (byte)v.length});
            this.attrs.write(v);
            int pad = (4 - (v.length & 3)) & 3;
            for (int i = 0; i < pad; i++) {
               this.attrs.write(0);
            }
            this.attrsLen += 4 + v.length + pad;
         } catch (IOException e) {
            throw new IllegalStateException(e);
         }
      }

      void putXorAddress(int at, InetSocketAddress addr, int cookie, byte[] txId) {
         byte[] ip = addr.getAddress().getAddress();
         byte[] v = new byte[ip.length == 4 ? 8 : 20];
         v[1] = (byte)(ip.length == 4 ? 0x01 : 0x02);
         int port = addr.getPort() ^ (cookie >>> 16);
         v[2] = (byte)(port >> 8);
         v[3] = (byte)port;
         if (ip.length == 4) {
            int x = ((ip[0] & 0xFF) << 24 | (ip[1] & 0xFF) << 16 | (ip[2] & 0xFF) << 8 | (ip[3] & 0xFF)) ^ cookie;
            v[4] = (byte)(x >> 24);
            v[5] = (byte)(x >> 16);
            v[6] = (byte)(x >> 8);
            v[7] = (byte)x;
         } else {
            for (int i = 0; i < 16; i++) {
               v[4 + i] = (byte)(ip[i] ^ (i < 4 ? (cookie >>> (24 - i * 8)) & 0xFF : txId[i - 4]));
            }
         }
         put(at, v);
      }

      /** 序列化：withIntegrity 时先算 MI 再算 FP（RFC 5389 §15.4/§15.5）。 */
      byte[] build(byte[] integrityKey) {
         byte[] attrBytes = this.attrs.toByteArray();
         if (integrityKey == null) {
            return finish(attrBytes, attrBytes.length, false, 0);
         }
         // 第一步：length 计到 MI 属性结束（+24），算 MI
         int lenWithMi = attrBytes.length + 24;
         byte[] head = header(this.type, lenWithMi, this.txId);
         byte[] miInput = new byte[head.length + attrBytes.length];
         System.arraycopy(head, 0, miInput, 0, head.length);
         System.arraycopy(attrBytes, 0, miInput, head.length, attrBytes.length);
         byte[] mi = hmacSha1(integrityKey, miInput);
         byte[] withMi = new byte[attrBytes.length + 24];
         System.arraycopy(attrBytes, 0, withMi, 0, attrBytes.length);
         withMi[attrBytes.length] = (byte)(AT_MESSAGE_INTEGRITY >> 8);
         withMi[attrBytes.length + 1] = (byte)AT_MESSAGE_INTEGRITY;
         withMi[attrBytes.length + 2] = 0;
         withMi[attrBytes.length + 3] = 20;
         System.arraycopy(mi, 0, withMi, attrBytes.length + 4, 20);
         // 第二步：length 计到 FP 结束（+8），算 CRC32
         return finish(withMi, withMi.length + 8, true, 0);
      }

      private byte[] finish(byte[] attrBytes, int totalLen, boolean withFp, int unused) {
         byte[] head = header(this.type, totalLen, this.txId);
         if (!withFp) {
            byte[] out = new byte[head.length + attrBytes.length];
            System.arraycopy(head, 0, out, 0, head.length);
            System.arraycopy(attrBytes, 0, out, head.length, attrBytes.length);
            return out;
         }
         byte[] out = new byte[head.length + attrBytes.length + 8];
         System.arraycopy(head, 0, out, 0, head.length);
         System.arraycopy(attrBytes, 0, out, head.length, attrBytes.length);
         CRC32 crc = new CRC32();
         crc.update(out, 0, head.length + attrBytes.length);
         long fp = crc.getValue() ^ FP_XOR;
         int off = head.length + attrBytes.length;
         out[off] = (byte)(AT_FINGERPRINT >> 8);
         out[off + 1] = (byte)AT_FINGERPRINT;
         out[off + 2] = 0;
         out[off + 3] = 4;
         out[off + 4] = (byte)(fp >> 24);
         out[off + 5] = (byte)(fp >> 16);
         out[off + 6] = (byte)(fp >> 8);
         out[off + 7] = (byte)fp;
         return out;
      }
   }

   // ---- STUN 消息解析 ----

   static final class StunMessage {
      int type;
      byte[] txId;
      // 快查表：属性号 <0x40 直接索引（本协议栈用到的属性全部落在该区间）
      byte[][] attrs = new byte[64][];
      int errorCode = -1;
      String errorReason = "";

      byte[] attr(int at) {
         return at >= 0 && at < 64 ? this.attrs[at] : null;
      }
   }

   private static StunMessage parse(byte[] buf, int len) {
      if (len < STUN_HEADER || (buf[0] & 0xC0) != 0) {
         return null;
      }
      int cookie = readU32(buf, 4);
      if (cookie != MAGIC_COOKIE) {
         return null;
      }
      StunMessage m = new StunMessage();
      m.type = ((buf[0] & 0xFF) << 8) | (buf[1] & 0xFF);
      m.txId = Arrays.copyOfRange(buf, 8, 20);
      int off = STUN_HEADER;
      int declared = readU16(buf, 2);
      int end = Math.min(len, STUN_HEADER + declared);
      while (off + 4 <= end) {
         int at = readU16(buf, off);
         int alen = readU16(buf, off + 2);
         if (off + 4 + alen > end) {
            break;
         }
         byte[] v = Arrays.copyOfRange(buf, off + 4, off + 4 + alen);
         if (at < 64) {
            m.attrs[at] = v;
         }
         if (at == AT_ERROR_CODE && alen >= 4) {
            m.errorCode = ((v[2] & 0x07) * 100) + (v[3] & 0xFF);
            m.errorReason = alen > 4 ? new String(v, 4, alen - 4, StandardCharsets.UTF_8) : "";
         }
         off += 4 + ((alen + 3) & ~3);
      }
      return m;
   }

   // ---- 同步事务（allocate~channelBind 阶段 socket 独占读） ----

   /** 发送并等响应：RTO 倍增重传，匹配 txId 的响应返回；超时 null。 */
   private static StunMessage transact(StdTurnSession s, MsgBuilder req, byte[] integrityKey, long totalBudgetMs) throws IOException {
      byte[] pkt = req.build(integrityKey);
      long deadline = System.currentTimeMillis() + totalBudgetMs;
      byte[] buf = new byte[2048];
      DatagramPacket resp = new DatagramPacket(buf, buf.length);
      long rto = TX_RTO_MS;
      int round = 0;

      while (System.currentTimeMillis() < deadline && round < TX_MAX_ROUNDS) {
         s.socket.send(new DatagramPacket(pkt, pkt.length, s.serverAddr));
         round++;
         long roundEnd = Math.min(deadline, System.currentTimeMillis() + rto);
         while (System.currentTimeMillis() < roundEnd) {
            resp.setLength(buf.length);
            try {
               s.socket.receive(resp);
            } catch (SocketTimeoutException e) {
               break;
            }
            StunMessage m = parse(buf, resp.getLength());
            if (m != null && Arrays.equals(m.txId, req.txId)) {
               return m;
            }
         }
         rto *= 3;
      }
      return null;
   }

   // ---- 公开 API ----

   /** 长项凭证 key：MD5(username:realm:password)（RFC 5389 §15.4）。 */
   public static byte[] longTermKey(String username, String realm, String password) {
      try {
         return MessageDigest.getInstance("MD5").digest((username + ":" + realm + ":" + password).getBytes(StandardCharsets.UTF_8));
      } catch (java.security.NoSuchAlgorithmException e) {
         throw new IllegalStateException(e);
      }
   }

   /**
    * Allocate：无凭证探一次拿 realm/nonce（401 挑战）→ 带凭证重试 → relay 地址。
    * 返回就绪会话（relayAddr 已填）；失败 null。
    */
   public static StdTurnSession allocate(String host, int port, String username, String password, int timeoutMs) {
      DatagramSocket socket = null;
      try {
         socket = new DatagramSocket();
         socket.setSoTimeout(200);
         InetSocketAddress serverAddr = new InetSocketAddress(InetAddress.getByName(host), port);

         // 第一轮：无凭证 → 401 + REALM + NONCE
         // 探包只发一次 = 一次 UDP 丢包就把整条标准 TURN 判死（实测 CGNAT 下会偶发全丢）：
         // 改成最多 3 发、每发预算压到 3s，成功仍然只花一个往返
         StunMessage challenge = null;
         for (int probeTry = 0; probeTry < 3 && challenge == null; probeTry++) {
            MsgBuilder probe = new MsgBuilder(MT_ALLOCATE_REQ);
            probe.putUint32(AT_REQUESTED_TRANSPORT, TRANSPORT_UDP << 24);
            probe.putStr(AT_SOFTWARE, "voxlink");
            StdTurnSession tmp = new StdTurnSession(socket, serverAddr, username, null);
            challenge = transact(tmp, probe, null, Math.min(timeoutMs, 3000));
         }

         if (challenge == null) {
            LOGGER.warn("[StdTurn] allocate probe no response from {}:{}", host, port);
            socket.close();
            return null;
         }
         if (challenge.type != MT_ALLOCATE_ERR || challenge.errorCode != 401) {
            LOGGER.warn("[StdTurn] allocate probe unexpected type=0x{} err={}", Integer.toHexString(challenge.type), challenge.errorCode);
            socket.close();
            return null;
         }
         byte[] realmB = challenge.attr(AT_REALM);
         byte[] nonceB = challenge.attr(AT_NONCE);
         if (realmB == null || nonceB == null) {
            LOGGER.warn("[StdTurn] 401 without realm/nonce");
            socket.close();
            return null;
         }
         String realm = new String(realmB, StandardCharsets.UTF_8);
         String nonce = new String(nonceB, StandardCharsets.UTF_8);
         byte[] key = longTermKey(username, realm, password);

         StdTurnSession session = new StdTurnSession(socket, serverAddr, username, key);
         session.realm = realm;
         session.nonce = nonce;

         // 第二轮：带凭证 Allocate
         MsgBuilder req = new MsgBuilder(MT_ALLOCATE_REQ);
         req.putUint32(AT_REQUESTED_TRANSPORT, TRANSPORT_UDP << 24);
         req.putStr(AT_USERNAME, username);
         req.putStr(AT_REALM, realm);
         req.putStr(AT_NONCE, nonce);
         req.putStr(AT_SOFTWARE, "voxlink");
         StunMessage ok = transact(session, req, key, timeoutMs);
         if (ok == null) {
            LOGGER.warn("[StdTurn] allocate(auth) no response");
            socket.close();
            return null;
         }
         if (ok.type == MT_ALLOCATE_ERR) {
            LOGGER.warn("[StdTurn] allocate rejected err={} {}", ok.errorCode, ok.errorReason);
            socket.close();
            return null;
         }
         if (ok.type != MT_ALLOCATE_OK) {
            LOGGER.warn("[StdTurn] allocate unexpected type=0x{}", Integer.toHexString(ok.type));
            socket.close();
            return null;
         }
         byte[] relayed = ok.attr(AT_XOR_RELAYED_ADDRESS);
         InetSocketAddress relayAddr = decodeXorAddress(relayed, ok.txId);
         if (relayAddr == null) {
            LOGGER.warn("[StdTurn] allocate ok but no XOR-RELAYED-ADDRESS");
            socket.close();
            return null;
         }
         session.relayAddr = relayAddr;
         byte[] life = ok.attr(AT_LIFETIME);
         if (life != null && life.length == 4) {
            session.lifetimeSec = ((life[0] & 0xFFL) << 24) | ((life[1] & 0xFFL) << 16) | ((life[2] & 0xFFL) << 8) | (life[3] & 0xFFL);
         }
         LOGGER.info("[StdTurn] allocated relay={} lifetime={}s via {}:{}", relayAddr, session.lifetimeSec, host, port);
         return session;
      } catch (IOException e) {
         LOGGER.warn("[StdTurn] allocate io fail: {}", e.getMessage());
         if (socket != null) {
            socket.close();
         }
         return null;
      }
   }

   /** CreatePermission（对端 relay IP）+ ChannelBind（对端 relay 地址 → channel）。 */
   public static boolean channelBind(StdTurnSession s, InetSocketAddress peerAddr, int channel, int timeoutMs) {
      try {
         MsgBuilder perm = new MsgBuilder(MT_CREATE_PERM_REQ);
         perm.putStr(AT_USERNAME, s.username);
         perm.putStr(AT_REALM, s.realm);
         perm.putStr(AT_NONCE, s.nonce);
         perm.putXorAddress(AT_XOR_PEER_ADDRESS, new InetSocketAddress(peerAddr.getAddress(), 0), MAGIC_COOKIE, perm.txId);
         StunMessage pm = transact(s, perm, s.authKey, timeoutMs);
         if (pm == null || pm.type != MT_CREATE_PERM_OK) {
            LOGGER.warn("[StdTurn] CreatePermission fail type={} err={}", pm == null ? "timeout" : Integer.toHexString(pm.type), pm == null ? -1 : pm.errorCode);
            return false;
         }

         MsgBuilder bind = new MsgBuilder(MT_CHANNEL_BIND_REQ);
         bind.putStr(AT_USERNAME, s.username);
         bind.putStr(AT_REALM, s.realm);
         bind.putStr(AT_NONCE, s.nonce);
         bind.put(AT_CHANNEL_NUMBER, new byte[]{(byte)(channel >> 8), (byte)channel, 0, 0});
         bind.putXorAddress(AT_XOR_PEER_ADDRESS, peerAddr, MAGIC_COOKIE, bind.txId);
         StunMessage bm = transact(s, bind, s.authKey, timeoutMs);
         if (bm == null || bm.type != MT_CHANNEL_BIND_OK) {
            LOGGER.warn("[StdTurn] ChannelBind fail type={} err={}", bm == null ? "timeout" : Integer.toHexString(bm.type), bm == null ? -1 : bm.errorCode);
            return false;
         }
         s.peerAddr = peerAddr;
         s.channel = channel;
         LOGGER.info("[StdTurn] channel 0x{} bound to peer {}", Integer.toHexString(channel), peerAddr);
         return true;
      } catch (IOException e) {
         LOGGER.warn("[StdTurn] channelBind io fail: {}", e.getMessage());
         return false;
      }
   }

   /**
    * Refresh 保活（发了就算：300s 周期对 600s 寿命有冗余，丢包下周补）。
    * lifetimeSec=0 即删除 allocation（拆除用，同样不等响应）。
    * 注意：socket 已交给 RUDP 后调用——响应包会被 codec.decode 丢弃，无影响。
    */
   public static void refreshQuiet(StdTurnSession s, long lifetimeSec) {
      try {
         MsgBuilder req = new MsgBuilder(MT_REFRESH_REQ);
         req.putStr(AT_USERNAME, s.username);
         req.putStr(AT_REALM, s.realm);
         req.putStr(AT_NONCE, s.nonce);
         req.putUint32(AT_LIFETIME, lifetimeSec);
         byte[] pkt = req.build(s.authKey);
         s.socket.send(new DatagramPacket(pkt, pkt.length, s.serverAddr));
      } catch (IOException e) {
         LOGGER.debug("[StdTurn] refresh send failed: {}", e.getMessage());
      }
   }

   /** 拆除：Refresh(lifetime=0) + 关 socket。 */
   public static void close(StdTurnSession s) {
      if (s == null) {
         return;
      }
      refreshQuiet(s, 0);
      try {
         s.socket.close();
      } catch (Exception e) {
      }
   }

   /**
    * ChannelBind 保活重发（发了就算）：channel 10min 寿命，240s 周期重发幂等刷新，
    * permission 随之刷新（RFC 5766 §11/§8）。
    * 与 refreshQuiet 同理：socket 已交 RUDP 后响应被 codec 丢弃属预期，不等响应。
    */
   public static void channelBindQuiet(StdTurnSession s) {
      if (s.peerAddr == null || s.channel < 0) {
         return;
      }
      try {
         MsgBuilder bind = new MsgBuilder(MT_CHANNEL_BIND_REQ);
         bind.putStr(AT_USERNAME, s.username);
         bind.putStr(AT_REALM, s.realm);
         bind.putStr(AT_NONCE, s.nonce);
         bind.put(AT_CHANNEL_NUMBER, new byte[]{(byte)(s.channel >> 8), (byte)s.channel, 0, 0});
         bind.putXorAddress(AT_XOR_PEER_ADDRESS, s.peerAddr, MAGIC_COOKIE, bind.txId);
         byte[] pkt = bind.build(s.authKey);
         s.socket.send(new DatagramPacket(pkt, pkt.length, s.serverAddr));
      } catch (IOException e) {
         LOGGER.debug("[StdTurn] channelBind refresh send failed: {}", e.getMessage());
      }
   }

   // ---- 凭证拉取（经信令 /relay/stdturn/cred） ----

   /** 信令签发的 TURN REST 凭证。 */
   public static class StdCred {
      public String host;
      public int port;
      public String username;
      public String password;
      public long expire;
   }

   /** 拉取凭证：房间 token 鉴权，节点须已启用标准 TURN（stdTurnPort>0）。 */
   public static java.util.concurrent.CompletableFuture<StdCred> fetchCred(SignalingClient sc, String roomCode, String clientId, String token, String nodeId) {
      com.google.gson.JsonObject body = new com.google.gson.JsonObject();
      body.addProperty("roomCode", roomCode);
      body.addProperty("clientId", clientId);
      body.addProperty("token", token);
      body.addProperty("nodeId", nodeId);
      return sc.relayStdTurnCred(body).thenApply(r -> {
         if (!r.success || r.data == null) {
            // 错误码是定位中继故障的唯一线索（MISSING_FIELDS / INVALID_TOKEN / NODE_OFFLINE / RATE_LIMITED），
            // 吞掉它就只能看到一个无信息量的 std_cred_failed
            LOGGER.warn("[StdTurn] cred rejected: error={} message={}", r.error, r.message);
            return null;
         }
         com.google.gson.JsonObject d = r.data;
         StdCred c = new StdCred();
         c.host = d.has("host") ? d.get("host").getAsString() : "";
         c.port = d.has("port") ? d.get("port").getAsInt() : 0;
         c.username = d.has("username") ? d.get("username").getAsString() : "";
         c.password = d.has("password") ? d.get("password").getAsString() : "";
         c.expire = d.has("expire") ? d.get("expire").getAsLong() : 0L;
         if (c.host.isEmpty() || c.port <= 0 || c.username.isEmpty() || c.password.isEmpty()) {
            LOGGER.warn("[StdTurn] cred incomplete: host={} port={} username?{} password?{}",
               c.host, c.port, !c.username.isEmpty(), !c.password.isEmpty());
            return null;
         }
         return c;
      });
   }

   // ---- UdpPath.Codec：rudp 帧 ↔ ChannelData（4 字节头） ----

   /** ChannelData 包封：| channel(2) | length(2) | payload |（RFC 5766 §11.4，无 padding——UDP 载荷无需对齐）。 */
   public static class StdTurnPathCodec implements UdpPath.Codec {
      private final int channel;

      public StdTurnPathCodec(int channel) {
         this.channel = channel;
      }

      @Override
      public byte[] encode(byte[] frame) {
         byte[] out = new byte[4 + frame.length];
         out[0] = (byte)(this.channel >> 8);
         out[1] = (byte)this.channel;
         out[2] = (byte)(frame.length >> 8);
         out[3] = (byte)frame.length;
         System.arraycopy(frame, 0, out, 4, frame.length);
         return out;
      }

      @Override
      public byte[] decode(byte[] packet, int len) {
         if (len < 4) {
            return null;
         }
         // ChannelData：首两位 01（0x4000-0x7FFF）；STUN 消息首两位 00 → null 丢弃
         int ch = ((packet[0] & 0xFF) << 8) | (packet[1] & 0xFF);
         if (ch < CHANNEL_BASE || ch > 0x7FFF || ch != this.channel) {
            return null;
         }
         int payloadLen = ((packet[2] & 0xFF) << 8) | (packet[3] & 0xFF);
         if (4 + payloadLen > len) {
            return null;
         }
         byte[] frame = new byte[payloadLen];
         System.arraycopy(packet, 4, frame, 0, payloadLen);
         return frame;
      }
   }

   // ---- 工具 ----

   static InetSocketAddress decodeXorAddress(byte[] v, byte[] txId) {
      if (v == null || v.length < 8 || v[0] != 0) {
         return null;
      }
      try {
         int port = ((v[2] & 0xFF) << 8 | (v[3] & 0xFF)) ^ (MAGIC_COOKIE >>> 16);
         if (v[1] == 0x01) {
            int x = (readU32(v, 4)) ^ MAGIC_COOKIE;
            return new InetSocketAddress(InetAddress.getByAddress(new byte[]{(byte)(x >> 24), (byte)(x >> 16), (byte)(x >> 8), (byte)x}), port);
         }
         if (v[1] == 0x02 && v.length >= 20) {
            byte[] ip = new byte[16];
            for (int i = 0; i < 16; i++) {
               ip[i] = (byte)(v[4 + i] ^ (i < 4 ? (MAGIC_COOKIE >>> (24 - i * 8)) & 0xFF : txId[i - 4]));
            }
            return new InetSocketAddress(InetAddress.getByAddress(ip), port);
         }
         return null;
      } catch (java.net.UnknownHostException e) {
         return null;
      }
   }

   static byte[] hmacSha1(byte[] key, byte[] data) {
      try {
         Mac mac = Mac.getInstance("HmacSHA1");
         mac.init(new SecretKeySpec(key, "HmacSHA1"));
         return mac.doFinal(data);
      } catch (java.security.GeneralSecurityException e) {
         throw new IllegalStateException(e);
      }
   }

   private static byte[] header(int type, int length, byte[] txId) {
      byte[] h = new byte[STUN_HEADER];
      h[0] = (byte)(type >> 8);
      h[1] = (byte)type;
      h[2] = (byte)(length >> 8);
      h[3] = (byte)length;
      h[4] = (byte)(MAGIC_COOKIE >>> 24);
      h[5] = (byte)(MAGIC_COOKIE >>> 16);
      h[6] = (byte)(MAGIC_COOKIE >>> 8);
      h[7] = (byte)MAGIC_COOKIE;
      System.arraycopy(txId, 0, h, 8, 12);
      return h;
   }

   private static int readU16(byte[] b, int off) {
      return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
   }

   private static int readU32(byte[] b, int off) {
      return (b[off] & 0xFF) << 24 | (b[off + 1] & 0xFF) << 16 | (b[off + 2] & 0xFF) << 8 | (b[off + 3] & 0xFF);
   }
}
