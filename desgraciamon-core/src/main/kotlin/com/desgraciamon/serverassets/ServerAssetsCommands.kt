package com.desgraciamon.serverassets

import com.mojang.brigadier.arguments.FloatArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.suggestion.SuggestionProvider
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.network.chat.Component

object ServerAssetsCommands {
    private val soundSuggestions = SuggestionProvider<CommandSourceStack> { _, builder ->
        SharedSuggestionProvider.suggest(ServerAssetsManager.listSounds().map { it.id }, builder)
    }

    fun buildSoundNode(): LiteralArgumentBuilder<CommandSourceStack> {
        return Commands.literal("sonido")
            .then(Commands.literal("lista").executes { ctx ->
                val list = ServerAssetsManager.listSounds()
                if (list.isEmpty()) {
                    ctx.source.sendSuccess({
                        Component.literal("§e[Sonidos] No hay sonidos cargados en config/desgraciamon4_core/sounds/.")
                    }, false)
                } else {
                    ctx.source.sendSuccess({
                        Component.literal("§b[Sonidos] Cargados (${list.size}):")
                    }, false)
                    for (entry in list) {
                        val kb = (entry.size / 1024).coerceAtLeast(1)
                        ctx.source.sendSuccess({
                            Component.literal(" §7- §f${entry.id} §8(.${entry.ext}, ${kb} KB)")
                        }, false)
                    }
                }
                1
            })
            .then(Commands.literal("recargar").executes { ctx ->
                val (soundCount, emojiCount) = ServerAssetsManager.reloadAndSync(ctx.source.server)
                ctx.source.sendSuccess({
                    Component.literal("§a[Assets] Recargados y sincronizados: §f$soundCount §asonidos y §f$emojiCount §aemojis.")
                }, true)
                1
            })
            .then(
                Commands.literal("reproducir")
                    .then(
                        Commands.argument("id", StringArgumentType.word())
                            .suggests(soundSuggestions)
                            .executes { ctx ->
                                val id = StringArgumentType.getString(ctx, "id")
                                executePlay(ctx.source, id, null, 1.0f)
                            }
                            .then(
                                Commands.argument("jugador", EntityArgument.player())
                                    .executes { ctx ->
                                        val id = StringArgumentType.getString(ctx, "id")
                                        val target = EntityArgument.getPlayer(ctx, "jugador")
                                        executePlay(ctx.source, id, target, 1.0f)
                                    }
                                    .then(
                                        Commands.argument("volumen", FloatArgumentType.floatArg(0.05f, 2.0f))
                                            .executes { ctx ->
                                                val id = StringArgumentType.getString(ctx, "id")
                                                val target = EntityArgument.getPlayer(ctx, "jugador")
                                                val volume = FloatArgumentType.getFloat(ctx, "volumen")
                                                executePlay(ctx.source, id, target, volume)
                                            }
                                    )
                            )
                    )
            )
    }

    fun buildEmojisNode(): LiteralArgumentBuilder<CommandSourceStack> {
        return Commands.literal("emojis")
            .then(Commands.literal("lista").executes { ctx ->
                val list = ServerAssetsManager.listEmojis()
                if (list.isEmpty()) {
                    ctx.source.sendSuccess({
                        Component.literal("§e[Emojis] No hay emojis PNG cargados en config/desgraciamon4_core/emojis/.")
                    }, false)
                } else {
                    ctx.source.sendSuccess({
                        Component.literal("§b[Emojis] Cargados (${list.size}):")
                    }, false)
                    val joined = list.joinToString("§7, ") { "§f:${it.id}:" }
                    ctx.source.sendSuccess({ Component.literal(" $joined") }, false)
                }
                1
            })
            .then(Commands.literal("recargar").executes { ctx ->
                val (soundCount, emojiCount) = ServerAssetsManager.reloadAndSync(ctx.source.server)
                ctx.source.sendSuccess({
                    Component.literal("§a[Assets] Recargados y sincronizados: §f$soundCount §asonidos y §f$emojiCount §aemojis.")
                }, true)
                1
            })
    }

    private fun executePlay(
        source: CommandSourceStack,
        rawId: String,
        target: net.minecraft.server.level.ServerPlayer?,
        volume: Float
    ): Int {
        val id = ServerAssetsManager.sanitizeId(rawId)
        if (!ServerAssetsManager.hasSound(id)) {
            source.sendFailure(Component.literal("§c[Sonidos] No existe el sonido '$rawId'. Usa /admin sonido lista."))
            return 0
        }
        val sent = ServerAssetsManager.playSound(source.server, id, volume, target)
        val scope = target?.scoreboardName ?: "todos ($sent jugadores)"
        source.sendSuccess({
            Component.literal("§a[Sonidos] Reproduciendo '§f$id§a' para §e$scope§a.")
        }, false)
        return 1
    }
}
