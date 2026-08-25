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
   private final Screen parent;
   private EditBox codeField;
   private EditBox passwordField;
   private Button joinButton;
   private String statusMessage = "";
   private int statusColor = -1;
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
      int formHeight = 102;
      int startY = Math.max(40, (this.height - formHeight) / 2);
      this.codeField = new EditBox(this.font, centerX - 100, startY, 200, 20, Component.translatable("voxlink.room_code"));
      this.codeField.setMaxLength(25);
      this.setInputFilter(this.codeField, s -> s.matches("[A-Z0-9uU/\\-]*"));
      this.codeField.setHint(Component.translatable("voxlink.enter_code"));
      if (!this.savedCode.isEmpty()) {
         this.codeField.setValue(this.savedCode);
      }

      this.codeField.setResponder(text -> {
         if (this.joinButton != null) {
            this.joinButton.active = this.isJoinable(text);
         }

         if (text.length() >= 6 && this.passwordField != null && RoomCodeRouter.isVoxLinkCode(text)) {
            this.setInitialFocus(this.passwordField);
         }
      });
      this.addRenderableWidget(this.codeField);
      int pwdY = startY + 20 + 4 + 10;
      this.passwordField = new EditBox(this.font, centerX - 100, pwdY, 200, 20, Component.translatable("voxlink.room_password"));
      this.passwordField.setMaxLength(32);
      this.passwordField.setHint(Component.translatable("voxlink.enter_password"));
      if (!this.savedPassword.isEmpty()) {
         this.passwordField.setValue(this.savedPassword);
      }

      this.addRenderableWidget(this.passwordField);
      int joinY = pwdY + 20 + 4 + 10;
      this.joinButton = Button.builder(Component.translatable("voxlink.join_room"), button -> this.attemptJoin()).bounds(centerX - 100, joinY, 200, 20).build();
      this.joinButton.active = !this.savedCode.isEmpty() && this.isJoinable(this.savedCode);
      this.addRenderableWidget(this.joinButton);
      int backY = joinY + 20 + 4;
      this.addRenderableWidget(Button.builder(Component.translatable("voxlink.back"), button -> this.goBack()).bounds(centerX - 100, backY, 200, 20).build());
      this.setInitialFocus(this.codeField);
   }

   public boolean shouldCloseOnEsc() {
      return true;
   }

   public void onClose() {
      this.goBack();
   }

   /** 陶瓦房间号在陶瓦未就绪（未下载或下载中）时不可提交：按钮提前置灰并配合行内提示，避免点击后才发现不可用。 */
   private boolean isJoinable(String text) {
      if (RoomCodeRouter.isTerracottaCode(text)) {
         return TerracottaManager.isBinaryReady();
      }

      return RoomCodeRouter.isVoxLinkCode(text);
   }

   private void goBack() {
      Minecraft.getInstance().setScreen(this.parent);
   }

   private void attemptJoin() {
      String code = this.codeField.getValue().trim().toUpperCase();
      if (code.isEmpty()) {
         this.statusMessage = Component.translatable("voxlink.join_room.enter_code").getString();
         this.statusColor = -43691;
      } else if (!RoomCodeRouter.isVoxLinkCode(code) && !RoomCodeRouter.isTerracottaCode(code)) {
         this.statusMessage = Component.translatable("voxlink.error.invalid_room_code").getString();
         this.statusColor = -43691;
      } else if (RoomCodeRouter.isTerracottaCode(code) && !TerracottaManager.isBinaryReady()) {
         this.statusMessage = Component.translatable("voxlink.join.terracotta_not_ready").getString();
         this.statusColor = -43691;
      } else {
         this.savedCode = code;
         this.savedPassword = this.passwordField.getValue().trim();
         String password = this.savedPassword.isEmpty() ? null : this.savedPassword;
         Minecraft.getInstance().setScreen(new AttemptingJoinScreen(this, code, password));
      }
   }

   public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
      super.extractRenderState(graphics, mouseX, mouseY, partialTick);
      int centerX = this.width / 2;
      int formHeight = 102;
      int startY = Math.max(40, (this.height - formHeight) / 2);
      int backY = startY + 68 + 20 + 4;
      this.drawCenteredString(graphics, this.title.getString(), centerX, 15, VoxLinkColors.TITLE);
      this.drawCenteredString(graphics, Component.translatable("voxlink.join.recommend_voxlink").getString(), centerX, backY + 20 + 6, VoxLinkColors.INFO);
      this.drawCenteredString(graphics, Component.translatable("voxlink.join.terracotta_code_hint").getString(), centerX, backY + 20 + 18, VoxLinkColors.MUTED);
      if (!this.statusMessage.isEmpty()) {
         this.drawCenteredClipped(graphics, this.statusMessage, centerX, backY + 20 + 32, this.statusColor);
      } else if (this.codeField != null && RoomCodeRouter.isTerracottaCode(this.codeField.getValue()) && !TerracottaManager.isBinaryReady()) {
         this.drawCenteredClipped(graphics, Component.translatable("voxlink.join.terracotta_not_ready").getString(), centerX, backY + 20 + 32, -22016);
      }
   }
}
