package icu.wuhui.voxlink.ui;

import icu.wuhui.voxlink.VoxLinkMod;
import java.lang.reflect.Constructor;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;

public final class ChatCompat {
   private ChatCompat() {
   }

   public static ClickEvent copyToClipboard(String value) {
      try {
         return ModernApi.copy(value);
      } catch (Throwable t) {
         return LegacyApi.copy(value);
      }
   }

   public static HoverEvent showText(Component text) {
      try {
         return ModernApi.showText(text);
      } catch (Throwable t) {
         return LegacyApi.showText(text);
      }
   }

   public static Style styleWithCopy(String copyValue, Component hoverText) {
      Style style = Style.EMPTY;
      ClickEvent click = copyToClipboard(copyValue);
      if (click != null) {
         style = style.withClickEvent(click);
      }

      HoverEvent hover = showText(hoverText);
      if (hover != null) {
         style = style.withHoverEvent(hover);
      }

      return style;
   }

   // 新API隔离: 1.21.5以下链接此类的NCDFE由外层try捕获
   private static final class ModernApi {
      private ModernApi() {
      }

      static ClickEvent copy(String value) {
         return new ClickEvent.CopyToClipboard(value);
      }

      static HoverEvent showText(Component text) {
         return new HoverEvent.ShowText(text);
      }
   }

   private static final class LegacyApi {
      private static Constructor<?> clickCtor;
      private static Object copyAction;
      private static Constructor<?> hoverCtor;
      private static Object showTextAction;
      private static boolean inited = false;

      private LegacyApi() {
      }

      static ClickEvent copy(String value) {
         try {
            init();
            if (clickCtor != null && copyAction != null) {
               return (ClickEvent)clickCtor.newInstance(copyAction, value);
            }
         } catch (Throwable t) {
            VoxLinkMod.LOGGER.debug("ChatCompat legacy copy failed", t);
         }

         return null;
      }

      static HoverEvent showText(Component text) {
         try {
            init();
            if (hoverCtor != null && showTextAction != null) {
               return (HoverEvent)hoverCtor.newInstance(showTextAction, text);
            }
         } catch (Throwable t) {
            VoxLinkMod.LOGGER.debug("ChatCompat legacy hover failed", t);
         }

         return null;
      }

      private static void init() {
         if (inited) {
            return;
         }
         inited = true;

         try {
            for (Class<?> inner : ClickEvent.class.getDeclaredClasses()) {
               if (inner.isEnum()) {
                  Object[] consts = inner.getEnumConstants();
                  // Action枚举序: CHANGE_PAGE(4), COPY_TO_CLIPBOARD(5)
                  if (consts != null && consts.length >= 6) {
                     copyAction = consts[5];
                     clickCtor = ClickEvent.class.getDeclaredConstructor(inner, String.class);
                     clickCtor.setAccessible(true);
                  }
                  break;
               }
            }

            for (Class<?> inner : HoverEvent.class.getDeclaredClasses()) {
               if (inner.isEnum()) {
                  for (Object c : inner.getEnumConstants()) {
                     if (c.toString().contains("SHOW_TEXT")) {
                        showTextAction = c;
                        break;
                     }
                  }

                  if (showTextAction != null) {
                     try {
                        hoverCtor = HoverEvent.class.getDeclaredConstructor(inner, Component.class);
                     } catch (NoSuchMethodException e) {
                        // 泛型擦除后value参数为Object
                        hoverCtor = HoverEvent.class.getDeclaredConstructor(inner, Object.class);
                     }

                     hoverCtor.setAccessible(true);
                  }
                  break;
               }
            }
         } catch (Throwable t) {
            VoxLinkMod.LOGGER.debug("ChatCompat legacy init failed", t);
         }
      }
   }
}
