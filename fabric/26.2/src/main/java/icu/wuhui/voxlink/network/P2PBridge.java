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

   /** TCP 打洞胜利 socket 交棒：joiner 侧直接建本地桥，不再二次 connect。 */
   public static synchronized CompletableFuture<Integer> connectToHostPreconnected(Socket punched, String hostIp, int hostPort) {
      cancelled.set(false);
      tcpJoinerBridgeConnectedV4.set(false);
      if (isRunning()) {
         int jp = joinerPort;
         if (jp > 0) {
            try {
               punched.close();
            } catch (Exception ignored) {
            }
            LOGGER.warn("Joiner: bridge already on {}, reuse it, close new punched socket", jp);
            return CompletableFuture.completedFuture(jp);
         }

         disconnect();
      }

      return CompletableFuture.supplyAsync(() -> {
         try {
            if (punched == null || punched.isClosed()) {
               running.set(false);
               return -1;
            }

            ServerSocket js = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            int jpx = js.getLocalPort();
            synchronized (P2PBridge.class) {
               joinerServer = js;
               joinerPort = jpx;
               currentHostIp = hostIp;
               currentHostPort = hostPort;
            }

            punched.setTcpNoDelay(true);
            punched.setSendBufferSize(32768);
            punched.setReceiveBufferSize(32768);
            running.set(true);
            LOGGER.info("Joiner: punched-socket bridge on port {} (peer {}:{})", new Object[]{jpx, hostIp, hostPort});
            getOrCreateExecutor().submit(() -> acceptJoinerConnectionPreconnected(js, punched));
            return jpx;
         } catch (IOException e) {
            LOGGER.error("Joiner: punched-socket bridge failed: {}", e.getMessage());
            running.set(false);
            if (punched != null) {
               try {
                  punched.close();
               } catch (IOException ignored) {
               }
            }

            return -1;
         }
      }, getOrCreateExecutor());
   }

   /** TCP 打洞胜利 socket 交棒：host 侧直桥本地 MC（等价 accept 后处理）。 */
   public static synchronized boolean bridgePunchedHostSocket(Socket punched, int minecraftPort) {
      if (punched == null || punched.isClosed() || minecraftPort <= 0) {
         return false;
      }

      Socket mcSocket = null;
      try {
         cancelled.set(false);
         running.set(true);
         punched.setTcpNoDelay(true);
         punched.setSendBufferSize(32768);
         punched.setReceiveBufferSize(32768);
         LOGGER.info("Punched client from {}, bridging to MC port {}", new Object[]{punched.getRemoteSocketAddress(), minecraftPort});
         mcSocket = new Socket();
         mcSocket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), minecraftPort), 10000);
         mcSocket.setTcpNoDelay(true);
         mcSocket.setSendBufferSize(32768);
         mcSocket.setReceiveBufferSize(32768);
         BridgePair pair = new BridgePair(punched, mcSocket);
         activePairs.add(pair);
         ExecutorService exec = getOrCreateExecutor();
         exec.submit(() -> bridge(pair, pair.client, pair.mc, "Punched->MC"));
         exec.submit(() -> bridge(pair, pair.mc, pair.client, "MC->Punched"));
         return true;
      } catch (IOException e) {
         LOGGER.error("Punched-socket host bridge failed: {}", e.getMessage());
         running.set(false);
         if (mcSocket != null) {
            try {
               mcSocket.close();
            } catch (IOException ignored) {
            }
         }

         try {
            punched.close();
         } catch (IOException ignored) {
         }

         return false;
      }
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
      joinerSessionAlive = false;

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
      resetJoinerDownlink();
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
         joinerSessionAlive = true;
         activeUdpTransports.add(transport);
         joinerCarrier = transport;
         transport.start();
         getOrCreateExecutor().execute(() -> acceptUdpJoinerConnections(joinerServer, transport));
         icu.wuhui.voxlink.ui.UiLogBus.push(0, "voxlink.logui.bridge_connecting");
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

   // ===== joiner 侧载具热切（自动中继换道 / 后台直连升级共用） =====
   // MC 客户端的 TCP 连接与本地桥端口全程不动，只换背后承载的 RUDP 传输。
   // 字节流零丢失的锁纪律：上行泵写出口必须在 JOINER_SWAP_LOCK 内且暂停时 wait；
   // 热切=暂停泵→排干旧出口积压→锁内二次排干+原子换引用→唤醒泵。
   private static volatile ReliableUdpTransport joinerCarrier;
   private static volatile boolean joinerPumpPaused;
   /** joiner 会话存活标志：startUdpJoinerBridge 置 true，disconnect() 置 false；
    * accept 循环在首个 MC 连接后清理 joinerServer/joinerPort，不能用它们当存活判据。 */
   private static volatile boolean joinerSessionAlive;
   private static final Object JOINER_SWAP_LOCK = new Object();
   // 下行首包标志：中继"真连接验证"用——服务器经中继回包到达即算该道活
   private static volatile boolean joinerDownlinkSeen = false;

   public static void markJoinerDownlink() {
      joinerDownlinkSeen = true;
   }

   public static boolean hasJoinerDownlink() {
      return joinerDownlinkSeen;
   }

   public static void resetJoinerDownlink() {
      joinerDownlinkSeen = false;
   }

   // ===== 连接期上行日志（中继换道重放用） =====
   // 从 MC 客户端读出的每一个字节都记账，直到"真连接验证"通过（下行首包/进世界）。
   // 换道时整段重放到新载具：旧道未送达的握手字节不丢，MC 服务器看到完整全新握手。
   // 验证通过后停止记账（活会话不允许重放语义）；上限 256KB 防失控。
   private static final int JOURNAL_MAX_BYTES = 262144;
   private static final Object JOURNAL_LOCK = new Object();
   private static java.io.ByteArrayOutputStream joinerUplinkJournal;
   private static volatile boolean joinerJournalActive;
   private static volatile boolean joinerJournalOverflow;

   public static void startJoinerJournal() {
      synchronized (JOURNAL_LOCK) {
         joinerUplinkJournal = new java.io.ByteArrayOutputStream();
         joinerJournalActive = true;
         joinerJournalOverflow = false;
      }
   }

   public static void stopJoinerJournal() {
      joinerJournalActive = false;
   }

   /** 取走当前日志快照（不停止记账；换道后由 startJoinerJournal 重置）。 */
   public static byte[] takeJoinerUplinkJournal() {
      synchronized (JOURNAL_LOCK) {
         if (joinerJournalOverflow || joinerUplinkJournal == null) {
            return joinerJournalOverflow ? null : new byte[0];
         }
         return joinerUplinkJournal.toByteArray();
      }
   }

   private static void journalUplink(byte[] buf, int off, int len) {
      if (!joinerJournalActive) {
         return;
      }
      synchronized (JOURNAL_LOCK) {
         if (!joinerJournalActive || joinerUpstreamOverflowed()) {
            return;
         }
         if (joinerUplinkJournal.size() + len > JOURNAL_MAX_BYTES) {
            joinerJournalOverflow = true;
            joinerJournalActive = false;
            LOGGER.warn("[Bridge] joiner uplink journal overflow ({}B), relay swap disabled this session", JOURNAL_MAX_BYTES);
            return;
         }
         joinerUplinkJournal.write(buf, off, len);
      }
   }

   private static boolean joinerUpstreamOverflowed() {
      return joinerJournalOverflow;
   }

   /** joiner 桥当前承载（无桥=null）。 */
   public static ReliableUdpTransport getJoinerCarrier() {
      return joinerCarrier;
   }

   /**
    * 热切 joiner 桥承载：排干旧传输出口积压 → 原子换载具 → 种子重放 → 关旧开新。
    * seedJournal（连接期上行日志）必须在泵解除暂停前、在临界区内写入新载具——
    * 否则泵恢复后先写未读字节，种子后到，字节序倒置即流损坏（本地实验实证）。
    * 前置：本地桥已在跑（joinerPort>0）。返回是否成功。
    */
   public static boolean swapJoinerCarrier(ReliableUdpTransport newTransport, byte[] seedJournal) {
      if (newTransport == null) {
         return false;
      }
      synchronized (JOINER_SWAP_LOCK) {
         if (joinerServer == null || joinerServer.isClosed() || joinerPort <= 0) {
            return false;
         }
         ReliableUdpTransport oldT = joinerCarrier;
         if (oldT == newTransport) {
            return true;
         }
         // 阶段1：暂停泵（锁内置位，泵在锁内写出口，此刻不可能有在途写）
         joinerPumpPaused = true;
      }

      // 阶段2：锁外排干旧出口积压（泵已暂停；ack 由网络线程推进无需锁）
      ReliableUdpTransport oldT = joinerCarrier;
      if (oldT != null && oldT.isConnected() && oldT.hasPendingOutbound()) {
         long ackDeadline = System.currentTimeMillis() + 3000L;
         while (oldT.isConnected() && oldT.hasPendingOutbound() && System.currentTimeMillis() < ackDeadline) {
            try {
               Thread.sleep(20L);
            } catch (InterruptedException e) {
               Thread.currentThread().interrupt();
               break;
            }
         }
      }

      synchronized (JOINER_SWAP_LOCK) {
         // 阶段3：锁内二次排干（防阶段2结束后最后一批写入）→ 原子换引用
         oldT = joinerCarrier;
         if (oldT != null && oldT != newTransport && oldT.isConnected() && oldT.hasPendingOutbound()) {
            long ackDeadline2 = System.currentTimeMillis() + 1000L;
            while (oldT.isConnected() && oldT.hasPendingOutbound() && System.currentTimeMillis() < ackDeadline2) {
               try {
                  JOINER_SWAP_LOCK.wait(20L);
               } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  break;
               }
            }
         }
         activeUdpTransports.add(newTransport);
         joinerCarrier = newTransport;
         newTransport.start();
         // 种子重放：泵仍处暂停态，新载具上字节序 = 旧载具已交付流 + 种子 + 泵后续写入
         if (seedJournal != null && seedJournal.length > 0) {
            try {
               newTransport.getOutputStream().write(seedJournal);
               newTransport.getOutputStream().flush();
               LOGGER.info("[Bridge] joiner carrier seeded with {} journal bytes", seedJournal.length);
            } catch (Exception e) {
               LOGGER.warn("[Bridge] journal seed write failed: {}", e.getMessage());
            }
         }
         // 换道即重启 journal(此刻泵仍被 JOINER_SWAP_LOCK 暂停, 无竞态):
         // 不重启的话二次换道 take 会把旧快照+已交付字节整段重放=字节重复流损坏
         startJoinerJournal();
         ReliableUdpTransport closed = oldT != null && oldT != newTransport ? oldT : null;
         joinerPumpPaused = false;
         JOINER_SWAP_LOCK.notifyAll();
         if (closed != null) {
            activeUdpTransports.remove(closed);
            try {
               closed.close();
            } catch (Exception e) {
            }
         }
         LOGGER.info("[Bridge] joiner carrier swapped (old pending drained)");
         return true;
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
            exec.submit(() -> bridgeCarrierToMcJoiner(mcClient));
            bridgeMcToCarrierJoiner(mcClient);
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

   /** joiner 下行泵：中继/host → MC 客户端。旧载具 EOF/异常时若桥仍存活则等待换道（15s 上限），换载续搬。 */
   private static void bridgeCarrierToMcJoiner(Socket mcSocket) {

      try {
         java.io.OutputStream mcOut = mcSocket.getOutputStream();
         byte[] buffer = new byte[32768];
         long carrierDeadSince = 0L;

         while (running.get() && !mcSocket.isClosed()) {
            ReliableUdpTransport t = joinerCarrier;
            if (t == null) {
               try {
                  Thread.sleep(50L);
               } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  return;
               }
               continue;
            }

            int bytesRead;
            try {
               bytesRead = t.getInputStream().read(buffer);
            } catch (IOException swapE) {
               bytesRead = -1;
            }
            if (bytesRead == -1) {
               if (t != joinerCarrier) {
                  carrierDeadSince = 0L;
                  continue;
               }
               // 当前载具死亡：若桥还活着，可能是换道挂起中——等新载具
               if (carrierDeadSince == 0L) {
                  carrierDeadSince = System.currentTimeMillis();
               }
               if (System.currentTimeMillis() - carrierDeadSince < 15_000L
                  && running.get() && joinerSessionAlive) {
                  try {
                     Thread.sleep(100L);
                  } catch (InterruptedException e) {
                     Thread.currentThread().interrupt();
                     return;
                  }
                  continue;
               }
               LOGGER.info("UDP->MC joiner pump end: carrier dead, no swap in 15s");
               return;
            }
            carrierDeadSince = 0L;
            if (bytesRead > 0) {
               markJoinerDownlink();
               mcOut.write(buffer, 0, bytesRead);
               mcOut.flush();
            }
         }
      } catch (IOException e) {
         if (running.get()) {
            LOGGER.info("UDP->MC joiner pump closed: {}", e.getMessage());
         }
      } finally {
         try {
            mcSocket.shutdownOutput();
         } catch (IOException ignored) {
         }
      }
   }

   /** joiner 上行泵：MC 客户端 → 中继/host。写出口在热切锁内进行（暂停时 wait），热切换载续搬。 */
   private static void bridgeMcToCarrierJoiner(Socket mcSocket) {

      try {
         java.io.InputStream mcIn = mcSocket.getInputStream();
         byte[] buffer = new byte[32768];

         readLoop:
         while (running.get() && !mcSocket.isClosed()) {
            int bytesRead = mcIn.read(buffer);
            if (bytesRead == -1) {
               LOGGER.info("MC->UDP joiner pump end: MC closed");
               ReliableUdpTransport t2 = joinerCarrier;
               if (t2 != null) {
                  try {
                     t2.close();
                  } catch (Exception ignored) {
                  }
               }
               return;
            }
            if (bytesRead <= 0) {
               continue;
            }

            journalUplink(buffer, 0, bytesRead);

            synchronized (JOINER_SWAP_LOCK) {
               while (joinerPumpPaused && running.get()) {
                  try {
                     JOINER_SWAP_LOCK.wait(200L);
                  } catch (InterruptedException e) {
                     Thread.currentThread().interrupt();
                     return;
                  }
               }
               ReliableUdpTransport t = joinerCarrier;
               if (t == null) {
                  continue readLoop;
               }
               try {
                  t.getOutputStream().write(buffer, 0, bytesRead);
                  t.getOutputStream().flush();
               } catch (IOException e) {
                  if (t != joinerCarrier) {
                     // 热切竞速：绝不向新载具重发本段——journal 在写入前已记账，
                     // 换道重放会完整补上；此处重发会与重放产生字节重复
                     continue readLoop;
                  }
                  // 当前载具死亡：字节已在 journal 里（写入前记账），换道重放会补上。
                  // 桥还活着=可能换道挂起中，等待换载；15s 无换道=真死亡，退出。
                  long waitStart = System.currentTimeMillis();
                  while (System.currentTimeMillis() - waitStart < 15_000L
                     && running.get() && joinerCarrier == t && joinerSessionAlive) {
                     try {
                        JOINER_SWAP_LOCK.wait(200L);
                     } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                     }
                  }
                  if (joinerCarrier != t) {
                     continue readLoop; // 换载成功, 本段由 journal 重放覆盖
                  }
                  LOGGER.info("MC->UDP joiner pump end: {}", e.getMessage());
                  return;
               }
            }
         }
      } catch (IOException e) {
         if (running.get()) {
            LOGGER.info("MC->UDP joiner pump closed: {}", e.getMessage());
         }
      }
   }

   public static synchronized void startUdpHostBridgeForClient(String clientId, ReliableUdpTransport transport, int mcPort, Runnable onClose) {
      cancelled.set(false);
      activeUdpTransports.add(transport);
      transport.start();
      running.set(true);
      // legacy 载体：整传输承载一条会话，桥死即关传输
      startHostBridgeCore(clientId, new HostBridgeCarrier() {
         @Override
         public InputStream input() {
            return transport.getInputStream();
         }

         @Override
         public OutputStream output() {
            return transport.getOutputStream();
         }

         @Override
         public boolean alive() {
            return transport.isConnected();
         }

         @Override
         public void shutdown() {
            activeUdpTransports.remove(transport);
            try {
               transport.close();
            } catch (Exception e) {
            }
         }
      }, mcPort, onClose);
   }

   /**
    * 玩家中继 v2（mux 子通道）host 侧桥：被中继房客的会话承载在候选者传输的子通道上。
    * 桥死只关子通道（EOF 标记），绝不关父传输——候选者自己的会话不能被连坐。
    */
   public static synchronized void startUdpHostBridgeForRelayedClient(String relayedClientId, ReliableUdpTransport parentTransport, int muxChan, int mcPort, Runnable onClose) {
      startHostBridgeCore("relay:" + relayedClientId, new HostBridgeCarrier() {
         @Override
         public InputStream input() {
            return parentTransport.getSubstreamInput(muxChan);
         }

         @Override
         public OutputStream output() {
            return parentTransport.getSubstreamOutput(muxChan);
         }

         @Override
         public boolean alive() {
            return parentTransport.isConnected();
         }

         @Override
         public void shutdown() {
            try {
               parentTransport.closeSubstream(muxChan);
            } catch (Exception e) {
            }
         }
      }, mcPort, onClose);
   }

   /** host 侧桥载体：一条会话的来往流 + 存活判定 + 会话级收尾（不动承载它的传输全局生命）。 */
   private interface HostBridgeCarrier {
      InputStream input();

      OutputStream output();

      boolean alive();

      void shutdown();
   }

   private static void startHostBridgeCore(String clientId, HostBridgeCarrier carrier, int mcPort, Runnable onClose) {
      cancelled.set(false);
      running.set(true);
      getOrCreateExecutor()
         .execute(
            () -> {
               Runnable onCloseFinal = onClose;
               Socket mcSocket = null;
               AtomicBoolean firstPacketArrived = new AtomicBoolean(false);
               AtomicBoolean failureHandled = new AtomicBoolean(false);

               try {
                  InputStream udpIn = carrier.input();
                  byte[] firstBuf = new byte[32768];
                  Thread firstPacketWatchdog = new Thread(() -> {
                     try {
                        // 观察窗口 1：首包未到先不自行动作；
                        // 若其他传输路径已活跃（TCP 直连桥对存在）或会话已在其他通道生效，
                        // 说明竞争已被别的路径获胜，本桥已落选——不触发 ICE restart、不发错误广播
                        Thread.sleep(FIRST_PACKET_WATCHDOG_MS);
                        if (firstPacketArrived.get() || !carrier.alive() || !running.get()) {
                           return;
                        }

                        if (!activePairs.isEmpty()) {
                           LOGGER.info(
                              "[BridgeWatchdog] UDP host bridge for {} idle {}s, TCP direct path active ({} pair(s)), skip",
                              new Object[]{clientId, FIRST_PACKET_WATCHDOG_MS / 1000, activePairs.size()}
                           );
                           return;
                        }

                        if (ConnectionState.getCurrent() == ConnectionState.CONNECTED) {
                           LOGGER.info("UDP host bridge for {} idle {}s, session active on other channel, skip ICE restart", clientId, FIRST_PACKET_WATCHDOG_MS / 1000);
                           return;
                        }

                        // 不再直接触发 ICE restart：仅记录 WARN 并进入延长观察窗口
                        LOGGER.warn(
                           "UDP host bridge for {} first packet timeout ({}s), extended observation {}s before teardown",
                           clientId, FIRST_PACKET_WATCHDOG_MS / 1000, FIRST_PACKET_WATCHDOG_MS / 1000
                        );

                        // 观察窗口 2：延长观察，期间收到任何对端数据或其他路径接管即撤销
                        long extendedDeadlineMs = System.currentTimeMillis() + (long)FIRST_PACKET_WATCHDOG_MS;
                        while (System.currentTimeMillis() < extendedDeadlineMs) {
                           if (firstPacketArrived.get()) {
                              LOGGER.info("[BridgeWatchdog] UDP host bridge for {} received traffic, extended observation revoked", clientId);
                              return;
                           }

                           if (!carrier.alive() || !running.get()) {
                              return;
                           }

                           if (!activePairs.isEmpty()) {
                              LOGGER.info("[BridgeWatchdog] UDP host bridge for {} other path took over, extended observation revoked", clientId);
                              return;
                           }

                           Thread.sleep((long)WATCHDOG_POLL_INTERVAL_MS);
                        }

                        // 连续两个窗口零流量且本桥仍是唯一路径时才走断开回调
                        if (!firstPacketArrived.get() && carrier.alive() && running.get() && activePairs.isEmpty() && ConnectionState.getCurrent() != ConnectionState.CONNECTED) {
                           LOGGER.warn(
                              "[BridgeWatchdog] UDP host bridge for {} zero traffic for 2 consecutive windows ({}s each), closing bridge",
                              clientId, FIRST_PACKET_WATCHDOG_MS / 1000
                           );
                           teardownCarrierHostBridge(carrier, null, failureHandled, onCloseFinal);
                        } else {
                           LOGGER.info("[BridgeWatchdog] UDP host bridge for {} extended observation finished, bridge kept", clientId);
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
                        "UDP host bridge for {} stream closed before first packet (read={}), grace period {}ms before failing",
                        new Object[]{clientId, firstLen, FIRST_PACKET_EOF_GRACE_MS}
                     );
                     long eofGraceDeadlineMs = System.currentTimeMillis() + (long)FIRST_PACKET_EOF_GRACE_MS;
                     while (firstLen <= 0 && System.currentTimeMillis() < eofGraceDeadlineMs && running.get()) {
                        Thread.sleep((long)MC_CONNECT_RETRY_INTERVAL_MS);
                        if (carrier.alive()) {
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
                     "UDP host bridge for {} connected to MC server on port {} (lazy, first packet {} bytes)", new Object[]{clientId, mcPort, firstLen}
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
                  exec.submit(() -> bridgeCarrierToMc(carrier, mcSocketFinal, onCloseOnce));
                  bridgeMcToCarrier(carrier, mcSocketFinal, onCloseOnce);
               } catch (IOException e) {
                  LOGGER.error("UDP host bridge for {} failed to connect to MC server: {}", clientId, e.getMessage());
                  teardownCarrierHostBridge(carrier, mcSocket, failureHandled, onCloseFinal);
               } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  teardownCarrierHostBridge(carrier, mcSocket, failureHandled, onCloseFinal);
               }
            }
         );
   }

   /** UDP host 桥失败收尾（幂等）：关闭本地 MC 套接字并回调 onClose；watchdog 与主流程并发触发只执行一次。 */
   private static void teardownCarrierHostBridge(HostBridgeCarrier carrier, Socket mcSocket, AtomicBoolean failureHandled, Runnable onClose) {
      if (!failureHandled.compareAndSet(false, true)) {
         return;
      }

      try {
         carrier.shutdown();
      } catch (Exception var7) {
      }

      if (mcSocket != null) {
         try {
            mcSocket.close();
         } catch (IOException var6) {
         }
      }

      if (onClose != null) {
         onClose.run();
      }
   }

   private static void bridgeCarrierToMc(HostBridgeCarrier carrier, Socket mcSocket, Runnable onClose) {
      InputStream udpIn = carrier.input();
      OutputStream mcOut = null;
      int bytesRead = 0;

      try {
         mcOut = mcSocket.getOutputStream();
         byte[] buffer = new byte[32768];

         while (running.get() && carrier.alive() && !mcSocket.isClosed() && (bytesRead = udpIn.read(buffer)) != -1) {
            mcOut.write(buffer, 0, bytesRead);
            mcOut.flush();
         }

         LOGGER.info(
            "UDP->MC exit: running={} conn={} mcClosed={} read={}", new Object[]{running.get(), carrier.alive(), mcSocket.isClosed(), bytesRead}
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

   private static void bridgeMcToCarrier(HostBridgeCarrier carrier, Socket mcSocket, Runnable onClose) {
      InputStream mcIn = null;
      OutputStream udpOut = null;
      int bytesRead = 0;

      try {
         mcIn = mcSocket.getInputStream();
         udpOut = carrier.output();
         byte[] buffer = new byte[32768];

         while (running.get() && carrier.alive() && !mcSocket.isClosed() && (bytesRead = mcIn.read(buffer)) != -1) {
            udpOut.write(buffer, 0, bytesRead);
            udpOut.flush();
         }

         LOGGER.info(
            "MC->UDP exit: running={} conn={} mcClosed={} read={}", new Object[]{running.get(), carrier.alive(), mcSocket.isClosed(), bytesRead}
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
            carrier.shutdown();
         } catch (Exception var17) {
         }

         if (onClose != null) {
            onClose.run();
         }
      }
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
