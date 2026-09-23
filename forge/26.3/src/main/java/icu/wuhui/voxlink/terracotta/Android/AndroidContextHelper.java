package icu.wuhui.voxlink.terracotta.Android;

import java.lang.reflect.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AndroidContextHelper {
   private static final Logger LOGGER = LoggerFactory.getLogger("voxlink-terracotta");

   private AndroidContextHelper() {
   }

   public static Object getActivityContext() {
      try {
         Class.forName("android.app.Activity");
      } catch (ClassNotFoundException e) {
         return null;
      }

      Object ctx = tryFromActivityThread();
      return ctx != null ? ctx : null;
   }

   private static Object tryFromActivityThread() {
      try {
         Class<?> atClass = Class.forName("android.app.ActivityThread");
         Method currentAT = atClass.getDeclaredMethod("currentActivityThread");
         currentAT.setAccessible(true);
         Object at = currentAT.invoke(null);
         if (at == null) {
            return null;
         }

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

   public static boolean isAndroid() {
      try {
         Class.forName("android.app.Activity");
         return true;
      } catch (ClassNotFoundException e) {
         return false;
      }
   }
}
