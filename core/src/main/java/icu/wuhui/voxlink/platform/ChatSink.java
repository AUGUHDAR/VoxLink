package icu.wuhui.voxlink.platform;

public final class ChatSink {
   private ChatSink() {
   }

   /** 聊天栏系统消息; 未注入 no-op。 */
   public static void chat(Text msg) {
      if (msg == null) {
         return;
      }
      try {
         Platform.host().chatSink().chat(msg);
      } catch (Throwable ignored) {
      }
   }

   public static void actionBar(Text msg) {
      if (msg == null) {
         return;
      }
      try {
         Platform.host().chatSink().actionBar(msg);
      } catch (Throwable ignored) {
      }
   }
}
