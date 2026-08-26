package icu.wuhui.voxlink.modsync;

import com.google.gson.JsonObject;
import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.room.RoomInfo;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 房主侧：创建房间成功后，后台解析本地 mods → Modrinth 批量反查 → 过滤纯服务端
 * 模组 → 生成清单并发布到信令服务器。全程异步、失败静默降级（房间照常可用）。
 */
public final class ModSyncManifestService {
   private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "VoxLink-ModSync-Host");
      t.setDaemon(true);
      return t;
   });
   private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

   private ModSyncManifestService() {
   }

   /** 房主创建房间成功后的入口（fire-and-forget，绝不阻塞建房）。 */
   public static void onRoomCreated(RoomInfo room) {
      if (room == null || room.getCode() == null || room.getCode().isEmpty() || room.getToken() == null || room.getToken().isEmpty()) {
         return;
      }

      if (!RUNNING.compareAndSet(false, true)) {
         ModSyncLog.warn("previous manifest build still running, skip this round");
         return;
      }

      EXECUTOR.execute(() -> {
         try {
            buildAndPublish(room);
         } catch (Throwable t) {
            ModSyncLog.warn("manifest build/publish failed (room continues without modsync): {}", t.toString());
         } finally {
            RUNNING.set(false);
         }
      });
   }

   private static void buildAndPublish(RoomInfo room) throws Exception {
      long start = System.currentTimeMillis();
      List<Path> jars = ModSyncFileHasher.listModJars();
      if (jars.isEmpty()) {
         publish(room, emptyManifest());
         ModSyncLog.info("no local mods, published empty manifest in {}ms", System.currentTimeMillis() - start);
         return;
      }

      // 1) 本地 jar 的 sha1 → MR 版本对象（识别哪些本地文件在 Modrinth 上）
      Map<String, Path> sha1ToFile = new LinkedHashMap<>();
      Map<String, String> sha1ToSha512 = new HashMap<>();
      List<String> sha1List = new ArrayList<>();
      for (Path jar : jars) {
         try {
            String sha1 = ModSyncFileHasher.sha1(jar);
            sha1ToFile.put(sha1, jar);
            sha1ToSha512.put(sha1, ModSyncFileHasher.sha512(jar));
            sha1List.add(sha1);
         } catch (IOException e) {
            ModSyncLog.warn("hash failed {}: {}", jar.getFileName(), e.getMessage());
         }
      }

      if (sha1List.isEmpty()) {
         publish(room, emptyManifest());
         return;
      }

      Map<String, JsonObject> versionsBySha1 = ModrinthClient.versionsFromSha1(sha1List);

      // 2) 项目级元数据批量查询，排除"纯服务端"模组（房客无需安装）
      List<JsonObject> keptVersions = new ArrayList<>();
      List<JsonObject> allKept = new ArrayList<>();
      for (Map.Entry<String, JsonObject> e : versionsBySha1.entrySet()) {
         allKept.add(e.getValue());
      }

      Map<String, JsonObject> projects = ModrinthClient.projectsByIds(ModrinthClient.distinctProjectIds(allKept));
      for (Map.Entry<String, JsonObject> e : versionsBySha1.entrySet()) {
         JsonObject version = e.getValue();
         JsonObject project = version.has("project_id") ? projects.get(version.get("project_id").getAsString()) : null;
         if (!ModrinthClient.isServerOnly(project)) {
            keptVersions.add(version);
         }
      }

      // 3) 组装清单（去重同 project 多文件：保留首个）
      JsonObject manifest = emptyManifest();
      Map<String, Boolean> seenProjects = new HashMap<>();
      int count = 0;
      for (JsonObject version : keptVersions) {
         String pid = version.has("project_id") ? version.get("project_id").getAsString() : "";
         if (pid.isEmpty() || seenProjects.putIfAbsent(pid, Boolean.TRUE) != null) {
            continue;
         }

         ModSyncEntry entry = ModSyncEntry.fromVersion(
            version, projects.get(pid)
         );
         if (entry.downloadUrl.isEmpty()) {
            continue;
         }

         manifest.getAsJsonArray("mods").add(entry.toJson());
         count++;
      }

      publish(room, manifest);
      ModSyncLog.info(
         "manifest ready: {} jars scanned, {} required entries, {}ms",
         new Object[]{jars.size(), count, System.currentTimeMillis() - start}
      );
   }

   private static JsonObject emptyManifest() {
      JsonObject m = new JsonObject();
      m.addProperty("protocolVersion", "modSync.v1");
      m.addProperty("loader", ModSyncEnv.LOADER);
      m.addProperty("mcVersion", ModSyncEnv.GAME_VERSION);
      m.add("mods", new com.google.gson.JsonArray());
      return m;
   }

   private static void publish(RoomInfo room, JsonObject manifest) throws Exception {
      var resp = VoxLinkMod.getSignalingClient()
         .publishModManifest(room.getCode(), room.getToken(), manifest)
         .get(20L, java.util.concurrent.TimeUnit.SECONDS);
      if (resp.success) {
         ModSyncLog.info("manifest published for room {}", room.getCode());
      } else {
         // 清单发布失败不影响建房；重试一次
         Thread.sleep(3000L);
         var retry = VoxLinkMod.getSignalingClient()
            .publishModManifest(room.getCode(), room.getToken(), manifest)
            .get(20L, java.util.concurrent.TimeUnit.SECONDS);
         ModSyncLog.warn("manifest publish retry success={} error={}", retry.success, retry.error);
      }
   }
}
