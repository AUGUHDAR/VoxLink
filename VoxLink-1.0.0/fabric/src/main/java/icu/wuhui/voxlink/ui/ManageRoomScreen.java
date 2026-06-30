package icu.wuhui.voxlink.ui;

import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.room.RoomInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;

public class ManageRoomScreen extends Screen {
    private final Screen parent;
    private final RoomInfo roomInfo;
    private EditBox nameField;
    private EditBox passwordField;
    private EditBox maxPlayersField;
    private Button visibleButton;
    private Button authButton;
    private Button saveButton;
    private String statusMessage = "";
    private int statusColor = 0xFFFFFFFF;

    private boolean visible;
    private boolean visibleBeforePassword = false;
    private AuthType authType;
    private int categoryIdx;
    private boolean passwordChanged = false;
    private volatile boolean saving = false;
    private static final String[] CATEGORIES = {"survival", "creative", "redstone", "pvp", "rpg", "minigame", "social", "other"};
    private static final String[] CATEGORY_TRANSLATION_KEYS = {
            "voxlink.category.survival", "voxlink.category.creative", "voxlink.category.redstone",
            "voxlink.category.pvp", "voxlink.category.rpg", "voxlink.category.minigame",
            "voxlink.category.social", "voxlink.category.other"
    };

    private enum AuthType {
        OFFLINE("voxlink.auth_type.offline"),
        ONLINE("voxlink.auth_type.online");
        final String translationKey;
        AuthType(String translationKey) { this.translationKey = translationKey; }
    }

    public ManageRoomScreen(Screen parent, RoomInfo roomInfo) {
        super(Component.translatable("voxlink.manage_room.title", roomInfo.getCode()));
        this.parent = parent;
        this.roomInfo = roomInfo;

        this.visible = roomInfo.isVisible();
        this.visibleBeforePassword = roomInfo.isVisible();
        this.authType = "ONLINE".equals(roomInfo.getAuthType()) ? AuthType.ONLINE : AuthType.OFFLINE;

        String cat = roomInfo.getCategory();
        this.categoryIdx = 7;
        for (int i = 0; i < CATEGORIES.length; i++) {
            if (CATEGORIES[i].equals(cat)) { categoryIdx = i; break; }
        }
    }

    @Override
    protected void init() {
        super.init();
        this.clearWidgets();

        int centerX = this.width / 2;
        int formHeight = 230;
        int y = Math.max(24, (this.height - formHeight) / 2);

        nameField = new EditBox(this.font, centerX - 100, y, 200, 20, Component.translatable("voxlink.room_name"));
        nameField.setMaxLength(64);
        nameField.setValue(roomInfo.getName() != null ? roomInfo.getName() : "");
        this.addRenderableWidget(nameField);

        passwordField = new EditBox(this.font, centerX - 100, y + 24, 200, 20, Component.translatable("voxlink.room_password"));
        passwordField.setMaxLength(32);
        passwordField.setHint(Component.translatable("voxlink.manage_room.password_hint"));
        passwordField.setResponder(s -> {
            passwordChanged = true;
            updateVisibleForPassword();
        });
        this.addRenderableWidget(passwordField);

        maxPlayersField = new EditBox(this.font, centerX - 100, y + 48, 200, 20, Component.translatable("voxlink.max_players"));
        maxPlayersField.setMaxLength(3);
        maxPlayersField.setValue(String.valueOf(roomInfo.getMaxPlayers()));
        maxPlayersField.setFilter(s -> s.matches("\\d*"));
        this.addRenderableWidget(maxPlayersField);

        int advY = y + 76;

        Button categoryBtn = Button.builder(Component.translatable("voxlink.manage_room.category", Component.translatable(CATEGORY_TRANSLATION_KEYS[categoryIdx])), button -> {
            categoryIdx = (categoryIdx + 1) % CATEGORIES.length;
            button.setMessage(Component.translatable("voxlink.manage_room.category", Component.translatable(CATEGORY_TRANSLATION_KEYS[categoryIdx])));
        }).bounds(centerX - 100, advY, 200, 20).build();
        this.addRenderableWidget(categoryBtn);

        visibleButton = Button.builder(buildVisibleLabel(), button -> {
            visible = !visible;
            visibleButton.setMessage(buildVisibleLabel());
        }).bounds(centerX - 100, advY + 24, 200, 20).build();
        this.addRenderableWidget(visibleButton);
        updateVisibleForPassword();

        authButton = Button.builder(buildAuthLabel(), button -> {
            authType = authType == AuthType.OFFLINE ? AuthType.ONLINE : AuthType.OFFLINE;
            authButton.setMessage(buildAuthLabel());
        }).bounds(centerX - 100, advY + 48, 200, 20).build();
        this.addRenderableWidget(authButton);

        this.addRenderableWidget(saveButton = Button.builder(Component.translatable("voxlink.manage_room.save_and_back"), button -> {
                saveSettings(() -> Minecraft.getInstance().setScreen(parent));
        }).bounds(centerX - 100, advY + 80, 200, 20).build());

        if (saving) {
            saveButton.active = false;
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return !saving;
    }

    private void updateVisibleForPassword() {
        boolean hasPassword = passwordField != null && !passwordField.getValue().trim().isEmpty();
        if (hasPassword) {
            if (visible) {
                visibleBeforePassword = true;
            }
            visible = false;
        } else if (passwordChanged) {
            visible = visibleBeforePassword;
        }
        if (visibleButton != null) {
            visibleButton.active = !hasPassword;
            visibleButton.setMessage(hasPassword
                    ? Component.translatable("voxlink.visible.password_hidden")
                    : buildVisibleLabel());
        }
    }

    private Component buildVisibleLabel() {
        return visible ? Component.translatable("voxlink.visible.on") : Component.translatable("voxlink.visible.off");
    }

    private Component buildAuthLabel() {
        return Component.translatable(authType.translationKey);
    }

    private void saveSettings(Runnable onSuccess) {
        var mc = Minecraft.getInstance();
        String name = nameField.getValue().trim();
        if (name.isEmpty()) {
            statusMessage = "\u00a7c" + Component.translatable("voxlink.manage_room.enter_name").getString();
            statusColor = 0xFFFF5555;
            return;
        }

        String password = passwordField.getValue().trim();
        String passwordToSend = null;
        if (passwordChanged) {
            passwordToSend = password.isEmpty() ? "" : password;
        }
        int maxPlayers;
        try {
            maxPlayers = Integer.parseInt(maxPlayersField.getValue());
        } catch (NumberFormatException e) {
            maxPlayers = roomInfo.getMaxPlayers();
        }
        if (maxPlayers < 2) maxPlayers = 2;
        if (maxPlayers > 100) maxPlayers = 100;

        saving = true;
        statusMessage = Component.translatable("voxlink.manage_room.saving").getString();
        statusColor = 0xFFFFFF55;
        saveButton.active = false;

        if (mc.player != null) {
            mc.player.displayClientMessage(Component.translatable("voxlink.chat.saving_settings"), false);
        }

        VoxLinkMod.getRoomManager().updateRoom(
                roomInfo.getCode(), roomInfo.getToken(),
                name, passwordToSend,
                maxPlayers, visible, authType.name(), CATEGORIES[categoryIdx]
        ).thenAccept(updated -> {
            mc.execute(() -> {
                if (mc.screen != ManageRoomScreen.this) {
                    saving = false;
                    return;
                }
                saving = false;
                saveButton.active = true;
                if (updated != null && !updated.isNameApproved()) {
                    statusMessage = "\u00a7e" + Component.translatable("voxlink.manage_room.saved_name_not_approved").getString();
                    statusColor = 0xFFFFFF55;
                    if (mc.player != null) {
                        mc.player.displayClientMessage(
                                Component.translatable("voxlink.chat.name_not_approved"), false);
                    }
                } else {
                    statusMessage = "\u00a7a" + Component.translatable("voxlink.manage_room.saved").getString();
                    statusColor = 0xFF55FF55;
                }

                roomInfo.setVisible(visible);
                roomInfo.setCategory(CATEGORIES[categoryIdx]);
                roomInfo.setAuthType(authType.name());

                if (mc.player != null) {
                    String code = roomInfo.getCode();
                    mc.player.displayClientMessage(
                            Component.translatable("voxlink.chat.room_settings_updated").withStyle(Style.EMPTY.withBold(true)),
                            false
                    );
                    mc.player.displayClientMessage(
                            Component.translatable("voxlink.chat.room_code_label")
                                    .append(Component.literal("\u00a7e\u00a7l" + code)
                                            .withStyle(Style.EMPTY
                                                    .withClickEvent(new ClickEvent.CopyToClipboard(code))
                                                    .withHoverEvent(new HoverEvent.ShowText(Component.translatable("voxlink.chat.click_to_copy"))))),
                            false
                    );
                    String hostIp = roomInfo.getHostIp();
                    int hostPort = roomInfo.getHostPort();
                    String hostIpv6 = roomInfo.getHostIpv6();
                    boolean hasV4 = hostIp != null && !hostIp.isEmpty();
                    boolean hasV6 = hostIpv6 != null && !hostIpv6.isEmpty();
                    if (hasV4 || hasV6) {
                        net.minecraft.network.chat.MutableComponent addrLine = Component.translatable("voxlink.chat.your_addresses");
                        if (hasV4) {
                            String addr = (hostIp.contains(":") ? "[" + hostIp + "]" : hostIp) + ":" + hostPort;
                            addrLine.append(Component.literal("[IPv4]")
                                    .withStyle(Style.EMPTY
                                            .withClickEvent(new ClickEvent.CopyToClipboard(addr))
                                            .withHoverEvent(new HoverEvent.ShowText(Component.translatable("voxlink.chat.copy_for_non_voxlink")))
                                            .withColor(0x55FF55)));
                        }
                        if (hasV4 && hasV6) addrLine.append(Component.literal(" "));
                        if (hasV6) {
                            String ipv6Addr = "[" + hostIpv6 + "]:" + hostPort;
                            addrLine.append(Component.literal("[IPv6]")
                                    .withStyle(Style.EMPTY
                                            .withClickEvent(new ClickEvent.CopyToClipboard(ipv6Addr))
                                            .withHoverEvent(new HoverEvent.ShowText(Component.translatable("voxlink.chat.copy_for_non_voxlink")))
                                            .withColor(0x55FF55)));
                        }
                        mc.player.displayClientMessage(addrLine, false);
                    }
                }

                if (onSuccess != null && updated != null) {
                    onSuccess.run();
                }
            });
        }).exceptionally(e -> {
            Throwable cause = e;
            while (cause.getCause() != null) cause = cause.getCause();
            String msg = cause.getMessage();
            VoxLinkMod.LOGGER.error("房间更新失败: {}", msg, cause);
            final String finalMsg = msg != null ? msg : Component.translatable("voxlink.error.unknown").getString();
            mc.execute(() -> {
                saving = false;
                saveButton.active = true;
                statusMessage = "\u00a7c" + finalMsg;
                statusColor = 0xFFFF5555;
                if (mc.player != null) {
                    mc.player.displayClientMessage(Component.translatable("voxlink.chat.error", finalMsg), false);
                }
            });
            return null;
        });
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        int centerX = this.width / 2;
        drawCenteredClipped(graphics, this.title.getString(), centerX, 8, 0xFFFFFFFF);

        if (!statusMessage.isEmpty()) {
            String clipped = statusMessage;
            int maxWidth = this.width - 20;
            if (this.font.width(statusMessage) > maxWidth) {
                while (this.font.width(clipped + "...") > maxWidth && clipped.length() > 0) {
                    clipped = clipped.substring(0, clipped.length() - 1);
                }
                clipped = clipped + "...";
            }
            int formHeight = 230;
            int y = Math.max(24, (this.height - formHeight) / 2);
            graphics.drawCenteredString(this.font, clipped, centerX, y + 186, statusColor);
        }
    }

    private void drawCenteredClipped(GuiGraphics graphics, String text, int centerX, int y, int color) {
        int maxWidth = this.width - 20;
        String clipped = text;
        if (this.font.width(text) > maxWidth) {
            while (this.font.width(clipped + "...") > maxWidth && clipped.length() > 0) {
                clipped = clipped.substring(0, clipped.length() - 1);
            }
            clipped = clipped + "...";
        }
        graphics.drawCenteredString(this.font, clipped, centerX, y, color);
    }
}
