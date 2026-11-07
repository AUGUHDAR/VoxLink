package icu.wuhui.voxlink.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** 标签按容器宽度自动缩字号的按钮: 放得下用原字号, 放不下等比缩小, 最小 0.6 倍。 */
public class ScaledButton extends Button {
   public ScaledButton(int x, int y, int w, int h, Component msg, OnPress onPress) {
      super(x, y, w, h, msg, onPress, DEFAULT_NARRATION);
   }

   @Override
   public void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
      Component msg = this.getMessage();
      int textW = Minecraft.getInstance().font.width(msg);
      int availW = this.getWidth() - 8;
      float scale = textW <= availW || textW <= 0 ? 1.0F : Math.max(0.6F, (float)availW / (float)textW);
      int cx = this.getX() + this.getWidth() / 2;
      int cy = this.getY() + (this.getHeight() - 8) / 2;
      if (scale >= 1.0F) {
         graphics.text(Minecraft.getInstance().font, msg.getString(), cx - textW / 2, cy, 0xFFFFFFFF);
         return;
      }

      graphics.pose().pushMatrix();
      graphics.pose().translate((float)cx, (float)cy);
      graphics.pose().scale(scale, scale);
      graphics.pose().translate((float)(-cx), (float)(-cy));
      graphics.text(Minecraft.getInstance().font, msg.getString(), cx - textW / 2, cy, 0xFFFFFFFF);
      graphics.pose().popMatrix();
   }
}
