package com.desgraciamon.legendarylimits

import com.addoncobblemon.legendarycooldown.RefundTracker
import com.cobblemon.mod.common.api.Priority
import com.cobblemon.mod.common.api.events.CobblemonEvents
import com.cobblemon.mod.common.api.events.pokeball.PokeBallCaptureCalculatedEvent
import com.cobblemon.mod.common.api.events.pokeball.ThrownPokeballHitEvent
import com.cobblemon.mod.common.api.events.pokemon.PokemonCapturedEvent
import com.cobblemon.mod.common.api.events.pokemon.PokemonGainedEvent
import com.cobblemon.mod.common.api.events.pokemon.TradeEvent
import com.cobblemon.mod.common.api.events.pokemon.evolution.EvolutionCompleteEvent
import com.cobblemon.mod.common.api.events.storage.ReleasePokemonEvent
import com.cobblemon.mod.common.api.pokeball.catching.CaptureContext
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.util.getPlayer
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.suggestion.SuggestionProvider
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.commands.arguments.ResourceLocationArgument
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

class LegendaryLimitsMod : ModInitializer {
    private val speciesSuggestions = SuggestionProvider<CommandSourceStack> { _, builder ->
        SharedSuggestionProvider.suggestResource(
            PokemonSpecies.species.map { it.resourceIdentifier },
            builder
        )
    }

    override fun onInitialize() {
        LegendaryLimitsConfig.load()

        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            LegendaryLimitsData.get(server).resyncAllPlayers(server)
        }

        ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            LegendaryLimitsData.get(handler.player.server).syncPlayer(handler.player)
        }

        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            LegendaryLimitsData.get(handler.player.server).syncPlayer(handler.player)
        }

        ServerTickEvents.END_SERVER_TICK.register { server ->
            if (server.tickCount % 200 != 0) return@register
            val data = LegendaryLimitsData.get(server)
            server.playerList.players.forEach { player ->
                data.syncPlayer(player)
            }
        }

        CobblemonEvents.THROWN_POKEBALL_HIT.subscribe(Priority.HIGH, ::onPokeballHit)
        CobblemonEvents.POKE_BALL_CAPTURE_CALCULATED.subscribe(Priority.HIGH, ::onCaptureCalculated)
        CobblemonEvents.POKEMON_CAPTURED.subscribe(Priority.NORMAL, ::onPokemonCaptured)
        CobblemonEvents.POKEMON_RELEASED_EVENT_POST.subscribe(Priority.NORMAL, ::onPokemonReleased)
        CobblemonEvents.EVOLUTION_COMPLETE.subscribe(Priority.NORMAL, ::onEvolutionComplete)
        CobblemonEvents.TRADE_EVENT_POST.subscribe(Priority.NORMAL, ::onTradePost)
        CobblemonEvents.POKEMON_GAINED.subscribe(Priority.NORMAL, ::onPokemonGained)

        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            registerCommands(dispatcher)
        }
    }

    private fun onPokeballHit(event: ThrownPokeballHitEvent) {
        if (event.isCanceled) return
        val player = event.pokeBall.owner as? ServerPlayer ?: return
        val pokemon = event.pokemon.pokemon

        val status = LegendaryLimitsService.getLimitStatus(
            player.server,
            pokemon.species.resourceIdentifier.toString(),
            pokemon.species
        )

        if (!status.canCapture) {
            RefundTracker.mark(event.pokeBall.uuid, event.pokeBall.pokeBall.item().defaultInstance)

            val ownersText = if (status.owners.isNotEmpty()) {
                " Dueño(s): ${status.owners.joinToString(", ")}."
            } else {
                ""
            }
            val msg = Component.literal(
                "§c¡Límite global del servidor alcanzado para ${status.displayName} " +
                    "(${status.currentCount}/${status.effectiveLimit})!$ownersText"
            )
            player.displayClientMessage(msg, true)
            player.sendSystemMessage(msg)

            if (event.pokemon.battleId != null) {
                event.pokeBall.captureFuture.complete(false)
            }
            event.cancel()
        }
    }

    private fun onCaptureCalculated(event: PokeBallCaptureCalculatedEvent) {
        val player = event.thrower as? ServerPlayer ?: return
        val pokemon = event.pokemonEntity.pokemon

        val status = LegendaryLimitsService.getLimitStatus(
            player.server,
            pokemon.species.resourceIdentifier.toString(),
            pokemon.species
        )

        if (!status.canCapture) {
            event.captureResult = CaptureContext(
                numberOfShakes = 0,
                isSuccessfulCapture = false,
                isCriticalCapture = false
            )
        }
    }

    private fun onPokemonCaptured(event: PokemonCapturedEvent) {
        val player = event.player
        val data = LegendaryLimitsData.get(player.server)
        data.syncPokemon(player.uuid, player.name.string, event.pokemon)
        data.syncPlayer(player)
    }

    private fun onPokemonReleased(event: ReleasePokemonEvent.Post) {
        val player = event.player
        val data = LegendaryLimitsData.get(player.server)
        data.remove(event.pokemon.uuid)
        data.syncPlayer(player)
    }

    private fun onEvolutionComplete(event: EvolutionCompleteEvent) {
        val pokemon = event.pokemon
        val owner = pokemon.getOwnerPlayer() as? ServerPlayer
        val server = owner?.server ?: com.cobblemon.mod.common.util.server() ?: return
        val data = LegendaryLimitsData.get(server)

        if (owner != null) {
            data.syncPokemon(owner.uuid, owner.name.string, pokemon)
            data.syncPlayer(owner)
        } else {
            val ownerUuid = pokemon.getOwnerUUID() ?: return
            val existingName = server.profileCache?.get(ownerUuid)?.orElse(null)?.name
                ?: ownerUuid.toString().substring(0, 8)
            data.syncPokemon(ownerUuid, existingName, pokemon)
        }
    }

    private fun onTradePost(event: TradeEvent.Post) {
        val p1 = event.tradeParticipant1.uuid.getPlayer() as? ServerPlayer
        val p2 = event.tradeParticipant2.uuid.getPlayer() as? ServerPlayer
        val server = p1?.server ?: p2?.server ?: return
        val data = LegendaryLimitsData.get(server)
        if (p1 != null) data.syncPlayer(p1)
        if (p2 != null) data.syncPlayer(p2)
    }

    private fun onPokemonGained(event: PokemonGainedEvent) {
        val pokemon = event.pokemon
        val server = (pokemon.getOwnerPlayer() as? ServerPlayer)?.server
            ?: com.cobblemon.mod.common.util.server()
            ?: return
        val data = LegendaryLimitsData.get(server)
        val player = server.playerList.getPlayer(event.playerId)
        if (player != null) {
            data.syncPokemon(player.uuid, player.name.string, pokemon)
            data.syncPlayer(player)
        } else {
            val name = server.profileCache?.get(event.playerId)?.orElse(null)?.name
                ?: event.playerId.toString().substring(0, 8)
            data.syncPokemon(event.playerId, name, pokemon)
        }
    }

    private fun registerCommands(dispatcher: CommandDispatcher<CommandSourceStack>) {
        for (rootName in listOf("legendarylimit", "llimit")) {
            dispatcher.register(buildCommandTree(rootName))
        }
    }

    private fun buildCommandTree(rootName: String) =
        Commands.literal(rootName)
            .requires { it.hasPermission(2) }
            .then(
                Commands.literal("status")
                    .then(
                        Commands.argument("species", ResourceLocationArgument.id())
                            .suggests(speciesSuggestions)
                            .executes { ctx ->
                                val speciesId = resolveArgSpeciesId(ResourceLocationArgument.getId(ctx, "species"))
                                val status = LegendaryLimitsService.getLimitStatus(ctx.source.server, speciesId)
                                val ownersStr = if (status.owners.isEmpty()) "Ninguno" else status.owners.joinToString(", ")
                                val limitStr = if (status.tracked) "${status.currentCount}/${status.effectiveLimit}" else "Sin límite"
                                val stateStr = if (status.canCapture) "§aCAPTURABLE" else "§cBLOQUEADO"
                                ctx.source.sendSuccess({
                                    Component.literal(
                                        "§e[${status.speciesId}] §f${status.displayName}: $stateStr §7($limitStr)\n" +
                                            "§7Base: §f${if (status.tracked) status.baseLimit else "N/A"} §7| Dueño(s): §f$ownersStr\n" +
                                            "§7Detalle: §f${status.note}"
                                    )
                                }, false)
                                1
                            }
                    )
            )
            .then(
                Commands.literal("list")
                    .executes { ctx ->
                        val server = ctx.source.server
                        val data = LegendaryLimitsData.get(server)
                        val entries = data.allEntries()
                        if (entries.isEmpty()) {
                            ctx.source.sendSuccess({ Component.literal("§7No hay Pokémon registrados actualmente en Legendary Limits.") }, false)
                            return@executes 1
                        }

                        val bySpecies = entries.groupBy { it.speciesId }.toSortedMap()
                        val lines = bySpecies.entries.joinToString("\n") { (speciesId, list) ->
                            val status = LegendaryLimitsService.getLimitStatus(server, speciesId)
                            val owners = list.groupBy { it.ownerName }
                                .entries
                                .joinToString(", ") { (name, items) ->
                                    val debugTag = if (items.any { it.isDebug }) "*" else ""
                                    if (items.size > 1) "$name x${items.size}$debugTag" else "$name$debugTag"
                                }
                            val color = if (status.canCapture) "§a" else "§c"
                            val limitText = if (status.tracked) "${status.currentCount}/${status.effectiveLimit}" else "${list.size}/∞"
                            "§e• ${status.speciesId} $color($limitText) §7[$owners]"
                        }
                        ctx.source.sendSuccess({
                            Component.literal("§6Registros activos (${entries.size} total, ${bySpecies.size} especies):\n$lines")
                        }, false)
                        1
                    }
            )
            .then(
                Commands.literal("resync")
                    .executes { ctx ->
                        val stats = LegendaryLimitsData.get(ctx.source.server).resyncAllPlayers(ctx.source.server)
                        ctx.source.sendSuccess({
                            Component.literal(
                                "§aResync completado: ${stats.playersScanned} jugadores escaneados, " +
                                    "${stats.totalTracked} Pokémon registrados en ${stats.uniqueSpecies} especies."
                            )
                        }, true)
                        1
                    }
            )
            .then(
                Commands.literal("reload")
                    .executes { ctx ->
                        LegendaryLimitsConfig.load()
                        val data = LegendaryLimitsData.get(ctx.source.server)
                        val stats = data.resyncAllPlayers(ctx.source.server)
                        ctx.source.sendSuccess({
                            Component.literal(
                                "§aConfiguración de límites recargada (enabled=${LegendaryLimitsConfig.current.enabled}, " +
                                    "custom=${LegendaryLimitsConfig.current.speciesLimits.size}). " +
                                    "Registros activos: ${stats.totalTracked}."
                            )
                        }, true)
                        1
                    }
            )
            .then(
                Commands.literal("setlimit")
                    .then(
                        Commands.argument("species", ResourceLocationArgument.id())
                            .suggests(speciesSuggestions)
                            .then(
                                Commands.argument("limit", IntegerArgumentType.integer(-1))
                                    .executes { ctx ->
                                        val speciesId = resolveArgSpeciesId(ResourceLocationArgument.getId(ctx, "species"))
                                        val limit = IntegerArgumentType.getInteger(ctx, "limit")
                                        LegendaryLimitsConfig.current.speciesLimits[speciesId] = limit
                                        LegendaryLimitsConfig.save()
                                        val stats = LegendaryLimitsData.get(ctx.source.server).resyncAllPlayers(ctx.source.server)
                                        val msg = if (limit < 0) {
                                            "§aEspecie §e$speciesId §aexenta de límite ($limit). Registros activos: ${stats.totalTracked}."
                                        } else {
                                            "§aLímite de §e$speciesId §aestablecido a §f$limit§a y guardado en config. Registros activos: ${stats.totalTracked}."
                                        }
                                        ctx.source.sendSuccess({ Component.literal(msg) }, true)
                                        1
                                    }
                            )
                    )
            )
            .then(
                Commands.literal("debug")
                    .then(
                        Commands.literal("add")
                            .then(
                                Commands.argument("species", ResourceLocationArgument.id())
                                    .suggests(speciesSuggestions)
                                    .executes { ctx ->
                                        executeDebugAdd(ctx.source, ResourceLocationArgument.getId(ctx, "species"), 1)
                                    }
                                    .then(
                                        Commands.argument("count", IntegerArgumentType.integer(1, 50))
                                            .executes { ctx ->
                                                val count = IntegerArgumentType.getInteger(ctx, "count")
                                                executeDebugAdd(ctx.source, ResourceLocationArgument.getId(ctx, "species"), count)
                                            }
                                    )
                            )
                    )
                    .then(
                        Commands.literal("clear")
                            .then(
                                Commands.literal("all")
                                    .executes { ctx ->
                                        val removed = LegendaryLimitsData.get(ctx.source.server).clearAll()
                                        ctx.source.sendSuccess({
                                            Component.literal("§eSe eliminaron todos los registros ($removed). Usa /llimit resync para reconstruir desde los jugadores.")
                                        }, true)
                                        1
                                    }
                            )
                            .then(
                                Commands.argument("species", ResourceLocationArgument.id())
                                    .suggests(speciesSuggestions)
                                    .executes { ctx ->
                                        val speciesId = resolveArgSpeciesId(ResourceLocationArgument.getId(ctx, "species"))
                                        val removed = LegendaryLimitsData.get(ctx.source.server).removeBySpecies(speciesId)
                                        ctx.source.sendSuccess({
                                            Component.literal("§eSe eliminaron $removed registros de §f$speciesId§e.")
                                        }, true)
                                        1
                                    }
                            )
                    )
            )

    private fun executeDebugAdd(source: CommandSourceStack, rawId: ResourceLocation, count: Int): Int {
        val speciesId = resolveArgSpeciesId(rawId)
        val data = LegendaryLimitsData.get(source.server)
        val ownerUuid = (source.entity as? ServerPlayer)?.uuid ?: UUID(0L, 0L)
        val ownerName = "DEBUG(${source.textName})"
        repeat(count) {
            data.upsert(
                pokemonUuid = UUID.randomUUID(),
                speciesId = speciesId,
                ownerUuid = ownerUuid,
                ownerName = ownerName,
                isDebug = true
            )
        }
        val status = LegendaryLimitsService.getLimitStatus(source.server, speciesId)
        source.sendSuccess({
            Component.literal(
                "§e[DEBUG] Añadido(s) $count registro(s) sintético(s) de §f$speciesId§e. " +
                    "Estado actual: §f${status.currentCount}/${status.effectiveLimit} §7(${status.note})"
            )
        }, true)
        return 1
    }

    private fun resolveArgSpeciesId(id: ResourceLocation): String {
        return if (id.namespace == "minecraft") {
            "cobblemon:${id.path}".lowercase()
        } else {
            id.toString().lowercase()
        }
    }
}
