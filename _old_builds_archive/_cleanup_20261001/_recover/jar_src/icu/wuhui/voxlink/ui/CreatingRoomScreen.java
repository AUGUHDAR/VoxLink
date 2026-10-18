package icu.wuhui.voxlink.ui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

public class CreatingRoomScreen extends VoxLinkScreenBase {
   private static final int MARGIN_X = 10;
   private final CreateRoomScreen home;

   protected CreatingRoomScreen(CreateRoomScreen home) {
      super(Component.translatable("voxlink.create_room"));
      this.home = home;
   }

   @Override
   protected void init() {
      super.init();
      int centerX = this.width / 2;
      int by = this.height / 2 + 20;
      this.addRenderableWidget(
         Button.builder(Component.translatable("voxlink.cancel"), button -> this.home.cancelFlowFromUi()).bounds(centerX - 100, by, 98, 20).build()
      );
      this.addRenderableWidget(
         Button.builder(Component.translatable("voxlink.create_flow.collapse"), button -> this.collapse()).bounds(centerX + 2, by, 98, 20).build()
      );
   }

   private void collapse() {
      Minecraft mc = Minecraft.getInstance();
      showMinimizeHints(mc);
      mc.gui.setScreen(null);
   }

   static void showMinimizeHints(Minecraft mc) {
      if (mc.player != null) {
         mc.player.sendSystemMessage(Component.translatable("voxlink.create_flow.minimized_hint").withStyle(s -> s.withColor(-5592321)));
         mc.player.sendSystemMessage(Component.translatable("voxlink.create_flow.minimized_hint_cfg").withStyle(ChatFormatting.GRAY));
      }
   }

   public boolean shouldCloseOnEsc() {
      return true;
   }

   public void onClose() {
      this.collapse();
   }

   public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
      super.extractRenderState(graphics, mouseX, mouseY, partialTick);
      long elapsed = CreateFlowState.elapsedMs() / 1000L;

      String msg = switch (CreateFlowState.getPhase()) {
         case PREPARE_LAN -> Component.translatable("voxlink.create_flow.phase_lan").getString();
         case REGISTERING -> Component.translatable("voxlink.create_flow.phase_registering").getString();
         case SUCCESS -> Component.translatable("voxlink.create_room.success").getString();
         case CANCELLED -> Component.translatable("voxlink.create_room.cancelled").getString();
         case FAILED -> Component.translatable("voxlink.create_room.timeout").getString();
         default -> Component.translatable("voxlink.create_room.creating").getString();
      } + Component.translatable("voxlink.create_room.elapsed_seconds", new Object[]{elapsed}).getString();
      int maxWidth = this.width - 20;
      String clipped = msg;
      if (this.fontWidth(msg) > maxWidth) {
         while (this.fontWidth(clipped + "...") > maxWidth && clipped.length() > 0) {
            clipped = clipped.substring(0, clipped.length() - 1);
         }

         clipped = clipped + "...";
      }

      this.drawCenteredString(graphics, clipped, this.width / 2, this.height / 2 - 6, -171);
      this.drawCenteredString(graphics, Component.translatable("voxlink.create_room.uac_hint").getString(), this.width / 2, this.height / 2 + 10, -5592406);
   }
}
