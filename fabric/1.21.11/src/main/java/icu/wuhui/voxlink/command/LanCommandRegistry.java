package icu.wuhui.voxlink.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.mixin.CommandNodeAccessor;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
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
         CommandNodeAccessor accessor = (CommandNodeAccessor)child;
         accessor.voxlink$setRequirement(src -> {
            CommandSourceStack s = (CommandSourceStack)src;
            return original != null && original.test(s) ? true : isLanHost(s);
         });
         VoxLinkMod.LOGGER.info("[LanCmd] Native command {} requires bypassed for LAN host", name);
      } catch (Exception e) {
         VoxLinkMod.LOGGER.warn("[LanCmd] Failed to modify native command {} requires: {}", name, e.getMessage());
      }
   }

   private static boolean isLanHost(CommandSourceStack src) {
      MinecraftServer server = src.getServer();
      if (server == null) {
         return false;
      } else if (!(server instanceof IntegratedServer)) {
         return false;
      } else {
         return server.getSingleplayerProfile() == null ? false : src.getTextName().equals(Minecraft.getInstance().getUser().getName());
      }
   }
}
