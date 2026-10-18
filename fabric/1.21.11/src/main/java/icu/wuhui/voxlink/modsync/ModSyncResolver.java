package icu.wuhui.voxlink.modsync;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 手动安装条目的 MR+CF 双源兜底解析：6 线程并行逐条
 * 搜索/反查 → 本端兼容版本 → 真名+真图标+可下载回流。
 * 前置跟随（PCL 同款思路）：文件 dependencies 里筛 required，
 * 先查清单/下载清单/本地 mods 防重复，没有才补下载。
 * 网络失败≠未找到（不写负缓存，失败批整体重试一次）。
 * 逐条结果落日志供真机对账。
 */
public final class ModSyncResolver {
   /** 未找到（可能已原地补 CF 真名/图标） */
   public static final int BUCKET_NOT_FOUND = 0;
   /** MR 有项目但无本端兼容版本 */
   public static final int BUCKET_INCOMPATIBLE = 1;
   /** 拿到本端兼容文件，可回流下载 */
   public static final int BUCKET_DOWNLOADABLE = 2;

   private static final Map<String, ModSyncEntry> RESOLVED = new ConcurrentHashMap<>();
   private static final Set<String> NEGATIVE = ConcurrentHashMap.newKeySet();
   /** 进行中的解析会话（清单键），防同清单双跑 */
   private static final Set<String> ACTIVE_SESSIONS = ConcurrentHashMap.newKeySet();
   private static final ExecutorService POOL = Executors.newFixedThreadPool(6, r -> {
      Thread t = new Thread(r, "VoxLink-ModSync-Resolve");
      t.setDaemon(true);
      return t;
   });
   private static final int MAX_PER_MINUTE = 240;
   private static final long WINDOW_MS = 60000L;
   private static final AtomicLong WINDOW_START = new AtomicLong(0L);
   private static final AtomicInteger WINDOW_COUNT = new AtomicInteger(0);
   /** 全局累计网络失败上限：两端皆不可达时收手。 */
   private static final int MAX_NET_FAILURES = 16;
   /** 单次解析会话最多补下的前置数。 */
   private static final int MAX_DEP_ADD = 24;
   /** 前置递归深度上限。 */
   private static final int MAX_DEP_DEPTH = 2;

   /** 回调在解析线程池触发，调用方自行切回主线程。original=null 表示新增前置条目。 */
   public interface Callback {
      void onResolved(ModSyncEntry original, ModSyncEntry resolved, int bucket);

      void onFinished(int resolvedCount);
   }

   /** 单次解析会话的查重上下文：清单/下载清单/本地 mods/已发前置。 */
   private static final class Ctx {
      final Set<String> seen = ConcurrentHashMap.newKeySet();
      final Set<String> norms = ConcurrentHashMap.newKeySet();
      final AtomicInteger added = new AtomicInteger(0);
      final AtomicInteger totalResolved = new AtomicInteger(0);
      final AtomicInteger netFailures = new AtomicInteger(0);
      private volatile Set<String> localNorms;
      private volatile Set<String> localSha1s;

      void scanLocal() {
         if (localNorms != null) {
            return;
         }

         synchronized (this) {
            if (localNorms != null) {
               return;
            }

            Set<String> n = new HashSet<>();
            Set<String> s = new HashSet<>();
            try {
               for (Path jar : ModSyncFileHasher.listModJars()) {
                  String nb = normalizeFileName(jar.getFileName().toString());
                  if (!nb.isEmpty()) {
                     n.add(nb);
                  }

                  try {
                     s.add(ModSyncFileHasher.sha1(jar));
                  } catch (Exception ignored) {
                  }
               }
            } catch (Exception ignored) {
            }

            localNorms = n;
            localSha1s = s;
         }
      }
   }

   private ModSyncResolver() {
   }

   public static void resolveAsync(List<ModSyncEntry> manualEntries, List<ModSyncEntry> incompatibleEntries,
                                   List<ModSyncEntry> knownDownloadable, Callback cb) {
      List<ModSyncEntry> all = new ArrayList<>();
      if (manualEntries != null) {
         all.addAll(manualEntries);
      }

      if (incompatibleEntries != null) {
         all.addAll(incompatibleEntries);
      }

      if (all.isEmpty()) {
         cb.onFinished(0);
         return;
      }

      Ctx ctx = new Ctx();
      // 防重复基线：清单里已有的（含可下载区）一律不再补
      if (knownDownloadable != null) {
         for (ModSyncEntry k : knownDownloadable) {
            markSeen(ctx, k);
         }
      }

      for (ModSyncEntry k : all) {
         markSeen(ctx, k);
      }

      // 单飞：同清单会话进行中则跳过（防双实例双跑）
      String sk = sessionKey(all);
      if (!ACTIVE_SESSIONS.add(sk)) {
         ModSyncLog.warn("resolve session in flight, skip duplicate");
         cb.onFinished(0);
         return;
      }

      Callback guarded = new Callback() {
         @Override
         public void onResolved(ModSyncEntry original, ModSyncEntry resolved, int bucket) {
            cb.onResolved(original, resolved, bucket);
         }

         @Override
         public void onFinished(int resolvedCount) {
            ACTIVE_SESSIONS.remove(sk);
            cb.onFinished(resolvedCount);
         }
      };

      resolveBatch(all, 0, ctx, guarded);
   }

   private static String sessionKey(List<ModSyncEntry> entries) {
      List<String> keys = new ArrayList<>();
      for (ModSyncEntry e : entries) {
         keys.add(keyOf(e));
      }

      java.util.Collections.sort(keys);
      return String.join("|", keys);
   }

   private static void resolveBatch(List<ModSyncEntry> entries, int pass, Ctx ctx, Callback cb) {
      AtomicInteger remaining = new AtomicInteger(entries.size());
      List<ModSyncEntry> failed = new CopyOnWriteArrayList<>();
      for (ModSyncEntry e : entries) {
         if (e == null) {
            decRemaining(remaining, failed, pass, ctx, cb);
            continue;
         }

         POOL.execute(() -> {
            try {
               runOne(e, failed, ctx, cb);
            } catch (Throwable t) {
               ModSyncLog.warn("resolve task error: {}", t.toString());
            } finally {
               decRemaining(remaining, failed, pass, ctx, cb);
            }
         });
      }
   }

   private static void runOne(ModSyncEntry e, List<ModSyncEntry> failed, Ctx ctx, Callback cb) {
      String key = keyOf(e);
      ModSyncEntry hit = RESOLVED.get(key);
      if (hit == null && !NEGATIVE.contains(key)) {
         try {
            hit = query(e, key);
         } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return;
         } catch (Exception ex) {
            failed.add(e);
            int n = ctx.netFailures.incrementAndGet();
            ModSyncLog.warn("resolve {} failed: {}", key, ex.toString());
            if (n >= MAX_NET_FAILURES) {
               ModSyncLog.warn("resolve giving up after {} network failures", n);
            }

            return;
         }
      }

      if (hit == null) {
         ModSyncLog.info("modsync resolve: NOT-FOUND {} ({})", clip(e.title), clip(e.fileName));
         return;
      }

      applyMeta(e, hit);
      int bucket = bucketOf(hit);
      if (bucket == BUCKET_DOWNLOADABLE) {
         ctx.totalResolved.incrementAndGet();
      }

      boolean cf = "cf".equals(hit.foundOn);
      String outcome = bucket == BUCKET_DOWNLOADABLE
         ? (cf ? "downloadable-cf" : "downloadable-mr")
         : (bucket == BUCKET_INCOMPATIBLE ? (cf ? "cf-file" : "no-compatible-file-mr") : "meta-only-cf");
      ModSyncLog.info("modsync resolve: {} {} ({})", outcome, clip(hit.title.isEmpty() ? e.title : hit.title), clip(e.fileName));
      cb.onResolved(e, hit, bucket);
      if (bucket == BUCKET_DOWNLOADABLE) {
         try {
            processDeps(hit, ctx, 1, cb);
         } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
         }
      }
   }

   private static void decRemaining(AtomicInteger remaining, List<ModSyncEntry> failed, int pass, Ctx ctx, Callback cb) {
      if (remaining.decrementAndGet() != 0) {
         return;
      }

      if (!failed.isEmpty() && pass == 0 && ctx.netFailures.get() < MAX_NET_FAILURES) {
         ModSyncLog.info("resolve retry pass for {} failed entries", failed.size());
         resolveBatch(new ArrayList<>(failed), 1, ctx, cb);
      } else {
         if (!failed.isEmpty()) {
            ModSyncLog.warn("resolve: {} entries unresolved (network), retry next join", failed.size());
         }

         cb.onFinished(ctx.totalResolved.get());
      }
   }

   /** 前置跟随：先查清单/下载清单/本地，没有才补下（深度受限）。 */
   private static void processDeps(ModSyncEntry hit, Ctx ctx, int depth, Callback cb) throws InterruptedException {
      if (depth > MAX_DEP_DEPTH || ctx.added.get() >= MAX_DEP_ADD) {
         return;
      }

      for (String pid : hit.depProjectIds) {
         if (ctx.added.get() >= MAX_DEP_ADD) {
            return;
         }

         if (ctx.seen.contains("mr:" + pid)) {
            continue;
         }

         ctx.seen.add("mr:" + pid);
         try {
            ModSyncEntry dep = resolveMrDep(pid);
            if (dep == null || dep.downloadUrl.isEmpty()) {
               continue;
            }

            if (isCovered(dep, ctx)) {
               continue;
            }

            markSeen(ctx, dep);
            ctx.added.incrementAndGet();
            ctx.totalResolved.incrementAndGet();
            ModSyncLog.info("modsync resolve: dep-added-mr {} ({})", clip(dep.title), clip(dep.fileName));
            cb.onResolved(null, dep, BUCKET_DOWNLOADABLE);
            processDeps(dep, ctx, depth + 1, cb);
         } catch (Exception ex) {
            ModSyncLog.warn("dep {} failed: {}", pid, ex.toString());
         }
      }

      for (String id : hit.cfDepIds) {
         if (ctx.added.get() >= MAX_DEP_ADD) {
            return;
         }

         int i = id.indexOf(':');
         if (i <= 0) {
            continue;
         }

         long modId;
         long fileId;
         try {
            modId = Long.parseLong(id.substring(0, i));
            fileId = Long.parseLong(id.substring(i + 1));
         } catch (NumberFormatException nfe) {
            continue;
         }

         try {
            ModSyncEntry dep = resolveCfDep(modId, fileId);
            if (dep == null || dep.downloadUrl.isEmpty()) {
               continue;
            }

            if (isCovered(dep, ctx)) {
               continue;
            }

            markSeen(ctx, dep);
            ctx.added.incrementAndGet();
            ctx.totalResolved.incrementAndGet();
            ModSyncLog.info("modsync resolve: dep-added-cf {} ({})", clip(dep.title), clip(dep.fileName));
            cb.onResolved(null, dep, BUCKET_DOWNLOADABLE);
            processDeps(dep, ctx, depth + 1, cb);
         } catch (Exception ex) {
            ModSyncLog.warn("cf dep {} failed: {}", id, ex.toString());
         }
      }
   }

   /** MR 前置：project 元数据 + 本端兼容版本；无文件时 CF 兜底。 */
   private static ModSyncEntry resolveMrDep(String projectId) throws IOException, InterruptedException {
      guard();
      JsonObject meta = ModrinthClient.project(projectId);
      if (meta == null) {
         return null;
      }

      guard();
      JsonObject version = ModrinthClient.latestVersionFor(projectId, ModSyncEnv.GAME_VERSION, ModSyncEnv.LOADER);
      ModSyncEntry out = ModSyncEntry.fromMetaAndVersion(meta, version);
      out.depProjectIds.addAll(requiredProjectIds(version));
      if (out.downloadUrl.isEmpty()) {
         tryCfFile(out, out.title, out.fileName);
      }

      return out;
   }

   /** CF 前置：fileDetail 直取文件 + modDetail 补真名真图标；无地址走 CDN 兜底。 */
   private static ModSyncEntry resolveCfDep(long modId, long fileId) throws IOException, InterruptedException {
      guard();
      JsonObject f = CurseForgeClient.fileDetail((int) modId, fileId);
      if (f == null || str(f, "fileName").isEmpty()) {
         return null;
      }

      ModSyncEntry out = new ModSyncEntry();
      out.foundOn = "cf";
      out.fileName = str(f, "fileName");
      out.downloadUrl = str(f, "downloadUrl");
      if (out.downloadUrl.isEmpty()) {
         out.downloadUrl = CurseForgeClient.cdnUrl(fileId, out.fileName);
      }

      out.size = f.has("fileLength") && f.get("fileLength").isJsonPrimitive() ? f.get("fileLength").getAsLong() : 0L;
      fillCfHash(out, f);
      guard();
      JsonObject m = CurseForgeClient.modDetail((int) modId);
      if (m != null) {
         out.title = str(m, "name");
         out.slug = str(m, "slug");
         JsonObject logo = m.has("logo") && m.get("logo").isJsonObject() ? m.getAsJsonObject("logo") : null;
         out.iconUrl = logo == null ? "" : (str(logo, "thumbnailUrl").isEmpty() ? str(logo, "url") : str(logo, "thumbnailUrl"));
      }

      if (out.title.isEmpty()) {
         out.title = str(f, "displayName");
      }

      if (out.title.isEmpty()) {
         out.title = ModSyncEntry.guessTitleFromFileName(out.fileName);
      }

      out.cfDepIds.addAll(cfDepIdsOf(f));
      return out;
   }

   /** 条目是否已被覆盖：清单/已发前置按归一名，本地 mods 按归一名+sha1。 */
   private static boolean isCovered(ModSyncEntry dep, Ctx ctx) {
      String nb = normalizeFileName(dep.fileName);
      if (!nb.isEmpty() && ctx.norms.contains(nb)) {
         return true;
      }

      ctx.scanLocal();
      if (!nb.isEmpty() && ctx.localNorms.contains(nb)) {
         return true;
      }

      return !dep.sha1.isEmpty() && ctx.localSha1s.contains(dep.sha1);
   }

   private static void markSeen(Ctx ctx, ModSyncEntry e) {
      if (e == null) {
         return;
      }

      if (!e.projectId.isEmpty()) {
         ctx.seen.add("mr:" + e.projectId);
      }

      String nb = normalizeFileName(e.fileName);
      if (!nb.isEmpty()) {
         ctx.norms.add(nb);
      }
   }

   private static int bucketOf(ModSyncEntry hit) {
      if (!hit.downloadUrl.isEmpty()) {
         return BUCKET_DOWNLOADABLE;
      }

      return "cf".equals(hit.foundOn) ? BUCKET_NOT_FOUND : BUCKET_INCOMPATIBLE;
   }

   private static ModSyncEntry query(ModSyncEntry e, String key) throws IOException, InterruptedException {
      String loader = ModSyncEnv.LOADER;
      String gv = ModSyncEnv.GAME_VERSION;
      JsonObject meta = null;
      String projectId = "";
      if (!e.projectId.isEmpty()) {
         projectId = e.projectId;
         if (!e.title.isBlank() && !e.iconUrl.isBlank()) {
            // 新版清单已带真名真图标：跳过元数据请求，只查兼容文件
            meta = metaFrom(e);
         } else {
            guard();
            meta = ModrinthClient.project(projectId);
         }
      } else {
         String q = ModSyncEntry.guessTitleFromFileName(e.fileName);
         if (q.isEmpty()) {
            q = stripJar(e.fileName);
         }

         guard();
         JsonArray hits = ModrinthClient.search(q, loader, gv);
         JsonObject hit = pickHit(hits, q);
         if (hit == null) {
            // 放宽：去掉版本过滤再试（版本标注差异经常误伤）
            guard();
            hits = ModrinthClient.search(q, loader, "");
            hit = pickHit(hits, q);
         }

         if (hit == null) {
            // 再用原始文件名试（猜名切不动的黏连名常有救）
            String raw = stripJar(e.fileName);
            if (!raw.isBlank() && !raw.equalsIgnoreCase(q)) {
               guard();
               hits = ModrinthClient.search(raw, loader, "");
               hit = pickHit(hits, raw);
            }
         }

         if (hit != null) {
            meta = hit;
            projectId = str(hit, "project_id");
         }
      }

      if (meta == null || projectId == null || projectId.isEmpty()) {
         // MR 无此项目 → CF 镜像免 key 补真名真图标（能下则连下载信息一起）
         ModSyncEntry cf = cfLookup(e);
         if (cf != null) {
            RESOLVED.put(key, cf);
            return cf;
         }

         NEGATIVE.add(key);
         return null;
      }

      guard();
      JsonObject version = ModrinthClient.latestVersionFor(projectId, gv, loader);
      ModSyncEntry out = new ModSyncEntry();
      out.projectId = projectId;
      out.slug = str(meta, "slug");
      out.title = str(meta, "title");
      out.iconUrl = str(meta, "icon_url");
      out.versionNumber = e.versionNumber;
      if (version != null) {
         out.versionNumber = str(version, "version_number");
         out.loaders = strList(version, "loaders");
         out.gameVersions = strList(version, "game_versions");
         JsonObject file = ModrinthClient.primaryFile(version);
         if (file != null) {
            out.fileName = str(file, "filename");
            out.downloadUrl = str(file, "url");
            out.size = file.has("size") && file.get("size").isJsonPrimitive() ? file.get("size").getAsLong() : 0L;
            fillHashes(out, file);
         }

         out.depProjectIds.addAll(requiredProjectIds(version));
      }

      if (out.fileName.isEmpty()) {
         // MR 无兼容文件 → CF 兜底找本端可用文件（双平台收录的模组常在此得救）
         if (!tryCfFile(out, str(meta, "title"), e.fileName)) {
            out.fileName = e.fileName;
            out.size = e.size;
         }
      }

      RESOLVED.put(key, out);
      return out;
   }

   /** e 自身元数据 → 基础条目（清单已带真名真图标时省一次请求）。 */
   private static JsonObject metaFrom(ModSyncEntry e) {
      JsonObject o = new JsonObject();
      o.addProperty("slug", e.slug);
      o.addProperty("title", e.title);
      o.addProperty("icon_url", e.iconUrl);
      return o;
   }

   /** CF 免 key 查询：真名+真图标，能找到本端文件则连下载信息一并补齐；未找到返回 null。网络失败上抛。 */
   private static ModSyncEntry cfLookup(ModSyncEntry e) throws IOException, InterruptedException {
      String q = ModSyncEntry.guessTitleFromFileName(e.fileName);
      if (q.isEmpty()) {
         q = stripJar(e.fileName);
      }

      guard();
      JsonObject m = pickCf(CurseForgeClient.search(q, ModSyncEnv.GAME_VERSION, ModSyncEnv.LOADER), q);
      if (m == null) {
         String raw = stripJar(e.fileName);
         m = pickCf(CurseForgeClient.search(raw, "", 0), raw);
      }

      if (m == null) {
         return null;
      }

      ModSyncEntry out = new ModSyncEntry();
      out.foundOn = "cf";
      out.title = str(m, "name");
      out.slug = str(m, "slug");
      JsonObject logo = m.has("logo") && m.get("logo").isJsonObject() ? m.getAsJsonObject("logo") : null;
      String icon = logo == null ? "" : (str(logo, "thumbnailUrl").isEmpty() ? str(logo, "url") : str(logo, "thumbnailUrl"));
      out.iconUrl = icon;
      out.fileName = e.fileName;
      if (out.title.isEmpty() && out.iconUrl.isEmpty()) {
         return null;
      }

      if (out.title.isEmpty()) {
         out.title = ModSyncEntry.guessTitleFromFileName(e.fileName);
      }

      tryCfFile(out, out.title, e.fileName);
      return out;
   }

   /** 按名称在 CF 找本端 版本+加载器 的文件：多查询变体直到命中。网络失败上抛。 */
   private static boolean tryCfFile(ModSyncEntry out, String queryName, String fileNameHint) throws IOException, InterruptedException {
      // 变体去重：传入名 / 文件名猜名 / 原始文件名
      LinkedHashSet<String> variants = new LinkedHashSet<>();
      if (queryName != null && !queryName.isBlank()) {
         variants.add(queryName);
      }

      if (fileNameHint != null && !fileNameHint.isBlank()) {
         String g = ModSyncEntry.guessTitleFromFileName(fileNameHint);
         if (!g.isBlank()) {
            variants.add(g);
         }

         String raw = stripJar(fileNameHint);
         if (!raw.isBlank()) {
            variants.add(raw);
         }
      }

      if (variants.isEmpty()) {
         return false;
      }

      JsonObject m = null;
      String usedQuery = "";
      for (String v : variants) {
         guard();
         m = pickCf(CurseForgeClient.search(v, ModSyncEnv.GAME_VERSION, ModSyncEnv.LOADER), v);
         if (m != null) {
            usedQuery = v;
            break;
         }
      }

      if (m == null) {
         return false;
      }

      JsonObject idx = CurseForgeClient.pickFileIndex(m, ModSyncEnv.GAME_VERSION, ModSyncEnv.LOADER);
      if (idx == null) {
         return false;
      }

      long fileId = 0L;
      try {
         fileId = Long.parseLong(str(idx, "fileId"));
      } catch (NumberFormatException nfe) {
         return false;
      }

      int modId = 0;
      try {
         modId = Integer.parseInt(str(m, "id"));
      } catch (NumberFormatException nfe) {
         return false;
      }

      guard();
      JsonObject f = CurseForgeClient.fileDetail(modId, fileId);
      if (f == null || str(f, "downloadUrl").isEmpty()) {
         return false;
      }

      out.foundOn = "cf";
      out.downloadUrl = str(f, "downloadUrl");
      out.fileName = str(f, "fileName");
      try {
         out.size = f.has("fileLength") && f.get("fileLength").isJsonPrimitive() ? f.get("fileLength").getAsLong() : 0L;
      } catch (Exception ignored) {
         out.size = 0L;
      }

      fillCfHash(out, f);
      out.cfDepIds.addAll(cfDepIdsOf(f));
      return true;
   }

   /** CF file 的 required 前置（relationType==3，modId:fileId）。 */
   private static List<String> cfDepIdsOf(JsonObject f) {
      List<String> out = new ArrayList<>();
      if (f == null || !f.has("dependencies") || !f.get("dependencies").isJsonArray()) {
         return out;
      }

      for (JsonElement el : f.getAsJsonArray("dependencies")) {
         if (!el.isJsonObject()) {
            continue;
         }

         JsonObject d = el.getAsJsonObject();
         if (!"3".equals(str(d, "relationType"))) {
            continue;
         }

         try {
            long mid = Long.parseLong(str(d, "modId"));
            long fid = Long.parseLong(str(d, "fileId"));
            if (mid > 0 && fid > 0) {
               out.add(mid + ":" + fid);
            }
         } catch (NumberFormatException ignored) {
         }
      }

      return out;
   }

   /** MR version 的 required 前置 project_id。 */
   private static List<String> requiredProjectIds(JsonObject version) {
      List<String> out = new ArrayList<>();
      if (version == null || !version.has("dependencies") || !version.get("dependencies").isJsonArray()) {
         return out;
      }

      for (JsonElement el : version.getAsJsonArray("dependencies")) {
         if (!el.isJsonObject()) {
            continue;
         }

         JsonObject d = el.getAsJsonObject();
         if ("required".equalsIgnoreCase(str(d, "dependency_type"))) {
            String pid = str(d, "project_id");
            if (!pid.isEmpty()) {
               out.add(pid);
            }
         }
      }

      return out;
   }

   private static void fillCfHash(ModSyncEntry out, JsonObject f) {
      if (!f.has("hashes") || !f.get("hashes").isJsonArray()) {
         return;
      }

      for (JsonElement el : f.getAsJsonArray("hashes")) {
         if (!el.isJsonObject()) {
            continue;
         }

         JsonObject h = el.getAsJsonObject();
         if ("1".equals(str(h, "algo"))) {
            out.sha1 = str(h, "value");
            out.sha512 = "";
            return;
         }
      }
   }

   /** 原条目原地升级真名/真图标（列表位置由回调方决定）。 */
   private static void applyMeta(ModSyncEntry orig, ModSyncEntry resolved) {
      if (!resolved.title.isEmpty()) {
         orig.title = resolved.title;
      }

      if (!resolved.iconUrl.isEmpty()) {
         orig.iconUrl = resolved.iconUrl;
      }

      if (!resolved.slug.isEmpty()) {
         orig.slug = resolved.slug;
      }

      if (orig.projectId.isEmpty()) {
         orig.projectId = resolved.projectId;
      }
   }

   /** 优先 slug 精确匹配，否则首个通过质量闸门的候选；无合格返回 null。 */
   private static JsonObject pickHit(JsonArray hits, String query) {
      return pickCandidate(hits, query, "title");
   }

   /** CF 候选选择（name 字段版）。 */
   private static JsonObject pickCf(JsonArray hits, String query) {
      return pickCandidate(hits, query, "name");
   }

   private static JsonObject pickCandidate(JsonArray hits, String query, String titleField) {
      if (hits == null || hits.size() == 0) {
         return null;
      }

      String want = query.toLowerCase(Locale.ROOT).replace(' ', '-');
      JsonObject first = null;
      for (JsonElement el : hits) {
         if (!el.isJsonObject()) {
            continue;
         }

         JsonObject h = el.getAsJsonObject();
         if (want.equals(str(h, "slug"))) {
            return h;
         }

         if (first == null && accept(query, str(h, titleField), str(h, "slug"))) {
            first = h;
         }
      }

      return first;
   }

   /**
    * 匹配质量闸门：查询与标题/slug 分别归一化（仅留字母数字）后互为包含才认。
    * 例：sophisticatedbackpacks 不会误配 Evil Seagull；
    * ironchestsforgemod269j 仍可命中 Iron Chests。
    */
   static boolean accept(String query, String title, String slug) {
      return matchOne(query, title, 3) || matchOne(query, slug, 4);
   }

   private static boolean matchOne(String query, String candidate, int minCand) {
      String a = norm(query);
      String b = norm(candidate);
      if (a.isEmpty() || b.isEmpty()) {
         return false;
      }

      if (a.length() >= 3 && b.contains(a)) {
         return true;
      }

      return b.length() >= minCand && a.contains(b);
   }

   private static String norm(String s) {
      if (s == null) {
         return "";
      }

      StringBuilder sb = new StringBuilder(s.length());
      for (char c : s.toLowerCase(Locale.ROOT).toCharArray()) {
         if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
            sb.append(c);
         }
      }

      return sb.toString();
   }

   /** 与房客 diff 同款归一化：小写去 .jar 只留字母并剔加载器噪音词。 */
   static String normalizeFileName(String fileName) {
      if (fileName == null) {
         return "";
      }

      String s = fileName.toLowerCase(Locale.ROOT);
      if (s.endsWith(".jar")) {
         s = s.substring(0, s.length() - 4);
      }

      List<String> stop = java.util.Arrays.asList(
         "fabric", "forge", "neoforge", "quilt", "fml", "mc", "all", "for", "the", "with");
      StringBuilder sb = new StringBuilder(s.length());
      for (String tok : s.split("[^a-z]+")) {
         if (tok.isEmpty() || stop.contains(tok)) {
            continue;
         }

         sb.append(tok);
      }

      return sb.toString();
   }

   /** 滚动窗限速：超窗后自旋等待，保护镜像与官方端点。 */
   private static void guard() throws InterruptedException {
      long now = System.currentTimeMillis();
      long win = WINDOW_START.get();
      if (now - win >= WINDOW_MS) {
         WINDOW_START.set(now);
         WINDOW_COUNT.set(0);
      }

      while (WINDOW_COUNT.incrementAndGet() > MAX_PER_MINUTE) {
         WINDOW_COUNT.decrementAndGet();
         Thread.sleep(200L);
         long n2 = System.currentTimeMillis();
         if (n2 - WINDOW_START.get() >= WINDOW_MS) {
            WINDOW_START.set(n2);
            WINDOW_COUNT.set(0);
         }
      }
   }

   private static void fillHashes(ModSyncEntry out, JsonObject file) {
      if (!file.has("hashes") || !file.get("hashes").isJsonObject()) {
         return;
      }

      JsonObject hashes = file.getAsJsonObject("hashes");
      if (hashes.has("sha1") && !hashes.get("sha1").isJsonNull()) {
         out.sha1 = hashes.get("sha1").getAsString();
      }

      if (hashes.has("sha512") && !hashes.get("sha512").isJsonNull()) {
         out.sha512 = hashes.get("sha512").getAsString();
      }
   }

   private static List<String> strList(JsonObject o, String key) {
      java.util.ArrayList<String> out = new java.util.ArrayList<>();
      if (o.has(key) && o.get(key).isJsonArray()) {
         for (JsonElement el : o.getAsJsonArray(key)) {
            if (el.isJsonPrimitive()) {
               out.add(el.getAsString());
            }
         }
      }

      return out;
   }

   private static String keyOf(ModSyncEntry e) {
      return !e.projectId.isEmpty() ? "id:" + e.projectId : "file:" + e.fileName;
   }

   private static String stripJar(String fileName) {
      String s = fileName == null ? "" : fileName;
      int i = s.toLowerCase(Locale.ROOT).lastIndexOf(".jar");
      return i > 0 ? s.substring(0, i) : s;
   }

   private static String clip(String s) {
      if (s == null) {
         return "";
      }

      return s.length() <= 48 ? s : s.substring(0, 47) + "…";
   }

   private static String str(JsonObject o, String key) {
      return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
   }
}
