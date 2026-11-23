package com.desgraciamon.videomenu.mixin;

import com.desgraciamon.videomenu.VideoPlayerManager;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {

    protected TitleScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("HEAD"))
    private void onTitleScreenInit(CallbackInfo ci) {
        VideoPlayerManager.INSTANCE.prewarm();
    }

    @Inject(method = "renderPanorama", at = @At("HEAD"), cancellable = true)
    private void renderCustomVideoPanorama(GuiGraphics guiGraphics, float delta, CallbackInfo ci) {
        VideoPlayerManager.INSTANCE.renderTitleScreen(guiGraphics, this.width, this.height);
        ci.cancel();
    }
}
