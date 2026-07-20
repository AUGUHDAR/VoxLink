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

    private static final int BTN_OFFSET_X = 102;
    private static final int PAUSE_BTN_Y_OFFSET = 144;
    private static final int BTN_FULL_W = 204;
    private static final int BTN_H = 20;

    protected PauseMenuMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"), require = 0)
    private void voxlink$onInit(CallbackInfo ci) {
        var mc = Minecraft.getInstance();
        var rm = VoxLinkMod.getRoomManager();
        if (rm == null) return;

        RoomInfo currentRoom = rm.getCurrentRoom();

        if (currentRoom != null) {
            this.addRenderableWidget(
                    Button.builder(Component.translatable("voxlink.pause.room_management"), button -> {
                        //debounce 回调内重新检查currentRoom 防止init与click之间状态变化
                        RoomInfo live = rm.getCurrentRoom();
                        if (live == null) {
                            mc.setScreen(new VoxLinkScreen((Screen)(Object)this));
                            return;
                        }
                        mc.setScreen(new VoxLinkScreen((Screen)(Object)this));
                    })
                    .bounds(this.width / 2 - BTN_OFFSET_X, this.height / 4 + PAUSE_BTN_Y_OFFSET, BTN_FULL_W, BTN_H)
                    .build()
            );
        } else if (mc.getSingleplayerServer() != null) {
            this.addRenderableWidget(
                    Button.builder(Component.translatable("voxlink.create_room"), button -> mc.setScreen(new CreateRoomScreen((Screen)(Object)this)))
                    .bounds(this.width / 2 - BTN_OFFSET_X, this.height / 4 + PAUSE_BTN_Y_OFFSET, BTN_FULL_W, BTN_H)
                    .build()
            );
        }
    }
}
