package icu.wuhui.voxlink.terracotta;

import icu.wuhui.voxlink.terracotta.Android.AndroidContextHelper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TerracottaProcess {
   private static final Logger LOGGER = LoggerFactory.getLogger("voxlink-terracotta");
   private static final long START_TOTAL_TIMEOUT_MS = 120000L;
   private static final long EXIT_GRACE_MS = 10000L;
   private static final long POLL_INTERVAL_MS = 500L;
   private static final AtomicBoolean startingGuard = new AtomicBoolean(false);
   private static volatile long startingSince = 0L;
   private static final long STARTING_GUARD_TIMEOUT_MS = 60000L;
   private static final AtomicReference<Process> PROCESS = new AtomicReference<>();
   private static volatile int httpPort = 0;
   private static volatile Path portFile;
   private static final Pattern PORT_PATTERN = Pattern.compile("\"port\"\\s*:\\s*(\\d+)");
   private static final Pattern SECONDARY_PORT_PATTERN = Pattern.compile("secondary mode[^0-9]*(\\d+)");
   private static volatile String lastErrorLine = null;
   private static final AtomicBoolean restartUsed = new AtomicBoolean(false);
   private static volatile boolean isSecondary = false;

   private TerracottaProcess() {
   }

   private static synchronized boolean acquireStartingGuard() {
      if (startingGuard.get() && startingSince > 0L && System.currentTimeMillis() - startingSince > 60000L) {
         LOGGER.warn("startingGuard stuck over 60s, force reset");
         startingGuard.set(false);
         startingSince = 0L;
      }

      if (!startingGuard.compareAndSet(false, true)) {
         return false;
      }

      startingSince = System.currentTimeMillis();
      return true;
   }

   public static CompletableFuture<Integer> start() {
      if (!acquireStartingGuard()) {
         return CompletableFuture.failedFuture(new IOException("Terracotta 启动已在进行中"));
      }

      boolean enteredAsync = false;

      try {
         if (AndroidContextHelper.isAndroid()) {
            if (!TerracottaBinary.ensureLoaded()) {
               return CompletableFuture.failedFuture(new TerracottaNotReadyException("陶瓦 .so 加载失败"));
            }

            if (httpPort != 0 && isAlive()) {
               return CompletableFuture.completedFuture(httpPort);
            }

            enteredAsync = true;
            return startAndroidInternal();
         } else {
            if (!TerracottaBinary.isReady()) {
               return CompletableFuture.failedFuture(new TerracottaNotReadyException("陶瓦二进制未就绪, 请先下载"));
            }

            if (httpPort != 0) {
               if (isAlive()) {
                  return CompletableFuture.completedFuture(httpPort);
               }

               stop();
            }

            isSecondary = false;
            enteredAsync = true;
            return startInternal();
         }
      } finally {
         if (!enteredAsync) {
            startingGuard.set(false);
            startingSince = 0L;
         }
      }
   }

   private static CompletableFuture<Integer> startAndroidInternal() {
      return CompletableFuture.supplyAsync(() -> {
         try {
            Class<?> bridge = Class.forName("icu.wuhui.voxlink.terracotta.Android.TerracottaAndroidBridge");
            Object ctx = AndroidContextHelper.getActivityContext();
            if (ctx == null) {
               throw new IOException("无法获取Android Context");
            }

            Runnable onVpnRequired = () -> {
               try {
                  startVpnServiceReflection(ctx);
               } catch (Throwable tx) {
                  LOGGER.warn("Failed to start VpnService: {}", tx.getMessage());

                  try {
                     bridge.getMethod("rejectVpn").invoke(null);
                  } catch (Throwable var4) {
                  }
               }
            };
            bridge.getMethod("initialize", Object.class, Runnable.class).invoke(null, ctx, onVpnRequired);
            httpPort = -1;
            isSecondary = false;
            LOGGER.info("Terracotta JNI mode started");
            return httpPort;
         } catch (Throwable t) {
            throw new RuntimeException("Android JNI 初始化失败: " + t.getMessage(), t);
         } finally {
            startingGuard.set(false);
            startingSince = 0L;
         }
      });
   }

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

      Object intent = intentClass.getConstructor(contextClass, Class.class).newInstance(ctx, serviceClass);
      String action = "icu.wuhui.voxlink.terracotta.action.START";
      intentClass.getMethod("setAction", String.class).invoke(intent, action);

      try {
         Class<?> ccClass = Class.forName("androidx.core.content.ContextCompat");
         ccClass.getMethod("startForegroundService", contextClass, intentClass).invoke(null, ctx, intent);
      } catch (ClassNotFoundException e) {
         contextClass.getMethod("startService", intentClass).invoke(ctx, intent);
      }
   }

   private static void stopVpnServiceReflection() {
      try {
         Object ctx = AndroidContextHelper.getActivityContext();
         if (ctx == null) {
            return;
         }

         Class<?> contextClass = Class.forName("android.content.Context");
         Class<?> intentClass = Class.forName("android.content.Intent");
         Class<?> serviceClass = Class.forName("icu.wuhui.voxlink.terracotta.Android.TerracottaVpnService");
         Object intent = intentClass.getConstructor(contextClass, Class.class).newInstance(ctx, serviceClass);
         intentClass.getMethod("setAction", String.class).invoke(intent, "icu.wuhui.voxlink.terracotta.action.STOP");

         try {
            Class<?> ccClass = Class.forName("androidx.core.content.ContextCompat");
            ccClass.getMethod("startForegroundService", contextClass, intentClass).invoke(null, ctx, intent);
         } catch (ClassNotFoundException e) {
            contextClass.getMethod("startService", intentClass).invoke(ctx, intent);
         }
      } catch (Throwable t) {
         LOGGER.debug("Failed to stop VpnService: {}", t.getMessage());
      }
   }

   private static CompletableFuture<Integer> startInternal() {
      return CompletableFuture.supplyAsync(() -> {
         try {
            return launchAndAwait(false);
         } catch (Exception e) {
            throw new RuntimeException("启动 Terracotta 失败: " + e.getMessage(), e);
         } finally {
            startingGuard.set(false);
            startingSince = 0L;
         }
      });
   }

   private static Integer launchAndAwait(boolean isRetry) throws Exception {
      Process proc = null;

      try {
         Path binary = TerracottaBinary.getBinaryPath();
         Path portDir = Files.createTempDirectory("voxlink-terracotta-" + ThreadLocalRandom.current().nextLong());
         portFile = portDir.resolve("http").toAbsolutePath();

         try {
            Files.deleteIfExists(portFile);
         } catch (IOException e) {
            LOGGER.warn("Failed to clean portFile: {}", e.getMessage());
         }

         ProcessBuilder pb = new ProcessBuilder(binary.toString(), "--hmcl", portFile.toString());
         pb.redirectErrorStream(true);
         proc = pb.start();
         PROCESS.set(proc);
         lastErrorLine = null;
         startOutputDrain(proc, isRetry);
         long waitStart = System.currentTimeMillis();
         long lastLogMs = waitStart;
         long procExitTime = -1L;

         while (true) {
            if (!proc.isAlive()) {
               if (isSecondary && httpPort > 0) {
                  restartUsed.set(false);
                  LOGGER.info("Terracotta secondary process exited, reuse primary port {}", httpPort);
                  return httpPort;
               }

               if (procExitTime == -1L) {
                  procExitTime = System.currentTimeMillis();
               }

               if (System.currentTimeMillis() - procExitTime >= EXIT_GRACE_MS) {
                  if (!isRetry && restartUsed.compareAndSet(false, true)) {
                     LOGGER.warn("Terracotta process exited unexpectedly, try restart once. Error line: {}", lastErrorLine);
                     stopInternalQuiet();
                     return launchAndAwait(true);
                  }

                  String errLine = lastErrorLine != null ? lastErrorLine : "无stdout错误输出";
                  throw new RuntimeException("Terracotta" + (isRetry ? "重启后仍" : "进程") + "意外退出: " + errLine);
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
                     LOGGER.info("Terracotta process{} started, HTTP port {}", isRetry ? " (restart)" : "", port);
                     return port;
                  }
               }
            }

            long now = System.currentTimeMillis();
            long elapsed = now - waitStart;
            if (elapsed > START_TOTAL_TIMEOUT_MS) {
               proc.destroyForcibly();
               throw new RuntimeException("陶瓦" + (isRetry ? "重启" : "启动") + "超时(120s)未写出端口文件: " + (lastErrorLine != null ? lastErrorLine : "无错误输出"));
            }

            if (now - lastLogMs >= 10000L) {
               LOGGER.info("Waiting for Terracotta {}, waited {}ms", isRetry ? "restart" : "start", elapsed);
               lastLogMs = now;
            }

            Thread.sleep(POLL_INTERVAL_MS);
         }
      } catch (Exception e) {
         if (proc != null && proc.isAlive()) {
            proc.destroyForcibly();
         }

         throw e;
      }
   }

   private static void startOutputDrain(Process proc, boolean isRetry) {
      Thread drainStdout = new Thread(
         () -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
               int[] lineCount = new int[]{0};

               String line;
               while ((line = br.readLine()) != null) {
                  String lower = line.toLowerCase(Locale.ROOT);
                  boolean isError = lower.contains("error")
                     || lower.contains("fatal")
                     || lower.contains("panic")
                     || lower.contains("failed to bind")
                     || lower.contains("address already in use")
                     || lower.contains("exception")
                     || lower.contains("cannot ")
                     || lower.contains("failed to");
                  if (!isError && lineCount[0]++ >= 20) {
                     LOGGER.debug("[terracotta] {}", line);
                  } else {
                     LOGGER.info("[terracotta] {}", line);
                  }

                  if (lower.contains("running in secondary mode")) {
                     Matcher spm = SECONDARY_PORT_PATTERN.matcher(line);
                     if (spm.find()) {
                        int sp = Integer.parseInt(spm.group(1));
                        if (sp > 0) {
                           httpPort = sp;
                           isSecondary = true;
                           LOGGER.info("Terracotta secondary mode{}, reuse primary port {}", isRetry ? " (restart)" : "", sp);
                        }
                     }
                  }

                  if (isError) {
                     lastErrorLine = line;
                  }
               }
            } catch (IOException var10x) {
            }
         },
         "terracotta-stdout-drain"
      );
      drainStdout.setDaemon(true);
      drainStdout.start();
   }

   public static int getHttpPort() {
      return httpPort;
   }

   public static boolean isAlive() {
      if (httpPort != -1) {
         return httpPort > 0;
      }

      try {
         Class<?> bridge = Class.forName("icu.wuhui.voxlink.terracotta.Android.TerracottaAndroidBridge");
         Boolean inited = (Boolean)bridge.getMethod("isInitialized").invoke(null);
         return inited != null && inited;
      } catch (Throwable t) {
         return false;
      }
   }

   public static boolean isResponsive() {
      if (!isAlive()) {
         return false;
      }

      if (httpPort == -1) {
         return true;
      }

      try {
         TerracottaClient.getMeta(httpPort).orTimeout(3L, TimeUnit.SECONDS).join();
         return true;
      } catch (Exception e) {
         return false;
      }
   }

   public static String getLastErrorLine() {
      return lastErrorLine;
   }

   public static void stop() {
      if (httpPort == -1) {
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
         startingSince = 0L;
      } else if (isSecondary) {
         LOGGER.info("Terracotta secondary mode disabled, keep primary process");
         httpPort = 0;
         isSecondary = false;
         restartUsed.set(false);
         startingGuard.set(false);
         startingSince = 0L;
         PROCESS.set(null);
      } else {
         if (httpPort > 0) {
            try {
               TerracottaClient.get(httpPort, "/panic?peaceful=true").orTimeout(500L, TimeUnit.MILLISECONDS).join();
            } catch (Exception var7) {
            }
         }

         Process p = PROCESS.getAndSet(null);
         if (p != null && p.isAlive()) {
            try {
               p.destroy();
            } catch (Exception var6) {
            }

            try {
               if (!p.waitFor(3L, TimeUnit.SECONDS)) {
                  try {
                     p.destroyForcibly();
                  } catch (Exception var4) {
                  }

                  LOGGER.warn("Terracotta process did not exit within {}s", 3);
               }
            } catch (InterruptedException e) {
               Thread.currentThread().interrupt();
            }
         }

         httpPort = 0;
         restartUsed.set(false);
         startingGuard.set(false);
         startingSince = 0L;
         if (portFile != null) {
            try {
               Files.deleteIfExists(portFile);
            } catch (IOException e) {
               LOGGER.warn("Failed to delete port file: {}", e.getMessage());
            }

            portFile = null;
         }
      }
   }

   private static void stopInternalQuiet() {
      Process p = PROCESS.getAndSet(null);
      if (p != null && p.isAlive()) {
         try {
            p.destroy();
         } catch (Exception var4) {
         }

         try {
            if (!p.waitFor(1L, TimeUnit.SECONDS)) {
               try {
                  p.destroyForcibly();
               } catch (Exception var2) {
               }
            }
         } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
         }
      }

      httpPort = 0;
   }
}
