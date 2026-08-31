package icu.wuhui.voxlink.modsync;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.room.RoomInfo;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 房主侧：创建房间成功后，后台解析本地 mods → Modrinth 批量反查 →
 * 以"客户端必装"为根、沿 required 依赖走闭包（按 project_id 去重）生成清单，
 * MR 查不到的 jar 进 unknownMods 仅提示名单 → 发布到信令服务器。
 * 全程异步、失败静默降级（房间照常可用）。
 */
public final class ModSyncManifestService {
   private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "VoxLink-ModSync-Host");
      t.setDaemon(true);
      return t;
   });
   private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
   /** 构建期间又来了新的建房请求时暂存于此，本轮完成后补一次（快速重开房间不再丢清单）。 */
   private static volatile RoomInfo PENDING;
   /** 依赖闭包最大深度（防脏元数据成环/超深；visited 已防环，此为双保险）。 */
   private static final int MAX_DEP_DEPTH = 8;
   /** unknownMods 清单上限（信令单条消息 48KB 上限，1024 个 jar 文件名可能撑爆）。 */
   private static final int UNKNOWN_MODS_CAP = 64;

   private ModSyncManifestService() {
   }

   /** 房主创建房间成功后的入口（fire-and-forget，绝不阻塞建房）。 */
   public static void onRoomCreated(RoomInfo room) {
      if (room == null || room.getCode() == null || room.getCode().isEmpty() || room.getToken() == null || room.getToken().isEmpty()) {
         return;
      }

      if (!VoxLinkMod.getConfig().isHostModSyncPublish()) {
         ModSyncLog.info("host mod-sync publish disabled, skip manifest build");
         return;
      }

      if (!RUNNING.compareAndSet(false, true)) {
         PENDING = room;
         ModSyncLog.warn("previous manifest build still running, queued this round");
         return;
      }

      EXECUTOR.execute(() -> {
         try {
            buildAndPublish(room);
            RoomInfo next = PENDING;
            PENDING = null;
            if (next != null && next.getCode() != null && !next.getCode().equals(room.getCode())) {
               try {
                  buildAndPublish(next);
               } catch (Throwable t) {
                  ModSyncLog.warn("queued manifest build failed: {}", t.toString());
               }
            }
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
      Map<String, String> sha1ToJarName = new LinkedHashMap<>();
      List<String> sha1List = new ArrayList<>();
      for (Path jar : jars) {
         try {
            String sha1 = ModSyncFileHasher.sha1(jar);
            sha1ToJarName.put(sha1, jar.getFileName().toString());
            sha1List.add(sha1);
         } catch (IOException e) {
            ModSyncLog.warn("hash failed {}: {}", jar.getFileName(), e.getMessage());
         }
      }

      JsonObject manifest = emptyManifest();
      if (sha1List.isEmpty()) {
         publish(room, manifest);
         return;
      }

      Map<String, JsonObject> versionsBySha1 = ModrinthClient.versionsFromSha1(sha1List);

      // 2) 未知模组：MR 上查不到的一律进仅提示名单（我们没有任何渠道让房客下到它们）
      JsonArray unknownMods = new JsonArray();
      for (Map.Entry<String, String> e : sha1ToJarName.entrySet()) {
         if (!versionsBySha1.containsKey(e.getKey())) {
            unknownMods.add(e.getValue());
         }
      }

      // project_id → 版本对象（同项目多文件取首个；哈希来自房主实际安装，天然去重后仍唯一）
      Map<String, JsonObject> versionByProject = new LinkedHashMap<>();
      Set<String> depRefs = new LinkedHashSet<>();
      for (JsonObject v : versionsBySha1.values()) {
         String pid = str(v, "project_id");
         if (pid.isEmpty()) {
            continue;
         }

         versionByProject.putIfAbsent(pid, v);
         collectRequiredDepIds(v, depRefs);
      }

      // 3) 项目元数据一次批量查全（自身 + 被引用的前置），避免逐层请求
      Map<String, JsonObject> projects = ModrinthClient.projectsByIds(depRefs);

      // 4) 根集（依赖感知）：client_required 但"同时是其他房主 mod 的 required 依赖"的库类
      //    （Architectury API / Cloth Config 等）不作根——它们是否必装由 BFS 按需决定：
      //    只要有必装根真的依赖它们就会经闭包拉入；依赖它们的都是选装 mod 时不打扰房客。
      Set<String> depIdsOfHostMods = new HashSet<>();
      for (JsonObject v : versionsBySha1.values()) {
         depIdsOfHostMods.addAll(requiredDepIds(v));
      }

      Deque<String> queue = new ArrayDeque<>();
      for (Map.Entry<String, JsonObject> e : versionByProject.entrySet()) {
         if (!depIdsOfHostMods.contains(e.getKey()) && isClientRequiredRoot(projects.get(e.getKey()), e.getKey())) {
            queue.add(e.getKey());
         }
      }

      // 5) BFS 必需前置闭包（visited 按 project_id 去重；前置必然也在房主 mods 内）
      Map<String, Boolean> visited = new HashMap<>();
      List<JsonObject> selectedVersions = new ArrayList<>();
      int depth = 0;
      while (!queue.isEmpty() && depth <= MAX_DEP_DEPTH * versionByProject.size()) {
         String pid = queue.poll();
         depth++;
         if (pid == null || visited.putIfAbsent(pid, Boolean.TRUE) != null) {
            continue;
         }

         JsonObject meta = projects.get(pid);
         if (ModrinthClient.isServerOnly(meta)) {
            continue;
         }

         JsonObject version = versionByProject.get(pid);
         if (version != null) {
            selectedVersions.add(version);
            for (String dep : requiredDepIds(version)) {
               if (!visited.containsKey(dep)) {
                  queue.add(dep);
               }
            }
         }
      }

      // 6) 组装清单
      int count = 0;
      for (JsonObject version : selectedVersions) {
         String pid = str(version, "project_id");
         ModSyncEntry entry = ModSyncEntry.fromVersion(version, projects.get(pid));
         if (entry.downloadUrl.isEmpty()) {
            continue;
         }

         manifest.getAsJsonArray("mods").add(entry.toJson());
         count++;
      }

      // 保护清单大小: 信令服务器单条消息有 48KB 上限, 1024 个 jar 的文件名可能撑爆。
      // unknownMods 只用于提示, 截断到 64 条对玩家提示已足够, 损失信息仅"多 N 个 mod 无法识别"。
      if (unknownMods.size() > UNKNOWN_MODS_CAP) {
         JsonArray trimmed = new JsonArray();
         for (int i = 0; i < UNKNOWN_MODS_CAP; i++) {
            trimmed.add(unknownMods.get(i));
         }
         unknownMods = trimmed;
      }
      manifest.add("unknownMods", unknownMods);
      publish(room, manifest);
      ModSyncLog.info(
         "manifest ready: {} jars scanned, {} required entries, {} unknown-only, {}ms",
         new Object[]{jars.size(), count, unknownMods.size(), System.currentTimeMillis() - start}
      );
   }

   private static void collectRequiredDepIds(JsonObject version, Set<String> out) {
      for (String dep : requiredDepIds(version)) {
         out.add(dep);
      }
   }

   /** version.dependencies 中 dependency_type=="required" 的 project_id 列表。 */
   private static List<String> requiredDepIds(JsonObject version) {
      List<String> out = new ArrayList<>();
      if (version == null || !version.has("dependencies") || !version.get("dependencies").isJsonArray()) {
         return out;
      }

      for (JsonElement el : version.getAsJsonArray("dependencies")) {
         if (!el.isJsonObject()) {
            continue;
         }

         JsonObject d = el.getAsJsonObject();
         String type = str(d, "dependency_type");
         String pid = str(d, "project_id");
         if ("required".equalsIgnoreCase(type) && !pid.isEmpty()) {
            out.add(pid);
         }
      }

      return out;
   }

   /**
    * 根集判定：optional/unsupported → 否；缺失字段按"fail-open"判否——
    * 把 Modrinth 元数据查不到的 mod 一律当必装根集会污染房客（房主端仅看 mods 目录里有就纳入）。
    * 真正必装的 mod 通常会被其他必装 mod 的 required 前置依赖闭包重新捞回 BFS；
    * 元数据缺失本身是异常情况，保持最小破坏面。
    * 注意：BFS 闭包内对 projectId 的依赖仍然全量保留，这里只改"根集"。
    */
   private static boolean isClientRequiredRoot(JsonObject project, String projectId) {
      if (project == null) {
         ModSyncLog.warn("isClientRequiredRoot: project meta missing for {} (fail-open, not a root)", projectId);
         return false;
      }

      String cs = str(project, "client_side");
      return !"optional".equalsIgnoreCase(cs) && !"unsupported".equalsIgnoreCase(cs);
   }

   private static String str(JsonObject o, String key) {
      return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
   }

   private static JsonObject emptyManifest() {
      JsonObject m = new JsonObject();
      m.addProperty("protocolVersion", "modSync.v1");
      m.addProperty("loader", ModSyncEnv.LOADER);
      m.addProperty("mcVersion", ModSyncEnv.GAME_VERSION);
      m.add("mods", new JsonArray());
      return m;
   }

   private static void publish(RoomInfo room, JsonObject manifest) throws Exception {
      var resp = VoxLinkMod.getSignalingClient()
         .publishModManifest(room.getCode(), room.getToken(), manifest)
         .get(20L, java.util.concurrent.TimeUnit.SECONDS);
      if (resp.success) {
         ModSyncLog.info("manifest published for room {}", room.getCode());
         // 给房主本地聊天提示: 从已发布的 manifest 里读 mods / unknownMods,
         // 各自为空则不发; 不修改 publish 签名, 避免调用方一连串改动。
         notifyHostFromManifest(manifest);
      } else {
         // 清单发布失败不影响建房；重试一次
         Thread.sleep(3000L);
         var retry = VoxLinkMod.getSignalingClient()
            .publishModManifest(room.getCode(), room.getToken(), manifest)
            .get(20L, TimeUnit.SECONDS);
         ModSyncLog.warn("manifest publish retry success={} error={}", retry.success, retry.error);
      }
   }

   /**
    * 房主本地聊天提示：unknownMods / 必装条目各自为空则不发；最多取前 3 个文件名做列表,
    * 超出加省略号。EXECUTOR 后台线程 → 用 Minecraft.execute 切到主线程调用 displayClientMessage。
    */
   private static void notifyHostFromManifest(JsonObject manifest) {
      int requiredCount = manifest.has("mods") && manifest.get("mods").isJsonArray()
         ? manifest.getAsJsonArray("mods").size() : 0;
      JsonArray unknownMods = manifest.has("unknownMods") && manifest.get("unknownMods").isJsonArray()
         ? manifest.getAsJsonArray("unknownMods") : null;
      boolean hasUnknown = unknownMods != null && unknownMods.size() > 0;
      boolean hasRequired = requiredCount > 0;
      if (!hasUnknown && !hasRequired) {
         return;
      }

      Minecraft mc = Minecraft.getInstance();
      if (mc == null) {
         return;
      }

      final int rc = requiredCount;
      final JsonArray um = unknownMods;
      mc.execute(() -> {
         try {
            Minecraft m = Minecraft.getInstance();
            if (m.player == null) {
               return;
            }

            if (um != null && um.size() > 0) {
               int total = um.size();
               StringBuilder names = new StringBuilder();
               int shown = Math.min(3, total);
               for (int i = 0; i < shown; i++) {
                  if (i > 0) {
                     names.append(", ");
                  }
                  names.append(um.get(i).getAsString());
               }
               if (total > shown) {
                  names.append("...");
               }
               m.player.sendSystemMessage(
                  Component.translatable("voxlink.modsync.host_unknown",
                     new Object[]{total, names.toString()})
                     .withStyle(ChatFormatting.YELLOW));
            }
            if (rc > 0) {
               m.player.sendSystemMessage(
                  Component.translatable("voxlink.modsync.host_published",
                     new Object[]{rc})
                     .withStyle(ChatFormatting.GREEN));
            }
         } catch (Exception ignored) {
         }
      });
   }
}
