package icu.wuhui.voxlink.config;

public final class LogUploadState
{
   private static volatile boolean enabled = true;

   private LogUploadState() {}

   public static boolean isLogUploadEnabled()
   {
      return enabled;
   }

   public static void setLogUploadEnabled(boolean value)
   {
      enabled = value;
   }
}
