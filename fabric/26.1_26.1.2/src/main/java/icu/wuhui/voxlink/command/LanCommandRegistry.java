package icu.wuhui.voxlink.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.mixin.CommandNodeAccessor;
import java.util.function.Predicate;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.commands.BanIpCommands;
import net.minecraft.server.commands.BanListCommands;
import net.minecraft.server.commands.BanPlayerCommands;
import net.minecraft.server.commands.DeOpCommands;
import net.minecraft.server.commands.KickCommand;
import net.minecraft.server.commands.OpCommand;
import net.minecraft.server.commands.PardonCommand;
import net.minecraft.server.commands.PardonIpCommand;
import net.minecraft.server.commands.WhitelistCommand;

public final class LanCommandRegistry {
   private LanCommandRegistry() {
   }

   public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
      OpCommand.register(dispatcher);
      DeOpCommands.register(dispatcher);
      BanPlayerCommands.register(dispatcher);
      BanIpCommands.register(dispatcher);
      BanListCommands.register(dispatcher);
      PardonCommand.register(dispatcher);
      PardonIpCommand.register(dispatcher);
      WhitelistCommand.register(dispatcher);
      KickCommand.register(dispatcher);
      modifyNativeRequires(dispatcher, "op");
      modifyNativeRequires(dispatcher, "deop");
      modifyNativeRequires(dispatcher, "ban");
      modifyNativeRequires(dispatcher, "ban-ip");
      modifyNativeRequires(dispatcher, "banlist");
      modifyNativeRequires(dispatcher, "pardon");
      modifyNativeRequires(dispatcher, "pardon-ip");
      modifyNativeRequires(dispatcher, "whitelist");
      modifyNativeRequires(dispatcher, "kick");
   }

   private static void modifyNativeRequires(CommandDispatcher<CommandSourceStack> dispatcher, String name) {
      try {
         CommandNode<CommandSourceStack> child = dispatcher.getRoot().getChild(name);
         if (child == null) {
            VoxLinkMod.LOGGER.warn("[LanCmd] Native command {} not found, skip modification", name);
            return;
         }

         Predicate<CommandSourceStack> original = child.getRequirement();
         setRequirementBypass(child, original, name);
      } catch (Exception e) {
         VoxLinkMod.LOGGER.warn("[LanCmd] Failed to modify native command {} requires: {}", name, e.getMessage());
      }
   }

   /**
    * 写入放宽后的 requirement。首选 mixin accessor（Fabric 下已应用零成本）；
    * 原生 NeoForge 上 brigadier 位于 MC-BOOTSTRAP 层、未被本模组 mixin 覆盖，
    * accessor 强转必败（线上实证：28 个会话 LAN 权限 bypass 全部失效），
    * 此时退回反射——brigadier 是无混淆库且 jar 为自动模块（全开放），反射跨加载器可用。
    */
   @SuppressWarnings({"unchecked", "rawtypes"})
   private static void setRequirementBypass(CommandNode<CommandSourceStack> node, Predicate<CommandSourceStack> original, String name) {
      Predicate bypass = src -> original != null && original.test((CommandSourceStack)src) ? true : isLanHost((CommandSourceStack)src);
      try {
         CommandNodeAccessor accessor = (CommandNodeAccessor)node;
         accessor.voxlink$setRequirement(bypass);
         VoxLinkMod.LOGGER.info("[LanCmd] Native command {} requires bypassed for LAN host", name);
      } catch (ClassCastException mixinNotApplied) {
         try {
            java.lang.reflect.Field requirementField = CommandNode.class.getDeclaredField("requirement");
            requirementField.setAccessible(true);
            requirementField.set(node, bypass);
            VoxLinkMod.LOGGER.info("[LanCmd] Native command {} requires bypassed for LAN host (reflection)", name);
         } catch (Exception e) {
            VoxLinkMod.LOGGER.warn("[LanCmd] Failed to modify native command {} requires: {}", name, e.getMessage());
         }
      }
   }

   /**
    * LAN 房主判定（安全修复，P0）：
    * 旧实现比较 {@code src.getTextName() == 本地用户名}，离线模式下同名攻击者可直接
    * 获得 op/ban/kick 等特权；离线 UUID 按名字派生，因此单纯改比 UUID 也不够。
    * 现改为：集成服务器 + singleplayerProfile 存在 + 命令来源玩家的 profile UUID
    * 属于 {@link LanHostRegistry} 的"启动快照"。
    * 时序保证：Open to LAN 时房主必然先于任何远程玩家在场（快照捕获于服务器启动后、
    * 远程玩家加入前），后来者无论同名还是同离线 UUID 都不在快照中。
    */
   private static boolean isLanHost(CommandSourceStack src) {
      MinecraftServer server = src.getServer();
      if (!(server instanceof IntegratedServer)) {
         return false;
      } else if (server.getSingleplayerProfile() == null) {
         return false;
      } else {
         // 仅当命令来源是本服在线玩家时才取其 profile UUID；控制台/Rcon 等非玩家来源一律不放行
         if (src.getEntity() instanceof ServerPlayer sender) {
            return LanHostRegistry.isBootstrapProfile(server, sender.getUUID());
         }

         return false;
      }
   }
}
