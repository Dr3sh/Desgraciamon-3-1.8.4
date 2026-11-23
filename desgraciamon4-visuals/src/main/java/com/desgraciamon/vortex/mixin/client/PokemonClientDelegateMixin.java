package com.desgraciamon.vortex.mixin.client;

import com.cobblemon.mod.common.client.entity.PokemonClientDelegate;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.desgraciamon.vortex.server.VortexManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Módulo 4.3: Supresión de Audio Shiny.
 * Cancela el efecto de sonido y anillo shiny de Cobblemon si la entidad tiene el tag `vortex_entity`.
 */
@Mixin(value = PokemonClientDelegate.class, remap = false)
public abstract class PokemonClientDelegateMixin {

    @Shadow
    public abstract PokemonEntity getCurrentEntity();

    @Inject(method = "playWildShinySounds", at = @At("HEAD"), cancellable = true)
    private void desgraciamon$suppressVortexShinySound(CallbackInfo ci) {
        PokemonEntity entity = this.getCurrentEntity();
        if (entity != null && (entity.getTags().contains(VortexManager.VORTEX_TAG)
                || entity.getPokemon().getPersistentData().getBoolean(VortexManager.VORTEX_TAG))) {
            ci.cancel();
        }
    }
}

