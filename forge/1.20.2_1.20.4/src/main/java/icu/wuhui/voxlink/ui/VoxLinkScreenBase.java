package icu.wuhui.voxlink.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.GuiGraphics;

public abstract class VoxLinkScreenBase extends Screen {
   private final List<GuiEventListener> myWidgets = new ArrayList<>();
   private static final int MARGIN_X = 20;

   protected VoxLinkScreenBase(Component title) {
      super(title);
   }

   protected void init() {
      super.init();
      this.clearOurWidgets();
   }

   protected void clearOurWidgets() {
      for (GuiEventListener l : new ArrayList<>(this.myWidgets)) {
         super.removeWidget(l);
      }

      this.myWidgets.clear();
   }

   protected <T extends GuiEventListener & Renderable & NarratableEntry> T addRenderableWidget(T widget) {
      T w = (T)super.addRenderableWidget(widget);
      this.myWidgets.add(w);
      return w;
   }

   protected <T extends GuiEventListener & NarratableEntry> T addWidget(T widget) {
      T w = (T)super.addWidget(widget);
      this.myWidgets.add(w);
      return w;
   }

   public void removeWidget(GuiEventListener listener) {
      super.removeWidget(listener);
      this.myWidgets.remove(listener);
   }

   protected void drawCenteredString(GuiGraphics graphics, String text, int centerX, int y, int color) {
      int width = Minecraft.getInstance().font.width(text);
      graphics.drawString(Minecraft.getInstance().font, text, centerX - width / 2, y, color);
   }

   protected void drawString(GuiGraphics graphics, String text, int x, int y, int color) {
      graphics.drawString(Minecraft.getInstance().font, text, x, y, color);
   }

   protected void setInputFilter(EditBox field, Predicate<String> filter) {
      boolean[] reverting = new boolean[]{false};
      String[] last = new String[]{field.getValue()};
      field.setResponder(s -> {
         if (!reverting[0]) {
            if (filter.test(s)) {
               last[0] = s;
            } else {
               reverting[0] = true;
               field.setValue(last[0]);
               reverting[0] = false;
            }
         }
      });
   }

   protected int fontWidth(String text) {
      return Minecraft.getInstance().font.width(text);
   }

   protected void drawCenteredClipped(GuiGraphics graphics, String text, int centerX, int y, int color, int maxWidth) {
      String clipped = text;
      if (this.fontWidth(text) > maxWidth) {
         while (this.fontWidth(clipped + "...") > maxWidth && clipped.length() > 0) {
            clipped = clipped.substring(0, clipped.length() - 1);
         }

         clipped = clipped + "...";
      }

      this.drawCenteredString(graphics, clipped, centerX, y, color);
   }

   protected void drawCenteredClipped(GuiGraphics graphics, String text, int centerX, int y, int color) {
      this.drawCenteredClipped(graphics, text, centerX, y, color, this.width - 20);
   }

   protected void drawTitle(GuiGraphics graphics, int y) {
      this.drawCenteredClipped(graphics, this.title.getString(), this.width / 2, y, -1, this.width - 20);
   }

   protected void drawCenteredComponent(GuiGraphics graphics, Component component, int centerX, int y, int color) {
      int width = Minecraft.getInstance().font.width(component);
      graphics.drawString(Minecraft.getInstance().font, component.getString(), centerX - width / 2, y, color);
   }

   protected int fontWidth(Component component) {
      return Minecraft.getInstance().font.width(component);
   }
}
