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
               byte[] decompressed = gis.readAllBytes();
               return new String(decompressed, StandardCharsets.UTF_8);
            }
         } else {
            return null;
         }
      } catch (IOException e) {
         return null;
      }
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
