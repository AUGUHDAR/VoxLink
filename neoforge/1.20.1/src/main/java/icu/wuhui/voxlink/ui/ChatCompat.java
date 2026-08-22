package icu.wuhui.voxlink.ui;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;

public final class ChatCompat {
   private ChatCompat() {
   }

   public static ClickEvent copyToClipboard(String value) {
      return new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, value);
   }

   public static HoverEvent showText(Component text) {
      return new HoverEvent(HoverEvent.Action.SHOW_TEXT, text);
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
}
