package com.addoncobblemon.legendarybestiary

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import net.minecraft.network.chat.MutableComponent
import org.slf4j.LoggerFactory
import java.io.File

object TextCatalog {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val logger = LoggerFactory.getLogger("legendary-bestiary")
    private var defaults: Map<String, String> = emptyMap()
    private var texts: Map<String, String> = emptyMap()

    private val legacyValuesToUpgrade = setOf(
        "En libertad", "Arrastra para rotar • Clic para rugir", "ESPÍRITU EN LIBERTAD", "EN ESTADO SALVAJE", "Estado: Libre en los confines del mundo", "Estado: Libre en el mundo salvaje", "¡Aún ningún entrenador ha logrado registrar su captura!", "¡Busca su rastro por su bioma característico!",
        "Abrir Códice Ancestral", "Códice Ancestral", "CÓDICE ANCESTRAL", "Ver pokedex de legendarios", "Pokedex de Legendarios"
    )

    fun load(defaultTexts: Map<String, String>) {
        defaults = defaultTexts
        val dir = File("config/legendary_bestiary/.texts")
        dir.mkdirs()
        runCatching { java.nio.file.Files.setAttribute(dir.toPath(), "dos:hidden", true) }
        val file = File(dir, "texts.json")
        val legacy = File("config/legendary_bestiary/texts.json")
        if (!file.exists() && legacy.exists()) {
            runCatching { java.nio.file.Files.move(legacy.toPath(), file.toPath()) }
        }
        runCatching {
            file.parentFile?.mkdirs()
            if (!file.exists()) file.writeText(gson.toJson(defaults))
            val type = object : TypeToken<Map<String, String>>() {}.type
            val rawOverrides: Map<String, String> = file.reader().use { gson.fromJson(it, type) ?: emptyMap() }
            val cleanedOverrides = rawOverrides.filter { (k, v) ->
                k in defaults && v !in legacyValuesToUpgrade
            }
            texts = defaults + cleanedOverrides
            file.writeText(gson.toJson(texts))
        }.onFailure { texts = defaults; logger.error("Could not load text catalog from ${file.path}", it) }
    }

    fun reload(): Boolean {
        if (defaults.isEmpty()) return false
        load(defaults)
        return true
    }

    fun component(key: String, vararg values: Pair<String, Any?>): MutableComponent {
        val template = texts[key] ?: defaults[key] ?: key
        return RichText.parse(template, values.associate { it.first to it.second })
    }

    fun string(key: String, vararg values: Pair<String, Any?>): String = component(key, *values).string
}
