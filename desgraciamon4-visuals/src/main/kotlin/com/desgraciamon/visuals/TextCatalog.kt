package com.desgraciamon.visuals

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import net.minecraft.network.chat.MutableComponent
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files

object TextCatalog {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val logger = LoggerFactory.getLogger("desgraciamon4-visuals")

    private val defaults: Map<String, String> = linkedMapOf(
        "vortex.bossbar.title" to "<prism=#A855F7,#EC4899><bold>Vórtice Dimensional</bold></prism>",
        "vortex.nofly.ride" to "<color=#F0ABFC>¡La gravedad del Vórtice Dimensional impide volar en monturas!</color>",
        "vortex.nofly.dismount" to "<color=#F0ABFC>¡El campo gravitacional del Vórtice ha desactivado tu vuelo! Salvavidas activado.</color>",
        "vortex.anomaly.steal_blocked" to "<color=#EF4444>¡No puedes robar la Anomalía mientras otro entrenador lucha contra ella!</color>",
        "vortex.limbo.empty" to "<color=#EF4444>No tienes ninguna Anomalía Dimensional pendiente en el Limbo.</color>",
        "vortex.limbo.claimed" to "<color=#4ADE80>¡Has reclamado la anomalía </color><color=#E879F9><bold>{pokemon} Shiny (6x31 IVs)</bold></color><color=#4ADE80>! Ha sido enviada a tu PC.</color>",
        "vortex.limbo.sold" to "<color=#F59E0B>¡Has vendido la anomalía </color><color=#FDE047>{pokemon}</color><color=#F59E0B> al Mercado Negro por </color><color=#4ADE80>${"{reward}"} CobbleDollars</color><color=#F59E0B>!</color>"
    )

    private var texts: Map<String, String> = defaults

    private fun resolveFile(): File {
        val dir = File("config/desgraciamon4_visuals/.texts")
        dir.mkdirs()
        runCatching { Files.setAttribute(dir.toPath(), "dos:hidden", true) }
        val file = File(dir, "texts.json")
        val legacy = File("config/desgraciamon4_visuals/texts.json")
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

    fun string(key: String, vararg values: Pair<String, Any?>): String = component(key, *values).string
}
