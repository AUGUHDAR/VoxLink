package icu.wuhui.voxlink.terracotta;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

//debounce 陶瓦公共节点列表 中国大陆只留CN节点避免延迟高
public final class TerracottaNodeList {
    private static final Logger LOGGER = LoggerFactory.getLogger("voxlink-terracotta");
    private static final String NODES_URL = "https://terracotta.glavo.site/nodes";
    private static final long CACHE_TTL_MS = TimeUnit.HOURS.toMillis(1);
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    //debounce 双重缓存 volatile保证可见性 缓存NodeInfo含region供fetchForChina过滤
    private static volatile List<NodeInfo> cachedNodes = null;
    private static volatile long cacheTime = 0;
    private static volatile boolean fetchInFlight = false;

    //debounce 节点信息 region为CN表示中国节点
    private static final class NodeInfo {
        final URI uri;
        final String region;
        NodeInfo(URI uri, String region) { this.uri = uri; this.region = region; }
    }

    private TerracottaNodeList() {}

    //判断是否中国大陆
    public static boolean isChinaMainland() {
        //debounce 用时区+国家码双判断 覆盖全部中国大陆时区
        String tz = java.util.TimeZone.getDefault().getID();
        String country = java.util.Locale.getDefault().getCountry();
        return "Asia/Shanghai".equals(tz) || "Asia/Urumqi".equals(tz)
            || "Asia/Chongqing".equals(tz) || "Asia/Harbin".equals(tz)
            || "CN".equalsIgnoreCase(country);
    }

    //debounce 统一拉取节点列表(含region) 失败返回空列表不阻塞主流程
    private static CompletableFuture<List<NodeInfo>> fetchNodeInfos() {
        List<NodeInfo> cached = cachedNodes;
        if (cached != null && (System.currentTimeMillis() - cacheTime) < CACHE_TTL_MS) {
            return CompletableFuture.completedFuture(cached);
        }
        synchronized (TerracottaNodeList.class) {
            cached = cachedNodes;
            if (cached != null && (System.currentTimeMillis() - cacheTime) < CACHE_TTL_MS) {
                return CompletableFuture.completedFuture(cached);
            }
            if (fetchInFlight) {
                //debounce 已有拉取在等 直接返回当前缓存(可能为空)
                return CompletableFuture.completedFuture(cached != null ? cached : new ArrayList<>());
            }
            fetchInFlight = true;
        }

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(NODES_URL))
            .timeout(Duration.ofSeconds(8))
            .GET()
            .build();

        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
            .thenApply(resp -> {
                List<NodeInfo> nodes = parseNodes(resp.body());
                cachedNodes = nodes;
                cacheTime = System.currentTimeMillis();
                LOGGER.info("陶瓦节点列表拉取成功: {} 个节点", nodes.size());
                return nodes;
            })
            .exceptionally(e -> {
                Throwable cause = (e instanceof java.util.concurrent.CompletionException && e.getCause() != null)
                    ? e.getCause() : e;
                LOGGER.warn("陶瓦节点列表拉取失败, Terracotta将用默认节点: {}", cause.getMessage());
                //debounce 失败保留旧缓存(如有) 否则空列表
                if (cachedNodes == null) cachedNodes = new ArrayList<>();
                return cachedNodes;
            })
            .whenComplete((r, e) -> fetchInFlight = false);
    }

    //拉取全部节点列表 失败返回空列表不阻塞主流程
    public static CompletableFuture<List<URI>> fetch() {
        return fetchNodeInfos().thenApply(TerracottaNodeList::toUris);
    }

    //为中国大陆用户拉取节点列表(已按region过滤)
    public static CompletableFuture<List<URI>> fetchForChina() {
        return fetchNodeInfos().thenApply(nodes -> {
            if (!isChinaMainland()) {
                //debounce 非中国大陆 返回全部节点
                return toUris(nodes);
            }
            //debounce 中国大陆保留region为空(全球节点)或region==CN
            List<URI> filtered = new ArrayList<>();
            for (NodeInfo ni : nodes) {
                if (ni.region == null || ni.region.isBlank() || "CN".equalsIgnoreCase(ni.region)) {
                    filtered.add(ni.uri);
                }
            }
            LOGGER.info("陶瓦CN节点过滤: {} -> {}", nodes.size(), filtered.size());
            //debounce 全过滤空时回退全部 避免无节点可用
            return filtered.isEmpty() ? toUris(nodes) : filtered;
        });
    }

    private static List<URI> toUris(List<NodeInfo> nodes) {
        List<URI> uris = new ArrayList<>(nodes.size());
        for (NodeInfo ni : nodes) uris.add(ni.uri);
        return uris;
    }

    //解析节点列表JSON [{url, region}]对象数组
    private static List<NodeInfo> parseNodes(String body) {
        List<NodeInfo> nodes = new ArrayList<>();
        if (body == null || body.isBlank()) return nodes;
        try {
            JsonElement elem = JsonParser.parseString(body);
            if (!elem.isJsonArray()) return nodes;
            JsonArray arr = elem.getAsJsonArray();
            for (JsonElement e : arr) {
                try {
                    if (!e.isJsonObject()) continue;
                    JsonObject obj = e.getAsJsonObject();
                    String url = obj.has("url") && !obj.get("url").isJsonNull()
                        ? obj.get("url").getAsString() : null;
                    if (url == null || url.isBlank()) continue;
                    String region = obj.has("region") && !obj.get("region").isJsonNull()
                        ? obj.get("region").getAsString() : null;
                    URI uri;
                    try {
                        uri = URI.create(url);
                    } catch (Exception ex) {
                        LOGGER.warn("陶瓦节点URL无效 跳过: {}", url);
                        continue;
                    }
                    nodes.add(new NodeInfo(uri, region));
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            LOGGER.warn("陶瓦节点列表解析失败: {}", e.getMessage());
        }
        return nodes;
    }

    //清缓存 用于测试或强制刷新
    public static void clearCache() {
        cachedNodes = null;
        cacheTime = 0;
    }
}
