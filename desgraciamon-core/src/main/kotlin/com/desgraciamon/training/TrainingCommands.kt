package com.desgraciamon.training

import com.desgraciamon.badges.AdminAuth
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.network.chat.Component

object TrainingCommands {

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        val trainRoot = Commands.literal("entrenar")
            .executes { ctx ->
                val player = ctx.source.playerOrException
                val (ok, msg) = TrainingManager.startTraining(player)
                if (ok) {
                    ctx.source.sendSuccess({ Component.literal(msg) }, false)
                } else {
                    ctx.source.sendFailure(Component.literal(msg))
                }
                1
            }
            .then(
                Commands.literal("estado")
                    .executes { ctx ->
                        val stateStr = if (TrainingManager.enabled) "§aACTIVADO" else "§cDESACTIVADO"
                        val limit = TrainingManager.dailyLimit
                        val limitStr = if (limit > 0) "$limit/dia (reinicio 8:00 AM Venezuela)" else "Sin limite"
                        val player = ctx.source.player
                        val personal = if (player != null && limit > 0) {
                            val used = TrainingManager.getDailyUsed(player.uuid)
                            val rem = TrainingManager.getDailyRemaining(player.uuid)
                            " | Usados hoy: §f$used/$limit §7(restantes: $rem)"
                        } else ""
                        ctx.source.sendSuccess(
                            { Component.literal("§e[Entrenamiento] Estado: $stateStr §7| Limite: §f$limitStr$personal") },
                            false
                        )
                        1
                    }
                    .then(
                        Commands.literal("activar")
                            .requires { AdminAuth.isAdminSource(it) }
                            .executes { ctx ->
                                TrainingManager.setEnabled(true)
                                ctx.source.sendSuccess({ Component.literal("§a[Entrenamiento] Comando /entrenar ACTIVADO.") }, true)
                                1
                            }
                    )
                    .then(
                        Commands.literal("desactivar")
                            .requires { AdminAuth.isAdminSource(it) }
                            .executes { ctx ->
                                TrainingManager.setEnabled(false)
                                ctx.source.sendSuccess({ Component.literal("§c[Entrenamiento] Comando /entrenar DESACTIVADO.") }, true)
                                1
                            }
                    )
            )
            .then(
                Commands.literal("limite")
                    .requires { AdminAuth.isAdminSource(it) }
                    .then(
                        Commands.argument("cantidad", IntegerArgumentType.integer(0, 100))
                            .executes { ctx ->
                                val qty = IntegerArgumentType.getInteger(ctx, "cantidad")
                                TrainingManager.setDailyLimit(qty)
                                val desc = if (qty == 0) "sin limite" else "$qty combates diarios (reinicio 8:00 AM Venezuela)"
                                ctx.source.sendSuccess(
                                    { Component.literal("§a[Entrenamiento] Limite diario establecido en: §f$desc§a.") },
                                    true
                                )
                                1
                            }
                    )
            )
            .then(
                Commands.literal("reset")
                    .requires { AdminAuth.isAdminSource(it) }
                    .then(
                        Commands.argument("jugador", EntityArgument.player())
                            .executes { ctx ->
                                val target = EntityArgument.getPlayer(ctx, "jugador")
                                TrainingManager.resetPlayerUsage(target.uuid)
                                ctx.source.sendSuccess(
                                    { Component.literal("§a[Entrenamiento] Contador diario reiniciado para §f${target.scoreboardName}§a.") },
                                    true
                                )
                                1
                            }
                    )
            )

        dispatcher.register(trainRoot)
    }
}
