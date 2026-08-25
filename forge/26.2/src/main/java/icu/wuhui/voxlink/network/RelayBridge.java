package icu.wuhui.voxlink.network;

import icu.wuhui.voxlink.room.ConnectionManager;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RelayBridge {
   private static final Logger LOGGER = LoggerFactory.getLogger("VoxLink-Relay");
   private static final int MONITOR_INITIAL_DELAY_SEC = 5;
   private static final int MONITOR_INTERVAL_SEC = 5;
   private static final int RELAY_BUFFER_SIZE = 65536;
   private final ScheduledExecutorService scheduler;
   private final Map<String, RelayBridge.RelaySession> activeRelays = new ConcurrentHashMap<>();
   private final AtomicBoolean running = new AtomicBoolean(false);
   private ScheduledFuture<?> monitorTask;
   private static volatile RelayBridge instance;

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

   public void startRelay(String peerAId, String peerBId, ReliableUdpTransport transportA, ReliableUdpTransport transportB) {
      if (peerAId != null && peerBId != null && !peerAId.equals(peerBId) && transportA != null && transportB != null && transportA != transportB) {
         String relayKey = peerAId.compareTo(peerBId) < 0 ? peerAId + "<->" + peerBId : peerBId + "<->" + peerAId;
         RelayBridge.RelaySession session = new RelayBridge.RelaySession(peerAId, peerBId, transportA, transportB);
         if (this.activeRelays.putIfAbsent(relayKey, session) != null) {
            LOGGER.info("[Relay] Relay session already exists: {}", relayKey);
         } else {
            session.startForwarding();
            LOGGER.info("[Relay] Relay started: {} (A={}, B={})", new Object[]{relayKey, peerAId, peerBId});
            // 网络线程不可直调 GUI：包一层主线程调度
            Minecraft mc = Minecraft.getInstance();
            if (mc != null) {
               mc.execute(() -> {
                  Minecraft m = Minecraft.getInstance();
                  if (m.player != null) {
                     m.player.sendSystemMessage(Component.translatable("voxlink.relay.started"));
                  }
               });
            }

            if (this.running.compareAndSet(false, true)) {
               this.monitorTask = this.scheduler.scheduleAtFixedRate(this::monitorRelays, 5L, 5L, TimeUnit.SECONDS);
            }
         }
      } else {
         LOGGER.warn("[Relay] Reject invalid relay session: {} <-> {}", peerAId, peerBId);
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
         if (this.running.compareAndSet(true, false) && this.monitorTask != null) {
            this.monitorTask.cancel(false);
            this.monitorTask = null;
         }
      }
   }

   private void monitorRelays() {
      List<String> deadRelays = new ArrayList<>();
      this.activeRelays.entrySet().removeIf(entry -> {
         RelayBridge.RelaySession s = entry.getValue();
         if (s.transportA.isConnected() && s.transportB.isConnected()) {
            return false;
         }

         LOGGER.warn("[Relay] Relay session disconnected: {}", entry.getKey());
         deadRelays.add(entry.getKey());
         s.stop();
         return true;
      });
      if (this.activeRelays.isEmpty() && this.running.compareAndSet(true, false)) {
         if (this.monitorTask != null) {
            this.monitorTask.cancel(false);
            this.monitorTask = null;
         }

         LOGGER.info("[Relay] No relay sessions, monitor stopped");
      }

      if (!deadRelays.isEmpty()) {
         for (String key : deadRelays) {
            String[] parts = key.split("<->");
            if (parts.length == 2) {
               try {
                  ConnectionManager cm = ConnectionManager.getInstance();
                  if (cm != null) {
                     cm.onRelayDisconnected(parts[0], parts[1]);
                  }
               } catch (Exception var6) {
               }
            }
         }
      }
   }

   private static class RelaySession {
      final String peerAId;
      final String peerBId;
      final ReliableUdpTransport transportA;
      final ReliableUdpTransport transportB;
      volatile boolean forwarding = true;
      Thread threadAB;
      Thread threadBA;

      RelaySession(String peerAId, String peerBId, ReliableUdpTransport transportA, ReliableUdpTransport transportB) {
         this.peerAId = peerAId;
         this.peerBId = peerBId;
         this.transportA = transportA;
         this.transportB = transportB;
      }

      void startForwarding() {
         InputStream inA = this.transportA.getInputStream();
         OutputStream outA = this.transportA.getOutputStream();
         InputStream inB = this.transportB.getInputStream();
         OutputStream outB = this.transportB.getOutputStream();
         this.threadAB = new Thread(() -> {
            byte[] buf = new byte[65536];

            while (this.forwarding) {
               try {
                  int n = inA.read(buf);
                  if (n > 0) {
                     outB.write(buf, 0, n);
                  } else if (n < 0) {
                     break;
                  }
               } catch (Exception e) {
                  if (this.forwarding) {
                     RelayBridge.LOGGER.debug("[Relay] A->B exception: {}", e.getMessage());
                  }
                  break;
               }
            }

            this.forwarding = false;
         }, "VoxLink-Relay-A2B");
         this.threadAB.setDaemon(true);
         this.threadBA = new Thread(() -> {
            byte[] buf = new byte[65536];

            while (this.forwarding) {
               try {
                  int n = inB.read(buf);
                  if (n > 0) {
                     outA.write(buf, 0, n);
                  } else if (n < 0) {
                     break;
                  }
               } catch (Exception e) {
                  if (this.forwarding) {
                     RelayBridge.LOGGER.debug("[Relay] B->A exception: {}", e.getMessage());
                  }
                  break;
               }
            }

            this.forwarding = false;
         }, "VoxLink-Relay-B2A");
         this.threadBA.setDaemon(true);
         this.threadAB.start();
         this.threadBA.start();
      }

      void stop() {
         this.forwarding = false;
         if (this.threadAB != null) {
            this.threadAB.interrupt();
         }

         if (this.threadBA != null) {
            this.threadBA.interrupt();
         }

         try {
            this.transportA.close();
         } catch (Exception var3) {
         }

         try {
            this.transportB.close();
         } catch (Exception var2) {
         }
      }
   }
}
