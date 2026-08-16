package icu.wuhui.voxlink.ui;

import com.google.gson.JsonObject;
import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.room.RoomInfo;
import icu.wuhui.voxlink.terracotta.TerracottaManager;
import io.netty.channel.ChannelFuture;
import io.netty.channel.local.LocalServerChannel;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerConnectionListener;
import net.minecraft.world.level.GameType;
import net.minecraft.client.gui.GuiGraphics;

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
   private volatile long createStartTime = 0L;
   private volatile boolean removed = false;
   private boolean visible = true;
   private boolean visibleBeforePassword = true;
   private CreateRoomScreen.AuthType authType = CreateRoomScreen.AuthType.OFFLINE;
   private boolean guestOp = false;
   private String gameType = "survival";
   private boolean hostOp = false;
   private Button guestOpButton;
   private Button gameTypeButton;
   private Button hostOpButton;
   private String selectedCategory = "other";
   private Map<String, String> categoryMap = new LinkedHashMap<>();
   private List<Button> categoryButtons = new ArrayList<>();
   private boolean showCustomInput = false;
   private volatile MinecraftServer publishedServer;
   private volatile boolean creating = false;
   private volatile boolean cancelled = false;
   private boolean categoriesFetched = false;
   private String savedName = "";
   private String savedPassword = "";
   private String savedMaxPlayers = String.valueOf(20);
   private final List<int[]> successClickAreas = new ArrayList<>();
   private final List<String> successClickTexts = new ArrayList<>();
   private final List<String> successClickLabels = new ArrayList<>();
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
   private static final int DEFAULT_MAX_PLAYERS = 20;
   private static final int MIN_MAX_PLAYERS = 2;
   private static final int MAX_MAX_PLAYERS = 100;
   private static final Map<String, String> DEFAULT_CATEGORIES = new LinkedHashMap<String, String>() {
      {
         this.put("survival", "voxlink.category.survival");
         this.put("creative", "voxlink.category.creative");
         this.put("redstone", "voxlink.category.redstone");
         this.put("pvp", "voxlink.category.pvp");
         this.put("rpg", "voxlink.category.rpg");
         this.put("minigame", "voxlink.category.minigame");
         this.put("social", "voxlink.category.social");
         this.put("other", "voxlink.category.other");
      }
   };

   public CreateRoomScreen(Screen parent) {
      super(Component.translatable("voxlink.create_room"));
      this.parent = parent;
   }

   @Override
   protected void init() {
      super.init();
      if (this.createdRoom != null) {
         int centerX = this.width / 2;
         this.addRenderableWidget(
            Button.builder(Component.translatable("voxlink.back"), button -> this.goBack()).bounds(centerX - 100, this.height / 2 + 40, 200, 20).build()
         );
      } else {
         int centerX = this.width / 2;
         int formHeight = 240;
         int y = Math.max(4, (this.height - formHeight) / 2);
         this.nameField = new EditBox(this.font, centerX - 100, y, 200, 20, Component.translatable("voxlink.room_name"));
         this.nameField.setMaxLength(20);
         this.nameField.setHint(Component.translatable("voxlink.create_room.name_hint"));
         if (!this.savedName.isEmpty()) {
            this.nameField.setValue(this.savedName);
         }

         this.addRenderableWidget(this.nameField);
         this.passwordField = new EditBox(this.font, centerX - 100, y + 24, 200, 20, Component.translatable("voxlink.room_password"));
         this.passwordField.setMaxLength(32);
         this.passwordField.setHint(Component.translatable("voxlink.create_room.password_hint"));
         this.passwordField.setResponder(text -> this.updateVisibleForPassword());
         if (!this.savedPassword.isEmpty()) {
            this.passwordField.setValue(this.savedPassword);
         }

         this.addRenderableWidget(this.passwordField);
         this.maxPlayersField = new EditBox(this.font, centerX - 100, y + 48, 200, 20, Component.translatable("voxlink.max_players"));
         this.maxPlayersField.setMaxLength(3);
         this.maxPlayersField.setValue(this.savedMaxPlayers);
         this.setInputFilter(this.maxPlayersField, s -> s.matches("\\d*"));
         this.addRenderableWidget(this.maxPlayersField);
         int catY = y + 74;
         this.buildCategoryButtons(centerX, catY);
         this.customCategoryField = new EditBox(this.font, centerX - 100, catY + 22, 200, 18, Component.translatable("voxlink.create_room.custom_category"));
         this.customCategoryField.setMaxLength(32);
         this.customCategoryField.setHint(Component.translatable("voxlink.create_room.custom_category_hint"));
         this.customCategoryField.setVisible(this.showCustomInput);
         this.customCategoryField.setEditable(this.showCustomInput);
         this.addRenderableWidget(this.customCategoryField);
         int advY = catY + 42;
         this.visibleButton = Button.builder(this.buildVisibleLabel(), button -> {
            this.visible = !this.visible;
            this.visibleButton.setMessage(this.buildVisibleLabel());
         }).bounds(centerX - 100, advY, 98, 20).build();
         this.addRenderableWidget(this.visibleButton);
         this.updateVisibleForPassword();
         this.authButton = Button.builder(this.buildAuthLabel(), button -> {
            this.authType = this.authType == CreateRoomScreen.AuthType.OFFLINE ? CreateRoomScreen.AuthType.ONLINE : CreateRoomScreen.AuthType.OFFLINE;
            this.authButton.setMessage(this.buildAuthLabel());
         }).bounds(centerX + 2, advY, 98, 20).build();
         this.addRenderableWidget(this.authButton);
         this.gameTypeButton = Button.builder(this.buildGameTypeLabel(), button -> {
            this.gameType = switch (this.gameType) {
               case "survival" -> "creative";
               case "creative" -> "adventure";
               case "adventure" -> "spectator";
               default -> "survival";
            };
            this.gameTypeButton.setMessage(this.buildGameTypeLabel());
         }).bounds(centerX - 100, advY + 24, 98, 20).build();
         this.addRenderableWidget(this.gameTypeButton);
         this.hostOpButton = Button.builder(this.buildHostOpLabel(), button -> {
            this.hostOp = !this.hostOp;
            this.hostOpButton.setMessage(this.buildHostOpLabel());
            if (!this.hostOp && this.guestOp) {
               this.guestOp = false;
               this.guestOpButton.setMessage(this.buildGuestOpLabel());
            }

            this.guestOpButton.active = this.hostOp;
         }).bounds(centerX + 2, advY + 24, 98, 20).build();
         this.addRenderableWidget(this.hostOpButton);
         this.guestOpButton = Button.builder(this.buildGuestOpLabel(), button -> {
            this.guestOp = !this.guestOp;
            this.guestOpButton.setMessage(this.buildGuestOpLabel());
         }).bounds(centerX - 100, advY + 48, 98, 20).build();
         this.guestOpButton.active = this.hostOp;
         this.addRenderableWidget(this.guestOpButton);
         if (TerracottaManager.isBinaryReady()) {
            this.addRenderableWidget(
               Button.builder(
                     Component.translatable(
                        "voxlink.terracotta.toggle",
                        new Object[]{Component.translatable(VoxLinkMod.getConfig().isParallelP2P() ? "voxlink.terracotta.on" : "voxlink.terracotta.off")}
                     ),
                     button -> {
                        boolean v = !VoxLinkMod.getConfig().isParallelP2P();
                        VoxLinkMod.getConfig().setParallelP2P(v);
                        VoxLinkMod.getConfig().save();
                        button.setMessage(
                           Component.translatable(
                              "voxlink.terracotta.toggle", new Object[]{Component.translatable(v ? "voxlink.terracotta.on" : "voxlink.terracotta.off")}
                           )
                        );
                     }
                  )
                  .bounds(centerX + 2, advY + 48, 98, 20)
                  .build()
            );
         }

         this.createButton = Button.builder(Component.translatable("voxlink.create_room"), button -> this.createRoom())
            .bounds(centerX - 100, advY + 72, 98, 20)
            .build();
         this.addRenderableWidget(this.createButton);
         this.backButton = Button.builder(Component.translatable("voxlink.back"), button -> Minecraft.getInstance().setScreen(this.parent))
            .bounds(centerX + 2, advY + 72, 98, 20)
            .build();
         this.addRenderableWidget(this.backButton);
         if (!this.categoriesFetched) {
            this.fetchCategories();
         }

         if (this.creating) {
            this.createButton.active = false;
            this.backButton.active = false;
            this.nameField.setEditable(false);
            this.passwordField.setEditable(false);
            this.maxPlayersField.setEditable(false);
            this.visibleButton.active = false;
            this.authButton.active = false;
            this.guestOpButton.active = false;
            this.gameTypeButton.active = false;
            this.hostOpButton.active = false;
            this.customCategoryField.setEditable(false);

            for (Button btn : this.categoryButtons) {
               btn.active = false;
            }
         }
      }
   }

   private void buildCategoryButtons(int centerX, int startY) {
      for (Button btn : this.categoryButtons) {
         this.removeWidget(btn);
      }

      this.categoryButtons.clear();
      List<String> keys = new ArrayList<>(DEFAULT_CATEGORIES.keySet());
      if (!this.categoryMap.isEmpty()) {
         for (String key : this.categoryMap.keySet()) {
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
         String catKey = key;
         Button btn = Button.builder(this.getLabelComponentForKey(key, false), b -> {
            this.selectedCategory = catKey;
            boolean nowOther = catKey.equals("other");
            if (nowOther != this.showCustomInput) {
               this.showCustomInput = nowOther;
               this.customCategoryField.setVisible(nowOther);
               this.customCategoryField.setEditable(nowOther);
            }

            this.rebuildCategoryLabels();
         }).bounds(startX + i * (btnW + gap), startY, btnW, 18).build();
         this.categoryButtons.add(btn);
         this.addRenderableWidget(btn);
      }

      this.rebuildCategoryLabels();
   }

   private void rebuildCategoryLabels() {
      for (int i = 0; i < this.categoryButtons.size(); i++) {
         Button btn = this.categoryButtons.get(i);
         String key = this.getCategoryKeyAtIndex(i);
         btn.setMessage(this.getLabelComponentForKey(key, key.equals(this.selectedCategory)));
      }
   }

   private String getCategoryKeyAtIndex(int index) {
      List<String> keys = new ArrayList<>(DEFAULT_CATEGORIES.keySet());
      if (!this.categoryMap.isEmpty()) {
         for (String key : this.categoryMap.keySet()) {
            if (!DEFAULT_CATEGORIES.containsKey(key) && !keys.contains(key)) {
               keys.add(key);
            }
         }
      }

      return index >= 0 && index < keys.size() ? keys.get(index) : "other";
   }

   private Component getLabelComponentForKey(String key, boolean bold) {
      MutableComponent label = DEFAULT_CATEGORIES.containsKey(key)
         ? Component.translatable(DEFAULT_CATEGORIES.get(key))
         : Component.literal(this.categoryMap.getOrDefault(key, key));
      return bold ? label.withStyle(Style.EMPTY.withBold(true)) : label;
   }

   private void fetchCategories() {
      VoxLinkMod.getSignalingClient().getCategories().thenAccept(response -> {
         Minecraft mc = Minecraft.getInstance();
         mc.execute(() -> {
            if (!this.removed && this.createdRoom == null) {
               try {
                  if (response.success && response.data != null && response.data.isJsonObject()) {
                     JsonObject obj = response.data.getAsJsonObject();
                     this.categoryMap.clear();

                     for (String k : obj.keySet()) {
                        if (!DEFAULT_CATEGORIES.containsKey(k)) {
                           this.categoryMap.put(k, obj.get(k).getAsString());
                        }
                     }

                     this.categoriesFetched = true;
                     this.buildCategoryButtons(this.width / 2, Math.max(4, (this.height - 240) / 2) + 74);
                  }
               } catch (Exception var5) {
               }
            }
         });
      }).exceptionally(e -> null);
   }

   private void updateVisibleForPassword() {
      boolean hasPassword = this.passwordField != null && !this.passwordField.getValue().trim().isEmpty();
      if (hasPassword) {
         if (this.visible) {
            this.visibleBeforePassword = true;
         }

         this.visible = false;
      } else {
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

   private Component buildGuestOpLabel() {
      return this.guestOp ? Component.translatable("voxlink.guest_op.on") : Component.translatable("voxlink.guest_op.off");
   }

   private Component buildGameTypeLabel() {
      return Component.translatable("voxlink.game_type." + this.gameType);
   }

   private Component buildHostOpLabel() {
      return this.hostOp ? Component.translatable("voxlink.host_op.on") : Component.translatable("voxlink.host_op.off");
   }

   private String resolveCategory() {
      if (!this.selectedCategory.equals("other")) {
         return this.selectedCategory;
      }

      String custom = this.customCategoryField.getValue().trim();
      return custom.isEmpty() ? "other" : custom;
   }

   public boolean shouldCloseOnEsc() {
      return !this.creating;
   }

   public void onClose() {
      if (!this.creating) {
         this.goBack();
      }
   }

   private void goBack() {
      Minecraft.getInstance().setScreen(this.parent);
   }

   void onCreateTimeout() {
      this.cancelled = true;
      this.creating = false;
      this.createStartTime = 0L;
      this.closeLan();
      VoxLinkMod.getRoomManager().leaveRoom();
   }

   void onCancelCreate() {
      this.cancelled = true;
      this.creating = false;
      this.createStartTime = 0L;
      this.closeLan();
      VoxLinkMod.getRoomManager().leaveRoom();
   }

   private void createRoom() {
      this.savedName = this.nameField.getValue();
      this.savedPassword = this.passwordField.getValue();
      this.savedMaxPlayers = this.maxPlayersField.getValue();
      Minecraft mc = Minecraft.getInstance();
      if (mc.getSingleplayerServer() == null) {
         if (mc.player != null) {
            mc.player.displayClientMessage(Component.translatable("voxlink.create_room.open_world_first").withStyle(style -> style.withColor(16733525)), false);
         }
      } else {
         String name = this.nameField.getValue().trim();
         if (name.isEmpty()) {
            name = mc.player.getName().getString() + Component.translatable("voxlink.create_room.default_room_suffix").getString();
         }

         String roomName = name;
         String password = this.passwordField.getValue().trim();

         int maxPlayers;
         try {
            maxPlayers = Integer.parseInt(this.maxPlayersField.getValue());
         } catch (NumberFormatException e) {
            maxPlayers = 20;
         }

         if (maxPlayers < 2) {
            maxPlayers = 2;
         }

         if (maxPlayers > 100) {
            maxPlayers = 100;
         }

         this.createButton.active = false;
         this.backButton.active = false;
         this.cancelled = false;
         this.creating = true;
         this.createStartTime = System.currentTimeMillis();
         Minecraft.getInstance().setScreen(new CreatingRoomScreen(this));
         if (mc.player != null) {
            mc.player.displayClientMessage(Component.translatable("voxlink.chat.creating_room"), false);
         }

         IntegratedServer server = mc.getSingleplayerServer();
         int mcPort = server.getPort();
         if (mcPort <= 0) {
            mcPort = 25565;
         }

         if (!server.isPublished()) {
            GameType selectedGameType = switch (this.gameType) {
               case "creative" -> GameType.CREATIVE;
               case "adventure" -> GameType.ADVENTURE;
               case "spectator" -> GameType.SPECTATOR;
               default -> GameType.SURVIVAL;
            };
            this.publishedServer = server;
            boolean allowCommands = this.guestOp;
            boolean published = server.publishServer(selectedGameType, allowCommands, mcPort);
            if (!published) {
               this.creating = false;
               if (mc.player != null) {
                  mc.player.displayClientMessage(Component.translatable("voxlink.create_room.lan_failed").withStyle(style -> style.withColor(16733525)), false);
               }

               this.createButton.active = true;
               return;
            }

            if (VoxLinkMod.getConfig().isOfflineMode()) {
               server.setUsesAuthentication(false);
               VoxLinkMod.LOGGER.info("[CreateRoom] Re-disable online auth after publishServer (usesAuthentication={})", server.usesAuthentication());
            }

            VoxLinkMod.getRoomManager().applyOpPolicy(server, this.hostOp, this.guestOp);
         }

         int effectivePort = server.getPort() > 0 ? server.getPort() : mcPort;
         String categoryText = this.resolveCategory();
         VoxLinkMod.getRoomManager()
            .createRoom(roomName, password.isEmpty() ? null : password, maxPlayers, effectivePort, this.visible, this.authType.name(), categoryText)
            .thenAccept(
               roomInfo -> mc.execute(
                  () -> {
                     if (!this.cancelled) {
                        this.createStartTime = 0L;
                        if (roomInfo == null) {
                           this.creating = false;
                           this.closeLan();
                           if (mc.player != null) {
                              mc.player
                                 .displayClientMessage(
                                    Component.translatable("voxlink.chat.error_prefix").append(Component.translatable("voxlink.create_room.timeout"))
                                 , false);
                           }

                           mc.setScreen(this);
                        } else {
                           this.creating = false;
                           this.createdRoom = roomInfo;
                           roomInfo.setGuestOp(this.guestOp);
                           roomInfo.setGameType(this.gameType);
                           roomInfo.setHostOp(this.hostOp);
                           this.sendChatMessages(mc, roomInfo);
                           mc.setScreen(this);
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
               String displayMsg = this.simplifyError(msg);
               mc.execute(() -> {
                  if (!this.cancelled) {
                     this.createStartTime = 0L;
                     this.creating = false;
                     this.closeLan();
                     if (mc.player != null) {
                        mc.player.displayClientMessage(Component.translatable("voxlink.chat.error", new Object[]{displayMsg}), false);
                     }

                     mc.setScreen(this);
                  }
               });
               return null;
            });
      }
   }

   private String simplifyError(String msg) {
      if (msg == null) {
         return Component.translatable("voxlink.error.unknown").getString();
      } else if (msg.contains("NETWORK_ERROR")) {
         return Component.translatable("voxlink.create_room.error.cannot_connect_server").getString();
      } else if (msg.contains("RATE_LIMITED")) {
         return Component.translatable("voxlink.create_room.error.rate_limited").getString();
      } else if (msg.contains("MAX_ROOMS_REACHED")) {
         return Component.translatable("voxlink.create_room.error.max_rooms").getString();
      } else if (msg.contains("CONTENT_BLOCKED")) {
         return Component.translatable("voxlink.create_room.error.name_blocked").getString();
      } else if (msg.contains("ALREADY_IN_ROOM")) {
         return Component.translatable("voxlink.error.already_in_room").getString();
      } else if (msg.contains("TIMEOUT")) {
         return Component.translatable("voxlink.create_room.error.timeout").getString();
      } else if (msg.contains("QUEUED")) {
         return Component.translatable("voxlink.create_room.error.server_busy").getString();
      } else {
         return msg.contains("PARSE_ERROR") ? Component.translatable("voxlink.error.server_response_abnormal").getString() : msg;
      }
   }

   private void sendChatMessages(Minecraft mc, RoomInfo roomInfo) {
      if (mc.player != null) {
         String code = roomInfo.getCode();
         mc.player.displayClientMessage(Component.translatable("voxlink.chat.room_created").withStyle(Style.EMPTY.withBold(true)), false);
         mc.player
            .displayClientMessage(
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
            , false);
         mc.player.displayClientMessage(Component.translatable("voxlink.chat.friends_install_hint"), false);
         mc.player.displayClientMessage(Component.translatable("voxlink.create_room.recommend_voxlink"), false);
         String hostIp = roomInfo.getHostIp();
         int hostPort = roomInfo.getHostPort();
         String hostIpv6 = roomInfo.getHostIpv6();
         boolean hasV4 = hostIp != null && !hostIp.isEmpty();
         boolean hasV6 = hostIpv6 != null && !hostIpv6.isEmpty();
         if (hasV4 || hasV6) {
            MutableComponent addrLine = Component.translatable("voxlink.chat.your_addresses").withStyle(ChatFormatting.YELLOW);
            if (hasV4) {
               String addr = (hostIp.contains(":") ? "[" + hostIp + "]" : hostIp) + ":" + hostPort;
               addrLine.append(
                  Component.translatable("voxlink.chat.ipv4_label")
                     .withStyle(ChatCompat.styleWithCopy(addr, Component.translatable("voxlink.chat.copy_for_non_voxlink")).withColor(5635925))
               );
            }

            if (hasV4 && hasV6) {
               addrLine.append(Component.literal(" "));
            }

            if (hasV6) {
               String ipv6Addr = "[" + hostIpv6 + "]:" + hostPort;
               addrLine.append(
                  Component.translatable("voxlink.chat.ipv6_label")
                     .withStyle(ChatCompat.styleWithCopy(ipv6Addr, Component.translatable("voxlink.chat.copy_for_non_voxlink")).withColor(5635925))
               );
            }

            mc.player.displayClientMessage(addrLine, false);
         }
      }
   }

   public void removed() {
      super.removed();
      this.removed = true;
      if (!this.creating && this.createdRoom == null) {
         this.closeLan();
      }
   }

   private void closeLan() {
      if (this.publishedServer != null && this.publishedServer.isPublished()) {
         try {
            if (this.publishedServer instanceof IntegratedServer integrated) {
               try {
                  Field lanPingerField = integrated.getClass().getDeclaredField("lanPinger");
                  lanPingerField.setAccessible(true);
                  if (lanPingerField.get(integrated) instanceof Thread pingerThread) {
                     pingerThread.interrupt();
                  }

                  lanPingerField.set(integrated, null);
               } catch (NoSuchFieldException nsfe) {
                  VoxLinkMod.LOGGER.debug("1.21.11 has no lanPinger field, skip");
               } catch (Exception e) {
                  VoxLinkMod.LOGGER.debug("lanPinger reflection error: {}", e.getMessage());
               }
            }

            ServerConnectionListener conn = this.publishedServer.getConnection();
            if (conn == null) {
               return;
            }

            try {
               Field channelsField = conn.getClass().getDeclaredField("channels");
               channelsField.setAccessible(true);
               List<?> channels = (List<?>)channelsField.get(conn);
               if (channels == null) {
                  return;
               }

               synchronized (channels) {
                  Iterator<?> it = channels.iterator();

                  while (it.hasNext()) {
                     ChannelFuture future = (ChannelFuture)it.next();
                     if (!(future.channel() instanceof LocalServerChannel)) {
                        future.channel().close();
                        it.remove();
                     }
                  }
               }
            } catch (NoSuchFieldException nsfe) {
               VoxLinkMod.LOGGER.debug("channels field not found, skip cleanup");
            }
         } catch (Exception e) {
            VoxLinkMod.LOGGER.warn("Failed to close LAN: {}", e.getMessage());
         }
      }
   }

   public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
      super.render(graphics, mouseX, mouseY, partialTick);
      int centerX = this.width / 2;
      if (this.createdRoom != null) {
         this.successClickAreas.clear();
         this.successClickTexts.clear();
         this.successClickLabels.clear();
         Font font = Minecraft.getInstance().font;
         int y = Math.max(20, this.height / 2 - 40);
         this.drawCenteredClipped(graphics, Component.translatable("voxlink.create_room.success").getString(), centerX, y, -11141291);
         y += 18;
         String code = this.createdRoom.getCode();
         Component codeLine = Component.translatable("voxlink.chat.room_code_label")
            .withStyle(ChatFormatting.YELLOW)
            .append(
               Component.literal("[")
                  .append(Component.translatable("voxlink.chat.click_to_copy"))
                  .append("]")
                  .withStyle(new ChatFormatting[]{ChatFormatting.GREEN, ChatFormatting.BOLD})
            );
         this.drawCenteredComponent(graphics, codeLine, centerX, y, -1);
         int codeW = this.fontWidth(codeLine);
         this.successClickAreas.add(new int[]{centerX - codeW / 2, y, codeW, 9});
         this.successClickTexts.add(code);
         this.successClickLabels.add(Component.translatable("voxlink.chat.room_code_label").getString());
         y += 12;
         this.drawCenteredClipped(graphics, Component.translatable("voxlink.create_room.recommend_voxlink").getString(), centerX, y, -5592321);
         y += 14;
         String tc = this.createdRoom.getTerracottaCode();
         if (tc != null && !tc.isEmpty()) {
            Component tcLine = Component.translatable("voxlink.chat.terracotta_code_label", new Object[]{" "})
               .append(
                  Component.literal("[")
                     .append(Component.translatable("voxlink.chat.click_to_copy"))
                     .append("]")
                     .withStyle(new ChatFormatting[]{ChatFormatting.AQUA, ChatFormatting.BOLD})
               );
            this.drawCenteredComponent(graphics, tcLine, centerX, y, -1);
            int tcW = this.fontWidth(tcLine);
            this.successClickAreas.add(new int[]{centerX - tcW / 2, y, tcW, 9});
            this.successClickTexts.add(tc);
            this.successClickLabels.add(Component.translatable("voxlink.chat.terracotta_code_label", new Object[]{""}).getString().trim());
            y += 14;
         }

         String hostIp = this.createdRoom.getHostIp();
         int hostPort = this.createdRoom.getHostPort();
         String hostIpv6 = this.createdRoom.getHostIpv6();
         boolean hasV4 = hostIp != null && !hostIp.isEmpty();
         boolean hasV6 = hostIpv6 != null && !hostIpv6.isEmpty();
         if (hasV4 || hasV6) {
            String addrLabel = Component.translatable("voxlink.chat.your_addresses").getString();
            int labelW = font.width(addrLabel);
            String v4Label = hasV4 ? Component.translatable("voxlink.chat.ipv4_label").getString() : "";
            String v6Label = hasV6 ? Component.translatable("voxlink.chat.ipv6_label").getString() : "";
            int v4W = hasV4 ? font.width(v4Label) : 0;
            int v6W = hasV6 ? font.width(v6Label) : 0;
            int spaceW = hasV4 && hasV6 ? font.width(" ") : 0;
            int totalW = labelW + v4W + spaceW + v6W;
            int startX = centerX - totalW / 2;
            this.drawString(graphics, addrLabel, startX, y, -1);
            int curX = startX + labelW;
            if (hasV4) {
               this.drawString(graphics, ChatFormatting.GREEN.toString() + v4Label + ChatFormatting.RESET.toString(), curX, y, -11141291);
               this.successClickAreas.add(new int[]{curX, y, v4W, 9});
               this.successClickTexts.add((hostIp.contains(":") ? "[" + hostIp + "]" : hostIp) + ":" + hostPort);
               this.successClickLabels.add(v4Label);
               curX += v4W;
            }

            if (hasV4 && hasV6) {
               this.drawString(graphics, " ", curX, y, -1);
               curX += spaceW;
            }

            if (hasV6) {
               this.drawString(graphics, ChatFormatting.GREEN.toString() + v6Label + ChatFormatting.RESET.toString(), curX, y, -11141291);
               this.successClickAreas.add(new int[]{curX, y, v6W, 9});
               this.successClickTexts.add("[" + hostIpv6 + "]:" + hostPort);
               this.successClickLabels.add(v6Label);
            }

            y += 14;
         }
      } else {
         this.drawCenteredClipped(graphics, this.title.getString(), centerX, 8, -1);
      }
   }

   protected boolean handleSuccessClick(double mx, double my) {
      if (this.createdRoom != null) {
         for (int i = 0; i < this.successClickAreas.size(); i++) {
            int[] a = this.successClickAreas.get(i);
            if (mx >= a[0] && mx < a[0] + a[2] && my >= a[1] && my < a[1] + a[3]) {
               String text = this.successClickTexts.get(i);
               String label = this.successClickLabels.get(i);
               Minecraft.getInstance().keyboardHandler.setClipboard(text);
               if (Minecraft.getInstance().player != null) {
                  Minecraft.getInstance().player.displayClientMessage(Component.translatable("voxlink.chat.copied_to_clipboard", new Object[]{label}), false);
               }

               return true;
            }
         }
      }

      return false;
   }

   public boolean mouseClicked(double mouseX, double mouseY, int button) {
      if (super.mouseClicked(mouseX, mouseY, button)) {
         return true;
      }
      return this.handleSuccessClick(mouseX, mouseY);
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
