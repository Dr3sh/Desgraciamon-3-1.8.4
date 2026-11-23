package com.desgraciamon.mirror.mixin;

import com.cobblemon.mod.common.client.render.pokemon.PokemonRenderer;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.desgraciamon.mirror.MirrorRules;
import net.minecraft.network.chat.MutableComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * En el Mundo Espejo, nunca muestra los nombres de los Pokémon como "???".
 * Muestra directamente su mote o el nombre oficial de su especie traducido,
 * independientemente del progreso en la Pokédex del jugador.
 */
@Mixin(value = PokemonRenderer.class, remap = false)
public abstract class PokemonRendererMixin {
    @Inject(method = "resolveBaseLabel", at = @At("HEAD"), cancellable = true)
    private void desgraciamon4mirror$forceMemoryName(PokemonEntity entity, CallbackInfoReturnable<MutableComponent> cir) {
        if (MirrorRules.isMirror(entity.level()) || entity.getTags().contains(MirrorRules.MEMORY_TAG)) {
            cir.setReturnValue(entity.getTitledName());
        }
    }
}

