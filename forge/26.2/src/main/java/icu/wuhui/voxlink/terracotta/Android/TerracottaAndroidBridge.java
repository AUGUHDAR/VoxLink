package icu.wuhui.voxlink.terracotta.Android;

import java.io.File;
import java.io.FileDescriptor;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TerracottaAndroidBridge {
   private static final Logger LOGGER = LoggerFactory.getLogger("voxlink-terracotta");
   private static volatile boolean libraryLoaded = false;
   private static volatile boolean initialized = false;
   private static volatile RandomAccessFile loggingFile;
   private static volatile Runnable vpnCallback;
   private static volatile Object pendingRequest;
   private static final long FD_PENDING = 2147483648L;
   private static final long FD_REJECT = 2147483649L;
   private static final AtomicLong fdHolder = new AtomicLong(2147483648L);

   private TerracottaAndroidBridge() {
   }

   public static void loadLibrary(String absolutePath) {
      if (!libraryLoaded) {
         System.load(absolutePath);
         libraryLoaded = true;
         LOGGER.info("Terracotta .so loaded: {}", absolutePath);
      }
   }

   public static boolean isLibraryLoaded() {
      return libraryLoaded;
   }

   public static boolean isInitialized() {
      return initialized;
   }

   public static synchronized void initialize(Object context, Runnable onVpnRequired) {
      if (!libraryLoaded) {
         throw new IllegalStateException("library not loaded");
      }

      if (!initialized) {
         try {
            // Terracotta 要求 baseDir 是已存在的目录(machine-id 等写入其下), 不能传文件路径
            Path baseDirPath = Files.createTempDirectory("voxlink-terracotta-");
            String baseDir = baseDirPath.toAbsolutePath().toString();
            File logFile = File.createTempFile("voxlink-terracotta-log-", ".log");
            loggingFile = new RandomAccessFile(logFile, "rw");
            int fd = (int)invokeParcelFdDetach(loggingFile.getFD());
            vpnCallback = onVpnRequired;
            int code = start0(baseDir, fd);
            if (code != 0) {
               throw new RuntimeException("start0 failed: " + code);
            }

            initialized = true;
            LOGGER.info("Terracotta JNI init succeeded");
         } catch (Exception e) {
            throw new RuntimeException("initialize failed: " + e.getMessage(), e);
         }
      }
   }

   private static long invokeParcelFdDetach(FileDescriptor fd) throws Exception {
      try {
         Class<?> pfdClass = Class.forName("android.os.ParcelFileDescriptor");
         Method dup = pfdClass.getMethod("dup", FileDescriptor.class);
         Object pfd = dup.invoke(null, fd);
         Method detach = pfdClass.getMethod("detachFd");
         Object result = detach.invoke(pfd);
         return ((Integer)result).intValue();
      } catch (Throwable t) {
         throw new RuntimeException("ParcelFileDescriptor.dup failed: " + t.getMessage(), t);
      }
   }

   public static String getState() {
      if (!initialized) {
         throw new IllegalStateException("not initialized");
      } else {
         return getState0();
      }
   }

   public static void setWaiting() {
      if (initialized) {
         setWaiting0();
      }
   }

   public static void setScanning(String room, String player) {
      if (!initialized) {
         throw new IllegalStateException("not initialized");
      }

      setScanning0(room, player);
   }

   public static boolean setGuesting(String room, String player) {
      if (!initialized) {
         throw new IllegalStateException("not initialized");
      } else {
         return setGuesting0(room, player);
      }
   }

   public static int verifyRoomCode(String room) {
      if (!initialized) {
         throw new IllegalStateException("not initialized");
      } else {
         return verifyRoomCode0(room);
      }
   }

   private static int onVpnServiceStateChanged(byte ip1, byte ip2, byte ip3, byte ip4, short networkLen, String cidr) throws Exception {
      if (pendingRequest != null) {
         throw new IllegalStateException("pending request exists");
      }

      fdHolder.set(2147483648L);
      Runnable cb = vpnCallback;
      if (cb != null) {
         cb.run();
      }

      long start = System.currentTimeMillis();

      while (true) {
         long v = fdHolder.get();
         if (v != 2147483648L) {
            if (v == 2147483649L) {
               pendingRequest = null;
               throw new IllegalStateException("VpnService rejected");
            }

            pendingRequest = null;
            return (int)v;
         }

         if (System.currentTimeMillis() - start >= 30000L) {
            throw new IllegalStateException("VpnService timeout 30s");
         }

         Thread.yield();
      }
   }

   public static void submitVpnFd(int fd) {
      fdHolder.set(fd);
   }

   public static int submitVpnFdBlocking(Object vpnBuilder) throws Exception {
      Class<?> builderClass = vpnBuilder.getClass();
      Method establish = builderClass.getMethod("establish");
      Object pfd = establish.invoke(vpnBuilder);
      if (pfd == null) {
         return -1;
      }

      Class<?> pfdClass = Class.forName("android.os.ParcelFileDescriptor");
      Method getFd = pfdClass.getMethod("getFd");
      Integer fd = (Integer)getFd.invoke(pfd);
      int fdVal = fd != null ? fd : -1;
      fdHolder.set(fdVal);
      return fdVal;
   }

   public static void rejectVpn() {
      fdHolder.set(2147483649L);
   }

   private static native int start0(String var0, int var1);

   private static native String getState0();

   private static native void setWaiting0();

   private static native void setScanning0(String var0, String var1);

   private static native boolean setGuesting0(String var0, String var1);

   private static native int verifyRoomCode0(String var0);

   private static native String getMetadata0();

   private static native long prepareExportLogs0();

   private static native void finishExportLogs0(long var0);

   private static native void panic0();

   static {
      System.setProperty("net.burningtnt.terracotta.native_location", TerracottaAndroidBridge.class.getName().replace('.', '/'));
   }
}
