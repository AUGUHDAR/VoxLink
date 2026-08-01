package icu.wuhui.voxlink.terracotta.Android;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.RandomAccessFile;
import java.util.concurrent.atomic.AtomicLong;

//debounce Android陶瓦JNI入口 PC端反射调用 native端通过native_location定位
public final class TerracottaAndroidBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger("voxlink-terracotta");

    static {
        //debounce 让native端按此路径定位Java类
        System.setProperty("net.burningtnt.terracotta.native_location",
                TerracottaAndroidBridge.class.getName().replace('.', '/'));
    }

    private TerracottaAndroidBridge() {}

    private static volatile boolean libraryLoaded = false;
    private static volatile boolean initialized = false;
    private static volatile RandomAccessFile loggingFile;
    private static volatile Runnable vpnCallback;

    //debounce 显式加载.so 由TerracottaBinary调用
    public static void loadLibrary(String absolutePath) {
        if (libraryLoaded) return;
        System.load(absolutePath);
        libraryLoaded = true;
        LOGGER.info("Terracotta .so loaded: {}", absolutePath);
    }

    public static boolean isLibraryLoaded() { return libraryLoaded; }
    public static boolean isInitialized() { return initialized; }

    //debounce 初始化 触发VpnService回调
    public static synchronized void initialize(Object context, Runnable onVpnRequired) {
        if (!libraryLoaded) throw new IllegalStateException("library not loaded");
        if (initialized) return;
        try {
            String baseDir = java.io.File.createTempFile("voxlink-terracotta-", "").getAbsolutePath();
            new java.io.File(baseDir).mkdirs();
            java.io.File logFile = java.io.File.createTempFile("voxlink-terracotta-log-", ".log");
            loggingFile = new RandomAccessFile(logFile, "rw");
            int fd = (int) invokeParcelFdDetach(loggingFile.getFD());
            vpnCallback = onVpnRequired;
            int code = start0(baseDir, fd);
            if (code != 0) throw new RuntimeException("start0 failed: " + code);
            initialized = true;
            LOGGER.info("Terracotta JNI init succeeded");
        } catch (Exception e) {
            throw new RuntimeException("initialize failed: " + e.getMessage(), e);
        }
    }

    //debounce 拿RandomAccessFile的fd
    private static long invokeParcelFdDetach(java.io.FileDescriptor fd) throws Exception {
        try {
            Class<?> pfdClass = Class.forName("android.os.ParcelFileDescriptor");
            java.lang.reflect.Method dup = pfdClass.getMethod("dup", java.io.FileDescriptor.class);
            Object pfd = dup.invoke(null, fd);
            java.lang.reflect.Method detach = pfdClass.getMethod("detachFd");
            Object result = detach.invoke(pfd);
            return ((Integer) result).intValue();
        } catch (Throwable t) {
            throw new RuntimeException("ParcelFileDescriptor.dup failed: " + t.getMessage(), t);
        }
    }

    public static String getState() {
        if (!initialized) throw new IllegalStateException("not initialized");
        return getState0();
    }

    public static void setWaiting() {
        if (!initialized) return;
        setWaiting0();
    }

    public static void setScanning(String room, String player) {
        if (!initialized) throw new IllegalStateException("not initialized");
        setScanning0(room, player);
    }

    public static boolean setGuesting(String room, String player) {
        if (!initialized) throw new IllegalStateException("not initialized");
        return setGuesting0(room, player);
    }

    public static int verifyRoomCode(String room) {
        if (!initialized) throw new IllegalStateException("not initialized");
        return verifyRoomCode0(room);
    }

    private static volatile Object pendingRequest;
    private static final long FD_PENDING = ((long) Integer.MAX_VALUE) + 1;
    private static final long FD_REJECT = FD_PENDING + 1;
    private static final AtomicLong fdHolder = new AtomicLong(FD_PENDING);

    //debounce native回调 触发VpnService申请
    @SuppressWarnings("unused")
    private static int onVpnServiceStateChanged(byte ip1, byte ip2, byte ip3, byte ip4,
                                                 short networkLen, String cidr) throws Exception {
        if (pendingRequest != null) throw new IllegalStateException("pending request exists");
        fdHolder.set(FD_PENDING);
        Runnable cb = vpnCallback;
        if (cb != null) cb.run();
        long start = System.currentTimeMillis();
        while (true) {
            long v = fdHolder.get();
            if (v == FD_PENDING) {
                if (System.currentTimeMillis() - start >= 30000) {
                    throw new IllegalStateException("VpnService timeout 30s");
                }
                Thread.yield();
            } else if (v == FD_REJECT) {
                pendingRequest = null;
                throw new IllegalStateException("VpnService rejected");
            } else {
                pendingRequest = null;
                return (int) v;
            }
        }
    }

    //debounce 供VpnService调用 提交VPN fd
    public static void submitVpnFd(int fd) {
        fdHolder.set(fd);
    }

    //debounce VpnService同步等待fd 提交后返回fd
    public static int submitVpnFdBlocking(Object vpnBuilder) throws Exception {
        Class<?> builderClass = vpnBuilder.getClass();
        java.lang.reflect.Method establish = builderClass.getMethod("establish");
        Object pfd = establish.invoke(vpnBuilder);
        if (pfd == null) return -1;
        Class<?> pfdClass = Class.forName("android.os.ParcelFileDescriptor");
        java.lang.reflect.Method getFd = pfdClass.getMethod("getFd");
        Integer fd = (Integer) getFd.invoke(pfd);
        int fdVal = fd != null ? fd : -1;
        //debounce pfd交给VpnService管理 陶瓦只拿fd值
        fdHolder.set(fdVal);
        return fdVal;
    }

    public static void rejectVpn() {
        fdHolder.set(FD_REJECT);
    }

    private static native int start0(String baseDir, int loggingFd);
    private static native String getState0();
    private static native void setWaiting0();
    private static native void setScanning0(String room, String player);
    private static native boolean setGuesting0(String room, String player);
    private static native int verifyRoomCode0(String room);
    private static native String getMetadata0();
    private static native long prepareExportLogs0();
    private static native void finishExportLogs0(long ptr);
    private static native void panic0();
}
