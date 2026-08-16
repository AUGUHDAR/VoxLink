package icu.wuhui.voxlink.mixin;

import com.mojang.brigadier.tree.CommandNode;
import java.util.function.Predicate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(CommandNode.class)
public interface CommandNodeAccessor<S> {
   @Accessor("requirement")
   @Mutable
   void voxlink$setRequirement(Predicate<S> var1);
}
