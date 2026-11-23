package com.desgraciamon.badges

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.suggestion.SuggestionProvider
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import org.slf4j.LoggerFactory

class BadgesMod : ModInitializer {

    override fun onInitialize() {
        if (initialized) return
        initialized = true

        TextCatalog.load()
        BadgeNetwork.registerCommon()
        net.fabricmc.loader.api.FabricLoader.getInstance().objectShare.put(
            "desgraciamon:has_badge",
            java.util.function.BiPredicate<java.util.UUID, String> { uuid, badgeId ->
                BadgeData.hasUnlocked(uuid, badgeId)
            }
        )

        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTING.register { server ->
            BadgeData.initForServer(server)
            GymDefeatListener.init(server)
        }
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register {
            BadgeData.save()
            BadgeData.resetServer()
        }

        ServerPlayConnectionEvents.JOIN.register { handler, _, server ->
            server.execute {
                AdminAuth.onPlayerJoin(server, handler.player)
                GymDefeatListener.onPlayerJoin(handler.player)
                BadgeNetwork.syncAll(server)
                BadgeNetwork.syncHitosPlayer(handler.player)
            }
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, server ->
            server.execute {
                AdminAuth.onPlayerDisconnect(handler.player.uuid)
                BadgeNetwork.syncAll(server)
            }
        }

        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            TextsPreviewCommand.register(dispatcher)
            SpawnCommand.register(dispatcher)
            registerCommands(dispatcher)
        }

        LOGGER.info("Desgraciamon 3 Badges inicializado correctamente con integracion de gimnasios y permisos por insignia admin.")
    }

    private fun registerCommands(dispatcher: com.mojang.brigadier.CommandDispatcher<CommandSourceStack>) {
        val badgeSuggestions = SuggestionProvider<CommandSourceStack> { _, builder ->
            SharedSuggestionProvider.suggest(TextCatalog.allBadgeIds(), builder)
        }

        val root = Commands.literal("insignias")
            .then(Commands.literal("lista").executes { ctx ->
                val ids = TextCatalog.allBadgeIds()
                ctx.source.sendSuccess({ TextCatalog.component("badge.list.header", "count" to ids.size) }, false)
                for (id in ids) {
                    val line = TextCatalog.component(
                        "badge.list.entry",
                        "badge" to TextCatalog.badgeComponent(id, withHover = true),
                        "id" to id,
                        "name" to RichText.parse(TextCatalog.badgeNameRaw(id)),
                        "description" to RichText.parse(TextCatalog.badgeDescriptionRaw(id))
                    )
                    ctx.source.sendSuccess({ line }, false)
                }
                1
            })
            .then(Commands.literal("jugador")
                .executes { ctx ->
                    val player = ctx.source.playerOrException
                    showPlayerBadges(ctx.source, player)
                    1
                }
                .then(Commands.argument("jugador", EntityArgument.player()).executes { ctx ->
                    val target = EntityArgument.getPlayer(ctx, "jugador")
                    showPlayerBadges(ctx.source, target)
                    1
                })
            )
            .then(Commands.literal("info")
                .then(Commands.argument("insignia", StringArgumentType.word())
                    .suggests(badgeSuggestions)
                    .executes { ctx ->
                        val player = ctx.source.playerOrException
                        val badgeId = StringArgumentType.getString(ctx, "insignia").lowercase()
                        showBadgeInfo(ctx.source, player, badgeId)
                        1
                    }
                    .then(Commands.argument("jugador", EntityArgument.player()).executes { ctx ->
                        val target = EntityArgument.getPlayer(ctx, "jugador")
                        val badgeId = StringArgumentType.getString(ctx, "insignia").lowercase()
                        showBadgeInfo(ctx.source, target, badgeId)
                        1
                    })
                )
            )
            .then(Commands.literal("admin")
                .requires { AdminAuth.canManageAdminBadge(it) }
                .then(Commands.literal("dar")
                    .then(Commands.argument("jugador", EntityArgument.player())
                        .then(Commands.argument("insignia", StringArgumentType.word())
                            .suggests(badgeSuggestions)
                            .executes { ctx ->
                                val target = EntityArgument.getPlayer(ctx, "jugador")
                                val badgeId = StringArgumentType.getString(ctx, "insignia").lowercase()
                                if (!TextCatalog.badgeExists(badgeId)) {
                                    ctx.source.sendFailure(TextCatalog.component("badge.unknown", "id" to badgeId))
                                    return@executes 0
                                }
                                val added = BadgeData.grant(target.uuid, badgeId)
                                if (!added) {
                                    ctx.source.sendFailure(
                                        TextCatalog.component("badge.already_owned", "player" to target.gameProfile.name, "id" to badgeId)
                                    )
                                    return@executes 0
                                }
                                if (badgeId == AdminAuth.ADMIN_BADGE_ID) {
                                    AdminAuth.refreshPermissions(ctx.source.server, target)
                                }
                                BadgeNetwork.syncAll(ctx.source.server)
                                announceUnlock(ctx.source.server, target, badgeId)
                                ctx.source.sendSuccess({
                                    TextCatalog.component(
                                        "badge.granted.admin",
                                        "badge" to TextCatalog.badgeComponent(badgeId),
                                        "name" to RichText.parse(TextCatalog.badgeNameRaw(badgeId)),
                                        "player" to target.gameProfile.name
                                    )
                                }, true)
                                1
                            }
                        )
                    )
                )
                .then(Commands.literal("quitar")
                    .then(Commands.argument("jugador", EntityArgument.player())
                        .then(Commands.argument("insignia", StringArgumentType.word())
                            .suggests(badgeSuggestions)
                            .executes { ctx ->
                                val target = EntityArgument.getPlayer(ctx, "jugador")
                                val badgeId = StringArgumentType.getString(ctx, "insignia").lowercase()
                                BadgeData.revoke(target.uuid, badgeId)
                                if (badgeId == AdminAuth.ADMIN_BADGE_ID) {
                                    AdminAuth.refreshPermissions(ctx.source.server, target)
                                }
                                BadgeNetwork.syncAll(ctx.source.server)
                                ctx.source.sendSuccess({
                                    TextCatalog.component("badge.revoked.admin", "id" to badgeId, "player" to target.gameProfile.name)
                                }, true)
                                1
                            }
                        )
                    )
                )
                .then(Commands.literal("dar_todas")
                    .executes { ctx ->
                        val p = ctx.source.playerOrException
                        for (id in TextCatalog.allBadgeIds()) BadgeData.grant(p.uuid, id)
                        AdminAuth.refreshPermissions(ctx.source.server, p)
                        BadgeNetwork.syncAll(ctx.source.server)
                        ctx.source.sendSuccess({
                            Component.literal("§a[Insignias] Se te otorgaron todas las insignias (${TextCatalog.allBadgeIds().size}).")
                        }, false)
                        1
                    }
                    .then(Commands.argument("jugador", EntityArgument.player()).executes { ctx ->
                        val target = EntityArgument.getPlayer(ctx, "jugador")
                        for (id in TextCatalog.allBadgeIds()) BadgeData.grant(target.uuid, id)
                        AdminAuth.refreshPermissions(ctx.source.server, target)
                        BadgeNetwork.syncAll(ctx.source.server)
                        ctx.source.sendSuccess({
                            Component.literal("§a[Insignias] Se otorgaron todas las insignias a ${target.gameProfile.name}.")
                        }, true)
                        1
                    })
                )
                .then(Commands.literal("limpiar")
                    .executes { ctx ->
                        val p = ctx.source.playerOrException
                        BadgeData.clear(p.uuid)
                        AdminAuth.refreshPermissions(ctx.source.server, p)
                        BadgeNetwork.syncAll(ctx.source.server)
                        ctx.source.sendSuccess({ Component.literal("§c[Insignias] Tus insignias fueron limpiadas.") }, false)
                        1
                    }
                    .then(Commands.argument("jugador", EntityArgument.player()).executes { ctx ->
                        val target = EntityArgument.getPlayer(ctx, "jugador")
                        BadgeData.clear(target.uuid)
                        AdminAuth.refreshPermissions(ctx.source.server, target)
                        BadgeNetwork.syncAll(ctx.source.server)
                        ctx.source.sendSuccess({ Component.literal("§c[Insignias] Insignias de ${target.gameProfile.name} limpiadas.") }, true)
                        1
                    })
                )
                .then(Commands.literal("recargar").executes { ctx ->
                    TextCatalog.reload()
                    BadgeData.load()
                    val (_, emojiCount) = com.desgraciamon.serverassets.ServerAssetsManager.reloadAndSync(ctx.source.server)
                    BadgeNetwork.syncAll(ctx.source.server)
                    ctx.source.sendSuccess({ Component.literal("§aCatálogo, datos de insignias y §f$emojiCount §aemojis recargados y sincronizados.") }, true)
                    1
                })
            )

        dispatcher.register(root)

        dispatcher.register(
            Commands.literal("hitos")
                .executes { ctx ->
                    val player = ctx.source.playerOrException
                    ServerPlayNetworking.send(player, OpenHitosPayload())
                    1
                }
        )
    }

    private fun showPlayerBadges(source: CommandSourceStack, target: ServerPlayer) {
        val unlocked = BadgeData.unlockedBadges(target.uuid)
        if (unlocked.isEmpty()) {
            source.sendSuccess({ TextCatalog.component("badge.player.empty", "player" to target.gameProfile.name) }, false)
            return
        }
        source.sendSuccess({
            TextCatalog.component("badge.player.header", "player" to target.gameProfile.name, "count" to unlocked.size)
        }, false)
        val pData = BadgeData.player(target.uuid)
        for (id in unlocked) {
            val rec = pData.records[id]
            val state = when {
                TextCatalog.isNonHideable(id) -> RichText.parse("<color=#FFD04E>Fija</color>")
                id in pData.hidden -> TextCatalog.component("gui.state.hidden")
                else -> TextCatalog.component("gui.state.visible")
            }

            val leaderInfo = if (rec != null && rec.gymLeader.isNotBlank()) {
                val reg = if (rec.region.isNotBlank()) " (${rec.region})" else ""
                " [Lider: ${rec.gymLeader}$reg]"
            } else ""

            val dateInfo = if (rec != null && rec.date.isNotBlank()) {
                " - ${rec.date}"
            } else ""

            val line = Component.empty()
                .append(TextCatalog.badgeComponent(id, withHover = true))
                .append(Component.literal(" "))
                .append(RichText.parse(TextCatalog.badgeNameRaw(id)))
                .append(Component.literal(leaderInfo).withStyle { it.withColor(0xFFD700) })
                .append(Component.literal(dateInfo).withStyle { it.withColor(0xAAAAAA) })
                .append(Component.literal(" ["))
                .append(state)
                .append(Component.literal("]"))
            source.sendSuccess({ line }, false)
        }
    }

    private fun showBadgeInfo(source: CommandSourceStack, target: ServerPlayer, badgeId: String) {
        if (!TextCatalog.badgeExists(badgeId)) {
            source.sendFailure(TextCatalog.component("badge.unknown", "id" to badgeId))
            return
        }
        val pData = BadgeData.player(target.uuid)
        val hasBadge = pData.unlocked.contains(badgeId)
        val rec = pData.records[badgeId]

        source.sendSuccess({
            RichText.parse("<prism=#00F2FE,#4FACFE><bold>=== Informacion de Insignia: ${TextCatalog.badgeNameRaw(badgeId)} ===</bold></prism>")
        }, false)
        source.sendSuccess({
            Component.empty()
                .append(Component.literal("Insignia: "))
                .append(TextCatalog.badgeComponent(badgeId, withHover = true))
                .append(Component.literal(" (ID: $badgeId)"))
        }, false)
        source.sendSuccess({
            Component.empty()
                .append(Component.literal("Descripcion: "))
                .append(RichText.parse(TextCatalog.badgeDescriptionRaw(badgeId)))
        }, false)
        val statusText = when {
            !hasBadge -> "Bloqueada"
            TextCatalog.isNonHideable(badgeId) -> "Desbloqueada (Fija)"
            badgeId in pData.hidden -> "Desbloqueada (Oculta)"
            else -> "Desbloqueada (Visible)"
        }
        source.sendSuccess({
            Component.literal("Jugador: ${target.gameProfile.name} - Estado: $statusText")
        }, false)
        if (rec != null) {
            if (rec.gymLeader.isNotBlank()) {
                val reg = if (rec.region.isNotBlank()) " (${rec.region})" else ""
                source.sendSuccess({ Component.literal("Lider de Gimnasio: ${rec.gymLeader}$reg") }, false)
            }
            if (rec.date.isNotBlank()) {
                source.sendSuccess({ Component.literal("Fecha de obtencion: ${rec.date}") }, false)
            }
        }
    }

    companion object {
        const val MOD_ID = "desgraciamon4_badges"
        val LOGGER = LoggerFactory.getLogger("desgraciamon4-badges")

        @Volatile
        private var initialized = false

        fun buildHitosAdminNode(): LiteralArgumentBuilder<CommandSourceStack> {
            return Commands.literal("hitos")
                .requires { AdminAuth.isAdminSource(it) }
                .executes { ctx ->
                    val player = ctx.source.playerOrException
                    if (!AdminAuth.isAdminModeActive(player)) {
                        ctx.source.sendFailure(Component.literal("§cDebes activar el modo admin primero usando §e/modoadmin§c."))
                        return@executes 0
                    }
                    BadgeNetwork.syncHitosPlayer(player)
                    ServerPlayNetworking.send(player, com.desgraciamon.announcements.OpenAdminScreenPayload("hitos"))
                    1
                }
                .then(Commands.literal("editor").executes { ctx ->
                    val player = ctx.source.playerOrException
                    if (!AdminAuth.isAdminModeActive(player)) {
                        ctx.source.sendFailure(Component.literal("§cDebes activar el modo admin primero usando §e/modoadmin§c."))
                        return@executes 0
                    }
                    BadgeNetwork.syncHitosPlayer(player)
                    ServerPlayNetworking.send(player, com.desgraciamon.announcements.OpenAdminScreenPayload("hitos"))
                    1
                })
                .then(Commands.literal("dia1")
                    .then(Commands.argument("fecha", StringArgumentType.string()).executes { ctx ->
                        val fecha = StringArgumentType.getString(ctx, "fecha")
                        val ok = HitosManager.setDia1(fecha, ctx.source.server)
                        if (ok) {
                            ctx.source.sendSuccess({ Component.literal("§aDia 1 fijado en: $fecha. Cronograma recalculado y sincronizado.") }, true)
                        } else {
                            ctx.source.sendFailure(Component.literal("§cFormato de fecha invalido. Usa YYYY-MM-DD (ej: 2026-10-31)."))
                        }
                        1
                    })
                )
                .then(Commands.literal("dia_actual")
                    .then(Commands.argument("dia", IntegerArgumentType.integer(0, 365)).executes { ctx ->
                        val dia = IntegerArgumentType.getInteger(ctx, "dia")
                        HitosManager.setManualCurrentDay(dia, ctx.source.server)
                        val msg = if (dia == 0) {
                            "§aDia actual del servidor restablecido a automatico (Dia ${HitosManager.config.currentServerDay()})."
                        } else {
                            "§aDia actual del servidor fijado manualmente en Dia $dia."
                        }
                        ctx.source.sendSuccess({ Component.literal(msg) }, true)
                        1
                    })
                )
                .then(Commands.literal("region")
                    .then(Commands.argument("region", StringArgumentType.word())
                        .suggests { _, builder ->
                            SharedSuggestionProvider.suggest(listOf("kanto", "johto", "hoenn", "sinnoh", "auto"), builder)
                        }
                        .executes { ctx ->
                            val reg = StringArgumentType.getString(ctx, "region").lowercase()
                            val ok = HitosManager.setManualActiveRegion(reg, ctx.source.server)
                            if (ok) {
                                val active = HitosConfig.regionDisplayName(HitosManager.config.activeRegion())
                                val mode = if (reg == "auto" || reg == "automatico") "automatico (actual: $active)" else active
                                ctx.source.sendSuccess({ Component.literal("§aRegion activa de Hitos cambiada a: §e$mode") }, true)
                            } else {
                                ctx.source.sendFailure(Component.literal("§cRegion invalida. Usa: kanto, johto, hoenn, sinnoh o auto."))
                            }
                            1
                        }
                    )
                )
                .then(Commands.literal("region_dia")
                    .then(Commands.argument("region", StringArgumentType.word())
                        .suggests { _, builder ->
                            SharedSuggestionProvider.suggest(listOf("kanto", "johto", "hoenn", "sinnoh"), builder)
                        }
                        .then(Commands.argument("dia", IntegerArgumentType.integer(1, 365)).executes { ctx ->
                            val reg = StringArgumentType.getString(ctx, "region").lowercase()
                            val dia = IntegerArgumentType.getInteger(ctx, "dia")
                            val ok = HitosManager.setRegionStartDay(reg, dia, ctx.source.server)
                            if (ok) {
                                val name = HitosConfig.regionDisplayName(reg)
                                ctx.source.sendSuccess({ Component.literal("§aRegion §e$name §aprogramada para iniciar en el Dia §e$dia§a (hitos desplazados y sincronizados).") }, true)
                            } else {
                                ctx.source.sendFailure(Component.literal("§cRegion invalida. Usa: kanto, johto, hoenn o sinnoh."))
                            }
                            1
                        })
                    )
                )
                .then(Commands.literal("agregar")
                    .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("dia", IntegerArgumentType.integer(1))
                            .then(Commands.argument("icono", StringArgumentType.word())
                                .then(Commands.argument("titulo", StringArgumentType.string()).executes { ctx ->
                                    val id = StringArgumentType.getString(ctx, "id")
                                    val dia = IntegerArgumentType.getInteger(ctx, "dia")
                                    val icono = StringArgumentType.getString(ctx, "icono")
                                    val titulo = StringArgumentType.getString(ctx, "titulo")
                                    HitosManager.addOrUpdateMilestone(id, dia, icono, titulo, isProgression = true, server = ctx.source.server)
                                    ctx.source.sendSuccess({ Component.literal("§aHito de progreso '$id' agregado/actualizado para el Dia $dia.") }, true)
                                    1
                                })
                            )
                        )
                    )
                )
                .then(Commands.literal("agregar_evento")
                    .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("dia", IntegerArgumentType.integer(1))
                            .then(Commands.argument("icono", StringArgumentType.word())
                                .then(Commands.argument("titulo", StringArgumentType.string()).executes { ctx ->
                                    val id = StringArgumentType.getString(ctx, "id")
                                    val dia = IntegerArgumentType.getInteger(ctx, "dia")
                                    val icono = StringArgumentType.getString(ctx, "icono")
                                    val titulo = StringArgumentType.getString(ctx, "titulo")
                                    HitosManager.addOrUpdateMilestone(id, dia, icono, titulo, isProgression = false, server = ctx.source.server)
                                    ctx.source.sendSuccess({ Component.literal("§aEvento especial '$id' agregado/actualizado para el Dia $dia.") }, true)
                                    1
                                })
                            )
                        )
                    )
                )
                .then(Commands.literal("eliminar")
                    .then(Commands.argument("id", StringArgumentType.word())
                        .suggests { _, builder -> SharedSuggestionProvider.suggest(HitosManager.config.milestones.map { it.id }, builder) }
                        .executes { ctx ->
                            val id = StringArgumentType.getString(ctx, "id")
                            val removed = HitosManager.removeMilestone(id, ctx.source.server)
                            if (removed) {
                                ctx.source.sendSuccess({ Component.literal("§aHito '$id' eliminado del cronograma.") }, true)
                            } else {
                                ctx.source.sendFailure(Component.literal("§cNo se encontro el hito con ID '$id'."))
                            }
                            1
                        }
                    )
                )
                .then(Commands.literal("listar").executes { ctx ->
                    val cfg = HitosManager.config
                    ctx.source.sendSuccess({ Component.literal("§6=== CRONOGRAMA DE HITOS (Dia 1: ${cfg.serverStartDate} | Dia actual: ${cfg.currentServerDay()}) ===") }, false)
                    for (m in cfg.milestones) {
                        val kind = if (m.isProgressMilestone()) "Progreso" else "Evento"
                        ctx.source.sendSuccess({ Component.literal("§e- [Dia ${m.dayOffset} | $kind] §f${m.title} §7(ID: ${m.id}, Recompensa: ${m.rewardSummary})") }, false)
                    }
                    1
                })
                .then(Commands.literal("roldiscord")
                    .executes { ctx ->
                        val saved = HitosManager.config.discordAdminRoleId
                        val msg = if (saved.isBlank()) {
                            "§eNo hay un rol especifico configurado en el servidor (solo Administradores)."
                        } else {
                            "§aRol autorizado de hitos en Discord: §e$saved"
                        }
                        ctx.source.sendSuccess({ Component.literal(msg) }, false)
                        1
                    }
                    .then(Commands.argument("rol_id", StringArgumentType.word()).executes { ctx ->
                        val rawRole = StringArgumentType.getString(ctx, "rol_id")
                        HitosManager.setDiscordAdminRole(rawRole, ctx.source.server)
                        val saved = HitosManager.config.discordAdminRoleId
                        if (saved.isBlank()) {
                            ctx.source.sendSuccess({ Component.literal("§aRol de hitos en Discord limpiado.") }, true)
                        } else {
                            ctx.source.sendSuccess({ Component.literal("§aRol autorizado en Discord para administrar hitos: §e$saved") }, true)
                        }
                        1
                    })
                )
        }

        fun buildBroadcastMessage(playerName: String, badgeId: String): Component {
            val gymDef = GymBadges.BY_BADGE_ID[badgeId.lowercase()]
            val extra = if (gymDef != null) " al derrotar a ${gymDef.gymLeaderName} (${gymDef.region})" else ""
            val template = TextCatalog.raw("badge.unlocked.broadcast")
            return RichText.parse(
                template,
                mapOf(
                    "player" to playerName,
                    "badge" to TextCatalog.badgeComponent(badgeId, withHover = true),
                    "name" to RichText.parse(TextCatalog.badgeNameRaw(badgeId) + extra),
                    "description" to RichText.parse(TextCatalog.badgeDescriptionRaw(badgeId)),
                    "id" to badgeId
                )
            )
        }

        fun announceUnlock(server: MinecraftServer, target: ServerPlayer, badgeId: String) {
            val gymDef = GymBadges.BY_BADGE_ID[badgeId.lowercase()]
            val extra = if (gymDef != null) " (Lider: ${gymDef.gymLeaderName} - ${gymDef.region})" else ""
            val settingsKeyId = if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("desgraciamon4_discord_bridge")) {
                "key.desgraciamon4_discord_bridge.settings"
            } else {
                "key.desgraciamon4_badges.settings"
            }
            val personal = TextCatalog.component(
                "badge.unlocked.personal",
                "badge" to TextCatalog.badgeComponent(badgeId, withHover = true),
                "name" to RichText.parse(TextCatalog.badgeNameRaw(badgeId) + extra),
                "id" to badgeId,
                "key" to Component.keybind(settingsKeyId)
            )
            target.sendSystemMessage(personal)
        }

        @JvmStatic
        fun decoratePlayerDisplayName(uuid: java.util.UUID, original: Component): Component {
            val visible = BadgeData.visibleBadges(uuid)
            if (visible.isEmpty()) return original
            val suffix = TextCatalog.formatBadgesSuffix(visible, withHover = true)
            return Component.empty().append(original).append(suffix)
        }
    }
}
