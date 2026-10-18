package icu.wuhui.voxlink.ui;

import com.google.gson.JsonObject;
import icu.wuhui.voxlink.VoxLinkConstants;
import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.network.FeedbackUploader;
import icu.wuhui.voxlink.network.TicketClient;
import java.awt.GraphicsEnvironment;
import java.awt.Window.Type;
import java.io.File;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class FeedbackFormScreen extends VoxLinkScreenBase {
   private static final int FIELD_W = 220;
   private static final int DESC_H = 56;
   private static final int MIN_DESC_H = 40;
   private static final int MAX_TIP_EXAMPLES = 3;
   private static final int FIXED_TIP_LINES = 4;
   private static final int TIP_LINE_H = 10;
   private static final int STATUS_RESERVE = 40;
   private static final int TITLE_Y = 15;
   private static final int MAX_FILE_LINES = 2;
   private final Screen chooser;
   private final Screen parent;
   private MultiLineEditBox descBox;
   private int descH = 56;
   private int tipExamples = 3;
   private int rowY;
   private int logY;
   private int tipsY;
   private int statusY;
   private Button submitButton;
   private final List<Path> attachments = new ArrayList<>();
   private boolean includeLogs = true;
   private boolean submitting = false;
   private boolean submitted = false;
   private String createdTicketId;
   private long rateLimitedUntilMs = 0L;
   private String statusMessage = "";
   private int statusColor = -7829368;
   private static volatile boolean systemLafReady;

   public FeedbackFormScreen(Screen chooser, Screen parent) {
      super(Component.translatable("voxlink.fb.form_title"));
      this.chooser = chooser;
      this.parent = parent;
   }

   @Override
   protected void init() {
      super.init();
      this.computeLayout();
      int centerX = this.width / 2;
      int x = centerX - 110;
      this.descBox = MultiLineEditBox.builder()
         .setX(x)
         .setY(30)
         .setPlaceholder(Component.translatable("voxlink.fb.desc_hint"))
         .build(this.font, 220, this.descH, Component.translatable("voxlink.fb.form_title"));
      this.addRenderableWidget(this.descBox);
      int rowY = this.rowY;
      this.addRenderableWidget(
         Button.builder(Component.translatable("voxlink.fb.add_files"), button -> this.openFileChooser()).bounds(centerX - 100, rowY, 98, 20).build()
      );
      this.addRenderableWidget(Button.builder(Component.translatable("voxlink.fb.clear_files"), button -> {
         this.attachments.clear();
         this.statusMessage = "";
      }).bounds(centerX + 2, rowY, 98, 20).build());
      int logY = this.logY;
      this.addRenderableWidget(
         Button.builder(
               Component.translatable("voxlink.fb.include_logs", new Object[]{Component.translatable(this.includeLogs ? "voxlink.fb.on" : "voxlink.fb.off")}),
               button -> {
                  this.includeLogs = !this.includeLogs;
                  button.setMessage(
                     Component.translatable(
                        "voxlink.fb.include_logs", new Object[]{Component.translatable(this.includeLogs ? "voxlink.fb.on" : "voxlink.fb.off")}
                     )
                  );
               }
            )
            .bounds(centerX - 100, logY, 200, 20)
            .build()
      );
      int submitY = this.height - 46;
      this.submitButton = Button.builder(Component.translatable("voxlink.fb.submit"), button -> this.submitFeedback())
         .bounds(centerX - 100, submitY, 200, 20)
         .build();
      this.submitButton.active = !this.submitting;
      this.addRenderableWidget(this.submitButton);
      this.addRenderableWidget(
         Button.builder(Component.translatable("voxlink.back"), button -> this.onClose()).bounds(centerX - 100, submitY + 22, 200, 20).build()
      );
   }

   private void computeLayout() {
      int submitY = this.height - 46;
      this.descH = 56;
      this.tipExamples = 3;

      while (true) {
         int bottom = 30 + this.descH + 6 + 20 + 4 + 20 + 8 + (4 + this.tipExamples) * 10;
         if (bottom <= submitY - 40) {
            break;
         }

         if (this.descH > 40) {
            this.descH = Math.max(40, this.descH - 8);
         } else {
            if (this.tipExamples <= 0) {
               break;
            }

            this.tipExamples--;
         }
      }

      this.rowY = 30 + this.descH + 6;
      this.logY = this.rowY + 24;
      this.tipsY = this.logY + 24;
      this.statusY = this.tipsY + (4 + this.tipExamples) * 10 + 4;
   }

   private void drawTips(GuiGraphicsExtractor graphics, int centerX) {
      int y = this.tipsY;
      this.drawCenteredClipped(graphics, Component.translatable("voxlink.fb.guide_title").getString(), centerX, y, -171);
      y += 10;
      this.drawCenteredClipped(graphics, Component.translatable("voxlink.fb.guide_sug").getString(), centerX, y, -7829368);
      y += 10;
      this.drawCenteredClipped(graphics, Component.translatable("voxlink.fb.guide_bug").getString(), centerX, y, -7829368);
      y += 10;
      this.drawCenteredClipped(graphics, Component.translatable("voxlink.fb.tips_body").getString(), centerX, y, -7829368);
      y += 10;

      for (int i = 0; i < this.tipExamples; i++) {
         this.drawCenteredClipped(graphics, Component.translatable("voxlink.fb.tips_ex" + (i + 1)).getString(), centerX, y, -5592406);
         y += 10;
      }
   }

   public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
      super.extractRenderState(graphics, mouseX, mouseY, partialTick);
      int centerX = this.width / 2;
      this.drawCenteredString(graphics, this.title.getString(), centerX, 15, -1);
      this.drawTips(graphics, centerX);
      this.drawStatus(graphics, centerX);
   }

   private void drawStatus(GuiGraphicsExtractor graphics, int centerX) {
      int y = this.statusY;
      if (!this.submitted) {
         if (this.rateLimitedUntilMs > 0L) {
            long leftMs = this.rateLimitedUntilMs - System.currentTimeMillis();
            if (leftMs <= 0L) {
               this.rateLimitedUntilMs = 0L;
            } else {
               long totalSec = (leftMs + 999L) / 1000L;
               this.drawCenteredClipped(
                  graphics, Component.translatable("voxlink.fb.rate_wait", new Object[]{totalSec / 60L, totalSec % 60L}).getString(), centerX, y, -171
               );
               y += 10;
               if (this.submitButton != null) {
                  this.submitButton.active = false;
               }
            }
         }

         long[] total = new long[]{0L};
         List<Path> files = FeedbackUploader.mergeFiles(this.attachments, this.includeLogs ? FeedbackUploader.defaultLogFiles(gameDir()) : null, total);
         if (files.isEmpty()) {
            this.drawCenteredClipped(graphics, Component.translatable("voxlink.fb.pick_drop").getString(), centerX, y, -5592406);
            y += 10;
         }

         if (!files.isEmpty()) {
            String shown;
            if (files.size() <= 2) {
               shown = joinNames(files);
            } else {
               shown = joinNames(files.subList(0, 2)) + Component.translatable("voxlink.fb.more_files", new Object[]{files.size() - 2}).getString();
            }

            this.drawCenteredClipped(graphics, shown, centerX, y, -5592321);
            y += 10;
            this.drawCenteredClipped(graphics, formatBytes(total[0]), centerX, y, -5592406);
            y += 10;
            if (total[0] > 524288000L) {
               this.drawCenteredClipped(graphics, Component.translatable("voxlink.fb.too_large").getString(), centerX, y, -43691);
               y += 10;
               this.submitButton.active = false;
            } else if (!this.submitting) {
               this.submitButton.active = true;
            }
         } else if (!this.submitting) {
            this.submitButton.active = true;
         }

         int msgY = Math.min(y + 2, this.height - 58);
         if (!this.statusMessage.isEmpty()) {
            this.drawCenteredClipped(graphics, this.statusMessage, centerX, msgY, this.statusColor);
         } else if (this.submitting) {
            this.drawCenteredClipped(graphics, Component.translatable("voxlink.fb.submitting").getString(), centerX, msgY, -5592406);
         }
      } else {
         if (this.createdTicketId != null && !this.createdTicketId.isEmpty()) {
            this.drawCenteredClipped(
               graphics, Component.translatable("voxlink.ticket.created", new Object[]{this.createdTicketId}).getString(), centerX, y, -11141291
            );
         } else {
            this.drawCenteredClipped(graphics, Component.translatable("voxlink.fb.success").getString(), centerX, y, -11141291);
         }

         y += 10;
         this.drawCenteredClipped(graphics, Component.translatable("voxlink.fb.rate_note").getString(), centerX, y, -5592406);
      }
   }

   private static String joinNames(List<Path> files) {
      StringBuilder sb = new StringBuilder();

      for (Path p : files) {
         if (sb.length() > 0) {
            sb.append(", ");
         }

         sb.append(p.getFileName());
      }

      return sb.toString();
   }

   private static String formatBytes(long b) {
      if (b >= 1073741824L) {
         return String.format("%.2f GB", (double)b / 1.0737418E9F);
      } else if (b >= 1048576L) {
         return String.format("%.1f MB", (double)b / 1048576.0);
      } else {
         return b >= 1024L ? String.format("%.1f KB", (double)b / 1024.0) : b + " B";
      }
   }

   private static Path gameDir() {
      return Minecraft.getInstance().gameDirectory.toPath();
   }

   private void openFileChooser() {
      Thread t = new Thread(
         () -> SwingUtilities.invokeLater(
               () -> {
                  System.setProperty("java.awt.headless", "false");
                  JDialog owner = null;

                  try {
                     if (!systemLafReady) {
                        try {
                           UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                           systemLafReady = true;
                        } catch (Throwable var13) {
                           VoxLinkMod.LOGGER.warn("[FeedbackFormScreen] system LAF unavailable: {}", var13.toString());
                        }
                     }

                     owner = new JDialog();
                     owner.setUndecorated(true);
                     owner.setType(Type.UTILITY);
                     owner.setAlwaysOnTop(true);
                     owner.setBounds(0, 0, 0, 0);
                     owner.setLocationRelativeTo(null);
                     JFileChooser chooser = new JFileChooser(defaultChooserDir());
                     chooser.setMultiSelectionEnabled(true);
                     chooser.setFileSelectionMode(0);
                     int r = chooser.showOpenDialog(owner);
                     if (r != 0) {
                        return;
                     }

                     List<Path> picked = new ArrayList<>();

                     for (File f : chooser.getSelectedFiles()) {
                        picked.add(f.toPath());
                     }

                     if (!picked.isEmpty()) {
                        Minecraft.getInstance().execute(() -> {
                           for (Path p : picked) {
                              if (!this.attachments.contains(p)) {
                                 this.attachments.add(p);
                              }
                           }

                           this.statusMessage = "";
                        });
                        return;
                     }
                  } catch (Throwable var14) {
                     VoxLinkMod.LOGGER
                        .warn(
                           "[FeedbackFormScreen] File chooser failed: {} (headless={}, property={})",
                           new Object[]{var14.toString(), GraphicsEnvironment.isHeadless(), System.getProperty("java.awt.headless")}
                        );
                     Minecraft.getInstance().execute(() -> this.setStatusKey("voxlink.fb.picker_failed", -43691));
                     return;
                  } finally {
                     if (owner != null) {
                        owner.dispose();
                     }
                  }
               }
            ),
         "voxlink-feedback-picker"
      );
      t.setDaemon(true);
      t.start();
   }

   private static File defaultChooserDir() {
      try {
         File gameDir = Minecraft.getInstance().gameDirectory;
         File shots = new File(gameDir, "screenshots");
         return shots.isDirectory() ? shots : gameDir;
      } catch (Throwable var2) {
         return null;
      }
   }

   public void onFilesDrop(List<Path> files) {
      if (files != null && !files.isEmpty()) {
         int added = 0;

         for (Path p : files) {
            if (!Files.isDirectory(p)) {
               try {
                  SeekableByteChannel ch = Files.newByteChannel(p);
                  if (ch != null) {
                     ch.close();
                  }
               } catch (Throwable var6) {
                  VoxLinkMod.LOGGER.warn("[FeedbackFormScreen] drop skipped unreadable: {} ({})", p, var6.toString());
                  continue;
               }

               if (!this.attachments.contains(p)) {
                  this.attachments.add(p);
                  added++;
               }
            }
         }

         if (added > 0) {
            this.statusMessage = Component.translatable("voxlink.fb.drop_added", new Object[]{added}).getString();
            this.statusColor = -11141291;
         }
      }
   }

   private void setStatusKey(String key, int color) {
      this.statusMessage = Component.translatable(key).getString();
      this.statusColor = color;
   }

   private void submitFeedback() {
      if (!this.submitting && !this.submitted) {
         if (System.currentTimeMillis() < this.rateLimitedUntilMs) {
            this.statusMessage = "";
         } else {
            String desc = this.descBox.getValue().trim();
            List<Path> logs = this.includeLogs ? FeedbackUploader.defaultLogFiles(gameDir()) : null;
            long[] total = new long[]{0L};
            List<Path> files = FeedbackUploader.mergeFiles(this.attachments, logs, total);
            if (desc.isEmpty() && files.isEmpty()) {
               this.setStatusKey("voxlink.fb.err_empty", -43691);
            } else if (total[0] > 524288000L) {
               this.setStatusKey("voxlink.fb.too_large", -43691);
            } else {
               this.submitting = true;
               this.submitButton.active = false;
               String url = VoxLinkMod.getConfig().getServerUrl();
               this.statusMessage = "";
               TicketClient.submit(url, desc, buildClientInfo(), files, result -> Minecraft.getInstance().execute(() -> {
                     this.submitting = false;
                     if (result.success) {
                        this.submitted = true;
                        this.createdTicketId = result.ticketId;
                        this.attachments.clear();
                        this.rateLimitedUntilMs = 0L;
                     } else if ("RATE_LIMITED".equals(result.errorCode)) {
                        int ra = result.retryAfterSec > 0 ? result.retryAfterSec : 600;
                        this.rateLimitedUntilMs = System.currentTimeMillis() + (long)ra * 1000L;
                        this.statusMessage = "";
                     } else if ("TICKET_TOO_LARGE".equals(result.errorCode)) {
                        this.setStatusKey("voxlink.fb.too_large", -43691);
                     } else {
                        this.statusMessage = Component.translatable("voxlink.fb.err_failed").getString() + " (" + result.errorCode + ")";
                        this.statusColor = -43691;
                     }
                  }));
            }
         }
      }
   }

   private static String buildClientInfo() {
      try {
         JsonObject info = new JsonObject();
         info.addProperty("gameVersion", VoxLinkConstants.GAME_VERSION);
         info.addProperty("loader", "forge");
         info.addProperty("modVersion", VoxLinkMod.MOD_VERSION);
         Minecraft mc = Minecraft.getInstance();
         if (mc != null && mc.getUser() != null && mc.getUser().getName() != null) {
            info.addProperty("nickname", mc.getUser().getName());
         }

         return info.toString();
      } catch (Exception var2) {
         return "{}";
      }
   }

   public boolean shouldCloseOnEsc() {
      return !this.submitting;
   }

   public void onClose() {
      if (!this.submitting) {
         Minecraft.getInstance().gui.setScreen(this.parent);
      }
   }

   static {
      try {
         if ("true".equals(System.getProperty("java.awt.headless"))) {
            System.setProperty("java.awt.headless", "false");
         }
      } catch (Throwable var1) {
      }

      systemLafReady = false;
   }
}
