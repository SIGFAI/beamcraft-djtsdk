package dev.beamcraft.mixin;

import dev.beamcraft.TerrainMirror;
import net.minecraft.fluid.FlowableFluid;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Water copied from BeamNG's lakes and sea must stay exactly where BeamNG has water: it never
 * flows or spreads. (Water the player pours from a bucket behaves normally.)
 */
@Mixin(FlowableFluid.class)
public abstract class FlowableFluidMixin {
    @Inject(method = "onScheduledTick", at = @At("HEAD"), cancellable = true)
    private void beamcraft$mirroredWaterStaysPut(World world, BlockPos pos, FluidState state, CallbackInfo ci) {
        if (!world.isClient() && TerrainMirror.isMirroredWater(pos)) ci.cancel();
    }
}
