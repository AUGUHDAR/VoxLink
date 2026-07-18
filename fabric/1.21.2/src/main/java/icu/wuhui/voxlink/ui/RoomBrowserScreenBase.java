package icu.wuhui.voxlink.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.compat.ViaCompat;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

//1.21.x 专属: 原生输入 API
public class RoomBrowserScreenBase extends VoxLinkScreenBase {
    private static final int KEY_ENTER = 257;
    private static final int COLOR_WHITE = 0xFFFFFFFF;
    private static final int COLOR_MUTED = 0xFFAAAAAA;
    private static final int COLOR_ERROR = 0xFFFF5555;
    private static final int COLOR_WARNING = 0xFFFFFF55;
    private static final int COLOR_SUCCESS = 0xFF55FF55;
    private static final int COLOR_TEXT_LIGHT = 0xFFCCCCCC;
    private static final int COLOR_TEXT_DIM = 0xFF888888;
    private static final int COLOR_NO_ROOMS_HINT = 0xFF666666;
    private static final int COLOR_CAT_BADGE_BG = 0x44333333;
    private static final int COLOR_CAT_BADGE_TEXT = 0xFF999999;
    private static final int COLOR_BG_SELECTED = 0xDD122E8A;
    private static final int COLOR_BG_HOVER = 0xDD555555;
    private static final int COLOR_BG_NORMAL = 0xDD333333;
    private static final int COLOR_PAGE_BTN_DISABLED_BG = 0x44888888;
    private static final int COLOR_PAGE_BTN_HOVER = 0xDD666666;
    private static final int COLOR_PAGE_BTN_NORMAL = 0xDD444444;

    private static final int SEARCH_Y = 8;
    private static final int SEARCH_H = 18;
    private static final int ELEMENT_GAP = 8;
    private static final int TOP_BTN_Y = 6;
    private static final int TOP_BTN_H = 20;
    private static final int REFRESH_BTN_W = 60;
    private static final int BOTTOM_MARGIN = 24;
    private static final int PAGE_INPUT_W = 32;
    private static final int PAGE_BTN_H = 14;
    private static final int CAT_Y = 32;
    private static final int MIN_CAT_BTN_W = 38;
    private static final int BTN_H = 18;
    private static final int SCROLL_SPEED = 20;
    private static final int LIST_BOTTOM_MARGIN = 52;
    private static final int CARD_TEXT_X = 6;
    private static final int CARD_TRUNC_DIV = 6;
    private static final int CARD_TEXT_Y = 5;
    private static final int CARD_CODE_Y = 11;
    private static final int CARD_PLAYERS_Y = 22;
    private static final int CAT_BADGE_W_PAD = 4;
    private static final int CAT_BADGE_MARGIN = 3;
    private static final int CAT_BADGE_TEXT_Y = 4;
    private static final int CAT_BADGE_BOTTOM_Y = 13;
    private static final int NO_ROOMS_Y_OFFSET = 30;
    private static final int NO_ROOMS_HINT_Y_OFFSET = 44;
    private static final int PAGE_BAR_Y_MARGIN = 46;
    private static final int PAGE_BTN_W = 16;
    private static final int PAGE_BTN_GAP = 2;
    private static final int GRID_Y_CUSTOM = 74;
    private static final int GRID_Y_DEFAULT = 54;
    private static final int COL_DIVISOR = 160;
    private static final int CARD_H = 48;
    private static final int GAP_DIVISOR = 80;

    protected final Screen parent;

    protected List<RoomEntry> allRooms = new ArrayList<>();
    protected List<RoomEntry> displayedRooms = new ArrayList<>();
    protected String selectedCategory = "all";
    protected SortMode sortMode = SortMode.PLAYERS_DESC;
    protected int scrollOffset = 0;
    protected EditBox searchField;
    protected Button joinBtn;
    protected int selectedIdx = -1;
    protected String statusMsg = "";
    protected int statusColor = COLOR_MUTED;
    protected boolean initialFetchDone = false;
    protected int currentPage = 1;
    protected int totalRooms = 0;
    protected volatile boolean loadingMore = false;
    protected volatile boolean removed = false;
    protected java.util.Map<String, String> categoryMap = new java.util.LinkedHashMap<>();
    protected boolean categoriesFetched = false;
    protected java.util.List<Button> categoryButtons = new java.util.ArrayList<>();
    protected java.util.List<String> customCatKeys = new java.util.ArrayList<>();
    protected int customTagStartIndex = 0;
    protected int customTagShowCount = 10;
    protected java.util.List<Button> customTagRowButtons = new java.util.ArrayList<>();
    protected Button shuffleCustomBtn;
    protected Button showMoreCustomBtn;
    protected String savedSearch = "";
    protected EditBox pageInput;
    protected java.util.List<int[]> pageClickAreas = new java.util.ArrayList<>();

    protected static final java.util.Set<String> DEFAULT_CATEGORY_KEYS = java.util.Set.of(
            "survival", "creative", "redstone", "pvp", "rpg", "minigame", "social", "other"
    );

    private static final String GAME_VERSION = "1.21.2";

    protected enum SortMode {
        PLAYERS_DESC(Component.translatable("voxlink.sort.players_desc")), PLAYERS_ASC(Component.translatable("voxlink.sort.players_asc")),
        VERSION_SAME(Component.translatable("voxlink.sort.version_same")), NAME_ASC(Component.translatable("voxlink.sort.name_asc")), NAME_DESC(Component.translatable("voxlink.sort.name_desc"));
        final Component label;
        SortMode(Component label) { this.label = label; }
    }

    protected record RoomEntry(String code, String name, String category, int players, int maxPlayers,
                               boolean hasPassword, String natType, int protocolVersion, String gameVersion) {}

    public RoomBrowserScreenBase(Screen parent) {
        super(Component.translatable("voxlink.browser.title"));
        this.parent = parent;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    protected void init() {
        super.init();

        int w = this.width;
        int pad = Math.max(8, w / 40);

        searchField = new EditBox(Minecraft.getInstance().font, pad, SEARCH_Y, w / 3, SEARCH_H, Component.translatable("voxlink.search"));
        searchField.setHint(Component.translatable("voxlink.browser.search_hint"));
        searchField.setResponder(q -> { savedSearch = q; applyFilter(); });
        searchField.setValue(savedSearch);
        this.addRenderableWidget(searchField);

        int sortX = w / 3 + pad + ELEMENT_GAP;
        this.addRenderableWidget(Button.builder(Component.translatable("voxlink.browser.sort", sortMode.label), b -> {
            sortMode = SortMode.values()[(sortMode.ordinal() + 1) % SortMode.values().length];
            b.setMessage(Component.translatable("voxlink.browser.sort", sortMode.label));
            applyFilter();
        }).bounds(sortX, TOP_BTN_Y, w / 5, TOP_BTN_H).build());

        this.addRenderableWidget(Button.builder(Component.translatable("voxlink.refresh"), b -> fetchRooms())
                .bounds(w - pad - REFRESH_BTN_W, TOP_BTN_Y, REFRESH_BTN_W, TOP_BTN_H).build());

        joinBtn = Button.builder(Component.translatable("voxlink.join_room"), b -> joinSelected())
                .bounds(w / 2 - 10, this.height - BOTTOM_MARGIN, 100, 20).build();
        joinBtn.active = false;
        this.addRenderableWidget(joinBtn);

        this.addRenderableWidget(Button.builder(Component.translatable("voxlink.back"), b ->
                Minecraft.getInstance().setScreen(parent))
                .bounds(w / 2 - 130, this.height - BOTTOM_MARGIN, 100, 20).build());

        pageInput = new EditBox(Minecraft.getInstance().font, -100, -100, PAGE_INPUT_W, PAGE_BTN_H, Component.literal(""));
        pageInput.setMaxLength(4);
        pageInput.setVisible(false);
        pageInput.setResponder(t -> {});
        pageInput.setHint(Component.translatable("voxlink.page.input_hint"));
        this.addRenderableWidget(pageInput);

        if (!initialFetchDone) {
            initialFetchDone = true;
            fetchCategories();
            fetchRooms();
        }

        rebuildCategoryButtons();
    }

    private void fetchCategories() {
        VoxLinkMod.getSignalingClient().getCategories()
                .thenAccept(apiResponse -> {
                    Minecraft mc = Minecraft.getInstance();
                    mc.execute(() -> {
                        if (removed) return;
                        try {
                            if (apiResponse.success && apiResponse.data != null && apiResponse.data.isJsonObject()) {
                                JsonObject obj = apiResponse.data.getAsJsonObject();
                                categoryMap.clear();
                                for (String key : obj.keySet()) {
                                    categoryMap.put(key, obj.get(key).getAsString());
                                }
                                categoriesFetched = true;
                                rebuildCategoryButtons();
                            }
                        } catch (Exception ignored) {}
                    });
                })
                .exceptionally(e -> null);
    }

    protected void rebuildCategoryButtons() {
        int w = this.width;
        int pad = Math.max(8, w / 40);
        int catY = CAT_Y;

        for (Button btn : categoryButtons) {
            this.removeWidget(btn);
        }
        categoryButtons.clear();
        for (Button btn : customTagRowButtons) {
            this.removeWidget(btn);
        }
        customTagRowButtons.clear();
        if (shuffleCustomBtn != null) {
            this.removeWidget(shuffleCustomBtn);
            shuffleCustomBtn = null;
        }
        if (showMoreCustomBtn != null) {
            this.removeWidget(showMoreCustomBtn);
            showMoreCustomBtn = null;
        }

        java.util.List<String> defaultKeys = new java.util.ArrayList<>();
        defaultKeys.add("all");
        for (String key : categoryMap.keySet()) {
            if (DEFAULT_CATEGORY_KEYS.contains(key) && !defaultKeys.contains(key)) {
                defaultKeys.add(key);
            }
        }

        int totalDef = defaultKeys.size();
        int defW = Math.max(MIN_CAT_BTN_W, (w - pad * 2 - (totalDef - 1) * 2) / totalDef);
        int defStartX = (w - totalDef * (defW + 2)) / 2;
        for (int i = 0; i < totalDef; i++) {
            final String cat = defaultKeys.get(i);
            Component label;
            if ("all".equals(cat)) {
                label = Component.translatable("voxlink.category.all");
            } else if (DEFAULT_CATEGORY_KEYS.contains(cat)) {
                label = Component.translatable("voxlink.category." + cat);
            } else {
                label = Component.literal(categoryMap.getOrDefault(cat, cat));
            }
            Button btn = Button.builder(label, b -> {
                selectedCategory = cat;
                fetchRooms();
                rebuildCategoryButtons();
            }).bounds(defStartX + i * (defW + 2), catY, defW, BTN_H).build();
            categoryButtons.add(btn);
            this.addRenderableWidget(btn);
        }

        customCatKeys.clear();
        for (String key : categoryMap.keySet()) {
            if (!DEFAULT_CATEGORY_KEYS.contains(key)) {
                customCatKeys.add(key);
            }
        }
        java.util.Collections.sort(customCatKeys);

        if (isCustomRowVisible() && !customCatKeys.isEmpty()) {
            int customRowY = catY + 20;
            int visibleCount = Math.min(customTagShowCount, customCatKeys.size() - customTagStartIndex);
            if (visibleCount < 0) visibleCount = 0;
            if (visibleCount > 0) {
                int totalItems = visibleCount;
                boolean needShuffle = customCatKeys.size() > visibleCount;
                boolean needMore = customTagStartIndex + customTagShowCount < customCatKeys.size();
                if (needShuffle) totalItems++;
                if (needMore) totalItems++;
                int itemW = Math.max(36, (w - pad * 2 - (totalItems - 1) * 2) / totalItems);
                int itemStartX = (w - totalItems * (itemW + 2)) / 2;

                for (int i = 0; i < visibleCount; i++) {
                    final String cat = customCatKeys.get(customTagStartIndex + i);
                    Component label = Component.literal(categoryMap.getOrDefault(cat, cat));
                    Button btn = Button.builder(label, b -> {
                        selectedCategory = cat;
                        fetchRooms();
                        rebuildCategoryButtons();
                    }).bounds(itemStartX + i * (itemW + 2), customRowY, itemW, BTN_H).build();
                    customTagRowButtons.add(btn);
                    this.addRenderableWidget(btn);
                }

                int btnIdx = visibleCount;
                if (needShuffle) {
                    shuffleCustomBtn = Button.builder(Component.translatable("voxlink.shuffle"), b -> {
                        int total = customCatKeys.size();
                        int step = Math.min(10, total);
                        customTagStartIndex = (customTagStartIndex + step) % total;
                        customTagShowCount = step;
                        rebuildCategoryButtons();
                    }).bounds(itemStartX + btnIdx * (itemW + 2), customRowY, itemW, BTN_H).build();
                    this.addRenderableWidget(shuffleCustomBtn);
                    btnIdx++;
                }

                if (needMore) {
                    showMoreCustomBtn = Button.builder(Component.translatable("voxlink.show_more"), b -> {
                        customTagShowCount += 10;
                        rebuildCategoryButtons();
                    }).bounds(itemStartX + btnIdx * (itemW + 2), customRowY, itemW, BTN_H).build();
                    this.addRenderableWidget(showMoreCustomBtn);
                }
            }
        }
    }

    protected static final int PAGE_SIZE = 20;

    protected void fetchRooms() {
        fetchPage(1, true);
    }

    protected void fetchMoreRooms() {
        if (loadingMore) return;
        int tp = totalPages();
        if (tp > 0 && currentPage >= tp) return;
        fetchPage(currentPage + 1, false);
    }

    protected int totalPages() {
        return totalRooms > 0 ? (int) Math.ceil(totalRooms / (double) PAGE_SIZE) : 0;
    }

    protected void fetchPage(int page, boolean clear) {
        if (loadingMore) return;
        int tp = totalPages();
        if (tp > 0 && (page < 1 || page > tp)) return;
        loadingMore = true;
        if (clear) {
            currentPage = 1;
            allRooms.clear();
            scrollOffset = 0;
            statusMsg = Component.translatable("voxlink.browser.loading").getString();
            statusColor = COLOR_WARNING;
        }
        String category = "all".equals(selectedCategory) ? null : selectedCategory;
        final int finalPage = page;
        VoxLinkMod.getSignalingClient().listRooms(page, PAGE_SIZE, category)
                .thenAccept(apiResponse -> {
                    Minecraft mc = Minecraft.getInstance();
                    mc.execute(() -> {
                        if (removed) return;
                        loadingMore = false;
                        try {
                            if (!apiResponse.success || apiResponse.data == null) {
                                if (clear) {
                                    statusMsg = ChatFormatting.RED.toString() + Component.translatable("voxlink.browser.load_failed").getString();
                                    statusColor = COLOR_ERROR;
                                }
                                return;
                            }
                            JsonObject data = apiResponse.data;
                            totalRooms = data.has("total") ? data.get("total").getAsInt() : totalRooms;
                            if (clear) allRooms.clear();
                            if (data.has("rooms") && data.get("rooms").isJsonArray()) {
                                java.util.Set<String> existingCodes = new java.util.HashSet<>();
                                for (RoomEntry existing : allRooms) {
                                    existingCodes.add(existing.code);
                                }
                                for (JsonElement e : data.getAsJsonArray("rooms")) {
                                    JsonObject r = e.getAsJsonObject();
                                    String code = r.has("code") ? r.get("code").getAsString() : "";
                                    if (existingCodes.contains(code)) continue;
                                    String roomClientType = r.has("clientType") ? r.get("clientType").getAsString() : "mod";
                                    if (!"mod".equals(roomClientType)) continue;
                                    allRooms.add(new RoomEntry(
                                            code,
                                            r.has("name") ? r.get("name").getAsString() : Component.translatable("voxlink.unknown").getString(),
                                            r.has("category") ? r.get("category").getAsString() : "other",
                                            r.has("currentPlayers") ? r.get("currentPlayers").getAsInt() : 0,
                                            r.has("maxPlayers") ? r.get("maxPlayers").getAsInt() : 20,
                                            r.has("hasPassword") && r.get("hasPassword").getAsBoolean(),
                                            r.has("natType") ? r.get("natType").getAsString() : "unknown",
                                            r.has("protocolVersion") ? r.get("protocolVersion").getAsInt() : 0,
                                            r.has("gameVersion") ? r.get("gameVersion").getAsString() : ""
                                    ));
                                    existingCodes.add(code);
                                }
                            }
                            currentPage = finalPage;
                            fetchP2PDetails();
                        } catch (Exception ex) {
                            statusMsg = Component.translatable("voxlink.browser.load_rooms_failed").getString();
                            statusColor = COLOR_ERROR;
                        }
                    });
                })
                .exceptionally(e -> {
                    Minecraft.getInstance().execute(() -> {
                        loadingMore = false;
                        statusMsg = Component.translatable("voxlink.error.network_error").getString();
                        statusColor = COLOR_ERROR;
                    });
                    return null;
                });
    }

    protected void fetchP2PDetails() {
        applyFilter();
        statusMsg = allRooms.size() + " " + Component.translatable("voxlink.browser.rooms_count").getString();
        statusColor = COLOR_SUCCESS;
    }

    protected void applyFilter() {
        String query = searchField.getValue().trim().toLowerCase();
        int myProtocol = ViaCompat.isViaLoaded() ? ViaCompat.getServerProtocolVersion() : 0;

        displayedRooms = allRooms.stream()
                .filter(r -> !r.hasPassword)
                .filter(r -> selectedCategory.equals("all") || r.category.equals(selectedCategory))
                .filter(r -> query.isEmpty() || r.name.toLowerCase().contains(query) || r.code.toLowerCase().contains(query))
                .sorted(getComparator(myProtocol))
                .toList();
        scrollOffset = 0;
        selectedIdx = -1;
        if (joinBtn != null) joinBtn.active = false;
    }

    protected Comparator<RoomEntry> getComparator(int myProtocol) {
        return switch (sortMode) {
            case PLAYERS_DESC -> Comparator.comparingInt((RoomEntry r) -> r.players).reversed();
            case PLAYERS_ASC -> Comparator.comparingInt(r -> r.players);
            case VERSION_SAME -> Comparator.comparingInt((RoomEntry r) -> GAME_VERSION.equals(r.gameVersion) ? 0 : 1)
                    .thenComparingInt(r -> -r.players);
            case NAME_ASC -> Comparator.comparing(r -> r.name.toLowerCase());
            case NAME_DESC -> Comparator.comparing((RoomEntry r) -> r.name.toLowerCase()).reversed();
        };
    }

    protected void joinSelected() {
        if (selectedIdx < 0 || selectedIdx >= displayedRooms.size()) return;
        RoomEntry room = displayedRooms.get(selectedIdx);
        Minecraft.getInstance().setScreen(new AttemptingJoinScreen(this, room.code, null));
    }

    protected boolean handleClick(double mouseX, double mouseY, int button) {
        for (int[] a : pageClickAreas) {
            if (mouseX >= a[0] && mouseX < a[0] + a[2] && mouseY >= a[1] && mouseY < a[1] + a[3]) {
                int page = a[4];
                if (page == -1) fetchPage(Math.max(1, currentPage - 1), true);
                else if (page == -3) fetchPage(Math.min(totalPages(), currentPage + 1), true);
                else if (page == -2) {
                    try { int p = Integer.parseInt(pageInput.getValue().trim()); fetchPage(p, true); } catch (Exception ignored) {}
                } else if (page > 0) fetchPage(page, true);
                return true;
            }
        }
        int cols = getColumns();
        int cardW = getCardWidth(cols);
        int cardH = getCardHeight();
        int gap = getGap();
        int gridX = getGridStartX(cols, cardW, gap);
        int gridY = getGridY();

        for (int i = 0; i < displayedRooms.size(); i++) {
            int col = i % cols;
            int row = i / cols;
            int x = gridX + col * (cardW + gap);
            int y = gridY + row * (cardH + gap) - scrollOffset;
            if (y >= gridY - cardH && y < this.height - 36) {
                if (mouseX >= x && mouseX < x + cardW && mouseY >= y && mouseY < y + cardH) {
                    selectedIdx = i;
                    joinBtn.active = true;
                    return true;
                }
            }
        }
        return false;
    }

    protected boolean handleKeyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == KEY_ENTER && pageInput != null && pageInput.isVisible() && pageInput.isFocused()) {
            try {
                int p = Integer.parseInt(pageInput.getValue().trim());
                fetchPage(p, true);
                return true;
            } catch (Exception ignored) {}
        }
        return false;
    }

    protected boolean handleMouseScrolled(double scrollY) {
        int cols = getColumns();
        int totalRows = (displayedRooms.size() + cols - 1) / cols;
        int cardH = getCardHeight();
        int gap = getGap();
        int maxScroll = Math.max(0, totalRows * (cardH + gap) - (this.height - 36 - getGridY()));
        int newOffset = Math.max(0, Math.min(maxScroll, scrollOffset - (int) scrollY * SCROLL_SPEED));
        scrollOffset = newOffset;
        if (newOffset >= maxScroll - 50 && allRooms.size() < totalRooms) {
            fetchMoreRooms();
        }
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (handleClick(mouseX, mouseY, button)) return true;
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (handleKeyPressed(keyCode, scanCode, modifiers)) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (handleMouseScrolled(scrollY)) return true;
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        updatePageInput();
        super.render(graphics, mouseX, mouseY, partialTick);

        int cols = getColumns();
        int cardW = getCardWidth(cols);
        int cardH = getCardHeight();
        int gap = getGap();
        int gridX = getGridStartX(cols, cardW, gap);
        int gridY = getGridY();
        int bottom = this.height - LIST_BOTTOM_MARGIN;

        for (int i = 0; i < displayedRooms.size(); i++) {
            int col = i % cols;
            int row = i / cols;
            int x = gridX + col * (cardW + gap);
            int y = gridY + row * (cardH + gap) - scrollOffset;
            if (y < gridY - cardH || y >= bottom) continue;

            RoomEntry r = displayedRooms.get(i);
            boolean sel = i == selectedIdx;
            boolean hover = mouseX >= x && mouseX < x + cardW && mouseY >= y && mouseY < y + cardH;

            int bg = sel ? COLOR_BG_SELECTED : (hover ? COLOR_BG_HOVER : COLOR_BG_NORMAL);
            graphics.fill(x, y, x + cardW, y + cardH, bg);

            String roomName = r.name;
            if ("name_not_approved".equals(roomName)) {
                roomName = Component.translatable("voxlink.room.name_not_approved").getString();
            }

            int textX = x + CARD_TEXT_X;
            int textY = y + CARD_TEXT_Y;

            drawString(graphics, truncate(roomName, cardW / CARD_TRUNC_DIV), textX, textY, COLOR_WHITE);
            drawString(graphics, ChatFormatting.GRAY.toString() + r.code, textX, textY + CARD_CODE_Y, COLOR_MUTED);
            drawString(graphics, ChatFormatting.WHITE.toString() + r.players + "/" + r.maxPlayers, textX, textY + CARD_PLAYERS_Y, COLOR_TEXT_LIGHT);

            String catLabel = getCategoryLabel(r.category);
            int catW = fontWidth(catLabel) + CAT_BADGE_W_PAD;
            graphics.fill(x + cardW - catW - CAT_BADGE_MARGIN, y + CAT_BADGE_MARGIN, x + cardW - CAT_BADGE_MARGIN, y + CAT_BADGE_BOTTOM_Y, COLOR_CAT_BADGE_BG);
            drawString(graphics, ChatFormatting.GRAY.toString() + catLabel, x + cardW - catW - 1, y + CAT_BADGE_TEXT_Y, COLOR_CAT_BADGE_TEXT);
        }

        if (displayedRooms.isEmpty()) {
            drawCenteredString(graphics, Component.translatable("voxlink.browser.no_rooms").getString(), this.width / 2, gridY + NO_ROOMS_Y_OFFSET, COLOR_TEXT_DIM);
            drawCenteredString(graphics, Component.translatable("voxlink.browser.no_rooms_hint").getString(), this.width / 2, gridY + NO_ROOMS_HINT_Y_OFFSET, COLOR_NO_ROOMS_HINT);
        }

        if (!statusMsg.isEmpty()) {
            String clippedStatus = statusMsg;
            int maxStatusWidth = this.width - 20;
            if (fontWidth(statusMsg) > maxStatusWidth) {
                while (fontWidth(clippedStatus + "...") > maxStatusWidth && clippedStatus.length() > 0) {
                    clippedStatus = clippedStatus.substring(0, clippedStatus.length() - 1);
                }
                clippedStatus = clippedStatus + "...";
            }
            drawCenteredString(graphics, clippedStatus, this.width / 2, this.height - 64, statusColor);
        }

        renderPagination(graphics, mouseX, mouseY);
    }

    protected void renderPagination(GuiGraphics graphics, int mouseX, int mouseY) {
        pageClickAreas.clear();
        int tp = totalPages();
        if (tp <= 1) return;
        int py = this.height - PAGE_BAR_Y_MARGIN;
        int bw = PAGE_BTN_W, bh = PAGE_BTN_H, gp = PAGE_BTN_GAP, iw = PAGE_INPUT_W;
        boolean showInput = tp > 6;
        int totalW = showInput ? (bw + gp) * 2 + bw * 3 + gp * 3 + iw + gp + bw + gp + bw
                               : (bw + gp) * 2 + bw * tp + gp * (tp - 1);
        int x = (this.width - totalW) / 2;
        drawPageBtn(graphics, mouseX, mouseY, x, py, bw, bh, Component.translatable("voxlink.page.prev").getString(), currentPage > 1, -1, currentPage);
        x += bw + gp;
        if (showInput) {
            for (int i = 1; i <= 3; i++) {
                drawPageBtn(graphics, mouseX, mouseY, x, py, bw, bh, String.valueOf(i), true, i, currentPage);
                x += bw + gp;
            }
            x += iw + gp;
            drawPageBtn(graphics, mouseX, mouseY, x, py, bw, bh, Component.translatable("voxlink.page.jump").getString(), true, -2, currentPage);
            x += bw + gp;
            drawPageBtn(graphics, mouseX, mouseY, x, py, bw, bh, String.valueOf(tp), true, tp, currentPage);
            x += bw + gp;
        } else {
            for (int i = 1; i <= tp; i++) {
                drawPageBtn(graphics, mouseX, mouseY, x, py, bw, bh, String.valueOf(i), true, i, currentPage);
                x += bw + gp;
            }
        }
        drawPageBtn(graphics, mouseX, mouseY, x, py, bw, bh, Component.translatable("voxlink.page.next").getString(), currentPage < tp, -3, currentPage);
    }

    protected void updatePageInput() {
        int tp = totalPages();
        if (tp > 6) {
            int py = this.height - PAGE_BAR_Y_MARGIN;
            int bw = PAGE_BTN_W, gp = PAGE_BTN_GAP, iw = PAGE_INPUT_W;
            int totalW = (bw + gp) * 2 + bw * 3 + gp * 3 + iw + gp + bw + gp + bw;
            int startX = (this.width - totalW) / 2;
            int inputX = startX + bw + gp + (bw + gp) * 3;
            pageInput.setPosition(inputX, py);
            pageInput.setVisible(true);
        } else {
            pageInput.setVisible(false);
        }
    }

    protected void drawPageBtn(GuiGraphics graphics, int mx, int my, int x, int y, int w, int h, String label, boolean enabled, int page, int currentPage) {
        boolean active = enabled && page == currentPage;
        boolean hover = enabled && mx >= x && mx < x + w && my >= y && my < y + h;
        int bg = !enabled ? COLOR_PAGE_BTN_DISABLED_BG : (active ? COLOR_BG_SELECTED : (hover ? COLOR_PAGE_BTN_HOVER : COLOR_PAGE_BTN_NORMAL));
        graphics.fill(x, y, x + w, y + h, bg);
        int tc = !enabled ? COLOR_TEXT_DIM : (active ? COLOR_WHITE : COLOR_TEXT_LIGHT);
        int labelWidth = fontWidth(label);
        drawString(graphics, label, x + w / 2 - labelWidth / 2, y + 3, tc);
        if (enabled) pageClickAreas.add(new int[]{x, y, w, h, page});
    }

    protected String getCategoryLabel(String category) {
        if (DEFAULT_CATEGORY_KEYS.contains(category)) {
            return Component.translatable("voxlink.category." + category).getString();
        }
        return categoryMap.getOrDefault(category, category);
    }

    protected int getGridY() {
        return isCustomRowVisible() ? GRID_Y_CUSTOM : GRID_Y_DEFAULT;
    }

    protected boolean isCustomRowVisible() {
        if (selectedCategory.equals("other")) return true;
        return !DEFAULT_CATEGORY_KEYS.contains(selectedCategory) && !selectedCategory.equals("all");
    }

    protected int getColumns() {
        return Math.max(2, Math.min(5, this.width / COL_DIVISOR));
    }

    protected int getCardWidth(int cols) {
        int gap = getGap();
        return (this.width - gap * (cols + 1)) / cols;
    }

    protected int getCardHeight() {
        return CARD_H;
    }

    protected int getGap() {
        return Math.max(4, this.width / GAP_DIVISOR);
    }

    protected int getGridStartX(int cols, int cardW, int gap) {
        return (this.width - (cols * cardW + (cols - 1) * gap)) / 2;
    }

    protected String truncate(String s, int maxChars) {
        if (s == null) return "";
        if (maxChars <= 0) return "";
        if (fontWidth(s) <= maxChars) return s;
        for (int i = s.length() - 1; i > 0; i--) {
            String candidate = s.substring(0, i) + "..";
            if (fontWidth(candidate) <= maxChars) {
                return candidate;
            }
        }
        return "..";
    }

    @Override
    public void removed() {
        super.removed();
        this.removed = true;
    }
}
