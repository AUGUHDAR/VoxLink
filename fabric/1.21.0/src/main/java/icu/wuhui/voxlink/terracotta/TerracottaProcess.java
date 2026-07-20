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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

//Terracotta 进程管理: 拉起 + 端口获取 + 关闭
public final class TerracottaProcess {
    private static final Logger LOGGER = LoggerFactory.getLogger("voxlink-terracotta");
    private static final int PORT_WAIT_CYCLES = 60;
    private static final int PORT_POLL_MS = 500;
    private static final int GRACEFUL_SHUTDOWN_SEC = 3;
    private static final AtomicBoolean startingGuard = new AtomicBoolean(false);
    //debounce startingGuard超时兜底 防止supplyAsync内部卡死导致永久占用
    private static volatile long startingSince = 0;
    private static final long STARTING_GUARD_TIMEOUT_MS = 60_000;
    private static final AtomicReference<Process> PROCESS = new AtomicReference<>();
    private static volatile int httpPort = 0;
    private static volatile Path portFile;
    //端口文件端口提取正则
    private static final Pattern PORT_PATTERN = Pattern.compile("\"port\"\\s*:\\s*(\\d+)");
    //debounce 最近一次错误行 启动失败时包装到异常message让用户看到真正原因
    private static volatile String lastErrorLine = null;
    //debounce 进程崩溃自动重启1次 避免无限循环
    private static final AtomicBoolean restartUsed = new AtomicBoolean(false);

    private TerracottaProcess() {}

    //启动 Terracotta 进程, 返回 HTTP 端口 (0=失败)
    public static CompletableFuture<Integer> start() {
        //debounce 1分钟兜底 防止supplyAsync内部卡死导致startingGuard永久true
        if (startingGuard.get() && startingSince > 0
                && System.currentTimeMillis() - startingSince > STARTING_GUARD_TIMEOUT_MS) {
            LOGGER.warn("startingGuard 卡死超过 60s 强制重置");
            startingGuard.set(false);
        }
        if (!startingGuard.compareAndSet(false, true)) {
            return CompletableFuture.failedFuture(new IOException("Terracotta 启动已在进行中"));
        }
        startingSince = System.currentTimeMillis();
        boolean enteredAsync = false;
        try {
            if (!TerracottaBinary.isReady()) {
                return CompletableFuture.failedFuture(new TerracottaNotReadyException("陶瓦二进制未就绪, 请先下载"));
            }
            if (httpPort > 0) {
                if (isAlive()) return CompletableFuture.completedFuture(httpPort);
                stop();
            }

            enteredAsync = true;
            return startInternal();
        } finally {
            if (!enteredAsync) {
                startingGuard.set(false);
                startingSince = 0;
            }
        }
    }

    //debounce 实际启动逻辑 抽出来便于崩溃后重启1次复用
    private static CompletableFuture<Integer> startInternal() {
        return CompletableFuture.supplyAsync(() -> {
            Process proc = null;
            try {
                Path binary = TerracottaBinary.getBinaryPath();
                portFile = TerracottaBinary.getCacheDir().resolve("port-" + ProcessHandle.current().pid() + ".json");
                //debounce 启动前先删当前PID的portFile 防止读到上次残留旧端口导致recover失败
                try { Files.deleteIfExists(portFile); } catch (IOException e) {
                    LOGGER.warn("清理当前PID portFile 失败: {}", e.getMessage());
                }

                try (var stream = Files.list(TerracottaBinary.getCacheDir())) {
                    stream.filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith("port-") && name.endsWith(".json");
                    }).forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
                } catch (IOException ignored) {}

                ProcessBuilder pb = new ProcessBuilder(binary.toString(), "--hmcl2", portFile.toString());
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

                for (int i = 0; i < PORT_WAIT_CYCLES; i++) {
                    if (!proc.isAlive() && i > 0) {
                        //debounce 进程崩溃 自动重启1次 第二次直接抛
                        if (restartUsed.compareAndSet(false, true)) {
                            LOGGER.warn("陶瓦进程意外退出, 尝试重启1次. 错误行: {}", lastErrorLine);
                            stopInternalQuiet();
                            return startInternalRetry();
                        }
                        String errLine = lastErrorLine != null ? lastErrorLine : "无stdout错误输出";
                        throw new RuntimeException("Terracotta进程意外退出: " + errLine);
                    }
                    if (Files.exists(portFile)) {
                        String content = Files.readString(portFile).trim();
                        Matcher m = PORT_PATTERN.matcher(content);
                        if (m.find()) {
                            int port = Integer.parseInt(m.group(1));
                            if (port > 0) {
                                httpPort = port;
                                restartUsed.set(false);  //debounce 成功启动后清重启标记 下次崩溃可再重启1次
                                LOGGER.info("陶瓦进程已启动, HTTP 端口 {}", port);
                                return port;
                            }
                        }
                    }
                    Thread.sleep(PORT_POLL_MS);
                }

                String errLine = lastErrorLine != null ? lastErrorLine : "无stdout错误输出";
                throw new IOException("等待 Terracotta 端口超时. 最后错误行: " + errLine);
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
            portFile = TerracottaBinary.getCacheDir().resolve("port-" + ProcessHandle.current().pid() + ".json");
            //debounce 启动前先删当前PID的portFile 防止读到上次残留旧端口导致recover失败
            try { Files.deleteIfExists(portFile); } catch (IOException e) {
                LOGGER.warn("清理当前PID portFile 失败: {}", e.getMessage());
            }
            ProcessBuilder pb = new ProcessBuilder(binary.toString(), "--hmcl2", portFile.toString());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            PROCESS.set(proc);
            lastErrorLine = null;

            final Process procRef = proc;
            Thread drainStdout = new Thread(() -> {
                try (var br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(procRef.getInputStream(), java.nio.charset.Charset.defaultCharset()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        LOGGER.info("[terracotta] {}", line);
                        String lower = line.toLowerCase(java.util.Locale.ROOT);
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

            for (int i = 0; i < PORT_WAIT_CYCLES; i++) {
                if (!proc.isAlive() && i > 0) {
                    String errLine = lastErrorLine != null ? lastErrorLine : "无stdout错误输出";
                    throw new RuntimeException("Terracotta重启后仍意外退出: " + errLine);
                }
                if (Files.exists(portFile)) {
                    String content = Files.readString(portFile).trim();
                    Matcher m = PORT_PATTERN.matcher(content);
                    if (m.find()) {
                        int port = Integer.parseInt(m.group(1));
                        if (port > 0) {
                            httpPort = port;
                            restartUsed.set(false);
                            LOGGER.info("陶瓦进程重启成功, HTTP 端口 {}", port);
                            return port;
                        }
                    }
                }
                Thread.sleep(PORT_POLL_MS);
            }
            String errLine = lastErrorLine != null ? lastErrorLine : "无stdout错误输出";
            throw new IOException("重启后等待 Terracotta 端口超时. 最后错误行: " + errLine);
        } catch (Exception e) {
            throw new RuntimeException("重启 Terracotta 失败: " + e.getMessage(), e);
        }
    }

    public static int getHttpPort() { return httpPort; }

    public static boolean isAlive() {
        Process p = PROCESS.get();
        //Windows ProcessBuilder 直接启动, 用 proc.isAlive() 判断
        //同时结合 httpPort, 防止 null 进程被误判
        return httpPort > 0 && p != null && p.isAlive();
    }

    //debounce 健康检查 区分进程活着但卡死和进程正常 调getMeta 1秒超时
    public static boolean isResponsive() {
        if (!isAlive()) return false;
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
                    LOGGER.warn("陶瓦进程未在{}秒内退出", GRACEFUL_SHUTDOWN_SEC);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        httpPort = 0;
        restartUsed.set(false);

        if (portFile != null) {
            try { Files.deleteIfExists(portFile); } catch (IOException e) {
                LOGGER.warn("删除端口文件失败: {}", e.getMessage());
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
