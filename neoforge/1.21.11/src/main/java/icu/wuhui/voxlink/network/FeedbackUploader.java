package icu.wuhui.voxlink.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 站内反馈上传器：把描述 + 任意附件（含可选的游戏日志）以 multipart/form-data
 * 提交到信令服务器的 /feedback/submit。附件走 BodyPublishers.ofFile 流式上传，
 * 不整块读进内存（500MB 上限由界面预检 + 服务端硬限共同把关）。
 * 服务端会对附件做压缩存储，并按 IP 限频（10 分钟 1 次）。
 */
public final class FeedbackUploader {
   public static final long MAX_TOTAL_BYTES = 500L * 1024L * 1024L;
   private static final Logger LOGGER = LoggerFactory.getLogger("voxlink-feedback");

   private FeedbackUploader() {
   }

   /** 提交结果（异步回调，HttpClient 线程触发；UI 侧自行切回主线程）。 */
   public static class Result {
      public final boolean success;
      /** 失败错误码：RATE_LIMITED / FEEDBACK_TOO_LARGE / FEEDBACK_EMPTY / NETWORK_ERROR / 其他服务端错误码 */
      public final String errorCode;
      /** RATE_LIMITED 时的等待秒数（服务端 Retry-After），非限频为 0 */
      public final int retryAfterSec;

      Result(boolean success, String errorCode, int retryAfterSec) {
         this.success = success;
         this.errorCode = errorCode;
         this.retryAfterSec = retryAfterSec;
      }

      static Result ok() {
         return new Result(true, null, 0);
      }
   }

   /** 收集游戏目录 logs/ 下的日志文件（latest.log / debug.log），存在才收录。 */
   public static List<Path> defaultLogFiles(Path gameDir) {
      List<Path> out = new ArrayList<>();
      Path logs = gameDir.resolve("logs");
      for (String name : new String[]{"latest.log", "debug.log"}) {
         Path p = logs.resolve(name);
         if (Files.isRegularFile(p)) {
            out.add(p);
         }
      }

      return out;
   }

   /** 合并附件与日志（按路径去重、保持顺序），并返回总字节数。sizeOut[0] 接收总大小。 */
   public static List<Path> mergeFiles(List<Path> attachments, List<Path> logFiles, long[] sizeOut) {
      Set<Path> seen = new LinkedHashSet<>();
      if (attachments != null) {
         seen.addAll(attachments);
      }

      if (logFiles != null) {
         seen.addAll(logFiles);
      }

      long total = 0L;
      List<Path> out = new ArrayList<>();
      for (Path p : seen) {
         if (!Files.isRegularFile(p)) {
            continue;
         }

         try {
            if (Files.size(p) <= 0L) {
               continue;
            }
         } catch (Exception ignored) {
            continue;
         }

         // 真实可读性探测：必须能打开读通道才算数。系统锁定文件（如 C:\DumpStack.log）、
         // 权限不足的文件在传输阶段才会失败（ofFile 懒打开），会把整次提交炸成 NETWORK_ERROR。
         try (var ch = Files.newByteChannel(p)) {
            // 只做打开验证，立即关闭
         } catch (Throwable e) {
            LOGGER.warn("[Feedback] skip unreadable file: {} ({})", p, e.toString());
            continue;
         }

         try {
            total += Files.size(p);
            out.add(p);
         } catch (Exception ignored) {
         }
      }

      sizeOut[0] = total;
      return out;
   }

   /**
    * 提交反馈。所有文件均流式上传；结果通过 callback 返回（非主线程）。
    *
    * @param serverUrl  信令服务器地址（如 https://p2p.wuhui.icu）
    * @param clientInfo JSON 字符串（游戏版本/加载器/mod版本/玩家名等），可为空串
    */
   public static void submit(String serverUrl, String description, String clientInfo,
                             List<Path> attachments, List<Path> logFiles, Consumer<Result> callback) {
      try {
         String url = normalizeUrl(serverUrl);
         if (url == null) {
            LOGGER.warn("[Feedback] submit aborted: bad server url: {}", serverUrl);
            callback.accept(new Result(false, "BAD_SERVER_URL", 0));
            return;
         }

         LOGGER.info("[Feedback] submit: {} (desc={} chars, files={}, logs={})", url,
            description == null ? 0 : description.length(), attachments == null ? 0 : attachments.size(), logFiles == null ? 0 : logFiles.size());

         String boundary = "----VoxLinkFB" + UUID.randomUUID().toString().replace("-", "");
         List<HttpRequest.BodyPublisher> parts = new ArrayList<>();
         parts.add(textPart(boundary, "description", description == null ? "" : description));
         parts.add(textPart(boundary, "client", "mod"));
         parts.add(textPart(boundary, "clientInfo", clientInfo == null ? "" : clientInfo));

         long[] total = new long[]{0L};
         List<Path> files = mergeFiles(attachments, logFiles, total);
         if (total[0] > MAX_TOTAL_BYTES) {
            callback.accept(new Result(false, "FEEDBACK_TOO_LARGE", 0));
            return;
         }

         for (Path p : files) {
            parts.add(filePart(boundary, p));
         }

         parts.add(HttpRequest.BodyPublishers.ofByteArray(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8)));

         HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMinutes(30L))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.concat(parts.toArray(new HttpRequest.BodyPublisher[0])))
            .build();

         HttpClient.newHttpClient()
            .sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenAccept(resp -> callback.accept(parseResponse(resp)))
            .exceptionally(ex -> {
               Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
               LOGGER.warn("[Feedback] submit network error: {}", cause.toString());
               callback.accept(new Result(false, "NETWORK_ERROR", 0));
               return null;
            });
      } catch (Exception ex) {
         LOGGER.warn("[Feedback] submit setup error: {}", ex.toString());
         callback.accept(new Result(false, "NETWORK_ERROR", 0));
      }
   }

   private static Result parseResponse(HttpResponse<String> resp) {
      String body = resp.body() == null ? "" : resp.body();
      LOGGER.info("[Feedback] response: HTTP {} body={}", resp.statusCode(),
         body.length() > 300 ? body.substring(0, 300) + "…" : body);
      try {
         var root = JsonParser.parseString(body);
         if (!root.isJsonObject()) {
            return new Result(false, "BAD_RESPONSE", 0);
         }

         var obj = root.getAsJsonObject();
         if (obj.has("success") && obj.get("success").isJsonPrimitive() && obj.get("success").getAsBoolean()) {
            return Result.ok();
         }

         String code = obj.has("error") && obj.get("error").isJsonPrimitive() ? obj.get("error").getAsString() : "BAD_RESPONSE";
         int retry = 0;
         if (obj.has("details") && obj.get("details").isJsonObject()) {
            var details = obj.getAsJsonObject("details");
            if (details.has("retryAfter") && details.get("retryAfter").isJsonPrimitive()) {
               retry = details.get("retryAfter").getAsInt();
            }
         }

         return new Result(false, code, retry);
      } catch (Exception ex) {
         return new Result(false, "BAD_RESPONSE", 0);
      }
   }

   /** 归一化服务器地址并拼出提交端点 URL；无效返回 null。 */
   static String normalizeUrl(String serverUrl) {
      String url = serverUrl == null ? "" : serverUrl.trim();
      if (url.isEmpty()) {
         url = "https://p2p.wuhui.icu";
      }

      if (!url.startsWith("http://") && !url.startsWith("https://")) {
         url = "https://" + url;
      }

      while (url.endsWith("/")) {
         url = url.substring(0, url.length() - 1);
      }

      try {
         URI uri = URI.create(url + "/?route=/feedback/submit");
         return uri.toString();
      } catch (Exception ex) {
         return null;
      }
   }

   private static HttpRequest.BodyPublisher textPart(String boundary, String name, String value) {
      String head = "--" + boundary + "\r\n"
         + "Content-Disposition: form-data; name=\"" + name + "\"\r\n"
         + "Content-Type: text/plain; charset=utf-8\r\n\r\n";
      return HttpRequest.BodyPublishers.concat(
         HttpRequest.BodyPublishers.ofString(head, StandardCharsets.UTF_8),
         HttpRequest.BodyPublishers.ofString(value == null ? "" : value, StandardCharsets.UTF_8),
         HttpRequest.BodyPublishers.ofByteArray("\r\n".getBytes(StandardCharsets.UTF_8))
      );
   }

   private static HttpRequest.BodyPublisher filePart(String boundary, Path file) {
      String fileName = file.getFileName().toString().replace("\"", "'");
      String head = "--" + boundary + "\r\n"
         + "Content-Disposition: form-data; name=\"attachments\"; filename=\"" + fileName + "\"\r\n"
         + "Content-Type: application/octet-stream\r\n\r\n";
      HttpRequest.BodyPublisher fileBody;
      try {
         fileBody = HttpRequest.BodyPublishers.ofFile(file);
      } catch (Exception ex) {
         fileBody = HttpRequest.BodyPublishers.ofByteArray(new byte[0]);
      }

      return HttpRequest.BodyPublishers.concat(
         HttpRequest.BodyPublishers.ofString(head, StandardCharsets.UTF_8),
         fileBody,
         HttpRequest.BodyPublishers.ofByteArray("\r\n".getBytes(StandardCharsets.UTF_8))
      );
   }
}
