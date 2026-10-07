package dev.beamcraft.mixin;

import dev.beamcraft.Bridge;
import java.util.Locale;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.explosion.Explosion;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** TNT, creepers, end crystals...: tell BeamNG so the blast throws its cars around too. */
@Mixin(Explosion.class)
public abstract class ExplosionMixin {
    @Shadow @Final private World world;

    @Inject(method = "collectBlocksAndDamageEntities", at = @At("HEAD"))
    private void beamcraft$reportExplosion(CallbackInfo ci) {
        if (world.isClient() || world.getRegistryKey() != World.OVERWORLD) return;
        Explosion self = (Explosion) (Object) this;
        Vec3d p = self.getPosition();
        Bridge.send(String.format(Locale.ROOT, "boom %.3f %.3f %.3f %.2f", p.x, p.y, p.z, self.getPower()));
        Bridge.flush();
    }
}
