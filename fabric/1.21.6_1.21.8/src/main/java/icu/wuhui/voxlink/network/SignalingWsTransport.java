package icu.wuhui.voxlink.network;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import icu.wuhui.voxlink.VoxLinkMod;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 信令 WebSocket 传输层（零新依赖：JDK 内置 HttpClient.newWebSocketBuilder + Gson）。
 *
 * <p>与服务端 /ws 端点通信：请求/响应按 id 多路复用，id==0 的帧为服务端实时推送。
 * 任何网络异常（连接失败/超时/断连）都会让对应请求以异常完成，由 SignalingClient 降级为 HTTP。
 * 连接断开后采用指数退避（10s/30s/60s，上限 60s）懒重连：仅在下次 request() 时尝试，
 * 退避窗口内 canUse() 返回 false，SignalingClient 直接走 HTTP，避免每次轮询都卡 3s。
 */
public final class SignalingWsTransport {
   private static final Gson GSON = new Gson();
   // 连接失败/退避档位（毫秒）
   private static final long[] BACKOFF_STEPS = {10000L, 30000L, 60000L};
   // 心跳看门狗：超过该时间未收到任何帧则主动断开重连
   private static final long HEARTBEAT_WATCHDOG_MS = 90000L;
   // 单次连接尝试超时
   private static final long CONNECT_TIMEOUT_MS = 3000L;

   // 共享 HttpClient（newWebSocketBuilder 是实例方法）
   private static volatile HttpClient sharedHttpClient;

   private static HttpClient wsHttpClient() {
      HttpClient c = sharedHttpClient;
      if (c == null) {
         synchronized (SignalingWsTransport.class) {
            if (sharedHttpClient == null) {
               sharedHttpClient = HttpClient.newHttpClient();
            }
            c = sharedHttpClient;
         }
      }
      return c;
   }

   private final String wsUrl;
   private final ExecutorService executor;
   private final ScheduledExecutorService scheduler;

   private final AtomicLong idGen = new AtomicLong(0);
   private final ConcurrentHashMap<Long, CompletableFuture<SignalingClient.ApiResponse>> pending = new ConcurrentHashMap<>();
   private final AtomicBoolean connected = new AtomicBoolean(false);
   private final AtomicBoolean closed = new AtomicBoolean(false);

   private volatile WebSocket webSocket;
   private volatile Consumer<JsonObject> pushListener;

   // 退避状态：nextRetryAt 之前 canUse() 为 false
   private final AtomicLong nextRetryAt = new AtomicLong(0L);
   private final AtomicLong backoffStep = new AtomicLong(0L);
   private final AtomicLong lastFrameAt = new AtomicLong(System.currentTimeMillis());

   // 单飞连接控制
   private final Object connectLock = new Object();
   private final AtomicBoolean connecting = new AtomicBoolean(false);
   private volatile CompletableFuture<Boolean> connectPromise;

   public SignalingWsTransport(String serverUrl) {
      this.wsUrl = deriveWsUrl(serverUrl);
      this.executor = Executors.newFixedThreadPool(2, r -> {
         Thread t = new Thread(r, "VoxLink-WS");
         t.setDaemon(true);
         return t;
      });
      this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
         Thread t = new Thread(r, "VoxLink-WS-Scheduler");
         t.setDaemon(true);
         return t;
      });
      // 心跳看门狗：每 30s 检查一次，正常情况下服务端 30s 一次 ping，不会触发
      this.scheduler.scheduleAtFixedRate(this::heartbeatWatchdog, 30L, 30L, TimeUnit.SECONDS);
   }

   /** 由 serverUrl 推导 /ws 端点：去掉路径与 query，scheme http→ws / https→wss，拼 /ws。 */
   private static String deriveWsUrl(String serverUrl) {
      if (serverUrl == null || serverUrl.isBlank()) {
         return null;
      }

      String s = serverUrl.trim();
      // 去掉 query
      int q = s.indexOf('?');
      if (q >= 0) {
         s = s.substring(0, q);
      }
      // 去掉路径，只保留 scheme://host[:port]
      int schemeEnd = s.indexOf("://");
      String hostPart;
      if (schemeEnd >= 0) {
         hostPart = s.substring(schemeEnd + 3);
      } else {
         hostPart = s;
      }
      int slash = hostPart.indexOf('/');
      if (slash >= 0) {
         hostPart = hostPart.substring(0, slash);
      }
      if (hostPart.isEmpty()) {
         return null;
      }

      String scheme;
      if (s.startsWith("https://")) {
         scheme = "wss://";
      } else if (s.startsWith("http://")) {
         scheme = "ws://";
      } else {
         // 没有显式 scheme，按 https 处理
         scheme = "wss://";
      }

      return scheme + hostPart + "/ws";
   }

   /** 当前是否处于已连接且存活状态。 */
   public boolean isConnected() {
      return this.connected.get() && this.webSocket != null;
   }

   /** 是否可尝试使用 WS：URL 合法且不在退避冷却窗口内。 */
   public boolean canUse() {
      return !this.closed.get() && this.wsUrl != null && System.currentTimeMillis() >= this.nextRetryAt.get();
   }

   /** 注册推送帧监听器（data 部分为 {"s":[...],"ts":N}）。传 null 取消。 */
   public void setPushListener(Consumer<JsonObject> listener) {
      this.pushListener = listener;
   }

   /**
    * 发起一次请求，返回 ApiResponse 的 future。
    * 未连接时先尝试异步连接（3s 超时）；连接失败则标记退避并异常完成，由上层降级 HTTP。
    */
   public CompletableFuture<SignalingClient.ApiResponse> request(
      String route, String method, JsonObject body, Map<String, String> query, long timeoutMs
   ) {
      if (!canUse()) {
         return failed(new IOException("WS 不可用（退避中或未配置）"));
      }

      CompletableFuture<SignalingClient.ApiResponse> result = new CompletableFuture<>();
      ensureConnected().whenComplete((ok, ex) -> {
         if (ex != null || !Boolean.TRUE.equals(ok)) {
            markUnavailable();
            result.completeExceptionally(ex != null ? ex : new IOException("WS 连接失败"));
            return;
         }
         try {
            sendFrame(result, route, method, body, query, timeoutMs);
         } catch (Exception e) {
            result.completeExceptionally(e);
         }
      });
      return result;
   }

   private void sendFrame(
      CompletableFuture<SignalingClient.ApiResponse> result,
      String route, String method, JsonObject body, Map<String, String> query, long timeoutMs
   ) {
      WebSocket ws = this.webSocket;
      if (ws == null) {
         result.completeExceptionally(new IOException("WS 已断开"));
         return;
      }

      long idTmp = this.idGen.incrementAndGet();
      if (idTmp == 0L) {
         idTmp = this.idGen.incrementAndGet();
      }
      final long id = idTmp;

      CompletableFuture<SignalingClient.ApiResponse> pendingFut = new CompletableFuture<>();
      this.pending.put(id, pendingFut);
      pendingFut.orTimeout(timeoutMs, TimeUnit.MILLISECONDS);
      pendingFut.whenComplete((resp, err) -> {
         this.pending.remove(id);
         if (err != null) {
            // 超时/网络错误 → 异常完成，上层降级 HTTP
            result.completeExceptionally(unwrap(err));
         } else {
            result.complete(resp);
         }
      });

      JsonObject frame = new JsonObject();
      frame.addProperty("id", id);
      frame.addProperty("route", route);
      frame.addProperty("method", method != null ? method : "POST");
      if (body != null) {
         frame.add("body", body);
      }
      if (query != null && !query.isEmpty()) {
         JsonObject q = new JsonObject();
         for (Map.Entry<String, String> e : query.entrySet()) {
            q.addProperty(e.getKey(), e.getValue());
         }
         frame.add("query", q);
      }

      final long finalId = id;
      ws.sendText(GSON.toJson(frame), true).whenComplete((w, sendErr) -> {
         if (sendErr != null) {
            CompletableFuture<SignalingClient.ApiResponse> f = this.pending.remove(finalId);
            if (f != null && !f.isDone()) {
               f.completeExceptionally(unwrap(sendErr));
            }
         }
      });
   }

   /** 确保已连接：已连接立即返回 true；否则单飞发起异步连接（3s 超时）。 */
   private CompletableFuture<Boolean> ensureConnected() {
      if (isConnected()) {
         return CompletableFuture.completedFuture(true);
      }

      synchronized (this.connectLock) {
         if (this.connecting.get()) {
            // 复用已有连接尝试
            CompletableFuture<Boolean> p = this.connectPromise;
            if (p != null) {
               return p;
            }
         }
         this.connecting.set(true);
         CompletableFuture<Boolean> p = new CompletableFuture<>();
         this.connectPromise = p;
         try {
            WebSocket.Builder builder = wsHttpClient().newWebSocketBuilder()
               .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS));
            builder.buildAsync(URI.create(this.wsUrl), new WsListener())
               .orTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
               .whenComplete((ws, ex) -> {
                  this.connecting.set(false);
                  this.connectPromise = null;
                  if (ex != null) {
                     VoxLinkMod.LOGGER.debug("[WS] 连接失败: {}", ex.getMessage());
                     p.complete(false);
                  } else {
                     // 确保 webSocket 与 connected 在后续 sendFrame 前已置位（不依赖 onOpen 调用顺序）
                     SignalingWsTransport.this.onConnected(ws);
                     VoxLinkMod.LOGGER.debug("[WS] 连接成功: {}", this.wsUrl);
                     p.complete(true);
                  }
               });
         } catch (Exception e) {
            this.connecting.set(false);
            this.connectPromise = null;
            VoxLinkMod.LOGGER.debug("[WS] 建立连接异常: {}", e.getMessage());
            p.complete(false);
         }
         return p;
      }
   }

   private void markUnavailable() {
      long step = this.backoffStep.getAndIncrement();
      long delay = BACKOFF_STEPS[(int) Math.min(step, (long) (BACKOFF_STEPS.length - 1))];
      this.nextRetryAt.set(System.currentTimeMillis() + delay);
      VoxLinkMod.LOGGER.debug("[WS] 标记不可用，退避 {}ms（第 {} 档）", delay, step + 1L);
   }

   private void onConnected(WebSocket ws) {
      this.webSocket = ws;
      this.connected.set(true);
      this.lastFrameAt.set(System.currentTimeMillis());
      // 连接成功，重置退避
      this.backoffStep.set(0L);
      this.nextRetryAt.set(0L);
   }

   private void onDisconnected() {
      boolean wasConnected = this.connected.getAndSet(false);
      this.webSocket = null;
      // 所有在途请求以网络错误完成，由上层降级 HTTP
      for (Map.Entry<Long, CompletableFuture<SignalingClient.ApiResponse>> e : this.pending.entrySet()) {
         CompletableFuture<SignalingClient.ApiResponse> f = e.getValue();
         if (!f.isDone()) {
            f.completeExceptionally(new IOException("WS 已断开"));
         }
      }
      this.pending.clear();
      if (wasConnected) {
         VoxLinkMod.LOGGER.debug("[WS] 连接断开，等待下次请求触发重连");
      }
   }

   private void handleFrame(String text) {
      try {
         JsonObject obj = GSON.fromJson(text, JsonObject.class);
         if (obj == null) {
            return;
         }
         long id = obj.has("id") ? obj.get("id").getAsLong() : -1L;
         // 推送帧：id==0 且含 push 字段
         if (id == 0L && obj.has("push")) {
            JsonObject data = obj.has("data") && obj.get("data").isJsonObject()
               ? obj.getAsJsonObject("data")
               : new JsonObject();
            Consumer<JsonObject> listener = this.pushListener;
            if (listener != null) {
               // 投递到专属 executor，避免阻塞 WS 读线程
               this.executor.execute(() -> {
                  try {
                     listener.accept(data);
                  } catch (Exception e) {
                     VoxLinkMod.LOGGER.warn("[WS] 推送处理异常: {}", e.getMessage());
                  }
               });
            }
            return;
         }
         CompletableFuture<SignalingClient.ApiResponse> fut = this.pending.remove(id);
         if (fut == null) {
            return;
         }
         fut.complete(SignalingClient.ApiResponse.fromJson(obj));
      } catch (Exception e) {
         VoxLinkMod.LOGGER.debug("[WS] 帧解析失败: {}", e.getMessage());
      }
   }

   private void heartbeatWatchdog() {
      if (!isConnected()) {
         return;
      }
      if (System.currentTimeMillis() - this.lastFrameAt.get() > HEARTBEAT_WATCHDOG_MS) {
         VoxLinkMod.LOGGER.debug("[WS] 心跳看门狗：90s 内无收帧，主动断开重连");
         WebSocket ws = this.webSocket;
         if (ws != null) {
            try {
               ws.sendClose(WebSocket.NORMAL_CLOSURE, "watchdog");
            } catch (Exception ignored) {
            }
         }
         onDisconnected();
      }
   }

   public void close() {
      if (this.closed.compareAndSet(false, true)) {
         this.connected.set(false);
         this.pushListener = null;
         WebSocket ws = this.webSocket;
         if (ws != null) {
            try {
               ws.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
            } catch (Exception ignored) {
            }
         }
         this.webSocket = null;
         for (Map.Entry<Long, CompletableFuture<SignalingClient.ApiResponse>> e : this.pending.entrySet()) {
            CompletableFuture<SignalingClient.ApiResponse> f = e.getValue();
            if (!f.isDone()) {
               f.completeExceptionally(new IOException("WS 已关闭"));
            }
         }
         this.pending.clear();
         this.scheduler.shutdownNow();
         this.executor.shutdownNow();
      }
   }

   private static <T> CompletableFuture<T> failed(Throwable t) {
      CompletableFuture<T> f = new CompletableFuture<>();
      f.completeExceptionally(t);
      return f;
   }

   private static Throwable unwrap(Throwable t) {
      if (t instanceof java.util.concurrent.CompletionException && t.getCause() != null) {
         return t.getCause();
      }
      return t;
   }

   /** 每个连接一个独立监听器，避免跨连接复用同一个分片缓冲。 */
   private final class WsListener implements WebSocket.Listener {
      private final StringBuilder buffer = new StringBuilder();

      @Override
      public void onOpen(WebSocket webSocket) {
         SignalingWsTransport.this.onConnected(webSocket);
         webSocket.request(1);
         WebSocket.Listener.super.onOpen(webSocket);
      }

      @Override
      public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
         SignalingWsTransport.this.lastFrameAt.set(System.currentTimeMillis());
         this.buffer.append(data);
         if (last) {
            String full = this.buffer.toString();
            this.buffer.setLength(0);
            SignalingWsTransport.this.handleFrame(full);
         }
         webSocket.request(1);
         return CompletableFuture.completedFuture(null);
      }

      @Override
      public void onError(WebSocket webSocket, Throwable error) {
         VoxLinkMod.LOGGER.debug("[WS] onError: {}", error.getMessage());
         SignalingWsTransport.this.onDisconnected();
      }

      @Override
      public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
         SignalingWsTransport.this.onDisconnected();
         return CompletableFuture.completedFuture(null);
      }
   }
}
