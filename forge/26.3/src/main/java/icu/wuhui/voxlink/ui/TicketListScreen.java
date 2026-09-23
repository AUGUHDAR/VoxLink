package icu.wuhui.voxlink.ui;

import icu.wuhui.voxlink.network.TicketClient;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 我的工单列表：本地存单号的历史工单（新→旧），带状态标签与分页；
 * 点击进入工单详情（追问/查看回复）。
 */
public class TicketListScreen extends VoxLinkScreenBase {
   private static final DateTimeFormatter TIME_FMT =
      DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());
   private static final int PAGE_SIZE = 6;

   private final Screen parent;
   private List<TicketClient.LocalTicket> rows = new ArrayList<>();
   private int page = 0;

   public TicketListScreen(Screen parent) {
      super(Component.translatable("voxlink.ticket.list_title"));
      this.parent = parent;
   }

   protected void init() {
      super.init();
      this.rows = TicketClient.snapshot();
      this.rebuild();
   }

   private void rebuild() {
      this.clearOurWidgets();
      int centerX = this.width / 2;
      int totalPages = Math.max(1, (this.rows.size() + PAGE_SIZE - 1) / PAGE_SIZE);
      if (this.page >= totalPages) {
         this.page = totalPages - 1;
      }
      if (this.page < 0) {
         this.page = 0;
      }

      int listTop = this.height / 2 - 78;
      int start = this.page * PAGE_SIZE;
      int end = Math.min(this.rows.size(), start + PAGE_SIZE);
      for (int i = start; i < end; i++) {
         TicketClient.LocalTicket t = this.rows.get(i);
         int y = listTop + (i - start) * 24;
         this.addRenderableWidget(
            Button.builder(this.rowLabel(t), button -> this.openDetail(t))
               .bounds(centerX - 110, y, 220, 20)
               .build()
         );
      }

      int navY = listTop + PAGE_SIZE * 24 + 4;
      if (totalPages > 1) {
         this.addRenderableWidget(
            Button.builder(Component.translatable("voxlink.ticket.prev_page"), button -> {
                  this.page = Math.max(0, this.page - 1);
                  this.rebuild();
               })
               .bounds(centerX - 110, navY, 106, 20)
               .build()
         );
         this.addRenderableWidget(
            Button.builder(Component.translatable("voxlink.ticket.next_page"), button -> {
                  this.page = Math.min(totalPages - 1, this.page + 1);
                  this.rebuild();
               })
               .bounds(centerX + 4, navY, 106, 20)
               .build()
         );
      }

      int backY = Math.max(navY + 24, this.height - 30);
      this.addRenderableWidget(
         Button.builder(Component.translatable("voxlink.back"), button -> this.onClose())
            .bounds(centerX - 100, backY, 200, 20)
            .build()
      );
   }

   private Component rowLabel(TicketClient.LocalTicket t) {
      String time = TIME_FMT.format(Instant.ofEpochMilli(t.lastTimeMs != 0 ? t.lastTimeMs : t.timeMs));
      String tag;
      if (t.deleted) {
         tag = Component.translatable("voxlink.ticket.tag_deleted").getString();
      } else if (t.hasUnread) {
         tag = Component.translatable("voxlink.ticket.tag_unread").getString();
      } else if (t.replyCount > 0) {
         tag = Component.translatable("voxlink.ticket.tag_viewed").getString();
      } else {
         tag = Component.translatable("voxlink.ticket.tag_waiting").getString();
      }
      return Component.literal("#" + t.id + "  " + tag + "  " + time);
   }

   private void openDetail(TicketClient.LocalTicket t) {
      Minecraft.getInstance().gui.setScreen(new TicketDetailScreen(this, this.parent, t.id));
   }

   public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
      super.extractRenderState(graphics, mouseX, mouseY, partialTick);
      int centerX = this.width / 2;
      this.drawTitle(graphics, 15);
      if (this.rows.isEmpty()) {
         this.drawCenteredClipped(graphics, Component.translatable("voxlink.ticket.empty").getString(),
            centerX, this.height / 2 - 10, VoxLinkColors.MUTED);
      } else if (this.rows.size() > 1) {
         this.drawCenteredClipped(graphics, Component.translatable("voxlink.ticket.count", this.rows.size()).getString(),
            centerX, 28, VoxLinkColors.GRAY);
      }
   }

   public void onClose() {
      Minecraft.getInstance().gui.setScreen(this.parent);
   }

   public boolean shouldCloseOnEsc() {
      return true;
   }
}
