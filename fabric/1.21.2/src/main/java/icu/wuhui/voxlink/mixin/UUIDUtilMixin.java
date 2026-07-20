package icu.wuhui.voxlink.mixin;

import icu.wuhui.voxlink.uuid.UUIDPolicyManager;
import net.fabricmc.loader.api.FabricLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

//1.20.5+: UUIDUtil 类存在; 1.20~1.20.4 类不存在时此 Mixin 自动跳过
@Mixin(targets = "net.minecraft.core.UUIDUtil")
public class UUIDUtilMixin {
    @Inject(method = "createOfflinePlayerUUID", at = @At("HEAD"), cancellable = true, require = 0)
    private static void voxlink$onCreateOfflinePlayerUUID(String playerName, CallbackInfoReturnable<UUID> cir) {
        if (FabricLoader.getInstance().isModLoaded("mcwifipnp")) return;
        UUID fixedUuid = UUIDPolicyManager.hookEntry(playerName);
        //debounce 删isInRoom检查 让策略全局生效(后续接入UI让玩家管理)
        if (fixedUuid != null) {
            cir.setReturnValue(fixedUuid);
        }
    }
}
