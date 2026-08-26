package icu.wuhui.voxlink.modsync;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
   private static final String USER_AGENT = "VoxLink/" + voxlinkVersion() + " (modsync)";
   private static final int CHUNK = 64;
   private static final Duration TIMEOUT = Duration.ofSeconds(20);
   private static final long MAX_DOWNLOAD_BYTES = 512L * 1024 * 1024;
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
               c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
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
         JsonObject resp = postJson(API + "/version_files", body.toString());
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
         String url = API + "/projects?ids=" + URLEncoder.encode(sb.toString(), StandardCharsets.UTF_8);
         JsonArray arr = getJsonArray(url);
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
      JsonArray arr = getJsonArray(API + "/project/" + escape(projectId) + "/version?game_versions=" + gv + "&loaders=" + ld);
      return arr != null && arr.size() > 0 && arr.get(0).isJsonObject() ? arr.get(0).getAsJsonObject() : null;
   }

   /** 下载到 mods 目录：先写 .part，校验 sha512 后原子改名。 */
   public static void downloadVerified(String url, String expectedSha512, String fileName, Path modsDir) throws IOException, InterruptedException {
      HttpRequest request = HttpRequest.newBuilder(URI.create(url))
         .header("User-Agent", USER_AGENT)
         .timeout(TIMEOUT)
         .GET()
         .build();
      HttpResponse<InputStream> resp = client()
         .send(request, HttpResponse.BodyHandlers.ofInputStream());
      if (resp.statusCode() / 100 != 2 || resp.body() == null) {
         throw new IOException("HTTP " + resp.statusCode() + " for " + fileName);
      }

      Path target = modsDir.resolve(fileName);
      Path part = modsDir.resolve(fileName + ".part");
      MessageDigest digest;
      try {
         digest = MessageDigest.getInstance("SHA-512");
      } catch (Exception e) {
         throw new IOException("sha512 unavailable", e);
      }

      long total = 0L;
      try (InputStream in = resp.body()) {
         try (var out = Files.newOutputStream(part)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
               total += n;
               if (total > MAX_DOWNLOAD_BYTES) {
                  throw new IOException("file too large: " + fileName);
               }

               digest.update(buf, 0, n);
               out.write(buf, 0, n);
            }
         }
      } catch (IOException e) {
         quietDelete(part);
         throw e;
      }

      String actual = hex(digest);
      if (expectedSha512 != null && !expectedSha512.isBlank() && !actual.equalsIgnoreCase(expectedSha512)) {
         quietDelete(part);
         throw new IOException("sha512 mismatch: " + fileName);
      }

      Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
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

   private static JsonObject postJson(String url, String json) throws IOException, InterruptedException {
      HttpRequest request = HttpRequest.newBuilder(URI.create(url))
         .header("Content-Type", "application/json")
         .header("User-Agent", USER_AGENT)
         .timeout(TIMEOUT)
         .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
         .build();
      HttpResponse<String> resp = client().send(request, HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() / 100 != 2) {
         throw new IOException("Modrinth POST failed: HTTP " + resp.statusCode());
      }

      JsonElement el = JsonParser.parseString(resp.body());
      return el.isJsonObject() ? el.getAsJsonObject() : null;
   }

   private static JsonArray getJsonArray(String url) throws IOException, InterruptedException {
      HttpRequest request = HttpRequest.newBuilder(URI.create(url))
         .header("User-Agent", USER_AGENT)
         .timeout(TIMEOUT)
         .GET()
         .build();
      HttpResponse<String> resp = client().send(request, HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() / 100 != 2) {
         throw new IOException("Modrinth GET failed: HTTP " + resp.statusCode());
      }

      JsonElement el = JsonParser.parseString(resp.body());
      return el.isJsonArray() ? el.getAsJsonArray() : null;
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
}
