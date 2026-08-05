package icu.wuhui.voxlink.terracotta.Android;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

//debounce Android反射拿Context 失败返回null由调用方降级
public final class AndroidContextHelper {
    private static final Logger LOGGER = LoggerFactory.getLogger("voxlink-terracotta");

    private AndroidContextHelper() {}

    //debounce 多路径反射拿Activity Context 失败返回null
    public static Object getActivityContext() {
        try {
            Class.forName("android.app.Activity");
        } catch (ClassNotFoundException e) {
            return null;
        }
        Object ctx = tryFromActivityThread();
        if (ctx != null) return ctx;
        return null;
    }

    //debounce 反射ActivityThread.currentApplication()
    private static Object tryFromActivityThread() {
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Method currentAT = atClass.getDeclaredMethod("currentActivityThread");
            currentAT.setAccessible(true);
            Object at = currentAT.invoke(null);
            if (at == null) return null;
            Method getApplication = atClass.getDeclaredMethod("getApplication");
            getApplication.setAccessible(true);
            Object app = getApplication.invoke(at);
            if (app != null) {
                LOGGER.info("Got Application Context via reflection");
                return app;
            }
        } catch (Throwable t) {
            LOGGER.debug("Failed to get Application from ActivityThread: {}", t.getMessage());
        }
        return null;
    }

    //debounce 判断当前是否Android环境
    public static boolean isAndroid() {
        try {
            Class.forName("android.app.Activity");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
