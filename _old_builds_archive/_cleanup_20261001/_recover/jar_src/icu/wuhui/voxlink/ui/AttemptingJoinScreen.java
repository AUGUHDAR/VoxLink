package icu.wuhui.voxlink.ui;

import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.modsync.ModSyncGuestService;
import icu.wuhui.voxlink.network.ConnectionHelper;
import icu.wuhui.voxlink.network.LogUploadManager;
import icu.wuhui.voxlink.network.NatClass;
import icu.wuhui.voxlink.network.TurnRelayClient;
import icu.wuhui.voxlink.room.ConnectionManager;
import icu.wuhui.voxlink.room.RoomInfo;
import icu.wuhui.voxlink.terracotta.RoomCodeRouter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

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
      "voxlink.tip.credit_bilibili",
      "voxlink.tip.edge_terracotta",
      "voxlink.tip.reverse_relay",
      "voxlink.tip.auto_collapse_create",
      "voxlink.tip.disable_join_check",
      "voxlink.tip.app",
      "voxlink.tip.kamu_launcher"
   };
   private final List<String> tipQueue = new ArrayList<>();
   private final ArrayList<String> logLines = new ArrayList<>();
   private final ArrayList<Integer> logLevels = new ArrayList<>();
   private int logScrollRows = 0;
   private boolean logFollowTail = true;
   private long logSeenVersion = -1L;
   private boolean logPanelOpen = false;
   private String currentTipKey = "";
   private long tipLastSwitchTime = 0L;
   private final Screen parent;
   private final String roomCode;
   private final String password;
   private String voxlinkStatusText = "";
   private int voxlinkStatusColor = -5592406;
   private volatile boolean voxlinkFinal = false;
   private volatile long voxlinkStatusLastUpdate = 0L;
   private String terracottaStatusText = "";
   private int terracottaStatusColor = -5592406;
   private volatile boolean terracottaFinal = false;
   private volatile boolean active = false;
   private boolean joinApiDone = false;
   private volatile boolean joinInitiated = false;
   private volatile boolean joinCompleted = false;
   private volatile ScheduledExecutorService connectionScheduler;
   private ScheduledFuture<?> connectionFuture;
   private int monitorTicks = 0;
   private int joinNoResponseTicks = -1;
   private static final int MAX_MONITOR_TICKS = 360;
   private static final int BRIDGE_HANDSHAKE_TICKS = 60;
   private int bridgeEstablishedAtTick = -1;
   private volatile boolean relayButtonVisible = false;
   private volatile long relayFailedMsgTime = 0L;
   private volatile boolean lastManualRelayInProgress = false;
   private volatile long turnFailedMsgTime = 0L;
   private volatile boolean lastTurnBusy = false;
   private volatile boolean turnButtonVisible = false;
   private volatile boolean turnServerEnabled = false;
   private volatile boolean turnStatusChecked = false;
   private volatile long turnStatusCheckedAt = 0L;
   private final boolean modSyncChecked;
   private boolean modSyncChecking = false;

   public AttemptingJoinScreen(Screen parent, String roomCode, String password) {
      this(parent, roomCode, password, false);
   }

   public AttemptingJoinScreen(Screen parent, String roomCode, String password, boolean modSyncChecked) {
      super(Component.translatable("voxlink.attempting_join"));
      this.parent = parent;
      this.roomCode = roomCode != null ? roomCode : "";
      this.password = password;
      this.modSyncChecked = modSyncChecked;
   }

   public Screen entryScreen() {
      return this.parent;
   }

   @Override
   protected void init() {
      UiLogBus.attach();
      super.init();
      RoomInfo room = VoxLinkMod.getRoomManager().getCurrentRoom();
      boolean bridgeReady = room != null && room.getLocalBridgePort() > 0 && ConnectionHelper.isMcTrulyConnected();
      if (bridgeReady) {
         this.active = false;
         room.setConnectionMode(Component.translatable("voxlink.connection.connected"));
         this.voxlinkFinal = true;
         this.voxlinkStatusText = Component.translatable(room.isUsingRelay() ? "voxlink.relay.connected_via" : "voxlink.dual.p2p_established").getString();
         this.voxlinkStatusColor = -11141291;
      } else if (room != null && room.isConnectionFailed()) {
         this.active = false;
         this.voxlinkFinal = true;
         this.voxlinkStatusText = Component.translatable("voxlink.connection.all_failed").getString();
         this.voxlinkStatusColor = -43691;
      } else if (room != null && room.getLocalBridgePort() > 0) {
         this.voxlinkStatusText = Component.translatable("voxlink.connection.bridge_setup").getString();
         this.voxlinkStatusColor = -171;
      }

      int centerX = this.width / 2;
      int btnY = this.height / 2 + 45;
      boolean gatePending = (this.modSyncChecking || !this.joinApiDone)
         && !this.modSyncChecked
         && ModSyncGuestService.isEnabled()
         && RoomCodeRouter.isVoxLinkCode(this.roomCode)
         && !ModSyncGuestService.shouldSkipGate(this.roomCode);
      if (!bridgeReady) {
         if (this.joinApiDone && !this.active) {
            this.addRenderableWidget(
               Button.builder(Component.translatable("voxlink.back"), button -> this.goBack()).bounds(centerX - 100, btnY, 200, 20).build()
            );
         } else if (gatePending) {
            this.addRenderableWidget(
               Button.builder(Component.translatable("voxlink.modsync.scope_required"), button -> this.onScopePicked("required"))
                  .bounds(centerX - 100, btnY - 26, 200, 20)
                  .build()
            );
            this.addRenderableWidget(
               Button.builder(Component.translatable("voxlink.modsync.scope_all"), button -> this.onScopePicked("all"))
                  .bounds(centerX - 100, btnY - 2, 200, 20)
                  .build()
            );
            this.addRenderableWidget(
               Button.builder(Component.translatable("voxlink.modsync.skip_check"), button -> this.onSkipCheckClicked())
                  .bounds(centerX - 100, btnY + 22, 200, 20)
                  .build()
            );
            this.addRenderableWidget(
               Button.builder(Component.translatable("voxlink.cancel"), button -> this.cancelJoin()).bounds(centerX - 100, btnY + 46, 200, 20).build()
            );
         } else {
            this.addRenderableWidget(
               Button.builder(Component.translatable("voxlink.cancel"), button -> this.cancelJoin()).bounds(centerX - 100, btnY, 200, 20).build()
            );
            if (this.active && VoxLinkMod.getRoomManager().getConnectionManager().canShowRelayButton()) {
               this.relayButtonVisible = true;
               this.addRenderableWidget(
                  Button.builder(Component.translatable("voxlink.relay.use_player_relay"), button -> this.onRelayButtonClicked())
                     .bounds(this.width - 104, 4, 100, 20)
                     .build()
               );
            } else if (this.active && VoxLinkMod.getRoomManager().getConnectionManager().isManualRelayInProgress()) {
               this.relayButtonVisible = true;
               this.addRenderableWidget(
                  Button.builder(Component.translatable("voxlink.relay.cancel"), button -> this.onCancelRelayClicked())
                     .bounds(this.width - 104, 4, 100, 20)
                     .build()
               );
            } else {
               this.relayButtonVisible = false;
            }
         }
      }

      if (!bridgeReady && this.active && this.turnButtonVisible) {
         this.addRenderableWidget(
            Button.builder(Component.translatable("voxlink.turn.use"), button -> this.onTurnButtonClicked()).bounds(this.width - 104, 26, 100, 20).build()
         );
      }

      if (!bridgeReady) {
         this.addRenderableWidget(
            Button.builder(Component.translatable(this.logPanelOpen ? "voxlink.log.panel_hide" : "voxlink.log.panel_show"), button -> this.toggleLogPanel())
               .bounds(this.width - 88, this.height - 32, 82, 18)
               .build()
         );
      }

      if (!this.joinApiDone) {
         this.joinApiDone = true;
         this.startJoin();
      }
   }

   private void toggleLogPanel() {
      this.logPanelOpen = !this.logPanelOpen;
      if (this.logPanelOpen) {
         this.logScrollRows = 0;
         this.logFollowTail = true;
      }

      this.clearOurWidgets();
      this.init();
   }

   public boolean shouldCloseOnEsc() {
      return !this.active;
   }

   public void onClose() {
      UiLogBus.detach();
      if (this.active) {
         this.cancelJoin();
      } else {
         this.goBack();
      }
   }

   private void goBack() {
      if (VoxLinkMod.getRoomManager().getCurrentRoom() != null) {
         VoxLinkMod.getRoomManager().leaveRoom("返回上一界面");
      }

      Minecraft.getInstance().gui.setScreen(this.parent);
   }

   private void cancelJoin() {
      if (this.modSyncChecking) {
         ModSyncGuestService.bypass(this.roomCode);
         this.modSyncChecking = false;
      }

      LogUploadManager.disarm();
      this.active = false;
      VoxLinkMod.getRoomManager().leaveRoom("取消加入");
      Minecraft.getInstance().gui.setScreen(this.parent);
   }

   private void onRelayButtonClicked() {
      if (VoxLinkMod.getConfig().isRelayEnabled()) {
         VoxLinkMod.getRoomManager().getConnectionManager().triggerManualRelay();
         this.relayButtonVisible = false;
         this.lastManualRelayInProgress = true;
         this.voxlinkStatusText = Component.translatable("voxlink.relay.trying").getString();
         this.voxlinkStatusColor = -171;
         this.voxlinkStatusLastUpdate = System.currentTimeMillis();
         this.clearOurWidgets();
         this.init();
      }
   }

   private void onCancelRelayClicked() {
      VoxLinkMod.getRoomManager().getConnectionManager().cancelManualRelay();
      this.lastManualRelayInProgress = false;
      this.clearOurWidgets();
      this.init();
   }

   private void onTurnButtonClicked() {
      VoxLinkMod.getRoomManager().getConnectionManager().triggerTurnRelay();
      this.voxlinkStatusText = Component.translatable("voxlink.turn.connecting").getString();
      this.voxlinkStatusColor = -171;
      this.voxlinkStatusLastUpdate = System.currentTimeMillis();
      this.clearOurWidgets();
      this.init();
   }

   private void startJoin() {
      this.active = true;
      this.joinInitiated = true;
      LogUploadManager.arm(this.roomCode, false);
      if (!this.modSyncChecked
         && ModSyncGuestService.isEnabled()
         && RoomCodeRouter.isVoxLinkCode(this.roomCode)
         && !ModSyncGuestService.shouldSkipGate(this.roomCode)) {
         this.modSyncChecking = true;
         VoxLinkMod.LOGGER.info("[ModSync] gate: waiting for scope selection, room={}", this.roomCode);
         this.voxlinkStatusText = Component.translatable("voxlink.modsync.scope_hint").getString();
         this.voxlinkStatusColor = -171;
         this.clearOurWidgets();
         this.init();
      } else {
         this.doStartDualP2P();
      }
   }

   private void onScopePicked(String scope) {
      if (this.modSyncChecking) {
         this.voxlinkStatusText = Component.translatable("all".equals(scope) ? "voxlink.modsync.checking_all" : "voxlink.modsync.checking").getString();
         this.voxlinkStatusColor = -171;
         Minecraft mc0 = Minecraft.getInstance();
         ModSyncGuestService.gate(
            this.roomCode,
            scope,
            () -> mc0.execute(() -> mc0.gui.setScreen(new AttemptingJoinScreen(this.parent, this.roomCode, this.password, true))),
            () -> mc0.execute(() -> {
                  if (VoxLinkMod.getRoomManager().getCurrentRoom() != null) {
                     VoxLinkMod.getRoomManager().leaveRoom("清单检查取消");
                  }

                  mc0.gui.setScreen(this.parent);
               })
         );
      }
   }

   private void onSkipCheckClicked() {
      if (this.modSyncChecking) {
         this.modSyncChecking = false;
         ModSyncGuestService.bypass(this.roomCode);
         this.voxlinkStatusText = "";
         this.clearOurWidgets();
         this.init();
         this.doStartDualP2P();
      }
   }

   private void doStartDualP2P() {
      Minecraft mc = Minecraft.getInstance();
      String playerName = mc.getUser().getName();
      CompletableFuture<Void> joinFuture = VoxLinkMod.getRoomManager()
         .getConnectionManager()
         .startDualP2P(this.roomCode, playerName, this.password, (channel, statusKey) -> mc.execute(() -> {
               if (mc.gui.screen() == this) {
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
            if (mc.gui.screen() == this) {
            }
         })).exceptionally(e -> {
         mc.execute(() -> {
            if (mc.gui.screen() == this) {
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
      ModSyncGuestService.pushPendingGateNote();
   }

   private int colorForStatus(String statusKey) {
      if (statusKey == null) {
         return -5592406;
      } else if (statusKey.endsWith(".connected") || statusKey.endsWith(".p2p_established")) {
         return -11141291;
      } else if (!statusKey.endsWith(".all_failed") && !statusKey.endsWith(".channel_failed")) {
         return statusKey.endsWith(".status_cancelled") ? -5592406 : -171;
      } else {
         return -43691;
      }
   }

   private void onFailed(String msg) {
      ModSyncGuestService.clearGateFor(this.roomCode);
      this.voxlinkFinal = true;
      this.voxlinkStatusText = msg;
      this.voxlinkStatusColor = -43691;
      this.active = false;
      this.stopConnectionMonitor();
      VoxLinkMod.getRoomManager().getConnectionManager().killAllConnectionAttempts();
      this.clearOurWidgets();
      this.init();
   }

   public void onRoomLost() {
      this.stopConnectionMonitor();
      this.active = false;
      this.voxlinkFinal = true;
      this.voxlinkStatusText = Component.translatable("voxlink.room_lost").getString();
      this.voxlinkStatusColor = -43691;
      Minecraft mc = Minecraft.getInstance();
      mc.execute(() -> {
         if (mc.gui.screen() == this) {
            this.clearOurWidgets();
            this.init();
         }
      });
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
         {
            Objects.requireNonNull(AttemptingJoinScreen.this);
         }

         @Override
         public void run() {
            if (monitorActive.get()) {
               try {
                  Minecraft mc = Minecraft.getInstance();
                  AttemptingJoinScreen.this.monitorTicks++;
                  RoomInfo roomInfo = VoxLinkMod.getRoomManager().getCurrentRoom();
                  if (roomInfo == null) {
                     if (!AttemptingJoinScreen.this.joinInitiated) {
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

                     if (!AttemptingJoinScreen.this.joinCompleted) {
                        if (AttemptingJoinScreen.this.joinNoResponseTicks < 0) {
                           AttemptingJoinScreen.this.joinNoResponseTicks = AttemptingJoinScreen.this.monitorTicks;
                        }

                        if (AttemptingJoinScreen.this.monitorTicks - AttemptingJoinScreen.this.joinNoResponseTicks >= 60) {
                           monitorActive.set(false);
                           mc.execute(() -> {
                              if (mc.gui.screen() == AttemptingJoinScreen.this) {
                                 AttemptingJoinScreen.this.onFailed(Component.translatable("voxlink.connection.join_no_response").getString());
                              }
                           });
                           if (AttemptingJoinScreen.this.connectionScheduler != null && !AttemptingJoinScreen.this.connectionScheduler.isShutdown()) {
                              AttemptingJoinScreen.this.connectionScheduler.shutdownNow();
                           }

                           return;
                        }

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
                        if (mc.gui.screen() == AttemptingJoinScreen.this) {
                           AttemptingJoinScreen.this.onFailed(Component.translatable("voxlink.room_lost").getString());
                        }
                     });
                     if (AttemptingJoinScreen.this.connectionScheduler != null && !AttemptingJoinScreen.this.connectionScheduler.isShutdown()) {
                        AttemptingJoinScreen.this.connectionScheduler.shutdownNow();
                     }

                     return;
                  }

                  AttemptingJoinScreen.this.joinNoResponseTicks = -1;
                  if (roomInfo.getLocalBridgePort() > 0) {
                     if (AttemptingJoinScreen.this.bridgeEstablishedAtTick < 0) {
                        AttemptingJoinScreen.this.bridgeEstablishedAtTick = AttemptingJoinScreen.this.monitorTicks;
                     }

                     if (ConnectionHelper.isMcTrulyConnected()) {
                        monitorActive.set(false);
                        ConnectionHelper.clearConnectInitiated();
                        roomInfo.setConnectionMode(Component.translatable("voxlink.connection.connected"));
                        mc.execute(
                           () -> {
                              if (mc.gui.screen() == AttemptingJoinScreen.this) {
                                 AttemptingJoinScreen.this.voxlinkFinal = true;
                                 AttemptingJoinScreen.this.voxlinkStatusText = Component.translatable(
                                       roomInfo.isUsingRelay() ? "voxlink.relay.connected_via" : "voxlink.dual.p2p_established"
                                    )
                                    .getString();
                                 AttemptingJoinScreen.this.voxlinkStatusColor = -11141291;
                                 AttemptingJoinScreen.this.active = false;
                              }
                           }
                        );
                        if (AttemptingJoinScreen.this.connectionScheduler != null && !AttemptingJoinScreen.this.connectionScheduler.isShutdown()) {
                           AttemptingJoinScreen.this.connectionScheduler.shutdownNow();
                        }

                        return;
                     }

                     if (ConnectionHelper.isConnectionRejected()) {
                        monitorActive.set(false);
                        ConnectionHelper.clearConnectInitiated();
                        mc.execute(() -> {
                           if (mc.gui.screen() == AttemptingJoinScreen.this) {
                              AttemptingJoinScreen.this.onFailed(Component.translatable("voxlink.connection.all_failed").getString());
                           }
                        });
                        if (AttemptingJoinScreen.this.connectionScheduler != null && !AttemptingJoinScreen.this.connectionScheduler.isShutdown()) {
                           AttemptingJoinScreen.this.connectionScheduler.shutdownNow();
                        }

                        return;
                     }

                     mc.execute(() -> {
                        if (mc.gui.screen() == AttemptingJoinScreen.this && !AttemptingJoinScreen.this.voxlinkFinal) {
                           AttemptingJoinScreen.this.voxlinkStatusText = Component.translatable("voxlink.connection.bridge_setup").getString();
                           AttemptingJoinScreen.this.voxlinkStatusColor = -171;
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
                        if (mc.gui.screen() == AttemptingJoinScreen.this) {
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
                           if (mc.gui.screen() == AttemptingJoinScreen.this && !AttemptingJoinScreen.this.voxlinkFinal) {
                              long now = System.currentTimeMillis();
                              String newText = connMode.getString();
                              if (!newText.equals(AttemptingJoinScreen.this.voxlinkStatusText)
                                 && now - AttemptingJoinScreen.this.voxlinkStatusLastUpdate < 2000L) {
                                 return;
                              }

                              AttemptingJoinScreen.this.voxlinkStatusText = newText;
                              AttemptingJoinScreen.this.voxlinkStatusColor = -171;
                              AttemptingJoinScreen.this.voxlinkStatusLastUpdate = now;
                           }
                        }
                     );
                  }

                  boolean shouldShowRelay = AttemptingJoinScreen.this.active && VoxLinkMod.getRoomManager().getConnectionManager().canShowRelayButton();
                  if (shouldShowRelay != AttemptingJoinScreen.this.relayButtonVisible) {
                     mc.execute(() -> {
                        if (mc.gui.screen() == AttemptingJoinScreen.this) {
                           AttemptingJoinScreen.this.clearOurWidgets();
                           AttemptingJoinScreen.this.init();
                        }
                     });
                  }

                  ConnectionManager cmTurn = VoxLinkMod.getRoomManager().getConnectionManager();
                  long punchStartMs = cmTurn.getPunchUiStartMs();
                  long punchMs = punchStartMs > 0L ? System.currentTimeMillis() - punchStartMs : 0L;
                  if (punchMs >= 20000L
                     && (!AttemptingJoinScreen.this.turnStatusChecked || System.currentTimeMillis() - AttemptingJoinScreen.this.turnStatusCheckedAt > 30000L)) {
                     AttemptingJoinScreen.this.turnStatusChecked = true;
                     AttemptingJoinScreen.this.turnStatusCheckedAt = System.currentTimeMillis();
                     TurnRelayClient.fetchStatus(VoxLinkMod.getRoomManager().getSignalingClient())
                        .thenAccept(ok -> AttemptingJoinScreen.this.turnServerEnabled = ok);
                  }

                  boolean shouldShowTurn = AttemptingJoinScreen.this.active
                     && roomInfo != null
                     && !roomInfo.isHost()
                     && punchMs >= 20000L
                     && AttemptingJoinScreen.this.turnServerEnabled
                     && !cmTurn.isTurnActive()
                     && !cmTurn.isTurnInProgress()
                     && !cmTurn.isTurnP2pGivenUp()
                     && !cmTurn.isManualRelayInProgress();
                  if (shouldShowTurn != AttemptingJoinScreen.this.turnButtonVisible) {
                     AttemptingJoinScreen.this.turnButtonVisible = shouldShowTurn;
                     mc.execute(() -> {
                        if (mc.gui.screen() == AttemptingJoinScreen.this) {
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
                  boolean turnBusyNow = cmTurn.isTurnInProgress() || cmTurn.isTurnActive();
                  if (AttemptingJoinScreen.this.lastTurnBusy && !turnBusyNow) {
                     AttemptingJoinScreen.this.turnFailedMsgTime = System.currentTimeMillis();
                  }

                  AttemptingJoinScreen.this.lastTurnBusy = turnBusyNow;
                  if (AttemptingJoinScreen.this.connectionFuture != null) {
                     AttemptingJoinScreen.this.connectionFuture.cancel(false);
                  }

                  if (monitorActive.get()
                     && AttemptingJoinScreen.this.connectionScheduler != null
                     && !AttemptingJoinScreen.this.connectionScheduler.isShutdown()) {
                     AttemptingJoinScreen.this.connectionFuture = AttemptingJoinScreen.this.connectionScheduler
                        .schedule(() -> mc.execute(this), 500L, TimeUnit.MILLISECONDS);
                  }
               } catch (Exception var17) {
                  Minecraft mcx = Minecraft.getInstance();
                  monitorActive.set(false);
                  mcx.execute(() -> {
                     if (mc.gui.screen() == AttemptingJoinScreen.this) {
                        AttemptingJoinScreen.this.onFailed(Component.translatable("voxlink.connection.monitor_error").getString());
                     }
                  });
               }
            }
         }
      };
      this.connectionFuture = this.connectionScheduler.schedule(() -> Minecraft.getInstance().execute(monitor), 500L, TimeUnit.MILLISECONDS);
   }

   public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
      super.extractRenderState(graphics, mouseX, mouseY, partialTick);
      this.drawLogPanel(graphics);
      this.renderNatOverlay(graphics);
      int centerX = this.width / 2;
      this.drawCenteredString(graphics, this.title.getString(), centerX, 15, -1);
      this.drawCenteredString(
         graphics,
         ChatFormatting.YELLOW.toString() + ChatFormatting.BOLD.toString() + Component.translatable("voxlink.chat.room_code_label").getString().trim(),
         centerX,
         this.height / 2 - 30,
         -171
      );
      if (this.relayFailedMsgTime > 0L) {
         long elapsed = System.currentTimeMillis() - this.relayFailedMsgTime;
         if (elapsed < 3000L) {
            this.drawCenteredString(graphics, Component.translatable("voxlink.relay.failed_retry_punch").getString(), centerX, this.height / 2 - 30 - 12, -171);
         } else {
            this.relayFailedMsgTime = 0L;
         }
      }

      if (this.turnFailedMsgTime > 0L) {
         long elapsedTurn = System.currentTimeMillis() - this.turnFailedMsgTime;
         if (elapsedTurn < 3000L) {
            this.drawCenteredString(graphics, Component.translatable("voxlink.turn.failed").getString(), centerX, this.height / 2 - 30 - 12, -171);
         } else {
            this.turnFailedMsgTime = 0L;
         }
      }

      String latest = this.modSyncChecking ? "" : UiLogBus.latestMessage();
      int lv = UiLogBus.latestLevel();
      if (!latest.isEmpty()) {
         int lvColor = lv == 3 ? -43691 : (lv == 1 ? -11141291 : (lv == 2 ? -171 : this.voxlinkStatusColor));
         int maxWidth = this.width - 20;
         if (this.fontWidth(latest) > maxWidth) {
            while (this.fontWidth(latest + "...") > maxWidth && latest.length() > 0) {
               latest = latest.substring(0, latest.length() - 1);
            }

            latest = latest + "...";
         }

         this.drawCenteredString(graphics, latest, centerX, this.height / 2 + 0, lvColor);
      } else if (!this.voxlinkStatusText.isEmpty()) {
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

      ConnectionManager cm = VoxLinkMod.getRoomManager().getConnectionManager();
      Component turnStatus = cm != null ? cm.getTurnStatusText() : null;
      Component relayProgress = cm != null ? cm.getRelayProgressText() : null;
      String line2 = null;
      int line2Color = -171;
      if (turnStatus != null) {
         line2 = turnStatus.getString();
         line2Color = cm.isTurnActive() ? -11141291 : -171;
      } else if (relayProgress != null) {
         line2 = relayProgress.getString();
      } else if (!this.terracottaStatusText.isEmpty()) {
         line2 = Component.translatable("voxlink.dual.terracotta_label").getString() + ": " + this.terracottaStatusText;
         line2Color = this.terracottaStatusColor;
      }

      if (line2 != null && !line2.isEmpty()) {
         String clipped = line2;
         int maxWidth = this.width - 20;
         if (this.fontWidth(line2) > maxWidth) {
            while (this.fontWidth(clipped + "...") > maxWidth && clipped.length() > 0) {
               clipped = clipped.substring(0, clipped.length() - 1);
            }

            clipped = clipped + "...";
         }

         this.drawCenteredString(graphics, clipped, centerX, this.height / 2 + 14, line2Color);
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

      this.drawString(graphics, tipText, 4, this.height - 12, -5592406);
      if (LogUploadManager.isUploadFinished()) {
         String uploadText = Component.translatable("voxlink.log_upload.uploaded").getString();
         int uploadWidth = this.fontWidth(uploadText);
         this.drawString(graphics, uploadText, this.width - uploadWidth - 6, this.height - 12, -11141291);
      }
   }

   public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
      if (!this.logPanelOpen) {
         return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
      } else {
         if (this.logPanelHovered(mouseX, mouseY)) {
            int visible = Math.max(1, (this.logPanelHeight() - 6) / 10);
            int total = this.logLines.size();
            if (scrollY > 0.0) {
               this.logScrollRows = Math.min(Math.max(0, total - visible), this.logScrollRows + 3);
               this.logFollowTail = this.logScrollRows <= 0;
               return true;
            }

            if (scrollY < 0.0) {
               this.logScrollRows = Math.max(0, this.logScrollRows - 3);
               this.logFollowTail = this.logScrollRows == 0;
               return true;
            }
         }

         return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
      }
   }

   private void drawLogPanel(GuiGraphicsExtractor graphics) {
      if (this.logPanelOpen) {
         UiLogBus.snapshot(this.logLines, this.logLevels);
         long ver = UiLogBus.version();
         if (ver != this.logSeenVersion) {
            this.logSeenVersion = ver;
            if (this.logFollowTail) {
               this.logScrollRows = 0;
            }
         }

         int x = this.logPanelX();
         int y = this.logPanelY();
         int w = this.logPanelWidth();
         int h = this.logPanelHeight();
         int visible = Math.max(1, (h - 6) / 10);
         int total = this.logLines.size();
         int start = Math.max(0, total - visible - this.logScrollRows);
         int end = Math.min(total, start + visible);
         graphics.fill(x, y, x + w, y + h, -1877995496);
         graphics.fill(x, y, x + w, y + 1, -12961195);
         graphics.fill(x, y + h - 1, x + w, y + h, -12961195);
         graphics.fill(x, y, x + 1, y + h, -12961195);
         graphics.fill(x + w - 1, y, x + w, y + h, -12961195);
         int row = y + 3;

         for (int i = start; i < end; i++) {
            String line = this.logLines.get(i);
            int color = this.logLevels.get(i) == 2 ? -33934 : (this.logLevels.get(i) == 1 ? -11393 : -4670264);

            while (this.font.width(line) > w - 8 && line.length() > 1) {
               line = line.substring(0, line.length() - 1);
            }

            graphics.text(this.font, line, x + 4, row, color);
            row += 10;
         }
      }
   }

   private int logPanelX() {
      return this.width - this.logPanelWidth() - 6;
   }

   private int logPanelY() {
      return 44;
   }

   private int logPanelWidth() {
      int w = this.width - (this.width / 2 + 118) - 6;
      return Math.max(110, Math.min(340, w));
   }

   private int logPanelHeight() {
      return Math.max(60, this.height - 44 - 40);
   }

   private boolean logPanelHovered(double mouseX, double mouseY) {
      return mouseX >= (double)this.logPanelX()
         && mouseX < (double)(this.logPanelX() + this.logPanelWidth())
         && mouseY >= (double)this.logPanelY()
         && mouseY < (double)(this.logPanelY() + this.logPanelHeight());
   }

   private void renderNatOverlay(GuiGraphicsExtractor graphics) {
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
      this.drawString(graphics, Component.translatable("voxlink.nat.label_opponent").getString() + ": " + opponentText, x, y, -5592406);
      this.drawString(graphics, Component.translatable("voxlink.nat.label_mine").getString() + ": " + mineText, x, y + line, -5592406);
      this.drawString(graphics, Component.translatable("voxlink.nat.label_difficulty").getString() + ": " + difficultyText, x, y + line * 2, -171);
      RoomInfo ri = VoxLinkMod.getRoomManager() != null ? VoxLinkMod.getRoomManager().getCurrentRoom() : null;
      if (ri != null) {
         String hm = ri.getHostModVersion();
         String shown = hm.isEmpty() ? Component.translatable("voxlink.host_version_unknown").getString() : hm;
         this.drawString(graphics, Component.translatable("voxlink.host_version_label").getString() + ": " + shown, x, y + line * 3, -5592406);
      }
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
