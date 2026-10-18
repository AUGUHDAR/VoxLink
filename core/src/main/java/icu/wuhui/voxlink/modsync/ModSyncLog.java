package icu.wuhui.voxlink.modsync;

import icu.wuhui.voxlink.VoxLinkMod;

/** modsync 统一日志出口（便于跨加载器传播时统一调整）。 */
final class ModSyncLog {
   static void info(String msg, Object... args) {
      VoxLinkMod.LOGGER.info("[ModSync] " + msg, args);
   }

   static void warn(String msg, Object... args) {
      VoxLinkMod.LOGGER.warn("[ModSync] " + msg, args);
   }

   static void error(String msg, Object... args) {
      VoxLinkMod.LOGGER.error("[ModSync] " + msg, args);
   }

   private ModSyncLog() {
   }
}
