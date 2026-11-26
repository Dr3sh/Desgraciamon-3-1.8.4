package com.desgraciamon.announcements

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
    private val logger = LoggerFactory.getLogger("desgraciamon4-announcements")
    private var defaults: Map<String, String> = mapOf(
        "daily.unlocked" to "<color=#38BDF8>Hay una nueva misión diaria, revísala con la tecla [{key}]</color>",
        "mission.new_online_notice" to "<color=#B91C3C>Hay una nueva misión, revísala con la tecla [{key}]</color>",
        "join.new_both" to "<color=#F59E0B>[Desgraciamon]</color> <color=#FDE047>Hay </color><color=#FFFFFF>{announcements}</color><color=#FDE047> anuncios y </color><color=#FFFFFF>{missions}</color><color=#FDE047> misiones nuevas. ¡Revisa con la tecla </color>[{key}]<color=#FDE047>!</color>",
        "join.new_announcements" to "<color=#F59E0B>[Desgraciamon]</color> <color=#FDE047>Hay </color><color=#FFFFFF>{announcements}</color><color=#FDE047> anuncios nuevos. ¡Revisa con la tecla </color>[{key}]<color=#FDE047>!</color>",
        "join.new_missions" to "<color=#F59E0B>[Desgraciamon]</color> <color=#FDE047>Hay </color><color=#FFFFFF>{missions}</color><color=#FDE047> misiones nuevas. ¡Revisa con la tecla </color>[{key}]<color=#FDE047>!</color>",
        "mission.chat_header" to "<color=#B91C3C>{prefix}</color> <color=#FFFFFF>{title}</color>",
        "mission.objective_progress" to "<color=#55FF55>{tag}</color> <color=#FFFFFF>{title}</color> <color=#AAAAAA>-</color> <color=#FFFF55>{label}:</color> <color=#55FFFF>{current}/{max}</color>",
        "mission.objective_completed" to "<color=#55FF55>{tag}</color> <color=#FFFFFF>{title}</color> <color=#AAAAAA>-</color> <color=#55FF55>¡Objetivo completado! ({completed}/{total} objetivos)</color>"
    )
    private var texts: Map<String, String> = defaults

    fun defaultFile(): File {
        val dir = File("config/desgraciamon4_announcements/.texts")
        dir.mkdirs()
        runCatching { java.nio.file.Files.setAttribute(dir.toPath(), "dos:hidden", true) }
        val file = File(dir, "texts.json")
        val legacy = File("config/desgraciamon4_announcements/texts.json")
        if (!file.exists() && legacy.exists()) {
            runCatching { java.nio.file.Files.move(legacy.toPath(), file.toPath()) }
        }
        return file
    }

    @JvmOverloads
    fun load(defaultTexts: Map<String, String>, file: File = defaultFile()) {
        val mergedDefaults = defaults + defaultTexts
        defaults = mergedDefaults
        runCatching {
            file.parentFile?.mkdirs()
            if (!file.exists()) {
                file.writeText(gson.toJson(mergedDefaults), Charsets.UTF_8)
            }
            val type = object : TypeToken<Map<String, String>>() {}.type
            val rawOverrides: Map<String, String> = file.reader(Charsets.UTF_8).use {
                gson.fromJson(it, type) ?: emptyMap()
            }
            val cleanedOverrides = rawOverrides.mapValues { (key, value) ->
                val sanitized = sanitizeText(value)
                val strippedDefault = mergedDefaults[key]
                if (sanitized.isBlank() || hasEmptyColorTag(sanitized)) {
                    strippedDefault ?: sanitized
                } else {
                    sanitized
                }
            }
            texts = mergedDefaults + cleanedOverrides
            file.writeText(gson.toJson(texts), Charsets.UTF_8)
        }.onFailure {
            texts = mergedDefaults
            logger.error("Could not load text catalog from ${file.path}", it)
        }
    }

    fun reload(): Boolean {
        if (defaults.isEmpty()) return false
        load(defaults)
        return true
    }

    fun raw(key: String): String = texts[key] ?: defaults[key] ?: key

    fun component(key: String, vararg values: Pair<String, Any?>): MutableComponent {
        var template = raw(key)
        val map = values.associate { it.first to it.second }
        val colorOverride = map["color"] as? String
        if (colorOverride != null && isValidHexColor(colorOverride)) {
            template = template.replace("{color}", normalizeHexColor(colorOverride, "#38BDF8"))
        }
        return RichText.parse(template, map)
    }

    fun string(key: String, vararg values: Pair<String, Any?>): String = component(key, *values).string

    fun dailyUnlockComponent(
        colorHex: String = "#38BDF8",
        keybindId: String = "key.desgraciamon4_announcements.open"
    ): MutableComponent {
        val safeHex = normalizeHexColor(colorHex, "#38BDF8")
        val rgb = parseRgb(safeHex)
        val keyComp = Component.keybind(keybindId).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb)).withBold(true))
        return Component.literal("Hay una nueva misión diaria, revísala con la tecla [")
            .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb)))
            .append(keyComp)
            .append(Component.literal("]").withStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb))))
    }

    fun missionPublishedComponent(
        colorHex: String = "#B91C3C",
        keybindId: String = "key.desgraciamon4_announcements.open"
    ): MutableComponent {
        val safeHex = normalizeHexColor(colorHex, "#B91C3C")
        val rgb = parseRgb(safeHex)
        val keyComp = Component.keybind(keybindId).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb)).withBold(true))
        return Component.literal("Hay una nueva misión, revísala con la tecla [")
            .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb)))
            .append(keyComp)
            .append(Component.literal("]").withStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb))))
    }

    fun sanitizeText(value: String): String {
        val sb = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val cp = value.codePointAt(i)
            val isSymbolOrEmoji =
                cp == 0x2726 || cp == 0x2714 ||
                    cp == 0xFE0E || cp == 0xFE0F || cp == 0x200D ||
                    cp == 0x2B50 || cp == 0x2B55 || cp == 0x2934 || cp == 0x2935 ||
                    cp in 0x1F000..0x1FAFF ||
                    cp in 0x2600..0x27BF ||
                    cp in 0x2300..0x23FF
            if (!isSymbolOrEmoji) {
                sb.appendCodePoint(cp)
            }
            i += Character.charCount(cp)
        }
        return sb.toString()
            .replace(Regex("<color=#?[0-9a-fA-F]{3,6}>\\s*</color>"), "")
            .replace(Regex(" {2,}"), " ")
            .trim()
    }

    private fun hasEmptyColorTag(text: String): Boolean =
        text.contains("<color=") && text.replace(Regex("<color=#?[0-9a-fA-F]{3,6}>|</color>"), "").isBlank()

    private fun isValidHexColor(raw: String): Boolean =
        raw.trim().removePrefix("#").matches(Regex("^(?:[0-9a-fA-F]{6}|[0-9a-fA-F]{3})$"))

    internal fun normalizeHexColor(raw: String, fallback: String = "#38BDF8"): String {
        val clean = raw.trim().removePrefix("#")
        return if (clean.matches(Regex("^(?:[0-9a-fA-F]{6}|[0-9a-fA-F]{3})$"))) {
            "#" + clean.uppercase()
        } else {
            fallback
        }
    }

    private fun parseRgb(hex: String): Int {
        val clean = hex.removePrefix("#")
        val full = if (clean.length == 3) clean.map { "$it$it" }.joinToString("") else clean
        return full.toIntOrNull(16) ?: 0x38BDF8
    }
}
