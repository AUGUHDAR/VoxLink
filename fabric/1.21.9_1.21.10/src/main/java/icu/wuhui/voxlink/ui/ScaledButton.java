package icu.wuhui.voxlink.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** 标签按容器宽度自动缩字号的按钮: 放得下用原字号, 放不下等比缩小, 最小 0.6 倍。 */
public class ScaledButton extends Button {
   public ScaledButton(int x, int y, int w, int h, Component msg, OnPress onPress) {
      super(x, y, w, h, msg, onPress, DEFAULT_NARRATION);
   }

   @Override
   protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
      float scale = this.fitScale();
      if (scale >= 1.0F) {
         super.renderWidget(graphics, mouseX, mouseY, partialTick);
         return;
      }

      int cx = this.getX() + this.getWidth() / 2;
      int cy = this.getY() + (this.getHeight() - 8) / 2;
      graphics.pose().pushMatrix();
      graphics.pose().translate((float)cx, (float)cy);
      graphics.pose().scale(scale, scale);
      graphics.pose().translate((float)(-cx), (float)(-cy));
      super.renderWidget(graphics, mouseX, mouseY, partialTick);
      graphics.pose().popMatrix();
   }

   private float fitScale() {
      int textW = Minecraft.getInstance().font.width(this.getMessage());
      int availW = this.getWidth() - 8;
      if (textW <= availW || textW <= 0) {
         return 1.0F;
      }

      float s = (float)availW / (float)textW;
      return Math.max(0.6F, s);
   }
}
