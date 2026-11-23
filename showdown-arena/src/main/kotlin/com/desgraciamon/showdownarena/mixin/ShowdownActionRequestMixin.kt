package com.desgraciamon.showdownarena.mixin

import com.cobblemon.mod.common.api.battles.model.actor.BattleActor
import com.cobblemon.mod.common.api.battles.model.PokemonBattle
import com.cobblemon.mod.common.battles.ShowdownActionRequest
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(ShowdownActionRequest::class)
abstract class ShowdownActionRequestMixin {
    @Inject(method = ["sanitize"], at = [At("RETURN")], remap = false)
    private fun fillMissingAbilities(battle: PokemonBattle, actor: BattleActor, callback: CallbackInfo) {
        val request = this as Any as ShowdownActionRequest
        val side = request.side ?: return
        side.pokemon.forEach { requestPokemon ->
            val hasAbility = runCatching { requestPokemon.ability }.getOrNull()?.isNotBlank() == true
            if (hasAbility) return@forEach

            val pokemon = actor.pokemonList.firstOrNull { it.uuid == requestPokemon.uuid }?.effectedPokemon
            val abilityName = pokemon?.ability?.name.orEmpty()
            requestPokemon.ability = abilityName
            if (runCatching { requestPokemon.baseAbility }.getOrNull().isNullOrBlank()) {
                requestPokemon.baseAbility = abilityName
            }
        }
    }
}