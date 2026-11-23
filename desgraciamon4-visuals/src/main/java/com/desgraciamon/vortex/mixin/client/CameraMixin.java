package com.desgraciamon.vortex.mixin.client;

import com.desgraciamon.vortex.client.VortexShaderController;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class CameraMixin {

    @Shadow
    private float xRot;

    @Shadow
    private float yRot;

    @Shadow
    protected abstract void setRotation(float yRot, float xRot);

    @Inject(method = "setup", at = @At("TAIL"))
    private void desgraciamon$applyCameraShake(BlockGetter level, Entity entity, boolean detached, boolean mirrored, float partialTick, CallbackInfo ci) {
        float shake = VortexShaderController.INSTANCE.getCurrentCameraShakeAmount(partialTick);
        if (shake > 0.0001f) {
            float time = VortexShaderController.INSTANCE.getSmoothTimeSeconds(partialTick);
            // Vibración armónica desfasada suave pero perceptible
            float pitchOffset = (float) (Math.sin(time * 26.0) * 0.70 + Math.sin(time * 41.0) * 0.30) * shake;
            float yawOffset   = (float) (Math.cos(time * 23.0) * 0.65 + Math.sin(time * 37.0) * 0.35) * shake;
            this.setRotation(this.yRot + yawOffset, this.xRot + pitchOffset);
        }
    }
}

