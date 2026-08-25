package icu.wuhui.voxlink.network;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 通用 UDP 转发桥：把"本地局域网服务器端口(如 25565 / 语音 24454)的 UDP"经 VoxLink 已打通的
 * ReliableUdp 数据面 best-effort 双向转发到对端对应端口。纯兼容，无此服务零影响，不打新洞。
 * host/joiner 对称性：
 *  - joiner(hostSide=false)：绑定 loopback:port 接收本地客户端(如 voice-chat)发包并中继；
 *  - host(hostSide=true) ：不绑端口(本机语言/服务器已占用)，用出站中继 socket 与本机服务对话。
 */
public class UdpForwardBridge implements AutoCloseable {
   private static final Logger LOGGER = LoggerFactory.getLogger("voxlink-udp-fwd");
   private static final int MAX_VOICE_PAYLOAD = 1400;
   private static final int PORT_HEADER_LEN = 2;
   private final ReliableUdpTransport transport;
   private final boolean hostSide;
   private final List<UdpForwardBridge.PortBridge> bridges = new CopyOnWriteArrayList<>();
   private final AtomicBoolean closed = new AtomicBoolean(false);

   public UdpForwardBridge(ReliableUdpTransport transport, boolean hostSide, int... localPorts) {
      this.transport = transport;
      this.hostSide = hostSide;
      for (int port : localPorts) {
         try {
            DatagramSocket socket;
            if (hostSide) {
               // host 出站中继：不绑服务端口，仅建一个临时 socket 与本机服务对话
               socket = new DatagramSocket();
            } else {
               socket = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            }
            UdpForwardBridge.PortBridge pb = new UdpForwardBridge.PortBridge(socket, port);
            this.bridges.add(pb);
            pb.start();
            LOGGER.info("[UdpForward] voice relay {} on loopback:{}", hostSide ? "host-relay" : "bound", port);
         } catch (IOException e) {
            LOGGER.warn("[UdpForward] skip port {} ({}): {}", port, hostSide ? "relay" : "bind", e.getMessage());
         }
      }

      if (!this.bridges.isEmpty()) {
         this.transport.setOnVoiceData(envelope -> {
            if (envelope != null && envelope.length >= PORT_HEADER_LEN && !this.closed.get()) {
               int destPort = this.readUint16(envelope, 0);
               for (UdpForwardBridge.PortBridge b : this.bridges) {
                  if (b.port == destPort) {
                     b.deliver(envelope);
                     break;
                  }
               }
            }
         });
      }
   }

   public boolean isActive() {
      return !this.bridges.isEmpty();
   }

   @Override
   public void close() {
      if (this.closed.compareAndSet(false, true)) {
         this.transport.setOnVoiceData(null);
         for (UdpForwardBridge.PortBridge b : this.bridges) {
            b.close();
         }
         LOGGER.info("[UdpForward] voice relay closed");
      }
   }

   private int readUint16(byte[] buf, int offset) {
      return (buf[offset] & 0xFF) << 8 | buf[offset + 1] & 0xFF;
   }

   private static void writeUint16(byte[] buf, int offset, int value) {
      buf[offset] = (byte)(value >> 8);
      buf[offset + 1] = (byte)value;
   }

   private class PortBridge implements AutoCloseable {
      final DatagramSocket socket;
      final int port;
      final AtomicBoolean running = new AtomicBoolean(true);
      // hostSide：记录最近给本机服务发过包的地址(即本机服务端), 回包写回它
      volatile InetSocketAddress localService;

      PortBridge(DatagramSocket socket, int port) {
         this.socket = socket;
         this.port = port;
      }

      void start() {
         Thread t = new Thread(this::receiveLoop, "VoxLink-UdpForward-" + this.port);
         t.setDaemon(true);
         t.start();
      }

      private void receiveLoop() {
         byte[] buf = new byte[MAX_VOICE_PAYLOAD];
         DatagramPacket packet = new DatagramPacket(buf, buf.length);
         while (this.running.get() && !this.socket.isClosed()) {
            try {
               this.socket.receive(packet);
               if (packet.getLength() >= MAX_VOICE_PAYLOAD - PORT_HEADER_LEN) {
                  continue;
               }
               this.localService = new InetSocketAddress(packet.getAddress(), packet.getPort());
               byte[] envelope = new byte[PORT_HEADER_LEN + packet.getLength()];
               UdpForwardBridge.writeUint16(envelope, 0, this.port);
               System.arraycopy(packet.getData(), packet.getOffset(), envelope, PORT_HEADER_LEN, packet.getLength());
               UdpForwardBridge.this.transport.sendVoice(envelope);
            } catch (IOException e) {
               if (this.running.get() && !this.socket.isClosed()) {
                  LOGGER.debug("[UdpForward] recv loop error on port {}: {}", this.port, e.getMessage());
               }
            }
         }
      }

      // 收到对端 VOICE 帧 → 交给本地：host 写回本机服务，joiner 写回本地客户端
      void deliver(byte[] envelope) {
         int payloadLen = envelope.length - PORT_HEADER_LEN;
         byte[] payload = new byte[payloadLen];
         System.arraycopy(envelope, PORT_HEADER_LEN, payload, 0, payloadLen);
         // 清理死三元表达式：原 `hostSide ? this.localService : this.localService` 两分支相同
         InetSocketAddress target = this.localService;
         if (target == null) {
            target = new InetSocketAddress(InetAddress.getLoopbackAddress(), this.port);
         }
         try {
            this.socket.send(new DatagramPacket(payload, payload.length, target));
         } catch (IOException e) {
            LOGGER.debug("[UdpForward] deliver failed on port {}: {}", this.port, e.getMessage());
         }
      }

      @Override
      public void close() {
         this.running.set(false);
         try {
            this.socket.close();
         } catch (Exception e) {
         }
      }
   }
}