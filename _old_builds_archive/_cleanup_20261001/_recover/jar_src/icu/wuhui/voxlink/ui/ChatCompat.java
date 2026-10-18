package icu.wuhui.voxlink.ui;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.ClickEvent.CopyToClipboard;
import net.minecraft.network.chat.HoverEvent.ShowText;

public final class ChatCompat {
   private ChatCompat() {
   }

   public static ClickEvent copyToClipboard(String value) {
      return new CopyToClipboard(value);
   }

   public static HoverEvent showText(Component text) {
      return new ShowText(text);
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
