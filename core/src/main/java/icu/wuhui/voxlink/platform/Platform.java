package icu.wuhui.voxlink.platform;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 静态宿主: loader 于客户端初始化阶段 install 一次, core 内代码一律经 getter 取用。
 * 未注入时 getter 返回安全降级实现, 绝不抛异常(无头 Harness 依赖此语义)。
 */
public final class Platform {
   private static final AtomicReference<Host> HOST = new AtomicReference<>(null);

   private Platform() {
   }

   /** 一次性注入宿主; 可重复安装(热重载), 不允许设回 null。 */
   public static void install(Host host) {
      if (host == null) {
         throw new IllegalArgumentException("platform host must not be null");
      }
      HOST.set(host);
   }

   public static Host host() {
      Host h = HOST.get();
      return h != null ? h : Host.FALLBACK;
   }

   /** 宿主聚合接口: loader 端单类实现全部 getter。 */
   public interface Host {
      ClientThreadFn clientThread();

      ChatSinkFn chatSink();

      ClientInfoFn clientInfo();

      TextRendererFn textRenderer();

      GameConnectionFn gameConnection();

      ServerHandleFn serverHandle();

      RoomLostUiHookFn roomLostUiHook();

      Host FALLBACK = new Host() {
         @Override
         public ClientThreadFn clientThread() {
            return ClientThreadFn.DIRECT;
         }

         @Override
         public ChatSinkFn chatSink() {
            return ChatSinkFn.NOOP;
         }

         @Override
         public ClientInfoFn clientInfo() {
            return ClientInfoFn.DEFAULTS;
         }

         @Override
         public TextRendererFn textRenderer() {
            return null;
         }

         @Override
         public GameConnectionFn gameConnection() {
            return GameConnectionFn.EMPTY;
         }

         @Override
         public ServerHandleFn serverHandle() {
            return ServerHandleFn.EMPTY;
         }

         @Override
         public RoomLostUiHookFn roomLostUiHook() {
            return RoomLostUiHookFn.NOOP;
         }
      };
   }

   /** mc.isSameThread / mc.execute。DIRECT: 未注入→调用线程直跑(保持旧 NoClassDefFoundError 兜底等价)。 */
   public interface ClientThreadFn {
      boolean isOnClientThread();

      void runOnClientThread(Runnable r);

      ClientThreadFn DIRECT = new ClientThreadFn() {
         @Override
         public boolean isOnClientThread() {
            return true;
         }

         @Override
         public void runOnClientThread(Runnable r) {
            if (r != null) {
               r.run();
            }
         }
      };
   }

   /** 聊天/动作栏输出; 未注入→no-op(纯 UI 通道)。 */
   public interface ChatSinkFn {
      void chat(Text msg);

      void actionBar(Text msg);

      ChatSinkFn NOOP = new ChatSinkFn() {
         @Override
         public void chat(Text msg) {
         }

         @Override
         public void actionBar(Text msg) {
         }
      };
   }

   /** 客户端静态信息; 未注入时 userName="", gameVersion=null(调用方回退常量), gameDir=当前目录。 */
   public interface ClientInfoFn {
      String userName();

      String gameVersion();

      File gameDir();

      List<Map<String, String>> installedMods();

      ClientInfoFn DEFAULTS = new ClientInfoFn() {
         @Override
         public String userName() {
            return "";
         }

         @Override
         public String gameVersion() {
            return null;
         }

         @Override
         public File gameDir() {
            return new File(".");
         }

         @Override
         public List<Map<String, String>> installedMods() {
            return Collections.emptyList();
         }
      };
   }

   /** Text→宿主本机渲染(Component); 未注入→返回 null, 调用方须容错。 */
   public interface TextRendererFn {
      Object render(Text text);
   }

   /** 连接态/发起连接抽象(批 3 接线), 本批仅定义。 */
   public interface GameConnectionFn {
      GameConnectionFn EMPTY = new GameConnectionFn() {
      };
   }

   /** 单人集成服务器抽象(批 3 接线), 本批仅定义。 */
   public interface ServerHandleFn {
      ServerHandleFn EMPTY = new ServerHandleFn() {
      };
   }

   /** 房间丢失 UI 回调(批 3 接线), 本批仅定义。 */
   public interface RoomLostUiHookFn {
      void onRoomLost();

      RoomLostUiHookFn NOOP = new RoomLostUiHookFn() {
         @Override
         public void onRoomLost() {
         }
      };
   }
}
