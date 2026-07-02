package icu.wuhui.voxlink.ui;

import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.room.RoomInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class VoxLinkScreen extends Screen {
    private final Screen parent;
    private RoomInfo lastRenderedRoom = null;
    private boolean lastRenderedIsHost = false;
    private boolean needsRebuild = true;
    private long lastRebuildTime = 0;

    private static boolean isInSingleplayerWorld() {
        return Minecraft.getInstance().getSingleplayerServer() != null;
    }

    public VoxLinkScreen(Screen parent) {
        super(Component.translatable("voxlink.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        needsRebuild = true;
    }

    @Override
    public void tick() {
        RoomInfo currentRoom = VoxLinkMod.getRoomManager().getCurrentRoom();
        boolean currentIsHost = currentRoom != null && currentRoom.isHost();
        long now = System.currentTimeMillis();
        if (now - lastRebuildTime < 250) return;
        if (!java.util.Objects.equals(currentRoom, lastRenderedRoom) || currentIsHost != lastRenderedIsHost || needsRebuild) {
            lastRenderedRoom = currentRoom;
            lastRenderedIsHost = currentIsHost;
            lastRebuildTime = now;
            rebuildWidgetsForState();
        }
    }

    private void rebuildWidgetsForState() {
        clearWidgets();
        int centerX = this.width / 2;
        int startY = this.height / 2 - 30;

        RoomInfo currentRoom = VoxLinkMod.getRoomManager().getCurrentRoom();

        if (currentRoom != null) {
            if (currentRoom.isHost()) {
                addRenderableWidget(Button.builder(
                        Component.translatable("voxlink.manage_room"),
                        button -> Minecraft.getInstance().setScreen(new ManageRoomScreen(VoxLinkScreen.this, currentRoom))
                ).bounds(centerX - 100, startY, 200, 20).build());
            }
        } else if (isInSingleplayerWorld()) {
            addRenderableWidget(Button.builder(
                    Component.translatable("voxlink.create_room"),
                    button -> Minecraft.getInstance().setScreen(new CreateRoomScreen(this))
            ).bounds(centerX - 100, startY, 200, 20).build());
        } else {
            addRenderableWidget(Button.builder(
                    Component.translatable("voxlink.join_by_code"),
                    button -> Minecraft.getInstance().setScreen(new JoinRoomScreen(this))
            ).bounds(centerX - 100, startY, 200, 20).build());

            addRenderableWidget(Button.builder(
                    Component.translatable("voxlink.browse_rooms"),
                    button -> Minecraft.getInstance().setScreen(new RoomBrowserScreen(this))
            ).bounds(centerX - 100, startY + 24, 200, 20).build());
        }

        addRenderableWidget(Button.builder(
                Component.translatable("voxlink.back"),
                button -> onClose()
        ).bounds(centerX - 100, this.height - 28, 200, 20).build());

        // P2P中继开关: 人人为我，我为人人
        boolean relayOn = VoxLinkMod.getConfig().isRelayEnabled();
        Component connMode = currentRoom != null ? currentRoom.getConnectionMode() : null;
        boolean usingRelay = connMode != null && connMode.getString().contains(
                Component.translatable("voxlink.relay.connected_via").getString());
        Button relayBtn = Button.builder(
                Component.translatable("voxlink.relay.toggle",
                        Component.translatable(relayOn ? "voxlink.relay.on" : "voxlink.relay.off")),
                button -> {
                    boolean newVal = !VoxLinkMod.getConfig().isRelayEnabled();
                    VoxLinkMod.getConfig().setRelayEnabled(newVal);
                    VoxLinkMod.getConfig().save();
                    button.setMessage(Component.translatable("voxlink.relay.toggle",
                            Component.translatable(newVal ? "voxlink.relay.on" : "voxlink.relay.off")));
                }
        ).bounds(centerX - 100, this.height - 52, 200, 20).build();
        if (usingRelay) relayBtn.active = false;
        addRenderableWidget(relayBtn);

        needsRebuild = false;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        int centerX = this.width / 2;
        graphics.drawCenteredString(this.font, this.title.getString(), centerX, 20, 0xFFFFFFFF);

        RoomInfo currentRoom = VoxLinkMod.getRoomManager().getCurrentRoom();
        if (currentRoom != null) {
            String codeText = "\u00a7e" + Component.translatable("voxlink.screen.room_code_display", currentRoom.getCode()).getString();
            int maxWidth = this.width - 20;
            String clippedCode = codeText;
            if (this.font.width(codeText) > maxWidth) {
                while (this.font.width(clippedCode + "...") > maxWidth && clippedCode.length() > 0) {
                    clippedCode = clippedCode.substring(0, clippedCode.length() - 1);
                }
                clippedCode = clippedCode + "...";
            }
            graphics.drawCenteredString(this.font, clippedCode, centerX, 36, 0xFFFFFF55);

            if (!currentRoom.isHost()) {
                Component connMode = currentRoom.getConnectionMode();
                if (connMode != null && !connMode.getString().isEmpty()) {
                    String text = connMode.getString();
                    int connMaxWidth = this.width - 20;
                    if (this.font.width(text) > connMaxWidth) {
                        while (this.font.width(text + "...") > connMaxWidth && text.length() > 0) {
                            text = text.substring(0, text.length() - 1);
                        }
                        text = text + "...";
                    }
                    graphics.drawCenteredString(this.font, text, centerX, 50, 0xFF888888);
                }
            }
        }

        // 中继开关说明（始终显示）
        int maxWidth = this.width - 20;
        drawCenteredClipped(graphics,
                Component.translatable("voxlink.relay.hint").getString(),
                centerX, this.height - 74, 0xFF888888, maxWidth);
        drawCenteredClipped(graphics,
                Component.translatable("voxlink.relay.slogan").getString(),
                centerX, this.height - 64, 0xFFAAAAAA, maxWidth);

    }

    private void drawCenteredClipped(GuiGraphics graphics, String text, int centerX, int y, int color, int maxWidth) {
        String clipped = text;
        if (this.font.width(text) > maxWidth) {
            while (this.font.width(clipped + "...") > maxWidth && clipped.length() > 0) {
                clipped = clipped.substring(0, clipped.length() - 1);
            }
            clipped = clipped + "...";
        }
        graphics.drawCenteredString(this.font, clipped, centerX, y, color);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public void removed() {
        super.removed();
    }
}
