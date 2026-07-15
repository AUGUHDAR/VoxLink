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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class UUIDPolicyManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("voxlink-uuid");
    private static final Gson GSON = new Gson();
    private static final int UUID_CACHE_MAX_SIZE = 1000;
    private static final int HEX_UUID_LENGTH = 32;
    private static final Duration HTTP_CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration HTTP_REQUEST_TIMEOUT = Duration.ofSeconds(3);
    private static final Map<String, UUID> uuidCache = Collections.synchronizedMap(
        new LinkedHashMap<String, UUID>(128, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, UUID> eldest) {
                return size() > UUID_CACHE_MAX_SIZE;
            }
        });
    private static final Map<String, String> policyMap = new ConcurrentHashMap<>();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(HTTP_CONNECT_TIMEOUT)
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
                    .timeout(HTTP_REQUEST_TIMEOUT)
                    .GET()
                    .build();

            HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> {
                        if (response.statusCode() == 200) {
                            try {
                                Type type = new TypeToken<Map<String, String>>() {}.getType();
                                Map<String, String> data = GSON.fromJson(response.body(), type);
                                String id = data.get("id");
                                if (id != null && id.length() == HEX_UUID_LENGTH) {
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
        policyMap.put(playerName, policy);
    }

    public static void setDefaultPolicy(String policy) {
        policyMap.put("*", policy);
    }

    public static void removePolicy(String playerName) {
        policyMap.remove(playerName);
    }
}
