package com.addoncobblemon.legendarybestiary

import com.cobblemon.mod.common.api.events.CobblemonEvents
import com.cobblemon.mod.common.api.events.pokemon.PokemonCapturedEvent
import com.cobblemon.mod.common.api.events.pokemon.PokemonGainedEvent
import com.cobblemon.mod.common.api.events.pokemon.TradeEvent
import com.cobblemon.mod.common.api.events.pokemon.evolution.EvolutionCompleteEvent
import com.cobblemon.mod.common.api.events.storage.ReleasePokemonEvent
import com.cobblemon.mod.common.pokemon.Pokemon
import com.cobblemon.mod.common.util.getPlayer
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer

class LegendaryBestiaryMod : ModInitializer {
    override fun onInitialize() {
        PayloadTypeRegistry.playS2C().register(LegendaryBestiaryPayload.TYPE, LegendaryBestiaryPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(LegendaryBestiaryRequest.TYPE, LegendaryBestiaryRequest.CODEC)
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            LegendaryBestiaryData.get(server).reconcileAllPlayers(server)
        }
        ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            val server = handler.player.server
            if (LegendaryBestiaryData.get(server).reconcileAllPlayers(server)) {
                server.playerList.players.forEach(::sendEntries)
            } else {
                sendEntries(handler.player)
            }
        }
        ServerPlayNetworking.registerGlobalReceiver(LegendaryBestiaryRequest.TYPE) { _, context ->
            context.server().execute {
                val server = context.server()
                if (LegendaryBestiaryData.get(server).reconcileAllPlayers(server)) {
                    server.playerList.players.forEach(::sendEntries)
                } else {
                    sendEntries(context.player())
                }
            }
        }
        CobblemonEvents.POKEMON_CAPTURED.subscribe(::onPokemonCaptured)
        CobblemonEvents.POKEMON_RELEASED_EVENT_POST.subscribe(::onPokemonReleased)
        CobblemonEvents.EVOLUTION_COMPLETE.subscribe(::onEvolutionComplete)
        CobblemonEvents.TRADE_EVENT_POST.subscribe(::onTradePost)
        CobblemonEvents.POKEMON_GAINED.subscribe(::onPokemonGained)
    }

    private fun isTrackedBestiaryPokemon(pokemon: Pokemon): Boolean {
        if (pokemon.isUltraBeast() || LegendaryBestiaryData.isUltraBeast(pokemon.species)) return false
        return pokemon.isLegendary() || pokemon.isMythical()
    }

    private fun onPokemonCaptured(event: PokemonCapturedEvent) {
        val pokemon = event.pokemon
        if (!isTrackedBestiaryPokemon(pokemon)) return

        val baseSpeciesId = pokemon.species.resourceIdentifier.toString()
        val formName = pokemon.form.name.lowercase().trim()
        val key = if (LegendaryBestiaryData.isNotableForm(formName)) {
            "$baseSpeciesId#$formName"
        } else {
            baseSpeciesId
        }

        val original = pokemon.originalTrainerName?.takeIf { it.isNotBlank() } ?: event.player.name.string
        val current = event.player.name.string
        val now = System.currentTimeMillis()
        val data = LegendaryBestiaryData.get(event.player.server)
        data.recordCapture(key, originalOwner = original, currentOwner = current, capturedAt = now)
        if (LegendaryBestiaryData.isNotableForm(formName)) {
            val canonicalForm = LegendaryBestiaryData.normalizeFormKey(formName)
            data.recordCapture("$baseSpeciesId#$canonicalForm", originalOwner = original, currentOwner = current, capturedAt = now)
        }
        event.player.server.playerList.players.forEach(::sendEntries)
    }

    private fun onPokemonReleased(event: ReleasePokemonEvent.Post) {
        if (!isTrackedBestiaryPokemon(event.pokemon)) return
        val server = event.player.server
        LegendaryBestiaryData.get(server).reconcileAllPlayers(server)
        server.playerList.players.forEach(::sendEntries)
    }

    private fun onEvolutionComplete(event: EvolutionCompleteEvent) {
        if (!isTrackedBestiaryPokemon(event.pokemon) && !isTrackedBestiaryPokemon(event.sourcePokemon)) return
        val server = (event.pokemon.getOwnerPlayer() as? ServerPlayer)?.server
            ?: com.cobblemon.mod.common.util.server()
            ?: return
        LegendaryBestiaryData.get(server).reconcileAllPlayers(server)
        server.playerList.players.forEach(::sendEntries)
    }

    private fun onTradePost(event: TradeEvent.Post) {
        val p1 = event.tradeParticipant1.uuid.getPlayer() as? ServerPlayer
        val p2 = event.tradeParticipant2.uuid.getPlayer() as? ServerPlayer
        val server = p1?.server ?: p2?.server ?: return

        if (p2 != null) handlePokemonTransfer(server, p2, event.tradeParticipant1Pokemon)
        if (p1 != null) handlePokemonTransfer(server, p1, event.tradeParticipant2Pokemon)
        LegendaryBestiaryData.get(server).reconcileAllPlayers(server)
        server.playerList.players.forEach(::sendEntries)
    }

    private fun onPokemonGained(event: PokemonGainedEvent) {
        val pokemon = event.pokemon
        if (!isTrackedBestiaryPokemon(pokemon)) return
        val server = pokemon.getOwnerPlayer()?.server
            ?: com.cobblemon.mod.common.util.server()
            ?: return
        val player = server.playerList.getPlayer(event.playerId) ?: return
        handlePokemonTransfer(server, player, pokemon)
    }

    private fun handlePokemonTransfer(server: MinecraftServer, newOwner: ServerPlayer, pokemon: Pokemon) {
        if (!isTrackedBestiaryPokemon(pokemon)) return

        val baseSpeciesId = pokemon.species.resourceIdentifier.toString()
        val formName = pokemon.form.name.lowercase().trim()
        val key = if (LegendaryBestiaryData.isNotableForm(formName)) {
            "$baseSpeciesId#$formName"
        } else {
            baseSpeciesId
        }

        val original = pokemon.originalTrainerName?.takeIf { it.isNotBlank() }
        val current = newOwner.name.string

        val data = LegendaryBestiaryData.get(server)
        data.updateOwner(key, currentOwner = current, originalOwner = original)
        if (LegendaryBestiaryData.isNotableForm(formName)) {
            val canonicalForm = LegendaryBestiaryData.normalizeFormKey(formName)
            data.updateOwner("$baseSpeciesId#$canonicalForm", currentOwner = current, originalOwner = original)
        }
        server.playerList.players.forEach(::sendEntries)
    }

    private fun sendEntries(player: ServerPlayer) {
        ServerPlayNetworking.send(player, LegendaryBestiaryPayload(LegendaryBestiaryData.get(player.server).entries()))
    }
}
