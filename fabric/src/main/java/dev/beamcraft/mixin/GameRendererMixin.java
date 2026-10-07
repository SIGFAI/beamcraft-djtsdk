package dev.beamcraft.mixin;

import dev.beamcraft.BeamCraftClient;
import dev.beamcraft.HudStream;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void beamcraft$renderStart(RenderTickCounter tickCounter, boolean tick, CallbackInfo ci) {
        dev.beamcraft.FrameTiming.begin(dev.beamcraft.FrameTiming.GAME);
    }

    /** Each finished frame (hand, Steve, HUD on a transparent background) goes to BeamNG's overlay. */
    @Inject(method = "render", at = @At("TAIL"))
    private void beamcraft$captureHud(RenderTickCounter tickCounter, boolean tick, CallbackInfo ci) {
        dev.beamcraft.FrameTiming.end(dev.beamcraft.FrameTiming.GAME);
        if (BeamCraftClient.streamingHud()) HudStream.capture(MinecraftClient.getInstance());
    }
}
