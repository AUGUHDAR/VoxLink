package icu.wuhui.voxlink.mixin;

import com.mojang.brigadier.tree.CommandNode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.function.Predicate;

//debounce 暴露Brigadier CommandNode.requirement写接口 让房主放行原生命令
@Mixin(CommandNode.class)
public interface CommandNodeAccessor<S> {
    @Accessor("requirement")
    @Mutable
    void voxlink$setRequirement(Predicate<S> predicate);
}
