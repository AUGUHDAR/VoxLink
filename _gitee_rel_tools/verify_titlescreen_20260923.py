# -*- coding: utf-8 -*-
"""验证 TitleScreenMixin 金标准 = 26.2簇 + 我的改动块（决定整拷策略）"""
import hashlib

GOLD = r"d:\桌面\voxlink\fabric\26.3\src\main\java\icu\wuhui\voxlink\mixin\TitleScreenMixin.java"
C262 = r"d:\桌面\voxlink\fabric\26.2\src\main\java\icu\wuhui\voxlink\mixin\TitleScreenMixin.java"

gold = open(GOLD, "rb").read().decode("utf-8").replace("\r\n", "\n")
c262 = open(C262, "rb").read().decode("utf-8").replace("\r\n", "\n")

# 我的改动块（金标准里的 poll 块）
poll_block = """      icu.wuhui.voxlink.network.TicketClient.pollOnceIfNeeded(() -> {
         Minecraft mc = Minecraft.getInstance();
         mc.execute(() -> {
            if (icu.wuhui.voxlink.network.TicketClient.hasUnread()
               && mc.gui.screen() instanceof TitleScreen) {
               mc.gui.setScreen(mc.gui.screen());
            }
         });
      });
"""
# 弹窗块
popup_block = """
      // 左上角工单未读弹窗：点击进我的工单
      if (icu.wuhui.voxlink.network.TicketClient.hasUnread()) {
         this.addRenderableWidget(
            Button.builder(Component.translatable("voxlink.ticket.notify"), button ->
                  Minecraft.getInstance().setScreenAndShow(new icu.wuhui.voxlink.ui.TicketListScreen(this)))
               .bounds(8, 8, 100, 18)
               .build()
         );
      }
"""

stripped = gold.replace(poll_block, "").replace(popup_block, "")
print("gold - myblocks == 26.2:", hashlib.md5(stripped.encode()).hexdigest() == hashlib.md5(c262.encode()).hexdigest())
print("gold md5:", hashlib.md5(gold.encode()).hexdigest()[:10])
print("262  md5:", hashlib.md5(c262.encode()).hexdigest()[:10])
print("stripped md5:", hashlib.md5(stripped.encode()).hexdigest()[:10])
if stripped != c262:
    import difflib
    for line in difflib.unified_diff(c262.splitlines(), stripped.splitlines(), lineterm="", n=1):
        print(line)
