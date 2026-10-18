import icu.wuhui.voxlink.network.ReliableUdpTransport;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 玩家中继 v2（mux 子通道）验证实验。
 * 拓扑: sym ──RUDP──> 候选者peerTransport ──[RelayBridge: substream(1) ↔ peerTransport]──> 候选者↔host主传输(chan0=候选者自己, chan1=被中继会话)
 * host侧: chan0 → 候选者的MC会话; chan1 → sym的MC会话(新MC socket)
 */
public class RelayV2 {
   static final String LO = "127.0.0.1";
   static final AtomicLong hostCandOk = new AtomicLong(), hostCandBad = new AtomicLong();
   static final AtomicLong candOk = new AtomicLong(), candBad = new AtomicLong();
   static final AtomicLong hostSymOk = new AtomicLong(), hostSymBad = new AtomicLong();
   static final AtomicLong symOk = new AtomicLong(), symBad = new AtomicLong();

   public static void main(String[] args) throws Exception {
      boolean v2 = args.length == 0 || !args[0].equals("v1broken");

      DatagramSocket hostSock = new DatagramSocket(new InetSocketAddress(LO, 32001));
      DatagramSocket candSockA = new DatagramSocket(new InetSocketAddress(LO, 32002));
      DatagramSocket candSockB = new DatagramSocket(new InetSocketAddress(LO, 32003));
      DatagramSocket symSock = new DatagramSocket(new InetSocketAddress(LO, 32004));

      ReliableUdpTransport hostTransport = new ReliableUdpTransport(hostSock, new InetSocketAddress(LO, 32002));
      ReliableUdpTransport candTransport = new ReliableUdpTransport(candSockA, new InetSocketAddress(LO, 32001));
      ReliableUdpTransport peerTransport = new ReliableUdpTransport(candSockB, new InetSocketAddress(LO, 32004));
      ReliableUdpTransport symTransport = new ReliableUdpTransport(symSock, new InetSocketAddress(LO, 32003));

      if (v2) {
         // 能力协商成功 → 双端 mux
         hostTransport.enableMux();
         candTransport.enableMux();
      }
      hostTransport.start();
      candTransport.start();
      peerTransport.start();
      symTransport.start();

      // host侧: chan0=候选者会话, chan1=sym会话(独立"MC socket")
      Thread hostCandSide = reader("host(chan0候选者)", hostTransport.getInputStream(), hostCandOk, hostCandBad, (byte) 0xB0);
      Thread hostSymSide = v2
         ? reader("host(chan1 sym)", hostTransport.getSubstreamInput(1), hostSymOk, hostSymBad, (byte) 0xC0)
         : reader("host(chan0候选者-旧坏接线)", hostTransport.getInputStream(), hostSymOk, hostSymBad, (byte) 0xC0);
      hostCandSide.start();
      hostSymSide.start();

      // 候选者自己: chan0 读自己下行
      Thread candMcSide = reader("candidate(自己MC)", candTransport.getInputStream(), candOk, candBad, (byte) 0xA0);
      candMcSide.start();

      // sym: 读自己的下行
      Thread symMcSide = reader("sym(被中继MC)", symTransport.getInputStream(), symOk, symBad, (byte) 0xD0);
      symMcSide.start();

      // RelayBridge: v2 = substream(1) ↔ peerTransport; v1broken = 旧坏接线(主传输流)
      AtomicBoolean forwarding = new AtomicBoolean(true);
      InputStream relayIn = v2 ? candTransport.getSubstreamInput(1) : candTransport.getInputStream();
      OutputStream relayOut = v2 ? candTransport.getSubstreamOutput(1) : candTransport.getOutputStream();
      Thread a2b = forwarder("A2B", relayIn, peerTransport.getOutputStream(), forwarding);
      Thread b2a = forwarder("B2A", peerTransport.getInputStream(), relayOut, forwarding);
      a2b.start();
      b2a.start();

      // 流量: host→候选者下行(0xA0), 候选者→host上行(0xB0), host→sym下行(0xD0 经chan1), sym→host上行(0xC0)
      Thread hostDown = writer("host→候选者", hostTransport.getOutputStream(), (byte) 0xA0, 40);
      Thread candUp = writer("候选者→host", candTransport.getOutputStream(), (byte) 0xB0, 40);
      Thread hostSymDown = v2
         ? writer("host→sym(chan1)", hostTransport.getSubstreamOutput(1), (byte) 0xD0, 40)
         : writer("host→sym(混入chan0)", hostTransport.getOutputStream(), (byte) 0xD0, 40);
      Thread symUp = writer("sym→中继", symTransport.getOutputStream(), (byte) 0xC0, 40);
      hostDown.start(); candUp.start(); hostSymDown.start(); symUp.start();

      Thread.sleep(12000);
      forwarding.set(false);
      // 关闭子通道验证EOF
      if (v2) { try { candTransport.closeSubstream(1); } catch (Exception ignored) {} }
      Thread.sleep(500);

      System.out.println("=============== 结果 ===============");
      System.out.println("host侧 候选者会话(chan0): ok=" + hostCandOk.get() + " bad=" + hostCandBad.get());
      System.out.println("host侧 sym会话:          ok=" + hostSymOk.get() + " bad=" + hostSymBad.get());
      System.out.println("候选者自己会话:          ok=" + candOk.get() + " bad=" + candBad.get());
      System.out.println("sym会话:                 ok=" + symOk.get() + " bad=" + symBad.get());
      boolean clean = hostCandBad.get() == 0 && hostSymBad.get() == 0 && candBad.get() == 0 && symBad.get() == 0;
      boolean flowing = hostCandOk.get() > 200 && hostSymOk.get() > 200 && candOk.get() > 200 && symOk.get() > 200;
      System.out.println(v2
         ? (clean && flowing ? "[PASS] v2 mux子通道: 四路全干净全通——玩家中继串流病根治" : "[FAIL] v2 仍有污染或断流")
         : (clean ? "[意外] v1也干净?" : "[基线] v1坏接线复现污染(对照)"));
      System.exit(v2 && clean && flowing ? 0 : 2);
   }

   static Thread reader(String name, InputStream in, AtomicLong ok, AtomicLong bad, byte expectCh) {
      return new Thread(() -> {
         try {
            while (true) {
               int b0 = in.read();
               if (b0 < 0) { System.out.println("reader " + name + " EOF"); return; }
               int b1 = in.read(); int b2 = in.read(); int b3 = in.read();
               if (b1 < 0 || b2 < 0 || b3 < 0) return;
               int len = (b0 << 24) | (b1 << 16) | (b2 << 8) | b3;
               if (len < 5 || len > 65536) { bad.incrementAndGet(); continue; }
               byte[] body = new byte[len];
               int off = 0;
               while (off < len) {
                  int n = in.read(body, off, len - off);
                  if (n < 0) return;
                  off += n;
               }
               byte ch = body[0];
               int seq = ((body[1] & 255) << 24) | ((body[2] & 255) << 16) | ((body[3] & 255) << 8) | (body[4] & 255);
               if (ch == expectCh && seq >= 0) ok.incrementAndGet(); else bad.incrementAndGet();
            }
         } catch (Exception e) {
            System.out.println("reader " + name + " exit: " + e);
         }
      }, "reader-" + name);
   }

   static Thread writer(String name, OutputStream out, byte ch, int intervalMs) {
      return new Thread(() -> {
         try {
            for (int seq = 0; seq < 4000; seq++) {
               int len = 24;
               byte[] frame = new byte[4 + len];
               frame[0] = (byte) (len >> 24); frame[1] = (byte) (len >> 16); frame[2] = (byte) (len >> 8); frame[3] = (byte) len;
               frame[4] = ch;
               frame[5] = (byte) (seq >> 24); frame[6] = (byte) (seq >> 16); frame[7] = (byte) (seq >> 8); frame[8] = (byte) seq;
               out.write(frame);
               out.flush();
               Thread.sleep(intervalMs);
            }
         } catch (Exception e) {
            // 正常退出
         }
      }, "writer-" + name);
   }

   static Thread forwarder(String name, InputStream in, OutputStream out, AtomicBoolean forwarding) {
      return new Thread(() -> {
         byte[] buf = new byte[65536];
         try {
            while (forwarding.get()) {
               int n = in.read(buf);
               if (n > 0) out.write(buf, 0, n);
               else if (n < 0) break;
            }
         } catch (Exception e) {
            // 忽略
         }
      }, "relay-" + name);
   }
}
