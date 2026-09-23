package icu.wuhui.voxlink.modsync;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.room.RoomInfo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
 * 房主侧：解析本地 mods → Modrinth 批量反查 → 构建两档清单（必装闭包 / 全部可识别）
 * 缓存到本地磁盘（按 mods 状态哈希键，跨房间复用）；不再建房即上报。
 * 房客按需经信号 mods_request 请求时，从本地缓存应答（缓存未命中则现场构建）。
 * 全程异步、失败静默降级（房间照常可用）。
 */
public final class ModSyncManifestService {
   private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "VoxLink-ModSync-Host");
      t.setDaemon(true);
      return t;
   });
   private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
   /** 依赖闭包最大深度（防脏元数据成环/超深；visited 已防环，此为双保险）。 */
   private static final int MAX_DEP_DEPTH = 8;
   /** unknownMods 清单上限（信令单条消息 48KB 上限，1024 个 jar 文件名可能撑爆）。 */
   private static final int UNKNOWN_MODS_CAP = 64;
   /** scope 取值：仅必装闭包 / 房主全部 MR 可识别模组。 */
   public static final String SCOPE_REQUIRED = "required";
   public static final String SCOPE_ALL = "all";

   private ModSyncManifestService() {
   }

   /** 房主创建房间成功后的入口：确保本地缓存已构建（异步），不再上报服务器。 */
   public static void onRoomCreated(RoomInfo room) {
      EXECUTOR.execute(() -> {
         try {
            ensureCache();
         } catch (Throwable t) {
            ModSyncLog.warn("local manifest cache build failed: {}", t.toString());
         }
      });
   }

   /**
    * 房主收到房客的按需请求（信号 mods_request）：从本地缓存取对应档清单
    * （未命中现场构建）→ POST /room/mods/answer 回服务器。
    */
   public static void onModsRequest(JsonObject data) {
      if (!VoxLinkMod.getConfig().isHostModSyncPublish()) {
         ModSyncLog.info("host mod-sync disabled, ignore mods_request");
         return;
      }
      String requestId = data.has("requestId") && data.get("requestId").isJsonPrimitive()
         ? data.get("requestId").getAsString() : "";
      String scope = data.has("scope") && data.get("scope").isJsonPrimitive()
         ? data.get("scope").getAsString() : SCOPE_REQUIRED;
      if (requestId.isEmpty()) {
         return;
      }
      if (!SCOPE_ALL.equals(scope)) {
         scope = SCOPE_REQUIRED;
      }

      RoomInfo room = VoxLinkMod.getRoomManager() != null ? VoxLinkMod.getRoomManager().getCurrentRoom() : null;
      if (room == null || room.getCode() == null || room.getCode().isEmpty() || room.getToken() == null || room.getToken().isEmpty()) {
         ModSyncLog.warn("mods_request {} ignored (not hosting)", requestId);
         return;
      }
      final RoomInfo hostRoom = room;
      final String reqId = requestId;
      final String reqScope = scope;
      EXECUTOR.execute(() -> {
         try {
            JsonObject manifest = ensureCache().getAsJsonObject(reqScope);
            var resp = VoxLinkMod.getSignalingClient()
               .answerRoomMods(hostRoom.getCode(), hostRoom.getToken(), reqId, reqScope, manifest)
               .get(20L, TimeUnit.SECONDS);
            ModSyncLog.info("mods_request {} ({}) answered, success={}", new Object[]{reqId, reqScope, resp.success});
         } catch (Throwable t) {
            ModSyncLog.warn("answer mods_request {} failed: {}", new Object[]{reqId, t.toString()});
         }
      });
   }

   // ---------- 本地缓存 ----------

   /** 内存缓存：mods 状态哈希 → 两档清单。 */
   private static volatile String cachedStateHash = null;
   private static volatile JsonObject cachedManifests = null;

   /** 确保本地缓存可用（状态哈希命中直接复用；否则构建并落盘）。 */
   private static JsonObject ensureCache() throws Exception {
      String stateHash = computeStateHash();
      JsonObject mem = cachedManifests;
      if (mem != null && stateHash.equals(cachedStateHash)) {
         return mem;
      }

      JsonObject disk = readDiskCache(stateHash);
      if (disk != null) {
         cachedStateHash = stateHash;
         cachedManifests = disk;
         ModSyncLog.info("manifest cache hit (state {})", stateHash);
         return disk;
      }

      JsonObject built = buildBothManifests(stateHash);
      cachedStateHash = stateHash;
      cachedManifests = built;
      writeDiskCache(stateHash, built);
      return built;
   }

   /** mods 状态哈希：全部 jar 的 sha1 排序后再 sha1。内容变则缓存键变。 */
   private static String computeStateHash() throws Exception {
      List<Path> jars = ModSyncFileHasher.listModJars();
      List<String> sha1s = new ArrayList<>();
      for (Path jar : jars) {
         try {
            sha1s.add(ModSyncFileHasher.sha1(jar));
         } catch (Exception ignored) {
         }
      }
      java.util.Collections.sort(sha1s);
      return ModSyncFileHasher.sha1OfBytes(String.join("\n", sha1s).getBytes(java.nio.charset.StandardCharsets.UTF_8));
   }

   private static Path cacheFile() {
      return ModSyncEnv.getCacheFile();
   }

   private static JsonObject readDiskCache(String stateHash) {
      try {
         Path f = cacheFile();
         if (!Files.isRegularFile(f)) {
            return null;
         }
         JsonObject root = com.google.gson.JsonParser
            .parseString(Files.readString(f, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
         if (!stateHash.equals(root.has("stateHash") && root.get("stateHash").isJsonPrimitive()
            ? root.get("stateHash").getAsString() : "")) {
            return null;
         }
         if (root.has("required") && root.get("required").isJsonObject()
            && root.has("all") && root.get("all").isJsonObject()) {
            return root;
         }
         return null;
      } catch (Throwable t) {
         return null;
      }
   }

   private static void writeDiskCache(String stateHash, JsonObject manifests) {
      try {
         manifests.addProperty("stateHash", stateHash);
         manifests.addProperty("builtAt", System.currentTimeMillis() / 1000L);
         Path f = cacheFile();
         if (f.getParent() != null) {
            Files.createDirectories(f.getParent());
         }
         Path tmp = f.resolveSibling(f.getFileName().toString() + ".tmp");
         Files.writeString(tmp, manifests.toString(), java.nio.charset.StandardCharsets.UTF_8);
         Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (Throwable t) {
         ModSyncLog.warn("manifest cache write failed: {}", t.toString());
      }
   }

   // ---------- 构建 ----------

   /**
    * 一次扫描构建两档清单：
    *   required = 以"客户端必装"为根、沿 required 依赖走闭包（1.1.2 起的语义，已修
    *              元数据查询漏掉自有 mod project_id 导致根集恒空的缺陷）；
    *   all      = 房主全部 MR 可识别模组（server-only 除外），不区分 client_side。
    */
   private static JsonObject buildBothManifests(String stateHash) throws Exception {
      long start = System.currentTimeMillis();
      JsonObject required = emptyManifest();
      JsonObject all = emptyManifest();
      JsonObject out = new JsonObject();
      out.add("required", required);
      out.add("all", all);

      List<Path> jars = ModSyncFileHasher.listModJars();
      if (jars.isEmpty()) {
         ModSyncLog.info("no local mods, manifests built in {}ms", System.currentTimeMillis() - start);
         return out;
      }

      // 1) 本地 jar 的 sha1 → MR 版本对象（识别哪些本地文件在 Modrinth 上）
      Map<String, String> sha1ToJarName = new LinkedHashMap<>();
      List<String> sha1List = new ArrayList<>();
      for (Path jar : jars) {
         try {
            String sha1 = ModSyncFileHasher.sha1(jar);
            sha1ToJarName.put(sha1, jar.getFileName().toString());
            sha1List.add(sha1);
         } catch (Exception e) {
            ModSyncLog.warn("hash failed {}: {}", jar.getFileName(), e.getMessage());
         }
      }
      if (sha1List.isEmpty()) {
         return out;
      }

      Map<String, JsonObject> versionsBySha1 = ModrinthClient.versionsFromSha1(sha1List);

      // 2) 未知模组：MR 上查不到的一律进仅提示名单（我们没有任何渠道让房客下到它们）
      JsonArray unknownMods = new JsonArray();
      for (Map.Entry<String, String> e : sha1ToJarName.entrySet()) {
         if (!versionsBySha1.containsKey(e.getKey())) {
            unknownMods.add(e.getValue());
         }
      }

      // project_id → 版本对象（同项目多文件取首个）
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

      // 3) 项目元数据一次批量查全：自有 mod + 被引用的前置。
      //    修复记录：1.1.2 二轮曾只查 depRefs，自有 mod 元数据缺失 → 根集 fail-open
      //    全排除 → 必装清单恒为 0（生产日志 "0 required entries" 实锤）。
      Set<String> projectIds = new LinkedHashSet<>(versionByProject.keySet());
      projectIds.addAll(depRefs);
      Map<String, JsonObject> projects = ModrinthClient.projectsByIds(projectIds);

      // 4) required 根集（依赖感知）：client_required 但"同时是其他房主 mod 的 required
      //    依赖"的库类不作根——它们是否必装由 BFS 按需决定。
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

      // 6) 组装：required=闭包结果；all=全部可识别（server-only 除外）
      int requiredCount = 0;
      for (JsonObject version : selectedVersions) {
         String pid = str(version, "project_id");
         ModSyncEntry entry = ModSyncEntry.fromVersion(version, projects.get(pid));
         if (entry.downloadUrl.isEmpty()) {
            continue;
         }

         required.getAsJsonArray("mods").add(entry.toJson());
         requiredCount++;
      }

      int allCount = 0;
      for (Map.Entry<String, JsonObject> e : versionByProject.entrySet()) {
         JsonObject meta = projects.get(e.getKey());
         if (ModrinthClient.isServerOnly(meta)) {
            continue;
         }

         ModSyncEntry entry = ModSyncEntry.fromVersion(e.getValue(), meta);
         if (entry.downloadUrl.isEmpty()) {
            continue;
         }

         all.getAsJsonArray("mods").add(entry.toJson());
         allCount++;
      }

      // 保护清单大小：unknownMods 只用于提示，截断到 64 条
      JsonArray trimmedUnknown = unknownMods;
      if (unknownMods.size() > UNKNOWN_MODS_CAP) {
         trimmedUnknown = new JsonArray();
         for (int i = 0; i < UNKNOWN_MODS_CAP; i++) {
            trimmedUnknown.add(unknownMods.get(i));
         }
      }
      required.add("unknownMods", trimmedUnknown);
      all.add("unknownMods", trimmedUnknown);

      ModSyncLog.info(
         "manifests ready: {} jars, required={} all={} unknown={} state={} {}ms",
         new Object[]{jars.size(), requiredCount, allCount, unknownMods.size(), stateHash.substring(0, 8), System.currentTimeMillis() - start}
      );
      return out;
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
    * 根集判定：optional/unsupported → 否；元数据缺失按"fail-open"判否——
    * 元数据查询失败的 mod 不作必装根（查询现已包含自有 ids，缺失属 MR 异常）。
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
}
