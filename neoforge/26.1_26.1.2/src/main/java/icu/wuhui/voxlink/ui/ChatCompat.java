package icu.wuhui.voxlink.ui;

import icu.wuhui.voxlink.VoxLinkMod;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;

public final class ChatCompat {
   private static Constructor<?> copyToClipboardCtor;
   private static Constructor<?> oldClickEventCtor;
   private static Object copyAction;
   private static boolean copyChecked = false;
   private static Constructor<?> showTextCtor;
   private static Constructor<?> oldHoverEventCtor;
   private static Object showTextAction;
   private static boolean hoverChecked = false;

   private ChatCompat() {
   }

   public static ClickEvent copyToClipboard(String value) {
      checkClickEvent();

      try {
         if (copyToClipboardCtor != null) {
            return (ClickEvent)copyToClipboardCtor.newInstance(value);
         }

         if (oldClickEventCtor != null && copyAction != null) {
            return (ClickEvent)oldClickEventCtor.newInstance(copyAction, value);
         }
      } catch (Exception e) {
         VoxLinkMod.LOGGER.debug("ChatCompat reflection failed", e);
      }

      return null;
   }

   public static HoverEvent showText(Component text) {
      checkHoverEvent();

      try {
         if (showTextCtor != null) {
            return (HoverEvent)showTextCtor.newInstance(text);
         }

         if (oldHoverEventCtor != null && showTextAction != null) {
            return (HoverEvent)oldHoverEventCtor.newInstance(showTextAction, text);
         }
      } catch (Exception e) {
         VoxLinkMod.LOGGER.debug("ChatCompat reflection failed", e);
      }

      return null;
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

   private static void checkClickEvent() {
      if (!copyChecked) {
         copyChecked = true;

         for (Class<?> inner : ClickEvent.class.getDeclaredClasses()) {
            try {
               Constructor<?> c = inner.getDeclaredConstructor(String.class);
               if (ClickEvent.class.isAssignableFrom(inner)) {
                  copyToClipboardCtor = c;
                  copyToClipboardCtor.setAccessible(true);
                  return;
               }
            } catch (NoSuchMethodException e) {
               VoxLinkMod.LOGGER.debug("ChatCompat reflection failed", e);
            }
         }

         try {
            Class<?> actionClass = null;

            for (Class<?> inner : ClickEvent.class.getDeclaredClasses()) {
               if (inner.isEnum()) {
                  actionClass = inner;
                  break;
               }
            }

            if (actionClass != null) {
               Object action = tryByName(actionClass, "copy_to_clipboard");
               if (action == null) {
                  for (Object e : actionClass.getEnumConstants()) {
                     String s = e.toString();
                     if (s.contains("COPY") || s.contains("copy")) {
                        action = e;
                        break;
                     }
                  }
               }

               if (action == null && actionClass.getEnumConstants().length >= 6) {
                  action = actionClass.getEnumConstants()[5];
               }

               if (action != null) {
                  copyAction = action;
                  oldClickEventCtor = ClickEvent.class.getDeclaredConstructor(actionClass, String.class);
                  oldClickEventCtor.setAccessible(true);
               }
            }
         } catch (Exception e) {
            VoxLinkMod.LOGGER.debug("ChatCompat reflection failed", e);
         }
      }
   }

   private static void checkHoverEvent() {
      if (!hoverChecked) {
         hoverChecked = true;

         for (Class<?> inner : HoverEvent.class.getDeclaredClasses()) {
            try {
               Constructor<?> c = inner.getDeclaredConstructor(Component.class);
               if (HoverEvent.class.isAssignableFrom(inner)) {
                  showTextCtor = c;
                  showTextCtor.setAccessible(true);
                  return;
               }
            } catch (NoSuchMethodException var8) {
            }
         }

         try {
            Class<?> actionClass = null;

            for (Class<?> inner : HoverEvent.class.getDeclaredClasses()) {
               if (inner.isEnum()) {
                  actionClass = inner;
                  break;
               }
            }

            if (actionClass != null) {
               Object action = tryByName(actionClass, "show_text");
               if (action == null) {
                  for (Object e : actionClass.getEnumConstants()) {
                     String s = e.toString();
                     if (s.contains("SHOW_TEXT") || s.contains("show_text")) {
                        action = e;
                        break;
                     }
                  }
               }

               if (action == null && actionClass.getEnumConstants().length >= 1) {
                  action = actionClass.getEnumConstants()[0];
               }

               if (action != null) {
                  showTextAction = action;

                  try {
                     oldHoverEventCtor = HoverEvent.class.getDeclaredConstructor(actionClass, Component.class);
                  } catch (NoSuchMethodException e) {
                     oldHoverEventCtor = HoverEvent.class.getDeclaredConstructor(actionClass, Object.class);
                  }

                  oldHoverEventCtor.setAccessible(true);
               }
            }
         } catch (Exception e) {
            VoxLinkMod.LOGGER.debug("ChatCompat reflection failed", e);
         }
      }
   }

   private static Object tryByName(Class<?> actionClass, String name) {
      try {
         Method byName = actionClass.getMethod("byName", String.class);
         return byName.invoke(null, name);
      } catch (Exception e) {
         return null;
      }
   }
}
