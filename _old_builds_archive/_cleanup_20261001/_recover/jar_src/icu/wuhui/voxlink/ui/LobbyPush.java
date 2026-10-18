package icu.wuhui.voxlink.ui;

public final class LobbyPush {
   private static volatile Runnable listener;

   private LobbyPush() {
   }

   public static void set(Runnable r) {
      listener = r;
   }

   public static void fire() {
      Runnable r = listener;
      if (r != null) {
         r.run();
      }
   }
}
