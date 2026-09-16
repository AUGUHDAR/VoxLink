package icu.wuhui.voxlink.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class RelatedLinksScreen extends VoxLinkScreenBase {

   private static final String[][] LINKS = {
      {"voxlink.links.mcmod", "https://www.mcmod.cn/class/28295.html"},
      {"voxlink.links.github", "https://github.com/AUGUHDAR/VoxLink"},
      {"voxlink.links.modrinth", "https://modrinth.com/mod/voxlink"},
      {"voxlink.links.curseforge", "https://www.curseforge.com/minecraft/mc-mods/voxlink"},
      {"voxlink.links.site", "https://p2p.wuhui.icu/"},
      {"voxlink.links.discord", "https://discord.gg/XaAFxvzPDS"},
      {"voxlink.links.qq", "https://qm.qq.com/cgi-bin/qm/qr?k=OEkk9L8m8jdFMkbGDhKZs0u2U0azLAPo&jump_from=webapi&authKey=v0dYAQniGZypAJuoPZW/7FL0bfoc32h68oIHd9lqGwOvAduzcwsJNR7Mei9/YugW"},
      {"voxlink.links.gitee", "https://gitee.com/AUGUHDAR/VoxLink"}
   };

   private final Screen parent;

   public RelatedLinksScreen(Screen parent) {
      super(Component.translatable("voxlink.links.title"));
      this.parent = parent;
   }

   protected void init() {
      super.init();
      int centerX = this.width / 2;
      int top = Math.max(46, this.height / 2 - 58);
      for (int i = 0; i < LINKS.length; i++) {
         int x = (i % 2 == 0) ? centerX - 100 : centerX + 2;
         int y = top + (i / 2) * 24;
         String url = LINKS[i][1];
         this.addRenderableWidget(
            Button.builder(Component.translatable(LINKS[i][0]), button -> FeedbackScreen.openBrowser(url))
               .bounds(x, y, 98, 20)
               .build()
         );
      }

      this.addRenderableWidget(
         Button.builder(Component.translatable("voxlink.back"), button -> this.onClose())
            .bounds(centerX - 100, top + 4 * 24 + 6, 200, 20)
            .build()
      );
   }

   public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
      super.extractRenderState(graphics, mouseX, mouseY, partialTick);
      this.drawTitle(graphics, 20);
   }

   public void onClose() {
      Minecraft.getInstance().gui.setScreen(this.parent);
   }
}
