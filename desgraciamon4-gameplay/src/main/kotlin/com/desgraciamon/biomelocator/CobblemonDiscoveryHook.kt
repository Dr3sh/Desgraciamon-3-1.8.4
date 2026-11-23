package com.desgraciamon.biomelocator

import com.cobblemon.mod.common.api.Priority
import com.cobblemon.mod.common.api.events.CobblemonEvents
import com.cobblemon.mod.common.api.events.pokemon.PokemonCapturedEvent
import com.cobblemon.mod.common.api.events.pokemon.evolution.EvolutionCompleteEvent
import kotlin.Unit

object CobblemonDiscoveryHook {
    fun register() {
        try {
            // 1. Hook de captura de Pokémon
            CobblemonEvents.POKEMON_CAPTURED.subscribe(Priority.NORMAL) { event: PokemonCapturedEvent ->
                val player = event.player
                val pokemon = event.pokemon
                if (player != null && pokemon != null) {
                    BiomeLocatorServer.registerPokemon(player, pokemon, "Captura")
                }
                Unit
            }

            // 2. Hook de evolución de Pokémon
            CobblemonEvents.EVOLUTION_COMPLETE.subscribe(Priority.NORMAL) { event: EvolutionCompleteEvent ->
                val pokemon = event.pokemon
                val player = pokemon?.getOwnerPlayer()
                if (player != null) {
                    BiomeLocatorServer.registerPokemon(player, pokemon, "Evolución")
                }
                Unit
            }
        } catch (t: Throwable) {
            System.err.println("[BiomeLocator] Warning: Error hooking Cobblemon events: ${t.message}")
        }
    }
}

