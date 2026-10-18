package icu.wuhui.voxlink.network;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FeedbackUploader {
   public static final long MAX_TOTAL_BYTES = 524288000L;
   private static final Logger LOGGER = LoggerFactory.getLogger("voxlink-feedback");

   private FeedbackUploader() {
   }

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
      Iterator var7 = seen.iterator();

      while (true) {
         Path p;
         while (true) {
            while (true) {
               if (!var7.hasNext()) {
                  sizeOut[0] = total;
                  return out;
               }

               p = (Path)var7.next();
               if (Files.isRegularFile(p)) {
                  try {
                     if (Files.size(p) <= 0L) {
                        continue;
                     }
                     break;
                  } catch (Exception var11) {
                  }
               }
            }

            try {
               SeekableByteChannel ch = Files.newByteChannel(p);
               if (ch != null) {
                  ch.close();
               }
               break;
            } catch (Throwable var12) {
               LOGGER.warn("[Feedback] skip unreadable file: {} ({})", p, var12.toString());
            }
         }

         try {
            total += Files.size(p);
            out.add(p);
         } catch (Exception var10) {
         }
      }
   }

   public static void submit(
      String serverUrl, String description, String clientInfo, List<Path> attachments, List<Path> logFiles, Consumer<FeedbackUploader.Result> callback
   ) {
      try {
         String url = normalizeUrl(serverUrl);
         if (url == null) {
            LOGGER.warn("[Feedback] submit aborted: bad server url: {}", serverUrl);
            callback.accept(new FeedbackUploader.Result(false, "BAD_SERVER_URL", 0));
            return;
         }

         LOGGER.info(
            "[Feedback] submit: {} (desc={} chars, files={}, logs={})",
            new Object[]{
               url, description == null ? 0 : description.length(), attachments == null ? 0 : attachments.size(), logFiles == null ? 0 : logFiles.size()
            }
         );
         String boundary = "----VoxLinkFB" + UUID.randomUUID().toString().replace("-", "");
         List<BodyPublisher> parts = new ArrayList<>();
         parts.add(textPart(boundary, "description", description == null ? "" : description));
         parts.add(textPart(boundary, "client", "mod"));
         parts.add(textPart(boundary, "clientInfo", clientInfo == null ? "" : clientInfo));
         long[] total = new long[]{0L};
         List<Path> files = mergeFiles(attachments, logFiles, total);
         if (total[0] > 524288000L) {
            callback.accept(new FeedbackUploader.Result(false, "FEEDBACK_TOO_LARGE", 0));
            return;
         }

         for (Path p : files) {
            parts.add(filePart(boundary, p));
         }

         parts.add(BodyPublishers.ofByteArray(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8)));
         HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMinutes(30L))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(BodyPublishers.concat(parts.toArray(new BodyPublisher[0])))
            .build();
         HttpClient.newHttpClient()
            .sendAsync(request, BodyHandlers.ofString())
            .thenAccept(resp -> callback.accept(parseResponse((HttpResponse<String>)resp)))
            .exceptionally(exx -> {
               Throwable cause = exx.getCause() != null ? exx.getCause() : exx;
               LOGGER.warn("[Feedback] submit network error: {}", cause.toString());
               callback.accept(new FeedbackUploader.Result(false, "NETWORK_ERROR", 0));
               return null;
            });
      } catch (Exception var13) {
         LOGGER.warn("[Feedback] submit setup error: {}", var13.toString());
         callback.accept(new FeedbackUploader.Result(false, "NETWORK_ERROR", 0));
      }
   }

   private static FeedbackUploader.Result parseResponse(HttpResponse<String> resp) {
      String body = resp.body() == null ? "" : resp.body();
      LOGGER.info("[Feedback] response: HTTP {} body={}", resp.statusCode(), body.length() > 300 ? body.substring(0, 300) + "…" : body);

      try {
         JsonElement root = JsonParser.parseString(body);
         if (!root.isJsonObject()) {
            return new FeedbackUploader.Result(false, "BAD_RESPONSE", 0);
         } else {
            JsonObject obj = root.getAsJsonObject();
            if (obj.has("success") && obj.get("success").isJsonPrimitive() && obj.get("success").getAsBoolean()) {
               return FeedbackUploader.Result.ok();
            } else {
               String code = obj.has("error") && obj.get("error").isJsonPrimitive() ? obj.get("error").getAsString() : "BAD_RESPONSE";
               int retry = 0;
               if (obj.has("details") && obj.get("details").isJsonObject()) {
                  JsonObject details = obj.getAsJsonObject("details");
                  if (details.has("retryAfter") && details.get("retryAfter").isJsonPrimitive()) {
                     retry = details.get("retryAfter").getAsInt();
                  }
               }

               return new FeedbackUploader.Result(false, code, retry);
            }
         }
      } catch (Exception var7) {
         return new FeedbackUploader.Result(false, "BAD_RESPONSE", 0);
      }
   }

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
      } catch (Exception var3) {
         return null;
      }
   }

   private static BodyPublisher textPart(String boundary, String name, String value) {
      String head = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\nContent-Type: text/plain; charset=utf-8\r\n\r\n";
      return BodyPublishers.concat(
         BodyPublishers.ofString(head, StandardCharsets.UTF_8),
         BodyPublishers.ofString(value == null ? "" : value, StandardCharsets.UTF_8),
         BodyPublishers.ofByteArray("\r\n".getBytes(StandardCharsets.UTF_8))
      );
   }

   private static BodyPublisher filePart(String boundary, Path file) {
      String fileName = file.getFileName().toString().replace("\"", "'");
      String head = "--"
         + boundary
         + "\r\nContent-Disposition: form-data; name=\"attachments\"; filename=\""
         + fileName
         + "\"\r\nContent-Type: application/octet-stream\r\n\r\n";

      BodyPublisher fileBody;
      try {
         fileBody = BodyPublishers.ofFile(file);
      } catch (Exception var6) {
         fileBody = BodyPublishers.ofByteArray(new byte[0]);
      }

      return BodyPublishers.concat(
         BodyPublishers.ofString(head, StandardCharsets.UTF_8), fileBody, BodyPublishers.ofByteArray("\r\n".getBytes(StandardCharsets.UTF_8))
      );
   }

   public static class Result {
      public final boolean success;
      public final String errorCode;
      public final int retryAfterSec;

      Result(boolean success, String errorCode, int retryAfterSec) {
         this.success = success;
         this.errorCode = errorCode;
         this.retryAfterSec = retryAfterSec;
      }

      static FeedbackUploader.Result ok() {
         return new FeedbackUploader.Result(true, null, 0);
      }
   }
}
