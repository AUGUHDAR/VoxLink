package icu.wuhui.voxlink.uuid;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class UUIDPolicyManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("voxlink-uuid");
    private static final Gson GSON = new Gson();
    private static final Map<String, UUID> uuidCache = new ConcurrentHashMap<>();
    private static final Map<String, String> policyMap = new ConcurrentHashMap<>();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    public static UUID hookEntry(String playerName) {
        String policy = policyMap.getOrDefault(playerName, policyMap.getOrDefault("*", null));
        if (policy == null) return null;

        if ("offline".equals(policy)) return null;

        if (policy.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            try {
                return UUID.fromString(policy);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        if ("online".equals(policy)) {
            UUID cached = uuidCache.get(playerName);
            if (cached != null) return cached;
            fetchOfficialUUIDAsync(playerName);
            return null;
        }

        return null;
    }

    private static void fetchOfficialUUIDAsync(String playerName) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.mojang.com/users/profiles/minecraft/" + java.net.URLEncoder.encode(playerName, StandardCharsets.UTF_8)))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();

            HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> {
                        if (response.statusCode() == 200) {
                            try {
                                Type type = new TypeToken<Map<String, String>>() {}.getType();
                                Map<String, String> data = GSON.fromJson(response.body(), type);
                                String id = data.get("id");
                                if (id != null && id.length() == 32) {
                                    String formatted = id.substring(0, 8) + "-" + id.substring(8, 12) + "-" +
                                            id.substring(12, 16) + "-" + id.substring(16, 20) + "-" + id.substring(20);
                                    UUID uuid = UUID.fromString(formatted);
                                    uuidCache.put(playerName, uuid);
                                    LOGGER.debug("缓存了{}的官方UUID", playerName);
                                }
                            } catch (Exception e) {
                                LOGGER.debug("Mojang响应解析失败({}): {}", playerName, e.getMessage());
                            }
                        }
                    })
                    .exceptionally(e -> {
                        LOGGER.debug("获取{}的官方UUID失败: {}", playerName, e.getMessage());
                        return null;
                    });
        } catch (Exception e) {
            LOGGER.debug("启动UUID查询失败 {}: {}", playerName, e.getMessage());
        }
    }

    public static void setPolicy(String playerName, String policy) {
        if (uuidCache.size() > 1000) {
            java.util.Iterator<String> it = uuidCache.keySet().iterator();
            int toRemove = uuidCache.size() / 2;
            for (int i = 0; i < toRemove && it.hasNext(); i++) {
                it.next();
                it.remove();
            }
        }
        policyMap.put(playerName, policy);
    }

    public static void setDefaultPolicy(String policy) {
        policyMap.put("*", policy);
    }

    public static void removePolicy(String playerName) {
        policyMap.remove(playerName);
    }
}
