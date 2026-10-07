package dev.beamcraft.mixin;

import dev.beamcraft.BeamCraftClient;
import net.minecraft.block.enums.CameraSubmersionType;
import net.minecraft.client.render.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** BeamNG draws its own underwater look; Minecraft's underwater fog would tint the hand and Steve. */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Inject(method = "getSubmersionType", at = @At("HEAD"), cancellable = true)
    private void beamcraft$neverSubmerged(CallbackInfoReturnable<CameraSubmersionType> cir) {
        if (BeamCraftClient.hudOnly()) cir.setReturnValue(CameraSubmersionType.NONE);
    }
}
