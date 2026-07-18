package icu.wuhui.voxlink.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

//1.20.x 专属: 原生 API, 无反射
//背景: 主菜单 renderBackground(dirt), 游戏内 renderBackground(半透明+模糊)
public abstract class VoxLinkScreenBase extends Screen {
    private final List<GuiEventListener> myWidgets = new ArrayList<>();

    private static final int MARGIN_X = 20;
    private static final int COLOR_TITLE = 0xFFFFFFFF;

    protected VoxLinkScreenBase(Component title) {
        super(title);
    }

    @Override
    protected void init() {
        super.init();
        clearOurWidgets();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    protected void clearOurWidgets() {
        for (GuiEventListener l : new ArrayList<>(myWidgets)) {
            super.removeWidget(l);
        }
        myWidgets.clear();
    }

    @Override
    protected <T extends GuiEventListener & Renderable & NarratableEntry> T addRenderableWidget(T widget) {
        T w = super.addRenderableWidget(widget);
        myWidgets.add(w);
        return w;
    }

    @Override
    protected <T extends GuiEventListener & NarratableEntry> T addWidget(T widget) {
        T w = super.addWidget(widget);
        myWidgets.add(w);
        return w;
    }

    @Override
    public void removeWidget(GuiEventListener listener) {
        super.removeWidget(listener);
        myWidgets.remove(listener);
    }

    protected void drawCenteredString(GuiGraphics graphics, String text, int centerX, int y, int color) {
        int width = Minecraft.getInstance().font.width(text);
        graphics.drawString(Minecraft.getInstance().font, text, centerX - width / 2, y, color);
    }

    protected void drawString(GuiGraphics graphics, String text, int x, int y, int color) {
        graphics.drawString(Minecraft.getInstance().font, text, x, y, color);
    }

    protected int fontWidth(String text) {
        return Minecraft.getInstance().font.width(text);
    }

    protected void drawCenteredClipped(GuiGraphics graphics, String text, int centerX, int y, int color, int maxWidth) {
        String clipped = text;
        if (fontWidth(text) > maxWidth) {
            while (fontWidth(clipped + "...") > maxWidth && clipped.length() > 0) {
                clipped = clipped.substring(0, clipped.length() - 1);
            }
            clipped = clipped + "...";
        }
        drawCenteredString(graphics, clipped, centerX, y, color);
    }

    protected void drawCenteredClipped(GuiGraphics graphics, String text, int centerX, int y, int color) {
        drawCenteredClipped(graphics, text, centerX, y, color, this.width - MARGIN_X);
    }

    protected void drawTitle(GuiGraphics graphics, int y) {
        drawCenteredClipped(graphics, this.title.getString(), this.width / 2, y, COLOR_TITLE, this.width - MARGIN_X);
    }
    protected void drawCenteredComponent(GuiGraphics graphics, Component component, int centerX, int y, int color) {
        int width = Minecraft.getInstance().font.width(component);
        graphics.drawString(Minecraft.getInstance().font, component, centerX - width / 2, y, color);
    }

    protected int fontWidth(Component component) {
        return Minecraft.getInstance().font.width(component);
    }
}
