package dev.beamcraft.mixin;

import dev.beamcraft.BlockSync;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(World.class)
public abstract class WorldMixin {
    @Inject(method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;II)Z", at = @At("RETURN"))
    private void beamcraft$onBlockChanged(BlockPos pos, BlockState state, int flags, int maxUpdateDepth,
                                          CallbackInfoReturnable<Boolean> cir) {
        World self = (World) (Object) this;
        if (cir.getReturnValueZ() && !self.isClient() && self.getRegistryKey() == World.OVERWORLD) {
            BlockSync.onBlockChanged(self, pos, state);
        }
    }
}
