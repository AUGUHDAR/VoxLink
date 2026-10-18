package icu.wuhui.voxlink.compat;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GeyserCompat {
   private static final Logger LOGGER = LoggerFactory.getLogger("voxlink-geyser");
   private static final int DEFAULT_BEDROCK_PORT = 19132;
   private static volatile Integer bedrockPort = null;
   private static volatile Boolean cachedLoaded = null;

   private GeyserCompat() {
   }

   public static boolean isGeyserLoaded() {
      Boolean cached = cachedLoaded;
      if (cached != null) {
         return cached;
      }

      boolean loaded = FabricLoader.getInstance().isModLoaded("geyser-fabric") || FabricLoader.getInstance().isModLoaded("geyser");
      cachedLoaded = loaded;
      return loaded;
   }

   public static int getBedrockPort() {
      Integer cached = bedrockPort;
      if (cached != null) {
         return cached;
      }

      if (!isGeyserLoaded()) {
         return -1;
      }

      try {
         Class<?> geyserApiClass = Class.forName("org.geysermc.geyser.api.GeyserApi");
         Object api = invokeAccessible(geyserApiClass, null, "api");
         if (api != null) {
            Object listener = invokeAccessible(api.getClass(), api, "bedrockListener");
            if (listener != null) {
               int port = (Integer)invokeAccessible(listener.getClass(), listener, "port");
               if (port > 0) {
                  bedrockPort = port;
                  return port;
               }
            }
         }
      } catch (Throwable e) {
         LOGGER.debug("GeyserApi.bedrockListener() failed, trying GeyserImpl: {}", e.getMessage());
      }

      try {
         Class<?> impl = Class.forName("org.geysermc.geyser.GeyserImpl");
         Object geyserImpl = invokeAccessible(impl, null, "getInstance");
         if (geyserImpl == null) {
            return 19132;
         }

         Object config = invokeAccessible(geyserImpl.getClass(), geyserImpl, "config");
         Object bedrockConfig = invokeAccessible(config.getClass(), config, "bedrock");
         int port = (Integer)invokeAccessible(bedrockConfig.getClass(), bedrockConfig, "port");
         bedrockPort = port > 0 ? port : 19132;
         return bedrockPort;
      } catch (Throwable e) {
         LOGGER.warn("Failed to read Geyser bedrock port, using default {}: {}", new Object[]{19132, e.toString(), e});
         return 19132;
      }
   }

   /** 反射调无参方法：先 trySetAccessible 再 invoke，避免 IllegalAccessException */
   private static Object invokeAccessible(Class<?> owner, Object target, String name) throws Exception {
      java.lang.reflect.Method m = owner.getMethod(name);
      m.trySetAccessible();
      return m.invoke(target);
   }
}
