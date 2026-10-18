package icu.wuhui.voxlink.mixin;

import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.room.RoomInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraftforge.fml.ModList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin({IntegratedServer.class})
public abstract class IntegratedServerMixin {
   @Inject(
      method = {"getMaxPlayers()I"},
      at = {@At("HEAD")},
      cancellable = true,
      require = 0
   )
   private void onGetMaxPlayers(CallbackInfoReturnable<Integer> cir) {
      if (!ModList.isLoaded("mcwifipnp")) {
         if (Minecraft.getInstance().getSingleplayerServer() == (IntegratedServer)this) {
            if (VoxLinkMod.getRoomManager() != null && VoxLinkMod.getRoomManager().isInRoom()) {
               RoomInfo room = VoxLinkMod.getRoomManager().getCurrentRoom();
               if (room != null) {
                  cir.setReturnValue(room.getMaxPlayers());
               }
            }
         }
      }
   }
}
