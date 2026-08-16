package icu.wuhui.voxlink.mixin;

import icu.wuhui.voxlink.ui.VoxLinkScreen;
import net.minecraft.client.Minecraft;
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
   private static final int MAX_BTN_W = 50;
   private static final int BTN_MARGIN = 10;
   private static final int TITLE_BTN_OFFSET_X = 104;
   private static final int TITLE_BTN_Y_OFFSET = 48;
   private static final int BTN_H = 20;

   protected TitleScreenMixin(Component title) {
      super(title);
   }

   @Inject(method = "init", at = @At("TAIL"), require = 0)
   private void onInit(CallbackInfo ci) {
      int buttonWidth = Math.max(20, Math.min(50, this.width / 4 - 10));
      int x = Math.min(this.width / 2 + 104, this.width - buttonWidth - 10);
      this.addRenderableWidget(
         Button.builder(Component.translatable("voxlink.title"), button -> Minecraft.getInstance().setScreen(new VoxLinkScreen(this)))
            .bounds(x, this.height / 4 + 48, buttonWidth, 20)
            .build()
      );
   }
}
