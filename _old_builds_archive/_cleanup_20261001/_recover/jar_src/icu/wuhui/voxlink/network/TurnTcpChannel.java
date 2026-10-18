package icu.wuhui.voxlink.network;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TurnTcpChannel {
   private static final Logger LOGGER = LoggerFactory.getLogger("VoxLink-TurnTcp");
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

   public static TurnTcpChannel open(String nodeHost, int nodePort, int preferLocalPort) throws IOException {
      Socket tcp = new Socket();

      try {
         tcp.connect(new InetSocketAddress(InetAddress.getByName(nodeHost), nodePort), 3000);
         tcp.setTcpNoDelay(true);
      } catch (IOException var13) {
         try {
            tcp.close();
         } catch (IOException var11) {
         }

         throw var13;
      }

      DatagramSocket client = new DatagramSocket(null);
      boolean reused = false;
      if (preferLocalPort > 0) {
         try {
            client.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), preferLocalPort));
            reused = true;
         } catch (IOException var12) {
            LOGGER.debug("[TurnTcp] reuse port {} failed: {}", preferLocalPort, var12.getMessage());
         }
      }

      if (!reused) {
         client.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
      }

      client.setSoTimeout(100);
      DatagramSocket shim = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
      shim.setSoTimeout(100);
      InetSocketAddress clientAddr = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), client.getLocalPort());
      TurnTcpChannel ch = new TurnTcpChannel(tcp, client, shim, clientAddr);
      Thread up = new Thread(ch::pumpUdpToTcp, "VoxLink-TurnTcp-Up");
      Thread down = new Thread(ch::pumpTcpToUdp, "VoxLink-TurnTcp-Down");
      up.setDaemon(true);
      down.setDaemon(true);
      up.start();
      down.start();
      LOGGER.info("[TurnTcp] channel up to {}:{} shim=:{} reusePort={}", new Object[]{nodeHost, nodePort, shim.getLocalPort(), reused});
      return ch;
   }

   public DatagramSocket clientSocket() {
      return this.clientSocket;
   }

   public int shimPort() {
      return this.shimSocket.getLocalPort();
   }

   private void pumpUdpToTcp() {
      byte[] buf = new byte[2048];
      DatagramPacket p = new DatagramPacket(buf, buf.length);

      try {
         OutputStream out = this.tcp.getOutputStream();

         while (!this.closed) {
            try {
               this.shimSocket.receive(p);
            } catch (SocketTimeoutException var7) {
               continue;
            }

            synchronized (this.writeLock) {
               out.write(p.getLength() >> 8 & 0xFF);
               out.write(p.getLength() & 0xFF);
               out.write(buf, 0, p.getLength());
               out.flush();
            }
         }
      } catch (IOException var8) {
         if (!this.closed) {
            LOGGER.warn("[TurnTcp] uplink failed: {}", var8.getMessage());
         }
      }

      this.close();
   }

   private void pumpTcpToUdp() {
      try {
         InputStream in = this.tcp.getInputStream();

         while (!this.closed) {
            int hi = in.read();
            int lo = hi < 0 ? -1 : in.read();
            if (hi >= 0 && lo >= 0) {
               int len = (hi & 0xFF) << 8 | lo & 0xFF;
               if (len != 0 && len <= 2048) {
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
                  continue;
               }

               throw new IOException("bad frame len " + len);
            }

            throw new IOException("EOF");
         }
      } catch (IOException var8) {
         if (!this.closed) {
            LOGGER.warn("[TurnTcp] downlink failed: {}", var8.getMessage());
         }
      }

      this.close();
   }

   public void close() {
      if (!this.closed) {
         this.closed = true;

         try {
            this.tcp.close();
         } catch (IOException var4) {
         }

         try {
            this.shimSocket.close();
         } catch (Exception var3) {
         }

         try {
            this.clientSocket.close();
         } catch (Exception var2) {
         }
      }
   }
}
