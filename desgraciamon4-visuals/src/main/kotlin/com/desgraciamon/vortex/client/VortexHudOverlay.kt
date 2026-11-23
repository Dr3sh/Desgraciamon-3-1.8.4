package com.desgraciamon.vortex.client

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import kotlin.math.roundToInt
import kotlin.math.sqrt

object VortexHudOverlay : HudRenderCallback {

    override fun onHudRender(guiGraphics: GuiGraphics, deltaTracker: DeltaTracker) {
        val partialTick = deltaTracker.getGameTimeDeltaPartialTick(true)
        val intensity = VortexShaderController.getSmoothIntensity(partialTick)

        if (!VortexShaderController.isEventActiveClient && intensity <= 0.005f) {
            return
        }

        // Los eventos legendarios (Dialga, Palkia, Giratina) no muestran recuadro HUD con contador ni coordenadas
        if (VortexShaderController.isLegendary()) {
            return
        }

        val mc = Minecraft.getInstance()
        if (mc.options.hideGui) return

        val screenWidth = mc.window.guiScaledWidth
        val font = mc.font
        val timerText = "${VortexShaderController.formatCountdown()}"
        val centerPos = VortexShaderController.vortexCenter
        val player = mc.player
        val distStr = if (player != null) {
            val dx = player.x - (centerPos.x + 0.5)
            val dz = player.z - (centerPos.z + 0.5)
            val dist = sqrt(dx * dx + dz * dz).roundToInt()
            " (${dist}m)"
        } else {
            ""
        }
        val effectName = when (VortexShaderController.currentEffectType.lowercase(java.util.Locale.ROOT)) {
            "vortex" -> "Vórtice"
            "equilibrio" -> "Bestia Equilibrio"
            "dialga" -> "Dialga (Tiempo)"
            "palkia" -> "Palkia (Espacio)"
            "giratina" -> "Giratina (Distorsión)"
            else -> "Star Trails"
        }
        val coordsText = "$effectName [${centerPos.x}, ${centerPos.y}, ${centerPos.z}]$distStr"

        val topY = 26
        val timerWidth = font.width(timerText)
        val coordsWidth = font.width(coordsText)
        val boxWidth = maxOf(timerWidth + 24, coordsWidth + 16)
        val boxHeight = 28
        val boxX = (screenWidth - boxWidth) / 2

        val (bgColor, borderColor, defaultTimerColor, coordsColor) = when (VortexShaderController.currentEffectType.lowercase(java.util.Locale.ROOT)) {
            "vortex" -> listOf(0xB0120224.toInt(), 0xFFC084FC.toInt(), 0xFFF0ABFC.toInt(), 0xFFE9D5FF.toInt())
            "equilibrio" -> listOf(0xB01A202C.toInt(), 0xFFE2E8F0.toInt(), 0xFFFFFFFF.toInt(), 0xFFF8FAFC.toInt())
            "dialga" -> listOf(0xB0021636.toInt(), 0xFF38BDF8.toInt(), 0xFF38BDF8.toInt(), 0xFFBAE6FD.toInt())
            "palkia" -> listOf(0xB0250624.toInt(), 0xFFF43F5E.toInt(), 0xFFFDA4AF.toInt(), 0xFFFCE7F3.toInt())
            "giratina" -> listOf(0xB0180407.toInt(), 0xFFDC2626.toInt(), 0xFFF87171.toInt(), 0xFFFEE2E2.toInt())
            else -> listOf(0xB0020B1E.toInt(), 0xFF38BDF8.toInt(), 0xFF7DD3FC.toInt(), 0xFFBAE6FD.toInt())
        }

        guiGraphics.fill(boxX, topY, boxX + boxWidth, topY + boxHeight, bgColor)
        guiGraphics.renderOutline(boxX, topY, boxWidth, boxHeight, borderColor)

        val remSec = VortexShaderController.remainingSecondsClient
        val timerColor = when {
            remSec <= 60 && (remSec % 2 == 0) -> 0xFFFF5555.toInt()
            remSec <= 60 -> 0xFFFFAA00.toInt()
            else -> defaultTimerColor
        }

        guiGraphics.drawCenteredString(
            font,
            timerText,
            screenWidth / 2,
            topY + 4,
            timerColor
        )

        guiGraphics.drawCenteredString(
            font,
            coordsText,
            screenWidth / 2,
            topY + 16,
            coordsColor
        )
    }
}
