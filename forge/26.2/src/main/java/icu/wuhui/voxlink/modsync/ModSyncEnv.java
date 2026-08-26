package icu.wuhui.voxlink.modsync;

import icu.wuhui.voxlink.VoxLinkConstants;
import java.nio.file.Path;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * ModSync 加载器/目录相关的唯一收敛点。
 * 跨加载器传播时只需改这一个文件：LOADER 字符串与 mods 目录解析。
 */
public final class ModSyncEnv {
   /** 本端加载器标识（与服务端房间 loader 字段同一取值域）。 */
   public static final String LOADER = "forge";
   /** 本端 Minecraft 版本（用于向 Modrinth 过滤兼容版本）。 */
   public static final String GAME_VERSION = VoxLinkConstants.GAME_VERSION;

   private ModSyncEnv() {
   }

   /** 当前实例的 mods 目录。 */
   public static Path getModsDir() {
      return FMLPaths.GAMEDIR.get().resolve("mods");
   }
}
