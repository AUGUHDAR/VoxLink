package icu.wuhui.voxlink.network;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.config.VoxLinkConfig;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpClient.Version;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class SignalingClient {
   private static final Gson GSON = new Gson();
   private static final int DEFAULT_RETRY_AFTER_SECONDS = 2;
   private static final int LOG_BODY_MAX_LEN = 500;
   private static final int HEARTBEAT_TIMEOUT_MS = 8000;
   private static final int HEARTBEAT_RETRY_DELAY_SEC = 2;
   private static final String RPC_PATH = "/rpc.php";
   private static final Map<String, String> ACTION_TO_ROUTE = Map.ofEntries(
      Map.entry("create_room", "/room/create"),
      Map.entry("update_room", "/room/update"),
      Map.entry("join_room", "/room/join"),
      Map.entry("leave_room", "/room/leave"),
      Map.entry("list_rooms", "/room/list"),
      Map.entry("heartbeat", "/room/heartbeat"),
      Map.entry("send_signal", "/signal/send"),
      Map.entry("poll_signals", "/signal/poll"),
      Map.entry("get_ip", "/stun"),
      Map.entry("check_port", "/stun/check"),
      Map.entry("get_categories", "/categories"),
      Map.entry("report_ready", "/topology/report_ready"),
      Map.entry("poll_topology", "/topology/poll"),
      Map.entry("relay_register", "/relay/register"),
      Map.entry("relay_candidates", "/relay/candidates")
   );
   private final VoxLinkConfig config;
   private final HttpClient httpClient;
   private volatile SignalingWsTransport wsTransport = null;
   private final Object wsTransportLock = new Object();
   private volatile String joinIdempotencyNonce = null;
   private final ExecutorService executor;
   private final ScheduledExecutorService scheduler;
   private static final int CREATE_ROOM_TIMEOUT_MS = 8000;
   private static final int POLL_SIGNALS_TIMEOUT_MS = 5000;
   private static final int GET_PUBLIC_IP_TIMEOUT_MS = 3000;
   private static final Set<String> TRANSIENT_ERRORS = Set.of("NETWORK_ERROR", "CDN_ERROR", "RATE_LIMITED", "QUEUED", "SERVER_403");

   private String getRpcBaseUrl() {
      String url = this.config.getServerUrl();
      if (url != null && !url.isBlank()) {
         VoxLinkMod.LOGGER.debug("[SignalingClient] serverUrl config: {}", url);
         return url.contains("route=") ? url : url.replaceAll("/+$", "") + "/?route=";
      } else {
         VoxLinkMod.LOGGER.error("[SignalingClient] serverUrl is empty");
         return "";
      }
   }

   private String buildPath(String action) {
      String baseUrl = this.getRpcBaseUrl();
      if (baseUrl.contains("route=")) {
         String route = ACTION_TO_ROUTE.getOrDefault(action, "/" + action);
         return baseUrl + route;
      } else {
         return baseUrl + "/rpc.php";
      }
   }

   private String buildGetPath(String action, String queryParams) {
      String baseUrl = this.getRpcBaseUrl();
      if (baseUrl.contains("route=")) {
         String route = ACTION_TO_ROUTE.getOrDefault(action, "/" + action);
         String url = baseUrl + route;
         if (queryParams != null && !queryParams.isEmpty()) {
            url = url + "&" + queryParams;
         }

         return url;
      } else {
         String url = baseUrl + "/rpc.php?action=" + action;
         if (queryParams != null && !queryParams.isEmpty()) {
            url = url + "&" + queryParams;
         }

         return url;
      }
   }

   public SignalingClient(VoxLinkConfig config) {
      this.config = config;
      String url = config.getServerUrl();
      if (url != null && url.startsWith("http://")) {
         // 与 VoxLinkConfig.validate 行为一致：http 默认已在 validate 中被拒并重置为默认 https；
         // 走到这里说明用户显式 allowInsecureServerUrl=true，降级为提示性日志
         if (config.isAllowInsecureServerUrl()) {
            VoxLinkMod.LOGGER.info("[SignalingClient] Insecure HTTP serverUrl explicitly allowed by config: {}", url);
         } else {
            VoxLinkMod.LOGGER.error("[SignalingClient] Plaintext HTTP serverUrl {} should have been rejected by config validation", url);
         }
      }

      this.executor = Executors.newFixedThreadPool(2, r -> {
         Thread t = new Thread(r, "VoxLink-Signaling");
         t.setDaemon(true);
         return t;
      });
      this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
         Thread t = new Thread(r, "VoxLink-Signaling-Scheduler");
         t.setDaemon(true);
         return t;
      });
      this.httpClient = HttpClient.newBuilder()
         .executor(this.executor)
         .connectTimeout(Duration.ofMillis(config.getConnectionTimeout()))
         .version(Version.HTTP_1_1)
         .followRedirects(Redirect.NORMAL)
         .build();
      VoxLinkMod.LOGGER.info("[SignalingClient] HTTP client ready, connectTimeout={}ms", config.getConnectionTimeout());
   }

   public CompletableFuture<SignalingClient.ApiResponse> createRoom(
      String name,
      String password,
      int maxPlayers,
      int hostPort,
      String natType,
      int geyserPort,
      boolean visible,
      String authType,
      String category,
      int protocolVersion,
      int peerPort,
      String hostIpv6,
      String gameVersion
   ) {
      JsonObject body = new JsonObject();
      body.addProperty("name", name != null ? name : "");
      if (password != null && !password.isEmpty()) {
         body.addProperty("password", password);
      }

      body.addProperty("maxPlayers", maxPlayers);
      body.addProperty("hostPort", hostPort);
      body.addProperty("natType", natType != null ? natType : "unknown");
      if (geyserPort > 0) {
         body.addProperty("bedrockPort", geyserPort);
      }

      body.addProperty("visible", visible);
      body.addProperty("authType", authType != null ? authType : "OFFLINE");
      body.addProperty("category", category != null ? category : "other");
      if (protocolVersion > 0) {
         body.addProperty("protocolVersion", protocolVersion);
      }

      if (peerPort > 0) {
         body.addProperty("peerPort", peerPort);
      }

      if (gameVersion != null && !gameVersion.isEmpty()) {
         body.addProperty("gameVersion", gameVersion);
      }

      if (hostIpv6 != null && !hostIpv6.isEmpty()) {
         body.addProperty("hostIpv6", hostIpv6);
      }

      body.addProperty("loader", "fabric");
      body.addProperty("clientProtocolVersion", 7);
      JsonArray capsArr = new JsonArray();

      for (String cap : ProtocolNegotiator.CURRENT_CAPABILITIES) {
         capsArr.add(cap);
      }

      body.add("clientCapabilities", capsArr);
      body.addProperty("action", "create_room");
      return this.postCreateRoom(this.buildPath("create_room"), body);
   }

   private CompletableFuture<SignalingClient.ApiResponse> postCreateRoom(String path, JsonObject body) {
      return this.postOnce(path, body, 8000L).thenCompose(response -> {
         if (!response.success && TRANSIENT_ERRORS.contains(response.error)) {
            VoxLinkMod.LOGGER.warn("POST {} failed {}, retry once after 2s (create room)", path, response.error);
            CompletableFuture<SignalingClient.ApiResponse> retry = new CompletableFuture<>();

            try {
               this.scheduler.schedule(() -> this.postOnce(path, body, 8000L).whenComplete((r, ex) -> {
                  if (ex != null) {
                     retry.completeExceptionally(ex);
                  } else {
                     retry.complete(r);
                  }
               }), 2L, TimeUnit.SECONDS);
            } catch (Exception e) {
               retry.complete(response);
            }

            return retry;
         } else {
            return CompletableFuture.completedFuture(response);
         }
      });
   }

   public CompletableFuture<SignalingClient.ApiResponse> updateRoom(
      String code, String token, String name, String password, int maxPlayers, boolean visible, String authType, String category
   ) {
      JsonObject body = new JsonObject();
      body.addProperty("code", code != null ? code : "");
      body.addProperty("token", token != null ? token : "");
      body.addProperty("name", name != null ? name : "");
      if (password != null) {
         body.addProperty("password", password);
      }

      body.addProperty("maxPlayers", maxPlayers);
      body.addProperty("visible", visible);
      body.addProperty("authType", authType != null ? authType : "OFFLINE");
      body.addProperty("category", category != null ? category : "other");
      body.addProperty("action", "update_room");
      return this.postNoRetry(this.buildPath("update_room"), body);
   }

   public CompletableFuture<SignalingClient.ApiResponse> updateTerracottaCode(String code, String token, String terracottaCode) {
      JsonObject body = new JsonObject();
      body.addProperty("code", code != null ? code : "");
      body.addProperty("token", token != null ? token : "");
      body.addProperty("terracottaCode", terracottaCode != null ? terracottaCode : "");
      body.addProperty("action", "update_room");
      return this.postNoRetry(this.buildPath("update_room"), body);
   }

   public CompletableFuture<SignalingClient.ApiResponse> joinRoom(String code, String password) {
      JsonObject body = new JsonObject();
      body.addProperty("code", code != null ? code.toUpperCase() : "");
      if (password != null && !password.isEmpty()) {
         body.addProperty("password", password);
      }

      body.addProperty("action", "join_room");
      if (this.joinIdempotencyNonce == null) {
         this.joinIdempotencyNonce = UUID.randomUUID().toString().replace("-", "");
      }

      body.addProperty("idempotencyKey", this.joinIdempotencyNonce);
      body.addProperty("clientProtocolVersion", 7);
      JsonArray capsArr = new JsonArray();

      for (String cap : ProtocolNegotiator.CURRENT_CAPABILITIES) {
         capsArr.add(cap);
      }

      body.add("clientCapabilities", capsArr);
      return this.post(this.buildPath("join_room"), body);
   }

   public CompletableFuture<SignalingClient.ApiResponse> leaveRoom(String code, String token, boolean isHost) {
      JsonObject body = new JsonObject();
      body.addProperty("code", code != null ? code : "");
      body.addProperty("token", token != null ? token : "");
      body.addProperty("isHost", isHost);
      body.addProperty("action", "leave_room");
      return this.postNoRetry(this.buildPath("leave_room"), body);
   }

   public CompletableFuture<SignalingClient.ApiResponse> listRooms(int page, int size, String category, String loader) {
      String params = "page=" + page + "&size=" + size + "&clientType=mod";
      if (category != null && !category.isEmpty()) {
         try {
            params = params + "&category=" + URLEncoder.encode(category, "UTF-8");
         } catch (UnsupportedEncodingException e) {
            params = params + "&category=" + category;
         }
      }

      if (loader != null && !loader.isEmpty()) {
         params = params + "&loader=" + loader;
      }

      return this.get(this.buildGetPath("list_rooms", params));
   }

   public CompletableFuture<SignalingClient.ApiResponse> heartbeat(
      String code, String token, boolean isHost, String natType, double load, JsonObject peerLatency, int seq, int overlayPort, int mcPlayerCount
   ) {
      JsonObject body = new JsonObject();
      body.addProperty("code", code != null ? code : "");
      body.addProperty("token", token != null ? token : "");
      body.addProperty("isHost", isHost);
      body.addProperty("natType", natType != null ? natType : "unknown");
      body.addProperty("load", load);
      body.add("peerLatency", peerLatency != null ? peerLatency : new JsonObject());
      body.addProperty("seq", seq);
      body.addProperty("overlayPort", overlayPort);
      body.addProperty("currentInterval", VoxLinkMod.getConfig().getHeartbeatInterval());
      body.addProperty("action", "heartbeat");
      if (isHost && mcPlayerCount > 0) {
         body.addProperty("mcPlayerCount", mcPlayerCount);
      }

      return this.postOnce(this.buildPath("heartbeat"), body, 8000L).thenCompose(response -> {
         if (!response.success && TRANSIENT_ERRORS.contains(response.error)) {
            CompletableFuture<SignalingClient.ApiResponse> retry = new CompletableFuture<>();

            try {
               this.scheduler.schedule(() -> this.postOnce(this.buildPath("heartbeat"), body, 8000L).whenComplete((r, ex) -> {
                  if (ex != null) {
                     retry.completeExceptionally(ex);
                  } else {
                     retry.complete(r);
                  }
               }), 2L, TimeUnit.SECONDS);
            } catch (Exception e) {
               retry.complete(response);
            }

            return retry;
         } else {
            return CompletableFuture.completedFuture(response);
         }
      });
   }

   public CompletableFuture<SignalingClient.ApiResponse> sendSignal(String code, String token, boolean isHost, String type, JsonObject data) {
      return this.sendSignal(code, token, isHost, type, data, null);
   }

   public CompletableFuture<SignalingClient.ApiResponse> sendSignal(String code, String token, boolean isHost, String type, JsonObject data, String to) {
      JsonObject body = new JsonObject();
      body.addProperty("code", code != null ? code : "");
      body.addProperty("token", token != null ? token : "");
      body.addProperty("isHost", isHost);
      body.addProperty("type", type != null ? type : "");
      body.add("data", data != null ? data : new JsonObject());
      if (to != null && !to.isEmpty()) {
         body.addProperty("to", to);
      }

      body.addProperty("action", "send_signal");
      return this.post(this.buildPath("send_signal"), body);
   }

   public CompletableFuture<SignalingClient.ApiResponse> pollSignals(String code, String token, boolean isHost, long since) {
      JsonObject body = new JsonObject();
      body.addProperty("code", code != null ? code : "");
      body.addProperty("token", token != null ? token : "");
      body.addProperty("isHost", isHost);
      body.addProperty("since", since);
      body.addProperty("action", "poll_signals");
      return this.postOnce(this.buildPath("poll_signals"), body, 5000L);
   }

   public CompletableFuture<SignalingClient.ApiResponse> getPublicIp() {
      return this.getOnce(this.buildGetPath("get_ip", null), 3000L);
   }

   public CompletableFuture<SignalingClient.ApiResponse> checkPortReachable(String ip, int port) {
      JsonObject body = new JsonObject();
      body.addProperty("ip", ip != null ? ip : "");
      body.addProperty("port", port);
      body.addProperty("action", "check_port");
      return this.post(this.buildPath("check_port"), body);
   }

   public CompletableFuture<SignalingClient.ApiResponse> registerRelayPeer(
      String clientId, String roomCode, String natType, String mappedIp, int mappedPort, boolean relayEnabled
   ) {
      JsonObject body = new JsonObject();
      body.addProperty("clientId", clientId);
      body.addProperty("roomCode", roomCode);
      body.addProperty("natType", natType);
      body.addProperty("mappedIp", mappedIp);
      body.addProperty("mappedPort", mappedPort);
      body.addProperty("relayEnabled", relayEnabled);
      body.addProperty("action", "relay_register");
      return this.post(this.buildPath("relay_register"), body);
   }

   public CompletableFuture<SignalingClient.ApiResponse> getRelayCandidates() {
      return this.get(this.buildGetPath("relay_candidates", null));
   }

   public CompletableFuture<SignalingClient.ApiResponse> getCategories() {
      return this.get(this.buildGetPath("get_categories", null));
   }

   public CompletableFuture<SignalingClient.ApiResponse> reportLinkReady(String code, String token, boolean isHost) {
      JsonObject body = new JsonObject();
      body.addProperty("code", code != null ? code : "");
      body.addProperty("token", token != null ? token : "");
      body.addProperty("isHost", isHost);
      body.addProperty("action", "report_ready");
      return this.post(this.buildPath("report_ready"), body);
   }

   public CompletableFuture<SignalingClient.ApiResponse> pollTopology(String code, String token, boolean isHost, int generation) {
      JsonObject body = new JsonObject();
      body.addProperty("code", code != null ? code : "");
      body.addProperty("token", token != null ? token : "");
      body.addProperty("isHost", isHost);
      body.addProperty("generation", generation);
      body.addProperty("action", "poll_topology");
      return this.post(this.buildPath("poll_topology"), body);
   }

   private CompletableFuture<SignalingClient.ApiResponse> postWithRetry(String path, JsonObject body, int maxRetries) {
      return this.postOnce(path, body).thenCompose(response -> {
         if (!response.success && TRANSIENT_ERRORS.contains(response.error) && maxRetries > 0) {
            long delay = response.retryAfter > 0 ? response.retryAfter : 2L;
            VoxLinkMod.LOGGER.warn("POST {} failed {}, retry after {}s ({} left)", new Object[]{path, response.error, delay, maxRetries});
            CompletableFuture<SignalingClient.ApiResponse> retry = new CompletableFuture<>();

            try {
               this.scheduler.schedule(() -> this.postWithRetry(path, body, maxRetries - 1).whenComplete((r, ex) -> {
                  if (ex != null) {
                     retry.completeExceptionally(ex);
                  } else {
                     retry.complete(r);
                  }
               }), delay, TimeUnit.SECONDS);
            } catch (Exception e) {
               retry.complete(response);
            }

            return retry;
         } else {
            return CompletableFuture.completedFuture(response);
         }
      });
   }

   private CompletableFuture<SignalingClient.ApiResponse> getWithRetry(String path, int maxRetries) {
      return this.getOnce(path).thenCompose(response -> {
         if (!response.success && TRANSIENT_ERRORS.contains(response.error) && maxRetries > 0) {
            long delay = response.retryAfter > 0 ? response.retryAfter : 2L;
            VoxLinkMod.LOGGER.warn("GET {} failed {}, retry after {}s ({} left)", new Object[]{path, response.error, delay, maxRetries});
            CompletableFuture<SignalingClient.ApiResponse> retry = new CompletableFuture<>();

            try {
               this.scheduler.schedule(() -> this.getWithRetry(path, maxRetries - 1).whenComplete((r, ex) -> {
                  if (ex != null) {
                     retry.completeExceptionally(ex);
                  } else {
                     retry.complete(r);
                  }
               }), delay, TimeUnit.SECONDS);
            } catch (Exception e) {
               retry.complete(response);
            }

            return retry;
         } else {
            return CompletableFuture.completedFuture(response);
         }
      });
   }

   private CompletableFuture<SignalingClient.ApiResponse> post(String path, JsonObject body) {
      return this.postWithRetry(path, body, 2);
   }

   private CompletableFuture<SignalingClient.ApiResponse> postNoRetry(String path, JsonObject body) {
      return this.postOnce(path, body);
   }

   private CompletableFuture<SignalingClient.ApiResponse> postOnce(String path, JsonObject body) {
      return this.postOnce(path, body, this.config.getConnectionTimeout());
   }

   private CompletableFuture<SignalingClient.ApiResponse> postOnce(String path, JsonObject body, long timeoutMs) {
      if (this.wsEnabled()) {
         WsRouteInfo info = this.parseWsRoute(path);
         if (info != null) {
            return this.getWsTransport()
               .request(info.route, "POST", body, info.query, timeoutMs)
               .exceptionallyCompose(ex -> this.httpPostOnce(path, body, timeoutMs));
         }
      }
      return this.httpPostOnce(path, body, timeoutMs);
   }

   private CompletableFuture<SignalingClient.ApiResponse> httpPostOnce(String path, JsonObject body, long timeoutMs) {
      String url = path.startsWith("http") ? path : this.getRpcBaseUrl() + path;
      VoxLinkMod.LOGGER.debug("[SignalingClient] POST {}", url);
      long requestStart = System.currentTimeMillis();
      HttpRequest request = HttpRequest.newBuilder()
         .uri(URI.create(url))
         .header("Content-Type", "application/json")
         .header("Accept", "application/json")
         .header("User-Agent", "Mozilla/5.0 (Java) VoxLink/" + VoxLinkMod.MOD_VERSION)
         .header("X-VoxLink-Version", VoxLinkMod.MOD_VERSION)
         .timeout(Duration.ofMillis(timeoutMs))
         .POST(BodyPublishers.ofString(GSON.toJson(body)))
         .build();
      return this.httpClient
         .sendAsync(request, BodyHandlers.ofString())
         .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
         .thenApply(response -> {
            long elapsed = System.currentTimeMillis() - requestStart;
            VoxLinkMod.LOGGER.debug("[SignalingClient] POST {} completed in {}ms, status={}", new Object[]{url, elapsed, response.statusCode()});
            int status = response.statusCode();
            if (status == 200) {
               return SignalingClient.ApiResponse.fromHttpResponse(status, response.body());
            } else if (status == 429) {
               int retryAfter = parseRetryAfter((HttpResponse<String>)response);
               return new SignalingClient.ApiResponse(false, "RATE_LIMITED", "RATE_LIMITED", null, -1, retryAfter);
            } else if (status != 502 && status != 503 && status != 504) {
               SignalingClient.ApiResponse parsed = SignalingClient.ApiResponse.tryParseError(response.body());
               return parsed != null ? parsed : new SignalingClient.ApiResponse(false, "SERVER_" + status, "SERVER_" + status, null);
            } else {
               SignalingClient.ApiResponse parsed503 = SignalingClient.ApiResponse.tryParseError(response.body());
               return parsed503 != null ? parsed503 : new SignalingClient.ApiResponse(false, "CDN_ERROR", "CDN_ERROR", null, -1, 5);
            }
         })
         .exceptionally(
            e -> {
               long elapsed = System.currentTimeMillis() - requestStart;
               String msg = e == null ? null : e.getMessage();
               Throwable cause = e != null ? e.getCause() : null;
               VoxLinkMod.LOGGER
                  .warn(
                     "[SignalingClient] POST {} failed after {}ms: {} (reason: {})",
                     new Object[]{url, elapsed, msg, cause != null ? cause.getClass().getSimpleName() : "none"}
                  );
               if (!(e instanceof TimeoutException) && (msg == null || !msg.contains("timed out") && !msg.contains("Timeout") && !msg.contains("timed_out"))) {
                  if (msg == null || !msg.contains("refused") && !msg.contains("Connection reset")) {
                     if (msg != null && msg.contains("SSL")) {
                        return new SignalingClient.ApiResponse(false, "NETWORK_ERROR", "SSL_ERROR", null);
                     } else {
                        return msg == null || !msg.contains("UnknownHost") && !msg.contains("nodename")
                           ? new SignalingClient.ApiResponse(false, "NETWORK_ERROR", "NETWORK_ERROR", null)
                           : new SignalingClient.ApiResponse(false, "NETWORK_ERROR", "UNKNOWN_HOST", null);
                     }
                  } else {
                     return new SignalingClient.ApiResponse(false, "NETWORK_ERROR", "CONNECTION_REFUSED", null);
                  }
               } else {
                  return new SignalingClient.ApiResponse(false, "NETWORK_ERROR", "CONNECTION_TIMEOUT", null);
               }
            }
         );
   }

   private CompletableFuture<SignalingClient.ApiResponse> get(String path) {
      return this.getWithRetry(path, 2);
   }

   private CompletableFuture<SignalingClient.ApiResponse> getOnce(String path) {
      return this.getOnce(path, this.config.getConnectionTimeout());
   }

   private CompletableFuture<SignalingClient.ApiResponse> getOnce(String path, long timeoutMs) {
      if (this.wsEnabled()) {
         WsRouteInfo info = this.parseWsRoute(path);
         if (info != null) {
            return this.getWsTransport()
               .request(info.route, "GET", null, info.query, timeoutMs)
               .exceptionallyCompose(ex -> this.httpGetOnce(path, timeoutMs));
         }
      }
      return this.httpGetOnce(path, timeoutMs);
   }

   private CompletableFuture<SignalingClient.ApiResponse> httpGetOnce(String path, long timeoutMs) {
      String url = path.startsWith("http") ? path : this.getRpcBaseUrl() + path;
      VoxLinkMod.LOGGER.debug("[SignalingClient] GET {}", url);
      long requestStart = System.currentTimeMillis();
      HttpRequest request = HttpRequest.newBuilder()
         .uri(URI.create(url))
         .header("Accept", "application/json")
         .header("User-Agent", "Mozilla/5.0 (Java) VoxLink/" + VoxLinkMod.MOD_VERSION)
         .header("X-VoxLink-Version", VoxLinkMod.MOD_VERSION)
         .timeout(Duration.ofMillis(timeoutMs))
         .GET()
         .build();
      return this.httpClient
         .sendAsync(request, BodyHandlers.ofString())
         .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
         .thenApply(response -> {
            long elapsed = System.currentTimeMillis() - requestStart;
            VoxLinkMod.LOGGER.debug("[SignalingClient] GET {} completed in {}ms, status={}", new Object[]{url, elapsed, response.statusCode()});
            int status = response.statusCode();
            if (status == 200) {
               return SignalingClient.ApiResponse.fromHttpResponse(status, response.body());
            } else if (status == 429) {
               int retryAfter = parseRetryAfter((HttpResponse<String>)response);
               return new SignalingClient.ApiResponse(false, "RATE_LIMITED", "RATE_LIMITED", null, -1, retryAfter);
            } else if (status != 502 && status != 503 && status != 504) {
               SignalingClient.ApiResponse parsed = SignalingClient.ApiResponse.tryParseError(response.body());
               return parsed != null ? parsed : new SignalingClient.ApiResponse(false, "SERVER_" + status, "SERVER_" + status, null);
            } else {
               SignalingClient.ApiResponse parsed503 = SignalingClient.ApiResponse.tryParseError(response.body());
               return parsed503 != null ? parsed503 : new SignalingClient.ApiResponse(false, "CDN_ERROR", "CDN_ERROR", null, -1, 5);
            }
         })
         .exceptionally(
            e -> {
               long elapsed = System.currentTimeMillis() - requestStart;
               String msg = e == null ? null : e.getMessage();
               Throwable cause = e != null ? e.getCause() : null;
               VoxLinkMod.LOGGER
                  .warn(
                     "[SignalingClient] GET {} failed after {}ms: {} (reason: {})",
                     new Object[]{url, elapsed, msg, cause != null ? cause.getClass().getSimpleName() : "none"}
                  );
               if (!(e instanceof TimeoutException) && (msg == null || !msg.contains("timed out") && !msg.contains("Timeout"))) {
                  if (msg == null || !msg.contains("refused") && !msg.contains("Connection reset")) {
                     if (msg != null && msg.contains("SSL")) {
                        return new SignalingClient.ApiResponse(false, "NETWORK_ERROR", "SSL_ERROR", null);
                     } else {
                        return msg == null || !msg.contains("UnknownHost") && !msg.contains("nodename")
                           ? new SignalingClient.ApiResponse(false, "NETWORK_ERROR", "NETWORK_ERROR", null)
                           : new SignalingClient.ApiResponse(false, "NETWORK_ERROR", "UNKNOWN_HOST", null);
                     }
                  } else {
                     return new SignalingClient.ApiResponse(false, "NETWORK_ERROR", "CONNECTION_REFUSED", null);
                  }
               } else {
                  return new SignalingClient.ApiResponse(false, "NETWORK_ERROR", "CONNECTION_TIMEOUT", null);
               }
            }
         );
   }

   private static int parseRetryAfter(HttpResponse<String> response) {
      String retryAfterStr = response.headers().firstValue("Retry-After").orElse(null);
      if (retryAfterStr != null) {
         try {
            return Math.max(Integer.parseInt(retryAfterStr), 1);
         } catch (NumberFormatException var3) {
         }
      }

      return 0;
   }

   public void shutdown() {
      if (this.wsTransport != null) {
         this.wsTransport.close();
         this.wsTransport = null;
      }
      this.executor.shutdownNow();
      this.scheduler.shutdownNow();
   }

   private boolean wsEnabled() {
      if (!this.config.isUseWebSocket()) {
         return false;
      }
      SignalingWsTransport t = this.getWsTransport();
      return t != null && t.canUse();
   }

   private SignalingWsTransport getWsTransport() {
      if (this.wsTransport == null) {
         synchronized (this.wsTransportLock) {
            if (this.wsTransport == null) {
               this.wsTransport = new SignalingWsTransport(this.config.getServerUrl());
            }
         }
      }
      return this.wsTransport;
   }

   /** WS 是否已连接且存活（供 RoomManager 做轮询间隔自适应）。 */
   public boolean isWsConnected() {
      return this.wsTransport != null && this.wsTransport.isConnected();
   }

   /** 透传信号推送监听器（data 为 {"s":[...],"ts":N}）；传 null 取消。useWebSocket 关闭时不注册。 */
   public void setSignalPushHandler(Consumer<JsonObject> handler) {
      if (!this.config.isUseWebSocket()) {
         return;
      }
      if (handler != null) {
         this.getWsTransport().setPushListener(handler);
      } else if (this.wsTransport != null) {
         this.wsTransport.setPushListener(null);
      }
   }

   /** 从完整 URL 解析出 WS 帧所需的 route 与 query；非 /?route= 形态（旧 PHP）返回 null 走 HTTP。 */
   private WsRouteInfo parseWsRoute(String path) {
      if (path == null || !path.startsWith("http")) {
         return null;
      }

      try {
         URI uri = URI.create(path);
         String query = uri.getQuery();
         if (query == null || query.isEmpty()) {
            return null;
         }

         String route = null;
         Map<String, String> queryMap = new LinkedHashMap<>();
         for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            String k = idx < 0 ? pair : pair.substring(0, idx);
            String v = idx < 0 ? "" : pair.substring(idx + 1);
            if ("route".equals(k)) {
               route = v;
            } else {
               queryMap.put(k, v);
            }
         }

         if (route == null || route.isEmpty()) {
            return null;
         }

         WsRouteInfo info = new WsRouteInfo();
         info.route = route;
         info.query = queryMap.isEmpty() ? null : queryMap;
         return info;
      } catch (Exception e) {
         return null;
      }
   }

   private static final class WsRouteInfo {
      String route;
      Map<String, String> query;
   }

   public static class ApiResponse {
      public final boolean success;
      public final String error;
      public final String message;
      public final JsonObject data;
      public final int queuePosition;
      public final int retryAfter;

      public ApiResponse(boolean success, String error, String message, JsonObject data) {
         this(success, error, message, data, -1, 0);
      }

      public ApiResponse(boolean success, String error, String message, JsonObject data, int queuePosition) {
         this(success, error, message, data, queuePosition, 0);
      }

      public ApiResponse(boolean success, String error, String message, JsonObject data, int queuePosition, int retryAfter) {
         this.success = success;
         this.error = error;
         this.message = message;
         this.data = data;
         this.queuePosition = queuePosition;
         this.retryAfter = retryAfter;
      }

      public static SignalingClient.ApiResponse fromHttpResponse(int statusCode, String body) {
         try {
            JsonObject json = (JsonObject)SignalingClient.GSON.fromJson(body, JsonObject.class);
            if (json == null) {
               return new SignalingClient.ApiResponse(false, "PARSE_ERROR", "空响应", null);
            }

            return fromJson(json);
         } catch (Exception e) {
            String bodyPreview = body != null ? body.substring(0, Math.min(body.length(), 500)) : "null";
            VoxLinkMod.LOGGER.error("API response parse failed (status {}): {}", statusCode, bodyPreview);
            VoxLinkMod.LOGGER.error("Parse error: {}", e.getMessage());
            return new SignalingClient.ApiResponse(false, "PARSE_ERROR", "响应解析失败", null);
         }
      }

      /** 从已解析的 JSON 对象（HTTP 响应体或 WS 帧体，二者同构）构造 ApiResponse。 */
      public static SignalingClient.ApiResponse fromJson(JsonObject json) {
         if (json == null) {
            return new SignalingClient.ApiResponse(false, "PARSE_ERROR", "空响应", null);
         }

         boolean success = json.has("success") && json.get("success").getAsBoolean();
         String error = json.has("error") ? json.get("error").getAsString() : null;
         String message = json.has("message") ? json.get("message").getAsString() : null;
         JsonObject data = json.has("data") && json.get("data").isJsonObject() ? json.getAsJsonObject("data") : null;
         int position = json.has("position") ? json.get("position").getAsInt() : -1;
         int retryAfter = json.has("retryAfter") ? json.get("retryAfter").getAsInt() : 0;
         return new SignalingClient.ApiResponse(success, error, message, data, position, retryAfter);
      }

      public static SignalingClient.ApiResponse tryParseError(String body) {
         if (body != null && !body.isEmpty()) {
            try {
               JsonObject json = (JsonObject)SignalingClient.GSON.fromJson(body, JsonObject.class);
               if (json == null) {
                  return null;
               }

               String error = json.has("error") ? json.get("error").getAsString() : null;
               String message = json.has("message") ? json.get("message").getAsString() : null;
               int position = json.has("position") ? json.get("position").getAsInt() : -1;
               int retryAfter = json.has("retryAfter") ? json.get("retryAfter").getAsInt() : 0;
               if (error != null) {
                  return new SignalingClient.ApiResponse(false, error, message, null, position, retryAfter);
               }
            } catch (Exception var6) {
            }

            return null;
         } else {
            return null;
         }
      }
   }
}
