package icu.wuhui.voxlink.modsync;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.network.SignalingClient;
import icu.wuhui.voxlink.terracotta.RoomCodeRouter;
import icu.wuhui.voxlink.ui.UiLogBus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public final class ModSyncGuestService {
   private static final Set<String> GATED_THIS_LAUNCH = ConcurrentHashMap.newKeySet();
   private static final Set<String> BYPASSED_THIS_LAUNCH = ConcurrentHashMap.newKeySet();
   private static final Set<String> USER_CHOSE_THIS_LAUNCH = ConcurrentHashMap.newKeySet();
   private static final Set<String> SKIPPED_THIS_SESSION = ConcurrentHashMap.newKeySet();
   private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "VoxLink-ModSync-Guest");
      t.setDaemon(true);
      return t;
   });
   private static final int READY_RETRIES = 5;
   private static volatile int pendingNoteLevel = -1;
   private static volatile String pendingNoteKey;

   private ModSyncGuestService() {
   }

   public static void clearGateFor(String roomCode) {
      if (roomCode != null) {
         GATED_THIS_LAUNCH.remove(roomCode);
      }
   }

   public static void pushPendingGateNote() {
      String key = pendingNoteKey;
      int level = pendingNoteLevel;
      pendingNoteKey = null;
      pendingNoteLevel = -1;
      if (key != null) {
         UiLogBus.push(level, key);
      }
   }

   public static void markSkipped(List<String> keys) {
      if (keys != null) {
         SKIPPED_THIS_SESSION.addAll(keys);
      }
   }

   public static void bypass(String roomCode) {
      if (roomCode != null) {
         BYPASSED_THIS_LAUNCH.add(roomCode);
         ModSyncLog.info("gate: player bypassed, room={}", roomCode);
      }
   }

   public static boolean shouldSkipGate(String roomCode) {
      return roomCode != null && BYPASSED_THIS_LAUNCH.contains(roomCode);
   }

   public static boolean isEnabled() {
      return VoxLinkMod.getConfig().isJoinRequiredModsCheck();
   }

   public static void gate(String roomCode, String scope, Runnable onProceed, Runnable onCancel) {
      if (!isEnabled() || !RoomCodeRouter.isVoxLinkCode(roomCode)) {
         onProceed.run();
      } else if (BYPASSED_THIS_LAUNCH.contains(roomCode)) {
         onProceed.run();
      } else {
         GATED_THIS_LAUNCH.add(roomCode);
         Runnable proceed = () -> {
            GATED_THIS_LAUNCH.remove(roomCode);
            onProceed.run();
         };
         Runnable cancel = () -> {
            GATED_THIS_LAUNCH.remove(roomCode);
            onCancel.run();
         };
         String fetchScope = "all".equals(scope) ? "all" : "required";
         pendingNoteKey = null;
         pendingNoteLevel = -1;
         ModSyncLog.info("gate: room={} scope={}", roomCode, fetchScope);
         EXECUTOR.execute(
            () -> {
               ModSyncGuestService.FetchResult fetched = fetchManifestWithRetry(roomCode, fetchScope);
               if (!BYPASSED_THIS_LAUNCH.contains(roomCode)) {
                  Minecraft mc = Minecraft.getInstance();
                  if (fetched.outcome != ModSyncGuestService.FetchOutcome.MANIFEST) {
                     ModSyncLog.info("gate skip: {}", fetched.outcome);
                     if (fetched.outcome == ModSyncGuestService.FetchOutcome.UNSUPPORTED) {
                        pendingNoteLevel = 0;
                        pendingNoteKey = "voxlink.logui.modsync_unsupported";
                     } else if (fetched.outcome == ModSyncGuestService.FetchOutcome.NOT_READY) {
                        pendingNoteLevel = 2;
                        pendingNoteKey = "voxlink.logui.modsync_fetch_failed";
                     }

                     mc.execute(proceed);
                  } else {
                     JsonObject manifest = fetched.manifest;

                     try {
                        ModSyncGuestService.DiffResult diff = computeDiff(manifest);
                        String hostMc = manifest.has("mcVersion") && manifest.get("mcVersion").isJsonPrimitive() ? manifest.get("mcVersion").getAsString() : "";
                        if (!hostMc.isEmpty() && !hostMc.equals(ModSyncEnv.GAME_VERSION)) {
                           ModSyncLog.warn("MC version mismatch: host={} guest={}", hostMc, ModSyncEnv.GAME_VERSION);
                           diff.unresolvable
                              .add(0, Component.translatable("voxlink.modsync.mc_mismatch", new Object[]{hostMc, ModSyncEnv.GAME_VERSION}).getString());
                        }

                        String hostLoader = manifest.has("loader") && manifest.get("loader").isJsonPrimitive() ? manifest.get("loader").getAsString() : "";
                        if (!hostLoader.isEmpty() && !hostLoader.equalsIgnoreCase("forge")) {
                           ModSyncLog.warn("Loader mismatch: host={} guest={}", hostLoader, "forge");
                           diff.unresolvable.add(0, Component.translatable("voxlink.modsync.loader_mismatch", new Object[]{hostLoader, "forge"}).getString());
                        }

                        ModSyncLog.info(
                           "guest diff: downloadable={} versionDiff={} unresolvable={} titles={}",
                           diff.downloadable.size(),
                           diff.versionDiff.size(),
                           diff.unresolvable.size(),
                           ModSyncSelectScreen.clip(joinTitles(diff), 200)
                        );
                        if (diff.downloadable.isEmpty() && diff.unresolvable.isEmpty() && diff.versionDiff.isEmpty()) {
                           mc.execute(proceed);
                           return;
                        }

                        mc.execute(
                           () -> {
                              if (mc.gui.screen() != null) {
                                 Minecraft.getInstance()
                                    .gui
                                    .setScreen(
                                       new ModSyncSelectScreen(
                                          roomCode, fetchScope, diff.downloadable, diff.unresolvable, diff.versionDiff, diff.skipKeys, proceed, cancel
                                       )
                                    );
                              } else {
                                 cancel.run();
                              }
                           }
                        );
                     } catch (Throwable var10) {
                        ModSyncLog.warn("guest diff failed, proceed without gate: {}", var10.toString());
                        mc.execute(() -> {
                           try {
                              if (mc.player != null) {
                                 mc.player.sendSystemMessage(Component.translatable("voxlink.modsync.gate_failed_warn").withStyle(ChatFormatting.YELLOW));
                              }
                           } catch (Exception var3x) {
                           }

                           proceed.run();
                        });
                     }
                  }
               }
            }
         );
      }
   }

   private static boolean cancellableSleep(String roomCode, long millis) {
      long end = System.currentTimeMillis() + millis;

      while (millis > 0L) {
         if (BYPASSED_THIS_LAUNCH.contains(roomCode)) {
            return false;
         }

         if (Thread.currentThread().isInterrupted()) {
            Thread.currentThread().interrupt();
            return false;
         }

         long step = Math.min(100L, millis);

         try {
            Thread.sleep(step);
         } catch (InterruptedException var8) {
            Thread.currentThread().interrupt();
            return false;
         }

         millis -= step;
      }

      return !BYPASSED_THIS_LAUNCH.contains(roomCode);
   }

   private static ModSyncGuestService.FetchResult fetchManifestWithRetry(String roomCode, String scope) {
      boolean sawNotReady = false;
      boolean sawTransient = false;
      int maxAttempts = 10;
      long deadline = System.currentTimeMillis() + 12000L;

      for (int attempt = 0; attempt < maxAttempts && System.currentTimeMillis() < deadline; attempt++) {
         if (BYPASSED_THIS_LAUNCH.contains(roomCode)) {
            return new ModSyncGuestService.FetchResult(ModSyncGuestService.FetchOutcome.BYPASSED, null);
         }

         try {
            SignalingClient.ApiResponse resp = VoxLinkMod.getSignalingClient().requestRoomMods(roomCode, scope).get(8L, TimeUnit.SECONDS);
            if (resp.success && resp.data != null) {
               boolean supported = resp.data.has("supported") && resp.data.get("supported").getAsBoolean();
               if (!supported) {
                  return new ModSyncGuestService.FetchResult(ModSyncGuestService.FetchOutcome.UNSUPPORTED, null);
               }

               boolean ready = resp.data.has("ready") && resp.data.get("ready").getAsBoolean();
               if (ready) {
                  JsonObject m = new JsonObject();
                  m.addProperty("loader", resp.data.has("loader") ? resp.data.get("loader").getAsString() : "unknown");
                  m.addProperty("mcVersion", resp.data.has("mcVersion") ? resp.data.get("mcVersion").getAsString() : "");
                  m.add("mods", resp.data.has("mods") && resp.data.get("mods").isJsonArray() ? resp.data.getAsJsonArray("mods") : new JsonArray());
                  m.add(
                     "unknownMods",
                     resp.data.has("unknownMods") && resp.data.get("unknownMods").isJsonArray() ? resp.data.getAsJsonArray("unknownMods") : new JsonArray()
                  );
                  if (m.getAsJsonArray("mods").size() == 0 && m.getAsJsonArray("unknownMods").size() == 0) {
                     return new ModSyncGuestService.FetchResult(ModSyncGuestService.FetchOutcome.EMPTY, null);
                  }

                  return new ModSyncGuestService.FetchResult(ModSyncGuestService.FetchOutcome.MANIFEST, m);
               }

               sawNotReady = true;
               if (!cancellableSleep(roomCode, 2000L)) {
                  return new ModSyncGuestService.FetchResult(ModSyncGuestService.FetchOutcome.BYPASSED, null);
               }
            } else {
               String err = resp.error != null ? resp.error.toUpperCase(Locale.ROOT) : "";
               boolean authoritative = err.contains("ROOM_NOT_FOUND")
                  || err.contains("ROOM_EXPIRED")
                  || err.contains("INVALID_TOKEN")
                  || err.contains("ROOM_CLOSED")
                  || err.contains("ROOM_EVICTED")
                  || err.contains("UNKNOWN_ENDPOINT")
                  || err.contains("INVALID_ENDPOINT");
               if (authoritative) {
                  ModSyncLog.warn("getRoomMods failed (authoritative): {} {}", resp.error, resp.message);
                  return new ModSyncGuestService.FetchResult(ModSyncGuestService.FetchOutcome.NOT_READY, null);
               }

               sawTransient = true;
               ModSyncLog.warn("getRoomMods transient ({}/{}): {}", attempt + 1, maxAttempts, resp.error);
               if (!cancellableSleep(roomCode, 1500L)) {
                  return new ModSyncGuestService.FetchResult(ModSyncGuestService.FetchOutcome.BYPASSED, null);
               }
            }
         } catch (TimeoutException var12) {
            sawTransient = true;
            ModSyncLog.warn("getRoomMods timeout ({}/{})", attempt + 1, maxAttempts);
         } catch (InterruptedException var13) {
            Thread.currentThread().interrupt();
            return new ModSyncGuestService.FetchResult(ModSyncGuestService.FetchOutcome.NOT_READY, null);
         } catch (Exception var14) {
            sawTransient = true;
            ModSyncLog.warn("manifest fetch error: {}", var14.toString());
         }
      }

      if (sawNotReady || sawTransient) {
         ModSyncLog.warn("manifest never became ready, giving up gate");
         GATED_THIS_LAUNCH.remove(roomCode);
      }

      return new ModSyncGuestService.FetchResult(ModSyncGuestService.FetchOutcome.NOT_READY, null);
   }

   private static String joinTitles(ModSyncGuestService.DiffResult diff) {
      StringBuilder sb = new StringBuilder("[");
      int n = 0;

      for (ModSyncEntry e : diff.downloadable) {
         if (n++ > 0) {
            sb.append(", ");
         }

         sb.append(e.title).append('@').append(e.versionNumber);
      }

      for (String s : diff.versionDiff) {
         if (n++ > 0) {
            sb.append(", ");
         }

         sb.append('~').append(s);
      }

      for (String s : diff.unresolvable) {
         if (n++ > 0) {
            sb.append(", ");
         }

         sb.append('?').append(s);
      }

      return sb.append(']').toString();
   }

   private static ModSyncGuestService.DiffResult computeDiff(JsonObject manifest) throws Exception {
      ModSyncGuestService.DiffResult out = new ModSyncGuestService.DiffResult();
      ModSyncFileHasher.ListModJarsResult scan = ModSyncFileHasher.listModJarsWithDisabled();
      List<Path> localJars = scan.jars;
      Set<String> disabledFileNames = scan.disabled;
      Set<String> localSha1s = new HashSet<>();
      Set<String> localFileNames = new HashSet<>();

      for (Path jar : localJars) {
         try {
            localSha1s.add(ModSyncFileHasher.sha1(jar));
         } catch (Exception var19) {
         }

         localFileNames.add(jar.getFileName().toString());
      }

      Set<String> disabledSha1s = new HashSet<>();
      if (!disabledFileNames.isEmpty()) {
         Path modsDir = ModSyncEnv.getModsDir();

         for (String disabledName : disabledFileNames) {
            Path p = modsDir.resolve(disabledName + ".disabled");

            try {
               if (Files.isRegularFile(p)) {
                  disabledSha1s.add(ModSyncFileHasher.sha1(p));
               }
            } catch (Exception var18) {
            }
         }
      }

      Map<String, String> normBaseToLocal = new HashMap<>();
      Map<String, Long> normSizeByBase = new HashMap<>();

      for (Path jar : localJars) {
         String nb = normalizeFileName(jar.getFileName().toString());
         if (!nb.isEmpty()) {
            normBaseToLocal.putIfAbsent(nb, jar.getFileName().toString());
            if (!normSizeByBase.containsKey(nb)) {
               try {
                  normSizeByBase.put(nb, Files.size(jar));
               } catch (Exception var17) {
               }
            }
         }
      }

      if (manifest.has("unknownMods") && manifest.get("unknownMods").isJsonArray()) {
         for (JsonElement el : manifest.getAsJsonArray("unknownMods")) {
            if (el.isJsonPrimitive()) {
               out.unresolvable.add(el.getAsString());
            }
         }
      }

      for (JsonElement elx : manifest.getAsJsonArray("mods")) {
         if (elx.isJsonObject()) {
            ModSyncEntry entry = ModSyncEntry.fromJson(elx.getAsJsonObject());
            if (!entry.projectId.isEmpty()) {
               boolean installed = false;
               boolean installedButDisabled = false;
               if (!entry.sha1.isEmpty()) {
                  if (localSha1s.contains(entry.sha1)) {
                     installed = true;
                  } else if (disabledSha1s.contains(entry.sha1)) {
                     installed = true;
                     installedButDisabled = true;
                  }
               } else if (!entry.fileName.isEmpty() && localFileNames.contains(entry.fileName)) {
                  installed = true;
               } else if (disabledFileNames.contains(entry.fileName)) {
                  installed = true;
                  installedButDisabled = true;
               }

               if (!installed) {
                  String nb2 = normalizeFileName(entry.fileName);
                  Long localSize = nb2.isEmpty() ? null : normSizeByBase.get(nb2);
                  if (localSize != null && localSize == entry.size) {
                     installed = true;
                  }
               }

               if (installed) {
                  if (installedButDisabled) {
                     out.unresolvable.add(Component.translatable("voxlink.modsync.disabled_hint", new Object[]{entry.title, entry.fileName}).getString());
                  }
               } else {
                  String normBase = normalizeFileName(entry.fileName);
                  if (!normBase.isEmpty() && normBaseToLocal.containsKey(normBase)) {
                     String skipKey = entry.projectId + "@" + entry.versionNumber;
                     if (SKIPPED_THIS_SESSION.add(skipKey)) {
                        out.versionDiff.add(Component.translatable("voxlink.modsync.diff_line", new Object[]{e_title(entry)}).getString());
                        out.skipKeys.add(skipKey);
                     }
                  } else {
                     boolean hostFileCompatible = entry.loaders.contains("forge")
                        && entry.gameVersions.contains(ModSyncEnv.GAME_VERSION)
                        && !entry.downloadUrl.isEmpty();
                     if (hostFileCompatible) {
                        out.downloadable.add(entry);
                     } else {
                        out.unresolvable.add(entry.title + " (" + entry.versionNumber + ")");
                     }
                  }
               }
            }
         }
      }

      return out;
   }

   private static String e_title(ModSyncEntry e) {
      return e.title;
   }

   static String normalizeFileName(String fileName) {
      if (fileName == null) {
         return "";
      } else {
         String s = fileName.toLowerCase(Locale.ROOT);
         if (s.endsWith(".jar")) {
            s = s.substring(0, s.length() - 4);
         }

         List<String> stop = Arrays.asList("fabric", "forge", "neoforge", "quilt", "fml", "mc", "all", "for", "the", "with");
         StringBuilder sb = new StringBuilder(s.length());

         for (String tok : s.split("[^a-z]+")) {
            if (!tok.isEmpty() && !stop.contains(tok)) {
               sb.append(tok);
            }
         }

         return sb.toString();
      }
   }

   public static final class DiffResult {
      public final List<ModSyncEntry> downloadable = new ArrayList<>();
      public final List<String> unresolvable = new ArrayList<>();
      public final List<String> versionDiff = new ArrayList<>();
      public final List<String> skipKeys = new ArrayList<>();
   }

   private static enum FetchOutcome {
      MANIFEST,
      UNSUPPORTED,
      EMPTY,
      NOT_READY,
      BYPASSED;
   }

   private static final class FetchResult {
      final ModSyncGuestService.FetchOutcome outcome;
      final JsonObject manifest;

      FetchResult(ModSyncGuestService.FetchOutcome outcome, JsonObject manifest) {
         this.outcome = outcome;
         this.manifest = manifest;
      }
   }
}
