package dev.beamcraft.mixin;

import net.minecraft.client.Keyboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Lets BeamNG's typing reach Minecraft's text boxes as real keyboard input. */
@Mixin(Keyboard.class)
public interface KeyboardInvoker {
    @Invoker("onChar")
    void beamcraft$onChar(long window, int codePoint, int modifiers);
}
