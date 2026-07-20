package icu.wuhui.voxlink.terracotta;

import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
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
    //debounce 连续拉取状态失败次数 达阈值切Fatal(TERRACOTTA)
    private static final int MAX_STATE_FAIL_COUNT = 3;

    private static volatile boolean initialized = false;
    private static volatile int port = 0;
    //debounce 状态机替代原JsonObject 类型安全+中间态可见
    private static final AtomicReference<TerracottaState> stateRef = new AtomicReference<>(TerracottaState.Bootstrap.INSTANCE);
    //debounce 兼容旧API 保留lastState JsonObject 派生自stateRef 供外部读取
    private static volatile JsonObject lastStateJson = null;
    //debounce 状态epoch 防止旧poll覆盖新状态(如重试场景)
    private static final java.util.concurrent.atomic.AtomicLong stateEpoch = new java.util.concurrent.atomic.AtomicLong(0);
    private static volatile long lastStateEpoch = -1;
    private static ScheduledExecutorService scheduler;
    private static ScheduledFuture<?> pollTask;
    private static final Object POLL_LOCK = new Object();
    //debounce 连续拉取失败计数
    private static volatile int stateFailCount = 0;

    private static final AtomicBoolean downloading = new AtomicBoolean(false);
    private static volatile boolean downloadFailed = false;
    private static volatile TerracottaBinary.DownloadProgress lastProgress = null;
    private static final int MAX_DOWNLOAD_ATTEMPTS = 60;
    private static volatile ExecutorService downloadExecutor = null;

    //debounce UI状态回调 让AttemptingJoinScreen看到中间态(HostScanning/GuestConnecting等)
    private static volatile Consumer<TerracottaState> uiStateCallback = null;

    private TerracottaManager() {}

    public static void setUiStateCallback(Consumer<TerracottaState> cb) { uiStateCallback = cb; }
    public static void clearUiStateCallback() { uiStateCallback = null; }

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

        //debounce 桌面端预检 二进制不存在/校验失败直接降级 避免拉起进程才发现
        if (!TerracottaBinary.verifyInstallation()) {
            LOGGER.warn("陶瓦安装自检失败, 降级到 VoxLink P2P");
            return CompletableFuture.failedFuture(new TerracottaNotReadyException("陶瓦安装自检失败"));
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
                //debounce 进入Launching状态 端口已知后切Unknown
                TerracottaState.Unknown unknown = new TerracottaState.Unknown();
                unknown.port = p;
                stateRef.compareAndSet(stateRef.get(), unknown);
                startPolling();
                return TerracottaClient.getMeta(p).thenApply(meta -> p);
            });
    }

    //debounce recover 可恢复Fatal时重新拉起进程1次
    public static CompletableFuture<Integer> recover() {
        TerracottaState current = stateRef.get();
        if (!(current instanceof TerracottaState.Fatal) || !((TerracottaState.Fatal) current).isRecoverable()) {
            LOGGER.info("当前状态{}不可恢复 跳过recover", current);
            return CompletableFuture.failedFuture(new RuntimeException("不可恢复的致命错误: " + current));
        }
        LOGGER.info("陶瓦进入可恢复Fatal 尝试recover");
        //debounce 重置epoch+lastState 让recover前未完成的poll响应自动失效
        stateEpoch.incrementAndGet();
        clearLastState();
        //debounce 重置状态机 进程残留先stop
        TerracottaProcess.stop();
        stateRef.set(TerracottaState.Launching.INSTANCE);
        initialized = false;
        port = 0;
        return initialize();
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
                    JsonObject json = com.google.gson.JsonParser.parseString(stateJson).getAsJsonObject();
                    //debounce 安卓侧也用当前epoch 防止新epoch守卫拒绝状态更新
                    updateState(json, stateEpoch.get());
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
                final long epoch = stateEpoch.get();
                try {
                    TerracottaClient.getState(port)
                        .thenAccept(json -> {
                            updateState(json, epoch);
                            stateFailCount = 0;
                        })
                        .exceptionally(e -> {
                            stateFailCount++;
                            //debounce 连续失败达阈值切Fatal(TERRACOTTA)
                            if (stateFailCount >= MAX_STATE_FAIL_COUNT) {
                                TerracottaState prev = stateRef.get();
                                TerracottaState.Fatal fatal = new TerracottaState.Fatal(TerracottaState.Fatal.Type.TERRACOTTA);
                                if (stateRef.compareAndSet(prev, fatal)) {
                                    LOGGER.warn("陶瓦状态拉取连续失败{}次 切Fatal(TERRACOTTA): {}", stateFailCount, e.getMessage());
                                }
                                stateFailCount = 0;
                            } else {
                                LOGGER.debug("陶瓦状态轮询失败({}/{}): {}", stateFailCount, MAX_STATE_FAIL_COUNT, e.getMessage());
                            }
                            return null;
                        });
                } catch (Exception e) {
                    LOGGER.debug("陶瓦状态轮询异常: {}", e.getMessage());
                }
            }, 0, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
        }
    }

    //debounce 更新状态机+epoch单调守卫+index单调守卫+日志
    private static void updateState(JsonObject json, long expectedEpoch) {
        if (json == null) return;
        //debounce 旧epoch响应不覆盖新状态 防止切Unknown后被旧poll污染导致waitForRoomCode超时
        if (expectedEpoch < lastStateEpoch) return;
        TerracottaState current = stateRef.get();
        int currentPort = port;
        if (current instanceof TerracottaState.PortSpecific) {
            currentPort = ((TerracottaState.PortSpecific) current).port;
            if (currentPort == 0) currentPort = port;
        }
        TerracottaState.Ready next = TerracottaState.parseFromState(json, currentPort);
        //debounce index单调守卫 旧响应不覆盖新状态 对齐HMCL
        if (current instanceof TerracottaState.Ready) {
            int currentIndex = ((TerracottaState.Ready) current).index;
            if (next.index <= currentIndex && next.index >= 0) {
                return;
            }
        }
        if (stateRef.compareAndSet(current, next)) {
            lastStateJson = json;
            lastStateEpoch = expectedEpoch;
            if (!current.name().equals(next.name())) {
                LOGGER.info("陶瓦状态: {} -> {}", current, next);
            }
            //debounce 推中间态到UI 调用方负责切主线程
            Consumer<TerracottaState> cb = uiStateCallback;
            if (cb != null && isUiRelevantState(next)) {
                try { cb.accept(next); } catch (Throwable t) { LOGGER.warn("UI状态回调异常: {}", t.getMessage()); }
            }
        }
    }

    //debounce 只推中间态/终态到UI 不推Unknown/Waiting等无意义状态
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
        //debounce 总是先initialize 确保进程活着 注入节点列表让中国大陆用户用CN节点
        return initialize().thenCompose(p ->
            TerracottaNodeList.fetchForChina().thenCompose(nodes -> {
                LOGGER.info("陶瓦host注入节点列表: {} 个", nodes.size());
                return TerracottaClient.startHost(p, playerName, nodes)
                    .thenCompose(v -> waitForRoomCode(ROOM_CODE_TIMEOUT_SEC));
            }));
    }

    private static CompletableFuture<String> waitForRoomCode(int timeoutSec) {
        //debounce 清除上次残留状态+递增epoch 防止立即返回旧房间号
        stateRef.set(new TerracottaState.Unknown());
        lastStateJson = null;
        final long myEpoch = stateEpoch.incrementAndGet();
        CompletableFuture<String> future = new CompletableFuture<>();
        long deadline = System.currentTimeMillis() + (long) timeoutSec * 1000L;
        ScheduledFuture<?>[] poll = new ScheduledFuture<?>[1];
        synchronized (POLL_LOCK) {
            poll[0] = scheduler.scheduleAtFixedRate(() -> {
                try {
                    if (future.isDone()) { poll[0].cancel(false); return; }
                    //debounce 进程崩了立即失败 不空等到超时
                    if (!TerracottaProcess.isAlive()) {
                        poll[0].cancel(false);
                        String errLine = TerracottaProcess.getLastErrorLine();
                        future.completeExceptionally(new RuntimeException("陶瓦进程意外退出" + (errLine != null ? ": " + errLine : "")));
                        return;
                    }
                    //debounce Fatal直接失败
                    TerracottaState cur = stateRef.get();
                    if (cur instanceof TerracottaState.Fatal) {
                        poll[0].cancel(false);
                        future.completeExceptionally(new RuntimeException("陶瓦进入致命状态: " + cur));
                        return;
                    }
                    if (isHostOk() && lastStateEpoch == myEpoch) {
                        String code = getRoomCode();
                        if (code != null && !code.isEmpty()) {
                            poll[0].cancel(false);
                            future.complete(code);
                            return;
                        }
                    }
                    if (isException()) {
                        poll[0].cancel(false);
                        future.completeExceptionally(new RuntimeException("陶瓦进入异常状态: " + getExceptionType()));
                    }
                    if (System.currentTimeMillis() > deadline) {
                        poll[0].cancel(false);
                        future.completeExceptionally(new RuntimeException("等待陶瓦房间号超时 最后状态: " + cur));
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
        //debounce 清除上次残留状态+递增epoch 防止立即返回旧connectUrl
        stateRef.set(new TerracottaState.Unknown());
        lastStateJson = null;
        final long myEpoch = stateEpoch.incrementAndGet();
        CompletableFuture<String> future = new CompletableFuture<>();
        long deadline = System.currentTimeMillis() + (long) timeoutSec * 1000L;
        ScheduledFuture<?>[] poll = new ScheduledFuture<?>[1];
        synchronized (POLL_LOCK) {
            poll[0] = scheduler.scheduleAtFixedRate(() -> {
                try {
                    if (future.isDone()) { poll[0].cancel(false); return; }
                    //debounce 进程崩了立即失败 不空等到超时
                    if (!TerracottaProcess.isAlive()) {
                        poll[0].cancel(false);
                        String errLine = TerracottaProcess.getLastErrorLine();
                        future.completeExceptionally(new RuntimeException("陶瓦进程意外退出" + (errLine != null ? ": " + errLine : "")));
                        return;
                    }
                    TerracottaState cur = stateRef.get();
                    if (cur instanceof TerracottaState.Fatal) {
                        poll[0].cancel(false);
                        future.completeExceptionally(new RuntimeException("陶瓦进入致命状态: " + cur));
                        return;
                    }
                    if (isGuestOk() && lastStateEpoch == myEpoch) {
                        poll[0].cancel(false);
                        future.complete(getConnectUrl());
                        return;
                    }
                    if (isException()) {
                        poll[0].cancel(false);
                        future.completeExceptionally(new RuntimeException("陶瓦进入异常状态: " + getExceptionType()));
                    }
                    if (System.currentTimeMillis() > deadline) {
                        poll[0].cancel(false);
                        future.completeExceptionally(new RuntimeException("等待陶瓦连接超时 最后状态: " + cur));
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
        //debounce 总是先initialize 确保进程活着 注入节点列表 让HTTP状态码自然传播
        return initialize().thenCompose(p ->
            TerracottaNodeList.fetchForChina().thenCompose(nodes -> {
                LOGGER.info("陶瓦guest注入节点列表: {} 个", nodes.size());
                return TerracottaClient.joinRoom(p, roomCode, playerName, nodes)
                    .thenCompose(success -> {
                        if (!success) throw new RuntimeException("加入陶瓦房间失败");
                        return waitForGuestOk(ROOM_CODE_TIMEOUT_SEC);
                    })
                    .exceptionally(e -> {
                        Throwable cause = (e instanceof CompletionException && e.getCause() != null) ? e.getCause() : e;
                        if (cause instanceof TerracottaClient.TerracottaHttpException) {
                            TerracottaClient.TerracottaHttpException httpEx = (TerracottaClient.TerracottaHttpException) cause;
                            throw new RuntimeException("加入陶瓦房间失败 " + httpEx.getErrorDetail());
                        }
                        if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                        throw new RuntimeException(cause);
                    });
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

    //debounce 清旧状态+epoch 防止下次poll立即返回旧结果
    public static void clearLastState() {
        stateRef.set(new TerracottaState.Unknown());
        lastStateJson = null;
        lastStateEpoch = -1;
        stateFailCount = 0;
    }

    //debounce 兼容旧API 保留JsonObject返回
    public static JsonObject getLastStateJson() { return lastStateJson; }
    public static TerracottaState getState() { return stateRef.get(); }

    public static String getRoomCode() {
        TerracottaState state = stateRef.get();
        if (state instanceof TerracottaState.HostOK) return ((TerracottaState.HostOK) state).code;
        //debounce 兼容JsonObject旧调用
        if (lastStateJson != null) return TerracottaClient.getRoomCode(lastStateJson);
        return null;
    }

    public static String getConnectUrl() {
        TerracottaState state = stateRef.get();
        if (state instanceof TerracottaState.GuestOK) return ((TerracottaState.GuestOK) state).url;
        if (lastStateJson != null) return TerracottaClient.getConnectUrl(lastStateJson);
        return null;
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

    //debounce 异常类型 让UI区分
    public static String getExceptionType() {
        TerracottaState state = stateRef.get();
        if (state instanceof TerracottaState.Exception) return ((TerracottaState.Exception) state).type;
        return null;
    }

    public static boolean isFatal() {
        return stateRef.get() instanceof TerracottaState.Fatal;
    }

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
                //debounce 等待下载线程退出 防止与后续deleteBinary竞态写文件
                try { downloadExecutor.awaitTermination(2, TimeUnit.SECONDS); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
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
        stateRef.set(TerracottaState.Bootstrap.INSTANCE);
        lastStateJson = null;
        stateFailCount = 0;
        uiStateCallback = null;
    }
}
