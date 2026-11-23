package com.desgraciamon.vortex.mixin.client;

import com.desgraciamon.vortex.client.VortexShaderController;
import com.desgraciamon.vortex.client.VortexSkyRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class LevelRendererMixin {

    @Inject(method = "renderSky", at = @At("TAIL"))
    private void desgraciamon$renderDimensionalVortexSkybox(
            Matrix4f frustumMatrix,
            Matrix4f projectionMatrix,
            float partialTick,
            Camera camera,
            boolean isFoggy,
            Runnable skyFogSetup,
            CallbackInfo ci
    ) {
        if (!isFoggy) {
            VortexSkyRenderer.INSTANCE.renderVortexSkybox(frustumMatrix, partialTick);
        }
    }

    /**
     * Oculta las nubes cuadradas de Minecraft Vanilla mientras el Vórtice Dimensional está activo
     * para que solo se vean las nubes volumétricas del shader del vórtice.
     */
    @Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true)
    private void desgraciamon$hideVanillaCloudsDuringVortex(
            PoseStack poseStack,
            Matrix4f frustumMatrix,
            Matrix4f projectionMatrix,
            float partialTick,
            double camX,
            double camY,
            double camZ,
            CallbackInfo ci
    ) {
        if (VortexShaderController.INSTANCE.isEventActiveClient()
                || VortexShaderController.INSTANCE.getSmoothIntensity(partialTick) > 0.1f) {
            ci.cancel();
        }
    }
}
