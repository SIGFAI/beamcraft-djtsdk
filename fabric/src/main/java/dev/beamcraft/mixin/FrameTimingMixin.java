package dev.beamcraft.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.beamcraft.FrameTiming;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Measures where Minecraft's frame time goes (for the overlay log line). */
@Mixin(RenderSystem.class)
public abstract class FrameTimingMixin {
    @Inject(method = "flipFrame", at = @At("HEAD"), remap = false)
    private static void beamcraft$flipStart(long window, CallbackInfo ci) {
        FrameTiming.begin(FrameTiming.FLIP);
    }

    @Inject(method = "flipFrame", at = @At("TAIL"), remap = false)
    private static void beamcraft$flipEnd(long window, CallbackInfo ci) {
        FrameTiming.end(FrameTiming.FLIP);
    }

    @Inject(method = "limitDisplayFPS", at = @At("HEAD"), remap = false)
    private static void beamcraft$limitStart(int fps, CallbackInfo ci) {
        FrameTiming.begin(FrameTiming.LIMIT);
    }

    @Inject(method = "limitDisplayFPS", at = @At("TAIL"), remap = false)
    private static void beamcraft$limitEnd(int fps, CallbackInfo ci) {
        FrameTiming.end(FrameTiming.LIMIT);
    }
}
