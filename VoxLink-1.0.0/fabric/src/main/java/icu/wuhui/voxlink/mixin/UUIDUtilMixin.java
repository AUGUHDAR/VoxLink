package icu.wuhui.voxlink.mixin;

import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.uuid.UUIDPolicyManager;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.UUIDUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

@Mixin(UUIDUtil.class)
public class UUIDUtilMixin {
    @Inject(method = "createOfflinePlayerUUID", at = @At("HEAD"), cancellable = true, require = 0)
    private static void onCreateOfflinePlayerUUID(String playerName, CallbackInfoReturnable<UUID> cir) {
        if (FabricLoader.getInstance().isModLoaded("mcwifipnp")) return;
        UUID fixedUuid = UUIDPolicyManager.hookEntry(playerName);
        if (fixedUuid != null && VoxLinkMod.getRoomManager() != null && VoxLinkMod.getRoomManager().isInRoom()) {
            cir.setReturnValue(fixedUuid);
        }
    }
}
