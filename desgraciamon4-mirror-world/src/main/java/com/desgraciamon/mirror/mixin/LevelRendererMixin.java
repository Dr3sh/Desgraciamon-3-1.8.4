package com.desgraciamon.mirror.mixin;

import com.desgraciamon.mirror.MirrorRules;
import com.desgraciamon.mirror.MirrorSkyRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class LevelRendererMixin {

    @Inject(method = "renderSky", at = @At("TAIL"))
    private void desgraciamon$renderMirrorWorldSkybox(
            Matrix4f frustumMatrix,
            Matrix4f projectionMatrix,
            float partialTick,
            Camera camera,
            boolean isFoggy,
            Runnable skyFogSetup,
            CallbackInfo ci
    ) {
        Minecraft client = Minecraft.getInstance();
        if (MirrorRules.isRecuerdos(client.level)) {
            MirrorSkyRenderer.INSTANCE.renderRecuerdosSkybox(frustumMatrix, projectionMatrix, partialTick);
        } else if (MirrorRules.isMirror(client.level)) {
            MirrorSkyRenderer.INSTANCE.renderMirrorSkybox(frustumMatrix, projectionMatrix, partialTick);
        }
    }

    /**
     * Oculta las nubes cúbicas por defecto de Minecraft en la Zona Espejo y en dimension_recuerdos
     * para que luzca el cielo volumétrico procedural.
     */
    @Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true)
    private void desgraciamon$hideVanillaCloudsInMirror(
            PoseStack poseStack,
            Matrix4f frustumMatrix,
            Matrix4f projectionMatrix,
            float partialTick,
            double camX,
            double camY,
            double camZ,
            CallbackInfo ci
    ) {
        Minecraft client = Minecraft.getInstance();
        if (MirrorRules.isMirror(client.level) || MirrorRules.isRecuerdos(client.level)) {
            ci.cancel();
        }
    }
}
