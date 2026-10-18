package icu.wuhui.voxlink.network;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 房主侧互通隧道客户端（协议照抄 HappyLink TunnelClient，零 MC 依赖）：
 * 一条控制 TCP 连到中继节点注册房间（HELLO code token lanIp localPort → OK publicPort），
 * 有玩家进来时节点经控制连接下发 CONNECT，本端再为每个玩家新开一条 DATA 连接，
 * 把玩家数据转发到本机 MC 端口。
 */
public final class FedTunnelClient {

   public interface Listener {
      void onStatus(String message, boolean fatal);
   }

   private static final Logger LOGGER = LoggerFactory.getLogger("VoxLink-FedTunnel");

   private final String host;
   private final int tunnelPort;
   private final String code;
   private final String token;
   private final int localPort;
   private final BooleanSupplier hostAlive;
   private final IntSupplier playerCount;
   private final Listener listener;
   private final AtomicBoolean running = new AtomicBoolean(true);
   private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
      Thread t = new Thread(r, "voxlink-fed-tunnel");
      t.setDaemon(true);
      return t;
   });

   private volatile Socket control;
   private volatile int publicPort = -1;

   public FedTunnelClient(String host, int tunnelPort, String code, String token, int localPort,
                          BooleanSupplier hostAlive, IntSupplier playerCount, Listener listener) {
      this.host = host;
      this.tunnelPort = tunnelPort;
      this.code = code;
      this.token = token;
      this.localPort = localPort;
      this.hostAlive = hostAlive;
      this.playerCount = playerCount;
      this.listener = listener;
   }

   public void start() {
      this.pool.submit(this::runLoop);
   }

   public void stop() {
      this.running.set(false);
      closeQuietly(this.control);
      this.pool.shutdownNow();
   }

   public boolean isRunning() {
      return this.running.get();
   }

   public int getPublicPort() {
      return this.publicPort;
   }

   // ---------------- 控制连接 ----------------
   private void runLoop() {
      int failures = 0;
      while (this.running.get()) {
         if (!alive()) {
            break;
         }
         try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(this.host, this.tunnelPort), 8000);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(15000);
            this.control = socket;
            failures = 0;

            BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

            out.write("HELLO " + this.code + " " + this.token + " " + lanIp() + " " + this.localPort + "\n");
            out.flush();

            long lastPing = System.currentTimeMillis();
            while (this.running.get()) {
               if (!alive()) {
                  break;
               }
               String line = null;
               try {
                  line = in.readLine();
               } catch (SocketTimeoutException ignored) {
                  // 没关系，只是心跳检查点
               }
               if (line != null) {
                  line = line.trim();
                  if (line.startsWith("OK")) {
                     String[] parts = line.split("\\s+");
                     if (parts.length > 1) {
                        try {
                           this.publicPort = Integer.parseInt(parts[1]);
                        } catch (NumberFormatException ignored) {
                        }
                     }
                     notify("互通房间已上线：" + this.host + " 端口 " + this.publicPort, false);
                  } else if (line.startsWith("CONNECT")) {
                     String[] parts = line.split("\\s+");
                     if (parts.length > 1) {
                        String id = parts[1];
                        this.pool.submit(() -> openData(id));
                     }
                  } else if (line.startsWith("ERR")) {
                     notify("中继节点拒绝隧道连接：" + line, true);
                     this.running.set(false);
                     break;
                  }
               }
               long now = System.currentTimeMillis();
               if (now - lastPing > 8000) {
                  out.write("PING " + (this.playerCount == null ? 0 : this.playerCount.getAsInt()) + "\n");
                  out.flush();
                  lastPing = now;
               }
            }
         } catch (Exception e) {
            LOGGER.debug("[fed-tunnel] control connection ended: {}", e.toString());
         }
         if (!this.running.get()) {
            break;
         }
         failures++;
         if (failures > 8) {
            notify("无法连接中继节点，互通隧道已停止", true);
            break;
         }
         notify("隧道连接中断，" + Math.min(15, 3 * failures) + " 秒后重试", false);
         sleepQuietly(Math.min(15000L, 3000L * failures));
      }
      this.running.set(false);
      notify("互通隧道已停止", false);
   }

   private boolean alive() {
      return this.hostAlive == null || this.hostAlive.getAsBoolean();
   }

   private static String lanIp() {
      try {
         Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
         while (nis.hasMoreElements()) {
            NetworkInterface ni = nis.nextElement();
            if (!ni.isUp() || ni.isLoopback()) {
               continue;
            }
            Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
            while (addrs.hasMoreElements()) {
               java.net.InetAddress a = addrs.nextElement();
               if (a.isSiteLocalAddress()) {
                  return a.getHostAddress();
               }
            }
         }
      } catch (Exception ignored) {
         // 拿不到局域网 IP 就回 "0"，节点按无局域网信息处理
      }
      return "0";
   }

   // ---------------- 数据连接 ----------------
   private void openData(String id) {
      try (Socket up = new Socket()) {
         up.connect(new InetSocketAddress(this.host, this.tunnelPort), 8000);
         up.setTcpNoDelay(true);
         OutputStream os = up.getOutputStream();
         os.write(("DATA " + id + " " + this.token + "\n").getBytes(StandardCharsets.UTF_8));
         os.flush();

         try (Socket local = new Socket()) {
            local.connect(new InetSocketAddress("127.0.0.1", this.localPort), 5000);
            local.setTcpNoDelay(true);
            pipe(up, local);
         }
      } catch (IOException ignored) {
         // 玩家可能已经断开，忽略
      }
   }

   private static void pipe(Socket a, Socket b) throws IOException {
      InputStream ai = a.getInputStream();
      OutputStream ao = a.getOutputStream();
      InputStream bi = b.getInputStream();
      OutputStream bo = b.getOutputStream();

      Thread t = new Thread(() -> {
         try {
            ai.transferTo(bo);
            bo.flush();
         } catch (IOException ignored) {
            // 对端断开
         }
      }, "voxlink-fed-pipe");
      t.setDaemon(true);
      t.start();

      try {
         bi.transferTo(ao);
         ao.flush();
      } catch (IOException ignored) {
         // 对端断开
      }
      closeQuietly(a);
      closeQuietly(b);
   }

   private static void closeQuietly(Socket s) {
      if (s != null) {
         try {
            s.close();
         } catch (IOException ignored) {
            // 忽略
         }
      }
   }

   private static void sleepQuietly(long ms) {
      try {
         Thread.sleep(ms);
      } catch (InterruptedException e) {
         Thread.currentThread().interrupt();
      }
   }

   private void notify(String message, boolean fatal) {
      LOGGER.info("[fed-tunnel] {}{}", message, fatal ? " (fatal)" : "");
      if (this.listener != null) {
         try {
            this.listener.onStatus(message, fatal);
         } catch (Exception ignored) {
            // 忽略
         }
      }
   }
}
