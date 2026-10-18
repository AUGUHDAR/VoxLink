import icu.wuhui.voxlink.network.P2PBridge;
import icu.wuhui.voxlink.network.RelayBridge;
import icu.wuhui.voxlink.network.ReliableUdpTransport;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * 玩家中继 v2 全链路验收实验（真类：mux RUDP + RelayBridge v2 + P2PBridge 子通道桥 + 载具热切）。
 * 单场景单进程（P2PBridge 为进程级单例，两会话互串）：
 *   java FullChainLab s1   —— 健康道: sym全链路会话 + 候选者自有会话(chan0) 零干扰
 *   java FullChainLab s2   —— 死道→热切: A桥连死端口, 3.5s后换道B(直连腿)+journal重放, 字节零丢失零重复
 */
public class FullChainLab {
   static final String LO = "127.0.0.1";

   public static void main(String[] args) throws Exception {
      int alivePort = startFakeMcServer();
      int deadPort = 47000;
      String mode = args.length > 0 ? args[0] : "s1";

      if (mode.equals("s1")) {
         Session s = new Session();
         s.setup(alivePort, alivePort);
         Thread sender = new Thread(() -> s.sendFrames(0, 250, 8), "sender");
         Thread echo = new Thread(() -> s.readEcho(250), "echo");
         echo.start(); sender.start();
         s.runOwnChan0();
         sender.join(60000); echo.join(60000); s.ownReader.join(20000);
         System.out.println("S1 回显: ok=" + s.ok + " bad=" + s.bad + " (期望250/0)");
         System.out.println("S1 候选者自有会话: ok=" + s.ownOk + " bad=" + s.ownBad + " (期望300/0)");
         boolean pass = s.ok == 250 && s.bad == 0 && s.ownOk == 300 && s.ownBad == 0;
         System.out.println(pass ? "[PASS] S1 全链路健康道+chan0互不干扰" : "[FAIL] S1");
         System.exit(pass ? 0 : 2);
      }

      // s2
      Session s = new Session();
      s.setup(deadPort, alivePort); // A桥连死端口, B桥(直连腿)连活端口
      Thread sender = new Thread(() -> s.sendFrames(0, 400, 8), "sender");
      Thread echo = new Thread(() -> s.readEcho(400), "echo");
      echo.start(); sender.start();
      Thread.sleep(3500);
      byte[] journal = P2PBridge.takeJoinerUplinkJournal();
      System.out.println("[S2] journal bytes=" + (journal == null ? "null" : journal.length));
      boolean swapped = P2PBridge.swapJoinerCarrier(s.symCarrierB, journal);
      System.out.println("[S2] swap=" + swapped);
      sender.join(60000);
      echo.join(60000);
      System.out.println("S2 换道回显: ok=" + s.ok + " bad=" + s.bad + " (期望400/0, 序号连续)");
      System.out.println("S2 下行标志: " + P2PBridge.hasJoinerDownlink());
      boolean pass = s.ok == 400 && s.bad == 0;
      System.out.println(pass ? "[PASS] S2 死道→热切→journal重放 字节零丢失零重复" : "[FAIL] S2");
      System.exit(pass ? 0 : 2);
   }

   static int startFakeMcServer() {
      try {
         ServerSocket mcServer = new ServerSocket(0, 50, InetSocketAddress.createUnresolved("127.0.0.1", 0).getAddress());
         int port = mcServer.getLocalPort();
         Thread acc = new Thread(() -> {
            while (true) {
               try {
                  Socket s = mcServer.accept();
                  s.setTcpNoDelay(true);
                  new Thread(() -> {
                     try (DataInputStream in = new DataInputStream(s.getInputStream());
                          DataOutputStream out = new DataOutputStream(s.getOutputStream())) {
                        while (true) {
                           int len = in.readInt();
                           if (len <= 0 || len > 65536) break;
                           byte[] body = new byte[len];
                           in.readFully(body);
                           out.writeInt(len); out.write(body); out.flush();
                        }
                     } catch (Exception ignored) {}
                  }, "fake-mc-conn").start();
               } catch (Exception e) { return; }
            }
         }, "fake-mc");
         acc.setDaemon(true);
         acc.start();
         return port;
      } catch (Exception e) {
         throw new RuntimeException(e);
      }
   }

   static class Session {
      ReliableUdpTransport hostTransport, candTransport, peerTransportA, symCarrierA, hostCarrierB, symCarrierB;
      Socket mcClient;
      DataOutputStream cliOut;
      DataInputStream cliIn;
      int ok = 0, bad = 0, ownOk = 0, ownBad = 0;
      int lastSeq = -1;
      Thread ownReader;

      void setup(int hostABridgeMcPort, int hostBBridgeMcPort) throws Exception {
         DatagramSocket hostSock = new DatagramSocket(new InetSocketAddress(LO, 0));
         DatagramSocket candSockA = new DatagramSocket(new InetSocketAddress(LO, 0));
         DatagramSocket candSockB = new DatagramSocket(new InetSocketAddress(LO, 0));
         DatagramSocket symSockA = new DatagramSocket(new InetSocketAddress(LO, 0));
         DatagramSocket hostSockB = new DatagramSocket(new InetSocketAddress(LO, 0));
         DatagramSocket symSockB = new DatagramSocket(new InetSocketAddress(LO, 0));

         hostTransport = new ReliableUdpTransport(hostSock, new InetSocketAddress(LO, candSockA.getLocalPort()));
         candTransport = new ReliableUdpTransport(candSockA, new InetSocketAddress(LO, hostSock.getLocalPort()));
         hostTransport.enableMux();
         candTransport.enableMux();
         hostTransport.start();
         candTransport.start();

         peerTransportA = new ReliableUdpTransport(candSockB, new InetSocketAddress(LO, symSockA.getLocalPort()));
         symCarrierA = new ReliableUdpTransport(symSockA, new InetSocketAddress(LO, candSockB.getLocalPort()));

         // 道B: sym ↔ host 直连腿(非mux, 主流承载 = TURN腿模型)
         hostCarrierB = new ReliableUdpTransport(hostSockB, new InetSocketAddress(LO, symSockB.getLocalPort()));
         symCarrierB = new ReliableUdpTransport(symSockB, new InetSocketAddress(LO, hostSockB.getLocalPort()));

         P2PBridge.startUdpHostBridgeForRelayedClient("symClient", hostTransport, 1, hostABridgeMcPort, () -> {});
         P2PBridge.startUdpHostBridgeForClient("symClientB", hostCarrierB, hostBBridgeMcPort, () -> {});
         hostCarrierB.start();

         RelayBridge.RelayLeg hostLeg = new RelayBridge.RelayLeg() {
            @Override public InputStream input() { return candTransport.getSubstreamInput(1); }
            @Override public OutputStream output() { return candTransport.getSubstreamOutput(1); }
            @Override public void close() { try { candTransport.closeSubstream(1); } catch (Exception ignored) {} }
         };
         peerTransportA.start();
         RelayBridge.getInstance(null).startRelay("muxrelay-" + symSockA.getPort(), "host-chan1", "symClient", hostLeg, RelayBridge.legOf(peerTransportA));

         int localPort = P2PBridge.startUdpJoinerBridge(symCarrierA);
         if (localPort <= 0) throw new IllegalStateException("joiner bridge start failed");
         mcClient = new Socket("127.0.0.1", localPort);
         mcClient.setTcpNoDelay(true);
         cliOut = new DataOutputStream(mcClient.getOutputStream());
         cliIn = new DataInputStream(mcClient.getInputStream());
         P2PBridge.startJoinerJournal();
      }

      void sendFrames(int from, int to, int intervalMs) {
         try {
            for (int i = from; i < to; i++) {
               byte[] pad = new byte[i % 7 == 0 ? 3000 : 64];
               byte[] head = ("f:" + i + "|").getBytes("UTF-8");
               byte[] body = new byte[head.length + pad.length];
               System.arraycopy(head, 0, body, 0, head.length);
               cliOut.writeInt(body.length);
               cliOut.write(body);
               cliOut.flush();
               Thread.sleep(intervalMs);
            }
         } catch (Exception e) {
            System.out.println("sender exit: " + e);
         }
      }

      void readEcho(int expect) {
         try {
            for (int i = 0; i < expect; i++) {
               int len = cliIn.readInt();
               if (len <= 0 || len > 65536) { bad++; continue; }
               byte[] body = new byte[len];
               cliIn.readFully(body);
               String s = new String(body, 0, Math.min(body.length, 40), "UTF-8");
               if (!s.startsWith("f:")) { bad++; continue; }
               try {
                  int seq = Integer.parseInt(s.substring(2, s.indexOf('|')));
                  if (seq != lastSeq + 1 && lastSeq >= 0) {
                     System.out.println("[ECHO] 序号跳变/重复 " + lastSeq + " -> " + seq);
                     bad++;
                  }
                  lastSeq = seq;
                  ok++;
               } catch (Exception pe) {
                  bad++;
               }
            }
         } catch (Exception e) {
            System.out.println("echo reader exit @" + ok + ": " + e);
         }
      }

      void runOwnChan0() {
         ownReader = new Thread(() -> {
            try {
               DataInputStream in = new DataInputStream(candTransport.getInputStream());
               for (int i = 0; i < 300; i++) {
                  int len = in.readInt();
                  byte[] b = new byte[len]; in.readFully(b);
                  if (new String(b, 0, 2, "UTF-8").equals("c:")) ownOk++; else ownBad++;
               }
            } catch (Exception ignored) {}
         }, "ownReader");
         Thread ownWriter = new Thread(() -> {
            try {
               DataOutputStream out = new DataOutputStream(candTransport.getOutputStream());
               for (int i = 0; i < 300; i++) {
                  byte[] b = ("c:" + i).getBytes("UTF-8");
                  out.writeInt(b.length); out.write(b); out.flush();
                  Thread.sleep(10);
               }
            } catch (Exception ignored) {}
         });
         Thread hostOwnWriter = new Thread(() -> {
            try {
               DataOutputStream out = new DataOutputStream(hostTransport.getOutputStream());
               for (int i = 0; i < 300; i++) {
                  byte[] b = ("c:" + i).getBytes("UTF-8");
                  out.writeInt(b.length); out.write(b); out.flush();
                  Thread.sleep(10);
               }
            } catch (Exception ignored) {}
         });
         ownReader.start(); ownWriter.start(); hostOwnWriter.start();
      }
   }
}
