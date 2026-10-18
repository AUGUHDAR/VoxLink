package icu.wuhui.voxlink.network;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.crypto.Mac;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ReliableUdpTransport implements AutoCloseable {
   private static final Logger LOGGER = LoggerFactory.getLogger("voxlink-rudp");
   private static final byte[] MAGIC = new byte[]{86, 76};
   private static final byte TYPE_PUNCH = 1;
   private static final byte TYPE_PUNCH_ACK = 2;
   private static final byte TYPE_DATA = 3;
   private static final byte TYPE_ACK = 4;
   private static final byte TYPE_DISCONNECT = 7;
   private static final byte TYPE_KEEPALIVE = 8;
   private static final byte TYPE_FEC_XOR = 9;
   private static final byte TYPE_RESTART = 10;
   private static final byte TYPE_VOICE = 11;
   private static final int FEC_GROUP_SIZE = 4;
   private static final int HEADER_SIZE = 11;
   private static final int PAYLOAD_LEN_SIZE = 2;
   private static final int MAX_PAYLOAD = 1400;
   private static final int TURN_MAX_SEND_CHUNK = 1374;
   private static final int WINDOW_SIZE = 64;
   private static final long RETRANSMIT_TIMEOUT_MS = 800L;
   private static final int KEEPALIVE_INTERVAL_S = 1;
   private static final int KEEPALIVE_TIMEOUT_S = 60;
   private static final int HEARTBEAT_FAIL_LIMIT = 5;
   private static final int MAX_SILENT_RETRANSMIT_CYCLES = 30;
   private static final int UNRELIABLE_FAIL_THRESHOLD = 5;
   private static final long UNRELIABLE_SILENCE_MS = 8000L;
   private static final int MAX_FEC_GROUP_SIZE = 20;
   private static final int FEC_MAX_PACKET_SIZE = 1454;
   private static final int FEC_CLEAN_WINDOW = 10;
   private static final int SMALL_PACKET_THRESHOLD = 512;
   private static final int POLL_INTERVAL_MS = 50;
   private static final int MAX_BUFFERED_CHUNKS = 512;
   private static final long RTO_MIN_MS = 100L;
   private static final long RTO_MAX_MS = 800L;
   private static final long CLOCK_GRANULARITY_MS = 10L;
   private static final int WINDOW_MIN = 16;
   private static final int WINDOW_MAX = 64;
   private static final int LOSS_SAMPLE_LIMIT = 200;
   private static final long MAX_RETRANSMIT_TOTAL_MS = 24000L;
   private static final long RETRANSMIT_BACKOFF_MS = 250L;
   private static final int DEAD_PEER_BREAKER_ROUNDS = 20;
   private static final long DEAD_PEER_BREAKER_MIN_SILENCE_MS = 23000L;
   private volatile long srtt = -1L;
   private volatile long rttvar = 0L;
   private volatile long rto = 200L;
   private int lossEvents = 0;
   private int lossLostEvents = 0;
   private volatile int effectiveWindow = 64;
   private volatile int lastAckSeq = -1;
   private int dupAckCount = 0;
   private final DatagramSocket socket;
   private volatile InetSocketAddress remoteAddress;
   private volatile UdpPath primaryPath;
   private volatile UdpPath secondaryPath;
   private volatile long switchGraceUntilMs = 0L;
   private volatile boolean remoteConfirmed = false;
   private volatile boolean running = true;
   private final AtomicBoolean connected = new AtomicBoolean(false);
   private volatile long lastRecvTime = System.currentTimeMillis();
   private final AtomicBoolean closed = new AtomicBoolean(false);
   private volatile int writeState = 0;
   private static final int STATE_WRITABLE = 0;
   private static final int STATE_UNRELIABLE = 1;
   private volatile int consecutiveFailures = 0;
   private final AtomicBoolean iceRestartTriggered = new AtomicBoolean(false);
   private int deadPeerRetransmitRounds = 0;
   private long deadPeerStreakStartMs = 0L;
   private long lastHeartbeatMs = System.currentTimeMillis();
   private int heartbeatFailStreak = 0;
   private boolean heartbeatEverRx = false;
   private volatile byte[] authKeyBytes;
   private volatile Mac authMac;
   private volatile Runnable onIceRestartRequested;
   private volatile Consumer<byte[]> onVoiceData;
   private volatile int nextSendSeq = 0;
   private volatile int oldestUnackedSeq = 0;
   private final ConcurrentSkipListMap<Integer, ReliableUdpTransport.PendingPacket> pendingAcks = new ConcurrentSkipListMap<>();
   private final Object sendLock = new Object();
   private final AtomicInteger nextExpectedSeq = new AtomicInteger(0);
   private final ConcurrentSkipListMap<Integer, byte[]> recvBuffer = new ConcurrentSkipListMap<>();
   private final Object recvLock = new Object();
   private final ReliableUdpTransport.UdpInputStream inputStream = new ReliableUdpTransport.UdpInputStream();
   private final ReliableUdpTransport.UdpOutputStream outputStream = new ReliableUdpTransport.UdpOutputStream();
   private final ConcurrentLinkedQueue<byte[]> outboundQueue = new ConcurrentLinkedQueue<>();
   private int bufferedChunks = 0;
   private Thread recvThread;
   private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "VoxLink-Retransmit");
      t.setDaemon(true);
      return t;
   });
   private ScheduledFuture<?> retransmitTask;
   private ScheduledFuture<?> keepaliveTask;
   private final List<byte[]> fecSendGroup = new ArrayList<>();
   private int fecSendGroupSeq = -1;
   private final Object fecSendLock = new Object();
   private final ConcurrentHashMap<Integer, ConcurrentHashMap<Integer, byte[]>> fecRecvGroup = new ConcurrentHashMap<>();
   private final ConcurrentHashMap<Integer, byte[]> fecRecvXor = new ConcurrentHashMap<>();
   private final ConcurrentHashMap<Integer, int[]> fecRecvLengths = new ConcurrentHashMap<>();
   private volatile int minActiveGroupId = Integer.MAX_VALUE;
   private volatile int pendingRebindPort = -1;
   private volatile long lastCurrentPortRecvMs = 0L;
   private volatile long pendingRebindTime = 0L;
   private volatile boolean allowAuthDowngrade = false;
   private int consecutiveAuthDrops = 0;

   public ReliableUdpTransport(DatagramSocket socket, InetSocketAddress remoteAddress) {
      this(socket, remoteAddress, null);
   }

   public ReliableUdpTransport(DatagramSocket socket, InetSocketAddress remoteAddress, UdpPath.Codec primaryCodec) {
      this.socket = socket;
      this.remoteAddress = remoteAddress;
      this.primaryPath = new UdpPath(socket, remoteAddress, primaryCodec);

      try {
         socket.setSoTimeout(100);
      } catch (Exception var5) {
      }
   }

   public void setAuthKey(byte[] key) {
      this.authKeyBytes = key == null ? null : (byte[])key.clone();
      this.authMac = PunchAuth.createMac(this.authKeyBytes);
   }

   public boolean isAuthEnabled() {
      return this.authMac != null;
   }

   public void allowAuthDowngradeForInterop() {
      this.allowAuthDowngrade = true;
   }

   private void handleAuthDrop(String where) {
      PunchAuth.logDrop(where);
      if (this.allowAuthDowngrade && this.authMac != null) {
         if (++this.consecutiveAuthDrops >= 3) {
            LOGGER.warn("[ReliableUdp] peer sends unauthenticated frames x{}, TURN interop downgrade to plaintext", this.consecutiveAuthDrops);
            this.authKeyBytes = null;
            this.authMac = null;
            this.heartbeatFailStreak = 0;
            this.lastRecvTime = System.currentTimeMillis();
         }
      }
   }

   private byte[] finalizeFrame(byte[] frame) {
      Mac mac = this.authMac;
      return mac != null ? PunchAuth.appendTrailer4(mac, frame) : frame;
   }

   public InputStream getInputStream() {
      return this.inputStream;
   }

   public OutputStream getOutputStream() {
      return this.outputStream;
   }

   public void start() {
      if (!this.closed.get()) {
         if (this.connected.compareAndSet(false, true)) {
            try {
               byte[] data = new byte[11];
               System.arraycopy(MAGIC, 0, data, 0, 2);
               data[2] = 8;
               writeInt32(data, 3, 0);
               writeInt32(data, 7, 0);
               byte[] framed = this.finalizeFrame(data);
               this.sendOnPrimary(framed);
            } catch (IOException var3) {
            }

            this.recvThread = new Thread(this::receiveLoop, "VoxLink-UdpRecv");
            this.recvThread.setDaemon(true);
            this.recvThread.start();
            this.retransmitTask = this.scheduler.scheduleWithFixedDelay(this::retransmitCheck, 50L, 50L, TimeUnit.MILLISECONDS);
            this.scheduler.scheduleWithFixedDelay(this::flushOutbound, 50L, 50L, TimeUnit.MILLISECONDS);
            this.keepaliveTask = this.scheduler.scheduleWithFixedDelay(this::heartbeatTick, 1L, 1L, TimeUnit.SECONDS);
         }
      }
   }

   public boolean isConnected() {
      return this.connected.get() && this.running;
   }

   public void sendKeepaliveFrame() {
      this.sendKeepalive();
   }

   public void setOnIceRestartRequested(Runnable r) {
      this.onIceRestartRequested = r;
   }

   public void setOnVoiceData(Consumer<byte[]> c) {
      this.onVoiceData = c;
   }

   private int currentMaxSendChunk() {
      UdpPath pri = this.primaryPath;
      return pri != null && pri.codec != null ? 1374 : 1400;
   }

   public void sendVoice(byte[] payload) {
      UdpPath primary = this.primaryPath;
      boolean anySendable = this.socket != null && !this.socket.isClosed() || primary != null && primary.socket != null && !primary.socket.isClosed();
      if (payload != null && anySendable) {
         if (payload.length > this.currentMaxSendChunk()) {
            LOGGER.debug("[ReliableUdp] Voice payload too large ({}), dropped", payload.length);
         } else {
            byte[] data = new byte[11 + payload.length];
            System.arraycopy(MAGIC, 0, data, 0, 2);
            data[2] = 11;
            System.arraycopy(payload, 0, data, 11, payload.length);
            byte[] framed = this.finalizeFrame(data);

            try {
               this.sendOnPrimary(framed);
            } catch (IOException var7) {
               LOGGER.debug("[ReliableUdp] Voice send failed: {}", var7.getMessage());
            }
         }
      }
   }

   private void handleVoice(byte[] buf, int len) {
      Consumer<byte[]> c = this.onVoiceData;
      if (c != null && len > 11) {
         try {
            c.accept(Arrays.copyOfRange(buf, 11, len));
         } catch (Throwable var5) {
            LOGGER.warn("[ReliableUdp] voice handler exception: {}", var5.getMessage());
         }
      }
   }

   public void requestIceRestart() {
      this.triggerIceRestart();
   }

   private void triggerIceRestart() {
      if (!this.closed.get()) {
         if (this.iceRestartTriggered.compareAndSet(false, true)) {
            Runnable r = this.onIceRestartRequested;
            if (r != null) {
               try {
                  r.run();
               } catch (Exception var3) {
                  LOGGER.warn("[ReliableUdp] ICE Restart callback exception: {}", var3.getMessage());
               }
            }
         }
      }
   }

   private void sendRestart() {
      try {
         byte[] data = new byte[11];
         System.arraycopy(MAGIC, 0, data, 0, 2);
         data[2] = 10;
         writeInt32(data, 3, 0);
         writeInt32(data, 7, 0);
         byte[] framed = this.finalizeFrame(data);
         this.sendOnPrimary(framed);
      } catch (IOException var3) {
      }
   }

   private void sendPunchAck(UdpPath path, SocketAddress from) {
      try {
         byte[] data = new byte[]{MAGIC[0], MAGIC[1], 2, 0, 0};
         byte[] framed = this.finalizeFrame(data);
         path.socket.send(new DatagramPacket(framed, framed.length, from));
      } catch (IOException var5) {
      }
   }

   private void maybeRebindRemote(DatagramPacket packet, UdpPath path) {
      if (path.codec == null) {
         InetSocketAddress cur = path.remoteAddress;
         if (cur != null) {
            boolean isPrimary = path == this.primaryPath;
            boolean fromCurrent = packet.getPort() == cur.getPort() && packet.getAddress().equals(cur.getAddress());
            if (fromCurrent) {
               if (isPrimary) {
                  this.remoteConfirmed = true;
                  this.lastCurrentPortRecvMs = System.currentTimeMillis();
               }

               this.pendingRebindPort = -1;
            } else if (packet.getAddress().equals(cur.getAddress())) {
               long now = System.currentTimeMillis();
               boolean currentAlive = isPrimary && this.lastCurrentPortRecvMs > 0L && now - this.lastCurrentPortRecvMs < 6000L;
               if (!currentAlive) {
                  if (packet.getPort() == this.pendingRebindPort && now - this.pendingRebindTime < 5000L) {
                     path.remoteAddress = new InetSocketAddress(cur.getAddress(), packet.getPort());
                     if (isPrimary) {
                        this.remoteAddress = path.remoteAddress;
                        this.remoteConfirmed = true;
                        this.lastCurrentPortRecvMs = now;
                     }

                     this.pendingRebindPort = -1;
                     LOGGER.info("[ReliableUdp] Remote port drifted {} -> {}, rebind to actual peer socket (symmetric NAT)", cur.getPort(), packet.getPort());
                  } else {
                     this.pendingRebindPort = packet.getPort();
                     this.pendingRebindTime = now;
                  }
               }
            }
         }
      }
   }

   private void receiveLoop() {
      this.recvPathLoop(this.primaryPath);
   }

   private void recvPathLoop(UdpPath path) {
      byte[] buf = new byte[1454];
      DatagramPacket packet = new DatagramPacket(buf, buf.length);

      while (this.running && !path.socket.isClosed()) {
         try {
            path.socket.receive(packet);
            LogUploadManager.onTransportActivity();
            this.processDatagram(buf, packet.getLength(), packet, path);
         } catch (SocketTimeoutException var11) {
         } catch (IOException var12) {
            if (path.socket.isClosed() || !this.running) {
               if (path == this.primaryPath) {
                  this.running = false;
               }
               break;
            }

            LOGGER.warn("[ReliableUdp] Receive error: {}", var12.getMessage());
         } catch (Throwable var13) {
            LOGGER.error("[ReliableUdp] receiveLoop died with exception: {}", var13.getMessage(), var13);
            if (path == this.primaryPath) {
               this.running = false;
               this.connected.set(false);
               synchronized (this.recvLock) {
                  this.recvLock.notifyAll();
               }

               synchronized (this.sendLock) {
                  this.sendLock.notifyAll();
               }

               try {
                  this.scheduler.execute(this::close);
               } catch (Exception var8) {
               }
            }
            break;
         }
      }
   }

   private void processDatagram(byte[] rx, int rxLen, DatagramPacket packet, UdpPath path) {
      byte[] buf = rx;
      int packetLen = rxLen;
      if (path.codec != null) {
         byte[] frame = path.codec.decode(rx, rxLen);
         if (frame == null) {
            return;
         }

         buf = frame;
         packetLen = frame.length;
      }

      if (packetLen >= 3 && buf[0] == MAGIC[0] && buf[1] == MAGIC[1]) {
         Mac rxMac = this.authMac;
         if (rxMac != null) {
            boolean macOk = packetLen >= 9 && PunchAuth.verifyTrailer4(rxMac, buf, packetLen);
            if (!macOk) {
               this.handleAuthDrop("rudp-data");
               return;
            }

            this.consecutiveAuthDrops = 0;
         }

         byte type = buf[2];
         if (type == 1 || type == 2) {
            this.maybeRebindRemote(packet, path);
            this.lastRecvTime = System.currentTimeMillis();
            path.rxCount++;
            path.lastRxMs = System.currentTimeMillis();
            if (type == 1) {
               this.sendPunchAck(path, packet.getSocketAddress());
            }
         } else if (packetLen >= 11) {
            this.maybeRebindRemote(packet, path);
            int seq = readInt32(buf, 3);
            int ack = readInt32(buf, 7);
            switch (type) {
               case 3:
                  this.handleData(seq, ack, buf, packetLen);
                  path.rxCount++;
                  path.lastRxMs = System.currentTimeMillis();
                  break;
               case 4:
                  this.handleAck(ack);
                  this.lastRecvTime = System.currentTimeMillis();
                  path.rxCount++;
                  path.lastRxMs = System.currentTimeMillis();
                  this.consecutiveFailures = 0;
                  if (this.writeState != 0) {
                     this.writeState = 0;
                     LOGGER.info("[ReliableUdp] writeState restored to WRITABLE (via ACK)");
                  }
               case 5:
               case 6:
               default:
                  break;
               case 7:
                  this.handleDisconnect();
                  break;
               case 8:
                  this.lastRecvTime = System.currentTimeMillis();
                  this.consecutiveFailures = 0;
                  path.rxCount++;
                  path.lastRxMs = System.currentTimeMillis();
                  if (this.writeState != 0) {
                     this.writeState = 0;
                     LOGGER.info("[ReliableUdp] writeState restored to WRITABLE");
                  }

                  this.sendKeepalive();
                  break;
               case 9:
                  this.handleFecXor(readInt32(buf, 3), buf, packetLen);
                  break;
               case 10:
                  this.lastRecvTime = System.currentTimeMillis();
                  LOGGER.info("[ReliableUdp] Received peer RESTART signal, trigger ICE Restart");
                  this.triggerIceRestart();
                  break;
               case 11:
                  this.handleVoice(buf, packetLen);
            }
         }
      }
   }

   private void handleData(int seq, int ack, byte[] buf, int packetLen) {
      this.lastRecvTime = System.currentTimeMillis();
      this.processAck(ack);
      byte[] payload = this.copyPayload(buf, packetLen);
      if (payload == null) {
         this.sendAck();
      } else {
         int expected = this.nextExpectedSeq.get();
         if (this.authMac != null && seqAfter(expected, seq) && seqDiff(expected, seq) > 128) {
            LOGGER.warn("[ReliableUdp] Peer restarted (seq {} far behind expected {}), reset receive state", seq, expected);
            this.nextExpectedSeq.set(seq);
            this.recvBuffer.clear();
            this.fecRecvGroup.clear();
            this.fecRecvXor.clear();
            this.fecRecvLengths.clear();
            this.minActiveGroupId = Integer.MAX_VALUE;
         }

         int groupId = seq / 4;
         this.fecRecvGroup.computeIfAbsent(groupId, k -> new ConcurrentHashMap<>()).put(seq, payload);
         if (groupId < this.minActiveGroupId) {
            this.minActiveGroupId = groupId;
         }

         if (this.fecRecvGroup.size() > 20) {
            int cutoff = this.minActiveGroupId + 10;
            this.fecRecvGroup.keySet().removeIf(g -> g < cutoff);
            this.fecRecvXor.keySet().removeIf(g -> g < cutoff);
            this.fecRecvLengths.keySet().removeIf(g -> g < cutoff);
            if (!this.fecRecvGroup.isEmpty()) {
               this.minActiveGroupId = this.fecRecvGroup.keySet().stream().mapToInt(Integer::intValue).min().orElse(this.minActiveGroupId);
            }
         }

         synchronized (this.recvLock) {
            int expectedSeq = this.nextExpectedSeq.get();
            if (seq != expectedSeq) {
               if (!seqAfter(seq, expectedSeq) || seqDiff(seq, expectedSeq) >= 128) {
                  this.sendAck();
                  return;
               }

               this.recvBuffer.putIfAbsent(seq, payload);
            } else {
               if (this.bufferedChunks >= 512) {
                  return;
               }

               this.inputStream.writeBuffer(payload);
               this.bufferedChunks++;
               this.nextExpectedSeq.getAndIncrement();

               while (this.recvBuffer.containsKey(this.nextExpectedSeq.get()) && this.bufferedChunks < 512) {
                  byte[] cached = this.recvBuffer.remove(this.nextExpectedSeq.get());
                  this.inputStream.writeBuffer(cached);
                  this.bufferedChunks++;
                  this.nextExpectedSeq.getAndIncrement();
               }

               this.recvLock.notifyAll();
            }
         }

         this.sendAck();
         this.tryFecRecovery(groupId);
      }
   }

   private byte[] copyPayload(byte[] buf, int packetLen) {
      int payloadLen = readUint16(buf, 11);
      if (13 + payloadLen > packetLen) {
         return null;
      } else {
         byte[] payload = new byte[payloadLen];
         System.arraycopy(buf, 13, payload, 0, payloadLen);
         return payload;
      }
   }

   private void handleFecXor(int groupId, byte[] buf, int packetLen) {
      this.lastRecvTime = System.currentTimeMillis();
      int xorPayloadLen = readUint16(buf, 11);
      int count = buf[13] & 255;
      int lengthsOffset = 14;
      int xorOffset = lengthsOffset + count * 2;
      if (xorOffset + xorPayloadLen <= packetLen) {
         int[] originalLengths = new int[count];

         for (int i = 0; i < count; i++) {
            originalLengths[i] = readUint16(buf, lengthsOffset + i * 2);
         }

         byte[] xorPayload = new byte[xorPayloadLen];
         System.arraycopy(buf, xorOffset, xorPayload, 0, xorPayloadLen);
         this.fecRecvXor.put(groupId, xorPayload);
         this.fecRecvLengths.put(groupId, originalLengths);
         this.tryFecRecovery(groupId);
      }
   }

   private void tryFecRecovery(int groupId) {
      Map<Integer, byte[]> groupData = this.fecRecvGroup.get(groupId);
      byte[] xorPayload = this.fecRecvXor.get(groupId);
      int[] originalLengths = this.fecRecvLengths.get(groupId);
      if (groupData != null && xorPayload != null && originalLengths != null) {
         int startSeq = groupId * 4;
         int missingSeq = -1;
         int missingIndex = -1;
         int receivedCount = 0;

         for (int i = 0; i < 4; i++) {
            int s = startSeq + i;
            if (groupData.containsKey(s)) {
               receivedCount++;
            } else if (seqAfter(this.nextExpectedSeq.get(), s)) {
               receivedCount++;
            } else {
               missingSeq = s;
               missingIndex = i;
            }
         }

         if (receivedCount == 3 && missingSeq >= 0) {
            byte[] recovered = (byte[])xorPayload.clone();

            for (Entry<Integer, byte[]> e : groupData.entrySet()) {
               byte[] p = e.getValue();
               int len = Math.min(recovered.length, p.length);

               for (int ix = 0; ix < len; ix++) {
                  recovered[ix] ^= p[ix];
               }
            }

            int origLen = missingIndex < originalLengths.length ? originalLengths[missingIndex] : recovered.length;
            if (origLen < recovered.length) {
               byte[] trimmed = new byte[origLen];
               System.arraycopy(recovered, 0, trimmed, 0, origLen);
               recovered = trimmed;
            }

            synchronized (this.recvLock) {
               if (this.bufferedChunks < 512) {
                  this.recvBuffer.put(missingSeq, recovered);
               }

               while (this.bufferedChunks < 512 && this.recvBuffer.containsKey(this.nextExpectedSeq.get())) {
                  byte[] cached = this.recvBuffer.remove(this.nextExpectedSeq.get());
                  this.inputStream.writeBuffer(cached);
                  this.bufferedChunks++;
                  this.nextExpectedSeq.getAndIncrement();
               }

               this.recvLock.notifyAll();
            }

            LOGGER.debug("[ReliableUdp] FEC recovered seq {}", missingSeq);
            this.fecRecvGroup.remove(groupId);
            this.fecRecvXor.remove(groupId);
            this.fecRecvLengths.remove(groupId);
         } else if (receivedCount == 4) {
            this.fecRecvGroup.remove(groupId);
            this.fecRecvXor.remove(groupId);
            this.fecRecvLengths.remove(groupId);
         }
      }
   }

   private static byte[] computeXorPayload(List<byte[]> payloads) {
      int maxLen = 0;

      for (byte[] p : payloads) {
         if (p.length > maxLen) {
            maxLen = p.length;
         }
      }

      byte[] xor = new byte[maxLen];

      for (byte[] px : payloads) {
         for (int i = 0; i < px.length; i++) {
            xor[i] ^= px[i];
         }
      }

      return xor;
   }

   private void handleAck(int ack) {
      this.processAck(ack);
   }

   private void processAck(int ack) {
      synchronized (this.sendLock) {
         if (ack == this.lastAckSeq) {
            this.dupAckCount++;
            if (this.dupAckCount >= 3 && !this.pendingAcks.isEmpty()) {
               ReliableUdpTransport.PendingPacket oldest = this.pendingAcks.get(this.oldestUnackedSeq);
               if (oldest != null) {
                  oldest.sendTime = System.currentTimeMillis();
                  oldest.retries++;
                  this.sendDataPacket(this.oldestUnackedSeq, oldest.data, false);
                  this.recordLoss(true);
               }

               this.dupAckCount = 0;
            }
         } else {
            this.lastAckSeq = ack;
            this.dupAckCount = 0;
         }

         while (!this.pendingAcks.isEmpty() && seqAfter(ack, this.oldestUnackedSeq)) {
            ReliableUdpTransport.PendingPacket pp = this.pendingAcks.get(this.oldestUnackedSeq);
            if (pp != null && pp.retries == 0) {
               this.updateRto(System.currentTimeMillis() - pp.sendTime);
            }

            this.pendingAcks.remove(this.oldestUnackedSeq);
            this.oldestUnackedSeq++;
            this.consecutiveFailures = 0;
            this.recordLoss(false);
         }

         if (this.writeState == 1) {
            this.writeState = 0;
            LOGGER.info("[ReliableUdp] writeState UNRELIABLE->WRITABLE (ack received)");
         }

         this.sendLock.notifyAll();
      }
   }

   private void updateRto(long sample) {
      if (sample > 0L) {
         if (this.srtt < 0L) {
            this.srtt = sample;
            this.rttvar = sample / 2L;
         } else {
            this.rttvar = (3L * this.rttvar + Math.abs(this.srtt - sample)) / 4L;
            this.srtt = (7L * this.srtt + sample) / 8L;
         }

         this.rto = Math.max(100L, Math.min(800L, this.srtt + Math.max(10L, 4L * this.rttvar)));
      }
   }

   private void recordLoss(boolean lost) {
      if (lost) {
         this.lossLostEvents++;
      }

      this.lossEvents++;
      if (this.lossEvents >= 200) {
         this.lossLostEvents = (this.lossLostEvents + 1) / 2;
         this.lossEvents = (this.lossEvents + 1) / 2;
      }

      double rate = (double)this.lossLostEvents / (double)Math.max(this.lossEvents, 1);
      int w;
      if (rate < 0.03) {
         w = 64;
      } else if (rate < 0.08) {
         w = 48;
      } else if (rate < 0.15) {
         w = 32;
      } else {
         w = 16;
      }

      if (w != this.effectiveWindow) {
         this.effectiveWindow = w;
         LOGGER.debug("[ReliableUdp] congestion window {} (loss={}%)", w, (int)(rate * 100.0));
      }
   }

   public long getRtoMs() {
      return this.rto;
   }

   private void handleDisconnect() {
      LOGGER.warn("[ReliableUdp] Received DISCONNECT packet");
      this.running = false;
      this.connected.set(false);
      synchronized (this.recvLock) {
         this.recvLock.notifyAll();
      }

      synchronized (this.sendLock) {
         this.sendLock.notifyAll();
      }
   }

   private void sendAck() {
      try {
         byte[] data = new byte[11];
         System.arraycopy(MAGIC, 0, data, 0, 2);
         data[2] = 4;
         writeInt32(data, 3, 0);
         writeInt32(data, 7, this.nextExpectedSeq.get());
         this.enqueueSend(this.finalizeFrame(data));
      } catch (Exception var2) {
         LOGGER.debug("[ReliableUdp] ACK build failed: {}", var2.getMessage());
      }
   }

   private void enqueueSend(byte[] data) {
      if (this.running && !this.closed.get()) {
         this.outboundQueue.offer(data);
      }
   }

   private void flushOutbound() {
      byte[] data;
      if (this.running && !this.closed.get()) {
         while ((data = this.outboundQueue.poll()) != null) {
            try {
               this.sendOnPrimary(data);
            } catch (IOException var3) {
               LOGGER.debug("[ReliableUdp] Outbound send failed: {}", var3.getMessage());
               break;
            }
         }
      } else {
         this.outboundQueue.clear();
      }
   }

   private void sendDataPacket(int seq, byte[] payload, boolean interleave) {
      try {
         byte[] data = new byte[13 + payload.length];
         System.arraycopy(MAGIC, 0, data, 0, 2);
         data[2] = 3;
         writeInt32(data, 3, seq);
         writeInt32(data, 7, this.nextExpectedSeq.get());
         writeUint16(data, 11, payload.length);
         System.arraycopy(payload, 0, data, 13, payload.length);
         byte[] framed = this.finalizeFrame(data);
         this.enqueueSend(framed);
         if (interleave && payload.length < 512 && this.running && !this.closed.get()) {
            byte[] dup = (byte[])framed.clone();
            this.scheduler.schedule(() -> this.enqueueSend(dup), 50L, TimeUnit.MILLISECONDS);
         }
      } catch (Exception var7) {
         LOGGER.debug("[ReliableUdp] Data build failed: {}", var7.getMessage());
      }
   }

   private void sendFecPacket(int groupId, byte[] xorPayload, int[] originalLengths) {
      try {
         int count = originalLengths.length;
         int bodyOffset = 14 + count * 2;
         byte[] data = new byte[bodyOffset + xorPayload.length];
         System.arraycopy(MAGIC, 0, data, 0, 2);
         data[2] = 9;
         writeInt32(data, 3, groupId);
         writeInt32(data, 7, 0);
         writeUint16(data, 11, xorPayload.length);
         data[13] = (byte)count;

         for (int i = 0; i < count; i++) {
            writeUint16(data, 14 + i * 2, originalLengths[i]);
         }

         System.arraycopy(xorPayload, 0, data, bodyOffset, xorPayload.length);
         byte[] framed = this.finalizeFrame(data);
         this.sendOnPrimary(framed);
      } catch (IOException var8) {
         LOGGER.debug("[ReliableUdp] FEC send failed: {}", var8.getMessage());
      }
   }

   private void sendKeepalive() {
      if (this.running && this.connected.get()) {
         try {
            byte[] data = new byte[11];
            System.arraycopy(MAGIC, 0, data, 0, 2);
            data[2] = 8;
            writeInt32(data, 3, 0);
            writeInt32(data, 7, this.nextExpectedSeq.get());
            byte[] framed = this.finalizeFrame(data);
            this.sendOnPrimary(framed);
         } catch (IOException var3) {
         }
      }
   }

   private void heartbeatTick() {
      if (this.running && this.connected.get()) {
         long now = System.currentTimeMillis();
         if (this.lastRecvTime > this.lastHeartbeatMs) {
            this.heartbeatFailStreak = 0;
            this.heartbeatEverRx = true;
         } else if (this.heartbeatEverRx && ++this.heartbeatFailStreak >= 5) {
            LOGGER.warn("[ReliableUdp] One-way dead: {} heartbeats no rx, restart & re-punch", this.heartbeatFailStreak);
            this.sendRestart();
            this.triggerIceRestart();
            this.close();
            return;
         }

         this.lastHeartbeatMs = now;
         this.sendKeepalive();
      }
   }

   private void retransmitCheck() {
      if (this.running) {
         long now = System.currentTimeMillis();
         long silenceMs = now - this.lastRecvTime;
         if (this.writeState == 0 && !this.pendingAcks.isEmpty()) {
            boolean tooManyFailures = this.consecutiveFailures >= 5;
            boolean tooLongNoResp = silenceMs > 8000L;
            if (tooManyFailures && tooLongNoResp) {
               this.writeState = 1;
               LOGGER.warn("[ReliableUdp] writeState WRITABLE->UNRELIABLE (failures={}, silence={}ms)", this.consecutiveFailures, silenceMs);
            }
         }

         if (!this.closed.get() && silenceMs > 60000L) {
            LOGGER.warn("[ReliableUdp] No data received for {}s, connection dead", 60);
            this.sendRestart();
            this.triggerIceRestart();
            this.close();
         } else if (!this.closed.get() && !this.pendingAcks.isEmpty() && silenceMs > 24000L) {
            LOGGER.warn("[ReliableUdp] No packets received for {}ms, {} packets pending, peer probably dead", 24000L, this.pendingAcks.size());
            this.sendRestart();
            this.triggerIceRestart();
            this.close();
         } else {
            synchronized (this.sendLock) {
               long maxRetransmits = Math.max(10L, Math.min(120L, 24000L / Math.max(this.rto, 100L)));
               if (this.lastRecvTime > this.deadPeerStreakStartMs) {
                  this.deadPeerStreakStartMs = this.lastRecvTime;
                  this.deadPeerRetransmitRounds = 0;
               }

               boolean didRetransmit = false;

               for (Entry<Integer, ReliableUdpTransport.PendingPacket> entry : this.pendingAcks.entrySet()) {
                  ReliableUdpTransport.PendingPacket pp = entry.getValue();
                  long backoff = this.rto + (long)Math.min(pp.retries, 3) * 250L;
                  if (now - pp.sendTime > backoff) {
                     if ((long)pp.retries >= maxRetransmits) {
                        LOGGER.warn(
                           "[ReliableUdp] seq {} retry {} times exceeded limit (unacked:{})",
                           new Object[]{entry.getKey(), maxRetransmits, this.pendingAcks.size()}
                        );
                        this.close();
                        return;
                     }

                     if (pp.retries == 0) {
                        LOGGER.debug("[ReliableUdp] Retransmit seq {} (pending={})", entry.getKey(), this.pendingAcks.size());
                     }

                     pp.sendTime = now;
                     pp.retries++;
                     this.consecutiveFailures++;
                     this.recordLoss(true);
                     this.sendDataPacket(entry.getKey(), pp.data, false);
                     didRetransmit = true;
                  }
               }

               if (didRetransmit) {
                  this.deadPeerRetransmitRounds++;
                  long silenceNowMs = now - this.lastRecvTime;
                  if (silenceNowMs > 23000L && this.deadPeerRetransmitRounds >= 20) {
                     LOGGER.warn(
                        "[ReliableUdp] Peer unresponsive ({}ms no rx, {} retransmits), dropping {} pending packets",
                        new Object[]{silenceNowMs, this.deadPeerRetransmitRounds, this.pendingAcks.size()}
                     );
                     this.pendingAcks.clear();
                     this.oldestUnackedSeq = this.nextSendSeq;
                     this.consecutiveFailures = 0;
                     this.dupAckCount = 0;
                     this.deadPeerRetransmitRounds = 0;
                     this.deadPeerStreakStartMs = 0L;
                  }
               }
            }
         }
      }
   }

   private void sendBytes(byte[] data, int offset, int length) throws IOException {
      if (this.running && this.connected.get()) {
         int pos = offset;

         while (pos < offset + length) {
            int chunkLen = Math.min(this.currentMaxSendChunk(), offset + length - pos);
            byte[] chunk = new byte[chunkLen];
            System.arraycopy(data, pos, chunk, 0, chunkLen);
            pos += chunkLen;
            synchronized (this.sendLock) {
               long stuckStartMs = -1L;
               int lastUnacked = this.oldestUnackedSeq;

               while (this.running && this.connected.get() && seqDiff(this.nextSendSeq, this.oldestUnackedSeq) >= this.effectiveWindow) {
                  try {
                     if (stuckStartMs < 0L) {
                        stuckStartMs = System.currentTimeMillis();
                        lastUnacked = this.oldestUnackedSeq;
                     } else if (this.oldestUnackedSeq != lastUnacked) {
                        stuckStartMs = System.currentTimeMillis();
                        lastUnacked = this.oldestUnackedSeq;
                     }

                     this.sendLock.wait(1000L);
                     long stuckMs = System.currentTimeMillis() - stuckStartMs;
                     if (stuckMs >= 30000L) {
                        throw new IOException("transport stuck: " + stuckMs / 1000L + "s no progress");
                     }
                  } catch (InterruptedException var20) {
                     Thread.currentThread().interrupt();
                     throw new IOException("Transport closed or interrupted");
                  }
               }

               if (!this.running || !this.connected.get()) {
                  throw new IOException("Transport closed");
               }

               int seq = this.nextSendSeq++;
               ReliableUdpTransport.PendingPacket pp = new ReliableUdpTransport.PendingPacket(chunk, System.currentTimeMillis(), 0);
               this.pendingAcks.put(seq, pp);
               this.sendDataPacket(seq, chunk, true);
               synchronized (this.fecSendLock) {
                  int groupId = seq / 4;
                  if (groupId != this.fecSendGroupSeq) {
                     this.fecSendGroup.clear();
                     this.fecSendGroupSeq = groupId;
                  }

                  this.fecSendGroup.add((byte[])chunk.clone());
                  if (this.fecSendGroup.size() == 4) {
                     byte[] xorPayload = computeXorPayload(this.fecSendGroup);
                     int[] lengths = new int[4];

                     for (int i = 0; i < 4; i++) {
                        lengths[i] = this.fecSendGroup.get(i).length;
                     }

                     this.sendFecPacket(groupId, xorPayload, lengths);
                     this.fecSendGroup.clear();
                  }
               }
            }
         }

         return;
      } else {
         throw new IOException("Transport closed");
      }
   }

   private void sendOnPrimary(byte[] framed) throws IOException {
      UdpPath p = this.primaryPath;
      if (p != null) {
         try {
            p.send(framed);
         } catch (IOException var6) {
            LOGGER.debug("[ReliableUdp] send failed: {}", var6.getMessage());
         }

         UdpPath s = this.secondaryPath;
         if (s != null && s != p && System.currentTimeMillis() < this.switchGraceUntilMs) {
            try {
               s.send(framed);
            } catch (IOException var5) {
            }
         }
      }
   }

   public synchronized void addSecondaryPath(DatagramSocket sock, InetSocketAddress addr, UdpPath.Codec codec) {
      if (sock != null && addr != null && !this.closed.get() && this.secondaryPath == null) {
         try {
            sock.setSoTimeout(100);
         } catch (Exception var6) {
         }

         UdpPath path = new UdpPath(sock, addr, codec);
         this.secondaryPath = path;
         Thread t = new Thread(() -> this.recvPathLoop(path), "VoxLink-UdpRecv-Alt");
         t.setDaemon(true);
         t.start();
         LOGGER.info("[ReliableUdp] secondary path added: {} (codec={})", addr, codec != null ? "turn" : "raw");
      }
   }

   public synchronized UdpPath getSecondaryPath() {
      return this.secondaryPath;
   }

   public UdpPath getPrimaryPath() {
      return this.primaryPath;
   }

   public long getSecondaryRxCount() {
      UdpPath s = this.secondaryPath;
      return s == null ? 0L : s.rxCount;
   }

   public long getSecondaryLastRxMs() {
      UdpPath s = this.secondaryPath;
      return s == null ? 0L : s.lastRxMs;
   }

   public synchronized void promoteSecondaryPath() {
      UdpPath sec = this.secondaryPath;
      UdpPath pri = this.primaryPath;
      if (sec != null && sec != pri) {
         this.primaryPath = sec;
         this.secondaryPath = pri;
         this.switchGraceUntilMs = System.currentTimeMillis() + 10000L;
         LOGGER.info("[ReliableUdp] path promoted: {} -> {} (grace dual-send 10s)", pri.remoteAddress, sec.remoteAddress);
      }
   }

   public synchronized void dropInactivePath() {
      UdpPath sec = this.secondaryPath;
      if (sec != null) {
         this.secondaryPath = null;
         this.switchGraceUntilMs = 0L;

         try {
            sec.socket.close();
         } catch (Exception var3) {
         }

         LOGGER.info("[ReliableUdp] inactive path dropped");
      }
   }

   @Override
   public void close() {
      if (this.closed.compareAndSet(false, true)) {
         this.running = false;
         this.connected.set(false);

         try {
            byte[] data = new byte[11];
            System.arraycopy(MAGIC, 0, data, 0, 2);
            data[2] = 7;
            writeInt32(data, 3, 0);
            writeInt32(data, 7, 0);
            byte[] framed = this.finalizeFrame(data);
            this.sendOnPrimary(framed);
         } catch (IOException var9) {
         }

         if (this.retransmitTask != null) {
            this.retransmitTask.cancel(false);
         }

         if (this.keepaliveTask != null) {
            this.keepaliveTask.cancel(false);
         }

         this.scheduler.shutdownNow();
         synchronized (this.recvLock) {
            this.recvLock.notifyAll();
         }

         synchronized (this.sendLock) {
            this.sendLock.notifyAll();
         }

         if (this.recvThread != null) {
            this.recvThread.interrupt();
         }

         try {
            if (this.primaryPath != null && this.primaryPath.socket != null && !this.primaryPath.socket.isClosed()) {
               this.primaryPath.socket.close();
            }
         } catch (Exception var6) {
         }

         UdpPath sec = this.secondaryPath;
         if (sec != null) {
            try {
               sec.socket.close();
            } catch (Exception var5) {
            }

            this.secondaryPath = null;
         }
      }
   }

   private static int readInt32(byte[] buf, int offset) {
      return (buf[offset] & 0xFF) << 24 | (buf[offset + 1] & 0xFF) << 16 | (buf[offset + 2] & 0xFF) << 8 | buf[offset + 3] & 0xFF;
   }

   private static boolean seqAfter(int a, int b) {
      return a > b && a - b < 1073741823 || a < b && b - a > 1073741823;
   }

   private static int seqDiff(int newer, int older) {
      long diff = (long)newer - (long)older & 4294967295L;
      return (int)diff;
   }

   private static int readUint16(byte[] buf, int offset) {
      return (buf[offset] & 0xFF) << 8 | buf[offset + 1] & 0xFF;
   }

   private static void writeInt32(byte[] buf, int offset, int value) {
      buf[offset] = (byte)(value >> 24);
      buf[offset + 1] = (byte)(value >> 16);
      buf[offset + 2] = (byte)(value >> 8);
      buf[offset + 3] = (byte)value;
   }

   private static void writeUint16(byte[] buf, int offset, int value) {
      buf[offset] = (byte)(value >> 8);
      buf[offset + 1] = (byte)value;
   }

   private static class PendingPacket {
      final byte[] data;
      long sendTime;
      int retries;

      PendingPacket(byte[] data, long sendTime, int retries) {
         this.data = data;
         this.sendTime = sendTime;
         this.retries = retries;
      }
   }

   private class UdpInputStream extends InputStream {
      private final ConcurrentLinkedQueue<byte[]> chunks;
      private byte[] currentChunk;
      private int currentPos;

      private UdpInputStream() {
         Objects.requireNonNull(ReliableUdpTransport.this);
         super();
         this.chunks = new ConcurrentLinkedQueue<>();
         this.currentChunk = null;
         this.currentPos = 0;
      }

      void writeBuffer(byte[] data) {
         this.chunks.offer(data);
      }

      @Override
      public int read() throws IOException {
         byte[] b = new byte[1];
         int n = this.read(b, 0, 1);
         return n <= 0 ? -1 : b[0] & 0xFF;
      }

      @Override
      public int read(byte[] b, int off, int len) throws IOException {
         if (len == 0) {
            return 0;
         } else {
            synchronized (ReliableUdpTransport.this.recvLock) {
               while (this.currentChunk == null || this.currentPos >= this.currentChunk.length) {
                  this.currentChunk = this.chunks.poll();
                  this.currentPos = 0;
                  if (this.currentChunk != null) {
                     if (ReliableUdpTransport.this.bufferedChunks > 0) {
                        ReliableUdpTransport.this.bufferedChunks--;
                     }
                  } else {
                     if (!ReliableUdpTransport.this.running && this.chunks.isEmpty()) {
                        return -1;
                     }

                     try {
                        ReliableUdpTransport.this.recvLock.wait(500L);
                     } catch (InterruptedException var8) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted");
                     }
                  }
               }

               int avail = this.currentChunk.length - this.currentPos;
               int toRead = Math.min(len, avail);
               System.arraycopy(this.currentChunk, this.currentPos, b, off, toRead);
               this.currentPos += toRead;
               if (this.currentPos >= this.currentChunk.length) {
                  this.currentChunk = null;
                  this.currentPos = 0;
               }

               return toRead;
            }
         }
      }

      @Override
      public int available() {
         synchronized (ReliableUdpTransport.this.recvLock) {
            int total = 0;
            if (this.currentChunk != null) {
               total += this.currentChunk.length - this.currentPos;
            }

            for (byte[] chunk : this.chunks.toArray(new byte[0][])) {
               total += chunk.length;
            }

            return total;
         }
      }
   }

   private class UdpOutputStream extends OutputStream {
      private UdpOutputStream() {
         Objects.requireNonNull(ReliableUdpTransport.this);
         super();
      }

      @Override
      public void write(int b) throws IOException {
         this.write(new byte[]{(byte)b}, 0, 1);
      }

      @Override
      public void write(byte[] b, int off, int len) throws IOException {
         try {
            ReliableUdpTransport.this.sendBytes(b, off, len);
         } catch (IOException var5) {
            ReliableUdpTransport.LOGGER.debug("[ReliableUdp] Write failed: {}", var5.getMessage());
            throw var5;
         }
      }

      @Override
      public void flush() {
      }
   }
}
