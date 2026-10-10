package icu.wuhui.voxlink.network;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 玩家中继 v2 转发器（2026-10-05 重写，根治串流架构病）。
 *
 * v1 病根：候选者复用自己连 host 的传输裸流转发被中继会话 → 两条 MC 会话字节
 * 交错进同一条有序字节流 + InputStream 双读者互抢字节（1.0.x 起从未真正可用，
 * 症状=被中继房客停在"正在连接到服务器"直至超时）。本地复现实验实证后废除。
 *
 * v2：被中继会话走候选者↔host 传输的 mux 子通道（chan 1..7，见 ReliableUdpTransport
 * deliverPayload），与候选者自有会话（chan 0）字节级隔离；本类只做"流↔流"转发，
 * 不关心流由哪条通道承载。任一方向 EOF/异常即整个会话收尾，双腿互关。
 */
public class RelayBridge {
   private static final Logger LOGGER = LoggerFactory.getLogger("VoxLink-Relay");
   private static final int RELAY_BUFFER_SIZE = 65536;
   private final ScheduledExecutorService scheduler;
   private final Map<String, RelayBridge.RelaySession> activeRelays = new ConcurrentHashMap<>();
   private static volatile RelayBridge instance;

   /** 一条被转发的腿：给出来往流与关闭动作（子通道腿关闭=closeSubstream，不动父传输）。 */
   public interface RelayLeg {
      InputStream input();

      OutputStream output();

      void close();
   }

   /** 整传输腿（对被中继房客的专打传输）。 */
   public static RelayLeg legOf(ReliableUdpTransport transport) {
      return new RelayLeg() {
         @Override
         public InputStream input() {
            return transport.getInputStream();
         }

         @Override
         public OutputStream output() {
            return transport.getOutputStream();
         }

         @Override
         public void close() {
            try {
               transport.close();
            } catch (Exception e) {
            }
         }
      };
   }

   public static RelayBridge getInstance(ScheduledExecutorService scheduler) {
      if (instance == null) {
         synchronized (RelayBridge.class) {
            if (instance == null) {
               instance = new RelayBridge(scheduler);
            }
         }
      }

      return instance;
   }

   private RelayBridge(ScheduledExecutorService scheduler) {
      this.scheduler = scheduler;
   }

   /**
    * 建立一条中继转发会话：A 腿与 B 腿双向泵流。
    * 任一方向读到 EOF/抛错即停止整个会话并关闭双腿；重复 key 静默忽略。
    */
   public void startRelay(String relayKey, String peerAId, String peerBId, RelayLeg legA, RelayLeg legB) {
      if (relayKey == null || legA == null || legB == null || legA == legB) {
         LOGGER.warn("[Relay] Reject invalid relay session: {}", relayKey);
         return;
      }
      RelayBridge.RelaySession session = new RelayBridge.RelaySession(this, relayKey, peerAId, peerBId, legA, legB);
      if (this.activeRelays.putIfAbsent(relayKey, session) != null) {
         LOGGER.info("[Relay] Relay session already exists: {}", relayKey);
      } else {
         session.startForwarding();
         LOGGER.info("[Relay] Relay started: {} (A={}, B={})", relayKey, peerAId, peerBId);
      }
   }

   public int getRelayCountForPeer(String peerId) {
      int count = 0;

      for (RelayBridge.RelaySession session : this.activeRelays.values()) {
         if (session.peerAId.equals(peerId) || session.peerBId.equals(peerId)) {
            count++;
         }
      }

      return count;
   }

   public void stopAllRelays() {
      if (!this.activeRelays.isEmpty()) {
         LOGGER.info("[Relay] Stopping {} relay sessions", this.activeRelays.size());

         for (RelayBridge.RelaySession s : this.activeRelays.values()) {
            try {
               s.stop();
            } catch (Exception var4) {
            }
         }

         this.activeRelays.clear();
      }
   }

   private static class RelaySession {
      final RelayBridge owner;
      final String key;
      final String peerAId;
      final String peerBId;
      final RelayLeg legA;
      final RelayLeg legB;
      final AtomicBoolean forwarding = new AtomicBoolean(true);
      Thread threadAB;
      Thread threadBA;

      RelaySession(RelayBridge owner, String key, String peerAId, String peerBId, RelayLeg legA, RelayLeg legB) {
         this.owner = owner;
         this.key = key;
         this.peerAId = peerAId;
         this.peerBId = peerBId;
         this.legA = legA;
         this.legB = legB;
      }

      void startForwarding() {
         InputStream inA = this.legA.input();
         OutputStream outA = this.legA.output();
         InputStream inB = this.legB.input();
         OutputStream outB = this.legB.output();
         this.threadAB = new Thread(() -> {
            byte[] buf = new byte[RELAY_BUFFER_SIZE];

            while (this.forwarding.get()) {
               try {
                  int n = inA.read(buf);
                  if (n > 0) {
                     outB.write(buf, 0, n);
                     outB.flush();
                  } else if (n < 0) {
                     break;
                  }
               } catch (Exception e) {
                  if (this.forwarding.get()) {
                     RelayBridge.LOGGER.debug("[Relay] A->B end: {}", e.getMessage());
                  }
                  break;
               }
            }

            this.stop();
         }, "VoxLink-Relay-A2B");
         this.threadAB.setDaemon(true);
         this.threadBA = new Thread(() -> {
            byte[] buf = new byte[RELAY_BUFFER_SIZE];

            while (this.forwarding.get()) {
               try {
                  int n = inB.read(buf);
                  if (n > 0) {
                     outA.write(buf, 0, n);
                     outA.flush();
                  } else if (n < 0) {
                     break;
                  }
               } catch (Exception e) {
                  if (this.forwarding.get()) {
                     RelayBridge.LOGGER.debug("[Relay] B->A end: {}", e.getMessage());
                  }
                  break;
               }
            }

            this.stop();
         }, "VoxLink-Relay-B2A");
         this.threadBA.setDaemon(true);
         this.threadAB.start();
         this.threadBA.start();
      }

      void stop() {
         if (this.forwarding.compareAndSet(true, false)) {
            RelayBridge.LOGGER.info("[Relay] Relay session stopped: {}", this.key);

            try {
               this.legA.close();
            } catch (Exception var3) {
            }

            try {
               this.legB.close();
            } catch (Exception var3) {
            }

            if (RelayBridge.getInstance(null) != null) {
               this.owner.activeRelays.remove(this.key);
            }
         }

         if (this.threadAB != null) {
            this.threadAB.interrupt();
         }

         if (this.threadBA != null) {
            this.threadBA.interrupt();
         }
      }
   }
}
