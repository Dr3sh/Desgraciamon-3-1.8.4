package com.addoncobblemon.legendarycooldown

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import org.slf4j.LoggerFactory
import java.io.File

object TextCatalog {
    private val logger = LoggerFactory.getLogger("legendary-cooldown")
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val defaults = linkedMapOf(
        "bossbar.enabled" to "Bossbar de cooldown activada.",
        "bossbar.disabled" to "Bossbar de cooldown desactivada.",
        "bossbar.title" to "Próxima captura legendaria: {time}",
        "notice.enabled" to "Aviso de cooldown al entrar activado.",
        "notice.disabled" to "Aviso de cooldown al entrar desactivado.",
        "cooldown.cleared.admin" to "Cooldown eliminado para {player}.",
        "cooldown.set.admin" to "Cooldown de {player} establecido en {time}.",
        "cooldown.reduced.admin" to "Se restó {time} al cooldown de {player}.",
        "cooldown.cleared" to "Tu cooldown de captura legendaria fue eliminado.",
        "cooldown.set" to "Se estableció un cooldown legendario de {time}.",
        "cooldown.none_to_reduce" to "No tienes un cooldown activo para reducir.",
        "cooldown.ready" to "Ya puedes capturar otro Pokémon legendario o singular.",
        "cooldown.remaining" to "Tu cooldown restante ahora es {time}.",
        "cooldown.blocked" to "No puedes capturar otro Pokémon legendario todavía. Tiempo restante: {time}",
        "cooldown.join_wait" to "Aún no puedes capturar otro Pokémon legendario o singular. Tiempo restante: {time}",
    )
    private val file: File by lazy {
        val dir = File("config/legendary_cooldown/.texts")
        dir.mkdirs()
        runCatching { java.nio.file.Files.setAttribute(dir.toPath(), "dos:hidden", true) }
        val f = File(dir, "texts.json")
        val legacy = File("config/legendary_cooldown/texts.json")
        if (!f.exists() && legacy.exists()) {
            runCatching { java.nio.file.Files.move(legacy.toPath(), f.toPath()) }
        }
        f
    }
    private var texts: Map<String, String> = defaults

    fun load() {
        runCatching {
            file.parentFile?.mkdirs()
            if (!file.exists()) file.writeText(gson.toJson(defaults))
            val type = object : TypeToken<Map<String, String>>() {}.type
            val overrides: Map<String, String> = file.reader().use { gson.fromJson(it, type) ?: emptyMap() }
            texts = defaults + overrides
            file.writeText(gson.toJson(texts))
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
