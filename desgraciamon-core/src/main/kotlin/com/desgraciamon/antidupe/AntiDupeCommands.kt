package com.desgraciamon.antidupe

import com.desgraciamon.badges.AdminAuth
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.network.chat.Component

object AntiDupeCommands {
    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        val invseeCmd = Commands.literal("invsee")
            .requires { AdminAuth.isAdminSource(it) }
            .then(
                Commands.argument("jugador", EntityArgument.player())
                    .executes { ctx ->
                        val admin = ctx.source.playerOrException
                        val target = EntityArgument.getPlayer(ctx, "jugador")
                        ContainerInspector.openPlayerInventory(admin, target)
                        1
                    }
                    .then(
                        Commands.literal("enderchest")
                            .executes { ctx ->
                                val admin = ctx.source.playerOrException
                                val target = EntityArgument.getPlayer(ctx, "jugador")
                                ContainerInspector.openEnderChest(admin, target)
                                1
                            }
                    )
                    .then(
                        Commands.literal("mochilas")
                            .executes { ctx ->
                                val admin = ctx.source.playerOrException
                                val target = EntityArgument.getPlayer(ctx, "jugador")
                                ContainerInspector.openContainersBrowser(admin, target, 0)
                                1
                            }
                    )
            )

        val antiDupeCmd = Commands.literal("antidupe")
            .requires { AdminAuth.isAdminSource(it) }
            .then(
                Commands.literal("escanear")
                    .executes { ctx ->
                        val count = AntiDupeEngine.runGlobalScan(ctx.source.server)
                        ctx.source.sendSuccess({
                            Component.literal("§a[Anti-Dupe] Escaneo global completado. Hallazgos en este ciclo: §e$count")
                        }, false)
                        1
                    }
            )
            .then(
                Commands.literal("revisar")
                    .then(
                        Commands.argument("jugador", EntityArgument.player())
                            .executes { ctx ->
                                val target = EntityArgument.getPlayer(ctx, "jugador")
                                val count = AntiDupeEngine.scanSinglePlayer(target)
                                val containers = ContainerInspector.collectAllContainers(target)
                                ctx.source.sendSuccess({
                                    Component.literal(
                                        "§6[Anti-Dupe] Revisión de §e${target.scoreboardName}§6:\n" +
                                        "§7Contenedores detectados (mochilas/shulkers): §b${containers.size}\n" +
                                        "§7Alertas disparadas en escaneo actual: §e$count"
                                    )
                                }, false)
                                1
                            }
                    )
            )
            .then(
                Commands.literal("historial")
                    .executes { ctx -> sendHistory(ctx.source, 10) }
                    .then(
                        Commands.argument("cantidad", IntegerArgumentType.integer(1, 50))
                            .executes { ctx ->
                                val limit = IntegerArgumentType.getInteger(ctx, "cantidad")
                                sendHistory(ctx.source, limit)
                            }
                    )
            )
            .then(
                Commands.literal("estado")
                    .executes { ctx ->
                        val cfg = AntiDupeConfigStore.config
                        val webhookStatus = if (cfg.discordWebhookUrl.isBlank()) "§cSin configurar" else "§aConfigurado"
                        ctx.source.sendSuccess({
                            Component.literal(
                                "§6§l[ANTI-DUPE - ESTADO]\n" +
                                "§7Activo: ${if (cfg.enabled) "§aSí" else "§cNo"}\n" +
                                "§7Eliminar dupes confirmados (UUID): ${if (cfg.removeConfirmedUuidDuplicates) "§aSí" else "§eSolo alerta"}\n" +
                                "§7Discord Webhook: $webhookStatus\n" +
                                "§7Intervalo escaneo: §e${cfg.scanIntervalSeconds}s §7| Ventana picos: §e${cfg.spikeWindowSeconds}s"
                            )
                        }, false)
                        1
                    }
            )
            .then(
                Commands.literal("recargar")
                    .executes { ctx ->
                        AntiDupeConfigStore.load()
                        ctx.source.sendSuccess({ Component.literal("§a[Anti-Dupe] Configuración recargada desde config.json.") }, true)
                        1
                    }
            )

        dispatcher.register(invseeCmd)
        dispatcher.register(antiDupeCmd)
    }

    private fun sendHistory(source: CommandSourceStack, limit: Int): Int {
        val list = AntiDupeEngine.getRecentAlerts(limit)
        if (list.isEmpty()) {
            source.sendSuccess({ Component.literal("§a[Anti-Dupe] No hay alertas recientes registradas en esta sesión.") }, false)
            return 1
        }
        val sb = StringBuilder("§6§l[ANTI-DUPE - ÚLTIMAS ${list.size} ALERTAS]\n")
        for (a in list) {
            val tag = if (a.severity == "DUPE CONFIRMADO") "§c" else "§e"
            sb.append("§8[${a.timestampVet}] $tag[${a.severity}] §f${a.playerName}: §7${a.summary}\n")
        }
        source.sendSuccess({ Component.literal(sb.toString().trimEnd()) }, false)
        return 1
    }
}
