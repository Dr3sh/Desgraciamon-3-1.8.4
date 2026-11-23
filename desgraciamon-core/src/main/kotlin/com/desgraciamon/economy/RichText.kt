package com.desgraciamon.economy

import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor

/**
 * Parser de texto enriquecido compartido por los mods de Desgraciamon.
 * Soporta etiquetas:
 *  - `<color=#RRGGBB>` / `<color=#RGB>` ... `</color>`
 *  - `<bold>` ... `</bold>`, `<italic>` ... `</italic>`
 *  - `<prism=#C1,#C2[,#C3...] [speed=N]>` ... `</prism>`
 *  - `{placeholder}`: se inserta como texto o Componente
 */
internal object RichText {
    const val PRISM_PREFIX = "prism:"

    private val TOKENS = Regex(
        "<color=#(?:[0-9a-f]{6}|[0-9a-f]{3})>|<bold>|<italic>|<prism=[^<>]{1,200}>|</(?:color|bold|italic|prism)>|\\{[A-Za-z][A-Za-z0-9_]*}",
        RegexOption.IGNORE_CASE
    )
    private val HEX = Regex("#([0-9a-fA-F]{6}|[0-9a-fA-F]{3})(?![0-9a-fA-F])")
    private val SPEED = Regex("speed=(-?[0-9]+(?:\\.[0-9]+)?)", RegexOption.IGNORE_CASE)
    private val EMOJI_TAG = Regex(":[a-zA-Z0-9_+\\-]+:")

    private class Prism(val colors: IntArray, val speed: Double) {
        val pieces = mutableListOf<Pair<String, Style>>()
    }

    private class Frame(val tag: String, val previous: Style, val previousPrism: Prism?)

    fun parse(template: String, replacements: Map<String, Any?>): MutableComponent {
        val result = Component.empty()
        var style = Style.EMPTY
        var prism: Prism? = null
        val stack = ArrayDeque<Frame>()

        fun emit(text: String) {
            if (text.isEmpty()) return
            val active = prism
            if (active != null) active.pieces += text to style
            else result.append(Component.literal(text).withStyle(style))
        }

        fun closeTag(tag: String) {
            val index = stack.indexOfLast { it.tag == tag }
            if (index < 0) return
            while (stack.size > index) {
                val frame = stack.removeLast()
                style = frame.previous
                if (frame.tag == "prism") {
                    prism?.let { flushPrism(it, result) }
                    prism = frame.previousPrism
                }
            }
        }

        var cursor = 0
        for (match in TOKENS.findAll(template)) {
            if (match.range.first > cursor) emit(template.substring(cursor, match.range.first))
            val token = match.value
            val lower = token.lowercase()
            when {
                lower.startsWith("<color=") -> {
                    stack.addLast(Frame("color", style, prism))
                    style = style.withColor(TextColor.fromRgb(parseHex(token.substring(8, token.length - 1))))
                }
                lower == "<bold>" -> { stack.addLast(Frame("bold", style, prism)); style = style.withBold(true) }
                lower == "<italic>" -> { stack.addLast(Frame("italic", style, prism)); style = style.withItalic(true) }
                lower.startsWith("<prism=") -> {
                    val colors = HEX.findAll(token).map { parseHex(it.groupValues[1]) }.toList()
                    if (colors.isEmpty()) {
                        emit(token)
                    } else {
                        val speed = SPEED.find(token)?.groupValues?.get(1)?.toDoubleOrNull() ?: 1.0
                        prism?.let { flushPrism(it, result) }
                        stack.addLast(Frame("prism", style, prism))
                        val palette = if (colors.size == 1) listOf(colors[0], colors[0]) else colors
                        prism = Prism(palette.toIntArray(), speed.coerceIn(-20.0, 20.0))
                    }
                }
                lower.startsWith("</") -> closeTag(lower.substring(2, lower.length - 1))
                else -> {
                    val name = token.substring(1, token.length - 1)
                    val replacement = replacements[name]
                    if (replacement is Component) {
                        prism?.let { flushPrism(it, result) }
                        result.append(replacement)
                    } else {
                        emit(replacement?.toString() ?: token)
                    }
                }
            }
            cursor = match.range.last + 1
        }
        if (cursor < template.length) emit(template.substring(cursor))
        prism?.let { flushPrism(it, result) }
        return result
    }

    private fun flushPrism(prism: Prism, result: MutableComponent) {
        if (prism.pieces.isEmpty()) return
        val tokens = mutableListOf<Pair<String, Style>>()
        for ((text, pieceStyle) in prism.pieces) {
            var cursor = 0
            for (m in EMOJI_TAG.findAll(text)) {
                if (m.range.first > cursor) {
                    val sub = text.substring(cursor, m.range.first)
                    var off = 0
                    while (off < sub.length) {
                        val cp = sub.codePointAt(off)
                        tokens += String(Character.toChars(cp)) to pieceStyle
                        off += Character.charCount(cp)
                    }
                }
                tokens += m.value to pieceStyle
                cursor = m.range.last + 1
            }
            if (cursor < text.length) {
                val sub = text.substring(cursor)
                var off = 0
                while (off < sub.length) {
                    val cp = sub.codePointAt(off)
                    tokens += String(Character.toChars(cp)) to pieceStyle
                    off += Character.charCount(cp)
                }
            }
        }
        val total = tokens.size.coerceAtLeast(1)
        val palette = prism.colors.joinToString(",") { String.format("%06x", it) }
        val speed = String.format(java.util.Locale.ROOT, "%.2f", prism.speed)
        for ((index, token) in tokens.withIndex()) {
            val (str, pieceStyle) = token
            val position = index.toDouble() / total
            val letterStyle = pieceStyle
                .withColor(TextColor.fromRgb(sample(prism.colors, position)))
                .withInsertion(PRISM_PREFIX + palette + ";" + speed + ";" + String.format(java.util.Locale.ROOT, "%.4f", position))
            result.append(Component.literal(str).withStyle(letterStyle))
        }
        prism.pieces.clear()
    }

    private fun sample(colors: IntArray, position: Double): Int {
        val scaled = (position - Math.floor(position)) * colors.size
        val i = scaled.toInt().coerceIn(0, colors.size - 1)
        val t = scaled - i
        val a = colors[i]
        val b = colors[(i + 1) % colors.size]
        fun channel(shift: Int): Int {
            val ca = (a shr shift) and 0xFF
            val cb = (b shr shift) and 0xFF
            return (ca + (cb - ca) * t).toInt().coerceIn(0, 255)
        }
        return (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun parseHex(hex: String): Int {
        val clean = hex.removePrefix("#")
        val full = if (clean.length == 3) clean.map { "$it$it" }.joinToString("") else clean
        return full.toInt(16)
    }
}

