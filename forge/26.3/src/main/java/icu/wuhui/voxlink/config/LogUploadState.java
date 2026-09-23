package icu.wuhui.voxlink.config;

public final class LogUploadState
{
   /** 默认开启（产品决策：打洞体验与远程排障）；启动时由 VoxLinkConfig.logUploadEnabled 初始化 */
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
