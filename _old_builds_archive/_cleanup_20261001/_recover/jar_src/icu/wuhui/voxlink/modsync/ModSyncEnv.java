package icu.wuhui.voxlink.modsync;

import icu.wuhui.voxlink.VoxLinkConstants;
import java.nio.file.Path;
import net.minecraftforge.fml.loading.FMLPaths;

public final class ModSyncEnv {
   public static final String LOADER = "forge";
   public static final String GAME_VERSION = VoxLinkConstants.GAME_VERSION;

   private ModSyncEnv() {
   }

   public static Path getModsDir() {
      return FMLPaths.GAMEDIR.get().resolve("mods");
   }

   public static Path getCacheFile() {
      return FMLPaths.GAMEDIR.get().resolve("mods").resolve("voxlink").resolve("modsync_cache.json");
   }
}
