package icu.wuhui.voxlink.uuid;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpClient.Version;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UUIDPolicyManager {
   private static final Logger LOGGER = LoggerFactory.getLogger("voxlink-uuid");
   private static final Gson GSON = new Gson();
   private static final int UUID_CACHE_MAX_SIZE = 1000;
   private static final int HEX_UUID_LENGTH = 32;
   private static final Duration HTTP_CONNECT_TIMEOUT = Duration.ofSeconds(3L);
   private static final Duration HTTP_REQUEST_TIMEOUT = Duration.ofSeconds(3L);
   private static final Map<String, UUID> uuidCache = Collections.synchronizedMap(new LinkedHashMap<String, UUID>(128, 0.75F, true) {
      @Override
      protected boolean removeEldestEntry(Entry<String, UUID> eldest) {
         return this.size() > 1000;
      }
   });
   private static final Map<String, String> policyMap = new ConcurrentHashMap<>();
   private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder().connectTimeout(HTTP_CONNECT_TIMEOUT).version(Version.HTTP_1_1).build();
   private static final Map<String, Long> lastFetchTime = new ConcurrentHashMap<>();
   private static final long FETCH_COOLDOWN_MS = 60000L;

   public static UUID hookEntry(String playerName) {
      String policy = policyMap.getOrDefault(playerName, policyMap.getOrDefault("*", null));
      if (policy == null) {
         return null;
      }

      if ("offline".equals(policy)) {
         return null;
      }

      if (policy.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
         try {
            return UUID.fromString(policy);
         } catch (IllegalArgumentException e) {
            return null;
         }
      } else if ("online".equals(policy)) {
         UUID cached = uuidCache.get(playerName);
         return cached != null ? cached : fetchOfficialUUIDSync(playerName);
      } else {
         return null;
      }
   }

   private static UUID fetchOfficialUUIDSync(String playerName) {
      long now = System.currentTimeMillis();
      Long last = lastFetchTime.get(playerName);
      if (last != null && now - last < 60000L) {
         return uuidCache.get(playerName);
      }

      lastFetchTime.put(playerName, now);

      try {
         HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.mojang.com/users/profiles/minecraft/" + URLEncoder.encode(playerName, StandardCharsets.UTF_8)))
            .timeout(HTTP_REQUEST_TIMEOUT)
            .GET()
            .build();
         HttpResponse<String> response = HTTP_CLIENT.send(request, BodyHandlers.ofString());
         if (response.statusCode() == 200) {
            try {
               Type type = (new TypeToken<Map<String, String>>() {}).getType();
               Map<String, String> data = (Map<String, String>)GSON.fromJson(response.body(), type);
               String id = data.get("id");
               if (id != null && id.length() == 32) {
                  String formatted = id.substring(0, 8)
                     + "-"
                     + id.substring(8, 12)
                     + "-"
                     + id.substring(12, 16)
                     + "-"
                     + id.substring(16, 20)
                     + "-"
                     + id.substring(20);
                  UUID uuid = UUID.fromString(formatted);
                  uuidCache.put(playerName, uuid);
                  LOGGER.debug("Cached official UUID for {}", playerName);
                  return uuid;
               }
            } catch (Exception e) {
               LOGGER.debug("Mojang response parse failed ({}): {}", playerName, e.getMessage());
            }
         }
      } catch (Exception e) {
         LOGGER.debug("Failed to get official UUID for {}: {}", playerName, e.getMessage());
      }

      return null;
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
