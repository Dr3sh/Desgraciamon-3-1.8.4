package com.desgraciamon.prismtext

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.minecraft.Util
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import org.slf4j.LoggerFactory
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor

class PrismTextClient : ClientModInitializer {
    override fun onInitializeClient() {
        LOGGER.info("Desgraciamon 3 Prism Text inicializado (animación de degradados <prism=...>).")

        // Comando local de prueba para previsualizar el efecto prisma en cualquier momento
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                ClientCommandManager.literal("prismdemo").executes { ctx ->
                    val demo = buildDemoComponent(
                        "[PRISM DEMO] ¡Efecto Prisma animado en bucle continuo!",
                        intArrayOf(0xFF3CAC, 0x784BA0, 0x2B86C5, 0x00F2FE),
                        1.0
                    )
                    ctx.source.sendFeedback(demo)
                    1
                }
            )
        }
    }

    companion object {
        val LOGGER = LoggerFactory.getLogger("desgraciamon4-prism-text")
        const val PRISM_PREFIX = "prism:"

        private class ParsedSpec(val colors: IntArray, val speed: Double, val position: Double)

        private val cache = ConcurrentHashMap<String, ParsedSpec>()

        @JvmStatic
        fun isPrismStyle(style: Style?): Boolean {
            val ins = style?.insertion ?: return false
            return ins.startsWith(PRISM_PREFIX)
        }

        @JvmStatic
        fun animateStyle(style: Style): Style {
            val ins = style.insertion ?: return style
            if (!ins.startsWith(PRISM_PREFIX)) return style

            val spec = cache[ins] ?: parseSpec(ins)?.also { cache[ins] = it } ?: return style
            val timeSeconds = (Util.getMillis() % 3_600_000L) / 1000.0
            // Movimiento continuo en una sola dirección a lo largo del texto
            val phase = spec.position - timeSeconds * 0.45 * spec.speed
            val rgb = sampleCyclic(spec.colors, phase)
            return style.withColor(TextColor.fromRgb(rgb))
        }

        private fun parseSpec(raw: String): ParsedSpec? {
            if (cache.size > 8192) cache.clear()
            val body = raw.removePrefix(PRISM_PREFIX)
            val parts = body.split(';')
            if (parts.size != 3) return null
            val colorTokens = parts[0].split(',')
            if (colorTokens.isEmpty()) return null
            val colors = IntArray(colorTokens.size)
            for (i in colorTokens.indices) {
                val c = colorTokens[i].toIntOrNull(16) ?: return null
                colors[i] = c and 0xFFFFFF
            }
            val speed = parts[1].toDoubleOrNull() ?: 1.0
            val pos = parts[2].toDoubleOrNull() ?: 0.0
            return ParsedSpec(colors, speed, pos)
        }

        /** Interpola suavemente en un ciclo continuo (el último color conecta con el primero). */
        private fun sampleCyclic(colors: IntArray, position: Double): Int {
            if (colors.isEmpty()) return 0xFFFFFF
            if (colors.size == 1) return colors[0]
            val wrapped = position - floor(position) // siempre en [0, 1)
            val scaled = wrapped * colors.size
            val idx = scaled.toInt().coerceIn(0, colors.size - 1)
            val t = scaled - idx
            val a = colors[idx]
            val b = colors[(idx + 1) % colors.size]
            val r = (((a shr 16) and 0xFF) + (((b shr 16) and 0xFF) - ((a shr 16) and 0xFF)) * t).toInt().coerceIn(0, 255)
            val g = (((a shr 8) and 0xFF) + (((b shr 8) and 0xFF) - ((a shr 8) and 0xFF)) * t).toInt().coerceIn(0, 255)
            val bl = ((a and 0xFF) + ((b and 0xFF) - (a and 0xFF)) * t).toInt().coerceIn(0, 255)
            return (r shl 16) or (g shl 8) or bl
        }

        private fun buildDemoComponent(text: String, colors: IntArray, speed: Double): Component {
            val out = Component.empty()
            val total = text.codePointCount(0, text.length).coerceAtLeast(1)
            val palette = colors.joinToString(",") { String.format("%06x", it) }
            val speedStr = String.format(Locale.ROOT, "%.2f", speed)
            var idx = 0
            var offset = 0
            while (offset < text.length) {
                val cp = text.codePointAt(offset)
                val pos = idx.toDouble() / total
                val st = Style.EMPTY
                    .withBold(true)
                    .withColor(TextColor.fromRgb(sampleCyclic(colors, pos)))
                    .withInsertion("$PRISM_PREFIX$palette;$speedStr;${String.format(Locale.ROOT, "%.4f", pos)}")
                out.append(Component.literal(String(Character.toChars(cp))).withStyle(st))
                offset += Character.charCount(cp)
                idx++
            }
            return out
        }
    }
}

