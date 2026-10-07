package dev.beamcraft.mixin;

import dev.beamcraft.BeamCraftClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
    /** Swinging at a BeamNG car damages the car instead of mining the block behind it. */
    @Inject(method = "doAttack", at = @At("HEAD"), cancellable = true)
    private void beamcraft$attackCar(CallbackInfoReturnable<Boolean> cir) {
        if (BeamCraftClient.attackCar((MinecraftClient) (Object) this)) cir.setReturnValue(true);
    }

    @Inject(method = "handleBlockBreaking", at = @At("HEAD"), cancellable = true)
    private void beamcraft$noMiningThroughCars(boolean breaking, CallbackInfo ci) {
        if (BeamCraftClient.carTargeted()) {
            MinecraftClient self = (MinecraftClient) (Object) this;
            if (self.interactionManager != null) self.interactionManager.cancelBlockBreaking();
            ci.cancel();
        }
    }

    @Inject(method = "doItemUse", at = @At("HEAD"), cancellable = true)
    private void beamcraft$noPlacingThroughCars(CallbackInfo ci) {
        // never faster than vanilla's right-click repeat (once every 4 ticks)
        if (BeamCraftClient.carTargeted() || !BeamCraftClient.allowItemUse()) ci.cancel();
    }

    /** Holding attack mines blocks only while the mouse is grabbed; BeamNG owns the mouse instead. */
    @Redirect(method = "handleInputEvents", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Mouse;isCursorLocked()Z"))
    private boolean beamcraft$cursorLocked(Mouse mouse) {
        return mouse.isCursorLocked() || BeamCraftClient.isDriving();
    }
}
