package icu.wuhui.voxlink.modsync;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import icu.wuhui.voxlink.VoxLinkMod;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
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
import java.util.Optional;
import java.util.Set;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

public final class ModrinthClient {
   private static final String API = "https://api.modrinth.com/v2";
   private static final String USER_AGENT = "VoxLink/" + voxlinkVersion() + " (modsync)";
   private static final int CHUNK = 64;
   private static final Duration TIMEOUT = Duration.ofSeconds(20L);
   private static final long HEADER_TIMEOUT_MS = 15000L;
   private static final long MAX_DOWNLOAD_BYTES = 536870912L;
   public static final int MAX_ATTEMPTS = 2;
   private static final long BACKOFF_MS_1 = 1000L;
   private static final long BACKOFF_MS_2 = 3000L;
   private static volatile HttpClient http;

   private ModrinthClient() {
   }

   private static String voxlinkVersion() {
      try {
         return VoxLinkMod.MOD_VERSION;
      } catch (Exception var1) {
         return "?";
      }
   }

   private static HttpClient client() {
      HttpClient c = http;
      if (c == null) {
         synchronized (ModrinthClient.class) {
            c = http;
            if (c == null) {
               c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10L)).followRedirects(Redirect.NORMAL).build();
               http = c;
            }
         }
      }

      return c;
   }

   public static Map<String, JsonObject> versionsFromSha1(List<String> sha1List) throws IOException, InterruptedException {
      Map<String, JsonObject> out = new HashMap<>();

      for (int from = 0; from < sha1List.size(); from += 64) {
         List<String> chunk = sha1List.subList(from, Math.min(sha1List.size(), from + 64));
         JsonObject body = new JsonObject();
         JsonArray arr = new JsonArray();
         chunk.forEach(arr::add);
         body.add("hashes", arr);
         body.addProperty("algorithm", "sha1");

         JsonObject resp;
         try {
            resp = postJson("https://api.modrinth.com/v2/version_files", body.toString());
         } catch (InterruptedException var9) {
            throw var9;
         } catch (Exception var10) {
            ModSyncLog.warn("version_files chunk failed ({} hashes): {}", chunk.size(), var10.getMessage());
            continue;
         }

         if (resp != null) {
            for (Entry<String, JsonElement> e : resp.entrySet()) {
               if (e.getValue().isJsonObject()) {
                  out.put(e.getKey(), e.getValue().getAsJsonObject());
               }
            }
         }
      }

      return out;
   }

   public static Map<String, JsonObject> projectsByIds(Set<String> ids) throws IOException, InterruptedException {
      Map<String, JsonObject> out = new HashMap<>();
      List<String> list = new ArrayList<>(ids);

      for (int from = 0; from < list.size(); from += 64) {
         List<String> chunk = list.subList(from, Math.min(list.size(), from + 64));
         StringBuilder sb = new StringBuilder("[");

         for (int i = 0; i < chunk.size(); i++) {
            if (i > 0) {
               sb.append(',');
            }

            sb.append('"').append(chunk.get(i)).append('"');
         }

         sb.append(']');
         String url = "https://api.modrinth.com/v2/projects?ids=" + URLEncoder.encode(sb.toString(), StandardCharsets.UTF_8);

         JsonArray arr;
         try {
            arr = getJsonArray(url);
         } catch (InterruptedException var10) {
            throw var10;
         } catch (Exception var11) {
            ModSyncLog.warn("projects chunk failed ({} ids): {}", chunk.size(), var11.getMessage());
            continue;
         }

         if (arr != null) {
            for (JsonElement el : arr) {
               if (el.isJsonObject() && el.getAsJsonObject().has("id")) {
                  out.put(el.getAsJsonObject().get("id").getAsString(), el.getAsJsonObject());
               }
            }
         }
      }

      return out;
   }

   public static JsonObject latestVersionFor(String projectId, String gameVersion, String loader) throws IOException, InterruptedException {
      String gv = URLEncoder.encode("[\"" + escape(gameVersion) + "\"]", StandardCharsets.UTF_8);
      String ld = URLEncoder.encode("[\"" + escape(loader) + "\"]", StandardCharsets.UTF_8);
      JsonArray arr = getJsonArray("https://api.modrinth.com/v2/project/" + escape(projectId) + "/version?game_versions=" + gv + "&loaders=" + ld);
      return arr != null && arr.size() > 0 && arr.get(0).isJsonObject() ? arr.get(0).getAsJsonObject() : null;
   }

   public static long downloadVerified(String url, String expectedSha512, String expectedSha1, String fileName, Path modsDir, BooleanSupplier shouldAbort) throws IOException, InterruptedException {
      ensureSafeFileName(fileName);
      StringBuilder errors = new StringBuilder();
      HttpClient cli = client();

      for (int i = 0; i < 2; i++) {
         if (shouldAbort.getAsBoolean()) {
            throw new ModrinthClient.AbortedException(fileName);
         }

         try {
            return doDownload(url, cli, expectedSha512, expectedSha1, fileName, modsDir, shouldAbort);
         } catch (ModrinthClient.AbortedException var13) {
            throw var13;
         } catch (ModrinthClient.PermanentHttpException var14) {
            throw var14;
         } catch (IOException var15) {
            if (shouldAbort.getAsBoolean()) {
               throw new ModrinthClient.AbortedException(fileName);
            }

            if (errors.length() > 0) {
               errors.append(" | ");
            }

            errors.append(var15.getMessage());
            if (i < 1) {
               long backoff = i == 0 ? 1000L : 3000L;

               try {
                  Thread.sleep(backoff);
               } catch (InterruptedException var12) {
                  Thread.currentThread().interrupt();
                  if (shouldAbort.getAsBoolean()) {
                     throw new ModrinthClient.AbortedException(fileName);
                  }
               }
            }
         }
      }

      String msg = errors.length() > 0 ? errors.toString() : "all attempts failed";
      throw new IOException("MODRINTH: " + msg);
   }

   public static long downloadVerified(String url, String expectedSha512, String fileName, Path modsDir, BooleanSupplier shouldAbort) throws IOException, InterruptedException {
      return downloadVerified(url, expectedSha512, null, fileName, modsDir, shouldAbort);
   }

   private static long doDownload(
      String url, HttpClient cli, String expectedSha512, String expectedSha1, String fileName, Path modsDir, BooleanSupplier shouldAbort
   ) throws IOException, InterruptedException {
      ensureSafeFileName(fileName);
      HttpRequest request = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", USER_AGENT).timeout(TIMEOUT).GET().build();
      CompletableFuture<HttpResponse<InputStream>> future = cli.sendAsync(request, BodyHandlers.ofInputStream());
      long headerDeadline = System.currentTimeMillis() + 15000L;

      while (!shouldAbort.getAsBoolean()) {
         HttpResponse<InputStream> resp;
         try {
            resp = future.get(200L, TimeUnit.MILLISECONDS);
         } catch (TimeoutException var50) {
            if (System.currentTimeMillis() <= headerDeadline) {
               continue;
            }

            future.cancel(true);
            throw new IOException("HTTP headers timeout for " + fileName);
         } catch (ExecutionException var51) {
            throw asIo(var51, fileName);
         }

         if (resp.statusCode() == 429) {
            throw new IOException("HTTP 429 for " + fileName);
         }

         if (resp.statusCode() / 100 != 4 && resp.body() != null) {
            if (resp.statusCode() / 100 != 2) {
               throw new IOException("HTTP " + resp.statusCode() + " for " + fileName);
            }

            Optional<String> clen = resp.headers().firstValue("Content-Length");
            if (clen.isPresent()) {
               try {
                  long declared = Long.parseLong(clen.get().trim());
                  if (declared > 536870912L) {
                     throw new IOException("declared Content-Length " + declared + " exceeds limit for " + fileName);
                  }
               } catch (NumberFormatException var45) {
               }
            }

            Path target = modsDir.resolve(fileName);
            Path part = modsDir.resolve(fileName + ".part");

            MessageDigest sha512;
            MessageDigest sha1;
            try {
               sha512 = MessageDigest.getInstance("SHA-512");
               sha1 = MessageDigest.getInstance("SHA-1");
            } catch (Exception var44) {
               throw new IOException("hash algorithm unavailable", var44);
            }

            long total = 0L;
            AtomicBoolean done = new AtomicBoolean(false);
            Thread cancelWatch = new Thread(() -> {
               while (!done.get() && !future.isDone()) {
                  if (shouldAbort.getAsBoolean()) {
                     future.cancel(true);
                     return;
                  }

                  try {
                     Thread.sleep(150L);
                  } catch (InterruptedException var4x) {
                     return;
                  }
               }
            }, "VoxLink-DL-CancelWatch");
            cancelWatch.setDaemon(true);
            cancelWatch.start();
            boolean abortedFlag = false;
            IOException ioFailure = null;

            try (
               InputStream in = resp.body();
               OutputStream out = Files.newOutputStream(part);
            ) {
               byte[] buf = new byte[65536];

               int n;
               while ((n = in.read(buf)) > 0) {
                  total += (long)n;
                  if (total > 536870912L) {
                     future.cancel(true);
                     throw new IOException("file too large: " + fileName);
                  }

                  sha512.update(buf, 0, n);
                  sha1.update(buf, 0, n);
                  out.write(buf, 0, n);
               }
            } catch (IOException var48) {
               if (shouldAbort.getAsBoolean()) {
                  abortedFlag = true;
               } else {
                  ioFailure = var48;
               }
            } finally {
               done.set(true);
               future.cancel(true);
            }

            if (!abortedFlag && !shouldAbort.getAsBoolean()) {
               if (ioFailure != null) {
                  quietDelete(part);
                  throw ioFailure;
               }

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

               try {
                  try {
                     Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                  } catch (AtomicMoveNotSupportedException var40) {
                     Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
                  }

                  return total;
               } catch (IOException var41) {
                  quietDelete(part);
                  throw var41;
               }
            }

            quietDelete(part);
            throw new ModrinthClient.AbortedException(fileName);
         }

         throw new ModrinthClient.PermanentHttpException("HTTP " + resp.statusCode() + " for " + fileName);
      }

      future.cancel(true);
      throw new ModrinthClient.AbortedException(fileName);
   }

   private static IOException asIo(ExecutionException ee, String fileName) {
      Throwable c = (Throwable)(ee.getCause() != null ? ee.getCause() : ee);
      return c instanceof IOException ioe ? ioe : new IOException(c.toString() + " for " + fileName);
   }

   public static boolean isServerOnly(JsonObject project) {
      if (project == null) {
         return false;
      } else if (project.has("client_side") && !project.get("client_side").isJsonNull()) {
         return "unsupported".equalsIgnoreCase(project.get("client_side").getAsString());
      } else {
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
   }

   private static JsonObject postJson(String url, String json) throws IOException, InterruptedException {
      HttpRequest request = HttpRequest.newBuilder(URI.create(url))
         .header("Content-Type", "application/json")
         .header("User-Agent", USER_AGENT)
         .timeout(TIMEOUT)
         .POST(BodyPublishers.ofString(json, StandardCharsets.UTF_8))
         .build();
      HttpResponse<String> resp = sendWithRetry(request, "Modrinth POST");
      if (resp.statusCode() / 100 != 2) {
         throw new IOException("Modrinth POST failed: HTTP " + resp.statusCode());
      } else {
         JsonElement el = JsonParser.parseString(resp.body());
         return el.isJsonObject() ? el.getAsJsonObject() : null;
      }
   }

   private static JsonArray getJsonArray(String url) throws IOException, InterruptedException {
      HttpRequest request = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", USER_AGENT).timeout(TIMEOUT).GET().build();
      HttpResponse<String> resp = sendWithRetry(request, "Modrinth GET");
      if (resp.statusCode() / 100 != 2) {
         throw new IOException("Modrinth GET failed: HTTP " + resp.statusCode());
      } else {
         JsonElement el = JsonParser.parseString(resp.body());
         return el.isJsonArray() ? el.getAsJsonArray() : null;
      }
   }

   private static HttpResponse<String> sendWithRetry(HttpRequest request, String label) throws IOException, InterruptedException {
      int maxTries = 3;
      StringBuilder errors = new StringBuilder();

      for (int i = 0; i < maxTries; i++) {
         try {
            HttpResponse<String> resp = client().send(request, BodyHandlers.ofString());
            int code = resp.statusCode();
            if (code == 429) {
               long waitSec = parseRetryAfter(resp);
               if (waitSec <= 0L) {
                  waitSec = 3L;
               }

               if (i >= maxTries - 1) {
                  throw new IOException(label + " HTTP 429 (exhausted)");
               }

               try {
                  Thread.sleep(waitSec * 1000L);
               } catch (InterruptedException var10) {
                  Thread.currentThread().interrupt();
                  throw var10;
               }
            } else {
               if (code / 100 == 4) {
                  throw new ModrinthClient.PermanentHttpException(label + " HTTP " + code);
               }

               if (code / 100 != 5) {
                  return resp;
               }

               appendError(errors, label + " HTTP " + code);
               if (i >= maxTries - 1) {
                  throw new IOException(label + " HTTP " + code + " (exhausted)");
               }

               backoff(i);
            }
         } catch (ModrinthClient.PermanentHttpException var11) {
            throw var11;
         } catch (IOException var12) {
            appendError(errors, label + " " + var12.getMessage());
            if (i >= maxTries - 1) {
               throw new IOException(label + ": " + (errors.length() > 0 ? errors.toString() : var12.getMessage()), var12);
            }

            backoff(i);
         }
      }

      throw new IOException(label + ": " + (errors.length() > 0 ? errors.toString() : "all attempts failed"));
   }

   private static long parseRetryAfter(HttpResponse<?> resp) {
      return resp.headers().firstValue("Retry-After").map(String::trim).map(s -> {
         try {
            return Long.parseLong(s);
         } catch (NumberFormatException var2) {
            return 3L;
         }
      }).orElse(3L);
   }

   private static void backoff(int i) throws InterruptedException {
      long backoff = i == 0 ? 1000L : 3000L;
      Thread.sleep(backoff);
   }

   private static void appendError(StringBuilder errors, String msg) {
      if (errors.length() > 0) {
         errors.append(" | ");
      }

      errors.append(msg);
   }

   public static JsonObject primaryFile(JsonObject version) {
      if (version != null && version.has("files") && version.get("files").isJsonArray()) {
         JsonArray files = version.getAsJsonArray("files");
         JsonObject first = null;

         for (JsonElement el : files) {
            if (el.isJsonObject()) {
               JsonObject f = el.getAsJsonObject();
               if (f.has("primary") && f.get("primary").isJsonPrimitive() && f.get("primary").getAsBoolean()) {
                  return f;
               }

               if (first == null) {
                  first = f;
               }
            }
         }

         return first;
      } else {
         return null;
      }
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
      } catch (IOException var2) {
      }
   }

   private static void ensureSafeFileName(String fileName) throws IOException {
      if (fileName == null || fileName.isBlank()) {
         throw new IOException("unsafe fileName: empty");
      } else if (!fileName.contains("..") && !fileName.contains("/") && !fileName.contains("\\") && !fileName.contains(":")) {
         for (int i = 0; i < fileName.length(); i++) {
            char c = fileName.charAt(i);
            if (c < ' ' || c == 127) {
               throw new IOException("unsafe fileName (control char): " + fileName);
            }
         }
      } else {
         throw new IOException("unsafe fileName: " + fileName);
      }
   }

   public static final class AbortedException extends IOException {
      public AbortedException(String name) {
         super("aborted: " + name);
      }
   }

   private static final class PermanentHttpException extends IOException {
      PermanentHttpException(String msg) {
         super(msg);
      }
   }
}
