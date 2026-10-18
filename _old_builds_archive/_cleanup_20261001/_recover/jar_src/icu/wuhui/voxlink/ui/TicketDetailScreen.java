package icu.wuhui.voxlink.ui;

import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.network.TicketClient;
import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class TicketDetailScreen extends VoxLinkScreenBase {
   private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());
   private static final int PAGE_SIZE = 5;
   private static final int MSG_LINE_W = 226;
   private final Screen listScreen;
   private final String ticketId;
   private TicketClient.Detail detail;
   private boolean loadFailed = false;
   private int page = 0;
   private EditBox replyBox;
   private Button sendButton;
   private final List<Path> replyAttachments = new CopyOnWriteArrayList<>();
   private boolean sending = false;
   private long rateLimitedUntilMs = 0L;
   private String statusMessage = "";
   private int statusColor = -7829368;

   public TicketDetailScreen(Screen listScreen, Screen parent, String ticketId) {
      super(Component.literal("#" + ticketId));
      this.listScreen = listScreen;
      this.ticketId = ticketId;
   }

   @Override
   protected void init() {
      super.init();
      this.buildUi();
      if (this.detail == null && !this.loadFailed) {
         this.refresh();
      }
   }

   private void buildUi() {
      this.clearOurWidgets();
      int centerX = this.width / 2;
      int inputY = this.height - 72;
      this.replyBox = new EditBox(this.font, centerX - 110, inputY, 166, 20, Component.translatable("voxlink.ticket.reply_hint"));
      this.replyBox.setMaxLength(2000);
      this.addRenderableWidget(this.replyBox);
      this.sendButton = Button.builder(Component.translatable("voxlink.ticket.send"), button -> this.sendReply()).bounds(centerX + 60, inputY, 50, 20).build();
      this.sendButton.active = !this.sending && System.currentTimeMillis() >= this.rateLimitedUntilMs;
      this.addRenderableWidget(this.sendButton);
      int rowY = inputY + 24;
      this.addRenderableWidget(
         Button.builder(Component.translatable("voxlink.ticket.add_attach"), button -> this.openFileChooser()).bounds(centerX - 110, rowY, 106, 20).build()
      );
      this.addRenderableWidget(
         Button.builder(Component.translatable("voxlink.ticket.clear_attach"), button -> this.replyAttachments.clear())
            .bounds(centerX + 4, rowY, 106, 20)
            .build()
      );
      this.addRenderableWidget(
         Button.builder(Component.translatable("voxlink.ticket.delete"), button -> this.deleteTicket()).bounds(centerX - 110, rowY + 22, 106, 20).build()
      );
      this.addRenderableWidget(Button.builder(Component.translatable("voxlink.back"), button -> this.onClose()).bounds(centerX + 4, rowY + 22, 106, 20).build());
   }

   private void refresh() {
      String url = VoxLinkMod.getConfig().getServerUrl();
      TicketClient.fetchDetail(url, this.ticketId, d -> Minecraft.getInstance().execute(() -> {
            if (d == null) {
               this.loadFailed = true;
            } else {
               this.detail = d;
               this.loadFailed = false;
            }

            if (this.detail != null) {
               TicketClient.markViewed(url, this.ticketId);
            }
         }));
   }

   private void sendReply() {
      if (!this.sending && System.currentTimeMillis() >= this.rateLimitedUntilMs) {
         String text = this.replyBox.getValue().trim();
         if (text.isEmpty() && this.replyAttachments.isEmpty()) {
            this.setStatus("voxlink.ticket.err_empty", -43691);
         } else {
            this.sending = true;
            this.sendButton.active = false;
            this.statusMessage = "";
            String url = VoxLinkMod.getConfig().getServerUrl();
            TicketClient.reply(url, this.ticketId, text, new ArrayList<>(this.replyAttachments), result -> Minecraft.getInstance().execute(() -> {
                  this.sending = false;
                  if (result.success) {
                     this.replyBox.setValue("");
                     this.replyAttachments.clear();
                     this.statusMessage = "";
                     this.refresh();
                  } else if ("RATE_LIMITED".equals(result.errorCode)) {
                     int ra = result.retryAfterSec > 0 ? result.retryAfterSec : 600;
                     this.rateLimitedUntilMs = System.currentTimeMillis() + (long)ra * 1000L;
                     this.setStatus("voxlink.ticket.rate_limited", -171);
                  } else {
                     this.statusMessage = Component.translatable("voxlink.ticket.err_failed").getString() + " (" + result.errorCode + ")";
                     this.statusColor = -43691;
                  }

                  if (this.sendButton != null) {
                     this.sendButton.active = System.currentTimeMillis() >= this.rateLimitedUntilMs;
                  }
               }));
         }
      }
   }

   private void deleteTicket() {
      TicketClient.deleteLocal(VoxLinkMod.getConfig().getServerUrl(), this.ticketId);
      Minecraft.getInstance().gui.setScreen(this.listScreen);
   }

   private void setStatus(String key, int color) {
      this.statusMessage = Component.translatable(key).getString();
      this.statusColor = color;
   }

   public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
      super.extractRenderState(graphics, mouseX, mouseY, partialTick);
      int centerX = this.width / 2;
      this.drawTitle(graphics, 15);
      if (this.detail == null) {
         this.drawCenteredClipped(
            graphics,
            Component.translatable(this.loadFailed ? "voxlink.ticket.load_failed" : "voxlink.ticket.loading").getString(),
            centerX,
            this.height / 2 - 10,
            this.loadFailed ? -43691 : -5592406
         );
      } else {
         TicketClient.Detail d = this.detail;
         String status = TIME_FMT.format(Instant.ofEpochMilli(d.timeMs))
            + (d.deleted ? "  " + Component.translatable("voxlink.ticket.tag_deleted").getString() : "");
         this.drawCenteredClipped(graphics, status, centerX, 27, -7829368);
         List<String[]> timeline = new ArrayList<>();
         timeline.add(new String[]{"me", d.description, attachNames(d.attachments)});

         for (TicketClient.Msg m : d.messages) {
            timeline.add(new String[]{"admin".equals(m.from) ? "admin" : "me", m.text, attachNames(m.attachments)});
         }

         int totalPages = Math.max(1, (timeline.size() + 5 - 1) / 5);
         if (this.page >= totalPages) {
            this.page = totalPages - 1;
         }

         if (this.page < 0) {
            this.page = 0;
         }

         int y = 42;
         int start = this.page * 5;
         int end = Math.min(timeline.size(), start + 5);

         for (int i = start; i < end; i++) {
            String[] entry = timeline.get(i);
            boolean isAdmin = "admin".equals(entry[0]);
            String head = isAdmin
               ? Component.translatable("voxlink.ticket.from_admin").getString()
               : Component.translatable("voxlink.ticket.from_me").getString();
            this.drawString(graphics, head, centerX - 113, y, isAdmin ? -171 : -11141291);
            y += 11;

            for (String line : this.wrapLines(entry[1], 226, 2)) {
               this.drawString(graphics, line, centerX - 113, y, -1);
               y += 11;
            }

            if (!entry[2].isEmpty()) {
               this.drawString(graphics, entry[2], centerX - 113, y, -7829368);
               y += 11;
            }

            y += 4;
         }

         if (totalPages > 1) {
            this.drawCenteredClipped(
               graphics,
               Component.translatable("voxlink.ticket.page", new Object[]{this.page + 1, totalPages}).getString(),
               centerX,
               this.height - 92,
               -7829368
            );
         }

         if (!this.replyAttachments.isEmpty()) {
            this.drawCenteredClipped(
               graphics,
               Component.translatable("voxlink.ticket.attach_count", new Object[]{this.replyAttachments.size()}).getString(),
               centerX,
               this.height - 92,
               -7829368
            );
         }

         if (!this.statusMessage.isEmpty()) {
            this.drawCenteredClipped(graphics, this.statusMessage, centerX, this.height - 50, this.statusColor);
         }
      }
   }

   private static String attachNames(List<TicketClient.Attach> atts) {
      if (atts != null && !atts.isEmpty()) {
         StringBuilder sb = new StringBuilder(Component.translatable("voxlink.ticket.attach_prefix").getString());

         for (int i = 0; i < atts.size() && i < 4; i++) {
            if (i > 0) {
               sb.append(", ");
            }

            sb.append(atts.get(i).name);
         }

         if (atts.size() > 4) {
            sb.append(" +").append(atts.size() - 4);
         }

         return sb.toString();
      } else {
         return "";
      }
   }

   private List<String> wrapLines(String text, int maxWidth, int maxLines) {
      List<String> out = new ArrayList<>();
      String remain = text == null ? "" : text;

      while (!remain.isEmpty() && out.size() < maxLines) {
         int take = remain.length();

         while (take > 1 && this.fontWidth(remain.substring(0, take)) > maxWidth) {
            take--;
         }

         boolean last = out.size() == maxLines - 1;
         if (last && take < remain.length()) {
            String clipped = remain.substring(0, take);

            while (take > 1 && this.fontWidth(clipped + "…") > maxWidth) {
               clipped = remain.substring(0, --take);
            }

            out.add(clipped + "…");
            return out;
         }

         out.add(remain.substring(0, take));
         remain = remain.substring(take);
      }

      return out;
   }

   private void openFileChooser() {
      Thread t = new Thread(() -> SwingUtilities.invokeLater(() -> {
            System.setProperty("java.awt.headless", "false");
            JDialog owner = null;

            try {
               try {
                  UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
               } catch (Throwable var11) {
               }

               owner = new JDialog();
               owner.setUndecorated(true);
               owner.setAlwaysOnTop(true);
               owner.setVisible(true);
               JFileChooser chooser = new JFileChooser();
               chooser.setMultiSelectionEnabled(true);
               if (chooser.showOpenDialog(owner) == 0) {
                  for (File f : chooser.getSelectedFiles()) {
                     this.replyAttachments.add(f.toPath());
                  }
               }
            } catch (Throwable var12) {
               VoxLinkMod.LOGGER.warn("[Ticket] file chooser failed: {}", var12.toString());
            } finally {
               if (owner != null) {
                  owner.dispose();
               }
            }
         }));
      t.setDaemon(true);
      t.start();
   }

   public void onClose() {
      Minecraft.getInstance().gui.setScreen(this.listScreen);
   }

   public boolean shouldCloseOnEsc() {
      return !this.sending;
   }
}
