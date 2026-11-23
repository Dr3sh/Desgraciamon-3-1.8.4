package com.desgraciamon.vortex.mixin.client;

import com.desgraciamon.vortex.client.VortexShaderController;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FogRenderer.class)
public class FogRendererMixin {

    @Shadow
    private static float fogRed;

    @Shadow
    private static float fogGreen;

    @Shadow
    private static float fogBlue;

    @Inject(method = "setupColor", at = @At("TAIL"))
    private static void desgraciamon$tintHorizonFogBlue(
            Camera camera,
            float partialTicks,
            ClientLevel level,
            int renderDistanceChunks,
            float bossColorModifier,
            CallbackInfo ci
    ) {
        float intensity = VortexShaderController.INSTANCE.getSmoothIntensity(partialTicks);
        if (intensity > 0.002f) {
            String effectType = VortexShaderController.INSTANCE.getCurrentEffectType().toLowerCase();
            float targetR, targetG, targetB;
            if ("vortex".equals(effectType)) {
                targetR = 0.09f;
                targetG = 0.015f;
                targetB = 0.16f;
            } else { // "startrails" / "goosebumps"
                targetR = 0.028f;
                targetG = 0.065f;
                targetB = 0.160f;
            }
            fogRed = fogRed + (targetR - fogRed) * intensity;
            fogGreen = fogGreen + (targetG - fogGreen) * intensity;
            fogBlue = fogBlue + (targetB - fogBlue) * intensity;
            RenderSystem.clearColor(fogRed, fogGreen, fogBlue, 0.0f);
        }
    }
}

