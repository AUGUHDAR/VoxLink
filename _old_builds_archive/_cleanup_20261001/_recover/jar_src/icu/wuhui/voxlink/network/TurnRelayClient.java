package icu.wuhui.voxlink.network;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TurnRelayClient {
   private static final Logger LOGGER = LoggerFactory.getLogger("VoxLink-TurnRelay");
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
   private static final long NODE_CACHE_TTL_MS = 60000L;
   private static final Object NODE_CACHE_LOCK = new Object();
   private static SignalingClient nodeCacheOwner;
   private static List<TurnRelayClient.NodeInfo> nodeCache;
   private static long nodeCacheAtMs;

   public static CompletableFuture<Boolean> fetchStatus(SignalingClient sc) {
      return sc.getRelayStatus().thenApply(r -> r.success && r.data != null && r.data.has("enabled") && r.data.get("enabled").getAsBoolean());
   }

   public static CompletableFuture<List<TurnRelayClient.NodeInfo>> fetchNodeList(SignalingClient sc) {
      List<TurnRelayClient.NodeInfo> hit = nodeCacheGet(sc, true);
      return hit != null ? CompletableFuture.completedFuture(hit) : fetchNodeRound(sc, 4);
   }

   private static CompletableFuture<List<TurnRelayClient.NodeInfo>> fetchNodeRound(SignalingClient sc, int left) {
      return sc.getRelayList().thenApply(TurnRelayClient::parseNodeList).thenCompose(nodes -> {
         if (!nodes.isEmpty()) {
            nodeCachePut(sc, (List<TurnRelayClient.NodeInfo>)nodes);
            return CompletableFuture.completedFuture((List<TurnRelayClient.NodeInfo>)nodes);
         } else {
            return fetchNodeRetry(sc, left, "empty");
         }
      }).exceptionallyCompose(ex -> fetchNodeRetry(sc, left, ex == null ? "error" : ex.getMessage()));
   }

   private static CompletableFuture<List<TurnRelayClient.NodeInfo>> fetchNodeRetry(SignalingClient sc, int left, String why) {
      if (left > 1) {
         LOGGER.warn("[TurnRelay] relay_list failed ({}), retry {}/{} in {}ms", new Object[]{why, 4 - left + 1, 3, 800L});
         return CompletableFuture.<Object>supplyAsync(() -> null, CompletableFuture.delayedExecutor(800L, TimeUnit.MILLISECONDS))
            .thenCompose(v -> fetchNodeRound(sc, left - 1));
      } else {
         List<TurnRelayClient.NodeInfo> stale = nodeCacheGet(sc, false);
         if (stale != null) {
            LOGGER.warn("[TurnRelay] relay_list failed ({}), using stale cache", why);
            return CompletableFuture.completedFuture(stale);
         } else {
            return CompletableFuture.completedFuture(new ArrayList<>());
         }
      }
   }

   private static List<TurnRelayClient.NodeInfo> parseNodeList(SignalingClient.ApiResponse r) {
      List<TurnRelayClient.NodeInfo> nodes = new ArrayList<>();
      if (r.success && r.data != null && r.data.has("nodes") && r.data.get("nodes").isJsonArray()) {
         for (JsonElement el : r.data.getAsJsonArray("nodes")) {
            if (el.isJsonObject()) {
               JsonObject n = el.getAsJsonObject();
               TurnRelayClient.NodeInfo info = new TurnRelayClient.NodeInfo();
               info.id = n.has("id") ? n.get("id").getAsString() : "";
               info.name = n.has("name") ? n.get("name").getAsString() : info.id;
               info.host = n.has("host") ? n.get("host").getAsString() : "";
               info.port = n.has("port") ? n.get("port").getAsInt() : '邈';
               if (!info.id.isEmpty() && !info.host.isEmpty() && info.port > 0) {
                  nodes.add(info);
               }
            }
         }
      }

      return nodes;
   }

   private static List<TurnRelayClient.NodeInfo> nodeCacheGet(SignalingClient sc, boolean freshOnly) {
      synchronized (NODE_CACHE_LOCK) {
         if (sc != null && sc == nodeCacheOwner && nodeCache != null) {
            return freshOnly && System.currentTimeMillis() - nodeCacheAtMs > 60000L ? null : new ArrayList<>(nodeCache);
         } else {
            return null;
         }
      }
   }

   private static void nodeCachePut(SignalingClient sc, List<TurnRelayClient.NodeInfo> nodes) {
      synchronized (NODE_CACHE_LOCK) {
         nodeCacheOwner = sc;
         nodeCache = new ArrayList<>(nodes);
         nodeCacheAtMs = System.currentTimeMillis();
      }
   }

   public static CompletableFuture<TurnRelayClient.Allocation> allocate(SignalingClient sc, String roomCode, String clientId, String token, String nodeId) {
      JsonObject body = new JsonObject();
      body.addProperty("roomCode", roomCode);
      body.addProperty("clientId", clientId);
      body.addProperty("token", token);
      body.addProperty("nodeId", nodeId);
      return sc.relayAllocate(body).thenApply(r -> {
         if (r.success && r.data != null) {
            JsonObject d = r.data;
            TurnRelayClient.Allocation a = new TurnRelayClient.Allocation();
            a.sessionIdHex = d.has("sessionId") ? d.get("sessionId").getAsString() : "";
            a.host = d.has("host") ? d.get("host").getAsString() : "";
            a.port = d.has("port") ? d.get("port").getAsInt() : 0;
            a.hostTicket = d.has("hostTicket") ? d.get("hostTicket").getAsString() : "";
            a.guestTicket = d.has("guestTicket") ? d.get("guestTicket").getAsString() : "";
            a.expireSec = d.has("expire") ? d.get("expire").getAsLong() : 0L;
            return a.sessionIdHex.length() == 32 && !a.host.isEmpty() && a.port > 0 && !a.guestTicket.isEmpty() && !a.hostTicket.isEmpty() ? a : null;
         } else {
            return null;
         }
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

   public static CompletableFuture<List<TurnRelayClient.ProbeResult>> probeNodes(List<TurnRelayClient.NodeInfo> nodes) {
      ExecutorService pool = Executors.newFixedThreadPool(Math.min(10, Math.max(2, nodes.size())), r -> {
         Thread t = new Thread(r, "VoxLink-TurnProbe");
         t.setDaemon(true);
         return t;
      });
      List<CompletableFuture<TurnRelayClient.ProbeResult>> futures = new ArrayList<>();

      for (TurnRelayClient.NodeInfo node : nodes) {
         futures.add(CompletableFuture.supplyAsync(() -> probeOne(node), pool));
      }

      CompletableFuture<List<TurnRelayClient.ProbeResult>> all = new CompletableFuture<>();
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).whenComplete((v, ex) -> {
         List<TurnRelayClient.ProbeResult> results = new ArrayList<>();

         for (CompletableFuture<TurnRelayClient.ProbeResult> f : futures) {
            try {
               results.add(f.join());
            } catch (Exception var8) {
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

   private static TurnRelayClient.ProbeResult probeOne(TurnRelayClient.NodeInfo node) {
      int best = -1;
      long deadline = System.currentTimeMillis() + 4000L;

      for (int i = 0; i < 6 && System.currentTimeMillis() < deadline; i++) {
         try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(800);
            InetSocketAddress ep = new InetSocketAddress(InetAddress.getByName(node.host), node.port);
            long nonce = System.nanoTime() & 4294967295L;
            long ts = System.currentTimeMillis();
            byte[] p = new byte[20];
            packHeader(p, (byte)1);
            writeInt32(p, 4, (int)nonce);
            writeInt64(p, 8, ts);
            long t0 = System.currentTimeMillis();
            s.send(new DatagramPacket(p, p.length, ep));
            byte[] buf = new byte[64];
            DatagramPacket resp = new DatagramPacket(buf, buf.length);

            while (System.currentTimeMillis() - t0 < 800L) {
               try {
                  s.receive(resp);
               } catch (SocketTimeoutException var18) {
                  break;
               }

               if (resp.getLength() >= 24 && buf[0] == MAGIC[0] && buf[1] == MAGIC[1] && buf[3] == 2 && readInt32(buf, 4) == (int)nonce) {
                  int rtt = (int)(System.currentTimeMillis() - t0);
                  if (best < 0 || rtt < best) {
                     best = rtt;
                  }
                  break;
               }
            }
         } catch (Exception var20) {
            LOGGER.debug("[TurnRelay] probe {} failed: {}", node.id, var20.getMessage());
         }
      }

      return new TurnRelayClient.ProbeResult(node, best);
   }

   public static int bind(TurnRelayClient.TurnSession session) {
      return bindWithRetry(session, 1);
   }

   public static int bindWithRetry(TurnRelayClient.TurnSession session, int rounds) {
      if (session.socket != null && !session.socket.isClosed()) {
         int code = 5;

         for (int attempt = 1; attempt <= Math.max(1, rounds); attempt++) {
            code = bindOnce(session);
            if (code == 0 || code != 5) {
               return code;
            }

            if (attempt < rounds) {
               LOGGER.warn("[TurnRelay] bind all sends lost (round {}/{}), retry in 1s", attempt, rounds);

               try {
                  Thread.sleep(1000L);
               } catch (InterruptedException var5) {
                  Thread.currentThread().interrupt();
                  return code;
               }
            }
         }

         return engageTcpFallback(session, code);
      } else {
         return 5;
      }
   }

   private static int engageTcpFallback(TurnRelayClient.TurnSession session, int udpCode) {
      if (session.tcpChannel != null) {
         return udpCode;
      } else {
         LOGGER.warn(
            "[TurnRelay] UDP bind no response (code={}) after retries, engaging TCP fallback to {}:{}", new Object[]{udpCode, session.host, session.port}
         );
         int preferPort = session.socket != null && !session.socket.isClosed() ? session.socket.getLocalPort() : 0;
         if (session.socket != null) {
            try {
               session.socket.close();
            } catch (Exception var8) {
            }
         }

         TurnTcpChannel ch;
         try {
            ch = TurnTcpChannel.open(session.host, session.port, preferPort);
         } catch (IOException var7) {
            LOGGER.warn("[TurnRelay] TCP fallback connect failed: {}", var7.getMessage());
            return udpCode;
         }

         session.tcpChannel = ch;
         session.socket = ch.clientSocket();
         session.host = "127.0.0.1";
         session.port = ch.shimPort();
         int code = 5;

         for (int attempt = 1; attempt <= 2; attempt++) {
            code = bindOnce(session);
            if (code != 5) {
               break;
            }

            if (attempt < 2) {
               try {
                  Thread.sleep(1000L);
               } catch (InterruptedException var9) {
                  Thread.currentThread().interrupt();
                  break;
               }
            }
         }

         if (code == 0) {
            LOGGER.warn("[TurnRelay] TCP fallback bind OK (UDP path dead)");
         } else {
            LOGGER.warn("[TurnRelay] TCP fallback bind failed code={}", code);
         }

         return code;
      }
   }

   private static int bindOnce(TurnRelayClient.TurnSession session) {
      if (session.socket != null && !session.socket.isClosed()) {
         byte[] ticketBytes = session.ticket.getBytes(StandardCharsets.US_ASCII);
         byte[] p = new byte[23 + ticketBytes.length];
         packHeader(p, (byte)3);
         hexToBytes(session.sessionIdHex, p, 4);
         p[20] = session.role;
         writeUint16(p, 21, ticketBytes.length);
         System.arraycopy(ticketBytes, 0, p, 23, ticketBytes.length);
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
               } catch (IOException var14) {
                  LOGGER.warn("[TurnRelay] bind send fail #{} to {}:{}: {}", new Object[]{sent + 1, session.host, session.port, var14.getMessage()});
               }

               nextSendAt = now + 900L;
            }

            resp.setLength(buf.length);

            try {
               session.socket.receive(resp);
            } catch (SocketTimeoutException var15) {
               continue;
            } catch (IOException var16) {
               break;
            }

            if (resp.getLength() >= 22 && buf[0] == MAGIC[0] && buf[1] == MAGIC[1] && buf[3] == 4) {
               byte[] sid = new byte[16];
               System.arraycopy(buf, 4, sid, 0, 16);
               if (Arrays.equals(sid, session.sessionId) && buf[20] == session.role) {
                  int code = buf[21] & 255;
                  session.bound = code == 0;
                  if (sent > 1) {
                     LOGGER.info("[TurnRelay] bind ok after {} sends (role={})", sent, session.role);
                  }

                  return code;
               }
            }
         }

         return 5;
      } else {
         return 5;
      }
   }

   private static void packHeader(byte[] buf, byte type) {
      buf[0] = MAGIC[0];
      buf[1] = MAGIC[1];
      buf[2] = 1;
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
         buf[off + i] = (byte)((int)(v >> 56 - i * 8));
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

   public static class Allocation {
      public String sessionIdHex;
      public String host;
      public int port;
      public String hostTicket;
      public String guestTicket;
      public long expireSec;
   }

   public static class NodeInfo {
      public String id;
      public String name;
      public String host;
      public int port;
   }

   public static class ProbeResult {
      public final TurnRelayClient.NodeInfo node;
      public final int rttMs;

      public ProbeResult(TurnRelayClient.NodeInfo node, int rttMs) {
         this.node = node;
         this.rttMs = rttMs;
      }
   }

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
         byte[] out = new byte[24 + frame.length];
         TurnRelayClient.packHeader(out, (byte)5);
         System.arraycopy(this.sessionId, 0, out, 4, 16);
         out[20] = this.fromRole;
         out[21] = this.toRole;
         TurnRelayClient.writeUint16(out, 22, frame.length);
         System.arraycopy(frame, 0, out, 24, frame.length);
         return out;
      }

      @Override
      public byte[] decode(byte[] packet, int len) {
         if (len >= 24 && packet[0] == TurnRelayClient.MAGIC[0] && packet[1] == TurnRelayClient.MAGIC[1] && packet[3] == 5) {
            for (int i = 0; i < 16; i++) {
               if (packet[4 + i] != this.sessionId[i]) {
                  return null;
               }
            }

            int payloadLen = (packet[22] & 255) << 8 | packet[23] & 255;
            if (24 + payloadLen > len) {
               return null;
            } else {
               byte[] frame = new byte[payloadLen];
               System.arraycopy(packet, 24, frame, 0, payloadLen);
               return frame;
            }
         } else {
            return null;
         }
      }
   }

   public static class TurnSession {
      public final String sessionIdHex;
      public final byte[] sessionId;
      public final byte role;
      public final String ticket;
      public final long expireSec;
      public volatile DatagramSocket socket;
      public volatile String host;
      public volatile int port;
      public volatile TurnTcpChannel tcpChannel = null;
      public volatile boolean bound = false;
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
         } catch (IOException var11) {
            TurnRelayClient.LOGGER.warn("[TurnRelay] alloc socket failed: {}", var11.getMessage());
         }

         this.socket = s;
      }

      public InetSocketAddress endpoint() {
         return new InetSocketAddress(this.host, this.port);
      }

      public void sendKeepalive() {
         if (this.socket != null && !this.socket.isClosed() && this.bound) {
            try {
               byte[] p = new byte[21];
               TurnRelayClient.packHeader(p, (byte)6);
               TurnRelayClient.hexToBytes(this.sessionIdHex, p, 4);
               p[20] = this.role;
               InetSocketAddress ep = this.endpoint();
               this.socket.send(new DatagramPacket(p, p.length, ep));
               this.keepaliveSent.incrementAndGet();
            } catch (IOException var3) {
               TurnRelayClient.LOGGER.debug("[TurnRelay] keepalive send failed: {}", var3.getMessage());
            }
         }
      }

      public void unbind() {
         try {
            if (this.socket != null && !this.socket.isClosed() && this.bound) {
               byte[] p = new byte[21];
               TurnRelayClient.packHeader(p, (byte)8);
               TurnRelayClient.hexToBytes(this.sessionIdHex, p, 4);
               p[20] = this.role;
               this.socket.send(new DatagramPacket(p, p.length, this.endpoint()));
            }
         } catch (IOException var12) {
            TurnRelayClient.LOGGER.debug("[TurnRelay] unbind send failed: {}", var12.getMessage());
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
            } catch (Exception var11) {
            }
         }
      }
   }
}
