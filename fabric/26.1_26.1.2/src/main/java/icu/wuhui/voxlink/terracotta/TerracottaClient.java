package icu.wuhui.voxlink.terracotta;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TerracottaClient {
   private static final Logger LOGGER = LoggerFactory.getLogger("voxlink-terracotta");
   private static final int CONNECT_TIMEOUT_SEC = 5;
   private static final int REQUEST_TIMEOUT_SEC = 8;
   private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5L)).build();
   private static final ExecutorService HTTP_EXECUTOR = Executors.newFixedThreadPool(4, r -> {
      Thread t = new Thread(r, "terracotta-http");
      t.setDaemon(true);
      return t;
   });
   private static final int HTTP_MAX_RETRIES = 3;
   private static final long[] HTTP_BACKOFF_MS = new long[]{10L, 50L, 200L};

   private TerracottaClient() {
   }

   public static CompletableFuture<String> get(int port, String path) {
      return CompletableFuture.supplyAsync(() -> {
         String url = "http://127.0.0.1:" + port + path;
         TerracottaClient.TerracottaHttpException lastHttpEx = null;
         IOException lastIoEx = null;

         for (int attempt = 0; attempt < 3; attempt++) {
            try {
               return doRequest(url);
            } catch (TerracottaClient.TerracottaHttpException e) {
               lastHttpEx = e;
               if (e.statusCode >= 400 && e.statusCode < 500) {
                  throw e;
               }

               if (attempt < 2) {
                  LOGGER.debug("Terracotta HTTP {} returned {} retry after {}ms backoff", new Object[]{path, e.statusCode, HTTP_BACKOFF_MS[attempt]});

                  try {
                     Thread.sleep(HTTP_BACKOFF_MS[attempt]);
                  } catch (InterruptedException ie) {
                     Thread.currentThread().interrupt();
                     throw e;
                  }
               }
            } catch (IOException e) {
               lastIoEx = e;
               if (attempt < 2) {
                  LOGGER.debug("Terracotta HTTP {} IO exception {} retry after {}ms backoff", new Object[]{path, e.getMessage(), HTTP_BACKOFF_MS[attempt]});

                  try {
                     Thread.sleep(HTTP_BACKOFF_MS[attempt]);
                  } catch (InterruptedException ie) {
                     Thread.currentThread().interrupt();
                     throw new RuntimeException(e);
                  }
               }
            }
         }

         if (lastHttpEx != null) {
            throw lastHttpEx;
         } else {
            throw new RuntimeException("陶瓦HTTP请求失败 3次: " + path, lastIoEx);
         }
      }, HTTP_EXECUTOR);
   }

   private static String doRequest(String url) throws IOException, TerracottaClient.TerracottaHttpException {
      HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(8L)).GET().build();

      try {
         HttpResponse<String> resp = HTTP.send(req, BodyHandlers.ofString());
         if (resp.statusCode() != 200) {
            throw new TerracottaClient.TerracottaHttpException("HTTP " + resp.statusCode() + ": " + url, resp.statusCode(), resp.body());
         } else {
            return resp.body();
         }
      } catch (HttpTimeoutException e) {
         throw new IOException("请求超时: " + url, e);
      } catch (InterruptedException e) {
         Thread.currentThread().interrupt();
         throw new IOException("请求被中断: " + url, e);
      }
   }

   public static CompletableFuture<JsonObject> getMeta(int port) {
      return get(port, "/meta").thenApply(s -> JsonParser.parseString(s).getAsJsonObject());
   }

   public static CompletableFuture<JsonObject> getState(int port) {
      return isJniMode(port) ? getStateJni() : get(port, "/state").thenApply(s -> JsonParser.parseString(s).getAsJsonObject());
   }

   private static CompletableFuture<JsonObject> getStateJni() {
      return CompletableFuture.supplyAsync(() -> {
         try {
            Class<?> bridge = Class.forName("icu.wuhui.voxlink.terracotta.Android.TerracottaAndroidBridge");
            String json = (String)bridge.getMethod("getState").invoke(null);
            return JsonParser.parseString(json).getAsJsonObject();
         } catch (Throwable t) {
            throw new RuntimeException("JNI getState失败: " + t.getMessage(), t);
         }
      }, HTTP_EXECUTOR);
   }

   public static CompletableFuture<Void> setIdle(int port) {
      return isJniMode(port) ? setIdleJni() : get(port, "/state/ide").thenRun(() -> {});
   }

   private static CompletableFuture<Void> setIdleJni() {
      return CompletableFuture.runAsync(() -> {
         try {
            Class<?> bridge = Class.forName("icu.wuhui.voxlink.terracotta.Android.TerracottaAndroidBridge");
            bridge.getMethod("setWaiting").invoke(null);
         } catch (Throwable t) {
            throw new RuntimeException("JNI setWaiting失败: " + t.getMessage(), t);
         }
      }, HTTP_EXECUTOR);
   }

   public static CompletableFuture<Void> startHost(int port, String playerName, List<URI> publicNodes) {
      if (isJniMode(port)) {
         return startHostJni(playerName);
      }

      StringBuilder path = new StringBuilder("/state/scanning");
      boolean first = true;
      if (playerName != null && !playerName.isEmpty()) {
         path.append("?player=").append(URLEncoder.encode(playerName, StandardCharsets.UTF_8));
         first = false;
      }

      if (publicNodes != null) {
         for (URI node : publicNodes) {
            path.append(first ? "?" : "&");
            first = false;
            path.append("public_nodes=").append(URLEncoder.encode(node.toString(), StandardCharsets.UTF_8));
         }
      }

      return get(port, path.toString()).thenRun(() -> {});
   }

   private static CompletableFuture<Void> startHostJni(String playerName) {
      return CompletableFuture.runAsync(() -> {
         try {
            Class<?> bridge = Class.forName("icu.wuhui.voxlink.terracotta.Android.TerracottaAndroidBridge");
            bridge.getMethod("setScanning", String.class, String.class).invoke(null, null, playerName);
         } catch (Throwable t) {
            throw new RuntimeException("JNI setScanning失败: " + t.getMessage(), t);
         }
      }, HTTP_EXECUTOR);
   }

   public static CompletableFuture<Void> startHost(int port, String playerName) {
      return startHost(port, playerName, null);
   }

   public static CompletableFuture<Boolean> joinRoom(int port, String roomCode, String playerName, List<URI> publicNodes) {
      if (isJniMode(port)) {
         return joinRoomJni(roomCode, playerName);
      }

      StringBuilder path = new StringBuilder("/state/guesting?room=");
      path.append(URLEncoder.encode(roomCode, StandardCharsets.UTF_8));
      if (playerName != null && !playerName.isEmpty()) {
         path.append("&player=").append(URLEncoder.encode(playerName, StandardCharsets.UTF_8));
      }

      if (publicNodes != null) {
         for (URI node : publicNodes) {
            path.append("&public_nodes=").append(URLEncoder.encode(node.toString(), StandardCharsets.UTF_8));
         }
      }

      return get(port, path.toString()).thenApply(resp -> true);
   }

   private static CompletableFuture<Boolean> joinRoomJni(String roomCode, String playerName) {
      return CompletableFuture.supplyAsync(() -> {
         try {
            Class<?> bridge = Class.forName("icu.wuhui.voxlink.terracotta.Android.TerracottaAndroidBridge");
            Boolean ok = (Boolean)bridge.getMethod("setGuesting", String.class, String.class).invoke(null, roomCode, playerName);
            return ok != null && ok;
         } catch (Throwable t) {
            throw new RuntimeException("JNI setGuesting失败: " + t.getMessage(), t);
         }
      }, HTTP_EXECUTOR);
   }

   public static CompletableFuture<Boolean> joinRoom(int port, String roomCode, String playerName) {
      return joinRoom(port, roomCode, playerName, null);
   }

   public static CompletableFuture<Void> panic(int port) {
      return isJniMode(port) ? setIdleJni() : get(port, "/panic?peaceful=true").thenRun(() -> {});
   }

   private static boolean isJniMode(int port) {
      return port == -1;
   }

   public static String getStateName(JsonObject state) {
      if (state == null) {
         return "unknown";
      } else {
         return state.has("state") && !state.get("state").isJsonNull() ? state.get("state").getAsString() : "unknown";
      }
   }

   public static String getRoomCode(JsonObject state) {
      if (state == null) {
         return null;
      } else {
         return state.has("room") && !state.get("room").isJsonNull() ? state.get("room").getAsString() : null;
      }
   }

   public static String getConnectUrl(JsonObject state) {
      if (state == null) {
         return null;
      } else {
         return state.has("url") && !state.get("url").isJsonNull() ? state.get("url").getAsString() : null;
      }
   }

   public static String getDifficulty(JsonObject state) {
      if (state == null) {
         return "UNKNOWN";
      } else {
         return state.has("difficulty") && !state.get("difficulty").isJsonNull() ? state.get("difficulty").getAsString() : "UNKNOWN";
      }
   }

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

      public int getStatusCode() {
         return this.statusCode;
      }

      public String getResponseBody() {
         return this.responseBody;
      }

      public String getErrorDetail() {
         return "HTTP " + this.statusCode + (this.responseBody != null && !this.responseBody.isEmpty() ? ": " + this.responseBody : "");
      }
   }
}
