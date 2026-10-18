package icu.wuhui.voxlink.platform;

/** 连接发起/连接态抽象(批 3 接线用骨架, 本批不注入)。core 内不得出现 Minecraft 类型。 */
public final class GameConnection {
   private GameConnection() {
   }

   public enum ScreenKind {
      ATTEMPTING_JOIN, CONNECT_SCREEN, DISCONNECTED, OTHER, NONE
   }

   public static boolean isConnected() {
      try {
         return Platform.host().gameConnection() instanceof Full fn && fn.isConnected();
      } catch (Throwable t) {
         return false;
      }
   }

   public static boolean playerInWorld() {
      try {
         return Platform.host().gameConnection() instanceof Full fn && fn.playerInWorld();
      } catch (Throwable t) {
         return false;
      }
   }

   public static ScreenKind currentScreenKind() {
      try {
         return Platform.host().gameConnection() instanceof Full fn ? fn.currentScreenKind() : ScreenKind.OTHER;
      } catch (Throwable t) {
         return ScreenKind.OTHER;
      }
   }

   /** modeTextForError 传 Text, 由 loader 端渲染, 签名与旧 connectToServer(localPort, roomInfo) 语义对应。 */
   public static void connect(String address, String name, int protocolVersion, Text modeTextForError) {
      try {
         if (Platform.host().gameConnection() instanceof Full fn) {
            fn.connect(address, name, protocolVersion, modeTextForError);
         }
      } catch (Throwable ignored) {
      }
   }

   /** loader 端完整实现此子接口; Host.gameConnection() 未实现 Full 时全部降级安全值。 */
   public interface Full {
      boolean isConnected();

      boolean playerInWorld();

      ScreenKind currentScreenKind();

      void connect(String address, String name, int protocolVersion, Text modeTextForError);
   }
}
