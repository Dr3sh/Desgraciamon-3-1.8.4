package com.desgraciamon.vortex.mixin;

import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.desgraciamon.vortex.server.VortexManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Módulo 5.1: Secuestro del Pokémon (Backend).
 * Al detectarse la captura del vortex_entity, cancela el envío normal al Party/PC
 * para que quede exclusivamente en el mapa Limbo hasta que el jugador tome su decisión en la GUI.
 */
@Mixin(value = PlayerPartyStore.class, remap = false)
public class PlayerPartyStoreMixin {

    @Inject(method = "add", at = @At("HEAD"), cancellable = true)
    private void desgraciamon$interceptVortexAnomalyCapture(Pokemon pokemon, CallbackInfoReturnable<Boolean> cir) {
        if (pokemon != null && pokemon.getPersistentData().getBoolean(VortexManager.VORTEX_LIMBO_NBT)) {
            // Retornar true para que EmptyPokeBallEntity complete la animación de captura y dispare POKEMON_CAPTURED,
            // pero sin enviarlo al Party ni al PC todavía.
            cir.setReturnValue(true);
        }
    }
}

