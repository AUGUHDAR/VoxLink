package icu.wuhui.voxlink.platform;

import icu.wuhui.voxlink.VoxLinkConstants;
import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class ClientInfo {
   private ClientInfo() {
   }

   /** 未注入返回 ""。 */
   public static String userName() {
      try {
         return andd(Platform.host().clientInfo().userName());
      } catch (Throwable t) {
         return "";
      }
   }

   /** 未注入回退 VoxLinkConstants.GAME_VERSION(模板侧常量, 依赖 loader 常量池注入)。 */
   public static String gameVersion() {
      try {
         String v = Platform.host().clientInfo().gameVersion();
         return v != null && !v.isEmpty() ? v : VoxLinkConstants.GAME_VERSION;
      } catch (Throwable t) {
         return VoxLinkConstants.GAME_VERSION;
      }
   }

   /** 未注入返回 new File(".")。 */
   public static File gameDir() {
      try {
         File d = Platform.host().clientInfo().gameDir();
         return d != null ? d : new File(".");
      } catch (Throwable t) {
         return new File(".");
      }
   }

   public static List<Map<String, String>> installedMods() {
      try {
         List<Map<String, String>> mods = Platform.host().clientInfo().installedMods();
         return mods != null ? Collections.unmodifiableList(mods) : Collections.emptyList();
      } catch (Throwable t) {
         return Collections.emptyList();
      }
   }

   private static String andd(String s) {
      return s == null ? "" : s;
   }
}
