package dev.beamcraft.mixin;

import dev.beamcraft.BeamCraftClient;
import dev.beamcraft.CarColliders;
import dev.beamcraft.GeoColliders;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Box;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Steve collides with BeamNG itself: the map's real geometry (sampled by BeamNG) and its cars are
 * added to what his movement is checked against. His movement is worked out on the client.
 */
@Mixin(Entity.class)
public abstract class EntityMixin {
    @Inject(method = "findCollisionsForMovement", at = @At("RETURN"), cancellable = true)
    private static void beamcraft$collideWithBeamNG(Entity entity, World world, List<VoxelShape> regularCollisions,
                                                    Box movingBox, CallbackInfoReturnable<List<VoxelShape>> cir) {
        if (entity == null || !world.isClient() || entity != MinecraftClient.getInstance().player) return;
        if (!BeamCraftClient.beamngPhysics()) return;
        Box area = movingBox.expand(1.0);
        List<VoxelShape> result = new ArrayList<>(cir.getReturnValue());
        int before = result.size();
        GeoColliders.collect(area, result);
        for (VoxelShape s : CarColliders.shapes()) {
            if (s.getBoundingBox().intersects(area)) result.add(s);
        }
        if (result.size() != before) cir.setReturnValue(result);
    }
}
