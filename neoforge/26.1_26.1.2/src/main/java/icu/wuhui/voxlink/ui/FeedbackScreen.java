package icu.wuhui.voxlink.ui;

import java.awt.Desktop;
import java.net.URI;
import icu.wuhui.voxlink.VoxLinkMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 反馈选择界面：玩家点"反馈与建议"后先到这里，
 * 三选一 —— 在本站内反馈（FeedbackFormScreen）、GitHub Issues 或 Gitee Issues。
 */
public class FeedbackScreen extends VoxLinkScreenBase {
   private static final String GITHUB_ISSUES_URL = "https://github.com/AUGUHDAR/VoxLink/issues";
   private static final String GITEE_ISSUES_URL = "https://gitee.com/AUGUHDAR/VoxLink/issues";
   private final Screen parent;

   public FeedbackScreen(Screen parent) {
      super(Component.translatable("voxlink.fb.title"));
      this.parent = parent;
   }

   protected void init() {
      super.init();
      int centerX = this.width / 2;
      int colY = this.height / 2 - 64;
      this.addRenderableWidget(
         Button.builder(Component.translatable("voxlink.ticket.my"), button ->
               Minecraft.getInstance().setScreen(new TicketListScreen(new FeedbackScreen(this.parent))))
            .bounds(centerX - 100, colY, 200, 20)
            .build()
      );
      this.addRenderableWidget(
         Button.builder(Component.translatable("voxlink.fb.site"), button -> Minecraft.getInstance().setScreen(new FeedbackFormScreen(this, this.parent)))
            .bounds(centerX - 100, colY + 24, 200, 20)
            .build()
      );
      this.addRenderableWidget(
         Button.builder(Component.translatable("voxlink.fb.github"), button -> openBrowser(GITHUB_ISSUES_URL))
            .bounds(centerX - 100, colY + 48, 200, 20)
            .build()
      );
      this.addRenderableWidget(
         Button.builder(Component.translatable("voxlink.fb.gitee"), button -> openBrowser(GITEE_ISSUES_URL))
            .bounds(centerX - 100, colY + 72, 200, 20)
            .build()
      );
      this.addRenderableWidget(
         Button.builder(Component.translatable("voxlink.back"), button -> this.onClose())
            .bounds(centerX - 100, colY + 96, 200, 20)
            .build()
      );
   }

   public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
      super.extractRenderState(graphics, mouseX, mouseY, partialTick);
      int centerX = this.width / 2;
      this.drawTitle(graphics, 20);
      this.drawCenteredClipped(graphics, Component.translatable("voxlink.fb.subtitle").getString(), centerX, 34, VoxLinkColors.GRAY);
   }

   public void onClose() {
      Minecraft.getInstance().setScreen(this.parent);
   }

   /** 用系统浏览器打开 URL（与官网按钮同一套兜底链路）。 */
   static void openBrowser(String url) {
      try {
         if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI.create(url));
            return;
         }

         String os = System.getProperty("os.name", "").toLowerCase();
         String[] cmd;
         if (os.contains("win")) {
            cmd = new String[]{"rundll32", "url.dll,FileProtocolHandler", url};
         } else if (!os.contains("mac") && !os.contains("darwin")) {
            cmd = new String[]{"xdg-open", url};
         } else {
            cmd = new String[]{"open", url};
         }

         Runtime.getRuntime().exec(cmd);
      } catch (Exception e) {
         VoxLinkMod.LOGGER.warn("[FeedbackScreen] Failed to open browser: {}", e.getMessage());
         Minecraft mc = Minecraft.getInstance();
         if (mc.player != null) {
            try {
               mc.player.getClass().getMethod("displayClientMessage", Component.class, boolean.class)
                     .invoke(mc.player, Component.translatable("voxlink.website_open_failed"), false);
            } catch (Throwable ignored) {
            }
         }
      }
   }
}
