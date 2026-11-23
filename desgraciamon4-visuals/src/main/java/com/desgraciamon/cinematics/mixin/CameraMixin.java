package com.desgraciamon.cinematics.mixin;

import com.desgraciamon.cinematics.client.CinematicClientEngine;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class CameraMixin {

    @Shadow
    @Final
    private Quaternionf rotation;

    @Shadow
    private boolean detached;

    @Shadow
    protected abstract void setPosition(double x, double y, double z);

    @Shadow
    protected abstract void setRotation(float yRot, float xRot);

    @Inject(method = "setup", at = @At("TAIL"))
    private void desgraciamon$overrideCinematicCamera(
            BlockGetter level,
            Entity entity,
            boolean detached,
            boolean mirrored,
            float partialTick,
            CallbackInfo ci
    ) {
        if (CinematicClientEngine.INSTANCE.isCinematicActive()) {
            this.detached = true;
            CinematicClientEngine.INSTANCE.updateCamera(partialTick);
            Vec3 pos = CinematicClientEngine.INSTANCE.getCameraPos();
            float yaw = CinematicClientEngine.INSTANCE.getCameraYaw();
            float pitch = CinematicClientEngine.INSTANCE.getCameraPitch();
            float roll = CinematicClientEngine.INSTANCE.getCameraRoll();

            this.setPosition(pos.x, pos.y, pos.z);
            this.setRotation(yaw, pitch);
            if (Math.abs(roll) > 0.001f) {
                this.rotation.rotateZ((float) Math.toRadians(roll));
            }
        }
    }
}
