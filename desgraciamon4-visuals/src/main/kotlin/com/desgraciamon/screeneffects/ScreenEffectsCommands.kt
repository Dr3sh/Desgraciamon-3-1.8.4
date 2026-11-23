package com.desgraciamon.screeneffects

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.FloatArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer

object ScreenEffectsCommands {

    fun parseColor(colorStr: String?): Int {
        if (colorStr.isNullOrBlank()) return 0x000000
        val clean = colorStr.trim().lowercase()
        return when (clean) {
            "black", "negro", "oscuro" -> 0x000000
            "white", "blanco" -> 0xFFFFFF
            "red", "rojo" -> 0xEE2222
            "blue", "azul" -> 0x2266FF
            "cyan", "celeste", "cian" -> 0x00E5FF
            "green", "verde" -> 0x22CC55
            "yellow", "amarillo" -> 0xFFDD00
            "orange", "naranja" -> 0xFF7A00
            "purple", "morado", "magenta" -> 0xBA55D3
            "pink", "rosa" -> 0xFF69B4
            "gold", "dorado" -> 0xFFD700
            else -> {
                runCatching {
                    val hex = when {
                        clean.startsWith("#") -> clean.substring(1)
                        clean.startsWith("0x") -> clean.substring(2)
                        else -> clean
                    }
                    if (hex.length == 6) {
                        hex.toInt(16)
                    } else if (hex.length >= 8) {
                        hex.takeLast(6).toInt(16)
                    } else {
                        0x000000
                    }
                }.getOrDefault(0x000000)
            }
        }
    }

    fun parseTimeMs(str: String, defaultMs: Int = 1000): Int {
        val clean = str.trim().lowercase()
        return runCatching {
            if (clean.endsWith("ms")) {
                clean.removeSuffix("ms").toDouble().toInt().coerceAtLeast(0)
            } else if (clean.endsWith("s")) {
                (clean.removeSuffix("s").toDouble() * 1000.0).toInt().coerceAtLeast(0)
            } else {
                (clean.toDouble() * 1000.0).toInt().coerceAtLeast(0)
            }
        }.getOrDefault(defaultMs)
    }

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        val pantallaNode = Commands.literal("pantalla")
            .requires { it.hasPermission(2) }
            .then(
                Commands.literal("fade")
                    .then(
                        Commands.argument("jugadores", EntityArgument.players())
                            .then(
                                Commands.argument("color", StringArgumentType.word())
                                    .executes { ctx ->
                                        val targets = EntityArgument.getPlayers(ctx, "jugadores")
                                        val color = parseColor(StringArgumentType.getString(ctx, "color"))
                                        sendEffect(targets, "FADE", color, 1000, 2000, 1000, 1.0f)
                                        ctx.source.sendSuccess({ Component.literal("§a[Efecto] Fade activado para ${targets.size} jugador(es).") }, false)
                                        targets.size
                                    }
                                    .then(
                                        Commands.argument("tiempo_entrada", StringArgumentType.word())
                                            .executes { ctx ->
                                                val targets = EntityArgument.getPlayers(ctx, "jugadores")
                                                val color = parseColor(StringArgumentType.getString(ctx, "color"))
                                                val dur = parseTimeMs(StringArgumentType.getString(ctx, "tiempo_entrada"), 2000)
                                                sendEffect(targets, "FADE", color, 600, dur, 600, 1.0f)
                                                ctx.source.sendSuccess({ Component.literal("§a[Efecto] Fade activado (${dur}ms).") }, false)
                                                targets.size
                                            }
                                            .then(
                                                Commands.argument("duracion", StringArgumentType.word())
                                                    .executes { ctx ->
                                                        val targets = EntityArgument.getPlayers(ctx, "jugadores")
                                                        val color = parseColor(StringArgumentType.getString(ctx, "color"))
                                                        val entrada = parseTimeMs(StringArgumentType.getString(ctx, "tiempo_entrada"), 1000)
                                                        val dur = parseTimeMs(StringArgumentType.getString(ctx, "duracion"), 2000)
                                                        sendEffect(targets, "FADE", color, entrada, dur, entrada, 1.0f)
                                                        targets.size
                                                    }
                                                    .then(
                                                        Commands.argument("tiempo_salida", StringArgumentType.word())
                                                            .executes { ctx ->
                                                                val targets = EntityArgument.getPlayers(ctx, "jugadores")
                                                                val color = parseColor(StringArgumentType.getString(ctx, "color"))
                                                                val entrada = parseTimeMs(StringArgumentType.getString(ctx, "tiempo_entrada"), 1000)
                                                                val dur = parseTimeMs(StringArgumentType.getString(ctx, "duracion"), 2000)
                                                                val salida = parseTimeMs(StringArgumentType.getString(ctx, "tiempo_salida"), 1000)
                                                                sendEffect(targets, "FADE", color, entrada, dur, salida, 1.0f)
                                                                ctx.source.sendSuccess({
                                                                    Component.literal("§a[Efecto] Fade activado (In: ${entrada}ms, Hold: ${dur}ms, Out: ${salida}ms).")
                                                                }, false)
                                                                targets.size
                                                            }
                                                    )
                                            )
                                    )
                            )
                    )
            )
            .then(
                Commands.literal("limpiar")
                    .executes { ctx ->
                        val player = ctx.source.playerOrException
                        ScreenEffectPacket.send(listOf(player), "CLEAR")
                        ctx.source.sendSuccess({ Component.literal("§a[Efecto] Efectos de pantalla limpiados.") }, false)
                        1
                    }
                    .then(
                        Commands.argument("jugadores", EntityArgument.players())
                            .executes { ctx ->
                                val targets = EntityArgument.getPlayers(ctx, "jugadores")
                                ScreenEffectPacket.send(targets, "CLEAR")
                                ctx.source.sendSuccess({ Component.literal("§a[Efecto] Efectos de pantalla limpiados para ${targets.size} jugador(es).") }, false)
                                targets.size
                            }
                    )
            )

        for ((name, effType) in listOf(
            "glitch" to "GLITCH",
            "desaturacion" to "DESATURATE",
            "bloom" to "BLOOM",
            "pestaneo" to "BLINK"
        )) {
            pantallaNode.then(
                Commands.literal(name)
                    .then(
                        Commands.argument("jugadores", EntityArgument.players())
                            .executes { ctx ->
                                val targets = EntityArgument.getPlayers(ctx, "jugadores")
                                sendEffect(targets, effType, 0x000000, 800, 2500, 800, 1.0f)
                                ctx.source.sendSuccess({ Component.literal("§a[Efecto] $name activado para ${targets.size} jugador(es).") }, false)
                                targets.size
                            }
                            .then(
                                Commands.argument("tiempo_entrada", StringArgumentType.word())
                                    .executes { ctx ->
                                        val targets = EntityArgument.getPlayers(ctx, "jugadores")
                                        val dur = parseTimeMs(StringArgumentType.getString(ctx, "tiempo_entrada"), 2500)
                                        sendEffect(targets, effType, 0x000000, 600, dur, 600, 1.0f)
                                        targets.size
                                    }
                                    .then(
                                        Commands.argument("duracion", StringArgumentType.word())
                                            .executes { ctx ->
                                                val targets = EntityArgument.getPlayers(ctx, "jugadores")
                                                val entrada = parseTimeMs(StringArgumentType.getString(ctx, "tiempo_entrada"), 800)
                                                val dur = parseTimeMs(StringArgumentType.getString(ctx, "duracion"), 2000)
                                                sendEffect(targets, effType, 0x000000, entrada, dur, entrada, 1.0f)
                                                targets.size
                                            }
                                            .then(
                                                Commands.argument("tiempo_salida", StringArgumentType.word())
                                                    .executes { ctx ->
                                                        val targets = EntityArgument.getPlayers(ctx, "jugadores")
                                                        val entrada = parseTimeMs(StringArgumentType.getString(ctx, "tiempo_entrada"), 800)
                                                        val dur = parseTimeMs(StringArgumentType.getString(ctx, "duracion"), 2000)
                                                        val salida = parseTimeMs(StringArgumentType.getString(ctx, "tiempo_salida"), 800)
                                                        sendEffect(targets, effType, 0x000000, entrada, dur, salida, 1.0f)
                                                        targets.size
                                                    }
                                                    .then(
                                                        Commands.argument("intensidad", FloatArgumentType.floatArg(0.1f, 5.0f))
                                                            .executes { ctx ->
                                                                val targets = EntityArgument.getPlayers(ctx, "jugadores")
                                                                val entrada = parseTimeMs(StringArgumentType.getString(ctx, "tiempo_entrada"), 800)
                                                                val dur = parseTimeMs(StringArgumentType.getString(ctx, "duracion"), 2000)
                                                                val salida = parseTimeMs(StringArgumentType.getString(ctx, "tiempo_salida"), 800)
                                                                val intensity = FloatArgumentType.getFloat(ctx, "intensidad")
                                                                sendEffect(targets, effType, 0x000000, entrada, dur, salida, intensity)
                                                                targets.size
                                                            }
                                                    )
                                            )
                                    )
                            )
                    )
            )
        }

        dispatcher.register(
            Commands.literal("efecto")
                .requires { it.hasPermission(2) }
                .then(pantallaNode)
        )
    }

    private fun sendEffect(
        targets: Collection<ServerPlayer>,
        type: String,
        color: Int,
        fadeInMs: Int,
        durationMs: Int,
        fadeOutMs: Int,
        intensity: Float
    ) {
        val startEpochMs = System.currentTimeMillis() + 40L
        ScreenEffectPacket.send(
            players = targets,
            effectType = type,
            color = color,
            fadeInMs = fadeInMs,
            durationMs = durationMs,
            fadeOutMs = fadeOutMs,
            intensity = intensity,
            startEpochMs = startEpochMs
        )
    }
}
