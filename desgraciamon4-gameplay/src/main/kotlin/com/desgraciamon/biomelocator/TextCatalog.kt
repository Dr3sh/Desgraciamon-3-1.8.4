package com.desgraciamon.biomelocator

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import net.minecraft.network.chat.MutableComponent
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files

object TextCatalog {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val logger = LoggerFactory.getLogger("desgraciamon4-biome-locator")

    private val defaults: Map<String, String> = linkedMapOf(
        "command.usage" to "<color=#F59E0B>Radar de Biomas: usa </color><color=#FDE047>/radar abrir</color><color=#F59E0B> o pulsa </color><color=#FDE047>[{key}]</color><color=#F59E0B>.</color>",
        "command.spawns.empty" to "<color=#FDE047>No se encontraron apariciones para el bioma </color><color=#FFFFFF>{biome}</color><color=#FDE047>.</color>",
        "command.spawns.header" to "<color=#F59E0B>Apariciones en </color><color=#FFFFFF>{biome}</color> <color=#9CA3AF>({count} pokemon):</color>",
        "command.biomes.empty" to "<color=#FDE047>No se encontraron biomas registrados para </color><color=#FFFFFF>{query}</color><color=#FDE047>.</color>",
        "command.biomes.header" to "<color=#F59E0B>Biomas donde aparece </color><color=#38BDF8>{query}</color> <color=#9CA3AF>({count} biomas):</color>",
        "atlas.stopped" to "<color=#FDE047>[Atlas de Biomas] Rastreo de bioma detenido.</color>",
        "atlas.cooldown" to "<color=#F59E0B>[Atlas de Biomas] </color><color=#FDE047>Espera unos segundos antes de iniciar otra búsqueda.</color>",
        "atlas.invalid_biome" to "<color=#EF4444>[Atlas de Biomas] ID de bioma inválido: {biomeId}</color>"
    )

    private var texts: Map<String, String> = defaults

    private fun resolveFile(): File {
        val dir = File("config/desgraciamon4_biome_locator/.texts")
        dir.mkdirs()
        runCatching { Files.setAttribute(dir.toPath(), "dos:hidden", true) }
        val file = File(dir, "texts.json")
        val legacy = File("config/desgraciamon4_biome_locator/texts.json")
        if (!file.exists() && legacy.exists()) {
            runCatching { Files.move(legacy.toPath(), file.toPath()) }
        }
        return file
    }

    fun load() {
        val file = resolveFile()
        runCatching {
            if (!file.exists()) file.writeText(gson.toJson(defaults), Charsets.UTF_8)
            val type = object : TypeToken<Map<String, String>>() {}.type
            val overrides: Map<String, String> = file.reader(Charsets.UTF_8).use { gson.fromJson(it, type) ?: emptyMap() }
            texts = defaults + overrides
            file.writeText(gson.toJson(texts), Charsets.UTF_8)
        }.onFailure {
            texts = defaults
            logger.error("Could not load text catalog from ${file.path}", it)
        }
    }

    fun reload(): Boolean {
        load()
        return true
    }

    fun component(key: String, vararg values: Pair<String, Any?>): MutableComponent {
        val template = texts[key] ?: defaults[key] ?: key
        return RichText.parse(template, values.associate { it.first to it.second })
    }
}
