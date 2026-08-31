package icu.wuhui.voxlink.ui;

import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.room.RoomInfo;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.client.gui.GuiGraphics;

public class ManageRoomScreen extends VoxLinkScreenBase {
   private final Screen parent;
   private final RoomInfo roomInfo;
   private EditBox nameField;
   private EditBox passwordField;
   private EditBox maxPlayersField;
   private Button visibleButton;
   private Button authButton;
   private Button saveButton;
   private String statusMessage = "";
   private int statusColor = VoxLinkColors.WHITE;
   private static final int BTN_W = 200;
   private static final int BTN_H = 20;
   private static final int MARGIN_X = 20;
   private static final int FORM_HEIGHT = 230;
   private static final int FORM_MIN_Y = 24;
   private static final int LABEL_OFFSET_Y = 24;
   private static final int FIELD_OFFSET_Y = 48;
   private static final int ADV_OFFSET_Y = 76;
   private static final int ADV_ROW1_OFFSET_Y = 24;
   private static final int ADV_ROW2_OFFSET_Y = 48;
   private static final int SAVE_BTN_OFFSET_Y = 80;
   private static final int STATUS_OFFSET_Y = 186;
   private static final int TITLE_Y = 8;
   private static final int MIN_MAX_PLAYERS = 2;
   private static final int MAX_MAX_PLAYERS = 100;
   private boolean visible;
   private boolean visibleBeforePassword = false;
   private ManageRoomScreen.AuthType authType;
   private int categoryIdx;
   private boolean passwordChanged = false;
   private volatile boolean saving = false;
   private static final String[] CATEGORIES = new String[]{"survival", "creative", "redstone", "pvp", "rpg", "minigame", "social", "other"};
   private static final String[] CATEGORY_TRANSLATION_KEYS = new String[]{
      "voxlink.category.survival",
      "voxlink.category.creative",
      "voxlink.category.redstone",
      "voxlink.category.pvp",
      "voxlink.category.rpg",
      "voxlink.category.minigame",
      "voxlink.category.social",
      "voxlink.category.other"
   };

   public ManageRoomScreen(Screen parent, RoomInfo roomInfo) {
      super(Component.translatable("voxlink.manage_room.title", new Object[]{"****"}));
      this.parent = parent;
      this.roomInfo = roomInfo;
      this.visible = roomInfo.hasPassword() ? false : roomInfo.isVisible();
      this.visibleBeforePassword = roomInfo.isVisible();
      this.authType = "ONLINE".equals(roomInfo.getAuthType()) ? ManageRoomScreen.AuthType.ONLINE : ManageRoomScreen.AuthType.OFFLINE;
      String cat = roomInfo.getCategory();
      this.categoryIdx = 7;

      for (int i = 0; i < CATEGORIES.length; i++) {
         if (CATEGORIES[i].equals(cat)) {
            this.categoryIdx = i;
            break;
         }
      }
   }

   @Override
   protected void init() {
      super.init();
      int centerX = this.width / 2;
      int formHeight = 230;
      int y = Math.max(24, (this.height - formHeight) / 2);
      this.nameField = new EditBox(this.font, centerX - 100, y, 200, 20, Component.translatable("voxlink.room_name"));
      this.nameField.setMaxLength(20);
      this.nameField.setValue(this.roomInfo.getName() != null ? this.roomInfo.getName() : "");
      this.addRenderableWidget(this.nameField);
      this.passwordField = new EditBox(this.font, centerX - 100, y + 24, 200, 20, Component.translatable("voxlink.room_password"));
      this.passwordField.setMaxLength(32);
      this.passwordField.setHint(Component.translatable("voxlink.manage_room.password_hint"));
      this.passwordField.setResponder(s -> {
         this.passwordChanged = true;
         this.updateVisibleForPassword();
      });
      this.addRenderableWidget(this.passwordField);
      this.maxPlayersField = new EditBox(this.font, centerX - 100, y + 48, 200, 20, Component.translatable("voxlink.max_players"));
      this.maxPlayersField.setMaxLength(3);
      this.maxPlayersField.setValue(String.valueOf(this.roomInfo.getMaxPlayers()));
      this.setInputFilter(this.maxPlayersField, s -> s.matches("\\d*"));
      this.addRenderableWidget(this.maxPlayersField);
      int advY = y + 76;
      Button categoryBtn = Button.builder(
            Component.translatable("voxlink.manage_room.category", new Object[]{Component.translatable(CATEGORY_TRANSLATION_KEYS[this.categoryIdx])}),
            button -> {
               this.categoryIdx = (this.categoryIdx + 1) % CATEGORIES.length;
               button.setMessage(
                  Component.translatable("voxlink.manage_room.category", new Object[]{Component.translatable(CATEGORY_TRANSLATION_KEYS[this.categoryIdx])})
               );
            }
         )
         .bounds(centerX - 100, advY, 200, 20)
         .build();
      this.addRenderableWidget(categoryBtn);
      this.visibleButton = Button.builder(this.buildVisibleLabel(), button -> {
         this.visible = !this.visible;
         this.visibleButton.setMessage(this.buildVisibleLabel());
      }).bounds(centerX - 100, advY + 24, 200, 20).build();
      this.addRenderableWidget(this.visibleButton);
      this.updateVisibleForPassword();
      this.authButton = Button.builder(this.buildAuthLabel(), button -> {
         this.authType = this.authType == ManageRoomScreen.AuthType.OFFLINE ? ManageRoomScreen.AuthType.ONLINE : ManageRoomScreen.AuthType.OFFLINE;
         this.authButton.setMessage(this.buildAuthLabel());
      }).bounds(centerX - 100, advY + 48, 200, 20).build();
      this.addRenderableWidget(this.authButton);
      this.addRenderableWidget(
         this.saveButton = Button.builder(
               Component.translatable("voxlink.manage_room.save_and_back"),
               button -> this.saveSettings(() -> Minecraft.getInstance().setScreen(this.parent))
            )
            .bounds(centerX - 100, advY + 80, 200, 20)
            .build()
      );
      if (this.saving) {
         this.saveButton.active = false;
         this.nameField.setEditable(false);
         this.passwordField.setEditable(false);
         this.maxPlayersField.setEditable(false);
         categoryBtn.active = false;
         this.visibleButton.active = false;
         this.authButton.active = false;
      }
   }

   public boolean shouldCloseOnEsc() {
      return !this.saving;
   }

   public void onClose() {
      if (!this.saving) {
         Minecraft.getInstance().setScreen(this.parent);
      }
   }

   private void updateVisibleForPassword() {
      boolean hasPassword = this.passwordField != null && !this.passwordField.getValue().trim().isEmpty();
      if (hasPassword) {
         if (this.visible) {
            this.visibleBeforePassword = true;
         }

         this.visible = false;
      } else if (this.passwordChanged) {
         this.visible = this.visibleBeforePassword;
      }

      if (this.visibleButton != null) {
         this.visibleButton.active = !hasPassword;
         this.visibleButton
            .setMessage(
               (Component)(hasPassword ? Component.translatable("voxlink.visible.password_hidden").withStyle(ChatFormatting.RED) : this.buildVisibleLabel())
            );
      }
   }

   private Component buildVisibleLabel() {
      return this.visible ? Component.translatable("voxlink.visible.on") : Component.translatable("voxlink.visible.off");
   }

   private Component buildAuthLabel() {
      return Component.translatable(this.authType.translationKey);
   }

   private void saveSettings(Runnable onSuccess) {
      Minecraft mc = Minecraft.getInstance();
      String name = this.nameField.getValue().trim();
      if (name.isEmpty()) {
         this.statusMessage = ChatFormatting.RED.toString() + Component.translatable("voxlink.manage_room.enter_name").getString();
         this.statusColor = VoxLinkColors.ERROR;
      } else {
         String password = this.passwordField.getValue().trim();
         String passwordToSend = null;
         if (this.passwordChanged) {
            passwordToSend = password.isEmpty() ? "" : password;
         }

         int maxPlayers;
         try {
            maxPlayers = Integer.parseInt(this.maxPlayersField.getValue());
         } catch (NumberFormatException e) {
            maxPlayers = this.roomInfo.getMaxPlayers();
         }

         if (maxPlayers < 2) {
            maxPlayers = 2;
         }

         if (maxPlayers > 100) {
            maxPlayers = 100;
         }

         this.saving = true;
         this.statusMessage = Component.translatable("voxlink.manage_room.saving").getString();
         this.statusColor = VoxLinkColors.WARNING;
         this.saveButton.active = false;
         if (mc.player != null) {
            mc.player.sendSystemMessage(Component.translatable("voxlink.chat.saving_settings"));
         }

         VoxLinkMod.getRoomManager()
            .updateRoom(
               this.roomInfo.getCode(),
               this.roomInfo.getToken(),
               name,
               passwordToSend,
               maxPlayers,
               this.visible,
               this.authType.name(),
               CATEGORIES[this.categoryIdx]
            )
            .thenAccept(
               updated -> mc.execute(
                  () -> {
                     if (mc.screen != this) {
                        this.saving = false;
                     } else {
                        this.saving = false;
                        this.saveButton.active = true;
                        if (updated != null && !updated.isNameApproved()) {
                           this.statusMessage = ChatFormatting.YELLOW.toString()
                              + Component.translatable("voxlink.manage_room.name_pending_review").getString();
                           this.statusColor = VoxLinkColors.WARNING;
                        } else {
                           this.statusMessage = ChatFormatting.GREEN.toString() + Component.translatable("voxlink.manage_room.saved").getString();
                           this.statusColor = VoxLinkColors.SUCCESS;
                        }

                        this.roomInfo.setVisible(this.visible);
                        this.roomInfo.setCategory(CATEGORIES[this.categoryIdx]);
                        this.roomInfo.setAuthType(this.authType.name());
                        if (mc.player != null) {
                           String code = this.roomInfo.getCode();
                           mc.player
                              .sendSystemMessage(

                                 Component.translatable("voxlink.chat.room_settings_updated")
                                    .withStyle(new ChatFormatting[]{ChatFormatting.GREEN, ChatFormatting.BOLD})
                              
);
                           mc.player
                              .sendSystemMessage(

                                 Component.translatable("voxlink.chat.room_code_label")
                                    .append(
                                       Component.literal(
                                             ChatFormatting.GREEN.toString()
                                                + ChatFormatting.BOLD.toString()
                                                + "["
                                                + Component.translatable("voxlink.chat.click_to_copy").getString()
                                                + "]"
                                          )
                                          .withStyle(ChatCompat.styleWithCopy(code, Component.translatable("voxlink.chat.click_to_copy")))
                                    )
                              
);
                           String hostIp = this.roomInfo.getHostIp();
                           int hostPort = this.roomInfo.getHostPort();
                           String hostIpv6 = this.roomInfo.getHostIpv6();
                           boolean hasV4 = hostIp != null && !hostIp.isEmpty();
                           boolean hasV6 = hostIpv6 != null && !hostIpv6.isEmpty();
                           if (hasV4 || hasV6) {
                              MutableComponent addrLine = Component.translatable("voxlink.chat.your_addresses").withStyle(ChatFormatting.YELLOW);
                              if (hasV4) {
                                 String addr = (hostIp.contains(":") ? "[" + hostIp + "]" : hostIp) + ":" + hostPort;
                                 addrLine.append(
                                    Component.translatable("voxlink.chat.ipv4_label")
                                       .withStyle(
                                          ChatCompat.styleWithCopy(addr, Component.translatable("voxlink.chat.copy_for_non_voxlink")).withColor(VoxLinkColors.SUCCESS_RGB)
                                       )
                                 );
                              }

                              if (hasV4 && hasV6) {
                                 addrLine.append(Component.literal(" "));
                              }

                              if (hasV6) {
                                 String ipv6Addr = "[" + hostIpv6 + "]:" + hostPort;
                                 addrLine.append(
                                    Component.translatable("voxlink.chat.ipv6_label")
                                       .withStyle(
                                          ChatCompat.styleWithCopy(ipv6Addr, Component.translatable("voxlink.chat.copy_for_non_voxlink")).withColor(VoxLinkColors.SUCCESS_RGB)
                                       )
                                 );
                              }

                              mc.player.sendSystemMessage(addrLine);
                           }
                        }

                        if (onSuccess != null && updated != null) {
                           onSuccess.run();
                        }
                     }
                  }
               )
            )
            .exceptionally(e -> {
               Throwable cause = e;

               while (cause.getCause() != null) {
                  cause = cause.getCause();
               }

               String msg = cause.getMessage();
               VoxLinkMod.LOGGER.error("Room update failed: {}", msg, cause);
               // 兜底文案,避免直接外露底层异常原文;LOG 已记录,排查用日志即可
               String finalMsg = Component.translatable("voxlink.error.unknown").getString();
               mc.execute(() -> {
                  this.saving = false;
                  this.saveButton.active = true;
                  this.statusMessage = ChatFormatting.RED.toString() + finalMsg;
                  this.statusColor = VoxLinkColors.ERROR;
                  if (mc.player != null) {
                     mc.player.sendSystemMessage(Component.translatable("voxlink.chat.error", new Object[]{finalMsg}));
                  }
               });
               return null;
            });
      }
   }

   public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
      super.render(graphics, mouseX, mouseY, partialTick);
      int centerX = this.width / 2;
      this.drawCenteredClipped(graphics, this.title.getString(), centerX, 8, VoxLinkColors.WHITE);
      if (!this.statusMessage.isEmpty()) {
         String clipped = this.statusMessage;
         int maxWidth = this.width - 20;
         if (this.fontWidth(this.statusMessage) > maxWidth) {
            while (this.fontWidth(clipped + "...") > maxWidth && clipped.length() > 0) {
               clipped = clipped.substring(0, clipped.length() - 1);
            }

            clipped = clipped + "...";
         }

         int formHeight = 230;
         int y = Math.max(24, (this.height - formHeight) / 2);
         // 矮屏时夹住状态行 Y，避免状态跑到按钮之上或屏幕外
         int statusY = Math.min(y + 186, this.height - 12);
         this.drawCenteredString(graphics, clipped, centerX, statusY, this.statusColor);
      }
   }

   private enum AuthType {
      OFFLINE("voxlink.auth_type.offline"),
      ONLINE("voxlink.auth_type.online");

      final String translationKey;

      AuthType(String translationKey) {
         this.translationKey = translationKey;
      }
   }
}
