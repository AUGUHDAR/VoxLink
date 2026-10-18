package icu.wuhui.voxlink.network;

import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TcpHolePuncher {
   private static final Logger LOGGER = LoggerFactory.getLogger("voxlink-tcppunch");
   private static final int CONNECT_TIMEOUT_MS = 3000;
   private static final int CONNECT_WINDOW_MS = 10000;
   private static final int LISTEN_WINDOW_MS = 10000;
   private static final int ACCEPT_POLL_MS = 500;
   private static final ExecutorService EXEC = Executors.newCachedThreadPool(r -> {
      Thread t = new Thread(r, "VoxLink-TcpPunch");
      t.setDaemon(true);
      return t;
   });
   private final AtomicBoolean running = new AtomicBoolean(false);
   private volatile boolean stopped = false;

   public static int selectPunchPort() {
      try {
         int var1;
         try (ServerSocket ss = new ServerSocket(0)) {
            var1 = ss.getLocalPort();
         }

         return var1;
      } catch (IOException var5) {
         return -1;
      }
   }

   public boolean isRunning() {
      return this.running.get();
   }

   public void cancel() {
      this.stopped = true;
   }

   public CompletableFuture<Socket> punchAsync(String remoteIp, int port, int connectAttempts, AtomicBoolean wonFlag) {
      if (remoteIp == null || remoteIp.isEmpty() || port <= 0 || port > 65535) {
         return CompletableFuture.completedFuture(null);
      } else if (!this.running.compareAndSet(false, true)) {
         return null;
      } else {
         CompletableFuture<Socket> result = new CompletableFuture<>();
         EXEC.submit(() -> {
            Socket won = null;

            try {
               won = this.connectWindow(remoteIp, port, connectAttempts, wonFlag);
               if (won == null && !this.stopped && !wonFlag.get()) {
                  won = this.listenWindow(port, remoteIp, wonFlag);
               }
            } catch (Exception var11) {
               LOGGER.info("[TcpPunch] punch error: {}", String.valueOf(var11));
            } finally {
               this.running.set(false);
               result.complete(won);
            }
         });
         return result;
      }
   }

   private Socket connectWindow(String remoteIp, int port, int connectAttempts, AtomicBoolean wonFlag) {
      InetAddress addr;
      try {
         addr = InetAddress.getByName(remoteIp);
      } catch (IOException var12) {
         return null;
      }

      long deadline = System.currentTimeMillis() + 10000L;
      Random rng = new Random();
      if (connectAttempts <= 0) {
         connectAttempts = 1;
      }

      for (int attempt = 1; attempt <= connectAttempts && !this.stopped && !wonFlag.get() && System.currentTimeMillis() < deadline; attempt++) {
         Socket s = new Socket();

         try {
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(port));
            s.connect(new InetSocketAddress(addr, port), 3000);
            LOGGER.info("[TcpPunch] simultaneous open connected {}:{} (local port {})", new Object[]{remoteIp, port, port});
            return s;
         } catch (IOException var14) {
            LOGGER.info("[TcpPunch] connect attempt {}/{} to {}:{} failed: {}", new Object[]{attempt, connectAttempts, remoteIp, port, var14.getMessage()});
            closeQuietly(s);
            if (!(var14 instanceof BindException)) {
               try {
                  Thread.sleep((long)(50 + rng.nextInt(200)));
               } catch (InterruptedException var13) {
                  Thread.currentThread().interrupt();
                  break;
               }
               continue;
            }
            break;
         }
      }

      return null;
   }

   private Socket listenWindow(int port, String remoteIp, AtomicBoolean wonFlag) {
      InetAddress expected;
      try {
         expected = InetAddress.getByName(remoteIp);
      } catch (IOException var19) {
         return null;
      }

      ServerSocket ss = null;

      try {
         ss = new ServerSocket();
         ss.setReuseAddress(true);
         ss.bind(new InetSocketAddress(port));
      } catch (IOException var18) {
         LOGGER.info("[TcpPunch] listen bind {} failed: {}", new Object[]{port, var18.getMessage()});
         closeQuietly(ss);
         return null;
      }

      long deadline = System.currentTimeMillis() + 10000L;

      try {
         ss.setSoTimeout(500);

         while (!this.stopped && !wonFlag.get() && System.currentTimeMillis() < deadline) {
            Socket s;
            try {
               s = ss.accept();
            } catch (SocketTimeoutException var20) {
               continue;
            }

            if (expected == null || expected.equals(s.getInetAddress())) {
               try {
                  s.setTcpNoDelay(true);
               } catch (IOException var17) {
               }

               LOGGER.info("[TcpPunch] accepted punched connection from {}", new Object[]{s.getRemoteSocketAddress()});
               return s;
            }

            LOGGER.info("[TcpPunch] reject unexpected accept from {}", new Object[]{s.getRemoteSocketAddress()});
            closeQuietly(s);
         }

         return null;
      } catch (IOException var21) {
         LOGGER.info("[TcpPunch] listen error: {}", var21.getMessage());
         return null;
      } finally {
         closeQuietly(ss);
      }
   }

   private static void closeQuietly(Socket s) {
      if (s != null) {
         try {
            s.close();
         } catch (IOException var2) {
         }
      }
   }

   private static void closeQuietly(ServerSocket s) {
      if (s != null) {
         try {
            s.close();
         } catch (IOException var2) {
         }
      }
   }
}
