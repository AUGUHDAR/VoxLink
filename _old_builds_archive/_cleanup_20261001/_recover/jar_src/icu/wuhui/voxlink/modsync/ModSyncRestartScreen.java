package icu.wuhui.voxlink.modsync;

import icu.wuhui.voxlink.ui.VoxLinkScreenBase;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

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
      this.addRenderableWidget(Button.builder(Component.translatable("voxlink.modsync.exit_game"), b -> {
         ModSyncLog.info("exit game requested from restart screen");
         Minecraft.getInstance().stop();
      }).bounds(centerX - 100, this.height / 2 + 40, 200, 20).build());
   }

   public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
      graphics.fill(0, 0, this.width, this.height, -435418096);
      super.extractRenderState(graphics, mouseX, mouseY, partialTick);
      int centerX = this.width / 2;
      this.drawCenteredString(graphics, this.title.getString(), centerX, this.height / 2 - 48, 16733525);
      this.drawCenteredString(
         graphics, Component.translatable("voxlink.modsync.restart_line1", new Object[]{this.downloadedCount}).getString(), centerX, this.height / 2 - 24, -1
      );
      this.drawCenteredString(graphics, Component.translatable("voxlink.modsync.restart_line2", new Object[0]).getString(), centerX, this.height / 2 - 8, -1);
   }

   public boolean shouldCloseOnEsc() {
      return false;
   }

   public void onClose() {
   }
}
