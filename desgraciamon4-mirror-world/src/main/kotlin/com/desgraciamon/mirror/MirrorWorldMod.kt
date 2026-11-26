package com.desgraciamon.mirror

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import org.slf4j.LoggerFactory
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class MirrorWorldMod : ModInitializer {
    companion object {
        private val logger = LoggerFactory.getLogger("desgraciamon4-mirror")

        data class PendingInvite(
            val ownerUuid: UUID,
            val ownerName: String,
            val expiresAtMillis: Long,
        )

        data class ScheduledTransition(
            val playerUuid: UUID,
            var ticksRemaining: Int,
            val action: (ServerPlayer) -> Unit,
        )

        /** Jugador invitado -> invitación pendiente más reciente. */
        private val pendingInvites = ConcurrentHashMap<UUID, PendingInvite>()
        /** Transiciones con Black Fade en curso (1 por jugador). */
        private val scheduledTransitions = ConcurrentHashMap<UUID, ScheduledTransition>()

        /**
         * Inicia un Black Fade consistente (~2.05s total) y ejecuta [onBlackScreen]
         * justo cuando la pantalla alcanza el 100% del velo.
         */
        fun startBlackFadeTransition(player: ServerPlayer, onBlackScreen: (ServerPlayer) -> Unit) {
            val totalMs = (MirrorSettings.config.fadeTotalSeconds.coerceIn(1.6, 2.5) * 1000.0).toInt()
            val holdMs = 250
            val fadeInMs = ((totalMs - holdMs) / 2).coerceAtLeast(650)
            val fadeOutMs = ((totalMs - holdMs) - fadeInMs).coerceAtLeast(650)

            MirrorPackets.send(player, fadeInMs = fadeInMs, holdMs = holdMs, fadeOutMs = fadeOutMs)

            val ticksUntilBlack = ((fadeInMs + holdMs / 2) / 50).coerceAtLeast(1)
            scheduledTransitions[player.uuid] = ScheduledTransition(
                playerUuid = player.uuid,
                ticksRemaining = ticksUntilBlack,
                action = onBlackScreen,
            )
        }
    }

    override fun onInitialize() {
        // Registrar tipo de función de densidad para confinar dimension_recuerdos a parcela de 300x300
        net.minecraft.core.Registry.register(
            net.minecraft.core.registries.BuiltInRegistries.DENSITY_FUNCTION_TYPE,
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MirrorRules.MOD_ID, "parcel_barrier"),
            ParcelBarrierDensityFunction.MAP_CODEC
        )

        MirrorSettings.load()
        PlayerLinksConfig.load()
        MirrorPackets.registerCommon()
        seedExampleMemoriesIfEmpty()
        MirrorRules.registerEvents()

        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            PlotManager.load(server)
            MemoryLoader.clearCache()

            // Delimitar la parcela de la Dimensión de los Recuerdos a 500x500 con un borde limpio
            val recuerdosLevel = server.getLevel(MirrorRules.RECUERDOS_DIMENSION_KEY)
            if (recuerdosLevel != null) {
                recuerdosLevel.setSpawnSettings(false, false)
                recuerdosLevel.worldBorder.setCenter(0.0, 0.0)
                recuerdosLevel.worldBorder.size = 500.0
            }
        }

        ServerLifecycleEvents.SERVER_STOPPING.register { server ->
            scheduledTransitions.clear()
            MirrorRules.clearAllEntities(server)
            PlotManager.save()
        }

        ServerTickEvents.END_SERVER_TICK.register { server ->
            if (scheduledTransitions.isNotEmpty()) {
                for ((uuid, transition) in scheduledTransitions.entries.toList()) {
                    transition.ticksRemaining--
                    if (transition.ticksRemaining <= 0) {
                        scheduledTransitions.remove(uuid)
                        val player = server.playerList.getPlayer(uuid)
                        if (player != null) {
                            runCatching { transition.action(player) }
                                .onFailure { logger.error("Error ejecutando transición del Mundo Espejo para ${player.scoreboardName}", it) }
                        }
                    }
                }
            }
        }

        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            registerCommands(dispatcher)
        }

        logger.info("Desgraciamon 3 Mundo Espejo inicializado.")
    }

    /**
     * Si las carpetas de `Desgraciamon 1` o `Desgraciamon 2` están vacías y existen datos en
     * `C:\Users\Administrator\Documents\teams ejemplos`, los copia automáticamente.
     */
    private fun seedExampleMemoriesIfEmpty() {
        runCatching {
            val exampleDir = File("C:/Users/Administrator/Documents/teams ejemplos")
            if (!exampleDir.isDirectory) return

            for (source in MirrorSettings.config.sources) {
                val targetRoot = File(source.path)
                val hasDat = targetRoot.walkTopDown().any { it.isFile && it.extension == "dat" }
                if (hasDat) continue

                val folderCandidate = when {
                    source.label.contains("1") && File(exampleDir, "desgraciamon1").isDirectory -> File(exampleDir, "desgraciamon1")
                    source.label.contains("2") && File(exampleDir, "desgraciamon2").isDirectory -> File(exampleDir, "desgraciamon2")
                    else -> exampleDir
                }

                for (sub in listOf("playerpartystore", "pcstore")) {
                    val srcSub = File(folderCandidate, sub)
                    if (srcSub.isDirectory) {
                        srcSub.copyRecursively(File(targetRoot, sub), overwrite = true)
                    }
                }
                logger.info("Datos de ${source.label} importados desde ${folderCandidate.path} a ${targetRoot.path}")
            }
        }.onFailure {
            logger.warn("No se pudieron copiar los datos de ejemplo automáticamente", it)
        }
    }

    private fun registerCommands(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("recuerdos").executes { ctx ->
                teleportToRecuerdosDimension(ctx.source.playerOrException)
                1
            }
        )
        dispatcher.register(
            Commands.literal("dimension_recuerdos").executes { ctx ->
                teleportToRecuerdosDimension(ctx.source.playerOrException)
                1
            }
        )
        dispatcher.register(
            Commands.literal("espejo")
                .executes { ctx ->
                    enterOwnMirror(ctx.source.playerOrException)
                    1
                }
                .then(Commands.literal("entrar").executes { ctx ->
                    enterOwnMirror(ctx.source.playerOrException)
                    1
                })
                .then(Commands.literal("salir").executes { ctx ->
                    val player = ctx.source.playerOrException
                    exitMirror(player)
                    1
                })
                .then(Commands.literal("dimension").executes { ctx ->
                    teleportToRecuerdosDimension(ctx.source.playerOrException)
                    1
                })
                .then(Commands.literal("invitar")
                    .then(Commands.argument("jugador", EntityArgument.player()).executes { ctx ->
                        val owner = ctx.source.playerOrException
                        val target = EntityArgument.getPlayer(ctx, "jugador")
                        invitePlayer(owner, target)
                        1
                    })
                )
                .then(Commands.literal("aceptar")
                    .executes { ctx ->
                        acceptInvite(ctx.source.playerOrException, null)
                        1
                    }
                    .then(Commands.argument("dueno", StringArgumentType.word()).executes { ctx ->
                        acceptInvite(ctx.source.playerOrException, StringArgumentType.getString(ctx, "dueno"))
                        1
                    })
                )
                .then(Commands.literal("rechazar").executes { ctx ->
                    declineInvite(ctx.source.playerOrException)
                    1
                })
                .then(Commands.literal("expulsar")
                    .then(Commands.argument("jugador", EntityArgument.player()).executes { ctx ->
                        val owner = ctx.source.playerOrException
                        val target = EntityArgument.getPlayer(ctx, "jugador")
                        kickVisitor(owner, target)
                        1
                    })
                )
                .then(Commands.literal("lista").executes { ctx ->
                    val player = ctx.source.playerOrException
                    showMemoriesList(ctx.source, player.uuid, player.scoreboardName)
                    1
                })
                .then(Commands.literal("admin")
                    .requires { it.hasPermission(2) }
                    .then(Commands.literal("enviar")
                        .then(Commands.argument("jugadores", EntityArgument.players()).executes { ctx ->
                            val targets = EntityArgument.getPlayers(ctx, "jugadores")
                            for (target in targets) {
                                teleportToOwnerMirrorWithFade(target, target.uuid, target.scoreboardName, isOwner = true)
                            }
                            ctx.source.sendSuccess({ Component.literal("§6[Mundo Espejo] §aEnviando a ${targets.size} jugador(es) a su parcela de recuerdos.") }, true)
                            targets.size
                        })
                    )
                    .then(Commands.literal("enviar_dimension")
                        .then(Commands.argument("jugadores", EntityArgument.players()).executes { ctx ->
                            val targets = EntityArgument.getPlayers(ctx, "jugadores")
                            for (target in targets) {
                                teleportToRecuerdosDimension(target)
                            }
                            ctx.source.sendSuccess({ Component.literal("§6[Recuerdos] §aEnviando a ${targets.size} jugador(es) a la Dimension de los Recuerdos.") }, true)
                            targets.size
                        })
                    )
                    .then(Commands.literal("enviar_todos_dimension").executes { ctx ->
                        val allPlayers = ctx.source.server.playerList.players
                        for (target in allPlayers) {
                            teleportToRecuerdosDimension(target)
                        }
                        ctx.source.sendSuccess({ Component.literal("§6[Recuerdos] §aEnviando a todos los jugadores (${allPlayers.size}) a la Dimension de los Recuerdos.") }, true)
                        allPlayers.size
                    })
                    .then(Commands.literal("visitar")
                        .then(Commands.argument("jugador", EntityArgument.player()).executes { ctx ->
                            val admin = ctx.source.playerOrException
                            val target = EntityArgument.getPlayer(ctx, "jugador")
                            teleportToOwnerMirrorWithFade(admin, target.uuid, target.scoreboardName, isOwner = admin.uuid == target.uuid)
                            1
                        })
                    )
                    .then(Commands.literal("regenerar")
                        .executes { ctx ->
                            val player = ctx.source.playerOrException
                            regeneratePlot(player, player.uuid, player.scoreboardName)
                            1
                        }
                        .then(Commands.argument("jugador", EntityArgument.player()).executes { ctx ->
                            val admin = ctx.source.playerOrException
                            val target = EntityArgument.getPlayer(ctx, "jugador")
                            regeneratePlot(admin, target.uuid, target.scoreboardName)
                            1
                        })
                    )
                    .then(Commands.literal("recargar").executes { ctx ->
                        MirrorSettings.load()
                        PlayerLinksConfig.load()
                        seedExampleMemoriesIfEmpty()
                        MemoryLoader.clearCache()
                        val mirrorLevel = ctx.source.server.getLevel(MirrorRules.DIMENSION_KEY)
                        if (mirrorLevel != null) {
                            MirrorRules.clearAllEntities(ctx.source.server)
                            for (p in mirrorLevel.players()) {
                                val plot = PlotManager.plotAt(p.x)
                                val owner = PlotManager.ownerOf(plot) ?: continue
                                MirrorRules.ensurePlotReady(mirrorLevel, owner, false)
                            }
                        }
                        ctx.source.sendSuccess({ MirrorSettings.text("admin.reloaded") }, true)
                        1
                    })
                    .then(Commands.literal("respawn").executes { ctx ->
                        val player = ctx.source.playerOrException
                        val level = ctx.source.server.getLevel(MirrorRules.DIMENSION_KEY) ?: return@executes 0
                        MemoryLoader.clearCache()
                        MirrorRules.respawnPlotMemories(level, player.uuid)
                        player.sendSystemMessage(MirrorSettings.text("admin.reloaded"))
                        1
                    })
                )
        )
    }

    private fun teleportToRecuerdosDimension(player: ServerPlayer) {
        val server = player.server
        val recuerdosLevel = server.getLevel(MirrorRules.RECUERDOS_DIMENSION_KEY)
        if (recuerdosLevel == null) {
            player.sendSystemMessage(Component.literal("§c[!] No se pudo cargar la dimensión dimension_recuerdos."))
            return
        }

        recuerdosLevel.setSpawnSettings(false, false)
        recuerdosLevel.worldBorder.setCenter(0.0, 0.0)
        recuerdosLevel.worldBorder.size = 500.0

        if (MirrorRules.isRecuerdos(player.serverLevel())) {
            exitRecuerdosDimension(player)
            return
        }

        PlotManager.saveReturnPoint(player)
        player.sendSystemMessage(MirrorSettings.text("recuerdos.entering"))

        startBlackFadeTransition(player) { p ->
            val surfaceY = recuerdosLevel.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, 0, 0).toDouble().coerceAtLeast(70.0) + 1.0
            p.teleportTo(recuerdosLevel, 0.5, surfaceY, 0.5, 0f, 0f)
            p.sendSystemMessage(MirrorSettings.text("recuerdos.arrived"))
        }
    }

    private fun exitRecuerdosDimension(player: ServerPlayer) {
        if (!MirrorRules.isRecuerdos(player.serverLevel())) {
            player.sendSystemMessage(MirrorSettings.text("recuerdos.not_inside"))
            return
        }
        startBlackFadeTransition(player) { p ->
            PlotManager.sendBack(p)
            p.sendSystemMessage(MirrorSettings.text("recuerdos.exited"))
        }
    }

    private fun enterOwnMirror(player: ServerPlayer) {
        val discordId = DiscordBridgeHook.getDiscordId(player.server, player.uuid)
        if (discordId.isNullOrBlank()) {
            player.sendSystemMessage(MirrorSettings.text("enter.discord_required"))
            return
        }
        teleportToOwnerMirrorWithFade(player, player.uuid, player.scoreboardName, isOwner = true)
    }

    private fun teleportToOwnerMirrorWithFade(
        traveler: ServerPlayer,
        ownerUuid: UUID,
        ownerName: String,
        isOwner: Boolean,
    ) {
        // Guardar posición de retorno antes de iniciar la transición (si viene desde fuera del Mundo Espejo)
        PlotManager.saveReturnPoint(traveler)
        traveler.sendSystemMessage(MirrorSettings.text("enter.generating"))

        startBlackFadeTransition(traveler) { currentTraveler ->
            val server: MinecraftServer = currentTraveler.server
            val mirrorLevel = server.getLevel(MirrorRules.DIMENSION_KEY) ?: return@startBlackFadeTransition
            val memories = MemoryLoader.get(ownerUuid, server)

            val index = if (isOwner) PlotManager.nextThemeIndex(ownerUuid) else PlotManager.currentPlotFor(ownerUuid)
            MirrorRules.ensurePlotReady(mirrorLevel, ownerUuid, forceRegenerate = false, explicitIndex = index)
            val spawn = TerrainGenerator.spawnPoint(mirrorLevel, index)
            currentTraveler.teleportTo(mirrorLevel, spawn.x, spawn.y, spawn.z, 0f, 0f)
            MirrorRules.syncPlotOriginsToPlayer(currentTraveler, index)

            val theme = TerrainGenerator.themeFor(index)

            if (isOwner) {
                if (memories.selected.isEmpty()) {
                    currentTraveler.sendSystemMessage(MirrorSettings.text("enter.no_memories"))
                } else {
                    currentTraveler.sendSystemMessage(
                        MirrorSettings.text(
                            "enter.welcome", "player" to currentTraveler.scoreboardName,
                            "count" to memories.selected.size,
                        )
                    )
                }
                currentTraveler.sendSystemMessage(MirrorSettings.text("enter.hint"))
            } else {
                currentTraveler.sendSystemMessage(MirrorSettings.text("enter.visit", "owner" to ownerName))
                currentTraveler.sendSystemMessage(MirrorSettings.text("enter.hint"))
                server.playerList.getPlayer(ownerUuid)?.sendSystemMessage(
                    MirrorSettings.text("enter.owner_notice", "player" to currentTraveler.scoreboardName)
                )
            }
        }
    }

    private fun exitMirror(player: ServerPlayer) {
        if (!MirrorRules.isMirror(player.serverLevel())) {
            player.sendSystemMessage(MirrorSettings.text("exit.not_inside"))
            return
        }
        val myPlot = PlotManager.indexOf(player.uuid)
        val currentPlot = PlotManager.plotAt(player.x)
        val mirrorLevel = player.serverLevel()

        // Si el dueño sale de su propia parcela, iniciar también el fade para sus visitantes
        val visitors = if (currentPlot == myPlot) {
            mirrorLevel.players().filter { it.uuid != player.uuid && PlotManager.plotAt(it.x) == myPlot }
        } else emptyList()

        for (visitor in visitors) {
            startBlackFadeTransition(visitor) { v ->
                PlotManager.sendBack(v)
                v.sendSystemMessage(MirrorSettings.text("kick.kicked", "owner" to player.scoreboardName))
            }
        }

        startBlackFadeTransition(player) { p ->
            PlotManager.sendBack(p)
            p.sendSystemMessage(MirrorSettings.text("exit.done"))
            if (currentPlot == myPlot) {
                MirrorRules.clearPlotEntities(mirrorLevel, myPlot)
            }
        }
    }

    private fun invitePlayer(owner: ServerPlayer, target: ServerPlayer) {
        if (owner.uuid == target.uuid) {
            owner.sendSystemMessage(MirrorSettings.text("invite.self"))
            return
        }
        val inOwnMirror = MirrorRules.isMirror(owner.serverLevel()) &&
            PlotManager.plotAt(owner.x) == PlotManager.indexOf(owner.uuid)
        if (!inOwnMirror) {
            owner.sendSystemMessage(MirrorSettings.text("invite.not_inside"))
            return
        }

        val expireMs = MirrorSettings.config.inviteExpireSeconds.coerceAtLeast(15) * 1000L
        pendingInvites[target.uuid] = PendingInvite(
            ownerUuid = owner.uuid,
            ownerName = owner.scoreboardName,
            expiresAtMillis = System.currentTimeMillis() + expireMs,
        )

        val acceptBtn = MirrorSettings.text("invite.accept_button").withStyle { style ->
            style.withClickEvent(ClickEvent(ClickEvent.Action.RUN_COMMAND, "/espejo aceptar ${owner.scoreboardName}"))
                .withHoverEvent(HoverEvent(HoverEvent.Action.SHOW_TEXT, MirrorSettings.text("invite.accept_hover", "owner" to owner.scoreboardName)))
        }
        val declineBtn = MirrorSettings.text("invite.decline_button").withStyle { style ->
            style.withClickEvent(ClickEvent(ClickEvent.Action.RUN_COMMAND, "/espejo rechazar"))
        }

        owner.sendSystemMessage(MirrorSettings.text("invite.sent", "target" to target.scoreboardName))
        target.sendSystemMessage(
            MirrorSettings.text(
                "invite.received", "owner" to owner.scoreboardName,
                "accept" to acceptBtn,
                "decline" to declineBtn,
            )
        )
    }

    private fun acceptInvite(player: ServerPlayer, ownerFilter: String?) {
        val invite = pendingInvites[player.uuid]
        val now = System.currentTimeMillis()
        if (invite == null || now > invite.expiresAtMillis || (ownerFilter != null && !invite.ownerName.equals(ownerFilter, ignoreCase = true))) {
            pendingInvites.remove(player.uuid)
            player.sendSystemMessage(MirrorSettings.text("invite.none"))
            return
        }
        pendingInvites.remove(player.uuid)
        player.server.playerList.getPlayer(invite.ownerUuid)?.sendSystemMessage(
            MirrorSettings.text("invite.accepted_owner", "target" to player.scoreboardName)
        )
        teleportToOwnerMirrorWithFade(player, invite.ownerUuid, invite.ownerName, isOwner = false)
    }

    private fun declineInvite(player: ServerPlayer) {
        val invite = pendingInvites.remove(player.uuid)
        if (invite == null || System.currentTimeMillis() > invite.expiresAtMillis) {
            player.sendSystemMessage(MirrorSettings.text("invite.none"))
            return
        }
        player.sendSystemMessage(MirrorSettings.text("invite.declined_target", "owner" to invite.ownerName))
        player.server.playerList.getPlayer(invite.ownerUuid)?.sendSystemMessage(
            MirrorSettings.text("invite.declined_owner", "target" to player.scoreboardName)
        )
    }

    private fun kickVisitor(owner: ServerPlayer, target: ServerPlayer) {
        val myPlot = PlotManager.indexOf(owner.uuid)
        if (!MirrorRules.isMirror(target.serverLevel()) || PlotManager.plotAt(target.x) != myPlot || target.uuid == owner.uuid) {
            owner.sendSystemMessage(MirrorSettings.text("kick.not_here", "target" to target.scoreboardName))
            return
        }
        owner.sendSystemMessage(MirrorSettings.text("kick.done", "target" to target.scoreboardName))
        startBlackFadeTransition(target) { t ->
            PlotManager.sendBack(t)
            t.sendSystemMessage(MirrorSettings.text("kick.kicked", "owner" to owner.scoreboardName))
        }
    }

    private fun regeneratePlot(caller: ServerPlayer, ownerUuid: UUID, ownerName: String) {
        val mirrorLevel = caller.server.getLevel(MirrorRules.DIMENSION_KEY) ?: return
        val oldIndex = PlotManager.indexOf(ownerUuid)
        MirrorRules.clearPlotEntities(mirrorLevel, oldIndex)

        caller.sendSystemMessage(MirrorSettings.text("enter.generating"))
        startBlackFadeTransition(caller) { c ->
            val newIndex = MirrorRules.ensurePlotReady(mirrorLevel, ownerUuid, forceRegenerate = true)
            if (MirrorRules.isMirror(c.serverLevel())) {
                val spawn = TerrainGenerator.spawnPoint(mirrorLevel, newIndex)
                c.teleportTo(mirrorLevel, spawn.x, spawn.y, spawn.z, 0f, 0f)
            }
            c.sendSystemMessage(MirrorSettings.text("admin.regenerated", "player" to ownerName))
        }
    }

    private fun showMemoriesList(source: CommandSourceStack, ownerUuid: UUID, ownerName: String) {
        val memories = MemoryLoader.get(ownerUuid, source.server)
        source.sendSuccess(
            {
                MirrorSettings.text(
                    "admin.list_header", "player" to ownerName,
                    "shown" to memories.selected.size,
                    "total" to memories.all.size,
                )
            },
            false
        )
        for (entry in memories.selected) {
            val speciesShort = entry.speciesId.substringAfter(':').replaceFirstChar { it.uppercase() }
            val displayName = entry.nickname.ifBlank { speciesShort }
            val shinyTag = if (entry.shiny) MirrorSettings.text("memory.shiny_tag") else Component.empty()
            val legendaryTag = if (entry.legendary) MirrorSettings.text("memory.legendary_tag") else Component.empty()
            source.sendSuccess(
                {
                    MirrorSettings.text(
                        "admin.list_entry", "name" to displayName,
                        "species" to speciesShort,
                        "level" to entry.level,
                        "shiny" to shinyTag,
                        "legendary" to legendaryTag,
                        "season" to entry.season,
                        "origin" to entry.origin,
                    )
                },
                false
            )
        }
    }
}
