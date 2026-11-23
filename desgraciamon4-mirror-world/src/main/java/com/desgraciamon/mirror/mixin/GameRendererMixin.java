package com.desgraciamon.mirror.mixin;

import com.desgraciamon.mirror.MirrorRules;
import com.desgraciamon.mirror.MirrorWorldClient;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Inject(method = "render", at = @At("TAIL"))
    private void desgraciamon$renderTopLevelMirrorOverlay(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        MirrorWorldClient.Companion.renderTopLevelTransition();
    }

    @Inject(method = "togglePostEffect", at = @At("HEAD"), cancellable = true)
    private void desgraciamon$blockToggleInMirror(CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && (MirrorRules.isMirror(mc.level) || MirrorRules.isRecuerdos(mc.level))) {
            ci.cancel();
        }
    }
}

