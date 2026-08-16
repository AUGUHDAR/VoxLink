package icu.wuhui.voxlink.mixin;

import icu.wuhui.voxlink.uuid.UUIDPolicyManager;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.UUIDUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(UUIDUtil.class)
public class UUIDUtilMixin {
   @Inject(method = "createOfflinePlayerUUID", at = @At("HEAD"), cancellable = true, require = 0)
   private static void voxlink$onCreateOfflinePlayerUUID(String playerName, CallbackInfoReturnable<UUID> cir) {
      if (!FabricLoader.getInstance().isModLoaded("mcwifipnp")) {
         UUID fixedUuid = UUIDPolicyManager.hookEntry(playerName);
         if (fixedUuid != null) {
            cir.setReturnValue(fixedUuid);
         }
      }
   }
}
