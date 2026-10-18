package icu.wuhui.voxlink.modsync;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Modrinth API v2 最小客户端（公开只读接口 + CDN 下载）。
 * 用到的接口：POST /version_files（sha1 批量反查版本）、GET /projects?ids=（批量项目元数据）、
 * GET /project/{id}/version（按 MC 版本+加载器过滤）、CDN 文件下载（sha512 校验）。
 */
public final class ModrinthClient {
   private static final String API = "https://api.modrinth.com/v2";
   /** MCIM 镜像（PCL 同款）：国内直连 MR 慢且不稳，镜像短超时优先、官方兜底。 */
   private static final String MIRROR_API = "https://mod.mcimirror.top/modrinth/v2";
   private static final String MIRROR_PREFIX = "https://mod.mcimirror.top";
   private static final Duration MIRROR_TIMEOUT = Duration.ofSeconds(8);
   private static final String USER_AGENT = "VoxLink/" + voxlinkVersion() + " (modsync)";
   private static final int CHUNK = 64;
   private static final Duration TIMEOUT = Duration.ofSeconds(20);
   /** 等待响应头（含重定向）的最长时间。 */
   private static final long HEADER_TIMEOUT_MS = 15000L;
   private static final long MAX_DOWNLOAD_BYTES = 512L * 1024 * 1024;
   /** 弱网/国内网络下载退化：最大尝试次数。顺序为 直连原URL、直连重试(新建连接)、走自建代理。 */
   public static final int MAX_ATTEMPTS = 2;
   /** 尝试间退避：第 1 次后 1s、第 2 次后 3s。 */
   private static final long BACKOFF_MS_1 = 1000L;
   private static final long BACKOFF_MS_2 = 3000L;
   private static volatile HttpClient http;

   private ModrinthClient() {
   }

   private static String voxlinkVersion() {
      try {
         return icu.wuhui.voxlink.VoxLinkMod.MOD_VERSION;
      } catch (Exception e) {
         return "?";
      }
   }

   private static HttpClient client() {
      HttpClient c = http;
      if (c == null) {
         synchronized (ModrinthClient.class) {
            c = http;
            if (c == null) {
               // FOLLOW 重定向：CDN 部分文件会 307 跳转，不跟随会被当成下载失败
               c = HttpClient.newBuilder()
                  .connectTimeout(Duration.ofSeconds(10))
                  .followRedirects(HttpClient.Redirect.NORMAL)
                  .build();
               http = c;
            }
         }
      }

      return c;
   }

   /** sha1 → version JSON；MR 不认识的哈希在返回 map 中缺席。 */
   public static Map<String, JsonObject> versionsFromSha1(List<String> sha1List) throws IOException, InterruptedException {
      Map<String, JsonObject> out = new HashMap<>();
      for (int from = 0; from < sha1List.size(); from += CHUNK) {
         List<String> chunk = sha1List.subList(from, Math.min(sha1List.size(), from + CHUNK));
         JsonObject body = new JsonObject();
         JsonArray arr = new JsonArray();
         chunk.forEach(arr::add);
         body.add("hashes", arr);
         body.addProperty("algorithm", "sha1");
         JsonObject resp;
         try {
            resp = postJson(apiUrls("/version_files"), body.toString());
         } catch (InterruptedException ie) {
            throw ie;
         } catch (Exception e) {
            // 分块网络失败只损失该块（落入 unknownMods），不作废整张清单（实证 MXZD5N：单块超时全清单报废）
            ModSyncLog.warn("version_files chunk failed ({} hashes): {}", chunk.size(), e.getMessage());
            continue;
         }
         if (resp == null) {
            continue;
         }

         for (Map.Entry<String, JsonElement> e : resp.entrySet()) {
            if (e.getValue().isJsonObject()) {
               out.put(e.getKey(), e.getValue().getAsJsonObject());
            }
         }
      }

      return out;
   }

   /** projectId → project JSON。 */
   public static Map<String, JsonObject> projectsByIds(Set<String> ids) throws IOException, InterruptedException {
      Map<String, JsonObject> out = new HashMap<>();
      List<String> list = new ArrayList<>(ids);
      for (int from = 0; from < list.size(); from += CHUNK) {
         List<String> chunk = list.subList(from, Math.min(list.size(), from + CHUNK));
         StringBuilder sb = new StringBuilder("[");
         for (int i = 0; i < chunk.size(); i++) {
            if (i > 0) {
               sb.append(',');
            }

            sb.append('"').append(chunk.get(i)).append('"');
         }

         sb.append(']');
         java.util.List<String> urls = apiUrls("/projects?ids=" + URLEncoder.encode(sb.toString(), StandardCharsets.UTF_8));
         JsonArray arr;
         try {
            arr = getJsonArray(urls);
         } catch (InterruptedException ie) {
            throw ie;
         } catch (Exception e) {
            // 分块失败降级：元数据缺失由调用方 fail-open 处理，不作废整批
            ModSyncLog.warn("projects chunk failed ({} ids): {}", chunk.size(), e.getMessage());
            continue;
         }
         if (arr == null) {
            continue;
         }

         for (JsonElement el : arr) {
            if (el.isJsonObject() && el.getAsJsonObject().has("id")) {
               out.put(el.getAsJsonObject().get("id").getAsString(), el.getAsJsonObject());
            }
         }
      }

      return out;
   }

   /** 指定项目在本端 MC 版本+加载器下的最新版本；无兼容版本返回 null。 */
   public static JsonObject latestVersionFor(String projectId, String gameVersion, String loader) throws IOException, InterruptedException {
      String gv = URLEncoder.encode("[\"" + escape(gameVersion) + "\"]", StandardCharsets.UTF_8);
      String ld = URLEncoder.encode("[\"" + escape(loader) + "\"]", StandardCharsets.UTF_8);
      JsonArray arr = getJsonArray(apiUrls("/project/" + escape(projectId) + "/version?game_versions=" + gv + "&loaders=" + ld));
      return arr != null && arr.size() > 0 && arr.get(0).isJsonObject() ? arr.get(0).getAsJsonObject() : null;
   }

   /** GET /v2/search：名称+加载器搜项目；gameVersion 非空时加版本 facet（空=放宽搜索）。 */
   public static JsonArray search(String query, String loader, String gameVersion) throws IOException, InterruptedException {
      String facets = "[[\"project_type:mod\"],[\"categories:" + escape(loader) + "\"]";
      if (gameVersion != null && !gameVersion.isBlank()) {
         facets += ",[\"versions:" + escape(gameVersion) + "\"]";
      }

      facets += "]";
      java.util.List<String> urls = apiUrls("/search?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
         + "&facets=" + URLEncoder.encode(facets, StandardCharsets.UTF_8) + "&limit=5");
      JsonObject o = getJsonObject(urls, false);
      return o != null && o.has("hits") && o.get("hits").isJsonArray() ? o.getAsJsonArray("hits") : new JsonArray();
   }

   /** GET /v2/project/{id|slug}；项目不存在(404)返回 null。 */
   public static JsonObject project(String idOrSlug) throws IOException, InterruptedException {
      return getJsonObject(apiUrls("/project/" + URLEncoder.encode(idOrSlug, StandardCharsets.UTF_8)), true);
   }

   /** API 请求链：镜像短超时在前，官方兜底在后。 */
   private static java.util.List<String> apiUrls(String pathAndQuery) {
      return java.util.Arrays.asList(MIRROR_API + pathAndQuery, API + pathAndQuery);
   }

   /**
    * 下载到 mods 目录：先写 .part，校验哈希后原子改名。
    * 弱网策略：最多 {@link #MAX_ATTEMPTS} 次尝试，全部 Modrinth 直连。
    * 尝试之间退避 1s / 3s。任一阶段命中 shouldAbort 立即上抛 AbortedException（取消逻辑不被破坏）。
    * 全部失败时收集每次错误简述，最终抛出的 IOException message 前缀 "MODRINTH:"。
    *
    * @param expectedSha512 Modrinth 文件的 sha512；非空时必须匹配
    * @param expectedSha1   回退校验：sha512 缺失但 sha1 存在时使用
    * @return 实际写入并通过校验的字节数
    * @throws AbortedException 当 shouldAbort 返回 true 被主动取消
    */
   public static long downloadVerified(String url, String expectedSha512, String expectedSha1, String fileName, Path modsDir,
                                       java.util.function.BooleanSupplier shouldAbort) throws IOException, InterruptedException {
      ensureSafeFileName(fileName);

      StringBuilder errors = new StringBuilder();
      HttpClient cli = client();

      for (int i = 0; i < MAX_ATTEMPTS; i++) {
         if (shouldAbort.getAsBoolean()) {
            throw new AbortedException(fileName);
         }

         // 尝试 0 走 MCIM 镜像（国内快），尝试 1 回官方 CDN
         String attemptUrl = i == 0 ? mirrorDownloadUrl(url) : url;
         try {
            long bytes = doDownload(attemptUrl, cli, expectedSha512, expectedSha1, fileName, modsDir, shouldAbort);
            return bytes;
         } catch (AbortedException ae) {
            throw ae; // 用户取消，立即上抛，不重试
         } catch (PermanentHttpException pe) {
            // 镜像层 4xx 不作数：还有官方尝试就换源，官方 4xx 才是永久失败
            if (i < MAX_ATTEMPTS - 1 && !attemptUrl.equals(url)) {
               continue;
            }

            throw pe;
         } catch (IOException e) {
            if (shouldAbort.getAsBoolean()) {
               throw new AbortedException(fileName);
            }
            if (errors.length() > 0) {
               errors.append(" | ");
            }
            errors.append(e.getMessage());
         }

         // 尝试间退避：第 1 次后 1s，第 2 次后 3s
         if (i < MAX_ATTEMPTS - 1) {
            long backoff = (i == 0) ? BACKOFF_MS_1 : BACKOFF_MS_2;
            try {
               Thread.sleep(backoff);
            } catch (InterruptedException ie) {
               Thread.currentThread().interrupt();
               if (shouldAbort.getAsBoolean()) {
                  throw new AbortedException(fileName);
               }
            }
         }
      }

      // 全部尝试失败：错误简述拼接，最终 message 前缀 MODRINTH:
      String msg = errors.length() > 0 ? errors.toString() : "all attempts failed";
      throw new IOException("MODRINTH: " + msg);
   }

   /** 旧 5 参重载：无 sha1 期望，保留二进制兼容。 */
   public static long downloadVerified(String url, String expectedSha512, String fileName, Path modsDir,
                                       java.util.function.BooleanSupplier shouldAbort) throws IOException, InterruptedException {
      return downloadVerified(url, expectedSha512, null, fileName, modsDir, shouldAbort);
   }

   /**
    * 单次下载尝试：先写 .part，校验哈希后原子改名。
    * 可中断版：sendAsync + 轮询 shouldAbort——点"取消下载"要在秒级内掐断底层连接，
    * 而不是等当前阻塞的 read/connect 慢慢超时（手机网络上 connect 超时 10s 就够让人以为卡死）。
    *
    * @return 实际写入并通过校验的字节数
    * @throws AbortedException 当 shouldAbort 返回 true 被主动取消
    */
   private static long doDownload(String url, HttpClient cli, String expectedSha512, String expectedSha1, String fileName, Path modsDir,
                                  java.util.function.BooleanSupplier shouldAbort) throws IOException, InterruptedException {
      ensureSafeFileName(fileName);

      HttpRequest request = HttpRequest.newBuilder(URI.create(url))
         .header("User-Agent", USER_AGENT)
         .timeout(TIMEOUT)
         .GET()
         .build();
      java.util.concurrent.CompletableFuture<HttpResponse<java.io.InputStream>> future =
         cli.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());

      // 阶段1：等待响应头（含重定向跟随），期间随时可取消
      long headerDeadline = System.currentTimeMillis() + HEADER_TIMEOUT_MS;
      HttpResponse<java.io.InputStream> resp;
      while (true) {
         if (shouldAbort.getAsBoolean()) {
            future.cancel(true);
            throw new AbortedException(fileName);
         }

         try {
            resp = future.get(200L, java.util.concurrent.TimeUnit.MILLISECONDS);
            break;
         } catch (java.util.concurrent.TimeoutException te) {
            if (System.currentTimeMillis() > headerDeadline) {
               future.cancel(true);
               throw new IOException("HTTP headers timeout for " + fileName);
            }
         } catch (java.util.concurrent.ExecutionException ee) {
            throw asIo(ee, fileName);
         }
      }

      if (resp.statusCode() == 429) {
         // 429：留给外层重试（按 Retry-After 退避）；不上抛 permanent
         throw new IOException("HTTP 429 for " + fileName);
      }

      if (resp.statusCode() / 100 == 4 || resp.body() == null) {
         // 4xx（除 429）：永久失败，调用方不应重试
         throw new PermanentHttpException("HTTP " + resp.statusCode() + " for " + fileName);
      }

      if (resp.statusCode() / 100 != 2) {
         throw new IOException("HTTP " + resp.statusCode() + " for " + fileName);
      }

      // Content-Length 预检：响应头已声明超限就拒绝写盘，连 .part 都不创建
      java.util.Optional<String> clen = resp.headers().firstValue("Content-Length");
      if (clen.isPresent()) {
         try {
            long declared = Long.parseLong(clen.get().trim());
            if (declared > MAX_DOWNLOAD_BYTES) {
               throw new IOException("declared Content-Length " + declared + " exceeds limit for " + fileName);
            }
         } catch (NumberFormatException nfe) {
            // 解析失败忽略，按流式上限兜底
         }
      }

      Path target = modsDir.resolve(fileName);
      // 唯一 .part：并发/残留写入不再互相踩踏
      Path part = modsDir.resolve(fileName + "." + System.nanoTime() + ".part");
      MessageDigest sha512;
      MessageDigest sha1;
      try {
         sha512 = MessageDigest.getInstance("SHA-512");
         sha1 = MessageDigest.getInstance("SHA-1");
      } catch (Exception e) {
         throw new IOException("hash algorithm unavailable", e);
      }

      long total = 0L;
      // 取消监视线程：read() 阻塞时也能在 150ms 内掐断（cancel(true) 会关闭底层连接，
      // 阻塞中的 read 立刻抛 IOException）。没有它，"取消下载"在手机网络弱连接下形同虚设。
      final java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean(false);
      Thread cancelWatch = new Thread(() -> {
         while (!done.get() && !future.isDone()) {
            if (shouldAbort.getAsBoolean()) {
               future.cancel(true);
               return;
            }

            try {
               Thread.sleep(150L);
            } catch (InterruptedException ie) {
               return;
            }
         }
      }, "VoxLink-DL-CancelWatch");
      cancelWatch.setDaemon(true);
      cancelWatch.start();

      boolean abortedFlag = false;
      IOException ioFailure = null;
      try (java.io.InputStream in = resp.body()) {
         try (var out = Files.newOutputStream(part)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
               total += n;
               if (total > MAX_DOWNLOAD_BYTES) {
                  future.cancel(true);
                  throw new IOException("file too large: " + fileName);
               }

               sha512.update(buf, 0, n);
               sha1.update(buf, 0, n);
               out.write(buf, 0, n);
            }
         }
      } catch (IOException e) {
         if (shouldAbort.getAsBoolean()) {
            abortedFlag = true;
         } else {
            ioFailure = e;
         }
      } finally {
         done.set(true);
         future.cancel(true);
      }

      if (abortedFlag || shouldAbort.getAsBoolean()) {
         quietDelete(part);
         throw new AbortedException(fileName);
      }

      if (ioFailure != null) {
         quietDelete(part);
         throw ioFailure;
      }

      // 校验：sha512 优先；缺失则回退到 sha1；都缺失放行（兼容老调用方）
      String actualSha512 = hex(sha512);
      String actualSha1 = hex(sha1);
      boolean sha512Wanted = expectedSha512 != null && !expectedSha512.isBlank();
      boolean sha1Wanted = expectedSha1 != null && !expectedSha1.isBlank();
      if (sha512Wanted) {
         if (!actualSha512.equalsIgnoreCase(expectedSha512)) {
            quietDelete(part);
            throw new IOException("sha512 mismatch: " + fileName);
         }
      } else if (sha1Wanted) {
         if (!actualSha1.equalsIgnoreCase(expectedSha1)) {
            quietDelete(part);
            throw new IOException("sha1 mismatch: " + fileName);
         }
      } else {
         ModSyncLog.warn("no expected hash for {}; skipping verification", fileName);
      }

      // 原子改名：优先 ATOMIC_MOVE；失败短退避重试 3 次（杀软/占用锁瞬态）
      IOException lastMove = null;
      for (int mv = 0; mv < 3; mv++) {
         try {
            try {
               Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException amns) {
               Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            }

            return total;
         } catch (IOException moveEx) {
            lastMove = moveEx;
            try {
               Thread.sleep(150L << mv);
            } catch (InterruptedException ie) {
               Thread.currentThread().interrupt();
               break;
            }
         }
      }

      quietDelete(part);
      throw lastMove != null ? lastMove : new IOException("move failed: " + fileName);
   }

   /** 用户主动取消专用异常：调用方按"静默回到选择界面"处理，不当失败刷红。 */
   public static final class AbortedException extends IOException {
      public AbortedException(String name) {
         super("aborted: " + name);
      }
   }

   /** 4xx（非 429）等"不要重试"的永久失败；外层重试循环识别后直接上抛。 */
   private static final class PermanentHttpException extends IOException {
      PermanentHttpException(String msg) {
         super(msg);
      }
   }

   private static IOException asIo(java.util.concurrent.ExecutionException ee, String fileName) {
      Throwable c = ee.getCause() != null ? ee.getCause() : ee;
      return c instanceof IOException ioe ? ioe : new IOException(c.toString() + " for " + fileName);
   }

   /** 项目是否为"纯服务端"模组（房客无需安装）。 */
   public static boolean isServerOnly(JsonObject project) {
      if (project == null) {
         return false;
      }

      if (project.has("client_side") && !project.get("client_side").isJsonNull()) {
         // required/optional 都可能被房主世界用到；仅明确不支持客户端的才跳过
         return "unsupported".equalsIgnoreCase(project.get("client_side").getAsString());
      }

      if (project.has("environment") && project.get("environment").isJsonArray()) {
         for (JsonElement el : project.getAsJsonObject().getAsJsonArray("environment")) {
            String v = el.isJsonPrimitive() ? el.getAsString() : "";
            if ("server_only".equalsIgnoreCase(v)) {
               return true;
            }
         }
      }

      return false;
   }

   private static JsonObject postJson(java.util.List<String> urls, String json) throws IOException, InterruptedException {
      HttpResponse<String> resp = sendAny(urls, "Modrinth POST", b -> b
         .header("Content-Type", "application/json")
         .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)));
      if (resp.statusCode() / 100 != 2) {
         throw new IOException("Modrinth POST failed: HTTP " + resp.statusCode());
      }

      JsonElement el = JsonParser.parseString(resp.body());
      return el.isJsonObject() ? el.getAsJsonObject() : null;
   }

   private static JsonArray getJsonArray(java.util.List<String> urls) throws IOException, InterruptedException {
      HttpResponse<String> resp = sendAny(urls, "Modrinth GET", HttpRequest.Builder::GET);
      if (resp.statusCode() / 100 != 2) {
         throw new IOException("Modrinth GET failed: HTTP " + resp.statusCode());
      }

      JsonElement el = JsonParser.parseString(resp.body());
      return el.isJsonArray() ? el.getAsJsonArray() : null;
   }

   private static JsonObject getJsonObject(java.util.List<String> urls, boolean nullOn404) throws IOException, InterruptedException {
      HttpResponse<String> resp;
      try {
         resp = sendAny(urls, "Modrinth GET", HttpRequest.Builder::GET);
      } catch (IOException e) {
         // 404 走 nullOn404 约定（永久失败 message 固定为 "Modrinth GET HTTP <code>"）
         if (nullOn404 && e.getMessage() != null && e.getMessage().endsWith("404")) {
            return null;
         }

         throw e;
      }

      if (resp.statusCode() / 100 != 2) {
         throw new IOException("Modrinth GET failed: HTTP " + resp.statusCode());
      }

      JsonElement el = JsonParser.parseString(resp.body());
      return el.isJsonObject() ? el.getAsJsonObject() : null;
   }

   /**
    * 多源请求链：镜像短超时在前，官方兜底在后（PCL 同款策略）。
    * 429 按小退避换源；4xx/5xx/网络失败换下一源，仅最后一源的 4xx 判永久失败。
    */
   private static HttpResponse<String> sendAny(java.util.List<String> urls, String label,
                                               java.util.function.UnaryOperator<HttpRequest.Builder> method) throws IOException, InterruptedException {
      IOException last = null;
      for (int i = 0; i < urls.size(); i++) {
         boolean isLast = i == urls.size() - 1;
         boolean mirror = urls.get(i).startsWith(MIRROR_PREFIX);
         try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(urls.get(i)))
               .header("User-Agent", USER_AGENT)
               .timeout(mirror ? MIRROR_TIMEOUT : TIMEOUT);
            HttpResponse<String> resp = client().send(method.apply(b).build(), HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();
            if (code == 429) {
               if (!isLast) {
                  Thread.sleep(Math.min(3L, parseRetryAfter(resp)) * 1000L);
                  continue;
               }

               throw new IOException(label + " HTTP 429 (exhausted)");
            }

            if (code / 100 == 4) {
               // 镜像层 4xx 不作数，交给官方端点判定
               if (!isLast) {
                  continue;
               }

               throw new PermanentHttpException(label + " HTTP " + code);
            }

            if (code / 100 == 5) {
               if (!isLast) {
                  continue;
               }

               throw new IOException(label + " HTTP " + code);
            }

            return resp;
         } catch (PermanentHttpException pe) {
            throw pe;
         } catch (IOException ioe) {
            last = ioe;
            if (isLast) {
               throw ioe;
            }
         }
      }

      throw last != null ? last : new IOException(label + ": no endpoint answered");
   }

   private static long parseRetryAfter(HttpResponse<?> resp) {
      return resp.headers().firstValue("Retry-After")
         .map(String::trim)
         .map(s -> {
            try {
               return Long.parseLong(s);
            } catch (NumberFormatException nfe) {
               return 3L;
            }
         })
         .orElse(3L);
   }

   /** 从 version JSON 取 primary 文件对象；无 primary 时取第一个。 */
   public static JsonObject primaryFile(JsonObject version) {
      if (version == null || !version.has("files") || !version.get("files").isJsonArray()) {
         return null;
      }

      JsonArray files = version.getAsJsonArray("files");
      JsonObject first = null;
      for (JsonElement el : files) {
         if (!el.isJsonObject()) {
            continue;
         }

         JsonObject f = el.getAsJsonObject();
         if (f.has("primary") && f.get("primary").isJsonPrimitive() && f.get("primary").getAsBoolean()) {
            return f;
         }

         if (first == null) {
            first = f;
         }
      }

      return first;
   }

   public static Set<String> distinctProjectIds(List<JsonObject> versions) {
      LinkedHashSet<String> ids = new LinkedHashSet<>();
      for (JsonObject v : versions) {
         if (v.has("project_id") && !v.get("project_id").isJsonNull()) {
            ids.add(v.get("project_id").getAsString());
         }
      }

      return ids;
   }

   /** MR CDN / forgecdn → MCIM 镜像；其余原样返回（PCL 同款映射）。 */
   private static String mirrorDownloadUrl(String url) {
      if (url == null) {
         return url;
      }

      if (url.startsWith("https://cdn.modrinth.com/")) {
         return MIRROR_PREFIX + "/" + url.substring("https://cdn.modrinth.com/".length());
      }

      try {
         URI u = URI.create(url);
         String host = u.getHost() == null ? "" : u.getHost();
         boolean cfCdn = host.equals("edge.forgecdn.net") || host.equals("mediafilez.forgecdn.net")
            || host.equals("media.forgecdn.net") || host.endsWith("-service.overwolf.wtf");
         if (cfCdn) {
            return MIRROR_PREFIX + (u.getPath() == null ? "" : u.getPath())
               + (u.getQuery() == null || u.getQuery().isEmpty() ? "" : "?" + u.getQuery());
         }
      } catch (Exception ignored) {
      }

      return url;
   }

   private static String escape(String s) {
      return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
   }

   private static String hex(MessageDigest digest) {
      StringBuilder sb = new StringBuilder(digest.getDigestLength() * 2);
      for (byte b : digest.digest()) {
         sb.append(Character.forDigit(b >> 4 & 15, 16)).append(Character.forDigit(b & 15, 16));
      }

      return sb.toString();
   }

   private static void quietDelete(Path p) {
      try {
         Files.deleteIfExists(p);
      } catch (IOException ignored) {
      }
   }

   /** 防御性 fileName 消毒：主要由调用方负责，这里兜一道防路径穿越/非法字符。 */
   private static void ensureSafeFileName(String fileName) throws IOException {
      if (fileName == null || fileName.isBlank()) {
         throw new IOException("unsafe fileName: empty");
      }

      if (fileName.contains("..") || fileName.contains("/") || fileName.contains("\\") || fileName.contains(":")) {
         throw new IOException("unsafe fileName: " + fileName);
      }

      for (int i = 0; i < fileName.length(); i++) {
         char c = fileName.charAt(i);
         if (c < 0x20 || c == 0x7F) {
            throw new IOException("unsafe fileName (control char): " + fileName);
         }
      }
   }
}
