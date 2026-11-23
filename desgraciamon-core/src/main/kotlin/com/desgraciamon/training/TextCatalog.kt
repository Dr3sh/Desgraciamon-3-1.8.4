package com.desgraciamon.training

import com.desgraciamon.badges.RichText
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import net.minecraft.network.chat.MutableComponent
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files

object TextCatalog {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val logger = LoggerFactory.getLogger("desgraciamon4-training")

    private val defaults: Map<String, String> = linkedMapOf(
        "training.victory" to "<color=#4ADE80><bold>VICTORIA! </bold></color><color=#FDE047>Has derrotado a </color><color=#FFFFFF>{trainer}</color><color=#FDE047>. </color><color=#9CA3AF>(Solo XP, 0 CD)</color>",
        "training.defeat" to "<color=#EF4444>[Entrenamiento] Has sido derrotado por {trainer}. Entrena a tus Pokemon y vuelve a intentarlo!</color>",
        "training.fled" to "<color=#9CA3AF>[Entrenamiento] Has huido del combate de entrenamiento.</color>"
    )

    private var texts: Map<String, String> = defaults
    private var loaded = false

    private fun resolveFile(): File {
        val dir = File("config/desgraciamon4_training/.texts")
        dir.mkdirs()
        runCatching { Files.setAttribute(dir.toPath(), "dos:hidden", true) }
        val file = File(dir, "texts.json")
        val legacy = File("config/desgraciamon4_training/texts.json")
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
            loaded = true
        }.onFailure {
            texts = defaults
            logger.error("Could not load text catalog from ${file.path}", it)
        }
    }

    fun component(key: String, vararg values: Pair<String, Any?>): MutableComponent {
        if (!loaded) load()
        val template = texts[key] ?: defaults[key] ?: key
        return RichText.parse(template, values.associate { it.first to it.second })
    }
}
