package icu.wuhui.voxlink.network;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy;
import java.util.concurrent.atomic.AtomicBoolean;
import icu.wuhui.voxlink.room.ConnectionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class P2PBridge {
   private static final Logger LOGGER = LoggerFactory.getLogger("voxlink-p2p");
   private static final int BUFFER_SIZE = 32768;
   private static final int CONNECT_TIMEOUT = 10000;
   private static final int MAX_RETRY = 3;
   private static final int PUNCH_SOCKET_TIMEOUT_MS = 1000;
   private static final int JOINER_ACCEPT_TIMEOUT_MS = 30000;
   private static final int KEEPALIVE_SOCKET_TIMEOUT_MS = 60000;
   private static final int MAX_THREADS = 16;
   private static final long KEEPALIVE_TIME_SEC = 60L;
   private static final int AWAIT_SEC = 3;
   private static final int AWAIT_FINAL_SEC = 1;
   private static final int RETRY_DELAY_MS = 500;
   private static final int FIRST_PACKET_WATCHDOG_MS = 30000;
   // host 桥连本地 MC 服务器的有界重试窗口：窗口内每 1s 重试一次，期间保持 P2P 传输存活
   private static final int MC_CONNECT_RETRY_WINDOW_MS = 10000;
   private static final int MC_CONNECT_RETRY_INTERVAL_MS = 1000;
   // 建桥后首包 read<=0 的宽限期：短窗口内不判死，周期性重试读取
   private static final int FIRST_PACKET_EOF_GRACE_MS = 10000;
   private static final int WATCHDOG_POLL_INTERVAL_MS = 500;
   private static final AtomicBoolean running = new AtomicBoolean(false);
   private static final AtomicBoolean cancelled = new AtomicBoolean(false);
   private static volatile ExecutorService bridgeExecutor;
   private static volatile ServerSocket hostServer;
   private static volatile ServerSocket joinerServer = null;
   private static volatile int joinerPort = -1;
   private static volatile int hostPort = -1;
   private static final List<P2PBridge.BridgePair> activePairs = new CopyOnWriteArrayList<>();
   private static final CopyOnWriteArrayList<ScheduledFuture<?>> pendingUdpTimeouts = new CopyOnWriteArrayList<>();
   private static volatile String currentHostIp;
   private static volatile int currentHostPort;
   private static volatile boolean trafficDetected = false;
   private static volatile long firstPacketDeadlineMs = 0L;
   private static final long LAZY_P2P_TIMEOUT_MS = 5000L;
   private static final AtomicBoolean tcpJoinerBridgeConnectedV4 = new AtomicBoolean(false);
   private static final AtomicBoolean tcpJoinerBridgeConnectedV6 = new AtomicBoolean(false);
   private static final CopyOnWriteArrayList<ReliableUdpTransport> activeUdpTransports = new CopyOnWriteArrayList<>();
   private static final AtomicBoolean currentJoinerMcSocketLock = new AtomicBoolean(false);
   private static volatile Socket currentJoinerMcSocket = null;
   private static final AtomicBoolean joinerBridgeConnected = new AtomicBoolean(false);

   public static synchronized CompletableFuture<Integer> startHostBridge(int minecraftPort) {
      cancelled.set(false);
      tcpJoinerBridgeConnectedV4.set(false);
      tcpJoinerBridgeConnectedV6.set(false);
      if (running.get()) {
         ServerSocket hs = hostServer;
         if (hs != null && !hs.isClosed()) {
            return CompletableFuture.completedFuture(hs.getLocalPort());
         }

         disconnect();
      }

      return CompletableFuture.supplyAsync(() -> {
         try {
            // 暴露面说明（本次仅分析、未改绑定，结论见下）：
            // 该端口必须保持 0.0.0.0 绑定——它是"直连模式/ConnectionFallback"的入站数据面：
            // 加入方通过 connectToHost(hostIp, hostPort) 从公网主动 TCP 连入房主的
            // bridge 端口（该端口经信令 holepunch_offer.hostPort 对端可见），
            // 改绑 127.0.0.1 会直接打断全部远程直连场景，属于功能回归。
            // 实际暴露面：任意主机可连入此端口并借由桥接到本机 MC 服务端口；
            // 鉴权依赖 MC 自身（在线模式/房间白名单），桥本身不额外鉴权。
            // 缓解现状：端口为临时端口（ServerSocket(0)）、仅在开房期间存活。
            ServerSocket ss = new ServerSocket(0, 50, InetAddress.getByName("0.0.0.0"));
            int bridgePort = ss.getLocalPort();
            LOGGER.info("Host bridge listening on port {}, forwarding to MC port {}", bridgePort, minecraftPort);
            ExecutorService exec = getOrCreateExecutor();
            exec.submit(() -> acceptHostConnections(ss, minecraftPort));
            synchronized (P2PBridge.class) {
               running.set(true);
               hostServer = ss;
               hostPort = bridgePort;
            }

            return bridgePort;
         } catch (IOException e) {
            LOGGER.error("Failed to start host bridge: {}", e.getMessage());
            running.set(false);
            return -1;
         }
      }, getOrCreateExecutor());
   }

   private static void acceptHostConnections(ServerSocket ss, int minecraftPort) {
      try {
         ss.setSoTimeout(1000);
      } catch (IOException var7) {
      }

      while (running.get()) {
         Socket clientSocket = null;

         try {
            try {
               clientSocket = ss.accept();
            } catch (SocketTimeoutException e) {
               continue;
            }

            clientSocket.setTcpNoDelay(true);
            clientSocket.setSendBufferSize(32768);
            clientSocket.setReceiveBufferSize(32768);
            LOGGER.info("P2P client connected from {}", clientSocket.getRemoteSocketAddress());
            Socket mcSocket = new Socket();
            mcSocket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), minecraftPort), 10000);
            mcSocket.setTcpNoDelay(true);
            mcSocket.setSendBufferSize(32768);
            mcSocket.setReceiveBufferSize(32768);
            P2PBridge.BridgePair pair = new P2PBridge.BridgePair(clientSocket, mcSocket);
            activePairs.add(pair);
            ExecutorService exec = getOrCreateExecutor();
            exec.submit(() -> bridge(pair, pair.client, pair.mc, "P2P->MC"));
            exec.submit(() -> bridge(pair, pair.mc, pair.client, "MC->P2P"));
         } catch (IOException e) {
            if (clientSocket != null) {
               try {
                  clientSocket.close();
               } catch (IOException var6) {
               }
            }

            if (running.get()) {
               LOGGER.error("Error accepting P2P connection: {}", e.getMessage());
            }
         }
      }
   }

   public static synchronized CompletableFuture<Integer> connectToHost(String hostIp, int hostPort) {
      cancelled.set(false);
      tcpJoinerBridgeConnectedV4.set(false);
      if (isRunning()) {
         int jp = joinerPort;
         if (jp > 0) {
            return CompletableFuture.completedFuture(jp);
         }

         disconnect();
      }

      if (hostIp != null && hostIp.contains(":")) {
         try {
            InetAddress.getByName(hostIp);
         } catch (Exception e) {
            LOGGER.warn("IPv6 address pre-resolution failed for {}: {}", hostIp, e.getMessage());
         }
      }

      return connectToHostWithRetry(hostIp, hostPort, 0);
   }

   private static CompletableFuture<Integer> connectToHostWithRetry(String hostIp, int hostPort, int attempt) {
      if (cancelled.get()) {
         return CompletableFuture.completedFuture(-1);
      }

      synchronized (P2PBridge.class) {
         if (isRunning()) {
            int jp = joinerPort;
            if (jp > 0) {
               return CompletableFuture.completedFuture(jp);
            }
         }

         running.set(true);
      }

      return CompletableFuture.<Integer>supplyAsync(() -> {
         if (cancelled.get()) {
            return -1;
         }

         try {
            LOGGER.info("Joiner: connecting to host {}:{} (attempt {}/{})", new Object[]{hostIp, hostPort, attempt + 1, MAX_RETRY + 1});
            Socket hostSocket = tryConnectWithRetry(hostIp, hostPort);
            if (hostSocket == null) {
               LOGGER.error("Joiner: failed to connect to host {}:{}", hostIp, hostPort);
               running.set(false);
               return -1;
            }

            LOGGER.info("Joiner: connected to host {}:{}", hostIp, hostPort);
            ServerSocket js = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            int jpx = js.getLocalPort();
            synchronized (P2PBridge.class) {
               joinerServer = js;
               joinerPort = jpx;
               currentHostIp = hostIp;
               currentHostPort = hostPort;
            }

            LOGGER.info("Joiner: local bridge on port {}, tunneling to host {}:{}", new Object[]{jpx, hostIp, hostPort});
            hostSocket.setTcpNoDelay(true);
            hostSocket.setSendBufferSize(32768);
            hostSocket.setReceiveBufferSize(32768);
            ServerSocket serv = js;
            ExecutorService exec = getOrCreateExecutor();
            exec.submit(() -> acceptJoinerConnectionPreconnected(serv, hostSocket));
            return jpx;
         } catch (IOException e) {
            LOGGER.error("Joiner: failed to create local bridge (attempt {}/{}): {}", new Object[]{attempt + 1, MAX_RETRY + 1, e.getMessage()});
            running.set(false);
            return -1;
         }
      }, getOrCreateExecutor()).thenCompose(result -> {
         if (result <= 0 && attempt < 3 && !cancelled.get()) {
            CompletableFuture<Integer> retry = new CompletableFuture<>();
            Thread t = new Thread(() -> {
               if (cancelled.get()) {
                  retry.complete(-1);
               } else {
                  try {
                     Thread.sleep(1000L * (attempt + 1));
                     if (cancelled.get()) {
                        retry.complete(-1);
                        return;
                     }

                     connectToHostWithRetry(hostIp, hostPort, attempt + 1).whenComplete((v, ex) -> {
                        if (ex != null) {
                           retry.completeExceptionally(ex);
                        } else {
                           retry.complete(v);
                        }
                     });
                  } catch (InterruptedException e) {
                     retry.complete(-1);
                  }
               }
            }, "VoxLink-Retry");
            t.setDaemon(true);
            t.start();
            return retry;
         } else {
            return CompletableFuture.completedFuture(result);
         }
      });
   }

   public static synchronized CompletableFuture<Integer> connectToHostIpv6(String hostIpv6, int hostPort) {
      cancelled.set(false);
      tcpJoinerBridgeConnectedV6.set(false);
      if (isRunning()) {
         int jp = joinerPort;
         if (jp > 0) {
            return CompletableFuture.completedFuture(jp);
         }

         disconnect();
      }

      return connectToHostIpv6WithRetry(hostIpv6, hostPort, 0);
   }

   private static CompletableFuture<Integer> connectToHostIpv6WithRetry(String hostIpv6, int hostPort, int attempt) {
      if (cancelled.get()) {
         return CompletableFuture.completedFuture(-1);
      }

      synchronized (P2PBridge.class) {
         if (isRunning()) {
            int jp = joinerPort;
            if (jp > 0) {
               return CompletableFuture.completedFuture(jp);
            }
         }

         running.set(true);
      }

      return CompletableFuture.<Integer>supplyAsync(() -> {
         if (cancelled.get()) {
            return -1;
         }

         try {
            LOGGER.info("Joiner: connecting to host [{}]:{} (attempt {}/{})", new Object[]{hostIpv6, hostPort, attempt + 1, MAX_RETRY + 1});
            Socket hostSocket = tryConnectWithRetry(hostIpv6, hostPort);
            if (hostSocket == null) {
               LOGGER.error("Joiner: failed to connect to host [{}]:{}", hostIpv6, hostPort);
               running.set(false);
               return -1;
            }

            LOGGER.info("Joiner: connected to host [{}]:{}", hostIpv6, hostPort);
            ServerSocket js = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            int jpx = js.getLocalPort();
            synchronized (P2PBridge.class) {
               joinerServer = js;
               joinerPort = jpx;
               currentHostIp = hostIpv6;
               currentHostPort = hostPort;
            }

            LOGGER.info("Joiner: local bridge on port {}, tunneling to host [{}]:{}", new Object[]{jpx, hostIpv6, hostPort});
            hostSocket.setTcpNoDelay(true);
            hostSocket.setSendBufferSize(32768);
            hostSocket.setReceiveBufferSize(32768);
            ServerSocket serv = js;
            ExecutorService exec = getOrCreateExecutor();
            exec.submit(() -> acceptJoinerConnectionPreconnected(serv, hostSocket));
            return jpx;
         } catch (IOException e) {
            LOGGER.error("Joiner: failed to create IPv6 local bridge (attempt {}/{}): {}", new Object[]{attempt + 1, MAX_RETRY + 1, e.getMessage()});
            running.set(false);
            return -1;
         }
      }, getOrCreateExecutor()).thenCompose(result -> {
         if (result <= 0 && attempt < 3 && !cancelled.get()) {
            CompletableFuture<Integer> retry = new CompletableFuture<>();
            Thread t = new Thread(() -> {
               if (cancelled.get()) {
                  retry.complete(-1);
               } else {
                  try {
                     Thread.sleep(1000L * (attempt + 1));
                     if (cancelled.get()) {
                        retry.complete(-1);
                        return;
                     }

                     connectToHostIpv6WithRetry(hostIpv6, hostPort, attempt + 1).whenComplete((v, ex) -> {
                        if (ex != null) {
                           retry.completeExceptionally(ex);
                        } else {
                           retry.complete(v);
                        }
                     });
                  } catch (InterruptedException e) {
                     retry.complete(-1);
                  }
               }
            }, "VoxLink-Retry6");
            t.setDaemon(true);
            t.start();
            return retry;
         } else {
            return CompletableFuture.completedFuture(result);
         }
      });
   }

   private static void acceptJoinerConnectionPreconnected(ServerSocket js, Socket preconnectedHostSocket) {
      try {
         js.setSoTimeout(10000);
      } catch (IOException var30) {
      }

      boolean first = true;

      try {
         while (running.get() && !js.isClosed()) {
            Socket mcClient;
            try {
               mcClient = js.accept();
            } catch (SocketTimeoutException e) {
               continue;
            }

            mcClient.setTcpNoDelay(true);
            mcClient.setSendBufferSize(32768);
            mcClient.setReceiveBufferSize(32768);
            LOGGER.info("Joiner: MC client connected to local bridge (pre-connected)");
            Socket hostSocket = null;
            if (first && preconnectedHostSocket != null && !preconnectedHostSocket.isClosed()) {
               hostSocket = preconnectedHostSocket;
               first = false;
            } else {
               hostSocket = tryConnectWithRetry(currentHostIp, currentHostPort);
            }

            if (hostSocket == null) {
               LOGGER.error("Joiner: failed to establish host channel for MC client");

               try {
                  mcClient.close();
               } catch (IOException var29) {
               }
            } else {
               String label = hostSocket.getInetAddress() instanceof Inet6Address ? "MC->Host(IPv6)" : "MC->Host";
               String labelRev = hostSocket.getInetAddress() instanceof Inet6Address ? "Host(IPv6)->MC" : "Host->MC";
               P2PBridge.BridgePair pair = new P2PBridge.BridgePair(mcClient, hostSocket);
               activePairs.add(pair);
               ExecutorService exec = getOrCreateExecutor();
               exec.submit(() -> bridge(pair, pair.client, pair.mc, label));
               exec.submit(() -> bridge(pair, pair.mc, pair.client, labelRev));
            }
         }
      } catch (IOException e) {
         if (running.get()) {
            LOGGER.error("Joiner: error accepting MC connection: {}", e.getMessage());
         }

         if (preconnectedHostSocket != null && !preconnectedHostSocket.isClosed()) {
            try {
               preconnectedHostSocket.close();
            } catch (IOException var28) {
            }
         }
      } finally {
         try {
            js.close();
         } catch (IOException var27) {
         }

         synchronized (P2PBridge.class) {
            if (joinerServer == js) {
               joinerServer = null;
               joinerPort = -1;
            }
         }
      }
   }

   private static void acceptJoinerConnections(ServerSocket js) {
      try {
         js.setSoTimeout(1000);
      } catch (IOException var9) {
      }

      while (running.get() && !js.isClosed() && !tcpJoinerBridgeConnectedV4.get()) {
         try {
            Socket mcClient;
            try {
               mcClient = js.accept();
            } catch (SocketTimeoutException e) {
               continue;
            }

            mcClient.setTcpNoDelay(true);
            mcClient.setSendBufferSize(32768);
            mcClient.setReceiveBufferSize(32768);
            LOGGER.info("Joiner: MC client connected to local bridge");
            Socket hostSocket = tryConnectWithRetry(currentHostIp, currentHostPort);
            if (hostSocket == null) {
               LOGGER.error("Joiner: failed to connect to host {}:{}", currentHostIp, currentHostPort);

               try {
                  mcClient.close();
               } catch (IOException var8) {
               }
            } else {
               tcpJoinerBridgeConnectedV4.set(true);
               P2PBridge.BridgePair pair = new P2PBridge.BridgePair(mcClient, hostSocket);
               activePairs.add(pair);
               ExecutorService exec = getOrCreateExecutor();
               exec.submit(() -> bridge(pair, pair.client, pair.mc, "MC->Host"));
               exec.submit(() -> bridge(pair, pair.mc, pair.client, "Host->MC"));
            }
         } catch (IOException e) {
            if (running.get()) {
               LOGGER.error("Joiner: error accepting MC connection: {}", e.getMessage());
            }
         }
      }

      tcpJoinerBridgeConnectedV4.set(false);

      try {
         js.close();
      } catch (IOException var7) {
      }

      synchronized (P2PBridge.class) {
         if (joinerServer == js) {
            joinerServer = null;
            joinerPort = -1;
         }
      }
   }

   private static void acceptJoinerConnectionsIpv6(ServerSocket js, String hostIpv6, int hostPort) {
      try {
         js.setSoTimeout(1000);
      } catch (IOException var11) {
      }

      while (running.get() && !js.isClosed() && !tcpJoinerBridgeConnectedV6.get()) {
         try {
            Socket mcClient;
            try {
               mcClient = js.accept();
            } catch (SocketTimeoutException e) {
               continue;
            }

            mcClient.setTcpNoDelay(true);
            mcClient.setSendBufferSize(32768);
            mcClient.setReceiveBufferSize(32768);
            LOGGER.info("Joiner: MC client connected to IPv6 local bridge");
            Socket hostSocket = tryConnectWithRetry(hostIpv6, hostPort);
            if (hostSocket == null) {
               LOGGER.error("Joiner: failed to connect to host [{}]:{}", hostIpv6, hostPort);

               try {
                  mcClient.close();
               } catch (IOException var10) {
               }
            } else {
               tcpJoinerBridgeConnectedV6.set(true);
               P2PBridge.BridgePair pair = new P2PBridge.BridgePair(mcClient, hostSocket);
               activePairs.add(pair);
               ExecutorService exec = getOrCreateExecutor();
               exec.submit(() -> bridge(pair, pair.client, pair.mc, "MC->Host(IPv6)"));
               exec.submit(() -> bridge(pair, pair.mc, pair.client, "Host(IPv6)->MC"));
            }
         } catch (IOException e) {
            if (running.get()) {
               LOGGER.error("Joiner: error accepting MC connection (IPv6): {}", e.getMessage());
            }
         }
      }

      tcpJoinerBridgeConnectedV6.set(false);

      try {
         js.close();
      } catch (IOException var9) {
      }

      synchronized (P2PBridge.class) {
         if (joinerServer == js) {
            joinerServer = null;
            joinerPort = -1;
         }
      }
   }

   private static Socket tryConnectWithRetry(String host, int port) {
      if (host == null) {
         return null;
      }

      for (int attempt = 1; attempt <= 3; attempt++) {
         Socket socket = null;

         try {
            LOGGER.info("Joiner: connecting to {}:{} (attempt {}/{})", new Object[]{host, port, attempt, 3});
            socket = new Socket();
            socket.setTcpNoDelay(true);
            socket.setSendBufferSize(32768);
            socket.setReceiveBufferSize(32768);
            String resolvedHost = host;
            InetSocketAddress addr;
            if (host.contains(":")) {
               if (host.startsWith("[") && host.contains("]")) {
                  int bracketEnd = host.indexOf(93);
                  resolvedHost = host.substring(1, bracketEnd);
               }

               addr = new InetSocketAddress(InetAddress.getByName(resolvedHost), port);
            } else {
               addr = new InetSocketAddress(host, port);
            }

            socket.connect(addr, 10000);
            LOGGER.info("Joiner: connected to {}:{}", host, port);
            return socket;
         } catch (IOException e) {
            if (socket != null) {
               try {
                  socket.close();
               } catch (IOException var8) {
               }
            }

            LOGGER.warn("Joiner: connect attempt {}/{} failed for {}:{} - {}", new Object[]{attempt, 3, host, port, e.getMessage()});
            if (attempt < 3) {
               try {
                  Thread.sleep(500 * attempt);
               } catch (InterruptedException ie) {
                  Thread.currentThread().interrupt();
                  return null;
               }
            }
         }
      }

      return null;
   }

   private static void bridge(P2PBridge.BridgePair pair, Socket from, Socket to, String label) {
      LOGGER.info("[Bridge] {} started (from={}, to={})", new Object[]{label, from.getRemoteSocketAddress(), to.getRemoteSocketAddress()});

      try {
         from.setSoTimeout(60000);
         from.setKeepAlive(true);
      } catch (IOException var22) {
      }

      try (
         InputStream in = from.getInputStream();
         OutputStream out = to.getOutputStream();
      ) {
         byte[] buffer = new byte[32768];
         boolean firstPacket = true;
         int consecutiveTimeouts = 0;

         while (running.get() && !from.isClosed() && !to.isClosed()) {
            int bytesRead;
            try {
               bytesRead = in.read(buffer);
               if (bytesRead == -1) {
                  break;
               }
            } catch (SocketTimeoutException e) {
               continue;
            }

            if (firstPacket) {
               LOGGER.info("[Bridge] {} first data: {} bytes", label, bytesRead);
               notifyTrafficDetected();
               firstPacket = false;
            }

            out.write(buffer, 0, bytesRead);
            out.flush();
         }
      } catch (IOException e) {
         if (running.get()) {
            LOGGER.info("Bridge {} closed: {}", label, e.getMessage());
         }
      } finally {
         pair.close();
         activePairs.remove(pair);
      }
   }

   public static void registerPendingUdpTimeout(ScheduledFuture<?> future) {
      if (future != null) {
         pendingUdpTimeouts.add(future);
      }
   }

   public static void cancelPendingUdpTimeouts() {
      for (ScheduledFuture<?> f : pendingUdpTimeouts) {
         f.cancel(false);
      }

      pendingUdpTimeouts.clear();
   }

   public static synchronized void disconnect() {
      StackTraceElement[] st = new Throwable().getStackTrace();
      String caller = st.length > 1 ? st[1].getClassName() + "." + st[1].getMethodName() : "?";
      LOGGER.info("[P2PBridge] disconnect called from {}", caller);
      cancelled.set(true);
      running.set(false);

      for (P2PBridge.BridgePair pair : activePairs) {
         pair.close();
      }

      activePairs.clear();

      for (ReliableUdpTransport transport : activeUdpTransports) {
         try {
            transport.close();
         } catch (Exception var7) {
         }
      }

      activeUdpTransports.clear();
      cancelPendingUdpTimeouts();

      try {
         if (hostServer != null && !hostServer.isClosed()) {
            hostServer.close();
         }
      } catch (IOException var6) {
      }

      try {
         if (joinerServer != null && !joinerServer.isClosed()) {
            joinerServer.close();
         }
      } catch (IOException var5) {
      }

      hostServer = null;
      hostPort = -1;
      joinerServer = null;
      joinerPort = -1;
      currentHostIp = null;
      currentHostPort = 0;
      joinerBridgeConnected.set(false);
      tcpJoinerBridgeConnectedV4.set(false);
      tcpJoinerBridgeConnectedV6.set(false);
      trafficDetected = false;
      firstPacketDeadlineMs = 0L;
      ExecutorService oldExecutor = bridgeExecutor;
      bridgeExecutor = null;
      if (oldExecutor != null && !oldExecutor.isShutdown()) {
         oldExecutor.shutdown();
         oldExecutor.shutdownNow();
      }
   }

   private static synchronized ExecutorService getOrCreateExecutor() {
      if (bridgeExecutor == null || bridgeExecutor.isShutdown()) {
         bridgeExecutor = new ThreadPoolExecutor(0, 16, 60L, TimeUnit.SECONDS, new SynchronousQueue<>(), r -> {
            Thread t = new Thread(r, "VoxLink-Bridge");
            t.setDaemon(true);
            return t;
         }, new CallerRunsPolicy());
      }

      return bridgeExecutor;
   }

   public static int getHostPort() {
      return hostPort;
   }

   public static int getJoinerPort() {
      return joinerPort;
   }

   public static boolean isRunning() {
      return running.get();
   }

   public static boolean isTargetMatch(String hostIp, int port) {
      return currentHostPort != port ? false : hostIp == null || hostIp.equals(currentHostIp);
   }

   public static void notifyTrafficDetected() {
      if (!trafficDetected) {
         trafficDetected = true;
         LOGGER.info("Detected TCP traffic, triggering P2P punch");
      }
   }

   public static boolean shouldStartPunching() {
      return trafficDetected || System.currentTimeMillis() > firstPacketDeadlineMs;
   }

   public static void armLazyP2pDeadline() {
      firstPacketDeadlineMs = System.currentTimeMillis() + 5000L;
   }

   public static boolean isTrafficDetected() {
      return trafficDetected;
   }

   public static synchronized int startUdpJoinerBridge(ReliableUdpTransport transport) {
      cancelled.set(false);
      joinerBridgeConnected.set(false);
      boolean hasActiveUdp = activeUdpTransports.stream().anyMatch(t -> t.isConnected());
      if (running.get() && joinerPort > 0 && hasActiveUdp) {
         return joinerPort;
      }

      try {
         if (joinerServer != null && !joinerServer.isClosed()) {
            joinerServer.close();
         }

         joinerServer = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
         joinerPort = joinerServer.getLocalPort();
         running.set(true);
         activeUdpTransports.add(transport);
         transport.start();
         getOrCreateExecutor().execute(() -> acceptUdpJoinerConnections(joinerServer, transport));
         LOGGER.info("UDP joiner bridge started on port {}", joinerPort);
         return joinerPort;
      } catch (IOException e) {
         LOGGER.error("Failed to start UDP joiner bridge: {}", e.getMessage());
         running.set(false);
         if (joinerServer != null && !joinerServer.isClosed()) {
            try {
               joinerServer.close();
            } catch (IOException var4) {
            }
         }

         joinerServer = null;
         joinerPort = -1;
         return -1;
      }
   }

   private static void acceptUdpJoinerConnections(ServerSocket js, ReliableUdpTransport transport) {
      try {
         js.setSoTimeout(30000);
      } catch (IOException var20) {
      }

      int consecutiveTimeouts = 0;
      String label = "UDP joiner";

      while (running.get() && !js.isClosed() && !joinerBridgeConnected.get()) {
         try {
            Socket mcClient;
            try {
               mcClient = js.accept();
            } catch (SocketTimeoutException e) {
               if (++consecutiveTimeouts < 2) {
                  continue;
               }

               LOGGER.warn("[Bridge] {} half-open detected ({} timeouts), closing", label, consecutiveTimeouts);
               break;
            }

            consecutiveTimeouts = 0;
            mcClient.setTcpNoDelay(true);
            mcClient.setSendBufferSize(32768);
            mcClient.setReceiveBufferSize(32768);
            if (currentJoinerMcSocketLock.compareAndSet(false, true)) {
               try {
                  Socket oldMcSocket = currentJoinerMcSocket;
                  if (oldMcSocket != null && !oldMcSocket.isClosed()) {
                     LOGGER.info("UDP: Closing previous MC client connection");

                     try {
                        oldMcSocket.close();
                     } catch (IOException var18) {
                     }
                  }

                  currentJoinerMcSocket = mcClient;
               } finally {
                  currentJoinerMcSocketLock.set(false);
               }
            }

            joinerBridgeConnected.set(true);
            LOGGER.info("UDP: MC client connected to joiner bridge");
            ExecutorService exec = getOrCreateExecutor();
            exec.submit(() -> bridgeUdpToMc(transport, mcClient, null));
            bridgeMcToUdp(transport, mcClient, null);
         } catch (IOException e) {
            if (running.get() && !js.isClosed()) {
               LOGGER.warn("UDP joiner bridge accept error: {}", e.getMessage());
            }
         }
      }

      joinerBridgeConnected.set(false);

      try {
         js.close();
      } catch (IOException var17) {
      }

      synchronized (P2PBridge.class) {
         if (joinerServer == js) {
            joinerServer = null;
            joinerPort = -1;
         }
      }
   }

   public static synchronized void startUdpHostBridgeForClient(String clientId, ReliableUdpTransport transport, int mcPort, Runnable onClose) {
      cancelled.set(false);
      activeUdpTransports.add(transport);
      transport.start();
      running.set(true);
      getOrCreateExecutor()
         .execute(
            () -> {
               Runnable onCloseFinal = onClose;
               Socket mcSocket = null;
               AtomicBoolean firstPacketArrived = new AtomicBoolean(false);
               AtomicBoolean failureHandled = new AtomicBoolean(false);

               try {
                  InputStream udpIn = transport.getInputStream();
                  byte[] firstBuf = new byte[32768];
                  Thread firstPacketWatchdog = new Thread(() -> {
                     try {
                        // 观察窗口 1：首包未到先不自行动作；
                        // 若其他传输路径已活跃（TCP 直连桥对存在）或会话已在其他通道生效，
                        // 说明竞争已被别的路径获胜，本桥已落选——不触发 ICE restart、不发错误广播
                        Thread.sleep(FIRST_PACKET_WATCHDOG_MS);
                        if (firstPacketArrived.get() || !transport.isConnected() || !running.get()) {
                           return;
                        }

                        if (!activePairs.isEmpty()) {
                           LOGGER.info(
                              "[BridgeWatchdog] UDP host bridge for client {} idle {}s, TCP direct path active ({} pair(s)), skip",
                              new Object[]{clientId, FIRST_PACKET_WATCHDOG_MS / 1000, activePairs.size()}
                           );
                           return;
                        }

                        if (ConnectionState.getCurrent() == ConnectionState.CONNECTED) {
                           LOGGER.info("UDP host bridge for client {} idle {}s, session active on other channel, skip ICE restart", clientId, FIRST_PACKET_WATCHDOG_MS / 1000);
                           return;
                        }

                        // 不再直接触发 ICE restart：仅记录 WARN 并进入延长观察窗口
                        LOGGER.warn(
                           "UDP host bridge for client {} first packet timeout ({}s), extended observation {}s before teardown",
                           clientId, FIRST_PACKET_WATCHDOG_MS / 1000, FIRST_PACKET_WATCHDOG_MS / 1000
                        );

                        // 观察窗口 2：延长观察，期间收到任何对端数据或其他路径接管即撤销
                        long extendedDeadlineMs = System.currentTimeMillis() + (long)FIRST_PACKET_WATCHDOG_MS;
                        while (System.currentTimeMillis() < extendedDeadlineMs) {
                           if (firstPacketArrived.get()) {
                              LOGGER.info("[BridgeWatchdog] UDP host bridge for client {} received traffic, extended observation revoked", clientId);
                              return;
                           }

                           if (!transport.isConnected() || !running.get()) {
                              return;
                           }

                           if (!activePairs.isEmpty()) {
                              LOGGER.info("[BridgeWatchdog] UDP host bridge for client {} other path took over, extended observation revoked", clientId);
                              return;
                           }

                           Thread.sleep((long)WATCHDOG_POLL_INTERVAL_MS);
                        }

                        // 连续两个窗口零流量且本桥仍是唯一路径时才走断开回调
                        if (!firstPacketArrived.get() && transport.isConnected() && running.get() && activePairs.isEmpty() && ConnectionState.getCurrent() != ConnectionState.CONNECTED) {
                           LOGGER.warn(
                              "[BridgeWatchdog] UDP host bridge for client {} zero traffic for 2 consecutive windows ({}s each), closing bridge",
                              clientId, FIRST_PACKET_WATCHDOG_MS / 1000
                           );
                           teardownUdpHostBridge(transport, null, failureHandled, onCloseFinal);
                        } else {
                           LOGGER.info("[BridgeWatchdog] UDP host bridge for client {} extended observation finished, bridge kept", clientId);
                        }
                     } catch (InterruptedException var3) {
                     }
                  }, "VoxLink-BridgeWatchdog");
                  firstPacketWatchdog.setDaemon(true);
                  firstPacketWatchdog.start();
                  int firstLen = udpIn.read(firstBuf);
                  // 容错：建桥后短窗口内 read<=0（如本地服务器刚重启导致的瞬断）
                  // 不立即判死，在宽限期内周期性重试读取；期间保持 P2P 传输存活
                  if (firstLen <= 0) {
                     LOGGER.warn(
                        "UDP host bridge for client {} stream closed before first packet (read={}), grace period {}ms before failing",
                        new Object[]{clientId, firstLen, FIRST_PACKET_EOF_GRACE_MS}
                     );
                     long eofGraceDeadlineMs = System.currentTimeMillis() + (long)FIRST_PACKET_EOF_GRACE_MS;
                     while (firstLen <= 0 && System.currentTimeMillis() < eofGraceDeadlineMs && running.get()) {
                        Thread.sleep((long)MC_CONNECT_RETRY_INTERVAL_MS);
                        if (transport.isConnected()) {
                           firstLen = udpIn.read(firstBuf);
                        }
                     }
                  }

                  firstPacketArrived.set(firstLen > 0);
                  if (firstLen <= 0) {
                     throw new IOException("UDP stream closed before first packet (read=" + firstLen + ")");
                  }

                  // 连接本地 MC 服务器改为有界重试窗口：host 停服重建/集成服重启期间保持桥接等待，
                  // 不立即拆链、不给对端发 DISCONNECT；窗口内任一次成功即进入正常转发循环
                  mcSocket = connectMcWithRetryWindow(clientId, mcPort);
                  if (mcSocket == null) {
                     throw new IOException("local MC server on port " + mcPort + " unreachable after retry window");
                  }

                  OutputStream mcOut = mcSocket.getOutputStream();
                  mcOut.write(firstBuf, 0, firstLen);
                  mcOut.flush();
                  LOGGER.info(
                     "UDP host bridge for client {} connected to MC server on port {} (lazy, first packet {} bytes)", new Object[]{clientId, mcPort, firstLen}
                  );
                  ExecutorService exec = getOrCreateExecutor();
                  Socket mcSocketFinal = mcSocket;
                  // H7: UDP->MC 与 MC->UDP 两个方向的 finally 都会执行 onClose,
                  // 必须把 onClose 包成 CAS 守卫的一次性 Runnable, 避免双跑
                  AtomicBoolean onCloseFired = new AtomicBoolean(false);
                  Runnable onCloseOnce = onCloseFinal == null ? null : () -> {
                     if (onCloseFired.compareAndSet(false, true)) {
                        onCloseFinal.run();
                     }
                  };
                  exec.submit(() -> bridgeUdpToMc(transport, mcSocketFinal, onCloseOnce));
                  bridgeMcToUdp(transport, mcSocketFinal, onCloseOnce);
               } catch (IOException e) {
                  LOGGER.error("UDP host bridge for client {} failed to connect to MC server: {}", clientId, e.getMessage());
                  teardownUdpHostBridge(transport, mcSocket, failureHandled, onCloseFinal);
               } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  teardownUdpHostBridge(transport, mcSocket, failureHandled, onCloseFinal);
               }
            }
         );
   }

   /**
    * 有界重试连接本地 MC 服务器：在 MC_CONNECT_RETRY_WINDOW_MS 窗口内每
    * MC_CONNECT_RETRY_INTERVAL_MS 重试一次。host 停服重建/集成服重启窗口内保持
    * P2P 传输存活等待（不关闭传输、不向对端发 DISCONNECT），任一次成功即返回；
    * 窗口耗尽仍失败返回 null，由调用方走原有失败路径。
    */
   private static Socket connectMcWithRetryWindow(String clientId, int mcPort) {
      long retryDeadlineMs = System.currentTimeMillis() + (long)MC_CONNECT_RETRY_WINDOW_MS;
      int attempt = 0;

      while (true) {
         attempt++;
         Socket socket = new Socket();

         try {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), mcPort), CONNECT_TIMEOUT);
            socket.setTcpNoDelay(true);
            socket.setSendBufferSize(32768);
            socket.setReceiveBufferSize(32768);
            if (attempt > 1) {
               LOGGER.info("UDP host bridge for client {} connected to MC server on port {} after {} attempts", new Object[]{clientId, mcPort, attempt});
            }

            return socket;
         } catch (IOException e) {
            try {
               socket.close();
            } catch (IOException var9) {
            }

            if (System.currentTimeMillis() >= retryDeadlineMs || !running.get()) {
               LOGGER.warn(
                  "UDP host bridge for client {} local MC server on port {} unreachable (attempts={}, window={}ms): {}",
                  new Object[]{clientId, mcPort, attempt, MC_CONNECT_RETRY_WINDOW_MS, e.getMessage()}
               );
               return null;
            }

            LOGGER.warn(
               "UDP host bridge for client {} local MC server on port {} not ready (attempt {}, window={}ms): {}, retrying in {}ms",
               new Object[]{clientId, mcPort, attempt, MC_CONNECT_RETRY_WINDOW_MS, e.getMessage(), MC_CONNECT_RETRY_INTERVAL_MS}
            );
         }

         try {
            Thread.sleep((long)MC_CONNECT_RETRY_INTERVAL_MS);
         } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return null;
         }
      }
   }

   /** UDP host 桥失败收尾（幂等）：关闭本地 MC 套接字与 P2P 传输并回调 onClose；watchdog 与主流程并发触发只执行一次。 */
   private static void teardownUdpHostBridge(ReliableUdpTransport transport, Socket mcSocket, AtomicBoolean failureHandled, Runnable onClose) {
      if (!failureHandled.compareAndSet(false, true)) {
         return;
      }

      try {
         transport.close();
      } catch (Exception var7) {
      }

      if (mcSocket != null) {
         try {
            mcSocket.close();
         } catch (IOException var6) {
         }
      }

      activeUdpTransports.remove(transport);
      if (onClose != null) {
         onClose.run();
      }
   }

   private static void bridgeUdpToMc(ReliableUdpTransport transport, Socket mcSocket, Runnable onClose) {
      InputStream udpIn = transport.getInputStream();
      OutputStream mcOut = null;
      int bytesRead = 0;

      try {
         mcOut = mcSocket.getOutputStream();
         byte[] buffer = new byte[32768];

         while (running.get() && transport.isConnected() && !mcSocket.isClosed() && (bytesRead = udpIn.read(buffer)) != -1) {
            mcOut.write(buffer, 0, bytesRead);
            mcOut.flush();
         }

         LOGGER.info(
            "UDP->MC exit: running={} conn={} mcClosed={} read={}", new Object[]{running.get(), transport.isConnected(), mcSocket.isClosed(), bytesRead}
         );
      } catch (IOException e) {
         if (running.get()) {
            LOGGER.info("UDP->MC bridge closed: {}", e.getMessage());
         }
      } finally {
         try {
            mcSocket.shutdownOutput();
         } catch (IOException var18) {
         }

         try {
            mcSocket.close();
         } catch (IOException var17) {
         }

         if (onClose != null) {
            onClose.run();
         }
      }
   }

   private static void bridgeMcToUdp(ReliableUdpTransport transport, Socket mcSocket, Runnable onClose) {
      InputStream mcIn = null;
      OutputStream udpOut = null;
      int bytesRead = 0;

      try {
         mcIn = mcSocket.getInputStream();
         udpOut = transport.getOutputStream();
         byte[] buffer = new byte[32768];

         while (running.get() && transport.isConnected() && !mcSocket.isClosed() && (bytesRead = mcIn.read(buffer)) != -1) {
            udpOut.write(buffer, 0, bytesRead);
            udpOut.flush();
         }

         LOGGER.info(
            "MC->UDP exit: running={} conn={} mcClosed={} read={}", new Object[]{running.get(), transport.isConnected(), mcSocket.isClosed(), bytesRead}
         );
      } catch (IOException e) {
         if (running.get()) {
            LOGGER.info("MC->UDP bridge closed: {}", e.getMessage());
         }
      } finally {
         try {
            mcSocket.close();
         } catch (IOException var18) {
         }

         try {
            transport.close();
         } catch (Exception var17) {
         }

         activeUdpTransports.remove(transport);
         if (onClose != null) {
            onClose.run();
         }
      }
   }

   private static class BridgePair {
      final Socket client;
      final Socket mc;
      private final AtomicBoolean closed = new AtomicBoolean(false);

      BridgePair(Socket client, Socket mc) {
         this.client = client;
         this.mc = mc;
      }

      void close() {
         if (this.closed.compareAndSet(false, true)) {
            try {
               if (!this.client.isClosed()) {
                  this.client.close();
               }
            } catch (IOException var3) {
            }

            try {
               if (!this.mc.isClosed()) {
                  this.mc.close();
               }
            } catch (IOException var2) {
            }
         }
      }
   }
}
