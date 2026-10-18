package icu.wuhui.voxlink.compat;

import java.lang.reflect.Method;
import net.minecraft.network.chat.Component;
import net.minecraft.client.player.LocalPlayer;

/** 跨版本玩家聊天桥: 优先 sendSystemMessage, 回退 displayClientMessage(false)。 */
public final class PlayerChat {
   private static volatile Method sendMsg;
   private static volatile Method displayMsg;
   private static boolean resolved;

   private PlayerChat() {
   }

   private static void resolve() {
      if (resolved) {
         return;
      }

      synchronized (PlayerChat.class) {
         if (resolved) {
            return;
         }

         try {
            sendMsg = LocalPlayer.class.getMethod("sendSystemMessage", Component.class);
         } catch (Throwable ignored) {
         }

         try {
            displayMsg = LocalPlayer.class.getMethod("displayClientMessage", Component.class, boolean.class);
         } catch (Throwable ignored) {
         }

         resolved = true;
      }
   }

   public static void send(LocalPlayer player, Component msg) {
      if (player == null || msg == null) {
         return;
      }

      try {
         resolve();
         Method m = sendMsg;
         if (m != null) {
            m.invoke(player, msg);
            return;
         }

         Method d = displayMsg;
         if (d != null) {
            d.invoke(player, msg, false);
         }
      } catch (Throwable ignored) {
      }
   }
}
