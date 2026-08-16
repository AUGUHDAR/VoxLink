package icu.wuhui.voxlink.compat;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ViaCompat {
   private static final Logger LOGGER = LoggerFactory.getLogger("voxlink-via");

   private ViaCompat() {
   }

   public static boolean isViaLoaded() {
      return FabricLoader.getInstance().isModLoaded("viaversion");
   }

   public static boolean isViaFabricLoaded() {
      return FabricLoader.getInstance().isModLoaded("viafabric");
   }

   public static int getServerProtocolVersion() {
      if (!isViaLoaded()) {
         return 0;
      }

      try {
         Class<?> viaClass = Class.forName("com.viaversion.viaversion.api.Via");

         try {
            Method managerMethod = viaClass.getMethod("getManager");
            Object api = managerMethod.invoke(null);
            Object protoManager = api.getClass().getMethod("getProtocolManager").invoke(api);
            if (protoManager.getClass().getMethod("getSupportedProtocolVersions").invoke(protoManager) instanceof List<?> list && !list.isEmpty()) {
               int maxVersion = -1;

               for (Object v : list) {
                  if (v.getClass().getMethod("getVersion").invoke(v) instanceof Integer vi && vi > maxVersion) {
                     maxVersion = vi;
                  }
               }

               if (maxVersion > 0) {
                  return maxVersion;
               }
            }

            return 0;
         } catch (NoSuchMethodException e) {
            Object api = viaClass.getMethod("getAPI").invoke(null);
            Object serverVersion = api.getClass().getMethod("getServerVersion").invoke(api);
            Object highest = serverVersion.getClass().getMethod("highestSupportedProtocolVersion").invoke(serverVersion);
            Object version = highest.getClass().getMethod("getVersion").invoke(highest);
            return version instanceof Integer ? (Integer)version : 0;
         }
      } catch (Throwable e) {
         LOGGER.warn("Failed to get ViaVersion server protocol: {}", e.getMessage());
         return 0;
      }
   }

   public static int getPlayerProtocolVersion(UUID uuid) {
      if (!isViaLoaded()) {
         return 0;
      }

      try {
         Class<?> viaClass = Class.forName("com.viaversion.viaversion.api.Via");

         try {
            Method managerMethod = viaClass.getMethod("getManager");
            Object api = managerMethod.invoke(null);
            Object protoManager = api.getClass().getMethod("getProtocolManager").invoke(api);
            Object protoVersion = protoManager.getClass().getMethod("getProtocolVersion", UUID.class).invoke(protoManager, uuid);
            Object version = protoVersion.getClass().getMethod("getVersion").invoke(protoVersion);
            return version instanceof Integer ? (Integer)version : 0;
         } catch (NoSuchMethodException e) {
            Object api = viaClass.getMethod("getAPI").invoke(null);
            Object protoVersion = api.getClass().getMethod("getPlayerProtocolVersion", UUID.class).invoke(api, uuid);
            Object version = protoVersion.getClass().getMethod("getVersion").invoke(protoVersion);
            return version instanceof Integer ? (Integer)version : 0;
         }
      } catch (Throwable e) {
         LOGGER.debug("Failed to get player protocol version: {}", e.getMessage());
         return -1;
      }
   }

   public static String buildViaAddress(String host, int port, int targetProtocol) {
      if (isViaFabricLoaded() && targetProtocol > 0) {
         try {
            Class<?> parserClass;
            try {
               parserClass = Class.forName("com.viaversion.fabric.common.util.AddressParser");
            } catch (ClassNotFoundException ex) {
               parserClass = Class.forName("com.viaversion.fabric.common.AddressParser");
            }

            Object parsed = parserClass.getMethod("parse", String.class).invoke(null, host + ":" + port);
            Object suffix = parsed.getClass().getMethod("getSuffixWithOptions").invoke(parsed);
            if (suffix instanceof String && !((String)suffix).isEmpty()) {
               return host + "." + suffix + ":" + port;
            }
         } catch (Throwable e) {
            LOGGER.debug("AddressParser failed, using direct suffix: {}", e.getMessage());
         }

         return host + "._v" + targetProtocol + ".viafabric:" + port;
      } else {
         return host + ":" + port;
      }
   }
}
