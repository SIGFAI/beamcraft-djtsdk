package dev.beamcraft.mixin;

import dev.beamcraft.BeamCraftClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.InGameOverlayRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Minecraft's blue underwater tint would cover BeamNG's whole view through the overlay. */
@Mixin(InGameOverlayRenderer.class)
public abstract class InGameOverlayRendererMixin {
    @Inject(method = "renderUnderwaterOverlay", at = @At("HEAD"), cancellable = true)
    private static void beamcraft$noUnderwaterTint(MinecraftClient client, MatrixStack matrices, CallbackInfo ci) {
        if (BeamCraftClient.hudOnly()) ci.cancel();
    }
}
