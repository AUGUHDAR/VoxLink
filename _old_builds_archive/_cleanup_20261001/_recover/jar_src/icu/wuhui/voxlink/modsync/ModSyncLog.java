package icu.wuhui.voxlink.modsync;

import icu.wuhui.voxlink.VoxLinkMod;

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
