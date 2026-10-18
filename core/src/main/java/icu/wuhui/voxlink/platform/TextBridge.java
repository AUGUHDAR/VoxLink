package icu.wuhui.voxlink.platform;

/** Text 转宿主 Component 的窄门面: 未注入/失败一律返回 null, 调用方回退 toPlainString/裸 key。 */
public final class TextBridge {
   private TextBridge() {
   }

   public static Object renderOrNull(Text text) {
      if (text == null) {
         return null;
      }
      try {
         return Platform.host().textRenderer() != null ? Platform.host().textRenderer().render(text) : null;
      } catch (Throwable t) {
         return null;
      }
   }
}
