package icu.wuhui.voxlink.terracotta;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpRequest.Builder;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import icu.wuhui.voxlink.terracotta.Android.AndroidContextHelper;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TerracottaBinary {
   private static final String VERSION = "0.4.2";
   private static final String LATEST_VERSION = "0.4.2";
   private static final String META_URL = "https://terracotta.glavo.site/meta";
   private static final int META_TIMEOUT_SEC = 8;
   private static final String GITHUB_URL = "https://github.com/burningtnt/Terracotta/releases/download/v" + VERSION;
   private static final String GITEE_URL = "https://gitee.com/burningtnt/Terracotta/releases/download/v" + VERSION;
   private static final String[] MIRROR_BASES = new String[]{
      "https://cnb.cool/HMCL-Terracotta/Terracotta/-/releases/download/v" + VERSION,
      "https://alist.8mi.tech/d/mirror/HMCL-Terracotta/Auto/v" + VERSION,
      "https://ghproxy.net/https://github.com/burningtnt/Terracotta/releases/download/v" + VERSION,
      "https://mirror.ghproxy.com/https://github.com/burningtnt/Terracotta/releases/download/v" + VERSION
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
   private static final int PROBE_TIMEOUT_SEC = 4;
   private static final int PROBE_TOTAL_TIMEOUT_SEC = 6;
   private static final HttpClient DOWNLOAD_CLIENT = HttpClient.newBuilder().followRedirects(Redirect.ALWAYS).connectTimeout(Duration.ofSeconds(20L)).build();
   private static final TerracottaBinary.PlatformInfo[] PLATFORMS = new TerracottaBinary.PlatformInfo[]{
      new TerracottaBinary.PlatformInfo(
         "windows",
         "x86_64",
         "terracotta-" + VERSION + "-windows-x86_64-pkg.tar.gz",
         "74c10568a7fea9c1d38cf8d2d4ca90baf1517f8e5a26c63d3349db70bc449796",
         "terracotta-" + VERSION + "-windows-x86_64.exe",
         false
      ),
      new TerracottaBinary.PlatformInfo(
         "windows",
         "aarch64",
         "terracotta-" + VERSION + "-windows-arm64-pkg.tar.gz",
         "782c2fa911488d487447694acca6b17fa68304c87023fb6814b83a167fc2845f",
         "terracotta-" + VERSION + "-windows-arm64.exe",
         false
      ),
      new TerracottaBinary.PlatformInfo(
         "linux",
         "x86_64",
         "terracotta-" + VERSION + "-linux-x86_64-pkg.tar.gz",
         "dc8eed0338a1888743ab38468d88b9dd8a60d60c29df072adba7c8d2edaf7937",
         "terracotta-" + VERSION + "-linux-x86_64",
         false
      ),
      new TerracottaBinary.PlatformInfo(
         "linux",
         "aarch64",
         "terracotta-" + VERSION + "-linux-arm64-pkg.tar.gz",
         "1cc03ed2ccaab8a7b64e8eb375ccfb8c1d4cd28f4c1a242fe3b492522f9f4aad",
         "terracotta-" + VERSION + "-linux-arm64",
         false
      ),
      new TerracottaBinary.PlatformInfo(
         "macos",
         "x86_64",
         "terracotta-" + VERSION + "-macos-x86_64-pkg.tar.gz",
         "07899429515f7646fd6c271acb39a2d3a34d330547b1d2682c2e3311db07aa0a",
         "terracotta-" + VERSION + "-macos-x86_64",
         false
      ),
      new TerracottaBinary.PlatformInfo(
         "macos",
         "aarch64",
         "terracotta-" + VERSION + "-macos-arm64-pkg.tar.gz",
         "14a6cfa98e841c33b552f2291b0637461f37813c0bb3d29c6b56a59cb5e6714a",
         "terracotta-" + VERSION + "-macos-arm64",
         false
      ),
      new TerracottaBinary.PlatformInfo(
         "android",
         "aarch64",
         "terracotta-" + VERSION + "-android-arm64v8a.so",
         "fc426710de5f53ae5b6350fdffe1012082992dac5b9d93ea5e86c0e56af5567a",
         "terracotta-" + VERSION + "-android-arm64v8a.so",
         true
      ),
      new TerracottaBinary.PlatformInfo(
         "android",
         "armv7",
         "terracotta-" + VERSION + "-android-armv7.so",
         "a777504e66bff55df4774d953598e33211a05b782fff4a5fd2a5dba254474239",
         "terracotta-" + VERSION + "-android-armv7.so",
         true
      ),
      new TerracottaBinary.PlatformInfo(
         "android",
         "x86",
         "terracotta-" + VERSION + "-android-x86.so",
         "45df60fe08e9d37ac1eab720eb494405162ff911b1b8d54ec3342c041e691722",
         "terracotta-" + VERSION + "-android-x86.so",
         true
      ),
      new TerracottaBinary.PlatformInfo(
         "android",
         "x86_64",
         "terracotta-" + VERSION + "-android-x86_64.so",
         "93f248637a966c8d3bb84d06ed027ad9ba7347615b3bab92e7d38b8764d023e4",
         "terracotta-" + VERSION + "-android-x86_64.so",
         true
      )
   };
   private static volatile boolean downloadPaused = false;
   private static volatile boolean downloadCancelled = false;
   private static final Path CACHE_DIR;
   private static final TerracottaBinary.PlatformInfo CURRENT = detectPlatform();
   private static final AtomicReference<TerracottaBinary.ReadyCache> readyCache = new AtomicReference<>();
   private static final AtomicBoolean downloadingNow = new AtomicBoolean(false);
   private static final Logger LOGGER = LoggerFactory.getLogger("voxlink-terracotta");

   public static void pauseDownload() {
      downloadPaused = true;
   }

   public static void resumeDownload() {
      downloadPaused = false;
   }

   public static void cancelDownload() {
      downloadCancelled = true;
      downloadPaused = false;
   }

   public static boolean isDownloadPaused() {
      return downloadPaused;
   }

   public static boolean isDownloadCancelled() {
      return downloadCancelled;
   }

   public static void resetDownloadFlags() {
      downloadPaused = false;
      downloadCancelled = false;
   }

   private TerracottaBinary() {
   }

   private static TerracottaBinary.PlatformInfo detectPlatform() {
      try {
         String os = System.getProperty("os.name", "").toLowerCase();
         String arch = System.getProperty("os.arch", "").toLowerCase();
         boolean isAndroid = false;

         try {
            Class.forName("android.os.Build");
            isAndroid = true;
         } catch (ClassNotFoundException ignored) {
            String vendor = System.getProperty("java.vendor", "").toLowerCase();
            String vmVendor = System.getProperty("java.vm.vendor", "").toLowerCase();
            String specVendor = System.getProperty("java.specification.vendor", "").toLowerCase();
            if (!vendor.contains("android") && !vmVendor.contains("android") && !specVendor.contains("android")) {
               if (Files.exists(Paths.get("/system/build.prop"))) {
                  isAndroid = true;
               }
            } else {
               isAndroid = true;
            }
         }

         String osNorm;
         String archNorm;
         if (isAndroid && !AndroidContextHelper.isAndroid()) {
            // Android 检出但游戏 JVM 里没有 ART 类(如 FCL/Pojav 内嵌 JVM): JNI 桥与外部进程都不可用, 不提供陶瓦
            if (LOGGER != null) {
               LOGGER.warn("Android detected without ART classes in game JVM (embedded-JVM launcher), Terracotta unsupported");
            }

            return null;
         } else if (isAndroid) {
            osNorm = "android";
            if (arch.contains("aarch64") || arch.contains("arm64")) {
               archNorm = "aarch64";
            } else if (arch.contains("armv7") || arch.contains("armeabi") || arch.equals("arm")) {
               archNorm = "armv7";
            } else if (!arch.contains("x86_64") && !arch.contains("amd64")) {
               if (!arch.contains("i386") && !arch.contains("x86")) {
                  return null;
               }

               archNorm = "x86";
            } else {
               archNorm = "x86_64";
            }
         } else {
            if (os.contains("win")) {
               osNorm = "windows";
            } else if (!os.contains("mac") && !os.contains("darwin")) {
               if (!os.contains("linux") && !os.contains("nix")) {
                  return null;
               }

               osNorm = "linux";
            } else {
               osNorm = "macos";
            }

            if (!arch.contains("aarch64") && !arch.contains("arm64")) {
               if (!arch.contains("x86_64") && !arch.contains("amd64")) {
                  return null;
               }

               archNorm = "x86_64";
            } else {
               archNorm = "aarch64";
            }
         }

         for (TerracottaBinary.PlatformInfo p : PLATFORMS) {
            if (p.os.equals(osNorm) && p.arch.equals(archNorm)) {
               return p;
            }
         }

         return null;
      } catch (Exception e) {
         return null;
      }
   }

   public static boolean isReady() {
      if (CURRENT == null) {
         return false;
      }

      try {
         Path binaryPath = CACHE_DIR.resolve(CURRENT.binaryName);
         if (!Files.exists(binaryPath)) {
            return false;
         }

         long mtime = Files.getLastModifiedTime(binaryPath).toMillis();
         long size = Files.size(binaryPath);
         TerracottaBinary.ReadyCache c = readyCache.get();
         if (c != null && c.path.equals(binaryPath) && c.mtime == mtime && c.size == size) {
            return c.ok;
         }

         boolean ok = verifySha256(binaryPath, CURRENT.sha256);
         readyCache.set(new TerracottaBinary.ReadyCache(binaryPath, mtime, size, ok));
         return ok;
      } catch (Exception e) {
         LOGGER.warn("Terracotta verify exception, treat as not ready: {}", e.getMessage());
         return false;
      }
   }

   public static boolean ensureLoaded() {
      if (!isReady()) {
         return false;
      }

      if (CURRENT != null && CURRENT.android) {
         try {
            Class<?> bridge = Class.forName("icu.wuhui.voxlink.terracotta.Android.TerracottaAndroidBridge");
            Boolean loaded = (Boolean)bridge.getMethod("isLibraryLoaded").invoke(null);
            if (loaded != null && loaded) {
               return true;
            }

            Path binaryPath = CACHE_DIR.resolve(CURRENT.binaryName);
            bridge.getMethod("loadLibrary", String.class).invoke(null, binaryPath.toAbsolutePath().toString());
            return true;
         } catch (Throwable t) {
            LOGGER.warn("Terracotta .so load failed: {}", t.getMessage());
            return false;
         }
      } else {
         return true;
      }
   }

   public static CompletableFuture<Path> downloadAsync(Consumer<TerracottaBinary.DownloadProgress> progressCallback) {
      CompletableFuture<Path> future = new CompletableFuture<>();
      if (CURRENT == null) {
         future.completeExceptionally(new IOException("当前平台不支持陶瓦"));
         return future;
      } else if (!downloadingNow.compareAndSet(false, true)) {
         future.completeExceptionally(new IOException("下载已在进行中"));
         return future;
      } else {
         CompletableFuture.runAsync(
            () -> {
               try {
                  try {
                     Files.createDirectories(CACHE_DIR);
                  } catch (IOException e) {
                     future.completeExceptionally(new IOException("无法创建缓存目录: " + e.getMessage(), e));
                     return;
                  }

                  Path binaryPath = CACHE_DIR.resolve(CURRENT.binaryName);
                  if (!CURRENT.android) {
                     Path archivePath = CACHE_DIR.resolve(CURRENT.filename + ".downloading");
                     List<String> urls = raceMirrors(CURRENT.filename);
                     LOGGER.info("[download] Mirror race sort: {}", urls);
                     Exception lastError = null;

                     for (int attempt = 0; attempt < 3 && !Thread.currentThread().isInterrupted() && !downloadCancelled; attempt++) {
                        Iterator i$ = urls.iterator();

                        while (true) {
                           if (i$.hasNext()) {
                              String url = (String)i$.next();
                              if (!Thread.currentThread().isInterrupted() && !downloadCancelled) {
                                 try {
                                    downloadOne(url, archivePath, progressCallback);
                                    if (!downloadCancelled) {
                                       if (progressCallback != null) {
                                          progressCallback.accept(new TerracottaBinary.DownloadProgress(0L, 0L, 100, 0L, false, false, null, "extracting"));
                                       }

                                       extractAndVerify(archivePath, binaryPath, progressCallback);
                                       if (progressCallback != null) {
                                          progressCallback.accept(new TerracottaBinary.DownloadProgress(0L, 0L, 100, 0L, true, false, null));
                                       }

                                       future.complete(binaryPath);
                                       return;
                                    }

                                    Files.deleteIfExists(archivePath);
                                    future.completeExceptionally(new IOException("下载已取消"));
                                    return;
                                 } catch (Exception e) {
                                    if (e instanceof InterruptedException) {
                                       Thread.currentThread().interrupt();
                                       future.completeExceptionally(new IOException("下载被中断", e));
                                       return;
                                    }

                                    if (downloadCancelled) {
                                       try {
                                          Files.deleteIfExists(archivePath);
                                       } catch (IOException var20) {
                                       }

                                       future.completeExceptionally(new IOException("下载已取消"));
                                       return;
                                    }

                                    lastError = e;
                                    LOGGER.warn("Download failed (attempt {}/{}): {} - {}", new Object[]{attempt + 1, 3, url, e.getMessage()});
                                    continue;
                                 }
                              }
                           }

                           if (attempt < 2 && !downloadCancelled) {
                              try {
                                 Thread.sleep(2000L);
                              } catch (InterruptedException ie) {
                                 Thread.currentThread().interrupt();
                                 future.completeExceptionally(new IOException("下载被中断", ie));
                                 return;
                              }
                           }
                           break;
                        }
                     }

                     if (!downloadCancelled) {
                        if (progressCallback != null) {
                           progressCallback.accept(
                              new TerracottaBinary.DownloadProgress(0L, 0L, -1, 0L, false, true, lastError != null ? lastError.getMessage() : "未知错误")
                           );
                        }

                        future.completeExceptionally(new IOException("陶瓦下载失败 (重试3次): " + (lastError != null ? lastError.getMessage() : "未知错误"), lastError));
                     } else {
                        future.completeExceptionally(new IOException("下载已取消"));
                     }
                  } else {
                     Path archivePath = CACHE_DIR.resolve(CURRENT.filename + ".downloading");
                     List<String> urls = raceMirrors(CURRENT.filename);
                     LOGGER.info("[download] Mirror race sort: {}", urls);
                     Exception lastError = null;

                     for (int attempt = 0; attempt < 3 && !Thread.currentThread().isInterrupted() && !downloadCancelled; attempt++) {
                        Iterator i$ = urls.iterator();

                        while (true) {
                           if (i$.hasNext()) {
                              String url = (String)i$.next();
                              if (!Thread.currentThread().isInterrupted() && !downloadCancelled) {
                                 try {
                                    downloadOne(url, archivePath, progressCallback);
                                    if (!downloadCancelled) {
                                       if (progressCallback != null) {
                                          progressCallback.accept(new TerracottaBinary.DownloadProgress(0L, 0L, 100, 0L, false, false, null, "verifying"));
                                       }

                                       if (!verifySha256(archivePath, CURRENT.sha256)) {
                                          throw new IOException("SHA256 校验失败");
                                       }

                                       Files.move(archivePath, binaryPath, StandardCopyOption.REPLACE_EXISTING);
                                       if (progressCallback != null) {
                                          progressCallback.accept(new TerracottaBinary.DownloadProgress(0L, 0L, 100, 0L, true, false, null));
                                       }

                                       future.complete(binaryPath);
                                       return;
                                    }

                                    Files.deleteIfExists(archivePath);
                                    future.completeExceptionally(new IOException("下载已取消"));
                                    return;
                                 } catch (Exception e) {
                                    if (e instanceof InterruptedException) {
                                       Thread.currentThread().interrupt();
                                       future.completeExceptionally(new IOException("下载被中断", e));
                                       return;
                                    }

                                    if (downloadCancelled) {
                                       try {
                                          Files.deleteIfExists(archivePath);
                                       } catch (IOException var21) {
                                       }

                                       future.completeExceptionally(new IOException("下载已取消"));
                                       return;
                                    }

                                    lastError = e;
                                    LOGGER.warn("Download failed (attempt {}/{}): {} - {}", new Object[]{attempt + 1, 3, url, e.getMessage()});
                                    continue;
                                 }
                              }
                           }

                           if (attempt < 2 && !downloadCancelled) {
                              try {
                                 Thread.sleep(2000L);
                              } catch (InterruptedException ie) {
                                 Thread.currentThread().interrupt();
                                 future.completeExceptionally(new IOException("下载被中断", ie));
                                 return;
                              }
                           }
                           break;
                        }
                     }

                     if (!downloadCancelled) {
                        if (progressCallback != null) {
                           progressCallback.accept(
                              new TerracottaBinary.DownloadProgress(0L, 0L, -1, 0L, false, true, lastError != null ? lastError.getMessage() : "未知错误")
                           );
                        }

                        future.completeExceptionally(new IOException("陶瓦下载失败 (重试3次): " + (lastError != null ? lastError.getMessage() : "未知错误"), lastError));
                     } else {
                        future.completeExceptionally(new IOException("下载已取消"));
                     }
                  }
               } finally {
                  downloadingNow.set(false);
               }
            }
         );
         return future;
      }
   }

   private static List<String> raceMirrors(String filename) {
      String[] bases = new String[MIRROR_BASES.length + 2];
      bases[0] = GITHUB_URL;
      bases[1] = GITEE_URL;

      for (int i = 0; i < MIRROR_BASES.length; i++) {
         bases[i + 2] = MIRROR_BASES[i];
      }

      ConcurrentLinkedQueue<String> okQueue = new ConcurrentLinkedQueue<>();
      List<CompletableFuture<Void>> futures = new ArrayList<>();

      for (String base : bases) {
         String url = base + "/" + filename;
         futures.add(CompletableFuture.runAsync(() -> {
            try {
               HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).method("HEAD", BodyPublishers.noBody()).timeout(Duration.ofSeconds(4L)).build();
               HttpResponse<Void> resp = DOWNLOAD_CLIENT.send(req, BodyHandlers.discarding());
               if (resp.statusCode() >= 200 && resp.statusCode() < 400) {
                  okQueue.add(url);
                  LOGGER.info("[probe] Mirror available: {} (status={})", url, resp.statusCode());
               } else {
                  LOGGER.info("[probe] Mirror unavailable: {} (status={})", url, resp.statusCode());
               }
            } catch (Exception e) {
               LOGGER.info("[probe] Mirror probe failed: {} ({})", url, e.getMessage());
            }
         }));
      }

      try {
         CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(6L, TimeUnit.SECONDS);
      } catch (Exception var9) {
      }

      List<String> ordered = new ArrayList<>(okQueue);
      if (ordered.isEmpty()) {
         LOGGER.warn("[probe] All mirror probes failed, try all in original order");

         for (String base : bases) {
            ordered.add(base + "/" + filename);
         }
      }

      return ordered;
   }

   private static void downloadOne(String url, Path archivePath, Consumer<TerracottaBinary.DownloadProgress> progressCallback) throws Exception {
      long existingBytes = 0L;
      if (Files.exists(archivePath)) {
         existingBytes = Files.size(archivePath);
      }

      if (progressCallback != null) {
         int initPct = existingBytes > 0L ? -1 : 0;
         progressCallback.accept(new TerracottaBinary.DownloadProgress(existingBytes, -1L, initPct, 0L, false, false, null, "connecting"));
      }

      if (downloadCancelled) {
         throw new IOException("下载已取消");
      }

      Builder reqBuilder = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(90L)).GET();
      if (existingBytes > 0L) {
         reqBuilder.header("Range", "bytes=" + existingBytes + "-");
      }

      HttpRequest req = reqBuilder.build();
      HttpResponse<InputStream> resp = DOWNLOAD_CLIENT.send(req, BodyHandlers.ofInputStream());
      boolean appendMode = false;
      long total;
      long downloaded;
      if (existingBytes > 0L && resp.statusCode() == 206) {
         appendMode = true;
         downloaded = existingBytes;
         String cr = resp.headers().firstValue("Content-Range").orElse("");
         int slash = cr.lastIndexOf(47);
         if (slash >= 0 && slash < cr.length() - 1) {
            try {
               total = Long.parseLong(cr.substring(slash + 1).trim());
            } catch (NumberFormatException e) {
               total = -1L;
            }
         } else {
            total = -1L;
         }
      } else {
         if (resp.statusCode() != 200) {
            if (resp.statusCode() == 416) {
               Files.deleteIfExists(archivePath);
               throw new IOException("HTTP 416 Range Not Satisfiable (范围请求越界,重置下载): " + url);
            }

            if (resp.statusCode() != 401 && resp.statusCode() != 403) {
               if (resp.statusCode() == 404) {
                  throw new IOException("HTTP 404 Not Found (资源不存在,版本可能已下架): " + url);
               }

               if (resp.statusCode() >= 500) {
                  throw new IOException("HTTP " + resp.statusCode() + " Server Error (服务端错误,稍后重试): " + url);
               }

               throw new IOException("HTTP " + resp.statusCode() + " Unexpected (非预期响应码/unexpected status): " + url);
            }

            throw new IOException("HTTP " + resp.statusCode() + " Auth/Forbidden (鉴权失败/无权限,镜像源可能限流): " + url);
         }

         appendMode = false;
         downloaded = 0L;
         total = resp.headers().firstValueAsLong("Content-Length").orElse(-1L);
      }

      if (progressCallback != null) {
         int pct = total > 0L ? (int)(downloaded * 100L / total) : 0;
         progressCallback.accept(new TerracottaBinary.DownloadProgress(downloaded, total, pct, 0L, false, false, null, null));
      }

      long speedWindowStart = System.currentTimeMillis();
      long speedWindowBytes = downloaded;

      try (
         InputStream is = resp.body();
         OutputStream os = Files.newOutputStream(
            archivePath,
            appendMode
               ? new OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.APPEND}
               : new OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING}
         );
      ) {
         byte[] buf = new byte[65536];
         long lastCallbackMs = System.currentTimeMillis();
         int lastPercent = total > 0L ? (int)(downloaded * 100L / total) : 0;

         int n;
         while ((n = is.read(buf)) != -1) {
            if (downloadCancelled) {
               os.flush();
               throw new IOException("下载已取消");
            }

            while (downloadPaused && !downloadCancelled) {
               try {
                  Thread.sleep(100L);
               } catch (InterruptedException ie) {
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
            int percent = total > 0L ? (int)(downloaded * 100L / total) : -1;
            if (now - lastCallbackMs >= 500L || percent >= 0 && Math.abs(percent - lastPercent) >= 2 || percent == 100) {
               long windowMs = now - speedWindowStart;
               long speedBytes = windowMs > 0L ? (downloaded - speedWindowBytes) * 1000L / windowMs : 0L;
               if (progressCallback != null) {
                  progressCallback.accept(new TerracottaBinary.DownloadProgress(downloaded, total, percent, speedBytes, false, false, null, null));
               }

               lastCallbackMs = now;
               lastPercent = percent;
               speedWindowStart = now;
               speedWindowBytes = downloaded;
            }
         }

         os.flush();
         if (total > 0L && downloaded < total) {
            throw new IOException("下载不完整: " + downloaded + "/" + total + " 字节");
         }

         if (progressCallback != null) {
            long now = System.currentTimeMillis();
            long windowMs = now - speedWindowStart;
            long speedBytes = windowMs > 0L ? (downloaded - speedWindowBytes) * 1000L / windowMs : 0L;
            int percent = total > 0L ? 100 : -1;
            progressCallback.accept(new TerracottaBinary.DownloadProgress(downloaded, total, percent, speedBytes, false, false, null, null));
         }
      }
   }

   private static void extractAndVerify(Path archivePath, Path binaryPath, Consumer<TerracottaBinary.DownloadProgress> progressCallback) throws Exception {
      Path extractDir = Files.createTempDirectory(CACHE_DIR, "extract-");

      try {
         if (downloadCancelled) {
            throw new IOException("下载已取消");
         }

         extractTarGz(archivePath, extractDir);
         if (downloadCancelled) {
            throw new IOException("下载已取消");
         }

         Path found = findBinary(extractDir);
         if (found == null) {
            throw new IOException("压缩包内未找到 " + CURRENT.binaryName);
         }

         Files.move(found, binaryPath, StandardCopyOption.REPLACE_EXISTING);

         try (Stream<Path> extra = Files.walk(extractDir)) {
            extra.filter(Files::isRegularFile)
               .filter(p -> !p.getFileName().toString().endsWith(".pkg"))
               .forEach(p -> {
                  String name = p.getFileName().toString();

                  try {
                     Files.move(p, CACHE_DIR.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                     LOGGER.info("Terracotta package file kept: {}", name);
                  } catch (IOException e) {
                     LOGGER.warn("Failed to keep package file {}: {}", name, e.getMessage());
                  }
               });
         }

         try {
            Files.deleteIfExists(archivePath);
         } catch (IOException e) {
            LOGGER.warn("Failed to delete archive: {}", e.getMessage());
         }

         if (downloadCancelled) {
            throw new IOException("下载已取消");
         }

         if (progressCallback != null) {
            progressCallback.accept(new TerracottaBinary.DownloadProgress(0L, 0L, 100, 0L, false, false, null, "verifying"));
         }

         if (!verifySha256(binaryPath, CURRENT.sha256)) {
            throw new IOException("SHA256 校验失败");
         }

         if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            try {
               binaryPath.toFile().setExecutable(true);
            } catch (Exception var14) {
            }
         }
      } finally {
         try {
            deleteRecursively(extractDir);
         } catch (IOException e) {
            LOGGER.warn("Failed to clean temp extract dir: {}", e.getMessage());
         }
      }
   }

   private static void extractTarGz(Path archivePath, Path destDir) throws IOException {
      try (InputStream gis = new GZIPInputStream(Files.newInputStream(archivePath))) {
         byte[] header = new byte[512];

         while (true) {
            if (downloadCancelled) {
               throw new IOException("下载已取消");
            }

            int read = readFully(gis, header, 512);
            if (read == 0) {
               break;
            }

            if (read < 512) {
               throw new IOException("tar头部不完整 (incomplete tar header)");
            }

            if (isZeroBlock(header)) {
               break;
            }

            String name = readTarString(header, 0, 100);
            if (!name.isEmpty()) {
               long size = parseTarOctal(header, 124, 12);
               if (size < 0L) {
                  throw new IOException("tar条目大小无效: " + size);
               }

               Path outFile = destDir.resolve(name).normalize();
               if (!outFile.startsWith(destDir)) {
                  throw new IOException("tar路径越界: " + name);
               }

               Files.createDirectories(outFile.getParent());
               long remaining = size;

               try (OutputStream os = Files.newOutputStream(outFile)) {
                  byte[] buf = new byte[8192];

                  while (remaining > 0L) {
                     int toRead = (int)Math.min(remaining, buf.length);
                     int n = gis.read(buf, 0, toRead);
                     if (n < 0) {
                        throw new IOException("tar数据不完整 (truncated tar data)");
                     }

                     os.write(buf, 0, n);
                     remaining -= n;
                  }
               }

               int padding = (int)((512L - size % 512L) % 512L);
               if (padding > 0) {
                  for (long skipped = gis.skip(padding); skipped < padding; skipped++) {
                     int n = gis.read();
                     if (n < 0) {
                        break;
                     }
                  }
               }
            }
         }
      }
   }

   private static int readFully(InputStream is, byte[] buf, int len) throws IOException {
      int total = 0;

      while (total < len) {
         int n = is.read(buf, total, len - total);
         if (n < 0) {
            return total;
         }

         total += n;
      }

      return total;
   }

   private static boolean isZeroBlock(byte[] block) {
      for (int i = 0; i < 512; i++) {
         if (block[i] != 0) {
            return false;
         }
      }

      return true;
   }

   private static String readTarString(byte[] header, int offset, int len) {
      int end = offset;

      while (end < offset + len && header[end] != 0) {
         end++;
      }

      return new String(header, offset, end - offset).trim();
   }

   private static long parseTarOctal(byte[] header, int offset, int len) {
      String s = readTarString(header, offset, len).trim();
      if (s.isEmpty()) {
         return 0L;
      }

      try {
         return Long.parseLong(s, 8);
      } catch (NumberFormatException e) {
         return -1L;
      }
   }

   private static Path findBinary(Path dir) throws IOException {
      Path direct = dir.resolve(CURRENT.binaryName);
      if (Files.isRegularFile(direct)) {
         return direct;
      }

      try (Stream<Path> stream = Files.walk(dir)) {
         return stream.filter(x$0 -> Files.isRegularFile(x$0)).filter(p -> p.getFileName().toString().equals(CURRENT.binaryName)).findFirst().orElse(null);
      }
   }

   private static void deleteRecursively(Path dir) throws IOException {
      if (Files.isDirectory(dir)) {
         try (Stream<Path> stream = Files.list(dir)) {
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
            byte[] buf = new byte[8192];

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

   public static String getVersion() {
      return VERSION;
   }

   public static String getLatestVersion() {
      return LATEST_VERSION;
   }

   public static CompletableFuture<String> fetchLatestVersion() {
      return CompletableFuture.supplyAsync(() -> {
         try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5L)).build();
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create("https://terracotta.glavo.site/meta")).timeout(Duration.ofSeconds(8L)).GET().build();
            HttpResponse<String> resp = client.send(req, BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
               LOGGER.debug("Terracotta meta returned non-200: {} use fallback version", resp.statusCode());
               return LATEST_VERSION;
            } else {
               JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
               if (json.has("version") && !json.get("version").isJsonNull()) {
                  return json.get("version").getAsString();
               } else {
                  return json.has("latest_version") && !json.get("latest_version").isJsonNull() ? json.get("latest_version").getAsString() : LATEST_VERSION;
               }
            }
         } catch (Exception e) {
            LOGGER.debug("Failed to fetch Terracotta latest version, use fallback: {}", e.getMessage());
            return LATEST_VERSION;
         }
      });
   }

   public static CompletableFuture<Boolean> isOutdated() {
      return fetchLatestVersion().thenApply(latest -> latest != null && !latest.isEmpty() ? !VERSION.equals(latest) : false);
   }

   public static boolean verifyInstallation() {
      if (CURRENT == null) {
         return false;
      } else {
         Path binaryPath = getBinaryPath();
         if (binaryPath == null || !Files.exists(binaryPath)) {
            return false;
         } else if (CURRENT.sha256 == null) {
            return false;
         } else if (!verifySha256(binaryPath, CURRENT.sha256)) {
            LOGGER.warn("Terracotta binary SHA256 verification failed");
            return false;
         } else if (!CURRENT.android && !System.getProperty("os.name", "").toLowerCase().contains("win") && !Files.isExecutable(binaryPath)) {
            LOGGER.warn("Terracotta binary not executable: {}", binaryPath);
            return false;
         } else {
            return true;
         }
      }
   }

   public static Path getCacheDir() {
      return CACHE_DIR;
   }

   public static Path getBinaryPath() {
      return CURRENT != null && CURRENT.binaryName != null ? CACHE_DIR.resolve(CURRENT.binaryName) : null;
   }

   public static boolean isPlatformSupported() {
      return CURRENT != null;
   }

   private static Path getPendingFile() {
      return CACHE_DIR.resolve(".download_pending");
   }

   public static boolean isDownloadPending() {
      return Files.exists(getPendingFile());
   }

   public static void markDownloadPending() {
      try {
         Files.createDirectories(CACHE_DIR);
         Files.createFile(getPendingFile());
      } catch (IOException e) {
         LOGGER.warn("Failed to write download intent marker: {}", e.getMessage());
      }
   }

   public static void clearDownloadPending() {
      try {
         Files.deleteIfExists(getPendingFile());
      } catch (IOException var1) {
      }
   }

   static {
      Path base = FabricLoader.getInstance().getGameDir().resolve(".voxlink").resolve("terracotta");
      CACHE_DIR = base;
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

      public DownloadProgress(
         long downloadedBytes, long totalBytes, int percent, long speedBps, boolean done, boolean failed, String errorMessage, String stage
      ) {
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

   private static final class ReadyCache {
      final Path path;
      final long mtime;
      final long size;
      final boolean ok;

      ReadyCache(Path p, long t, long s, boolean o) {
         this.path = p;
         this.mtime = t;
         this.size = s;
         this.ok = o;
      }
   }
}
