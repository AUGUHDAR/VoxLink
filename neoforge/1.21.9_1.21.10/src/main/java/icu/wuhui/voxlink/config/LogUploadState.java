package icu.wuhui.voxlink.config;

public final class LogUploadState
{
   /** 安全修复：默认关闭（隐私内容默认不出网）；启动时由 VoxLinkConfig.logUploadEnabled 初始化 */
   private static volatile boolean enabled = false;

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
