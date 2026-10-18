package icu.wuhui.voxlink.network;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;

public class UdpPath {
   public final DatagramSocket socket;
   public volatile InetSocketAddress remoteAddress;
   public final UdpPath.Codec codec;
   public volatile long rxCount = 0L;
   public volatile long lastRxMs = 0L;

   public UdpPath(DatagramSocket socket, InetSocketAddress remoteAddress, UdpPath.Codec codec) {
      this.socket = socket;
      this.remoteAddress = remoteAddress;
      this.codec = codec;
   }

   public byte[] encode(byte[] frame) {
      return this.codec == null ? frame : this.codec.encode(frame);
   }

   public byte[] decode(byte[] packet, int len) {
      return this.codec == null ? (len > 0 ? packet : null) : this.codec.decode(packet, len);
   }

   public void send(byte[] rudpFrame) throws IOException {
      byte[] out = this.encode(rudpFrame);
      this.socket.send(new DatagramPacket(out, out.length, this.remoteAddress));
   }

   public interface Codec {
      byte[] encode(byte[] var1);

      byte[] decode(byte[] var1, int var2);
   }
}
