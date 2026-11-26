package com.desgraciamon.announcements

import com.cobblemon.mod.common.api.Priority
import com.cobblemon.mod.common.api.events.CobblemonEvents
import com.cobblemon.mod.common.api.events.battles.BattleFaintedEvent
import com.cobblemon.mod.common.api.events.battles.BattleVictoryEvent
import com.cobblemon.mod.common.api.events.pokemon.PokemonCapturedEvent
import com.cobblemon.mod.common.api.events.pokemon.evolution.EvolutionCompleteEvent
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor
import com.desgraciamon.badges.RctProgressBridge
import com.desgraciamon.economy.CobbleDollarsBridge
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import org.slf4j.LoggerFactory

class AnnouncementsMod : ModInitializer {
    override fun onInitialize() {
        logger.info("Loading Desgraciamon 3 Announcements")
        BulletinStore.registerItemScanner(ContainerItemScanner, ContainerItemScanner)
        TextCatalog.load(mapOf(
            "inbox.unread" to "<color=#E5ECE8>Hay {announcements} y {missions} desde la última vez que te conectaste</color>",
            "count.announcement.one" to "{count} anuncio nuevo",
            "count.announcement.many" to "{count} anuncios nuevos",
            "count.mission.one" to "{count} misión nueva",
            "count.mission.many" to "{count} misiones nuevas",
            "mission.new_online" to "<color=#55D69E><bold>Nueva misión disponible:</bold></color> {title}",
            "mission.progress_step" to "<color=#55D69E>[Misión]</color> {title} <color=#93A39C>—</color> <color=#E5ECE8>{objective}:</color> <color=#FFD04E><bold>{progress}/{amount}</bold></color>",
            "mission.objective_done" to "<color=#55D69E>[Misión]</color> {title} <color=#93A39C>—</color> <color=#78DCA9>¡Objetivo completado! ({done}/{total} objetivos)</color>",
            "mission.completed" to "<prism=#55D69E,#FFD04E><bold>¡Misión completada!</bold></prism> {title}<color=#E5ECE8>. Reclama tu recompensa en el menú de misiones.</color>",
            "reward.item_label" to "{item} x{count}",
            "reward.party_full" to "No hay espacio en tu equipo; el Pokémon se guardó en el PC.",
            "reward.failed" to "No se pudo entregar el Pokémon de la misión; avisa a un administrador.",
            "reward.money_mod_missing" to "Esta misión requiere CobbleDollars, pero el mod no está instalado. El reclamo sigue disponible.",
            "reward.money_failed" to "No se pudo entregar el dinero de la misión. El reclamo sigue disponible.",
            "chat.announcement.header" to "<color=#FF5555>[Anuncio]</color> {title}",
            "hud.dismiss_hint" to "<color=#A8B2AD>[Presiona {key}] para ocultar aviso</color>"
        ))
        PayloadTypeRegistry.playS2C().register(BulletinSyncPayload.TYPE, BulletinSyncPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(OpenAdminScreenPayload.TYPE, OpenAdminScreenPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(BulletinActionPayload.TYPE, BulletinActionPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(BulletinAdminActionPayload.TYPE, BulletinAdminActionPayload.CODEC)
        ServerBulletinNetworking.register()

        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                Commands.literal("modoadmin")
                    .requires { it.player != null && com.desgraciamon.badges.AdminAuth.hasAdminBadge(it.player!!.uuid) }
                    .executes { ctx ->
                        val player = ctx.source.playerOrException
                        if (!com.desgraciamon.badges.AdminAuth.hasAdminBadge(player.uuid)) {
                            ctx.source.sendFailure(Component.literal("§cNo tienes permiso para usar /modoadmin."))
                            return@executes 0
                        }
                        val active = com.desgraciamon.badges.AdminAuth.toggleAdminMode(player)
                        if (active) {
                            ctx.source.sendSuccess({ Component.literal("§a[Desgraciamon] Modo Admin ACTIVADO. Permisos de comandos y herramientas habilitados.") }, false)
                        } else {
                            ctx.source.sendSuccess({ Component.literal("§c[Desgraciamon] Modo Admin DESACTIVADO. Permisos de comandos desactivados (conservas tu insignia; usa /modoadmin para reactivarlos).") }, false)
                        }
                        1
                    }
            )
            dispatcher.register(
                Commands.literal("anuncios")
                    .executes { ctx ->
                        val player = ctx.source.playerOrException
                        ServerPlayNetworking.send(player, OpenAdminScreenPayload("open_inbox"))
                        1
                    }
                    .then(Commands.literal("admin")
                        .requires { it.player != null && com.desgraciamon.badges.AdminAuth.hasAdminBadge(it.player!!.uuid) }
                        .executes { ctx ->
                            val player = ctx.source.playerOrException
                            if (com.desgraciamon.badges.AdminAuth.isAdminModeActive(player)) {
                                com.desgraciamon.badges.BadgeNetwork.syncHitosPlayer(player)
                                BulletinStore.syncFromGitHub(player) {
                                    ServerPlayNetworking.send(player, OpenAdminScreenPayload("announcements"))
                                }
                            } else {
                                ctx.source.sendFailure(Component.literal("§cDebes activar el modo admin primero usando §e/modoadmin§c."))
                            }
                            1
                        }
                    )
            )
            dispatcher.register(
                Commands.literal("misiones")
                    .executes { ctx ->
                        val player = ctx.source.playerOrException
                        ServerPlayNetworking.send(player, OpenAdminScreenPayload("open_missions"))
                        1
                    }
                    .then(Commands.literal("admin")
                        .requires { it.player != null && com.desgraciamon.badges.AdminAuth.hasAdminBadge(it.player!!.uuid) }
                        .executes { ctx ->
                            val player = ctx.source.playerOrException
                            if (com.desgraciamon.badges.AdminAuth.isAdminModeActive(player)) {
                                com.desgraciamon.badges.BadgeNetwork.syncHitosPlayer(player)
                                BulletinStore.syncFromGitHub(player) {
                                    ServerPlayNetworking.send(player, OpenAdminScreenPayload("missions"))
                                }
                            } else {
                                ctx.source.sendFailure(Component.literal("§cDebes activar el modo admin primero usando §e/modoadmin§c."))
                            }
                            1
                        }
                    )
            )
            dispatcher.register(
                Commands.literal("admin")
                    .requires { com.desgraciamon.badges.AdminAuth.isAdminSource(it) }
                    .then(Commands.literal("recargar").executes { ctx ->
                        TextCatalog.reload()
                        BulletinStore.forceReload()
                        com.desgraciamon.joinmessages.JoinMessagesMod.loadConfig()
                        com.desgraciamon.badges.TextCatalog.reload()
                        com.desgraciamon.badges.BadgeData.load()
                        com.desgraciamon.badges.BadgeNetwork.syncAll(ctx.source.server)
                        com.desgraciamon.badges.HitosManager.load()
                        com.desgraciamon.badges.HitosManager.syncAll(ctx.source.server)
                        com.desgraciamon.customnames.CustomNamesManager.load()
                        com.desgraciamon.customnames.CustomNamesManager.syncToAll(ctx.source.server)
                        com.desgraciamon.itemrestrictions.ItemRestrictionsConfig.load()
                        com.desgraciamon.itemrestrictions.ItemRestrictionsMod.broadcastSync(ctx.source.server)
                        com.desgraciamon.economy.MarketStore.reload()
                        com.desgraciamon.economy.TextCatalog.reload()
                        com.desgraciamon.economy.CobbleDollarsBankSync.syncPrices()
                        com.desgraciamon.serverassets.ServerAssetsManager.reloadAndSync(ctx.source.server)
                        ctx.source.sendSuccess({ Component.literal("§a[Admin] Todas las configuraciones del nucleo fueron recargadas y sincronizadas.") }, true)
                        1
                    })
                    .then(com.desgraciamon.training.XpMultiplierManager.buildAdminNode())
                    .then(DiariasCommands.buildNode())
                    .then(com.desgraciamon.badges.BadgesMod.buildHitosAdminNode())
                    .then(com.desgraciamon.serverassets.ServerAssetsCommands.buildSoundNode())
                    .then(com.desgraciamon.serverassets.ServerAssetsCommands.buildEmojisNode())
            )
        }

        ServerLifecycleEvents.SERVER_STARTED.register(BulletinStore::start)
        ServerLifecycleEvents.SERVER_STOPPING.register { BulletinStore.stop() }
        ServerTickEvents.END_SERVER_TICK.register { BulletinStore.tick() }
        ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            val current = ContainerItemScanner.countItems(handler.player)
            lastInventories[handler.player.uuid] = current
            if (CobbleDollarsBridge.isAvailable()) {
                lastBalances[handler.player.uuid] = CobbleDollarsBridge.getBalance(handler.player).toLong()
            }
            lastDefeatedRctTrainers[handler.player.uuid] = RctProgressBridge.readFor(handler.player).defeatedTrainerIds
            BulletinStore.sync(handler.player, isJoinSync = true)
            BulletinStore.reconcilePlayerItems(handler.player, current)
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            lastInventories.remove(handler.player.uuid)
            lastBalances.remove(handler.player.uuid)
            lastDefeatedRctTrainers.remove(handler.player.uuid)
        }
        ServerPlayNetworking.registerGlobalReceiver(BulletinActionPayload.TYPE) { payload, context ->
            context.server().execute { BulletinStore.action(context.player(), payload.action, payload.id) }
        }
        ServerPlayNetworking.registerGlobalReceiver(BulletinAdminActionPayload.TYPE) { payload, context ->
            val player = context.player()
            if (!com.desgraciamon.badges.AdminAuth.isAdminModeActive(player)) return@registerGlobalReceiver
            context.server().execute {
                when (payload.action) {
                    "saveAnnouncement" -> {
                        val ann = BulletinJson.gson.fromJson(payload.payloadJson, Announcement::class.java)
                        BulletinStore.saveAdminAnnouncement(ann)
                    }
                    "deleteAnnouncement" -> {
                        BulletinStore.deleteAdminAnnouncement(payload.payloadJson)
                    }
                    "saveMission" -> {
                        val mission = BulletinJson.gson.fromJson(payload.payloadJson, Mission::class.java)
                        BulletinStore.saveAdminMission(mission)
                    }
                    "deleteMission" -> {
                        BulletinStore.deleteAdminMission(payload.payloadJson)
                    }
                    "publishGithub" -> {
                        BulletinStore.publishToGitHub(player)
                    }
                    "syncGithub" -> {
                        BulletinStore.syncFromGitHub(player)
                    }
                    "requestAdminScreen" -> {
                        val tab = payload.payloadJson.ifBlank { "announcements" }
                        com.desgraciamon.badges.BadgeNetwork.syncHitosPlayer(player)
                        BulletinStore.syncFromGitHub(player) {
                            ServerPlayNetworking.send(player, OpenAdminScreenPayload(tab))
                        }
                    }
                    "saveHitoMilestone" -> {
                        val obj = com.google.gson.JsonParser.parseString(payload.payloadJson).asJsonObject
                        val originalId = if (obj.has("originalId")) obj.get("originalId").asString else ""
                        val milestone = BulletinJson.gson.fromJson(
                            obj.getAsJsonObject("milestone"),
                            com.desgraciamon.badges.HitoMilestone::class.java
                        )
                        if (milestone != null) {
                            com.desgraciamon.badges.HitosManager.saveFullMilestone(originalId, milestone, player.server)
                        }
                    }
                    "deleteHitoMilestone" -> {
                        com.desgraciamon.badges.HitosManager.removeMilestone(payload.payloadJson, player.server)
                    }
                    "saveHitosGlobal" -> {
                        val obj = com.google.gson.JsonParser.parseString(payload.payloadJson).asJsonObject
                        val headerTitle = if (obj.has("headerTitle")) obj.get("headerTitle").asString else ""
                        val startDate = if (obj.has("serverStartDate")) obj.get("serverStartDate").asString else ""
                        val manualDay = if (obj.has("manualCurrentDay")) obj.get("manualCurrentDay").asInt else 0
                        com.desgraciamon.badges.HitosManager.updateGlobalSettings(headerTitle, startDate, manualDay, player.server)
                    }
                }
            }
        }

        CobblemonEvents.POKEMON_CAPTURED.subscribe(::onPokemonCaptured)
        CobblemonEvents.BATTLE_FAINTED.subscribe(::onPokemonDefeated)
        CobblemonEvents.EVOLUTION_COMPLETE.subscribe(::onPokemonEvolved)
        CobblemonEvents.BATTLE_VICTORY.subscribe(Priority.HIGH, ::onBattleVictory)
        ServerTickEvents.END_SERVER_TICK.register { server ->
            if (++inventoryPoll % 20 == 0) {
                server.playerList.players.forEach { player ->
                    updateItemProgress(player)
                    updateMoneyProgress(player)
                }
            }
        }
    }

    private fun onPokemonCaptured(event: PokemonCapturedEvent) {
        BulletinStore.progressCatch(event.player, event.pokemon)
    }

    private fun onPokemonDefeated(event: BattleFaintedEvent) {
        val defeatedPokemon = event.killed.originalPokemon
        val defeatedActor = event.battle.actors.firstOrNull { actor ->
            actor.pokemonList.any { it.originalPokemon.uuid == defeatedPokemon.uuid }
        }
        event.battle.actors.filterIsInstance<PlayerBattleActor>()
            .filter { defeatedActor == null || it.getSide() != defeatedActor.getSide() }
            .mapNotNull { it.entity }.forEach { player ->
            BulletinStore.progressDefeat(player, defeatedPokemon)
        }
    }

    private fun onPokemonEvolved(event: EvolutionCompleteEvent) {
        val owner = event.pokemon.getOwnerPlayer() ?: return
        val initialSpeciesId = event.sourcePokemon.species.resourceIdentifier.toString()
        BulletinStore.progressEvolution(owner, initialSpeciesId)
    }

    private fun onBattleVictory(event: BattleVictoryEvent) {
        val winnerPlayers = event.winners.filterIsInstance<PlayerBattleActor>().mapNotNull { it.entity }
        if (winnerPlayers.isEmpty()) return

        val showdownMeta = runCatching {
            Class.forName("com.desgraciamon.showdownarena.ShowdownArenaCommands")
                .getMethod("getBattleMeta", java.util.UUID::class.java)
                .invoke(null, event.battle.battleId)
        }.getOrNull()

        if (showdownMeta != null) {
            winnerPlayers.forEach { BulletinStore.progressShowdownWin(it) }
            return
        }

        if (event.battle.isPvP) {
            winnerPlayers.forEach { BulletinStore.progressPvpWin(it) }
            return
        }

        // Detectar victoria contra NPC de rctmod (tanto primer combate como revancha)
        val rctTrainerIds = resolveRctBattleTrainerIds(event.battle.battleId)
        for (player in winnerPlayers) {
            val prevDefeated = lastDefeatedRctTrainers[player.uuid].orEmpty()
            val currDefeated = RctProgressBridge.readFor(player).defeatedTrainerIds
            lastDefeatedRctTrainers[player.uuid] = currDefeated
            val newlyDefeated = currDefeated - prevDefeated

            when {
                rctTrainerIds.isNotEmpty() -> rctTrainerIds.forEach { BulletinStore.progressRctNpcWin(player, it) }
                newlyDefeated.isNotEmpty() -> newlyDefeated.forEach { BulletinStore.progressRctNpcWin(player, it) }
            }
        }
    }

    private fun resolveRctBattleTrainerIds(battleId: java.util.UUID): List<String> {
        return runCatching {
            val rctClass = Class.forName("com.gitlab.srcmc.rctmod.api.RCTMod")
            val rctInstance = rctClass.getMethod("getInstance").invoke(null) ?: return emptyList()
            val tm = rctInstance.javaClass.getMethod("getTrainerManager").invoke(rctInstance) ?: return emptyList()
            val opt = tm.javaClass.getMethod("getBattle", java.util.UUID::class.java).invoke(tm, battleId) as? java.util.Optional<*> ?: return emptyList()
            val battle = opt.orElse(null) ?: return emptyList()
            val mobs = battle.javaClass.getMethod("getTrainerSideMobs").invoke(battle) as? Collection<*> ?: return emptyList()
            mobs.mapNotNull { mob ->
                if (mob == null) null else runCatching {
                    val data = tm.javaClass.getMethod("getData", mob.javaClass).invoke(tm, mob)
                    data?.javaClass?.getMethod("getTrainerId")?.invoke(data) as? String
                }.getOrNull() ?: runCatching {
                    mob.javaClass.getMethod("getTrainerId").invoke(mob) as? String
                }.getOrNull() ?: ""
            }.ifEmpty { listOf("") }
        }.getOrDefault(emptyList())
    }

    private val lastInventories = mutableMapOf<java.util.UUID, Map<String, Int>>()
    private val lastBalances = mutableMapOf<java.util.UUID, Long>()
    private val lastDefeatedRctTrainers = mutableMapOf<java.util.UUID, Set<String>>()
    private var inventoryPoll = 0

    private fun updateItemProgress(player: ServerPlayer) {
        val current = ContainerItemScanner.countItems(player)
        val previous = lastInventories.put(player.uuid, current).orEmpty()
        current.forEach { (id, count) ->
            val gained = count - (previous[id] ?: 0)
            if (gained > 0) BulletinStore.progressItem(player, id, gained)
        }
        BulletinStore.reconcilePlayerItems(player, current)
    }

    private fun updateMoneyProgress(player: ServerPlayer) {
        if (!CobbleDollarsBridge.isAvailable()) return
        val current = CobbleDollarsBridge.getBalance(player).toLong()
        val previous = lastBalances.put(player.uuid, current) ?: return
        val delta = current - previous
        if (delta > 0L) {
            BulletinStore.progressMoneyEarned(player, delta)
        } else if (delta < 0L) {
            BulletinStore.progressMoneySpent(player, -delta)
        }
    }

    companion object {
        internal val logger = LoggerFactory.getLogger("desgraciamon4-announcements")
    }
}

object DiariasCommands {
    val ROTATE_TARGETS = listOf("1", "2", "3", "todas", "recompensa")
    val OPEN_SLOTS = listOf("1", "2", "3")
    val SIMULATE_MODES = listOf("08:00", "13:30", "19:00", "reset")

    fun hasPermission(source: CommandSourceStack): Boolean {
        return com.desgraciamon.badges.AdminAuth.isAdminSource(source)
    }

    fun normalizeRotateTarget(raw: String): String? {
        val normalized = raw.trim().lowercase()
        return when (normalized) {
            "1", "2", "3", "todas", "recompensa" -> normalized
            "all" -> "todas"
            "bonus" -> "recompensa"
            else -> null
        }
    }

    fun normalizeOpenSlot(slot: Int): Int? = if (slot in 1..3) slot else null

    fun normalizeSimulateMode(raw: String): String? {
        val trimmed = raw.trim().removeSurrounding("\"").lowercase()
        return when (trimmed) {
            "08:00", "8:00" -> "08:00"
            "13:30" -> "13:30"
            "19:00" -> "19:00"
            "reset" -> "reset"
            else -> null
        }
    }

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(buildNode())
    }

    fun buildNode(): com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> {
        return Commands.literal("diarias")
                .requires { hasPermission(it) }
                .then(
                    Commands.literal("pausa")
                        .executes { ctx ->
                            val paused = BulletinStore.toggleDailyPause(ctx.source)
                            val msg = if (paused) {
                                "§e[Diarias] §fMisiones diarias §cpausadas §fpara todo el servidor."
                            } else {
                                "§e[Diarias] §fMisiones diarias §areanudadas §f(horario sincronizado)."
                            }
                            ctx.source.sendSuccess({ Component.literal(msg) }, true)
                            1
                        }
                )
                .then(
                    Commands.literal("rotar")
                        .executes { ctx ->
                            try {
                                val result = BulletinStore.rotateDailyMissions("todas")
                                ctx.source.sendSuccess({ Component.literal("§e[Diarias] §f$result") }, true)
                                1
                            } catch (e: Exception) {
                                AnnouncementsMod.logger.error("Error al ejecutar /diarias rotar todas", e)
                                ctx.source.sendFailure(Component.literal("§c[Diarias] Error al rotar: ${e.message}"))
                                0
                            }
                        }
                        .then(
                            Commands.argument("objetivo", StringArgumentType.word())
                                .suggests { _, builder -> SharedSuggestionProvider.suggest(ROTATE_TARGETS, builder) }
                                .executes { ctx ->
                                    val raw = StringArgumentType.getString(ctx, "objetivo")
                                    val target = normalizeRotateTarget(raw)
                                    if (target == null) {
                                        ctx.source.sendFailure(
                                            Component.literal("§c[Diarias] Objetivo invalido. Usa: 1, 2, 3, todas o recompensa.")
                                        )
                                        return@executes 0
                                    }
                                    try {
                                        val result = BulletinStore.rotateDailyMissions(target)
                                        ctx.source.sendSuccess({ Component.literal("§e[Diarias] §f$result") }, true)
                                        1
                                    } catch (e: Exception) {
                                        AnnouncementsMod.logger.error("Error al ejecutar /diarias rotar $raw", e)
                                        ctx.source.sendFailure(Component.literal("§c[Diarias] Error al rotar $raw: ${e.message}"))
                                        0
                                    }
                                }
                        )
                )
                .then(
                    Commands.literal("recompensa")
                        .executes { ctx ->
                            try {
                                val result = BulletinStore.resetDailyBonus()
                                ctx.source.sendSuccess({ Component.literal("§e[Diarias] §a$result") }, true)
                                1
                            } catch (e: Exception) {
                                AnnouncementsMod.logger.error("Error al ejecutar /diarias recompensa", e)
                                ctx.source.sendFailure(Component.literal("§c[Diarias] Error al resetear recompensa diaria: ${e.message}"))
                                0
                            }
                        }
                )
                .then(
                    Commands.literal("abrir")
                        .then(
                            Commands.argument("ranura", IntegerArgumentType.integer(1, 3))
                                .suggests { _, builder -> SharedSuggestionProvider.suggest(OPEN_SLOTS, builder) }
                                .executes { ctx ->
                                    val slot = IntegerArgumentType.getInteger(ctx, "ranura")
                                    val validSlot = normalizeOpenSlot(slot)
                                    if (validSlot == null) {
                                        ctx.source.sendFailure(
                                            Component.literal("§c[Diarias] Ranura invalida. Usa: 1, 2 o 3.")
                                        )
                                        return@executes 0
                                    }
                                    val opened = BulletinStore.forceUnlockDailySlot(validSlot)
                                    if (opened) {
                                        ctx.source.sendSuccess(
                                            { Component.literal("§e[Diarias] §aRanura diaria $validSlot desbloqueada antes de su hora.") },
                                            true
                                        )
                                        1
                                    } else {
                                        ctx.source.sendFailure(
                                            Component.literal("§c[Diarias] No se pudo abrir la ranura $validSlot (ya esta desbloqueada o en pausa).")
                                        )
                                        0
                                    }
                                }
                        )
                )
                .then(
                    Commands.literal("simular")
                        .then(
                            Commands.argument("modo", StringArgumentType.greedyString())
                                .suggests { _, builder -> SharedSuggestionProvider.suggest(SIMULATE_MODES, builder) }
                                .executes { ctx ->
                                    val raw = StringArgumentType.getString(ctx, "modo")
                                    val mode = normalizeSimulateMode(raw)
                                    if (mode == null) {
                                        ctx.source.sendFailure(
                                            Component.literal("§c[Diarias] Modo invalido. Usa: 08:00, 13:30, 19:00 o reset.")
                                        )
                                        return@executes 0
                                    }
                                    val result = BulletinStore.simulateDailyTime(mode)
                                    ctx.source.sendSuccess({ Component.literal("§e[Diarias] §f$result") }, true)
                                    1
                                }
                        )
                )
    }
}

object ServerBulletinNetworking {
    fun register() = Unit

    fun sendSnapshot(player: ServerPlayer, snapshot: ClientBulletinSnapshot) {
        ServerPlayNetworking.send(player, BulletinSyncPayload(BulletinJson.gson.toJson(snapshot)))
    }
}
