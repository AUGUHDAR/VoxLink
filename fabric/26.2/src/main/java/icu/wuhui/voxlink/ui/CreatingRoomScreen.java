package icu.wuhui.voxlink.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

public class CreatingRoomScreen extends VoxLinkScreenBase {
    private final CreateRoomScreen parent;
    private long startTime;
    private static final long TIMEOUT_MS = 30000;

    //布局常量
    private static final int BTN_W = 200;
    private static final int BTN_H = 20;
    private static final int MARGIN_X = 20;
    private static final int CANCEL_BTN_Y_OFFSET = 20;
    private static final int COLOR_ERROR_RGB = 0xFF5555;
    private static final int COLOR_WARNING = 0xFFFFFF55;
    private static final int UAC_HINT_Y_OFFSET = 44;
    private static final int COLOR_MUTED = 0xFFAAAAAA;

    protected CreatingRoomScreen(CreateRoomScreen parent) {
        super(Component.translatable("voxlink.create_room"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        if (startTime == 0) startTime = System.currentTimeMillis();
        this.addRenderableWidget(Button.builder(
                Component.translatable("voxlink.cancel"),
                button -> onCancel()
        ).bounds(this.width / 2 - BTN_W / 2, this.height / 2 + CANCEL_BTN_Y_OFFSET, BTN_W, BTN_H).build());
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public void onClose() {
        onCancel();
    }

    //debounce 用户主动取消 走cancelled分支 不走timeout
    private void onCancel() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendSystemMessage(
                    Component.translatable("voxlink.create_room.cancelled").withStyle(s -> s.withColor(COLOR_ERROR_RGB)));
        }
        parent.onCancelCreate();
        mc.gui.setScreen(parent);
    }

    @Override
    public void tick() {
        super.tick();
        if (System.currentTimeMillis() - startTime > TIMEOUT_MS) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.sendSystemMessage(Component.translatable("voxlink.create_room.timeout").withStyle(s -> s.withColor(COLOR_ERROR_RGB)));
            }
            parent.onCreateTimeout();
            mc.gui.setScreen(parent);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        long elapsed = (System.currentTimeMillis() - startTime) / 1000;
        String msg = Component.translatable("voxlink.create_room.creating").getString()
                + Component.translatable("voxlink.create_room.elapsed_seconds", elapsed).getString();
        int maxWidth = this.width - MARGIN_X;
        String clipped = msg;
        if (fontWidth(msg) > maxWidth) {
            while (fontWidth(clipped + "...") > maxWidth && clipped.length() > 0) {
                clipped = clipped.substring(0, clipped.length() - 1);
            }
            clipped = clipped + "...";
        }
        drawCenteredString(graphics, clipped, this.width / 2, this.height / 2, COLOR_WARNING);
        drawCenteredString(graphics,
                Component.translatable("voxlink.create_room.uac_hint").getString(),
                this.width / 2, this.height / 2 + UAC_HINT_Y_OFFSET, COLOR_MUTED);
    }
}
