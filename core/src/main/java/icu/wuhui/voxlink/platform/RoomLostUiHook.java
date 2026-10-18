package icu.wuhui.voxlink.platform;

/** 房间丢失 UI 回调(批 3 接线用骨架): 替代 RoomManager 内 mc.screen instanceof 模板类探测。 */
public final class RoomLostUiHook {
   private RoomLostUiHook() {
   }

   /** loader 端注册; 未注入/失败静默(设计文档 R4 兜底: 模板保证 install 不漏)。 */
   public static void onRoomLost() {
      try {
         Platform.host().roomLostUiHook().onRoomLost();
      } catch (Throwable ignored) {
      }
   }
}
