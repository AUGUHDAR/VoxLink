import icu.wuhui.voxlink.network.ReliableUdpTransport;
import java.io.*;
import java.net.*;
import java.util.concurrent.atomic.*;

public class DebugDump {
   public static void main(String[] a) throws Exception {
      String LO = "127.0.0.1";
      DatagramSocket hostSock = new DatagramSocket(new InetSocketAddress(LO, 33001));
      DatagramSocket candSock = new DatagramSocket(new InetSocketAddress(LO, 33002));
      ReliableUdpTransport host = new ReliableUdpTransport(hostSock, new InetSocketAddress(LO, 33002));
      ReliableUdpTransport cand = new ReliableUdpTransport(candSock, new InetSocketAddress(LO, 33001));
      host.enableMux(); cand.enableMux();
      host.start(); cand.start();
      // 双向chan0+chan1小帧
      Thread w1 = new Thread(() -> { try { for (int i=0;i<200;i++){ host.getOutputStream().write(frame((byte)0xA0,i)); Thread.sleep(20);} } catch(Exception e){} });
      Thread w2 = new Thread(() -> { try { for (int i=0;i<200;i++){ host.getSubstreamOutput(1).write(frame((byte)0xC0,i)); Thread.sleep(20);} } catch(Exception e){} });
      Thread w3 = new Thread(() -> { try { for (int i=0;i<200;i++){ cand.getOutputStream().write(frame((byte)0xB0,i)); Thread.sleep(20);} } catch(Exception e){} });
      w1.start(); w2.start(); w3.start();
      // host侧读: 主流+子流
      readAll("host-chan0", host.getInputStream());
      readAll("host-chan1", host.getSubstreamInput(1));
      readAll("cand-chan0", cand.getInputStream());
      Thread.sleep(6000);
      System.out.println("done");
      System.exit(0);
   }
   static byte[] frame(byte ch, int seq) {
      byte[] f = new byte[28];
      f[0]=0; f[1]=0; f[2]=0; f[3]=24; f[4]=ch;
      f[5]=(byte)(seq>>24); f[6]=(byte)(seq>>16); f[7]=(byte)(seq>>8); f[8]=(byte)seq;
      return f;
   }
   static void readAll(String name, InputStream in) {
      new Thread(() -> {
         try {
            DataInputStream di = new DataInputStream(in);
            int ok=0, bad=0;
            while (true) {
               int len = di.readInt();
               if (len!=24) { bad++; if(bad<5) System.out.println(name+" BADLEN "+len+" ok="+ok); di.skipBytes(len>0&&len<65536?len:0); continue; }
               byte ch=(byte)di.read();
               int seq=di.readInt();
               byte[] pad=new byte[19]; di.readFully(pad);
               ok++;
            }
         } catch(Exception e){ System.out.println(name+" reader exit "+e); }
      }, name).start();
   }
}
