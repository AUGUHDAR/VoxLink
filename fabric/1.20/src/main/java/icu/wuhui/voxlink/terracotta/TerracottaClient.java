package icu.wuhui.voxlink.terracotta;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

//Terracotta HTTP API 客户端
public final class TerracottaClient {
    private static final Logger LOGGER = LoggerFactory.getLogger("voxlink-terracotta");
    private static final int CONNECT_TIMEOUT_SEC = 5;
    private static final int REQUEST_TIMEOUT_SEC = 8;
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SEC))
        .build();

    private TerracottaClient() {}

    //GET 请求
    public static CompletableFuture<String> get(int port, String path) {
        String url = "http://127.0.0.1:" + port + path;
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SEC))
            .GET()
            .build();

        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
            .thenApply(resp -> {
                if (resp.statusCode() != 200) {
                    throw new TerracottaHttpException("HTTP " + resp.statusCode() + ": " + url, resp.statusCode());
                }
                return resp.body();
            });
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

    //创建房间 (host scanning)
    public static CompletableFuture<Void> startHost(int port, String playerName) {
        String path = "/state/scanning";
        if (playerName != null && !playerName.isEmpty()) {
            path += "?player=" + java.net.URLEncoder.encode(playerName, java.nio.charset.StandardCharsets.UTF_8);
        }
        return get(port, path).thenRun(() -> {});
    }

    //加入房间 (guest)
    //返回 true=HTTP成功(进入guest状态), false=HTTP失败(房码错误/房间不存在等)
    public static CompletableFuture<Boolean> joinRoom(int port, String roomCode, String playerName) {
        String path = "/state/guesting?room=" + java.net.URLEncoder.encode(roomCode, java.nio.charset.StandardCharsets.UTF_8);
        if (playerName != null && !playerName.isEmpty()) {
            path += "&player=" + java.net.URLEncoder.encode(playerName, java.nio.charset.StandardCharsets.UTF_8);
        }
        return get(port, path)
            .thenApply(resp -> true)
            .exceptionally(e -> {
                //记录失败原因, 便于排查
                LOGGER.warn("加入陶瓦房间失败: {}", e.getMessage());
                return false;
            });
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

    //带状态码的异常, 便于调用方区分错误类型
    public static final class TerracottaHttpException extends RuntimeException {
        private final int statusCode;
        public TerracottaHttpException(String message, int statusCode) {
            super(message);
            this.statusCode = statusCode;
        }
        public int getStatusCode() { return statusCode; }
    }
}
