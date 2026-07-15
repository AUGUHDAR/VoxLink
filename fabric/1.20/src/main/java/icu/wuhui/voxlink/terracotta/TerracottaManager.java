package icu.wuhui.voxlink.terracotta;

import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class TerracottaManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("voxlink-terracotta");
    private static final long POLL_INTERVAL_MS = 500;
    private static final int INIT_POLL_CYCLES = 50;
    private static final int INIT_POLL_MS = 100;
    private static final int ROOM_CODE_TIMEOUT_SEC = 30;
    private static final int TIMEOUT_MARGIN_SEC = 5;

    private static volatile boolean initialized = false;
    private static volatile int port = 0;
    private static final AtomicReference<JsonObject> lastState = new AtomicReference<>();
    private static final AtomicReference<Consumer<JsonObject>> stateListener = new AtomicReference<>();
    private static ScheduledExecutorService scheduler;
    private static ScheduledFuture<?> pollTask;
    private static final Object POLL_LOCK = new Object();

    private static final AtomicBoolean downloading = new AtomicBoolean(false);
    private static volatile boolean downloadFailed = false;
    private static volatile TerracottaBinary.DownloadProgress lastProgress = null;
    private static final int MAX_DOWNLOAD_ATTEMPTS = 60;
    private static volatile ExecutorService downloadExecutor = null;

    private TerracottaManager() {}

    public static void resumeDownloadIfPending() {
        if (!TerracottaBinary.isPlatformSupported()) {
            LOGGER.info("当前平台不支持陶瓦, 跳过");
            return;
        }
        if (TerracottaBinary.isAndroid()) {
            if (TerracottaAndroidBridge.isLibraryPresent()) {
                LOGGER.info("安卓检测到陶瓦库, 等待启动器初始化");
            } else {
                LOGGER.info("安卓未检测到陶瓦库, 请使用支持陶瓦的启动器 (FCL/HMCL)");
            }
            return;
        }
        if (TerracottaBinary.isReady()) return;
        if (!TerracottaBinary.isDownloadPending()) return;
        LOGGER.info("检测到未完成的陶瓦下载, 自动恢复");
        startBackgroundDownload(null);
    }

    private static void startBackgroundDownload(Consumer<TerracottaBinary.DownloadProgress> uiCallback) {
        if (!downloading.compareAndSet(false, true)) return;
        if (!TerracottaBinary.isPlatformSupported()) { downloading.set(false); return; }
        if (TerracottaBinary.isAndroid()) { downloading.set(false); return; }
        downloadFailed = false;
        lastProgress = null;
        TerracottaBinary.resetDownloadFlags();
        TerracottaBinary.markDownloadPending();
        synchronized (TerracottaManager.class) {
            if (downloadExecutor == null || downloadExecutor.isShutdown()) {
                downloadExecutor = Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "terracotta-download");
                    t.setDaemon(true);
                    return t;
                });
            }
        }
        final ExecutorService executor = downloadExecutor;
        executor.submit(() -> {
            try {
                int attempt = 0;
                while (!TerracottaBinary.isReady() && !Thread.currentThread().isInterrupted() && attempt < MAX_DOWNLOAD_ATTEMPTS) {
                    attempt++;
                    if (TerracottaBinary.isDownloadCancelled()) break;
                    try {
                        TerracottaBinary.downloadAsync(progress -> {
                            lastProgress = progress;
                            if (progress.done) {
                                downloadFailed = false;
                            } else if (progress.failed) {
                                downloadFailed = true;
                            }
                            if (uiCallback != null) uiCallback.accept(progress);
                        }).join();
                    } catch (Exception e) {
                        if (TerracottaBinary.isDownloadCancelled()) break;
                        LOGGER.warn("陶瓦下载失败, 5秒后重试 (尝试 {}): {}", attempt, e.getMessage(), e);
                        downloadFailed = true;
                    }
                    if (TerracottaBinary.isReady()) break;
                    if (TerracottaBinary.isDownloadCancelled()) break;
                    if (Thread.currentThread().isInterrupted()) break;
                    if (attempt >= MAX_DOWNLOAD_ATTEMPTS) {
                        LOGGER.error("陶瓦下载已达最大重试次数 {}, 停止", MAX_DOWNLOAD_ATTEMPTS);
                        downloadFailed = true;
                        break;
                    }
                    for (int i = 0; i < INIT_POLL_CYCLES && !TerracottaBinary.isDownloadCancelled(); i++) {
                        try { Thread.sleep(INIT_POLL_MS); } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
                if (TerracottaBinary.isReady()) {
                    TerracottaBinary.clearDownloadPending();
                    LOGGER.info("陶瓦后台下载完成");
                    downloadFailed = false;
                } else if (TerracottaBinary.isDownloadCancelled()) {
                    TerracottaBinary.clearDownloadPending();
                    LOGGER.info("陶瓦下载已取消");
                    downloadFailed = false;
                }
            } finally {
                TerracottaBinary.resetDownloadFlags();
                downloading.set(false);
            }
        });
    }

    public static void pauseDownload() { TerracottaBinary.pauseDownload(); }
    public static void resumeDownload() { TerracottaBinary.resumeDownload(); }
    public static void cancelDownload() {
        TerracottaBinary.cancelDownload();
        TerracottaBinary.clearDownloadPending();
    }
    public static boolean isDownloadPaused() { return TerracottaBinary.isDownloadPaused(); }
    public static boolean isDownloadCancelled() { return TerracottaBinary.isDownloadCancelled(); }

    public static CompletableFuture<Integer> initialize() {
        if (initialized && port > 0 && TerracottaProcess.isAlive()) {
            return CompletableFuture.completedFuture(port);
        }

        if (TerracottaAndroidBridge.isAndroid()) {
            if (TerracottaAndroidBridge.isInitialized()) {
                initialized = true;
                startPollingAndroid();
                LOGGER.info("安卓陶瓦已由启动器初始化, 使用反射调用");
                return CompletableFuture.completedFuture(0);
            } else {
                LOGGER.warn("安卓陶瓦未初始化, 请在启动器中启用陶瓦");
                return CompletableFuture.failedFuture(new TerracottaNotReadyException(
                    "安卓陶瓦未初始化, 请使用支持陶瓦的启动器 (FCL/HMCL) 并在其中启用陶瓦"));
            }
        }

        return TerracottaProcess.start()
            .exceptionally(e -> {
                Throwable cause = (e instanceof CompletionException && e.getCause() != null) ? e.getCause() : e;
                if (cause instanceof TerracottaNotReadyException) {
                    LOGGER.info("陶瓦二进制未就绪，跳过初始化");
                    throw (TerracottaNotReadyException) cause;
                }
                if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                throw new RuntimeException(cause);
            })
            .thenCompose(p -> {
                port = p;
                initialized = true;
                startPolling();
                return TerracottaClient.getMeta(p).thenApply(meta -> p);
            });
    }

    private static void startPollingAndroid() {
        synchronized (POLL_LOCK) {
            if (scheduler == null || scheduler.isShutdown()) {
                scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "terracotta-android-poll");
                    t.setDaemon(true);
                    return t;
                });
            }
            if (pollTask != null) pollTask.cancel(false);
            pollTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                String stateJson = TerracottaAndroidBridge.getState();
                if (stateJson != null && !stateJson.isEmpty()) {
                    JsonObject stateObj = com.google.gson.JsonParser.parseString(stateJson).getAsJsonObject();
                    JsonObject prev = lastState.getAndSet(stateObj);
                    boolean changed = prev == null
                        || !stateObj.has("index")
                        || !prev.has("index")
                        || !stateObj.get("index").equals(prev.get("index"));
                    if (changed) {
                        Consumer<JsonObject> listener = stateListener.get();
                        if (listener != null) listener.accept(stateObj);
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("安卓状态轮询异常: {}", e.getMessage());
            }
        }, 0, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
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
            if (pollTask != null) pollTask.cancel(false);
            pollTask = scheduler.scheduleAtFixedRate(() -> {
                if (port <= 0 || !TerracottaProcess.isAlive()) return;
                try {
                    TerracottaClient.getState(port)
                        .thenAccept(state -> {
                            JsonObject prev = lastState.getAndSet(state);
                            boolean changed = prev == null
                                || !state.has("index")
                                || !prev.has("index")
                                || !state.get("index").equals(prev.get("index"));
                            if (changed) {
                                Consumer<JsonObject> listener = stateListener.get();
                                if (listener != null) listener.accept(state);
                            }
                        })
                        .exceptionally(e -> {
                            LOGGER.debug("陶瓦状态轮询失败: {}", e.getMessage());
                            return null;
                        });
                } catch (Exception e) {
                    LOGGER.debug("陶瓦状态轮询异常: {}", e.getMessage());
                }
            }, 0, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
        }
    }

    public static void setStateListener(Consumer<JsonObject> listener) {
        stateListener.set(listener);
    }

    public static JsonObject getCurrentState() {
        return lastState.get();
    }

    public static CompletableFuture<String> createRoom(String playerName) {
        if (!TerracottaBinary.isReady()) {
            return CompletableFuture.failedFuture(new TerracottaNotReadyException("陶瓦二进制未就绪, 降级到 VoxLink P2P"));
        }
        if (TerracottaAndroidBridge.isAndroid()) {
            return initialize().thenCompose(v -> {
                TerracottaAndroidBridge.setScanning(null, playerName);
                return waitForRoomCode(ROOM_CODE_TIMEOUT_SEC);
            });
        }
        //debounce 总是先initialize 确保进程活着 不用残留port
        return initialize().thenCompose(p ->
            TerracottaClient.startHost(p, playerName)
                .thenCompose(v -> waitForRoomCode(ROOM_CODE_TIMEOUT_SEC)));
    }

    private static CompletableFuture<String> waitForRoomCode(int timeoutSec) {
        //debounce 清除上次残留状态 防止立即返回旧房间号
        lastState.set(null);
        CompletableFuture<String> future = new CompletableFuture<>();
        long deadline = System.currentTimeMillis() + (long) timeoutSec * 1000L;
        ScheduledFuture<?>[] poll = new ScheduledFuture<?>[1];
        synchronized (POLL_LOCK) {
            poll[0] = scheduler.scheduleAtFixedRate(() -> {
                try {
                    if (isHostOk()) {
                        String code = getRoomCode();
                        if (code != null && !code.isEmpty()) {
                            poll[0].cancel(false);
                            future.complete(code);
                            return;
                        }
                    }
                    if (isException()) {
                        poll[0].cancel(false);
                        future.completeExceptionally(new RuntimeException("陶瓦进入异常状态"));
                    }
                    if (System.currentTimeMillis() > deadline) {
                        poll[0].cancel(false);
                        future.completeExceptionally(new RuntimeException("等待陶瓦房间号超时"));
                    }
                } catch (Exception e) {
                    poll[0].cancel(false);
                    future.completeExceptionally(e);
                }
            }, POLL_INTERVAL_MS, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
        }
        return future.orTimeout(timeoutSec + TIMEOUT_MARGIN_SEC, TimeUnit.SECONDS);
    }

    public static CompletableFuture<String> waitForGuestOk(int timeoutSec) {
        //debounce 清除上次残留状态 防止立即返回旧connectUrl
        lastState.set(null);
        CompletableFuture<String> future = new CompletableFuture<>();
        long deadline = System.currentTimeMillis() + (long) timeoutSec * 1000L;
        ScheduledFuture<?>[] poll = new ScheduledFuture<?>[1];
        synchronized (POLL_LOCK) {
            poll[0] = scheduler.scheduleAtFixedRate(() -> {
                try {
                    if (isGuestOk()) {
                        poll[0].cancel(false);
                        future.complete(getConnectUrl());
                        return;
                    }
                    if (isException()) {
                        poll[0].cancel(false);
                        future.completeExceptionally(new RuntimeException("陶瓦进入异常状态"));
                    }
                    if (System.currentTimeMillis() > deadline) {
                        poll[0].cancel(false);
                        future.completeExceptionally(new RuntimeException("等待陶瓦连接超时"));
                    }
                } catch (Exception e) {
                    poll[0].cancel(false);
                    future.completeExceptionally(e);
                }
            }, POLL_INTERVAL_MS, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
        }
        return future.orTimeout(timeoutSec + TIMEOUT_MARGIN_SEC, TimeUnit.SECONDS);
    }

    public static CompletableFuture<String> joinRoom(String roomCode, String playerName) {
        if (!TerracottaBinary.isReady()) {
            return CompletableFuture.failedFuture(new TerracottaNotReadyException("陶瓦二进制未就绪, 降级到 VoxLink P2P"));
        }
        if (TerracottaAndroidBridge.isAndroid()) {
            //debounce 安卓等待guest-ok
            return initialize().thenCompose(v -> {
                TerracottaAndroidBridge.setGuesting(roomCode, playerName);
                return waitForGuestOk(ROOM_CODE_TIMEOUT_SEC);
            });
        }
        //debounce 总是先initialize 确保进程活着 检查joinRoom返回值
        return initialize().thenCompose(p ->
            TerracottaClient.joinRoom(p, roomCode, playerName).thenCompose(success -> {
                if (!success) throw new RuntimeException("加入陶瓦房间失败");
                return waitForGuestOk(ROOM_CODE_TIMEOUT_SEC);
            }));
    }

    public static CompletableFuture<Void> setIdle() {
        if (TerracottaAndroidBridge.isAndroid()) {
            if (!TerracottaAndroidBridge.setWaiting()) {
                return CompletableFuture.failedFuture(new RuntimeException("安卓陶瓦 setWaiting 失败"));
            }
            return CompletableFuture.completedFuture(null);
        }
        if (port <= 0) return CompletableFuture.completedFuture(null);
        return TerracottaClient.setIdle(port);
    }

    public static String getRoomCode() {
        JsonObject state = lastState.get();
        if (state == null) return null;
        return TerracottaClient.getRoomCode(state);
    }

    public static String getConnectUrl() {
        JsonObject state = lastState.get();
        if (state == null) return null;
        return TerracottaClient.getConnectUrl(state);
    }

    public static boolean isHostOk() {
        JsonObject state = lastState.get();
        return state != null && "host-ok".equals(TerracottaClient.getStateName(state));
    }

    public static boolean isGuestOk() {
        JsonObject state = lastState.get();
        return state != null && "guest-ok".equals(TerracottaClient.getStateName(state));
    }

    public static boolean isException() {
        JsonObject state = lastState.get();
        return state != null && "exception".equals(TerracottaClient.getStateName(state));
    }

    public static int getExceptionType() {
        JsonObject state = lastState.get();
        if (state == null || !state.has("type")) return -1;
        return state.get("type").getAsInt();
    }

    public static int getPort() { return port; }

    public static boolean isReady() {
        if (TerracottaAndroidBridge.isInitialized()) return true;
        return initialized && port > 0 && TerracottaProcess.isAlive();
    }

    public static boolean isBinaryReady() {
        return TerracottaBinary.isReady();
    }

    public static void startDownload(Consumer<TerracottaBinary.DownloadProgress> callback) {
        startBackgroundDownload(callback);
    }

    public static boolean isDownloading() { return downloading.get(); }
    public static boolean isDownloadFailed() { return downloadFailed; }
    public static TerracottaBinary.DownloadProgress getLastProgress() { return lastProgress; }

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
                downloadExecutor = null;
            }
        }
        downloading.set(false);
        downloadFailed = false;
        lastProgress = null;
        TerracottaBinary.resetDownloadFlags();
        TerracottaProcess.stop();
        port = 0;
        initialized = false;
        lastState.set(null);
    }
}
