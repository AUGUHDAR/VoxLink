package icu.wuhui.voxlink.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 工单客户端：本地存单号（无账号体系的身份锚点），启动时向服务器轮询一次回复状态。
 * 端点与限频见服务端 ticket.go（提交/追问共用每 IP 10 分钟限频）。
 */
public final class TicketClient {
   private static final Logger LOGGER = LoggerFactory.getLogger("voxlink-ticket");
   private static final List<LocalTicket> LOCAL = new ArrayList<>();
   private static final AtomicBoolean LOADED = new AtomicBoolean(false);
   private static final AtomicBoolean POLLED = new AtomicBoolean(false);

   /** 本地工单记录（voxlink_tickets.json 的一行）。 */
   public static final class LocalTicket {
      public String id;
      /** 提交时服务端签发的一次性归属凭证（明文只出现这一次），随查询/追问/删除回传。 */
      public String secret = "";
      public long timeMs;
      public boolean deleted;
      public boolean hasUnread;
      public int replyCount;
      public long lastTimeMs;
   }

   /** 服务端详情（ticket/detail 响应）。 */
   public static final class Detail {
      public String id;
      public long timeMs;
      public boolean deleted;
      public String description;
      public List<Attach> attachments = new ArrayList<>();
      public List<Msg> messages = new ArrayList<>();
   }

   public static final class Attach {
      public String name;
      public long size;
   }

   public static final class Msg {
      /** 服务端消息主键，撤回要按它定位；老服务端没这个字段时为 null（撤回入口自动禁用）。 */
      public String id;
      public String from;
      public long timeMs;
      public String text;
      public List<Attach> attachments = new ArrayList<>();
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

      static Result ok(String id) {
         return new Result(true, null, 0, id);
      }
   }

   private TicketClient() {
   }

   private static Path storePath() {
      return Minecraft.getInstance().gameDirectory.toPath().resolve("voxlink_tickets.json");
   }

   private static synchronized void load() {
      if (LOADED.get()) {
         return;
      }
      LOADED.set(true);
      try {
         Path p = storePath();
         if (Files.isRegularFile(p)) {
            var root = JsonParser.parseString(Files.readString(p));
            if (root.isJsonArray()) {
               for (var e : root.getAsJsonArray()) {
                  if (!e.isJsonObject()) {
                     continue;
                  }
                  var o = e.getAsJsonObject();
                  LocalTicket t = new LocalTicket();
                  t.id = str(o, "id");
                  t.secret = str(o, "secret");
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
      } catch (Exception e) {
         LOGGER.debug("[Ticket] load store failed: {}", e.toString());
      }
   }

   private static void save() {
      try {
         Path p = storePath();
         JsonArray arr = new JsonArray();
         synchronized (LOCAL) {
            for (LocalTicket t : LOCAL) {
               JsonObject o = new JsonObject();
               o.addProperty("id", t.id);
               o.addProperty("secret", t.secret == null ? "" : t.secret);
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
      } catch (Exception e) {
         LOGGER.warn("[Ticket] save store failed: {}", e.toString());
      }
   }

   /** 本地工单快照（新→旧）。 */
   public static List<LocalTicket> snapshot() {
      load();
      synchronized (LOCAL) {
         List<LocalTicket> out = new ArrayList<>(LOCAL);
         out.sort((a, b) -> Long.compare(b.lastTimeMs != 0 ? b.lastTimeMs : b.timeMs, a.lastTimeMs != 0 ? a.lastTimeMs : a.timeMs));
         return out;
      }
   }

   /** 是否存在未读回复（主菜单弹窗依据）。 */
   public static boolean hasUnread() {
      load();
      synchronized (LOCAL) {
         for (LocalTicket t : LOCAL) {
            if (t.hasUnread && !t.deleted) {
               return true;
            }
         }
      }
      return false;
   }

   /** 取本地保存的工单归属凭证；未知/老记录返回空串（服务端对老工单继续放行）。 */
   private static String secretOf(String ticketId) {
      load();
      if (ticketId == null) {
         return "";
      }
      synchronized (LOCAL) {
         for (LocalTicket t : LOCAL) {
            if (t.id.equals(ticketId)) {
               return t.secret == null ? "" : t.secret;
            }
         }
      }
      return "";
   }

   private static void upsertLocal(String id, long timeMs, String secret) {
      load();
      synchronized (LOCAL) {
         for (LocalTicket t : LOCAL) {
            if (t.id.equals(id)) {
               if (secret != null && !secret.isEmpty() && (t.secret == null || t.secret.isEmpty())) {
                  t.secret = secret;
                  save();
               }
               return;
            }
         }
         LocalTicket t = new LocalTicket();
         t.id = id;
         t.secret = secret == null ? "" : secret;
         t.timeMs = timeMs;
         t.lastTimeMs = timeMs;
         LOCAL.add(t);
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

   /** 启动后首次进入主菜单时轮询一次（静默，失败仅 debug）。onDone 在 HTTP 线程触发，可为 null。 */
   public static void pollOnceIfNeeded(Runnable onDone) {
      if (POLLED.getAndSet(true)) {
         return;
      }
      load();
      List<String> ids;
      synchronized (LOCAL) {
         ids = new ArrayList<>();
         for (LocalTicket t : LOCAL) {
            ids.add(t.id);
         }
      }
      if (ids.isEmpty()) {
         if (onDone != null) {
            onDone.run();
         }
         return;
      }
      String url = endpoint("/ticket/poll");
      if (url == null) {
         if (onDone != null) {
            onDone.run();
         }
         return;
      }
      JsonObject body = new JsonObject();
      JsonArray arr = new JsonArray();
      for (String id : ids) {
         arr.add(id);
      }
      body.add("ids", arr);
      postJson(url, body, resp -> {
         if (resp != null && resp.success) {
            var data = resp.root.getAsJsonObject("data");
            if (data != null && data.has("tickets") && data.get("tickets").isJsonArray()) {
               for (var e : data.getAsJsonArray("tickets")) {
                  if (!e.isJsonObject()) {
                     continue;
                  }
                  var o = e.getAsJsonObject();
                  String id = str(o, "id");
                  synchronized (LOCAL) {
                     for (LocalTicket t : LOCAL) {
                        if (t.id.equals(id)) {
                           t.hasUnread = boolval(o, "hasUnread");
                           t.deleted = boolval(o, "deleted");
                           t.replyCount = intval(o, "replyCount");
                           t.lastTimeMs = longval(o, "lastTime") * 1000L;
                           break;
                        }
                     }
                  }
               }
               save();
            }
            // 服务端已经删掉的单号（管理员硬删/90 天保留期清理）：本地连一次性 secret 一起丢弃，
            // 否则列表里永远躺着一张点不开的僵尸单
            if (data != null && data.has("removed") && data.get("removed").isJsonArray()) {
               final JsonArray gone = data.getAsJsonArray("removed");
               if (gone.size() > 0) {
                  synchronized (LOCAL) {
                     LOCAL.removeIf(t -> {
                        for (var r : gone) {
                           if (r.isJsonPrimitive() && r.getAsString().equals(t.id)) {
                              return true;
                           }
                        }
                        return false;
                     });
                  }
                  save();
               }
            }
         }
         if (onDone != null) {
            onDone.run();
         }
      });
   }

   /** 提交工单（multipart：description/client/clientInfo/attachments）。 */
   public static void submit(String serverUrl, String description, String clientInfo,
                             List<Path> attachments, Consumer<Result> callback) {
      String url = normalize(serverUrl, "/ticket/submit");
      if (url == null) {
         callback.accept(new Result(false, "BAD_SERVER_URL", 0, null));
         return;
      }
      String boundary = "----VoxLinkTK" + UUID.randomUUID().toString().replace("-", "");
      List<HttpRequest.BodyPublisher> parts = new ArrayList<>();
      parts.add(textPart(boundary, "description", description));
      parts.add(textPart(boundary, "client", "mod"));
      parts.add(textPart(boundary, "clientInfo", clientInfo));
      long[] total = new long[]{0L};
      List<Path> files = FeedbackUploader.mergeFiles(attachments, null, total);
      if (total[0] > FeedbackUploader.MAX_TOTAL_BYTES) {
         callback.accept(new Result(false, "TICKET_TOO_LARGE", 0, null));
         return;
      }
      for (Path f : files) {
         parts.add(filePart(boundary, f));
      }
      parts.add(HttpRequest.BodyPublishers.ofByteArray(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8)));
      send(url, boundary, parts, resp -> {
         if (resp == null) {
            callback.accept(new Result(false, "NETWORK_ERROR", 0, null));
            return;
         }
         if (resp.success) {
            JsonObject tkData = resp.root.getAsJsonObject("data");
            String id = str(tkData, "id");
            upsertLocal(id, System.currentTimeMillis(), str(tkData, "ticketSecret"));
            callback.accept(Result.ok(id));
         } else {
            callback.accept(new Result(false, resp.error, resp.retryAfter, null));
         }
      });
   }

   /** 追问（multipart：id/text/attachments）。 */
   public static void reply(String serverUrl, String ticketId, String text,
                            List<Path> attachments, Consumer<Result> callback) {
      String url = normalize(serverUrl, "/ticket/reply");
      if (url == null) {
         callback.accept(new Result(false, "BAD_SERVER_URL", 0, null));
         return;
      }
      String boundary = "----VoxLinkTK" + UUID.randomUUID().toString().replace("-", "");
      List<HttpRequest.BodyPublisher> parts = new ArrayList<>();
      parts.add(textPart(boundary, "id", ticketId));
      parts.add(textPart(boundary, "secret", secretOf(ticketId)));
      parts.add(textPart(boundary, "text", text));
      long[] total = new long[]{0L};
      List<Path> files = FeedbackUploader.mergeFiles(attachments, null, total);
      if (total[0] > FeedbackUploader.MAX_TOTAL_BYTES) {
         callback.accept(new Result(false, "TICKET_TOO_LARGE", 0, null));
         return;
      }
      for (Path f : files) {
         parts.add(filePart(boundary, f));
      }
      parts.add(HttpRequest.BodyPublishers.ofByteArray(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8)));
      send(url, boundary, parts, resp -> {
         if (resp == null) {
            callback.accept(new Result(false, "NETWORK_ERROR", 0, null));
            return;
         }
         if (resp.success) {
            callback.accept(Result.ok(ticketId));
         } else {
            callback.accept(new Result(false, resp.error, resp.retryAfter, null));
         }
      });
   }

   /**
    * 撤回自己发出的某一条追问。服务端只允许撤回 From=player 且归属凭证相符的消息，
    * 撤管理员的消息会回 TICKET_FORBIDDEN。msgId 来自 {@link Msg#id}；
    * 老服务端不返回 id 时调用方拿不到 id，界面上撤回入口是禁用的，不会发这种请求。
    */
   public static void retract(String serverUrl, String ticketId, String msgId, Consumer<Result> callback) {
      String url = normalize(serverUrl, "/ticket/retract");
      if (url == null) {
         callback.accept(new Result(false, "BAD_SERVER_URL", 0, null));
         return;
      }
      if (msgId == null || msgId.isEmpty()) {
         callback.accept(new Result(false, "NO_MESSAGE_ID", 0, null));
         return;
      }
      JsonObject body = new JsonObject();
      body.addProperty("id", ticketId);
      body.addProperty("msg", msgId);
      body.addProperty("secret", secretOf(ticketId));
      postJson(url, body, resp -> {
         if (resp == null) {
            callback.accept(new Result(false, "NETWORK_ERROR", 0, null));
         } else if (resp.success) {
            callback.accept(Result.ok(ticketId));
         } else {
            callback.accept(new Result(false, resp.error, resp.retryAfter, null));
         }
      });
   }

   /** 拉取工单详情。回调在 HttpClient 线程触发（UI 需自行切主线程）。 */
   public static void fetchDetail(String serverUrl, String ticketId, Consumer<Detail> callback) {
      String sec = secretOf(ticketId);
      // 只能拼在 query 段：normalize 把 route 塞进 "?route=..."，query 混进去服务端就找不到接口了
      String base = normalize(serverUrl, "/ticket/detail");
      String url = base == null ? null : base + "&id=" + ticketId + (sec.isEmpty() ? "" : "&secret=" + sec);
      if (url == null) {
         callback.accept(null);
         return;
      }
      HttpClient.newHttpClient()
         .sendAsync(HttpRequest.newBuilder(URI.create(url)).timeout(java.time.Duration.ofSeconds(15)).GET().build(),
            HttpResponse.BodyHandlers.ofString())
         .thenAccept(resp -> callback.accept(parseDetail(resp)))
         .exceptionally(ex -> {
            LOGGER.debug("[Ticket] detail error: {}", ex.toString());
            callback.accept(null);
            return null;
         });
   }

   /** 已读回执（fire-and-forget，本地未读即时清零）。 */
   public static void markViewed(String serverUrl, String ticketId) {
      load();
      synchronized (LOCAL) {
         for (LocalTicket t : LOCAL) {
            if (t.id.equals(ticketId)) {
               t.hasUnread = false;
               break;
            }
         }
      }
      save();
      String url = normalize(serverUrl, "/ticket/viewed");
      if (url == null) {
         return;
      }
      JsonObject body = new JsonObject();
      body.addProperty("id", ticketId);
      body.addProperty("secret", secretOf(ticketId));
      postJson(url, body, resp -> {
      });
   }

   /** 玩家端软删：本地即时标记，服务器打"玩家端已删除"标签。 */
   public static void deleteLocal(String serverUrl, String ticketId) {
      load();
      synchronized (LOCAL) {
         for (LocalTicket t : LOCAL) {
            if (t.id.equals(ticketId)) {
               t.deleted = true;
               t.hasUnread = false;
               break;
            }
         }
      }
      save();
      String url = normalize(serverUrl, "/ticket/delete");
      if (url == null) {
         return;
      }
      JsonObject body = new JsonObject();
      body.addProperty("id", ticketId);
      body.addProperty("secret", secretOf(ticketId));
      postJson(url, body, resp -> {
      });
   }

   private static Detail parseDetail(HttpResponse<String> resp) {
      try {
         var root = JsonParser.parseString(resp.body() == null ? "" : resp.body());
         if (!root.isJsonObject()) {
            return null;
         }
         var obj = root.getAsJsonObject();
         if (!boolval(obj, "success")) {
            return null;
         }
         var data = obj.getAsJsonObject("data");
         if (data == null) {
            return null;
         }
         Detail d = new Detail();
         d.id = str(data, "id");
         d.timeMs = longval(data, "time") * 1000L;
         d.deleted = boolval(data, "deleted");
         d.description = str(data, "description");
         if (data.has("attachments") && data.get("attachments").isJsonArray()) {
            for (var e : data.getAsJsonArray("attachments")) {
               if (e.isJsonObject()) {
                  d.attachments.add(parseAttach(e.getAsJsonObject()));
               }
            }
         }
         if (data.has("messages") && data.get("messages").isJsonArray()) {
            for (var e : data.getAsJsonArray("messages")) {
               if (!e.isJsonObject()) {
                  continue;
               }
               var mo = e.getAsJsonObject();
               Msg m = new Msg();
               m.id = str(mo, "id");
               m.from = str(mo, "from");
               m.timeMs = longval(mo, "time") * 1000L;
               m.text = str(mo, "text");
               if (mo.has("attachments") && mo.get("attachments").isJsonArray()) {
                  for (var ae : mo.getAsJsonArray("attachments")) {
                     if (ae.isJsonObject()) {
                        m.attachments.add(parseAttach(ae.getAsJsonObject()));
                     }
                  }
               }
               d.messages.add(m);
            }
         }
         return d;
      } catch (Exception e) {
         LOGGER.debug("[Ticket] parse detail failed: {}", e.toString());
         return null;
      }
   }

   private static Attach parseAttach(JsonObject o) {
      Attach a = new Attach();
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
      } catch (Exception ex) {
         return null;
      }
   }

   private static String endpoint(String route) {
      try {
         String cfg = icu.wuhui.voxlink.VoxLinkMod.getConfig().getServerUrl();
         return normalize(cfg, route);
      } catch (Exception e) {
         return null;
      }
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

   private static void postJson(String url, JsonObject body, Consumer<Resp> callback) {
      try {
         HttpClient.newHttpClient()
            .sendAsync(HttpRequest.newBuilder(URI.create(url))
                  .timeout(java.time.Duration.ofSeconds(15))
                  .header("Content-Type", "application/json")
                  .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                  .build(),
               HttpResponse.BodyHandlers.ofString())
            .thenAccept(resp -> callback.accept(parseResp(resp)))
            .exceptionally(ex -> {
               LOGGER.debug("[Ticket] post error: {}", ex.toString());
               callback.accept(null);
               return null;
            });
      } catch (Exception e) {
         LOGGER.debug("[Ticket] post setup error: {}", e.toString());
         callback.accept(null);
      }
   }

   private static void send(String url, String boundary, List<HttpRequest.BodyPublisher> parts, Consumer<Resp> callback) {
      try {
         HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(java.time.Duration.ofMinutes(30L))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.concat(parts.toArray(new HttpRequest.BodyPublisher[0])))
            .build();
         HttpClient.newHttpClient()
            .sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenAccept(resp -> callback.accept(parseResp(resp)))
            .exceptionally(ex -> {
               LOGGER.warn("[Ticket] send network error: {}", ex.toString());
               callback.accept(null);
               return null;
            });
      } catch (Exception e) {
         LOGGER.warn("[Ticket] send setup error: {}", e.toString());
         callback.accept(null);
      }
   }

   private static Resp parseResp(HttpResponse<String> resp) {
      try {
         var root = JsonParser.parseString(resp.body() == null ? "" : resp.body());
         if (!root.isJsonObject()) {
            return new Resp(false, "BAD_RESPONSE", 0, null);
         }
         var obj = root.getAsJsonObject();
         if (boolval(obj, "success")) {
            return new Resp(true, null, 0, obj);
         }
         String code = str(obj, "error");
         int retry = 0;
         if (obj.has("details") && obj.get("details").isJsonObject()) {
            retry = intval(obj.getAsJsonObject("details"), "retryAfter");
         }
         return new Resp(false, code.isEmpty() ? "BAD_RESPONSE" : code, retry, obj);
      } catch (Exception e) {
         return new Resp(false, "BAD_RESPONSE", 0, null);
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
