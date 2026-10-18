# -*- coding: utf-8 -*-
"""2026-08-31 UI 审查修复轮·收尾传播（r3 锚点 era/混合行尾兼容版）：
- AJS 9 处（22 模板）：monitor 终态改单行锚点（era 中性）
- VLS 2 处（neoforge/26.2）：eol 双探测适配混合行尾
- TCS removed()（22 模板）：单行锚点
- MRS 可编辑恢复（24 模板）：两行 era 中性锚点
幂等：new 文本已存在视为成功；LF/CRLF 双探测取 count==1 者。
"""
import os, sys, io

ROOT = os.path.dirname(os.path.abspath(__file__))
LOADERS = ["fabric", "forge", "neoforge"]
CM = "src/main/java/icu/wuhui/voxlink/room/ConnectionManager.java"
AJS = "src/main/java/icu/wuhui/voxlink/ui/AttemptingJoinScreen.java"
VLS = "src/main/java/icu/wuhui/voxlink/ui/VoxLinkScreen.java"
TCS = "src/main/java/icu/wuhui/voxlink/ui/TerracottaConfigScreen.java"
MRS = "src/main/java/icu/wuhui/voxlink/ui/ManageRoomScreen.java"

AJS_PAIRS = [
("""      if (!this.terracottaStatusText.isEmpty()) {
         // 中继进行中时下方槽位优先显示进度文本（搜索 / 第 N 轮…）,
         // 没在进行中才走原有的 terracottaStatusText。
         ConnectionManager cm = VoxLinkMod.getRoomManager().getConnectionManager();
         Component relayProgress = cm != null ? cm.getRelayProgressText() : null;
         if (relayProgress != null) {
            String progressText = relayProgress.getString();
            String clipped = progressText;
            int maxWidth = this.width - 20;
            if (this.fontWidth(clipped) > maxWidth) {
               while (this.fontWidth(clipped + "...") > maxWidth && clipped.length() > 0) {
                  clipped = clipped.substring(0, clipped.length() - 1);
               }

               clipped = clipped + "...";
            }

            this.drawCenteredString(graphics, clipped, centerX, this.height / 2 + 14, VoxLinkColors.WARNING);
         } else {
         String label = Component.translatable("voxlink.dual.terracotta_label").getString();
         String clipped = this.terracottaStatusText;
         int maxWidth = this.width - 20;
         if (this.fontWidth(label + ": " + clipped) > maxWidth) {
            while (this.fontWidth(label + ": " + clipped + "...") > maxWidth && clipped.length() > 0) {
               clipped = clipped.substring(0, clipped.length() - 1);
            }

            clipped = clipped + "...";
         }

         this.drawCenteredString(graphics, label + ": " + clipped, centerX, this.height / 2 + 14, this.terracottaStatusColor);
         }

      }""",
"""      // 下方状态行 = 专用中继槽位：TURN 状态 > 玩家中继进度 > Terracotta 通道状态。
      // 三者互斥占用；绝不能嵌套在 terracotta 非空分支里——Terracotta 未启用时中继进度会整场不可见。
      ConnectionManager cm = VoxLinkMod.getRoomManager().getConnectionManager();
      Component turnStatus = cm != null ? cm.getTurnStatusText() : null;
      Component relayProgress = cm != null ? cm.getRelayProgressText() : null;
      String line2 = null;
      int line2Color = VoxLinkColors.WARNING;
      if (turnStatus != null) {
         line2 = turnStatus.getString();
         line2Color = cm.isTurnActive() ? VoxLinkColors.SUCCESS : VoxLinkColors.WARNING;
      } else if (relayProgress != null) {
         line2 = relayProgress.getString();
      } else if (!this.terracottaStatusText.isEmpty()) {
         line2 = Component.translatable("voxlink.dual.terracotta_label").getString() + ": " + this.terracottaStatusText;
         line2Color = this.terracottaStatusColor;
      }

      if (line2 != null && !line2.isEmpty()) {
         String clipped = line2;
         int maxWidth = this.width - 20;
         if (this.fontWidth(clipped) > maxWidth) {
            while (this.fontWidth(clipped + "...") > maxWidth && clipped.length() > 0) {
               clipped = clipped.substring(0, clipped.length() - 1);
            }

            clipped = clipped + "...";
         }

         this.drawCenteredString(graphics, clipped, centerX, this.height / 2 + 14, line2Color);
      }"""),
("""   private volatile boolean relayButtonVisible = false;
   private volatile long relayFailedMsgTime = 0L;
   private volatile boolean lastManualRelayInProgress = false;""",
"""   private volatile boolean relayButtonVisible = false;
   private volatile long relayFailedMsgTime = 0L;
   private volatile boolean lastManualRelayInProgress = false;
   /** TURN 由忙转闲且未连接成功：显示 3 秒"中继不可用"瞬态提示（与玩家中继失败提示同机制）。 */
   private volatile long turnFailedMsgTime = 0L;
   private volatile boolean lastTurnBusy = false;"""),
("""      if (bridgeReady) {
         this.active = false;
         room.setConnectionMode(Component.translatable("voxlink.connection.connected"));
         this.voxlinkFinal = true;
         this.voxlinkStatusText = Component.translatable("voxlink.dual.p2p_established").getString();
         this.voxlinkStatusColor = VoxLinkColors.SUCCESS;""",
"""      if (bridgeReady) {
         this.active = false;
         room.setConnectionMode(Component.translatable("voxlink.connection.connected"));
         this.voxlinkFinal = true;
         // 经中继建立时文案必须是"已通过中继连接"，不能谎报 P2P 已建立
         this.voxlinkStatusText = Component.translatable(room.isUsingRelay() ? "voxlink.relay.connected_via" : "voxlink.dual.p2p_established").getString();
         this.voxlinkStatusColor = VoxLinkColors.SUCCESS;"""),
# monitor 终态：单行锚点（era 中性；init 处已被上一对改写，此处全局唯一）
("""                              AttemptingJoinScreen.this.voxlinkStatusText = Component.translatable("voxlink.dual.p2p_established").getString();""",
"""                              AttemptingJoinScreen.this.voxlinkStatusText = Component.translatable(
                                 roomInfo.isUsingRelay() ? "voxlink.relay.connected_via" : "voxlink.dual.p2p_established"
                              ).getString();"""),
("""                  AttemptingJoinScreen.this.lastManualRelayInProgress = currentRelayInProgress;""",
"""                  AttemptingJoinScreen.this.lastManualRelayInProgress = currentRelayInProgress;
                  // TURN 失败瞬态：进行中→空闲且未连上 = 失败，给 3 秒可见反馈（否则只有静默 teardown）
                  boolean turnBusyNow = cmTurn.isTurnInProgress() || cmTurn.isTurnActive();
                  if (AttemptingJoinScreen.this.lastTurnBusy && !turnBusyNow) {
                     AttemptingJoinScreen.this.turnFailedMsgTime = System.currentTimeMillis();
                  }

                  AttemptingJoinScreen.this.lastTurnBusy = turnBusyNow;"""),
("""      if (this.relayFailedMsgTime > 0L) {
         long elapsed = System.currentTimeMillis() - this.relayFailedMsgTime;
         if (elapsed < 3000L) {
            this.drawCenteredString(graphics, Component.translatable("voxlink.relay.failed_retry_punch").getString(), centerX, this.height / 2 - 30 - 12, VoxLinkColors.WARNING);
         } else {
            this.relayFailedMsgTime = 0L;
         }
      }""",
"""      if (this.relayFailedMsgTime > 0L) {
         long elapsed = System.currentTimeMillis() - this.relayFailedMsgTime;
         if (elapsed < 3000L) {
            this.drawCenteredString(graphics, Component.translatable("voxlink.relay.failed_retry_punch").getString(), centerX, this.height / 2 - 30 - 12, VoxLinkColors.WARNING);
         } else {
            this.relayFailedMsgTime = 0L;
         }
      }

      if (this.turnFailedMsgTime > 0L) {
         long elapsedTurn = System.currentTimeMillis() - this.turnFailedMsgTime;
         if (elapsedTurn < 3000L) {
            this.drawCenteredString(graphics, Component.translatable("voxlink.turn.failed").getString(), centerX, this.height / 2 - 30 - 12, VoxLinkColors.WARNING);
         } else {
            this.turnFailedMsgTime = 0L;
         }
      }"""),
("""      if (!bridgeReady && this.active && this.turnButtonVisible) {
         this.addRenderableWidget(
            Button.builder(Component.translatable("voxlink.turn.use"), button -> this.onTurnButtonClicked())
               .bounds(this.width - 104, 4, 100, 20)
               .build()
         );
      }""",
"""      // TURN"使用中继"：右上角小按钮。y=26 避开标题（标题文字在 y=15..24，y=4 会与标题同一高度带重叠）
      if (!bridgeReady && this.active && this.turnButtonVisible) {
         this.addRenderableWidget(
            Button.builder(Component.translatable("voxlink.turn.use"), button -> this.onTurnButtonClicked())
               .bounds(this.width - 104, 26, 100, 20)
               .build()
         );
      }"""),
("""   private void onRelayButtonClicked() {
      if (VoxLinkMod.getConfig().isRelayEnabled()) {
         this.relayButtonVisible = false;
         this.lastManualRelayInProgress = true;
         this.clearOurWidgets();
         this.init();
         VoxLinkMod.getRoomManager().getConnectionManager().triggerManualRelay();
      }
   }

   /** "取消中继"按钮: 调用 ConnectionManager.cancelManualRelay(), 然后刷新布局回到直连态。 */
   private void onCancelRelayClicked() {
      VoxLinkMod.getRoomManager().getConnectionManager().cancelManualRelay();
      // 让 AttemptingJoinScreen 重新走 init: manualRelayInProgress=false 后, canShowRelayButton 会重新判定。
      this.clearOurWidgets();
      this.init();
   }""",
"""   private void onRelayButtonClicked() {
      if (VoxLinkMod.getConfig().isRelayEnabled()) {
         // 先触发再重建：triggerManualRelay 同步置 manualRelayInProgress=true，
         // init() 里 canShowRelayButton() 立即为 false，按钮不会被重新加回（原先有 500ms 幽灵按钮窗口）
         VoxLinkMod.getRoomManager().getConnectionManager().triggerManualRelay();
         this.relayButtonVisible = false;
         this.lastManualRelayInProgress = true;
         // 即时反馈：不等 monitor + 2s 抑制窗口，点击当场切换状态文本
         this.voxlinkStatusText = Component.translatable("voxlink.relay.trying").getString();
         this.voxlinkStatusColor = VoxLinkColors.WARNING;
         this.voxlinkStatusLastUpdate = System.currentTimeMillis();
         this.clearOurWidgets();
         this.init();
      }
   }

   /** "取消中继"按钮: 调用 ConnectionManager.cancelManualRelay(), 然后刷新布局回到直连态。 */
   private void onCancelRelayClicked() {
      VoxLinkMod.getRoomManager().getConnectionManager().cancelManualRelay();
      // 手动取消不是失败：同步复位沿检测，否则下一个 monitor tick 会弹出"中继失败"误报
      this.lastManualRelayInProgress = false;
      // 让 AttemptingJoinScreen 重新走 init: manualRelayInProgress=false 后, canShowRelayButton 会重新判定。
      this.clearOurWidgets();
      this.init();
   }"""),
("""   public void onRoomLost() {
      this.stopConnectionMonitor();
      this.active = false;""",
"""   public void onRoomLost() {
      this.stopConnectionMonitor();
      this.active = false;
      // 房间丢失必须给终态文案：否则状态行残留旧的打洞进度（如"UDP打洞…"），玩家不知道发生了什么
      this.voxlinkFinal = true;
      this.voxlinkStatusText = Component.translatable("voxlink.room_lost").getString();
      this.voxlinkStatusColor = VoxLinkColors.ERROR;"""),
]

VLS_PAIRS = [
("""            this.lyTopStart = Math.max(headerFloor + 4, ideal);
            this.lyWebsite = this.lyTopStart + sectionH - L_ROW;
            if (decor) {""",
"""            this.lyTopStart = Math.max(headerFloor + 4, ideal);
            this.lyWebsite = this.lyTopStart + sectionH - L_ROW;
            // 极矮屏强制提交时官网行会与下载行同位叠合（240 高实测同 y=116）：
            // 裁掉官网行保下载行——下载是更关键的 CTA（与"装饰先裁"同一准则）
            if (showDownloadRow && this.lyDownloadRow != Integer.MIN_VALUE && this.lyWebsite >= this.lyDownloadRow - L_ROW) {
               this.lyWebsite = Integer.MIN_VALUE;
            }
            if (decor) {"""),
("""      this.addRenderableWidget(
         Button.builder(Component.translatable("voxlink.website"), button -> this.openWebsite()).bounds(centerX - 100, this.lyWebsite, 200, 20).build()
      );
      if (showDownload && this.lyDownloadRow != Integer.MIN_VALUE) {""",
"""      if (this.lyWebsite != Integer.MIN_VALUE) {
         this.addRenderableWidget(
            Button.builder(Component.translatable("voxlink.website"), button -> this.openWebsite()).bounds(centerX - 100, this.lyWebsite, 200, 20).build()
         );
      }
      if (showDownload && this.lyDownloadRow != Integer.MIN_VALUE) {"""),
]

TCS_PAIRS = [
("""   public void onClose() {""",
"""   public void removed() {
      // ESC/切屏撤销"再次点击确认删除"武装态：防止下次进屏按钮仍停留在确认文案
      this.deleteConfirmArmed = false;
      this.deleteConfirmArmTime = 0L;
   }

   public void onClose() {"""),
]

MRS_PAIRS = [
("""                  this.saveButton.active = true;
                  this.statusMessage = ChatFormatting.RED.toString() + finalMsg;""",
"""                  this.saveButton.active = true;
                  // 保存失败必须恢复字段可编辑，否则玩家被永久锁在只读态只能重开屏
                  this.nameField.setEditable(true);
                  this.passwordField.setEditable(true);
                  this.maxPlayersField.setEditable(true);
                  this.statusMessage = ChatFormatting.RED.toString() + finalMsg;"""),
]

CHANGES = {AJS: AJS_PAIRS, VLS: VLS_PAIRS, TCS: TCS_PAIRS, MRS: MRS_PAIRS}


def apply(path, pairs, report):
    if not os.path.isfile(path):
        report.append(("MISSING", path))
        return False
    with io.open(path, "r", encoding="utf-8", newline="") as f:
        text = f.read()
    changed = False
    ok = True
    for i, (old, new) in enumerate(pairs, 1):
        matched = False
        for eol in ("\n", "\r\n"):
            old_t = old.replace("\n", eol)
            new_t = new.replace("\n", eol)
            if new_t in text:
                matched = True  # 已应用
                break
            n = text.count(old_t)
            if n == 1:
                text = text.replace(old_t, new_t)
                changed = True
                matched = True
                break
        if not matched:
            report.append(("ANCHOR%d" % i, path))
            ok = False
    if ok and changed:
        with io.open(path, "w", encoding="utf-8", newline="") as f:
            f.write(text)
    return ok


def main():
    report = []
    done = fail = 0
    for loader in LOADERS:
        base = os.path.join(ROOT, loader)
        for ver in sorted(os.listdir(base)):
            tpl = os.path.join(base, ver)
            if not os.path.isdir(os.path.join(tpl, "src")):
                continue
            tag = "%s/%s" % (loader, ver)
            r = True
            for rel, pairs in CHANGES.items():
                if loader == "fabric" and ver == "26.2" and rel != MRS:
                    continue  # fabric/26.2 已手改（MRS 手改标记不同也允许重跑）
                r = apply(os.path.join(tpl, rel), pairs, report) and r
            print(("OK   " if r else "FAIL ") + tag)
            done, fail = (done + 1, fail) if r else (done, fail + 1)
    print("\n==== 完成 %d, 失败 %d ====" % (done, fail))
    for kind, path in report:
        print("  [%s] %s" % (kind, os.path.relpath(path, ROOT)))
    sys.exit(1 if fail else 0)


if __name__ == "__main__":
    main()
