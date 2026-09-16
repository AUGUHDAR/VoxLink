package icu.wuhui.voxlink.network;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import javax.crypto.Mac;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class P2POverlayManager {
   private static final Logger LOGGER = LoggerFactory.getLogger("voxlink-overlay");
   private static final Gson GSON = new Gson();
   private static final int MAX_PACKET_SIZE = 32768;
   private static final int READ_BUFFER_SIZE = 65536;
   private static final int PING_INTERVAL_SEC = 2;
   private static final int MAX_PING_FAILURES = 5;
   private static final int MAX_SEEN_SEQ_SIZE = 1000;
   /** 安全修复：单个源在 seenSeq 去重窗口内的最大条目数，防伪造 seq 刷爆窗口挤掉合法去重记录 */
   private static final int PER_SOURCE_SEEN_CAP = 400;
   /** 安全修复：解压输出上限 256KB，超限丢弃（防 zip-bomb 式解压放大） */
   private static final int MAX_DECOMPRESSED_BYTES = 256 * 1024;
   private DatagramSocket socket;
   private final AtomicBoolean running = new AtomicBoolean(false);
   private final AtomicReference<P2POverlayManager.Role> role = new AtomicReference<>(P2POverlayManager.Role.NONE);
   private final AtomicReference<InetSocketAddress> upstreamAddr = new AtomicReference<>(null);
   private volatile String upstreamId;
   private final AtomicReference<InetSocketAddress> downstreamAddr = new AtomicReference<>(null);
   private volatile String downstreamId;
   private String nodeId;
   private final int localPort;
   private final AtomicInteger packetSeq = new AtomicInteger(0);
   private final ConcurrentHashMap<String, Long> seenSeq = new ConcurrentHashMap<>();
   /**
    * overlayAuthV1 链路密钥表（nodeId → Mac）。由上层在确认对端声明
    * ProtocolNegotiator.CAP_OVERLAY_AUTH_V1 后注入；对已配置密钥的来源强制校验报文
    * "mac" 字段（截断 HMAC-SHA256），未配置的来源保持旧行为（老对端互操作不变）。
    */
   private final ConcurrentHashMap<String, Mac> linkKeysByNode = new ConcurrentHashMap<>();
   private final AtomicInteger authDropCount = new AtomicInteger(0);
   private static final AtomicInteger DECOMPRESS_DROP_COUNT = new AtomicInteger(0);
   private ExecutorService ioExecutor;
   private ScheduledExecutorService pingScheduler;
   private volatile P2POverlayManager.PacketHandler handler;
   private final AtomicInteger upstreamLatency = new AtomicInteger(-1);
   private final AtomicInteger downstreamLatency = new AtomicInteger(-1);
   private final AtomicReference<P2POverlayManager.PendingPing> pendingUpstreamPing = new AtomicReference<>(null);
   private final AtomicReference<P2POverlayManager.PendingPing> pendingDownstreamPing = new AtomicReference<>(null);
   private final AtomicInteger upstreamPingFailures = new AtomicInteger(0);
   private final AtomicInteger downstreamPingFailures = new AtomicInteger(0);

   public P2POverlayManager(String nodeId, int port) {
      this.nodeId = nodeId != null ? nodeId : "node_" + System.identityHashCode(this);
      this.localPort = port;
   }

   public void start(P2POverlayManager.PacketHandler handler) throws IOException {
      if (!this.running.get()) {
         this.handler = handler;

         try {
            this.socket = new DatagramSocket(null);
            this.socket.setReuseAddress(true);
            this.socket.bind(new InetSocketAddress(this.localPort));
            this.socket.setSoTimeout(1000);
         } catch (SocketException e) {
            LOGGER.error("Overlay UDP port {} bind failed: {}", this.localPort, e.getMessage());
            throw new IOException("Failed to bind overlay socket", e);
         }

         try {
            this.ioExecutor = Executors.newFixedThreadPool(2, r -> {
               Thread t = new Thread(r, "VoxLink-Overlay-IO");
               t.setDaemon(true);
               return t;
            });
            this.pingScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
               Thread t = new Thread(r, "VoxLink-Overlay-Ping");
               t.setDaemon(true);
               return t;
            });
         } catch (Exception e) {
            if (this.ioExecutor != null) {
               this.ioExecutor.shutdownNow();
            }

            if (this.pingScheduler != null) {
               this.pingScheduler.shutdownNow();
            }

            if (this.socket != null && !this.socket.isClosed()) {
               this.socket.close();
            }

            throw new IOException("Failed to create executors", e);
         }

         this.running.set(true);

         try {
            this.ioExecutor.submit(this::readLoop);
         } catch (RejectedExecutionException e) {
            LOGGER.error("Overlay read loop submit failed: {}", e.getMessage());
            this.stop();
            throw new IOException("Failed to submit overlay read loop", e);
         }

         this.pingScheduler.scheduleAtFixedRate(this::pingTask, 2L, 2L, TimeUnit.SECONDS);
         LOGGER.info("P2P overlay started, port {}, node {}", this.socket.getLocalPort(), this.nodeId);
      }
   }

   public void connectUpstream(String peerId, String host, int port) {
      if (this.running.get()) {
         if (host != null) {
            InetSocketAddress addr = new InetSocketAddress(host, port);
            this.upstreamAddr.set(addr);
            this.upstreamId = peerId;
            if (this.downstreamAddr.get() == null) {
               this.role.set(P2POverlayManager.Role.CHAIN_TAIL);
            } else {
               this.role.set(P2POverlayManager.Role.CHAIN_MIDDLE);
            }

            LOGGER.info("Upstream connection: {} at {}:{} role {}", new Object[]{peerId, host, port, this.role.get()});
            this.sendHandshake(peerId, addr);
         }
      }
   }

   public void setDownstream(String peerId, String host, int port) {
      if (this.running.get()) {
         if (host != null) {
            InetSocketAddress addr = new InetSocketAddress(host, port);
            this.downstreamAddr.set(addr);
            this.downstreamId = peerId;
            LOGGER.info("Downstream set: {} at {}:{}", new Object[]{peerId, host, port});
            if (this.role.get() != P2POverlayManager.Role.CHAIN_HEAD && this.role.get() != P2POverlayManager.Role.CHAIN_MIDDLE) {
               if (this.upstreamAddr.get() != null) {
                  this.role.set(P2POverlayManager.Role.CHAIN_MIDDLE);
               } else {
                  this.role.set(P2POverlayManager.Role.CHAIN_TAIL);
               }
            }
         }
      }
   }

   public void becomeHead(String downstreamPeerId, String downstreamHost, int downstreamPort) {
      if (this.running.get()) {
         this.role.set(P2POverlayManager.Role.CHAIN_HEAD);
         this.upstreamAddr.set(null);
         this.upstreamId = null;
         if (downstreamPeerId != null && downstreamHost != null) {
            InetSocketAddress addr = new InetSocketAddress(downstreamHost, downstreamPort);
            this.downstreamAddr.set(addr);
            this.downstreamId = downstreamPeerId;
            this.sendHandshake(downstreamPeerId, addr);
         } else {
            this.downstreamAddr.set(null);
            this.downstreamId = null;
         }

         LOGGER.info("Became chain head, downstream: {}", downstreamPeerId != null ? downstreamPeerId : "none");
      }
   }

   public void switchToDirectMode() {
      this.role.set(P2POverlayManager.Role.NONE);
      this.upstreamAddr.set(null);
      this.upstreamId = null;
      this.downstreamAddr.set(null);
      this.downstreamId = null;
      LOGGER.info("Switched to direct mode");
   }

   public int getUpstreamLatency() {
      return this.upstreamLatency.get();
   }

   public int getDownstreamLatency() {
      return this.downstreamLatency.get();
   }

   public P2POverlayManager.Role getRole() {
      return this.role.get();
   }

   public void setNodeId(String id) {
      this.nodeId = id;
   }

   /**
    * overlayAuthV1：为指定邻居节点注入链路密钥（截断 HMAC-SHA256 用）。
    * 注入后：来自该 nodeId 的所有报文必须携带有效 "mac" 字段，否则丢弃并计数。
    * 不注入则该来源保持旧行为（与老版本互操作）。
    */
   public void setLinkKeyForPeer(String peerNodeId, byte[] key) {
      if (peerNodeId != null && !peerNodeId.isEmpty()) {
         if (key == null) {
            this.linkKeysByNode.remove(peerNodeId);
         } else {
            Mac mac = PunchAuth.createMac(key);
            if (mac != null) {
               this.linkKeysByNode.put(peerNodeId, mac);
            }
         }
      }
   }

   /** 为报文附加 overlayAuthV1 截断 MAC（对去除了 mac 字段后的规范 JSON 签名，取前 8 字节 hex）。 */
   static void signPacket(JsonObject packet, Mac mac) {
      if (mac == null || packet.has("mac")) {
         return;
      }

      try {
         byte[] full;
         synchronized (mac) {
            full = mac.doFinal(GSON.toJson(packet).getBytes(StandardCharsets.UTF_8));
         }

         StringBuilder hex = new StringBuilder();
         for (int i = 0; i < 8 && i < full.length; i++) {
            hex.append(String.format("%02x", full[i]));
         }

         packet.addProperty("mac", hex.toString());
      } catch (Exception ignored) {
      }
   }

   /** 校验并剥离 "mac" 字段；返回 false 表示 MAC 缺失/无效（应丢弃）。 */
   static boolean verifyAndStripPacket(JsonObject packet, Mac mac) {
      if (mac == null) {
         return true;
      }

      String claimed = packet.has("mac") && packet.get("mac").isJsonPrimitive() ? packet.get("mac").getAsString() : null;
      if (claimed == null) {
         return false;
      }

      packet.remove("mac");
      try {
         byte[] full;
         synchronized (mac) {
            full = mac.doFinal(GSON.toJson(packet).getBytes(StandardCharsets.UTF_8));
         }

         StringBuilder hex = new StringBuilder();
         for (int i = 0; i < 8 && i < full.length; i++) {
            hex.append(String.format("%02x", full[i]));
         }

         return MessageDigest.isEqual(hex.toString().getBytes(StandardCharsets.US_ASCII), claimed.getBytes(StandardCharsets.US_ASCII));
      } catch (Exception e) {
         return false;
      }
   }

   public void stop() {
      this.running.set(false);
      this.upstreamAddr.set(null);
      this.downstreamAddr.set(null);
      this.upstreamId = null;
      this.downstreamId = null;
      if (this.socket != null && !this.socket.isClosed()) {
         this.socket.close();
      }

      this.socket = null;
      if (this.pingScheduler != null) {
         this.pingScheduler.shutdownNow();
         this.pingScheduler = null;
      }

      if (this.ioExecutor != null) {
         this.ioExecutor.shutdownNow();
         this.ioExecutor = null;
      }

      this.role.set(P2POverlayManager.Role.NONE);
      this.seenSeq.clear();
      this.linkKeysByNode.clear();
      LOGGER.info("P2P overlay stopped");
   }

   public int getLocalPort() {
      if (this.socket != null && !this.socket.isClosed()) {
         try {
            return this.socket.getLocalPort();
         } catch (Exception e) {
            return this.localPort;
         }
      } else {
         return this.localPort;
      }
   }

   private void readLoop() {
      byte[] buf = new byte[65536];

      while (this.running.get() && !Thread.currentThread().isInterrupted()) {
         try {
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            this.socket.receive(packet);
            byte[] data = new byte[packet.getLength()];
            System.arraycopy(packet.getData(), packet.getOffset(), data, 0, packet.getLength());
            this.ioExecutor.submit(() -> this.processPacket(data, packet.getSocketAddress()));
         } catch (SocketException e) {
            if (!this.running.get()) {
               break;
            }

            LOGGER.debug("Overlay read socket temp error: {}", e.getMessage());
         } catch (Exception e) {
            if (this.running.get()) {
               LOGGER.debug("Overlay read error: {}", e.getMessage());
            }
         }
      }
   }

   private void processPacket(byte[] data, SocketAddress fromAddr) {
      try {
         if (!(fromAddr instanceof InetSocketAddress inetAddr)) {
            return;
         }

         String json = decompress(data);
         if (json == null) {
            return;
         }

         JsonObject packet = (JsonObject)GSON.fromJson(json, JsonObject.class);
         String type = packet.has("type") ? packet.get("type").getAsString() : "";
         String from = packet.has("from") ? packet.get("from").getAsString() : "";
         // overlayAuthV1：对已注入链路密钥的来源强制 MAC 校验（失败丢弃并计数）；未配置来源保持旧行为
         Mac linkMac = from != null ? this.linkKeysByNode.get(from) : null;
         if (linkMac != null && !verifyAndStripPacket(packet, linkMac)) {
            int drops = this.authDropCount.incrementAndGet();
            if (drops == 1 || drops % 50 == 0) {
               LOGGER.warn("Overlay packet from configured peer {} failed MAC verification (drop #{})", from, drops);
            }

            return;
         }

         switch (type) {
            case "handshake":
               this.handleHandshake(packet, inetAddr);
               break;
            case "ping":
               this.handlePing(packet, from, inetAddr);
               break;
            case "pong":
               this.handlePong(packet);
               break;
            case "data_relay":
               String fromDir = this.determineDirection(inetAddr);
               this.handleDataRelay(packet, fromDir, inetAddr);
               break;
            default:
               LOGGER.debug("Unknown overlay packet type: {}", type);
         }
      } catch (Exception e) {
         LOGGER.debug("Overlay packet process failed: {}", e.getMessage());
      }
   }

   private void handleHandshake(JsonObject packet, InetSocketAddress fromAddr) {
      // 安全修复：握手来源约束——拓扑指令已把对端 ip:port 下发给链路两端，
      // 因此合法握手只可能来自已配置的 upstream/downstream 地址（NAT 重写端口允许漂移，IP 必须一致）；
      // 链路尚未配置任何对端时保持旧行为（引导期兼容）。其余来源一律拒绝，
      // 防止任意互联网主机伪造 handshake 抢占 upstream/downstream 槽位。
      if (!this.isKnownLinkAddress(fromAddr)) {
         int drops = this.authDropCount.incrementAndGet();
         LOGGER.warn("Rejected overlay handshake from unknown source {} (drop #{})", fromAddr, drops);
         return;
      }

      String peerId = packet.has("from") ? packet.get("from").getAsString() : "";
      LOGGER.info("Received downstream handshake: {}", peerId);
      if (this.role.get() == P2POverlayManager.Role.CHAIN_HEAD || this.role.get() == P2POverlayManager.Role.CHAIN_MIDDLE) {
         this.downstreamAddr.set(fromAddr);
         this.downstreamId = peerId;
      }

      if (this.handler != null) {
         this.handler.onLinkReady();
      }
   }

   /**
    * 判定握手来源是否可信：协议上握手只会由下游发往上游（用于登记 downstream 槽位），
    * 因此只需对 downstream 槽位做来源校验。downstream 地址未配置时（引导期或拓扑指令
    * 未携带地址）保持旧行为，避免破坏首连。
    */
   private boolean isKnownLinkAddress(InetSocketAddress fromAddr) {
      if (fromAddr == null) {
         return false;
      }

      InetSocketAddress down = this.downstreamAddr.get();
      return down == null || isSameHost(down, fromAddr);
   }

   private static boolean isSameHost(InetSocketAddress a, InetSocketAddress b) {
      return a != null && a.getAddress() != null && a.getAddress().equals(b.getAddress());
   }

   private void handlePing(JsonObject packet, String from, InetSocketAddress senderAddr) {
      JsonObject pong = new JsonObject();
      pong.addProperty("type", "pong");
      pong.addProperty("from", this.nodeId);
      pong.addProperty("seq", packet.has("seq") ? packet.get("seq").getAsInt() : 0);
      if (packet.has("dir")) {
         pong.addProperty("dir", packet.get("dir").getAsString());
      }

      this.sendPacketTo(pong, senderAddr);
   }

   private void handlePong(JsonObject packet) {
      long now = System.currentTimeMillis();
      int pingSeq = packet.has("seq") ? packet.get("seq").getAsInt() : -1;
      String dir = packet.has("dir") ? packet.get("dir").getAsString() : "up";
      if ("down".equals(dir)) {
         P2POverlayManager.PendingPing pending = this.pendingDownstreamPing.get();
         if (pending != null && pingSeq == pending.seq) {
            int latency = (int)(now - pending.timestamp);
            this.downstreamLatency.set(latency);
            this.pendingDownstreamPing.compareAndSet(pending, null);
            this.downstreamPingFailures.set(0);
         }
      } else {
         P2POverlayManager.PendingPing pending = this.pendingUpstreamPing.get();
         if (pending != null && pingSeq == pending.seq) {
            int latency = (int)(now - pending.timestamp);
            this.upstreamLatency.set(latency);
            this.pendingUpstreamPing.compareAndSet(pending, null);
            this.upstreamPingFailures.set(0);
         }
      }
   }

   private void handleDataRelay(JsonObject packet, String fromDirection, InetSocketAddress fromAddr) {
      String from = packet.has("from") ? packet.get("from").getAsString() : "";
      int seq = packet.has("seq") ? packet.get("seq").getAsInt() : 0;
      // 安全修复：单源占比限制——伪造高频唯一 seq 可把合法去重记录挤出窗口（重放攻击面），
      // 限制单个 from 最多占窗口的 40%，超限直接丢弃该源的新包
      if (this.countSeenForSource(from) >= PER_SOURCE_SEEN_CAP) {
         return;
      }

      String dedupKey = from + ":" + seq;
      if (this.seenSeq.putIfAbsent(dedupKey, System.currentTimeMillis()) == null) {
         if (this.seenSeq.size() > 1000) {
            long cutoff = System.currentTimeMillis() - 60000L;
            Iterator<Entry<String, Long>> it = this.seenSeq.entrySet().iterator();

            while (it.hasNext() && this.seenSeq.size() > 1000) {
               if (it.next().getValue() < cutoff) {
                  it.remove();
               }
            }

            if (this.seenSeq.size() > 1000) {
               List<Entry<String, Long>> sorted = new ArrayList<>(this.seenSeq.entrySet());
               sorted.sort(Comparator.comparingLong(Entry::getValue));
               int toRemove = this.seenSeq.size() - 1000;

               for (int i = 0; i < toRemove; i++) {
                  this.seenSeq.remove(sorted.get(i).getKey());
               }
            }
         }

         String to = packet.has("to") && !packet.get("to").isJsonNull() ? packet.get("to").getAsString() : null;
         String priority = packet.has("priority") ? packet.get("priority").getAsString() : "L2";
         JsonObject payload = packet.has("payload") ? packet.getAsJsonObject("payload") : new JsonObject();
         if ((to == null || to.equals(this.nodeId)) && this.handler != null) {
            this.handler.onDataReceived(from, priority, payload);
         }

         if (to == null || !to.equals(this.nodeId)) {
            if ("upstream".equals(fromDirection)) {
               this.forwardToDownstream(packet);
            } else if ("downstream".equals(fromDirection)) {
               this.forwardToUpstream(packet);
            } else {
               InetSocketAddress up = this.upstreamAddr.get();
               InetSocketAddress down = this.downstreamAddr.get();
               if (up != null && up.equals(fromAddr)) {
                  this.forwardToDownstream(packet);
               } else if (down != null && down.equals(fromAddr)) {
                  this.forwardToUpstream(packet);
               }
            }
         }
      }
   }

   /** 统计 seenSeq 窗口中属于指定源的条目数（窗口上限 1000，线性扫描代价可忽略）。 */
   private int countSeenForSource(String from) {
      if (from == null || from.isEmpty()) {
         return 0;
      }

      String prefix = from + ":";
      int count = 0;

      for (String key : this.seenSeq.keySet()) {
         if (key.startsWith(prefix) && ++count >= PER_SOURCE_SEEN_CAP) {
            break;
         }
      }

      return count;
   }

   private void forwardToDownstream(JsonObject packet) {
      InetSocketAddress down = this.downstreamAddr.get();
      if (down != null) {
         this.sendPacketTo(packet, down);
      }
   }

   private void forwardToUpstream(JsonObject packet) {
      InetSocketAddress up = this.upstreamAddr.get();
      if (up != null) {
         this.sendPacketTo(packet, up);
      }
   }

   private String determineDirection(InetSocketAddress fromAddr) {
      InetSocketAddress up = this.upstreamAddr.get();
      InetSocketAddress down = this.downstreamAddr.get();
      if (up != null && up.equals(fromAddr)) {
         return "upstream";
      } else {
         return down != null && down.equals(fromAddr) ? "downstream" : "unknown";
      }
   }

   private void sendHandshake(String peerId, InetSocketAddress addr) {
      JsonObject handshake = new JsonObject();
      handshake.addProperty("type", "handshake");
      handshake.addProperty("from", this.nodeId);
      this.sendPacketTo(handshake, addr);
   }

   private void pingTask() {
      if (this.running.get() && this.role.get() != P2POverlayManager.Role.NONE) {
         InetSocketAddress up = this.upstreamAddr.get();
         if (up != null) {
            P2POverlayManager.PendingPing currentUp = this.pendingUpstreamPing.get();
            if (currentUp != null) {
               int failures = this.upstreamPingFailures.incrementAndGet();
               if (failures >= 5) {
                  LOGGER.warn("Upstream link broken, {} consecutive ping failures", failures);
                  this.upstreamPingFailures.set(0);
                  this.pendingUpstreamPing.compareAndSet(currentUp, null);
                  if (this.handler != null) {
                     this.handler.onLinkLost("upstream_timeout");
                  }
               }
            } else {
               this.upstreamPingFailures.set(0);
            }

            int seq = this.packetSeq.incrementAndGet();
            this.pendingUpstreamPing.set(new P2POverlayManager.PendingPing(seq, System.currentTimeMillis()));
            JsonObject ping = new JsonObject();
            ping.addProperty("type", "ping");
            ping.addProperty("from", this.nodeId);
            ping.addProperty("seq", seq);
            ping.addProperty("dir", "up");
            this.sendPacketTo(ping, up);
         }

         InetSocketAddress down = this.downstreamAddr.get();
         if (down != null) {
            P2POverlayManager.PendingPing currentDown = this.pendingDownstreamPing.get();
            if (currentDown != null) {
               int failures = this.downstreamPingFailures.incrementAndGet();
               if (failures >= 5) {
                  LOGGER.warn("Downstream link broken, {} consecutive ping failures", failures);
                  this.downstreamPingFailures.set(0);
                  this.pendingDownstreamPing.compareAndSet(currentDown, null);
                  if (this.handler != null) {
                     this.handler.onLinkLost("downstream_timeout");
                  }
               }
            } else {
               this.downstreamPingFailures.set(0);
            }

            int seq = this.packetSeq.incrementAndGet();
            this.pendingDownstreamPing.set(new P2POverlayManager.PendingPing(seq, System.currentTimeMillis()));
            JsonObject ping = new JsonObject();
            ping.addProperty("type", "ping");
            ping.addProperty("from", this.nodeId);
            ping.addProperty("seq", seq);
            ping.addProperty("dir", "down");
            this.sendPacketTo(ping, down);
         }
      }
   }

   private void sendPacket(JsonObject packet) {
      P2POverlayManager.Role r = this.role.get();
      if (r == P2POverlayManager.Role.CHAIN_HEAD) {
         this.sendPacketToDownstream(packet);
      } else if (r == P2POverlayManager.Role.CHAIN_TAIL) {
         this.sendPacketToUpstream(packet);
      } else if (r == P2POverlayManager.Role.CHAIN_MIDDLE) {
         String to = packet.has("to") && !packet.get("to").isJsonNull() ? packet.get("to").getAsString() : null;
         if (to == null) {
            this.sendPacketToUpstream(packet);
            this.sendPacketToDownstream(packet);
         } else if (to.equals(this.upstreamId)) {
            this.sendPacketToUpstream(packet);
         } else if (to.equals(this.downstreamId)) {
            this.sendPacketToDownstream(packet);
         } else {
            this.sendPacketToUpstream(packet);
            this.sendPacketToDownstream(packet);
         }
      }
   }

   private void sendPacketToUpstream(JsonObject packet) {
      InetSocketAddress up = this.upstreamAddr.get();
      if (up != null) {
         this.sendPacketTo(packet, up);
      }
   }

   private void sendPacketToDownstream(JsonObject packet) {
      InetSocketAddress down = this.downstreamAddr.get();
      if (down != null) {
         this.sendPacketTo(packet, down);
      }
   }

   private void sendPacketTo(JsonObject packet, InetSocketAddress addr) {
      try {
         String json = GSON.toJson(packet);
         byte[] payload = compress(json);
         byte[] framed = framePacket(payload);
         DatagramPacket dp = new DatagramPacket(framed, framed.length, addr);
         this.socket.send(dp);
      } catch (Exception e) {
         LOGGER.debug("Send packet to {} failed: {}", addr, e.getMessage());
      }
   }

   static byte[] compress(String data) {
      try {
         ByteArrayOutputStream bos = new ByteArrayOutputStream();

         try (GZIPOutputStream gos = new GZIPOutputStream(bos)) {
            gos.write(data.getBytes(StandardCharsets.UTF_8));
         }

         return bos.toByteArray();
      } catch (IOException e) {
         LOGGER.error("Overlay data compression failed: {}", e.getMessage());
         throw new RuntimeException("Compression failed", e);
      }
   }

   static String decompress(byte[] data) {
      if (data.length < 4) {
         return null;
      }

      try {
         int payloadLen = ByteBuffer.wrap(data, 0, 4).getInt();
         if (payloadLen > 0 && payloadLen <= data.length - 4) {
            ByteArrayInputStream bis = new ByteArrayInputStream(data, 4, payloadLen);

            try (GZIPInputStream gis = new GZIPInputStream(bis)) {
               // 安全修复：解压输出上限（防解压放大攻击），超限丢弃并计数
               byte[] decompressed = readBounded(gis, MAX_DECOMPRESSED_BYTES);
               if (decompressed == null) {
                  int drops = DECOMPRESS_DROP_COUNT.incrementAndGet();
                  LOGGER.warn("Overlay packet decompressed size exceeded {} bytes, dropped (drop #{})", MAX_DECOMPRESSED_BYTES, drops);
                  return null;
               }

               return new String(decompressed, StandardCharsets.UTF_8);
            }
         } else {
            return null;
         }
      } catch (IOException e) {
         return null;
      }
   }

   /** 有界读取：超过 limit 返回 null；正常流一次读完全部内容。 */
   private static byte[] readBounded(GZIPInputStream gis, int limit) throws IOException {
      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      byte[] chunk = new byte[8192];
      int total = 0;

      int n;
      while ((n = gis.read(chunk)) != -1) {
         total += n;
         if (total > limit) {
            return null;
         }

         bos.write(chunk, 0, n);
      }

      return bos.toByteArray();
   }

   static byte[] framePacket(byte[] compressed) {
      ByteBuffer buf = ByteBuffer.allocate(4 + compressed.length);
      buf.putInt(compressed.length);
      buf.put(compressed);
      return buf.array();
   }

   public interface PacketHandler {
      void onDataReceived(String var1, String var2, JsonObject var3);

      void onLinkReady();

      void onLinkLost(String var1);
   }

   private static class PendingPing {
      final int seq;
      final long timestamp;

      PendingPing(int seq, long timestamp) {
         this.seq = seq;
         this.timestamp = timestamp;
      }
   }

   public enum Role {
      NONE,
      CHAIN_HEAD,
      CHAIN_MIDDLE,
      CHAIN_TAIL;
   }
}
