package icu.wuhui.voxlink.platform;

import java.util.List;
import java.util.UUID;

/** 单人集成服务器/OP 策略抽象(批 3 接线用骨架, 本批不注入)。 */
public final class ServerHandle {
   private ServerHandle() {
   }

   public static ServerRef localServer() {
      try {
         return Platform.host().serverHandle() instanceof Provider p ? p.localServer() : null;
      } catch (Throwable t) {
         return null;
      }
   }

   public interface ServerRef {
      void executeOnServerThread(Runnable r);

      int playerCount();

      List<PlayerRef> players();

      boolean performPrefixedCommand(String cmd);
   }

   public interface PlayerRef {
      String name();

      UUID uuid();
   }

   /** loader 端 Provider 实现此接口并注入 Host.serverHandle()。 */
   public interface Provider {
      ServerRef localServer();
   }
}
