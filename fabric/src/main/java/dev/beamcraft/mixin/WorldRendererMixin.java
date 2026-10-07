package dev.beamcraft.mixin;

import dev.beamcraft.BeamCraftClient;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While BeamNG draws the world, Minecraft draws only what BeamNG can't: Steve (third person),
 * the hand and held item, dropped items and particles. Sky, terrain blocks, clouds, weather and
 * the block outline are skipped so the frame stays transparent everywhere else.
 */
@Mixin(WorldRenderer.class)
public abstract class WorldRendererMixin {
    @Inject(method = "renderSky", at = @At("HEAD"), cancellable = true)
    private void beamcraft$noSky(Matrix4f matrix4f, Matrix4f projectionMatrix, float tickDelta, Camera camera,
                                 boolean thickFog, Runnable fogCallback, CallbackInfo ci) {
        if (BeamCraftClient.hudOnly()) ci.cancel();
    }

    @Inject(method = "renderLayer", at = @At("HEAD"), cancellable = true)
    private void beamcraft$noTerrain(RenderLayer renderLayer, double x, double y, double z, Matrix4f matrix4f,
                                     Matrix4f positionMatrix, CallbackInfo ci) {
        if (BeamCraftClient.hudOnly()) ci.cancel();
    }

    @Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true)
    private void beamcraft$noClouds(MatrixStack matrices, Matrix4f matrix4f, Matrix4f matrix4f2, float tickDelta,
                                    double cameraX, double cameraY, double cameraZ, CallbackInfo ci) {
        if (BeamCraftClient.hudOnly()) ci.cancel();
    }

    @Inject(method = "renderWeather", at = @At("HEAD"), cancellable = true)
    private void beamcraft$noWeather(LightmapTextureManager manager, float tickDelta, double cameraX, double cameraY,
                                     double cameraZ, CallbackInfo ci) {
        if (BeamCraftClient.hudOnly()) ci.cancel();
    }

    @Inject(method = "renderWorldBorder", at = @At("HEAD"), cancellable = true)
    private void beamcraft$noBorder(Camera camera, CallbackInfo ci) {
        if (BeamCraftClient.hudOnly()) ci.cancel();
    }

    @Inject(method = "drawBlockOutline", at = @At("HEAD"), cancellable = true)
    private void beamcraft$noBlockOutline(MatrixStack matrices, VertexConsumer vertexConsumer, Entity entity,
                                          double cameraX, double cameraY, double cameraZ, BlockPos pos,
                                          BlockState state, CallbackInfo ci) {
        if (BeamCraftClient.hudOnly()) ci.cancel();
    }
}
