package icu.wuhui.voxlink.network;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.config.VoxLinkConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class SignalingClient {
    private static final Gson GSON = new Gson();
    private static final int DEFAULT_RETRY_AFTER_SECONDS = 2;

    private static final String RPC_PATH = "/rpc.php";

    private static final java.util.Map<String, String> ACTION_TO_ROUTE = java.util.Map.ofEntries(
            java.util.Map.entry("create_room", "/room/create"),
            java.util.Map.entry("update_room", "/room/update"),
            java.util.Map.entry("join_room", "/room/join"),
            java.util.Map.entry("leave_room", "/room/leave"),
            java.util.Map.entry("get_room_info", "/room/info"),
            java.util.Map.entry("list_rooms", "/room/list"),
            java.util.Map.entry("heartbeat", "/room/heartbeat"),
            java.util.Map.entry("send_signal", "/signal/send"),
            java.util.Map.entry("poll_signals", "/signal/poll"),
            java.util.Map.entry("get_ip", "/stun"),
            java.util.Map.entry("check_port", "/stun/check"),
            java.util.Map.entry("get_categories", "/categories"),
            java.util.Map.entry("report_ready", "/topology/report_ready"),
            java.util.Map.entry("poll_topology", "/topology/poll"),
            java.util.Map.entry("get_bedrock", "/room/bedrock"),
            java.util.Map.entry("relay_register", "/relay/register"),
            java.util.Map.entry("relay_candidates", "/relay/candidates")
    );

    private final VoxLinkConfig config;
    private final HttpClient httpClient;
    private final ExecutorService executor;
    private final java.util.concurrent.ScheduledExecutorService scheduler;

    private String getRpcBaseUrl() {
        String url = config.getServerUrl();
        if (url == null || url.isBlank()) {
            VoxLinkMod.LOGGER.error("[SignalingClient] serverUrl是空的");
            return "";
        }
        VoxLinkMod.LOGGER.debug("[SignalingClient] serverUrl配置: {}", url);
        if (url.contains("route=")) {
            return url;
        }
        return url.replaceAll("/+$", "") + "/?route=";
    }

    private String buildPath(String action) {
        String baseUrl = getRpcBaseUrl();
        if (baseUrl.contains("route=")) {
            String route = ACTION_TO_ROUTE.getOrDefault(action, "/" + action);
            return baseUrl + route;
        }
        return baseUrl + RPC_PATH;
    }

    private String buildGetPath(String action, String queryParams) {
        String baseUrl = getRpcBaseUrl();
        if (baseUrl.contains("route=")) {
            String route = ACTION_TO_ROUTE.getOrDefault(action, "/" + action);
            String url = baseUrl + route;
            if (queryParams != null && !queryParams.isEmpty()) {
                url += "&" + queryParams;
            }
            return url;
        }
        String url = baseUrl + RPC_PATH + "?action=" + action;
        if (queryParams != null && !queryParams.isEmpty()) {
            url += "&" + queryParams;
        }
        return url;
    }

    public SignalingClient(VoxLinkConfig config) {
        this.config = config;
        String url = config.getServerUrl();
        if (url != null && url.startsWith("http://")) {
            VoxLinkMod.LOGGER.warn("[SignalingClient] 用了HTTP不是HTTPS: {}，建议换成HTTPS", url);
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
                .executor(executor)
                .connectTimeout(Duration.ofMillis(config.getConnectionTimeout()))
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        VoxLinkMod.LOGGER.info("[SignalingClient] HTTP客户端就绪, connectTimeout={}ms", config.getConnectionTimeout());
    }

    public CompletableFuture<ApiResponse> createRoom(String name, String password, int maxPlayers, int hostPort, String natType, int geyserPort, boolean visible, String authType, String category, int protocolVersion, int peerPort, String hostIpv6) {
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
        if (hostIpv6 != null && !hostIpv6.isEmpty()) {
            body.addProperty("hostIpv6", hostIpv6);
        }
        body.addProperty("action", "create_room");
    return postCreateRoom(buildPath("create_room"), body);
}

    //创建房间专用: 8s超时+1次重试, 避免长时间等待和创建多个孤儿房间
    private static final int CREATE_ROOM_TIMEOUT_MS = 8000;
    private CompletableFuture<ApiResponse> postCreateRoom(String path, JsonObject body) {
        return postOnce(path, body, CREATE_ROOM_TIMEOUT_MS).thenCompose(response -> {
            if (response.success || !TRANSIENT_ERRORS.contains(response.error)) {
                return CompletableFuture.completedFuture(response);
            }
            VoxLinkMod.LOGGER.warn("POST {}失败{}, 2s后重试1次(创建房间)", path, response.error);
            CompletableFuture<ApiResponse> retry = new CompletableFuture<>();
            try {
                scheduler.schedule(() -> {
                    postOnce(path, body, CREATE_ROOM_TIMEOUT_MS).whenComplete((r, ex) -> {
                        if (ex != null) retry.completeExceptionally(ex);
                        else retry.complete(r);
                    });
                }, 2, java.util.concurrent.TimeUnit.SECONDS);
            } catch (java.util.concurrent.RejectedExecutionException e) {
                retry.complete(response);
            }
            return retry;
        });
    }

    public CompletableFuture<ApiResponse> updateRoom(String code, String token, String name, String password, int maxPlayers, boolean visible, String authType, String category) {
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
        return postNoRetry(buildPath("update_room"), body);
    }

    public CompletableFuture<ApiResponse> joinRoom(String code, String password) {
        JsonObject body = new JsonObject();
        body.addProperty("code", code != null ? code.toUpperCase() : "");
        if (password != null && !password.isEmpty()) {
            body.addProperty("password", password);
        }
        body.addProperty("action", "join_room");
        return post(buildPath("join_room"), body);
    }

    public CompletableFuture<ApiResponse> leaveRoom(String code, String token, boolean isHost) {
        JsonObject body = new JsonObject();
        body.addProperty("code", code != null ? code : "");
        body.addProperty("token", token != null ? token : "");
        body.addProperty("isHost", isHost);
        body.addProperty("action", "leave_room");
        return postNoRetry(buildPath("leave_room"), body);
    }

    public CompletableFuture<ApiResponse> getRoomInfo(String code) {
        return get(buildGetPath("get_room_info", "code=" + (code != null ? code : "")));
    }

    public CompletableFuture<ApiResponse> listRooms(int page, int size) {
        return listRooms(page, size, null);
    }

    public CompletableFuture<ApiResponse> listRooms(int page, int size, String category) {
        String params = "page=" + page + "&size=" + size;
        if (category != null && !category.isEmpty()) {
            try {
                params += "&category=" + java.net.URLEncoder.encode(category, "UTF-8");
            } catch (java.io.UnsupportedEncodingException e) {
                params += "&category=" + category;
            }
        }
        return get(buildGetPath("list_rooms", params));
    }

    public CompletableFuture<ApiResponse> heartbeat(String code, String token, boolean isHost,
                                                     String natType, double load, JsonObject peerLatency, int seq, int overlayPort) {
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
        return postNoRetry(buildPath("heartbeat"), body);
    }

    public CompletableFuture<ApiResponse> sendSignal(String code, String token, boolean isHost, String type, JsonObject data) {
        return sendSignal(code, token, isHost, type, data, null);
    }

    public CompletableFuture<ApiResponse> sendSignal(String code, String token, boolean isHost, String type, JsonObject data, String to) {
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
        return post(buildPath("send_signal"), body);
    }

    public CompletableFuture<ApiResponse> pollSignals(String code, String token, boolean isHost, long since) {
        JsonObject body = new JsonObject();
        body.addProperty("code", code != null ? code : "");
        body.addProperty("token", token != null ? token : "");
        body.addProperty("isHost", isHost);
        body.addProperty("since", since);
        body.addProperty("action", "poll_signals");
        return postNoRetry(buildPath("poll_signals"), body);
    }

    //获取公网IP专用: 3s超时不重试, 超时用本地IPv6兜底, 不阻塞建房
    private static final int GET_PUBLIC_IP_TIMEOUT_MS = 3000;
    public CompletableFuture<ApiResponse> getPublicIp() {
        return getOnce(buildGetPath("get_ip", null), GET_PUBLIC_IP_TIMEOUT_MS);
    }

    public CompletableFuture<ApiResponse> checkPortReachable(String ip, int port) {
        JsonObject body = new JsonObject();
        body.addProperty("ip", ip != null ? ip : "");
        body.addProperty("port", port);
        body.addProperty("action", "check_port");
        return post(buildPath("check_port"), body);
    }

    public CompletableFuture<ApiResponse> registerRelayPeer(String clientId, String roomCode, String natType,
                                                              String mappedIp, int mappedPort, boolean relayEnabled) {
        JsonObject body = new JsonObject();
        body.addProperty("clientId", clientId);
        body.addProperty("roomCode", roomCode);
        body.addProperty("natType", natType);
        body.addProperty("mappedIp", mappedIp);
        body.addProperty("mappedPort", mappedPort);
        body.addProperty("relayEnabled", relayEnabled);
        body.addProperty("action", "relay_register");
        return post(buildPath("relay_register"), body);
    }

    public CompletableFuture<ApiResponse> fetchRelayCandidates() {
        return getOnce(buildGetPath("relay_candidates", null), 5000);
    }

    public CompletableFuture<ApiResponse> getBedrockInfo(String code) {
        JsonObject body = new JsonObject();
        body.addProperty("code", code != null ? code.toUpperCase() : "");
        body.addProperty("action", "get_bedrock");
        return postNoRetry(buildPath("get_bedrock"), body);
    }

    public CompletableFuture<ApiResponse> getCategories() {
        return get(buildGetPath("get_categories", null));
    }

    public CompletableFuture<ApiResponse> reportLinkReady(String code, String token, boolean isHost) {
        JsonObject body = new JsonObject();
        body.addProperty("code", code != null ? code : "");
        body.addProperty("token", token != null ? token : "");
        body.addProperty("isHost", isHost);
        body.addProperty("action", "report_ready");
        return post(buildPath("report_ready"), body);
    }

    public CompletableFuture<ApiResponse> pollTopology(String code, String token, boolean isHost, int generation) {
        JsonObject body = new JsonObject();
        body.addProperty("code", code != null ? code : "");
        body.addProperty("token", token != null ? token : "");
        body.addProperty("isHost", isHost);
        body.addProperty("generation", generation);
        body.addProperty("action", "poll_topology");
        return post(buildPath("poll_topology"), body);
    }

    private static final Set<String> TRANSIENT_ERRORS = Set.of(
            "NETWORK_ERROR", "CDN_ERROR", "RATE_LIMITED", "QUEUED", "SERVER_404", "SERVER_403"
    );

    private CompletableFuture<ApiResponse> postWithRetry(String path, JsonObject body, int maxRetries) {
        return postOnce(path, body).thenCompose(response -> {
            if (response.success || !TRANSIENT_ERRORS.contains(response.error) || maxRetries <= 0) {
                return CompletableFuture.completedFuture(response);
            }
            long delay = response.retryAfter > 0 ? response.retryAfter : DEFAULT_RETRY_AFTER_SECONDS;
            VoxLinkMod.LOGGER.warn("POST {}失败{}, {}s后重试(还剩{})", path, response.error, delay, maxRetries);
            CompletableFuture<ApiResponse> retry = new CompletableFuture<>();
            try {
                scheduler.schedule(() -> {
                    postWithRetry(path, body, maxRetries - 1).whenComplete((r, ex) -> {
                        if (ex != null) retry.completeExceptionally(ex);
                        else retry.complete(r);
                    });
                }, delay, java.util.concurrent.TimeUnit.SECONDS);
            } catch (java.util.concurrent.RejectedExecutionException e) {
                retry.complete(response);
            }
            return retry;
        });
    }

    private CompletableFuture<ApiResponse> getWithRetry(String path, int maxRetries) {
        return getOnce(path).thenCompose(response -> {
            if (response.success || !TRANSIENT_ERRORS.contains(response.error) || maxRetries <= 0) {
                return CompletableFuture.completedFuture(response);
            }
            long delay = response.retryAfter > 0 ? response.retryAfter : DEFAULT_RETRY_AFTER_SECONDS;
            VoxLinkMod.LOGGER.warn("GET {}失败{}, {}s后重试(还剩{})", path, response.error, delay, maxRetries);
            CompletableFuture<ApiResponse> retry = new CompletableFuture<>();
            try {
                scheduler.schedule(() -> {
                    getWithRetry(path, maxRetries - 1).whenComplete((r, ex) -> {
                        if (ex != null) retry.completeExceptionally(ex);
                        else retry.complete(r);
                    });
                }, delay, java.util.concurrent.TimeUnit.SECONDS);
            } catch (java.util.concurrent.RejectedExecutionException e) {
                retry.complete(response);
            }
            return retry;
        });
    }

    private CompletableFuture<ApiResponse> post(String path, JsonObject body) {
        return postWithRetry(path, body, 2);
    }

    private CompletableFuture<ApiResponse> postNoRetry(String path, JsonObject body) {
        return postOnce(path, body);
    }

    private CompletableFuture<ApiResponse> postOnce(String path, JsonObject body) {
        return postOnce(path, body, config.getConnectionTimeout());
    }

    private CompletableFuture<ApiResponse> postOnce(String path, JsonObject body, long timeoutMs) {
        String url = path.startsWith("http") ? path : getRpcBaseUrl() + path;
        VoxLinkMod.LOGGER.debug("[SignalingClient] POST {}", url);
        long requestStart = System.currentTimeMillis();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", "Mozilla/5.0 (Java) VoxLink/" + VoxLinkMod.MOD_VERSION)
                .header("X-VoxLink-Version", VoxLinkMod.MOD_VERSION)
                .timeout(Duration.ofMillis(timeoutMs))
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .orTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                .thenApply(response -> {
                    long elapsed = System.currentTimeMillis() - requestStart;
                    VoxLinkMod.LOGGER.debug("[SignalingClient] POST {} completed in {}ms, status={}", url, elapsed, response.statusCode());
                    int status = response.statusCode();
                    if (status == 200) {
                        return ApiResponse.fromHttpResponse(status, response.body());
                    }
                    if (status == 429) {
                        int retryAfter = parseRetryAfter(response);
                        return new ApiResponse(false, "RATE_LIMITED", "RATE_LIMITED", null, -1, retryAfter);
                    }
                    if (status == 502 || status == 503 || status == 504) {
                        ApiResponse parsed503 = ApiResponse.tryParseError(response.body());
                        if (parsed503 != null) return parsed503;
                        return new ApiResponse(false, "CDN_ERROR", "CDN_ERROR", null, -1, 5);
                    }
                    ApiResponse parsed = ApiResponse.tryParseError(response.body());
                    if (parsed != null) return parsed;
                    return new ApiResponse(false, "SERVER_" + status, "SERVER_" + status, null);
                })
                .exceptionally(e -> {
                    long elapsed = System.currentTimeMillis() - requestStart;
                    String msg = e == null ? null : e.getMessage();
                    Throwable cause = e != null ? e.getCause() : null;
                    VoxLinkMod.LOGGER.warn("[SignalingClient] POST {} {}ms后失败: {} (原因: {})",
                            url, elapsed, msg, cause != null ? cause.getClass().getSimpleName() : "none");
                    if (e instanceof java.util.concurrent.TimeoutException ||
                        (msg != null && (msg.contains("timed out") || msg.contains("Timeout") || msg.contains("timed_out")))) {
                        return new ApiResponse(false, "NETWORK_ERROR", "CONNECTION_TIMEOUT", null);
                    }
                    if (msg != null && (msg.contains("refused") || msg.contains("Connection reset"))) {
                        return new ApiResponse(false, "NETWORK_ERROR", "CONNECTION_REFUSED", null);
                    }
                    if (msg != null && msg.contains("SSL")) {
                        return new ApiResponse(false, "NETWORK_ERROR", "SSL_ERROR", null);
                    }
                    if (msg != null && (msg.contains("UnknownHost") || msg.contains("nodename"))) {
                        return new ApiResponse(false, "NETWORK_ERROR", "UNKNOWN_HOST", null);
                    }
                    return new ApiResponse(false, "NETWORK_ERROR", "NETWORK_ERROR", null);
                });
    }

    private CompletableFuture<ApiResponse> get(String path) {
        return getWithRetry(path, 2);
    }

    private CompletableFuture<ApiResponse> getOnce(String path) {
        return getOnce(path, config.getConnectionTimeout());
    }

    private CompletableFuture<ApiResponse> getOnce(String path, long timeoutMs) {
        String url = path.startsWith("http") ? path : getRpcBaseUrl() + path;
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

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .orTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                .thenApply(response -> {
                    long elapsed = System.currentTimeMillis() - requestStart;
                    VoxLinkMod.LOGGER.debug("[SignalingClient] GET {} completed in {}ms, status={}", url, elapsed, response.statusCode());
                    int status = response.statusCode();
                    if (status == 200) {
                        return ApiResponse.fromHttpResponse(status, response.body());
                    }
                    if (status == 429) {
                        int retryAfter = parseRetryAfter(response);
                        return new ApiResponse(false, "RATE_LIMITED", "RATE_LIMITED", null, -1, retryAfter);
                    }
                    if (status == 502 || status == 503 || status == 504) {
                        ApiResponse parsed503 = ApiResponse.tryParseError(response.body());
                        if (parsed503 != null) return parsed503;
                        return new ApiResponse(false, "CDN_ERROR", "CDN_ERROR", null, -1, 5);
                    }
                    ApiResponse parsed = ApiResponse.tryParseError(response.body());
                    if (parsed != null) return parsed;
                    return new ApiResponse(false, "SERVER_" + status, "SERVER_" + status, null);
                })
                .exceptionally(e -> {
                    long elapsed = System.currentTimeMillis() - requestStart;
                    String msg = e == null ? null : e.getMessage();
                    Throwable cause = e != null ? e.getCause() : null;
                    VoxLinkMod.LOGGER.warn("[SignalingClient] GET {} {}ms后失败: {} (原因: {})",
                            url, elapsed, msg, cause != null ? cause.getClass().getSimpleName() : "none");
                    if (e instanceof java.util.concurrent.TimeoutException ||
                        (msg != null && (msg.contains("timed out") || msg.contains("Timeout")))) {
                        return new ApiResponse(false, "NETWORK_ERROR", "CONNECTION_TIMEOUT", null);
                    }
                    if (msg != null && (msg.contains("refused") || msg.contains("Connection reset"))) {
                        return new ApiResponse(false, "NETWORK_ERROR", "CONNECTION_REFUSED", null);
                    }
                    if (msg != null && msg.contains("SSL")) {
                        return new ApiResponse(false, "NETWORK_ERROR", "SSL_ERROR", null);
                    }
                    if (msg != null && (msg.contains("UnknownHost") || msg.contains("nodename"))) {
                        return new ApiResponse(false, "NETWORK_ERROR", "UNKNOWN_HOST", null);
                    }
                    return new ApiResponse(false, "NETWORK_ERROR", "NETWORK_ERROR", null);
                });
    }

    private static int parseRetryAfter(HttpResponse<String> response) {
        String retryAfterStr = response.headers().firstValue("Retry-After").orElse(null);
        if (retryAfterStr != null) {
            try {
                return Math.max(Integer.parseInt(retryAfterStr), 1);
            } catch (NumberFormatException ignored) {}
        }
        return 0;
    }

    public void shutdown() {
        executor.shutdownNow();
        scheduler.shutdownNow();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                VoxLinkMod.LOGGER.warn("signaling executor没停干净");
            }
            if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                VoxLinkMod.LOGGER.warn("signaling scheduler没停干净");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            httpClient.close();
        } catch (Exception e) {
            VoxLinkMod.LOGGER.warn("HTTP client关不掉: {}", e.getMessage());
        }
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

        public static ApiResponse fromHttpResponse(int statusCode, String body) {
            try {
                JsonObject json = GSON.fromJson(body, JsonObject.class);
                if (json == null) {
                    return new ApiResponse(false, "PARSE_ERROR", "空响应", null);
                }
                boolean success = json.has("success") && json.get("success").getAsBoolean();
                String error = json.has("error") ? json.get("error").getAsString() : null;
                String message = json.has("message") ? json.get("message").getAsString() : null;
                JsonObject data = json.has("data") ? json.getAsJsonObject("data") : null;
                int position = json.has("position") ? json.get("position").getAsInt() : -1;
                int retryAfter = json.has("retryAfter") ? json.get("retryAfter").getAsInt() : 0;
                return new ApiResponse(success, error, message, data, position, retryAfter);
            } catch (Exception e) {
                String bodyPreview = body != null ? body.substring(0, Math.min(body.length(), 500)) : "null";
                VoxLinkMod.LOGGER.error("API响应解析失败 (status {}): {}", statusCode, bodyPreview);
                VoxLinkMod.LOGGER.error("解析错误: {}", e.getMessage());
                return new ApiResponse(false, "PARSE_ERROR", "响应解析失败", null);
            }
        }

        public static ApiResponse tryParseError(String body) {
            if (body == null || body.isEmpty()) return null;
            try {
                JsonObject json = GSON.fromJson(body, JsonObject.class);
                if (json == null) return null;
                String error = json.has("error") ? json.get("error").getAsString() : null;
                String message = json.has("message") ? json.get("message").getAsString() : null;
                int position = json.has("position") ? json.get("position").getAsInt() : -1;
                int retryAfter = json.has("retryAfter") ? json.get("retryAfter").getAsInt() : 0;
                if (error != null) {
                    return new ApiResponse(false, error, message, null, position, retryAfter);
                }
            } catch (Exception ignored) {}
            return null;
        }
    }
}