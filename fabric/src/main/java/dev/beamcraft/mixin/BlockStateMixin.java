package dev.beamcraft.mixin;

import dev.beamcraft.BeamCraftClient;
import dev.beamcraft.GeoColliders;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.EntityShapeContext;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The invisible barrier "ground" stays in the world (so blocks can be placed on it and items land
 * on it), but Steve no longer bumps into it once BeamNG's real geometry is in use. Applies on both
 * client and integrated server, so the server agrees with where Steve walked.
 */
@Mixin(AbstractBlock.AbstractBlockState.class)
public abstract class BlockStateMixin {
    @Inject(method = "getCollisionShape(Lnet/minecraft/world/BlockView;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/ShapeContext;)Lnet/minecraft/util/shape/VoxelShape;",
            at = @At("HEAD"), cancellable = true)
    private void beamcraft$playerIgnoresBarriers(BlockView world, BlockPos pos, ShapeContext context,
                                                 CallbackInfoReturnable<VoxelShape> cir) {
        if (!GeoColliders.active() || !BeamCraftClient.beamngPhysics()) return;
        if (!((BlockState) (Object) this).isOf(Blocks.BARRIER)) return;
        if (context instanceof EntityShapeContext esc && esc.getEntity() instanceof PlayerEntity) {
            cir.setReturnValue(VoxelShapes.empty());
        }
    }
}
