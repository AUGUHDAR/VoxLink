package icu.wuhui.voxlink.network;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * TURN TCP 兜底承载：本地回环 UDP shim ↔ 节点 TCP 长连接。
 * 帧格式：2 字节大端长度 + 与 UDP 完全同构的报文（≤2048）。
 * 对上层透明：TurnSession 的 socket 指向 shim 回环口，
 * bind/keepalive/rudp/transport 原逻辑零改动。
 */
public class TurnTcpChannel {
   private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("VoxLink-TurnTcp");
   private static final int FRAME_MAX = 2048;
   private static final int TCP_CONNECT_TIMEOUT_MS = 3000;
   private static final int SHIM_POLL_MS = 100;

   private final Socket tcp;
   private final DatagramSocket clientSocket;
   private final DatagramSocket shimSocket;
   private final InetSocketAddress clientAddr;
   private volatile boolean closed = false;
   private final Object writeLock = new Object();

   private TurnTcpChannel(Socket tcp, DatagramSocket client, DatagramSocket shim, InetSocketAddress clientAddr) {
      this.tcp = tcp;
      this.clientSocket = client;
      this.shimSocket = shim;
      this.clientAddr = clientAddr;
   }

   /**
    * 建立到节点的 TCP 通道。preferLocalPort>0 时 client 口复用原 UDP 端口，
    * 保住节点侧会话身份连续性（端口被抢则退回随机口）。
    */
   public static TurnTcpChannel open(String nodeHost, int nodePort, int preferLocalPort) throws IOException {
      Socket tcp = new Socket();
      try {
         tcp.connect(new InetSocketAddress(InetAddress.getByName(nodeHost), nodePort), TCP_CONNECT_TIMEOUT_MS);
         tcp.setTcpNoDelay(true);
      } catch (IOException e) {
         try {
            tcp.close();
         } catch (IOException ignored) {
         }
         throw e;
      }

      DatagramSocket client = new DatagramSocket(null);
      boolean reused = false;
      if (preferLocalPort > 0) {
         try {
            client.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), preferLocalPort));
            reused = true;
         } catch (IOException e) {
            LOGGER.debug("[TurnTcp] reuse port {} failed: {}", preferLocalPort, e.getMessage());
         }
      }
      if (!reused) {
         client.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
      }
      client.setSoTimeout(SHIM_POLL_MS);

      DatagramSocket shim = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
      shim.setSoTimeout(SHIM_POLL_MS);
      InetSocketAddress clientAddr = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), client.getLocalPort());

      TurnTcpChannel ch = new TurnTcpChannel(tcp, client, shim, clientAddr);
      Thread up = new Thread(ch::pumpUdpToTcp, "VoxLink-TurnTcp-Up");
      Thread down = new Thread(ch::pumpTcpToUdp, "VoxLink-TurnTcp-Down");
      up.setDaemon(true);
      down.setDaemon(true);
      up.start();
      down.start();
      LOGGER.info("[TurnTcp] channel up to {}:{} shim=:{} reusePort={}", nodeHost, nodePort, shim.getLocalPort(), reused);
      return ch;
   }

   public DatagramSocket clientSocket() {
      return this.clientSocket;
   }

   public int shimPort() {
      return this.shimSocket.getLocalPort();
   }

   /** shim 收包 → TCP 帧。任一方向断开即整体关闭（链路已死）。 */
   private void pumpUdpToTcp() {
      byte[] buf = new byte[FRAME_MAX];
      DatagramPacket p = new DatagramPacket(buf, buf.length);
      try {
         OutputStream out = this.tcp.getOutputStream();
         while (!this.closed) {
            try {
               this.shimSocket.receive(p);
            } catch (java.net.SocketTimeoutException e) {
               continue;
            }
            synchronized (this.writeLock) {
               out.write((p.getLength() >> 8) & 0xFF);
               out.write(p.getLength() & 0xFF);
               out.write(buf, 0, p.getLength());
               out.flush();
            }
         }
      } catch (IOException e) {
         if (!this.closed) {
            LOGGER.warn("[TurnTcp] uplink failed: {}", e.getMessage());
         }
      }
      close();
   }

   private void pumpTcpToUdp() {
      try {
         InputStream in = this.tcp.getInputStream();
         while (!this.closed) {
            int hi = in.read();
            int lo = hi < 0 ? -1 : in.read();
            if (hi < 0 || lo < 0) {
               throw new IOException("EOF");
            }
            int len = ((hi & 0xFF) << 8) | (lo & 0xFF);
            if (len == 0 || len > FRAME_MAX) {
               throw new IOException("bad frame len " + len);
            }
            byte[] pkt = new byte[len];
            int off = 0;
            while (off < len) {
               int n = in.read(pkt, off, len - off);
               if (n < 0) {
                  throw new IOException("EOF in frame");
               }
               off += n;
            }
            this.shimSocket.send(new DatagramPacket(pkt, len, this.clientAddr));
         }
      } catch (IOException e) {
         if (!this.closed) {
            LOGGER.warn("[TurnTcp] downlink failed: {}", e.getMessage());
         }
      }
      close();
   }

   /** 幂等关闭：TCP 死亡时 client 口随之关，rudp 收包循环据此退出。 */
   public void close() {
      if (this.closed) {
         return;
      }
      this.closed = true;
      try {
         this.tcp.close();
      } catch (IOException ignored) {
      }
      try {
         this.shimSocket.close();
      } catch (Exception ignored) {
      }
      try {
         this.clientSocket.close();
      } catch (Exception ignored) {
      }
   }
}
