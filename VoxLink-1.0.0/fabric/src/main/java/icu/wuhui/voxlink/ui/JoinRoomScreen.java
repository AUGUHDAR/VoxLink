package icu.wuhui.voxlink.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class JoinRoomScreen extends Screen {
    private final Screen parent;
    private EditBox codeField;
    private EditBox passwordField;
    private Button joinButton;
    private String statusMessage = "";
    private int statusColor = 0xFFFFFFFF;
    private String savedCode = "";
    private String savedPassword = "";

    public JoinRoomScreen(Screen parent) {
        super(Component.translatable("voxlink.join_room"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        this.clearWidgets();

        int centerX = this.width / 2;
        int startY = this.height / 2 - 50;

        codeField = new EditBox(this.font, centerX - 100, startY, 200, 20, Component.translatable("voxlink.room_code"));
        codeField.setMaxLength(6);
        codeField.setFilter(s -> s.matches("[A-HJ-NP-Z2-9]*"));
        codeField.setHint(Component.translatable("voxlink.enter_code"));
        if (!savedCode.isEmpty()) codeField.setValue(savedCode);
        codeField.setResponder(text -> {
            if (joinButton != null) {
                joinButton.active = text.matches("^[A-HJ-NP-Z2-9]{6}$");
            }
            if (text.length() >= 6 && passwordField != null) {
                this.setInitialFocus(passwordField);
            }
        });
        this.addRenderableWidget(codeField);

        passwordField = new EditBox(this.font, centerX - 100, startY + 30, 200, 20, Component.translatable("voxlink.room_password"));
        passwordField.setMaxLength(32);
        passwordField.setHint(Component.translatable("voxlink.enter_password"));
        if (!savedPassword.isEmpty()) passwordField.setValue(savedPassword);
        this.addRenderableWidget(passwordField);

        joinButton = Button.builder(Component.translatable("voxlink.join_room"), button -> attemptJoin())
                .bounds(centerX - 100, startY + 65, 200, 20).build();
        joinButton.active = !savedCode.isEmpty() && savedCode.matches("^[A-HJ-NP-Z2-9]{6}$");
        this.addRenderableWidget(joinButton);

        this.addRenderableWidget(Button.builder(Component.translatable("voxlink.back"), button -> goBack())
                .bounds(centerX - 100, startY + 95, 200, 20).build());

        this.setInitialFocus(codeField);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public void onClose() {
        goBack();
    }

    private void goBack() {
        Minecraft.getInstance().setScreen(parent);
    }

    private void attemptJoin() {
        String code = codeField.getValue().trim().toUpperCase();
        if (code.isEmpty()) {
            statusMessage = "\u00a7c" + Component.translatable("voxlink.join_room.enter_code").getString();
            statusColor = 0xFFFF5555;
            return;
        }
        if (!code.matches("^[A-HJ-NP-Z2-9]{6}$")) {
            statusMessage = "\u00a7c" + Component.translatable("voxlink.error.invalid_room_code").getString();
            statusColor = 0xFFFF5555;
            return;
        }
        savedCode = code;
        savedPassword = passwordField.getValue().trim();
        String password = savedPassword.isEmpty() ? null : savedPassword;
        Minecraft.getInstance().setScreen(new AttemptingJoinScreen(this, code, password));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 15, 0xFFFFFFFF);

        if (!statusMessage.isEmpty()) {
            String clipped = statusMessage;
            int maxWidth = this.width - 20;
            if (this.font.width(statusMessage) > maxWidth) {
                while (this.font.width(clipped + "...") > maxWidth && clipped.length() > 0) {
                    clipped = clipped.substring(0, clipped.length() - 1);
                }
                clipped = clipped + "...";
            }
            graphics.drawCenteredString(this.font, clipped, this.width / 2, this.height / 2 + 70, statusColor);
        }
    }
}
