package com.desgraciamon.vortex.command

import com.desgraciamon.vortex.config.VortexConfig
import com.desgraciamon.vortex.server.VortexManager
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component

object VortexCommand {
    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        fun resolveEffectName(raw: String): String {
            return when (raw.lowercase()) {
                "vortex", "vortice" -> "vortex"
                "goosebumps", "travis", "astronomical", "neon", "psychedelic" -> "goosebumps"
                "star", "startrails", "startrail", "stars" -> "startrails"
                else -> "startrails"
            }
        }

        fun getEffectDisplay(effect: String): String {
            return when (effect) {
                "vortex" -> "Vórtice Dimensional"
                "goosebumps" -> "Goosebumps (Travis Scott - Astronomical)"
                else -> "Star Trails"
            }
        }

        fun executeStart(ctx: com.mojang.brigadier.context.CommandContext<CommandSourceStack>, effectType: String, noFly: Boolean): Int {
            val resolvedEffect = resolveEffectName(effectType)
            val pos = VortexManager.startVortex(ctx.source.server, noFly = noFly, effectType = resolvedEffect)
            val flyLabel = if (noFly) "No-Fly ACTIVO" else "Vuelo permitido"
            val display = getEffectDisplay(resolvedEffect)
            val isLegendary = VortexManager.isLegendaryEvent(resolvedEffect)
            val coordInfo = if (isLegendary) "" else " en [${pos.x}, ${pos.y}, ${pos.z}]"
            ctx.source.sendSuccess(
                {
                    Component.literal("Efecto de cielo $display iniciado$coordInfo ($flyLabel).")
                        .withStyle(ChatFormatting.AQUA)
                },
                true
            )
            return 1
        }

        fun buildEffectStartNode(forcedEffect: String?) = Commands.literal("iniciar")
            .executes { ctx ->
                executeStart(ctx, forcedEffect ?: "startrails", noFly = false)
            }
            .then(
                if (forcedEffect != null) {
                    Commands.argument("mode", StringArgumentType.word())
                        .suggests { _, b -> b.suggest("nofly").suggest("normal").buildFuture() }
                        .executes { ctx ->
                            val arg = StringArgumentType.getString(ctx, "mode").lowercase()
                            val noFly = arg == "nofly" || arg == "true"
                            executeStart(ctx, forcedEffect, noFly = noFly)
                        }
                } else {
                    Commands.argument("effect", StringArgumentType.word())
                        .suggests { _, b ->
                            b.suggest("vortex")
                                .suggest("goosebumps")
                                .suggest("travis")
                                .suggest("star")
                                .buildFuture()
                        }
                        .executes { ctx ->
                            val effectArg = StringArgumentType.getString(ctx, "effect")
                            if (effectArg.lowercase() in listOf("nofly", "normal")) {
                                executeStart(ctx, "startrails", noFly = effectArg.lowercase() == "nofly")
                            } else {
                                executeStart(ctx, effectArg, noFly = false)
                            }
                        }
                        .then(
                            Commands.argument("mode", StringArgumentType.word())
                                .suggests { _, b -> b.suggest("nofly").suggest("normal").buildFuture() }
                                .executes { ctx ->
                                    val effectArg = StringArgumentType.getString(ctx, "effect")
                                    val modeArg = StringArgumentType.getString(ctx, "mode").lowercase()
                                    val noFly = modeArg == "nofly" || modeArg == "true"
                                    executeStart(ctx, effectArg, noFly = noFly)
                                }
                        )
                }
            )

        fun executeStop(ctx: com.mojang.brigadier.context.CommandContext<CommandSourceStack>, force: Boolean): Int {
            if (!VortexManager.isVortexActive) {
                ctx.source.sendFailure(Component.literal("No hay ningún evento activo en este momento."))
                return 0
            }
            val effect = VortexManager.currentEffectType
            val display = getEffectDisplay(effect)
            val msgSuffix = if (force) " inmediatamente (forcestop)" else ""
            VortexManager.stopVortex(
                ctx.source.server,
                Component.literal("§7El evento §e$display §7ha sido detenido$msgSuffix por un administrador."),
                force = force
            )
            ctx.source.sendSuccess(
                { Component.literal("Evento $display detenido$msgSuffix y limpiado.").withStyle(ChatFormatting.GREEN) },
                true
            )
            return 1
        }

        fun buildStopNode() = Commands.literal("detener")
            .executes { ctx -> executeStop(ctx, force = false) }
            .then(
                Commands.argument("option", StringArgumentType.word())
                    .suggests { _, b -> b.suggest("forcestop").suggest("force").suggest("normal").buildFuture() }
                    .executes { ctx ->
                        val opt = StringArgumentType.getString(ctx, "option").lowercase()
                        val force = opt in listOf("forcestop", "force", "instant", "inmediato", "true")
                        executeStop(ctx, force = force)
                    }
            )

        fun buildForceStopNode() = Commands.literal("forzardetener")
            .executes { ctx -> executeStop(ctx, force = true) }

        fun buildReloadNode() = Commands.literal("recargar")
            .executes { ctx ->
                val cfg = VortexConfig.load()
                ctx.source.sendSuccess(
                    {
                        Component.literal(
                            "Configuración recargada (Duración: ${cfg.durationSeconds}s, Recompensa: $${cfg.blackMarketReward})."
                        ).withStyle(ChatFormatting.GREEN)
                    },
                    true
                )
                1
            }

        val cieloNode = Commands.literal("cielo")
            .requires { it.hasPermission(2) }
            .then(buildStopNode())
            .then(buildForceStopNode())
            .then(buildReloadNode())
            .then(
                Commands.argument("efecto", StringArgumentType.word())
                    .suggests { _, b ->
                        listOf("vortex", "goosebumps", "star").forEach { b.suggest(it) }
                        b.buildFuture()
                    }
                    .executes { ctx ->
                        val effectArg = StringArgumentType.getString(ctx, "efecto").lowercase()
                        executeStart(ctx, effectArg, noFly = false)
                    }
                    .then(
                        Commands.argument("modo", StringArgumentType.word())
                            .suggests { _, b -> b.suggest("normal").suggest("sin_vuelo").buildFuture() }
                            .executes { ctx ->
                                val effectArg = StringArgumentType.getString(ctx, "efecto").lowercase()
                                val modeArg = StringArgumentType.getString(ctx, "modo").lowercase()
                                val noFly = modeArg == "sin_vuelo" || modeArg == "nofly" || modeArg == "true"
                                executeStart(ctx, effectArg, noFly = noFly)
                            }
                    )
            )

        dispatcher.register(
            Commands.literal("efecto")
                .requires { it.hasPermission(2) }
                .then(cieloNode)
        )
    }
}

