package icu.wuhui.voxlink.mixin;

import icu.wuhui.voxlink.ui.RoomBrowserScreenBase;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {
   /** 工单提示的存活时长（tick，20 = 1 秒）；到期自动收起，下次进主菜单重新开始。 */
   private static final int TICKET_NOTICE_TICKS = 15 * 20;
   /** MC 原版输入框/按钮配色：纯黑底 + 浅灰 1px 边 + 白/灰文字。 */
   private static final int VANILLA_BG = 0xFF000000;
   private static final int VANILLA_BORDER = 0xFFA0A0A0;
   private static final int VANILLA_TEXT = 0xFFFFFFFF;
   private static final int VANILLA_SUB = 0xFFA0A0A0;
   /** 自绘按钮底色（原版灰：常态 / 悬停 / 按下）。 */
   private static final int BTN_FACE = 0xFF8B8B8B;
   private static final int BTN_HOVER = 0xFFA8A8A8;
   private static final int BTN_DOWN = 0xFF707070;
   private static final int BTN_EDGE = 0xFF000000;

   /** 提示面板与两个按钮全部自绘：位置在 init 时算好，渲染与命中都在自己手里。 */
   /** 本次游戏进程是否已经提示过（防止 init 被重跑导致倒计时重置/死而复生）。 */
   private static boolean ticketNoticeShownThisRun;
   private static boolean ticketNoticeOn;
   /** 剩余 tick 静态保存：分辨率变化或重建 Screen 时续上，不被重置。 */
   private static int ticketLeftTicks;
   private int ticketCardX;
   private int ticketCardY;
   private int ticketCardW;
   private int ticketCardH;
   private int ticketBtnX;
   private int ticketBtnY;
   private int ticketBtnW;

   protected TitleScreenMixin(Component title) {
      super(title);
   }

   @Inject(method = "init", at = @At("TAIL"), require = 0)
   private void onInit(CallbackInfo ci) {
      icu.wuhui.voxlink.network.TicketClient.pollOnceIfNeeded(() -> {
         Minecraft mc = Minecraft.getInstance();
         mc.execute(() -> {
            if (icu.wuhui.voxlink.network.TicketClient.hasUnread()
               && mc.screen instanceof TitleScreen) {
               mc.setScreen(mc.screen);
            }
         });
      });
      // 草图定稿: "多人游戏"改半宽短条, VoxLink 同行右半宽 —— 两个联机入口并排
      // 原版主列: 单人游戏(+48, 200宽) / 多人游戏(+72, 200宽→98) / Realms(+96)
      AbstractWidget multiplayerBtn = null;
      for (GuiEventListener l : this.children()) {
         if (l instanceof AbstractWidget w
            && w.getX() == this.width / 2 - 100
            && w.getWidth() == 200
            && w.getY() > this.height / 4 + 48
            && (multiplayerBtn == null || w.getY() < multiplayerBtn.getY())) {
            multiplayerBtn = w;
         }
      }
      if (multiplayerBtn != null) {
         multiplayerBtn.setWidth(98);
      }
      int mx = multiplayerBtn != null ? multiplayerBtn.getX() : this.width / 2 - 100;
      int my = multiplayerBtn != null ? multiplayerBtn.getY() : this.height / 4 + 72;
      this.addRenderableWidget(
         Button.builder(Component.translatable("voxlink.title"), button -> Minecraft.getInstance().setScreen(new RoomBrowserScreenBase(this)))
            .bounds(mx + 102, my, 98, 20)
            .build()
      );
      // 工单未读提示：左上角，原版面框 + 框内文字 + 两个原版灰按钮（全自绘）。
      if (!ticketNoticeShownThisRun && icu.wuhui.voxlink.network.TicketClient.hasUnread()) {
         this.layoutTicketNotice();   // 本次运行首次：起倒计时
      } else if (TitleScreenMixin.ticketNoticeOn) {
         this.recomputeTicketGeometry();   // 已提示过且仍在显示：只按新分辨率重排，倒计时不动
      }
   }

   /** 首次：按当前分辨率算几何 + 起倒计时 + 标记本次已提示。 */
   private void layoutTicketNotice() {
      this.recomputeTicketGeometry();
      TitleScreenMixin.ticketLeftTicks = TICKET_NOTICE_TICKS;
      ticketNoticeShownThisRun = true;
      TitleScreenMixin.ticketNoticeOn = true;
   }

   /** 只按当前分辨率重排几何（不动倒计时、不改可见态），供分辨率变化/重建时调用。 */
   private void recomputeTicketGeometry() {
      Minecraft mc = Minecraft.getInstance();
      String title = Component.translatable("voxlink.ticket.notice_title").getString();
      // 宽度按标题实测宽度与按钮需求取较大者，上限屏宽 3/5：大分辨率放得开，窄屏靠截断兜住
      int btnNeed = 8 * 2 + 58 * 2 + 6;
      int titleNeed = mc.font.width(title) + 16;
      int w = Math.max(btnNeed, titleNeed);
      w = Math.min(w, Math.max(140, this.width * 3 / 5));

      this.ticketCardX = 8;
      this.ticketCardY = 8;
      this.ticketCardW = w;
      this.ticketCardH = 62;
      this.ticketBtnW = Math.max(58, (w - 8 * 2 - 6) / 2);
      this.ticketBtnX = this.ticketCardX + 8;
      this.ticketBtnY = this.ticketCardY + this.ticketCardH - 24;
   }

   /** 收起提示（只影响本次主菜单，不写已读，下次进主菜单还会提醒）。 */
   private void ticketNoticeHide() {
      TitleScreenMixin.ticketNoticeOn = false;
      TitleScreenMixin.ticketLeftTicks = 0;
   }

   @Inject(method = "tick", at = @At("TAIL"), require = 1)
   private void onTick(CallbackInfo ci) {
      if (!TitleScreenMixin.ticketNoticeOn || TitleScreenMixin.ticketLeftTicks <= 0) {
         return;
      }
      TitleScreenMixin.ticketLeftTicks--;
      if (TitleScreenMixin.ticketLeftTicks <= 0) {
         this.ticketNoticeHide();
      }
   }

   /** 点击处理：面板画在最上层，两个自绘按钮的命中在这里判定，命中就 return true 吃掉这一下。 */
   @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true, require = 1)
   private void onMouseClicked(double mouseX, double mouseY, int button,
                               CallbackInfoReturnable<Boolean> cir) {
      if (!TitleScreenMixin.ticketNoticeOn || button != 0) {
         return;
      }
      // 左：稍后（只收起）；右：查看（进我的工单）
      if (this.inTicketButton(mouseX, mouseY, 0)) {
         this.ticketNoticeHide();
         cir.setReturnValue(true);
         return;
      }
      if (this.inTicketButton(mouseX, mouseY, 1)) {
         this.ticketNoticeHide();
         Minecraft.getInstance().setScreen(new icu.wuhui.voxlink.ui.TicketListScreen(this));
         cir.setReturnValue(true);
      }
   }

   /** 命中第 index 个按钮（0=稍后，1=查看）。 */
   private boolean inTicketButton(double mouseX, double mouseY, int index) {
      int x = this.ticketBtnX + (this.ticketBtnW + 6) * index;
      return mouseX >= x && mouseX < x + this.ticketBtnW
         && mouseY >= this.ticketBtnY && mouseY < this.ticketBtnY + 20;
   }

   /** 面板 + 文字 + 两个原版灰按钮，全部画在最后（最上层）。 */
   @Inject(method = "render", at = @At("TAIL"), require = 1)
   private void onRenderTail(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
      if (!TitleScreenMixin.ticketNoticeOn) {
         return;
      }
      int x = this.ticketCardX;
      int y = this.ticketCardY;
      int w = this.ticketCardW;
      int h = this.ticketCardH;
      // 原版输入框那套：纯黑底 + 浅灰 1px 边
      graphics.fill(x, y, x + w, y + h, VANILLA_BG);
      graphics.fill(x, y, x + w, y + 1, VANILLA_BORDER);
      graphics.fill(x, y + h - 1, x + w, y + h, VANILLA_BORDER);
      graphics.fill(x, y, x + 1, y + h, VANILLA_BORDER);
      graphics.fill(x + w - 1, y, x + w, y + h, VANILLA_BORDER);

      Minecraft mc = Minecraft.getInstance();
      String title = this.fitText(
         Component.translatable("voxlink.ticket.notice_title").getString(), w - 12, mc);
      graphics.drawString(mc.font, title, x + (w - mc.font.width(title)) / 2, y + 8, VANILLA_TEXT);
      String sub = this.fitText(
         Component.translatable("voxlink.ticket.notice_count", this.ticketUnreadCount()).getString(),
         w - 12, mc);
      graphics.drawString(mc.font, sub, x + (w - mc.font.width(sub)) / 2, y + 22, VANILLA_SUB);

      // 两个原版灰按钮
      int left = Math.max(0, (TitleScreenMixin.ticketLeftTicks + 19) / 20);
      String laterText = this.fitText(
         Component.translatable("voxlink.ticket.notice_later").getString() + " (" + left + "s)",
         this.ticketBtnW - 8, mc);
      String viewText = this.fitText(
         Component.translatable("voxlink.ticket.notice_view").getString(),
         this.ticketBtnW - 8, mc);
      this.drawVanillaButton(graphics, mouseX, mouseY, 0, laterText, mc);
      this.drawVanillaButton(graphics, mouseX, mouseY, 1, viewText, mc);
   }

   /** 画一个原版灰按钮（面 + 深边 + 居中文字，悬停/按下换底色）。 */
   private void drawVanillaButton(GuiGraphics graphics, int mouseX, int mouseY, int index,
                                  String text, Minecraft mc) {
      int x = this.ticketBtnX + (this.ticketBtnW + 6) * index;
      int y = this.ticketBtnY;
      int w = this.ticketBtnW;
      int h = 20;
      boolean down = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h
         && Minecraft.getInstance().mouseHandler.isLeftPressed();
      boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
      int face = down ? BTN_DOWN : (hover ? BTN_HOVER : BTN_FACE);
      // 深色外框 + 面色 + 顶部一道浅色高光，复刻原版按钮的立体感
      graphics.fill(x, y, x + w, y + h, BTN_EDGE);
      graphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, face);
      graphics.fill(x + 1, y + 1, x + w - 1, y + 2, down ? BTN_FACE : 0xFFB8B8B8);
      graphics.drawString(mc.font, text, x + (w - mc.font.width(text)) / 2, y + 6, VANILLA_TEXT);
   }

   /** 超宽文字按可用宽度截断补省略号（用原版字体度量，不猜字符数）。 */
   private String fitText(String text, int maxWidth, Minecraft mc) {
      if (mc.font.width(text) <= maxWidth) {
         return text;
      }
      String s = text;
      while (!s.isEmpty() && mc.font.width(s + "...") > maxWidth) {
         s = s.substring(0, s.length() - 1);
      }
      return s + "...";
   }

   /** 未读工单条数（面板副标题用）；读不到就当 1，不显示 0。 */
   private int ticketUnreadCount() {
      try {
         int n = 0;
         for (icu.wuhui.voxlink.network.TicketClient.LocalTicket t
               : icu.wuhui.voxlink.network.TicketClient.snapshot()) {
            if (t.hasUnread && !t.deleted) {
               n++;
            }
         }
         return n > 0 ? n : 1;
      } catch (Throwable t) {
         return 1;
      }
   }
}
