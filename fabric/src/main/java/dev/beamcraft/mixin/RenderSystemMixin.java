package dev.beamcraft.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.beamcraft.BeamCraftClient;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * While BeamNG shows Minecraft's picture, nobody looks at Minecraft's own window, and presenting
 * to a background window gets throttled hard by the driver (~15 fps here). Skip presenting and
 * just flush the GPU work; the overlay reads Minecraft's framebuffer directly.
 */
@Mixin(RenderSystem.class)
public abstract class RenderSystemMixin {
    @Redirect(method = "flipFrame", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwSwapBuffers(J)V"), remap = false)
    private static void beamcraft$skipPresent(long window) {
        if (BeamCraftClient.hudOnly()) GL11.glFlush();
        else GLFW.glfwSwapBuffers(window);
    }
}
