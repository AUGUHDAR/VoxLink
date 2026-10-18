package icu.wuhui.voxlink.network;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.crypto.Mac;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UdpHolePuncher {
   private static final Logger LOGGER = LoggerFactory.getLogger("voxlink-punch");
   private static final ConcurrentHashMap<String, Long> BLACKLIST_WARN_AT = new ConcurrentHashMap<>();
   private static final byte[] MAGIC = new byte[]{86, 76};
   private static final byte TYPE_PUNCH = 1;
   private static final byte TYPE_PUNCH_ACK = 2;
   private static final int CONTROL_PLAIN_LEN = 5;
   private static final int CONTROL_AUTH_LEN = 9;
   private static volatile ScheduledExecutorService PUNCH_TIMEOUT_SCHEDULER = createScheduler();
   private DatagramSocket socket;
   private final int sessionNonce = ThreadLocalRandom.current().nextInt(65536);
   private volatile byte[] authKeyBytes;
   private volatile Mac authMac;
   private final AtomicBoolean punching = new AtomicBoolean(false);
   private final AtomicBoolean holeOpen = new AtomicBoolean(false);
   private final AtomicBoolean remoteReceived = new AtomicBoolean(false);
   private final AtomicBoolean localConfirmed = new AtomicBoolean(false);
   private final AtomicBoolean completed = new AtomicBoolean(false);
   private volatile InetAddress remoteAddress;
   private volatile int remotePort;
   private volatile List<Thread> recvThreadsRef = null;
   private volatile Thread sendThreadRef;
   private volatile ScheduledFuture<?> timeoutFuture;
   private volatile boolean socketTransferred = false;
   private volatile CompletableFuture<PunchResult> activeResult;
   private volatile Consumer<InetSocketAddress> onPeerPunchReceived;
   private volatile List<UdpHolePuncher> socketGroup;
   private volatile boolean skipFirewallDetection;
   private volatile PunchProfile profile;
   private volatile PunchParams punchParams;
   private int[] hardSymSprayTable;
   private int hardSymSprayCursor;
   private static final long PUNCH_LOG_INFO_DEDUP_MS = 60000L;
   private static final Object PUNCH_LOG_DEDUP_LOCK = new Object();
   private static String punchStartLastInfoKey = "";
   private static long punchStartLastInfoMs = 0L;
   private static String multiPortLastInfoKey = "";
   private static long multiPortLastInfoMs = 0L;

   public void setProfile(PunchProfile p) {
      this.profile = p;
   }

   public void setAuthKey(byte[] key) {
      this.authKeyBytes = key == null ? null : (byte[])key.clone();
      this.authMac = PunchAuth.createMac(this.authKeyBytes);
   }

   public void inheritAuthFrom(UdpHolePuncher other) {
      if (other != null) {
         byte[] key = other.authKeyBytes;
         if (key != null) {
            this.setAuthKey(key);
         }
      }
   }

   public boolean isAuthEnabled() {
      return this.authMac != null;
   }

   private byte[] buildControl(byte type) {
      byte[] base = new byte[]{MAGIC[0], MAGIC[1], type, (byte)(this.sessionNonce >> 8), (byte)this.sessionNonce};
      Mac mac = this.authMac;
      return mac != null ? PunchAuth.appendTrailer4(mac, base) : base;
   }

   public PunchProfile profile() {
      PunchProfile p = this.profile;
      return p != null ? p : PunchProfile.current();
   }

   public void setPunchParams(PunchParams params) {
      this.punchParams = params;
   }

   public PunchParams punchParams() {
      return this.punchParams;
   }

   public int effectiveTimeoutMs() {
      PunchParams p = this.punchParams;
      return p != null && p.timeoutMs > 0 ? p.timeoutMs : this.profile().punchTimeoutMs;
   }

   public int effectivePortRange() {
      PunchParams p = this.punchParams;
      return p != null && p.portRange > 0 ? p.portRange : this.profile().portPredictionMaxRange;
   }

   public int effectiveSendInterval() {
      PunchParams p = this.punchParams;
      return p != null && p.sendInterval > 0 ? p.sendInterval : this.profile().send.intervalMs;
   }

   public int effectiveMinRounds() {
      PunchParams p = this.punchParams;
      return p != null && p.sendMinRounds > 0 ? p.sendMinRounds : this.profile().send.minRounds;
   }

   public int effectiveMinPass() {
      PunchParams p = this.punchParams;
      return p != null && p.sendMinPass > 0 ? p.sendMinPass : this.profile().send.minPass;
   }

   public boolean effectiveSkipDirectPunch() {
      PunchParams p = this.punchParams;
      return p != null && p.skipDirectPunch;
   }

   public static void shutdown() {
      ScheduledExecutorService s = PUNCH_TIMEOUT_SCHEDULER;
      if (s != null) {
         s.shutdown();
         s.shutdownNow();
      }
   }

   private PunchResult failAndRecord(int tried, int recvPunch, int recvAck, long elapsed, boolean firewall) {
      PunchResult fr = PunchResult.failure(tried, recvPunch, recvAck, 0, elapsed, firewall);

      try {
         PunchFailureClassifier.FailureReason reason = PunchFailureClassifier.classify(fr);
         if (reason == PunchFailureClassifier.FailureReason.NO_RESPONSE
            || reason == PunchFailureClassifier.FailureReason.FIREWALL_DETECTED
            || reason == PunchFailureClassifier.FailureReason.PREDICTION_OFF) {
            InetAddress addr = this.remoteAddress;
            int port = this.remotePort;
            if (addr != null && port > 0) {
               AddressBlacklist.get().recordUdpFailure(new InetSocketAddress(addr, port));
            }
         }
      } catch (Exception var11) {
      }

      return fr;
   }

   public static void observeBlacklistedTarget(InetAddress addr, int port) {
      if (addr != null && port > 0) {
         if (AddressBlacklist.get().isBlacklisted(new InetSocketAddress(addr, port))) {
            warnBlacklistedOnce(addr, port);
         }
      }
   }

   private static void warnBlacklistedOnce(InetAddress addr, int port) {
      String key = addr.getHostAddress() + ":" + port;
      long now = System.currentTimeMillis();
      Long last = BLACKLIST_WARN_AT.put(key, now);
      if (last == null || now - last >= 30000L) {
         LOGGER.warn("[Punch] target {} blacklisted (observation-only), punching anyway", key);
      }
   }

   public int getRemotePort() {
      return this.remotePort;
   }

   private static ScheduledExecutorService createScheduler() {
      return Executors.newSingleThreadScheduledExecutor(r -> {
         Thread t = new Thread(r, "VoxLink-PunchTimeout");
         t.setDaemon(true);
         return t;
      });
   }

   private static ScheduledExecutorService scheduler() {
      ScheduledExecutorService s = PUNCH_TIMEOUT_SCHEDULER;
      if (s == null || s.isShutdown()) {
         synchronized (UdpHolePuncher.class) {
            if (PUNCH_TIMEOUT_SCHEDULER == null || PUNCH_TIMEOUT_SCHEDULER.isShutdown()) {
               PUNCH_TIMEOUT_SCHEDULER = createScheduler();
            }
         }
      }

      return PUNCH_TIMEOUT_SCHEDULER;
   }

   void replaceSocket(DatagramSocket newSocket) {
      DatagramSocket old = this.socket;
      if (old != null && old != newSocket && !old.isClosed()) {
         try {
            old.close();
         } catch (Exception var4) {
         }
      }

      this.socket = newSocket;
   }

   public void markSocketTransferred() {
      this.socketTransferred = true;
   }

   public boolean isSocketTransferred() {
      return this.socketTransferred;
   }

   public void setSkipFirewallDetection(boolean skip) {
      this.skipFirewallDetection = skip;
   }

   public boolean isPunching() {
      return this.punching.get();
   }

   public DatagramSocket createSocket() throws SocketException {
      DatagramSocket old = this.socket;
      if (old != null && !old.isClosed()) {
         old.close();
      }

      this.socket = new DatagramSocket();
      this.socket.setSoTimeout(this.profile().send.socketTimeoutMs);
      return this.socket;
   }

   public DatagramSocket createSocket(int preferredPort) throws SocketException {
      DatagramSocket old = this.socket;
      if (old != null && !old.isClosed()) {
         old.close();
      }

      try {
         this.socket = new DatagramSocket(preferredPort);
         this.socket.setSoTimeout(this.profile().send.socketTimeoutMs);
         return this.socket;
      } catch (SocketException var4) {
         return this.createSocket();
      }
   }

   public DatagramSocket getSocket() {
      return this.socket;
   }

   public StunProbe.PublicMappedAddress discoverMappedAddress(List<String> stunUrls) {
      return StunProbe.discoverMappedAddress(this.socket, stunUrls);
   }

   public StunProbe.PublicMappedAddress[] discoverMappedAddressDual(String stunUrl1, String stunUrl2) {
      return StunProbe.discoverMappedAddressDual(this.socket, stunUrl1, stunUrl2);
   }

   public CompletableFuture<PunchResult> punch(String remoteIp, int remotePort) {
      return this.punchWithPortPrediction(remoteIp, remotePort, 0);
   }

   public CompletableFuture<PunchResult> punchWithPortPrediction(String remoteIp, int basePort, int portRange) {
      return this.punchWithPortPrediction(remoteIp, basePort, portRange, false);
   }

   public CompletableFuture<PunchResult> punchMultiSocket(String remoteIp, int targetPort, List<UdpHolePuncher> socketGroup, AtomicBoolean wonFlag) {
      return this.punchMultiSocket(remoteIp, targetPort, socketGroup, wonFlag, 0);
   }

   public CompletableFuture<PunchResult> punchMultiSocket(
      String remoteIp, int targetPort, List<UdpHolePuncher> socketGroup, AtomicBoolean wonFlag, int sweepSpread
   ) {
      this.punching.set(true);
      this.holeOpen.set(false);
      this.remoteReceived.set(false);
      this.localConfirmed.set(false);
      this.completed.set(false);

      try {
         this.remoteAddress = InetAddress.getByName(remoteIp);
         this.remotePort = targetPort;
      } catch (Exception var38) {
         return CompletableFuture.failedFuture(var38);
      }

      observeBlacklistedTarget(this.remoteAddress, targetPort);
      CompletableFuture<PunchResult> result = new CompletableFuture<>();
      this.activeResult = result;
      this.socketGroup = socketGroup;
      Object completionLock = new Object();
      Consumer<InetSocketAddress> peerPunchCb = this.onPeerPunchReceived;
      int[] recvPunchCounter = new int[]{0};
      int[] recvAckCounter = new int[]{0};
      long startTime = System.currentTimeMillis();
      int socketsTried = socketGroup.size();
      byte[] data = this.buildControl((byte)1);
      PunchParams mode = this.punchParams != null ? this.punchParams : PunchParams.fromProfile(this.profile());
      boolean sprayMode = mode.hardSymSpray;
      boolean bombMode = mode.easySymBomb;
      UdpHolePuncher.PpsLimiter ppsLimiter = new UdpHolePuncher.PpsLimiter(this.profile().sym.maxPps);
      int roundInterval = bombMode ? Math.min(this.effectiveSendInterval(), Math.max(1, mode.bombRoundIntervalMs)) : this.effectiveSendInterval();
      int maxTotalCycles = this.effectiveTimeoutMs() / Math.max(1, roundInterval);
      String modeName = sprayMode ? "hard_sym_spray" : (bombMode ? "easy_sym_bomb" : "classic");
      if (shouldLogPunchStartInfo(remoteIp, targetPort)) {
         LOGGER.info(
            "[UdpHolePuncher] Multi-socket send start: target={}:{}, sockets={}, interval={}ms, mode={}, profile={}",
            new Object[]{remoteIp, targetPort, socketGroup.size(), roundInterval, modeName, this.profile().describeInstance()}
         );
      } else {
         LOGGER.debug(
            "[UdpHolePuncher] Multi-socket send start: target={}:{}, sockets={}, interval={}ms, mode={}, profile={}",
            new Object[]{remoteIp, targetPort, socketGroup.size(), roundInterval, modeName, this.profile().describeInstance()}
         );
      }

      List<DatagramChannel> channels = new ArrayList<>();
      Map<DatagramChannel, UdpHolePuncher> channelToPuncher = new HashMap<>();
      Map<DatagramChannel, Integer> channelToIndex = new HashMap<>();
      Selector selector = null;
      Set<DatagramChannel> transferredChannels = Collections.newSetFromMap(new ConcurrentHashMap<>());

      try {
         selector = Selector.open();

         for (int si = 0; si < socketGroup.size(); si++) {
            UdpHolePuncher sp = socketGroup.get(si);
            DatagramSocket ssock = sp.getSocket();
            if (ssock != null && !ssock.isClosed()) {
               DatagramChannel ch = ssock.getChannel();
               if (ch == null) {
                  InetAddress localAddr = ssock.getLocalAddress();
                  int localPort = ssock.getLocalPort();

                  try {
                     ssock.close();
                  } catch (Exception var37) {
                  }

                  ch = DatagramChannel.open();
                  ch.configureBlocking(false);
                  ch.bind(new InetSocketAddress(localAddr, localPort));
                  sp.replaceSocket(ch.socket());
                  transferredChannels.add(ch);
               } else {
                  ch.configureBlocking(false);
               }

               ch.register(selector, 1);
               channels.add(ch);
               channelToPuncher.put(ch, sp);
               channelToIndex.put(ch, si);
            }
         }
      } catch (IOException var39) {
         LOGGER.warn("[UdpHolePuncher] NIO Selector init failed, fallback to multi-thread mode: {}", var39.getMessage());
         if (selector != null) {
            try {
               selector.close();
            } catch (Exception var36) {
            }
         }

         for (DatagramChannel ch : channels) {
            if (ch != null && !transferredChannels.contains(ch)) {
               try {
                  ch.close();
               } catch (Exception var35) {
               }
            }
         }

         return this.punchMultiSocketLegacy(remoteIp, targetPort, socketGroup, wonFlag);
      }

      int finalNonce = this.sessionNonce;
      Thread recvThread = new Thread(
         () -> {
            ByteBuffer buf = ByteBuffer.allocate(64);
            boolean peerPunchNotified = false;

            try {
               label290:
               while (this.punching.get() && !this.holeOpen.get()) {
                  int ready = selector.select(500L);
                  if (ready != 0) {
                     Iterator<SelectionKey> it = selector.selectedKeys().iterator();

                     while (true) {
                        SelectionKey key;
                        DatagramChannel chx;
                        InetSocketAddress from;
                        while (true) {
                           if (!it.hasNext()) {
                              continue label290;
                           }

                           key = it.next();
                           it.remove();
                           if (key.isReadable()) {
                              chx = (DatagramChannel)key.channel();
                              buf.clear();

                              try {
                                 from = (InetSocketAddress)chx.receive(buf);
                                 break;
                              } catch (IOException var53) {
                              }
                           }
                        }

                        if (from != null && buf.position() >= 3) {
                           buf.flip();
                           int pktLen = buf.limit();
                           byte b0 = buf.get(0);
                           byte b1 = buf.get(1);
                           byte b2 = buf.get(2);
                           boolean nioAccepted = false;
                           if (b0 == MAGIC[0] && b1 == MAGIC[1]) {
                              Mac nioMac = this.authMac;
                              boolean authenticated = false;
                              boolean macOk = true;
                              if (nioMac != null) {
                                 if (pktLen >= 9) {
                                    byte[] tmp = new byte[pktLen];
                                    buf.position(0);
                                    buf.get(tmp);
                                    macOk = PunchAuth.verifyTrailer4(nioMac, tmp, pktLen);
                                 } else {
                                    macOk = false;
                                 }

                                 if (!macOk) {
                                    PunchAuth.logDrop("punch-control-nio");
                                 } else {
                                    authenticated = true;
                                 }
                              }

                              nioAccepted = macOk && this.acceptAddress(from.getAddress(), b2, authenticated);
                           }

                           if (nioAccepted) {
                              UdpHolePuncher sp = channelToPuncher.get(chx);
                              int sIdx = channelToIndex.get(chx);
                              if (b2 == 1) {
                                 recvPunchCounter[0]++;
                                 this.remoteReceived.set(true);
                                 sp.sendControlTo((byte)2, from.getAddress(), from.getPort());
                                 synchronized (completionLock) {
                                    if ((this.localConfirmed.get() || this.remoteReceived.get())
                                       && wonFlag.compareAndSet(false, true)
                                       && this.completed.compareAndSet(false, true)) {
                                       this.holeOpen.set(true);
                                       this.punching.set(false);
                                       sp.socketTransferred = true;
                                       this.remoteAddress = from.getAddress();
                                       this.remotePort = from.getPort();
                                       LOGGER.info("[UdpHolePuncher] socket#{} received PUNCH, punch success (NIO)", sIdx);

                                       try {
                                          key.cancel();

                                          try {
                                             selector.selectNow();
                                          } catch (Exception var51) {
                                          }

                                          chx.configureBlocking(true);
                                       } catch (Exception var52) {
                                          LOGGER.warn("[UdpHolePuncher] win socket restore blocking failed: {}", String.valueOf(var52));
                                       }

                                       long elapsed = System.currentTimeMillis() - startTime;
                                       result.complete(PunchResult.success(sp.getSocket(), socketsTried, recvPunchCounter[0], recvAckCounter[0], 0, elapsed));
                                    }
                                 }
                              } else if (b2 == 2) {
                                 recvAckCounter[0]++;
                                 this.localConfirmed.set(true);
                                 sp.sendControlTo((byte)2, from.getAddress(), from.getPort());
                                 synchronized (completionLock) {
                                    if ((this.localConfirmed.get() || this.remoteReceived.get())
                                       && wonFlag.compareAndSet(false, true)
                                       && this.completed.compareAndSet(false, true)) {
                                       this.holeOpen.set(true);
                                       this.punching.set(false);
                                       sp.socketTransferred = true;
                                       this.remoteAddress = from.getAddress();
                                       this.remotePort = from.getPort();
                                       LOGGER.info("[UdpHolePuncher] socket#{} received ACK, punch success (NIO)", sIdx);

                                       try {
                                          key.cancel();

                                          try {
                                             selector.selectNow();
                                          } catch (Exception var49) {
                                          }

                                          chx.configureBlocking(true);
                                       } catch (Exception var50) {
                                          LOGGER.warn("[UdpHolePuncher] win socket restore blocking failed: {}", String.valueOf(var50));
                                       }

                                       long elapsed = System.currentTimeMillis() - startTime;
                                       result.complete(PunchResult.success(sp.getSocket(), socketsTried, recvPunchCounter[0], recvAckCounter[0], 0, elapsed));
                                    }
                                 }
                              }

                              if (!peerPunchNotified && peerPunchCb != null) {
                                 peerPunchNotified = true;

                                 try {
                                    peerPunchCb.accept(from);
                                 } catch (Exception var48) {
                                 }
                              }
                           }
                        }
                     }
                  }
               }
            } catch (IOException var56) {
               LOGGER.debug("[UdpHolePuncher] Selector receive exception: {}", var56.getMessage());
            } finally {
               try {
                  selector.close();
               } catch (IOException var47) {
               }
            }
         },
         "VoxLink-PunchRecvNIO"
      );
      recvThread.setDaemon(true);
      this.recvThreadsRef = Collections.singletonList(recvThread);
      recvThread.start();
      boolean skipFirewallCheck = socketGroup.size() <= 1 || this.skipFirewallDetection;
      Thread sendThread = new Thread(
         () -> {
            int cycles = 0;
            long sendStartMs = System.currentTimeMillis();

            while (this.punching.get() && !this.holeOpen.get() && cycles < maxTotalCycles) {
               if (!skipFirewallCheck && cycles >= this.profile().firewallDetectCycles && !this.remoteReceived.get()) {
                  long elapsed = System.currentTimeMillis() - sendStartMs;
                  LOGGER.warn("[UdpHolePuncher] Multi-socket firewall check: sent {} cycles/{}ms no reply, UDP blocked, abort early", cycles, elapsed);
                  synchronized (completionLock) {
                     if (this.completed.compareAndSet(false, true)) {
                        this.punching.set(false);
                        result.complete(this.failAndRecord(socketsTried, recvPunchCounter[0], recvAckCounter[0], elapsed, true));
                     }

                     return;
                  }
               }

               if (sprayMode) {
                  if (!this.sprayRound(data, mode, socketGroup, ++cycles, ppsLimiter)) {
                     break;
                  }
               } else {
                  if (bombMode) {
                     for (UdpHolePuncher sp : socketGroup) {
                        DatagramSocket s = sp.getSocket();
                        if (s != null && !s.isClosed()) {
                           for (int off = -mode.bombWindow; off <= mode.bombWindow; off++) {
                              int dp = this.remotePort + off;
                              if (dp < 1) {
                                 dp = 1;
                              } else if (dp > 65535) {
                                 dp = 65535;
                              }

                              DatagramPacket pkt = new DatagramPacket(data, data.length, this.remoteAddress, dp);
                              ppsLimiter.beforeSend();
                              sendPkt(s, pkt);
                           }
                        }
                     }
                  } else {
                     int sweepSpan = sweepSpread > 0 ? sweepSpread * 2 + 1 : 0;
                     int sweepIdx = 0;

                     for (UdpHolePuncher spx : socketGroup) {
                        DatagramSocket s = spx.getSocket();
                        if (s != null && !s.isClosed()) {
                           int destPort = this.remotePort;
                           if (sweepSpread > 0) {
                              destPort = this.remotePort + (sweepIdx++ % sweepSpan - sweepSpread);
                           }

                           for (int r = 0; r < 3; r++) {
                              DatagramPacket pkt = new DatagramPacket(data, data.length, this.remoteAddress, destPort);
                              sendPkt(s, pkt);
                           }
                        }
                     }
                  }

                  cycles++;

                  try {
                     int sleepMs = bombMode && System.currentTimeMillis() - sendStartMs < (long)mode.bombDurationMs
                        ? mode.bombRoundIntervalMs
                        : this.effectiveSendInterval();
                     Thread.sleep((long)sleepMs);
                  } catch (InterruptedException var31x) {
                     Thread.currentThread().interrupt();
                     break;
                  }
               }
            }

            LOGGER.info("[UdpHolePuncher] Multi-socket send end: cycles={}, holeOpen={}", cycles, this.holeOpen.get());
            if (!this.holeOpen.get() && this.punching.get()) {
               synchronized (completionLock) {
                  if (this.completed.compareAndSet(false, true)) {
                     this.punching.set(false);
                     long elapsed = System.currentTimeMillis() - startTime;
                     result.complete(this.failAndRecord(socketsTried, recvPunchCounter[0], recvAckCounter[0], elapsed, false));
                  }
               }
            }
         },
         "VoxLink-PunchSend"
      );
      sendThread.setDaemon(true);
      this.sendThreadRef = sendThread;
      sendThread.start();
      ScheduledFuture<?> tf = scheduler().schedule(() -> {
         if (this.punching.get()) {
            synchronized (completionLock) {
               if (this.completed.compareAndSet(false, true)) {
                  this.punching.set(false);
                  long elapsed = System.currentTimeMillis() - startTime;
                  result.complete(this.failAndRecord(socketsTried, recvPunchCounter[0], recvAckCounter[0], elapsed, false));
               }
            }
         }
      }, (long)(this.effectiveTimeoutMs() + this.profile().send.extraWaitMs), TimeUnit.MILLISECONDS);
      this.timeoutFuture = tf;
      P2PBridge.registerPendingUdpTimeout(tf);
      return result;
   }

   private boolean sprayRound(byte[] data, PunchParams mode, List<UdpHolePuncher> group, int round, UdpHolePuncher.PpsLimiter limiter) {
      if (this.hardSymSprayTable == null) {
         int[] table = new int[65535];

         for (int i = 0; i < table.length; i++) {
            table[i] = i + 1;
         }

         for (int i = table.length - 1; i > 0; i--) {
            int j = ThreadLocalRandom.current().nextInt(i + 1);
            int t = table[i];
            table[i] = table[j];
            table[j] = t;
         }

         this.hardSymSprayTable = table;
         this.hardSymSprayCursor = 0;
      }

      for (int r = 0; r < mode.sprayPacketsPerPort; r++) {
         DatagramPacket cone = new DatagramPacket(data, data.length, this.remoteAddress, this.remotePort);
         limiter.beforeSend();
         sendPkt(this.socket, cone);
      }

      int span = Math.max(1, mode.sprayPortCountMax - mode.sprayPortCountMin + 1);
      int base = mode.sprayPortCountMin + ThreadLocalRandom.current().nextInt(span);
      int count = round > 2 ? Math.max(base * mode.sprayDecayNumerator / round, mode.sprayDecayFloor) : base;
      LOGGER.debug("[UdpHolePuncher] spray round {}: {} ports, cursor={}", new Object[]{round, count, this.hardSymSprayCursor});

      for (int i = 0; i < count; i++) {
         int port = this.nextSprayPort();
         DatagramSocket s = group.get(i % group.size()).getSocket();
         if (s != null && !s.isClosed()) {
            for (int r = 0; r < mode.sprayPacketsPerPort; r++) {
               DatagramPacket pkt = new DatagramPacket(data, data.length, this.remoteAddress, port);
               limiter.beforeSend();
               sendPkt(s, pkt);
            }

            try {
               Thread.sleep((long)Math.max(0, mode.sprayPortIntervalMs));
            } catch (InterruptedException var14) {
               Thread.currentThread().interrupt();
               return false;
            }
         }
      }

      return true;
   }

   private int nextSprayPort() {
      int idx = this.hardSymSprayCursor % this.hardSymSprayTable.length;
      this.hardSymSprayCursor = idx + 1;
      return this.hardSymSprayTable[idx];
   }

   private void sendControlTo(byte type, InetAddress addr, int port) {
      byte[] d = this.buildControl(type);
      DatagramPacket packet = new DatagramPacket(d, d.length, addr, port);
      sendPkt(this.socket, packet);
   }

   private static void sendPkt(DatagramSocket s, DatagramPacket pkt) {
      for (int attempt = 0; attempt < 3; attempt++) {
         try {
            DatagramChannel ch = s.getChannel();
            if (ch != null) {
               ByteBuffer bb = ByteBuffer.wrap(pkt.getData(), 0, pkt.getLength());
               ch.send(bb, pkt.getSocketAddress());
            } else {
               s.send(pkt);
            }

            return;
         } catch (RuntimeException | IOException var5) {
         }
      }
   }

   private boolean acceptAddress(InetAddress addr, byte type, boolean authenticated) {
      InetAddress exp = this.remoteAddress;
      if (exp == null) {
         return false;
      } else if (exp.equals(addr)) {
         return true;
      } else {
         if (!authenticated && (type == 1 || type == 2) && this.punching.get()) {
            byte[] a = exp.getAddress();
            byte[] b = addr.getAddress();
            boolean sameSegment = a != null && b != null && a.length == 4 && b.length == 4 && a[0] == b[0] && a[1] == b[1];
            if (sameSegment) {
               LOGGER.info("[UdpHolePuncher] CGNAT multi-IP accept {} (expected {})", new Object[]{addr.getHostAddress(), exp.getHostAddress()});
               this.remoteAddress = addr;
               return true;
            }
         }

         return false;
      }
   }

   private boolean acceptAddress(InetAddress addr, byte type) {
      return this.acceptAddress(addr, type, false);
   }

   private boolean acceptPacket(byte[] buf, int len, DatagramPacket packet) {
      if (len >= 3 && buf[0] == MAGIC[0] && buf[1] == MAGIC[1]) {
         Mac mac = this.authMac;
         boolean authenticated = false;
         if (mac != null) {
            if (len < 9 || !PunchAuth.verifyTrailer4(mac, buf, len)) {
               PunchAuth.logDrop("punch-control");
               return false;
            }

            authenticated = true;
         }

         return this.acceptAddress(packet.getAddress(), buf[2], authenticated);
      } else {
         return false;
      }
   }

   private boolean acceptPacket(byte[] buf, DatagramPacket packet) {
      return this.acceptPacket(buf, packet.getLength(), packet);
   }

   public CompletableFuture<PunchResult> punchMultiPort(String remoteIp, List<Integer> targetPorts) {
      if (targetPorts != null && !targetPorts.isEmpty()) {
         this.punching.set(true);
         this.holeOpen.set(false);
         this.remoteReceived.set(false);
         this.localConfirmed.set(false);
         this.completed.set(false);
         LOGGER.info(
            "[UdpHolePuncher] punchMultiPort start: target={}, port count={}, range={}~{}, profile={}",
            new Object[]{remoteIp, targetPorts.size(), targetPorts.get(0), targetPorts.get(targetPorts.size() - 1), this.profile().describeInstance()}
         );

         try {
            this.remoteAddress = InetAddress.getByName(remoteIp);
            this.remotePort = targetPorts.get(0);
         } catch (Exception var14) {
            this.punching.set(false);
            return CompletableFuture.failedFuture(var14);
         }

         observeBlacklistedTarget(this.remoteAddress, targetPorts.get(0));
         CompletableFuture<PunchResult> result = new CompletableFuture<>();
         this.activeResult = result;
         Object completionLock = new Object();
         Consumer<InetSocketAddress> peerPunchCb = this.onPeerPunchReceived;
         int[] recvPunchCounter = new int[]{0};
         int[] recvAckCounter = new int[]{0};
         long startTime = System.currentTimeMillis();
         int socketsTried = targetPorts.size();
         Thread recvThread = new Thread(
            () -> {
               byte[] buf = new byte[64];
               DatagramPacket packet = new DatagramPacket(buf, buf.length);
               boolean peerPunchNotified = false;
               int debugCount = 0;

               while (this.punching.get() && !this.holeOpen.get()) {
                  try {
                     this.socket.receive(packet);
                     if (++debugCount <= 10) {
                        LOGGER.info(
                           "[UdpHolePuncher] Received #{}: from {}:{}, len={}, bytes=[{},{},{}]",
                           new Object[]{
                              debugCount,
                              packet.getAddress().getHostAddress(),
                              packet.getPort(),
                              packet.getLength(),
                              packet.getLength() > 0 ? buf[0] & 255 : -1,
                              packet.getLength() > 1 ? buf[1] & 255 : -1,
                              packet.getLength() > 2 ? buf[2] & 255 : -1
                           }
                        );
                     }

                     if (packet.getLength() >= 3 && buf[0] == MAGIC[0] && buf[1] == MAGIC[1] && this.acceptPacket(buf, packet)) {
                        byte type = buf[2];
                        boolean punchLive = !this.completed.get() || type == 1;
                        if (punchLive && !packet.getAddress().equals(this.remoteAddress)) {
                           LOGGER.info(
                              "[UdpHolePuncher] CGNAT multi-IP: accept from {}:{} (expected IP {})",
                              new Object[]{packet.getAddress().getHostAddress(), packet.getPort(), this.remoteAddress.getHostAddress()}
                           );
                           this.remoteAddress = packet.getAddress();
                           this.remotePort = packet.getPort();
                           if (!peerPunchNotified && peerPunchCb != null) {
                              peerPunchNotified = true;

                              try {
                                 peerPunchCb.accept(new InetSocketAddress(packet.getAddress(), packet.getPort()));
                              } catch (Exception var23) {
                              }
                           }
                        }

                        if (punchLive && packet.getPort() != this.remotePort) {
                           LOGGER.info(
                              "[UdpHolePuncher] Accept from {}:{} (expected port {})",
                              new Object[]{packet.getAddress().getHostAddress(), packet.getPort(), this.remotePort}
                           );
                           this.remotePort = packet.getPort();
                           if (!peerPunchNotified && peerPunchCb != null) {
                              peerPunchNotified = true;

                              try {
                                 peerPunchCb.accept(new InetSocketAddress(packet.getAddress(), packet.getPort()));
                              } catch (Exception var22) {
                              }
                           }
                        }

                        if (type == 1) {
                           recvPunchCounter[0]++;
                           this.remoteReceived.set(true);
                           this.sendControlTo((byte)2, packet.getAddress(), packet.getPort());
                           synchronized (completionLock) {
                              if ((this.localConfirmed.get() || this.remoteReceived.get()) && this.completed.compareAndSet(false, true)) {
                                 this.holeOpen.set(true);
                                 this.punching.set(false);
                                 this.socketTransferred = true;
                                 long elapsed = System.currentTimeMillis() - startTime;
                                 result.complete(PunchResult.success(this.socket, socketsTried, recvPunchCounter[0], recvAckCounter[0], 0, elapsed));
                              }
                           }
                        } else if (type == 2) {
                           recvAckCounter[0]++;
                           this.localConfirmed.set(true);
                           this.sendControlTo((byte)2, packet.getAddress(), packet.getPort());
                           synchronized (completionLock) {
                              if ((this.localConfirmed.get() || this.remoteReceived.get()) && this.completed.compareAndSet(false, true)) {
                                 this.holeOpen.set(true);
                                 this.punching.set(false);
                                 this.socketTransferred = true;
                                 long elapsed = System.currentTimeMillis() - startTime;
                                 result.complete(PunchResult.success(this.socket, socketsTried, recvPunchCounter[0], recvAckCounter[0], 0, elapsed));
                              }
                           }
                        }
                     }
                  } catch (SocketTimeoutException var26) {
                  } catch (IOException var271) {
                     IOException e = var271;
                     if (this.punching.get()) {
                        synchronized (completionLock) {
                           if (this.completed.compareAndSet(false, true)) {
                              this.punching.set(false);
                              result.completeExceptionally(e);
                           }
                        }
                     }

                     return;
                  }
               }
            },
            "VoxLink-PunchRecv"
         );
         recvThread.setDaemon(true);
         this.recvThreadsRef = Collections.singletonList(recvThread);
         recvThread.start();
         Thread sendThread = new Thread(
            () -> {
               int cyclesPerformed = 0;
               int maxTotalCycles = this.effectiveTimeoutMs() / this.effectiveSendInterval();
               long sendStartMs = System.currentTimeMillis();
               byte[] data = this.buildControl((byte)1);
               LOGGER.info(
                  "[UdpHolePuncher] Multi-port send thread start: target={}, port={}, local port={}",
                  new Object[]{this.remoteAddress.getHostAddress(), targetPorts, this.socket.getLocalPort()}
               );

               while (this.punching.get() && !this.holeOpen.get() && cyclesPerformed < maxTotalCycles) {
                  if (cyclesPerformed >= maxTotalCycles * 4 / 5 && !this.remoteReceived.get()) {
                     long elapsed = System.currentTimeMillis() - sendStartMs;
                     LOGGER.debug(
                        "[UdpHolePuncher] Multi-port no reply after {} cycles/{}ms, continue until timeout (peer may be offline)", cyclesPerformed, elapsed
                     );
                  }

                  for (int port : targetPorts) {
                     DatagramPacket pkt = new DatagramPacket(data, data.length, this.remoteAddress, port);
                     sendPkt(this.socket, pkt);
                  }

                  cyclesPerformed++;

                  try {
                     Thread.sleep((long)this.effectiveSendInterval());
                  } catch (InterruptedException var19) {
                     Thread.currentThread().interrupt();
                     break;
                  }
               }

               LOGGER.info(
                  "[UdpHolePuncher] Multi-port send thread end: cycles={}, holeOpen={}, punching={}",
                  new Object[]{cyclesPerformed, this.holeOpen.get(), this.punching.get()}
               );
               if (!this.holeOpen.get() && this.punching.get()) {
                  synchronized (completionLock) {
                     if (this.completed.compareAndSet(false, true)) {
                        this.punching.set(false);
                        long elapsed = System.currentTimeMillis() - startTime;
                        result.complete(this.failAndRecord(socketsTried, recvPunchCounter[0], recvAckCounter[0], elapsed, false));
                     }
                  }
               }
            },
            "VoxLink-PunchSend"
         );
         sendThread.setDaemon(true);
         this.sendThreadRef = sendThread;
         sendThread.start();
         ScheduledFuture<?> tf = scheduler().schedule(() -> {
            synchronized (completionLock) {
               if (this.completed.compareAndSet(false, true)) {
                  this.punching.set(false);
                  long elapsed = System.currentTimeMillis() - startTime;
                  result.complete(this.failAndRecord(socketsTried, recvPunchCounter[0], recvAckCounter[0], elapsed, false));
               }
            }
         }, (long)(this.effectiveTimeoutMs() + this.profile().send.extraWaitLongMs), TimeUnit.MILLISECONDS);
         this.timeoutFuture = tf;
         P2PBridge.registerPendingUdpTimeout(tf);
         return result;
      } else {
         return CompletableFuture.failedFuture(new IllegalArgumentException("punchMultiPort: empty target ports"));
      }
   }

   public CompletableFuture<PunchResult> punchWithPortPrediction(String remoteIp, int basePort, int portRange, boolean fixedRange) {
      this.punching.set(true);
      this.holeOpen.set(false);
      this.remoteReceived.set(false);
      this.localConfirmed.set(false);
      this.completed.set(false);
      LOGGER.info(
         "[UdpHolePuncher] punchWithPortPrediction start: target={}:{}, range={}, fixed={}, profile={}",
         new Object[]{remoteIp, basePort, portRange, fixedRange, this.profile().describeInstance()}
      );

      try {
         this.remoteAddress = InetAddress.getByName(remoteIp);
         this.remotePort = basePort;
      } catch (Exception var18) {
         return CompletableFuture.failedFuture(var18);
      }

      observeBlacklistedTarget(this.remoteAddress, basePort);
      CompletableFuture<PunchResult> result = new CompletableFuture<>();
      this.activeResult = result;
      Object completionLock = new Object();
      boolean portPrediction = portRange > 0;
      Consumer<InetSocketAddress> peerPunchCb = this.onPeerPunchReceived;
      int[] recvPunchCounter = new int[]{0};
      int[] recvAckCounter = new int[]{0};
      long startTime = System.currentTimeMillis();
      int socketsTried = 1;
      Thread recvThread = new Thread(
         () -> {
            byte[] buf = new byte[64];
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            boolean peerPunchNotified = false;
            int debugCount = 0;

            while (this.punching.get() && !this.holeOpen.get()) {
               try {
                  this.socket.receive(packet);
                  if (++debugCount <= 10) {
                     LOGGER.info(
                        "[UdpHolePuncher] Received #{}: from {}:{}, len={}, bytes=[{},{},{}]",
                        new Object[]{
                           debugCount,
                           packet.getAddress().getHostAddress(),
                           packet.getPort(),
                           packet.getLength(),
                           packet.getLength() > 0 ? buf[0] & 255 : -1,
                           packet.getLength() > 1 ? buf[1] & 255 : -1,
                           packet.getLength() > 2 ? buf[2] & 255 : -1
                        }
                     );
                  }

                  if (packet.getLength() >= 3 && buf[0] == MAGIC[0] && buf[1] == MAGIC[1] && this.acceptPacket(buf, packet)) {
                     byte type = buf[2];
                     if ((type == 1 || type == 2) && this.remoteAddress != null) {
                        boolean punchLive = !this.completed.get() || type == 1;
                        if (punchLive && packet.getPort() != this.remotePort) {
                           LOGGER.info(
                              "[UdpHolePuncher] Accept from {}:{} (expected port {})",
                              new Object[]{packet.getAddress().getHostAddress(), packet.getPort(), this.remotePort}
                           );
                           this.remotePort = packet.getPort();
                           if (!peerPunchNotified && peerPunchCb != null) {
                              peerPunchNotified = true;

                              try {
                                 peerPunchCb.accept(new InetSocketAddress(packet.getAddress(), packet.getPort()));
                              } catch (Exception var20) {
                              }
                           }
                        }
                     }

                     if (type == 1) {
                        recvPunchCounter[0]++;
                        this.remoteReceived.set(true);
                        this.sendControlTo((byte)2, packet.getAddress(), packet.getPort());
                        synchronized (completionLock) {
                           if ((this.localConfirmed.get() || this.remoteReceived.get()) && this.completed.compareAndSet(false, true)) {
                              this.holeOpen.set(true);
                              this.punching.set(false);
                              this.socketTransferred = true;
                              long elapsed = System.currentTimeMillis() - startTime;
                              result.complete(PunchResult.success(this.socket, 1, recvPunchCounter[0], recvAckCounter[0], 0, elapsed));
                           }
                        }
                     } else if (type == 2) {
                        recvAckCounter[0]++;
                        this.localConfirmed.set(true);
                        this.sendControlTo((byte)2, packet.getAddress(), packet.getPort());
                        synchronized (completionLock) {
                           if ((this.localConfirmed.get() || this.remoteReceived.get()) && this.completed.compareAndSet(false, true)) {
                              this.holeOpen.set(true);
                              this.punching.set(false);
                              this.socketTransferred = true;
                              long elapsed = System.currentTimeMillis() - startTime;
                              result.complete(PunchResult.success(this.socket, 1, recvPunchCounter[0], recvAckCounter[0], 0, elapsed));
                           }
                        }
                     }
                  }
               } catch (SocketTimeoutException var23) {
               } catch (IOException var241) {
                  IOException e = var241;
                  if (this.punching.get()) {
                     synchronized (completionLock) {
                        if (this.completed.compareAndSet(false, true)) {
                           this.punching.set(false);
                           result.completeExceptionally(e);
                        }
                     }
                  }

                  return;
               }
            }
         },
         "VoxLink-PunchRecv"
      );
      recvThread.setDaemon(true);
      this.recvThreadsRef = Collections.singletonList(recvThread);
      recvThread.start();
      Thread sendThread = new Thread(
         () -> {
            int cyclesPerformed = 0;
            int maxTotalCycles = this.effectiveTimeoutMs() / this.effectiveSendInterval();
            int debugSendCount = 0;
            long sendStartMs = System.currentTimeMillis();
            LOGGER.info(
               "[UdpHolePuncher] Send thread start: target={}, port={}, range={}, local port={}",
               new Object[]{this.remoteAddress != null ? this.remoteAddress.getHostAddress() : "null", this.remotePort, portRange, this.socket.getLocalPort()}
            );

            while (this.punching.get() && !this.holeOpen.get() && cyclesPerformed < maxTotalCycles) {
               if (portPrediction) {
                  int currentRange;
                  if (fixedRange) {
                     currentRange = portRange;
                  } else {
                     int rangeIdx = cyclesPerformed / this.profile().cyclesPerRange;
                     if (rangeIdx >= this.profile().progressiveRanges.length) {
                        rangeIdx = this.profile().progressiveRanges.length - 1;
                     }

                     currentRange = Math.min(this.profile().progressiveRanges[rangeIdx], portRange > 0 ? portRange : this.effectivePortRange());
                  }

                  if (debugSendCount < 5) {
                     LOGGER.info(
                        "[UdpHolePuncher] Send #{}: PUNCH to {}:{}±{} (cycle={}, fixed={}, local port={})",
                        new Object[]{
                           debugSendCount + 1,
                           this.remoteAddress.getHostAddress(),
                           basePort,
                           currentRange,
                           cyclesPerformed,
                           fixedRange,
                           this.socket.getLocalPort()
                        }
                     );
                  }

                  this.sendControlMultiPort((byte)1, basePort, currentRange, cyclesPerformed);
               } else {
                  if (debugSendCount < 5) {
                     LOGGER.info(
                        "[UdpHolePuncher] Send #{}: PUNCH to {}:{} (cycle={}, local port={})",
                        new Object[]{debugSendCount + 1, this.remoteAddress.getHostAddress(), this.remotePort, cyclesPerformed, this.socket.getLocalPort()}
                     );
                  }

                  this.sendControl((byte)1);
               }

               cyclesPerformed++;
               debugSendCount++;

               try {
                  Thread.sleep((long)this.effectiveSendInterval());
               } catch (InterruptedException var22) {
                  Thread.currentThread().interrupt();
                  break;
               }
            }

            LOGGER.info(
               "[UdpHolePuncher] Send thread end: cyclesPerformed={}, holeOpen={}, punching={}",
               new Object[]{cyclesPerformed, this.holeOpen.get(), this.punching.get()}
            );
            if (!this.holeOpen.get() && this.punching.get()) {
               synchronized (completionLock) {
                  if (this.completed.compareAndSet(false, true)) {
                     this.punching.set(false);
                     long elapsed = System.currentTimeMillis() - startTime;
                     PunchResult fr = this.failAndRecord(1, recvPunchCounter[0], recvAckCounter[0], elapsed, false);
                     result.complete(portPrediction ? fr.withPortPrediction() : fr);
                  }
               }
            }
         },
         "VoxLink-PunchSend"
      );
      sendThread.setDaemon(true);
      this.sendThreadRef = sendThread;
      sendThread.start();
      ScheduledFuture<?> tf = scheduler().schedule(() -> {
         if (this.punching.get()) {
            synchronized (completionLock) {
               if (this.completed.compareAndSet(false, true)) {
                  this.punching.set(false);
                  long elapsed = System.currentTimeMillis() - startTime;
                  PunchResult fr = this.failAndRecord(1, recvPunchCounter[0], recvAckCounter[0], elapsed, false);
                  result.complete(portPrediction ? fr.withPortPrediction() : fr);
               }
            }
         }
      }, (long)(this.effectiveTimeoutMs() + this.profile().send.extraWaitMs), TimeUnit.MILLISECONDS);
      this.timeoutFuture = tf;
      P2PBridge.registerPendingUdpTimeout(tf);
      return result;
   }

   public CompletableFuture<PunchResult> punchEasySymDual(String remoteIp, int remoteBasePort, StunProbe.NatType localNat, StunProbe.NatType remoteNat) {
      return this.punchEasySymDual(remoteIp, remoteBasePort, localNat, remoteNat, this.profile().easySymDualSocketCount);
   }

   public CompletableFuture<PunchResult> punchEasySymDual(
      String remoteIp, int remoteBasePort, StunProbe.NatType localNat, StunProbe.NatType remoteNat, int socketCount
   ) {
      int effectiveSocketCount = socketCount > 0 ? socketCount : this.profile().easySymDualSocketCount;
      PunchProfile.SymParams sym = this.profile().sym;
      LOGGER.info(
         "[UdpHolePuncher] EasySym mutual bomb: target={}:{}, sockets={}, window=+/-{}, round={}ms, budget={}ms, local={}, remote={}, profile={}",
         new Object[]{
            remoteIp,
            remoteBasePort,
            effectiveSocketCount,
            sym.easySymBombWindow,
            sym.easySymRoundIntervalMs,
            sym.easySymBombDurationMs,
            localNat.key,
            remoteNat.key,
            this.profile().describeInstance()
         }
      );
      List<UdpHolePuncher> punchers = new ArrayList<>();

      for (int i = 0; i < effectiveSocketCount; i++) {
         UdpHolePuncher p = new UdpHolePuncher();
         p.inheritAuthFrom(this);

         try {
            p.createSocket();
         } catch (SocketException var12) {
            LOGGER.warn("[UdpHolePuncher] EasySym socket#{} create failed: {}", i, var12.getMessage());
            continue;
         }

         punchers.add(p);
      }

      if (punchers.isEmpty()) {
         return CompletableFuture.failedFuture(new SocketException("EasySym对打: 无可用socket"));
      } else {
         PunchParams bomb = this.punchParams != null ? new PunchParams(this.punchParams) : PunchParams.fromProfile(this.profile());
         bomb.easySymBomb = true;
         bomb.bombWindow = sym.easySymBombWindow;
         bomb.bombRoundIntervalMs = sym.easySymRoundIntervalMs;
         bomb.bombDurationMs = sym.easySymBombDurationMs;
         UdpHolePuncher lead = punchers.get(0);
         lead.setPunchParams(bomb);
         this.socketGroup = punchers;
         CompletableFuture<PunchResult> wrapped = new CompletableFuture<>();
         lead.punchMultiSocket(remoteIp, remoteBasePort, punchers, new AtomicBoolean(false), 0).whenComplete((pr, ex) -> {
            for (UdpHolePuncher p : punchers) {
               try {
                  p.cancel();
               } catch (Exception var7x) {
               }
            }

            if (pr != null && pr.isSuccess()) {
               wrapped.complete(pr);
            } else if (ex != null) {
               wrapped.completeExceptionally(ex);
            } else if (pr != null) {
               wrapped.complete(pr.withPortPrediction());
            } else {
               wrapped.completeExceptionally(new IllegalStateException("punchEasySymDual: no result"));
            }
         });
         return wrapped;
      }
   }

   private void sendControl(byte type) {
      byte[] data = this.buildControl(type);
      DatagramPacket packet = new DatagramPacket(data, data.length, this.remoteAddress, this.remotePort);
      sendPkt(this.socket, packet);
   }

   private static boolean shouldLogPunchStartInfo(String remoteIp, int targetPort) {
      return shouldLogDedupInfo(remoteIp + ":" + targetPort, true);
   }

   private static boolean shouldLogMultiPortInfo(InetAddress addr, int centerPort) {
      return shouldLogDedupInfo(addr.getHostAddress() + ":" + centerPort, false);
   }

   private static boolean shouldLogDedupInfo(String key, boolean punchStart) {
      synchronized (PUNCH_LOG_DEDUP_LOCK) {
         long now = System.currentTimeMillis();
         String lastKey = punchStart ? punchStartLastInfoKey : multiPortLastInfoKey;
         long lastMs = punchStart ? punchStartLastInfoMs : multiPortLastInfoMs;
         if (key.equals(lastKey) && now - lastMs < 60000L) {
            return false;
         } else {
            if (punchStart) {
               punchStartLastInfoKey = key;
               punchStartLastInfoMs = now;
            } else {
               multiPortLastInfoKey = key;
               multiPortLastInfoMs = now;
            }

            return true;
         }
      }
   }

   private void sendControlMultiPort(byte type, int basePort, int portRange, int round) {
      byte[] data = this.buildControl(type);
      InetAddress addr = this.remoteAddress;
      if (addr != null) {
         int centerPort = this.remotePort;
         boolean useRandomScan = portRange > 20;
         List<Integer> portsToSend = new ArrayList<>();
         portsToSend.add(centerPort);
         Random rnd = new Random();
         if (!useRandomScan) {
            for (int offset = 1; offset <= portRange; offset++) {
               int portLow = centerPort - offset;
               int portHigh = centerPort + offset;
               if (portLow > 0) {
                  portsToSend.add(portLow);
               }

               if (portHigh <= 65535) {
                  portsToSend.add(portHigh);
               }
            }
         } else {
            int lowBound = Math.max(1, centerPort - portRange);
            int highBound = Math.min(65535, centerPort + portRange);
            int rangeSize = highBound - lowBound + 1;
            int maxRandom = Math.min(this.profile().send.sweepWindowSize, rangeSize - 1);
            Set<Integer> chosen = new HashSet<>();
            chosen.add(centerPort);

            while (chosen.size() < maxRandom + 1) {
               chosen.add(lowBound + rnd.nextInt(rangeSize));
            }

            portsToSend.addAll(chosen);
            Collections.shuffle(portsToSend.subList(1, portsToSend.size()), rnd);
         }

         if (shouldLogMultiPortInfo(addr, centerPort)) {
            LOGGER.info(
               "[UdpHolePuncher] sendControlMultiPort: send to {} ports (x{} rounds x{} pass, round={}): {} (center={}, range=+/-{}, random={}, local port={})",
               new Object[]{
                  portsToSend.size(),
                  this.effectiveMinRounds(),
                  this.effectiveMinPass(),
                  round,
                  portsToSend.subList(0, Math.min(10, portsToSend.size())),
                  centerPort,
                  portRange,
                  useRandomScan,
                  this.socket.getLocalPort()
               }
            );
         } else {
            LOGGER.debug("[UdpHolePuncher] sendControlMultiPort: sent multi-port control to {} ports (cycle={})", portsToSend.size(), round);
         }

         for (int roundPass = 0; roundPass < this.effectiveMinRounds(); roundPass++) {
            for (int i = 0; i < portsToSend.size(); i++) {
               int port = portsToSend.get(i);

               for (int r = 0; r < this.effectiveMinPass(); r++) {
                  DatagramPacket packet = new DatagramPacket(data, data.length, addr, port);
                  sendPkt(this.socket, packet);
               }

               if (i < portsToSend.size() - 1) {
                  try {
                     Thread.sleep((long)this.profile().send.sleepShortMs);
                  } catch (InterruptedException var17) {
                     Thread.currentThread().interrupt();
                     return;
                  }
               }
            }

            if (roundPass < 2) {
               try {
                  Thread.sleep((long)this.profile().send.sleepLongMs);
               } catch (InterruptedException var16) {
                  Thread.currentThread().interrupt();
                  return;
               }
            }
         }
      }
   }

   public void cancel() {
      if (this.punching.compareAndSet(true, false)) {
         CompletableFuture<PunchResult> r = this.activeResult;
         if (r != null && this.completed.compareAndSet(false, true)) {
            r.completeExceptionally(new CancellationException("punch cancelled"));
         }
      }

      ScheduledFuture<?> tf = this.timeoutFuture;
      if (tf != null) {
         tf.cancel(false);
         this.timeoutFuture = null;
      }

      List<UdpHolePuncher> group = this.socketGroup;
      if (group != null) {
         for (UdpHolePuncher sp : group) {
            DatagramSocket s = sp.getSocket();
            if (s != null && !s.isClosed() && !sp.socketTransferred) {
               s.close();
            }
         }

         this.socketGroup = null;
      }

      if (this.socket != null && !this.socket.isClosed() && !this.socketTransferred) {
         this.socket.close();
      }

      List<Thread> rts = this.recvThreadsRef;
      if (rts != null) {
         for (Thread t : rts) {
            if (t != null) {
               t.interrupt();
            }
         }
      }

      if (this.sendThreadRef != null) {
         this.sendThreadRef.interrupt();
      }
   }

   public void stopPunch() {
      if (this.punching.compareAndSet(true, false)) {
         CompletableFuture<PunchResult> r = this.activeResult;
         if (r != null && this.completed.compareAndSet(false, true)) {
            r.completeExceptionally(new CancellationException("punch stopped"));
         }
      }

      ScheduledFuture<?> tf = this.timeoutFuture;
      if (tf != null) {
         tf.cancel(false);
         this.timeoutFuture = null;
      }

      List<Thread> rts = this.recvThreadsRef;
      if (rts != null) {
         for (Thread t : rts) {
            if (t != null) {
               t.interrupt();
            }
         }
      }

      if (this.sendThreadRef != null) {
         this.sendThreadRef.interrupt();
      }

      this.holeOpen.set(false);
      this.remoteReceived.set(false);
      this.localConfirmed.set(false);
   }

   public synchronized void updateTarget(String newIp, int newPort) {
      try {
         this.remoteAddress = InetAddress.getByName(newIp);
         this.remotePort = newPort;
         LOGGER.debug("[UdpHolePuncher] Target updated to {}:{}", newIp, newPort);
      } catch (Exception var4) {
         LOGGER.warn("[UdpHolePuncher] Target update failed: {}", var4.getMessage());
      }
   }

   public void setOnPeerPunchReceived(Consumer<InetSocketAddress> callback) {
      this.onPeerPunchReceived = callback;
   }

   public boolean isHoleOpen() {
      return this.holeOpen.get();
   }

   public InetSocketAddress getActualRemoteAddress() {
      InetAddress addr = this.remoteAddress;
      int port = this.remotePort;
      return addr != null && port > 0 ? new InetSocketAddress(addr, port) : null;
   }

   public void waitForRecvThreadExit() {
      List<Thread> rts = this.recvThreadsRef;
      if (rts != null) {
         for (Thread t : rts) {
            if (t != null && t.isAlive()) {
               try {
                  t.join(2000L);
               } catch (InterruptedException var6) {
                  Thread.currentThread().interrupt();
               }
            }
         }
      }

      Thread s = this.sendThreadRef;
      if (s != null && s.isAlive()) {
         try {
            s.join(2000L);
         } catch (InterruptedException var5) {
            Thread.currentThread().interrupt();
         }
      }
   }

   public boolean waitPunchStop(long timeoutMs) {
      long deadline = System.currentTimeMillis() + timeoutMs;
      if (this.punching.get()) {
         this.stopPunch();
      }

      while (System.currentTimeMillis() < deadline && this.punching.get()) {
         try {
            Thread.sleep(20L);
         } catch (InterruptedException var10) {
            Thread.currentThread().interrupt();
            return false;
         }
      }

      if (this.punching.get()) {
         return false;
      } else {
         while (System.currentTimeMillis() < deadline) {
            boolean alive = false;
            List<Thread> rts = this.recvThreadsRef;
            if (rts != null) {
               for (Thread t : rts) {
                  if (t != null && t.isAlive()) {
                     alive = true;
                  }
               }
            }

            Thread st = this.sendThreadRef;
            if (st != null && st.isAlive()) {
               alive = true;
            }

            if (!alive) {
               return true;
            }

            try {
               Thread.sleep(20L);
            } catch (InterruptedException var9) {
               Thread.currentThread().interrupt();
               return false;
            }
         }

         return false;
      }
   }

   public void close() {
      if (this.punching.compareAndSet(true, false)) {
         CompletableFuture<PunchResult> r = this.activeResult;
         if (r != null && this.completed.compareAndSet(false, true)) {
            r.completeExceptionally(new CancellationException("punch closed"));
         }
      }

      ScheduledFuture<?> tf = this.timeoutFuture;
      if (tf != null) {
         tf.cancel(false);
         this.timeoutFuture = null;
      }

      List<UdpHolePuncher> group = this.socketGroup;
      if (group != null) {
         for (UdpHolePuncher sp : group) {
            DatagramSocket s = sp.getSocket();
            if (s != null && !s.isClosed() && !sp.socketTransferred) {
               s.close();
            }
         }

         this.socketGroup = null;
      }

      if (this.socket != null && !this.socket.isClosed() && !this.socketTransferred) {
         this.socket.close();
      }

      List<Thread> rts = this.recvThreadsRef;
      if (rts != null) {
         for (Thread t : rts) {
            if (t != null) {
               t.interrupt();
            }
         }
      }

      if (this.sendThreadRef != null) {
         this.sendThreadRef.interrupt();
      }
   }

   private CompletableFuture<PunchResult> punchMultiSocketLegacy(String remoteIp, int targetPort, List<UdpHolePuncher> socketGroup, AtomicBoolean wonFlag) {
      CompletableFuture<PunchResult> result = new CompletableFuture<>();
      this.activeResult = result;
      this.socketGroup = socketGroup;
      Object completionLock = new Object();
      Consumer<InetSocketAddress> peerPunchCb = this.onPeerPunchReceived;
      int[] recvPunchCounter = new int[]{0};
      int[] recvAckCounter = new int[]{0};
      long startTime = System.currentTimeMillis();
      int socketsTried = socketGroup.size();
      byte[] data = this.buildControl((byte)1);
      int maxTotalCycles = this.effectiveTimeoutMs() / this.effectiveSendInterval();
      if (shouldLogPunchStartInfo(remoteIp, targetPort)) {
         LOGGER.info(
            "[UdpHolePuncher] Multi-socket send start (Legacy): target={}:{}, sockets={}, mode=classic(fallback), profile={}",
            new Object[]{remoteIp, targetPort, socketGroup.size(), this.profile().describeInstance()}
         );
      } else {
         LOGGER.debug(
            "[UdpHolePuncher] Multi-socket send start (Legacy): target={}:{}, sockets={}, mode=classic(fallback), profile={}",
            new Object[]{remoteIp, targetPort, socketGroup.size(), this.profile().describeInstance()}
         );
      }

      List<Thread> recvThreads = new ArrayList<>();

      for (int si = 0; si < socketGroup.size(); si++) {
         UdpHolePuncher sp = socketGroup.get(si);
         int sIdx = si;
         DatagramSocket ssock = sp.getSocket();
         if (ssock != null && !ssock.isClosed()) {
            try {
               ssock.setSoTimeout(this.profile().send.socketTimeoutMs);
            } catch (Exception var21) {
            }

            Thread rt = new Thread(() -> {
               byte[] buf = new byte[64];
               DatagramPacket packet = new DatagramPacket(buf, buf.length);
               boolean peerPunchNotified = false;

               while (this.punching.get() && !this.holeOpen.get()) {
                  try {
                     ssock.receive(packet);
                     if (packet.getLength() >= 3 && buf[0] == MAGIC[0] && buf[1] == MAGIC[1] && packet.getAddress().equals(this.remoteAddress)) {
                        byte type = buf[2];
                        if (type == 1) {
                           recvPunchCounter[0]++;
                           this.remoteReceived.set(true);
                           sp.sendControlTo((byte)2, packet.getAddress(), packet.getPort());
                           synchronized (completionLock) {
                              if (this.localConfirmed.get() && wonFlag.compareAndSet(false, true) && this.completed.compareAndSet(false, true)) {
                                 this.holeOpen.set(true);
                                 this.punching.set(false);
                                 sp.socketTransferred = true;
                                 this.remoteAddress = packet.getAddress();
                                 this.remotePort = packet.getPort();
                                 LOGGER.info("[UdpHolePuncher] socket#{} received PUNCH, punch success (Legacy)", sIdx);
                                 long elapsed = System.currentTimeMillis() - startTime;
                                 result.complete(PunchResult.success(ssock, socketsTried, recvPunchCounter[0], recvAckCounter[0], 0, elapsed));
                              }
                           }
                        } else if (type == 2) {
                           recvAckCounter[0]++;
                           this.localConfirmed.set(true);
                           sp.sendControlTo((byte)2, packet.getAddress(), packet.getPort());
                           synchronized (completionLock) {
                              if (this.remoteReceived.get() && wonFlag.compareAndSet(false, true) && this.completed.compareAndSet(false, true)) {
                                 this.holeOpen.set(true);
                                 this.punching.set(false);
                                 sp.socketTransferred = true;
                                 this.remoteAddress = packet.getAddress();
                                 this.remotePort = packet.getPort();
                                 LOGGER.info("[UdpHolePuncher] socket#{} received ACK, punch success (Legacy)", sIdx);
                                 long elapsed = System.currentTimeMillis() - startTime;
                                 result.complete(PunchResult.success(ssock, socketsTried, recvPunchCounter[0], recvAckCounter[0], 0, elapsed));
                              }
                           }
                        }

                        if (!peerPunchNotified && peerPunchCb != null) {
                           peerPunchNotified = true;

                           try {
                              peerPunchCb.accept(new InetSocketAddress(packet.getAddress(), packet.getPort()));
                           } catch (Exception var22x) {
                           }
                        }
                     }
                  } catch (SocketTimeoutException var25) {
                  } catch (IOException var26) {
                     if (this.punching.get()) {
                        return;
                     }
                  }
               }
            }, "VoxLink-PunchRecv-Legacy-" + si);
            rt.setDaemon(true);
            recvThreads.add(rt);
            this.recvThreadsRef = recvThreads;
            rt.start();
         }
      }

      this.recvThreadsRef = recvThreads.isEmpty() ? null : recvThreads;
      boolean skipFirewallCheck = socketGroup.size() <= 1 || this.skipFirewallDetection;
      Thread sendThread = new Thread(() -> {
         int cycles = 0;
         long sendStartMs = System.currentTimeMillis();

         while (this.punching.get() && !this.holeOpen.get() && cycles < maxTotalCycles) {
            if (!skipFirewallCheck && cycles >= this.profile().firewallDetectCycles && !this.remoteReceived.get()) {
               long elapsed = System.currentTimeMillis() - sendStartMs;
               LOGGER.warn("[UdpHolePuncher] Multi-socket firewall check (Legacy): sent {} cycles/{}ms no reply, UDP blocked", cycles, elapsed);
               synchronized (completionLock) {
                  if (this.completed.compareAndSet(false, true)) {
                     this.punching.set(false);
                     result.complete(this.failAndRecord(socketsTried, recvPunchCounter[0], recvAckCounter[0], elapsed, true));
                  }

                  return;
               }
            }

            for (UdpHolePuncher sp : socketGroup) {
               DatagramSocket s = sp.getSocket();
               if (s != null && !s.isClosed()) {
                  for (int r = 0; r < 3; r++) {
                     DatagramPacket pkt = new DatagramPacket(data, data.length, this.remoteAddress, this.remotePort);
                     sendPkt(s, pkt);
                  }
               }
            }

            cycles++;

            try {
               Thread.sleep((long)this.effectiveSendInterval());
            } catch (InterruptedException var23x) {
               Thread.currentThread().interrupt();
               break;
            }
         }

         if (!this.holeOpen.get() && this.punching.get()) {
            synchronized (completionLock) {
               if (this.completed.compareAndSet(false, true)) {
                  this.punching.set(false);
                  long elapsed = System.currentTimeMillis() - startTime;
                  result.complete(this.failAndRecord(socketsTried, recvPunchCounter[0], recvAckCounter[0], elapsed, false));
               }
            }
         }
      }, "VoxLink-PunchSend-Legacy");
      sendThread.setDaemon(true);
      this.sendThreadRef = sendThread;
      sendThread.start();
      ScheduledFuture<?> tf = scheduler().schedule(() -> {
         if (this.punching.get()) {
            synchronized (completionLock) {
               if (this.completed.compareAndSet(false, true)) {
                  this.punching.set(false);
                  long elapsed = System.currentTimeMillis() - startTime;
                  result.complete(this.failAndRecord(socketsTried, recvPunchCounter[0], recvAckCounter[0], elapsed, false));
               }
            }
         }
      }, (long)(this.effectiveTimeoutMs() + this.profile().send.extraWaitMs), TimeUnit.MILLISECONDS);
      this.timeoutFuture = tf;
      P2PBridge.registerPendingUdpTimeout(tf);
      return result;
   }

   private static final class PpsLimiter {
      private final int maxPps;
      private final long startNanos = System.nanoTime();
      private long sent;

      PpsLimiter(int maxPps) {
         this.maxPps = Math.max(1, maxPps);
      }

      void beforeSend() {
         this.sent++;
         long allowed = (System.nanoTime() - this.startNanos) * (long)this.maxPps / 1000000000L;
         if (this.sent > allowed) {
            long sleepNs = (this.sent - allowed) * 1000000000L / (long)this.maxPps;

            try {
               Thread.sleep(sleepNs / 1000000L, (int)(sleepNs % 1000000L));
            } catch (InterruptedException var6) {
               Thread.currentThread().interrupt();
            }
         }
      }
   }
}
