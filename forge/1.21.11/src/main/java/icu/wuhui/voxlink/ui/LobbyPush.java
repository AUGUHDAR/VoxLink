package icu.wuhui.voxlink.ui;

/**
 * 服务器 lobby 变更事件总线: WS 收到 push=lobby 时 fire,
 * 打开中的房间浏览器监听并做事件驱动刷新(无轮询)。
 */
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
