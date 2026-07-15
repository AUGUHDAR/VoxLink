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
    private static final AtomicReference<Process> PROCESS = new AtomicReference<>();
    private static volatile int httpPort = 0;
    private static volatile Path portFile;
    //端口文件端口提取正则
    private static final Pattern PORT_PATTERN = Pattern.compile("\"port\"\\s*:\\s*(\\d+)");

    private TerracottaProcess() {}

    //启动 Terracotta 进程, 返回 HTTP 端口 (0=失败)
    public static CompletableFuture<Integer> start() {
        if (!startingGuard.compareAndSet(false, true)) {
            return CompletableFuture.failedFuture(new IOException("Terracotta 启动已在进行中"));
        }
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
            return CompletableFuture.supplyAsync(() -> {
                Process proc = null;
                try {
                    Path binary = TerracottaBinary.getBinaryPath();
                    portFile = TerracottaBinary.getCacheDir().resolve("port-" + ProcessHandle.current().pid() + ".json");

                    try (var stream = Files.list(TerracottaBinary.getCacheDir())) {
                        stream.filter(p -> {
                            String name = p.getFileName().toString();
                            return name.startsWith("port-") && name.endsWith(".json") && !p.equals(portFile);
                        }).forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                        });
                    } catch (IOException ignored) {}

                    String os = System.getProperty("os.name", "").toLowerCase();
                    boolean isWindows = os.contains("win");

                    ProcessBuilder pb = new ProcessBuilder(binary.toString(), "--hmcl2", portFile.toString());
                    pb.redirectErrorStream(true);
                    proc = pb.start();
                    PROCESS.set(proc);

                    for (int i = 0; i < PORT_WAIT_CYCLES; i++) {
                        if (!proc.isAlive() && i > 0) {
                            throw new RuntimeException("Terracotta进程意外退出");
                        }
                        if (Files.exists(portFile)) {
                            String content = Files.readString(portFile).trim();
                            Matcher m = PORT_PATTERN.matcher(content);
                            if (m.find()) {
                                int port = Integer.parseInt(m.group(1));
                                if (port > 0) {
                                    httpPort = port;
                                    LOGGER.info("陶瓦进程已启动, HTTP 端口 {}", port);
                                    return port;
                                }
                            }
                        }
                        Thread.sleep(PORT_POLL_MS);
                    }

                    throw new IOException("等待 Terracotta 端口超时");
                } catch (Exception e) {
                    if (proc != null && proc.isAlive()) proc.destroyForcibly();
                    throw new RuntimeException("启动 Terracotta 失败: " + e.getMessage(), e);
                } finally {
                    startingGuard.set(false);
                }
            });
        } finally {
            if (!enteredAsync) startingGuard.set(false);
        }
    }

    public static int getHttpPort() { return httpPort; }

    public static boolean isAlive() {
        Process p = PROCESS.get();
        //Windows ProcessBuilder 直接启动, 用 proc.isAlive() 判断
        //同时结合 httpPort, 防止 null 进程被误判
        return httpPort > 0 && p != null && p.isAlive();
    }

    //优雅关闭
    public static void stop() {
        if (httpPort > 0) {
            try {
                TerracottaClient.get(httpPort, "/panic?peaceful=true");
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

        if (portFile != null) {
            try { Files.deleteIfExists(portFile); } catch (IOException e) {
                LOGGER.warn("删除端口文件失败: {}", e.getMessage());
            }
            portFile = null;
        }
    }
}
