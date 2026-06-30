package icu.wuhui.voxlink.mixin;

import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.room.RoomInfo;
import icu.wuhui.voxlink.ui.CreateRoomScreen;
import icu.wuhui.voxlink.ui.VoxLinkScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PauseScreen.class)
public abstract class PauseMenuMixin extends Screen {

    protected PauseMenuMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"), require = 0)
    private void onInit(CallbackInfo ci) {
        var mc = Minecraft.getInstance();
        var rm = VoxLinkMod.getRoomManager();
        if (rm == null) return;

        RoomInfo currentRoom = rm.getCurrentRoom();

        if (currentRoom != null) {
            Component label = Component.translatable("voxlink.pause.button_with_code", currentRoom.getCode());
            this.addRenderableWidget(
                    Button.builder(label, button -> Minecraft.getInstance().setScreen(new VoxLinkScreen((Screen)(Object)this)))
                    .bounds(this.width / 2 - 102, this.height / 4 + 144, 204, 20)
                    .build()
            );
        } else if (mc.getSingleplayerServer() != null) {
            this.addRenderableWidget(
                    Button.builder(Component.translatable("voxlink.create_room"), button -> Minecraft.getInstance().setScreen(new CreateRoomScreen((Screen)(Object)this)))
                    .bounds(this.width / 2 - 102, this.height / 4 + 144, 204, 20)
                    .build()
            );
        }
    }
}
