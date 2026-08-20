package icu.wuhui.voxlink.terracotta;

import com.google.gson.JsonObject;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TerracottaManager {
   private static final Logger LOGGER = LoggerFactory.getLogger("voxlink-terracotta");
   private static final long POLL_INTERVAL_MS = 500L;
   private static final int INIT_POLL_CYCLES = 50;
   private static final int INIT_POLL_MS = 100;
   private static final int ROOM_CODE_TIMEOUT_SEC = 30;
   private static final int TIMEOUT_MARGIN_SEC = 5;
   private static final int MAX_STATE_FAIL_COUNT = 3;
   private static volatile boolean initialized = false;
   private static volatile int port = 0;
   private static final AtomicReference<TerracottaState> stateRef = new AtomicReference<>(TerracottaState.Bootstrap.INSTANCE);
   private static volatile JsonObject lastStateJson = null;
   private static final AtomicLong stateEpoch = new AtomicLong(0L);
   private static volatile long lastStateEpoch = -1L;
   private static ScheduledExecutorService scheduler;
   private static ScheduledFuture<?> pollTask;
   private static final Object POLL_LOCK = new Object();
   private static volatile int stateFailCount = 0;
   private static volatile boolean skipNextIndexGuard = false;
   private static final AtomicBoolean downloading = new AtomicBoolean(false);
   private static volatile boolean downloadFailed = false;
   private static volatile TerracottaBinary.DownloadProgress lastProgress = null;
   private static final int MAX_DOWNLOAD_ATTEMPTS = 60;
   private static volatile ExecutorService downloadExecutor = null;
   private static volatile CompletableFuture<Boolean> downloadCompletion = null;
   private static volatile Consumer<TerracottaState> uiStateCallback = null;
   private static final AtomicReference<TerracottaManager.WaitContext> pendingWait = new AtomicReference<>(null);

   private TerracottaManager() {
   }

   public static void setUiStateCallback(Consumer<TerracottaState> cb) {
      uiStateCallback = cb;
   }

   public static void clearUiStateCallback() {
      uiStateCallback = null;
   }

   public static void resumeDownloadIfPending() {
      if (!TerracottaBinary.isPlatformSupported()) {
         LOGGER.info("Current platform does not support Terracotta, skip");
      } else if (!TerracottaBinary.isReady()) {
         if (TerracottaBinary.isDownloadPending()) {
            LOGGER.info("Detected incomplete Terracotta download, auto resume");
            startBackgroundDownload(null);
         }
      }
   }

   private static void startBackgroundDownload(Consumer<TerracottaBinary.DownloadProgress> uiCallback) {
      if (downloading.compareAndSet(false, true)) {
         if (!TerracottaBinary.isPlatformSupported()) {
            downloading.set(false);
         } else {
            downloadFailed = false;
            lastProgress = null;
            TerracottaBinary.resetDownloadFlags();
            TerracottaBinary.markDownloadPending();
            downloadCompletion = new CompletableFuture<>();
            synchronized (TerracottaManager.class) {
               if (downloadExecutor == null || downloadExecutor.isShutdown()) {
                  downloadExecutor = Executors.newSingleThreadExecutor(r -> {
                     Thread t = new Thread(r, "terracotta-download");
                     t.setDaemon(true);
                     return t;
                  });
               }
            }

            ExecutorService executor = downloadExecutor;
            executor.submit(() -> {
               try {
                  int attempt = 0;

                  while (!TerracottaBinary.isReady() && !Thread.currentThread().isInterrupted() && attempt < 60) {
                     attempt++;
                     if (TerracottaBinary.isDownloadCancelled()) {
                        break;
                     }

                     try {
                        TerracottaBinary.downloadAsync(progress -> {
                           lastProgress = progress;
                           if (progress.done) {
                              downloadFailed = false;
                           } else if (progress.failed) {
                              downloadFailed = true;
                           }

                           if (uiCallback != null) {
                              uiCallback.accept(progress);
                           }
                        }).join();
                     } catch (Exception e) {
                        if (TerracottaBinary.isDownloadCancelled()) {
                           break;
                        }

                        LOGGER.warn("Terracotta download failed, retry after 5s (attempt {}): {}", new Object[]{attempt, e.getMessage(), e});
                        downloadFailed = true;
                     }

                     if (TerracottaBinary.isReady() || TerracottaBinary.isDownloadCancelled() || Thread.currentThread().isInterrupted()) {
                        break;
                     }

                     if (attempt >= 60) {
                        LOGGER.error("Terracotta download reached max retries {}, stop", 60);
                        downloadFailed = true;
                        break;
                     }

                     for (int i = 0; i < 50 && !TerracottaBinary.isDownloadCancelled(); i++) {
                        try {
                           Thread.sleep(100L);
                        } catch (InterruptedException ie) {
                           Thread.currentThread().interrupt();
                           break;
                        }
                     }
                  }

                  if (TerracottaBinary.isReady()) {
                     TerracottaBinary.clearDownloadPending();
                     LOGGER.info("Terracotta background download completed");
                     downloadFailed = false;
                  } else if (TerracottaBinary.isDownloadCancelled()) {
                     TerracottaBinary.clearDownloadPending();
                     LOGGER.info("Terracotta download cancelled");
                     downloadFailed = false;
                  }
               } finally {
                  TerracottaBinary.resetDownloadFlags();
                  downloading.set(false);
                  CompletableFuture<Boolean> dc = downloadCompletion;
                  if (dc != null && !dc.isDone()) {
                     dc.complete(TerracottaBinary.isReady());
                  }
               }
            });
         }
      }
   }

   public static CompletableFuture<Boolean> waitForDownload() {
      if (!downloading.get()) {
         return CompletableFuture.completedFuture(TerracottaBinary.isReady());
      }

      CompletableFuture<Boolean> dc = downloadCompletion;
      return dc == null ? CompletableFuture.completedFuture(TerracottaBinary.isReady()) : dc;
   }

   public static void pauseDownload() {
      TerracottaBinary.pauseDownload();
   }

   public static void resumeDownload() {
      TerracottaBinary.resumeDownload();
   }

   public static void cancelDownload() {
      TerracottaBinary.cancelDownload();
      TerracottaBinary.clearDownloadPending();
   }

   public static boolean isDownloadPaused() {
      return TerracottaBinary.isDownloadPaused();
   }

   public static boolean isDownloadCancelled() {
      return TerracottaBinary.isDownloadCancelled();
   }

   public static CompletableFuture<Integer> initialize() {
      if (initialized && port != 0 && TerracottaProcess.isAlive()) {
         return CompletableFuture.completedFuture(port);
      } else if (!TerracottaBinary.verifyInstallation()) {
         LOGGER.warn("Terracotta install self-check failed, fallback to VoxLink P2P");
         return CompletableFuture.failedFuture(new TerracottaNotReadyException("陶瓦安装自检失败"));
      } else {
         return TerracottaProcess.start().exceptionally(e -> {
            Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
            if (cause instanceof TerracottaNotReadyException) {
               LOGGER.info("Terracotta binary not ready, skip init");
               throw (TerracottaNotReadyException)cause;
            } else if (cause instanceof RuntimeException) {
               throw (RuntimeException)cause;
            } else {
               throw new RuntimeException(cause);
            }
         }).thenCompose(p -> {
            port = p;
            initialized = true;
            TerracottaState.Unknown unknown = new TerracottaState.Unknown();
            unknown.port = p;
            stateRef.set(unknown);
            startPolling();
            // JNI 模式(port==-1)没有 HTTP meta, 用 bridge 存活状态代替
            return p == -1
               ? CompletableFuture.completedFuture(p)
               : TerracottaClient.getMeta(p).thenApply(meta -> p);
         });
      }
   }

   private static CompletableFuture<Integer> ensureReady() {
      TerracottaState cur = stateRef.get();
      if (cur instanceof TerracottaState.Fatal && ((TerracottaState.Fatal)cur).isRecoverable()) {
         LOGGER.info("Terracotta in recoverable Fatal, recover before new session");
         return recover();
      } else if (cur instanceof TerracottaState.Ready && !(cur instanceof TerracottaState.Waiting)) {
         LOGGER.info("Terracotta in {}, setIdle cleanup before new session", cur);
         return setIdle().exceptionally(e -> {
            LOGGER.warn("setIdle failed, continue trying: {}", e.getMessage());
            return null;
         }).thenCompose(v -> {
            TerracottaState.Unknown unknown = new TerracottaState.Unknown();
            unknown.port = port;
            stateRef.set(unknown);
            lastStateJson = null;
            return initialize();
         });
      } else {
         return initialize();
      }
   }

   public static CompletableFuture<Integer> recover() {
      TerracottaState current = stateRef.get();
      if (current instanceof TerracottaState.Fatal && ((TerracottaState.Fatal)current).isRecoverable()) {
         LOGGER.info("Terracotta entered recoverable Fatal, try recover");
         failPendingWait("陶瓦recover取消等待");
         stateEpoch.incrementAndGet();
         clearLastState();
         skipNextIndexGuard = true;
         TerracottaProcess.stop();
         stateRef.set(TerracottaState.Launching.INSTANCE);
         initialized = false;
         port = 0;
         return initialize();
      } else {
         LOGGER.info("Current state {} not recoverable, skip recover", current);
         return CompletableFuture.failedFuture(new RuntimeException("不可恢复的致命错误: " + current));
      }
   }

   private static void startPolling() {
      synchronized (POLL_LOCK) {
         if (scheduler == null || scheduler.isShutdown()) {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
               Thread t = new Thread(r, "terracotta-poll");
               t.setDaemon(true);
               return t;
            });
         }

         if (pollTask != null) {
            pollTask.cancel(false);
         }

         pollTask = scheduler.scheduleAtFixedRate(() -> {
            if (port != 0 && TerracottaProcess.isAlive()) {
               long epoch = stateEpoch.get();

               try {
                  TerracottaClient.getState(port).thenAccept(json -> {
                     updateState(json, epoch);
                     stateFailCount = 0;
                  }).exceptionally(e -> {
                     stateFailCount++;
                     if (stateFailCount >= 3) {
                        TerracottaState prev = stateRef.get();
                        TerracottaState.Fatal fatal = new TerracottaState.Fatal(TerracottaState.Fatal.Type.TERRACOTTA);
                        if (stateRef.compareAndSet(prev, fatal)) {
                           LOGGER.warn("Terracotta state fetch failed {} consecutive times, switch to Fatal(TERRACOTTA): {}", stateFailCount, e.getMessage());
                           notifyPendingWait(fatal);
                        }

                        stateFailCount = 0;
                     } else {
                        LOGGER.debug("Terracotta state polling failed ({}/{}): {}", new Object[]{stateFailCount, 3, e.getMessage()});
                     }

                     return null;
                  });
               } catch (Exception e) {
                  LOGGER.debug("Terracotta state polling exception: {}", e.getMessage());
               }
            } else {
               String errLine = TerracottaProcess.getLastErrorLine();
               failPendingWait("陶瓦进程意外退出" + (errLine != null ? ": " + errLine : ""));
            }
         }, 0L, 500L, TimeUnit.MILLISECONDS);
      }
   }

   private static void updateState(JsonObject json, long expectedEpoch) {
      if (json != null) {
         if (expectedEpoch >= lastStateEpoch) {
            TerracottaState current = stateRef.get();
            int currentPort = port;
            if (current instanceof TerracottaState.PortSpecific) {
               currentPort = ((TerracottaState.PortSpecific)current).port;
               if (currentPort == 0) {
                  currentPort = port;
               }
            }

            TerracottaState.Ready next = TerracottaState.parseFromState(json, currentPort);
            if (current instanceof TerracottaState.Ready) {
               int currentIndex = ((TerracottaState.Ready)current).index;
               if (next.index <= currentIndex && next.index >= 0) {
                  if (!skipNextIndexGuard) {
                     return;
                  }

                  skipNextIndexGuard = false;
                  LOGGER.info("Skip index guard after Terracotta recover: current={}, next={}", currentIndex, next.index);
               }
            }

            if (stateRef.compareAndSet(current, next)) {
               lastStateJson = json;
               lastStateEpoch = expectedEpoch;
               if (!current.name().equals(next.name())) {
                  LOGGER.info("Terracotta state: {} -> {}", current, next);
               }

               notifyPendingWait(next);
               Consumer<TerracottaState> cb = uiStateCallback;
               if (cb != null && isUiRelevantState(next)) {
                  try {
                     cb.accept(next);
                  } catch (Throwable t) {
                     LOGGER.warn("UI state callback exception: {}", t.getMessage());
                  }
               }
            }
         }
      }
   }

   private static boolean isUiRelevantState(TerracottaState state) {
      return state instanceof TerracottaState.HostScanning
         || state instanceof TerracottaState.HostStarting
         || state instanceof TerracottaState.GuestConnecting
         || state instanceof TerracottaState.GuestStarting
         || state instanceof TerracottaState.HostOK
         || state instanceof TerracottaState.GuestOK
         || state instanceof TerracottaState.Exception
         || state instanceof TerracottaState.Fatal;
   }

   private static void notifyPendingWait(TerracottaState state) {
      TerracottaManager.WaitContext ctx = pendingWait.get();
      if (ctx != null && !ctx.future.isDone()) {
         if (state instanceof TerracottaState.Fatal) {
            ctx.future.completeExceptionally(new RuntimeException("陶瓦进入致命状态: " + state));
            clearPendingWait(ctx);
         } else if (state instanceof TerracottaState.Exception) {
            ctx.future.completeExceptionally(new TerracottaManager.TerracottaExceptionStateException(((TerracottaState.Exception)state).type));
            clearPendingWait(ctx);
         } else {
            if (ctx.predicate.test(state)) {
               String result = ctx.supplier.get();
               if (result != null && !result.isEmpty()) {
                  ctx.future.complete(result);
                  clearPendingWait(ctx);
               }
            }
         }
      }
   }

   private static void failPendingWait(String reason) {
      TerracottaManager.WaitContext ctx = pendingWait.get();
      if (ctx != null && !ctx.future.isDone()) {
         ctx.future.completeExceptionally(new RuntimeException(reason));
         clearPendingWait(ctx);
      }
   }

   private static void clearPendingWait(TerracottaManager.WaitContext expected) {
      if (expected != null) {
         pendingWait.compareAndSet(expected, null);
      }
   }

   public static CompletableFuture<String> createRoom(String playerName) {
      return !TerracottaBinary.isReady()
         ? CompletableFuture.failedFuture(new TerracottaNotReadyException("陶瓦二进制未就绪, 降级到 VoxLink P2P"))
         : createRoomAttempt(playerName).handle((r, e) -> {
            if (e == null) {
               return CompletableFuture.completedFuture(r);
            }

            Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
            if (!(cause instanceof TerracottaManager.TerracottaExceptionStateException)) {
               return CompletableFuture.failedFuture(e);
            }

            LOGGER.warn("Terracotta createRoom 遇到可恢复异常状态(type={}), setIdle重置后自动重试1次", ((TerracottaManager.TerracottaExceptionStateException)cause).stateType);
            return resetToWaiting().thenCompose(v -> createRoomAttempt(playerName));
         }).thenCompose(f -> (CompletionStage<String>)f);
   }

   private static CompletableFuture<String> createRoomAttempt(String playerName) {
      return ensureReady().thenCompose(p -> TerracottaNodeList.fetchForChina().thenCompose(nodes -> {
         LOGGER.info("Terracotta host inject node list: {} nodes", nodes.size());
         return TerracottaClient.startHost(p, playerName, (List<URI>)nodes).thenCompose(v -> waitForRoomCode(30));
      }));
   }

   private static CompletableFuture<String> waitForRoomCode(int timeoutSec) {
      return waitForState(state -> state instanceof TerracottaState.HostOK, TerracottaManager::getRoomCode, timeoutSec, "房间号");
   }

   public static CompletableFuture<String> waitForGuestOk(int timeoutSec) {
      return waitForState(state -> state instanceof TerracottaState.GuestOK, TerracottaManager::getConnectUrl, timeoutSec, "连接");
   }

   private static CompletableFuture<String> waitForState(
      Predicate<TerracottaState> successPred, Supplier<String> resultSupplier, int timeoutSec, String actionName
   ) {
      lastStateJson = null;
      long myEpoch = stateEpoch.incrementAndGet();
      lastStateEpoch = myEpoch;
      TerracottaState cur = stateRef.get();
      if (cur instanceof TerracottaState.Fatal) {
         return CompletableFuture.failedFuture(new RuntimeException("陶瓦进入致命状态: " + cur));
      }

      if (cur instanceof TerracottaState.Exception) {
         return CompletableFuture.failedFuture(new TerracottaManager.TerracottaExceptionStateException(getExceptionType()));
      }

      TerracottaManager.WaitContext prev = pendingWait.getAndSet(null);
      if (prev != null && !prev.future.isDone()) {
         prev.future.cancel(true);
      }

      CompletableFuture<String> future = new CompletableFuture<>();
      TerracottaManager.WaitContext ctx = new TerracottaManager.WaitContext(future, successPred, resultSupplier);
      pendingWait.set(ctx);
      TerracottaState latest = stateRef.get();
      if (latest instanceof TerracottaState.Fatal) {
         ctx.future.completeExceptionally(new RuntimeException("陶瓦进入致命状态: " + latest));
         clearPendingWait(ctx);
      } else if (latest instanceof TerracottaState.Exception) {
         ctx.future.completeExceptionally(new TerracottaManager.TerracottaExceptionStateException(((TerracottaState.Exception)latest).type));
         clearPendingWait(ctx);
      } else if (successPred.test(latest)) {
         String result = resultSupplier.get();
         if (result != null && !result.isEmpty()) {
            ctx.future.complete(result);
            clearPendingWait(ctx);
         }
      }

      return future.orTimeout(timeoutSec + 5, TimeUnit.SECONDS).whenComplete((r, e) -> {
         if (e != null && e instanceof TimeoutException) {
            LOGGER.warn("Wait for Terracotta {} timeout, last state: {}", actionName, stateRef.get());
         }

         clearPendingWait(ctx);
      });
   }

   public static CompletableFuture<String> joinRoom(String roomCode, String playerName) {
      return !TerracottaBinary.isReady()
         ? CompletableFuture.failedFuture(new TerracottaNotReadyException("陶瓦二进制未就绪, 降级到 VoxLink P2P"))
         : joinRoomAttempt(roomCode, playerName).handle((r, e) -> {
            if (e == null) {
               return CompletableFuture.completedFuture(r);
            }

            Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
            if (!(cause instanceof TerracottaManager.TerracottaExceptionStateException)) {
               return CompletableFuture.failedFuture(e);
            }

            LOGGER.warn("Terracotta joinRoom 遇到可恢复异常状态(type={}), stop进程重启后重试1次", ((TerracottaManager.TerracottaExceptionStateException)cause).stateType);
            return hardRestartForRetry().thenCompose(v -> joinRoomAttempt(roomCode, playerName));
         }).thenCompose(f -> (CompletionStage<String>)f);
   }

   private static CompletableFuture<String> joinRoomAttempt(String roomCode, String playerName) {
      return ensureReady().thenCompose(p -> TerracottaNodeList.fetchForChina().thenCompose(nodes -> {
         LOGGER.info("Terracotta guest inject node list: {} nodes", nodes.size());
         return TerracottaClient.joinRoom(p, roomCode, playerName, (List<URI>)nodes).thenCompose(success -> {
            if (!success) {
               throw new RuntimeException("加入陶瓦房间失败");
            } else {
               return waitForGuestOk(60);
            }
         }).exceptionally(e -> {
            Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
            if (cause instanceof TerracottaClient.TerracottaHttpException httpEx) {
               throw new RuntimeException("加入陶瓦房间失败 " + httpEx.getErrorDetail());
            } else if (cause instanceof RuntimeException) {
               throw (RuntimeException)cause;
            } else {
               throw new RuntimeException(cause);
            }
         });
      }));
   }

   public static CompletableFuture<Void> setIdle() {
      return port == 0 ? CompletableFuture.completedFuture(null) : TerracottaClient.setIdle(port);
   }

   private static CompletableFuture<Void> resetToWaiting() {
      return setIdle().exceptionally(e -> {
         LOGGER.warn("setIdle重置失败, 仍继续重试: {}", e.getMessage());
         return null;
      }).thenRun(() -> {
         TerracottaState.Unknown unknown = new TerracottaState.Unknown();
         unknown.port = port;
         stateRef.set(unknown);
         lastStateJson = null;
      });
   }

   private static CompletableFuture<Void> hardRestartForRetry() {
      failPendingWait("hardRestartForRetry取消等待");
      stateEpoch.incrementAndGet();
      TerracottaProcess.stop();
      stateRef.set(TerracottaState.Launching.INSTANCE);
      initialized = false;
      port = 0;
      lastStateJson = null;
      skipNextIndexGuard = true;
      return initialize().thenApply(p -> null);
   }

   public static void clearLastState() {
      failPendingWait("clearLastState取消等待");
      stateRef.set(new TerracottaState.Unknown());
      lastStateJson = null;
      lastStateEpoch = stateEpoch.get();
      stateFailCount = 0;
   }

   public static JsonObject getLastStateJson() {
      return lastStateJson;
   }

   public static TerracottaState getState() {
      return stateRef.get();
   }

   public static String getRoomCode() {
      TerracottaState state = stateRef.get();
      if (state instanceof TerracottaState.HostOK) {
         return ((TerracottaState.HostOK)state).code;
      } else {
         return lastStateJson != null ? TerracottaClient.getRoomCode(lastStateJson) : null;
      }
   }

   public static String getConnectUrl() {
      TerracottaState state = stateRef.get();
      if (state instanceof TerracottaState.GuestOK) {
         return ((TerracottaState.GuestOK)state).url;
      } else {
         return lastStateJson != null ? TerracottaClient.getConnectUrl(lastStateJson) : null;
      }
   }

   public static boolean isHostOk() {
      return stateRef.get() instanceof TerracottaState.HostOK;
   }

   public static boolean isGuestOk() {
      return stateRef.get() instanceof TerracottaState.GuestOK;
   }

   public static boolean isException() {
      return stateRef.get() instanceof TerracottaState.Exception;
   }

   public static String getExceptionType() {
      TerracottaState state = stateRef.get();
      return state instanceof TerracottaState.Exception ? ((TerracottaState.Exception)state).type : "UNKNOWN";
   }

   public static boolean isFatal() {
      return stateRef.get() instanceof TerracottaState.Fatal;
   }

   public static boolean isReady() {
      return initialized && port != 0 && TerracottaProcess.isAlive();
   }

   public static boolean isBinaryReady() {
      return TerracottaBinary.isReady();
   }

   public static void startDownload(Consumer<TerracottaBinary.DownloadProgress> callback) {
      startBackgroundDownload(callback);
   }

   public static boolean isDownloading() {
      return downloading.get();
   }

   public static boolean isDownloadFailed() {
      return downloadFailed;
   }

   public static TerracottaBinary.DownloadProgress getLastProgress() {
      return lastProgress;
   }

   public static void shutdown() {
      synchronized (POLL_LOCK) {
         if (pollTask != null) {
            pollTask.cancel(false);
            pollTask = null;
         }

         if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
         }
      }

      TerracottaBinary.cancelDownload();
      synchronized (TerracottaManager.class) {
         if (downloadExecutor != null) {
            downloadExecutor.shutdownNow();

            try {
               downloadExecutor.awaitTermination(2L, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
               Thread.currentThread().interrupt();
            }

            downloadExecutor = null;
         }
      }

      CompletableFuture<Boolean> dc = downloadCompletion;
      if (dc != null && !dc.isDone()) {
         dc.complete(false);
      }

      downloadCompletion = null;
      downloading.set(false);
      downloadFailed = false;
      lastProgress = null;
      TerracottaBinary.resetDownloadFlags();
      TerracottaProcess.stop();
      port = 0;
      initialized = false;
      stateRef.set(TerracottaState.Bootstrap.INSTANCE);
      lastStateJson = null;
      stateFailCount = 0;
      skipNextIndexGuard = false;
      uiStateCallback = null;
      failPendingWait("shutdown取消等待");
      pendingWait.set(null);
   }

   public static final class TerracottaExceptionStateException extends RuntimeException {
      public final String stateType;

      public TerracottaExceptionStateException(String stateType) {
         super("陶瓦进入异常状态: " + stateType);
         this.stateType = stateType;
      }
   }

   private static final class WaitContext {
      final CompletableFuture<String> future;
      final Predicate<TerracottaState> predicate;
      final Supplier<String> supplier;

      WaitContext(CompletableFuture<String> future, Predicate<TerracottaState> predicate, Supplier<String> supplier) {
         this.future = future;
         this.predicate = predicate;
         this.supplier = supplier;
      }
   }
}
