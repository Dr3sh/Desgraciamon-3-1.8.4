package com.desgraciamon.biomelocator

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component

object BiomeLocatorCommands {

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        val root = Commands.literal("radar")
            .executes { ctx ->
                val player = ctx.source.playerOrException
                BiomeLocatorServer.syncToPlayer(player)
                player.sendSystemMessage(TextCatalog.component("command.usage", "key" to Component.keybind("key.desgraciamon4_biome_locator.open")))
                1
            }
            .then(Commands.literal("abrir").executes { ctx ->
                val player = ctx.source.playerOrException
                BiomeLocatorServer.syncToPlayer(player)
                1
            })
            .then(Commands.literal("detener").executes { ctx ->
                val player = ctx.source.playerOrException
                BiomeLocatorServer.handleAction(player, "STOP", "")
                1
            })
            .then(Commands.literal("apariciones")
                .then(Commands.argument("bioma", StringArgumentType.greedyString()).executes { ctx ->
                    val player = ctx.source.playerOrException
                    val biome = StringArgumentType.getString(ctx, "bioma")
                    val spawns = CobblemonSpawnIntegration.getSpawnsForBiome(player.server, biome)
                    if (spawns.isEmpty()) {
                        player.sendSystemMessage(TextCatalog.component("command.spawns.empty", "biome" to biome))
                    } else {
                        player.sendSystemMessage(TextCatalog.component("command.spawns.header", "biome" to biome, "count" to spawns.size))
                        spawns.take(15).forEach { s ->
                            val col = when (s.bucket) {
                                "ultra-rare" -> "§6"
                                "rare" -> "§d"
                                "uncommon" -> "§b"
                                else -> "§a"
                            }
                            player.sendSystemMessage(Component.literal(" $col- §f${s.displayName} §7(${s.bucket.uppercase()} ${s.percentage}%) §8[${s.timeOfDay}, ${s.context}]"))
                        }
                    }
                    1
                })
            )
            .then(Commands.literal("pokemon")
                .then(Commands.argument("especie", StringArgumentType.greedyString()).executes { ctx ->
                    val player = ctx.source.playerOrException
                    val query = StringArgumentType.getString(ctx, "especie")
                    val biomes = CobblemonSpawnIntegration.getBiomesForPokemon(player.server, query)
                    if (biomes.isEmpty()) {
                        player.sendSystemMessage(Component.literal("§eNo se encontraron biomas registrados para §f$query§e."))
                    } else {
                        player.sendSystemMessage(Component.literal("§6Biomas donde aparece §b$query §7(${biomes.size} biomas):"))
                        biomes.take(12).forEach { b ->
                            player.sendSystemMessage(Component.literal(" §7- §a$b"))
                        }
                    }
                    1
                })
            )
            .then(Commands.literal("admin")
                .requires { it.hasPermission(2) }
                .then(Commands.literal("rastrear")
                    .then(Commands.argument("bioma", StringArgumentType.greedyString()).executes { ctx ->
                        val player = ctx.source.playerOrException
                        val biome = StringArgumentType.getString(ctx, "bioma")
                        BiomeLocatorServer.handleAction(player, "UNLOCK", biome)
                        BiomeLocatorServer.handleAction(player, "TRACK", biome)
                        1
                    })
                )
                .then(Commands.literal("tokens")
                    .then(Commands.argument("cantidad", IntegerArgumentType.integer()).executes { ctx ->
                        val player = ctx.source.playerOrException
                        BiomeLocatorServer.debugAddTokens(player, IntegerArgumentType.getInteger(ctx, "cantidad"))
                        1
                    })
                )
                .then(Commands.literal("especie")
                    .then(Commands.argument("especie", StringArgumentType.word()).executes { ctx ->
                        val player = ctx.source.playerOrException
                        BiomeLocatorServer.debugAddSpecies(player, StringArgumentType.getString(ctx, "especie"))
                        1
                    })
                )
                .then(Commands.literal("desbloquear_todo").executes { ctx ->
                    BiomeLocatorServer.debugUnlockAll(ctx.source.playerOrException)
                    1
                })
                .then(Commands.literal("dimension")
                    .then(Commands.argument("dimension", StringArgumentType.word()).executes { ctx ->
                        BiomeLocatorServer.debugUnlockDimension(ctx.source.playerOrException, StringArgumentType.getString(ctx, "dimension"))
                        1
                    })
                )
                .then(Commands.literal("reiniciar").executes { ctx ->
                    BiomeLocatorServer.debugReset(ctx.source.playerOrException)
                    1
                })
            )

        dispatcher.register(root)
    }
}
