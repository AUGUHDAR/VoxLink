package icu.wuhui.voxlink.ui;

import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.network.ConnectionHelper;
import icu.wuhui.voxlink.network.LogUploadManager;
import icu.wuhui.voxlink.network.NatClass;
import icu.wuhui.voxlink.room.ConnectionManager;
import icu.wuhui.voxlink.room.RoomInfo;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.GuiGraphics;

public class AttemptingJoinScreen extends VoxLinkScreenBase {
   private static final int BTN_W = 200;
   private static final int HALF_BTN_W = 100;
   private static final int BTN_H = 20;
   private static final int BTN_Y_OFFSET = 45;
   private static final int TITLE_Y = 15;
   private static final int ROOM_CODE_Y_OFFSET = 30;
   private static final int STATUS_MARGIN = 20;
   private static final int VOXLINK_STATUS_Y_OFFSET = 0;
   private static final int TERRACOTTA_STATUS_Y_OFFSET = 14;
   private static final int TICK_INTERVAL_MS = 500;
   private static final int MAX_MONITOR_TICKS_DUAL = 260;
   private static final int RELAY_BTN_GAP = 4;
   private static final int RELAY_FAILED_MSG_MS = 3000;
   private static final int TIP_SWITCH_MS = 5000;
   private static final String[] TIP_KEYS = new String[]{
      "voxlink.tip.wait",
      "voxlink.tip.punch_luck",
      "voxlink.tip.website",
      "voxlink.tip.author_id",
      "voxlink.tip.server",
      "voxlink.tip.donate",
      "voxlink.tip.issues",
      "voxlink.tip.share",
      "voxlink.tip.knowledge_restart",
      "voxlink.tip.knowledge_terracotta",
      "voxlink.tip.knowledge_network",
      "voxlink.tip.knowledge_fallback",
      "voxlink.tip.turn_future",
      "voxlink.tip.edge_terracotta",
      "voxlink.tip.reverse_relay"
   };
   private final List<String> tipQueue = new ArrayList<>();
   private String currentTipKey = "";
   private long tipLastSwitchTime = 0L;
   private final Screen parent;
   private final String roomCode;
   private final String password;
   private String voxlinkStatusText = "";
   private int voxlinkStatusColor = VoxLinkColors.MUTED;
   private volatile boolean voxlinkFinal = false;
   private volatile long voxlinkStatusLastUpdate = 0L;
   private String terracottaStatusText = "";
   private int terracottaStatusColor = VoxLinkColors.MUTED;
   private volatile boolean terracottaFinal = false;
   private volatile boolean active = false;
   private boolean joinApiDone = false;
   private volatile boolean joinCompleted = false;
   private volatile ScheduledExecutorService connectionScheduler;
   private ScheduledFuture<?> connectionFuture;
   private int monitorTicks = 0;
   private static final int MAX_MONITOR_TICKS = 360;
   private static final int BRIDGE_HANDSHAKE_TICKS = 120;
   private int bridgeEstablishedAtTick = -1;
   private volatile boolean relayButtonVisible = false;
   private volatile long relayFailedMsgTime = 0L;
   private volatile boolean lastManualRelayInProgress = false;

   public AttemptingJoinScreen(Screen parent, String roomCode, String password) {
      super(Component.translatable("voxlink.attempting_join"));
      this.parent = parent;
      this.roomCode = roomCode != null ? roomCode : "";
      this.password = password;
   }

   @Override
   protected void init() {
      super.init();
      RoomInfo room = VoxLinkMod.getRoomManager().getCurrentRoom();
      boolean bridgeReady = room != null && room.getLocalBridgePort() > 0 && ConnectionHelper.isMcTrulyConnected();
      if (bridgeReady) {
         this.active = false;
         room.setConnectionMode(Component.translatable("voxlink.connection.connected"));
         this.voxlinkFinal = true;
         this.voxlinkStatusText = Component.translatable("voxlink.dual.p2p_established").getString();
         this.voxlinkStatusColor = VoxLinkColors.SUCCESS;
      } else if (room != null && room.isConnectionFailed()) {
         this.active = false;
         this.voxlinkFinal = true;
         this.voxlinkStatusText = Component.translatable("voxlink.connection.all_failed").getString();
         this.voxlinkStatusColor = VoxLinkColors.ERROR;
      } else if (room != null && room.getLocalBridgePort() > 0) {
         this.voxlinkStatusText = Component.translatable("voxlink.connection.bridge_setup").getString();
         this.voxlinkStatusColor = VoxLinkColors.WARNING;
      }

      int centerX = this.width / 2;
      int btnY = this.height / 2 + 45;
      if (!bridgeReady) {
         if (this.joinApiDone && !this.active) {
            this.addRenderableWidget(
               Button.builder(Component.translatable("voxlink.back"), button -> this.goBack()).bounds(centerX - 100, btnY, 200, 20).build()
            );
         } else {
            this.addRenderableWidget(
               Button.builder(Component.translatable("voxlink.cancel"), button -> this.cancelJoin()).bounds(centerX - 100, btnY, 200, 20).build()
            );
            if (this.active && VoxLinkMod.getRoomManager().getConnectionManager().canShowRelayButton()) {
               this.relayButtonVisible = true;
               this.addRenderableWidget(
                  Button.builder(Component.translatable("voxlink.relay.use_player_relay"), button -> this.onRelayButtonClicked())
                     .bounds(centerX - 100, btnY + 20 + 4, 200, 20)
                     .build()
               );
            } else {
               this.relayButtonVisible = false;
            }
         }
      }

      if (!this.joinApiDone) {
         this.joinApiDone = true;
         this.startJoin();
      }
   }

   public boolean shouldCloseOnEsc() {
      return !this.active;
   }

   public void onClose() {
      if (this.active) {
         this.cancelJoin();
      } else {
         this.goBack();
      }
   }

   private void goBack() {
      if (VoxLinkMod.getRoomManager().getCurrentRoom() != null) {
         VoxLinkMod.getRoomManager().leaveRoom();
      }

      Minecraft.getInstance().setScreen(this.parent);
   }

   private void cancelJoin() {
      this.active = false;
      VoxLinkMod.getRoomManager().leaveRoom();
      Minecraft.getInstance().setScreen(this.parent);
   }

   private void onRelayButtonClicked() {
      if (VoxLinkMod.getConfig().isRelayEnabled()) {
         this.relayButtonVisible = false;
         this.lastManualRelayInProgress = true;
         this.clearOurWidgets();
         this.init();
         VoxLinkMod.getRoomManager().getConnectionManager().triggerManualRelay();
      }
   }

   private void startJoin() {
      this.active = true;
      LogUploadManager.arm(this.roomCode, false);
      Minecraft mc = Minecraft.getInstance();
      String playerName = mc.getUser().getName();
      CompletableFuture<Void> joinFuture = VoxLinkMod.getRoomManager()
         .getConnectionManager()
         .startDualP2P(this.roomCode, playerName, this.password, (channel, statusKey) -> mc.execute(() -> {
            if (mc.screen == this) {
               int color = this.colorForStatus(statusKey);
               String text = Component.translatable(statusKey).getString();
               if ("voxlink".equals(channel)) {
                  this.voxlinkStatusText = text;
                  this.voxlinkStatusColor = color;
                  if (statusKey.endsWith(".p2p_established") || statusKey.endsWith(".channel_failed") || statusKey.endsWith(".status_cancelled")) {
                     this.voxlinkFinal = true;
                  }
               } else if ("terracotta".equals(channel)) {
                  this.terracottaStatusText = text;
                  this.terracottaStatusColor = color;
                  if (statusKey.endsWith(".p2p_established") || statusKey.endsWith(".channel_failed") || statusKey.endsWith(".status_cancelled")) {
                     this.terracottaFinal = true;
                  }
               }
            }
         }));
      joinFuture.whenComplete((v, e) -> this.joinCompleted = true);
      joinFuture.thenAccept(v -> mc.execute(() -> {
         if (mc.screen == this) {
            ;
         }
      })).exceptionally(e -> {
         mc.execute(() -> {
            if (mc.screen == this) {
               Throwable cause = e;

               while (cause.getCause() != null) {
                  cause = cause.getCause();
               }

               this.onFailed(this.extractErrorMessage(cause.getMessage()));
            }
         });
         return null;
      });
      this.startConnectionMonitor();
   }

   private int colorForStatus(String statusKey) {
      if (statusKey == null) {
         return VoxLinkColors.MUTED;
      } else if (statusKey.endsWith(".connected") || statusKey.endsWith(".p2p_established")) {
         return VoxLinkColors.SUCCESS;
      } else if (statusKey.endsWith(".all_failed") || statusKey.endsWith(".channel_failed")) {
         return VoxLinkColors.ERROR;
      } else {
         return statusKey.endsWith(".status_cancelled") ? VoxLinkColors.MUTED : VoxLinkColors.WARNING;
      }
   }

   private void onFailed(String msg) {
      this.voxlinkFinal = true;
      this.voxlinkStatusText = msg;
      this.voxlinkStatusColor = VoxLinkColors.ERROR;
      this.active = false;
      this.stopConnectionMonitor();
      VoxLinkMod.getRoomManager().getConnectionManager().killAllConnectionAttempts();
      this.clearOurWidgets();
      this.init();
   }

   public void onRoomLost() {
      this.stopConnectionMonitor();
      this.active = false;
   }

   private void stopConnectionMonitor() {
      if (this.connectionFuture != null) {
         this.connectionFuture.cancel(false);
         this.connectionFuture = null;
      }

      if (this.connectionScheduler != null && !this.connectionScheduler.isShutdown()) {
         this.connectionScheduler.shutdownNow();
         this.connectionScheduler = null;
      }
   }

   private String extractErrorMessage(String msg) {
      if (msg == null) {
         return Component.translatable("voxlink.error.unknown").getString();
      } else if (msg.contains("ROOM_NOT_FOUND") || msg.contains("SERVER_404")) {
         return Component.translatable("voxlink.join_room.error.not_found").getString();
      } else if (msg.contains("ROOM_FULL")) {
         return Component.translatable("voxlink.error.room_full").getString();
      } else if (msg.contains("WRONG_PASSWORD")) {
         return Component.translatable("voxlink.error.wrong_password").getString();
      } else if (msg.contains("RATE_LIMITED")) {
         return Component.translatable("voxlink.join_room.error.rate_limited").getString();
      } else if (msg.contains("NETWORK_ERROR")) {
         return Component.translatable("voxlink.join_room.error.network").getString();
      } else if (msg.contains("ALREADY_IN_ROOM")) {
         return Component.translatable("voxlink.error.already_in_room").getString();
      } else if (msg.contains("INVALID_ROOM_CODE")) {
         return Component.translatable("voxlink.error.invalid_room_code").getString();
      } else {
         return msg.contains("QUEUED") ? Component.translatable("voxlink.join_room.error.server_busy").getString() : msg;
      }
   }

   private void startConnectionMonitor() {
      this.stopConnectionMonitor();
      this.monitorTicks = 0;
      final AtomicBoolean monitorActive = new AtomicBoolean(true);
      this.connectionScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
         Thread t = new Thread(r, "VoxLink-JoinConnMonitor");
         t.setDaemon(true);
         return t;
      });
      Runnable monitor = new Runnable() {
         @Override
         public void run() {
            if (monitorActive.get()) {
               try {
                  Minecraft mc = Minecraft.getInstance();
                  AttemptingJoinScreen.this.monitorTicks++;
                  RoomInfo roomInfo = VoxLinkMod.getRoomManager().getCurrentRoom();
                  if (roomInfo == null) {
                     if (!AttemptingJoinScreen.this.joinCompleted) {
                        if (AttemptingJoinScreen.this.connectionFuture != null) {
                           AttemptingJoinScreen.this.connectionFuture.cancel(false);
                        }

                        if (monitorActive.get()
                           && AttemptingJoinScreen.this.connectionScheduler != null
                           && !AttemptingJoinScreen.this.connectionScheduler.isShutdown()) {
                           AttemptingJoinScreen.this.connectionFuture = AttemptingJoinScreen.this.connectionScheduler
                              .schedule(() -> mc.execute(this), 500L, TimeUnit.MILLISECONDS);
                        }

                        return;
                     }

                     monitorActive.set(false);
                     mc.execute(() -> {
                        if (mc.screen == AttemptingJoinScreen.this) {
                           AttemptingJoinScreen.this.onFailed(Component.translatable("voxlink.room_lost").getString());
                        }
                     });
                     if (AttemptingJoinScreen.this.connectionScheduler != null && !AttemptingJoinScreen.this.connectionScheduler.isShutdown()) {
                        AttemptingJoinScreen.this.connectionScheduler.shutdownNow();
                     }

                     return;
                  }

                  if (roomInfo.getLocalBridgePort() > 0) {
                     if (AttemptingJoinScreen.this.bridgeEstablishedAtTick < 0) {
                        AttemptingJoinScreen.this.bridgeEstablishedAtTick = AttemptingJoinScreen.this.monitorTicks;
                     }

                     if (ConnectionHelper.isMcTrulyConnected()) {
                        monitorActive.set(false);
                        ConnectionHelper.clearConnectInitiated();
                        roomInfo.setConnectionMode(Component.translatable("voxlink.connection.connected"));
                        mc.execute(() -> {
                           if (mc.screen == AttemptingJoinScreen.this) {
                              AttemptingJoinScreen.this.voxlinkFinal = true;
                              AttemptingJoinScreen.this.voxlinkStatusText = Component.translatable("voxlink.dual.p2p_established").getString();
                              AttemptingJoinScreen.this.voxlinkStatusColor = VoxLinkColors.SUCCESS;
                              AttemptingJoinScreen.this.active = false;
                           }
                        });
                        if (AttemptingJoinScreen.this.connectionScheduler != null && !AttemptingJoinScreen.this.connectionScheduler.isShutdown()) {
                           AttemptingJoinScreen.this.connectionScheduler.shutdownNow();
                        }

                        return;
                     }

                     if (ConnectionHelper.isConnectionRejected()) {
                        monitorActive.set(false);
                        ConnectionHelper.clearConnectInitiated();
                        mc.execute(() -> {
                           if (mc.screen == AttemptingJoinScreen.this) {
                              AttemptingJoinScreen.this.onFailed(Component.translatable("voxlink.connection.all_failed").getString());
                           }
                        });
                        if (AttemptingJoinScreen.this.connectionScheduler != null && !AttemptingJoinScreen.this.connectionScheduler.isShutdown()) {
                           AttemptingJoinScreen.this.connectionScheduler.shutdownNow();
                        }

                        return;
                     }

                     mc.execute(() -> {
                        if (mc.screen == AttemptingJoinScreen.this) {
                           if (!AttemptingJoinScreen.this.voxlinkFinal) {
                              AttemptingJoinScreen.this.voxlinkStatusText = Component.translatable("voxlink.connection.bridge_setup").getString();
                              AttemptingJoinScreen.this.voxlinkStatusColor = VoxLinkColors.WARNING;
                           }
                        }
                     });
                  }

                  boolean bridgeBuiltNow = roomInfo.getLocalBridgePort() > 0;
                  int maxTicks = VoxLinkMod.getRoomManager().getConnectionManager().isDualRaceActive() ? 260 : 360;
                  boolean handshakeTimeout = bridgeBuiltNow && AttemptingJoinScreen.this.bridgeEstablishedAtTick >= 0
                     ? AttemptingJoinScreen.this.monitorTicks - AttemptingJoinScreen.this.bridgeEstablishedAtTick >= 120
                     : AttemptingJoinScreen.this.monitorTicks >= maxTicks;
                  boolean persistentRetrying = VoxLinkMod.getRoomManager().getConnectionManager().isPersistentRetrying();
                  if ((roomInfo.isConnectionFailed() || handshakeTimeout) && !persistentRetrying) {
                     monitorActive.set(false);
                     if (handshakeTimeout) {
                        ConnectionHelper.clearConnectInitiated();
                     }

                     mc.execute(() -> {
                        if (mc.screen == AttemptingJoinScreen.this) {
                           Component connModex = roomInfo.getConnectionMode();
                           String failReason;
                           if (roomInfo.isConnectionFailed() && connModex != null && !connModex.getString().isEmpty()) {
                              failReason = connModex.getString();
                           } else if (handshakeTimeout) {
                              failReason = Component.translatable("voxlink.connection.timeout_retry").getString();
                           } else {
                              failReason = Component.translatable("voxlink.connection.all_failed").getString();
                           }

                           AttemptingJoinScreen.this.onFailed(failReason);
                        }
                     });
                     if (AttemptingJoinScreen.this.connectionScheduler != null && !AttemptingJoinScreen.this.connectionScheduler.isShutdown()) {
                        AttemptingJoinScreen.this.connectionScheduler.shutdownNow();
                     }

                     return;
                  }

                  Component connMode = roomInfo.getConnectionMode();
                  if (connMode != null && !connMode.getString().isEmpty()) {
                     mc.execute(
                        () -> {
                           if (mc.screen == AttemptingJoinScreen.this) {
                              if (!AttemptingJoinScreen.this.voxlinkFinal) {
                                 long now = System.currentTimeMillis();
                                 String newText = connMode.getString();
                                 if (!newText.equals(AttemptingJoinScreen.this.voxlinkStatusText)
                                    && now - AttemptingJoinScreen.this.voxlinkStatusLastUpdate < 2000L) {
                                    return;
                                 }

                                 AttemptingJoinScreen.this.voxlinkStatusText = newText;
                                 AttemptingJoinScreen.this.voxlinkStatusColor = VoxLinkColors.WARNING;
                                 AttemptingJoinScreen.this.voxlinkStatusLastUpdate = now;
                              }
                           }
                        }
                     );
                  }

                  boolean shouldShowRelay = AttemptingJoinScreen.this.active && VoxLinkMod.getRoomManager().getConnectionManager().canShowRelayButton();
                  if (shouldShowRelay != AttemptingJoinScreen.this.relayButtonVisible) {
                     mc.execute(() -> {
                        if (mc.screen == AttemptingJoinScreen.this) {
                           AttemptingJoinScreen.this.clearOurWidgets();
                           AttemptingJoinScreen.this.init();
                        }
                     });
                  }

                  boolean currentRelayInProgress = VoxLinkMod.getRoomManager().getConnectionManager().isManualRelayInProgress();
                  if (AttemptingJoinScreen.this.lastManualRelayInProgress && !currentRelayInProgress) {
                     AttemptingJoinScreen.this.relayFailedMsgTime = System.currentTimeMillis();
                  }

                  AttemptingJoinScreen.this.lastManualRelayInProgress = currentRelayInProgress;
                  if (AttemptingJoinScreen.this.connectionFuture != null) {
                     AttemptingJoinScreen.this.connectionFuture.cancel(false);
                  }

                  if (monitorActive.get()
                     && AttemptingJoinScreen.this.connectionScheduler != null
                     && !AttemptingJoinScreen.this.connectionScheduler.isShutdown()) {
                     AttemptingJoinScreen.this.connectionFuture = AttemptingJoinScreen.this.connectionScheduler
                        .schedule(() -> mc.execute(this), 500L, TimeUnit.MILLISECONDS);
                  }
               } catch (Exception e) {
                  Minecraft mc = Minecraft.getInstance();
                  mc.execute(() -> {
                     if (mc.screen == AttemptingJoinScreen.this) {
                        AttemptingJoinScreen.this.onFailed(Component.translatable("voxlink.connection.monitor_error").getString());
                     }
                  });
               }
            }
         }
      };
      this.connectionFuture = this.connectionScheduler.schedule(() -> Minecraft.getInstance().execute(monitor), 500L, TimeUnit.MILLISECONDS);
   }

   public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
      super.render(graphics, mouseX, mouseY, partialTick);
      this.renderNatOverlay(graphics);
      int centerX = this.width / 2;
      this.drawCenteredString(graphics, this.title.getString(), centerX, 15, VoxLinkColors.WHITE);
      this.drawCenteredString(
         graphics,
         ChatFormatting.YELLOW.toString() + ChatFormatting.BOLD.toString() + Component.translatable("voxlink.chat.room_code_label").getString().trim(),
         centerX,
         this.height / 2 - 30,
         VoxLinkColors.WARNING
      );
      if (this.relayFailedMsgTime > 0L) {
         long elapsed = System.currentTimeMillis() - this.relayFailedMsgTime;
         if (elapsed < 3000L) {
            this.drawCenteredString(graphics, Component.translatable("voxlink.relay.failed_retry_punch").getString(), centerX, this.height / 2 - 30 - 12, VoxLinkColors.WARNING);
         } else {
            this.relayFailedMsgTime = 0L;
         }
      }

      if (!this.voxlinkStatusText.isEmpty()) {
         String label = Component.translatable("voxlink.dual.voxlink_label").getString();
         String clipped = this.voxlinkStatusText;
         int maxWidth = this.width - 20;
         if (this.fontWidth(label + ": " + clipped) > maxWidth) {
            while (this.fontWidth(label + ": " + clipped + "...") > maxWidth && clipped.length() > 0) {
               clipped = clipped.substring(0, clipped.length() - 1);
            }

            clipped = clipped + "...";
         }

         this.drawCenteredString(graphics, label + ": " + clipped, centerX, this.height / 2 + 0, this.voxlinkStatusColor);
      }

      if (!this.terracottaStatusText.isEmpty()) {
         String label = Component.translatable("voxlink.dual.terracotta_label").getString();
         String clipped = this.terracottaStatusText;
         int maxWidth = this.width - 20;
         if (this.fontWidth(label + ": " + clipped) > maxWidth) {
            while (this.fontWidth(label + ": " + clipped + "...") > maxWidth && clipped.length() > 0) {
               clipped = clipped.substring(0, clipped.length() - 1);
            }

            clipped = clipped + "...";
         }

         this.drawCenteredString(graphics, label + ": " + clipped, centerX, this.height / 2 + 14, this.terracottaStatusColor);
      }

      long now = System.currentTimeMillis();
      if (this.currentTipKey.isEmpty() || now - this.tipLastSwitchTime >= 5000L) {
         if (this.tipQueue.isEmpty()) {
            this.refillTipQueue();
         }

         this.currentTipKey = this.tipQueue.remove(0);
         this.tipLastSwitchTime = now;
      }

      String tipText = Component.translatable("voxlink.tip.prefix").getString() + Component.translatable(this.currentTipKey).getString();
      int tipMaxWidth = this.width - 8;
      if (LogUploadManager.isUploadFinished()) {
         int uploadTextWidth = this.fontWidth(Component.translatable("voxlink.log_upload.uploaded").getString());
         tipMaxWidth = Math.max(80, this.width - uploadTextWidth - 16);
      }
      if (this.fontWidth(tipText) > tipMaxWidth) {
         while (this.fontWidth(tipText + "...") > tipMaxWidth && tipText.length() > 0) {
            tipText = tipText.substring(0, tipText.length() - 1);
         }

         tipText = tipText + "...";
      }

      this.drawString(graphics, tipText, 4, this.height - 12, VoxLinkColors.MUTED);

      if (LogUploadManager.isUploadFinished()) {
         String uploadText = Component.translatable("voxlink.log_upload.uploaded").getString();
         int uploadWidth = this.fontWidth(uploadText);
         this.drawString(graphics, uploadText, this.width - uploadWidth - 6, this.height - 12, VoxLinkColors.SUCCESS);
      }
   }

   private void renderNatOverlay(GuiGraphics graphics) {
      ConnectionManager cm = VoxLinkMod.getRoomManager().getConnectionManager();
      NatClass local = cm.getLocalNatClass();
      NatClass remote = cm.getRemoteNatClass();
      if (local == null) {
         local = NatClass.UNKNOWN;
      }

      if (remote == null) {
         remote = NatClass.UNKNOWN;
      }

      boolean anyUnknown = local == NatClass.UNKNOWN || remote == NatClass.UNKNOWN;
      String opponentText = this.natCnName(remote);
      String mineText = this.natCnName(local);
      String difficultyText = Component.translatable(cm.getConnectionDifficultyKey()).getString();
      if (anyUnknown) {
         difficultyText = difficultyText + Component.translatable("voxlink.nat.doubt").getString();
      }

      int x = 4;
      int y = 18;
      int line = 10;
      this.drawString(graphics, Component.translatable("voxlink.nat.label_opponent").getString() + ": " + opponentText, x, y, VoxLinkColors.MUTED);
      this.drawString(graphics, Component.translatable("voxlink.nat.label_mine").getString() + ": " + mineText, x, y + line, VoxLinkColors.MUTED);
      this.drawString(graphics, Component.translatable("voxlink.nat.label_difficulty").getString() + ": " + difficultyText, x, y + line * 2, VoxLinkColors.WARNING);
   }

   private String natCnName(NatClass nat) {
      switch (nat) {
         case CONE:
            return Component.translatable("voxlink.nat.cone").getString();
         case EASY_SYM:
            return Component.translatable("voxlink.nat.easy_sym").getString();
         case HARD_SYM:
            return Component.translatable("voxlink.nat.hard_sym").getString();
         default:
            return Component.translatable("voxlink.nat.unknown").getString();
      }
   }

   private void refillTipQueue() {
      this.tipQueue.addAll(Arrays.asList(TIP_KEYS));
      Collections.shuffle(this.tipQueue);
   }

   public void removed() {
      super.removed();
      this.stopConnectionMonitor();
      RoomInfo room = VoxLinkMod.getRoomManager().getCurrentRoom();
      boolean bridgeBuilt = room != null && room.getLocalBridgePort() > 0;
      if (this.active && !bridgeBuilt) {
         this.active = false;
         VoxLinkMod.getRoomManager().getConnectionManager().killAllConnectionAttempts();
      } else {
         this.active = false;
      }
   }
}
