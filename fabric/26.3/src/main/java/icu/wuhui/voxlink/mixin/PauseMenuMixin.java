package icu.wuhui.voxlink.mixin;

import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.room.RoomInfo;
import icu.wuhui.voxlink.room.RoomManager;
import icu.wuhui.voxlink.ui.CreateRoomScreen;
import icu.wuhui.voxlink.ui.ManageRoomScreen;
import icu.wuhui.voxlink.ui.VoxLinkScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
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
   private void voxlink$onInit(CallbackInfo ci) {
      Minecraft mc = Minecraft.getInstance();
      RoomManager rm = VoxLinkMod.getRoomManager();
      if (rm == null) {
         return;
      }
      RoomInfo currentRoom = rm.getCurrentRoom();
      boolean hasRoom = currentRoom != null;
      if (!hasRoom && mc.getSingleplayerServer() == null) {
         return;
      }
      // 与原版按钮列同宽同对齐(200 居中), 贴原版最低整宽按钮下一行(防隐藏元素拉大空隙)
      int bottom = this.height / 4;
      for (GuiEventListener l : this.children()) {
         if (l instanceof AbstractWidget w && w.getWidth() >= 90) {
            int b = w.getY() + w.getHeight();
            if (b > bottom) {
               bottom = b;
            }
         }
      }
      int y = bottom + 4;
      // 高频路径不加深: 无房世界内直达建房屏(1 层), 有房才进主屏看房码面板
      this.addRenderableWidget(
         hasRoom
            ? Button.builder(Component.translatable("voxlink.pause.room_management"), button -> mc.setScreenAndShow(new ManageRoomScreen(this, currentRoom)))
               .bounds(this.width / 2 - 100, y, 200, 20).build()
            : Button.builder(Component.translatable("voxlink.create_room"), button -> mc.setScreenAndShow(new CreateRoomScreen(this)))
               .bounds(this.width / 2 - 100, y, 200, 20).build()
      );
   }
}
