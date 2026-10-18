package icu.wuhui.voxlink.network;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;

/**
 * 传输层"路径"抽象：一对 (socket, 对端地址) 加可选的包封编解码器。
 * 直连路径 codec=null（rudp 帧裸跑）；TURN 路径 codec=TurnRelayClient.TurnPathCodec
 * （rudp 帧外套 TURN DATA 头，见 SPECS/turn-protocol-v1.md §1）。
 * 平滑切换的基础：同一 ReliableUdpTransport 会话下 primary/secondary 两条路径并存，
 * 双路径收包喂同一序号空间（去重由 nextExpectedSeq 天然完成），发送始终走 primary。
 */
public class UdpPath {
   public final DatagramSocket socket;
   public volatile InetSocketAddress remoteAddress;
   public final Codec codec;

   // 路径健康观测：BackgroundPunchMonitor 以收包计数/最近收包时间判定是否可升级为主路径
   public volatile long rxCount = 0L;
   public volatile long lastRxMs = 0L;

   public UdpPath(DatagramSocket socket, InetSocketAddress remoteAddress, Codec codec) {
      this.socket = socket;
      this.remoteAddress = remoteAddress;
      this.codec = codec;
   }

   public byte[] encode(byte[] frame) {
      return this.codec == null ? frame : this.codec.encode(frame);
   }

   /** 底层 UDP 包 → rudp 帧；不属于本路径协议的包返回 null。 */
   public byte[] decode(byte[] packet, int len) {
      return this.codec == null ? (len > 0 ? packet : null) : this.codec.decode(packet, len);
   }

   public void send(byte[] rudpFrame) throws IOException {
      byte[] out = this.encode(rudpFrame);
      this.socket.send(new DatagramPacket(out, out.length, this.remoteAddress));
   }

   /** 路径包封编解码器。实现必须无状态且线程安全。 */
   public interface Codec {
      /** rudp 帧 → 底层 UDP 包载荷 */
      byte[] encode(byte[] frame);

      /** 底层 UDP 包 → rudp 帧；格式不符返回 null */
      byte[] decode(byte[] packet, int len);
   }
}
