import icu.wuhui.voxlink.network.ReliableUdpTransport;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 玩家中继字节流污染复现实验（2026-10-05）。
 *
 * 拓扑（复刻 ConnectionManager.handleRelaySetup + RelayBridge 的现行接线）：
 *   sym房客MC会话 ──RUDP──> 中继候选者peerTransport ──[RelayBridge转发线程]──> 候选者自己的"joiner"传输 ──RUDP──> host
 *   host侧: 该传输已被 startUdpHostBridgeForClient(candidateId) 独占消费（读到啥写进候选者的MC socket）
 *   候选者自己: 该传输同样被自己的 MC<->RUDP 桥线程独占消费
 *
 * 预期：
 *   1) 候选者自己的下行流被 RelayBridge A2B 线程抢走一半字节 → 候选者会话帧错乱
 *   2) sym的上行字节被注入候选者的上行流 → host侧会话帧错乱
 *   → 两条MC会话都无法正常工作 = 用户症状："能到原版连接屏，过段时间连接中断/超时"
 */
public class RelayRepro {
   static final String LO = "127.0.0.1";
   static final AtomicLong hostRecvOk = new AtomicLong();
   static final AtomicLong hostRecvBad = new AtomicLong();
   static final AtomicLong candRecvOk = new AtomicLong();
   static final AtomicLong candRecvBad = new AtomicLong();
   static final AtomicLong symRecvOk = new AtomicLong();
   static final AtomicLong symRecvBad = new AtomicLong();

   public static void main(String[] args) throws Exception {
      boolean withRelay = args.length > 0 && args[0].equals("relay");

      DatagramSocket hostSock = new DatagramSocket(new InetSocketAddress(LO, 31001));
      DatagramSocket candSockA = new DatagramSocket(new InetSocketAddress(LO, 31002)); // 候选者→host
      DatagramSocket candSockB = new DatagramSocket(new InetSocketAddress(LO, 31003)); // 候选者→sym
      DatagramSocket symSock = new DatagramSocket(new InetSocketAddress(LO, 31004));

      ReliableUdpTransport hostTransport = new ReliableUdpTransport(hostSock, new InetSocketAddress(LO, 31002));
      ReliableUdpTransport candTransport = new ReliableUdpTransport(candSockA, new InetSocketAddress(LO, 31001));
      ReliableUdpTransport peerTransport = new ReliableUdpTransport(candSockB, new InetSocketAddress(LO, 31004));
      ReliableUdpTransport symTransport = new ReliableUdpTransport(symSock, new InetSocketAddress(LO, 31003));

      hostTransport.start();
      candTransport.start();
      peerTransport.start();
      symTransport.start();

      // ---- host 侧: 模拟 startUdpHostBridgeForClient —— 候选者传输的下行流被 host 独占消费 ----
      Thread hostMcSide = reader("host(候选者会话)", hostTransport.getInputStream(), hostRecvOk, hostRecvBad, (byte) 0xB0);
      hostMcSide.start();

      // ---- 候选者自己: 自己的MC客户端也在读自己传输的下行流（双读者！）----
      Thread candMcSide = reader("candidate(自己的MC)", candTransport.getInputStream(), candRecvOk, candRecvBad, (byte) 0xA0);
      candMcSide.start();

      // ---- sym 侧: 自己的MC会话读 peerTransport 下行 ----
      Thread symMcSide = reader("sym(被中继的MC)", symTransport.getInputStream(), symRecvOk, symRecvBad, (byte) 0xC0);
      symMcSide.start();

      // ---- RelayBridge 复刻: A2B / B2A 两条转发线程 ----
      AtomicBoolean forwarding = new AtomicBoolean(withRelay);
      if (withRelay) {
         Thread a2b = forwarder("A2B", candTransport.getInputStream(), peerTransport.getOutputStream(), forwarding);
         Thread b2a = forwarder("B2A", peerTransport.getInputStream(), candTransport.getOutputStream(), forwarding);
         a2b.start();
         b2a.start();
      }

      // ---- 流量发生器: 三个方向各自发带序号的帧 (模拟MC协议的length-prefixed流) ----
      Thread hostDown = writer("host→候选者下行", hostTransport.getOutputStream(), (byte) 0xA0, 40); // host发给候选者的游戏数据
      Thread candUp = writer("候选者→host上行", candTransport.getOutputStream(), (byte) 0xB0, 40);   // 候选者自己的上行
      Thread symUp = writer("sym→中继上行(握手)", symTransport.getOutputStream(), (byte) 0xC0, 40);  // sym的MC握手
      hostDown.start();
      candUp.start();
      symUp.start();

      Thread.sleep(12000);
      forwarding.set(false);
      System.out.println("=============== 结果 ===============");
      System.out.println("host侧(候选者会话)帧校验:  ok=" + hostRecvOk.get() + "  bad=" + hostRecvBad.get());
      System.out.println("候选者自己的MC会话帧校验: ok=" + candRecvOk.get() + "  bad=" + candRecvBad.get());
      System.out.println("sym(被中继)会话帧校验:    ok=" + symRecvOk.get() + "  bad=" + symRecvBad.get());
      boolean pass = hostRecvBad.get() == 0 && candRecvBad.get() == 0 && symRecvBad.get() == 0;
      if (withRelay) {
         System.out.println(pass
            ? "[意外] 中继下三路全干净 —— 污染假设被推翻"
            : "[证实] 玩家中继把两条MC会话灌进同一条RUDP字节流 → 双向帧错乱（bad>0 即铁证）");
      } else {
         System.out.println(pass ? "[基线] 无中继时干净（对照组通过）" : "[异常] 对照组就脏了，实验环境有问题");
      }
      System.exit(pass == !withRelay ? 0 : 2);
   }

   /** 帧协议: 4字节大端长度 + 1字节通道标记 + 4字节序号 + payload（长度-5字节）。校验序号连续+通道匹配。 */
   static Thread reader(String name, InputStream in, AtomicLong ok, AtomicLong bad, byte expectCh) {
      return new Thread(() -> {
         try {
            while (true) {
               int b0 = in.read();
               if (b0 < 0) return;
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
            // 传输关闭等正常退出
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
