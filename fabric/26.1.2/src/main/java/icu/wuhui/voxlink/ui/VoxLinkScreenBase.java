package icu.wuhui.voxlink.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.Predicate;

import java.util.ArrayList;
import java.util.List;

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

    // 26.x: GuiGraphicsExtractor.drawString → text
    protected void drawCenteredString(GuiGraphicsExtractor graphics, String text, int centerX, int y, int color) {
        int width = Minecraft.getInstance().font.width(text);
        graphics.text(Minecraft.getInstance().font, text, centerX - width / 2, y, color);
    }

    protected void drawString(GuiGraphicsExtractor graphics, String text, int x, int y, int color) {
        graphics.text(Minecraft.getInstance().font, text, x, y, color);
    }

    // 26.x: EditBox.setFilter 移除, 用 setResponder 模拟
    protected void setInputFilter(EditBox field, Predicate<String> filter) {
        final boolean[] reverting = {false};
        final String[] last = {field.getValue()};
        field.setResponder(s -> {
            if (reverting[0]) return;
            if (filter.test(s)) {
                last[0] = s;
            } else {
                reverting[0] = true;
                field.setValue(last[0]);
                reverting[0] = false;
            }
        });
    }

    protected int fontWidth(String text) {
        return Minecraft.getInstance().font.width(text);
    }

    protected void drawCenteredClipped(GuiGraphicsExtractor graphics, String text, int centerX, int y, int color, int maxWidth) {
        String clipped = text;
        if (fontWidth(text) > maxWidth) {
            while (fontWidth(clipped + "...") > maxWidth && clipped.length() > 0) {
                clipped = clipped.substring(0, clipped.length() - 1);
            }
            clipped = clipped + "...";
        }
        drawCenteredString(graphics, clipped, centerX, y, color);
    }

    protected void drawCenteredClipped(GuiGraphicsExtractor graphics, String text, int centerX, int y, int color) {
        drawCenteredClipped(graphics, text, centerX, y, color, this.width - MARGIN_X);
    }

    protected void drawTitle(GuiGraphicsExtractor graphics, int y) {
        drawCenteredClipped(graphics, this.title.getString(), this.width / 2, y, COLOR_TITLE, this.width - MARGIN_X);
    }
    protected void drawCenteredComponent(GuiGraphicsExtractor graphics, Component component, int centerX, int y, int color) {
        int width = Minecraft.getInstance().font.width(component);
        graphics.text(Minecraft.getInstance().font, component.getString(), centerX - width / 2, y, color);
    }

    protected int fontWidth(Component component) {
        return Minecraft.getInstance().font.width(component);
    }
}
