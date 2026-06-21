package icu.wuhui.voxlink.mixin;

import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.room.RoomInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class DisconnectMixin {

    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;Z)V", at = @At("TAIL"), require = 0)
    private void onDisconnect(Screen screen, boolean keepResourcePacks, CallbackInfo ci) {
        onMcDisconnect();
    }

    private void onMcDisconnect() {
        var rm = VoxLinkMod.getRoomManager();
        if (rm == null) return;
        RoomInfo currentRoom = rm.getCurrentRoom();
        // 重置connecting
        icu.wuhui.voxlink.network.ConnectionHelper.resetConnecting();
        if (currentRoom != null && !rm.isConnectionCycleActive()) {
            VoxLinkMod.LOGGER.info("[VoxLink] MC断开，自动离开房间");
            rm.leaveRoom();
        }
    }
}
