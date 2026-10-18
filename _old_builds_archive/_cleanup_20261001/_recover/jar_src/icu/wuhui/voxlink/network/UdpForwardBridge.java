package icu.wuhui.voxlink.network;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
               socket = new DatagramSocket();
            } else {
               socket = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            }

            UdpForwardBridge.PortBridge pb = new UdpForwardBridge.PortBridge(socket, port);
            this.bridges.add(pb);
            pb.start();
            LOGGER.info("[UdpForward] voice relay {} on loopback:{}", hostSide ? "host-relay" : "bound", port);
         } catch (IOException var10) {
            LOGGER.warn("[UdpForward] skip port {} ({}): {}", new Object[]{port, hostSide ? "relay" : "bind", var10.getMessage()});
         }
      }

      if (!this.bridges.isEmpty()) {
         this.transport.setOnVoiceData(envelope -> {
            if (envelope != null && envelope.length >= 2 && !this.closed.get()) {
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
      final AtomicBoolean running;
      volatile InetSocketAddress localService;

      PortBridge(DatagramSocket socket, int port) {
         Objects.requireNonNull(UdpForwardBridge.this);
         super();
         this.running = new AtomicBoolean(true);
         this.socket = socket;
         this.port = port;
      }

      void start() {
         Thread t = new Thread(this::receiveLoop, "VoxLink-UdpForward-" + this.port);
         t.setDaemon(true);
         t.start();
      }

      private void receiveLoop() {
         byte[] buf = new byte[1400];
         DatagramPacket packet = new DatagramPacket(buf, buf.length);

         while (this.running.get() && !this.socket.isClosed()) {
            try {
               this.socket.receive(packet);
               if (packet.getLength() < 1398) {
                  this.localService = new InetSocketAddress(packet.getAddress(), packet.getPort());
                  byte[] envelope = new byte[2 + packet.getLength()];
                  UdpForwardBridge.writeUint16(envelope, 0, this.port);
                  System.arraycopy(packet.getData(), packet.getOffset(), envelope, 2, packet.getLength());
                  UdpForwardBridge.this.transport.sendVoice(envelope);
               }
            } catch (IOException var4) {
               if (this.running.get() && !this.socket.isClosed()) {
                  UdpForwardBridge.LOGGER.debug("[UdpForward] recv loop error on port {}: {}", this.port, var4.getMessage());
               }
            }
         }
      }

      void deliver(byte[] envelope) {
         int payloadLen = envelope.length - 2;
         byte[] payload = new byte[payloadLen];
         System.arraycopy(envelope, 2, payload, 0, payloadLen);
         InetSocketAddress target = this.localService;
         if (target == null) {
            target = new InetSocketAddress(InetAddress.getLoopbackAddress(), this.port);
         }

         try {
            this.socket.send(new DatagramPacket(payload, payload.length, target));
         } catch (IOException var6) {
            UdpForwardBridge.LOGGER.debug("[UdpForward] deliver failed on port {}: {}", this.port, var6.getMessage());
         }
      }

      @Override
      public void close() {
         this.running.set(false);

         try {
            this.socket.close();
         } catch (Exception var2) {
         }
      }
   }
}
