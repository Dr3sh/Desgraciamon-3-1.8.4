package com.desgraciamon.vortex.mixin.client;

import com.desgraciamon.vortex.client.VortexShaderController;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Inject(method = "render", at = @At("HEAD"))
    private void desgraciamon$updateVortexShaderUniforms(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        VortexShaderController.INSTANCE.onPreRenderFrame((GameRenderer) (Object) this, deltaTracker.getGameTimeDeltaPartialTick(true));
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void desgraciamon$renderTopLevelScreenEffects(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        com.desgraciamon.screeneffects.ScreenEffectsClientEngine.INSTANCE.renderTopLevel(deltaTracker.getGameTimeDeltaPartialTick(true));
    }

    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void desgraciamon$applyDynamicFov(net.minecraft.client.Camera camera, float partialTick, boolean useFovSetting, CallbackInfoReturnable<Double> cir) {
        float fovMult = VortexShaderController.INSTANCE.getFovMultiplier(partialTick);
        if (Math.abs(fovMult - 1.0f) > 0.0005f) {
            cir.setReturnValue(cir.getReturnValue() * (double) fovMult);
        }
    }
}

