package com.desgraciamon.catchassistant

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import org.slf4j.LoggerFactory
import java.io.File

object TextCatalog {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val logger = LoggerFactory.getLogger("desgraciamon4-catch-assistant")
    private var defaults: Map<String, String> = emptyMap()
    private var texts: Map<String, String> = emptyMap()

    fun load(defaultTexts: Map<String, String>) {
        defaults = defaultTexts
        val dir = File("config/desgraciamon4_catch_assistant/.texts")
        dir.mkdirs()
        runCatching { java.nio.file.Files.setAttribute(dir.toPath(), "dos:hidden", true) }
        val file = File(dir, "texts.json")
        val legacy = File("config/desgraciamon4_catch_assistant/texts.json")
        if (!file.exists() && legacy.exists()) {
            runCatching { java.nio.file.Files.move(legacy.toPath(), file.toPath()) }
        }
        runCatching {
            file.parentFile?.mkdirs()
            if (!file.exists()) file.writeText(gson.toJson(defaults))
            val type = object : TypeToken<Map<String, String>>() {}.type
            val overrides: Map<String, String> = file.reader().use { gson.fromJson(it, type) ?: emptyMap() }
                texts = defaults + overrides
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

    fun color(key: String, fallback: Int): Int = (texts[key] ?: defaults[key])
        ?.removePrefix("#")
        ?.toIntOrNull(16)
        ?.takeIf { it in 0..0xFFFFFF }
        ?: fallback
}