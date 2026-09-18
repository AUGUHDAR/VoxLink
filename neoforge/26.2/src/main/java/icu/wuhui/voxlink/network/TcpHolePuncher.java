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

/** TCP simultaneous open 协调打洞。 */
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

   /** 选取空闲端口作为双方约定的 punch 端口。 */
   public static int selectPunchPort() {
      try (ServerSocket ss = new ServerSocket(0)) {
         return ss.getLocalPort();
      } catch (IOException e) {
         return -1;
      }
   }

   public boolean isRunning() {
      return this.running.get();
   }

   public void cancel() {
      this.stopped = true;
   }

   /** bind同端口互连, 败转listen。 */
   public CompletableFuture<Socket> punchAsync(String remoteIp, int port, int connectAttempts, AtomicBoolean wonFlag) {
      if (remoteIp == null || remoteIp.isEmpty() || port <= 0 || port > 65535) {
         return CompletableFuture.completedFuture(null);
      }

      if (!this.running.compareAndSet(false, true)) {
         return null;
      }

      CompletableFuture<Socket> result = new CompletableFuture<>();
      EXEC.submit(() -> {
         Socket won = null;
         try {
            won = this.connectWindow(remoteIp, port, connectAttempts, wonFlag);
            if (won == null && !this.stopped && !wonFlag.get()) {
               won = this.listenWindow(port, remoteIp, wonFlag);
            }
         } catch (Exception e) {
            LOGGER.info("[TcpPunch] punch error: {}", String.valueOf(e));
         } finally {
            this.running.set(false);
            result.complete(won);
         }
      });
      return result;
   }

   /** 窗口内bind同端口connect对端。 */
   private Socket connectWindow(String remoteIp, int port, int connectAttempts, AtomicBoolean wonFlag) {
      InetAddress addr;
      try {
         addr = InetAddress.getByName(remoteIp);
      } catch (IOException e) {
         return null;
      }

      long deadline = System.currentTimeMillis() + (long)CONNECT_WINDOW_MS;
      Random rng = new Random();
      if (connectAttempts <= 0) {
         connectAttempts = 1;
      }

      for (int attempt = 1; attempt <= connectAttempts && !this.stopped && !wonFlag.get(); attempt++) {
         if (System.currentTimeMillis() >= deadline) {
            break;
         }

         Socket s = new Socket();
         try {
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(port));
            s.connect(new InetSocketAddress(addr, port), CONNECT_TIMEOUT_MS);
            LOGGER.info("[TcpPunch] simultaneous open connected {}:{} (local port {})", new Object[]{remoteIp, port, port});
            return s;
         } catch (IOException e) {
            LOGGER.info(
               "[TcpPunch] connect attempt {}/{} to {}:{} failed: {}", new Object[]{attempt, connectAttempts, remoteIp, port, e.getMessage()}
            );
            closeQuietly(s);
            // 端口被占即放弃本轮，下轮换端口
            if (e instanceof BindException) {
               break;
            }
         }

         try {
            Thread.sleep(50 + rng.nextInt(200));
         } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            break;
         }
      }

      return null;
   }

   private Socket listenWindow(int port, String remoteIp, AtomicBoolean wonFlag) {
      InetAddress expected;
      try {
         expected = InetAddress.getByName(remoteIp);
      } catch (IOException e) {
         return null;
      }

      ServerSocket ss = null;
      try {
         ss = new ServerSocket();
         ss.setReuseAddress(true);
         ss.bind(new InetSocketAddress(port));
      } catch (IOException e) {
         LOGGER.info("[TcpPunch] listen bind {} failed: {}", new Object[]{port, e.getMessage()});
         closeQuietly(ss);
         return null;
      }

      long deadline = System.currentTimeMillis() + (long)LISTEN_WINDOW_MS;
      try {
         ss.setSoTimeout(ACCEPT_POLL_MS);
         while (!this.stopped && !wonFlag.get() && System.currentTimeMillis() < deadline) {
            Socket s;
            try {
               s = ss.accept();
            } catch (SocketTimeoutException e) {
               continue;
            }

            if (expected != null && !expected.equals(s.getInetAddress())) {
               LOGGER.info("[TcpPunch] reject unexpected accept from {}", new Object[]{s.getRemoteSocketAddress()});
               closeQuietly(s);
               continue;
            }

            try {
               s.setTcpNoDelay(true);
            } catch (IOException ignored) {
            }

            LOGGER.info("[TcpPunch] accepted punched connection from {}", new Object[]{s.getRemoteSocketAddress()});
            return s;
         }
      } catch (IOException e) {
         LOGGER.info("[TcpPunch] listen error: {}", e.getMessage());
      } finally {
         closeQuietly(ss);
      }

      return null;
   }

   private static void closeQuietly(Socket s) {
      if (s != null) {
         try {
            s.close();
         } catch (IOException ignored) {
         }
      }
   }

   private static void closeQuietly(ServerSocket s) {
      if (s != null) {
         try {
            s.close();
         } catch (IOException ignored) {
         }
      }
   }
}
