package icu.wuhui.voxlink.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TURN 中继客户端（协议契约：SPECS/turn-protocol-v1.md）。
 * 职责：拉节点列表 → 应用层 UDP PING 测延迟（禁 ICMP 也无碍）→ 选最低延迟节点 →
 * allocate → BIND → 会话保活；并把 rudp 帧包进 TURN DATA 的 UdpPath.Codec 提供给传输层。
 * 流程编排（何时调这里的方法）在 ConnectionManager；本类保持无状态工具 + 会话对象。
 */
public class TurnRelayClient {
   private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("VoxLink-TurnRelay");
   private static final byte[] MAGIC = new byte[]{86, 76};
   private static final byte PROTOCOL_VERSION = 1;
   private static final byte TYPE_PING = 1;
   private static final byte TYPE_PONG = 2;
   private static final byte TYPE_BIND = 3;
   private static final byte TYPE_BIND_RESULT = 4;
   private static final byte TYPE_DATA = 5;
   private static final byte TYPE_KEEPALIVE = 6;
   private static final byte TYPE_KEEPALIVE_ACK = 7;
   private static final byte TYPE_UNBIND = 8;
   public static final byte ROLE_HOST = 1;
   public static final byte ROLE_GUEST = 2;
   public static final int BIND_OK = 0;
   public static final int BIND_BAD_TICKET = 1;
   public static final int BIND_TICKET_EXPIRED = 2;
   public static final int BIND_SESSION_FULL = 3;
   public static final int BIND_ROLE_CONFLICT = 4;
   public static final int BIND_SERVER_BUSY = 5;
   private static final int PING_PROBE_COUNT = 6;
   private static final int PING_TIMEOUT_MS = 800;
   private static final int PROBE_TOTAL_BUDGET_MS = 4000;
   private static final int BIND_TIMEOUT_MS = 2000;
   private static final int KEEPALIVE_INTERVAL_SEC = 15;
   private static final int FETCH_MAX_ATTEMPTS = 4;
   private static final long FETCH_RETRY_DELAY_MS = 800L;
   private static final long NODE_CACHE_TTL_MS = 60_000L;
   private static final Object NODE_CACHE_LOCK = new Object();
   private static SignalingClient nodeCacheOwner;
   private static List<NodeInfo> nodeCache;
   private static long nodeCacheAtMs;

   /**
    * 已分配的 TURN 会话（本端视角）。allocate 响应的两张票分别供两端 BIND：
    * 发起方（guest）用 guestTicket，host 经信令拿到 hostTicket 后各自 BIND。
    */
   public static class TurnSession {
      public final String sessionIdHex;
      public final byte[] sessionId;
      public final byte role;
      public final String ticket;
      public final long expireSec;
      // TCP 兜底激活后被替换：socket→shim 回环口、host/port→shim 地址
      public volatile DatagramSocket socket;
      public volatile String host;
      public volatile int port;
      public volatile TurnTcpChannel tcpChannel = null;
      public volatile boolean bound = false;
      // 保活统计（仅发送侧计数；TURN 节点 90s 无包踢角色，KEEPALIVE 到达即保活）
      public final AtomicLong keepaliveSent = new AtomicLong();

      public TurnSession(String sessionIdHex, byte[] sessionId, String host, int port, byte role, String ticket, long expireSec) {
         this.sessionIdHex = sessionIdHex;
         this.sessionId = sessionId;
         this.host = host;
         this.port = port;
         this.role = role;
         this.ticket = ticket;
         this.expireSec = expireSec;
         DatagramSocket s = null;
         try {
            s = new DatagramSocket();
            s.setSoTimeout(100);
         } catch (IOException e) {
            LOGGER.warn("[TurnRelay] alloc socket failed: {}", e.getMessage());
         }
         this.socket = s;
      }

      public InetSocketAddress endpoint() {
         return new InetSocketAddress(this.host, this.port);
      }

      /** 会话保活（规范 §3：90s 无包踢角色）。由 ConnectionManager 的 scheduler 定时驱动。 */
      public void sendKeepalive() {
         if (this.socket == null || this.socket.isClosed() || !this.bound) {
            return;
         }

         try {
            // 头4 + sessionId16 + role1 = 21
            byte[] p = new byte[21];
            packHeader(p, TYPE_KEEPALIVE);
            hexToBytes(this.sessionIdHex, p, 4);
            p[20] = this.role;
            InetSocketAddress ep = this.endpoint();
            this.socket.send(new DatagramPacket(p, p.length, ep));
            this.keepaliveSent.incrementAndGet();
         } catch (IOException e) {
            LOGGER.debug("[TurnRelay] keepalive send failed: {}", e.getMessage());
         }
      }

      /** UNBIND 并关 socket（切换成功释放 TURN / 断线清理共用）。 */
      public void unbind() {
         try {
            if (this.socket != null && !this.socket.isClosed() && this.bound) {
               byte[] p = new byte[21];
               packHeader(p, TYPE_UNBIND);
               hexToBytes(this.sessionIdHex, p, 4);
               p[20] = this.role;
               this.socket.send(new DatagramPacket(p, p.length, this.endpoint()));
            }
         } catch (IOException e) {
            LOGGER.debug("[TurnRelay] unbind send failed: {}", e.getMessage());
         } finally {
            this.bound = false;
            TurnTcpChannel ch = this.tcpChannel;
            if (ch != null) {
               ch.close();
               this.tcpChannel = null;
            }
            try {
               if (this.socket != null) {
                  this.socket.close();
               }
            } catch (Exception e) {
            }
         }
      }
   }

   // ---------- HTTP（全部经 SignalingClient，路由 /relay/*） ----------

   public static CompletableFuture<Boolean> fetchStatus(SignalingClient sc) {
      return sc.getRelayStatus().thenApply(r -> r.success && r.data != null && r.data.has("enabled") && r.data.get("enabled").getAsBoolean());
   }

   public static CompletableFuture<List<NodeInfo>> fetchNodeList(SignalingClient sc) {
      List<NodeInfo> hit = nodeCacheGet(sc, true);
      if (hit != null) {
         return CompletableFuture.completedFuture(hit);
      }

      return fetchNodeRound(sc, FETCH_MAX_ATTEMPTS);
   }

   /** 首轮加 3 轮重试；全败回退旧缓存。 */
   private static CompletableFuture<List<NodeInfo>> fetchNodeRound(SignalingClient sc, int left) {
      return sc.getRelayList()
         .thenApply(TurnRelayClient::parseNodeList)
         .thenCompose(nodes -> {
            if (!nodes.isEmpty()) {
               nodeCachePut(sc, nodes);
               return CompletableFuture.completedFuture(nodes);
            }

            return fetchNodeRetry(sc, left, "empty");
         })
         .exceptionallyCompose(ex -> fetchNodeRetry(sc, left, ex == null ? "error" : ex.getMessage()));
   }

   private static CompletableFuture<List<NodeInfo>> fetchNodeRetry(SignalingClient sc, int left, String why) {
      if (left > 1) {
         LOGGER.warn("[TurnRelay] relay_list failed ({}), retry {}/{} in {}ms", why, FETCH_MAX_ATTEMPTS - left + 1, FETCH_MAX_ATTEMPTS - 1, FETCH_RETRY_DELAY_MS);
         return CompletableFuture
            .supplyAsync(() -> null, CompletableFuture.delayedExecutor(FETCH_RETRY_DELAY_MS, TimeUnit.MILLISECONDS))
            .thenCompose(v -> fetchNodeRound(sc, left - 1));
      }

      List<NodeInfo> stale = nodeCacheGet(sc, false);
      if (stale != null) {
         LOGGER.warn("[TurnRelay] relay_list failed ({}), using stale cache", why);
         return CompletableFuture.completedFuture(stale);
      }

      return CompletableFuture.completedFuture(new ArrayList<>());
   }

   private static List<NodeInfo> parseNodeList(SignalingClient.ApiResponse r) {
      List<NodeInfo> nodes = new ArrayList<>();
      if (r.success && r.data != null && r.data.has("nodes") && r.data.get("nodes").isJsonArray()) {
         for (JsonElement el : r.data.getAsJsonArray("nodes")) {
            if (!el.isJsonObject()) {
               continue;
            }

            JsonObject n = el.getAsJsonObject();
            NodeInfo info = new NodeInfo();
            info.id = n.has("id") ? n.get("id").getAsString() : "";
            info.name = n.has("name") ? n.get("name").getAsString() : info.id;
            info.host = n.has("host") ? n.get("host").getAsString() : "";
            info.port = n.has("port") ? n.get("port").getAsInt() : 37000;
            if (!info.id.isEmpty() && !info.host.isEmpty() && info.port > 0) {
               nodes.add(info);
            }
         }
      }

      return nodes;
   }

   /** freshOnly=true 只认未过期缓存。 */
   private static List<NodeInfo> nodeCacheGet(SignalingClient sc, boolean freshOnly) {
      synchronized (NODE_CACHE_LOCK) {
         if (sc == null || sc != nodeCacheOwner || nodeCache == null) {
            return null;
         }

         if (freshOnly && System.currentTimeMillis() - nodeCacheAtMs > NODE_CACHE_TTL_MS) {
            return null;
         }

         return new ArrayList<>(nodeCache);
      }
   }

   private static void nodeCachePut(SignalingClient sc, List<NodeInfo> nodes) {
      synchronized (NODE_CACHE_LOCK) {
         nodeCacheOwner = sc;
         nodeCache = new ArrayList<>(nodes);
         nodeCacheAtMs = System.currentTimeMillis();
      }
   }

   public static class Allocation {
      public String sessionIdHex;
      public String host;
      public int port;
      public String hostTicket;
      public String guestTicket;
      public long expireSec;
   }

   public static CompletableFuture<Allocation> allocate(SignalingClient sc, String roomCode, String clientId, String token, String nodeId) {
      JsonObject body = new JsonObject();
      body.addProperty("roomCode", roomCode);
      body.addProperty("clientId", clientId);
      body.addProperty("token", token);
      body.addProperty("nodeId", nodeId);
      return sc.relayAllocate(body).thenApply(r -> {
         if (!r.success || r.data == null) {
            return null;
         }

         JsonObject d = r.data;
         Allocation a = new Allocation();
         a.sessionIdHex = d.has("sessionId") ? d.get("sessionId").getAsString() : "";
         a.host = d.has("host") ? d.get("host").getAsString() : "";
         a.port = d.has("port") ? d.get("port").getAsInt() : 0;
         a.hostTicket = d.has("hostTicket") ? d.get("hostTicket").getAsString() : "";
         a.guestTicket = d.has("guestTicket") ? d.get("guestTicket").getAsString() : "";
         a.expireSec = d.has("expire") ? d.get("expire").getAsLong() : 0L;
         if (a.sessionIdHex.length() != 32 || a.host.isEmpty() || a.port <= 0 || a.guestTicket.isEmpty() || a.hostTicket.isEmpty()) {
            return null;
         }

         return a;
      });
   }

   public static CompletableFuture<Boolean> release(SignalingClient sc, String roomCode, String clientId, String token, String sessionIdHex) {
      JsonObject body = new JsonObject();
      body.addProperty("roomCode", roomCode);
      body.addProperty("clientId", clientId);
      body.addProperty("token", token);
      body.addProperty("sessionId", sessionIdHex);
      return sc.relayRelease(body).thenApply(r -> r.success);
   }

   // ---------- 延迟探测（应用层 UDP PING/PONG，非 ICMP） ----------

   public static class NodeInfo {
      public String id;
      public String name;
      public String host;
      public int port;
   }

   public static class ProbeResult {
      public final NodeInfo node;
      public final int rttMs;

      public ProbeResult(NodeInfo node, int rttMs) {
         this.node = node;
         this.rttMs = rttMs;
      }
   }

   /**
    * 并行探测全部节点：每节点 3 次 PING 取最小 RTT；不通的节点 rtt=-1。
    * 返回按 RTT 升序的可达节点（不可达的排在最后，rtt=-1）。
    */
   public static CompletableFuture<List<ProbeResult>> probeNodes(List<NodeInfo> nodes) {
      ExecutorService pool = Executors.newFixedThreadPool(Math.min(10, Math.max(2, nodes.size())), r -> {
         Thread t = new Thread(r, "VoxLink-TurnProbe");
         t.setDaemon(true);
         return t;
      });
      List<CompletableFuture<ProbeResult>> futures = new ArrayList<>();

      for (NodeInfo node : nodes) {
         futures.add(CompletableFuture.supplyAsync(() -> probeOne(node), pool));
      }

      CompletableFuture<List<ProbeResult>> all = new CompletableFuture<>();
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).whenComplete((v, ex) -> {
         List<ProbeResult> results = new ArrayList<>();

         for (CompletableFuture<ProbeResult> f : futures) {
            try {
               results.add(f.join());
            } catch (Exception e) {
            }
         }

         results.sort((a, b) -> {
            int ra = a.rttMs < 0 ? Integer.MAX_VALUE : a.rttMs;
            int rb = b.rttMs < 0 ? Integer.MAX_VALUE : b.rttMs;
            return Integer.compare(ra, rb);
         });
         all.complete(results);
      });
      return all;
   }

   /** 单节点 3 次探测取最小；每次独立 socket 免粘连，超时/异常算不通。 */
   private static ProbeResult probeOne(NodeInfo node) {
      int best = -1;
      long deadline = System.currentTimeMillis() + (long)PROBE_TOTAL_BUDGET_MS;

      for (int i = 0; i < PING_PROBE_COUNT && System.currentTimeMillis() < deadline; i++) {
         try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(PING_TIMEOUT_MS);
            InetSocketAddress ep = new InetSocketAddress(InetAddress.getByName(node.host), node.port);
            long nonce = System.nanoTime() & 0xFFFFFFFFL;
            long ts = System.currentTimeMillis();
            byte[] p = new byte[20];
            packHeader(p, TYPE_PING);
            writeInt32(p, 4, (int)nonce);
            writeInt64(p, 8, ts);
            long t0 = System.currentTimeMillis();
            s.send(new DatagramPacket(p, p.length, ep));
            byte[] buf = new byte[64];
            DatagramPacket resp = new DatagramPacket(buf, buf.length);

            while (System.currentTimeMillis() - t0 < PING_TIMEOUT_MS) {
               try {
                  s.receive(resp);
               } catch (SocketTimeoutException e) {
                  break;
               }

               if (resp.getLength() >= 24 && buf[0] == MAGIC[0] && buf[1] == MAGIC[1] && buf[3] == TYPE_PONG && readInt32(buf, 4) == (int)nonce) {
                  int rtt = (int)(System.currentTimeMillis() - t0);
                  if (best < 0 || rtt < best) {
                     best = rtt;
                  }

                  break;
               }
            }
         } catch (Exception e) {
            LOGGER.debug("[TurnRelay] probe {} failed: {}", node.id, e.getMessage());
         }
      }

      return new ProbeResult(node, best);
   }

   // ---------- BIND ----------

   /**
    * 向节点 BIND 本端角色并等待 BIND_RESULT（源地址即绑定地址，规范 §3）。
    * 返回 code（0=ok）；session.bound 置位由调用方根据返回值处理。
    */
   public static int bind(TurnRelayClient.TurnSession session) {
      return bindWithRetry(session, 1);
   }

   /**
    * 带外层重试的 BIND（1.1.5）：单轮 5 发共 4.6s 全丢在移动弱网很常见
    * （实证 09-11 23:28: host 5 发无一到达 turn01, 同会话 guest 一次即中）。
    * 整轮失败后隔 1s 再来一轮, rounds 轮内任一成功即返回; 非超时类失败码
    * （票据/角色/满员）重试无意义, 原样返回。
    */
   public static int bindWithRetry(TurnRelayClient.TurnSession session, int rounds) {
      if (session.socket == null || session.socket.isClosed()) {
         return BIND_SERVER_BUSY;
      }
      int code = BIND_SERVER_BUSY;
      for (int attempt = 1; attempt <= Math.max(1, rounds); attempt++) {
         code = bindOnce(session);
         if (code == BIND_OK || code != BIND_SERVER_BUSY) {
            return code;
         }
         if (attempt < rounds) {
            LOGGER.warn("[TurnRelay] bind all sends lost (round {}/{}), retry in 1s", attempt, rounds);
            try {
               Thread.sleep(1000L);
            } catch (InterruptedException e) {
               Thread.currentThread().interrupt();
               return code;
            }
         }
      }
      // UDP 轮次耗尽仍无响应（BIND_FAILED_5=UDP黑洞实证）→ TCP 兜底
      return engageTcpFallback(session, code);
   }

   /**
    * TCP 兜底：UDP 全丢时经本地 shim 转走 TCP 长连接（同端口）。
    * 只改 session 的 socket/host/port，bind/keepalive/rudp 零感知；
    * 节点侧跨承载接管保证会话连续。
    */
   private static int engageTcpFallback(TurnRelayClient.TurnSession session, int udpCode) {
      if (session.tcpChannel != null) {
         return udpCode;
      }
      LOGGER.warn("[TurnRelay] UDP bind no response (code={}) after retries, engaging TCP fallback to {}:{}", udpCode, session.host, session.port);
      int preferPort = session.socket != null && !session.socket.isClosed() ? session.socket.getLocalPort() : 0;
      if (session.socket != null) {
         try {
            session.socket.close();
         } catch (Exception e) {
         }
      }
      TurnTcpChannel ch;
      try {
         ch = TurnTcpChannel.open(session.host, session.port, preferPort);
      } catch (IOException e) {
         LOGGER.warn("[TurnRelay] TCP fallback connect failed: {}", e.getMessage());
         return udpCode;
      }
      session.tcpChannel = ch;
      session.socket = ch.clientSocket();
      session.host = "127.0.0.1";
      session.port = ch.shimPort();
      int code = BIND_SERVER_BUSY;
      for (int attempt = 1; attempt <= 2; attempt++) {
         code = bindOnce(session);
         if (code != BIND_SERVER_BUSY) {
            break;
         }
         if (attempt < 2) {
            try {
               Thread.sleep(1000L);
            } catch (InterruptedException e) {
               Thread.currentThread().interrupt();
               break;
            }
         }
      }
      if (code == BIND_OK) {
         LOGGER.warn("[TurnRelay] TCP fallback bind OK (UDP path dead)");
      } else {
         LOGGER.warn("[TurnRelay] TCP fallback bind failed code={}", code);
      }
      return code;
   }

   private static int bindOnce(TurnRelayClient.TurnSession session) {
      if (session.socket == null || session.socket.isClosed()) {
         return BIND_SERVER_BUSY;
      }

      byte[] ticketBytes = session.ticket.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
      byte[] p = new byte[4 + 16 + 1 + 2 + ticketBytes.length];
      packHeader(p, TYPE_BIND);
      hexToBytes(session.sessionIdHex, p, 4);
      p[20] = session.role;
      writeUint16(p, 21, ticketBytes.length);
      System.arraycopy(ticketBytes, 0, p, 23, ticketBytes.length);

      // UDP 单发丢包即败（2026-08-31 生产：移动→节点首包丢失，host bind 超时中继失败）。
      // 0/900/1800/2700/3600ms 五次重发，总窗口 4.6s；发送失败（DNS 解析等）记 WARN 后继续重试。
      // 2026-09-06 加固：弱网 3 发全丢率高，加到 5 发给丢包余量（guest 流程 20s 总超时内仍够用）。
      long deadline = System.currentTimeMillis() + 4600L;
      byte[] buf = new byte[64];
      DatagramPacket resp = new DatagramPacket(buf, buf.length);
      int sent = 0;
      long nextSendAt = 0L;

      while (System.currentTimeMillis() < deadline) {
         long now = System.currentTimeMillis();
         if (sent < 5 && now >= nextSendAt) {
            try {
               session.socket.send(new DatagramPacket(p, p.length, session.endpoint()));
               sent++;
            } catch (IOException e) {
               LOGGER.warn("[TurnRelay] bind send fail #{} to {}:{}: {}", sent + 1, session.host, session.port, e.getMessage());
            }

            nextSendAt = now + 900L;
         }

         resp.setLength(buf.length);
         try {
            session.socket.receive(resp);
         } catch (SocketTimeoutException e) {
            continue;
         } catch (IOException e) {
            break;
         }

         if (resp.getLength() >= 22 && buf[0] == MAGIC[0] && buf[1] == MAGIC[1] && buf[3] == TYPE_BIND_RESULT) {
            byte[] sid = new byte[16];
            System.arraycopy(buf, 4, sid, 0, 16);
            if (java.util.Arrays.equals(sid, session.sessionId) && buf[20] == session.role) {
               int code = buf[21] & 0xFF;
               session.bound = code == BIND_OK;
               if (sent > 1) {
                  LOGGER.info("[TurnRelay] bind ok after {} sends (role={})", sent, session.role);
               }
               return code;
            }
         }
      }

      return BIND_SERVER_BUSY;
   }

   // ---------- UdpPath.Codec：rudp 帧 ↔ TURN DATA ----------

   /** 把 rudp 帧包进 TURN DATA（encode）/从 TURN DATA 剥出 rudp 帧（decode）。 */
   public static class TurnPathCodec implements UdpPath.Codec {
      private final byte[] sessionId;
      private final byte fromRole;
      private final byte toRole;

      public TurnPathCodec(byte[] sessionId, byte fromRole, byte toRole) {
         this.sessionId = sessionId;
         this.fromRole = fromRole;
         this.toRole = toRole;
      }

      @Override
      public byte[] encode(byte[] frame) {
         byte[] out = new byte[4 + 16 + 1 + 1 + 2 + frame.length];
         packHeader(out, TYPE_DATA);
         System.arraycopy(this.sessionId, 0, out, 4, 16);
         out[20] = this.fromRole;
         out[21] = this.toRole;
         writeUint16(out, 22, frame.length);
         System.arraycopy(frame, 0, out, 24, frame.length);
         return out;
      }

      @Override
      public byte[] decode(byte[] packet, int len) {
         if (len < 24 || packet[0] != MAGIC[0] || packet[1] != MAGIC[1] || packet[3] != TYPE_DATA) {
            return null;
         }

         for (int i = 0; i < 16; i++) {
            if (packet[4 + i] != this.sessionId[i]) {
               return null;
            }
         }

         int payloadLen = ((packet[22] & 0xFF) << 8) | (packet[23] & 0xFF);
         if (24 + payloadLen > len) {
            return null;
         }

         byte[] frame = new byte[payloadLen];
         System.arraycopy(packet, 24, frame, 0, payloadLen);
         return frame;
      }
   }

   // ---------- 字节工具（大端，与规范一致） ----------

   private static void packHeader(byte[] buf, byte type) {
      buf[0] = MAGIC[0];
      buf[1] = MAGIC[1];
      buf[2] = PROTOCOL_VERSION;
      buf[3] = type;
   }

   private static void writeInt32(byte[] buf, int off, int v) {
      buf[off] = (byte)(v >> 24);
      buf[off + 1] = (byte)(v >> 16);
      buf[off + 2] = (byte)(v >> 8);
      buf[off + 3] = (byte)v;
   }

   private static int readInt32(byte[] buf, int off) {
      return (buf[off] & 0xFF) << 24 | (buf[off + 1] & 0xFF) << 16 | (buf[off + 2] & 0xFF) << 8 | buf[off + 3] & 0xFF;
   }

   private static void writeInt64(byte[] buf, int off, long v) {
      for (int i = 0; i < 8; i++) {
         buf[off + i] = (byte)(v >> (56 - i * 8));
      }
   }

   private static void writeUint16(byte[] buf, int off, int v) {
      buf[off] = (byte)(v >> 8);
      buf[off + 1] = (byte)v;
   }

   private static void hexToBytes(String hex, byte[] out, int off) {
      for (int i = 0; i < 16; i++) {
         out[off + i] = (byte)Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
      }
   }
}
