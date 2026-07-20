package icu.wuhui.voxlink.mixin;

import icu.wuhui.voxlink.uuid.UUIDPolicyManager;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.UUIDUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

//debounce 1.20.5+: UUIDUtil类存在 用value显式引用消除mixin warning
//1.20~1.20.4: 类不存在时此Mixin自动跳过(运行时无害)
@Mixin(value = UUIDUtil.class)
public class UUIDUtilMixin {
    @Inject(method = "createOfflinePlayerUUID", at = @At("HEAD"), cancellable = true, require = 0)
    private static void voxlink$onCreateOfflinePlayerUUID(String playerName, CallbackInfoReturnable<UUID> cir) {
        if (FabricLoader.getInstance().isModLoaded("mcwifipnp")) return;
        //debounce 删isInRoom检查 让策略全局生效(后续接入UI让玩家管理)
        UUID fixedUuid = UUIDPolicyManager.hookEntry(playerName);
        if (fixedUuid != null) {
            cir.setReturnValue(fixedUuid);
        }
    }
}
