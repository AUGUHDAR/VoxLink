package icu.wuhui.voxlink.network;

import java.io.IOException;
import java.net.ConnectException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Enumeration;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ConnectionFallback {
   private static final Logger LOGGER = LoggerFactory.getLogger("voxlink-fallback");
   private static final ExecutorService FALLBACK_EXECUTOR = Executors.newCachedThreadPool(r -> {
      Thread t = new Thread(r, "VoxLink-Fallback");
      t.setDaemon(true);
      return t;
   });
   private static final int SOCKET_TIMEOUT = 3000;
   private static final int SOCKET_CONNECT_TIMEOUT_MS = 2000;
   private static final int TCP_SIMOPEN_WINDOW_MS = 10000;
   private static final int TCP_SIMOPEN_MAX_ATTEMPTS = 5;
   private final AtomicBoolean cancelled = new AtomicBoolean(false);
   private final AtomicBoolean settled = new AtomicBoolean(false);
   private final AtomicBoolean won = new AtomicBoolean(false);
   private volatile Component statusText = Component.empty();
   private static volatile Boolean ipv6ConnectivityCached = null;
   private static volatile long ipv6ConnectivityCheckTime = 0L;
   private static final long IPV6_CHECK_CACHE_MS = 60000L;

   public static void shutdown() {
      FALLBACK_EXECUTOR.shutdown();

      try {
         if (!FALLBACK_EXECUTOR.awaitTermination(2L, TimeUnit.SECONDS)) {
            FALLBACK_EXECUTOR.shutdownNow();
         }
      } catch (InterruptedException e) {
         FALLBACK_EXECUTOR.shutdownNow();
         Thread.currentThread().interrupt();
      }
   }

   public void cancel() {
      this.cancelled.set(true);
   }

   public Component getStatusText() {
      return this.statusText;
   }

   public boolean isSettled() {
      return this.settled.get();
   }

   public boolean isCancelled() {
      return this.cancelled.get();
   }

   public CompletableFuture<ConnectionFallback.ConnectResult> tryIpv6Direct(String hostIpv6, int hostPort) {
      if (hostIpv6 != null && !hostIpv6.isEmpty()) {
         this.statusText = Component.translatable("voxlink.connection.probing");
         return CompletableFuture.supplyAsync(() -> {
            if (!this.won.get() && !this.cancelled.get() && !this.settled.get()) {
               Socket socket = null;

               try {
                  socket = new Socket();
                  socket.setTcpNoDelay(true);
                  InetAddress addr = InetAddress.getByName(hostIpv6);
                  socket.connect(new InetSocketAddress(addr, hostPort), 3000);
                  socket.close();
                  socket = null;
                  LOGGER.info("IPv6 connected: [{}]:{}", hostIpv6, hostPort);
                  this.statusText = Component.translatable("voxlink.connection.bridge_setup");
                  if (!this.won.compareAndSet(false, true)) {
                     return ConnectionFallback.ConnectResult.cancelled();
                  }

                  this.settled.set(true);
                  return ConnectionFallback.ConnectResult.success("IPv6", hostIpv6, hostPort, ConnectionFallback.ConnectionMode.IPV6_DIRECT);
               } catch (SocketTimeoutException e) {
                  LOGGER.info("IPv6 timeout: [{}]:{}", hostIpv6, hostPort);
                  this.statusText = Component.translatable("voxlink.connection.timeout");
                  return ConnectionFallback.ConnectResult.failed("IPV6_TIMEOUT", "IPv6 timeout");
               } catch (NoRouteToHostException e) {
                  LOGGER.info("IPv6 no route: [{}]:{}", hostIpv6, hostPort);
                  this.statusText = Component.translatable("voxlink.connection.failed");
                  return ConnectionFallback.ConnectResult.failed("IPV6_NO_ROUTE", "IPv6 no route");
               } catch (ConnectException e) {
                  String msg = e.getMessage();
                  LOGGER.info("IPv6 connect failed: [{}]:{} - {}", new Object[]{hostIpv6, hostPort, msg});
                  if (msg != null && msg.contains("Connection refused")) {
                     this.statusText = Component.translatable("voxlink.connection.failed");
                     return ConnectionFallback.ConnectResult.failed("IPV6_REFUSED", "IPv6 refused");
                  } else {
                     this.statusText = Component.translatable("voxlink.connection.failed");
                     return ConnectionFallback.ConnectResult.failed("IPV6_ERROR", "IPv6 failed");
                  }
               } catch (IOException e) {
                  LOGGER.info("IPv6 exception: [{}]:{} - {}", new Object[]{hostIpv6, hostPort, e.getMessage()});
                  this.statusText = Component.translatable("voxlink.connection.failed");
                  return ConnectionFallback.ConnectResult.failed("IPV6_EXCEPTION", "IPv6 exception: " + e.getMessage());
               } finally {
                  if (socket != null) {
                     try {
                        socket.close();
                     } catch (Exception var23) {
                     }
                  }
               }
            } else {
               return ConnectionFallback.ConnectResult.cancelled();
            }
         }, FALLBACK_EXECUTOR);
      } else {
         return CompletableFuture.completedFuture(ConnectionFallback.ConnectResult.failed("NO_IPV6", "没有IPv6地址"));
      }
   }

   public CompletableFuture<ConnectionFallback.ConnectResult> tryIpv4Direct(String hostIp, int hostPort) {
      if (hostIp != null && !hostIp.isEmpty()) {
         this.statusText = Component.translatable("voxlink.connection.probing");
         return CompletableFuture.supplyAsync(() -> {
            if (!this.won.get() && !this.cancelled.get() && !this.settled.get()) {
               Socket socket = null;

               try {
                  socket = new Socket();
                  socket.setTcpNoDelay(true);
                  InetAddress addr = InetAddress.getByName(hostIp);
                  socket.connect(new InetSocketAddress(addr, hostPort), 3000);
                  socket.close();
                  socket = null;
                  LOGGER.info("IPv4 connected: {}:{}", hostIp, hostPort);
                  this.statusText = Component.translatable("voxlink.connection.bridge_setup");
                  if (!this.won.compareAndSet(false, true)) {
                     return ConnectionFallback.ConnectResult.cancelled();
                  }

                  this.settled.set(true);
                  return ConnectionFallback.ConnectResult.success("IPv4", hostIp, hostPort, ConnectionFallback.ConnectionMode.IPV4_DIRECT);
               } catch (SocketTimeoutException e) {
                  LOGGER.info("IPv4 timeout: {}:{}", hostIp, hostPort);
                  this.statusText = Component.translatable("voxlink.connection.timeout");
                  return ConnectionFallback.ConnectResult.failed("IPV4_TIMEOUT", "IPv4 timeout");
               } catch (NoRouteToHostException e) {
                  LOGGER.info("IPv4 no route: {}:{}", hostIp, hostPort);
                  this.statusText = Component.translatable("voxlink.connection.failed");
                  return ConnectionFallback.ConnectResult.failed("IPV4_NO_ROUTE", "IPv4 no route");
               } catch (ConnectException e) {
                  String msg = e.getMessage();
                  LOGGER.info("IPv4 connect failed: {}:{} - {}", new Object[]{hostIp, hostPort, msg});
                  if (msg != null && msg.contains("Connection refused")) {
                     this.statusText = Component.translatable("voxlink.connection.failed");
                     return ConnectionFallback.ConnectResult.failed("IPV4_REFUSED", "IPv4 refused");
                  } else {
                     this.statusText = Component.translatable("voxlink.connection.failed");
                     return ConnectionFallback.ConnectResult.failed("IPV4_ERROR", "IPv4 failed");
                  }
               } catch (IOException e) {
                  LOGGER.info("IPv4 exception: {}:{} - {}", new Object[]{hostIp, hostPort, e.getMessage()});
                  this.statusText = Component.translatable("voxlink.connection.failed");
                  return ConnectionFallback.ConnectResult.failed("IPV4_EXCEPTION", "IPv4 exception: " + e.getMessage());
               } finally {
                  if (socket != null) {
                     try {
                        socket.close();
                     } catch (Exception var23) {
                     }
                  }
               }
            } else {
               return ConnectionFallback.ConnectResult.cancelled();
            }
         }, FALLBACK_EXECUTOR);
      } else {
         return CompletableFuture.completedFuture(ConnectionFallback.ConnectResult.failed("NO_IPV4", "没有IPv4地址"));
      }
   }

   public static synchronized boolean verifyIPv6Connectivity() {
      long now = System.currentTimeMillis();
      if (ipv6ConnectivityCached != null && now - ipv6ConnectivityCheckTime < 60000L) {
         return ipv6ConnectivityCached;
      }

      if (!hasIPv6Connectivity()) {
         ipv6ConnectivityCached = false;
         ipv6ConnectivityCheckTime = now;
         return false;
      }

      String[] testTargets = new String[]{"2001:4860:4860::8888", "2001:4860:4860::8844"};

      for (String target : testTargets) {
         try (Socket sock = new Socket()) {
            sock.connect(new InetSocketAddress(target, 53), 2000);
            LOGGER.info("[IPv6] Connectivity check success: {}", target);
            ipv6ConnectivityCached = true;
            ipv6ConnectivityCheckTime = now;
            return true;
         } catch (Exception e) {
            LOGGER.debug("[IPv6] Connectivity check failed {}: {}", target, e.getMessage());
         }
      }

      LOGGER.info("[IPv6] Connectivity check failed: all targets unreachable");
      ipv6ConnectivityCached = false;
      ipv6ConnectivityCheckTime = now;
      return false;
   }

   public static boolean hasIPv6Connectivity() {
      try {
         Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();

         while (interfaces.hasMoreElements()) {
            NetworkInterface ni = interfaces.nextElement();
            if (!ni.isLoopback() && ni.isUp()) {
               Enumeration<InetAddress> addrs = ni.getInetAddresses();

               while (addrs.hasMoreElements()) {
                  InetAddress addr = addrs.nextElement();
                  if (addr instanceof Inet6Address inet6 && !addr.isLoopbackAddress() && !addr.isLinkLocalAddress()) {
                     String ip = addr.getHostAddress();
                     int scopeIdx = ip.indexOf(37);
                     if (scopeIdx >= 0) {
                        ip = ip.substring(0, scopeIdx);
                     }

                     LOGGER.debug(
                        "[IPv6] Found IPv6 addr: {} on interface {} (loopback={}, linkLocal={}, siteLocal={}, ULA={})",
                        new Object[]{
                           ip,
                           ni.getName(),
                           addr.isLoopbackAddress(),
                           addr.isLinkLocalAddress(),
                           addr.isSiteLocalAddress(),
                           ip.startsWith("fd") || ip.startsWith("fc")
                        }
                     );
                     if (!ip.startsWith("fd") && !ip.startsWith("fc")) {
                        LOGGER.info("[IPv6] Usable global IPv6 found on {}", ni.getName());
                        return true;
                     }
                  }
               }
            }
         }

         LOGGER.info("[IPv6] No usable global IPv6 address found");
      } catch (Exception e) {
         LOGGER.warn("[IPv6] Error checking IPv6: {}", e.getMessage());
      }

      return false;
   }

   public static String getLocalGlobalIpv6() {
      try {
         Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();

         while (interfaces.hasMoreElements()) {
            NetworkInterface ni = interfaces.nextElement();
            if (!ni.isLoopback() && ni.isUp()) {
               Enumeration<InetAddress> addrs = ni.getInetAddresses();

               while (addrs.hasMoreElements()) {
                  InetAddress addr = addrs.nextElement();
                  if (addr instanceof Inet6Address && !addr.isLoopbackAddress() && !addr.isLinkLocalAddress()) {
                     String ipv6 = addr.getHostAddress();
                     int scopeIdx = ipv6.indexOf(37);
                     if (scopeIdx >= 0) {
                        ipv6 = ipv6.substring(0, scopeIdx);
                     }

                     if (!ipv6.startsWith("fd") && !ipv6.startsWith("fc")) {
                        return ipv6;
                     }
                  }
               }
            }
         }
      } catch (Exception var6) {
      }

      return null;
   }

   public CompletableFuture<ConnectionFallback.ConnectResult> tryTcpSimultaneousOpen(String remoteIp, int remotePort, int localPort) {
      if (remoteIp != null && !remoteIp.isEmpty()) {
         this.statusText = Component.translatable("voxlink.connection.probing");
         return CompletableFuture.supplyAsync(
            () -> {
               if (!this.won.get() && !this.cancelled.get() && !this.settled.get()) {
                  InetAddress addr;
                  try {
                     addr = InetAddress.getByName(remoteIp);
                  } catch (Exception e) {
                     return ConnectionFallback.ConnectResult.failed("TCP_SIMOPEN_FAILED", "Bad addr: " + e.getMessage());
                  }

                  long deadline = System.currentTimeMillis() + 10000L;
                  int attempts = 0;
                  Random rng = new Random();
                  boolean randomPortMode = false;

                  while (System.currentTimeMillis() < deadline && attempts < 5 && !this.won.get() && !this.cancelled.get() && !this.settled.get()) {
                     attempts++;
                     Socket clientSocket = null;

                     try {
                        clientSocket = new Socket();
                        clientSocket.setTcpNoDelay(true);
                        clientSocket.setReuseAddress(true);

                        try {
                           clientSocket.bind(new InetSocketAddress(randomPortMode ? 0 : localPort));
                        } catch (IOException bindEx) {
                           if (!randomPortMode) {
                              randomPortMode = true;
                              LOGGER.info("TCP SimOpen: local port {} occupied, fallback to random port", localPort);
                              if (clientSocket != null) {
                                 try {
                                    clientSocket.close();
                                 } catch (Exception var16) {
                                 }
                              }
                              continue;
                           }

                           LOGGER.info("TCP SimOpen: local port {} occupied, SimOpen will fail", localPort);
                           if (clientSocket != null) {
                              try {
                                 clientSocket.close();
                              } catch (Exception var15) {
                              }
                           }

                           return ConnectionFallback.ConnectResult.failed("TCP_SIMOPEN_BIND_FAIL", "本地端口" + localPort + "被占");
                        }

                        LOGGER.info(
                           "TCP SimOpen: attempt {}/{}, from port {} to {}:{}", new Object[]{attempts, 5, clientSocket.getLocalPort(), remoteIp, remotePort}
                        );
                        clientSocket.connect(new InetSocketAddress(addr, remotePort), 3000);
                        LOGGER.info("TCP SimOpen: attempt {} connected {}:{}", new Object[]{attempts, remoteIp, remotePort});
                        this.statusText = Component.translatable("voxlink.connection.bridge_setup");
                        if (!this.won.compareAndSet(false, true)) {
                           if (clientSocket != null) {
                              try {
                                 clientSocket.close();
                              } catch (Exception var14) {
                              }
                           }

                           return ConnectionFallback.ConnectResult.cancelled();
                        }

                        this.settled.set(true);
                        clientSocket.close();
                        return ConnectionFallback.ConnectResult.success("TCP-SimOpen", remoteIp, remotePort, ConnectionFallback.ConnectionMode.IPV4_DIRECT);
                     } catch (Exception e) {
                        LOGGER.info("TCP SimOpen attempt {} failed: {}:{} - {}", new Object[]{attempts, remoteIp, remotePort, e.getMessage()});
                        if (!randomPortMode && e instanceof IOException && String.valueOf(e.getMessage()).contains("Cannot assign requested address")) {
                           randomPortMode = true;
                        }

                        if (clientSocket != null) {
                           try {
                              clientSocket.close();
                           } catch (Exception var13) {
                           }
                        }

                        if (this.won.get() || this.cancelled.get() || this.settled.get() || System.currentTimeMillis() >= deadline || attempts >= 5) {
                           break;
                        }

                        try {
                           Thread.sleep(10 + rng.nextInt(90));
                        } catch (InterruptedException ie) {
                           Thread.currentThread().interrupt();
                           break;
                        }
                     }
                  }

                  this.statusText = Component.translatable("voxlink.connection.failed");
                  return ConnectionFallback.ConnectResult.failed("TCP_SIMOPEN_FAILED", "TCP SimOpen failed after " + attempts + " attempts");
               } else {
                  return ConnectionFallback.ConnectResult.cancelled();
               }
            },
            FALLBACK_EXECUTOR
         );
      } else {
         return CompletableFuture.completedFuture(ConnectionFallback.ConnectResult.failed("NO_IP", "No remote IP"));
      }
   }

   public static class ConnectResult {
      public final boolean success;
      public final String failureReason;
      public final String errorCode;
      public final String label;
      public final String remoteHost;
      public final int remotePort;
      public final ConnectionFallback.ConnectionMode mode;

      private ConnectResult(
         boolean success, String errorCode, String failureReason, String label, String remoteHost, int remotePort, ConnectionFallback.ConnectionMode mode
      ) {
         this.success = success;
         this.errorCode = errorCode;
         this.failureReason = failureReason;
         this.label = label;
         this.remoteHost = remoteHost;
         this.remotePort = remotePort;
         this.mode = mode;
      }

      public static ConnectionFallback.ConnectResult success(String label, String remoteHost, int remotePort, ConnectionFallback.ConnectionMode mode) {
         return new ConnectionFallback.ConnectResult(true, null, null, label, remoteHost, remotePort, mode);
      }

      public static ConnectionFallback.ConnectResult failed(String errorCode, String reason) {
         return new ConnectionFallback.ConnectResult(false, errorCode, reason, null, null, 0, null);
      }

      public static ConnectionFallback.ConnectResult cancelled() {
         return new ConnectionFallback.ConnectResult(false, "CANCELLED", "Connection cancelled", null, null, 0, null);
      }
   }

   public enum ConnectionMode {
      IPV6_DIRECT("voxlink.mode.ipv6_direct"),
      IPV4_DIRECT("voxlink.mode.ipv4_direct"),
      UDP_PUNCH("voxlink.mode.udp_punch");

      public final String translationKey;

      ConnectionMode(String translationKey) {
         this.translationKey = translationKey;
      }
   }
}
