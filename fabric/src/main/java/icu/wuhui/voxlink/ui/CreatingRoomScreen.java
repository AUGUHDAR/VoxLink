package icu.wuhui.voxlink.ui;

import icu.wuhui.voxlink.VoxLinkMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class CreatingRoomScreen extends Screen {
    private final CreateRoomScreen parent;
    private long startTime;
    private static final long TIMEOUT_MS = 90000;

    protected CreatingRoomScreen(CreateRoomScreen parent) {
        super(Component.translatable("voxlink.create_room"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        if (startTime == 0) startTime = System.currentTimeMillis();
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public void tick() {
        super.tick();
        if (System.currentTimeMillis() - startTime > TIMEOUT_MS) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.translatable("voxlink.create_room.timeout").withStyle(s -> s.withColor(0xFF5555)), false);
            }
            parent.onCreateTimeout();
            mc.setScreen(parent);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        long elapsed = (System.currentTimeMillis() - startTime) / 1000;
        String msg = Component.translatable("voxlink.create_room.creating").getString() + " (" + elapsed + "s)";
        int maxWidth = this.width - 20;
        String clipped = msg;
        if (this.font.width(msg) > maxWidth) {
            while (this.font.width(clipped + "...") > maxWidth && clipped.length() > 0) {
                clipped = clipped.substring(0, clipped.length() - 1);
            }
            clipped = clipped + "...";
        }
        graphics.drawCenteredString(this.font, clipped, this.width / 2, this.height / 2, 0xFFFFFF55);
    }
}
