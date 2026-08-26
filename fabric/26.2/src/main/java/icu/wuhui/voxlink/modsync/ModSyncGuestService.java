package icu.wuhui.voxlink.modsync;

import com.google.gson.JsonObject;
import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.terracotta.RoomCodeRouter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;

/**
 * 房客侧门控：打洞前拉取房主必装清单 → 与本地 mods 做 diff →
 * 有缺失/版本差异时弹选择界面，下载完成后进入强制重启屏。
 * 开关关闭、旧房主（不支持 modSyncV1）、空清单时零打扰直通。
 */
public final class ModSyncGuestService {
   private static final java.util.Set<String> GATED_THIS_LAUNCH = java.util.concurrent.ConcurrentHashMap.newKeySet();
   private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "VoxLink-ModSync-Guest");
      t.setDaemon(true);
      return t;
   });
   /** supported 但未 ready 的最长等待：5 次 × 2s（房主 MR 解析通常数秒内完成）。 */
   private static final int READY_RETRIES = 5;

   private ModSyncGuestService() {
   }

   public static boolean isEnabled() {
      return VoxLinkMod.getConfig().isJoinRequiredModsCheck();
   }

   /**
    * 加入流程入口门控（在 join_room / 打洞之前调用）。
    * onProceed/onCancel 都会被调度回主线程执行。
    */
   public static void gate(String roomCode, Runnable onProceed, Runnable onCancel) {
      if (!isEnabled() || !RoomCodeRouter.isVoxLinkCode(roomCode)) {
         onProceed.run();
         return;
      }

      // 同一次游戏会话内每个房间只门控一次；取消选择会清除标记，允许下次重试
      if (!GATED_THIS_LAUNCH.add(roomCode)) {
         onProceed.run();
         return;
      }


      EXECUTOR.execute(() -> {
         JsonObject manifest = fetchManifestWithRetry(roomCode);
         Minecraft mc = Minecraft.getInstance();
         if (manifest == null) {
            // 拉取失败或房主不支持：不打扰，直接继续正常加入流程
            mc.execute(onProceed);
            return;
         }

         try {
            DiffResult diff = computeDiff(manifest);
            if (diff.downloadable.isEmpty() && diff.unresolvable.isEmpty() && diff.versionDiff.isEmpty()) {
               mc.execute(onProceed);
               return;
            }

            mc.execute(() -> {
               if (mc.gui.screen() != null) {
                  // 当前一般是 AttemptingJoinScreen：切到选择屏；继续时由调用方重建加入流程
                  Minecraft.getInstance().gui.setScreen(
                     new ModSyncSelectScreen(
                        roomCode,
                        diff.downloadable,
                        diff.unresolvable,
                        diff.versionDiff,
                        onProceed,
                        () -> {
                           GATED_THIS_LAUNCH.remove(roomCode);
                           onCancel.run();
                        }
                     )
                  );
               } else {
                  onCancel.run();
               }
            });
         } catch (Throwable t) {
            ModSyncLog.warn("guest diff failed, proceed without gate: {}", t.toString());
            mc.execute(onProceed);
         }
      });
   }

   /** 返回 null 表示"无需处理"（不支持/失败/空清单）。 */
   private static JsonObject fetchManifestWithRetry(String roomCode) {
      for (int attempt = 0; attempt < READY_RETRIES; attempt++) {
         try {
            var resp = VoxLinkMod.getSignalingClient()
               .getRoomMods(roomCode)
               .get(8L, TimeUnit.SECONDS);
            if (!resp.success || resp.data == null) {
               ModSyncLog.warn("getRoomMods failed: {} {}", resp.error, resp.message);
               return null;
            }

            boolean supported = resp.data.has("supported") && resp.data.get("supported").getAsBoolean();
            if (!supported) {
               return null;
            }

            boolean ready = resp.data.has("ready") && resp.data.get("ready").getAsBoolean();
            if (ready) {
               JsonObject m = new JsonObject();
               m.addProperty("loader", resp.data.has("loader") ? resp.data.get("loader").getAsString() : "unknown");
               m.addProperty("mcVersion", resp.data.has("mcVersion") ? resp.data.get("mcVersion").getAsString() : "");
               m.add("mods", resp.data.has("mods") && resp.data.get("mods").isJsonArray()
                  ? resp.data.getAsJsonArray("mods")
                  : new com.google.gson.JsonArray());
               if (m.getAsJsonArray("mods").size() == 0) {
                  return null;
               }

               return m;
            }

            Thread.sleep(2000L);
         } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return null;
         } catch (Exception e) {
            ModSyncLog.warn("manifest fetch error: {}", e.toString());
            return null;
         }
      }

      ModSyncLog.warn("manifest never became ready, giving up gate");
      return null;
   }

   public static final class DiffResult {
      public final List<ModSyncEntry> downloadable = new ArrayList<>();
      public final List<String> unresolvable = new ArrayList<>();
      public final List<String> versionDiff = new ArrayList<>();
   }

   private static DiffResult computeDiff(JsonObject manifest) throws Exception {
      DiffResult out = new DiffResult();

      // 本地 jar → projectId → versionNumber（与房主同一套哈希反查）
      Map<String, String> localVersionByProject = new HashMap<>();
      List<String> localSha1s = new ArrayList<>();
      for (var jar : ModSyncFileHasher.listModJars()) {
         try {
            localSha1s.add(ModSyncFileHasher.sha1(jar));
         } catch (Exception ignored) {
         }
      }

      if (!localSha1s.isEmpty()) {
         Map<String, JsonObject> localVersions = ModrinthClient.versionsFromSha1(localSha1s);
         for (JsonObject v : localVersions.values()) {
            if (v.has("project_id") && v.has("version_number")) {
               localVersionByProject.putIfAbsent(
                  v.get("project_id").getAsString(),
                  v.get("version_number").getAsString()
               );
            }
         }
      }

      // 未知模组：房主侧在 MR 查不到的 jar，仅提示（我们没有任何渠道让房客下到它们）
      if (manifest.has("unknownMods") && manifest.get("unknownMods").isJsonArray()) {
         for (var el : manifest.getAsJsonArray("unknownMods")) {
            if (el.isJsonPrimitive()) {
               out.unresolvable.add(el.getAsString());
            }
         }
      }

      for (var el : manifest.getAsJsonArray("mods")) {
         if (!el.isJsonObject()) {
            continue;
         }

         ModSyncEntry entry = ModSyncEntry.fromJson(el.getAsJsonObject());
         if (entry.projectId.isEmpty()) {
            continue;
         }

         String localVer = localVersionByProject.get(entry.projectId);
         if (localVer == null) {
            resolveDownloadable(entry, out);
         } else if (!localVer.equals(entry.versionNumber)) {
            out.versionDiff.add(entry.title + " " + localVer + " ≠ " + entry.versionNumber);
         }
      }

      return out;
   }

   /** 房主文件与本端 MC/加载器兼容 → 直接用房主同款（最稳）；否则查本端兼容最新版；再不行进 unresolvable。 */
   private static void resolveDownloadable(ModSyncEntry entry, DiffResult out) throws Exception {
      boolean hostFileCompatible = entry.loaders.contains(ModSyncEnv.LOADER)
         && entry.gameVersions.contains(ModSyncEnv.GAME_VERSION)
         && !entry.downloadUrl.isEmpty();
      if (hostFileCompatible) {
         out.downloadable.add(entry);
         return;
      }

      try {
         JsonObject alt = ModrinthClient.latestVersionFor(entry.projectId, ModSyncEnv.GAME_VERSION, ModSyncEnv.LOADER);
         if (alt != null) {
            ModSyncEntry altEntry = ModSyncEntry.fromVersion(alt, null);
            if (altEntry.title == null || altEntry.title.isBlank()) {
               altEntry.title = entry.title;
            }

            if (!altEntry.downloadUrl.isEmpty()) {
               out.downloadable.add(altEntry);
               return;
            }
         }
      } catch (Exception e) {
         ModSyncLog.warn("fallback lookup failed for {}: {}", entry.projectId, e.getMessage());
      }

      out.unresolvable.add(entry.title + " (" + entry.versionNumber + ")");
   }
}
