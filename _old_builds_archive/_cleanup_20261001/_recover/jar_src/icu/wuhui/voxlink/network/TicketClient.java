package icu.wuhui.voxlink.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import icu.wuhui.voxlink.VoxLinkMod;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TicketClient {
   private static final Logger LOGGER = LoggerFactory.getLogger("voxlink-ticket");
   private static final List<TicketClient.LocalTicket> LOCAL = new ArrayList<>();
   private static final AtomicBoolean LOADED = new AtomicBoolean(false);
   private static final AtomicBoolean POLLED = new AtomicBoolean(false);

   private TicketClient() {
   }

   private static Path storePath() {
      return Minecraft.getInstance().gameDirectory.toPath().resolve("voxlink_tickets.json");
   }

   private static synchronized void load() {
      if (!LOADED.get()) {
         LOADED.set(true);

         try {
            Path p = storePath();
            if (Files.isRegularFile(p)) {
               JsonElement root = JsonParser.parseString(Files.readString(p));
               if (root.isJsonArray()) {
                  for (JsonElement e : root.getAsJsonArray()) {
                     if (e.isJsonObject()) {
                        JsonObject o = e.getAsJsonObject();
                        TicketClient.LocalTicket t = new TicketClient.LocalTicket();
                        t.id = str(o, "id");
                        t.timeMs = o.has("timeMs") ? o.get("timeMs").getAsLong() : 0L;
                        t.deleted = o.has("deleted") && o.get("deleted").getAsBoolean();
                        t.hasUnread = o.has("hasUnread") && o.get("hasUnread").getAsBoolean();
                        t.replyCount = o.has("replyCount") ? o.get("replyCount").getAsInt() : 0;
                        t.lastTimeMs = o.has("lastTimeMs") ? o.get("lastTimeMs").getAsLong() : t.timeMs;
                        if (!t.id.isEmpty()) {
                           LOCAL.add(t);
                        }
                     }
                  }
               }
            }
         } catch (Exception var6) {
            LOGGER.debug("[Ticket] load store failed: {}", var6.toString());
         }
      }
   }

   private static void save() {
      try {
         Path p = storePath();
         JsonArray arr = new JsonArray();
         synchronized (LOCAL) {
            for (TicketClient.LocalTicket t : LOCAL) {
               JsonObject o = new JsonObject();
               o.addProperty("id", t.id);
               o.addProperty("timeMs", t.timeMs);
               o.addProperty("deleted", t.deleted);
               o.addProperty("hasUnread", t.hasUnread);
               o.addProperty("replyCount", t.replyCount);
               o.addProperty("lastTimeMs", t.lastTimeMs);
               arr.add(o);
            }
         }

         Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
         Files.writeString(tmp, arr.toString(), StandardCharsets.UTF_8);
         Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
      } catch (Exception var8) {
         LOGGER.warn("[Ticket] save store failed: {}", var8.toString());
      }
   }

   public static List<TicketClient.LocalTicket> snapshot() {
      load();
      synchronized (LOCAL) {
         List<TicketClient.LocalTicket> out = new ArrayList<>(LOCAL);
         out.sort((a, b) -> Long.compare(b.lastTimeMs != 0L ? b.lastTimeMs : b.timeMs, a.lastTimeMs != 0L ? a.lastTimeMs : a.timeMs));
         return out;
      }
   }

   public static boolean hasUnread() {
      load();
      synchronized (LOCAL) {
         for (TicketClient.LocalTicket t : LOCAL) {
            if (t.hasUnread && !t.deleted) {
               return true;
            }
         }

         return false;
      }
   }

   private static void upsertLocal(String id, long timeMs) {
      load();
      synchronized (LOCAL) {
         for (TicketClient.LocalTicket t : LOCAL) {
            if (t.id.equals(id)) {
               return;
            }
         }

         TicketClient.LocalTicket tx = new TicketClient.LocalTicket();
         tx.id = id;
         tx.timeMs = timeMs;
         tx.lastTimeMs = timeMs;
         LOCAL.add(tx);
      }

      save();
   }

   private static String str(JsonObject o, String k) {
      return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
   }

   private static int intval(JsonObject o, String k) {
      return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsInt() : 0;
   }

   private static long longval(JsonObject o, String k) {
      return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsLong() : 0L;
   }

   private static boolean boolval(JsonObject o, String k) {
      return o != null && o.has(k) && o.get(k).isJsonPrimitive() && o.get(k).getAsBoolean();
   }

   public static void pollOnceIfNeeded(Runnable onDone) {
      if (!POLLED.getAndSet(true)) {
         load();
         List<String> ids;
         synchronized (LOCAL) {
            ids = new ArrayList<>();

            for (TicketClient.LocalTicket t : LOCAL) {
               ids.add(t.id);
            }
         }

         if (ids.isEmpty()) {
            if (onDone != null) {
               onDone.run();
            }
         } else {
            String url = endpoint("/ticket/poll");
            if (url == null) {
               if (onDone != null) {
                  onDone.run();
               }
            } else {
               JsonObject body = new JsonObject();
               JsonArray arr = new JsonArray();

               for (String id : ids) {
                  arr.add(id);
               }

               body.add("ids", arr);
               postJson(url, body, resp -> {
                  if (resp != null && resp.success) {
                     JsonObject data = resp.root.getAsJsonObject("data");
                     if (data != null && data.has("tickets") && data.get("tickets").isJsonArray()) {
                        for (JsonElement e : data.getAsJsonArray("tickets")) {
                           if (e.isJsonObject()) {
                              JsonObject o = e.getAsJsonObject();
                              String idx = str(o, "id");
                              synchronized (LOCAL) {
                                 for (TicketClient.LocalTicket t : LOCAL) {
                                    if (t.id.equals(idx)) {
                                       t.hasUnread = boolval(o, "hasUnread");
                                       t.deleted = boolval(o, "deleted");
                                       t.replyCount = intval(o, "replyCount");
                                       t.lastTimeMs = longval(o, "lastTime") * 1000L;
                                       break;
                                    }
                                 }
                              }
                           }
                        }

                        save();
                     }
                  }

                  if (onDone != null) {
                     onDone.run();
                  }
               });
            }
         }
      }
   }

   public static void submit(String serverUrl, String description, String clientInfo, List<Path> attachments, Consumer<TicketClient.Result> callback) {
      String url = normalize(serverUrl, "/ticket/submit");
      if (url == null) {
         callback.accept(new TicketClient.Result(false, "BAD_SERVER_URL", 0, null));
      } else {
         String boundary = "----VoxLinkTK" + UUID.randomUUID().toString().replace("-", "");
         List<BodyPublisher> parts = new ArrayList<>();
         parts.add(textPart(boundary, "description", description));
         parts.add(textPart(boundary, "client", "mod"));
         parts.add(textPart(boundary, "clientInfo", clientInfo));
         long[] total = new long[]{0L};
         List<Path> files = FeedbackUploader.mergeFiles(attachments, null, total);
         if (total[0] > 524288000L) {
            callback.accept(new TicketClient.Result(false, "TICKET_TOO_LARGE", 0, null));
         } else {
            for (Path f : files) {
               parts.add(filePart(boundary, f));
            }

            parts.add(BodyPublishers.ofByteArray(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8)));
            send(url, boundary, parts, resp -> {
               if (resp == null) {
                  callback.accept(new TicketClient.Result(false, "NETWORK_ERROR", 0, null));
               } else {
                  if (resp.success) {
                     String id = str(resp.root.getAsJsonObject("data"), "id");
                     upsertLocal(id, System.currentTimeMillis());
                     callback.accept(TicketClient.Result.ok(id));
                  } else {
                     callback.accept(new TicketClient.Result(false, resp.error, resp.retryAfter, null));
                  }
               }
            });
         }
      }
   }

   public static void reply(String serverUrl, String ticketId, String text, List<Path> attachments, Consumer<TicketClient.Result> callback) {
      String url = normalize(serverUrl, "/ticket/reply");
      if (url == null) {
         callback.accept(new TicketClient.Result(false, "BAD_SERVER_URL", 0, null));
      } else {
         String boundary = "----VoxLinkTK" + UUID.randomUUID().toString().replace("-", "");
         List<BodyPublisher> parts = new ArrayList<>();
         parts.add(textPart(boundary, "id", ticketId));
         parts.add(textPart(boundary, "text", text));
         long[] total = new long[]{0L};
         List<Path> files = FeedbackUploader.mergeFiles(attachments, null, total);
         if (total[0] > 524288000L) {
            callback.accept(new TicketClient.Result(false, "TICKET_TOO_LARGE", 0, null));
         } else {
            for (Path f : files) {
               parts.add(filePart(boundary, f));
            }

            parts.add(BodyPublishers.ofByteArray(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8)));
            send(url, boundary, parts, resp -> {
               if (resp == null) {
                  callback.accept(new TicketClient.Result(false, "NETWORK_ERROR", 0, null));
               } else {
                  if (resp.success) {
                     callback.accept(TicketClient.Result.ok(ticketId));
                  } else {
                     callback.accept(new TicketClient.Result(false, resp.error, resp.retryAfter, null));
                  }
               }
            });
         }
      }
   }

   public static void fetchDetail(String serverUrl, String ticketId, Consumer<TicketClient.Detail> callback) {
      String url = normalize(serverUrl, "/ticket/detail?id=" + ticketId);
      if (url == null) {
         callback.accept(null);
      } else {
         HttpClient.newHttpClient()
            .sendAsync(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15L)).GET().build(), BodyHandlers.ofString())
            .thenAccept(resp -> callback.accept(parseDetail((HttpResponse<String>)resp)))
            .exceptionally(ex -> {
               LOGGER.debug("[Ticket] detail error: {}", ex.toString());
               callback.accept(null);
               return null;
            });
      }
   }

   public static void markViewed(String serverUrl, String ticketId) {
      load();
      synchronized (LOCAL) {
         for (TicketClient.LocalTicket t : LOCAL) {
            if (t.id.equals(ticketId)) {
               t.hasUnread = false;
               break;
            }
         }
      }

      save();
      String url = normalize(serverUrl, "/ticket/viewed");
      if (url != null) {
         JsonObject body = new JsonObject();
         body.addProperty("id", ticketId);
         postJson(url, body, resp -> {
         });
      }
   }

   public static void deleteLocal(String serverUrl, String ticketId) {
      load();
      synchronized (LOCAL) {
         for (TicketClient.LocalTicket t : LOCAL) {
            if (t.id.equals(ticketId)) {
               t.deleted = true;
               t.hasUnread = false;
               break;
            }
         }
      }

      save();
      String url = normalize(serverUrl, "/ticket/delete");
      if (url != null) {
         JsonObject body = new JsonObject();
         body.addProperty("id", ticketId);
         postJson(url, body, resp -> {
         });
      }
   }

   private static TicketClient.Detail parseDetail(HttpResponse<String> resp) {
      try {
         JsonElement root = JsonParser.parseString(resp.body() == null ? "" : resp.body());
         if (!root.isJsonObject()) {
            return null;
         } else {
            JsonObject obj = root.getAsJsonObject();
            if (!boolval(obj, "success")) {
               return null;
            } else {
               JsonObject data = obj.getAsJsonObject("data");
               if (data == null) {
                  return null;
               } else {
                  TicketClient.Detail d = new TicketClient.Detail();
                  d.id = str(data, "id");
                  d.timeMs = longval(data, "time") * 1000L;
                  d.deleted = boolval(data, "deleted");
                  d.description = str(data, "description");
                  if (data.has("attachments") && data.get("attachments").isJsonArray()) {
                     for (JsonElement e : data.getAsJsonArray("attachments")) {
                        if (e.isJsonObject()) {
                           d.attachments.add(parseAttach(e.getAsJsonObject()));
                        }
                     }
                  }

                  if (data.has("messages") && data.get("messages").isJsonArray()) {
                     for (JsonElement ex : data.getAsJsonArray("messages")) {
                        if (ex.isJsonObject()) {
                           JsonObject mo = ex.getAsJsonObject();
                           TicketClient.Msg m = new TicketClient.Msg();
                           m.from = str(mo, "from");
                           m.timeMs = longval(mo, "time") * 1000L;
                           m.text = str(mo, "text");
                           if (mo.has("attachments") && mo.get("attachments").isJsonArray()) {
                              for (JsonElement ae : mo.getAsJsonArray("attachments")) {
                                 if (ae.isJsonObject()) {
                                    m.attachments.add(parseAttach(ae.getAsJsonObject()));
                                 }
                              }
                           }

                           d.messages.add(m);
                        }
                     }
                  }

                  return d;
               }
            }
         }
      } catch (Exception var11) {
         LOGGER.debug("[Ticket] parse detail failed: {}", var11.toString());
         return null;
      }
   }

   private static TicketClient.Attach parseAttach(JsonObject o) {
      TicketClient.Attach a = new TicketClient.Attach();
      a.name = str(o, "name");
      a.size = longval(o, "size");
      return a;
   }

   private static String normalize(String serverUrl, String route) {
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
         return URI.create(url + "/?route=" + route).toString();
      } catch (Exception var4) {
         return null;
      }
   }

   private static String endpoint(String route) {
      try {
         String cfg = VoxLinkMod.getConfig().getServerUrl();
         return normalize(cfg, route);
      } catch (Exception var2) {
         return null;
      }
   }

   private static void postJson(String url, JsonObject body, Consumer<TicketClient.Resp> callback) {
      try {
         HttpClient.newHttpClient()
            .sendAsync(
               HttpRequest.newBuilder(URI.create(url))
                  .timeout(Duration.ofSeconds(15L))
                  .header("Content-Type", "application/json")
                  .POST(BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                  .build(),
               BodyHandlers.ofString()
            )
            .thenAccept(resp -> callback.accept(parseResp((HttpResponse<String>)resp)))
            .exceptionally(ex -> {
               LOGGER.debug("[Ticket] post error: {}", ex.toString());
               callback.accept(null);
               return null;
            });
      } catch (Exception var4) {
         LOGGER.debug("[Ticket] post setup error: {}", var4.toString());
         callback.accept(null);
      }
   }

   private static void send(String url, String boundary, List<BodyPublisher> parts, Consumer<TicketClient.Resp> callback) {
      try {
         HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMinutes(30L))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(BodyPublishers.concat(parts.toArray(new BodyPublisher[0])))
            .build();
         HttpClient.newHttpClient()
            .sendAsync(request, BodyHandlers.ofString())
            .thenAccept(resp -> callback.accept(parseResp((HttpResponse<String>)resp)))
            .exceptionally(ex -> {
               LOGGER.warn("[Ticket] send network error: {}", ex.toString());
               callback.accept(null);
               return null;
            });
      } catch (Exception var5) {
         LOGGER.warn("[Ticket] send setup error: {}", var5.toString());
         callback.accept(null);
      }
   }

   private static TicketClient.Resp parseResp(HttpResponse<String> resp) {
      try {
         JsonElement root = JsonParser.parseString(resp.body() == null ? "" : resp.body());
         if (!root.isJsonObject()) {
            return new TicketClient.Resp(false, "BAD_RESPONSE", 0, null);
         } else {
            JsonObject obj = root.getAsJsonObject();
            if (boolval(obj, "success")) {
               return new TicketClient.Resp(true, null, 0, obj);
            } else {
               String code = str(obj, "error");
               int retry = 0;
               if (obj.has("details") && obj.get("details").isJsonObject()) {
                  retry = intval(obj.getAsJsonObject("details"), "retryAfter");
               }

               return new TicketClient.Resp(false, code.isEmpty() ? "BAD_RESPONSE" : code, retry, obj);
            }
         }
      } catch (Exception var5) {
         return new TicketClient.Resp(false, "BAD_RESPONSE", 0, null);
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

   public static final class Attach {
      public String name;
      public long size;
   }

   public static final class Detail {
      public String id;
      public long timeMs;
      public boolean deleted;
      public String description;
      public List<TicketClient.Attach> attachments = new ArrayList<>();
      public List<TicketClient.Msg> messages = new ArrayList<>();
   }

   public static final class LocalTicket {
      public String id;
      public long timeMs;
      public boolean deleted;
      public boolean hasUnread;
      public int replyCount;
      public long lastTimeMs;
   }

   public static final class Msg {
      public String from;
      public long timeMs;
      public String text;
      public List<TicketClient.Attach> attachments = new ArrayList<>();
   }

   private static final class Resp {
      final boolean success;
      final String error;
      final int retryAfter;
      final JsonObject root;

      Resp(boolean success, String error, int retryAfter, JsonObject root) {
         this.success = success;
         this.error = error;
         this.retryAfter = retryAfter;
         this.root = root;
      }
   }

   public static final class Result {
      public final boolean success;
      public final String errorCode;
      public final int retryAfterSec;
      public final String ticketId;

      Result(boolean success, String errorCode, int retryAfterSec, String ticketId) {
         this.success = success;
         this.errorCode = errorCode;
         this.retryAfterSec = retryAfterSec;
         this.ticketId = ticketId;
      }

      static TicketClient.Result ok(String id) {
         return new TicketClient.Result(true, null, 0, id);
      }
   }
}
