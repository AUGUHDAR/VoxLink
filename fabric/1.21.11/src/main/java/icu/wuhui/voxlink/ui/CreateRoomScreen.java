package icu.wuhui.voxlink.ui;

import com.google.gson.JsonObject;
import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.room.RoomInfo;
import icu.wuhui.voxlink.terracotta.TerracottaManager;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CreateRoomScreen extends VoxLinkScreenBase {
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
    private final java.util.List<int[]> successClickAreas = new java.util.ArrayList<>();
    private final java.util.List<String> successClickTexts = new java.util.ArrayList<>();
    private final java.util.List<String> successClickLabels = new java.util.ArrayList<>();

    //布局常量
    private static final int BTN_W = 200;
    private static final int BTN_H = 20;
    private static final int GAP = 4;
    private static final int HALF_BTN_W = 98;
    private static final int PAIR_BTN_OFFSET = 2;
    private static final int FORM_HEIGHT = 240;
    private static final int FORM_MIN_Y = 4;
    private static final int LABEL_OFFSET_Y = 24;
    private static final int FIELD_OFFSET_Y = 48;
    private static final int CATEGORY_OFFSET_Y = 74;
    private static final int CUSTOM_CAT_OFFSET_Y = 22;
    private static final int ADV_OFFSET_Y = 42;
    private static final int ADV_ROW1_OFFSET_Y = 24;
    private static final int ADV_ROW2_OFFSET_Y = 48;
    private static final int ADV_ROW3_OFFSET_Y = 72;
    private static final int CAT_BTN_W = 40;
    private static final int CAT_BTN_W2 = 56;
    private static final int CAT_BTN_GAP = 3;
    private static final int CAT_BTN_H = 18;
    private static final int CAT_AREA_MARGIN = 40;
    private static final int TITLE_Y = 8;
    private static final int SUCCESS_MIN_Y = 20;
    private static final int SUCCESS_OFFSET_Y = 40;
    private static final int SUCCESS_LINE_H = 18;
    private static final int SUCCESS_CODE_H = 9;
    private static final int SUCCESS_SMALL_GAP = 12;
    private static final int SUCCESS_MED_GAP = 14;
    //颜色常量
    private static final int COLOR_ERROR_RGB = 0xFF5555;
    private static final int COLOR_SUCCESS_RGB = 0x55FF55;
    private static final int COLOR_SUCCESS = 0xFF55FF55;
    private static final int COLOR_TITLE = 0xFFFFFFFF;
    private static final int COLOR_INFO = 0xFFAAAAFF;

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

        if (createdRoom != null) {
            int centerX = this.width / 2;
            this.addRenderableWidget(Button.builder(Component.translatable("voxlink.back"), button -> goBack())
                    .bounds(centerX - BTN_W / 2, this.height / 2 + SUCCESS_OFFSET_Y, BTN_W, BTN_H).build());
            return;
        }

        int centerX = this.width / 2;
        // 防抖
        int formHeight = FORM_HEIGHT;
        int y = Math.max(FORM_MIN_Y, (this.height - formHeight) / 2);

        nameField = new EditBox(this.font, centerX - BTN_W / 2, y, BTN_W, BTN_H, Component.translatable("voxlink.room_name"));
        nameField.setMaxLength(20);
        nameField.setHint(Component.translatable("voxlink.create_room.name_hint"));
        if (!savedName.isEmpty()) nameField.setValue(savedName);
        this.addRenderableWidget(nameField);

        passwordField = new EditBox(this.font, centerX - BTN_W / 2, y + LABEL_OFFSET_Y, BTN_W, BTN_H, Component.translatable("voxlink.room_password"));
        passwordField.setMaxLength(32);
        passwordField.setHint(Component.translatable("voxlink.create_room.password_hint"));
        passwordField.setResponder(text -> updateVisibleForPassword());
        if (!savedPassword.isEmpty()) passwordField.setValue(savedPassword);
        this.addRenderableWidget(passwordField);

        maxPlayersField = new EditBox(this.font, centerX - BTN_W / 2, y + FIELD_OFFSET_Y, BTN_W, BTN_H, Component.translatable("voxlink.max_players"));
        maxPlayersField.setMaxLength(3);
        maxPlayersField.setValue(savedMaxPlayers);
        maxPlayersField.setFilter(s -> s.matches("\\d*"));
        this.addRenderableWidget(maxPlayersField);

        int catY = y + CATEGORY_OFFSET_Y;
        buildCategoryButtons(centerX, catY);

        customCategoryField = new EditBox(this.font, centerX - BTN_W / 2, catY + CUSTOM_CAT_OFFSET_Y, BTN_W, CAT_BTN_H, Component.translatable("voxlink.create_room.custom_category"));
        customCategoryField.setMaxLength(32);
        customCategoryField.setHint(Component.translatable("voxlink.create_room.custom_category_hint"));
        customCategoryField.setVisible(showCustomInput);
        customCategoryField.setEditable(showCustomInput);
        this.addRenderableWidget(customCategoryField);

        int advY = catY + ADV_OFFSET_Y;

        visibleButton = Button.builder(buildVisibleLabel(), button -> {
            visible = !visible;
            visibleButton.setMessage(buildVisibleLabel());
        }).bounds(centerX - BTN_W / 2, advY, HALF_BTN_W, BTN_H).build();
        this.addRenderableWidget(visibleButton);
        updateVisibleForPassword();

        authButton = Button.builder(buildAuthLabel(), button -> {
            authType = authType == AuthType.OFFLINE ? AuthType.ONLINE : AuthType.OFFLINE;
            authButton.setMessage(buildAuthLabel());
        }).bounds(centerX + PAIR_BTN_OFFSET, advY, HALF_BTN_W, BTN_H).build();
        this.addRenderableWidget(authButton);

        gameTypeButton = Button.builder(buildGameTypeLabel(), button -> {
            gameType = switch (gameType) {
                case "survival" -> "creative";
                case "creative" -> "adventure";
                case "adventure" -> "spectator";
                default -> "survival";
            };
            gameTypeButton.setMessage(buildGameTypeLabel());
        }).bounds(centerX - BTN_W / 2, advY + ADV_ROW1_OFFSET_Y, HALF_BTN_W, BTN_H).build();
        this.addRenderableWidget(gameTypeButton);

        cheatsButton = Button.builder(buildCheatsLabel(), button -> {
            allowCheats = !allowCheats;
            cheatsButton.setMessage(buildCheatsLabel());
        }).bounds(centerX + PAIR_BTN_OFFSET, advY + ADV_ROW1_OFFSET_Y, HALF_BTN_W, BTN_H).build();
        this.addRenderableWidget(cheatsButton);

        guestOpButton = Button.builder(buildGuestOpLabel(), button -> {
            guestOp = !guestOp;
            guestOpButton.setMessage(buildGuestOpLabel());
        }).bounds(centerX - BTN_W / 2, advY + ADV_ROW2_OFFSET_Y, HALF_BTN_W, BTN_H).build();
        this.addRenderableWidget(guestOpButton);

        if (TerracottaManager.isBinaryReady()) {
            addRenderableWidget(Button.builder(
                    Component.translatable("voxlink.terracotta.toggle",
                            Component.translatable(VoxLinkMod.getConfig().isParallelP2P()
                                    ? "voxlink.terracotta.on" : "voxlink.terracotta.off")),
                    button -> {
                        boolean v = !VoxLinkMod.getConfig().isParallelP2P();
                        VoxLinkMod.getConfig().setParallelP2P(v);
                        VoxLinkMod.getConfig().save();
                        button.setMessage(Component.translatable("voxlink.terracotta.toggle",
                                Component.translatable(v ? "voxlink.terracotta.on" : "voxlink.terracotta.off")));
                    }
            ).bounds(centerX + PAIR_BTN_OFFSET, advY + ADV_ROW2_OFFSET_Y, HALF_BTN_W, BTN_H).build());
        }

        createButton = Button.builder(Component.translatable("voxlink.create_room"), button -> createRoom())
                .bounds(centerX - BTN_W / 2, advY + ADV_ROW3_OFFSET_Y, HALF_BTN_W, BTN_H).build();
        this.addRenderableWidget(createButton);

        backButton = Button.builder(Component.translatable("voxlink.back"), button ->
                Minecraft.getInstance().setScreen(parent)
        ).bounds(centerX + PAIR_BTN_OFFSET, advY + ADV_ROW3_OFFSET_Y, HALF_BTN_W, BTN_H).build();
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
        int btnW = Math.max(CAT_BTN_W, Math.min(CAT_BTN_W2, (this.width - CAT_AREA_MARGIN) / totalCats - GAP));
        int gap = CAT_BTN_GAP;
        int totalW = totalCats * btnW + (totalCats - 1) * gap;
        int startX = centerX - totalW / 2;

        for (int i = 0; i < totalCats; i++) {
            String key = keys.get(i);
            final String catKey = key;

            Button btn = Button.builder(getLabelComponentForKey(key, false), b -> {
                selectedCategory = catKey;
                boolean nowOther = catKey.equals("other");
                if (nowOther != showCustomInput) {
                    showCustomInput = nowOther;
                    customCategoryField.setVisible(nowOther);
                    customCategoryField.setEditable(nowOther);
                }
                rebuildCategoryLabels();
            }).bounds(startX + i * (btnW + gap), startY, btnW, CAT_BTN_H).build();

            categoryButtons.add(btn);
            this.addRenderableWidget(btn);
        }

        rebuildCategoryLabels();
    }

    private void rebuildCategoryLabels() {
        for (int i = 0; i < categoryButtons.size(); i++) {
            Button btn = categoryButtons.get(i);
            String key = getCategoryKeyAtIndex(i);
            btn.setMessage(getLabelComponentForKey(key, key.equals(selectedCategory)));
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

    private Component getLabelComponentForKey(String key, boolean bold) {
        net.minecraft.network.chat.MutableComponent label = DEFAULT_CATEGORIES.containsKey(key)
                ? Component.translatable(DEFAULT_CATEGORIES.get(key))
                : Component.literal(categoryMap.getOrDefault(key, key));
        return bold ? label.withStyle(Style.EMPTY.withBold(true)) : label;
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
                                buildCategoryButtons(this.width / 2, Math.max(FORM_MIN_Y, (this.height - FORM_HEIGHT) / 2) + CATEGORY_OFFSET_Y);
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

    @Override
    public void onClose() {
        if (creating) return;
        goBack();
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
                mc.player.displayClientMessage(Component.translatable("voxlink.create_room.open_world_first").withStyle(style -> style.withColor(COLOR_ERROR_RGB)), false);
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
                    mc.player.displayClientMessage(Component.translatable("voxlink.create_room.lan_failed").withStyle(style -> style.withColor(COLOR_ERROR_RGB)), false);
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
        //debounce 不显示明文房间号 只显示点击复制标签
        mc.player.displayClientMessage(
                Component.translatable("voxlink.chat.room_code_label")
                        .append(Component.literal(ChatFormatting.GREEN.toString() + ChatFormatting.BOLD.toString()
                                        + "[" + Component.translatable("voxlink.chat.click_to_copy").getString() + "]")
                                .withStyle(ChatCompat.styleWithCopy(code,
                                        Component.translatable("voxlink.chat.click_to_copy")))),
                false
        );
        mc.player.displayClientMessage(
                Component.translatable("voxlink.chat.friends_install_hint"),
                false
        );
        mc.player.displayClientMessage(
                Component.translatable("voxlink.create_room.recommend_voxlink"),
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
                addrLine.append(Component.translatable("voxlink.chat.ipv4_label")
                        .withStyle(ChatCompat.styleWithCopy(addr,
                                Component.translatable("voxlink.chat.copy_for_non_voxlink"))
                                .withColor(COLOR_SUCCESS_RGB)));
            }
            if (hasV4 && hasV6) addrLine.append(Component.literal(" "));
            if (hasV6) {
                String ipv6Addr = "[" + hostIpv6 + "]:" + hostPort;
                addrLine.append(Component.translatable("voxlink.chat.ipv6_label")
                        .withStyle(ChatCompat.styleWithCopy(ipv6Addr,
                                Component.translatable("voxlink.chat.copy_for_non_voxlink"))
                                .withColor(COLOR_SUCCESS_RGB)));
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

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        int centerX = this.width / 2;

        if (createdRoom != null) {
            successClickAreas.clear();
            successClickTexts.clear();
            successClickLabels.clear();
            net.minecraft.client.gui.Font font = Minecraft.getInstance().font;
            int y = Math.max(SUCCESS_MIN_Y, this.height / 2 - SUCCESS_OFFSET_Y);
            drawCenteredClipped(graphics, Component.translatable("voxlink.create_room.success").getString(), centerX, y, COLOR_SUCCESS);
            y += SUCCESS_LINE_H;
            String code = createdRoom.getCode();
            //debounce 不显示明文 只显示标签 点击复制
            String codeLine = Component.translatable("voxlink.chat.room_code_label").getString() + ChatFormatting.GREEN.toString() + ChatFormatting.BOLD.toString() + Component.translatable("voxlink.chat.click_to_copy").getString();
            drawCenteredClipped(graphics, codeLine, centerX, y, COLOR_TITLE);
            int codeW = font.width(codeLine);
            successClickAreas.add(new int[]{centerX - codeW / 2, y, codeW, SUCCESS_CODE_H});
            successClickTexts.add(code);
            successClickLabels.add(Component.translatable("voxlink.chat.room_code_label").getString());
            y += SUCCESS_SMALL_GAP;
            drawCenteredClipped(graphics, Component.translatable("voxlink.create_room.recommend_voxlink").getString(), centerX, y, COLOR_INFO);
            y += SUCCESS_MED_GAP;
            String tc = createdRoom.getTerracottaCode();
            if (tc != null && !tc.isEmpty()) {
                String tcLine = Component.translatable("voxlink.chat.terracotta_code_label", "").getString() + " " + ChatFormatting.AQUA.toString() + ChatFormatting.BOLD.toString() + Component.translatable("voxlink.chat.click_to_copy").getString();
                drawCenteredClipped(graphics, tcLine, centerX, y, COLOR_TITLE);
                int tcW = font.width(tcLine);
                successClickAreas.add(new int[]{centerX - tcW / 2, y, tcW, SUCCESS_CODE_H});
                successClickTexts.add(tc);
                successClickLabels.add(Component.translatable("voxlink.chat.terracotta_code_label", "").getString().trim());
                y += SUCCESS_MED_GAP;
            }
            String hostIp = createdRoom.getHostIp();
            int hostPort = createdRoom.getHostPort();
            String hostIpv6 = createdRoom.getHostIpv6();
            boolean hasV4 = hostIp != null && !hostIp.isEmpty();
            boolean hasV6 = hostIpv6 != null && !hostIpv6.isEmpty();
            if (hasV4 || hasV6) {
                String addrLabel = Component.translatable("voxlink.chat.your_addresses").getString();
                int labelW = font.width(addrLabel);
                String v4Label = hasV4 ? Component.translatable("voxlink.chat.ipv4_label").getString() : "";
                String v6Label = hasV6 ? Component.translatable("voxlink.chat.ipv6_label").getString() : "";
                int v4W = hasV4 ? font.width(v4Label) : 0;
                int v6W = hasV6 ? font.width(v6Label) : 0;
                int spaceW = (hasV4 && hasV6) ? font.width(" ") : 0;
                int totalW = labelW + v4W + spaceW + v6W;
                int startX = centerX - totalW / 2;

                drawString(graphics, addrLabel, startX, y, COLOR_TITLE);
                int curX = startX + labelW;
                if (hasV4) {
                    drawString(graphics, ChatFormatting.GREEN.toString() + v4Label + ChatFormatting.RESET.toString(), curX, y, COLOR_SUCCESS);
                    successClickAreas.add(new int[]{curX, y, v4W, SUCCESS_CODE_H});
                    successClickTexts.add((hostIp.contains(":") ? "[" + hostIp + "]" : hostIp) + ":" + hostPort);
                    successClickLabels.add(v4Label);
                    curX += v4W;
                }
                if (hasV4 && hasV6) {
                    drawString(graphics, " ", curX, y, COLOR_TITLE);
                    curX += spaceW;
                }
                if (hasV6) {
                    drawString(graphics, ChatFormatting.GREEN.toString() + v6Label + ChatFormatting.RESET.toString(), curX, y, COLOR_SUCCESS);
                    successClickAreas.add(new int[]{curX, y, v6W, SUCCESS_CODE_H});
                    successClickTexts.add("[" + hostIpv6 + "]:" + hostPort);
                    successClickLabels.add(v6Label);
                }
                y += SUCCESS_MED_GAP;
            }
            return;
        }

        drawCenteredClipped(graphics, this.title.getString(), centerX, TITLE_Y, COLOR_TITLE);
    }

    protected boolean handleSuccessClick(double mx, double my) {
        if (createdRoom != null) {
            for (int i = 0; i < successClickAreas.size(); i++) {
                int[] a = successClickAreas.get(i);
                if (mx >= a[0] && mx < a[0] + a[2] && my >= a[1] && my < a[1] + a[3]) {
                    String text = successClickTexts.get(i);
                    String label = successClickLabels.get(i);
                    Minecraft.getInstance().keyboardHandler.setClipboard(text);
                    if (Minecraft.getInstance().player != null) {
                        Minecraft.getInstance().player.displayClientMessage(
                            Component.translatable("voxlink.chat.copied_to_clipboard", label), false);
                    }
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean processed) {
        if (processed) return super.mouseClicked(event, processed);
        if (handleSuccessClick(event.x(), event.y())) return true;
        return super.mouseClicked(event, processed);
    }
}
