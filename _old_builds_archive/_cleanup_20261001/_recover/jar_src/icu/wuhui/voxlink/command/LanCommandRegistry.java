package icu.wuhui.voxlink.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.mixin.CommandNodeAccessor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
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
import net.minecraft.server.level.ServerPlayer;

public final class LanCommandRegistry {
   private static final String[] NATIVE_COMMANDS = new String[]{"op", "deop", "ban", "ban-ip", "banlist", "pardon", "pardon-ip", "whitelist", "kick"};
   private static final boolean ACCESSOR_PATH_USABLE = isFabricLoaderPresent();

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
      List<String> failures = new ArrayList<>();

      for (String name : NATIVE_COMMANDS) {
         modifyNativeRequires(dispatcher, name, failures);
      }

      if (!failures.isEmpty()) {
         VoxLinkMod.LOGGER
            .warn(
               "[LanCmd] Failed to modify native command requires, {}/{} failed: {}",
               new Object[]{failures.size(), NATIVE_COMMANDS.length, String.join("; ", failures)}
            );
      }
   }

   private static void modifyNativeRequires(CommandDispatcher<CommandSourceStack> dispatcher, String name, List<String> failures) {
      try {
         CommandNode<CommandSourceStack> child = dispatcher.getRoot().getChild(name);
         if (child == null) {
            failures.add(name + ": native command not found");
            return;
         }

         Predicate<CommandSourceStack> original = child.getRequirement();
         setRequirementBypass(child, original, name, failures);
      } catch (Exception var5) {
         failures.add(name + ": " + var5);
      }
   }

   private static void setRequirementBypass(CommandNode<CommandSourceStack> node, Predicate<CommandSourceStack> original, String name, List<String> failures) {
      Predicate bypass = src -> original != null && original.test((CommandSourceStack)src) ? true : isLanHost((CommandSourceStack)src);
      if (ACCESSOR_PATH_USABLE) {
         try {
            CommandNodeAccessor accessor = (CommandNodeAccessor)node;
            accessor.voxlink$setRequirement(bypass);
            VoxLinkMod.LOGGER.info("[LanCmd] Native command {} requires bypassed for LAN host", name);
            return;
         } catch (ClassCastException var7) {
         }
      }

      try {
         Field requirementField = CommandNode.class.getDeclaredField("requirement");
         requirementField.setAccessible(true);
         requirementField.set(node, bypass);
         VoxLinkMod.LOGGER.info("[LanCmd] Native command {} requires bypassed for LAN host (reflection)", name);
      } catch (Exception var6) {
         failures.add(name + ": " + var6);
      }
   }

   private static boolean isFabricLoaderPresent() {
      try {
         Class.forName("net.fabricmc.loader.api.FabricLoader", false, LanCommandRegistry.class.getClassLoader());
         return true;
      } catch (ClassNotFoundException var1) {
         return false;
      }
   }

   private static boolean isLanHost(CommandSourceStack src) {
      MinecraftServer server = src.getServer();
      if (!(server instanceof IntegratedServer)) {
         return false;
      } else if (server.getSingleplayerProfile() == null) {
         return false;
      } else {
         return src.getEntity() instanceof ServerPlayer sender ? LanHostRegistry.isBootstrapProfile(server, sender.getUUID()) : false;
      }
   }
}
