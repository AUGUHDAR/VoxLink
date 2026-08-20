package icu.wuhui.voxlink.network;

import com.google.gson.JsonObject;
import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.config.LogUploadState;
import icu.wuhui.voxlink.config.VoxLinkConfig;
import java.io.ByteArrayOutputStream;
import java.io.RandomAccessFile;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public final class LogUploadManager
{
   private static final long UPLOAD_DELAY_MS = 90L * 1000L;
   private static final long STABLE_WINDOW_MS = 120L * 1000L;
   private static final long ACTIVITY_TIMEOUT_MS = 15L * 1000L;
   private static final long HOST_WAIT_TIMEOUT_MS = 5L * 60L * 1000L;
   private static final int MAX_LOG_BYTES = 4 * 1024 * 1024;
   private static final int MAX_ATTEMPTS = 3;
   private static final long RETRY_DELAY_MS = 15_000L;
   private static final String ROUTE_STATUS = "/log/status";
   private static final String ROUTE_UPLOAD = "/log/upload";

   private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "VoxLink-LogUpload");
      t.setDaemon(true);
      return t;
   });

   private static final AtomicReference<String> ACTIVE_CODE = new AtomicReference<>(null);
   private static final AtomicBoolean UPLOADED = new AtomicBoolean(false);
   // 上传单飞: 并发双runUpload时只允许一个真正post, 杜绝重复上传/重复toast
   private static final AtomicBoolean UPLOAD_IN_FLIGHT = new AtomicBoolean(false);
   private static volatile ScheduledFuture<?> uploadFuture;
   private static volatile ScheduledFuture<?> pollFuture;
   private static volatile Runnable onUploaded;
   private static volatile String activeCode;
   private static volatile boolean activeIsHost;
   private static volatile long punchStartMs;
   private static volatile long connectedAtMs = 0L;
   private static volatile long lastTransportActivityMs = 0L;
   private static volatile long lastActivityReportMs = 0L;
   private static volatile String role;
   private static volatile HttpClient httpClient;

   private LogUploadManager() {}

   public static void arm(String code, boolean isHost)
   {
      String normalized = code == null ? "" : code.trim();
      if (normalized.isEmpty()) {
         return;
      }
      String existing = ACTIVE_CODE.get();
      if (normalized.equals(existing)) {
         if (UPLOADED.get()) {
            UPLOADED.set(false);
         }
         return;
      }
      if (existing != null && uploadFuture != null) {
         uploadFuture.cancel(false);
      }
      if (pollFuture != null) {
         pollFuture.cancel(false);
         pollFuture = null;
      }
      ACTIVE_CODE.set(normalized);
      activeCode = normalized;
      activeIsHost = isHost;
      punchStartMs = System.currentTimeMillis();
      role = isHost ? "host" : "joiner";
      UPLOADED.set(false);
      VoxLinkMod.LOGGER.info("[LogUpload] armed code={} role={}", activeCode, role);
      reportStatus();
      if (uploadFuture != null) {
         uploadFuture.cancel(false);
      }
      // 两端各自计时2分钟; host从建房改到真正开打(punch_info)才起算(见schedulePunchUpload),
      // 否则host建房间比joiner进房早, 上传永远早于joiner; host额外保留"对手已上传立即传"快路径
      if (!activeIsHost) {
         uploadFuture = SCHEDULER.schedule(LogUploadManager::runUpload, UPLOAD_DELAY_MS, TimeUnit.MILLISECONDS);
      }
      if (isHost) {
         pollFuture = SCHEDULER.schedule(LogUploadManager::pollOpponentUploaded, 5L, TimeUnit.SECONDS);
      }
   }

   public static void schedulePunchUpload()
   {
      if (!activeIsHost || UPLOADED.get()) {
         return;
      }
      // 自身已连接且稳定时, 不应因"新加入玩家(可能失败)触发的punch"去重铸铁上传/去上传样本
      if (connectedAtMs > 0L && System.currentTimeMillis() - connectedAtMs >= STABLE_WINDOW_MS) {
         return;
      }
      if (uploadFuture != null) {
         uploadFuture.cancel(false);
      }
      punchStartMs = System.currentTimeMillis();
      uploadFuture = SCHEDULER.schedule(LogUploadManager::runUpload, UPLOAD_DELAY_MS, TimeUnit.MILLISECONDS);
      // 成功连接时disarm会取消轮询; 新joiner进房重打时恢复"对手已传→1秒跟上"快路径
      if (pollFuture != null) {
         pollFuture.cancel(false);
      }
      pollFuture = SCHEDULER.schedule(LogUploadManager::pollOpponentUploaded, 5L, TimeUnit.SECONDS);
      VoxLinkMod.LOGGER.info("[LogUpload] host punch started, upload timer re-armed from now");
   }

   // 连接成功事件: 不再直接取消上传——连上后短时间内掉线仍属失败, 需要日志诊断
   public static void onConnected()
   {
      connectedAtMs = System.currentTimeMillis();
      lastTransportActivityMs = connectedAtMs;
   }

   // 数据面收到对端包即视为活跃(内部节流1s, receiveLoop每包调用开销可忽略)
   public static void onTransportActivity()
   {
      long now = System.currentTimeMillis();
      if (now - lastActivityReportMs >= 1000L) {
         lastActivityReportMs = now;
         lastTransportActivityMs = now;
      }
   }

   // 断链事件(稳定窗口内掉线=高价值失败现场, 立即上传)
   public static void onDisconnected()
   {
      if (connectedAtMs > 0L && System.currentTimeMillis() - connectedAtMs < STABLE_WINDOW_MS && !UPLOADED.get()) {
         if (uploadFuture != null) {
            uploadFuture.cancel(false);
         }
         uploadFuture = SCHEDULER.schedule(LogUploadManager::runUpload, 1L, TimeUnit.SECONDS);
         VoxLinkMod.LOGGER.info("[LogUpload] link dropped inside stable window, upload now");
      }
      connectedAtMs = 0L;
   }

   private static void pollOpponentUploaded()
   {
      if (pollFuture == null) {
         return;
      }
      String code = activeCode;
      if (code == null || UPLOADED.get()) {
         return;
      }
      if (System.currentTimeMillis() - punchStartMs > HOST_WAIT_TIMEOUT_MS) {
         VoxLinkMod.LOGGER.info("[LogUpload] host wait timeout, upload anyway");
         rescheduleUploadNow();
         return;
      }
      JsonObject body = new JsonObject();
      body.addProperty("code", code);
      body.addProperty("role", role);
      body.addProperty("enabled", LogUploadState.isLogUploadEnabled());
      body.addProperty("query", true);
      postOnce(ROUTE_STATUS, body).thenAccept(response -> {
         if (pollFuture == null) {
            return;
         }
         boolean opponentUploaded = false;
         if (response != null && response.success && response.data != null && response.data.has("opponent")) {
            JsonObject opp = response.data.getAsJsonObject("opponent");
            opponentUploaded = opp.has("uploaded") && opp.get("uploaded").getAsBoolean();
         }
         if (opponentUploaded) {
            VoxLinkMod.LOGGER.info("[LogUpload] opponent uploaded, host upload now");
            rescheduleUploadNow();
         } else {
            pollFuture = SCHEDULER.schedule(LogUploadManager::pollOpponentUploaded, 5L, TimeUnit.SECONDS);
         }
      });
   }

   // 覆盖调度前必须先取消旧任务, 避免双runUpload并发
   private static void rescheduleUploadNow()
   {
      if (uploadFuture != null) {
         uploadFuture.cancel(false);
      }
      uploadFuture = SCHEDULER.schedule(LogUploadManager::runUpload, 1L, TimeUnit.MILLISECONDS);
   }

   public static void disarm()
   {
      if (uploadFuture != null) {
         uploadFuture.cancel(false);
         uploadFuture = null;
      }
      if (pollFuture != null) {
         pollFuture.cancel(false);
         pollFuture = null;
      }
      ACTIVE_CODE.set(null);
   }

   public static void setOnUploaded(Runnable callback)
   {
      onUploaded = callback;
   }

   public static boolean isUploadFinished()
   {
      return UPLOADED.get();
   }

   private static void notifyUploadedInChat()
   {
      Minecraft mc = Minecraft.getInstance();
      if (mc == null || mc.player == null) {
         return;
      }
      mc.execute(() -> {
         if (mc.player == null) {
            return;
         }
         try {
            Component msg = Component.translatable("voxlink.log_upload.uploaded");
            try {
               mc.player.getClass().getMethod("sendSystemMessage", Component.class).invoke(mc.player, msg);
            } catch (NoSuchMethodException e) {
               mc.player.getClass().getMethod("displayClientMessage", Component.class, boolean.class).invoke(mc.player, msg, false);
            }
         } catch (Exception e) {
            VoxLinkMod.LOGGER.debug("[LogUpload] chat notify failed: {}", e.getMessage());
         }
      });
   }

   private static void reportStatus()
   {
      boolean enabled = LogUploadState.isLogUploadEnabled();
      JsonObject body = new JsonObject();
      body.addProperty("code", activeCode);
      body.addProperty("role", role);
      body.addProperty("enabled", enabled);
      postOnce(ROUTE_STATUS, body).thenAccept(response -> {
         if (!response.success) {
            VoxLinkMod.LOGGER.warn("[LogUpload] status report failed: {}", response.error);
         }
      });
   }

   private static void runUpload()
   {
      String code = activeCode;
      if (code == null || !ACTIVE_CODE.get().equals(code)) {
         return;
      }
      if (!LogUploadState.isLogUploadEnabled()) {
         VoxLinkMod.LOGGER.info("[LogUpload] skipped, upload disabled by player");
         return;
      }
      if (UPLOADED.get()) {
         return;
      }
      if (connectedAtMs > 0L) {
         long now = System.currentTimeMillis();
         if (now - lastTransportActivityMs > ACTIVITY_TIMEOUT_MS) {
            // 数据面已静默: 连上但链路死了, 属失败, 立即上传
            VoxLinkMod.LOGGER.info("[LogUpload] transport silent after connect, upload now");
         } else if (now - connectedAtMs < STABLE_WINDOW_MS) {
            // 连接仍在稳定窗口内: 顺延复查, 稳定超窗口=成功不上传
            uploadFuture = SCHEDULER.schedule(LogUploadManager::runUpload, 15L, TimeUnit.SECONDS);
            return;
         } else {
            VoxLinkMod.LOGGER.info("[LogUpload] connection stable, skip upload");
            return;
         }
      }
      uploadWithRetry(code, MAX_ATTEMPTS);
   }

   private static void uploadWithRetry(String code, int attemptsLeft)
   {
      if (!UPLOAD_IN_FLIGHT.compareAndSet(false, true)) {
         VoxLinkMod.LOGGER.info("[LogUpload] upload already in flight, skip duplicate");
         return;
      }
      byte[] payload = buildPayload();
      if (payload == null) {
         UPLOAD_IN_FLIGHT.set(false);
         return;
      }
      CompletableFuture<SignalingClient.ApiResponse> upload = postBytes(ROUTE_UPLOAD, payload, code);
      upload.thenAccept(response -> {
         UPLOAD_IN_FLIGHT.set(false);
         if (response.success) {
            VoxLinkMod.LOGGER.info("[LogUpload] uploaded code={} role={} size={}", code, role, payload.length);
            UPLOADED.set(true);
            Runnable cb = onUploaded;
            if (cb != null) {
               cb.run();
            }
            notifyUploadedInChat();
         } else if (attemptsLeft > 1) {
            VoxLinkMod.LOGGER.warn("[LogUpload] upload failed ({}), retrying {} left", response.error, attemptsLeft - 1);
            SCHEDULER.schedule(() -> uploadWithRetry(code, attemptsLeft - 1), RETRY_DELAY_MS, TimeUnit.MILLISECONDS);
         } else {
            VoxLinkMod.LOGGER.warn("[LogUpload] upload gave up, error={}", response.error);
         }
      });
   }

   private static byte[] buildPayload()
   {
      try {
         byte[] raw = readLogTail();
         if (raw == null || raw.length == 0) {
            VoxLinkMod.LOGGER.info("[LogUpload] no log content, skip");
            return null;
         }
         ByteArrayOutputStream bos = new ByteArrayOutputStream();
         try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(raw);
         }
         return bos.toByteArray();
      } catch (Exception e) {
         VoxLinkMod.LOGGER.warn("[LogUpload] gzip failed: {}", e.getMessage());
         return null;
      }
   }

   private static byte[] readLogTail()
   {
      try {
         java.io.File logFile = new java.io.File(Minecraft.getInstance().gameDirectory, "logs/latest.log");
         if (!logFile.isFile()) {
            VoxLinkMod.LOGGER.info("[LogUpload] latest.log missing, skip");
            return null;
         }
         long length = logFile.length();
         if (length <= 0) {
            return new byte[0];
         }
         long offset = Math.max(0, length - MAX_LOG_BYTES);
         try (RandomAccessFile raf = new RandomAccessFile(logFile, "r")) {
            raf.seek(offset);
            byte[] data = new byte[(int) (length - offset)];
            raf.readFully(data);
            return data;
         }
      } catch (Exception e) {
         VoxLinkMod.LOGGER.warn("[LogUpload] read latest.log failed: {}", e.getMessage());
         return null;
      }
   }

   private static CompletableFuture<SignalingClient.ApiResponse> postOnce(String path, JsonObject body)
   {
      return postJson(path, body).thenApply(LogUploadManager::parseResponse);
   }

   private static CompletableFuture<HttpResponse<String>> postJson(String path, JsonObject body)
   {
      String url = buildUrl(path);
      HttpRequest request = HttpRequest.newBuilder()
         .uri(URI.create(url))
         .header("Content-Type", "application/json")
         .header("Accept", "application/json")
         .header("User-Agent", "Mozilla/5.0 (Java) VoxLink/" + VoxLinkMod.MOD_VERSION)
         .header("X-VoxLink-Version", VoxLinkMod.MOD_VERSION)
         .timeout(Duration.ofMillis(client().connectTimeout().orElse(Duration.ofSeconds(10)).toMillis()))
         .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
         .build();
      return client().sendAsync(request, HttpResponse.BodyHandlers.ofString());
   }

   private static CompletableFuture<SignalingClient.ApiResponse> postBytes(String path, byte[] payload, String code)
   {
      String url = buildUrl(path);
      String sha = sha256(payload);
      long durationMs = Math.max(0, System.currentTimeMillis() - punchStartMs);
      String playerName = Minecraft.getInstance().getUser().getName();
      HttpRequest request = HttpRequest.newBuilder()
         .uri(URI.create(url))
         .header("Content-Type", "application/octet-stream")
         .header("Accept", "application/json")
         .header("User-Agent", "Mozilla/5.0 (Java) VoxLink/" + VoxLinkMod.MOD_VERSION)
         .header("X-VoxLink-Version", VoxLinkMod.MOD_VERSION)
         .header("X-Log-Sha256", sha)
         .header("X-Log-Role", role)
         .header("X-Log-Code", code)
         .header("X-Log-Name", playerName == null ? "" : playerName)
         .header("X-Log-Version", VoxLinkMod.MOD_VERSION)
         .header("X-Log-Duration-Ms", String.valueOf(durationMs))
         .timeout(Duration.ofMillis(client().connectTimeout().orElse(Duration.ofSeconds(10)).toMillis()))
         .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
         .build();
      return client().sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(LogUploadManager::parseResponse);
   }

   private static SignalingClient.ApiResponse parseResponse(HttpResponse<String> response)
   {
      int status = response.statusCode();
      if (status == 200) {
         return SignalingClient.ApiResponse.fromHttpResponse(status, response.body());
      }
      if (status == 429) {
         return new SignalingClient.ApiResponse(false, "RATE_LIMITED", "RATE_LIMITED", null, -1, 15);
      }
      SignalingClient.ApiResponse parsed = SignalingClient.ApiResponse.tryParseError(response.body());
      return parsed != null ? parsed : new SignalingClient.ApiResponse(false, "SERVER_" + status, "SERVER_" + status, null);
   }

   private static String buildUrl(String path)
   {
      VoxLinkConfig config = VoxLinkMod.getConfig();
      String url = config.getServerUrl();
      if (url == null || url.isBlank()) {
         return path;
      }
      String base = url.contains("route=") ? url : url.replaceAll("/+$", "") + "/?route=";
      return base + path;
   }

   private static HttpClient client()
   {
      HttpClient c = httpClient;
      if (c == null) {
         VoxLinkConfig config = VoxLinkMod.getConfig();
         c = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(config.getConnectionTimeout()))
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
         httpClient = c;
      }
      return c;
   }

   private static String sha256(byte[] data)
   {
      try {
         MessageDigest md = MessageDigest.getInstance("SHA-256");
         byte[] digest = md.digest(data);
         StringBuilder sb = new StringBuilder(64);
         for (byte b : digest) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
         }
         return sb.toString();
      } catch (Exception e) {
         return "";
      }
   }
}
