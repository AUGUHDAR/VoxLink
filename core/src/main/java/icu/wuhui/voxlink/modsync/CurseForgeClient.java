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
import java.time.Duration;
import java.util.Locale;

/**
 * CurseForge 免 key 查询：走 MCIM 镜像（mod.mcimirror.top/curseforge，PCL 同款，
 * 无需官方 API key）。仅查项目元数据（真名/图标），不做下载。
 */
public final class CurseForgeClient {
   private static final String MIRROR = "https://mod.mcimirror.top/curseforge/v1";
   private static final String USER_AGENT = "VoxLink/" + voxlinkVersion() + " (modsync)";
   private static final Duration TIMEOUT = Duration.ofSeconds(12);
   private static volatile HttpClient http;

   private CurseForgeClient() {
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
         synchronized (CurseForgeClient.class) {
            c = http;
            if (c == null) {
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

   /** 名称搜索：返回候选数组（可能为空）。 */
   public static JsonArray search(String query, String gameVersion, String loader) throws IOException, InterruptedException {
      return search(query, gameVersion, loaderType(loader));
   }

   /** 名称搜索（modLoaderType 0=不过滤）：返回候选数组（可能为空）。 */
   public static JsonArray search(String query, String gameVersion, int modLoaderType) throws IOException, InterruptedException {
      if (query == null || query.isBlank()) {
         return new JsonArray();
      }

      StringBuilder qs = new StringBuilder(MIRROR)
         .append("/mods/search?gameId=432&classId=6&sortField=2&sortOrder=desc&pageSize=8");
      if (modLoaderType > 0) {
         qs.append("&modLoaderType=").append(modLoaderType);
      }

      if (gameVersion != null && !gameVersion.isBlank()) {
         qs.append("&gameVersion=").append(URLEncoder.encode(gameVersion, StandardCharsets.UTF_8));
      }

      qs.append("&searchFilter=").append(URLEncoder.encode(query, StandardCharsets.UTF_8));
      HttpRequest request = HttpRequest.newBuilder(URI.create(qs.toString()))
         .header("User-Agent", USER_AGENT)
         .timeout(TIMEOUT)
         .GET()
         .build();
      HttpResponse<String> resp = client().send(request, HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() / 100 != 2) {
         throw new IOException("CF mirror HTTP " + resp.statusCode());
      }

      JsonObject o = JsonParser.parseString(resp.body()).isJsonObject()
         ? JsonParser.parseString(resp.body()).getAsJsonObject() : null;
      if (o == null || !o.has("data") || !o.get("data").isJsonArray()) {
         return new JsonArray();
      }

      return o.getAsJsonArray("data");
   }

   /** 文件详情：modId+fileId → 文件对象（downloadUrl/sha1/fileName/fileLength/dependencies）。 */
   public static JsonObject fileDetail(int modId, long fileId) throws IOException, InterruptedException {
      HttpRequest request = HttpRequest.newBuilder(URI.create(MIRROR + "/mods/" + modId + "/files/" + fileId))
         .header("User-Agent", USER_AGENT)
         .timeout(TIMEOUT)
         .GET()
         .build();
      HttpResponse<String> resp = client().send(request, HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() / 100 != 2) {
         throw new IOException("CF mirror HTTP " + resp.statusCode());
      }

      JsonObject o = JsonParser.parseString(resp.body()).isJsonObject()
         ? JsonParser.parseString(resp.body()).getAsJsonObject() : null;
      return o != null && o.has("data") && o.get("data").isJsonObject() ? o.getAsJsonObject("data") : null;
   }

   /** 项目详情：modId → 项目对象（name/slug/logo），查不到返回 null。 */
   public static JsonObject modDetail(int modId) throws IOException, InterruptedException {
      HttpRequest request = HttpRequest.newBuilder(URI.create(MIRROR + "/mods/" + modId))
         .header("User-Agent", USER_AGENT)
         .timeout(TIMEOUT)
         .GET()
         .build();
      HttpResponse<String> resp = client().send(request, HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() / 100 != 2) {
         throw new IOException("CF mirror HTTP " + resp.statusCode());
      }

      JsonObject o = JsonParser.parseString(resp.body()).isJsonObject()
         ? JsonParser.parseString(resp.body()).getAsJsonObject() : null;
      return o != null && o.has("data") && o.get("data").isJsonObject() ? o.getAsJsonObject("data") : null;
   }

   /** CF CDN 直链兜底：downloadUrl 缺失时按 fileId 推导（PCL 同款）。 */
   public static String cdnUrl(long fileId, String fileName) {
      return "https://edge.forgecdn.net/files/" + (fileId / 1000L) + "/" + (fileId % 1000L) + "/" + fileName;
   }

   /** 项目 latestFilesIndexes 中挑本端 版本+加载器 的文件（优先正式版）。 */
   public static JsonObject pickFileIndex(JsonObject mod, String gameVersion, String loader) {
      if (mod == null || !mod.has("latestFilesIndexes") || !mod.get("latestFilesIndexes").isJsonArray()) {
         return null;
      }

      int lt = loaderType(loader);
      JsonObject best = null;
      int bestRank = 99;
      for (JsonElement el : mod.getAsJsonArray("latestFilesIndexes")) {
         if (!el.isJsonObject()) {
            continue;
         }

         JsonObject idx = el.getAsJsonObject();
         if (!gameVersion.equals(str(idx, "gameVersion"))) {
            continue;
         }

         if (lt > 0 && str(idx, "modLoader").matches("[0-9]+") && Integer.parseInt(str(idx, "modLoader")) != lt) {
            continue;
         }

         int release = 3;
         try {
            release = Integer.parseInt(str(idx, "releaseType"));
         } catch (NumberFormatException ignored) {
         }

         if (release < bestRank) {
            bestRank = release;
            best = idx;
         }
      }

      return best;
   }

   /** CF modLoaderType：Forge=1 Fabric=4 NeoForge=6，未知=0 不过滤。 */
   public static int loaderType(String loader) {
      if (loader == null) {
         return 0;
      }

      switch (loader.toLowerCase(Locale.ROOT)) {
         case "forge":
            return 1;
         case "fabric":
            return 4;
         case "neoforge":
            return 6;
         default:
            return 0;
      }
   }

   private static String str(JsonObject o, String key) {
      return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
   }
}
