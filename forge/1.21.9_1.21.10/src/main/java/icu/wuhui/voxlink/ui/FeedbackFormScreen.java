package icu.wuhui.voxlink.ui;

import com.google.gson.JsonObject;
import icu.wuhui.voxlink.VoxLinkConstants;
import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.network.FeedbackUploader;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 站内反馈表单（游戏内）：描述 + 任意附件（系统文件选择器）+ 可选附加游戏日志，
 * 提交到信令服务器的 /feedback/submit（服务端按 IP 限频 10 分钟 1 次）。
 */
public class FeedbackFormScreen extends VoxLinkScreenBase {
   static {
      // MC 26.x 的 net.minecraft.client.main.Main 静态块把 java.awt.headless 设成了 "true"，
      // 会让一切 Swing 对话框抛 HeadlessException。Toolkit 是懒初始化的——
      // 在任何人触碰 AWT 之前把属性翻回 false 即可恢复窗口能力（老版本 MC 无此属性，翻转是无害 no-op）。
      try {
         if ("true".equals(System.getProperty("java.awt.headless"))) {
            System.setProperty("java.awt.headless", "false");
         }
      } catch (Throwable ignored) {
      }
   }

   private static final int FIELD_W = 220;
   private static final int DESC_H = 56;
   private static final int TITLE_Y = 15;
   private static final int MAX_FILE_LINES = 2;
   private final Screen chooser;   // 选择界面（FeedbackScreen）
   private final Screen parent;    // 最终返回的界面
   private MultiLineEditBox descBox;
   private Button submitButton;
   private final List<Path> attachments = new ArrayList<>();
   private boolean includeLogs = true;
   private boolean submitting = false;
   private boolean submitted = false;
   /** 限频截止时间戳（毫秒）：被服务端 429 后据此禁用提交按钮并显示剩余时间 */
   private long rateLimitedUntilMs = 0L;
   private String statusMessage = "";
   private int statusColor = VoxLinkColors.GRAY;
   /** 系统 LAF 只设置一次：Windows 原生文件对话框比 Metal 默认快且不会扫死网络位置 */
   private static volatile boolean systemLafReady = false;

   public FeedbackFormScreen(Screen chooser, Screen parent) {
      super(Component.translatable("voxlink.fb.form_title"));
      this.chooser = chooser;
      this.parent = parent;
   }

   @Override
   protected void init() {
      super.init();
      int centerX = this.width / 2;
      int x = centerX - FIELD_W / 2;
      this.descBox = MultiLineEditBox.builder()
         .setX(x)
         .setY(30)
         .setPlaceholder(Component.translatable("voxlink.fb.desc_hint"))
         .build(this.font, FIELD_W, DESC_H, Component.translatable("voxlink.fb.form_title"));
      this.addRenderableWidget(this.descBox);
      int rowY = 30 + DESC_H + 6;
      this.addRenderableWidget(
         Button.builder(Component.translatable("voxlink.fb.add_files"), button -> this.openFileChooser())
            .bounds(centerX - 100, rowY, 98, 20)
            .build()
      );
      this.addRenderableWidget(
         Button.builder(Component.translatable("voxlink.fb.clear_files"), button -> {
               this.attachments.clear();
               this.statusMessage = "";
            })
            .bounds(centerX + 2, rowY, 98, 20)
            .build()
      );
      int logY = rowY + 24;
      this.addRenderableWidget(
         Button.builder(Component.translatable("voxlink.fb.include_logs", new Object[]{Component.translatable(this.includeLogs ? "voxlink.fb.on" : "voxlink.fb.off")}), button -> {
               this.includeLogs = !this.includeLogs;
               button.setMessage(Component.translatable("voxlink.fb.include_logs", new Object[]{Component.translatable(this.includeLogs ? "voxlink.fb.on" : "voxlink.fb.off")}));
            })
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
         Button.builder(Component.translatable("voxlink.back"), button -> this.onClose())
            .bounds(centerX - 100, submitY + 22, 200, 20)
            .build()
      );
   }

   public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
      super.render(graphics, mouseX, mouseY, partialTick);
      int centerX = this.width / 2;
      this.drawCenteredString(graphics, this.title.getString(), centerX, TITLE_Y, VoxLinkColors.WHITE);
      this.drawStatus(graphics, centerX);
   }

   private void drawStatus(GuiGraphics graphics, int centerX) {
      int y = 30 + DESC_H + 6 + 24 + 20 + 6;
      if (this.submitted) {
         this.drawCenteredClipped(graphics, Component.translatable("voxlink.fb.success").getString(), centerX, y, VoxLinkColors.SUCCESS);
         y += 10;
         this.drawCenteredClipped(graphics, Component.translatable("voxlink.fb.rate_note").getString(), centerX, y, VoxLinkColors.MUTED);
         return;
      }

      // 限频倒计时：明确告诉玩家还剩多久能再提交，期间禁用提交按钮
      if (this.rateLimitedUntilMs > 0L) {
         long leftMs = this.rateLimitedUntilMs - System.currentTimeMillis();
         if (leftMs <= 0L) {
            this.rateLimitedUntilMs = 0L;
         } else {
            long totalSec = (leftMs + 999L) / 1000L;
            this.drawCenteredClipped(graphics,
               Component.translatable("voxlink.fb.rate_wait", new Object[]{totalSec / 60L, totalSec % 60L}).getString(),
               centerX, y, VoxLinkColors.WARNING);
            y += 10;
            if (this.submitButton != null) {
               this.submitButton.active = false;
            }
         }
      }

      long[] total = new long[]{0L};
      List<Path> files = FeedbackUploader.mergeFiles(this.attachments, this.includeLogs ? FeedbackUploader.defaultLogFiles(gameDir()) : null, total);
      if (files.isEmpty()) {
         this.drawCenteredClipped(graphics, Component.translatable("voxlink.fb.pick_drop").getString(), centerX, y, VoxLinkColors.MUTED);
         y += 10;
      }

      if (!files.isEmpty()) {
         String shown;
         if (files.size() <= MAX_FILE_LINES) {
            shown = joinNames(files);
         } else {
            shown = joinNames(files.subList(0, MAX_FILE_LINES))
               + Component.translatable("voxlink.fb.more_files", new Object[]{files.size() - MAX_FILE_LINES}).getString();
         }

         this.drawCenteredClipped(graphics, shown, centerX, y, VoxLinkColors.INFO);
         y += 10;
         this.drawCenteredClipped(graphics, formatBytes(total[0]), centerX, y, VoxLinkColors.MUTED);
         y += 10;
         if (total[0] > FeedbackUploader.MAX_TOTAL_BYTES) {
            this.drawCenteredClipped(graphics, Component.translatable("voxlink.fb.too_large").getString(), centerX, y, VoxLinkColors.ERROR);
            y += 10;
            this.submitButton.active = false;
         } else if (!this.submitting) {
            this.submitButton.active = true;
         }
      } else if (!this.submitting) {
         this.submitButton.active = true;
      }

      if (!this.statusMessage.isEmpty()) {
         this.drawCenteredClipped(graphics, this.statusMessage, centerX, this.height - 66, this.statusColor);
      } else if (this.submitting) {
         this.drawCenteredClipped(graphics, Component.translatable("voxlink.fb.submitting").getString(), centerX, this.height - 66, VoxLinkColors.MUTED);
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
         return String.format("%.2f GB", b / 1073741824.0);
      }

      if (b >= 1048576L) {
         return String.format("%.1f MB", b / 1048576.0);
      }

      if (b >= 1024L) {
         return String.format("%.1f KB", b / 1024.0);
      }

      return b + " B";
   }

   private static Path gameDir() {
      return Minecraft.getInstance().gameDirectory.toPath();
   }

   /**
    * 打开系统原生文件选择器（Windows/macOS 原生体验，可跨盘、任意路径）。
    * 三点是可靠弹出的关键：
    * 1. Swing 一律走 EDT（非 EDT 创建 JFileChooser 可能死锁或抛异常）；
    * 2. 用无边框 alwaysOnTop 的 UTILITY 窗口作属主——否则对话框会被全屏的
    *    Minecraft 窗口挡在后面，玩家看起来就是"点了没反应"；
    * 3. 整链路捕获 Throwable，headless/无 AWT 环境（部分启动器）给出明确提示，
    *    玩家仍可把文件直接拖进窗口（onFilesDrop）。
    */
   private void openFileChooser() {
      Thread t = new Thread(() -> SwingUtilities.invokeLater(() -> {
         // 若 LocalGE 已被别的代码以 headless 初始化，这里翻转无效——失败走拖放兜底
         System.setProperty("java.awt.headless", "false");
         JDialog owner = null;
         try {
            if (!systemLafReady) {
               try {
                  UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                  systemLafReady = true;
               } catch (Throwable e) {
                  // 失败保持 Metal 默认（功能不受影响），下次点击重试
                  VoxLinkMod.LOGGER.warn("[FeedbackFormScreen] system LAF unavailable: {}", e.toString());
               }
            }

            owner = new JDialog();
            owner.setUndecorated(true);
            owner.setType(JDialog.Type.UTILITY);
            owner.setAlwaysOnTop(true);
            owner.setBounds(0, 0, 0, 0);
            owner.setLocationRelativeTo(null);

            JFileChooser chooser = new JFileChooser(defaultChooserDir());
            chooser.setMultiSelectionEnabled(true);
            chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
            int r = chooser.showOpenDialog(owner);
            if (r != JFileChooser.APPROVE_OPTION) {
               return;
            }

            List<Path> picked = new ArrayList<>();
            for (File f : chooser.getSelectedFiles()) {
               picked.add(f.toPath());
            }

            if (picked.isEmpty()) {
               return;
            }

            Minecraft.getInstance().execute(() -> {
               for (Path p : picked) {
                  if (!this.attachments.contains(p)) {
                     this.attachments.add(p);
                  }
               }

               this.statusMessage = "";
            });
         } catch (Throwable e) {
            VoxLinkMod.LOGGER.warn("[FeedbackFormScreen] File chooser failed: {} (headless={}, property={})",
               e.toString(), java.awt.GraphicsEnvironment.isHeadless(), System.getProperty("java.awt.headless"));
            Minecraft.getInstance().execute(() -> this.setStatusKey("voxlink.fb.picker_failed", VoxLinkColors.ERROR));
         } finally {
            if (owner != null) {
               owner.dispose();
            }
         }
      }), "voxlink-feedback-picker");
      t.setDaemon(true);
      t.start();
   }

   /** 默认目录：优先 .minecraft/screenshots（玩家最常附截图），其次游戏目录。 */
   private static File defaultChooserDir() {
      try {
         File gameDir = Minecraft.getInstance().gameDirectory;
         File shots = new File(gameDir, "screenshots");
         return shots.isDirectory() ? shots : gameDir;
      } catch (Throwable ignored) {
         return null;
      }
   }

   /** 拖放支持：把文件直接拖进窗口即可加入附件（窗口化模式；全屏独占下系统不投递拖放事件）。 */
   public void onFilesDrop(List<Path> files) {
      if (files == null || files.isEmpty()) {
         return;
      }

      int added = 0;
      for (Path p : files) {
         if (Files.isDirectory(p)) {
            continue;
         }

         // 拖放可能带入系统锁定/不可读文件（如 C:\DumpStack.log），提前过滤避免提交时整单失败
         try (var ch = Files.newByteChannel(p)) {
         } catch (Throwable e) {
            VoxLinkMod.LOGGER.warn("[FeedbackFormScreen] drop skipped unreadable: {} ({})", p, e.toString());
            continue;
         }

         if (!this.attachments.contains(p)) {
            this.attachments.add(p);
            added++;
         }
      }

      if (added > 0) {
         this.statusMessage = Component.translatable("voxlink.fb.drop_added", new Object[]{added}).getString();
         this.statusColor = VoxLinkColors.SUCCESS;
      }
   }

   private void setStatusKey(String key, int color) {
      this.statusMessage = Component.translatable(key).getString();
      this.statusColor = color;
   }

   private void submitFeedback() {
      if (this.submitting || this.submitted) {
         return;
      }

      if (System.currentTimeMillis() < this.rateLimitedUntilMs) {
         this.statusMessage = "";
         return;
      }

      String desc = this.descBox.getValue().trim();
      List<Path> logs = this.includeLogs ? FeedbackUploader.defaultLogFiles(gameDir()) : null;
      long[] total = new long[]{0L};
      List<Path> files = FeedbackUploader.mergeFiles(this.attachments, logs, total);
      if (desc.isEmpty() && files.isEmpty()) {
         this.setStatusKey("voxlink.fb.err_empty", VoxLinkColors.ERROR);
         return;
      }

      if (total[0] > FeedbackUploader.MAX_TOTAL_BYTES) {
         this.setStatusKey("voxlink.fb.too_large", VoxLinkColors.ERROR);
         return;
      }

      this.submitting = true;
      this.submitButton.active = false;
      String url = VoxLinkMod.getConfig().getServerUrl();
      this.statusMessage = "";
      FeedbackUploader.submit(url, desc, buildClientInfo(), this.attachments, logs, result -> Minecraft.getInstance().execute(() -> {
         this.submitting = false;
         if (result.success) {
            this.submitted = true;
            this.attachments.clear();
            this.rateLimitedUntilMs = 0L;
         } else if ("RATE_LIMITED".equals(result.errorCode)) {
            int ra = result.retryAfterSec > 0 ? result.retryAfterSec : 600;
            this.rateLimitedUntilMs = System.currentTimeMillis() + ra * 1000L;
            this.statusMessage = "";
         } else if ("FEEDBACK_TOO_LARGE".equals(result.errorCode)) {
            this.setStatusKey("voxlink.fb.too_large", VoxLinkColors.ERROR);
         } else {
            this.statusMessage = Component.translatable("voxlink.fb.err_failed").getString() + " (" + result.errorCode + ")";
            this.statusColor = VoxLinkColors.ERROR;
         }
      }));
   }

   private static String buildClientInfo() {
      try {
         JsonObject info = new JsonObject();
         info.addProperty("gameVersion", VoxLinkConstants.GAME_VERSION);
         info.addProperty("loader", VoxLinkConstants.LOADER);
         info.addProperty("modVersion", VoxLinkMod.MOD_VERSION);
         Minecraft mc = Minecraft.getInstance();
         if (mc != null && mc.getUser() != null && mc.getUser().getName() != null) {
            info.addProperty("nickname", mc.getUser().getName());
         }

         return info.toString();
      } catch (Exception e) {
         return "{}";
      }
   }

   public boolean shouldCloseOnEsc() {
      return !this.submitting;
   }

   public void onClose() {
      if (this.submitting) {
         return;
      }

      Minecraft.getInstance().setScreen(this.parent);
   }
}
