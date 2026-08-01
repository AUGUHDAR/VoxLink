package icu.wuhui.voxlink.terracotta;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

//Terracotta 进程管理: 拉起 + 端口获取 + 关闭
public final class TerracottaProcess {
    private static final Logger LOGGER = LoggerFactory.getLogger("voxlink-terracotta");
    private static final int PORT_WAIT_CYCLES = 60;
    private static final int PORT_POLL_MS = 500;
    private static final int GRACEFUL_SHUTDOWN_SEC = 3;
    //debounce 进程启动总超时 UAC授权可能耗时 设120s兜底防止永久卡死
    private static final long START_TOTAL_TIMEOUT_MS = 120_000;
    private static final AtomicBoolean startingGuard = new AtomicBoolean(false);
    //debounce startingGuard超时兜底 防止supplyAsync内部卡死导致永久占用
    private static volatile long startingSince = 0;
    private static final long STARTING_GUARD_TIMEOUT_MS = 60_000;
    private static final AtomicReference<Process> PROCESS = new AtomicReference<>();
    private static volatile int httpPort = 0;
    private static volatile Path portFile;
    //端口文件端口提取正则
    private static final Pattern PORT_PATTERN = Pattern.compile("\"port\"\\s*:\\s*(\\d+)");
    //debounce secondary模式stdout端口提取 同机joiner复用primary的HTTP端口
    private static final Pattern SECONDARY_PORT_PATTERN = Pattern.compile("secondary mode[^0-9]*(\\d+)");
    //debounce 最近一次错误行 启动失败时包装到异常message让用户看到真正原因
    private static volatile String lastErrorLine = null;
    //debounce 进程崩溃自动重启1次 避免无限循环
    private static final AtomicBoolean restartUsed = new AtomicBoolean(false);
    //debounce secondary模式标志 同机joiner不拥有进程 复用primary端口
    private static volatile boolean isSecondary = false;

    private TerracottaProcess() {}

    //启动 Terracotta 进程, 返回 HTTP 端口 (0=失败)
    public static CompletableFuture<Integer> start() {
        //debounce 1分钟兜底 防止supplyAsync内部卡死导致startingGuard永久true
        if (startingGuard.get() && startingSince > 0
                && System.currentTimeMillis() - startingSince > STARTING_GUARD_TIMEOUT_MS) {
            LOGGER.warn("startingGuard stuck over 60s, force reset");
            startingGuard.set(false);
        }
        if (!startingGuard.compareAndSet(false, true)) {
            return CompletableFuture.failedFuture(new IOException("Terracotta 启动已在进行中"));
        }
        startingSince = System.currentTimeMillis();
        boolean enteredAsync = false;
        try {
            if (icu.wuhui.voxlink.terracotta.Android.AndroidContextHelper.isAndroid()) {
                //debounce Android走JNI 调TerracottaAndroidBridge.initialize
                if (!TerracottaBinary.ensureLoaded()) {
                    return CompletableFuture.failedFuture(new TerracottaNotReadyException("陶瓦 .so 加载失败"));
                }
                if (httpPort > 0 && isAlive()) {
                    return CompletableFuture.completedFuture(httpPort);
                }
                enteredAsync = true;
                return startAndroidInternal();
            }
            if (!TerracottaBinary.isReady()) {
                return CompletableFuture.failedFuture(new TerracottaNotReadyException("陶瓦二进制未就绪, 请先下载"));
            }
            if (httpPort > 0) {
                if (isAlive()) return CompletableFuture.completedFuture(httpPort);
                stop();
            }

            isSecondary = false;
            enteredAsync = true;
            return startInternal();
        } finally {
            if (!enteredAsync) {
                startingGuard.set(false);
                startingSince = 0;
            }
        }
    }

    //debounce Android JNI初始化 端口用固定值0标识JNI模式
    private static CompletableFuture<Integer> startAndroidInternal() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Class<?> bridge = Class.forName("icu.wuhui.voxlink.terracotta.Android.TerracottaAndroidBridge");
                Object ctx = icu.wuhui.voxlink.terracotta.Android.AndroidContextHelper.getActivityContext();
                if (ctx == null) throw new IOException("无法获取Android Context");
                //debounce VpnService回调 触发startVpnService申请
                Runnable onVpnRequired = () -> {
                    try {
                        startVpnServiceReflection(ctx);
                    } catch (Throwable t) {
                        LOGGER.warn("Failed to start VpnService: {}", t.getMessage());
                        try {
                            bridge.getMethod("rejectVpn").invoke(null);
                        } catch (Throwable ignored) {}
                    }
                };
                bridge.getMethod("initialize", Object.class, Runnable.class).invoke(null, ctx, onVpnRequired);
                httpPort = JNI_PORT;
                isSecondary = false;
                LOGGER.info("Terracotta JNI mode started");
                return httpPort;
            } catch (Throwable t) {
                throw new RuntimeException("Android JNI 初始化失败: " + t.getMessage(), t);
            } finally {
                startingGuard.set(false);
                startingSince = 0;
            }
        });
    }

    //debounce Android固定端口标识JNI模式 不走HTTP
    private static final int JNI_PORT = -1;

    //debounce 反射启动VpnService前台服务
    private static void startVpnServiceReflection(Object ctx) throws Exception {
        Class<?> vpnClass = Class.forName("android.net.VpnService");
        Class<?> contextClass = Class.forName("android.content.Context");
        Class<?> intentClass = Class.forName("android.content.Intent");
        Class<?> serviceClass;
        try {
            serviceClass = Class.forName("icu.wuhui.voxlink.terracotta.Android.TerracottaVpnService");
        } catch (ClassNotFoundException e) {
            throw new IOException("TerracottaVpnService 未打包进 jar");
        }
        Object intent = intentClass.getConstructor(contextClass, Class.class)
                .newInstance(ctx, serviceClass);
        String action = "icu.wuhui.voxlink.terracotta.action.START";
        intentClass.getMethod("setAction", String.class).invoke(intent, action);
        //debounce ContextCompat.startForegroundService(context, intent)
        try {
            Class<?> ccClass = Class.forName("androidx.core.content.ContextCompat");
            ccClass.getMethod("startForegroundService", contextClass, intentClass)
                    .invoke(null, ctx, intent);
        } catch (ClassNotFoundException e) {
            //debounce 无androidx退化到startService
            contextClass.getMethod("startService", intentClass).invoke(ctx, intent);
        }
    }

    //debounce 反射停VpnService
    private static void stopVpnServiceReflection() {
        try {
            Object ctx = icu.wuhui.voxlink.terracotta.Android.AndroidContextHelper.getActivityContext();
            if (ctx == null) return;
            Class<?> contextClass = Class.forName("android.content.Context");
            Class<?> intentClass = Class.forName("android.content.Intent");
            Class<?> serviceClass = Class.forName("icu.wuhui.voxlink.terracotta.Android.TerracottaVpnService");
            Object intent = intentClass.getConstructor(contextClass, Class.class)
                    .newInstance(ctx, serviceClass);
            intentClass.getMethod("setAction", String.class).invoke(intent,
                    "icu.wuhui.voxlink.terracotta.action.STOP");
            try {
                Class<?> ccClass = Class.forName("androidx.core.content.ContextCompat");
                ccClass.getMethod("startForegroundService", contextClass, intentClass)
                        .invoke(null, ctx, intent);
            } catch (ClassNotFoundException e) {
                contextClass.getMethod("startService", intentClass).invoke(ctx, intent);
            }
        } catch (Throwable t) {
            LOGGER.debug("Failed to stop VpnService: {}", t.getMessage());
        }
    }

    //debounce 实际启动逻辑 抽出来便于崩溃后重启1次复用
    private static CompletableFuture<Integer> startInternal() {
        return CompletableFuture.supplyAsync(() -> {
            Process proc = null;
            try {
                Path binary = TerracottaBinary.getBinaryPath();
                //debounce 对齐HMCL: tempdir/http 路径 --hmcl参数让Terracotta自处理Windows分离子进程
                Path portDir = Files.createTempDirectory("voxlink-terracotta-" + ThreadLocalRandom.current().nextLong());
                portFile = portDir.resolve("http").toAbsolutePath();
                //debounce 启动前先删portFile 防止读到上次残留旧端口
                try { Files.deleteIfExists(portFile); } catch (IOException e) {
                    LOGGER.warn("Failed to clean portFile: {}", e.getMessage());
                }

                ProcessBuilder pb = new ProcessBuilder(binary.toString(), "--hmcl", portFile.toString());
                pb.redirectErrorStream(true);
                proc = pb.start();
                PROCESS.set(proc);
                lastErrorLine = null;

                //debounce stdout逐行解析 提取错误关键词+全量透传到日志 不再仅排空
                final Process procRef = proc;
                Thread drainStdout = new Thread(() -> {
                    try (var br = new java.io.BufferedReader(
                            new java.io.InputStreamReader(procRef.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            LOGGER.info("[terracotta] {}", line);
                            String lower = line.toLowerCase(java.util.Locale.ROOT);
                            //debounce secondary模式 同机joiner复用primary端口 进程会正常退出
                            if (lower.contains("running in secondary mode")) {
                                Matcher spm = SECONDARY_PORT_PATTERN.matcher(line);
                                if (spm.find()) {
                                    int sp = Integer.parseInt(spm.group(1));
                                    if (sp > 0) {
                                        httpPort = sp;
                                        isSecondary = true;
                                        LOGGER.info("Terracotta secondary mode, reuse primary port {}", sp);
                                    }
                                }
                            }
                            //debounce 错误关键词命中时记录到lastErrorLine 启动失败时透传给用户
                            if (lower.contains("error") || lower.contains("fatal")
                                || lower.contains("panic") || lower.contains("failed to bind")
                                || lower.contains("address already in use") || lower.contains("exception")
                                || lower.contains("cannot ") || lower.contains("failed to")) {
                                lastErrorLine = line;
                            }
                        }
                    } catch (java.io.IOException ignored) {}
                }, "terracotta-stdout-drain");
                drainStdout.setDaemon(true);
                drainStdout.start();

                //debounce UAC弹窗兼容: 进程活着就等端口文件 但有120s总超时防止永久卡死
                long waitStart = System.currentTimeMillis();
                long lastLogMs = waitStart;
                long procExitTime = -1;
                while (true) {
                    //debounce --hmcl模式 Windows上父进程拉起--hmcl2子进程后立即退出 属正常行为
                    if (!proc.isAlive()) {
                        if (isSecondary && httpPort > 0) {
                            restartUsed.set(false);
                            LOGGER.info("Terracotta secondary process exited, reuse primary port {}", httpPort);
                            return httpPort;
                        }
                        if (procExitTime == -1) procExitTime = System.currentTimeMillis();
                        //debounce 进程退出后继续等10s端口文件 对齐HMCL逻辑 --hmcl父进程会立即退出
                        if (System.currentTimeMillis() - procExitTime >= 10000) {
                            if (restartUsed.compareAndSet(false, true)) {
                                LOGGER.warn("Terracotta process exited unexpectedly, try restart once. Error line: {}", lastErrorLine);
                                stopInternalQuiet();
                                return startInternalRetry();
                            }
                            String errLine = lastErrorLine != null ? lastErrorLine : "无stdout错误输出";
                            throw new RuntimeException("Terracotta进程意外退出: " + errLine);
                        }
                    }
                    if (Files.exists(portFile)) {
                        String content = Files.readString(portFile).trim();
                        Matcher m = PORT_PATTERN.matcher(content);
                        if (m.find()) {
                            int port = Integer.parseInt(m.group(1));
                            if (port > 0) {
                                httpPort = port;
                                restartUsed.set(false);
                                LOGGER.info("Terracotta process started, HTTP port {}", port);
                                return port;
                            }
                        }
                    }
                    long now = System.currentTimeMillis();
                    long elapsed = now - waitStart;
                    if (elapsed > START_TOTAL_TIMEOUT_MS) {
                        proc.destroyForcibly();
                        throw new RuntimeException("陶瓦启动超时(" + START_TOTAL_TIMEOUT_MS / 1000
                            + "s)未写出端口文件: " + (lastErrorLine != null ? lastErrorLine : "无错误输出"));
                    }
                    if (now - lastLogMs >= 10000) {
                        LOGGER.info("Waiting for Terracotta start (UAC prompt may have appeared), waited {}ms", elapsed);
                        lastLogMs = now;
                    }
                    Thread.sleep(PORT_POLL_MS);
                }
            } catch (Exception e) {
                if (proc != null && proc.isAlive()) proc.destroyForcibly();
                throw new RuntimeException("启动 Terracotta 失败: " + e.getMessage(), e);
            } finally {
                startingGuard.set(false);
                startingSince = 0;
            }
        });
    }

    //debounce 重启内部方法 不递归startInternal 防止栈深+1
    private static Integer startInternalRetry() {
        try {
            Path binary = TerracottaBinary.getBinaryPath();
            //debounce 对齐HMCL: tempdir/http 路径 --hmcl参数
            Path portDir = Files.createTempDirectory("voxlink-terracotta-" + ThreadLocalRandom.current().nextLong());
            portFile = portDir.resolve("http").toAbsolutePath();
            try { Files.deleteIfExists(portFile); } catch (IOException e) {
                LOGGER.warn("Failed to clean portFile: {}", e.getMessage());
            }
            ProcessBuilder pb = new ProcessBuilder(binary.toString(), "--hmcl", portFile.toString());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            PROCESS.set(proc);
            lastErrorLine = null;

            final Process procRef = proc;
            Thread drainStdout = new Thread(() -> {
                try (var br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(procRef.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        LOGGER.info("[terracotta] {}", line);
                        String lower = line.toLowerCase(java.util.Locale.ROOT);
                        //debounce secondary模式 同机joiner复用primary端口 进程会正常退出
                        if (lower.contains("running in secondary mode")) {
                            Matcher spm = SECONDARY_PORT_PATTERN.matcher(line);
                            if (spm.find()) {
                                int sp = Integer.parseInt(spm.group(1));
                                if (sp > 0) {
                                    httpPort = sp;
                                    isSecondary = true;
                                    LOGGER.info("Terracotta secondary mode (restart), reuse primary port {}", sp);
                                }
                            }
                        }
                        if (lower.contains("error") || lower.contains("fatal")
                            || lower.contains("panic") || lower.contains("failed to bind")
                            || lower.contains("address already in use") || lower.contains("exception")
                            || lower.contains("cannot ") || lower.contains("failed to")) {
                            lastErrorLine = line;
                        }
                    }
                } catch (java.io.IOException ignored) {}
            }, "terracotta-stdout-drain");
            drainStdout.setDaemon(true);
            drainStdout.start();

            //debounce 重启后同样等端口文件 但有120s总超时
            long waitStart = System.currentTimeMillis();
            long lastLogMs = waitStart;
            long procExitTime = -1;
            while (true) {
                if (!proc.isAlive()) {
                    //debounce secondary模式进程正常退出 httpPort已被stdout设置 直接返回
                    if (isSecondary && httpPort > 0) {
                        restartUsed.set(false);
                        LOGGER.info("Terracotta secondary process exited (restart), reuse primary port {}", httpPort);
                        return httpPort;
                    }
                    //debounce --hmcl父进程立即退出 属正常 继续等10s端口文件
                    if (procExitTime == -1) procExitTime = System.currentTimeMillis();
                    if (System.currentTimeMillis() - procExitTime >= 10000) {
                        String errLine = lastErrorLine != null ? lastErrorLine : "无stdout错误输出";
                        throw new RuntimeException("Terracotta重启后仍意外退出: " + errLine);
                    }
                }
                if (Files.exists(portFile)) {
                    String content = Files.readString(portFile).trim();
                    Matcher m = PORT_PATTERN.matcher(content);
                    if (m.find()) {
                        int port = Integer.parseInt(m.group(1));
                        if (port > 0) {
                            httpPort = port;
                            restartUsed.set(false);
                            LOGGER.info("Terracotta process restart succeeded, HTTP port {}", port);
                            return port;
                        }
                    }
                }
                long now = System.currentTimeMillis();
                long elapsed = now - waitStart;
                if (elapsed > START_TOTAL_TIMEOUT_MS) {
                    proc.destroyForcibly();
                    throw new RuntimeException("陶瓦重启超时(" + START_TOTAL_TIMEOUT_MS / 1000
                        + "s)未写出端口文件: " + (lastErrorLine != null ? lastErrorLine : "无错误输出"));
                }
                if (now - lastLogMs >= 10000) {
                    LOGGER.info("Waiting for Terracotta restart, waited {}ms", elapsed);
                    lastLogMs = now;
                }
                Thread.sleep(PORT_POLL_MS);
            }
        } catch (Exception e) {
            throw new RuntimeException("重启 Terracotta 失败: " + e.getMessage(), e);
        }
    }

    public static int getHttpPort() { return httpPort; }

    public static boolean isAlive() {
        if (httpPort == JNI_PORT) {
            //debounce Android JNI模式 调bridge.isInitialized
            try {
                Class<?> bridge = Class.forName("icu.wuhui.voxlink.terracotta.Android.TerracottaAndroidBridge");
                Boolean inited = (Boolean) bridge.getMethod("isInitialized").invoke(null);
                return inited != null && inited;
            } catch (Throwable t) {
                return false;
            }
        }
        //debounce --hmcl父进程会立即退出 信任httpPort就绪 由HTTP请求失败判定真实死亡
        return httpPort > 0;
    }

    //debounce 健康检查 区分进程活着但卡死和进程正常 调getMeta 1秒超时
    public static boolean isResponsive() {
        if (!isAlive()) return false;
        if (httpPort == JNI_PORT) return true;
        try {
            TerracottaClient.getMeta(httpPort).orTimeout(1, TimeUnit.SECONDS).join();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    //debounce 最近错误行 让调用方在异常中包装具体原因
    public static String getLastErrorLine() { return lastErrorLine; }

    //优雅关闭
    public static void stop() {
        if (httpPort == JNI_PORT) {
            //debounce Android JNI模式 调setWaiting+停VpnService
            try {
                Class<?> bridge = Class.forName("icu.wuhui.voxlink.terracotta.Android.TerracottaAndroidBridge");
                bridge.getMethod("setWaiting").invoke(null);
            } catch (Throwable t) {
                LOGGER.warn("JNI setWaiting failed: {}", t.getMessage());
            }
            stopVpnServiceReflection();
            httpPort = 0;
            restartUsed.set(false);
            startingGuard.set(false);
            startingSince = 0;
            return;
        }
        //debounce secondary模式不拥有进程 只清静态变量 primary由其他JVM管理
        if (isSecondary) {
            LOGGER.info("Terracotta secondary mode disabled, keep primary process");
            httpPort = 0;
            isSecondary = false;
            restartUsed.set(false);
            startingGuard.set(false);
            startingSince = 0;
            PROCESS.set(null);
            return;
        }

        if (httpPort > 0) {
            try {
                //debounce 同步等panic 不让进程被destroy前错过请求 缩短到500ms避免阻塞主线程
                TerracottaClient.get(httpPort, "/panic?peaceful=true").orTimeout(500, TimeUnit.MILLISECONDS).join();
            } catch (Exception ignored) {}
        }

        Process p = PROCESS.getAndSet(null);
        if (p != null && p.isAlive()) {
            try { p.destroy(); } catch (Exception ignored) {}
            try {
                if (!p.waitFor(GRACEFUL_SHUTDOWN_SEC, TimeUnit.SECONDS)) {
                    try { p.destroyForcibly(); } catch (Exception ignored) {}
                    LOGGER.warn("Terracotta process did not exit within {}s", GRACEFUL_SHUTDOWN_SEC);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        httpPort = 0;
        restartUsed.set(false);
        //debounce 清startingGuard 防止recover时上次start卡住导致新start失败
        startingGuard.set(false);
        startingSince = 0;

        if (portFile != null) {
            try { Files.deleteIfExists(portFile); } catch (IOException e) {
                LOGGER.warn("Failed to delete port file: {}", e.getMessage());
            }
            portFile = null;
        }
    }

    //debounce 静默清理 不打panic 不删portFile 用于重启前清进程
    private static void stopInternalQuiet() {
        Process p = PROCESS.getAndSet(null);
        if (p != null && p.isAlive()) {
            try { p.destroy(); } catch (Exception ignored) {}
            try {
                if (!p.waitFor(1, TimeUnit.SECONDS)) {
                    try { p.destroyForcibly(); } catch (Exception ignored) {}
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        httpPort = 0;
    }
}
