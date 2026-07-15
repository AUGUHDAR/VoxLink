package icu.wuhui.voxlink.terracotta;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

public final class TerracottaAndroidBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger("voxlink-terracotta");

    private static final String TERRACOTTA_API_CLASS = "net.burningtnt.terracotta.TerracottaAndroidAPI";

    private static final AtomicReference<Class<?>> apiClassRef = new AtomicReference<>();
    private static volatile boolean checked = false;
    private static volatile Boolean initialized = null;
    private static volatile long initializedCheckTime = 0;
    private static final long INIT_CACHE_TTL_MS = 30000;

    private TerracottaAndroidBridge() {}

    public static boolean isAndroid() {
        return TerracottaBinary.isAndroid();
    }

    private static Class<?> findApiClass() {
        Class<?> cached = apiClassRef.get();
        if (cached != null || checked) return cached;
        synchronized (TerracottaAndroidBridge.class) {
            cached = apiClassRef.get();
            if (cached != null || checked) return cached;
            checked = true;
            try {
                cached = Class.forName(TERRACOTTA_API_CLASS);
                apiClassRef.set(cached);
                LOGGER.info("检测到 TerracottaAndroidAPI 在 classpath (启动器已集成陶瓦库)");
            } catch (ClassNotFoundException e) {
                LOGGER.info("TerracottaAndroidAPI 不在 classpath (启动器未集成陶瓦)");
            }
            return cached;
        }
    }

    public static boolean isInitialized() {
        if (!isAndroid()) return false;
        Boolean cached = initialized;
        long now = System.currentTimeMillis();
        if (cached != null && cached && now - initializedCheckTime < INIT_CACHE_TTL_MS) {
            return cached;
        }
        Class<?> api = findApiClass();
        if (api == null) {
            initialized = false;
            return false;
        }
        try {
            Object result = invokeStatic(api, "getState");
            if (result == null) {
                initialized = false;
                LOGGER.info("陶瓦库在 classpath 但 getState 返回 null, 可能未初始化");
                return false;
            }
            initialized = true;
            initializedCheckTime = now;
            LOGGER.info("陶瓦已由启动器初始化, 可正常使用");
            return true;
        } catch (Exception e) {
            initialized = false;
            LOGGER.info("陶瓦库在 classpath 但未初始化, 请在启动器中启用陶瓦: {}", e.getMessage());
            return false;
        }
    }

    public static boolean isLibraryPresent() {
        return findApiClass() != null;
    }

    public static String getState() {
        Class<?> api = findApiClass();
        if (api == null) return null;
        try {
            return (String) invokeStatic(api, "getState");
        } catch (Exception e) {
            LOGGER.debug("陶瓦 getState 失败: {}", e.getMessage());
            return null;
        }
    }

    public static boolean setWaiting() {
        Class<?> api = findApiClass();
        if (api == null) return false;
        try {
            invokeStatic(api, "setWaiting");
            return true;
        } catch (Exception e) {
            LOGGER.warn("陶瓦 setWaiting 失败: {}", e.getMessage());
            return false;
        }
    }

    public static boolean setScanning(String room, String player) {
        Class<?> api = findApiClass();
        if (api == null) return false;
        try {
            Method m = api.getDeclaredMethod("setScanning", String.class, String.class, java.util.List.class);
            m.setAccessible(true);
            m.invoke(null, room, player, null);
            return true;
        } catch (Exception e) {
            LOGGER.warn("陶瓦 setScanning 失败: {}", e.getMessage());
            return false;
        }
    }

    public static boolean setGuesting(String room, String player) {
        Class<?> api = findApiClass();
        if (api == null) return false;
        try {
            Method m = api.getDeclaredMethod("setGuesting", String.class, String.class, java.util.List.class);
            m.setAccessible(true);
            Object result = m.invoke(null, room, player, null);
            if (result instanceof Boolean) return (Boolean) result;
            return true;
        } catch (Exception e) {
            LOGGER.warn("陶瓦 setGuesting 失败: {}", e.getMessage());
            return false;
        }
    }

    public static void refreshInitialized() {
        initialized = null;
        initializedCheckTime = 0;
    }

    private static Object invokeStatic(Class<?> clazz, String methodName) throws Exception {
        Method m = clazz.getDeclaredMethod(methodName);
        m.setAccessible(true);
        return m.invoke(null);
    }
}
