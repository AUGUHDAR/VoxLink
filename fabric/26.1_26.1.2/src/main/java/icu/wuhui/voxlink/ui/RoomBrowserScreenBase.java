package icu.wuhui.voxlink.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import icu.wuhui.voxlink.VoxLinkConstants;
import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.compat.ViaCompat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

public class RoomBrowserScreenBase extends VoxLinkScreenBase {
   private static final int KEY_ENTER = 257;
   private static final int COLOR_NO_ROOMS_HINT = -10066330;
   private static final int COLOR_CAT_BADGE_BG = 1144206131;
   private static final int COLOR_BG_SELECTED = -586010998;
   private static final int COLOR_BG_HOVER = -581610155;
   private static final int COLOR_BG_NORMAL = -583847117;
   private static final int COLOR_PAGE_BTN_DISABLED_BG = 1149798536;
   private static final int COLOR_PAGE_BTN_HOVER = -580491674;
   private static final int COLOR_PAGE_BTN_NORMAL = -582728636;
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
   private static final int ROW_GAP = 2;
   private static final int LOADER_ROW_Y = 52;
   private static final int CUSTOM_ROW_Y = 72;
   private static final int GRID_GAP = 4;
   private static final int LOADER_BTN_W = 200;
   private static final int GRID_Y_CUSTOM = 94;
   private static final int GRID_Y_DEFAULT = 74;
   private static final int COL_DIVISOR = 160;
   private static final int CARD_H = 48;
   private static final int LOADER_BADGE_Y_TOP = 35;
   private static final int LOADER_BADGE_Y_BOTTOM = 45;
   private static final int LOADER_BADGE_TEXT_Y = 36;
   private static final int GAP_DIVISOR = 80;
   protected final Screen parent;
   protected List<RoomBrowserScreenBase.RoomEntry> allRooms = new ArrayList<>();
   protected List<RoomBrowserScreenBase.RoomEntry> displayedRooms = new ArrayList<>();
   protected String selectedCategory = "all";
   protected String selectedLoader = "";
   protected RoomBrowserScreenBase.SortMode sortMode = RoomBrowserScreenBase.SortMode.PLAYERS_DESC;
   protected int scrollOffset = 0;
   protected EditBox searchField;
   protected Button joinBtn;
   protected int selectedIdx = -1;
   protected String statusMsg = "";
   protected int statusColor = VoxLinkColors.MUTED;
   /** 被"有密码"过滤掉的房间数：仅在 >0 时在状态栏显示提示玩家走"输入房间号"加入。 */
   protected int hiddenPasswordCount = 0;
   protected boolean initialFetchDone = false;
   protected int currentPage = 1;
   protected int totalRooms = 0;
   protected volatile boolean loadingMore = false;
   protected volatile boolean removed = false;
   protected Map<String, String> categoryMap = new LinkedHashMap<>();
   protected boolean categoriesFetched = false;
   protected List<Button> categoryButtons = new ArrayList<>();
   protected List<String> customCatKeys = new ArrayList<>();
   protected int customTagStartIndex = 0;
   protected int customTagShowCount = 10;
   protected List<Button> customTagRowButtons = new ArrayList<>();
   protected Button shuffleCustomBtn;
   protected Button showMoreCustomBtn;
   protected Button loaderFilterBtn;
   protected String savedSearch = "";
   protected EditBox pageInput;
   protected List<int[]> pageClickAreas = new ArrayList<>();
   protected static final Set<String> DEFAULT_CATEGORY_KEYS = Set.of("survival", "creative", "redstone", "pvp", "rpg", "minigame", "social", "other");
   private static final String GAME_VERSION = VoxLinkConstants.GAME_VERSION;
   protected static final int PAGE_SIZE = 20;

   public RoomBrowserScreenBase(Screen parent) {
      super(Component.translatable("voxlink.browser.title"));
      this.parent = parent;
   }

   public void onClose() {
      Minecraft.getInstance().setScreen(this.parent);
   }

   @Override
   protected void init() {
      super.init();
      int w = this.width;
      int pad = Math.max(8, w / 40);
      this.searchField = new EditBox(Minecraft.getInstance().font, pad, 8, w / 3, 18, Component.translatable("voxlink.search"));
      this.searchField.setHint(Component.translatable("voxlink.browser.search_hint"));
      this.searchField.setResponder(q -> {
         this.savedSearch = q;
         this.applyFilter();
      });
      this.searchField.setValue(this.savedSearch);
      this.addRenderableWidget(this.searchField);
      int sortX = w / 3 + pad + 8;
      this.addRenderableWidget(Button.builder(Component.translatable("voxlink.browser.sort", new Object[]{this.sortMode.label}), b -> {
         this.sortMode = RoomBrowserScreenBase.SortMode.values()[(this.sortMode.ordinal() + 1) % RoomBrowserScreenBase.SortMode.values().length];
         b.setMessage(Component.translatable("voxlink.browser.sort", new Object[]{this.sortMode.label}));
         this.applyFilter();
      }).bounds(sortX, 6, w / 5, 20).build());
      this.addRenderableWidget(Button.builder(Component.translatable("voxlink.refresh"), b -> this.fetchRooms()).bounds(w - pad - 60, 6, 60, 20).build());
      this.joinBtn = Button.builder(Component.translatable("voxlink.join_room"), b -> this.joinSelected())
         .bounds(w / 2 - 10, this.height - 24, 100, 20)
         .build();
      this.joinBtn.active = false;
      this.addRenderableWidget(this.joinBtn);
      this.addRenderableWidget(
         Button.builder(Component.translatable("voxlink.back"), b -> Minecraft.getInstance().setScreen(this.parent))
            .bounds(w / 2 - 130, this.height - 24, 100, 20)
            .build()
      );
      this.pageInput = new EditBox(Minecraft.getInstance().font, -100, -100, 32, 14, Component.literal(""));
      this.pageInput.setMaxLength(4);
      this.pageInput.setVisible(false);
      this.pageInput.setResponder(t -> {});
      this.pageInput.setHint(Component.translatable("voxlink.page.input_hint"));
      this.addRenderableWidget(this.pageInput);
      if (!this.initialFetchDone) {
         this.initialFetchDone = true;
         this.fetchCategories();
         this.fetchRooms();
      }

      this.rebuildCategoryButtons();
   }

   private void fetchCategories() {
      VoxLinkMod.getSignalingClient().getCategories().thenAccept(apiResponse -> {
         Minecraft mc = Minecraft.getInstance();
         mc.execute(() -> {
            if (!this.removed) {
               try {
                  if (apiResponse.success && apiResponse.data != null && apiResponse.data.isJsonObject()) {
                     JsonObject obj = apiResponse.data.getAsJsonObject();
                     this.categoryMap.clear();

                     for (String key : obj.keySet()) {
                        this.categoryMap.put(key, obj.get(key).getAsString());
                     }

                     this.categoriesFetched = true;
                     this.rebuildCategoryButtons();
                  }
               } catch (Exception var5) {
               }
            }
         });
      }).exceptionally(e -> null);
   }

   protected void rebuildCategoryButtons() {
      int w = this.width;
      int pad = Math.max(8, w / 40);
      int catY = 32;

      for (Button btn : this.categoryButtons) {
         this.removeWidget(btn);
      }

      this.categoryButtons.clear();

      for (Button btn : this.customTagRowButtons) {
         this.removeWidget(btn);
      }

      this.customTagRowButtons.clear();
      if (this.shuffleCustomBtn != null) {
         this.removeWidget(this.shuffleCustomBtn);
         this.shuffleCustomBtn = null;
      }

      if (this.showMoreCustomBtn != null) {
         this.removeWidget(this.showMoreCustomBtn);
         this.showMoreCustomBtn = null;
      }

      if (this.loaderFilterBtn != null) {
         this.removeWidget(this.loaderFilterBtn);
         this.loaderFilterBtn = null;
      }

      List<String> defaultKeys = new ArrayList<>();
      defaultKeys.add("all");

      for (String key : this.categoryMap.keySet()) {
         if (DEFAULT_CATEGORY_KEYS.contains(key) && !defaultKeys.contains(key)) {
            defaultKeys.add(key);
         }
      }

      int totalDef = defaultKeys.size();
      int defW = Math.max(38, (w - pad * 2 - (totalDef - 1) * 2) / totalDef);
      int defStartX = (w - totalDef * (defW + 2)) / 2;

      for (int i = 0; i < totalDef; i++) {
         String cat = defaultKeys.get(i);
         Component label;
         if ("all".equals(cat)) {
            label = Component.translatable("voxlink.category.all");
         } else if (DEFAULT_CATEGORY_KEYS.contains(cat)) {
            label = Component.translatable("voxlink.category." + cat);
         } else {
            label = Component.literal(this.categoryMap.getOrDefault(cat, cat));
         }

         Button btn = Button.builder(label, b -> {
            this.selectedCategory = cat;
            this.fetchRooms();
            this.rebuildCategoryButtons();
         }).bounds(defStartX + i * (defW + 2), catY, defW, 18).build();
         this.categoryButtons.add(btn);
         this.addRenderableWidget(btn);
      }

      this.loaderFilterBtn = Button.builder(
            Component.translatable(
               "voxlink.browser.loader",
               new Object[]{
                  this.selectedLoader.isEmpty()
                     ? Component.translatable("voxlink.loader.all")
                     : Component.translatable("voxlink.loader." + this.selectedLoader)
               }
            ),
            button -> {
               String[] loaders = new String[]{"", "fabric", "neoforge", "forge"};
               int idx = 0;

               for (int i = 0; i < loaders.length; i++) {
                  if (loaders[i].equals(this.selectedLoader)) {
                     idx = i;
                     break;
                  }
               }

               this.selectedLoader = loaders[(idx + 1) % loaders.length];
               button.setMessage(
                  Component.translatable(
                     "voxlink.browser.loader",
                     new Object[]{
                        this.selectedLoader.isEmpty()
                           ? Component.translatable("voxlink.loader.all")
                           : Component.translatable("voxlink.loader." + this.selectedLoader)
                     }
                  )
               );
               this.fetchRooms();
            }
         )
         .bounds(this.width / 2 - 100, 52, 200, 18)
         .build();
      this.addRenderableWidget(this.loaderFilterBtn);
      this.customCatKeys.clear();

      for (String key : this.categoryMap.keySet()) {
         if (!DEFAULT_CATEGORY_KEYS.contains(key)) {
            this.customCatKeys.add(key);
         }
      }

      Collections.sort(this.customCatKeys);
      if (this.isCustomRowVisible() && !this.customCatKeys.isEmpty()) {
         int customRowY = 72;
         int visibleCount = Math.min(this.customTagShowCount, this.customCatKeys.size() - this.customTagStartIndex);
         if (visibleCount < 0) {
            visibleCount = 0;
         }

         if (visibleCount > 0) {
            int totalItems = visibleCount;
            boolean needShuffle = this.customCatKeys.size() > visibleCount;
            boolean needMore = this.customTagStartIndex + this.customTagShowCount < this.customCatKeys.size();
            if (needShuffle) {
               totalItems++;
            }

            if (needMore) {
               totalItems++;
            }

            int itemW = Math.max(36, (w - pad * 2 - (totalItems - 1) * 2) / totalItems);
            int itemStartX = (w - totalItems * (itemW + 2)) / 2;

            for (int i = 0; i < visibleCount; i++) {
               String cat = this.customCatKeys.get(this.customTagStartIndex + i);
               Component label = Component.literal(this.categoryMap.getOrDefault(cat, cat));
               Button btn = Button.builder(label, b -> {
                  this.selectedCategory = cat;
                  this.fetchRooms();
                  this.rebuildCategoryButtons();
               }).bounds(itemStartX + i * (itemW + 2), customRowY, itemW, 18).build();
               this.customTagRowButtons.add(btn);
               this.addRenderableWidget(btn);
            }

            int btnIdx = visibleCount;
            if (needShuffle) {
               this.shuffleCustomBtn = Button.builder(Component.translatable("voxlink.shuffle"), b -> {
                  int total = this.customCatKeys.size();
                  int step = Math.min(10, total);
                  if (total > step) {
                     this.customTagStartIndex = new java.util.Random().nextInt(total - step + 1);
                  }

                  this.customTagShowCount = step;
                  this.rebuildCategoryButtons();
               }).bounds(itemStartX + btnIdx * (itemW + 2), customRowY, itemW, 18).build();
               this.addRenderableWidget(this.shuffleCustomBtn);
               btnIdx++;
            }

            if (needMore) {
               this.showMoreCustomBtn = Button.builder(Component.translatable("voxlink.show_more"), b -> {
                  this.customTagShowCount += 10;
                  this.rebuildCategoryButtons();
               }).bounds(itemStartX + btnIdx * (itemW + 2), customRowY, itemW, 18).build();
               this.addRenderableWidget(this.showMoreCustomBtn);
            }
         }
      }
   }

   protected void fetchRooms() {
      this.fetchPage(1, true);
   }

   protected void fetchMoreRooms() {
      if (!this.loadingMore) {
         int tp = this.totalPages();
         if (tp <= 0 || this.currentPage < tp) {
            this.fetchPage(this.currentPage + 1, false);
         }
      }
   }

   protected int totalPages() {
      return this.totalRooms > 0 ? (int)Math.ceil(this.totalRooms / 20.0) : 0;
   }

   protected void fetchPage(int page, boolean clear) {
      if (!this.loadingMore) {
         int tp = this.totalPages();
         if (tp <= 0 || page >= 1 && page <= tp) {
            this.loadingMore = true;
            if (clear) {
               this.currentPage = 1;
               this.allRooms.clear();
               this.scrollOffset = 0;
               this.statusMsg = Component.translatable("voxlink.browser.loading").getString();
               this.statusColor = VoxLinkColors.WARNING;
            }

            String category = "all".equals(this.selectedCategory) ? null : this.selectedCategory;
            int finalPage = page;
            VoxLinkMod.getSignalingClient()
               .listRooms(page, 20, category, this.selectedLoader)
               .thenAccept(
                  apiResponse -> {
                     Minecraft mc = Minecraft.getInstance();
                     mc.execute(
                        () -> {
                           if (!this.removed) {
                              this.loadingMore = false;

                              try {
                                 if (!apiResponse.success || apiResponse.data == null) {
                                    if (clear) {
                                       this.statusMsg = ChatFormatting.RED.toString() + Component.translatable("voxlink.browser.load_failed").getString();
                                       this.statusColor = VoxLinkColors.ERROR;
                                    }

                                    return;
                                 }

                                 JsonObject data = apiResponse.data;
                                 this.totalRooms = data.has("total") ? data.get("total").getAsInt() : this.totalRooms;
                                 if (clear) {
                                    this.allRooms.clear();
                                 }

                                 if (data.has("rooms") && data.get("rooms").isJsonArray()) {
                                    Set<String> existingCodes = new HashSet<>();

                                    for (RoomBrowserScreenBase.RoomEntry existing : this.allRooms) {
                                       existingCodes.add(existing.code);
                                    }

                                    for (JsonElement e : data.getAsJsonArray("rooms")) {
                                       JsonObject r = e.getAsJsonObject();
                                       String code = r.has("code") ? r.get("code").getAsString() : "";
                                       if (!existingCodes.contains(code)) {
                                          String roomClientType = r.has("clientType") ? r.get("clientType").getAsString() : "mod";
                                          if ("mod".equals(roomClientType)) {
                                             this.allRooms
                                                .add(
                                                   new RoomBrowserScreenBase.RoomEntry(
                                                      code,
                                                      r.has("name") ? r.get("name").getAsString() : Component.translatable("voxlink.unknown").getString(),
                                                      r.has("category") ? r.get("category").getAsString() : "other",
                                                      r.has("loader") ? r.get("loader").getAsString() : "unknown",
                                                      r.has("currentPlayers") ? r.get("currentPlayers").getAsInt() : 0,
                                                      r.has("maxPlayers") ? r.get("maxPlayers").getAsInt() : 20,
                                                      r.has("hasPassword") && r.get("hasPassword").getAsBoolean(),
                                                      r.has("natType") ? r.get("natType").getAsString() : "unknown",
                                                      r.has("protocolVersion") ? r.get("protocolVersion").getAsInt() : 0,
                                                      r.has("gameVersion") ? r.get("gameVersion").getAsString() : ""
                                                   )
                                                );
                                             existingCodes.add(code);
                                          }
                                       }
                                    }
                                 }

                                 this.currentPage = finalPage;
                                 this.fetchP2PDetails();
                              } catch (Exception ex) {
                                 this.statusMsg = Component.translatable("voxlink.browser.load_rooms_failed").getString();
                                 this.statusColor = VoxLinkColors.ERROR;
                              }
                           }
                        }
                     );
                  }
               )
               .exceptionally(e -> {
                  Minecraft.getInstance().execute(() -> {
                     this.loadingMore = false;
                     this.statusMsg = Component.translatable("voxlink.error.network_error").getString();
                     this.statusColor = VoxLinkColors.ERROR;
                  });
                  return null;
               });
         }
      }
   }

   protected void fetchP2PDetails() {
      this.applyFilter();
      this.statusMsg = this.allRooms.size() + " " + Component.translatable("voxlink.browser.rooms_count").getString();
      this.statusColor = VoxLinkColors.SUCCESS;
   }

   protected void applyFilter() {
      String query = this.searchField.getValue().trim().toLowerCase();
      int myProtocol = ViaCompat.isViaLoaded() ? ViaCompat.getServerProtocolVersion() : 0;
      this.displayedRooms = this.allRooms
         .stream()
         .filter(r -> !r.hasPassword)
         .filter(
            r -> (this.selectedCategory.equals("all") || r.category.equals(this.selectedCategory))
               && (this.selectedLoader.isEmpty() || this.selectedLoader.equals(r.loader))
         )
         .filter(r -> query.isEmpty() || r.name.toLowerCase().contains(query) || r.code.toLowerCase().contains(query))
         .filter(r -> this.sortMode != RoomBrowserScreenBase.SortMode.VERSION_SAME || GAME_VERSION.equals(r.gameVersion))
         .sorted(this.getComparator(myProtocol))
         .toList();
      // 统计被按"有密码"过滤掉的房间：仅当确实过滤掉了且当前可见列表为空/几乎为空时，给玩家一条状态行提示走"输入房间号"加入
      long hiddenByPassword = this.allRooms.stream().filter(r -> r.hasPassword).count();
      this.hiddenPasswordCount = hiddenByPassword > 0L ? (int)hiddenByPassword : 0;
      this.scrollOffset = 0;
      this.selectedIdx = -1;
      if (this.joinBtn != null) {
         this.joinBtn.active = false;
      }
   }

   protected Comparator<RoomBrowserScreenBase.RoomEntry> getComparator(int myProtocol) {
      return switch (this.sortMode) {
         case PLAYERS_DESC -> Comparator.<RoomBrowserScreenBase.RoomEntry>comparingInt(r -> r.players).reversed();
         case PLAYERS_ASC -> Comparator.comparingInt(r -> r.players);
         case VERSION_SAME -> Comparator.<RoomBrowserScreenBase.RoomEntry>comparingInt(r -> GAME_VERSION.equals(r.gameVersion) ? 0 : 1)
            .thenComparingInt(r -> -r.players);
         case NAME_ASC -> Comparator.comparing(r -> r.name.toLowerCase());
         case NAME_DESC -> Comparator.<RoomBrowserScreenBase.RoomEntry, String>comparing(r -> r.name.toLowerCase()).reversed();
      };
   }

   protected void joinSelected() {
      if (this.selectedIdx >= 0 && this.selectedIdx < this.displayedRooms.size()) {
         RoomBrowserScreenBase.RoomEntry room = this.displayedRooms.get(this.selectedIdx);
         Minecraft.getInstance().setScreen(new AttemptingJoinScreen(this, room.code, null));
      }
   }

   protected boolean handleClick(double mouseX, double mouseY, int button) {
      for (int[] a : this.pageClickAreas) {
         if (mouseX >= a[0] && mouseX < a[0] + a[2] && mouseY >= a[1] && mouseY < a[1] + a[3]) {
            int page = a[4];
            if (page == -1) {
               this.fetchPage(Math.max(1, this.currentPage - 1), true);
            } else if (page == -3) {
               this.fetchPage(Math.min(this.totalPages(), this.currentPage + 1), true);
            } else if (page == -2) {
               try {
                  int p = Integer.parseInt(this.pageInput.getValue().trim());
                  this.fetchPage(p, true);
               } catch (Exception var17) {
               }
            } else if (page > 0) {
               this.fetchPage(page, true);
            }

            return true;
         }
      }

      int cols = this.getColumns();
      int cardW = this.getCardWidth(cols);
      int cardH = this.getCardHeight();
      int gap = this.getGap();
      int gridX = this.getGridStartX(cols, cardW, gap);
      int gridY = this.getGridY();

      for (int i = 0; i < this.displayedRooms.size(); i++) {
         int col = i % cols;
         int row = i / cols;
         int x = gridX + col * (cardW + gap);
         int y = gridY + row * (cardH + gap) - this.scrollOffset;
         if (y >= gridY - cardH && y + cardH <= this.height - 52 && mouseX >= x && mouseX < x + cardW && mouseY >= y && mouseY < y + cardH) {
            this.selectedIdx = i;
            this.joinBtn.active = true;
            return true;
         }
      }

      return false;
   }

   protected boolean handleKeyPressed(int keyCode, int scanCode, int modifiers) {
      if (keyCode == 257 && this.pageInput != null && this.pageInput.isVisible() && this.pageInput.isFocused()) {
         try {
            int p = Integer.parseInt(this.pageInput.getValue().trim());
            this.fetchPage(p, true);
            return true;
         } catch (Exception var5) {
         }
      }

      return false;
   }

   protected boolean handleMouseScrolled(double scrollY) {
      int cols = this.getColumns();
      int totalRows = (this.displayedRooms.size() + cols - 1) / cols;
      int cardH = this.getCardHeight();
      int gap = this.getGap();
      int maxScroll = Math.max(0, totalRows * (cardH + gap) - (this.height - 36 - this.getGridY()));
      int newOffset = Math.max(0, Math.min(maxScroll, this.scrollOffset - (int)scrollY * 20));
      this.scrollOffset = newOffset;
      if (newOffset >= maxScroll - 50 && this.allRooms.size() < this.totalRooms) {
         this.fetchMoreRooms();
      }

      return true;
   }

   public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
      this.updatePageInput();
      super.extractRenderState(graphics, mouseX, mouseY, partialTick);
      int cols = this.getColumns();
      int cardW = this.getCardWidth(cols);
      int cardH = this.getCardHeight();
      int gap = this.getGap();
      int gridX = this.getGridStartX(cols, cardW, gap);
      int gridY = this.getGridY();
      int bottom = this.height - 52;

      for (int i = 0; i < this.displayedRooms.size(); i++) {
         int col = i % cols;
         int row = i / cols;
         int x = gridX + col * (cardW + gap);
         int y = gridY + row * (cardH + gap) - this.scrollOffset;
         // 卡片必须整体落在网格与分页条/按钮区（height-52）之间，任何滚动位置都不越界压控件

         if (y >= gridY - cardH && y + cardH <= bottom) {
            RoomBrowserScreenBase.RoomEntry r = this.displayedRooms.get(i);
            boolean sel = i == this.selectedIdx;
            boolean hover = mouseX >= x && mouseX < x + cardW && mouseY >= y && mouseY < y + cardH;
            int bg = sel ? COLOR_BG_SELECTED : (hover ? COLOR_BG_HOVER : COLOR_BG_NORMAL);
            graphics.fill(x, y, x + cardW, y + cardH, bg);
            String roomName = r.name;
            if ("name_not_approved".equals(roomName)) {
               roomName = Component.translatable("voxlink.room.name_not_approved").getString();
            }

            int textX = x + 6;
            int textY = y + 5;
            this.drawString(graphics, this.truncate(roomName, cardW / 6), textX, textY, VoxLinkColors.WHITE);
            this.drawString(graphics, ChatFormatting.GRAY.toString() + r.code, textX, textY + 11, VoxLinkColors.MUTED);
            this.drawString(graphics, ChatFormatting.WHITE.toString() + r.players + "/" + r.maxPlayers, textX, textY + 22, VoxLinkColors.TEXT_LIGHT);
            String catLabel = this.getCategoryLabel(r.category);
            int catW = this.fontWidth(catLabel) + 4;
            graphics.fill(x + cardW - catW - 3, y + 3, x + cardW - 3, y + 13, COLOR_CAT_BADGE_BG);
            this.drawString(graphics, ChatFormatting.GRAY.toString() + catLabel, x + cardW - catW - 1, y + 4, VoxLinkColors.CAT_BADGE_TEXT);
            String loaderKey = r.loader != null && !r.loader.isEmpty() ? r.loader : "unknown";
            String loaderLabel = Component.translatable("voxlink.loader." + loaderKey).getString();
            int loaderW = this.fontWidth(loaderLabel) + 4;
            // 小卡片（cardH<48）跳过 loader 角标以免溢出
            if (cardH >= 48) {
               graphics.fill(x + cardW - loaderW - 3, y + 35, x + cardW - 3, y + 45, COLOR_CAT_BADGE_BG);
               this.drawString(graphics, ChatFormatting.GRAY.toString() + loaderLabel, x + cardW - loaderW - 1, y + 36, VoxLinkColors.CAT_BADGE_TEXT);
            }
         }
      }

      if (this.displayedRooms.isEmpty()) {
         this.drawCenteredString(graphics, Component.translatable("voxlink.browser.no_rooms").getString(), this.width / 2, gridY + 30, VoxLinkColors.GRAY);
         this.drawCenteredString(graphics, Component.translatable("voxlink.browser.no_rooms_hint").getString(), this.width / 2, gridY + 44, COLOR_NO_ROOMS_HINT);
      }

      if (!this.statusMsg.isEmpty()) {
         String clippedStatus = this.statusMsg;
         int maxStatusWidth = this.width - 20;
         if (this.fontWidth(this.statusMsg) > maxStatusWidth) {
            while (this.fontWidth(clippedStatus + "...") > maxStatusWidth && clippedStatus.length() > 0) {
               clippedStatus = clippedStatus.substring(0, clippedStatus.length() - 1);
            }

            clippedStatus = clippedStatus + "...";
         }

         this.drawCenteredString(graphics, clippedStatus, this.width / 2, this.height - 64, this.statusColor);
      }

      // 当过滤后排除了密码房（且显示列表为空或只显示非密码房时），给玩家一条提示：可通过"输入房间号"加入
      if (this.hiddenPasswordCount > 0 && this.displayedRooms.isEmpty() && this.height >= 360) {
         String hiddenHint = Component.translatable("voxlink.browser.password_rooms_hidden", new Object[]{this.hiddenPasswordCount}).getString();
         int maxHintWidth = this.width - 20;
         if (this.fontWidth(hiddenHint) > maxHintWidth) {
            String clipped = hiddenHint;
            while (this.fontWidth(clipped + "...") > maxHintWidth && clipped.length() > 0) {
               clipped = clipped.substring(0, clipped.length() - 1);
            }

            hiddenHint = clipped + "...";
         }

         this.drawCenteredString(graphics, hiddenHint, this.width / 2, this.height - 52, VoxLinkColors.INFO);
      }

      this.renderPagination(graphics, mouseX, mouseY);
   }

   protected void renderPagination(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
      this.pageClickAreas.clear();
      int tp = this.totalPages();
      if (tp > 1) {
         int py = this.height - 46;
         int bw = 16;
         int bh = 14;
         int gp = 2;
         int iw = 32;
         boolean showInput = tp > 6;
         int totalW = showInput ? (bw + gp) * 2 + bw * 3 + gp * 3 + iw + gp + bw + gp + bw : (bw + gp) * 2 + bw * tp + gp * (tp - 1);
         int x = (this.width - totalW) / 2;
         this.drawPageBtn(
            graphics, mouseX, mouseY, x, py, bw, bh, Component.translatable("voxlink.page.prev").getString(), this.currentPage > 1, -1, this.currentPage
         );
         x += bw + gp;
         if (showInput) {
            for (int i = 1; i <= 3; i++) {
               this.drawPageBtn(graphics, mouseX, mouseY, x, py, bw, bh, String.valueOf(i), true, i, this.currentPage);
               x += bw + gp;
            }

            x += iw + gp;
            this.drawPageBtn(graphics, mouseX, mouseY, x, py, bw, bh, Component.translatable("voxlink.page.jump").getString(), true, -2, this.currentPage);
            x += bw + gp;
            this.drawPageBtn(graphics, mouseX, mouseY, x, py, bw, bh, String.valueOf(tp), true, tp, this.currentPage);
            x += bw + gp;
         } else {
            for (int i = 1; i <= tp; i++) {
               this.drawPageBtn(graphics, mouseX, mouseY, x, py, bw, bh, String.valueOf(i), true, i, this.currentPage);
               x += bw + gp;
            }
         }

         this.drawPageBtn(
            graphics, mouseX, mouseY, x, py, bw, bh, Component.translatable("voxlink.page.next").getString(), this.currentPage < tp, -3, this.currentPage
         );
      }
   }

   protected void updatePageInput() {
      int tp = this.totalPages();
      if (tp > 6) {
         int py = this.height - 46;
         int bw = 16;
         int gp = 2;
         int iw = 32;
         int totalW = (bw + gp) * 2 + bw * 3 + gp * 3 + iw + gp + bw + gp + bw;
         int startX = (this.width - totalW) / 2;
         int inputX = startX + bw + gp + (bw + gp) * 3;
         this.pageInput.setPosition(inputX, py);
         this.pageInput.setVisible(true);
      } else {
         this.pageInput.setVisible(false);
      }
   }

   protected void drawPageBtn(
      GuiGraphicsExtractor graphics, int mx, int my, int x, int y, int w, int h, String label, boolean enabled, int page, int currentPage
   ) {
      boolean active = enabled && page == currentPage;
      boolean hover = enabled && mx >= x && mx < x + w && my >= y && my < y + h;
      int bg = !enabled ? COLOR_PAGE_BTN_DISABLED_BG : (active ? COLOR_BG_SELECTED : (hover ? COLOR_PAGE_BTN_HOVER : COLOR_PAGE_BTN_NORMAL));
      graphics.fill(x, y, x + w, y + h, bg);
      int tc = !enabled ? VoxLinkColors.TEXT_DIM : (active ? VoxLinkColors.WHITE : VoxLinkColors.TEXT_LIGHT);
      int labelWidth = this.fontWidth(label);
      this.drawString(graphics, label, x + w / 2 - labelWidth / 2, y + 3, tc);
      if (enabled) {
         this.pageClickAreas.add(new int[]{x, y, w, h, page});
      }
   }

   protected String getCategoryLabel(String category) {
      return DEFAULT_CATEGORY_KEYS.contains(category)
         ? Component.translatable("voxlink.category." + category).getString()
         : this.categoryMap.getOrDefault(category, category);
   }

   protected int getGridY() {
      return this.isCustomRowVisible() ? 94 : 74;
   }

   protected boolean isCustomRowVisible() {
      return this.selectedCategory.equals("other") ? true : !DEFAULT_CATEGORY_KEYS.contains(this.selectedCategory) && !this.selectedCategory.equals("all");
   }

   protected int getColumns() {
      return Math.max(2, Math.min(5, this.width / 160));
   }

   protected int getCardWidth(int cols) {
      int gap = this.getGap();
      return (this.width - gap * (cols + 1)) / cols;
   }

   protected int getCardHeight() {
      // 小高度屏幕：卡片缩为 36 以让出底部空间给分页/状态/提示/按钮
      return this.height < 360 ? 36 : 48;
   }

   protected int getGap() {
      return Math.max(4, this.width / 80);
   }

   protected int getGridStartX(int cols, int cardW, int gap) {
      return (this.width - (cols * cardW + (cols - 1) * gap)) / 2;
   }

   protected String truncate(String s, int maxChars) {
      if (s == null) {
         return "";
      }

      if (maxChars <= 0) {
         return "";
      }

      if (this.fontWidth(s) <= maxChars) {
         return s;
      }

      for (int i = s.length() - 1; i > 0; i--) {
         String candidate = s.substring(0, i) + "..";
         if (this.fontWidth(candidate) <= maxChars) {
            return candidate;
         }
      }

      return "..";
   }

   public boolean mouseClicked(MouseButtonEvent event, boolean processed) {
      if (processed) {
         return super.mouseClicked(event, processed);
      } else {
         return this.handleClick(event.x(), event.y(), event.button()) ? true : super.mouseClicked(event, processed);
      }
   }

   public boolean keyPressed(KeyEvent event) {
      return this.handleKeyPressed(event.key(), event.scancode(), event.modifiers()) ? true : super.keyPressed(event);
   }

   public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
      return this.handleMouseScrolled(scrollY) ? true : super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
   }

   public void removed() {
      super.removed();
      this.removed = true;
   }

   protected record RoomEntry(
      String code,
      String name,
      String category,
      String loader,
      int players,
      int maxPlayers,
      boolean hasPassword,
      String natType,
      int protocolVersion,
      String gameVersion
   ) {
   }

   protected enum SortMode {
      PLAYERS_DESC(Component.translatable("voxlink.sort.players_desc")),
      PLAYERS_ASC(Component.translatable("voxlink.sort.players_asc")),
      VERSION_SAME(Component.translatable("voxlink.sort.version_same")),
      NAME_ASC(Component.translatable("voxlink.sort.name_asc")),
      NAME_DESC(Component.translatable("voxlink.sort.name_desc"));

      final Component label;

      SortMode(Component label) {
         this.label = label;
      }
   }
}
