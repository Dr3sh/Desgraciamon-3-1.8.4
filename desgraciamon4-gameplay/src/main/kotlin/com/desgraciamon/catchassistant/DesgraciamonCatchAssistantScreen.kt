package com.desgraciamon.catchassistant

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.pow

class DesgraciamonCatchAssistantScreen : Screen(TextCatalog.component("screen.title")) {
    companion object {
        private const val BASE_W = 500f
        private const val BASE_H = 320f
    }
    private var uiScale = 1.0f
    private var renderX = 0f
    private var renderY = 0f
    private val openedAtNanos = System.nanoTime()

    private fun openingProgress(): Float =
        ((System.nanoTime() - openedAtNanos) / 2.8e8f).coerceIn(0.0f, 1.0f)

    override fun renderBlurredBackground(delta: Float) {}
    override fun renderMenuBackground(graphics: GuiGraphics) {}

    private val formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.of("America/Caracas"))
    private var scrollOffset = 0

    override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val margin = 8f
        uiScale = minOf(1.0f, (width - margin * 2) / BASE_W, (height - margin * 2) / BASE_H).coerceAtLeast(0.45f)
        renderX = (width - BASE_W * uiScale) / 2f
        renderY = (height - BASE_H * uiScale) / 2f

        guiGraphics.fill(0, 0, width, height, 0xF20B1119.toInt())

        val matrices = guiGraphics.pose()
        matrices.pushPose()
        matrices.translate(renderX, renderY, 0f)
        matrices.scale(uiScale, uiScale, 1.0f)

        val progress = openingProgress()
        if (progress < 1.0f) {
            val eased = 1.0f - (1.0f - progress).toDouble().pow(3.0).toFloat()
            val animScale = 0.08f + eased * 0.92f
            val cx = BASE_W / 2.0f
            val cy = BASE_H / 2.0f
            matrices.translate(cx, cy, 0f)
            matrices.scale(animScale, animScale, 1.0f)
            matrices.translate(-cx, -cy, 0f)
        }

        val title = DesgraciamonConfig.current.cardTitle
        guiGraphics.drawCenteredString(Minecraft.getInstance().font, title, (BASE_W / 2).toInt(), 14, 0xFFDF7F.toInt())

        val entries = LegendaryCaptureClientData.entries
        val startX = 12
        val startY = 36
        val rowHeight = 60
        val visibleCount = ((BASE_H.toInt() - startY - 10) / rowHeight).coerceAtLeast(1)
        val maxOffset = (entries.size - visibleCount).coerceAtLeast(0)
        scrollOffset = scrollOffset.coerceIn(0, maxOffset)

        if (entries.isEmpty()) {
            guiGraphics.drawCenteredString(Minecraft.getInstance().font, TextCatalog.component("screen.waiting"), (BASE_W / 2).toInt(), startY + 20, 0xFFBFC7D5.toInt())
        }

        val rowWidth = (BASE_W - startX * 2).toInt()
        entries.drop(scrollOffset).take(visibleCount).forEachIndexed { index, entry ->
            val x = startX
            val y = startY + index * rowHeight
            val captured = entry.capturedAt > 0L
            guiGraphics.fill(x, y, x + rowWidth, y + rowHeight - 4, 0xEE141D29.toInt())
            guiGraphics.fill(x + 8, y + 8, x + 42, y + 42, if (captured) 0xFF23553F.toInt() else 0xFF303744.toInt())
            guiGraphics.drawCenteredString(Minecraft.getInstance().font, if (captured) "[OK]" else "·", x + 25, y + 19, 0xFFE6F6EE.toInt())
            val textX = x + 50
            val textWidth = rowWidth - 58
            val speciesKey = if (entry.shiny) "species.shiny" else "species.normal"
            val displaySpeciesName = TextCatalog.component(speciesKey, "species" to entry.speciesName)
            guiGraphics.drawString(Minecraft.getInstance().font, displaySpeciesName, textX, y + 6, if (entry.shiny) 0xFFE3B341.toInt() else 0xFFF4F1E8.toInt())
            if (captured) {
                guiGraphics.drawString(Minecraft.getInstance().font, trim(TextCatalog.string("capture.by", "player" to entry.capturedBy, "date" to formatter.format(Instant.ofEpochMilli(entry.capturedAt))), textWidth), textX, y + 22, 0xFFB6D5C2.toInt())
                val availableAt = Instant.ofEpochMilli(entry.nextCaptureAt)
                val statusKey = if (entry.nextCaptureAt > System.currentTimeMillis()) "status.available" else "status.cooldown_done"
                val status = TextCatalog.component(statusKey, "date" to formatter.format(availableAt))
                guiGraphics.drawString(Minecraft.getInstance().font, status, textX, y + 36, 0xFFE8C77C.toInt())
            } else {
                guiGraphics.drawString(Minecraft.getInstance().font, TextCatalog.component("status.no_captures"), textX, y + 24, 0xFFAAB3C0.toInt())
            }
        }

        // Scrollbar
        if (entries.size > visibleCount) {
            val trackX = startX + rowWidth - 3
            val trackY = startY
            val trackH = visibleCount * rowHeight - 4
            guiGraphics.fill(trackX, trackY, trackX + 3, trackY + trackH, 0x40000000.toInt())
            val thumbH = ((visibleCount.toFloat() / entries.size) * trackH).toInt().coerceAtLeast(12)
            val thumbY = trackY + ((scrollOffset.toFloat() / maxOffset) * (trackH - thumbH)).toInt()
            guiGraphics.fill(trackX, thumbY, trackX + 3, thumbY + thumbH, 0xFFDF7F.toInt())
        }

        matrices.popPose()
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        val visibleCount = ((BASE_H.toInt() - 36 - 10) / 60).coerceAtLeast(1)
        val maxOffset = (LegendaryCaptureClientData.entries.size - visibleCount).coerceAtLeast(0)
        scrollOffset = (scrollOffset - scrollY.toInt()).coerceIn(0, maxOffset)
        return true
    }

    private fun trim(text: String, maxWidth: Int): String {
        val font = Minecraft.getInstance().font
        if (font.width(text) <= maxWidth) return text
        return font.plainSubstrByWidth(text, (maxWidth - font.width("…")).coerceAtLeast(0)) + "…"
    }
}
