package icu.wuhui.voxlink.command;

import icu.wuhui.voxlink.VoxLinkMod;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class LanHostRegistry {
   private static final int CAPTURE_MAX_ATTEMPTS = 20;
   private static final int CAPTURE_INTERVAL_TICKS = 10;
   private static final long LAZY_CAPTURE_GRACE_MS = 15000L;
   private static volatile Set<UUID> bootstrapProfiles = Collections.emptySet();
   private static volatile long serverStartMs = 0L;
   private static volatile long capturedAtMs = 0L;

   private LanHostRegistry() {
   }

   public static void scheduleCapture(MinecraftServer server) {
      serverStartMs = System.currentTimeMillis();
      bootstrapProfiles = Collections.emptySet();
      capturedAtMs = 0L;
      scheduleCaptureAttempt(server, 0);
   }

   private static void scheduleCaptureAttempt(MinecraftServer server, int attempt) {
      try {
         server.execute(() -> {
            if (!server.isStopped()) {
               List<ServerPlayer> players = server.getPlayerList().getPlayers();
               boolean emptySnapshot = bootstrapProfiles.isEmpty();
               if (!players.isEmpty() && emptySnapshot) {
                  Set<UUID> ids = new HashSet<>();

                  for (ServerPlayer p : players) {
                     try {
                        ids.add(p.getUUID());
                     } catch (Exception var8) {
                     }
                  }

                  if (!ids.isEmpty()) {
                     bootstrapProfiles = Collections.unmodifiableSet(ids);
                     capturedAtMs = System.currentTimeMillis();
                     VoxLinkMod.LOGGER.info("[LanHost] Bootstrap profile snapshot captured: {} player(s)", ids.size());
                  }
               } else if (emptySnapshot && players.isEmpty() && attempt < 20) {
                  scheduleCaptureAttempt(server, attempt + 1);
               }
            }
         });
      } catch (Exception var3) {
         VoxLinkMod.LOGGER.warn("[LanHost] Snapshot capture schedule failed: {}", var3.getMessage());
      }
   }

   public static void clear() {
      Set<UUID> old = bootstrapProfiles;
      if (old != null && !old.isEmpty()) {
         VoxLinkMod.LOGGER.info("[LanHost] Bootstrap profile snapshot cleared");
      }

      bootstrapProfiles = Collections.emptySet();
      capturedAtMs = 0L;
      serverStartMs = 0L;
   }

   public static boolean isBootstrapProfile(MinecraftServer server, UUID queriedProfileId) {
      if (queriedProfileId == null) {
         return false;
      } else {
         Set<UUID> snapshot = bootstrapProfiles;
         if (!snapshot.isEmpty()) {
            return snapshot.contains(queriedProfileId);
         } else {
            long start = serverStartMs;
            return start > 0L && System.currentTimeMillis() - start > 15000L && !server.isStopped() && lazyCapture(server, queriedProfileId);
         }
      }
   }

   private static synchronized boolean lazyCapture(MinecraftServer server, UUID queriedProfileId) {
      Set<UUID> snapshot = bootstrapProfiles;
      if (!snapshot.isEmpty()) {
         return snapshot.contains(queriedProfileId);
      } else {
         UUID localProfileId = null;

         try {
            localProfileId = server.getSingleplayerProfile() != null ? server.getSingleplayerProfile().id() : null;
         } catch (Exception var5) {
         }

         if (localProfileId != null && localProfileId.equals(queriedProfileId)) {
            bootstrapProfiles = Collections.unmodifiableSet(new CopyOnWriteArraySet<>(Collections.singletonList(queriedProfileId)));
            capturedAtMs = System.currentTimeMillis();
            VoxLinkMod.LOGGER.info("[LanHost] Lazy bootstrap capture anchored to singleplayerProfile");
            return true;
         } else {
            return false;
         }
      }
   }

   public static Set<UUID> snapshot() {
      return bootstrapProfiles;
   }

   public static long capturedAt() {
      return capturedAtMs;
   }
}
