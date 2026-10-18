package icu.wuhui.voxlink.platform;

public final class ClientThread {
   private ClientThread() {
   }

   public static boolean isOnClientThread() {
      try {
         return Platform.host().clientThread().isOnClientThread();
      } catch (Throwable t) {
         return true;
      }
   }

   /** 未注入→调用线程直跑, 与迁移前 NoClassDefFoundError 兜底语义一致。 */
   public static void runOnClientThread(Runnable r) {
      if (r == null) {
         return;
      }
      try {
         Platform.host().clientThread().runOnClientThread(r);
      } catch (Throwable t) {
         r.run();
      }
   }
}
