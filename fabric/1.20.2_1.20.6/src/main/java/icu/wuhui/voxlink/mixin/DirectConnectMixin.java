package icu.wuhui.voxlink.mixin;

import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.room.RoomInfo;
import icu.wuhui.voxlink.room.RoomManager;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.DirectJoinServerScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DirectJoinServerScreen.class)
public abstract class DirectConnectMixin extends Screen {
   private static final Pattern ROOM_CODE_PATTERN = Pattern.compile("^[A-HJ-NP-Z2-9]{6}$");
   @Shadow
   private EditBox ipEdit;
   @Unique
   private volatile ScheduledExecutorService monitorScheduler;
   @Unique
   private volatile ScheduledFuture<?> monitorFuture;
   @Unique
   private volatile int monitorTicks = 0;
   @Unique
   private static final int MAX_MONITOR_TICKS = 90;
   @Unique
   private static final int MONITOR_POLL_SEC = 1;
   @Unique
   private final AtomicBoolean monitorDone = new AtomicBoolean(false);

   protected DirectConnectMixin(Component title) {
      super(title);
   }

   @Inject(method = "onSelect", at = @At("HEAD"), cancellable = true, require = 0)
   private void onDirectConnect(CallbackInfo ci) {
      String address = this.ipEdit.getValue().trim().toUpperCase();
      if (!address.contains(".") && !address.contains(":")) {
         if (ROOM_CODE_PATTERN.matcher(address).matches()) {
            ci.cancel();
            Minecraft mc = Minecraft.getInstance();
            String code = address;
            RoomManager rm = VoxLinkMod.getRoomManager();
            if (rm == null) {
               if (mc.player != null) {
                  mc.player.sendSystemMessage(Component.translatable("voxlink.error.not_available"));
               }
            } else if (rm.isInRoom()) {
               if (mc.player != null) {
                  mc.player.sendSystemMessage(Component.translatable("voxlink.chat.already_in_room_leave_first"));
               }
            } else {
               rm.joinRoom(code, null).thenAccept(roomInfo -> mc.execute(() -> {
                  if (!(mc.screen instanceof DirectJoinServerScreen)) {
                     VoxLinkMod.getRoomManager().leaveRoom();
                  } else {
                     if (mc.player != null) {
                        mc.player.sendSystemMessage(Component.translatable("voxlink.chat.joined_waiting_host"));
                     }

                     this.startDirectConnectMonitor(mc);
                  }
               })).exceptionally(e -> {
                  mc.execute(() -> {
                     if (mc.screen instanceof DirectJoinServerScreen) {
                        Throwable cause = e;

                        while (cause.getCause() != null) {
                           cause = cause.getCause();
                        }

                        String msg = cause.getMessage();
                        if (msg == null) {
                           msg = Component.translatable("voxlink.error.unknown").getString();
                        }

                        if (msg.contains("ROOM_NOT_FOUND")) {
                           msg = Component.translatable("voxlink.error.room_not_found").getString();
                        } else if (msg.contains("ROOM_FULL")) {
                           msg = Component.translatable("voxlink.error.room_full").getString();
                        } else if (msg.contains("WRONG_PASSWORD")) {
                           msg = Component.translatable("voxlink.error.wrong_password").getString();
                        }

                        if (mc.player != null) {
                           mc.player.sendSystemMessage(Component.translatable("voxlink.chat.error", new Object[]{msg}));
                        }
                     }
                  });
                  return null;
               });
            }
         }
      }
   }

   private synchronized void startDirectConnectMonitor(final Minecraft mc) {
      if (this.monitorScheduler == null || this.monitorScheduler.isShutdown()) {
         this.monitorScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "VoxLink-DirectConnect-Monitor");
            t.setDaemon(true);
            t.setUncaughtExceptionHandler((thread, ex) -> VoxLinkMod.LOGGER.error("Monitor thread crashed", ex));
            return t;
         });
      }

      this.monitorTicks = 0;
      this.monitorDone.set(false);
      Runnable monitor = new Runnable() {
         @Override
         public void run() {
            if (!DirectConnectMixin.this.monitorDone.get()) {
               DirectConnectMixin.this.monitorTicks++;
               RoomInfo roomInfo = VoxLinkMod.getRoomManager().getCurrentRoom();
               if (roomInfo == null) {
                  if (DirectConnectMixin.this.monitorDone.compareAndSet(false, true)) {
                     mc.execute(
                        () -> {
                           if (mc.player != null) {
                              mc.player
                                 .sendSystemMessage(
                                    Component.translatable(
                                       "voxlink.chat.connection_failed_detail",
                                       new Object[]{Component.translatable("voxlink.connection.all_failed").getString()}
                                    )
                                 );
                           }

                           VoxLinkMod.getRoomManager().leaveRoom();
                        }
                     );
                  }
               } else if (roomInfo.getLocalBridgePort() > 0) {
                  if (DirectConnectMixin.this.monitorDone.compareAndSet(false, true)) {
                     mc.execute(() -> {
                        if (mc.player != null) {
                           mc.player.sendSystemMessage(Component.translatable("voxlink.chat.connected_entering_game"));
                        }
                     });
                  }
               } else {
                  boolean persistentRetrying = VoxLinkMod.getRoomManager().getConnectionManager().isPersistentRetrying();
                  if (roomInfo.isConnectionFailed() || DirectConnectMixin.this.monitorTicks >= 90 && !persistentRetrying) {
                     if (DirectConnectMixin.this.monitorDone.compareAndSet(false, true)) {
                        mc.execute(
                           () -> {
                              if (mc.player != null) {
                                 Component connMode = roomInfo.getConnectionMode();
                                 String reason = roomInfo.isConnectionFailed() && connMode != null
                                    ? connMode.getString()
                                    : Component.translatable("voxlink.connection.timeout_retry").getString();
                                 if (reason == null || reason.isEmpty()) {
                                    reason = Component.translatable("voxlink.connection.all_failed").getString();
                                 }

                                 mc.player.sendSystemMessage(Component.translatable("voxlink.chat.connection_failed_detail", new Object[]{reason}));
                              }

                              VoxLinkMod.getRoomManager().leaveRoom("连接失败");
                           }
                        );
                     }
                  } else {
                     if (!DirectConnectMixin.this.monitorDone.get()
                        && DirectConnectMixin.this.monitorScheduler != null
                        && !DirectConnectMixin.this.monitorScheduler.isShutdown()) {
                        DirectConnectMixin.this.monitorFuture = DirectConnectMixin.this.monitorScheduler.schedule(() -> mc.execute(this), 1L, TimeUnit.SECONDS);
                     }
                  }
               }
            }
         }
      };
      this.monitorFuture = this.monitorScheduler.schedule(() -> mc.execute(monitor), 1L, TimeUnit.SECONDS);
   }

   @Inject(method = "removed", at = @At("HEAD"))
   private void onRemoved(CallbackInfo ci) {
      this.monitorDone.set(true);
      if (this.monitorFuture != null) {
         this.monitorFuture.cancel(false);
         this.monitorFuture = null;
      }

      if (this.monitorScheduler != null) {
         this.monitorScheduler.shutdownNow();
         this.monitorScheduler = null;
      }
   }
}
