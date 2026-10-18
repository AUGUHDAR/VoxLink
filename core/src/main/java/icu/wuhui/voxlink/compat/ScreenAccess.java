package icu.wuhui.voxlink.compat;

import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Field;

/**
 * 跨时代取当前屏。mc.screen(<=1.21) / mc.gui.screen()(26.2+)。
 * 反射实现，方法与字段两形态自动判别。
 */
public final class ScreenAccess {
   private static volatile Member screenGetter;
   private static volatile boolean getterOnGui;
   private static volatile boolean resolved;

   private ScreenAccess() {}

   /** 返回当前打开的 Screen; 失败返回 null */
   public static Object current(Object mc) {
      if (mc == null) {
         return null;
      }
      if (!resolved) {
         resolve(mc);
      }
      Member getter = screenGetter;
      if (getter == null) {
         return null;
      }
      try {
         Object holder = getterOnGui ? mc.getClass().getMethod("gui").invoke(mc) : mc;
         if (holder == null) {
            return null;
         }
         if (getter instanceof Method m) {
            return m.invoke(holder);
         }
         return ((Field) getter).get(holder);
      } catch (Throwable t) {
         return null;
      }
   }

   /**
    * 解析一次取屏句柄，兼容三形态：
    * mc.screen 字段 / mc.gui.screen() 方法 / mc.gui.screen 字段。
    */
   private static synchronized void resolve(Object mc) {
      if (resolved) {
         return;
      }
      try {
         // mc 或 mc.gui 上的 screen 直接字段
         try {
            Field f = mc.getClass().getField("screen");
            f.setAccessible(true);
            screenGetter = f;
            getterOnGui = false;
            return;
         } catch (NoSuchFieldException ignored) {
            // fall through
         }
         // 形态2: mc.gui 上取 screen 方法或字段
         Object gui = mc.getClass().getMethod("gui").invoke(mc);
         if (gui != null) {
            try {
               screenGetter = gui.getClass().getMethod("screen");
               getterOnGui = true;
               return;
            } catch (NoSuchMethodException ignored) {
               // fall through
            }
            try {
               Field f = gui.getClass().getField("screen");
               f.setAccessible(true);
               screenGetter = f;
               getterOnGui = true;
               return;
            } catch (NoSuchFieldException ignored) {
               // fall through
            }
         }
         screenGetter = null;
      } catch (Throwable t) {
         screenGetter = null;
      } finally {
         resolved = true;
      }
   }
}
