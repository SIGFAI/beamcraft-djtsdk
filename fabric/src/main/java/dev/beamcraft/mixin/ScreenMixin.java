package dev.beamcraft.mixin;

import dev.beamcraft.BeamCraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Menus darken and blur the whole screen behind them. Over BeamNG that turns every inventory frame
 * into a huge full-screen image, so skip it: only the inventory panel itself is drawn and sent.
 */
@Mixin(Screen.class)
public abstract class ScreenMixin {
    @Inject(method = "renderBackground", at = @At("HEAD"), cancellable = true)
    private void beamcraft$noMenuBackground(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (BeamCraftClient.hudOnly()) ci.cancel();
    }
}
