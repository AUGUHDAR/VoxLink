package icu.wuhui.voxlink.terracotta;

import java.util.Locale;
import java.util.regex.Pattern;

public final class RoomCodeRouter {
   private static final Pattern TERRACOTTA_LEGACY = Pattern.compile("^U/[A-Z0-9]{4}(-[A-Z0-9]{4}){3}$");
   private static final Pattern TERRACOTTA_PCL2CE = Pattern.compile("^P/[A-Z0-9]{4}(-[A-Z0-9]{4}){3}$");
   private static final Pattern TERRACOTTA_SCAFFOLDING = Pattern.compile("^S/[A-Z0-9]{4}(-[A-Z0-9]{4}){3}$");
   private static final Pattern VOXLINK_CODE = Pattern.compile("^[A-HJ-NP-Z2-9]{6}$");

   private RoomCodeRouter() {
   }

   public static boolean isTerracottaCode(String code) {
      if (code != null && code.length() >= 3) {
         String upper = code.toUpperCase(Locale.ROOT);
         return TERRACOTTA_LEGACY.matcher(upper).matches() || TERRACOTTA_PCL2CE.matcher(upper).matches() || TERRACOTTA_SCAFFOLDING.matcher(upper).matches();
      } else {
         return false;
      }
   }

   public static boolean isVoxLinkCode(String code) {
      return code == null ? false : VOXLINK_CODE.matcher(code.toUpperCase(Locale.ROOT)).matches();
   }

   public static boolean shouldUseTerracotta(String code) {
      return isTerracottaCode(code);
   }

   public static String getRouteName(String code) {
      if (isTerracottaCode(code)) {
         return "Terracotta";
      } else {
         return isVoxLinkCode(code) ? "VoxLink" : "Unknown";
      }
   }
}
