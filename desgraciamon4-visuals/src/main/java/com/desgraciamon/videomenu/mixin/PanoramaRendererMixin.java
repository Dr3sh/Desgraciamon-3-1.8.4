package com.desgraciamon.videomenu.mixin;

import com.desgraciamon.videomenu.VideoPlayerManager;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.PanoramaRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PanoramaRenderer.class)
public class PanoramaRendererMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void renderCustomPanorama(GuiGraphics guiGraphics, int width, int height, float alpha, float delta, CallbackInfo ci) {
        VideoPlayerManager.INSTANCE.renderStaticFrame(guiGraphics, width, height);
        ci.cancel();
    }
}
