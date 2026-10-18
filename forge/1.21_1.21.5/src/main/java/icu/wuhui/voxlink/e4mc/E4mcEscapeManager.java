package icu.wuhui.voxlink.e4mc;

import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.ui.ChatCompat;
import icu.wuhui.voxlink.ui.UiLogBus;
import icu.wuhui.voxlink.ui.VoxLinkColors;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * 公共中继逃生通道入口: 玩家手动触发, 绝不自动。
 * childHandler/group 由 E4mcServerConnectionMixin 在开服时捕获。
 */
public final class E4mcEscapeManager {
   private static volatile E4mcEscapeSession session;
   private static volatile Object capturedHandler;
   private static volatile Object capturedGroup;

   private E4mcEscapeManager() {
   }

   public static boolean hasServerCapture() {
      return capturedHandler != null && capturedGroup != null;
   }

   public static void onServerChannelReady(Object childHandler, Object eventLoopGroup) {
      capturedHandler = childHandler;
      capturedGroup = eventLoopGroup;
   }

   public static synchronized void onServerStopped() {
      E4mcEscapeSession s = session;
      if (s != null && s.isRunning()) {
         s.stop();
      }

      session = null;
      capturedHandler = null;
      capturedGroup = null;
   }

   public static synchronized boolean isActive() {
      E4mcEscapeSession s = session;
      return s != null && s.isRunning();
   }

   public static synchronized void start() {
      if (isActive()) {
         return;
      }

      if (!hasServerCapture()) {
         notify("voxlink.e4mc.no_world", ChatFormatting.RED);
         return;
      }

      notify("voxlink.e4mc.starting", ChatFormatting.YELLOW);
      UiLogBus.push(1, "voxlink.e4mc.starting");
      Minecraft mc = Minecraft.getInstance();
      E4mcEscapeSession s = new E4mcEscapeSession(
         (io.netty.channel.ChannelHandler) capturedHandler,
         (io.netty.channel.EventLoopGroup) capturedGroup,
         domain -> mc.execute(() -> {
            notifyDomain(domain);
            UiLogBus.push(1, "voxlink.e4mc.domain_assigned", domain);
         }),
         message -> mc.execute(() -> notifyBroadcast(message))
      );
      session = s;
      s.start();
      watchFailure(s);
   }

   public static synchronized void stop() {
      E4mcEscapeSession s = session;
      if (s != null) {
         s.stop();
         notify("voxlink.e4mc.stopped", ChatFormatting.GRAY);
         UiLogBus.push(1, "voxlink.e4mc.stopped");
      }

      session = null;
   }

   private static void watchFailure(E4mcEscapeSession s) {
      Thread t = new Thread(() -> {
         while (true) {
            E4mcEscapeSession.State st = s.state;
            if (st == E4mcEscapeSession.State.UNHEALTHY) {
               Minecraft mc = Minecraft.getInstance();
               mc.execute(() -> {
                  notify("voxlink.e4mc.failed", ChatFormatting.RED);
                  UiLogBus.push(3, "voxlink.e4mc.failed");
               });
               if (session == s) {
                  session = null;
               }
               return;
            }

            if (st == E4mcEscapeSession.State.STOPPED || st == E4mcEscapeSession.State.STOPPING) {
               return;
            }

            try {
               Thread.sleep(1000L);
            } catch (InterruptedException e) {
               return;
            }
         }
      }, "VoxLink-e4mc-watch");
      t.setDaemon(true);
      t.start();
   }

   private static void notifyDomain(String domain) {
      Minecraft mc = Minecraft.getInstance();
      if (mc.player == null) {
         return;
      }

      mc.player.displayClientMessage(
         Component.translatable("voxlink.e4mc.domain_assigned").withStyle(ChatFormatting.GREEN)
            .append(
               Component.literal(domain).withStyle(
                  ChatCompat.styleWithCopy(domain, Component.translatable("voxlink.chat.click_to_copy"))
                     .withColor(VoxLinkColors.SUCCESS_RGB)
               )
            )
      , false);
   }

   private static void notifyBroadcast(String message) {
      Minecraft mc = Minecraft.getInstance();
      if (mc.player != null && message != null && !message.isEmpty()) {
         mc.player.displayClientMessage(Component.literal(message), false);
      }
   }

   private static void notify(String key, ChatFormatting formatting) {
      Minecraft mc = Minecraft.getInstance();
      if (mc.player != null) {
         mc.player.displayClientMessage(Component.translatable(key).withStyle(formatting), false);
      }
   }
}
