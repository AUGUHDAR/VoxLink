package icu.wuhui.voxlink.modsync;

import icu.wuhui.voxlink.ui.VoxLinkColors;
import icu.wuhui.voxlink.ui.VoxLinkScreenBase;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 强制重启屏：必装模组已落盘但需重启生效。全屏无 ESC 退出、无返回，
 * 唯一动作是退出游戏——把"玩家只做一个动作"做实。
 */
public class ModSyncRestartScreen extends VoxLinkScreenBase {
   private final int downloadedCount;

   public ModSyncRestartScreen(int downloadedCount) {
      super(Component.translatable("voxlink.modsync.restart_title"));
      this.downloadedCount = downloadedCount;
   }

   @Override
   protected void init() {
      super.init();
      int centerX = this.width / 2;
      this.addRenderableWidget(
         Button
            .builder(
               Component.translatable("voxlink.modsync.exit_game"),
               b -> {
                  ModSyncLog.info("exit game requested from restart screen");
                  Minecraft.getInstance().stop();
               }
            )
            .bounds(centerX - 100, this.height / 2 + 40, 200, 20)
            .build()
      );
   }

   @Override
   public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
      // 全屏深色遮罩：不用 renderBackground（其签名在 1.20.2+ 变更），用 fill 保证跨版本一致
      graphics.fill(0, 0, this.width, this.height, 0xE60C0C10);
      super.render(graphics, mouseX, mouseY, partialTick);
      int centerX = this.width / 2;
      this.drawCenteredString(graphics, this.title.getString(), centerX, this.height / 2 - 48, 0xFF5555);
      this.drawCenteredString(
         graphics,
         Component.translatable("voxlink.modsync.restart_line1", new Object[]{this.downloadedCount}).getString(),
         centerX,
         this.height / 2 - 24,
         VoxLinkColors.WHITE
      );
      this.drawCenteredString(
         graphics,
         Component.translatable("voxlink.modsync.restart_line2", new Object[0]).getString(),
         centerX,
         this.height / 2 - 8,
         VoxLinkColors.WHITE
      );
   }

   @Override
   public boolean shouldCloseOnEsc() {
      return false;
   }

   @Override
   public void onClose() {
      // 吞掉关闭请求：本屏唯一出口是"退出游戏"按钮
   }
}
