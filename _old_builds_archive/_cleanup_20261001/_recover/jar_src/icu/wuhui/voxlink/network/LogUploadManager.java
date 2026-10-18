package icu.wuhui.voxlink.network;

import com.google.gson.JsonObject;
import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.config.LogUploadState;
import icu.wuhui.voxlink.config.VoxLinkConfig;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.RandomAccessFile;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpClient.Version;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public final class LogUploadManager {
   private static final long UPLOAD_DELAY_MS = 90000L;
   private static final long STABLE_WINDOW_MS = 120000L;
   private static final long ACTIVITY_TIMEOUT_MS = 15000L;
   private static final long HOST_WAIT_TIMEOUT_MS = 300000L;
   private static final long FAILURE_UPLOAD_DELAY_MS = 5000L;
   private static final long MIN_AGE_FOR_QUIT_UPLOAD_MS = 30000L;
   private static final long SHUTDOWN_UPLOAD_TIMEOUT_MS = 6000L;
   private static final int MAX_LOG_BYTES = 4194304;
   private static final int MAX_ATTEMPTS = 3;
   private static final long RETRY_DELAY_MS = 15000L;
   private static final String ROUTE_STATUS = "/log/status";
   private static final String ROUTE_UPLOAD = "/log/upload";
   private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "VoxLink-LogUpload");
      t.setDaemon(true);
      return t;
   });
   private static final AtomicReference<String> ACTIVE_CODE = new AtomicReference<>(null);
   private static final AtomicBoolean UPLOADED = new AtomicBoolean(false);
   private static final AtomicBoolean UPLOAD_IN_FLIGHT = new AtomicBoolean(false);
   private static volatile boolean failureUploadArmed = false;
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

   private LogUploadManager() {
   }

   public static void arm(String code, boolean isHost) {
      String normalized = code == null ? "" : code.trim();
      if (!normalized.isEmpty()) {
         String existing = ACTIVE_CODE.get();
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
         failureUploadArmed = false;
         connectedAtMs = 0L;
         lastTransportActivityMs = 0L;
         VoxLinkMod.LOGGER.info("[LogUpload] armed code={} role={}", activeCode, role);
         reportStatus();
         if (uploadFuture != null) {
            uploadFuture.cancel(false);
         }

         if (!activeIsHost) {
            uploadFuture = SCHEDULER.schedule(LogUploadManager::runUpload, 90000L, TimeUnit.MILLISECONDS);
         }

         if (isHost) {
            pollFuture = SCHEDULER.schedule(LogUploadManager::pollOpponentUploaded, 5L, TimeUnit.SECONDS);
         }
      }
   }

   public static void schedulePunchUpload() {
      if (activeIsHost && !UPLOADED.get()) {
         if (connectedAtMs <= 0L || System.currentTimeMillis() - connectedAtMs < 120000L) {
            if (uploadFuture != null) {
               uploadFuture.cancel(false);
            }

            punchStartMs = System.currentTimeMillis();
            uploadFuture = SCHEDULER.schedule(LogUploadManager::runUpload, 90000L, TimeUnit.MILLISECONDS);
            if (pollFuture != null) {
               pollFuture.cancel(false);
            }

            pollFuture = SCHEDULER.schedule(LogUploadManager::pollOpponentUploaded, 5L, TimeUnit.SECONDS);
            VoxLinkMod.LOGGER.info("[LogUpload] host punch started, upload timer re-armed from now");
         }
      }
   }

   public static void onConnected() {
      connectedAtMs = System.currentTimeMillis();
      lastTransportActivityMs = connectedAtMs;
   }

   public static void onTransportActivity() {
      long now = System.currentTimeMillis();
      if (now - lastActivityReportMs >= 1000L) {
         lastActivityReportMs = now;
         lastTransportActivityMs = now;
      }
   }

   public static void onDisconnected() {
      if (connectedAtMs > 0L && System.currentTimeMillis() - connectedAtMs < 120000L && !UPLOADED.get()) {
         if (uploadFuture != null) {
            uploadFuture.cancel(false);
         }

         uploadFuture = SCHEDULER.schedule(LogUploadManager::runUpload, 1L, TimeUnit.SECONDS);
         VoxLinkMod.LOGGER.info("[LogUpload] link dropped inside stable window, upload now");
      }

      connectedAtMs = 0L;
   }

   public static void onTerminalFailure() {
      if (activeCode != null && !UPLOADED.get() && !failureUploadArmed) {
         if (connectedAtMs <= 0L || System.currentTimeMillis() - connectedAtMs < 120000L) {
            failureUploadArmed = true;
            if (uploadFuture != null) {
               uploadFuture.cancel(false);
            }

            uploadFuture = SCHEDULER.schedule(LogUploadManager::runUpload, 5000L, TimeUnit.MILLISECONDS);
            VoxLinkMod.LOGGER.info("[LogUpload] terminal failure captured, upload in {}ms", 5000L);
         }
      }
   }

   private static void pollOpponentUploaded() {
      if (pollFuture != null) {
         String code = activeCode;
         if (code != null && !UPLOADED.get()) {
            if (connectedAtMs > 0L && System.currentTimeMillis() - connectedAtMs >= 120000L) {
               if (pollFuture != null) {
                  pollFuture.cancel(false);
                  pollFuture = null;
               }
            } else if (System.currentTimeMillis() - punchStartMs > 300000L) {
               VoxLinkMod.LOGGER.info("[LogUpload] host wait timeout, upload anyway");
               rescheduleUploadNow();
            } else {
               JsonObject body = new JsonObject();
               body.addProperty("code", code);
               body.addProperty("role", role);
               body.addProperty("enabled", LogUploadState.isLogUploadEnabled());
               body.addProperty("query", true);
               postOnce("/log/status", body).thenAccept(response -> {
                  if (pollFuture != null) {
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
                  }
               });
            }
         }
      }
   }

   private static void rescheduleUploadNow() {
      if (uploadFuture != null) {
         uploadFuture.cancel(false);
      }

      uploadFuture = SCHEDULER.schedule(LogUploadManager::runUpload, 1L, TimeUnit.MILLISECONDS);
   }

   public static void disarm() {
      boolean flushFailure = failureUploadArmed && !UPLOADED.get() && ACTIVE_CODE.get() != null;
      if (uploadFuture != null) {
         uploadFuture.cancel(false);
         uploadFuture = null;
      }

      if (pollFuture != null) {
         pollFuture.cancel(false);
         pollFuture = null;
      }

      ACTIVE_CODE.set(null);
      if (flushFailure && LogUploadState.isLogUploadEnabled()) {
         ACTIVE_CODE.set(activeCode);
         uploadFuture = SCHEDULER.schedule(LogUploadManager::runUpload, 200L, TimeUnit.MILLISECONDS);
         VoxLinkMod.LOGGER.info("[LogUpload] left room after terminal failure, flush log now");
      }
   }

   public static void setOnUploaded(Runnable callback) {
      onUploaded = callback;
   }

   public static boolean isUploadFinished() {
      return UPLOADED.get();
   }

   private static void notifyUploadedInChat() {
      Minecraft mc = Minecraft.getInstance();
      if (mc != null && mc.player != null) {
         mc.execute(() -> {
            if (mc.player != null) {
               try {
                  Component msg = Component.translatable("voxlink.log_upload.uploaded");

                  try {
                     mc.player.getClass().getMethod("sendSystemMessage", Component.class).invoke(mc.player, msg);
                  } catch (NoSuchMethodException var3) {
                     mc.player.getClass().getMethod("displayClientMessage", Component.class, boolean.class).invoke(mc.player, msg, false);
                  }
               } catch (Exception var4) {
                  VoxLinkMod.LOGGER.debug("[LogUpload] chat notify failed: {}", var4.getMessage());
               }
            }
         });
      }
   }

   private static void reportStatus() {
      boolean enabled = LogUploadState.isLogUploadEnabled();
      JsonObject body = new JsonObject();
      body.addProperty("code", activeCode);
      body.addProperty("role", role);
      body.addProperty("enabled", enabled);
      postOnce("/log/status", body).thenAccept(response -> {
         if (!response.success) {
            VoxLinkMod.LOGGER.warn("[LogUpload] status report failed: {}", response.error);
         }
      });
   }

   private static void runUpload() {
      String code = activeCode;
      if (code != null && ACTIVE_CODE.get().equals(code)) {
         if (!LogUploadState.isLogUploadEnabled()) {
            VoxLinkMod.LOGGER.info("[LogUpload] skipped, upload disabled by player");
         } else if (!UPLOADED.get()) {
            if (connectedAtMs > 0L) {
               long now = System.currentTimeMillis();
               if (now - lastTransportActivityMs <= 15000L) {
                  if (now - connectedAtMs < 120000L) {
                     if (uploadFuture != null) {
                        uploadFuture.cancel(false);
                     }

                     uploadFuture = SCHEDULER.schedule(LogUploadManager::runUpload, 15L, TimeUnit.SECONDS);
                     return;
                  }

                  VoxLinkMod.LOGGER.info("[LogUpload] connection stable, skip upload");
                  return;
               }

               VoxLinkMod.LOGGER.info("[LogUpload] transport silent after connect, upload now");
            }

            uploadWithRetry(code, 3);
         }
      }
   }

   private static void uploadWithRetry(String code, int attemptsLeft) {
      String cur = ACTIVE_CODE.get();
      if (cur != null && cur.equals(code)) {
         if (!UPLOAD_IN_FLIGHT.compareAndSet(false, true)) {
            VoxLinkMod.LOGGER.info("[LogUpload] upload already in flight, retry in 15s");
            if (uploadFuture != null) {
               uploadFuture.cancel(false);
            }

            uploadFuture = SCHEDULER.schedule(() -> uploadWithRetry(code, attemptsLeft), 15000L, TimeUnit.MILLISECONDS);
         } else {
            byte[] payload = buildPayload();
            if (payload == null) {
               UPLOAD_IN_FLIGHT.set(false);
            } else {
               CompletableFuture<SignalingClient.ApiResponse> upload = postBytes("/log/upload", payload, code).orTimeout(90L, TimeUnit.SECONDS);
               upload.whenComplete((response, ex) -> {
                  UPLOAD_IN_FLIGHT.set(false);
                  if (ex != null) {
                     Throwable root = ex.getCause() != null ? ex.getCause() : ex;
                     String why = root instanceof TimeoutException ? "timeout" : String.valueOf(root);
                     if (attemptsLeft > 1) {
                        VoxLinkMod.LOGGER.warn("[LogUpload] upload error ({}), retrying {} left", why, attemptsLeft - 1);
                        SCHEDULER.schedule(() -> uploadWithRetry(code, attemptsLeft - 1), 15000L, TimeUnit.MILLISECONDS);
                     } else {
                        VoxLinkMod.LOGGER.warn("[LogUpload] upload gave up, error={}", why);
                     }
                  } else {
                     if (response != null && response.success) {
                        VoxLinkMod.LOGGER.info("[LogUpload] uploaded code={} role={} size={}", new Object[]{code, role, payload.length});
                        UPLOADED.set(true);
                        Runnable cb = onUploaded;
                        if (cb != null) {
                           cb.run();
                        }

                        notifyUploadedInChat();
                     } else if (attemptsLeft > 1) {
                        VoxLinkMod.LOGGER.warn("[LogUpload] upload failed ({}), retrying {} left", response.error, attemptsLeft - 1);
                        SCHEDULER.schedule(() -> uploadWithRetry(code, attemptsLeft - 1), 15000L, TimeUnit.MILLISECONDS);
                     } else {
                        VoxLinkMod.LOGGER.warn("[LogUpload] upload gave up, error={}", response.error);
                     }
                  }
               });
            }
         }
      }
   }

   private static void uploadOnJvmShutdown() {
      try {
         String code = ACTIVE_CODE.get();
         if (code == null || code.isEmpty() || UPLOADED.get() || UPLOAD_IN_FLIGHT.get()) {
            return;
         }

         if (!LogUploadState.isLogUploadEnabled()) {
            return;
         }

         if (connectedAtMs > 0L) {
            return;
         }

         long now = System.currentTimeMillis();
         boolean failed = failureUploadArmed;
         boolean abandoned = now - punchStartMs >= 30000L;
         if (!failed && !abandoned) {
            return;
         }

         byte[] payload = buildPayload();
         if (payload == null) {
            return;
         }

         UPLOAD_IN_FLIGHT.set(true);
         uploadSyncBeforeExit(code, payload, failed ? "failed" : "unfinished");
      } catch (Throwable var6) {
      }
   }

   private static void uploadSyncBeforeExit(String code, byte[] payload, String cause) {
      try {
         String url = buildUrl("/log/upload");
         String playerName = "";

         try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.getUser() != null && mc.getUser().getName() != null) {
               playerName = mc.getUser().getName();
            }
         } catch (Throwable var10) {
         }

         long durationMs = Math.max(0L, System.currentTimeMillis() - punchStartMs);
         HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/octet-stream")
            .header("Accept", "application/json")
            .header("User-Agent", "Mozilla/5.0 (Java) VoxLink/" + VoxLinkMod.MOD_VERSION)
            .header("X-VoxLink-Version", VoxLinkMod.MOD_VERSION)
            .header("X-Log-Sha256", sha256(payload))
            .header("X-Log-Role", role == null ? "" : role)
            .header("X-Log-Code", code)
            .header("X-Log-Name", playerName)
            .header("X-Log-Version", VoxLinkMod.MOD_VERSION)
            .header("X-Log-Duration-Ms", String.valueOf(durationMs))
            .timeout(Duration.ofMillis(6000L))
            .POST(BodyPublishers.ofByteArray(payload))
            .build();
         HttpClient shutdownClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(Math.min(6000L, 4000L)))
            .version(Version.HTTP_1_1)
            .followRedirects(Redirect.NORMAL)
            .build();
         HttpResponse<String> response = shutdownClient.send(request, BodyHandlers.ofString());
         if (response.statusCode() == 200) {
            UPLOADED.set(true);
            VoxLinkMod.LOGGER.info("[LogUpload] shutdown upload ok ({} session, {} bytes)", cause, payload.length);
         } else {
            VoxLinkMod.LOGGER.warn("[LogUpload] shutdown upload rejected: HTTP {}", response.statusCode());
         }
      } catch (Exception var11) {
         VoxLinkMod.LOGGER.warn("[LogUpload] shutdown upload failed: {}", var11.getMessage());
      }
   }

   private static byte[] buildPayload() {
      try {
         byte[] raw = readLogTail();
         if (raw != null && raw.length != 0) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();

            try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
               gz.write(raw);
            }

            return bos.toByteArray();
         } else {
            VoxLinkMod.LOGGER.info("[LogUpload] no log content, skip");
            return null;
         }
      } catch (Exception var7) {
         VoxLinkMod.LOGGER.warn("[LogUpload] gzip failed: {}", var7.getMessage());
         return null;
      }
   }

   private static byte[] readLogTail() {
      try {
         File logFile = new File(Minecraft.getInstance().gameDirectory, "logs/latest.log");
         if (!logFile.isFile()) {
            VoxLinkMod.LOGGER.info("[LogUpload] latest.log missing, skip");
            return null;
         } else {
            long length = logFile.length();
            if (length <= 0L) {
               return new byte[0];
            } else {
               long offset = Math.max(0L, length - 4194304L);

               byte[] var7;
               try (RandomAccessFile raf = new RandomAccessFile(logFile, "r")) {
                  raf.seek(offset);
                  byte[] data = new byte[(int)(length - offset)];
                  raf.readFully(data);
                  var7 = data;
               }

               return var7;
            }
         }
      } catch (Exception var10) {
         VoxLinkMod.LOGGER.warn("[LogUpload] read latest.log failed: {}", var10.getMessage());
         return null;
      }
   }

   private static CompletableFuture<SignalingClient.ApiResponse> postOnce(String path, JsonObject body) {
      return postJson(path, body).thenApply(LogUploadManager::parseResponse);
   }

   private static CompletableFuture<HttpResponse<String>> postJson(String path, JsonObject body) {
      String url = buildUrl(path);
      HttpRequest request = HttpRequest.newBuilder()
         .uri(URI.create(url))
         .header("Content-Type", "application/json")
         .header("Accept", "application/json")
         .header("User-Agent", "Mozilla/5.0 (Java) VoxLink/" + VoxLinkMod.MOD_VERSION)
         .header("X-VoxLink-Version", VoxLinkMod.MOD_VERSION)
         .timeout(Duration.ofMillis(client().connectTimeout().orElse(Duration.ofSeconds(10L)).toMillis()))
         .POST(BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
         .build();
      return client().sendAsync(request, BodyHandlers.ofString());
   }

   private static CompletableFuture<SignalingClient.ApiResponse> postBytes(String path, byte[] payload, String code) {
      String url = buildUrl(path);
      String sha = sha256(payload);
      long durationMs = Math.max(0L, System.currentTimeMillis() - punchStartMs);
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
         .timeout(Duration.ofMillis(client().connectTimeout().orElse(Duration.ofSeconds(10L)).toMillis()))
         .POST(BodyPublishers.ofByteArray(payload))
         .build();
      return client().sendAsync(request, BodyHandlers.ofString()).thenApply(LogUploadManager::parseResponse);
   }

   private static SignalingClient.ApiResponse parseResponse(HttpResponse<String> response) {
      int status = response.statusCode();
      if (status == 200) {
         return SignalingClient.ApiResponse.fromHttpResponse(status, response.body());
      } else if (status == 429) {
         return new SignalingClient.ApiResponse(false, "RATE_LIMITED", "RATE_LIMITED", null, -1, 15);
      } else {
         SignalingClient.ApiResponse parsed = SignalingClient.ApiResponse.tryParseError(response.body());
         return parsed != null ? parsed : new SignalingClient.ApiResponse(false, "SERVER_" + status, "SERVER_" + status, null);
      }
   }

   private static String buildUrl(String path) {
      VoxLinkConfig config = VoxLinkMod.getConfig();
      String url = config.getServerUrl();
      if (url != null && !url.isBlank()) {
         String base = url.contains("route=") ? url : url.replaceAll("/+$", "") + "/?route=";
         return base + path;
      } else {
         return path;
      }
   }

   private static HttpClient client() {
      HttpClient c = httpClient;
      if (c == null) {
         VoxLinkConfig config = VoxLinkMod.getConfig();
         c = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis((long)config.getConnectionTimeout()))
            .version(Version.HTTP_1_1)
            .followRedirects(Redirect.NORMAL)
            .build();
         httpClient = c;
      }

      return c;
   }

   private static String sha256(byte[] data) {
      try {
         MessageDigest md = MessageDigest.getInstance("SHA-256");
         byte[] digest = md.digest(data);
         StringBuilder sb = new StringBuilder(64);

         for (byte b : digest) {
            sb.append(Character.forDigit(b >> 4 & 15, 16)).append(Character.forDigit(b & 15, 16));
         }

         return sb.toString();
      } catch (Exception var8) {
         return "";
      }
   }

   static {
      try {
         Runtime.getRuntime().addShutdownHook(new Thread(LogUploadManager::uploadOnJvmShutdown, "VoxLink-LogUpload-Shutdown"));
      } catch (Throwable var1) {
      }
   }
}
