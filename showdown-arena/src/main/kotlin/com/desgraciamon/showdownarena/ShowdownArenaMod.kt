package com.desgraciamon.showdownarena

import com.cobblemon.mod.common.api.Priority
import com.cobblemon.mod.common.api.events.CobblemonEvents
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.TextColor

class ShowdownArenaMod : ModInitializer {
    override fun onInitialize() {
        TextCatalog.load(mapOf(
            "error.players_only" to "Este modo solo se puede iniciar dentro del juego.",
            "error.format" to "Formato no válido. Elige Random Battle u OU de una generación disponible.",
            "error.in_battle" to "Ya estás en un combate.",
            "error.team_generation" to "No se pudo generar el equipo: {error}",
            "error.trainer_creation" to "No se pudo crear el rival de prueba.",
            "error.player_team" to "No se pudo generar tu equipo: {error}",
            "trainer.name" to "Rival de prueba",
            "test.started" to "Combate de prueba {format} iniciado. Tu equipo real no cambiará.",
            "error.test_battle_start" to "Cobblemon no pudo iniciar el combate de prueba.",
            "error.test_battle_exception" to "No se pudo iniciar el combate de prueba: {error}",
            "error.self_challenge" to "No puedes desafiarte a ti mismo.",
            "error.challenge_pending" to "Ese jugador ya tiene un desafío pendiente.",
            "challenge.received.clickable" to "{player} te desafía en {format}.",
            "challenge.sent" to "Desafío enviado a {player} para {format}.",
            "challenge.accept.hover" to "Aceptar desafío y empezar el combate",
            "challenge.decline.hover" to "Rechazar desafío",
            "challenge.accepted" to "Combate aceptado por {player}.",
            "challenge.not_accepted" to "Combate no aceptado por {player}.",
            "challenge.declined" to "Has rechazado el combate de {player}.",
            "error.challenge_expired" to "No hay un desafío vigente de ese jugador.",
            "error.team_generation.generic" to "No se pudo generar un equipo: {error}",
            "battle.started" to "Combate {format} iniciado contra {player}.",
            "error.battle_start" to "Cobblemon no pudo iniciar el combate.",
            "error.battle_start.exception" to "No se pudo iniciar el combate: {error}",
            "error.no_challenge" to "No hay un desafío pendiente de ese jugador.",
            "challenge.declined_notice" to "{player} rechazó tu desafío."
        ))

        ArenaZoneManager.init()
        ArenaPlaylistManager.init()
        ArenaLightsManager.init()
        ProtectedZoneManager.init()
        SkyLogoManager.init()
        GtsZoneManager.init()
        ShowdownBattlePartySync.register()

        // Evitar "R para combatir", ataques o capturas sobre Pokémon temporales generados por Showdown
        ServerEntityEvents.ENTITY_LOAD.register { entity, _ ->
            if (entity is PokemonEntity && entity.pokemon.persistentData.getBoolean(ArenaTeamFactory.SHOWDOWN_TEMP_NBT_KEY)) {
                entity.entityData.set(PokemonEntity.UNBATTLEABLE, true)
                entity.isInvulnerable = true
            }
        }
        CobblemonEvents.POKEMON_ENTITY_SPAWN.subscribe(Priority.HIGHEST) { event ->
            val entity = event.entity
            if (entity.pokemon.persistentData.getBoolean(ArenaTeamFactory.SHOWDOWN_TEMP_NBT_KEY)) {
                entity.entityData.set(PokemonEntity.UNBATTLEABLE, true)
                entity.isInvulnerable = true
            } else if (ArenaZoneManager.config.enabled) {
                val zone = ArenaZoneManager.config.zone
                if (zone != null && zone.contains(entity) && ArenaZoneManager.isCommonWildPokemon(entity)) {
                    event.cancel()
                }
            }
        }
        CobblemonEvents.THROWN_POKEBALL_HIT.subscribe(Priority.HIGHEST) { event ->
            if (event.pokemon.pokemon.persistentData.getBoolean(ArenaTeamFactory.SHOWDOWN_TEMP_NBT_KEY)) {
                event.cancel()
            }
        }
        CobblemonEvents.BATTLE_STARTED_PRE.subscribe(Priority.HIGHEST) { event ->
            if (event.battle.isPvW && event.battle.actors.any { actor ->
                    actor.pokemonList.any { it.originalPokemon.persistentData.getBoolean(ArenaTeamFactory.SHOWDOWN_TEMP_NBT_KEY) }
                }) {
                event.cancel()
            }
        }
        CobblemonEvents.BATTLE_VICTORY.subscribe(Priority.NORMAL) { event ->
            val meta = ShowdownArenaCommands.getBattleMeta(event.battle.battleId) ?: return@subscribe
            if (!meta.isPvp || meta.announced) return@subscribe
            meta.announced = true

            val winners = event.winners.joinToString(", ") { it.getName().string }.ifBlank { return@subscribe }
            val losers = event.losers.joinToString(", ") { it.getName().string }.ifBlank { return@subscribe }
            val server = event.battle.players.firstOrNull()?.server ?: return@subscribe

            val arenaColor = TextColor.fromRgb(0xFCA103)
            val msg = Component.empty()
                .append(Component.literal("ARENA").withStyle { it.withColor(arenaColor).withBold(true) })
                .append(Component.literal(" $winners venció a $losers en un combate de ${meta.formatLabel}").withStyle { it.withColor(arenaColor).withBold(false) })
            server.playerList.broadcastSystemMessage(msg, false)
        }

        ServerLifecycleEvents.SERVER_STARTING.register { server ->
            ArenaZoneManager.onServerStart(server)
            ArenaLightsManager.onServerStart(server)
            ProtectedZoneManager.onServerStart(server)
            GtsZoneManager.onServerStart(server)
        }

        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            ShowdownArenaCommands.register(dispatcher)
            ArenaLightsManager.registerCommands(dispatcher)
            ProtectedZoneManager.registerCommands(dispatcher)
            SkyLogoManager.registerCommands(dispatcher)
        }
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            ShowdownEngineBridge.warmupAsync()
            ArenaPlaylistManager.refreshAsync(server)
        }
        ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            ArenaZoneManager.syncToPlayer(handler.player)
            ArenaPlaylistManager.syncToPlayer(handler.player)
            ArenaLightsManager.syncToPlayer(handler.player)
            GtsZoneManager.syncToPlayer(handler.player)
        }
        ServerTickEvents.END_SERVER_TICK.register(ShowdownArenaCommands::tickTestBattles)
        ServerTickEvents.END_SERVER_TICK.register(ArenaLightsManager::tick)
        ServerTickEvents.END_SERVER_TICK.register(ArenaZoneManager::tickWildCleanup)
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            ShowdownArenaCommands.onPlayerDisconnect(handler.player)
        }
        ServerLifecycleEvents.SERVER_STOPPING.register {
            ArenaZoneManager.onServerStop()
            ArenaLightsManager.onServerStop()
            ProtectedZoneManager.onServerStop()
            GtsZoneManager.onServerStop()
            ShowdownArenaCommands.removeAllTestBattles()
        }
    }
}
