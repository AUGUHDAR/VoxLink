package icu.wuhui.voxlink.mixin;

import icu.wuhui.voxlink.ui.RoomBrowserScreenBase;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {
   protected TitleScreenMixin(Component title) {
      super(title);
   }

   @Inject(method = "init", at = @At("TAIL"), require = 0)
   private void onInit(CallbackInfo ci) {
      icu.wuhui.voxlink.network.TicketClient.pollOnceIfNeeded(() -> {
         Minecraft mc = Minecraft.getInstance();
         mc.execute(() -> {
            if (icu.wuhui.voxlink.network.TicketClient.hasUnread()
               && mc.screen instanceof TitleScreen) {
               mc.setScreen(mc.screen);
            }
         });
      });
      // 草图定稿: "多人游戏"改半宽短条, VoxLink 同行右半宽 —— 两个联机入口并排
      // 原版主列: 单人游戏(+48, 200宽) / 多人游戏(+72, 200宽→98) / Realms(+96)
      AbstractWidget multiplayerBtn = null;
      for (GuiEventListener l : this.children()) {
         if (l instanceof AbstractWidget w
            && w.getX() == this.width / 2 - 100
            && w.getWidth() == 200
            && w.getY() > this.height / 4 + 48
            && (multiplayerBtn == null || w.getY() < multiplayerBtn.getY())) {
            multiplayerBtn = w;
         }
      }
      if (multiplayerBtn != null) {
         multiplayerBtn.setWidth(98);
      }
      int mx = multiplayerBtn != null ? multiplayerBtn.getX() : this.width / 2 - 100;
      int my = multiplayerBtn != null ? multiplayerBtn.getY() : this.height / 4 + 72;
      this.addRenderableWidget(
         Button.builder(Component.translatable("voxlink.title"), button -> Minecraft.getInstance().setScreenAndShow(new RoomBrowserScreenBase(this)))
            .bounds(mx + 102, my, 98, 20)
            .build()
      );
      // 左上角工单未读弹窗：点击进我的工单
      if (icu.wuhui.voxlink.network.TicketClient.hasUnread()) {
         this.addRenderableWidget(
            Button.builder(Component.translatable("voxlink.ticket.notify"), button ->
                  Minecraft.getInstance().setScreenAndShow(new icu.wuhui.voxlink.ui.TicketListScreen(this)))
               .bounds(8, 8, 100, 18)
               .build()
         );
      }
   }
}
