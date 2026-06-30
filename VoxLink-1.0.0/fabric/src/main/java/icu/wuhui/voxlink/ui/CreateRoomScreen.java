package icu.wuhui.voxlink.ui;

import com.google.gson.JsonObject;
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
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CreateRoomScreen extends Screen {
    private final Screen parent;
    private EditBox nameField;
    private EditBox passwordField;
    private EditBox maxPlayersField;
    private EditBox customCategoryField;
    private Button createButton;
    private RoomInfo createdRoom;
    private Button visibleButton;
    private Button authButton;
    private Button backButton;
    private volatile long createStartTime = 0;
    private volatile boolean removed = false;

    private boolean visible = true;
    private boolean visibleBeforePassword = true;
    private AuthType authType = AuthType.OFFLINE;
    private boolean guestOp = false;
    private String gameType = "survival";
    private boolean allowCheats = false;
    private Button guestOpButton;
    private Button gameTypeButton;
    private Button cheatsButton;

    private String selectedCategory = "other";
    private Map<String, String> categoryMap = new LinkedHashMap<>();
    private List<Button> categoryButtons = new ArrayList<>();
    private boolean showCustomInput = false;
    private volatile net.minecraft.server.MinecraftServer publishedServer;
    private volatile boolean creating = false;
    private boolean categoriesFetched = false;
    private String savedName = "";
    private String savedPassword = "";
    private String savedMaxPlayers = "20";

    private static final Map<String, String> DEFAULT_CATEGORIES = new java.util.LinkedHashMap<>() {{
        put("survival", "voxlink.category.survival");
        put("creative", "voxlink.category.creative");
        put("redstone", "voxlink.category.redstone");
        put("pvp", "voxlink.category.pvp");
        put("rpg", "voxlink.category.rpg");
        put("minigame", "voxlink.category.minigame");
        put("social", "voxlink.category.social");
        put("other", "voxlink.category.other");
    }};

    private enum AuthType {
        OFFLINE("voxlink.auth_type.offline"),
        ONLINE("voxlink.auth_type.online");
        final String translationKey;
        AuthType(String translationKey) { this.translationKey = translationKey; }
    }

    public CreateRoomScreen(Screen parent) {
        super(Component.translatable("voxlink.create_room"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        this.clearWidgets();

        if (createdRoom != null) {
            int centerX = this.width / 2;
            this.addRenderableWidget(Button.builder(Component.translatable("voxlink.back"), button -> goBack())
                    .bounds(centerX - 100, this.height / 2 + 40, 200, 20).build());
            return;
        }

        int centerX = this.width / 2;
        // 防抖
        int formHeight = 240;
        int y = Math.max(4, (this.height - formHeight) / 2);

        nameField = new EditBox(this.font, centerX - 100, y, 200, 20, Component.translatable("voxlink.room_name"));
        nameField.setMaxLength(64);
        nameField.setHint(Component.translatable("voxlink.create_room.name_hint"));
        if (!savedName.isEmpty()) nameField.setValue(savedName);
        this.addRenderableWidget(nameField);

        passwordField = new EditBox(this.font, centerX - 100, y + 24, 200, 20, Component.translatable("voxlink.room_password"));
        passwordField.setMaxLength(32);
        passwordField.setHint(Component.translatable("voxlink.create_room.password_hint"));
        passwordField.setResponder(text -> updateVisibleForPassword());
        if (!savedPassword.isEmpty()) passwordField.setValue(savedPassword);
        this.addRenderableWidget(passwordField);

        maxPlayersField = new EditBox(this.font, centerX - 100, y + 48, 200, 20, Component.translatable("voxlink.max_players"));
        maxPlayersField.setMaxLength(3);
        maxPlayersField.setValue(savedMaxPlayers);
        maxPlayersField.setFilter(s -> s.matches("\\d*"));
        this.addRenderableWidget(maxPlayersField);

        int catY = y + 74;
        buildCategoryButtons(centerX, catY);

        customCategoryField = new EditBox(this.font, centerX - 100, catY + 22, 200, 18, Component.translatable("voxlink.create_room.custom_category"));
        customCategoryField.setMaxLength(32);
        customCategoryField.setHint(Component.translatable("voxlink.create_room.custom_category_hint"));
        customCategoryField.setVisible(showCustomInput);
        customCategoryField.setEditable(showCustomInput);
        this.addRenderableWidget(customCategoryField);

        int advY = catY + 42;

        visibleButton = Button.builder(buildVisibleLabel(), button -> {
            visible = !visible;
            visibleButton.setMessage(buildVisibleLabel());
        }).bounds(centerX - 100, advY, 98, 20).build();
        this.addRenderableWidget(visibleButton);
        updateVisibleForPassword();

        authButton = Button.builder(buildAuthLabel(), button -> {
            authType = authType == AuthType.OFFLINE ? AuthType.ONLINE : AuthType.OFFLINE;
            authButton.setMessage(buildAuthLabel());
        }).bounds(centerX + 2, advY, 98, 20).build();
        this.addRenderableWidget(authButton);

        gameTypeButton = Button.builder(buildGameTypeLabel(), button -> {
            gameType = switch (gameType) {
                case "survival" -> "creative";
                case "creative" -> "adventure";
                case "adventure" -> "spectator";
                default -> "survival";
            };
            gameTypeButton.setMessage(buildGameTypeLabel());
        }).bounds(centerX - 100, advY + 24, 98, 20).build();
        this.addRenderableWidget(gameTypeButton);

        cheatsButton = Button.builder(buildCheatsLabel(), button -> {
            allowCheats = !allowCheats;
            cheatsButton.setMessage(buildCheatsLabel());
        }).bounds(centerX + 2, advY + 24, 98, 20).build();
        this.addRenderableWidget(cheatsButton);

        guestOpButton = Button.builder(buildGuestOpLabel(), button -> {
            guestOp = !guestOp;
            guestOpButton.setMessage(buildGuestOpLabel());
        }).bounds(centerX - 100, advY + 48, 98, 20).build();
        this.addRenderableWidget(guestOpButton);

        createButton = Button.builder(Component.translatable("voxlink.create_room"), button -> createRoom())
                .bounds(centerX - 100, advY + 72, 98, 20).build();
        this.addRenderableWidget(createButton);

        backButton = Button.builder(Component.translatable("voxlink.back"), button ->
                Minecraft.getInstance().setScreen(parent)
        ).bounds(centerX + 2, advY + 72, 98, 20).build();
        this.addRenderableWidget(backButton);

        if (!categoriesFetched) fetchCategories();

        if (creating) {
            createButton.active = false;
            backButton.active = false;
            nameField.setEditable(false);
            passwordField.setEditable(false);
            maxPlayersField.setEditable(false);
            visibleButton.active = false;
            authButton.active = false;
            guestOpButton.active = false;
            gameTypeButton.active = false;
            cheatsButton.active = false;
            customCategoryField.setEditable(false);
            for (Button btn : categoryButtons) {
                btn.active = false;
            }
        }
    }

    private void buildCategoryButtons(int centerX, int startY) {
        for (Button btn : categoryButtons) {
            this.removeWidget(btn);
        }
        categoryButtons.clear();

        List<String> keys = new ArrayList<>(DEFAULT_CATEGORIES.keySet());
        if (!categoryMap.isEmpty()) {
            for (String key : categoryMap.keySet()) {
                if (!DEFAULT_CATEGORIES.containsKey(key) && !keys.contains(key)) {
                    keys.add(key);
                }
            }
        }

        int totalCats = keys.size();
        int btnW = Math.max(40, Math.min(56, (this.width - 40) / totalCats - 4));
        int gap = 3;
        int totalW = totalCats * btnW + (totalCats - 1) * gap;
        int startX = centerX - totalW / 2;

        for (int i = 0; i < totalCats; i++) {
            String key = keys.get(i);
            String label = DEFAULT_CATEGORIES.containsKey(key)
                    ? Component.translatable(DEFAULT_CATEGORIES.get(key)).getString()
                    : categoryMap.getOrDefault(key, key);
            final String catKey = key;

            Button btn = Button.builder(Component.literal(label), b -> {
                selectedCategory = catKey;
                boolean nowOther = catKey.equals("other");
                if (nowOther != showCustomInput) {
                    showCustomInput = nowOther;
                    customCategoryField.setVisible(nowOther);
                    customCategoryField.setEditable(nowOther);
                }
                rebuildCategoryLabels();
            }).bounds(startX + i * (btnW + gap), startY, btnW, 18).build();

            categoryButtons.add(btn);
            this.addRenderableWidget(btn);
        }

        rebuildCategoryLabels();
    }

    private void repositionLowerWidgets() {
        int centerX = this.width / 2;
        int formHeight = 240;
        int y = Math.max(4, (this.height - formHeight) / 2);
        int catY = y + 74;
        customCategoryField.setX(centerX - 100);
        customCategoryField.setY(catY + 22);
        int advY = catY + 42;

        visibleButton.setX(centerX - 100);
        visibleButton.setY(advY);
        authButton.setX(centerX + 2);
        authButton.setY(advY);
        gameTypeButton.setX(centerX - 100);
        gameTypeButton.setY(advY + 24);
        cheatsButton.setX(centerX + 2);
        cheatsButton.setY(advY + 24);
        guestOpButton.setX(centerX - 100);
        guestOpButton.setY(advY + 48);
        createButton.setX(centerX - 100);
        createButton.setY(advY + 72);
        backButton.setX(centerX + 2);
        backButton.setY(advY + 72);
    }

    private void rebuildCategoryLabels() {
        for (int i = 0; i < categoryButtons.size(); i++) {
            Button btn = categoryButtons.get(i);
            String key = getCategoryKeyAtIndex(i);
            if (key.equals(selectedCategory)) {
                btn.setMessage(Component.literal("\u00a7l" + getLabelForKey(key)));
            } else {
                btn.setMessage(Component.literal(getLabelForKey(key)));
            }
        }
    }

    private String getCategoryKeyAtIndex(int index) {
        List<String> keys = new ArrayList<>(DEFAULT_CATEGORIES.keySet());
        if (!categoryMap.isEmpty()) {
            for (String key : categoryMap.keySet()) {
                if (!DEFAULT_CATEGORIES.containsKey(key) && !keys.contains(key)) {
                    keys.add(key);
                }
            }
        }
        if (index >= 0 && index < keys.size()) return keys.get(index);
        return "other";
    }

    private String getLabelForKey(String key) {
        return DEFAULT_CATEGORIES.containsKey(key)
                ? Component.translatable(DEFAULT_CATEGORIES.get(key)).getString()
                : categoryMap.getOrDefault(key, key);
    }

    private void fetchCategories() {
        VoxLinkMod.getSignalingClient().getCategories()
                .thenAccept(response -> {
                    Minecraft mc = Minecraft.getInstance();
                    mc.execute(() -> {
                        if (removed || createdRoom != null) return;
                        try {
                            if (response.success && response.data != null && response.data.isJsonObject()) {
                                JsonObject obj = response.data.getAsJsonObject();
                                categoryMap.clear();
                                for (String k : obj.keySet()) {
                                    if (!DEFAULT_CATEGORIES.containsKey(k)) {
                                        categoryMap.put(k, obj.get(k).getAsString());
                                    }
                                }
                                categoriesFetched = true;
                                buildCategoryButtons(this.width / 2, Math.max(4, (this.height - 240) / 2) + 74);
                            }
                        } catch (Exception ignored) {}
                    });
                })
                .exceptionally(e -> null);
    }

    private void updateVisibleForPassword() {
        boolean hasPassword = passwordField != null && !passwordField.getValue().trim().isEmpty();
        if (hasPassword) {
            if (visible) {
                visibleBeforePassword = true;
            }
            visible = false;
        } else {
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

    private Component buildGuestOpLabel() {
        return guestOp ? Component.translatable("voxlink.guest_op.on")
                : Component.translatable("voxlink.guest_op.off");
    }

    private Component buildGameTypeLabel() {
        return Component.translatable("voxlink.game_type." + gameType);
    }

    private Component buildCheatsLabel() {
        return allowCheats ? Component.translatable("voxlink.cheats.on")
                : Component.translatable("voxlink.cheats.off");
    }

    private String resolveCategory() {
        if (!selectedCategory.equals("other")) return selectedCategory;
        String custom = customCategoryField.getValue().trim();
        return custom.isEmpty() ? "other" : custom;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return !creating;
    }

    private void goBack() {
        Minecraft.getInstance().setScreen(parent);
    }

    void onCreateTimeout() {
        creating = false;
        createStartTime = 0;
        closeLan();
        VoxLinkMod.getRoomManager().leaveRoom();
    }

    private void createRoom() {
        savedName = nameField.getValue();
        savedPassword = passwordField.getValue();
        savedMaxPlayers = maxPlayersField.getValue();
        var mc = Minecraft.getInstance();
        if (mc.getSingleplayerServer() == null) {
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.translatable("voxlink.create_room.open_world_first").withStyle(style -> style.withColor(0xFF5555)), false);
            }
            return;
        }

        String name = nameField.getValue().trim();
        if (name.isEmpty()) {
            name = mc.player.getName().getString() + Component.translatable("voxlink.create_room.default_room_suffix").getString();
        }
        final String roomName = name;

        String password = passwordField.getValue().trim();
        int maxPlayers;
        try {
            maxPlayers = Integer.parseInt(maxPlayersField.getValue());
        } catch (NumberFormatException e) {
            maxPlayers = 20;
        }
        if (maxPlayers < 2) maxPlayers = 2;
        if (maxPlayers > 100) maxPlayers = 100;

        createButton.active = false;
        backButton.active = false;
        creating = true;
        createStartTime = System.currentTimeMillis();

        Minecraft.getInstance().setScreen(new CreatingRoomScreen(this));

        if (mc.player != null) {
            mc.player.displayClientMessage(Component.translatable("voxlink.chat.creating_room"), false);
        }

        var server = mc.getSingleplayerServer();
        int mcPort = server.getPort();
        if (mcPort <= 0) mcPort = 25565;

        if (!server.isPublished()) {
            GameType selectedGameType = switch (gameType) {
                case "creative" -> GameType.CREATIVE;
                case "adventure" -> GameType.ADVENTURE;
                case "spectator" -> GameType.SPECTATOR;
                default -> GameType.SURVIVAL;
            };
            this.publishedServer = server;
            boolean published = server.publishServer(selectedGameType, allowCheats, mcPort);
            if (!published) {
                creating = false;
                if (mc.player != null) {
                    mc.player.displayClientMessage(Component.translatable("voxlink.create_room.lan_failed").withStyle(style -> style.withColor(0xFF5555)), false);
                }
                createButton.active = true;
                return;
            }
            if (VoxLinkMod.getConfig().isOfflineMode()) {
                server.setUsesAuthentication(false);
                VoxLinkMod.LOGGER.info("[CreateRoom] publishServer后重新关闭正版验证 (usesAuthentication={})", server.usesAuthentication());
            }
        }

        final int effectivePort = server.getPort() > 0 ? server.getPort() : mcPort;
        String categoryText = resolveCategory();

        VoxLinkMod.getRoomManager().createRoom(roomName, password.isEmpty() ? null : password, maxPlayers, effectivePort, visible, authType.name(), categoryText)
                .thenAccept(roomInfo -> {
                    mc.execute(() -> {
                        createStartTime = 0;
                        if (roomInfo == null) {
                            creating = false;
                            closeLan();
                            if (mc.player != null) {
                                mc.player.displayClientMessage(
                                        Component.translatable("voxlink.chat.error_prefix")
                                                .append(Component.translatable("voxlink.create_room.timeout")), false);
                            }
                            mc.setScreen(CreateRoomScreen.this);
                        } else {
                            creating = false;
                            createdRoom = roomInfo;
                            roomInfo.setGuestOp(guestOp);
                            roomInfo.setGameType(gameType);
                            roomInfo.setAllowCheats(allowCheats);
                            sendChatMessages(mc, roomInfo);
                            mc.setScreen(CreateRoomScreen.this);
                        }
                    });
                })
                .exceptionally(e -> {
                    Throwable cause = e;
                    while (cause.getCause() != null) cause = cause.getCause();
                    String msg = cause.getMessage();
                    final String displayMsg = simplifyError(msg);
                    mc.execute(() -> {
                        createStartTime = 0;
                        creating = false;
                        closeLan();
                        if (mc.player != null) {
                            mc.player.displayClientMessage(Component.translatable("voxlink.chat.error", displayMsg), false);
                        }
                        mc.setScreen(CreateRoomScreen.this);
                    });
                    return null;
                });
    }

    private String simplifyError(String msg) {
        if (msg == null) return Component.translatable("voxlink.error.unknown").getString();
        if (msg.contains("NETWORK_ERROR")) return Component.translatable("voxlink.create_room.error.cannot_connect_server").getString();
        if (msg.contains("RATE_LIMITED")) return Component.translatable("voxlink.create_room.error.rate_limited").getString();
        if (msg.contains("MAX_ROOMS_REACHED")) return Component.translatable("voxlink.create_room.error.max_rooms").getString();
        if (msg.contains("CONTENT_BLOCKED")) return Component.translatable("voxlink.create_room.error.name_blocked").getString();
        if (msg.contains("ALREADY_IN_ROOM")) return Component.translatable("voxlink.error.already_in_room").getString();
        if (msg.contains("TIMEOUT")) return Component.translatable("voxlink.create_room.error.timeout").getString();
        if (msg.contains("QUEUED")) return Component.translatable("voxlink.create_room.error.server_busy").getString();
        if (msg.contains("PARSE_ERROR")) return Component.translatable("voxlink.error.server_response_abnormal").getString();
        return msg;
    }

    private void sendChatMessages(Minecraft mc, RoomInfo roomInfo) {
        if (mc.player == null) return;

        String code = roomInfo.getCode();
        mc.player.displayClientMessage(
                Component.translatable("voxlink.chat.room_created").withStyle(Style.EMPTY.withBold(true)),
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
        mc.player.displayClientMessage(
                Component.translatable("voxlink.chat.friends_install_hint"),
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

    @Override
    public void removed() {
        super.removed();
        this.removed = true;
        if (!creating && createdRoom == null) {
            closeLan();
        }
    }

    private void closeLan() {
        if (publishedServer == null || !publishedServer.isPublished()) return;
        try {
            if (publishedServer instanceof net.minecraft.client.server.IntegratedServer integrated) {
                try {
                    var lanPingerField = integrated.getClass().getDeclaredField("lanPinger");
                    lanPingerField.setAccessible(true);
                    Object pinger = lanPingerField.get(integrated);
                    if (pinger instanceof Thread pingerThread) {
                        pingerThread.interrupt();
                    }
                    lanPingerField.set(integrated, null);
                } catch (NoSuchFieldException nsfe) {
                    VoxLinkMod.LOGGER.debug("1.21.11没有lanPinger字段，跳过");
                } catch (Exception e) {
                    VoxLinkMod.LOGGER.debug("lanPinger反射出错: {}", e.getMessage());
                }
            }
            var conn = publishedServer.getConnection();
            if (conn == null) return;
            try {
                var channelsField = conn.getClass().getDeclaredField("channels");
                channelsField.setAccessible(true);
                var channels = (java.util.List<?>) channelsField.get(conn);
                if (channels == null) return;
                synchronized (channels) {
                    var it = channels.iterator();
                    while (it.hasNext()) {
                        var future = (io.netty.channel.ChannelFuture) it.next();
                        if (!(future.channel() instanceof io.netty.channel.local.LocalServerChannel)) {
                            future.channel().close();
                            it.remove();
                        }
                    }
                }
            } catch (NoSuchFieldException nsfe) {
                VoxLinkMod.LOGGER.debug("找不到channels字段，跳过清理");
            }
        } catch (Exception e) {
            VoxLinkMod.LOGGER.warn("关LAN失败: {}", e.getMessage());
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

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        int centerX = this.width / 2;

        if (createdRoom != null) {
            int y = Math.max(20, this.height / 2 - 40);
            drawCenteredClipped(graphics, Component.translatable("voxlink.create_room.success").getString(), centerX, y, 0xFF55FF55);
            y += 18;
            String code = createdRoom.getCode();
            drawCenteredClipped(graphics, Component.translatable("voxlink.chat.room_code_label").getString() + "\u00a7e\u00a7l" + code, centerX, y, 0xFFFFFFFF);
            y += 14;
            String hostIp = createdRoom.getHostIp();
            int hostPort = createdRoom.getHostPort();
            String hostIpv6 = createdRoom.getHostIpv6();
            boolean hasV4 = hostIp != null && !hostIp.isEmpty();
            boolean hasV6 = hostIpv6 != null && !hostIpv6.isEmpty();
            if (hasV4 || hasV6) {
                StringBuilder sb = new StringBuilder(Component.translatable("voxlink.chat.your_addresses").getString());
                if (hasV4) sb.append("\u00a7a[IPv4]\u00a7r");
                if (hasV4 && hasV6) sb.append(" ");
                if (hasV6) sb.append("\u00a7a[IPv6]\u00a7r");
                drawCenteredClipped(graphics, sb.toString(), centerX, y, 0xFFFFFFFF);
                y += 14;
            }
            return;
        }

        drawCenteredClipped(graphics, this.title.getString(), centerX, 8, 0xFFFFFFFF);
    }
}
