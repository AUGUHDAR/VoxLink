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
         Object api = geyserApiClass.getMethod("api").invoke(null);
         if (api != null) {
            Object listener = api.getClass().getMethod("bedrockListener").invoke(api);
            if (listener != null) {
               int port = (Integer)listener.getClass().getMethod("port").invoke(listener);
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
         Object geyserImpl = Class.forName("org.geysermc.geyser.GeyserImpl").getMethod("getInstance").invoke(null);
         if (geyserImpl == null) {
            return 19132;
         }

         Object config = geyserImpl.getClass().getMethod("config").invoke(geyserImpl);
         Object bedrockConfig = config.getClass().getMethod("bedrock").invoke(config);
         int port = (Integer)bedrockConfig.getClass().getMethod("port").invoke(bedrockConfig);
         bedrockPort = port > 0 ? port : 19132;
         return bedrockPort;
      } catch (Throwable e) {
         LOGGER.warn("Failed to read Geyser bedrock port, using default {}: {}", new Object[]{19132, e.toString(), e});
         return 19132;
      }
   }
}
