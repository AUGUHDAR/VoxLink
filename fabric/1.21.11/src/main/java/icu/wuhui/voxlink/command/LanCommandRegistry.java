package icu.wuhui.voxlink.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.mixin.CommandNodeAccessor;
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

import java.util.function.Predicate;

//debounce LAN注册原生op/deop/ban/kick并放行房主
public final class LanCommandRegistry {
    private LanCommandRegistry() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        //LAN原生未注册, 调用register保留补全与选择器
        OpCommand.register(dispatcher);
        DeOpCommands.register(dispatcher);
        BanPlayerCommands.register(dispatcher);
        BanIpCommands.register(dispatcher);
        BanListCommands.register(dispatcher);
        PardonCommand.register(dispatcher);
        PardonIpCommand.register(dispatcher);
        WhitelistCommand.register(dispatcher);
        KickCommand.register(dispatcher);

        //放行LAN房主, 不依赖MC的OP权限检查
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

    //debounce requires改为: 原生检查 || LAN房主
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void modifyNativeRequires(CommandDispatcher<CommandSourceStack> dispatcher, String name) {
        try {
            CommandNode<CommandSourceStack> child = dispatcher.getRoot().getChild(name);
            if (child == null) {
                VoxLinkMod.LOGGER.warn("[LanCmd] 原生命令 {} 未找到 跳过修改", name);
                return;
            }
            Predicate<CommandSourceStack> original = child.getRequirement();
            CommandNodeAccessor accessor = (CommandNodeAccessor) (Object) child;
            accessor.voxlink$setRequirement((Predicate) (src -> {
                CommandSourceStack s = (CommandSourceStack) src;
                if (original != null && original.test(s)) return true;
                return isLanHost(s);
            }));
            VoxLinkMod.LOGGER.info("[LanCmd] 原生命令 {} requires 已放行LAN房主", name);
        } catch (Exception e) {
            VoxLinkMod.LOGGER.warn("[LanCmd] 修改原生命令 {} requires失败: {}", name, e.getMessage());
        }
    }

    //debounce 判断是否LAN房主
    private static boolean isLanHost(CommandSourceStack src) {
        MinecraftServer server = src.getServer();
        if (server == null) return false;
        if (!(server instanceof net.minecraft.client.server.IntegratedServer)) return false;
        var hostProfile = server.getSingleplayerProfile();
        if (hostProfile == null) return false;
        return src.getTextName().equals(hostProfile.name());
    }
}
