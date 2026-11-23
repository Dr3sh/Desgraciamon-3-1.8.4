package com.desgraciamon.showdownarena

import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents

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
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            ShowdownArenaCommands.removeTestBattle(handler.player.uuid)
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
