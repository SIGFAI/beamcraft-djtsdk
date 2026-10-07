package dev.beamcraft.mixin;

import dev.beamcraft.BeamCraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InGameHud.class)
public abstract class InGameHudMixin {
    /** Full-screen effects (vignette, pumpkin, portal, powder snow) would cover BeamNG's whole view. */
    @Inject(method = "renderVignetteOverlay", at = @At("HEAD"), cancellable = true)
    private void beamcraft$noVignette(DrawContext context, Entity entity, CallbackInfo ci) {
        if (BeamCraftClient.hudOnly()) ci.cancel();
    }

    @Inject(method = "renderMiscOverlays", at = @At("HEAD"), cancellable = true)
    private void beamcraft$noOverlays(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        if (BeamCraftClient.hudOnly()) ci.cancel();
    }

    /** Red corners around the crosshair when a BeamNG car is within hitting range. */
    @Inject(method = "renderCrosshair", at = @At("TAIL"))
    private void beamcraft$carMarker(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        if (!BeamCraftClient.carTargeted()) return;
        int cx = context.getScaledWindowWidth() / 2, cy = context.getScaledWindowHeight() / 2;
        int r = 8, l = 3, color = 0xE0FF3020;
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sy = -1; sy <= 1; sy += 2) {
                int x = cx + sx * r, y = cy + sy * r;
                context.fill(Math.min(x, x - sx * l), y, Math.max(x, x - sx * l) + 1, y + 1, color);
                context.fill(x, Math.min(y, y - sy * l), x + 1, Math.max(y, y - sy * l) + 1, color);
            }
        }
    }
}
