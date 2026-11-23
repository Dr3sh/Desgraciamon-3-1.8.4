package com.desgraciamon.visuals

import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor

internal object RichText {
    const val PRISM_PREFIX = "prism:"

    private val TOKENS = Regex(
        "<color=#(?:[0-9a-f]{6}|[0-9a-f]{3})>|<bold>|<italic>|<prism=[^<>]{1,200}>|</(?:color|bold|italic|prism)>|\\{[A-Za-z][A-Za-z0-9_]*}",
        RegexOption.IGNORE_CASE
    )
    private val HEX = Regex("#([0-9a-fA-F]{6}|[0-9a-fA-F]{3})(?![0-9a-fA-F])")
    private val SPEED = Regex("speed=(-?[0-9]+(?:\\.[0-9]+)?)", RegexOption.IGNORE_CASE)

    private class Prism(val colors: IntArray, val speed: Double) {
        val pieces = mutableListOf<Pair<String, Style>>()
    }

    private class Frame(val tag: String, val previous: Style, val previousPrism: Prism?)

    fun parse(template: String, replacements: Map<String, Any?> = emptyMap()): MutableComponent {
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
                lower == "<bold>" -> {
                    stack.addLast(Frame("bold", style, prism))
                    style = style.withBold(true)
                }
                lower == "<italic>" -> {
                    stack.addLast(Frame("italic", style, prism))
                    style = style.withItalic(true)
                }
                lower.startsWith("<prism=") -> {
                    stack.addLast(Frame("prism", style, prism))
                    prism = parsePrism(token.substring(7, token.length - 1))
                }
                lower.startsWith("</") -> closeTag(lower.substring(2, lower.length - 1))
                else -> {
                    val value = replacements[token.substring(1, token.length - 1)]
                    when (value) {
                        is Component -> {
                            val active = prism
                            if (active != null) active.pieces += value.string to style
                            else result.append(value)
                        }
                        null -> {}
                        else -> emit(value.toString())
                    }
                }
            }
            cursor = match.range.last + 1
        }
        if (cursor < template.length) emit(template.substring(cursor))
        while (stack.isNotEmpty()) {
            val frame = stack.removeLast()
            if (frame.tag == "prism") {
                prism?.let { flushPrism(it, result) }
                prism = frame.previousPrism
            }
        }
        return result
    }

    private fun parsePrism(raw: String): Prism? {
        val colors = HEX.findAll(raw).map { parseHex(it.groupValues[1]) }.toList()
        if (colors.isEmpty()) return null
        val speed = SPEED.find(raw)?.groupValues?.get(1)?.toDoubleOrNull()?.coerceIn(-10.0, 10.0) ?: 1.0
        return Prism(colors.toIntArray(), speed)
    }

    private fun parseHex(hex: String): Int {
        val expanded = if (hex.length == 3) "${hex[0]}${hex[0]}${hex[1]}${hex[1]}${hex[2]}${hex[2]}" else hex
        return expanded.toInt(16)
    }

    private fun flushPrism(prism: Prism, target: MutableComponent) {
        val payload = buildString {
            append(PRISM_PREFIX)
            append(prism.speed)
            append(':')
            prism.colors.forEachIndexed { idx, color ->
                if (idx > 0) append(',')
                append(color.toString(16).padStart(6, '0'))
            }
        }
        for ((text, baseStyle) in prism.pieces) {
            if (text.isEmpty()) continue
            val rgb = prism.colors.firstOrNull() ?: 0xFFFFFF
            target.append(Component.literal(text).withStyle(baseStyle.withColor(TextColor.fromRgb(rgb)).withInsertion(payload)))
        }
    }
}
