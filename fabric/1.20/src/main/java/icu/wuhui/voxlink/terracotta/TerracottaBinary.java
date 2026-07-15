package icu.wuhui.voxlink.terracotta;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class TerracottaBinary {
    private static final String VERSION = "0.4.2";
    private static final String GITHUB_URL = "https://github.com/burningtnt/Terracotta/releases/download/v" + VERSION;
    private static final String[] MIRROR_BASES = {
        "https://ghproxy.net/https://github.com/burningtnt/Terracotta/releases/download/v" + VERSION,
        "https://mirror.ghproxy.com/https://github.com/burningtnt/Terracotta/releases/download/v" + VERSION,
    };
    private static final int MAX_RETRIES = 3;
    private static final int RETRY_DELAY_MS = 2000;
    private static final int DOWNLOAD_CONNECT_TIMEOUT_SEC = 20;
    private static final int DOWNLOAD_REQUEST_TIMEOUT_SEC = 90;
    private static final int DOWNLOAD_BUFFER_SIZE = 65536;
    private static final int PAUSE_POLL_MS = 100;
    private static final int PROGRESS_THROTTLE_MS = 500;
    private static final int PROGRESS_PERCENT_THRESHOLD = 2;
    private static final int EXTRACT_TIMEOUT_SEC = 120;
    private static final int VERIFY_BUFFER_SIZE = 8192;
    private static final HttpClient DOWNLOAD_CLIENT = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .connectTimeout(Duration.ofSeconds(DOWNLOAD_CONNECT_TIMEOUT_SEC))
        .build();

    private static final PlatformInfo[] PLATFORMS = {
        new PlatformInfo("windows", "x86_64", "terracotta-" + VERSION + "-windows-x86_64-pkg.tar.gz",
            "74c10568a7fea9c1d38cf8d2d4ca90baf1517f8e5a26c63d3349db70bc449796",
            "terracotta-" + VERSION + "-windows-x86_64.exe", false),
        new PlatformInfo("windows", "aarch64", "terracotta-" + VERSION + "-windows-arm64-pkg.tar.gz",
            "782c2fa911488d487447694acca6b17fa68304c87023fb6814b83a167fc2845f",
            "terracotta-" + VERSION + "-windows-arm64.exe", false),
        new PlatformInfo("linux", "x86_64", "terracotta-" + VERSION + "-linux-x86_64-pkg.tar.gz",
            "dc8eed0338a1888743ab38468d88b9dd8a60d60c29df072adba7c8d2edaf7937",
            "terracotta-" + VERSION + "-linux-x86_64", false),
        new PlatformInfo("linux", "aarch64", "terracotta-" + VERSION + "-linux-arm64-pkg.tar.gz",
            "1cc03ed2ccaab8a7b64e8eb375ccfb8c1d4cd28f4c1a242fe3b492522f9f4aad",
            "terracotta-" + VERSION + "-linux-arm64", false),
        new PlatformInfo("macos", "x86_64", "terracotta-" + VERSION + "-macos-x86_64-pkg.tar.gz",
            "07899429515f7646fd6c271acb39a2d3a34d330547b1d2682c2e3311db07aa0a",
            "terracotta-" + VERSION + "-macos-x86_64", false),
        new PlatformInfo("macos", "aarch64", "terracotta-" + VERSION + "-macos-arm64-pkg.tar.gz",
            "14a6cfa98e841c33b552f2291b0637461f37813c0bb3d29c6b56a59cb5e6714a",
            "terracotta-" + VERSION + "-macos-arm64", false),
        new PlatformInfo("android", "aarch64", null, null, null, true),
        new PlatformInfo("android", "armv7", null, null, null, true),
        new PlatformInfo("android", "x86", null, null, null, true),
        new PlatformInfo("android", "x86_64", null, null, null, true),
    };

    private static volatile boolean downloadPaused = false;
    private static volatile boolean downloadCancelled = false;

    public static void pauseDownload() { downloadPaused = true; }
    public static void resumeDownload() { downloadPaused = false; }
    public static void cancelDownload() {
        downloadCancelled = true;
        downloadPaused = false;
    }
    public static boolean isDownloadPaused() { return downloadPaused; }
    public static boolean isDownloadCancelled() { return downloadCancelled; }
    public static void resetDownloadFlags() {
        downloadPaused = false;
        downloadCancelled = false;
    }

    private static final Path CACHE_DIR;
    private static final PlatformInfo CURRENT;

    static {
        //版本隔离: 下载到游戏目录 (启动器启用版本隔离时即版本目录, 删版本即清)
        Path base = net.fabricmc.loader.api.FabricLoader.getInstance()
                .getGameDir().resolve(".voxlink").resolve("terracotta");
        CACHE_DIR = base;
        CURRENT = detectPlatform();
    }

    private TerracottaBinary() {}

    private static PlatformInfo detectPlatform() {
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            String arch = System.getProperty("os.arch", "").toLowerCase();

            boolean isAndroid = false;
            try {
                Class.forName("android.os.Build");
                isAndroid = true;
            } catch (ClassNotFoundException ignored) {}

            String osNorm;
            String archNorm;

            if (isAndroid) {
                osNorm = "android";
                if (arch.contains("aarch64") || arch.contains("arm64")) archNorm = "aarch64";
                else if (arch.contains("armv7") || arch.contains("armeabi") || arch.equals("arm")) archNorm = "armv7";
                else if (arch.contains("x86_64") || arch.contains("amd64")) archNorm = "x86_64";
                else if (arch.contains("i386") || arch.contains("x86")) archNorm = "x86";
                else return null;
            } else {
                if (os.contains("win")) osNorm = "windows";
                else if (os.contains("mac") || os.contains("darwin")) osNorm = "macos";
                else if (os.contains("linux") || os.contains("nix")) osNorm = "linux";
                else return null;

                if (arch.contains("aarch64") || arch.contains("arm64")) archNorm = "aarch64";
                else if (arch.contains("x86_64") || arch.contains("amd64")) archNorm = "x86_64";
                else return null;
            }

            for (PlatformInfo p : PLATFORMS) {
                if (p.os.equals(osNorm) && p.arch.equals(archNorm)) return p;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean isReady() {
        if (CURRENT == null) return false;
        if (CURRENT.android) {
            return TerracottaAndroidBridge.isInitialized();
        }
        try {
            Path binaryPath = CACHE_DIR.resolve(CURRENT.binaryName);
            if (!Files.exists(binaryPath)) return false;
            return verifySha256(binaryPath, CURRENT.sha256);
        } catch (Exception e) {
            LOGGER.warn("陶瓦校验异常, 当作未就绪: {}", e.getMessage());
            return false;
        }
    }

    private static final AtomicBoolean downloadingNow = new AtomicBoolean(false);
    public static CompletableFuture<Path> downloadAsync(Consumer<DownloadProgress> progressCallback) {
        CompletableFuture<Path> future = new CompletableFuture<>();
        if (CURRENT == null || CURRENT.android) {
            future.completeExceptionally(new IOException(
                CURRENT != null && CURRENT.android
                    ? "安卓依赖启动器集成陶瓦, 无需下载"
                    : "当前平台不支持陶瓦"));
            return future;
        }
        if (!downloadingNow.compareAndSet(false, true)) {
            future.completeExceptionally(new IOException("下载已在进行中"));
            return future;
        }
        CompletableFuture.runAsync(() -> {
            try {
                try { Files.createDirectories(CACHE_DIR); }
                catch (IOException e) {
                    future.completeExceptionally(new IOException("无法创建缓存目录: " + e.getMessage(), e));
                    return;
                }
                Path binaryPath = CACHE_DIR.resolve(CURRENT.binaryName);
                Path archivePath = CACHE_DIR.resolve(CURRENT.filename + ".downloading");

                String[] urls = new String[MIRROR_BASES.length + 1];
                urls[0] = GITHUB_URL + "/" + CURRENT.filename;
                for (int i = 0; i < MIRROR_BASES.length; i++) {
                    urls[i + 1] = MIRROR_BASES[i] + "/" + CURRENT.filename;
                }

                Exception lastError = null;
                for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
                    if (Thread.currentThread().isInterrupted() || downloadCancelled) break;
                    for (String url : urls) {
                        if (Thread.currentThread().isInterrupted() || downloadCancelled) break;
                        try {
                            downloadOne(url, archivePath, progressCallback);
                            if (downloadCancelled) {
                                Files.deleteIfExists(archivePath);
                                future.completeExceptionally(new IOException("下载已取消"));
                                return;
                            }
                            if (progressCallback != null) {
                                progressCallback.accept(new DownloadProgress(0, 0, 100, 0, false, false, null, "extracting"));
                            }
                            extractAndVerify(archivePath, binaryPath, progressCallback);
                            if (progressCallback != null) {
                                progressCallback.accept(new DownloadProgress(0, 0, 100, 0, true, false, null));
                            }
                            future.complete(binaryPath);
                            return;
                        } catch (Exception e) {
                            if (e instanceof InterruptedException) {
                                Thread.currentThread().interrupt();
                                future.completeExceptionally(new IOException("下载被中断", e));
                                return;
                            }
                            if (downloadCancelled) {
                                try { Files.deleteIfExists(archivePath); } catch (IOException ignored) {}
                                future.completeExceptionally(new IOException("下载已取消"));
                                return;
                            }
                            lastError = e;
                            LOGGER.warn("下载失败 (尝试 {}/{}): {} - {}", attempt + 1, MAX_RETRIES, url, e.getMessage());
                        }
                    }
                    if (attempt < MAX_RETRIES - 1 && !downloadCancelled) {
                        try { Thread.sleep(RETRY_DELAY_MS); } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            future.completeExceptionally(new IOException("下载被中断", ie));
                            return;
                        }
                    }
                }
                if (downloadCancelled) {
                    future.completeExceptionally(new IOException("下载已取消"));
                    return;
                }
                if (progressCallback != null) {
                    progressCallback.accept(new DownloadProgress(0, 0, -1, 0, false, true,
                        lastError != null ? lastError.getMessage() : "未知错误"));
                }
                future.completeExceptionally(new IOException("陶瓦下载失败 (重试" + MAX_RETRIES + "次): "
                    + (lastError != null ? lastError.getMessage() : "未知错误"), lastError));
            } finally {
                downloadingNow.set(false);
            }
        });
        return future;
    }

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("voxlink-terracotta");

    private static void downloadOne(String url, Path archivePath, Consumer<DownloadProgress> progressCallback) throws Exception {
        long existingBytes = 0;
        if (Files.exists(archivePath)) {
            existingBytes = Files.size(archivePath);
        }

        if (progressCallback != null) {
            int initPct = existingBytes > 0 ? -1 : 0;
            progressCallback.accept(new DownloadProgress(existingBytes, -1, initPct, 0, false, false, null, "connecting"));
        }
        if (downloadCancelled) throw new IOException("下载已取消");

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(DOWNLOAD_REQUEST_TIMEOUT_SEC))
            .GET();
        if (existingBytes > 0) {
            reqBuilder.header("Range", "bytes=" + existingBytes + "-");
        }
        HttpRequest req = reqBuilder.build();

        HttpResponse<InputStream> resp = DOWNLOAD_CLIENT.send(req, HttpResponse.BodyHandlers.ofInputStream());
        long total;
        long downloaded;
        boolean appendMode = false;

        if (existingBytes > 0 && resp.statusCode() == 206) {
            appendMode = true;
            downloaded = existingBytes;
            String cr = resp.headers().firstValue("Content-Range").orElse("");
            int slash = cr.lastIndexOf('/');
            if (slash >= 0 && slash < cr.length() - 1) {
                try { total = Long.parseLong(cr.substring(slash + 1).trim()); }
                catch (NumberFormatException e) { total = -1; }
            } else { total = -1; }
        } else if (resp.statusCode() == 200) {
            appendMode = false;
            downloaded = 0;
            total = resp.headers().firstValueAsLong("Content-Length").orElse(-1);
        } else if (resp.statusCode() == 416) {
            Files.deleteIfExists(archivePath);
            throw new IOException("HTTP 416, 重置下载");
        } else {
            throw new IOException("HTTP " + resp.statusCode());
        }

        if (progressCallback != null) {
            int pct = total > 0 ? (int) (downloaded * 100 / total) : 0;
            progressCallback.accept(new DownloadProgress(downloaded, total, pct, 0, false, false, null, null));
        }

        long speedWindowStart = System.currentTimeMillis();
        long speedWindowBytes = downloaded;

        try (InputStream is = resp.body();
             OutputStream os = Files.newOutputStream(archivePath, appendMode
                     ? new java.nio.file.OpenOption[]{java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND}
                     : new java.nio.file.OpenOption[]{java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING})) {
            byte[] buf = new byte[DOWNLOAD_BUFFER_SIZE];
            int n;
            long lastCallbackMs = System.currentTimeMillis();
            int lastPercent = total > 0 ? (int) (downloaded * 100 / total) : 0;
            while ((n = is.read(buf)) != -1) {
                //取消
                if (downloadCancelled) {
                    os.flush();
                    throw new IOException("下载已取消");
                }
                while (downloadPaused && !downloadCancelled) {
                    try { Thread.sleep(PAUSE_POLL_MS); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("下载被中断", ie);
                    }
                }
                if (downloadCancelled) {
                    os.flush();
                    throw new IOException("下载已取消");
                }
                os.write(buf, 0, n);
                downloaded += n;
                long now = System.currentTimeMillis();
                int percent = total > 0 ? (int) (downloaded * 100 / total) : -1;
                if (now - lastCallbackMs >= PROGRESS_THROTTLE_MS
                        || (percent >= 0 && Math.abs(percent - lastPercent) >= PROGRESS_PERCENT_THRESHOLD)
                        || percent == 100) {
                    long windowMs = now - speedWindowStart;
                    long speedBytes = windowMs > 0 ? (downloaded - speedWindowBytes) * 1000 / windowMs : 0;
                    if (progressCallback != null) {
                        progressCallback.accept(new DownloadProgress(downloaded, total, percent, speedBytes, false, false, null, null));
                    }
                    lastCallbackMs = now;
                    lastPercent = percent;
                    speedWindowStart = now;
                    speedWindowBytes = downloaded;
                }
            }
            os.flush();
            if (total > 0 && downloaded < total) {
                throw new IOException("下载不完整: " + downloaded + "/" + total + " 字节");
            }
            if (progressCallback != null) {
                long now = System.currentTimeMillis();
                long windowMs = now - speedWindowStart;
                long speedBytes = windowMs > 0 ? (downloaded - speedWindowBytes) * 1000 / windowMs : 0;
                int percent = total > 0 ? 100 : -1;
                progressCallback.accept(new DownloadProgress(downloaded, total, percent, speedBytes, false, false, null, null));
            }
        }
    }

    private static void extractAndVerify(Path archivePath, Path binaryPath, Consumer<DownloadProgress> progressCallback) throws Exception {
        Path extractDir = Files.createTempDirectory(CACHE_DIR, "extract-");
        Process proc = null;
        try {
            if (downloadCancelled) throw new IOException("下载已取消");
            ProcessBuilder pb = new ProcessBuilder("tar", "-xzf", archivePath.toString(), "-C", extractDir.toString());
            pb.redirectErrorStream(true);
            proc = pb.start();
            byte[] output = proc.getInputStream().readAllBytes();
            if (!proc.waitFor(EXTRACT_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                throw new IOException("tar 解压超时 (" + EXTRACT_TIMEOUT_SEC + "秒)");
            }
            int code = proc.exitValue();
            if (code != 0) {
                throw new IOException("tar 解压失败 exit=" + code + ": " + new String(output));
            }
            if (downloadCancelled) throw new IOException("下载已取消");

            Path found = findBinary(extractDir);
            if (found == null) {
                throw new IOException("压缩包内未找到 " + CURRENT.binaryName);
            }
            Files.move(found, binaryPath, StandardCopyOption.REPLACE_EXISTING);

            try {
                Files.deleteIfExists(archivePath);
            } catch (IOException e) {
                LOGGER.warn("删除压缩包失败: {}", e.getMessage());
            }

            if (downloadCancelled) throw new IOException("下载已取消");
            if (progressCallback != null) {
                progressCallback.accept(new DownloadProgress(0, 0, 100, 0, false, false, null, "verifying"));
            }
            if (!verifySha256(binaryPath, CURRENT.sha256)) {
                throw new IOException("SHA256 校验失败");
            }

            if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
                try { binaryPath.toFile().setExecutable(true); } catch (Exception ignored) {}
            }
        } finally {
            if (proc != null && proc.isAlive()) proc.destroyForcibly();
            try { deleteRecursively(extractDir); } catch (IOException e) {
                LOGGER.warn("清理临时解压目录失败: {}", e.getMessage());
            }
        }
    }

    private static Path findBinary(Path dir) throws IOException {
        Path direct = dir.resolve(CURRENT.binaryName);
        if (Files.isRegularFile(direct)) return direct;

        try (var stream = Files.walk(dir)) {
            return stream
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().equals(CURRENT.binaryName))
                .findFirst()
                .orElse(null);
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (Files.isDirectory(dir)) {
            try (var stream = Files.list(dir)) {
                for (Path p : stream.toList()) {
                    deleteRecursively(p);
                }
            }
        }
        Files.deleteIfExists(dir);
    }

    private static boolean verifySha256(Path file, String expected) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream is = Files.newInputStream(file)) {
                byte[] buf = new byte[VERIFY_BUFFER_SIZE];
                int n;
                while ((n = is.read(buf)) != -1) {
                    md.update(buf, 0, n);
                }
            }
            byte[] digest = md.digest();
            String actual = HexFormat.of().formatHex(digest);
            return actual.equals(expected);
        } catch (Exception e) {
            return false;
        }
    }

    public static String getVersion() { return VERSION; }
    public static Path getCacheDir() { return CACHE_DIR; }
    public static Path getBinaryPath() {
        if (CURRENT == null || CURRENT.android || CURRENT.binaryName == null) return null;
        return CACHE_DIR.resolve(CURRENT.binaryName);
    }
    public static boolean isPlatformSupported() { return CURRENT != null; }
    public static boolean isAndroid() { return CURRENT != null && CURRENT.android; }

    private static Path getPendingFile() { return CACHE_DIR.resolve(".download_pending"); }
    public static boolean isDownloadPending() {
        return Files.exists(getPendingFile());
    }
    public static void markDownloadPending() {
        try { Files.createDirectories(CACHE_DIR); Files.createFile(getPendingFile()); }
        catch (IOException e) {
            LOGGER.warn("无法写入下载意图标记: {}", e.getMessage());
        }
    }
    public static void clearDownloadPending() {
        try { Files.deleteIfExists(getPendingFile()); } catch (IOException ignored) {}
    }

    public static final class DownloadProgress {
        public final long downloadedBytes;
        public final long totalBytes;
        public final int percent;
        public final long speedBps;
        public final boolean done;
        public final boolean failed;
        public final String errorMessage;
        public final String stage;

        public DownloadProgress(long downloadedBytes, long totalBytes, int percent, long speedBps, boolean done, boolean failed, String errorMessage) {
            this(downloadedBytes, totalBytes, percent, speedBps, done, failed, errorMessage, null);
        }

        public DownloadProgress(long downloadedBytes, long totalBytes, int percent, long speedBps, boolean done, boolean failed, String errorMessage, String stage) {
            this.downloadedBytes = downloadedBytes;
            this.totalBytes = totalBytes;
            this.percent = percent;
            this.speedBps = speedBps;
            this.done = done;
            this.failed = failed;
            this.errorMessage = errorMessage;
            this.stage = stage;
        }
    }

    private static final class PlatformInfo {
        final String os;
        final String arch;
        final String filename;
        final String sha256;
        final String binaryName;
        final boolean android;

        PlatformInfo(String os, String arch, String filename, String sha256, String binaryName, boolean android) {
            this.os = os;
            this.arch = arch;
            this.filename = filename;
            this.sha256 = sha256;
            this.binaryName = binaryName;
            this.android = android;
        }
    }
}
