package icu.wuhui.voxlink.terracotta;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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

//debounce 陶瓦公共节点列表 对齐HMCL TerracottaNodeList
//让Terracotta知道用哪些中继节点 中国大陆只留CN节点 避免延迟高
public final class TerracottaNodeList {
    private static final Logger LOGGER = LoggerFactory.getLogger("voxlink-terracotta");
    private static final String NODES_URL = "https://terracotta.glavo.site/nodes";
    private static final long CACHE_TTL_MS = TimeUnit.HOURS.toMillis(1);
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    //debounce 双重缓存 volatile保证可见性
    private static volatile List<URI> cachedNodes = null;
    private static volatile long cacheTime = 0;
    private static volatile boolean fetchInFlight = false;

    private TerracottaNodeList() {}

    //判断是否中国大陆 对齐HMCL LocaleUtils.IS_CHINA_MAINLAND
    public static boolean isChinaMainland() {
        //debounce 用时区+国家码双判断 HMCL用LocaleUtils更严格 VoxLink简化
        String tz = java.util.TimeZone.getDefault().getID();
        String country = java.util.Locale.getDefault().getCountry();
        return "Asia/Shanghai".equals(tz) || "CN".equalsIgnoreCase(country);
    }

    //拉取节点列表 失败返回空列表不阻塞主流程
    public static CompletableFuture<List<URI>> fetch() {
        //debounce 缓存有效直接返回
        if (cachedNodes != null && (System.currentTimeMillis() - cacheTime) < CACHE_TTL_MS) {
            return CompletableFuture.completedFuture(cachedNodes);
        }
        //debounce 防止并发重复拉取
        synchronized (TerracottaNodeList.class) {
            if (cachedNodes != null && (System.currentTimeMillis() - cacheTime) < CACHE_TTL_MS) {
                return CompletableFuture.completedFuture(cachedNodes);
            }
            if (fetchInFlight) {
                //debounce 已有拉取在等 直接返回当前缓存(可能为空)
                return CompletableFuture.completedFuture(cachedNodes != null ? cachedNodes : new ArrayList<>());
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
                List<URI> nodes = parseNodes(resp.body());
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

    //为中国大陆用户拉取节点列表(已过滤)
    public static CompletableFuture<List<URI>> fetchForChina() {
        return fetch().thenApply(nodes -> {
            if (!isChinaMainland()) return nodes;
            //debounce 中国大陆只留CN节点 host含.cn或.cn.结尾
            List<URI> filtered = new ArrayList<>();
            for (URI node : nodes) {
                String host = node.getHost();
                if (host == null) continue;
                String lower = host.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".cn") || lower.contains(".cn.") || lower.startsWith("cn.") || lower.equals("cn")) {
                    filtered.add(node);
                }
            }
            LOGGER.info("陶瓦CN节点过滤: {} -> {}", nodes.size(), filtered.size());
            return filtered.isEmpty() ? nodes : filtered;
        });
    }

    //解析节点列表 JSON 支持数组格式
    private static List<URI> parseNodes(String body) {
        List<URI> nodes = new ArrayList<>();
        if (body == null || body.isBlank()) return nodes;
        try {
            JsonElement elem = JsonParser.parseString(body);
            if (elem.isJsonArray()) {
                JsonArray arr = elem.getAsJsonArray();
                for (JsonElement e : arr) {
                    try {
                        String s = e.isJsonPrimitive() ? e.getAsString() : e.toString();
                        //debounce 节点可能是 host:port 或完整URL 统一包装成URI
                        URI uri;
                        if (s.contains("://")) {
                            uri = URI.create(s);
                        } else {
                            uri = URI.create("terracotta://" + s);
                        }
                        nodes.add(uri);
                    } catch (Exception ignored) {}
                }
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
