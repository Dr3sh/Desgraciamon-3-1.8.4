package com.desgraciamon.badges

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.suggestion.SuggestionProvider
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.Style
import net.minecraft.server.level.ServerPlayer
import java.io.File

internal object TextsPreviewCommand {
    private val gson = Gson()
    private val mapType = object : TypeToken<LinkedHashMap<String, String>>() {}.type

    private val MODULE_SUGGESTIONS = SuggestionProvider<CommandSourceStack> { _, builder ->
        SharedSuggestionProvider.suggest(discoverModules(), builder)
    }

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("textos")
                .requires { it.hasPermission(2) }
                .executes { ctx -> listModules(ctx.source) }
                .then(Commands.literal("listar").executes { ctx -> listModules(ctx.source) })
                .then(
                    Commands.literal("ver")
                        .then(
                            Commands.argument("modulo", StringArgumentType.word())
                                .suggests(MODULE_SUGGESTIONS)
                                .executes { ctx ->
                                    previewModule(
                                        ctx.source,
                                        StringArgumentType.getString(ctx, "modulo"),
                                        ""
                                    )
                                }
                                .then(
                                    Commands.argument("filtro", StringArgumentType.greedyString())
                                        .executes { ctx ->
                                            previewModule(
                                                ctx.source,
                                                StringArgumentType.getString(ctx, "modulo"),
                                                StringArgumentType.getString(ctx, "filtro")
                                            )
                                        }
                                )
                        )
                )
        )
    }

    private fun discoverModules(): List<String> {
        val cfg = File("config")
        if (!cfg.isDirectory) return emptyList()
        return cfg.listFiles()
            ?.filter { it.isDirectory && File(it, ".texts/texts.json").isFile }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()
    }

    private fun listModules(source: CommandSourceStack): Int {
        val modules = discoverModules()
        if (modules.isEmpty()) {
            source.sendFailure(Component.literal("No se encontraron carpetas config/<mod>/.texts/texts.json"))
            return 0
        }
        source.sendSuccess({ Component.literal("§b§l[Textos] Módulos con .texts/texts.json (${modules.size}):") }, false)
        for (mod in modules) {
            val file = File("config/$mod/.texts/texts.json")
            val count = runCatching {
                file.reader(Charsets.UTF_8).use { gson.fromJson<Map<String, String>>(it, mapType)?.size ?: 0 }
            }.getOrDefault(0)
            val clickable = Component.literal(" §7- §f$mod §8($count claves) ")
                .append(
                    Component.literal("[Ver en chat]").setStyle(
                        Style.EMPTY
                            .withColor(0x38BDF8)
                            .withBold(true)
                            .withClickEvent(ClickEvent(ClickEvent.Action.RUN_COMMAND, "/textos ver $mod"))
                            .withHoverEvent(HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Ejecutar /textos ver $mod")))
                    )
                )
            source.sendSuccess({ clickable }, false)
        }
        return modules.size
    }

    private fun previewModule(source: CommandSourceStack, module: String, filter: String): Int {
        val file = File("config/$module/.texts/texts.json")
        if (!file.isFile) {
            source.sendFailure(Component.literal("No existe config/$module/.texts/texts.json"))
            return 0
        }
        val entries: Map<String, String> = runCatching {
            file.reader(Charsets.UTF_8).use { gson.fromJson<Map<String, String>>(it, mapType) ?: emptyMap() }
        }.getOrElse {
            source.sendFailure(Component.literal("Error al leer ${file.path}: ${it.message}"))
            return 0
        }

        val needle = filter.trim().lowercase()
        val filtered = entries.entries.filter { (k, v) ->
            needle.isEmpty() || k.lowercase().contains(needle) || v.lowercase().contains(needle)
        }

        val playerName = (source.entity as? ServerPlayer)?.scoreboardName ?: "Entrenador"
        val samples = sampleReplacements(playerName)

        source.sendSuccess({
            Component.literal("§b§l=== Preview: $module (${filtered.size}/${entries.size}) ===")
        }, false)

        for ((key, template) in filtered) {
            val rendered = runCatching { RichText.parse(template, samples) }
                .getOrElse { Component.literal(template) }
            val line = Component.literal("§8[$key] §r").append(rendered)
            source.sendSuccess({ line }, false)
        }
        return filtered.size
    }

    private fun sampleReplacements(playerName: String): Map<String, Any?> = mapOf(
        "player" to playerName,
        "owner" to playerName,
        "target" to "Ash",
        "challenger" to playerName,
        "opponent" to "Red",
        "winner" to playerName,
        "loser" to "Gary",
        "pokemon" to "Rayquaza",
        "species" to "Pikachu",
        "name" to "Rayquaza",
        "trainer" to "Brock",
        "biome" to "Cherry Grove",
        "biomeId" to "minecraft:cherry_grove",
        "query" to "Pikachu",
        "format" to "OU",
        "arena" to "Arena Principal",
        "reward" to "50,000",
        "price" to "2,500",
        "amount" to "500",
        "count" to "3",
        "total" to "10",
        "shown" to "5",
        "visible" to "2",
        "time" to "12m 30s",
        "remaining" to "12m 30s",
        "key" to "U",
        "code" to "ABCD-1234",
        "id" to "admin",
        "badge" to "[RA]",
        "badges" to "[RA] ",
        "description" to "Insignia de ejemplo",
        "color" to "#38BDF8",
        "item" to "Master Ball",
        "season" to "Temporada 2",
        "ball" to "Ultra Ball",
        "origin" to "PC",
        "title" to "Anuncio de prueba",
        "message" to "Mensaje de ejemplo"
    )
}
