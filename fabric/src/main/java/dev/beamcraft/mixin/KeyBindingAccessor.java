package dev.beamcraft.mixin;

import net.minecraft.client.option.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(KeyBinding.class)
public interface KeyBindingAccessor {
    @Accessor("timesPressed")
    int beamcraft$getTimesPressed();

    @Accessor("timesPressed")
    void beamcraft$setTimesPressed(int value);
}
