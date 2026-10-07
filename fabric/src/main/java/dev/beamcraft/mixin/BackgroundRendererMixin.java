package dev.beamcraft.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.beamcraft.BeamCraftClient;
import net.minecraft.client.render.BackgroundRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The world is cleared to the fog colour each frame; for BeamNG's overlay it must be see-through. */
@Mixin(BackgroundRenderer.class)
public abstract class BackgroundRendererMixin {
    @Inject(method = "render", at = @At("TAIL"))
    private static void beamcraft$transparentBackground(Camera camera, float tickDelta, ClientWorld world,
                                                        int viewDistance, float skyDarkness, CallbackInfo ci) {
        if (BeamCraftClient.hudOnly()) RenderSystem.clearColor(0f, 0f, 0f, 0f);
    }
}
