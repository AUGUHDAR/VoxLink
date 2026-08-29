package icu.wuhui.voxlink.terracotta;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TerracottaNodeList {
   private static final Logger LOGGER = LoggerFactory.getLogger("voxlink-terracotta");
   private static final String NODES_URL = "https://terracotta.glavo.site/nodes";
   private static final long CACHE_TTL_MS = TimeUnit.HOURS.toMillis(1L);
   private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5L)).build();
   private static volatile List<TerracottaNodeList.NodeInfo> cachedNodes = null;
   private static volatile long cacheTime = 0L;
   private static volatile boolean fetchInFlight = false;
   private static volatile CompletableFuture<List<TerracottaNodeList.NodeInfo>> inFlightFuture = null;

   private TerracottaNodeList() {
   }

   public static boolean isChinaMainland() {
      String tz = TimeZone.getDefault().getID();
      String country = Locale.getDefault().getCountry();
      return "Asia/Shanghai".equals(tz)
         || "Asia/Urumqi".equals(tz)
         || "Asia/Chongqing".equals(tz)
         || "Asia/Harbin".equals(tz)
         || "CN".equalsIgnoreCase(country);
   }

   private static CompletableFuture<List<TerracottaNodeList.NodeInfo>> fetchNodeInfos() {
      List<TerracottaNodeList.NodeInfo> cached = cachedNodes;
      if (cached != null && System.currentTimeMillis() - cacheTime < CACHE_TTL_MS) {
         return CompletableFuture.completedFuture(cached);
      }

      synchronized (TerracottaNodeList.class) {
         cached = cachedNodes;
         if (cached != null && System.currentTimeMillis() - cacheTime < CACHE_TTL_MS) {
            return CompletableFuture.completedFuture(cached);
         }

         if (fetchInFlight) {
            return inFlightFuture != null ? inFlightFuture : CompletableFuture.completedFuture(cached != null ? cached : new ArrayList<>());
         }

         fetchInFlight = true;
      }

      HttpRequest req = HttpRequest.newBuilder().uri(URI.create("https://terracotta.glavo.site/nodes")).timeout(Duration.ofSeconds(8L)).GET().build();
      CompletableFuture<List<TerracottaNodeList.NodeInfo>> future = HTTP.sendAsync(req, BodyHandlers.ofString()).thenApply(resp -> {
         List<TerracottaNodeList.NodeInfo> nodes = parseNodes(resp.body());
         cachedNodes = nodes;
         cacheTime = System.currentTimeMillis();
         LOGGER.info("Terracotta node list fetched: {} nodes", nodes.size());
         return nodes;
      }).exceptionally(e -> {
         Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
         LOGGER.warn("Terracotta node list fetch failed, using default nodes: {}", cause.getMessage());
         if (cachedNodes == null) {
            cachedNodes = new ArrayList<>();
         }

         return cachedNodes;
      }).whenComplete((r, e) -> {
         fetchInFlight = false;
         inFlightFuture = null;
      });
      inFlightFuture = future;
      return future;
   }

   public static CompletableFuture<List<URI>> fetch() {
      return fetchNodeInfos().thenApply(TerracottaNodeList::toUris);
   }

   public static CompletableFuture<List<URI>> fetchForChina() {
      return fetchNodeInfos().thenApply(nodes -> {
         if (!isChinaMainland()) {
            return toUris((List<TerracottaNodeList.NodeInfo>)nodes);
         }

         List<URI> filtered = new ArrayList<>();

         for (TerracottaNodeList.NodeInfo ni : nodes) {
            if (ni.region == null || ni.region.isBlank() || "CN".equalsIgnoreCase(ni.region)) {
               filtered.add(ni.uri);
            }
         }

         LOGGER.info("Terracotta CN node filter: {} -> {}", nodes.size(), filtered.size());
         return filtered.isEmpty() ? toUris((List<TerracottaNodeList.NodeInfo>)nodes) : filtered;
      });
   }

   private static List<URI> toUris(List<TerracottaNodeList.NodeInfo> nodes) {
      List<URI> uris = new ArrayList<>(nodes.size());

      for (TerracottaNodeList.NodeInfo ni : nodes) {
         uris.add(ni.uri);
      }

      return uris;
   }

   private static List<TerracottaNodeList.NodeInfo> parseNodes(String body) {
      List<TerracottaNodeList.NodeInfo> nodes = new ArrayList<>();
      if (body != null && !body.isBlank()) {
         try {
            JsonElement elem = JsonParser.parseString(body);
            if (!elem.isJsonArray()) {
               return nodes;
            }

            for (JsonElement e : elem.getAsJsonArray()) {
               try {
                  if (e.isJsonObject()) {
                     JsonObject obj = e.getAsJsonObject();
                     String url = obj.has("url") && !obj.get("url").isJsonNull() ? obj.get("url").getAsString() : null;
                     if (url != null && !url.isBlank()) {
                        String region = obj.has("region") && !obj.get("region").isJsonNull() ? obj.get("region").getAsString() : null;

                        URI uri;
                        try {
                           uri = URI.create(url);
                        } catch (Exception ex) {
                           LOGGER.warn("Terracotta node URL invalid, skip: {}", url);
                           continue;
                        }

                        nodes.add(new TerracottaNodeList.NodeInfo(uri, region));
                     }
                  }
               } catch (Exception var12) {
               }
            }
         } catch (Exception e) {
            LOGGER.warn("Terracotta node list parse failed: {}", e.getMessage());
         }

         return nodes;
      } else {
         return nodes;
      }
   }

   public static void clearCache() {
      cachedNodes = null;
      cacheTime = 0L;
   }

   private static final class NodeInfo {
      final URI uri;
      final String region;

      NodeInfo(URI uri, String region) {
         this.uri = uri;
         this.region = region;
      }
   }
}
