package dev.beamcraft.mixin;

import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Lets BeamNG's mouse drive Minecraft's inventory screens as if it were the real mouse. */
@Mixin(Mouse.class)
public interface MouseInvoker {
    @Invoker("onCursorPos")
    void beamcraft$onCursorPos(long window, double x, double y);

    @Invoker("onMouseButton")
    void beamcraft$onMouseButton(long window, int button, int action, int mods);

    @Invoker("onMouseScroll")
    void beamcraft$onMouseScroll(long window, double horizontal, double vertical);
}
