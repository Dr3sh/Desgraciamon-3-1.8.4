package com.desgraciamon.itemrestrictions

import com.desgraciamon.badges.RichText
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import net.minecraft.network.chat.MutableComponent
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files

object TextCatalog {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val logger = LoggerFactory.getLogger("desgraciamon4-item-restrictions")

    private val defaults: Map<String, String> = linkedMapOf(
        "item.disabled" to "<color=#EF4444>Este objeto está deshabilitado</color>"
    )

    private var texts: Map<String, String> = defaults
    private var loaded = false

    private fun resolveFile(): File {
        val dir = File("config/desgraciamon4_item_restrictions/.texts")
        dir.mkdirs()
        runCatching { Files.setAttribute(dir.toPath(), "dos:hidden", true) }
        val file = File(dir, "texts.json")
        val legacy = File("config/desgraciamon4_item_restrictions/texts.json")
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
