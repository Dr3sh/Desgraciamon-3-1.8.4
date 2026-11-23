package com.desgraciamon.videomenu.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.CubeMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CubeMap.class)
public class CubeMapMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void cancelVanillaCubeMap(Minecraft mc, float pitch, float yaw, float alpha, CallbackInfo ci) {
        ci.cancel();
    }
}
