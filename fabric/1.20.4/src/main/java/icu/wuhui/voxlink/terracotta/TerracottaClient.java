package icu.wuhui.voxlink.terracotta;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

//Terracotta HTTP API 客户端
public final class TerracottaClient {
    private static final Logger LOGGER = LoggerFactory.getLogger("voxlink-terracotta");
    private static final int CONNECT_TIMEOUT_SEC = 5;
    private static final int REQUEST_TIMEOUT_SEC = 8;
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SEC))
        .build();
    //debounce HTTP专用线程 避免重试sleep阻塞调用方
    private static final ExecutorService HTTP_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "terracotta-http");
        t.setDaemon(true);
        return t;
    });
    //debounce 5xx退避重试 10/50/200ms 共3次 对齐HMCL retry(5)简化
    private static final int HTTP_MAX_RETRIES = 3;
    private static final long[] HTTP_BACKOFF_MS = {10, 50, 200};

    private TerracottaClient() {}

    //GET 请求 自动重试5xx和IO异常 4xx直接抛
    public static CompletableFuture<String> get(int port, String path) {
        return CompletableFuture.supplyAsync(() -> {
            String url = "http://127.0.0.1:" + port + path;
            TerracottaHttpException lastHttpEx = null;
            IOException lastIoEx = null;
            for (int attempt = 0; attempt < HTTP_MAX_RETRIES; attempt++) {
                try {
                    return doRequest(url);
                } catch (TerracottaHttpException e) {
                    lastHttpEx = e;
                    //debounce 4xx不重试 404房间不存在/400参数错误
                    if (e.statusCode >= 400 && e.statusCode < 500) {
                        throw e;
                    }
                    //debounce 5xx退避重试
                    if (attempt < HTTP_MAX_RETRIES - 1) {
                        LOGGER.debug("陶瓦HTTP {}返回{} 退避{}ms后重试", path, e.statusCode, HTTP_BACKOFF_MS[attempt]);
                        try {
                            Thread.sleep(HTTP_BACKOFF_MS[attempt]);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw e;
                        }
                    }
                } catch (IOException e) {
                    lastIoEx = e;
                    if (attempt < HTTP_MAX_RETRIES - 1) {
                        LOGGER.debug("陶瓦HTTP {} IO异常 {} 退避{}ms后重试", path, e.getMessage(), HTTP_BACKOFF_MS[attempt]);
                        try {
                            Thread.sleep(HTTP_BACKOFF_MS[attempt]);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException(e);
                        }
                    }
                }
            }
            if (lastHttpEx != null) throw lastHttpEx;
            throw new RuntimeException("陶瓦HTTP请求失败 " + HTTP_MAX_RETRIES + "次: " + path, lastIoEx);
        }, HTTP_EXECUTOR);
    }

    //实际单次请求
    private static String doRequest(String url) throws IOException, TerracottaHttpException {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SEC))
            .GET()
            .build();
        try {
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new TerracottaHttpException("HTTP " + resp.statusCode() + ": " + url, resp.statusCode(), resp.body());
            }
            return resp.body();
        } catch (java.net.http.HttpTimeoutException e) {
            throw new IOException("请求超时: " + url, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("请求被中断: " + url, e);
        }
    }

    //获取元数据
    public static CompletableFuture<JsonObject> getMeta(int port) {
        return get(port, "/meta").thenApply(s -> JsonParser.parseString(s).getAsJsonObject());
    }

    //获取状态
    public static CompletableFuture<JsonObject> getState(int port) {
        return get(port, "/state").thenApply(s -> JsonParser.parseString(s).getAsJsonObject());
    }

    //重置为等待状态
    public static CompletableFuture<Void> setIdle(int port) {
        return get(port, "/state/idle").thenRun(() -> {});
    }

    //创建房间 (host scanning) 带节点列表参数 对齐HMCL
    public static CompletableFuture<Void> startHost(int port, String playerName, List<java.net.URI> publicNodes) {
        StringBuilder path = new StringBuilder("/state/scanning");
        boolean first = true;
        if (playerName != null && !playerName.isEmpty()) {
            path.append("?player=").append(java.net.URLEncoder.encode(playerName, java.nio.charset.StandardCharsets.UTF_8));
            first = false;
        }
        if (publicNodes != null) {
            for (java.net.URI node : publicNodes) {
                path.append(first ? "?" : "&");
                first = false;
                path.append("public_nodes=").append(java.net.URLEncoder.encode(node.toString(), java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        return get(port, path.toString()).thenRun(() -> {});
    }

    //debounce 兼容旧调用 不带节点列表
    public static CompletableFuture<Void> startHost(int port, String playerName) {
        return startHost(port, playerName, null);
    }

    //加入房间 (guest) 带节点列表参数 对齐HMCL
    public static CompletableFuture<Boolean> joinRoom(int port, String roomCode, String playerName, List<java.net.URI> publicNodes) {
        StringBuilder path = new StringBuilder("/state/guesting?room=");
        path.append(java.net.URLEncoder.encode(roomCode, java.nio.charset.StandardCharsets.UTF_8));
        if (playerName != null && !playerName.isEmpty()) {
            path.append("&player=").append(java.net.URLEncoder.encode(playerName, java.nio.charset.StandardCharsets.UTF_8));
        }
        if (publicNodes != null) {
            for (java.net.URI node : publicNodes) {
                path.append("&public_nodes=").append(java.net.URLEncoder.encode(node.toString(), java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        //debounce 让TerracottaHttpException自然传播 保留HTTP状态码 503/404可区分
        return get(port, path.toString()).thenApply(resp -> true);
    }

    //debounce 兼容旧调用 不带节点列表
    public static CompletableFuture<Boolean> joinRoom(int port, String roomCode, String playerName) {
        return joinRoom(port, roomCode, playerName, null);
    }

    //关闭 Terracotta
    public static CompletableFuture<Void> panic(int port) {
        return get(port, "/panic?peaceful=true").thenRun(() -> {});
    }

    //解析状态字符串
    public static String getStateName(JsonObject state) {
        if (state == null) return "unknown";
        return state.has("state") && !state.get("state").isJsonNull()
            ? state.get("state").getAsString() : "unknown";
    }

    //获取房间码 (host-ok 或 guest-* 状态)
    public static String getRoomCode(JsonObject state) {
        if (state == null) return null;
        return state.has("room") && !state.get("room").isJsonNull()
            ? state.get("room").getAsString() : null;
    }

    //获取连接地址 (guest-ok 状态)
    public static String getConnectUrl(JsonObject state) {
        if (state == null) return null;
        return state.has("url") && !state.get("url").isJsonNull()
            ? state.get("url").getAsString() : null;
    }

    //获取连接难度 (guest-starting 状态)
    public static String getDifficulty(JsonObject state) {
        if (state == null) return "UNKNOWN";
        return state.has("difficulty") && !state.get("difficulty").isJsonNull()
            ? state.get("difficulty").getAsString() : "UNKNOWN";
    }

    //带状态码+响应body的异常 便于调用方区分错误类型
    public static final class TerracottaHttpException extends RuntimeException {
        private final int statusCode;
        private final String responseBody;
        public TerracottaHttpException(String message, int statusCode) {
            this(message, statusCode, "");
        }
        public TerracottaHttpException(String message, int statusCode, String responseBody) {
            super(message);
            this.statusCode = statusCode;
            this.responseBody = responseBody != null ? responseBody : "";
        }
        public int getStatusCode() { return statusCode; }
        public String getResponseBody() { return responseBody; }
        //debounce 完整错误详情 透传给UI
        public String getErrorDetail() {
            return "HTTP " + statusCode + (responseBody != null && !responseBody.isEmpty() ? ": " + responseBody : "");
        }
    }
}
