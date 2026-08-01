package icu.wuhui.voxlink.mixin;

import icu.wuhui.voxlink.VoxLinkMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(IntegratedServer.class)
public abstract class IntegratedServerMixin {

    //debounce 用完整签名 消除mixin AP descriptor warning
    @Inject(method = "getMaxPlayers()I", at = @At("HEAD"), cancellable = true, require = 0)
    private void onGetMaxPlayers(CallbackInfoReturnable<Integer> cir) {
        if (FabricLoader.getInstance().isModLoaded("mcwifipnp")) return;
        //debounce 仅当前IntegratedServer是mc单人server时生效 防止误改其他场景
        if (Minecraft.getInstance().getSingleplayerServer() != (IntegratedServer)(Object)this) return;
        if (VoxLinkMod.getRoomManager() != null && VoxLinkMod.getRoomManager().isInRoom()) {
            var room = VoxLinkMod.getRoomManager().getCurrentRoom();
            if (room != null) {
                cir.setReturnValue(room.getMaxPlayers());
            }
        }
    }
}
