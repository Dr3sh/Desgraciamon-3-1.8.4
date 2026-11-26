package com.desgraciamon.videomenu.mixin;

import com.desgraciamon.videomenu.VideoPlayerManager;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.LoadingOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LoadingOverlay.class)
public class LoadingOverlayMixin {

    @Inject(method = "render", at = @At("HEAD"))
    private void onLoadingOverlayRender(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        VideoPlayerManager.INSTANCE.prewarmStaticOnly();
    }
}
