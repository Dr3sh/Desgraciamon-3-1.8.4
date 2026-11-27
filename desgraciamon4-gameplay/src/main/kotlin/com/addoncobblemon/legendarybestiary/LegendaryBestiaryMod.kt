package com.addoncobblemon.legendarybestiary

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.events.CobblemonEvents
import com.cobblemon.mod.common.api.events.pokemon.PokemonCapturedEvent
import com.cobblemon.mod.common.api.events.pokemon.PokemonGainedEvent
import com.cobblemon.mod.common.api.events.pokemon.TradeEvent
import com.cobblemon.mod.common.pokemon.Pokemon
import com.cobblemon.mod.common.util.getPlayer
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer

class LegendaryBestiaryMod : ModInitializer {
    override fun onInitialize() {
        PayloadTypeRegistry.playS2C().register(LegendaryBestiaryPayload.TYPE, LegendaryBestiaryPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(LegendaryBestiaryRequest.TYPE, LegendaryBestiaryRequest.CODEC)
        ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            refreshOnlineOwners(handler.player.server)
            sendEntries(handler.player)
        }
        ServerPlayNetworking.registerGlobalReceiver(LegendaryBestiaryRequest.TYPE) { _, context ->
            context.server().execute {
                refreshOnlineOwners(context.server())
                sendEntries(context.player())
            }
        }
        CobblemonEvents.POKEMON_CAPTURED.subscribe(::onPokemonCaptured)
        CobblemonEvents.TRADE_EVENT_POST.subscribe(::onTradePost)
        CobblemonEvents.POKEMON_GAINED.subscribe(::onPokemonGained)
    }

    private fun onPokemonCaptured(event: PokemonCapturedEvent) {
        val pokemon = event.pokemon
        if (pokemon.isUltraBeast() || LegendaryBestiaryData.isUltraBeast(pokemon.species)) return
        if (!pokemon.isLegendary() && !pokemon.isMythical()) return

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

    private fun onTradePost(event: TradeEvent.Post) {
        val p1 = event.tradeParticipant1.uuid.getPlayer() as? ServerPlayer
        val p2 = event.tradeParticipant2.uuid.getPlayer() as? ServerPlayer
        val server = p1?.server ?: p2?.server ?: return

        if (p2 != null) handlePokemonTransfer(server, p2, event.tradeParticipant1Pokemon)
        if (p1 != null) handlePokemonTransfer(server, p1, event.tradeParticipant2Pokemon)
    }

    private fun onPokemonGained(event: PokemonGainedEvent) {
        val pokemon = event.pokemon
        val server = pokemon.getOwnerPlayer()?.server
            ?: com.cobblemon.mod.common.util.server()
            ?: return
        val player = server.playerList.getPlayer(event.playerId) ?: return
        handlePokemonTransfer(server, player, pokemon)
    }

    private fun handlePokemonTransfer(server: MinecraftServer, newOwner: ServerPlayer, pokemon: Pokemon) {
        if (pokemon.isUltraBeast() || LegendaryBestiaryData.isUltraBeast(pokemon.species)) return
        if (!pokemon.isLegendary() && !pokemon.isMythical()) return

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

    private fun refreshOnlineOwners(server: MinecraftServer) {
        val data = LegendaryBestiaryData.get(server)
        for (player in server.playerList.players) {
            val currentName = player.name.string
            val party = runCatching { Cobblemon.storage.getParty(player) }.getOrNull()
            val pc = runCatching { Cobblemon.storage.getPC(player) }.getOrNull()
            val allMons = buildList {
                if (party != null) for (p in party) add(p)
                if (pc != null) for (p in pc) add(p)
            }
            for (pokemon in allMons) {
                if (pokemon.isUltraBeast() || LegendaryBestiaryData.isUltraBeast(pokemon.species)) continue
                if (!pokemon.isLegendary() && !pokemon.isMythical()) continue
                val baseSpeciesId = pokemon.species.resourceIdentifier.toString()
                val formName = pokemon.form.name.lowercase().trim()
                val key = if (LegendaryBestiaryData.isNotableForm(formName)) "$baseSpeciesId#$formName" else baseSpeciesId
                val original = pokemon.originalTrainerName?.takeIf { it.isNotBlank() }
                data.updateOwner(key, currentOwner = currentName, originalOwner = original)
                if (LegendaryBestiaryData.isNotableForm(formName)) {
                    val canonicalForm = LegendaryBestiaryData.normalizeFormKey(formName)
                    data.updateOwner("$baseSpeciesId#$canonicalForm", currentOwner = currentName, originalOwner = original)
                }
            }
        }
    }

    private fun sendEntries(player: ServerPlayer) {
        ServerPlayNetworking.send(player, LegendaryBestiaryPayload(LegendaryBestiaryData.get(player.server).entries()))
    }
}
