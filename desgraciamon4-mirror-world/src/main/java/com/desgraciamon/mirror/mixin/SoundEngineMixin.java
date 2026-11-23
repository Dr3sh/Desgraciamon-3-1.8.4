package com.desgraciamon.mirror.mixin;

import com.desgraciamon.mirror.MirrorRules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Silencia los efectos de sonido estridentes de Pokémon Shiny dentro del Mundo Espejo.
 */
@Mixin(SoundEngine.class)
public abstract class SoundEngineMixin {
    @Inject(method = "play", at = @At("HEAD"), cancellable = true)
    private void desgraciamon4mirror$muteShinyInMirror(SoundInstance soundInstance, CallbackInfo ci) {
        if (soundInstance == null) return;
        Minecraft client = Minecraft.getInstance();
        if (client.level != null && MirrorRules.isMirror(client.level)) {
            String path = soundInstance.getLocation().getPath();
            if (path != null && path.contains("shiny")) {
                ci.cancel();
            }
        }
    }
}
