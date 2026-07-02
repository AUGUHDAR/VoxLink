package icu.wuhui.voxlink.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.compat.ViaCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class RoomBrowserScreen extends Screen {
    private final Screen parent;

    private List<RoomEntry> allRooms = new ArrayList<>();
    private List<RoomEntry> displayedRooms = new ArrayList<>();
    private String selectedCategory = "all";
    private SortMode sortMode = SortMode.PLAYERS_DESC;
    private int scrollOffset = 0;
    private EditBox searchField;
    private Button joinBtn;
    private int selectedIdx = -1;
    private String statusMsg = "";
    private int statusColor = 0xFFAAAAAA;
    private boolean initialFetchDone = false;
    private int currentPage = 1;
    private int totalRooms = 0;
    private volatile boolean loadingMore = false;
    private volatile boolean removed = false;
    private java.util.Map<String, String> categoryMap = new java.util.LinkedHashMap<>();
    private boolean categoriesFetched = false;
    private java.util.List<Button> categoryButtons = new java.util.ArrayList<>();
    private java.util.List<String> customCatKeys = new java.util.ArrayList<>();
    private int customTagStartIndex = 0;
    private int customTagShowCount = 10;
    private java.util.List<Button> customTagRowButtons = new java.util.ArrayList<>();
    private Button shuffleCustomBtn;
    private Button showMoreCustomBtn;
    private String savedSearch = "";
    private EditBox pageInput;
    private java.util.List<int[]> pageClickAreas = new java.util.ArrayList<>();

    private static final java.util.Set<String> DEFAULT_CATEGORY_KEYS = java.util.Set.of(
            "survival", "creative", "redstone", "pvp", "rpg", "minigame", "social", "other"
    );

    private enum SortMode {
        PLAYERS_DESC(Component.translatable("voxlink.sort.players_desc")), PLAYERS_ASC(Component.translatable("voxlink.sort.players_asc")),
        VERSION_SAME(Component.translatable("voxlink.sort.version_same")), NAME_ASC(Component.translatable("voxlink.sort.name_asc")), NAME_DESC(Component.translatable("voxlink.sort.name_desc"));
        final Component label;
        SortMode(Component label) { this.label = label; }
    }

    private record RoomEntry(String code, String name, String category, int players, int maxPlayers,
                             boolean hasPassword, String natType, int protocolVersion) {}

    public RoomBrowserScreen(Screen parent) {
        super(Component.translatable("voxlink.browser.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        this.clearWidgets();

        int w = this.width;
        int pad = Math.max(8, w / 40);

        searchField = new EditBox(Minecraft.getInstance().font, pad, 8, w / 3, 18, Component.translatable("voxlink.search"));
        searchField.setHint(Component.translatable("voxlink.browser.search_hint"));
        searchField.setResponder(q -> { savedSearch = q; applyFilter(); });
        searchField.setValue(savedSearch);
        this.addRenderableWidget(searchField);

        int sortX = w / 3 + pad + 8;
        this.addRenderableWidget(Button.builder(Component.translatable("voxlink.browser.sort", sortMode.label), b -> {
            sortMode = SortMode.values()[(sortMode.ordinal() + 1) % SortMode.values().length];
            b.setMessage(Component.translatable("voxlink.browser.sort", sortMode.label));
            applyFilter();
        }).bounds(sortX, 6, w / 5, 20).build());

        this.addRenderableWidget(Button.builder(Component.translatable("voxlink.refresh"), b -> fetchRooms())
                .bounds(w - pad - 60, 6, 60, 20).build());

        joinBtn = Button.builder(Component.translatable("voxlink.join_room"), b -> joinSelected())
                .bounds(w / 2 - 10, this.height - 24, 100, 20).build();
        joinBtn.active = false;
        this.addRenderableWidget(joinBtn);

        this.addRenderableWidget(Button.builder(Component.translatable("voxlink.back"), b ->
                Minecraft.getInstance().setScreen(parent))
                .bounds(w / 2 - 130, this.height - 24, 100, 20).build());

        pageInput = new EditBox(Minecraft.getInstance().font, -100, -100, 32, 14, Component.literal(""));
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

    private void rebuildCategoryButtons() {
        int w = this.width;
        int pad = Math.max(8, w / 40);
        int catY = 32;

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
        int defW = Math.max(38, (w - pad * 2 - (totalDef - 1) * 2) / totalDef);
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
            }).bounds(defStartX + i * (defW + 2), catY, defW, 18).build();
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
                    }).bounds(itemStartX + i * (itemW + 2), customRowY, itemW, 18).build();
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
                    }).bounds(itemStartX + btnIdx * (itemW + 2), customRowY, itemW, 18).build();
                    this.addRenderableWidget(shuffleCustomBtn);
                    btnIdx++;
                }

                if (needMore) {
                    showMoreCustomBtn = Button.builder(Component.translatable("voxlink.show_more"), b -> {
                        customTagShowCount += 10;
                        rebuildCategoryButtons();
                    }).bounds(itemStartX + btnIdx * (itemW + 2), customRowY, itemW, 18).build();
                    this.addRenderableWidget(showMoreCustomBtn);
                }
            }
        }
    }

    private static final int PAGE_SIZE = 20;

    private void fetchRooms() {
        fetchPage(1, true);
    }

    private void fetchMoreRooms() {
        if (loadingMore) return;
        int tp = totalPages();
        if (tp > 0 && currentPage >= tp) return;
        fetchPage(currentPage + 1, false);
    }

    private int totalPages() {
        return totalRooms > 0 ? (int) Math.ceil(totalRooms / (double) PAGE_SIZE) : 0;
    }

    private void fetchPage(int page, boolean clear) {
        if (loadingMore) return;
        int tp = totalPages();
        if (tp > 0 && (page < 1 || page > tp)) return;
        loadingMore = true;
        if (clear) {
            currentPage = 1;
            allRooms.clear();
            scrollOffset = 0;
            statusMsg = Component.translatable("voxlink.browser.loading").getString();
            statusColor = 0xFFFFFF55;
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
                                    statusMsg = "\u00a7c" + Component.translatable("voxlink.browser.load_failed").getString();
                                    statusColor = 0xFFFF5555;
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
                                    allRooms.add(new RoomEntry(
                                            code,
                                            r.has("name") ? r.get("name").getAsString() : Component.translatable("voxlink.unknown").getString(),
                                            r.has("category") ? r.get("category").getAsString() : "other",
                                            r.has("currentPlayers") ? r.get("currentPlayers").getAsInt() : 0,
                                            r.has("maxPlayers") ? r.get("maxPlayers").getAsInt() : 20,
                                            r.has("hasPassword") && r.get("hasPassword").getAsBoolean(),
                                            r.has("natType") ? r.get("natType").getAsString() : "unknown",
                                            r.has("protocolVersion") ? r.get("protocolVersion").getAsInt() : 0
                                    ));
                                    existingCodes.add(code);
                                }
                            }
                            currentPage = finalPage;
                            fetchP2PDetails();
                        } catch (Exception ex) {
                            statusMsg = Component.translatable("voxlink.browser.load_rooms_failed").getString();
                            statusColor = 0xFFFF5555;
                        }
                    });
                })
                .exceptionally(e -> {
                    Minecraft.getInstance().execute(() -> {
                        loadingMore = false;
                        statusMsg = Component.translatable("voxlink.error.network_error").getString();
                        statusColor = 0xFFFF5555;
                    });
                    return null;
                });
    }

    private void fetchP2PDetails() {
        applyFilter();
        statusMsg = allRooms.size() + " " + Component.translatable("voxlink.browser.rooms_count").getString();
        statusColor = 0xFF55FF55;
    }

    private void applyFilter() {
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

    private Comparator<RoomEntry> getComparator(int myProtocol) {
        return switch (sortMode) {
            case PLAYERS_DESC -> Comparator.comparingInt((RoomEntry r) -> r.players).reversed();
            case PLAYERS_ASC -> Comparator.comparingInt(r -> r.players);
            case VERSION_SAME -> Comparator.comparingInt((RoomEntry r) -> r.protocolVersion == myProtocol ? 0 : 1)
                    .thenComparingInt(r -> -r.players);
            case NAME_ASC -> Comparator.comparing(r -> r.name.toLowerCase());
            case NAME_DESC -> Comparator.comparing((RoomEntry r) -> r.name.toLowerCase()).reversed();
        };
    }

    private void joinSelected() {
        if (selectedIdx < 0 || selectedIdx >= displayedRooms.size()) return;
        RoomEntry room = displayedRooms.get(selectedIdx);
        Minecraft.getInstance().setScreen(new AttemptingJoinScreen(this, room.code, null));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean processed) {
        if (processed) return super.mouseClicked(event, processed);
        double mouseX = event.x();
        double mouseY = event.y();
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
        int button = event.button();
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
        return super.mouseClicked(event, processed);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (event.key() == 257 && pageInput != null && pageInput.isVisible() && pageInput.isFocused()) {
            try {
                int p = Integer.parseInt(pageInput.getValue().trim());
                fetchPage(p, true);
                return true;
            } catch (Exception ignored) {}
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int cols = getColumns();
        int totalRows = (displayedRooms.size() + cols - 1) / cols;
        int cardH = getCardHeight();
        int gap = getGap();
        int maxScroll = Math.max(0, totalRows * (cardH + gap) - (this.height - 36 - getGridY()));
        int newOffset = Math.max(0, Math.min(maxScroll, scrollOffset - (int) scrollY * 20));
        scrollOffset = newOffset;
        if (newOffset >= maxScroll - 50 && allRooms.size() < totalRooms) {
            fetchMoreRooms();
        }
        return true;
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
        int bottom = this.height - 52;

        for (int i = 0; i < displayedRooms.size(); i++) {
            int col = i % cols;
            int row = i / cols;
            int x = gridX + col * (cardW + gap);
            int y = gridY + row * (cardH + gap) - scrollOffset;
            if (y < gridY - cardH || y >= bottom) continue;

            RoomEntry r = displayedRooms.get(i);
            boolean sel = i == selectedIdx;
            boolean hover = mouseX >= x && mouseX < x + cardW && mouseY >= y && mouseY < y + cardH;

            int bg = sel ? 0xDD122E8A : (hover ? 0xDD555555 : 0xDD333333);
            graphics.fill(x, y, x + cardW, y + cardH, bg);

            String roomName = r.name;
            if ("name_not_approved".equals(roomName)) {
                roomName = Component.translatable("voxlink.room.name_not_approved").getString();
            }

            int textX = x + 6;
            int textY = y + 5;

            graphics.drawString(Minecraft.getInstance().font, truncate(roomName, cardW / 6), textX, textY, 0xFFFFFFFF);
            graphics.drawString(Minecraft.getInstance().font, "\u00a77" + r.code, textX, textY + 11, 0xFFAAAAAA);
            graphics.drawString(Minecraft.getInstance().font, "\u00a7f" + r.players + "/" + r.maxPlayers, textX, textY + 22, 0xFFCCCCCC);

            String catLabel = getCategoryLabel(r.category);
            int catW = Minecraft.getInstance().font.width(catLabel) + 4;
            graphics.fill(x + cardW - catW - 3, y + 3, x + cardW - 3, y + 13, 0x44333333);
            graphics.drawString(Minecraft.getInstance().font, "\u00a77" + catLabel, x + cardW - catW - 1, y + 4, 0xFF999999);
        }

        if (displayedRooms.isEmpty()) {
            graphics.drawCenteredString(Minecraft.getInstance().font, Component.translatable("voxlink.browser.no_rooms").getString(), this.width / 2, gridY + 30, 0xFF888888);
            graphics.drawCenteredString(Minecraft.getInstance().font, Component.translatable("voxlink.browser.no_rooms_hint").getString(), this.width / 2, gridY + 44, 0xFF666666);
        }

        if (!statusMsg.isEmpty()) {
            String clippedStatus = statusMsg;
            int maxStatusWidth = this.width - 20;
            if (Minecraft.getInstance().font.width(statusMsg) > maxStatusWidth) {
                while (Minecraft.getInstance().font.width(clippedStatus + "...") > maxStatusWidth && clippedStatus.length() > 0) {
                    clippedStatus = clippedStatus.substring(0, clippedStatus.length() - 1);
                }
                clippedStatus = clippedStatus + "...";
            }
            graphics.drawCenteredString(Minecraft.getInstance().font, clippedStatus, this.width / 2, this.height - 64, statusColor);
        }

        renderPagination(graphics, mouseX, mouseY);
    }

    private void renderPagination(GuiGraphics graphics, int mouseX, int mouseY) {
        pageClickAreas.clear();
        int tp = totalPages();
        if (tp <= 1) return;
        int py = this.height - 46;
        int bw = 16, bh = 14, gp = 2, iw = 32;
        boolean showInput = tp > 6;
        int totalW = showInput ? (bw + gp) * 2 + bw * 3 + gp * 3 + iw + gp + bw + gp + bw
                               : (bw + gp) * 2 + bw * tp + gp * (tp - 1);
        int x = (this.width - totalW) / 2;
        var font = Minecraft.getInstance().font;
        drawPageBtn(graphics, font, mouseX, mouseY, x, py, bw, bh, Component.translatable("voxlink.page.prev").getString(), currentPage > 1, -1, currentPage);
        x += bw + gp;
        if (showInput) {
            for (int i = 1; i <= 3; i++) {
                drawPageBtn(graphics, font, mouseX, mouseY, x, py, bw, bh, String.valueOf(i), true, i, currentPage);
                x += bw + gp;
            }
            x += iw + gp;
            drawPageBtn(graphics, font, mouseX, mouseY, x, py, bw, bh, Component.translatable("voxlink.page.jump").getString(), true, -2, currentPage);
            x += bw + gp;
            drawPageBtn(graphics, font, mouseX, mouseY, x, py, bw, bh, String.valueOf(tp), true, tp, currentPage);
            x += bw + gp;
        } else {
            for (int i = 1; i <= tp; i++) {
                drawPageBtn(graphics, font, mouseX, mouseY, x, py, bw, bh, String.valueOf(i), true, i, currentPage);
                x += bw + gp;
            }
        }
        drawPageBtn(graphics, font, mouseX, mouseY, x, py, bw, bh, Component.translatable("voxlink.page.next").getString(), currentPage < tp, -3, currentPage);
    }

    private void updatePageInput() {
        int tp = totalPages();
        if (tp > 6) {
            int py = this.height - 46;
            int bw = 16, gp = 2, iw = 32;
            int totalW = (bw + gp) * 2 + bw * 3 + gp * 3 + iw + gp + bw + gp + bw;
            int startX = (this.width - totalW) / 2;
            int inputX = startX + bw + gp + (bw + gp) * 3;
            pageInput.setPosition(inputX, py);
            pageInput.setVisible(true);
        } else {
            pageInput.setVisible(false);
        }
    }

    private void drawPageBtn(GuiGraphics graphics, net.minecraft.client.gui.Font font, int mx, int my, int x, int y, int w, int h, String label, boolean enabled, int page, int currentPage) {
        boolean active = enabled && page == currentPage;
        boolean hover = enabled && mx >= x && mx < x + w && my >= y && my < y + h;
        int bg = !enabled ? 0x44888888 : (active ? 0xDD122E8A : (hover ? 0xDD666666 : 0xDD444444));
        graphics.fill(x, y, x + w, y + h, bg);
        int tc = !enabled ? 0xFF888888 : (active ? 0xFFFFFFFF : 0xFFCCCCCC);
        graphics.drawCenteredString(font, label, x + w / 2, y + 3, tc);
        if (enabled) pageClickAreas.add(new int[]{x, y, w, h, page});
    }

    private String getCategoryLabel(String category) {
        if (DEFAULT_CATEGORY_KEYS.contains(category)) {
            return Component.translatable("voxlink.category." + category).getString();
        }
        return categoryMap.getOrDefault(category, category);
    }

    private int getGridY() {
        return isCustomRowVisible() ? 74 : 54;
    }

    private boolean isCustomRowVisible() {
        if (selectedCategory.equals("other")) return true;
        return !DEFAULT_CATEGORY_KEYS.contains(selectedCategory) && !selectedCategory.equals("all");
    }

    private int getColumns() {
        return Math.max(2, Math.min(5, this.width / 160));
    }

    private int getCardWidth(int cols) {
        int gap = getGap();
        return (this.width - gap * (cols + 1)) / cols;
    }

    private int getCardHeight() {
        return 48;
    }

    private int getGap() {
        return Math.max(4, this.width / 80);
    }

    private int getGridStartX(int cols, int cardW, int gap) {
        return (this.width - (cols * cardW + (cols - 1) * gap)) / 2;
    }

    private String truncate(String s, int maxChars) {
        if (s == null) return "";
        if (maxChars <= 0) return "";
        if (this.font.width(s) <= maxChars) return s;
        for (int i = s.length() - 1; i > 0; i--) {
            String candidate = s.substring(0, i) + "..";
            if (this.font.width(candidate) <= maxChars) {
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
