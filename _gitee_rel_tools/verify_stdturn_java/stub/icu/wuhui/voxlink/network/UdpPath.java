package icu.wuhui.voxlink.network;

// 桩：只要 Codec 接口形状与生产一致
public class UdpPath {
   public interface Codec {
      byte[] encode(byte[] frame);
      byte[] decode(byte[] packet, int len);
   }
}
