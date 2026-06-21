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

    protected TitleScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"), require = 0)
    private void onInit(CallbackInfo ci) {
        int buttonWidth = Math.min(50, this.width / 4 - 10);
        this.addRenderableWidget(
                Button.builder(Component.translatable("voxlink.title"), button -> {
                    Minecraft.getInstance().setScreen(new VoxLinkScreen((Screen) (Object) this));
                })
                .bounds(this.width / 2 + 104, this.height / 4 + 48, buttonWidth, 20)
                .build()
        );
    }
}
