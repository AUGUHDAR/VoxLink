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
    //debounce recover后第一次index守卫跳过 进程重启后index归零 防止旧current.index拒绝新next
    private static volatile boolean skipNextIndexGuard = false;

    private static final AtomicBoolean downloading = new AtomicBoolean(false);
    private static volatile boolean downloadFailed = false;
    private static volatile TerracottaBinary.DownloadProgress lastProgress = null;
    private static final int MAX_DOWNLOAD_ATTEMPTS = 60;
    private static volatile ExecutorService downloadExecutor = null;
    //debounce 下载完成future startDualP2P等待用
    private static volatile CompletableFuture<Boolean> downloadCompletion = null;

    //debounce UI状态回调 让AttemptingJoinScreen看到中间态(HostScanning/GuestConnecting等)
    private static volatile Consumer<TerracottaState> uiStateCallback = null;

    //debounce 一次性等待上下文 替代waitForState独立轮询 listener模式
    //updateState检测到目标态/Fatal/Exception时由notifyPendingWait完成future
    private static final AtomicReference<WaitContext> pendingWait = new AtomicReference<>(null);

    private static final class WaitContext {
        final CompletableFuture<String> future;
        final java.util.function.Predicate<TerracottaState> predicate;
        final java.util.function.Supplier<String> supplier;
        WaitContext(CompletableFuture<String> future,
                    java.util.function.Predicate<TerracottaState> predicate,
                    java.util.function.Supplier<String> supplier) {
            this.future = future;
            this.predicate = predicate;
            this.supplier = supplier;
        }
    }

    private TerracottaManager() {}

    public static void setUiStateCallback(Consumer<TerracottaState> cb) { uiStateCallback = cb; }
    public static void clearUiStateCallback() { uiStateCallback = null; }

    public static void resumeDownloadIfPending() {
        if (!TerracottaBinary.isPlatformSupported()) {
            LOGGER.info("当前平台不支持陶瓦, 跳过");
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
                CompletableFuture<Boolean> dc = downloadCompletion;
                if (dc != null && !dc.isDone()) {
                    dc.complete(TerracottaBinary.isReady());
                }
            }
        });
    }

    //debounce 等待下载完成 startDualP2P在下载进行中时调用
    public static CompletableFuture<Boolean> waitForDownload() {
        if (!downloading.get()) {
            return CompletableFuture.completedFuture(TerracottaBinary.isReady());
        }
        CompletableFuture<Boolean> dc = downloadCompletion;
        if (dc == null) {
            return CompletableFuture.completedFuture(TerracottaBinary.isReady());
        }
        return dc;
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

        //debounce 预检 二进制不存在/校验失败直接降级 避免拉起进程才发现
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
                //debounce fix: 用set替代compareAndSet(stateRef.get(), unknown) 后者存在TOCTOU竞态
                stateRef.set(unknown);
                startPolling();
                return TerracottaClient.getMeta(p).thenApply(meta -> p);
            });
    }

    //debounce 状态预检+initialize Fatal可恢复先recover 非Waiting的Ready先setIdle 避免带旧状态发请求
    private static CompletableFuture<Integer> ensureReady() {
        TerracottaState cur = stateRef.get();
        if (cur instanceof TerracottaState.Fatal && ((TerracottaState.Fatal) cur).isRecoverable()) {
            LOGGER.info("陶瓦处于可恢复Fatal, 开新会话前先recover");
            return recover();
        }
        if (cur instanceof TerracottaState.Ready && !(cur instanceof TerracottaState.Waiting)) {
            LOGGER.info("陶瓦处于{} 开新会话前先setIdle清理", cur);
            return setIdle().exceptionally(e -> {
                    LOGGER.warn("setIdle失败 继续尝试: {}", e.getMessage());
                    return null;
                }).thenCompose(v -> {
                    //debounce 清旧Ready状态防waitForState预检失败(Exception残留)或index守卫拒绝
                    TerracottaState.Unknown unknown = new TerracottaState.Unknown();
                    unknown.port = port;
                    stateRef.set(unknown);
                    lastStateJson = null;
                    return initialize();
                });
        }
        return initialize();
    }

    //debounce recover 可恢复Fatal时重新拉起进程1次
    public static CompletableFuture<Integer> recover() {
        TerracottaState current = stateRef.get();
        if (!(current instanceof TerracottaState.Fatal) || !((TerracottaState.Fatal) current).isRecoverable()) {
            LOGGER.info("当前状态{}不可恢复 跳过recover", current);
            return CompletableFuture.failedFuture(new RuntimeException("不可恢复的致命错误: " + current));
        }
        LOGGER.info("陶瓦进入可恢复Fatal 尝试recover");
        //debounce 失败pending wait 避免recover后旧wait残留
        failPendingWait("陶瓦recover取消等待");
        //debounce 重置epoch+lastState 让recover前未完成的poll响应自动失效
        stateEpoch.incrementAndGet();
        clearLastState();
        //debounce 进程重启后index归零 跳过下一次index守卫 避免旧current.index拒绝新next
        skipNextIndexGuard = true;
        //debounce 重置状态机 进程残留先stop
        TerracottaProcess.stop();
        stateRef.set(TerracottaState.Launching.INSTANCE);
        initialized = false;
        port = 0;
        return initialize();
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
                if (port <= 0 || !TerracottaProcess.isAlive()) {
                    //debounce 进程死了 立即失败pending wait 不空等到超时
                    String errLine = TerracottaProcess.getLastErrorLine();
                    failPendingWait("陶瓦进程意外退出" + (errLine != null ? ": " + errLine : ""));
                    return;
                }
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
                                    notifyPendingWait(fatal);
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
        //debounce index单调守卫 旧响应不覆盖新状态
        if (current instanceof TerracottaState.Ready) {
            int currentIndex = ((TerracottaState.Ready) current).index;
            if (next.index <= currentIndex && next.index >= 0) {
                //debounce recover后第一次index守卫跳过 进程重启后index归零
                if (skipNextIndexGuard) {
                    skipNextIndexGuard = false;
                    LOGGER.info("陶瓦recover后跳过index守卫: current={}, next={}", currentIndex, next.index);
                } else {
                    return;
                }
            }
        }
        if (stateRef.compareAndSet(current, next)) {
            lastStateJson = json;
            lastStateEpoch = expectedEpoch;
            if (!current.name().equals(next.name())) {
                LOGGER.info("陶瓦状态: {} -> {}", current, next);
            }
            //debounce 通知pending wait 单listener模式替代双轮询
            notifyPendingWait(next);
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

    //debounce 由updateState调用 检测目标态/Fatal/Exception完成pending wait future
    private static void notifyPendingWait(TerracottaState state) {
        WaitContext ctx = pendingWait.get();
        if (ctx == null || ctx.future.isDone()) return;
        if (state instanceof TerracottaState.Fatal) {
            ctx.future.completeExceptionally(new RuntimeException("陶瓦进入致命状态: " + state));
            clearPendingWait(ctx);
            return;
        }
        if (state instanceof TerracottaState.Exception) {
            ctx.future.completeExceptionally(new RuntimeException("陶瓦进入异常状态: " + ((TerracottaState.Exception) state).type));
            clearPendingWait(ctx);
            return;
        }
        if (ctx.predicate.test(state)) {
            String result = ctx.supplier.get();
            if (result != null && !result.isEmpty()) {
                ctx.future.complete(result);
                clearPendingWait(ctx);
            }
        }
    }

    //debounce 进程死亡/手动取消时直接失败pending wait
    private static void failPendingWait(String reason) {
        WaitContext ctx = pendingWait.get();
        if (ctx == null || ctx.future.isDone()) return;
        ctx.future.completeExceptionally(new RuntimeException(reason));
        clearPendingWait(ctx);
    }

    //debounce CAS清理 避免清掉新注册的wait
    private static void clearPendingWait(WaitContext expected) {
        if (expected != null) pendingWait.compareAndSet(expected, null);
    }

    public static CompletableFuture<String> createRoom(String playerName) {
        if (!TerracottaBinary.isReady()) {
            return CompletableFuture.failedFuture(new TerracottaNotReadyException("陶瓦二进制未就绪, 降级到 VoxLink P2P"));
        }
        //debounce ensureReady预检状态+initialize 注入节点列表让中国大陆用户用CN节点
        return ensureReady().thenCompose(p ->
            TerracottaNodeList.fetchForChina().thenCompose(nodes -> {
                LOGGER.info("陶瓦host注入节点列表: {} 个", nodes.size());
                return TerracottaClient.startHost(p, playerName, nodes)
                    .thenCompose(v -> waitForRoomCode(ROOM_CODE_TIMEOUT_SEC));
            }));
    }

    private static CompletableFuture<String> waitForRoomCode(int timeoutSec) {
        //debounce 抽共用waitForState host侧等待HostOK 取roomCode
        return waitForState(
            state -> state instanceof TerracottaState.HostOK,
            TerracottaManager::getRoomCode,
            timeoutSec,
            "房间号");
    }

    public static CompletableFuture<String> waitForGuestOk(int timeoutSec) {
        //debounce 抽共用waitForState guest侧等待GuestOK 取connectUrl
        return waitForState(
            state -> state instanceof TerracottaState.GuestOK,
            TerracottaManager::getConnectUrl,
            timeoutSec,
            "连接");
    }

    //debounce listener模式 由updateState在状态变更时通知
    //不再起独立轮询任务 单一startPolling是状态更新唯一来源
    private static CompletableFuture<String> waitForState(
            java.util.function.Predicate<TerracottaState> successPred,
            java.util.function.Supplier<String> resultSupplier,
            int timeoutSec,
            String actionName) {
        //debounce 递增epoch+设lastStateEpoch=myEpoch 拒绝旧epoch的poll 避免旧状态污染
        lastStateJson = null;
        final long myEpoch = stateEpoch.incrementAndGet();
        lastStateEpoch = myEpoch;
        //debounce 预检Fatal/Exception 立即失败不等poll
        TerracottaState cur = stateRef.get();
        if (cur instanceof TerracottaState.Fatal) {
            return CompletableFuture.failedFuture(new RuntimeException("陶瓦进入致命状态: " + cur));
        }
        if (cur instanceof TerracottaState.Exception) {
            return CompletableFuture.failedFuture(new RuntimeException("陶瓦进入异常状态: " + getExceptionType()));
        }
        //debounce 取消前一个pending wait(理论上不会并发 防御性清理)
        WaitContext prev = pendingWait.getAndSet(null);
        if (prev != null && !prev.future.isDone()) {
            prev.future.cancel(true);
        }
        CompletableFuture<String> future = new CompletableFuture<>();
        final WaitContext ctx = new WaitContext(future, successPred, resultSupplier);
        pendingWait.set(ctx);
        //debounce 注册后重检状态 防止注册前状态已变更导致notifyPendingWait漏通知 30s白等
        //complete/completeExceptionally幂等 已完成则no-op clearPendingWait用CAS也安全
        TerracottaState latest = stateRef.get();
        if (latest instanceof TerracottaState.Fatal) {
            ctx.future.completeExceptionally(new RuntimeException("陶瓦进入致命状态: " + latest));
            clearPendingWait(ctx);
        } else if (latest instanceof TerracottaState.Exception) {
            ctx.future.completeExceptionally(new RuntimeException("陶瓦进入异常状态: " + ((TerracottaState.Exception) latest).type));
            clearPendingWait(ctx);
        } else if (successPred.test(latest)) {
            String result = resultSupplier.get();
            if (result != null && !result.isEmpty()) {
                ctx.future.complete(result);
                clearPendingWait(ctx);
            }
        }
        //debounce 超时+清理 timeout由orTimeout驱动 进程死/Fatal由notifyPendingWait/failPendingWait驱动
        return future.orTimeout(timeoutSec + TIMEOUT_MARGIN_SEC, TimeUnit.SECONDS)
            .whenComplete((r, e) -> {
                if (e != null && e instanceof java.util.concurrent.TimeoutException) {
                    LOGGER.warn("等待陶瓦{}超时 最后状态: {}", actionName, stateRef.get());
                }
                clearPendingWait(ctx);
            });
    }

    public static CompletableFuture<String> joinRoom(String roomCode, String playerName) {
        if (!TerracottaBinary.isReady()) {
            return CompletableFuture.failedFuture(new TerracottaNotReadyException("陶瓦二进制未就绪, 降级到 VoxLink P2P"));
        }
        //debounce ensureReady预检状态+initialize 注入节点列表 让HTTP状态码自然传播
        return ensureReady().thenCompose(p ->
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
        if (port <= 0) return CompletableFuture.completedFuture(null);
        return TerracottaClient.setIdle(port);
    }

    //debounce 清旧状态+epoch 防止下次poll立即返回旧结果
    public static void clearLastState() {
        //debounce 失败pending wait ConnectionManager终止陶瓦侧时调用
        failPendingWait("clearLastState取消等待");
        stateRef.set(new TerracottaState.Unknown());
        lastStateJson = null;
        //debounce 用当前epoch而非-1 拒绝旧epoch的in-flight poll 防止旧状态污染
        lastStateEpoch = stateEpoch.get();
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

    //debounce 异常类型 对齐1.0.6 返回String 让UI走Component.translatable
    public static String getExceptionType() {
        TerracottaState state = stateRef.get();
        if (state instanceof TerracottaState.Exception) return ((TerracottaState.Exception) state).type;
        return "UNKNOWN";
    }

    public static boolean isFatal() {
        return stateRef.get() instanceof TerracottaState.Fatal;
    }

    public static boolean isReady() {
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
        skipNextIndexGuard = false;
        uiStateCallback = null;
        //debounce 清理pending wait
        failPendingWait("shutdown取消等待");
        pendingWait.set(null);
    }
}
