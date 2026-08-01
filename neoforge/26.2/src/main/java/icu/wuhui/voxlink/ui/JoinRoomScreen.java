package icu.wuhui.voxlink.ui;

import icu.wuhui.voxlink.terracotta.RoomCodeRouter;
import icu.wuhui.voxlink.terracotta.TerracottaManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class JoinRoomScreen extends VoxLinkScreenBase {
    private static final int BTN_W = 200;
    private static final int BTN_H = 20;
    private static final int GAP = 4;
    private static final int BTN_COUNT = 4;
    private static final int GAP_COUNT = 3;
    private static final int FORM_EXTRA = 10;
    private static final int CODE_MIN_LENGTH = 6;
    private static final int FIELD_SPACING = 10;
    private static final int HINT_Y_OFFSET = 6;
    private static final int STATUS_Y_OFFSET = 32;
    private static final int TERRACOTTA_HINT_Y_OFFSET = 18;
    private static final int TITLE_Y = 15;
    private static final int COLOR_WHITE = 0xFFFFFFFF;
    private static final int COLOR_ERROR = 0xFFFF5555;
    private static final int COLOR_INFO = 0xFFAAAAFF;
    private static final int COLOR_MUTED = 0xFFAAAAAA;

    private final Screen parent;
    private EditBox codeField;
    private EditBox passwordField;
    private Button joinButton;
    private String statusMessage = "";
    private int statusColor = COLOR_WHITE;
    private String savedCode = "";
    private String savedPassword = "";

    public JoinRoomScreen(Screen parent) {
        super(Component.translatable("voxlink.join_room"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();

        int centerX = this.width / 2;
        int formHeight = BTN_H * BTN_COUNT + GAP * GAP_COUNT + FORM_EXTRA;
        int startY = Math.max(40, (this.height - formHeight) / 2);

        codeField = new EditBox(this.font, centerX - BTN_W / 2, startY, BTN_W, BTN_H, Component.translatable("voxlink.room_code"));
        codeField.setMaxLength(25);
        setInputFilter(codeField, s -> s.matches("[A-Z0-9uU/\\-]*"));
        codeField.setHint(Component.translatable("voxlink.enter_code"));
        if (!savedCode.isEmpty()) codeField.setValue(savedCode);
        codeField.setResponder(text -> {
            if (joinButton != null) {
                joinButton.active = RoomCodeRouter.isVoxLinkCode(text) || RoomCodeRouter.isTerracottaCode(text);
            }
            if (text.length() >= CODE_MIN_LENGTH && passwordField != null && RoomCodeRouter.isVoxLinkCode(text)) {
                this.setInitialFocus(passwordField);
            }
        });
        this.addRenderableWidget(codeField);

        int pwdY = startY + BTN_H + GAP + FIELD_SPACING;
        passwordField = new EditBox(this.font, centerX - BTN_W / 2, pwdY, BTN_W, BTN_H, Component.translatable("voxlink.room_password"));
        passwordField.setMaxLength(32);
        passwordField.setHint(Component.translatable("voxlink.enter_password"));
        if (!savedPassword.isEmpty()) passwordField.setValue(savedPassword);
        this.addRenderableWidget(passwordField);

        int joinY = pwdY + BTN_H + GAP + FIELD_SPACING;
        joinButton = Button.builder(Component.translatable("voxlink.join_room"), button -> attemptJoin())
                .bounds(centerX - BTN_W / 2, joinY, BTN_W, BTN_H).build();
        joinButton.active = !savedCode.isEmpty() && (RoomCodeRouter.isVoxLinkCode(savedCode) || RoomCodeRouter.isTerracottaCode(savedCode));
        this.addRenderableWidget(joinButton);

        int backY = joinY + BTN_H + GAP;
        this.addRenderableWidget(Button.builder(Component.translatable("voxlink.back"), button -> goBack())
                .bounds(centerX - BTN_W / 2, backY, BTN_W, BTN_H).build());

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
        Minecraft.getInstance().gui.setScreen(parent);
    }

    private void attemptJoin() {
        String code = codeField.getValue().trim().toUpperCase();
        if (code.isEmpty()) {
            statusMessage = Component.translatable("voxlink.join_room.enter_code").getString();
            statusColor = COLOR_ERROR;
            return;
        }
        if (!RoomCodeRouter.isVoxLinkCode(code) && !RoomCodeRouter.isTerracottaCode(code)) {
            statusMessage = Component.translatable("voxlink.error.invalid_room_code").getString();
            statusColor = COLOR_ERROR;
            return;
        }
        //陶瓦码需要陶瓦就绪
        if (RoomCodeRouter.isTerracottaCode(code) && !TerracottaManager.isBinaryReady()) {
            statusMessage = Component.translatable("voxlink.join.terracotta_not_ready").getString();
            statusColor = COLOR_ERROR;
            return;
        }
        savedCode = code;
        savedPassword = passwordField.getValue().trim();
        String password = savedPassword.isEmpty() ? null : savedPassword;
        Minecraft.getInstance().gui.setScreen(new AttemptingJoinScreen(this, code, password));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int centerX = this.width / 2;
        int formHeight = BTN_H * BTN_COUNT + GAP * GAP_COUNT + FORM_EXTRA;
        int startY = Math.max(40, (this.height - formHeight) / 2);
        int backY = startY + (BTN_H + GAP + FIELD_SPACING) * 2 + BTN_H + GAP;

        drawCenteredString(graphics, this.title.getString(), centerX, TITLE_Y, COLOR_WHITE);
        drawCenteredString(graphics, Component.translatable("voxlink.join.recommend_voxlink").getString(), centerX, backY + BTN_H + HINT_Y_OFFSET, COLOR_INFO);
        drawCenteredString(graphics, Component.translatable("voxlink.join.terracotta_code_hint").getString(), centerX, backY + BTN_H + TERRACOTTA_HINT_Y_OFFSET, COLOR_MUTED);

        if (!statusMessage.isEmpty()) {
            drawCenteredClipped(graphics, statusMessage, centerX, backY + BTN_H + STATUS_Y_OFFSET, statusColor);
        }
    }
}
