package icu.wuhui.voxlink.ui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * 创建中进度视图：只负责呈现 CreateFlowState 的实时状态，绝不持有流程生命周期。
 * 两个动作：【取消】终止后台并作废；【收起】隐藏本屏回到世界，后台继续跑。
 * ESC 等价于【收起】；任何时候重新打开本屏都会读到最新进度。
 */
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
         Button.builder(Component.translatable("voxlink.cancel"), button -> this.home.cancelFlowFromUi())
            .bounds(centerX - 100, by, 98, 20)
            .build()
      );
      this.addRenderableWidget(
         Button.builder(Component.translatable("voxlink.create_flow.collapse"), button -> this.collapse())
            .bounds(centerX + 2, by, 98, 20)
            .build()
      );
   }

   /** 收起：回世界不中断；聊天栏双提示（如何回来 + 自动收起开关在哪）。 */
   private void collapse() {
      Minecraft mc = Minecraft.getInstance();
      showMinimizeHints(mc);
      mc.gui.setScreen(null);
   }

   static void showMinimizeHints(Minecraft mc) {
      if (mc.player != null) {
         mc.player.sendSystemMessage(Component.translatable("voxlink.create_flow.minimized_hint").withStyle(s -> s.withColor(VoxLinkColors.INFO)));
         mc.player.sendSystemMessage(Component.translatable("voxlink.create_flow.minimized_hint_cfg").withStyle(ChatFormatting.GRAY));
      }
   }

   @Override
   public boolean shouldCloseOnEsc() {
      return true;
   }

   @Override
   public void onClose() {
      // ESC 视同收起而不是取消：后台继续跑
      this.collapse();
   }

   @Override
   public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
      super.extractRenderState(graphics, mouseX, mouseY, partialTick);
      long elapsed = CreateFlowState.elapsedMs() / 1000L;
      String phaseText;
      switch (CreateFlowState.getPhase()) {
         case PREPARE_LAN -> phaseText = Component.translatable("voxlink.create_flow.phase_lan").getString();
         case REGISTERING -> phaseText = Component.translatable("voxlink.create_flow.phase_registering").getString();
         case SUCCESS -> phaseText = Component.translatable("voxlink.create_room.success").getString();
         case CANCELLED -> phaseText = Component.translatable("voxlink.create_room.cancelled").getString();
         case FAILED -> phaseText = Component.translatable("voxlink.create_room.timeout").getString();
         default -> phaseText = Component.translatable("voxlink.create_room.creating").getString();
      }

      String msg = phaseText
         + Component.translatable("voxlink.create_room.elapsed_seconds", new Object[]{elapsed}).getString();
      int maxWidth = this.width - MARGIN_X * 2;
      String clipped = msg;
      if (this.fontWidth(msg) > maxWidth) {
         while (this.fontWidth(clipped + "...") > maxWidth && clipped.length() > 0) {
            clipped = clipped.substring(0, clipped.length() - 1);
         }

         clipped = clipped + "...";
      }

      this.drawCenteredString(graphics, clipped, this.width / 2, this.height / 2 - 6, VoxLinkColors.WARNING);
      this.drawCenteredString(
         graphics,
         Component.translatable("voxlink.create_room.uac_hint").getString(),
         this.width / 2,
         this.height / 2 + MARGIN_X,
         VoxLinkColors.MUTED
      );
   }
}
