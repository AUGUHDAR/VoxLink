package icu.wuhui.voxlink.modsync;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.network.SignalingClient;
import icu.wuhui.voxlink.room.RoomInfo;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ModSyncManifestService {
   private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "VoxLink-ModSync-Host");
      t.setDaemon(true);
      return t;
   });
   private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
   private static final int MAX_DEP_DEPTH = 8;
   private static final int UNKNOWN_MODS_CAP = 64;
   public static final String SCOPE_REQUIRED = "required";
   public static final String SCOPE_ALL = "all";
   private static volatile String cachedStateHash = null;
   private static volatile JsonObject cachedManifests = null;

   private ModSyncManifestService() {
   }

   public static void onRoomCreated(RoomInfo room) {
      EXECUTOR.execute(() -> {
         try {
            ensureCache();
         } catch (Throwable var1) {
            ModSyncLog.warn("local manifest cache build failed: {}", var1.toString());
         }
      });
   }

   public static void onModsRequest(JsonObject data) {
      if (!VoxLinkMod.getConfig().isHostModSyncPublish()) {
         ModSyncLog.info("host mod-sync disabled, ignore mods_request");
      } else {
         String requestId = data.has("requestId") && data.get("requestId").isJsonPrimitive() ? data.get("requestId").getAsString() : "";
         String scope = data.has("scope") && data.get("scope").isJsonPrimitive() ? data.get("scope").getAsString() : "required";
         if (!requestId.isEmpty()) {
            if (!"all".equals(scope)) {
               scope = "required";
            }

            RoomInfo room = VoxLinkMod.getRoomManager() != null ? VoxLinkMod.getRoomManager().getCurrentRoom() : null;
            if (room != null && room.getCode() != null && !room.getCode().isEmpty() && room.getToken() != null && !room.getToken().isEmpty()) {
               String reqScope = scope;
               EXECUTOR.execute(
                  () -> {
                     try {
                        JsonObject manifest = ensureCache().getAsJsonObject(reqScope);
                        SignalingClient.ApiResponse resp = VoxLinkMod.getSignalingClient()
                           .answerRoomMods(room.getCode(), room.getToken(), requestId, reqScope, manifest)
                           .get(20L, TimeUnit.SECONDS);
                        ModSyncLog.info("mods_request {} ({}) answered, success={}", requestId, reqScope, resp.success);
                     } catch (Throwable var5) {
                        ModSyncLog.warn("answer mods_request {} failed: {}", requestId, var5.toString());
                     }
                  }
               );
            } else {
               ModSyncLog.warn("mods_request {} ignored (not hosting)", requestId);
            }
         }
      }
   }

   private static JsonObject ensureCache() throws Exception {
      String stateHash = computeStateHash();
      JsonObject mem = cachedManifests;
      if (mem != null && stateHash.equals(cachedStateHash)) {
         return mem;
      } else {
         JsonObject disk = readDiskCache(stateHash);
         if (disk != null) {
            cachedStateHash = stateHash;
            cachedManifests = disk;
            ModSyncLog.info("manifest cache hit (state {})", stateHash);
            return disk;
         } else {
            JsonObject built = buildBothManifests(stateHash);
            cachedStateHash = stateHash;
            cachedManifests = built;
            writeDiskCache(stateHash, built);
            return built;
         }
      }
   }

   private static String computeStateHash() throws Exception {
      List<Path> jars = ModSyncFileHasher.listModJars();
      List<String> sha1s = new ArrayList<>();

      for (Path jar : jars) {
         try {
            sha1s.add(ModSyncFileHasher.sha1(jar));
         } catch (Exception var5) {
         }
      }

      Collections.sort(sha1s);
      return ModSyncFileHasher.sha1OfBytes(String.join("\n", sha1s).getBytes(StandardCharsets.UTF_8));
   }

   private static Path cacheFile() {
      return ModSyncEnv.getCacheFile();
   }

   private static JsonObject readDiskCache(String stateHash) {
      try {
         Path f = cacheFile();
         if (!Files.isRegularFile(f)) {
            return null;
         } else {
            JsonObject root = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!stateHash.equals(root.has("stateHash") && root.get("stateHash").isJsonPrimitive() ? root.get("stateHash").getAsString() : "")) {
               return null;
            } else {
               return root.has("required") && root.get("required").isJsonObject() && root.has("all") && root.get("all").isJsonObject() ? root : null;
            }
         }
      } catch (Throwable var3) {
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
         Files.writeString(tmp, manifests.toString(), StandardCharsets.UTF_8);
         Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (Throwable var4) {
         ModSyncLog.warn("manifest cache write failed: {}", var4.toString());
      }
   }

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
      } else {
         Map<String, String> sha1ToJarName = new LinkedHashMap<>();
         List<String> sha1List = new ArrayList<>();

         for (Path jar : jars) {
            try {
               String sha1 = ModSyncFileHasher.sha1(jar);
               sha1ToJarName.put(sha1, jar.getFileName().toString());
               sha1List.add(sha1);
            } catch (Exception var26) {
               ModSyncLog.warn("hash failed {}: {}", jar.getFileName(), var26.getMessage());
            }
         }

         if (sha1List.isEmpty()) {
            return out;
         } else {
            Map<String, JsonObject> versionsBySha1 = ModrinthClient.versionsFromSha1(sha1List);
            JsonArray unknownMods = new JsonArray();

            for (Entry<String, String> e : sha1ToJarName.entrySet()) {
               if (!versionsBySha1.containsKey(e.getKey())) {
                  unknownMods.add(e.getValue());
               }
            }

            Map<String, JsonObject> versionByProject = new LinkedHashMap<>();
            Set<String> depRefs = new LinkedHashSet<>();

            for (JsonObject v : versionsBySha1.values()) {
               String pid = str(v, "project_id");
               if (!pid.isEmpty()) {
                  versionByProject.putIfAbsent(pid, v);
                  collectRequiredDepIds(v, depRefs);
               }
            }

            Set<String> projectIds = new LinkedHashSet<>(versionByProject.keySet());
            projectIds.addAll(depRefs);
            Map<String, JsonObject> projects = ModrinthClient.projectsByIds(projectIds);
            Set<String> depIdsOfHostMods = new HashSet<>();

            for (JsonObject vx : versionsBySha1.values()) {
               depIdsOfHostMods.addAll(requiredDepIds(vx));
            }

            Deque<String> queue = new ArrayDeque<>();

            for (Entry<String, JsonObject> ex : versionByProject.entrySet()) {
               if (!depIdsOfHostMods.contains(ex.getKey()) && isClientRequiredRoot(projects.get(ex.getKey()), ex.getKey())) {
                  queue.add(ex.getKey());
               }
            }

            Map<String, Boolean> visited = new HashMap<>();
            List<JsonObject> selectedVersions = new ArrayList<>();
            int depth = 0;

            while (!queue.isEmpty() && depth <= 8 * versionByProject.size()) {
               String pid = queue.poll();
               depth++;
               if (pid != null && visited.putIfAbsent(pid, Boolean.TRUE) == null) {
                  JsonObject meta = projects.get(pid);
                  if (!ModrinthClient.isServerOnly(meta)) {
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
               }
            }

            int requiredCount = 0;

            for (JsonObject version : selectedVersions) {
               String pid = str(version, "project_id");
               ModSyncEntry entry = ModSyncEntry.fromVersion(version, projects.get(pid));
               if (!entry.downloadUrl.isEmpty()) {
                  required.getAsJsonArray("mods").add(entry.toJson());
                  requiredCount++;
               }
            }

            int allCount = 0;

            for (Entry<String, JsonObject> exx : versionByProject.entrySet()) {
               JsonObject meta = projects.get(exx.getKey());
               if (!ModrinthClient.isServerOnly(meta)) {
                  ModSyncEntry entry = ModSyncEntry.fromVersion(exx.getValue(), meta);
                  if (!entry.downloadUrl.isEmpty()) {
                     all.getAsJsonArray("mods").add(entry.toJson());
                     allCount++;
                  }
               }
            }

            JsonArray trimmedUnknown = unknownMods;
            if (unknownMods.size() > 64) {
               trimmedUnknown = new JsonArray();

               for (int i = 0; i < 64; i++) {
                  trimmedUnknown.add(unknownMods.get(i));
               }
            }

            required.add("unknownMods", trimmedUnknown);
            all.add("unknownMods", trimmedUnknown);
            ModSyncLog.info(
               "manifests ready: {} jars, required={} all={} unknown={} state={} {}ms",
               jars.size(),
               requiredCount,
               allCount,
               unknownMods.size(),
               stateHash.substring(0, 8),
               System.currentTimeMillis() - start
            );
            return out;
         }
      }
   }

   private static void collectRequiredDepIds(JsonObject version, Set<String> out) {
      for (String dep : requiredDepIds(version)) {
         out.add(dep);
      }
   }

   private static List<String> requiredDepIds(JsonObject version) {
      List<String> out = new ArrayList<>();
      if (version != null && version.has("dependencies") && version.get("dependencies").isJsonArray()) {
         for (JsonElement el : version.getAsJsonArray("dependencies")) {
            if (el.isJsonObject()) {
               JsonObject d = el.getAsJsonObject();
               String type = str(d, "dependency_type");
               String pid = str(d, "project_id");
               if ("required".equalsIgnoreCase(type) && !pid.isEmpty()) {
                  out.add(pid);
               }
            }
         }

         return out;
      } else {
         return out;
      }
   }

   private static boolean isClientRequiredRoot(JsonObject project, String projectId) {
      if (project == null) {
         ModSyncLog.warn("isClientRequiredRoot: project meta missing for {} (fail-open, not a root)", projectId);
         return false;
      } else {
         String cs = str(project, "client_side");
         return !"optional".equalsIgnoreCase(cs) && !"unsupported".equalsIgnoreCase(cs);
      }
   }

   private static String str(JsonObject o, String key) {
      return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
   }

   private static JsonObject emptyManifest() {
      JsonObject m = new JsonObject();
      m.addProperty("protocolVersion", "modSync.v1");
      m.addProperty("loader", "forge");
      m.addProperty("mcVersion", ModSyncEnv.GAME_VERSION);
      m.add("mods", new JsonArray());
      return m;
   }
}
