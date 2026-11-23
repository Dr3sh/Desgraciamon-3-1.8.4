package com.desgraciamon.showdownarena.mixin;

import com.desgraciamon.showdownarena.ArenaZoneClient;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class ArenaDarkSkyMixin {
    @Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true)
    private void showdown$hideCloudsInsideArena(
        PoseStack poseStack,
        Matrix4f frustumMatrix,
        Matrix4f projectionMatrix,
        float partialTick,
        double camX,
        double camY,
        double camZ,
        CallbackInfo ci
    ) {
        if (ArenaZoneClient.insideDarkRoomFactor > 0.45f) {
            ci.cancel();
        }
    }
}
