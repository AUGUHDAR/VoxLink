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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UdpHolePuncher {
   private static final Logger LOGGER = LoggerFactory.getLogger("voxlink-punch");
   private static final byte[] MAGIC = new byte[]{86, 76};
   private static final byte TYPE_PUNCH = 1;
   private static final byte TYPE_PUNCH_ACK = 2;
   private static volatile ScheduledExecutorService PUNCH_TIMEOUT_SCHEDULER = createScheduler();
   private DatagramSocket socket;
   private final int sessionNonce = ThreadLocalRandom.current().nextInt(65536);
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

   public void setProfile(PunchProfile p) {
      this.profile = p;
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
      } catch (SocketException e) {
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
      this.punching.set(true);
      this.holeOpen.set(false);
      this.remoteReceived.set(false);
      this.localConfirmed.set(false);
      this.completed.set(false);

      try {
         this.remoteAddress = InetAddress.getByName(remoteIp);
         this.remotePort = targetPort;
      } catch (Exception e) {
         return CompletableFuture.failedFuture(e);
      }

      CompletableFuture<PunchResult> result = new CompletableFuture<>();
      this.activeResult = result;
      this.socketGroup = socketGroup;
      Object completionLock = new Object();
      Consumer<InetSocketAddress> peerPunchCb = this.onPeerPunchReceived;
      int[] recvPunchCounter = new int[]{0};
      int[] recvAckCounter = new int[]{0};
      long startTime = System.currentTimeMillis();
      int socketsTried = socketGroup.size();
      byte[] data = new byte[]{MAGIC[0], MAGIC[1], 1, (byte)(this.sessionNonce >> 8), (byte)this.sessionNonce};
      int maxTotalCycles = this.effectiveTimeoutMs() / this.effectiveSendInterval();
      LOGGER.info(
         "[UdpHolePuncher] Multi-socket send start: target={}:{}, sockets={}, interval={}ms, profile={}",
         new Object[]{remoteIp, targetPort, socketGroup.size(), this.effectiveSendInterval(), this.profile().describeInstance()}
      );
      List<DatagramChannel> channels = new ArrayList<>();
      Map<DatagramChannel, UdpHolePuncher> channelToPuncher = new HashMap<>();
      Map<DatagramChannel, Integer> channelToIndex = new HashMap<>();
      Selector selector = null;

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
                  } catch (Exception var28) {
                  }

                  ch = DatagramChannel.open();
                  ch.configureBlocking(false);
                  ch.bind(new InetSocketAddress(localAddr, localPort));
                  sp.replaceSocket(ch.socket());
               } else {
                  ch.configureBlocking(false);
               }

               ch.register(selector, 1);
               channels.add(ch);
               channelToPuncher.put(ch, sp);
               channelToIndex.put(ch, si);
            }
         }
      } catch (IOException e) {
         LOGGER.warn("[UdpHolePuncher] NIO Selector init failed, fallback to multi-thread mode: {}", e.getMessage());
         return this.punchMultiSocketLegacy(remoteIp, targetPort, socketGroup, wonFlag);
      }

      Selector finalSelector = selector;
      Map<DatagramChannel, UdpHolePuncher> finalChannelToPuncher = channelToPuncher;
      Map<DatagramChannel, Integer> finalChannelToIndex = channelToIndex;
      int finalNonce = this.sessionNonce;
      Thread recvThread = new Thread(() -> {
         ByteBuffer buf = ByteBuffer.allocate(64);
         boolean peerPunchNotified = false;

         try {
            while (this.punching.get() && !this.holeOpen.get()) {
               int ready = finalSelector.select(500L);
               if (ready != 0) {
                  Iterator<SelectionKey> it = finalSelector.selectedKeys().iterator();

                  while (it.hasNext()) {
                     SelectionKey key = it.next();
                     it.remove();
                     if (key.isReadable()) {
                        DatagramChannel ch = (DatagramChannel)key.channel();
                        buf.clear();

                        InetSocketAddress from;
                        try {
                           from = (InetSocketAddress)ch.receive(buf);
                        } catch (IOException e) {
                           continue;
                        }

                        if (from != null && buf.position() >= 3) {
                           buf.flip();
                           byte b0 = buf.get(0);
                           byte b1 = buf.get(1);
                           byte b2 = buf.get(2);
                           if (b0 == MAGIC[0] && b1 == MAGIC[1] && from.getAddress().equals(this.remoteAddress)) {
                              UdpHolePuncher sp = finalChannelToPuncher.get(ch);
                              int sIdx = finalChannelToIndex.get(ch);
                              if (b2 == 1) {
                                 recvPunchCounter[0]++;
                                 this.remoteReceived.set(true);
                                 sp.sendControlTo((byte)2, from.getAddress(), from.getPort());
                                 synchronized (completionLock) {
                                    if (this.localConfirmed.get() && wonFlag.compareAndSet(false, true) && this.completed.compareAndSet(false, true)) {
                                       this.holeOpen.set(true);
                                       this.punching.set(false);
                                       sp.socketTransferred = true;
                                       this.remoteAddress = from.getAddress();
                                       this.remotePort = from.getPort();
                                       LOGGER.info("[UdpHolePuncher] socket#{} received PUNCH, punch success (NIO)", sIdx);
                                       long elapsed = System.currentTimeMillis() - startTime;
                                       result.complete(PunchResult.success(sp.getSocket(), socketsTried, recvPunchCounter[0], recvAckCounter[0], 0, elapsed));
                                    }
                                 }
                              } else if (b2 == 2) {
                                 recvAckCounter[0]++;
                                 this.localConfirmed.set(true);
                                 sp.sendControlTo((byte)2, from.getAddress(), from.getPort());
                                 synchronized (completionLock) {
                                    if (this.remoteReceived.get() && wonFlag.compareAndSet(false, true) && this.completed.compareAndSet(false, true)) {
                                       this.holeOpen.set(true);
                                       this.punching.set(false);
                                       sp.socketTransferred = true;
                                       this.remoteAddress = from.getAddress();
                                       this.remotePort = from.getPort();
                                       LOGGER.info("[UdpHolePuncher] socket#{} received ACK, punch success (NIO)", sIdx);
                                       long elapsed = System.currentTimeMillis() - startTime;
                                       result.complete(PunchResult.success(sp.getSocket(), socketsTried, recvPunchCounter[0], recvAckCounter[0], 0, elapsed));
                                    }
                                 }
                              }

                              if (!peerPunchNotified && peerPunchCb != null) {
                                 peerPunchNotified = true;

                                 try {
                                    peerPunchCb.accept(from);
                                 } catch (Exception var42) {
                                 }
                              }
                           }
                        }
                     }
                  }
               }
            }
         } catch (IOException e) {
            LOGGER.debug("[UdpHolePuncher] Selector receive exception: {}", e.getMessage());
         } finally {
            try {
               finalSelector.close();
            } catch (IOException var41) {
            }
         }
      }, "VoxLink-PunchRecvNIO");
      recvThread.setDaemon(true);
      this.recvThreadsRef = Collections.singletonList(recvThread);
      recvThread.start();
      boolean skipFirewallCheck = socketGroup.size() <= 1 || this.skipFirewallDetection;
      Thread sendThread = new Thread(() -> {
         int cycles = 0;
         long sendStartMs = System.currentTimeMillis();

         while (this.punching.get() && !this.holeOpen.get() && cycles < maxTotalCycles) {
            if (!skipFirewallCheck && cycles >= this.profile().firewallDetectCycles && !this.remoteReceived.get()) {
               long elapsed = System.currentTimeMillis() - sendStartMs;
               LOGGER.warn("[UdpHolePuncher] Multi-socket firewall check: sent {} cycles/{}ms no reply, UDP blocked, abort early", cycles, elapsed);
               synchronized (completionLock) {
                  if (this.completed.compareAndSet(false, true)) {
                     this.punching.set(false);
                     result.complete(PunchResult.failure(socketsTried, recvPunchCounter[0], recvAckCounter[0], 0, elapsed, true));
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
               Thread.sleep(this.effectiveSendInterval());
            } catch (InterruptedException e) {
               Thread.currentThread().interrupt();
               break;
            }
         }

         LOGGER.info("[UdpHolePuncher] Multi-socket send end: cycles={}, holeOpen={}", cycles, this.holeOpen.get());
         if (!this.holeOpen.get() && this.punching.get()) {
            synchronized (completionLock) {
               if (this.completed.compareAndSet(false, true)) {
                  this.punching.set(false);
                  long elapsed = System.currentTimeMillis() - startTime;
                  result.complete(PunchResult.failure(socketsTried, recvPunchCounter[0], recvAckCounter[0], 0, elapsed, false));
               }
            }
         }
      }, "VoxLink-PunchSend");
      sendThread.setDaemon(true);
      this.sendThreadRef = sendThread;
      sendThread.start();
      ScheduledFuture<?> tf = scheduler().schedule(() -> {
         if (this.punching.get()) {
            synchronized (completionLock) {
               if (this.completed.compareAndSet(false, true)) {
                  this.punching.set(false);
                  long elapsed = System.currentTimeMillis() - startTime;
                  result.complete(PunchResult.failure(socketsTried, recvPunchCounter[0], recvAckCounter[0], 0, elapsed, false));
               }
            }
         }
      }, this.effectiveTimeoutMs() + this.profile().send.extraWaitMs, TimeUnit.MILLISECONDS);
      this.timeoutFuture = tf;
      P2PBridge.registerPendingUdpTimeout(tf);
      return result;
   }

   private void sendControlTo(byte type, InetAddress addr, int port) {
      byte[] d = new byte[]{MAGIC[0], MAGIC[1], type, (byte)(this.sessionNonce >> 8), (byte)this.sessionNonce};
      DatagramPacket packet = new DatagramPacket(d, d.length, addr, port);
      sendPkt(this.socket, packet);
   }

   private static void sendPkt(DatagramSocket s, DatagramPacket pkt) {
      try {
         DatagramChannel ch = s.getChannel();
         if (ch != null) {
            ByteBuffer bb = ByteBuffer.wrap(pkt.getData(), 0, pkt.getLength());
            ch.send(bb, pkt.getSocketAddress());
         } else {
            s.send(pkt);
         }
      } catch (IOException | RuntimeException var4) {
      }
   }

   private boolean acceptPacket(byte[] buf, DatagramPacket packet) {
      InetAddress exp = this.remoteAddress;
      return exp != null && exp.equals(packet.getAddress());
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
         } catch (Exception e) {
            this.punching.set(false);
            return CompletableFuture.failedFuture(e);
         }

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
                              packet.getLength() > 0 ? buf[0] & 0xFF : -1,
                              packet.getLength() > 1 ? buf[1] & 0xFF : -1,
                              packet.getLength() > 2 ? buf[2] & 0xFF : -1
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
                              } catch (Exception var25) {
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
                              } catch (Exception var24) {
                              }
                           }
                        }

                        if (type == 1) {
                           recvPunchCounter[0]++;
                           this.remoteReceived.set(true);
                           this.sendControlTo((byte)2, packet.getAddress(), packet.getPort());
                           synchronized (completionLock) {
                              if (this.localConfirmed.get() && this.completed.compareAndSet(false, true)) {
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
                              if (this.remoteReceived.get() && this.completed.compareAndSet(false, true)) {
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
                  } catch (IOException var27) {
                     IOException e = var27;
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
               byte[] data = new byte[]{MAGIC[0], MAGIC[1], 1, (byte)(this.sessionNonce >> 8), (byte)this.sessionNonce};
               LOGGER.info(
                  "[UdpHolePuncher] Multi-port send thread start: target={}, port={}, local port={}",
                  new Object[]{this.remoteAddress.getHostAddress(), targetPorts, this.socket.getLocalPort()}
               );

               while (this.punching.get() && !this.holeOpen.get() && cyclesPerformed < maxTotalCycles) {
                  if (cyclesPerformed >= maxTotalCycles * 4 / 5 && !this.remoteReceived.get()) {
                     long elapsed = System.currentTimeMillis() - sendStartMs;
                     LOGGER.debug("[UdpHolePuncher] Multi-port no reply after {} cycles/{}ms, continue until timeout (peer may be offline)", cyclesPerformed, elapsed);
                  }

                  for (int port : targetPorts) {
                     DatagramPacket pkt = new DatagramPacket(data, data.length, this.remoteAddress, port);
                     sendPkt(this.socket, pkt);
                  }

                  cyclesPerformed++;

                  try {
                     Thread.sleep(this.effectiveSendInterval());
                  } catch (InterruptedException e) {
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
                        result.complete(PunchResult.failure(socketsTried, recvPunchCounter[0], recvAckCounter[0], 0, elapsed, false));
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
                  result.complete(PunchResult.failure(socketsTried, recvPunchCounter[0], recvAckCounter[0], 0, elapsed, false));
               }
            }
         }, this.effectiveTimeoutMs() + this.profile().send.extraWaitLongMs, TimeUnit.MILLISECONDS);
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
      } catch (Exception e) {
         return CompletableFuture.failedFuture(e);
      }

      CompletableFuture<PunchResult> result = new CompletableFuture<>();
      this.activeResult = result;
      Object completionLock = new Object();
      boolean portPrediction = portRange > 0;
      boolean useFixedRange = fixedRange;
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
                           packet.getLength() > 0 ? buf[0] & 0xFF : -1,
                           packet.getLength() > 1 ? buf[1] & 0xFF : -1,
                           packet.getLength() > 2 ? buf[2] & 0xFF : -1
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
                              } catch (Exception var22) {
                              }
                           }
                        }
                     }

                     if (type == 1) {
                        recvPunchCounter[0]++;
                        this.remoteReceived.set(true);
                        this.sendControlTo((byte)2, packet.getAddress(), packet.getPort());
                        synchronized (completionLock) {
                           if (this.localConfirmed.get() && this.completed.compareAndSet(false, true)) {
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
                           if (this.remoteReceived.get() && this.completed.compareAndSet(false, true)) {
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
               } catch (IOException var24) {
                  IOException e = var24;
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
                  int currentRange = portRange > 0 ? portRange : this.effectivePortRange();
                  if (cyclesPerformed > 0) {
                     currentRange = Math.min(currentRange, 20);
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
                           useFixedRange,
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
                  Thread.sleep(this.effectiveSendInterval());
               } catch (InterruptedException e) {
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
                     PunchResult fr = PunchResult.failure(1, recvPunchCounter[0], recvAckCounter[0], 0, elapsed, false);
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
                  PunchResult fr = PunchResult.failure(1, recvPunchCounter[0], recvAckCounter[0], 0, elapsed, false);
                  result.complete(portPrediction ? fr.withPortPrediction() : fr);
               }
            }
         }
      }, this.effectiveTimeoutMs() + this.profile().send.extraWaitMs, TimeUnit.MILLISECONDS);
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
      LOGGER.info(
         "[UdpHolePuncher] EasySym mutual punch start: target={}:{}, sockets={}, range=+/-{}, local={}, remote={}, profile={}",
         new Object[]{
            remoteIp, remoteBasePort, effectiveSocketCount, this.profile().easySymDualPortRange, localNat.key, remoteNat.key, this.profile().describeInstance()
         }
      );
      List<UdpHolePuncher> punchers = new ArrayList<>();

      for (int i = 0; i < effectiveSocketCount; i++) {
         UdpHolePuncher p = new UdpHolePuncher();

         try {
            p.createSocket();
         } catch (SocketException e) {
            LOGGER.warn("[UdpHolePuncher] EasySym socket#{} create failed: {}", i, e.getMessage());
            continue;
         }

         punchers.add(p);
      }

      if (punchers.isEmpty()) {
         return CompletableFuture.failedFuture(new SocketException("EasySym对打: 无可用socket"));
      }

      List<UdpHolePuncher> punchersFinal = punchers;
      List<CompletableFuture<PunchResult>> futures = new ArrayList<>();

      for (UdpHolePuncher p : punchers) {
         futures.add(p.punchWithPortPrediction(remoteIp, remoteBasePort, this.profile().easySymDualPortRange, true));
      }

      CompletableFuture<PunchResult> result = new CompletableFuture<>();
      AtomicInteger remaining = new AtomicInteger(futures.size());
      AtomicInteger recvPunchSum = new AtomicInteger(0);
      AtomicInteger recvAckSum = new AtomicInteger(0);
      long startTime = System.currentTimeMillis();
      int socketsTried = punchersFinal.size();

      for (int i = 0; i < futures.size(); i++) {
         int idx = i;
         futures.get(i).whenComplete((pr, ex) -> {
            if (pr != null && pr.isSuccess()) {
               if (result.complete(pr)) {
                  LOGGER.info("[UdpHolePuncher] EasySym socket#{} hit, cancel others", idx);

                  for (int j = 0; j < punchersFinal.size(); j++) {
                     if (j != idx) {
                        punchersFinal.get(j).cancel();
                     }
                  }
               } else {
                  try {
                     pr.getSuccessSocket().close();
                  } catch (Exception var15) {
                  }
               }
            } else {
               if (pr != null) {
                  recvPunchSum.addAndGet(pr.socketsReceivedPunch);
                  recvAckSum.addAndGet(pr.socketsReceivedAck);
               }

               if (remaining.decrementAndGet() == 0 && !result.isDone()) {
                  for (UdpHolePuncher p : punchersFinal) {
                     try {
                        p.close();
                     } catch (Exception var14x) {
                     }
                  }

                  if (ex != null) {
                     result.completeExceptionally(ex);
                  } else {
                     long elapsed = System.currentTimeMillis() - startTime;
                     result.complete(PunchResult.failure(socketsTried, recvPunchSum.get(), recvAckSum.get(), 0, elapsed, false).withPortPrediction());
                  }
               }
            }
         });
      }

      return result;
   }

   private void sendControl(byte type) {
      byte[] data = new byte[]{MAGIC[0], MAGIC[1], type, (byte)(this.sessionNonce >> 8), (byte)this.sessionNonce};
      DatagramPacket packet = new DatagramPacket(data, data.length, this.remoteAddress, this.remotePort);
      sendPkt(this.socket, packet);
   }

   private void sendControlMultiPort(byte type, int basePort, int portRange, int round) {
      byte[] data = new byte[]{MAGIC[0], MAGIC[1], type, (byte)(this.sessionNonce >> 8), (byte)this.sessionNonce};
      InetAddress addr = this.remoteAddress;
      if (addr != null) {
         int centerPort = this.remotePort;
         boolean useRandomScan = portRange > 20;
         List<Integer> portsToSend = new ArrayList<>();
         portsToSend.add(centerPort);
         Random rnd = new Random();
         if (useRandomScan) {
            int windowSize = this.profile().send.jitterBaseMs + rnd.nextInt(this.profile().send.jitterRangeMs);
            int lowBound = Math.max(1, centerPort - portRange);
            int highBound = Math.min(65535, centerPort + portRange);
            int rangeSize = highBound - lowBound + 1;
            windowSize = Math.min(windowSize, rangeSize - 1);
            if (windowSize > 0) {
               int maxStart = rangeSize - windowSize;
               int windowStart = lowBound + round * windowSize % (maxStart + 1);
               Set<Integer> chosen = new HashSet<>();
               chosen.add(centerPort);
               int added = 0;

               while (added < windowSize) {
                  int p = windowStart + rnd.nextInt(windowSize);
                  if (chosen.add(p)) {
                     added++;
                  }
               }

               portsToSend.addAll(chosen);
               Collections.shuffle(portsToSend.subList(1, portsToSend.size()), rnd);
            }
         } else {
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
         }

         LOGGER.info(
            "[UdpHolePuncher] sendControlMultiPort: send to {} ports (x3 times x3 rounds, round={}): {} (center={}, range=+/-{}, random={}, local port={})",
            new Object[]{
               portsToSend.size(),
               round,
               portsToSend.subList(0, Math.min(10, portsToSend.size())),
               centerPort,
               portRange,
               useRandomScan,
               this.socket.getLocalPort()
            }
         );

         for (int roundPass = 0; roundPass < this.profile().send.minRounds; roundPass++) {
            for (int i = 0; i < portsToSend.size(); i++) {
               int port = portsToSend.get(i);

               for (int r = 0; r < this.profile().send.minPass; r++) {
                  DatagramPacket packet = new DatagramPacket(data, data.length, addr, port);
                  sendPkt(this.socket, packet);
               }

               if (i < portsToSend.size() - 1) {
                  try {
                     Thread.sleep(this.profile().send.sleepShortMs);
                  } catch (InterruptedException ignored) {
                     Thread.currentThread().interrupt();
                     return;
                  }
               }
            }

            if (roundPass < 2) {
               try {
                  Thread.sleep(this.profile().send.sleepLongMs);
               } catch (InterruptedException ignored) {
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
         LOGGER.info("[UdpHolePuncher] Target updated to {}:{}", newIp, newPort);
      } catch (Exception e) {
         LOGGER.warn("[UdpHolePuncher] Target update failed: {}", e.getMessage());
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
               } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
               }
            }
         }
      }

      Thread s = this.sendThreadRef;
      if (s != null && s.isAlive()) {
         try {
            s.join(2000L);
         } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
         }
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
      byte[] data = new byte[]{MAGIC[0], MAGIC[1], 1, (byte)(this.sessionNonce >> 8), (byte)this.sessionNonce};
      int maxTotalCycles = this.effectiveTimeoutMs() / this.effectiveSendInterval();
      LOGGER.info(
         "[UdpHolePuncher] Multi-socket send start (Legacy): target={}:{}, sockets={}, profile={}",
         new Object[]{remoteIp, targetPort, socketGroup.size(), this.profile().describeInstance()}
      );
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
                  } catch (IOException e) {
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
                     result.complete(PunchResult.failure(socketsTried, recvPunchCounter[0], recvAckCounter[0], 0, elapsed, true));
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
               Thread.sleep(this.effectiveSendInterval());
            } catch (InterruptedException e) {
               Thread.currentThread().interrupt();
               break;
            }
         }

         if (!this.holeOpen.get() && this.punching.get()) {
            synchronized (completionLock) {
               if (this.completed.compareAndSet(false, true)) {
                  this.punching.set(false);
                  long elapsed = System.currentTimeMillis() - startTime;
                  result.complete(PunchResult.failure(socketsTried, recvPunchCounter[0], recvAckCounter[0], 0, elapsed, false));
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
                  result.complete(PunchResult.failure(socketsTried, recvPunchCounter[0], recvAckCounter[0], 0, elapsed, false));
               }
            }
         }
      }, this.effectiveTimeoutMs() + this.profile().send.extraWaitMs, TimeUnit.MILLISECONDS);
      this.timeoutFuture = tf;
      P2PBridge.registerPendingUdpTimeout(tf);
      return result;
   }
}
