package icu.wuhui.voxlink.network;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.ui.LobbyPush;
import icu.wuhui.voxlink.ui.UiLogBus;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocket.Builder;
import java.net.http.WebSocket.Listener;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class SignalingWsTransport {
   private static final Gson GSON = new Gson();
   private static final long[] BACKOFF_STEPS = new long[]{10000L, 30000L, 60000L};
   private static final long HEARTBEAT_WATCHDOG_MS = 35000L;
   private static final long CLIENT_PING_INTERVAL_SEC = 15L;
   private static final long CONNECT_TIMEOUT_MS = 3000L;
   private static volatile HttpClient sharedHttpClient;
   private final String wsUrl;
   private final ExecutorService executor;
   private final ScheduledExecutorService scheduler;
   private final AtomicLong idGen = new AtomicLong(0L);
   private final ConcurrentHashMap<Long, CompletableFuture<SignalingClient.ApiResponse>> pending = new ConcurrentHashMap<>();
   private final AtomicBoolean connected = new AtomicBoolean(false);
   private final AtomicBoolean closed = new AtomicBoolean(false);
   private volatile WebSocket webSocket;
   private volatile Consumer<JsonObject> pushListener;
   private volatile Runnable reconnectListener;
   private volatile WebSocket reconnectFiredFor;
   private final AtomicLong nextRetryAt = new AtomicLong(0L);
   private final AtomicLong backoffStep = new AtomicLong(0L);
   private final AtomicLong lastFrameAt = new AtomicLong(System.currentTimeMillis());
   private final Object connectLock = new Object();
   private final AtomicBoolean connecting = new AtomicBoolean(false);
   private volatile CompletableFuture<Boolean> connectPromise;
   private final AtomicBoolean reconnectPending = new AtomicBoolean(false);

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
      this.scheduler.scheduleAtFixedRate(this::heartbeatWatchdog, 10L, 10L, TimeUnit.SECONDS);
      this.scheduler.scheduleAtFixedRate(this::clientPing, 15L, 15L, TimeUnit.SECONDS);
   }

   private void clientPing() {
      if (this.isConnected()) {
         WebSocket ws = this.webSocket;
         if (ws != null) {
            try {
               ws.sendPing(ByteBuffer.allocate(0)).whenComplete((v, err) -> {
                  if (err != null) {
                     VoxLinkMod.LOGGER.debug("[WS] 主动 ping 发送失败: {}", err.getMessage());
                     this.onDisconnected();
                  }
               });
            } catch (Exception var3) {
               VoxLinkMod.LOGGER.debug("[WS] 主动 ping 异常: {}", var3.getMessage());
            }
         }
      }
   }

   private static String deriveWsUrl(String serverUrl) {
      if (serverUrl != null && !serverUrl.isBlank()) {
         String s = serverUrl.trim();
         int q = s.indexOf(63);
         if (q >= 0) {
            s = s.substring(0, q);
         }

         int schemeEnd = s.indexOf("://");
         String hostPart;
         if (schemeEnd >= 0) {
            hostPart = s.substring(schemeEnd + 3);
         } else {
            hostPart = s;
         }

         int slash = hostPart.indexOf(47);
         if (slash >= 0) {
            hostPart = hostPart.substring(0, slash);
         }

         if (hostPart.isEmpty()) {
            return null;
         } else {
            String scheme;
            if (s.startsWith("https://")) {
               scheme = "wss://";
            } else if (s.startsWith("http://")) {
               scheme = "ws://";
            } else {
               scheme = "wss://";
            }

            return scheme + hostPart + "/ws";
         }
      } else {
         return null;
      }
   }

   public boolean isConnected() {
      return this.connected.get() && this.webSocket != null;
   }

   public boolean canUse() {
      return !this.closed.get() && this.wsUrl != null && System.currentTimeMillis() >= this.nextRetryAt.get();
   }

   public void setPushListener(Consumer<JsonObject> listener) {
      this.pushListener = listener;
   }

   public void setReconnectListener(Runnable listener) {
      this.reconnectListener = listener;
   }

   public CompletableFuture<SignalingClient.ApiResponse> request(String route, String method, JsonObject body, Map<String, String> query, long timeoutMs) {
      if (!this.canUse()) {
         return failed(new IOException("WS 不可用（退避中或未配置）"));
      } else {
         CompletableFuture<SignalingClient.ApiResponse> result = new CompletableFuture<>();
         this.ensureConnected().whenComplete((ok, ex) -> {
            if (ex == null && Boolean.TRUE.equals(ok)) {
               try {
                  this.sendFrame(result, route, method, body, query, timeoutMs);
               } catch (Exception var11) {
                  result.completeExceptionally(var11);
               }
            } else {
               this.markUnavailable();
               result.completeExceptionally((Throwable)(ex != null ? ex : new IOException("WS 连接失败")));
            }
         });
         return result;
      }
   }

   private void sendFrame(
      CompletableFuture<SignalingClient.ApiResponse> result, String route, String method, JsonObject body, Map<String, String> query, long timeoutMs
   ) {
      WebSocket ws = this.webSocket;
      if (ws == null) {
         result.completeExceptionally(new IOException("WS 已断开"));
      } else {
         long idTmp = this.idGen.incrementAndGet();
         if (idTmp == 0L) {
            idTmp = this.idGen.incrementAndGet();
         }

         long id = idTmp;
         CompletableFuture<SignalingClient.ApiResponse> pendingFut = new CompletableFuture<>();
         this.pending.put(id, pendingFut);
         pendingFut.orTimeout(timeoutMs, TimeUnit.MILLISECONDS);
         pendingFut.whenComplete((resp, err) -> {
            this.pending.remove(id);
            if (err != null) {
               this.markUnavailable();
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

            for (Entry<String, String> e : query.entrySet()) {
               q.addProperty(e.getKey(), e.getValue());
            }

            frame.add("query", q);
         }

         ws.sendText(GSON.toJson(frame), true).whenComplete((w, sendErr) -> {
            if (sendErr != null) {
               CompletableFuture<SignalingClient.ApiResponse> f = this.pending.remove(id);
               if (f != null && !f.isDone()) {
                  f.completeExceptionally(unwrap(sendErr));
               }
            }
         });
      }
   }

   private CompletableFuture<Boolean> ensureConnected() {
      if (this.isConnected()) {
         return CompletableFuture.completedFuture(true);
      } else {
         synchronized (this.connectLock) {
            if (this.connecting.get()) {
               CompletableFuture<Boolean> p = this.connectPromise;
               if (p != null) {
                  return p;
               }
            }

            this.connecting.set(true);
            CompletableFuture<Boolean> p = new CompletableFuture<>();
            this.connectPromise = p;

            try {
               UiLogBus.push(0, "voxlink.logui.signaling_connecting");
               Builder builder = wsHttpClient().newWebSocketBuilder().connectTimeout(Duration.ofMillis(3000L));
               builder.buildAsync(URI.create(this.wsUrl), new SignalingWsTransport.WsListener())
                  .orTimeout(3000L, TimeUnit.MILLISECONDS)
                  .whenComplete((ws, ex) -> {
                     this.connecting.set(false);
                     this.connectPromise = null;
                     if (ex != null) {
                        VoxLinkMod.LOGGER.debug("[WS] 连接失败: {}", ex.getMessage());
                        p.complete(false);
                     } else {
                        this.onConnected(ws);
                        VoxLinkMod.LOGGER.debug("[WS] 连接成功: {}", this.wsUrl);
                        p.complete(true);
                     }
                  });
            } catch (Exception var5) {
               this.connecting.set(false);
               this.connectPromise = null;
               VoxLinkMod.LOGGER.debug("[WS] 建立连接异常: {}", var5.getMessage());
               p.complete(false);
            }

            return p;
         }
      }
   }

   private void markUnavailable() {
      long step = this.backoffStep.getAndIncrement();
      long delay = BACKOFF_STEPS[(int)Math.min(step, (long)(BACKOFF_STEPS.length - 1))];
      this.nextRetryAt.set(System.currentTimeMillis() + delay);
      VoxLinkMod.LOGGER.debug("[WS] 标记不可用，退避 {}ms（第 {} 档）", delay, step + 1L);
   }

   private void onConnected(WebSocket ws) {
      UiLogBus.push(1, "voxlink.logui.signaling_connected");
      this.webSocket = ws;
      this.connected.set(true);
      this.lastFrameAt.set(System.currentTimeMillis());
      this.backoffStep.set(0L);
      this.nextRetryAt.set(0L);
      WebSocket prevWs = this.reconnectFiredFor;
      if (prevWs != ws) {
         this.reconnectFiredFor = ws;
         if (prevWs != null) {
            Runnable r = this.reconnectListener;
            if (r != null) {
               try {
                  this.executor.execute(r);
               } catch (Exception var5) {
               }
            }
         }
      }
   }

   private void onDisconnected() {
      boolean wasConnected = this.connected.getAndSet(false);
      this.webSocket = null;

      for (Entry<Long, CompletableFuture<SignalingClient.ApiResponse>> e : this.pending.entrySet()) {
         CompletableFuture<SignalingClient.ApiResponse> f = e.getValue();
         if (!f.isDone()) {
            f.completeExceptionally(new IOException("WS 已断开"));
         }
      }

      this.pending.clear();
      if (wasConnected) {
         UiLogBus.push(2, "voxlink.logui.signaling_lost");
         VoxLinkMod.LOGGER.debug("[WS] 连接断开，立即主动重连");
      }

      this.scheduleReconnect(0L);
   }

   private void scheduleReconnect(long delayMs) {
      if (!this.closed.get() && this.wsUrl != null) {
         if (this.reconnectPending.compareAndSet(false, true)) {
            try {
               this.scheduler.schedule(this::runReconnect, delayMs, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException var4) {
               this.reconnectPending.set(false);
            }
         }
      }
   }

   private void runReconnect() {
      this.reconnectPending.set(false);
      if (!this.closed.get() && !this.isConnected()) {
         this.ensureConnected().whenComplete((ok, ex) -> {
            if (!this.closed.get() && !Boolean.TRUE.equals(ok)) {
               this.markUnavailable();
               long delay = Math.max(1000L, this.nextRetryAt.get() - System.currentTimeMillis());
               this.scheduleReconnect(delay);
            }
         });
      }
   }

   private void handleFrame(String text) {
      try {
         JsonObject obj = (JsonObject)GSON.fromJson(text, JsonObject.class);
         if (obj == null) {
            return;
         }

         long id = obj.has("id") ? obj.get("id").getAsLong() : -1L;
         if (id == 0L && obj.has("push")) {
            if ("lobby".equals(obj.get("push").getAsString())) {
               LobbyPush.fire();
            }

            JsonObject data = obj.has("data") && obj.get("data").isJsonObject() ? obj.getAsJsonObject("data") : new JsonObject();
            Consumer<JsonObject> listener = this.pushListener;
            if (listener != null) {
               this.executor.execute(() -> {
                  try {
                     listener.accept(data);
                  } catch (Exception var3x) {
                     VoxLinkMod.LOGGER.warn("[WS] 推送处理异常: {}", var3x.getMessage());
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
      } catch (Exception var7) {
         VoxLinkMod.LOGGER.debug("[WS] 帧解析失败: {}", var7.getMessage());
      }
   }

   private void heartbeatWatchdog() {
      if (this.isConnected()) {
         if (System.currentTimeMillis() - this.lastFrameAt.get() > 35000L) {
            VoxLinkMod.LOGGER.debug("[WS] 心跳看门狗：{}ms 内无收帧，主动断开重连", 35000L);
            WebSocket ws = this.webSocket;
            if (ws != null) {
               try {
                  ws.sendClose(1000, "watchdog");
               } catch (Exception var3) {
               }
            }

            this.onDisconnected();
         }
      }
   }

   public void preconnect() {
      this.ensureConnected();
   }

   public void close() {
      if (this.closed.compareAndSet(false, true)) {
         this.connected.set(false);
         this.pushListener = null;
         WebSocket ws = this.webSocket;
         if (ws != null) {
            try {
               ws.sendClose(1000, "shutdown");
            } catch (Exception var5) {
            }
         }

         this.webSocket = null;

         for (Entry<Long, CompletableFuture<SignalingClient.ApiResponse>> e : this.pending.entrySet()) {
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
      return t instanceof CompletionException && t.getCause() != null ? t.getCause() : t;
   }

   private final class WsListener implements Listener {
      private final StringBuilder buffer;

      private WsListener() {
         Objects.requireNonNull(SignalingWsTransport.this);
         super();
         this.buffer = new StringBuilder();
      }

      @Override
      public void onOpen(WebSocket webSocket) {
         SignalingWsTransport.this.onConnected(webSocket);
         webSocket.request(1L);
         Listener.super.onOpen(webSocket);
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

         webSocket.request(1L);
         return CompletableFuture.completedFuture(null);
      }

      @Override
      public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
         SignalingWsTransport.this.lastFrameAt.set(System.currentTimeMillis());
         return Listener.super.onPing(webSocket, message);
      }

      @Override
      public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
         SignalingWsTransport.this.lastFrameAt.set(System.currentTimeMillis());
         return Listener.super.onPong(webSocket, message);
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
